# Bazel - Google's Build System

load("@bazel_skylib//rules:write_file.bzl", "write_file")
load("@rules_license//rules:license.bzl", "license")
load("@rules_pkg//pkg:mappings.bzl", "pkg_attributes", "pkg_files")
load("@rules_pkg//pkg:tar.bzl", "pkg_tar")
load("@rules_python//python:defs.bzl", "py_binary")
load("//src/tools/bzlmod:utils.bzl", "get_canonical_repo_name")
load("//tools/distributions:distribution_rules.bzl", "distrib_jar_filegroup")

package(default_visibility = ["//scripts/release:__pkg__"])

license(
    name = "license",
    package_name = "bazelbuild/bazel",
    copyright_notice = "Copyright © 2014 The Bazel Authors. All rights reserved.",
    license_kinds = [
        "@rules_license//licenses/spdx:Apache-2.0",
    ],
    license_text = "LICENSE",
)

exports_files([
    "LICENSE",
    "MODULE.bazel.lock",
])

filegroup(
    name = "srcs",
    srcs = glob(
        ["*"],
        exclude = [
            "MODULE.bazel.lock",  # Use MODULE.bazel.lock.dist instead
            "bazel-*",  # convenience symlinks
            "out",  # IntelliJ with setup-intellij.sh
            "output",  # output of compile.sh
            ".*",  # mainly .git* files
        ],
    ) + [
        "//:MODULE.bazel.lock.dist",
        "//examples:srcs",
        "//scripts:srcs",
        "//site:srcs",
        "//src:srcs",
        "//src/main/java/com/google/devtools/build/docgen/release:srcs",
        "//src/main/starlark/tests/builtins_bzl:srcs",
        "//third_party:srcs",
        "//tools:srcs",
    ] + glob([".bazelci/*"]) + [
        ".bazelrc",
        ".bazelversion",
    ],
    applicable_licenses = ["@io_bazel//:license"],
    visibility = ["//src/test/shell/bazel:__pkg__"],
)

filegroup(
    name = "dummy",
    visibility = ["//visibility:public"],
)

filegroup(
    name = "workspace-deps-bzl",
    srcs = [
        ":workspace_deps.bzl",
    ],
    visibility = [
        "//src/test/shell/bazel:__subpackages__",
    ],
)

filegroup(
    name = "changelog-file",
    srcs = [":CHANGELOG.md"],
    visibility = [
        "//scripts/packages:__subpackages__",
    ],
)

genrule(
    name = "generate_dist_lockfile",
    srcs = [
        "MODULE.bazel",
        "//third_party/remoteapis:MODULE.bazel",
        "//third_party:BUILD",
        "//third_party:rules_jvm_external_6.5.patch",
        "//third_party:rules_graalvm_fix.patch",
        "//third_party:rules_graalvm_unicode.patch",
    ],
    outs = ["MODULE.bazel.lock.dist"],
    cmd = " && ".join([
        "ROOT=$$PWD",
        "TMPDIR=$$(mktemp -d)",
        "trap 'rm -rf $$TMPDIR' EXIT",
        "mkdir -p $$TMPDIR/workspace",
        "touch $$TMPDIR/workspace/BUILD.bazel",
        "for i in $(SRCS); do dir=$$TMPDIR/workspace/$$(dirname $$i); mkdir -p $$dir; cp $$i $$dir; done",
        "cd $$TMPDIR/workspace",
        # Instead of `bazel mod deps`, we run a simpler command like `bazel query :all` here
        # so that we only trigger module resolution, not extension eval.
        # Also use `--batch` so that Bazel doesn't keep a server process alive.
        "$$ROOT/$(location //src:bazel) --batch --output_user_root=$$TMPDIR/output_user_root query --check_direct_dependencies=error --lockfile_mode=update :all",
        "mv MODULE.bazel.lock $$ROOT/$@",
    ]),
    tags = ["requires-network"],
    tools = ["//src:bazel"],
)

