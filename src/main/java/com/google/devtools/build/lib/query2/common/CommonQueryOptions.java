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
package com.google.devtools.build.lib.query2.common;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionMetadataTag;
import com.google.devtools.common.options.OptionsBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.starlark.java.eval.StarlarkSemantics;

/** Options shared between blaze query implementations. */
public class CommonQueryOptions extends OptionsBase {

  @Option(
      name = "universe_scope",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.QUERY,
      converter = Converters.CommaSeparatedOptionListConverter.class,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "A comma-separated set of target patterns (additive and subtractive). The query may be"
              + " performed in the universe defined by the transitive closure of the specified"
              + " targets. This option is used for the query and cquery commands.\n"
              + "For cquery, the input to this option is the targets all answers are built under"
              + " and so this option may affect configurations and transitions. If this option is"
              + " not specified, the top-level targets are assumed to be the targets parsed from"
              + " the query expression. Note: For cquery, not specifying this option may cause the"
              + " build to break if targets parsed from the query expression are not buildable"
              + " with top-level options.")
  public List<String> universeScope;

  @Option(
      name = "line_terminator_null",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Whether each format is terminated with \\0 instead of newline.")
  public boolean lineTerminatorNull;

  /** Ugly workaround since line terminator option default has to be constant expression. */
  public String getLineTerminator() {
    if (lineTerminatorNull) {
      return "\0";
    }

    return System.lineSeparator();
  }

  @Option(
      name = "infer_universe_scope",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.LOADING_AND_ANALYSIS},
      help =
          "If set and --universe_scope is unset, then a value of --universe_scope will be inferred"
              + " as the list of unique target patterns in the query expression. Note that the"
              + " --universe_scope value inferred for a query expression that uses universe-scoped"
              + " functions (e.g.`allrdeps`) may not be what you want, so you should use this"
              + " option only if you know what you are doing. See"
              + " https://bazel.build/reference/query#sky-query for details and"
              + " examples. If --universe_scope is set, then this option's value is ignored. Note:"
              + " this option applies only to `query` (i.e. not `cquery`).")
  public boolean inferUniverseScope;

  @Option(
      name = "tool_deps",
      oldName = "host_deps",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "Query: If disabled, dependencies on 'exec configuration' will"
              + " not be included in the dependency graph over which the query operates. An 'exec"
              + " configuration' dependency edge, such as the one from any 'proto_library' rule to"
              + " the Protocol Compiler, usually points to a tool executed during the build rather"
              + " than a part of the same 'target' program.\n"
              + "Cquery: If disabled, filters out all configured targets which cross an"
              + " execution transition from the top-level target that discovered this configured"
              + " target. That means if the top-level target is in the target configuration, only"
              + " configured targets also in the target configuration will be returned. If the"
              + " top-level target is in the exec configuration, only exec configured targets will"
              + " be returned. This option will NOT exclude resolved toolchains.")
  public boolean includeToolDeps;

  @Option(
      name = "implicit_deps",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "If enabled, implicit dependencies will be included in the dependency graph over "
              + "which the query operates. An implicit dependency is one that is not explicitly "
              + "specified in the BUILD file but added by bazel. For cquery, this option controls "
              + "filtering resolved toolchains.")
  public boolean includeImplicitDeps;

  @Option(
      name = "nodep_deps",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "If enabled, deps from \"nodep\" attributes will be included in the dependency graph "
              + "over which the query operates. A common example of a \"nodep\" attribute is "
              + "\"visibility\". Run and parse the output of `info build-language` to learn about "
              + "all the \"nodep\" attributes in the build language.")
  public boolean includeNoDepDeps;

  @Option(
      name = "include_aspects",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "aquery, cquery: whether to include aspect-generated actions in the output. "
              + "query: no-op (aspects are always followed).")
  public boolean useAspects;

