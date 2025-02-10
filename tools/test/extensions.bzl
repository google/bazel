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
"""A module extension to bring in the remote coverage tools under
@remote_coverage_tools."""

load("//tools/build_defs/repo:http.bzl", "http_archive")

def _remote_coverage_tools_extension_impl(ctx):
    http_archive(
        name = "remote_coverage_tools",
        sha256 = "172be177db06b16632335f27d50cee0786fb1873df344852db71b2171cd6d996",
        urls = [
            "https://mirror.bazel.build/bazel_coverage_output_generator/releases/coverage_output_generator-v2.8.zip",
        ],
    )
    return ctx.extension_metadata(reproducible = True)

remote_coverage_tools_extension = module_extension(
    implementation = _remote_coverage_tools_extension_impl,
)
