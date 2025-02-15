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

package com.google.devtools.common.options;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.common.options.OptionPriority.PriorityCategory.INVOCATION_POLICY;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toCollection;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.devtools.common.options.OptionPriority.PriorityCategory;
import com.google.devtools.common.options.OptionValueDescription.ExpansionBundle;
import com.google.devtools.common.options.OptionsParser.ArgAndFallbackData;
import com.google.devtools.common.options.OptionsParser.OptionDescription;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Keep;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.starlark.java.spelling.SpellChecker;

/**
 * The implementation of the options parser. This is intentionally package private for full
 * flexibility. Use {@link OptionsParser} or {@link Options} if you're a consumer.
 */
class OptionsParserImpl {

  /** Helper class to create a new instance of {@link OptionsParserImpl}. */
  static final class Builder {
    private OptionsData optionsData;
    private ArgsPreProcessor argsPreProcessor = args -> args;
    private final ArrayList<String> skippedPrefixes = new ArrayList<>();
    private boolean ignoreInternalOptions = true;
    @Nullable private String aliasFlag = null;
    @Nullable private Object conversionContext = null;
    private final Map<String, String> aliases = new HashMap<>();

    /** Set the {@link OptionsData} to be used in this instance. */
    @CanIgnoreReturnValue
    public Builder optionsData(OptionsData optionsData) {
      this.optionsData = optionsData;
      return this;
    }

    /** Sets the {@link ArgsPreProcessor} to use during processing. */
    @CanIgnoreReturnValue
    public Builder argsPreProcessor(ArgsPreProcessor preProcessor) {
      this.argsPreProcessor = preProcessor;
      return this;
    }

    /** Any flags with this prefix will be skipped during processing. */
    @CanIgnoreReturnValue
    public Builder skippedPrefix(String skippedPrefix) {
      this.skippedPrefixes.add(skippedPrefix);
      return this;
    }

    /** Sets whether the parser should ignore internal-only options. */
    @CanIgnoreReturnValue
    public Builder ignoreInternalOptions(boolean ignoreInternalOptions) {
      this.ignoreInternalOptions = ignoreInternalOptions;
      return this;
    }

    /**
     * Sets what flag the parser should use for flag aliasing. Defaults to null if not set,
     * effectively disabling the aliasing functionality.
     */
    @CanIgnoreReturnValue
    public Builder withAliasFlag(@Nullable String aliasFlag) {
      if (aliasFlag != null) {
        this.aliasFlag = "--" + aliasFlag;
      }
      return this;
    }

    public Builder withConversionContext(@Nullable Object conversionContext) {
      this.conversionContext = conversionContext;
      return this;
    }

    /**
     * Adds a map of flag aliases where the keys are the flags' alias names and the values are their
     * actual names.
     */
    @CanIgnoreReturnValue
    public Builder withAliases(Map<String, String> aliases) {
      this.aliases.putAll(aliases);
      return this;
    }

    /** Returns a newly-initialized {@link OptionsParserImpl}. */
    public OptionsParserImpl build() {
      return new OptionsParserImpl(
          this.optionsData,
          this.argsPreProcessor,
          this.skippedPrefixes,
          this.ignoreInternalOptions,
          this.aliasFlag,
          this.conversionContext,
          this.aliases);
    }
  }

  /** Returns a new {@link Builder} with correct defaults applied. */
  public static Builder builder() {
    return new Builder();
  }

  private final OptionsData optionsData;

  /**
   * We store the results of option parsing in here - since there can only be one value per option
   * field, this is where the different instances of an option have been combined and the final
   * value is tracked. It'll look like
   *
   * <pre>
   *   OptionDefinition("--host") -> "www.google.com"
   *   OptionDefinition("--port") -> 80
   * </pre>
   *
   * This map is modified by repeated calls to {@link #parse(OptionPriority.PriorityCategory,
   * Function,List)}.
   */
  private final Map<OptionDefinition, OptionValueDescription> optionValues = new HashMap<>();

  /**
   * Since parse() expects multiple calls to it with the same {@link PriorityCategory} to be treated
   * as though the args in the later call have higher priority over the earlier calls, we need to
   * track the high water mark of option priority at each category. Each call to parse will start at
   * this level.
   */
  private final Map<PriorityCategory, OptionPriority> nextPriorityPerPriorityCategory =
      Arrays.stream(PriorityCategory.values())
          .collect(Collectors.toMap(p -> p, OptionPriority::lowestOptionPriorityAtCategory));

  /**
   * Explicit option tracking, tracking each option as it was provided, after they have been parsed.
   *
   * <p>The value is unconverted, still the string as it was read from the input, or partially
   * altered in cases where the flag was set by non {@code --flag=value} forms; e.g. {@code --nofoo}
   * becomes {@code --foo=0}.
   */
  private final List<ParsedOptionDescription> parsedOptions = new ArrayList<>();

  private final List<ParsedOptionDescription> skippedOptions = new ArrayList<>();

