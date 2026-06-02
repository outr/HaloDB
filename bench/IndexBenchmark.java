package com.oath.halodb;

import com.google.common.primitives.Longs;

import java.util.Random;

/**
 * Microbenchmark for the off-heap in-memory index (the surface changed by the FFM migration:
 * Uns get/put/copy/compare, Hasher, MemoryPoolChunk/Segment*, and the native allocator).
 *
 * No disk involved: it builds an {@link InMemoryIndex}, fills it, and times put/get throughput.
 * Single-threaded so the per-op off-heap cost is isolated from contention/scheduling noise.
 *
 * Usage: IndexBenchmark [numKeys] [pool|nopool]
 */
public class IndexBenchmark {

    public static void main(String[] args) {
        int n = args.length >= 1 ? Integer.parseInt(args[0]) : 2_000_000;
        boolean pool = args.length < 2 || args[1].equals("pool");
        int chunk = 2 * 1024 * 1024;
        int fixedKeySize = 8;

        System.out.printf("=== IndexBenchmark java=%s mode=%s n=%,d ===%n",
            System.getProperty("java.version"), pool ? "memoryPool" : "noMemoryPool", n);

        byte[][] keys = new byte[n][];
        for (int i = 0; i < n; i++) keys[i] = Longs.toByteArray(i);
        InMemoryIndexMetaData meta = new InMemoryIndexMetaData(1, 0, 1024, 42L);

        // ---- PUT throughput: fresh index each pass; first 3 passes are JIT warmup ----
        int putWarmup = 3, putPasses = 8;
        double bestPut = 0;
        for (int pass = 0; pass < putPasses; pass++) {
            InMemoryIndex idx = new InMemoryIndex(2 * n, pool, fixedKeySize, chunk);
            long s = System.nanoTime();
            for (int i = 0; i < n; i++) idx.put(keys[i], meta);
            long e = System.nanoTime();
            double ops = n / ((e - s) / 1e9);
            boolean warm = pass < putWarmup;
            if (!warm) bestPut = Math.max(bestPut, ops);
            System.out.printf("  put pass %d%-9s %,.0f ops/s  (%.1f ns/op)%n",
                pass, warm ? " [warmup]" : "", ops, (e - s) / (double) n);
            idx.close();
        }

        // ---- GET throughput: filled index, random access order; first 3 passes are warmup ----
        InMemoryIndex idx = new InMemoryIndex(2 * n, pool, fixedKeySize, chunk);
        for (int i = 0; i < n; i++) idx.put(keys[i], meta);

        Random rnd = new Random(100);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = rnd.nextInt(n);

        long checksum = 0;
        double bestGet = 0;
        int getWarmup = 6, getPasses = 18;
        for (int pass = 0; pass < getPasses; pass++) {
            long s = System.nanoTime();
            long sum = 0;
            for (int i = 0; i < n; i++) {
                InMemoryIndexMetaData m = idx.get(keys[order[i]]);
                sum += m.getValueSize(); // consume result to defeat dead-code elimination
            }
            long e = System.nanoTime();
            checksum += sum;
            double ops = n / ((e - s) / 1e9);
            boolean warm = pass < getWarmup;
            if (!warm) bestGet = Math.max(bestGet, ops);
            System.out.printf("  get pass %d%-9s %,.0f ops/s  (%.1f ns/op)%n",
                pass, warm ? " [warmup]" : "", ops, (e - s) / (double) n);
        }
        idx.close();

        System.out.printf(">>> RESULT mode=%-13s put_best=%,.0f ops/s  get_best=%,.0f ops/s  (checksum=%d)%n",
            pool ? "memoryPool" : "noMemoryPool", bestPut, bestGet, checksum);
    }
}
