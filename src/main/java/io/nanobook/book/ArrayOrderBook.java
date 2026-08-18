package io.nanobook.book;

import io.nanobook.collections.LongIntHashMap;

import java.util.Arrays;

/**
 * The fast order book. Same observable behaviour as {@link NaiveOrderBook},
 * no objects. Phase 3.
 *
 * <h2>Structure of arrays</h2>
 * There is no {@code Order} class. An order is an index into a set of parallel
 * primitive arrays, so the fields of the orders being walked during a level
 * traversal are contiguous rather than scattered across the heap. Slots come
 * from an int free-list threaded through {@code orderNext}, so the arrays are
 * allocated once and reused for the life of the session.
 *
 * <h2>Price levels are a flat array</h2>
 * A {@code TreeMap} lookup is a red-black descent with a likely cache miss at
 * every node. Real books are shallow and prices cluster, so levels are indexed
 * directly by tick offset from a base price -- one array load, no comparisons.
 *
 * <p>Prices far from the touch are legal and do occur. Rather than reject them
 * or fall back to a map, the window <b>regrows</b>: it doubles outward and the
 * existing levels are copied to their new offsets. That allocates, so it is not
 * free -- but it happens a handful of times during warmup and never in steady
 * state. Order slots grow the same way. Size both generously at construction
 * and neither happens at all.
 *
 * <p>Order prices are stored, not tick indices, precisely so that regrowing the
 * window does not require touching every live order.
 *
 * <h2>Tracking the touch</h2>
 * The best bid and ask are cached as tick indices and repaired incrementally.
 * An add can only improve the touch, which is one compare. A removal only
 * triggers a scan when it empties the touch level, and that scan walks outward
 * one tick at a time.
 *
 * <h2>Limitation</h2>
 * Prices must be a multiple of {@code tickSize}. The default of 100 raw ITCH
 * units is one cent, which is right for any stock above $1.00. Sub-dollar names
 * quote in sub-pennies and need {@code tickSize = 1} and a correspondingly
 * larger window. A misaligned price throws rather than rounding, because
 * silently snapping a price to a tick would corrupt the book in a way that
 * still looks plausible.
 */
public final class ArrayOrderBook implements OrderBook {

    /** Accept messages for every symbol. */
    public static final int ANY_STOCK = NaiveOrderBook.ANY_STOCK;

    /** Empty slot / empty level / absent tick. */
    static final int NIL = -1;

    private static final int BID = 0;
    private static final int ASK = 1;

    private static final int DEFAULT_TICK_SIZE = 100;      // one cent, in ITCH units
    private static final int DEFAULT_LEVEL_CAPACITY = 1 << 16;
    private static final int DEFAULT_ORDER_CAPACITY = 1 << 16;

    private final int stockLocate;
    private final int tickSize;

    // --- price levels, one set per side, indexed by tick ---------------
    private int[] bidHead;
    private int[] bidTail;
    private long[] bidShares;
    private int[] askHead;
    private int[] askTail;
    private long[] askShares;

    private int levelCapacity;
    private int basePrice;      // price of tick 0
    private boolean based;      // has basePrice been established yet

    private int bestBidTick = NIL;
    private int bestAskTick = NIL;

    // --- orders, structure of arrays -----------------------------------
    private long[] orderId;
    private int[] orderPrice;
    private int[] orderShares;
    private int[] orderNext;
    private int[] orderPrev;
    private byte[] orderSide;

    private int freeHead = NIL;
    private int liveOrders;

    private final LongIntHashMap slotByReference;

    private long unknownReferences;
    private long levelRegrowths;

    public ArrayOrderBook() {
        this(ANY_STOCK, DEFAULT_TICK_SIZE, DEFAULT_LEVEL_CAPACITY, DEFAULT_ORDER_CAPACITY);
    }

    public ArrayOrderBook(int stockLocate) {
        this(stockLocate, DEFAULT_TICK_SIZE, DEFAULT_LEVEL_CAPACITY, DEFAULT_ORDER_CAPACITY);
    }

    public ArrayOrderBook(int stockLocate, int tickSize, int levelCapacity, int orderCapacity) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
        if (levelCapacity <= 1) throw new IllegalArgumentException("levelCapacity too small");
        if (orderCapacity <= 1) throw new IllegalArgumentException("orderCapacity too small");

        this.stockLocate = stockLocate;
        this.tickSize = tickSize;
        this.levelCapacity = Integer.highestOneBit(levelCapacity - 1) << 1;

        allocateLevels(this.levelCapacity);

