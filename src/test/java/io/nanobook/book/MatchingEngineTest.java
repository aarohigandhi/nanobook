package io.nanobook.book;

import io.nanobook.book.ExecutionListener.RejectReason;
import io.nanobook.book.MatchingEngine.TimeInForce;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingEngineTest {

    /** Captures the execution report stream as comparable text. */
    private static final class Recorder implements ExecutionListener {
        final List<String> reports = new ArrayList<>();

        @Override
        public void onAccepted(long orderId, boolean buy, int price, int shares) {
            reports.add("accept " + orderId + " " + (buy ? "B" : "S") + " " + price + " " + shares);
        }

        @Override
        public void onRejected(long orderId, RejectReason reason) {
            reports.add("reject " + orderId + " " + reason);
        }

        @Override
        public void onFill(long makerOrderId, long takerOrderId, int price, int shares, boolean takerBuy) {
            reports.add("fill maker=" + makerOrderId + " taker=" + takerOrderId
                    + " " + price + " " + shares + " " + (takerBuy ? "B" : "S"));
        }

        @Override
        public void onResting(long orderId, boolean buy, int price, int shares) {
            reports.add("rest " + orderId + " " + (buy ? "B" : "S") + " " + price + " " + shares);
        }

        @Override
        public void onCancelled(long orderId, int remainingShares) {
            reports.add("cancel " + orderId + " " + remainingShares);
        }

        @Override
        public void onExpired(long orderId, int remainingShares) {
            reports.add("expire " + orderId + " " + remainingShares);
        }

        List<String> fills() {
            return reports.stream().filter(report -> report.startsWith("fill")).toList();
        }

        void clear() {
            reports.clear();
        }
    }

    private Recorder recorder;
    private MatchingEngine engine;

    /** tickSize 1 so tests can use small readable prices. */
    private static MatchingEngine engineWith(ExecutionListener listener) {
        return new MatchingEngine(listener,
                new ArrayOrderBook(ArrayOrderBook.ANY_STOCK, 1, 4096, 1024));
    }

    @BeforeEach
    void setUp() {
        recorder = new Recorder();
        engine = engineWith(recorder);
    }

    // ---------------------------------------------------------------
    // Resting and crossing
    // ---------------------------------------------------------------

    @Test
    void anUncrossedLimitRests() {
        engine.limit(1, true, 100, 500);

        assertEquals(List.of("accept 1 B 100 500", "rest 1 B 100 500"), recorder.reports);
        assertEquals(100, engine.book().bestBid());
        assertEquals(500, engine.book().bestBidSize());
        assertEquals(0, engine.fills());
    }

    @Test
    void aCrossingLimitFillsAtTheMakerPrice() {
        engine.limit(1, false, 102, 300);   // resting ask at 102
        recorder.clear();

        engine.limit(2, true, 105, 300);    // buyer willing to pay 105

        assertEquals(
                List.of("fill maker=1 taker=2 102 300 B"),
                recorder.fills(),
                "the taker crossed the spread, so price improvement is theirs");
        assertEquals(OrderBook.NO_PRICE, engine.book().bestAsk());
        assertEquals(0, engine.book().orderCount());
    }

    @Test
    void anUnfilledRemainderRests() {
        engine.limit(1, false, 100, 200);
        recorder.clear();

        engine.limit(2, true, 100, 500);

        assertEquals(List.of("fill maker=1 taker=2 100 200 B"), recorder.fills());
        assertTrue(recorder.reports.contains("rest 2 B 100 300"));
        assertEquals(100, engine.book().bestBid());
        assertEquals(300, engine.book().bestBidSize());
    }

    @Test
    void betterPricesFillFirst() {
        engine.limit(1, false, 104, 100);
        engine.limit(2, false, 102, 100);
        engine.limit(3, false, 103, 100);
        recorder.clear();

        engine.limit(9, true, 110, 300);

        assertEquals(
                List.of("fill maker=2 taker=9 102 100 B",
                        "fill maker=3 taker=9 103 100 B",
                        "fill maker=1 taker=9 104 100 B"),
                recorder.fills());
    }

    @Test
    void arrivalOrderBreaksTiesWithinALevel() {
        engine.limit(1, false, 100, 100);
        engine.limit(2, false, 100, 100);
        engine.limit(3, false, 100, 100);
        recorder.clear();

        engine.limit(9, true, 100, 250);

        assertEquals(
                List.of("fill maker=1 taker=9 100 100 B",
                        "fill maker=2 taker=9 100 100 B",
                        "fill maker=3 taker=9 100 50 B"),
                recorder.fills());
        assertEquals(50, engine.book().bestAskSize(), "order 3 keeps its remainder");
    }

    @Test
    void aSellerCrossesDownIntoBids() {
        engine.limit(1, true, 100, 400);
        recorder.clear();

        engine.limit(2, false, 95, 400);

        assertEquals(List.of("fill maker=1 taker=2 100 400 S"), recorder.fills());
        assertEquals(OrderBook.NO_PRICE, engine.book().bestBid());
    }

    @Test
    void aLimitStopsAtItsPrice() {
        engine.limit(1, false, 100, 100);
        engine.limit(2, false, 105, 100);
        recorder.clear();

        engine.limit(9, true, 100, 300);

        assertEquals(List.of("fill maker=1 taker=9 100 100 B"), recorder.fills());
        assertTrue(recorder.reports.contains("rest 9 B 100 200"));
        assertEquals(105, engine.book().bestAsk(), "the 105 ask is untouched");
    }

    // ---------------------------------------------------------------
    // Time in force
    // ---------------------------------------------------------------

    @Test
    void immediateOrCancelExpiresItsRemainder() {
        engine.limit(1, false, 100, 100);
        recorder.clear();

        engine.limit(2, true, 100, 400, TimeInForce.IOC);

        assertEquals(List.of("fill maker=1 taker=2 100 100 B"), recorder.fills());
        assertTrue(recorder.reports.contains("expire 2 300"));
        assertEquals(OrderBook.NO_PRICE, engine.book().bestBid(), "IOC must never rest");
    }

    @Test
    void fillOrKillRejectsWithoutTouchingTheBook() {
        engine.limit(1, false, 100, 100);
        recorder.clear();

        engine.limit(2, true, 100, 400, TimeInForce.FOK);

        assertEquals(List.of("reject 2 INSUFFICIENT_LIQUIDITY"), recorder.reports,
                "a rejected FOK leaves no partial fills to unwind");
        assertEquals(100, engine.book().bestAskSize(), "resting liquidity is untouched");
        assertEquals(0, engine.fills());
    }

    @Test
    void fillOrKillFillsWhenLiquiditySpansLevels() {
        engine.limit(1, false, 100, 100);
        engine.limit(2, false, 101, 100);
        recorder.clear();

        engine.limit(9, true, 101, 200, TimeInForce.FOK);

        assertEquals(
                List.of("fill maker=1 taker=9 100 100 B",
                        "fill maker=2 taker=9 101 100 B"),
                recorder.fills());
        assertEquals(0, engine.book().orderCount());
    }

    @Test
    void fillOrKillIgnoresLiquidityBeyondItsLimit() {
        engine.limit(1, false, 100, 100);
        engine.limit(2, false, 200, 900); // plenty of size, but out of reach
        recorder.clear();

        engine.limit(9, true, 100, 400, TimeInForce.FOK);

        assertEquals(List.of("reject 9 INSUFFICIENT_LIQUIDITY"), recorder.reports);
    }

    @Test
    void marketOrdersSweepEveryPrice() {
        engine.limit(1, false, 100, 100);
        engine.limit(2, false, 500, 100);
        recorder.clear();

        engine.market(9, true, 200);

        assertEquals(
                List.of("fill maker=1 taker=9 100 100 B",
                        "fill maker=2 taker=9 500 100 B"),
                recorder.fills());
        assertEquals(0, engine.book().orderCount());
    }

    @Test
    void marketOrdersNeverRest() {
        engine.market(9, true, 200);

        assertTrue(recorder.reports.contains("expire 9 200"));
        assertEquals(0, engine.book().orderCount());
    }

    // ---------------------------------------------------------------
    // Cancel and replace
    // ---------------------------------------------------------------

    @Test
    void cancelRemovesRestingLiquidity() {
        engine.limit(1, true, 100, 500);
        recorder.clear();

        assertTrue(engine.cancel(1));

        assertEquals(List.of("cancel 1 500"), recorder.reports);
        assertEquals(OrderBook.NO_PRICE, engine.book().bestBid());
    }

    @Test
    void cancellingAnUnknownOrderIsRejected() {
        assertFalse(engine.cancel(404));
        assertEquals(List.of("reject 404 UNKNOWN_ORDER"), recorder.reports);
    }

    @Test
    void duplicateOrderIdsAreRejected() {
        engine.limit(1, true, 100, 100);
        recorder.clear();

        engine.limit(1, true, 100, 100);

        assertEquals(List.of("reject 1 DUPLICATE_ID"), recorder.reports);
        assertEquals(100, engine.book().bestBidSize());
    }

    /**
     * The rule that gets written wrong most often. A replacement is a new order:
     * it joins the back of the queue, so an order that arrived after the
     * original now fills ahead of it. This is exactly why a trader reducing
     * size cancels down instead of replacing.
     */
    @Test
    void replacementGoesToTheBackOfTheQueue() {
        engine.limit(1, false, 100, 100);  // first in queue
        engine.limit(2, false, 100, 100);  // behind it
        engine.replace(1, 3, 100, 100);    // 1 is replaced by 3
        recorder.clear();

        engine.limit(9, true, 100, 100);

        assertEquals(List.of("fill maker=2 taker=9 100 100 B"), recorder.fills(),
                "order 2 must fill before the replacement");
    }

    @Test
    void replaceReportsTheCancelThenTheNewOrder() {
        engine.limit(1, true, 100, 500);
        recorder.clear();

        assertTrue(engine.replace(1, 2, 99, 300));

        assertEquals(List.of("cancel 1 500", "accept 2 B 99 300", "rest 2 B 99 300"),
                recorder.reports);
        assertEquals(99, engine.book().bestBid());
        assertEquals(300, engine.book().bestBidSize());
    }

    @Test
    void aMarketableReplacementCrossesImmediately() {
        engine.limit(1, false, 105, 100);
        engine.limit(2, true, 100, 100);
        recorder.clear();

        engine.replace(2, 3, 110, 100); // repriced through the ask

        assertEquals(List.of("fill maker=1 taker=3 105 100 B"), recorder.fills());
        assertEquals(0, engine.book().orderCount());
    }

    @Test
    void replacingAnUnknownOrderIsRejected() {
        assertFalse(engine.replace(404, 405, 100, 100));
        assertEquals(List.of("reject 404 UNKNOWN_ORDER"), recorder.reports);
    }

    // ---------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------

    @Test
    void nonPositiveQuantitiesAreRejected() {
        engine.limit(1, true, 100, 0);
        engine.market(2, true, -5);
        assertEquals(List.of("reject 1 INVALID_QUANTITY", "reject 2 INVALID_QUANTITY"),
                recorder.reports);
    }

    @Test
    void nonPositivePricesAreRejected() {
        engine.limit(1, true, 0, 100);
        assertEquals(List.of("reject 1 INVALID_PRICE"), recorder.reports);
    }

    // ---------------------------------------------------------------
    // Invariants
    // ---------------------------------------------------------------

    /**
     * After every operation the book must not be crossed: any bid at or above
     * the best ask should have matched instead of resting. A cached touch that
     * is repaired incorrectly shows up here first.
     */
    @Test
    void theBookNeverRestsCrossed() {
        Random random = new Random(4242L);
        long nextId = 1;
        List<Long> live = new ArrayList<>();

        for (int step = 0; step < 20_000; step++) {
            int roll = random.nextInt(100);
            if (!live.isEmpty() && roll < 20) {
                long id = live.remove(random.nextInt(live.size()));
                engine.cancel(id);
            } else {
                long id = nextId++;
                boolean buy = random.nextBoolean();
                int price = 900 + random.nextInt(200);
                int shares = 1 + random.nextInt(300);
                TimeInForce tif = switch (roll % 8) {
                    case 0 -> TimeInForce.IOC;
                    case 1 -> TimeInForce.FOK;
                    default -> TimeInForce.GTC;
                };
                engine.limit(id, buy, price, shares, tif);
                if (tif == TimeInForce.GTC) live.add(id);
            }

            int bid = engine.book().bestBid();
            int ask = engine.book().bestAsk();
            if (bid != OrderBook.NO_PRICE && ask != OrderBook.NO_PRICE) {
                assertTrue(bid < ask,
                        "book crossed at step " + step + ": bid " + bid + " >= ask " + ask);
            }
            live.removeIf(id -> ((ArrayOrderBook) engine.book()).slotOf(id) == ArrayOrderBook.NIL);
        }
        assertTrue(engine.fills() > 0, "the session should have traded");
    }

    /** Same input, same output, every run. Phase 5 depends on this holding. */
    @Test
    void theReportStreamIsDeterministic() {
        List<String> first = runScriptedSession();
        for (int run = 0; run < 5; run++) {
            assertEquals(first, runScriptedSession(), "engine output varied between runs");
        }
    }

    private static List<String> runScriptedSession() {
        Recorder recorder = new Recorder();
        MatchingEngine engine = engineWith(recorder);
        Random random = new Random(31337L);
        long nextId = 1;

        for (int step = 0; step < 5_000; step++) {
            long id = nextId++;
            boolean buy = random.nextBoolean();
            int price = 950 + random.nextInt(100);
            int shares = 1 + random.nextInt(200);
            switch (random.nextInt(6)) {
                case 0 -> engine.market(id, buy, shares);
                case 1 -> engine.limit(id, buy, price, shares, TimeInForce.IOC);
                case 2 -> engine.limit(id, buy, price, shares, TimeInForce.FOK);
                default -> engine.limit(id, buy, price, shares);
            }
            if (step % 7 == 0 && id > 10) {
                engine.cancel(id - 10);
            }
        }
        return recorder.reports;
    }
}
