/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import java.lang.invoke.VarHandle;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Pointer-tagged off-heap ART (fixed-length keys). Each stored child pointer carries the pointee's
 * node type in its low 3 bits (allocations are >=8-aligned), so navigation never loads a node's type
 * byte or branches on a loaded value — the type is known from the tag already in hand. This targets
 * the per-hop dispatch overhead that separates real ART (~235ns) from the dispatch-free proxy
 * (~125ns, which beat the hash).
 *
 * Node types N4/N16/N256 (Node48 dropped); lazy leaves; no path compression / delete yet. Header
 * byte still stores the type code (== tag) for scan/close, which aren't on the hot path.
 *
 * Tagged pointer = address | type, where type in {LEAF=1, N4=2, N16=3, N256=4}; 0 = empty slot.
 */
final class OffHeapART {

    private static final int LEAF = 1, N4 = 2, N16 = 3, N256 = 4;
    private static final long MASK = ~7L;
    private static final long ONES = 0x0101010101010101L;
    private static final long HIGHS = 0x8080808080808080L;

    private final int keyLen;
    private final int valueSize;
    private long root = 0; // tagged pointer, 0 = empty tree
    private long size = 0;
    private volatile boolean closed = false;

    OffHeapART(int keyLen, int valueSize) {
        this.keyLen = keyLen;
        this.valueSize = valueSize;
    }

    private long nodeSize(int type) {
        switch (type) {
            case N4:   return 16 + 4L * 8;
            case N16:  return 24 + 16L * 8;
            case N256: return 8 + 256L * 8;
            case LEAF: return 8 + keyLen + valueSize;
            default: throw new IllegalStateException();
        }
    }

    private long newNode(int type) {
        long s = nodeSize(type);
        long n = Uns.allocate(s, true);
        Uns.setMemory(n, 0, s, (byte) 0);
        Uns.putByte(n, 0, (byte) type);
        return n; // untagged
    }

    private long newLeaf(byte[] key, byte[] value) {
        long n = Uns.allocate(8 + keyLen + valueSize, true);
        Uns.putByte(n, 0, (byte) LEAF);
        Uns.copyMemory(key, 0, n, 8, keyLen);
        Uns.copyMemory(value, 0, n, 8L + keyLen, valueSize);
        return n; // untagged
    }

    private static int hdrType(long node) { return Uns.getByte(node, 0) & 0xFF; }
    private static int count(long n) { return Uns.getInt(n, 4); }
    private static void setCount(long n, int c) { Uns.putInt(n, 4, c); }

    private static long n4Key(int i) { return 8 + i; }
    private static long n4Child(int i) { return 16 + 8L * i; }
    private static long n16Child(int i) { return 24 + 8L * i; }
    private static long n256Child(int b) { return 8 + 8L * b; }

    private boolean leafKeyEquals(long leaf, byte[] key) {
        for (int i = 0; i < keyLen; i++) if (Uns.getByte(leaf, 8 + i) != key[i]) return false;
        return true;
    }

    private byte[] leafKey(long leaf) {
        byte[] k = new byte[keyLen];
        Uns.copyMemory(leaf, 8, k, 0, keyLen);
        return k;
    }

    /** Returns the stored tagged child for byte b, or 0. type is the (already-known) tag of node. */
    private long findChildTagged(long node, int type, int b) {
        switch (type) {
            case N4: {
                int c = count(node);
                for (int i = 0; i < c; i++) if ((Uns.getByte(node, n4Key(i)) & 0xFF) == b) return Uns.getLong(node, n4Child(i));
                return 0;
            }
            case N16: {
                int c = count(node);
                long bc = (b & 0xFFL) * ONES;
                long x0 = Uns.getLong(node, 8) ^ bc;
                long m0 = (x0 - ONES) & ~x0 & HIGHS;
                if (m0 != 0) { int lane = Long.numberOfTrailingZeros(m0) >>> 3; if (lane < c) return Uns.getLong(node, n16Child(lane)); }
                if (c > 8) {
                    long x1 = Uns.getLong(node, 16) ^ bc;
                    long m1 = (x1 - ONES) & ~x1 & HIGHS;
                    if (m1 != 0) { int idx = 8 + (Long.numberOfTrailingZeros(m1) >>> 3); if (idx < c) return Uns.getLong(node, n16Child(idx)); }
                }
                return 0;
            }
            case N256:
                return Uns.getLong(node, n256Child(b));
            default:
                throw new IllegalStateException();
        }
    }

