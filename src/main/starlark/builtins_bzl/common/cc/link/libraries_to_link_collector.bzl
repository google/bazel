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
"""Goes over LegacyLinkerInputs and produces LibraryToLinkValue-s."""

load(":common/cc/cc_helper_internal.bzl", "artifact_category", "is_shared_library", "is_versioned_shared_library")
load(":common/cc/link/target_types.bzl", "LINK_TARGET_TYPE", "is_dynamic_library")
load(":common/paths.bzl", "paths")

cc_internal = _builtins.internal.cc_internal

LINKING_MODE = struct(
    STATIC = "static",
    DYNAMIC = "dynamic",
)

# TODO(b/338618120): This code is doing 2 distinct tasks and should be split accordingly: converting
# LegacyLinkerInputs to LibraryToLinkValues and collecting rpaths for dynamic libraries.

# TODO(b/338618120): Refine the signature of collect_libraries_to_link. Large objects are passed in
# just to determine a single property, for example link_type and linking_mode are passed in, just to
# determine need_toolchain_libraries_rpath. Refining the signature will increase readability.
def collect_libraries_to_link(
        linker_inputs,
        cc_toolchain,
        feature_configuration,
        output,
        dynamic_library_solib_symlink_output,
        link_type,
        linking_mode,
        is_native_deps,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        allow_lto_indexing,
        lto_mapping,
        workspace_name):
    """Goes over LegacyLinkerInputs and produces LibraryToLinkValue-s and rpaths.

    LegacyLinkerInputs are produced by convert_linker_inputs.bzl. LibraryToLinkValues are consumed
    by link_build_variables.bzl.

    When linking a shared library fully or mostly static then we need to link in *all* dependent
    files, not just what the shared library needs for its own code. This is done by wrapping all
    objects/libraries with -Wl,-whole-archive and -Wl,-no-whole-archive. For this case the
    globalNeedWholeArchive parameter must be set to true. Otherwise only library objects (.lo) need
    to be wrapped with -Wl,-whole-archive and -Wl,-no-whole-archive.

    Args:
      linker_inputs: (list[LegacyLinkerInput]) Linker inputs
      cc_toolchain: cc_toolchain providing some extra information in the conversion.
      feature_configuration: Feature configuration to be queried.
      output: (File) The linker's output.
      dynamic_library_solib_symlink_output: (None|File) Symlink to the dynamic library being created.
      link_type: (LINK_TARGET_TYPE) The type of ELF file to be created (.a, .so, .lo, executable).
      linking_mode: ("static"|"dynamic") Linking mode.
      is_native_deps: (bool) Is this link action is used for a native dependency library.
      need_whole_archive: (bool) Whether we need to use whole-archive for the link.
      solib_dir: (str) solib directory.
      toolchain_libraries_solib_dir: (str) Directory where toolchain stores language-runtime libraries (libstdc++, libc++ ...).
      allow_lto_indexing: (bool) Is LTO indexing being done.
      lto_mapping: (dict[File, File]) Map from bitcode files to object files. Used to replace all linker inputs.
      workspace_name: (str) Workspace name. To support legacy code.

    Returns:
      ({libraries_to_link: list[LibraryToLinkValue],
        expanded_linker_inputs: list[LegacyLinkerInput],
        library_search_directories: depset[str],
        all_runtime_library_search_directories: depset[str]})

      - Returned libraries_to_link are passed in this form to link_build_variables.
      - expanded_linker_inputs are all the files that will be consumed by the linker (if
        start-end library is used, then it contains object files, otherwise an archive)
      - library_search_directories are absolute directories of all dynamic libraries
      - all_runtime_library_search_directories are directories of all dynamic libraries

      Both depsets of directories are exposed to the link_build_variables.

    """
    return cc_internal.collect_libraries_to_link(
        linker_inputs,
        cc_toolchain,
        feature_configuration,
        output,
        dynamic_library_solib_symlink_output,
        link_type,
        linking_mode,
        is_native_deps,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        allow_lto_indexing,
        lto_mapping,
        workspace_name,
    )

