// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.config;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.analysis.test.TestTrimmingLogic;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.PathFragment.InvalidBaseNameException;
import com.google.devtools.common.options.OptionDefinition;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Machinery for computing the output path mnemonic given the target options, constructed fragments
 * for those target options, and current baseline options (which will be null in legacy mode or odd
 * parts of the test infra).
 */
@VisibleForTesting
public final class OutputPathMnemonicComputer {
  // The length of the hash of the config tacked onto the end of the output path.
  // Limited for ergonomics and MAX_PATH reasons.
  private static final int HASH_LENGTH = 12;

  private OutputPathMnemonicComputer() {}

  /** Indicates a failure to construct the mnemonic for an output directory. */
  public static class InvalidMnemonicException extends InvalidConfigurationException {
    InvalidMnemonicException(String message, Exception e) {
      super(
          message + " is invalid as part of a path: " + e.getMessage(),
          Code.INVALID_OUTPUT_DIRECTORY_MNEMONIC);
    }
  }

  /**
   * Create a fresh context to pass to {@link Fragment.processForOutputPathMnemonic}
   *
   * <p>Needs to be fresh since want new state tracking the current mnemonic and explicit in output
   * path option exclusions.
   *
   * <p>Note that this class roughly has two sets of methods: 1. The overrides of
   * Fragment.OutputDirectoriesContext 2. The new methods used by OutputPathMnemonicComputer to make
   * its own additions to the mnemonic and extraction of the information.
   */
  private static final class MnemonicContext implements Fragment.OutputDirectoriesContext {
    @Nullable private final BuildOptions baselineOptions;
    private final StringBuilder mnemonicBuilder;
    private final ImmutableSet.Builder<String> explicitInOutputPathBuilder;

    private MnemonicContext(@Nullable BuildOptions baselineOptions) {
      this.baselineOptions = baselineOptions;
      this.mnemonicBuilder = new StringBuilder();
      this.explicitInOutputPathBuilder = ImmutableSet.builder();
    }

    // Implementations for FragmentOptions to use:
    /* If available, get the baseline version of some FragmentOptions */
    @Nullable
    @Override
    public <T extends FragmentOptions> T getBaseline(Class<T> optionsClass) {
      if (baselineOptions == null) {
        return null;
      }
      return baselineOptions.get(optionsClass);
    }

    /* Adds given String to the explicit part of the output path. */
    @Override
    @CanIgnoreReturnValue
    public Fragment.OutputDirectoriesContext addToMnemonic(@Nullable String value)
        throws Fragment.OutputDirectoriesContext.AddToMnemonicException {
      if (Strings.isNullOrEmpty(value)) {
        return this;
      }
      try {
        // Allowing for path separators (e.g. /) would be a disaster.
        PathFragment.checkSeparators(value);
        // Want dashes in-between additions.
        // (Note that length of a StringBuilder is very cheap to check so this performs fine.)
        if (mnemonicBuilder.length() > 0) {
          mnemonicBuilder.append("-");
        }
        mnemonicBuilder.append(value);
      } catch (InvalidBaseNameException e) {
        throw new AddToMnemonicException(value, e);
      }
      return this;
    }

    /* See docs at {@link Fragment.OutputDirectoriesContext.markAsExplicitInOutputPathFor}*/
    @Override
    @CanIgnoreReturnValue
    public Fragment.OutputDirectoriesContext markAsExplicitInOutputPathFor(String optionName) {
      explicitInOutputPathBuilder.add(optionName);
      return this;
    }

    // Interface and Implementations for BuildConfigurationFunction to use:
    public void consume(Fragment fragment) throws InvalidMnemonicException {
      try {
        fragment.processForOutputPathMnemonic(this);
      } catch (AddToMnemonicException e) {
        throw new InvalidMnemonicException(
            String.format(
                "Output directory name '%s' specified by %s",
                e.badValue, fragment.getClass().getSimpleName()),
            e.tunneledException);
      }
    }

    @CanIgnoreReturnValue
    public Fragment.OutputDirectoriesContext checkedAddToMnemonic(
        @Nullable String value, String valueCtx) throws InvalidMnemonicException {
      try {
        addToMnemonic(value);
      } catch (AddToMnemonicException e) {
        throw new InvalidMnemonicException(
            String.format("%s '%s'", valueCtx, e.badValue), e.tunneledException);
      }
      return this;
    }