    boolean get(byte[] key, byte[] dst) {
        ensureOpen();
        long cur = root;
        if (cur == 0) return false;
        int depth = 0;
        int t;
        while ((t = (int) (cur & 7)) != LEAF) {
            long node = cur & MASK;
            int b = key[depth] & 0xFF;
            // Inline the Node256 fast path (the common hop for dense trees); method-call only for N4/N16.
            cur = (t == N256) ? Uns.getLong(node, 8 + 8L * b) : findChildTagged(node, t, b);
            if (cur == 0) return false;
            depth++;
        }
        long leaf = cur & MASK;
        if (leafKeyEquals(leaf, key)) {
            Uns.copyMemory(leaf, 8L + keyLen, dst, 0, valueSize);
            return true;
        }
        return false;
    }

    boolean contains(byte[] key) {
        ensureOpen();
        long cur = root;
        if (cur == 0) return false;
        int depth = 0;
        while (((int) (cur & 7)) != LEAF) {
            cur = findChildTagged(cur & MASK, (int) (cur & 7), key[depth] & 0xFF);
            if (cur == 0) return false;
            depth++;
        }
        return leafKeyEquals(cur & MASK, key);
    }

    boolean put(byte[] key, byte[] value) {
        ensureOpen();
        if (root == 0) {
            root = newLeaf(key, value) | LEAF;
            size++;
            return true;
        }
        long parent = 0;
        int parentByte = -1, depth = 0;
        boolean parentIsRoot = true;
        long cur = root;
        while (true) {
            int t = (int) (cur & 7);
            if (t == LEAF) {
                long leaf = cur & MASK;
                if (leafKeyEquals(leaf, key)) { Uns.copyMemory(value, 0, leaf, 8L + keyLen, valueSize); return false; }
                long sub = buildSplit(leaf, leafKey(leaf), key, newLeaf(key, value), depth);
                long tagged = sub | N4;
                if (parentIsRoot) root = tagged; else replaceChild(parent, parentByte, tagged);
                size++;
                return true;
            }
            long node = cur & MASK;
            int b = key[depth] & 0xFF;
            long child = findChildTagged(node, t, b);
            if (child == 0) {
                long grown = addChild(node, t, b, newLeaf(key, value) | LEAF);
                if (grown != node) {
                    long tg = grown | hdrType(grown);
                    if (parentIsRoot) root = tg; else replaceChild(parent, parentByte, tg);
                }
                size++;
                return true;
            }
            parent = node;
            parentByte = b;
            parentIsRoot = false;
            cur = child;
            depth++;
        }
    }

    /**
     * Removes a key. Unlinks its leaf and frees it; does not merge/shrink under-full inner nodes
     * (correct, just not maximally compact — fine for a side index). Frees immediately, which is safe
     * because callers serialize remove with scans (a scan holds the read side of the index lock).
     */
    boolean remove(byte[] key) {
        ensureOpen();
        if (root == 0) return false;
        long parentNode = 0;
        int parentByte = -1;
        long cur = root;
        int depth = 0;
        while (true) {
            int t = (int) (cur & 7);
            if (t == LEAF) {
                long leaf = cur & MASK;
                if (!leafKeyEquals(leaf, key)) return false;
                if (parentNode == 0) root = 0; // leaf was the root
                else removeChild(parentNode, parentByte);
                Uns.free(leaf);
                size--;
                return true;
            }
            long node = cur & MASK;
            int b = key[depth] & 0xFF;
            long child = findChildTagged(node, t, b);
            if (child == 0) return false;
            parentNode = node;
            parentByte = b;
            cur = child;
            depth++;
        }
    }

