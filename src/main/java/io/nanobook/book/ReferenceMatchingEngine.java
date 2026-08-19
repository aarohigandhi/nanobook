package io.nanobook.book;

import io.nanobook.book.ExecutionListener.RejectReason;
import io.nanobook.book.MatchingEngine.TimeInForce;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberately naive matching engine, written to be read rather than to run.
 *
 * <p>This is the oracle {@link io.nanobook.tools.EngineFuzzer} tests
 * {@link MatchingEngine} against, and it earns that role only by sharing no
 * code with it. It keeps its own {@link TreeMap} of price to a queue of orders
 * per side and does its own matching. Two implementations that call into the
 * same helper agree by construction, which proves nothing.
 *
 * <p>The observable contract is identical: same calls in, same execution report
 * stream out, down to the ordering of individual reports.
 */
public final class ReferenceMatchingEngine {

    private static final class Order {
        final long id;
        final boolean buy;
        final int price;
        int shares;

        Order(long id, boolean buy, int price, int shares) {
            this.id = id;
            this.buy = buy;
            this.price = price;
            this.shares = shares;
        }
    }

    /** Descending, so {@code firstEntry()} is the best bid. */
    private final TreeMap<Integer, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());

    /** Ascending, so {@code firstEntry()} is the best ask. */
    private final TreeMap<Integer, ArrayDeque<Order>> asks = new TreeMap<>();

    private final Map<Long, Order> byId = new HashMap<>();
    private final ExecutionListener listener;

    private long fills;

    public ReferenceMatchingEngine(ExecutionListener listener) {
        this.listener = listener;
    }

    public long fills() {
        return fills;
    }

    private TreeMap<Integer, ArrayDeque<Order>> side(boolean buy) {
        return buy ? bids : asks;
    }

    // ---------------------------------------------------------------
    // Order entry
    // ---------------------------------------------------------------

    public void limit(long orderId, boolean buy, int price, int shares, TimeInForce timeInForce) {
        if (shares <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (price <= 0) {
            listener.onRejected(orderId, RejectReason.INVALID_PRICE);
            return;
        }
        if (byId.containsKey(orderId)) {
            listener.onRejected(orderId, RejectReason.DUPLICATE_ID);
            return;
        }
        if (timeInForce == TimeInForce.FOK && available(buy, price) < shares) {
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
                Order resting = new Order(orderId, buy, price, remaining);
                byId.put(orderId, resting);
                side(buy).computeIfAbsent(price, key -> new ArrayDeque<>()).addLast(resting);
                listener.onResting(orderId, buy, price, remaining);
            }
            case IOC, FOK -> listener.onExpired(orderId, remaining);
        }
    }

    public void limit(long orderId, boolean buy, int price, int shares) {
        limit(orderId, buy, price, shares, TimeInForce.GTC);
    }

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

    public boolean cancel(long orderId) {
        Order order = byId.get(orderId);
        if (order == null) {
            listener.onRejected(orderId, RejectReason.UNKNOWN_ORDER);
            return false;
        }
        detach(order);
        listener.onCancelled(orderId, order.shares);
        return true;
    }

    public boolean replace(long originalId, long newId, int price, int shares) {
        Order order = byId.get(originalId);
        if (order == null) {
            listener.onRejected(originalId, RejectReason.UNKNOWN_ORDER);
            return false;
        }
        boolean buy = order.buy;
        int remaining = order.shares;
        detach(order);
        listener.onCancelled(originalId, remaining);
        limit(newId, buy, price, shares, TimeInForce.GTC);
        return true;
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private void detach(Order order) {
        byId.remove(order.id);
        TreeMap<Integer, ArrayDeque<Order>> book = side(order.buy);
        ArrayDeque<Order> queue = book.get(order.price);
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                book.remove(order.price);
            }
        }
    }

    private int match(long takerId, boolean takerBuy, int limitPrice, int shares, boolean anyPrice) {
        TreeMap<Integer, ArrayDeque<Order>> opposite = side(!takerBuy);
        int remaining = shares;

        while (remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Integer, ArrayDeque<Order>> best = opposite.firstEntry();
            int restingPrice = best.getKey();
            if (!anyPrice && !crosses(takerBuy, limitPrice, restingPrice)) break;

            ArrayDeque<Order> queue = best.getValue();
            while (remaining > 0 && !queue.isEmpty()) {
                Order maker = queue.peekFirst();
                int fill = Math.min(remaining, maker.shares);

                listener.onFill(maker.id, takerId, restingPrice, fill, takerBuy);
                fills++;

                maker.shares -= fill;
                remaining -= fill;
                if (maker.shares == 0) {
                    queue.pollFirst();
                    byId.remove(maker.id);
                }
            }
            if (queue.isEmpty()) {
                opposite.remove(restingPrice);
            }
        }
        return remaining;
    }

    private static boolean crosses(boolean takerBuy, int limitPrice, int restingPrice) {
        return takerBuy ? restingPrice <= limitPrice : restingPrice >= limitPrice;
    }

    private long available(boolean takerBuy, int limitPrice) {
        long total = 0;
        for (Map.Entry<Integer, ArrayDeque<Order>> level : side(!takerBuy).entrySet()) {
            if (!crosses(takerBuy, limitPrice, level.getKey())) break;
            for (Order order : level.getValue()) {
                total += order.shares;
            }
        }
        return total;
    }

    // ---------------------------------------------------------------
    // Queries, for cross-checking book state as well as report streams
    // ---------------------------------------------------------------

    public int bestBid() {
        return bids.isEmpty() ? OrderBook.NO_PRICE : bids.firstKey();
    }

    public int bestAsk() {
        return asks.isEmpty() ? OrderBook.NO_PRICE : asks.firstKey();
    }

    public int orderCount() {
        return byId.size();
    }
}
