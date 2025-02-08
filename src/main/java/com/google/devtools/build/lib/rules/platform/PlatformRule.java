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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.util.FileTypeSet;

/** Rule definition for {@link Platform}. */
public class PlatformRule implements RuleDefinition {
  public static final String RULE_NAME = "platform";
  public static final String CONSTRAINT_VALUES_ATTR = "constraint_values";
  public static final String PARENTS_PLATFORM_ATTR = "parents";
  public static final String REMOTE_EXECUTION_PROPS_ATTR = "remote_execution_properties";
  public static final String EXEC_PROPS_ATTR = "exec_properties";
  public static final String FLAGS_ATTR = "flags";
  public static final String REQUIRED_SETTINGS_ATTR = "required_settings";

  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    /* <!-- #BLAZE_RULE(platform).NAME -->
    <!-- #END_BLAZE_RULE.NAME --> */
    return builder
        .advertiseStarlarkProvider(PlatformInfo.PROVIDER.id())
        .exemptFromConstraintChecking("this rule helps *define* a constraint")
        .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
        .removeAttribute(":action_listener")
        .removeAttribute(RuleClass.APPLICABLE_METADATA_ATTR)
        .override(
            attr("tags", Types.STRING_LIST)
                // No need to show up in ":all", etc. target patterns.
                .value(ImmutableList.of("manual"))
                .nonconfigurable("low-level attribute, used in platform configuration"))
        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(constraint_values) -->
        The combination of constraint choices that this platform comprises. In order for a platform
        to apply to a given environment, the environment must have at least the values in this list.

        <p>Each <code>constraint_value</code> in this list must be for a different
        <code>constraint_setting</code>. For example, you cannot define a platform that requires the
        cpu architecture to be both <code>@platforms//cpu:x86_64</code> and
        <code>@platforms//cpu:arm</code>.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(CONSTRAINT_VALUES_ATTR, BuildType.LABEL_LIST)
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                .nonconfigurable("Part of the configuration"))

        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(parents) -->
        The label of a <code>platform</code> target that this platform should inherit from. Although
        the attribute takes a list, there should be no more than one platform present. Any
        constraint_settings not set directly on this platform will be found in the parent platform.
        See the section on <a href="#platform_inheritance">Platform Inheritance</a> for details.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(PARENTS_PLATFORM_ATTR, BuildType.LABEL_LIST)
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .mandatoryProviders(PlatformInfo.PROVIDER.id())
                .nonconfigurable("Part of the configuration"))

        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(remote_execution_properties) -->
        DEPRECATED. Please use exec_properties attribute instead.

        A string used to configure a remote execution platform. Actual builds make no attempt to
        interpret this, it is treated as opaque data that can be used by a specific SpawnRunner.
        This can include data from the parent platform's "remote_execution_properties" attribute,
        by using the macro "{PARENT_REMOTE_EXECUTION_PROPERTIES}". See the section on
        <a href="#platform_inheritance">Platform Inheritance</a> for details.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(REMOTE_EXECUTION_PROPS_ATTR, Type.STRING)
                .nonconfigurable("Part of the configuration"))

        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(exec_properties) -->
        A map of strings that affect the way actions are executed remotely. Bazel makes no attempt
        to interpret this, it is treated as opaque data that's forwarded via the Platform field of
        the  <a href="https://github.com/bazelbuild/remote-apis">remote execution protocol</a>.

        This includes any data from the parent platform's <code>exec_properties</code> attributes.
        If the child and parent platform define the same keys, the child's values are kept. Any
        keys associated with a value that is an empty string are removed from the dictionary.

        This attribute is a full replacement for the deprecated
        <code>remote_execution_properties</code>.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(EXEC_PROPS_ATTR, Types.STRING_DICT)
                .value(ImmutableMap.of())
                .nonconfigurable("Part of the configuration"))

        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(flags) -->
        A list of flags that will be enabled when this platform is used as the target platform in
        a configuration. Only flags that can be set in transitions are allowed to be used.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(FLAGS_ATTR, Types.STRING_LIST)
                .value(ImmutableList.of())
                .nonconfigurable("Part of the configuration"))
        /* <!-- #BLAZE_RULE(platform).ATTRIBUTE(required_settings) -->
        A list of <code>config_setting</code>s that must be satisfied by the target configuration
        in order for this platform to be used as an execution platform during toolchain resolution.

        Required settings are not inherited from parent platforms.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr(REQUIRED_SETTINGS_ATTR, BuildType.LABEL_LIST)
                .allowedRuleClasses("config_setting")
                .allowedFileTypes(FileTypeSet.NO_FILE))
        // Undocumented, used for exec platform migrations.
        .add(attr("check_toolchain_types", Type.BOOLEAN).value(false))
        .add(attr("allowed_toolchain_types", BuildType.NODEP_LABEL_LIST))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return Metadata.builder()
        .name(RULE_NAME)
        .ancestors(BaseRuleClasses.NativeBuildRule.class)
        .factoryClass(Platform.class)
        .build();
  }
}
/*<!-- #FAMILY_SUMMARY -->

<p>
This set of rules exists to allow you to model specific hardware platforms you are
building for and specify the specific tools you may need to compile code for those platforms.
The user should be familiar with the concepts explained <a href="/extending/platforms">here</a>.
</p>

<!-- #END_FAMILY_SUMMARY -->*/

