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
package com.google.devtools.build.lib.bazel.rules;

import static com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions.EXPERIMENTAL_JAVA_LIBRARY_EXPORT;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.RuleSet;
import com.google.devtools.build.lib.bazel.rules.java.BazelJavaSemantics;
import com.google.devtools.build.lib.rules.core.CoreRules;
import com.google.devtools.build.lib.rules.extra.ActionListenerRule;
import com.google.devtools.build.lib.rules.extra.ExtraActionRule;
import com.google.devtools.build.lib.rules.java.JavaConfiguration;
import com.google.devtools.build.lib.rules.java.JavaPluginsFlagAliasRule;
import com.google.devtools.build.lib.rules.java.JavaRuleClasses.JavaRuntimeBaseRule;
import com.google.devtools.build.lib.rules.java.JavaRuleClasses.JavaToolchainBaseRule;
import com.google.devtools.build.lib.rules.java.JavaStarlarkCommon;
import com.google.devtools.build.lib.rules.java.ProguardSpecProvider;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaBootstrap;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import java.io.IOException;
import net.starlark.java.eval.FlagGuardedValue;
import net.starlark.java.eval.Starlark;

/** Rules for Java support in Bazel. */
public class JavaRules implements RuleSet {
  public static final JavaRules INSTANCE = new JavaRules();

  private JavaRules() {
    // Use the static INSTANCE field instead.
  }

  @Override
  public void init(ConfiguredRuleClassProvider.Builder builder) {
    builder.addConfigurationFragment(JavaConfiguration.class);

    builder.addRuleDefinition(new JavaToolchainBaseRule());
    builder.addRuleDefinition(new JavaRuntimeBaseRule());
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_binary", coreBzlLabel("java_binary")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_library", coreBzlLabel("java_library")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_import", coreBzlLabel("java_import")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_test", coreBzlLabel("java_test")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_plugin", coreBzlLabel("java_plugin")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_toolchain", toolchainBzlLabel("java_toolchain")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule(
            "java_package_configuration", toolchainBzlLabel("java_package_configuration")) {});
    builder.addRuleDefinition(
        new BaseRuleClasses.EmptyRule("java_runtime", toolchainBzlLabel("java_runtime")) {});
    builder.addRuleDefinition(new JavaPluginsFlagAliasRule());

    builder.addRuleDefinition(new ExtraActionRule());
    builder.addRuleDefinition(new ActionListenerRule());

    builder.addStarlarkBootstrap(new JavaBootstrap(ProguardSpecProvider.PROVIDER));

    builder.addStarlarkBuiltinsInternal(
        "java_common_internal_do_not_use", new JavaStarlarkCommon(BazelJavaSemantics.INSTANCE));

    builder.addBzlToplevel(
        "experimental_java_library_export_do_not_use",
        FlagGuardedValue.onlyWhenExperimentalFlagIsTrue(
            EXPERIMENTAL_JAVA_LIBRARY_EXPORT, Starlark.NONE));

    try {
      builder.addWorkspaceFileSuffix(
          ResourceFileLoader.loadResource(JavaRules.class, "coverage.WORKSPACE"));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public ImmutableList<RuleSet> requires() {
    return ImmutableList.of(CoreRules.INSTANCE, CcRules.INSTANCE);
  }

  private static String coreBzlLabel(String ruleName) {
    return "@rules_java//java" + ":" + ruleName + ".bzl";
  }

  private static String toolchainBzlLabel(String ruleName) {
    return "@rules_java//java/toolchains" + ":" + ruleName + ".bzl";
  }
}
