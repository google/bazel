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
"""Functions that create LTO indexing action."""

load(":common/cc/link/finalize_link_action.bzl", "finalize_link_action")
load(":common/cc/link/libraries_to_link_collector.bzl", "LINKING_MODE")
load(":common/cc/link/link_build_variables.bzl", "setup_lto_indexing_variables")
load(":common/cc/link/target_types.bzl", "LINK_TARGET_TYPE", "is_dynamic_library")

cc_internal = _builtins.internal.cc_internal

def create_lto_artifacts_and_lto_indexing_action(
        actions,
        link_type,
        linking_mode,
        use_pic,
        feature_configuration,
        cc_toolchain,
        # Inputs from compilation:
        compilation_outputs,
        # Inputs from linking_contexts:
        libraries,
        linkopts,
        # The final output file, uses its name:
        output,
        # Originating from private APIs:
        test_only_target,
        # LTO:
        lto_compilation_context,
        variables_extensions,
        **link_action_args):
    """Creates the LTO indexing step, rather than the real link.

    Creates all LTO artifacts and if allowed the LTO indexing action.

    LTO indexing action creates `thinlto_param_file` and `thinlto_merged_object_file`.
    Both of the files are later passed into C++ linking action.

    Args:
        actions: (Actions) `actions` object.
        link_type: (LINK_TARGET_TYPE) Type of libraries to create.
        linking_mode: (LINKING_MODE) Linking mode used for dynamic libraries.
        use_pic: (bool) Whether to use PIC.
        feature_configuration: (FeatureConfiguration) `feature_configuration` to be queried.
        cc_toolchain: (CcToolchainInfo) CcToolchainInfo provider to be used.
        compilation_outputs: (CompilationOutputs) Compilation outputs containing object files
            to link.
        libraries: (list[LegacyLinkerInput]) The libraries to link in.
        linkopts: (list[str]) Additional list of linker options.
        variables_extensions: (dict[str, str|list[str]|depset[str]]) Additional variables to pass to
            the toolchain configuration when creating link command line.
        output: (File) The main output. Not created here, just using its name.
        test_only_target: (bool) undocumented.
        lto_compilation_context:
        variables_extensions: (dict[str,?]) linking variables.
        **link_action_args: Arguments passed through to link action.

    Returns:
        ([list[LtoBackendArtifacts]], bool, File, File)
        all_lto_artifacts, allow_lto_indexing, thinlto_param_file, thinlto_merged_object_file

    """
    if not feature_configuration.is_enabled("supports_start_end_lib"):
        fail("When using LTO. The feature supports_start_end_lib must be enabled.")
    can_include_any_link_static_in_lto_indexing = \
        not feature_configuration.is_enabled("thin_lto_all_linkstatic_use_shared_nonlto_backends")
    can_include_any_link_static_test_target_in_lto_indexing = \
        not feature_configuration.is_enabled("thin_lto_linkstatic_tests_use_shared_nonlto_backends")
    include_link_static_in_lto_indexing = can_include_any_link_static_in_lto_indexing and (
        can_include_any_link_static_test_target_in_lto_indexing or not test_only_target
    )
    allow_lto_indexing = include_link_static_in_lto_indexing or (
        linking_mode == LINKING_MODE.DYNAMIC and bool(lto_compilation_context.lto_bitcode_inputs())
    )

    lto_output_root_prefix = output.short_path + ".lto" if allow_lto_indexing else "shared.nonlto"
    lto_obj_root_prefix = lto_output_root_prefix
    if feature_configuration.is_enabled("use_lto_native_object_directory"):
        lto_obj_root_prefix = lto_output_root_prefix + "-obj"
    object_file_inputs = [
        cc_internal.simple_linker_input(input)
        for input in (compilation_outputs.pic_objects if use_pic else compilation_outputs.objects)
    ]

    all_lto_artifacts = cc_internal.create_lto_artifacts(
        actions,
        lto_compilation_context,
        feature_configuration,
        cc_toolchain,
        use_pic,
        object_file_inputs,
        lto_output_root_prefix,
        lto_obj_root_prefix,
        depset(libraries),
        allow_lto_indexing,
        include_link_static_in_lto_indexing,
    )
    if allow_lto_indexing:
        thinlto_param_file, thinlto_merged_object_file = _lto_indexing_action(
            actions = actions,
            cc_toolchain = cc_toolchain,
            compilation_outputs = compilation_outputs,
            feature_configuration = feature_configuration,
            linkopts = linkopts,
            variables_extensions = variables_extensions,
            output = output,
            link_type = link_type,
            libraries = libraries,
            use_pic = use_pic,
            linking_mode = linking_mode,
            all_lto_artifacts = all_lto_artifacts,
            allow_lto_indexing = allow_lto_indexing,
            include_link_static_in_lto_indexing = include_link_static_in_lto_indexing,
            lto_output_root_prefix = lto_output_root_prefix,
            lto_obj_root_prefix = lto_obj_root_prefix,
            **link_action_args
        )
    else:
        thinlto_param_file, thinlto_merged_object_file = None, None

    return all_lto_artifacts, allow_lto_indexing, thinlto_param_file, thinlto_merged_object_file

