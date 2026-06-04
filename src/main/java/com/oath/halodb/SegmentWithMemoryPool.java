/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.oath.halodb.histo.EstimatedHistogram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

class SegmentWithMemoryPool<V> extends Segment<V> {

    private static final Logger logger = LoggerFactory.getLogger(SegmentWithMemoryPool.class);

    // maximum hash table size
    private static final int MAX_TABLE_SIZE = 1 << 30;

    private long hitCount = 0;
    private long size = 0;
    private long missCount = 0;
    private long putAddCount = 0;
    private long putReplaceCount = 0;
    private long removeCount = 0;
    private long threshold = 0;
    private final float loadFactor;
    private long rehashes = 0;

    private final List<MemoryPoolChunk> chunks;
    private byte currentChunkIndex = -1;

    private final int chunkSize;

    private final MemoryPoolAddress emptyAddress = new MemoryPoolAddress((byte) -1, -1);

    private MemoryPoolAddress freeListHead = emptyAddress;
    private long freeListSize = 0;

    private final int fixedSlotSize;

    private final HashTableValueSerializer<V> valueSerializer;

    private Table table;

    private final ByteBuffer oldValueBuffer = ByteBuffer.allocate(fixedValueLength);
    private final ByteBuffer newValueBuffer = ByteBuffer.allocate(fixedValueLength);

    private final HashAlgorithm hashAlgorithm;

    SegmentWithMemoryPool(OffHeapHashTableBuilder<V> builder) {
        super(builder.getValueSerializer(), builder.getFixedValueSize(), builder.getFixedKeySize(),
              builder.getHasher());

        this.chunks = new ArrayList<>();
        this.chunkSize = builder.getMemoryPoolChunkSize();
        this.valueSerializer = builder.getValueSerializer();
        this.fixedSlotSize = MemoryPoolHashEntries.HEADER_SIZE + fixedKeyLength + fixedValueLength;
        this.hashAlgorithm = builder.getHashAlgorighm();

        int hts = builder.getHashTableSize();
        if (hts <= 0) {
            hts = 8192;
        }
        if (hts < 256) {
            hts = 256;
        }
        int msz = Ints.checkedCast(HashTableUtil.roundUpToPowerOf2(hts, MAX_TABLE_SIZE));
        table = Table.create(msz);
        if (table == null) {
            throw new RuntimeException("unable to allocate off-heap memory for segment");
        }

        float lf = builder.getLoadFactor();
        if (lf <= .0d) {
            lf = .75f;
        }
        this.loadFactor = lf;
        threshold = (long) ((double) table.size() * loadFactor);
    }

    @Override
    public V getEntry(KeyBuffer key) {
        boolean wasFirst = lock();
        try {
            for (MemoryPoolAddress address = table.getFirst(key.hash());
                 address.chunkIndex >= 0;
                 address = nextEntry(address)) {

                if (entryKeyEquals(address, key.buffer)) {
                    hitCount++;
                    MemoryPoolChunk chunk = chunks.get(address.chunkIndex);
                    return valueSerializer.deserialize(chunk.readOnlyValueByteBuffer(address.chunkOffset));
                }
            }

            missCount++;
            return null;
        } finally {
            unlock(wasFirst);
        }
    }

    @Override
    public boolean containsEntry(KeyBuffer key) {
        boolean wasFirst = lock();
        try {
            for (MemoryPoolAddress address = table.getFirst(key.hash());
                 address.chunkIndex >= 0;
                 address = nextEntry(address)) {

                if (entryKeyEquals(address, key.buffer)) {
                    hitCount++;
                    return true;
                }
            }

            missCount++;
            return false;
        } finally {
            unlock(wasFirst);
        }
    }

