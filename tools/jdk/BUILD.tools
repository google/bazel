package(default_visibility = ["//visibility:public"])

load("@rules_java//java:defs.bzl", "java_import")
load("@rules_python//python:defs.bzl", "py_binary", "py_test")
load(":launcher_flag_alias.bzl", "launcher_flag_alias")

exports_files([
    "BUILD.java_tools",
])

# A single binary distribution of a JDK (e.g., OpenJDK 17 for Windows arm64) provides three
# different types of toolchains from the perspective of Bazel:

# The compilation toolchain, which provides the Java runtime used to execute the Java compiler, as
# well as various helper tools and settings.
#
# Toolchains of this type typically have constraints on the execution platform so that their Java
# runtime can run the compiler, but not on the target platform as Java compilation outputs are
# platform independent.
#
# Obtain the associated JavaToolchainInfo via:
#   ctx.toolchains["@bazel_tools//tools/jdk:toolchain_type"].java
toolchain_type(name = "toolchain_type")

# The Java runtime that executable Java compilation outputs (e.g., java_binary with
# create_executable = True) will run on.
#
# Toolchains of this type typically have constraints on the target platform so that the runtime's
# native 'java' binary can be run there, but not on the execution platform as building an executable
# Java target only requires copying or symlinking the runtime, which can be done on any platform.
#
# Obtain the associated JavaRuntimeInfo via:
#   ctx.toolchains["@bazel_tools//tools/jdk:runtime_toolchain_type"].java_runtime
toolchain_type(name = "runtime_toolchain_type")

# The Java runtime to extract the bootclasspath from that is then used to compile Java sources.
#
# As the bootclasspath is platform independent, toolchains of this type may have no constraints.
# Purely as an optimization to prevent unnecessary fetches of remote runtimes for other
# architectures, toolchains of this type may have constraints on the execution platform that match
# those on the corresponding compilation toolchain.
#
# Toolchains of this type are only consumed internally by the bootclasspath rule and should not be
# accessed from Starlark.
toolchain_type(name = "bootstrap_runtime_toolchain_type")

# Aliases value of --plugins flag as a JavaPluginInfo
java_plugins_flag_alias(
    name = "java_plugins_flag_alias",
)

alias(
    name = "genclass",
    actual = "@remote_java_tools//:GenClass",
)

alias(
    name = "GenClass_deploy.jar",
    actual = "@remote_java_tools//:GenClass",
)

alias(
    name = "turbine_direct",
    actual = "@remote_java_tools//:TurbineDirect",
)

alias(
    name = "turbine_direct_binary_deploy.jar",
    actual = "@remote_java_tools//:TurbineDirect",
)

alias(
    name = "javabuilder",
    actual = "@remote_java_tools//:JavaBuilder",
)

alias(
    name = "JavaBuilder_deploy.jar",
    actual = "@remote_java_tools//:JavaBuilder",
)

alias(
    name = "vanillajavabuilder",
    actual = "@remote_java_tools//:VanillaJavaBuilder",
)

alias(
    name = "JacocoCoverageRunner",
    actual = "@remote_java_tools//:jacoco_coverage_runner",
)

alias(
    name = "JacocoCoverage",
    actual = "@remote_java_tools//:jacoco_coverage_runner",
)

java_import(
    name = "TestRunner",
    jars = ["@remote_java_tools//:Runner"],
)

alias(
    name = "TestRunner_deploy.jar",
    actual = "@remote_java_tools//:Runner",
)

alias(
    name = "proguard",
    actual = "@remote_java_tools//:proguard",
)

alias(
    name = "jre",
    actual = "@local_jdk//:jre",
)

alias(
    name = "jdk",
    actual = "@local_jdk//:jdk",
)

alias(
    name = "host_jdk",
    actual = ":remote_jdk11",
)

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]) + ["@rules_java//toolchains:bzl_srcs"],
    visibility = ["//tools:__pkg__"],
)

# TODO(cushon): Delete this alias in the fullness of time.
alias(
    name = "proguard_whitelister",
    actual = ":proguard_allowlister",
)

py_binary(
    name = "proguard_allowlister",
    srcs = [
        "proguard_allowlister.py",
    ],
)

py_test(
    name = "proguard_allowlister_test",
    srcs = ["proguard_allowlister_test.py"],
    data = ["proguard_allowlister_test_input.pgcfg"],
    deps = [
        ":proguard_allowlister",
    ],
)

#### Aliases to rules_java to keep backward-compatibility (begin) ####

TARGET_NAMES = [
    "java_runtime_alias",
    "current_host_java_runtime",
    "current_java_runtime",
    "current_java_toolchain",
    "jni",
    "jni_header",
    "jni_md_header-darwin",
    "jni_md_header-freebsd",
    "jni_md_header-linux",
    "jni_md_header-openbsd",
    "jni_md_header-windows",
    "platformclasspath",
    "remote_jdk11",
    "remotejdk_17",
    "remote_toolchain",
    "ijar",
    "singlejar",
    "toolchain",
] + [
    "toolchain_java%d" % version
    for version in (8, 9, 10, 11)
] + [
    "toolchain_java%d_definition" % version
    for version in (8, 9, 10, 11)
] + [
    "toolchain_jdk_%d" % version
    for version in (14, 15, 16, 17, 21)
] + [
     "toolchain_jdk_%d_definition" % version
     for version in (14, 15, 16, 17, 21)
]

[
    alias(
        name = name,
        actual = "@rules_java//toolchains:" + name,
    )
    for name in TARGET_NAMES
]

#### Aliases to rules_java to keep backward-compatibility (end) ####

# TODO(b/295221112): replace this with Starlark label_flag (or an alias resolving to either java_launcher or host_java_launcher flag)
launcher_flag_alias(
    name = "launcher_flag_alias",
    visibility = ["//visibility:public"],
)
