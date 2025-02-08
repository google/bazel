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

package net.starlark.java.eval;

import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;

/**
 * Starlark String module.
 *
 * <p>This module has special treatment in Starlark, as its methods represent methods present for
 * any 'string' objects in the language.
 *
 * <p>Methods of this class annotated with {@link StarlarkMethod} must have a positional-only
 * 'String self' parameter as the first parameter of the method.
 */
@StarlarkBuiltin(
    name = "string",
    category = "core",
    doc =
        "A language built-in type to support strings. "
            + "Examples of string literals:<br>"
            + "<pre class=\"language-python\">a = 'abc\\ndef'\n"
            + "b = \"ab'cd\"\n"
            + "c = \"\"\"multiline string\"\"\"\n"
            + "\n"
            + "# Strings support slicing (negative index starts from the end):\n"
            + "x = \"hello\"[2:4]  # \"ll\"\n"
            + "y = \"hello\"[1:-1]  # \"ell\"\n"
            + "z = \"hello\"[:4]  # \"hell\"\n"
            + "# Slice steps can be used, too:\n"
            + "s = \"hello\"[::2] # \"hlo\"\n"
            + "t = \"hello\"[3:0:-1] # \"lle\"\n</pre>"
            + "Strings are not directly iterable, use the <code>.elems()</code> "
            + "method to iterate over their characters. Examples:<br>"
            + "<pre class=\"language-python\">\"bc\" in \"abcd\"   # evaluates to True\n"
            + "x = [c for c in \"abc\".elems()]  # x == [\"a\", \"b\", \"c\"]</pre>\n"
            + "Implicit concatenation of strings is not allowed; use the <code>+</code> "
            + "operator instead. Comparison operators perform a lexicographical comparison; "
            + "use <code>==</code> to test for equality.")
final class StringModule implements StarlarkValue {

  static final StringModule INSTANCE = new StringModule();

  private StringModule() {}

  // Returns s[start:stop:step], as if by Sequence.getSlice.
  static String slice(String s, int start, int stop, int step) throws EvalException {
    RangeList indices = new RangeList(start, stop, step);
    int n = indices.size();
    if (n == 0) {
      return "";
    } else if (n == 1) {
      return memoizedCharToString(s.charAt(indices.at(0)));
    } else if (step == 1) { // common case
      return s.substring(indices.at(0), indices.at(n));
    } else {
      char[] res = new char[n];
      for (int i = 0; i < n; ++i) {
        res[i] = s.charAt(indices.at(i));
      }
      return new String(res);
    }
  }

  // Nearly all chars in Starlark strings are ASCII.
  // This is a cache of single-char strings to avoid allocation in the s[i] operation.
  private static final String[] ASCII_CHAR_STRINGS = initCharStrings();

  private static String[] initCharStrings() {
    String[] a = new String[0x80];
    for (int i = 0; i < a.length; ++i) {
      a[i] = String.valueOf((char) i);
    }
    return a;
  }

  /** Semantically equivalent to {@link String#valueOf(char)} but faster for ASCII strings. */
  static String memoizedCharToString(char c) {
    if (c < ASCII_CHAR_STRINGS.length) {
      return ASCII_CHAR_STRINGS[c];
    } else {
      return String.valueOf(c);
    }
  }

  // Returns the substring denoted by str[start:end], which is never out of bounds.
  // For speed, we don't return str.substring(start, end), as substring allocates a copy.
  // Instead we return the (start, end) indices, packed into the lo/hi arms of a long.
  private static long substringIndices(String str, Object start, Object end) throws EvalException {
    // This function duplicates the logic of Starlark.slice for strings.
    int n = str.length();
    int istart = 0;
    if (start != Starlark.NONE) {
      istart = EvalUtils.toIndex(Starlark.toInt(start, "start"), n);
    }
    int iend = n;
    if (end != Starlark.NONE) {
      iend = EvalUtils.toIndex(Starlark.toInt(end, "end"), n);
    }
    if (iend < istart) {
      iend = istart; // => empty result
    }
    return pack(istart, iend); // = str.substring(start, end)
  }

