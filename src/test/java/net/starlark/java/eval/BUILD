load("@rules_java//java:defs.bzl", "java_binary", "java_test")

package(default_testonly = 1)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src/main/java/net/starlark/java:bazel"],
)

# EvalTests test the Starlark interpreter.
java_test(
    name = "EvalTests",
    srcs = [
        "EvalTests.java",  # (suite)
        "EvalUtilsTest.java",
        "EvaluationTest.java",
        "EvaluationTestCase.java",
        "Examples.java",
        "FunctionTest.java",
        "ImmutableKeyTrackingDictTest.java",
        "MethodLibraryTest.java",
        "MutabilityTest.java",
        "PrinterTest.java",
        "StarlarkAnnotationsTest.java",
        "StarlarkClassTest.java",
        "StarlarkEvaluationTest.java",
        "StarlarkFlagGuardingTest.java",
        "StarlarkListTest.java",
        "StarlarkMutableTest.java",
        "StarlarkThreadDebuggingTest.java",
        "StarlarkThreadTest.java",
    ],
    jvm_flags = [
        "-Dfile.encoding=UTF8",
        # StarlarkListTest.concat_failsCleanlyOnOverflow() needs at least 4GB of max heap
        "-Xmx4096m",
    ],
    deps = [
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
        "//third_party:guava-testlib",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "ScriptTest_lib",
    srcs = ["ScriptTest.java"],
    data = glob(["testdata/*.star"]),
    deps = [
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/lib/json",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
    ],
)

# Script-based tests of the Starlark interpreter.
java_test(
    name = "ScriptTest",
    use_testrunner = False,
    runtime_deps = [
        ":ScriptTest_lib",
    ],
)

java_test(
    name = "ScriptTest_Latin1",
    jvm_flags = ["-Dnet.starlark.java.eval.ScriptTest.utf8ByteStrings=true"],
    main_class = "net.starlark.java.eval.ScriptTest",
    use_testrunner = False,
    runtime_deps = [
        ":ScriptTest_lib",
    ],
)

# Script-based benchmarks of the Starlark interpreter.
java_binary(
    name = "Benchmarks",
    srcs = ["Benchmarks.java"],
    data = glob(["testdata/bench_*.star"]),
    jvm_flags = ["-Dfile.encoding=UTF8"],
    deps = [
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/eval",
        "//src/main/java/net/starlark/java/lib/json",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:guava",
    ],
)
