<!-- GENERATED FILE — edit docs/README.md and run `sbt docs/mdoc`. -->
# HaloDB Revive

[![Apache License, Version 2.0, January 2004](https://img.shields.io/github/license/apache/maven.svg?label=License)](LICENSE)
[![CI](https://github.com/outr/HaloDB/actions/workflows/ci.yml/badge.svg)](https://github.com/outr/HaloDB/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.outr/halodb-revive.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/com.outr/halodb-revive)

A fast, embedded **key-value store for the JVM** (pure Java), tuned for IO-bound workloads with
submillisecond reads. Keys live in an off-heap in-memory index; values in append-only log files — so
a read is at most one disk seek. A maintained fork of Yahoo's unmaintained
[HaloDB](https://github.com/yahoo/HaloDB), modernized for **JDK 22+** (off-heap via the Foreign
Function & Memory API) and extended with **arbitrary-length keys** and optional **prefix/range scans**.

## Install

```scala
libraryDependencies += "com.outr" % "halodb-revive" % "0.7.0"
```

```xml
<dependency>
  <groupId>com.outr</groupId>
  <artifactId>halodb-revive</artifactId>
  <version>0.7.0</version>
</dependency>
```

Requires **JDK 22+**; run with `--enable-native-access=ALL-UNNAMED` to silence the off-heap layer's
restricted-method warning.

## Usage

Keys and values are `byte[]`:

```java
HaloDBOptions options = new HaloDBOptions();
options.setMaxFileSize(1024 * 1024 * 1024);       // 1 GB data files
options.setCompactionThresholdPerFile(0.5);       // compact at 50% stale (write amplification ≈ 2)

HaloDB db = HaloDB.open(new File("/tmp/halodb"), options); // created/reopened; index rebuilt from disk

db.put("hello".getBytes(), "world".getBytes());
byte[] value = db.get("hello".getBytes());        // "world"
db.delete("hello".getBytes());

HaloDBIterator iterator = db.newIterator();        // iterate all records (unordered)
while (iterator.hasNext()) {
    Record record = iterator.next();               // record.getKey() / record.getValue()
}

db.close();
```

## Configuration

`HaloDBOptions` exposes every tuning knob:

```java
HaloDBOptions options = new HaloDBOptions();

// Size of each data file (1 GB here).
options.setMaxFileSize(1024 * 1024 * 1024);

// Size of each tombstone file (64 MB here). Larger files mean fewer files but slower db open; too
// small results in a large number of tombstone files in the db folder.
options.setMaxTombstoneFileSize(64 * 1024 * 1024);

// Number of threads used to scan index and tombstone files in parallel to build the in-memory index
// on open. Must be positive and <= Runtime.getRuntime().availableProcessors(). Speeds up db open.
options.setBuildIndexThreads(8);

// Threshold at which the page cache is synced to disk. Data is durable only once flushed, so more
// data is lost on power loss if this is set too high; too low may hurt read/write performance.
options.setFlushDataSizeBytes(10 * 1024 * 1024);

// Percentage of stale data in a data file at which it will be compacted. This (with compactionJobRate)
// is the most important tuning knob: it controls write vs space amplification. If set to x, write
// amplification is approximately 1/x. Increasing it reduces write amplification but increases space
// amplification.
options.setCompactionThresholdPerFile(0.7);

// How fast the compaction job runs — the amount of data the compaction thread copies per second.
// The optimal value depends on compactionThresholdPerFile.
options.setCompactionJobRate(50 * 1024 * 1024);

// Preallocates enough memory for the off-heap index; if too low the db may need to rehash. For a db
// of size n, set this to 2*n.
options.setNumberOfRecords(100_000_000);

// A delete writes a tombstone record; the tombstone can be removed only once all previous versions of
// that key have been removed by compaction. Enabling this deletes, during startup, all tombstone
// records whose previous versions were already removed from the data file.
options.setCleanUpTombstonesDuringOpen(true);

// HaloDB allocates native memory for the in-memory index. Enabling this releases all allocated memory
// back to the kernel when the db is closed. Not needed if the JVM is shut down on close (the kernel
// reclaims it automatically). Without the memory pool, this can be slow as _free_ is called per record.
options.setCleanUpInMemoryIndexOnClose(false);

// ** memory pool settings ** — a lower-footprint, lower-fragmentation index using fixed-size slots.
options.setUseMemoryPool(true);

// The hash table (like Java 7's ConcurrentHashMap) is split into segments — twice the number of CPU
// cores — each managing its own native memory, further divided into chunks of this size.
options.setMemoryPoolChunkSize(2 * 1024 * 1024);

// With a memory pool, fixedKeySize declares the inline key size of each slot. Keys up to this size
// occupy a single slot; longer keys overflow into additional chained slots, so keys of any length are
// supported (set this to your typical key size for best density).
options.setFixedKeySize(8);

// Enables prefix/range scans (see below). Requires fixed-length keys.
options.setUseOrderedIndex(true);
```

## Prefix / range scans (optional)

Enabling the ordered index keeps an off-heap
[adaptive radix tree](https://db.in.tum.de/~leis/papers/ART.pdf) alongside the hash index, adding
ascending prefix scans. It needs **fixed-length keys** and leaves point-read latency unchanged.

```java
HaloDBOptions options = new HaloDBOptions();
options.setUseOrderedIndex(true);
options.setFixedKeySize(8);

HaloDB db = HaloDB.open(new File("/tmp/halodb-ordered"), options);
Iterator<Record> matches = db.prefixScan("user:001".getBytes()); // ascending key order
db.close();
```

## Notes

- **Read amplification of 1** — submillisecond point reads; the off-heap index keeps the JVM heap small.
- Keys may be any length; the ordered index is the exception (fixed-length only).
- The WAL *is* the database: on restart only the actively-written files are repaired. Writes hit the
  page cache and are flushed at `flushDataSizeBytes`, so unflushed data is lost on power loss.
- **On-disk format is version 1** (4-byte key length); pre-0.7 databases must be rebuilt.

## More

- [Benchmarks](docs/benchmarks.md) vs RocksDB (reads, writes, prefix scans, key-size scaling).
- [Why HaloDB](docs/WhyHaloDB.md) — design and motivation.
- Originally by [Arjun Mannaly](https://github.com/amannaly) at Yahoo; fork maintained by
  [OUTR](https://github.com/outr). Apache License 2.0.
