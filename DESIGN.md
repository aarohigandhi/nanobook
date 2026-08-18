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
index and an int free-list for slot reuse. Arrays are sized at startup and
never grow mid-session.

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

Order reference lookup goes through `LongIntHashMap`. Given that lifecycle
traffic dominates the message mix, this is the largest measurable win over
Phase 2 — record the allocation-rate delta, not just the throughput delta.

| | Phase 2 | Phase 3 |
|---|---|---|
| Messages/sec | *TBD* | *TBD* |
| Allocation rate | *TBD* | *TBD* |
| GC events per session | *TBD* | *TBD* |

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

## Phase 5 — differential fuzzer

The books already have this: `OrderBookEquivalenceTest` drives both
implementations through randomized message streams and compares every
observable after every message. It found nothing on the first run, which says
more about the test than the code — the next step is to make it harder.

What remains is the engine-level version, which is the higher-value one:

- Write a deliberately naive `MatchingEngine` on top of `NaiveOrderBook`, then
  fuzz the two engines against each other on execution report streams rather
  than book state. Report streams catch ordering bugs that a state comparison
  cannot see — two engines can agree on the final book having filled in a
  different sequence.
- Shrink failing cases to a minimal repro automatically.
- Widen the generator past the current shape: zero and negative quantities,
  duplicate ids, self-crossing, replace chains, prices at the window edge.
- Record what it finds here, including bugs that turned out to be in the test.

Any two implementations of the same specification disagree somewhere. The
interesting question is where.

## Phase 6 — benchmarks

- **JMH** for microbenchmarks — handles JIT warmup and dead-code elimination.
- **HdrHistogram** for latency. p50 / p99 / p99.9 / p99.99 / max. Never averages.
- **Open-loop load generation** against a fixed schedule. Closed-loop drivers
  that send the next message only after the last returns silently hide latency
  under load — coordinated omission.
- **Epsilon GC** proves the zero-allocation claim. A no-op collector means a run
  that survives genuinely did not allocate, and one that allocates dies.
- Report cold and warm numbers separately; note where C2 kicks in.

Any bound the benchmark imposes — sampling, warmup discards, symbols excluded —
gets stated explicitly. Silent truncation reads as full coverage.
