package io.nanobook.itch;

import io.nanobook.collections.LongIntHashMap;

import java.nio.ByteBuffer;

/**
 * Interns ITCH ticker symbols to dense {@code int} ids.
 *
 * <p>The trick that makes this free: an ITCH stock field is exactly 8 bytes of
 * right-padded ASCII, and a {@code long} is exactly 8 bytes. So the raw symbol
 * IS a long -- read it with one {@code getLong} and use it directly as a hash
 * key. No {@code String}, no {@code byte[]}, no allocation, and comparison is a
 * single 64-bit compare instead of a character loop.
 *
 * <p>Ids are assigned densely from 0 in first-seen order, so downstream code
 * can use them to index flat arrays.
 *
 * <p>{@link #name(int)} allocates and exists only for reporting.
 */
public final class SymbolTable {

    private static final int MAX_SYMBOLS = 1 << 14; // ~16k, comfortably above a US equities session

    private final LongIntHashMap idsByRaw = new LongIntHashMap(MAX_SYMBOLS);
    private long[] rawById = new long[1024];
    private int count;

    /**
     * Returns the id for the 8-byte symbol at {@code offset}, assigning a new
     * one if this is the first sighting. Does not allocate in the steady state.
     */
    public int intern(ByteBuffer buffer, int offset) {
        return intern(buffer.getLong(offset));
    }

    /** Returns the id for a raw packed symbol, assigning one if unseen. */
    public int intern(long raw) {
        int existing = idsByRaw.get(raw);
        if (existing != LongIntHashMap.NO_VALUE) {
            return existing;
        }
        int id = count++;
        if (id == rawById.length) {
            long[] grown = new long[rawById.length << 1];
            System.arraycopy(rawById, 0, grown, 0, rawById.length);
            rawById = grown;
        }
        rawById[id] = raw;
        idsByRaw.put(raw, id);
        return id;
    }

    /** Id for a symbol already seen, or {@code -1}. Does not assign. */
    public int lookup(long raw) {
        int id = idsByRaw.get(raw);
        return id == LongIntHashMap.NO_VALUE ? -1 : id;
    }

    /** Id for a ticker written as text, e.g. {@code "AAPL"}. Reporting only. */
    public int lookup(String ticker) {
        return lookup(pack(ticker));
    }

    /** Number of distinct symbols interned so far. */
    public int size() {
        return count;
    }

    /** Ticker text for an id, trailing padding stripped. Allocates. */
    public String name(int id) {
        if (id < 0 || id >= count) throw new IndexOutOfBoundsException("symbol id " + id);
        return unpack(rawById[id]);
    }

    /** Packs a ticker into the same 8-byte big-endian layout ITCH uses. */
    public static long pack(String ticker) {
        if (ticker.length() > Itch.STOCK_FIELD_BYTES) {
            throw new IllegalArgumentException("ticker longer than 8 bytes: " + ticker);
        }
        long raw = 0L;
        for (int i = 0; i < Itch.STOCK_FIELD_BYTES; i++) {
            char c = i < ticker.length() ? ticker.charAt(i) : ' ';
            raw = (raw << 8) | (c & 0xFF);
        }
        return raw;
    }

    /** Inverse of {@link #pack}, with trailing spaces removed. Allocates. */
    public static String unpack(long raw) {
        char[] chars = new char[Itch.STOCK_FIELD_BYTES];
        for (int i = Itch.STOCK_FIELD_BYTES - 1; i >= 0; i--) {
            chars[i] = (char) (raw & 0xFF);
            raw >>>= 8;
        }
        int end = chars.length;
        while (end > 0 && chars[end - 1] == ' ') end--;
        return new String(chars, 0, end);
    }
}
