package io.nanobook.book;

/**
 * PHASE 2 -- your work. The obviously-correct, deliberately slow book.
 *
 * <p>Write this one first, and write it the dumbest way you can stand:
 *
 * <pre>
 *   TreeMap&lt;Integer, LinkedList&lt;Order&gt;&gt; bids;   // descending comparator
 *   TreeMap&lt;Integer, LinkedList&lt;Order&gt;&gt; asks;   // ascending
 *   HashMap&lt;Long, Order&gt; ordersByRef;            // for cancel/execute/delete
 * </pre>
 *
 * <p>Boxing, pointer chasing, an object per order -- all of it is fine here.
 * Legibility is the entire point. Every optimization you make in Phase 3 gets
 * measured against this, and in Phase 5 this becomes the oracle the fuzzer
 * trusts. <b>Do not delete it once the fast book works.</b> A reference
 * implementation you can read is worth more than the throughput it costs, and
 * "I kept a slow implementation to prove the fast one correct" is a genuinely
 * strong thing to be able to say out loud.
 *
 * <h2>Message handling</h2>
 * <ul>
 *   <li>{@code onAddOrder} -- insert at the BACK of the queue at that price.</li>
 *   <li>{@code onOrderExecuted} / {@code onOrderExecutedWithPrice} -- reduce the
 *       order by the executed shares; remove it when it hits zero. The price on
 *       the WithPrice variant is the print price, not the resting price: it does
 *       not move the order.</li>
 *   <li>{@code onOrderCancel} -- partial. Reduce shares, keep queue position.</li>
 *   <li>{@code onOrderDelete} -- full. Remove the order outright.</li>
 *   <li>{@code onOrderReplace} -- remove the original ref, add the new ref at the
 *       BACK of the queue at its price. The replacement does NOT inherit queue
 *       priority. This is the single most commonly botched rule in this file.</li>
 *   <li>{@code onTrade} / {@code onCrossTrade} -- ignore. Hidden and auction
 *       liquidity never touched the visible book.</li>
 * </ul>
 *
 * <h2>Two things that will bite you</h2>
 * <ol>
 *   <li><b>Empty price levels.</b> When the last order at a level is removed,
 *       remove the level from the map. Leave it behind and {@code bestBid()}
 *       starts returning a price with zero size resting at it.</li>
 *   <li><b>Unknown order references.</b> A session file does not start at the
 *       true beginning of the book, so you will get executes and deletes for
 *       orders you never saw added. Skip them silently, but COUNT them -- if
 *       that count is not small, your parser is misaligned, not the data.</li>
 * </ol>
 *
 * <p>Milestone: replay a full day and cross-check your reconstructed trades
 * against the stream's own Trade messages. If they agree, the book is right.
 */
public final class NaiveOrderBook implements OrderBook {

    @Override
    public int bestBid() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public int bestAsk() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public long bestBidSize() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public long bestAskSize() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public long sizeAt(int price, boolean buy) {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public int priceAtLevel(boolean buy, int level) {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public int orderCount() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Phase 2: implement NaiveOrderBook");
    }
}
