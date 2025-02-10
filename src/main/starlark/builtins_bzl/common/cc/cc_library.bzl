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

"""cc_library Starlark implementation replacing native"""

load(":common/cc/attrs.bzl", "common_attrs", "linkstatic_doc")
load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", "semantics")

cc_internal = _builtins.internal.cc_internal

def _cc_library_impl(ctx):
    cc_helper.check_srcs_extensions(ctx, ALLOWED_SRC_FILES, "cc_library", True)

    semantics.check_cc_shared_library_tags(ctx)

    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    cc_helper.report_invalid_options(cc_toolchain, ctx.fragments.cpp)

    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
        requested_features = ctx.features,
        unsupported_features = ctx.disabled_features,
    )

    cc_helper.check_cpp_modules(ctx, feature_configuration)

    precompiled_files = cc_helper.build_precompiled_files(ctx = ctx)

    semantics.validate_attributes(ctx = ctx)
    _check_no_repeated_srcs(ctx)

    semantics.check_can_use_implementation_deps(ctx)
    interface_deps = ctx.attr.deps + semantics.get_cc_runtimes(ctx, True)
    runtimes_copts = semantics.get_cc_runtimes_copts(ctx)
    compilation_contexts = cc_helper.get_compilation_contexts_from_deps(interface_deps)
    implementation_compilation_contexts = cc_helper.get_compilation_contexts_from_deps(ctx.attr.implementation_deps)

    additional_make_variable_substitutions = cc_helper.get_toolchain_global_make_variables(cc_toolchain)
    additional_make_variable_substitutions.update(cc_helper.get_cc_flags_make_variable(ctx, feature_configuration, cc_toolchain))

    (compilation_context, srcs_compilation_outputs) = cc_common.compile(
        actions = ctx.actions,
        name = ctx.label.name,
        cc_toolchain = cc_toolchain,
        feature_configuration = feature_configuration,
        user_compile_flags = runtimes_copts + cc_helper.get_copts(ctx, feature_configuration, additional_make_variable_substitutions, attr = "copts"),
        conly_flags = cc_helper.get_copts(ctx, feature_configuration, additional_make_variable_substitutions, attr = "conlyopts"),
        cxx_flags = cc_helper.get_copts(ctx, feature_configuration, additional_make_variable_substitutions, attr = "cxxopts"),
        defines = cc_helper.defines(ctx, additional_make_variable_substitutions),
        local_defines = cc_helper.local_defines(ctx, additional_make_variable_substitutions) + cc_helper.get_local_defines_for_runfiles_lookup(ctx, ctx.attr.deps + ctx.attr.implementation_deps),
        system_includes = cc_helper.system_include_dirs(ctx, additional_make_variable_substitutions),
        copts_filter = cc_helper.copts_filter(ctx, additional_make_variable_substitutions),
        purpose = "cc_library-compile",
        srcs = cc_helper.get_srcs(ctx),
        module_interfaces = cc_helper.get_cpp_module_interfaces(ctx),
        private_hdrs = cc_helper.get_private_hdrs(ctx),
        public_hdrs = cc_helper.get_public_hdrs(ctx),
        code_coverage_enabled = cc_helper.is_code_coverage_enabled(ctx),
        compilation_contexts = compilation_contexts,
        implementation_compilation_contexts = implementation_compilation_contexts,
        textual_hdrs = ctx.files.textual_hdrs,
        include_prefix = ctx.attr.include_prefix,
        strip_include_prefix = ctx.attr.strip_include_prefix,
        additional_inputs = ctx.files.additional_compiler_inputs,
    )

    precompiled_objects = cc_common.create_compilation_outputs(
        # TODO(bazel-team): Perhaps this should be objects, leaving as it is in the original
        # Java code for now. Changing it might cause breakages.
        objects = depset(precompiled_files[1]),
        pic_objects = depset(precompiled_files[1]),
    )

    compilation_outputs = cc_common.merge_compilation_outputs(
        compilation_outputs = [precompiled_objects, srcs_compilation_outputs],
    )

    supports_dynamic_linker = cc_common.is_enabled(
        feature_configuration = feature_configuration,
        feature_name = "supports_dynamic_linker",
    )

    create_dynamic_library = (not ctx.attr.linkstatic and
                              supports_dynamic_linker and
                              (not cc_helper.is_compilation_outputs_empty(compilation_outputs) or
                               cc_common.is_enabled(
                                   feature_configuration = feature_configuration,
                                   feature_name = "header_module_codegen",
                               )))

    output_group_builder = {}

    has_compilation_outputs = not cc_helper.is_compilation_outputs_empty(compilation_outputs)
    linking_context = CcInfo().linking_context
    empty_archive_linking_context = CcInfo().linking_context

    linking_contexts = cc_helper.get_linking_contexts_from_deps(ctx.attr.deps)
    linking_contexts.extend(cc_helper.get_linking_contexts_from_deps(ctx.attr.implementation_deps))
    if ctx.file.linkstamp != None:
        linkstamps = []
        linkstamps.append(cc_internal.create_linkstamp(
            actions = ctx.actions,
            linkstamp = ctx.file.linkstamp,
            compilation_context = compilation_context,
        ))
        linkstamps_linker_input = cc_common.create_linker_input(
            owner = ctx.label,
            linkstamps = depset(linkstamps),
        )
        linkstamps_linking_context = cc_common.create_linking_context(
            linker_inputs = depset([linkstamps_linker_input]),
        )
        linking_contexts.append(linkstamps_linking_context)

    if has_compilation_outputs:
        dll_name_suffix = ""
        additional_inputs = _filter_linker_scripts(ctx.files.deps) + ctx.files.additional_linker_inputs
        link_variables = {}
        is_windows_enabled = cc_common.is_enabled(feature_configuration = feature_configuration, feature_name = "targets_windows")
        if is_windows_enabled:
            dll_name_suffix = cc_helper.dll_hash_suffix(ctx, feature_configuration, ctx.fragments.cpp)
            generated_def_file = None

            def_parser = ctx.file._def_parser
            if def_parser != None:
                generated_def_file = cc_helper.generate_def_file(ctx, def_parser, compilation_outputs.objects, ctx.label.name + dll_name_suffix)
                output_group_builder["def_file"] = depset([generated_def_file])

            win_def_file = cc_helper.get_windows_def_file_for_linking(ctx, ctx.file.win_def_file, generated_def_file, feature_configuration)
            link_variables["def_file_path"] = win_def_file.path
            additional_inputs.append(win_def_file)

        (
            linking_context,
            linking_outputs,
        ) = cc_common.create_linking_context_from_compilation_outputs(
            actions = ctx.actions,
            name = ctx.label.name,
            compilation_outputs = compilation_outputs,
            cc_toolchain = cc_toolchain,
            feature_configuration = feature_configuration,
            additional_inputs = additional_inputs,
            linking_contexts = linking_contexts,
            user_link_flags = cc_helper.linkopts(ctx, additional_make_variable_substitutions, cc_toolchain),
            alwayslink = ctx.attr.alwayslink,
            disallow_dynamic_library = not create_dynamic_library,
            linked_dll_name_suffix = dll_name_suffix,
            variables_extension = link_variables,
        )
    elif semantics.should_create_empty_archive():
        precompiled_files_count = 0
        for precompiled_files_entry in precompiled_files:
            precompiled_files_count += len(precompiled_files_entry)

        (
            linking_context,
            linking_outputs,
        ) = cc_common.create_linking_context_from_compilation_outputs(
            actions = ctx.actions,
            name = ctx.label.name,
            cc_toolchain = cc_toolchain,
            compilation_outputs = cc_common.create_compilation_outputs(),
            feature_configuration = feature_configuration,
            disallow_dynamic_library = True,
            alwayslink = ctx.attr.alwayslink,
        )

        if precompiled_files_count == 0:
            empty_archive_linking_context = linking_context
    else:
        linking_outputs = struct(library_to_link = None)

    _add_linker_artifacts_output_groups(output_group_builder, linking_outputs)

    precompiled_libraries = _convert_precompiled_libraries_to_library_to_link(
        ctx,
        cc_toolchain,
        feature_configuration,
        ctx.fragments.cpp.force_pic(),
        precompiled_files,
    )

    if not cc_helper.is_compilation_outputs_empty(compilation_outputs):
        _check_if_link_outputs_colliding_with_precompiled_files(
            ctx,
            linking_outputs,
            precompiled_libraries,
        )

    precompiled_linking_context = cc_helper.build_linking_context_from_libraries(ctx, precompiled_libraries)

    contexts_to_merge = [precompiled_linking_context, empty_archive_linking_context]
    if has_compilation_outputs:
        contexts_to_merge.append(linking_context)
    else:
        user_link_flags = cc_helper.linkopts(ctx, additional_make_variable_substitutions, cc_toolchain)
        linker_scripts = _filter_linker_scripts(ctx.files.deps)
        additional_linker_inputs = ctx.files.additional_linker_inputs
        if len(user_link_flags) > 0 or len(linker_scripts) > 0 or len(additional_linker_inputs) > 0 or not semantics.should_create_empty_archive():
            linker_input = cc_common.create_linker_input(
                owner = ctx.label,
                user_link_flags = user_link_flags,
                additional_inputs = depset(linker_scripts + additional_linker_inputs),
            )
            contexts_to_merge.append(cc_common.create_linking_context(linker_inputs = depset([linker_input])))

        contexts_to_merge.extend(linking_contexts)

    linking_context = cc_common.merge_linking_contexts(
        linking_contexts = contexts_to_merge,
    )

    libraries_to_link = _create_libraries_to_link_list(
        linking_outputs.library_to_link,
        precompiled_libraries,
    )

    linking_context_for_runfiles = cc_helper.build_linking_context_from_libraries(ctx, libraries_to_link)

    cc_native_library_info = cc_helper.collect_native_cc_libraries(
        deps = ctx.attr.deps,
        libraries = libraries_to_link,
    )

    files_builder = []
    if linking_outputs.library_to_link != None:
        artifacts_to_build = linking_outputs.library_to_link
        if artifacts_to_build.static_library != None:
            files_builder.append(artifacts_to_build.static_library)

        if artifacts_to_build.pic_static_library != None:
            files_builder.append(artifacts_to_build.pic_static_library)

        if not cc_common.is_enabled(
            feature_configuration = feature_configuration,
            feature_name = "targets_windows",
        ):
            if artifacts_to_build.resolved_symlink_dynamic_library != None:
                files_builder.append(artifacts_to_build.resolved_symlink_dynamic_library)
            elif artifacts_to_build.dynamic_library != None:
                files_builder.append(artifacts_to_build.dynamic_library)

            if artifacts_to_build.resolved_symlink_interface_library != None:
                files_builder.append(artifacts_to_build.resolved_symlink_interface_library)
            elif artifacts_to_build.interface_library != None:
                files_builder.append(artifacts_to_build.interface_library)

    instrumented_files_info = cc_helper.create_cc_instrumented_files_info(
        ctx = ctx,
        cc_config = ctx.fragments.cpp,
        cc_toolchain = cc_toolchain,
        metadata_files = compilation_outputs.gcno_files() + compilation_outputs.pic_gcno_files(),
    )

    runfiles_list = []
    for data_dep in ctx.attr.data:
        if data_dep[DefaultInfo].data_runfiles.files:
            runfiles_list.append(data_dep[DefaultInfo].data_runfiles)
        else:
            # This branch ensures interop with custom Starlark rules following
            # https://bazel.build/extending/rules#runfiles_features_to_avoid
            runfiles_list.append(ctx.runfiles(transitive_files = data_dep[DefaultInfo].files))
            runfiles_list.append(data_dep[DefaultInfo].default_runfiles)

    for src in ctx.attr.srcs:
        runfiles_list.append(src[DefaultInfo].default_runfiles)

    for dep in ctx.attr.deps:
        runfiles_list.append(dep[DefaultInfo].default_runfiles)

    for dep in ctx.attr.implementation_deps:
        runfiles_list.append(dep[DefaultInfo].default_runfiles)

    runfiles = ctx.runfiles().merge_all(runfiles_list)

    default_runfiles = ctx.runfiles(files = cc_helper.get_dynamic_libraries_for_runtime(linking_context_for_runfiles, True))
    default_runfiles = runfiles.merge(default_runfiles)

    data_runfiles = ctx.runfiles(files = cc_helper.get_dynamic_libraries_for_runtime(linking_context_for_runfiles, False))
    data_runfiles = runfiles.merge(data_runfiles)

    current_output_groups = cc_helper.build_output_groups_for_emitting_compile_providers(
        compilation_outputs,
        compilation_context,
        ctx.fragments.cpp,
        cc_toolchain,
        feature_configuration,
        ctx,
        generate_hidden_top_level_group = True,
    )
    providers = []

    providers.append(DefaultInfo(
        files = depset(files_builder),
        default_runfiles = default_runfiles,
        data_runfiles = data_runfiles,
    ))

    debug_context = cc_helper.merge_cc_debug_contexts(
        compilation_outputs,
        cc_helper.get_providers(ctx.attr.deps + ctx.attr.implementation_deps, CcInfo),
    )
    cc_info = CcInfo(
        compilation_context = compilation_context,
        linking_context = linking_context,
        debug_context = debug_context,
        cc_native_library_info = cc_native_library_info,
    )

    merged_output_groups = cc_helper.merge_output_groups(
        [current_output_groups, output_group_builder],
    )

    providers.append(cc_info)
    providers.append(OutputGroupInfo(**merged_output_groups))
    providers.append(instrumented_files_info)

    return providers

