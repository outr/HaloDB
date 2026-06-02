# Storage Engine Benchmarks

An sbt subproject that benchmarks HaloDB, including a side-by-side comparison against RocksDB.
It depends on the HaloDB source directly, so it always builds against the current tree (no
`publishLocal` needed). Requires **Java 22+** like the main project.

## Side-by-side comparison (HaloDB vs RocksDB)

`Comparison` runs an identical workload — fill, then multithreaded random reads — against each
engine and prints their write throughput and read throughput/latency next to each other. Both
engines see the same keys and the same random read order (fixed seed), so the numbers are directly
comparable.

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

Sample output (`quick`, in-RAM, one workstation — directional only):

```
metric                            halodb           rocksdb
WRITE ops/sec                    302,638         1,104,091
WRITE MB/sec                       295.5           1,078.2
READ ops/sec                   2,749,455         1,018,378
READ p50 (us)                        2.4               6.7
READ p99 (us)                        6.6              19.8
READ p99.9 (us)                     16.0              41.6
```

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
