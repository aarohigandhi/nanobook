# nanobook

A limit order book and matching engine in Java that never allocates.

Reconstructs the Nasdaq order book from raw TotalView-ITCH 5.0 exchange data,
then matches against it. The design constraint that drives everything here is
**zero allocation on the hot path** — no garbage means no collector, which is
provable rather than assertable: the engine replays a full trading session under
Epsilon GC, a collector that never reclaims anything.

Built as a study of how exchange infrastructure actually works, on real market
data, with the performance claims measured instead of asserted.

## Status

| Phase | | |
|---|---|---|
| 1 | ITCH 5.0 feed handler | **done** |
| 2 | Reference order book | **done** |
| 3 | Array-backed order book | **done** |
| 4 | Matching engine | **done** |
| 5 | Differential fuzzer | in progress |
| 6 | Benchmarks | — |

63 tests, including a randomized differential check that the two order book
implementations cannot be told apart. Results tables below are filled in as
phases land — nothing is quoted before it is measured.

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
book/         Order book — reference implementation and fast implementation
tools/        Message-mix statistics
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
squarely on the hot path.

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

## Benchmarks

*Phase 6.* Reported as p50 / p99 / p99.9 / p99.99 / max — never averages.
Load is generated open-loop against a fixed schedule to avoid coordinated
omission. Microbenchmarks run under JMH so JIT warmup and dead-code elimination
are handled properly.

The zero-allocation claim is proven, not asserted:

```bash
./gradlew replayEpsilon --args="data/01302020.NASDAQ_ITCH50"
```

Epsilon GC never reclaims anything. A run that survives to the end genuinely did
not allocate; a run that allocates dies with an `OutOfMemoryError`.

## References

- Nasdaq, *TotalView-ITCH 5.0* specification
- Gil Tene, *How NOT to Measure Latency* — on coordinated omission

## License

MIT
