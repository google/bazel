# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""objc_import Starlark implementation replacing native"""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", cc_semantics = "semantics")
load(":common/objc/attrs.bzl", "common_attrs")
load(":common/objc/compilation_support.bzl", "compilation_support")

objc_internal = _builtins.internal.objc_internal

def _objc_import_impl(ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    alwayslink = ctx.fragments.objc.target_should_alwayslink(ctx)
    common_variables = compilation_support.build_common_variables(
        ctx = ctx,
        deps = ctx.attr.deps,
        toolchain = cc_toolchain,
        alwayslink = alwayslink,
        empty_compilation_artifacts = True,
    )

    compilation_support.validate_attributes(common_variables)

    (compilation_context, _, _, _) = compilation_support.register_compile_and_archive_actions(
        common_variables,
    )

    libraries_to_link = []
    for archive in ctx.files.archives:
        library_to_link = cc_common.create_library_to_link(
            actions = ctx.actions,
            cc_toolchain = cc_toolchain,
            static_library = archive,
            alwayslink = alwayslink,
        )
        libraries_to_link.append(library_to_link)

    linking_context = cc_common.create_linking_context(
        linker_inputs = depset(
            direct = [
                cc_common.create_linker_input(
                    owner = ctx.label,
                    libraries = depset(libraries_to_link),
                    user_link_flags = common_variables.objc_linking_context.linkopts,
                ),
            ],
        ),
    )

    cc_info = cc_common.merge_cc_infos(
        direct_cc_infos = [
            CcInfo(
                compilation_context = compilation_context,
                linking_context = linking_context,
            ),
        ],
        cc_infos = [dep[CcInfo] for dep in ctx.attr.deps],
    )

    return [
        cc_info,
        common_variables.objc_provider,
    ]

objc_import = rule(
    implementation = _objc_import_impl,
    doc = """
<p>This rule encapsulates an already-compiled static library in the form of an
<code>.a</code> file. It also allows exporting headers and resources using the same
attributes supported by <code>objc_library</code>.</p>""",
    attrs = common_attrs.union(
        {
            "archives": attr.label_list(allow_empty = False, mandatory = True, allow_files = [".a"], doc = """
The list of <code>.a</code> files provided to Objective-C targets that
depend on this target."""),
        },
        common_attrs.ALWAYSLINK_RULE,
        common_attrs.COMPILE_DEPENDENCY_RULE,
        common_attrs.LICENSES,
        common_attrs.SDK_FRAMEWORK_DEPENDER_RULE,
    ),
    fragments = ["objc", "apple", "cpp"],
    toolchains = cc_helper.use_cpp_toolchain() + cc_semantics.get_runtimes_toolchain(),
)
