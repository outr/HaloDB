/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */
package com.oath.halodb.benchmarks;

import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.io.File;

public class RocksDBStorageEngine implements StorageEngine {

    static {
        RocksDB.loadLibrary();
    }

    private RocksDB db;
    private Options options;
    private WriteOptions writeOptions;

    private final File dbDirectory;
    private final boolean compress;

    public RocksDBStorageEngine(File dbDirectory, int noOfRecords) {
        this(dbDirectory, false);
    }

    public RocksDBStorageEngine(File dbDirectory, boolean compress) {
        this.dbDirectory = dbDirectory;
        this.compress = compress;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        try {
            db.put(writeOptions, key, value);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] get(byte[] key) {
        try {
            return db.get(key);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long prefixScan(byte[] prefix) {
        try (RocksIterator it = db.newIterator()) {
            long count = 0, bytes = 0;
            for (it.seek(prefix); it.isValid() && startsWith(it.key(), prefix); it.next()) {
                bytes += it.value().length; // touch the value
                count++;
            }
            return count;
        }
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (key[i] != prefix[i]) return false;
        return true;
    }

    @Override
    public void delete(byte[] key) {
        try {
            db.delete(writeOptions, key);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void open() {
        options = new Options()
            .setCreateIfMissing(true)
            .setWriteBufferSize(128L * 1024 * 1024)
            .setMaxWriteBufferNumber(3)
            .setMaxBackgroundJobs(Math.max(4, Runtime.getRuntime().availableProcessors()))
            .setMaxBytesForLevelBase(512L * 1024 * 1024)
            .setTargetFileSizeBase(64L * 1024 * 1024)
            .setLevel0FileNumCompactionTrigger(4)
            .setLevel0SlowdownWritesTrigger(20)
            .setLevel0StopWritesTrigger(36)
            .setNumLevels(7)
            // Default off for a like-for-like comparison with HaloDB (which stores values raw);
            // pass --rocks-compress to enable LZ4.
            .setCompressionType(compress ? CompressionType.LZ4_COMPRESSION : CompressionType.NO_COMPRESSION);

        // Match HaloDB's "flush to page cache, not fsync per write" durability profile.
        writeOptions = new WriteOptions().setDisableWAL(true);

        try {
            db = RocksDB.open(options, dbDirectory.getPath());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        if (db != null) db.close();
        if (writeOptions != null) writeOptions.close();
        if (options != null) options.close();
    }

    @Override
    public long size() {
        try {
            return db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            return -1;
        }
    }
}
