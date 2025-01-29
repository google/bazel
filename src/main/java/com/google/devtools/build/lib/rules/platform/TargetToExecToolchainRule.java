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
import static com.google.devtools.build.lib.rules.platform.ToolchainRule.EXEC_COMPATIBLE_WITH_ATTR;
import static com.google.devtools.build.lib.rules.platform.ToolchainRule.TARGET_COMPATIBLE_WITH_ATTR;
import static com.google.devtools.build.lib.rules.platform.ToolchainRule.TOOLCHAIN_ATTR;

import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;

/** Rule definition for {@link TargetToExecToolchain}. */
public class TargetToExecToolchainRule implements RuleDefinition {
  public static final String RULE_NAME = "target_to_exec_toolchain";

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    return builder
        .removeAttribute(EXEC_COMPATIBLE_WITH_ATTR)
        .removeAttribute(TARGET_COMPATIBLE_WITH_ATTR)
        /* <!-- #BLAZE_RULE(toolchain).ATTRIBUTE(toolchain) -->
        The target representing the actual tool or tool suite that is made available when this
        toolchain is selected. If the attribute is not specified, the toolchain is only useful to
        influence the selection of the execution platform, it does not provide any data itself.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        // This needs to not introduce a dependency so that we can load the toolchain only if it is
        // needed.
        .add(attr(TOOLCHAIN_ATTR, BuildType.NODEP_LABEL))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return Metadata.builder()
        .name(RULE_NAME)
        .ancestors(ToolchainRule.class)
        .factoryClass(TargetToExecToolchain.class)
        .build();
  }
}
/*<!-- #BLAZE_RULE (NAME = toolchain, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>This rule declares a specific toolchain's type and constraints so that it can be selected
during toolchain resolution. See the
<a href="https://bazel.build/docs/toolchains">Toolchains</a> page for more
details.

<!-- #END_BLAZE_RULE -->*/
