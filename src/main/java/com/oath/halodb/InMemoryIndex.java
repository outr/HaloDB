/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import com.google.common.primitives.Ints;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Hash table stored in native memory, outside Java heap.
 *
 * <p>When ordered indexing is enabled, an off-heap ART side index of the key set is maintained
 * alongside the hash table to support prefix/range scans. The hash table remains the sole structure
 * for point lookups, so reads are unaffected. The ART is single-writer (mutated only under the
 * caller's write lock and the parallel open-build, both serialized here by {@link #orderedLock})
 * and read by scans under the read lock — so node frees never race with a scan.
 */
class InMemoryIndex {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryIndex.class);
    private static final byte[] EMPTY = new byte[0];

    private final OffHeapHashTable<InMemoryIndexMetaData> offHeapHashTable;

    private final int noOfSegments;
    private final int maxSizeOfEachSegment;

    // Optional ordered side index (key set) for prefix/range scans.
    private final OffHeapART orderedIndex;
    private final ReadWriteLock orderedLock;

    InMemoryIndex(int numberOfKeys, boolean useMemoryPool, int fixedKeySize, int memoryPoolChunkSize) {
        this(numberOfKeys, useMemoryPool, fixedKeySize, memoryPoolChunkSize, false);
    }

    InMemoryIndex(int numberOfKeys, boolean useMemoryPool, int fixedKeySize, int memoryPoolChunkSize, boolean useOrderedIndex) {
        noOfSegments = Ints.checkedCast(Utils.roundUpToPowerOf2(Runtime.getRuntime().availableProcessors() * 2));
        maxSizeOfEachSegment = Ints.checkedCast(Utils.roundUpToPowerOf2(numberOfKeys / noOfSegments));
        long start = System.currentTimeMillis();
        OffHeapHashTableBuilder<InMemoryIndexMetaData> builder =
            OffHeapHashTableBuilder.<InMemoryIndexMetaData>newBuilder()
                .valueSerializer(new InMemoryIndexMetaDataSerializer())
                .segmentCount(noOfSegments)
                .hashTableSize(maxSizeOfEachSegment)
                .fixedValueSize(InMemoryIndexMetaData.SERIALIZED_SIZE)
                .loadFactor(1);

        if (useMemoryPool) {
            builder.useMemoryPool(true).fixedKeySize(fixedKeySize).memoryPoolChunkSize(memoryPoolChunkSize);
        }

        this.offHeapHashTable = builder.build();

        if (useOrderedIndex) {
            this.orderedIndex = new OffHeapART(fixedKeySize, 0); // key-only side index
            this.orderedLock = new ReentrantReadWriteLock();
        } else {
            this.orderedIndex = null;
            this.orderedLock = null;
        }

        logger.debug("Allocated memory for the index in {}", (System.currentTimeMillis() - start));
    }

    boolean hasOrderedIndex() {
        return orderedIndex != null;
    }

    private void orderedAdd(byte[] key) {
        orderedLock.writeLock().lock();
        try {
            orderedIndex.put(key, EMPTY); // idempotent: no-op if the key is already present
        } finally {
            orderedLock.writeLock().unlock();
        }
    }

    private void orderedRemove(byte[] key) {
        orderedLock.writeLock().lock();
        try {
            orderedIndex.remove(key);
        } finally {
            orderedLock.writeLock().unlock();
        }
    }

    boolean put(byte[] key, InMemoryIndexMetaData metaData) {
        boolean r = offHeapHashTable.put(key, metaData);
        if (orderedIndex != null) orderedAdd(key);
        return r;
    }

    boolean putIfAbsent(byte[] key, InMemoryIndexMetaData metaData) {
        boolean r = offHeapHashTable.putIfAbsent(key, metaData);
        if (orderedIndex != null) orderedAdd(key);
        return r;
    }

    boolean remove(byte[] key) {
        boolean r = offHeapHashTable.remove(key);
        if (orderedIndex != null) orderedRemove(key);
        return r;
    }

    /**
     * Collects, in ascending key order, the keys with the given prefix — a snapshot taken under the
     * read lock (in-memory only; the lock is not held while the caller subsequently reads records).
     */
    List<byte[]> prefixScanKeys(byte[] prefix) {
        if (orderedIndex == null) throw new IllegalStateException("ordered index is not enabled");
        List<byte[]> keys = new ArrayList<>();
        orderedLock.readLock().lock();
        try {
            Iterator<byte[]> it = orderedIndex.prefixScan(prefix);
            while (it.hasNext()) keys.add(it.next());
        } finally {
            orderedLock.readLock().unlock();
        }
        return Collections.unmodifiableList(keys);
    }

    boolean replace(byte[] key, InMemoryIndexMetaData oldValue, InMemoryIndexMetaData newValue) {
        return offHeapHashTable.addOrReplace(key, oldValue, newValue);
    }

    InMemoryIndexMetaData get(byte[] key) {
        return offHeapHashTable.get(key);
    }

    boolean containsKey(byte[] key) {
        return offHeapHashTable.containsKey(key);
    }

    void close() {
        try {
            offHeapHashTable.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (orderedIndex != null) {
            orderedLock.writeLock().lock();
            try {
                orderedIndex.close();
            } finally {
                orderedLock.writeLock().unlock();
            }
        }
    }

    long size() {
        return offHeapHashTable.size();
    }

    public OffHeapHashTableStats stats() {
        return offHeapHashTable.stats();
    }

    void resetStats() {
        offHeapHashTable.resetStatistics();
    }

    int getNoOfSegments() {
        return noOfSegments;
    }

    int getMaxSizeOfEachSegment() {
        return maxSizeOfEachSegment;
    }
}
