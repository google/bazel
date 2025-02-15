# Packaging

load("@bazel_pip_dev_deps//:requirements.bzl", "requirement")
load("@bazel_skylib//:bzl_library.bzl", "bzl_library")
load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_python//python:defs.bzl", "py_binary", "py_library")
load("//src:build_defs.bzl", "transition_java_language_8_archive")
load("//src:release_archive.bzl", "release_archive")
load(":embedded_tools.bzl", "srcsfile")
load(":rule_size_test.bzl", "rule_size_test")

package(default_applicable_licenses = ["@io_bazel//:license"])

exports_files([
    "jdeps_modules.golden",
    "MODULE.tools",
])

md5_cmd = "set -e -o pipefail && %s $(SRCS) | %s | %s > $@"

[genrule(
    name = "install_base_key-file" + suffix,
    srcs = [
        # Ensure we haven't forgotten any package-zip items,
        # otherwise bazel won't correctly reextract modified files.
        "//src/main/java/com/google/devtools/build/lib/bazel:BazelServer_deploy.jar",
        "//src/main/java/net/starlark/java/eval:cpu_profiler",
        "//src/main/cpp:client",
        "//src/main/tools:build-runfiles",
        "//src/main/tools:process-wrapper",
        "//src/main/tools:linux-sandbox",
        "//tools/osx:xcode-locator",
        "//:platforms-srcs",
    ] + select({
        "//src/conditions:windows": [],
        "//conditions:default": [
            "//src/main/tools:daemonize",
        ],
    }) + embedded_tools_target,
    outs = ["install_base_key" + suffix],
    cmd = select({
        "//src/conditions:darwin": md5_cmd % ("/sbin/md5", "/sbin/md5", "head -c 32"),
        "//src/conditions:freebsd": md5_cmd % ("/sbin/md5", "/sbin/md5", "head -c 32"),
        # We avoid using the `head` tool's `-c` option, since it does not exist
        # on OpenBSD.
        "//src/conditions:openbsd": md5_cmd % ("/bin/md5", "/bin/md5", "dd bs=32 count=1"),
        "//conditions:default": md5_cmd % ("md5sum", "md5sum", "head -c 32"),
    }),
) for suffix, embedded_tools_target in {
    "_jdk_allmodules": [":embedded_tools_jdk_allmodules"],
    "_jdk_minimal": [":embedded_tools_jdk_minimal"],
    "_nojdk": [":embedded_tools_nojdk"],
    "_notools": [],
}.items()]

# Try to grab the java version from the java_toolchain.
# Unfortunately, we don't have access to the javac options
# so we cannot get it from the command-line options.
genquery(
    name = "java_toolchain_content",
    expression = "kind(java_toolchain, deps(@rules_java//toolchains:current_java_toolchain))",
    opts = ["--output=xml"],
    scope = ["@rules_java//toolchains:current_java_toolchain"],
)

py_library(
    name = "create_embedded_tools_lib",
    srcs = ["create_embedded_tools_lib.py"],
    visibility = ["//:__pkg__"],
)

py_binary(
    name = "create_embedded_tools",
    srcs = ["create_embedded_tools.py"],
    deps = [":create_embedded_tools_lib"],
)

# Script for turning builtins_bzl sources into a zip file for unioning into the
# package zip / install base.
sh_binary(
    name = "zip_builtins",
    srcs = ["zip_builtins.sh"],
    visibility = ["//src/main/starlark/builtins_bzl:__pkg__"],
)

# The tools Bazel uses to compile Java.
# TODO(#6316): Gradually remove the targets here.
JAVA_TOOLS = [
    "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/jarhelper:srcs",
    "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:embedded_tools",
]

