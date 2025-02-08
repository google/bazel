load("@rules_java//java:defs.bzl", "java_library", "java_plugin")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(
        ["**"],
    ),
    visibility = ["//src:__subpackages__"],
)

# @AutoCodec annotation and plugin. Used by clients. Use
# :serialization-constant instead if possible.
java_library(
    name = "autocodec",
    exported_plugins = [":autocodec-plugin"],
    exports = [
        ":autocodec-annotation",
        # Generated classes have the following dependencies.
        ":registered-singleton",
        ":runtime-helpers",
        "@com_google_protobuf//:protobuf_java",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/unsafe:unsafe-provider",
    ],
)

java_library(
    name = "runtime-helpers",
    srcs = ["RuntimeHelpers.java"],
)

java_library(
    name = "registered-singleton",
    srcs = ["RegisteredSingletonDoNotUse.java"],
)

java_library(
    name = "serialization-constant",
    exported_plugins = [":serialization-constant-plugin"],
    exports = [
        ":registered-singleton",
        ":serialization-constant-annotation",
    ],
)

java_library(
    name = "serialization-constant-annotation",
    srcs = ["SerializationConstant.java"],
)

java_plugin(
    name = "serialization-constant-plugin",
    processor_class = "com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstantProcessor",
    deps = [
        ":serialization-constant-processor",
    ],
)

java_library(
    name = "serialization-processing-exception",
    srcs = ["SerializationProcessingException.java"],
    deps = ["//third_party:error_prone_annotations"],
)

java_library(
    name = "type-operations",
    srcs = ["TypeOperations.java"],
    deps = [
        ":serialization-processing-exception",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party/java/javapoet",
    ],
)

java_library(
    name = "serialization-constant-processor",
    srcs = ["SerializationConstantProcessor.java"],
    deps = [
        ":registered-singleton",
        ":serialization-constant-annotation",
        ":serialization-processing-exception",
        ":type-operations",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:codec-scanning-constants",
        "//third_party:auto_service",
        "//third_party:guava",
        "//third_party/java/javapoet",
    ],
)

# @AutoCodec annotation only. Used by clients and the processor.
java_library(
    name = "autocodec-annotation",
    srcs = ["AutoCodec.java"],
)

# Installs the @AutoCodec annotation processor as a compiler plugin.
java_plugin(
    name = "autocodec-plugin",
    processor_class = "com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodecProcessor",
    deps = [
        ":autocodec-processor",
    ],
)

# @AutoCodec annotation processor implementation.
java_library(
    name = "autocodec-processor",
    srcs = [
        "AutoCodecProcessor.java",
        "CodecGenerator.java",
        "DeferredObjectCodecConstants.java",
        "DeferredObjectCodecFieldGenerators.java",
        "DeferredObjectCodecGenerator.java",
        "FieldGenerator.java",
        "Initializers.java",
        "InterningObjectCodecFieldGenerators.java",
        "InterningObjectCodecGenerator.java",
        "SerializationCodeGenerator.java",
    ],
    deps = [
        ":autocodec-annotation",
        ":runtime-helpers",
        ":serialization-processing-exception",
        ":type-operations",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/unsafe:unsafe-provider",
        "//src/main/java/com/google/devtools/build/lib/util:string",
        "//third_party:auto_service",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305",
        "//third_party/java/javapoet",
        "@com_google_protobuf//:protobuf_java",
    ],
)
