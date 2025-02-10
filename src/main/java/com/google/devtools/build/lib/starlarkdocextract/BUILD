load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "labelrenderer",
    srcs = ["LabelRenderer.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/net/starlark/java/eval",
    ],
)

java_library(
    name = "extractionexception",
    srcs = ["ExtractionException.java"],
)

java_library(
    name = "starlarkfunctioninfoextractor",
    srcs = ["StarlarkFunctionInfoExtractor.java"],
    deps = [
        ":extractionexception",
        ":labelrenderer",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//src/main/protobuf:stardoc_output_java_proto",
        "//src/tools/starlark/java/com/google/devtools/starlark/common",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "ruleinfoextractor",
    srcs = [
        "AttributeInfoExtractor.java",
        "ExtractorContext.java",
        "ProviderNameGroupExtractor.java",
        "RuleInfoExtractor.java",
    ],
    deps = [
        ":labelrenderer",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:stardoc_output_java_proto",
        "//third_party:auto_value",
        "//third_party:guava",
    ],
)

java_library(
    name = "moduleinfoextractor",
    srcs = [
        "ModuleInfoExtractor.java",
    ],
    deps = [
        ":extractionexception",
        ":labelrenderer",
        ":ruleinfoextractor",
        ":starlarkfunctioninfoextractor",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:module_extension",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/starlark",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/util:string_encoding",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/protobuf:stardoc_output_java_proto",
        "//third_party:guava",
    ],
)
