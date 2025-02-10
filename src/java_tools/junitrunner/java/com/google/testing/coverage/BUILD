load("@rules_java//java:defs.bzl", "java_binary", "java_library")

package(default_visibility = ["//src:__subpackages__"])

licenses(["notice"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

# Bazel custom Jacoco runner used to provide proper initialization and lcov
# report generation when using offline Jacoco instrumentation.
# This target should not be used as a dependency (except when writing tests for
# it).
#
# An implicit dependency of all "java_binary" rules.
java_binary(
    name = "JacocoCoverage",
    srcs = [
        "JacocoCoverageRunner.java",
    ],
    deps = [
        ":JacocoCoverageLib",
        ":bitfield",
        "//third_party:guava",
        "//third_party/java/jacoco:blaze-agent",
        "//third_party/java/jacoco:core",
        "//third_party/java/jacoco:report",
    ],
)

java_library(
    name = "JacocoCoverageLib",
    srcs = [
        "BranchCoverageDetail.java",
        "BranchDetailAnalyzer.java",
        "BranchExp.java",
        "ClassProbesMapper.java",
        "CovExp.java",
        "JacocoLCOVFormatter.java",
        "MethodProbesMapper.java",
        "ProbeExp.java",
    ],
    deps = [
        ":bitfield",
        "//third_party:guava",
        "//third_party/java/jacoco:blaze-agent",
        "//third_party/java/jacoco:core",
        "//third_party/java/jacoco:report",
    ],
)

java_library(
    name = "bitfield",
    srcs = [
        "BitField.java",
        "IllegalStringException.java",
    ],
)

genrule(
    name = "Jacoco_jarjar",
    srcs = [
        ":JacocoCoverage_deploy.jar",
        ":JacocoCoverage.jarjar",
    ],
    outs = ["JacocoCoverage_jarjar_deploy.jar"],
    cmd = "\n".join([
        "JARJAR=\"$$(mktemp -t bazel.XXXXXXXX)\"",
        "\"$(JAVA)\" -jar \"$(location //third_party/jarjar:jarjar_command_deploy.jar)\" process \"$(location :JacocoCoverage.jarjar)\" \"$(location :JacocoCoverage_deploy.jar)\" \"$${JARJAR}\"",
        "\"$(location //src/tools/singlejar:singlejar)\" --normalize --sources \"$${JARJAR}\" --output \"$@\"",
        "rm -fr \"$${JARJAR}\"",
    ]),
    tags = ["manual"],
    toolchains = ["@rules_java//toolchains:current_host_java_runtime"],
    tools = [
        "//src/tools/singlejar",
        "//third_party/jarjar:jarjar_command_deploy.jar",
        "@rules_java//toolchains:current_host_java_runtime",
    ],
)
