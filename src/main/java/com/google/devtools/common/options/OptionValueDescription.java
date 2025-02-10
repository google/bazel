// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.common.options;

import static java.util.Map.Entry.comparingByKey;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ListMultimap;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionsParser.ConstructionException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * The value of an option.
 *
 * <p>This takes care of tracking the final value as multiple instances of an option are parsed.
 */
public abstract class OptionValueDescription {

  protected final OptionDefinition optionDefinition;
  @Nullable protected final Object conversionContext;

  public OptionValueDescription(
      OptionDefinition optionDefinition, @Nullable Object conversionContext) {
    this.optionDefinition = optionDefinition;
    this.conversionContext = conversionContext;
  }

  public OptionDefinition getOptionDefinition() {
    return optionDefinition;
  }

  /** Returns the current or final value of this option. */
  public abstract Object getValue();

  public abstract boolean containsErrors();

  /** Returns the source(s) of this option, if there were multiple, duplicates are removed. */
  public abstract String getSourceString();

  /**
   * Add an instance of the option to this value. The various types of options are in charge of
   * making sure that the value is correctly stored, with proper tracking of its priority and
   * placement amongst other options.
   *
   * @return a bundle containing arguments that need to be parsed further.
   */
  abstract ExpansionBundle addOptionInstance(
      ParsedOptionDescription parsedOption, Set<String> warnings) throws OptionsParsingException;

  /**
   * Grouping of convenience for the options that expand to other options, to attach an
   * option-appropriate source string along with the options that need to be parsed.
   */
  public static class ExpansionBundle {
    List<String> expansionArgs;
    String sourceOfExpansionArgs;

    public ExpansionBundle(List<String> args, String source) {
      expansionArgs = args;
      sourceOfExpansionArgs = source;
    }
  }

  /**
   * Returns the canonical instances of this option - the instances that affect the current value.
   *
   * <p>For options that do not have values in their own right, this should be the empty list. In
   * contrast, the DefaultOptionValue does not have a canonical form at all, since it was never set,
   * and is null.
   */
  @Nullable
  public abstract List<ParsedOptionDescription> getCanonicalInstances();

  /**
   * For the given option, returns the correct type of OptionValueDescription, to which unparsed
   * values can be added.
   *
   * <p>The categories of option types are non-overlapping, an invariant checked by the
   * OptionProcessor at compile time.
   */
  public static OptionValueDescription createOptionValueDescription(
      OptionDefinition option, OptionsData optionsData, @Nullable Object conversionContext) {
    if (option.isExpansionOption()) {
      return new ExpansionOptionValueDescription(option, optionsData, conversionContext);
    } else if (option.allowsMultiple()) {
      return new RepeatableOptionValueDescription(option, conversionContext);
    } else if (option.hasImplicitRequirements()) {
      return new OptionWithImplicitRequirementsValueDescription(option, conversionContext);
    } else {
      return new SingleOptionValueDescription(option, conversionContext);
    }
  }

  /**
   * For options that have not been set, this will return a correct OptionValueDescription for the
   * default value.
   */
  public static OptionValueDescription getDefaultOptionValue(
      OptionDefinition option, @Nullable Object conversionContext) {
    return new DefaultOptionValueDescription(option, conversionContext);
  }

  private static class DefaultOptionValueDescription extends OptionValueDescription {

    private DefaultOptionValueDescription(
        OptionDefinition optionDefinition, @Nullable Object conversionContext) {
      super(optionDefinition, conversionContext);
    }

    @Override
    public Object getValue() {
      return optionDefinition.getDefaultValue(conversionContext);
    }

    @Override
    public boolean containsErrors() {
      return false;
    }

    @Override
    public String getSourceString() {
      return null;
    }

    @Override
    ExpansionBundle addOptionInstance(ParsedOptionDescription parsedOption, Set<String> warnings) {
      throw new IllegalStateException(
          "Cannot add values to the default option value. Create a modifiable "
              + "OptionValueDescription using createOptionValueDescription() instead.");
    }

