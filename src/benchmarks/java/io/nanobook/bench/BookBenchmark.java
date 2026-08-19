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

    @Benchmark
    @OperationsPerInvocation(100_000)
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
    @OperationsPerInvocation(100_000)
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
}
