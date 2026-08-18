package io.nanobook.collections;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongIntHashMapTest {

    @Test
    void putThenGet() {
        LongIntHashMap map = new LongIntHashMap(16, 0.5f);
        assertEquals(LongIntHashMap.NO_VALUE, map.put(42L, 7));
        assertEquals(7, map.get(42L));
        assertEquals(1, map.size());
    }

    @Test
    void putReturnsPreviousValue() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(42L, 7);
        assertEquals(7, map.put(42L, 9));
        assertEquals(9, map.get(42L));
        assertEquals(1, map.size(), "overwrite must not change size");
    }

    @Test
    void missingKeyReturnsSentinel() {
        LongIntHashMap map = new LongIntHashMap();
        assertEquals(LongIntHashMap.NO_VALUE, map.get(999L));
        assertFalse(map.containsKey(999L));
    }

    @Test
    void removeReturnsValueAndClearsKey() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(42L, 7);
        assertEquals(7, map.remove(42L));
        assertEquals(LongIntHashMap.NO_VALUE, map.get(42L));
        assertEquals(0, map.size());
        assertEquals(LongIntHashMap.NO_VALUE, map.remove(42L), "second remove is a no-op");
    }

    /** Zero is a legal key but doubles as the empty-slot marker, so it lives out-of-band. */
    @Test
    void zeroKeyIsStorable() {
        LongIntHashMap map = new LongIntHashMap();
        assertEquals(LongIntHashMap.NO_VALUE, map.get(0L));
        map.put(0L, 123);
        assertEquals(123, map.get(0L));
        assertEquals(1, map.size());
        assertTrue(map.containsKey(0L));
        assertEquals(123, map.remove(0L));
        assertEquals(LongIntHashMap.NO_VALUE, map.get(0L));
        assertEquals(0, map.size());
    }

    @Test
    void negativeKeysWork() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(-1L, 10);
        map.put(Long.MIN_VALUE, 20);
        assertEquals(10, map.get(-1L));
        assertEquals(20, map.get(Long.MIN_VALUE));
    }

    @Test
    void growsPastInitialCapacity() {
        LongIntHashMap map = new LongIntHashMap(4, 0.5f);
        for (int i = 1; i <= 10_000; i++) {
            map.put(i, i * 3);
        }
        assertEquals(10_000, map.size());
        assertTrue(map.capacity() > 10_000);
        for (int i = 1; i <= 10_000; i++) {
            assertEquals(i * 3, map.get(i), "key " + i + " lost across rehash");
        }
    }

    @Test
    void clearEmptiesEverything() {
        LongIntHashMap map = new LongIntHashMap();
        for (int i = 0; i < 100; i++) map.put(i, i);
        map.clear();
        assertEquals(0, map.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(LongIntHashMap.NO_VALUE, map.get(i));
        }
    }

    /**
     * The test that actually matters. Linear probing puts colliding keys in a
     * contiguous run, and removing from the middle of that run breaks the chain
     * unless the following entries are shifted back. Tombstones are the usual
     * fix; this map repairs the chain instead. Force dense collisions with a
     * tiny table and a heavy churn pattern -- if the shift is wrong, some key
     * that probed past the hole becomes unreachable.
     */
    @Test
    void survivesHeavyChurnWithoutLosingKeys() {
        LongIntHashMap map = new LongIntHashMap(64, 0.9f);
        Map<Long, Integer> model = new HashMap<>();
        Random random = new Random(20260818L);

        for (int round = 0; round < 50_000; round++) {
            long key = random.nextInt(200); // tiny key space forces collisions
            if (random.nextBoolean()) {
                int value = random.nextInt(1_000_000);
                Integer expected = model.put(key, value);
                int actual = map.put(key, value);
                assertEquals(expected == null ? LongIntHashMap.NO_VALUE : expected, actual);
            } else {
                Integer expected = model.remove(key);
                int actual = map.remove(key);
                assertEquals(expected == null ? LongIntHashMap.NO_VALUE : expected, actual);
            }
            assertEquals(model.size(), map.size(), "size diverged at round " + round);
        }

        for (Map.Entry<Long, Integer> entry : model.entrySet()) {
            assertEquals(entry.getValue().intValue(), map.get(entry.getKey()),
                    "key " + entry.getKey() + " unreachable after churn");
        }
        for (long key = 0; key < 200; key++) {
            if (!model.containsKey(key)) {
                assertEquals(LongIntHashMap.NO_VALUE, map.get(key),
                        "key " + key + " resurrected after removal");
            }
        }
    }

    /**
     * Same idea at ITCH scale, with sequential keys. Order references in a real
     * session are sequential, which is exactly the pattern a weak hash function
     * turns into one enormous probe run -- so this is the case worth checking.
     */
    @Test
    void matchesHashMapUnderSequentialOrderReferences() {
        LongIntHashMap map = new LongIntHashMap();
        Map<Long, Integer> model = new HashMap<>();
        Random random = new Random(7L);

        long nextOrderRef = 1;
        for (int i = 0; i < 200_000; i++) {
            if (model.isEmpty() || random.nextInt(100) < 55) {
                long ref = nextOrderRef++;
                model.put(ref, i);
                map.put(ref, i);
            } else {
                long ref = nextOrderRef - 1 - random.nextInt((int) Math.min(nextOrderRef, 5_000));
                Integer expected = model.remove(ref);
                int actual = map.remove(ref);
                assertEquals(expected == null ? LongIntHashMap.NO_VALUE : expected, actual);
            }
        }

        assertEquals(model.size(), map.size());
        for (Map.Entry<Long, Integer> entry : model.entrySet()) {
            assertEquals(entry.getValue().intValue(), map.get(entry.getKey()));
        }
        assertTrue(map.meanProbeDistance() < 3.0,
                "probe distance " + map.meanProbeDistance() + " is too high -- check the mixer");
    }
}
