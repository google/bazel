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

"""Utility functions for C++ rules."""

load(":common/cc/cc_common.bzl", "cc_common")
load(
    ":common/cc/cc_helper_internal.bzl",
    "is_versioned_shared_library_extension_valid",
    "should_create_per_object_debug_info",
    _artifact_category = "artifact_category",
)
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/objc/objc_common.bzl", "objc_common")
load(":common/objc/semantics.bzl", objc_semantics = "semantics")
load(":common/paths.bzl", "paths")

cc_internal = _builtins.internal.cc_internal
CcNativeLibraryInfo = _builtins.internal.CcNativeLibraryInfo
config_common = _builtins.toplevel.config_common
coverage_common = _builtins.toplevel.coverage_common
platform_common = _builtins.toplevel.platform_common

artifact_category = _artifact_category

# LINT.IfChange(linker_mode)
linker_mode = struct(
    LINKING_DYNAMIC = "dynamic_linking_mode",
    LINKING_STATIC = "static_linking_mode",
)
# LINT.ThenChange(@rules_cc//cc/common/cc_helper.bzl:linker_mode)

cpp_file_types = struct(
    LINKER_SCRIPT = ["ld", "lds", "ldscript"],
)

SYSROOT_FLAG = "--sysroot="

def _build_linking_context_from_libraries(ctx, libraries):
    if len(libraries) == 0:
        return CcInfo().linking_context
    linker_input = cc_common.create_linker_input(
        owner = ctx.label,
        libraries = depset(libraries),
    )

    linking_context = cc_common.create_linking_context(
        linker_inputs = depset([linker_input]),
    )

    return linking_context

def _check_file_extension(file, allowed_extensions, allow_versioned_shared_libraries):
    extension = "." + file.extension
    if _matches_extension(extension, allowed_extensions) or (allow_versioned_shared_libraries and is_versioned_shared_library_extension_valid(file.path)):
        return True
    return False

def _check_file_extensions(attr_values, allowed_extensions, attr_name, label, rule_name, allow_versioned_shared_libraries):
    for attr_value in attr_values:
        if DefaultInfo in attr_value:
            files = attr_value[DefaultInfo].files.to_list()
            if len(files) == 1 and files[0].is_source:
                if not _check_file_extension(files[0], allowed_extensions, allow_versioned_shared_libraries) and not files[0].is_directory:
                    fail("in {} attribute of {} rule {}: source file '{}' is misplaced here".format(
                        attr_name,
                        rule_name,
                        label,
                        str(attr_value.label),
                    ))
            else:
                at_least_one_good = False
                for file in files:
                    if _check_file_extension(file, allowed_extensions, allow_versioned_shared_libraries) or file.is_directory:
                        at_least_one_good = True
                        break
                if not at_least_one_good:
                    fail("'{}' does not produce any {} {} files".format(str(attr_value.label), rule_name, attr_name), attr = attr_name)

def _check_srcs_extensions(ctx, allowed_extensions, rule_name, allow_versioned_shared_libraries):
    _check_file_extensions(ctx.attr.srcs, allowed_extensions, "srcs", ctx.label, rule_name, allow_versioned_shared_libraries)

def _merge_cc_debug_contexts(compilation_outputs, dep_cc_infos):
    debug_context = cc_common.create_debug_context(compilation_outputs)
    debug_contexts = []
    for dep_cc_info in dep_cc_infos:
        debug_contexts.append(dep_cc_info.debug_context())
    debug_contexts.append(debug_context)

    return cc_common.merge_debug_context(debug_contexts)

def _is_code_coverage_enabled(ctx):
    if ctx.coverage_instrumented():
        return True
    if hasattr(ctx.attr, "deps"):
        for dep in ctx.attr.deps:
            if CcInfo in dep:
                if ctx.coverage_instrumented(dep):
                    return True
    return False

def _get_dynamic_libraries_for_runtime(cc_linking_context, linking_statically):
    libraries = []
    for linker_input in cc_linking_context.linker_inputs.to_list():
        libraries.extend(linker_input.libraries)

    dynamic_libraries_for_runtime = []
    for library in libraries:
        artifact = _get_dynamic_library_for_runtime_or_none(library, linking_statically)
        if artifact != None:
            dynamic_libraries_for_runtime.append(artifact)

    return dynamic_libraries_for_runtime

def _get_dynamic_library_for_runtime_or_none(library, linking_statically):
    if library.dynamic_library == None:
        return None

    if linking_statically and (library.static_library != None or library.pic_static_library != None):
        return None

    return library.dynamic_library

_CPP_TOOLCHAIN_TYPE = "@@" + objc_semantics.get_repo() + "//tools/cpp:toolchain_type"

def _find_cpp_toolchain(ctx, *, mandatory = True):
    """
    Finds the c++ toolchain.

    If the c++ toolchain is in use, returns it.  Otherwise, returns a c++
    toolchain derived from legacy toolchain selection, constructed from
    the CppConfiguration.

    Args:
      ctx: The rule context for which to find a toolchain.
      mandatory: If this is set to False, this function will return None rather
        than fail if no toolchain is found.

    Returns:
      A CcToolchainProvider, or None if the c++ toolchain is declared as
      optional, mandatory is False and no toolchain has been found.
    """

    if not _CPP_TOOLCHAIN_TYPE in ctx.toolchains:
        fail("In order to use find_cpp_toolchain, you must include the '//tools/cpp:toolchain_type' in the toolchains argument to your rule.")
    toolchain_info = ctx.toolchains[_CPP_TOOLCHAIN_TYPE]
    if toolchain_info == None:
        if not mandatory:
            return None

        # No cpp toolchain was found, so report an error.
        fail("Unable to find a CC toolchain using toolchain resolution. Target: %s, Platform: %s, Exec platform: %s" %
             (ctx.label, ctx.fragments.platform.platform, ctx.fragments.platform.host_platform))
    if hasattr(toolchain_info, "cc_provider_in_toolchain") and hasattr(toolchain_info, "cc"):
        return toolchain_info.cc
    return toolchain_info

def _use_cpp_toolchain(mandatory = False):
    """
    Helper to depend on the c++ toolchain.

    Usage:
    ```
    my_rule = rule(
        toolchains = [other toolchain types] + use_cpp_toolchain(),
    )
    ```

    Args:
      mandatory: Whether or not it should be an error if the toolchain cannot be resolved.

    Returns:
      A list that can be used as the value for `rule.toolchains`.
    """
    return [config_common.toolchain_type(_CPP_TOOLCHAIN_TYPE, mandatory = mandatory)]

