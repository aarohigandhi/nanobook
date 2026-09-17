# nanobook

A limit order book and matching engine in Java that never allocates.

It reads raw Nasdaq TotalView-ITCH 5.0 files, rebuilds the order book, and
matches against it. The design constraint is zero allocation on the hot path.
No garbage means no collector, and that gets proven rather than claimed: a
full 268M-message session replays under Epsilon GC, a collector that never
reclaims anything, finishing with 95 MB used.

Validated on a real session: 268,744,780 messages from 2019-12-30, 8.25 GB,
parsed at 7.8M msg/s. That session also falsified one of the design's core
assumptions, written up in [What real data broke](#what-real-data-broke).

87 tests. 15M fuzzed operations against an independent reference engine, zero
divergences.

## Running it

Java 21+. The Gradle wrapper is committed, so there is no install step.

```bash
./gradlew build
```

Fetch a session file (see [data/README.md](data/README.md)), then:

```bash
./gradlew replay --args="data/12302019.NASDAQ_ITCH50"
```

```bash
./gradlew replay --args="data/12302019.NASDAQ_ITCH50 --book AAPL"
```

Other tasks: `test`, `fuzz`, `jmh`, `latency`, `latencyEpsilon`, `replayEpsilon`.

## Layout

```
itch/         ITCH 5.0 wire format: layout table, parser, symbol interning
collections/  Primitive collections that exist to avoid boxing
book/         Two order books, a matching engine, and a reference engine
tools/        Message-mix statistics, book replay, differential fuzzer
bench/        JMH microbenchmarks and the open-loop latency harness
```

## The feed handler

The file is memory-mapped, so decoding reads out of the page cache with no copy
and no syscall per message. Two details carry most of the work.

**The 2 GiB problem.** A `MappedByteBuffer` is `int`-addressed, so one mapping
caps at 2 GiB and a session file is several times that. The parser slides a
1 GiB window. A message must never straddle a boundary, so the next window
starts at the first byte of the message that did not fit, not at the end of the
previous one.

**Symbols are longs.** An ITCH stock field is 8 bytes of right-padded ASCII, and
a `long` is 8 bytes. So the symbol *is* a long: one `getLong`, used directly as
a hash key. No `String`, no `byte[]`, and comparison is a single 64-bit compare.

Decoded fields reach the handler as primitives. At a few hundred million
messages a day, one small object per message is tens of gigabytes of garbage.

## The message mix

From the full 2019-12-30 session, 268,744,780 messages across 8,906 symbols:

| message | count | share |
|---|---:|---:|
| `AddOrder` | 118,631,456 | 44.14% |
| `OrderDelete` | 114,360,997 | 42.55% |
| `OrderReplace` | 21,639,067 | 8.05% |
| `OrderExecuted` | 5,722,824 | 2.13% |
| `NetOrderImbalanceIndicator` | 4,024,315 | 1.50% |
| `OrderCancel` | 2,787,676 | 1.04% |
| `Trade` | 1,218,602 | 0.45% |
| everything else | 359,843 | 0.13% |

Order lifecycle traffic is **95.79%** of the session. Executions are **2.17%**.
Adds and deletes nearly cancel out: 118.6M posted against 114.4M withdrawn.
Most orders that reach the book never trade.

So the hot path is order lifecycle, not matching. O(1) cancel-by-order-reference
matters more than a clever matching loop, and that is what the fast book is
built around. Tuning the matching loop would have tuned 2% of the traffic.

## Rebuilding a real book

`--book AAPL` over the same session:

| | |
|---|---:|
| top-of-book changes | 152,427 |
| mean quoted spread | $0.0350 |
| **locked or crossed quotes** | **0** |
| **unknown order references** | **0** |
| orders resting at close | 0 |
| orders held off-band | 19 |

Zero unknown references across 268.7M messages means every delete, execution and
replace resolved to an order the parser had already seen. One wrong field offset
in the add path would have orphaned millions.

Zero locked or crossed quotes means the book never rested with a bid at or above
the ask. That is the invariant a mis-repaired cached touch breaks first.

The $0.035 mean spread is the sanity check: about three and a half cents on a
$289 stock, which is what AAPL traded at.

### Sub-penny symbols

`AAU` averages $0.7092 and quotes 98.5% of its prices off the penny grid. It is
the case `--tick 1` exists for, and the two runs show what the parameter costs:

| `--book AAU` | off-band | top-of-book changes | mean spread |
|---|---:|---:|---:|
| `--tick 100` (default) | 6,222 of 6,265 | 93 | $0.0607 |
| `--tick 1` | **1** | **1,448** | **$0.0273** |

At the wrong tick the book degrades instead of failing: nearly everything goes
off-band and the count says so. At the right one it reconstructs cleanly. Both
runs report zero unknown references and zero crossed quotes.

### The whole session, under a collector that never reclaims

```bash
./gradlew replayEpsilon --args="data/12302019.NASDAQ_ITCH50 --book AAPL"
```

268,744,780 messages parsed and AAPL rebuilt under Epsilon GC in a 1 GB heap,
finishing with **95 MB used**. One 16-byte object per message would have been
4.3 GB and killed the run. This covers the feed handler and the book, not just
the engine.

Warm page cache, it parses at 9.8M msg/s.

## What real data broke

The tick-indexed level array is the core of the fast book, and the real session
falsified the assumption behind it. AAPL's 698,744 add orders that day:

| | ticks at tick=100 |
|---|---:|
| p1-p99 | 7,942 |
| p0.1-p99.9 | 21,654 |
| full range | 19,999,899 |

99.8% of the session fits in 21,654 ticks. The rest, sub-penny stink bids at
$0.0001 and a sell at $199,999.00 against a $288.91 mean, drags the window to
20 million ticks and 79 MB per side, for orders that never trade. Thirteen
orders out of 698,744 killed the whole replay, because the original code treated
an off-grid price as fatal.

No synthetic workload finds this. You do not generate a $199,999 order on a $289
stock. The exchange does.

The window is now capped. An order that cannot be represented is held
**off-band**: it keeps its slot and stays addressable by reference, so a later
delete or execution still resolves, but it rests in no price level and never
reaches the touch. The count is reported, since a silently dropped order looks
identical to one parsed wrong.

## Two order books

`NaiveOrderBook` is a `TreeMap` of price to a `LinkedList` per side, an `Order`
object each, and a `HashMap` for lookup. It is slow on purpose, and exists to be
read and believed.

`ArrayOrderBook` produces identical output with no objects. An order is an index
into parallel primitive arrays, drawn from a free list threaded through
`orderNext`. Price levels are indexed by tick offset, so finding one is an array
load instead of a red-black descent with a cache miss per node. The touch is
cached and repaired incrementally: an add can only improve it, and a removal
only triggers a scan when it empties the touch level.

The slow one is the oracle the fast one is tested against.

## The matching engine

Price-time priority, with limit, market, IOC and FOK orders. Fills print at the
maker's resting price, since the taker crossed the spread. A fill-or-kill is
decided before anything is touched, so a rejected one leaves no partial fills to
unwind.

A replacement is a new order: it joins the back of the queue rather than
inheriting the original's position. That is why a trader reducing size cancels
down instead of replacing, and it is the rule this kind of engine most often
gets wrong.

The engine is deterministic. Nothing reads a clock, hashes an identity, or
iterates a container with unspecified order, so the same input gives a
byte-identical report stream. The fuzzer depends on that, and a test asserts it.

## Testing

```bash
./gradlew test
```

Parser tests encode messages byte by byte from the spec and assert on decoded
fields. An offset wrong by two bytes still parses cleanly and produces plausible
prices, so only a round-trip test catches it.

The two books are driven through identical randomized streams and compared after
every message. The hash map gets the same treatment against `java.util.HashMap`.

### Differential fuzzer

`ReferenceMatchingEngine` is a second, deliberately naive engine sharing no code
with the fast one. The fuzzer generates random sequences, runs both, and
compares execution report streams.

Report streams rather than final book state. Two engines can reach an identical
resting book having filled orders in a different order, at different prices,
against different counterparties.

About one generated operation in twelve is malformed: zero and negative
quantities, non-positive prices, ids already resting, references that never
existed. Validation paths are where implementations drift apart, and a generator
emitting only valid orders never visits them.

Divergences are shrunk by delta debugging, and repros print as pasteable Java.

```bash
./gradlew fuzz --args="30000 500"
```

30,000 sequences, 15,000,000 operations, zero divergences, 16.5s.

## Benchmarks

Windows 11, Snapdragon X 10-core (aarch64), Temurin 21.0.5. JMH at 3 forks,
3x3s warmup, 5x3s measurement.

One operation is one book message, or one map call.
`@OperationsPerInvocation` is a compile-time constant JMH trusts blindly, so
`MessageScriptTest` asserts the workload generator emits exactly the declared
count. It did not, once, and every ns/op figure was silently rescaled by the
ratio.

### Book throughput

| | ns/op |
|---|---|
| `NaiveOrderBook` | 748 +/- 323 |
| `ArrayOrderBook` | **119 +/- 14** |

Roughly 6x. The naive book's error bar is 43% of its own score, which is the
result rather than a measurement failure: it allocates, so GC lands
unpredictably inside the measurement. The array book's error is 12%.

### The hash map, and why the benchmark decides the answer

`HashMap<Long, Order>` boxes every order reference and allocates a `Node` per
entry. Replacing it with an open-addressed primitive map should be a large
throughput win. Whether it is depends entirely on which benchmark you run.

| | `LongIntHashMap` | `HashMap<Long, Integer>` |
|---|---|---|
| phase-separated churn | 28.9 +/- 0.7 | **20.5 +/- 1.4** |
| interleaved, ITCH-like | **56.8 +/- 5.4** | 75.8 +/- 22.1 |

The first fills the map, reads it all back, then empties it. That is the worst
case for backward-shift deletion specifically, because bulk removal means every
removal repairs a cluster the next one disturbs again. There the boxed
`HashMap` wins by 40%.

The second interleaves adds, lookups and cancels at constant depth, which is
what the message mix above says actually happens. There the primitive map wins
by 25%, with an error bar four times tighter, because boxing allocates and GC
lands unpredictably.

The map was originally justified on throughput, and that justification was a
guess. What it reliably buys is no garbage, which shows up in the latency tail
rather than in a throughput average.

### Latency

Open-loop against a fixed schedule, 1.5M operations at 200k/sec, book holding
~8,400 resting orders.

| percentile | service (ns) | response (ns) |
|---|---:|---:|
| p50 | 300 | 500 |
| p90 | 600 | 13,703 |
| p99 | 1,200 | 8,970,239 |
| p99.9 | 12,007 | 39,354,367 |
| p99.99 | 134,015 | 42,860,543 |
| max | 16,203,775 | 43,515,903 |

**Zero GC pauses across the entire run**, and the same run survives under
Epsilon GC in a 512 MB heap:

```bash
./gradlew latencyEpsilon
```

A run that allocates in steady state dies with an `OutOfMemoryError`. This one
finishes. That is the zero-allocation claim proven rather than asserted.

`System.nanoTime()` costs ~68 ns per pair here, so p50 sits within a few
multiples of the measurement floor.

Service time is the operation alone. Response time is measured from the intended
send time, so an operation delayed by the one before it carries that delay. A
closed-loop harness cannot see this: when the system stalls it stalls too and
stops issuing the requests that would have been slow. The gap between the two
columns is that error. Here 10.5% of operations missed their slot.

The far tail is not this code. With zero collector activity, a 16 ms max is OS
scheduling and safepoints on a shared desktop. Stated rather than trimmed.

## References

- Nasdaq, *TotalView-ITCH 5.0* specification
- Gil Tene, *How NOT to Measure Latency*, on coordinated omission

## License

MIT
