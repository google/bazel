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

"""Utilities related to C++ support."""

load(
    ":common/cc/cc_helper_internal.bzl",
    _CREATE_COMPILE_ACTION_API_ALLOWLISTED_PACKAGES = "CREATE_COMPILE_ACTION_API_ALLOWLISTED_PACKAGES",
    _PRIVATE_STARLARKIFICATION_ALLOWLIST = "PRIVATE_STARLARKIFICATION_ALLOWLIST",
)
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_shared_library_hint_info.bzl", "CcSharedLibraryHintInfo")
load(":common/cc/link/create_linking_context_from_compilation_outputs.bzl", "create_linking_context_from_compilation_outputs")
load(":common/cc/link/link.bzl", "link")
load(":common/cc/link/link_build_variables.bzl", "create_link_variables")

cc_common_internal = _builtins.internal.cc_common
CcNativeLibraryInfo = _builtins.internal.CcNativeLibraryInfo

# buildifier: disable=name-conventions
_UnboundValueProviderDoNotUse = provider("This provider is used as an unique symbol to distinguish between bound and unbound Starlark values, to avoid using kwargs.", fields = [])
_UNBOUND = _UnboundValueProviderDoNotUse()

_OLD_STARLARK_API_ALLOWLISTED_PACKAGES = [("", "tools/build_defs/cc"), ("_builtins", "")]

_BUILTINS = [("_builtins", "")]

def _check_all_sources_contain_tuples_or_none_of_them(files):
    no_tuple = False
    has_tuple = False
    for sequence in files:
        if len(sequence) != 0:
            if type(sequence[0]) == "tuple":
                has_tuple = True
            elif type(sequence[0]) == "File":
                no_tuple = True
            else:
                fail("srcs, private_hdrs and public_hdrs must all be Tuples<File, Label> or File")
        if has_tuple and no_tuple:
            fail("srcs, private_hdrs and public_hdrs must all be Tuples<File, Label> or File")
    return has_tuple

def _link(
        *,
        actions,
        name,
        feature_configuration,
        cc_toolchain,
        language = "c++",
        output_type = "executable",
        link_deps_statically = True,
        compilation_outputs = _builtins.internal.cc_internal.empty_compilation_outputs(),
        linking_contexts = [],
        user_link_flags = [],
        stamp = 0,
        additional_inputs = [],
        additional_outputs = [],
        variables_extension = {},
        use_test_only_flags = _UNBOUND,
        never_link = _UNBOUND,
        test_only_target = _UNBOUND,
        native_deps = _UNBOUND,
        whole_archive = _UNBOUND,
        additional_linkstamp_defines = _UNBOUND,
        always_link = _UNBOUND,
        only_for_dynamic_libs = _UNBOUND,
        link_artifact_name_suffix = _UNBOUND,
        main_output = _UNBOUND,
        use_shareable_artifact_factory = _UNBOUND,
        build_config = _UNBOUND,
        emit_interface_shared_library = _UNBOUND):
    if output_type == "archive":
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    # TODO(b/205690414): Keep linkedArtifactNameSuffixObject protected. Use cases that are
    #  passing the suffix should be migrated to using mainOutput instead where the suffix is
    #  taken into account. Then this parameter should be removed.
    if use_test_only_flags != _UNBOUND or \
       never_link != _UNBOUND or \
       test_only_target != _UNBOUND or \
       native_deps != _UNBOUND or \
       whole_archive != _UNBOUND or \
       additional_linkstamp_defines != _UNBOUND or \
       always_link != _UNBOUND or \
       only_for_dynamic_libs != _UNBOUND or \
       link_artifact_name_suffix != _UNBOUND or \
       main_output != _UNBOUND or \
       use_shareable_artifact_factory != _UNBOUND or \
       build_config != _UNBOUND or \
       emit_interface_shared_library != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    if use_test_only_flags == _UNBOUND:
        use_test_only_flags = False
    if never_link == _UNBOUND:
        never_link = False
    if test_only_target == _UNBOUND:
        test_only_target = False
    if native_deps == _UNBOUND:
        native_deps = False
    if whole_archive == _UNBOUND:
        whole_archive = False
    if additional_linkstamp_defines == _UNBOUND:
        additional_linkstamp_defines = []
    if always_link == _UNBOUND:
        always_link = False
    if only_for_dynamic_libs == _UNBOUND:
        only_for_dynamic_libs = False
    if link_artifact_name_suffix == _UNBOUND:
        link_artifact_name_suffix = ""
    if main_output == _UNBOUND:
        main_output = None
    if use_shareable_artifact_factory == _UNBOUND:
        use_shareable_artifact_factory = False
    if build_config == _UNBOUND:
        build_config = None
    if emit_interface_shared_library == _UNBOUND:
        emit_interface_shared_library = False

    if cc_toolchain._cpp_configuration.experimental_starlark_linking():
        link_func = link
    else:
        link_func = cc_common_internal.link
    return link_func(
        actions = actions,
        name = name,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        language = language,
        output_type = output_type,
        link_deps_statically = link_deps_statically,
        compilation_outputs = compilation_outputs,
        linking_contexts = linking_contexts,
        user_link_flags = user_link_flags,
        stamp = stamp,
        additional_inputs = additional_inputs,
        additional_outputs = additional_outputs,
        variables_extension = variables_extension,
        use_test_only_flags = use_test_only_flags,
        never_link = never_link,
        test_only_target = test_only_target,
        native_deps = native_deps,
        whole_archive = whole_archive,
        additional_linkstamp_defines = additional_linkstamp_defines,
        always_link = always_link,
        only_for_dynamic_libs = only_for_dynamic_libs,
        link_artifact_name_suffix = link_artifact_name_suffix,
        main_output = main_output,
        use_shareable_artifact_factory = use_shareable_artifact_factory,
        build_config = build_config,
        emit_interface_shared_library = emit_interface_shared_library,
    )

