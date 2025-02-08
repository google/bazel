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
package com.google.devtools.build.lib.rules;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL;
import static com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME;
import static com.google.devtools.build.lib.packages.Type.STRING;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute.AllowedValueSet;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.BuildSetting;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.rules.LateBoundAlias.AbstractAliasRule;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import net.starlark.java.eval.Starlark;

/**
 * Native implementation of label setting and flags.
 *
 * <p>While most build settings are completely defined in starlark, we're natively defining
 * label-typed ones because:
 *
 * <ul>
 *   <li>they're essentially special Alias targets
 *   <li>we don't have a known use case where you'd want to manipulate a label-typed build setting
 *       in its implementation section.
 * </ul>
 *
 * <p>Once we do have (2), we can consider switching over to starlark implementation. The dangers
 * there involve the implementation function returning a label we've never seen before in the build.
 * And since label-typed build settings actually return the providers of the targets they point to,
 * we'd have to be able to load and configure potentially arbitrary labels on the fly. This is not
 * possible today and could easily introduce large performance issues.
 */
public final class LabelBuildSettings {
  @SerializationConstant @VisibleForSerialization
  static final String NONCONFIGURABLE_ATTRIBUTE_REASON =
      "part of a rule class that *triggers* configurable behavior";

  // TODO(b/65746853): find a way to do this without passing the entire BuildConfigurationValue
  static final LabelLateBoundDefault<BuildConfigurationValue> ACTUAL =
      LabelLateBoundDefault.fromTargetConfigurationWithRuleBasedDefault(
          BuildConfigurationValue.class,
          (rule) ->
              // RawAttributeMapper means this attribute can't be select()able (which it isn't).
              RawAttributeMapper.of(rule)
                  .get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL),
          (rule, attributes, configuration) -> {
            if (rule == null || configuration == null) {
              return attributes.get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL);
            }
            Object commandLineValue =
                configuration.getOptions().getStarlarkOptions().get(rule.getLabel());
            if (commandLineValue == null) {
              return attributes.get(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME, NODEP_LABEL);
            }
            Preconditions.checkState(
                commandLineValue instanceof Label,
                "the value of %s should have been converted to a label already, but its type is %s",
                rule.getLabel(),
                Starlark.type(commandLineValue));
            return (Label) commandLineValue;
          });

  private static RuleClass buildRuleClass(RuleClass.Builder builder, boolean flag) {
    return builder
        .removeAttribute("licenses")
        .removeAttribute("distribs")
        .removeAttribute(":action_listener")
        .add(attr(":alias", LABEL).value(ACTUAL))
        .add(
            attr("scope", STRING)
                .value("universal")
                .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON)
                .allowedValues(new AllowedValueSet("universal", "project")))
        .setBuildSetting(BuildSetting.create(flag, NODEP_LABEL))
        .canHaveAnyProvider()
        .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
        .build();
  }

  /** Rule definition of label_setting. */
  public static final class LabelBuildSettingRule extends AbstractAliasRule {

    public LabelBuildSettingRule() {
      super("label_setting");
    }

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return buildRuleClass(builder, /* flag= */ false);
    }
  }

  /** Rule definition of label_flag */
  public static final class LabelBuildFlagRule extends AbstractAliasRule {

    public LabelBuildFlagRule() {
      super("label_flag");
    }

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return buildRuleClass(builder, /* flag= */ true);
    }
  }

  private LabelBuildSettings() {}
}
