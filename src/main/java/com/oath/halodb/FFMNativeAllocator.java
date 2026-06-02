/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Off-heap allocator backed by the C standard library {@code malloc}/{@code free}, reached through
 * the Foreign Function &amp; Memory API rather than {@code sun.misc.Unsafe} or JNA.
 *
 * <p>Pointers are exchanged as raw {@code long} addresses: on the 64-bit platforms HaloDB targets a
 * {@code void*} and {@code size_t} are both eight bytes, so they share the integer calling convention
 * and can be described with {@link ValueLayout#JAVA_LONG}. This preserves the historic per-address
 * {@code free(ptr)} model and support for allocations larger than 2GB.
 */
final class FFMNativeAllocator implements NativeMemoryAllocator {

    private static final MethodHandle MALLOC;
    private static final MethodHandle FREE;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        MALLOC = linker.downcallHandle(
            libc.find("malloc").orElseThrow(() -> new AssertionError("malloc not found in default lookup")),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        FREE = linker.downcallHandle(
            libc.find("free").orElseThrow(() -> new AssertionError("free not found in default lookup")),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));
    }

    @Override
    public long allocate(long size) {
        try {
            // malloc returns NULL (0) on failure, matching this method's "0 means failure" contract.
            return (long) MALLOC.invokeExact(size);
        } catch (OutOfMemoryError oom) {
            return 0L;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Override
    public void free(long peer) {
        try {
            FREE.invokeExact(peer);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    @Override
    public long getTotalAllocated() {
        // Not tracked: free(ptr) carries no size, so a faithful total would require a per-address map
        // on the allocation hot path. Mirrors the prior JNA/Unsafe allocators, which also returned -1.
        return -1L;
    }
}
