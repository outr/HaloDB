/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

// This code is a derivative work heavily modified from the OHC project. See NOTICE file for copyright and license.

package com.oath.halodb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.CRC32;

/**
 * Off-heap memory access for the in-memory index.
 *
 * <p>Historically this was implemented on {@code sun.misc.Unsafe}, which is deprecated for removal.
 * It is now built on the Foreign Function &amp; Memory API (JDK 22+). Raw {@code long} addresses
 * returned by {@link FFMNativeAllocator} are accessed through {@link #ALL}, a single segment that
 * spans the whole address space; every access is expressed as an absolute-address offset into it.
 * The {@code *_UNALIGNED} layouts use the platform's native byte order and impose no alignment
 * requirement, exactly mirroring the previous {@code Unsafe} semantics. Atomic counters use the
 * naturally-aligned layouts, which the FFM API requires for atomic access.
 *
 * <p>{@link MemorySegment#reinterpret(long)} and the {@link FFMNativeAllocator} downcalls are
 * "restricted" methods; run with {@code --enable-native-access=ALL-UNNAMED} (or the module that
 * embeds HaloDB) to suppress the runtime warning.
 */
final class Uns {

    private static final Logger LOGGER = LoggerFactory.getLogger(Uns.class);

    private static final NativeMemoryAllocator allocator;

    /** View over the entire native address space; absolute addresses are used as offsets into it. */
    private static final MemorySegment ALL = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private static final VarHandle INT_ATOMIC = ValueLayout.JAVA_INT.varHandle();

    private static final boolean __DEBUG_OFF_HEAP_MEMORY_ACCESS = Boolean.parseBoolean(System.getProperty(OffHeapHashTableBuilder.SYSTEM_PROPERTY_PREFIX + "debugOffHeapAccess", "false"));

    //
    // #ifdef __DEBUG_OFF_HEAP_MEMORY_ACCESS
    //
    private static final ConcurrentMap<Long, AllocInfo> ohDebug = __DEBUG_OFF_HEAP_MEMORY_ACCESS ? new ConcurrentHashMap<Long, AllocInfo>(16384) : null;
    private static final Map<Long, Throwable> ohFreeDebug = __DEBUG_OFF_HEAP_MEMORY_ACCESS ? new ConcurrentHashMap<Long, Throwable>(16384) : null;

    private static final class AllocInfo {

        final long size;
        final Throwable trace;

        AllocInfo(Long size, Throwable trace) {
            this.size = size;
            this.trace = trace;
        }
    }

