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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;
import static com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.PATCH_TRANSITION_KEY;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.OptionInfo;
import com.google.devtools.build.lib.analysis.config.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.ValidationException;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;

/**
 * Utility class for common work done across {@link StarlarkAttributeTransitionProvider} and {@link
 * StarlarkRuleTransitionProvider}.
 */
public final class FunctionTransitionUtil {

  private static final Predicate<String> IS_NATIVE_OPTION =
      setting -> setting.startsWith(COMMAND_LINE_OPTION_PREFIX);
  private static final Predicate<String> IS_STARLARK_OPTION = not(IS_NATIVE_OPTION);

  /**
   * Figure out what build settings the given transition changes and apply those changes to the
   * incoming {@link BuildOptions}. For native options, this involves a preprocess step of
   * converting options to their "command line form".
   *
   * <p>Also perform validation on the inputs and outputs:
   *
   * <ol>
   *   <li>Ensure that all native input options exist
   *   <li>Ensure that all native output options exist
   *   <li>Ensure that there are no attempts to update the {@code --define} option.
   *   <li>Ensure that no {@link OptionMetadataTag#IMMUTABLE immutable} native options are updated.
   *   <li>Ensure that transitions output all of the declared options.
   * </ol>
   *
   * @param fromOptions the pre-transition build options
   * @param starlarkTransition the transition to apply
   * @param attrObject the attributes of the rule to which this transition is attached
   * @return the post-transition build options, or null if errors were reported to handler.
   */
  @Nullable
  static ImmutableMap<String, BuildOptions> applyAndValidate(
      BuildOptions fromOptions,
      StarlarkDefinedConfigTransition starlarkTransition,
      boolean allowImmutableFlagChanges,
      StructImpl attrObject,
      EventHandler handler)
      throws InterruptedException {
    try {
      // TODO(waltl): Consider building this once and using it across different split transitions,
      // or reusing BuildOptionDetails.
      ImmutableMap<String, OptionInfo> optionInfoMap = OptionInfo.buildMapFrom(fromOptions);

      validateInputOptions(starlarkTransition.getInputs(), optionInfoMap);
      validateOutputOptions(
          starlarkTransition.getOutputs(), allowImmutableFlagChanges, optionInfoMap);

      ImmutableMap<String, Object> settings =
          buildSettings(fromOptions, optionInfoMap, starlarkTransition);

      ImmutableMap.Builder<String, BuildOptions> splitBuildOptions = ImmutableMap.builder();

      // For anything except the exec transition this is just fromOptions. See maybeGetExecDefaults
      // for why the exec transition is different.
      BuildOptions baselineToOptions = maybeGetExecDefaults(fromOptions, starlarkTransition);

      ImmutableMap<String, Map<String, Object>> transitions =
          starlarkTransition.evaluate(settings, attrObject, optionInfoMap, handler);
      if (transitions == null) {
        return null; // errors reported to handler
      } else if (transitions.isEmpty()) {
        // The transition produced a no-op.
        return ImmutableMap.of(PATCH_TRANSITION_KEY, baselineToOptions);
      }

      for (Map.Entry<String, Map<String, Object>> entry : transitions.entrySet()) {
        Map<String, Object> newValues =
            handleImplicitPlatformChange(baselineToOptions, entry.getValue());
        BuildOptions transitionedOptions =
            applyTransition(baselineToOptions, newValues, optionInfoMap, starlarkTransition);
        splitBuildOptions.put(entry.getKey(), transitionedOptions);
      }
      return splitBuildOptions.buildOrThrow();

    } catch (ValidationException ex) {
      handler.handle(Event.error(starlarkTransition.getLocation(), ex.getMessage()));
      return null;
    }
  }