    private void removeChild(long node, int b) {
        switch (hdrType(node)) {
            case N4: {
                int c = count(node);
                for (int i = 0; i < c; i++) {
                    if ((Uns.getByte(node, n4Key(i)) & 0xFF) == b) {
                        for (int j = i; j < c - 1; j++) {
                            Uns.putByte(node, n4Key(j), Uns.getByte(node, n4Key(j + 1)));
                            Uns.putLong(node, n4Child(j), Uns.getLong(node, n4Child(j + 1)));
                        }
                        setCount(node, c - 1);
                        return;
                    }
                }
                return;
            }
            case N16: {
                int c = count(node);
                for (int i = 0; i < c; i++) {
                    if ((Uns.getByte(node, 8 + i) & 0xFF) == b) {
                        for (int j = i; j < c - 1; j++) {
                            Uns.putByte(node, 8 + j, Uns.getByte(node, 8 + (j + 1)));
                            Uns.putLong(node, n16Child(j), Uns.getLong(node, n16Child(j + 1)));
                        }
                        setCount(node, c - 1);
                        return;
                    }
                }
                return;
            }
            case N256:
                Uns.putLong(node, n256Child(b), 0);
                setCount(node, count(node) - 1);
                return;
        }
    }

    private long buildSplit(long eLeaf, byte[] ek, byte[] nk, long nLeaf, int from) {
        int d = from;
        while (d < keyLen && ek[d] == nk[d]) d++;
        long div = newNode(N4);
        addChild(div, N4, ek[d] & 0xFF, eLeaf | LEAF);
        addChild(div, N4, nk[d] & 0xFF, nLeaf | LEAF);
        long top = div;
        for (int level = d - 1; level >= from; level--) {
            long n = newNode(N4);
            addChild(n, N4, ek[level] & 0xFF, top | N4);
            top = n;
        }
        return top;
    }

    /** Adds taggedChild for byte b; may grow node, returning the (possibly new) untagged node. */
    private long addChild(long node, int type, int b, long taggedChild) {
        switch (type) {
            case N4: {
                int c = count(node);
                if (c < 4) { insertSortedN4(node, b, taggedChild, c); return node; }
                return addChild(growN4toN16(node), N16, b, taggedChild);
            }
            case N16: {
                int c = count(node);
                if (c < 16) { insertSortedN16(node, b, taggedChild, c); return node; }
                return addChild(growN16toN256(node), N256, b, taggedChild);
            }
            case N256: {
                VarHandle.releaseFence();
                Uns.putLong(node, n256Child(b), taggedChild);
                setCount(node, count(node) + 1);
                return node;
            }
            default:
                throw new IllegalStateException();
        }
    }

    private void insertSortedN4(long node, int b, long taggedChild, int c) {
        int pos = 0;
        while (pos < c && (Uns.getByte(node, n4Key(pos)) & 0xFF) < b) pos++;
        for (int i = c; i > pos; i--) {
            Uns.putByte(node, n4Key(i), Uns.getByte(node, n4Key(i - 1)));
            Uns.putLong(node, n4Child(i), Uns.getLong(node, n4Child(i - 1)));
        }
        Uns.putByte(node, n4Key(pos), (byte) b);
        VarHandle.releaseFence();
        Uns.putLong(node, n4Child(pos), taggedChild);
        setCount(node, c + 1);
    }

    private void insertSortedN16(long node, int b, long taggedChild, int c) {
        int pos = 0;
        while (pos < c && (Uns.getByte(node, 8 + pos) & 0xFF) < b) pos++;
        for (int i = c; i > pos; i--) {
            Uns.putByte(node, 8 + i, Uns.getByte(node, 8 + (i - 1)));
            Uns.putLong(node, n16Child(i), Uns.getLong(node, n16Child(i - 1)));
        }
        Uns.putByte(node, 8 + pos, (byte) b);
        VarHandle.releaseFence();
        Uns.putLong(node, n16Child(pos), taggedChild);
        setCount(node, c + 1);
    }

    private long growN4toN16(long node) {
        long m = newNode(N16);
        int c = count(node);
        for (int i = 0; i < c; i++) {
            Uns.putByte(m, 8 + i, Uns.getByte(node, n4Key(i)));
            Uns.putLong(m, n16Child(i), Uns.getLong(node, n4Child(i)));
        }
        setCount(m, c);
        Uns.free(node);
        return m;
    }

