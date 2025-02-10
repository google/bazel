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
package com.google.devtools.build.lib.analysis.stringtemplate;

import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/**
 * Simple string template expansion. String templates consist of text interspersed with
 * <code>$(variable)</code> or <code>$(function value)</code> references, which are replaced by
 * strings.
 */
public final class TemplateExpander {
  private final char[] buffer;
  private final int length;
  private int offset;

  private TemplateExpander(String expression) {
    buffer = expression.toCharArray();
    length = buffer.length;
    offset = 0;
  }

  /**
   * If the string contains a single variable, return the expansion of that variable. Otherwise,
   * return null.
   */
  @Nullable
  public static String expandSingleVariable(String expression, TemplateContext context)
      throws ExpansionException {
    String var = new TemplateExpander(expression).getSingleVariable();
    return (var != null) ? context.lookupVariable(var) : null;
  }

  /**
   * Expands all references to template variables embedded within string "expr", using the provided
   * {@link TemplateContext} instance to expand individual variables.
   *
   * @param expression the string to expand.
   * @param context the context which defines the expansion of each individual variable
   * @return the expansion of "expr"
   * @throws ExpansionException if "expr" contained undefined or ill-formed variables references
   */
  public static Expansion expand(String expression, TemplateContext context)
      throws ExpansionException {
    if (expression.indexOf('$') < 0) {
      return Expansion.create(expression, ImmutableSet.of());
    }
    return expand(expression, context, 0);
  }

  // Helper method for counting recursion depth.
  private static Expansion expand(String expression, TemplateContext context, int depth)
      throws ExpansionException {
    if (depth > 10) { // plenty!
      throw new ExpansionException(
          String.format("potentially unbounded recursion during expansion of '%s'", expression));
    }
    return new TemplateExpander(expression).expand(context, depth);
  }

  private Expansion expand(TemplateContext context, int depth) throws ExpansionException {
    StringBuilder result = new StringBuilder();
    ImmutableSet.Builder<String> lookedUpVariables = ImmutableSet.builder();
    while (offset < length) {
      char c = buffer[offset];
      if (c == '$') { // variable
        offset++;
        if (offset >= length) {
          throw new ExpansionException("unterminated $");
        }
        if (buffer[offset] == '$') {
          result.append('$');
        } else {
          String var = scanVariable();
          int spaceIndex = var.indexOf(' ');
          if (spaceIndex < 0) {
            String value = context.lookupVariable(var);
            lookedUpVariables.add(var);
            // To prevent infinite recursion for the ignored shell variables
            if (!value.equals(var)) {
              // recursively expand using Make's ":=" semantics:
              Expansion expansion = expand(value, context, depth + 1);
              value = expansion.expansion();
              lookedUpVariables.addAll(expansion.lookedUpVariables());
            }
            result.append(value);
          } else {
            String name = var.substring(0, spaceIndex);
            // Trim the string to remove leading and trailing whitespace.
            String param = var.substring(spaceIndex + 1).trim();
            String value = context.lookupFunction(name, param);
            lookedUpVariables.add(name);
            result.append(value);
          }
        }
      } else {
        result.append(c);
      }
      offset++;
    }
    return Expansion.create(result.toString(), lookedUpVariables.build());
  }

  /**
   * Starting at the current position, scans forward until the name of a template variable has been
   * consumed. Returns the variable name and advances the position. If the variable is a potential
   * shell variable returns the shell variable expression itself, so that we can let the shell
   * handle the expansion.
   *
   * @return the name of the variable found at the current point.
   * @throws ExpansionException if the variable reference was ill-formed.
   */
  private String scanVariable() throws ExpansionException {
    char c = buffer[offset];
    switch (c) {
      case '(' -> {
        // looks like $(SRCS)
        offset++;
        int start = offset;
        while (offset < length && buffer[offset] != ')') {
          offset++;
        }
        if (offset >= length) {
          throw new ExpansionException("unterminated variable reference");
        }
        return new String(buffer, start, offset - start);
        // We only parse ${variable} syntax to provide a better error message.
      }
      case '{' -> {
        // looks like ${SRCS}
        offset++;
        int start = offset;
        while (offset < length && buffer[offset] != '}') {
          offset++;
        }
        if (offset >= length) {
          throw new ExpansionException("unterminated variable reference");
        }
        String expr = new String(buffer, start, offset - start);
        throw new ExpansionException(
            "'${"
                + expr
                + "}' syntax is not supported; use '$("
                + expr
                + ")' instead for \"Make\" variables, or escape the '$' as "
                + "'$$' if you intended this for the shell");
      }
      case '@', '<', '^' -> {
        return String.valueOf(c);
      }
      default -> {
        int start = offset;
        while (offset + 1 < length && Character.isJavaIdentifierPart(buffer[offset + 1])) {
          offset++;
        }
        String expr = new String(buffer, start, offset + 1 - start);
        throw new ExpansionException(
            "'$"
                + expr
                + "' syntax is not supported; use '$("
                + expr
                + ")' instead for \"Make\" variables, or escape the '$' as "
                + "'$$' if you intended this for the shell");
      }
    }
  }

  /**
   * @return the variable name if the variable spans from offset to the end of the buffer, otherwise
   *     null
   * @throws ExpansionException if the variable reference was ill-formed
   */
  @Nullable
  private String getSingleVariable() throws ExpansionException {
    if (buffer[offset] == '$') {
      offset++;
      String result = scanVariable();
      if (offset + 1 == length) {
        return result;
      }
    }
    return null;
  }
}
