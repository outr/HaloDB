/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */
package com.oath.halodb.benchmarks;

public interface StorageEngine {

    void put(byte[] key, byte[] value);

    default String stats() { return "";}

    byte[] get(byte[] key);

    /** Scans all records whose key begins with {@code prefix}, reading each value; returns the count. */
    default long prefixScan(byte[] prefix) { throw new UnsupportedOperationException(); }

    default void delete(byte[] key) {};

    void open();

    void close();

    default long size() {return 0;}

    default void printStats() {

    }
}
