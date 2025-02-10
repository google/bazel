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
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.MoreCollectors;
import com.google.common.escape.Escaper;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.common.options.OptionsParserImpl.OptionsParserImplResult;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * A parser for options. Typical use case in a main method:
 *
 * <pre>
 * OptionsParser parser = OptionsParser.newOptionsParser(FooOptions.class, BarOptions.class);
 * parser.parseAndExitUponError(args);
 * FooOptions foo = parser.getOptions(FooOptions.class);
 * BarOptions bar = parser.getOptions(BarOptions.class);
 * List&lt;String&gt; otherArguments = parser.getResidue();
 * </pre>
 *
 * <p>FooOptions and BarOptions would be options specification classes, derived from OptionsBase,
 * that contain fields annotated with @Option(...).
 *
 * <p>Alternatively, rather than calling {@link
 * #parseAndExitUponError(OptionPriority.PriorityCategory, String, String[])}, client code may call
 * {@link #parse(OptionPriority.PriorityCategory,String,List)}, and handle parser exceptions usage
 * messages themselves.
 *
 * <p>This options parsing implementation has (at least) one design flaw. It allows both '--foo=baz'
 * and '--foo baz' for all options except void, boolean and tristate options. For these, the 'baz'
 * in '--foo baz' is not treated as a parameter to the option, making it is impossible to switch
 * options between void/boolean/tristate and everything else without breaking backwards
 * compatibility.
 *
 * @see Options a simpler class which you can use if you only have one options specification class
 */
public class OptionsParser implements OptionsParsingResult {

  // TODO(b/65049598) make ConstructionException checked.
  /**
   * An unchecked exception thrown when there is a problem constructing a parser, e.g. an error
   * while validating an {@link OptionDefinition} in one of its {@link OptionsBase} subclasses.
   *
   * <p>This exception is unchecked because it generally indicates an internal error affecting all
   * invocations of the program. I.e., any such error should be immediately obvious to the
   * developer. Although unchecked, we explicitly mark some methods as throwing it as a reminder in
   * the API.
   */
  public static class ConstructionException extends RuntimeException {
    public ConstructionException(String message) {
      super(message);
    }

    public ConstructionException(Throwable cause) {
      super(cause);
    }

    public ConstructionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * A cache for the parsed options data. Both keys and values are immutable, so this is always
   * safe. Only access this field through the {@link #getOptionsData} method for thread-safety! The
   * cache is very unlikely to grow to a significant amount of memory, because there's only a fixed
   * set of options classes on the classpath.
   */
  private static final Map<Pair<ImmutableList<Class<? extends OptionsBase>>, Boolean>, OptionsData>
      optionsData = new HashMap<>();

  /** Skipped prefixes for starlark options. */
  public static final ImmutableList<String> STARLARK_SKIPPED_PREFIXES =
      ImmutableList.of("--//", "--no//", "--@", "--no@");

  /**
   * Returns {@link OpaqueOptionsData} suitable for passing along to {@link
   * Builder#optionsData(OpaqueOptionsData optionsData)}.
   *
   * <p>This is useful when you want to do the work of analyzing the given {@code optionsClasses}
   * exactly once, but you want to parse lots of different lists of strings (and thus need to
   * construct lots of different {@link OptionsParser} instances).
   */
  public static OpaqueOptionsData getOptionsData(
      List<Class<? extends OptionsBase>> optionsClasses) {
    return getOptionsDataInternal(optionsClasses, false);
  }

  public static OpaqueOptionsData getFallbackOptionsData(
      List<Class<? extends OptionsBase>> optionsClasses) {
    return getOptionsDataInternal(optionsClasses, true);
  }

  /** Returns the {@link OptionsData} associated with the given list of options classes. */
  static synchronized OptionsData getOptionsDataInternal(
      List<Class<? extends OptionsBase>> optionsClasses,
      boolean allowDuplicatesParsingEquivalently) {
    ImmutableList<Class<? extends OptionsBase>> immutableOptionsClasses =
        ImmutableList.copyOf(optionsClasses);
    Pair<ImmutableList<Class<? extends OptionsBase>>, Boolean> cacheKey =
        Pair.of(immutableOptionsClasses, allowDuplicatesParsingEquivalently);
    OptionsData result = optionsData.get(cacheKey);
    if (result == null) {
      try {
        result = OptionsData.from(immutableOptionsClasses, allowDuplicatesParsingEquivalently);
      } catch (Exception e) {
        Throwables.throwIfInstanceOf(e, ConstructionException.class);
        throw new ConstructionException(e.getMessage(), e);
      }
      optionsData.put(cacheKey, result);
    }
    return result;
  }

  /** Returns the {@link OptionsData} associated with the given options class. */
  static OptionsData getOptionsDataInternal(Class<? extends OptionsBase> optionsClass) {
    return getOptionsDataInternal(ImmutableList.of(optionsClass), false);
  }

  /** A helper class to create new instances of {@link OptionsParser}. */
  public static final class Builder {
    private final OptionsParserImpl.Builder implBuilder;
    private boolean allowResidue = true;
    private boolean ignoreUserOptions = false;

    private Builder(OptionsParserImpl.Builder implBuilder) {
      this.implBuilder = implBuilder;
    }

    /** Directly sets the {@link OptionsData} used by this parser. */
    @CanIgnoreReturnValue
    public Builder optionsData(OptionsData optionsData) {
      Preconditions.checkArgument(!optionsData.createdWithAllowDuplicatesParsingEquivalently());
      this.implBuilder.optionsData(optionsData);
      return this;
    }

    /** Directly sets the {@link OpaqueOptionsData} used by this parser. */
    public Builder optionsData(OpaqueOptionsData optionsData) {
      return this.optionsData((OptionsData) optionsData);
    }

    /**
     * Sets the {@link OptionsData} used by this parser, based on the given {@code optionsClasses}.
     */
    @SafeVarargs
    public final Builder optionsClasses(Class<? extends OptionsBase>... optionsClasses) {
      return this.optionsData(
          (OpaqueOptionsData) getOptionsDataInternal(ImmutableList.copyOf(optionsClasses), false));
    }

    /**
     * Sets the {@link OptionsData} used by this parser, based on the given {@code optionsClasses}.
     */
    public Builder optionsClasses(Iterable<? extends Class<? extends OptionsBase>> optionsClasses) {
      return this.optionsData(
          (OpaqueOptionsData) getOptionsDataInternal(ImmutableList.copyOf(optionsClasses), false));
    }

    /**
     * Enables the Parser to handle params files using the provided {@link ParamsFilePreProcessor}.
     */
    @CanIgnoreReturnValue
    public Builder argsPreProcessor(ArgsPreProcessor preProcessor) {
      this.implBuilder.argsPreProcessor(preProcessor);
      return this;
    }

    /** Skip all the prefixes associated with Starlark options */
    @CanIgnoreReturnValue
    public Builder skipStarlarkOptionPrefixes() {
      for (String prefix : STARLARK_SKIPPED_PREFIXES) {
        this.implBuilder.skippedPrefix(prefix);
      }

      return this;
    }

    /**
     * Indicates whether or not the parser will allow a non-empty residue; that is, iff this value
     * is true then a call to one of the {@code parse} methods will throw {@link
     * OptionsParsingException} unless {@link #getResidue()} is empty after parsing.
     */
    @CanIgnoreReturnValue
    public Builder allowResidue(boolean allowResidue) {
      this.allowResidue = allowResidue;
      return this;
    }

    /** Sets whether the parser should ignore internal-only options. */
    @CanIgnoreReturnValue
    public Builder ignoreInternalOptions(boolean ignoreInternalOptions) {
      this.implBuilder.ignoreInternalOptions(ignoreInternalOptions);
      return this;
    }

    /** Sets whether the parser should ignore user options. If true, returns no user options. */
    @CanIgnoreReturnValue
    public Builder ignoreUserOptions() {
      this.ignoreUserOptions = true;
      return this;
    }

    /** Sets the string the parser should look for as an identifier for flag aliases. */
    @CanIgnoreReturnValue
    public Builder withAliasFlag(@Nullable String aliasFlag) {
      this.implBuilder.withAliasFlag(aliasFlag);
      return this;
    }

    /**
     * Adds a map of flag aliases for the OptionsParser to reference. The keys are the aliases and
     * the values are the actual options.
     */
    @CanIgnoreReturnValue
    public Builder withAliases(Map<String, String> aliases) {
      this.implBuilder.withAliases(aliases);
      return this;
    }

    public Builder withConversionContext(Object conversionContext) {
      this.implBuilder.withConversionContext(conversionContext);
      return this;
    }

    /** Returns a new {@link OptionsParser}. */
    public OptionsParser build() {
      return new OptionsParser(implBuilder.build(), allowResidue, ignoreUserOptions);
    }
  }

  /** Returns a new {@link Builder} to create {@link OptionsParser} instances. */
  public static Builder builder() {
    return new Builder(OptionsParserImpl.builder());
  }

  public Builder toBuilder() {
    return new Builder(impl.toBuilder()).allowResidue(allowResidue);
  }

  private final OptionsParserImpl impl;
  private final List<String> residue = new ArrayList<>();
  private final List<String> postDoubleDashResidue = new ArrayList<>();
  private final boolean allowResidue;
  private final boolean ignoreUserOptions;

  private ImmutableSortedMap<String, Object> starlarkOptions = ImmutableSortedMap.of();
  // scopes for starlark options
  private ImmutableSortedMap<String, String> scopesAttributes = ImmutableSortedMap.of();
  private final Map<String, String> aliases = new HashMap<>();
  private boolean success = true;

  private OptionsParser(OptionsParserImpl impl, boolean allowResidue, boolean ignoreUserOptions) {
    this.impl = impl;
    this.allowResidue = allowResidue;
    this.ignoreUserOptions = ignoreUserOptions;
  }

  public Object getConversionContext() {
    return impl.getConversionContext();
  }

  @Override
  public ImmutableSortedMap<String, Object> getStarlarkOptions() {
    return starlarkOptions;
  }

  @Override
  public ImmutableMap<String, String> getScopesAttributes() {
    return scopesAttributes;
  }

  @Override
  public ImmutableSortedMap<String, Object> getExplicitStarlarkOptions(
      Predicate<? super ParsedOptionDescription> filter) {
    ImmutableSet<String> explicitOptions =
        impl.getSkippedOptions().stream()
            .filter(ParsedOptionDescription::isExplicit)
            .filter(filter)
            // Since this was passed from OptionsParserImpl unparsed, it still appears in its raw
            // form "--//foo=bar". Do some more string manipulation to reduce it to "//foo". By
            // contract, getStarlarkOptions(), which we compare against below, contains options that
            // were fully parsed by StarlarkOptionsParser. So the keys of that method are already in
            // "//foo" form.
            // TODO(https://github.com/bazelbuild/bazel/issues/17414): integrate Starlark and native
            // options parsing more tightly together in the options parsing logic. The complication
            // is that getSkippedOptions, which comes from OptionsParserImpl, has the
            // ParsedOptionsDescription structure which includes where the option comes from (i.e.
            // from a blazerc). But it doesn't have the <String, Object> map of the actually parsed
            // Starlark option. StarlarkOptionsParser is the exact converse. It'd be nice to have
            // common logic that could store both pieces of information so we don't have to
            // awkwardly synthesize the data we need from both sources here.
            .map(d -> Iterables.get(Splitter.on('=').split(d.getCommandLineForm().substring(2)), 0))
            .collect(toImmutableSet());
    ImmutableSortedMap.Builder<String, Object> result =
        ImmutableSortedMap.<String, Object>naturalOrder();
    for (Map.Entry<String, Object> entry : getStarlarkOptions().entrySet()) {
      // getSkippedOptions() doesn't necessarily *only* have Starlark options. By comparing here we
      // filter to just Starlark options.
      if (explicitOptions.contains(entry.getKey())) {
        result.put(entry);
      }
    }
    return result.buildOrThrow();
  }

  public void setStarlarkOptions(Map<String, Object> starlarkOptions) {
    this.starlarkOptions = ImmutableSortedMap.copyOf(starlarkOptions);
  }

  public void setScopesAttributes(Map<String, String> scopesAttributes) {
    this.scopesAttributes = ImmutableSortedMap.copyOf(scopesAttributes);
  }

  public void parseAndExitUponError(String[] args) {
    parseAndExitUponError(OptionPriority.PriorityCategory.COMMAND_LINE, "unknown", args);
  }

  /**
   * A convenience function for use in main methods. Parses the command line parameters, and exits
   * upon error. Also, prints out the usage message if "--help" appears anywhere within {@code
   * args}.
   */
  @SuppressWarnings("SystemExitOutsideMain")
  public void parseAndExitUponError(
      OptionPriority.PriorityCategory priority, String source, String[] args) {
    for (String arg : args) {
      if (arg.equals("--help")) {
        System.out.println(
            describeOptionsWithDeprecatedCategories(ImmutableMap.of(), HelpVerbosity.LONG));

        System.exit(0);
      }
    }
    try {
      parse(priority, source, Arrays.asList(args));
    } catch (OptionsParsingException e) {
      System.err.println("Error parsing command line: " + e.getMessage());
      System.err.println("Try --help.");
      System.exit(2);
    }
  }

  /** The metadata about an option, in the context of this options parser. */
  public static final class OptionDescription {
    private final OptionDefinition optionDefinition;
    private final ImmutableList<String> evaluatedExpansion;

    OptionDescription(OptionDefinition definition, OptionsData optionsData) {
      this.optionDefinition = definition;
      this.evaluatedExpansion = optionsData.getEvaluatedExpansion(optionDefinition);
    }

    public OptionDefinition getOptionDefinition() {
      return optionDefinition;
    }

    public boolean isExpansion() {
      return optionDefinition.isExpansionOption();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof OptionDescription) {
        OptionDescription other = (OptionDescription) obj;
        // Check that the option is the same, with the same expansion.
        return other.optionDefinition.equals(optionDefinition)
            && other.evaluatedExpansion.equals(evaluatedExpansion);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return optionDefinition.hashCode() + evaluatedExpansion.hashCode();
    }
  }

  /**
   * The verbosity with which option help messages are displayed: short (just the name), medium
   * (name, type, default, abbreviation), and long (full description).
   */
  public enum HelpVerbosity {
    LONG,
    MEDIUM,
    SHORT
  }

  /**
   * Returns a description of all the options this parser can digest. In addition to {@link Option}
   * annotations, this method also interprets {@link OptionsUsage} annotations which give an
   * intuitive short description for the options. Options of the same category (see {@link
   * OptionDocumentationCategory}) will be grouped together.
   *
   * @param productName the name of this product (blaze, bazel)
   * @param helpVerbosity if {@code long}, the options will be described verbosely, including their
   *     types, defaults and descriptions. If {@code medium}, the descriptions are omitted, and if
   *     {@code short}, the options are just enumerated.
   */
  public String describeOptions(String productName, HelpVerbosity helpVerbosity) {
    StringBuilder desc = new StringBuilder();
    LinkedHashMap<OptionDocumentationCategory, List<OptionDefinition>> optionsByCategory =
        getOptionsSortedByCategory();
    ImmutableMap<OptionDocumentationCategory, String> optionCategoryDescriptions =
        OptionFilterDescriptions.getOptionCategoriesEnumDescription(productName);
    for (Map.Entry<OptionDocumentationCategory, List<OptionDefinition>> e :
        optionsByCategory.entrySet()) {
      String categoryDescription = optionCategoryDescriptions.get(e.getKey());
      List<OptionDefinition> categorizedOptionList = e.getValue();

      // Describe the category if we're going to end up using it at all.
      if (!categorizedOptionList.isEmpty()) {
        desc.append("\n").append(categoryDescription).append(":\n");
      }
      // Describe the options in this category.
      for (OptionDefinition optionDef : categorizedOptionList) {
        OptionsUsage.getUsage(optionDef, desc, helpVerbosity, impl.getOptionsData(), true);
      }
    }

    return desc.toString().trim();
  }

  /**
   * Returns all documented options loaded in this parser, grouped by categories in display order.
   */
  public LinkedHashMap<OptionDocumentationCategory, List<OptionDefinition>>
      getOptionsSortedByCategory() {
    OptionsData data = impl.getOptionsData();
    if (data.getOptionsClasses().isEmpty()) {
      return new LinkedHashMap<>();
    }

    // Get the documented options grouped by category.
    ListMultimap<OptionDocumentationCategory, OptionDefinition> optionsByCategories =
        ArrayListMultimap.create();
    for (Class<? extends OptionsBase> optionsClass : data.getOptionsClasses()) {
      for (OptionDefinition optionDefinition :
          OptionsData.getAllOptionDefinitionsForClass(optionsClass)) {
        // Only track documented options.
        if (optionDefinition.getDocumentationCategory()
            != OptionDocumentationCategory.UNDOCUMENTED) {
          optionsByCategories.put(optionDefinition.getDocumentationCategory(), optionDefinition);
        }
      }
    }

    // Put the categories into display order and sort the options in each category.
    LinkedHashMap<OptionDocumentationCategory, List<OptionDefinition>> sortedCategoriesToOptions =
        new LinkedHashMap<>(OptionFilterDescriptions.documentationOrder.length, 1);
    for (OptionDocumentationCategory category : OptionFilterDescriptions.documentationOrder) {
      List<OptionDefinition> optionList = optionsByCategories.get(category);
      if (optionList != null) {
        optionList.sort(OptionDefinition.BY_OPTION_NAME);
        sortedCategoriesToOptions.put(category, optionList);
      }
    }
    return sortedCategoriesToOptions;
  }

  /**
   * Returns a description of all the options this parser can digest. In addition to {@link Option}
   * annotations, this method also interprets {@link OptionsUsage} annotations which give an
   * intuitive short description for the options. Options of the same category (see {@link
   * Option#category}) will be grouped together.
   *
   * @param categoryDescriptions a mapping from category names to category descriptions.
   *     Descriptions are optional; if omitted, a string based on the category name will be used.
   * @param helpVerbosity if {@code long}, the options will be described verbosely, including their
   *     types, defaults and descriptions. If {@code medium}, the descriptions are omitted, and if
   *     {@code short}, the options are just enumerated.
   */
  @Deprecated
  public String describeOptionsWithDeprecatedCategories(
      Map<String, String> categoryDescriptions, HelpVerbosity helpVerbosity) {
    OptionsData data = impl.getOptionsData();
    StringBuilder desc = new StringBuilder();
    if (!data.getOptionsClasses().isEmpty()) {
      List<OptionDefinition> allFields = new ArrayList<>();
      for (Class<? extends OptionsBase> optionsClass : data.getOptionsClasses()) {
        allFields.addAll(OptionsData.getAllOptionDefinitionsForClass(optionsClass));
      }
      Collections.sort(allFields, OptionDefinition.BY_CATEGORY);
      String prevCategory = null;

      for (OptionDefinition optionDefinition : allFields) {
        String category = optionDefinition.getOptionCategory();
        if (!category.equals(prevCategory)
            && optionDefinition.getDocumentationCategory()
                != OptionDocumentationCategory.UNDOCUMENTED) {
          String description = categoryDescriptions.get(category);
          if (description == null) {
            description = "Options category '" + category + "'";
          }
          desc.append("\n").append(description).append(":\n");
          prevCategory = category;
        }

        if (optionDefinition.getDocumentationCategory()
            != OptionDocumentationCategory.UNDOCUMENTED) {
          OptionsUsage.getUsage(
              optionDefinition, desc, helpVerbosity, impl.getOptionsData(), false);
        }
      }
    }
    return desc.toString().trim();
  }

  /**
   * Returns a description of all the options this parser can digest. In addition to {@link Option}
   * annotations, this method also interprets {@link OptionsUsage} annotations which give an
   * intuitive short description for the options.
   */
  public String describeOptionsHtml(
      Escaper escaper, String productName, List<String> optionsToIgnore) {
    StringBuilder desc = new StringBuilder();
    LinkedHashMap<OptionDocumentationCategory, List<OptionDefinition>> optionsByCategory =
        getOptionsSortedByCategory();
    ImmutableMap<OptionDocumentationCategory, String> optionCategoryDescriptions =
        OptionFilterDescriptions.getOptionCategoriesEnumDescription(productName);

    for (Map.Entry<OptionDocumentationCategory, List<OptionDefinition>> e :
        optionsByCategory.entrySet()) {
      List<OptionDefinition> categorizedOptionsList = e.getValue();
      categorizedOptionsList =
          categorizedOptionsList.stream()
              .filter(
                  optionDef ->
                      Arrays.stream(optionDef.getOptionEffectTags())
                          .noneMatch(effectTag -> effectTag.equals(OptionEffectTag.NO_OP)))
              .filter(optionDef -> !optionsToIgnore.contains(optionDef.getOptionName()))
              .collect(toImmutableList());
      if (categorizedOptionsList.isEmpty()) {
        continue;
      }
      String categoryDescription = optionCategoryDescriptions.get(e.getKey());

      desc.append("<dl>").append(escaper.escape(categoryDescription)).append(":\n");
      for (OptionDefinition optionDef : categorizedOptionsList) {
        OptionsUsage.getUsageHtml(optionDef, desc, escaper, impl.getOptionsData(), true);
      }
      desc.append("</dl>\n");
    }
    return desc.toString();
  }

  /**
   * Returns a string listing the possible flag completion for this command along with the command
   * completion if any. See {@link OptionsUsage#getCompletion(OptionDefinition, StringBuilder)} for
   * more details on the format for the flag completion.
   */
  public String getOptionsCompletion() {
    StringBuilder desc = new StringBuilder();

    visitOptions(
        optionDefinition ->
            optionDefinition.getDocumentationCategory() != OptionDocumentationCategory.UNDOCUMENTED,
        optionDefinition -> OptionsUsage.getCompletion(optionDefinition, desc));

    return desc.toString();
  }

  public void visitOptions(
      Predicate<OptionDefinition> predicate, Consumer<OptionDefinition> visitor) {
    Preconditions.checkNotNull(predicate, "Missing predicate.");
    Preconditions.checkNotNull(visitor, "Missing visitor.");

    OptionsData data = impl.getOptionsData();
    data
        .getOptionsClasses()
        // List all options
        .stream()
        .flatMap(optionsClass -> OptionsData.getAllOptionDefinitionsForClass(optionsClass).stream())
        // Sort field for deterministic ordering
        .sorted(OptionDefinition.BY_OPTION_NAME)
        .filter(predicate)
        .forEach(visitor);
  }

  /**
   * Returns a description of the option.
   *
   * @return The {@link OptionDescription} for the option, or null if there is no option by the
   *     given name.
   */
  OptionDescription getOptionDescription(String name) throws OptionsParsingException {
    return impl.getOptionDescription(name);
  }

  /**
   * Returns the parsed options that get expanded from this option, whether it expands due to an
   * implicit requirement or expansion.
   *
   * @param expansionOption the option that might need to be expanded. If this option does not
   *     expand to other options, the empty list will be returned.
   * @param originOfExpansionOption the origin of the option that's being expanded. This function
   *     will take care of adjusting the source messages as necessary.
   */
  ImmutableList<ParsedOptionDescription> getExpansionValueDescriptions(
      OptionDefinition expansionOption, OptionInstanceOrigin originOfExpansionOption)
      throws OptionsParsingException {
    return impl.getExpansionValueDescriptions(expansionOption, originOfExpansionOption);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the value set by the last previous call to {@link
   * #parse(OptionPriority.PriorityCategory, String, List)} that successfully set the given option.
   * If the option is of type {@link List}, the description will correspond to any one of the calls,
   * but not necessarily the last.
   */
  @Override
  public OptionValueDescription getOptionValueDescription(String name) {
    return impl.getOptionValueDescription(name);
  }

  /**
   * A convenience method, equivalent to {@code parse(PriorityCategory.COMMAND_LINE, null,
   * Arrays.asList(args))}.
   */
  public void parse(String... args) throws OptionsParsingException {
    parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, Arrays.asList(args));
  }

  /**
   * A convenience method, equivalent to {@code parse(PriorityCategory.COMMAND_LINE, null, args)}.
   */
  public void parse(List<String> args) throws OptionsParsingException {
    parse(OptionPriority.PriorityCategory.COMMAND_LINE, null, args);
  }

  /**
   * Parses {@code args}, using the classes registered with this parser, at the given priority.
   *
   * <p>May be called multiple times; later options override existing ones if they have equal or
   * higher priority. Strings that cannot be parsed as options are accumulated as residue, if this
   * parser allows it.
   *
   * <p>{@link #getOptions(Class)} and {@link #getResidue()} will return the results.
   *
   * @param priority the priority at which to parse these options. Within this priority category,
   *     each option will be given an index to track its position. If parse() has already been
   *     called at this priority, the indexing will continue where it left off, to keep ordering.
   * @param source the source to track for each option parsed.
   * @param args the arg list to parse. Each element might be an option, a value linked to an
   *     option, or residue.
   */
  public void parse(OptionPriority.PriorityCategory priority, String source, List<String> args)
      throws OptionsParsingException {
    parseWithSourceFunction(priority, o -> source, args, /* fallbackData= */ null);
  }

  /**
   * Parses {@code args}, using the classes registered with this parser, at the given priority.
   *
   * <p>May be called multiple times; later options override existing ones if they have equal or
   * higher priority. Strings that cannot be parsed as options are accumulated as residue, if this
   * parser allows it.
   *
   * <p>{@link #getOptions(Class)} and {@link #getResidue()} will return the results.
   *
   * @param priority the priority at which to parse these options. Within this priority category,
   *     each option will be given an index to track its position. If parse() has already been
   *     called at this priority, the indexing will continue where it left off, to keep ordering.
   * @param sourceFunction a function that maps option names to the source of the option.
   * @param args the arg list to parse. Each element might be an option, a value linked to an
   *     option, or residue.
   * @return a list of options and values that were parsed but ignored due to only resolving against
   *     the fallback data
   */
  @CanIgnoreReturnValue
  public ImmutableList<String> parseWithSourceFunction(
      OptionPriority.PriorityCategory priority,
      Function<OptionDefinition, String> sourceFunction,
      List<String> args,
      @Nullable OpaqueOptionsData fallbackData)
      throws OptionsParsingException {
    Preconditions.checkNotNull(priority);
    Preconditions.checkArgument(priority != OptionPriority.PriorityCategory.DEFAULT);
    OptionsParserImplResult optionsParserImplResult =
        impl.parse(
            priority, sourceFunction, ArgAndFallbackData.wrapWithFallbackData(args, fallbackData));
    addResidueFromResult(optionsParserImplResult);
    aliases.putAll(optionsParserImplResult.aliases);
    return optionsParserImplResult.ignoredArgs;
  }

  /**
   * Parses the args at the priority of the provided option. This is useful for after-the-fact
   * expansion.
   *
   * @param optionToExpand the option that is being "expanded" after the fact. The provided args
   *     will have the same priority as this option.
   * @param source a description of where the expansion arguments came from.
   * @param args the arguments to parse as the expansion. Order matters, as the value of a flag may
   *     be in the following argument. Each arg is optionally annotated with the full collection of
   *     options that should be parsed and ignored without raising an error if they are not
   *     recognized by the options classes registered with this parser.
   * @return a list of options and values that were parsed but ignored due to only resolving against
   *     the fallback data
   */
  @CanIgnoreReturnValue
  public ImmutableList<String> parseArgsAsExpansionOfOption(
      ParsedOptionDescription optionToExpand, String source, List<ArgAndFallbackData> args)
      throws OptionsParsingException {
    Preconditions.checkNotNull(
        optionToExpand, "Option for expansion not specified for arglist %s", args);
    Preconditions.checkArgument(
        optionToExpand.getPriority().getPriorityCategory()
            != OptionPriority.PriorityCategory.DEFAULT,
        "Priority cannot be default, which was specified for arglist %s",
        args);
    OptionsParserImplResult optionsParserImplResult =
        impl.parseArgsAsExpansionOfOption(optionToExpand, o -> source, args);
    addResidueFromResult(optionsParserImplResult);
    return optionsParserImplResult.ignoredArgs;
  }

  private void addResidueFromResult(OptionsParserImplResult result) throws OptionsParsingException {
    residue.addAll(result.getResidue());
    postDoubleDashResidue.addAll(result.postDoubleDashResidue);
    if (!allowResidue && !residue.isEmpty()) {
      String errorMsg = "Unrecognized arguments: " + Joiner.on(' ').join(residue);
      throw new OptionsParsingException(errorMsg);
    }
  }

  /**
   * Sets provided value for a flag with a particular priority. This only sets the value of the flag
   * itself and does not affect any of its implicit requirements or expansions.
   *
   * @param origin the origin of this option instance, it includes the priority of the value. If
   *     other values have already been or will be parsed at a higher priority, they might override
   *     the provided value. If this option already has a value at this priority, this value will
   *     have precedence, but this should be avoided, as it breaks order tracking.
   * @param option the option to add the value for.
   * @param value the value to add at the given priority.
   */
  void setOptionValueAtSpecificPriorityWithoutExpansion(
      OptionInstanceOrigin origin, OptionDefinition option, String value)
      throws OptionsParsingException {
    impl.setOptionValueAtSpecificPriorityWithoutExpansion(origin, option, value);
  }

  /**
   * Clears the given option.
   *
   * <p>This will not affect options objects that have already been retrieved from this parser
   * through {@link #getOptions(Class)}.
   *
   * @param option The option to clear.
   * @return The old value of the option that was cleared.
   * @throws IllegalArgumentException If the flag does not exist.
   */
  public OptionValueDescription clearValue(OptionDefinition option) throws OptionsParsingException {
    return impl.clearValue(option);
  }

  @Override
  public Map<String, String> getAliases() {
    return ImmutableMap.copyOf(aliases);
  }

  /** Makes {@link #success()} return false. */
  public void setError() {
    success = false;
  }

  @Override
  public boolean success() {
    return success;
  }

  @Override
  public ImmutableList<String> getSkippedArgs() {
    return ImmutableList.copyOf(impl.getSkippedArgs());
  }

  @Override
  public ImmutableList<String> getResidue() {
    return ImmutableList.copyOf(residue);
  }

  @Override
  public List<String> getPreDoubleDashResidue() {
    return postDoubleDashResidue.isEmpty()
        ? ImmutableList.copyOf(residue)
        : residue.stream()
            .filter(residue -> !postDoubleDashResidue.contains(residue))
            .collect(toImmutableList());
  }

  /* Sets the residue (all elements parsed as non-options) to {@code residue}, as well as the part
   * of the residue that follows the double-dash on the command line, {@code postDoubleDashResidue}.
   * {@code postDoubleDashResidue} must be a subset of {@code residue}. */
  public void setResidue(List<String> residue, List<String> postDoubleDashResidue) {
    Preconditions.checkArgument(residue.containsAll(postDoubleDashResidue));
    this.residue.clear();
    this.residue.addAll(residue);
    this.postDoubleDashResidue.clear();
    this.postDoubleDashResidue.addAll(postDoubleDashResidue);
  }

  /** Returns a list of warnings about problems encountered by previous parse calls. */
  public ImmutableList<String> getWarnings() {
    return impl.getWarnings();
  }

  @Override
  public <O extends OptionsBase> O getOptions(Class<O> optionsClass) {
    return impl.getParsedOptions(optionsClass);
  }

  @Override
  public boolean containsExplicitOption(String name) {
    return impl.containsExplicitOption(name);
  }

  @Override
  public List<ParsedOptionDescription> asCompleteListOfParsedOptions() {
    return impl.asCompleteListOfParsedOptions();
  }

  @Override
  public List<ParsedOptionDescription> asListOfExplicitOptions() {
    return impl.asListOfExplicitOptions();
  }

  @Override
  public List<ParsedOptionDescription> asListOfCanonicalOptions() {
    return impl.asCanonicalizedListOfParsedOptions();
  }

  @Override
  public List<OptionValueDescription> asListOfOptionValues() {
    return impl.asListOfEffectiveOptions();
  }

  @Override
  public List<OptionValueDescription> allOptionValues() {
    return impl.allOptionValues();
  }

  @Override
  public List<String> canonicalize() {
    return impl.asCanonicalizedList();
  }

  @Override
  public ImmutableMap<String, String> getUserOptions() {
    if (ignoreUserOptions) {
      return ImmutableMap.of();
    }

    // First collect to a hashmap to deduplicate options.
    HashMap<String, String> userOptions = new HashMap<>();

    asCompleteListOfParsedOptions().stream()
        .filter(GlobalRcUtils.IS_GLOBAL_RC_OPTION.negate())
        .filter(option -> !option.getCanonicalForm().contains("default_override"))
        .forEach(
            option ->
                userOptions.put(
                    option.getCanonicalForm(),
                    option.getExpandedFrom() == null
                        ? ""
                        : option.getExpandedFrom().getCanonicalForm()));
    impl.getSkippedOptions().stream()
        .filter(GlobalRcUtils.IS_GLOBAL_RC_OPTION.negate())
        .map(option -> option.getUnconvertedValue())
        .filter(
            o ->
                getStarlarkOptions()
                    .containsKey(Iterables.get(Splitter.on('=').split(o.replace("--", "")), 0)))
        .forEach(option -> userOptions.put(option, ""));

    return ImmutableMap.copyOf(userOptions);
  }

  /** Returns all options fields of the given options class, in alphabetic order. */
  public static ImmutableList<? extends OptionDefinition> getOptionDefinitions(
      Class<? extends OptionsBase> optionsClass) {
    return OptionsData.getAllOptionDefinitionsForClass(optionsClass);
  }

  /**
   * Returns the option with the given name from the given class.
   *
   * <p>The preferred way of using this method is as the initializer for a static final field in the
   * options class which defines the option. This reduces the possibility that another contributor
   * might change the name of the option without realizing it's used by name elsewhere.
   *
   * @throws IllegalArgumentException if there are two or more options with that name.
   * @throws java.util.NoSuchElementException if there are no options with that name.
   */
  public static OptionDefinition getOptionDefinitionByName(
      Class<? extends OptionsBase> optionsClass, String optionName) {
    return getOptionDefinitions(optionsClass).stream()
        .filter(definition -> definition.getOptionName().equals(optionName))
        .collect(MoreCollectors.onlyElement());
  }

  /**
   * Returns whether the given options class uses only the core types listed in {@link
   * UsesOnlyCoreTypes#CORE_TYPES}. These are guaranteed to be deeply immutable and serializable.
   */
  public static boolean getUsesOnlyCoreTypes(Class<? extends OptionsBase> optionsClass) {
    OptionsData data = OptionsParser.getOptionsDataInternal(optionsClass);
    return data.getUsesOnlyCoreTypes(optionsClass);
  }

  /**
   * A container for an arg and associated options that should be silently ignored when parsed but
   * not recognized by the current command.
   */
  public static final class ArgAndFallbackData {
    public final String arg;
    @Nullable final OptionsData fallbackData;

    public ArgAndFallbackData(String arg, @Nullable OpaqueOptionsData fallbackData) {
      this.arg = Preconditions.checkNotNull(arg);
      this.fallbackData = (OptionsData) fallbackData;
    }

    public static List<ArgAndFallbackData> wrapWithFallbackData(
        List<String> args, @Nullable OpaqueOptionsData fallbackData) {
      return Lists.transform(args, arg -> new ArgAndFallbackData(arg, fallbackData));
    }
  }
}
