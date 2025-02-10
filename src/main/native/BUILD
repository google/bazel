genrule(
    name = "copy_link_jni_md_header",
    srcs = select({
        "//src/conditions:darwin": ["@rules_java//toolchains:jni_md_header-darwin"],
        "//src/conditions:freebsd": ["@rules_java//toolchains:jni_md_header-freebsd"],
        "//src/conditions:openbsd": ["@rules_java//toolchains:jni_md_header-openbsd"],
        "//src/conditions:windows": ["@rules_java//toolchains:jni_md_header-windows"],
        "//conditions:default": ["@rules_java//toolchains:jni_md_header-linux"],
    }),
    outs = ["jni_md.h"],
    cmd = "cp -f $< $@",
    visibility = ["//src/main/native:__subpackages__"],
)

genrule(
    name = "copy_link_jni_header",
    srcs = ["@rules_java//toolchains:jni_header"],
    outs = ["jni.h"],
    cmd = "cp -f $< $@",
    visibility = ["//src/main/native:__subpackages__"],
)

filegroup(
    name = "jni_os",
    srcs = select({
        "//src/conditions:darwin": [
            "darwin/file_jni.cc",
            "darwin/fsevents.cc",
            "darwin/sleep_prevention_jni.cc",
            "darwin/system_cpu_speed_monitor_jni.cc",
            "darwin/system_disk_space_monitor_jni.cc",
            "darwin/system_load_advisory_monitor_jni.cc",
            "darwin/system_memory_pressure_jni.cc",
            "darwin/system_network_stats.cc",
            "darwin/system_suspension_monitor_jni.cc",
            "darwin/system_thermal_monitor_jni.cc",
            "darwin/util.cc",
            "darwin/util.h",
        ],
        "//src/conditions:freebsd": ["unix_jni_bsd.cc"],
        "//src/conditions:openbsd": ["unix_jni_bsd.cc"],
        "//conditions:default": ["unix_jni_linux.cc"],
    }),
)

cc_library(
    name = "latin1_jni_path",
    srcs = [
        "latin1_jni_path.cc",
        ":jni.h",
        ":jni_md.h",
    ],
    hdrs = ["latin1_jni_path.h"],
    includes = ["."],  # For jni headers.
    deps = [
        "//src/main/cpp/util:logging",
    ],
)

cc_library(
    name = "blake3_jni",
    srcs = [
        "blake3_jni.cc",
        ":jni.h",
        ":jni_md.h",
    ],
    includes = ["."],  # For jni headers.
    visibility = ["//src/main/native:__subpackages__"],
    deps = [
        "@blake3",
    ],
    alwayslink = 1,
)

cc_binary(
    name = "libunix_jni.so",
    srcs = [
        "macros.h",
        "process.cc",
        "unix_jni.cc",
        "unix_jni.h",
        ":jni.h",
        ":jni_md.h",
        ":jni_os",
    ],
    copts = [
        "-fPIC",
        "-DBLAZE_JAVA_CPU=\"k8\"",
    ],
    includes = ["."],  # For jni headers.
    linkopts = select({
        "//src/conditions:darwin": [
            "-Wl,-framework,CoreServices",
            "-Wl,-framework,IOKit",
        ],
        "//conditions:default": [],
    }),
    linkshared = 1,
    visibility = ["//src/main/java/com/google/devtools/build/lib/jni:__pkg__"],
    deps = [
        ":blake3_jni",
        ":latin1_jni_path",
        "//src/main/cpp/util:logging",
        "//src/main/cpp/util:md5",
        "//src/main/cpp/util:port",
    ],
)

# TODO(bazel-team): Come up with a way to support platform-specific dynamic
# library extensions.  This is issue #914.
genrule(
    name = "mac-compat",
    srcs = ["libunix_jni.so"],
    outs = ["libunix_jni.dylib"],
    cmd = "cp $< $@",
    output_to_bindir = 1,
    visibility = ["//src/main/java/com/google/devtools/build/lib/jni:__pkg__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//src/main/native/windows:srcs",
    ],
    visibility = ["//src:__pkg__"],
)

filegroup(
    name = "embedded_tools",
    srcs = [
        "BUILD",
        "//src/main/native/windows:embedded_tools",
    ],
    visibility = ["//visibility:public"],
)