# A pure Starlark implementation of collect_libraries_to_link at the moment unused,
# because it causes a regression
def _collect_libraries_to_link(
        linker_inputs,
        cc_toolchain,
        feature_configuration,
        output,
        dynamic_library_solib_symlink_output,
        link_type,
        linking_mode,
        is_native_deps,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        allow_lto_indexing,
        lto_mapping,
        workspace_name):
    need_toolchain_libraries_rpath = (
        toolchain_libraries_solib_dir and
        (is_dynamic_library(link_type) or
         (link_type == LINK_TARGET_TYPE.EXECUTABLE and linking_mode == LINKING_MODE.DYNAMIC))
    )

    # Collect LibrariesToLink
    library_search_directories = []
    rpath_roots_for_explicit_so_deps = {}
    expanded_linker_inputs = []

    # List of command line parameters that need to be placed *outside* of
    # --whole-archive ... --no-whole-archive.
    libraries_to_link = []

    # Calculate the correct relative value for the "-rpath" link option (which sets
    # the search path for finding shared libraries).
    solib_dir_path_string = cc_toolchain._solib_dir
    if is_native_deps and cc_toolchain._cpp_configuration.share_native_deps():
        # For shared native libraries, special symlinking is applied to ensure C++
        # toolchain libraries are available under $ORIGIN/_solib_[arch]. So we set the RPATH to find
        # them.

        # Note that we have to do this because $ORIGIN points to different paths for
        # different targets. In other words, blaze-bin/d1/d2/d3/a_shareddeps.so and
        # blaze-bin/d4/b_shareddeps.so have different path depths. The first could
        # reference a standard blaze-bin/_solib_[arch] via $ORIGIN/../../../_solib[arch],
        # and the second could use $ORIGIN/../_solib_[arch]. But since this is a shared
        # artifact, both are symlinks to the same place, so
        # there's no *one* RPATH setting that fits all targets involved in the sharing.
        potential_solib_parents = []
        rpath_roots = [solib_dir_path_string + "/"]
    else:
        potential_solib_parents = _find_potential_solib_parents(output, dynamic_library_solib_symlink_output, workspace_name)
        rpath_roots = [exec_root + solib_dir_path_string + "/" for exec_root in potential_solib_parents]

    lto_map = dict(lto_mapping)  # We make a copy, to check all mapped files were used

    # include_solib_dir: bool, include_toolchain_libraries_solib_dir: bool
    # TODO(b/338618120): instead of returning include_solib_dir, and the paths inside _add_linker_inputs
    include_solib_dir, include_toolchain_libraries_solib_dir = _add_linker_inputs(
        linker_inputs,
        cc_toolchain,
        feature_configuration,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        rpath_roots,
        allow_lto_indexing,
        lto_map,
        # Outputs:
        libraries_to_link,
        expanded_linker_inputs,
        library_search_directories,
        rpath_roots_for_explicit_so_deps,
    )

    if lto_map:
        fail("Still have LTO objects left: %s" % lto_map)

    # Remove repetitions
    rpath_roots_for_explicit_so_deps = rpath_roots_for_explicit_so_deps.keys()

    # rpath ordering matters for performance; first add the one where most libraries are found.
    direct_runtime_library_search_directories = []
    if include_solib_dir:
        direct_runtime_library_search_directories.extend(rpath_roots)
    direct_runtime_library_search_directories.extend(rpath_roots_for_explicit_so_deps)

    transitive_runtime_library_search_directories = []
    if include_toolchain_libraries_solib_dir:
        transitive_runtime_library_search_directories = [
            _collect_toolchain_runtime_library_search_directories(
                cc_toolchain,
                output,
                potential_solib_parents,
                need_toolchain_libraries_rpath,
                toolchain_libraries_solib_dir,
                is_native_deps,
                workspace_name,
            ),
        ]
    all_runtime_library_search_directories = depset(
        order = "topological",
        direct = direct_runtime_library_search_directories,
        transitive = transitive_runtime_library_search_directories,
    )

    return struct(
        libraries_to_link = libraries_to_link,
        expanded_linker_inputs = expanded_linker_inputs,
        library_search_directories = depset(library_search_directories),
        all_runtime_library_search_directories = all_runtime_library_search_directories,
    )

