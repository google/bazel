################################################################################
# DEPRECATION WARNING                                                          #
#                                                                              #
# Direct access to @bazel_tools//tools/python, and to the native Python rules  #
# and providers, is deprecated. All of these symbols are now accessible from   #
# bazelbuild/rules_python.                                                     #
#                                                                              #
# For native rules in particular, --incompatible_load_python_rules_from_bzl    #
# (#9006) prohibits direct access to the native rules, and will be flipped on  #
# in Bazel 1.0.                                                                #
################################################################################

load(":python_version.bzl", "define_python_version_flag")

package(default_visibility = ["//visibility:public"])

# These exports are needed for Starlark tooling to work on files that
# transitive load these ones. Ideally they would be bzl_library targets, but we
# don't have access to Skylib here. See
# https://github.com/bazelbuild/skydoc/issues/166.
exports_files([
    "python_bootstrap_template.txt",
    "python_version.bzl",
    "srcs_version.bzl",
    "toolchain.bzl",
    "utils.bzl",
    "private/py_test_alias.bzl",
    # used in this BUILD file.
])

filegroup(
    name = "bzl_srcs",
    srcs = glob(["*.bzl"]) + [
        "private/py_test_alias.bzl",
    ],
    visibility = ["//tools:__pkg__"],
)

# This target can be used to inspect the current Python major version. To use,
# put it in the `flag_values` attribute of a `config_setting` and test it
# against the values "PY2" or "PY3". It will always match one or the other.
#
# If you do not need to test any other flags in combination with the Python
# version, then as a convenience you may use the predefined `config_setting`s
# `@bazel_tools//tools/python:PY2` and `@bazel_tools//tools/python:PY3`.
#
# Example usage:
#
#     config_setting(
#         name = "py3_on_arm",
#         values = {"cpu": "arm"},
#         flag_values = {"@bazel_tools//tools/python:python_version": "PY3"},
#     )
#
#     my_target(
#         ...
#         some_attr = select({
#             ":py3_on_arm": ...,
#             ...
#         }),
#         ...
#     )
#
# Note that it is not allowed to `select()` on the built-in command-line flag
# `--python_version`. This is because historically, the flag's value has not
# always corresponded to the effective Python version, due to some indirections
# around legacy APIs, legacy semantics, and changing the default version from
# PY2 to PY3.
define_python_version_flag(
    name = "python_version",
)

config_setting(
    name = "PY2",
    flag_values = {":python_version": "PY2"},
    visibility = ["//visibility:public"],
)

config_setting(
    name = "PY3",
    flag_values = {":python_version": "PY3"},
    visibility = ["//visibility:public"],
)

# The toolchain type for Python rules. Provides a Python 2 and/or Python 3
# runtime.
toolchain_type(name = "toolchain_type")

# A constraint_setting to use for constraints related to the location of the
# system Python 2 interpreter on a platform.
constraint_setting(name = "py2_interpreter_path")

# A constraint_setting to use for constraints related to the location of the
# system Python 3 interpreter on a platform.
constraint_setting(name = "py3_interpreter_path")

# Definitions for a Python toolchain that, at execution time, attempts to detect
# a platform runtime having the appropriate major Python version.
#
# This is a toolchain of last resort that gets automatically registered in all
# workspaces. Ideally you should register your own Python toolchain, which will
# supersede this one so long as its constraints match the target platform.

alias(
    name = "autodetecting_toolchain",
    actual = "@rules_python//python:autodetecting_toolchain",
    deprecation = "Use @rules_python//python:autodetecting_toolchain",
)

