package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

# This rule is only available from Bazel 7.0.0
starlark_doc_extract(
    name = "gen_be_proto_stardoc_proto",
    src = "proto.bzl",
    deps = [
        "@com_google_protobuf//bazel:cc_proto_library_bzl",
        "@com_google_protobuf//bazel:java_lite_proto_library_bzl",
        "@com_google_protobuf//bazel:java_proto_library_bzl",
        "@com_google_protobuf//bazel:proto_library_bzl",
        "@com_google_protobuf//bazel:py_proto_library_bzl",
        "@com_google_protobuf//bazel/toolchains:proto_lang_toolchain_bzl",
        "@com_google_protobuf//bazel/toolchains:proto_toolchain_bzl",
        "@rules_cc//cc/common",
        "@rules_cc//cc/toolchains:toolchain_rules",
        "@rules_java//java/common:proguard_spec_info_bzl",
        "@rules_java//java/private:proto_support",
    ],
)

starlark_doc_extract(
    name = "gen_be_java_stardoc_proto",
    src = "java.bzl",
    deps = [
        "@com_google_protobuf//bazel/common:proto_info_bzl",
        "@rules_java//java:java_single_jar",
        "@rules_java//java/bazel/rules",
    ],
)

starlark_doc_extract(
    name = "gen_be_cpp_stardoc_proto",
    src = "cpp.bzl",
)

starlark_doc_extract(
    name = "gen_be_objc_stardoc_proto",
    src = "objc.bzl",
)

starlark_doc_extract(
    name = "gen_be_python_stardoc_proto",
    src = "python.bzl",
    deps = [
        "@com_google_protobuf//bazel:cc_proto_library_bzl",  # Because rules_python are using @rules_cc//cc:defs.bzl
        "@rules_cc//cc/common",
        "@rules_cc//cc/toolchains:toolchain_rules",
        "@rules_python//python:py_binary_bzl",
        "@rules_python//python:py_library_bzl",
        "@rules_python//python:py_runtime_bzl",
        "@rules_python//python:py_test_bzl",
    ],
)

starlark_doc_extract(
    name = "gen_be_shell_stardoc_proto",
    src = "sh.bzl",
    deps = ["@rules_shell//shell:rules_bzl"],
)