def _create_lto_compilation_context(*, objects = {}):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.create_lto_compilation_context(objects = objects)

def _create_compilation_outputs(*, objects = None, pic_objects = None, lto_compilation_context = _UNBOUND, dwo_objects = _UNBOUND, pic_dwo_objects = _UNBOUND):
    if lto_compilation_context != _UNBOUND or dwo_objects != _UNBOUND or pic_dwo_objects != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if lto_compilation_context == _UNBOUND:
        lto_compilation_context = None
    if dwo_objects == _UNBOUND:
        dwo_objects = depset()
    if pic_dwo_objects == _UNBOUND:
        pic_dwo_objects = depset()
    return cc_common_internal.create_compilation_outputs(
        objects = objects,
        pic_objects = pic_objects,
        lto_compilation_context = lto_compilation_context,
        dwo_objects = dwo_objects,
        pic_dwo_objects = pic_dwo_objects,
    )

def _merge_compilation_outputs(*, compilation_outputs = []):
    return cc_common_internal.merge_compilation_outputs(compilation_outputs = compilation_outputs)

def _configure_features(*, cc_toolchain, ctx = None, language = None, requested_features = [], unsupported_features = []):
    return cc_common_internal.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        language = language,
        requested_features = requested_features,
        unsupported_features = unsupported_features,
    )

def _get_tool_for_action(*, feature_configuration, action_name):
    return cc_common_internal.get_tool_for_action(feature_configuration = feature_configuration, action_name = action_name)

def _get_execution_requirements(*, feature_configuration, action_name):
    return cc_common_internal.get_execution_requirements(feature_configuration = feature_configuration, action_name = action_name)

def _is_enabled(*, feature_configuration, feature_name):
    return feature_configuration.is_enabled(feature_name)

def _action_is_enabled(*, feature_configuration, action_name):
    return cc_common_internal.action_is_enabled(feature_configuration = feature_configuration, action_name = action_name)

def _get_memory_inefficient_command_line(*, feature_configuration, action_name, variables):
    return cc_common_internal.get_memory_inefficient_command_line(feature_configuration = feature_configuration, action_name = action_name, variables = variables)

def _get_environment_variables(*, feature_configuration, action_name, variables):
    return cc_common_internal.get_environment_variables(feature_configuration = feature_configuration, action_name = action_name, variables = variables)

