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

# I/O utilities.

IO_PROTO_SRCS = [
    "CommandExtensionReporter.java",
    "MessageInputStream.java",
    "MessageInputStreamWrapper.java",
    "MessageOutputStream.java",
    "MessageOutputStreamWrapper.java",
]

OUT_ERR_SRCS = [
    "AnsiTerminal.java",
    "AnsiTerminalPrinter.java",
    "AnsiTerminalWriter.java",
    "DelegatingOutErr.java",
    "OutErr.java",
    "LineCountingAnsiTerminalWriter.java",
    "LinePrefixingOutputStream.java",
    "LineFlushingOutputStream.java",
    "LoggingTerminalWriter.java",
    "PositionAwareAnsiTerminalWriter.java",
    "RecordingOutErr.java",
    "StreamDemultiplexer.java",
    "StreamMultiplexer.java",
]

java_library(
    name = "io",
    srcs = glob(
        ["*.java"],
        exclude = OUT_ERR_SRCS + IO_PROTO_SRCS,
    ),
    deps = [
        ":io-proto",
        ":out-err",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/profiler",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//third_party:flogger",
        "//third_party:guava",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "io-proto",
    srcs = IO_PROTO_SRCS,
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
    ],
)

java_library(
    name = "out-err",
    srcs = OUT_ERR_SRCS,
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent:thread_safety",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)
