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
"""Helper functions converting LibraryToLink-s to LegacyLinkerInput-s."""

load(":common/cc/cc_helper_internal.bzl", "artifact_category")

cc_internal = _builtins.internal.cc_internal

def convert_library_to_link_list_to_linker_input_list(linker_inputs, static_mode, for_dynamic_library, supports_dynamic_linker):
    """
    Converts LibraryToLink-s from CcLinkingContext-s to LegacyLinkerInput-s.

    This code is used for creating/linking a dynamic library or an executable, where all transitive
    libraries are input to the linker. Linking mode can be either static or dynamic.

    First the function flattens transitive CcLinkingContext-s in a topological/linking orders, then
    it flattens all LibraryToLink-s in them.

    For each LibraryToLink it creates one of the best suited LegacyLinkerInputs (static pic/nopic,
    interface, dynamic), prioritising the flavour based on the given parameters.

    Further on, LegacyLinkerInputs are converted to LibraryToLinkValues and passed as link build
    variables to generated the command line.

    Args:
      linker_inputs: (list[LinkerInput]) Linker inputs from dependencies.
      static_mode: (bool) True for `static`, False for `dynamic` linking mode.
      for_dynamic_library: (bool) True when creating a library. False for executable.
      supports_dynamic_linker: (bool) True when C++ toolchain supports_dynamic_linker. That is,
        toolchain can produce binaries that load shared libraries at runtime.
    Returns:
      (list[LibraryInput]) The selected libraries. LibraryInputs are subclass of LegacyLinkerInputs.
      The contain the information about libraries main file as well as it's object files. Only one
      of those is used in the link.
    """
    # TODO(b/338618120): This whole function shouldn't really exist. Eventually the processing
    # should go directly from LibraryToLink into LibraryToLinkValue. Most likely the conversion will
    # need to move into LibrariesToLinkCollector to achieve that.
    #
    # Some smaller goals: Reorder the if statements, to follow 4 possible distinct returned values.
    # Rewrite LegacyLinkerInputs to Starlark (no blockers). This changes need to be done with
    # performance in mind. Current implementation might already need improvements before the
    # Starlark cc_common.link may be turned on, because it's converting between Java and Starlark
    # types a lot and that might cause more garbage. But the garbage also can't be completely
    # removed until both LibraryToLink and LibraryToLinkValues are in Starlark.

    # This ordering of LinkerInputs and Librar(-ies)ToLink is really sensitive, changes result in
    # subtle breakages.
    libraries_to_link = depset(
        [lib for linker_input in linker_inputs for lib in linker_input.libraries],
        order = "topological",
    )

    return cc_internal.convert_library_to_link_list_to_linker_input_list(libraries_to_link, static_mode, for_dynamic_library, supports_dynamic_linker)

# A pure Starlark implementation of convert_library_to_link_list_to_linker_input_list
# at the moment unused, becuase it causes a regression
def _convert_library_to_link_list_to_linker_input_list(linker_inputs, static_mode, for_dynamic_library, supports_dynamic_linker):
    libraries_to_link = depset(
        [lib for linker_input in linker_inputs for lib in linker_input.libraries],
        order = "topological",
    )

    library_inputs = []

    for library_to_link in libraries_to_link.to_list():
        static_library_input = None
        if library_to_link.static_library:
            static_library_input = _static_library_input(library_to_link)

        pic_static_library_input = None
        if library_to_link.pic_static_library:
            pic_static_library_input = _pic_static_library_input(library_to_link)

        library_input_to_use = None
        if static_mode:
            if for_dynamic_library:
                if pic_static_library_input:
                    library_input_to_use = pic_static_library_input
                elif static_library_input:
                    library_input_to_use = static_library_input
            elif static_library_input:
                library_input_to_use = static_library_input
            elif pic_static_library_input:
                library_input_to_use = pic_static_library_input

            if not library_input_to_use:
                if library_to_link.interface_library:
                    library_input_to_use = _interface_library_input(library_to_link)
                elif library_to_link.dynamic_library:
                    library_input_to_use = _dynamic_library_input(library_to_link)

        else:
            if library_to_link.interface_library:
                library_input_to_use = _interface_library_input(library_to_link)
            elif library_to_link.dynamic_library:
                library_input_to_use = _dynamic_library_input(library_to_link)

            if not library_input_to_use or not supports_dynamic_linker:
                if for_dynamic_library:
                    if pic_static_library_input:
                        library_input_to_use = pic_static_library_input
                    elif static_library_input:
                        library_input_to_use = static_library_input
                elif static_library_input:
                    library_input_to_use = static_library_input
                elif pic_static_library_input:
                    library_input_to_use = pic_static_library_input
        if not library_input_to_use:
            fail("No flavour of library found.")  # This (should) never happen(s).
        library_inputs.append(library_input_to_use)

    # TODO(b/338618120): Deduplicate LibraryToLink, so that deduplication
    # here isn't needed.
    # The following step is necessary because of LibraryLinkerInput.equals compares
    # only on artifacts. Somtimes a duplicated library is present, once with object
    # file and once without, we need to remove one.
    library_inputs = depset(library_inputs, order = "topological").to_list()
    return library_inputs

