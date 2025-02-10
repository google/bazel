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

package com.google.devtools.build.lib.starlarkbuildapi;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.Depset.TypeException;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.TransitiveInfoCollectionApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintValueInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ExecGroupCollectionApi;
import com.google.devtools.build.lib.starlarkbuildapi.platform.ToolchainContextApi;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.Structure;
import net.starlark.java.eval.Tuple;

/** Interface for a context object given to rule implementation functions. */
@StarlarkBuiltin(
    name = "ctx",
    category = DocCategory.BUILTIN,
    doc =
        "A context object that is passed to the implementation function for a rule or aspect. It"
            + " provides access to the information and methods needed to analyze the current"
            + " target.<p>In particular, it lets the implementation function access the current"
            + " target's label, attributes, configuration, and the providers of its dependencies."
            + " It has methods for declaring output files and the actions that produce"
            + " them.<p>Context objects essentially live for the duration of the call to the"
            + " implementation function. It is not useful to access these objects outside of their"
            + " associated function. See the <a"
            + " href='https://bazel.build/extending/rules#implementation_function'>Rules page</a>"
            + " for more information.")
public interface StarlarkRuleContextApi<ConstraintValueT extends ConstraintValueInfoApi>
    extends StarlarkValue {

  String EXECUTABLE_DOC =
      "A <code>struct</code> containing executable files defined in <a"
          + " href='../toplevel/attr.html#label'>label type attributes</a> marked as <a"
          + " href='../toplevel/attr.html#label.executable'><code>executable=True</code></a>. The"
          + " struct fields correspond to the attribute names. Each value in the struct is either a"
          + " <a href='../builtins/File.html'><code>File</code></a> or <code>None</code>. If an"
          + " optional attribute is not specified in the rule then the corresponding struct value"
          + " is <code>None</code>. If a label type is not marked as <code>executable=True</code>,"
          + " no corresponding struct field is generated. <a "
          + "href=\"https://github.com/bazelbuild/examples/blob/main/rules/actions_run/execute.bzl\">See"
          + " example of use</a>.";
  String FILES_DOC =
      "A <code>struct</code> containing files defined in <a"
          + " href='../toplevel/attr.html#label'>label</a> or <a"
          + " href='../toplevel/attr.html#label_list'>label list</a> type attributes. The struct"
          + " fields correspond to the attribute names. The struct values are <code>list</code> of"
          + " <a href='../builtins/File.html'><code>File</code></a>s.  It is a shortcut for:<pre"
          + " class=language-python>[f for t in ctx.attr.&lt;ATTR&gt; for f in t.files]</pre> In"
          + " other words, use <code>files</code> to access the <a"
          + " href=\"https://bazel.build/extending/rules#requesting_output_files\"> default"
          + " outputs</a> of a dependency. <a"
          + " href=\"https://github.com/bazelbuild/examples/blob/main/rules/depsets/foo.bzl\">See"
          + " example of use</a>.";
  String FILE_DOC =
      "A <code>struct</code> containing files defined in <a"
          + " href='../toplevel/attr.html#label'>label type attributes</a> marked as <a"
          + " href='../toplevel/attr.html#label.allow_single_file'><code>allow_single_file</code></a>."
          + " The struct fields correspond to the attribute names. The struct value is always a <a"
          + " href='../builtins/File.html'><code>File</code></a> or <code>None</code>. If an"
          + " optional attribute is not specified in the rule then the corresponding struct value"
          + " is <code>None</code>. If a label type is not marked as"
          + " <code>allow_single_file</code>, no corresponding struct field is generated. It is a"
          + " shortcut for:<pre class=language-python>list(ctx.attr.&lt;ATTR&gt;.files)[0]</pre>In"
          + " other words, use <code>file</code> to access the (singular) <a"
          + " href=\"https://bazel.build/extending/rules#requesting_output_files\">default"
          + " output</a> of a dependency. <a"
          + " href=\"https://github.com/bazelbuild/examples/blob/main/rules/expand_template/hello.bzl\">See"
          + " example of use</a>.";
  String ATTR_DOC =
      "A struct to access the values of the <a"
          + " href='https://bazel.build/extending/rules#attributes'>attributes</a>. The values are"
          + " provided by the user (if not, a default value is used). The attributes of the struct"
          + " and the types of their values correspond to the keys and values of the <a"
          + " href='../globals/bzl.html#rule.attrs'><code>attrs</code> dict</a> provided to the <a"
          + " href='../globals/bzl.html#rule'><code>rule</code> function</a>. <a "
          + "href=\"https://github.com/bazelbuild/examples/blob/main/rules/attributes/printer.bzl\">See"
          + " example of use</a>.";
  String SPLIT_ATTR_DOC =
      "A struct to access the values of attributes with split configurations. If the attribute is "
          + "a label list, the value of split_attr is a dict of the keys of the split (as strings) "
          + "to lists of the ConfiguredTargets in that branch of the split. If the attribute is a "
          + "label, then the value of split_attr is a dict of the keys of the split (as strings) "
          + "to single ConfiguredTargets. Attributes with split configurations still appear in the "
          + "attr struct, but their values will be single lists with all the branches of the split "
          + "merged together.";
  String OUTPUTS_DOC =
      "A pseudo-struct containing all the predeclared output files, represented by <a"
          + " href='../builtins/File.html'><code>File</code></a> objects. See the <a"
          + " href='https://bazel.build/extending/rules#files'>Rules page</a> for more information"
          + " and examples.<p>This field does not exist on aspect contexts, since aspects do not"
          + " have predeclared outputs.<p>The fields of this object are defined as follows. It is"
          + " an error if two outputs produce the same field name or have the same label.<ul><li>"
          + " If the rule declares an <a"
          + " href='../globals/bzl.html#rule.outputs'><code>outputs</code></a> dict, then for every"
          + " entry in the dict, there is a field whose name is the key and whose value is the"
          + " corresponding <code>File</code>.<li>For every attribute of type <a"
          + " href='../toplevel/attr.html#output'><code>attr.output</code></a> that the rule"
          + " declares, there is a field whose name is the attribute's name. If the target"
          + " specified a label for that attribute, then the field value is the corresponding"
          + " <code>File</code>; otherwise the field value is <code>None</code>.<li>For every"
          + " attribute of type <a"
          + " href='../toplevel/attr.html#output_list'><code>attr.output_list</code></a> that the"
          + " rule declares, there is a field whose name is the attribute's name. The field value"
          + " is a list of <code>File</code> objects corresponding to the labels given for that"
          + " attribute in the target, or an empty list if the attribute was not specified in the"
          + " target.<li><b>(Deprecated)</b> If the rule is marked <a"
          + " href='../globals/bzl.html#rule.executable'><code>executable</code></a> or <a"
          + " href='../globals/bzl.html#rule.test'><code>test</code></a>, there is a field named"
          + " <code>\"executable\"</code>, which is the default executable. It is recommended that"
          + " instead of using this, you pass another file (either predeclared or not) to the"
          + " <code>executable</code> arg of <a"
          + " href='../providers/DefaultInfo.html'><code>DefaultInfo</code></a>.</ul>";

  @StarlarkMethod(
      name = "actions",
      structField = true,
      doc = "Contains methods for declaring output files and the actions that produce them.")
  StarlarkActionFactoryApi actions();

  @StarlarkMethod(
      name = "super",
      doc = "Experimental: Calls parent's implementation function and returns its providers",
      useStarlarkThread = true)
  Object callParent(StarlarkThread thread) throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "created_actions",
      doc =
          "For rules with <a"
              + " href=\"../globals/bzl.html#rule._skylark_testable\">_skylark_testable</a> set to"
              + " <code>True</code>, this returns an <code>Actions</code> provider representing all"
              + " actions created so far for the current rule. For all other rules, returns"
              + " <code>None</code>. Note that the provider is not updated when subsequent actions"
              + " are created, so you will have to call this function again if you wish to inspect"
              + " them. <br/><br/>This is intended to help write tests for rule-implementation"
              + " helper functions, which may take in a <code>ctx</code> object and create actions"
              + " on it.")
  StarlarkValue createdActions() throws EvalException;

  @StarlarkMethod(name = "attr", structField = true, doc = ATTR_DOC)
  StructApi getAttr() throws EvalException;

  @StarlarkMethod(name = "split_attr", structField = true, doc = SPLIT_ATTR_DOC)
  StructApi getSplitAttr() throws EvalException;

  @StarlarkMethod(name = "executable", structField = true, doc = EXECUTABLE_DOC)
  StructApi getExecutable() throws EvalException;

  @StarlarkMethod(name = "file", structField = true, doc = FILE_DOC)
  StructApi getFile() throws EvalException;

  @StarlarkMethod(name = "files", structField = true, doc = FILES_DOC)
  StructApi getFiles() throws EvalException;

  @StarlarkMethod(
      name = "workspace_name",
      structField = true,
      doc =
          "The name of the workspace, which is effectively the execution root name and runfiles"
              + " prefix for the main repo. If <code>--enable_bzlmod</code> is on, this is the"
              + " fixed string <code>_main</code>. Otherwise, this is the workspace name as defined"
              + " in the WORKSPACE file.")
  String getWorkspaceName() throws EvalException;

  @StarlarkMethod(
      name = "label",
      structField = true,
      doc = "The label of the target currently being analyzed.")
  Label getLabel() throws EvalException;

  @StarlarkMethod(
      name = "fragments",
      structField = true,
      doc = "Allows access to configuration fragments in target configuration.")
  FragmentCollectionApi getFragments() throws EvalException;

  @StarlarkMethod(
      name = "configuration",
      structField = true,
      doc =
          "The default configuration. See the <a href=\"../builtins/configuration.html\">"
              + "configuration</a> type for more details.")
  BuildConfigurationApi getConfiguration() throws EvalException;

  @StarlarkMethod(
      name = "build_setting_value",
      structField = true,
      doc =
          "Value of the build setting represented by the current target. If this isn't the context"
              + " for an instance of a rule that sets the <a href=\""
              + "https://bazel.build/extending/config#rule-parameter\"><code>build_setting</code>"
              + "</a> attribute, reading this is an error.")
  Object getBuildSettingValue() throws EvalException;

  @StarlarkMethod(
      name = "coverage_instrumented",
      doc =
          "Returns whether code coverage instrumentation should be generated when performing"
              + " compilation actions for this rule or, if <code>target</code> is provided, the"
              + " rule specified by that Target. (If a non-rule or a Starlark rule Target is"
              + " provided, this returns False.) Checks if the sources of the current rule (if no"
              + " Target is provided) or the sources of Target should be instrumented based on the"
              + " --instrumentation_filter and --instrument_test_targets config settings. This"
              + " differs from <code>coverage_enabled</code> in the <a"
              + " href=\"../builtins/configuration.html\">configuration</a>, which notes whether"
              + " coverage data collection is enabled for the entire run, but not whether a"
              + " specific target should be instrumented.",
      parameters = {
        @Param(
            name = "target",
            allowedTypes = {
              @ParamType(type = TransitiveInfoCollectionApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            doc = "A Target specifying a rule. If not provided, defaults to the current rule.")
      })
  boolean instrumentCoverage(Object targetUnchecked) throws EvalException;

  @StarlarkMethod(
      name = "features",
      structField = true,
      doc =
          "The set of features that are explicitly enabled by the user for this rule. "
              + "<a href=\"https://github.com/bazelbuild/examples/blob/main/rules/"
              + "features/rule.bzl\">See example of use</a>.")
  ImmutableList<String> getFeatures() throws EvalException;

  @StarlarkMethod(
      name = "disabled_features",
      structField = true,
      doc = "The set of features that are explicitly disabled by the user for this rule.")
  ImmutableList<String> getDisabledFeatures() throws EvalException;

  @StarlarkMethod(
      name = "bin_dir",
      structField = true,
      doc = "The root corresponding to bin directory.")
  FileRootApi getBinDirectory() throws EvalException;

  @StarlarkMethod(
      name = "genfiles_dir",
      structField = true,
      doc = "The root corresponding to genfiles directory.")
  FileRootApi getGenfilesDirectory() throws EvalException;

  @StarlarkMethod(name = "outputs", structField = true, doc = OUTPUTS_DOC)
  Structure outputs() throws EvalException;

  @StarlarkMethod(
      name = "rule",
      structField = true,
      doc =
          "Rule attributes descriptor for the rule that the aspect is applied to."
              + " Only available in aspect implementation functions.")
  StarlarkAttributesCollectionApi rule() throws EvalException;

  @StarlarkMethod(
      name = "aspect_ids",
      structField = true,
      doc =
          "A list of ids for all aspects applied to the target."
              + " Only available in aspect implementation functions.")
  ImmutableList<String> aspectIds() throws EvalException;

  @StarlarkMethod(
      name = "var",
      structField = true,
      doc = "Dictionary (String to String) of configuration variables.")
  Dict<String, String> var() throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "toolchains",
      structField = true,
      doc = "Toolchains for the default exec group of this rule.")
  ToolchainContextApi toolchains() throws EvalException;

  @StarlarkMethod(
      name = "target_platform_has_constraint",
      doc = "Returns true if the given constraint value is part of the current target platform.",
      parameters = {
        @Param(
            name = "constraintValue",
            positional = true,
            named = false,
            doc = "The constraint value to check the target platform against.")
      })
  boolean targetPlatformHasConstraint(ConstraintValueT constraintValue);

  @StarlarkMethod(
      name = "exec_groups",
      structField = true,
      doc =
          "A collection of the execution groups available for this rule,"
              + " indexed by their name. Access with <code>ctx.exec_groups[name_of_group]</code>.")
  ExecGroupCollectionApi execGroups() throws EvalException;

  @StarlarkMethod(
      name = "tokenize",
      doc = "Splits a shell command into a list of tokens.",
      // TODO(cparsons): Look into flipping this to true.
      documented = false,
      parameters = {
        @Param(
            name = "option",
            positional = true,
            named = false,
            doc = "The string to split."),
      })
  Sequence<String> tokenize(String optionString) throws EvalException;

  @StarlarkMethod(
      name = "check_placeholders",
      documented = false,
      parameters = {
        @Param(name = "template", positional = true, named = false, doc = "The template."),
        @Param(
            name = "allowed_placeholders",
            positional = true,
            named = false,
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
            },
            doc = "The allowed placeholders."),
      })
  boolean checkPlaceholders(String template, Sequence<?> allowedPlaceholders) // <String>
      throws EvalException;

  @StarlarkMethod(
      name = "expand_make_variables",
      doc =
          "<b>Deprecated.</b> Use <a href=\"../builtins/ctx.html#var\">ctx.var</a> to access the"
              + " variables instead.<br>Returns a string after expanding all references to \"Make"
              + " variables\". The variables must have the following format:"
              + " <code>$(VAR_NAME)</code>. Also, <code>$$VAR_NAME</code> expands to"
              + " <code>$VAR_NAME</code>. Examples:<pre class=language-python>\n"
              + "ctx.expand_make_variables(\"cmd\", \"$(MY_VAR)\", {\"MY_VAR\": \"Hi\"})  # =="
              + " \"Hi\"\n"
              + "ctx.expand_make_variables(\"cmd\", \"$$PWD\", {})  # == \"$PWD\"\n"
              + "</pre>Additional variables may come from other places, such as configurations."
              + " Note that this function is experimental.",
      parameters = {
        @Param(
            name = "attribute_name",
            positional = true,
            named = false,
            doc = "The attribute name. Used for error reporting."),
        @Param(
            name = "command",
            positional = true,
            named = false,
            doc =
                "The expression to expand. It can contain references to " + "\"Make variables\"."),
        @Param(
            name = "additional_substitutions",
            positional = true,
            named = false,
            doc = "Additional substitutions to make beyond the default make variables."),
      })
  String expandMakeVariables(
      String attributeName,
      String command,
      final Dict<?, ?> additionalSubstitutions) // <String, String>
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "info_file",
      structField = true,
      documented = true,
      doc =
          "The file that is used to hold the non-volatile workspace status for the "
              + "current build request. See documentation for --workspace_status_command "
              + "for more information.")
  FileApi getStableWorkspaceStatus() throws InterruptedException, EvalException;

  @StarlarkMethod(
      name = "version_file",
      structField = true,
      documented = true,
      doc =
          "The file that is used to hold the volatile workspace status for the "
              + "current build request. See documentation for --workspace_status_command "
              + "for more information.")
  FileApi getVolatileWorkspaceStatus() throws InterruptedException, EvalException;

  @StarlarkMethod(
      name = "build_file_path",
      structField = true,
      documented = true,
      doc =
          "Deprecated: Use <code>ctx.label.package + '/BUILD'</code>. "
              + "The path to the BUILD file for this rule, relative to the source root.")
  String getBuildFileRelativePath() throws EvalException;

  @StarlarkMethod(
      name = "expand_location",
      doc =
          "Expands all <code>$(location ...)</code> templates in the given string by replacing "
              + "<code>$(location //x)</code> with the path of the output file of target //x. "
              + "Expansion only works for labels that point to direct dependencies of this rule or "
              + "that are explicitly listed in the optional argument <code>targets</code>. "
              + "<br/><br/>"
              //
              + "<code>$(location ...)</code> will cause an error if the referenced target has "
              + "multiple outputs. In this case, please use <code>$(locations ...)</code> since it "
              + "produces a space-separated list of output paths. It can be safely used for a "
              + "single output file, too."
              + "<br/><br/>"
              //
              + "This function is useful to let the user specify a command in a BUILD file (like"
              + " for <code>genrule</code>). In other cases, it is often better to manipulate"
              + " labels directly.",
      parameters = {
        @Param(name = "input", doc = "String to be expanded."),
        @Param(
            name = "targets",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = TransitiveInfoCollectionApi.class)
            },
            defaultValue = "[]",
            named = true,
            doc =
                "List of targets for additional lookup information. These are expanded as follows:"
                    + " A target with a single file in <code>DefaultInfo.files</code> expands to"
                    + " that file. Other targets expand to their"
                    + " <code>DefaultInfo.executable</code> file if set and if"
                    + " <code>--incompatible_locations_prefers_executable</code> is enabled,"
                    + " otherwise they expand to <code>DefaultInfo.files</code>."),
        @Param(
            name = "short_paths",
            named = true,
            positional = false,
            defaultValue = "False",
            documented = false),
      },
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  String expandLocation(
      String input, Sequence<?> targets, boolean shortPaths, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "runfiles",
      doc = "Creates a runfiles object.",
      parameters = {
        @Param(
            name = "files",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            named = true,
            defaultValue = "[]",
            doc = "The list of files to be added to the runfiles."),
        // TODO(bazel-team): If we have a memory efficient support for lazy list containing
        // NestedSets we can remove this and just use files = [file] + list(set)
        // Also, allow empty set for init
        @Param(
            name = "transitive_files",
            allowedTypes = {
              @ParamType(type = Depset.class, generic1 = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            doc =
                "The (transitive) set of files to be added to the runfiles. The depset should "
                    + "use the <code>default</code> order (which, as the name implies, is the "
                    + "default)."),
        @Param(
            name = "collect_data",
            defaultValue = "False",
            named = true,
            doc =
                "<b>Use of this parameter is not recommended. See <a"
                    + " href=\"https://bazel.build/extending/rules#runfiles\">runfiles"
                    + " guide</a></b>. <p>Whether to collect the data runfiles from the"
                    + " dependencies in srcs, data and deps attributes."),
        @Param(
            name = "collect_default",
            defaultValue = "False",
            named = true,
            doc =
                "<b>Use of this parameter is not recommended. See <a"
                    + " href=\"https://bazel.build/extending/rules#runfiles\">runfiles"
                    + " guide</a></b>. <p>Whether to collect the default runfiles from the"
                    + " dependencies in srcs, data and deps attributes."),
        @Param(
            name = "symlinks",
            defaultValue = "{}",
            named = true,
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = Depset.class, generic1 = SymlinkEntryApi.class)
            },
            doc =
                "Either a SymlinkEntry depset or the map of symlinks to be added to the runfiles."
                    + " Symlinks are always added under the main workspace's runfiles directory"
                    + " (e.g. <code>&lt;runfiles_root>/_main/&lt;symlink_path></code>, <b>not</b>"
                    + " the directory corresponding to the current target's repository. See <a"
                    + " href=\"https://bazel.build/extending/rules#runfiles_symlinks\">Runfiles"
                    + " symlinks</a> in the rules guide."),
        @Param(
            name = "root_symlinks",
            defaultValue = "{}",
            named = true,
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = Depset.class, generic1 = SymlinkEntryApi.class)
            },
            doc =
                "Either a SymlinkEntry depset or a map of symlinks to be added to the runfiles. See"
                    + " <a href=\"https://bazel.build/extending/rules#runfiles_symlinks\">Runfiles"
                    + " symlinks</a> in the rules guide."),
        @Param(
            name = "skip_conflict_checking",
            defaultValue = "False",
            named = true,
            positional = false,
            documented = false)
      },
      useStarlarkThread = true)
  RunfilesApi runfiles(
      Sequence<?> files,
      Object transitiveFiles,
      Boolean collectData,
      Boolean collectDefault,
      Object symlinks,
      Object rootSymlinks,
      boolean skipConflictChecking,
      StarlarkThread thread)
      throws EvalException, TypeException;

  @StarlarkMethod(
      name = "resolve_command",
      doc =
          "<i>(Experimental)</i> Returns a tuple <code>(inputs, command, empty list)</code>"
              + " of the list of resolved inputs and the argv list for the resolved command"
              + " both of them suitable for passing as the same-named arguments of the "
              + " <code>ctx.action</code> method.<br/><b>Note for Windows users</b>: this method"
              + " requires Bash (MSYS2). Consider using <code>resolve_tools()</code> instead (if"
              + " that fits your needs). The empty list is returned as the third member of the"
              + " tuple for backwards compatibility.",
      parameters = {
        @Param(
            name = "command",
            defaultValue = "''",
            named = true,
            positional = false,
            doc = "Command to resolve."),
        @Param(
            name = "attribute",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Name of the associated attribute for which to issue an error, or None."),
        @Param(
            name = "expand_locations",
            defaultValue = "False",
            named = true,
            positional = false,
            doc =
                "Shall we expand $(location) variables? See <a"
                    + " href=\"#expand_location\">ctx.expand_location()</a> for more details."),
        @Param(
            name = "make_variables",
            allowedTypes = {
              @ParamType(type = Dict.class), // <String, String>
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Make variables to expand, or None."),
        @Param(
            name = "tools",
            defaultValue = "[]",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = TransitiveInfoCollectionApi.class),
            },
            named = true,
            positional = false,
            doc = "List of tools (list of targets)."),
        @Param(
            name = "label_dict",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc =
                "Dictionary of resolved labels and the corresponding list of Files "
                    + "(a dict of Label : list of Files)."),
        @Param(
            name = "execution_requirements",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc =
                "Information for scheduling the action to resolve this command. See "
                    + "<a href=\"${link common-definitions#common.tags}\">tags</a> "
                    + "for useful keys."),
      },
      useStarlarkThread = true)
  Tuple resolveCommand(
      String command,
      Object attributeUnchecked,
      Boolean expandLocations,
      Object makeVariablesUnchecked,
      Sequence<?> tools,
      Dict<?, ?> labelDictUnchecked,
      Dict<?, ?> executionRequirementsUnchecked,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "resolve_tools",
      doc =
          "Returns a tuple <code>(inputs, empty list)</code> of the depset of resolved inputs"
              + " required to run the tools, suitable for passing as the same-named argument of the"
              + " <code>ctx.actions.run</code> and <code>ctx.actions.run_shell</code> methods."
              + " <br/><br/>In contrast to <code>ctx.resolve_command</code>, this method does not"
              + " require that Bash be installed on the machine, so it's suitable for rules built"
              + " on Windows. The empty list is returned as part of the tuple for backward"
              + " compatibility.",
      parameters = {
        @Param(
            name = "tools",
            defaultValue = "[]",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = TransitiveInfoCollectionApi.class)
            },
            named = true,
            positional = false,
            doc = "List of tools (list of targets)."),
      })
  Tuple resolveTools(Sequence<?> tools) throws EvalException;
}
