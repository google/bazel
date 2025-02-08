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

"""Exported builtins symbols that are not specific to OSS Bazel."""

load("@_builtins//:common/cc/cc_binary.bzl", "cc_binary")
load("@_builtins//:common/cc/cc_common.bzl", "cc_common")
load("@_builtins//:common/cc/cc_compilation_helper.bzl", "cc_compilation_helper")
load("@_builtins//:common/cc/cc_helper.bzl", "cc_helper")
load("@_builtins//:common/cc/cc_import.bzl", "cc_import")
load("@_builtins//:common/cc/cc_library.bzl", "cc_library")
load("@_builtins//:common/cc/cc_shared_library.bzl", "CcSharedLibraryInfo", "cc_shared_library")
load("@_builtins//:common/cc/cc_shared_library_hint_info.bzl", "CcSharedLibraryHintInfo")
load("@_builtins//:common/cc/cc_test.bzl", "cc_test")
load("@_builtins//:common/cc/cc_toolchain.bzl", "cc_toolchain")
load("@_builtins//:common/cc/cc_toolchain_alias.bzl", "cc_toolchain_alias")
load("@_builtins//:common/cc/experimental_cc_static_library.bzl", "cc_static_library")
load("@_builtins//:common/objc/objc_import.bzl", "objc_import")
load("@_builtins//:common/objc/objc_library.bzl", "objc_library")
load("@_builtins//:common/xcode/available_xcodes.bzl", "available_xcodes")
load("@_builtins//:common/xcode/xcode_config.bzl", "xcode_config")
load("@_builtins//:common/xcode/xcode_config_alias.bzl", "xcode_config_alias")
load("@_builtins//:common/xcode/xcode_version.bzl", "xcode_version")
load(":common/cc/fdo/fdo_prefetch_hints.bzl", "fdo_prefetch_hints")
load(":common/cc/fdo/fdo_profile.bzl", "fdo_profile")
load(":common/cc/fdo/memprof_profile.bzl", "memprof_profile")
load(":common/cc/fdo/propeller_optimize.bzl", "propeller_optimize")
load(":common/objc/apple_common.bzl", "apple_common")

exported_toplevels = {
    # This dummy symbol is not part of the public API; it is only used to test
    # that builtins injection is working properly. Its built-in value is
    # "original value".
    "_builtins_dummy": "overridden value",
    "CcSharedLibraryInfo": CcSharedLibraryInfo,
    "CcSharedLibraryHintInfo": CcSharedLibraryHintInfo,
    "cc_common": cc_common,
    "apple_common": apple_common,
    "proto_common_do_not_use": struct(
        INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION =
            _builtins.toplevel.proto_common_do_not_use.incompatible_enable_proto_toolchain_resolution(),
    ),
}

# A list of Starlarkified native rules.
#
# * leading `+` means the Starlark rule is used by default, but can be overridden
#   on the Bazel command line
# * no leading symbol means the Starlark rule is used and can't be overridden
# * leading `-` means the Starlark rule exists, but is not used by default
exported_rules = {
    "cc_import": cc_import,
    "objc_import": objc_import,
    "objc_library": objc_library,
    "cc_shared_library": cc_shared_library,
    "cc_static_library": cc_static_library,
    "cc_binary": cc_binary,
    "cc_test": cc_test,
    "cc_library": cc_library,
    "cc_toolchain_alias": cc_toolchain_alias,
    "cc_toolchain": cc_toolchain,
    "fdo_prefetch_hints": fdo_prefetch_hints,
    "fdo_profile": fdo_profile,
    "memprof_profile": memprof_profile,
    "propeller_optimize": propeller_optimize,
    "xcode_version": xcode_version,
    "available_xcodes": available_xcodes,
    "xcode_config": xcode_config,
    "xcode_config_alias": xcode_config_alias,
}

# A list of Starlark functions callable from native rules implementation.
exported_to_java = {
    "init_cc_compilation_context": cc_compilation_helper.init_cc_compilation_context,
    "get_toolchain_global_make_variables": cc_helper.get_toolchain_global_make_variables,
}
