# Storage Engine Benchmarks

An sbt subproject that benchmarks HaloDB, including a side-by-side comparison against RocksDB.
It depends on the HaloDB source directly, so it always builds against the current tree (no
`publishLocal` needed). Requires **Java 22+** like the main project.

## Side-by-side comparison (HaloDB vs RocksDB)

`Comparison` runs an identical workload — fill, multithreaded random reads, then prefix scans —
against each engine and prints their write throughput, read throughput/latency, and prefix-scan
throughput next to each other. Both engines see the same keys and the same random read order (fixed
seed), so the numbers are directly comparable. HaloDB's prefix scan uses the ordered index
(`setUseOrderedIndex`); RocksDB's uses a `RocksIterator` over its sorted keyspace.

```bash
sbt "benchmarks/run quick"     # ~2M x 1KB records, fits in RAM — fast iteration
sbt "benchmarks/run large"     # 40M records, exceeds RAM so reads hit disk
```

Override any preset value, or pick engines:

```bash
sbt "benchmarks/run quick --records=5000000 --record-size=512 --reads=10000000 --read-threads=16"
sbt "benchmarks/run quick --engines=halodb"          # one engine only
sbt "benchmarks/run quick --rocks-compress=true"      # enable RocksDB LZ4 compression
sbt "benchmarks/run quick --dir=/mnt/ssd/bench"        # data directory (default: target/benchmark-data)
```

Sample output (one workstation, in page cache — directional only):

```
                            1KB records (quick)        16KB records
metric                      halodb     rocksdb      halodb     rocksdb
WRITE ops/sec               305,508  1,407,542      71,183     222,664
READ  ops/sec             2,929,898  1,488,505     635,900     493,585
READ  p50 (us)                  2.1        4.7         (HaloDB wins reads)
PREFIX keys/sec              30,695  1,156,098     115,653     192,062
```

Reading the three dimensions:
* **Point reads** — HaloDB wins (read-amplification-1), ~1.3–2x.
* **Writes** — RocksDB wins (LSM), ~3–4.6x.
* **Prefix/range scans** — RocksDB wins, but the margin depends heavily on record size. RocksDB stores
  values sorted, so a range scan reads them sequentially; HaloDB reads each matched record
  individually (its data is append-ordered, not key-ordered). For **small** records that per-record
  read is seek/overhead-bound and RocksDB is ~38x faster; for **large** records it is transfer-bound
  and the gap collapses to ~1.7x. So HaloDB's prefix scanning is most competitive for large records.

### Reading the results — caveats

Engine comparisons are sensitive; treat the output as directional on your hardware, not an absolute
ranking:

- **Dataset vs RAM.** `quick` fits in page cache, so reads measure each engine's CPU/index path;
  `large` exceeds RAM so reads hit disk — closer to HaloDB's real-world target.
- **Durability profile.** RocksDB runs with the WAL disabled (memtable writes); HaloDB flushes to
  the OS page cache rather than fsync-ing per write. Neither is durable per write, which keeps the
  write comparison roughly like-for-like.
- **Compression.** RocksDB compression is **off** by default for a like-for-like comparison with
  HaloDB (which stores values raw); `--rocks-compress` enables LZ4.
- **Config.** Both engines use a fixed, reasonable config (see `HaloDBStorageEngine` /
  `RocksDBStorageEngine`); tuning either changes the picture.

## Single-engine deep benchmark

`BenchmarkTool` is the original, large-scale single-engine harness (`FILL_SEQUENCE`, `FILL_RANDOM`,
`READ_RANDOM`, `RANDOM_UPDATE`, `READ_AND_UPDATE`):

```bash
sbt "benchmarks/runMain com.oath.halodb.benchmarks.BenchmarkTool <db directory> READ_RANDOM"
```
