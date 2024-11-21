load("@rules_java//java:defs.bzl", "java_binary", "java_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]) + [
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/commands:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/coverage:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/debug:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "repository_module",
    srcs = ["BazelRepositoryModule.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/authandtls",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper:credential_module",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:common",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:extension_eval_impl",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:inspection_impl",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:registry",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:resolution_impl",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:tidy_impl",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:vendor",
        "//src/main/java/com/google/devtools/build/lib/bazel/commands",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository:repository_options",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/cache",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/downloader",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/starlark",
        "//src/main/java/com/google/devtools/build/lib/clock",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/pkgcache",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/local_repository_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/new_local_repository_function",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/new_local_repository_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/repository_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe:mutable_supplier",
        "//src/main/java/com/google/devtools/build/lib/skyframe:precomputed_value",
        "//src/main/java/com/google/devtools/build/lib/skyframe:repository_mapping_function",
        "//src/main/java/com/google/devtools/build/lib/skyframe:sky_functions",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_cluster",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skyframe_executor_repository_helpers_holder",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/repository",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "bazel_configuration",
    srcs = ["BazelConfiguration.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/concurrent:thread_safety",
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_library(
    name = "builtin_command_module",
    srcs = ["BazelBuiltinCommandModule.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/exec:standalone_test_strategy",
        "//src/main/java/com/google/devtools/build/lib/runtime/commands",
    ],
)

java_library(
    name = "modules",
    srcs = [
        "BazelDiffAwarenessModule.java",
        "BazelStartupOptionsModule.java",
        "BazelWorkspaceStatusModule.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/actions:artifacts",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_directories",
        "//src/main/java/com/google/devtools/build/lib/analysis:build_info",
        "//src/main/java/com/google/devtools/build/lib/analysis:build_info_event",
        "//src/main/java/com/google/devtools/build/lib/analysis:workspace_status_action",
        "//src/main/java/com/google/devtools/build/lib/collect/nestedset",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:module_action_context_registry",
        "//src/main/java/com/google/devtools/build/lib/shell",
        "//src/main/java/com/google/devtools/build/lib/skyframe:diff_awareness",
        "//src/main/java/com/google/devtools/build/lib/skyframe:local_diff_awareness",
        "//src/main/java/com/google/devtools/build/lib/skyframe:workspace_info",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:command",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "spawn_log_module",
    srcs = [
        "SpawnLogModule.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/exec:execution_options",
        "//src/main/java/com/google/devtools/build/lib/exec:executor_builder",
        "//src/main/java/com/google/devtools/build/lib/exec:module_action_context_registry",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_log_context",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

java_library(
    name = "bazel_filesystem_module",
    srcs = ["BazelFileSystemModule.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/jni",
        "//src/main/java/com/google/devtools/build/lib/unix",
        "//src/main/java/com/google/devtools/build/lib/util:abrupt_exit_exception",
        "//src/main/java/com/google/devtools/build/lib/util:detailed_exit_code",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/bazel",
        "//src/main/java/com/google/devtools/build/lib/windows",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:failure_details_java_proto",
        "//third_party:guava",
    ],
)

java_library(
    name = "main",
    srcs = ["Bazel.java"],
    deps = [
        ":bazel_filesystem_module",
        ":builtin_command_module",
        ":modules",
        ":repository_module",
        ":spawn_log_module",
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/analysis:blaze_version_info",
        "//src/main/java/com/google/devtools/build/lib/authandtls/credentialhelper:credential_module",
        "//src/main/java/com/google/devtools/build/lib/bazel/bzlmod:bazel_lockfile_module",
        "//src/main/java/com/google/devtools/build/lib/bazel/coverage",
        "//src/main/java/com/google/devtools/build/lib/bazel/debug:workspace-rule-module",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository/starlark",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules",
        "//src/main/java/com/google/devtools/build/lib/buildeventservice",
        "//src/main/java/com/google/devtools/build/lib/dynamic",
        "//src/main/java/com/google/devtools/build/lib/includescanning",
        "//src/main/java/com/google/devtools/build/lib/metrics:memory-use-recorder",
        "//src/main/java/com/google/devtools/build/lib/metrics:metrics_module",
        "//src/main/java/com/google/devtools/build/lib/network:noop_connectivity",
        "//src/main/java/com/google/devtools/build/lib/outputfilter",
        "//src/main/java/com/google/devtools/build/lib/packages/metrics",
        "//src/main/java/com/google/devtools/build/lib/platform:sleep_prevention_module",
        "//src/main/java/com/google/devtools/build/lib/platform:system_suspension_module",
        "//src/main/java/com/google/devtools/build/lib/profiler:command_profiler_module",
        "//src/main/java/com/google/devtools/build/lib/profiler/memory:allocationtracker_module",
        "//src/main/java/com/google/devtools/build/lib/remote",
        "//src/main/java/com/google/devtools/build/lib/runtime/mobileinstall",
        "//src/main/java/com/google/devtools/build/lib/sandbox:sandbox_module",
        "//src/main/java/com/google/devtools/build/lib/skyframe:skymeld_module",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:serialization_module",
        "//src/main/java/com/google/devtools/build/lib/standalone",
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/module",
        "//src/main/java/com/google/devtools/build/lib/worker:worker_module",
        "//third_party:guava",
    ],
)

java_library(
    name = "resolved_event",
    srcs = ["ResolvedEvent.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/events",
        "//src/main/java/com/google/devtools/build/lib/vfs",
    ],
)

java_binary(
    name = "BazelServer",
    javacopts = [
        "-Xlint:all",
    ],
    main_class = "com.google.devtools.build.lib.bazel.Bazel",
    runtime_deps = [
        ":main",
        "//src/main/java/com/google/devtools/build/lib/server",
        "//src/main/java/com/google/devtools/build/lib/util:simple_log_handler",  # See startup_options.cc
        "//src/main/java/com/google/devtools/build/lib/util:single_line_formatter",  # See startup_options.cc
    ],
)

java_library(
    name = "bazel_version",
    srcs = ["BazelVersion.java"],
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)
