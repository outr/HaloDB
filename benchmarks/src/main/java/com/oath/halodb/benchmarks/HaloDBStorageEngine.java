/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */
package com.oath.halodb.benchmarks;

import com.google.common.primitives.Ints;
import com.oath.halodb.HaloDB;
import com.oath.halodb.HaloDBException;
import com.oath.halodb.HaloDBOptions;
import com.oath.halodb.Record;

import java.io.File;
import java.util.Iterator;

public class HaloDBStorageEngine implements StorageEngine {

    private final File dbDirectory;

    private HaloDB db;
    private final long noOfRecords;
    private final int keySize;

    public HaloDBStorageEngine(File dbDirectory, long noOfRecords) {
        this(dbDirectory, noOfRecords, 8);
    }

    public HaloDBStorageEngine(File dbDirectory, long noOfRecords, int keySize) {
        this.dbDirectory = dbDirectory;
        this.noOfRecords = noOfRecords;
        this.keySize = keySize;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        try {
            db.put(key, value);
        } catch (HaloDBException e) {
            e.printStackTrace();
        }

    }

    @Override
    public byte[] get(byte[] key) {
        try {
            return db.get(key);
        } catch (HaloDBException e) {
            e.printStackTrace();
        }

        return new byte[0];
    }

    @Override
    public long prefixScan(byte[] prefix) {
        try {
            Iterator<Record> it = db.prefixScan(prefix);
            long count = 0, bytes = 0;
            while (it.hasNext()) {
                Record r = it.next();
                bytes += r.getValue().length; // touch the value
                count++;
            }
            return count;
        } catch (HaloDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void delete(byte[] key) {
        try {
            db.delete(key);
        } catch (HaloDBException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void open() {
        HaloDBOptions opts = new HaloDBOptions();
        opts.setMaxFileSize(1024*1024*1024);
        opts.setCompactionThresholdPerFile(0.50);
        opts.setFlushDataSizeBytes(10 * 1024 * 1024);
        opts.setNumberOfRecords(Ints.checkedCast(2 * noOfRecords));
        opts.setCompactionJobRate(135 * 1024 * 1024);
        opts.setUseMemoryPool(true);
        opts.setFixedKeySize(keySize);
        // The ordered (ART) index requires fixed keys <= 127 bytes; enable prefix/range scans only
        // when the configured key size fits. Larger keys still work for point reads/writes via the
        // hash index (overflowing into chained memory-pool slots).
        if (keySize <= 127) {
            opts.setUseOrderedIndex(true); // enable prefix/range scans
        }

        try {
            db = HaloDB.open(dbDirectory, opts);
        } catch (HaloDBException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        if (db != null){
            try {
                db.close();
            } catch (HaloDBException e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public long size() {
        return db.size();
    }

    @Override
    public void printStats() {
        
    }

    @Override
    public String stats() {
        return db.stats().toString();
    }
}