def _collect_compilation_prerequisites(ctx, compilation_context):
    direct = []
    transitive = []
    if hasattr(ctx.attr, "srcs"):
        for src in ctx.attr.srcs:
            if DefaultInfo in src:
                files = src[DefaultInfo].files.to_list()
                for file in files:
                    if _check_file_extension(file, extensions.CC_AND_OBJC, False):
                        direct.append(file)

    transitive.append(compilation_context.headers)
    transitive.append(compilation_context.additional_inputs())
    transitive.append(compilation_context.transitive_modules(use_pic = True))
    transitive.append(compilation_context.transitive_modules(use_pic = False))

    return depset(direct = direct, transitive = transitive)

def _build_output_groups_for_emitting_compile_providers(
        compilation_outputs,
        compilation_context,
        cpp_configuration,
        cc_toolchain,
        feature_configuration,
        ctx,
        generate_hidden_top_level_group):
    output_groups_builder = {}
    process_hdrs = cpp_configuration.process_headers_in_dependencies()
    use_pic = cc_toolchain.needs_pic_for_dynamic_libraries(feature_configuration = feature_configuration)
    output_groups_builder["temp_files_INTERNAL_"] = compilation_outputs.temps()
    files_to_compile = compilation_outputs.files_to_compile(
        parse_headers = process_hdrs,
        use_pic = use_pic,
    )
    output_groups_builder["compilation_outputs"] = files_to_compile
    output_groups_builder["compilation_prerequisites_INTERNAL_"] = _collect_compilation_prerequisites(ctx = ctx, compilation_context = compilation_context)
    output_groups_builder["module_files"] = depset(compilation_outputs.module_files())

    if generate_hidden_top_level_group:
        output_groups_builder["_hidden_top_level_INTERNAL_"] = _collect_library_hidden_top_level_artifacts(
            ctx,
            files_to_compile,
        )

    _create_save_feature_state_artifacts(
        output_groups_builder,
        cpp_configuration,
        feature_configuration,
        ctx,
    )

    return output_groups_builder

def _dll_hash_suffix(ctx, feature_configuration, cpp_config):
    if cpp_config.dynamic_mode() != "OFF":
        if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "targets_windows"):
            if not hasattr(ctx.attr, "win_def_file") or ctx.file.win_def_file == None:
                # Note: ctx.label.workspace_name strips leading @,
                # which is different from the native behavior.
                string_to_hash = ctx.label.workspace_name + ctx.label.package
                return "_%x" % hash(string_to_hash)
    return ""

def _gen_empty_def_file(ctx):
    trivial_def_file = ctx.actions.declare_file(ctx.label.name + ".gen.empty.def")
    ctx.actions.write(trivial_def_file, "", False)
    return trivial_def_file

def _get_windows_def_file_for_linking(ctx, custom_def_file, generated_def_file, feature_configuration):
    # 1. If a custom DEF file is specified in win_def_file attribute, use it.
    # 2. If a generated DEF file is available and should be used, use it.
    # 3. Otherwise, we use an empty DEF file to ensure the import library will be generated.
    if custom_def_file != None:
        return custom_def_file
    elif generated_def_file != None and _should_generate_def_file(ctx, feature_configuration) == True:
        return generated_def_file
    else:
        return _gen_empty_def_file(ctx)

def _should_generate_def_file(ctx, feature_configuration):
    windows_export_all_symbols_enabled = cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "windows_export_all_symbols")
    no_windows_export_all_symbols_enabled = cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "no_windows_export_all_symbols")
    return windows_export_all_symbols_enabled and (not no_windows_export_all_symbols_enabled) and (ctx.attr.win_def_file == None)

def _generate_def_file(ctx, def_parser, object_files, dll_name):
    def_file = ctx.actions.declare_file(ctx.label.name + ".gen.def")
    args = ctx.actions.args()
    args.add(def_file)
    args.add(dll_name)
    argv = ctx.actions.args()
    argv.use_param_file("@%s", use_always = True)
    argv.set_param_file_format("shell")
    for object_file in object_files:
        argv.add(object_file.path)

    ctx.actions.run(
        mnemonic = "DefParser",
        executable = def_parser,
        toolchain = None,
        arguments = [args, argv],
        inputs = object_files,
        outputs = [def_file],
        use_default_shell_env = True,
    )
    return def_file

def _is_non_empty_list_or_select(value, attr):
    if type(value) == "list":
        return len(value) > 0
    elif type(value) == "select":
        return True
    else:
        fail("Only select or list is valid for {} attr".format(attr))

CC_SOURCE = [".cc", ".cpp", ".cxx", ".c++", ".C", ".cu", ".cl"]
C_SOURCE = [".c"]
OBJC_SOURCE = [".m"]
OBJCPP_SOURCE = [".mm"]
CLIF_INPUT_PROTO = [".ipb"]
CLIF_OUTPUT_PROTO = [".opb"]
CC_HEADER = [".h", ".hh", ".hpp", ".ipp", ".hxx", ".h++", ".inc", ".inl", ".tlh", ".tli", ".H", ".tcc"]
ASSESMBLER_WITH_C_PREPROCESSOR = [".S"]
ASSEMBLER = [".s", ".asm"]
ARCHIVE = [".a", ".lib"]
PIC_ARCHIVE = [".pic.a"]
ALWAYSLINK_LIBRARY = [".lo"]
ALWAYSLINK_PIC_LIBRARY = [".pic.lo"]
SHARED_LIBRARY = [".so", ".dylib", ".dll", ".wasm"]
INTERFACE_SHARED_LIBRARY = [".ifso", ".tbd", ".lib", ".dll.a"]
OBJECT_FILE = [".o", ".obj"]
PIC_OBJECT_FILE = [".pic.o"]

CC_AND_OBJC = []
CC_AND_OBJC.extend(CC_SOURCE)
CC_AND_OBJC.extend(C_SOURCE)
CC_AND_OBJC.extend(OBJC_SOURCE)
CC_AND_OBJC.extend(OBJCPP_SOURCE)
CC_AND_OBJC.extend(CC_HEADER)
CC_AND_OBJC.extend(ASSEMBLER)
CC_AND_OBJC.extend(ASSESMBLER_WITH_C_PREPROCESSOR)

