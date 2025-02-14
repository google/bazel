# Copyright 2023 The Bazel Authors. All rights reserved.
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

"""A helper for creating CcToolchainProvider."""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_toolchain_info.bzl", "CcToolchainInfo")
load(":common/cc/fdo/fdo_context.bzl", "create_fdo_context")
load(":common/paths.bzl", "paths")

cc_internal = _builtins.internal.cc_internal

_TOOL_PATH_ONLY_TOOLS = [
    "gcov-tool",
    "gcov",
    "llvm-profdata",
    "llvm-cov",
]

_REQUIRED_TOOLS = [
    "ar",
    "cpp",
    "gcc",
    "ld",
    "nm",
    "objdump",
    "strip",
]

_SYSROOT_START = "%sysroot%/"
_WORKSPACE_START = "%workspace%/"
_CROSSTOOL_START = "%crosstool_top%/"
_PACKAGE_START = "%package("
_PACKAGE_END = ")%"
_BUILTIN_INCLUDE_FILE_SUFFIX = "include/stdc-predef.h"

def _builtin_includes(libc):
    result = []
    for artifact in libc.to_list():
        if artifact.path.endswith(_BUILTIN_INCLUDE_FILE_SUFFIX):
            result.append(artifact)
    return result

def _legacy_cc_flags_make_variable(toolchain_make_vars):
    legacy_cc_flags = ""
    for variable in toolchain_make_vars:
        if variable[0] == "CC_FLAGS":
            legacy_cc_flags = variable[1]
    return legacy_cc_flags

def _additional_make_variables(toolchain_make_vars):
    make_vars = {}

    # The following are to be used to allow some build rules to avoid the limits on stack frame
    # sizes and variable-length arrays.
    # These variables are initialized here, but may be overridden by the getMakeVariables() checks.
    make_vars["STACK_FRAME_UNLIMITED"] = ""
    for variable in toolchain_make_vars:
        make_vars[variable[0]] = variable[1]
    make_vars.pop("CC_FLAGS", None)
    return make_vars

def _compute_tool_paths(target_label, toolchain_config_info, crosstool_top_path, sysroot_path):
    tool_paths_collector = {}
    for tool in toolchain_config_info.tool_paths():
        path_str = tool[1]
        if not paths.is_normalized(path_str):
            fail("The include path '" + path_str + "' is not normalized.")
        tool_paths_collector[tool[0]] = _resolve_path(target_label, path_str, sysroot_path, crosstool_top_path)

    # These tools can only be declared using tool paths, so action-only toolchains should still
    # be allowed to declared them while still being treated as an action-only toolchain. If a tool
    # that can be specified with actions is declared using paths, the toolchain will be treated as
    # a tool-path toolchain to enforce users to migrate their toolchain fully.
    contains_all = True
    for key in tool_paths_collector.keys():
        if key not in _TOOL_PATH_ONLY_TOOLS:
            contains_all = False
            break
    if contains_all:
        for tool in _REQUIRED_TOOLS:
            tool_paths_collector[tool] = paths.get_relative(crosstool_top_path, tool)
    else:
        for tool in _REQUIRED_TOOLS:
            if tool not in tool_paths_collector.keys():
                fail("Tool path for '" + tool + "' is missing")
    return tool_paths_collector