    @Override
    boolean putEntry(byte[] key, V value, long hash, boolean putIfAbsent, V oldValue) {
        boolean wasFirst = lock();
        try {
            if (oldValue != null) {
                oldValueBuffer.clear();
                valueSerializer.serialize(oldValue, oldValueBuffer);
            }
            newValueBuffer.clear();
            valueSerializer.serialize(value, newValueBuffer);

            MemoryPoolAddress first = table.getFirst(hash);
            for (MemoryPoolAddress address = first; address.chunkIndex >= 0; address = nextEntry(address)) {
                if (entryKeyEquals(address, key)) {
                    // key is already present in the segment.

                    // putIfAbsent is true, but key is already present, return.
                    if (putIfAbsent) {
                        return false;
                    }

                    MemoryPoolChunk chunk = chunks.get(address.chunkIndex);
                    // code for replace() operation
                    if (oldValue != null) {
                        if (!chunk.compareValue(address.chunkOffset, oldValueBuffer.array())) {
                            return false;
                        }
                    }

                    // replace value with the new one (value always lives in the head slot).
                    chunk.setValue(newValueBuffer.array(), address.chunkOffset);
                    putReplaceCount++;
                    return true;
                }
            }

            if (oldValue != null) {
                // key is not present but old value is not null.
                // we consider this as a mismatch and return.
                return false;
            }

            if (size >= threshold) {
                rehash();
                first = table.getFirst(hash);
            }

            // key is not present in the segment, we need to add a new entry.
            MemoryPoolAddress head = writeToFreeSlots(key, newValueBuffer.array(), first);
            table.addAsHead(hash, head);
            size++;
            putAddCount++;
        } finally {
            unlock(wasFirst);
        }

        return true;
    }

    @Override
    public boolean removeEntry(KeyBuffer key) {
        boolean wasFirst = lock();
        try {
            MemoryPoolAddress previous = null;
            for (MemoryPoolAddress address = table.getFirst(key.hash());
                 address.chunkIndex >= 0;
                 previous = address, address = nextEntry(address)) {

                if (entryKeyEquals(address, key.buffer)) {
                    removeInternal(address, previous, key.hash());
                    removeCount++;
                    size--;
                    return true;
                }
            }

            return false;
        } finally {
            unlock(wasFirst);
        }
    }

    // ---- entry-level chain navigation ----
    //
    // An entry occupies 1 head slot plus N overflow slots (N>0 only for keys longer than
    // fixedKeyLength), all linked by the per-slot next pointer: head -> frag1 -> ... -> fragN ->
    // (next entry's head). So the raw slot chain interleaves entries and their fragments; the
    // number of slots an entry spans is derived from its key length.

    private int getKeyLen(MemoryPoolAddress head) {
        return chunks.get(head.chunkIndex).getKeyLength(head.chunkOffset);
    }

    private MemoryPoolAddress rawNext(MemoryPoolAddress address) {
        return chunks.get(address.chunkIndex).getNextAddress(address.chunkOffset);
    }

    /** Number of overflow slots a key of the given length needs (0 if it fits in the head slot). */
    private int numOverflowSlots(int keyLength) {
        if (keyLength <= fixedKeyLength) {
            return 0;
        }
        int remaining = keyLength - fixedKeyLength;
        int cap = fixedSlotSize - MemoryPoolHashEntries.ENTRY_OFF_FRAGMENT;
        return (remaining + cap - 1) / cap;
    }

    /** Address of the next entry's head, skipping this entry's overflow slots. */
    private MemoryPoolAddress nextEntry(MemoryPoolAddress head) {
        int hops = numOverflowSlots(getKeyLen(head)) + 1;
        MemoryPoolAddress a = head;
        for (int i = 0; i < hops; i++) {
            a = rawNext(a);
        }
        return a;
    }

    /** Address of this entry's last (tail) slot — the head itself when the key has no overflow. */
    private MemoryPoolAddress tail(MemoryPoolAddress head) {
        int hops = numOverflowSlots(getKeyLen(head));
        MemoryPoolAddress a = head;
        for (int i = 0; i < hops; i++) {
            a = rawNext(a);
        }
        return a;
    }

