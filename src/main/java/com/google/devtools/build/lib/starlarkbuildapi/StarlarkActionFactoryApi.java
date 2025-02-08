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

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Module providing functions to create actions. */
@StarlarkBuiltin(
    name = "actions",
    category = DocCategory.BUILTIN,
    doc =
        "Module providing functions to create actions. Access this module using <a"
            + " href=\"../builtins/ctx.html#actions\"><code>ctx.actions</code></a>.")
public interface StarlarkActionFactoryApi extends StarlarkValue {

  static final String TRANSFORM_VERSION_FUNC_DOC =
      "A Starlark callback method which takes <code>dict</code> as an input and  returns"
          + " <code>dict</code> as an output. The input <code>dict</code> is generated"
          + " from <a"
          + " href='https://bazel.build/rules/lib/builtins/ctx#version_file'>ctx.version_file</a>."
          + " The output <code>dict</code> needs to contain the user translated key-value"
          + " pairs. Output <code>dict</code> will serve as a substitutions"
          + " dictionary for template expansion.";
  static final String TRANSFORM_INFO_FUNC_DOC =
      "A Starlark callback method which takes <code>dict</code> as an input and  returns"
          + " <code>dict</code> as an output. The input <code>dict</code> is generated"
          + " from <a"
          + " href='https://bazel.build/rules/lib/builtins/ctx#info_file'>ctx.info_file</a>,"
          + " the output <code>dict</code> needs to contain the user translated key-value"
          + " pairs. Output <code>dict</code> will serve as a substitutions"
          + " dictionary for template expansion.";
  static final String TEMPLATE_DOC =
      "A <code>template</code> file according to which the output <code>dict</code> from"
          + " <code>transform_func</code> will be formatted and written to a file.";
  static final String INFO_OUTPUT_DOC =
      "Name of the output file of the action. File will be created under generating rule's"
          + " directory, similar to what <code>ctx.actions.declare_file</code> does.";
  static final String VERSION_OUTPUT_DOC =
      "Name of the output file of the action. File will be created under generating rule's"
          + " directory, similar to what <code>ctx.actions.declare_file</code> does. Due to its"
          + " volatile nature the output file of this action is special cased in Bazel, similar to"
          + " <code>ctx.version_file</code>. Therefore changes in this file will not retrigger"
          + " actions depending on it.";

  @StarlarkMethod(
      name = "declare_file",
      doc =
          "Declares that the rule or aspect creates a file with the given filename. If"
              + " <code>sibling</code> is not specified, the file name is relative to the package"
              + " directory, otherwise the file is in the same directory as <code>sibling</code>."
              + " Files cannot be created outside of the current package.<p>Remember that in"
              + " addition to declaring a file, you must separately create an action that emits the"
              + " file. Creating that action will require passing the returned <code>File</code>"
              + " object to the action's construction function.<p>Note that <a"
              + " href='https://bazel.build/extending/rules#files'>predeclared output files</a> do"
              + " not need to be (and cannot be) declared using this function. You can obtain their"
              + " <code>File</code> objects from <a"
              + " href=\"../builtins/ctx.html#outputs\"><code>ctx.outputs</code></a> instead. <a"
              + " href=\"https://github.com/bazelbuild/examples/tree/main/rules/computed_dependencies/hash.bzl\">See"
              + " example of use</a>.",
      parameters = {
        @Param(
            name = "filename",
            doc =
                "If no 'sibling' provided, path of the new file, relative "
                    + "to the current package. Otherwise a base name for a file "
                    + "('sibling' determines a directory)."),
        @Param(
            name = "sibling",
            doc =
                "A file that lives in the same directory as the newly created file. "
                    + "The file must be in the current package.",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            positional = false,
            named = true,
            defaultValue = "None")
      })
  FileApi declareFile(String filename, Object sibling) throws EvalException;

