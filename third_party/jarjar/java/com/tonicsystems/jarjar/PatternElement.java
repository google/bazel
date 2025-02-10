/*
 * Copyright 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tonicsystems.jarjar;

import java.util.ArrayList;
import java.util.List;

public abstract class PatternElement {
  private String pattern;

  public void setPattern(String pattern) {
    this.pattern = pattern;
  }

  public String getPattern() {
    return pattern;
  }

  static List<Wildcard> createWildcards(List<? extends PatternElement> patterns) {
    List<Wildcard> wildcards = new ArrayList<>();
    for (PatternElement pattern : patterns) {
      String result = (pattern instanceof Rule) ? ((Rule) pattern).getResult() : "";
      String expr = pattern.getPattern();
      if (expr.indexOf('/') >= 0) {
        throw new IllegalArgumentException("Patterns cannot contain slashes");
      }
      wildcards.add(new Wildcard(expr.replace('.', '/'), result));
    }
    return wildcards;
  }
}
