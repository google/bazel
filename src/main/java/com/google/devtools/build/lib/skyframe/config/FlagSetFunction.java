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
package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.common.options.OptionsParser.STARLARK_SKIPPED_PREFIXES;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.runtime.ConfigFlagDefinitions;
import com.google.devtools.build.lib.skyframe.ProjectValue;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.GlobalRcUtils;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Collection;
import java.util.HashSet;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;

/**
 * A SkyFunction that, given an scl file path and the name of scl configs, does the following:
 *
 * <ol>
 *   <li>calls {@link com.google.devtools.build.lib.skyframe.ProjectFunction} to load the content of
 *       scl files given the provided scl config name
 *   <li>calls {@link ParsedFlagsFunction} to parse the list of options
 *   <li>returns the list of flags in command line format to be applied to the build
 * </ol>
 *
 * <p>If --enforce_project_configs is set, invalid --scl_config values or invalid project files will
 * cause the build to fail.
 */
public final class FlagSetFunction implements SkyFunction {
  private static final String CONFIGS = "configs";

  private static final String DEFAULT_CONFIG = "default_config";
  private static final String ENFORCEMENT_POLICY = "enforcement_policy";
  private static final String ALWAYS_ALLOWED_CONFIGS = "always_allowed_configs";

  private enum EnforcementPolicy {
    WARN("warn"), // Default, enforced in getSclConfig().
    COMPATIBLE("compatible"),
    STRICT("strict");

    EnforcementPolicy(String value) {
      this.value = value;
    }

    private final String value;

    public static EnforcementPolicy fromString(String value) {
      for (EnforcementPolicy policy : EnforcementPolicy.values()) {
        if (policy.value.equals(value)) {
          return policy;
        }
      }
      throw new IllegalArgumentException(String.format("invalid enforcement_policy '%s'", value));
    }
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws FlagSetFunctionException, InterruptedException {
    FlagSetValue.Key key = (FlagSetValue.Key) skyKey.argument();
    if (!key.enforceCanonical()) {
      if (!key.getSclConfig().isEmpty()) {
        env.getListener()
            .handle(
                Event.info(
                    String.format(
                        "Ignoring --scl_config=%s because --enforce_project_configs is not set",
                        key.getSclConfig())));
      }
      // --noenforce_project_configs. Nothing to do.
      return FlagSetValue.create(ImmutableSet.of(), ImmutableSet.of());
    }
    ProjectValue projectValue =
        (ProjectValue) env.getValue(new ProjectValue.Key(key.getProjectFile()));
    if (projectValue == null) {
      return null;
    }

    // Skyframe doesn't replay warnings or info messages on cache hits: see Event.storeForReplay and
    // Reportable.storeForReplay. We want some flag set messages to be more persistent, so we
    // return them in the Skyvalue for the caller to emit.
    ImmutableSet.Builder<Event> persistentMessages = ImmutableSet.builder();
    ImmutableSet<String> sclConfigAsStarlarkList =
        getSclConfig(key, projectValue, persistentMessages);
    return FlagSetValue.create(sclConfigAsStarlarkList, persistentMessages.build());
  }

