package io.nanobook.tools;

import io.nanobook.book.ArrayOrderBook;
import io.nanobook.book.MatchingEngine;
import io.nanobook.book.MatchingEngine.TimeInForce;
import io.nanobook.book.RecordingListener;
import io.nanobook.book.ReferenceMatchingEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Differential fuzzer for {@link MatchingEngine}. Phase 5.
 *
 * <p>Generates random but well-formed order sequences, feeds each to both the
 * fast engine and {@link ReferenceMatchingEngine}, and asserts the execution
 * report streams are identical.
 *
 * <p><b>Why report streams and not book state.</b> Comparing final books is the
 * obvious thing and it is much weaker. Two engines can arrive at exactly the
 * same resting book having filled the orders in a different sequence, at
 * different prices, against different counterparties. The report stream is the
 * engine's actual output and the thing a downstream consumer would act on, so
 * that is what has to match.
 *
 * <p>When a divergence is found it is <b>shrunk</b> by delta debugging: keep
 * deleting operations for as long as the streams still disagree. A 4000-message
 * failure is not a bug report, it is a haystack; the same failure reduced to
 * three operations usually names the bug outright.
 *
 * <p>Run it for a long time from the command line:
 * <pre>
 *   ./gradlew fuzz --args="200000 400"
 * </pre>
 */
public final class EngineFuzzer {

    private EngineFuzzer() {}

    public enum Kind { LIMIT, MARKET, CANCEL, REPLACE }

    /** One operation against an engine. */
    public record Op(Kind kind, long id, long replacementId,
                     boolean buy, int price, int shares, TimeInForce timeInForce) {

        /** Renders as compilable Java, so a shrunk repro pastes into a test. */
        @Override
        public String toString() {
            return switch (kind) {
                case LIMIT -> "engine.limit(" + id + ", " + buy + ", " + price + ", " + shares
                        + ", TimeInForce." + timeInForce + ");";
                case MARKET -> "engine.market(" + id + ", " + buy + ", " + shares + ");";
                case CANCEL -> "engine.cancel(" + id + ");";
                case REPLACE -> "engine.replace(" + id + ", " + replacementId + ", "
                        + price + ", " + shares + ");";
            };
        }
    }

    /** A reproducible disagreement between the two engines. */
    public record Divergence(long seed, List<Op> operations, int reportIndex,
                             String reference, String actual) {

        public String describe() {
            StringBuilder out = new StringBuilder();
            out.append("engines diverged at report ").append(reportIndex)
               .append(" (seed ").append(seed).append(")\n")
               .append("  reference: ").append(reference).append('\n')
               .append("  fast:      ").append(actual).append('\n')
               .append("  minimal repro (").append(operations.size()).append(" operations):\n");
            for (Op op : operations) {
                out.append("    ").append(op).append('\n');
            }
            return out.toString();
        }
    }

    // ---------------------------------------------------------------
    // Generation
    // ---------------------------------------------------------------

    private static final int PRICE_LOW = 950;
    private static final int PRICE_HIGH = 1_050;

