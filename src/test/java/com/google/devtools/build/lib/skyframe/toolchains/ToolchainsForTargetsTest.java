// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.analysis.testing.ToolchainCollectionSubject.assertThat;
import static com.google.devtools.build.lib.analysis.testing.ToolchainContextSubject.assertThat;
import static com.google.devtools.build.lib.skyframe.DependencyResolver.getDependencyContext;
import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.AnalysisResult;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker.IncompatibleTargetException;
import com.google.devtools.build.lib.analysis.producers.DependencyContext;
import com.google.devtools.build.lib.analysis.producers.DependencyContextProducer;
import com.google.devtools.build.lib.analysis.test.BaselineCoverageAction;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.DependencyResolver;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests {@link ConfiguredTargetFunction}'s logic for determining each target toolchain context.
 *
 * <p>This is essentially an integration test for the toolchain part of {@link
 * DependencyContextProducer}. These methods form the core logic that figures out what a target's
 * toolchain dependencies are.
 *
 * <p>{@link ConfiguredTargetFunction} is a complicated class that does a lot of things. This test
 * focuses purely on the task of toolchain resolution. So instead of evaluating full {@link
 * ConfiguredTargetFunction} instances, it evaluates a mock {@link SkyFunction} that just wraps the
 * {@link DependencyResolver#getDependencyContext} part. This keeps focus tight and integration
 * dependencies narrow.
 *
 * <p>We can't just call {@link ToolchainContextProducer} directly because that method needs a
 * {@link SkyFunction.Environment} and Blaze's test infrastructure doesn't support direct access to
 * environments.
 */
@RunWith(JUnit4.class)
public final class ToolchainsForTargetsTest extends AnalysisTestCase {
  /** Returns a {@link SkyKey} for a given <Target, BuildConfigurationValue> pair. */
  private static Key key(
      TargetAndConfiguration targetAndConfiguration, ConfiguredTargetKey configuredTargetKey) {
    return new AutoValue_ToolchainsForTargetsTest_Key(targetAndConfiguration, configuredTargetKey);
  }

  /** Key class for {@link ComputeUnloadedToolchainContextsFunction}. */
  @AutoValue
  abstract static class Key implements SkyKey {
    abstract TargetAndConfiguration targetAndConfiguration();

    abstract ConfiguredTargetKey configuredTargetKey();

    @Override
    public SkyFunctionName functionName() {
      return ComputeUnloadedToolchainContextsFunction.SKYFUNCTION_NAME;
    }
  }

  /**
   * Returns a {@link ToolchainCollection< UnloadedToolchainContext >} as the result of {@link
   * DependencyResolver#getDependencyContext}.
   */
  @AutoCodec
  record Value(ToolchainCollection<UnloadedToolchainContext> toolchainCollection)
      implements SkyValue {
    Value {
      requireNonNull(toolchainCollection, "toolchainCollection");
    }

    static Value create(ToolchainCollection<UnloadedToolchainContext> toolchainCollection) {
      return new Value(toolchainCollection);
    }
  }

  /**
   * A mock {@link SkyFunction} that just calls {@link DependencyResolver#getDependencyContext} and
   * returns its results.
   */
  static class ComputeUnloadedToolchainContextsFunction implements SkyFunction {
    static final SkyFunctionName SKYFUNCTION_NAME =
        SkyFunctionName.createHermetic(
            "CONFIGURED_TARGET_FUNCTION_COMPUTE_UNLOADED_TOOLCHAIN_CONTEXTS");

    private final LateBoundStateProvider stateProvider;

    ComputeUnloadedToolchainContextsFunction(LateBoundStateProvider lateBoundStateProvider) {
      this.stateProvider = lateBoundStateProvider;
    }

    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws ComputeUnloadedToolchainContextsException, InterruptedException {
      Key key = (Key) skyKey.argument();
      var state =
          env.getState(
              () -> DependencyResolver.State.createForTesting(key.targetAndConfiguration()));
      DependencyContext result;
      try {
        result =
            getDependencyContext(
                state,
                key.configuredTargetKey(),
                stateProvider.lateBoundRuleClassProvider(),
                env,
                env.getListener());
      } catch (ToolchainException
          | ConfiguredValueCreationException
          | IncompatibleTargetException
          | DependencyEvaluationException
          | ExecGroupCollection.InvalidExecGroupException e) {
        throw new ComputeUnloadedToolchainContextsException(e);
      }
      if (!state.transitiveRootCauses().isEmpty()) {
        throw new IllegalStateException(
            "expected empty: " + state.transitiveRootCauses().build().toList());
      }
      if (result == null) {
        return null;
      }
      return Value.create(result.unloadedToolchainContexts());
    }

    private static class ComputeUnloadedToolchainContextsException extends SkyFunctionException {
      ComputeUnloadedToolchainContextsException(Exception cause) {
        super(cause, Transience.PERSISTENT); // We can generalize the transience if/when needed.
      }
    }
  }

  /**
   * Provides build state to {@link ComputeUnloadedToolchainContextsFunction}. This needs to be
   * late-bound (i.e. we can't just pass the contents directly) because of the way {@link
   * AnalysisTestCase} works: the {@link AnalysisMock} instance that instantiates the function gets
   * created before the rest of the build state. See {@link AnalysisTestCase#createMocks} for
   * details.
   */
  private class LateBoundStateProvider {
    RuleClassProvider lateBoundRuleClassProvider() {
      return ruleClassProvider;
    }
  }

  /**
   * An {@link AnalysisMock} that injects {@link ComputeUnloadedToolchainContextsFunction} into the
   * Skyframe executor.
   */
  private static final class AnalysisMockWithComputeDepsFunction extends AnalysisMock.Delegate {
    private final LateBoundStateProvider stateProvider;

    AnalysisMockWithComputeDepsFunction(AnalysisMock parent, LateBoundStateProvider stateProvider) {
      super(parent);
      this.stateProvider = stateProvider;
    }

    @Override
    public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
        BlazeDirectories directories) {
      return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
          .putAll(super.getSkyFunctions(directories))
          .put(
              ComputeUnloadedToolchainContextsFunction.SKYFUNCTION_NAME,
              new ComputeUnloadedToolchainContextsFunction(stateProvider))
          .buildOrThrow();
    }
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    return new AnalysisMockWithComputeDepsFunction(
        super.getAnalysisMock(), new LateBoundStateProvider());
  }

  public ToolchainCollection<UnloadedToolchainContext> getToolchainCollection(
      ConfiguredTarget configuredTarget, ConfiguredTargetKey configuredTargetKey)
      throws InterruptedException {
    String targetLabel = configuredTarget.getOriginalLabel().toString();
    SkyKey key =
        key(
            new TargetAndConfiguration(getTarget(targetLabel), getConfiguration(configuredTarget)),
            configuredTargetKey);
    // Analysis phase ended after the update() call in getToolchainCollection. We must re-enable
    // analysis so we can call ConfiguredTargetFunction again without raising an error.
    skyframeExecutor.getSkyframeBuildView().enableAnalysis(true);
    EvaluationResult<Value> evalResult =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    // Test call has finished, to reset the state.
    skyframeExecutor.getSkyframeBuildView().enableAnalysis(false);
    return evalResult.get(key).toolchainCollection();
  }

  public ToolchainCollection<UnloadedToolchainContext> getToolchainCollection(String targetLabel)
      throws Exception {
    ConfiguredTarget target = Iterables.getOnlyElement(update(targetLabel).getTargetsToBuild());
    return getToolchainCollection(
        target,
        ConfiguredTargetKey.builder()
            .setLabel(target.getOriginalLabel())
            .setConfigurationKey(target.getConfigurationKey())
            .build());
  }

  @Before
  public void createToolchains() throws Exception {
    scratch.appendFile("MODULE.bazel", "register_toolchains('//toolchains:all')");

    scratch.file(
        "toolchain/toolchain_def.bzl",
        """
        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                data = ctx.attr.data,
            )
            return [toolchain]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "data": attr.string(),
            },
        )
        """);

    scratch.file("toolchain/BUILD", "toolchain_type(name = 'test_toolchain')");

    scratch.appendFile(
        "toolchains/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "toolchain_1",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_1_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "toolchain_1_impl",
            data = "foo",
        )

        toolchain(
            name = "toolchain_2",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_2_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "toolchain_2_impl",
            data = "bar",
        )
        """);

    scratch.appendFile(
        "toolchain/rule.bzl",
        """
        def _impl(ctx):
            data = ctx.toolchains["//toolchain:test_toolchain"].data
            return [
                coverage_common.instrumented_files_info(
                    ctx,
                    source_attributes = ["srcs"],
                )
            ]

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
            },
            toolchains = ["//toolchain:test_toolchain"],
        )
        """);
  }

  // actual tests
  @Test
  public void basicToolchains() throws Exception {
    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        """);

    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection("//a");
    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        .hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        .hasResolvedToolchain("//toolchains:toolchain_1_impl");
  }

  @Test
  public void basicToolchainsWithAliasAutoExecGroups() throws Exception {
    scratch.file(
        "test/alias/BUILD",
        """
        alias(
            name = "alias_toolchain_type",
            actual = "//toolchain:test_toolchain",
        )
        """);
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            print(ctx.toolchains["//test/alias:alias_toolchain_type"])
            print(ctx.toolchains["//toolchain:test_toolchain"])
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
        )
        """);
    useConfiguration("--incompatible_auto_exec_groups");

    assertThat(update("//test:custom_rule_name").hasError()).isFalse();
  }

  @Test
  public void basicToolchainsWithAliasNoAutoExecGroups() throws Exception {
    scratch.file(
        "test/alias/BUILD",
        """
        alias(
            name = "alias_toolchain_type",
            actual = "//toolchain:test_toolchain",
        )
        """);
    scratch.file(
        "test/defs.bzl",
        """
        def _impl(ctx):
            print(ctx.toolchains["//test/alias:alias_toolchain_type"])
            print(ctx.toolchains["//toolchain:test_toolchain"])
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type"],
        )
        """);
    scratch.file(
        "test/BUILD",
        """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
        )
        """);
    useConfiguration("--noincompatible_auto_exec_groups");

    assertThat(update("//test:custom_rule_name").hasError()).isFalse();
  }

  @Test
  public void basicToolchainsWithAliasNoAutoExecGroups_test() throws Exception {
    scratch.appendFile(
        "toolchain/exec_group_rule.bzl",
        """
        def _impl(ctx):
            if "//toolchain:test_toolchain" in ctx.toolchains:
                fail("this is not expected, it's an exec gp toolchain")
            if ctx.exec_groups["temp"].toolchains["//toolchain:test_toolchain"] == None:
                fail("this is not expected, it's an exec gp toolchain")
            return []

        my_exec_group_rule = rule(
            implementation = _impl,
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        """);

    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        """);

    useConfiguration("--incompatible_auto_exec_groups");

    assertThat(update("//a:a").hasError()).isFalse();
  }

  @Test
  public void execPlatform() throws Exception {
    // Add some platforms and custom constraints.
    scratch.file("platforms/BUILD", "platform(name = 'local_platform_a')");

    // Test normal resolution, and with a per-target exec constraint.
    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        """);

    useConfiguration("--extra_execution_platforms=//platforms:local_platform_a");

    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection("//a");
    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        // First execution platform will be used.
        .hasExecutionPlatform("//platforms:local_platform_a");
  }

  @Test
  public void execPlatform_withExecConstraint() throws Exception {
    // Add some platforms and custom constraints.
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);

    // Test normal resolution, and with a per-target exec constraint.
    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(
            name = "a",
            exec_compatible_with = ["//platforms:local_value_b"],
        )
        """);

    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");

    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection("//a");
    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        // Exec constraint forces the use of this exec platform.
        .hasExecutionPlatform("//platforms:local_platform_b");
  }

  @Test
  public void execGroups_named() throws Exception {
    // Write a rule with exec groups.
    scratch.appendFile(
        "toolchain/exec_group_rule.bzl",
        """
        def _impl(ctx):
            pass

        my_exec_group_rule = rule(
            implementation = _impl,
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        """);

    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        """);

    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection("//a");
    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();
    assertThat(toolchainCollection).defaultToolchainContext().toolchainTypes().isEmpty();
    assertThat(toolchainCollection).defaultToolchainContext().resolvedToolchainLabels().isEmpty();

    assertThat(toolchainCollection).hasExecGroup("temp");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasResolvedToolchain("//toolchains:toolchain_1_impl");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasResolvedToolchain("//toolchains:toolchain_1_impl");
  }

  @Test
  public void execGroups_defaultAndNamed() throws Exception {
    // Add another toolchain type.
    scratch.appendFile(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain_type(name = "extra_toolchain")

        toolchain(
            name = "toolchain",
            exec_compatible_with = [],
            target_compatible_with = [],
            toolchain = ":toolchain_impl",
            toolchain_type = ":extra_toolchain",
        )

        test_toolchain(
            name = "toolchain_impl",
            data = "foo",
        )
        """);

    // Write a rule with exec groups.
    scratch.appendFile(
        "toolchain/exec_group_rule.bzl",
        """
        def _impl(ctx):
            pass

        my_exec_group_rule = rule(
            implementation = _impl,
            toolchains = ["//extra:extra_toolchain"],
            exec_groups = {
                "temp": exec_group(
                    toolchains = ["//toolchain:test_toolchain"],
                ),
            },
        )
        """);

    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:exec_group_rule.bzl", "my_exec_group_rule")

        my_exec_group_rule(name = "a")
        """);

    useConfiguration("--extra_toolchains=//extra:toolchain");
    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection("//a");
    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        .hasToolchainType("//extra:extra_toolchain");
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        .hasResolvedToolchain("//extra:toolchain_impl");

    assertThat(toolchainCollection).hasExecGroup("temp");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainCollection)
        .execGroup("temp")
        .hasResolvedToolchain("//toolchains:toolchain_1_impl");
  }

  @Test
  public void keepParentToolchainContext() throws Exception {
    // Add some platforms and custom constraints.
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);

    // Test normal resolution, and with a per-target exec constraint.
    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(name = "a")
        """);

    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");

    ConfiguredTarget target = Iterables.getOnlyElement(update("//a").getTargetsToBuild());
    ToolchainCollection<UnloadedToolchainContext> toolchainCollection =
        getToolchainCollection(
            target,
            ConfiguredTargetKey.builder()
                .setLabel(target.getOriginalLabel())
                .setConfigurationKey(target.getConfigurationKey())
                .setExecutionPlatformLabel(
                    Label.parseCanonicalUnchecked("//platforms:local_platform_b"))
                .build());

    assertThat(toolchainCollection).isNotNull();
    assertThat(toolchainCollection).hasDefaultExecGroup();

    // This should have the same exec platform as parentToolchainKey, which is local_platform_b.
    assertThat(toolchainCollection)
        .defaultToolchainContext()
        .hasExecutionPlatform("//platforms:local_platform_b");
  }

  /** Regression test for b/214105142, https://github.com/bazelbuild/bazel/issues/14521 */
  @Test
  public void toolchainWithDifferentExecutionPlatforms_doesNotGenerateConflictingCoverageAction()
      throws Exception {
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);
    scratch.file(
        "a/BUILD",
        """
        load("//toolchain:rule.bzl", "my_rule")

        my_rule(
            name = "a",
            srcs = ["a.c"],
            exec_compatible_with = ["//platforms:local_value_a"],
        )

        my_rule(
            name = "b",
            srcs = ["b.c"],
            exec_compatible_with = ["//platforms:local_value_b"],
        )
        """);
    useConfiguration(
        "--collect_code_coverage",
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");

    update("//a:a", "//a:b");

    // Sanity check that a coverage action was generated for the rule itself.
    assertHasBaselineCoverageAction("//a:a", "Writing file a/a/baseline_coverage.dat");
    assertHasBaselineCoverageAction("//a:b", "Writing file a/b/baseline_coverage.dat");
    assertThat(getActions("//toolchains:toolchain_1_impl")).isEmpty();
    ToolchainContext toolchainAContext =
        getToolchainCollection("//a:a").getDefaultToolchainContext();
    assertThat(toolchainAContext).hasExecutionPlatform("//platforms:local_platform_a");
    assertThat(toolchainAContext).hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainAContext).hasResolvedToolchain("//toolchains:toolchain_1_impl");
    ToolchainContext toolchainBContext =
        getToolchainCollection("//a:b").getDefaultToolchainContext();
    assertThat(toolchainBContext).hasExecutionPlatform("//platforms:local_platform_b");
    assertThat(toolchainBContext).hasToolchainType("//toolchain:test_toolchain");
    assertThat(toolchainBContext).hasResolvedToolchain("//toolchains:toolchain_1_impl");
  }

  @CanIgnoreReturnValue
  private AnalysisResult updateExplicitTarget(String label) throws Exception {
    return update(
        new EventBus(),
        defaultFlags().with(Flag.KEEP_GOING),
        /* explicitTargetPatterns= */ ImmutableSet.of(Label.parseCanonicalUnchecked(label)),
        /* aspects= */ ImmutableList.of(),
        /* aspectsParameters= */ ImmutableMap.of(),
        label);
  }

  @Test
  public void targetCompatibleWith_matchesExecCompatibleWith() throws Exception {
    getAnalysisMock()
        .ccSupport()
        .setupCcToolchainConfig(
            mockToolsConfig,
            CcToolchainConfig.builder()
                .withToolchainTargetConstraints("@@//platforms:local_value_a")
                .withToolchainExecConstraints()
                .withCpu("fake"));
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);
    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");
    scratch.file(
        "foo/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.sh"],
            target_compatible_with = ["//platforms:local_value_b"],
        )

        genrule(
            name = "runtool",
            outs = ["b.txt"],
            cmd = "",
            exec_compatible_with = ["//platforms:local_value_b"],
            tools = [":tool"],
        )
        """);

    AnalysisResult result = updateExplicitTarget("//foo:runtool");

    assertThat(result.hasError()).isFalse();
    assertNoEvents();
  }

  @Test
  public void targetCompatibleWith_mismatchesExecCompatibleWith() throws Exception {
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);
    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");
    scratch.file(
        "foo/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.sh"],
            target_compatible_with = ["//platforms:local_value_a"],
        )

        genrule(
            name = "runtool",
            outs = ["b.txt"],
            cmd = "",
            exec_compatible_with = ["//platforms:local_value_b"],
            tools = [":tool"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    AnalysisResult result = updateExplicitTarget("//foo:runtool");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Target //foo:runtool is incompatible and cannot be built, but was explicitly requested");
  }

  @Test
  public void targetCompatibleWith_mismatchesExecCompatibleInDifferentPackage() throws Exception {
    // Regression test for a case where incompatibility happens in an aspect tool in a different
    // package over a Starlark target with
    // --incompatible_visibility_private_attributes_at_definition
    // The tool is replaced with a fake target {@link
    // IncommpatibleTargetChecker#createIncompatibleRuleConfiguredTarget)
    // and it's verified if the tool is visible to the Starlark target.
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);

    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");
    scratch.file(
        "foo/lib.bzl",
        """
        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"_my_tool": attr.label(default = "//tool")},
        )
        """);
    scratch.file(
        "tool/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_a"],
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":lib.bzl", "my_rule")

        my_rule(name = "target_in_different_package")
        """);

    reporter.removeHandler(failFastHandler);
    AnalysisResult result = updateExplicitTarget("//foo:target_in_different_package");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Target //foo:target_in_different_package is incompatible and cannot be built, but was"
            + " explicitly requested");
  }

  @Test
  public void targetCompatibleWith_mismatchesExecCompatibleDepInDifferentPackage()
      throws Exception {
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);

    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");
    scratch.file(
        "foo/lib.bzl",
        """
        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"_my_tool": attr.label(default = "//tool")},
        )
        """);
    scratch.file(
        "tool/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_a"],
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":lib.bzl", "my_rule")

        my_rule(name = "dep")

        filegroup(
            name = "target_with_dep",
            srcs = [":dep"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    AnalysisResult result = updateExplicitTarget("//foo:target_with_dep");

    assertThat(result.hasError()).isTrue();
    assertContainsEvent(
        "Target //foo:target_with_dep is incompatible and cannot be built, but was"
            + " explicitly requested");
  }

  @Test
  public void targetCompatibleWith_mismatchesExecCompatibleWithinAspect() throws Exception {
    // Regression test for a case where incompatibility happens in an aspect tool in a different
    // package over a Starlark target with
    // --incompatible_visibility_private_attributes_at_definition
    // The tool is replaced with a fake target {@link
    // IncommpatibleTargetChecker#createIncompatibleRuleConfiguredTarget)
    // and it's verified if the tool is visible to the Starlark target the aspect is over.
    scratch.file(
        "platforms/BUILD",
        """
        constraint_setting(name = "local_setting")

        constraint_value(
            name = "local_value_a",
            constraint_setting = ":local_setting",
        )

        constraint_value(
            name = "local_value_b",
            constraint_setting = ":local_setting",
        )

        platform(
            name = "local_platform_a",
            constraint_values = [":local_value_a"],
        )

        platform(
            name = "local_platform_b",
            constraint_values = [":local_value_b"],
        )
        """);

    useConfiguration(
        "--extra_execution_platforms=//platforms:local_platform_a,//platforms:local_platform_b");
    scratch.file(
        "foo/lib.bzl",
        """
        def _impl_aspect(ctx, target):
            return []

        my_aspect = aspect(
            _impl_aspect,
            attrs = {"_my_tool": attr.label(default = "//tool")},
            exec_compatible_with = ["//platforms:local_value_a"],
        )

        def _impl(ctx):
            pass

        my_rule = rule(
            _impl,
            attrs = {"deps": attr.label_list(aspects = [my_aspect])},
        )
        simple_starlark_rule = rule(
            _impl,
        )
        """);
    scratch.file(
        "tool/BUILD",
        """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "tool",
            srcs = ["a.cc"],
            target_compatible_with = ["//platforms:local_value_b"],
        )
        """);
    scratch.file(
        "foo/BUILD",
        """
        load(":lib.bzl", "my_rule", "simple_starlark_rule")

        simple_starlark_rule(name = "simple_dep")

        my_rule(
            name = "target_with_aspect",
            deps = [":simple_dep"],
        )
        """);

    reporter.removeHandler(failFastHandler);
    AnalysisResult result = updateExplicitTarget("//foo:target_with_aspect");

    // TODO(bazel-team): This should report an error similarly to {@code
    // #targetCompatibleWith_mismatchesExecCompatibleDepInDifferentPackage}
    assertThat(result.hasError()).isFalse();
  }

  private void assertHasBaselineCoverageAction(String label, String progressMessage)
      throws InterruptedException {
    Action coverageAction = Iterables.getOnlyElement(getActions(label));
    assertThat(coverageAction).isInstanceOf(BaselineCoverageAction.class);
    assertThat(coverageAction.getProgressMessage()).isEqualTo(progressMessage);
  }

  private ImmutableList<Action> getActions(String label) throws InterruptedException {
    return ((RuleConfiguredTarget) getConfiguredTarget(label))
        .getActions().stream().map(Action.class::cast).collect(toImmutableList());
  }
}
