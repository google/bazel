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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.output.ConfigurationForOutput;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.runtime.BlazeCommandDispatcher;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.ConfigurationDiffForOutput;
import com.google.devtools.build.lib.runtime.commands.ConfigCommand.FragmentDiffForOutput;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.io.RecordingOutErr;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ConfigCommand} ("<code>$ blaze config</code>").
 *
 * <p>These tests assume all important testable properties are determined in {@link ConfigCommand},
 * so the output formatter used doesn't affect those properties. We test with <code>--output=json
 * </code> for easy parsing.
 */
@RunWith(JUnit4.class)
public class ConfigCommandTest extends BuildIntegrationTestCase {
  private BlazeCommandDispatcher dispatcher;

  @Before
  public final void init() throws Exception {
    getRuntime().overrideCommands(ImmutableList.of(new BuildCommand(), new ConfigCommand()));
    dispatcher = new BlazeCommandDispatcher(getRuntime());
    write(
        "tools/allowlists/function_transition_allowlist/BUILD",
        """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//..."],
        )
        """);
    write(
        "test/defs.bzl",
        """
        def _simple_rule_impl(ctx):
            pass

        simple_rule = rule(
            implementation = _simple_rule_impl,
            attrs = {},
        )

        def _sometransition_impl(settings, attr):
            _ignore = (settings, attr)
            return {"//command_line_option:platform_suffix": "transitioned"}

        _sometransition = transition(
            implementation = _sometransition_impl,
            inputs = [],
            outputs = ["//command_line_option:platform_suffix"],
        )
        rule_with_transition = rule(
            implementation = _simple_rule_impl,
            cfg = _sometransition,
        )
        """);
    write(
        "test/BUILD",
        """
        load("//test:defs.bzl", "rule_with_transition", "simple_rule")

        simple_rule(name = "buildme")

        rule_with_transition(name = "buildme_with_transition")
        """);
  }

  /**
   * Performs loading and analysis on the fixed rule <code>//test:buildme</code> with the given
   * build options (as they'd appear on the command line).
   */
  private void analyzeTarget(String... args) throws Exception {
    List<String> params = Lists.newArrayList("build");
    // Ideally we'd directly use BlazeRuntimeWrapper, but it's explicitly documented as not
    // exercising the command dispatcher, which is exactly what we need here.
    params.addAll(runtimeWrapper.getOptions());
    params.add("//test:buildme");
    params.add("--nobuild"); // Execution phase isn't necessary to collect configurations.
    Collections.addAll(params, args);
    dispatcher.exec(params, "my client", outErr);
  }

  /**
   * Performs loading and analysis on the fixed rule <code>//test:buildme</code> with the given
   * build options (as they'd appear on the command line).
   */
  private void analyzeTargetWithTransition(String... args) throws Exception {
    List<String> params = Lists.newArrayList("build");
    // Ideally we'd directly use BlazeRuntimeWrapper, but it's explicitly documented as not
    // exercising the command dispatcher, which is exactly what we need here.
    params.addAll(runtimeWrapper.getOptions());
    params.add("//test:buildme_with_transition");
    params.add("--nobuild"); // Execution phase isn't necessary to collect configurations.
    Collections.addAll(params, args);
    dispatcher.exec(params, "my client", outErr);
  }

  /**
   * Calls <cod>blaze config --output=json</code> with the given flags and returns the raw output.
   *
   * <p>Should be called after {@link #analyzeTarget} so there are actual configs to read.
   */
  private RecordingOutErr callConfigCommand(String... args) throws Exception {
    List<String> params = Lists.newArrayList("config");
    params.add("--output=json");
    Collections.addAll(params, args);
    RecordingOutErr recordingOutErr = new RecordingOutErr();
    dispatcher.exec(params, "my client", recordingOutErr);
    return recordingOutErr;
  }

  /**
   * Returns the value of an option under a configuration's {@link FragmentOptions}.
   *
   * <p>Throws {@link NoSuchElementException} if it can't be found.
   */
  private static String getOptionValue(
      ConfigurationForOutput config, String fragmentOptions, String optionName) {
    List<String> ans =
        config.getFragmentOptions().stream()
            .filter(fragment -> fragment.getName().endsWith(fragmentOptions))
            .flatMap(fragment -> fragment.getOptions().entrySet().stream())
            .filter(setting -> setting.getKey().equals(optionName))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    if (ans.size() > 1) {
      throw new NoSuchElementException(
          String.format(
              "Multiple matches for fragment=%s, option=%s", fragmentOptions, optionName));
    } else if (ans.isEmpty()) {
      throw new NoSuchElementException(
          String.format("No matches for fragment=%s, option=%s", fragmentOptions, optionName));
    }
    return ans.get(0);
  }

