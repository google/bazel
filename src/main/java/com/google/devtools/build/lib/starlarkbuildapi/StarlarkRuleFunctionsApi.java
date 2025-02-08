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

import com.google.devtools.build.docgen.annot.GlobalMethods;
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment;
import com.google.devtools.build.docgen.annot.StarlarkConstructor;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.config.StarlarkConfigApi.BuildSettingApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkThread;

/**
 * Interface for a global Starlark library containing rule-related helper and registration
 * functions.
 */
@GlobalMethods(environment = Environment.BZL)
public interface StarlarkRuleFunctionsApi {

  String EXEC_COMPATIBLE_WITH_PARAM = "exec_compatible_with";
  String TOOLCHAINS_PARAM = "toolchains";

  String PROVIDES_DOC =
      "A list of providers that the implementation function must return." //
          + "<p>It is an error if the implementation function omits any of the types of providers"
          + " listed here from its return value. However, the implementation function may return"
          + " additional providers not listed here." //
          + "<p>Each element of the list is an <code>*Info</code> object returned by <a"
          + " href='../globals/bzl.html#provider'><code>provider()</code></a>, except that a legacy"
          + " provider is represented by its string name instead.When a target of the rule is used"
          + " as a dependency for a target that declares a required provider, it is not necessary"
          + " to specify that provider here. It is enough that the implementation function returns"
          + " it. However, it is considered best practice to specify it, even though this is not"
          + " required. The <a"
          + " href='../globals/bzl.html#aspect.required_providers'><code>required_providers</code></a>"
          + " field of an <a href='../globals/bzl.html#aspect'>aspect</a> does, however, require"
          + " that providers are specified here.";

  String DEPENDENCY_RESOLUTION_RULE_DOC =
      "If set, the rule can be a dependency through attributes also marked as available in"
          + " materializers. Every attribute of rules with this flag set must be marked as "
          + " available in materializers also. This is so that rules so marked cannot depend on"
          + " rules that are not so marked.";

