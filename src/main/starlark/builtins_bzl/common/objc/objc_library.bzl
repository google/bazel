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

"""objc_library Starlark implementation replacing native"""

load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", cc_semantics = "semantics")
load(":common/objc/attrs.bzl", "common_attrs")
load(":common/objc/compilation_support.bzl", "compilation_support")
load(":common/objc/objc_common.bzl", "extensions")
load(":common/objc/semantics.bzl", "semantics")

objc_internal = _builtins.internal.objc_internal
coverage_common = _builtins.toplevel.coverage_common

def _attribute_error(attr_name, msg):
    fail("in attribute '" + attr_name + "': " + msg)

def _validate_attributes(srcs, non_arc_srcs, label):
    cc_helper.check_file_extensions(
        srcs,
        extensions.SRCS,
        "srcs",
        label,
        "objc_library",
        False,
    )
    cc_helper.check_file_extensions(
        non_arc_srcs,
        extensions.NON_ARC_SRCS,
        "non_arc_srcs",
        label,
        "objc_library",
        False,
    )

    if label.name.find("/") != -1:
        _attribute_error("name", "this attribute has unsupported character '/'")

def _objc_library_impl(ctx):
    """Implementation of objc_library."""

    _validate_attributes(srcs = ctx.attr.srcs, non_arc_srcs = ctx.attr.non_arc_srcs, label = ctx.label)

    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    semantics.check_toolchain_supports_objc_compile(ctx, cc_toolchain)

    common_variables = compilation_support.build_common_variables(
        ctx = ctx,
        toolchain = cc_toolchain,
        use_pch = True,
        deps = ctx.attr.deps,
        implementation_deps = ctx.attr.implementation_deps,
        attr_linkopts = ctx.attr.linkopts,
        alwayslink = ctx.fragments.objc.target_should_alwayslink(ctx),
    )
    files = []
    if common_variables.compilation_artifacts.archive != None:
        files.append(common_variables.compilation_artifacts.archive)

    compilation_result = compilation_support.register_compile_and_archive_actions(
        common_variables,
    )
    (compilation_context, linking_context, compilation_outputs, output_groups) = compilation_result

    compilation_support.validate_attributes(common_variables)

    objc_provider = common_variables.objc_provider

    instrumented_files_info = coverage_common.instrumented_files_info(
        ctx = ctx,
        source_attributes = ["srcs", "non_arc_srcs", "hdrs"],
        dependency_attributes = ["deps", "data", "binary", "xctest_app"],
        extensions = extensions.NON_CPP_SOURCES + extensions.CPP_SOURCES + extensions.HEADERS,
        coverage_environment = cc_helper.get_coverage_environment(ctx, ctx.fragments.cpp, cc_toolchain),
        # TODO(cmita): Use ctx.coverage_instrumented() instead when rules_swift can access
        # cc_toolchain.coverage_files and the coverage_support_files parameter of
        # coverage_common.instrumented_files_info(...)
        coverage_support_files = cc_toolchain._coverage_files if ctx.configuration.coverage_enabled else depset([]),
        metadata_files = compilation_outputs.gcno_files() + compilation_outputs.pic_gcno_files(),
    )

    return [
        DefaultInfo(
            files = depset(files),
            data_runfiles = ctx.runfiles(files = files),
        ),
        CcInfo(
            compilation_context = compilation_context,
            linking_context = linking_context,
        ),
        objc_provider,
        instrumented_files_info,
        OutputGroupInfo(**output_groups),
    ]

objc_library = rule(
    implementation = _objc_library_impl,
    doc = """
<p>This rule produces a static library from the given Objective-C source files.</p>""",
    attrs = common_attrs.union(
        {
            "data": attr.label_list(allow_files = True),
            "implementation_deps": attr.label_list(providers = [CcInfo], allow_files = False, doc = """
The list of other libraries that the library target depends on. Unlike with
<code>deps</code>, the headers and include paths of these libraries (and all their
transitive deps) are only used for compilation of this library, and not libraries that
depend on it. Libraries specified with <code>implementation_deps</code> are still linked
in binary targets that depend on this library."""),
        },
        common_attrs.ALWAYSLINK_RULE,
        common_attrs.COMPILING_RULE,
        common_attrs.COMPILE_DEPENDENCY_RULE,
        common_attrs.COPTS_RULE,
        common_attrs.LICENSES,
        common_attrs.SDK_FRAMEWORK_DEPENDER_RULE,
    ),
    fragments = ["objc", "apple", "cpp"],
    cfg = semantics.apple_crosstool_transition,
    toolchains = cc_helper.use_cpp_toolchain() + cc_semantics.get_runtimes_toolchain(),
)
