package(default_visibility = ["//visibility:__pkg__"])

licenses(["notice"])  # Apache License 2.0

exports_files(["LICENSE"])

sh_test(
    name = "ijar_test",
    size = "enormous",
    srcs = ["ijar_test.sh"],
    args = [
        "$(JAVABASE)",
        "$(location //third_party/ijar)",
        # We assume unzip and zip to be on the path
        "unzip",
        "zip",
        "$(location :zip_count)",
    ],
    data = [
        "//third_party/ijar",
        "A.java",
        "B.java",
        "Object.java",
        "WellCompressed1.java",
        "WellCompressed2.java",
        ":libtypeannotations2.jar",
        ":libmethodparameters.jar",
        # This is a precompiled .jar file because it needs to be compiled with JDK 11.
        # TODO(cushon): build from source once we have a JDK 11
        "nestmates/nestmates.jar",
        "records/records.jar",
        "sealed/sealed.jar",
        ":source_debug_extension.jar",
        ":duplicated_unsupported_attribute.jar",
        ":keep_for_compile_lib.jar",
        ":largest_regular.jar",
        ":smallest_zip64.jar",
        ":definitely_zip64.jar",
        ":zip_count",
        "TypeAnnotationTest2.java",
        ":libinvokedynamic.jar",
        # Intentionally bad test JAR: file count in central directory
        # wrong.
        "libwrongcentraldir.jar",
        ":dynamic_constant.jar",
        "@bazel_tools//tools/bash/runfiles",
        "@bazel_tools//tools/jdk:current_java_runtime",
    ],
    shard_count = 5,
    tags = ["zip"],
    toolchains = [
        "@bazel_tools//tools/jdk:current_java_runtime",
    ],
    deps = [":testenv"],
)

sh_test(
    name = "zip_test",
    size = "large",
    srcs = ["zip_test.sh"],
    args = [
        "$(location //third_party/ijar:zipper)",
        # We assume unzip and zip to be on the path
        "unzip",
        "zip",
    ],
    data = ["//third_party/ijar:zipper"],
    tags = ["zip"],
    deps = [":testenv"],
)

sh_library(
    name = "testenv",
    srcs = ["testenv.sh"],
    deps = [
        "//src/test/shell:bashunit",
        "@bazel_tools//tools/bash/runfiles",
    ],
)

java_library(
    name = "invokedynamic",
    testonly = 1,
    srcs = ["invokedynamic/ClassWithLambda.java"],
)

# For testing purposes: a class which exposes a private nested class in
# public API, classes with annotations and a class with different forms of
# deprecations markers
java_library(
    name = "ijar_testlib",
    srcs = [
        "AnnotatedClass.java",
        "Annotations.java",
        "DeprecatedParts.java",
        "DumbClass.java",
        "EnclosingMethod.java",
        "PrivateMembersClass.java",
    ],
)

