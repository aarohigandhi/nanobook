# nanobook

A limit order book and matching engine in Java that never allocates.

Reconstructs the Nasdaq order book from raw TotalView-ITCH 5.0 exchange data,
then matches against it. The design constraint that drives everything here is
**zero allocation on the hot path** — no garbage means no collector, and that is
provable rather than assertable: the engine runs 1.5M operations under Epsilon
GC, a collector that never reclaims anything, in a 512 MB heap.

Built as a study of how exchange infrastructure actually works, with the
performance claims measured instead of asserted — including the one that turned
out to be wrong.

## Status

| Phase | | |
|---|---|---|
| 1 | ITCH 5.0 feed handler | **done** |
| 2 | Reference order book | **done** |
| 3 | Array-backed order book | **done** |
| 4 | Matching engine | **done** |
| 5 | Differential fuzzer | **done** |
| 6 | Benchmarks | **done** |

72 tests. 15,000,000 fuzzed operations against an independent reference engine,
zero divergences. Zero GC pauses across a 1.5M-operation measurement run, and
the engine survives the same run under a collector that never reclaims.

## Running it

Java 21+. No install step — the Gradle wrapper is committed.

```bash
./gradlew build
```

Then fetch a session file (see [data/README.md](data/README.md)) and replay it:

```bash
./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
```

To reconstruct one symbol's book across the whole session:

```bash
./gradlew replay --args="data/01302020.NASDAQ_ITCH50 --book AAPL"
```

## What is here

```
itch/         ITCH 5.0 wire format: layout table, parser, symbol interning
collections/  Primitive collections that exist to avoid boxing
book/         Two order books, a matching engine, and a reference engine
tools/        Message-mix statistics, book replay, differential fuzzer
bench/        JMH microbenchmarks and the open-loop latency harness
```

### Phase 1 — the feed handler

The file is memory-mapped rather than streamed, so decoding reads straight out
of the page cache with no copy and no syscall per message. Two details carry
most of the work:

**The 2 GiB problem.** A `MappedByteBuffer` is addressed by `int`, so a single
mapping cannot exceed 2 GiB, and a session file is several times that. The
parser slides a 1 GiB window and remaps — but a message must never straddle a
boundary, so the next window starts at the first byte of the message that did
not fit, not at the end of the previous one.

**Symbols are longs.** An ITCH stock field is exactly 8 bytes of right-padded
ASCII, and a `long` is exactly 8 bytes. So the raw symbol *is* a long: one
`getLong`, used directly as a hash key. No `String`, no `byte[]`, and symbol
comparison is a single 64-bit compare rather than a character loop.

Decoded fields reach the handler as primitives. Nothing is allocated per
message — at a few hundred million messages a day, one small object per message
is tens of gigabytes of garbage.

### The message mix

The first real output is a histogram of message types, and it is the number
that justifies the rest of the design:

| | |
|---|---|
| Order lifecycle (add / cancel / delete / replace) | *TBD — fill from a real session* |
| Executions | *TBD* |

Order lifecycle traffic dominates by a wide margin. Executions are comparatively
rare. So the hot path is **order lifecycle, not matching** — which means O(1)
cancel-by-order-reference matters far more than a clever matching loop, and
that is what the fast book is built around.

### Why a hand-written hash map

`HashMap<Long, Order>` boxes every order reference into a `Long` and allocates
a `Node` per entry, so a single order lookup costs two heap allocations and two
dereferences into unrelated cache lines. Given the message mix above, that is
squarely on the hot path — so replacing it should be a large throughput win.