  private final Map<String, String> flagAliasMappings;
  private final Set<String> permittedButIgnoredFlagAliases = new HashSet<>();
  // We want to keep the invariant that warnings are produced as they are encountered, but only
  // show each one once.
  private final Set<String> warnings = new LinkedHashSet<>();
  private final ArgsPreProcessor argsPreProcessor;
  private final List<String> skippedPrefixes;
  private final boolean ignoreInternalOptions;
  @Nullable private final String aliasFlag;
  @Nullable private final Object conversionContext;

  /**
   * This option is used to collect skipped arguments while preserving the relative ordering between
   * those given explicitly on the command line and those expanded by {@code ConfigExpander}. The
   * field itself is not used for any purpose other than retrieving its {@link Option} annotation.
   */
  @Keep
  @Option(
      name = "skipped args",
      allowMultiple = true,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.NO_OP},
      help = "Only used internally by OptionsParserImpl")
  private final List<String> skippedArgs = new ArrayList<>();

  private static final OptionDefinition skippedArgsDefinition;

  static {
    try {
      skippedArgsDefinition =
          FieldOptionDefinition.extractOptionDefinition(
              OptionsParserImpl.class.getDeclaredField("skippedArgs"));
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }

  OptionsParserImpl(
      OptionsData optionsData,
      ArgsPreProcessor argsPreProcessor,
      List<String> skippedPrefixes,
      boolean ignoreInternalOptions,
      @Nullable String aliasFlag,
      @Nullable Object conversionContext,
      Map<String, String> aliases) {
    this.optionsData = optionsData;
    this.argsPreProcessor = argsPreProcessor;
    this.skippedPrefixes = skippedPrefixes;
    this.ignoreInternalOptions = ignoreInternalOptions;
    this.aliasFlag = aliasFlag;
    this.conversionContext = conversionContext;
    this.flagAliasMappings = aliases;
  }

  /** Returns the {@link OptionsData} used in this instance. */
  OptionsData getOptionsData() {
    return optionsData;
  }

  @Nullable
  public Object getConversionContext() {
    return conversionContext;
  }

  /** Returns a {@link Builder} that is configured the same as this parser. */
  Builder toBuilder() {
    Builder builder =
        builder()
            .optionsData(optionsData)
            .argsPreProcessor(argsPreProcessor)
            .withAliasFlag(aliasFlag)
            .withAliases(flagAliasMappings)
            .withConversionContext(conversionContext)
            .ignoreInternalOptions(ignoreInternalOptions);
    for (String skippedPrefix : skippedPrefixes) {
      builder.skippedPrefix(skippedPrefix);
    }
    return builder;
  }

  /** Implements {@link OptionsParser#asCompleteListOfParsedOptions()}. */
  List<ParsedOptionDescription> asCompleteListOfParsedOptions() {
    return parsedOptions.stream()
        // It is vital that this sort is stable so that options on the same priority are not
        // reordered.
        .sorted(comparing(ParsedOptionDescription::getPriority))
        .collect(toCollection(ArrayList::new));
  }

  /** Implements {@link OptionsParser#asListOfExplicitOptions()}. */
  List<ParsedOptionDescription> asListOfExplicitOptions() {
    return parsedOptions.stream()
        .filter(ParsedOptionDescription::isExplicit)
        // It is vital that this sort is stable so that options on the same priority are not
        // reordered.
        .sorted(comparing(ParsedOptionDescription::getPriority))
        .collect(toCollection(ArrayList::new));
  }

  List<ParsedOptionDescription> getSkippedOptions() {
    return skippedOptions;
  }

  /** Implements {@link OptionsParser#canonicalize}. */
  List<String> asCanonicalizedList() {
    return asCanonicalizedListOfParsedOptions().stream()
        .map(ParsedOptionDescription::getDeprecatedCanonicalForm)
        .collect(toImmutableList());
  }

  /** Implements {@link OptionsParser#canonicalize}. */
  List<ParsedOptionDescription> asCanonicalizedListOfParsedOptions() {
    return optionValues.keySet().stream()
        .filter(k -> !Objects.equals(k, skippedArgsDefinition))
        .map(optionDefinition -> optionValues.get(optionDefinition).getCanonicalInstances())
        .flatMap(Collection::stream)
        // Return the effective (canonical) options in the order they were applied.
        .sorted(comparing(ParsedOptionDescription::getPriority))
        .collect(toImmutableList());
  }

  /** Implements {@link OptionsParser#asListOfOptionValues()}. */
  List<OptionValueDescription> asListOfEffectiveOptions() {
    List<OptionValueDescription> result = new ArrayList<>();
    for (Map.Entry<String, OptionDefinition> mapEntry : optionsData.getAllOptionDefinitions()) {
      OptionDefinition optionDefinition = mapEntry.getValue();
      OptionValueDescription optionValue = optionValues.get(optionDefinition);
      if (optionValue == null) {
        result.add(
            OptionValueDescription.getDefaultOptionValue(optionDefinition, conversionContext));
      } else {
        result.add(optionValue);
      }
    }
    return result;
  }

  List<OptionValueDescription> allOptionValues() {
    return optionsData.getAllOptionDefinitions().stream()
        .map(Map.Entry::getValue)
        .map(optionValues::get)
        .filter(optionValue -> optionValue != null)
        .collect(toImmutableList());
  }

  private void maybeAddDeprecationWarning(
      OptionDefinition optionDefinition, PriorityCategory priority) {
    // Don't add a warning for deprecated flag set by the invocation policy.
    if (priority.equals(INVOCATION_POLICY)) {
      return;
    }
    // Continue to support the old behavior for @Deprecated options.
    String warning = optionDefinition.getDeprecationWarning();
    if (!warning.isEmpty() || optionDefinition.isDeprecated()) {
      addDeprecationWarning(optionDefinition.getOptionName(), warning);
    }
  }

  private void maybeAddOldNameWarning(ParsedOptionDescription parsedOption) {
    // Don't add a warning for old name options set by the invocation policy.
    if (parsedOption.getPriority().getPriorityCategory().equals(INVOCATION_POLICY)) {
      return;
    }
    OptionDefinition optionDefinition = parsedOption.getOptionDefinition();
    if (!optionDefinition.getOldNameWarning()) {
      return;
    }
    String oldOptionName = optionDefinition.getOldOptionName();
    String optionName = optionDefinition.getOptionName();
    if (parsedOption.isOldNameUsed()) {
      addDeprecationWarning(oldOptionName, String.format("Use --%s instead", optionName));
    }
  }

  private void addDeprecationWarning(String optionName, String warning) {
    warnings.add(
        String.format(
            "Option '%s' is deprecated%s", optionName, (warning.isEmpty() ? "" : ": " + warning)));
  }

  OptionValueDescription clearValue(OptionDefinition optionDefinition)
      throws OptionsParsingException {
    return optionValues.remove(optionDefinition);
  }

  OptionValueDescription getOptionValueDescription(String name) {
    OptionDefinition optionDefinition = optionsData.getOptionDefinitionFromName(name);
    if (optionDefinition == null) {
      throw new IllegalArgumentException("No such option '" + name + "'");
    }
    return optionValues.get(optionDefinition);
  }

  @Nullable
  OptionDescription getOptionDescription(String name) throws OptionsParsingException {
    OptionDefinition optionDefinition = optionsData.getOptionDefinitionFromName(name);
    if (optionDefinition == null) {
      return null;
    }
    return new OptionDescription(optionDefinition, optionsData);
  }

  /**
   * Implementation of {@link OptionsParser#getExpansionValueDescriptions(OptionDefinition,
   * OptionInstanceOrigin)}
   */
  ImmutableList<ParsedOptionDescription> getExpansionValueDescriptions(
      OptionDefinition expansionFlagDef, OptionInstanceOrigin originOfExpansionFlag)
      throws OptionsParsingException {
    ImmutableList.Builder<ParsedOptionDescription> builder = ImmutableList.builder();

    // Values needed to correctly track the origin of the expanded options.
    OptionPriority nextOptionPriority =
        OptionPriority.getChildPriority(originOfExpansionFlag.getPriority());
    String source;
    ParsedOptionDescription implicitDependent = null;
    ParsedOptionDescription expandedFrom = null;

    ImmutableList<String> options;
    ParsedOptionDescription expansionFlagParsedDummy =
        ParsedOptionDescription.newDummyInstance(
            expansionFlagDef, originOfExpansionFlag, conversionContext);
    if (expansionFlagDef.hasImplicitRequirements()) {
      options = ImmutableList.copyOf(expansionFlagDef.getImplicitRequirements());
      source =
          String.format(
              "implicitly required by %s (source: %s)",
              expansionFlagDef, originOfExpansionFlag.getSource());
      implicitDependent = expansionFlagParsedDummy;
    } else if (expansionFlagDef.isExpansionOption()) {
      options = optionsData.getEvaluatedExpansion(expansionFlagDef);
      source =
          String.format(
              "expanded by %s (source: %s)", expansionFlagDef, originOfExpansionFlag.getSource());
      expandedFrom = expansionFlagParsedDummy;
    } else {
      return ImmutableList.of();
    }

    Iterator<String> optionsIterator = options.iterator();
    while (optionsIterator.hasNext()) {
      String unparsedFlagExpression = optionsIterator.next();
      identifyOptionAndPossibleArgument(
              unparsedFlagExpression,
              optionsIterator,
              nextOptionPriority,
              o -> source,
              implicitDependent,
              expandedFrom,
              /* fallbackData= */ null)
          .parsedOptionDescription
          .ifPresent(builder::add);
      nextOptionPriority = OptionPriority.nextOptionPriority(nextOptionPriority);
    }
    return builder.build();
  }

  boolean containsExplicitOption(String name) {
    OptionDefinition optionDefinition = optionsData.getOptionDefinitionFromName(name);
    if (optionDefinition == null) {
      throw new IllegalArgumentException("No such option '" + name + "'");
    }
    return optionValues.get(optionDefinition) != null;
  }

  @SuppressWarnings("unchecked")
  List<String> getSkippedArgs() {
    OptionValueDescription value = optionValues.get(skippedArgsDefinition);
    if (value == null) {
      return ImmutableList.of();
    }
    return (List<String>) value.getValue();
  }

  /**
   * Parses the args, and returns what it doesn't parse. May be called multiple times, and may be
   * called recursively. The option's definition dictates how it reacts to multiple settings. By
   * default, the arg seen last at the highest priority takes precedence, overriding the early
   * values. Options that accumulate multiple values will track them in priority and appearance
   * order.
   */
  OptionsParserImplResult parse(
      PriorityCategory priorityCat,
      Function<OptionDefinition, String> sourceFunction,
      List<ArgAndFallbackData> args)
      throws OptionsParsingException {
    OptionsParserImplResult optionsParserImplResult =
        parse(nextPriorityPerPriorityCategory.get(priorityCat), sourceFunction, null, null, args);
    nextPriorityPerPriorityCategory.put(priorityCat, optionsParserImplResult.nextPriority);
    return optionsParserImplResult;
  }

  /**
   * Parses the args, and returns what it doesn't parse. May be called multiple times, and may be
   * called recursively. Calls may contain intersecting sets of options; in that case, the arg seen
   * last takes precedence.
   *
   * <p>The method treats options that have neither an implicitDependent nor an expandedFrom value
   * as explicitly set.
   */
  private OptionsParserImplResult parse(
      OptionPriority priority,
      Function<OptionDefinition, String> sourceFunction,
      ParsedOptionDescription implicitDependent,
      ParsedOptionDescription expandedFrom,
      List<ArgAndFallbackData> args)
      throws OptionsParsingException {
    List<String> unparsedArgs = new ArrayList<>();
    List<String> unparsedPostDoubleDashArgs = new ArrayList<>();
    List<String> ignoredArgs = new ArrayList<>();

    Iterator<ArgAndFallbackData> argsAndFallbackDataIterator =
        argsPreProcessor.preProcess(args).iterator();
    Iterator<String> argsIterator = Iterators.transform(argsAndFallbackDataIterator, a -> a.arg);
    while (argsAndFallbackDataIterator.hasNext()) {
      ArgAndFallbackData argAndFallbackData = argsAndFallbackDataIterator.next();
      String arg = argAndFallbackData.arg;
      @Nullable OptionsData fallbackData = argAndFallbackData.fallbackData;

      if (!arg.startsWith("-")) {
        unparsedArgs.add(arg);
        continue; // not an option arg
      }

      if (arg.startsWith("-//") || arg.startsWith("-@")) {
        // Fail with a helpful error when an invalid option looks like an absolute negative target
        // pattern or a typoed Starlark option.
        throw new OptionsParsingException(
            String.format(
                "Invalid options syntax: %s\n"
                    + "Note: Negative target patterns can only appear after the end of options"
                    + " marker ('--'). Flags corresponding to Starlark-defined build settings"
                    + " always start with '--', not '-'.",
                arg));
      }

      arg = swapShorthandAlias(arg);

      if (arg.equals("--")) { // "--" means all remaining args aren't options
        Iterators.addAll(unparsedPostDoubleDashArgs, argsIterator);
        break;
      }

      Optional<ParsedOptionDescription> parsedOption;
      if (containsSkippedPrefix(arg)) {
        // Parse the skipped arg into a synthetic allowMultiple option to preserve its order
        // relative to skipped args coming from expansions. Simply adding it to the residue would
        // end up placing expanded skipped args after all explicitly given skipped args, which isn't
        // correct.
        parsedOption =
            Optional.of(
                ParsedOptionDescription.newParsedOptionDescription(
                    skippedArgsDefinition,
                    arg,
                    arg,
                    new OptionInstanceOrigin(
                        priority,
                        sourceFunction.apply(skippedArgsDefinition),
                        implicitDependent,
                        expandedFrom),
                    conversionContext));
      } else {
        ParsedOptionDescriptionOrIgnoredArgs result =
            identifyOptionAndPossibleArgument(
                arg,
                argsIterator,
                priority,
                sourceFunction,
                implicitDependent,
                expandedFrom,
                fallbackData);
        result.ignoredArgs.ifPresent(ignoredArgs::add);
        parsedOption = result.parsedOptionDescription;
      }
      if (parsedOption.isPresent()) {
        handleNewParsedOption(parsedOption.get(), fallbackData);
      }
      priority = OptionPriority.nextOptionPriority(priority);
    }

    // Go through the final values and make sure they are valid values for their option. Unlike any
    // checks that happened above, this also checks that flags that were not set have a valid
    // default value. getValue() will throw if the value is invalid.
    for (OptionValueDescription valueDescription : asListOfEffectiveOptions()) {
      valueDescription.getValue();
    }

    return new OptionsParserImplResult(
        unparsedArgs, unparsedPostDoubleDashArgs, ignoredArgs, priority, flagAliasMappings);
  }

  /** A class that stores residue and priority information. */
  static final class OptionsParserImplResult {
    final List<String> postDoubleDashResidue;
    final List<String> preDoubleDashResidue;
    final ImmutableList<String> ignoredArgs;
    final OptionPriority nextPriority;
    final ImmutableMap<String, String> aliases;

    OptionsParserImplResult(
        List<String> preDashResidue,
        List<String> postDashResidue,
        List<String> ignoredArgs,
        OptionPriority nextPriority,
        Map<String, String> aliases) {
      this.preDoubleDashResidue = preDashResidue;
      this.postDoubleDashResidue = postDashResidue;
      this.ignoredArgs = ImmutableList.copyOf(ignoredArgs);
      this.nextPriority = nextPriority;
      this.aliases = ImmutableMap.copyOf(aliases);
    }

    public List<String> getResidue() {
      List<String> toReturn =
          new ArrayList<>(preDoubleDashResidue.size() + postDoubleDashResidue.size());
      toReturn.addAll(preDoubleDashResidue);
      toReturn.addAll(postDoubleDashResidue);
      return toReturn;
    }
  }

  /** Implements {@link OptionsParser#parseArgsAsExpansionOfOption} */
  OptionsParserImplResult parseArgsAsExpansionOfOption(
      ParsedOptionDescription optionToExpand,
      Function<OptionDefinition, String> sourceFunction,
      List<ArgAndFallbackData> args)
      throws OptionsParsingException {
    return parse(
        OptionPriority.getChildPriority(optionToExpand.getPriority()),
        sourceFunction,
        null,
        optionToExpand,
        args);
  }

  /**
   * Implementation of {@link
   * OptionsParser#setOptionValueAtSpecificPriorityWithoutExpansion(OptionInstanceOrigin,
   * OptionDefinition, String)}
   */
  void setOptionValueAtSpecificPriorityWithoutExpansion(
      OptionInstanceOrigin origin, OptionDefinition option, String unconvertedValue)
      throws OptionsParsingException {
    Preconditions.checkNotNull(option);
    Preconditions.checkNotNull(
        unconvertedValue,
        "Cannot set %s to a null value. Pass \"\" if an empty value is required.",
        option);
    Preconditions.checkNotNull(
        origin,
        "Cannot assign value '%s' to %s without a clear origin for this value.",
        unconvertedValue,
        option);
    PriorityCategory priorityCategory = origin.getPriority().getPriorityCategory();
    boolean isNotDefault = priorityCategory != OptionPriority.PriorityCategory.DEFAULT;
    Preconditions.checkArgument(
        isNotDefault,
        "Attempt to assign value '%s' to %s at priority %s failed. Cannot set options at "
            + "default priority - by definition, that means the option is unset.",
        unconvertedValue,
        option,
        priorityCategory);

    setOptionValue(
        ParsedOptionDescription.newParsedOptionDescription(
            option,
            String.format("--%s=%s", option.getOptionName(), unconvertedValue),
            unconvertedValue,
            origin,
            conversionContext));
  }

  /** Takes care of tracking the parsed option's value in relation to other options. */
  private void handleNewParsedOption(
      ParsedOptionDescription parsedOption, @Nullable OptionsData fallbackData)
      throws OptionsParsingException {
    OptionDefinition optionDefinition = parsedOption.getOptionDefinition();
    ExpansionBundle expansionBundle = setOptionValue(parsedOption);
    @Nullable String unconvertedValue = parsedOption.getUnconvertedValue();

    if (expansionBundle != null) {
      OptionsParserImplResult optionsParserImplResult =
          parse(
              OptionPriority.getChildPriority(parsedOption.getPriority()),
              o -> expansionBundle.sourceOfExpansionArgs,
              optionDefinition.hasImplicitRequirements() ? parsedOption : null,
              optionDefinition.isExpansionOption() ? parsedOption : null,
              ArgAndFallbackData.wrapWithFallbackData(expansionBundle.expansionArgs, fallbackData));
      if (!optionsParserImplResult.getResidue().isEmpty()) {

        // Throw an assertion here, because this indicates an error in the definition of this
        // option's expansion or requirements, not with the input as provided by the user.
        throw new AssertionError(
            "Unparsed options remain after processing "
                + unconvertedValue
                + ": "
                + Joiner.on(' ').join(optionsParserImplResult.getResidue()));
      }
    }
  }

  private ExpansionBundle setOptionValue(ParsedOptionDescription parsedOption)
      throws OptionsParsingException {
    OptionDefinition optionDefinition = parsedOption.getOptionDefinition();
    // All options can be deprecated; check and warn before doing any option-type specific work.
    maybeAddDeprecationWarning(optionDefinition, parsedOption.getPriority().getPriorityCategory());
    // Check if the old option name is used and add a warning
    maybeAddOldNameWarning(parsedOption);
    // Track the value, before any remaining option-type specific work that is done outside of
    // the OptionValueDescription.
    OptionValueDescription entry =
        optionValues.computeIfAbsent(
            optionDefinition,
            def ->
                OptionValueDescription.createOptionValueDescription(
                    def, optionsData, conversionContext));
    ExpansionBundle expansionBundle = entry.addOptionInstance(parsedOption, warnings);

    // There are 3 types of flags that expand to other flag values. Expansion flags are the
    // accepted way to do this, but implicit requirements also do this. We rely on the
    // OptionProcessor compile-time check's guarantee that no option sets
    // both expansion behaviors. (In Bazel, --config is another such flag, but that expansion
    // is not controlled within the options parser, so we ignore it here)

    // As much as possible, we want the behaviors of these different types of flags to be
    // identical, as this minimizes the number of edge cases, but we do not yet track these values
    // in the same way.
    if (parsedOption.getImplicitDependent() == null) {
      if (Objects.equals(parsedOption.getOptionDefinition(), skippedArgsDefinition)) {
        // This may be a Starlark option. Don't parse it here (save it for StarlarkOptionsParser)
        // but keep the context so we can track if the option was explicitly set or not for BEP
        // reporting.
        skippedOptions.add(parsedOption);
      } else {
        // Log explicit options and expanded options in the order they are parsed (can be sorted
        // later). This information is needed to correctly canonicalize flags.
        parsedOptions.add(parsedOption);
      }

      if (aliasFlag != null && parsedOption.getCommandLineForm().startsWith(aliasFlag)) {
        List<String> alias =
            Splitter.on('=').limit(2).splitToList(parsedOption.getUnconvertedValue());

        flagAliasMappings.put(alias.get(0), alias.get(1));
      }
    }

    return expansionBundle;
  }

  /**
   * Keep the properties of {@link OptionsData} used below in sync with {@link
   * #equivalentForParsing}.
   *
   * <p>If an option is not found in the current {@link OptionsData}, but is found in the specified
   * fallback data, a {@link ParsedOptionDescriptionOrIgnoredArgs} with no {@link
   * ParsedOptionDescription}, but the ignored arguments is returned.
   */
  private ParsedOptionDescriptionOrIgnoredArgs identifyOptionAndPossibleArgument(
      String arg,
      Iterator<String> nextArgs,
      OptionPriority priority,
      Function<OptionDefinition, String> sourceFunction,
      ParsedOptionDescription implicitDependent,
      ParsedOptionDescription expandedFrom,
      @Nullable OptionsData fallbackData)
      throws OptionsParsingException {

    // Store the way this option was parsed on the command line.
    StringBuilder commandLineForm = new StringBuilder();
    commandLineForm.append(arg);
    String unconvertedValue = null;
    OptionLookupResult lookupResult;
    boolean booleanValue = true;
    String parsedOptionName = "";

    if (arg.length() == 2) { // -l  (may be nullary or unary)
      lookupResult = getWithFallback(OptionsData::getFieldForAbbrev, arg.charAt(1), fallbackData);
      booleanValue = true;

    } else if (arg.length() == 3 && arg.charAt(2) == '-') { // -l-  (boolean)
      lookupResult = getWithFallback(OptionsData::getFieldForAbbrev, arg.charAt(1), fallbackData);
      booleanValue = false;

    } else if (arg.startsWith("--")) { // --long_option

      int equalsAt = arg.indexOf('=');
      int nameStartsAt = 2;
      String name =
          equalsAt == -1 ? arg.substring(nameStartsAt) : arg.substring(nameStartsAt, equalsAt);
      if (name.trim().isEmpty()) {
        throw new OptionsParsingException("Invalid options syntax: " + arg, arg);
      }
      unconvertedValue = equalsAt == -1 ? null : arg.substring(equalsAt + 1);
      lookupResult = getWithFallback(OptionsData::getOptionDefinitionFromName, name, fallbackData);

      // Look for a "no"-prefixed option name: "no<optionName>".
      if (lookupResult == null && name.startsWith("no")) {
        name = name.substring(2);
        lookupResult =
            getWithFallback(OptionsData::getOptionDefinitionFromName, name, fallbackData);
        booleanValue = false;
        if (lookupResult != null) {
          // TODO(bazel-team): Add tests for these cases.
          if (!lookupResult.definition.usesBooleanValueSyntax()) {
            throw new OptionsParsingException(
                "Illegal use of 'no' prefix on non-boolean option: " + arg, arg);
          }
          if (unconvertedValue != null) {
            throw new OptionsParsingException("Unexpected value after boolean option: " + arg, arg);
          }
          // "no<optionname>" signifies a boolean option w/ false value
          unconvertedValue = "0";
        }
      }
      parsedOptionName = name;
    } else {
      throw new OptionsParsingException("Invalid options syntax: " + arg, arg);
    }

    if (lookupResult == null && permittedButIgnoredFlagAliases.contains(parsedOptionName)) {
      // The name references a flag alias defined on an ignored command, ignore it as well.
      return new ParsedOptionDescriptionOrIgnoredArgs(
          Optional.empty(), Optional.of(commandLineForm.toString()));
    }

    // Do not recognize internal options, which are treated as if they did not exist.
    if (lookupResult == null || shouldIgnoreOption(lookupResult.definition)) {
      String suggestion;
      // Do not offer suggestions for short-form options.
      if (arg.startsWith("--")) {
        suggestion = SpellChecker.didYouMean(arg, getAllValidArgs());
      } else {
        suggestion = "";
      }
      throw new OptionsParsingException("Unrecognized option: " + arg + suggestion, arg);
    }

    if (unconvertedValue == null) {
      // Special-case boolean to supply value based on presence of "no" prefix.
      if (lookupResult.definition.usesBooleanValueSyntax()) {
        unconvertedValue = booleanValue ? "1" : "0";
      } else if (lookupResult.definition.getType().equals(Void.class)) {
        // This is expected, Void type options have no args.
      } else if (nextArgs.hasNext()) {
        // "--flag value" form
        unconvertedValue = nextArgs.next();
        commandLineForm.append(" ").append(unconvertedValue);
      } else {
        throw new OptionsParsingException("Expected value after " + arg);
      }
    }

    if (lookupResult.fromFallback) {
      if (Objects.equals(aliasFlag, "--" + lookupResult.definition.getOptionName())) {
        Map.Entry<String, String> alias;
        try {
          alias =
              (Map.Entry<String, String>)
                  lookupResult
                      .definition
                      .getConverter()
                      .convert(unconvertedValue, conversionContext);
        } catch (OptionsParsingException e) {
          // The converter doesn't know the option name, so we supply it here by re-throwing:
          throw new OptionsParsingException(
              String.format("While parsing option %s: %s", commandLineForm, e.getMessage()), e);
        }
        permittedButIgnoredFlagAliases.add(alias.getKey());
      }

      // The option was not found on the current command, but is a valid option for some other
      // command. Ignore it.
      return new ParsedOptionDescriptionOrIgnoredArgs(
          Optional.empty(), Optional.of(commandLineForm.toString()));
    }

    return new ParsedOptionDescriptionOrIgnoredArgs(
        Optional.of(
            ParsedOptionDescription.newParsedOptionDescription(
                lookupResult.definition,
                commandLineForm.toString(),
                unconvertedValue,
                new OptionInstanceOrigin(
                    priority,
                    sourceFunction.apply(lookupResult.definition),
                    implicitDependent,
                    expandedFrom),
                conversionContext,
                !parsedOptionName.isEmpty()
                    && lookupResult.definition.getOldOptionName().equals(parsedOptionName))),
        Optional.empty());
  }

  private Iterable<String> getAllValidArgs() {
    return () ->
        optionsData.getAllOptionDefinitions().stream()
            .filter(entry -> !shouldIgnoreOption(entry.getValue()))
            .flatMap(
                definition -> {
                  Stream.Builder<String> builder = Stream.builder();
                  builder.add("--" + definition.getKey());
                  if (definition.getValue().usesBooleanValueSyntax()) {
                    builder.add("--no" + definition.getKey());
                  }
                  return builder.build();
                })
            .iterator();
  }

  /**
   * Two option definitions are considered equivalent for parsing if they result in the same control
   * flow through {@link #identifyOptionAndPossibleArgument}. This is crucial to ensure that the
   * beginning of the next option can be determined unambiguously when parsing with fallback data.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>Both {@code query} and {@code cquery} have a {@code --output} option, but the options
   *       accept different sets of values (e.g. {@code cquery} has {@code --output=files}, but
   *       {@code query} doesn't. However, since both options accept a string value, they parse
   *       equivalently as far as {@link #identifyOptionAndPossibleArgument} is concerned -
   *       potential failures due to unsupported values occur after parsing, during value
   *       conversion. There is no ambiguity in how many command-line arguments are consumed
   *       depending on which option definition is used.
   *   <li>If the hypothetical {@code foo} command also had a {@code --output} option, but it were
   *       boolean-valued, then the two option definitions would <b>not</b> be equivalent for
   *       parsing: The command line {@code --output --copt=foo} would parse as {@code {"output":
   *       "--copt=foo"}} for the {@code cquery} command, but as {@code {"output": true, "copt":
   *       "foo"}} for the {@code foo} command, thus resulting in parsing ambiguities between the
   *       two commands.
   * </ul>
   */
  public static boolean equivalentForParsing(
      OptionDefinition definition, OptionDefinition otherDefinition) {
    if (definition.equals(otherDefinition)) {
      return true;
    }
    return (definition.usesBooleanValueSyntax() == otherDefinition.usesBooleanValueSyntax())
        && (definition.getType().equals(Void.class) == otherDefinition.getType().equals(Void.class))
        && (ImmutableList.copyOf(definition.getOptionMetadataTags())
                .contains(OptionMetadataTag.INTERNAL)
            == ImmutableList.copyOf(otherDefinition.getOptionMetadataTags())
                .contains(OptionMetadataTag.INTERNAL));
  }

  // TODO: Replace with a sealed interface unwrapped via pattern matching when available.
  private static final class ParsedOptionDescriptionOrIgnoredArgs {

    final Optional<ParsedOptionDescription> parsedOptionDescription;
    final Optional<String> ignoredArgs;

    private ParsedOptionDescriptionOrIgnoredArgs(
        Optional<ParsedOptionDescription> parsedOptionDescription, Optional<String> ignoredArgs) {
      Preconditions.checkArgument(parsedOptionDescription.isPresent() != ignoredArgs.isPresent());
      this.parsedOptionDescription = parsedOptionDescription;
      this.ignoredArgs = ignoredArgs;
    }
  }

  private static final class OptionLookupResult {

    final OptionDefinition definition;
    final boolean fromFallback;

    private OptionLookupResult(OptionDefinition definition, boolean fromFallback) {
      this.definition = definition;
      this.fromFallback = fromFallback;
    }
  }

  @Nullable
  private <T> OptionLookupResult getWithFallback(
      BiFunction<OptionsData, T, OptionDefinition> getter, T param, OptionsData fallbackData) {
    OptionDefinition optionDefinition;
    if ((optionDefinition = getter.apply(optionsData, param)) != null) {
      return new OptionLookupResult(optionDefinition, false);
    }
    if (fallbackData != null && (optionDefinition = getter.apply(fallbackData, param)) != null) {
      return new OptionLookupResult(optionDefinition, true);
    }
    return null;
  }

  private boolean shouldIgnoreOption(OptionDefinition optionDefinition) {
    return ignoreInternalOptions
        && ImmutableList.copyOf(optionDefinition.getOptionMetadataTags())
            .contains(OptionMetadataTag.INTERNAL);
  }

  /** Gets the result of parsing the options. */
  <O extends OptionsBase> O getParsedOptions(Class<O> optionsClass) {
    // Create the instance:
    O optionsInstance;
    try {
      Constructor<O> constructor = optionsData.getConstructor(optionsClass);
      if (constructor == null) {
        return null;
      }
      optionsInstance = constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Error while instantiating options class", e);
    }

    // Set the fields
    for (OptionDefinition optionDefinition :
        OptionsData.getAllOptionDefinitionsForClass(optionsClass)) {
      Object value;
      OptionValueDescription optionValue = optionValues.get(optionDefinition);
      if (optionValue == null || optionValue.containsErrors()) {
        value = optionDefinition.getDefaultValue(conversionContext);
      } else {
        value = optionValue.getValue();
      }
      try {
        optionDefinition.setValue(optionsInstance, value);
      } catch (IllegalArgumentException e) {
        // May happen when a boolean option got a string value. Just ignore this error without
        // updating the field. Fixes https://github.com/bazelbuild/bazel/issues/7847
      }
    }
    return optionsInstance;
  }

  ImmutableList<String> getWarnings() {
    return ImmutableList.copyOf(warnings);
  }

  /**
   * Takes a string with a leading "-" and swaps it with the matching alias mapping. Example case
   * with --flag_alias=foo=bar mapped:
   *
   * <pre>
   *   swapShorthandAlias("-c") returns "-c"
   *   swapShorthandAlias("--foo") returns "--bar"
   *   swapShorthandAlias("--baz") returns "--baz"
   * </pre>
   *
   * This method returns immediately when aliasFlag is not set via the builder, which is an implicit
   * disabling of the aliasing functionality.
   */
  private String swapShorthandAlias(String arg) {
    if (aliasFlag == null || !arg.startsWith("--")) {
      return arg;
    }

    int equalSign = arg.indexOf("=");

    // Extracts the <arg> from '--<arg>=<value>' and '--<arg> <value>' formats on the command line
    String actualArg = (equalSign != -1) ? arg.substring(2, equalSign) : arg.substring(2);

    if (!flagAliasMappings.containsKey(actualArg)) {
      return arg;
    }

    String alias = flagAliasMappings.get(actualArg);
    actualArg = alias;

    // Converts the arg back into a command line option, accounting for both '--<arg>=<value>' and
    // '--<arg> <value>' formats
    actualArg = (equalSign != -1) ? "--" + actualArg + arg.substring(equalSign) : "--" + actualArg;

    return actualArg;
  }

  private boolean containsSkippedPrefix(String arg) {
    return skippedPrefixes.stream().anyMatch(arg::startsWith);
  }
}
