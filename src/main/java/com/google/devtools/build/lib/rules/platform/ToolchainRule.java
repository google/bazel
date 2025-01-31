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

import static com.google.devtools.build.lib.packages.Attribute.attr;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.util.FileTypeSet;

/** Rule definition for {@link Toolchain}. */
public class ToolchainRule implements RuleDefinition {
  public static final String RULE_NAME = "toolchain";
  public static final String TOOLCHAIN_TYPE_ATTR = "toolchain_type";
  public static final String EXEC_COMPATIBLE_WITH_ATTR = "exec_compatible_with";
  public static final String TARGET_COMPATIBLE_WITH_ATTR = "target_compatible_with";
  public static final String TARGET_SETTING_ATTR = "target_settings";
  public static final String TOOLCHAIN_ATTR = "toolchain";
  public static final String USE_TARGET_PLATFORM_CONSTRAINTS_ATTR = "use_target_platform_constraints";

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    return builder
        .advertiseProvider(DeclaredToolchainInfo.class)
        .override(
            attr("tags", Types.STRING_LIST)
                // No need to show up in ":all", etc. target patterns.
                .value(ImmutableList.of("manual"))
                .nonconfigurable("low-level attribute, used in platform configuration"))
        .removeAttribute(":action_listener")
        .exemptFromConstraintChecking("this rule *defines* a constraint")
        .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)

        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(toolchain_type) -->
        The label of a <code>toolchain_type</code> target that represents the role that this
        toolchain serves.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(TOOLCHAIN_TYPE_ATTR, BuildType.LABEL)
                .mandatory()
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .allowedRuleClasses("toolchain_type")
                .mandatoryProviders(ToolchainTypeInfo.PROVIDER.id())
                .nonconfigurable("part of toolchain configuration"))
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(exec_compatible_with) -->
        A list of <code>constraint_value</code>s that must be satisfied by an execution platform in
        order for this toolchain to be selected for a target building on that platform.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .nonconfigurable("part of toolchain configuration"))
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(target_compatible_with) -->
        A list of <code>constraint_value</code>s that must be satisfied by the target platform in
        order for this toolchain to be selected for a target building for that platform.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(TARGET_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .nonconfigurable("part of toolchain configuration"))
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(use_target_platform_constraints) -->
        If <code>True</code>, this toolchain behaves as if its <code>exec_compatible_with</code> and
        <code>target_compatible_with</code> constraints are set to those of the current target
        platform. <code>exec_compatible_with</code> and <code>target_compatible_with</code> must not
        be set in that case.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(USE_TARGET_PLATFORM_CONSTRAINTS_ATTR, Type.BOOLEAN)
                .value(false)
                .nonconfigurable("part of toolchain configuration"))
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(target_settings) -->
        A list of <code>config_setting</code>s that must be satisfied by the target configuration
        in order for this toolchain to be selected during toolchain resolution.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(TARGET_SETTING_ATTR, BuildType.LABEL_LIST)
                .allowedRuleClasses("config_setting")
                .allowedFileTypes(FileTypeSet.NO_FILE))
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(toolchain) -->
        The target representing the actual tool or tool suite that is made available when this
        toolchain is selected.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        // This needs to not introduce a dependency so that we can load the toolchain only if it is
        // needed.
        .add(attr(TOOLCHAIN_ATTR, BuildType.NODEP_LABEL).mandatory())
        .build();
  }

  @Override
  public RuleDefinition.Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name(RULE_NAME)
        .ancestors(BaseRuleClasses.NativeBuildRule.class)
        .factoryClass(Toolchain.class)
        .build();
  }
}
/*<!-- #BLAZE_RULE (NAME = toolchain, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>This rule declares a specific toolchain's type and constraints so that it can be selected
during toolchain resolution. See the
<a href="https://bazel.build/docs/toolchains">Toolchains</a> page for more
details.

<!-- #END_BLAZE_RULE -->*/
