package io.nanobook.tools;

import io.nanobook.book.ArrayOrderBook;
import io.nanobook.book.OrderBook;
import io.nanobook.itch.Itch;
import io.nanobook.itch.ItchHandler;
import io.nanobook.itch.SymbolTable;

import java.io.PrintStream;

/**
 * Reconstructs the book for one symbol from a full ITCH session.
 *
 * <p>The book is created lazily, when the session's Stock Directory message for
 * the target ticker reveals its stock locate. That indirection is unavoidable:
 * the messages that dominate the stream -- executed, cancel, delete, replace --
 * carry only an order reference and a locate, never a ticker. Filtering on the
 * locate is the only way to isolate one symbol without reconstructing all of
 * them.
 *
 * <p>Everything here forwards to the book. The counters exist so a replay
 * reports something checkable rather than just finishing.
 */
public final class BookReplay implements ItchHandler {

    private final long targetSymbol;
    private final String ticker;
    private final int tickSize;
    private final SymbolTable symbols;

    private ArrayOrderBook book;
    private int stockLocate = -1;

    private long topOfBookChanges;
    private long lockedOrCrossedQuotes;
    private long spreadSamples;
    private long spreadTotal;
    private int lastBid = OrderBook.NO_PRICE;
    private int lastAsk = OrderBook.NO_PRICE;

    public BookReplay(String ticker, int tickSize, SymbolTable symbols) {
        this.ticker = ticker;
        this.targetSymbol = SymbolTable.pack(ticker);
        this.tickSize = tickSize;
        this.symbols = symbols;
    }

    /** The reconstructed book, or null if the ticker never appeared. */
    public OrderBook book() {
        return book;
    }

    /**
     * The directory callback carries a symbol id rather than the raw ticker.
     * The parser has just interned this symbol, so resolving the target through
     * the table is a hash lookup and allocates nothing.
     */
    @Override
    public void onStockDirectory(long timestamp, int locate, int symbolId, int roundLotSize) {
        if (book != null) return;
        if (symbols.lookup(targetSymbol) != symbolId) return;
        stockLocate = locate;
        book = new ArrayOrderBook(locate, tickSize, 1 << 16, 1 << 20);
    }

    @Override
    public void onAddOrder(long timestamp, int locate, long orderRef,
                           boolean buy, int shares, int symbolId, int price) {
        if (book == null) return;
        book.onAddOrder(timestamp, locate, orderRef, buy, shares, symbolId, price);
        if (locate == stockLocate) sampleQuote();
    }

    @Override
    public void onOrderExecuted(long timestamp, int locate, long orderRef,
                                int shares, long matchNumber) {
        if (book == null) return;
        book.onOrderExecuted(timestamp, locate, orderRef, shares, matchNumber);
        if (locate == stockLocate) sampleQuote();
    }

    @Override
    public void onOrderExecutedWithPrice(long timestamp, int locate, long orderRef,
                                         int shares, long matchNumber,
                                         boolean printable, int price) {
        if (book == null) return;
        book.onOrderExecutedWithPrice(timestamp, locate, orderRef, shares, matchNumber, printable, price);
        if (locate == stockLocate) sampleQuote();
    }

    @Override
    public void onOrderCancel(long timestamp, int locate, long orderRef, int shares) {
        if (book == null) return;
        book.onOrderCancel(timestamp, locate, orderRef, shares);
        if (locate == stockLocate) sampleQuote();
    }

    @Override
    public void onOrderDelete(long timestamp, int locate, long orderRef) {
        if (book == null) return;
        book.onOrderDelete(timestamp, locate, orderRef);
        if (locate == stockLocate) sampleQuote();
    }

    @Override
    public void onOrderReplace(long timestamp, int locate, long originalRef,
                               long newRef, int shares, int price) {
        if (book == null) return;
        book.onOrderReplace(timestamp, locate, originalRef, newRef, shares, price);
        if (locate == stockLocate) sampleQuote();
    }

    /** Records a top-of-book change, if this message moved the quote. */
    private void sampleQuote() {
        int bid = book.bestBid();
        int ask = book.bestAsk();
        if (bid == lastBid && ask == lastAsk) return;

        topOfBookChanges++;
        lastBid = bid;
        lastAsk = ask;

        if (bid == OrderBook.NO_PRICE || ask == OrderBook.NO_PRICE) return;
        if (bid >= ask) {
            // Locked and crossed books are real. They happen around the open,
            // during halts, and whenever a hidden order sits inside the spread.
            lockedOrCrossedQuotes++;
            return;
        }
        spreadTotal += ask - bid;
        spreadSamples++;
    }

    public void report(PrintStream out) {
        out.println();
        out.println("book: " + ticker);
        out.println("-".repeat(58));

        if (book == null) {
            out.println("  ticker never appeared in this session");
            out.println();
            return;
        }

        out.printf("  %-28s %14s%n", "stock locate", stockLocate);
        out.printf("  %-28s %14s%n", "top-of-book changes", group(topOfBookChanges));
        out.printf("  %-28s %14s%n", "locked or crossed quotes", group(lockedOrCrossedQuotes));
        if (spreadSamples > 0) {
            out.printf("  %-28s %14s%n", "mean quoted spread",
                    dollars((int) (spreadTotal / spreadSamples)));
        }
        out.printf("  %-28s %14s%n", "orders resting at close", group(book.orderCount()));
        out.printf("  %-28s %14s%n", "unknown order refs", group(book.unknownReferences()));
        out.printf("  %-28s %14s%n", "price window regrowths", group(book.levelRegrowths()));

        out.println();
        out.println("  closing book");
        out.printf("    %-12s %12s   %-12s %12s%n", "bid", "size", "ask", "size");
        for (int level = 0; level < 5; level++) {
            int bid = book.priceAtLevel(true, level);
            int ask = book.priceAtLevel(false, level);
            if (bid == OrderBook.NO_PRICE && ask == OrderBook.NO_PRICE) break;
            out.printf("    %-12s %12s   %-12s %12s%n",
                    bid == OrderBook.NO_PRICE ? "-" : dollars(bid),
                    bid == OrderBook.NO_PRICE ? "-" : group(book.sizeAt(bid, true)),
                    ask == OrderBook.NO_PRICE ? "-" : dollars(ask),
                    ask == OrderBook.NO_PRICE ? "-" : group(book.sizeAt(ask, false)));
        }
        out.println();
    }

    private static String group(long value) {
        return String.format("%,d", value);
    }

    /** Raw ITCH price units carry 4 implied decimals. */
    private static String dollars(int price) {
        return String.format("%.4f", price / (double) Itch.PRICE_SCALE);
    }
}
