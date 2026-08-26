# Design notes

Decisions, and the reasoning behind them. Each phase gets its measured result
recorded here as it lands — including the ones that did not work.

## Phase 1 — feed handler ✅

**Memory-map, don't stream.** Decoding reads out of the page cache with no
intermediate copy and no read syscall per message.

**Slide the window.** `MappedByteBuffer` is `int`-addressed, capping a single
mapping at 2 GiB against session files several times larger. The parser maps
1 GiB at a time. The subtlety: a message must never straddle a boundary, so
after draining a window the next one starts at the first byte of the message
that did not fit — not at the end of the previous window.

**Symbols are longs.** An 8-byte right-padded ASCII stock field is exactly one
`long`. Use it directly as a hash key: no `String`, no `byte[]`, and comparison
is one 64-bit compare.

**Message lengths in a flat table.** `int[128]` indexed by the type byte. One
array load, no branch chain. A declared length that disagrees with the spec is
a fatal stream error, not something to skip past — misalignment that goes
unreported produces plausible garbage for the rest of the file.

**Primitives across the handler boundary.** No message object per callback.
At a few hundred million messages per session, one small object per message is
tens of gigabytes of garbage.

## Phase 2 — reference book ✅

Write the slow, obvious one first: `TreeMap<Integer, LinkedList<Order>>` per
side plus `HashMap<Long, Order>` for lookup. Boxing and pointer chasing are
fine here; legibility is the point.

Keep it forever. It is the oracle Phase 5 fuzzes against, and every Phase 3
optimization is measured relative to it.

Rules that are easy to get wrong:

- **Replace loses queue priority.** The replacement joins the back of the queue
  at its price level. It does not inherit the original's position.
- **Remove empty price levels.** Leave one behind and `bestBid()` starts
  returning a price with nothing resting at it.
- **Trade and CrossTrade never touch the visible book.** They are hidden and
  auction liquidity.
- **Unknown order references are normal.** A session file does not begin at the
  true start of the book, so some executes and deletes reference orders never
  seen. Skip them — but count them. A large count means the parser is
  misaligned, not the data.

Milestone: replay a full day and reconcile reconstructed executions against the
stream's own Trade messages.

## Phase 3 — fast book ✅

Structure of arrays, not array of structures. No `Order` class: an order is an
index into parallel `int[]` arrays, with an intrusive doubly-linked list by
index and an int free-list for slot reuse. Arrays are sized at startup and grow
only by doubling, which in practice means during warmup and never again.

Price levels indexed directly by tick offset rather than through a tree — one
array load instead of a red-black descent with a cache miss per node.

**Prices outside the window: regrow, don't overflow.** Prices far from the touch
are legal and do occur. The two honest options were a secondary overflow map or
widening the array. The overflow map was rejected: it would put a second lookup
path on the hot side of every query and, worse, split best-price tracking across
two structures. Instead the window doubles outward and copies its levels. That
allocates, so it is not free — but it happens a handful of times during warmup
and never in steady state, and `levelRegrowths()` reports the count so the claim
is checkable rather than assumed.

**Store prices, not ticks, per order.** This is what makes regrowing cheap: tick
indices would all shift when the base moves, forcing a pass over every live
order. Prices do not move, so the regrow only touches the level arrays.

**Tick alignment throws rather than rounds.** Prices must be a multiple of
`tickSize` (default 100 raw units, one cent). Sub-dollar names quote in
sub-pennies and need `tickSize = 1`. Silently snapping a price to the grid would
corrupt the book in a way that still looks entirely plausible, which is the
worst possible failure mode here.

Cache the touch. Adds can only improve it (one compare); removals only need a
scan when the touch level empties, and then only outward, one tick at a time.

Order reference lookup goes through `LongIntHashMap`.

**This is where the design was wrong.** The map was written on the premise that
boxing every order reference into a `Long` would be visibly slower, and that
this would be the largest single win over Phase 2. Measured head to head against
`HashMap<Long, Integer>`, it is 21.4 ns/op against 22.4 — no difference at all,
the gap being a fraction of the run-to-run error.
Short-lived boxed `Long`s are bump-allocated in a TLAB and reclaimed by a
young-gen pass that costs almost nothing, and a phase-separated
put/get/remove benchmark is about as cache-friendly as that access pattern gets.

The map stays, but the justification changes. What it actually buys is that it
produces **no garbage**, and that pays off somewhere a throughput average
cannot see: the latency tail, and the ability to run the whole engine under a
collector that never reclaims. The original reasoning was a guess that happened
to reach a defensible structure for the wrong reason.

