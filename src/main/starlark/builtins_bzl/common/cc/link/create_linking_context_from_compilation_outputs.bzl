# Copyright 2024 The Bazel Authors. All rights reserved.
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
"""
The cc_common.create_linking_context_from_compilation_outputs function.

Used to prepare a single library for linking. See also: cc_common.link
"""

load(":common/cc/link/cc_linking_helper.bzl", "create_cc_link_actions")
load(":common/cc/link/libraries_to_link_collector.bzl", "LINKING_MODE")
load(":common/cc/link/target_types.bzl", "LINK_TARGET_TYPE")

cc_internal = _builtins.internal.cc_internal
cc_common_internal = _builtins.internal.cc_common

# LINT.IfChange

# IMPORTANT: This function is public API exposed on cc_common module!
def create_linking_context_from_compilation_outputs(
        *,
        actions,
        name,
        feature_configuration,
        cc_toolchain,
        language = "c++",  # buildifier: disable=unused-variable
        disallow_static_libraries = False,
        disallow_dynamic_library = False,
        compilation_outputs,
        linking_contexts = [],
        user_link_flags = [],
        alwayslink = False,
        additional_inputs = [],
        variables_extension = {},
        # Private:
        stamp = 0,
        linked_dll_name_suffix = "",
        test_only_target = False):
    """
    Links a single library.

    Should be used for creating library rules that can propagate information downstream in
    order to be linked later by a top level rule that does transitive linking to
    create an executable or a dynamic library.

    The function creates static libraries and "nodeps" dynamic library, using `name` to name them,
    only linking the object files from `compilation_outputs`. Static libraries are produced in
    nopic and/or pic version, depending on the configuration and if the toolchain supports pic.

    Disable either static or dynamic library using `disallow_static_libraries` or
    `disallow_dynamic_library`.

    Callee may specify `user_link_flags`, `additional_inputs` to C++ linking action, and
    custom `variables_extension`, which are passed to link command line.

    The exception are Windows, where "nodeps" dynamic library links also all the transitive
    libraries from `linking_context`.

    TODO(b/338618120): during Starlarkification of rules several private parameters were introduced,
    those parameters need to be eventually removed or made public.

    Args:
        actions: (Actions) `actions` object.
        name: (str) This is used for naming the output artifacts of actions created by this method.
        feature_configuration: (FeatureConfiguration) `feature_configuration` to be queried.
        language: ("cpp") Only C++ supported for now. Do not use this parameter.
        cc_toolchain: (CcToolchainInfo) CcToolchainInfo provider to be used.
        disallow_static_libraries: (bool) Whether static libraries should be created.
        disallow_dynamic_library: (bool) Whether a dynamic library should be created.
        compilation_outputs: (CompilationOutputs) Compilation outputs containing object files to link.
        linking_contexts: (list[LinkingContext]) Libraries from dependencies. These libraries will
           be linked into the output artifact of the link() call, be it a binary or a library.
        user_link_flags: (list[str]) Additional list of linker options.
        alwayslink: (bool) Whether this library should always be linked.
        additional_inputs: (list[File]|depset[File]) For additional inputs to the linking action,
            e.g.: linking scripts.
        variables_extension: (dict[str, str|list[str]|depset[str]]) Additional variables to pass to
            the toolchain configuration when creating link command line.
        stamp: (bool) undocumented.
        linked_dll_name_suffix: (str) undocumented.
        test_only_target: (bool) undocumented.

    Returns:
      (`CcLinkingContext`, `CcLinkingOutputs`) A pair.
    """

    # LINT.ThenChange(//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/cpp/CcModuleApi.java)
    # TODO(b/202252560): Fix for swift_library's implicit output, remove rule_kind_cheat.
    if alwayslink and cc_internal.rule_kind_cheat(actions) != "swift_library rule":
        static_link_type = LINK_TARGET_TYPE.ALWAYS_LINK_STATIC_LIBRARY
    else:
        static_link_type = LINK_TARGET_TYPE.STATIC_LIBRARY
    if type(additional_inputs) == type([]):
        additional_inputs = depset(additional_inputs)

    cc_linking_outputs = create_cc_link_actions(
        cc_internal.wrap_link_actions(actions),
        name,
        None if disallow_static_libraries else static_link_type,
        None if disallow_dynamic_library else LINK_TARGET_TYPE.NODEPS_DYNAMIC_LIBRARY,
        LINKING_MODE.DYNAMIC,
        feature_configuration,
        cc_toolchain,
        compilation_outputs,
        linking_contexts,
        user_link_flags,  # linkopts
        stamp,
        additional_inputs,  # additional_linker_inputs
        [],  # linker_outputs
        variables_extension,
        alwayslink = alwayslink,
        test_only_target = test_only_target,
        linked_dll_name_suffix = linked_dll_name_suffix,
    )

    linker_input = cc_common_internal.create_linker_input(
        # TODO(b/331164666): remove cheat, we always produce a file, file.owner gives us a label
        owner = cc_internal.actions2ctx_cheat(actions).label.same_package_label(name),
        libraries = depset([cc_linking_outputs.library_to_link]) if cc_linking_outputs.library_to_link else None,
        user_link_flags = user_link_flags,
        additional_inputs = additional_inputs,
    )
    direct_linking_context = cc_common_internal.create_linking_context(
        linker_inputs = depset([linker_input]),
    )
    linking_context = cc_common_internal.merge_linking_contexts(
        linking_contexts = [direct_linking_context] + linking_contexts,
    )
    return linking_context, cc_linking_outputs
