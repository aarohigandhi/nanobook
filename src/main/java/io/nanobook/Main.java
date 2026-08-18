package io.nanobook;

import io.nanobook.itch.ItchParser;
import io.nanobook.tools.MessageStats;

import java.nio.file.Path;

/**
 * Entry point. Replays an ITCH 5.0 file and reports the message mix.
 *
 * <pre>
 *   ./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
 * </pre>
 *
 * <p>Once the book exists, this is where a {@code --book SYMBOL} mode goes.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("""
                    usage: replay <itch-file>

                      <itch-file>  a decompressed Nasdaq TotalView-ITCH 5.0 file.
                                   See data/README.md for where to get one.

                    example:
                      ./gradlew replay --args="data/01302020.NASDAQ_ITCH50"
                    """);
            System.exit(2);
        }

        Path path = Path.of(args[0]);
        ItchParser parser = new ItchParser();
        MessageStats stats = new MessageStats(parser.symbols());

        System.out.println("replaying " + path.toAbsolutePath());

        long startedAt = System.nanoTime();
        parser.parse(path, stats);
        long elapsed = System.nanoTime() - startedAt;

        stats.report(System.out, elapsed);

        double megabytes = parser.bytesConsumed() / (1024.0 * 1024.0);
        System.out.printf("  consumed %.1f MiB%n%n", megabytes);
    }
}