DISALLOWED_HDRS_FILES = []
DISALLOWED_HDRS_FILES.extend(ARCHIVE)
DISALLOWED_HDRS_FILES.extend(PIC_ARCHIVE)
DISALLOWED_HDRS_FILES.extend(ALWAYSLINK_LIBRARY)
DISALLOWED_HDRS_FILES.extend(ALWAYSLINK_PIC_LIBRARY)
DISALLOWED_HDRS_FILES.extend(SHARED_LIBRARY)
DISALLOWED_HDRS_FILES.extend(INTERFACE_SHARED_LIBRARY)
DISALLOWED_HDRS_FILES.extend(OBJECT_FILE)
DISALLOWED_HDRS_FILES.extend(PIC_OBJECT_FILE)

extensions = struct(
    CC_SOURCE = CC_SOURCE,
    C_SOURCE = C_SOURCE,
    CC_HEADER = CC_HEADER,
    ASSESMBLER_WITH_C_PREPROCESSOR = ASSESMBLER_WITH_C_PREPROCESSOR,
    ASSEMBLER = ASSEMBLER,
    ARCHIVE = ARCHIVE,
    PIC_ARCHIVE = PIC_ARCHIVE,
    ALWAYSLINK_LIBRARY = ALWAYSLINK_LIBRARY,
    ALWAYSLINK_PIC_LIBRARY = ALWAYSLINK_PIC_LIBRARY,
    SHARED_LIBRARY = SHARED_LIBRARY,
    OBJECT_FILE = OBJECT_FILE,
    PIC_OBJECT_FILE = PIC_OBJECT_FILE,
    CC_AND_OBJC = CC_AND_OBJC,
    DISALLOWED_HDRS_FILES = DISALLOWED_HDRS_FILES,  # Also includes VERSIONED_SHARED_LIBRARY files.
)

def _collect_library_hidden_top_level_artifacts(
        ctx,
        files_to_compile):
    artifacts_to_force_builder = [files_to_compile]
    if hasattr(ctx.attr, "deps"):
        for dep in ctx.attr.deps:
            if OutputGroupInfo in dep:
                if "_hidden_top_level_INTERNAL_" in dep[OutputGroupInfo]:
                    artifacts_to_force_builder.append(dep[OutputGroupInfo]["_hidden_top_level_INTERNAL_"])

    return depset(transitive = artifacts_to_force_builder)

def _create_save_feature_state_artifacts(
        output_groups_builder,
        cpp_configuration,
        feature_configuration,
        ctx):
    if cpp_configuration.save_feature_state():
        feature_state_file = ctx.actions.declare_file(ctx.label.name + "_feature_state.txt")

        ctx.actions.write(feature_state_file, str(feature_configuration))
        output_groups_builder["default"] = depset(direct = [feature_state_file])

def _merge_output_groups(output_groups):
    merged_output_groups_builder = {}
    for output_group in output_groups:
        for output_key, output_value in output_group.items():
            depset_list = merged_output_groups_builder.get(output_key, [])
            depset_list.append(output_value)
            merged_output_groups_builder[output_key] = depset_list

    merged_output_group = {}
    for k, v in merged_output_groups_builder.items():
        merged_output_group[k] = depset(transitive = v)

    return merged_output_group

def _rule_error(msg):
    fail(msg)

def _attribute_error(attr_name, msg):
    fail("in attribute '" + attr_name + "': " + msg)

def _get_linking_contexts_from_deps(deps):
    linking_contexts = []
    for dep in deps:
        if CcInfo in dep:
            linking_contexts.append(dep[CcInfo].linking_context)
    return linking_contexts

def _is_test_target(ctx):
    if hasattr(ctx.attr, "testonly"):
        return ctx.attr.testonly

    return False

def _get_compilation_contexts_from_deps(deps):
    compilation_contexts = []
    for dep in deps:
        if CcInfo in dep:
            compilation_contexts.append(dep[CcInfo].compilation_context)
    return compilation_contexts

def _is_compilation_outputs_empty(compilation_outputs):
    return (len(compilation_outputs.pic_objects) == 0 and
            len(compilation_outputs.objects) == 0)

def _matches_extension(extension, patterns):
    for pattern in patterns:
        if extension.endswith(pattern):
            return True
    return False

def _build_precompiled_files(ctx):
    objects = []
    pic_objects = []
    static_libraries = []
    pic_static_libraries = []
    alwayslink_static_libraries = []
    pic_alwayslink_static_libraries = []
    shared_libraries = []

    for src in ctx.files.srcs:
        short_path = src.short_path

        # For compatibility with existing BUILD files, any ".o" files listed
        # in srcs are assumed to be position-independent code, or
        # at least suitable for inclusion in shared libraries, unless they
        # end with ".nopic.o". (The ".nopic.o" extension is an undocumented
        # feature to give users at least some control over this.) Note that
        # some target platforms do not require shared library code to be PIC.
        if _matches_extension(short_path, OBJECT_FILE):
            objects.append(src)
            if not short_path.endswith(".nopic.o"):
                pic_objects.append(src)

            if _matches_extension(short_path, PIC_OBJECT_FILE):
                pic_objects.append(src)

        elif _matches_extension(short_path, PIC_ARCHIVE):
            pic_static_libraries.append(src)
        elif _matches_extension(short_path, ARCHIVE):
            static_libraries.append(src)
        elif _matches_extension(short_path, ALWAYSLINK_PIC_LIBRARY):
            pic_alwayslink_static_libraries.append(src)
        elif _matches_extension(short_path, ALWAYSLINK_LIBRARY):
            alwayslink_static_libraries.append(src)
        elif _is_valid_shared_library_artifact(src):
            shared_libraries.append(src)
    return (
        objects,
        pic_objects,
        static_libraries,
        pic_static_libraries,
        alwayslink_static_libraries,
        pic_alwayslink_static_libraries,
        shared_libraries,
    )

# NOTE: Prefer to use _is_valid_shared_library_artifact() instead of this method since
# it has better performance (checking for extension in a short list rather than multiple
# string.endswith() checks)
def _is_valid_shared_library_name(shared_library_name):
    if (shared_library_name.endswith(".so") or
        shared_library_name.endswith(".dll") or
        shared_library_name.endswith(".dylib") or
        shared_library_name.endswith(".wasm")):
        return True

    return is_versioned_shared_library_extension_valid(shared_library_name)

