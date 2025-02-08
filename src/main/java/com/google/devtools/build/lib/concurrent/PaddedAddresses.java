// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.concurrent;

import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.annotations.VisibleForTesting;

import sun.misc.Unsafe;

/** This class encapsulates creating padded addresses for 8-byte values. */
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
final class PaddedAddresses {
  /**
   * The target alignment bytes.
   *
   * <p>On x86, the cache line size is 64-bytes, but spatial prefetching may fetch two lines at a
   * time so interference is observable over a range of 128-bytes.
   */
  @VisibleForTesting static final long ALIGNMENT = 128;

  /** The amount of padding needed between 8-byte objects to avoid interference. */
  private static final long PADDING_WIDTH = ALIGNMENT - 8;

  private PaddedAddresses() {}

  /**
   * Creates a base address for the specified number of entries.
   *
   * <p>The caller must call {@link Unsafe#freeMemory} on the returned address. Use of {@link
   * AddressFreer} could be helpful.
   *
   * @param count allocates a buffer large enough to accommodate this many aligned blocks.
   */
  // TODO: b/386384684 - remove Unsafe usage
  static long createPaddedBaseAddress(int count) {
    return unsafe().allocateMemory(count * ALIGNMENT + PADDING_WIDTH);
  }

  /**
   * Obtains an {@link #ALIGNMENT} aligned address from an 8-byte aligned base address and offset.
   *
   * @param baseAddress an address obtained from {@link #createPaddedBaseAddress}.
   * @param offset an offset less than the {@code count} parameter provided when creating the
   *     address.
   */
  static long getAlignedAddress(long baseAddress, int offset) {
    long cacheLineAddress = (baseAddress + PADDING_WIDTH) & ~(ALIGNMENT - 1);
    return cacheLineAddress + offset * ALIGNMENT;
  }
}