def _create_compile_variables(
        *,
        cc_toolchain,
        feature_configuration,
        source_file = None,
        output_file = None,
        user_compile_flags = None,
        include_directories = None,
        quote_include_directories = None,
        system_include_directories = None,
        framework_include_directories = None,
        preprocessor_defines = None,
        thinlto_index = None,
        thinlto_input_bitcode_file = None,
        thinlto_output_object_file = None,
        use_pic = False,
        add_legacy_cxx_options = False,
        variables_extension = {},
        strip_opts = _UNBOUND,
        input_file = _UNBOUND):
    if strip_opts != _UNBOUND or input_file != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if strip_opts == _UNBOUND:
        strip_opts = []
    if input_file == _UNBOUND:
        input_file = None
    return cc_common_internal.create_compile_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        source_file = source_file,
        output_file = output_file,
        user_compile_flags = user_compile_flags,
        include_directories = include_directories,
        quote_include_directories = quote_include_directories,
        system_include_directories = system_include_directories,
        framework_include_directories = framework_include_directories,
        preprocessor_defines = preprocessor_defines,
        thinlto_index = thinlto_index,
        thinlto_input_bitcode_file = thinlto_input_bitcode_file,
        thinlto_output_object_file = thinlto_output_object_file,
        use_pic = use_pic,
        add_legacy_cxx_options = add_legacy_cxx_options,
        variables_extension = variables_extension,
        strip_opts = strip_opts,
        input_file = input_file,
    )

def _empty_variables():
    return cc_common_internal.empty_variables()

def _create_library_to_link(
        *,
        actions,
        feature_configuration = None,
        cc_toolchain = None,
        static_library = None,
        pic_static_library = None,
        dynamic_library = None,
        interface_library = None,
        pic_objects = _UNBOUND,
        objects = _UNBOUND,
        lto_compilation_context = _UNBOUND,
        alwayslink = False,
        dynamic_library_symlink_path = "",
        interface_library_symlink_path = "",
        must_keep_debug = _UNBOUND):
    if lto_compilation_context != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    else:  # lto_compilation_context == _UNBOUND
        lto_compilation_context = None
    if must_keep_debug != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if must_keep_debug == _UNBOUND:
        must_keep_debug = False
    if objects != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if pic_objects != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    # We cannot check if experimental_starlark_cc_import is set or not here,
    # since there is not ctx. So for a native code to perform the check
    # pic_objects and objects need to be unbound.
    kwargs = {
        "actions": actions,
        "feature_configuration": feature_configuration,
        "cc_toolchain": cc_toolchain,
        "static_library": static_library,
        "pic_static_library": pic_static_library,
        "dynamic_library": dynamic_library,
        "interface_library": interface_library,
        "alwayslink": alwayslink,
        "dynamic_library_symlink_path": dynamic_library_symlink_path,
        "interface_library_symlink_path": interface_library_symlink_path,
        "must_keep_debug": must_keep_debug,
        "lto_compilation_context": lto_compilation_context,
    }
    if pic_objects != _UNBOUND:
        kwargs["pic_objects"] = pic_objects
    if objects != _UNBOUND:
        kwargs["objects"] = objects
    return cc_common_internal.create_library_to_link(
        **kwargs
    )

def _create_linker_input(
        *,
        owner,
        libraries = None,
        user_link_flags = None,
        additional_inputs = None,
        linkstamps = None):
    return cc_common_internal.create_linker_input(
        owner = owner,
        libraries = libraries,
        user_link_flags = user_link_flags,
        additional_inputs = additional_inputs,
        linkstamps = linkstamps,
    )

def _create_linking_context(
        *,
        linker_inputs = None,
        libraries_to_link = _UNBOUND,
        user_link_flags = _UNBOUND,
        additional_inputs = _UNBOUND,
        extra_link_time_library = _UNBOUND,
        owner = _UNBOUND):
    if extra_link_time_library != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if extra_link_time_library == _UNBOUND:
        extra_link_time_library = None

    # Usage of libraries_to_link, user_link_flags and additional_inputs are restricted by a flag.
    # Since we cannot do it here, we let the native code to do it.
    kwargs = {
        "linker_inputs": linker_inputs,
        "extra_link_time_library": extra_link_time_library,
    }
    if libraries_to_link != _UNBOUND:
        kwargs["libraries_to_link"] = libraries_to_link
    if user_link_flags != _UNBOUND:
        kwargs["user_link_flags"] = user_link_flags
    if additional_inputs != _UNBOUND:
        kwargs["additional_inputs"] = additional_inputs
    if owner != _UNBOUND:
        kwargs["owner"] = owner
    return cc_common_internal.create_linking_context(
        **kwargs
    )