  /**
   * For all transitions except the exec transition, returns {@code fromOptions}.
   *
   * <p>The exec transition is special: any options not explicitly set by the transition take their
   * defaults, not {@code fromOptions}'s values. This method adjusts the baseline options
   * accordingly.
   *
   * <p>The exec transition's full sequence is:
   *
   * <ol>
   *   <li>The transition's Starlark function runs over {@code fromOptions}: {@code
   *       {"//command_line_option:foo": settings["//command_line_option:foo"}} sets {@code foo} to
   *       {@code fromOptions}'s value (i.e. propagates from the source config)
   *   <li>This method constructs a {@link BuildOptions} default value (which doesn't inherit from
   *       the source config)
   *   <li>{@link #applyTransition} creates final options: use whatever options the Starlark logic
   *       set (which may propagate from the source config). For all other options, use default
   *       values
   *       <p>See {@link com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory}.
   */
  private static BuildOptions maybeGetExecDefaults(
      BuildOptions fromOptions, StarlarkDefinedConfigTransition starlarkTransition) {
    if (starlarkTransition == null || !starlarkTransition.isExecTransition()) {
      // Not an exec transition: the baseline options are just the input options.
      return fromOptions;
    }
    BuildOptions.Builder defaultBuilder = BuildOptions.builder();
    // Get the defaults:
    fromOptions.getNativeOptions().forEach(o -> defaultBuilder.addFragmentOptions(o.getDefault()));
    // Propagate Starlark options from the source config if allowed.
    if (fromOptions.get(CoreOptions.class).excludeStarlarkFlagsFromExecConfig) {
      ImmutableMap<Label, Object> starlarkOptions =
          fromOptions.getStarlarkOptions().entrySet().stream()
              .filter(
                  (starlarkFlag) -> propagateStarlarkFlagToExec(starlarkFlag.getKey(), fromOptions))
              .collect(toImmutableMap(Map.Entry::getKey, (e) -> e.getValue()));
      defaultBuilder.addStarlarkOptions(starlarkOptions);
    } else {
      defaultBuilder.addStarlarkOptions(fromOptions.getStarlarkOptions());
    }
    // Hard-code TestConfiguration for now, which clones the source options.
    // TODO(b/295936652): handle this directly in Starlark. This has two complications:
    //  1: --trim_test_configuration means the flags may not exist. Starlark logic needs to handle
    //     that possibility.
    //  2: --runs_per_test has a non-Starlark readable type.
    var testOptions = fromOptions.get(TestOptions.class);
    if (testOptions != null) {
      defaultBuilder.removeFragmentOptions(TestOptions.class);
      defaultBuilder.addFragmentOptions(testOptions);
    }
    BuildOptions ans = defaultBuilder.build();
    if (fromOptions.get(CoreOptions.class).excludeDefinesFromExecConfig) {
      ans.get(CoreOptions.class).commandLineBuildVariables =
          fromOptions.get(CoreOptions.class).commandLineBuildVariables.stream()
              .filter(
                  (define) ->
                      fromOptions
                          .get(CoreOptions.class)
                          .customFlagsToPropagate
                          .contains(define.getKey()))
              .collect(toImmutableList());
    } else {
      ans.get(CoreOptions.class).commandLineBuildVariables =
          fromOptions.get(CoreOptions.class).commandLineBuildVariables;
    }
    return ans;
  }

  /**
   * Returns true if the given Starlark flag should propagate from the target to exec configuration.
   */
  private static boolean propagateStarlarkFlagToExec(
      Label starlarkFlag, BuildOptions buildOptions) {
    return buildOptions.get(CoreOptions.class).customFlagsToPropagate.stream()
        .anyMatch(
            flagToPropagate ->
                (flagToPropagate.equals(starlarkFlag.toString())
                    || (flagToPropagate.endsWith("/...")
                        && starlarkFlag
                            .toString()
                            .startsWith(
                                flagToPropagate.substring(
                                    0, flagToPropagate.lastIndexOf("/..."))))));
  }

  /**
   * If the transition changes --cpu but not --platforms, clear out --platforms.
   *
   * <p>Purpose:
   *
   * <ol>
   *   <li>A platform mapping sets --cpu=foo when --platforms=foo.
   *   <li>A transition sets --cpu=bar.
   *   <li>Because --platforms=foo, the platform mapping kicks in to set --cpu back to foo.
   *   <li>Result: the mapping accidentally overrides the transition
   * </ol>
   *
   * <p>Transitions can also explicitly set --platforms to be clear what platform they set.
   *
   * <p>Platform mappings: https://bazel.build/concepts/platforms-intro#platform-mappings.
   */
  private static Map<String, Object> handleImplicitPlatformChange(
      BuildOptions options, Map<String, Object> rawTransitionOutput) {
    Object newCpu = rawTransitionOutput.get(COMMAND_LINE_OPTION_PREFIX + "cpu");
    if (newCpu == null || newCpu.equals(options.get(CoreOptions.class).cpu)) {
      // No effective change to --cpu, so no need to prevent the platform mapping from resetting it.
      return rawTransitionOutput;
    }
    if (rawTransitionOutput.containsKey(COMMAND_LINE_OPTION_PREFIX + "platforms")) {
      // Explicitly setting --platforms overrides the implicit clearing.
      return rawTransitionOutput;
    }
    return ImmutableMap.<String, Object>builder()
        .putAll(rawTransitionOutput)
        .put(COMMAND_LINE_OPTION_PREFIX + "platforms", ImmutableList.<Label>of())
        .buildOrThrow();
  }

