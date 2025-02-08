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
#
# Build Encyclopedia entry point for Python rules implemented in Starlark in Bazel's @_builtins
"""Shell"""

load("@rules_shell//shell/private:sh_binary.bzl", "sh_binary")  # buildifier: disable=bzl-visibility
load("@rules_shell//shell/private:sh_library.bzl", "sh_library")  # buildifier: disable=bzl-visibility
load("@rules_shell//shell/private:sh_test.bzl", "sh_test")  # buildifier: disable=bzl-visibility

binary_rules = struct(
    sh_binary = sh_binary,
)

library_rules = struct(
    sh_library = sh_library,
)

test_rules = struct(
    sh_test = sh_test,
)

other_rules = struct()