def _lto_indexing_action(
        actions,
        linking_mode,
        cc_toolchain,
        all_lto_artifacts,
        allow_lto_indexing,
        libraries,
        include_link_static_in_lto_indexing,
        compilation_outputs,
        output,
        feature_configuration,
        link_type,
        linkopts,
        use_pic,
        lto_output_root_prefix,  # str
        lto_obj_root_prefix,
        # We're called with `stamping` and `link_action_outputs` parameters, but we don't use them.
        # They appear here, to remove them from `link_action_args`.
        stamping,  # buildifier: disable=unused-variable
        link_action_outputs,  # buildifier: disable=unused-variable
        variables_extensions,
        **link_action_args):
    # Get the set of object files and libraries containing the correct
    # inputs for this link, depending on whether this is LTO indexing or
    # a native link.
    lto_compilation_context = compilation_outputs.lto_compilation_context()
    object_file_inputs = [cc_internal.simple_linker_input(
        lto_compilation_context.get_minimized_bitcode_or_self(input),
    ) for input in (compilation_outputs.pic_objects if use_pic else compilation_outputs.objects)]

    unique_libraries = []
    for lib in libraries:
        if lib.object_files == None:
            unique_libraries.append(lib)
            continue
        new_object_files = []
        for a in lib.object_files:
            # If this link includes object files from another library, that library must be
            # statically linked.
            if not include_link_static_in_lto_indexing:
                lto_artifacts = lib.shared_non_lto_backends.get(a, None)

                # Either we have a shared LTO artifact, or this wasn't bitcode to start with.
                if lto_artifacts:
                    # Include the native object produced by the shared LTO backend in the LTO indexing
                    # step instead of the bitcode file. The LTO indexing step invokes the linker which
                    # must see all objects used to produce the final link output.
                    new_object_files.append(lto_artifacts.object_file())
                    continue
                elif lib.lto_compilation_context.get_minimized_bitcode_or_self(a) != a:
                    fail(("For artifact '%s' in library '%s': unexpectedly has a shared LTO artifact for " +
                          "bitcode") % (a, lib.file))
            new_object_files.append(lib.lto_compilation_context.get_minimized_bitcode_or_self(a))
        unique_libraries.append(cc_internal.library_linker_input(
            lib.file,
            lib.artifact_category,
            lib.library_identifier,
            new_object_files,
            lib.lto_compilation_context,
        ))

    # Create artifact for the file that the LTO indexing step will emit
    # object file names into for any that were included in the link as
    # determined by the linker's symbol resolution. It will be used to
    # provide the inputs for the subsequent final native object link.
    # Note that the paths emitted into this file will have their prefixes
    # replaced with the final output directory, so they will be the paths
    # of the native object files not the input bitcode files.
    thinlto_param_file = \
        actions.declare_shareable_artifact(output.short_path + "-lto-final.params")

    # Create artifact for the merged object file, which is an object file that is created
    # during the LTO indexing step and needs to be passed to the final link.
    thinlto_merged_object_file = \
        actions.declare_shareable_artifact(output.short_path + ".lto.merged.o")

    action_outputs = \
        ([lto_artifact.imports for lto_artifact in all_lto_artifacts if lto_artifact.index] +
         [lto_artifact.index for lto_artifact in all_lto_artifacts if lto_artifact.index] +
         [thinlto_param_file, thinlto_merged_object_file])

    build_variables = variables_extensions | setup_lto_indexing_variables(
        cc_toolchain,
        feature_configuration,
        # TODO(b/338618120): remove cheat using the root of one of the created outputs
        cc_internal.actions2ctx_cheat(actions).bin_dir.path,
        thinlto_param_file.path,
        thinlto_merged_object_file.path,
        lto_output_root_prefix,
        lto_obj_root_prefix,
    )

    cpp_config = cc_toolchain._cpp_configuration
    user_link_flags = linkopts + cpp_config.linkopts + cpp_config.lto_index_options()

    # We check that this action is not lto-indexing, or when it is, it's either for executable
    # or transitive or nodeps dynamic library.
    if not link_type.executable and not is_dynamic_library(link_type):
        fail("Can only do LTO on executables and dynamic libraries")

    if link_type.executable:
        action_name = "lto-index-for-executable"
    elif link_type == LINK_TARGET_TYPE.DYNAMIC_LIBRARY:
        action_name = "lto-index-for-dynamic-library"
    else:
        action_name = "lto-index-for-nodeps-dynamic-library"

    finalize_link_action(
        actions,
        "CppLTOIndexing",  # mnemonic
        action_name,
        link_type,
        linking_mode,
        False,  # stamping (we don't have linkstamps, nothing to stamp)
        feature_configuration,
        cc_toolchain,
        "LTO indexing %{output}",  # progress_message
        # Inputs:
        object_file_inputs = object_file_inputs,
        unique_libraries = unique_libraries,
        linkstamp_map = {},
        linkstamp_object_artifacts = [],
        linkstamp_object_file_inputs = [],
        user_link_flags = user_link_flags,
        # Custom user input files and variables:
        additional_build_variables = build_variables,
        # Outputs:
        output = output,
        interface_output = None,
        dynamic_library_solib_symlink_output = None,
        action_outputs = action_outputs,
        # LTO:
        lto_mapping = {},
        allow_lto_indexing = allow_lto_indexing,
        **link_action_args
    )

    return thinlto_param_file, thinlto_merged_object_file