It is not. That prediction is measured and refuted in
[Benchmarks](#the-hash-map-and-a-result-that-did-not-go-as-expected) below; the
map earns its place for a different reason than the one it was written for.

`LongIntHashMap` is open-addressed with linear probing over two flat primitive
arrays. Deletion uses **backward-shift** rather than tombstones: tombstones are
simpler, but they accumulate permanently under churn, and an ITCH session is
nothing but churn. Backward-shift repairs the probe chain on removal, so the
table stays as clean as if the removed key had never been inserted.

Hashing is a Fibonacci mix rather than raw masking, because ITCH order
references are sequential and sequential keys with a weak mixer collapse into
one enormous probe run.

### Phases 2 and 3 — two order books

`NaiveOrderBook` is a `TreeMap` of price to a `LinkedList` of orders per side,
with an `Order` object each and a `HashMap` for reference lookup. It is slow on
purpose. It exists to be read and believed.

`ArrayOrderBook` produces identical output with no objects at all. An order is
an index into parallel primitive arrays, drawn from a free list threaded through
`orderNext`. Price levels are indexed directly by tick offset from a base price
— one array load instead of a red-black descent with a cache miss per node. The
touch is cached and repaired incrementally: an add can only improve it, and a
removal only triggers a scan when it empties the touch level.

Prices far from the touch are legal and do occur, so the window regrows outward
and copies its levels rather than rejecting them. Order prices are stored rather
than tick indices precisely so that regrowing does not require touching every
live order.

Keeping both is not redundancy. The slow one is the oracle the fast one is
tested against, and a reference implementation you can read is worth more than
the throughput it costs.

### Phase 4 — matching engine

Price-time priority, with limit, market, IOC and FOK orders. Fills print at the
**maker's** resting price: the taker crossed the spread, so price improvement is
theirs. A fill-or-kill is decided before anything is touched, so a rejected one
leaves no partial fills to unwind.

A replacement is a genuinely new order — it joins the back of the queue and
crosses if it is marketable. It does not inherit the original's position. That
is why a trader reducing size cancels down instead of replacing, and it is the
rule this kind of engine most often gets wrong.

The engine is deterministic by construction: nothing reads a clock, hashes an
identity, or iterates a container with unspecified order. Same input, byte-
identical execution report stream, every run. Phase 5 depends entirely on that
holding, so there is a test that asserts it directly.

## Testing

```bash
./gradlew test
```

The parser tests encode messages byte by byte from the specification and assert
on the decoded fields. This is deliberate: an offset that is wrong by two bytes
still parses cleanly and produces plausible-looking prices and share counts. It
would silently poison every downstream result, and only a round-trip test
catches it.

The two order books are driven through identical randomized message streams and
compared after every single message — best prices, sizes, depth and order count.
The naive book is the oracle; any disagreement is a bug in the fast one. The
hash map gets the same treatment against `java.util.HashMap`.

The engine has an invariant test that asserts the book never rests crossed: any
bid at or above the best ask should have matched instead of resting, and a
cached touch that is repaired incorrectly shows up there first.

### Phase 5 — differential fuzzer

`ReferenceMatchingEngine` is a second, deliberately naive engine that shares no
code with the fast one. The fuzzer generates random order sequences, runs both,
and compares **execution report streams**.

Report streams rather than final book state, deliberately. Comparing books is
the obvious thing and it is much weaker: two engines can reach an identical
resting book having filled the orders in a different order, at different prices,
against different counterparties. The report stream is what a downstream
consumer would actually act on.

The generator is hostile on purpose — roughly one operation in twelve is
malformed (zero and negative quantities, non-positive prices, ids already
resting, references that never existed, prices far outside the tick window).
Validation and rejection paths are where two implementations drift apart, and a
generator that only emits valid orders never visits them.

A divergence is **shrunk** by delta debugging before it is reported, and repros
print as pasteable Java. A 500-operation failure is a haystack; the same failure
cut to three operations usually names the bug.

```bash
./gradlew fuzz --args="30000 500"
```

**Result: 30,000 sequences, 15,000,000 operations, zero divergences.**

## Benchmarks

Measured on Windows 11, Snapdragon X 10-core (aarch64), Temurin JDK 21.0.5.
JMH at 1 fork, 3×3s warmup, 5×3s measurement — below JMH's recommended fork
count, so the error bars are wide and the numbers are indicative, not decisive.

One "operation" is one book message for the book benchmarks, and one map call
(put, get or remove) for the map benchmarks. Worth stating explicitly, because
`@OperationsPerInvocation` is a compile-time constant that JMH trusts blindly:
declare the wrong count and every ns/op figure is silently rescaled by the ratio
while still looking entirely plausible. `MessageScriptTest` asserts the workload
generator emits exactly the count the annotation claims.

```bash
./gradlew jmh
./gradlew latency
```

### Book throughput

Replaying a balanced 200,000-operation lifecycle script, per operation:

| | ns/op |
|---|---|
| `NaiveOrderBook` | 512 ± 59 |
| `ArrayOrderBook` | **112 ± 56** |

Roughly 4.6× — flat arrays and an O(1) cancel against a tree walk and an object
per order. The fast book's error bar is half its own score, so read the ratio,
not the digits.

### The hash map, and a result that did not go as expected

| | ns/op |
|---|---|
| `LongIntHashMap` | 21.4 ± 18.3 |
| `HashMap<Long, Integer>` | 22.4 ± 4.8 |

**No measurable throughput difference.** The premise the map was written on —
that boxing every order reference would be visibly slower — does not survive
contact with a throughput microbenchmark. Short-lived boxed `Long`s are bump-
allocated in a TLAB and collected by a young-gen pass that costs almost nothing,
and this benchmark's phase-separated put/get/remove loops are unusually
cache-friendly.

The map still earns its place, but not for the reason it was written. What it
buys is **no garbage at all**, and that only shows up somewhere a throughput
average cannot see — the latency tail. Which is the actual result below.

### Latency

Open-loop against a fixed schedule, 1.5M operations at a 200k/sec target
against a book holding ~8,400 resting orders:

| percentile | service time |
|---|---|
| p50 | 300 ns |
| p90 | 500 ns |
| p99 | 800 ns |
| p99.9 | 6.2 µs |
| p99.99 | 119 µs |

`System.nanoTime()` costs ~68 ns per pair on this machine, so p50 is within a
few multiples of the measurement floor and should be read as such.

**Zero GC pauses across the entire measurement run**, and the same run survives
under Epsilon GC — a collector that never reclaims anything — in a 512 MB heap:

```bash
./gradlew latencyEpsilon
```

That is the zero-allocation claim proven rather than asserted. A run that
allocates in steady state dies with an `OutOfMemoryError`; this one finishes.

**What the far tail is.** Response-time percentiles past p99 run into
milliseconds, and with zero collector activity that is not this code — it is OS
scheduling and safepoints on a shared desktop. Chasing it further means pinning
the thread to an isolated core, which is a property of the deployment, not the
engine. Stated rather than trimmed, because a benchmark that quietly drops its
inconvenient percentiles is not a benchmark.

**On coordinated omission.** The harness reports service time and response time
side by side. Service time is the operation alone; response time is measured
from the *intended* send time, so an operation delayed by the one before it
carries that delay. A closed-loop harness — send, time, send the next when the
last returns — cannot see this: when the system stalls, it stalls too and simply
stops issuing the requests that would have been slow. The gap between those two
columns is exactly the error a naive harness reports as success.

## References

- Nasdaq, *TotalView-ITCH 5.0* specification
- Gil Tene, *How NOT to Measure Latency* — on coordinated omission

## License

MIT