def _add_linker_inputs(
        linker_inputs,
        cc_toolchain,
        feature_configuration,
        need_whole_archive,
        solib_dir,
        toolchain_libraries_solib_dir,
        rpath_roots,
        allow_lto_indexing,
        lto_map,
        # Outputs:
        libraries_to_link,
        expanded_linker_inputs,
        library_search_directories,
        rpath_roots_for_explicit_so_deps):
    """
    Goes over all linker_inputs transforming them and collecting rpath_roots.

    Args:
        linker_inputs: (list[LegacyLinkerInput]) Linker inputs
        cc_toolchain: cc_toolchain providing some extra information in the conversion.
        feature_configuration: Feature configuration to be queried.
        need_whole_archive: (bool) Whether we need to use whole-archive for the link.
        solib_dir: (str) solib directory.
        toolchain_libraries_solib_dir: (str) Directory where toolchain stores language-runtime libraries (libstdc++, libc++ ...).
        rpath_roots: (list[str]) rpath roots (for example solib_dir)
        allow_lto_indexing: bool) Is LTO indexing being done.
        lto_map: (dict[File, File]) Map from bitcode files to object files. Used to replace all linker inputs.
        libraries_to_link: (list[LibraryToLinkValue]) Output collecting libraries to link.
        expanded_linker_inputs: (list[LegacyLinkerInput]) Output collecting expanded linker inputs.
        library_search_directories: (list[str]) Output collecting library search directories.
        rpath_roots_for_explicit_so_deps: (dict[str, None]) Output collecting rpaths.

    Returns:
      (include_solib_dir: bool, include_toolchain_libraries_solib_dir: bool)
    """

    include_solib_dir, include_toolchain_libraries_solib_dir = False, False
    linked_libraries_paths = {}  # :dict[str, str]

    # TODO(b/331164666): Remove CppHelper.getArchiveType
    use_start_end_lib = (cc_toolchain._cpp_configuration.start_end_lib() and
                         feature_configuration.is_enabled("supports_start_end_lib"))

    for input in linker_inputs:
        if (input.artifact_category in
            [artifact_category.DYNAMIC_LIBRARY, artifact_category.INTERFACE_LIBRARY]):
            original_lib_dir = input.original_file.dirname
            library_identifier = input.library_identifier
            previous_lib_dir = linked_libraries_paths.get(library_identifier, None)

            if not previous_lib_dir:
                linked_libraries_paths[library_identifier] = original_lib_dir
            elif previous_lib_dir != original_lib_dir:
                fail(("You are trying to link the same dynamic library %s built in a different" +
                      " configuration. Previously registered instance had path %s, current one" +
                      " has path %s") %
                     (library_identifier, previous_lib_dir, original_lib_dir))

            lib_dir = input.file.dirname

            # When COPY_DYNAMIC_LIBRARIES_TO_BINARY is enabled, dynamic libraries are not symlinked
            # under solib_dir, so don't check it and don't include solib_dir.
            if not feature_configuration.is_enabled("copy_dynamic_libraries_to_binary"):
                # The first fragment is bazel-out, and the second may contain a configuration mnemonic.
                # We should always add the default solib dir because that's where libraries will be found
                # e.g., in remote execution, so we ignore the first two fragments.
                if lib_dir.split("/")[2:] == solib_dir.split("/")[2:]:
                    include_solib_dir = True
                if lib_dir == toolchain_libraries_solib_dir:
                    include_toolchain_libraries_solib_dir = True

            _add_dynamic_input_link_options(
                input,
                feature_configuration,
                solib_dir,
                toolchain_libraries_solib_dir,
                rpath_roots,
                # Outputs:
                libraries_to_link,
                expanded_linker_inputs,
                library_search_directories,
                rpath_roots_for_explicit_so_deps,
            )
        else:
            _add_static_input_link_options(
                input,
                feature_configuration,
                use_start_end_lib,
                need_whole_archive,
                lto_map,
                allow_lto_indexing,
                # Outputs:
                libraries_to_link,
                expanded_linker_inputs,
            )

    return include_solib_dir, include_toolchain_libraries_solib_dir

