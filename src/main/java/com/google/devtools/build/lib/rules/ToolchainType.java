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

package com.google.devtools.build.lib.rules;

import static com.google.devtools.build.lib.packages.Attribute.attr;

import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.packages.Type;
import javax.annotation.Nullable;

/**
 * Implementation of {@code toolchain_type}.
 */
public class ToolchainType implements RuleConfiguredTargetFactory {

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws ActionConflictException, InterruptedException {

    String noMatchError = ruleContext.attributes().get("no_match_error", Type.STRING);
    ToolchainTypeInfo toolchainTypeInfo =
        ToolchainTypeInfo.create(
            ruleContext.getLabel(), noMatchError.isEmpty() ? null : noMatchError);

    return new RuleConfiguredTargetBuilder(ruleContext)
        .addProvider(RunfilesProvider.simple(Runfiles.EMPTY))
        .addNativeDeclaredProvider(toolchainTypeInfo)
        .build();
  }

  /** Definition for {@code toolchain_type}. */
  public static class ToolchainTypeRule implements RuleDefinition {

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return builder
          .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
          .advertiseStarlarkProvider(ToolchainTypeInfo.PROVIDER.id())
          .removeAttribute("licenses")
          .removeAttribute("distribs")
          .removeAttribute(":action_listener")
          /*<!-- #BLAZE_RULE(toolchain_type).ATTRIBUTE(no_match_error) -->
          A custom error message to display when no matching toolchain is found for this type.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(
              attr("no_match_error", Type.STRING)
                  .nonconfigurable("low-level attribute, used in platform configuration"))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return Metadata.builder()
          .name("toolchain_type")
          .factoryClass(ToolchainType.class)
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .build();
    }
  }
}
/*<!-- #BLAZE_RULE (NAME = toolchain_type, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>
  This rule defines a new type of toolchain -- a simple target that represents a class of tools that
  serve the same role for different platforms.
</p>

<p>
  See the <a href="${link toolchains}">Toolchains</a> page for more details.
</p>

<h4 id="toolchain_type_examples">Example</h4>
<p>
  This defines a toolchain type for a custom rule.
</p>
<pre class="code">
toolchain_type(
    name = "bar_toolchain_type",
)
</pre>

<p>
  This can be used in a bzl file.
</p>
<pre class="code">
bar_binary = rule(
    implementation = _bar_binary_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        ...
        # No `_compiler` attribute anymore.
    },
    toolchains = ["//bar_tools:toolchain_type"]
)
</pre>
<!-- #END_BLAZE_RULE -->*/