  private static long pack(int lo, int hi) {
    return (((long) hi) << 32) | (lo & 0xffffffffL);
  }

  private static int lo(long x) {
    return (int) x;
  }

  private static int hi(long x) {
    return (int) (x >>> 32);
  }

  @StarlarkMethod(
      name = "join",
      doc =
          "Returns a string in which the string elements of the argument have been "
              + "joined by this string as a separator. Example:<br>"
              + "<pre class=\"language-python\">\"|\".join([\"a\", \"b\", \"c\"]) == \"a|b|c\""
              + "</pre>",
      parameters = {@Param(name = "self"), @Param(name = "elements", doc = "The objects to join.")},
      useStarlarkThread = true)
  public String join(String self, Object elements, StarlarkThread thread) throws EvalException {
    Iterable<?> items = Starlark.toIterable(elements);
    int i = 0;
    for (Object item : items) {
      if (!(item instanceof String)) {
        throw Starlark.errorf(
            "expected string for sequence element %d, got '%s' of type %s",
            i, Starlark.str(item, thread.getSemantics()), Starlark.type(item));
      }
      i++;
    }
    return Joiner.on(self).join(items);
  }

  @StarlarkMethod(
      name = "lower",
      doc = "Returns the lower case version of this string.",
      parameters = {@Param(name = "self")})
  public String lower(String self) {
    return Ascii.toLowerCase(self);
  }

  @StarlarkMethod(
      name = "upper",
      doc = "Returns the upper case version of this string.",
      parameters = {@Param(name = "self")})
  public String upper(String self) {
    return Ascii.toUpperCase(self);
  }

  /**
   * For consistency with Python we recognize the same whitespace characters as they do over the
   * range 0x00-0xFF. See https://hg.python.org/cpython/file/3.6/Objects/unicodetype_db.h#l5738 This
   * list is a consequence of Unicode character information.
   *
   * <p>Note that this differs from Python 2.7, which uses ctype.h#isspace(), and from
   * java.lang.Character#isWhitespace(), which does not recognize U+00A0.
   */
  // TODO(https://github.com/bazelbuild/starlark/issues/112): use the Unicode definition of
  // whitespace, matching Python 3.
  private static final CharMatcher LATIN1_WHITESPACE =
      CharMatcher.anyOf(
          "\u0009" + "\n" + "\u000B" + "\u000C" + "\r" + "\u001C" + "\u001D" + "\u001E" + "\u001F "
              + "\u0085" + "\u00A0");

