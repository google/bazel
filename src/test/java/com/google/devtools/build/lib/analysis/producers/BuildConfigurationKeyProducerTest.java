// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.config.Scope;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link BuildConfigurationKeyProducer}. */
@RunWith(JUnit4.class)
public class BuildConfigurationKeyProducerTest extends ProducerTestCase {

  @Before
  public final void initializeSkyframExecutor() throws Exception {
    AnalysisMock analysisMock = AnalysisMock.get();

    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    ImmutableSortedSet<Class<? extends FragmentOptions>> buildOptionClasses =
        ruleClassProvider.getFragmentRegistry().getOptionsClasses();

    SequencedSkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    BuildOptions defaultBuildOptions =
        BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone();
    skyframeExecutor.injectExtraPrecomputedValues(
        new ImmutableList.Builder<PrecomputedValue.Injected>()
            .add(
                PrecomputedValue.injected(
                    PrecomputedValue.BASELINE_CONFIGURATION, defaultBuildOptions))
            .addAll(analysisMock.getPrecomputedValues())
            .build());
  }

  /** Extra options for this test. */
  public static class DummyTestOptions extends FragmentOptions {
    public DummyTestOptions() {}

    @Option(
        name = "option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "from_default")
    public String option;

    @Option(
        name = "internal_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "from_default",
        metadataTags = {OptionMetadataTag.INTERNAL})
    public String internalOption;

    @Option(
        name = "accumulating",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null")
    public List<String> accumulating;
  }

  /** Test fragment. */
  @RequiresOptions(options = {DummyTestOptions.class})
  public static final class DummyTestOptionsFragment extends Fragment {
    private final BuildOptions buildOptions;

    public DummyTestOptionsFragment(BuildOptions buildOptions) {
      this.buildOptions = buildOptions;
    }

    // Getter required to satisfy AutoCodec.
    public BuildOptions getBuildOptions() {
      return buildOptions;
    }
  }

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestOptionsFragment.class);
    return builder.build();
  }

  @Before
  public void writePlatforms() throws Exception {
    scratch.file(
        "platforms/BUILD",
        """
        platform(name = "sample")
        """);
  }

  private void createStarlarkFlagRule() throws Exception {
    scratch.file(
        "flag/def.bzl",
        """
        def _impl(ctx):
            return []

        basic_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
            attrs = {
              "scope": attr.string(
                  doc = "The scope",
                  default = "universal",
                  values = ["universal", "project"],
              ),
            },
        )
        """);
  }

  private void createStarlarkFlag() throws Exception {
    createStarlarkFlagRule();
    scratch.file(
        "flag/BUILD",
        """
        load(":def.bzl", "basic_flag")

        basic_flag(
            name = "flag",
            build_setting_default = "from_default",
        )
        """);
  }

  @Test
  public void createKey() throws Exception {
    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_cmd");
  }

