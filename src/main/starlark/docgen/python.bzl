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
#
# Build Encyclopedia entry point for Python rules implemented in Starlark in Bazel's @_builtins
"""Python"""

load("@rules_python//python/private:py_binary_rule.bzl", "py_binary")  # buildifier: disable=bzl-visibility
load("@rules_python//python/private:py_library_rule.bzl", "py_library")  # buildifier: disable=bzl-visibility
load("@rules_python//python/private:py_runtime_rule.bzl", "py_runtime")  # buildifier: disable=bzl-visibility
load("@rules_python//python/private:py_test_rule.bzl", "py_test")  # buildifier: disable=bzl-visibility

binary_rules = struct(
    py_binary = py_binary,
)

library_rules = struct(
    py_library = py_library,
)

test_rules = struct(
    py_test = py_test,
)

other_rules = struct(
    py_runtime = py_runtime,
)