  private static boolean isNativeOptionValid(
      ImmutableMap<String, OptionInfo> optionInfoMap, String flag) {
    String optionName = flag.substring(COMMAND_LINE_OPTION_PREFIX.length());

    // Make sure the option exists.
    return optionInfoMap.containsKey(optionName);
  }

  /**
   * Check if a native option is immutable.
   *
   * @return whether or not the option is immutable
   * @throws VerifyException if the option does not exist
   */
  private static boolean isNativeOptionImmutable(
      ImmutableMap<String, OptionInfo> optionInfoMap, String flag) {
    String optionName = flag.substring(COMMAND_LINE_OPTION_PREFIX.length());
    OptionInfo optionInfo = optionInfoMap.get(optionName);
    if (optionInfo == null) {
      throw new VerifyException(
          "Cannot check if option " + flag + " is immutable: it does not exist");
    }
    return optionInfo.hasOptionMetadataTag(OptionMetadataTag.IMMUTABLE);
  }

  private static void validateInputOptions(
      ImmutableList<String> options, ImmutableMap<String, OptionInfo> optionInfoMap)
      throws ValidationException {
    ImmutableList<String> invalidNativeOptions =
        options.stream()
            .filter(IS_NATIVE_OPTION)
            .filter(optionName -> !isNativeOptionValid(optionInfoMap, optionName))
            .collect(toImmutableList());
    if (!invalidNativeOptions.isEmpty()) {
      throw ValidationException.format(
          "transition inputs [%s] do not correspond to valid settings",
          Joiner.on(", ").join(invalidNativeOptions));
    }
  }

  private static void validateOutputOptions(
      ImmutableList<String> options,
      boolean allowImmutableFlagChanges,
      ImmutableMap<String, OptionInfo> optionInfoMap)
      throws ValidationException {
    if (options.contains("//command_line_option:define")) {
      throw new ValidationException(
          "Starlark transition on --define not supported - try using build settings"
              + " (https://bazel.build/rules/config#user-defined-build-settings).");
    }

    // TODO: blaze-configurability - Move the checks for incompatible and experimental flags to here
    // (currently in ConfigGlobalLibrary.validateBuildSettingKeys).

    ImmutableList<String> invalidNativeOptions =
        options.stream()
            .filter(IS_NATIVE_OPTION)
            .filter(optionName -> !isNativeOptionValid(optionInfoMap, optionName))
            .collect(toImmutableList());
    if (!invalidNativeOptions.isEmpty()) {
      throw ValidationException.format(
          "transition outputs [%s] do not correspond to valid settings",
          Joiner.on(", ").join(invalidNativeOptions));
    }

    if (!allowImmutableFlagChanges) {
      ImmutableList<String> immutableNativeOptions =
          options.stream()
              .filter(IS_NATIVE_OPTION)
              .filter(optionName -> isNativeOptionImmutable(optionInfoMap, optionName))
              .collect(toImmutableList());
      if (!immutableNativeOptions.isEmpty()) {
        throw ValidationException.format(
            "transition outputs [%s] cannot be changed: they are immutable",
            Joiner.on(", ").join(immutableNativeOptions));
      }
    }
  }

