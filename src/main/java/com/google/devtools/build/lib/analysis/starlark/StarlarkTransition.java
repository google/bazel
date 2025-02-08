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
package com.google.devtools.build.lib.analysis.starlark;

import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptionDetails;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.Settings;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A marker class for configuration transitions that are defined in Starlark. */
public abstract class StarlarkTransition implements ConfigurationTransition {

  private final StarlarkDefinedConfigTransition starlarkDefinedConfigTransition;

  protected StarlarkTransition(StarlarkDefinedConfigTransition starlarkDefinedConfigTransition) {
    this.starlarkDefinedConfigTransition = starlarkDefinedConfigTransition;
  }

  @Override
  public String getName() {
    return "Starlark transition:" + starlarkDefinedConfigTransition.getLocation();
  }

  // Get the inputs of the starlark transition as a list of canonicalized labels strings.
  private ImmutableSet<String> getInputs() {
    return starlarkDefinedConfigTransition.getInputsCanonicalizedToGiven().keySet();
  }

  // Get the outputs of the starlark transition as a list of canonicalized labels strings.
  private ImmutableSet<String> getOutputs() {
    return starlarkDefinedConfigTransition.getOutputsCanonicalizedToGiven().keySet();
  }

  @Override
  public void addRequiredFragments(
      RequiredConfigFragmentsProvider.Builder requiredFragments, BuildOptionDetails optionDetails) {
    for (String optionStarlarkName : Iterables.concat(getInputs(), getOutputs())) {
      if (!optionStarlarkName.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        requiredFragments.addStarlarkOption(Label.parseCanonicalUnchecked(optionStarlarkName));
      } else {
        String optionNativeName = optionStarlarkName.substring(COMMAND_LINE_OPTION_PREFIX.length());
        // A null optionsClass means the flag is invalid. Starlark transitions independently catch
        // and report that (search the code for "do not correspond to valid settings").
        Class<? extends FragmentOptions> optionsClass =
            optionDetails.getOptionClass(optionNativeName);
        if (optionsClass != null) {
          requiredFragments.addOptionsClass(optionsClass);
        }
      }
    }
  }

  /** Exception class for exceptions thrown during application of a starlark-defined transition */
  // TODO(blaze-configurability): add more information to this exception e.g. originating target of
  // transition.
  public static class TransitionException extends Exception {
    public TransitionException(String message) {
      super(message);
    }

    public TransitionException(Throwable cause) {
      super(cause);
    }

    public TransitionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Given a {@link ConfigurationTransition}, decompose (if possible) and find all referenced
   * Starlark build settings.
   *
   * <p>If a transition references a build setting via an alias, this set includes the alias' label
   * and *does not* include the actual label i.e. this method returns all referenced labels exactly
   * as they are.
   */
  public static ImmutableSet<Label> getAllStarlarkBuildSettings(ConfigurationTransition root) {
    ImmutableSet.Builder<Label> keyBuilder = new ImmutableSet.Builder<>();
    try {
      root.visit(
          (StarlarkTransitionVisitor)
              transition ->
                  keyBuilder.addAll(
                      getRelevantStarlarkSettingsFromTransition(
                          transition, Settings.INPUTS_AND_OUTPUTS)));
    } catch (TransitionException e) {
      // Not actually thrown in the visitor, but declared.
    }
    return keyBuilder.build();
  }