_SHARED_LIBRARY_EXTENSIONS = ["so", "dll", "dylib", "wasm"]

def _is_valid_shared_library_artifact(shared_library):
    if (shared_library.extension in _SHARED_LIBRARY_EXTENSIONS):
        return True

    return is_versioned_shared_library_extension_valid(shared_library.basename)

def _get_providers(deps, provider):
    providers = []
    for dep in deps:
        if provider in dep:
            providers.append(dep[provider])
    return providers

def _libraries_from_linking_context(linking_context):
    libraries = []
    for linker_input in linking_context.linker_inputs.to_list():
        libraries.extend(linker_input.libraries)
    return depset(libraries, order = "topological")

def _additional_inputs_from_linking_context(linking_context):
    inputs = []
    for linker_input in linking_context.linker_inputs.to_list():
        inputs.extend(linker_input.additional_inputs)
    return depset(inputs, order = "topological")

def _stringify_linker_input(linker_input):
    parts = []
    parts.append(str(linker_input.owner))
    for library in linker_input.libraries:
        if library.static_library != None:
            parts.append(library.static_library.path)
        if library.pic_static_library != None:
            parts.append(library.pic_static_library.path)
        if library.dynamic_library != None:
            parts.append(library.dynamic_library.path)
        if library.interface_library != None:
            parts.append(library.interface_library.path)

    for additional_input in linker_input.additional_inputs:
        parts.append(additional_input.path)

    for linkstamp in linker_input.linkstamps:
        parts.append(linkstamp.file().path)

    return "".join(parts)

def _replace_name(name, new_name):
    last_slash = name.rfind("/")
    if last_slash == -1:
        return new_name
    return name[:last_slash] + "/" + new_name

def _get_base_name(name):
    last_slash = name.rfind("/")
    if last_slash == -1:
        return name
    return name[last_slash + 1:]

def _get_artifact_name_for_category(cc_toolchain, is_dynamic_link_type, output_name):
    linked_artifact_category = None
    if is_dynamic_link_type:
        linked_artifact_category = artifact_category.DYNAMIC_LIBRARY
    else:
        linked_artifact_category = artifact_category.EXECUTABLE

    return cc_internal.get_artifact_name_for_category(cc_toolchain = cc_toolchain, category = linked_artifact_category, output_name = output_name)

def _get_linked_artifact(ctx, cc_toolchain, is_dynamic_link_type):
    name = ctx.label.name
    new_name = _get_artifact_name_for_category(cc_toolchain, is_dynamic_link_type, _get_base_name(name))
    name = _replace_name(name, new_name)

    return ctx.actions.declare_file(name)

def _collect_native_cc_libraries(deps, libraries):
    transitive_libraries = [dep[CcInfo].transitive_native_libraries() for dep in deps if CcInfo in dep]
    return CcNativeLibraryInfo(libraries_to_link = depset(direct = libraries, transitive = transitive_libraries))

def _tool_path(cc_toolchain, tool):
    return cc_toolchain._tool_paths.get(tool, None)

def _get_toolchain_global_make_variables(cc_toolchain):
    result = {
        "CC": _tool_path(cc_toolchain, "gcc"),
        "AR": _tool_path(cc_toolchain, "ar"),
        "NM": _tool_path(cc_toolchain, "nm"),
        "LD": _tool_path(cc_toolchain, "ld"),
        "STRIP": _tool_path(cc_toolchain, "strip"),
        "C_COMPILER": cc_toolchain.compiler,
    }
    obj_copy_tool = _tool_path(cc_toolchain, "objcopy")
    if obj_copy_tool != None:
        # objcopy is optional in Crostool.
        result["OBJCOPY"] = obj_copy_tool
    gcov_tool = _tool_path(cc_toolchain, "gcov-tool")
    if gcov_tool != None:
        # gcovtool is optional in Crostool.
        result["GCOVTOOL"] = gcov_tool

    libc = cc_toolchain.libc
    if libc.startswith("glibc-"):
        # Strip "glibc-" prefix.
        result["GLIBC_VERSION"] = libc[6:]
    else:
        result["GLIBC_VERSION"] = libc

    abi_glibc_version = cc_toolchain._abi_glibc_version
    if abi_glibc_version != None:
        result["ABI_GLIBC_VERSION"] = abi_glibc_version

    abi = cc_toolchain._abi
    if abi != None:
        result["ABI"] = abi

    result["CROSSTOOLTOP"] = cc_toolchain._crosstool_top_path
    return result

def _contains_sysroot(original_cc_flags, feature_config_cc_flags):
    if SYSROOT_FLAG in original_cc_flags:
        return True
    for flag in feature_config_cc_flags:
        if SYSROOT_FLAG in flag:
            return True

    return False

# LINT.IfChange(forked_exports)

def _get_static_mode_params_for_dynamic_library_libraries(libs):
    linker_inputs = []
    for lib in libs.to_list():
        if lib.pic_static_library:
            linker_inputs.append(lib.pic_static_library)
        elif lib.static_library:
            linker_inputs.append(lib.static_library)
        elif lib.interface_library:
            linker_inputs.append(lib.interface_library)
        else:
            linker_inputs.append(lib.dynamic_library)
    return linker_inputs

def _create_strip_action(ctx, cc_toolchain, cpp_config, input, output, feature_configuration):
    if cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "no_stripping"):
        ctx.actions.symlink(
            output = output,
            target_file = input,
            progress_message = "Symlinking original binary as stripped binary",
        )
        return

    if not cc_common.action_is_enabled(feature_configuration = feature_configuration, action_name = "strip"):
        fail("Expected action_config for 'strip' to be configured.")

    variables = cc_common.create_compile_variables(
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        output_file = output.path,
        input_file = input.path,
        strip_opts = cpp_config.strip_opts(),
    )
    command_line = cc_common.get_memory_inefficient_command_line(
        feature_configuration = feature_configuration,
        action_name = "strip",
        variables = variables,
    )
    env = cc_common.get_environment_variables(
        feature_configuration = feature_configuration,
        action_name = "strip",
        variables = variables,
    )
    execution_info = {}
    for execution_requirement in cc_common.get_tool_requirement_for_action(feature_configuration = feature_configuration, action_name = "strip"):
        execution_info[execution_requirement] = ""
    ctx.actions.run(
        inputs = depset(
            direct = [input],
            transitive = [cc_toolchain._strip_files],
        ),
        outputs = [output],
        use_default_shell_env = True,
        env = env,
        executable = cc_common.get_tool_for_action(feature_configuration = feature_configuration, action_name = "strip"),
        toolchain = cc_helper.CPP_TOOLCHAIN_TYPE,
        execution_requirements = execution_info,
        progress_message = "Stripping {} for {}".format(output.short_path, ctx.label),
        mnemonic = "CcStrip",
        arguments = command_line,
    )

