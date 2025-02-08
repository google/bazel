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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import java.lang.reflect.Field;
import java.nio.charset.Charset;

/**
 * Utility functions for reencoding strings between Bazel's internal raw byte encoding and regular
 * Java strings.
 *
 * <p>Bazel needs to support the following two setups:
 *
 * <ul>
 *   <li>File paths, command-line arguments, environment variables, BUILD and .bzl files are all
 *       encoded in UTF-8, on Linux, macOS or Windows.
 *   <li>File paths, command-line arguments, environment variables, BUILD and .bzl files are all
 *       encoded in <i>some</i> consistent encoding, on Linux and with the en_US.ISO-8859-1 locale
 *       available on the host (legacy setup). In particular, this setup allows any byte sequence to
 *       appear in a file path and be referenced in a BUILD file.
 * </ul>
 *
 * <p>Bazel achieves this by forcing an en_US.ISO-8859-1 locale on Unix when available, which due to
 * the byte-based nature of Unix APIs allows all Java (N)IO functions to treat strings as raw byte
 * sequences (a Latin-1 character is equivalent to an unconstrained byte value). On macOS, where the
 * JVM forces UTF-8 encoding for any kind of system interaction, as well as on Windows, where system
 * APIs are all restricted to valid Unicode strings, Bazel has to reencode strings to Unicode before
 * passing them to the JVM (and vice versa). Since BUILD and .bzl files are always read into Latin-1
 * strings (file encodings are not forced by the JVM) and are assumed to be encoded in UTF-8 (unless
 * the Latin-1 locale is available), Bazel has to reencode the strings to UTF-8 so that they match
 * up with the Starlark contents of these files (e.g. file paths mentioned in a BUILD file).
 *
 * <p>While allowing the user a great deal of flexibility, this requires great care when {@link
 * String}s are passed into or out of Bazel via Java standard library functions or external APIs.
 * The following three different types of strings need to be distinguished as if they were different
 * Java types:
 *
 * <ul>
 *   <li>Internal strings: All strings retained by Bazel and used in its inner layers are expected
 *       to be raw byte sequences stored in Latin-1 {@link String}s. With Java's compact string
 *       representation, this means that the Latin-1 bytes are stored directly in the internal byte
 *       array {@link String#value} and the {@link String#coder} is {@link String#LATIN1}.
 *   <li>Unicode strings: Regular Java strings, which are always Unicode. A common example is a
 *       {@code string} field in a protobuf message.
 *   <li>Platform strings: Strings that are passed to or returned from Java (N)IO functions or as
 *       command-line arguments or environment variables to the {@code java} binary at startup or
 *       processes started via {@link java.lang.ProcessBuilder}. These strings are encoded and
 *       decoded by the JVM according to its default native encoding, which is given by the {@code
 *       sun.jnu.encoding} system property. With the current JDK version (21), this is:
 *       <ul>
 *         <li>UTF-8 on macOS;
 *         <li>determined by the active code page on Windows (Cp1252 on US Windows, can be set to
 *             UTF-8 by the user);
 *         <li>determined by the current locale on Linux (forced to en_US.ISO-8859-1 by the client
 *             if available, otherwise usually UTF-8);
 *         <li>determined by the current locale on OpenBSD, which is always UTF-8.
 *       </ul>
 *       As a result, there are two cases to consider:
 *       <ul>
 *         <li>On Linux with a Latin-1 locale, platform strings are identical to internal strings
 *             and Java (N)IO functions can be used to operate with Unix API on a raw byte level.
 *         <li>In all other cases, platform strings are a subset of Unicode strings.
 *       </ul>
 * </ul>
 *
 * <p>The static methods in this class efficiently reencode {@link String}s between these three
 * "types". Crucially, since ASCII strings are encoded identically in ISO-8859-1 and UTF-8, such
 * strings do not need to be reencoded.
 */
public final class StringEncoding {

  static {
    try {
      Field compactStrings = String.class.getDeclaredField("COMPACT_STRINGS");
      compactStrings.setAccessible(true);
      Preconditions.checkState(
          (boolean) compactStrings.get(null), "Bazel requires -XX:-CompactStrings");
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Transforms an internal string into a platform string as efficiently as possible.
   *
   * <p>See the class documentation for more information on the different types of strings.
   */
  public static String internalToPlatform(String s) {
    return needsReencodeForPlatform(s)
        ? new String(STRING_UNSAFE.getInternalStringBytes(s), UTF_8)
        : s;
  }

  /**
   * Transforms a platform string into an internal string as efficiently as possible.
   *
   * <p>See the class documentation for more information on the different types of strings.
   */
  public static String platformToInternal(String s) {
    return needsReencodeForPlatform(s)
        ? STRING_UNSAFE.newInstance(s.getBytes(UTF_8), StringUnsafe.LATIN1)
        : s;
  }

  /**
   * Transforms an internal string into a Unicode string as efficiently as possible.
   *
   * <p>See the class documentation for more information on the different types of strings.
   */
  public static String internalToUnicode(String s) {
    return needsReencodeForUnicode(s)
        ? new String(STRING_UNSAFE.getInternalStringBytes(s), UTF_8)
        : s;
  }

  /**
   * Transforms a Unicode string into an internal string as efficiently as possible.
   *
   * <p>See the class documentation for more information on the different types of strings.
   */
  public static String unicodeToInternal(String s) {
    return needsReencodeForUnicode(s)
        ? STRING_UNSAFE.newInstance(s.getBytes(UTF_8), StringUnsafe.LATIN1)
        : s;
  }

  private static final StringUnsafe STRING_UNSAFE = StringUnsafe.getInstance();

  /**
   * The {@link Charset} with which the JVM encodes any strings passed to or returned from Java
   * (N)IO functions, command-line arguments or environment variables.
   */
  private static final boolean SUN_JNU_ENCODING_IS_ISO_8859_1 =
      Charset.forName(System.getProperty("sun.jnu.encoding")).equals(ISO_8859_1);

  /**
   * This only exists for RemoteWorker, which uses JavaIoFileSystem with Unicode strings and thus
   * shouldn't be subject to any reencoding.
   */
  private static final boolean BAZEL_UNICODE_STRINGS =
      Boolean.getBoolean("bazel.internal.UnicodeStrings");

  private static boolean needsReencodeForPlatform(String s) {
    if (SUN_JNU_ENCODING_IS_ISO_8859_1 && OS.getCurrent() == OS.LINUX) {
      // In this case, platform strings encode raw bytes and are thus identical to internal strings.
      return false;
    }
    // Otherwise, platform strings are a subset of Unicode strings.
    return needsReencodeForUnicode(s);
  }

  private static boolean needsReencodeForUnicode(String s) {
    if (BAZEL_UNICODE_STRINGS) {
      return false;
    }
    return !STRING_UNSAFE.isAscii(s);
  }

  private StringEncoding() {}
}
