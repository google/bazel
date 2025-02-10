# Copyright 2020 The Bazel Authors. All rights reserved.
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

"""Starlark implementation of cc_import.

We may change the implementation at any moment or even delete this file. Do not
rely on this. Pass the flag --experimental_starlark_cc_import
"""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", "semantics")

CPP_LINK_STATIC_LIBRARY_ACTION_NAME = "c++-link-static-library"

def _perform_error_checks(
        system_provided,
        shared_library_artifact,
        interface_library_artifact):
    # If the shared library will be provided by system during runtime, users are not supposed to
    # specify shared_library.
    if system_provided and shared_library_artifact != None:
        fail("'shared_library' shouldn't be specified when 'system_provided' is true")

    # If a shared library won't be provided by system during runtime and we are linking the shared
    # library through interface library, the shared library must be specified.
    if (not system_provided and shared_library_artifact == None and
        interface_library_artifact != None):
        fail("'shared_library' should be specified when 'system_provided' is false")

    if (shared_library_artifact != None and
        not cc_helper.is_valid_shared_library_artifact(shared_library_artifact)):
        fail("'shared_library' does not produce any cc_import shared_library files (expected .so, .dylib or .dll)")

def _create_archive_action(
        ctx,
        feature_configuration,
        cc_toolchain,
        output_file,
        object_files):
    archiver_path = cc_common.get_tool_for_action(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
    )
    archiver_variables = cc_common.create_link_variables(
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        output_file = output_file.path,
        is_using_linker = False,
    )
    command_line = cc_common.get_memory_inefficient_command_line(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
        variables = archiver_variables,
    )
    args = ctx.actions.args()
    args.add_all(command_line)
    args.add_all(object_files)
    args.use_param_file("@%s", use_always = True)

    env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
        variables = archiver_variables,
    )

    # TODO(bazel-team): PWD=/proc/self/cwd env var is missing, but it is present when an analogous archiving
    # action is created by cc_library
    ctx.actions.run(
        executable = archiver_path,
        toolchain = cc_helper.CPP_TOOLCHAIN_TYPE,
        arguments = [args],
        env = env,
        inputs = depset(
            direct = object_files,
            transitive = [
                cc_toolchain.all_files,
            ],
        ),
        use_default_shell_env = True,
        outputs = [output_file],
        mnemonic = "CppArchive",
    )

def _cc_import_impl(ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )

    _perform_error_checks(
        ctx.attr.system_provided,
        ctx.file.shared_library,
        ctx.file.interface_library,
    )

    pic_static_library = ctx.file.pic_static_library or None
    static_library = ctx.file.static_library or None

    if ctx.files.pic_objects and not pic_static_library:
        lib_name = "lib" + ctx.label.name + ".pic.a"
        pic_static_library = ctx.actions.declare_file(lib_name)
        _create_archive_action(ctx, feature_configuration, cc_toolchain, pic_static_library, ctx.files.pic_objects)

    if ctx.files.objects and not static_library:
        lib_name = "lib" + ctx.label.name + ".a"
        static_library = ctx.actions.declare_file(lib_name)
        _create_archive_action(ctx, feature_configuration, cc_toolchain, static_library, ctx.files.objects)

    not_none_artifact_to_link = False

    # Check if there is something to link, if not skip that part.
    if static_library != None or pic_static_library != None or ctx.file.interface_library != None or ctx.file.shared_library != None:
        not_none_artifact_to_link = True

    linking_context = None
    if not_none_artifact_to_link:
        library_to_link = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            static_library = static_library,
            pic_static_library = pic_static_library,
            interface_library = ctx.file.interface_library,
            dynamic_library = ctx.file.shared_library,
            pic_objects = ctx.files.pic_objects,
            objects = ctx.files.objects,
            alwayslink = ctx.attr.alwayslink,
        )

        linker_input = cc_common.create_linker_input(
            libraries = depset([library_to_link]),
            user_link_flags = depset(ctx.attr.linkopts),
            owner = ctx.label,
        )

        linking_context = cc_common.create_linking_context(
            linker_inputs = depset([linker_input]),
        )

    runtimes_deps = semantics.get_cc_runtimes(ctx, True)
    runtimes_copts = semantics.get_cc_runtimes_copts(ctx)
    compilation_contexts = cc_helper.get_compilation_contexts_from_deps(runtimes_deps)
    (compilation_context, _) = cc_common.compile(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        user_compile_flags = runtimes_copts,
        cc_toolchain = cc_toolchain,
        compilation_contexts = compilation_contexts,
        public_hdrs = ctx.files.hdrs,
        includes = ctx.attr.includes,
        name = ctx.label.name,
    )

    this_cc_info = CcInfo(compilation_context = compilation_context, linking_context = linking_context)
    cc_infos = [this_cc_info]

    for dep in ctx.attr.deps:
        cc_infos.append(dep[CcInfo])
    merged_cc_info = cc_common.merge_cc_infos(direct_cc_infos = [this_cc_info], cc_infos = cc_infos)

    return [merged_cc_info]