    static void clearUnsDebugForTest() {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS) {
            try {
                if (!ohDebug.isEmpty()) {
                    for (Map.Entry<Long, AllocInfo> addrSize : ohDebug.entrySet()) {
                        System.err.printf("  still allocated: address=%d, size=%d%n", addrSize.getKey(), addrSize.getValue().size);
                        addrSize.getValue().trace.printStackTrace();
                    }
                    throw new RuntimeException("Not all allocated memory has been freed!");
                }
            } finally {
                ohDebug.clear();
                ohFreeDebug.clear();
            }
        }
    }

    private static void freed(long address) {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS) {
            AllocInfo allocInfo = ohDebug.remove(address);
            if (allocInfo == null) {
                Throwable freedAt = ohFreeDebug.get(address);
                throw new IllegalStateException("Free of unallocated region " + address, freedAt);
            }
            ohFreeDebug.put(address, new Exception("free backtrace - t=" + System.nanoTime()));
        }
    }

    private static void allocated(long address, long bytes) {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS) {
            AllocInfo allocatedLen =
                ohDebug.putIfAbsent(address, new AllocInfo(bytes, new Exception("Thread: " + Thread.currentThread())));
            if (allocatedLen != null) {
                throw new Error("Oops - allocate() got duplicate address");
            }
            ohFreeDebug.remove(address);
        }
    }

    private static void validate(long address, long offset, long len) {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS) {
            if (address == 0L) {
                throw new NullPointerException();
            }
            AllocInfo allocInfo = ohDebug.get(address);
            if (allocInfo == null) {
                Throwable freedAt = ohFreeDebug.get(address);
                throw new IllegalStateException("Access to unallocated region " + address + " - t=" + System.nanoTime(), freedAt);
            }
            if (offset < 0L) {
                throw new IllegalArgumentException("Negative offset");
            }
            if (len < 0L) {
                throw new IllegalArgumentException("Negative length");
            }
            if (offset + len > allocInfo.size) {
                throw new IllegalArgumentException("Access outside allocated region");
            }
        }
    }
    //
    // #endif
    //

    static {
        if (__DEBUG_OFF_HEAP_MEMORY_ACCESS)
            LOGGER.warn("Degraded performance due to off-heap memory allocations and access guarded by debug code enabled via system property " + OffHeapHashTableBuilder.SYSTEM_PROPERTY_PREFIX + "debugOffHeapAccess=true");

        allocator = new FFMNativeAllocator();
        LOGGER.info("HaloDB using FFM (java.lang.foreign) off-heap memory access");
    }

    private Uns() {
    }

    static long getLongFromByteArray(byte[] array, int offset) {
        if (offset < 0 || offset + 8 > array.length)
            throw new ArrayIndexOutOfBoundsException();
        return MemorySegment.ofArray(array).get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }

    static int getIntFromByteArray(byte[] array, int offset) {
        if (offset < 0 || offset + 4 > array.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return MemorySegment.ofArray(array).get(ValueLayout.JAVA_INT_UNALIGNED, offset);
    }

    static short getShortFromByteArray(byte[] array, int offset) {
        if (offset < 0 || offset + 2 > array.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return MemorySegment.ofArray(array).get(ValueLayout.JAVA_SHORT_UNALIGNED, offset);
    }

    static void putLong(long address, long offset, long value) {
        validate(address, offset, 8L);
        ALL.set(ValueLayout.JAVA_LONG_UNALIGNED, address + offset, value);
    }

    static long getLong(long address, long offset) {
        validate(address, offset, 8L);
        return ALL.get(ValueLayout.JAVA_LONG_UNALIGNED, address + offset);
    }

    static void putInt(long address, long offset, int value) {
        validate(address, offset, 4L);
        ALL.set(ValueLayout.JAVA_INT_UNALIGNED, address + offset, value);
    }

    static int getInt(long address, long offset) {
        validate(address, offset, 4L);
        return ALL.get(ValueLayout.JAVA_INT_UNALIGNED, address + offset);
    }

    static void putShort(long address, long offset, short value) {
        validate(address, offset, 2L);
        ALL.set(ValueLayout.JAVA_SHORT_UNALIGNED, address + offset, value);
    }

    static short getShort(long address, long offset) {
        validate(address, offset, 2L);
        return ALL.get(ValueLayout.JAVA_SHORT_UNALIGNED, address + offset);
    }

    static void putByte(long address, long offset, byte value) {
        validate(address, offset, 1L);
        ALL.set(ValueLayout.JAVA_BYTE, address + offset, value);
    }

    static byte getByte(long address, long offset) {
        validate(address, offset, 1L);
        return ALL.get(ValueLayout.JAVA_BYTE, address + offset);
    }

    /**
     * Atomically decrements the 32-bit counter and returns true if it reached zero.
     * The offset must be 4-byte aligned (required for atomic access via the FFM API).
     */
    static boolean decrement(long address, long offset) {
        validate(address, offset, 4L);
        int v = (int) INT_ATOMIC.getAndAdd(ALL, address + offset, -1);
        return v == 1;
    }

    /**
     * Atomically increments the 32-bit counter. The offset must be 4-byte aligned.
     */
    static void increment(long address, long offset) {
        validate(address, offset, 4L);
        INT_ATOMIC.getAndAdd(ALL, address + offset, 1);
    }

    static void copyMemory(byte[] arr, int off, long address, long offset, long len) {
        validate(address, offset, len);
        MemorySegment.copy(MemorySegment.ofArray(arr), off, ALL, address + offset, len);
    }

    static void copyMemory(long address, long offset, byte[] arr, int off, long len) {
        validate(address, offset, len);
        MemorySegment.copy(ALL, address + offset, MemorySegment.ofArray(arr), off, len);
    }

    static void copyMemory(long src, long srcOffset, long dst, long dstOffset, long len) {
        validate(src, srcOffset, len);
        validate(dst, dstOffset, len);
        MemorySegment.copy(ALL, src + srcOffset, ALL, dst + dstOffset, len);
    }

    static void setMemory(long address, long offset, long len, byte val) {
        validate(address, offset, len);
        ALL.asSlice(address + offset, len).fill(val);
    }

    static boolean memoryCompare(long adr1, long off1, long adr2, long off2, long len) {
        if (adr1 == 0L) {
            return false;
        }

        if (adr1 == adr2) {
            assert off1 == off2;
            return true;
        }

        for (; len >= 8; len -= 8, off1 += 8, off2 += 8) {
            if (Uns.getLong(adr1, off1) != Uns.getLong(adr2, off2)) {
                return false;
            }
        }
        for (; len >= 4; len -= 4, off1 += 4, off2 += 4) {
            if (Uns.getInt(adr1, off1) != Uns.getInt(adr2, off2)) {
                return false;
            }
        }
        for (; len >= 2; len -= 2, off1 += 2, off2 += 2) {
            if (Uns.getShort(adr1, off1) != Uns.getShort(adr2, off2)) {
                return false;
            }
        }
        for (; len > 0; len--, off1++, off2++) {
            if (Uns.getByte(adr1, off1) != Uns.getByte(adr2, off2)) {
                return false;
            }
        }

        return true;
    }

    static long crc32(long address, long offset, long len) {
        validate(address, offset, len);
        CRC32 crc = new CRC32();
        crc.update(directBufferFor(address, offset, len, true));
        long h = crc.getValue();
        h |= h << 32;
        return h;
    }

    static long getTotalAllocated() {
        return allocator.getTotalAllocated();
    }

    static long allocate(long bytes) {
        return allocate(bytes, false);
    }

    static long allocate(long bytes, boolean throwOOME) {
        long address = allocator.allocate(bytes);
        if (address != 0L) {
            allocated(address, bytes);
        } else if (throwOOME) {
            throw new OutOfMemoryError("unable to allocate " + bytes + " in off-heap");
        }
        return address;
    }

    static long allocateIOException(long bytes) throws IOException {
        return allocateIOException(bytes, false);
    }

    static long allocateIOException(long bytes, boolean throwOOME) throws IOException {
        long address = allocate(bytes, throwOOME);
        if (address == 0L) {
            throw new IOException("unable to allocate " + bytes + " in off-heap");
        }
        return address;
    }

    static void free(long address) {
        if (address == 0L) {
            return;
        }
        freed(address);
        allocator.free(address);
    }

    static ByteBuffer directBufferFor(long address, long offset, long len, boolean readOnly) {
        if (len > Integer.MAX_VALUE || len < 0L) {
            throw new IllegalArgumentException();
        }
        ByteBuffer bb = ALL.asSlice(address + offset, len).asByteBuffer();
        if (readOnly) {
            bb = bb.asReadOnlyBuffer();
        }
        bb.order(ByteOrder.BIG_ENDIAN);
        return bb;
    }

    static ByteBuffer readOnlyBuffer(long hashEntryAdr, int length, long offset) {
        return Uns.directBufferFor(hashEntryAdr + offset, 0, length, true);
    }

    static ByteBuffer buffer(long hashEntryAdr, long length, long offset) {
        return Uns.directBufferFor(hashEntryAdr + offset, 0, length, false);
    }
}
