load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")
load("//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps:tests.bzl", "create_golden_test")

# Description:
#   Tests for the checker to check the completeness of the deps of java_import or aar_import targets.
package(
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    testonly = False,
    srcs = glob(["**"]) + ["//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:srcs"],
)

java_test(
    name = "ClassInfoTest",
    srcs = ["ClassInfoTest.java"],
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:import_deps_checker",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ResultCollectorTest",
    srcs = ["ResultCollectorTest.java"],
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:import_deps_checker",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "LazyClassEntryStateTest",
    srcs = ["LazyClassEntryStateTest.java"],
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:import_deps_checker",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "ClassCacheTest",
    srcs = [
        "AbstractClassCacheTest.java",
        "ClassCacheTest.java",
    ],
    data = [
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_client",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryAnnotations",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryException",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryInterface",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryModuleInfo",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library_no_members",
        "@rules_java//toolchains:platformclasspath",
    ],
    jvm_flags = [
        "-Dclasscache.test.bootclasspath=$(location @rules_java//toolchains:platformclasspath)",
        "-Dclasscache.test.Client=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_client)",
        "-Dclasscache.test.Library=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library)",
        "-Dclasscache.test.Library_no_members=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library_no_members)",
        "-Dclasscache.test.LibraryAnnotations=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryAnnotations)",
        "-Dclasscache.test.LibraryException=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryException)",
        "-Dclasscache.test.LibraryInterface=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryInterface)",
        "-Dclasscache.test.LibraryModuleInfo=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryModuleInfo)",
    ],
    test_class = "com.google.devtools.build.importdeps.ClassCacheTest",
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:import_deps_checker",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "DepsCheckerClassVisitorTest",
    srcs = [
        "AbstractClassCacheTest.java",
        "DepsCheckerClassVisitorTest.java",
    ],
    data = [
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_client",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryAnnotations",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryException",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryInterface",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryModuleInfo",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library_no_members",
        "@rules_java//toolchains:platformclasspath",
    ],
    jvm_flags = [
        "-Dclasscache.test.bootclasspath=$(location @rules_java//toolchains:platformclasspath)",
        "-Dclasscache.test.Client=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_client)",
        "-Dclasscache.test.Library=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library)",
        "-Dclasscache.test.Library_no_members=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_Library_no_members)",
        "-Dclasscache.test.LibraryAnnotations=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryAnnotations)",
        "-Dclasscache.test.LibraryException=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryException)",
        "-Dclasscache.test.LibraryInterface=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryInterface)",
        "-Dclasscache.test.LibraryModuleInfo=$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:testdata_lib_LibraryModuleInfo)",
    ],
    test_class = "com.google.devtools.build.importdeps.DepsCheckerClassVisitorTest",
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:import_deps_checker",
        "//third_party:asm",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

create_golden_test(
    name = "bootclasspath_missing_golden_test",
    check_missing = True,
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_bootclasspath_missing.txt",
    golden_stderr_file = "golden_bootclasspath_missing.stderr.txt",
    has_bootclasspath = False,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = None,
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "warn_on_bootclasspath_missing_golden_test",
    check_missing = True,
    checking_mode = "warning",
    expect_errors = False,
    golden_output_file = "golden_warn_on_bootclasspath_missing.txt",
    golden_stderr_file = "golden_bootclasspath_missing.stderr.warning.txt",
    has_bootclasspath = False,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = None,
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "silent_on_bootclasspath_missing_golden_test",
    checking_mode = "silence",
    expect_errors = False,
    golden_output_file = "golden_silent_on_bootclasspath_missing_golden_test.txt",
    golden_stderr_file = "golden_complete_classpath.stderr.txt",
    has_bootclasspath = False,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = None,
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_exception_missing_golden_test",
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_library_exception_missing.txt",
    golden_stderr_file = "golden_library_exception_missing.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_LibraryException",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_annotation_missing_golden_test",
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_library_annotation_missing.txt",
    golden_stderr_file = "golden_library_annotation_missing.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_LibraryAnnotations",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_missing_golden_test",
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_library_missing.txt",
    golden_stderr_file = "golden_library_missing.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_Library",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_interface_missing_golden_test",
    check_missing = True,
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_library_interface_missing.txt",
    golden_stderr_file = "golden_library_interface_missing.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_LibraryInterface",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_members_missing_golden_test",
    check_missing = True,
    checking_mode = "error",
    expect_errors = True,
    golden_output_file = "golden_library_members_missing.txt",
    golden_stderr_file = "golden_library_members_missing.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_Library",
    replacing_jar = "testdata_lib_Library_no_members",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "library_members_missing_unchecked_golden_test",
    check_missing = False,
    checking_mode = "error",
    expect_errors = False,
    golden_output_file = "golden_missing_member_unchecked.txt",
    golden_stderr_file = "golden_complete_classpath.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = "testdata_lib_Library",
    replacing_jar = "testdata_lib_Library_no_members",
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

create_golden_test(
    name = "complete_classpath_golden_test",
    checking_mode = "error",
    expect_errors = False,
    golden_output_file = "golden_complete_classpath.txt",
    golden_stderr_file = "golden_complete_classpath.stderr.txt",
    has_bootclasspath = True,
    import_deps_checker = "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
    missing_jar = None,
    replacing_jar = None,
    rt_jar = "@rules_java//toolchains:platformclasspath",
    testdata_pkg = "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata",
)

java_test(
    name = "MainTest",
    srcs = ["MainTest.java"],
    deps = [
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_library(
    name = "empty",
    srcs = [],
)

sh_test(
    name = "string_annotation_value_test",
    srcs = ["golden_test.sh"],
    args = [
        "$(location golden_string_annotation_value_test.txt)",
        "$(location golden_string_annotation_value_test.stderr.txt)",
        "199",
        "$(location :DumpProto)",
        "$(location //src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker)",
        "--checking_mode=error",
        "--bootclasspath_entry",
        "$(location :libempty.jar)",
        "--classpath_entry",
        "$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libStringAnnotation.jar)",
        "--input",
        "$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libOneAnnotationValue.jar)",
    ],
    data = [
        ":DumpProto",
        ":libempty.jar",
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps:golden_string_annotation_value_test.stderr.txt",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps:golden_string_annotation_value_test.txt",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libOneAnnotationValue.jar",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libStringAnnotation.jar",
    ],
)

sh_test(
    name = "invoke_polymorphic_test",
    srcs = ["golden_test.sh"],
    args = [
        "$(location golden_invoke_polymorphic_test.txt)",
        "$(location golden_invoke_polymorphic_test.stderr.txt)",
        "0",
        "$(location :DumpProto)",
        "$(location //src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker)",
        "--checking_mode=error",
        "--bootclasspath_entry",
        "$(location @rules_java//toolchains:platformclasspath)",
        "--input",
        "$(location //src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libInvokePolymorphic.jar)",
    ],
    data = [
        ":DumpProto",
        ":libempty.jar",
        "//src/java_tools/import_deps_checker/java/com/google/devtools/build/importdeps:ImportDepsChecker",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps:golden_invoke_polymorphic_test.stderr.txt",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps:golden_invoke_polymorphic_test.txt",
        "//src/java_tools/import_deps_checker/javatests/com/google/devtools/build/importdeps/testdata:libInvokePolymorphic.jar",
        "@rules_java//toolchains:platformclasspath",
    ],
)

java_binary(
    name = "DumpProto",
    srcs = ["DumpProto.java"],
    deps = ["//src/main/protobuf:deps_java_proto"],
)
