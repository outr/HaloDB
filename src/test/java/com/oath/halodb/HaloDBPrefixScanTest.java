/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class HaloDBPrefixScanTest extends TestBase {

    private static final int KEY_SIZE = 8;

    private static byte[] key(Random r, int category) {
        byte[] k = new byte[KEY_SIZE];
        r.nextBytes(k);
        k[0] = (byte) category; // first byte = prefix/category
        return k;
    }

    private static String s(byte[] b) { return new String(b, StandardCharsets.ISO_8859_1); }

    private static HaloDBOptions orderedOptions() {
        HaloDBOptions o = new HaloDBOptions();
        o.setUseOrderedIndex(true);
        o.setFixedKeySize(KEY_SIZE);
        o.setCompactionDisabled(true); // keep the test deterministic
        return o;
    }

    private List<byte[]> scanKeys(HaloDB db, byte[] prefix) throws Exception {
        List<byte[]> out = new ArrayList<>();
        Iterator<Record> it = db.prefixScan(prefix);
        byte[] prev = null;
        while (it.hasNext()) {
            Record rec = it.next();
            byte[] k = rec.getKey();
            // returned key actually has the prefix, and order is strictly ascending
            assertTrue(s(k).startsWith(s(prefix)), "key lacks prefix");
            if (prev != null) assertTrue(s(prev).compareTo(s(k)) < 0, "not ascending");
            prev = k;
            out.add(k);
        }
        return out;
    }

    private static List<String> oracleKeys(TreeMap<String, byte[]> oracle, byte[] prefix) {
        List<String> out = new ArrayList<>();
        String p = s(prefix);
        for (String k : oracle.keySet()) if (k.startsWith(p)) out.add(k);
        return out;
    }

    @Test
    public void testPrefixScanAgainstOracle() throws Exception {
        String dir = TestUtils.getTestDirectory("HaloDBPrefixScanTest", "scan");
        HaloDB db = getTestDB(dir, orderedOptions());
        Random r = new Random(1);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        for (int i = 0; i < 20_000; i++) {
            byte[] k = key(r, i % 5);
            byte[] v = ("v" + i).getBytes();
            db.put(k, v);
            oracle.put(s(k), v);
        }
        for (int cat = 0; cat < 6; cat++) { // 0..4 present, 5 absent
            byte[] prefix = {(byte) cat};
            List<byte[]> got = scanKeys(db, prefix);
            List<String> expected = oracleKeys(oracle, prefix);
            assertEquals(got.size(), expected.size(), "count for category " + cat);
            for (int i = 0; i < got.size(); i++) assertEquals(s(got.get(i)), expected.get(i));
        }
        // empty prefix scans everything in order
        assertEquals(scanKeys(db, new byte[0]).size(), oracle.size());
    }

    @Test
    public void testPrefixScanReflectsDeletes() throws Exception {
        String dir = TestUtils.getTestDirectory("HaloDBPrefixScanTest", "deletes");
        HaloDB db = getTestDB(dir, orderedOptions());
        Random r = new Random(2);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            byte[] k = key(r, 0);
            db.put(k, ("v" + i).getBytes());
            oracle.put(s(k), null);
            keys.add(k);
        }
        for (int i = 0; i < keys.size(); i += 3) {
            db.delete(keys.get(i));
            oracle.remove(s(keys.get(i)));
        }
        List<byte[]> got = scanKeys(db, new byte[]{0});
        assertEquals(got.size(), oracle.size());
        for (int i = 0; i < got.size(); i++) assertEquals(s(got.get(i)), new ArrayList<>(oracle.keySet()).get(i));
    }

    @Test
    public void testPrefixScanSurvivesReopen() throws Exception {
        String dir = TestUtils.getTestDirectory("HaloDBPrefixScanTest", "reopen");
        HaloDB db = getTestDB(dir, orderedOptions());
        Random r = new Random(3);
        TreeMap<String, byte[]> oracle = new TreeMap<>();
        for (int i = 0; i < 10_000; i++) {
            byte[] k = key(r, i % 3);
            db.put(k, ("v" + i).getBytes());
            oracle.put(s(k), null);
        }
        db.close();
        // reopen: the ordered index must be rebuilt from the index files during open
        db = getTestDBWithoutDeletingFiles(dir, orderedOptions());
        byte[] prefix = {1};
        List<byte[]> got = scanKeys(db, prefix);
        List<String> expected = oracleKeys(oracle, prefix);
        assertEquals(got.size(), expected.size());
        for (int i = 0; i < got.size(); i++) assertEquals(s(got.get(i)), expected.get(i));
    }

    @Test
    public void testPrefixScanThrowsWhenDisabled() throws Exception {
        String dir = TestUtils.getTestDirectory("HaloDBPrefixScanTest", "disabled");
        HaloDB db = getTestDB(dir, new HaloDBOptions()); // ordered index off
        db.put("12345678".getBytes(), "v".getBytes());
        try {
            db.prefixScan(new byte[]{(byte) '1'});
            fail("expected HaloDBException when ordered index is disabled");
        } catch (HaloDBException expected) {
            // ok
        }
    }
}
