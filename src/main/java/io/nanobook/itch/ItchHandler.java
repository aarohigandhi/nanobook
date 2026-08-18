package io.nanobook.itch;

/**
 * Callback interface for decoded ITCH messages.
 *
 * <p>Every parameter is a primitive. Nothing here hands you an object, because
 * the parser must not allocate one per message -- at ~300M messages in a
 * trading day, one small object per message is tens of gigabytes of garbage.
 * Ticker symbols arrive as an {@code int} symbol id from {@link SymbolTable}
 * rather than a {@code String} for the same reason.
 *
 * <p>All methods default to no-ops so an implementation overrides only the
 * messages it cares about. The book, for instance, ignores {@code onTrade}
 * entirely: non-cross trades are hidden liquidity and never touch the visible
 * book.
 *
 * <p>Prices are in raw ITCH units -- uint32 with 4 implied decimals, so
 * 1234500 is $123.4500. Do not convert to double.
 */
public interface ItchHandler {

    /**
     * System event. Code {@code 'Q'} is start of market hours, {@code 'M'} is
     * end of market hours, {@code 'C'} is end of messages.
     */
    default void onSystemEvent(long timestamp, char eventCode) {}

    /**
     * Stock directory. Emitted once per symbol at the start of the session and
     * establishes the {@code stockLocate -> symbolId} mapping used everywhere
     * else. Add/Trade messages carry the symbol too, but Executed/Cancel/Delete
     * do not -- they only carry the order reference -- so you either keep this
     * mapping or resolve the symbol through the order.
     */
    default void onStockDirectory(long timestamp, int stockLocate, int symbolId, int roundLotSize) {}

    /** Trading state change: halt, resume, quotation-only. */
    default void onTradingAction(long timestamp, int stockLocate, char tradingState) {}

    /**
     * New displayed order at a price level. {@code buy} is true for the bid
     * side. This and {@link #onOrderDelete} dominate the message mix.
     */
    default void onAddOrder(long timestamp, int stockLocate, long orderRef,
                            boolean buy, int shares, int symbolId, int price) {}

    /**
     * An existing order executed against incoming liquidity, at the price it
     * was resting at. Reduce the order by {@code shares}; remove it if that
     * takes it to zero.
     */
    default void onOrderExecuted(long timestamp, int stockLocate, long orderRef,
                                 int shares, long matchNumber) {}

    /**
     * As {@link #onOrderExecuted}, but printed at a different price than the
     * order was resting at. {@code printable} false means the trade should be
     * excluded from volume statistics.
     */
    default void onOrderExecutedWithPrice(long timestamp, int stockLocate, long orderRef,
                                          int shares, long matchNumber,
                                          boolean printable, int price) {}

    /** Partial cancel: reduce the order by {@code shares}, keeping its place. */
    default void onOrderCancel(long timestamp, int stockLocate, long orderRef, int shares) {}

    /** Full cancel: remove the order from the book. */
    default void onOrderDelete(long timestamp, int stockLocate, long orderRef) {}

    /**
     * Replace: remove {@code originalRef} and add {@code newRef} with the given
     * shares and price. The replacement joins the BACK of the queue at its
     * level -- it does not inherit the original queue position.
     */
    default void onOrderReplace(long timestamp, int stockLocate, long originalRef,
                                long newRef, int shares, int price) {}

    /**
     * Non-cross trade against hidden liquidity. Does NOT modify the visible
     * book -- consume it for trade prints and volume only.
     */
    default void onTrade(long timestamp, int stockLocate, long orderRef, boolean buy,
                         int shares, int symbolId, int price, long matchNumber) {}

    /** Opening, closing, or halt cross. Also does not modify the visible book. */
    default void onCrossTrade(long timestamp, int stockLocate, long shares, int symbolId,
                              int price, long matchNumber, char crossType) {}

    /** A previously reported trade was broken. Back it out of volume stats. */
    default void onBrokenTrade(long timestamp, int stockLocate, long matchNumber) {}

    /**
     * Any message the handler did not otherwise claim, including types this
     * interface does not model. Useful for the message-mix histogram.
     */
    default void onOther(long timestamp, int stockLocate, byte messageType) {}
}