def _add_dynamic_input_link_options(
        input,
        feature_configuration,
        solib_dir,
        toolchain_libraries_solib_dir,
        rpath_roots,

        # Outputs:
        libraries_to_link,
        expanded_linker_inputs,
        library_search_directories,
        rpath_roots_for_explicit_so_deps):
    """Processes dynamic and interface libraries.

    The LegacyLinkerInput is always expanded (added to expanded_linker_inputs).

    When library is not in solib_dir or toolchain_libraries_solib_dir, a relative path from
    the solib_dir to the library is added to `rpath_roots_for_explicit_so_deps` (for each rpath_root).

    Path to the library is added to `library_search_directories`.

    One of three flavours of LibraryToLinkValue are appended to libraries_to_link:
    - for_dynamic_library
    - for_versioned_dynamic_library
    - for_interface_library

    Args:
        input: (LegacyLinkerInput) Linker input
        feature_configuration: Feature configuration to be queried.
        solib_dir: (str) solib directory.
        toolchain_libraries_solib_dir: (list[str])
        rpath_roots: (list[str]) rpath roots (for example solib_dir)
        libraries_to_link:  (list[LibraryToLinkValue]) Output collecting libraries to link.
        expanded_linker_inputs:  (list[LegacyLinkerInput]) Output collecting expanded linker inputs.
        library_search_directories: (list[str]) Output collecting library search directories.
        rpath_roots_for_explicit_so_deps: (list[str, None]) Output collecting rpaths.

    Returns:
        None
    """
    artifact_cat = input.artifact_category
    if artifact_cat not in [artifact_category.DYNAMIC_LIBRARY, artifact_category.INTERFACE_LIBRARY]:
        fail("Bad artifact category " + artifact_cat)

    expanded_linker_inputs.append(input)

    if (feature_configuration.is_enabled("targets_windows") and
        feature_configuration.is_enabled("supports_interface_shared_libraries")):
        # On Windows, dynamic library (dll) cannot be linked directly when using toolchains that
        # support interface library (eg. MSVC). If the user is doing so, it is only to be referenced
        # in other places (such as copy_dynamic_libraries_to_binary); skip adding it.
        if is_shared_library(input.file):
            return

    input_file = input.file
    lib_dir = input_file.dirname
    if lib_dir != solib_dir and (not toolchain_libraries_solib_dir or toolchain_libraries_solib_dir != lib_dir):
        # TODO(b/338618120): the code should be optimized to first get unique library_search_directories and
        # then compute relative paths, i.e. rpath_roots_for_explicit_so_deps
        # TODO(b/331164666): this is a duplication of _get_relative function implemented below
        dotdots = ""
        common_parent = solib_dir
        for seg in reversed(common_parent.split("/")):
            if paths.starts_with(lib_dir, common_parent):
                break
            dotdots += "../"
            common_parent = common_parent[:-len(seg) - 1]

        #  When all dynamic deps are built in transitioned configurations, the default solib dir is
        #  not created. While resolving paths, the dynamic linker stops at the first directory that
        #  does not exist, even when followed by "../". We thus have to normalize the relative path.
        for rpath_root in rpath_roots:
            normalized_path_to_root = paths.normalize(rpath_root + dotdots + paths.relativize(lib_dir, common_parent))
            rpath_roots_for_explicit_so_deps[normalized_path_to_root] = None

        # Unless running locally, libraries will be available under the root relative path, so we
        # should add that to the rpath as well.
        if input_file.short_path.startswith("_solib_"):
            artifact_path_under_solib = input_file.short_path.split("/")[1]
            for rpath_root in rpath_roots:
                rpath_roots_for_explicit_so_deps[rpath_root + artifact_path_under_solib] = None

    library_search_directories.append(lib_dir)

    name = input_file.basename

    # Use the normal shared library resolution rules if possible, otherwise treat as a versioned
    # library that must use the exact name. e.g.:
    # -lfoo -> libfoo.so
    # -l:foo -> foo.so
    # -l:libfoo.so.1 -> libfoo.so.1
    has_compatible_name = (
        name.startswith("lib") or
        (not name.endswith(".so") and not name.endswith(".dylib") and not name.endswith(".dll"))
    )
    if is_shared_library(input_file) and has_compatible_name:
        lib_name = name.removeprefix("lib").removesuffix(".so").removesuffix(".dylib").removesuffix(".dll")
        libraries_to_link.append(cc_internal.for_dynamic_library(lib_name))
    elif is_shared_library(input_file) or is_versioned_shared_library(input_file):
        libraries_to_link.append(cc_internal.for_versioned_dynamic_library(name, input_file.path))
    else:
        # Interface shared objects have a non-standard extension
        # that the linker won't be able to find.  So use the
        # filename directly rather than a -l option.  Since the
        #  library has an SONAME attribute, this will work fine.
        libraries_to_link.append(cc_internal.for_interface_library(input_file.path))