  /**
   * Given an .scl file and {@code --scl_config} value, returns the flags denoted by that {@code
   * --scl_config}. Flags are a list of strings (not parsed through the options parser).
   */
  @SuppressWarnings("unchecked")
  private static ImmutableSet<String> getSclConfig(
      FlagSetValue.Key key, ProjectValue sclContent, ImmutableSet.Builder<Event> persistentMessages)
      throws FlagSetFunctionException {
    Label projectFile = key.getProjectFile();
    String sclConfigName = key.getSclConfig();
    EnforcementPolicy enforcementPolicy = EnforcementPolicy.WARN;
    Object enforcementPolicyRaw = sclContent.getProject().get(ENFORCEMENT_POLICY);
    if (enforcementPolicyRaw != null) {
      try {
        enforcementPolicy = EnforcementPolicy.fromString(enforcementPolicyRaw.toString());
      } catch (IllegalArgumentException e) {
        throw new FlagSetFunctionException(
            new InvalidProjectFileException(e.getMessage() + " in " + projectFile),
            Transience.PERSISTENT);
      }
    }
    var unTypeCheckedConfigs = sclContent.getProject().get(CONFIGS);
    // This project file doesn't define configs, so it must not be used for canonical configs.
    if (unTypeCheckedConfigs == null) {
      return ImmutableSet.of();
    }
    boolean expectedConfigsType = false;
    if (unTypeCheckedConfigs instanceof Dict<?, ?> configsAsDict) {
      expectedConfigsType = true;
      for (var entry : configsAsDict.entrySet()) {
        if (!(entry.getKey() instanceof String
            && entry.getValue() instanceof Collection<?> values)) {
          expectedConfigsType = false;
          break;
        }
        for (var value : values) {
          if (!(value instanceof String)) {
            expectedConfigsType = false;
            break;
          }
        }
      }
    }
    if (!expectedConfigsType) {
      throw new FlagSetFunctionException(
          new InvalidProjectFileException(
              String.format("%s variable must be a map of strings to lists of strings", CONFIGS)),
          Transience.PERSISTENT);
    }
    var configs = (Dict<String, Collection<String>>) unTypeCheckedConfigs;

    String sclConfigNameForMessage = sclConfigName;
    Collection<String> sclConfigValue = null;
    if (sclConfigName.isEmpty()) {
      // If there's no --scl_config, try to use the default_config.
      var defaultConfigNameRaw = sclContent.getProject().get(DEFAULT_CONFIG);
      try {
        if (defaultConfigNameRaw != null && !(defaultConfigNameRaw instanceof String)) {
          throw new FlagSetFunctionException(
              new InvalidProjectFileException(
                  String.format(
                      "%s must be a string matching a %s variable definition",
                      DEFAULT_CONFIG, CONFIGS)),
              Transience.PERSISTENT);
        }

        String defaultConfigName = (String) defaultConfigNameRaw;
        sclConfigValue = validateDefaultConfig(defaultConfigName, configs);
        sclConfigNameForMessage = defaultConfigName;
      } catch (InvalidProjectFileException e) {
        throw new FlagSetFunctionException(
            new UnsupportedConfigException(
                String.format(
                    "This project's builds must set --scl_config because %s.\n%s",
                    e.getMessage(), supportedConfigsDesc(projectFile, configs))),
            Transience.PERSISTENT);
      }
    } else {
      if (!configs.containsKey(sclConfigName)) {
        // The user set --scl_config to an unknown config.
        throw new FlagSetFunctionException(
            new UnsupportedConfigException(
                String.format(
                    "--scl_config=%s is not a valid configuration for this project.%s",
                    sclConfigName, supportedConfigsDesc(projectFile, configs))),
            Transience.PERSISTENT);
      }
      sclConfigValue = configs.get(sclConfigName);
    }

    // Replace --config=foo entries with their expanded definitions.
    sclConfigValue =
        expandConfigFlags(sclConfigName, sclConfigValue, key.getConfigFlagDefinitions());

    ImmutableList<String> buildOptionsAsStrings = getBuildOptionsAsStrings(key.getTargetOptions());
    ImmutableSet<String> optionsToApply = filterOptions(sclConfigValue, buildOptionsAsStrings);

    if (optionsToApply.isEmpty()) {
      return ImmutableSet.of();
    }

    Collection<String> alwaysAllowedConfigs =
        !sclContent.getProject().containsKey(ALWAYS_ALLOWED_CONFIGS)
            ? ImmutableList.of()
            : (Collection<String>) sclContent.getProject().get(ALWAYS_ALLOWED_CONFIGS);

    validateNoExtraFlagsSet(
        enforcementPolicy,
        alwaysAllowedConfigs,
        buildOptionsAsStrings,
        key.getUserOptions(),
        optionsToApply,
        persistentMessages,
        projectFile);
    persistentMessages.add(
        Event.info(
            String.format(
                "Applying flags from the config '%s' defined in %s: %s ",
                sclConfigNameForMessage, projectFile, optionsToApply)));
    return optionsToApply;
  }

  private static Collection<String> validateDefaultConfig(
      @Nullable String defaultConfigName, Dict<String, Collection<String>> configs)
      throws InvalidProjectFileException {
    if (defaultConfigName == null) {
      throw new InvalidProjectFileException("no default_config is defined");
    }

    if (!configs.containsKey(defaultConfigName)) {
      throw new InvalidProjectFileException(
          String.format("default_config refers to a nonexistent config: %s", defaultConfigName));
    }

    return configs.get(defaultConfigName);
  }

  private static ImmutableList<String> getBuildOptionsAsStrings(BuildOptions targetOptions) {
    ImmutableList.Builder<String> allOptionsAsStringsBuilder = new ImmutableList.Builder<>();

    // Collect a list of BuildOptions, excluding TestOptions.
    targetOptions.getStarlarkOptions().keySet().stream()
        .map(Object::toString)
        .forEach(allOptionsAsStringsBuilder::add);
    for (FragmentOptions fragmentOptions : targetOptions.getNativeOptions()) {
      if (fragmentOptions.getClass().equals(TestConfiguration.TestOptions.class)) {
        continue;
      }
      fragmentOptions.asMap().keySet().forEach(allOptionsAsStringsBuilder::add);
    }
    return allOptionsAsStringsBuilder.build();
  }

