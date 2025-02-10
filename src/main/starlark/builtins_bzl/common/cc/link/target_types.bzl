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
"""Types of ELF files that can be created by the linker (.a, .so, .lo, executable)."""

load(":common/cc/action_names.bzl", "ACTION_NAMES")
load(":common/cc/cc_helper_internal.bzl", "artifact_category")

USE_LINKER = "linker"
USE_ARCHIVER = "archiver"

LINK_TARGET_TYPE = struct(
    # A normal static archive.
    STATIC_LIBRARY = struct(
        _name = "STATIC_LIBRARY",
        linker_or_archiver = USE_ARCHIVER,
        action_name = ACTION_NAMES.cpp_link_static_library,
        is_pic = False,
        linker_output = artifact_category.STATIC_LIBRARY,
        executable = False,
    ),

    # An objc fully linked static archive.
    OBJC_FULLY_LINKED_ARCHIVE = struct(
        _name = "OBJC_FULLY_LINKED_ARCHIVE",
        linker_or_archiver = USE_ARCHIVER,
        action_name = ACTION_NAMES.objc_fully_link,
        is_pic = False,
        linker_output = artifact_category.STATIC_LIBRARY,
        executable = False,
    ),

    # An objc executable.
    OBJC_EXECUTABLE = struct(
        _name = "OBJC_EXECUTABLE",
        linker_or_archiver = USE_LINKER,
        action_name = ACTION_NAMES.objc_executable,
        is_pic = False,
        linker_output = artifact_category.EXECUTABLE,
        executable = True,
    ),

    #A  static archive with .pic.o object files (compiled with -fPIC).
    PIC_STATIC_LIBRARY = struct(
        _name = "PIC_STATIC_LIBRARY",
        linker_or_archiver = USE_ARCHIVER,
        action_name = ACTION_NAMES.cpp_link_static_library,
        is_pic = True,
        linker_output = artifact_category.STATIC_LIBRARY,
        executable = False,
    ),

    #  An interface dynamic library.
    INTERFACE_DYNAMIC_LIBRARY = struct(
        _name = "INTERFACE_DYNAMIC_LIBRARY",
        linker_or_archiver = USE_LINKER,
        action_name = ACTION_NAMES.cpp_link_dynamic_library,
        is_pic = False,  # Actually PIC but it's not indicated in the file name
        linker_output = artifact_category.INTERFACE_LIBRARY,
        executable = False,
    ),

    # A dynamic library built from cc_library srcs.
    NODEPS_DYNAMIC_LIBRARY = struct(
        _name = "NODEPS_DYNAMIC_LIBRARY",
        linker_or_archiver = USE_LINKER,
        action_name = ACTION_NAMES.cpp_link_nodeps_dynamic_library,
        is_pic = False,  #  Actually PIC but it's not indicated in the file name
        linker_output = artifact_category.DYNAMIC_LIBRARY,
        executable = False,
    ),

    # A transitive dynamic library used for distribution.
    DYNAMIC_LIBRARY = struct(
        _name = "DYNAMIC_LIBRARY",
        linker_or_archiver = USE_LINKER,
        action_name = ACTION_NAMES.cpp_link_dynamic_library,
        is_pic = False,  #  Actually PIC but it's not indicated in the file name
        linker_output = artifact_category.DYNAMIC_LIBRARY,
        executable = False,
    ),

    # A static archive without removal of unused object files.
    ALWAYS_LINK_STATIC_LIBRARY = struct(
        _name = "ALWAYS_LINK_STATIC_LIBRARY",
        linker_or_archiver = USE_ARCHIVER,
        action_name = ACTION_NAMES.cpp_link_static_library,
        is_pic = False,
        linker_output = artifact_category.ALWAYSLINK_STATIC_LIBRARY,
        executable = False,
    ),

    # A PIC static archive without removal of unused object files.
    ALWAYS_LINK_PIC_STATIC_LIBRARY = struct(
        _name = "ALWAYS_LINK_PIC_STATIC_LIBRARY",
        linker_or_archiver = USE_ARCHIVER,
        action_name = ACTION_NAMES.cpp_link_static_library,
        is_pic = True,
        linker_output = artifact_category.ALWAYSLINK_STATIC_LIBRARY,
        executable = False,
    ),

    # An executable binary.
    EXECUTABLE = struct(
        _name = "EXECUTABLE",
        linker_or_archiver = USE_LINKER,
        action_name = ACTION_NAMES.cpp_link_executable,
        is_pic = False,  #  is_pic is not indicate in the file name
        linker_output = artifact_category.EXECUTABLE,
        executable = True,
    ),
)

def is_dynamic_library(link_target):
    """Returns true iff this link type is a dynamic library or transitive dynamic library."""
    return link_target in [LINK_TARGET_TYPE.NODEPS_DYNAMIC_LIBRARY, LINK_TARGET_TYPE.DYNAMIC_LIBRARY]
