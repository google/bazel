load("@rules_java//java:defs.bzl", "java_import", "java_library", "java_plugin")
load("@rules_license//rules:license.bzl", "license")
load("//tools/distributions:distribution_rules.bzl", "distrib_jar_filegroup", "distrib_java_import")
load(":compiler_config_setting.bzl", "create_compiler_config_setting")

package(default_visibility = ["//visibility:public"])

filegroup(
    name = "srcs",
    srcs = glob(["**"]) + [
        "//third_party/allocation_instrumenter:srcs",
        "//third_party/android_dex:srcs",
        "//third_party/cncf_udpa:srcs",
        "//third_party/def_parser:srcs",
        "//third_party/googleapis:srcs",
        "//third_party/grpc:srcs",
        "//third_party/grpc-java:srcs",
        "//third_party/ijar:srcs",
        "//third_party/jarjar:srcs",
        "//third_party/java/android_databinding:srcs",
        "//third_party/java/aosp_gradle_core:srcs",
        "//third_party/java/j2objc:srcs",
        "//third_party/java/j2objc-annotations:srcs",
        "//third_party/java/jacoco:srcs",
        "//third_party/java/javapoet:srcs",
        "//third_party/java/jcommander:srcs",
        "//third_party/java/proguard:srcs",
        "//third_party/pprof:srcs",
        "//third_party/protobuf:srcs",
        "//third_party/protoc_gen_validate:srcs",
        "//third_party/py/abseil:srcs",
        "//third_party/py/concurrent:srcs",
        "//third_party/py/dataclasses:srcs",
        "//third_party/py/frozendict:srcs",
        "//third_party/py/mock:srcs",
        "//third_party/py/six:srcs",
        "//third_party/remoteapis:srcs",
        "//third_party/upb:srcs",
        "//third_party/zlib:srcs",
    ],
)

# Filegroup to ship the sources to the Bazel embededded tools
# This filegroup should contains all GPL with classpath exception
# and LGPL code that we use in Bazel.
filegroup(
    name = "gpl-srcs",
    srcs = [],
)

alias(
    name = "android-annotations",
    actual = "@maven_android//:com_android_tools_annotations",
)

alias(
    name = "apache_commons_collections",
    actual = "@maven//:commons_collections_commons_collections",
)

alias(
    name = "apache_commons_lang",
    actual = "@maven//:commons_lang_commons_lang",
)

alias(
    name = "apache_commons_compress",
    actual = "@maven//:org_apache_commons_commons_compress",
)

alias(
    name = "apache_commons_pool2",
    actual = "@maven//:org_apache_commons_commons_pool2",
)

alias(
    name = "apache_velocity",
    actual = "@maven//:org_apache_velocity_velocity",
)

java_library(
    name = "api_client",
    exports = [
        "@maven//:com_google_api_client_google_api_client",
        "@maven//:com_google_api_client_google_api_client_gson",
        "@maven//:com_google_http_client_google_http_client",
        "@maven//:com_google_http_client_google_http_client_gson",
    ],
    runtime_deps = [
        ":gson",
    ],
)

distrib_java_import(
    name = "asm",
    enable_distributions = ["debian"],
    jars = ["asm/asm-9.6.jar"],
    srcjar = "asm/asm-9.6-sources.jar",
)

java_import(
    name = "asm-analysis",
    jars = ["asm/asm-analysis-9.6.jar"],
    srcjar = "asm/asm-analysis-9.6-sources.jar",
    runtime_deps = [":asm-tree"],
)

java_import(
    name = "asm-commons",
    jars = ["asm/asm-commons-9.6.jar"],
    srcjar = "asm/asm-commons-9.6-sources.jar",
    runtime_deps = [":asm-tree"],
)

java_import(
    name = "asm-tree",
    jars = ["asm/asm-tree-9.6.jar"],
    srcjar = "asm/asm-tree-9.6-sources.jar",
    runtime_deps = [":asm"],
)

java_import(
    name = "asm-util",
    jars = ["asm/asm-util-9.6.jar"],
    srcjar = "asm/asm-util-9.6-sources.jar",
    runtime_deps = [":asm-tree"],
)

java_library(
    name = "auth",
    exports = [
        "@maven//:com_google_auth_google_auth_library_credentials",
        "@maven//:com_google_auth_google_auth_library_oauth2_http",
    ],
    runtime_deps = [
        ":api_client",
        ":guava",
    ],
)