/*<!-- #BLAZE_RULE (NAME = platform, FAMILY = Platforms and Toolchains)[GENERIC_RULE] -->

<p>This rule defines a new platform -- a named collection of constraint choices
(such as cpu architecture or compiler version) describing an environment in
which part of the build may run.

For more details, see the <a href="/extending/platforms">Platforms</a> page.


<h4 id="platform_examples">Example</h4>
<p>
  This defines a platform that describes any environment running Linux on ARM.
</p>
<pre class="code">
platform(
    name = "linux_arm",
    constraint_values = [
        "@platforms//os:linux",
        "@platforms//cpu:arm",
    ],
)
</pre>

<h3 id="platform_flags">Platform Flags</h3>
<p>
  Platforms may use the <code>flags</code> attribute to specify a list of flags that will be added
  to the configuration whenever the platform is used as the target platform (i.e., as the value of
  the <code>--platforms</code> flag).
</p>

<p>
  Flags set from the platform effectively have the highest precedence and overwrite any previous
  value for that flag, from the command line, rc file, or transition.
</p>

<h4 id="platform_flags_examples">Example</h4>

<pre class="code">
platform(
    name = "foo",
    flags = [
        "--dynamic_mode=fully",
        "--//bool_flag",
        "--no//package:other_bool_flag",
    ],
)
</pre>

<p>
  This defines a platform named <code>foo</code>. When this is the target platform (either because
  the user specified <code>--platforms//:foo</code>, because a transition set the
  <code>//command_line_option:platforms</code> flag to <code>["//:foo"]</code>, or because
  <code>//:foo</code> was used as an execution platform), then the given flags will be set in the
  configuration.
</p>

<h4 id=platform_flags_repeated>Platforms and Repeatable Flags</h4>

<p>
  Some flags will accumulate values when they are repeated, such as <code>--features</code>,
  <code>--copt</code>, any Starlark flag created as <code>config.string(repeatable = True)</code>.
  These flags are not compatible with setting the flags from the platform: instead, all previous
  values will be removed and overwritten with the values from the platform.
</p>

<p>
  As an example, given the following platform, the invocation <code>build --platforms=//:repeat_demo
  --features feature_a --features feature_b</code> will end up with the value of the
  <code>--feature</code> flag being <code>["feature_c", "feature_d"]</code>, removing the features
  set on the command line.
</p>

<pre class="code">
platform(
    name = "repeat_demo",
    flags = [
        "--features=feature_c",
        "--features=feature_d",
    ],
)
</pre>

<p>
  For this reason, it is discouraged to use repeatable flags in the <code>flags</code> attribute.
</p>

<h3 id="platform_inheritance">Platform Inheritance</h3>
<p>
  Platforms may use the <code>parents</code> attribute to specify another platform that they will
  inherit constraint values from. Although the <code>parents</code> attribute takes a list, no
  more than a single value is currently supported, and specifying multiple parents is an error.
</p>

<p>
  When checking for the value of a constraint setting in a platform, first the values directly set
  (via the <code>constraint_values</code> attribute) are checked, and then the constraint values on
  the parent. This continues recursively up the chain of parent platforms. In this manner, any
  values set directly on a platform will override the values set on the parent.
</p>

<p>
  Platforms inherit the <code>exec_properties</code> attribute from the parent platform.
  The dictionary entries in <code>exec_properties</code> of the parent and child platforms
  will be combined.
  If the same key appears in both the parent's and the child's <code>exec_properties</code>,
  the child's value will be used. If the child platform specifies an empty string as a value, the
  corresponding property will be unset.
</p>

<p>
  Platforms can also inherit the (deprecated) <code>remote_execution_properties</code> attribute
  from the parent platform. Note: new code should use <code>exec_properties</code> instead. The
  logic described below is maintained to be compatible with legacy behavior but will be removed
  in the future.

  The logic for setting the <code>remote_execution_platform</code> is as follows when there
  is a parent platform:

  <ol>
    <li>
      If <code>remote_execution_property</code> is not set on the child platform, the parent's
      <code>remote_execution_properties</code> will be used.
    </li>
    <li>
      If <code>remote_execution_property</code> is set on the child platform, and contains the
      literal string </code>{PARENT_REMOTE_EXECUTION_PROPERTIES}</code>, that macro will be
      replaced with the contents of the parent's <code>remote_execution_property</code> attribute.
    </li>
    <li>
      If <code>remote_execution_property</code> is set on the child platform, and does not contain
      the macro, the child's <code>remote_execution_property</code> will be used unchanged.
    </li>
  </ol>
</p>

<p>
  <em>Since <code>remote_execution_properties</code> is deprecated and will be phased out, mixing
  <code>remote_execution_properties</code> and <code>exec_properties</code> in the same
  inheritance chain is not allowed.</em>
  Prefer to use <code>exec_properties</code> over the deprecated
  <code>remote_execution_properties</code>.
</p>

<h4 id="platform_inheritance_examples">Example: Constraint Values</h4>
<pre class="code">
platform(
    name = "parent",
    constraint_values = [
        "@platforms//os:linux",
        "@platforms//cpu:arm",
    ],
)
platform(
    name = "child_a",
    parents = [":parent"],
    constraint_values = [
        "@platforms//cpu:x86_64",
    ],
)
platform(
    name = "child_b",
    parents = [":parent"],
)
</pre>

<p>
  In this example, the child platforms have the following properties:

  <ul>
    <li>
      <code>child_a</code> has the constraint values <code>@platforms//os:linux</code> (inherited
      from the parent) and <code>@platforms//cpu:x86_64</code> (set directly on the platform).
    </li>
    <li>
      <code>child_b</code> inherits all constraint values from the parent, and doesn't set any of
      its own.
    </li>
  </ul>
</p>

<h4 id="platform_inheritance_exec_examples">Example: Execution properties</h4>
<pre class="code">
platform(
    name = "parent",
    exec_properties = {
      "k1": "v1",
      "k2": "v2",
    },
)
platform(
    name = "child_a",
    parents = [":parent"],
)
platform(
    name = "child_b",
    parents = [":parent"],
    exec_properties = {
      "k1": "child"
    }
)
platform(
    name = "child_c",
    parents = [":parent"],
    exec_properties = {
      "k1": ""
    }
)
platform(
    name = "child_d",
    parents = [":parent"],
    exec_properties = {
      "k3": "v3"
    }
)
</pre>

<p>
  In this example, the child platforms have the following properties:

  <ul>
    <li>
      <code>child_a</code> inherits the "exec_properties" of the parent and does not set its own.
    </li>
    <li>
      <code>child_b</code> inherits the parent's <code>exec_properties</code> and overrides the
      value of <code>k1</code>. Its <code>exec_properties</code> will be:
      <code>{ "k1": "child", "k2": "v2" }</code>.
    </li>
    <li>
      <code>child_c</code> inherits the parent's <code>exec_properties</code> and unsets
      <code>k1</code>. Its <code>exec_properties</code> will be:
      <code>{ "k2": "v2" }</code>.
    </li>
    <li>
      <code>child_d</code> inherits the parent's <code>exec_properties</code> and adds a new
      property. Its <code>exec_properties</code> will be:
      <code>{ "k1": "v1",  "k2": "v2", "k3": "v3" }</code>.
    </li>
  </ul>
</p>

<!-- #END_BLAZE_RULE -->*/