def _lookup_var(ctx, additional_vars, var):
    expanded_make_var_ctx = ctx.var.get(var)
    expanded_make_var_additional = additional_vars.get(var)
    if expanded_make_var_additional != None:
        return expanded_make_var_additional
    if expanded_make_var_ctx != None:
        return expanded_make_var_ctx
    fail("{}: {} not defined".format(ctx.label, "$(" + var + ")"))

def _expand_nested_variable(ctx, additional_vars, exp, execpath = True, targets = []):
    # If make variable is predefined path variable(like $(location ...))
    # we will expand it first.
    if exp.find(" ") != -1:
        if not execpath:
            if exp.startswith("location"):
                exp = exp.replace("location", "rootpath", 1)
        data_targets = []
        if ctx.attr.data != None:
            data_targets = ctx.attr.data

        # Make sure we do not duplicate targets.
        unified_targets_set = {}
        for data_target in data_targets:
            unified_targets_set[data_target] = True
        for target in targets:
            unified_targets_set[target] = True
        return ctx.expand_location("$({})".format(exp), targets = unified_targets_set.keys())

    # Recursively expand nested make variables, but since there is no recursion
    # in Starlark we will do it via for loop.
    unbounded_recursion = True

    # The only way to check if the unbounded recursion is happening or not
    # is to have a look at the depth of the recursion.
    # 10 seems to be a reasonable number, since it is highly unexpected
    # to have nested make variables which are expanding more than 10 times.
    for _ in range(10):
        exp = _lookup_var(ctx, additional_vars, exp)
        if len(exp) >= 3 and exp[0] == "$" and exp[1] == "(" and exp[len(exp) - 1] == ")":
            # Try to expand once more.
            exp = exp[2:len(exp) - 1]
            continue
        unbounded_recursion = False
        break

    if unbounded_recursion:
        fail("potentially unbounded recursion during expansion of {}".format(exp))
    return exp

def _expand(ctx, expression, additional_make_variable_substitutions, execpath = True, targets = []):
    idx = 0
    last_make_var_end = 0
    result = []
    n = len(expression)
    for _ in range(n):
        if idx >= n:
            break
        if expression[idx] != "$":
            idx += 1
            continue

        idx += 1

        # We've met $$ pattern, so $ is escaped.
        if idx < n and expression[idx] == "$":
            idx += 1
            result.append(expression[last_make_var_end:idx - 1])
            last_make_var_end = idx
            # We might have found a potential start for Make Variable.

        elif idx < n and expression[idx] == "(":
            # Try to find the closing parentheses.
            make_var_start = idx
            make_var_end = make_var_start
            for j in range(idx + 1, n):
                if expression[j] == ")":
                    make_var_end = j
                    break

            # Note we cannot go out of string's bounds here,
            # because of this check.
            # If start of the variable is different from the end,
            # we found a make variable.
            if make_var_start != make_var_end:
                # Some clarifications:
                # *****$(MAKE_VAR_1)*******$(MAKE_VAR_2)*****
                #                   ^       ^          ^
                #                   |       |          |
                #   last_make_var_end  make_var_start make_var_end
                result.append(expression[last_make_var_end:make_var_start - 1])
                make_var = expression[make_var_start + 1:make_var_end]
                exp = _expand_nested_variable(ctx, additional_make_variable_substitutions, make_var, execpath, targets)
                result.append(exp)

                # Update indexes.
                idx = make_var_end + 1
                last_make_var_end = idx

    # Add the last substring which would be skipped by for loop.
    if last_make_var_end < n:
        result.append(expression[last_make_var_end:n])

    return "".join(result)

def _get_expanded_env(ctx, additional_make_variable_substitutions):
    if not hasattr(ctx.attr, "env"):
        fail("could not find rule attribute named: 'env'")
    expanded_env = {}
    for k in ctx.attr.env:
        expanded_env[k] = _expand(
            ctx,
            ctx.attr.env[k],
            additional_make_variable_substitutions,
            # By default, Starlark `ctx.expand_location` has `execpath` semantics.
            # For legacy attributes, e.g. `env`, we want `rootpath` semantics instead.
            execpath = False,
        )
    return expanded_env

# Implementation of Bourne shell tokenization.
# Tokenizes str and appends result to the options list.
def _tokenize(options, options_string):
    token = []
    force_token = False
    quotation = "\0"
    length = len(options_string)

    # Since it is impossible to modify loop variable inside loop
    # in Starlark, and also there is no while loop, I have to
    # use this ugly hack.
    i = -1
    for _ in range(length):
        i += 1
        if i >= length:
            break
        c = options_string[i]
        if quotation != "\0":
            # In quotation.
            if c == quotation:
                # End quotation.
                quotation = "\0"
            elif c == "\\" and quotation == "\"":
                i += 1
                if i == length:
                    fail("backslash at the end of the string: {}".format(options_string))
                c = options_string[i]
                if c != "\\" and c != "\"":
                    token.append("\\")
                token.append(c)
            else:
                # Regular char, in quotation.
                token.append(c)
        else:
            # Not in quotation.
            if c == "'" or c == "\"":
                # Begin single double quotation.
                quotation = c
                force_token = True
            elif c == " " or c == "\t":
                # Space not quoted.
                if force_token or len(token) > 0:
                    options.append("".join(token))
                    token = []
                    force_token = False
            elif c == "\\":
                # Backslash not quoted.
                i += 1
                if i == length:
                    fail("backslash at the end of the string: {}".format(options_string))
                token.append(options_string[i])
            else:
                # Regular char, not quoted.
                token.append(c)
    if quotation != "\0":
        fail("unterminated quotation at the end of the string: {}".format(options_string))

    if force_token or len(token) > 0:
        options.append("".join(token))

