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
    name = "allocationtracker_module",
    srcs = [
        "AllocationTrackerInstaller.java",
        "AllocationTrackerModule.java",
    ],
    deps = [
        ":allocationtracker",
        ":current_rule_tracker",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_version_info",
        "//src/main/java/com/google/devtools/build/lib/analysis:server_directories",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/eval",
        "//third_party/allocation_instrumenter",
    ],
)

java_library(
    name = "current_rule_tracker",
    srcs = ["CurrentRuleTracker.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//third_party:guava",
    ],
)

java_library(
    name = "allocationtracker",
    srcs = [
        "AllocationTracker.java",
    ],
    deps = [
        ":current_rule_tracker",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:caffeine",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party/allocation_instrumenter",
        "//third_party/pprof:profile_java_proto",
    ],
)
