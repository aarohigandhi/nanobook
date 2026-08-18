package io.nanobook.collections;

import java.util.Arrays;

/**
 * Open-addressed {@code long -> int} hash map with linear probing.
 *
 * <p>This exists to replace {@code HashMap<Long, Order>} on the hot path. That
 * map boxes every order reference into a {@code Long} object and stores every
 * entry in a {@code Node} object, so a single order lookup costs two heap
 * allocations and two pointer dereferences into unrelated cache lines. At the
 * message rates in a real ITCH session that is the dominant source of garbage.
 * Here, keys and values live in two flat primitive arrays: a lookup is a hash,
 * an array load, and a compare.
 *
 * <p>Deletion uses <b>backward-shift</b> rather than tombstones. Tombstones are
 * simpler but degrade the table permanently under a churn-heavy workload, and
 * ITCH is exactly that -- the overwhelming majority of messages are adds and
 * cancels, so entries are inserted and removed constantly. Backward-shift
 * repairs the probe chain on removal, so the table stays as clean as if the
 * removed key had never been inserted.
 *
 * <p>Not thread-safe. Intentionally: the engine is single-threaded on the hot
 * path, and synchronization would cost more than it buys.
 */
public final class LongIntHashMap {

    /** Returned by {@link #get} when a key is absent. */
    public static final int NO_VALUE = Integer.MIN_VALUE;

    /**
     * Key slot marker for "empty". Zero is a legal key in principle, so it is
     * handled separately in dedicated fields rather than occupying a slot.
     */
    private static final long EMPTY_KEY = 0L;

    private static final int DEFAULT_CAPACITY = 1 << 16;
    private static final float DEFAULT_LOAD_FACTOR = 0.65f;

    private long[] keys;
    private int[] values;
    private int mask;
    private int size;
    private int resizeThreshold;
    private final float loadFactor;

    // Zero is stored out-of-band so that EMPTY_KEY can be a plain 0 in the array.
    private boolean hasZeroKey;
    private int zeroValue = NO_VALUE;

    public LongIntHashMap() {
        this(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR);
    }

    public LongIntHashMap(int expectedEntries) {
        this(tableSizeFor((int) (expectedEntries / DEFAULT_LOAD_FACTOR) + 1), DEFAULT_LOAD_FACTOR);
    }

    public LongIntHashMap(int capacity, float loadFactor) {
        if (loadFactor <= 0f || loadFactor >= 1f) {
            throw new IllegalArgumentException("loadFactor must be in (0,1): " + loadFactor);
        }
        int cap = tableSizeFor(Math.max(capacity, 4));
        this.loadFactor = loadFactor;
        this.keys = new long[cap];
        this.values = new int[cap];
        this.mask = cap - 1;
        this.resizeThreshold = (int) (cap * loadFactor);
    }

    /**
     * Fibonacci hashing. The multiply-and-xor-shift mixes high-entropy bits of
     * the key down into the low bits that the mask selects. Straight masking is
     * not safe here: ITCH order references are sequential, and sequential keys
     * with a poor mixer produce long contiguous probe runs.
     */
    private static int hash(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return (int) h & mask;
    }

    private static int tableSizeFor(int n) {
        int cap = Integer.highestOneBit(Math.max(n - 1, 1)) << 1;
        return Math.max(cap, 4);
    }

    /**
     * Associates {@code value} with {@code key}.
     *
     * @return the previous value, or {@link #NO_VALUE} if the key was absent
     */
    public int put(long key, int value) {
        if (key == EMPTY_KEY) {
            int previous = hasZeroKey ? zeroValue : NO_VALUE;
            hasZeroKey = true;
            zeroValue = value;
            if (previous == NO_VALUE) size++;
            return previous;
        }

        int index = hash(key, mask);
        while (true) {
            long existing = keys[index];
            if (existing == EMPTY_KEY) {
                keys[index] = key;
                values[index] = value;
                if (++size > resizeThreshold) rehash();
                return NO_VALUE;
            }
            if (existing == key) {
                int previous = values[index];
                values[index] = value;
                return previous;
            }
            index = (index + 1) & mask;
        }
    }