  /**
   * Return an ImmutableMap containing only BuildOptions explicitly registered as transition inputs.
   *
   * <p>nulls are converted to Starlark.NONE but no other conversions are done.
   *
   * @throws IllegalArgumentException If the method is unable to look up the value in buildOptions
   *     corresponding to an entry in optionInfoMap
   * @throws RuntimeException If the field corresponding to an option value in buildOptions is
   *     inaccessible due to Java language access control, or if an option name is an invalid key to
   *     the Starlark dictionary
   * @throws ValidationException if any of the specified transition inputs do not correspond to a
   *     valid build setting
   */
  private static ImmutableMap<String, Object> buildSettings(
      BuildOptions buildOptions,
      Map<String, OptionInfo> optionInfoMap,
      StarlarkDefinedConfigTransition starlarkTransition)
      throws ValidationException {
    ImmutableMap<String, String> inputsCanonicalizedToGiven =
        starlarkTransition.getInputsCanonicalizedToGiven();

    ImmutableMap.Builder<String, Object> optionsBuilder = ImmutableMap.builder();

    // Handle native options.
    starlarkTransition.getInputsCanonicalizedToGiven().keySet().stream()
        .filter(IS_NATIVE_OPTION)
        .forEach(
            setting -> {
              Optional<Object> result = findNativeOptionValue(buildOptions, optionInfoMap, setting);
              result.ifPresent(optionValue -> optionsBuilder.put(setting, optionValue));
            });

    // Handle starlark options.
    starlarkTransition.getInputsCanonicalizedToGiven().keySet().stream()
        .filter(IS_STARLARK_OPTION)
        .forEach(
            setting -> {
              Object optionValue = findStarlarkOptionValue(buildOptions, setting);
              // Convert the canonical form to the user requested form that they expect to see.
              String userRequestedLabelForm = inputsCanonicalizedToGiven.get(setting);
              optionsBuilder.put(userRequestedLabelForm, optionValue);
            });

    ImmutableMap<String, Object> result = optionsBuilder.buildOrThrow();
    SetView<String> remainingInputs =
        Sets.difference(ImmutableSet.copyOf(inputsCanonicalizedToGiven.values()), result.keySet());
    if (!remainingInputs.isEmpty()) {
      throw ValidationException.format(
          "transition inputs [%s] do not correspond to valid settings",
          Joiner.on(", ").join(remainingInputs));
    }

    return result;
  }

  private static Optional<Object> findNativeOptionValue(
      BuildOptions buildOptions, Map<String, OptionInfo> optionInfoMap, String setting) {
    setting = setting.substring(COMMAND_LINE_OPTION_PREFIX.length());
    if (!optionInfoMap.containsKey(setting)) {
      return Optional.empty();
    }
    OptionInfo optionInfo = optionInfoMap.get(setting);
    FragmentOptions options = buildOptions.get(optionInfo.getOptionClass());
    // Get the raw value to avoid the default handling for null values.
    Object optionValue = optionInfo.getDefinition().getRawValue(options);
    // convert nulls here b/c ImmutableMap bans null values
    return Optional.of(optionValue == null ? Starlark.NONE : optionValue);
  }

  private static Object findStarlarkOptionValue(BuildOptions buildOptions, String setting) {
    Label settingLabel = Label.parseCanonicalUnchecked(setting);
    return buildOptions.getStarlarkOptions().get(settingLabel);
  }

