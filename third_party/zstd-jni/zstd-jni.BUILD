genrule(
    name = "copy_link_jni_md_header",
    srcs = select({
        "@bazel_tools//src/conditions:darwin": ["@rules_java//toolchains:jni_md_header-darwin"],
        "@bazel_tools//src/conditions:freebsd": ["@rules_java//toolchains:jni_md_header-freebsd"],
        "@bazel_tools//src/conditions:openbsd": ["@rules_java//toolchains:jni_md_header-openbsd"],
        "@bazel_tools//src/conditions:windows": ["@rules_java//toolchains:jni_md_header-windows"],
        "//conditions:default": ["@rules_java//toolchains:jni_md_header-linux"],
    }),
    outs = ["jni_md.h"],
    cmd = "cp -f $< $@",
)

genrule(
    name = "copy_link_jni_header",
    srcs = ["@rules_java//toolchains:jni_header"],
    outs = ["jni.h"],
    cmd = "cp -f $< $@",
)

cc_binary(
    name = "libzstd-jni.so",
    srcs = glob([
        "src/main/native/**/*.c",
        "src/main/native/**/*.h",
    ]) + [
        ":jni_md.h",
        ":jni.h",
    ] + select({
        "@bazel_tools//src/conditions:windows": [],
        "//conditions:default": glob(["src/main/native/**/*.S"]),
    }),
    copts = select({
        "@bazel_tools//src/conditions:windows": [],
        "@bazel_tools//src/conditions:darwin": [
            "-std=c99",
            "-Wno-unused-variable",
            "-Wno-sometimes-uninitialized",
        ],
        "//conditions:default": [
            "-std=c99",
            "-Wno-unused-variable",
            "-Wno-maybe-uninitialized",
            "-Wno-sometimes-uninitialized",
        ]
    }),
    linkshared = 1,
    includes = [
        ".",  # For jni headers.
        "src/main/native",
        "src/main/native/common",
    ],
    local_defines = [
        "ZSTD_LEGACY_SUPPORT=4",
        "ZSTD_MULTITHREAD=1",
    ] + select({
        "@bazel_tools//src/conditions:windows": ["_JNI_IMPLEMENTATION_"],
        "//conditions:default": [],
    }),
)


genrule(
    name = "version-java",
    cmd_bash = 'echo "package com.github.luben.zstd.util;\n\npublic class ZstdVersion {\n\tpublic static final String VERSION = \\"$$(cat $<)\\";\n}" > $@',
    cmd_ps = '$$PSDefaultParameterValues.Remove("*:Encoding"); $$version = (Get-Content $<) -join ""; Set-Content -NoNewline -Path $@ -Value "package com.github.luben.zstd.util;\n\npublic class ZstdVersion {\n\tpublic static final String VERSION = `"$${version}`";\n}\n"',
    srcs = ["version"],
    outs = ["ZstdVersion.java"],
)

java_library(
    name = "zstd-jni",
    srcs = glob([
        "src/main/java/**/*.java",
    ]) + [
        ":version-java",
    ],
    resources = [":libzstd-jni.so"],
    visibility = [
        "//visibility:public",
    ],
)