def _add_static_input_link_options(
        input,
        feature_configuration,
        use_start_end_lib,
        need_whole_archive,
        lto_map,
        allow_lto_indexing,
        # Outputs:
        libraries_to_link,
        expanded_linker_inputs):
    """Processes static libraries and object files.

    When start-end library is used, object files in static libraries are unpacked into following
    flavours of LibraryToLinkValues:
    - for_object_file
    - for_object_file_group

    Similarly object file inputs are repacked into the above two flavours, specially handled
    object file tree artifacts.

    When start-end library isn't used, static libraries are converted to for_static_library
    LibraryToLinkValue.

    Either whole library or library's object files LegacyLinkerInputs are expanded
    (added to expanded_linker_inputs).

    Args:
        input: (LegacyLinkerInput) Linker input
        feature_configuration: Feature configuration to be queried.
        use_start_end_lib: (bool) Whether to use start end lib.
        need_whole_archive: (bool) Whether we need to use whole-archive for the link.
        lto_map: (dict[File, File]) Map from bitcode files to object files. Used to replace all linker inputs.
        allow_lto_indexing: bool) Is LTO indexing being done.
        libraries_to_link:  (list[LibraryToLinkValue]) Output collecting libraries to link.
        expanded_linker_inputs:  (list[LegacyLinkerInput]) Output collecting expanded linker inputs.
    """
    artifact_cat = input.artifact_category
    if artifact_cat not in [
        artifact_category.OBJECT_FILE,
        artifact_category.STATIC_LIBRARY,
        artifact_category.ALWAYSLINK_STATIC_LIBRARY,
    ]:
        fail("Bad artifact category " + artifact_cat)

    # input.disable_whole_archive should only be true for libstdc++/libc++ etc.
    input_is_whole_archive = not input.disable_whole_archive and (
        artifact_cat == artifact_category.ALWAYSLINK_STATIC_LIBRARY or need_whole_archive
    )

    if feature_configuration.is_enabled("use_lto_native_object_directory"):
        shared_non_lto_obj_root_prefix = "shared.nonlto-obj"
    else:
        shared_non_lto_obj_root_prefix = "shared.nonlto"

    # If we had any LTO artifacts, lto_map whould be non-null. In that case,
    # we should have created a thinlto_param_file which the LTO indexing
    # step will populate with the exec paths that correspond to the LTO
    # artifacts that the linker decided to include based on symbol resolution.
    # Those files will be included directly in the link (and not wrapped
    # in --start-lib/--end-lib) to ensure consistency between the two link
    # steps.

    # start-lib/end-lib library: adds its input object files.
    # TODO(bazel-team): Figure out if PicArchives are actually used. For it to be used, both
    # linkingStatically and linkShared must me true, we must be in opt mode and cpu has to be k8.
    if (use_start_end_lib and
        artifact_cat in [artifact_category.STATIC_LIBRARY, artifact_category.ALWAYSLINK_STATIC_LIBRARY] and
        input.object_files != None):
        archive_members = input.object_files
        non_lto_archive_members = []
        for archive_member in archive_members:
            # When ltoMap is non-empty the backend artifact may be missing due to libraries that
            # list .o files explicitly, or generate .o files from assembler.
            member = lto_map.pop(archive_member, archive_member)

            # Object files are always (LTO or no LTO) expanded (input to the action).
            expanded_linker_inputs.append(cc_internal.simple_linker_input(member))

            if (member != archive_member and
                _handled_by_lto_indexing(member, allow_lto_indexing, shared_non_lto_obj_root_prefix)):
                # The LTO artifacts that should be included in the final link
                # are listed in the thinltoParamFile, generated by the LTO indexing.
                continue

            # No LTO indexing step, so use the LTO backend's generated artifact directly
            # instead of the bitcode object.
            non_lto_archive_members.append(member)

        if input_is_whole_archive:
            for member in non_lto_archive_members:
                if member.is_directory:
                    # TODO(b/78189629): This object filegroup is expanded at action time but wrapped
                    # with --start/--end-lib. There's currently no way to force these objects to be
                    # linked in.
                    libraries_to_link.append(cc_internal.for_object_file_group([member], is_whole_archive = True))
                else:
                    # TODO(b/78189629): These each need to be their own LibraryToLinkValue so they're
                    # not wrapped in --start/--end-lib (which lets the linker leave out objects with
                    # unreferenced code).
                    libraries_to_link.append(cc_internal.for_object_file(member.path, is_whole_archive = True))
        elif non_lto_archive_members:
            libraries_to_link.append(cc_internal.for_object_file_group(non_lto_archive_members, is_whole_archive = False))
    else:
        input_file = lto_map.pop(input.file, input.file)
        if (input_file != input.file and
            _handled_by_lto_indexing(input_file, allow_lto_indexing, shared_non_lto_obj_root_prefix)):
            # The LTO artifacts that should be included in the final link
            # are listed in the thinltoParamFile, generated by the LTO indexing.

            # Even if this object file is being skipped for exposure as a build variable, it's
            # still an input to this action.
            # TODO(b/331164666): simplify like in then branch above - expand the original input,
            #  instead creating a new one
            expanded_linker_inputs.append(cc_internal.simple_linker_input(input_file))
            return

        # No LTO indexing step, so use the LTO backend's generated artifact directly
        # instead of the bitcode object.
        if artifact_cat == artifact_category.OBJECT_FILE:
            if input.file.is_directory:
                libraries_to_link.append(cc_internal.for_object_file_group([input_file], input_is_whole_archive))
            else:
                libraries_to_link.append(cc_internal.for_object_file(input_file.path, input_is_whole_archive))
            if not input.is_linkstamp:
                expanded_linker_inputs.append(input)
        else:
            libraries_to_link.append(cc_internal.for_static_library(input_file.path, input_is_whole_archive))
            expanded_linker_inputs.append(input)

