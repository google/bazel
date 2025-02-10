load("@rules_java//java:java_library.bzl", "java_library")

# Description:
#   Zip provides a library for reading and writing zip files, allowing more
#   advanced manipulation than the JDK equivalents by providing detailed zip
#   entry data and raw file access.
package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src/java_tools/singlejar:singlejar_package_group"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*.java"]) + ["BUILD"],
    visibility = ["//src:__subpackages__"],
)

# Necessary for the bootstrap compilation.
filegroup(
    name = "java-srcs",
    srcs = glob(["*.java"]),
)

java_library(
    name = "zip",
    srcs = glob(["*.java"]),
    visibility = ["//visibility:public"],
    deps = ["//third_party:jsr305"],
)