  @StarlarkMethod(
      name = "provider",
      doc =
          "Defines a provider symbol. The resulting value of this function must be stored in a"
              + " global value to be usable in a rule or aspect implementation. Providers can be"
              + " instantiated by calling the resulting value as a function, or used directly as"
              + " an index key for retrieving an instance of that provider from a target."
              + " Example:<br><pre class=\"language-python\">" //
              + "MyInfo = provider()\n"
              + "...\n"
              + "def _my_library_impl(ctx):\n"
              + "    ...\n"
              + "    my_info = MyInfo(x = 2, y = 3)\n"
              + "    # my_info.x == 2\n"
              + "    # my_info.y == 3\n"
              + "    ..." //
              + "</pre><p>See <a href='https://bazel.build/extending/rules#providers'>Rules"
              + " (Providers)</a> for a comprehensive guide on how to use providers." //
              + "<p>Returns a <a href='../builtins/Provider.html'><code>Provider</code></a>"
              + " callable value if <code>init</code> is not specified." //
              + "<p>If <code>init</code> is specified, returns a tuple of 2 elements: a <a"
              + " href='../builtins/Provider.html'><code>Provider</code></a> callable value and a"
              + " <em>raw constructor</em> callable value. See <a"
              + " href='https://bazel.build/extending/rules#custom_initialization_of_providers'>"
              + " Rules (Custom initialization of custom providers)</a> and the discussion of the"
              + " <code>init</code> parameter below for details.",
      parameters = {
        @Param(
            name = "doc",
            named = true,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the provider that can be extracted by documentation generating"
                    + " tools."),
        @Param(
            name = "fields",
            doc =
                "If specified, restricts the set of allowed fields. <br>Possible values are:<ul> "
                    + " <li> list of fields:<br>       <pre"
                    + " class=\"language-python\">provider(fields = ['a', 'b'])</pre><p>  <li>"
                    + " dictionary field name -> documentation:<br>       <pre"
                    + " class=\"language-python\">provider(\n"
                    + "       fields = { 'a' : 'Documentation for a', 'b' : 'Documentation for b'"
                    + " })</pre></ul>All fields are optional.",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = String.class),
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            positional = false,
            defaultValue = "None"),
        @Param(
            name = "init",
            doc =
                "An optional callback for preprocessing and validating the provider's field values"
                    + " during instantiation. If <code>init</code> is specified,"
                    + " <code>provider()</code> returns a tuple of 2 elements: the normal provider"
                    + " symbol and a <em>raw constructor</em>." //
                    + "<p>A precise description follows; see <a href='"
                    + "https://bazel.build/extending/rules#custom_initialization_of_providers'>"
                    + "Rules (Custom initialization of providers)</a>"
                    + " for an intuitive discussion and use cases." //
                    + "<p>Let <code>P</code> be the provider symbol created by calling"
                    + " <code>provider()</code>. Conceptually, an instance of <code>P</code> is"
                    + " generated by calling a default constructor function <code>c(*args,"
                    + " **kwargs)</code>, which does the following:" //
                    + "<ul>" //
                    + "<li>If <code>args</code> is non-empty, an error occurs.</li>" //
                    + "<li>If the <code>fields</code> parameter was specified when"
                    + " <code>provider()</code> was called, and if <code>kwargs</code> contains any"
                    + " key that was not listed in <code>fields</code>, an error occurs.</li>" //
                    + "<li>Otherwise, <code>c</code> returns a new instance that has, for each"
                    + " <code>k: v</code> entry in <code>kwargs</code>, a field named"
                    + " <code>k</code> with value <code>v</code>." //
                    + "</ul>" //
                    + "In the case where an <code>init</code> callback is <em>not</em> given, a"
                    + " call to the symbol <code>P</code> itself acts as a call to the default"
                    + " constructor function <code>c</code>; in other words, <code>P(*args,"
                    + " **kwargs)</code> returns <code>c(*args, **kwargs)</code>. For example," //
                    + "<pre class=\"language-python\">" //
                    + "MyInfo = provider()\n" //
                    + "m = MyInfo(foo = 1)" //
                    + "</pre>" //
                    + "will straightforwardly make it so that <code>m</code> is a"
                    + " <code>MyInfo</code> instance with <code>m.foo == 1</code>." //
                    + "<p>But in the case where <code>init</code> is specified, the call"
                    + " <code>P(*args, **kwargs)</code> will perform the following steps"
                    + " instead:" //
                    + "<ol>" //
                    + "<li>The callback is invoked as <code>init(*args, **kwargs)</code>, that is,"
                    + " with the exact same positional and keyword arguments as were passed to"
                    + " <code>P</code>.</li>" //
                    + "<li>The return value of <code>init</code> is expected to be a dictionary,"
                    + " <code>d</code>, whose keys are field name strings. If it is not, an error"
                    + " occurs.</li>" //
                    + "<li>A new instance of <code>P</code> is generated as if by calling the"
                    + " default constructor with <code>d</code>'s entries as keyword arguments, as"
                    + " in <code>c(**d)</code>.</li>" //
                    + "</ol>" //
                    + "<p>NB: the above steps imply that an error occurs if <code>*args</code> or"
                    + " <code>**kwargs</code> does not match <code>init</code>'s signature, or the"
                    + " evaluation of <code>init</code>'s body fails (perhaps intentionally via a"
                    + " call to <a href=\"../globals/all.html#fail\"><code>fail()</code></a>), or"
                    + " if the return value of <code>init</code> is not a dictionary with the"
                    + " expected schema." //
                    + "<p>In this way, the <code>init</code> callback generalizes normal provider"
                    + " construction by allowing positional arguments and arbitrary logic for"
                    + " preprocessing and validation. It does <em>not</em> enable circumventing the"
                    + " list of allowed <code>fields</code>." //
                    + "<p>When <code>init</code> is specified, the return value of"
                    + " <code>provider()</code> becomes a tuple <code>(P, r)</code>, where"
                    + " <code>r</code> is the <em>raw constructor</em>. In fact, the behavior of"
                    + " <code>r</code> is exactly that of the default constructor function"
                    + " <code>c</code> discussed above. Typically, <code>r</code> is bound to a"
                    + " variable whose name is prefixed with an underscore, so that only the"
                    + " current .bzl file has direct access to it:" //
                    + "<pre class=\"language-python\">" //
                    + "MyInfo, _new_myinfo = provider(init = ...)" //
                    + "</pre>",
            named = true,
            allowedTypes = {
              @ParamType(type = StarlarkCallable.class),
              @ParamType(type = NoneType.class),
            },
            positional = false,
            defaultValue = "None"),
      },
      useStarlarkThread = true)
  Object provider(Object doc, Object fields, Object init, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "macro",
      doc =
          """
Defines a symbolic macro, which may be called in <code>BUILD</code> files or macros (legacy or
symbolic) to define targets &ndash; possibly multiple ones.

<p>The value returned by <code>macro(...)</code> must be assigned to a global variable in a .bzl
file; the name of the global variable will be the macro symbol's name.

<p>See <a href="/extending/macros">Macros</a> for a comprehensive guide on how to use symbolic
macros.
""",
      parameters = {
        @Param(
            name = "implementation",
            positional = false,
            named = true,
            doc =
                """
The Starlark function implementing this macro. The values of the macro's attributes are passed to
the implementation function as keyword arguments. The implementation function must have at least two
named parameters, <code>name</code> and <code>visibility</code>, and if the macro inherits
attributes (see <code>inherit_attrs</code> below), it must have a <code>**kwargs</code> residual
keyword parameter.

<p>By convention, the implementation function should have a named parameter for any attribute that
the macro needs to examine, modify, or pass to non-"main" targets, while the "bulk" inherited
attributes which will be passed to the "main" target unchanged are passed as <code>**kwargs</code>.

<p>The implementation function must not return a value. Instead, the implementation function
<em>declares targets</em> by calling rule or macro symbols.

<p>The name of any target or inner symbolic macro declared by a symbolic macro (including by any
Starlark function that the macro's implementation function transitively calls) must either equal
<code>name</code> (this is referred to as the "main" target) or start with <code>name</code>,
followed by a separator chracter (<code>"_"</code>, <code>"-"</code>, or <code>"."</code>) and a
string suffix. (Targets violating this naming scheme are allowed to be declared, but cannot be
built, configured, or depended upon.)

<p>By default, targets declared by a symbolic macro (including by any Starlark function that the
macro's implementation function transitively calls) are visible only in the package containing the
.bzl file defining the macro. To declare targets visible externally, <em>including to the caller of
the symbolic macro</em>, the implementation function must set <code>visibility</code> appropriately
&ndash; typically, by passing <code>visibility = visibility</code> to the rule or macro symbol being
called.

<p>The following APIs are unavailable within a macro implementation function and any Starlark
function it transitively calls:
<ul>
  <li><a href="/reference/be/functions#package"><code>package()</code>, <code>licenses()</code>
  <li><code>environment_group()</code>
  <li><a href="../toplevel/native#glob"><code>native.glob()</code></a> &ndash; instead, you may pass
    a glob into the macro via a label list attribute
  <li><a href="../toplevel/native#subpackages"><code>native.subpackages()</code></a>
  <li>(allowed in rule finalizers only, see <code>finalizer</code> below)
    <a href="../toplevel/native#existing_rules"><code>native.existing_rules()</code></a>,
    <a href="../toplevel/native#existing_rule"><code>native.existing_rule()</code></a>
  <li>(for <code>WORKSPACE</code> threads)
    <a href="../globals/workspace#workspace"><code>workspace()</code></a>,
    <a href="../globals/workspace#register_toolchains"><code>register_toolchains()</code></a>,
    <a href="../globals/workspace#register_execution_platforms><code>register_execution_platforms()</code></a>,
    <a href="../globals/workspace#bind"><code>bind()</code></a>, repository rule instantiation
</ul>
"""),
        @Param(
            name = "attrs",
            allowedTypes = {
              @ParamType(type = Dict.class),
            },
            named = true,
            positional = false,
            defaultValue = "{}",
            doc =
                """
A dictionary of the attributes this macro supports, analogous to
<a href="#rule.attrs">rule.attrs</a>. Keys are attribute names, and values are either attribute
objects like <code>attr.label_list(...)</code> (see the <a href=\"../toplevel/attr.html\">attr</a>
module), or <code>None</code>. A <code>None</code> entry means that the macro does not have an
attribute by that name, even if it would have otherwise inherited one via <code>inherit_attrs</code>
(see below).

<p>The special <code>name</code> attribute is predeclared and must not be included in the
dictionary. The <code>visibility</code> attribute name is reserved and must not be included in the
dictionary.

<p>Attributes whose names start with <code>_</code> are private -- they cannot be passed at the call
site of the rule. Such attributes can be assigned a default value (as in
<code>attr.label(default="//pkg:foo")</code>) to create an implicit dependency on a label.

<p>To limit memory usage, there is a cap on the number of attributes that may be declared.
"""),
        @Param(
            name = "inherit_attrs",
            allowedTypes = {
              @ParamType(type = RuleFunctionApi.class),
              @ParamType(type = MacroFunctionApi.class),
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class)
            },
            positional = false,
            named = true,
            defaultValue = "None",
            doc =
                """
A rule symbol, macro symbol, or the name of a built-in common attribute list (see below) from which
the macro should inherit attributes.

<p>If <code>inherit_attrs</code> is set to the string <code>"common"</code>, the macro will inherit
<a href="/reference/be/common-definitions#common-attributes">common rule attribute definitions</a>
used by all Starlark rules.

<p>Note that if the return value of <code>rule()</code> or <code>macro()</code> was not assigned to
a global variable in a .bzl file, then such a value has not been registered as a rule or macro
symbol, and therefore cannot be used for <code>inherit_attrs</code>.

<p>The inheritance mechanism works as follows:</p>
<ol>
  <li>The special <code>name</code> and <code>visibility</code> attributes are never inherited;
  <li>Hidden attributes (ones whose name starts with <code>"_"</code>) are never inherited;
  <li>Attributes whose names are already defined in the <code>attrs</code> dictionary are never
    inherited (the entry in <code>attrs</code> takes precedence; note that an entry may be set to
    <code>None</code> to ensure that no attribute by that name gets defined on the macro);
  <li>All other attributes are inherited from the rule or macro and effectively merged into the
    <code>attrs</code> dict.
</ol>

<p>When a non-mandatory attribute is inherited, the default value of the attribute is overridden
to be <code>None</code>, regardless of what it was specified in the original rule or macro. This
ensures that when the macro forwards the attribute's value to an instance of the wrapped rule or
macro &ndash; such as by passing in the unmodified <code>**kwargs</code> &ndash; a value that was
absent from the outer macro's call will also be absent in the inner rule or macro's call (since
passing <code>None</code> to an attribute is treated the same as omitting the attribute).
This is important because omitting an attribute has subtly different semantics from passing
its apparent default value. In particular, omitted attributes are not shown in some <code>bazel
query</code> output formats, and computed defaults only execute when the value is omitted. If the
macro needs to examine or modify an inherited attribute &ndash; for example, to add a value to an
inherited <code>tags</code> attribute &ndash; you must make sure to handle the <code>None</code>
case in the macro's implementation function.

<p>For example, the following macro inherits all attributes from <code>native.cc_library</code>,
except for <code>cxxopts</code> (which is removed from the attribute list) and <code>copts</code>
(which is given a new definition). It also takes care to checks for the default <code>None</code>
value of the inherited <code>tags</code> attribute before appending an additional tag.

<pre class="language-python">
def _my_cc_library_impl(name, visibility, tags, **kwargs):
    # Append a tag; tags attr was inherited from native.cc_library, and
    # therefore is None unless explicitly set by the caller of my_cc_library()
    my_tags = (tags or []) + ["my_custom_tag"]
    native.cc_library(
        name = name,
        visibility = visibility,
        tags = my_tags,
        **kwargs
    )

my_cc_library = macro(
    implementation = _my_cc_library_impl,
    inherit_attrs = native.cc_library,
    attrs = {
        "cxxopts": None,
        "copts": attr.string_list(default = ["-D_FOO"]),
    },
)
</pre>

<p>If <code>inherit_attrs</code> is set, the macro's implementation function <em>must</em> have a
<code>**kwargs</code> residual keyword parameter.

<p>By convention, a macro should pass inherited, non-overridden attributes unchanged to the "main"
rule or macro symbol which the macro is wrapping. Typically, most inherited attributes will not have
a parameter in the implementation function's parameter list, and will simply be passed via
<code>**kwargs</code>. It can be convenient for the implementation function to have explicit
parameters for some inherited attributes (most commonly, <code>tags</code> and
<code>testonly</code>) if the macro needs to pass those attributes to both "main" and non-"main"
targets &ndash; but if the macro also needs to examine or manipulate those attributes, you must take
care to handle the <code>None</code> default value of non-mandatory inherited attributes.
"""),
        // TODO: #19922 - Make a concepts page for symbolic macros, migrate some details like the
        // list of disallowed APIs to there.
        // TODO: #19922 - Make good on the above threat of enforcing a cap on the number of
        // attributes.
        // TODO: #19922 - Add a mechanism to optionally automatically pre-populate attrs with
        // common build rule attributes ("tags", "testonly", etc.), or to inherit the list of
        // attributes of a given rule class.
        @Param(
            name = "finalizer",
            positional = false,
            named = true,
            defaultValue = "False",
            doc =
                """
Whether this macro is a rule finalizer, which is a macro that, regardless of its position in a
<code>BUILD</code> file, is evaluated at the end of package loading, after all non-finalizer targets
have been defined.

<p>Unlike ordinary symbolic macros, rule finalizers may call
<a href="../toplevel/native#existing_rule"><code>native.existing_rule()</code></a> and
<a href="../toplevel/native#existing_rules"><code>native.existing_rules()</code></a> to query the
set of <em>non-finalizer</em> rule targets defined in the current package. Note that
<code>native.existing_rule()</code> and <code>native.existing_rules()</code> cannot access the
targets defined by any rule finalizer, including this one.
"""),
        @Param(
            name = "doc",
            positional = false,
            named = true,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the macro that can be extracted by documentation generating "
                    + "tools.")
      },
      useStarlarkThread = true)
  MacroFunctionApi macro(
      StarlarkFunction implementation,
      Dict<?, ?> attrs,
      Object inheritAttrs,
      boolean finalizer,
      Object doc,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "rule",
      doc =
          "Creates a new rule, which can be called from a BUILD file or a macro to create targets."
              + "<p>Rules must be assigned to global variables in a .bzl file; the name of the "
              + "global variable is the rule's name."
              + "<p>Test rules are required to have a name ending in <code>_test</code>, while all "
              + "other rules must not have this suffix. (This restriction applies only to rules, "
              + "not to their targets.)",
      parameters = {
        @Param(
            name = "implementation",
            named = true,
            doc =
                "the Starlark function implementing this rule, must have exactly one parameter: <a"
                    + " href=\"../builtins/ctx.html\">ctx</a>. The function is called during the"
                    + " analysis phase for each instance of the rule. It can access the attributes"
                    + " provided by the user. It must create actions to generate all the declared "
                    + "outputs."),
        @Param(
            name = "test",
            named = true,
            positional = false,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = Boolean.class),
            },
            doc =
                "Whether this rule is a test rule, that is, whether it may be the subject of a"
                    + " <code>blaze test</code> command. All test rules are automatically"
                    + " considered <a href='#rule.executable'>executable</a>; it is unnecessary"
                    + " (and discouraged) to explicitly set <code>executable = True</code> for a"
                    + " test rule. The value defaults to <code>False</code>. See the <a"
                    + " href='https://bazel.build/extending/rules#executable_rules_and_test_rules'>"
                    + " Rules page</a> for more information."),
        @Param(
            name = "attrs",
            allowedTypes = {
              @ParamType(type = Dict.class),
            },
            named = true,
            positional = false,
            defaultValue = "{}",
            doc =
                """
A dictionary to declare all the attributes of the rule. It maps from an attribute \
name to an attribute object (see
<a href="../toplevel/attr.html"><code>attr</code></a> module). Attributes starting \
with <code>_</code> are private, and can be used to add an implicit dependency on \
a label. The attribute <code>name</code> is implicitly added and must not be \
specified. Attributes <code>visibility</code>, <code>deprecation</code>, \
<code>tags</code>, <code>testonly</code>, and <code>features</code> are implicitly \
added and cannot be overridden. Most rules need only a handful of attributes. To \
limit memory usage, there is a cap on the number of attributes that may be \
declared.
<p>Declared attributes will convert <code>None</code> to the default value.</p>
"""),
        // TODO(bazel-team): need to give the types of these builtin attributes
        @Param(
            name = "outputs",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
              @ParamType(type = StarlarkFunction.class) // a function defined in Starlark
            },
            named = true,
            positional = false,
            defaultValue = "None",
            valueWhenDisabled = "None",
            disableWithFlag = BuildLanguageOptions.INCOMPATIBLE_NO_RULE_OUTPUTS_PARAM,
            doc =
                "This parameter has been deprecated. Migrate rules to use"
                    + " <code>OutputGroupInfo</code> or <code>attr.output</code> instead. <p>A"
                    + " schema for defining predeclared outputs. Unlike <a"
                    + " href='../toplevel/attr.html#output'><code>output</code></a> and <a"
                    + " href='../toplevel/attr.html#output_list'><code>output_list</code></a>"
                    + " attributes, the user does not specify the labels for these files. See the"
                    + " <a href='https://bazel.build/extending/rules#files'>Rules page</a> for more"
                    + " on predeclared outputs.<p>The value of this argument is either a dictionary"
                    + " or a callback function that produces a dictionary. The callback works"
                    + " similar to computed dependency attributes: The function's parameter names"
                    + " are matched against the rule's attributes, so for example if you pass"
                    + " <code>outputs = _my_func</code> with the definition <code>def"
                    + " _my_func(srcs, deps): ...</code>, the function has access to the attributes"
                    + " <code>srcs</code> and <code>deps</code>. Whether the dictionary is"
                    + " specified directly or via a function, it is interpreted as follows.<p>Each"
                    + " entry in the dictionary creates a predeclared output where the key is an"
                    + " identifier and the value is a string template that determines the output's"
                    + " label. In the rule's implementation function, the identifier becomes the"
                    + " field name used to access the output's <a"
                    + " href='../builtins/File.html'><code>File</code></a> in <a"
                    + " href='../builtins/ctx.html#outputs'><code>ctx.outputs</code></a>. The"
                    + " output's label has the same package as the rule, and the part after the"
                    + " package is produced by substituting each placeholder of the form"
                    + " <code>\"%{ATTR}\"</code> with a string formed from the value of the"
                    + " attribute <code>ATTR</code>:<ul><li>String-typed attributes are substituted"
                    + " verbatim.<li>Label-typed attributes become the part of the label after the"
                    + " package, minus the file extension. For example, the label"
                    + " <code>\"//pkg:a/b.c\"</code> becomes <code>\"a/b\"</code>.<li>Output-typed"
                    + " attributes become the part of the label after the package, including the"
                    + " file extension (for the above example, <code>\"a/b.c\"</code>).<li>All"
                    + " list-typed attributes (for example, <code>attr.label_list</code>) used in"
                    + " placeholders are required to have <i>exactly one element</i>. Their"
                    + " conversion is the same as their non-list version"
                    + " (<code>attr.label</code>).<li>Other attribute types may not appear in"
                    + " placeholders.<li>The special non-attribute placeholders"
                    + " <code>%{dirname}</code> and <code>%{basename}</code> expand to those parts"
                    + " of the rule's label, excluding its package. For example, in"
                    + " <code>\"//pkg:a/b.c\"</code>, the dirname is <code>a</code> and the"
                    + " basename is <code>b.c</code>.</ul><p>In practice, the most common"
                    + " substitution placeholder is <code>\"%{name}\"</code>. For example, for a"
                    + " target named \"foo\", the outputs dict <code>{\"bin\":"
                    + " \"%{name}.exe\"}</code> predeclares an output named <code>foo.exe</code>"
                    + " that is accessible in the implementation function as"
                    + " <code>ctx.outputs.bin</code>."),
        @Param(
            name = "executable",
            named = true,
            positional = false,
            defaultValue = "unbound",
            allowedTypes = {
              @ParamType(type = Boolean.class),
            },
            doc =
                "Whether this rule is considered executable, that is, whether it may be the subject"
                    + " of a <code>blaze run</code> command. It defaults to <code>False</code>. See"
                    + " the <a"
                    + " href='https://bazel.build/extending/rules#executable_rules_and_test_rules'>"
                    + " Rules page</a> for more information."),
        @Param(
            name = "output_to_genfiles",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "If true, the files will be generated in the genfiles directory instead of the "
                    + "bin directory. Unless you need it for compatibility with existing rules "
                    + "(e.g. when generating header files for C++), do not set this flag."),
        @Param(
            name = "fragments",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "List of names of configuration fragments that the rule requires "
                    + "in target configuration."),
        @Param(
            name = "host_fragments",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "List of names of configuration fragments that the rule requires "
                    + "in host configuration."),
        @Param(
            name = "_skylark_testable",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "<i>(Experimental)</i><br/><br/>If true, this rule will expose its actions for"
                    + " inspection by rules that depend on it via an <code>Actions</code> provider."
                    + " The provider is also available to the rule itself by calling <a"
                    + " href=\"../builtins/ctx.html#created_actions\">ctx.created_actions()</a>."
                    + "<br/><br/>This should only be used for testing the analysis-time behavior of"
                    + " Starlark rules. This flag may be removed in the future."),
        @Param(
            name = TOOLCHAINS_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = Object.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "If set, the set of toolchains this rule requires. The list can contain String,"
                    + " Label, or StarlarkToolchainTypeApi objects, in any combination. Toolchains"
                    + " will be found by checking the current platform, and provided to the rule"
                    + " implementation via <code>ctx.toolchain</code>."),
        @Param(
            name = "incompatible_use_toolchain_transition",
            defaultValue = "False",
            named = true,
            positional = false,
            doc = "Deprecated, this is no longer in use and should be removed."),
        @Param(
            name = "doc",
            named = true,
            positional = false,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the rule that can be extracted by documentation generating "
                    + "tools."),
        @Param(
            name = "provides",
            named = true,
            positional = false,
            defaultValue = "[]",
            doc = PROVIDES_DOC),
        @Param(
            name = "dependency_resolution_rule",
            named = true,
            positional = false,
            defaultValue = "False",
            doc = DEPENDENCY_RESOLUTION_RULE_DOC),
        @Param(
            name = EXEC_COMPATIBLE_WITH_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "A list of constraints on the execution platform that apply to all targets of "
                    + "this rule type."),
        @Param(
            name = "analysis_test",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "If true, then this rule is treated as an analysis test. <p>Note: Analysis test"
                    + " rules are primarily defined using infrastructure provided in core Starlark"
                    + " libraries. See <a"
                    + " href=\"https://bazel.build/rules/testing#testing-rules\">Testing</a> for"
                    + " guidance. <p>If a rule is defined as an analysis test rule, it becomes"
                    + " allowed to use configuration transitions defined using <a"
                    + " href=\"#analysis_test_transition\">analysis_test_transition</a> on its"
                    + " attributes, but opts into some restrictions: <ul><li>Targets of this rule"
                    + " are limited in the number of transitive dependencies they may have. <li>The"
                    + " rule is considered a test rule (as if <code>test=True</code> were set)."
                    + " This supersedes the value of <code>test</code></li> <li>The rule"
                    + " implementation function may not register actions. Instead, it must register"
                    + " a pass/fail result via providing <a"
                    + " href='../providers/AnalysisTestResultInfo.html'>AnalysisTestResultInfo</a>.</li></ul>"),
        @Param(
            name = "build_setting",
            allowedTypes = {
              @ParamType(type = BuildSettingApi.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "If set, describes what kind of <a href='${link"
                    + " config#user-defined-build-settings}'><code>build setting</code></a> this"
                    + " rule is. See the <a href='../toplevel/config.html'><code>config</code></a>"
                    + " module. If this is set, a mandatory attribute named"
                    + " \"build_setting_default\" is automatically added to this rule, with a type"
                    + " corresponding to the value passed in here."),
        @Param(
            name = "cfg",
            defaultValue = "None",
            named = true,
            positional = false,
            doc =
                "If set, points to the configuration transition the rule will "
                    + "apply to its own configuration before analysis."),
        @Param(
            name = "exec_groups",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            defaultValue = "None",
            positional = false,
            doc =
                "Dict of execution group name (string) to <a"
                    + " href='../globals/bzl.html#exec_group'><code>exec_group</code>s</a>. If set,"
                    + " allows rules to run actions on multiple execution platforms within a"
                    + " single target. See <a href='${link exec-groups}'>execution groups"
                    + " documentation</a> for more info."),
        @Param(
            name = "initializer",
            named = true,
            defaultValue = "None",
            positional = false,
            doc =
                "Experimental: the Stalark function initializing the attributes of the rule. "
                    + "<p>The function is called at load time for each instance of the rule. It's "
                    + "called with <code>name</code> and the values of public attributes defined "
                    + "by the rule (not with generic attributes, for example <code>tags</code>). "
                    + "<p>It has to return a dictionary from the attribute names to the desired "
                    + "values. The attributes that are not returned are unaffected. Returning "
                    + "<code>None</code> as value results in using the default value specified in "
                    + "the attribute definition. "
                    + "<p>Initializers are evaluated before the default values specified in "
                    + "an attribute definition. Consequently, if a parameter in the initializer's "
                    + "signature contains a default values, it overwrites the default from the "
                    + "attribute definition (except if returning <code>None</code>). "
                    + "<p>Similarly, if a parameter in the initializer's signature doesn't have a "
                    + "default, the parameter will become mandatory. It's a good practice to omit "
                    + "default/mandatory settings on an attribute definition in such cases. "
                    + "<p>It's a good practice to use <code>**kwargs</code> for attributes that "
                    + "are not handled."
                    + "<p>In case of extended rules, all initializers are called proceeding from "
                    + "child to ancestors. Each initializer is passed only the public attributes "
                    + "it knows about."),
        @Param(
            name = "parent",
            named = true,
            defaultValue = "None",
            positional = false,
            doc =
                "Experimental: the Stalark rule that is extended. When set the public"
                    + " attributes are merged as well as advertised providers. The rule matches"
                    + " <code>executable</code> and <code>test</code> from the parent. Values of"
                    + " <code>fragments</code>, <code>toolchains</code>,"
                    + " <code>exec_compatible_with</code>, and <code>exec_groups</code> are"
                    + " merged. Legacy or deprecated parameters may not be set. Incoming "
                    + "configuration transition <code>cfg</code> of parent is applied after this"
                    + "rule's incoming configuration."),
        @Param(
            name = "extendable",
            named = true,
            defaultValue = "None",
            positional = false,
            allowedTypes = {
              @ParamType(type = Boolean.class),
              @ParamType(type = Label.class),
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            doc =
                "Experimental: A label of an allowlist defining which rules can extending this"
                    + " rule. It can be set also to True/False to always allow/disallow extending."
                    + " Bazel defaults to always allowing extensions."),
        @Param(
            name = "subrules",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = StarlarkSubruleApi.class),
            },
            named = true,
            defaultValue = "[]",
            positional = false,
            doc = "Experimental: List of subrules used by this rule."),
      },
      useStarlarkThread = true)
  StarlarkCallable rule(
      StarlarkFunction implementation,
      Object testUnchecked,
      Dict<?, ?> attrs,
      Object implicitOutputs,
      Object executableUnchecked,
      boolean outputToGenfiles,
      Sequence<?> fragments,
      Sequence<?> hostFragments,
      boolean starlarkTestable,
      Sequence<?> toolchains,
      boolean useToolchainTransition,
      Object doc,
      Sequence<?> providesArg,
      boolean dependencyResolutionRule,
      Sequence<?> execCompatibleWith,
      boolean analysisTest,
      Object buildSetting,
      Object cfg,
      Object execGroups,
      Object initializer,
      Object parentUnchecked,
      Object extendableUnchecked,
      Sequence<?> subrules,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "aspect",
      doc =
          "Creates a new aspect. The result of this function must be stored in a global value."
              + " Please see the <a href=\"https://bazel.build/rules/aspects\">introduction to"
              + " Aspects</a> for more details.",
      parameters = {
        @Param(
            name = "implementation",
            named = true,
            doc =
                "A Starlark function that implements this aspect, with exactly two parameters: <a"
                    + " href=\"../builtins/Target.html\">Target</a> (the target to which the aspect"
                    + " is applied) and <a href=\"../builtins/ctx.html\">ctx</a> (the rule context"
                    + " which the target is created from). Attributes of the target are available"
                    + " via the <code>ctx.rule</code> field. This function is evaluated during the"
                    + " analysis phase for each application of an aspect to a target."),
        @Param(
            name = "attr_aspects",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            defaultValue = "[]",
            doc =
                "List of attribute names. The aspect propagates along dependencies specified in "
                    + "the attributes of a target with these names. Common values here include "
                    + "<code>deps</code> and <code>exports</code>. The list can also contain a "
                    + "single string <code>\"*\"</code> to propagate along all dependencies of a "
                    + "target."),
        @Param(
            name = "toolchains_aspects",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = Object.class)},
            named = true,
            defaultValue = "[]",
            doc =
                "List of toolchain types. The aspect propagates to target"
                    + " toolchains which match these toolchain types."),
        @Param(
            name = "attrs",
            allowedTypes = {
              @ParamType(type = Dict.class),
            },
            named = true,
            defaultValue = "{}",
            doc =
                """
                A dictionary declaring all the attributes of the aspect. It maps from an \
                attribute name to an attribute object, like <code>attr.label</code> or \
                <code>attr.string</code> (see \
                <a href="../toplevel/attr.html"><code>attr</code></a> module). Aspect attributes \
                are available to implementation function as fields of <code>ctx</code> parameter. \
                <p>Implicit attributes starting with <code>_</code> must have default values, and \
                have type <code>label</code> or <code>label_list</code>.</p> \
                <p>Explicit attributes must have type <code>string</code>, and must use the \
                <code>values</code> restriction. Explicit attributes restrict the aspect to only \
                be used with rules that have attributes of the same name, type, and valid values \
                according to the restriction.</p>
                <p>Declared attributes will convert <code>None</code> to the default value.</p>
                """),
        @Param(
            name = "required_providers",
            named = true,
            defaultValue = "[]",
            doc =
                "This attribute allows the aspect to limit its propagation to only the targets "
                    + "whose rules advertise its required providers. The value must be a "
                    + "list containing either individual providers or lists of providers but not "
                    + "both. For example, <code>[[FooInfo], [BarInfo], [BazInfo, QuxInfo]]</code> "
                    + "is a valid value while <code>[FooInfo, BarInfo, [BazInfo, QuxInfo]]</code> "
                    + "is not valid."
                    + ""
                    + "<p>An unnested list of providers will automatically be converted to a list "
                    + "containing one list of providers. That is, <code>[FooInfo, BarInfo]</code> "
                    + "will automatically be converted to <code>[[FooInfo, BarInfo]]</code>."
                    + ""
                    + "<p>To make some rule (e.g. <code>some_rule</code>) targets visible to an "
                    + "aspect, <code>some_rule</code> must advertise all providers from at least "
                    + "one of the required providers lists. For example, if the "
                    + "<code>required_providers</code> of an aspect are "
                    + "<code>[[FooInfo], [BarInfo], [BazInfo, QuxInfo]]</code>, this aspect can "
                    + "see <code>some_rule</code> targets if and only if "
                    + "<code>some_rule</code> provides <code>FooInfo</code>, <em>or</em> "
                    + "<code>BarInfo</code>, <em>or</em> both <code>BazInfo</code> <em>and</em> "
                    + "<code>QuxInfo</code>."),
        @Param(
            name = "required_aspect_providers",
            named = true,
            defaultValue = "[]",
            doc =
                "This attribute allows this aspect to inspect other aspects. The value must be a "
                    + "list containing either individual providers or lists of providers but not "
                    + "both. For example, <code>[[FooInfo], [BarInfo], [BazInfo, QuxInfo]]</code> "
                    + "is a valid value while <code>[FooInfo, BarInfo, [BazInfo, QuxInfo]]</code> "
                    + "is not valid."
                    + ""
                    + "<p>An unnested list of providers will automatically be converted to a list "
                    + "containing one list of providers. That is, "
                    + "<code>[FooInfo, BarInfo]</code> will automatically be converted to "
                    + "<code>[[FooInfo, BarInfo]]</code>. "
                    + ""
                    + "<p>To make another aspect (e.g. <code>other_aspect</code>) visible to this "
                    + "aspect, <code>other_aspect</code> must provide all providers from at least "
                    + "one of the lists. In the example of "
                    + "<code>[[FooInfo], [BarInfo], [BazInfo, QuxInfo]]</code>, this aspect can "
                    + "see <code>other_aspect</code> if and only if <code>other_aspect</code> "
                    + "provides <code>FooInfo</code>, <em>or</em> <code>BarInfo</code>, "
                    + "<em>or</em> both <code>BazInfo</code> <em>and</em> <code>QuxInfo</code>."),
        @Param(name = "provides", named = true, defaultValue = "[]", doc = PROVIDES_DOC),
        @Param(
            name = "requires",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = StarlarkAspectApi.class)},
            named = true,
            defaultValue = "[]",
            doc = "List of aspects required to be propagated before this aspect."),
        @Param(
            name = "fragments",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            defaultValue = "[]",
            doc =
                "List of names of configuration fragments that the aspect requires "
                    + "in target configuration."),
        @Param(
            name = "host_fragments",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            defaultValue = "[]",
            doc =
                "List of names of configuration fragments that the aspect requires "
                    + "in host configuration."),
        @Param(
            name = TOOLCHAINS_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = Object.class)},
            named = true,
            defaultValue = "[]",
            doc =
                "If set, the set of toolchains this aspect requires. The list can contain String,"
                    + " Label, or StarlarkToolchainTypeApi objects, in any combination. Toolchains"
                    + " will be found by checking the current platform, and provided to the aspect"
                    + " implementation via <code>ctx.toolchain</code>."),
        @Param(
            name = "incompatible_use_toolchain_transition",
            defaultValue = "False",
            named = true,
            doc = "Deprecated, this is no longer in use and should be removed."),
        @Param(
            name = "doc",
            named = true,
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = NoneType.class),
            },
            defaultValue = "None",
            doc =
                "A description of the aspect that can be extracted by documentation generating "
                    + "tools."),
        @Param(
            name = "apply_to_generating_rules",
            named = true,
            positional = false,
            defaultValue = "False",
            doc =
                "If true, the aspect will, when applied to an output file, instead apply to the "
                    + "output file's generating rule. "
                    + "<p>For example, suppose an aspect propagates transitively through attribute "
                    + "`deps` and it is applied to target `alpha`. Suppose `alpha` has "
                    + "`deps = [':beta_output']`, where `beta_output` is a declared output of "
                    + "a target `beta`. Suppose `beta` has a target `charlie` as one of its "
                    + "`deps`. If `apply_to_generating_rules=True` for the aspect, then the aspect "
                    + "will propagate through `alpha`, `beta`, and `charlie`. If False, then the "
                    + "aspect will propagate only to `alpha`. </p><p>False by default.</p>"),
        @Param(
            name = EXEC_COMPATIBLE_WITH_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "A list of constraints on the execution platform that apply to all instances of"
                    + " this aspect."),
        @Param(
            name = "exec_groups",
            allowedTypes = {
              @ParamType(type = Dict.class),
              @ParamType(type = NoneType.class),
            },
            named = true,
            defaultValue = "None",
            positional = false,
            doc =
                "Dict of execution group name (string) to <a"
                    + " href='../globals/bzl.html#exec_group'><code>exec_group</code>s</a>. If set,"
                    + " allows aspects to run actions on multiple execution platforms within a"
                    + " single instance. See <a href='${link exec-groups}'>execution groups"
                    + " documentation</a> for more info."),
        @Param(
            name = "subrules",
            allowedTypes = {
              @ParamType(type = Sequence.class, generic1 = StarlarkSubruleApi.class),
            },
            named = true,
            defaultValue = "[]",
            positional = false,
            doc = "Experimental: list of subrules used by this aspect.")
      },
      useStarlarkThread = true)
  StarlarkAspectApi aspect(
      StarlarkFunction implementation,
      Sequence<?> attributeAspects,
      Sequence<?> toolchainsAspects,
      Dict<?, ?> attrs,
      Sequence<?> requiredProvidersArg,
      Sequence<?> requiredAspectProvidersArg,
      Sequence<?> providesArg,
      Sequence<?> requiredAspects,
      Sequence<?> fragments,
      Sequence<?> hostFragments,
      Sequence<?> toolchains,
      boolean useToolchainTransition,
      Object doc,
      Boolean applyToGeneratingRules,
      Sequence<?> execCompatibleWith,
      Object execGroups,
      Sequence<?> subrules,
      StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "Label",
      doc =
          "Converts a label string into a <code>Label</code> object, in the context of the package"
              + " where the calling <code>.bzl</code> source file lives. If the given value is"
              + " already a <code>Label</code>, it is returned unchanged.<p>For macros, a related"
              + " function, <code><a"
              + " href='../toplevel/native.html#package_relative_label'>native.package_relative_label()</a></code>,"
              + " converts the input into a <code>Label</code> in the context of the package"
              + " currently being constructed. Use that function to mimic the string-to-label"
              + " conversion that is automatically done by label-valued rule attributes.",
      parameters = {
        @Param(
            name = "input",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = Label.class)},
            doc =
                "The input label string or Label object. If a Label object is passed, it's"
                    + " returned as is.")
      },
      useStarlarkThread = true)
  @StarlarkConstructor
  Label label(Object input, StarlarkThread thread) throws EvalException;

  @StarlarkMethod(
      name = "exec_group",
      doc =
          "Creates an <a href='${link exec-groups}'>execution group</a> which can be used to"
              + " create actions for a specific execution platform during rule implementation.",
      parameters = {
        @Param(
            name = TOOLCHAINS_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = Object.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "The set of toolchains this execution group requires. The list can contain String,"
                    + " Label, or StarlarkToolchainTypeApi objects, in any combination."),
        @Param(
            name = EXEC_COMPATIBLE_WITH_PARAM,
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc = "A list of constraints on the execution platform."),
      },
      useStarlarkThread = true)
  ExecGroupApi execGroup(
      Sequence<?> toolchains, Sequence<?> execCompatibleWith, StarlarkThread thread)
      throws EvalException;

  @StarlarkMethod(
      name = "subrule",
      doc =
          "Constructs a new instance of a subrule. The result of this function must be stored in "
              + "a global variable before it can be used.",
      parameters = {
        @Param(
            name = "implementation",
            doc = "The Starlark function implementing this subrule",
            named = true,
            positional = false,
            allowedTypes = {@ParamType(type = StarlarkFunction.class)}),
        @Param(
            name = "attrs",
            allowedTypes = {@ParamType(type = Dict.class)},
            named = true,
            positional = false,
            defaultValue = "{}",
            doc =
                "A dictionary to declare all the (private) attributes of the subrule. "
                    + "<p/>Subrules may only have private attributes that are label-typed (i.e. "
                    + "label or label-list). The resolved values corresponding to these labels are"
                    + " automatically passed by Bazel to the subrule's implementation function as"
                    + " named arguments (thus the implementation function is required to accept"
                    + " named parameters matching the attribute names). The types of these values"
                    + " will be: "
                    + "<ul><li><code>FilesToRunProvider</code> for label attributes with"
                    + " <code>executable=True</code></li>"
                    + "<li><code>File</code> for label attributes"
                    + " with <code>allow_single_file=True</code></li>"
                    + "<li><code>Target</code> for"
                    + " all other label attributes</li>"
                    + "<li><code>[Target]</code> for all label-list"
                    + " attributes</li></ul>"),
        @Param(
            name = "toolchains",
            allowedTypes = {@ParamType(type = Sequence.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "If set, the set of toolchains this subrule requires. The list can contain String,"
                    + " Label, or StarlarkToolchainTypeApi objects, in any combination. Toolchains"
                    + " will be found by checking the current platform, and provided to the subrule"
                    + " implementation via <code>ctx.toolchains</code>. Note that AEGs need to be"
                    + " enabled on the consuming rule(s) if this parameter is set. In case you"
                    + " haven't migrated to AEGs yet, see"
                    + " https://bazel.build/extending/auto-exec-groups#migration-aegs."),
        @Param(
            name = "fragments",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = String.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc =
                "List of names of configuration fragments that the subrule requires in target"
                    + " configuration."),
        @Param(
            name = "subrules",
            allowedTypes = {@ParamType(type = Sequence.class, generic1 = StarlarkSubruleApi.class)},
            named = true,
            positional = false,
            defaultValue = "[]",
            doc = "List of other subrules needed by this subrule.")
      },
      useStarlarkThread = true)
  StarlarkSubruleApi subrule(
      StarlarkFunction implementation,
      Dict<?, ?> attrs,
      Sequence<?> toolchains,
      Sequence<?> fragments,
      Sequence<?> subrules,
      StarlarkThread thread)
      throws EvalException;
}
