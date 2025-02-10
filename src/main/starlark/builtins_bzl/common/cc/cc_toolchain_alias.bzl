# Copyright 2022 The Bazel Authors. All rights reserved.
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

"""Starlark implementation of cc_toolchain_alias rule."""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/semantics.bzl", "semantics")

CcToolchainInfo = cc_common.CcToolchainInfo
TemplateVariableInfo = _builtins.toplevel.platform_common.TemplateVariableInfo
ToolchainInfo = _builtins.toplevel.platform_common.ToolchainInfo

def _impl(ctx):
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx, mandatory = ctx.attr.mandatory)
    if not cc_toolchain:
        return []
    make_variables = cc_toolchain._additional_make_variables
    cc_provider_make_variables = cc_helper.get_toolchain_global_make_variables(cc_toolchain)
    template_variable_info = TemplateVariableInfo(make_variables | cc_provider_make_variables)
    toolchain = ToolchainInfo(
        cc = cc_toolchain,
        cc_provider_in_toolchain = True,
    )
    return [
        cc_toolchain,
        toolchain,
        template_variable_info,
        DefaultInfo(
            files = cc_toolchain._all_files_including_libc,
        ),
    ]

cc_toolchain_alias = rule(
    implementation = _impl,
    fragments = ["cpp", "platform"],
    attrs = {
        "mandatory": attr.bool(default = True),
    },
    toolchains = cc_helper.use_cpp_toolchain() +
                 semantics.get_runtimes_toolchain(),
)
