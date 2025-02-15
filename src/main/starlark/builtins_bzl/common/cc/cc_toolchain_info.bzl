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

"""
Definition of CcToolchainInfo provider.
"""

load(":common/cc/cc_common.bzl", "cc_common")

cc_internal = _builtins.internal.cc_internal

def _needs_pic_for_dynamic_libraries(*, feature_configuration):
    return cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "supports_pic")

def _static_runtime_lib(static_runtime_lib):
    def static_runtime_lib_func(*, feature_configuration):
        if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "static_link_cpp_runtimes"):
            if static_runtime_lib == None:
                fail("Toolchain supports embedded runtimes, but didn't provide static_runtime_lib attribute.")
            return static_runtime_lib
        return depset()

    return static_runtime_lib_func

def _dynamic_runtime_lib(dynamic_runtime_lib):
    def dynamic_runtime_lib_func(*, feature_configuration):
        if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "static_link_cpp_runtimes"):
            if dynamic_runtime_lib == None:
                fail("Toolchain supports embedded runtimes, but didn't provide dynamic_runtime_lib attribute.")
            return dynamic_runtime_lib
        return depset()

    return dynamic_runtime_lib_func

def _create_cc_toolchain_info(
        *,
        built_in_include_directories,
        static_runtime_lib_depset,
        dynamic_runtime_lib_depset,
        sysroot,
        toolchain_config_info,
        dynamic_runtime_solib_dir,
        objcopy_executable,
        compiler_executable,
        preprocessor_executable,
        nm_executable,
        objdump_executable,
        ar_executable,
        strip_executable,
        ld_executable,
        gcov_executable,
        runtime_sysroot,
        tool_paths,
        solib_dir,
        fdo_context,
        legacy_cc_flags_make_variable,
        additional_make_variables,
        crosstool_top_path,
        toolchain_features,
        toolchain_label,
        cpp_configuration,
        is_tool_configuration,
        is_sibling_repository_layout,
        stamp_binaries,
        default_sysroot,
        builtin_include_files,
        build_variables_dict,
        build_variables,
        cc_info,
        all_files,
        all_files_including_libc,
        compiler_files,
        compiler_files_without_includes,
        strip_files,
        as_files,
        ar_files,
        linker_files,
        if_so_builder,
        dwp_files,
        coverage_files,
        supports_param_files,
        supports_header_parsing,
        link_dynamic_library_tool,
        grep_includes,
        allowlist_for_layering_check,
        build_info_files,
        objcopy_files,
        aggregate_ddi,
        generate_modmap):
    cc_toolchain_info = dict(
        needs_pic_for_dynamic_libraries = (lambda *, feature_configuration: True) if cpp_configuration.force_pic() else _needs_pic_for_dynamic_libraries,
        built_in_include_directories = built_in_include_directories,
        all_files = all_files,
        static_runtime_lib = _static_runtime_lib(static_runtime_lib_depset),
        dynamic_runtime_lib = _dynamic_runtime_lib(dynamic_runtime_lib_depset),
        sysroot = sysroot,
        compiler = toolchain_config_info.compiler(),
        libc = toolchain_config_info.target_libc(),
        cpu = toolchain_config_info.target_cpu(),
        target_gnu_system_name = toolchain_config_info.target_system_name(),
        toolchain_id = toolchain_config_info.toolchain_id(),
        dynamic_runtime_solib_dir = dynamic_runtime_solib_dir,
        objcopy_executable = objcopy_executable,
        compiler_executable = compiler_executable,
        preprocessor_executable = preprocessor_executable,
        nm_executable = nm_executable,
        objdump_executable = objdump_executable,
        ar_executable = ar_executable,
        strip_executable = strip_executable,
        ld_executable = ld_executable,
        gcov_executable = gcov_executable,
        additional_link_outputs = toolchain_config_info.additional_link_outputs(),
        _runtime_sysroot = runtime_sysroot,
        _as_files = as_files,
        _ar_files = ar_files,
        _strip_files = strip_files,
        _tool_paths = tool_paths,
        _solib_dir = solib_dir,
        _linker_files = linker_files,
        _coverage_files = coverage_files,
        _fdo_context = fdo_context,
        _compiler_files = compiler_files,
        _dwp_files = dwp_files,
        _builtin_include_files = builtin_include_files,
        _legacy_cc_flags_make_variable = legacy_cc_flags_make_variable,
        _additional_make_variables = additional_make_variables,
        _all_files_including_libc = all_files_including_libc,
        _abi = toolchain_config_info.abi_version(),
        _abi_glibc_version = toolchain_config_info.abi_libc_version(),
        _crosstool_top_path = crosstool_top_path,
        _build_info_files = build_info_files,
        _supports_header_parsing = supports_header_parsing,
        _supports_param_files = supports_param_files,
        _toolchain_features = toolchain_features,
        _toolchain_label = toolchain_label,
        _cpp_configuration = cpp_configuration,
        _link_dynamic_library_tool = link_dynamic_library_tool,
        _grep_includes = grep_includes,
        _if_so_builder = if_so_builder,
        _is_tool_configuration = is_tool_configuration,
        _is_sibling_repository_layout = is_sibling_repository_layout,
        _stamp_binaries = stamp_binaries,
        _default_sysroot = default_sysroot,
        _static_runtime_lib_depset = static_runtime_lib_depset,
        _dynamic_runtime_lib_depset = dynamic_runtime_lib_depset,
        _compiler_files_without_includes = compiler_files_without_includes,
        _build_variables_dict = build_variables_dict,
        _build_variables = build_variables,
        _allowlist_for_layering_check = allowlist_for_layering_check,
        _cc_info = cc_info,
        _objcopy_files = objcopy_files,
        _aggregate_ddi = aggregate_ddi,
        _generate_modmap = generate_modmap,
    )
    return cc_toolchain_info

