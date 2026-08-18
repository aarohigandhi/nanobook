package io.nanobook.book;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;

/**
 * The obviously-correct, deliberately slow order book. Phase 2.
 *
 * <p>Written the dumbest way it can be written: a {@link TreeMap} of price to a
 * {@link LinkedList} of orders per side, a {@link HashMap} from order reference
 * to order, and an {@code Order} object per resting order. Boxing, pointer
 * chasing, and allocation everywhere. That is the point -- this implementation
 * exists to be read and believed, not to be fast.
 *
 * <p>It earns its keep twice. Every optimization in {@link ArrayOrderBook} is
 * measured against it, and it is the oracle the differential tests trust: two
 * independent implementations of the same specification will disagree
 * somewhere, and the interesting question is where.
 *
 * <p>A book tracks one symbol. Filtering is by {@code stockLocate} rather than
 * symbol id, because the messages that matter most -- executed, cancel, delete,
 * replace -- carry only the order reference and the locate, never the ticker.
 */
public final class NaiveOrderBook implements OrderBook {

    /** Accept messages for every symbol. Useful for tests and fuzzing. */
    public static final int ANY_STOCK = -1;

    private static final class Order {
        final long reference;
        final boolean buy;
        final int price;
        int shares;

        Order(long reference, boolean buy, int price, int shares) {
            this.reference = reference;
            this.buy = buy;
            this.price = price;
            this.shares = shares;
        }
    }

    private final int stockLocate;

    /** Descending, so {@code firstKey()} is the best bid. */
    private final TreeMap<Integer, LinkedList<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

    /** Ascending, so {@code firstKey()} is the best ask. */
    private final TreeMap<Integer, LinkedList<Order>> asks = new TreeMap<>();

    private final Map<Long, Order> byReference = new HashMap<>();

    private long unknownReferences;

    public NaiveOrderBook() {
        this(ANY_STOCK);
    }

    public NaiveOrderBook(int stockLocate) {
        this.stockLocate = stockLocate;
    }

    private boolean ignores(int locate) {
        return stockLocate != ANY_STOCK && locate != stockLocate;
    }

    private TreeMap<Integer, LinkedList<Order>> side(boolean buy) {
        return buy ? bids : asks;
    }

    // ---------------------------------------------------------------
    // Message handling
    // ---------------------------------------------------------------

    @Override
    public void onAddOrder(long timestamp, int locate, long orderRef,
                           boolean buy, int shares, int symbolId, int price) {
        if (ignores(locate)) return;
        insert(new Order(orderRef, buy, price, shares));
    }

    @Override
    public void onOrderExecuted(long timestamp, int locate, long orderRef,
                                int shares, long matchNumber) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    /**
     * The price on this message is the price the trade PRINTED at, which can
     * differ from the price the order is resting at. It does not move the
     * order -- only the executed quantity matters to the book.
     */
    @Override
    public void onOrderExecutedWithPrice(long timestamp, int locate, long orderRef,
                                         int shares, long matchNumber,
                                         boolean printable, int price) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    /** Partial cancel. The order keeps its queue position. */
    @Override
    public void onOrderCancel(long timestamp, int locate, long orderRef, int shares) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    @Override
    public void onOrderDelete(long timestamp, int locate, long orderRef) {
        if (ignores(locate)) return;
        remove(orderRef);
    }

    /**
     * Remove the original, add the replacement at the BACK of the queue at its
     * price. The replacement does not inherit queue priority.
     *
     * <p>The replace message carries no side, so if the original reference is
     * unknown the replacement cannot be placed either -- there is no way to
     * know which side of the book it belongs on. Both are counted as unknown.
     */
    @Override
    public void onOrderReplace(long timestamp, int locate, long originalRef,
                               long newRef, int shares, int price) {
        if (ignores(locate)) return;
        Order original = remove(originalRef);
        if (original == null) return;
        insert(new Order(newRef, original.buy, price, shares));
    }

    // onTrade and onCrossTrade are intentionally not overridden: hidden and
    // auction liquidity never touched the visible book.

    // ---------------------------------------------------------------
    // Book mutation
    // ---------------------------------------------------------------

    private void insert(Order order) {
        byReference.put(order.reference, order);
        side(order.buy).computeIfAbsent(order.price, price -> new LinkedList<>()).addLast(order);
    }

    private void reduce(long orderRef, int shares) {
        Order order = byReference.get(orderRef);
        if (order == null) {
            unknownReferences++;
            return;
        }
        order.shares -= shares;
        if (order.shares <= 0) {
            remove(orderRef);
        }
    }

    private Order remove(long orderRef) {
        Order order = byReference.remove(orderRef);
        if (order == null) {
            unknownReferences++;
            return null;
        }
        TreeMap<Integer, LinkedList<Order>> book = side(order.buy);
        LinkedList<Order> level = book.get(order.price);
        if (level != null) {
            level.remove(order);
            // Empty levels must go. Left behind, they make bestBid() report a
            // price with nothing resting at it.
            if (level.isEmpty()) {
                book.remove(order.price);
            }
        }
        return order;
    }

    // ---------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------

    @Override
    public int bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    @Override
    public int bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    @Override
    public long bestBidSize() {
        return bids.isEmpty() ? 0L : total(bids.firstEntry().getValue());
    }

    @Override
    public long bestAskSize() {
        return asks.isEmpty() ? 0L : total(asks.firstEntry().getValue());
    }

    @Override
    public long sizeAt(int price, boolean buy) {
        LinkedList<Order> level = side(buy).get(price);
        return level == null ? 0L : total(level);
    }

    @Override
    public int priceAtLevel(boolean buy, int level) {
        if (level < 0) return NO_PRICE;
        int index = 0;
        for (int price : side(buy).keySet()) {
            if (index++ == level) return price;
        }
        return NO_PRICE;
    }

    @Override
    public int orderCount() {
        return byReference.size();
    }

    @Override
    public void clear() {
        bids.clear();
        asks.clear();
        byReference.clear();
        unknownReferences = 0;
    }

    /**
     * Messages referencing orders never seen added.
     *
     * <p>Expected to be non-zero: a session file does not begin at the true
     * start of the book, so early executes and deletes refer to orders that
     * were resting before the capture started. A count that is <i>large</i>
     * relative to the message volume means the parser is misaligned, not that
     * the data is odd -- worth asserting on rather than eyeballing.
     */
    public long unknownReferences() {
        return unknownReferences;
    }

    private static long total(LinkedList<Order> level) {
        long shares = 0;
        for (Order order : level) {
            shares += order.shares;
        }
        return shares;
    }
}