  /**
   * Apply the transition dictionary to the build option, using optionInfoMap to look up the option
   * info.
   *
   * @param fromOptions the pre-transition build options
   * @param newValues a map of option name: option value entries to override current option values
   *     in the buildOptions param
   * @param optionInfoMap a map of all native options (name -> OptionInfo) present in {@code
   *     toOptions}.
   * @param starlarkTransition transition object that is being applied. Used for error reporting and
   *     checking for analysis testing
   * @return the post-transition build options
   * @throws ValidationException If a requested option field is inaccessible
   */
  private static BuildOptions applyTransition(
      BuildOptions fromOptions,
      Map<String, Object> newValues,
      Map<String, OptionInfo> optionInfoMap,
      StarlarkDefinedConfigTransition starlarkTransition)
      throws ValidationException {
    // toOptions being null means the transition hasn't changed anything. We avoid preemptively
    // cloning it from fromOptions since options cloning is an expensive operation.
    BuildOptions toOptions = null;
    // The names of options (Starlark + native) that are different after this transition and must
    //   be added to "affected by Starlark transition"
    Set<String> convertedAffectedOptions = new HashSet<>();
    // Starlark options that are different after this transition. We collect all of them, then clone
    // the build options once with all cumulative changes. Native option changes, in contrast, are
    // set directly in the BuildOptions instance. The former approach is preferred since it makes
    // BuildOptions objects more immutable. Native options use the latter approach for legacy
    // reasons. While not preferred, direct mutation doesn't require expensive cloning.
    Map<Label, Object> changedStarlarkOptions = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : newValues.entrySet()) {
      String optionKey = entry.getKey();
      Object optionValue = entry.getValue();

      if (!optionKey.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        // The transition changes a Starlark option.
        Label optionLabel = Label.parseCanonicalUnchecked(optionKey);
        Object oldValue = fromOptions.getStarlarkOptions().get(optionLabel);
        if (oldValue instanceof Label) {
          // If this is a label-typed build setting, we need to convert the provided new value into
          // a Label object.
          if (optionValue instanceof String) {
            try {
              optionValue =
                  Label.parseWithPackageContext(
                      (String) optionValue, starlarkTransition.getPackageContext());
            } catch (LabelSyntaxException e) {
              throw ValidationException.format(
                  "Error parsing value for option '%s': %s", optionKey, e.getMessage());
            }
          } else if (!(optionValue instanceof Label)) {
            throw ValidationException.format(
                "Invalid value type for option '%s': want label, got %s",
                optionKey, Starlark.type(optionValue));
          }
        }
        if (!Objects.equals(oldValue, optionValue)) {
          changedStarlarkOptions.put(optionLabel, optionValue);
          convertedAffectedOptions.add(optionLabel.toString());
        }
      } else {
        // The transition changes a native option.
        String optionName = optionKey.substring(COMMAND_LINE_OPTION_PREFIX.length());
        OptionInfo optionInfo = optionInfoMap.get(optionName);

        // Convert NoneType to null.
        if (optionValue instanceof NoneType) {
          optionValue = null;
        } else if (optionValue instanceof StarlarkInt starlarkInt) {
          optionValue = starlarkInt.toIntUnchecked();
        } else if (optionValue instanceof List<?>) {
          // Converting back to the Java-native type makes it easier to check if a Starlark
          // transition set the same value a native transition would. This is important for
          // ExecutionTransitionFactory#ComparingTransition.
          // TODO(b/288258583): remove this case when ComparingTransition is no longer needed for
          // debugging. Production code just iterates over the lists, which both Starlark and
          // native List types implement.
          optionValue = ImmutableList.copyOf((List<?>) optionValue);
        } else if (optionValue instanceof Map<?, ?>) {
          // TODO(b/288258583): remove this case when ComparingTransition is no longer needed for
          // debugging. See above TODO.
          optionValue = ImmutableMap.copyOf(((Map<?, ?>) optionValue));
        }
        try {
          OptionDefinition def = optionInfo.getDefinition();
          // TODO(b/153867317): check for crashing options types in this logic.
          Object convertedValue;
          if (def.getType() == List.class && optionValue instanceof List<?> optionValueAsList) {
            // This is possible with Starlark code like "{ //command_line_option:foo: ["a", "b"] }".
            // In that case def.getType() == List.class while optionValue.type == StarlarkList.
            // Unfortunately we can't check the *element* types because OptionDefinition won't tell
            // us that about def (def.getConverter() returns LabelListConverter but nowhere does it
            // mention Label.class). Worse, def.getConverter().convert takes a String input. This
            // forces us to serialize optionValue back to a scalar string to convert. There's no
            // generically safe way to do this. We convert its elements with .toString() with a ","
            // separator, which happens to work for most implementations. But that's not universally
            // guaranteed.
            if (optionValueAsList.isEmpty()) {
              convertedValue = ImmutableList.of();
            } else if (!def.allowsMultiple()) {
              convertedValue =
                  def.getConverter()
                      .convert(
                          optionValueAsList.stream()
                              .map(
                                  element ->
                                      element instanceof Label label
                                          ? label.getUnambiguousCanonicalForm()
                                          : element.toString())
                              .collect(joining(",")),
                          starlarkTransition.getPackageContext());
            } else {
              var valueBuilder = ImmutableList.builder();
              // We can't use streams because def.getConverter().convert may throw an
              // OptionsParsingException.
              for (Object e : optionValueAsList) {
                Object converted =
                    def.getConverter()
                        .convert(e.toString(), starlarkTransition.getPackageContext());
                if (converted instanceof List<?> list) {
                  valueBuilder.addAll(list);
                } else {
                  valueBuilder.add(converted);
                }
              }
              convertedValue = valueBuilder.build();
            }
          } else if (def.getType() == List.class && optionValue == null) {
            throw ValidationException.format(
                "'None' value not allowed for List-type option '%s'. Please use '[]' instead if"
                    + " trying to set option to empty value.",
                optionName);
          } else if (optionValue == null || def.getType().isInstance(optionValue)) {
            convertedValue = optionValue;
          } else if (def.getType().equals(int.class) && optionValue instanceof Integer) {
            convertedValue = optionValue;
          } else if (def.getType().equals(boolean.class) && optionValue instanceof Boolean) {
            convertedValue = optionValue;
          } else if (optionValue instanceof String) {
            convertedValue =
                def.getConverter()
                    .convert((String) optionValue, starlarkTransition.getPackageContext());
          } else {
            throw ValidationException.format("Invalid value type for option '%s'", optionName);
          }

          Object oldValue = def.getRawValue(fromOptions.get(optionInfo.getOptionClass()));
          if (!Objects.equals(oldValue, convertedValue)) {
            if (toOptions == null) {
              toOptions = fromOptions.clone();
            }
            def.setValue(toOptions.get(optionInfo.getOptionClass()), convertedValue);

            convertedAffectedOptions.add(optionKey);
          }

        } catch (IllegalArgumentException e) {
          throw ValidationException.format(
              "IllegalArgumentError for option '%s': %s", optionName, e.getMessage());
        } catch (OptionsParsingException e) {
          throw ValidationException.format(
              "OptionsParsingError for option '%s': %s", optionName, e.getMessage());
        }
      }
    }

