load("@rules_java//java:java_library.bzl", "java_library")

package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

java_library(
    name = "allocation_instrumenter",
    neverlink = True,
    exports = ["@maven//:com_google_code_java_allocation_instrumenter_java_allocation_instrumenter"],
)

java_library(
    name = "allocation_instrumenter_runtime",
    testonly = True,
    exports = ["@maven//:com_google_code_java_allocation_instrumenter_java_allocation_instrumenter"],
)
