package io.nanobook.bench;

import io.nanobook.book.ArrayOrderBook;
import io.nanobook.book.NaiveOrderBook;
import io.nanobook.book.OrderBook;
import io.nanobook.collections.LongIntHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Throughput comparison between the two book implementations, and between the
 * primitive hash map and {@code HashMap<Long, ...>}.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew jmh
 *   ./gradlew jmh --args="Book -f 1 -wi 3 -i 5"
 * </pre>
 *
 * <p>JMH is used rather than a hand-rolled timing loop because the two things
 * most likely to make a microbenchmark lie -- JIT warmup and dead code
 * elimination -- are handled properly. A loop that computes a result nothing
 * consumes gets compiled away entirely, and a naive harness reports the
 * resulting picoseconds as a triumph.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 1, jvmArgs = {"-Xmx2g"})
@Measurement(iterations = 5, time = 3)
@org.openjdk.jmh.annotations.Warmup(iterations = 3, time = 3)
public class BookBenchmark {

    @State(Scope.Thread)
    public static class Script {
        @Param({"200000"})
        public int operations;

        MessageScript script;
        NaiveOrderBook naive;
        ArrayOrderBook array;

        @Setup(Level.Iteration)
        public void setUp() {
            script = MessageScript.generate(20260818L, operations);
            naive = new NaiveOrderBook();
            array = new ArrayOrderBook();
        }
    }

    /**
     * The script is balanced, so both books end each invocation empty and the
     * next invocation starts from the same state.
     */
    @Benchmark
    @OperationsPerInvocation(200_000)
    public void naiveBook(Script state, Blackhole blackhole) {
        replay(state.naive, state.script, blackhole);
    }

    @Benchmark
    @OperationsPerInvocation(200_000)
    public void arrayBook(Script state, Blackhole blackhole) {
        replay(state.array, state.script, blackhole);
    }

    /**
     * Reads the touch after every message and consumes it. Without this the
     * benchmark would measure mutation only, and a book that never has its
     * best price queried is not a book anyone uses.
     */
    private static void replay(OrderBook book, MessageScript script, Blackhole blackhole) {
        for (int i = 0; i < script.length; i++) {
            script.applyTo(book, i);
            blackhole.consume(book.bestBid());
            blackhole.consume(book.bestAsk());
        }
    }

    // ---------------------------------------------------------------
    // The map that motivated the whole design
    // ---------------------------------------------------------------

    @State(Scope.Thread)
    public static class Keys {
        long[] references;
        LongIntHashMap primitive;
        Map<Long, Integer> boxed;

        @Setup(Level.Iteration)
        public void setUp() {
            Random random = new Random(7L);
            references = new long[100_000];
            for (int i = 0; i < references.length; i++) {
                // Sequential with gaps, as ITCH order references actually are.
                references[i] = i * 3L + random.nextInt(3);
            }
            primitive = new LongIntHashMap(references.length);
            boxed = new HashMap<>(references.length * 2);
        }
    }

    // 100_000 keys, each visited by all three loops: put, get, remove.
    @Benchmark
    @OperationsPerInvocation(300_000)
    public void primitiveMapChurn(Keys state, Blackhole blackhole) {
        LongIntHashMap map = state.primitive;
        for (int i = 0; i < state.references.length; i++) {
            map.put(state.references[i], i);
        }
        for (long reference : state.references) {
            blackhole.consume(map.get(reference));
        }
        for (long reference : state.references) {
            blackhole.consume(map.remove(reference));
        }
    }

    @Benchmark
    @OperationsPerInvocation(300_000)
    public void boxedMapChurn(Keys state, Blackhole blackhole) {
        Map<Long, Integer> map = state.boxed;
        for (int i = 0; i < state.references.length; i++) {
            map.put(state.references[i], i);
        }
        for (long reference : state.references) {
            blackhole.consume(map.get(reference));
        }
        for (long reference : state.references) {
            blackhole.consume(map.remove(reference));
        }
    }

    // ---------------------------------------------------------------
    // The same two maps, on the access pattern they actually see
    // ---------------------------------------------------------------