java_plugin(
    name = "auto_annotation_plugin",
    processor_class = "com.google.auto.value.processor.AutoAnnotationProcessor",
    deps = [
        ":apache_commons_collections",
        ":apache_velocity",
        ":auto_common",
        ":auto_service_lib",
        ":auto_value_lib",
        ":guava",
        ":jsr305",
        ":tomcat_annotations_api",
    ],
)

java_plugin(
    name = "auto_builder_plugin",
    processor_class = "com.google.auto.value.processor.AutoBuilderProcessor",
    deps = [
        ":apache_commons_collections",
        ":apache_velocity",
        ":auto_common",
        ":auto_service_lib",
        ":auto_value_lib",
        ":guava",
        ":tomcat_annotations_api",
    ],
)

alias(
    name = "auto_common",
    actual = "@maven//:com_google_auto_auto_common",
)

java_library(
    name = "auto_service",
    exported_plugins = [
        ":auto_service_plugin",
    ],
    exports = [
        ":auto_service_api",
    ],
)

java_plugin(
    name = "auto_service_plugin",
    processor_class = "com.google.auto.service.processor.AutoServiceProcessor",
    deps = [
        ":auto_common",
        ":auto_service_lib",
        ":guava",
    ],
)

java_library(
    name = "auto_service_api",
    exports = [
        "@maven//:com_google_auto_service_auto_service_annotations",
    ],
)

java_library(
    name = "auto_service_lib",
    exports = [
        "@maven//:com_google_auto_service_auto_service",
        "@maven//:com_google_auto_service_auto_service_annotations",
    ],
)

java_plugin(
    name = "auto_value_plugin",
    processor_class = "com.google.auto.value.processor.AutoValueProcessor",
    deps = [
        ":apache_commons_collections",
        ":apache_velocity",
        ":auto_common",
        ":auto_service_lib",
        ":auto_value_lib",
        ":guava",
        ":tomcat_annotations_api",
    ],
)

java_plugin(
    name = "auto_oneof_plugin",
    processor_class = "com.google.auto.value.processor.AutoOneOfProcessor",
    deps = [
        ":apache_commons_collections",
        ":apache_velocity",
        ":auto_common",
        ":auto_service_lib",
        ":auto_value_lib",
        ":guava",
        ":tomcat_annotations_api",
    ],
)

java_plugin(
    name = "auto_value_gson_plugin",
    processor_class = "com.ryanharter.auto.value.gson.factory.AutoValueGsonAdapterFactoryProcessor",
    deps = [
        "@maven//:com_ryanharter_auto_value_auto_value_gson_extension",
        "@maven//:com_ryanharter_auto_value_auto_value_gson_factory",
    ],
)

java_library(
    name = "auto_value",
    exported_plugins = [
        ":auto_annotation_plugin",
        ":auto_builder_plugin",
        ":auto_oneof_plugin",
        ":auto_value_plugin",
        ":auto_value_gson_plugin",
    ],
    exports = [
        ":auto_value_lib",
        ":tomcat_annotations_api",
        "@maven//:com_ryanharter_auto_value_auto_value_gson_runtime",
    ],
)

java_library(
    name = "auto_value_api",
    exports = [
        "@maven//:com_google_auto_value_auto_value_annotations",
    ],
)

java_library(
    name = "auto_value_lib",
    exports = [
        "@maven//:com_google_auto_value_auto_value",
        "@maven//:com_google_auto_value_auto_value_annotations",
    ],
)

# For bootstrapping JavaBuilder
distrib_jar_filegroup(
    name = "auto_value-jars",
    srcs = [
        "@maven//:com_google_auto_value_auto_value_annotations_file",
        "@maven//:com_google_auto_value_auto_value_file",
    ],
    enable_distributions = ["debian"],
)

alias(
    name = "checker_framework_annotations",
    actual = "@maven//:org_checkerframework_checker_qual",
)

alias(
    name = "gson",
    actual = "@maven//:com_google_code_gson_gson",
)

alias(
    name = "caffeine",
    actual = "@maven//:com_github_ben_manes_caffeine_caffeine",
)

# When using new classes from this dependency, make sure to update fastutil.proguard.
java_import(
    name = "fastutil",
    jars = [":fastutil_stripped_jar"],
)