    public String getMnemonic() {
      return mnemonicBuilder.toString();
    }

    public ImmutableSet<String> getExplicitInOutputPathOptions() {
      return explicitInOutputPathBuilder.build();
    }
  }

  /**
   * Compute and return the output path mnemonic.
   *
   * <p>The general form is [cpu]-[compilation_mode]-[platform_suffix?]-...-[-ST-hash?] where ... is
   * any additions requested by the {@link Fragment} via {@link
   * Fragment.OutputDirectoriesContext.addToMnemonic} during calls to {@link
   * Fragment.processForOutputPathMnemonic}.
   *
   * <p>platform_suffix is omitted if empty.
   *
   * <p>The exact ST-hash used depends on if baselineOptions is available:
   *
   * <p>If not, assume in legacy mode and use `affected by starlark transition` to see what options
   * need to be hashed.
   *
   * <p>If available, the hash includes all options that are different between buildOptions and
   * baselineOptions but were also not excluded from the output path by a call to {@link
   * Fragment.OutputDirectoriesContext.markAsExplicitInOutputPathFor}
   */
  static final String computeMnemonic(
      BuildOptions buildOptions,
      @Nullable BuildOptions baselineOptions,
      ImmutableSortedMap<Class<? extends Fragment>, Fragment> fragments)
      throws InvalidMnemonicException {

    CoreOptions coreOptions = buildOptions.get(CoreOptions.class);

    if (buildOptions.hasNoConfig()) {
      // Historically, the noconfig output path mnemonic had the compilation mode.
      return coreOptions.compilationMode + "-noconfig"; // See NoConfigTransition.
    }

    PlatformOptions platformOptions = buildOptions.get(PlatformOptions.class);

    MnemonicContext ctx = new MnemonicContext(baselineOptions);

    handlePlatformCpuDescriptor(ctx, coreOptions, platformOptions);

    ctx.checkedAddToMnemonic(coreOptions.compilationMode.toString(), "Compilation mode");
    ctx.markAsExplicitInOutputPathFor("compilation_mode");

    if (!Strings.isNullOrEmpty(coreOptions.platformSuffix)) {
      ctx.checkedAddToMnemonic(coreOptions.platformSuffix, "Platform suffix");
    }
    ctx.markAsExplicitInOutputPathFor("platform_suffix");

    for (Map.Entry<Class<? extends Fragment>, Fragment> entry : fragments.entrySet()) {
      ctx.consume(entry.getValue());
    }

    ImmutableSet<String> explicitInOutputPathOptions = ctx.getExplicitInOutputPathOptions();

    // Sanity check that every listed option in explicitInOutputPathOptions actually exists.
    // TODO(blaze-configurability-team): Should technically be unnecessary to do this every time as
    // all the calls to markAsExplicitInOutputPathFor should be constant for a given release.
    // Instead, could do this when a specific flag is supplied and just check in test code.
    // Alternatively, just do a better job of caching the call to OptionInfo.buildMapFrom as only
    // that call is the expensive part.
    ImmutableMap<String, OptionInfo> optionInfoMap = OptionInfo.buildMapFrom(buildOptions);
    ImmutableSet<String> missingOptions =
        explicitInOutputPathOptions.stream()
            .filter(optionName -> !optionInfoMap.containsKey(optionName))
            .collect(toImmutableSet());
    if (!missingOptions.isEmpty()) {
      throw new IllegalStateException(
          "Internal error: Options registered for special output handling that do not exist: "
              + missingOptions);
    }

    if (baselineOptions == null) {
      ctx.checkedAddToMnemonic(
          computeNameFragmentWithAffectedByStarlarkTransition(buildOptions),
          "Transition directory name fragment");
    } else {
      ctx.checkedAddToMnemonic(
          computeNameFragmentWithDiff(buildOptions, baselineOptions, explicitInOutputPathOptions),
          "Transition directory name fragment");
    }
    return ctx.getMnemonic();
  }