    if (toOptions == null && changedStarlarkOptions.isEmpty()) {
      return fromOptions;
    }
    // Note that rebuilding also calls FragmentOptions.getNormalized() to guarantee --define,
    // --features, and similar flags are consistently ordered.
    toOptions =
        BuildOptions.builder()
            .merge(toOptions == null ? fromOptions.clone() : toOptions)
            .addStarlarkOptions(changedStarlarkOptions)
            .build();
    if (starlarkTransition.isForAnalysisTesting()) {
      // We need to record every time we change a configuration option.
      // see {@link #updateOutputDirectoryNameFragment} for usage.
      convertedAffectedOptions.add("//command_line_option:evaluating for analysis test");
      toOptions.get(CoreOptions.class).evaluatingForAnalysisTest = true;
    }

    CoreOptions coreOptions = toOptions.get(CoreOptions.class);
    boolean isExecTransition = starlarkTransition != null && starlarkTransition.isExecTransition();

    if (!isExecTransition
        && coreOptions.outputDirectoryNamingScheme.equals(
            CoreOptions.OutputDirectoryNamingScheme.LEGACY)) {
      // The exec transition uses its own logic in ExecutionTransitionFactory.
      updateAffectedByStarlarkTransition(coreOptions, convertedAffectedOptions);
    }
    return toOptions;
  }

  /** Return different options in "affected by Starlark transition" form */
  // TODO(blaze-configurability-team):This only exists for pseudo-legacy fixups of native
  //   transitions. Remove once those fixups are removed in favor of the global fixup.
  public static ImmutableSet<String> getAffectedByStarlarkTransitionViaDiff(
      BuildOptions toOptions, BuildOptions baselineOptions) {
    if (toOptions.equals(baselineOptions)) {
      return ImmutableSet.of();
    }

    OptionsDiff diff = OptionsDiff.diff(toOptions, baselineOptions);
    Stream<String> diffNative =
        diff.getFirst().keySet().stream()
            .map(option -> COMMAND_LINE_OPTION_PREFIX + option.getOptionName());
    // Note: getChangedStarlarkOptions includes all changed options, added options and removed
    //   options between baselineOptions and toOptions. This is necessary since there is no current
    //   notion of trimming a Starlark option: 'null' or non-existent justs means set to default.
    Stream<String> diffStarlark = diff.getChangedStarlarkOptions().stream().map(Label::toString);
    return Streams.concat(diffNative, diffStarlark).collect(toImmutableSet());
  }

  /**
   * Extend the global build config affectedByStarlarkTransition, by adding any new option names
   * from changedOptions. Does nothing if output directory naming scheme is not in legacy mode.
   */
  public static void updateAffectedByStarlarkTransition(
      CoreOptions buildConfigOptions, Set<String> changedOptions) {
    if (changedOptions.isEmpty()) {
      return;
    }
    Set<String> mutableCopyToUpdate =
        new TreeSet<>(buildConfigOptions.affectedByStarlarkTransition);
    mutableCopyToUpdate.addAll(changedOptions);
    buildConfigOptions.affectedByStarlarkTransition =
        ImmutableList.sortedCopyOf(mutableCopyToUpdate);
  }

  private FunctionTransitionUtil() {}
}