pkg_tar(
    name = "bootstrap-jars",
    srcs = [
        "//third_party/googleapis:dist_jars",
        "//third_party/grpc-java:grpc_jars",
        "@async_profiler//file",
        "@com_google_protobuf//:protobuf_java",
        "@com_google_protobuf//:protobuf_java_util",
        "@com_google_protobuf//:protobuf_javalite",
        "@zstd-jni//:zstd-jni",
    ],
    package_dir = "derived/jars",
    remap_paths = {
        "external/": "",
        "../": "",
    },
    strip_prefix = ".",
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

distrib_jar_filegroup(
    name = "bootstrap-derived-java-jars",
    srcs = glob(
        ["derived/jars/**/*.jar"],
        allow_empty = True,
    ),
    enable_distributions = ["debian"],
    visibility = ["//:__subpackages__"],
)

filegroup(
    name = "bootstrap-derived-java-srcs",
    srcs = glob(
        ["derived/**/*.java"],
        allow_empty = True,
    ),
    visibility = ["//:__subpackages__"],
)

# Additional generated files that are not Java sources (which could otherwise
# be included in //src:derived_java_sources).
filegroup(
    name = "generated_resources",
    srcs = [
        "//src/main/java/com/google/devtools/build/lib/bazel/rules:builtins_bzl.zip",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules:coverage.WORKSPACE",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules:rules_suffix.WORKSPACE",
        "//src/main/java/com/google/devtools/build/lib/bazel/rules/cpp:cc_configure.WORKSPACE",
    ],
)

# Bazel sources excluding files that are not needed in the distfile.
pkg_files(
    name = "dist-srcs",
    srcs = ["//:srcs"],
    attributes = pkg_attributes(mode = "0755"),
    excludes = [
        "//examples:srcs",
        "//site:srcs",
        "//src:srcs-to-exclude-in-distfile",
    ],
    renames = {
        "MODULE.bazel.lock.dist": "MODULE.bazel.lock",
    },
    strip_prefix = "/",  # Ensure paths are relative to the workspace root.
)

pkg_tar(
    name = "bazel-srcs",
    srcs = [
        ":dist-srcs",
        ":generated_resources",
    ],
    strip_prefix = ".",
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

pkg_tar(
    name = "platforms-srcs",
    srcs = ["@platforms//:srcs"],
    remap_paths = {
        "external/": "",
        "../": "",
    },
    strip_prefix = ".",
    visibility = ["//:__subpackages__"],
)

write_file(
    name = "gen_maven_repo_name",
    out = "MAVEN_CANONICAL_REPO_NAME",
    content = [get_canonical_repo_name("@maven")],
)

# The @maven repository is created by maven_install from rules_jvm_external.
# `@maven//:srcs` contains all jar files downloaded and BUILD files created by maven_install.
pkg_tar(
    name = "maven-srcs",
    srcs = ["@maven//:srcs"] + ["MAVEN_CANONICAL_REPO_NAME"],
    package_dir = "derived/maven",
    remap_paths = {
        "external/" + get_canonical_repo_name("@maven") + "/": "",
        "../" + get_canonical_repo_name("@maven") + "/": "",
    },
    strip_prefix = ".",
    visibility = ["//:__subpackages__"],
)

exports_files(
    ["maven_install.json"],
    visibility = ["//tools/compliance:__pkg__"],
)

py_binary(
    name = "combine_distfiles",
    srcs = ["combine_distfiles.py"],
    visibility = ["//visibility:private"],
    deps = ["//src:create_embedded_tools_lib"],
)

genrule(
    name = "bazel-distfile",
    srcs = [
        ":bazel-srcs",
        ":bootstrap-jars",
        ":maven-srcs",
        "//src:derived_java_srcs",
        "@bootstrap_repo_cache//:archives.tar",
    ],
    outs = ["bazel-distfile.zip"],
    cmd = "$(location :combine_distfiles) $@ $(SRCS)",
    tools = [":combine_distfiles"],
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

genrule(
    name = "bazel-distfile-tar",
    srcs = [
        ":bazel-srcs",
        ":bootstrap-jars",
        ":platforms-srcs",
        ":maven-srcs",
        "//src:derived_java_srcs",
        "@bootstrap_repo_cache//:archives.tar",
    ],
    outs = ["bazel-distfile.tar"],
    cmd = "$(location :combine_distfiles_to_tar.sh) $@ $(SRCS)",
    tools = ["combine_distfiles_to_tar.sh"],
    # Public but bazel-only visibility.
    visibility = ["//:__subpackages__"],
)

constraint_setting(name = "machine_size")

# A machine with "high cpu count".
constraint_value(
    name = "highcpu_machine",
    constraint_setting = ":machine_size",
    visibility = ["//visibility:public"],
)

platform(
    name = "default_host_platform",
    constraint_values = [
        ":highcpu_machine",
    ],
    parents = ["@platforms//host"],
)

platform(
    name = "windows_arm64",
    constraint_values = [
        "@platforms//os:windows",
        "@platforms//cpu:arm64",
    ],
)

REMOTE_PLATFORMS = ("rbe_ubuntu2004",)

[
    platform(
        name = platform_name + "_platform",
        exec_properties = {
            "dockerNetwork": "standard",
            "dockerPrivileged": "true",
            "Pool": "default",
        },
        parents = ["@" + platform_name + "//config:platform"],
    )
    for platform_name in REMOTE_PLATFORMS
]

[
    # The highcpu RBE platform where heavy actions run on. In order to
    # use this platform add the highcpu_machine constraint to your target.
    platform(
        name = platform_name + "_highcpu_platform",
        constraint_values = [
            "//:highcpu_machine",
        ],
        exec_properties = {
            "Pool": "highcpu",
        },
        parents = ["//:" + platform_name + "_platform"],
    )
    for platform_name in REMOTE_PLATFORMS
]