  @Test
  public void createKey_emptyConfig() throws Exception {
    BuildOptions baseOptions = CommonOptions.EMPTY_OPTIONS;
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptionsChecksum()).isEqualTo(CommonOptions.EMPTY_OPTIONS.checksum());
  }

  @Test
  public void createKey_platformMapping() throws Exception {
    scratch.file(
        "/workspace/platform_mappings",
        """
        platforms:
          //platforms:sample
            --internal_option=from_mapping_changed
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_mapping_changed");
  }

  @Test
  public void createKey_platformMapping_invalidFile() throws Exception {
    scratch.file(
        "/workspace/platform_mappings",
        """
        not a mapping file
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    // Fails because the mapping file is poorly formed and cannot be parsed.
    assertThrows(PlatformMappingException.class, () -> fetch(baseOptions, null));
  }

  @Test
  public void createKey_platformMapping_invalidOption() throws Exception {
    scratch.file(
        "/workspace/platform_mappings",
        """
        platforms:
          //platforms:sample
            --fake_option
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    // Fails because the changed platform has an invalid mapping.
    var e = assertThrows(PlatformMappingException.class, () -> fetch(baseOptions, null));
    assertThat(e).hasMessageThat().contains("Unrecognized option: --fake_option");
  }

  @Test
  public void createKey_platformFlags_native() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--internal_option=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_platform");
  }

  @Test
  public void createKey_platformFlags_starlark() throws Exception {
    createStarlarkFlag();
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().getStarlarkOptions())
        .containsAtLeast(Label.parseCanonicalUnchecked("//flag"), "from_platform");
  }

  @Test
  public void createKey_platformFlags_override_native() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--option=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--option=from_cli");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).option).isEqualTo("from_platform");
  }

  @Test
  public void createKey_platformFlags_override_starlark() throws Exception {
    createStarlarkFlag();
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--//flag=from_cli");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().getStarlarkOptions())
        .containsAtLeast(Label.parseCanonicalUnchecked("//flag"), "from_platform");
  }

  @Test
  public void createKey_platformFlags_resetToDefault_native() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--option=from_default",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--option=from_cli");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).option).isEqualTo("from_default");
  }

  // Regression test for https://github.com/bazelbuild/bazel/issues/23147
  @Test
  public void createKey_platformFlags_resetToDefault_starlark() throws Exception {
    createStarlarkFlag();
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--//flag=from_default",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--//flag=from_cli");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    // Default key values should not be present in starlark options.
    assertThat(result.getOptions().getStarlarkOptions())
        .doesNotContainKey(Label.parseCanonicalUnchecked("//flag"));
  }

  @Test
  // Re-enable this once merging repeatable flags works properly. Also add a corresponding Starlark
  // flag to test.
  @Ignore("https://github.com/bazelbuild/bazel/issues/22453")
  public void createKey_platformFlags_accumulate() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--accumulating=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--accumulating=from_cli");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).accumulating)
        .containsExactly("from_cli", "from_platform")
        .inOrder();
  }

  @Test
  public void createKey_platformFlags_invalidPlatform() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        filegroup(name = "sample")
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    assertThrows(InvalidPlatformException.class, () -> fetch(baseOptions, null));
  }

  @Test
  public void createKey_platformFlags_invalidOption() throws Exception {
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--fake_option_doesnt_exist=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    assertThrows(OptionsParsingException.class, () -> fetch(baseOptions, null));
  }

  @Test
  public void createKey_platformFlags_overridesMapping() throws Exception {
    scratch.file(
        "/workspace/platform_mappings",
        """
        platforms:
          //platforms:sample
            --internal_option=from_mapping
        """);
    scratch.overwriteFile(
        "platforms/BUILD",
        """
        platform(
            name = "sample",
            flags = [
                "--internal_option=from_platform",
            ],
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    BuildConfigurationKey result = fetch(baseOptions, null);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_platform");
  }

  @Test
  public void createKey_withScopedBuildOptions_outOfScopeFlag_flagNotSetInTheBaseline()
      throws Exception {
    createStarlarkFlagRule();
    scratch.file(
        "flag/BUILD",
        """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "project",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "flag/PROJECT.scl",
        """
        project = {
          "active_directories": {
            "default": [
                "//my_project"
            ]
          }
        }
        """);

    scratch.file(
        "out_of_scope_flag/BUILD",
        """
        load("//flag:def.bzl", "basic_flag")
        basic_flag(
            name = "baz",
            scope = "project",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "out_of_scope_flag/PROJECT.scl",
        """
        project = {
          "active_directories": {
            "default": [
                "//out_side_of_my_project"
            ]
          }
        }
        """);

    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar", "--//out_of_scope_flag:baz=baz");
    BuildConfigurationKey result =
        fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"));
    assertThat(result).isNotNull();
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo")))
        .isEqualTo("foo");
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:bar")))
        .isEqualTo("bar");
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz")))
        .isNull();

    // Since the effective BuildOptions does not have //out_of_scope_flag:baz, its scope type should
    // not exist in the scope type map.
    ImmutableMap<Label, Scope.ScopeType> expectedScopeTypeMap =
        ImmutableMap.of(
            Label.parseCanonicalUnchecked("//flag:foo"),
            Scope.ScopeType.PROJECT,
            Label.parseCanonicalUnchecked("//flag:bar"),
            Scope.ScopeType.UNIVERSAL);
    assertThat(result.getOptions().getScopeTypeMap())
        .containsExactlyEntriesIn(expectedScopeTypeMap);
  }

  @Test
  public void createKey_withScopedBuildOptions_outOfScopeFlag_flagSetInTheBaseline()
      throws Exception {
    AnalysisMock analysisMock = AnalysisMock.get();

    ConfiguredRuleClassProvider ruleClassProvider = analysisMock.createRuleClassProvider();
    ImmutableSortedSet<Class<? extends FragmentOptions>> buildOptionClasses =
        ruleClassProvider.getFragmentRegistry().getOptionsClasses();

    SequencedSkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    BuildOptions.Builder defaultBuildOptionsBuilder =
        BuildOptions.getDefaultBuildOptionsForFragments(buildOptionClasses).clone().toBuilder();

    // set the out of scope flag in the baseline
    Map<Label, Object> starlarkOptions = new HashMap<>();
    starlarkOptions.put(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"), "baselineValue");
    defaultBuildOptionsBuilder.addStarlarkOptions(starlarkOptions);

    skyframeExecutor.injectExtraPrecomputedValues(
        new ImmutableList.Builder<PrecomputedValue.Injected>()
            .add(
                PrecomputedValue.injected(
                    PrecomputedValue.BASELINE_CONFIGURATION, defaultBuildOptionsBuilder.build()))
            .addAll(analysisMock.getPrecomputedValues())
            .build());

    createStarlarkFlagRule();
    scratch.file(
        "flag/BUILD",
        """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "project",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "flag/PROJECT.scl",
        """
        project = {
          "active_directories": {
            "default": [
                "//my_project"
            ]
          }
        }
        """);

    scratch.file(
        "out_of_scope_flag/BUILD",
        """
        load("//flag:def.bzl", "basic_flag")
        basic_flag(
            name = "baz",
            scope = "project",
            build_setting_default = "default",
        )
        """);
    scratch.file(
        "out_of_scope_flag/PROJECT.scl",
        """
        project = {
          "active_directories": {
            "default": [
                "//out_side_of_my_project"
            ]
          }
        }
        """);

    invalidatePackages(false);

    BuildOptions baseOptions =
        createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar", "--//out_of_scope_flag:baz=baz");
    BuildConfigurationKey result =
        fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"));
    assertThat(result).isNotNull();
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:foo")))
        .isEqualTo("foo");
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//flag:bar")))
        .isEqualTo("bar");
    assertThat(
            result
                .getOptions()
                .getStarlarkOptions()
                .get(Label.parseCanonicalUnchecked("//out_of_scope_flag:baz")))
        .isEqualTo("baselineValue");

    // Since the effective BuildOptions has //out_of_scope_flag:baz, its scope type should
    // exist in the scope type map.
    ImmutableMap<Label, Scope.ScopeType> expectedScopeTypeMap =
        ImmutableMap.of(
            Label.parseCanonicalUnchecked("//flag:foo"),
            Scope.ScopeType.PROJECT,
            Label.parseCanonicalUnchecked("//flag:bar"),
            Scope.ScopeType.UNIVERSAL,
            Label.parseCanonicalUnchecked("//out_of_scope_flag:baz"),
            Scope.ScopeType.PROJECT);
    assertThat(result.getOptions().getScopeTypeMap())
        .containsExactlyEntriesIn(expectedScopeTypeMap);
  }

  @Test
  public void checkFinalizeBuildOptions_haveCorrectScopeTypeMap_noScopingApplied()
      throws Exception {
    createStarlarkFlagRule();
    scratch.file(
        "flag/BUILD",
        """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            build_setting_default = "default",
        )
        basic_flag(
            name = "bar",
            build_setting_default = "default",
        )
        """);
    invalidatePackages(false);

    BuildOptions baseOptions = createBuildOptions("--//flag:foo=foo", "--//flag:bar=bar");
    BuildConfigurationKey result =
        fetch(baseOptions, Label.parseCanonicalUnchecked("//my_project:my_target"));

    // All flags should be universal
    ImmutableMap<Label, Scope.ScopeType> expectedScopeTypeMap =
        ImmutableMap.of(
            Label.parseCanonicalUnchecked("//flag:foo"),
            Scope.ScopeType.UNIVERSAL,
            Label.parseCanonicalUnchecked("//flag:bar"),
            Scope.ScopeType.UNIVERSAL);
    assertThat(result.getOptions().getScopeTypeMap())
        .containsExactlyEntriesIn(expectedScopeTypeMap);
  }

  @Test
  public void errorThrown_disallowedScopeType() throws Exception {
    createStarlarkFlagRule();
    scratch.file(
        "flag/BUILD",
        """
        load(":def.bzl", "basic_flag")
        basic_flag(
            name = "foo",
            scope = "Project",
            build_setting_default = "default",
        )
        """);
    invalidatePackages(false);

    var e = assertThrows(AssertionError.class, () -> createBuildOptions("--//flag:foo=foo"));
    assertThat(e)
        .hasMessageThat()
        .contains(
            "//flag:foo: invalid value in 'scope' attribute: has to be one of 'universal' or"
                + " 'project' instead of 'Project");
  }

  private static final String CONTEXT = "context";

  private BuildConfigurationKey fetch(BuildOptions options, Label label)
      throws InterruptedException,
          OptionsParsingException,
          PlatformMappingException,
          InvalidPlatformException,
          BuildOptionsScopeFunctionException {
    Sink sink = new Sink();
    BuildConfigurationKeyProducer<String> producer =
        new BuildConfigurationKeyProducer<>(sink, StateMachine.DONE, CONTEXT, options, label);
    // Ignore the return value: sink will either return a result or re-throw whatever exception it
    // received from the producer.
    var unused = executeProducer(producer);
    return sink.options(CONTEXT);
  }

  /** Receiver for platform info from {@link PlatformProducer}. */
  private static class Sink implements BuildConfigurationKeyProducer.ResultSink<String> {
    @Nullable private OptionsParsingException optionsParsingException;
    @Nullable private PlatformMappingException platformMappingException;
    @Nullable private InvalidPlatformException invalidPlatformException;
    @Nullable private BuildOptionsScopeFunctionException buildOptionsScopeFunctionException;
    @Nullable private String context;
    @Nullable private BuildConfigurationKey key;

    @Override
    public void acceptOptionsParsingError(OptionsParsingException e) {
      this.optionsParsingException = e;
    }

    @Override
    public void acceptPlatformMappingError(PlatformMappingException e) {
      this.platformMappingException = e;
    }

    @Override
    public void acceptPlatformFlagsError(InvalidPlatformException e) {
      this.invalidPlatformException = e;
    }

    @Override
    public void acceptTransitionedConfiguration(String context, BuildConfigurationKey key) {
      this.context = context;
      this.key = key;
    }

    @Override
    public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
      this.buildOptionsScopeFunctionException = e;
    }

    BuildConfigurationKey options(String expectedContext)
        throws OptionsParsingException,
            PlatformMappingException,
            InvalidPlatformException,
            BuildOptionsScopeFunctionException {
      if (this.optionsParsingException != null) {
        throw this.optionsParsingException;
      }
      if (this.platformMappingException != null) {
        throw this.platformMappingException;
      }
      if (this.invalidPlatformException != null) {
        throw this.invalidPlatformException;
      }
      if (this.buildOptionsScopeFunctionException != null) {
        throw this.buildOptionsScopeFunctionException;
      }
      if (this.key != null) {
        assertThat(this.context).isEqualTo(expectedContext);
        return this.key;
      }
      throw new IllegalStateException("Value and exception not set");
    }
  }
}
