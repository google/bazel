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
"""Java"""

load("@rules_java//java:java_single_jar.bzl", _java_single_jar = "java_single_jar")
load("@rules_java//java/bazel/rules:bazel_java_binary.bzl", _java_binary = "java_binary")
load("@rules_java//java/bazel/rules:bazel_java_import.bzl", _java_import = "java_import")
load("@rules_java//java/bazel/rules:bazel_java_library.bzl", _java_library = "java_library")
load("@rules_java//java/bazel/rules:bazel_java_plugin.bzl", _java_plugin = "java_plugin")
load("@rules_java//java/bazel/rules:bazel_java_test.bzl", _java_test = "java_test")
load("@rules_java//java/common/rules:java_package_configuration.bzl", _java_package_configuration = "java_package_configuration")
load("@rules_java//java/common/rules:java_runtime.bzl", _java_runtime = "java_runtime")
load("@rules_java//java/common/rules:java_toolchain.bzl", _java_toolchain = "java_toolchain")

# Build Encyclopedia entry point for Java rules implemented in Starlark

binary_rules = struct(
    java_binary = _java_binary,
)

library_rules = struct(
    java_import = _java_import,
    java_library = _java_library,
)

test_rules = struct(
    java_test = _java_test,
)

other_rules = struct(
    java_package_configuration = _java_package_configuration,
    java_plugin = _java_plugin,
    java_runtime = _java_runtime,
    java_toolchain = _java_toolchain,
    java_single_jar = _java_single_jar,
)
