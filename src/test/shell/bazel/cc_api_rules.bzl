# Copyright 2019 The Bazel Authors. All rights reserved.
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

"""Example C++ API usage"""

load("@bazel_tools//tools/cpp:toolchain_utils.bzl", "find_cpp_toolchain", "use_cpp_toolchain")

def _filter_none(input_list):
    filtered_list = []
    for element in input_list:
        if element != None:
            filtered_list.append(element)
    return filtered_list

def _cc_lib_impl(ctx):
    cc_toolchain = find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )
    compilation_contexts = []
    linking_contexts = []
    for dep in ctx.attr.deps:
        if CcInfo in dep:
            compilation_contexts.append(dep[CcInfo].compilation_context)
            linking_contexts.append(dep[CcInfo].linking_context)

    (compilation_context, compilation_outputs) = cc_common.compile(
        name = ctx.label.name,
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        public_hdrs = ctx.files.public_hdrs,
        private_hdrs = ctx.files.private_hdrs,
        srcs = ctx.files.srcs,
        includes = ctx.attr.includes,
        quote_includes = ctx.attr.quote_includes,
        system_includes = ctx.attr.system_includes,
        defines = ctx.attr.defines,
        user_compile_flags = ctx.attr.user_compile_flags,
        compilation_contexts = compilation_contexts,
    )
    (linking_context, linking_outputs) = cc_common.create_linking_context_from_compilation_outputs(
        name = ctx.label.name,
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        language = "c++",
        compilation_outputs = compilation_outputs,
        linking_contexts = linking_contexts,
    )
    library = linking_outputs.library_to_link
    files = []
    files.extend(compilation_outputs.objects)
    files.extend(compilation_outputs.pic_objects)
    files.append(library.pic_static_library)
    files.append(library.static_library)

    files.append(library.dynamic_library)

    return [
        DefaultInfo(
            files = depset(_filter_none(files)),
        ),
        CcInfo(
            compilation_context = compilation_context,
            linking_context = linking_context,
        ),
    ]

cc_lib = rule(
    implementation = _cc_lib_impl,
    attrs = {
        "public_hdrs": attr.label_list(allow_files = [".h"]),
        "private_hdrs": attr.label_list(allow_files = [".h"]),
        "srcs": attr.label_list(allow_files = [".cc"]),
        "deps": attr.label_list(
            allow_empty = True,
            providers = [[CcInfo]],
        ),
        "user_compile_flags": attr.string_list(),
        "user_link_flags": attr.string_list(),
        "includes": attr.string_list(),
        "quote_includes": attr.string_list(),
        "system_includes": attr.string_list(),
        "defines": attr.string_list(),
        "alwayslink": attr.bool(default = False),
    },
    fragments = ["cpp"],
    toolchains = use_cpp_toolchain(),
)

def _cc_bin_impl(ctx):
    cc_toolchain = find_cpp_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )
    compilation_contexts = []
    linking_contexts = []
    for dep in ctx.attr.deps:
        if CcInfo in dep:
            compilation_contexts.append(dep[CcInfo].compilation_context)
            linking_contexts.append(dep[CcInfo].linking_context)

    (_compilation_context, compilation_outputs) = cc_common.compile(
        name = ctx.label.name,
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        srcs = ctx.files.srcs,
        compilation_contexts = compilation_contexts,
    )
    output_type = "dynamic_library" if ctx.attr.linkshared else "executable"
    user_link_flags = []
    for user_link_flag in ctx.attr.user_link_flags:
        user_link_flags.append(ctx.expand_location(user_link_flag, targets = ctx.attr.additional_linker_inputs))

    malloc = ctx.attr._custom_malloc or ctx.attr.malloc
    linking_contexts.append(malloc[CcInfo].linking_context)

    linking_outputs = cc_common.link(
        name = ctx.label.name,
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        language = "c++",
        compilation_outputs = compilation_outputs,
        linking_contexts = linking_contexts,
        user_link_flags = user_link_flags,
        link_deps_statically = ctx.attr.linkstatic,
        stamp = ctx.attr.stamp,
        additional_inputs = ctx.files.additional_linker_inputs,
        output_type = output_type,
    )
    files = []
    if output_type == "executable":
        files.append(linking_outputs.executable)
    elif output_type == "dynamic_library":
        files.append(linking_outputs.library_to_link.dynamic_library)
        files.append(linking_outputs.library_to_link.resolved_symlink_dynamic_library)

    return [
        DefaultInfo(
            files = depset(_filter_none(files)),
            runfiles = ctx.runfiles(files = ctx.files.data),
        ),
    ]

cc_bin = rule(
    implementation = _cc_bin_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = [".cc"]),
        "additional_linker_inputs": attr.label_list(
            allow_empty = True,
            allow_files = [".lds"],
        ),
        "deps": attr.label_list(
            allow_empty = True,
            providers = [CcInfo],
        ),
        "data": attr.label_list(
            default = [],
            allow_files = True,
        ),
        "user_link_flags": attr.string_list(),
        "linkstatic": attr.bool(default = True),
        "linkshared": attr.bool(default = False),
        "stamp": attr.int(default = -1),
        "malloc": attr.label(
            default = "@bazel_tools//tools/cpp:malloc",
            providers = [CcInfo],
        ),
        # Exposes --custom_malloc flag, if you really need behavior to match
        # native.cc_binary and have that override the malloc attr.
        "_custom_malloc": attr.label(
            default = configuration_field(
                fragment = "cpp",
                name = "custom_malloc",
            ),
            providers = [CcInfo],
        ),
    },
    fragments = ["cpp"],
    toolchains = use_cpp_toolchain(),
)