  @Option(
      name = "incompatible_package_group_includes_double_slash",
      defaultValue = FlagConstants.DEFAULT_INCOMPATIBLE_PACKAGE_GROUP_INCLUDES_DOUBLE_SLASH,
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      metadataTags = {OptionMetadataTag.INCOMPATIBLE_CHANGE},
      help =
          "If enabled, when outputting package_group's `packages` attribute, the leading `//`"
              + " will not be omitted.")
  public boolean incompatiblePackageGroupIncludesDoubleSlash;

  @Option(
      name = "experimental_explicit_aspects",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "aquery, cquery: whether to include aspect-generated actions in the output. "
              + "query: no-op (aspects are always followed).")
  public boolean explicitAspects;

  /** Return the current options as a set of QueryEnvironment settings. */
  public Set<Setting> toSettings() {
    Set<Setting> settings = EnumSet.noneOf(Setting.class);
    if (!includeToolDeps) {
      settings.add(Setting.ONLY_TARGET_DEPS);
    }
    if (!includeImplicitDeps) {
      settings.add(Setting.NO_IMPLICIT_DEPS);
    }
    if (!includeNoDepDeps) {
      settings.add(Setting.NO_NODEP_DEPS);
    }
    if (useAspects) {
      settings.add(Setting.INCLUDE_ASPECTS);
    }
    if (explicitAspects) {
      settings.add(Setting.EXPLICIT_ASPECTS);
    }
    return settings;
  }

  @Option(
      name = "consistent_labels",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "If enabled, every query command emits labels as if by the Starlark <code>str</code>"
              + " function applied to a <code>Label</code> instance. This is useful for tools that"
              + " need to match the output of different query commands and/or labels emitted by"
              + " rules. If not enabled, output formatters are free to emit apparent repository"
              + " names (relative to the main repository) instead to make the output more"
              + " readable.")
  public boolean emitConsistentLabels;

  public LabelPrinter getLabelPrinter(
      StarlarkSemantics starlarkSemantics, RepositoryMapping mainRepoMapping) {
    return emitConsistentLabels
        ? LabelPrinter.starlark(starlarkSemantics)
        : LabelPrinter.displayForm(mainRepoMapping);
  }

  public LabelPrinter getLabelPrinterLegacy(StarlarkSemantics starlarkSemantics) {
    return emitConsistentLabels ? LabelPrinter.starlark(starlarkSemantics) : LabelPrinter.LEGACY;
  }

  ///////////////////////////////////////////////////////////
  // PROTO OUTPUT FORMATTER OPTIONS                        //
  ///////////////////////////////////////////////////////////

  @Option(
      name = "relative_locations",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "If true, the location of BUILD files in xml and proto outputs will be relative. "
              + "By default, the location output is an absolute path and will not be consistent "
              + "across machines. You can set this option to true to have a consistent result "
              + "across machines.")
  public boolean relativeLocations;

