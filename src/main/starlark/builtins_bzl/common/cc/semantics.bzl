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

"""Semantics for Bazel cc rules"""

# Point virtual includes symlinks to the source root for better IDE integration.
# See https://github.com/bazelbuild/bazel/pull/20540.
# TODO: b/320980684 - Add a test that fails if this is flipped to True.
USE_EXEC_ROOT_FOR_VIRTUAL_INCLUDES_SYMLINKS = False

def _get_proto_aspects():
    return []

def _should_create_empty_archive():
    return False

def _validate_attributes(ctx):
    pass

def _get_semantics():
    return _builtins.internal.bazel_cc_internal.semantics

def _get_stl():
    return attr.label()

def _get_repo():
    return "bazel_tools"

def _get_platforms_root():
    return "platforms//"

def _additional_fragments():
    return []

def _get_distribs_attr():
    return {}

def _get_licenses_attr():
    # TODO(b/182226065): Change to applicable_licenses
    return {}

def _def_parser_computed_default(name, tags):
    # This is needed to break the dependency cycle.
    if "__DONT_DEPEND_ON_DEF_PARSER__" in tags or "def_parser" in name:
        return None
    else:
        return Label("@bazel_tools//tools/def_parser:def_parser")

def _get_def_parser():
    return attr.label(
        default = _def_parser_computed_default,
        allow_single_file = True,
        cfg = "exec",
    )

def _get_grep_includes():
    return attr.label()

def _get_runtimes_toolchain():
    return []

def _get_test_malloc_attr():
    return {}

def _get_coverage_attrs():
    return {
        "_lcov_merger": attr.label(
            default = configuration_field(fragment = "coverage", name = "output_generator"),
            executable = True,
            cfg = "exec",
        ),
        "_collect_cc_coverage": attr.label(
            default = "@bazel_tools//tools/test:collect_cc_coverage",
            executable = True,
            cfg = "exec",
        ),
    }

def _get_coverage_env(ctx):
    return ctx.runfiles(), {}

def _get_cc_runtimes(ctx, is_library):
    if is_library:
        return []

    runtimes = [ctx.attr.link_extra_lib]

    if ctx.fragments.cpp.custom_malloc != None:
        runtimes.append(ctx.attr._default_malloc)
    else:
        runtimes.append(ctx.attr.malloc)

    return runtimes

def _get_cc_runtimes_copts(ctx):
    return []

def _get_implementation_deps_allowed_attr():
    return {}

def _check_can_use_implementation_deps(ctx):
    experimental_cc_implementation_deps = ctx.fragments.cpp.experimental_cc_implementation_deps()
    if (not experimental_cc_implementation_deps and ctx.attr.implementation_deps):
        fail("requires --experimental_cc_implementation_deps", attr = "implementation_deps")

_WINDOWS_PLATFORM = Label("@platforms//os:windows")  # Resolve the label within builtins context

def _get_linkstatic_default_for_test():
    return select({
        _WINDOWS_PLATFORM: True,
        "//conditions:default": False,
    })

def _get_cc_link_memlimit(compilation_mode, exec_info):
    return exec_info

def _get_nocopts_attr():
    return {}

def _get_experimental_link_static_libraries_once(ctx):
    return ctx.fragments.cpp.experimental_link_static_libraries_once()

def _check_cc_shared_library_tags(ctx):
    pass

def _cpp_modules_tools():
    return {
        "_aggregate_ddi": attr.label(
            executable = True,
            cfg = "exec",
            default = "@" + _get_repo() + "//tools/cpp:aggregate-ddi",
        ),
        "_generate_modmap": attr.label(
            executable = True,
            cfg = "exec",
            default = "@" + _get_repo() + "//tools/cpp:generate-modmap",
        ),
    }

semantics = struct(
    toolchain = "@bazel_tools//tools/cpp:toolchain_type",
    ALLOWED_RULES_IN_DEPS = [
        "cc_library",
        "objc_library",
        "cc_proto_library",
        "cc_import",
    ],
    ALLOWED_FILES_IN_DEPS = [
        ".ld",
        ".lds",
        ".ldscript",
    ],
    ALLOWED_RULES_WITH_WARNINGS_IN_DEPS = [],
    validate_attributes = _validate_attributes,
    get_semantics = _get_semantics,
    get_repo = _get_repo,
    get_platforms_root = _get_platforms_root,
    additional_fragments = _additional_fragments,
    get_distribs_attr = _get_distribs_attr,
    get_licenses_attr = _get_licenses_attr,
    get_def_parser = _get_def_parser,
    get_stl = _get_stl,
    should_create_empty_archive = _should_create_empty_archive,
    get_grep_includes = _get_grep_includes,
    get_implementation_deps_allowed_attr = _get_implementation_deps_allowed_attr,
    check_can_use_implementation_deps = _check_can_use_implementation_deps,
    get_linkstatic_default_for_test = _get_linkstatic_default_for_test,
    get_cc_link_memlimit = _get_cc_link_memlimit,
    get_runtimes_toolchain = _get_runtimes_toolchain,
    get_test_malloc_attr = _get_test_malloc_attr,
    get_cc_runtimes = _get_cc_runtimes,
    get_cc_runtimes_copts = _get_cc_runtimes_copts,
    get_coverage_attrs = _get_coverage_attrs,
    get_coverage_env = _get_coverage_env,
    get_proto_aspects = _get_proto_aspects,
    get_nocopts_attr = _get_nocopts_attr,
    get_experimental_link_static_libraries_once = _get_experimental_link_static_libraries_once,
    cpp_modules_tools = _cpp_modules_tools,
    check_cc_shared_library_tags = _check_cc_shared_library_tags,
    BUILD_INFO_TRANLATOR_LABEL = "@bazel_tools//tools/build_defs/build_info:cc_build_info",
    CC_PROTO_TOOLCHAIN = "@rules_cc//cc/proto:toolchain_type",
    is_bazel = True,
    stamp_extra_docs = "",
    malloc_docs = """
 Override the default dependency on malloc.
 <p>
   By default, C++ binaries are linked against <code>//tools/cpp:malloc</code>,
   which is an empty library so the binary ends up using libc malloc.
   This label must refer to a <code>cc_library</code>. If compilation is for a non-C++
   rule, this option has no effect. The value of this attribute is ignored if
   <code>linkshared=True</code> is specified.
 </p>
""",
    cc_binary_extra_docs = "",
    cc_test_extra_docs = "",
)
