// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.config;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_KEYED_STRING_DICT;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_DICT;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.AllowlistChecker;
import com.google.devtools.build.lib.packages.Attribute.AllowedValueSet;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;

/**
 * Definitions for rule classes that specify or manipulate configuration settings.
 *
 * <p>These are not "traditional" rule classes in that they can't be requested as top-level
 * targets and don't translate input artifacts into output artifacts. Instead, they affect
 * how *other* rules work. See individual class comments for details.
 */
public class ConfigRuleClasses {

  private static final String NONCONFIGURABLE_ATTRIBUTE_REASON =
      "part of a rule class that *triggers* configurable behavior";

  /**
   * Common settings for all configurability rules.
   */
  public static final class ConfigBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .override(
              attr("tags", Types.STRING_LIST)
                  // No need to show up in ":all", etc. target patterns.
                  .value(ImmutableList.of("manual"))
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
          .exemptFromConstraintChecking(
              "these rules don't include content that gets built into their dependers")
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$config_base_rule")
          .type(RuleClass.Builder.RuleClassType.ABSTRACT)
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .build();
    }
  }

  /**
   * A named "partial configuration setting" that specifies a set of command-line "flag=value"
   * bindings.
   *
   * <p>For example:
   *
   * <pre>
   *   config_setting(
   *       name = 'foo',
   *       values = {
   *           'flag1': 'aValue'
   *           'flag2': 'bValue'
   *       })
   * </pre>
   *
   * <p>declares a setting that binds command-line flag
   *
   * <pre>flag1</pre>
   *
   * to value
   *
   * <pre>aValue</pre>
   *
   * and
   *
   * <pre>flag2</pre>
   *
   * to
   *
   * <pre>bValue</pre>
   *
   * .
   *
   * <p>This is used by configurable attributes to determine which branch to follow based on which
   *
   * <pre>config_setting</pre>
   *
   * instance matches all its flag values in the configurable attribute owner's configuration.
   *
   * <p>This rule isn't accessed through the standard {@link
   * com.google.devtools.build.lib.analysis.RuleContext#getPrerequisites} interface. This is because
   * Bazel constructs a rule's configured attribute map *before* its {@link
   * com.google.devtools.build.lib.analysis.RuleContext} is created (in fact, the map is an input to
   * the context's constructor). And the config_settings referenced by the rule's configurable
   * attributes are themselves inputs to that map. So Bazel has special logic to read and properly
   * apply config_setting instances. See {@link
   * com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction#getConfigConditions} for
   * details.
   */
  public static final class ConfigSettingRule implements RuleDefinition {
    /**
     * The name of this rule.
     */
    public static final String RULE_NAME = "config_setting";

    /** The name of the attribute that declares flag bindings. */
    public static final String SETTINGS_ATTRIBUTE = "values";
    /** The name of the attribute that declares "--define foo=bar" flag bindings.*/
    public static final String DEFINE_SETTINGS_ATTRIBUTE = "define_values";
    /** The name of the attribute that declares user-defined flag bindings. */
    public static final String FLAG_SETTINGS_ATTRIBUTE = "flag_values";
    /** The name of the attribute that declares constraint_values. */
    public static final String CONSTRAINT_VALUES_ATTRIBUTE = "constraint_values";

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setIgnoreLicenses()
          .toolchainResolutionMode(
              (rule) -> {
                RawAttributeMapper attr = RawAttributeMapper.of(rule);
                return attr.has(CONSTRAINT_VALUES_ATTRIBUTE)
                    && !attr.get(CONSTRAINT_VALUES_ATTRIBUTE, LABEL_LIST).isEmpty();
              })

          /* <!-- #BLAZE_RULE(config_setting).ATTRIBUTE(values) -->
          The set of configuration values that match this rule (expressed as build flags)

          <p>This rule inherits the configuration of the configured target that
            references it in a <code>select</code> statement. It is considered to
            "match" a Bazel invocation if, for every entry in the dictionary, its
            configuration matches the entry's expected value. For example
            <code>values = {"compilation_mode": "opt"}</code> matches the invocations
            <code>bazel build --compilation_mode=opt ...</code> and
            <code>bazel build -c opt ...</code> on target-configured rules.
          </p>

          <p>For convenience's sake, configuration values are specified as build flags (without
            the preceding <code>"--"</code>). But keep in mind that the two are not the same. This
            is because targets can be built in multiple configurations within the same
            build. For example, an exec configuration's "cpu" matches the value of
            <code>--host_cpu</code>, not <code>--cpu</code>. So different instances of the
            same <code>config_setting</code> may match the same invocation differently
            depending on the configuration of the rule using them.
          </p>

          <p>If a flag is not explicitly set at the command line, its default value is used.
             If a key appears multiple times in the dictionary, only the last instance is used.
             If a key references a flag that can be set multiple times on the command line (e.g.
             <code>bazel build --copt=foo --copt=bar --copt=baz ...</code>), a match occurs if
             <i>any</i> of those settings match.
          <p>
          <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
          .add(
              attr(SETTINGS_ATTRIBUTE, STRING_DICT)
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
          /* <!-- #BLAZE_RULE(config_setting).ATTRIBUTE(define_values) -->
          The same as <a href="${link config_setting.values}"><code>values</code></a> but
          specifically for the <code>--define</code> flag.

          <p><code>--define</code> is special because its syntax (<code>--define KEY=VAL</code>)
            means <code>KEY=VAL</code> is a <i>value</i> from a Bazel flag perspective.
          </p>

          <p>That means:

          <pre class="code">
            config_setting(
                name = "a_and_b",
                values = {
                    "define": "a=1",
                    "define": "b=2",
                })
          </pre>

          <p>doesn't work because the same key (<code>define</code>) appears twice in the
          dictionary. This attribute solves that problem:

          <pre class="code">
            config_setting(
                name = "a_and_b",
                define_values = {
                    "a": "1",
                    "b": "2",
                })
          </pre>

          <p>correctly matches <code>bazel build //foo --define a=1 --define b=2</code>.

          <p><code>--define</code> can still appear in
          <a href="${link config_setting.values}"><code>values</code></a> with normal flag syntax,
          and can be mixed freely with this attribute as long as dictionary keys remain distinct.
          <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
          .add(
              attr(DEFINE_SETTINGS_ATTRIBUTE, STRING_DICT)
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))

          /* <!-- #BLAZE_RULE(config_setting).ATTRIBUTE(flag_values) -->
          The same as <a href="${link config_setting.values}"><code>values</code></a> but
          for <a href="https://bazel.build/rules/config#user-defined-build-settings">
          user-defined build flags</a>.

          <p>This is a distinct attribute because user-defined flags are referenced as labels while
          built-in flags are referenced as arbitrary strings.

          <!-- #END_BLAZE_RULE.ATTRIBUTE --> */

          // Originally this attribute was a map of feature flags targets -> feature flag values,
          // the latter of which are always strings. Now it also includes starlark build setting
          // targets -> starlark build setting values. In other places in the starlark configuration
          // API, starlark setting values are passed as their actual object instead of a string
          // representation. It would be more consistent to be able to pass starlark setting values
          // as objects to this attribute as well. But attributes are strongly-typed so
          // label->object dict is not an option for attribute types right now.
          .add(
              attr(FLAG_SETTINGS_ATTRIBUTE, LABEL_KEYED_STRING_DICT)
                  .allowedFileTypes()
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
          /* <!-- #BLAZE_RULE(config_setting).ATTRIBUTE(constraint_values) -->
          The minimum set of <code>constraint_values</code> that the target platform must specify
          in order to match this <code>config_setting</code>. (The execution platform is not
          considered here.) Any additional constraint values that the platform has are ignored. See
          <a href="https://bazel.build/docs/configurable-attributes#platforms">
          Configurable Build Attributes</a> for details.

          <p>If two <code>config_setting</code>s match in the same <code>select</code> and one has
          all the same flags and <code>constraint_setting</code>s as the other plus additional ones,
          the one with more settings is chosen. This is known as "specialization". For example,
          a <code>config_setting</code> matching <code>x86</code> and <code>Linux</code> specializes
          a <code>config_setting</code> matching <code>x86</code>.

          <p>If two <code>config_setting</code>s match and both have <code>constraint_value</code>s
          not present in the other, this is an error.
          <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
          .add(
              attr(CONSTRAINT_VALUES_ATTRIBUTE, LABEL_LIST)
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON)
                  .allowedFileTypes())
          .setOptionReferenceFunctionForConfigSettingOnly(
              rule ->
                  NonconfigurableAttributeMapper.of(rule)
                      .get(SETTINGS_ATTRIBUTE, Types.STRING_DICT)
                      .keySet())
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name(RULE_NAME)
          .type(RuleClass.Builder.RuleClassType.NORMAL)
          .ancestors(ConfigBaseRule.class)
          .factoryClass(ConfigSetting.class)
          .build();
    }
  }
  /*<!-- #BLAZE_RULE (NAME = config_setting, FAMILY = General)[GENERIC_RULE] -->

  <p>
    Matches an expected configuration state (expressed as build flags or platform constraints) for
    the purpose of triggering configurable attributes. See <a href="${link select}">select</a> for
    how to consume this rule and <a href="${link common-definitions#configurable-attributes}">
    Configurable attributes</a> for an overview of the general feature.

  <h4 id="config_setting_examples">Examples</h4>

  <p>The following matches any build that sets <code>--compilation_mode=opt</code> or
  <code>-c opt</code> (either explicitly at the command line or implicitly from .bazelrc files):
  </p>

  <pre class="code">
  config_setting(
      name = "simple",
      values = {"compilation_mode": "opt"}
  )
  </pre>

  <p>The following matches any build that targets ARM and applies the custom define
  <code>FOO=bar</code> (for instance, <code>bazel build --cpu=arm --define FOO=bar ...</code>):
  </p>

  <pre class="code">
  config_setting(
      name = "two_conditions",
      values = {
          "cpu": "arm",
          "define": "FOO=bar"
      }
  )
  </pre>

  <p>The following matches any build that sets
     <a href="https://bazel.build/rules/config#user-defined-build-settings">user-defined flag</a>
     <code>--//custom_flags:foo=1</code> (either explicitly at the command line or implicitly from
     .bazelrc files):
  </p>

  <pre class="code">
  config_setting(
      name = "my_custom_flag_is_set",
      flag_values = { "//custom_flags:foo": "1" },
  )
  </pre>

  <p>The following matches any build that targets a platform with an x86_64 architecture and glibc
     version 2.25, assuming the existence of a <code>constraint_value</code> with label
     <code>//example:glibc_2_25</code>. Note that a platform still matches if it defines additional
     constraint values beyond these two.
  </p>

  <pre class="code">
  config_setting(
      name = "64bit_glibc_2_25",
      constraint_values = [
          "@platforms//cpu:x86_64",
          "//example:glibc_2_25",
      ]
  )
  </pre>

  In all these cases, it's possible for the configuration to change within the build, for example if
  a target needs to be built for a different platform than its dep. This means that even when a
  <code>config_setting</code> doesn't match the top-level command-line flags, it may still match
  some build targets.

  <h4 id="config_setting_notes">Notes</h4>
  <ul>
    <li>See <a href="${link select}">select</a> for what happens when multiple
       <code>config_setting</code>s match the current configuration state.
    </li>

    <li>For flags that support shorthand forms (e.g. <code>--compilation_mode</code> vs.
      <code>-c</code>), <code>values</code> definitions must use the full form. These automatically
      match invocations using either form.
    </li>

    <li>
      If a flag takes multiple values (like <code>--copt=-Da --copt=-Db</code> or a list-typed
      <a href="https://bazel.build/rules/config#user-defined-build-settings">
      Starlark flag</a>), <code>values = { "flag": "a" }</code> matches if <code>"a"</code> is
      present <i>anywhere</i> in the actual list.

      <p>
        <code>values = { "myflag": "a,b" }</code> works the same way: this matches
        <code>--myflag=a --myflag=b</code>, <code>--myflag=a --myflag=b --myflag=c</code>,
        <code>--myflag=a,b</code>, and <code>--myflag=c,b,a</code>. Exact semantics vary between
        flags. For example, <code>--copt</code> doesn't support multiple values <i>in the same
        instance</i>: <code>--copt=a,b</code> produces <code>["a,b"]</code> while <code>--copt=a
        --copt=b</code> produces <code>["a", "b"]</code> (so <code>values = { "copt": "a,b" }</code>
        matches the former but not the latter). But <code>--ios_multi_cpus</code> (for Apple rules)
        <i>does</i>: <code>-ios_multi_cpus=a,b</code> and <code>ios_multi_cpus=a --ios_multi_cpus=b
        </code> both produce <code>["a", "b"]</code>. Check flag definitions and test your
        conditions carefully to verify exact expectations.
      </p>
    </li>

    <li>If you need to define conditions that aren't modeled by built-in build flags, use
      <a href="https://bazel.build/rules/config#user-defined-build-settings">
      Starlark-defined flags</a>. You can also use <code>--define</code>, but this offers weaker
      support and is not recommended. See
      <a href="${link common-definitions#configurable-attributes}">here</a> for more discussion.
    </li>

    <li>Avoid repeating identical <code>config_setting</code> definitions in different packages.
      Instead, reference a common <code>config_setting</code> that defined in a canonical package.
    </li>

    <li><a href="general.html#config_setting.values"><code>values</code></a>,
       <a href="general.html#config_setting.define_values"><code>define_values</code></a>, and
       <a href="general.html#config_setting.constraint_values"><code>constraint_values</code></a>
       can be used in any combination in the same <code>config_setting</code> but at least one must
       be set for any given <code>config_setting</code>.
    </li>
  </ul>
  <!-- #END_BLAZE_RULE -->*/

  /** Rule definition for Android's config_feature_flag rule. */
  public static final class ConfigFeatureFlagRule implements RuleDefinition {
    public static final String RULE_NAME = "config_feature_flag";

    @SerializationConstant @VisibleForSerialization
    static final AllowlistChecker ALWAYS_CHECK_ALLOWLIST =
        AllowlistChecker.builder()
            .setAllowlistAttr(ConfigFeatureFlag.ALLOWLIST_NAME)
            .setErrorMessage("the config_feature_flag rule is not available in this package")
            .setLocationCheck(AllowlistChecker.LocationCheck.INSTANCE)
            .build();

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented(/* the feature flag feature has not yet been launched */ )
          // Some rule has to ask for ConfigFeatureFlagConfiguration.class so that it is retained.
          .requiresConfigurationFragments(ConfigFeatureFlagConfiguration.class)
          .add(
              attr("allowed_values", STRING_LIST)
                  .mandatory()
                  .nonEmpty()
                  .orderIndependent()
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
          .add(attr("default_value", STRING).nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON))
          .add(
              attr("scope", STRING)
                  .value("universal")
                  .nonconfigurable(NONCONFIGURABLE_ATTRIBUTE_REASON)
                  .allowedValues(new AllowedValueSet("universal", "project")))
          .add(ConfigFeatureFlag.getAllowlistAttribute(env))
          .addAllowlistChecker(ALWAYS_CHECK_ALLOWLIST)
          .removeAttribute(BaseRuleClasses.TAGGED_TRIMMING_ATTR)
          .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
          .build();
    }

    @Override
    public RuleDefinition.Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name(RULE_NAME)
          .ancestors(ConfigBaseRule.class)
          .factoryClass(ConfigFeatureFlag.class)
          .build();
    }
  }
}