# TODO(philwo): Clean this up, once a Bazel that can run py_binary and sh_binary
# in Starlark rules on Windows has been released.
[filegroup(
    name = "embedded_tools" + suffix + "_srcs",
    srcs = JAVA_TOOLS + [
               "BUILD.tools",
               "MODULE.tools",
               "//src/conditions:embedded_tools",
               "//src/main/cpp/util:embedded_tools",
               "//src/main/native:embedded_tools",
               "//src/main/protobuf:srcs",
               "//src/tools/launcher:srcs",
               "//third_party:gpl-srcs",
               "//third_party/def_parser:srcs",
               "//third_party/grpc:embedded_tools_srcs",
               "//third_party/grpc/bazel:embedded_tools_srcs",
               "//third_party/ijar:embedded_zipper_sources",
               "//third_party/ijar:zipper",
               "//third_party/py/abseil:srcs",
               "//third_party/zlib:embedded_tools",
               "//tools:embedded_tools_srcs",
           ] + select({
               "//src/conditions:darwin": [
                   "//tools/osx:xcode_locator.m",
               ],
               "//conditions:default": [],
           }) + select({
               "//src/conditions:windows": [
                   "//src/tools/launcher",
                   "//src/tools/launcher:launcher_maker",
                   "//third_party/def_parser",
               ],
               "//conditions:default": [],
           }) +
           jdk,
    visibility = [
        "//src/test:__subpackages__",  # For integration tests
    ],
) for suffix, jdk in {
    "_jdk_allmodules": [":embedded_jdk_allmodules"],
    "_jdk_minimal": select({
        # We cannot minimize the JDK during cross compiling for Windows arm64
        "//src/conditions:windows_arm64": [":embedded_jdk_vanilla"],
        "//conditions:default": [":embedded_jdk_minimal"],
    }),
    "_nojdk": [],
}.items()]

rule_size_test(
    name = "embedded_tools_size_test",
    src = ":embedded_tools_jdk_allmodules_srcs",
    # WARNING: Only adjust the number in `expect` if you are intentionally
    # adding or removing embedded tools. Know that the more embedded tools there
    # are in Bazel, the bigger the binary becomes and the slower Bazel starts.
    expect = 364,
    margin = 5,  # percentage
)

