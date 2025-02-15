load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/lib/util/io:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "logging",
    srcs = ["LoggingUtil.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "var_int",
    srcs = [
        "VarInt.java",
    ],
)

java_library(
    name = "os",
    srcs = ["OS.java"],
)

java_library(
    name = "classpath",
    srcs = ["Classpath.java"],
    deps = ["//third_party:guava"],
)

java_library(
    name = "file_system_lock",
    srcs = ["FileSystemLock.java"],
    deps = [
        ":string_encoding",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:guava",
    ],
)

java_library(
    name = "process",
    srcs = ["ProcessUtils.java"],
    deps = [
        ":os",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/windows:processes",
    ],
)

java_library(
    name = "single_line_formatter",
    srcs = ["SingleLineFormatter.java"],
    deps = [
        "//third_party:guava",
    ],
)

java_library(
    name = "simple_log_handler",
    srcs = ["SimpleLogHandler.java"],
    deps = [
        ":os",
        ":single_line_formatter",
        ":string_encoding",
        ":util",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "debug-logger-configurator",
    srcs = ["DebugLoggerConfigurator.java"],
    deps = [":util"],
)

java_library(
    name = "describable_execution_unit",
    srcs = ["DescribableExecutionUnit.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "script_util",
    srcs = ["ScriptUtil.java"],
    deps = [
        ":os",
        ":shell_escaper",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "command",
    srcs = [
        "CommandBuilder.java",
        "CommandDescriptionForm.java",
        "CommandFailureUtils.java",
        "CommandUtils.java",
    ],
    deps = [
        ":describable_execution_unit",
        ":os",
        ":script_util",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "maybe_complete_set",
    srcs = ["MaybeCompleteSet.java"],
    deps = [
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "resource_converter",
    srcs = [
        "ResourceConverter.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:localhost_capacity",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "cpu_resource_converter",
    srcs = [
        "CpuResourceConverter.java",
    ],
    deps = [
        ":resource_converter",
        "//third_party:guava",
    ],
)

java_library(
    name = "shallow_object_size_computer",
    srcs = ["ShallowObjectSizeComputer.java"],
)

java_library(
    name = "object_graph_traverser",
    srcs = ["ObjectGraphTraverser.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/collect",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "memory_accountant",
    srcs = ["MemoryAccountant.java"],
    deps = [
        ":object_graph_traverser",
        ":shallow_object_size_computer",
        "//third_party:guava",
    ],
)

java_library(
    name = "ram_resource_converter",
    srcs = [
        "RamResourceConverter.java",
    ],
    deps = [
        ":resource_converter",
        "//third_party:guava",
    ],
)

java_library(
    name = "pair",
    srcs = [
        "Pair.java",
    ],
    deps = [
        "//third_party:jsr305",
    ],
)

java_library(
    name = "util",
    srcs = [
        "AnsiStrippingOutputStream.java",
        "CPU.java",
        "ClassName.java",
        "DependencySet.java",
        "Either.java",
        "FileHandlerQuerier.java",
        "Fingerprint.java",
        "JavaSleeper.java",
        "LatestObjectMetricExporter.java",
        "LogHandlerQuerier.java",
        "LoggingUtil.java",
        "LongArrayList.java",
        "NetUtil.java",
        "OnDemandString.java",
        "OptionsUtils.java",
        "OrderedSetMultimap.java",
        "OsUtils.java",
        "PathFragmentFilter.java",
        "PersistentMap.java",
        "RegexFilter.java",
        "ResourceFileLoader.java",
        "Sleeper.java",
        "StringIndexer.java",
        "ThreadUtils.java",
        "TimeUtilities.java",
        "UserUtils.java",
    ],
    exports = [
        # vfs depends on the profiler and creates a cycle since we use Pair in profiler
        ":pair",
        ":stream_writer",
    ],
    deps = [
        ":os",
        ":shell_escaper",
        ":string_encoding",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:error_prone_annotations",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "stream_writer",
    srcs = [
        "StreamWriter.java",
    ],
)

java_library(
    name = "filetype",
    srcs = [
        "FileType.java",
        "FileTypeSet.java",
    ],
    deps = [
        ":string",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:visible-for-serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/autocodec:serialization-constant",
        "//src/main/java/com/google/devtools/build/lib/vfs:ospathpolicy",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "TestType",
    srcs = ["TestType.java"],
)

java_library(
    name = "resource_usage",
    srcs = [
        "ResourceUsage.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/unix:procmeminfo_parser",
        "//third_party:guava",
    ],
)

java_library(
    name = "shell_escaper",
    srcs = [
        "GccParamFileEscaper.java",
        "ShellEscaper.java",
        "WindowsParamFileEscaper.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "string_encoding",
    srcs = ["StringEncoding.java"],
    deps = [
        ":os",
        "//src/main/java/com/google/devtools/build/lib/unsafe:string",
        "//third_party:guava",
    ],
)

java_library(
    name = "string",
    srcs = [
        "StringUtil.java",
        "StringUtilities.java",
    ],
    deps = ["//third_party:guava"],
)

java_library(
    name = "abrupt_exit_exception",
    srcs = ["AbruptExitException.java"],
    deps = [
        ":detailed_exit_code",
        ":exit_code",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "exit_code",
    srcs = [
        "ExitCode.java",
    ],
    deps = [
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "custom_exit_code_publisher",
    srcs = ["CustomExitCodePublisher.java"],
    deps = [
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "custom_failure_detail_publisher",
    srcs = ["CustomFailureDetailPublisher.java"],
    deps = [
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "detailed_exit_code",
    srcs = ["DetailedExitCode.java"],
    deps = [
        ":exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "crash_failure_details",
    srcs = ["CrashFailureDetails.java"],
    deps = [
        ":detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "hash_codes",
    srcs = ["HashCodes.java"],
    deps = ["//third_party:jsr305"],
)

java_library(
    name = "heap_offset_helper",
    srcs = ["HeapOffsetHelper.java"],
    deps = [
        ":TestType",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)

java_library(
    name = "interrupted_failure_details",
    srcs = ["InterruptedFailureDetails.java"],
    deps = [
        ":abrupt_exit_exception",
        ":detailed_exit_code",
        "//src/main/protobuf:failure_details_java_proto",
    ],
)

java_library(
    name = "concurrency_meter",
    srcs = ["ConcurrencyMeter.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//third_party:error_prone_annotations",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "blocker",
    srcs = ["Blocker.java"],
    add_exports = ["java.base/jdk.internal.misc"],
)

java_library(
    name = "value_or_exception",
    srcs = ["ValueOrException.java"],
    deps = [
        "//third_party:guava",
    ],
)

java_library(
    name = "temp_path_generator",
    srcs = ["TempPathGenerator.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "cleaner",
    srcs = ["BazelCleaner.java"],
)
