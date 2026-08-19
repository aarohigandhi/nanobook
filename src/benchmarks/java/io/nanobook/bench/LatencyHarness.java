package io.nanobook.bench;

import io.nanobook.book.ArrayOrderBook;
import io.nanobook.book.ExecutionListener;
import io.nanobook.book.MatchingEngine;
import io.nanobook.book.MatchingEngine.TimeInForce;
import org.HdrHistogram.Histogram;

import java.util.Random;

/**
 * Open-loop latency measurement for {@link MatchingEngine}. Phase 6.
 *
 * <h2>Coordinated omission</h2>
 * The obvious way to measure this is a closed loop: send an order, time it,
 * send the next one as soon as the last returns. That harness silently lies.
 * When the system stalls, a closed-loop driver stalls with it and simply stops
 * sending — so the requests that would have been slow are never issued, and
 * never recorded. The measurement improves precisely when the system gets
 * worse.
 *
 * <p>This harness is open-loop instead. Every operation has an <b>intended</b>
 * send time fixed in advance by the target rate, and latency is measured from
 * that intended time, not from when the harness got around to it. An operation
 * delayed because the previous one ran long carries that delay in its number.
 * That is the honest figure, and it is the one that diverges from the naive
 * measurement exactly where it matters -- in the tail.
 *
 * <p>Both are reported side by side, because the gap between them is the point.
 *
 * <pre>
 *   ./gradlew latency
 *   ./gradlew latency --args="5000000 500000"
 * </pre>
 */
public final class LatencyHarness {

    private static final int LIMIT = 0;
    private static final int CANCEL = 1;

    /** Steady-state resting depth the workload holds the book at. */
    private static final int TARGET_DEPTH = 20_000;

    private LatencyHarness() {}

    /** Pre-generated engine operations, as primitive arrays. */
    private static final class Workload {
        final byte[] kind;
        final long[] id;
        final boolean[] buy;
        final int[] price;
        final int[] shares;
        final int length;

        Workload(int length) {
            this.length = length;
            this.kind = new byte[length];
            this.id = new long[length];
            this.buy = new boolean[length];
            this.price = new int[length];
            this.shares = new int[length];
        }

        /**
         * Holds book depth near {@code targetDepth} by cancelling as often as
         * it adds once that depth is reached.
         *
         * <p>This matters more than it looks. An earlier version of this
         * harness only ever cancelled from a small ring of recent ids, so older
         * orders accumulated forever, the book's arrays kept doubling, and the
         * hash map kept rehashing. The resulting latency report was dominated
         * by multi-millisecond GC pauses -- a real measurement of a workload
         * nobody runs. Steady state is the thing worth measuring, so the
         * workload has to actually reach one.
         */
        static Workload generate(long seed, int length, int targetDepth) {
            Random random = new Random(seed);
            Workload workload = new Workload(length);
            long[] live = new long[targetDepth * 2];
            int liveCount = 0;
            long nextId = 1;

            for (int i = 0; i < length; i++) {
                boolean cancel = liveCount >= targetDepth
                        || (liveCount > targetDepth / 2 && random.nextBoolean());

                if (cancel && liveCount > 0) {
                    int index = random.nextInt(liveCount);
                    workload.kind[i] = CANCEL;
                    workload.id[i] = live[index];
                    live[index] = live[--liveCount];
                } else {
                    long id = nextId++;
                    boolean buy = random.nextBoolean();
                    workload.kind[i] = LIMIT;
                    workload.id[i] = id;
                    workload.buy[i] = buy;
                    // Bands overlap slightly, so most orders rest and a minority
                    // cross. An all-resting workload never exercises matching.
                    workload.price[i] = buy
                            ? 9_950 + random.nextInt(56)
                            : 9_995 + random.nextInt(56);
                    workload.shares[i] = 100 * (1 + random.nextInt(20));
                    live[liveCount++] = id;
                }
            }
            return workload;
        }
    }

