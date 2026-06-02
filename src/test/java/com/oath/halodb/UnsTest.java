/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

// This code is a derivative work heavily modified from the OHC project. See NOTICE file for copyright and license.

package com.oath.halodb;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.testng.Assert.*;

public class UnsTest
{
    @AfterMethod(alwaysRun = true)
    public void deinit()
    {
        Uns.clearUnsDebugForTest();
    }

    static final int CAPACITY = 65536;

    private static byte[] randomBytes(int len)
    {
        Random r = new Random();
        byte[] b = new byte[len];
        r.nextBytes(b);
        return b;
    }

    @Test
    public void testDirectBufferFor() throws Exception
    {
        // Oracle is a plain JDK heap buffer over the same bytes; directBufferFor uses BIG_ENDIAN order.
        byte[] bytes = randomBytes(CAPACITY);
        ByteBuffer reference = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

        long adr = Uns.allocate(CAPACITY);
        try
        {
            Uns.copyMemory(bytes, 0, adr, 0, CAPACITY);
            ByteBuffer buf = Uns.directBufferFor(adr, 0, CAPACITY, false);

            for (int i = 0; i < CAPACITY; i++)
            {
                byte b = buf.get();
                byte d = reference.get();
                assertEquals(b, d);

                assertEquals(buf.position(), reference.position());
                assertEquals(buf.limit(), reference.limit());
                assertEquals(buf.remaining(), reference.remaining());
                assertEquals(buf.capacity(), reference.capacity());
            }

            buf.clear();
            reference.clear();

            while (buf.remaining() >= 8)
            {
                long b = buf.getLong();
                long d = reference.getLong();
                assertEquals(b, d);

                assertEquals(buf.position(), reference.position());
                assertEquals(buf.remaining(), reference.remaining());
            }

            while (buf.remaining() >= 4)
            {
                int b = buf.getInt();
                int d = reference.getInt();
                assertEquals(b, d);

                assertEquals(buf.position(), reference.position());
                assertEquals(buf.remaining(), reference.remaining());
            }

            for (int i = 0; i < CAPACITY; i++)
            {
                byte b = buf.get(i);
                byte d = reference.get(i);
                assertEquals(b, d);

                if (i >= CAPACITY - 1)
                    continue;

                char bufChar = buf.getChar(i);
                char dirChar = reference.getChar(i);
                short bufShort = buf.getShort(i);
                short dirShort = reference.getShort(i);

                assertEquals(bufChar, dirChar);
                assertEquals(bufShort, dirShort);

                if (i >= CAPACITY - 3)
                    continue;

                int bufInt = buf.getInt(i);
                int dirInt = reference.getInt(i);
                float bufFloat = buf.getFloat(i);
                float dirFloat = reference.getFloat(i);

                assertEquals(bufInt, dirInt);
                assertEquals(bufFloat, dirFloat);

                if (i >= CAPACITY - 7)
                    continue;

                long bufLong = buf.getLong(i);
                long dirLong = reference.getLong(i);
                double bufDouble = buf.getDouble(i);
                double dirDouble = reference.getDouble(i);

                assertEquals(bufLong, dirLong);
                assertEquals(bufDouble, dirDouble);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testAllocate() throws Exception
    {
        long adr = Uns.allocate(100);
        assertNotEquals(adr, 0L);
        Uns.free(adr);

        adr = Uns.allocateIOException(100);
        Uns.free(adr);
    }

    @Test(expectedExceptions = IOException.class)
    public void testAllocateTooMuch() throws Exception
    {
        Uns.allocateIOException(Long.MAX_VALUE);
    }

    @Test
    public void testGetTotalAllocated() throws Exception
    {
        long before = Uns.getTotalAllocated();
        if (before < 0L)
            return;

        long adr = Uns.allocate(128 * 1024 * 1024);
        try
        {
            assertTrue(Uns.getTotalAllocated() > before);
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testCopyMemory() throws Exception
    {
        byte[] ref = HashTableTestUtils.randomBytes(7777 + 130);
        byte[] arr = new byte[7777 + 130];

        long adr = Uns.allocate(7777 + 130);
        try
        {
            for (int offset = 0; offset < 10; offset += 13)
                for (int off = 0; off < 10; off += 13)
                {
                    Uns.copyMemory(ref, off, adr, offset, 7777);

                    equals(ref, adr, offset, 7777);

                    Uns.copyMemory(adr, offset, arr, off, 7777);

                    equals(ref, arr, off, 7777);
                }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    private static void equals(byte[] ref, long adr, int off, int len)
    {
        for (; len-- > 0; off++)
            assertEquals(Uns.getByte(adr, off), ref[off]);
    }

    private static void equals(byte[] ref, byte[] arr, int off, int len)
    {
        for (; len-- > 0; off++)
            assertEquals(arr[off], ref[off]);
    }

    @Test
    public void testSetMemory() throws Exception
    {
        long adr = Uns.allocate(7777 + 130);
        try
        {
            for (byte b = 0; b < 13; b++)
                for (int offset = 0; offset < 10; offset += 13)
                {
                    Uns.setMemory(adr, offset, 7777, b);

                    for (int off = 0; off < 7777; off++)
                        assertEquals(Uns.getByte(adr, offset), b);
                }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testGetLongFromByteArray() throws Exception
    {
        byte[] arr = HashTableTestUtils.randomBytes(32);
        // getLongFromByteArray mirrors native byte order, so the oracle reads in native order too.
        ByteBuffer reference = ByteBuffer.wrap(arr).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 14; i++)
        {
            long u = Uns.getLongFromByteArray(arr, i);
            long b = reference.getLong(i);
            assertEquals(b, u);
        }
    }

    @Test
    public void testGetPutLong() throws Exception
    {
        byte[] src = HashTableTestUtils.randomBytes(128);
        ByteBuffer oracle = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder());
        long adr = Uns.allocate(128);
        try
        {
            Uns.copyMemory(src, 0, adr, 0, 128);

            for (int i = 0; i < 14; i++)
            {
                long l = Uns.getLong(adr, i);
                assertEquals(oracle.getLong(i), l);

                Uns.putLong(adr, i, l);
                assertEquals(Uns.getLong(adr, i), l);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testGetPutInt() throws Exception
    {
        byte[] src = HashTableTestUtils.randomBytes(128);
        ByteBuffer oracle = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder());
        long adr = Uns.allocate(128);
        try
        {
            Uns.copyMemory(src, 0, adr, 0, 128);

            for (int i = 0; i < 14; i++)
            {
                int l = Uns.getInt(adr, i);
                assertEquals(oracle.getInt(i), l);

                Uns.putInt(adr, i, l);
                assertEquals(Uns.getInt(adr, i), l);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testGetPutShort() throws Exception
    {
        byte[] src = HashTableTestUtils.randomBytes(128);
        ByteBuffer oracle = ByteBuffer.wrap(src).order(ByteOrder.nativeOrder());
        long adr = Uns.allocate(128);
        try
        {
            Uns.copyMemory(src, 0, adr, 0, 128);

            for (int i = 0; i < 14; i++)
            {
                short l = Uns.getShort(adr, i);
                assertEquals(oracle.getShort(i), l);

                Uns.putShort(adr, i, l);
                assertEquals(Uns.getShort(adr, i), l);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testGetPutByte() throws Exception
    {
        long adr = Uns.allocate(128);
        try
        {
            Uns.copyMemory(HashTableTestUtils.randomBytes(128), 0, adr, 0, 128);

            for (int i = 0; i < 14; i++)
            {
                ByteBuffer buf = Uns.directBufferFor(adr, i, 8, false);
                byte l = Uns.getByte(adr, i);
                assertEquals(buf.get(0), Uns.getByte(adr, i));

                Uns.putByte(adr, i, l);
                assertEquals(buf.get(0), l);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testDecrementIncrement() throws Exception
    {
        long adr = Uns.allocate(128);
        try
        {
            // Atomic counters require 4-byte aligned offsets under the FFM API.
            for (int i = 0; i + 4 <= 128; i += 4)
            {
                String loop = "at loop #" + i;
                long v = Uns.getInt(adr, i);
                Uns.increment(adr, i);
                assertEquals(Uns.getInt(adr, i), v + 1, loop);
                Uns.increment(adr, i);
                assertEquals(Uns.getInt(adr, i), v + 2, loop);
                Uns.increment(adr, i);
                assertEquals(Uns.getInt(adr, i), v + 3, loop);
                Uns.decrement(adr, i);
                assertEquals(Uns.getInt(adr, i), v + 2, loop);
                Uns.decrement(adr, i);
                assertEquals(Uns.getInt(adr, i), v + 1, loop);
            }

            Uns.putLong(adr, 8, 1);
            assertTrue(Uns.decrement(adr, 8));
            Uns.putLong(adr, 8, 2);
            assertFalse(Uns.decrement(adr, 8));
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testCompare() throws Exception
    {
        long adr = Uns.allocate(CAPACITY);
        try
        {
            long adr2 = Uns.allocate(CAPACITY);
            try
            {

                Uns.setMemory(adr, 5, 11, (byte) 0);
                Uns.setMemory(adr2, 5, 11, (byte) 1);

                assertFalse(Uns.memoryCompare(adr, 5, adr2, 5, 11));

                assertTrue(Uns.memoryCompare(adr, 5, adr, 5, 11));
                assertTrue(Uns.memoryCompare(adr2, 5, adr2, 5, 11));

                Uns.setMemory(adr, 5, 11, (byte) 1);

                assertTrue(Uns.memoryCompare(adr, 5, adr2, 5, 11));
            }
            finally
            {
                Uns.free(adr2);
            }
        }
        finally
        {
            Uns.free(adr);
        }
    }

    @Test
    public void testCompareManyKeys() {

        Random random = new Random();
        for (int i = 0; i < 128; i++) {
            long adr1 = Uns.allocate(CAPACITY);
            long adr2 = Uns.allocate(CAPACITY);
            try {
                byte[] key = com.oath.halodb.TestUtils.generateRandomByteArray();
                Uns.copyMemory(key, 0, adr1, i, key.length);
                Uns.copyMemory(key, 0, adr2, i, key.length);
                assertTrue(Uns.memoryCompare(adr1, i, adr2, i, key.length));


                int offsetToChange = i + random.nextInt(key.length);
                byte change = (byte)~Uns.getByte(adr2, offsetToChange);

                Uns.setMemory(adr2, offsetToChange, 1, change);
                assertFalse(Uns.memoryCompare(adr1, i, adr2, i, key.length));
            }
            finally {
                Uns.free(adr1);
                Uns.free(adr2);
            }
        }
    }

}
