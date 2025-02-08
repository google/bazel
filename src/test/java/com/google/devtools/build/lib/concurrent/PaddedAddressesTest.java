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

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.createPaddedBaseAddress;
import static com.google.devtools.build.lib.concurrent.PaddedAddresses.getAlignedAddress;

import com.google.devtools.build.lib.unsafe.UnsafeProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import sun.misc.Unsafe;

@RunWith(JUnit4.class)
@SuppressWarnings("SunApi") // TODO: b/359688989 - clean this up
public final class PaddedAddressesTest {

  // TODO: b/386384684 - remove Unsafe usage
  @Test
  public void createdAddresses_areAligned() {
    long address = createPaddedBaseAddress(2);

    long first = getAlignedAddress(address, /* offset= */ 0);
    assertThat(first & (ALIGNMENT - 1)).isEqualTo(0);

    long second = getAlignedAddress(address, /* offset= */ 1);
    assertThat(second - first).isEqualTo(ALIGNMENT);

    UNSAFE.freeMemory(address);
  }

  private static final Unsafe UNSAFE = UnsafeProvider.unsafe();
}