  @Option(
      name = "proto:locations",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Whether to output location information in proto output at all.")
  public boolean protoIncludeLocations;

  @Option(
      name = "proto:default_values",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "If true, attributes whose value is not explicitly specified in the BUILD file are "
              + "included; otherwise they are omitted. This option is applicable to --output=proto")
  public boolean protoIncludeDefaultValues;

  @Option(
      name = "proto:flatten_selects",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "If enabled, configurable attributes created by select() are flattened. For list types "
              + "the flattened representation is a list containing each value of the select map "
              + "exactly once. Scalar types are flattened to null.")
  public boolean protoFlattenSelects;

  @Option(
      name = "proto:output_rule_attrs",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "all",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Comma separated list of attributes to include in output. Defaults to all attributes. "
              + "Set to empty string to not output any attribute. "
              + "This option is applicable to --output=proto.")
  public List<String> protoOutputRuleAttributes = ImmutableList.of("all");

  @Option(
      name = "proto:rule_inputs_and_outputs",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Whether or not to populate the rule_input and rule_output fields.")
  public boolean protoIncludeRuleInputsAndOutputs;

  @Option(
      name = "proto:include_synthetic_attribute_hash",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help = "Whether or not to calculate and populate the $internal_attr_hash attribute.")
  public boolean protoIncludeSyntheticAttributeHash;

  @Option(
      name = "proto:instantiation_stack",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Populate the instantiation call stack of each rule. "
              + "Note that this requires the stack to be present")
  public boolean protoIncludeInstantiationStack;

  @Option(
      name = "proto:definition_stack",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Populate the definition_stack proto field, which records for each rule instance the "
              + "Starlark call stack at the moment the rule's class was defined.")
  public boolean protoIncludeDefinitionStack;

  @Option(
      name = "proto:include_starlark_rule_env",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Use the starlark environment in the value of the generated $internal_attr_hash"
              + " attribute. This ensures that the starlark rule definition (and its transitive"
              + " imports) are part of this identifier.")
  public boolean protoIncludeStarlarkRuleEnv;

  @Option(
      name = "proto:include_attribute_source_aspects",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Populate the source_aspect_name proto field of each Attribute with the source aspect "
              + "that the attribute came from (empty string if it did not).")
  public boolean protoIncludeAttributeSourceAspects;

  @Option(
      name = "proto:rule_classes",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "Populate the rule_class_key field of each rule; and for the first rule with a given"
              + " rule_class_key, also populate its rule_class_info proto field. The rule_class_key"
              + " field uniquely identifies a rule class, and the rule_class_info field is a"
              + " Stardoc-format rule class API definition.")
  public boolean protoRuleClasses;

  /** An enum converter for {@code AspectResolver.Mode} . Should be used internally only. */
  public static class AspectResolutionModeConverter extends EnumConverter<Mode> {
    public AspectResolutionModeConverter() {
      super(AspectResolver.Mode.class, "Aspect resolution mode");
    }
  }

  @Option(
      name = "aspect_deps",
      converter = AspectResolutionModeConverter.class,
      defaultValue = "conservative",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.BUILD_FILE_SEMANTICS},
      help =
          "How to resolve aspect dependencies when the output format is one of {xml,proto,record}. "
              + "'off' means no aspect dependencies are resolved, 'conservative' (the default) "
              + "means all declared aspect dependencies are added regardless of whether they are "
              + "given the rule class of direct dependencies, 'precise' means that only those "
              + "aspects are added that are possibly active given the rule class of the direct "
              + "dependencies. Note that precise mode requires loading other packages to evaluate "
              + "a single target thus making it slower than the other modes. Also note that even "
              + "precise mode is not completely precise: the decision whether to compute an aspect "
              + "is decided in the analysis phase, which is not run during 'bazel query'.")
  public AspectResolver.Mode aspectDeps;

  ///////////////////////////////////////////////////////////
  // GRAPH OUTPUT FORMATTER OPTIONS                        //
  ///////////////////////////////////////////////////////////

  @Option(
      name = "graph:node_limit",
      defaultValue = "512",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "The maximum length of the label string for a graph node in the output.  Longer labels"
              + " will be truncated; -1 means no truncation.  This option is only applicable to"
              + " --output=graph.")
  public int graphNodeStringLimit;

  @Option(
      name = "graph:factored",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "If true, then the graph will be emitted 'factored', i.e. topologically-equivalent nodes "
              + "will be merged together and their labels concatenated. This option is only "
              + "applicable to --output=graph.")
  public boolean graphFactored;

  ///////////////////////////////////////////////////////////
  // INPUT / OUTPUT OPTIONS                                //
  ///////////////////////////////////////////////////////////

  @Option(
      name = "query_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.CHANGES_INPUTS},
      help =
          "If set, query will read the query from the file named here, rather than on the command "
              + "line. It is an error to specify a file here as well as a command-line query.")
  public String queryFile;

  @Option(
      name = "output_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.QUERY,
      effectTags = {OptionEffectTag.TERMINAL_OUTPUT},
      help =
          "When specified, query results will be written directly to this file, and nothing will be"
              + " printed to Bazel's standard output stream (stdout). In benchmarks, this is"
              + " generally faster than <code>bazel query &gt; file</code>.")
  public String outputFile;
}