        int slots = Integer.highestOneBit(orderCapacity - 1) << 1;
        this.orderId = new long[slots];
        this.orderPrice = new int[slots];
        this.orderShares = new int[slots];
        this.orderNext = new int[slots];
        this.orderPrev = new int[slots];
        this.orderSide = new byte[slots];
        this.slotByReference = new LongIntHashMap(slots);
        buildFreeList(0, slots);
    }

    private void allocateLevels(int capacity) {
        bidHead = new int[capacity];
        bidTail = new int[capacity];
        bidShares = new long[capacity];
        askHead = new int[capacity];
        askTail = new int[capacity];
        askShares = new long[capacity];
        Arrays.fill(bidHead, NIL);
        Arrays.fill(bidTail, NIL);
        Arrays.fill(askHead, NIL);
        Arrays.fill(askTail, NIL);
    }

    /** Threads slots {@code [from, to)} onto the free list, newest first. */
    private void buildFreeList(int from, int to) {
        for (int slot = to - 1; slot >= from; slot--) {
            orderNext[slot] = freeHead;
            freeHead = slot;
        }
    }

    private boolean ignores(int locate) {
        return stockLocate != ANY_STOCK && locate != stockLocate;
    }

    // ---------------------------------------------------------------
    // Message handling
    // ---------------------------------------------------------------

    @Override
    public void onAddOrder(long timestamp, int locate, long orderRef,
                           boolean buy, int shares, int symbolId, int price) {
        if (ignores(locate)) return;
        insert(orderRef, buy, price, shares);
    }

    @Override
    public void onOrderExecuted(long timestamp, int locate, long orderRef,
                                int shares, long matchNumber) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    @Override
    public void onOrderExecutedWithPrice(long timestamp, int locate, long orderRef,
                                         int shares, long matchNumber,
                                         boolean printable, int price) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    @Override
    public void onOrderCancel(long timestamp, int locate, long orderRef, int shares) {
        if (ignores(locate)) return;
        reduce(orderRef, shares);
    }

    @Override
    public void onOrderDelete(long timestamp, int locate, long orderRef) {
        if (ignores(locate)) return;
        int slot = slotByReference.get(orderRef);
        if (slot == LongIntHashMap.NO_VALUE) {
            unknownReferences++;
            return;
        }
        unlink(slot);
    }

    @Override
    public void onOrderReplace(long timestamp, int locate, long originalRef,
                               long newRef, int shares, int price) {
        if (ignores(locate)) return;
        int slot = slotByReference.get(originalRef);
        if (slot == LongIntHashMap.NO_VALUE) {
            // No side information on a replace message, so an unknown original
            // means the replacement cannot be placed either.
            unknownReferences++;
            return;
        }
        boolean buy = orderSide[slot] == BID;
        unlink(slot);
        insert(newRef, buy, price, shares);
    }

    // ---------------------------------------------------------------
    // Mutation
    // ---------------------------------------------------------------

    private void insert(long reference, boolean buy, int price, int shares) {
        int tick = ensureTick(price);
        int slot = allocateSlot();

        orderId[slot] = reference;
        orderPrice[slot] = price;
        orderShares[slot] = shares;
        orderSide[slot] = (byte) (buy ? BID : ASK);
        orderNext[slot] = NIL;

        int[] head = buy ? bidHead : askHead;
        int[] tail = buy ? bidTail : askTail;
        long[] resting = buy ? bidShares : askShares;

        int last = tail[tick];
        orderPrev[slot] = last;
        if (last == NIL) {
            head[tick] = slot;
        } else {
            orderNext[last] = slot;
        }
        tail[tick] = slot;
        resting[tick] += shares;

        slotByReference.put(reference, slot);
        liveOrders++;

        // An add can only improve the touch.
        if (buy) {
            if (bestBidTick == NIL || tick > bestBidTick) bestBidTick = tick;
        } else {
            if (bestAskTick == NIL || tick < bestAskTick) bestAskTick = tick;
        }
    }

    private void reduce(long reference, int shares) {
        int slot = slotByReference.get(reference);
        if (slot == LongIntHashMap.NO_VALUE) {
            unknownReferences++;
            return;
        }
        reduceSlot(slot, shares);
    }

    /** Reduces a slot by {@code shares}, unlinking it if that empties it. */
    void reduceSlot(int slot, int shares) {
        int remaining = orderShares[slot] - shares;
        if (remaining <= 0) {
            unlink(slot);
            return;
        }
        orderShares[slot] = remaining;
        boolean buy = orderSide[slot] == BID;
        int tick = tickOf(orderPrice[slot]);
        if (buy) {
            bidShares[tick] -= shares;
        } else {
            askShares[tick] -= shares;
        }
    }

    /** Removes a slot from its level and returns it to the free list. */
    void unlink(int slot) {
        boolean buy = orderSide[slot] == BID;
        int tick = tickOf(orderPrice[slot]);

        int[] head = buy ? bidHead : askHead;
        int[] tail = buy ? bidTail : askTail;
        long[] resting = buy ? bidShares : askShares;

        int previous = orderPrev[slot];
        int next = orderNext[slot];

        if (previous == NIL) {
            head[tick] = next;
        } else {
            orderNext[previous] = next;
        }
        if (next == NIL) {
            tail[tick] = previous;
        } else {
            orderPrev[next] = previous;
        }

        resting[tick] -= orderShares[slot];

        slotByReference.remove(orderId[slot]);
        liveOrders--;
        releaseSlot(slot);

        // Only a removal that empties the touch level can move the touch.
        if (head[tick] == NIL) {
            resting[tick] = 0L; // guard against drift from unknown-reference traffic
            if (buy && tick == bestBidTick) {
                bestBidTick = scanDown(tick - 1);
            } else if (!buy && tick == bestAskTick) {
                bestAskTick = scanUp(tick + 1);
            }
        }
    }

    private int scanDown(int from) {
        for (int tick = from; tick >= 0; tick--) {
            if (bidHead[tick] != NIL) return tick;
        }
        return NIL;
    }

    private int scanUp(int from) {
        for (int tick = from; tick < levelCapacity; tick++) {
            if (askHead[tick] != NIL) return tick;
        }
        return NIL;
    }

    // ---------------------------------------------------------------
    // Slot allocation
    // ---------------------------------------------------------------

    private int allocateSlot() {
        if (freeHead == NIL) {
            growOrders();
        }
        int slot = freeHead;
        freeHead = orderNext[slot];
        return slot;
    }

    private void releaseSlot(int slot) {
        orderNext[slot] = freeHead;
        freeHead = slot;
    }

    private void growOrders() {
        int previous = orderId.length;
        int grown = previous << 1;
        if (grown <= 0) throw new IllegalStateException("order slots exhausted");

        orderId = Arrays.copyOf(orderId, grown);
        orderPrice = Arrays.copyOf(orderPrice, grown);
        orderShares = Arrays.copyOf(orderShares, grown);
        orderNext = Arrays.copyOf(orderNext, grown);
        orderPrev = Arrays.copyOf(orderPrev, grown);
        orderSide = Arrays.copyOf(orderSide, grown);
        buildFreeList(previous, grown);
    }

    // ---------------------------------------------------------------
    // Tick mapping and window growth
    // ---------------------------------------------------------------

    int tickOf(int price) {
        return (price - basePrice) / tickSize;
    }

    int priceOfTick(int tick) {
        return basePrice + tick * tickSize;
    }

    /** Maps a price to a tick, establishing or regrowing the window as needed. */
    private int ensureTick(int price) {
        if (price % tickSize != 0) {
            throw new IllegalArgumentException(
                    "price " + price + " is not a multiple of tickSize " + tickSize
                            + " -- sub-penny symbols need tickSize = 1");
        }
        if (!based) {
            // Centre the initial window on the first price seen.
            basePrice = price - (levelCapacity / 2) * tickSize;
            based = true;
        }
        int tick = (price - basePrice) / tickSize;
        if (tick < 0 || tick >= levelCapacity) {
            regrow(price);
            tick = (price - basePrice) / tickSize;
        }
        return tick;
    }

    /**
     * Widens the level window to cover {@code price}, doubling until it fits
     * and keeping a margin so a drifting price does not regrow every message.
     */
    private void regrow(int price) {
        int lowPrice = Math.min(basePrice, price);
        int highPrice = Math.max(basePrice + (levelCapacity - 1) * tickSize, price);

        int span = (highPrice - lowPrice) / tickSize + 1;
        int grown = levelCapacity;
        while (grown < span * 2) {
            grown <<= 1;
            if (grown <= 0) throw new IllegalStateException("price window exhausted");
        }

        // Centre the old-and-new span inside the larger window.
        int margin = (grown - span) / 2;
        int newBase = lowPrice - margin * tickSize;
        int offset = (basePrice - newBase) / tickSize;

        int[] oldBidHead = bidHead, oldBidTail = bidTail, oldAskHead = askHead, oldAskTail = askTail;
        long[] oldBidShares = bidShares, oldAskShares = askShares;
        int oldCapacity = levelCapacity;

        levelCapacity = grown;
        allocateLevels(grown);

        System.arraycopy(oldBidHead, 0, bidHead, offset, oldCapacity);
        System.arraycopy(oldBidTail, 0, bidTail, offset, oldCapacity);
        System.arraycopy(oldBidShares, 0, bidShares, offset, oldCapacity);
        System.arraycopy(oldAskHead, 0, askHead, offset, oldCapacity);
        System.arraycopy(oldAskTail, 0, askTail, offset, oldCapacity);
        System.arraycopy(oldAskShares, 0, askShares, offset, oldCapacity);

        basePrice = newBase;
        if (bestBidTick != NIL) bestBidTick += offset;
        if (bestAskTick != NIL) bestAskTick += offset;
        levelRegrowths++;
    }

    // ---------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------

    @Override
    public int bestBid() {
        return bestBidTick == NIL ? NO_PRICE : priceOfTick(bestBidTick);
    }

    @Override
    public int bestAsk() {
        return bestAskTick == NIL ? NO_PRICE : priceOfTick(bestAskTick);
    }

    @Override
    public long bestBidSize() {
        return bestBidTick == NIL ? 0L : bidShares[bestBidTick];
    }

    @Override
    public long bestAskSize() {
        return bestAskTick == NIL ? 0L : askShares[bestAskTick];
    }

    @Override
    public long sizeAt(int price, boolean buy) {
        if (!based || price % tickSize != 0) return 0L;
        int tick = (price - basePrice) / tickSize;
        if (tick < 0 || tick >= levelCapacity) return 0L;
        return buy ? bidShares[tick] : askShares[tick];
    }

    @Override
    public int priceAtLevel(boolean buy, int level) {
        if (level < 0) return NO_PRICE;
        int tick = buy ? bestBidTick : bestAskTick;
        int remaining = level;
        while (tick != NIL) {
            if (remaining == 0) return priceOfTick(tick);
            remaining--;
            tick = buy ? scanDown(tick - 1) : scanUp(tick + 1);
        }
        return NO_PRICE;
    }

    @Override
    public int orderCount() {
        return liveOrders;
    }

    @Override
    public void clear() {
        Arrays.fill(bidHead, NIL);
        Arrays.fill(bidTail, NIL);
        Arrays.fill(askHead, NIL);
        Arrays.fill(askTail, NIL);
        Arrays.fill(bidShares, 0L);
        Arrays.fill(askShares, 0L);
        bestBidTick = NIL;
        bestAskTick = NIL;
        slotByReference.clear();
        liveOrders = 0;
        freeHead = NIL;
        buildFreeList(0, orderId.length);
        based = false;
        unknownReferences = 0;
        levelRegrowths = 0;
    }

    /** @see NaiveOrderBook#unknownReferences() */
    public long unknownReferences() {
        return unknownReferences;
    }

    /**
     * How many times the price window had to widen. Expect a small number
     * during warmup and zero thereafter; a count that keeps climbing means the
     * window was sized badly for the symbol.
     */
    public long levelRegrowths() {
        return levelRegrowths;
    }

    // ---------------------------------------------------------------
    // Package-private surface for MatchingEngine.
    //
    // The engine needs to walk levels and consume orders, which is exactly the
    // bookkeeping implemented above. Exposing it within the package beats
    // duplicating a second copy of the same array plumbing inside the engine.
    // ---------------------------------------------------------------

    /** Rests a new order. Returns its slot. */
    int addResting(long id, boolean buy, int price, int shares) {
        insert(id, buy, price, shares);
        return slotByReference.get(id);
    }

    /** @return the slot for an id, or {@link #NIL} */
    int slotOf(long id) {
        int slot = slotByReference.get(id);
        return slot == LongIntHashMap.NO_VALUE ? NIL : slot;
    }

    int bestTick(boolean buy) {
        return buy ? bestBidTick : bestAskTick;
    }

    /** Next non-empty level walking away from the touch, or {@link #NIL}. */
    int nextTick(boolean buy, int fromTick) {
        return buy ? scanDown(fromTick - 1) : scanUp(fromTick + 1);
    }

    int headSlot(boolean buy, int tick) {
        return buy ? bidHead[tick] : askHead[tick];
    }

    int slotShares(int slot) {
        return orderShares[slot];
    }

    long slotId(int slot) {
        return orderId[slot];
    }

    int slotPrice(int slot) {
        return orderPrice[slot];
    }

    boolean slotIsBuy(int slot) {
        return orderSide[slot] == BID;
    }

    long sharesAtTick(boolean buy, int tick) {
        return buy ? bidShares[tick] : askShares[tick];
    }
}
