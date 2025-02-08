// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.analysis.Expander;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;

/** Utility methods for Objc rules in Starlark Builtins */
@StarlarkBuiltin(name = "objc_internal", category = DocCategory.BUILTIN, documented = false)
public class ObjcStarlarkInternal implements StarlarkValue {

  public static final String NAME = "objc_internal";

  /**
   * Converts a possibly NoneType object to the real object if it is not NoneType or returns the
   * default value if it is.
   */
  @SuppressWarnings("unchecked")
  public static <T> T convertFromNoneable(Object obj, @Nullable T defaultValue) {
    if (Starlark.UNBOUND == obj || Starlark.isNullOrNone(obj)) {
      return defaultValue;
    }
    return (T) obj;
  }

  /**
   * Run variable expansion and shell tokenization on a sequence of flags.
   *
   * <p>When expanding path variables (e.g. $(execpath ...)), the label can refer to any of which in
   * the {@code srcs}, {@code non_arc_srcs}, {@code hdrs} or {@code data} attributes or an output of
   * the target.
   *
   * @param starlarkRuleContext The rule context of the expansion.
   * @param attributeName The attribute of the rule tied to the expansion. Used for error reporting
   *     only.
   * @param flags The sequence of flags to expand.
   */
  @StarlarkMethod(
      name = "expand_and_tokenize",
      documented = false,
      parameters = {
        @Param(name = "ctx", positional = false, named = true),
        @Param(name = "attr", positional = false, named = true),
        @Param(name = "flags", positional = false, defaultValue = "[]", named = true),
      })
  public Sequence<String> expandAndTokenize(
      StarlarkRuleContext starlarkRuleContext, String attributeName, Sequence<?> flags)
      throws EvalException, InterruptedException {
    if (flags.isEmpty()) {
      return Sequence.cast(flags, String.class, attributeName);
    }
    Expander expander =
        starlarkRuleContext
            .getRuleContext()
            .getExpander(
                StarlarkRuleContext.makeLabelMap(
                    ImmutableSet.copyOf(
                        Iterables.concat(
                            starlarkRuleContext.getRuleContext().getPrerequisites("srcs"),
                            starlarkRuleContext.getRuleContext().getPrerequisites("non_arc_srcs"),
                            starlarkRuleContext.getRuleContext().getPrerequisites("hdrs"),
                            starlarkRuleContext.getRuleContext().getPrerequisites("data"),
                            starlarkRuleContext
                                .getRuleContext()
                                .getPrerequisites("additional_linker_inputs"))),
                    starlarkRuleContext
                        .getStarlarkSemantics()
                        .getBool(BuildLanguageOptions.INCOMPATIBLE_LOCATIONS_PREFERS_EXECUTABLE)))
            .withDataExecLocations();
    ImmutableList<String> expandedFlags =
        expander.tokenized(attributeName, Sequence.cast(flags, String.class, attributeName));
    return StarlarkList.immutableCopyOf(expandedFlags);
  }

  @StarlarkMethod(
      name = "get_split_prerequisites",
      documented = false,
      parameters = {@Param(name = "ctx", named = true)})
  public ImmutableMap<String, BuildConfigurationValue> getSplitPrerequisites(
      StarlarkRuleContext starlarkRuleContext) throws EvalException {
    Map<Optional<String>, List<ConfiguredTargetAndData>> ctads =
        starlarkRuleContext
            .getRuleContext()
            .getRulePrerequisitesCollection()
            .getSplitPrerequisites(ObjcRuleClasses.CHILD_CONFIG_ATTR);
    ImmutableMap.Builder<String, BuildConfigurationValue> result = ImmutableMap.builder();
    for (Optional<String> splitTransitionKey : ctads.keySet()) {
      if (!splitTransitionKey.isPresent()) {
        throw new EvalException("unexpected empty key in split transition");
      }
      result.put(
          splitTransitionKey.get(),
          Iterables.getOnlyElement(ctads.get(splitTransitionKey)).getConfiguration());
    }
    return result.buildOrThrow();
  }

  @StarlarkMethod(
      name = "get_apple_config",
      documented = false,
      parameters = {@Param(name = "build_config", named = true)})
  public AppleConfiguration getAppleConfig(BuildConfigurationValue buildConfiguration)
      throws EvalException {
    return buildConfiguration.getFragment(AppleConfiguration.class);
  }

  @StarlarkMethod(
      name = "get_cpu",
      documented = false,
      parameters = {@Param(name = "build_config", named = true)})
  public String getCpu(BuildConfigurationValue buildConfiguration) throws EvalException {
    return buildConfiguration.getCpu();
  }

  @StarlarkMethod(
      name = "get_split_build_configs",
      documented = false,
      parameters = {@Param(name = "ctx", positional = true, named = true)})
  public Dict<String, BuildConfigurationValue> getSplitBuildConfigs(
      StarlarkRuleContext starlarkRuleContext) throws EvalException {
    Map<Optional<String>, List<ConfiguredTargetAndData>> ctads =
        starlarkRuleContext
            .getRuleContext()
            .getRulePrerequisitesCollection()
            .getSplitPrerequisites(ObjcRuleClasses.CHILD_CONFIG_ATTR);
    Dict.Builder<String, BuildConfigurationValue> result = Dict.builder();
    for (Optional<String> splitTransitionKey : ctads.keySet()) {
      if (!splitTransitionKey.isPresent()) {
        throw new EvalException("unexpected empty key in split transition");
      }
      result.put(
          splitTransitionKey.get(),
          Iterables.getOnlyElement(ctads.get(splitTransitionKey)).getConfiguration());
    }
    return result.buildImmutable();
  }
}
