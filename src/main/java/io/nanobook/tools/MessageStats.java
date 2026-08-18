package io.nanobook.tools;

import io.nanobook.itch.Itch;
import io.nanobook.itch.ItchHandler;
import io.nanobook.itch.SymbolTable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Counts the message mix of an ITCH session. This is the Phase 1 milestone.
 *
 * <p>The number to look for is the share of Add + Delete + Cancel + Replace
 * against everything else. It is overwhelmingly the majority, and executions
 * are a rounding error by comparison. That single fact is what justifies the
 * whole design of the fast book: the hot path is <i>order lifecycle</i>, not
 * matching, so O(1) cancel by order reference matters far more than a clever
 * matching loop. Have this number ready -- it is the most useful thing you can
 * say when someone asks why the book is built the way it is.
 *
 * <p>Counting is into a flat {@code long[128]} indexed by the message-type
 * byte, so the handler allocates nothing. Formatting at the end allocates
 * freely; it runs once.
 */
public final class MessageStats implements ItchHandler {

    private final long[] countsByType = new long[128];
    private final SymbolTable symbols;

    private long firstTimestamp = -1;
    private long lastTimestamp;
    private long totalMessages;
    private long totalShares;

    public MessageStats(SymbolTable symbols) {
        this.symbols = symbols;
    }

    private void record(byte type, long timestamp) {
        countsByType[type & 0x7F]++;
        totalMessages++;
        if (firstTimestamp < 0 && timestamp > 0) firstTimestamp = timestamp;
        if (timestamp > lastTimestamp) lastTimestamp = timestamp;
    }

    @Override
    public void onAddOrder(long timestamp, int stockLocate, long orderRef,
                           boolean buy, int shares, int symbolId, int price) {
        record(Itch.ADD_ORDER, timestamp);
        totalShares += shares;
    }

    @Override
    public void onOrderExecuted(long timestamp, int stockLocate, long orderRef,
                                int shares, long matchNumber) {
        record(Itch.ORDER_EXECUTED, timestamp);
    }

    @Override
    public void onOrderExecutedWithPrice(long timestamp, int stockLocate, long orderRef,
                                         int shares, long matchNumber,
                                         boolean printable, int price) {
        record(Itch.ORDER_EXECUTED_WITH_PRICE, timestamp);
    }

    @Override
    public void onOrderCancel(long timestamp, int stockLocate, long orderRef, int shares) {
        record(Itch.ORDER_CANCEL, timestamp);
    }

    @Override
    public void onOrderDelete(long timestamp, int stockLocate, long orderRef) {
        record(Itch.ORDER_DELETE, timestamp);
    }

    @Override
    public void onOrderReplace(long timestamp, int stockLocate, long originalRef,
                               long newRef, int shares, int price) {
        record(Itch.ORDER_REPLACE, timestamp);
    }

    @Override
    public void onTrade(long timestamp, int stockLocate, long orderRef, boolean buy,
                        int shares, int symbolId, int price, long matchNumber) {
        record(Itch.TRADE, timestamp);
    }

    @Override
    public void onCrossTrade(long timestamp, int stockLocate, long shares, int symbolId,
                             int price, long matchNumber, char crossType) {
        record(Itch.CROSS_TRADE, timestamp);
    }

    @Override
    public void onBrokenTrade(long timestamp, int stockLocate, long matchNumber) {
        record(Itch.BROKEN_TRADE, timestamp);
    }

    @Override
    public void onStockDirectory(long timestamp, int stockLocate, int symbolId, int roundLotSize) {
        record(Itch.STOCK_DIRECTORY, timestamp);
    }

    @Override
    public void onTradingAction(long timestamp, int stockLocate, char tradingState) {
        record(Itch.STOCK_TRADING_ACTION, timestamp);
    }

    @Override
    public void onSystemEvent(long timestamp, char eventCode) {
        record(Itch.SYSTEM_EVENT, timestamp);
    }

    @Override
    public void onOther(long timestamp, int stockLocate, byte messageType) {
        record(messageType, timestamp);
    }

    public long totalMessages() {
        return totalMessages;
    }

    /** Share of traffic that is order lifecycle rather than execution. */
    public double lifecycleShare() {
        long lifecycle = countsByType[Itch.ADD_ORDER & 0x7F]
                + countsByType[Itch.ADD_ORDER_MPID & 0x7F]
                + countsByType[Itch.ORDER_DELETE & 0x7F]
                + countsByType[Itch.ORDER_CANCEL & 0x7F]
                + countsByType[Itch.ORDER_REPLACE & 0x7F];
        return totalMessages == 0 ? 0.0 : (double) lifecycle / totalMessages;
    }

    public void report(PrintStream out, long elapsedNanos) {
        out.println();
        out.println("message mix");
        out.println("-".repeat(58));

        List<Integer> present = new ArrayList<>();
        for (int i = 0; i < countsByType.length; i++) {
            if (countsByType[i] > 0) present.add(i);
        }
        present.sort(Comparator.comparingLong((Integer i) -> countsByType[i]).reversed());

        for (int type : present) {
            double share = 100.0 * countsByType[type] / totalMessages;
            out.printf("  %-28s %14s  %5.2f%%%n",
                    Itch.typeName((byte) type), group(countsByType[type]), share);
        }

        out.println("-".repeat(58));
        out.printf("  %-28s %14s%n", "total messages", group(totalMessages));
        out.printf("  %-28s %14s%n", "symbols seen", group(symbols.size()));
        out.printf("  %-28s %14s%n", "shares added", group(totalShares));
        if (firstTimestamp >= 0) {
            out.printf("  %-28s %14s%n", "session start", clock(firstTimestamp));
            out.printf("  %-28s %14s%n", "session end", clock(lastTimestamp));
        }

        out.println();
        out.printf("  order lifecycle traffic:    %.2f%% of all messages%n", 100.0 * lifecycleShare());
        out.println("  ^ this is why cancel-by-order-ref is the hot path, not matching.");

        if (elapsedNanos > 0) {
            double seconds = elapsedNanos / 1e9;
            out.println();
            out.printf("  parsed in %.2fs  (%s msg/s)%n",
                    seconds, group(Math.round(totalMessages / seconds)));
        }
        out.println();
    }

    private static String group(long value) {
        return String.format("%,d", value);
    }

    /** Formats a nanoseconds-since-midnight timestamp as wall clock. */
    private static String clock(long nanosSinceMidnight) {
        long totalSeconds = nanosSinceMidnight / 1_000_000_000L;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