def _add_linker_artifacts_output_groups(output_group_builder, linking_outputs):
    archive_file = []
    dynamic_library = []

    lib = linking_outputs.library_to_link

    if lib == None:
        return

    if lib.static_library != None:
        archive_file.append(lib.static_library)
    elif lib.pic_static_library != None:
        archive_file.append(lib.pic_static_library)

    if lib.resolved_symlink_dynamic_library != None:
        dynamic_library.append(lib.resolved_symlink_dynamic_library)
    elif lib.dynamic_library != None:
        dynamic_library.append(lib.dynamic_library)

    if lib.resolved_symlink_interface_library != None:
        dynamic_library.append(lib.resolved_symlink_interface_library)
    elif lib.interface_library != None:
        dynamic_library.append(lib.interface_library)

    output_group_builder["archive"] = depset(archive_file)
    output_group_builder["dynamic_library"] = depset(dynamic_library)

def _convert_precompiled_libraries_to_library_to_link(
        ctx,
        cc_toolchain,
        feature_configuration,
        force_pic,
        precompiled_files):
    static_libraries = _build_map_identifier_to_artifact(precompiled_files[2])
    pic_static_libraries = _build_map_identifier_to_artifact(precompiled_files[3])
    alwayslink_static_libraries = _build_map_identifier_to_artifact(precompiled_files[4])
    alwayslink_pic_static_libraries = _build_map_identifier_to_artifact(precompiled_files[5])
    dynamic_libraries = _build_map_identifier_to_artifact(precompiled_files[6])

    libraries = []

    identifiers_used = {}
    static_libraries_it = []
    static_libraries_it.extend(static_libraries.items())
    static_libraries_it.extend(alwayslink_static_libraries.items())
    for identifier, v in static_libraries_it:
        static_library = None
        pic_static_library = None
        dynamic_library = None

        has_pic = identifier in pic_static_libraries
        has_always_pic = identifier in alwayslink_pic_static_libraries
        if has_pic or has_always_pic:
            if has_pic:
                pic_static_library = pic_static_libraries[identifier]
            else:
                pic_static_library = alwayslink_pic_static_libraries[identifier]
        if not force_pic or not (has_pic or has_always_pic):
            static_library = v

        if identifier in dynamic_libraries:
            dynamic_library = dynamic_libraries[identifier]

        identifiers_used[identifier] = True

        library = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            static_library = static_library,
            pic_static_library = pic_static_library,
            dynamic_library = dynamic_library,
            alwayslink = identifier in alwayslink_static_libraries,
        )
        libraries.append(library)

    pic_static_libraries_it = []
    pic_static_libraries_it.extend(pic_static_libraries.items())
    pic_static_libraries_it.extend(alwayslink_pic_static_libraries.items())
    for identifier, v in pic_static_libraries_it:
        if identifier in identifiers_used:
            continue

        pic_static_library = v
        if identifier in dynamic_libraries:
            dynamic_library = dynamic_libraries[identifier]

        identifiers_used[identifier] = True

        library = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            pic_static_library = pic_static_library,
            alwayslink = identifier in alwayslink_static_libraries,
        )
        libraries.append(library)

    for identifier, v in dynamic_libraries.items():
        if identifier in identifiers_used:
            continue

        dynamic_library = dynamic_libraries[identifier]

        library = cc_common.create_library_to_link(
            actions = ctx.actions,
            feature_configuration = feature_configuration,
            cc_toolchain = cc_toolchain,
            dynamic_library = dynamic_library,
        )
        libraries.append(library)

    return libraries

