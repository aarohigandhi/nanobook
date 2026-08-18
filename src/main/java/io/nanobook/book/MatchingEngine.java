package io.nanobook.book;

import io.nanobook.book.ExecutionListener.RejectReason;

/**
 * A price-time priority matching engine. Phase 4.
 *
 * <p>Orders are matched against the best opposing price first, and within a
 * price level in arrival order. Fills print at the <b>maker's</b> resting
 * price: the taker crossed the spread, so any price improvement goes to them,
 * never to the resting side.
 *
 * <p>Resting liquidity lives in an {@link ArrayOrderBook}. The engine drives it
 * through the package-private surface at the bottom of that class rather than
 * carrying a second copy of the same array plumbing.
 *
 * <h2>Determinism</h2>
 * Given the same sequence of calls, this engine produces a byte-identical
 * execution report stream on every run. Nothing here reads the clock, hashes an
 * identity, or iterates a container whose order is unspecified. That property
 * is not incidental -- Phase 5 fuzzes this engine against a reference
 * implementation by comparing report streams, and a single nondeterministic
 * field would make every comparison meaningless.
 *
 * <p>Single-threaded by design. An exchange core that has to synchronize has
 * already lost the latency argument; concurrency belongs at the edges, in the
 * feed handlers and gateways, not in the matcher.
 */
public final class MatchingEngine {

    /** How long an order may live if it cannot be filled immediately. */
    public enum TimeInForce {
        /** Rest the unfilled remainder in the book. */
        GTC,
        /** Fill what is available now, cancel the rest. */
        IOC,
        /** Fill the entire quantity now, or reject the order untouched. */
        FOK
    }

    private final ArrayOrderBook book;
    private final ExecutionListener listener;

    private long fills;
    private long filledShares;

    public MatchingEngine(ExecutionListener listener) {
        this(listener, new ArrayOrderBook());
    }

    public MatchingEngine(ExecutionListener listener, ArrayOrderBook book) {
        this.listener = listener;
        this.book = book;
    }

    /** The resting book. Read-only in spirit -- mutate it and the engine lies. */
    public OrderBook book() {
        return book;
    }

    public long fills() {
        return fills;
    }

    public long filledShares() {
        return filledShares;
    }

    // ---------------------------------------------------------------
    // Order entry
    // ---------------------------------------------------------------

    /** Submits a limit order. */
    public void limit(long orderId, boolean buy, int price, int shares, TimeInForce timeInForce) {
        if (shares <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (price <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_PRICE);
            return;
        }
        if (book.slotOf(orderId) != ArrayOrderBook.NIL) {
            listener.onRejected(orderId, RejectReason.DUPLICATE_ID);
            return;
        }

        // Fill-or-kill is decided before anything is touched, so a rejected FOK
        // leaves no trace: no partial fills to unwind, no reports to retract.
        if (timeInForce == TimeInForce.FOK && available(buy, price, false) < shares) {
            listener.onRejected(orderId, RejectReason.INSUFFICIENT_LIQUIDITY);
            return;
        }

        listener.onAccepted(orderId, buy, price, shares);
        int remaining = match(orderId, buy, price, shares, false);

        if (remaining == 0) {
            return;
        }
        switch (timeInForce) {
            case GTC -> {
                book.addResting(orderId, buy, price, remaining);
                listener.onResting(orderId, buy, price, remaining);
            }
            case IOC, FOK -> listener.onExpired(orderId, remaining);
        }
    }

    /** Submits a limit order that rests if it cannot fill. */
    public void limit(long orderId, boolean buy, int price, int shares) {
        limit(orderId, buy, price, shares, TimeInForce.GTC);
    }

