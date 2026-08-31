package io.nanobook;

import io.nanobook.itch.CompositeHandler;
import io.nanobook.itch.ItchHandler;
import io.nanobook.itch.ItchParser;
import io.nanobook.tools.BookReplay;
import io.nanobook.tools.MessageStats;

import java.nio.file.Path;

/**
 * Entry point. Replays an ITCH 5.0 session and reports the message mix,
 * optionally reconstructing the book for one symbol along the way.
 *
 * <pre>
 *   ./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
 *   ./gradlew replay --args="data/01302020.NASDAQ_ITCH50 --book AAPL"
 * </pre>
 */
public final class Main {

    private static final String USAGE = """
            usage: replay <itch-file> [--book TICKER] [--tick UNITS]

              <itch-file>     a decompressed Nasdaq TotalView-ITCH 5.0 file.
                              See data/README.md for where to get one.
              --book TICKER   also reconstruct the order book for TICKER.
              --tick UNITS    price tick in raw ITCH units. Default 100, which
                              is one cent and correct for any stock above $1.
                              Sub-dollar symbols quote in sub-pennies and need
                              1. Do not reach for 1 just because a session
                              reports off-band orders: real sessions carry a
                              few junk prices ($0.0001 on a $289 stock), and a
                              tick of 1 would widen the window by 100x to hold
                              orders that never trade.

            examples:
              ./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
              ./gradlew replay --args="data/01302020.NASDAQ_ITCH50 --book AAPL"
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].startsWith("--")) {
            System.err.println(USAGE);
            System.exit(2);
        }

        Path path = Path.of(args[0]);
        String ticker = null;
        int tickSize = 100;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--book" -> ticker = requireValue(args, ++i, "--book");
                case "--tick" -> tickSize = Integer.parseInt(requireValue(args, ++i, "--tick"));
                default -> {
                    System.err.println("unrecognised option: " + args[i]);
                    System.err.println();
                    System.err.println(USAGE);
                    System.exit(2);
                }
            }
        }

        ItchParser parser = new ItchParser();
        MessageStats stats = new MessageStats(parser.symbols());
        BookReplay bookReplay = ticker == null
                ? null
                : new BookReplay(ticker, tickSize, parser.symbols());

        ItchHandler handler = bookReplay == null
                ? stats
                : new CompositeHandler(stats, bookReplay);

        System.out.println("replaying " + path.toAbsolutePath());
        if (ticker != null) {
            System.out.println("reconstructing book for " + ticker + " (tick " + tickSize + ")");
        }

        long startedAt = System.nanoTime();
        parser.parse(path, handler);
        long elapsed = System.nanoTime() - startedAt;

        stats.report(System.out, elapsed);
        if (bookReplay != null) {
            bookReplay.report(System.out);
        }

        System.out.printf("  consumed %.1f MiB%n%n", parser.bytesConsumed() / (1024.0 * 1024.0));
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            System.err.println(option + " needs a value");
            System.err.println();
            System.err.println(USAGE);
            System.exit(2);
        }
        return args[index];
    }
}