    /** @return the value for {@code key}, or {@link #NO_VALUE} if absent */
    public int get(long key) {
        if (key == EMPTY_KEY) {
            return hasZeroKey ? zeroValue : NO_VALUE;
        }
        int index = hash(key, mask);
        while (true) {
            long existing = keys[index];
            if (existing == key) return values[index];
            if (existing == EMPTY_KEY) return NO_VALUE;
            index = (index + 1) & mask;
        }
    }

    public boolean containsKey(long key) {
        return get(key) != NO_VALUE;
    }

    /**
     * Removes {@code key}, repairing the probe chain by backward-shifting the
     * cluster that follows it. Without this repair, a removed slot would break
     * the chain and later lookups for keys that probed past it would wrongly
     * report absent -- which is why tombstones exist, and why they accumulate.
     *
     * @return the removed value, or {@link #NO_VALUE} if the key was absent
     */
    public int remove(long key) {
        if (key == EMPTY_KEY) {
            if (!hasZeroKey) return NO_VALUE;
            int previous = zeroValue;
            hasZeroKey = false;
            zeroValue = NO_VALUE;
            size--;
            return previous;
        }

        int index = hash(key, mask);
        while (true) {
            long existing = keys[index];
            if (existing == EMPTY_KEY) return NO_VALUE;
            if (existing == key) break;
            index = (index + 1) & mask;
        }

        int removed = values[index];
        shiftBack(index);
        size--;
        return removed;
    }

    /**
     * Closes the gap at {@code gapIndex}. Walks forward through the contiguous
     * cluster; any entry whose ideal slot is at or before the gap is moved into
     * it, and the gap follows. Stops at the first empty slot.
     */
    private void shiftBack(int gapIndex) {
        int gap = gapIndex;
        int scan = gap;
        while (true) {
            scan = (scan + 1) & mask;
            long candidate = keys[scan];
            if (candidate == EMPTY_KEY) {
                keys[gap] = EMPTY_KEY;
                values[gap] = 0;
                return;
            }
            int ideal = hash(candidate, mask);
            // The candidate may move into the gap only if the gap lies on its
            // own probe path, i.e. the gap is no further from its ideal slot
            // than its current slot is. Otherwise it belongs where it is, and
            // moving it would make it unreachable.
            if (distance(ideal, gap) <= distance(ideal, scan)) {
                keys[gap] = candidate;
                values[gap] = values[scan];
                gap = scan;
            }
        }
    }

    /** Forward distance from {@code from} to {@code to}, with wraparound. */
    private int distance(int from, int to) {
        return (to - from) & mask;
    }

    private void rehash() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        int newCapacity = oldKeys.length << 1;
        if (newCapacity <= 0) throw new IllegalStateException("map too large to grow");

        keys = new long[newCapacity];
        values = new int[newCapacity];
        mask = newCapacity - 1;
        resizeThreshold = (int) (newCapacity * loadFactor);

        for (int i = 0; i < oldKeys.length; i++) {
            long key = oldKeys[i];
            if (key == EMPTY_KEY) continue;
            int index = hash(key, mask);
            while (keys[index] != EMPTY_KEY) {
                index = (index + 1) & mask;
            }
            keys[index] = key;
            values[index] = oldValues[i];
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Backing table length. Exposed for load-factor and probe-length reporting. */
    public int capacity() {
        return keys.length;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY_KEY);
        Arrays.fill(values, 0);
        size = 0;
        hasZeroKey = false;
        zeroValue = NO_VALUE;
    }

    /**
     * Mean probe distance across all present keys. Not used on the hot path --
     * this is for the benchmark writeup, where "my hash function is fine" is
     * worth more as a number than as a claim.
     */
    public double meanProbeDistance() {
        if (size == 0) return 0.0;
        long total = 0;
        int counted = 0;
        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            if (key == EMPTY_KEY) continue;
            total += distance(hash(key, mask), i);
            counted++;
        }
        return counted == 0 ? 0.0 : (double) total / counted;
    }
}