def _merge_cc_infos(*, direct_cc_infos = [], cc_infos = []):
    direct_cc_compilation_contexts = []
    cc_compilation_contexts = []
    cc_linking_contexts = []
    cc_debug_info_contexts = []
    transitive_native_cc_libraries = []

    for cc_info in direct_cc_infos:
        direct_cc_compilation_contexts.append(cc_info.compilation_context)
        cc_linking_contexts.append(cc_info.linking_context)
        cc_debug_info_contexts.append(cc_info.debug_context())
        transitive_native_cc_libraries.append(cc_info.transitive_native_libraries())

    for cc_info in cc_infos:
        cc_compilation_contexts.append(cc_info.compilation_context)
        cc_linking_contexts.append(cc_info.linking_context)
        cc_debug_info_contexts.append(cc_info.debug_context())
        transitive_native_cc_libraries.append(cc_info.transitive_native_libraries())

    return CcInfo(
        compilation_context = cc_common_internal.merge_compilation_contexts(compilation_contexts = direct_cc_compilation_contexts, non_exported_compilation_contexts = cc_compilation_contexts),
        linking_context = cc_common_internal.merge_linking_contexts(linking_contexts = cc_linking_contexts),
        debug_context = cc_common_internal.merge_debug_context(cc_debug_info_contexts),
        cc_native_library_info = CcNativeLibraryInfo(libraries_to_link = depset(order = "topological", transitive = transitive_native_cc_libraries)),
    )

def _create_compilation_context(
        *,
        headers = None,
        system_includes = None,
        includes = None,
        quote_includes = None,
        framework_includes = None,
        defines = None,
        local_defines = None,
        direct_textual_headers = [],
        direct_public_headers = [],
        direct_private_headers = [],
        purpose = _UNBOUND,
        module_map = _UNBOUND,
        actions = _UNBOUND,
        label = _UNBOUND,
        external_includes = _UNBOUND,
        virtual_to_original_headers = _UNBOUND,
        dependent_cc_compilation_contexts = _UNBOUND,
        exported_dependent_cc_compilation_contexts = _UNBOUND,
        non_code_inputs = _UNBOUND,
        headers_checking_mode = _UNBOUND,
        propagate_module_map_to_compile_action = _UNBOUND,
        pic_header_module = _UNBOUND,
        header_module = _UNBOUND,
        separate_module_headers = _UNBOUND,
        separate_module = _UNBOUND,
        separate_pic_module = _UNBOUND,
        add_public_headers_to_modular_headers = _UNBOUND):
    if purpose != _UNBOUND or \
       module_map != _UNBOUND or \
       actions != _UNBOUND or \
       external_includes != _UNBOUND or \
       virtual_to_original_headers != _UNBOUND or \
       dependent_cc_compilation_contexts != _UNBOUND or \
       non_code_inputs != _UNBOUND or \
       headers_checking_mode != _UNBOUND or \
       propagate_module_map_to_compile_action != _UNBOUND or \
       pic_header_module != _UNBOUND or \
       header_module != _UNBOUND or \
       separate_module_headers != _UNBOUND or \
       separate_module != _UNBOUND or \
       separate_pic_module != _UNBOUND or \
       add_public_headers_to_modular_headers != _UNBOUND or \
       label != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    if purpose == _UNBOUND:
        purpose = None
    if module_map == _UNBOUND:
        module_map = None
    if actions == _UNBOUND:
        actions = None
    if label == _UNBOUND:
        label = None
    if external_includes == _UNBOUND:
        external_includes = depset()
    if virtual_to_original_headers == _UNBOUND:
        virtual_to_original_headers = depset()
    if dependent_cc_compilation_contexts == _UNBOUND:
        dependent_cc_compilation_contexts = []
    if exported_dependent_cc_compilation_contexts == _UNBOUND:
        exported_dependent_cc_compilation_contexts = []
    if non_code_inputs == _UNBOUND:
        non_code_inputs = []
    if headers_checking_mode == _UNBOUND:
        headers_checking_mode = "STRICT"
    if propagate_module_map_to_compile_action == _UNBOUND:
        propagate_module_map_to_compile_action = True
    if pic_header_module == _UNBOUND:
        pic_header_module = None
    if header_module == _UNBOUND:
        header_module = None
    if separate_module_headers == _UNBOUND:
        separate_module_headers = []
    if separate_module == _UNBOUND:
        separate_module = None
    if separate_pic_module == _UNBOUND:
        separate_pic_module = None
    if add_public_headers_to_modular_headers == _UNBOUND:
        add_public_headers_to_modular_headers = True
    return cc_common_internal.create_compilation_context(
        headers = headers,
        system_includes = system_includes,
        includes = includes,
        quote_includes = quote_includes,
        framework_includes = framework_includes,
        defines = defines,
        local_defines = local_defines,
        direct_textual_headers = direct_textual_headers,
        direct_public_headers = direct_public_headers,
        direct_private_headers = direct_private_headers,
        purpose = purpose,
        module_map = module_map,
        actions = actions,
        label = label,
        external_includes = external_includes,
        virtual_to_original_headers = virtual_to_original_headers,
        dependent_cc_compilation_contexts = dependent_cc_compilation_contexts,
        exported_dependent_cc_compilation_contexts = exported_dependent_cc_compilation_contexts,
        non_code_inputs = non_code_inputs,
        loose_hdrs_dirs = [],
        headers_checking_mode = headers_checking_mode,
        propagate_module_map_to_compile_action = propagate_module_map_to_compile_action,
        pic_header_module = pic_header_module,
        header_module = header_module,
        separate_module_headers = separate_module_headers,
        separate_module = separate_module,
        separate_pic_module = separate_pic_module,
        add_public_headers_to_modular_headers = add_public_headers_to_modular_headers,
    )

