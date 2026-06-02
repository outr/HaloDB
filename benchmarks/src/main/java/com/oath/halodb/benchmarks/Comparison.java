/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */
package com.oath.halodb.benchmarks;

import org.HdrHistogram.Histogram;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Side-by-side benchmark: runs an identical fill + multithreaded random-read workload against
 * HaloDB and RocksDB and prints their write throughput and read throughput/latency next to each
 * other.
 *
 * Usage:  Comparison [quick|large] [--records=N] [--record-size=BYTES] [--reads=N]
 *                    [--read-threads=N] [--dir=PATH] [--engines=halodb,rocksdb] [--rocks-compress]
 *
 * Both engines see the same keys (sequential 8-byte) and the same random read order (fixed seed),
 * so the numbers are directly comparable. Values are reused per put (unique in their first 8 bytes)
 * to keep allocation noise out of the write timing.
 *
 * Caveats: results depend heavily on dataset-vs-RAM (the `quick` preset fits in page cache, so it
 * leans on each engine's CPU/index path; `large` exceeds RAM so reads hit disk), per-engine config,
 * value size, compression, and hardware. Treat the output as a directional comparison on this box,
 * not an absolute ranking.
 */
public class Comparison {

    static final int SEED = 100;

    static final class Config {
        long records;
        int recordSize;
        long reads;
        int readThreads;
        boolean rocksCompress;
        File dir;
        List<String> engines = new ArrayList<>(List.of("halodb", "rocksdb"));
    }

    static final class Result {
        String engine;
        double writeOpsPerSec;
        double writeMbPerSec;
        double writeSeconds;
        double readOpsPerSec;
        double readSeconds;
        Histogram readLatencyNanos;
        long misses;
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);

        double dataGb = cfg.records * (double) cfg.recordSize / (1024 * 1024 * 1024);
        System.out.printf(
            "Workload: records=%,d  recordSize=%dB  reads=%,d  readThreads=%d  (~%.2f GB of values)%n",
            cfg.records, cfg.recordSize, cfg.reads, cfg.readThreads, dataGb);
        System.out.printf("Engines: %s   dir=%s   rocksCompress=%s%n%n",
            cfg.engines, cfg.dir, cfg.rocksCompress);

        Map<String, Result> results = new LinkedHashMap<>();
        for (String engine : cfg.engines) {
            File dir = new File(cfg.dir, engine);
            deleteRecursively(dir);
            dir.mkdirs();
            StorageEngine db = engine.equals("rocksdb")
                ? new RocksDBStorageEngine(dir, cfg.rocksCompress)
                : new HaloDBStorageEngine(dir, cfg.records);
            System.out.printf("==== %s ====%n", engine);
            db.open();
            try {
                results.put(engine, runWorkload(engine, db, cfg));
            } finally {
                db.close();
            }
            System.out.println();
        }

        printComparison(cfg, results);
    }

    private static Result runWorkload(String engine, StorageEngine db, Config cfg) throws InterruptedException {
        Result r = new Result();
        r.engine = engine;

        // ---- FILL: sequential keys, reused value buffer (unique in first 8 bytes) ----
        byte[] value = new byte[cfg.recordSize];
        new Random(SEED).nextBytes(value);
        byte[] key = new byte[8];
        long fillStart = System.nanoTime();
        for (long i = 0; i < cfg.records; i++) {
            writeLong(key, i);
            writeLong(value, i); // make each value distinct
            db.put(key, value);
            if (i > 0 && i % 5_000_000 == 0) System.out.printf("  filled %,d%n", i);
        }
        r.writeSeconds = (System.nanoTime() - fillStart) / 1e9;
        r.writeOpsPerSec = cfg.records / r.writeSeconds;
        r.writeMbPerSec = (cfg.records * (double) cfg.recordSize) / r.writeSeconds / (1024 * 1024);
        System.out.printf("  fill: %,.0f ops/s, %,.1f MB/s, %.1fs%n",
            r.writeOpsPerSec, r.writeMbPerSec, r.writeSeconds);

        // ---- WARMUP reads (untimed): warms page cache, JIT, and lets writes settle ----
        long warmup = Math.min(cfg.records, Math.max(1, cfg.reads / 10));
        Random wr = new Random(SEED);
        byte[] wk = new byte[8];
        for (long i = 0; i < warmup; i++) {
            writeLong(wk, Math.floorMod(wr.nextLong(), cfg.records));
            db.get(wk);
        }

        // ---- READ: multithreaded random gets, per-thread latency histograms ----
        long perThread = cfg.reads / cfg.readThreads;
        ReadWorker[] workers = new ReadWorker[cfg.readThreads];
        long readStart = System.nanoTime();
        for (int t = 0; t < cfg.readThreads; t++) {
            workers[t] = new ReadWorker(db, perThread, cfg.records, SEED + t);
            workers[t].start();
        }
        long misses = 0;
        for (ReadWorker w : workers) {
            w.join();
            misses += w.misses;
        }
        r.readSeconds = (System.nanoTime() - readStart) / 1e9;
        long totalReads = perThread * cfg.readThreads;
        r.readOpsPerSec = totalReads / r.readSeconds;
        r.misses = misses;
        r.readLatencyNanos = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        for (ReadWorker w : workers) r.readLatencyNanos.add(w.histogram);
        System.out.printf("  read: %,.0f ops/s, %.1fs, %d misses%n",
            r.readOpsPerSec, r.readSeconds, misses);
        return r;
    }

    static final class ReadWorker extends Thread {
        final StorageEngine db;
        final long count;
        final long records;
        final long seed;
        final Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        long misses = 0;

        ReadWorker(StorageEngine db, long count, long records, long seed) {
            this.db = db;
            this.count = count;
            this.records = records;
            this.seed = seed;
        }

        @Override
        public void run() {
            Random rand = new Random(seed);
            byte[] key = new byte[8];
            for (long i = 0; i < count; i++) {
                writeLong(key, Math.floorMod(rand.nextLong(), records));
                long s = System.nanoTime();
                byte[] v = db.get(key);
                histogram.recordValue(System.nanoTime() - s);
                if (v == null) misses++;
            }
        }
    }

    private static void printComparison(Config cfg, Map<String, Result> results) {
        List<Result> rs = new ArrayList<>(results.values());
        System.out.println("================ COMPARISON ================");
        StringBuilder header = new StringBuilder(String.format("%-22s", "metric"));
        for (Result r : rs) header.append(String.format("%18s", r.engine));
        System.out.println(header);

        row("WRITE ops/sec", rs, r -> String.format("%,.0f", r.writeOpsPerSec));
        row("WRITE MB/sec", rs, r -> String.format("%,.1f", r.writeMbPerSec));
        row("WRITE total (s)", rs, r -> String.format("%.1f", r.writeSeconds));
        row("READ ops/sec", rs, r -> String.format("%,.0f", r.readOpsPerSec));
        row("READ p50 (us)", rs, r -> us(r.readLatencyNanos.getValueAtPercentile(50)));
        row("READ p99 (us)", rs, r -> us(r.readLatencyNanos.getValueAtPercentile(99)));
        row("READ p99.9 (us)", rs, r -> us(r.readLatencyNanos.getValueAtPercentile(99.9)));
        row("READ max (us)", rs, r -> us(r.readLatencyNanos.getMaxValue()));

        // Relative read/write throughput (fastest = 1.00x) for a quick at-a-glance read.
        if (rs.size() == 2) {
            System.out.println("--------------------------------------------");
            double wFast = rs.stream().mapToDouble(r -> r.writeOpsPerSec).max().orElse(1);
            double rFast = rs.stream().mapToDouble(r -> r.readOpsPerSec).max().orElse(1);
            row("WRITE relative", rs, r -> String.format("%.2fx", r.writeOpsPerSec / wFast));
            row("READ relative", rs, r -> String.format("%.2fx", r.readOpsPerSec / rFast));
        }
        System.out.println("============================================");
    }

    interface Cell {
        String of(Result r);
    }

    private static void row(String label, List<Result> rs, Cell cell) {
        StringBuilder sb = new StringBuilder(String.format("%-22s", label));
        for (Result r : rs) sb.append(String.format("%18s", cell.of(r)));
        System.out.println(sb);
    }

    private static String us(double nanos) {
        return String.format("%,.1f", nanos / 1000.0);
    }

    // ---- helpers ----

    private static void writeLong(byte[] b, long v) {
        b[0] = (byte) (v >>> 56);
        b[1] = (byte) (v >>> 48);
        b[2] = (byte) (v >>> 40);
        b[3] = (byte) (v >>> 32);
        b[4] = (byte) (v >>> 24);
        b[5] = (byte) (v >>> 16);
        b[6] = (byte) (v >>> 8);
        b[7] = (byte) v;
    }

    private static Config parse(String[] args) {
        Config cfg = new Config();
        String preset = (args.length > 0 && !args[0].startsWith("--")) ? args[0] : "quick";
        switch (preset) {
            case "large":
                cfg.records = 40_000_000;
                cfg.recordSize = 1024;
                cfg.reads = 20_000_000;
                cfg.readThreads = 16;
                break;
            case "quick":
            default:
                cfg.records = 2_000_000;
                cfg.recordSize = 1024;
                cfg.reads = 4_000_000;
                cfg.readThreads = 8;
        }
        cfg.dir = new File("target/benchmark-data");
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            String[] kv = a.substring(2).split("=", 2);
            String k = kv[0];
            String v = kv.length > 1 ? kv[1] : "true";
            switch (k) {
                case "records": cfg.records = Long.parseLong(v); break;
                case "record-size": cfg.recordSize = Integer.parseInt(v); break;
                case "reads": cfg.reads = Long.parseLong(v); break;
                case "read-threads": cfg.readThreads = Integer.parseInt(v); break;
                case "dir": cfg.dir = new File(v); break;
                case "rocks-compress": cfg.rocksCompress = Boolean.parseBoolean(v); break;
                case "engines":
                    cfg.engines = new ArrayList<>(List.of(v.split(",")));
                    break;
                default: System.out.println("Ignoring unknown option: " + a);
            }
        }
        if (cfg.recordSize < 8) throw new IllegalArgumentException("record-size must be >= 8");
        return cfg;
    }

    private static void deleteRecursively(File dir) throws IOException {
        if (!dir.exists()) return;
        try (var paths = Files.walk(dir.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
