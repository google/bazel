load("@rules_java//java:defs.bzl", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/java/com/google/devtools/build/lib/server/signal:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "idle_task",
    srcs = ["IdleTask.java"],
)

java_library(
    name = "server",
    srcs = [
        "CommandManager.java",
        "GrpcServerImpl.java",
        "IdleTaskManager.java",
        "ServerWatcherRunnable.java",
    ],
    deps = [
        ":idle_task",
        ":pid_file_watcher",
        ":rpc_server",
        ":shutdown_hooks",
        "//src/main/java/com/google/devtools/build/lib:runtime/blaze_command_result",
        "//src/main/java/com/google/devtools/build/lib:runtime/command_dispatcher",
        "//src/main/java/com/google/devtools/build/lib:runtime/safe_request_logging",
        "//src/main/java/com/google/devtools/build/lib/bugreport",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/concurrent",
        "//src/main/java/com/google/devtools/build/lib/platform:system_memory_pressure_monitor",
        "//src/main/java/com/google/devtools/build/lib/profiler:google-auto-profiler-utils",
        "//src/main/java/com/google/devtools/build/lib/unix:procmeminfo_parser",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:interrupted_failure_details",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//src/main/java/com/google/devtools/build/lib/util/io:io-proto",
        "//src/main/java/com/google/devtools/build/lib/util/io:out-err",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/com/google/devtools/common/options:invocation_policy",
        "//src/main/protobuf:command_server_java_grpc",
        "//src/main/protobuf:command_server_java_proto",
        "//src/main/protobuf:failure_details_java_proto",
        "//src/main/protobuf:invocation_policy_java_proto",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party:netty",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_library(
    name = "pid_file_watcher",
    srcs = [
        "PidFileWatcher.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "shutdown_hooks",
    srcs = [
        "ShutdownHooks.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "rpc_server",
    srcs = [
        "RPCServer.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
    ],
)

java_library(
    name = "install_base_garbage_collector",
    srcs = [
        "InstallBaseGarbageCollector.java",
        "InstallBaseGarbageCollectorIdleTask.java",
    ],
    deps = [
        ":idle_task",
        "//src/main/java/com/google/devtools/build/lib/util:file_system_lock",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//third_party:flogger",
        "//third_party:guava",
    ],
)