  /**
   * Method to be called after Starlark-transitions are applied. Checks outputs.
   *
   * <p>We only do validation on Starlark-defined build settings. Native options (designated with
   * {@code COMMAND_LINE_OPTION_PREFIX}) already have their output values checked in {@link
   * FunctionTransitionUtil#applyTransition}.
   *
   * <p>Remove build settings in {@code toOptions} that have been set to their default value. This
   * is how we ensure that an unset build setting and a set-to-default build settings represent the
   * same configuration.
   *
   * @param root transition that was applied. Likely a {@link
   *     com.google.devtools.build.lib.analysis.config.transitions.ComposingTransitionFactory} so we
   *     decompose and post-process all StarlarkTransitions out of whatever transition is passed
   *     here.
   * @param details a StarlarkBuildSettingsDetailsValue whose corresponding key was all the input
   *     and output settings of root. Use {@link getAllStarlarkBuildSettings}.
   * @param toOptions result of applying {@code root}
   * @return validated toOptions with default values filtered out
   * @throws TransitionException if an error occurred during Starlark transition application.
   */
  // TODO(juliexxia): the current implementation masks certain bad transitions and only checks the
  // final result. I.e. if a transition that writes a non int --//int-build-setting is composed
  // with another transition that writes --//int-build-setting (without reading it first), then
  // the bad output of transition 1 is masked.
  public static Map<String, BuildOptions> validate(
      ConfigurationTransition root,
      StarlarkBuildSettingsDetailsValue details,
      Map<String, BuildOptions> toOptions)
      throws TransitionException {
    // Collect settings that are inputs or outputs of the transition together with their types.
    // Output setting values will be validated and removed if set to their default.
    // Raw means these have not been unaliased.
    ImmutableSet.Builder<Label> rawInputAndOutputSettingsBuilder = ImmutableSet.builder();
    // Collect settings that were only used as inputs to the transition and thus possibly had their
    // default values added to the fromOptions. They will be removed if set to ther default, but
    // should not be validated.
    ImmutableSet.Builder<Label> inputOnlySettingsBuilder = ImmutableSet.builder();
    root.visit(
        (StarlarkTransitionVisitor)
            transition -> {
              ImmutableSet<Label> inputAndOutputSettings =
                  getRelevantStarlarkSettingsFromTransition(
                      transition, Settings.INPUTS_AND_OUTPUTS);
              ImmutableSet<Label> outputSettings =
                  getRelevantStarlarkSettingsFromTransition(transition, Settings.OUTPUTS);
              for (Label setting : inputAndOutputSettings) {
                rawInputAndOutputSettingsBuilder.add(setting);
                if (!outputSettings.contains(setting)) {
                  inputOnlySettingsBuilder.add(setting);
                }
              }
            });

    ImmutableSet<Label> rawInputAndOutputSettings = rawInputAndOutputSettingsBuilder.build();
    ImmutableSet<Label> inputOnlySettings = inputOnlySettingsBuilder.build();

    // Return early if the transition has neither inputs nor outputs (rare).
    if (rawInputAndOutputSettings.isEmpty()) {
      return toOptions;
    }

    // Verify changed settings were changed to something reasonable for their type and filter out
    // default values.
    ImmutableMap.Builder<String, BuildOptions> cleanedOptionMap = ImmutableMap.builder();
    for (Map.Entry<String, BuildOptions> entry : toOptions.entrySet()) {
      // Lazily initialized to optimize for the common case where we don't modify anything.
      BuildOptions.Builder cleanedOptions = null;
      // Clean up aliased values.
      // TODO(blaze-configurability-team): This is actually a quagmire of undefined behavior
      //   if a user asks for both an alias and the unaliased build setting.
      BuildOptions options = unalias(entry.getValue(), details.aliasToActual());
      for (Label maybeAliasSetting : rawInputAndOutputSettings) {
        // Note that if the build setting may be referenced in the transition via an alias
        Label setting = details.aliasToActual().getOrDefault(maybeAliasSetting, maybeAliasSetting);
        // Input-only settings may have had their literal default value added to the BuildOptions
        // so that the transition can read them. We have to remove these explicitly set value here
        // to preserve the invariant that Starlark settings at default values are not explicitly set
        // in the BuildOptions.
        final boolean isInputOnlySettingAtDefault =
            inputOnlySettings.contains(maybeAliasSetting)
                && details
                    .buildSettingToDefault()
                    .get(setting)
                    .equals(options.getStarlarkOptions().get(setting));
        // For output settings, the raw value returned by the transition first has to be validated
        // and converted to the proper type before it can be compared to the default value.
        if (isInputOnlySettingAtDefault
            || validateAndCheckIfAtDefault(
                details, options, maybeAliasSetting, setting, rawInputAndOutputSettings)) {
          if (cleanedOptions == null) {
            cleanedOptions = options.toBuilder();
          }
          cleanedOptions.removeStarlarkOption(setting);
        }
      }
      // Keep the same instance if we didn't do anything to maintain reference equality later on.
      options = cleanedOptions != null ? cleanedOptions.build() : options;
      cleanedOptionMap.put(entry.getKey(), options);
    }
    return cleanedOptionMap.buildOrThrow();
  }

