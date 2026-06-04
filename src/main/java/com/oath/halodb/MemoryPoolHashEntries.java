/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

class MemoryPoolHashEntries {

    /*
     * Every slot begins with a 5-byte "next" pointer (chunk index + chunk offset).
     *
     * A HEAD slot (the first slot of an entry) then holds:
     *   chunk index  - 1 byte.
     *   chunk offset - 4 byte.
     *   key length   - 4 byte (int) — supports arbitrary-length keys.
     *   inline key   - up to fixedKeyLength bytes (the first fixedKeyLength bytes of the key).
     *   value        - fixedValueLength bytes.
     *
     * If the key is longer than fixedKeyLength, the remaining key bytes overflow into additional
     * OVERFLOW slots chained via the same "next" pointer. An overflow slot reuses everything after
     * its 5-byte next pointer as a key fragment:
     *   chunk index  - 1 byte.
     *   chunk offset - 4 byte.
     *   key fragment - up to (slotSize - 5) bytes.
     */
    static final int HEADER_SIZE = 1 + 4 + 4;

    static final int ENTRY_OFF_NEXT_CHUNK_INDEX = 0;
    static final int ENTRY_OFF_NEXT_CHUNK_OFFSET = 1;

    // offset of key length in a head slot (4 bytes, int)
    static final int ENTRY_OFF_KEY_LENGTH = 5;

    // offset of inline key/value data in a head slot
    static final int ENTRY_OFF_DATA = 9;

    // offset of the key fragment in an overflow slot (everything after the next pointer)
    static final int ENTRY_OFF_FRAGMENT = 5;

}
