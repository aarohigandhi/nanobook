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

## Phase 2 — reference book

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

## Phase 3 — fast book

Structure of arrays, not array of structures. No `Order` class: an order is an
index into parallel `int[]` arrays, with an intrusive doubly-linked list by
index and an int free-list for slot reuse. Arrays are sized at startup and
never grow mid-session.

Price levels indexed directly by tick offset rather than through a tree — one
array load instead of a red-black descent with a cache miss per node. Prices
outside the window are legal and do occur; decide deliberately between widening
the window and keeping an overflow map, and say which in the writeup.

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

## Phase 4 — matching engine

Price-time priority. Limit, market, IOC, FOK. Cancel and cancel/replace. Emits
an execution report stream.

**Determinism is a hard requirement**: identical input sequence produces
byte-identical output, every run. Phase 5 is impossible without it.

## Phase 5 — differential fuzzer

Generate random but valid order sequences. Feed each to both the Phase 2 and
Phase 4 engines. Assert the output streams match exactly. Run millions of
iterations, shrink any failing case to a minimal repro, and record what it
found in this file.

Roughly 200 lines, and the highest-value phase in the project. Any two
implementations of the same specification disagree somewhere; the interesting
question is where.

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