genrule(
    name = "fastutil_stripped_jar",
    srcs = [
        "@maven//:it_unimi_dsi_fastutil_file",
        "@rules_java//toolchains:platformclasspath",
    ],
    outs = ["fastutil-stripped.jar"],
    cmd = """
    $(location :proguard) \
        -injars $(execpath @maven//:it_unimi_dsi_fastutil_file) \
        -outjars $@ \
        -libraryjars $(execpath @rules_java//toolchains:platformclasspath) \
        @$(location //tools:fastutil.proguard) \
      | tail -n +2 # Skip the "ProGuard, version X" line
    # Null out the file times stored in the jar to make the output reproducible.
    TMPDIR=$$(mktemp -d)
    trap 'rm -rf $$TMPDIR' EXIT
    unzip -q $@ -d $$TMPDIR
    rm $@
    find $$TMPDIR -type f -print0 | xargs -0 touch -t 198001010000.00
    OUTPUT="$$(pwd)/$@"
    (cd $$TMPDIR && find . -type f | LC_ALL=C sort | zip -qDX0r@ "$$OUTPUT")
    """,
    tools = [
        ":proguard",
        "//tools:fastutil.proguard",
    ],
)

java_binary(
    name = "proguard",
    main_class = "proguard.ProGuard",
    runtime_deps = ["@maven//:com_guardsquare_proguard_base"],
)

java_library(
    name = "error_prone_annotations",
    exports = [
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:com_google_errorprone_error_prone_type_annotations",
    ],
)

distrib_jar_filegroup(
    name = "error_prone_annotations-jar",
    srcs = [
        "@maven//:com_google_errorprone_error_prone_annotations_file",
        "@maven//:org_threeten_threeten_extra_file",
    ],
    enable_distributions = ["debian"],
)

java_library(
    name = "error_prone",
    exports = [
        ":error_prone_annotations",
        "@maven//:com_google_errorprone_error_prone_check_api",
        "@maven//:com_google_errorprone_error_prone_core",
    ],
)

alias(
    name = "jcip_annotations",
    actual = "@maven//:com_github_stephenc_jcip_jcip_annotations",
)

# For bootstrapping JavaBuilder
distrib_jar_filegroup(
    name = "jcip_annotations-jars",
    srcs = [
        "@maven//:com_github_stephenc_jcip_jcip_annotations_file",
    ],
    enable_distributions = ["debian"],
)

alias(
    name = "pcollections",
    actual = "@maven//:org_pcollections_pcollections",
)

# For bootstrapping JavaBuilder
filegroup(
    name = "bootstrap_guava_and_error_prone-jars",
    srcs = [
        ":error_prone_annotations-jar",
        ":guava-jars",
        ":jcip_annotations-jars",
        ":jsr305-jars",
    ],
)

# TODO(https://github.com/bazelbuild/bazel/issues/18214): After fixing Guava leak
# in test-runner, the guava target can be reverted back to java_library
java_import(
    name = "guava",
    applicable_licenses = [":guava_license"],
    jars = [
        "@maven//:com_google_guava_guava_file",
        "@maven//:com_google_guava_failureaccess_file",
    ],
    exports = [
        ":error_prone_annotations",
        ":jcip_annotations",
        ":jsr305",
    ],
)

license(
    name = "guava_license",
    package_name = "guava/31.1",
    license_kinds = [
        "@rules_license//licenses/spdx:Apache-2.0",
    ],
    license_text = "guava/LICENSE",
)

java_library(
    name = "flogger",
    applicable_licenses = [":flogger_license"],
    exports = [
        "@maven//:com_google_flogger_flogger",
        "@maven//:com_google_flogger_flogger_system_backend",
        "@maven//:com_google_flogger_google_extensions",
    ],
)

license(
    name = "flogger_license",
    package_name = "flogger/0.5.1",
    license_kinds = [
        "@rules_license//licenses/spdx:Apache-2.0",
    ],
)

distrib_jar_filegroup(
    name = "flogger-jars",
    srcs = [
        "@maven//:com_google_flogger_flogger_file",
        "@maven//:com_google_flogger_flogger_system_backend_file",
        "@maven//:com_google_flogger_google_extensions_file",
    ],
    enable_distributions = ["debian"],
)

# For bootstrapping JavaBuilder
distrib_jar_filegroup(
    name = "guava-jars",
    srcs = ["@maven//:com_google_guava_guava_file"],
    enable_distributions = ["debian"],
)

# For desugaring the Guava jar.
distrib_jar_filegroup(
    name = "guava-failureaccess-jar",
    srcs = ["@maven//:com_google_guava_failureaccess_file"],
    enable_distributions = ["debian"],
)

alias(
    name = "javax_activation",
    actual = "@maven//:javax_activation_javax_activation_api",
)

# javax.annotation.Generated is not included in the default root modules in 9,
# see: http://openjdk.java.net/jeps/320.
java_library(
    name = "javax_annotations",
    neverlink = 1,  # @Generated is source-retention
    exports = ["@maven//:javax_annotation_javax_annotation_api"],
)