CcToolchainInfo, _ = provider(
    doc = "Information about a C++ compiler used by the cc_* rules.",
    fields = {
        # Public fields used by Starlark.
        "needs_pic_for_dynamic_libraries": """
            Returns true if this rule's compilations should apply -fPIC, false otherwise.
            Determines if we should apply -fPIC for this rule's C++ compilations depending
            on the C++ toolchain and presence of `--force_pic` Bazel option.""",
        "built_in_include_directories": "Returns the list of built-in directories of the compiler.",
        "all_files": "Returns all toolchain files (so they can be passed to actions using this toolchain as inputs).",
        "static_runtime_lib": """
            Returns the files from `static_runtime_lib` attribute (so they can be passed to actions
            using this toolchain as inputs). The caller should check whether the
            feature_configuration enables `static_link_cpp_runtimes` feature (if not,
            neither `static_runtime_lib` nor `dynamic_runtime_lib` should be used), and
            use `dynamic_runtime_lib` if dynamic linking mode is active.""",
        "dynamic_runtime_lib": """
            Returns the files from `dynamic_runtime_lib` attribute (so they can be passed to
            actions using this toolchain as inputs). The caller can check whether the
            feature_configuration enables `static_link_cpp_runtimes` feature (if not, neither
            `static_runtime_lib` nor `dynamic_runtime_lib` have to be used), and use
            `static_runtime_lib` if static linking mode is active.""",
        "sysroot": """
            Returns the sysroot to be used. If the toolchain compiler does not support
            different sysroots, or the sysroot is the same as the default sysroot, then
            this method returns <code>None</code>.""",
        "compiler": "C++ compiler.",
        "libc": "libc version string.",
        "cpu": "Target CPU of the C++ toolchain.",
        "target_gnu_system_name": "The GNU System Name.",
        "toolchain_id": "",
        "dynamic_runtime_solib_dir": "",
        "objcopy_executable": "The path to the objcopy binary.",
        "compiler_executable": "The path to the compiler binary.",
        "preprocessor_executable": "The path to the preprocessor binary.",
        "nm_executable": "The path to the nm binary.",
        "objdump_executable": "The path to the objdump binary.",
        "ar_executable": "The path to the ar binary.",
        "strip_executable": "The path to the strip binary.",
        "ld_executable": "The path to the ld binary.",
        "gcov_executable": "The path to the gcov binary.",
        "additional_link_outputs": "List of additional suffixes to output",
        "_runtime_sysroot": """
            INTERNAL API, DO NOT USE!
            Returns the runtime sysroot, where the dynamic linker and system libraries are found at
            runtime. This is usually an absolute path. If the toolchain compiler does not
            support sysroots then this method returns <code>None></code>.""",
        # Private fields used by Starlark.
        "_as_files": "INTERNAL API, DO NOT USE!",
        "_ar_files": "INTERNAL API, DO NOT USE!",
        "_strip_files": "INTERNAL API, DO NOT USE!",
        "_tool_paths": "INTERNAL API, DO NOT USE!",
        "_solib_dir": "INTERNAL API, DO NOT USE!",
        "_linker_files": "INTERNAL API, DO NOT USE!",
        "_coverage_files": "INTERNAL API, DO NOT USE!",
        # WARNING: We don't like FdoContext. Its fdoProfilePath is pure path
        # and that is horrible as it breaks many Bazel assumptions! Don't do bad stuff with it, don't
        # take inspiration from it.
        "_fdo_context": "INTERNAL API, DO NOT USE!",
        "_compiler_files": "INTERNAL API, DO NOT USE!",
        "_dwp_files": "INTERNAL API, DO NOT USE!",
        "_builtin_include_files": "INTERNAL API, DO NOT USE!",
        # TODO(b/65151735): Remove when cc_flags is entirely from features.
        "_legacy_cc_flags_make_variable": "INTERNAL API, DO NOT USE!",
        "_additional_make_variables": "INTERNAL API, DO NOT USE!",
        "_all_files_including_libc": "INTERNAL API, DO NOT USE!",
        "_abi": "INTERNAL API, DO NOT USE!",
        "_abi_glibc_version": "INTERNAL API, DO NOT USE!",
        "_crosstool_top_path": "INTERNAL API, DO NOT USE!",
        "_build_info_files": "INTERNAL API, DO NOT USE!",
        "_build_variables_dict": "INTERNAL API, DO NOT USE!",
        "_build_variables": "INTERNAL API, DO NOT USE!",
        # Fields still used by native code - will be used by Starlark in the future.
        "_supports_header_parsing": "INTERNAL API, DO NOT USE!",
        "_supports_param_files": "INTERNAL API, DO NOT USE!",
        "_toolchain_features": "INTERNAL API, DO NOT USE!",
        "_toolchain_label": "INTERNAL API, DO NOT USE!",
        "_cpp_configuration": "INTERNAL API, DO NOT USE!",
        "_link_dynamic_library_tool": "INTERNAL API, DO NOT USE!",
        "_grep_includes": "INTERNAL API, DO NOT USE!",
        "_if_so_builder": "INTERNAL API, DO NOT USE!",
        "_is_tool_configuration": "INTERNAL API, DO NOT USE!",
        "_is_sibling_repository_layout": "INTERNAL API, DO NOT USE!",
        "_stamp_binaries": "INTERNAL API, DO NOT USE!",
        "_default_sysroot": "INTERNAL API, DO NOT USE!",
        "_static_runtime_lib_depset": "INTERNAL API, DO NOT USE!",
        "_dynamic_runtime_lib_depset": "INTERNAL API, DO NOT USE!",
        "_compiler_files_without_includes": "INTERNAL API, DO NOT USE!",
        "_allowlist_for_layering_check": "INTERNAL API, DO NOT USE!",
        "_cc_info": "INTERNAL API, DO NOT USE!",
        "_objcopy_files": "INTERNAL API, DO NOT USE!",
        "_aggregate_ddi": "INTERNAL API, DO NOT USE!",
        "_generate_modmap": "INTERNAL API, DO NOT USE!",
    },
    init = _create_cc_toolchain_info,
)
