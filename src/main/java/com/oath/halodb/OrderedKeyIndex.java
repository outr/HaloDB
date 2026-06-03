/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import java.io.Closeable;
import java.util.Iterator;

/**
 * An ordered set of keys kept in native (off-heap) memory, used only to support prefix/range scans.
 *
 * <p>This sits <em>alongside</em> HaloDB's hash index (it does not replace it): point gets,
 * {@code isRecordFresh}, and full-scan iteration keep using the hash table, so existing read paths
 * are unaffected. Only the write path (and only when ordered indexing is enabled) maintains this
 * structure. Keys are compared as unsigned-byte lexicographic strings, which is the order a prefix
 * scan walks.
 *
 * <p>Concurrency model is single-writer / multi-reader: HaloDB serializes writes through one write
 * lock, so {@link #add}/{@link #remove} are never called concurrently with each other, while
 * {@link #contains}/{@link #scan} may run concurrently with a writer.
 */
interface OrderedKeyIndex extends Closeable {

    /** Adds the key. Returns true if it was newly added, false if it was already present. */
    boolean add(byte[] key);

    /** Removes the key. Returns true if it was present. */
    boolean remove(byte[] key);

    boolean contains(byte[] key);

    long size();

    /**
     * Iterates keys in ascending unsigned-byte order within [startInclusive, endExclusive).
     * A null end means "to the end". The returned keys are fresh copies.
     */
    Iterator<byte[]> scan(byte[] startInclusive, byte[] endExclusive);

    /** Iterates, in order, every key that begins with {@code prefix}. */
    default Iterator<byte[]> prefixScan(byte[] prefix) {
        return scan(prefix, prefixUpperBound(prefix));
    }

    /**
     * The smallest key that is strictly greater than every key starting with {@code prefix} — i.e.
     * the exclusive upper bound for a prefix scan. Returns null when the prefix is all 0xFF bytes
     * (no upper bound; scan runs to the end).
     */
    static byte[] prefixUpperBound(byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            int b = prefix[i] & 0xFF;
            if (b != 0xFF) {
                byte[] bound = new byte[i + 1];
                System.arraycopy(prefix, 0, bound, 0, i + 1);
                bound[i] = (byte) (b + 1);
                return bound;
            }
        }
        return null;
    }
}
