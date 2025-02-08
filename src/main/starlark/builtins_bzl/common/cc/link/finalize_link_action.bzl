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
"""Common functions that create C++ link and LTO indexing action."""

load(":common/cc/link/libraries_to_link_collector.bzl", "LINKING_MODE", "collect_libraries_to_link")
load(":common/cc/link/link_build_variables.bzl", "setup_common_linking_variables")
load(":common/cc/link/target_types.bzl", "LINK_TARGET_TYPE", "USE_ARCHIVER", "USE_LINKER", "is_dynamic_library")
load(":common/cc/semantics.bzl", "semantics")

cc_common_internal = _builtins.internal.cc_common
cc_internal = _builtins.internal.cc_internal

def finalize_link_action(
        actions,
        mnemonic,
        action_name,
        link_type,
        linking_mode,
        stamping,
        feature_configuration,
        cc_toolchain,
        progress_message,
        # Inputs:
        object_file_inputs,
        non_code_inputs,
        unique_libraries,
        linkstamp_map,
        linkstamp_object_artifacts,
        linkstamp_object_file_inputs,
        toolchain_libraries_type,
        toolchain_libraries_input,
        user_link_flags,
        # Custom user input files and variables:
        additional_linker_inputs,
        additional_build_variables,
        # Outputs:
        output,
        interface_output,
        dynamic_library_solib_symlink_output,
        action_outputs,
        # Originating from private APIs:
        use_test_only_flags,
        whole_archive,
        native_deps,
        additional_linkstamp_defines,
        # LTO:
        lto_mapping,
        allow_lto_indexing):
    """Creates C++ linking or LTO indexing, and linkstamp compile actions.

    Runs all the libraries through `libraries_to_link_collector`.
    Sets up common link build variables.
    Picks the right tool for the action.
    Compiles the linkstamps.
    Creates the action, producing the `output` and maybe `interface_output`.

    Args:
        actions: (Actions) `actions` object.
        mnemonic: (str) The mnemonic used in the action.
        action_name: (str) action name.
        link_type: (LINK_TARGET_TYPE) Type of libraries to create.
        linking_mode: (LINKING_MODE) Linking mode used for dynamic libraries.
        stamping: (bool) Whether to stamp the output.
        feature_configuration: (FeatureConfiguration) `feature_configuration` to be queried.
        cc_toolchain: (CcToolchainInfo) CcToolchainInfo provider to be used.
        progress_message: (str) The progress message of the action.
        object_file_inputs: (list[LegacyLinkerInput]) Object files
        non_code_inputs: (list[File]) Additional inputs to the linker.
        unique_libraries: (list[LegacyLinkerInput]) The libraries to link in.
        linkstamp_map: (dict[Linkstamp, File]) Map from linkstamps to their object files.
        linkstamp_object_artifacts: (list[File]) Linkstamp object files.
        linkstamp_object_file_inputs: (list[LegacyLinkerInput]) Linkstamp object files wrapped into LinkerInputs.
        toolchain_libraries_type: (artifact_category) Type of toolchain libraries.
        toolchain_libraries_input: (depset[File]) Toolchain libraries.
        user_link_flags: (list[str]) Additional list of linker options.
        additional_linker_inputs: (list[File]|depset[File]) For additional inputs to the linking action,
                  e.g.: linking scripts.
        additional_build_variables: (dict[str,?]) linking variables.
        output: (File) The main output.
        interface_output: (None|File) The interface library. The second output.
        dynamic_library_solib_symlink_output: (None|File) The symlink to the main output in _solib_ dir.
        action_outputs: (depset[File])
        use_test_only_flags: (bool) undocumented.
        whole_archive: (bool) undocumented.
        native_deps: (bool) undocumented.
        additional_linkstamp_defines: (list[str]) undocumented.
        lto_mapping: (dict[File, File]) Map from bitcode files to object files. Used to replace all linker inputs.
        allow_lto_indexing: (bool) Was LTO indexing done.

    Returns:
      None
    """
    need_whole_archive = whole_archive or _need_whole_archive(
        feature_configuration,
        linking_mode,
        link_type,
        user_link_flags,
        cc_toolchain._cpp_configuration,
    )

    must_keep_debug = any([lib.must_keep_debug for lib in unique_libraries])

    toolchain_libraries_solib_dir = ""
    if feature_configuration.is_enabled("static_link_cpp_runtimes"):
        toolchain_libraries_solib_dir = cc_toolchain.dynamic_runtime_solib_dir

    # Linker inputs without any start/end lib expansions.
    non_expanded_linker_inputs = object_file_inputs + linkstamp_object_file_inputs + \
                                 unique_libraries

    # Adding toolchain libraries without whole archive no-matter-what. People don't want to
    # include whole libstdc++ in their binary ever.
    non_expanded_linker_inputs.extend([
        cc_internal.simple_linker_input(input, toolchain_libraries_type, True)
        for input in toolchain_libraries_input.to_list()
    ])

    solib_dir = output.root.path + "/" + cc_toolchain._solib_dir
    collected_libraries_to_link = collect_libraries_to_link(
        non_expanded_linker_inputs,
        cc_toolchain,
        feature_configuration,
        output,
        dynamic_library_solib_symlink_output,
        link_type,
        linking_mode,
        native_deps,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        allow_lto_indexing,
        lto_mapping,
        # TODO(b/338618120): remove cheat using semantic or simplifying collect_libraries_to_link
        cc_internal.actions2ctx_cheat(actions).workspace_name,
    )

    expanded_linker_artifacts = depset([
        lto_mapping.get(li.file, li.file)
        for li in collected_libraries_to_link.expanded_linker_inputs
    ])

    #  Add build variables necessary to template link args into the crosstool.
    build_variables = setup_common_linking_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        is_using_linker = link_type.linker_or_archiver == USE_LINKER,
        is_linking_dynamic_library = link_type == LINK_TARGET_TYPE.DYNAMIC_LIBRARY,
        param_file = "LINKER_PARAM_FILE_PLACEHOLDER",
        must_keep_debug = must_keep_debug,
        use_test_only_flags = use_test_only_flags,
        user_link_flags = user_link_flags,
        runtime_library_search_directories =
            collected_libraries_to_link.all_runtime_library_search_directories,
        libraries_to_link = collected_libraries_to_link.libraries_to_link,
        library_search_directories = collected_libraries_to_link.library_search_directories,
    )

    build_variables = build_variables | additional_build_variables

    if link_type == LINK_TARGET_TYPE.INTERFACE_DYNAMIC_LIBRARY:
        fail("you can't link an interface dynamic library directly")
    if not is_dynamic_library(link_type):
        if interface_output:
            fail("interface output may only be non-null for dynamic library links")
    if link_type.linker_or_archiver == USE_ARCHIVER:
        # solib dir must be None for static links
        toolchain_libraries_solib_dir = None

        if linking_mode != LINKING_MODE.STATIC:
            fail("static library link must be static")
        if native_deps:
            fail("the native deps flag must be false for static links")
        if need_whole_archive:
            fail("the need whole archive flag must be false for static links")

    # TODO(b/62693279): Cleanup once internal crosstools specify ifso building correctly.
    should_use_link_dynamic_library_tool = (
        is_dynamic_library(link_type) and
        feature_configuration.is_enabled("supports_interface_shared_libraries") and
        not feature_configuration.is_enabled("has_configured_linker_path")
    )
    if should_use_link_dynamic_library_tool:
        tool_path = cc_toolchain._link_dynamic_library_tool.path
    else:
        tool_path = cc_common_internal.get_tool_for_action(
            feature_configuration = feature_configuration,
            action_name = link_type.action_name,
        )

    if cc_toolchain._cpp_configuration.incompatible_use_specific_tool_files() and \
       link_type.linker_or_archiver == USE_ARCHIVER:
        linker_files = cc_toolchain._ar_files
    else:
        linker_files = cc_toolchain._linker_files

    # Compute the set of inputs - we only need stable order here.
    dependency_inputs = depset(
        # TODO(b/338618120): This should be set as a tool, not an input
        direct = [cc_toolchain._link_dynamic_library_tool] if should_use_link_dynamic_library_tool else [],
        transitive = [additional_linker_inputs, linker_files],
    )

    non_code_inputs = depset(non_code_inputs)

    # actions display their first input in progress message, and that is a public interface - therefore the
    # order here is important.
    inputs = depset(transitive = [expanded_linker_artifacts, non_code_inputs, dependency_inputs])

    if linkstamp_map:
        # A different value from use_pic
        needs_pic = (cc_toolchain._cpp_configuration.force_pic() or
                     (is_dynamic_library(link_type) and feature_configuration.is_enabled("supports_pic")))

        for linkstamp, artifact in linkstamp_map.items():
            cc_common_internal.register_linkstamp_compile_action(
                actions = actions,
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                source_file = linkstamp.file(),
                output_file = artifact,
                compilation_inputs = linkstamp.hdrs(),
                inputs_for_validation = inputs,
                label_replacement = _quote_replacement(output.path if native_deps and cc_toolchain._cpp_configuration.share_native_deps() else str(output.owner)),
                output_replacement = _quote_replacement(output.path),
                needs_pic = needs_pic,
                stamping = stamping,
                additional_linkstamp_defines = additional_linkstamp_defines,
            )

        # Add linkstamps to the inputs (adding them sooner would create a cycle)
        inputs = depset(
            direct = linkstamp_map.values(),
            transitive = [
                expanded_linker_artifacts,
                non_code_inputs,
                dependency_inputs,
                depset(linkstamp_object_artifacts),
            ],
        )

    _create_action(
        actions,
        action_name,
        feature_configuration,
        cc_toolchain,
        build_variables,
        mnemonic,
        tool_path,
        inputs,
        action_outputs,
        progress_message,
        link_type,
        interface_output,
    )

