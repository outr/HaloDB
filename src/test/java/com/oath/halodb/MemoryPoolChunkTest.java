/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import com.google.common.primitives.Longs;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.Random;

public class MemoryPoolChunkTest {

    private MemoryPoolChunk chunk = null;

    @AfterMethod(alwaysRun = true)
    private void destroyChunk() {
        if (chunk != null) {
            chunk.destroy();
        }
    }

    @Test
    public void testAllocateSlot() {
        int chunkSize = 16 * 1024;
        int fixedKeyLength = 12, fixedValueLength = 20;
        int slotSize = MemoryPoolHashEntries.HEADER_SIZE + fixedKeyLength + fixedValueLength;

        chunk = MemoryPoolChunk.create(chunkSize, fixedKeyLength, fixedValueLength);
        Assert.assertEquals(chunk.getWriteOffset(), 0);
        Assert.assertEquals(chunk.remaining(), chunkSize);

        int first = chunk.allocateSlot();
        Assert.assertEquals(first, 0);
        Assert.assertEquals(chunk.getWriteOffset(), slotSize);
        Assert.assertEquals(chunk.remaining(), chunkSize - slotSize);

        int second = chunk.allocateSlot();
        Assert.assertEquals(second, slotSize);
        Assert.assertEquals(chunk.getWriteOffset(), 2 * slotSize);
    }

    @Test
    public void testHeadSlotKeyValue() {
        int chunkSize = 16 * 1024;
        int fixedKeyLength = 12, fixedValueLength = 20;

        chunk = MemoryPoolChunk.create(chunkSize, fixedKeyLength, fixedValueLength);
        int offset = chunk.allocateSlot();

        byte[] key = Longs.toByteArray(101); // 8 bytes, fits inline
        byte[] value = HashTableTestUtils.randomBytes(fixedValueLength);
        MemoryPoolAddress next = new MemoryPoolAddress((byte) 10, 34343);

        chunk.setKeyLength(offset, key.length);
        chunk.setInlineKey(offset, key, key.length);
        chunk.setValue(value, offset);
        chunk.setNextAddress(offset, next);

        Assert.assertEquals(chunk.getKeyLength(offset), key.length);
        Assert.assertTrue(chunk.compareInlineKey(offset, key, key.length));
        Assert.assertTrue(chunk.compareValue(offset, value));
        Assert.assertEquals(chunk.getNextAddress(offset), next);

        byte[] readKey = new byte[key.length];
        chunk.readInlineKey(offset, readKey, 0, key.length);
        Assert.assertEquals(readKey, key);

        ByteBuffer valueBuf = chunk.readOnlyValueByteBuffer(offset);
        byte[] readValue = new byte[fixedValueLength];
        valueBuf.get(readValue);
        Assert.assertEquals(readValue, value);

        // a differing key/value must not compare equal.
        byte[] otherKey = Longs.toByteArray(102);
        Assert.assertFalse(chunk.compareInlineKey(offset, otherKey, otherKey.length));
        value[0] = (byte) ~value[0];
        Assert.assertFalse(chunk.compareValue(offset, value));
    }

    @Test
    public void testOverflowFragment() {
        int chunkSize = 16 * 1024;
        int fixedKeyLength = 8, fixedValueLength = 20;

        chunk = MemoryPoolChunk.create(chunkSize, fixedKeyLength, fixedValueLength);
        int offset = chunk.allocateSlot();
        int cap = chunk.fragmentCapacity();

        // a fragment is an arbitrary slice of a key written after the next pointer.
        byte[] key = HashTableTestUtils.randomBytes(cap);
        chunk.setFragment(offset, key, 0, cap);

        Assert.assertTrue(chunk.compareFragment(offset, key, 0, cap));

        byte[] readBack = new byte[cap];
        chunk.readFragment(offset, readBack, 0, cap);
        Assert.assertEquals(readBack, key);

        // partial fragment from a non-zero key position.
        byte[] longKey = HashTableTestUtils.randomBytes(cap + fixedKeyLength);
        chunk.setFragment(offset, longKey, fixedKeyLength, cap);
        Assert.assertTrue(chunk.compareFragment(offset, longKey, fixedKeyLength, cap));

        longKey[fixedKeyLength] = (byte) ~longKey[fixedKeyLength];
        Assert.assertFalse(chunk.compareFragment(offset, longKey, fixedKeyLength, cap));
    }

    @Test
    public void setAndGetNextAddress() {
        int chunkSize = 1024;
        Random r = new Random();
        int fixedKeyLength = 1 + r.nextInt(100), fixedValueLength = 1 + r.nextInt(100);

        chunk = MemoryPoolChunk.create(chunkSize, fixedKeyLength, fixedValueLength);

        MemoryPoolAddress nextAddress = new MemoryPoolAddress((byte) r.nextInt(Byte.MAX_VALUE), r.nextInt());
        int offset = r.nextInt(chunkSize - fixedKeyLength - fixedValueLength - MemoryPoolHashEntries.HEADER_SIZE);
        chunk.setNextAddress(offset, nextAddress);

        Assert.assertEquals(chunk.getNextAddress(offset), nextAddress);
    }
}