  private static boolean isTargetConfig(ConfigurationForOutput config) {
    if (config.getMnemonic().endsWith("-noconfig")) {
      return false;
    }
    return !Boolean.parseBoolean(getOptionValue(config, "CoreOptions", "is exec configuration"));
  }

  /** Converts {@code a.b.d} to {@code d}. */
  private static String getBaseName(String str) {
    return str.substring(str.lastIndexOf(".") + 1);
  }

  /** Converts a list of {@code a.b.d} strings to {@code d} form. */
  private static List<String> getBaseNames(List<String> list) {
    return list.stream().map(ConfigCommandTest::getBaseName).collect(Collectors.toList());
  }

  @Test
  public void showConfigIds() throws Exception {
    analyzeTarget();
    JsonObject fullJson =
        JsonParser.parseString(callConfigCommand().outAsLatin1()).getAsJsonObject();
    // Should be: target configuration, target configuration without test.
    assertThat(fullJson).isNotNull();
    assertThat(fullJson.has("configuration-IDs")).isTrue();
    assertThat(fullJson.get("configuration-IDs").getAsJsonArray().size()).isEqualTo(3);
  }

  private boolean skipNoConfig(JsonElement configHash) {
    try {
      return !new Gson()
          .fromJson(
              callConfigCommand(configHash.getAsString()).outAsLatin1(),
              ConfigurationForOutput.class)
          .getMnemonic()
          .contains("-noconfig");
    } catch (Exception e) {
      assertWithMessage("Failed to retrieve %s: %s", configHash.getAsString(), e.getMessage())
          .fail();
      return false;
    }
  }

  /**
   * Calls the config command to return all config hashes currently available.
   *
   * @param includeNoConfig if true, include the "noconfig" configuration (see {@link
   *     com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition}. Else filter
   *     it out.
   */
  private ImmutableList<String> getConfigHashes(boolean includeNoConfig) throws Exception {
    return stream(
            JsonParser.parseString(callConfigCommand().outAsLatin1())
                .getAsJsonObject()
                .get("configuration-IDs")
                .getAsJsonArray()
                .iterator())
        .filter(includeNoConfig ? Predicates.alwaysTrue() : this::skipNoConfig)
        .map(c -> c.getAsString())
        .collect(toImmutableList());
  }

  @Test
  public void showSingleConfig() throws Exception {
    analyzeTarget();
    // Find the first non-noconfig configuration (see NoConfigTransition). noconfig is a special
    // configuration that strips away most of its structure, so not a good candidate for this test.
    String configHash = getConfigHashes(/* includeNoConfig= */ false).get(0);
    ConfigurationForOutput config =
        new Gson()
            .fromJson(callConfigCommand(configHash).outAsLatin1(), ConfigurationForOutput.class);

    assertThat(config).isNotNull();
    // Verify config metadata:
    assertThat(config.getConfigHash()).isEqualTo(configHash);
    assertThat(config.getSkyKey())
        .isEqualTo(String.format("BuildConfigurationKey[%s]", configHash));
    // Verify the existence of a couple of expected fragments:
    assertThat(
            config.getFragments().stream()
                .map(
                    fragment ->
                        Pair.of(
                            getBaseName(fragment.getName()),
                            getBaseNames(fragment.getFragmentOptions())))
                .collect(Collectors.toList()))
        .containsAtLeast(
            Pair.of("PlatformConfiguration", ImmutableList.of("PlatformOptions")),
            Pair.of("TestConfiguration", ImmutableList.of("TestConfiguration$TestOptions")));
    // Verify the existence of a couple of expected fragment options:
    assertThat(
            config.getFragmentOptions().stream()
                .map(fragment -> getBaseName(fragment.getName()))
                .collect(Collectors.toList()))
        .containsAtLeast("PlatformOptions", "CoreOptions", "user-defined");
    // Verify the existence of a couple of expected option names:
    assertThat(
            config.getFragmentOptions().stream()
                .filter(fragment -> fragment.getName().endsWith("CoreOptions"))
                .flatMap(fragment -> fragment.getOptions().keySet().stream())
                .collect(Collectors.toList()))
        .containsAtLeast("run_under", "check_visibility", "stamp");
  }

