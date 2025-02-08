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

package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.common.truth.IterableSubject;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RegisteredExecutionPlatformsFunction} and {@link
 * RegisteredExecutionPlatformsValue}.
 */
@RunWith(JUnit4.class)
public class RegisteredExecutionPlatformsFunctionTest extends ToolchainTestCase {

  protected EvaluationResult<RegisteredExecutionPlatformsValue>
      requestExecutionPlatformsFromSkyframe(SkyKey executionPlatformsKey)
          throws InterruptedException {
    try {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          getSkyframeExecutor(), executionPlatformsKey, /*keepGoing=*/ false, reporter);
    } finally {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false);
    }
  }

  protected static IterableSubject assertExecutionPlatformLabels(
      RegisteredExecutionPlatformsValue registeredExecutionPlatformsValue) {
    return assertExecutionPlatformLabels(registeredExecutionPlatformsValue, null);
  }

  protected static IterableSubject assertExecutionPlatformLabels(
      RegisteredExecutionPlatformsValue registeredExecutionPlatformsValue,
      @Nullable PackageIdentifier packageRoot) {
    assertThat(registeredExecutionPlatformsValue).isNotNull();
    ImmutableList<ConfiguredTargetKey> declaredExecutionPlatformKeys =
        registeredExecutionPlatformsValue.registeredExecutionPlatformKeys();
    List<Label> labels = collectExecutionPlatformLabels(declaredExecutionPlatformKeys, packageRoot);
    return assertThat(labels);
  }

  protected static List<Label> collectExecutionPlatformLabels(
      List<ConfiguredTargetKey> executionPlatformKeys, @Nullable PackageIdentifier packageRoot) {
    return executionPlatformKeys.stream()
        .map(ConfiguredTargetKey::getLabel)
        .filter(label -> filterLabel(packageRoot, label))
        .collect(Collectors.toList());
  }

  @Test
  public void testRegisteredExecutionPlatforms() throws Exception {
    // Request the executionPlatforms.
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey).isNotNull();

    RegisteredExecutionPlatformsValue value = result.get(executionPlatformsKey);
    assertThat(value.registeredExecutionPlatformKeys()).isEmpty();
    assertThat(value.rejectedPlatforms()).isNull();
  }

  @Test
  public void testRegisteredExecutionPlatforms_flagOverride() throws Exception {

    // Add an extra execution platform.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:execution_platform_2")
        """);
    useConfiguration("--extra_execution_platforms=//extra:execution_platform_1");

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_execution_platforms flag is first in the
    // list.
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_flagOverride_multiple() throws Exception {

    // Add an extra execution platform.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);

    useConfiguration(
        "--extra_execution_platforms=//extra:execution_platform_1,//extra:execution_platform_2");

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_execution_platforms flag is first in the
    // list.
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_targetPattern_workspace() throws Exception {

    // Add an extra execution platform.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra/...")
        """);

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_execution_platforms flag is first in the
    // list.
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_aliased() throws Exception {
    // Add an extra execution platform.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);
    scratch.file(
        "alias/BUILD",
        """
        alias(name = "alias_platform_1", actual = "//extra:execution_platform_1");

        alias(name = "alias_platform_2", actual = "//extra:execution_platform_2");
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//alias/...")
        """);

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that aliases were resolved to actual targets.
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_targetPattern_mixed() throws Exception {

    // Add several targets, some of which are not actually platforms.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")

        filegroup(name = "not_an_execution_platform")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:all")
        """);

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // There should only be two execution platforms registered from //extra.
    // Verify that the target registered with the extra_execution_platforms flag is first in the
    // list.
    assertExecutionPlatformLabels(
            result.get(executionPlatformsKey), PackageIdentifier.createInMainRepo("extra"))
        .containsExactly(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_targetPattern_flagOverride() throws Exception {

    // Add an extra execution platform.
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);

    useConfiguration("--extra_execution_platforms=//extra/...");

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_execution_platforms flag is first in the
    // list.
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:execution_platform_1"),
            Label.parseCanonicalUnchecked("//extra:execution_platform_2"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatforms_notExecutionPlatform() throws Exception {
    rewriteModuleDotBazel(
        """
        register_execution_platforms("//error:not_an_execution_platform")
        """);
    // Have to use a rule that doesn't require a target platform, or else there will be a cycle.
    scratch.file(
        "error/BUILD",
        """
        toolchain_type(name = "not_an_execution_platform")
        """);

    // Request the executionPlatforms.
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(executionPlatformsKey)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(executionPlatformsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//error:not_an_execution_platform");
  }

  @Test
  public void testRegisteredExecutionPlatforms_reload() throws Exception {
    scratch.overwriteFile(
        "platform/BUILD",
        """
        platform(name = "execution_platform_1")

        platform(name = "execution_platform_2")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//platform:execution_platform_1")
        """);

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .contains(Label.parseCanonicalUnchecked("//platform:execution_platform_1"));

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//platform:execution_platform_2")
        """);

    executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    result = requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .contains(Label.parseCanonicalUnchecked("//platform:execution_platform_2"));
  }

  @Test
  public void testRegisteredExecutionPlatforms_bzlmod() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        """
        register_execution_platforms("//:plat")
        register_execution_platforms("//:dev_plat", dev_dependency = True)
        bazel_dep(name = "bbb", version = "1.0")
        bazel_dep(name = "ccc", version = "1.1")
        """);
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            """
            module(name = "bbb", version = "1.0")
            register_execution_platforms("//:plat")
            register_execution_platforms("//:dev_plat", dev_dependency = True)
            bazel_dep(name = "ddd", version = "1.0")
            """)
        .addModule(
            createModuleKey("ccc", "1.1"),
            """
            module(name = "ccc", version = "1.1")
            register_execution_platforms("//:plat")
            register_execution_platforms("//:dev_plat", dev_dependency = True)
            bazel_dep(name = "ddd", version = "1.1")
            """)
        // ddd@1.0 is not selected
        .addModule(
            createModuleKey("ddd", "1.0"),
            """
            module(name = "ddd", version = "1.0")
            register_execution_platforms('//:plat')
            """)
        .addModule(
            createModuleKey("ddd", "1.1"),
            """
            module(name = "ddd", version = "1.1")
            register_execution_platforms("@eee//:plat", "//:plat")
            bazel_dep(name = "eee", version = "1.0")
            """)
        .addModule(
            createModuleKey("eee", "1.0"),
            """
            module(name = "eee", version = "1.0")
            """);
    for (String repo : ImmutableList.of("bbb+1.0", "ccc+1.1", "ddd+1.0", "ddd+1.1", "eee+1.0")) {
      scratch.file(moduleRoot.getRelative(repo).getRelative("REPO.bazel").getPathString());
      scratch.file(
          moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(),
          """
          platform(name = "plat")
          """);
    }
    scratch.overwriteFile(
        "BUILD",
        """
        platform(name = "plat")
        platform(name = "dev_plat")
        """);
    invalidatePackages();

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the execution platforms registered with bzlmod come in the BFS order
    assertExecutionPlatformLabels(result.get(executionPlatformsKey))
        .containsExactly(
            // Root module platforms
            Label.parseCanonical("//:plat"),
            Label.parseCanonical("//:dev_plat"),
            // Other modules' toolchains
            Label.parseCanonical("@@bbb+//:plat"),
            Label.parseCanonical("@@ccc+//:plat"),
            Label.parseCanonical("@@eee+//:plat"),
            Label.parseCanonical("@@ddd+//:plat"))
        .inOrder();
  }

  @Test
  public void testRegisteredExecutionPlatformsValue_equalsAndHashCode()
      throws ConstraintCollection.DuplicateConstraintException {
    ConfiguredTargetKey executionPlatformKey1 =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//test:executionPlatform1"))
            .setConfigurationKey(null)
            .build();
    ConfiguredTargetKey executionPlatformKey2 =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//test:executionPlatform2"))
            .setConfigurationKey(null)
            .build();

    new EqualsTester()
        .addEqualityGroup(
            // Two platforms registered.
            RegisteredExecutionPlatformsValue.create(
                ImmutableList.of(executionPlatformKey1, executionPlatformKey2),
                /* rejectedPlatforms= */ null),
            RegisteredExecutionPlatformsValue.create(
                ImmutableList.of(executionPlatformKey1, executionPlatformKey2),
                /* rejectedPlatforms= */ null))
        .addEqualityGroup(
            // A single platform registered.
            RegisteredExecutionPlatformsValue.create(
                ImmutableList.of(executionPlatformKey1), /* rejectedPlatforms= */ null))
        .addEqualityGroup(
            // A single, different, platform registered.
            RegisteredExecutionPlatformsValue.create(
                ImmutableList.of(executionPlatformKey2), /* rejectedPlatforms= */ null))
        .addEqualityGroup(
            // The same as the first group, but the order is different.
            RegisteredExecutionPlatformsValue.create(
                ImmutableList.of(executionPlatformKey2, executionPlatformKey1),
                /* rejectedPlatforms= */ null))
        .testEquals();
  }

  /*
   * Regression test for https://github.com/bazelbuild/bazel/issues/10101.
   */
  @Test
  public void testInvalidExecutionPlatformLabelDoesntCrash() throws Exception {
    rewriteModuleDotBazel(
        """
        register_execution_platforms("//test:bad_exec_platform_label")
        """);
    scratch.file(
        "test/BUILD",
        """
        genrule(
            name = "g",
            srcs = [],
            outs = ["g.out"],
            cmd = "echo hi > $@",
        )
        """);
    reporter.removeHandler(failFastHandler);
    assertThrows(
        "invalid registered execution platform '//test:bad_exec_platform_label': "
            + "no such target '//test:bad_exec_platform_label'",
        ViewCreationFailedException.class,
        () ->
            update(
                ImmutableList.of("//test:g"),
                /*keepGoing=*/ false,
                /*loadingPhaseThreads=*/ 1,
                /*doAnalysis=*/ true,
                eventBus));
  }

  @Test
  public void testRegisteredExecutionPlatforms_requiredSettings_enabled() throws Exception {
    // Add an extra platform with a required_setting
    scratch.file(
        "extra/BUILD",
        """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        """);

    useConfiguration("--compilation_mode=opt");
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey).isNotNull();

    RegisteredExecutionPlatformsValue value = result.get(executionPlatformsKey);

    // Both platforms should be present because the required settings match.
    assertExecutionPlatformLabels(value)
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:required_platform"),
            Label.parseCanonicalUnchecked("//extra:always_platform"));
  }

  @Test
  public void testRegisteredExecutionPlatforms_requiredSettings_disabled() throws Exception {
    // Add an extra platform with a required_setting
    scratch.file(
        "extra/BUILD",
        """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        """);

    useConfiguration("--compilation_mode=dbg");
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey).isNotNull();

    RegisteredExecutionPlatformsValue value = result.get(executionPlatformsKey);

    // The platform with required settings should not be present.
    assertExecutionPlatformLabels(value)
        .contains(Label.parseCanonicalUnchecked("//extra:always_platform"));
    assertExecutionPlatformLabels(value)
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:required_platform"));
  }

  @Test
  public void testRegisteredExecutionPlatforms_requiredSettings_debug() throws Exception {
    // Add an extra platform with a required_setting
    scratch.file(
        "extra/BUILD",
        """
        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":optimized",
            ],
        )

        platform(name = "always_platform")
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:required_platform", "//extra:always_platform")
        """);

    useConfiguration("--compilation_mode=dbg");
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ true);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(executionPlatformsKey).isNotNull();

    RegisteredExecutionPlatformsValue value = result.get(executionPlatformsKey);

    // Verify that the message about the unmatched config_setting is present.
    assertThat(value.rejectedPlatforms()).isNotNull();
    assertThat(value.rejectedPlatforms())
        .containsEntry(
            Label.parseCanonicalUnchecked("//extra:required_platform"),
            "mismatching config settings: optimized");
  }

  @Test
  public void testRegisteredExecutionPlatforms_requiredSettings_config_error() throws Exception {
    // Add an extra platform with a required_setting
    scratch.file(
        "extra/BUILD",
        """
        config_setting(
            name = "flagged",
            flag_values = {":flag": "default"},
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":flagged",
            ],
        )
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:required_platform")
        """);

    // Need this so the feature flag is actually gone from the configuration.
    useConfiguration("--enforce_transitive_configs_for_config_feature_flag");
    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(executionPlatformsKey).isNotNull();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(executionPlatformsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "Unrecoverable errors resolving config_setting associated with"
                + " //extra:required_platform: For config_setting flagged, Feature flag"
                + " //extra:flag was accessed in a configuration it is not present in.");
  }

  @Test
  public void testRegisteredExecutionPlatforms_requiredSettings_cantDependOnConstraintValues_error()
      throws Exception {
    // Add an extra platform with a required_setting
    scratch.file(
        "extra/BUILD",
        """
        constraint_setting(name = "cs1")
        constraint_value(name = "cv1", constraint_setting = ":cs1")
        constraint_value(name = "cv2", constraint_setting = ":cs1")
        config_setting(
            name = "setting",
            constraint_values = [":cv1"],
        )

        platform(
            name = "required_platform",
            required_settings = [
                ":setting",
                ":cv2",
            ],
        )
        """);

    rewriteModuleDotBazel(
        """
        register_execution_platforms("//extra:required_platform")
        """);

    SkyKey executionPlatformsKey =
        RegisteredExecutionPlatformsValue.key(targetConfigKey, /* debug= */ false);
    EvaluationResult<RegisteredExecutionPlatformsValue> result =
        requestExecutionPlatformsFromSkyframe(executionPlatformsKey);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result).hasErrorEntryForKeyThat(executionPlatformsKey).isNotNull();
  }
}
