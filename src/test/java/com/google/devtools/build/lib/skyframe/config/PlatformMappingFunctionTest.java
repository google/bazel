// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.MissingInputFileException;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link PlatformMappingFunction}.
 *
 * <p>Note that all parsing tests are located in {@link PlatformMappingFunctionParserTest}.
 */
@RunWith(JUnit4.class)
public final class PlatformMappingFunctionTest extends BuildViewTestCase {

  private static final Label PLATFORM1 = Label.parseCanonicalUnchecked("//platforms:one");

  /** Extra options for this test. */
  public static class DummyTestOptions extends FragmentOptions {
    public DummyTestOptions() {}

    @Option(
        name = "str_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "defVal")
    public String strOption;

    @Option(
        name = "internal_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "super secret",
        metadataTags = {OptionMetadataTag.INTERNAL})
    public String internalOption;

    @Option(
        name = "list",
        converter = CommaSeparatedOptionListConverter.class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "null")
    public List<String> list;
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
    // Needed to properly initialize skyframe.
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestOptionsFragment.class);
    return builder.build();
  }

  @Test
  public void invalidMappingFile_doesNotExist_customLocation() {
    PlatformMappingException exception =
        assertThrows(
            PlatformMappingException.class,
            () ->
                executeFunction(
                    PlatformMappingKey.createExplicitlySet(
                        PathFragment.create("random_location"))));
    assertThat(exception).hasCauseThat().isInstanceOf(MissingInputFileException.class);
    assertThat(exception).hasMessageThat().contains("random_location");
  }

  @Test
  public void invalidMappingFile_doesNotExist_defaultLocation() throws Exception {
    PlatformMappingValue platformMappingValue = executeFunction(PlatformMappingKey.DEFAULT);

    BuildOptions mapped = platformMappingValue.map(createBuildOptions()).getOptions();

    assertThat(mapped.get(PlatformOptions.class).platforms)
        .containsExactly(Label.parseCanonicalUnchecked("@bazel_tools//tools:host_platform"));
  }

  @Test
  public void invalidMappingFile_isDirectory() throws Exception {
    scratch.dir("somedir");

    PlatformMappingException exception =
        assertThrows(
            PlatformMappingException.class,
            () ->
                executeFunction(
                    PlatformMappingKey.createExplicitlySet(PathFragment.create("somedir"))));
    assertThat(exception).hasCauseThat().isInstanceOf(MissingInputFileException.class);
    assertThat(exception).hasMessageThat().contains("somedir");
  }

  @Test
  public void mapFromPlatform() throws Exception {
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=one
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  @Test
  public void mapFromPlatform_fromAlternatePackagePath() throws Exception {
    scratch.setWorkingDir("/other/package/path");
    scratch.copyFile(rootDirectory.getRelative("MODULE.bazel").getPathString(), "MODULE.bazel");
    setPackageOptions("--package_path=/other/package/path");
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=one
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  @Test
  public void mapFromPlatform_noWorkspace() throws Exception {
    // --package_path is not relevant for Bazel and difficult to get to work correctly with
    // WORKSPACE suffixes in tests.
    if (analysisMock.isThisBazel()) {
      return;
    }

    scratch.setWorkingDir("/other/package/path");
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=one
        """);
    setPackageOptions("--package_path=/other/package/path");

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));
    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  @Test
  public void multiplePackagePaths() throws Exception {
    scratch.setWorkingDir("/other/package/path");
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=one
        """);
    setPackageOptions("--package_path=%workspace%:/other/package/path");

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  @Test
  public void multiplePackagePathsFirstWins() throws Exception {
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=one
        """);
    scratch.setWorkingDir("/other/package/path");
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=two
        """);
    setPackageOptions("--package_path=%workspace%:/other/package/path");

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  // Internal flags (OptionMetadataTag.INTERNAL) cannot be set from the command-line, but
  // platform mapping needs to access them.
  @Test
  public void mapFromPlatform_internalOption() throws Exception {
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --internal_option=something_new
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(DummyTestOptions.class).internalOption).isEqualTo("something_new");
  }

  @Test
  public void mapFromPlatform_starlarkFlag() throws Exception {
    writeStarlarkFlag();
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --//flag:my_string_flag=mapped_value
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.getStarlarkOptions())
        .containsExactly(Label.parseCanonical("//flag:my_string_flag"), "mapped_value");
  }

  @Test
  public void mapFromPlatform_listFlag_overridesConfig() throws Exception {
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --list=from_mapping
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions =
        createBuildOptions("--platforms=//platforms:one", "--list=from_config");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    // The mapping should completely replace the list, because it is not accumulating.
    assertThat(mapped.get(DummyTestOptions.class).list).containsExactly("from_mapping");
  }

  @Test
  public void mapFromPlatform_badStarlarkFlag() throws Exception {
    scratch.file("test/BUILD"); // Set up a valid package but invalid flag.
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --//test:this_flag_doesnt_exist=mapped_value
        """);

    PlatformMappingException exception =
        assertThrows(
            PlatformMappingException.class,
            () ->
                executeFunction(
                    PlatformMappingKey.createExplicitlySet(
                        PathFragment.create("my_mapping_file"))));
    assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException.class);
    assertThat(exception).hasMessageThat().contains("Failed to load //test:this_flag_doesnt_exist");
  }

  @Test
  public void platformTransitionWithStarlarkFlagMapping() throws Exception {
    writeStarlarkFlag();

    // Define a custom platform and mapping from that platform to the flag:
    scratch.file(
        "test/platforms/BUILD",
        """
        platform(
            name = "my_platform",
        )
        """);
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //test/platforms:my_platform
            --//flag:my_string_flag=platform-mapped value
        """);

