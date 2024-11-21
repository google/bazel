"""BUILD rules to generate gRPC service interfaces.
You need to load the rules in your BUILD file for use, like:
load("//third_party/grpc:build_defs.bzl", "java_grpc_library")
"""

load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@rules_cc//cc:defs.bzl", "cc_library")
load("@rules_java//java:defs.bzl", "java_library")

def _path_ignoring_repository(f):
    if (len(f.owner.workspace_root) == 0):
        return f.short_path

    virtual_imports = "/_virtual_imports/"
    if virtual_imports in f.path:
        return f.path.split(virtual_imports)[1].split("/", 1)[1]
    else:
        # If |f| is a generated file, it will have "bazel-out/*/genfiles" prefix
        # before "external/workspace", so we need to add the starting index of "external/workspace"
        return f.path[f.path.find(f.owner.workspace_root) + len(f.owner.workspace_root) + 1:]

def _gensource_impl(ctx):
    if len(ctx.attr.srcs) > 1:
        fail("Only one src value supported", "srcs")
    for s in ctx.attr.srcs:
        if s.label.package != ctx.label.package:
            print(("in srcs attribute of {0}: Proto source with label {1} should be in " +
                   "same package as consuming rule").format(ctx.label, s.label))
    srcdotjar = ctx.actions.declare_file(ctx.label.name + ".jar")
    srcs = [f for dep in ctx.attr.srcs for f in dep[ProtoInfo].direct_sources]
    includes = [f for dep in ctx.attr.srcs for f in dep[ProtoInfo].transitive_imports.to_list()]

    ctx.actions.run_shell(
        command = " ".join([
                               ctx.executable._protoc.path,
                               "--plugin=protoc-gen-grpc-java={0}".format(ctx.executable._java_plugin.path),
                               "--grpc-java_out=enable_deprecated={0}:{1}".format(str(ctx.attr.enable_deprecated).lower(), srcdotjar.path),
                           ] +
                           ["-I{0}={1}".format(_path_ignoring_repository(include), include.path) for include in includes] +
                           [src.path for src in srcs]),
        inputs = srcs + includes,
        tools = [ctx.executable._java_plugin, ctx.executable._protoc],
        outputs = [srcdotjar],
        mnemonic = "JavaGrpcGenSource",
        use_default_shell_env = True,
    )

    ctx.actions.run_shell(
        command = "cp '%s' '%s'" % (srcdotjar.path, ctx.outputs.srcjar.path),
        inputs = [srcdotjar],
        outputs = [ctx.outputs.srcjar],
    )

_java_grpc_gensource = rule(
    attrs = {
        "srcs": attr.label_list(
            mandatory = True,
            allow_empty = False,
            providers = [ProtoInfo],
        ),
        "enable_deprecated": attr.bool(
            default = False,
        ),
        "_protoc": attr.label(
            default = Label("@com_google_protobuf//:protoc"),
            executable = True,
            cfg = "exec",
            allow_single_file = True,
        ),
        "_java_plugin": attr.label(
            default = Label("//third_party/grpc-java:grpc-java-plugin"),
            executable = True,
            cfg = "exec",
        ),
    },
    outputs = {
        "srcjar": "%{name}.srcjar",
    },
    implementation = _gensource_impl,
)

def java_grpc_library(name, srcs, deps, enable_deprecated = None, visibility = None, constraints = None, **kwargs):
    """Generates and compiles gRPC Java sources for services defined in a proto
    file. This rule is compatible with proto_library with java_api_version,
    java_proto_library, and java_lite_proto_library.
    Do note that this rule only scans through the proto file for RPC services. It
    does not generate Java classes for proto messages. You will need a separate
    proto_library with java_api_version, java_proto_library, or
    java_lite_proto_library rule.
    Args:
      name: (str) A unique name for this rule. Required.
      srcs: (list) a single proto_library target that contains the schema of the
          service. Required.
      deps: (list) a single java_proto_library target for the proto_library in
          srcs.  Required.
      visibility: (list) the visibility list
      **kwargs: Passed through to generated targets
    """
    gensource_name = name + "_srcs"
    _java_grpc_gensource(
        name = gensource_name,
        srcs = srcs,
        enable_deprecated = enable_deprecated,
        visibility = ["//visibility:private"],
        tags = [
            "avoid_dep",
        ],
        **kwargs
    )
    java_library(
        name = name,
        srcs = [gensource_name],
        visibility = visibility,
        deps = [
            Label("//third_party:javax_annotations"),
            Label("//third_party:jsr305"),
            Label("//third_party/grpc-java:grpc-jar"),
            Label("//third_party:guava"),
            "@com_google_protobuf//:protobuf_java",
        ] + deps,
        **kwargs
    )

def _get_external_deps(external_deps):
    ret = []
    for dep in external_deps:
        ret += ["//third_party/" + dep]
    return ret

# Simplified version of gRPC upstream's grpc_cc_library.
def grpc_cc_library(
        name,
        srcs = [],
        public_hdrs = [],
        hdrs = [],
        external_deps = [],
        deps = [],
        standalone = False,
        language = "C++",
        testonly = False,
        visibility = None,
        alwayslink = 0,
        data = []):
    copts = []
    if language.upper() == "C":
        copts = select({
            "//conditions:default": [
                "-std=c99",
                "-Wimplicit-function-declaration",
            ],
            ":windows": ["/we4013"],
        })
    cc_library(
        name = name,
        srcs = srcs,
        defines = ["GRPC_ARES=0"],  # Our use case doesn't need ares.
        hdrs = hdrs + public_hdrs,
        deps = deps + _get_external_deps(external_deps),
        copts = copts,
        visibility = visibility,
        testonly = testonly,
        linkopts = select({
            "//conditions:default": ["-pthread"],
            ":windows": [],
        }),
        includes = [
            ".",
            "include",
        ],
        alwayslink = alwayslink,
        data = data,
    )
