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
"""Protocol Buffer"""

# Build Encyclopedia entry point for Proto rules implemented in protobuf repository

load("@com_google_protobuf//bazel:py_proto_library.bzl", "py_proto_library")
load("@com_google_protobuf//bazel/private:bazel_cc_proto_library.bzl", "cc_proto_library")  # buildifier: disable=bzl-visibility
load("@com_google_protobuf//bazel/private:bazel_java_proto_library_rule.bzl", "java_proto_library")  # buildifier: disable=bzl-visibility
load("@com_google_protobuf//bazel/private:bazel_proto_library_rule.bzl", "proto_library")  # buildifier: disable=bzl-visibility
load("@com_google_protobuf//bazel/private:java_lite_proto_library.bzl", "java_lite_proto_library")  # buildifier: disable=bzl-visibility
load("@com_google_protobuf//bazel/private:proto_lang_toolchain_rule.bzl", "proto_lang_toolchain")  # buildifier: disable=bzl-visibility
load("@com_google_protobuf//bazel/private:proto_toolchain_rule.bzl", "proto_toolchain")  # buildifier: disable=bzl-visibility

binary_rules = struct()

library_rules = struct(
    proto_library = proto_library,
    cc_proto_library = cc_proto_library,
    java_proto_library = java_proto_library,
    java_lite_proto_library = java_lite_proto_library,
    py_proto_library = py_proto_library,
)

test_rules = struct()

other_rules = struct(
    proto_toolchain = proto_toolchain,
    proto_lang_toolchain = proto_lang_toolchain,
)
