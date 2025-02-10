# Copyright 2023 The Bazel Authors. All rights reserved.
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

""" Utilities for Java compilation support in Starlark. """

_java_common_internal = _builtins.internal.java_common_internal_do_not_use

def _internal_exports():
    _builtins.internal.cc_common.check_private_api(allowlist = [
        ("", "javatests/com/google/devtools/grok/kythe/analyzers/build/testdata/pkg"),
        ("", "third_party/bazel_rules/rules_java"),
        ("rules_java", ""),
    ])
    return struct(
        create_compilation_action = _java_common_internal.create_compilation_action,
        create_header_compilation_action = _java_common_internal.create_header_compilation_action,
        check_java_toolchain_is_declared_on_rule = _java_common_internal._check_java_toolchain_is_declared_on_rule,
        check_provider_instances = _java_common_internal.check_provider_instances,
        collect_native_deps_dirs = _java_common_internal.collect_native_deps_dirs,
        expand_java_opts = _java_common_internal.expand_java_opts,
        get_runtime_classpath_for_archive = _java_common_internal.get_runtime_classpath_for_archive,
        google_legacy_api_enabled = _java_common_internal._google_legacy_api_enabled,
        incompatible_disable_non_executable_java_binary = _java_common_internal.incompatible_disable_non_executable_java_binary,
        incompatible_java_info_merge_runtime_module_flags = _java_common_internal._incompatible_java_info_merge_runtime_module_flags,
        target_kind = _java_common_internal.target_kind,
    )

java_common = struct(internal_DO_NOT_USE = _internal_exports)

_FakeJavaInfo = provider()  # buildifier: disable=provider-params
_FakeJavaPluginInfo = provider()  # buildifier: disable=provider-params
_FakeJavaToolchainInfo = provider()  # buildifier: disable=provider-params
_FakeJavaRuntimeInfo = provider()  # buildifier: disable=provider-params
_FakeBootClassPathInfo = provider()  # buildifier: disable=provider-params
_FakeJavaRuntimeClasspathInfo = provider()  # buildifier: disable=provider-params

java_common_export_for_bazel = struct(
    internal_DO_NOT_USE = _internal_exports,
    # fake exports for WORKSPACE loading
    provider = _FakeJavaInfo,
    JavaPluginInfo = _FakeJavaPluginInfo,
    JavaToolchainInfo = _FakeJavaToolchainInfo,
    JavaRuntimeInfo = _FakeJavaRuntimeInfo,
    BootClassPathInfo = _FakeBootClassPathInfo,
    JavaRuntimeClasspathInfo = _FakeJavaRuntimeClasspathInfo,
)
