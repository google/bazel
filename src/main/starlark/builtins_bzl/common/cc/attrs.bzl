# Copyright 2022 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Attributes for cc_binary.
"""

load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_shared_library.bzl", "dynamic_deps_attrs")
load(":common/cc/semantics.bzl", "semantics")

cc_internal = _builtins.internal.cc_internal

common_attrs = {
    "srcs": attr.label_list(
        allow_files = True,
        flags = ["DIRECT_COMPILE_TIME_INPUT"],
        doc = """
The list of C and C++ files that are processed to create the library target.
These are C/C++ source and header files, either non-generated (normal source
code) or generated.
<p>All <code>.cc</code>, <code>.c</code>, and <code>.cpp</code> files will
   be compiled. These might be generated files: if a named file is in
   the <code>outs</code> of some other rule, this <code>cc_library</code>
   will automatically depend on that other rule.
</p>
<p>Pure assembler files (.s, .asm) are not preprocessed and are typically built using
the assembler. Preprocessed assembly files (.S) are preprocessed and are typically built
using the C/C++ compiler.
</p>
<p>A <code>.h</code> file will not be compiled, but will be available for
   inclusion by sources in this rule. Both <code>.cc</code> and
   <code>.h</code> files can directly include headers listed in
   these <code>srcs</code> or in the <code>hdrs</code> of this rule or any
   rule listed in the <code>deps</code> argument.
</p>
<p>All <code>#include</code>d files must be mentioned in the
   <code>hdrs</code> attribute of this or referenced <code>cc_library</code>
   rules, or they should be listed in <code>srcs</code> if they are private
   to this library. See <a href="#hdrs">"Header inclusion checking"</a> for
   a more detailed description.
</p>
<p><code>.so</code>, <code>.lo</code>, and <code>.a</code> files are
   pre-compiled files. Your library might have these as
   <code>srcs</code> if it uses third-party code for which we don't
   have source code.
</p>
<p>If the <code>srcs</code> attribute includes the label of another rule,
   <code>cc_library</code> will use the output files of that rule as source files to
   compile. This is useful for one-off generation of source code (for more than occasional
   use, it's better to implement a Starlark rule class and use the <code>cc_common</code>
   API)
</p>
<p>
  Permitted <code>srcs</code> file types:
</p>
<ul>
<li>C and C++ source files: <code>.c</code>, <code>.cc</code>, <code>.cpp</code>,
  <code>.cxx</code>, <code>.c++</code>, <code>.C</code></li>
<li>C and C++ header files: <code>.h</code>, <code>.hh</code>, <code>.hpp</code>,
  <code>.hxx</code>, <code>.inc</code>, <code>.inl</code>, <code>.H</code></li>
<li>Assembler with C preprocessor: <code>.S</code></li>
<li>Archive: <code>.a</code>, <code>.pic.a</code></li>
<li>"Always link" library: <code>.lo</code>, <code>.pic.lo</code></li>
<li>Shared library, versioned or unversioned: <code>.so</code>,
  <code>.so.<i>version</i></code></li>
<li>Object file: <code>.o</code>, <code>.pic.o</code></li>
</ul>

<p>
  ... and any rules that produce those files (e.g. <code>cc_embed_data</code>).
  Different extensions denote different programming languages in
  accordance with gcc convention.
</p>
""",
    ),
    "module_interfaces": attr.label_list(
        allow_files = True,
        doc = """
The list of files are regarded as C++20 Modules Interface.

<p>
C++ Standard has no restriction about module interface file extension
<ul>
<li>Clang use cppm </li>
<li>GCC can use any source file extension </li>
<li>MSVC use ixx </li>
</ul>
</p>
<p>The use is guarded by the flag
<code>--experimental_cpp_modules</code>.</p>
        """,
    ),
    "data": attr.label_list(
        allow_files = True,
        flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
        doc = """
The list of files needed by this library at runtime.

See general comments about <code>data</code>
at <a href="${link common-definitions#typical-attributes}">Typical attributes defined by
most build rules</a>.
<p>If a <code>data</code> is the name of a generated file, then this
   <code>cc_library</code> rule automatically depends on the generating
   rule.
