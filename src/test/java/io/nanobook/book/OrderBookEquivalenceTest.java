package io.nanobook.book;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link NaiveOrderBook} and {@link ArrayOrderBook} through identical
 * message sequences and asserts they cannot be told apart.
 *
 * <p>The naive book is the oracle. It is slow and obviously correct; the array
 * book is fast and full of index arithmetic, free lists and a cached touch that
 * has to be repaired by hand. Any disagreement is a bug in the fast one.
 *
 * <p>This is the same technique Phase 5 applies to the matching engine, at
 * smaller scale.
 */
class OrderBookEquivalenceTest {

    private static final long TS = 34_200_000_000_000L;
    private static final int LOCATE = 7;
    private static final int SYMBOL = 0;

    /**
     * Everything a caller can observe about a book, flattened to a string so a
     * mismatch reports both sides rather than just a failed boolean.
     */
    private static String snapshot(OrderBook book) {
        StringBuilder out = new StringBuilder(160);
        out.append("bid=").append(book.bestBid()).append('x').append(book.bestBidSize())
           .append(" ask=").append(book.bestAsk()).append('x').append(book.bestAskSize())
           .append(" spread=").append(book.spread())
           .append(" orders=").append(book.orderCount());
        for (int level = 0; level < 6; level++) {
            appendLevel(out, book, true, level);
            appendLevel(out, book, false, level);
        }
        return out.toString();
    }

    private static void appendLevel(StringBuilder out, OrderBook book, boolean buy, int level) {
        int price = book.priceAtLevel(buy, level);
        out.append(buy ? " B" : " A").append(level).append(':').append(price);
        if (price != OrderBook.NO_PRICE) {
            out.append('x').append(book.sizeAt(price, buy));
        }
    }

    /** Applies one message to both books. */
    private interface Message {
        void applyTo(OrderBook book);
    }

    private record Resting(long ref, boolean buy, int price, int shares) {}

    // ---------------------------------------------------------------
    // Targeted cases
    // ---------------------------------------------------------------

    @Test
    void emptyBooksAgree() {
        assertEquals(snapshot(new NaiveOrderBook()), snapshot(new ArrayOrderBook()));
    }

