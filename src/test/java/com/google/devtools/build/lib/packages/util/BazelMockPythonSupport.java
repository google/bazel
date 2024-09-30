// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages.util;

import com.google.devtools.build.lib.bazel.rules.python.BazelPyBuiltins;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;

/** Mock python support in Bazel. */
public final class BazelMockPythonSupport extends MockPythonSupport {

  public static final BazelMockPythonSupport INSTANCE = new BazelMockPythonSupport();

  private BazelMockPythonSupport() {}

  private static void addTool(MockToolsConfig config, String toolRelativePath) throws IOException {
    config.create(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + toolRelativePath,
        ResourceLoader.readFromResources(TestConstants.BAZEL_REPO_PATH + toolRelativePath));
  }

  @Override
  public void setup(MockToolsConfig config) throws IOException {
    addTool(config, "tools/python/python_version.bzl");
    addTool(config, "tools/python/srcs_version.bzl");
    addTool(config, "tools/python/toolchain.bzl");
    addTool(config, "tools/python/utils.bzl");
    addTool(config, "tools/python/python_bootstrap_template.txt");

    config.create(
        TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/python/BUILD",
        "package(default_visibility=['//visibility:public'])",
        "load(':python_version.bzl', 'define_python_version_flag')",
        "load('//tools/python:toolchain.bzl', 'py_runtime_pair')",
        "define_python_version_flag(",
        "    name = 'python_version',",
        ")",
        "config_setting(",
        "    name = 'PY2',",
        "    flag_values = {':python_version': 'PY2'},",
        ")",
        "config_setting(",
        "    name = 'PY3',",
        "    flag_values = {':python_version': 'PY3'},",
        ")",
        "toolchain_type(name = 'toolchain_type')",
        "constraint_setting(name = 'py2_interpreter_path')",
        "constraint_setting(name = 'py3_interpreter_path')",
        "py_runtime(",
        "    name = 'py3_interpreter',",
        "    interpreter_path = '/usr/bin/mockpython3',",
        "    python_version = 'PY3',",
        ")",
        "py_runtime_pair(",
        "    name = 'default_py_runtime_pair',",
        "    py3_runtime = ':py3_interpreter',",
        ")",
        "toolchain(",
        "    # The Python workspace suffix looks to register a toolchain of this name.",
        "    name = 'autodetecting_toolchain',",
        "    toolchain = ':default_py_runtime_pair',",
        "    toolchain_type = ':toolchain_type',",
        ")",
        "exports_files(['precompile.py'])");

    // Copies and mock rules_python from real @rules_python
    config.create("rules_python_workspace/WORKSPACE", "workspace(name = 'rules_python')");
    config.create("rules_python_workspace/MODULE.bazel", "module(name = 'rules_python')");
    config.copyDirectory(PathFragment.create("rules_python+/"), "rules_python_workspace", 5, true);

    config.create("rules_python_workspace/python/BUILD",
        "alias(name = 'toolchain_type', actual = '@bazel_tools//tools/python:toolchain_type')",
        "toolchain_type(name = 'exec_tools_toolchain_type')");
    config.create("rules_python_workspace/python/private/BUILD");
    config.create("rules_python_workspace/python/private/common/BUILD");
    config.create("rules_python_workspace/python/config_settings/BUILD",

        "load(\"@bazel_skylib//rules:common_settings.bzl\", \"string_flag\")",
        "string_flag(name = 'precompile', build_setting_default = 'auto')",
        "string_flag(name = 'pyc_collection', build_setting_default = 'disabled')",
        "string_flag(name = 'precompile_source_retention', build_setting_default = 'auto')",
        "string_flag(name = 'bootstrap_impl', build_setting_default = 'system_python')",
        "string_flag(name = 'precompile_add_to_runfiles', build_setting_default = 'always')"
    );

    config.create("rules_python_workspace/tools/build_defs/python/private/BUILD");
    config.create("rules_python_workspace/tools/launcher/BUILD",
        "filegroup(name = 'launcher')");

    config.create("rules_python_internal_workspace/MODULE.bazel",
        "module(name = 'rules_python_internal')");
    config.create("rules_python_internal_workspace/BUILD");
    config.create("rules_python_internal_workspace/rules_python_config.bzl",
        "config = struct(enable_pystar = True)");

    config.create("rules_python_internal_workspace/py_internal.bzl",
        "load('@rules_python//tools/build_defs/python/private:py_internal_renamed.bzl', 'py_internal_renamed')",
        "py_internal_impl = py_internal_renamed");
  }

  @Override
  public String createPythonTopEntryPoint(MockToolsConfig config, String pyRuntimeLabel)
      throws IOException {
    // Under BazelPythonSemantics, we can simply set --python_top to be the py_runtime target.
    return pyRuntimeLabel;
  }

  @Override
  public com.google.devtools.build.lib.analysis.Runfiles.EmptyFilesSupplier getEmptyRunfilesSupplier() {
    return BazelPyBuiltins.GET_INIT_PY_FILES;
  }
}