  /**
   * Validate the value of a particular build setting after a transition has been applied.
   *
   * @param buildSettingRule the build setting to validate.
   * @param options the {@link BuildOptions} reflecting the post-transition configuration.
   * @param maybeAliasSetting the label used to refer to the build setting in the transition,
   *     possibly an alias. This is only used for error messages.
   * @param inputAndOutputSettings the transition input and output settings. This is only used for
   *     error messages.
   * @return {@code true} if and only if the setting is set to its default value after the
   *     transition.
   * @throws TransitionException if the value returned by the transition for this setting has an
   *     invalid type.
   */
  private static boolean validateAndCheckIfAtDefault(
      StarlarkBuildSettingsDetailsValue details,
      BuildOptions options,
      Label maybeAliasSetting,
      Label setting,
      Set<Label> inputAndOutputSettings)
      throws TransitionException {
    Object newValue = options.getStarlarkOptions().get(setting);
    // TODO(b/154132845): fix NPE occasionally observed here.
    Preconditions.checkState(
        newValue != null,
        "Error while attempting to validate new values from starlark"
            + " transition(s) with the inputs and outputs %s. Post-transition configuration should"
            + " include '%s' but only includes starlark options: %s. If you run into this error"
            + " please ping b/154132845 or email blaze-configurability@google.com.",
        inputAndOutputSettings,
        setting,
        options.getStarlarkOptions().keySet());
    boolean allowsMultiple = details.buildSettingIsAllowsMultiple().contains(setting);
    if (allowsMultiple) {
      // if this setting allows multiple settings
      if (!(newValue instanceof List<?> rawNewValueAsList)) {
        throw new TransitionException(
            String.format(
                "'%s' allows multiple values and must be set"
                    + " in transition using a starlark list instead of single value '%s'",
                setting, newValue));
      }
      List<Object> convertedValue = new ArrayList<>();
      Type<?> type = details.buildSettingToType().get(setting);
      for (Object value : rawNewValueAsList) {
        try {
          convertedValue.add(type.convert(value, maybeAliasSetting));
        } catch (ConversionException e) {
          throw new TransitionException(e);
        }
      }
      return convertedValue.equals(ImmutableList.of(details.buildSettingToDefault().get(setting)));
    } else {
      // if this setting does not allow multiple settings
      Object convertedValue;
      try {
        convertedValue =
            details.buildSettingToType().get(setting).convert(newValue, maybeAliasSetting);
      } catch (ConversionException e) {
        throw new TransitionException(e);
      }
      return convertedValue.equals(details.buildSettingToDefault().get(setting));
    }
  }

  /*
   * Resolve aliased build setting issues
   *
   * <p>If a build setting is transitioned upon via an alias, the resulting {@link
   * BuildOptions#getStarlarkOptions()} map will look like this:
   *
   * <entry1>alias-label -> new-value
   * <entry2>actual-label -> old-value
   *
   * <p>we need to collapse this to the correct single entry: actual-label -> new-value.
   * By the end of this method, the starlark options map in the returned {@link BuildOptions}
   * contains only keys that are actual build settings, no aliases.
   */
  private static BuildOptions unalias(
      BuildOptions options, ImmutableMap<Label, Label> aliasToActual) {
    if (aliasToActual.isEmpty()) {
      return options;
    }
    Collection<Label> aliases = aliasToActual.keySet();
    Collection<Label> actuals = aliasToActual.values();
    BuildOptions.Builder toReturn = options.toBuilder();
    for (Map.Entry<Label, Object> entry : options.getStarlarkOptions().entrySet()) {
      Label setting = entry.getKey();
      if (actuals.contains(setting)) {
        // if entry is keyed by an actual (e.g. <entry2> in javadoc), don't care about its value
        // it's stale
        continue;
      }
      if (aliases.contains(setting)) {
        // if an entry is keyed by an alias (e.g. <entry1> in javadoc), newly key (overwrite) its
        // actual to its alias' value and remove the alias-keyed entry
        toReturn.addStarlarkOption(
            aliasToActual.get(setting), options.getStarlarkOptions().get(setting));
        toReturn.removeStarlarkOption(setting);
      } else {
        // else - just copy over
        toReturn.addStarlarkOption(entry.getKey(), entry.getValue());
      }
    }
    return toReturn.build();
  }

