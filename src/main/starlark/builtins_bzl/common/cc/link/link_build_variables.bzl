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
"""Functions that populate link build variables.

Link build variables are a dictionary of string named variables that are used to generate C++
linking command line. The values of variables may be strings, string lists or
even lists of structures.

Generally C++ linking code sets up the values, however users may also use custom variables.

The final C++ linking command line is generated from these variables and the specification
given by the C++ toolchain. The specification can pass flags, values of variables to the linker.
In more complex cases like libraries_to_link, the specification iterates over the list, selecting
and formatting different types of libraries. For an example specification,
see `unix_cc_toolchain_config.bzl`
"""

load(":common/cc/cc_helper_internal.bzl", "artifact_category", "should_create_per_object_debug_info")
load(":common/paths.bzl", "paths")

cc_internal = _builtins.internal.cc_internal

# Enum covering all build variables we create for all various C++ linking actions
LINK_BUILD_VARIABLES = struct(
    # execpath of the output of the linker.
    OUTPUT_EXECPATH = "output_execpath",

    # Flags providing files to link as inputs in the linker invocation
    LIBRARIES_TO_LINK = "libraries_to_link",

    # Entries in the linker runtime search path =usually set by -rpath flag)
    RUNTIME_LIBRARY_SEARCH_DIRECTORIES = "runtime_library_search_directories",
    # Entries in the linker search path =usually set by -L flag
    LIBRARY_SEARCH_DIRECTORIES = "library_search_directories",
    # The name of the runtime solib symlink of the shared library.
    RUNTIME_SOLIB_NAME = "runtime_solib_name",

    # "yes"|"no" depending on whether interface library should be generated.
    GENERATE_INTERFACE_LIBRARY = "generate_interface_library",
    # Path to the interface library builder tool.
    INTERFACE_LIBRARY_BUILDER = "interface_library_builder_path",
    # Input for the interface library ifso builder tool.
    INTERFACE_LIBRARY_INPUT = "interface_library_input_path",
    # Path where to generate interface library using the ifso builder tool.
    INTERFACE_LIBRARY_OUTPUT = "interface_library_output_path",

    # Linker flags coming from the --linkopt or linkopts attribute.
    USER_LINK_FLAGS = "user_link_flags",
    # Presence of this variable indicates that PIC code should be generated.
    FORCE_PIC = "force_pic",
    # Presence of this variable indicates that the debug symbols should be stripped.
    STRIP_DEBUG_SYMBOLS = "strip_debug_symbols",
    # Truthy when current action is a cc_test linking action, falsey otherwise.
    IS_CC_TEST = "is_cc_test",
    # Presence of this variable indicates that files were compiled with fission =debug info is in
    # .dwo files instead of .o files and linker needs to know.
    IS_USING_FISSION = "is_using_fission",
    # Location of linker param file created by bazel to overcome command line length limit
    LINKER_PARAM_FILE = "linker_param_file",

    # Thinlto param file produced by thinlto-indexing action consumed by the final link action.
    THINLTO_PARAM_FILE = "thinlto_param_file",
    THINLTO_OPTIONAL_PARAMS_FILE = "thinlto_optional_params_file",
    # Location where thinlto should write thinlto_param_file flags when indexing.
    THINLTO_INDEXING_PARAM_FILE = "thinlto_indexing_param_file",
    THINLTO_PREFIX_REPLACE = "thinlto_prefix_replace",
    # A build variable to let the LTO indexing step know how to map from the minimized bitcode file
    # to the full bitcode file used by the LTO Backends.
    THINLTO_OBJECT_SUFFIX_REPLACE = "thinlto_object_suffix_replace",
    # A build variable for the path to the merged object file, which is an object file that is
    # created during the LTO indexing step and needs to be passed to the final link.
    THINLTO_MERGED_OBJECT_FILE = "thinlto_merged_object_file",

    # Path to the fdo instrument.
    FDO_INSTRUMENT_PATH = "fdo_instrument_path",
    # Path to the context sensitive fdo instrument.
    CS_FDO_INSTRUMENT_PATH = "cs_fdo_instrument_path",
    # Path to the Propeller Optimize linker profile artifact
    PROPELLER_OPTIMIZE_LD_PATH = "propeller_optimize_ld_path",
)

# TODO(b/338618120): Pass artifacts/files instead of strings to these function. Artifacts have
# better memory footprint.

# TODO(b/338618120): Except for setup_common_linking_variables, inline other two methods into the
# linking code. This way whole "features" will be co-located and there will potentially
# be less duplication, for example on conditions checking if feature is enabled.