    @Test
    void bestPricesAreTheTouch() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                add(2, true, 10_100, 200),   // better bid
                add(3, false, 10_300, 300),
                add(4, false, 10_200, 400)); // better ask
        OrderBook book = replay(new ArrayOrderBook(), messages);

        assertEquals(10_100, book.bestBid());
        assertEquals(200, book.bestBidSize());
        assertEquals(10_200, book.bestAsk());
        assertEquals(400, book.bestAskSize());
        assertEquals(100, book.spread());
        assertAgree(messages);
    }

    @Test
    void partialCancelKeepsTheLevel() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 500),
                cancel(1, 200));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(10_000, book.bestBid());
        assertEquals(300, book.bestBidSize());
        assertEquals(1, book.orderCount());
        assertAgree(messages);
    }

    @Test
    void emptyingTheTouchFallsBackToTheNextLevel() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                add(2, true, 10_100, 200),
                delete(2));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(10_000, book.bestBid(), "touch must fall back, not linger");
        assertEquals(100, book.bestBidSize());
        assertAgree(messages);
    }

    @Test
    void deletingEverythingEmptiesTheBook() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                add(2, false, 10_100, 100),
                delete(1),
                delete(2));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(OrderBook.NO_PRICE, book.bestBid());
        assertEquals(OrderBook.NO_PRICE, book.bestAsk());
        assertEquals(0, book.bestBidSize());
        assertEquals(0, book.orderCount());
        assertAgree(messages);
    }

    @Test
    void executionRemovesAnOrderThatIsFullyFilled() {
        List<Message> messages = List.of(
                add(1, false, 10_200, 100),
                execute(1, 100));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(OrderBook.NO_PRICE, book.bestAsk());
        assertEquals(0, book.orderCount());
        assertAgree(messages);
    }

    @Test
    void replaceMovesPriceAndSize() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                replace(1, 2, 300, 10_100));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(10_100, book.bestBid());
        assertEquals(300, book.bestBidSize());
        assertEquals(0, book.sizeAt(10_000, true));
        assertEquals(1, book.orderCount());
        assertAgree(messages);
    }

    @Test
    void unknownReferencesAreSkippedIdentically() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                delete(999),
                cancel(999, 50),
                execute(999, 50),
                replace(999, 1000, 100, 10_500));
        OrderBook book = replay(new ArrayOrderBook(), messages);
        assertEquals(1, book.orderCount());
        assertEquals(10_000, book.bestBid());
        assertAgree(messages);

        NaiveOrderBook naive = new NaiveOrderBook();
        ArrayOrderBook array = new ArrayOrderBook();
        messages.forEach(m -> { m.applyTo(naive); m.applyTo(array); });
        assertEquals(4, naive.unknownReferences());
        assertEquals(naive.unknownReferences(), array.unknownReferences());
    }

    @Test
    void hiddenAndAuctionLiquidityNeverTouchTheBook() {
        NaiveOrderBook naive = new NaiveOrderBook();
        ArrayOrderBook array = new ArrayOrderBook();
        for (OrderBook book : List.of(naive, array)) {
            book.onAddOrder(TS, LOCATE, 1, true, 100, SYMBOL, 10_000);
            book.onTrade(TS, LOCATE, 0, true, 500, SYMBOL, 10_050, 1);
            book.onCrossTrade(TS, LOCATE, 900, SYMBOL, 10_050, 2, 'O');
        }
        assertEquals(1, naive.orderCount());
        assertEquals(snapshot(naive), snapshot(array));
    }

    @Test
    void clearResetsBothToEmpty() {
        List<Message> messages = List.of(
                add(1, true, 10_000, 100),
                add(2, false, 10_100, 100));
        NaiveOrderBook naive = new NaiveOrderBook();
        ArrayOrderBook array = new ArrayOrderBook();
        messages.forEach(m -> { m.applyTo(naive); m.applyTo(array); });
        naive.clear();
        array.clear();
        assertEquals(0, array.orderCount());
        assertEquals(snapshot(naive), snapshot(array));
        assertEquals(snapshot(new ArrayOrderBook()), snapshot(array));
    }

    /**
     * Prices well outside the initial tick window force the array book to
     * widen and copy its levels. The naive book has no window, so it is the
     * oracle for whether the regrow preserved everything.
     */
    @Test
    void survivesPriceWindowRegrowth() {
        ArrayOrderBook array = new ArrayOrderBook(NaiveOrderBook.ANY_STOCK, 100, 64, 64);
        NaiveOrderBook naive = new NaiveOrderBook();

        List<Message> messages = new ArrayList<>();
        messages.add(add(1, true, 100_000, 100));
        messages.add(add(2, false, 100_100, 100));
        messages.add(add(3, true, 1_000, 50));       // far below the window
        messages.add(add(4, false, 900_000, 50));    // far above it
        messages.add(add(5, true, 100_200, 75));
        messages.add(delete(1));

        for (Message message : messages) {
            message.applyTo(naive);
            message.applyTo(array);
            assertEquals(snapshot(naive), snapshot(array));
        }
        assertTrue(array.levelRegrowths() >= 2, "expected the window to widen");
        assertEquals(4, array.orderCount());
    }

    @Test
    void holdsPricesOffTheTickGridOffBand() {
        ArrayOrderBook array = new ArrayOrderBook();
        array.onAddOrder(TS, LOCATE, 1, true, 100, SYMBOL, 10_000);
        array.onAddOrder(TS, LOCATE, 2, true, 100, SYMBOL, 10_050 + 7);

        // Present and addressable, but in no level and invisible to the touch.
        assertEquals(2, array.orderCount());
        assertEquals(1, array.offBandOrders());
        assertEquals(10_000, array.bestBid(),
                "an off-grid price must not become the touch");

        // Still resolvable by reference, which is the whole point of keeping it.
        array.onOrderDelete(TS, LOCATE, 2);
        assertEquals(1, array.orderCount());
        assertEquals(10_000, array.bestBid());
    }

    @Test
    void holdsPricesBeyondTheWindowCapOffBand() {
        // Cap the window at 1,024 ticks so the far price cannot be reached by
        // regrowth. $199,999 against a $289 book is the real case this models.
        ArrayOrderBook array = new ArrayOrderBook(LOCATE, 100, 256, 256, 1_024);
        array.onAddOrder(TS, LOCATE, 1, false, 100, SYMBOL, 2_890_000);
        array.onAddOrder(TS, LOCATE, 2, false, 100, SYMBOL, 1_999_990_000);

        assertEquals(2, array.orderCount());
        assertEquals(1, array.offBandOrders());
        assertEquals(2_890_000, array.bestAsk());

        array.onOrderDelete(TS, LOCATE, 2);
        assertEquals(1, array.orderCount());
        assertEquals(1, array.offBandOrders(),
                "the counter is cumulative: it records that the session contained "
                        + "an unrepresentable order, and deleting it does not undo that");
    }

    @Test
    void executesAndReducesOffBandOrders() {
        ArrayOrderBook array = new ArrayOrderBook();
        array.onAddOrder(TS, LOCATE, 1, true, 500, SYMBOL, 10_050 + 7);
        assertEquals(1, array.offBandOrders());

        array.onOrderCancel(TS, LOCATE, 1, 200);
        assertEquals(1, array.orderCount(), "a partial cancel leaves it resting");

        array.onOrderExecuted(TS, LOCATE, 1, 300, 1L);
        assertEquals(0, array.orderCount(), "filling the remainder retires it");
        assertEquals(OrderBook.NO_PRICE, array.bestBid());
    }

    // ---------------------------------------------------------------
    // Randomized differential
    // ---------------------------------------------------------------

    @Test
    void agreeUnderRandomizedMessageStreams() {
        for (long seed = 0; seed < 20; seed++) {
            runRandomSession(seed, 4_000);
        }
    }

    /** A long single session, to shake out state that only drifts over time. */
    @Test
    void agreeOverALongSession() {
        runRandomSession(999L, 60_000);
    }

    private void runRandomSession(long seed, int operations) {
        Random random = new Random(seed);
        NaiveOrderBook naive = new NaiveOrderBook(LOCATE);
        ArrayOrderBook array = new ArrayOrderBook(LOCATE);
        List<Resting> live = new ArrayList<>();
        long nextRef = 1;

        for (int step = 0; step < operations; step++) {
            Message message;
            int roll = random.nextInt(100);

            if (live.isEmpty() || roll < 55) {
                boolean buy = random.nextBoolean();
                // Bids below 200.00, asks above, with an overlap band so the
                // book locks and crosses the way a reconstructed book really does.
                int price = (buy ? 1_900 + random.nextInt(120) : 1_980 + random.nextInt(120)) * 100;
                int shares = 1 + random.nextInt(500);
                long ref = nextRef++;
                live.add(new Resting(ref, buy, price, shares));
                message = add(ref, buy, price, shares);
            } else if (roll < 70) {
                Resting order = live.get(random.nextInt(live.size()));
                int shares = 1 + random.nextInt(order.shares() + 50);
                message = cancel(order.ref(), shares);
                trim(live, order, shares);
            } else if (roll < 85) {
                Resting order = live.get(random.nextInt(live.size()));
                int shares = 1 + random.nextInt(order.shares() + 50);
                message = execute(order.ref(), shares);
                trim(live, order, shares);
            } else if (roll < 95) {
                Resting order = live.remove(random.nextInt(live.size()));
                message = delete(order.ref());
            } else {
                Resting order = live.remove(random.nextInt(live.size()));
                long ref = nextRef++;
                int price = (order.buy() ? 1_900 + random.nextInt(120) : 1_980 + random.nextInt(120)) * 100;
                int shares = 1 + random.nextInt(500);
                live.add(new Resting(ref, order.buy(), price, shares));
                message = replace(order.ref(), ref, shares, price);
            }

            message.applyTo(naive);
            message.applyTo(array);

            assertEquals(snapshot(naive), snapshot(array),
                    "books diverged at seed " + seed + " step " + step);
        }
        assertEquals(naive.unknownReferences(), array.unknownReferences(),
                "unknown-reference accounting diverged at seed " + seed);
    }

    private static void trim(List<Resting> live, Resting order, int shares) {
        live.remove(order);
        int remaining = order.shares() - shares;
        if (remaining > 0) {
            live.add(new Resting(order.ref(), order.buy(), order.price(), remaining));
        }
    }

    // ---------------------------------------------------------------
    // Message builders
    // ---------------------------------------------------------------

    private static Message add(long ref, boolean buy, int price, int shares) {
        return book -> book.onAddOrder(TS, LOCATE, ref, buy, shares, SYMBOL, price);
    }

    private static Message cancel(long ref, int shares) {
        return book -> book.onOrderCancel(TS, LOCATE, ref, shares);
    }

    private static Message execute(long ref, int shares) {
        return book -> book.onOrderExecuted(TS, LOCATE, ref, shares, 0L);
    }

    private static Message delete(long ref) {
        return book -> book.onOrderDelete(TS, LOCATE, ref);
    }

    private static Message replace(long originalRef, long newRef, int shares, int price) {
        return book -> book.onOrderReplace(TS, LOCATE, originalRef, newRef, shares, price);
    }

    private static OrderBook replay(OrderBook book, List<Message> messages) {
        messages.forEach(message -> message.applyTo(book));
        return book;
    }

    private static void assertAgree(List<Message> messages) {
        assertEquals(
                snapshot(replay(new NaiveOrderBook(), messages)),
                snapshot(replay(new ArrayOrderBook(), messages)));
    }
}