  private static void handlePlatformCpuDescriptor(
      MnemonicContext ctx, CoreOptions coreOptions, @Nullable PlatformOptions platformOptions)
      throws InvalidMnemonicException {
    if (!coreOptions.platformInOutputDir || platformOptions == null) {
      ctx.checkedAddToMnemonic(coreOptions.cpu, "CPU/Platform descriptor");
      ctx.markAsExplicitInOutputPathFor("cpu");
      return;
    }

    if (platformOptions.platforms != null && platformOptions.platforms.size() > 1) {
      ctx.checkedAddToMnemonic("multi-platform", "CPU/Platform descriptor");
      // Intentionally not marking anything as explicit in output path so ST-hash used if needed.
      return;
    }

    ctx.checkedAddToMnemonic(
        computePlatformName(platformOptions.computeTargetPlatform(), coreOptions),
        "CPU/Platform descriptor");
    ctx.markAsExplicitInOutputPathFor("platforms");
  }

  private static String computePlatformName(Label platform, CoreOptions options) {
    // As highest priority, use the last entry that matches in name override option.
    Optional<String> overridePlatformName =
        Streams.findLast(
            options.overrideNamePlatformInOutputDirEntries.stream()
                .filter(e -> e.getKey().equals(platform))
                .map(Map.Entry::getValue));
    if (overridePlatformName.isPresent()) {
      return overridePlatformName.get();
    }

    // Handle legacy heuristic if enabled.
    // Note that it is known this heuristic is not necessarily complete.
    if (options.usePlatformsInOutputDirLegacyHeuristic) {
      // Only use non-default platforms.

      if (!PlatformOptions.platformIsDefault(platform)) {
        return platform.getName();
      }
      // Fall back to using the CPU.
      return options.cpu;
    }
    // As a last resort use hashCode of the unambiguous form of the label.
    return String.format("platform-%X", platform.getUnambiguousCanonicalForm().hashCode());
  }

  /**
   * Compute the hash for the new BuildOptions based on the names and values of all options (both
   * native and Starlark) that are different from some supplied baseline configuration.
   */
  @VisibleForTesting
  public static String computeNameFragmentWithDiff(
      BuildOptions toOptions,
      BuildOptions baselineOptions,
      ImmutableSet<String> explicitInOutputPathOptions) {
    // Quick short-circuit for trivial case.
    if (toOptions.equals(baselineOptions)) {
      return "";
    }

    if (!toOptions.contains(TestConfiguration.TestOptions.class)) {
      baselineOptions = TestTrimmingLogic.trim(baselineOptions);
    }

    // TODO(blaze-configurability-team): As a mild performance update, getFirst already includes
    //   details of the corresponding option. Could incorporate this instead of hashChosenOptions
    //   regenerating the OptionDefinitions and values.
    OptionsDiff diff = OptionsDiff.diff(toOptions, baselineOptions);
    // Note: getFirst only excludes options trimmed between baselineOptions to toOptions and this is
    //   considered OK as a given Rule should not be being built with options of different
    //   trimmings. See longform note in {@link ConfiguredTargetKey} for details.
    ImmutableSet<String> chosenNativeOptions =
        diff.getFirst().keySet().stream()
            .map(OptionDefinition::getOptionName)
            .filter(optionName -> !explicitInOutputPathOptions.contains(optionName))
            .collect(toImmutableSet());
    // Note: getChangedStarlarkOptions includes all changed options, added options and removed
    //   options between baselineOptions and toOptions. This is necessary since there is no current
    //   notion of trimming a Starlark option: 'null' or non-existent justs means set to default.
    ImmutableSet<String> chosenStarlarkOptions =
        diff.getChangedStarlarkOptions().stream().map(Label::toString).collect(toImmutableSet());
    return hashChosenOptions(toOptions, chosenNativeOptions, chosenStarlarkOptions);
  }