def _should_use_pic(ctx, cc_toolchain, feature_configuration):
    """Whether to use pic files

    Args:
        ctx: (RuleContext)
        cc_toolchain: (CcToolchainInfo)
        feature_configuration: (FeatureConfiguration)

    Returns:
        (bool)
    """
    return ctx.fragments.cpp.force_pic() or (
        cc_toolchain.needs_pic_for_dynamic_libraries(feature_configuration = feature_configuration) and (
            ctx.var["COMPILATION_MODE"] != "opt" or
            cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "prefer_pic_for_opt_binaries")
        )
    )

# LINT.ThenChange(@rules_cc//cc/common/cc_helper.bzl:forked_exports)

def _get_cc_flags_make_variable(ctx, feature_configuration, cc_toolchain):
    original_cc_flags = cc_toolchain._legacy_cc_flags_make_variable
    sysroot_cc_flag = ""
    if cc_toolchain.sysroot != None:
        sysroot_cc_flag = SYSROOT_FLAG + cc_toolchain.sysroot

    build_vars = cc_toolchain._build_variables
    feature_config_cc_flags = cc_common.get_memory_inefficient_command_line(
        feature_configuration = feature_configuration,
        action_name = "cc-flags-make-variable",
        variables = build_vars,
    )
    cc_flags = [original_cc_flags]

    # Only add sysroots flag if nothing else adds sysroot, BUT it must appear
    # before the feature config flags.
    if not _contains_sysroot(original_cc_flags, feature_config_cc_flags):
        cc_flags.append(sysroot_cc_flag)
    cc_flags.extend(feature_config_cc_flags)
    return {"CC_FLAGS": " ".join(cc_flags)}

# Tries to expand a single make variable from token.
# If token has additional characters other than ones
# corresponding to make variable returns None.
def _expand_single_make_variable(ctx, token, additional_make_variable_substitutions):
    if len(token) < 3:
        return None
    if token[0] != "$" or token[1] != "(" or token[len(token) - 1] != ")":
        return None
    unexpanded_var = token[2:len(token) - 1]
    expanded_var = _expand_nested_variable(ctx, additional_make_variable_substitutions, unexpanded_var)
    return expanded_var

def _expand_make_variables_for_copts(ctx, tokenization, unexpanded_tokens, additional_make_variable_substitutions):
    tokens = []
    targets = []
    for additional_compiler_input in getattr(ctx.attr, "additional_compiler_inputs", []):
        targets.append(additional_compiler_input)
    for token in unexpanded_tokens:
        if tokenization:
            expanded_token = _expand(ctx, token, additional_make_variable_substitutions, targets = targets)
            _tokenize(tokens, expanded_token)
        else:
            exp = _expand_single_make_variable(ctx, token, additional_make_variable_substitutions)
            if exp != None:
                _tokenize(tokens, exp)
            else:
                tokens.append(_expand(ctx, token, additional_make_variable_substitutions, targets = targets))
    return tokens

def _get_copts(ctx, feature_configuration, additional_make_variable_substitutions, attr = "copts"):
    if not hasattr(ctx.attr, attr):
        fail("could not find rule attribute named: '{}'".format(attr))
    attribute_copts = getattr(ctx.attr, attr)
    tokenization = not (cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "no_copts_tokenization") or "no_copts_tokenization" in ctx.features)
    expanded_attribute_copts = _expand_make_variables_for_copts(ctx, tokenization, attribute_copts, additional_make_variable_substitutions)
    return expanded_attribute_copts

def _has_target_constraints(ctx, constraints):
    # Constraints is a label_list.
    for constraint in constraints:
        constraint_value = constraint[platform_common.ConstraintValueInfo]
        if ctx.target_platform_has_constraint(constraint_value):
            return True
    return False

def _is_stamping_enabled(ctx):
    if ctx.configuration.is_tool_configuration():
        return 0
    stamp = 0
    if hasattr(ctx.attr, "stamp"):
        stamp = ctx.attr.stamp
    return stamp

def _is_stamping_enabled_for_aspect(ctx):
    if ctx.configuration.is_tool_configuration():
        return 0
    stamp = 0
    if hasattr(ctx.rule.attr, "stamp"):
        stamp = ctx.rule.attr.stamp
    return stamp

_RUNFILES_LIBRARY_TARGET = Label("@rules_cc//cc/runfiles")
_LEGACY_RUNFILES_LIBRARY_TARGET = Label("@bazel_tools//tools/cpp/runfiles")

def _get_local_defines_for_runfiles_lookup(ctx, all_deps):
    for dep in all_deps:
        if dep.label == _RUNFILES_LIBRARY_TARGET or dep.label == _LEGACY_RUNFILES_LIBRARY_TARGET:
            return ["BAZEL_CURRENT_REPOSITORY=\"{}\"".format(ctx.label.workspace_name)]
    return []

# This should be enough to assume if two labels are equal.
def _are_labels_equal(a, b):
    return a.name == b.name and a.package == b.package

def _map_to_list(m):
    result = []
    for k, v in m.items():
        result.append((k, v))
    return result

def _calculate_artifact_label_map(attr_list, attr_name):
    """
    Converts a label_list attribute into a list of (Artifact, Label) tuples.

    Each tuple represents an input source file and the label of the rule that generates it
    (or the label of the source file itself if it is an input file).
    """
    artifact_label_map = {}
    for attr in attr_list:
        if DefaultInfo in attr:
            for artifact in attr[DefaultInfo].files.to_list():
                if "." + artifact.extension not in CC_HEADER:
                    old_label = artifact_label_map.get(artifact, None)
                    artifact_label_map[artifact] = attr.label
                    if old_label != None and not _are_labels_equal(old_label, attr.label) and ("." + artifact.extension in CC_AND_OBJC or attr_name == "module_interfaces"):
                        fail(
                            "Artifact '{}' is duplicated (through '{}' and '{}')".format(artifact, old_label, attr),
                            attr = attr_name,
                        )
    return artifact_label_map

def _get_srcs(ctx):
    if not hasattr(ctx.attr, "srcs"):
        return []
    artifact_label_map = _calculate_artifact_label_map(ctx.attr.srcs, "srcs")
    return _map_to_list(artifact_label_map)

def _get_cpp_module_interfaces(ctx):
    if not hasattr(ctx.attr, "module_interfaces"):
        return []
    artifact_label_map = _calculate_artifact_label_map(ctx.attr.module_interfaces, "module_interfaces")
    return _map_to_list(artifact_label_map)

