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
import com.google.devtools.build.docgen.annot.GlobalMethods;
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/** Interface for a module with native rule and package helper functions. */
@StarlarkBuiltin(
    name = "native",
    category = DocCategory.TOP_LEVEL_MODULE,
    doc =
        "A built-in module to support native rules and other package helper functions. "
            + "All native rules appear as functions in this module, e.g. "
            + "<code>native.cc_library</code>. "
            + "Note that the native module is only available in the loading phase "
            + "(i.e. for macros, not for rule implementations). Attributes will ignore "
            + "<code>None</code> values, and treat them as if the attribute was unset.<br>"
            + "The following functions are also available:")
// Methods in this class are also available at the top level in BUILD files.
@GlobalMethods(environment = Environment.BUILD)
public interface StarlarkNativeModuleApi extends StarlarkValue {

  @StarlarkMethod(
      name = "glob",
      doc =
          "Glob returns a new, mutable, sorted list of every file in the current package "
              + "that:<ul>\n"
              + "<li>Matches at least one pattern in <code>include</code>.</li>\n"
              + "<li>Does not match any of the patterns in <code>exclude</code> "
              + "(default <code>[]</code>).</li></ul>\n"
              + "If the <code>exclude_directories</code> argument is enabled "
              + "(set to <code>1</code>), files of type directory will be omitted from the "
              + "results (default <code>1</code>).",
      parameters = {
        @Param(
            name = "include",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            named = true,
            doc = "The list of glob patterns to include."),
        @Param(
            name = "exclude",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            named = true,
            doc = "The list of glob patterns to exclude."),
        // TODO(bazel-team): accept booleans as well as integers? (and eventually migrate?)
        @Param(
            name = "exclude_directories",
            defaultValue = "1", // keep consistent with glob prefetching logic in PackageFactory
            named = true,
            doc = "A flag whether to exclude directories or not."),
        @Param(
            name = "allow_empty",
            defaultValue = "unbound",
            named = true,
            doc =
                "Whether we allow glob patterns to match nothing. If `allow_empty` is False, each"
                    + " individual include pattern must match something and also the final"
                    + " result must be non-empty (after the matches of the `exclude` patterns are"
                    + " excluded).")
      },
      useStarlarkThread = true)
  Sequence<?> glob(
      Sequence<?> include,
      Sequence<?> exclude,
      StarlarkInt excludeDirectories,
      Object allowEmpty,
      StarlarkThread thread)
      throws EvalException, InterruptedException;

