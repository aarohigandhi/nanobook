package io.nanobook.book;

import io.nanobook.itch.ItchHandler;

/**
 * A single-symbol limit order book driven by an ITCH message stream.
 *
 * <p>Extending {@link ItchHandler} means a book IS a message handler -- feed it
 * straight from the parser. Implementations must ignore {@code onTrade} and
 * {@code onCrossTrade}: those represent hidden and auction liquidity and do not
 * change the visible book.
 *
 * <p>Two implementations exist on purpose. {@link NaiveOrderBook} is obviously
 * correct and slow; {@link ArrayOrderBook} is fast and non-obvious. Keeping
 * both is what makes differential fuzzing possible in Phase 5 -- the slow one
 * is the oracle that proves the fast one right.
 *
 * <p>All prices are raw ITCH units: uint32 with 4 implied decimals.
 */
public interface OrderBook extends ItchHandler {

    /** Returned by {@link #bestBid()} / {@link #bestAsk()} when a side is empty. */
    int NO_PRICE = -1;

    /** Highest resting bid price, or {@link #NO_PRICE}. */
    int bestBid();

    /** Lowest resting ask price, or {@link #NO_PRICE}. */
    int bestAsk();

    /** Total displayed shares resting at the best bid. */
    long bestBidSize();

    /** Total displayed shares resting at the best ask. */
    long bestAskSize();

    /** Total displayed shares at {@code price} on the given side. */
    long sizeAt(int price, boolean buy);

    /**
     * Price of the {@code level}-th price level from the top, zero-indexed, or
     * {@link #NO_PRICE} if the book is not that deep. Level 0 is the touch.
     */
    int priceAtLevel(boolean buy, int level);

    /** Number of live orders currently resting in the book. */
    int orderCount();

    /** Resets to an empty book. */
    void clear();

    /** Best ask minus best bid, or {@link #NO_PRICE} if either side is empty. */
    default int spread() {
        int bid = bestBid();
        int ask = bestAsk();
        if (bid == NO_PRICE || ask == NO_PRICE) return NO_PRICE;
        return ask - bid;
    }

    /**
     * Arithmetic mid. Note this is NOT the microprice -- it ignores the size
     * imbalance between the two sides, which is exactly the information that
     * carries short-horizon predictive power. Worth revisiting later.
     */
    default double mid() {
        int bid = bestBid();
        int ask = bestAsk();
        if (bid == NO_PRICE || ask == NO_PRICE) return Double.NaN;
        return (bid + ask) / 2.0;
    }
}