def _build_map_identifier_to_artifact(artifacts):
    libraries = {}
    for artifact in artifacts:
        identifier = _identifier_of_artifact(artifact)
        if identifier in libraries:
            fail(
                "Trying to link twice a library with the same identifier '{}',".format(identifier) +
                "files: {} and {}".format(
                    artifact.short_path,
                    libraries[identifier].short_path,
                ),
                attr = "srcs",
            )
        libraries[identifier] = artifact
    return libraries

def _identifier_of_artifact(artifact):
    name = artifact.short_path
    for pic_suffix in [".pic.a", ".nopic.a", ".pic.lo"]:
        if name.endswith(pic_suffix):
            return name[:len(name) - len(pic_suffix)]

    return name[:len(name) - len(artifact.extension) - 1]

def _identifier_of_library(library):
    if library.static_library != None:
        return _identifier_of_artifact(library.static_library)
    if library.pic_static_library != None:
        return _identifier_of_artifact(library.pic_static_library)
    if library.dynamic_library != None:
        return _identifier_of_artifact(library.dynamic_library)
    if library.interface_library != None:
        return _identifier_of_artifact(library.interface_library)

    return None

def _create_libraries_to_link_list(current_library, precompiled_libraries):
    libraries = []
    libraries.extend(precompiled_libraries)
    if current_library != None:
        libraries.append(current_library)

    return libraries