# LINT.IfChange

# IMPORTANT: This function is public API exposed on cc_common module!
def create_link_variables(
        *,
        cc_toolchain,
        feature_configuration,
        output_file = None,
        runtime_library_search_directories = [],
        library_search_directories = [],
        user_link_flags = [],
        param_file = None,
        is_using_linker = True,
        is_linking_dynamic_library = False,
        must_keep_debug = True,
        use_test_only_flags = False,
        is_static_linking_mode = None):
    """Returns common link build variables used for both linking and thin LTO indexing actions.

    The implementation also includes variables specified by cc_toolchain.

    Args:
      cc_toolchain: cc_toolchain for which we are creating build variables.
      feature_configuration: Feature configuration to be queried.
      output_file: (str) Optional output file path. Used also as an input to interface_library builder.
      runtime_library_search_directories: (depset[str]) Directories where loader will look for
        libraries at runtime.
      library_search_directories: (depset[str]) Directories where linker will look for libraries at
        link time.
      user_link_flags: List of additional link flags (linkopts).
      param_file: (str|None) Optional param file path.
      is_using_linker: (bool) True when using linker, False when archiver. Caller is responsible for
        keeping this in sync with action name used (is_using_linker = True for linking
        executable or dynamic library, is_using_linker = False for archiving static
        library)
      is_linking_dynamic_library: (bool) True when creating dynamic library, False when executable
        or static library. Caller is responsible for keeping this in sync with action name used.
      must_keep_debug: (bool) When set to False, bazel will expose 'strip_debug_symbols' variable,
        which is usually used to use the linker to strip debug symbols from the output file.
      use_test_only_flags: When set to true, 'is_cc_test' variable will be set.
      is_static_linking_mode: (bool) Unused.

    Returns:
      (CcToolchainVariables) common linking build variables
    """

    # LINT.ThenChange(//src/main/java/com/google/devtools/build/lib/rules/cpp/CcModule.java)
    if feature_configuration.is_enabled("fdo_instrument"):
        fail("FDO instrumentation not supported")

    vars = setup_common_linking_variables(
        cc_toolchain,
        feature_configuration,
        [],  # libraries_to_link
        runtime_library_search_directories,
        library_search_directories,
        user_link_flags,
        param_file,
        is_using_linker,
        is_linking_dynamic_library,
        must_keep_debug,
        use_test_only_flags,
        is_static_linking_mode,
    )

    # output exec path
    if output_file:
        if type(output_file) != type(""):
            fail("Parameter 'output_file' expected String, got '%s'" % type(output_file))
        vars["output_execpath"] = output_file
    return cc_internal.cc_toolchain_variables(vars = vars)

