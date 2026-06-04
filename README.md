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

`HaloDBOptions` carries the tuning knobs (compaction, flush size, memory pool, index threads, …) —
see the setter Javadoc. The main trade-off is `compactionThresholdPerFile` (write vs space
amplification).

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