alias(
    name = "rxjava3",
    actual = "@maven//:io_reactivex_rxjava3_rxjava",
)

alias(
    name = "jsr305",
    actual = "@maven//:com_google_code_findbugs_jsr305",
)

# For bootstrapping JavaBuilder
distrib_jar_filegroup(
    name = "jsr305-jars",
    srcs = ["@maven//:com_google_code_findbugs_jsr305_file"],
    enable_distributions = ["debian"],
)

alias(
    name = "jsr330_inject",
    actual = "@maven//:javax_inject_javax_inject",
)

UNNECESSARY_DYNAMIC_LIBRARIES = select({
    "//src/conditions:windows": "*.so *.jnilib",
    "//src/conditions:darwin": "*.so *.dll",
    "//src/conditions:linux_x86_64": "*.jnilib *.dll",
    "//src/conditions:linux_s390x": "*.jnilib *.dll",
    # The .so file is an x86/s390x one, so we can just remove it if the CPU is not x86/s390x
    "//src/conditions:arm": "*.so *.jnilib *.dll",
    "//src/conditions:linux_aarch64": "*.so *.jnilib *.dll",
    "//src/conditions:linux_ppc": "*.so *.jnilib *.dll",
    "//src/conditions:freebsd": "*.so *.jnilib *.dll",
    "//src/conditions:openbsd": "*.so *.jnilib *.dll",
    # Default is to play it safe -- better have a big binary than a slow binary
    #   The empty string means nothing is to be removed from the library;
    #   the rule command tests for the empty string explictly to avoid
    #   zip erroring when it finds nothing to remove.
    "//conditions:default": "",
})

# Remove native libraries that are for a platform different from the one we are
# building Bazel for.
genrule(
    name = "filter_netty_dynamic_libs",
    srcs = select({
        "//src/conditions:darwin_arm64": ["@maven//:io_netty_netty_tcnative_boringssl_static_osx_aarch_64_file"],
        "//src/conditions:darwin_x86_64": ["@maven//:io_netty_netty_tcnative_boringssl_static_osx_x86_64_file"],
        "//src/conditions:linux_aarch64": ["@maven//:io_netty_netty_tcnative_boringssl_static_linux_aarch_64_file"],
        "//src/conditions:linux_x86_64": ["@maven//:io_netty_netty_tcnative_boringssl_static_linux_x86_64_file"],
        "//src/conditions:windows": ["@maven//:io_netty_netty_tcnative_boringssl_static_windows_x86_64_file"],
        "//conditions:default": [],
    }),
    outs = ["netty_tcnative/netty-tcnative-filtered.jar"],
    cmd = "cp $< $@ && " +
          # Make sure we can write the output file, even if the input isn't writable.
          "chmod +w $@ && " +
          "zip -qd $@ */license/* " + UNNECESSARY_DYNAMIC_LIBRARIES,
)

distrib_java_import(
    name = "netty",
    enable_distributions = ["debian"],
    jars = [
        "@maven//:io_netty_netty_buffer_file",
        "@maven//:io_netty_netty_codec_file",
        "@maven//:io_netty_netty_codec_http2_file",
        "@maven//:io_netty_netty_codec_http_file",
        "@maven//:io_netty_netty_common_file",
        "@maven//:io_netty_netty_handler_file",
        "@maven//:io_netty_netty_handler_proxy_file",
        "@maven//:io_netty_netty_resolver_file",
        "@maven//:io_netty_netty_resolver_dns_file",
        "@maven//:io_netty_netty_transport_file",
        "@maven//:io_netty_netty_transport_classes_epoll_file",
        "@maven//:io_netty_netty_transport_classes_kqueue_file",
    ] + select({
        "//src/conditions:darwin_arm64": ["@maven//:io_netty_netty_transport_native_unix_common_osx_aarch_64_file"],
        "//src/conditions:darwin_x86_64": ["@maven//:io_netty_netty_transport_native_unix_common_osx_x86_64_file"],
        "//src/conditions:linux_aarch64": ["@maven//:io_netty_netty_transport_native_unix_common_linux_aarch_64_file"],
        "//src/conditions:linux_x86_64": ["@maven//:io_netty_netty_transport_native_unix_common_linux_x86_64_file"],
        "//conditions:default": ["@maven//:io_netty_netty_transport_native_unix_common_file"],
    }) + select({
        "//src/conditions:darwin_arm64": ["@maven//:io_netty_netty_transport_native_kqueue_osx_aarch_64_file"],
        "//src/conditions:darwin_x86_64": ["@maven//:io_netty_netty_transport_native_kqueue_osx_x86_64_file"],
        "//conditions:default": [],
    }) + select({
        "//src/conditions:linux_aarch64": ["@maven//:io_netty_netty_transport_native_epoll_linux_aarch_64_file"],
        "//src/conditions:linux_x86_64": ["@maven//:io_netty_netty_transport_native_epoll_linux_x86_64_file"],
        "//conditions:default": [],
    }),
)

