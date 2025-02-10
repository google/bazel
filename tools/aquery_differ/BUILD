# Copyright 2018 The Bazel Authors. All rights reserved.
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

load("@rules_python//python:defs.bzl", "py_binary", "py_library")
load("//tools/python:private/py_test_alias.bzl", "py_test_alias")

package(default_visibility = ["//visibility:public"])

licenses(["notice"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

py_library(
    name = "aquery_differ_resolvers",
    srcs = glob(
        ["resolvers/*.py"],
    ),
)

py_binary(
    name = "aquery_differ",
    srcs = ["aquery_differ.py"],
    deps = [
        ":aquery_differ_main_lib",
    ],
)

py_library(
    name = "aquery_differ_main_lib",
    srcs = ["aquery_differ.py"],
    deps = [
        ":aquery_differ_resolvers",
        "//src/main/protobuf:analysis_v2_py_proto",
        "//third_party/py/abseil",
    ],
)

py_test_alias(
    name = "aquery_differ_test",
    srcs = ["aquery_differ_test.py"],
    deps = [
        ":aquery_differ_main_lib",
        "//src/main/protobuf:analysis_v2_py_proto",
        "//third_party/py/mock",
    ],
)