def _filter_linker_scripts(files):
    linker_scripts = []
    for file in files:
        extension = "." + file.extension
        if extension in LINKER_SCRIPT:
            linker_scripts.append(file)
    return linker_scripts

def _check_if_link_outputs_colliding_with_precompiled_files(ctx, linking_outputs, precompiled_libraries):
    identifier = _identifier_of_library(linking_outputs.library_to_link)
    for precompiled_library in precompiled_libraries:
        precompiled_library_identifier = _identifier_of_library(precompiled_library)
        if precompiled_library_identifier == identifier:
            fail("Can't put library with identifier '{}' into the srcs of a cc_library with".format(identifier) +
                 " the same name ({}) which also contains other code or objects to link".format(
                     ctx.label.name,
                 ))

def _check_no_repeated_srcs(ctx):
    seen = {}
    for target in ctx.attr.srcs:
        if DefaultInfo in target:
            for file in target.files.to_list():
                extension = "." + file.extension
                if extension not in cc_helper.extensions.CC_HEADER:
                    if extension in cc_helper.extensions.CC_AND_OBJC:
                        if file.path in seen:
                            if seen[file.path] != target.label:
                                fail("Artifact '{}' is duplicated (through ".format(file.path) +
                                     "'{}' and '{}')".format(str(seen[file.path]), str(target.label)))
                        seen[file.path] = target.label

