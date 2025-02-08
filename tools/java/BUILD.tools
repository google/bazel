filegroup(
  name = "java_stub_template.txt",
  srcs = ["@rules_java//java/bazel/rules:java_stub_template.txt"],
  visibility = ["//visibility:public"],
  deprecation = """
    The java_stub_template is no longer versioned with Bazel. It is now tied to the @rules_java version,
    and hence can no longer be considered stable. This alias target will be deleted in a future release.
    Rulesets are encouraged to maintain their own (copy of the) template that matches their template expansion
    code.
                """,
)