def _handled_by_lto_indexing(file, allow_lto_indexing, shared_non_lto_obj_root_prefix):
    """Returns true if this artifact is produced from a bitcode file.

    Returns true if this artifact is produced from a bitcode file that will be input to the LTO
    indexing step, in which case that step will add it to the generated thinlto_param_file for
    inclusion in the final link step if the linker decides to include it.

    Args:
      file: (File) an artifact produced by an LTO backend.
      allow_lto_indexing: (bool)
      shared_non_lto_obj_root_prefix: (str) the root prefix of where the shared non lto obj are stored
    """

    # If no LTO indexing is allowed for this link, then none are handled by LTO indexing.
    # Otherwise, this may be from a linkstatic library that we decided not to include in
    # LTO indexing because we are linking a test, to improve scalability when linking many tests.
    return allow_lto_indexing and not file.short_path.startswith(shared_non_lto_obj_root_prefix)

def _collect_toolchain_runtime_library_search_directories(
        cc_toolchain,
        output,
        potential_solib_parents,
        need_toolchain_libraries_rpath,
        toolchain_libraries_solib_dir,
        is_native_deps,
        workspace_name):
    if not need_toolchain_libraries_rpath:
        return depset()

    runtime_library_search_directories = []
    toolchain_libraries_solib_name = paths.basename(toolchain_libraries_solib_dir)
    if not (is_native_deps and cc_toolchain.cc_configuration.share_native_deps()):
        for potential_exec_root in _find_toolchain_solib_parents(cc_toolchain, output, potential_solib_parents, toolchain_libraries_solib_dir, workspace_name):
            runtime_library_search_directories.append(potential_exec_root + toolchain_libraries_solib_name + "/")

    if is_native_deps:
        runtime_library_search_directories.append("../" + toolchain_libraries_solib_name + "/")
        runtime_library_search_directories.append(".")

    runtime_library_search_directories.append(toolchain_libraries_solib_name + "/")

    return depset(runtime_library_search_directories, order = "topological")

# TODO(b/338618120): converge back together _find_toolchain_solib_parents and _find_potential_solib_parents