  /** Adds the default values for a transition's input build settings to its input build options. */
  public static BuildOptions addDefaultStarlarkOptions(
      BuildOptions fromOptions,
      ConfigurationTransition transition,
      StarlarkBuildSettingsDetailsValue details)
      throws TransitionException {
    if (details.buildSettingToDefault().isEmpty()) {
      // No need to traverse the transition to find its Starlark flag inputs. There are none.
      return fromOptions;
    }

    BuildOptions.Builder optionsWithDefaults = null;
    for (Label maybeAliasSetting : getAllStarlarkBuildSettings(transition)) {
      // details will only have the defaults of the actual setting so must unalias
      Label setting = details.aliasToActual().getOrDefault(maybeAliasSetting, maybeAliasSetting);
      if (!fromOptions.getStarlarkOptions().containsKey(maybeAliasSetting)) {
        if (optionsWithDefaults == null) {
          optionsWithDefaults = fromOptions.toBuilder();
        }
        optionsWithDefaults.addStarlarkOption(
            maybeAliasSetting, details.buildSettingToDefault().get(setting));
      }
    }
    return optionsWithDefaults == null
        ? fromOptions
        : optionsWithDefaults.addScopeTypeMap(fromOptions.getScopeTypeMap()).build();
  }

  private static ImmutableSet<Label> getRelevantStarlarkSettingsFromTransition(
      StarlarkTransition transition, Settings settings) {
    ImmutableSet.Builder<Label> result = ImmutableSet.builder();
    switch (settings) {
      case INPUTS -> addLabelIfRelevant(result, transition.getInputs());
      case OUTPUTS -> addLabelIfRelevant(result, transition.getOutputs());
      case INPUTS_AND_OUTPUTS -> {
        addLabelIfRelevant(result, transition.getInputs());
        addLabelIfRelevant(result, transition.getOutputs());
      }
    }
    return result.build();
  }

  private static void addLabelIfRelevant(
      ImmutableSet.Builder<Label> builder, Iterable<String> entries) {
    for (String entry : entries) {
      if (!entry.startsWith(COMMAND_LINE_OPTION_PREFIX)) {
        builder.add(Label.parseCanonicalUnchecked(entry));
      }
    }
  }

  @Override
  public boolean equals(Object object) {
    if (object == this) {
      return true;
    }
    if (object instanceof StarlarkTransition) {
      StarlarkDefinedConfigTransition starlarkDefinedConfigTransition =
          ((StarlarkTransition) object).starlarkDefinedConfigTransition;
      return Objects.equals(starlarkDefinedConfigTransition, this.starlarkDefinedConfigTransition);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(starlarkDefinedConfigTransition);
  }

  /** Given a transition, figures out if it composes any Starlark transitions. */
  public static boolean doesStarlarkTransition(ConfigurationTransition root)
      throws TransitionException {
    AtomicBoolean doesStarlarkTransition = new AtomicBoolean(false);
    root.visit((StarlarkTransitionVisitor) transition -> doesStarlarkTransition.set(true));
    return doesStarlarkTransition.get();
  }

  @FunctionalInterface
  // This is only used in this class to handle the cast and the exception
  @SuppressWarnings("FunctionalInterfaceMethodChanged")
  private interface StarlarkTransitionVisitor
      extends ConfigurationTransition.Visitor<TransitionException> {
    @Override
    default void accept(ConfigurationTransition transition) throws TransitionException {
      if (transition instanceof StarlarkTransition starlarkTransition) {
        this.accept(starlarkTransition);
      }
    }

    void accept(StarlarkTransition transition) throws TransitionException;
  }
}