def _resolve_path(target_label, s, sysroot, crosstool_path):
    """ Resolve the given include directory.

    If it starts with %sysroot%/, that part is replaced with the actual sysroot.

    If it starts with %workspace%/, that part is replaced with the empty string (essentially
    making it relative to the build directory).

    If it starts with %crosstool_top%/ or is any relative path, it is interpreted relative to
    the crosstool top. The use of assumed-crosstool-relative specifications is considered
    deprecated, and all such uses should eventually be replaced by "%crosstool_top%/".

    If it is of the form %package(@repository//my/package)%/folder, then it is interpreted as
    the named folder in the appropriate package. All of the normal package syntax is supported. The
    /folder part is optional.

    It is illegal if it starts with a % and does not match any of the above forms to avoid
    accidentally silently ignoring misspelled prefixes.

    If it is absolute, it remains unchanged.
    """
    package_end_index = s.find(_PACKAGE_END)
    if package_end_index != -1 and s.startswith(_PACKAGE_START):
        package = s[len(_PACKAGE_START):package_end_index]
        if package.find(":") >= 0:
            fail("invalid package identifier '" + package + "': contains ':'")

        # This is necessary to avoid the hard work of parsing,
        # and use an already existing API.
        dummy_label = target_label.relative(package + ":dummy_target")
        repo_prefix = dummy_label.workspace_root
        path_prefix = paths.get_relative(repo_prefix, dummy_label.package)
        path_start_index = package_end_index + len(_PACKAGE_END)
        if path_start_index + 1 < len(s):
            if s[path_start_index] != "/":
                fail("The path in the package for '" + s + "' is not valid")
            path_string = s[path_start_index + 1:]
        else:
            path_string = ""
    elif s.startswith(_SYSROOT_START):
        if sysroot == None:
            fail("A %sysroot% prefix is only allowed if the default_sysroot option is set")
        path_prefix = sysroot
        path_string = s[len(_SYSROOT_START):len(s)]
    elif s.startswith(_WORKSPACE_START):
        path_prefix = ""
        path_string = s[len(_WORKSPACE_START):len(s)]
    else:
        path_prefix = crosstool_path
        if s.startswith(_CROSSTOOL_START):
            path_string = s[len(_CROSSTOOL_START):len(s)]
        elif s.startswith("%"):
            fail("The include path '" + s + "' has an " + "unrecognized %prefix%")
        else:
            path_string = s

    return paths.get_relative(path_prefix, path_string)

def _get_cc_toolchain_vars(cpp_config, sysroot):
    variables = {}
    min_os_version = cpp_config.minimum_os_version()
    if min_os_version != None:
        variables["minimum_os_version"] = min_os_version
    if sysroot != None:
        variables["sysroot"] = sysroot
    return variables