def _create_action(
        actions,
        action_name,
        feature_configuration,
        cc_toolchain,
        build_variables,
        mnemonic,
        tool_path,
        inputs,
        outputs,
        progress_message,
        link_type,
        interface_output):
    """
    Creates C++ linking or LTO indexing action.

    Args:
      actions: (StarlarkActions) `actions` object
      action_name: (str) action name
      feature_configuration: (FeatureConfiguration) `feature_configuration` to be queried.
      cc_toolchain: (CcToolchainInfo) CcToolchainInfo provider to be used.
      build_variables: (dict[str,?]) variables to substitute into command line
      mnemonic: (str) action mnemonic
      tool_path: (str) tool to execute
      inputs: (depset[File]) all inputs to the action
      outputs: (depset(File)) all outputs of the action
      progress_message: (str) progress message
      link_type: (LINK_TARGET_TYPE) link type, used to determine parameter file type
    """

    parameter_file_type = None
    if _can_split_command_line(
        link_type,
        cc_toolchain,
        feature_configuration,
        interface_output,
    ):
        if feature_configuration.is_enabled("gcc_quoting_for_param_files"):
            parameter_file_type = "GCC_QUOTED"
        elif feature_configuration.is_enabled("windows_quoting_for_param_files"):
            parameter_file_type = "WINDOWS"
        else:
            parameter_file_type = "UNQUOTED"

    # If the crosstool uses action_configs to configure cc compilation, collect execution info
    # from there, otherwise, use no execution info.
    # TODO(b/27903698): Assert that the crosstool has an action_config for this action.
    execution_info = {}
    for req in cc_common_internal.get_execution_requirements(feature_configuration = feature_configuration, action_name = action_name):
        execution_info[req] = ""

    build_variables = cc_internal.cc_toolchain_variables(vars = build_variables)
    link_args = cc_internal.get_link_args(
        feature_configuration = feature_configuration,
        action_name = action_name,
        build_variables = build_variables,
        parameter_file_type = parameter_file_type,
    )
    env = cc_common_internal.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = action_name,
        variables = build_variables,
    )
    if "requires_darwin" not in execution_info:
        # This prevents gcc from writing the unpredictable (and often irrelevant)
        # value of getcwd() into the debug info.
        env = env | {"PWD": "/proc/self/cwd"}
    exec_group = None
    toolchain = None

    if "cpp_link" in cc_internal.actions2ctx_cheat(actions).exec_groups:
        # TODO(b/338618120): ^ remove cheat, no idea how though, maybe always use cpp_link exec group?
        exec_group = "cpp_link"
    elif "@//tools/cpp:toolchain_type" in cc_internal.actions2ctx_cheat(actions).toolchains:
        # TODO(b/338618120): ^ remove cheat, needs depot cleanup, always use a toolchain
        toolchain = semantics.toolchain

    execution_info = semantics.get_cc_link_memlimit(
        cc_toolchain._cpp_configuration.compilation_mode(),
        execution_info,
    )
    actions.run(
        mnemonic = mnemonic,
        executable = tool_path,
        arguments = [link_args],
        inputs = inputs,
        outputs = outputs,
        progress_message = progress_message,
        resource_set = _resource_set,
        env = env,
        use_default_shell_env = True,
        execution_requirements = execution_info,
        toolchain = toolchain,
        exec_group = exec_group,
    )

