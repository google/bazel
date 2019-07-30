"""
Copyright 2014 The Bazel Authors. All rights reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

This is a quick and dirty rule to make Bazel compile itself. Do not use.
"""

load("@rules_cc//cc:defs.bzl", "cc_library")

proto_filetype = [".proto"]

def cc_grpc_library(name, src):
    """Creates a cc_library for grpc.

    A genrule is created as well to generate protobuf headers.

    Args:
      name: used for the cc_library
      src: source input for the cc_library
    """
    basename = src[:-len(".proto")]
    protoc_label = str(Label("//third_party/protobuf:protoc"))
    protobuf_lib_label = str(Label("//third_party/protobuf"))
    cpp_plugin_label = str(Label("//third_party/grpc:cpp_plugin"))
    native.genrule(
        name = name + "_codegen",
        srcs = [src],
        tools = [protoc_label, cpp_plugin_label],
        cmd = "\\\n".join([
            "$(location " + protoc_label + ")",
            "    --plugin=protoc-gen-grpc=$(location " + cpp_plugin_label + ")",
            "    --cpp_out=lite:$(GENDIR)",
            "    --grpc_out=$(GENDIR)",
            "    $(location " + src + ")",
        ]),
        outs = [basename + ".grpc.pb.h", basename + ".grpc.pb.cc", basename + ".pb.cc", basename + ".pb.h"],
    )

    cc_library(
        name = name,
        srcs = [basename + ".grpc.pb.cc", basename + ".pb.cc"],
        hdrs = [basename + ".grpc.pb.h", basename + ".pb.h"],
        defines = ["GRPC_USE_PROTO_LITE=ON"],
        deps = [
            str(Label("//third_party/grpc:grpc++_unsecure")),
            protobuf_lib_label,
        ],
        includes = ["."],
    )
