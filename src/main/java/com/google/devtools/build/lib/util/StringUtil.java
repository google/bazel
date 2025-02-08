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
package com.google.devtools.build.lib.util;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Iterator;

/** Various utility methods operating on strings. */
public class StringUtil {
  /**
   * Creates a comma-separated list of words as in English.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>["a"] → "a"
   *   <li>["a", "b"] → "a or b"
   *   <li>["a", "b", "c"] → "a, b, or c"
   * </ul>
   */
  public static String joinEnglishList(Iterable<?> choices) {
    return joinEnglishList(choices, "or", "", /* oxfordComma= */ true);
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator.
   *
   * <p>Example with lastSeparator="and": ["a", "b", "c"] → "a, b, and c".
   */
  public static String joinEnglishList(Iterable<?> choices, String lastSeparator) {
    return joinEnglishList(choices, lastSeparator, "", /* oxfordComma= */ true);
  }

  /**
   * Creates a comma-separated list of words as in English with the given last-separator and quotes.
   *
   * <p>Example with lastSeparator="then", quote="'", oxfordComma=false: ["a", "b", "c"] → "'a', 'b'
   * then 'c'".
   */
  public static String joinEnglishList(
      Iterable<?> choices, String lastSeparator, String quote, boolean oxfordComma) {
    StringBuilder buf = new StringBuilder();
    int numChoicesSeen = 0;
    for (Iterator<?> ii = choices.iterator(); ii.hasNext(); ) {
      Object choice = ii.next();
      if (buf.length() > 0) {
        if (ii.hasNext() || (oxfordComma && numChoicesSeen >= 2)) {
          buf.append(",");
        }
        if (!ii.hasNext()) {
          buf.append(" ").append(lastSeparator);
        }
        buf.append(" ");
      }
      buf.append(quote).append(choice).append(quote);
      numChoicesSeen++;
    }
    return buf.length() == 0 ? "nothing" : buf.toString();
  }

  /**
   * Creates a comma-separated list of singe-quoted words as in English.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>["a"] → "'a'""
   *   <li>["a", "b"] → "'a' or 'b'"
   *   <li>["a", "b", "c"] → "'a', 'b', or 'c'"
   * </ul>
   */
  public static String joinEnglishListSingleQuoted(Iterable<?> choices) {
    return joinEnglishList(choices, "or", "'", /* oxfordComma= */ true);
  }

  /**
   * Lists items up to a given limit, then prints how many were omitted.
   */
  public static StringBuilder listItemsWithLimit(StringBuilder appendTo, int limit,
      Collection<?> items) {
    Preconditions.checkState(limit > 0);
    Joiner.on(", ").appendTo(appendTo, Iterables.limit(items, limit));
    if (items.size() > limit) {
      appendTo.append(" ...(omitting ")
          .append(items.size() - limit)
          .append(" more item(s))");
    }
    return appendTo;
  }

  /**
   * Returns the ordinal representation of the number.
   */
  public static String ordinal(int number) {
    switch (number) {
      case 1:
        return "1st";
      case 2:
        return "2nd";
      case 3:
        return "3rd";
      default:
        return number + "th";
    }
  }

  private StringUtil() {}
}