    public static void main(String[] args) {
        int operations = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        int targetRate = args.length > 1 ? Integer.parseInt(args[1]) : 250_000;

        System.out.printf("open-loop latency: %,d operations at %,d/sec%n", operations, targetRate);
        System.out.printf("clock overhead:    %d ns per System.nanoTime() pair%n%n", clockOverhead());

        // One workload, one engine. The warmup prefix both compiles the hot
        // path and fills the book to its steady-state depth; measurement then
        // continues on the same engine rather than starting from an empty book
        // and paying for its growth.
        int warmup = Math.min(operations, 1_000_000);
        Workload workload = Workload.generate(20260818L, warmup + operations, TARGET_DEPTH);
        MatchingEngine engine = newEngine();

        for (int i = 0; i < warmup; i++) {
            apply(engine, workload, i);
        }
        System.out.printf("warmed up on %,d operations, book at %,d resting orders%n",
                warmup, engine.book().orderCount());

        Histogram responseTime = new Histogram(1, 60_000_000_000L, 3);
        Histogram serviceTime = new Histogram(1, 60_000_000_000L, 3);

        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();

        long intervalNanos = 1_000_000_000L / targetRate;
        long startedAt = System.nanoTime();
        long behind = 0;

        for (int i = 0; i < operations; i++) {
            long intendedAt = startedAt + i * intervalNanos;
            int op = warmup + i;

            long now = System.nanoTime();
            if (now < intendedAt) {
                while (System.nanoTime() < intendedAt) {
                    Thread.onSpinWait();
                }
            } else if (now > intendedAt + intervalNanos) {
                behind++;
            }

            long before = System.nanoTime();
            apply(engine, workload, op);
            long after = System.nanoTime();

            // Response time includes the wait for a slot that had already
            // passed. Service time is the operation alone.
            responseTime.recordValue(Math.max(1, after - intendedAt));
            serviceTime.recordValue(Math.max(1, after - before));
        }

        long elapsed = System.nanoTime() - startedAt;
        double achievedRate = operations / (elapsed / 1e9);

        System.out.printf("%nachieved %,.0f ops/sec over %.2fs%n", achievedRate, elapsed / 1e9);
        System.out.printf("%,d operations (%.2f%%) could not be issued on schedule%n%n",
                behind, 100.0 * behind / operations);

        System.out.printf("  %-12s %14s %14s%n", "percentile", "service (ns)", "response (ns)");
        System.out.println("  " + "-".repeat(42));
        for (double percentile : new double[]{50, 90, 99, 99.9, 99.99}) {
            System.out.printf("  p%-11s %14s %14s%n",
                    trim(percentile),
                    group(serviceTime.getValueAtPercentile(percentile)),
                    group(responseTime.getValueAtPercentile(percentile)));
        }
        System.out.printf("  %-12s %14s %14s%n", "max",
                group(serviceTime.getMaxValue()), group(responseTime.getMaxValue()));
        System.out.println();
        System.out.println("  service  = the operation alone");
        System.out.println("  response = measured from the intended send time, so queueing counts");
        System.out.println("  the gap between the two columns IS coordinated omission");

        long collections = gcCount() - gcCountBefore;
        long pausedMillis = gcMillis() - gcMillisBefore;
        System.out.printf("%n  %d GC pauses totalling %d ms during measurement (%.2f%% of wall time)%n",
                collections, pausedMillis, 100.0 * pausedMillis / (elapsed / 1e6));
        if (collections > 0) {
            System.out.println("  ^ the far tail belongs to the collector, not the engine.");
            System.out.println("    p50/p90 are the engine; anything past p99 is contaminated.");
        } else {
            System.out.println("  ^ no collector activity at all: the hot path allocated nothing.");
            System.out.println("    Whatever is in the far tail, it is not garbage collection.");
            System.out.println("    On a shared desktop that residue is OS scheduling and");
            System.out.println("    safepoints. Pin the thread on an isolated core to chase it.");
        }
        System.out.printf("%n  engine filled %,d orders%n%n", engine.fills());
    }

    private static long gcCount() {
        long total = 0;
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0;
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long millis = bean.getCollectionTime();
            if (millis > 0) total += millis;
        }
        return total;
    }

    private static MatchingEngine newEngine() {
        return new MatchingEngine(new ExecutionListener() {},
                new ArrayOrderBook(ArrayOrderBook.ANY_STOCK, 1, 1 << 14, 1 << 20));
    }

    private static void apply(MatchingEngine engine, Workload workload, int index) {
        if (workload.kind[index] == LIMIT) {
            engine.limit(workload.id[index], workload.buy[index],
                    workload.price[index], workload.shares[index], TimeInForce.GTC);
        } else {
            engine.cancel(workload.id[index]);
        }
    }

    /**
     * The measurement floor. At sub-microsecond operations the timer itself is
     * a meaningful share of the reading, and a latency report that does not say
     * so is overstating its own precision.
     */
    private static long clockOverhead() {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 10; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                System.nanoTime();
            }
            long elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed / 100_000);
        }
        return best;
    }

    private static String trim(double percentile) {
        return percentile == Math.floor(percentile)
                ? String.valueOf((long) percentile)
                : String.valueOf(percentile);
    }

    private static String group(long value) {
        return String.format("%,d", value);
    }
}
