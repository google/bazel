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

package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.DISTRIBUTIONS;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LICENSE;
import static com.google.devtools.build.lib.packages.BuildType.NODEP_LABEL_LIST;
import static com.google.devtools.build.lib.packages.RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP;
import static com.google.devtools.build.lib.packages.RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.INTEGER;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_DICT;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.RunUnder.LabelRunUnder;
import com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition;
import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.test.CoverageConfiguration;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.Attribute.LabelListLateBoundDefault;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault.Resolver;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.FileTypeSet;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkInt;

/**
 * Rule class definitions used by (almost) every rule.
 */
public class BaseRuleClasses {

  private BaseRuleClasses() {}

  @SerializationConstant @VisibleForSerialization
  static final Attribute.ComputedDefault testonlyDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageArgs().defaultTestOnly();
        }
      };

  @SerializationConstant @VisibleForSerialization
  static final Attribute.ComputedDefault deprecationDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageArgs().defaultDeprecation();
        }
      };

  @SerializationConstant @VisibleForSerialization
  public static final Attribute.ComputedDefault TIMEOUT_DEFAULT =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          TestSize size = TestSize.getTestSize(rule.get("size", Type.STRING));
          if (size != null) {
            String timeout = size.getDefaultTimeout().toString();
            if (timeout != null) {
              return timeout;
            }
          }
          return "illegal";
        }
      };

  @SerializationConstant @VisibleForSerialization
  public static final Attribute.ComputedDefault packageMetadataDefault =
      new Attribute.ComputedDefault() {
        @Override
        public Object getDefault(AttributeMap rule) {
          return rule.getPackageArgs().defaultPackageMetadata();
        }
      };

  // TODO(b/65746853): provide a way to do this without passing the entire configuration
  /**
   * Implementation for the :action_listener attribute.
   *
   * <p>action_listeners are special rules; they tell the build system to add extra_actions to
   * existing rules. As such they need an edge to every ConfiguredTarget with the limitation that
   * they only run on the target configuration and should not operate on action_listeners and
   * extra_actions themselves (to avoid cycles).
   */
  @SerializationConstant @VisibleForSerialization @VisibleForTesting
  static final LabelListLateBoundDefault<?> ACTION_LISTENER =
      LabelListLateBoundDefault.fromTargetConfiguration(
          BuildConfigurationValue.class,
          (rule, attributes, configuration) -> configuration.getActionListeners());

  public static final String DEFAULT_COVERAGE_SUPPORT_VALUE = "//tools/test:coverage_support";

  @SerializationConstant @VisibleForSerialization
  static final Resolver<TestConfiguration, Label> COVERAGE_SUPPORT_CONFIGURATION_RESOLVER =
      (rule, attributes, configuration) -> configuration.getCoverageSupport();

  public static LabelLateBoundDefault<TestConfiguration> coverageSupportAttribute(
      Label defaultValue) {
    return LabelLateBoundDefault.fromTargetConfiguration(
        TestConfiguration.class, defaultValue, COVERAGE_SUPPORT_CONFIGURATION_RESOLVER);
  }

  public static final String DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE =
      "//tools/test:coverage_report_generator";

  @SerializationConstant @VisibleForSerialization
  static final Resolver<CoverageConfiguration, Label>
      COVERAGE_REPORT_GENERATOR_CONFIGURATION_RESOLVER =
          (rule, attributes, configuration) -> configuration.reportGenerator();

  public static LabelLateBoundDefault<CoverageConfiguration> coverageReportGeneratorAttribute(
      Label defaultValue) {
    return LabelLateBoundDefault.fromTargetConfiguration(
        CoverageConfiguration.class,
        defaultValue,
        COVERAGE_REPORT_GENERATOR_CONFIGURATION_RESOLVER);
  }

  public static LabelLateBoundDefault<CoverageConfiguration> getCoverageOutputGeneratorLabel() {
    return LabelLateBoundDefault.fromTargetConfiguration(
        CoverageConfiguration.class, null, COVERAGE_OUTPUT_GENERATOR_RESOLVER);
  }

  @SerializationConstant @VisibleForSerialization
  static final Resolver<CoverageConfiguration, Label> COVERAGE_OUTPUT_GENERATOR_RESOLVER =
      (rule, attributes, configuration) -> configuration.outputGenerator();

  // TODO(b/65746853): provide a way to do this without passing the entire configuration
  /**
   * Resolves the latebound exec-configured :run_under label. This only exists if --run_under is set
   * to a label and --incompatible_bazel_test_exec_run_under is true. Else it's null.
   *
   * <p>{@link RUN_UNDER_EXEC_CONFIG} and {@link RUN_UNDER_TARGET_CONFIG} cannot both be non-null in
   * a build: if {@code --run_under} is set it must connect to a single dependency.
   */
  @SerializationConstant @VisibleForSerialization
  public static final LabelLateBoundDefault<?> RUN_UNDER_EXEC_CONFIG =
      LabelLateBoundDefault.fromTargetConfiguration(
          BuildConfigurationValue.class,
          null,
          (rule, attributes, config) -> {
            if (config.isExecConfiguration()
                // This is the opposite of RUN_UNDER_TARGET_CONFIG, so both can't be non-null.
                || !config.runUnderExecConfigForTests()
                || config.getRunUnder() == null) {
              return null;
            }
            return config.getRunUnder() instanceof LabelRunUnder runUnder ? runUnder.label() : null;
          });

  // TODO(b/65746853): provide a way to do this without passing the entire configuration
  /**
   * Resolves the latebound target-configured :run_under label. This only exists if --run_under is
   * set to a label and --incompatible_bazel_test_exec_run_under is false. Else it's null.
   *
   * <p>{@link RUN_UNDER_EXEC_CONFIG} and {@link RUN_UNDER_TARGET_CONFIG} cannot both be non-null in
   * a build: if {@code --run_under} is set it must connect to a single dependency.
   */
  public static final LabelLateBoundDefault<?> RUN_UNDER_TARGET_CONFIG =
      LabelLateBoundDefault.fromTargetConfiguration(
          BuildConfigurationValue.class,
          null,
          (rule, attributes, config) -> {
            if (config.isExecConfiguration()
                // This is the opposite of RUN_UNDER_EXEC_CONFIG, so both can't be non-null.
                || config.runUnderExecConfigForTests()
                || config.getRunUnder() == null) {
              return null;
            }
            return config.getRunUnder() instanceof LabelRunUnder runUnder ? runUnder.label() : null;
          });

  /**
   * A base rule for all test rules.
   */
  public static final class TestBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      builder
          .addExecGroups(
              ImmutableMap.of(DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME, DEFAULT_TEST_RUNNER_EXEC_GROUP))
          .requiresConfigurationFragments(TestConfiguration.class)
          // TestConfiguration only needed to create TestAction and TestProvider
          // Only necessary at top-level and can be skipped if trimmed.
          .setMissingFragmentPolicy(TestConfiguration.class, MissingFragmentPolicy.IGNORE)
          .add(
              attr("size", STRING)
                  .value("medium")
                  .taggable()
                  .nonconfigurable("policy decision: should be consistent across configurations"))
          .add(
              attr("timeout", STRING)
                  .taggable()
                  .nonconfigurable("policy decision: should be consistent across configurations")
                  .value(TIMEOUT_DEFAULT))
          .add(
              attr("flaky", BOOLEAN)
                  .value(false)
                  .taggable()
                  .nonconfigurable("policy decision: should be consistent across configurations"))
          .add(attr("shard_count", INTEGER).value(StarlarkInt.of(-1)))
          .add(
              attr("local", BOOLEAN)
                  .value(false)
                  .taggable()
                  .nonconfigurable("policy decision: should be consistent across configurations"))
          .add(attr("args", STRING_LIST))
          .add(attr("env", STRING_DICT))
          .add(attr("env_inherit", STRING_LIST))
          .add(
              attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                  .value(true)
                  .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target"))
          // Input files for every test action
          .add(
              attr("$test_wrapper", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .singleArtifact()
                  .value(env.getToolsLabel("//tools/test:test_wrapper")))
          .add(
              attr("$xml_writer", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .singleArtifact()
                  .value(env.getToolsLabel("//tools/test:xml_writer")))
          .add(
              attr("$test_runtime", LABEL_LIST)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .value(getTestRuntimeLabelList(env)))
          .add(
              attr("$test_setup_script", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .singleArtifact()
                  .value(env.getToolsLabel("//tools/test:test_setup")))
          .add(
              attr("$xml_generator_script", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .singleArtifact()
                  .value(env.getToolsLabel("//tools/test:test_xml_generator")))
          .add(
              attr("$collect_coverage_script", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .singleArtifact()
                  .value(env.getToolsLabel("//tools/test:collect_coverage")))
          // Input files for test actions collecting code coverage
          .add(
              attr(":coverage_support", LABEL)
                  .value(
                      coverageSupportAttribute(env.getToolsLabel(DEFAULT_COVERAGE_SUPPORT_VALUE))))
          // Used in the one-per-build coverage report generation action.
          .add(
              attr(":coverage_report_generator", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory())
                  .value(
                      coverageReportGeneratorAttribute(
                          env.getToolsLabel(DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE))))
          // Bazel runs --run_under targets on exec machines:
          //   * For "$ bazel run", they run directly on the machine running bazel.
          //   * For "$ bazel test", they run on the build machine that executes tests.
          //
          // This means they should be configured for the exec configuration.
          // --incompatible_bazel_test_exec_run_under supports this. But we still need to support
          // legacy invocations that incorrectly configure it with the target configuration. To
          // support both modes, we define both an exec-configured and target-configured --run_under
          // dep and have consuming logic choose the right one based on the flag.
          //
          // TODO: https://github.com/bazelbuild/bazel/discussions/21805 this works for
          // "$ bazel test" but not "$ bazel run". Make this work for "$ bazel run" by updating
          // RunCommand.java to self-transition --run_under to the exec configuration.
          .add(
              attr(":run_under_exec_config", LABEL)
                  .cfg(ExecutionTransitionFactory.createFactory("test"))
                  .value(RUN_UNDER_EXEC_CONFIG)
                  .skipPrereqValidatorCheck())
          .add(
              attr(":run_under_target_config", LABEL)
                  .value(RUN_UNDER_TARGET_CONFIG)
                  .skipPrereqValidatorCheck());
      env.getNetworkAllowlistForTests()
          .ifPresent(
              label ->
                  builder.add(
                      Allowlist.getAttributeFromAllowlistName("external_network").value(label)));

      return builder.build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$test_base_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(MakeVariableExpandingRule.class)
          .build();
    }
  }

  private static final String TOOLS_TEST_RUNTIME_TARGET_PATTERN = "//tools/test:runtime";
  private static ImmutableList<Label> testRuntimeLabelList = null;

  // Always return the same ImmutableList<Label> for every $test_runtime attribute's default value.
  public static synchronized ImmutableList<Label> getTestRuntimeLabelList(
      RuleDefinitionEnvironment env) {
    if (testRuntimeLabelList == null) {
      testRuntimeLabelList =
          ImmutableList.of(
              Label.parseCanonicalUnchecked(
                  env.getToolsRepository() + TOOLS_TEST_RUNTIME_TARGET_PATTERN));
    }
    return testRuntimeLabelList;
  }

  /**
   * The attribute used to list the configuration properties used by a target and its transitive
   * dependencies. Currently only supports config_feature_flag.
   *
   * <p>A special value of "//command_line_option/fragments:test" instructs
   * TestTrimmingTransitionFactory to skip trimming for this rule.
   */
  public static final String TAGGED_TRIMMING_ATTR = "transitive_configs";

  /** Share common attributes across both base and Starlark base rules. */
  // TODO(bazel-team): replace this with a common RuleDefinition ancestor of NativeBuildRule
  // and StarlarkRuleClassFunctions.baseRule. This requires refactoring StarlarkRuleClassFunctions
  // to instantiate its RuleClasses through RuleDefinition.
  public static RuleClass.Builder commonCoreAndStarlarkAttributes(RuleClass.Builder builder) {
    return builder
        // The visibility attribute is special: it is a nodep label, and loading the
        // necessary package groups is handled by {@link LabelVisitor#visitTargetVisibility}.
        // Package groups always have the null configuration so that they are not duplicated
        // needlessly.
        .add(
            attr("visibility", NODEP_LABEL_LIST)
                .orderIndependent()
                .cfg(ExecutionTransitionFactory.createFactory())
                .nonconfigurable(
                    "special attribute integrated more deeply into Bazel's core logic"))
        .add(
            attr(TAGGED_TRIMMING_ATTR, NODEP_LABEL_LIST)
                .orderIndependent()
                .nonconfigurable("Used in determining configuration"))
        .add(
            attr("deprecation", STRING)
                .value(deprecationDefault)
                .nonconfigurable("Used in core loading phase logic with no access to configs"))
        .add(
            attr("tags", STRING_LIST)
                .orderIndependent()
                .taggable()
                .nonconfigurable("low-level attribute, used in TargetUtils without configurations"))
        .add(
            attr("generator_name", STRING)
                .undocumented("internal")
                .nonconfigurable("static structure of a rule"))
        .add(
            attr("generator_function", STRING)
                .undocumented("internal")
                .nonconfigurable("static structure of a rule"))
        .add(
            attr("generator_location", STRING)
                .undocumented("internal")
                .nonconfigurable("static structure of a rule"))
        .add(
            attr("testonly", BOOLEAN)
                .value(testonlyDefault)
                .nonconfigurable("policy decision: rules testability should be consistent"))
        .add(attr("features", STRING_LIST).orderIndependent())
        .add(
            attr(":action_listener", LABEL_LIST)
                .cfg(ExecutionTransitionFactory.createFactory())
                .value(ACTION_LISTENER))
        .add(
            attr(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR, LABEL_LIST)
                .allowedRuleClasses(ConstraintConstants.ENVIRONMENT_RULE)
                .cfg(NoConfigTransition.getFactory())
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .dontCheckConstraints()
                .nonconfigurable(
                    "special logic for constraints and select: see ConstraintSemantics"))
        .add(
            attr(RuleClass.RESTRICTED_ENVIRONMENT_ATTR, LABEL_LIST)
                .allowedRuleClasses(ConstraintConstants.ENVIRONMENT_RULE)
                .cfg(NoConfigTransition.getFactory())
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .dontCheckConstraints()
                .nonconfigurable(
                    "special logic for constraints and select: see ConstraintSemantics"))
        .add(
            attr(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE, LABEL_LIST)
                .nonconfigurable("stores configurability keys"))
        .add(
            attr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST)
                .value(packageMetadataDefault)
                .cfg(NoConfigTransition.getFactory())
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .dontCheckConstraints()
                .nonconfigurable("applicable_metadata is not configurable"))
        .add(
            attr("aspect_hints", LABEL_LIST)
                .allowedFileTypes(FileTypeSet.NO_FILE)
                .undocumented("experimental attribute"));
  }

  public static RuleClass.Builder execPropertiesAttribute(RuleClass.Builder builder)
      throws ConversionException {
    return builder.add(
        attr(RuleClass.EXEC_PROPERTIES_ATTR, STRING_DICT).defaultValue(ImmutableMap.of()));
  }

  /**
   * Ancestor of every native rule in BUILD files (not WORKSPACE files).
   *
   * <p>This includes:
   *
   * <ul>
   *   <li>rules that create actions ({@link NativeActionCreatingRule})
   *   <li>rules that encapsulate toolchain and build environment context
   *   <li>rules that aggregate other rules (like file groups, test suites, or aliases)
   * </ul>
   */
  public static final class NativeBuildRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return commonCoreAndStarlarkAttributes(builder)
          .add(
              attr("licenses", LICENSE)
                  .nonconfigurable("Used in core loading phase logic with no access to configs"))
          .add(
              attr("distribs", DISTRIBUTIONS)
                  .nonconfigurable("Used in core loading phase logic with no access to configs"))
          // Any rule that provides its own meaning for the "target_compatible_with" attribute
          // has to be excluded in `IncompatibleTargetChecker`.
          .add(
              attr(RuleClass.TARGET_COMPATIBLE_WITH_ATTR, LABEL_LIST)
                  .mandatoryProviders(ConstraintValueInfo.PROVIDER.id())
                  // This should be configurable to allow for complex types of restrictions.
                  .tool(
                      "target_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .allowedFileTypes(FileTypeSet.NO_FILE))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$native_build_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * A rule that contains a {@code variables=} attribute to allow referencing Make variables.
   */
  public static final class MakeVariableExpandingRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          // Documented in
          // com/google/devtools/build/docgen/templates/attributes/common/toolchains.html.
          .add(
              attr("toolchains", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.NO_FILE)
                  .mandatoryProviders(ImmutableList.of(TemplateVariableInfo.PROVIDER.id()))
                  .dontCheckConstraints())
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$make_variable_expanding_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Ancestor of every native BUILD rule that creates actions.
   *
   * <p>This is a subset of all BUILD rules. Filegroups and aliases, for example, simply encapsulate
   * other rules. Toolchain rules provide metadata for actions of other rules. See {@link
   * NativeBuildRule} for these.
   */
  public static final class NativeActionCreatingRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType())
          .add(
              attr("data", LABEL_LIST)
                  .allowedFileTypes(FileTypeSet.ANY_FILE)
                  .dontCheckConstraints())
          .add(attr(RuleClass.EXEC_PROPERTIES_ATTR, Types.STRING_DICT).value(ImmutableMap.of()))
          .add(
              attr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                  .allowedFileTypes()
                  .nonconfigurable("Used in toolchain resolution")
                  .tool(
                      "exec_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .value(ImmutableList.of()))
          .add(
              attr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
                  .allowedFileTypes()
                  .nonconfigurable("Used in toolchain resolution")
                  .tool(
                      "exec_group_compatible_with exists for constraint checking, not to create an"
                          + " actual dependency")
                  .value(ImmutableMap.of()))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$native_buildable_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .build();
    }
  }

  /** A base rule for all binary rules. */
  public static final class BinaryBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("args", STRING_LIST))
          .add(attr("env", STRING_DICT))
          .add(attr("output_licenses", LICENSE))
          .add(
              attr(Rule.IS_EXECUTABLE_ATTRIBUTE_NAME, BOOLEAN)
                  .value(true)
                  .nonconfigurable("Called from RunCommand.isExecutable, which takes a Target"))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$binary_base_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(MakeVariableExpandingRule.class)
          .build();
    }
  }

  /**
   * An empty rule that exists for the sole purpose to completely remove a native rule while it's
   * still defined as a Starlark rule in builtins.
   *
   * <p>Use it like <code>builder.addRuleDefinition(new BaseRuleClasses.EmptyRule("name") {});
   * </code>. The <code>{}</code> create a new class for each rule. That's needed because {@link
   * ConfiguredRuleClassProvider.Builder} assumes each rule class has a different Java class.
   */
  public abstract static class EmptyRule implements RuleDefinition {
    private final String name;
    @Nullable private final String bzlLoadFile;

    public EmptyRule(String name) {
      this(name, null);
    }

    public EmptyRule(String name, @Nullable String bzlLoadLabel) {
      this.name = name;
      this.bzlLoadFile = bzlLoadLabel;
    }

    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .removeAttribute("deps")
          .removeAttribute("data")
          .addAttribute(attr("$bzl_load_label", STRING).value(this.bzlLoadFile).build())
          .build();
    }

    @Override
    public Metadata getMetadata() {
      Metadata.Builder metadata =
          Metadata.builder()
              .name(name)
              .type(TargetUtils.isTestRuleName(name) ? RuleClassType.TEST : RuleClassType.NORMAL)
              .ancestors(BaseRuleClasses.NativeActionCreatingRule.class)
              .factoryClass(EmptyRuleConfiguredTargetFactory.class);
      if (TargetUtils.isTestRuleName(name)) {
        metadata.ancestors(
            BaseRuleClasses.TestBaseRule.class, BaseRuleClasses.NativeActionCreatingRule.class);
      }
      return metadata.build();
    }
  }

  /**
   * Factory used by rules' definitions that exist for the sole purpose of providing documentation.
   * For most of these rules, the actual rule is implemented in Starlark but the documentation
   * generation mechanism does not work yet for Starlark rules. TODO(bazel-team): Delete once
   * documentation tools work for Starlark.
   */
  public static class EmptyRuleConfiguredTargetFactory implements RuleConfiguredTargetFactory {
    @Override
    @Nullable
    public ConfiguredTarget create(RuleContext ruleContext) {
      String ruleName = ruleContext.getRule().getRuleClass();
      String bzlLoadLabel = ruleContext.attributes().getOrDefault("$bzl_load_label", STRING, null);
      if (bzlLoadLabel != null) {
        ruleContext.ruleError(
            """
            The %s rule has been removed, add the following to your BUILD/bzl file:

            load("%s", "%s")
            """
                .formatted(ruleName, bzlLoadLabel, ruleName));
      } else {
        ruleContext.ruleError("Rule is unimplemented.");
      }
      return null;
    }
  }
}