  @Test
  public void showSingleConfigHashPrefix() throws Exception {
    analyzeTarget();
    String configHash =
        JsonParser.parseString(callConfigCommand().outAsLatin1())
            .getAsJsonObject()
            .get("configuration-IDs")
            .getAsJsonArray()
            .get(0)
            .getAsString();
    String hashPrefix = configHash.substring(0, configHash.length() / 2);
    ConfigurationForOutput config =
        new Gson()
            .fromJson(callConfigCommand(hashPrefix).outAsLatin1(), ConfigurationForOutput.class);
    assertThat(config).isNotNull();
    assertThat(config.getConfigHash()).startsWith(hashPrefix);
  }

  @Test
  public void unknownHashPrefix() throws Exception {
    analyzeTarget();
    String configHash =
        JsonParser.parseString(callConfigCommand().outAsLatin1())
            .getAsJsonObject()
            .get("configuration-IDs")
            .getAsJsonArray()
            .get(0)
            .getAsString();
    // No valid hash has spaces.
    String hashPrefix = configHash.substring(0, configHash.length() / 2) + " ";
    assertThat(callConfigCommand(hashPrefix).errAsLatin1())
        .contains("No configuration found with ID prefix " + hashPrefix);
  }

  @Test
  public void showAllConfigs() throws Exception {
    analyzeTarget();

    int numConfigs = 0;
    for (JsonElement configJson :
        JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1()).getAsJsonArray()) {
      ConfigurationForOutput config = new Gson().fromJson(configJson, ConfigurationForOutput.class);
      assertThat(config).isNotNull();
      numConfigs++;
    }
    assertThat(numConfigs).isEqualTo(3); // Target + target w/o test + nonConfig.
  }

  @Test
  public void compareConfigs() throws Exception {
    // Do not trim test configuration for now to make 'finding' the configurations easier.
    analyzeTargetWithTransition("--platform_suffix=pure", "--notrim_test_configuration");
    String targetConfig1Hash = getTargetConfig().getConfigHash();
    String targetConfig2Hash =
        getTargetConfig(/* excludedHashes= */ ImmutableSet.of(targetConfig1Hash)).getConfigHash();

    // Get their diff.
    String result = callConfigCommand(targetConfig1Hash, targetConfig2Hash).outAsLatin1();
    ConfigurationDiffForOutput diff = new Gson().fromJson(result, ConfigurationDiffForOutput.class);
    assertThat(diff).isNotNull();
    assertThat(diff.configHash1).isEqualTo(targetConfig1Hash);
    assertThat(diff.configHash2).isEqualTo(targetConfig2Hash);
    FragmentDiffForOutput fragmentDiff = Iterables.getOnlyElement(diff.fragmentsDiff);
    assertThat(fragmentDiff.name).endsWith("CoreOptions");
    Map.Entry<String, Pair<String, String>> optionDiff =
        Iterators.getOnlyElement(
            fragmentDiff.optionsDiff.entrySet().stream()
                .filter(x -> !x.getKey().equals("affected by starlark transition"))
                .iterator());
    assertThat(optionDiff.getKey()).isEqualTo("platform_suffix");
    // Convert from Pair<firstVal, secondVal> to an ImmutableList because the ordering of the
    // difference depends on which configuration comes first, which depends on the configuration
    // hash name, which we can't predict statically.
    assertThat(ImmutableList.of(optionDiff.getValue().first, optionDiff.getValue().second))
        .containsExactly("pure", "transitioned");
  }

  @Test
  public void compareConfigsHashPrefix() throws Exception {
    // Do not trim test configuration for now to make 'finding' the configurations easier.
    analyzeTargetWithTransition("--platform_suffix=pure", "--notrim_test_configuration");
    String targetConfig1Hash = getTargetConfig().getConfigHash();
    String targetConfig2Hash =
        getTargetConfig(/* excludedHashes= */ ImmutableSet.of(targetConfig1Hash)).getConfigHash();

    String hashPrefix1 = targetConfig1Hash.substring(0, targetConfig1Hash.length() / 2);
    String hashPrefix2 = targetConfig2Hash.substring(0, targetConfig2Hash.length() / 2);

    ConfigurationDiffForOutput diff =
        new Gson()
            .fromJson(
                callConfigCommand(hashPrefix1, hashPrefix2).outAsLatin1(),
                ConfigurationDiffForOutput.class);
    assertThat(diff).isNotNull();
    assertThat(diff.configHash1).startsWith(hashPrefix1);
    assertThat(diff.configHash2).startsWith(hashPrefix2);
  }

  private ConfigurationForOutput getTargetConfig() throws Exception {
    return getTargetConfig(ImmutableSet.of());
  }

  private ConfigurationForOutput getTargetConfig(ImmutableSet<String> excludedHashes)
      throws Exception {
    // Find a target configuration hash.
    for (JsonElement element :
        JsonParser.parseString(callConfigCommand().outAsLatin1())
            .getAsJsonObject()
            .get("configuration-IDs")
            .getAsJsonArray()) {
      String configHash = element.getAsString();
      if (excludedHashes.contains(configHash)) {
        continue;
      }
      ConfigurationForOutput config =
          new Gson()
              .fromJson(callConfigCommand(configHash).outAsLatin1(), ConfigurationForOutput.class);
      if (isTargetConfig(config)) {
        return config;
      }
    }
    throw new AssertionError("Should have found config hash");
  }

  @Test
  public void starlarkFlagsInUserDefinedFragment() throws Exception {
    write(
        "test/flagdef.bzl",
        """
        def _rule_impl(ctx):
            return []

        string_flag = rule(
            implementation = _rule_impl,
            build_setting = config.string(flag = True),
        )
        simple_rule = rule(
            implementation = _rule_impl,
            attrs = {},
        )
        """);
    write(
        "custom_flags/BUILD",
        """
        load("//test:flagdef.bzl", "string_flag")

        string_flag(
            name = "my_flag",
            build_setting_default = "",
        )
        """);

    analyzeTarget("--//custom_flags:my_flag=hello");

    ConfigurationForOutput targetConfig = null;
    String result = callConfigCommand("--dump_all").outAsLatin1();
    for (JsonElement configJson : JsonParser.parseString(result).getAsJsonArray()) {
      ConfigurationForOutput config = new Gson().fromJson(configJson, ConfigurationForOutput.class);
      if (isTargetConfig(config)) {
        targetConfig = config;
        break;
      }
    }

    assertThat(targetConfig).isNotNull();
    assertThat(getOptionValue(targetConfig, "user-defined", "//custom_flags:my_flag"))
        .isEqualTo("hello");
  }

  @Test
  public void defineFlagsIndividuallyListedInUserDefinedFragment() throws Exception {
    analyzeTarget("--define", "a=1", "--define", "b=2");

    ConfigurationForOutput targetConfig = null;
    for (JsonElement configJson :
        JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1()).getAsJsonArray()) {
      ConfigurationForOutput config = new Gson().fromJson(configJson, ConfigurationForOutput.class);
      if (isTargetConfig(config)) {
        targetConfig = config;
        break;
      }
    }

    assertThat(targetConfig).isNotNull();
    assertThat(getOptionValue(targetConfig, "user-defined", "--define:a")).isEqualTo("1");
    assertThat(getOptionValue(targetConfig, "user-defined", "--define:b")).isEqualTo("2");
    assertThat(
            targetConfig.getFragmentOptions().stream()
                .filter(fragment -> fragment.getName().endsWith("CoreOptions"))
                .flatMap(fragment -> fragment.getOptions().keySet().stream())
                .filter(name -> name.equals("define"))
                .collect(Collectors.toList()))
        .isEmpty();
  }

  @Test
  public void conflictingDefinesLastWins() throws Exception {
    analyzeTarget("--define", "a=1", "--define", "a=2");

    ConfigurationForOutput targetConfig = null;
    for (JsonElement configJson :
        JsonParser.parseString(callConfigCommand("--dump_all").outAsLatin1()).getAsJsonArray()) {
      ConfigurationForOutput config = new Gson().fromJson(configJson, ConfigurationForOutput.class);
      if (isTargetConfig(config)) {
        targetConfig = config;
        break;
      }
    }

    assertThat(targetConfig).isNotNull();
    assertThat(getOptionValue(targetConfig, "user-defined", "--define:a")).isEqualTo("2");
    assertThat(
            targetConfig.getFragmentOptions().stream()
                .filter(fragment -> fragment.getName().endsWith("CoreOptions"))
                .flatMap(fragment -> fragment.getOptions().keySet().stream())
                .filter(name -> name.equals("define"))
                .collect(Collectors.toList()))
        .isEmpty();
  }
}
