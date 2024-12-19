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
package net.starlark.java.eval;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;

/**
 * A helper class that offers a subset of the functionality of Python's string#format.
 *
 * <p>Currently, both manual and automatic positional as well as named replacement fields are
 * supported. However, nested replacement fields are not allowed.
 */
final class FormatParser {

  /**
   * Matches strings likely to be a number, faster alternative to relying solely on Integer.parseInt
   * and NumberFormatException to determine numericness.
   */
  private static final CharMatcher LIKELY_NUMERIC_MATCHER =
      CharMatcher.inRange('0', '9').or(CharMatcher.is('-'));

  private static final ImmutableSet<Character> ILLEGAL_IN_FIELD =
      ImmutableSet.of('.', '[', ']', ',');

  /**
   * Formats the given input string by using the given arguments
   *
   * <p>This method offers a subset of the functionality of Python's string#format
   *
   * @param input The string to be formatted
   * @param args Positional arguments
   * @param kwargs Named arguments
   * @return The formatted string
   */
  String format(
      String input, List<Object> args, Map<String, Object> kwargs, StarlarkSemantics semantics)
      throws EvalException {
    StringBuilder output = new StringBuilder();
    History history = new History();

    for (int pos = 0; pos < input.length(); ++pos) {
      char current = input.charAt(pos);
      int advancePos = 0;

      if (current == '{') {
        advancePos = processOpeningBrace(input, pos, args, kwargs, history, output, semantics);
      } else if (current == '}') {
        advancePos = processClosingBrace(input, pos, output);
      } else {
        output.append(current);
      }

      pos += advancePos;
    }

    return output.toString();
  }

  /**
   * Processes the expression after an opening brace (possibly a replacement field) and emits the
   * result to the output StringBuilder
   *
   * @param s The entire string
   * @param pos The position of the opening brace
   * @param args List of positional arguments
   * @param kwargs Map of named arguments
   * @param history Helper object that tracks information about previously seen positional
   *     replacement fields
   * @param output StringBuilder that consumes the result
   * @return Number of characters that have been consumed by this method
   */
  private int processOpeningBrace(
      String s,
      int pos,
      List<Object> args,
      Map<String, Object> kwargs,
      History history,
      StringBuilder output,
      StarlarkSemantics semantics)
      throws EvalException {
    Printer printer = new Printer(output);
    if (has(s, pos + 1, '{')) {
      // Escaped brace -> output and move to char after right brace
      printer.append("{");
      return 1;
    }

    // Inside a replacement field
    String key = getFieldName(s, pos);
    Object value = null;

    // Only positional replacement fields will lead to a valid index
    try {
      if (key.isEmpty() || LIKELY_NUMERIC_MATCHER.matchesAllOf(key)) {
        int index = parsePositional(key, history);

        if (index < 0 || index >= args.size()) {
          throw Starlark.errorf("No replacement found for index %d", index);
        }

        value = args.get(index);
      } else {
        value = getKwarg(kwargs, key);
      }
    } catch (NumberFormatException nfe) {
      // Non-integer index -> Named
      value = getKwarg(kwargs, key);
    }

    // Format object for output
    printer.str(value, semantics);

    // Advances the current position to the index of the closing brace of the
    // replacement field. Due to the definition of the enclosing for() loop,
    // the next iteration will examine the character right after the brace.
    return key.length() + 1;
  }

  private Object getKwarg(Map<String, Object> kwargs, String key) throws EvalException {
    if (!kwargs.containsKey(key)) {
      throw Starlark.errorf("Missing argument '%s'", key);
    }

    return kwargs.get(key);
  }

  /**
   * Processes a closing brace and emits the result to the output StringBuilder
   *
   * @param s The entire string
   * @param pos Position of the closing brace
   * @param output StringBuilder that consumes the result
   * @return Number of characters that have been consumed by this method
   */
  private int processClosingBrace(String s, int pos, StringBuilder output)
      throws EvalException {
    if (!has(s, pos + 1, '}')) {
      // Invalid brace outside replacement field
      throw Starlark.errorf("Found '}' without matching '{'");
    }

    // Escaped brace -> output and move to char after right brace
    output.append("}");
    return 1;
  }

  /**
   * Checks whether the given input string has a specific character at the given location
   *
   * @param s Input string
   * @param pos Position to be checked
   * @param needle Character to be searched for
   * @return True if string has the specified character at the given location
   */
  private static boolean has(String s, int pos, char needle) {
    return pos < s.length() && s.charAt(pos) == needle;
  }

  /**
   * Extracts the name/index of the replacement field that starts at the specified location
   *
   * @param s Input string
   * @param openingBrace Position of the opening brace of the replacement field
   * @return Name or index of the current replacement field
   */
  private String getFieldName(String s, int openingBrace) throws EvalException {
    StringBuilder result = new StringBuilder();
    boolean foundClosingBrace = false;

    for (int pos = openingBrace + 1; pos < s.length(); ++pos) {
      char current = s.charAt(pos);

      if (current == '}') {
        foundClosingBrace = true;
        break;
      } else {
        if (current == '{') {
          throw Starlark.errorf("Nested replacement fields are not supported");
        } else if (ILLEGAL_IN_FIELD.contains(current)) {
          throw Starlark.errorf("Invalid character '%s' inside replacement field", current);
        }

        result.append(current);
      }
    }

    if (!foundClosingBrace) {
      throw Starlark.errorf("Found '{' without matching '}'");
    }

    return result.toString();
  }

  /**
   * Converts the given key into an integer or assigns the next available index, if empty.
   *
   * @param key Key to be converted
   * @param history Helper object that tracks information about previously seen positional
   *     replacement fields
   * @return The integer equivalent of the key
   */
  private int parsePositional(String key, History history) throws EvalException {
    int result = -1;

    try {
      if (key.isEmpty()) {
        // Automatic positional -> a new index value has to be assigned
        history.setAutomaticPositional();
        result = history.getNextPosition();
      } else {
        // This will fail if key is a named argument
        result = Integer.parseInt(key);
        history.setManualPositional(); // Only register if the conversion succeeds
      }
    } catch (MixedTypeException mte) {
      throw Starlark.errorf("%s", mte.getMessage());
    }

    return result;
  }

  /**
   * Exception for invalid combinations of replacement field types
   */
  private static final class MixedTypeException extends Exception {
    MixedTypeException() {
      super("Cannot mix manual and automatic numbering of positional fields");
    }
  }

  /**
   * A wrapper to keep track of information about previous replacement fields
   */
  private static final class History {
    /** Different types of positional replacement fields */
    enum Positional {
      NONE,
      MANUAL, // {0}, {1} etc.
      AUTOMATIC // {}
    }

    Positional type = Positional.NONE;
    int position = -1;

    /**
     * Returns the next available index for an automatic positional replacement field
     *
     * @return Next index
     */
    int getNextPosition() {
      ++position;
      return position;
    }

    /** Registers a manual positional replacement field */
    void setManualPositional() throws MixedTypeException {
      setPositional(Positional.MANUAL);
    }

    /** Registers an automatic positional replacement field */
    void setAutomaticPositional() throws MixedTypeException {
      setPositional(Positional.AUTOMATIC);
    }

    /**
     * Indicates that a positional replacement field of the specified type is being processed and
     * checks whether this conflicts with any previously seen replacement fields
     *
     * @param current Type of current replacement field
     */
    void setPositional(Positional current) throws MixedTypeException {
      if (type == Positional.NONE) {
        type = current;
      } else if (type != current) {
        throw new MixedTypeException();
      }
    }
  }
}
