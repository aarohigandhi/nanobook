package io.nanobook.itch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips synthetic ITCH messages through the parser.
 *
 * <p>These tests exist to pin the field offsets. An offset that is wrong by a
 * couple of bytes still parses without error and produces numbers that look
 * entirely plausible -- prices in the right ballpark, share counts that could
 * be real. It would silently poison every result downstream. Encoding messages
 * by hand from the spec and asserting on the decoded values is the only way to
 * know the layout table is right.
 */
class ItchParserTest {

    private static final long NINE_THIRTY = 34_200_000_000_000L; // 09:30:00 in nanos

    @TempDir
    Path tempDir;

    /** Records every callback so a test can assert on the decoded fields. */
    private static final class Recorder implements ItchHandler {
        final List<String> events = new ArrayList<>();
        int otherCount;

        @Override
        public void onSystemEvent(long timestamp, char eventCode) {
            events.add("system " + timestamp + " " + eventCode);
        }

        @Override
        public void onStockDirectory(long timestamp, int stockLocate, int symbolId, int roundLotSize) {
            events.add("directory " + stockLocate + " " + symbolId + " " + roundLotSize);
        }

        @Override
        public void onAddOrder(long timestamp, int stockLocate, long orderRef,
                               boolean buy, int shares, int symbolId, int price) {
            events.add("add " + timestamp + " " + stockLocate + " " + orderRef + " "
                    + (buy ? "B" : "S") + " " + shares + " " + symbolId + " " + price);
        }

        @Override
        public void onOrderExecuted(long timestamp, int stockLocate, long orderRef,
                                    int shares, long matchNumber) {
            events.add("exec " + orderRef + " " + shares + " " + matchNumber);
        }

        @Override
        public void onOrderCancel(long timestamp, int stockLocate, long orderRef, int shares) {
            events.add("cancel " + orderRef + " " + shares);
        }

        @Override
        public void onOrderDelete(long timestamp, int stockLocate, long orderRef) {
            events.add("delete " + orderRef);
        }

        @Override
        public void onOrderReplace(long timestamp, int stockLocate, long originalRef,
                                   long newRef, int shares, int price) {
            events.add("replace " + originalRef + " " + newRef + " " + shares + " " + price);
        }

        @Override
        public void onTrade(long timestamp, int stockLocate, long orderRef, boolean buy,
                            int shares, int symbolId, int price, long matchNumber) {
            events.add("trade " + shares + " " + symbolId + " " + price + " " + matchNumber);
        }

        @Override
        public void onOther(long timestamp, int stockLocate, byte messageType) {
            otherCount++;
        }
    }

    // ---------------------------------------------------------------
    // Message construction, straight from the spec
    // ---------------------------------------------------------------

    private static final class Writer {
        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 16).order(ByteOrder.BIG_ENDIAN);

        /** Starts a message: writes the length prefix and the 11-byte header. */
        private ByteBuffer start(byte type, int stockLocate, long timestamp) {
            int length = Itch.messageLength(type);
            buffer.putShort((short) length);
            int base = buffer.position();
            buffer.position(base + length); // reserve the body
            ByteBuffer body = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
            body.put(base + Itch.OFF_TYPE, type);
            body.putShort(base + Itch.OFF_STOCK_LOCATE, (short) stockLocate);
            body.putShort(base + Itch.OFF_TRACKING_NUMBER, (short) 0);
            body.putInt(base + Itch.OFF_TIMESTAMP, (int) (timestamp >>> 16));
            body.putShort(base + Itch.OFF_TIMESTAMP + 4, (short) (timestamp & 0xFFFF));
            return body.position(base);
        }

        Writer systemEvent(long timestamp, char code) {
            ByteBuffer body = start(Itch.SYSTEM_EVENT, 0, timestamp);
            body.put(body.position() + Itch.SYSTEM_EVENT_CODE, (byte) code);
            return this;
        }

        Writer stockDirectory(int locate, String ticker, int roundLot) {
            ByteBuffer body = start(Itch.STOCK_DIRECTORY, locate, NINE_THIRTY);
            int base = body.position();
            body.putLong(base + Itch.DIR_STOCK, SymbolTable.pack(ticker));
            body.put(base + Itch.DIR_MARKET_CATEGORY, (byte) 'Q');
            body.putInt(base + Itch.DIR_ROUND_LOT_SIZE, roundLot);
            return this;
        }