def _find_potential_solib_parents(output, dynamic_library_solib_symlink_output, workspace_name):
    solib_parents = []
    outputs = [output]
    if dynamic_library_solib_symlink_output:
        outputs.append(dynamic_library_solib_symlink_output)

    for output in outputs:
        # The runtime location of the solib directory relative to the binary depends on four factors:
        #
        # * whether the binary is contained in the main repository or an external repository;
        # * whether the binary is executed directly or from a runfiles tree;
        # * whether the binary is staged as a symlink (sandboxed execution; local execution if the
        #   binary is in the runfiles of another target) or a regular file (remote execution) - the
        #   dynamic linker follows sandbox and runfiles symlinks into its location under the
        #   unsandboxed execroot, which thus becomes the effective $ORIGIN;
        # * whether --experimental_sibling_repository_layout is enabled or not.
        #
        # The rpaths emitted into the binary thus have to cover the following cases (assuming that
        # the binary target is located in the pkg `pkg` and has name `file`) for the directory used
        # as $ORIGIN by the dynamic linker and the directory containing the solib directories:
        #
        # 1. main, direct, symlink:
        #    $ORIGIN:    $EXECROOT/pkg
        #    solib root: $EXECROOT
        # 2. main, direct, regular file:
        #    $ORIGIN:    $EXECROOT/pkg
        #    solib root: $EXECROOT/pkg/file.runfiles/main_repo
        # 3. main, runfiles, symlink:
        #    $ORIGIN:    $EXECROOT/pkg
        #    solib root: $EXECROOT
        # 4. main, runfiles, regular file:
        #    $ORIGIN:    other_target.runfiles/main_repo/pkg
        #    solib root: other_target.runfiles/main_repo
        # 5a. external, direct, symlink:
        #    $ORIGIN:    $EXECROOT/external/other_repo/pkg
        #    solib root: $EXECROOT
        # 5b. external, direct, symlink, with --experimental_sibling_repository_layout:
        #    $ORIGIN:    $EXECROOT/../other_repo/pkg
        #    solib root: $EXECROOT/../other_repo
        # 6a. external, direct, regular file:
        #    $ORIGIN:    $EXECROOT/external/other_repo/pkg
        #    solib root: $EXECROOT/external/other_repo/pkg/file.runfiles/main_repo
        # 6b. external, direct, regular file, with --experimental_sibling_repository_layout:
        #    $ORIGIN:    $EXECROOT/../other_repo/pkg
        #    solib root: $EXECROOT/../other_repo/pkg/file.runfiles/other_repo
        # 7a. external, runfiles, symlink:
        #    $ORIGIN:    $EXECROOT/external/other_repo/pkg
        #    solib root: $EXECROOT
        # 7b. external, runfiles, symlink, with --experimental_sibling_repository_layout:
        #    $ORIGIN:    $EXECROOT/../other_repo/pkg
        #    solib root: $EXECROOT/../other_repo
        # 8a. external, runfiles, regular file:
        #    $ORIGIN:    other_target.runfiles/some_repo/pkg
        #    solib root: other_target.runfiles/main_repo
        # 8b. external, runfiles, regular file, with --experimental_sibling_repository_layout:
        #    $ORIGIN:    other_target.runfiles/some_repo/pkg
        #    solib root: other_target.runfiles/some_repo
        #
        # Cases 1, 3, 4, 5, 7, and 8b are covered by an rpath that walks up the root relative path.
        # Cases 2 and 6 covered by walking into file.runfiles/main_repo.
        # Case 8a is covered by walking up some_repo/pkg and then into main_repo.
        is_external = output.short_path.startswith("../")
        uses_legacy_repository_layout = output.short_path.startswith("../external")

        # Handles cases 1, 3, 4, 5, and 7.
        solib_parents.append("../" * (len(output.short_path.split("/")) - 1))

        # Handle cases 2 and 6.
        if is_external and not uses_legacy_repository_layout:
            # Case 6b
            solib_repository_name = output.short_path.split("/")[1]
        else:
            # Cases 2 and 6a
            solib_repository_name = workspace_name

        solib_parents.append(output.basename + ".runfiles/" + solib_repository_name + "/")
        if is_external and uses_legacy_repository_layout:
            # Handles case 8a. The runfiles path is of the form ../some_repo/pkg/file and we need to
            # walk up some_repo/pkg and then down into main_repo.
            solib_parents.append(
                "../" * (len(output.root.path.split("/")) - 2) + workspace_name + "/",
            )

    return solib_parents