def _legacy_cc_flags_make_variable_do_not_use(*, cc_toolchain):
    return cc_common_internal.legacy_cc_flags_make_variable_do_not_use(cc_toolchain = cc_toolchain)

def _is_cc_toolchain_resolution_enabled_do_not_use(*, ctx):
    # Supports public is_cc_toolchain_resolution_enabled_do_not_use
    # TODO(b/218795674): remove once uses are cleaned up
    return True

def _create_cc_toolchain_config_info(
        *,
        ctx,
        toolchain_identifier,
        compiler,
        features = [],
        action_configs = [],
        artifact_name_patterns = [],
        cxx_builtin_include_directories = [],
        host_system_name = None,
        target_system_name = None,
        target_cpu = None,
        target_libc = None,
        abi_version = None,
        abi_libc_version = None,
        tool_paths = [],
        make_variables = [],
        builtin_sysroot = None,
        cc_target_os = None):
    return cc_common_internal.create_cc_toolchain_config_info(
        ctx = ctx,
        toolchain_identifier = toolchain_identifier,
        target_system_name = target_system_name,
        target_cpu = target_cpu,
        target_libc = target_libc,
        compiler = compiler,
        features = features,
        action_configs = action_configs,
        artifact_name_patterns = artifact_name_patterns,
        cxx_builtin_include_directories = cxx_builtin_include_directories,
        host_system_name = host_system_name,
        abi_version = abi_version,
        abi_libc_version = abi_libc_version,
        tool_paths = tool_paths,
        make_variables = make_variables,
        builtin_sysroot = builtin_sysroot,
    )

def _create_linking_context_from_compilation_outputs(
        *,
        actions,
        name,
        feature_configuration,
        cc_toolchain,
        language = "c++",
        disallow_static_libraries = False,
        disallow_dynamic_library = False,
        compilation_outputs,
        linking_contexts = [],
        user_link_flags = [],
        alwayslink = False,
        additional_inputs = [],
        variables_extension = {},
        stamp = _UNBOUND,
        linked_dll_name_suffix = _UNBOUND,
        test_only_target = _UNBOUND):
    if stamp != _UNBOUND or \
       linked_dll_name_suffix != _UNBOUND or \
       test_only_target != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    if stamp == _UNBOUND:
        stamp = 0
    if linked_dll_name_suffix == _UNBOUND:
        linked_dll_name_suffix = ""
    if test_only_target == _UNBOUND:
        test_only_target = False

    if cc_toolchain._cpp_configuration.experimental_starlark_linking():
        linking_func = create_linking_context_from_compilation_outputs
    else:
        linking_func = cc_common_internal.create_linking_context_from_compilation_outputs
    return linking_func(
        actions = actions,
        name = name,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        language = language,
        disallow_static_libraries = disallow_static_libraries,
        disallow_dynamic_library = disallow_dynamic_library,
        compilation_outputs = compilation_outputs,
        linking_contexts = linking_contexts,
        user_link_flags = user_link_flags,
        alwayslink = alwayslink,
        additional_inputs = additional_inputs,
        variables_extension = variables_extension,
        stamp = stamp,
        linked_dll_name_suffix = linked_dll_name_suffix,
        test_only_target = test_only_target,
    )