    private long growN16toN256(long node) {
        long m = newNode(N256);
        int c = count(node);
        for (int i = 0; i < c; i++) {
            int kb = Uns.getByte(node, 8 + i) & 0xFF;
            Uns.putLong(m, n256Child(kb), Uns.getLong(node, n16Child(i)));
        }
        setCount(m, c);
        Uns.free(node);
        return m;
    }

    private void replaceChild(long node, int b, long taggedChild) {
        int type = hdrType(node);
        switch (type) {
            case N4: {
                int c = count(node);
                for (int i = 0; i < c; i++) if ((Uns.getByte(node, n4Key(i)) & 0xFF) == b) { Uns.putLong(node, n4Child(i), taggedChild); return; }
                break;
            }
            case N16: {
                int c = count(node);
                for (int i = 0; i < c; i++) if ((Uns.getByte(node, 8 + i) & 0xFF) == b) { Uns.putLong(node, n16Child(i), taggedChild); return; }
                break;
            }
            case N256:
                Uns.putLong(node, n256Child(b), taggedChild);
                return;
        }
        throw new IllegalStateException("replaceChild: byte not present");
    }

    long size() { return size; }

    // ---- ordered scan (uses header type; not hot path) ----
    private int nextChildByte(long node, int from) {
        switch (hdrType(node)) {
            case N4: {
                int c = count(node), best = 256;
                for (int i = 0; i < c; i++) { int kb = Uns.getByte(node, n4Key(i)) & 0xFF; if (kb >= from && kb < best) best = kb; }
                return best;
            }
            case N16: {
                int c = count(node), best = 256;
                for (int i = 0; i < c; i++) { int kb = Uns.getByte(node, 8 + i) & 0xFF; if (kb >= from && kb < best) best = kb; }
                return best;
            }
            case N256:
                for (int b = from; b < 256; b++) if (Uns.getLong(node, n256Child(b)) != 0) return b;
                return 256;
            default:
                return 256;
        }
    }

    Iterator<byte[]> scan(byte[] startInclusive, byte[] endExclusive) {
        ensureOpen();
        return new Iterator<>() {
            final long[] sn = new long[keyLen + 2];
            final int[] sc = new int[keyLen + 2];
            int sp = -1;
            boolean started = false, done = false;
            byte[] pending = init();

            private byte[] init() {
                if (root == 0) return null;
                if ((root & 7) == LEAF) { done = true; return accept(leafKey(root & MASK)); }
                sp = 0; sn[0] = root & MASK; sc[0] = 0;
                return advance();
            }

            private byte[] accept(byte[] k) {
                if (startInclusive != null && cmp(k, startInclusive) < 0) return null;
                if (endExclusive != null && cmp(k, endExclusive) >= 0) { done = true; sp = -1; return null; }
                return k;
            }

            private byte[] advance() {
                while (sp >= 0) {
                    long node = sn[sp];
                    int b = nextChildByte(node, sc[sp]);
                    if (b > 255) { sp--; continue; }
                    sc[sp] = b + 1;
                    long child = findChildTagged(node, hdrType(node), b);
                    if ((child & 7) == LEAF) {
                        byte[] k = accept(leafKey(child & MASK));
                        if (k != null) return k;
                        if (done) return null;
                        continue;
                    }
                    sp++; sn[sp] = child & MASK; sc[sp] = 0;
                }
                return null;
            }

            @Override public boolean hasNext() { return pending != null; }

            @Override public byte[] next() {
                if (pending == null) throw new NoSuchElementException();
                byte[] k = pending;
                pending = done ? null : advance();
                return k;
            }
        };
    }

    Iterator<byte[]> prefixScan(byte[] prefix) {
        return scan(prefix, OrderedKeyIndex.prefixUpperBound(prefix));
    }

    private static int cmp(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) { int x = a[i] & 0xFF, y = b[i] & 0xFF; if (x != y) return x - y; }
        return a.length - b.length;
    }

    void close() {
        if (closed) return;
        closed = true;
        if (root != 0) freeRec(root);
    }

    private void freeRec(long tagged) {
        long node = tagged & MASK;
        if ((tagged & 7) != LEAF) {
            int b = nextChildByte(node, 0);
            while (b <= 255) { freeRec(findChildTagged(node, hdrType(node), b)); b = nextChildByte(node, b + 1); }
        }
        Uns.free(node);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("OffHeapART is closed");
    }
}
