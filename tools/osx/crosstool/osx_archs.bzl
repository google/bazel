"""Information regarding crosstool-supported architectures."""
# Copyright 2017 The Bazel Authors. All rights reserved.
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

# List of architectures supported by osx crosstool.
OSX_TOOLS_NON_DEVICE_ARCHS = [
    "darwin_x86_64",
    "darwin_arm64",
    "darwin_arm64e",
    "ios_i386",
    "ios_x86_64",
    "ios_sim_arm64",
    "watchos_i386",
    "watchos_x86_64",
    "tvos_x86_64",
]

OSX_TOOLS_ARCHS = [
    "ios_armv7",
    "ios_arm64",
    "ios_arm64e",
    "watchos_armv7k",
    "watchos_arm64_32",
    "tvos_arm64",
] + OSX_TOOLS_NON_DEVICE_ARCHS
