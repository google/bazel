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

"""Exported builtins symbols that are specific to OSS Bazel."""

load("@_builtins//:common/python/py_internal.bzl", "py_internal")

exported_toplevels = {
    "py_internal": py_internal,
    "proto_common_do_not_use": struct(INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION = _builtins.toplevel.proto_common_do_not_use.incompatible_enable_proto_toolchain_resolution()),
}
exported_rules = {}
exported_to_java = {}