</p>
<p>If a <code>data</code> is a rule name, then this
   <code>cc_library</code> rule automatically depends on that rule,
   and that rule's <code>outs</code> are automatically added to
   this <code>cc_library</code>'s data files.
</p>
<p>Your C++ code can access these data files like so:</p>
<pre><code class="lang-starlark">
  const std::string path = devtools_build::GetDataDependencyFilepath(
      "my/test/data/file");
</code></pre>
""",
    ),
    "includes": attr.string_list(doc = """
List of include dirs to be added to the compile line.
Subject to <a href="${link make-variables}">"Make variable"</a> substitution.
Each string is prepended with the package path and passed to the C++ toolchain for
expansion via the "include_paths" CROSSTOOL feature. A toolchain running on a POSIX system
with typical feature definitions will produce
<code>-isystem path_to_package/include_entry</code>.
This should only be used for third-party libraries that
do not conform to the Google style of writing #include statements.
Unlike <a href="#cc_binary.copts">COPTS</a>, these flags are added for this rule
and every rule that depends on it. (Note: not the rules it depends upon!) Be
very careful, since this may have far-reaching effects.  When in doubt, add
"-I" flags to <a href="#cc_binary.copts">COPTS</a> instead.
<p>
The added <code>include</code> paths will include generated files as well as
files in the source tree.
</p>
"""),
    "defines": attr.string_list(doc = """
List of defines to add to the compile line.
Subject to <a href="${link make-variables}">"Make" variable</a> substitution and
<a href="${link common-definitions#sh-tokenization}">Bourne shell tokenization</a>.
Each string, which must consist of a single Bourne shell token,
is prepended with <code>-D</code> and added to the compile command line to this target,
as well as to every rule that depends on it. Be very careful, since this may have
far-reaching effects.  When in doubt, add define values to
<a href="#cc_binary.local_defines"><code>local_defines</code></a> instead.
"""),
    "local_defines": attr.string_list(doc = """
List of defines to add to the compile line.
Subject to <a href="${link make-variables}">"Make" variable</a> substitution and
<a href="${link common-definitions#sh-tokenization}">Bourne shell tokenization</a>.
Each string, which must consist of a single Bourne shell token,
is prepended with <code>-D</code> and added to the compile command line for this target,
but not to its dependents.
"""),
    "copts": attr.string_list(doc = """
Add these options to the C/C++ compilation command.
Subject to <a href="${link make-variables}">"Make variable"</a> substitution and
<a href="${link common-definitions#sh-tokenization}">Bourne shell tokenization</a>.
<p>
  Each string in this attribute is added in the given order to <code>COPTS</code> before
  compiling the binary target. The flags take effect only for compiling this target, not
  its dependencies, so be careful about header files included elsewhere.
  All paths should be relative to the workspace, not to the current package.
  This attribute should not be needed outside of <code>third_party</code>.
</p>
<p>
  If the package declares the <a href="${link package.features}">feature</a>
  <code>no_copts_tokenization</code>, Bourne shell tokenization applies only to strings
  that consist of a single "Make" variable.
</p>
"""),
    "conlyopts": attr.string_list(doc = """
Add these options to the C compilation command.
Subject to <a href="${link make-variables}">"Make variable"</a> substitution and
<a href="${link common-definitions#sh-tokenization}">Bourne shell tokenization</a>.
"""),
    "cxxopts": attr.string_list(doc = """
Add these options to the C++ compilation command.
Subject to <a href="${link make-variables}">"Make variable"</a> substitution and
<a href="${link common-definitions#sh-tokenization}">Bourne shell tokenization</a>.
"""),
    "hdrs_check": attr.string(
        doc = "Deprecated, no-op.",
    ),
    "additional_linker_inputs": attr.label_list(
        allow_files = True,
        flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
        doc = """
Pass these files to the C++ linker command.
<p>
  For example, compiled Windows .res files can be provided here to be embedded in
  the binary target.
</p>
""",
    ),
    "win_def_file": attr.label(
        allow_single_file = [".def"],
        doc = """