  /**
   * Filters the options from the selected config to only those that are part of {@link
   * BuildOptions}, excluding {@link TestConfiguration.TestOptions}.
   *
   * <p>Only the options that are part of {@link BuildOptions} are allowed to be set in the project
   * file.
   */
  private static ImmutableSet<String> filterOptions(
      Collection<String> flagsFromSelectedConfig, ImmutableList<String> buildOptionsAsStrings) {
    ImmutableSet.Builder<String> filteredFlags = ImmutableSet.builder();
    for (String flagSetting : flagsFromSelectedConfig) {
      // Remove options that aren't part of BuildOptions from the selected config.
      if (buildOptionsAsStrings.contains(
          Iterables.get(Splitter.on("=").split(flagSetting), 0)
              .replaceFirst("--", "")
              .replace("'", ""))) {
        filteredFlags.add(flagSetting);
      } else if (STARLARK_SKIPPED_PREFIXES.stream().anyMatch(flagSetting::startsWith)) {
        // Because the BuildOptions might not already include Starlark flags that are set in the
        // flagset, explicitly add them to the set of options to return.
        filteredFlags.add(flagSetting);
      }
    }
    return filteredFlags.build();
  }

  /**
   * In-place expands {@code --config=foo} entries in {@code inputFlags}.
   *
   * <p>Doesn't parse flags or check where they're defined. It's up to callers to determine if flags
   * are, for example, part of {@link BuildOptions}, if they parse correctly, or if they even exist.
   *
   * @throws FlagSetFunctionException if {@code --config=foo} doesn't evaluate, it defines
   *     non-{@link BuildOptions} flags, isn't defined in a global rc file, or is defined multiple
   *     times.
   */
  private static ImmutableList<String> expandConfigFlags(
      String sclConfigName,
      Collection<String> inputFlags,
      ConfigFlagDefinitions configFlagDefinitions)
      throws FlagSetFunctionException {
    // First look for dupes.
    HashSet<String> dupeChecker = new HashSet<>();
    for (var flag : inputFlags) {
      if (flag.startsWith("--config=") && !dupeChecker.add(flag)) {
        throw new FlagSetFunctionException(
            new UnsupportedConfigException(
                String.format(
                    "--scl_config=%s: %s appears multiple times. Please ensure it appears at most"
                        + " once.",
                    sclConfigName, flag)),
            Transience.PERSISTENT);
      }
    }

    // Now rebuild the input list while in-place expanding each "--config=foo" entry.
    var ans = ImmutableList.<String>builder();
    for (var flag : inputFlags) {
      if (!flag.startsWith("--config=")) {
        ans.add(flag);
        continue;
      }
      // TODO: b/388289978 - fail when a --config sets non-BuildOptions flags.
      ConfigFlagDefinitions.ConfigValue expandedFlags;
      try {
        expandedFlags =
            ConfigFlagDefinitions.get(flag.substring(flag.indexOf("=") + 1), configFlagDefinitions);
      } catch (OptionsParsingException e) {
        throw new FlagSetFunctionException(
            new UnsupportedConfigException(
                String.format("--scl_config=%s: %s", sclConfigName, e.getMessage())),
            Transience.PERSISTENT);
      }
      for (String rcSource : expandedFlags.rcSources()) {
        if (!GlobalRcUtils.isGlobalRcFile(rcSource)) {
          throw new FlagSetFunctionException(
              new UnsupportedConfigException(
                  String.format(
                      "--scl_config=%s: can't set %s because its definition depends on %s which"
                          + " isn't a global rc file.",
                      sclConfigName, flag, rcSource)),
              Transience.PERSISTENT);
        }
      }
      ans.addAll(expandedFlags.flags());
    }
    return ans.build();
  }