    private boolean entryKeyEquals(MemoryPoolAddress head, byte[] key) {
        MemoryPoolChunk chunk = chunks.get(head.chunkIndex);
        int keyLen = chunk.getKeyLength(head.chunkOffset);
        if (keyLen != key.length) {
            return false;
        }
        if (keyLen <= fixedKeyLength) {
            // fast path: whole key inline in the head slot.
            return chunk.compareInlineKey(head.chunkOffset, key, keyLen);
        }
        // multi-slot key: compare the inline portion then each fragment.
        if (!chunk.compareInlineKey(head.chunkOffset, key, fixedKeyLength)) {
            return false;
        }
        int cap = fixedSlotSize - MemoryPoolHashEntries.ENTRY_OFF_FRAGMENT;
        int pos = fixedKeyLength;
        MemoryPoolAddress a = chunk.getNextAddress(head.chunkOffset);
        while (pos < keyLen) {
            MemoryPoolChunk c = chunks.get(a.chunkIndex);
            int len = Math.min(cap, keyLen - pos);
            if (!c.compareFragment(a.chunkOffset, key, pos, len)) {
                return false;
            }
            pos += len;
            a = c.getNextAddress(a.chunkOffset);
        }
        return true;
    }

    /** Reconstruct an entry's full key (used for rehashing; not on the lookup hot path). */
    private byte[] readKey(MemoryPoolAddress head) {
        MemoryPoolChunk chunk = chunks.get(head.chunkIndex);
        int keyLen = chunk.getKeyLength(head.chunkOffset);
        byte[] key = new byte[keyLen];
        int inline = Math.min(keyLen, fixedKeyLength);
        chunk.readInlineKey(head.chunkOffset, key, 0, inline);
        int cap = fixedSlotSize - MemoryPoolHashEntries.ENTRY_OFF_FRAGMENT;
        int pos = inline;
        MemoryPoolAddress a = chunk.getNextAddress(head.chunkOffset);
        while (pos < keyLen) {
            MemoryPoolChunk c = chunks.get(a.chunkIndex);
            int len = Math.min(cap, keyLen - pos);
            c.readFragment(a.chunkOffset, key, pos, len);
            pos += len;
            a = c.getNextAddress(a.chunkOffset);
        }
        return key;
    }

    // ---- slot allocation ----

    private MemoryPoolAddress allocateSlot() {
        if (!freeListHead.equals(emptyAddress)) {
            MemoryPoolAddress slot = freeListHead;
            freeListHead = chunks.get(slot.chunkIndex).getNextAddress(slot.chunkOffset);
            --freeListSize;
            return slot;
        }

        if (currentChunkIndex == -1 || chunks.get(currentChunkIndex).remaining() < fixedSlotSize) {
            if (chunks.size() > Byte.MAX_VALUE) {
                logger.error("No more memory left. Each segment can have at most {} chunks.", Byte.MAX_VALUE + 1);
                throw new OutOfMemoryError("Each segment can have at most " + (Byte.MAX_VALUE + 1) + " chunks.");
            }
            // No chunk allocated yet, or the current chunk has no room left: allocate a new one.
            chunks.add(MemoryPoolChunk.create(chunkSize, fixedKeyLength, fixedValueLength));
            ++currentChunkIndex;
        }

        MemoryPoolChunk chunk = chunks.get(currentChunkIndex);
        return new MemoryPoolAddress(currentChunkIndex, chunk.allocateSlot());
    }