        Writer addOrder(long timestamp, int locate, long ref, boolean buy,
                        int shares, String ticker, int price) {
            ByteBuffer body = start(Itch.ADD_ORDER, locate, timestamp);
            int base = body.position();
            body.putLong(base + Itch.ADD_ORDER_REF, ref);
            body.put(base + Itch.ADD_BUY_SELL, (byte) (buy ? 'B' : 'S'));
            body.putInt(base + Itch.ADD_SHARES, shares);
            body.putLong(base + Itch.ADD_STOCK, SymbolTable.pack(ticker));
            body.putInt(base + Itch.ADD_PRICE, price);
            return this;
        }

        Writer executed(long ref, int shares, long matchNumber) {
            ByteBuffer body = start(Itch.ORDER_EXECUTED, 1, NINE_THIRTY);
            int base = body.position();
            body.putLong(base + Itch.EXEC_ORDER_REF, ref);
            body.putInt(base + Itch.EXEC_SHARES, shares);
            body.putLong(base + Itch.EXEC_MATCH_NUMBER, matchNumber);
            return this;
        }

        Writer cancel(long ref, int shares) {
            ByteBuffer body = start(Itch.ORDER_CANCEL, 1, NINE_THIRTY);
            int base = body.position();
            body.putLong(base + Itch.CANCEL_ORDER_REF, ref);
            body.putInt(base + Itch.CANCEL_SHARES, shares);
            return this;
        }

        Writer delete(long ref) {
            ByteBuffer body = start(Itch.ORDER_DELETE, 1, NINE_THIRTY);
            body.putLong(body.position() + Itch.DELETE_ORDER_REF, ref);
            return this;
        }

        Writer replace(long originalRef, long newRef, int shares, int price) {
            ByteBuffer body = start(Itch.ORDER_REPLACE, 1, NINE_THIRTY);
            int base = body.position();
            body.putLong(base + Itch.REPLACE_ORIG_REF, originalRef);
            body.putLong(base + Itch.REPLACE_NEW_REF, newRef);
            body.putInt(base + Itch.REPLACE_SHARES, shares);
            body.putInt(base + Itch.REPLACE_PRICE, price);
            return this;
        }

        Writer trade(long ref, int shares, String ticker, int price, long matchNumber) {
            ByteBuffer body = start(Itch.TRADE, 1, NINE_THIRTY);
            int base = body.position();
            body.putLong(base + Itch.TRADE_ORDER_REF, ref);
            body.put(base + Itch.TRADE_BUY_SELL, (byte) 'B');
            body.putInt(base + Itch.TRADE_SHARES, shares);
            body.putLong(base + Itch.TRADE_STOCK, SymbolTable.pack(ticker));
            body.putInt(base + Itch.TRADE_PRICE, price);
            body.putLong(base + Itch.TRADE_MATCH_NUMBER, matchNumber);
            return this;
        }

        /** A type the handler interface does not model, to exercise onOther. */
        Writer regSho(int locate) {
            start(Itch.REG_SHO_RESTRICTION, locate, NINE_THIRTY);
            return this;
        }