def _find_toolchain_solib_parents(cc_toolchain, output, potential_solib_parents, toolchain_libraries_solib_dir, workspace_name):
    uses_legacy_repository_layout = not cc_toolchain._is_sibling_repository_layout

    # When -experimental_sibling_repository_layout is not enabled, the toolchain solib sits next to
    # the solib_<cpu> directory - so that it shares the same parents.
    if uses_legacy_repository_layout:
        return potential_solib_parents

    # When -experimental_sibling_repository_layout is enabled, the toolchain solib is located in
    # these 2 places:
    # 1. The `bin` directory of the repository where the toolchain target is declared (this is the
    # parent directory of `toolchainLibrariesSolibDir`).
    # 2. In `target.runfiles/<toolchain repo>`
    #
    # And the following factors affect what $ORIGIN is resolved to:
    # * whether the binary is contained in the main repository or an external repository;
    # * whether the binary is executed directly or from a runfiles tree;
    # * whether the binary is staged as a symlink (sandboxed execution; local execution if the
    #   binary is in the runfiles of another target) or a regular file (remote execution) - the
    #   dynamic linker follows sandbox and runfiles symlinks into its location under the
    #   unsandboxed execroot, which thus becomes the effective $ORIGIN;
    #
    # The rpaths emitted into the binary thus have to cover the following cases (assuming that
    # the binary target is located in the pkg `pkg` and has name `file`) for the directory used
    # as $ORIGIN by the dynamic linker and the directory containing the solib directories:
    # 1. main, direct, symlink:
    #    $ORIGIN:    $EXECROOT/pkg
    #    solib root: <toolchain repo bin>
    # 2. main, direct, regular file:
    #    $ORIGIN:    $EXECROOT/pkg
    #    solib root: $EXECROOT/pkg/file.runfiles/<toolchain repo>
    # 3. main, runfiles, symlink:
    #    $ORIGIN:    $EXECROOT/pkg
    #    solib root: <toolchain repo bin>
    # 4. main, runfiles, regular file:
    #    $ORIGIN:    other_target.runfiles/main_repo/pkg
    #    solib root: other_target.runfiles/<toolchain repo>
    # 5. external, direct, symlink:
    #    $ORIGIN:    $EXECROOT/../other_repo/pkg
    #    solib root: <toolchain repo bin>
    # 6. external, direct, regular file:
    #    $ORIGIN:    $EXECROOT/../other_repo/pkg
    #    solib root: $EXECROOT/../other_repo/pkg/file.runfiles/<toolchain repo>
    # 7. external, runfiles, symlink:
    #    $ORIGIN:    $EXECROOT/../other_repo/pkg
    #    solib root: <toolchain repo bin>
    # 8. external, runfiles, regular file:
    #    $ORIGIN:    other_target.runfiles/some_repo/pkg
    #    solib root: other_target.runfiles/<toolchain repo>
    #
    # For cases 1, 3, 5, 7, we need to compute the relative path from the output artifact to
    # toolchain repo's bin directory. For 2 and 6, we walk down into `file.runfiles/<toolchain
    # repo>`. For 4 and 8, we need to compute the relative path from the output runfile to
    # <toolchain repo> under runfiles.
    solib_parents = []

    # Cases 1, 3, 5, 7
    toolchain_bin_exec_path = paths.dirname(toolchain_libraries_solib_dir)
    binary_origin_exec_path = output.dirname
    solib_parents.append(
        _get_relative(binary_origin_exec_path, toolchain_bin_exec_path) + "/",
    )

    # Cases 2 and 6
    toolchain_runfiles_repo_name = _get_runfiles_repo_name(cc_toolchain._toolchain_label.repo_name, workspace_name)
    solib_parents.append(
        output.basename + ".runfiles/" + toolchain_runfiles_repo_name + "/",
    )

    # Cases 4 and 8
    binary_repo_name = _get_runfiles_repo_name(output.owner.repo_name, workspace_name)
    toolchain_bin_runfiles_path = toolchain_runfiles_repo_name
    binary_origin_runfiles_path = binary_repo_name + "/" + output.dirname[len(output.root.path):].removeprefix("/")
    solib_parents.append(
        _get_relative(binary_origin_runfiles_path, toolchain_bin_runfiles_path) + "/",
    )

    return solib_parents

def _get_runfiles_repo_name(repo_name, workspace_name):
    # TODO(b/331164666): inline
    return repo_name or workspace_name

def _get_relative(start, to):
    """
    Returns the relative path from "from" to "to".

    Example 1:
    `_get_relative("foo", "foo/bar/wiz") -> returns "bar/wiz"`

    Example 2:
    `_get_relative("foo/bar/wiz", "foo/wiz") -> returns "../../wiz"`

    The following requirements / assumptions are made: 1) paths must be both relative; 2) they
    are assumed to be relative to the same location; 3) when the `from` path starts with
    `..` prefixes, the prefix length must not exceed `..` prefixes of the `to` path.
    """
    common_parent = start
    dotdots = ""
    for seg in reversed(common_parent.split("/")):
        if paths.starts_with(to, common_parent):
            break
        dotdots += "../"
        common_parent = common_parent[:-len(seg) - 1]

    return dotdots + paths.relativize(to, common_parent)
