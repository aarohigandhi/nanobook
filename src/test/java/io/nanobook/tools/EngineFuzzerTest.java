package io.nanobook.tools;

import io.nanobook.book.MatchingEngine.TimeInForce;
import io.nanobook.tools.EngineFuzzer.Divergence;
import io.nanobook.tools.EngineFuzzer.Kind;
import io.nanobook.tools.EngineFuzzer.Op;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFuzzerTest {

    /**
     * The headline check. Every sequence must produce byte-identical execution
     * report streams from two engines that share no matching code.
     */
    @Test
    void engineMatchesTheReferenceAcrossManySequences() {
        for (long seed = 0; seed < 300; seed++) {
            Optional<Divergence> divergence = EngineFuzzer.check(seed, 300);
            if (divergence.isPresent()) {
                throw new AssertionError(divergence.get().describe());
            }
        }
    }

    /** Long sequences, where state has time to drift apart. */
    @Test
    void engineMatchesTheReferenceOverLongSequences() {
        for (long seed = 1_000; seed < 1_020; seed++) {
            Optional<Divergence> divergence = EngineFuzzer.check(seed, 8_000);
            if (divergence.isPresent()) {
                throw new AssertionError(divergence.get().describe());
            }
        }
    }

    // ---------------------------------------------------------------
    // Tests of the fuzzer itself
    //
    // A fuzzer that cannot fail is worse than no fuzzer, because it reads as
    // evidence. These check that it detects a real difference and that the
    // shrinker actually reduces one.
    // ---------------------------------------------------------------

    @Test
    void generatorProducesTheHostileCasesItClaimsTo() {
        List<Op> operations = EngineFuzzer.generate(42L, 20_000);

        assertTrue(operations.stream().anyMatch(op -> op.shares() <= 0),
                "expected some invalid quantities");
        assertTrue(operations.stream().anyMatch(op -> op.kind() == Kind.LIMIT && op.price() <= 0),
                "expected some invalid prices");
        assertTrue(operations.stream().anyMatch(op -> op.timeInForce() == TimeInForce.FOK),
                "expected some fill-or-kill orders");
        assertTrue(operations.stream().anyMatch(op -> op.kind() == Kind.REPLACE),
                "expected some replaces");
        assertTrue(operations.stream().anyMatch(op -> op.kind() == Kind.LIMIT && op.price() > 3_000),
                "expected prices far outside the window, to force a regrow");
    }

    @Test
    void generationIsReproducible() {
        assertEquals(EngineFuzzer.generate(7L, 500), EngineFuzzer.generate(7L, 500));
        assertFalse(EngineFuzzer.generate(7L, 500).equals(EngineFuzzer.generate(8L, 500)));
    }

    @Test
    void identicalSequencesDoNotDiverge() {
        assertFalse(EngineFuzzer.diverges(EngineFuzzer.generate(3L, 1_000)));
    }

    /**
     * Feeds the two engines sequences that differ, to prove the comparison
     * would notice. Without this, a green fuzzer might only mean the harness
     * compares nothing.
     */
    @Test
    void theComparisonDetectsARealDifference() {
        List<Op> base = List.of(
                new Op(Kind.LIMIT, 1, 0, false, 100, 100, TimeInForce.GTC),
                new Op(Kind.LIMIT, 2, 0, true, 100, 100, TimeInForce.GTC));
        List<String> reference = EngineFuzzer.runReference(base);
        List<String> fast = EngineFuzzer.runFast(base);
        assertEquals(reference, fast);

        List<Op> altered = new ArrayList<>(base);
        altered.set(1, new Op(Kind.LIMIT, 2, 0, true, 99, 100, TimeInForce.GTC));
        assertFalse(EngineFuzzer.runReference(altered).equals(reference),
                "changing the price must change the reports, or the harness is inert");
    }

    /**
     * Drives the shrinker with a synthetic predicate -- "the sequence still
     * contains the two operations that matter" -- so its reduction can be
     * checked exactly. A shrinker that quietly returns its input is
     * indistinguishable from one that works, until the day you need it.
     */
    @Test
    void shrinkerReducesToTheOperationsThatMatter() {
        Op culprit = new Op(Kind.LIMIT, 777, 0, true, 1_000, 50, TimeInForce.GTC);
        Op accomplice = new Op(Kind.MARKET, 888, 0, false, 0, 50, TimeInForce.GTC);

        List<Op> haystack = new ArrayList<>(EngineFuzzer.generate(5L, 400));
        haystack.add(120, culprit);
        haystack.add(300, accomplice);

        List<Op> minimal = EngineFuzzer.shrink(haystack,
                ops -> ops.contains(culprit) && ops.contains(accomplice));

        assertEquals(List.of(culprit, accomplice), minimal,
                "delta debugging should strip everything the predicate does not need");
    }

    @Test
    void shrinkerLeavesACleanSequenceAlone() {
        List<Op> clean = EngineFuzzer.generate(11L, 200);
        assertFalse(EngineFuzzer.diverges(clean));
        assertEquals(clean, EngineFuzzer.shrink(clean),
                "nothing to shrink when nothing diverges");
    }

    @Test
    void reprosRenderAsPasteableJava() {
        Op limit = new Op(Kind.LIMIT, 5, 0, true, 1_000, 200, TimeInForce.IOC);
        assertEquals("engine.limit(5, true, 1000, 200, TimeInForce.IOC);", limit.toString());

        Op replace = new Op(Kind.REPLACE, 5, 6, false, 999, 50, TimeInForce.GTC);
        assertEquals("engine.replace(5, 6, 999, 50);", replace.toString());
    }
}
