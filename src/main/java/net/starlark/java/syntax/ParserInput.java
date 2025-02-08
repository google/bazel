// Copyright 2014 The Bazel Authors. All rights reserved.
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
package net.starlark.java.syntax;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The apparent name and contents of a source file, for consumption by the parser. The file name
 * appears in the location information in the syntax tree, and in error messages, but the Starlark
 * interpreter will not attempt to open the file. However, the default behavior of {@link
 * EvalException#getMessageWithStack} attempts to read the specified file when formatting a stack
 * trace.
 *
 * <p>The parser consumes a stream of chars (UTF-16 codes), and the syntax positions reported by
 * {@link Node#getStartOffset} and {@link Location.column} are effectively indices into a char
 * array.
 */
public final class ParserInput {

  private final String file;
  private final char[] content;

  private ParserInput(char[] content, String file) {
    this.content = content;
    this.file = Preconditions.checkNotNull(file);
  }

  /** Returns the content of the input source. Callers must not modify the result. */
  char[] getContent() {
    return content;
  }

  /** Returns the apparent file name of the input source. */
  public String getFile() {
    return file;
  }

  /**
   * Returns an input source that uses the name and content of the specified UTF-8-encoded text
   * file.
   */
  public static ParserInput readFile(String file) throws IOException {
    byte[] utf8 = Files.readAllBytes(Paths.get(file));
    return fromUTF8(utf8, file);
  }

  /** Returns an unnamed input source that reads from a list of strings, joined by newlines. */
  public static ParserInput fromLines(String... lines) {
    return fromString(Joiner.on("\n").join(lines), "");
  }

  /**
   * Returns an input source that reads from a UTF-8-encoded byte array. The caller is free to
   * subsequently mutate the array.
   */
  public static ParserInput fromUTF8(byte[] bytes, String file) {
    CharBuffer cb = UTF_8.decode(ByteBuffer.wrap(bytes));
    char[] utf16 = new char[cb.length()];
    cb.get(utf16);
    return fromCharArray(utf16, file);
  }

  /**
   * Returns an input source that reads from a Latin1-encoded byte array. The caller is free to
   * subsequently mutate the array.
   *
   * @deprecated This function exists to support legacy uses of Latin1 in Bazel. Do not use Latin1
   *     in new applications.
   */
  @Deprecated
  public static ParserInput fromLatin1(byte[] bytes, String file) {
    char[] chars = new char[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      chars[i] = (char) (0xff & bytes[i]);
    }
    return new ParserInput(chars, file);
  }

  /** Returns an input source that reads from the given string. */
  public static ParserInput fromString(String content, String file) {
    return fromCharArray(content.toCharArray(), file);
  }

  /**
   * Returns an input source that reads from the given char array. The caller must not subsequently
   * modify the array.
   */
  public static ParserInput fromCharArray(char[] content, String file) {
    return new ParserInput(content, file);
  }
}