    /**
     * The churn benchmarks above are phase-separated: fill the map, read it all
     * back, then empty it. That is not what an ITCH session does, and it is the
     * worst case for backward-shift deletion specifically -- removing keys in
     * bulk means every removal repairs a cluster that the next removal is about
     * to disturb again.
     *
     * <p>Real traffic interleaves. The book holds a roughly constant number of
     * live orders and each message adds one, drops one, or looks one up. This
     * state replays exactly that, over a fixed key universe so the map ends each
     * invocation holding precisely the keys it started with.
     */
    @State(Scope.Thread)
    public static class Interleaved {
        static final byte PUT = 0;
        static final byte GET = 1;
        static final byte REMOVE = 2;

        /** Resting orders held at steady state. */
        static final int DEPTH = 100_000;
        static final int OPERATIONS = 300_000;

        byte[] kind;
        long[] key;
        long[] universe;

        LongIntHashMap primitive;
        Map<Long, Integer> boxed;

        @Setup(Level.Iteration)
        public void setUp() {
            Random random = new Random(11L);
            universe = new long[DEPTH];
            for (int i = 0; i < DEPTH; i++) {
                // Sequential with gaps, as ITCH order references are.
                universe[i] = i * 3L + random.nextInt(3);
            }

            kind = new byte[OPERATIONS];
            key = new long[OPERATIONS];

            long[] live = universe.clone();
            int liveCount = DEPTH;
            long[] removed = new long[DEPTH];
            int removedCount = 0;
            int n = 0;

            // A REMOVE costs two of the budget: itself, plus the PUT that must
            // restore the key before the script ends. Same accounting as
            // MessageScript, and the same reason -- the declared operation
            // count has to be the count actually replayed.
            while (n + removedCount < OPERATIONS) {
                int remaining = OPERATIONS - (n + removedCount);
                int roll = random.nextInt(100);

                if (remaining >= 2 && roll < 45 && liveCount > 0) {
                    int index = random.nextInt(liveCount);
                    kind[n] = REMOVE;
                    key[n++] = live[index];
                    removed[removedCount++] = live[index];
                    live[index] = live[--liveCount];
                } else if (roll < 90 && removedCount > 0) {
                    long restored = removed[--removedCount];
                    kind[n] = PUT;
                    key[n++] = restored;
                    live[liveCount++] = restored;
                } else {
                    kind[n] = GET;
                    key[n++] = live[random.nextInt(liveCount)];
                }
            }
            while (removedCount > 0) {
                long restored = removed[--removedCount];
                kind[n] = PUT;
                key[n++] = restored;
            }
            if (n != OPERATIONS) {
                throw new IllegalStateException("script is " + n + ", declared " + OPERATIONS);
            }

            primitive = new LongIntHashMap(DEPTH);
            boxed = new HashMap<>(DEPTH * 2);
            for (int i = 0; i < DEPTH; i++) {
                primitive.put(universe[i], i);
                boxed.put(universe[i], i);
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(Interleaved.OPERATIONS)
    public void primitiveMapInterleaved(Interleaved state, Blackhole blackhole) {
        LongIntHashMap map = state.primitive;
        for (int i = 0; i < state.kind.length; i++) {
            switch (state.kind[i]) {
                case Interleaved.PUT -> blackhole.consume(map.put(state.key[i], i));
                case Interleaved.GET -> blackhole.consume(map.get(state.key[i]));
                default -> blackhole.consume(map.remove(state.key[i]));
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(Interleaved.OPERATIONS)
    public void boxedMapInterleaved(Interleaved state, Blackhole blackhole) {
        Map<Long, Integer> map = state.boxed;
        for (int i = 0; i < state.kind.length; i++) {
            switch (state.kind[i]) {
                case Interleaved.PUT -> blackhole.consume(map.put(state.key[i], i));
                case Interleaved.GET -> blackhole.consume(map.get(state.key[i]));
                default -> blackhole.consume(map.remove(state.key[i]));
            }
        }
    }
}
