# gRPC Bazel BUILD file.
#
# Copyright 2016 gRPC authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@rules_license//rules:license.bzl", "license")
load("//tools/distributions:distribution_rules.bzl", "distrib_cc_binary", "distrib_jar_filegroup")

package(
    default_applicable_licenses = [":license"],
    default_visibility = ["//visibility:public"],
)

licenses(["notice"])  # Apache v2

license(
    name = "license",
    package_name = "grpc-java",
    license_kinds = [
        "@rules_license//licenses/spdx:Apache-2.0",
    ],
    license_text = "LICENSE",
    package_version = "1.47",
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

# @grpc-java specifies overrides for these maven coordinates, so these are not actually
# jars, but built from source from the @grpc-java repo.
GRPC_JARS = [
    "@maven//:io_grpc_grpc_api",
    "@maven//:io_grpc_grpc_auth",
    "@maven//:io_grpc_grpc_context",
    "@maven//:io_grpc_grpc_core",
    "@maven//:io_grpc_grpc_inprocess",
    "@maven//:io_grpc_grpc_netty",
    "@maven//:io_grpc_grpc_protobuf",
    "@maven//:io_grpc_grpc_protobuf_lite",
    "@maven//:io_grpc_grpc_stub",
    "@maven//:io_grpc_grpc_xds",
]

# TODO: wyv@ - rename this to just `grpc-java`.
java_library(
    name = "grpc-jar",
    exports = GRPC_JARS,
)

filegroup(
    name = "grpc_jars",
    srcs = GRPC_JARS,
)

distrib_cc_binary(
    name = "grpc-java-plugin",
    srcs = [
        "compiler/src/java_plugin/cpp/java_generator.cpp",
        "compiler/src/java_plugin/cpp/java_generator.h",
        "compiler/src/java_plugin/cpp/java_plugin.cpp",
    ],
    copts = ["-w"],
    enable_distributions = ["debian"],
    deps = ["//third_party/protobuf:protoc_lib"],
)
