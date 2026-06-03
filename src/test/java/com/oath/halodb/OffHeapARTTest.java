/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class OffHeapARTTest {

    private static final Comparator<byte[]> CMP = (a, b) -> {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) { int x = a[i] & 0xFF, y = b[i] & 0xFF; if (x != y) return x - y; }
        return a.length - b.length;
    };

    private static byte[] randomKey(Random r, int len) {
        byte[] k = new byte[len];
        for (int i = 0; i < len; i++) k[i] = (byte) (i < len / 2 ? r.nextInt(6) : r.nextInt(256));
        return k;
    }

    private static byte[] randomValue(Random r, int len) { byte[] v = new byte[len]; r.nextBytes(v); return v; }

    private static List<byte[]> toList(Iterator<byte[]> it) {
        List<byte[]> out = new ArrayList<>();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    private static void assertSameOrder(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(actual.size(), expected.size());
        for (int i = 0; i < expected.size(); i++) assertEquals(actual.get(i), expected.get(i));
    }

    @Test
    public void testGrowthThroughNodeTypes() {
        OffHeapART art = new OffHeapART(4, 8);
        byte[] dst = new byte[8];
        try {
            for (int b = 0; b < 256; b++) art.put(new byte[]{0, 0, 0, (byte) b}, new byte[]{(byte) b, 1, 2, 3, 4, 5, 6, 7});
            assertEquals(art.size(), 256L);
            for (int b = 0; b < 256; b++) {
                assertTrue(art.get(new byte[]{0, 0, 0, (byte) b}, dst));
                assertEquals(dst[0], (byte) b);
            }
            List<byte[]> scanned = toList(art.scan(null, null));
            assertEquals(scanned.size(), 256);
            for (int i = 0; i < 256; i++) assertEquals(scanned.get(i)[3] & 0xFF, i);
        } finally {
            art.close();
        }
    }

    @Test
    public void testRandomAgainstOracle() {
        int keyLen = 4, vsize = 8;
        Random r = new Random(42);
        TreeMap<byte[], byte[]> oracle = new TreeMap<>(CMP);
        OffHeapART art = new OffHeapART(keyLen, vsize);
        byte[] dst = new byte[vsize];
        try {
            for (int i = 0; i < 50_000; i++) {
                byte[] k = randomKey(r, keyLen);
                byte[] v = randomValue(r, vsize);
                assertEquals(art.put(k, v), oracle.put(k, v) == null);
            }
            assertEquals(art.size(), (long) oracle.size());
            for (Map.Entry<byte[], byte[]> e : oracle.entrySet()) {
                assertTrue(art.get(e.getKey(), dst));
                assertEquals(dst, e.getValue());
            }
            for (int i = 0; i < 10_000; i++) {
                byte[] k = randomKey(r, keyLen);
                assertEquals(art.contains(k), oracle.containsKey(k));
            }
            assertSameOrder(new ArrayList<>(oracle.keySet()), toList(art.scan(null, null)));
        } finally {
            art.close();
        }
    }

    @Test
    public void testPrefixAndRangeScan() {
        int keyLen = 4, vsize = 4;
        Random r = new Random(99);
        TreeMap<byte[], byte[]> oracle = new TreeMap<>(CMP);
        OffHeapART art = new OffHeapART(keyLen, vsize);
        try {
            for (int i = 0; i < 30_000; i++) {
                byte[] k = randomKey(r, keyLen);
                byte[] v = randomValue(r, vsize);
                art.put(k, v);
                oracle.put(k, v);
            }
            byte[][] prefixes = {{}, {0}, {3}, {1, 2}, {0, 0}, {5, 5, 5}, {9}};
            for (byte[] prefix : prefixes) {
                List<byte[]> expected = new ArrayList<>();
                for (byte[] k : oracle.keySet()) if (startsWith(k, prefix)) expected.add(k);
                assertSameOrder(expected, toList(art.prefixScan(prefix)));
            }
            byte[] start = {1, 0, 0, 0}, end = {4, 0, 0, 0};
            List<byte[]> expected = new ArrayList<>(oracle.subMap(start, true, end, false).keySet());
            assertSameOrder(expected, toList(art.scan(start, end)));
        } finally {
            art.close();
        }
    }

    @Test
    public void testEightByteKeysOverwrite() {
        OffHeapART art = new OffHeapART(8, 20);
        Random r = new Random(5);
        TreeMap<byte[], byte[]> oracle = new TreeMap<>(CMP);
        byte[] dst = new byte[20];
        try {
            for (int i = 0; i < 40_000; i++) {
                byte[] k = new byte[8];
                r.nextBytes(k);
                byte[] v = randomValue(r, 20);
                art.put(k, v);
                oracle.put(k, v);
            }
            assertEquals(art.size(), (long) oracle.size());
            for (Map.Entry<byte[], byte[]> e : oracle.entrySet()) {
                assertTrue(art.get(e.getKey(), dst));
                assertEquals(dst, e.getValue());
            }
            byte[] k0 = oracle.firstKey();
            byte[] nv = new byte[20];
            nv[0] = 77;
            assertFalse(art.put(k0, nv));
            assertTrue(art.get(k0, dst));
            assertEquals(dst[0], (byte) 77);
        } finally {
            art.close();
        }
    }

    @Test
    public void testRemoveAgainstOracle() {
        int keyLen = 6, vsize = 8;
        Random r = new Random(2024);
        TreeMap<byte[], byte[]> oracle = new TreeMap<>(CMP);
        OffHeapART art = new OffHeapART(keyLen, vsize);
        byte[] dst = new byte[vsize];
        try {
            for (int i = 0; i < 40_000; i++) {
                byte[] k = randomKey(r, keyLen);
                byte[] v = randomValue(r, vsize);
                art.put(k, v);
                oracle.put(k, v);
            }
            // remove ~half
            List<byte[]> snapshot = new ArrayList<>(oracle.keySet());
            for (int i = 0; i < snapshot.size(); i += 2) {
                byte[] k = snapshot.get(i);
                assertTrue(art.remove(k));
                oracle.remove(k);
            }
            assertFalse(art.remove(snapshot.get(0))); // already gone
            assertEquals(art.size(), (long) oracle.size());
            for (Map.Entry<byte[], byte[]> e : oracle.entrySet()) {
                assertTrue(art.get(e.getKey(), dst), "missing after removes");
                assertEquals(dst, e.getValue());
            }
            // removed keys absent; ordering still correct
            for (int i = 0; i < snapshot.size(); i += 2) assertFalse(art.contains(snapshot.get(i)));
            assertSameOrder(new ArrayList<>(oracle.keySet()), toList(art.scan(null, null)));
        } finally {
            art.close();
        }
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (key[i] != prefix[i]) return false;
        return true;
    }
}
