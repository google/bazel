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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.Fragment;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.RequiresOptions;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
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
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of {@link BuildConfigurationKeyMapProducer}. */
@RunWith(JUnit4.class)
public class BuildConfigurationKeyMapProducerTest extends ProducerTestCase {

  /** Extra options for this test. */
  public static class DummyTestOptions extends FragmentOptions {
    public DummyTestOptions() {}

    @Option(
        name = "option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "super secret")
    public String option;

    @Option(
        name = "internal_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "super secret",
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

  @Test
  public void createKey() throws Exception {
    BuildOptions baseOptions =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=from_cmd");
    BuildConfigurationKey result = fetch(baseOptions);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_cmd");
  }

  @Test
  public void createKeys_preservesOrder() throws Exception {
    BuildOptions baseOptions1 =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=first");
    BuildOptions baseOptions2 =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=second");
    BuildOptions baseOptions3 =
        createBuildOptions("--platforms=//platforms:sample", "--internal_option=third");

    // Use a sorted map implementation to ensure consistent ordering of the input.
    SortedMap<String, BuildOptions> input =
        new ImmutableSortedMap.Builder<String, BuildOptions>(Ordering.natural())
            .put("first", baseOptions1)
            .put("second", baseOptions2)
            .put("third", baseOptions3)
            .buildOrThrow();
    assertThat(input.keySet()).containsExactly("first", "second", "third").inOrder();
    ImmutableMap<String, BuildConfigurationKey> result = fetch(input);

    assertThat(result).isNotNull();
    assertThat(result.keySet()).containsExactly("first", "second", "third").inOrder();
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
    BuildConfigurationKey result = fetch(baseOptions);

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
    assertThrows(PlatformMappingException.class, () -> fetch(baseOptions));
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
    var e = assertThrows(PlatformMappingException.class, () -> fetch(baseOptions));
    assertThat(e).hasMessageThat().contains("Unrecognized option: --fake_option");
  }

  @Test
  public void createKey_platformFlags() throws Exception {
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
    BuildConfigurationKey result = fetch(baseOptions);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_platform");
  }

  @Test
  public void createKey_platformFlags_override() throws Exception {
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
    BuildConfigurationKey result = fetch(baseOptions);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).option).isEqualTo("from_platform");
  }

  @Test
  // Re-enable this once merging repeatable flags works properly.
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
    BuildConfigurationKey result = fetch(baseOptions);

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
    assertThrows(InvalidPlatformException.class, () -> fetch(baseOptions));
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

    useConfiguration("--platforms=//platforms:sample");
    BuildOptions baseOptions = createBuildOptions("--platforms=//platforms:sample");
    assertThrows(OptionsParsingException.class, () -> fetch(baseOptions));
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
    BuildConfigurationKey result = fetch(baseOptions);

    assertThat(result).isNotNull();
    assertThat(result.getOptions().get(DummyTestOptions.class).internalOption)
        .isEqualTo("from_platform");
  }

  private BuildConfigurationKey fetch(BuildOptions options)
      throws InterruptedException,
          OptionsParsingException,
          PlatformMappingException,
          InvalidPlatformException,
          BuildOptionsScopeFunctionException {
    ImmutableMap<String, BuildConfigurationKey> result = fetch(ImmutableMap.of("only", options));
    return result.get("only");
  }

  private ImmutableMap<String, BuildConfigurationKey> fetch(Map<String, BuildOptions> options)
      throws InterruptedException,
          OptionsParsingException,
          PlatformMappingException,
          InvalidPlatformException,
          BuildOptionsScopeFunctionException {
    Sink sink = new Sink();
    BuildConfigurationKeyMapProducer producer =
        new BuildConfigurationKeyMapProducer(sink, StateMachine.DONE, options, null);
    // Ignore the return value: sink will either return a result or re-throw whatever exception it
    // received from the producer.
    var unused = executeProducer(producer);
    return sink.options();
  }

  /** Receiver for platform info from {@link PlatformProducer}. */
  private static class Sink implements BuildConfigurationKeyMapProducer.ResultSink {
    @Nullable private OptionsParsingException optionsParsingException;
    @Nullable private PlatformMappingException platformMappingException;
    @Nullable private InvalidPlatformException invalidPlatformException;
    @Nullable private BuildOptionsScopeFunctionException buildOptionsScopeFunctionException;
    @Nullable private ImmutableMap<String, BuildConfigurationKey> keys;

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
    public void acceptTransitionedConfigurations(ImmutableMap<String, BuildConfigurationKey> keys) {
      this.keys = keys;
    }

    @Override
    public void acceptBuildOptionsScopeFunctionError(BuildOptionsScopeFunctionException e) {
      this.buildOptionsScopeFunctionException = e;
    }

    ImmutableMap<String, BuildConfigurationKey> options()
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
      if (this.keys != null) {
        return this.keys;
      }
      throw new IllegalStateException("Value and exception not set");
    }
  }
}