def _static_library_input(library_to_link):
    if library_to_link.alwayslink:
        artifact_cat = artifact_category.ALWAYSLINK_STATIC_LIBRARY
    else:
        artifact_cat = artifact_category.STATIC_LIBRARY
    return cc_internal.library_linker_input(
        library_identifier = library_to_link.library_identifier(),
        artifact_category = artifact_cat,
        input = library_to_link.static_library,
        object_files = library_to_link.objects_private(),
        lto_compilation_context = library_to_link.lto_compilation_context(),
        shared_non_lto_backends = library_to_link.shared_non_lto_backends(),
        must_keep_debug = library_to_link.must_keep_debug(),
        disable_whole_archive = library_to_link.disable_whole_archive(),
    )

def _pic_static_library_input(library_to_link):
    if library_to_link.alwayslink:
        artifact_cat = artifact_category.ALWAYSLINK_STATIC_LIBRARY
    else:
        artifact_cat = artifact_category.STATIC_LIBRARY
    return cc_internal.library_linker_input(
        library_identifier = library_to_link.library_identifier(),
        artifact_category = artifact_cat,
        input = library_to_link.pic_static_library,
        object_files = library_to_link.pic_objects_private(),
        lto_compilation_context = library_to_link.pic_lto_compilation_context(),
        shared_non_lto_backends = library_to_link.pic_shared_non_lto_backends(),
        must_keep_debug = library_to_link.must_keep_debug(),
        disable_whole_archive = library_to_link.disable_whole_archive(),
    )

def _interface_library_input(library_to_link):
    if library_to_link.resolved_symlink_interface_library:
        return cc_internal.solib_linker_input(
            library_to_link.interface_library,
            library_to_link.resolved_symlink_interface_library,
            library_to_link.library_identifier(),
        )
    return cc_internal.library_linker_input(
        library_identifier = library_to_link.library_identifier(),
        artifact_category = artifact_category.INTERFACE_LIBRARY,
        input = library_to_link.interface_library,
        object_files = [],
        lto_compilation_context = None,
        shared_non_lto_backends = {},
        must_keep_debug = library_to_link.must_keep_debug(),
        disable_whole_archive = library_to_link.disable_whole_archive(),
    )

def _dynamic_library_input(library_to_link):
    if library_to_link.resolved_symlink_dynamic_library:
        return cc_internal.solib_linker_input(
            library_to_link.dynamic_library,
            library_to_link.resolved_symlink_dynamic_library,
            library_to_link.library_identifier(),
        )
    return cc_internal.library_linker_input(
        library_identifier = library_to_link.library_identifier(),
        artifact_category = artifact_category.DYNAMIC_LIBRARY,
        input = library_to_link.dynamic_library,
        object_files = [],
        lto_compilation_context = None,
        shared_non_lto_backends = {},
        must_keep_debug = library_to_link.must_keep_debug(),
        disable_whole_archive = library_to_link.disable_whole_archive(),
    )