  @StarlarkMethod(
      name = "declare_directory",
      doc =
          "Declares that the rule or aspect creates a directory with the given name, in the "
              + "current package. You must create an action that generates the directory. "
              + "The contents of the directory are not directly accessible from Starlark, "
              + "but can be expanded in an action command with "
              + "<a href=\"../builtins/Args.html#add_all\"><code>Args.add_all()</code></a>. "
              + "Only regular files and directories can be in the expanded contents of a "
              + "declare_directory.",
      parameters = {
        @Param(
            name = "filename",
            doc =
                "If no 'sibling' provided, path of the new directory, relative "
                    + "to the current package. Otherwise a base name for a file "
                    + "('sibling' defines a directory)."),
        @Param(
            name = "sibling",
            doc =
                "A file that lives in the same directory as the newly declared directory. "
                    + "The file must be in the current package.",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            positional = false,
            named = true,
            defaultValue = "None")
      })
  FileApi declareDirectory(String filename, Object sibling) throws EvalException;

  @StarlarkMethod(
      name = "declare_symlink",
      doc =
          "Declares that the rule or aspect creates a symlink with the given name in the current"
              + " package. You must create an action that generates this symlink. Bazel will never"
              + " dereference this symlink and will transfer it verbatim to sandboxes or remote"
              + " executors. Symlinks inside tree artifacts are not currently supported.",
      parameters = {
        @Param(
            name = "filename",
            doc =
                "If no 'sibling' provided, path of the new symlink, relative "
                    + "to the current package. Otherwise a base name for a file "
                    + "('sibling' defines a directory)."),
        @Param(
            name = "sibling",
            doc = "A file that lives in the same directory as the newly declared symlink.",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            positional = false,
            named = true,
            defaultValue = "None")
      })
  FileApi declareSymlink(String filename, Object sibling) throws EvalException;