    /**
     * Allocate the slots for an entry, write the key (inline portion + overflow fragments) and
     * value, and chain them so the tail points at {@code nextAddress}. Returns the head address.
     */
    private MemoryPoolAddress writeToFreeSlots(byte[] key, byte[] value, MemoryPoolAddress nextAddress) {
        int keyLen = key.length;
        int overflow = numOverflowSlots(keyLen);

        MemoryPoolAddress head = allocateSlot();
        MemoryPoolChunk headChunk = chunks.get(head.chunkIndex);
        headChunk.setKeyLength(head.chunkOffset, keyLen);
        headChunk.setInlineKey(head.chunkOffset, key, Math.min(keyLen, fixedKeyLength));
        headChunk.setValue(value, head.chunkOffset);

        if (overflow == 0) {
            headChunk.setNextAddress(head.chunkOffset, nextAddress);
            return head;
        }

        // Write the overflowing key bytes across chained slots.
        int cap = fixedSlotSize - MemoryPoolHashEntries.ENTRY_OFF_FRAGMENT;
        MemoryPoolAddress prev = head;
        MemoryPoolChunk prevChunk = headChunk;
        int pos = fixedKeyLength;
        for (int i = 0; i < overflow; i++) {
            MemoryPoolAddress slot = allocateSlot();
            prevChunk.setNextAddress(prev.chunkOffset, slot);
            MemoryPoolChunk chunk = chunks.get(slot.chunkIndex);
            int len = Math.min(cap, keyLen - pos);
            chunk.setFragment(slot.chunkOffset, key, pos, len);
            pos += len;
            prev = slot;
            prevChunk = chunk;
        }
        prevChunk.setNextAddress(prev.chunkOffset, nextAddress);
        return head;
    }

    private void removeInternal(MemoryPoolAddress head, MemoryPoolAddress previous, long hash) {
        MemoryPoolAddress next = nextEntry(head);
        if (table.getFirst(hash).equals(head)) {
            table.addAsHead(hash, next);
        } else if (previous == null) {
            //this should never happen.
            throw new IllegalArgumentException("Removing entry which is not head but with previous null");
        } else {
            // splice this entry out by pointing the previous entry's tail at our successor.
            MemoryPoolAddress prevTail = tail(previous);
            chunks.get(prevTail.chunkIndex).setNextAddress(prevTail.chunkOffset, next);
        }

        // return every slot of this entry (head + overflow) to the free list.
        MemoryPoolAddress a = head;
        while (!a.equals(next)) {
            MemoryPoolAddress n = rawNext(a);
            chunks.get(a.chunkIndex).setNextAddress(a.chunkOffset, freeListHead);
            freeListHead = a;
            ++freeListSize;
            a = n;
        }
    }

    private void rehash() {
        long start = System.currentTimeMillis();
        Table currentTable = table;
        int tableSize = currentTable.size();
        if (tableSize > MAX_TABLE_SIZE) {
            return;
        }

        Table newTable = Table.create(tableSize * 2);
        Hasher hasher = Hasher.create(hashAlgorithm);
        MemoryPoolAddress next;

        for (int i = 0; i < tableSize; i++) {
            for (MemoryPoolAddress head = table.getFirst(i); head.chunkIndex >= 0; head = next) {
                next = nextEntry(head);
                long hash = hasher.hash(readKey(head));
                MemoryPoolAddress first = newTable.getFirst(hash);
                newTable.addAsHead(hash, head);
                // chain the rest of the new bucket onto this entry's tail slot.
                MemoryPoolAddress t = tail(head);
                chunks.get(t.chunkIndex).setNextAddress(t.chunkOffset, first);
            }
        }

        threshold = (long) ((float) newTable.size() * loadFactor);
        table.release();
        table = newTable;
        rehashes++;

        logger.info("Completed rehashing segment in {} ms.", (System.currentTimeMillis() - start));
    }

    @Override
    long size() {
        return size;
    }

    @Override
    void release() {
        boolean wasFirst = lock();
        try {
            chunks.forEach(MemoryPoolChunk::destroy);
            chunks.clear();
            currentChunkIndex = -1;
            size = 0;
            table.release();
        } finally {
            unlock(wasFirst);
        }

    }