    @Override
    public ImmutableList<ParsedOptionDescription> getCanonicalInstances() {
      return null;
    }
  }

  /**
   * The form of a value for a default type of flag, one that does not accumulate multiple values
   * and has no expansion.
   */
  private static class SingleOptionValueDescription extends OptionValueDescription {
    private ParsedOptionDescription effectiveOptionInstance;
    private Object effectiveValue;
    private boolean containsErrors;

    private SingleOptionValueDescription(
        OptionDefinition optionDefinition, @Nullable Object conversionContext) {
      super(optionDefinition, conversionContext);
      if (optionDefinition.allowsMultiple()) {
        throw new ConstructionException("Can't have a single value for an allowMultiple option.");
      }
      if (optionDefinition.isExpansionOption()) {
        throw new ConstructionException("Can't have a single value for an expansion option.");
      }
      effectiveOptionInstance = null;
      effectiveValue = null;
      containsErrors = false;
    }

    @Override
    public Object getValue() {
      return effectiveValue;
    }

    @Override
    public boolean containsErrors() {
      return containsErrors;
    }

    @Override
    public String getSourceString() {
      return effectiveOptionInstance.getSource();
    }

    private Object convertValue(ParsedOptionDescription parsedOption)
        throws OptionsParsingException {
      try {
        return parsedOption.getConvertedValue();
      } catch (OptionsParsingException e) {
        containsErrors = true;
        throw e;
      }
    }

    // Warnings should not end with a '.' because the internal reporter adds one automatically.
    @Nullable
    @Override
    ExpansionBundle addOptionInstance(ParsedOptionDescription parsedOption, Set<String> warnings)
        throws OptionsParsingException {
      // This might be the first value, in that case, just store it!
      if (effectiveOptionInstance == null) {
        effectiveOptionInstance = parsedOption;
        effectiveValue = convertValue(parsedOption);
        return null;
      }

      // If there was another value, check whether the new one will override it, and if so,
      // log warnings describing the change.
      if (parsedOption.getPriority().compareTo(effectiveOptionInstance.getPriority()) >= 0) {
        // Identify the option that might have led to the current and new value of this option.
        ParsedOptionDescription implicitDependent = parsedOption.getImplicitDependent();
        ParsedOptionDescription expandedFrom = parsedOption.getExpandedFrom();
        ParsedOptionDescription optionThatDependsOnEffectiveValue =
            effectiveOptionInstance.getImplicitDependent();
        ParsedOptionDescription optionThatExpandedToEffectiveValue =
            effectiveOptionInstance.getExpandedFrom();

        Object newValue = convertValue(parsedOption);
        // Output warnings if there is conflicting options set different values in a way that might
        // not have been obvious to the user, such as through expansions and implicit requirements.
        if (effectiveValue != null && !effectiveValue.equals(newValue)) {
          boolean samePriorityCategory =
              parsedOption
                  .getPriority()
                  .getPriorityCategory()
                  .equals(effectiveOptionInstance.getPriority().getPriorityCategory());
          if ((implicitDependent != null) && (optionThatDependsOnEffectiveValue != null)) {
            if (!implicitDependent.equals(optionThatDependsOnEffectiveValue)) {
              warnings.add(
                  String.format(
                      "%s is implicitly defined by both %s and %s",
                      optionDefinition, optionThatDependsOnEffectiveValue, implicitDependent));
            }
          } else if ((implicitDependent != null) && samePriorityCategory) {
            warnings.add(
                String.format(
                    "%s is implicitly defined by %s; the implicitly set value "
                        + "overrides the previous one",
                    optionDefinition, implicitDependent));
          } else if (optionThatDependsOnEffectiveValue != null) {
            warnings.add(
                String.format(
                    "A new value for %s overrides a previous implicit setting of that "
                        + "option by %s",
                    optionDefinition, optionThatDependsOnEffectiveValue));
          } else if (samePriorityCategory
              && parsedOption
                  .getPriority()
                  .getPriorityCategory()
                  .equals(PriorityCategory.COMMAND_LINE)
              && ((optionThatExpandedToEffectiveValue == null) && (expandedFrom != null))) {
            // Create a warning if an expansion option overrides an explicit option:
            warnings.add(
                String.format(
                    "%s was expanded and now overrides the explicit option %s with %s",
                    expandedFrom,
                    effectiveOptionInstance.getCommandLineForm(),
                    parsedOption.getCommandLineForm()));
          } else if ((optionThatExpandedToEffectiveValue != null)
              && (expandedFrom != null)
              && !(samePriorityCategory
                  && parsedOption
                      .getPriority()
                      .getPriorityCategory()
                      .equals(PriorityCategory.RC_FILE))) {
            warnings.add(
                String.format(
                    "%s was expanded from both %s and %s",
                    optionDefinition, optionThatExpandedToEffectiveValue, expandedFrom));
          }
        }

        // Record the new value:
        effectiveOptionInstance = parsedOption;
        effectiveValue = newValue;
      }
      return null;
    }

