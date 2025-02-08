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

package com.google.devtools.build.lib.testutil;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.BuildType.OUTPUT_LIST;
import static com.google.devtools.build.lib.packages.Type.INTEGER;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.CommonPrerequisiteValidator;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TemplateVariableInfo;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.StarlarkProvider;
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.rules.config.ConfigRules;
import com.google.devtools.build.lib.rules.core.CoreRules;
import com.google.devtools.build.lib.rules.platform.PlatformRules;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.lang.reflect.Method;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** Helper class to provide a RuleClassProvider for tests. */
public class TestRuleClassProvider {

  private static ConfiguredRuleClassProvider ruleClassProvider = null;

  private TestRuleClassProvider() {}

  /** Adds all the rule classes supported internally within the build tool to the given builder. */
  public static void addStandardRules(ConfiguredRuleClassProvider.Builder builder) {
    try {
      Class<?> providerClass = Class.forName(TestConstants.TEST_RULE_CLASS_PROVIDER);
      Method setupMethod =
          providerClass.getMethod("setup", ConfiguredRuleClassProvider.Builder.class);
      setupMethod.invoke(null, builder);

      // Add the repository module for any unit tests that test local_repository behavior
      builder.addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    addStandardRules(builder);
    // TODO(b/174773026): Eliminate TestingDummyRule/MockToolchainRule from this class, push them
    // down into the tests that use them. It's better for tests to avoid spooky mocks at a distance.
    // If we eliminate it, TestRuleClassProvider probably doesn't need to exist anymore.
    builder.addRuleDefinition(new TestingDummyRule());
    builder.addRuleDefinition(new MockToolchainRule());
    return builder.build();
  }

  /** Returns a rule class provider. */
  public static ConfiguredRuleClassProvider getRuleClassProvider() {
    if (ruleClassProvider == null) {
      ruleClassProvider = createRuleClassProvider();
    }
    return ruleClassProvider;
  }

  // TODO(bazel-team): The logic for the "minimal" rule class provider is currently split between
  // TestRuleClassProvider and BuiltinsInjectionTest's overrides of BuildViewTestCase setup helpers.
  // Consider refactoring this together into one place as a new MinimalAnalysisMock.
  /**
   * Adds a few essential rules to a builder, such that it is usable but does not contain all the
   * rule classes known to the production environment.
   */
  public static void addMinimalRules(ConfiguredRuleClassProvider.Builder builder) {
    // TODO(bazel-team): See also TrimmableTestConfigurationFragments#installFragmentsAndNativeRules
    // for alternative/additional setup. Consider factoring that one to use this method.
    builder
        .setToolsRepository(RepositoryName.MAIN)
        .setRunfilesPrefix("test")
        .setPrerequisiteValidator(new MinimalPrerequisiteValidator());
    CoreRules.INSTANCE.init(builder);
    builder.addConfigurationOptions(CoreOptions.class);
    PlatformRules.INSTANCE.init(builder);
    ConfigRules.INSTANCE.init(builder);
  }

  private static class MinimalPrerequisiteValidator extends CommonPrerequisiteValidator {
    @Override
    public boolean isSameLogicalPackage(
        PackageIdentifier thisPackage, PackageIdentifier prerequisitePackage) {
      return thisPackage.equals(prerequisitePackage);
    }

    @Override
    protected boolean packageUnderExperimental(PackageIdentifier packageIdentifier) {
      return false;
    }

    @Override
    protected boolean checkVisibilityForExperimental(RuleContext.Builder context) {
      // It does not matter whether we return true or false here if packageUnderExperimental always
      // returns false.
      return true;
    }

    @Override
    protected boolean checkVisibilityForToolchains(
        RuleContext.Builder context, Label prerequisite) {
      return true;
    }

    @Override
    protected boolean allowExperimentalDeps(RuleContext.Builder context) {
      // It does not matter whether we return true or false here if packageUnderExperimental always
      // returns false.
      return false;
    }
  }

  /** A dummy rule with some dummy attributes. */
  public static final class TestingDummyRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .setUndocumented()
          .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
          .add(attr("outs", OUTPUT_LIST))
          .add(attr("dummystrings", STRING_LIST))
          .add(attr("dummyinteger", INTEGER))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("testing_dummy_rule")
          .ancestors(BaseRuleClasses.NativeActionCreatingRule.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  /** Stub rule to test Make variable expansion. */
  public static final class MakeVariableTester implements RuleConfiguredTargetFactory {

    @Override
    @Nullable
    public ConfiguredTarget create(RuleContext ruleContext)
        throws InterruptedException, RuleErrorException, ActionConflictException {
      Map<String, String> variables = ruleContext.attributes().get("variables", Types.STRING_DICT);
      return new RuleConfiguredTargetBuilder(ruleContext)
          .setFilesToBuild(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
          .addProvider(RunfilesProvider.EMPTY)
          .addNativeDeclaredProvider(
              new TemplateVariableInfo(ImmutableMap.copyOf(variables), Location.BUILTIN))
          .build();
    }
  }

  /** Definition of a stub rule to test Make variable expansion. */
  public static final class MakeVariableTesterRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return builder
          .advertiseStarlarkProvider(
              StarlarkProviderIdentifier.forKey(TemplateVariableInfo.PROVIDER.getKey()))
          .add(attr("variables", Types.STRING_DICT))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return Metadata.builder()
          .name("make_variable_tester")
          .ancestors(
              BaseRuleClasses.NativeBuildRule.class,
              BaseRuleClasses.MakeVariableExpandingRule.class)
          .factoryClass(MakeVariableTester.class)
          .build();
    }
  }

  /** A mock rule that requires a toolchain. */
  public static class MockToolchainRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .requiresConfigurationFragments(PlatformConfiguration.class)
          .addToolchainTypes(
              ToolchainTypeRequirement.create(
                  Label.parseCanonicalUnchecked("//toolchain:test_toolchain")))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("mock_toolchain_rule")
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .ancestors(BaseRuleClasses.NativeActionCreatingRule.class)
          .build();
    }
  }

  /** A simple provider for testing. */
  public static final class FooProvider implements TransitiveInfoProvider {}

  private static final Label FAKE_LABEL = Label.parseCanonicalUnchecked("//fake/label.bzl");

  private static final StarlarkProviderIdentifier STARLARK_P1 =
      StarlarkProviderIdentifier.forKey(
          new StarlarkProvider.Key(keyForBuild(FAKE_LABEL), "STARLARK_P1"));

  /** Definition of a rule that advertises a native provider that it does not return. */
  public static final class LiarRuleWithNativeProvider implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return builder.advertiseProvider(FooProvider.class).build();
    }

    @Override
    public Metadata getMetadata() {
      return Metadata.builder()
          .name("liar_rule_with_native_provider")
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }

  /** Definition of a rule that advertises a Starlark provider that it does not return. */
  public static final class LiarRuleWithStarlarkProvider implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment environment) {
      return builder.advertiseStarlarkProvider(STARLARK_P1).build();
    }

    @Override
    public Metadata getMetadata() {
      return Metadata.builder()
          .name("liar_rule_with_starlark_provider")
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .factoryClass(UnknownRuleConfiguredTarget.class)
          .build();
    }
  }
}
