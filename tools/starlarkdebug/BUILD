# Copyright 2018 The Bazel Authors. All rights reserved.
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

package(default_visibility = ["//visibility:public"])

py_library(
    name = "debugger",
    srcs = ["debugger.py"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/starlarkdebug/proto:starlark_debugging_py_proto_library",
    ],
    imports = ["."],
)

py_binary(
    name = "trace",
    srcs = ["trace.py"],
    deps = [":debugger"],
)

py_library(
    name = "python_sandbox",
    srcs = [
        "testsupport/__init__.py",
        "testsupport/python_sandbox.py",
    ],
    imports = ["."],
)

py_test(
    name = "debugger_test",
    srcs = ["debugger_test.py"],
    deps = [
        ":debugger",
        ":python_sandbox",
    ],
)