    @Override
    public ImmutableList<ParsedOptionDescription> getCanonicalInstances() {
      // If the current option is an implicit requirement, we don't need to list this value since
      // the parent implies it. In this case, it is sufficient to not list this value at all.
      if (effectiveOptionInstance.getImplicitDependent() == null) {
        return ImmutableList.of(effectiveOptionInstance);
      }
      return ImmutableList.of();
    }
  }

  /** The form of a value for an option that accumulates multiple values on the command line. */
  private static class RepeatableOptionValueDescription extends OptionValueDescription {
    private final ListMultimap<OptionPriority, ParsedOptionDescription> parsedOptions;
    private final ListMultimap<OptionPriority, Object> optionValues;
    private boolean containsErrors;

    private RepeatableOptionValueDescription(
        OptionDefinition optionDefinition, @Nullable Object conversionContext) {
      super(optionDefinition, conversionContext);
      if (!optionDefinition.allowsMultiple()) {
        throw new ConstructionException(
            "Can't have a repeated value for a non-allowMultiple option.");
      }
      parsedOptions = ArrayListMultimap.create();
      optionValues = ArrayListMultimap.create();
      containsErrors = false;
    }

    @Override
    public String getSourceString() {
      return parsedOptions.asMap().entrySet().stream()
          .sorted(comparingByKey())
          .map(Map.Entry::getValue)
          .flatMap(Collection::stream)
          .map(ParsedOptionDescription::getSource)
          .distinct()
          .collect(Collectors.joining(", "));
    }

    @Override
    public ImmutableList<Object> getValue() {
      // Sort the results by option priority and return them in a new list. The generic type of
      // the list is not known at runtime, so we can't use it here.
      return optionValues.asMap().entrySet().stream()
          .sorted(comparingByKey())
          .map(Map.Entry::getValue)
          .flatMap(Collection::stream)
          .collect(ImmutableList.toImmutableList());
    }

    @Override
    public boolean containsErrors() {
      return containsErrors;
    }

    @Nullable
    @Override
    ExpansionBundle addOptionInstance(ParsedOptionDescription parsedOption, Set<String> warnings)
        throws OptionsParsingException {
      // For repeatable options, we allow flags that take both single values and multiple values,
      // potentially collapsing them down.
      Object convertedValue;

      try {
        convertedValue = parsedOption.getConvertedValue();
      } catch (OptionsParsingException e) {
        containsErrors = true;
        throw e;
      }

      OptionPriority priority = parsedOption.getPriority();
      parsedOptions.put(priority, parsedOption);
      if (convertedValue instanceof List<?>) {
        optionValues.putAll(priority, (List<?>) convertedValue);
      } else {
        optionValues.put(priority, convertedValue);
      }
      return null;
    }

    @Override
    public ImmutableList<ParsedOptionDescription> getCanonicalInstances() {
      return parsedOptions.asMap().entrySet().stream()
          .sorted(comparingByKey())
          .map(Map.Entry::getValue)
          .flatMap(Collection::stream)
          // Only provide the options that aren't implied elsewhere.
          .filter(optionDesc -> optionDesc.getImplicitDependent() == null)
          .collect(ImmutableList.toImmutableList());
    }
  }

