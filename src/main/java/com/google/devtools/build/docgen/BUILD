load("@rules_java//java:defs.bzl", "java_binary", "java_library")

# Description:
#   Documentation generator for Bazel
package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/docgen/annot:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "docgen_javalib",
    srcs = glob(["**/*.java"]),
    resources = [":template_files"],
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel:main",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/docgen/annot",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/starlarkdocextract:labelrenderer",
        "//src/main/java/com/google/devtools/build/lib/util:classpath",
        "//src/main/java/com/google/devtools/build/lib/util:filetype",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:builtin_java_proto",
        "//src/main/protobuf:stardoc_output_java_proto",
        "//third_party:apache_velocity",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:gson",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_binary(
    name = "docgen_bin",
    srcs = ["BuildEncyclopediaGenerator.java"],
    main_class = "com.google.devtools.build.docgen.BuildEncyclopediaGenerator",
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel/rules",
    ],
    deps = [
        ":docgen_javalib",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_binary(
    name = "skydoc_bin",
    srcs = ["StarlarkDocumentationGenerator.java"],
    main_class = "com.google.devtools.build.docgen.StarlarkDocumentationGenerator",
    runtime_deps = ["//src/main/java/com/google/devtools/build/lib/bazel/repository"],
    deps = [":docgen_javalib"],
)

java_binary(
    name = "api_exporter",
    srcs = ["ApiExporter.java"],
    main_class = "com.google.devtools.build.docgen.ApiExporter",
    runtime_deps = [
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/net/starlark/java/syntax",
    ],
    deps = [
        ":docgen_javalib",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:builtin_java_proto",
        "//third_party:guava",
    ],
)

filegroup(
    name = "template_files",
    srcs = glob([
        "templates/**/*.html",
        "templates/**/*.vm",
    ]),
)

filegroup(
    name = "bazel_link_map",
    srcs = ["bazel_link_map.json"],
)