def _merge_compilation_contexts(*, compilation_contexts = []):
    return cc_common_internal.merge_compilation_contexts(compilation_contexts = compilation_contexts)

def _merge_linking_contexts(*, linking_contexts = []):
    return cc_common_internal.merge_linking_contexts(linking_contexts = linking_contexts)

def _check_experimental_cc_shared_library():
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.check_experimental_cc_shared_library()

def _check_experimental_cc_static_library():
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.check_experimental_cc_static_library()

def _incompatible_disable_objc_library_transition():
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.incompatible_disable_objc_library_transition()

def _create_module_map(*, file, name, umbrella_header = None):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.create_module_map(
        file = file,
        name = name,
        umbrella_header = umbrella_header,
    )

def _create_debug_context(compilation_outputs = []):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.create_debug_context(compilation_outputs)

def _merge_debug_context(debug_contexts = []):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.merge_debug_context(debug_contexts)

def _get_tool_requirement_for_action(*, feature_configuration, action_name):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.get_tool_requirement_for_action(feature_configuration = feature_configuration, action_name = action_name)

def _create_extra_link_time_library(*, build_library_func, **kwargs):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.create_extra_link_time_library(build_library_func = build_library_func, **kwargs)

def _register_linkstamp_compile_action(
        *,
        actions,
        cc_toolchain,
        feature_configuration,
        source_file,
        output_file,
        compilation_inputs,
        inputs_for_validation,
        label_replacement,
        output_replacement):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.register_linkstamp_compile_action(
        actions = actions,
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        source_file = source_file,
        output_file = output_file,
        compilation_inputs = compilation_inputs,
        inputs_for_validation = inputs_for_validation,
        label_replacement = label_replacement,
        output_replacement = output_replacement,
    )