    /**
     * Builds a random operation sequence.
     *
     * <p>The mix is deliberately hostile. Roughly one operation in twelve is
     * malformed -- zero or negative quantities, non-positive prices, ids that
     * are already resting -- because validation and rejection paths are exactly
     * where two implementations drift apart, and a generator that only produces
     * valid orders never visits them.
     */
    public static List<Op> generate(long seed, int count) {
        Random random = new Random(seed);
        List<Op> operations = new ArrayList<>(count);
        List<Long> known = new ArrayList<>();
        long nextId = 1;

        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            Op op;

            if (roll < 50) {
                long id = random.nextInt(100) < 6 && !known.isEmpty()
                        ? known.get(random.nextInt(known.size()))   // provoke DUPLICATE_ID
                        : nextId++;
                op = new Op(Kind.LIMIT, id, 0, random.nextBoolean(),
                        price(random), shares(random), timeInForce(random));
                known.add(id);
            } else if (roll < 62) {
                op = new Op(Kind.MARKET, nextId++, 0, random.nextBoolean(),
                        0, shares(random), TimeInForce.GTC);
            } else if (roll < 80) {
                op = new Op(Kind.CANCEL, targetId(random, known, nextId), 0,
                        false, 0, 0, TimeInForce.GTC);
            } else {
                long replacement = nextId++;
                op = new Op(Kind.REPLACE, targetId(random, known, nextId), replacement,
                        false, price(random), shares(random), TimeInForce.GTC);
                known.add(replacement);
            }

            operations.add(op);
            if (known.size() > 600) {
                known.subList(0, 300).clear();
            }
        }
        return operations;
    }

    /** Mostly a live order, sometimes a reference that was never valid. */
    private static long targetId(Random random, List<Long> known, long nextId) {
        if (known.isEmpty() || random.nextInt(100) < 20) {
            return nextId + random.nextInt(50);
        }
        return known.get(random.nextInt(known.size()));
    }

    private static int price(Random random) {
        int roll = random.nextInt(100);
        if (roll < 3) return -random.nextInt(10);                    // invalid
        if (roll < 6) return 1 + random.nextInt(80);                 // far below, forces regrow
        if (roll < 9) return 4_000 + random.nextInt(2_000);          // far above, forces regrow
        return PRICE_LOW + random.nextInt(PRICE_HIGH - PRICE_LOW + 1);
    }

    private static int shares(Random random) {
        int roll = random.nextInt(100);
        if (roll < 3) return 0;
        if (roll < 5) return -random.nextInt(100);
        if (roll < 10) return 1 + random.nextInt(4_000);             // large enough to sweep
        return 1 + random.nextInt(300);
    }

    private static TimeInForce timeInForce(Random random) {
        return switch (random.nextInt(10)) {
            case 0, 1 -> TimeInForce.IOC;
            case 2, 3 -> TimeInForce.FOK;
            default -> TimeInForce.GTC;
        };
    }

    // ---------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------

    /** Runs a sequence through the fast engine. */
    public static List<String> runFast(List<Op> operations) {
        RecordingListener recorder = new RecordingListener();
        MatchingEngine engine = new MatchingEngine(recorder,
                new ArrayOrderBook(ArrayOrderBook.ANY_STOCK, 1, 4_096, 1_024));
        for (Op op : operations) {
            switch (op.kind()) {
                case LIMIT -> engine.limit(op.id(), op.buy(), op.price(), op.shares(), op.timeInForce());
                case MARKET -> engine.market(op.id(), op.buy(), op.shares());
                case CANCEL -> engine.cancel(op.id());
                case REPLACE -> engine.replace(op.id(), op.replacementId(), op.price(), op.shares());
            }
        }
        return recorder.reports();
    }

    /** Runs the same sequence through the reference engine. */
    public static List<String> runReference(List<Op> operations) {
        RecordingListener recorder = new RecordingListener();
        ReferenceMatchingEngine engine = new ReferenceMatchingEngine(recorder);
        for (Op op : operations) {
            switch (op.kind()) {
                case LIMIT -> engine.limit(op.id(), op.buy(), op.price(), op.shares(), op.timeInForce());
                case MARKET -> engine.market(op.id(), op.buy(), op.shares());
                case CANCEL -> engine.cancel(op.id());
                case REPLACE -> engine.replace(op.id(), op.replacementId(), op.price(), op.shares());
            }
        }
        return recorder.reports();
    }

    /** @return the index of the first differing report, or -1 if identical */
    private static int firstDifference(List<String> reference, List<String> actual) {
        int shared = Math.min(reference.size(), actual.size());
        for (int i = 0; i < shared; i++) {
            if (!reference.get(i).equals(actual.get(i))) return i;
        }
        return reference.size() == actual.size() ? -1 : shared;
    }

    private static String at(List<String> reports, int index) {
        return index < reports.size() ? reports.get(index) : "<stream ended>";
    }

    /** True if the two engines disagree on this sequence. */
    public static boolean diverges(List<Op> operations) {
        return firstDifference(runReference(operations), runFast(operations)) >= 0;
    }

    /** Checks one seed, returning a shrunk repro if the engines disagree. */
    public static Optional<Divergence> check(long seed, int operationCount) {
        List<Op> operations = generate(seed, operationCount);
        List<String> reference = runReference(operations);
        List<String> actual = runFast(operations);

        int index = firstDifference(reference, actual);
        if (index < 0) return Optional.empty();

        List<Op> minimal = shrink(operations);
        List<String> minimalReference = runReference(minimal);
        List<String> minimalActual = runFast(minimal);
        int minimalIndex = firstDifference(minimalReference, minimalActual);

        return Optional.of(new Divergence(seed, minimal, minimalIndex,
                at(minimalReference, minimalIndex), at(minimalActual, minimalIndex)));
    }

    // ---------------------------------------------------------------
    // Shrinking
    // ---------------------------------------------------------------

    /** Shrinks a diverging sequence to a minimal one that still diverges. */
    public static List<Op> shrink(List<Op> failing) {
        return shrink(failing, EngineFuzzer::diverges);
    }

    /**
     * Delta debugging. Deletes operations for as long as {@code stillFails}
     * holds, largest chunks first so a long sequence collapses quickly before
     * the expensive one-at-a-time pass begins.
     *
     * <p>The predicate is a parameter rather than hard-wired to
     * {@link #diverges} so the shrinker can be tested on its own. A shrinker
     * that quietly returns its input looks exactly like a shrinker that works.
     */
    public static List<Op> shrink(List<Op> failing, Predicate<List<Op>> stillFails) {
        List<Op> best = new ArrayList<>(failing);

        for (int chunk = Math.max(best.size() / 2, 1); chunk >= 1; chunk /= 2) {
            boolean progress = true;
            while (progress) {
                progress = false;
                for (int start = 0; start + chunk <= best.size(); start++) {
                    List<Op> candidate = new ArrayList<>(best);
                    candidate.subList(start, start + chunk).clear();
                    if (!candidate.isEmpty() && stillFails.test(candidate)) {
                        best = candidate;
                        progress = true;
                        break;
                    }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------
    // Long runs
    // ---------------------------------------------------------------

    public static void main(String[] args) {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 20_000;
        int operationCount = args.length > 1 ? Integer.parseInt(args[1]) : 400;

        System.out.printf("fuzzing %,d sequences of %,d operations%n", iterations, operationCount);
        long startedAt = System.nanoTime();

        for (long seed = 0; seed < iterations; seed++) {
            Optional<Divergence> divergence = check(seed, operationCount);
            if (divergence.isPresent()) {
                System.out.println();
                System.out.println(divergence.get().describe());
                System.exit(1);
            }
            if ((seed + 1) % 1_000 == 0) {
                System.out.printf("  %,d sequences clean%n", seed + 1);
            }
        }

        double seconds = (System.nanoTime() - startedAt) / 1e9;
        System.out.printf("%n%,d sequences (%,d operations) clean in %.1fs%n",
                iterations, (long) iterations * operationCount, seconds);
    }
}
