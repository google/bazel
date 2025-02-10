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

package com.google.devtools.build.lib.starlarkbuildapi.repository;

import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.GlobalMethods;
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

/**
 * The Starlark module containing the definition of {@code repository_rule} function to define a
 * Starlark remote repository.
 */
@GlobalMethods(environment = Environment.BZL)
public interface RepositoryModuleApi {

  @StarlarkMethod(
      name = "repository_rule",
      doc =
          """
          Creates a new repository rule. Store it in a global value, so that it can be loaded and \
          called from a <a href="#module_extension"><code>module_extension()</code></a> \
          implementation function, or used by \
          <a href="../globals/module.html#use_repo_rule"><code>use_repo_rule()</code></a>.
          """,
      parameters = {
        @Param(
            name = "implementation",
            named = true,
            doc =
                "the function that implements this rule. Must have a single parameter, <code><a"
                    + " href=\"../builtins/repository_ctx.html\">repository_ctx</a></code>. The"
                    + " function is called during the loading phase for each instance of the"
                    + " rule."),
        @Param(
            name = "attrs",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                """
                A dictionary to declare all the attributes of the repository rule. It maps from \
                an attribute name to an attribute object (see
                <a href="../toplevel/attr.html"><code>attr</code></a> module). Attributes \
                starting with <code>_</code> are private, and can be used to add an implicit \
                dependency on a label to a file (a repository rule cannot depend on a generated \
                artifact). The attribute <code>name</code> is implicitly added and must not be \
                specified.
                <p>Declared attributes will convert <code>None</code> to the default value.</p>
                """,
            named = true,
            positional = false),
        @Param(
            name = "local",
            defaultValue = "False",
            doc =
                "Indicate that this rule fetches everything from the local system and should be "
                    + "reevaluated at every fetch.",
            named = true,
            positional = false),
        @Param(
            name = "environ",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
            },
            defaultValue = "[]",
            doc = """
                <b>Deprecated</b>. This parameter has been deprecated. Migrate to \
                <code>repository_ctx.getenv</code> instead.<br/>
                Provides a list of environment variable that this repository rule depends \
                on. If an environment variable in that list change, the repository will be \
                refetched.<br/>
                See also <code>--strict_repo_env</code>.
                """,
            named = true,
            positional = false),
        @Param(
            name = "configure",
            defaultValue = "False",
            doc = "Indicate that the repository inspects the system for configuration purpose",
            named = true,
            positional = false),
        @Param(
            name = "remotable",
            defaultValue = "False",
            doc = "Compatible with remote execution",
            named = true,
            positional = false,
            enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC,
            valueWhenDisabled = "False"),
        @Param(
            name = "doc",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the repository rule that can be extracted by documentation "
                    + "generating tools.",
            named = true,
            positional = false)
      },
      useStarlarkThread = true)
  StarlarkCallable repositoryRule(
      StarlarkCallable implementation,
      Object attrs,
      Boolean local,
      Sequence<?> environ, // <String> expected
      Boolean configure,
      Boolean remotable,
      Object doc,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "module_extension",
      doc =
          "Creates a new module extension. Store it in a global value, so that it can be exported"
              + " and used in a MODULE.bazel file with <code><a"
              + " href=\"../globals/module.html#use_extension\">use_extension</a></code>.",
      parameters = {
        @Param(
            name = "implementation",
            named = true,
            doc =
                "The function that implements this module extension. Must take a single parameter,"
                    + " <code><a href=\"../builtins/module_ctx.html\">module_ctx</a></code>. The"
                    + " function is called once at the beginning of a build to determine the set of"
                    + " available repos."),
        @Param(
            name = "tag_classes",
            defaultValue = "{}",
            doc =
                "A dictionary to declare all the tag classes used by the extension. It maps from"
                    + " the name of the tag class to a <code><a"
                    + " href=\"../builtins/tag_class.html\">tag_class</a></code> object.",
            named = true,
            positional = false),
        @Param(
            name = "doc",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the module extension that can be extracted by documentation"
                    + " generating tools.",
            named = true,
            positional = false),
        @Param(
            name = "environ",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
            },
            defaultValue = "[]",
            doc = """
                <b>Deprecated</b>. This parameter has been deprecated. Migrate to \
                <code>module_ctx.getenv</code> instead.
                Provides a list of environment variable that this module extension depends on. If \
                an environment variable in that list changes, the extension will be \
                re-evaluated.<br/>
                See also <code>--strict_repo_env</code>.
                """,
            named = true,
            positional = false),
        @Param(
            name = "os_dependent",
            defaultValue = "False",
            doc = "Indicates whether this extension is OS-dependent or not",
            named = true,
            positional = false),
        @Param(
            name = "arch_dependent",
            defaultValue = "False",
            doc = "Indicates whether this extension is architecture-dependent or not",
            named = true,
            positional = false)
      },
      useStarlarkThread = true)
  Object moduleExtension(
      StarlarkCallable implementation,
      Dict<?, ?> tagClasses, // Dict<String, TagClassApi>
      Object doc, // <String> or Starlark.NONE
      Sequence<?> environ, // <String>
      boolean osDependent,
      boolean archDependent,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "tag_class",
      doc =
          "Creates a new tag_class object, which defines an attribute schema for a class of tags,"
              + " which are data objects usable by a module extension.",
      parameters = {
        @Param(
            name = "attrs",
            defaultValue = "{}",
            named = true,
            doc =
                """
                A dictionary to declare all the attributes of this tag class. It maps from an \
                attribute name to an attribute object (see <a href="../toplevel/attr.html">
                attr</a> module).
                <p>Note that unlike <a href="../globals/bzl.html#rule"><code>rule()</code></a>, \
                <a href="../globals/bzl.html#aspect"><code>aspect()</code></a> and \
                <a href="../globals/bzl.html#repository_rule"><code>repository_rule()</code></a>,
                declared attributes will not convert <code>None</code> to the default value. For \
                the default to be used, the attribute must be omitted entirely by the caller.</p>
                """),
        @Param(
            name = "doc",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the tag class that can be extracted by documentation"
                    + " generating tools.",
            named = true,
            positional = false)
      })
  TagClassApi tagClass(
      Dict<?, ?> attrs, // Dict<String, StarlarkAttrModuleApi.Descriptor>
      Object doc)
      throws EvalException;

  /** Represents a tag class, which is a "class" of tags that share the same attribute schema. */
  @StarlarkBuiltin(
      name = "tag_class",
      category = DocCategory.BUILTIN,
      doc =
          """
          Defines a schema of attributes for a tag, created by \
          <a href="../globals/bzl.html#tag_class"><code>tag_class()</code></a>.
          """)
  interface TagClassApi extends StarlarkValue {}

  @StarlarkMethod(
      name = "__do_not_use_fail_with_incompatible_use_cc_configure_from_rules_cc",
      doc =
          "When --incompatible_use_cc_configure_from_rules_cc is set to true, Bazel will "
              + "fail the build. Please see https://github.com/bazelbuild/bazel/issues/10134 for "
              + "details and migration instructions.",
      documented = false,
      useStarlarkThread = true)
  void failWithIncompatibleUseCcConfigureFromRulesCc(StarlarkThread thread) throws EvalException;
}