  @StarlarkMethod(
      name = "existing_rule",
      doc =
          "Returns an immutable dict-like object that describes the attributes of a rule"
              + " instantiated in this thread's package, or <code>None</code> if no rule instance"
              + " of that name exists." //
              + "<p>Here, an <em>immutable dict-like object</em> means a deeply immutable object"
              + " <code>x</code> supporting dict-like iteration, <code>len(x)</code>, <code>name in"
              + " x</code>, <code>x[name]</code>, <code>x.get(name)</code>, <code>x.items()</code>,"
              + " <code>x.keys()</code>, and <code>x.values()</code>." //
              + "<p>The result contains an entry for each attribute, with the exception of private"
              + " ones (whose names do not start with a letter) and a few unrepresentable legacy"
              + " attribute types. In addition, the dict contains entries for the rule instance's"
              + " <code>name</code> and <code>kind</code> (for example,"
              + " <code>'cc_binary'</code>)." //
              + "<p>The values of the result represent attribute values as follows:" //
              + "<ul><li>Attributes of type str, int, and bool are represented as is.</li>" //
              + "<li>Labels are converted to strings of the form <code>':foo'</code> for targets"
              + " in the same package or <code>'//pkg:name'</code> for targets in a different"
              + " package.</li>" //
              + "<li>Lists are represented as tuples, and dicts are converted to new, mutable"
              + " dicts. Their elements are recursively converted in the same fashion.</li>" //
              + "<li><code>select</code> values are returned with their contents transformed as " //
              + "described above.</li>" //
              + "<li>Attributes for which no value was specified during rule instantiation and"
              + " whose default value is computed are excluded from the result. (Computed defaults"
              + " cannot be computed until the analysis phase.).</li>" //
              + "</ul>" //
              + "<p>If possible, use this function only in <a"
              + " href=\"https://bazel.build/extending/macros#finalizers\">implementation functions"
              + " of rule finalizer symbolic macros</a>. Use of this function in other contexts is"
              + " not recommened, and will be disabled in a future Bazel release; it makes"
              + " <code>BUILD</code> files brittle and order-dependent. Also, beware that it"
              + " differs subtly from the two other conversions of rule attribute values from"
              + " internal form to Starlark: one used by computed defaults, the other used by"
              + " <code>ctx.attr.foo</code>.",
      parameters = {@Param(name = "name", doc = "The name of the target.")},
      useStarlarkThread = true)
  Object existingRule(String name, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "existing_rules",
      doc =
          "Returns an immutable dict-like object describing the rules so far instantiated in this"
              + " thread's package. Each entry of the dict-like object maps the name of the rule"
              + " instance to the result that would be returned by"
              + " <code>existing_rule(name)</code>." //
              + "<p>Here, an <em>immutable dict-like object</em> means a deeply immutable object"
              + " <code>x</code> supporting dict-like iteration, <code>len(x)</code>, <code>name in"
              + " x</code>, <code>x[name]</code>, <code>x.get(name)</code>, <code>x.items()</code>,"
              + " <code>x.keys()</code>, and <code>x.values()</code>." //
              + "<p>If possible, use this function only in <a"
              + " href=\"https://bazel.build/extending/macros#finalizers\">implementation functions"
              + " of rule finalizer symbolic macros</a>. Use of this function in other contexts is"
              + " not recommened, and will be disabled in a future Bazel release; it makes"
              + " <code>BUILD</code> files brittle and order-dependent.",
      useStarlarkThread = true)
  Object existingRules(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "package_group",
      doc =
          "This function defines a set of packages and assigns a label to the group. "
              + "The label can be referenced in <code>visibility</code> attributes.",
      parameters = {
        @Param(
            name = "name",
            named = true,
            positional = false,
            doc = "The unique name for this rule."),
        @Param(
            name = "packages",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
            },
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "A complete enumeration of packages in this group."),
        @Param(
            name = "includes",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            named = true,
            positional = false,
            doc = "Other package groups that are included in this one.")
      },
      useStarlarkThread = true)
  NoneType packageGroup(
      String name, Sequence<?> packages, Sequence<?> includes, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "exports_files",
      doc =
          "Specifies a list of files belonging to this package that are exported to other "
              + "packages.",
      parameters = {
        @Param(
            name = "srcs",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            doc = "The list of files to export."),
        @Param(
            name = "visibility",
            allowedTypes = {
              @ParamType(type = Sequence.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            doc =
                "A visibility declaration can to be specified. The files will be visible to the "
                    + "targets specified. If no visibility is specified, the files will be visible "
                    + "to every package."),
        @Param(
            name = "licenses",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            defaultValue = "None",
            doc = "Licenses to be specified.")
      },
      useStarlarkThread = true)
  NoneType exportsFiles(Sequence<?> srcs, Object visibility, Object licenses, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "package_name",
      doc =
          "The name of the package being evaluated, without the repository name. "
              + "For example, in the BUILD file <code>some/package/BUILD</code>, its value "
              + "will be <code>some/package</code>. "
              + "If the BUILD file calls a function defined in a .bzl file, "
              + "<code>package_name()</code> will match the caller BUILD file package."
              + "The value will always be an empty string for the root package.",
      useStarlarkThread = true)
  String packageName(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "repository_name",
      doc =
          "<strong>Deprecated.</strong> Prefer to use <a"
              + " href=\"#repo_name\"><code>repo_name</code></a> instead, which doesn't contain the"
              + " spurious leading at-sign, but behaves identically otherwise.<p>The canonical name"
              + " of the repository containing the package currently being evaluated, with a single"
              + " at-sign (<code>@</code>) prefixed. For example, in packages that are called into"
              + " existence by the WORKSPACE stanza <code>local_repository(name='local',"
              + " path=...)</code> it will be set to <code>@local</code>. In packages in the main"
              + " repository, it will be set to <code>@</code>.",
      enableOnlyWithFlag = BuildLanguageOptions.INCOMPATIBLE_ENABLE_DEPRECATED_LABEL_APIS,
      useStarlarkThread = true)
  @Deprecated
  String repositoryName(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "repo_name",
      doc =
          "The canonical name of the repository containing the package currently being evaluated,"
              + " with no leading at-signs.",
      useStarlarkThread = true)
  String repoName(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "package_relative_label",
      doc =
          "Converts the input string into a <a href='../builtins/Label.html'>Label</a> object, in"
              + " the context of the package currently being initialized (that is, the"
              + " <code>BUILD</code> file for which the current macro is executing). If the input"
              + " is already a <code>Label</code>, it is returned unchanged.<p>This function may"
              + " only be called while evaluating a BUILD file and the macros it directly or"
              + " indirectly calls; it may not be called in (for instance) a rule implementation"
              + " function. <p>The result of this function is the same <code>Label</code> value as"
              + " would be produced by passing the given string to a label-valued attribute of a"
              + " target declared in the BUILD file. <p><i>Usage note:</i> The difference between"
              + " this function and <a href='../builtins/Label.html#Label'>Label()</a></code> is"
              + " that <code>Label()</code> uses the context of the package of the"
              + " <code>.bzl</code> file that called it, not the package of the <code>BUILD</code>"
              + " file. Use <code>Label()</code> when you need to refer to a fixed target that is"
              + " hardcoded into the macro, such as a compiler. Use"
              + " <code>package_relative_label()</code> when you need to normalize a label string"
              + " supplied by the BUILD file to a <code>Label</code> object. (There is no way to"
              + " convert a string to a <code>Label</code> in the context of a package other than"
              + " the BUILD file or the calling .bzl file. For that reason, outer macros should"
              + " always prefer to pass Label objects to inner macros rather than label strings.)",
      parameters = {
        @Param(
            name = "input",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = Label.class)},
            doc =
                "The input label string or Label object. If a Label object is passed, it's"
                    + " returned as is.")
      },
      useStarlarkThread = true)
  Label packageRelativeLabel(Object input, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "module_name",
      doc =
          "The name of the Bazel module associated with the repo this package is in. If this"
              + " package is from a repo defined in WORKSPACE instead of MODULE.bazel, this is"
              + " empty. For repos generated by module extensions, this is the name of the module"
              + " hosting the extension. It's the same as the <code>module.name</code> field seen"
              + " in <code>module_ctx.modules</code>.",
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  String moduleName(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "module_version",
      doc =
          "The version of the Bazel module associated with the repo this package is in. If this"
              + " package is from a repo defined in WORKSPACE instead of MODULE.bazel, this is"
              + " empty. For repos generated by module extensions, this is the version of the"
              + " module hosting the extension. It's the same as the <code>module.version</code>"
              + " field seen in <code>module_ctx.modules</code>.",
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  String moduleVersion(StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "subpackages",
      doc =
          "Returns a new mutable list of every direct subpackage of the current package,"
              + " regardless of file-system directory depth. List returned is sorted and contains"
              + " the names of subpackages relative to the current package. It is advised to"
              + " prefer using the methods in bazel_skylib.subpackages module rather than calling"
              + " this function directly.",
      parameters = {
        @Param(
            name = "include",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            positional = false,
            named = true,
            doc = "The list of glob patterns to include in subpackages scan."),
        @Param(
            name = "exclude",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            defaultValue = "[]",
            positional = false,
            named = true,
            doc = "The list of glob patterns to exclude from subpackages scan."),
        @Param(
            name = "allow_empty",
            defaultValue = "False",
            positional = false,
            named = true,
            doc =
                "Whether we fail if the call returns an empty list. By default empty list indicates"
                    + " potential error in BUILD file where the call to subpackages() is"
                    + " superflous.  Setting to true allows this function to succeed in that case.")
      },
      useStarlarkThread = true)
  Sequence<?> subpackages(
      Sequence<?> include, Sequence<?> exclude, boolean allowEmpty, StarlarkThread thread)
      throws EvalException, InterruptedException;
}