The Windows DEF file to be passed to linker.
<p>This attribute should only be used when Windows is the target platform.
It can be used to <a href="https://msdn.microsoft.com/en-us/library/d91k01sh.aspx">
export symbols</a> during linking a shared library.</p>
""",
    ),
}

linkstatic_doc = """
For <a href="${link cc_binary}"><code>cc_binary</code></a> and
<a href="${link cc_test}"><code>cc_test</code></a>: link the binary in static
mode. For <code>cc_library.link_static</code>: see below.
<p>By default this option is on for <code>cc_binary</code> and off for the rest.</p>
<p>
  If enabled and this is a binary or test, this option tells the build tool to link in
  <code>.a</code>'s instead of <code>.so</code>'s for user libraries whenever possible.
  System libraries such as libc (but <i>not</i> the C/C++ runtime libraries,
  see below) are still linked dynamically, as are libraries for which
  there is no static library. So the resulting executable will still be dynamically
  linked, hence only <i>mostly</i> static.
</p>
<p>
There are really three different ways to link an executable:
</p>
<ul>
<li> STATIC with fully_static_link feature, in which everything is linked statically;
  e.g. "<code>gcc -static foo.o libbar.a libbaz.a -lm</code>".<br/>
  This mode is enabled by specifying <code>fully_static_link</code> in the
  <a href="${link common-definitions#features}"><code>features</code></a> attribute.</li>
<li> STATIC, in which all user libraries are linked statically (if a static
  version is available), but where system libraries (excluding C/C++ runtime libraries)
  are linked dynamically, e.g. "<code>gcc foo.o libfoo.a libbaz.a -lm</code>".<br/>
  This mode is enabled by specifying <code>linkstatic=True</code>.</li>
<li> DYNAMIC, in which all libraries are linked dynamically (if a dynamic version is
  available), e.g. "<code>gcc foo.o libfoo.so libbaz.so -lm</code>".<br/>
  This mode is enabled by specifying <code>linkstatic=False</code>.</li>
</ul>
<p>
If the <code>linkstatic</code> attribute or <code>fully_static_link</code> in
<code>features</code> is used outside of <code>//third_party</code>
please include a comment near the rule to explain why.
</p>
<p>
The <code>linkstatic</code> attribute has a different meaning if used on a
<a href="${link cc_library}"><code>cc_library()</code></a> rule.
For a C++ library, <code>linkstatic=True</code> indicates that only
static linking is allowed, so no <code>.so</code> will be produced. linkstatic=False does
not prevent static libraries from being created. The attribute is meant to control the
creation of dynamic libraries.
</p>
<p>
There should be very little code built with <code>linkstatic=False</code> in production.
If <code>linkstatic=False</code>, then the build tool will create symlinks to
depended-upon shared libraries in the <code>*.runfiles</code> area.
</p>
"""

stamp_doc = """
Whether to encode build information into the binary. Possible values:
<ul>
<li>
  <code>stamp = 1</code>: Always stamp the build information into the binary, even in
  <a href="${link user-manual#flag--stamp}"><code>--nostamp</code></a> builds. <b>This
  setting should be avoided</b>, since it potentially kills remote caching for the
  binary and any downstream actions that depend on it.
</li>
<li>
  <code>stamp = 0</code>: Always replace build information by constant values. This
  gives good build result caching.
</li>
<li>
  <code>stamp = -1</code>: Embedding of build information is controlled by the
  <a href="${link user-manual#flag--stamp}"><code>--[no]stamp</code></a> flag.
</li>
</ul>
<p>Stamped binaries are <em>not</em> rebuilt unless their dependencies change.</p>
""" + semantics.stamp_extra_docs

cc_binary_attrs = common_attrs | {
    "deps": attr.label_list(
        allow_files = semantics.ALLOWED_FILES_IN_DEPS,
        allow_rules = semantics.ALLOWED_RULES_IN_DEPS + semantics.ALLOWED_RULES_WITH_WARNINGS_IN_DEPS,
        flags = ["SKIP_ANALYSIS_TIME_FILETYPE_CHECK"],
        providers = [CcInfo],
        doc = """
The list of other libraries to be linked in to the binary target.
<p>These can be <code>cc_library</code> or <code>objc_library</code>
targets.</p>

It is also allowed to
put linker scripts (.lds) into deps, and reference them in
<a href="#cc_binary.linkopts">linkopts</a>.
""",
    ),
    "reexport_deps": attr.label_list(
        allow_files = True,
        allow_rules = semantics.ALLOWED_RULES_IN_DEPS,
        # TODO(blaze-team): undocumented
    ),
    "linkopts": attr.string_list(doc = """
