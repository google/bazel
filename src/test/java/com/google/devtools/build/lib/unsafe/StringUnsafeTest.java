// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.unsafe;

import static com.google.common.truth.Truth.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link StringUnsafe}. */
@RunWith(JUnit4.class)
public final class StringUnsafeTest {

  @Test
  public void testGetCoder() {
    StringUnsafe stringUnsafe = StringUnsafe.getInstance();
    assertThat(stringUnsafe.getCoder("")).isEqualTo(StringUnsafe.LATIN1);
    assertThat(stringUnsafe.getCoder("hello")).isEqualTo(StringUnsafe.LATIN1);
    assertThat(stringUnsafe.getCoder("lambda λ")).isEqualTo(StringUnsafe.UTF16);
  }

  @Test
  public void testGetBytes() {
    StringUnsafe stringUnsafe = StringUnsafe.getInstance();
    assertThat(ByteBuffer.wrap(stringUnsafe.getByteArray("hello")))
        .isEqualTo(StandardCharsets.ISO_8859_1.encode("hello"));

    if (ByteOrder.nativeOrder().equals(ByteOrder.BIG_ENDIAN)) {
      assertThat(ByteBuffer.wrap(stringUnsafe.getByteArray("lambda λ")))
          .isEqualTo(StandardCharsets.UTF_16BE.encode("lambda λ"));
    } else {
      assertThat(ByteBuffer.wrap(stringUnsafe.getByteArray("lambda λ")))
          .isEqualTo(StandardCharsets.UTF_16LE.encode("lambda λ"));
    }
  }

  @Test
  public void testNewInstance() throws Exception {
    StringUnsafe stringUnsafe = StringUnsafe.getInstance();
    String s = "hello";
    assertThat(stringUnsafe.newInstance(stringUnsafe.getByteArray(s), stringUnsafe.getCoder(s)))
        .isEqualTo("hello");
  }

  @Test
  public void testIsAscii() {
    StringUnsafe stringUnsafe = StringUnsafe.getInstance();
    assertThat(stringUnsafe.isAscii("")).isTrue();
    assertThat(stringUnsafe.isAscii("hello")).isTrue();
    assertThat(stringUnsafe.isAscii("hällo")).isFalse();
    assertThat(stringUnsafe.isAscii("hållo")).isFalse();
    assertThat(stringUnsafe.isAscii("h👋llo")).isFalse();
  }
}
