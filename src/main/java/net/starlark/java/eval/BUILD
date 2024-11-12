# Description: Bazel's Starlark interpreter

load("@rules_java//java:defs.bzl", "java_library")

licenses(["notice"])  # Apache 2.0

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src/main/java/net/starlark/java:bazel"],
)

# The Starlark evaluator
java_library(
    name = "eval",
    srcs = [
        "BuiltinFunction.java",
        "CallUtils.java",
        "CpuProfiler.java",
        "Debug.java",
        "Dict.java",
        "Eval.java",
        "EvalException.java",
        "EvalUtils.java",
        "FlagGuardedValue.java",
        "FormatParser.java",
        "FrozenDict.java",
        "GuardedValue.java",
        "HasBinary.java",
        "ImmutableSingletonStarlarkList.java",
        "ImmutableStarlarkList.java",
        "JNI.java",
        "LazyImmutableStarlarkList.java",
        "MethodDescriptor.java",
        "MethodLibrary.java",
        "Module.java",
        "Mutability.java",
        "MutableStarlarkList.java",
        "NoneType.java",
        "ParamDescriptor.java",
        "Printer.java",
        "RangeList.java",
        "RegularImmutableStarlarkList.java",
        "RegularTuple.java",
        "Sequence.java",
        "SingletonTuple.java",
        "Starlark.java",
        "StarlarkCallable.java",
        "StarlarkFloat.java",
        "StarlarkFunction.java",
        "StarlarkIndexable.java",
        "StarlarkInt.java",
        "StarlarkIterable.java",
        "StarlarkList.java",
        "StarlarkSemantics.java",
        "StarlarkThread.java",
        "StarlarkValue.java",
        "StringModule.java",
        "Structure.java",
        "SymbolGenerator.java",
        "Tuple.java",
    ],
    visibility = ["//src/main/java/net/starlark/java:clients"],
    deps = [
        # Do not add Bazel or Google dependencies here!
        "//src/main/java/net/starlark/java/annot",
        "//src/main/java/net/starlark/java/spelling",
        "//src/main/java/net/starlark/java/syntax",
        "//third_party:error_prone_annotations",
        "//third_party:auto_value",
        "//third_party:flogger",
        "//third_party:guava",
        "//third_party:jsr305",
    ],
)

# Dynamic library for Starlark CPU profiler.
# The application or test must arrange for the library to be added
# to the java.library.path directory (e.g. by 'java -Djava.library.path=<dir>').
# What a mess.
filegroup(
    name = "cpu_profiler",
    srcs = select({
        "//src/conditions:darwin": [":libcpu_profiler.dylib"],
        "//src/conditions:windows": [":cpu_profiler.dll"],
        "//conditions:default": [":libcpu_profiler.so"],  # POSIX
    }),
    visibility = ["//src/main/java/net/starlark/java:clients"],
)

genrule(
    name = "cpu_profiler_darwin",
    srcs = ["libcpu_profiler.so"],
    outs = ["libcpu_profiler.dylib"],
    cmd = "cp $< $@",
)

genrule(
    name = "cpu_profiler_windows",
    srcs = ["libcpu_profiler.so"],
    outs = ["cpu_profiler.dll"],
    cmd = "cp $< $@",
)

# The C++ portion of the Starlark CPU profiler.
cc_binary(
    name = "libcpu_profiler.so",
    srcs = select({
        "//src/conditions:darwin": ["cpu_profiler_posix.cc"],
        "//src/conditions:linux": ["cpu_profiler_posix.cc"],
        "//conditions:default": ["cpu_profiler_unimpl.cc"],
    }),
    linkshared = 1,
    deps = ["@bazel_tools//tools/jdk:jni"],
)
