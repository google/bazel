// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.actions.Substitution;
import com.google.devtools.build.lib.analysis.actions.Substitution.ComputedSubstitution;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.starlarkbuildapi.TemplateDictApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;

/** Implementation of the {@code TemplateDict} Starlark type */
public class TemplateDict implements TemplateDictApi {

  private final List<Substitution> substitutions = Lists.newArrayList();

  private TemplateDict() {}

  public static TemplateDictApi newDict() {
    return new TemplateDict();
  }

  @CanIgnoreReturnValue
  @Override
  public TemplateDictApi addArgument(String key, String value, StarlarkThread thread)
      throws EvalException {
    substitutions.add(Substitution.of(key, value));
    return this;
  }

  @CanIgnoreReturnValue
  @Override
  public TemplateDictApi addJoined(
      String key,
      Depset valuesSet,
      String joinWith,
      StarlarkCallable mapEach,
      Boolean uniquify,
      Object formatJoined,
      Boolean allowClosure,
      StarlarkThread thread)
      throws EvalException {
    if (mapEach instanceof StarlarkFunction sfn) {
      if (!allowClosure && sfn.getModule().getGlobal(sfn.getName()) != sfn) {
        throw Starlark.errorf(
            "to avoid unintended retention of analysis data structures, "
                + "the map_each function (declared at %s) must be declared "
                + "by a top-level def statement",
            sfn.getLocation());
      }
    }
    substitutions.add(
        new LazySubstitution(
            key,
            thread.getSemantics(),
            valuesSet,
            mapEach,
            uniquify,
            joinWith,
            formatJoined != Starlark.NONE ? (String) formatJoined : null));
    return this;
  }

  public Iterable<? extends Substitution> getAll() {
    return substitutions;
  }

  private static class LazySubstitution extends ComputedSubstitution {

    private final StarlarkSemantics semantics;
    private final Depset valuesSet;
    private final StarlarkCallable mapEach;
    private final boolean uniquify;
    private final String joinWith;
    @Nullable private final String formatJoined;

    public LazySubstitution(
        String key,
        StarlarkSemantics semantics,
        Depset valuesSet,
        StarlarkCallable mapEach,
        boolean uniquify,
        String joinWith,
        @Nullable String formatJoined) {
      super(key);
      this.semantics = semantics;
      this.valuesSet = valuesSet;
      this.mapEach = mapEach;
      this.uniquify = uniquify;
      this.joinWith = joinWith;
      this.formatJoined = formatJoined;
    }

    @Override
    public String getValue() throws EvalException {
      try (Mutability mutability = Mutability.create("expand_template")) {
        StarlarkThread execThread =
            StarlarkThread.create(
                mutability,
                semantics,
                "map_each callback",
                // The map_each callback should not create any persistent state beyond the returned
                // String value.
                SymbolGenerator.createTransient());
        ImmutableList<?> values = valuesSet.toList();
        List<String> parts = new ArrayList<>(values.size());
        for (Object val : values) {
          try {
            Object ret = Starlark.positionalOnlyCall(execThread, mapEach, val);
            if (ret instanceof String string) {
              parts.add(string);
            } else if (ret instanceof Sequence<?> sequence) {
              for (Object v : sequence) {
                if (!(v instanceof String)) {
                  throw Starlark.errorf(
                      "Function provided to map_each must return string, None, or list of strings,"
                          + " but returned list containing element '%s' of type %s for key '%s' and"
                          + " value: %s",
                      v, Starlark.type(v), getKey(), val);
                }
                parts.add((String) v);
              }
            } else if (ret != Starlark.NONE) {
              throw Starlark.errorf(
                  "Function provided to map_each must return string, None, or list of strings, but "
                      + "returned type %s for key '%s' and value: %s",
                  Starlark.type(ret), getKey(), val);
            }
          } catch (InterruptedException e) {
            // Report the error to the user, but the stack trace is not of use to them
            throw Starlark.errorf(
                "Could not evaluate substitution for %s: %s", val, e.getMessage());
          }
        }
        if (uniquify) {
          // Stably deduplicate parts in-place.
          int count = parts.size();
          HashSet<String> seen = Sets.newHashSetWithExpectedSize(count);
          int addIndex = 0;
          for (int i = 0; i < count; ++i) {
            String val = parts.get(i);
            if (seen.add(val)) {
              parts.set(addIndex++, val);
            }
          }
          parts = parts.subList(0, addIndex);
        }
        String joined = Joiner.on(joinWith).join(parts);
        if (formatJoined != null) {
          return Starlark.format(semantics, formatJoined, joined);
        }
        return joined;
      }
    }
  }
}