ALLOWED_SRC_FILES = []
ALLOWED_SRC_FILES.extend(cc_helper.extensions.CC_SOURCE)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.C_SOURCE)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.CC_HEADER)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.ASSESMBLER_WITH_C_PREPROCESSOR)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.ASSEMBLER)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.ARCHIVE)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.PIC_ARCHIVE)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.ALWAYSLINK_LIBRARY)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.ALWAYSLINK_PIC_LIBRARY)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.SHARED_LIBRARY)

SRCS_FOR_COMPILATION = []
SRCS_FOR_COMPILATION.extend(cc_helper.extensions.CC_SOURCE)
SRCS_FOR_COMPILATION.extend(cc_helper.extensions.C_SOURCE)
SRCS_FOR_COMPILATION.extend(cc_helper.extensions.ASSESMBLER_WITH_C_PREPROCESSOR)
SRCS_FOR_COMPILATION.extend(cc_helper.extensions.ASSEMBLER)

ALLOWED_SRC_FILES.extend(cc_helper.extensions.OBJECT_FILE)
ALLOWED_SRC_FILES.extend(cc_helper.extensions.PIC_OBJECT_FILE)

LINKER_SCRIPT = [".ld", ".lds", ".ldscript"]
PREPROCESSED_C = [".i"]
DEPS_ALLOWED_RULES = [
    "genrule",
    "cc_library",
    "cc_inc_library",
    "cc_embed_data",
    "go_library",
    "objc_library",
    "cc_import",
    "cc_proto_library",
    "gentpl",
    "gentplvars",
    "genantlr",
    "sh_library",
    "cc_binary",
    "cc_test",
]

