package(
    default_visibility = [
        ":__subpackages__",
        "//tools/test:__pkg__",
    ],
)

# Generate list of all srcs via:
# bazel query 'filter("srcs", kind("filegroup rule", //src/test/java/com/google/devtools/build/lib/...))' | sort | sed -e "s/^/\"/" | sed -e "s/$/\",/" | fgrep -v "build/lib:srcs"
filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/test/java/com/google/devtools/build/lib/actions:srcs",
        "//src/test/java/com/google/devtools/build/lib/analysis:srcs",
        "//src/test/java/com/google/devtools/build/lib/authandtls:srcs",
        "//src/test/java/com/google/devtools/build/lib/bazel:srcs",
        "//src/test/java/com/google/devtools/build/lib/bazel/google:srcs",
        "//src/test/java/com/google/devtools/build/lib/blackbox:srcs",
        "//src/test/java/com/google/devtools/build/lib/bugreport:srcs",
        "//src/test/java/com/google/devtools/build/lib/buildeventservice:srcs",
        "//src/test/java/com/google/devtools/build/lib/buildeventstream:srcs",
        "//src/test/java/com/google/devtools/build/lib/buildtool:srcs",
        "//src/test/java/com/google/devtools/build/lib/cmdline:srcs",
        "//src/test/java/com/google/devtools/build/lib/collect:srcs",
        "//src/test/java/com/google/devtools/build/lib/concurrent:srcs",
        "//src/test/java/com/google/devtools/build/lib/dynamic:srcs",
        "//src/test/java/com/google/devtools/build/lib/events:srcs",
        "//src/test/java/com/google/devtools/build/lib/exec:srcs",
        "//src/test/java/com/google/devtools/build/lib/generatedprojecttest:srcs",
        "//src/test/java/com/google/devtools/build/lib/generatedprojecttest/util:srcs",
        "//src/test/java/com/google/devtools/build/lib/graph:srcs",
        "//src/test/java/com/google/devtools/build/lib/integration/util:srcs",
        "//src/test/java/com/google/devtools/build/lib/io:srcs",
        "//src/test/java/com/google/devtools/build/lib/metrics:srcs",
        "//src/test/java/com/google/devtools/build/lib/outputfilter:srcs",
        "//src/test/java/com/google/devtools/build/lib/packages:srcs",
        "//src/test/java/com/google/devtools/build/lib/packages/metrics:srcs",
        "//src/test/java/com/google/devtools/build/lib/packages/semantics:srcs",
        "//src/test/java/com/google/devtools/build/lib/pkgcache:srcs",
        "//src/test/java/com/google/devtools/build/lib/platform:srcs",
        "//src/test/java/com/google/devtools/build/lib/platform/darwin:srcs",
        "//src/test/java/com/google/devtools/build/lib/profiler:srcs",
        "//src/test/java/com/google/devtools/build/lib/profiler/memory:srcs",
        "//src/test/java/com/google/devtools/build/lib/query2:srcs",
        "//src/test/java/com/google/devtools/build/lib/remote:srcs",
        "//src/test/java/com/google/devtools/build/lib/rules:srcs",
        "//src/test/java/com/google/devtools/build/lib/sandbox:srcs",
        "//src/test/java/com/google/devtools/build/lib/server:srcs",
        "//src/test/java/com/google/devtools/build/lib/skyframe:srcs",
        "//src/test/java/com/google/devtools/build/lib/skyframe/packages:srcs",
        "//src/test/java/com/google/devtools/build/lib/skyframe/serialization:srcs",
        "//src/test/java/com/google/devtools/build/lib/standalone:srcs",
        "//src/test/java/com/google/devtools/build/lib/starlark:srcs",
        "//src/test/java/com/google/devtools/build/lib/starlarkdebug/server:srcs",
        "//src/test/java/com/google/devtools/build/lib/supplier:srcs",
        "//src/test/java/com/google/devtools/build/lib/testing/common:srcs",
        "//src/test/java/com/google/devtools/build/lib/testutil:srcs",
        "//src/test/java/com/google/devtools/build/lib/unix:srcs",
        "//src/test/java/com/google/devtools/build/lib/unsafe:srcs",
        "//src/test/java/com/google/devtools/build/lib/util:srcs",
        "//src/test/java/com/google/devtools/build/lib/versioning:srcs",
        "//src/test/java/com/google/devtools/build/lib/vfs:srcs",
        "//src/test/java/com/google/devtools/build/lib/view/cpp:srcs",
        "//src/test/java/com/google/devtools/build/lib/view/java:srcs",
        "//src/test/java/com/google/devtools/build/lib/view/util:srcs",
        "//src/test/java/com/google/devtools/build/lib/windows:srcs",
        "//src/test/java/com/google/devtools/build/lib/worker:srcs",
        "//src/test/java/net/starlark/java/annot/processor:srcs",
        "//src/test/java/net/starlark/java/eval:srcs",
        "//src/test/java/net/starlark/java/syntax:srcs",
    ],
    visibility = ["//src:__pkg__"],
)

# This should correspond to the list of "EMBEDDED_TOOLS" in TestConstants.java.bazel.
filegroup(
    name = "embedded_scripts",
    testonly = 1,
    data = [
        "//src/main/tools:build-runfiles",
        "//src/main/tools:linux-sandbox",
        "//src/main/tools:process-wrapper",
    ],
)

alias(
    name = "test_runner",
    actual = ":AllTests",
    visibility = ["//src/test:__subpackages__"],
)

java_library(
    name = "AllTests",
    testonly = 1,
    srcs = ["AllTests.java"],
    visibility = ["//src/test:__subpackages__"],
    deps = [
        "//src/test/java/com/google/devtools/build/lib/testutil",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestSuite",
        "//third_party:junit4",
        "//third_party:mockito",
    ],
)

TEST_SUITES = [
    "ziputils",
    "rules",
    "shell",
    "server",
    "skyframe",
]

[
    test_suite(
        name = n,
        tags = [n],
    )
    for n in TEST_SUITES
]

test_suite(
    name = "exec",
    tests = [
        "//src/test/java/com/google/devtools/build/lib/exec:ExecTests",
        "//src/test/java/com/google/devtools/build/lib/exec/local:ExecLocalTests",
    ],
)

test_suite(
    name = "foundations",
    tests = [
        "//src/test/java/com/google/devtools/build/lib/cmdline:CmdLineTests",
        "//src/test/java/com/google/devtools/build/lib/collect:CollectTests",
        "//src/test/java/com/google/devtools/build/lib/concurrent:ConcurrentTests",
        "//src/test/java/com/google/devtools/build/lib/events:EventsTests",
        "//src/test/java/com/google/devtools/build/lib/testutil:TestUtilTests",
    ],
)

test_suite(
    name = "analysis",
    tests = [
        "//src/test/java/com/google/devtools/build/lib/analysis:AllAnalysisTests",
    ],
)

test_suite(
    name = "others",
    tags = ["-" + n for n in TEST_SUITES],
)