    @Override
    void clear() {
        boolean wasFirst = lock();
        try {
            chunks.forEach(MemoryPoolChunk::destroy);
            chunks.clear();
            currentChunkIndex = -1;
            size = 0;
            table.clear();
        } finally {
            unlock(wasFirst);
        }
    }

    @Override
    long hitCount() {
        return hitCount;
    }

    @Override
    long missCount() {
        return missCount;
    }

    @Override
    long putAddCount() {
        return putAddCount;
    }

    @Override
    long putReplaceCount() {
        return putReplaceCount;
    }

    @Override
    long removeCount() {
        return removeCount;
    }

    @Override
    void resetStatistics() {
        rehashes = 0L;
        hitCount = 0L;
        missCount = 0L;
        putAddCount = 0L;
        putReplaceCount = 0L;
        removeCount = 0L;
    }

    @Override
    long numberOfChunks() {
        return chunks.size();
    }

    @Override
    long numberOfSlots() {
        return chunks.size() * chunkSize / fixedSlotSize;
    }

    @Override
    long freeListSize() {
        return freeListSize;
    }

    @Override
    long rehashes() {
        return rehashes;
    }

    @Override
    float loadFactor() {
        return loadFactor;
    }

    @Override
    int hashTableSize() {
        return table.size();
    }

    @Override
    void updateBucketHistogram(EstimatedHistogram hist) {
        boolean wasFirst = lock();
        try {
            table.updateBucketHistogram(hist, chunks);
        } finally {
            unlock(wasFirst);
        }
    }

    static final class Table {

        final int mask;
        final long address;
        private boolean released;

        static Table create(int hashTableSize) {
            int msz = Ints.checkedCast(HashTableUtil.MEMORY_POOL_BUCKET_ENTRY_LEN * hashTableSize);
            long address = Uns.allocate(msz, true);
            return address != 0L ? new Table(address, hashTableSize) : null;
        }

        private Table(long address, int hashTableSize) {
            this.address = address;
            this.mask = hashTableSize - 1;
            clear();
        }

        void clear() {
            Uns.setMemory(address, 0L, HashTableUtil.MEMORY_POOL_BUCKET_ENTRY_LEN * size(), (byte) -1);
        }

        void release() {
            Uns.free(address);
            released = true;
        }

        protected void finalize() throws Throwable {
            if (!released) {
                Uns.free(address);
            }
            super.finalize();
        }

        MemoryPoolAddress getFirst(long hash) {
            long bOffset = address + bucketOffset(hash);
            byte chunkIndex = Uns.getByte(bOffset, 0);
            int chunkOffset = Uns.getInt(bOffset, 1);
            return new MemoryPoolAddress(chunkIndex, chunkOffset);

        }

        void addAsHead(long hash, MemoryPoolAddress entryAddress) {
            long bOffset = address + bucketOffset(hash);
            Uns.putByte(bOffset, 0, entryAddress.chunkIndex);
            Uns.putInt(bOffset, 1, entryAddress.chunkOffset);
        }

        long bucketOffset(long hash) {
            return bucketIndexForHash(hash) * HashTableUtil.MEMORY_POOL_BUCKET_ENTRY_LEN;
        }

        private int bucketIndexForHash(long hash) {
            return (int) (hash & mask);
        }

        int size() {
            return mask + 1;
        }

        void updateBucketHistogram(EstimatedHistogram h, final List<MemoryPoolChunk> chunks) {
            for (int i = 0; i < size(); i++) {
                int len = 0;
                for (MemoryPoolAddress adr = getFirst(i); adr.chunkIndex >= 0;
                     adr = chunks.get(adr.chunkIndex).getNextAddress(adr.chunkOffset)) {
                    len++;
                }
                h.add(len + 1);
            }
        }
    }

    @VisibleForTesting
    MemoryPoolAddress getFreeListHead() {
        return freeListHead;
    }

    @VisibleForTesting
    int getChunkWriteOffset(int index) {
        return chunks.get(index).getWriteOffset();
    }
}