distrib_java_import(
    name = "netty_tcnative",
    enable_distributions = ["debian"],
    jars = [
        "@maven//:io_netty_netty_tcnative_classes_file",
    ] + select({
        "//src/conditions:darwin_arm64": [":netty_tcnative/netty-tcnative-filtered.jar"],
        "//src/conditions:darwin_x86_64": [":netty_tcnative/netty-tcnative-filtered.jar"],
        "//src/conditions:linux_aarch64": [":netty_tcnative/netty-tcnative-filtered.jar"],
        "//src/conditions:linux_x86_64": [":netty_tcnative/netty-tcnative-filtered.jar"],
        "//src/conditions:windows": [":netty_tcnative/netty-tcnative-filtered.jar"],
        "//conditions:default": [],
    }),
)

alias(
    name = "tomcat_annotations_api",
    actual = "@maven//:org_apache_tomcat_tomcat_annotations_api",
)

# For bootstrapping JavaBuilder
distrib_jar_filegroup(
    name = "tomcat_annotations_api-jars",
    srcs = ["@maven//:org_apache_tomcat_tomcat_annotations_api_file"],
    enable_distributions = ["debian"],
)

alias(
    name = "java-diff-utils",
    actual = "@maven//:io_github_java_diff_utils_java_diff_utils",
)

# Testing

alias(
    name = "compile_testing",
    testonly = 1,
    actual = "@maven//:com_google_testing_compile_compile_testing",
)

alias(
    name = "guava-testlib",
    testonly = 1,
    actual = "@maven//:com_google_guava_guava_testlib",
)

# Not test_only due to //src/java_tools/junitrunner/java/com/google/testing/junit/junit4:runner
java_library(
    name = "junit4",
    exports = [
        "@maven//:junit_junit",
        "@maven//:org_hamcrest_hamcrest_core",
    ],
)

alias(
    name = "jimfs",
    testonly = 1,
    actual = "@maven//:com_google_jimfs_jimfs",
)

alias(
    name = "mockito",
    testonly = 1,
    actual = "@maven//:org_mockito_mockito_core",
)

alias(
    name = "turbine_direct",
    actual = "@maven//:com_google_turbine_turbine",
)

alias(
    name = "turbine",
    actual = "@maven//:com_google_turbine_turbine",
)

java_library(
    name = "truth",
    testonly = 1,
    exports = [
        "@maven//:com_google_truth_extensions_truth_java8_extension",
        "@maven//:com_google_truth_extensions_truth_proto_extension",
        "@maven//:com_google_truth_truth",
    ],
)

alias(
    name = "xz",
    actual = "@maven//:org_tukaani_xz",
)

# To be used by the starlark example.
filegroup(
    name = "junit4-jars",
    srcs = [
        "@maven//:junit_junit_file",
        "@maven//:org_hamcrest_hamcrest_core_file",
    ],
)

create_compiler_config_setting(
    name = "windows_mingw",
    value = "windows_mingw",
)

create_compiler_config_setting(
    name = "windows_msys64",
    value = "windows_msys64",
)

create_compiler_config_setting(
    name = "windows_msys64_mingw64",
    value = "windows_msys64_mingw64",
)

create_compiler_config_setting(
    name = "windows_clang",
    value = "windows_clang",
)

config_setting(
    name = "k8",
    values = {"host_cpu": "k8"},
)

config_setting(
    name = "piii",
    values = {"host_cpu": "piii"},
)

config_setting(
    name = "arm",
    values = {"host_cpu": "arm"},
)

config_setting(
    name = "aarch64",
    values = {"host_cpu": "aarch64"},
)

config_setting(
    name = "freebsd",
    values = {"host_cpu": "freebsd"},
)

config_setting(
    name = "openbsd",
    values = {"host_cpu": "openbsd"},
)

config_setting(
    name = "s390x",
    values = {"host_cpu": "s390x"},
)

config_setting(
    name = "ppc",
    values = {"host_cpu": "ppc"},
)

test_suite(
    name = "all_windows_tests",
    tests = [
        "//third_party/def_parser:windows_tests",
    ],
    visibility = ["//src:__pkg__"],
)
