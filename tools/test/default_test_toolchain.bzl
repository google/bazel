# Copyright 2025 The Bazel Authors. All rights reserved.
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

# A bool-typed build setting that can be set on the command line.

visibility("private")

bool_flag = rule(
    implementation = lambda _: None,
    build_setting = config.bool(flag = True),
    doc = "A bool-typed build setting that can be set on the command line",
)

empty_toolchain = rule(
    implementation = lambda ctx: platform_common.ToolchainInfo(),
)