  /**
   * Compute the output directory name fragment corresponding to the new BuildOptions based on the
   * names and values of all options (both native and Starlark) previously transitioned anywhere in
   * the build by Starlark transitions. Options only set on command line are not affecting the
   * computation.
   *
   * @param toOptions the {@link BuildOptions} to use to calculate which we need to compute {@code
   *     transitionDirectoryNameFragment}.
   */
  private static String computeNameFragmentWithAffectedByStarlarkTransition(
      BuildOptions toOptions) {
    CoreOptions buildConfigOptions = toOptions.get(CoreOptions.class);
    if (buildConfigOptions.affectedByStarlarkTransition.isEmpty()) {
      return "";
    }

    ImmutableList.Builder<String> affectedNativeOptions = ImmutableList.builder();
    ImmutableList.Builder<String> affectedStarlarkOptions = ImmutableList.builder();

    // Note that explicitInOutputPathOptions is not sent to this function.
    // It is possible for two BuildOptions to differ only in `affected by Starlark transition`
    //   where the only different is one includes a marked option and the other doesn't.
    // Thus, must include all options so those cases get a different output path.
    // This legacy is no longer the default and thus entire code path is slated for removal.
    for (String optionName : buildConfigOptions.affectedByStarlarkTransition) {
      if (optionName.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        String nativeOptionName = optionName.substring(COMMAND_LINE_OPTION_PREFIX.length());
        affectedNativeOptions.add(nativeOptionName);
      } else {
        affectedStarlarkOptions.add(optionName);
      }
    }

    return hashChosenOptions(
        toOptions, affectedNativeOptions.build(), affectedStarlarkOptions.build());
  }

  /**
   * Compute a hash of the given BuildOptions by hashing only the options referenced in both
   * chosenNative and chosenStarlark. The order of the chosen order does not matter (as this
   * function will effectively sort them into a canonical order) and the pre-hash for each option
   * will be of the form (//command_line_option:[native option]|[Starlark option label])=[value].
   *
   * <p>If a supplied native option does not exist, it is skipped (as it is presumed non-existence
   * is due to trimming).
   *
   * <p>If a supplied Starlark option does exist, the pre-hash will be [Starlark option label]@null
   * (as it is presumed non-existence is due to being set to default value).
   */
  private static String hashChosenOptions(
      BuildOptions toOptions, Iterable<String> chosenNative, Iterable<String> chosenStarlark) {
    // TODO(blaze-configurability-team): A mild performance optimization would have this be global.
    ImmutableMap<String, OptionInfo> optionInfoMap = OptionInfo.buildMapFrom(toOptions);

    // Note that the TreeMap guarantees a stable ordering of keys and thus
    // it is okay if chosenNative or chosenStarlark do not have a stable iteration order
    TreeMap<String, Object> toHash = new TreeMap<>();
    for (String nativeOptionName : chosenNative) {
      OptionInfo optionInfo = optionInfoMap.get(nativeOptionName);
      if (optionInfo == null) {
        // This can occur if toOptions has been trimmed but the supplied chosen native options
        // includes that trimmed options.
        // (e.g. legacy naming mode, using --trim_test_configuration and --test_arg transition).
        continue;
      }
      FragmentOptions fragmentOptions =
          toOptions.get(optionInfoMap.get(nativeOptionName).getOptionClass());
      Object value = optionInfo.getDefinition().getValue(fragmentOptions);
      // TODO(blaze-configurability-team): The commandline option is legacy and can be removed
      //   after fixing up all the associated tests.
      toHash.put("//command_line_option:" + nativeOptionName, value);
    }
    for (String starlarkOptionName : chosenStarlark) {
      Object value =
          toOptions.getStarlarkOptions().get(Label.parseCanonicalUnchecked(starlarkOptionName));
      toHash.put(starlarkOptionName, value);
    }

    if (toHash.isEmpty()) {
      return "";
    } else {
      ImmutableList.Builder<String> hashStrs = ImmutableList.builderWithExpectedSize(toHash.size());
      for (Map.Entry<String, Object> singleOptionAndValue : toHash.entrySet()) {
        Object value = singleOptionAndValue.getValue();
        if (value != null) {
          hashStrs.add(singleOptionAndValue.getKey() + "=" + value);
        } else {
          // Avoid using =null to different from value being the non-null String "null"
          hashStrs.add(singleOptionAndValue.getKey() + "@null");
        }
      }
      return transitionDirectoryNameFragment(hashStrs.build());
    }
  }

  @VisibleForTesting
  public static String transitionDirectoryNameFragment(Iterable<String> opts) {
    Fingerprint fp = new Fingerprint();
    for (String opt : opts) {
      fp.addString(opt);
    }
    // Shorten the hash to HASH_LENGTH characters. This should provide sufficient collision
    // avoidance (that is, we don't expect anyone to experience a collision ever).
    // Shortening the hash is important for Windows paths that tend to be short.
    String suffix = fp.hexDigestAndReset().substring(0, HASH_LENGTH);
    return "ST-" + suffix;
  }
}