def setup_common_linking_variables(
        cc_toolchain,
        feature_configuration,
        libraries_to_link = [],
        runtime_library_search_directories = [],
        library_search_directories = [],
        user_link_flags = [],
        param_file = None,
        is_using_linker = True,
        is_linking_dynamic_library = False,
        must_keep_debug = True,
        use_test_only_flags = False,
        is_static_linking_mode = None):
    """Returns common link build variables used for both linking and thin LTO indexing actions.

    The implementation also includes variables specified by cc_toolchain.

    Args:
      cc_toolchain: cc_toolchain for which we are creating build variables.
      feature_configuration: Feature configuration to be queried.
      libraries_to_link: (list[LibraryToLinkValue]) List of libraries passed to the linker.
      runtime_library_search_directories: (depset[str]) Directories where loader will look for
        libraries at runtime.
      library_search_directories: (depset[str]) Directories where linker will look for libraries at
        link time.
      user_link_flags: List of additional link flags (linkopts).
      param_file: (str|None) Optional param file path.
      is_using_linker: (bool) True when using linker, False when archiver. Caller is responsible for
        keeping this in sync with action name used (is_using_linker = True for linking
        executable or dynamic library, is_using_linker = False for archiving static
        library)
      is_linking_dynamic_library: (bool) True when creating dynamic library, False when executable
        or static library. Caller is responsible for keeping this in sync with action name used.
      must_keep_debug: (bool) When set to False, bazel will expose 'strip_debug_symbols' variable,
        which is usually used to use the linker to strip debug symbols from the output file.
      use_test_only_flags: When set to true, 'is_cc_test' variable will be set.
      is_static_linking_mode: (bool) Unused.

    Returns:
        (dict[str, ?]) common linking variables
    """
    vars = dict(cc_toolchain._build_variables_dict)
    cpp_config = cc_toolchain._cpp_configuration

    # TODO(b/338618120): Reorder the statements to match order of parameters.
    # TODO(b/65151735): Remove once we migrate crosstools to features: param_file,
    # is_linking_dynamic_library, is_using_linker, is_static_linking_mode.

    # pic
    if cpp_config.force_pic():
        vars[LINK_BUILD_VARIABLES.FORCE_PIC] = ""

    if not must_keep_debug and cpp_config.should_strip_binaries():
        vars[LINK_BUILD_VARIABLES.STRIP_DEBUG_SYMBOLS] = ""

    if (is_using_linker and
        should_create_per_object_debug_info(feature_configuration, cpp_config)):
        vars[LINK_BUILD_VARIABLES.IS_USING_FISSION] = ""

    vars[LINK_BUILD_VARIABLES.IS_CC_TEST] = use_test_only_flags

    vars[LINK_BUILD_VARIABLES.RUNTIME_LIBRARY_SEARCH_DIRECTORIES] = runtime_library_search_directories

    vars[LINK_BUILD_VARIABLES.LIBRARIES_TO_LINK] = libraries_to_link

    vars[LINK_BUILD_VARIABLES.LIBRARY_SEARCH_DIRECTORIES] = library_search_directories

    if param_file:
        # TODO(b/338618120): Starlark command line doesn't pass a file path here, because Starlark
        # APIS abstract the file param file away. Provide a separate mechanism to extract the
        # formatting of param file path from the specification.
        vars[LINK_BUILD_VARIABLES.LINKER_PARAM_FILE] = param_file

    if feature_configuration.is_enabled("fdo_instrument"):
        if getattr(cc_toolchain._fdo_context, "branch_fdo_profile", None):
            fail("Can't use --feature=fdo_instrument together with --fdo_profile")
        if not cpp_config.fdo_instrument():
            fail("When using --feature=fdo_instrument, you need to set --fdo_instrument as well")
        vars[LINK_BUILD_VARIABLES.FDO_INSTRUMENT_PATH] = cpp_config.fdo_instrument()
    elif feature_configuration.is_enabled("cs_fdo_instrument"):
        if not cpp_config.cs_fdo_instrument():
            fail("When using --feature=cs_fdo_instrument, you need to set --cs_fdo_instrument as well")
        vars[LINK_BUILD_VARIABLES.CS_FDO_INSTRUMENT_PATH] = cpp_config.cs_fdo_instrument()

    # For now, silently ignore linkopts if this is a static library
    user_link_flags = user_link_flags if is_using_linker else []
    if is_linking_dynamic_library:
        vars[LINK_BUILD_VARIABLES.USER_LINK_FLAGS] = _remove_pie(user_link_flags)
    else:
        vars[LINK_BUILD_VARIABLES.USER_LINK_FLAGS] = user_link_flags
    return vars

def _remove_pie(flags):
    return [flag for flag in flags if flag != "-pie" and flag != "-Wl,-pie"]

_DONT_GENERATE_INTERFACE_LIBRARY = {
    LINK_BUILD_VARIABLES.GENERATE_INTERFACE_LIBRARY: "no",
    LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_BUILDER: "ignored",
    LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_INPUT: "ignored",
    LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_OUTPUT: "ignored",
}