        byte[] bytes() {
            byte[] out = new byte[buffer.position()];
            buffer.duplicate().position(0).get(out);
            return out;
        }
    }

    private Path write(Writer writer) throws IOException {
        Path file = tempDir.resolve("synthetic.itch");
        Files.write(file, writer.bytes());
        return file;
    }

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    void decodesAddOrderFields() throws IOException {
        Path file = write(new Writer()
                .addOrder(NINE_THIRTY, 17, 4242L, true, 300, "AAPL", 1_234_500));

        Recorder recorder = new Recorder();
        ItchParser parser = new ItchParser();
        assertEquals(1, parser.parse(file, recorder));

        int symbolId = parser.symbols().lookup("AAPL");
        assertEquals(
                "add " + NINE_THIRTY + " 17 4242 B 300 " + symbolId + " 1234500",
                recorder.events.get(0));
    }

    @Test
    void decodesSellSide() throws IOException {
        Path file = write(new Writer()
                .addOrder(NINE_THIRTY, 1, 7L, false, 100, "MSFT", 4_000_000));
        Recorder recorder = new Recorder();
        new ItchParser().parse(file, recorder);
        assertTrue(recorder.events.get(0).contains(" S "), recorder.events.get(0));
    }

    @Test
    void decodesFullOrderLifecycle() throws IOException {
        Path file = write(new Writer()
                .systemEvent(NINE_THIRTY, 'Q')
                .stockDirectory(17, "AAPL", 100)
                .addOrder(NINE_THIRTY, 17, 1000L, true, 500, "AAPL", 1_500_000)
                .executed(1000L, 100, 55L)
                .cancel(1000L, 50)
                .replace(1000L, 1001L, 350, 1_500_100)
                .delete(1001L)
                .trade(0L, 250, "AAPL", 1_500_050, 56L));

        Recorder recorder = new Recorder();
        ItchParser parser = new ItchParser();
        assertEquals(8, parser.parse(file, recorder));

        int symbolId = parser.symbols().lookup("AAPL");
        assertEquals("system " + NINE_THIRTY + " Q", recorder.events.get(0));
        assertEquals("directory 17 " + symbolId + " 100", recorder.events.get(1));
        assertEquals("exec 1000 100 55", recorder.events.get(3));
        assertEquals("cancel 1000 50", recorder.events.get(4));
        assertEquals("replace 1000 1001 350 1500100", recorder.events.get(5));
        assertEquals("delete 1001", recorder.events.get(6));
        assertEquals("trade 250 " + symbolId + " 1500050 56", recorder.events.get(7));
    }

    /**
     * The 48-bit timestamp is a 4-byte read shifted over a 2-byte read. Shift
     * by the wrong amount and every timestamp is off by a factor of 65536,
     * which still looks like a number.
     */
    @Test
    void decodesFortyEightBitTimestamp() throws IOException {
        long lateInSession = 57_599_999_999_999L; // 15:59:59.999999999
        Path file = write(new Writer().systemEvent(lateInSession, 'M'));
        Recorder recorder = new Recorder();
        new ItchParser().parse(file, recorder);
        assertEquals("system " + lateInSession + " M", recorder.events.get(0));
    }

    @Test
    void unmodelledTypesReachOnOther() throws IOException {
        Path file = write(new Writer().regSho(3).regSho(4));
        Recorder recorder = new Recorder();
        assertEquals(2, new ItchParser().parse(file, recorder));
        assertEquals(2, recorder.otherCount);
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    void symbolsInternToStableDenseIds() throws IOException {
        Path file = write(new Writer()
                .addOrder(NINE_THIRTY, 1, 1L, true, 100, "AAPL", 1_000_000)
                .addOrder(NINE_THIRTY, 2, 2L, true, 100, "MSFT", 2_000_000)
                .addOrder(NINE_THIRTY, 1, 3L, true, 100, "AAPL", 1_000_100));

        ItchParser parser = new ItchParser();
        parser.parse(file, new Recorder());

        SymbolTable symbols = parser.symbols();
        assertEquals(2, symbols.size(), "AAPL must not be interned twice");
        assertEquals(0, symbols.lookup("AAPL"));
        assertEquals(1, symbols.lookup("MSFT"));
        assertEquals("AAPL", symbols.name(0));
        assertEquals("MSFT", symbols.name(1));
    }

    @Test
    void symbolPackRoundTrips() {
        assertEquals("AAPL", SymbolTable.unpack(SymbolTable.pack("AAPL")));
        assertEquals("A", SymbolTable.unpack(SymbolTable.pack("A")));
        assertEquals("BRK.A", SymbolTable.unpack(SymbolTable.pack("BRK.A")));
        assertEquals("ABCDEFGH", SymbolTable.unpack(SymbolTable.pack("ABCDEFGH")));
    }

    @Test
    void rejectsLengthThatContradictsTheSpec() throws IOException {
        // Declare 30 bytes for an AddOrder, which the spec fixes at 36.
        ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) 30);
        buffer.put(Itch.ADD_ORDER);
        while (buffer.position() < 32) buffer.put((byte) 0);
        Path file = tempDir.resolve("bad.itch");
        Files.write(file, java.util.Arrays.copyOf(buffer.array(), buffer.position()));

        IOException error = assertThrows(IOException.class,
                () -> new ItchParser().parse(file, new Recorder()));
        assertTrue(error.getMessage().contains("spec says 36"), error.getMessage());
    }

    @Test
    void refusesCompressedInput() {
        Path file = tempDir.resolve("session.itch.gz");
        IOException error = assertThrows(IOException.class,
                () -> new ItchParser().parse(file, new Recorder()));
        assertTrue(error.getMessage().contains("gunzip"), error.getMessage());
    }

    @Test
    void emptyFileParsesToNothing() throws IOException {
        Path file = tempDir.resolve("empty.itch");
        Files.write(file, new byte[0]);
        assertEquals(0, new ItchParser().parse(file, new Recorder()));
    }
}