def _compile(
        *,
        actions,
        feature_configuration,
        cc_toolchain,
        name,
        srcs = [],
        public_hdrs = [],
        private_hdrs = [],
        textual_hdrs = [],
        additional_exported_hdrs = _UNBOUND,  # TODO(ilist@): remove, there are no uses
        includes = [],
        quote_includes = [],
        system_includes = [],
        framework_includes = [],
        defines = [],
        local_defines = [],
        include_prefix = "",
        strip_include_prefix = "",
        user_compile_flags = [],
        conly_flags = [],
        cxx_flags = [],
        compilation_contexts = [],
        implementation_compilation_contexts = _UNBOUND,
        disallow_pic_outputs = False,
        disallow_nopic_outputs = False,
        additional_include_scanning_roots = [],
        additional_inputs = [],
        module_map = _UNBOUND,
        additional_module_maps = _UNBOUND,
        propagate_module_map_to_compile_action = _UNBOUND,
        do_not_generate_module_map = _UNBOUND,
        code_coverage_enabled = _UNBOUND,
        hdrs_checking_mode = _UNBOUND,
        variables_extension = {},
        language = None,
        purpose = _UNBOUND,
        copts_filter = _UNBOUND,
        separate_module_headers = _UNBOUND,
        module_interfaces = _UNBOUND,
        non_compilation_additional_inputs = _UNBOUND):
    if module_map != _UNBOUND or \
       additional_module_maps != _UNBOUND or \
       additional_exported_hdrs != _UNBOUND or \
       propagate_module_map_to_compile_action != _UNBOUND or \
       do_not_generate_module_map != _UNBOUND or \
       code_coverage_enabled != _UNBOUND or \
       purpose != _UNBOUND or \
       hdrs_checking_mode != _UNBOUND or \
       implementation_compilation_contexts != _UNBOUND or \
       copts_filter != _UNBOUND or \
       separate_module_headers != _UNBOUND or \
       module_interfaces != _UNBOUND or \
       non_compilation_additional_inputs != _UNBOUND:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    if module_map == _UNBOUND:
        module_map = None
    if additional_module_maps == _UNBOUND:
        additional_module_maps = []
    if additional_exported_hdrs == _UNBOUND:
        additional_exported_hdrs = []
    if propagate_module_map_to_compile_action == _UNBOUND:
        propagate_module_map_to_compile_action = True
    if do_not_generate_module_map == _UNBOUND:
        do_not_generate_module_map = False
    if code_coverage_enabled == _UNBOUND:
        code_coverage_enabled = False
    if purpose == _UNBOUND:
        purpose = None
    if hdrs_checking_mode == _UNBOUND:
        hdrs_checking_mode = None
    if implementation_compilation_contexts == _UNBOUND:
        implementation_compilation_contexts = []
    if copts_filter == _UNBOUND:
        copts_filter = None
    if separate_module_headers == _UNBOUND:
        separate_module_headers = []
    if module_interfaces == _UNBOUND:
        module_interfaces = []
    if non_compilation_additional_inputs == _UNBOUND:
        non_compilation_additional_inputs = []

    has_tuple = _check_all_sources_contain_tuples_or_none_of_them([srcs, module_interfaces, private_hdrs, public_hdrs])
    if has_tuple:
        cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)

    return cc_common_internal.compile(
        actions = actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        name = name,
        srcs = srcs,
        module_interfaces = module_interfaces,
        public_hdrs = public_hdrs,
        private_hdrs = private_hdrs,
        textual_hdrs = textual_hdrs,
        additional_exported_hdrs = additional_exported_hdrs,
        includes = includes,
        quote_includes = quote_includes,
        system_includes = system_includes,
        framework_includes = framework_includes,
        defines = defines,
        local_defines = local_defines,
        include_prefix = include_prefix,
        strip_include_prefix = strip_include_prefix,
        user_compile_flags = user_compile_flags,
        conly_flags = conly_flags,
        cxx_flags = cxx_flags,
        compilation_contexts = compilation_contexts,
        implementation_compilation_contexts = implementation_compilation_contexts,
        disallow_pic_outputs = disallow_pic_outputs,
        disallow_nopic_outputs = disallow_nopic_outputs,
        additional_include_scanning_roots = additional_include_scanning_roots,
        additional_inputs = additional_inputs,
        module_map = module_map,
        additional_module_maps = additional_module_maps,
        propagate_module_map_to_compile_action = propagate_module_map_to_compile_action,
        do_not_generate_module_map = do_not_generate_module_map,
        code_coverage_enabled = code_coverage_enabled,
        hdrs_checking_mode = hdrs_checking_mode,
        variables_extension = variables_extension,
        language = language,
        purpose = purpose,
        copts_filter = copts_filter,
        separate_module_headers = separate_module_headers,
        non_compilation_additional_inputs = non_compilation_additional_inputs,
    )

def _create_lto_backend_artifacts(
        *,
        ctx,
        lto_output_root_prefix,
        lto_obj_root_prefix,
        bitcode_file,
        feature_configuration,
        cc_toolchain,
        fdo_context,
        use_pic,
        should_create_per_object_debug_info,
        argv):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.create_lto_backend_artifacts(
        ctx = ctx,
        bitcode_file = bitcode_file,
        lto_output_root_prefix = lto_output_root_prefix,
        lto_obj_root_prefix = lto_obj_root_prefix,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        fdo_context = fdo_context,
        use_pic = use_pic,
        should_create_per_object_debug_info = should_create_per_object_debug_info,
        argv = argv,
    )

def _create_cc_launcher_info(*, cc_info, compilation_outputs):
    return cc_common_internal.create_cc_launcher_info(cc_info = cc_info, compilation_outputs = compilation_outputs)

def _objcopy(*, ctx, cc_toolchain):
    cc_common_internal.check_private_api(allowlist = _OLD_STARLARK_API_ALLOWLISTED_PACKAGES)
    return cc_toolchain._objcopy_files

def _objcopy_tool_path(*, ctx, cc_toolchain):
    cc_common_internal.check_private_api(allowlist = _OLD_STARLARK_API_ALLOWLISTED_PACKAGES)
    return cc_toolchain._tool_paths.get("objcopy", None)