  /**
   * Enforces one of the following `enforcement_policies`:
   *
   * <p>WARN - warn if the user set any output-affecting options that are not present in the
   * selected config in a bazelrc or on the command line.
   *
   * <p>COMPATIBLE - fail if the user set any options that are present in the selected config to a
   * different value than the one in the config. Also warn for other output-affecting options
   *
   * <p>STRICT - fail if the user set any output-affecting options that are not present in the
   * selected config.
   *
   * <p>Conflicting output-affecting options may be set in global RC files (including the {@code
   * InvocationPolicy}). Flags that do not affect outputs are always allowed.
   *
   * @param userOptions the user options set in the command line or user bazelrc as a map from
   *     option.getCanonicalForm()to option.getExpandedFrom(), {"--define=foo=bar": "--config=foo"}.
   */
  private static void validateNoExtraFlagsSet(
      EnforcementPolicy enforcementPolicy,
      Collection<String> alwaysAllowedConfigs,
      ImmutableList<String> buildOptionsAsStrings,
      ImmutableMap<String, String> userOptions,
      ImmutableSet<String> flagsFromSelectedConfig,
      ImmutableSet.Builder<Event> persistentMessages,
      Label projectFile)
      throws FlagSetFunctionException {
    ImmutableSet<String> overlap =
        userOptions.keySet().stream()
            // Remove options that aren't part of BuildOptions. This section can be removed once
            // we only include BuildOptions in the passed userOptions.
            .filter(
                option ->
                    buildOptionsAsStrings.contains(
                        Iterables.get(Splitter.on("=").split(option), 0)
                            .replaceFirst("--", "")
                            .replace("'", "")))
            .filter(option -> !option.startsWith("--scl_config"))
            .filter(option -> !flagsFromSelectedConfig.contains(option))
            // Remove options that are expanded from always-allowed configs either defined in the
            // project file...
            .filter(option -> !alwaysAllowedConfigs.contains(userOptions.get(option)))
            // ... or globally
            .filter(
                option -> !GlobalRcUtils.ALLOWED_GLOBAL_CONFIGS.contains(userOptions.get(option)))
            .map(
                option ->
                    userOptions.get(option).isEmpty()
                        ? "'" + option + "'"
                        : "'" + userOptions.get(option) + "'")
            .collect(toImmutableSet());
    if (overlap.isEmpty()) {
      return;
    }
    switch (enforcementPolicy) {
      case WARN:
        break;
      case COMPATIBLE:
        ImmutableSet<String> optionNamesFromSelectedConfig =
            flagsFromSelectedConfig.stream()
                .map(flag -> Iterables.get(Splitter.on("=").split(flag), 0).replace("'", ""))
                .collect(toImmutableSet());
        ImmutableSet<String> conflictingOptions =
            overlap.stream()
                .filter(
                    option ->
                        optionNamesFromSelectedConfig.contains(
                            Iterables.get(Splitter.on("=").split(option), 0).replace("'", "")))
                .collect(toImmutableSet());
        if (!conflictingOptions.isEmpty()) {
          throw new FlagSetFunctionException(
              new UnsupportedConfigException(
                  String.format(
                      "This build uses a project file (%s) that does not allow conflicting flags"
                          + " in the command line or user bazelrc. Found %s. Please remove these"
                          + " flags or disable project file resolution via"
                          + " --noenforce_project_configs.",
                      projectFile, conflictingOptions)),
              Transience.PERSISTENT);
        }
        break;
      case STRICT:
        throw new FlagSetFunctionException(
            new UnsupportedConfigException(
                String.format(
                    "This build uses a project file (%s) that does not allow output-affecting"
                        + " flags in the command line or user bazelrc. Found %s. Please remove"
                        + " these flags or disable project file resolution via"
                        + " --noenforce_project_configs.",
                    projectFile, overlap)),
            Transience.PERSISTENT);
    }
    // This appears in the WARN case, or for a COMPATIBLE project file that doesn't have
    // conflicting flags. We never hit this in the STRICT case, since we've already thrown.
    persistentMessages.add(
        Event.warn(
            String.format(
                "This build uses a project file (%s), but also sets output-affecting"
                    + " flags in the command line or user bazelrc: %s. Please consider"
                    + " removing these flags.",
                projectFile, overlap)));
  }

  /** Returns a user-friendly description of project-supported configurations. */
  private static String supportedConfigsDesc(
      Label projectFile, Dict<String, Collection<String>> configs) {
    String ans = "\nThis project supports:\n";
    for (var configInfo : configs.entrySet()) {
      ans += String.format("  --scl_config=%s: %s\n", configInfo.getKey(), configInfo.getValue());
    }
    ans += String.format("\nThis policy is defined in %s.\n", projectFile.toPathFragment());
    return ans;
  }


  private static final class FlagSetFunctionException extends SkyFunctionException {
    FlagSetFunctionException(Exception cause, Transience transience) {
      super(cause, transience);
    }
  }

  private static final class UnsupportedConfigException extends Exception {
    UnsupportedConfigException(String msg) {
      super(msg);
    }
  }

  private static final class InvalidProjectFileException extends Exception {
    InvalidProjectFileException(String msg) {
      super(msg);
    }
  }
}