cc_import = rule(
    implementation = _cc_import_impl,
    doc = """
<p>
<code>cc_import</code> rules allows users to import precompiled C/C++ libraries.
</p>

<p>
The following are the typical use cases: <br/>

1. Linking a static library
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  static_library = "libmylib.a",
  # If alwayslink is turned on,
  # libmylib.a will be forcely linked into any binary that depends on it.
  # alwayslink = True,
)
</code></pre>

2. Linking a shared library (Unix)
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  shared_library = "libmylib.so",
)
</code></pre>

3. Linking a shared library with interface library

<p>On Unix:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  # libmylib.ifso is an interface library for libmylib.so which will be passed to linker
  interface_library = "libmylib.ifso",
  # libmylib.so will be available for runtime
  shared_library = "libmylib.so",
)
</code></pre>

<p>On Windows:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  # mylib.lib is an import library for mylib.dll which will be passed to linker
  interface_library = "mylib.lib",
  # mylib.dll will be available for runtime
  shared_library = "mylib.dll",
)
</code></pre>

4. Linking a shared library with <code>system_provided=True</code>

<p>On Unix:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  interface_library = "libmylib.ifso", # Or we can also use libmylib.so as its own interface library
  # libmylib.so is provided by system environment, for example it can be found in LD_LIBRARY_PATH.
  # This indicates that Bazel is not responsible for making libmylib.so available.
  system_provided = True,
)
</code></pre>

<p>On Windows:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  # mylib.lib is an import library for mylib.dll which will be passed to linker
  interface_library = "mylib.lib",
  # mylib.dll is provided by system environment, for example it can be found in PATH.
  # This indicates that Bazel is not responsible for making mylib.dll available.
  system_provided = True,
)
</code></pre>

5. Linking to static or shared library

<p>On Unix:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  static_library = "libmylib.a",
  shared_library = "libmylib.so",
)
</code></pre>

<p>On Windows:
<pre><code class="lang-starlark">
cc_import(
  name = "mylib",
  hdrs = ["mylib.h"],
  static_library = "libmylib.lib", # A normal static library
  interface_library = "mylib.lib", # An import library for mylib.dll
  shared_library = "mylib.dll",
)
</code></pre>

<p>The remaining is the same on Unix and Windows:
<pre><code class="lang-starlark">
# first will link to libmylib.a (or libmylib.lib)
cc_binary(
  name = "first",
  srcs = ["first.cc"],
  deps = [":mylib"],
  linkstatic = True, # default value
)

# second will link to libmylib.so (or libmylib.lib)
cc_binary(
  name = "second",
  srcs = ["second.cc"],
  deps = [":mylib"],
  linkstatic = False,
)
</code></pre>

<p>
<code>cc_import</code> supports an include attribute. For example:
<pre><code class="lang-starlark">
cc_import(
  name = "curl_lib",
  hdrs = glob(["vendor/curl/include/curl/*.h"]),
  includes = ["vendor/curl/include"],
  shared_library = "vendor/curl/lib/.libs/libcurl.dylib",
)
</code></pre>
</p>
""",
    attrs = {
        "hdrs": attr.label_list(
            allow_files = True,
            flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
            doc = """
The list of header files published by
this precompiled library to be directly included by sources in dependent rules.""",
        ),
        "static_library": attr.label(allow_single_file = [".a", ".lib"], doc = """
A single precompiled static library.
<p> Permitted file types:
  <code>.a</code>,
  <code>.pic.a</code>
  or <code>.lib</code>
</p>"""),
        "pic_static_library": attr.label(allow_single_file = [".pic.a", ".pic.lib"]),  # TODO: b/320462212 - document this attribute
        "shared_library": attr.label(allow_single_file = True, doc = """
A single precompiled shared library. Bazel ensures it is available to the
binary that depends on it during runtime.
<p> Permitted file types:
  <code>.so</code>,
  <code>.dll</code>
  or <code>.dylib</code>
</p>"""),
        "interface_library": attr.label(
            allow_single_file = [".ifso", ".tbd", ".lib", ".so", ".dylib"],
            doc = """
A single interface library for linking the shared library.
<p> Permitted file types:
  <code>.ifso</code>,
  <code>.tbd</code>,
  <code>.lib</code>,
  <code>.so</code>
  or <code>.dylib</code>
</p>""",
        ),
        "pic_objects": attr.label_list(
            # TODO: b/320462212 - document this attribute
            allow_files = [".o", ".pic.o"],
        ),
        "objects": attr.label_list(
            # TODO: b/320462212 - document this attribute
            allow_files = [".o", ".nopic.o"],
        ),
        "system_provided": attr.bool(default = False, doc = """
If 1, it indicates the shared library required at runtime is provided by the system. In
this case, <code>interface_library</code> should be specified and
<code>shared_library</code> should be empty."""),
        "alwayslink": attr.bool(default = False, doc = """
If 1, any binary that depends (directly or indirectly) on this C++
precompiled library will link in all the object files archived in the static library,
even if some contain no symbols referenced by the binary.
This is useful if your code isn't explicitly called by code in
the binary, e.g., if your code registers to receive some callback
provided by some service.

<p>If alwayslink doesn't work with VS 2017 on Windows, that is due to a
<a href="https://github.com/bazelbuild/bazel/issues/3949">known issue</a>,
please upgrade your VS 2017 to the latest version.</p>"""),
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
</p>"""),
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
The default <code>include</code> path doesn't include generated
files. If you need to <code>#include</code> a generated header
file, list it in the <code>srcs</code>.
</p>
"""),
        "deps": attr.label_list(doc = """
The list of other libraries that the target depends upon.
See general comments about <code>deps</code>
at <a href="${link common-definitions#typical-attributes}">Typical attributes defined by
most build rules</a>."""),
        "data": attr.label_list(
            allow_files = True,
            flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
        ),
        "_use_auto_exec_groups": attr.bool(default = True),
    },
    provides = [CcInfo],
    toolchains = cc_helper.use_cpp_toolchain() + semantics.get_runtimes_toolchain(),
    fragments = ["cpp"],
)
