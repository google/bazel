# Copyright 2021 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""cc_test Starlark implementation."""

load(":common/cc/attrs.bzl", "cc_binary_attrs", "linkstatic_doc", "stamp_doc")
load(":common/cc/cc_binary.bzl", "cc_binary_impl")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/cc_shared_library.bzl", "dynamic_deps_initializer")
load(":common/cc/semantics.bzl", "semantics")
load(":common/paths.bzl", "paths")

cc_internal = _builtins.internal.cc_internal
config_common = _builtins.toplevel.config_common
platform_common = _builtins.toplevel.platform_common
testing = _builtins.toplevel.testing

_CC_TEST_TOOLCHAIN_TYPE = "@" + semantics.get_repo() + "//tools/cpp:test_runner_toolchain_type"

def _legacy_cc_test_impl(ctx):
    binary_info, providers = cc_binary_impl(ctx, [])
    test_env = {}
    test_env.update(cc_helper.get_expanded_env(ctx, {}))

    coverage_runfiles, coverage_env = semantics.get_coverage_env(ctx)

    runfiles_list = [binary_info.runfiles]
    if coverage_runfiles:
        runfiles_list.append(coverage_runfiles)

    runfiles = ctx.runfiles()
    runfiles = runfiles.merge_all(runfiles_list)

    test_env.update(coverage_env)
    providers.append(testing.TestEnvironment(
        environment = test_env,
        inherited_environment = ctx.attr.env_inherit,
    ))
    providers.append(DefaultInfo(
        files = binary_info.files,
        runfiles = runfiles,
        executable = binary_info.executable,
    ))

    if cc_helper.has_target_constraints(ctx, ctx.attr._apple_constraints):
        # When built for Apple platforms, require the execution to be on a Mac.
        providers.append(testing.ExecutionInfo({"requires-darwin": ""}))
    return providers

def _impl(ctx):
    cc_test_toolchain = ctx.exec_groups["test"].toolchains[_CC_TEST_TOOLCHAIN_TYPE]
    if cc_test_toolchain:
        cc_test_info = cc_test_toolchain.cc_test_info
    else:
        # This is the "legacy" cc_test flow
        return _legacy_cc_test_impl(ctx)

    binary_info, providers = cc_binary_impl(ctx, cc_test_info.linkopts, cc_test_info.linkstatic)
    processed_environment = cc_helper.get_expanded_env(ctx, {})

    test_providers = cc_test_info.get_runner.func(
        ctx,
        binary_info,
        processed_environment = processed_environment,
        **cc_test_info.get_runner.args
    )
    providers.extend(test_providers)
    return providers

_cc_test_attrs = dict(cc_binary_attrs)

# Update cc_test defaults:
_cc_test_attrs.update(
    _is_test = attr.bool(default = True),
    _apple_constraints = attr.label_list(
        default = [
            "@" + paths.join(semantics.get_platforms_root(), "os:ios"),
            "@" + paths.join(semantics.get_platforms_root(), "os:macos"),
            "@" + paths.join(semantics.get_platforms_root(), "os:tvos"),
            "@" + paths.join(semantics.get_platforms_root(), "os:watchos"),
        ],
    ),
    # Starlark tests don't get `env_inherit` by default.
    env_inherit = attr.string_list(),
    stamp = attr.int(values = [-1, 0, 1], default = 0, doc = stamp_doc),
    linkstatic = attr.bool(default = False, doc = linkstatic_doc),
)
_cc_test_attrs.update(semantics.get_test_malloc_attr())
_cc_test_attrs.update(semantics.get_coverage_attrs())

def cc_test_initializer(**kwargs):
    """Entry point for cc_test rules.

    It  serves to detect if the `linkstatic` attribute was explicitly set or not.
    This is to workaround a deficiency in Starlark attributes.
    (See: https://github.com/bazelbuild/bazel/issues/14434)

    Args:
        **kwargs: Arguments suitable for cc_test.
    """

    if "linkstatic" not in kwargs:
        kwargs["linkstatic"] = semantics.get_linkstatic_default_for_test()

    return dynamic_deps_initializer(**kwargs)

cc_test = rule(
    initializer = cc_test_initializer,
    implementation = _impl,
    doc = """
<p>
A <code>cc_test()</code> rule compiles a test.  Here, a test
is a binary wrapper around some testing code.
</p>

<p><i>By default, C++ tests are dynamically linked.</i><br/>
    To statically link a unit test, specify
    <a href="${link cc_binary.linkstatic}"><code>linkstatic=True</code></a>.
    It would probably be good to comment why your test needs
    <code>linkstatic</code>; this is probably not obvious.</p>

<h4>Implicit output targets</h4>
<ul>
<li><code><var>name</var>.stripped</code> (only built if explicitly requested): A stripped
  version of the binary. <code>strip -g</code> is run on the binary to remove debug
  symbols.  Additional strip options can be provided on the command line using
  <code>--stripopt=-foo</code>.</li>
<li><code><var>name</var>.dwp</code> (only built if explicitly requested): If
  <a href="https://gcc.gnu.org/wiki/DebugFission">Fission</a> is enabled: a debug
  information package file suitable for debugging remotely deployed binaries. Else: an
  empty file.</li>
</ul>

<p>
See the <a href="${link cc_binary_args}">cc_binary()</a> arguments, except that
the <code>stamp</code> argument is set to 0 by default for tests and
that <code>cc_test</code> has extra <a href="${link common-definitions#common-attributes-tests}">
attributes common to all test rules (*_test)</a>.</p>
""" + semantics.cc_test_extra_docs,
    attrs = _cc_test_attrs,
    outputs = {
        # TODO(b/198254254): Handle case for windows.
        "stripped_binary": "%{name}.stripped",
        "dwp_file": "%{name}.dwp",
    },
    fragments = ["cpp", "coverage"] + semantics.additional_fragments(),
    exec_groups = {
        "cpp_link": exec_group(toolchains = cc_helper.use_cpp_toolchain()),
        # testing.ExecutionInfo defaults to an exec_group of "test".
        "test": exec_group(toolchains = [config_common.toolchain_type(_CC_TEST_TOOLCHAIN_TYPE, mandatory = False)]),
    },
    toolchains = [] +
                 cc_helper.use_cpp_toolchain() +
                 semantics.get_runtimes_toolchain(),
    test = True,
    provides = [CcInfo],
)
