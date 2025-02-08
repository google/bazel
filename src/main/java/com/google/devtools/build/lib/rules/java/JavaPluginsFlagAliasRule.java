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
package com.google.devtools.build.lib.rules.java;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.RuleClass;
import javax.annotation.Nullable;

/**
 * Rule definition for 'java_plugins_flag_alias' rule. It is only to be used by the Starlark
 * implementation of 'java_library' while other native rules that also rely on the option have not
 * been migrated.
 */
public final class JavaPluginsFlagAliasRule implements RuleDefinition {

  private static final ImmutableSet<Label> ALLOWLISTED_LABELS =
      ImmutableSet.of(
          Label.parseCanonicalUnchecked("//tools/jdk:java_plugins_flag_alias"),
          Label.parseCanonicalUnchecked("@bazel_tools//tools/jdk:java_plugins_flag_alias"));

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
    return builder
        .add(
            attr(":java_plugins", LABEL_LIST)
                .cfg(ExecutionTransitionFactory.createFactory())
                .mandatoryProvidersList(
                    ImmutableList.of(
                        ImmutableList.of(JavaPluginInfo.PROVIDER.id()),
                        ImmutableList.of(JavaPluginInfo.RULES_JAVA_PROVIDER.id()),
                        ImmutableList.of(JavaPluginInfo.WORKSPACE_PROVIDER.id())))
                .silentRuleClassFilter()
                .value(JavaSemantics.JAVA_PLUGINS))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return Metadata.builder()
        .name("java_plugins_flag_alias")
        .ancestors(BaseRuleClasses.NativeBuildRule.class)
        .factoryClass(JavaPluginsFlagAlias.class)
        .build();
  }

  /**
   * Implementation of the 'java_plugins_flag_alias' rule. Provides plugins specified by the
   * --plugin flag.
   */
  public static class JavaPluginsFlagAlias implements RuleConfiguredTargetFactory {
    @Override
    @Nullable
    public ConfiguredTarget create(RuleContext ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictException {
      if (!ALLOWLISTED_LABELS.contains(ruleContext.getLabel())) {
        ruleContext.ruleError("Rule " + ruleContext.getLabel() + " cannot use private rule");
        return null;
      }

      ImmutableList<JavaPluginInfo> plugins =
          ruleContext
              .getRulePrerequisitesCollection()
              .getPrerequisites(":java_plugins", JavaPluginInfo.PROVIDER);
      if (plugins.isEmpty()) {
        plugins =
            ruleContext
                .getRulePrerequisitesCollection()
                .getPrerequisites(":java_plugins", JavaPluginInfo.RULES_JAVA_PROVIDER);
      }
      if (plugins.isEmpty()) {
        plugins =
            ruleContext
                .getRulePrerequisitesCollection()
                .getPrerequisites(":java_plugins", JavaPluginInfo.WORKSPACE_PROVIDER);
      }
      JavaPluginInfo javaPluginInfo =
          JavaPluginInfo.mergeWithoutJavaOutputs(plugins, JavaPluginInfo.PROVIDER);
      JavaPluginInfo rulesJavaProviderInfo =
          JavaPluginInfo.mergeWithoutJavaOutputs(plugins, JavaPluginInfo.RULES_JAVA_PROVIDER);
      JavaPluginInfo workspaceProviderInfo =
          JavaPluginInfo.mergeWithoutJavaOutputs(plugins, JavaPluginInfo.WORKSPACE_PROVIDER);

      return new RuleConfiguredTargetBuilder(ruleContext)
          .addStarlarkDeclaredProvider(javaPluginInfo)
          .addStarlarkDeclaredProvider(rulesJavaProviderInfo)
          .addStarlarkDeclaredProvider(workspaceProviderInfo)
          .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY)
          .build();
    }
  }
}
