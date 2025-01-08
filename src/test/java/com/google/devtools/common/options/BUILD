load("@rules_java//java:defs.bzl", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/test/java/com/google/devtools/common/options/processor:srcs",
        "//src/test/java/com/google/devtools/common/options/testing:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "testutils",
    testonly = 1,
    srcs = [
        "InvocationPolicyEnforcerTestBase.java",
        "TestOptions.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/com/google/devtools/common/options:invocation_policy",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
    ],
)

java_test(
    name = "AllTests",
    srcs = glob(
        ["*.java"],
        exclude = [
            "TestOptions.java",
            "InvocationPolicyEnforcerTestBase.java",
        ],
    ),
    deps = [
        ":testutils",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/util:classpath",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/com/google/devtools/common/options:invocation_policy",
        "//src/main/java/com/google/devtools/common/options/testing",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//src/main/protobuf:option_filters_java_proto",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestSuite",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:jimfs",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:mockito",
        "//third_party:truth",
        "@maven//:com_google_testparameterinjector_test_parameter_injector",
    ],
)