# Returns a list of (Artifact, Label) tuples. Each tuple represents an input source
# file and the label of the rule that generates it (or the label of the source file itself if it
# is an input file).
def _get_private_hdrs(ctx):
    if not hasattr(ctx.attr, "srcs"):
        return []
    artifact_label_map = {}
    for src in ctx.attr.srcs:
        if DefaultInfo in src:
            for artifact in src[DefaultInfo].files.to_list():
                if "." + artifact.extension in CC_HEADER:
                    artifact_label_map[artifact] = src.label
    return _map_to_list(artifact_label_map)

# Returns the files from headers and does some checks.
def _get_public_hdrs(ctx):
    if not hasattr(ctx.attr, "hdrs"):
        return []
    artifact_label_map = {}
    for hdr in ctx.attr.hdrs:
        if DefaultInfo in hdr:
            for artifact in hdr[DefaultInfo].files.to_list():
                if _check_file_extension(artifact, DISALLOWED_HDRS_FILES, True):
                    continue
                artifact_label_map[artifact] = hdr.label
    return _map_to_list(artifact_label_map)

def _report_invalid_options(cc_toolchain, cpp_config):
    if cpp_config.grte_top() != None and cc_toolchain.sysroot == None:
        fail("The selected toolchain does not support setting --grte_top (it doesn't specify builtin_sysroot).")

def _is_repository_main(repository):
    return repository == ""

def _repository_exec_path(repository, sibling_repository_layout):
    if _is_repository_main(repository):
        return ""
    prefix = "external"
    if sibling_repository_layout:
        prefix = ".."
    if repository.startswith("@"):
        repository = repository[1:]
    return paths.get_relative(prefix, repository)

def _package_exec_path(ctx, package, sibling_repository_layout):
    return paths.get_relative(_repository_exec_path(ctx.label.workspace_name, sibling_repository_layout), package)

def _package_source_root(repository, package, sibling_repository_layout):
    if _is_repository_main(repository) or sibling_repository_layout:
        return package
    if repository.startswith("@"):
        repository = repository[1:]
    return paths.get_relative(paths.get_relative("external", repository), package)

def _system_include_dirs(ctx, additional_make_variable_substitutions):
    result = []
    sibling_repository_layout = ctx.configuration.is_sibling_repository_layout()
    package = ctx.label.package
    package_exec_path = _package_exec_path(ctx, package, sibling_repository_layout)
    package_source_root = _package_source_root(ctx.label.workspace_name, package, sibling_repository_layout)
    for include in ctx.attr.includes:
        includes_attr = _expand(ctx, include, additional_make_variable_substitutions)
        if includes_attr.startswith("/"):
            continue
        includes_path = paths.get_relative(package_exec_path, includes_attr)
        if not sibling_repository_layout and paths.contains_up_level_references(includes_path):
            fail("Path references a path above the execution root.", attr = "includes")

        if includes_path == ".":
            fail("'" + includes_attr + "' resolves to the workspace root, which would allow this rule and all of its " +
                 "transitive dependents to include any file in your workspace. Please include only" +
                 " what you need", attr = "includes")
        result.append(includes_path)

        # We don't need to perform the above checks against out_includes_path again since any errors
        # must have manifested in includesPath already.
        out_includes_path = paths.get_relative(package_source_root, includes_attr)
        if (ctx.configuration.has_separate_genfiles_directory()):
            result.append(paths.get_relative(ctx.genfiles_dir.path, out_includes_path))
        result.append(paths.get_relative(ctx.bin_dir.path, out_includes_path))
    return result

def _get_coverage_environment(ctx, cc_config, cc_toolchain):
    if not ctx.configuration.coverage_enabled:
        return {}
    env = {
        "COVERAGE_GCOV_PATH": _tool_path(cc_toolchain, "gcov"),
        "LLVM_COV": _tool_path(cc_toolchain, "llvm-cov"),
        "LLVM_PROFDATA": _tool_path(cc_toolchain, "llvm-profdata"),
        "GENERATE_LLVM_LCOV": "1" if cc_config.generate_llvm_lcov() else "0",
    }
    for k in list(env.keys()):
        if env[k] == None:
            env.pop(k)
    if cc_config.fdo_instrument():
        env["FDO_DIR"] = cc_config.fdo_instrument()
    return env

def _create_cc_instrumented_files_info(ctx, cc_config, cc_toolchain, metadata_files, virtual_to_original_headers = None):
    extensions = CC_SOURCE + \
                 C_SOURCE + \
                 CC_HEADER + \
                 ASSESMBLER_WITH_C_PREPROCESSOR + \
                 ASSEMBLER
    coverage_environment = {}
    if ctx.configuration.coverage_enabled:
        coverage_environment = _get_coverage_environment(ctx, cc_config, cc_toolchain)
    coverage_support_files = cc_toolchain._coverage_files if ctx.configuration.coverage_enabled else depset([])
    info = coverage_common.instrumented_files_info(
        ctx = ctx,
        source_attributes = ["srcs", "hdrs"],
        dependency_attributes = ["implementation_deps", "deps", "data"],
        extensions = extensions,
        metadata_files = metadata_files,
        coverage_support_files = coverage_support_files,
        coverage_environment = coverage_environment,
        reported_to_actual_sources = virtual_to_original_headers,
    )
    return info

def _linkopts(ctx, additional_make_variable_substitutions, cc_toolchain):
    linkopts = getattr(ctx.attr, "linkopts", [])
    if len(linkopts) == 0:
        return []
    targets = []
    for additional_linker_input in getattr(ctx.attr, "additional_linker_inputs", []):
        targets.append(additional_linker_input)
    tokens = []
    for linkopt in linkopts:
        expanded_linkopt = _expand(ctx, linkopt, additional_make_variable_substitutions, targets = targets)
        _tokenize(tokens, expanded_linkopt)
    if objc_common.is_apple_platform(cc_toolchain.cpu) and "-static" in tokens:
        fail("in linkopts attribute of cc_library rule {}: Apple builds do not support statically linked binaries".format(ctx.label))
    return tokens

