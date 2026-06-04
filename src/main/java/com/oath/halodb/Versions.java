/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

class Versions {

    // Version 1 widened the key-size field from 1 byte to a 4-byte int in the data, index and
    // tombstone formats to support arbitrary-length keys. Version-0 files are not readable.
    static final int CURRENT_DATA_FILE_VERSION = 1;
    static final int CURRENT_INDEX_FILE_VERSION = 1;
    static final int CURRENT_TOMBSTONE_FILE_VERSION = 1;
    static final int CURRENT_META_FILE_VERSION = 0;
}
