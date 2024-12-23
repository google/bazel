// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.view.cpp;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.ValueWithMetadata;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for CppCompileActionTemplate. */
@RunWith(TestParameterInjector.class)
public class CppTemplateTest extends BuildIntegrationTestCase {
  @Test
  public void irrelevantFile(@TestParameter boolean keepGoing) throws Exception {
    write(
        "tree/tree.bzl",
        "def _impl(ctx):",
        "  dir = ctx.actions.declare_directory('dir')",
        "  ctx.actions.run_shell(outputs = [dir], command = 'touch %s/file.cc %s/other.file' %"
            + " (dir.path, dir.path))",
        "  return [DefaultInfo(files = depset([dir]))]",
        "",
        "tree = rule(implementation = _impl)");
    write(
        "tree/BUILD",
        """
        load(":tree.bzl", "tree")

        tree(name = "lib")

        cc_library(
            name = "cc",
            srcs = [":lib"],
        )
        """);
    addOptions("--keep_going=" + keepGoing);
    // In addition to testing the specific functionality of erroring out when there is an irrelevant
    // file in the tree, this test also checks that error messages are properly cached in Skyframe.
    // When messages are improperly not stored, the error message for --nokeep-going is suppressed
    // after two runs because there are two actions (pic and non-pic), and whichever one
    // fails first prevents the other one from being committed to the graph on the first run. Then
    // the second run prints out the other's error message and only the third run shows no error.
    for (int i = 0; i < (keepGoing ? 1 : 3); i++) {
      assertThrows(BuildFailedException.class, () -> buildTarget("//tree:cc"));
      events.assertContainsError(
          Pattern.compile(
              "tree/BUILD:[0-9]+:[0-9]+: Compiling all C\\+\\+ files in tree/dir failed: Artifact"
                  + " '.*/tree/dir/other.file' expanded from the directory artifact '.*/tree/dir'"
                  + " is neither header nor source file"));
    }
  }

  private Path actionNamesBzl;

  @After
  public void maybeDeleteModifiedActionNamesBzl() throws IOException {
    if (actionNamesBzl != null) {
      actionNamesBzl.delete();
      actionNamesBzl = null;
    }
  }

  @Test
  public void badActionName() throws Exception {
    write(
        "tree/tree.bzl",
        """
        def _impl(ctx):
            dir = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(outputs = [dir], command = "touch %s/file.cc" % (dir.path))
            return [DefaultInfo(files = depset([dir]))]

        tree = rule(implementation = _impl)
        """);
    write(
        "tree/BUILD",
        """
        load(":tree.bzl", "tree")

        tree(name = "lib")

        cc_library(
            name = "cc",
            srcs = [":lib"],
        )
        """);
    // Hack up the workspace to make *.cc files not associated to any C++ action name. Should never
    // happen in practice.
    actionNamesBzl =
        getWorkspace().getRelative("third_party/bazel_rules/rules_cc/cc/action_names.bzl");
    String oldContents = FileSystemUtils.readContentToString(actionNamesBzl);
    // Don't overwrite the user's source tree on a local test run when this is a symlink.
    assertThat(actionNamesBzl.delete()).isTrue();
    String originalActionName = "CPP_COMPILE_ACTION_NAME = \"c++-compile\"";
    assertThat(oldContents).contains(originalActionName);
    FileSystemUtils.writeContent(
        actionNamesBzl,
        oldContents.replace(originalActionName, "CPP_COMPILE_ACTION_NAME = \"c++++++-compile\""));
    String writtenContents = FileSystemUtils.readContentToString(actionNamesBzl);
    assertThat(writtenContents).contains("\"c++++++-compile\"");
    assertThrows(BuildFailedException.class, () -> buildTarget("//tree:cc"));
    events.assertContainsError(
        Pattern.compile(
            "tree/BUILD:[0-9]+:[0-9]+: Compiling all C\\+\\+ files in tree/dir failed: Expected"
                + " action_config for 'c\\+\\+-compile' to be configured"));
  }

  @Test
  public void warningNotPersisted() throws Exception {
    write(
        "tree/tree.bzl",
        """
        def _impl(ctx):
            dir = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(outputs = [dir], command = "touch %s/file.cc" % (dir.path))
            return [DefaultInfo(files = depset([dir]))]

        tree = rule(implementation = _impl)
        """);
    write(
        "tree/BUILD",
        """
        load(":tree.bzl", "tree")

        tree(
            name = "lib",
            deprecation = "This is a warning",
        )
        """);
    write("cc/BUILD", "cc_library(name = 'cc', srcs = ['//tree:lib'])");
    buildTarget("//cc:cc");
    assertContainsEvent(EventKind.WARNING, "This is a warning");
    getSkyframeExecutor()
        .getEvaluator()
        .getDoneValues()
        .forEach(
            (k, v) ->
                assertWithMessage("Node " + k + " warnings")
                    .that(ValueWithMetadata.getEvents(v).toList())
                    .isEmpty());

    // Warning is not replayed on a no-op incremental build.
    buildTarget("//cc:cc");
    assertDoesNotContainEvent("This is a warning");
  }
}