  @StarlarkMethod(
      name = "do_nothing",
      doc =
          "Creates an empty action that neither executes a command nor produces any "
              + "output, but that is useful for inserting 'extra actions'.",
      parameters = {
        @Param(
            name = "mnemonic",
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
            },
            named = true,
            positional = false,
            defaultValue = "[]",
            doc = "List of the input files of the action."),
      })
  void doNothing(String mnemonic, Object inputs) throws EvalException;

  @StarlarkMethod(
      name = "symlink",
      doc =
          "Creates an action that writes a symlink in the file system.<p>This function must be"
              + " called with exactly one of <code>target_file</code> or <code>target_path</code>"
              + " specified.</p><p>When you use <code>target_file</code>, declare"
              + " <code>output</code> with <a"
              + " href=\"#declare_file\"><code>declare_file()</code></a> or <a"
              + " href=\"#declare_directory\"><code>declare_directory()</code></a> and match the"
              + " type of <code>target_file</code>. This makes the symlink point to"
              + " <code>target_file</code>. Bazel invalidates the output of this action whenever"
              + " the target of the symlink or its contents change.</p><p>Otherwise, when you use"
              + " <code>target_path</code>, declare <code>output</code> with <a"
              + " href=\"#declare_symlink\"><code>declare_symlink()</code></a>). In this case, the"
              + " symlink points to <code>target_path</code>. Bazel never resolves the symlink"
              + " and the output of this action is invalidated only when the text contents of the"
              + " symlink (that is, the value of <code>readlink()</code>) changes. In particular,"
              + " this can be used to create a dangling symlink.</p>",
      parameters = {
        @Param(
            name = "output",
            named = true,
            positional = false,
            doc = "The output of this action."),
        @Param(
            name = "target_file",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc = "The File that the output symlink will point to."),
        @Param(
            name = "target_path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc =
                "The exact path that the output symlink will point to. No normalization or other "
                    + "processing is applied."),
        @Param(
            name = "is_executable",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "May only be used with <code>target_file</code>, not <code>target_path</code>. "
                    + "If true, when the action is executed, the <code>target_file</code>'s path "
                    + "is checked to confirm that it is executable, and an error is reported if it "
                    + "is not. Setting <code>is_executable</code> to False does not mean the "
                    + "target is not executable, just that no verification is done."
                    + "<p>This feature does not make sense for <code>target_path</code> because "
                    + "dangling symlinks might not exist at build time.</p>"),
        @Param(
            name = "progress_message",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None",
            doc = "Progress message to show to the user during the build."),
        @Param(
            name = "use_exec_root_for_source",
            named = true,
            positional = false,
            defaultValue = "unbound",
            documented = false)
      },
      useStarlarkThread = true)
  void symlink(
      FileApi output,
      Object targetFile,
      Object targetPath,
      Boolean isExecutable,
      Object progressMessage,
      Object useExecRootForSourceObject,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "write",
      doc =
          "Creates a file write action. When the action is executed, it will write the given "
              + "content to a file. This is used to generate files using information available in "
              + "the analysis phase. If the file is large and with a lot of static content, "
              + "consider using <a href=\"#expand_template\"><code>expand_template</code></a>.",
      parameters = {
        @Param(name = "output", doc = "The output file.", named = true),
        @Param(
            name = "content",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = CommandLineArgsApi.class)
            },
            doc =
                "the contents of the file. May be a either a string or an <a"
                    + " href=\"#args\"><code>actions.args()</code></a>"
                    + " object.",
            named = true),
        @Param(
            name = "is_executable",
            defaultValue = "False",
            doc = "Whether the output file should be executable.",
            named = true)
      })
  void write(FileApi output, Object content, Boolean isExecutable)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "run",
      doc =
          "Creates an action that runs an executable. "
              + "<a href=\"https://github.com/bazelbuild/examples/tree/main/rules/"
              + "actions_run/execute.bzl\">See example of use</a>.",
      parameters = {
        @Param(
            name = "outputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
            },
            named = true,
            positional = false,
            doc = "List of the output files of the action."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."),
        @Param(
            name = "unused_inputs_list",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            defaultValue = "None",
            positional = false,
            doc =
                "File containing list of inputs unused by the action. "
                    + ""
                    + "<p>The content of this file (generally one of the outputs of the action) "
                    + "corresponds to  the list of input files that were not used during the whole "
                    + "action execution. Any change in those files must not affect in any way the "
                    + "outputs of the action."),
        @Param(
            name = "executable",
            allowedTypes = {
              @ParamType(type = FileApi.class),
              @ParamType(type = String.class),
              @ParamType(type = FilesToRunProviderApi.class),
            },
            named = true,
            positional = false,
            doc = "The executable file to be called by the action."),
        @Param(
            name = "tools",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = Depset.class),
            },
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "List or depset of any tools needed by the action. Tools are inputs with "
                    + "additional runfiles that are automatically made available to the action. "
                    + "When a list is provided, it can be a heterogenous collection of "
                    + "Files, FilesToRunProvider instances, or depsets of Files. Files which are "
                    + "directly in the list and come from ctx.executable will have their runfiles "
                    + "automatically added. When a depset is provided, it must contain only Files. "
                    + "In both cases, files within depsets are not cross-referenced with "
                    + "ctx.executable for runfiles."),
        @Param(
            name = "arguments",
            // TODO(#13365): improve the @ParamType annotation once it can support multiple
            // contained types.
            allowedTypes = {@ParamType(type = Sequence.class)},
            defaultValue = "[]",
            named = true,
            positional = false,
            doc =
                "Command line arguments of the action. "
                    + "Must be a list of strings or "
                    + "<a href=\"#args\"><code>actions.args()</code></a> objects."),
        @Param(
            name = "mnemonic",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "progress_message",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Progress message to show to the user during the build, for example, \"Compiling"
                    + " foo.cc to create foo.o\". The message may contain <code>%{label}</code>,"
                    + " <code>%{input}</code>, or <code>%{output}</code> patterns, which are"
                    + " substituted with label string, first input, or output's path,"
                    + " respectively. Prefer to use patterns instead of static strings, because"
                    + " the former are more efficient."),
        @Param(
            name = "use_default_shell_env",
            defaultValue = "False",
            named = true,
            positional = false,
            doc =
                "Whether the action should use the default shell environment, which consists of a"
                    + " few OS-dependent variables as well as variables set via <a"
                    + " href=\"/reference/command-line-reference#flag--action_env\"><code>--action_env</code></a>.<p>If"
                    + " both <code>use_default_shell_env</code> and <code>env</code> are set to"
                    + " <code>True</code>, values set in <code>env</code> will overwrite the"
                    + " default shell environment if <a "
                    + "href=\"/reference/command-line-reference#flag--incompatible_merge_fixed_and_default_shell_env\"><code>--incompatible_merge_fixed_and_default_shell_env</code></a>"
                    + " is enabled (default). If the flag is not enabled, <code>env</code> is"
                    + " ignored."),
        @Param(
            name = "env",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Sets the dictionary of environment variables.<p>If both"
                    + " <code>use_default_shell_env</code> and <code>env</code> are set to"
                    + " <code>True</code>, values set in <code>env</code> will overwrite the"
                    + " default shell environment if <a "
                    + "href=\"/reference/command-line-reference#flag--incompatible_merge_fixed_and_default_shell_env\"><code>--incompatible_merge_fixed_and_default_shell_env</code></a>"
                    + " is enabled (default). If the flag is not enabled, <code>env</code> is"
                    + " ignored."),
        @Param(
            name = "execution_requirements",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Information for scheduling the action. See "
                    + "<a href=\"${link common-definitions#common.tags}\">tags</a> "
                    + "for useful keys."),
        @Param(
            // TODO(bazel-team): The name here isn't accurate anymore.
            // This is technically experimental, so folks shouldn't be too attached,
            // but consider renaming to be more accurate/opaque.
            name = "input_manifests",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Legacy argument. Ignored."),
        @Param(
            name = "exec_group",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Runs the action on the given exec group's execution platform. If"
                    + " none, uses the target's default execution platform."),
        @Param(
            name = "shadowed_action",
            allowedTypes = {
              @ParamType(type = ActionApi.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Runs the action using the given shadowed action's inputs and"
                    + " environment added to the action's inputs list and environment. The action"
                    + " environment can overwrite any of the shadowed action's environment"
                    + " variables. If none, uses only the action's inputs and given environment."),
        @Param(
            name = "resource_set",
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "A callback function that returns a resource set dictionary, used to estimate"
                    + " resource usage at execution time if this action is run locally.<p>The"
                    + " function accepts two positional arguments: a string representing an OS name"
                    + " (e.g. \"osx\"), and an integer representing the number of inputs to the"
                    + " action. The returned dictionary may contain the following entries, each of"
                    + " which may be a float or an int:<ul><li>\"cpu\": number of CPUs; default"
                    + " 1<li>\"memory\": in MB; default 250<li>\"local_test\": number of local"
                    + " tests; default 1</ul><p>If this parameter is set to <code>None</code> , the"
                    + " default values are used.<p>The callback must be top-level (lambda and"
                    + " nested functions aren't allowed)."),
        @Param(
            name = "toolchain",
            allowedTypes = {
              @ParamType(type = Label.class),
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "<p>Toolchain type of the executable or tools used in this action.</p><p>If"
                    + " executable and tools are not coming from a toolchain, set this parameter to"
                    + " `None`.</p><p>If executable and tools are coming from a toolchain,"
                    + " toolchain type must be set so that the action executes on the correct"
                    + " execution platform.</p><p>Note that the rule which creates this action"
                    + " needs to define this toolchain inside its 'rule()' function.</p><p>When"
                    + " `toolchain` and `exec_group` parameters are both set, `exec_group` will be"
                    + " used. An error is raised in case the `exec_group` doesn't specify the same"
                    + " toolchain.</p>"),
      })
  void run(
      Sequence<?> outputs,
      Object inputs,
      Object unusedInputsList,
      Object executableUnchecked,
      Object toolsUnchecked,
      Sequence<?> arguments,
      Object mnemonicUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Object execGroupUnchecked,
      Object shadowedAction,
      Object resourceSetUnchecked,
      Object toolchainUnchecked)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "run_shell",
      doc =
          "Creates an action that runs a shell command. "
              + "<a href=\"https://github.com/bazelbuild/examples/tree/main/rules/"
              + "shell_command/rules.bzl\">See example of use</a>.",
      parameters = {
        @Param(
            name = "outputs",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = FileApi.class)},
            named = true,
            positional = false,
            doc = "List of the output files of the action."),
        @Param(
            name = "inputs",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "List or depset of the input files of the action."),
        @Param(
            name = "tools",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = FileApi.class),
              @ParamType(type = Depset.class),
            },
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "List or depset of any tools needed by the action. Tools are inputs with "
                    + "additional runfiles that are automatically made available to the action. "
                    + "The list can contain Files or FilesToRunProvider instances."),
        @Param(
            name = "arguments",
            // TODO(#13365): improve the @ParamType annotation once it can support multiple
            // contained types.
            allowedTypes = {@ParamType(type = Sequence.class)},
            defaultValue = "[]",
            named = true,
            positional = false,
            doc =
                "Command line arguments of the action. Must be a list of strings or "
                    + "<a href=\"#args\"><code>actions.args()</code></a> objects."
                    + ""
                    + "<p>Bazel passes the elements in this attribute as arguments to the command."
                    + "The command can access these arguments using shell variable substitutions "
                    + "such as <code>$1</code>, <code>$2</code>, etc. Note that since Args "
                    + "objects are flattened before indexing, if there is an Args object of "
                    + "unknown size then all subsequent strings will be at unpredictable indices. "
                    + "It may be useful to use <code>$@</code> (to retrieve all arguments) in "
                    + "conjunction with Args objects of indeterminate size."
                    + ""
                    + "<p>In the case where <code>command</code> is a list of strings, this "
                    + "parameter may not be used."),
        @Param(
            name = "mnemonic",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "A one-word description of the action, for example, CppCompile or GoLink."),
        @Param(
            name = "command",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Sequence.class, generic1 = String.class),
            },
            named = true,
            positional = false,
            doc =
                "Shell command to execute. This may either be a string (preferred) or a sequence "
                    + "of strings <b>(deprecated)</b>."
                    + ""
                    + "<p>If <code>command</code> is a string, then it is executed as if by "
                    + "<code>sh -c &lt;command&gt; \"\" &lt;arguments&gt;</code> -- that is, the "
                    + "elements in <code>arguments</code> are made available to the command as "
                    + "<code>$1</code>, <code>$2</code> (or <code>%1</code>, <code>%2</code> if "
                    + "using Windows batch), etc. If <code>arguments</code> contains "
                    + "any <a href=\"#args\"><code>actions.args()</code></a> objects, "
                    + "their contents are appended one by one to the command line, so "
                    + "<code>$</code><i>i</i> can refer to individual strings within an Args "
                    + "object. Note that if an Args object of unknown size is passed as part of "
                    + "<code>arguments</code>, then the strings will be at unknown indices; in "
                    + "this case the <code>$@</code> shell substitution (retrieve all arguments) "
                    + "may be useful."
                    + ""
                    + "<p><b>(Deprecated)</b> If <code>command</code> is a sequence of strings, "
                    + "the first item is the executable to run and the remaining items are its "
                    + "arguments. If this form is used, the <code>arguments</code> parameter must "
                    + "not be supplied. <i>Note that this form is deprecated and will soon "
                    + "be removed. It is disabled with `--incompatible_run_shell_command_string`. "
                    + "Use this flag to verify your code is compatible. </i>"
                    + ""
                    + "<p>Bazel uses the same shell to execute the command as it does for "
                    + "genrules."),
        @Param(
            name = "progress_message",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Progress message to show to the user during the build, for example, \"Compiling"
                    + " foo.cc to create foo.o\". The message may contain <code>%{label}</code>,"
                    + " <code>%{input}</code>, or <code>%{output}</code> patterns, which are"
                    + " substituted with label string, first input, or output's path,"
                    + " respectively. Prefer to use patterns instead of static strings, because"
                    + " the former are more efficient."),
        @Param(
            name = "use_default_shell_env",
            defaultValue = "False",
            named = true,
            positional = false,
            doc =
                "Whether the action should use the default shell environment, which consists of a"
                    + " few OS-dependent variables as well as variables set via <a"
                    + " href=\"/reference/command-line-reference#flag--action_env\"><code>--action_env</code></a>.<p>If"
                    + " both <code>use_default_shell_env</code> and <code>env</code> are set to"
                    + " <code>True</code>, values set in <code>env</code> will overwrite the"
                    + " default shell environment if <a "
                    + "href=\"/reference/command-line-reference#flag--incompatible_merge_fixed_and_default_shell_env\"><code>--incompatible_merge_fixed_and_default_shell_env</code></a>"
                    + " is enabled (default). If the flag is not enabled, <code>env</code> is"
                    + " ignored."),
        @Param(
            name = "env",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Sets the dictionary of environment variables.<p>If both"
                    + " <code>use_default_shell_env</code> and <code>env</code> are set to"
                    + " <code>True</code>, values set in <code>env</code> will overwrite the"
                    + " default shell environment if <a "
                    + "href=\"/reference/command-line-reference#flag--incompatible_merge_fixed_and_default_shell_env\"><code>--incompatible_merge_fixed_and_default_shell_env</code></a>"
                    + " is enabled (default). If the flag is not enabled, <code>env</code> is"
                    + " ignored."),
        @Param(
            name = "execution_requirements",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Information for scheduling the action. See "
                    + "<a href=\"${link common-definitions#common.tags}\">tags</a> "
                    + "for useful keys."),
        @Param(
            // TODO(bazel-team): The name here isn't accurate anymore.
            // This is technically experimental, so folks shouldn't be too attached,
            // but consider renaming to be more accurate/opaque.
            name = "input_manifests",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc = "Legacy argument. Ignored."),
        @Param(
            name = "exec_group",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Runs the action on the given exec group's execution platform. If"
                    + " none, uses the target's default execution platform."),
        @Param(
            name = "shadowed_action",
            allowedTypes = {
              @ParamType(type = ActionApi.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "Runs the action using the given shadowed action's discovered inputs"
                    + " added to the action's inputs list. If none, uses only the action's"
                    + " inputs."),
        @Param(
            name = "resource_set",
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "A callback function for estimating resource usage if run locally. See"
                    + "<a href=\"#run.resource_set\"><code>ctx.actions.run()</code></a>."),
        @Param(
            name = "toolchain",
            allowedTypes = {
              @ParamType(type = Label.class),
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "unbound",
            named = true,
            positional = false,
            doc =
                "<p>Toolchain type of the executable or tools used in this action.</p><p>If"
                    + " executable and tools are not coming from a toolchain, set this parameter to"
                    + " `None`.</p><p>If executable and tools are coming from a toolchain,"
                    + " toolchain type must be set so that the action executes on the correct"
                    + " execution platform.</p><p>Note that the rule which creates this action"
                    + " needs to define this toolchain inside its 'rule()' function.</p><p>When"
                    + " `toolchain` and `exec_group` parameters are both set, `exec_group` will be"
                    + " used. An error is raised in case the `exec_group` doesn't specify the same"
                    + " toolchain.</p>"),
      })
  void runShell(
      Sequence<?> outputs,
      Object inputs,
      Object toolsUnchecked,
      Sequence<?> arguments,
      Object mnemonicUnchecked,
      Object commandUnchecked,
      Object progressMessage,
      Boolean useDefaultShellEnv,
      Object envUnchecked,
      Object executionRequirementsUnchecked,
      Object inputManifestsUnchecked,
      Object execGroupUnchecked,
      Object shadowedAction,
      Object resourceSetUnchecked,
      Object toolchainUnchecked)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "expand_template",
      doc =
          "Creates a template expansion action. When the action is executed, it will "
              + "generate a file based on a template. Parts of the template will be replaced "
              + "using the <code>substitutions</code> dictionary, in the order the substitutions "
              + "are specified. Whenever a key of the dictionary appears in the template (or a "
              + "result of a previous substitution), it is replaced with the associated value. "
              + "There is no special syntax for the keys. You may, for example, use curly braces "
              + "to avoid conflicts (for example, <code>{KEY}</code>). "
              + "<a href=\"https://github.com/bazelbuild/examples/blob/main/rules/"
              + "expand_template/hello.bzl\">"
              + "See example of use</a>.",
      parameters = {
        @Param(
            name = "template",
            named = true,
            positional = false,
            doc = "The template file, which is a UTF-8 encoded text file."),
        @Param(
            name = "output",
            named = true,
            positional = false,
            doc = "The output file, which is a UTF-8 encoded text file."),
        @Param(
            name = "substitutions",
            named = true,
            positional = false,
            defaultValue = "{}",
            doc = "Substitutions to make when expanding the template."),
        @Param(
            name = "is_executable",
            defaultValue = "False",
            named = true,
            positional = false,
            doc = "Whether the output file should be executable."),
        @Param(
            name = "computed_substitutions",
            named = true,
            positional = false,
            allowedTypes = {@ParamType(type = TemplateDictApi.class)},
            defaultValue = "unbound",
            doc = "Substitutions to make when expanding the template.")
      })
  void expandTemplate(
      FileApi template,
      FileApi output,
      Dict<?, ?> substitutionsUnchecked,
      Boolean executable,
      Object computedSubstitutions)
      throws EvalException;

  @StarlarkMethod(
      name = "args",
      doc = "Returns an Args object that can be used to build memory-efficient command lines.",
      useStarlarkThread = true)
  CommandLineArgsApi args(StarlarkThread thread);

  @StarlarkMethod(
      name = "template_dict",
      doc = "Returns a TemplateDict object for memory-efficient template expansion.")
  TemplateDictApi templateDict();

  @StarlarkMethod(
      name = "declare_shareable_artifact",
      parameters = {
        @Param(name = "path"),
        @Param(name = "artifact_root", defaultValue = "unbound"),
      },
      documented = false,
      useStarlarkThread = true)
  FileApi createShareableArtifact(String path, Object root, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "transform_version_file",
      documented = false,
      doc =
          "Similar to <a href=\"#transform_info_file\">transform_info_file</a>, but transforms <a"
              + " href='https://bazel.build/rules/lib/builtins/ctx#version_file'>ctx.version_file</a>.",
      parameters = {
        @Param(
            name = "transform_func",
            doc = TRANSFORM_VERSION_FUNC_DOC,
            allowedTypes = {@ParamType(type = StarlarkFunction.class)},
            positional = false,
            named = true),
        @Param(
            name = "template",
            doc = TEMPLATE_DOC,
            allowedTypes = {@ParamType(type = FileApi.class)},
            positional = false,
            named = true),
        @Param(
            name = "output_file_name",
            doc = VERSION_OUTPUT_DOC,
            allowedTypes = {@ParamType(type = String.class)},
            positional = false,
            named = true),
      },
      useStarlarkThread = true)
  FileApi transformVersionFile(
      Object transformFuncObject,
      Object templateObject,
      String outputFileName,
      StarlarkThread thread)
      throws InterruptedException, EvalException;

  @StarlarkMethod(
      name = "transform_info_file",
      documented = false,
      doc =
          "Transforms <a"
              + " href='https://bazel.build/rules/lib/builtins/ctx#info_file'>ctx.info_file</a> to"
              + " a language-consumable file and writes its contents to <code>output</code>. Keys"
              + " and values are transformed by calling the <code>transform_func</code> Starlark"
              + " method, and the output file format is generated according to the"
              + " <code>template</code>. Use this call to create an action in an auxiliary rule."
              + " Create a single target for an auxiliary rule which is then used as an implicit"
              + " dependency for the main rule. Main rule is the one which needs the transformed"
              + " <code>info_file</code>. The auxiliary rule should also declare"
              + " <code>output</code> file to which transformed content is written. The"
              + " <code>output</code> file is then provided to the dependant main rule. This will"
              + " avoid action conflicts and duplicated file generations.",
      parameters = {
        @Param(
            name = "transform_func",
            doc = TRANSFORM_INFO_FUNC_DOC,
            allowedTypes = {@ParamType(type = StarlarkFunction.class)},
            positional = false,
            named = true),
        @Param(
            name = "template",
            doc = TEMPLATE_DOC,
            allowedTypes = {@ParamType(type = FileApi.class)},
            positional = false,
            named = true),
        @Param(
            name = "output_file_name",
            doc = INFO_OUTPUT_DOC,
            allowedTypes = {@ParamType(type = String.class)},
            positional = false,
            named = true),
      },
      useStarlarkThread = true)
  FileApi transformInfoFile(
      Object transformFuncObject,
      Object templateObject,
      String outputFileName,
      StarlarkThread thread)
      throws InterruptedException, EvalException;
}
