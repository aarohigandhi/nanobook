package io.nanobook.tools;

import io.nanobook.book.OrderBook;
import io.nanobook.itch.SymbolTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookReplayTest {

    private static final long TS = 34_200_000_000_000L;

    /** Interns symbols the way the parser would, then announces them. */
    private static BookReplay start(String target, SymbolTable symbols, String... session) {
        BookReplay replay = new BookReplay(target, 100, symbols);
        for (int i = 0; i < session.length; i++) {
            int symbolId = symbols.intern(SymbolTable.pack(session[i]));
            replay.onStockDirectory(TS, 10 + i, symbolId, 100);
        }
        return replay;
    }

    @Test
    void isolatesTheTargetSymbolByStockLocate() {
        SymbolTable symbols = new SymbolTable();
        BookReplay replay = start("MSFT", symbols, "AAPL", "MSFT", "NVDA");

        // locate 10 is AAPL, 11 is MSFT, 12 is NVDA
        replay.onAddOrder(TS, 10, 1, true, 100, 0, 10_000);
        replay.onAddOrder(TS, 11, 2, true, 300, 1, 20_000);
        replay.onAddOrder(TS, 12, 3, true, 500, 2, 30_000);

        OrderBook book = replay.book();
        assertNotNull(book);
        assertEquals(1, book.orderCount(), "only MSFT messages belong in this book");
        assertEquals(20_000, book.bestBid());
        assertEquals(300, book.bestBidSize());
    }

    @Test
    void tracksTheFullOrderLifecycleForItsSymbol() {
        SymbolTable symbols = new SymbolTable();
        BookReplay replay = start("AAPL", symbols, "AAPL");

        replay.onAddOrder(TS, 10, 1, true, 500, 0, 10_000);
        replay.onAddOrder(TS, 10, 2, false, 400, 0, 10_100);
        replay.onOrderCancel(TS, 10, 1, 200);
        replay.onOrderExecuted(TS, 10, 2, 400, 1L);
        replay.onOrderReplace(TS, 10, 1, 3, 250, 10_100);

        OrderBook book = replay.book();
        assertEquals(10_100, book.bestBid());
        assertEquals(250, book.bestBidSize());
        assertEquals(OrderBook.NO_PRICE, book.bestAsk(), "the ask fully executed");
        assertEquals(1, book.orderCount());
    }

    @Test
    void reportsNothingWhenTheTickerIsAbsent() {
        SymbolTable symbols = new SymbolTable();
        BookReplay replay = start("TSLA", symbols, "AAPL", "MSFT");

        replay.onAddOrder(TS, 10, 1, true, 100, 0, 10_000);

        assertNull(replay.book());
        assertTrue(render(replay).contains("never appeared"));
    }

    @Test
    void reportsQuoteActivityAndClosingBook() {
        SymbolTable symbols = new SymbolTable();
        BookReplay replay = start("AAPL", symbols, "AAPL");

        replay.onAddOrder(TS, 10, 1, true, 100, 0, 10_000);
        replay.onAddOrder(TS, 10, 2, false, 150, 0, 10_200);
        replay.onAddOrder(TS, 10, 3, true, 200, 0, 10_100);  // improves the bid

        String report = render(replay);
        assertTrue(report.contains("book: AAPL"), report);
        assertTrue(report.contains("stock locate"), report);
        assertTrue(report.contains("top-of-book changes"), report);
        assertTrue(report.contains("1.0100"), "closing bid should print as dollars\n" + report);
    }

    /** A crossed quote is real market data, not a bug -- it must not be averaged in. */
    @Test
    void locksAndCrossesAreCountedNotAveraged() {
        SymbolTable symbols = new SymbolTable();
        BookReplay replay = start("AAPL", symbols, "AAPL");

        replay.onAddOrder(TS, 10, 1, true, 100, 0, 10_200);
        replay.onAddOrder(TS, 10, 2, false, 100, 0, 10_100); // crossed

        String report = render(replay);
        assertTrue(report.matches("(?s).*locked or crossed quotes\\s+1\\s.*"), report);
        assertFalse(report.contains("mean quoted spread"),
                "a crossed quote has a negative spread and must not be averaged in\n" + report);
    }

    private static String render(BookReplay replay) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        replay.report(new PrintStream(captured, true, StandardCharsets.UTF_8));
        return captured.toString(StandardCharsets.UTF_8);
    }
}