cc_library = rule(
    implementation = _cc_library_impl,
    doc = """
<p>Use <code>cc_library()</code> for C++-compiled libraries.
  The result is  either a <code>.so</code>, <code>.lo</code>,
  or <code>.a</code>, depending on what is needed.
</p>

<p>
  If you build something with static linking that depends on
  a <code>cc_library</code>, the output of a depended-on library rule
  is the <code>.a</code> file. If you specify
   <code>alwayslink=True</code>, you get the <code>.lo</code> file.
</p>

<p>
  The actual output file name is <code>lib<i>foo</i>.so</code> for
  the shared library, where <i>foo</i> is the name of the rule.  The
  other kinds of libraries end with <code>.lo</code> and <code>.a</code>,
  respectively.  If you need a specific shared library name, for
  example, to define a Python module, use a genrule to copy the library
  to the desired name.
</p>

<h4 id="hdrs">Header inclusion checking</h4>

<p>
  All header files that are used in the build must be declared in
  the <code>hdrs</code> or <code>srcs</code> of <code>cc_*</code> rules.
  This is enforced.
</p>

<p>
  For <code>cc_library</code> rules, headers in <code>hdrs</code> comprise the
  public interface of the library and can be directly included both
  from the files in <code>hdrs</code> and <code>srcs</code> of the library
  itself as well as from files in <code>hdrs</code> and <code>srcs</code>
  of <code>cc_*</code> rules that list the library in their <code>deps</code>.
  Headers in <code>srcs</code> must only be directly included from the files
  in <code>hdrs</code> and <code>srcs</code> of the library itself. When
  deciding whether to put a header into <code>hdrs</code> or <code>srcs</code>,
  you should ask whether you want consumers of this library to be able to
  directly include it. This is roughly the same decision as
  between <code>public</code> and <code>private</code> visibility in programming languages.
</p>

<p>
  <code>cc_binary</code> and <code>cc_test</code> rules do not have an exported
  interface, so they also do not have a <code>hdrs</code> attribute. All headers
  that belong to the binary or test directly should be listed in
  the <code>srcs</code>.
</p>

<p>
  To illustrate these rules, look at the following example.
</p>

<pre><code class="lang-starlark">
cc_binary(
    name = "foo",
    srcs = [
        "foo.cc",
        "foo.h",
    ],
    deps = [":bar"],
)

cc_library(
    name = "bar",
    srcs = [
        "bar.cc",
        "bar-impl.h",
    ],
    hdrs = ["bar.h"],
    deps = [":baz"],
)

cc_library(
    name = "baz",
    srcs = [
        "baz.cc",
        "baz-impl.h",
    ],
    hdrs = ["baz.h"],
)
</code></pre>

<p>
  The allowed direct inclusions in this example are listed in the table below.
  For example <code>foo.cc</code> is allowed to directly
  include <code>foo.h</code> and <code>bar.h</code>, but not <code>baz.h</code>.
</p>

<table class="table table-striped table-bordered table-condensed">
  <thead>
    <tr><th>Including file</th><th>Allowed inclusions</th></tr>
  </thead>
  <tbody>
    <tr><td>foo.h</td><td>bar.h</td></tr>
    <tr><td>foo.cc</td><td>foo.h bar.h</td></tr>
    <tr><td>bar.h</td><td>bar-impl.h baz.h</td></tr>
    <tr><td>bar-impl.h</td><td>bar.h baz.h</td></tr>
    <tr><td>bar.cc</td><td>bar.h bar-impl.h baz.h</td></tr>
    <tr><td>baz.h</td><td>baz-impl.h</td></tr>
    <tr><td>baz-impl.h</td><td>baz.h</td></tr>
    <tr><td>baz.cc</td><td>baz.h baz-impl.h</td></tr>
  </tbody>
</table>

<p>
  The inclusion checking rules only apply to <em>direct</em>
  inclusions. In the example above <code>foo.cc</code> is allowed to
  include <code>bar.h</code>, which may include <code>baz.h</code>, which in
  turn is allowed to include <code>baz-impl.h</code>. Technically, the
  compilation of a <code>.cc</code> file may transitively include any header
  file in the <code>hdrs</code> or <code>srcs</code> in
  any <code>cc_library</code> in the transitive <code>deps</code> closure. In
  this case the compiler may read <code>baz.h</code> and <code>baz-impl.h</code>
  when compiling <code>foo.cc</code>, but <code>foo.cc</code> must not
  contain <code>#include "baz.h"</code>. For that to be
  allowed, <code>baz</code> must be added to the <code>deps</code>
  of <code>foo</code>.
</p>

<p>
  Bazel depends on toolchain support to enforce the inclusion checking rules.
  The <code>layering_check</code> feature has to be supported by the toolchain
  and requested explicitly, for example via the
  <code>--features=layering_check</code> command-line flag or the
  <code>features</code> parameter of the
  <a href="${link package}"><code>package</code></a> function. The toolchains
  provided by Bazel only support this feature with clang on Unix and macOS.
</p>

<h4 id="cc_library_examples">Examples</h4>

<p id="alwayslink_lib_example">
   We use the <code>alwayslink</code> flag to force the linker to link in
   this code although the main binary code doesn't reference it.
</p>

<pre><code class="lang-starlark">
cc_library(
    name = "ast_inspector_lib",
    srcs = ["ast_inspector_lib.cc"],
    hdrs = ["ast_inspector_lib.h"],
    visibility = ["//visibility:public"],
    deps = ["//third_party/llvm/llvm/tools/clang:frontend"],
    # alwayslink as we want to be able to call things in this library at
    # debug time, even if they aren't used anywhere in the code.
    alwayslink = 1,
)
</code></pre>


<p>The following example comes from
   <code>third_party/python2_4_3/BUILD</code>.
   Some of the code uses the <code>dl</code> library (to load
   another, dynamic library), so this
   rule specifies the <code>-ldl</code> link option to link the
   <code>dl</code> library.
</p>

<pre><code class="lang-starlark">
cc_library(
    name = "python2_4_3",
    linkopts = [
        "-ldl",
        "-lutil",
    ],
    deps = ["//third_party/expat"],
)
</code></pre>

<p>The following example comes from <code>third_party/kde/BUILD</code>.
   We keep pre-built <code>.so</code> files in the depot.
   The header files live in a subdirectory named <code>include</code>.
</p>

<pre><code class="lang-starlark">
cc_library(
    name = "kde",
    srcs = [
        "lib/libDCOP.so",
        "lib/libkdesu.so",
        "lib/libkhtml.so",
        "lib/libkparts.so",
        <var>...more .so files...</var>,
    ],
    includes = ["include"],
    deps = ["//third_party/X11"],
)
</code></pre>

<p>The following example comes from <code>third_party/gles/BUILD</code>.
   Third-party code often needs some <code>defines</code> and
   <code>linkopts</code>.
</p>

<pre><code class="lang-starlark">
cc_library(
    name = "gles",
    srcs = [
        "GLES/egl.h",
        "GLES/gl.h",
        "ddx.c",
        "egl.c",
    ],
    defines = [
        "USE_FLOAT",
        "__GL_FLOAT",
        "__GL_COMMON",
    ],
    linkopts = ["-ldl"],  # uses dlopen(), dl library
    deps = [
        "es",
        "//third_party/X11",
    ],
)
</code></pre>
""",
    attrs = common_attrs | {
        "hdrs": attr.label_list(
            allow_files = True,
            flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
            doc = """
The list of header files published by
this library to be directly included by sources in dependent rules.
<p>This is the strongly preferred location for declaring header files that
 describe the interface for the library. These headers will be made
 available for inclusion by sources in this rule or in dependent rules.
 Headers not meant to be included by a client of this library should be
 listed in the <code>srcs</code> attribute instead, even if they are
 included by a published header. See <a href="#hdrs">"Header inclusion
 checking"</a> for a more detailed description. </p>
<p>Permitted <code>headers</code> file types:
  <code>.h</code>,
  <code>.hh</code>,
  <code>.hpp</code>,
  <code>.hxx</code>.
</p>
        """,
        ),
        "textual_hdrs": attr.label_list(
            allow_files = True,
            flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
            doc = """
The list of header files published by
this library to be textually included by sources in dependent rules.
<p>This is the location for declaring header files that cannot be compiled on their own;
 that is, they always need to be textually included by other source files to build valid
 code.</p>
""",
        ),
        "deps": attr.label_list(
            providers = [CcInfo],
            flags = ["SKIP_ANALYSIS_TIME_FILETYPE_CHECK"],
            allow_files = LINKER_SCRIPT + PREPROCESSED_C,
            allow_rules = DEPS_ALLOWED_RULES,
            doc = """
The list of other libraries that the library target depends upon.

<p>These can be <code>cc_library</code> or <code>objc_library</code> targets.</p>

<p>See general comments about <code>deps</code>
  at <a href="${link common-definitions#typical-attributes}">Typical attributes defined by
  most build rules</a>.
</p>
<p>These should be names of C++ library rules.
   When you build a binary that links this rule's library,
   you will also link the libraries in <code>deps</code>.
</p>
<p>Despite the "deps" name, not all of this library's clients
   belong here.  Run-time data dependencies belong in <code>data</code>.
   Source files generated by other rules belong in <code>srcs</code>.
</p>
<p>To link in a pre-compiled third-party library, add its name to
   the <code>srcs</code> instead.
</p>
<p>To depend on something without linking it to this library, add its
   name to the <code>data</code> instead.
</p>
""",
        ),
        "implementation_deps": attr.label_list(providers = [CcInfo], allow_files = False, doc = """
The list of other libraries that the library target depends on. Unlike with
<code>deps</code>, the headers and include paths of these libraries (and all their
transitive deps) are only used for compilation of this library, and not libraries that
depend on it. Libraries specified with <code>implementation_deps</code> are still linked in
binary targets that depend on this library.
"""),
        "strip_include_prefix": attr.string(doc = """
The prefix to strip from the paths of the headers of this rule.

<p>When set, the headers in the <code>hdrs</code> attribute of this rule are accessible
at their path with this prefix cut off.

<p>If it's a relative path, it's taken as a package-relative one. If it's an absolute one,
it's understood as a repository-relative path.

<p>The prefix in the <code>include_prefix</code> attribute is added after this prefix is
stripped.

<p>This attribute is only legal under <code>third_party</code>.
"""),
        "include_prefix": attr.string(doc = """
The prefix to add to the paths of the headers of this rule.

<p>When set, the headers in the <code>hdrs</code> attribute of this rule are accessible
at is the value of this attribute prepended to their repository-relative path.

<p>The prefix in the <code>strip_include_prefix</code> attribute is removed before this
prefix is added.

<p>This attribute is only legal under <code>third_party</code>.
"""),
        "additional_compiler_inputs": attr.label_list(
            allow_files = True,
            flags = ["ORDER_INDEPENDENT", "DIRECT_COMPILE_TIME_INPUT"],
            doc = """
Any additional files you might want to pass to the compiler command line, such as sanitizer
ignorelists, for example. Files specified here can then be used in copts with the
$(location) function.
""",
        ),
        "alwayslink": attr.bool(default = False, doc = """
If 1, any binary that depends (directly or indirectly) on this C++
library will link in all the object files for the files listed in
<code>srcs</code>, even if some contain no symbols referenced by the binary.
This is useful if your code isn't explicitly called by code in
the binary, e.g., if your code registers to receive some callback
provided by some service.

<p>If alwayslink doesn't work with VS 2017 on Windows, that is due to a
<a href="https://github.com/bazelbuild/bazel/issues/3949">known issue</a>,
please upgrade your VS 2017 to the latest version.</p>
"""),
        "linkstatic": attr.bool(default = False, doc = linkstatic_doc),
        "linkstamp": attr.label(allow_single_file = True, doc = """
Simultaneously compiles and links the specified C++ source file into the final
binary. This trickery is required to introduce timestamp
information into binaries; if we compiled the source file to an
object file in the usual way, the timestamp would be incorrect.
A linkstamp compilation may not include any particular set of
compiler flags and so should not depend on any particular
header, compiler option, or other build variable.
<em class='harmful'>This option should only be needed in the
<code>base</code> package.</em>
"""),
        "linkopts": attr.string_list(doc = """
See <a href="${link cc_binary.linkopts}"><code>cc_binary.linkopts</code></a>.
The <code>linkopts</code> attribute is also applied to any target that
depends, directly or indirectly, on this library via <code>deps</code>
attributes (or via other attributes that are treated similarly:
the <a href="${link cc_binary.malloc}"><code>malloc</code></a>
attribute of <a href="${link cc_binary}"><code>cc_binary</code></a>). Dependency
linkopts take precedence over dependent linkopts (i.e. dependency linkopts
appear later in the command line). Linkopts specified in
<a href='../user-manual.html#flag--linkopt'><code>--linkopt</code></a>
take precedence over rule linkopts.
</p>
<p>
Note that the <code>linkopts</code> attribute only applies
when creating <code>.so</code> files or executables, not
when creating <code>.a</code> or <code>.lo</code> files.
So if the <code>linkstatic=True</code> attribute is set, the
<code>linkopts</code> attribute has no effect on the creation of
this library, only on other targets which depend on this library.
</p>
<p>
Also, it is important to note that "-Wl,-soname" or "-Xlinker -soname"
options are not supported and should never be specified in this attribute.
</p>
<p> The <code>.so</code> files produced by <code>cc_library</code>
rules are not linked against the libraries that they depend
on.  If you're trying to create a shared library for use
outside of the main repository, e.g. for manual use
with <code>dlopen()</code> or <code>LD_PRELOAD</code>,
it may be better to use a <code>cc_binary</code> rule
with the <code>linkshared=True</code> attribute.
See <a href="${link cc_binary.linkshared}"><code>cc_binary.linkshared</code></a>.
</p>
"""),
        # buildifier: disable=attr-license
        "licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
        "_stl": semantics.get_stl(),
        "_def_parser": semantics.get_def_parser(),
        "_use_auto_exec_groups": attr.bool(default = True),
    } | semantics.get_distribs_attr() | semantics.get_implementation_deps_allowed_attr() | semantics.get_nocopts_attr(),
    toolchains = cc_helper.use_cpp_toolchain() +
                 semantics.get_runtimes_toolchain(),
    fragments = ["cpp"] + semantics.additional_fragments(),
    provides = [CcInfo],
    exec_groups = {
        "cpp_link": exec_group(toolchains = cc_helper.use_cpp_toolchain()),
    },
)
