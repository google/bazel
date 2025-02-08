load("@rules_java//java:defs.bzl", "java_library")
load("//:workspace_deps.bzl", "gen_workspace_stanza")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["*"]) + [
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/cpp:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/genrule:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/java:srcs",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/python:srcs",
    ],
    visibility = ["//src:__subpackages__"],
)

java_library(
    name = "rules",
    srcs = glob(["*.java"]),
    resources = [
        "tools.WORKSPACE",
        "xcode_configure.WORKSPACE",
        ":builtins_bzl_zip",
        ":coverage.WORKSPACE",
        ":rules_suffix.WORKSPACE",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/actions",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/file_write_action_context",
        "//src/main/java/com/google/devtools/build/lib/analysis:actions/template_expansion_action",
        "//src/main/java/com/google/devtools/build/lib/analysis:analysis_cluster",
        "//src/main/java/com/google/devtools/build/lib/analysis:common_prerequisite_validator",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/build_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/core_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment",
        "//src/main/java/com/google/devtools/build/lib/analysis:config/fragment_options",
        "//src/main/java/com/google/devtools/build/lib/analysis:constraints/environment_rule",
        "//src/main/java/com/google/devtools/build/lib/analysis:platform_configuration",
        "//src/main/java/com/google/devtools/build/lib/analysis:statically_linked_marker_provider",
        "//src/main/java/com/google/devtools/build/lib/bazel:bazel_configuration",
        "//src/main/java/com/google/devtools/build/lib/bazel/repository",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/genrule",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/java:bazel_java_semantics",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/python",
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/exec:execution_options",
        "//src/main/java/com/google/devtools/build/lib/exec:module_action_context_registry",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_cache",
        "//src/main/java/com/google/devtools/build/lib/exec:spawn_strategy_registry",
        "//src/main/java/com/google/devtools/build/lib/packages",
        "//src/main/java/com/google/devtools/build/lib/packages/semantics",
        "//src/main/java/com/google/devtools/build/lib/remote",
        "//src/main/java/com/google/devtools/build/lib/remote/options",
        "//src/main/java/com/google/devtools/build/lib/rules:alias",
        "//src/main/java/com/google/devtools/build/lib/rules:core_workspace_rules",
        "//src/main/java/com/google/devtools/build/lib/rules:extra/action_listener_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:extra/extra_action_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:filegroup/filegroup",
        "//src/main/java/com/google/devtools/build/lib/rules:label_build_settings",
        "//src/main/java/com/google/devtools/build/lib/rules:repository/new_local_repository_rule",
        "//src/main/java/com/google/devtools/build/lib/rules:test_rules",
        "//src/main/java/com/google/devtools/build/lib/rules:toolchain_type",
        "//src/main/java/com/google/devtools/build/lib/rules/android",
        "//src/main/java/com/google/devtools/build/lib/rules/apple",
        "//src/main/java/com/google/devtools/build/lib/rules/config",
        "//src/main/java/com/google/devtools/build/lib/rules/core",
        "//src/main/java/com/google/devtools/build/lib/rules/cpp",
        "//src/main/java/com/google/devtools/build/lib/rules/genquery",
        "//src/main/java/com/google/devtools/build/lib/rules/genrule",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-compilation",
        "//src/main/java/com/google/devtools/build/lib/rules/java:java-rules",
        "//src/main/java/com/google/devtools/build/lib/rules/objc",
        "//src/main/java/com/google/devtools/build/lib/rules/platform",
        "//src/main/java/com/google/devtools/build/lib/rules/proto",
        "//src/main/java/com/google/devtools/build/lib/rules/python",
        "//src/main/java/com/google/devtools/build/lib/rules/starlarkdocextract",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/android",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/core",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/cpp",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/java",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/objc",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/proto",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/python",
        "//src/main/java/com/google/devtools/build/lib/starlarkbuildapi/stubs",
        "//src/main/java/com/google/devtools/build/lib/util",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/java/net/starlark/java/eval",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

# We need this redirect target so that the builtins zip can be packaged as a
# resource next to the BazelBuiltins class (com/google/devtools/...). Otherwise
# it gets placed under the package path of the original zip target.
genrule(
    name = "builtins_bzl_zip",
    srcs = ["//src/main/starlark/builtins_bzl:builtins_bzl.zip"],
    outs = ["builtins_bzl.zip"],
    cmd = "cp $(SRCS) $@",
    visibility = ["//:__pkg__"],
)

# To generate an updated version of CoverageOutputGenerator:
# 1. bazel build tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator_zip
# 2. Copy and rename the zip file with a new version locally.
# 3. Upload the file under https://mirror.bazel.build/bazel_coverage_output_generator/releases.
# 4. Update //workspace_deps.bzl and //tools/test/extensions.bzl to point to the new release.
gen_workspace_stanza(
    name = "workspace_with_coverage_output_generator",
    out = "coverage.WORKSPACE",
    preamble = """
        load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
        load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")
    """,
    repos = ["remote_coverage_tools"],
    use_maybe = 1,
    visibility = ["//:__pkg__"],
)

gen_workspace_stanza(
    name = "rules_suffix",
    out = "rules_suffix.WORKSPACE",
    postamble = """
        load("@rules_java//java:rules_java_deps.bzl", "rules_java_dependencies")
        rules_java_dependencies()
        load("@rules_python//python:repositories.bzl", "py_repositories")
        py_repositories()
        load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps")
        protobuf_deps()
        load("@rules_java//java:repositories.bzl", "rules_java_toolchains")
        rules_java_toolchains()
    """,
    preamble = """
        load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
        load("@bazel_tools//tools/build_defs/repo:utils.bzl", "maybe")
    """,
    repos = [
        "bazel_skylib",  # Dependency of other rules
        "com_google_protobuf",  # dependency of rules_java
        "rules_java",  # For auto-load java rules symbols
        "rules_cc",  # For auto-load cc rules symbols
        "rules_python",  # For auto-load python rules symbols
        "rules_license",  # for license attestations
    ],
    use_maybe = 1,
    visibility = ["//:__pkg__"],
)
