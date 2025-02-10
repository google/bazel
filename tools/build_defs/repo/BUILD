load("@bazel_skylib//:bzl_library.bzl", "bzl_library")
load("@io_bazel_skydoc//stardoc:html_tables_stardoc.bzl", "html_tables_stardoc")
load("@rules_pkg//:pkg.bzl", "pkg_tar")

licenses(["notice"])  # Apache 2.0

exports_files(glob(["*.bzl"]))

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//tools/build_defs:__pkg__"],
)

filegroup(
    name = "mock_srcs",
    srcs = [
        "cache.bzl",
        "http.bzl",
        "local.bzl",
        "utils.bzl",
    ],
    visibility = ["//src/test/py/bazel:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = glob(["*.bzl"]) + ["BUILD.repo"],
    visibility = ["//tools/build_defs:__pkg__"],
)

bzl_library(
    name = "lib",
    srcs = glob(["*.bzl"]),
)

genrule(
    name = "empty",
    outs = ["empty.bzl"],
    cmd = "touch $@",
)

# This seemingly useless rule is needed to work around some Google-internal linter issues.
genrule(
    name = "index_md",
    srcs = [":index.md.tmpl"],
    outs = ["index.md"],
    cmd = "cat $< > $@",
)

REPO_BZL_FILES = [
    "cache",
    "git",
    "http",
    "local",
    "utils",
]

[html_tables_stardoc(
    name = "%s_raw_md" % (name,),
    out = "%s_raw.md" % (name,),
    input = "%s.bzl" % (name,),
    deps = [":lib"],
) for name in REPO_BZL_FILES]

[genrule(
    name = "preamb_%s_md" % (name,),
    srcs = [":preamb.md.tmpl"],
    outs = ["preamb_%s.md" % (name,)],
    cmd = "sed 's/BZL_FILE_BASE_NAME/%s/g' < $< > $@" % (name,),
) for name in REPO_BZL_FILES]

[genrule(
    name = "%s_md" % (name,),
    srcs = [
        ":preamb_%s.md" % (name,),
        ":%s_raw_md" % (name,),
    ],
    outs = ["%s.md" % (name,)],
    cmd = "cat $(SRCS) > $@",
) for name in REPO_BZL_FILES]

pkg_tar(
    name = "doc",
    srcs = ["%s_md" % (name,) for name in REPO_BZL_FILES] + ["index.md"],
    strip_prefix = "/tools/build_defs/repo",
    visibility = [
        "//src/main/java/com/google/devtools/build/lib:__pkg__",
    ],
)