    /**
     * Submits a market order: takes liquidity at any price and never rests.
     * An empty or exhausted book expires the remainder.
     */
    public void market(long orderId, boolean buy, int shares) {
        if (shares <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_QUANTITY);
            return;
        }
        listener.onAccepted(orderId, buy, 0, shares);
        int remaining = match(orderId, buy, 0, shares, true);
        if (remaining > 0) {
            listener.onExpired(orderId, remaining);
        }
    }

    /** Withdraws a resting order. */
    public boolean cancel(long orderId) {
        int slot = book.slotOf(orderId);
        if (slot == ArrayOrderBook.NIL) {
            listener.onRejected(orderId, RejectReason.UNKNOWN_ORDER);
            return false;
        }
        int remaining = book.slotShares(slot);
        book.unlink(slot);
        listener.onCancelled(orderId, remaining);
        return true;
    }

    /**
     * Cancels {@code originalId} and submits {@code newId} on the same side.
     *
     * <p>The replacement is a genuinely new order: it goes to the back of the
     * queue at its price and it will cross if it is marketable. It does not
     * inherit the original's queue position. Exchanges work this way, and it is
     * the reason a trader who only wants to reduce size cancels down rather
     * than replacing.
     */
    public boolean replace(long originalId, long newId, int price, int shares) {
        int slot = book.slotOf(originalId);
        if (slot == ArrayOrderBook.NIL) {
            listener.onRejected(originalId, RejectReason.UNKNOWN_ORDER);
            return false;
        }
        boolean buy = book.slotIsBuy(slot);
        int remaining = book.slotShares(slot);
        book.unlink(slot);
        listener.onCancelled(originalId, remaining);
        limit(newId, buy, price, shares, TimeInForce.GTC);
        return true;
    }

    // ---------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------

    /**
     * Consumes opposing liquidity, best price first and FIFO within a level.
     *
     * @param anyPrice true for market orders, which ignore {@code limitPrice}
     * @return the shares left unfilled
     */
    private int match(long takerId, boolean takerBuy, int limitPrice, int shares, boolean anyPrice) {
        boolean makerBuy = !takerBuy;
        int remaining = shares;

        while (remaining > 0) {
            int tick = book.bestTick(makerBuy);
            if (tick == ArrayOrderBook.NIL) break;

            int restingPrice = book.priceOfTick(tick);
            if (!anyPrice && !crosses(takerBuy, limitPrice, restingPrice)) break;

            // Drain this level in arrival order. Consuming from the touch means
            // the book repairs its own best-price cache as levels empty, so the
            // outer loop simply asks for the new touch.
            while (remaining > 0) {
                int slot = book.headSlot(makerBuy, tick);
                if (slot == ArrayOrderBook.NIL) break;

                int makerShares = book.slotShares(slot);
                long makerId = book.slotId(slot);
                int fill = Math.min(remaining, makerShares);

                listener.onFill(makerId, takerId, restingPrice, fill, takerBuy);
                fills++;
                filledShares += fill;

                book.reduceSlot(slot, fill);
                remaining -= fill;
            }
        }
        return remaining;
    }

    /** Would a taker at {@code limitPrice} accept a fill at {@code restingPrice}? */
    private static boolean crosses(boolean takerBuy, int limitPrice, int restingPrice) {
        return takerBuy ? restingPrice <= limitPrice : restingPrice >= limitPrice;
    }

    /**
     * Total opposing shares a taker at {@code limitPrice} could reach. Walks
     * levels outward from the touch and stops at the first uncrossable price,
     * so it costs one pass over the marketable levels rather than the book.
     */
    private long available(boolean takerBuy, int limitPrice, boolean anyPrice) {
        boolean makerBuy = !takerBuy;
        long total = 0;
        int tick = book.bestTick(makerBuy);
        while (tick != ArrayOrderBook.NIL) {
            if (!anyPrice && !crosses(takerBuy, limitPrice, book.priceOfTick(tick))) break;
            total += book.sharesAtTick(makerBuy, tick);
            tick = book.nextTick(makerBuy, tick);
        }
        return total;
    }
}
