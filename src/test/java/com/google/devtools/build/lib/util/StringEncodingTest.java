// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.devtools.build.lib.util.StringEncoding.internalToPlatform;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static com.google.devtools.build.lib.util.StringEncoding.platformToInternal;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public class StringEncodingTest {

  public static final Charset SUN_JNU_ENCODING =
      Charset.forName(System.getProperty("sun.jnu.encoding"));

  @Test
  public void testUnicodeToInternal() {
    assertThat(unicodeToInternal("")).isSameInstanceAs("");
    assertThat(unicodeToInternal("hello")).isSameInstanceAs("hello");
    assertThat(unicodeToInternal("hällo"))
        .isEqualTo(new String("hällo".getBytes(UTF_8), ISO_8859_1));
    assertThat(unicodeToInternal("hållo"))
        .isEqualTo(new String("hållo".getBytes(UTF_8), ISO_8859_1));
    assertThat(unicodeToInternal("h👋llo"))
        .isEqualTo(new String("h👋llo".getBytes(UTF_8), ISO_8859_1));
  }

  @Test
  public void testInternalToUnicode() {
    assertThat(internalToUnicode("")).isSameInstanceAs("");
    assertThat(internalToUnicode("hello")).isSameInstanceAs("hello");
    assertThat(internalToUnicode(new String("hällo".getBytes(UTF_8), ISO_8859_1)))
        .isEqualTo("hällo");
    assertThat(internalToUnicode(new String("hållo".getBytes(UTF_8), ISO_8859_1)))
        .isEqualTo("hållo");
    assertThat(internalToUnicode(new String("h👋llo".getBytes(UTF_8), ISO_8859_1)))
        .isEqualTo("h👋llo");
  }

  @Test
  public void testPlatformToInternal() {
    if (SUN_JNU_ENCODING.equals(ISO_8859_1) && OS.getCurrent() == OS.LINUX) {
      assertThat(platformToInternal("")).isSameInstanceAs("");
      assertThat(platformToInternal("hello")).isSameInstanceAs("hello");
      {
        String s = new String("hällo".getBytes(UTF_8), ISO_8859_1);
        assertThat(platformToInternal(s)).isSameInstanceAs(s);
      }
      {
        String s = new String("hållo".getBytes(UTF_8), ISO_8859_1);
        assertThat(platformToInternal(s)).isSameInstanceAs(s);
      }
      {
        String s = new String("h👋llo".getBytes(UTF_8), ISO_8859_1);
        assertThat(platformToInternal(s)).isSameInstanceAs(s);
      }
      {
        // Not valid Unicode.
        String s = new String(new byte[] {(byte) 0xFF, (byte) 0xFE, 0X01}, ISO_8859_1);
        assertThat(platformToInternal(s)).isSameInstanceAs(s);
      }
    } else {
      assertThat(platformToInternal("")).isSameInstanceAs("");
      assertThat(platformToInternal("hello")).isSameInstanceAs("hello");
      assertThat(platformToInternal("hällo"))
          .isEqualTo(new String("hällo".getBytes(UTF_8), ISO_8859_1));
      assertThat(platformToInternal("hållo"))
          .isEqualTo(new String("hållo".getBytes(UTF_8), ISO_8859_1));
      assertThat(platformToInternal("h👋llo"))
          .isEqualTo(new String("h👋llo".getBytes(UTF_8), ISO_8859_1));
    }
  }

  @Test
  public void testInternalToPlatform() {
    if (SUN_JNU_ENCODING.equals(ISO_8859_1) && OS.getCurrent() == OS.LINUX) {
      assertThat(internalToPlatform("")).isSameInstanceAs("");
      assertThat(internalToPlatform("hello")).isSameInstanceAs("hello");
      {
        String s = new String("hällo".getBytes(UTF_8), ISO_8859_1);
        assertThat(internalToPlatform(s)).isSameInstanceAs(s);
      }
      {
        String s = new String("hållo".getBytes(UTF_8), ISO_8859_1);
        assertThat(internalToPlatform(s)).isSameInstanceAs(s);
      }
      {
        String s = new String("h👋llo".getBytes(UTF_8), ISO_8859_1);
        assertThat(internalToPlatform(s)).isSameInstanceAs(s);
      }
      {
        // Not valid Unicode.
        String s = new String(new byte[] {(byte) 0xFF, (byte) 0xFE, 0X01}, ISO_8859_1);
        assertThat(internalToPlatform(s)).isSameInstanceAs(s);
      }
    } else {
      assertThat(internalToPlatform("")).isSameInstanceAs("");
      assertThat(internalToPlatform("hello")).isSameInstanceAs("hello");
      assertThat(internalToPlatform(new String("hällo".getBytes(UTF_8), ISO_8859_1)))
          .isEqualTo("hällo");
      assertThat(internalToPlatform(new String("hållo".getBytes(UTF_8), ISO_8859_1)))
          .isEqualTo("hållo");
      assertThat(internalToPlatform(new String("h👋llo".getBytes(UTF_8), ISO_8859_1)))
          .isEqualTo("h👋llo");
    }
  }

  @Test
  public void testPlatformToInternal_roundtrip(
      @TestParameter({"ascii", "äöüÄÖÜß", "🌱", "羅勒罗勒学名"}) String s) {
    assume().that(canEncode(s, SUN_JNU_ENCODING)).isTrue();

    String internal = platformToInternal(s);
    // In the internal encoding, raw bytes are encoded as Latin-1.
    assertThat(StringUnsafe.getInstance().getCoder(internal)).isEqualTo(StringUnsafe.LATIN1);
    String roundtripped = internalToPlatform(internal);
    if (StringUnsafe.getInstance().isAscii(s)) {
      assertThat(roundtripped).isSameInstanceAs(s);
    } else {
      assertThat(roundtripped).isEqualTo(s);
    }
  }

  @Test
  public void testPlatformToInternal_rawBytesRoundtrip() {
    // Not valid UTF-8
    byte[] rawBytes = new byte[] {0x00, 0x7F, (byte) 0x80, (byte) 0xFE, (byte) 0xFF};
    assertThat(canDecode(rawBytes, UTF_8)).isFalse();

    // Roundtripping raw bytes through the internal encoding requires Linux and a Latin-1 locale.
    assume().that(OS.getCurrent()).isEqualTo(OS.LINUX);
    assume().that(SUN_JNU_ENCODING).isEqualTo(ISO_8859_1);

    String platform = new String(rawBytes, ISO_8859_1);
    String internal = platformToInternal(platform);
    assertThat(internal).isSameInstanceAs(platform);
    String roundtripped = internalToPlatform(internal);
    assertThat(roundtripped).isSameInstanceAs(internal);
  }

  @Test
  public void testUnicodeToInternal_roundtrip(
      @TestParameter({"ascii", "äöüÄÖÜß", "🌱", "羅勒罗勒学名"}) String s) {
    String internal = unicodeToInternal(s);
    // In the internal encoding, raw bytes are encoded as Latin-1.
    assertThat(StringUnsafe.getInstance().getCoder(internal)).isEqualTo(StringUnsafe.LATIN1);
    String roundtripped = internalToUnicode(internal);
    if (StringUnsafe.getInstance().isAscii(s)) {
      assertThat(roundtripped).isSameInstanceAs(s);
    } else {
      assertThat(roundtripped).isEqualTo(s);
    }
  }

  private static boolean canEncode(String s, Charset charset) {
    try {
      charset.newEncoder().encode(CharBuffer.wrap(s));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  private static boolean canDecode(byte[] bytes, Charset charset) {
    try {
      charset.newDecoder().decode(ByteBuffer.wrap(bytes));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }
}
