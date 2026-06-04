/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Exercises arbitrary-length keys (well beyond the old 127-byte cap) on both index implementations
 * via the {@code Options} data provider. For the memory-pool variant the default {@code fixedKeySize}
 * is 127, so keys larger than that span multiple chained slots.
 */
public class HaloDBLargeKeyTest extends TestBase {

    // Sizes around the inline/overflow boundary (127) and several multi-slot sizes.
    private static final int[] KEY_SIZES = {1, 8, 100, 127, 128, 200, 511, 1000, 4096, 16384};

    private static byte[] uniqueKey(int size, Set<ByteBuffer> seen) {
        byte[] key;
        do {
            key = TestUtils.generateRandomByteArray(size);
        } while (!seen.add(ByteBuffer.wrap(key)));
        return key;
    }

    @Test(dataProvider = "Options")
    public void testVariousKeySizes(HaloDBOptions options) throws HaloDBException {
        String directory = TestUtils.getTestDirectory("HaloDBLargeKeyTest", "testVariousKeySizes");
        options.setCompactionDisabled(true);
        HaloDB db = getTestDB(directory, options);

        Random r = new Random(42);
        Set<ByteBuffer> seen = new HashSet<>();
        List<Record> records = new ArrayList<>();
        for (int size : KEY_SIZES) {
            byte[] key = uniqueKey(size, seen);
            byte[] value = TestUtils.generateRandomByteArray(50 + r.nextInt(200));
            db.put(key, value);
            records.add(new Record(key, value));
        }

        // every key, including the multi-slot ones, reads back its value.
        for (Record record : records) {
            Assert.assertEquals(db.get(record.getKey()), record.getValue());
            Assert.assertTrue(db.contains(record.getKey()));
            Assert.assertEquals(db.valueSize(record.getKey()), record.getValue().length);
        }

        // update each key in place with a new value.
        for (int i = 0; i < records.size(); i++) {
            byte[] newValue = TestUtils.generateRandomByteArray(80);
            db.put(records.get(i).getKey(), newValue);
            records.set(i, new Record(records.get(i).getKey(), newValue));
        }
        for (Record record : records) {
            Assert.assertEquals(db.get(record.getKey()), record.getValue());
        }

        // delete every other key; the rest must remain intact (verifies chain splicing).
        for (int i = 0; i < records.size(); i += 2) {
            db.delete(records.get(i).getKey());
        }
        for (int i = 0; i < records.size(); i++) {
            if (i % 2 == 0) {
                Assert.assertNull(db.get(records.get(i).getKey()));
                Assert.assertFalse(db.contains(records.get(i).getKey()));
            } else {
                Assert.assertEquals(db.get(records.get(i).getKey()), records.get(i).getValue());
            }
        }
    }

    @Test(dataProvider = "Options")
    public void testLargeKeysPersistAcrossReopen(HaloDBOptions options) throws HaloDBException {
        String directory = TestUtils.getTestDirectory("HaloDBLargeKeyTest", "testLargeKeysPersistAcrossReopen");
        options.setCompactionDisabled(true);
        HaloDB db = getTestDB(directory, options);

        Random r = new Random(7);
        Set<ByteBuffer> seen = new HashSet<>();
        List<Record> records = new ArrayList<>();
        for (int size : KEY_SIZES) {
            byte[] key = uniqueKey(size, seen);
            byte[] value = TestUtils.generateRandomByteArray(64 + r.nextInt(64));
            db.put(key, value);
            records.add(new Record(key, value));
        }

        // delete one large key so a tombstone with a long key must also round-trip on reopen.
        byte[] deletedKey = records.get(records.size() - 1).getKey();
        db.delete(deletedKey);

        // reopen: the in-memory index is rebuilt from the .index/.tombstone files.
        db.close();
        db = getTestDBWithoutDeletingFiles(directory, options);

        for (int i = 0; i < records.size() - 1; i++) {
            Assert.assertEquals(db.get(records.get(i).getKey()), records.get(i).getValue());
        }
        Assert.assertNull(db.get(deletedKey));
    }

    @Test(dataProvider = "Options")
    public void testManyLargeKeysWithRehash(HaloDBOptions options) throws HaloDBException {
        // Insert enough large (multi-slot) keys to force the off-heap index to rehash, then verify
        // every key is still retrievable — exercising rehash over multi-slot entries.
        String directory = TestUtils.getTestDirectory("HaloDBLargeKeyTest", "testManyLargeKeysWithRehash");
        options.setCompactionDisabled(true);
        HaloDB db = getTestDB(directory, options);

        int count = 5000;
        Random r = new Random(99);
        Set<ByteBuffer> seen = new HashSet<>();
        List<Record> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] key = uniqueKey(150 + r.nextInt(400), seen); // always > 127 -> multi-slot
            byte[] value = TestUtils.generateRandomByteArray(32);
            db.put(key, value);
            records.add(new Record(key, value));
        }

        Assert.assertEquals(db.size(), count);
        for (Record record : records) {
            Assert.assertEquals(db.get(record.getKey()), record.getValue());
        }

        // remove half and confirm the rest survive (chain splicing under load).
        for (int i = 0; i < count; i += 2) {
            db.delete(records.get(i).getKey());
        }
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                Assert.assertNull(db.get(records.get(i).getKey()));
            } else {
                Assert.assertEquals(db.get(records.get(i).getKey()), records.get(i).getValue());
            }
        }
    }
}
