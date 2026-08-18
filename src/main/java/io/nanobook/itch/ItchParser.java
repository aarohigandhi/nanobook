package io.nanobook.itch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Zero-allocation parser for the Nasdaq TotalView-ITCH 5.0 file format.
 *
 * <p>The file is memory-mapped rather than streamed, so message decoding reads
 * straight out of the page cache with no intermediate copy and no read syscall
 * per message.
 *
 * <p><b>The 2 GiB problem.</b> A {@link MappedByteBuffer} is addressed by
 * {@code int}, so a single mapping cannot exceed 2 GiB -- and an ITCH session
 * file is several times that. This parser therefore maps a sliding window and
 * remaps when the remaining bytes cannot hold another complete message. The
 * subtlety is that a message must never straddle a window boundary: after
 * draining a window, the next one starts at the first byte of the message that
 * did not fit, not at the end of the previous window.
 *
 * <p>Nothing in {@link #parse} allocates once the window is mapped. Decoded
 * fields are passed to the handler as primitives; symbols are interned to ints.
 */
public final class ItchParser {

    /**
     * Sliding window size. Comfortably under the 2 GiB int ceiling, and large
     * enough that remapping happens a handful of times per file rather than
     * constantly.
     */
    private static final long WINDOW_BYTES = 1L << 30; // 1 GiB

    private final SymbolTable symbols;

    private long messagesParsed;
    private long bytesConsumed;

    public ItchParser() {
        this(new SymbolTable());
    }

    public ItchParser(SymbolTable symbols) {
        this.symbols = symbols;
    }

    public SymbolTable symbols() {
        return symbols;
    }

    public long messagesParsed() {
        return messagesParsed;
    }

    public long bytesConsumed() {
        return bytesConsumed;
    }

    /**
     * Parses an entire ITCH file, invoking {@code handler} per message.
     *
     * @return the number of messages parsed
     */
    public long parse(Path path, ItchHandler handler) throws IOException {
        if (path.getFileName().toString().endsWith(".gz")) {
            throw new IOException("""
                    Refusing to parse a .gz file: this parser memory-maps its input, \
                    which requires the decompressed file. Run `gunzip` on it first. \
                    See data/README.md.""");
        }
        if (!Files.exists(path)) {
            throw new IOException("no such file: " + path.toAbsolutePath());
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            long windowStart = 0;

            while (windowStart < fileSize) {
                long windowLength = Math.min(WINDOW_BYTES, fileSize - windowStart);
                boolean lastWindow = windowStart + windowLength >= fileSize;

                MappedByteBuffer window =
                        channel.map(FileChannel.MapMode.READ_ONLY, windowStart, windowLength);
                window.order(ByteOrder.BIG_ENDIAN);

                int limit = (int) windowLength;
                int position = drain(window, limit, handler);

                bytesConsumed = windowStart + position;

                if (lastWindow) {
                    // Anything left is a trailing partial message; the session is over.
                    break;
                }
                if (position == 0) {
                    throw new IOException(
                            "no complete message in a " + windowLength + " byte window at offset "
                                    + windowStart + " -- stream is misaligned or corrupt");
                }
                windowStart += position;
            }
        }
        return messagesParsed;
    }

    /**
     * Consumes as many complete messages as the window holds.
     *
     * @return the offset just past the last fully consumed message
     */
    private int drain(ByteBuffer window, int limit, ItchHandler handler) throws IOException {
        int position = 0;
        while (true) {
            if (position + Itch.LENGTH_PREFIX_BYTES > limit) {
                return position;
            }
            int declaredLength = window.getShort(position) & 0xFFFF;
            if (declaredLength == 0) {
                // Some distributions pad with zero-length records between blocks.
                position += Itch.LENGTH_PREFIX_BYTES;
                continue;
            }
            int bodyStart = position + Itch.LENGTH_PREFIX_BYTES;
            if (bodyStart + declaredLength > limit) {
                return position;
            }

            byte type = window.get(bodyStart + Itch.OFF_TYPE);
            int expectedLength = Itch.messageLength(type);
            if (expectedLength != 0 && expectedLength != declaredLength) {
                throw new IOException("message type " + (char) type + " at offset " + bodyStart
                        + " declared " + declaredLength + " bytes, spec says " + expectedLength);
            }

            dispatch(window, bodyStart, type, handler);
            messagesParsed++;
            position = bodyStart + declaredLength;
        }
    }

    private void dispatch(ByteBuffer buf, int base, byte type, ItchHandler handler) {
        int stockLocate = u16(buf, base + Itch.OFF_STOCK_LOCATE);
        long timestamp = u48(buf, base + Itch.OFF_TIMESTAMP);

        switch (type) {
            case Itch.ADD_ORDER, Itch.ADD_ORDER_MPID -> handler.onAddOrder(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.ADD_ORDER_REF),
                    buf.get(base + Itch.ADD_BUY_SELL) == 'B',
                    buf.getInt(base + Itch.ADD_SHARES),
                    symbols.intern(buf, base + Itch.ADD_STOCK),
                    buf.getInt(base + Itch.ADD_PRICE));

            case Itch.ORDER_EXECUTED -> handler.onOrderExecuted(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.EXEC_ORDER_REF),
                    buf.getInt(base + Itch.EXEC_SHARES),
                    buf.getLong(base + Itch.EXEC_MATCH_NUMBER));

            case Itch.ORDER_EXECUTED_WITH_PRICE -> handler.onOrderExecutedWithPrice(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.EXECP_ORDER_REF),
                    buf.getInt(base + Itch.EXECP_SHARES),
                    buf.getLong(base + Itch.EXECP_MATCH_NUMBER),
                    buf.get(base + Itch.EXECP_PRINTABLE) == 'Y',
                    buf.getInt(base + Itch.EXECP_PRICE));

            case Itch.ORDER_CANCEL -> handler.onOrderCancel(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.CANCEL_ORDER_REF),
                    buf.getInt(base + Itch.CANCEL_SHARES));

            case Itch.ORDER_DELETE -> handler.onOrderDelete(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.DELETE_ORDER_REF));

            case Itch.ORDER_REPLACE -> handler.onOrderReplace(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.REPLACE_ORIG_REF),
                    buf.getLong(base + Itch.REPLACE_NEW_REF),
                    buf.getInt(base + Itch.REPLACE_SHARES),
                    buf.getInt(base + Itch.REPLACE_PRICE));

            case Itch.TRADE -> handler.onTrade(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.TRADE_ORDER_REF),
                    buf.get(base + Itch.TRADE_BUY_SELL) == 'B',
                    buf.getInt(base + Itch.TRADE_SHARES),
                    symbols.intern(buf, base + Itch.TRADE_STOCK),
                    buf.getInt(base + Itch.TRADE_PRICE),
                    buf.getLong(base + Itch.TRADE_MATCH_NUMBER));

            case Itch.CROSS_TRADE -> handler.onCrossTrade(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.CROSS_SHARES),
                    symbols.intern(buf, base + Itch.CROSS_STOCK),
                    buf.getInt(base + Itch.CROSS_PRICE),
                    buf.getLong(base + Itch.CROSS_MATCH_NUMBER),
                    (char) buf.get(base + Itch.CROSS_TYPE));

            case Itch.BROKEN_TRADE -> handler.onBrokenTrade(
                    timestamp,
                    stockLocate,
                    buf.getLong(base + Itch.BROKEN_MATCH_NUMBER));

            case Itch.STOCK_DIRECTORY -> handler.onStockDirectory(
                    timestamp,
                    stockLocate,
                    symbols.intern(buf, base + Itch.DIR_STOCK),
                    buf.getInt(base + Itch.DIR_ROUND_LOT_SIZE));

            case Itch.STOCK_TRADING_ACTION -> handler.onTradingAction(
                    timestamp,
                    stockLocate,
                    (char) buf.get(base + Itch.ACTION_TRADING_STATE));

            case Itch.SYSTEM_EVENT -> handler.onSystemEvent(
                    timestamp,
                    (char) buf.get(base + Itch.SYSTEM_EVENT_CODE));

            default -> handler.onOther(timestamp, stockLocate, type);
        }
    }

    // ---------------------------------------------------------------
    // Unsigned reads. Java has no unsigned primitives, so widen and mask.
    // ---------------------------------------------------------------

    /** Unsigned 16-bit. */
    private static int u16(ByteBuffer buf, int offset) {
        return buf.getShort(offset) & 0xFFFF;
    }

    /**
     * Unsigned 48-bit ITCH timestamp: nanoseconds since midnight Eastern.
     * There is no 6-byte read, so this is a 4-byte read shifted over a 2-byte
     * read. Getting the shift wrong yields timestamps that look plausible and
     * are wrong by a factor of 65536 -- check that the first message of the
     * session lands near 04:00 ET.
     */
    private static long u48(ByteBuffer buf, int offset) {
        long high = buf.getInt(offset) & 0xFFFF_FFFFL;
        long low = buf.getShort(offset + 4) & 0xFFFFL;
        return (high << 16) | low;
    }
}
