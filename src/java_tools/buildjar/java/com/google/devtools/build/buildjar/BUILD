load("@rules_java//java:defs.bzl", "java_binary", "java_library")
load("//tools/build_rules:java_rules_skylark.bzl", "bootstrap_java_binary", "bootstrap_java_library")

# Description:
#   The Java library builders, which are used by Bazel to compile Java
#   source code.
package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src/java_tools/buildjar:buildjar_package_group"],
)

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
    ) + [
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:srcs",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper:srcs",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins:srcs",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/statistics:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "optionsparser",
    srcs = [
        "InvalidCommandLineException.java",
        "OptionsParser.java",
    ],
    visibility = [
        "//src/java_tools/buildjar:buildjar_package_group",
        "//src/test/java/com/google/devtools/build/lib:__subpackages__",
    ],
    deps =
        [
            ":javac_options",
            "//third_party:guava",
            "//third_party:jsr305",
        ],
)

JAVAC_OPTIONS_SRCS = [
    "javac/JavacOptions.java",
    "javac/WerrorCustomOption.java",
]

java_library(
    name = "javac",
    srcs = glob(
        ["javac/*.java"],
        exclude = JAVAC_OPTIONS_SRCS,
    ),
    add_exports = [
        "jdk.compiler/com.sun.tools.javac.api",
        "jdk.compiler/com.sun.tools.javac.code",
        "jdk.compiler/com.sun.tools.javac.comp",
        "jdk.compiler/com.sun.tools.javac.file",
        "jdk.compiler/com.sun.tools.javac.main",
        "jdk.compiler/com.sun.tools.javac.util",
    ],
    deps = [
        ":invalid_command_line_exception",
        ":javac_options",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/statistics",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

alias(
    name = "jarhelper",
    actual = "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper",
    visibility = ["//visibility:public"],
)

# Bazel's BuildJar
java_library(
    name = "buildjar",
    srcs = glob(
        [
            "*.java",
            "instrumentation/*.java",
        ],
        exclude = [
            "InvalidCommandLineException.java",
            "JarOwner.java",
            "VanillaJavaBuilder.java",
        ],
    ),
    deps = [
        ":invalid_command_line_exception",
        ":jarhelper",
        ":javac",
        ":javac_options",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins:dependency",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins:errorprone",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins:processing",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/statistics",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//third_party:error_prone",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party/java/jacoco:core",
    ],
)

java_binary(
    name = "BazelJavaBuilder",
    main_class = "com.google.devtools.build.buildjar.BazelJavaBuilder",
    runtime_deps = [":buildjar"],
)

java_library(
    name = "javac_options",
    srcs = JAVAC_OPTIONS_SRCS,
    visibility = ["//visibility:public"],
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "invalid_command_line_exception",
    srcs = ["InvalidCommandLineException.java"],
)

java_library(
    name = "JarOwner",
    srcs = ["JarOwner.java"],
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_binary(
    name = "VanillaJavaBuilder",
    main_class = "com.google.devtools.build.buildjar.VanillaJavaBuilder",
    runtime_deps = [":vanilla_java_builder"],
)

java_library(
    name = "vanilla_java_builder",
    srcs = ["VanillaJavaBuilder.java"],
    deps = [
        ":jarhelper",
        ":javac_options",
        ":optionsparser",
        "//src/main/protobuf:deps_java_proto",
        "//src/main/protobuf:java_compilation_java_proto",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party:guava",
    ],
)

#
## Bootstrapping using Starlark rules
#

bootstrap_java_library(
    name = "starlark-deps",
    srcs = ["//:bootstrap-derived-java-srcs"],
    jars = [
        "//:bootstrap-derived-java-jars",
        "//third_party:bootstrap_guava_and_error_prone-jars",
        "//third_party:jsr305-jars",
        "//third_party/java/jacoco:core-jars",
        "//third_party:tomcat_annotations_api-jars",
    ],
    neverlink_jars = ["//third_party:auto_value-jars"],
    tags = ["manual"],
)

bootstrap_java_library(
    name = "bootstrap_invalid_command_line_exception",
    srcs = ["InvalidCommandLineException.java"],
    tags = ["manual"],
)

bootstrap_java_library(
    name = "bootstrap_JarOwner",
    srcs = ["JarOwner.java"],
    jars = [
        "//third_party:jsr305-jars",
        "//third_party:auto_value-jars",
        "//third_party:bootstrap_guava_and_error_prone-jars",
    ],
    tags = ["manual"],
)

bootstrap_java_binary(
    name = "bootstrap_VanillaJavaBuilder",
    srcs = [
        "InvalidCommandLineException.java",
        "JarOwner.java",
        "OptionsParser.java",
        "VanillaJavaBuilder.java",
        "javac/JavacOptions.java",
        "javac/WerrorCustomOption.java",
    ],
    main_class = "com.google.devtools.build.buildjar.VanillaJavaBuilder",
    neverlink_jars = ["//third_party:auto_value-jars"],
    tags = ["manual"],
    deps = [
        ":starlark-deps",
        "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper:bootstrap_jarhelper",
    ],
)
