/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import java.nio.ByteBuffer;

import static com.oath.halodb.MemoryPoolHashEntries.*;

/**
 * Memory pool is divided into chunks of configurable size, each holding a number of fixed-size
 * slots. This represents such a chunk.
 *
 * <p>A slot is either the HEAD of an entry — holding the next pointer, the (int) key length, up to
 * {@code fixedKeyLength} inline key bytes and the {@code fixedValueLength} value — or an OVERFLOW
 * slot holding a key fragment for a key longer than {@code fixedKeyLength}. Slots are addressed by
 * their byte offset within the chunk and chained, across chunks if necessary, via the next pointer.
 * See {@link MemoryPoolHashEntries} for the byte layout. This class only reads and writes slot
 * fields; the chaining of head and overflow slots into entries is managed by
 * {@link SegmentWithMemoryPool}.
 */
class MemoryPoolChunk {

    private final long address;
    private final int chunkSize;
    private final int fixedKeyLength;
    private final int fixedValueLength;
    private final int fixedSlotSize;
    private int writeOffset = 0;

    private MemoryPoolChunk(long address, int chunkSize, int fixedKeyLength, int fixedValueLength) {
        this.address = address;
        this.chunkSize = chunkSize;
        this.fixedKeyLength = fixedKeyLength;
        this.fixedValueLength = fixedValueLength;
        this.fixedSlotSize = HEADER_SIZE + fixedKeyLength + fixedValueLength;
    }

    static MemoryPoolChunk create(int chunkSize, int fixedKeyLength, int fixedValueLength) {
        int fixedSlotSize = HEADER_SIZE + fixedKeyLength + fixedValueLength;
        if (fixedSlotSize > chunkSize) {
            throw new IllegalArgumentException("fixedSlotSize " + fixedSlotSize + " must be smaller than chunkSize " + chunkSize);
        }
        long address = Uns.allocate(chunkSize, true);
        return new MemoryPoolChunk(address, chunkSize, fixedKeyLength, fixedValueLength);
    }

    void destroy() {
        Uns.free(address);
    }

    /** Number of bytes a key fragment can hold in a single overflow slot. */
    int fragmentCapacity() {
        return fixedSlotSize - ENTRY_OFF_FRAGMENT;
    }

    // ---- slot allocation (sequential fill of fresh slots) ----

    /** Reserve the next free slot in this chunk, returning its offset. */
    int allocateSlot() {
        if (chunkSize - writeOffset < fixedSlotSize) {
            throw new IllegalArgumentException(
                String.format("Chunk full. Chunk size %d. write offset %d. fixed slot size %d",
                              chunkSize, writeOffset, fixedSlotSize));
        }
        int slotOffset = writeOffset;
        writeOffset += fixedSlotSize;
        return slotOffset;
    }

    int getWriteOffset() {
        return writeOffset;
    }

    int remaining() {
        return chunkSize - writeOffset;
    }

    // ---- next pointer ----

    MemoryPoolAddress getNextAddress(int slotOffset) {
        byte chunkIndex = Uns.getByte(address, slotOffset + ENTRY_OFF_NEXT_CHUNK_INDEX);
        int chunkOffset = Uns.getInt(address, slotOffset + ENTRY_OFF_NEXT_CHUNK_OFFSET);
        return new MemoryPoolAddress(chunkIndex, chunkOffset);
    }

    void setNextAddress(int slotOffset, MemoryPoolAddress next) {
        Uns.putByte(address, slotOffset + ENTRY_OFF_NEXT_CHUNK_INDEX, next.chunkIndex);
        Uns.putInt(address, slotOffset + ENTRY_OFF_NEXT_CHUNK_OFFSET, next.chunkOffset);
    }

    // ---- head slot: key length, inline key, value ----

    int getKeyLength(int slotOffset) {
        return Uns.getInt(address, slotOffset + ENTRY_OFF_KEY_LENGTH);
    }

    void setKeyLength(int slotOffset, int keyLength) {
        Uns.putInt(address, slotOffset + ENTRY_OFF_KEY_LENGTH, keyLength);
    }

    /** Write the first {@code length} bytes of {@code key} into the head slot's inline key region. */
    void setInlineKey(int slotOffset, byte[] key, int length) {
        Uns.copyMemory(key, 0, address, slotOffset + ENTRY_OFF_DATA, length);
    }

    void setValue(byte[] value, int slotOffset) {
        if (value.length != fixedValueLength) {
            throw new IllegalArgumentException(
                String.format("Invalid value length. fixedValueLength %d, value length %d",
                              fixedValueLength, value.length));
        }
        Uns.copyMemory(value, 0, address, slotOffset + ENTRY_OFF_DATA + fixedKeyLength, value.length);
    }

    /** Compare the first {@code length} bytes of {@code key} against the head slot's inline key. */
    boolean compareInlineKey(int slotOffset, byte[] key, int length) {
        return compare(slotOffset + ENTRY_OFF_DATA, key, 0, length);
    }

    boolean compareValue(int slotOffset, byte[] value) {
        return compare(slotOffset + ENTRY_OFF_DATA + fixedKeyLength, value, 0, fixedValueLength);
    }

    void readInlineKey(int slotOffset, byte[] dest, int destPos, int length) {
        Uns.copyMemory(address, slotOffset + ENTRY_OFF_DATA, dest, destPos, length);
    }

    ByteBuffer readOnlyValueByteBuffer(int slotOffset) {
        return Uns.directBufferFor(address, slotOffset + ENTRY_OFF_DATA + fixedKeyLength, fixedValueLength, true);
    }

    // ---- overflow slot: key fragment ----

    /** Write {@code length} bytes of {@code key} starting at {@code keyPos} into an overflow slot. */
    void setFragment(int slotOffset, byte[] key, int keyPos, int length) {
        Uns.copyMemory(key, keyPos, address, slotOffset + ENTRY_OFF_FRAGMENT, length);
    }

    boolean compareFragment(int slotOffset, byte[] key, int keyPos, int length) {
        return compare(slotOffset + ENTRY_OFF_FRAGMENT, key, keyPos, length);
    }

    void readFragment(int slotOffset, byte[] dest, int destPos, int length) {
        Uns.copyMemory(address, slotOffset + ENTRY_OFF_FRAGMENT, dest, destPos, length);
    }

    /** Compare {@code length} bytes at {@code byteOffset} in this chunk with {@code array} from {@code arrayPos}. */
    private boolean compare(int byteOffset, byte[] array, int arrayPos, int length) {
        int p = 0;
        for (; length - p >= 8; p += 8) {
            if (Uns.getLong(address, byteOffset + p) != Uns.getLongFromByteArray(array, arrayPos + p)) {
                return false;
            }
        }
        for (; length - p >= 4; p += 4) {
            if (Uns.getInt(address, byteOffset + p) != Uns.getIntFromByteArray(array, arrayPos + p)) {
                return false;
            }
        }
        for (; length - p >= 2; p += 2) {
            if (Uns.getShort(address, byteOffset + p) != Uns.getShortFromByteArray(array, arrayPos + p)) {
                return false;
            }
        }
        for (; length - p >= 1; p += 1) {
            if (Uns.getByte(address, byteOffset + p) != array[arrayPos + p]) {
                return false;
            }
        }
        return true;
    }
}