def _can_split_command_line(
        link_type,
        cc_toolchain,
        feature_configuration,
        interface_output):
    if not cc_toolchain._supports_param_files:
        return False
    elif is_dynamic_library(link_type):
        # On Unix, we currently can't split dynamic library links if they have interface outputs.
        # That was probably an unintended side effect of the change that introduced interface
        # outputs.
        if interface_output:
            # On Windows, We can always split the command line when building DLL.
            return feature_configuration.is_enabled("targets_windows")
        else:
            return True
    elif link_type.linker_or_archiver == USE_LINKER:
        return True
    elif link_type.linker_or_archiver == USE_ARCHIVER:
        # A feature to control whether to use param files for archiving commands.
        return feature_configuration.is_enabled("archive_param_file")

    # This should be unreachable:
    return False

def _need_whole_archive(feature_configuration, linking_mode, link_type, linkopts, cpp_config):
    """The default heuristic on whether we need to use whole-archive for the link."""
    shared_linkopts = is_dynamic_library(link_type) or "-shared" in linkopts or "-shared" in cpp_config.linkopts

    # Fasten your seat belt, the logic below doesn't make perfect sense and it's full of obviously
    # missed corner cases. The world still stands and depends on this behavior, so ¯\_(ツ)_/¯.
    if not shared_linkopts:
        # We are not producing shared library, there is no reason to use --whole-archive with
        # executable (if the executable doesn't use the symbols, nobody else will, so --whole-archive
        # is not needed).
        return False

    if feature_configuration.is_requested("force_no_whole_archive"):
        return False

    if cpp_config.incompatible_remove_legacy_whole_archive():
        # --incompatible_remove_legacy_whole_archive has been flipped, no --whole-archive for the
        # entire build.
        return False

    if linking_mode != LINKING_MODE.STATIC:
        # legacy whole archive only applies to static linking mode.
        return False

    if feature_configuration.is_requested("legacy_whole_archive"):
        # --incompatible_remove_legacy_whole_archive has not been flipped, and this target requested
        # --whole-archive using features.
        return True

    if cpp_config.legacy_whole_archive():
        # --incompatible_remove_legacy_whole_archive has not been flipped, so whether to
        # use --whole-archive depends on --legacy_whole_archive.
        return True

    # Hopefully future default.
    return False

def _quote_replacement(s):
    if "\\" not in s and "$" not in s:
        return s
    return s.replace("\\", "\\\\").replace("$", "\\$")

def _resource_set(os, inputs):
    if os == "osx":
        return {"memory": 15 + 0.05 * inputs, "cpu": 1}
    elif os == "linux":
        return {"memory": max(50, -100 + 0.1 * inputs), "cpu": 1}
    else:
        return {"memory": 1500 + inputs, "cpu": 1}