Measured, per operation over a balanced 200k-operation script:

| | Phase 2 (naive) | Phase 3 (array) |
|---|---|---|
| ns/op | 512 ± 59 | **112 ± 56** |
| GC pauses in steady state | many | **zero** |

About 4.6x. The wide error bars are a single JMH fork; treat the ratio as
indicative rather than decisive.

## Phase 4 — matching engine ✅

Price-time priority. Limit, market, IOC, FOK. Cancel and cancel/replace. Emits
an execution report stream.

**Fills print at the maker's price.** The taker crossed the spread, so any price
improvement is theirs. Printing at the taker's limit instead is the classic
tell that an engine was written without reference to how exchanges actually
work.

**Fill-or-kill is decided before anything is touched.** Liquidity is totalled
across marketable levels first; if it falls short the order is rejected outright.
The alternative — fill greedily, then unwind — means emitting fill reports that
have to be retracted, and downstream consumers that already acted on them.

**The engine drives `ArrayOrderBook` through a package-private surface** rather
than carrying its own copy of the array plumbing. Two implementations of the
same bookkeeping would drift.

**Replace is a new order**, not a repriced one: back of the queue, and it
crosses if marketable. There is a test asserting an order that arrived *after*
the original now fills ahead of the replacement.

**Determinism is a hard requirement**: identical input sequence produces
byte-identical output, every run. Nothing reads a clock, hashes an identity, or
iterates a container with unspecified order. Phase 5 is impossible without it,
so there is a test that runs a scripted session six times and compares streams.

Single-threaded on purpose. An exchange core that has to synchronize has already
lost the latency argument; concurrency belongs at the edges.

## Phase 5 — differential fuzzer ✅

`ReferenceMatchingEngine` is an independent naive engine sharing no code with
the fast one. The fuzzer compares **execution report streams**, not final book
state: two engines can reach an identical resting book having filled orders in a
different sequence, at different prices, against different counterparties.

**The generator is hostile by design.** Roughly one operation in twelve is
malformed. Validation and rejection paths are where two implementations drift,
and a generator that only emits well-formed orders never reaches them.

**Shrinking is not optional.** Delta debugging cuts a failing sequence down for
as long as the divergence survives, and repros render as pasteable Java. The
shrinker takes its predicate as a parameter rather than hard-wiring
`diverges`, so it can be tested on its own — a shrinker that quietly returns
its input is indistinguishable from one that works until the day it matters.

**Result: 30,000 sequences, 15,000,000 operations, zero divergences.**

Nothing found is a weak result, not a strong one. It bounds the bug rate at
whatever this generator can reach and no further. The honest next moves are
multi-symbol sequences, self-crossing, and deeper replace chains.

## Phase 6 — benchmarks ✅

Windows 11, Snapdragon X 10-core (aarch64), Temurin 21.0.5.

**Latency, open-loop, 1.5M operations at 200k/sec against a ~8,400-order book:**

| percentile | service time |
|---|---|
| p50 | 300 ns |
| p90 | 500 ns |
| p99 | 800 ns |
| p99.9 | 6.2 µs |
| p99.99 | 119 µs |

`System.nanoTime()` costs ~68 ns per pair here, so p50 sits within a few
multiples of the measurement floor and should be read as such.

**Zero GC pauses across the measurement run**, and it survives under Epsilon GC
in a 512 MB heap. That is the zero-allocation claim proven rather than asserted:
a steady state that allocates dies with an `OutOfMemoryError` under a collector
that never reclaims.

**The workload had to be fixed before the number meant anything.** The first
version of the harness only cancelled from a small ring of recent order ids, so
older orders accumulated forever, the book's arrays kept doubling and the hash
map kept rehashing. The result was a latency report dominated by multi-
millisecond GC pauses — a real measurement of a workload nobody runs. Holding
the book at a steady depth is what made the tail mean anything.

**What the far tail is.** Response-time percentiles past p99 reach milliseconds
with zero collector activity, which makes them OS scheduling and safepoints on a
shared desktop rather than this code. Reported, not trimmed. Chasing it further
means pinning to an isolated core, which is a deployment property.

**Coordinated omission** is why service time and response time are reported side
by side. A closed-loop driver stalls when the system stalls and stops issuing the
requests that would have been slow, so its numbers improve exactly as things get
worse. The gap between the two columns is that error, made visible.

Every bound the benchmarks impose is stated: single JMH fork (below the
recommended count, hence wide error bars), a fixed 200k-operation script, one
symbol, warmup discarded. Silent truncation reads as full coverage.
