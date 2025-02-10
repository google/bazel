// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.config;

import static com.google.devtools.build.lib.analysis.config.CoreOptionConverters.BUILD_SETTING_CONVERTERS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.BuildSettingProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.LicensesProviderImpl;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptionDetails;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.rules.config.ConfigRuleClasses.ConfigSettingRule;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.FieldOptionDefinition;
import com.google.devtools.common.options.IsolatedOptionsData;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Implementation for the config_setting rule.
 *
 * <p>This is a "pseudo-rule" in that its purpose isn't to generate output artifacts from input
 * artifacts. Rather, it provides configuration context to rules that depend on it.
 */
public final class ConfigSetting implements RuleConfiguredTargetFactory {

  /** Flags we'd like to remove once there are no more repo references. */
  private static final ImmutableSet<String> DEPRECATED_FLAGS =
      ImmutableSet.of("cpu", "host_cpu", "crosstool_top");

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, ActionConflictException {
    AttributeMap attributes = NonconfigurableAttributeMapper.of(ruleContext.getRule());

    Optional<String> likelyLabelInvalidSetting =
        attributes.get(ConfigSettingRule.SETTINGS_ATTRIBUTE, Types.STRING_DICT).keySet().stream()
            .filter(s -> s.startsWith("@") || s.startsWith("//") || s.startsWith(":"))
            .findFirst();
    if (likelyLabelInvalidSetting.isPresent()) {
      ruleContext.attributeError(
          ConfigSettingRule.SETTINGS_ATTRIBUTE,
          String.format(
              "'%s' is not a valid setting name, but appears to be a label. Did you mean to place"
                  + " it in %s instead?",
              likelyLabelInvalidSetting.get(), ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE));
      return null;
    }

    // Get the built-in Blaze flag settings that match this rule.
    ImmutableMultimap<String, String> nativeFlagSettings =
        ImmutableMultimap.<String, String>builder()
            .putAll(
                attributes.get(ConfigSettingRule.SETTINGS_ATTRIBUTE, Types.STRING_DICT).entrySet())
            .putAll(
                attributes
                    .get(ConfigSettingRule.DEFINE_SETTINGS_ATTRIBUTE, Types.STRING_DICT)
                    .entrySet()
                    .stream()
                    .map(in -> Maps.immutableEntry("define", in.getKey() + "=" + in.getValue()))
                    .collect(ImmutableList.toImmutableList()))
            .build();

    // Get the user-defined flag settings that match this rule.
    Map<Label, String> userDefinedFlagSettings =
        attributes.get(
            ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE, BuildType.LABEL_KEYED_STRING_DICT);

    // Get the platform constraint settings that match this rule.
    List<Label> constraintValueSettings =
        attributes.get(ConfigSettingRule.CONSTRAINT_VALUES_ATTRIBUTE, BuildType.LABEL_LIST);

    // Check that this config_setting contains at least one of {values, define_values,
    // constraint_values}
    if (!valuesAreSet(
        nativeFlagSettings, userDefinedFlagSettings, constraintValueSettings, ruleContext)) {
      return null;
    }

    BuildOptionDetails optionDetails = ruleContext.getConfiguration().getBuildOptionDetails();
    boolean nativeFlagsMatch =
        nativeFlagsMatch(nativeFlagSettings.entries(), optionDetails, ruleContext);

    UserDefinedFlagMatch userDefinedFlags =
        UserDefinedFlagMatch.fromAttributeValueAndPrerequisites(
            userDefinedFlagSettings, optionDetails, ruleContext);

    boolean constraintValuesMatch = constraintValuesMatch(ruleContext);

    if (ruleContext.hasErrors()) {
      return null;
    }

    ConfigMatchingProvider configMatcher =
        ConfigMatchingProvider.create(
            ruleContext.getLabel(),
            nativeFlagSettings,
            userDefinedFlags.getSpecifiedFlagValues(),
            ImmutableSet.copyOf(constraintValueSettings),
            ConfigMatchingProvider.MatchResult.merge(
                userDefinedFlags.result(), nativeFlagsMatch && constraintValuesMatch));

    return new RuleConfiguredTargetBuilder(ruleContext)
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY)
        .addProvider(FileProvider.class, FileProvider.EMPTY)
        .addProvider(FilesToRunProvider.class, FilesToRunProvider.EMPTY)
        .addNativeDeclaredProvider(LicensesProviderImpl.EMPTY)
        .addProvider(ConfigMatchingProvider.class, configMatcher)
        .build();
  }

  @Override
  public void addRuleImplSpecificRequiredConfigFragments(
      RequiredConfigFragmentsProvider.Builder requiredFragments,
      AttributeMap attributes,
      BuildConfigurationValue configuration) {
    // values
    attributes
        .get(ConfigSettingRule.SETTINGS_ATTRIBUTE, Types.STRING_DICT)
        .forEach(
            (optionName, value) -> {
              if (optionName.equals("define")) {
                int equalsIndex = value.indexOf('=');
                requiredFragments.addDefine(
                    equalsIndex > 0 ? value.substring(0, equalsIndex) : value);
              } else {
                Class<? extends FragmentOptions> optionsClass =
                    configuration.getBuildOptionDetails().getOptionClass(optionName);
                if (optionsClass != null) {
                  requiredFragments.addOptionsClass(optionsClass);
                }
              }
            });

    // define_values
    requiredFragments.addDefines(
        attributes.get(ConfigSettingRule.DEFINE_SETTINGS_ATTRIBUTE, Types.STRING_DICT).keySet());

    // flag_values
    requiredFragments.addStarlarkOptions(
        attributes
            .get(ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE, BuildType.LABEL_KEYED_STRING_DICT)
            .keySet());
  }

  /**
   * Returns true if all <code>constraint_values</code> settings are valid and match this
   * configuration, false otherwise.
   *
   * <p>May generate rule errors on bad settings (e.g. wrong target types).
   */
  private static boolean constraintValuesMatch(RuleContext ruleContext) {
    List<ConstraintValueInfo> constraintValues = new ArrayList<>();
    for (TransitiveInfoCollection dep :
        ruleContext.getPrerequisites(ConfigSettingRule.CONSTRAINT_VALUES_ATTRIBUTE)) {
      if (!PlatformProviderUtils.hasConstraintValue(dep)) {
        ruleContext.attributeError(
            ConfigSettingRule.CONSTRAINT_VALUES_ATTRIBUTE,
            dep.getLabel() + " is not a constraint_value");
      } else {
        constraintValues.add(PlatformProviderUtils.constraintValue(dep));
      }
    }
    if (ruleContext.hasErrors()) {
      return false;
    }

    if (constraintValues.isEmpty()) {
      return true;
    }

    // The set of constraint_values in a config_setting should never contain multiple
    // constraint_values that map to the same constraint_setting. This method checks if there are
    // duplicates and records an error if so.
    try {
      ConstraintCollection.validateConstraints(constraintValues);
    } catch (ConstraintCollection.DuplicateConstraintException e) {
      ruleContext.ruleError(
          ConstraintCollection.DuplicateConstraintException.formatError(e.duplicateConstraints()));
        return false;
    }

    if (ruleContext.getToolchainContext() == null) {
      ruleContext.attributeError(
          ConfigSettingRule.CONSTRAINT_VALUES_ATTRIBUTE, "No target platform is present");
      return false;
    }

    return ruleContext
        .getToolchainContext()
        .targetPlatform()
        .constraints()
        .containsAll(constraintValues);
  }

  /**
   * Returns whether the given label falls under the {@code //tools} package (including subpackages)
   * of the tools repository.
   */
  @VisibleForTesting
  static boolean isUnderToolsPackage(Label label, RepositoryName toolsRepository) {
    PackageIdentifier packageId = label.getPackageIdentifier();
    if (!packageId.getRepository().equals(toolsRepository)) {
      return false;
    }
    try {
      return packageId.getPackageFragment().subFragment(0, 1).equals(PathFragment.create("tools"));
    } catch (IndexOutOfBoundsException e) {
      // Top-level package (//).
      return false;
    }
  }

  /**
   * User error when value settings can't be properly parsed.
   */
  private static final String PARSE_ERROR_MESSAGE = "error while parsing configuration settings: ";

  /**
   * Check to make sure this config_setting contains and sets least one of {values, define_values,
   * flag_value or constraint_values}.
   */
  private static boolean valuesAreSet(
      ImmutableMultimap<String, String> nativeFlagSettings,
      Map<Label, String> userDefinedFlagSettings,
      Iterable<Label> constraintValues,
      RuleErrorConsumer errors) {
    if (nativeFlagSettings.isEmpty()
        && userDefinedFlagSettings.isEmpty()
        && Iterables.isEmpty(constraintValues)) {
      errors.ruleError(
          String.format(
              "Either %s, %s or %s must be specified and non-empty",
              ConfigSettingRule.SETTINGS_ATTRIBUTE,
              ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
              ConfigSettingRule.CONSTRAINT_VALUES_ATTRIBUTE));
      return false;
    }
    return true;
  }

  /**
   * Given a list of [flagName, flagValue] pairs for native Blaze flags, returns true if flagName ==
   * flagValue for every item in the list under this configuration, false otherwise.
   */
  private static boolean nativeFlagsMatch(
      Collection<Map.Entry<String, String>> expectedSettings,
      BuildOptionDetails options,
      RuleContext ruleContext) {
    // Rather than returning fast when we find a mismatch, continue looking at the other flags
    // to check they're indeed valid flag specifications.
    boolean foundMismatch = false;

    for (Map.Entry<String, String> setting : expectedSettings) {
      String optionName = setting.getKey();
      String expectedRawValue = setting.getValue();
      if (!checkOptionValue(options, ruleContext, optionName, expectedRawValue)) {
        foundMismatch = true;
      }
    }
    return !foundMismatch;
  }

  /** Returns {@code true} if the option is set to the expected value in the configuration. */
  private static boolean checkOptionValue(
      BuildOptionDetails options,
      RuleContext ruleContext,
      String optionName,
      String expectedRawValue) {
    if (DEPRECATED_FLAGS.contains(optionName)) {
      ruleContext.ruleWarning(
          String.format(
              "select() on %s is deprecated. Use platform constraints instead:"
                  + " https://bazel.build/docs/configurable-attributes#platforms.",
              optionName));
    }
    Class<? extends FragmentOptions> optionClass = options.getOptionClass(optionName);
    if (optionClass == null) {
      if (isTestOption(optionName)) {
        // If TestOptions isn't present then they were trimmed, so any test options set are
        // considered unset by default.
        return false;
      }

      // Report the unknown option as an error.
      ruleContext.attributeError(
          ConfigSettingRule.SETTINGS_ATTRIBUTE,
          String.format(PARSE_ERROR_MESSAGE + "unknown option: '%s'", optionName));
      return false;
    }

    OptionsParser parser;
    try {
      parser = OptionsParser.builder().optionsClasses(optionClass).build();
      parser.parse("--" + optionName + "=" + expectedRawValue);
    } catch (OptionsParsingException ex) {
      ruleContext.attributeError(
          ConfigSettingRule.SETTINGS_ATTRIBUTE, PARSE_ERROR_MESSAGE + ex.getMessage());
      return false;
    }

    Object expectedParsedValue = parser.getOptions(optionClass).asMap().get(optionName);
    return optionMatches(options, optionName, expectedParsedValue);
  }

  // Special hard-coded check to allow config_setting to handle test options even when the test
  // configuration has been trimmed.
  private static boolean isTestOption(String optionName) {
    return IsolatedOptionsData.getAllOptionDefinitionsForClass(TestOptions.class).stream()
        .map(FieldOptionDefinition::getOptionName)
        .anyMatch(name -> name.equals(optionName));
  }

  /**
   * For single-value options, returns true iff the option's value matches the expected value.
   *
   * <p>For multi-value List options returns true iff any of the option's values matches the
   * expected value(s). This means "--ios_multi_cpus=a --ios_multi_cpus=b --ios_multi_cpus=c"
   * matches the expected conditions {'ios_multi_cpus': 'a' } and { 'ios_multi_cpus': 'b,c' } but
   * not { 'ios_multi_cpus': 'd' }.
   *
   * <p>For multi-value Map options, returns true iff the last instance with the same key as the
   * expected key has the same value. This means "--define foo=1 --define bar=2" matches { 'define':
   * 'foo=1' }, but "--define foo=1 --define bar=2 --define foo=3" doesn't match. Note that the
   * definition of --define states that the last instance takes precedence. Also note that there's
   * no options-parsing support for multiple values in a single clause, e.g. { 'define':
   * 'foo=1,bar=2' } expands to { "foo": "1,bar=2" }, not {"foo": 1, "bar": "2"}.
   */
  private static boolean optionMatches(
      BuildOptionDetails options, String optionName, Object expectedValue) {
    Object actualValue = options.getOptionValue(optionName);
    if (actualValue == null) {
      return expectedValue == null;

      // Single-value case:
    } else if (!options.allowsMultipleValues(optionName)) {
      return actualValue.equals(expectedValue);
    }

    // Multi-value case:
    Preconditions.checkState(actualValue instanceof List);
    Preconditions.checkState(expectedValue instanceof List);
    List<?> actualList = (List<?>) actualValue;
    List<?> expectedList = (List<?>) expectedValue;

    if (actualList.isEmpty() || expectedList.isEmpty()) {
      return actualList.isEmpty() && expectedList.isEmpty();
    }

    // Multi-value map:
    if (actualList.get(0) instanceof Map.Entry) {
      // The config_setting's expected value *must* be a single map entry (see method comments).
      Object expectedListValue = Iterables.getOnlyElement(expectedList);
      Map.Entry<?, ?> expectedEntry = (Map.Entry<?, ?>) expectedListValue;
      for (Object elem : Lists.reverse(actualList)) {
        Map.Entry<?, ?> actualEntry = (Map.Entry<?, ?>) elem;
        if (actualEntry.getKey().equals(expectedEntry.getKey())) {
          // Found a key match!
          return actualEntry.getValue().equals(expectedEntry.getValue());
        }
      }
      return false;
    }

    // Multi-value list:
    return actualList.containsAll(expectedList);
  }

  private static final class UserDefinedFlagMatch {
    private final ConfigMatchingProvider.MatchResult result;
    private final ImmutableMap<Label, String> specifiedFlagValues;

    private static final Joiner QUOTED_COMMA_JOINER = Joiner.on("', '");

    private UserDefinedFlagMatch(
        ConfigMatchingProvider.MatchResult result,
        ImmutableMap<Label, String> specifiedFlagValues) {
      this.result = result;
      this.specifiedFlagValues = specifiedFlagValues;
    }

    /** Returns whether the specified flag values matched the actual flag values. */
    public ConfigMatchingProvider.MatchResult result() {
      return result;
    }

    /** Gets the specified flag values, with aliases converted to their original targets' labels. */
    ImmutableMap<Label, String> getSpecifiedFlagValues() {
      return specifiedFlagValues;
    }

    /** Groups aliases in the list of prerequisites by the target they point to. */
    private static ListMultimap<Label, Label> collectAliases(
        Iterable<? extends TransitiveInfoCollection> prerequisites) {
      ImmutableListMultimap.Builder<Label, Label> targetsToAliases =
          new ImmutableListMultimap.Builder<>();
      for (TransitiveInfoCollection target : prerequisites) {
        targetsToAliases.put(target.getLabel(), AliasProvider.getDependencyLabel(target));
      }
      return targetsToAliases.build();
    }

    /**
     * The 'flag_values' attribute takes a label->string dictionary of feature flags and
     * starlark-defined settings to their values in string form.
     *
     * @param attributeValue map of user-defined flag labels to their values as set in the
     *     'flag_values' attribute
     * @param optionDetails information about the configuration to match against
     * @param ruleContext this rule's RuleContext
     */
    static UserDefinedFlagMatch fromAttributeValueAndPrerequisites(
        Map<Label, String> attributeValue,
        BuildOptionDetails optionDetails,
        RuleContext ruleContext) {
      Map<Label, String> specifiedFlagValues = new LinkedHashMap<>();

      boolean matches = true;
      // Only configuration-dependent errors should be deferred.
      ArrayList<String> deferredErrors = new ArrayList<>();
      boolean foundDuplicate = false;

      // Get the actual targets the 'flag_values' keys reference.
      Iterable<? extends TransitiveInfoCollection> prerequisites =
          ruleContext.getPrerequisites(ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE);

      for (TransitiveInfoCollection target : prerequisites) {
        Label actualLabel = target.getLabel();
        Label specifiedLabel = AliasProvider.getDependencyLabel(target);
        String specifiedValue =
            maybeCanonicalizeLabel(attributeValue.get(specifiedLabel), target, ruleContext);
        if (specifiedFlagValues.containsKey(actualLabel)) {
          foundDuplicate = true;
        }
        specifiedFlagValues.put(actualLabel, specifiedValue);

        if (target.satisfies(ConfigFeatureFlagProvider.REQUIRE_CONFIG_FEATURE_FLAG_PROVIDER)) {
          // config_feature_flag
          ConfigFeatureFlagProvider provider = ConfigFeatureFlagProvider.fromTarget(target);
          if (!provider.isValidValue(specifiedValue)) {
            // This is a configuration-independent error on the attributes of config_setting.
            // So, is appropriate to error immediately.
            ruleContext.attributeError(
                ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
                String.format(
                    "error while parsing user-defined configuration values: "
                        + "'%s' is not a valid value for '%s'",
                    specifiedValue, specifiedLabel));
            matches = false;
            continue;
          }
          if (!Strings.isNullOrEmpty(provider.getError())) {
            deferredErrors.add(provider.getError());
            continue;
          } else if (!provider.getFlagValue().equals(specifiedValue)) {
            matches = false;
          }
        } else if (target.satisfies(BuildSettingProvider.REQUIRE_BUILD_SETTING_PROVIDER)) {
          // build setting
          BuildSettingProvider provider = target.getProvider(BuildSettingProvider.class);

          Object configurationValue;
          if (optionDetails.getOptionValue(provider.getLabel()) != null) {
            configurationValue = optionDetails.getOptionValue(provider.getLabel());
          } else {
            configurationValue = provider.getDefaultValue();
          }

          Object convertedSpecifiedValue;
          try {
            // We don't need to supply a base package or repo mapping for the conversion here,
            // because `specifiedValue` is already canonicalized.
            convertedSpecifiedValue =
                BUILD_SETTING_CONVERTERS
                    .get(provider.getType())
                    .convert(specifiedValue, /*conversionContext=*/ null);
          } catch (OptionsParsingException e) {
            // This is a configuration-independent error on the attributes of config_setting.
            // So, is appropriate to error immediately.
            ruleContext.attributeError(
                ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
                String.format(
                    "error while parsing user-defined configuration values: "
                        + "'%s' cannot be converted to %s type %s",
                    specifiedValue, specifiedLabel, provider.getType()));
            matches = false;
            continue;
          }

          if (configurationValue instanceof List) {
            // If the build_setting is a list it's either an allow-multiple string-typed build
            // setting or a string_list-typed build setting. We use the same semantics as for
            // multi-value native flags: if *any* entry in the list matches the config_setting's
            // expected entry, it's a match. In other words,
            // config_setting(flag_values {"//foo": "bar"} matches //foo=["bar", "baz"].

            // If this is an allow-multiple build setting, the converter will have converted the
            // config settings value to a singular object, if it's a string_list build setting the
            // converter will have converted it to a list.
            Iterable<?> specifiedValueAsIterable =
                provider.allowsMultiple()
                    ? ImmutableList.of(convertedSpecifiedValue)
                    : (Iterable<?>) convertedSpecifiedValue;
            if (Iterables.size(specifiedValueAsIterable) != 1) {
              // This is a configuration-independent error on the attributes of config_setting.
              // So, is appropriate to error immediately.
              ruleContext.attributeError(
                  ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
                  String.format(
                      "\"%s\" not a valid value for flag %s. Only single, exact values are"
                          + " allowed. If you want to match multiple values, consider Skylib's "
                          + "selects.config_setting_group",
                      specifiedValue, specifiedLabel));
              matches = false;
            } else if (!((List<?>) configurationValue)
                .contains(Iterables.getOnlyElement(specifiedValueAsIterable))) {
              matches = false;
            }
          } else if (!configurationValue.equals(convertedSpecifiedValue)) {
            matches = false;
          }
        } else {
          // This should be configuration-independent error on the attributes of config_setting.
          // So, is appropriate to error immediately.
          // 'Should' b/c the underlying flag rule COULD change providers based on configuration;
          // however, this is HIGHLY irregular.
          ruleContext.attributeError(
              ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
              String.format(
                  "error while parsing user-defined configuration values: "
                      + "%s keys must be build settings or feature flags and %s is not",
                  ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE, specifiedLabel));
          matches = false;
        }
      }

      // attributeValue is the source of the prerequisites in prerequisites, so the final map built
      // from iterating over prerequisites should always be the same size, barring duplicates.
      assert foundDuplicate || attributeValue.size() == specifiedFlagValues.size();

      if (foundDuplicate) {
        ListMultimap<Label, Label> aliases = collectAliases(prerequisites);
        for (Label actualLabel : aliases.keySet()) {
          List<Label> aliasList = aliases.get(actualLabel);
          if (aliasList.size() > 1) {
            // This is a configuration-independent error on the attributes of config_setting.
            // So, is appropriate to error immediately.
            ruleContext.attributeError(
                ConfigSettingRule.FLAG_SETTINGS_ATTRIBUTE,
                String.format(
                    "flag '%s' referenced multiple times as ['%s']",
                    actualLabel, QUOTED_COMMA_JOINER.join(aliasList)));
          }
        }
        matches = false;
      }
      if (!deferredErrors.isEmpty()) {
        return new UserDefinedFlagMatch(
            ConfigMatchingProvider.MatchResult.InError.create(Joiner.on(", ").join(deferredErrors)),
            ImmutableMap.copyOf(specifiedFlagValues));
      }

      return new UserDefinedFlagMatch(
          ConfigMatchingProvider.MatchResult.create(matches),
          ImmutableMap.copyOf(specifiedFlagValues));
    }
  }

  /**
   * Given a 'flag_values = {"//ref:to:flagTarget": "expectedValue"}' pair, if expectedValue is a
   * relative label (e.g. ":sometarget") and flagTarget's value(s) are label-typed, returns an
   * absolute form of the label under the config_setting's package. Else returns the original value
   * unchanged.
   *
   * <p>This lets config_setting use relative labels to match against the actual values, which are
   * already represented in absolute form.
   *
   * <p>The value is returned as a string because it's subsequently fed through the flag's type
   * converter (which maps a string to the final type). Invalid labels are treated no differently
   * (they don't trigger special errors here) because the type converter will also handle that.
   *
   * @param expectedValue the raw value the config_setting expects
   * @param flagTarget the target of the flag whose value is being checked
   * @param ruleContext this rule's RuleContext
   */
  private static String maybeCanonicalizeLabel(
      String expectedValue, TransitiveInfoCollection flagTarget, RuleContext ruleContext) {
    if (!flagTarget.satisfies(BuildSettingProvider.REQUIRE_BUILD_SETTING_PROVIDER)) {
      return expectedValue;
    }
    if (!BuildType.isLabelType(flagTarget.getProvider(BuildSettingProvider.class).getType())) {
      return expectedValue;
    }
    try {
      return Label.parseWithPackageContext(expectedValue, ruleContext.getPackageContext())
          .getUnambiguousCanonicalForm();
    } catch (LabelSyntaxException e) {
      // Swallow this: the subsequent type conversion already checks for this.
      return expectedValue;
    }
  }
}
