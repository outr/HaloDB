/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Set;

public final class HaloDB {

    private HaloDBInternal dbInternal;

    private File directory;

    public static HaloDB open(File dirname, HaloDBOptions opts) throws HaloDBException {
        HaloDB db = new HaloDB();
        try {
            db.dbInternal = HaloDBInternal.open(dirname, opts);
            db.directory = dirname;
        } catch (IOException e) {
            throw new HaloDBException("Failed to open db " + dirname.getName(), e);
        }
        return db;
    }

    public static HaloDB open(String directory, HaloDBOptions opts) throws HaloDBException {
        return HaloDB.open(new File(directory), opts);
    }

    public byte[] get(byte[] key) throws HaloDBException {
        try {
            return dbInternal.get(key, 1);
        } catch (IOException e) {
            throw new HaloDBException("Lookup failed.", e);
        }
    }

    public boolean put(byte[] key, byte[] value) throws HaloDBException {
        try {
            return dbInternal.put(key, value);
        } catch (IOException e) {
            throw new HaloDBException("Store to db failed.", e);
        }
    }

    public void delete(byte[] key) throws HaloDBException {
        try {
            dbInternal.delete(key);
        } catch (IOException e) {
            throw new HaloDBException("Delete operation failed.", e);
        }
    }

    /** Returns true if {@code key} is present in the db. Resolved from the in-memory index only,
     *  with no disk access. */
    public boolean contains(byte[] key) {
        return dbInternal.contains(key);
    }

    /** Returns the size in bytes of the value stored for {@code key}, or -1 if the key is absent.
     *  Read from the in-memory index without reading the value from disk. */
    public int valueSize(byte[] key) {
        return dbInternal.valueSize(key);
    }

    public void close() throws HaloDBException {
        try {
            dbInternal.close();
        } catch (IOException e) {
            throw new HaloDBException("Error while closing " + directory.getName(), e);
        }
    }

    public long size() {
        return dbInternal.size();
    }

    public HaloDBStats stats() {
        return dbInternal.stats();
    }

    public void resetStats() {
        dbInternal.resetStats();
    }

    public HaloDBIterator newIterator() throws HaloDBException {
        return new HaloDBIterator(dbInternal);
    }

    public HaloDBKeyIterator newKeyIterator() {
        return new HaloDBKeyIterator(dbInternal);
    }

    /**
     * Iterates, in ascending key order, the records whose key begins with {@code prefix}.
     * Requires {@code HaloDBOptions.setUseOrderedIndex(true)} (with a fixed key size); otherwise
     * throws {@link HaloDBException}.
     */
    public java.util.Iterator<Record> prefixScan(byte[] prefix) throws HaloDBException {
        return dbInternal.prefixScan(prefix);
    }

    public void pauseCompaction() throws HaloDBException {
        try {
            dbInternal.pauseCompaction();
        } catch (IOException e) {
            throw new HaloDBException("Error while trying to pause compaction thread", e);
        }
    }

    public boolean snapshot() {
        return dbInternal.takeSnapshot();
    }

    public boolean clearSnapshot() {
        return dbInternal.clearSnapshot();
    }

    public File getSnapshotDirectory() {
        return dbInternal.getSnapshotDirectory();
    }

    public void resumeCompaction() {
        dbInternal.resumeCompaction();
    }

    // methods used in tests.

    @VisibleForTesting
    boolean isCompactionComplete() {
        return dbInternal.isCompactionComplete();
    }

    @VisibleForTesting
    boolean isTombstoneFilesMerging() {
        return dbInternal.isTombstoneFilesMerging();
    }
}