Add these flags to the C++ linker command.
Subject to <a href="make-variables.html">"Make" variable</a> substitution,
<a href="common-definitions.html#sh-tokenization">
Bourne shell tokenization</a> and
<a href="common-definitions.html#label-expansion">label expansion</a>.
Each string in this attribute is added to <code>LINKOPTS</code> before
linking the binary target.
<p>
    Each element of this list that does not start with <code>$</code> or <code>-</code> is
    assumed to be the label of a target in <code>deps</code>. The
    list of files generated by that target is appended to the linker
    options.  An error is reported if the label is invalid, or is
    not declared in <code>deps</code>.
</p>
"""),
    "nocopts": attr.string(doc = """
Remove matching options from the C++ compilation command.
Subject to <a href="${link make-variables}">"Make" variable</a> substitution.
The value of this attribute is interpreted as a regular expression.
Any preexisting <code>COPTS</code> that match this regular expression
(including values explicitly specified in the rule's <a
href="#cc_binary.copts">copts</a> attribute)
will be removed from <code>COPTS</code> for purposes of compiling this rule.
This attribute should not be needed or used
outside of <code>third_party</code>.  The values are not preprocessed
in any way other than the "Make" variable substitution.
    """),
    "linkstatic": attr.bool(
        default = True,
        doc = linkstatic_doc,
    ),
    "malloc": attr.label(
        default = Label("@" + semantics.get_repo() + "//tools/cpp:malloc"),
        allow_files = False,
        providers = [CcInfo],
        allow_rules = ["cc_library"],
        doc = semantics.malloc_docs,
    ),
    "_default_malloc": attr.label(
        default = configuration_field(fragment = "cpp", name = "custom_malloc"),
    ),
    "link_extra_lib": attr.label(
        default = Label("@" + semantics.get_repo() + "//tools/cpp:link_extra_lib"),
        providers = [CcInfo],
        doc = """
Control linking of extra libraries.
<p>
    By default, C++ binaries are linked against <code>//tools/cpp:link_extra_lib</code>,
    which by default depends on the label flag <code>//tools/cpp:link_extra_libs</code>.
    Without setting the flag, this library is empty by default. Setting the label flag
    allows linking optional dependencies, such as overrides for weak symbols, interceptors
    for shared library functions, or special runtime libraries (for malloc replacements,
    prefer <code>malloc</code> or <code>--custom_malloc</code>). Setting this attribute to
    <code>None</code> disables this behaviour.
</p>
""",
    ),
    "stamp": attr.int(
        values = [-1, 0, 1],
        default = -1,
        doc = stamp_doc,
    ),
    "linkshared": attr.bool(
        default = False,
        doc = """
Create a shared library.
To enable this attribute, include <code>linkshared=True</code> in your rule. By default
this option is off.
<p>
  The presence of this flag means that linking occurs with the <code>-shared</code> flag
  to <code>gcc</code>, and the resulting shared library is suitable for loading into for
  example a Java program. However, for build purposes it will never be linked into the
  dependent binary, as it is assumed that shared libraries built with a
  <a href="#cc_binary">cc_binary</a> rule are only loaded manually by other programs, so
  it should not be considered a substitute for the <a href="#cc_library">cc_library</a>
  rule. For sake of scalability we recommend avoiding this approach altogether and
  simply letting <code>java_library</code> depend on <code>cc_library</code> rules
  instead.
</p>
<p>
  If you specify both <code>linkopts=['-static']</code> and <code>linkshared=True</code>,
  you get a single completely self-contained unit. If you specify both
  <code>linkstatic=True</code> and <code>linkshared=True</code>, you get a single, mostly
  self-contained unit.
</p>
""",
    ),
    "env": attr.string_dict(),
    "distribs": attr.string_list(),
    "licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
    "_cc_binary": attr.bool(),
    "_is_test": attr.bool(default = False),
    "_stl": semantics.get_stl(),
    "_def_parser": semantics.get_def_parser(),
    "_use_auto_exec_groups": attr.bool(default = True),
}

cc_binary_attrs.update(dynamic_deps_attrs)
cc_binary_attrs.update(semantics.get_distribs_attr())
