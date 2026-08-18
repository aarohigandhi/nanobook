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
| 2 | Reference order book | in progress |
| 3 | Array-backed order book | — |
| 4 | Matching engine | — |
| 5 | Differential fuzzer | — |
| 6 | Benchmarks | — |

Results tables below are filled in as phases land. Nothing is quoted before it
is measured.

## Running it

Java 21+. No install step — the Gradle wrapper is committed.

```bash
./gradlew build
```

Then fetch a session file (see [data/README.md](data/README.md)) and replay it:

```bash
./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
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

## Testing

```bash
./gradlew test
```

The parser tests encode messages byte by byte from the specification and assert
on the decoded fields. This is deliberate: an offset that is wrong by two bytes
still parses cleanly and produces plausible-looking prices and share counts. It
would silently poison every downstream result, and only a round-trip test
catches it.

The hash map is checked against `java.util.HashMap` under randomized churn —
the same differential technique Phase 5 applies to the book itself.

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
