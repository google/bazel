package(default_visibility = ["//visibility:public"])

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "prebuilt_one_version",
    srcs = glob(
        [
            "java_tools/src/tools/one_version/one_version_main",
            "java_tools/src/tools/one_version/one_version_main.exe",
        ],
        allow_empty = True,
    ) or fail("No one_version binary found"),
)

filegroup(
    name = "prebuilt_singlejar",
    srcs = glob(
        [
            "java_tools/src/tools/singlejar/singlejar_local",
            "java_tools/src/tools/singlejar/singlejar_local.exe",
        ],
        allow_empty = True,
    ) or fail("No singlejar binary found"),
)

filegroup(
    name = "ijar_prebuilt_binary",
    srcs = glob(
        [
            "java_tools/ijar/ijar",
            "java_tools/ijar/ijar.exe",
        ],
        allow_empty = True,
    ) or fail("No ijar binary found"),
)

filegroup(
    name = "turbine_direct_graal",
    srcs = glob(
        [
            "java_tools/turbine_direct_graal",
            "java_tools/turbine_direct_graal.exe",
        ],
        allow_empty = True,
    ) or fail("No turbine_direct_graal binary found"),
)