    // Define a rule that platform-transitions its deps:
    scratch.overwriteFile(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        """);
    scratch.file(
        "test/starlark/rules.bzl",
        """
        def transition_func(settings, attr):
            return {"//command_line_option:platforms": "//test/platforms:my_platform"}

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:platforms"],
        )
        transition_rule = rule(
            implementation = lambda ctx: [],
            attrs = {
                "dep": attr.label(cfg = my_transition),
            },
        )
        """);
    scratch.file("test/starlark/BUILD");

    // Define a target to build and its dep:
    scratch.file(
        "test/BUILD",
        """
        load("//test/starlark:rules.bzl", "transition_rule")

        transition_rule(
            name = "main",
            dep = ":dep",
        )

        transition_rule(name = "dep")
        """);

    // Set the Starlark flag explicitly. Otherwise it won't show up at all in the top-level config's
    // getOptions().getStarlarkOptions() map.
    useConfiguration(
        "--//flag:my_string_flag=top-level value", "--platform_mappings=my_mapping_file");
    ConfiguredTarget main = getConfiguredTarget("//test:main");
    ConfiguredTarget dep = getDirectPrerequisite(main, "//test:dep");

    assertThat(getConfiguration(main).getOptions().getStarlarkOptions())
        .containsAtLeast(Label.parseCanonical("//flag:my_string_flag"), "top-level value");
    assertThat(getConfiguration(dep).getOptions().getStarlarkOptions())
        .containsAtLeast(Label.parseCanonical("//flag:my_string_flag"), "platform-mapped value");
  }

  @Test
  public void mapFromFlag() throws Exception {
    scratch.file(
        "my_mapping_file",
        """
        flags:
          --str_option=one
              //platforms:one
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--str_option=one");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(PlatformOptions.class).platforms).containsExactly(PLATFORM1);
  }

  @Test
  public void mapFromFlag_starlarkFlag() throws Exception {
    writeStarlarkFlag();
    scratch.file(
        "my_mapping_file",
        """
        flags:
          --//flag:my_string_flag=mapped_value
            //platforms:one
        """);

    PlatformMappingValue platformMappingValue =
        executeFunction(
            PlatformMappingKey.createExplicitlySet(PathFragment.create("my_mapping_file")));

    BuildOptions modifiedOptions = createBuildOptions("--//flag:my_string_flag=mapped_value");

    BuildOptions mapped = platformMappingValue.map(modifiedOptions).getOptions();

    assertThat(mapped.get(PlatformOptions.class).platforms).containsExactly(PLATFORM1);
  }

  @Test
  public void mapFromFlag_badStarlarkFlag() throws Exception {
    scratch.file("test/BUILD"); // Set up a valid package but invalid flag.
    scratch.file(
        "my_mapping_file",
        """
        flags:
          --//test:this_flag_doesnt_exist=mapped_value
            //platforms:one
        """);

    PlatformMappingException exception =
        assertThrows(
            PlatformMappingException.class,
            () ->
                executeFunction(
                    PlatformMappingKey.createExplicitlySet(
                        PathFragment.create("my_mapping_file"))));
    assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException.class);
    assertThat(exception).hasMessageThat().contains("Failed to load //test:this_flag_doesnt_exist");
  }

  @Test
  public void mappingSyntaxError() throws Exception {
    scratch.file("test/BUILD");
    scratch.file(
        "my_mapping_file",
        """
        platforms:
          //platforms:one
            --str_option=k8
          # Duplicate platform label
          //platforms:one
            --str_option=arm
        """);

    PlatformMappingException exception =
        assertThrows(
            PlatformMappingException.class,
            () ->
                executeFunction(
                    PlatformMappingKey.createExplicitlySet(
                        PathFragment.create("my_mapping_file"))));
    assertThat(exception).hasCauseThat().isInstanceOf(PlatformMappingParsingException.class);
    assertThat(exception).hasMessageThat().contains("Got duplicate platform entries");
  }

  private void writeStarlarkFlag() throws Exception {
    scratch.file(
        "flag/build_setting.bzl",
        """
        def _impl(ctx):
            return []

        string_flag = rule(
            implementation = _impl,
            build_setting = config.string(flag = True),
        )
        """);
    scratch.file(
        "flag/BUILD",
        """
        load("//flag:build_setting.bzl", "string_flag")

        string_flag(
            name = "my_string_flag",
            build_setting_default = "default value",
        )
        """);
  }

  private PlatformMappingValue executeFunction(PlatformMappingKey key) throws Exception {
    SkyframeExecutor skyframeExecutor = getSkyframeExecutor();
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(
                RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE, Optional.empty())));
    EvaluationResult<PlatformMappingValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    if (result.hasError()) {
      throw result.getError(key).getException();
    }
    return result.get(key);
  }
}