# For testing purposes: an interface jar (ijar) which includes the
# interface view of the various test class from above.
genrule(
    name = "interface_ijar_testlib",
    srcs = [":ijar_testlib"],
    outs = ["interface_ijar_testlib.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "interface_ijar_testlib_with_target_label",
    srcs = [":ijar_testlib"],
    outs = ["interface_ijar_testlib_with_target_label.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --injecting_rule_kind foo_library $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "interface_ijar_testlib_nostrip",
    srcs = [":ijar_testlib"],
    outs = ["interface_ijar_testlib_nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_with_manifest_nostrip",
    srcs = ["jar-with-manifest.jar"],
    outs = ["jar-with-manifest-nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_with_manifest_and_target_label_nostrip",
    srcs = ["jar-with-manifest-and-target-label.jar"],
    outs = ["jar-with-manifest-and-target-label-nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_without_manifest_nostrip",
    srcs = ["jar-without-manifest.jar"],
    outs = ["jar-without-manifest-nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_with_manifest_sections_nostrip",
    testonly = 1,
    srcs = [":jar_with_manifest_sections"],
    outs = ["jar-with-manifest-sections-nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_with_target_label_and_manifest_sections_nostrip",
    testonly = 1,
    srcs = [":jar_with_target_label_and_manifest_sections"],
    outs = ["jar-with-target-label-and-manifest-sections-nostrip.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "jar_without_manifest_nostrip_idempotence",
    srcs = ["jar-without-manifest-nostrip.jar"],
    outs = ["jar-without-manifest-nostrip-idempotence.jar"],
    cmd = "$(location //third_party/ijar) --target_label //foo:foo --nostrip_jar $< $@",
    tools = ["//third_party/ijar"],
)

genrule(
    name = "empty_with_target_label",
    srcs = [":empty_zip.jar"],
    outs = ["empty_with_target_label.jar"],
    cmd = "$(location //third_party/ijar) $< $@ --target_label //empty",
    tools = ["//third_party/ijar"],
)

java_library(
    name = "typeannotations2",
    srcs = glob(["typeannotations2/**/*.java"]),
)

java_library(
    name = "methodparameters",
    srcs = ["methodparameters/Test.java"],
    javacopts = ["-parameters"],
)

java_binary(
    name = "gen_source_debug_extension",
    srcs = ["GenSourceDebugExtension.java"],
    main_class = "test.GenSourceDebugExtension",
    deps = ["//third_party:asm"],
)

genrule(
    name = "gen_source_debug_extension_jar",
    srcs = [],
    outs = ["source_debug_extension.jar"],
    cmd = "$(location :gen_source_debug_extension) $@",
    tools = ["gen_source_debug_extension"],
)

java_binary(
    name = "gen_duplicated_unsupported_attribute",
    srcs = ["GenDuplicatedUnsupportedAttribute.java"],
    main_class = "test.GenDuplicatedUnsupportedAttribute",
    deps = ["//third_party:asm"],
)

genrule(
    name = "gen_duplicated_unsupported_attribute_jar",
    srcs = [],
    outs = ["duplicated_unsupported_attribute.jar"],
    cmd = "$(location :gen_duplicated_unsupported_attribute) $@",
    tools = ["gen_duplicated_unsupported_attribute"],
)

java_binary(
    name = "gen_dynamic_constant",
    srcs = ["GenDynamicConstant.java"],
    main_class = "test.GenDynamicConstant",
    deps = ["//third_party:asm"],
)

genrule(
    name = "gen_dynamic_constant_jar",
    srcs = [],
    outs = ["dynamic_constant.jar"],
    cmd = "$(location :gen_dynamic_constant) $@",
    tools = [":gen_dynamic_constant"],
)

java_library(
    name = "local_and_anonymous_lib",
    srcs = ["LocalAndAnonymous.java"],
    javacopts = ["-source 8 -target 8"],
)

genrule(
    name = "local_and_anonymous_interface",
    srcs = [":local_and_anonymous_lib"],
    outs = ["local_and_anonymous-interface.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

java_binary(
    name = "GenKeepForCompile",
    testonly = 1,
    srcs = ["GenKeepForCompile.java"],
    main_class = "GenKeepForCompile",
    deps = [
        "//third_party:asm",
    ],
)

genrule(
    name = "keep_for_compile_lib",
    testonly = 1,
    outs = ["keep_for_compile_lib.jar"],
    cmd = "$(location :GenKeepForCompile) $@",
    tools = [":GenKeepForCompile"],
)

java_binary(
    name = "GenModuleInfo",
    testonly = 1,
    srcs = ["GenModuleInfo.java"],
    main_class = "GenModuleInfo",
    deps = ["//third_party:guava"],
)

genrule(
    name = "module_info",
    testonly = 1,
    outs = ["module_info.jar"],
    cmd = "$(location :GenModuleInfo) $@",
    tools = [":GenModuleInfo"],
)

genrule(
    name = "module_info_interface",
    testonly = 1,
    srcs = [":module_info.jar"],
    outs = ["module_info-interface.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

java_binary(
    name = "GenKotlinModule",
    testonly = 1,
    srcs = ["GenKotlinModule.java"],
    main_class = "GenKotlinModule",
    deps = ["//third_party:guava"],
)

genrule(
    name = "kotlin_module",
    testonly = 1,
    outs = ["kotlin_module.jar"],
    cmd = "$(location :GenKotlinModule) $@",
    tools = [":GenKotlinModule"],
)

genrule(
    name = "kotlin_module_interface",
    testonly = 1,
    srcs = [":kotlin_module.jar"],
    outs = ["kotlin_module-interface.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

java_binary(
    name = "GenScalaTasty",
    testonly = 1,
    srcs = ["GenScalaTasty.java"],
    main_class = "GenScalaTasty",
    deps = ["//third_party:guava"],
)

genrule(
    name = "scala_tasty",
    testonly = 1,
    outs = ["scala_tasty.jar"],
    cmd = "$(location :GenScalaTasty) $@",
    tools = [":GenScalaTasty"],
)

genrule(
    name = "scala_tasty-interface",
    testonly = 1,
    srcs = [":scala_tasty.jar"],
    outs = ["scala_tasty-interface.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

java_binary(
    name = "GenJarWithManifestSections",
    testonly = 1,
    srcs = ["GenJarWithManifestSections.java"],
    main_class = "GenJarWithManifestSections",
    deps = ["//third_party:guava"],
)

genrule(
    name = "jar_with_manifest_sections",
    testonly = 1,
    outs = ["jar-with-manifest-sections.jar"],
    cmd = "$(location :GenJarWithManifestSections) $@",
    tools = [":GenJarWithManifestSections"],
)

genrule(
    name = "jar_with_target_label_and_manifest_sections",
    testonly = 1,
    outs = ["jar-with-target-label-and-manifest-sections.jar"],
    cmd = "$(location :GenJarWithManifestSections) $@ //not:this",
    tools = [":GenJarWithManifestSections"],
)

java_binary(
    name = "GenMetaInfTransitive",
    testonly = 1,
    srcs = ["GenMetaInfTransitive.java"],
    main_class = "GenMetaInfTransitive",
    deps = ["//third_party:guava"],
)

genrule(
    name = "meta_inf_transitive",
    testonly = 1,
    outs = ["meta_inf_transitive.jar"],
    cmd = "$(location :GenMetaInfTransitive) $@",
    tools = [":GenMetaInfTransitive"],
)

genrule(
    name = "meta_inf_transitive-interface",
    testonly = 1,
    srcs = [":meta_inf_transitive.jar"],
    outs = ["meta_inf_transitive-interface.jar"],
    cmd = "$(location //third_party/ijar) $< $@",
    tools = ["//third_party/ijar"],
)

java_test(
    name = "IjarTests",
    size = "small",
    srcs = [
        "IjarTests.java",
        "StripVerifyingVisitor.java",
    ],
    data = [
        "PrivateNestedClass.java",
        "UseDeprecatedParts.java",
        "UseRestrictedAnnotation.java",
        "jar-with-manifest.jar",
        "jar-with-manifest-and-target-label.jar",
        "jar-with-manifest-sections.jar",
        "jar-without-manifest.jar",
        "package-info.java",
        ":empty_with_target_label",
        ":ijar_testlib",
        ":interface_ijar_testlib",
        ":interface_ijar_testlib_nostrip",
        ":interface_ijar_testlib_with_target_label",
        ":jar_with_manifest_and_target_label_nostrip",
        ":jar_with_manifest_nostrip",
        ":jar_with_manifest_sections_nostrip",
        ":jar_with_target_label_and_manifest_sections_nostrip",
        ":jar_without_manifest_nostrip",
        ":jar_without_manifest_nostrip_idempotence",
        ":kotlin_module-interface.jar",
        ":liblocal_and_anonymous_lib.jar",
        ":local_and_anonymous-interface.jar",
        ":meta_inf_transitive-interface",
        ":module_info-interface.jar",
        ":scala_tasty-interface.jar",
    ],
    tags = ["zip"],
    test_class = "IjarTests",
    deps = [
        "//third_party:asm",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

test_suite(
    name = "all_tests",
    testonly = 1,
    visibility = ["//visibility:public"],
)

java_binary(
    name = "gen_zip_with_entries",
    srcs = ["GenZipWithEntries.java"],
    jvm_flags = ["-Djdk.util.zip.inhibitZip64=false"],
    main_class = "test.GenZipWithEntries",
    deps = ["//third_party:asm"],
)

[
    genrule(
        name = name,
        outs = [name + ".jar"],
        cmd = "$(location :gen_zip_with_entries) %s $@" % entries,
        tools = [":gen_zip_with_entries"],
    )
    for name, entries in [
        ("largest_regular", 65535),
        ("smallest_zip64", 65536),
        ("definitely_zip64", 70000),
        ("empty_zip", 1),
    ]
]

java_binary(
    name = "zip_count",
    srcs = ["ZipCount.java"],
    jvm_flags = ["-Djdk.util.zip.inhibitZip64=false"],
    main_class = "test.ZipCount",
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//third_party/ijar:__pkg__"],
)