def setup_linking_variables(
        cc_toolchain,
        feature_configuration,
        output_file,
        runtime_solib_name,
        interface_library_output,
        thinlto_param_file):
    """Returns additional build variables used by regular linking action.

    Args:
      cc_toolchain: cc_toolchain for which we are creating build variables.
      feature_configuration: Feature configuration to be queried.
      output_file: (File) Optional output file. Used also as an input to interface_library builder.
      runtime_solib_name: (str) The name of the runtime solib symlink of the shared library.
      interface_library_output: (File) Optional interface library to generate using the ifso builder tool.
      thinlto_param_file: (File) Optional Thinlto param file consumed by the final link action. (Produced
        by thin-lto indexing action)
    Returns:
        (dict[str, ?]) linking build variables
    """
    vars = {}

    if thinlto_param_file:
        # This is a normal link action and we need to use param file created by lto-indexing.
        vars[LINK_BUILD_VARIABLES.THINLTO_PARAM_FILE] = thinlto_param_file

    # output exec path
    vars[LINK_BUILD_VARIABLES.OUTPUT_EXECPATH] = output_file

    vars[LINK_BUILD_VARIABLES.RUNTIME_SOLIB_NAME] = runtime_solib_name

    fdo_context = cc_toolchain._fdo_context
    if (not cc_toolchain._is_tool_configuration and
        fdo_context and
        feature_configuration.is_enabled("propeller_optimize") and
        fdo_context.propeller_optimize_info and
        fdo_context.propeller_optimize_info.ld_profile):
        vars[LINK_BUILD_VARIABLES.PROPELLER_OPTIMIZE_LD_PATH] = fdo_context.propeller_optimize_info.ld_profile

    # ifso variables
    should_generate_interface_library = output_file and cc_toolchain._if_so_builder and interface_library_output
    if should_generate_interface_library:
        vars[LINK_BUILD_VARIABLES.GENERATE_INTERFACE_LIBRARY] = "yes"
        vars[LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_BUILDER] = cc_toolchain._if_so_builder
        vars[LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_INPUT] = output_file
        vars[LINK_BUILD_VARIABLES.INTERFACE_LIBRARY_OUTPUT] = interface_library_output
    else:
        vars = vars | _DONT_GENERATE_INTERFACE_LIBRARY

    return vars

def setup_lto_indexing_variables(
        cc_toolchain,
        feature_configuration,
        bin_directory_path,
        thinlto_param_file,
        thinlto_merged_object_file,
        lto_output_root_prefix,
        lto_obj_root_prefix):
    """Returns additional build variables used by LTO indexing actions.

    Args:
      cc_toolchain: cc_toolchain for which we are creating build variables.
      feature_configuration: Feature configuration to be queried.
      bin_directory_path: (str)  # str
      thinlto_param_file: (str) Thinlto param file produced by thinlto-indexing action (and consumed
        by the final link action).
      thinlto_merged_object_file: (str) Path to the merged object file, which is an object file.
      lto_output_root_prefix: (str) lto output root directory path
      lto_obj_root_prefix: (str) lto obj root directory path
    Returns:
        (dict[str, ?]) linking build variables
    """
    vars = {}

    # This is a lto-indexing action and we want it to populate param file.
    vars[LINK_BUILD_VARIABLES.THINLTO_INDEXING_PARAM_FILE] = thinlto_param_file

    # TODO(b/33846234): Remove once all the relevant crosstools don't depend on the variable.
    vars[LINK_BUILD_VARIABLES.THINLTO_OPTIONAL_PARAMS_FILE] = "=" + thinlto_param_file

    # Given "fullbitcode_prefix;thinlto_index_prefix;native_object_prefix", replaces
    # fullbitcode_prefix with thinlto_index_prefix to generate the index and imports files.
    # fullbitcode_prefix is the empty string because we are appending a prefix to the fullbitcode
    # instead of replacing it. This argument is passed to the linker.
    # The native objects generated after the LTOBackend action are stored in a directory by
    # replacing the prefix "fullbitcode_prefix" with "native_object_prefix", and this is used
    # when generating the param file in the indexing step, which will be used during the final
    # link step.
    if lto_output_root_prefix != lto_obj_root_prefix:
        # TODO(b/338618120): prepend bin_directory_path to lto_{output,obj}_root_prefix
        #  and remove bin_directory_path parameter
        vars[LINK_BUILD_VARIABLES.THINLTO_PREFIX_REPLACE] = (
            ";" + paths.get_relative(bin_directory_path, lto_output_root_prefix) + "/;" +
            paths.get_relative(bin_directory_path, lto_obj_root_prefix) + "/"
        )
    else:
        vars[LINK_BUILD_VARIABLES.THINLTO_PREFIX_REPLACE] = (
            ";" + paths.get_relative(bin_directory_path, lto_output_root_prefix) + "/"
        )

    if not feature_configuration.is_enabled("no_use_lto_indexing_bitcode_file"):
        object_file_extension = cc_internal.get_artifact_name_extension_for_category(
            cc_toolchain,
            artifact_category.OBJECT_FILE,
        )

        # TODO(b/338618120): ".indexing.o" should be coming from Starlark definitions of CppFileTypes
        vars[LINK_BUILD_VARIABLES.THINLTO_OBJECT_SUFFIX_REPLACE] = ".indexing.o;" + object_file_extension

    vars[LINK_BUILD_VARIABLES.THINLTO_MERGED_OBJECT_FILE] = thinlto_merged_object_file

    vars = vars | _DONT_GENERATE_INTERFACE_LIBRARY

    return vars