def _ld_tool_path(*, ctx, cc_toolchain):
    cc_common_internal.check_private_api(allowlist = _OLD_STARLARK_API_ALLOWLISTED_PACKAGES)
    return cc_toolchain._tool_paths.get("ld", None)

def _create_compile_action(
        *,
        actions,
        cc_toolchain,
        feature_configuration,
        source_file,
        output_file,
        variables,
        action_name,
        compilation_context,
        additional_inputs = None,
        additional_outputs = []):
    cc_common_internal.check_private_api(allowlist = _CREATE_COMPILE_ACTION_API_ALLOWLISTED_PACKAGES)
    return cc_common_internal.create_compile_action(
        actions = actions,
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        source_file = source_file,
        output_file = output_file,
        variables = variables,
        action_name = action_name,
        compilation_context = compilation_context,
        additional_inputs = additional_inputs,
        additional_outputs = additional_outputs,
    )

def _implementation_deps_allowed_by_allowlist(*, ctx):
    cc_common_internal.check_private_api(allowlist = _PRIVATE_STARLARKIFICATION_ALLOWLIST)
    return cc_common_internal.implementation_deps_allowed_by_allowlist(ctx = ctx)

cc_common = struct(
    link = _link,
    create_lto_compilation_context = _create_lto_compilation_context,
    create_compilation_outputs = _create_compilation_outputs,
    merge_compilation_outputs = _merge_compilation_outputs,
    # Ideally we would like to get rid of this Java symbol and replace it with Starlark one.
    # And also deprecate this public API.
    CcToolchainInfo = cc_common_internal.CcToolchainInfo,
    do_not_use_tools_cpp_compiler_present = cc_common_internal.do_not_use_tools_cpp_compiler_present,
    configure_features = _configure_features,
    get_tool_for_action = _get_tool_for_action,
    get_execution_requirements = _get_execution_requirements,
    is_enabled = _is_enabled,
    action_is_enabled = _action_is_enabled,
    get_memory_inefficient_command_line = _get_memory_inefficient_command_line,
    get_environment_variables = _get_environment_variables,
    create_compile_variables = _create_compile_variables,
    create_link_variables = create_link_variables,
    empty_variables = _empty_variables,
    create_library_to_link = _create_library_to_link,
    create_linker_input = _create_linker_input,
    create_linking_context = _create_linking_context,
    merge_cc_infos = _merge_cc_infos,
    create_compilation_context = _create_compilation_context,
    legacy_cc_flags_make_variable_do_not_use = _legacy_cc_flags_make_variable_do_not_use,
    incompatible_disable_objc_library_transition = _incompatible_disable_objc_library_transition,
    is_cc_toolchain_resolution_enabled_do_not_use = _is_cc_toolchain_resolution_enabled_do_not_use,
    create_cc_toolchain_config_info = _create_cc_toolchain_config_info,
    create_linking_context_from_compilation_outputs = _create_linking_context_from_compilation_outputs,
    merge_compilation_contexts = _merge_compilation_contexts,
    merge_linking_contexts = _merge_linking_contexts,
    check_experimental_cc_shared_library = _check_experimental_cc_shared_library,
    check_experimental_cc_static_library = _check_experimental_cc_static_library,
    create_module_map = _create_module_map,
    create_debug_context = _create_debug_context,
    merge_debug_context = _merge_debug_context,
    get_tool_requirement_for_action = _get_tool_requirement_for_action,
    create_extra_link_time_library = _create_extra_link_time_library,
    register_linkstamp_compile_action = _register_linkstamp_compile_action,
    compile = _compile,
    create_lto_backend_artifacts = _create_lto_backend_artifacts,
    # Google internal methods.
    create_cc_launcher_info = _create_cc_launcher_info,
    # TODO: b/295221112 - Remove after migrating launchers to Starlark flags
    launcher_provider = _builtins.internal.cc_internal.launcher_provider,
    objcopy = _objcopy,
    objcopy_tool_path = _objcopy_tool_path,
    ld_tool_path = _ld_tool_path,
    create_compile_action = _create_compile_action,
    implementation_deps_allowed_by_allowlist = _implementation_deps_allowed_by_allowlist,
    CcSharedLibraryHintInfo = CcSharedLibraryHintInfo,
)
