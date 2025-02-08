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

# cmdline provides Label and target pattern parsing.
java_library(
    name = "cmdline",
    srcs = [
        "BazelCompileContext.java",
        "BazelModuleContext.java",
        "BazelModuleKey.java",
        "IgnoredSubdirectories.java",
        "Label.java",
        "LabelConstants.java",
        "LabelParser.java",
        "LabelSyntaxException.java",
        "PackageIdentifier.java",
        "RepositoryMapping.java",
        "RepositoryName.java",
        "ResolvedTargets.java",
        "SignedTargetPattern.java",
        "StarlarkThreadContext.java",
        "TargetParsingException.java",
        "TargetPattern.java",
        "TargetPatternResolver.java",
    ],
    deps = [
        ":LabelValidator",
        ":batch_callback",
        ":query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/docgen/annot",
        "//src/main/java/com/google/devtools/build/lib/actions:commandline_item",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/io:inconsistent_filesystem_exception",
        "//src/main/java/com/google/devtools/build/lib/io:process_package_directory_exception",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/skyframe:detailed_exceptions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/supplier",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:hash_codes",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:ospathpolicy",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/skyframe:skyframe-objects",
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/spelling",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:auto_value",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "parallel_visitor",
    srcs = ["ParallelVisitor.java"],
    deps = [
        ":batch_callback",
        ":query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
    ],
)

java_library(
    name = "batch_callback",
    srcs = ["BatchCallback.java"],
    deps = [
        ":query_exception_marker_interface",
        "//src/main/java/com/google/devtools/build/lib/concurrent:thread_safety",
    ],
)

java_library(
    name = "query_exception_marker_interface",
    srcs = ["QueryExceptionMarkerInterface.java"],
)

# LabelValidator provides validation of Bazel build labels.
# This is a public API.
# TODO(adonovan): unsplit the lib.cmdline Java package by moving this logic to a subpackage.
java_library(
    name = "LabelValidator",
    srcs = ["LabelValidator.java"],
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
