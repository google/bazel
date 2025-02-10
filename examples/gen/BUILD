package(default_visibility = ["//visibility:public"])

genquery(
    name = "genquery",
    expression = "deps(@rules_java//toolchains:current_java_runtime)",
    scope = ["@rules_java//toolchains:current_java_runtime"],
)

genrule(
    name = "genrule",
    srcs = [":genquery"],
    outs = ["genrule.txt"],
    cmd = "cat $(SRCS) > $@",
)

filegroup(
    name = "srcs",
    srcs = [
        "BUILD",
    ],
)
