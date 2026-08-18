package io.nanobook.book;

/**
 * Receives the execution report stream produced by {@link MatchingEngine}.
 *
 * <p>This stream is the engine's observable output, and the thing Phase 5
 * compares between implementations. It must therefore be a pure function of the
 * input sequence: same orders in, byte-identical reports out, every run. No
 * wall-clock timestamps, no identity hash codes, nothing that varies between
 * JVM runs.
 *
 * <p>All methods default to no-ops.
 */
public interface ExecutionListener {

    /** Why an order was refused outright. */
    enum RejectReason {
        /** An order with this id is already resting. */
        DUPLICATE_ID,
        /** Cancel or replace named an order that is not resting. */
        UNKNOWN_ORDER,
        /** Quantity was zero or negative. */
        INVALID_QUANTITY,
        /** Limit price was not positive, or not on a tick boundary. */
        INVALID_PRICE,
        /** Fill-or-kill could not be filled in full. */
        INSUFFICIENT_LIQUIDITY
    }

    /** The order passed validation and entered the engine. */
    default void onAccepted(long orderId, boolean buy, int price, int shares) {}

    /** The order never entered the book. */
    default void onRejected(long orderId, RejectReason reason) {}

    /**
     * A trade. Always printed at the <b>maker's resting price</b> -- the taker
     * crossed the spread and gets price improvement, never the reverse.
     */
    default void onFill(long makerOrderId, long takerOrderId, int price, int shares, boolean takerBuy) {}

    /** The unfilled remainder came to rest in the book. */
    default void onResting(long orderId, boolean buy, int price, int shares) {}

    /** An order was withdrawn by request. */
    default void onCancelled(long orderId, int remainingShares) {}

    /**
     * The unfilled remainder of an order that is not permitted to rest --
     * immediate-or-cancel, or a market order that exhausted the book.
     */
    default void onExpired(long orderId, int remainingShares) {}
}