  /**
   * The form of a value for an expansion option, one that does not have its own value but expands
   * in place to other options. This should be used for flags with anN expansion defined in {@link
   * Option#expansion()}.
   */
  private static class ExpansionOptionValueDescription extends OptionValueDescription {
    private final List<String> expansion;

    private ExpansionOptionValueDescription(
        OptionDefinition optionDefinition,
        OptionsData optionsData,
        @Nullable Object conversionContext) {
      super(optionDefinition, conversionContext);
      this.expansion = optionsData.getEvaluatedExpansion(optionDefinition);
      if (!optionDefinition.isExpansionOption()) {
        throw new ConstructionException(
            "Options without expansions can't be tracked using ExpansionOptionValueDescription");
      }
    }

    @Override
    public Object getValue() {
      return null;
    }

    @Override
    public boolean containsErrors() {
      return false;
    }

    @Override
    public String getSourceString() {
      return null;
    }

    @Override
    ExpansionBundle addOptionInstance(ParsedOptionDescription parsedOption, Set<String> warnings) {
      if (parsedOption.getUnconvertedValue() != null
          && !parsedOption.getUnconvertedValue().isEmpty()) {
        warnings.add(
            String.format(
                "%s is an expansion option. It does not accept values, and does not change its "
                    + "expansion based on the value provided. Value '%s' will be ignored.",
                optionDefinition, parsedOption.getUnconvertedValue()));
      }

      return new ExpansionBundle(
          expansion,
          (parsedOption.getSource() == null)
              ? String.format("expanded from %s", optionDefinition)
              : String.format(
                  "expanded from %s (source %s)", optionDefinition, parsedOption.getSource()));
    }

    @Override
    public ImmutableList<ParsedOptionDescription> getCanonicalInstances() {
      // The options this expands to are incorporated in their own right - this option does
      // not have a canonical form.
      return ImmutableList.of();
    }
  }

  /** The form of a value for a flag with implicit requirements. */
  private static class OptionWithImplicitRequirementsValueDescription
      extends SingleOptionValueDescription {

    private OptionWithImplicitRequirementsValueDescription(
        OptionDefinition optionDefinition, @Nullable Object conversionContext) {
      super(optionDefinition, conversionContext);
      if (!optionDefinition.hasImplicitRequirements()) {
        throw new ConstructionException(
            "Options without implicit requirements can't be tracked using "
                + "OptionWithImplicitRequirementsValueDescription");
      }
    }

    @Override
    ExpansionBundle addOptionInstance(ParsedOptionDescription parsedOption, Set<String> warnings)
        throws OptionsParsingException {
      // This is a valued flag, its value is handled the same way as a normal
      // SingleOptionValueDescription. (We check at compile time that these flags aren't
      // "allowMultiple")
      ExpansionBundle superExpansion = super.addOptionInstance(parsedOption, warnings);
      Preconditions.checkArgument(
          superExpansion == null, "SingleOptionValueDescription should not expand to anything.");
      if (parsedOption
          .getConvertedValue()
          .equals(optionDefinition.getDefaultValue(conversionContext))) {
        warnings.add(
            String.format(
                "%s sets %s to its default value. Since this option has implicit requirements that "
                    + "are set whenever the option is explicitly provided, regardless of the "
                    + "value, this will behave differently than letting a default be a default. "
                    + "Specifically, this options expands to {%s}.",
                parsedOption.getCommandLineForm(),
                optionDefinition,
                String.join(" ", optionDefinition.getImplicitRequirements())));
      }

      // Now deal with the implicit requirements.
      return new ExpansionBundle(
          ImmutableList.copyOf(optionDefinition.getImplicitRequirements()),
          (parsedOption.getSource() == null)
              ? String.format("implicit requirement of %s", optionDefinition)
              : String.format(
                  "implicit requirement of %s (source %s)",
                  optionDefinition, parsedOption.getSource()));
    }
  }
}
