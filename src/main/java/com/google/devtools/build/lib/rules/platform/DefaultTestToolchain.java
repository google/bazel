// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.platform;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BuildType;
import javax.annotation.Nullable;

/** Defines a toolchain that can be used by rules. */
public class DefaultTestToolchain implements RuleConfiguredTargetFactory {

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {

    ToolchainTypeInfo toolchainType =
        PlatformProviderUtils.toolchainType(
            ruleContext.getPrerequisite(ToolchainRule.TOOLCHAIN_TYPE_ATTR));
    ImmutableList<ConfigMatchingProvider> targetSettings =
        ruleContext.getPrerequisites(ToolchainRule.TARGET_SETTING_ATTR).stream()
            .map(target -> target.getProvider(ConfigMatchingProvider.class))
            .collect(toImmutableList());
    Label resolvedToolchainLabel =
        ruleContext.attributes().get(ToolchainRule.TOOLCHAIN_ATTR, BuildType.NODEP_LABEL);

    DeclaredToolchainInfo registeredToolchain;
    try {
      registeredToolchain =
          DeclaredToolchainInfo.builder()
              .toolchainType(toolchainType)
              .addTargetSettings(targetSettings)
              .resolvedToolchainLabel(resolvedToolchainLabel)
              .targetLabel(ruleContext.getLabel())
              .buildForTestToolchain();
    } catch (DeclaredToolchainInfo.DuplicateConstraintException e) {
      throw new IllegalStateException(
          "default_test_toolchain does not set constraints, but failed with duplicate constraints",
          e);
    }

    return new RuleConfiguredTargetBuilder(ruleContext)
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY)
        .addProvider(FileProvider.class, FileProvider.EMPTY)
        .addProvider(FilesToRunProvider.class, FilesToRunProvider.EMPTY)
        .addProvider(registeredToolchain)
        .build();
  }
}
