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
"""Functions that create C++ link action."""

load(":common/cc/cc_helper_internal.bzl", "artifact_category")
load(":common/cc/link/finalize_link_action.bzl", "finalize_link_action")
load(":common/cc/link/link_build_variables.bzl", "setup_linking_variables")
load(":common/cc/link/target_types.bzl", "LINK_TARGET_TYPE", "USE_ARCHIVER", "USE_LINKER", "is_dynamic_library")
load(":common/paths.bzl", "paths")

cc_common_internal = _builtins.internal.cc_common
cc_internal = _builtins.internal.cc_internal

def link_action(
        *,
        actions,
        mnemonic,
        library_identifier,  # TODO(b/331164666): Set in the callee and remove
        link_type,
        linking_mode,
        use_pic,
        stamping,
        feature_configuration,
        cc_toolchain,

        # Inputs from compilation:
        compilation_outputs,
        additional_object_files = [],

        # Inputs from linking_contexts and toolchain:
        libraries,
        linkstamps,
        linkopts,
        non_code_inputs,
        # TODO(b/331164666): merge into libraries or additional_object_files
        toolchain_libraries_type,
        toolchain_libraries_input,

        # Custom user input/output files and variables:
        additional_linker_inputs,  # TODO(b/331164666): rename to linker_input_files
        link_action_outputs,  # TODO(b/331164666): rename to linker_output_files,
        variables_extensions,

        # Output files:
        output,
        interface_output,
        dynamic_library_solib_symlink_output,

        # Originating from private APIs:
        use_test_only_flags,
        whole_archive,
        native_deps,
        additional_linkstamp_defines,

        # LTO:
        thinlto_param_file,
        all_lto_artifacts = [],
        allow_lto_indexing = False):
    """Creates a C++ linking action.

    The function collects all object files, maps them with LTO mapping when present.
    It declares linkstamp object (but doesn't yet compile them).

    All object files are wrapped with LegacyLinkerInputs.

    It prepares link build variables specific to linking actions and creates the
    action by calling `finalize_link_action`.

    The main output and optionally interface library are wrapped into LegacyLinkerInputs
    and returned.

    Args:
        actions: (Actions) `actions` object.
        mnemonic: (str) The mnemonic used in the action.
        library_identifier: (str) Identifier of the library.
        link_type: (LINK_TARGET_TYPE) Type of libraries to create.
        linking_mode: (LINKING_MODE) Linking mode used for dynamic libraries.
        use_pic: (bool) Whether to use PIC.
        stamping: (bool) Whether to stamp the output.
        feature_configuration: (FeatureConfiguration) `feature_configuration` to be queried.
        cc_toolchain: (CcToolchainInfo) CcToolchainInfo provider to be used.
        compilation_outputs: (CompilationOutputs) Compilation outputs containing object files to link.
        additional_object_files: (list[File]) Additional object files not in the `compilation_outputs`.
        libraries: (list[LegacyLinkerInput]) The libraries to link in.
        linkstamps: (list[Linkstamp]) The linkstamps to use.
        linkopts: (list[str]) Additional list of linker options.
        non_code_inputs: (list[File]) Additional inputs to the linker.
        toolchain_libraries_type: (artifact_category) Type of toolchain libraries.
        toolchain_libraries_input: (depset[File]) Toolchain libraries.
        additional_linker_inputs: (list[File]|depset[File]) For additional inputs to the linking action,
          e.g.: linking scripts.
        link_action_outputs: (list[File]) For additional outputs to the linking action, e.g.: map files.
        variables_extensions: (dict[str, str|list[str]|depset[str]]) Additional variables to pass to
            the toolchain configuration when creating link command line.
        output: (File) The main output.
        interface_output: (None|File) The interface library. The second output.
        dynamic_library_solib_symlink_output: (None|File) The symlink to the main output in _solib_ dir.
        use_test_only_flags: (bool) undocumented.
        whole_archive: (bool) undocumented.
        native_deps: (bool) undocumented.
        additional_linkstamp_defines: (list[str]) undocumented.
        thinlto_param_file: (None|File) The input file created by LTO indexing action.
        all_lto_artifacts: (list[LtoBackendArtifacts]) LTO artifacts.
        allow_lto_indexing: (bool) Was LTO indexing done.

    Returns:
      (LegacyLinkerInput, LegacyLinkerInput) output library and interface output
      library.
    """

    # Executable links do not have library identifiers.
    if bool(library_identifier) == link_type.executable:
        fail("Executables can't have library identifier", library_identifier, link_type.executable)
    if interface_output and not is_dynamic_library(link_type):
        fail("Interface output can only be used with DYNAMIC_LIBRARY targets")
    if not cc_common_internal.action_is_enabled(
        feature_configuration = feature_configuration,
        action_name = link_type.action_name,
    ):
        fail("Expected action_config for '%s' to be configured" % link_type.action_name)

    object_files = compilation_outputs.pic_objects if use_pic else compilation_outputs.objects
    object_files = object_files + additional_object_files

    propeller_optimize_info = getattr(cc_toolchain._fdo_context, "propeller_optimize_info", None)
    if not cc_toolchain._is_tool_configuration and propeller_optimize_info and \
       propeller_optimize_info.ld_profile:
        non_code_inputs.append(propeller_optimize_info.ld_profile)

    lto_mapping = {}

    # We're doing 4-phased lto build, and this is the final link action (4-th phase).
    if all_lto_artifacts:
        for lto_artifact in all_lto_artifacts:
            lto_mapping[lto_artifact.bitcode_file()] = lto_artifact.object_file()

    if thinlto_param_file:
        non_code_inputs.append(thinlto_param_file)

    linkstamp_map = _map_linkstamps_to_outputs(actions, linkstamps, output)
    linkstamp_object_file_inputs = [cc_internal.linkstamp_linker_input(input) for input in linkstamp_map.values()]
    object_file_inputs = [cc_internal.simple_linker_input(input) for input in object_files]

    object_artifacts = [lto_mapping.get(obj, obj) for obj in object_files]
    linkstamp_object_artifacts = [lto_mapping.get(obj, obj) for obj in linkstamp_map.values()]

    combined_object_artifacts = object_artifacts + linkstamp_object_artifacts

    output_library = None
    lto_compilation_context = compilation_outputs.lto_compilation_context()
    if not link_type.executable:
        use_archiver = link_type.linker_or_archiver == USE_ARCHIVER
        output_library = struct(
            file = output,
            artifact_category = link_type.linker_output,
            library_identifier = library_identifier,
            object_files = combined_object_artifacts if use_archiver else [],
            lto_compilation_context = lto_compilation_context if use_archiver else [],
            shared_non_lto_backends = cc_internal.create_shared_non_lto_artifacts(
                actions,
                lto_compilation_context,
                link_type.linker_or_archiver == USE_LINKER,
                feature_configuration,
                cc_toolchain,
                use_pic,
                object_file_inputs,
            ),
            must_keep_debug = False,
        )

    interface_output_library = None
    if interface_output:
        interface_output_library = struct(
            file = interface_output,
            artifact_category = artifact_category.DYNAMIC_LIBRARY,
            library_identifier = library_identifier,
            object_files = combined_object_artifacts,
            lto_compilation_context = lto_compilation_context,
            shared_non_lto_backends = None,
            must_keep_debug = False,
        )

    action_outputs = [output] + link_action_outputs + ([interface_output] if interface_output else [])

    build_variables = variables_extensions | setup_linking_variables(
        cc_toolchain,
        feature_configuration,
        output,
        cc_internal.dynamic_library_soname(
            actions,
            output.short_path,
            link_type != LINK_TARGET_TYPE.NODEPS_DYNAMIC_LIBRARY,
        ),
        interface_output,
        thinlto_param_file,
    )

    user_link_flags = linkopts + cc_toolchain._cpp_configuration.linkopts

    finalize_link_action(
        actions,
        # TODO(b/331164666): use default value instead of an if
        mnemonic if mnemonic else "CppLink",
        link_type.action_name,
        link_type,
        linking_mode,
        stamping,
        feature_configuration,
        cc_toolchain,
        "Linking %{output}",  # progress_message
        # Inputs:
        object_file_inputs,
        non_code_inputs,
        libraries,
        linkstamp_map,
        linkstamp_object_artifacts,
        linkstamp_object_file_inputs,
        toolchain_libraries_type,
        toolchain_libraries_input,
        user_link_flags,
        # Custom user input files and variables:
        additional_linker_inputs,
        build_variables,
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
        allow_lto_indexing,
    )

    return output_library, interface_output_library

def _map_linkstamps_to_outputs(actions, linkstamps, output):
    """ Translates a collection of Linkstamp instances to an immutable mapping from linkstamp to object files.

     In other words, given a set of source files, this method determines the output
     path to which each file should be compiled.

     Args:
       actions: (Actions) `actions` object.
       linkstamps: (list[Linkstamp])
       output: the binary output path for this link
    Returns:
      (dict[File,File]) a dict pairs each source file with the corresponding object file that
       should be fed into the link
    """
    map = {}

    stamp_output_dir = paths.join(paths.dirname(output.short_path), "_objs", output.basename)
    linkstamps = set(linkstamps)
    seen_linkstamp_sources = set()
    for linkstamp in linkstamps:
        if linkstamp.file() in seen_linkstamp_sources:
            continue
        seen_linkstamp_sources.add(linkstamp.file())
        linkstamp_file = linkstamp.file()
        stamp_output_path = paths.join(
            stamp_output_dir,
            paths.replace_extension(linkstamp_file.short_path, ".o"),
        )
        stamp_output_file = actions.declare_shareable_artifact(stamp_output_path)
        map[linkstamp] = stamp_output_file
    return map
