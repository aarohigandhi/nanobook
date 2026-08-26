package io.nanobook.bench;

import io.nanobook.book.ArrayOrderBook;
import io.nanobook.book.NaiveOrderBook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The benchmark workload generator is itself measured infrastructure.
 *
 * <p>{@code @OperationsPerInvocation} is a compile-time constant, so JMH divides
 * by whatever number the annotation claims. If the script actually replays a
 * different count, every ns/op figure is scaled by the ratio and looks entirely
 * plausible while being wrong. That is the failure this class exists to catch.
 */
class MessageScriptTest {

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 10, 1_000, 199_999, 200_000, 500_000})
    void emitsExactlyTheRequestedMessageCount(int target) {
        assertEquals(target, MessageScript.generate(20260818L, target).length,
                "script length must equal the count @OperationsPerInvocation declares");
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 200_000})
    void leavesBothBooksEmpty(int target) {
        MessageScript script = MessageScript.generate(20260818L, target);
        ArrayOrderBook array = new ArrayOrderBook();
        NaiveOrderBook naive = new NaiveOrderBook();
        for (int i = 0; i < script.length; i++) {
            script.applyTo(array, i);
            script.applyTo(naive, i);
        }
        // A balanced script is what lets JMH reuse one book across invocations
        // without the numbers drifting as the book grows.
        assertEquals(0, array.orderCount(), "array book must drain");
        assertEquals(0, naive.orderCount(), "naive book must drain");
    }

    @Test
    void isDeterministicForAGivenSeed() {
        MessageScript a = MessageScript.generate(42L, 50_000);
        MessageScript b = MessageScript.generate(42L, 50_000);
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a.kind[i], b.kind[i], "kind at " + i);
            assertEquals(a.reference[i], b.reference[i], "reference at " + i);
            assertEquals(a.price[i], b.price[i], "price at " + i);
            assertEquals(a.shares[i], b.shares[i], "shares at " + i);
        }
    }

    @Test
    void exercisesEveryMessageKind() {
        MessageScript script = MessageScript.generate(20260818L, 200_000);
        boolean[] seen = new boolean[4];
        for (int i = 0; i < script.length; i++) {
            seen[script.kind[i]] = true;
        }
        // A workload that never cancels or executes would benchmark the add
        // path alone, which is not the path the message mix says dominates.
        assertTrue(seen[MessageScript.ADD], "no adds");
        assertTrue(seen[MessageScript.DELETE], "no deletes");
        assertTrue(seen[MessageScript.CANCEL], "no cancels");
        assertTrue(seen[MessageScript.EXECUTE], "no executes");
    }
}
