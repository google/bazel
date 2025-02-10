load("@rules_java//java:defs.bzl", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = 0,
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "SleepPreventionModuleTest",
    timeout = "short",
    srcs = ["SleepPreventionModuleTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/platform:sleep_prevention_module",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SystemSuspensionEventTest",
    timeout = "short",
    srcs = ["SystemSuspensionEventTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/platform:system_suspension_module",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "SystemMemoryPressureEventTest",
    timeout = "short",
    srcs = ["SystemMemoryPressureEventTest.java"],
    data = select({
        "//src/conditions:darwin": [
            "//src/test/java/com/google/devtools/build/lib/platform/darwin:notifier",
        ],
        "//conditions:default": [
        ],
    }),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/platform:system_memory_pressure_module",
        "//src/main/java/com/google/devtools/build/lib/platform:system_memory_pressure_monitor",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_test(
    name = "SystemThermalEventTest",
    timeout = "short",
    srcs = ["SystemThermalEventTest.java"],
    data = select({
        "//src/conditions:darwin": [
            "//src/test/java/com/google/devtools/build/lib/platform/darwin:notifier",
        ],
        "//conditions:default": [
        ],
    }),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/platform:system_thermal_module",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_test(
    name = "SystemDiskSpaceEventTest",
    timeout = "short",
    srcs = ["SystemDiskSpaceEventTest.java"],
    data = select({
        "//src/conditions:darwin": [
            "//src/test/java/com/google/devtools/build/lib/platform/darwin:notifier",
        ],
        "//conditions:default": [
        ],
    }),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/platform:system_disk_space_module",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)

java_test(
    name = "SystemCPUSpeedEventTest",
    timeout = "short",
    srcs = ["SystemCPUSpeedEventTest.java"],
    data = select({
        "//src/conditions:darwin": [
            "//src/test/java/com/google/devtools/build/lib/platform/darwin:notifier",
        ],
        "//conditions:default": [
        ],
    }),
    deps = [
        "//src/main/java/com/google/devtools/build/lib:runtime",
        "//src/main/java/com/google/devtools/build/lib/platform:system_cpu_speed_module",
        "//src/main/java/com/google/devtools/build/lib/util:os",
        "//src/test/java/com/google/devtools/build/lib/buildtool/util",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "@rules_java//java/runfiles",
    ],
)
