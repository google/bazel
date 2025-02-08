// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.blackbox.tests.workspace;

import com.google.devtools.build.lib.blackbox.framework.PathUtils;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Generator of the test local repository with:
 *
 * <p>helper.bzl file, that contains a write_to_file rule, which writes some text to the output
 * file.
 *
 * <p>empty MODULE.bazel file,
 *
 * <p>BUILD file, with write_to_file target and pkg_tar target for packing the contents of the
 * generated repository.
 *
 * <p>Intended to be used by workspace tests.
 */
public class RepoWithRuleWritingTextGenerator {

  static final String HELPER_FILE = "helper.bzl";
  static final String RULE_NAME = "write_to_file";
  static final String HELLO = "HELLO";
  static final String TARGET = "write_text";
  static final String OUT_FILE = "out";

  static final String WRITE_TEXT_TO_FILE =
      "def _impl(ctx):\n"
          + "  out = ctx.actions.declare_file(ctx.attr.filename)\n"
          + "  ctx.actions.write(out, ctx.attr.text)\n"
          + "  return [DefaultInfo(files = depset([out]))]\n"
          + "\n"
          + RULE_NAME
          + " = rule(\n"
          + "    implementation = _impl,\n"
          + "    attrs = {\n"
          + "        \"filename\": attr.string(default = \"out\"),\n"
          + "        \"text\": attr.string()\n"
          + "    }\n"
          + ")";

  private final Path root;
  private String target;
  private String outputText;
  private String outFile;
  private boolean generateBuildFile;

  /**
   * Generator constructor
   *
   * @param root - the Path to the directory, where the repository contents should be placed
   */
  RepoWithRuleWritingTextGenerator(Path root) {
    this.root = root;
    this.target = TARGET;
    this.outputText = HELLO;
    this.outFile = OUT_FILE;
    generateBuildFile = true;
  }

  /**
   * Specifies the text to be put into generated file by write_to_file rule target
   *
   * @param text - text to be put into a file
   * @return this generator
   */
  RepoWithRuleWritingTextGenerator withOutputText(String text) {
    outputText = text;
    return this;
  }

  /**
   * Specifies the name of the write_to_file target in the generated repository
   *
   * @param name - name of the target
   * @return this generator
   */
  RepoWithRuleWritingTextGenerator withTarget(String name) {
    target = name;
    return this;
  }

  /**
   * Specifies the output file name of the write_to_file rule target
   *
   * @param name - output file name
   * @return this generator
   */
  RepoWithRuleWritingTextGenerator withOutFile(String name) {
    outFile = name;
    return this;
  }

  /**
   * Specifies that BUILD file should not be generated
   *
   * @return this generator
   */
  RepoWithRuleWritingTextGenerator skipBuildFile() {
    generateBuildFile = false;
    return this;
  }

  /**
   * Generates the repository: MODULE.bazel, BUILD, and helper.bzl files.
   *
   * @return repository directory
   * @throws IOException if was not able to create or write to files
   */
  Path setupRepository() throws IOException {
    Path workspace = PathUtils.writeFileInDir(root, "MODULE.bazel");
    PathUtils.writeFileInDir(root, HELPER_FILE, WRITE_TEXT_TO_FILE);
    if (generateBuildFile) {
      PathUtils.writeFileInDir(
          root,
          "BUILD",
          "load(\"@bazel_tools//tools/build_defs/pkg:pkg.bzl\", \"pkg_tar\")",
          loadRule(""),
          callRule(target, outFile, outputText),
          String.format("pkg_tar(name = \"%s\", srcs = glob([\"*\"]),)", getPkgTarTarget()));
    }
    return workspace.getParent();
  }

  /**
   * Returns the text to be put into a header of Starlark file, which is going to use write_to_file
   * rule from the @repoName repository
   *
   * @param repoName the name of the repository
   * @return load statement text
   */
  static String loadRule(String repoName) {
    return String.format("load('%s//:%s', '%s')", repoName, HELPER_FILE, RULE_NAME);
  }

  /**
   * Returns the text with the write_to_file target
   *
   * @param name target name
   * @param filename name of the output file
   * @param text text to be put into output file
   * @return the write_to_file target definition
   */
  static String callRule(String name, String filename, String text) {
    return String.format(
        "%s(name = '%s', filename = '%s', text ='%s')", RULE_NAME, name, filename, text);
  }

  /** @return name of the generated pkg_tar target */
  String getPkgTarTarget() {
    return "pkg_tar_" + target;
  }
}