filegroup(
    name = "embedded_jdk_vanilla",
    srcs = select({
        "//src/conditions:darwin_x86_64": [
            "@openjdk_macos_x86_64_vanilla//file",
        ],
        "//src/conditions:darwin_arm64": [
            "@openjdk_macos_aarch64_vanilla//file",
        ],
        "//src/conditions:windows_x86_64": [
            "@openjdk_win_vanilla//file",
        ],
        "//src/conditions:windows_arm64": [
            "@openjdk_win_arm64_vanilla//file",
        ],
        "//src/conditions:linux_aarch64": [
            "@openjdk_linux_aarch64_vanilla//file",
        ],
        "//src/conditions:linux_ppc64le": [
            "@openjdk_linux_ppc64le_vanilla//file",
        ],
        "//src/conditions:linux_s390x": [
            "@openjdk_linux_s390x_vanilla//file",
        ],
        "//src/conditions:linux_riscv64": [
            "@openjdk_linux_riscv64_vanilla//file",
        ],
        "//conditions:default": [
            "@openjdk_linux_vanilla//file",
        ],
    }),
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

# This program patches the app manifest of the java.exe launcher to force its
# active code page to UTF-8 on Windows 1903 and later, which is required for
# proper support of Unicode characters outside the system code page.
# The JDK currently (as of JDK 23) doesn't support this natively:
# https://mail.openjdk.org/pipermail/core-libs-dev/2024-November/133773.html
cc_binary(
    name = "patch_java_manifest_for_utf8",
    srcs = ["patch_java_manifest_for_utf8.cc"],
    tags = ["manual"],
    target_compatible_with = ["@platforms//os:windows"],
)

sh_binary(
    name = "minimize_jdk",
    srcs = ["minimize_jdk.sh"],
    data = select({
        "@platforms//os:windows": [
            ":patch_java_manifest_for_utf8",
        ],
        "//conditions:default": [],
    }),
    deps = [
        "@bazel_tools//tools/bash/runfiles",
    ],
)

genrule(
    name = "embedded_jdk_minimal",
    srcs = [
        ":embedded_jdk_vanilla",
        ":jdeps_modules.golden",
    ],
    outs = ["minimal_jdk.zip"],
    cmd = "$(location :minimize_jdk) $(location :embedded_jdk_vanilla) $(location :jdeps_modules.golden) $(OUTS)",
    tools = [
        ":minimize_jdk",
    ],
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

genrule(
    name = "embedded_jdk_allmodules",
    srcs = [
        ":embedded_jdk_vanilla",
        ":jdeps_modules.golden",
    ],
    outs = ["allmodules_jdk.zip"],
    cmd = "$(location :minimize_jdk) --allmodules $(location :embedded_jdk_vanilla) $(location :jdeps_modules.golden) $(OUTS)",
    tools = [
        ":minimize_jdk",
    ],
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

[srcsfile(
    name = "embedded_tools" + suffix + "_params",
    srcs = [":embedded_tools" + suffix + "_srcs"],
    out = "embedded_tools" + suffix + ".params",
) for suffix in [
    "_jdk_allmodules",
    "_jdk_minimal",
    "_nojdk",
]]

[genrule(
    name = "embedded_tools" + suffix,
    srcs = [
        ":embedded_tools" + suffix + "_params",
        ":embedded_tools" + suffix + "_srcs",
    ],
    outs = ["embedded_tools" + suffix + ".zip"],
    cmd = "$(location :create_embedded_tools) \"$@\" $(location :embedded_tools" + suffix + "_params)",
    tools = [":create_embedded_tools"],
) for suffix in [
    "_jdk_allmodules",
    "_jdk_minimal",
    "_nojdk",
]]

[genrule(
    name = "package-zip" + suffix,
    # This script assumes the following arg order:
    #   1) embedded tools zip (if it exists)
    #   2) the deploy jar
    #   3) the install base key
    #   4) the platforms archive
    #   5) everything else to be bundled
    # Ensure we haven't forgotten to add all package-zip items as dependencies
    # of :install_base_key-file, otherwise bazel won't correctly reextract
    # modified files.
    srcs = ([":embedded_tools" + suffix + ".zip"] if embed else []) + [
        "//src/main/java/com/google/devtools/build/lib/bazel:BazelServer_deploy.jar",
        "install_base_key" + suffix,
        "//:platforms-srcs",
        # Non-ordered items follow:
        "//src/main/java/net/starlark/java/eval:cpu_profiler",
        "//src/main/tools:build-runfiles",
        "//src/main/tools:process-wrapper",
        "//src/main/tools:linux-sandbox",
        "//tools/osx:xcode-locator",
    ] + select({
        "//src/conditions:windows": [],
        "//conditions:default": [
            "//src/main/tools:daemonize",
        ],
    }),
    outs = ["package" + suffix + ".zip"],
    cmd = "$(location :package_bazel_on_host_platform) $@ " + ("" if embed else "''") + " $(SRCS)",
    tools = [":package_bazel_on_host_platform"],
) for suffix, embed in [
    ("_jdk_allmodules", True),
    ("_jdk_minimal", True),
    ("_notools", False),
    ("_nojdk", True),
]]

genrule(
    name = "package_bazel_on_host_platform",
    srcs = ["package-bazel.sh"],
    outs = ["package-bazel-on-host-platform.sh"],
    cmd = select({
        "//src/conditions:openbsd": "cat $(SRCS) | sed -e 's@#!/bin/bash@#!/usr/local/bin/bash@' > $@",
        "//conditions:default": "cp $(SRCS) $@",
    }),
)

[genrule(
    name = "bazel-bin" + suffix,
    srcs = [
        "//src/main/cpp:client",
        "package-zip" + jdk,
    ],
    outs = ["bazel" + suffix],
    # In msys, a file path without .exe suffix(say foo), refers to a file with .exe
    # suffix(say foo.exe), if foo.exe exists and foo doesn't. So, on windows, we
    # need to remove bazel.exe first, so that cat to bazel won't fail.
    cmd = "rm -f $@; cat $(location //src/main/cpp:client) $(location :package-zip" + jdk + ") > $@ && zip -qA $@ && chmod a+x $@",
    executable = 1,
    output_to_bindir = 1,
    visibility = [
        "//:__pkg__",  # For distribution archive lockfile generation
        "//scripts:__pkg__",  # For bash completion generation
        "//scripts/packages:__pkg__",  # For installer generation
        "//src/java:__subpackages__",  # For command line reference generation
        "//src/test:__subpackages__",  # For integration tests
        "//tools/compliance:__pkg__",  # For license gathering
    ],
) for suffix, jdk in [
    ("-dev", "_jdk_allmodules"),
    ("", "_jdk_minimal"),
    ("_jdk_allmodules", "_jdk_allmodules"),
    ("_jdk_minimal", "_jdk_minimal"),
    ("_notools", "_notools"),
    ("_nojdk", "_nojdk"),
]]

# Build an executable named `bazel.exe`.
# Works on all platforms but only makes sense on Windows.
# Workaround for non-configurability of genrule's `outs` attribute.
[genrule(
    name = "bazel-bin" + suffix + ".exe",
    srcs = [":bazel-bin" + suffix],
    outs = ["bazel" + suffix + ".exe"],
    cmd = "cp $(location :bazel-bin" + suffix + ") $@",
    executable = 1,
    output_to_bindir = 1,
    visibility = [
        "//scripts:__pkg__",  # For bash completion generation
        "//scripts/packages:__pkg__",  # For installer generation
        "//src/test:__subpackages__",  # For integration tests
    ],
) for suffix in [
    "",
    "-dev",
    "_notools",
    "_nojdk",
]]

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/conditions:srcs",
        "//src/java_tools/buildjar:srcs",
        "//src/java_tools/import_deps_checker:srcs",
        "//src/java_tools/junitrunner:srcs",
        "//src/java_tools/junitrunner/javatests/com/google/testing/coverage:srcs",
        "//src/java_tools/singlejar:srcs",
        "//src/main/cpp:srcs",
        "//src/main/java/com/google/devtools/build/docgen:srcs",
        "//src/main/java/com/google/devtools/build/lib:srcs",
        "//src/main/java/com/google/devtools/build/lib/includescanning:srcs",
        "//src/main/java/com/google/devtools/build/lib/network:srcs",
        "//src/main/java/com/google/devtools/build/lib/outputfilter:srcs",
        "//src/main/java/com/google/devtools/build/skyframe:srcs",
        "//src/main/java/com/google/devtools/common/options:srcs",
        "//src/main/java/net/starlark/java/cmd:srcs",
        "//src/main/java/net/starlark/java/lib/json:srcs",
        "//src/main/java/net/starlark/java/spelling:srcs",
        "//src/main/native:srcs",
        "//src/main/protobuf:srcs",
        "//src/main/res:srcs",
        "//src/main/starlark/builtins_bzl:srcs",
        "//src/main/tools:srcs",
        "//src/test/cpp:srcs",
        "//src/test/gen:srcs",
        "//src/test/java/com/google/devtools/build/docgen:srcs",
        "//src/test/java/com/google/devtools/build/lib:srcs",
        "//src/test/java/com/google/devtools/build/lib/shell:srcs",
        "//src/test/java/com/google/devtools/build/skyframe:srcs",
        "//src/test/java/com/google/devtools/common/options:srcs",
        "//src/test/java/net/starlark/java/eval:srcs",
        "//src/test/java/net/starlark/java/spelling:srcs",
        "//src/test/native/windows:srcs",
        "//src/test/py/bazel:srcs",
        "//src/test/res:srcs",
        "//src/test/shell:srcs",
        "//src/test/testdata/test_tls_certificate",
        "//src/test/tools:srcs",
        "//src/test/tools/bzlmod:srcs",
        "//src/tools/bzlmod:srcs",
        "//src/tools/diskcache:srcs",
        "//src/tools/execlog:srcs",
        "//src/tools/launcher:srcs",
        "//src/tools/one_version:srcs",
        "//src/tools/remote:srcs",
        "//src/tools/singlejar:srcs",
        "//src/tools/starlark/java/com/google/devtools/starlark/common:srcs",
        "//src/tools/workspacelog:srcs",
        "//tools/osx:srcs",
    ],
    visibility = ["//:__pkg__"],
)

# Source files that can be excluded from //:bazel-distfile to avoid triggering the bootstrap test.
filegroup(
    name = "srcs-to-exclude-in-distfile",
    srcs = [
        "//src/test/cpp:srcs",
        "//src/test/gen:srcs",
        "//src/test/java/com/google/devtools/build/docgen:srcs",
        "//src/test/java/com/google/devtools/build/lib:srcs",
        "//src/test/java/com/google/devtools/build/lib/shell:srcs",
        "//src/test/java/com/google/devtools/build/skyframe:srcs",
        "//src/test/java/com/google/devtools/common/options:srcs",
        "//src/test/java/net/starlark/java/eval:srcs",
        "//src/test/java/net/starlark/java/spelling:srcs",
        "//src/test/native/windows:srcs",
        "//src/test/py/bazel:srcs",
        "//src/test/res:srcs",
        "//src/test/shell:srcs",
        "//src/test/testdata/test_tls_certificate",
        "//src/test/tools:srcs",
    ],
    visibility = ["//:__pkg__"],
)

genrule(
    name = "derived_java_srcs",
    srcs = [
        "//src/main/protobuf:dist_jars",
        "//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:dist_jars",
        "//src/main/java/com/google/devtools/build/lib/bazel/debug:dist_jars",
        "//src/main/java/com/google/devtools/build/lib/packages/metrics:dist_jars",
        "//src/main/java/com/google/devtools/build/lib/skyframe/proto:dist_jars",
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/proto:dist_jars",
        "//src/main/java/com/google/devtools/build/skyframe:dist_jars",
        "@remoteapis//:dist_jars",
    ],
    outs = ["derived_java_srcs.zip"],
    cmd = "$(location :combine_derived_java_srcs.sh) $(JAVABASE) $@ $(SRCS)",
    toolchains = ["@rules_java//toolchains:current_host_java_runtime"],
    tools = ["combine_derived_java_srcs.sh"],
    visibility = ["//:__pkg__"],
)

# config_setting alias left here because bazel-only third_party code is pointing to it
alias(
    name = "windows",
    actual = "//src/conditions:windows",
    visibility = ["//visibility:public"],
)

sh_binary(
    name = "zip_files",
    srcs = ["zip_files.sh"],
    visibility = ["//visibility:public"],
)

sh_binary(
    name = "merge_zip_files",
    srcs = ["merge_zip_files.sh"],
    visibility = ["//visibility:public"],
)

# The java_tools release process produces following zip files:
#  - java_tools_dist.zip: all sources (Java and C++)
#  - java_tools.zip: platform independent part - Java classes (deploy jars) and C++ sources
#  - java_tools_prebuilt.zip: C++ binaries (for platforms: Linux, Darwin and Windows)
#
# The files are then used in Bazel as @remote_java_tools and @remote_java_tools_{platform}
# repositories.
# The zip files are not embedded or used in Bazel.

# Following target build java_tools_dist.zip - the sources
genrule(
    name = "java_tools_dist",
    srcs = [
        "//src/java_tools/buildjar:srcs",
        "//src/java_tools/junitrunner:srcs",
        "//src/java_tools/singlejar:srcs",
        "//src/tools/one_version:embedded_java_tools",
        "//src/tools/singlejar:embedded_java_tools",
        "//third_party/ijar:transitive_sources",
        "//third_party/java/jacoco:transitive_sources",
        "//third_party/java/proguard:srcs",
    ],
    outs = ["java_tools_dist.zip"],
    cmd = "zip -qXr $@ $$(echo $(SRCS) | sort)",
    output_to_bindir = 1,
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

# Following targets build java_tools.zip - platform independent part of java_tools
JAVA_TOOLS_DEPLOY_JARS = [
    "//src/java_tools/buildjar:JavaBuilder_deploy.jar",
    "//src/java_tools/buildjar:VanillaJavaBuilder_deploy.jar",
    "//src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:GenClass_deploy.jar",
    "//src/java_tools/buildjar/java/com/google/devtools/build/java/turbine:turbine_direct_binary_deploy.jar",
    "//src/java_tools/junitrunner/java/com/google/testing/coverage:JacocoCoverage_jarjar_deploy.jar",
    "//src/java_tools/junitrunner/java/com/google/testing/junit/runner:Runner_deploy.jar",
]

release_archive(
    name = "jars_java_tools_zip",
    srcs = JAVA_TOOLS_DEPLOY_JARS,
    package_dir = "java_tools",
    visibility = ["//visibility:private"],
)

release_archive(
    name = "java_tools_zip",
    src_map = {
        "//tools/jdk:BUILD.java_tools": "BUILD",
    },
    visibility = ["//src/test/shell/bazel:__pkg__"],
    deps = [
        ":jars_java_tools_zip",
        "//src/tools/one_version:one_version_transitive_zip",
        "//src/tools/singlejar:singlejar_transitive_zip",
        "//third_party/ijar:ijar_transitive_srcs_zip",
        "//third_party/java/jacoco:jacoco_jars_zip",
        "//third_party/java/proguard:proguard_zip",
    ],
)

transition_java_language_8_archive(
    name = "java_tools_java8.zip",
    archive_zip = ":java_tools.zip",
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

release_archive(
    name = "turbine_direct_graal_zip",
    srcs = ["//src/java_tools/buildjar/java/com/google/devtools/build/java/turbine:turbine_direct_graal"],
    package_dir = "java_tools",
    visibility = ["//visibility:private"],
)

# Following target builds java_tools_prebuilt.zip part of java_tools
release_archive(
    name = "java_tools_prebuilt_zip",
    src_map = {
        "//tools/jdk:BUILD.java_tools_prebuilt": "BUILD",
    },
    visibility = ["//src/test/shell/bazel:__pkg__"],
    deps = [
        ":turbine_direct_graal_zip",
        "//src/tools/one_version:one_version_deploy_zip",
        "//src/tools/singlejar:singlejar_deploy_zip",
        "//third_party/ijar:ijar_deploy_zip",
    ],
)

transition_java_language_8_archive(
    name = "java_tools_prebuilt_java8.zip",
    archive_zip = ":java_tools_prebuilt.zip",
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

# Following targets used by the java_tools_binaries Buildkite pipeline to upload
# the java_tools_*.zip to either tmp/sources or tmp/build directories in GCS.
sh_binary(
    name = "upload_java_tools_dist",
    srcs = ["upload_java_tools.sh"],
    args = [
        "--java_tools_zip",
        "src/java_tools_dist.zip",
        "--gcs_java_tools_dir",
        "tmp/sources",
    ],
    data = [":java_tools_dist"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_binary(
    name = "upload_java_tools",
    srcs = ["upload_java_tools.sh"],
    args = [
        "--java_tools_zip",
        "src/java_tools.zip",
        "--gcs_java_tools_dir",
        "tmp/build",
    ],
    data = [":java_tools_zip"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

sh_binary(
    name = "upload_java_tools_prebuilt",
    srcs = ["upload_java_tools.sh"],
    args = [
        "--java_tools_zip",
        "src/java_tools_prebuilt.zip",
        "--gcs_java_tools_dir",
        "tmp/build",
        "--platform",
    ] + select({
        "//src/conditions:darwin_x86_64": ["darwin_x86_64"],
        "//src/conditions:darwin_arm64": ["darwin_arm64"],
        "//src/conditions:windows": ["windows"],
        "//src/conditions:linux": ["linux"],
        "//conditions:default": ["unknown"],
    }),
    data = [":java_tools_prebuilt_zip"],
    deps = ["@bazel_tools//tools/bash/runfiles"],
)

# Part of the Java tools remote archive. Not embedded or used in Bazel.
release_archive(
    name = "zlib_zip",
    srcs = ["//third_party/zlib:embedded_tools"],
    package_dir = "zlib",
    visibility = [
        "//src/tools/singlejar:__pkg__",
        "//third_party/ijar:__pkg__",
    ],
)

JDK_VERSIONS = [
    "11",
    "17",
    "21",
]

filegroup(
    name = "test_repos",
    srcs = [
        requirement("bazel-runfiles"),  # Fetching the hermetic Python toolchain.
        "@bazel_skylib//:WORKSPACE",
        "@com_google_protobuf//:LICENSE",
        "@remote_coverage_tools//:all_lcov_merger_lib",
        "@remote_java_tools//:GenClass",
        "@remote_java_tools_darwin_arm64//:java_tools/src/tools/singlejar/singlejar_local",
        "@remote_java_tools_darwin_x86_64//:java_tools/src/tools/singlejar/singlejar_local",
        "@remote_java_tools_linux//:java_tools/src/tools/singlejar/singlejar_local",
        "@remote_java_tools_windows//:java_tools/src/tools/singlejar/singlejar_local.exe",
        "@rules_cc//:LICENSE",
        "@rules_java//:WORKSPACE",
        "@rules_license//:WORKSPACE",
        "@rules_pkg//:WORKSPACE",
        "@rules_python//:WORKSPACE",
        "@rules_shell//shell:rules_bzl",
        "@rules_testing//:LICENSE",
    ] + select({
        "//src/conditions:linux_x86_64": ["@remotejdk%s_linux//:WORKSPACE" % v for v in JDK_VERSIONS],
        "//src/conditions:darwin_x86_64": ["@remotejdk%s_macos//:WORKSPACE" % v for v in JDK_VERSIONS],
        "//src/conditions:darwin_arm64": ["@remotejdk%s_macos_aarch64//:WORKSPACE" % v for v in JDK_VERSIONS],
        "//src/conditions:windows_x86_64": ["@remotejdk%s_win//:WORKSPACE" % v for v in JDK_VERSIONS],
        "//conditions:default": [],
    }) + [
        "@bazel_tools_repo_cache//:files",
        "@workspace_repo_cache//:files",
    ],
)

bzl_library(
    name = "build_defs_bzl",
    srcs = ["build_defs.bzl"],
)

java_binary(
    name = "CheckSunJnuEncoding",
    srcs = ["CheckSunJnuEncoding.java"],
    env = {"LC_CTYPE": "C.UTF-8"},
    main_class = "CheckSunJnuEncoding",
)
