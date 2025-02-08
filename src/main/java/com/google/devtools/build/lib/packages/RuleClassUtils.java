// Copyright 2024 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.packages;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import com.google.devtools.build.lib.starlarkbuildapi.MacroFunctionApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import net.starlark.java.eval.StarlarkFunction;

/** Rule class utilities. */
public final class RuleClassUtils {

  /**
   * Returns the sorted list of all builtin rule classes.
   *
   * <p>Unlike {@link RuleClassProvider#getRuleClassMap}, this method returns real Starlark builtins
   * instead of stub overridden native rules.
   *
   * @param includeMacroWrappedRules if true, include rule classes for rules wrapped in macros.
   */
  public static ImmutableList<RuleClass> getBuiltinRuleClasses(
      StarlarkBuiltinsValue builtins,
      RuleClassProvider ruleClassProvider,
      boolean includeMacroWrappedRules) {
    ImmutableMap<String, RuleClass> nativeRuleClasses = ruleClassProvider.getRuleClassMap();
    // The conditional for selecting whether or not to load symbols from @_builtins is the same as
    // in PackageFunction.compileBuildFile
    if (builtins
        .starlarkSemantics
        .get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH)
        .isEmpty()) {
      return ImmutableList.sortedCopyOf(
          Comparator.comparing(RuleClass::getName), nativeRuleClasses.values());
    } else {
      ArrayList<RuleClass> ruleClasses = new ArrayList<>(builtins.predeclaredForBuild.size());
      for (Map.Entry<String, Object> entry : builtins.predeclaredForBuild.entrySet()) {
        if (entry.getValue() instanceof RuleFunction) {
          ruleClasses.add(((RuleFunction) entry.getValue()).getRuleClass());
        } else if ((entry.getValue() instanceof StarlarkFunction
                || entry.getValue() instanceof MacroFunctionApi)
            && includeMacroWrappedRules) {
          // entry.getValue() is a macro in @_builtins which overrides a native rule and wraps a
          // instantiation of a rule target. We cannot get at that main target's rule class
          // directly, so we attempt heuristics.
          // Note that we do not rely on the StarlarkFunction or MacroFunction object's name because
          // the name under which the macro was defined may not match the name under which
          // @_builtins re-exported it.
          if (builtins.exportedToJava.containsKey(entry.getKey() + "_rule_function")) {
            ruleClasses.add(
                ((RuleFunction) builtins.exportedToJava.get(entry.getKey() + "_rule_function"))
                    .getRuleClass());
          } else if (nativeRuleClasses.containsKey(entry.getKey())) {
            ruleClasses.add(nativeRuleClasses.get(entry.getKey()));
          }
        }
      }
      return ImmutableList.sortedCopyOf(Comparator.comparing(RuleClass::getName), ruleClasses);
    }
  }

  private RuleClassUtils() {}
}
