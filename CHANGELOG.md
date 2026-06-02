# HaloDB Change Log

## Unreleased
* Migrated off-heap memory access from `sun.misc.Unsafe` (deprecated for removal in JDK 23+) to the
  Foreign Function & Memory API (`java.lang.foreign`). Resolves issue #63.
* Off-heap allocation now uses libc `malloc`/`free` via FFM downcalls; the JNA dependency has been removed.
* **Minimum supported Java version is now 22** (FFM was finalized in JDK 22). Run with
  `--enable-native-access=ALL-UNNAMED` to suppress the restricted-method warning.
* Test suite now runs under TestNG via `sbt test` and on modern JDKs (jmockit upgraded 1.38 → 1.49).

## 0.4.3 (08/20/2018)
* Sequence number, instead of relying on system time, is now a number incremented for each write operation. 
* Include compaction rate in stats.  

## 0.4.2 (08/06/2018)
* Handle the case where db crashes while it is being repaired due to error from a previous crash.
* _put_ operation in _HaloDB_ now returns a boolean value indicating the status of the operation.

## 0.4.1 (7/16/2018)
* Include version, checksum and max file size in META file. 
* _maxFileSize_ in _HaloDBOptions_ now accepts only int values.  

## 0.4.0 (7/11/2018)
* Implemented memory pool for in-memory index. 