  private static String stringLStrip(String self, CharMatcher matcher) {
    for (int i = 0; i < self.length(); i++) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(i);
      }
    }
    return ""; // All characters were stripped.
  }

  private static String stringRStrip(String self, CharMatcher matcher) {
    for (int i = self.length() - 1; i >= 0; i--) {
      if (!matcher.matches(self.charAt(i))) {
        return self.substring(0, i + 1);
      }
    }
    return ""; // All characters were stripped.
  }

  private static String stringStrip(String self, CharMatcher matcher) {
    return stringLStrip(stringRStrip(self, matcher), matcher);
  }

  @StarlarkMethod(
      name = "lstrip",
      doc =
          "Returns a copy of the string where leading characters that appear in "
              + "<code>chars</code> are removed. Note that <code>chars</code> "
              + "is not a prefix: all combinations of its value are removed:"
              + "<pre class=\"language-python\">"
              + "\"abcba\".lstrip(\"ba\") == \"cba\""
              + "</pre>",
      parameters = {
        @Param(name = "self"),
        @Param(
            name = "chars",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None")
      })
  public String lstrip(String self, Object charsOrNone) {
    CharMatcher matcher =
        charsOrNone != Starlark.NONE ? CharMatcher.anyOf((String) charsOrNone) : LATIN1_WHITESPACE;
    return stringLStrip(self, matcher);
  }

  @StarlarkMethod(
      name = "rstrip",
      doc =
          "Returns a copy of the string where trailing characters that appear in "
              + "<code>chars</code> are removed. Note that <code>chars</code> "
              + "is not a suffix: all combinations of its value are removed:"
              + "<pre class=\"language-python\">"
              + "\"abcbaa\".rstrip(\"ab\") == \"abc\""
              + "</pre>",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(
            name = "chars",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None")
      })
  public String rstrip(String self, Object charsOrNone) {
    CharMatcher matcher =
        charsOrNone != Starlark.NONE ? CharMatcher.anyOf((String) charsOrNone) : LATIN1_WHITESPACE;
    return stringRStrip(self, matcher);
  }

  @StarlarkMethod(
      name = "strip",
      doc =
          "Returns a copy of the string where leading or trailing characters that appear in "
              + "<code>chars</code> are removed. Note that <code>chars</code> "
              + "is neither a prefix nor a suffix: all combinations of its value "
              + "are removed:"
              + "<pre class=\"language-python\">"
              + "\"aabcbcbaa\".strip(\"ab\") == \"cbc\""
              + "</pre>",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(
            name = "chars",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            doc = "The characters to remove, or all whitespace if None.",
            defaultValue = "None")
      })
  public String strip(String self, Object charsOrNone) {
    CharMatcher matcher =
        charsOrNone != Starlark.NONE ? CharMatcher.anyOf((String) charsOrNone) : LATIN1_WHITESPACE;
    return stringStrip(self, matcher);
  }

  @StarlarkMethod(
      name = "replace",
      doc =
          "Returns a copy of the string in which the occurrences "
              + "of <code>old</code> have been replaced with <code>new</code>, optionally "
              + "restricting the number of replacements to <code>count</code>.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "old", doc = "The string to be replaced."),
        @Param(name = "new", doc = "The string to replace with."),
        @Param(
            name = "count",
            defaultValue = "-1",
            doc =
                "The maximum number of replacements. If omitted, or if the value is negative, "
                    + "there is no limit.")
      },
      useStarlarkThread = true)
  public String replace(
      String self, String oldString, String newString, StarlarkInt countI, StarlarkThread thread)
      throws EvalException {
    int count = countI.toInt("count");
    if (count < 0) {
      count = Integer.MAX_VALUE;
    }

    StringBuilder sb = new StringBuilder();
    int start = 0;
    for (int i = 0; i < count; i++) {
      if (oldString.isEmpty()) {
        sb.append(newString);
        if (start < self.length()) {
          sb.append(self.charAt(start++));
        } else {
          break;
        }
      } else {
        int end = self.indexOf(oldString, start);
        if (end < 0) {
          break;
        }
        sb.append(self, start, end).append(newString);
        start = end + oldString.length();
      }
    }
    sb.append(self, start, self.length());
    return sb.toString();
  }

  @StarlarkMethod(
      name = "split",
      doc =
          "Returns a list of all the words in the string, using <code>sep</code> as the "
              + "separator, optionally limiting the number of splits to <code>maxsplit</code>.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sep", doc = "The string to split on."),
        @Param(
            name = "maxsplit",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The maximum number of splits.")
      },
      useStarlarkThread = true)
  public StarlarkList<String> split(
      String self, String sep, Object maxSplitO, StarlarkThread thread) throws EvalException {
    if (sep.isEmpty()) {
      throw Starlark.errorf("Empty separator");
    }
    int maxSplit = Integer.MAX_VALUE;
    if (maxSplitO != Starlark.NONE) {
      maxSplit = Starlark.toInt(maxSplitO, "maxsplit");
    }
    StarlarkList<String> res = StarlarkList.newList(thread.mutability());
    int start = 0;
    while (true) {
      int end = self.indexOf(sep, start);
      if (end < 0 || maxSplit-- == 0) {
        res.addElement(self.substring(start));
        break;
      }
      res.addElement(self.substring(start, end));
      start = end + sep.length();
    }
    return res;
  }

  @StarlarkMethod(
      name = "rsplit",
      doc =
          "Returns a list of all the words in the string, using <code>sep</code> as the "
              + "separator, optionally limiting the number of splits to <code>maxsplit</code>. "
              + "Except for splitting from the right, this method behaves like split().",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sep", doc = "The string to split on."),
        @Param(
            name = "maxsplit",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "The maximum number of splits.")
      },
      useStarlarkThread = true)
  public StarlarkList<String> rsplit(
      String self, String sep, Object maxSplitO, StarlarkThread thread) throws EvalException {
    if (sep.isEmpty()) {
      throw Starlark.errorf("Empty separator");
    }
    int maxSplit = Integer.MAX_VALUE;
    if (maxSplitO != Starlark.NONE) {
      maxSplit = Starlark.toInt(maxSplitO, "maxsplit");
    }
    ArrayList<String> res = new ArrayList<>();
    int end = self.length();
    while (true) {
      int start = self.lastIndexOf(sep, end - 1);
      if (start < 0 || maxSplit-- == 0) {
        res.add(self.substring(0, end));
        break;
      }
      res.add(self.substring(start + sep.length(), end));
      end = start;
    }
    Collections.reverse(res);
    return StarlarkList.copyOf(thread.mutability(), res);
  }

  @StarlarkMethod(
      name = "partition",
      doc =
          "Splits the input string at the first occurrence of the separator <code>sep</code> and"
              + " returns the resulting partition as a three-element tuple of the form (before,"
              + " separator, after). If the input string does not contain the separator, partition"
              + " returns (self, '', '').",
      parameters = {@Param(name = "self"), @Param(name = "sep", doc = "The string to split on.")})
  public Tuple partition(String self, String sep) throws EvalException {
    return partitionCommon(self, sep, /*first=*/ true);
  }

  @StarlarkMethod(
      name = "rpartition",
      doc =
          "Splits the input string at the last occurrence of the separator <code>sep</code> and"
              + " returns the resulting partition as a three-element tuple of the form (before,"
              + " separator, after). If the input string does not contain the separator,"
              + " rpartition returns ('', '', self).",
      parameters = {@Param(name = "self"), @Param(name = "sep", doc = "The string to split on.")})
  public Tuple rpartition(String self, String sep) throws EvalException {
    return partitionCommon(self, sep, /*first=*/ false);
  }

  // Splits input at the first or last occurrence of the given separator,
  // and returns a triple of substrings (before, separator, after).
  // If the input does not contain the separator,
  // it returns (input, "", "") if first, or ("", "", input), if !first.
  private static Tuple partitionCommon(String input, String separator, boolean first)
      throws EvalException {
    if (separator.isEmpty()) {
      throw Starlark.errorf("empty separator");
    }

    String a = "";
    String b = "";
    String c = "";

    int pos = first ? input.indexOf(separator) : input.lastIndexOf(separator);
    if (pos < 0) {
      if (first) {
        a = input;
      } else {
        c = input;
      }
    } else {
      a = input.substring(0, pos);
      b = separator;
      c = input.substring(pos + separator.length());
    }

    return Tuple.triple(a, b, c);
  }

  @StarlarkMethod(
      name = "capitalize",
      doc =
          "Returns a copy of the string with its first character (if any) capitalized and the rest "
              + "lowercased. This method does not support non-ascii characters. ",
      parameters = {@Param(name = "self", doc = "This string.")})
  public String capitalize(String self) throws EvalException {
    if (self.isEmpty()) {
      return self;
    }
    // TODO(adonovan): fix: support non-ASCII characters. Requires that Bazel stop abusing Latin1.
    return Character.toUpperCase(self.charAt(0)) + Ascii.toLowerCase(self.substring(1));
  }

  @StarlarkMethod(
      name = "title",
      doc =
          "Converts the input string into title case, i.e. every word starts with an "
              + "uppercase letter while the remaining letters are lowercase. In this "
              + "context, a word means strictly a sequence of letters. This method does "
              + "not support supplementary Unicode characters.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public String title(String self) throws EvalException {
    char[] data = self.toCharArray();
    boolean previousWasLetter = false;

    for (int pos = 0; pos < data.length; ++pos) {
      char current = data[pos];
      boolean currentIsLetter = Character.isLetter(current);

      if (currentIsLetter) {
        if (previousWasLetter && Character.isUpperCase(current)) {
          data[pos] = Character.toLowerCase(current);
        } else if (!previousWasLetter && Character.isLowerCase(current)) {
          data[pos] = Character.toUpperCase(current);
        }
      }
      previousWasLetter = currentIsLetter;
    }

    return new String(data);
  }

  /**
   * Common implementation for find, rfind, index, rindex.
   *
   * @param forward true if we want to return the last matching index.
   */
  private static int stringFind(boolean forward, String self, String sub, Object start, Object end)
      throws EvalException {
    long indices = substringIndices(self, start, end);
    int startpos = lo(indices);
    int endpos = hi(indices);
    if (forward) {
      return self.indexOf(sub, startpos, endpos);
    }
    // String#lastIndexOf can't be used to implement rfind() because it only
    // confines the start position of the substring, not the entire substring.
    int subpos = self.substring(startpos, endpos).lastIndexOf(sub);
    return subpos < 0
        ? subpos //
        : subpos + startpos;
  }

  private static final Pattern SPLIT_LINES_PATTERN =
      Pattern.compile("(?<line>.*)(?<break>(\\r\\n|\\r|\\n)?)");

  @StarlarkMethod(
      name = "rfind",
      doc =
          "Returns the last index where <code>sub</code> is found, or -1 if no such index exists, "
              + "optionally restricting to <code>[start:end]</code>, "
              + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sub", doc = "The substring to find."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position before which to restrict to search.")
      })
  public int rfind(String self, String sub, Object start, Object end) throws EvalException {
    return stringFind(false, self, sub, start, end);
  }

  @StarlarkMethod(
      name = "find",
      doc =
          "Returns the first index where <code>sub</code> is found, or -1 if no such index exists, "
              + "optionally restricting to <code>[start:end]</code>, "
              + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sub", doc = "The substring to find."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position before which to restrict to search.")
      })
  public int find(String self, String sub, Object start, Object end) throws EvalException {
    return stringFind(true, self, sub, start, end);
  }

  @StarlarkMethod(
      name = "rindex",
      doc =
          "Returns the last index where <code>sub</code> is found, or raises an error if no such "
              + "index exists, optionally restricting to <code>[start:end]</code>, "
              + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sub", doc = "The substring to find."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position before which to restrict to search.")
      })
  public int rindex(String self, String sub, Object start, Object end) throws EvalException {
    int res = stringFind(false, self, sub, start, end);
    if (res < 0) {
      throw Starlark.errorf("substring not found");
    }
    return res;
  }

  @StarlarkMethod(
      name = "index",
      doc =
          "Returns the first index where <code>sub</code> is found, or raises an error if no such "
              + " index exists, optionally restricting to <code>[start:end]</code>"
              + "<code>start</code> being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sub", doc = "The substring to find."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position before which to restrict to search.")
      })
  public int index(String self, String sub, Object start, Object end) throws EvalException {
    int res = stringFind(true, self, sub, start, end);
    if (res < 0) {
      throw Starlark.errorf("substring not found");
    }
    return res;
  }

  @StarlarkMethod(
      name = "splitlines",
      doc =
          "Splits the string at line boundaries ('\\n', '\\r\\n', '\\r') "
              + "and returns the result as a new mutable list.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(
            // TODO(b/67740837): clarify whether this is named or positional.
            name = "keepends",
            defaultValue = "False",
            doc = "Whether the line breaks should be included in the resulting list.")
      },
      useStarlarkThread = true)
  public Sequence<String> splitLines(String self, boolean keepEnds, StarlarkThread thread)
      throws EvalException {
    StarlarkList<String> result = StarlarkList.newList(thread.mutability());
    Matcher matcher = SPLIT_LINES_PATTERN.matcher(self);
    while (matcher.find()) {
      String line = matcher.group("line");
      String lineBreak = matcher.group("break");
      boolean trailingBreak = lineBreak.isEmpty();
      if (line.isEmpty() && trailingBreak) {
        break;
      }
      if (keepEnds && !trailingBreak) {
        result.addElement(line + lineBreak);
      } else {
        result.addElement(line);
      }
    }
    // TODO(adonovan): spec should state that result is mutable,
    // as in Python[23] and go.starlark.net.
    return result;
  }

  @StarlarkMethod(
      name = "isalpha",
      doc =
          "Returns True if all characters in the string are alphabetic ([a-zA-Z]) and there is "
              + "at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isAlpha(String self) throws EvalException {
    return matches(self, ALPHA, false);
  }

  @StarlarkMethod(
      name = "isalnum",
      doc =
          "Returns True if all characters in the string are alphanumeric ([a-zA-Z0-9]) and there "
              + "is at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isAlnum(String self) throws EvalException {
    return matches(self, ALNUM, false);
  }

  @StarlarkMethod(
      name = "isdigit",
      doc =
          "Returns True if all characters in the string are digits ([0-9]) and there is "
              + "at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isDigit(String self) throws EvalException {
    return matches(self, DIGIT, false);
  }

  @StarlarkMethod(
      name = "isspace",
      doc =
          "Returns True if all characters are white space characters and the string "
              + "contains at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isSpace(String self) throws EvalException {
    return matches(self, SPACE, false);
  }

  @StarlarkMethod(
      name = "islower",
      doc =
          "Returns True if all cased characters in the string are lowercase and there is "
              + "at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isLower(String self) throws EvalException {
    // Python also accepts non-cased characters, so we cannot use LOWER.
    return matches(self, UPPER.negate(), true);
  }

  @StarlarkMethod(
      name = "isupper",
      doc =
          "Returns True if all cased characters in the string are uppercase and there is "
              + "at least one character.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isUpper(String self) throws EvalException {
    // Python also accepts non-cased characters, so we cannot use UPPER.
    return matches(self, LOWER.negate(), true);
  }

  @StarlarkMethod(
      name = "istitle",
      doc =
          "Returns True if the string is in title case and it contains at least one character. "
              + "This means that every uppercase character must follow an uncased one (e.g. "
              + "whitespace) and every lowercase character must follow a cased one (e.g. "
              + "uppercase or lowercase).",
      parameters = {@Param(name = "self", doc = "This string.")})
  public boolean isTitle(String self) throws EvalException {
    if (self.isEmpty()) {
      return false;
    }
    // From the Python documentation: "uppercase characters may only follow uncased characters
    // and lowercase characters only cased ones".
    char[] data = self.toCharArray();
    CharMatcher matcher = CharMatcher.any();
    char leftMostCased = ' ';
    for (int pos = data.length - 1; pos >= 0; --pos) {
      char current = data[pos];
      // 1. Check condition that was determined by the right neighbor.
      if (!matcher.matches(current)) {
        return false;
      }
      // 2. Determine condition for the left neighbor.
      if (LOWER.matches(current)) {
        matcher = CASED;
      } else if (UPPER.matches(current)) {
        matcher = CASED.negate();
      } else {
        matcher = CharMatcher.any();
      }
      // 3. Store character if it is cased.
      if (CASED.matches(current)) {
        leftMostCased = current;
      }
    }
    // The leftmost cased letter must be uppercase. If leftMostCased is not a cased letter here,
    // then the string doesn't have any cased letter, so UPPER.test will return false.
    return UPPER.matches(leftMostCased);
  }

  private static boolean matches(
      String str, CharMatcher matcher, boolean requiresAtLeastOneCasedLetter) {
    if (str.isEmpty()) {
      return false;
    } else if (!requiresAtLeastOneCasedLetter) {
      return matcher.matchesAllOf(str);
    }
    int casedLetters = 0;
    for (char current : str.toCharArray()) {
      if (!matcher.matches(current)) {
        return false;
      } else if (requiresAtLeastOneCasedLetter && CASED.matches(current)) {
        ++casedLetters;
      }
    }
    return casedLetters > 0;
  }

  private static final CharMatcher DIGIT = CharMatcher.javaDigit();
  private static final CharMatcher LOWER = CharMatcher.inRange('a', 'z');
  private static final CharMatcher UPPER = CharMatcher.inRange('A', 'Z');
  private static final CharMatcher ALPHA = LOWER.or(UPPER);
  private static final CharMatcher ALNUM = ALPHA.or(DIGIT);
  private static final CharMatcher CASED = ALPHA;
  private static final CharMatcher SPACE = CharMatcher.whitespace();

  @StarlarkMethod(
      name = "count",
      doc =
          "Returns the number of (non-overlapping) occurrences of substring <code>sub</code> in "
              + "string, optionally restricting to <code>[start:end]</code>, <code>start</code> "
              + "being inclusive and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "sub", doc = "The substring to count."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Restrict to search from this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position before which to restrict to search.")
      })
  public int count(String self, String sub, Object start, Object end) throws EvalException {
    long indices = substringIndices(self, start, end);
    if (sub.isEmpty()) {
      return hi(indices) - lo(indices) + 1; // str.length() + 1
    }
    // Unfortunately Java forces us to allocate here, even though
    // String has a private indexOf method that accepts indices.
    // Fortunately the common case is self[0:n].
    String str = self.substring(lo(indices), hi(indices));
    int count = 0;
    int index = 0;
    while ((index = str.indexOf(sub, index)) >= 0) {
      count++;
      index += sub.length();
    }
    return count;
  }

  @StarlarkMethod(
      name = "elems",
      doc =
          "Returns an iterable value containing successive 1-element substrings of the string. "
              + "Equivalent to <code>[s[i] for i in range(len(s))]</code>, except that the "
              + "returned value might not be a list.",
      parameters = {@Param(name = "self", doc = "This string.")})
  public Sequence<String> elems(String self) {
    // TODO(adonovan): opt: return a new type that is lazily iterable.
    char[] chars = self.toCharArray();
    Object[] strings = new Object[chars.length];
    for (int i = 0; i < chars.length; i++) {
      strings[i] = memoizedCharToString(chars[i]);
    }
    return StarlarkList.wrap(null, strings);
  }

  @StarlarkMethod(
      name = "endswith",
      doc =
          "Returns True if the string ends with <code>sub</code>, otherwise False, optionally "
              + "restricting to <code>[start:end]</code>, <code>start</code> being inclusive "
              + "and <code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(
            name = "sub",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Tuple.class, generic1 = String.class),
            },
            doc = "The suffix (or tuple of alternative suffixes) to match."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "optional position at which to stop comparing.")
      })
  public boolean endsWith(String self, Object sub, Object start, Object end) throws EvalException {
    long indices = substringIndices(self, start, end);
    if (sub instanceof String) {
      return substringEndsWith(self, lo(indices), hi(indices), (String) sub);
    }
    for (String s : Sequence.cast(sub, String.class, "sub")) {
      if (substringEndsWith(self, lo(indices), hi(indices), s)) {
        return true;
      }
    }
    return false;
  }

  // Computes str.substring(start, end).endsWith(suffix) without allocation.
  private static boolean substringEndsWith(String str, int start, int end, String suffix) {
    int n = suffix.length();
    return start + n <= end && str.regionMatches(end - n, suffix, 0, n);
  }

  // In Python, formatting is very complex.
  // We handle here the simplest case which provides most of the value of the function.
  // https://docs.python.org/3/library/string.html#formatstrings
  @StarlarkMethod(
      name = "format",
      doc =
          "Perform string interpolation. Format strings contain replacement fields surrounded by"
              + " curly braces <code>&#123;&#125;</code>. Anything that is not contained in braces"
              + " is considered literal text, which is copied unchanged to the output.If you need"
              + " to include a brace character in the literal text, it can be escaped by doubling:"
              + " <code>&#123;&#123;</code> and <code>&#125;&#125;</code>A replacement field can be"
              + " either a name, a number, or empty. Values are converted to strings using the <a"
              + " href=\"../globals/all.html#str\">str</a> function.<pre"
              + " class=\"language-python\"># Access in order:\n"
              + "\"&#123;&#125; < &#123;&#125;\".format(4, 5) == \"4 < 5\"\n"
              + "# Access by position:\n"
              + "\"{1}, {0}\".format(2, 1) == \"1, 2\"\n"
              + "# Access by name:\n"
              + "\"x{key}x\".format(key = 2) == \"x2x\"</pre>\n",
      parameters = {
        @Param(name = "self", doc = "This string."),
      },
      extraPositionals = @Param(name = "args", defaultValue = "()", doc = "List of arguments."),
      extraKeywords =
          @Param(name = "kwargs", defaultValue = "{}", doc = "Dictionary of arguments."),
      useStarlarkThread = true)
  public String format(String self, Tuple args, Dict<String, Object> kwargs, StarlarkThread thread)
      throws EvalException {
    return new FormatParser().format(self, args, kwargs, thread.getSemantics());
  }

  @StarlarkMethod(
      name = "startswith",
      doc =
          "Returns True if the string starts with <code>sub</code>, otherwise False, optionally "
              + "restricting to <code>[start:end]</code>, <code>start</code> being inclusive and "
              + "<code>end</code> being exclusive.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(
            name = "sub",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Tuple.class, generic1 = String.class),
            },
            doc = "The prefix (or tuple of alternative prefixes) to match."),
        @Param(
            name = "start",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "0",
            doc = "Test beginning at this position."),
        @Param(
            name = "end",
            allowedTypes = {
              @ParamType(type = StarlarkInt.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc = "Stop comparing at this position.")
      })
  public boolean startsWith(String self, Object sub, Object start, Object end)
      throws EvalException {
    long indices = substringIndices(self, start, end);
    if (sub instanceof String) {
      return substringStartsWith(self, lo(indices), hi(indices), (String) sub);
    }
    for (String s : Sequence.cast(sub, String.class, "sub")) {
      if (substringStartsWith(self, lo(indices), hi(indices), s)) {
        return true;
      }
    }
    return false;
  }

  // Computes str.substring(start, end).startsWith(prefix) without allocation.
  private static boolean substringStartsWith(String str, int start, int end, String prefix) {
    return start + prefix.length() <= end && str.startsWith(prefix, start);
  }

  @StarlarkMethod(
      name = "removeprefix",
      doc =
          "If the string starts with <code>prefix</code>, returns a new string with the prefix "
              + "removed. Otherwise, returns the string.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "prefix", doc = "The prefix to remove if present."),
      })
  public String removePrefix(String self, String prefix) {
    if (self.startsWith(prefix)) {
      return self.substring(prefix.length());
    }
    return self;
  }

  @StarlarkMethod(
      name = "removesuffix",
      doc =
          "If the string ends with <code>suffix</code>, returns a new string with the suffix "
              + "removed. Otherwise, returns the string.",
      parameters = {
        @Param(name = "self", doc = "This string."),
        @Param(name = "suffix", doc = "The suffix to remove if present."),
      })
  public String removeSuffix(String self, String suffix) {
    if (self.endsWith(suffix)) {
      return self.substring(0, self.length() - suffix.length());
    }
    return self;
  }
}