def _defines_attribute(ctx, additional_make_variable_substitutions, attr_name, additional_targets):
    defines = getattr(ctx.attr, attr_name, [])
    if len(defines) == 0:
        return []
    targets = list(additional_targets)
    for dep in ctx.attr.deps:
        targets.append(dep)
    result = []
    for define in defines:
        expanded_define = _expand(ctx, define, additional_make_variable_substitutions, targets = targets)
        tokens = []
        _tokenize(tokens, expanded_define)
        if len(tokens) == 1:
            result.append(tokens[0])
        elif len(tokens) == 0:
            fail("empty definition not allowed", attr = attr_name)
        else:
            fail("definition contains too many tokens (found {}, expecting exactly one)".format(len(tokens)), attr = attr_name)

    return result

def _defines(ctx, additional_make_variable_substitutions):
    return _defines_attribute(ctx, additional_make_variable_substitutions, "defines", [])

def _local_defines(ctx, additional_make_variable_substitutions):
    return _defines_attribute(ctx, additional_make_variable_substitutions, "local_defines", getattr(ctx.attr, "additional_compiler_inputs", []))

def _linker_scripts(ctx):
    result = []
    for dep in ctx.attr.deps:
        for f in dep.files.to_list():
            if f.extension in cpp_file_types.LINKER_SCRIPT:
                result.append(f)
    return result

def _copts_filter(ctx, additional_make_variable_substitutions):
    nocopts = getattr(ctx.attr, "nocopts", None)

    if nocopts == None or len(nocopts) == 0:
        return nocopts

    # Check if nocopts is disabled.
    if ctx.fragments.cpp.disable_nocopts():
        fail("This attribute was removed. See https://github.com/bazelbuild/bazel/issues/8706 for details.", attr = "nocopts")

    # Expand nocopts and create CoptsFilter.
    return _expand(ctx, nocopts, additional_make_variable_substitutions)

def _proto_output_root(proto_root, bin_dir_path):
    if proto_root == ".":
        return bin_dir_path

    if proto_root.startswith(bin_dir_path):
        return proto_root
    else:
        return bin_dir_path + "/" + proto_root

def _check_cpp_modules(ctx, feature_configuration):
    if len(ctx.files.module_interfaces) == 0:
        return
    if not ctx.fragments.cpp.experimental_cpp_modules():
        fail("requires --experimental_cpp_modules", attr = "module_interfaces")
    if not cc_common.is_enabled(
        feature_configuration = feature_configuration,
        feature_name = "cpp_modules",
    ):
        fail("to use C++ modules, the feature cpp_modules must be enabled")

cc_helper = struct(
    CPP_TOOLCHAIN_TYPE = _CPP_TOOLCHAIN_TYPE,
    merge_cc_debug_contexts = _merge_cc_debug_contexts,
    is_code_coverage_enabled = _is_code_coverage_enabled,
    get_dynamic_libraries_for_runtime = _get_dynamic_libraries_for_runtime,
    get_dynamic_library_for_runtime_or_none = _get_dynamic_library_for_runtime_or_none,
    find_cpp_toolchain = _find_cpp_toolchain,
    use_cpp_toolchain = _use_cpp_toolchain,
    build_output_groups_for_emitting_compile_providers = _build_output_groups_for_emitting_compile_providers,
    merge_output_groups = _merge_output_groups,
    rule_error = _rule_error,
    attribute_error = _attribute_error,
    get_linking_contexts_from_deps = _get_linking_contexts_from_deps,
    get_compilation_contexts_from_deps = _get_compilation_contexts_from_deps,
    is_test_target = _is_test_target,
    extensions = extensions,
    build_precompiled_files = _build_precompiled_files,
    is_valid_shared_library_name = _is_valid_shared_library_name,
    is_valid_shared_library_artifact = _is_valid_shared_library_artifact,
    get_providers = _get_providers,
    is_compilation_outputs_empty = _is_compilation_outputs_empty,
    matches_extension = _matches_extension,
    get_static_mode_params_for_dynamic_library_libraries = _get_static_mode_params_for_dynamic_library_libraries,
    should_create_per_object_debug_info = should_create_per_object_debug_info,
    check_file_extensions = _check_file_extensions,
    check_srcs_extensions = _check_srcs_extensions,
    libraries_from_linking_context = _libraries_from_linking_context,
    additional_inputs_from_linking_context = _additional_inputs_from_linking_context,
    dll_hash_suffix = _dll_hash_suffix,
    get_windows_def_file_for_linking = _get_windows_def_file_for_linking,
    generate_def_file = _generate_def_file,
    stringify_linker_input = _stringify_linker_input,
    get_linked_artifact = _get_linked_artifact,
    collect_compilation_prerequisites = _collect_compilation_prerequisites,
    collect_native_cc_libraries = _collect_native_cc_libraries,
    create_strip_action = _create_strip_action,
    get_toolchain_global_make_variables = _get_toolchain_global_make_variables,
    get_cc_flags_make_variable = _get_cc_flags_make_variable,
    get_copts = _get_copts,
    get_expanded_env = _get_expanded_env,
    has_target_constraints = _has_target_constraints,
    is_non_empty_list_or_select = _is_non_empty_list_or_select,
    expand_make_variables_for_copts = _expand_make_variables_for_copts,
    build_linking_context_from_libraries = _build_linking_context_from_libraries,
    is_stamping_enabled = _is_stamping_enabled,
    is_stamping_enabled_for_aspect = _is_stamping_enabled_for_aspect,
    get_local_defines_for_runfiles_lookup = _get_local_defines_for_runfiles_lookup,
    are_labels_equal = _are_labels_equal,
    get_srcs = _get_srcs,
    get_cpp_module_interfaces = _get_cpp_module_interfaces,
    get_private_hdrs = _get_private_hdrs,
    get_public_hdrs = _get_public_hdrs,
    report_invalid_options = _report_invalid_options,
    system_include_dirs = _system_include_dirs,
    get_coverage_environment = _get_coverage_environment,
    create_cc_instrumented_files_info = _create_cc_instrumented_files_info,
    linkopts = _linkopts,
    defines = _defines,
    local_defines = _local_defines,
    linker_scripts = _linker_scripts,
    copts_filter = _copts_filter,
    package_exec_path = _package_exec_path,
    repository_exec_path = _repository_exec_path,
    proto_output_root = _proto_output_root,
    package_source_root = _package_source_root,
    tokenize = _tokenize,
    should_use_pic = _should_use_pic,
    check_cpp_modules = _check_cpp_modules,
)