def get_cc_toolchain_provider(ctx, attributes):
    """Constructs a CcToolchainProvider instance.

    Args:
        ctx: rule context.
        attributes: encapsulated attributes of cc_toolchain rule.
    Returns:
        A constructed CcToolchainProvider instance.
    """
    toolchain_config_info = attributes.cc_toolchain_config_info
    tools_directory = cc_helper.package_exec_path(
        ctx,
        attributes.cc_toolchain_label.package,
        ctx.configuration.is_sibling_repository_layout(),
    )
    default_sysroot = None
    if toolchain_config_info.builtin_sysroot() != "":
        default_sysroot = toolchain_config_info.builtin_sysroot()
    if attributes.libc_top_label == None:
        sysroot = default_sysroot
    else:
        sysroot = attributes.libc_top_label.package
    tool_paths = _compute_tool_paths(ctx.label, toolchain_config_info, tools_directory, sysroot)
    toolchain_features = cc_internal.cc_toolchain_features(toolchain_config_info = toolchain_config_info, tools_directory = tools_directory)
    fdo_context = create_fdo_context(
        llvm_profdata = tool_paths.get("llvm-profdata"),
        all_files = attributes.all_files,
        zipper = attributes.zipper,
        cc_toolchain_config_info = toolchain_config_info,
        coverage_enabled = ctx.configuration.coverage_enabled,
    )
    if fdo_context == None:
        return None
    runtime_solib_dir_base = attributes.runtime_solib_dir_base
    runtime_solib_dir = paths.get_relative(ctx.bin_dir.path, runtime_solib_dir_base)
    solib_directory = "_solib_" + toolchain_config_info.target_cpu()

    static_runtime_lib = attributes.static_runtime_lib
    if static_runtime_lib != None:
        static_runtime_link_inputs = static_runtime_lib[DefaultInfo].files
    else:
        static_runtime_link_inputs = None

    dynamic_runtime_lib = attributes.dynamic_runtime_lib
    if dynamic_runtime_lib != None:
        dynamic_runtime_link_symlinks_elems = []
        for artifact in dynamic_runtime_lib[DefaultInfo].files.to_list():
            if cc_helper.is_valid_shared_library_artifact(artifact):
                dynamic_runtime_link_symlinks_elems.append(cc_internal.solib_symlink_action(
                    ctx = ctx,
                    artifact = artifact,
                    solib_directory = solib_directory,
                    runtime_solib_dir_base = runtime_solib_dir_base,
                ))
        if len(dynamic_runtime_link_symlinks_elems) == 0:
            dynamic_runtime_link_symlinks = depset()
        else:
            dynamic_runtime_link_symlinks = depset(direct = dynamic_runtime_link_symlinks_elems)
    else:
        dynamic_runtime_link_symlinks = None

    module_map = None
    if attributes.module_map != None and attributes.module_map_artifact != None:
        module_map = cc_common.create_module_map(file = attributes.module_map_artifact, name = "crosstool")

    cc_compilation_context = cc_common.create_compilation_context(module_map = module_map)

    builtin_include_directories = []
    for s in toolchain_config_info.cxx_builtin_include_directories():
        builtin_include_directories.append(_resolve_path(ctx.label, s, sysroot, tools_directory))

    build_variables_dict = _get_cc_toolchain_vars(ctx.fragments.cpp, sysroot)
    build_variables = cc_internal.cc_toolchain_variables(
        vars = build_variables_dict,
    )

    return CcToolchainInfo(
        cpp_configuration = ctx.fragments.cpp,
        toolchain_config_info = toolchain_config_info,
        toolchain_features = toolchain_features,
        crosstool_top_path = tools_directory,
        static_runtime_lib_depset = static_runtime_link_inputs,
        dynamic_runtime_lib_depset = dynamic_runtime_link_symlinks,
        dynamic_runtime_solib_dir = runtime_solib_dir,
        cc_info = CcInfo(
            compilation_context = cc_compilation_context,
        ),
        builtin_include_files = _builtin_includes(attributes.libc),
        built_in_include_directories = builtin_include_directories,
        sysroot = sysroot,
        fdo_context = fdo_context,
        is_tool_configuration = ctx.configuration.is_tool_configuration(),
        is_sibling_repository_layout = ctx.configuration.is_sibling_repository_layout(),
        stamp_binaries = ctx.configuration.stamp_binaries(),
        tool_paths = tool_paths,
        default_sysroot = default_sysroot,
        # The runtime sysroot should really be set from --grte_top. However, currently libc has
        # no way to set the sysroot. The CROSSTOOL file does set the runtime sysroot, in the
        # builtin_sysroot field. This implies that you can not arbitrarily mix and match
        # Crosstool and libc versions, you must always choose compatible ones.
        runtime_sysroot = default_sysroot,
        solib_dir = solib_directory,
        additional_make_variables = _additional_make_variables(toolchain_config_info.make_variables()),
        legacy_cc_flags_make_variable = _legacy_cc_flags_make_variable(toolchain_config_info.make_variables()),
        objcopy_executable = tool_paths.get("objcopy", ""),
        compiler_executable = tool_paths.get("gcc", ""),
        preprocessor_executable = tool_paths.get("cpp", ""),
        nm_executable = tool_paths.get("nm", ""),
        objdump_executable = tool_paths.get("objdump", ""),
        ar_executable = tool_paths.get("ar", ""),
        strip_executable = tool_paths.get("strip", ""),
        ld_executable = tool_paths.get("ld", ""),
        gcov_executable = tool_paths.get("gcov", ""),
        build_variables_dict = build_variables_dict,
        build_variables = build_variables,
        all_files = attributes.all_files,
        all_files_including_libc = attributes.all_files_including_libc,
        compiler_files = attributes.compiler_files,
        compiler_files_without_includes = attributes.compiler_files_without_includes,
        strip_files = attributes.strip_files,
        objcopy_files = attributes.objcopy_files,
        as_files = attributes.as_files,
        ar_files = attributes.ar_files,
        linker_files = attributes.linker_files,
        if_so_builder = attributes.if_so_builder,
        dwp_files = attributes.dwp_files,
        coverage_files = attributes.coverage_files,
        supports_param_files = attributes.supports_param_files,
        supports_header_parsing = attributes.supports_header_parsing,
        link_dynamic_library_tool = attributes.link_dynamic_library_tool,
        grep_includes = attributes.grep_includes,
        aggregate_ddi = attributes.aggregate_ddi,
        generate_modmap = attributes.generate_modmap,
        allowlist_for_layering_check = attributes.allowlist_for_layering_check,
        build_info_files = attributes.build_info_files,
        toolchain_label = ctx.label,
    )
