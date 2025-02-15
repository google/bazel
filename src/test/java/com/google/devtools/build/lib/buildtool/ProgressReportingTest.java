// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for progress reporting.
 */
@RunWith(JUnit4.class)
public class ProgressReportingTest extends BuildIntegrationTestCase {
  private enum PathOp {
    DELETE,
  }

  @FunctionalInterface
  private interface Receiver {
    void accept(PathFragment path, PathOp op);
  }

  private Receiver receiver = (x, y) -> {};

  @Override
  protected ImmutableSet<EventKind> additionalEventsToCollect() {
    return ImmutableSet.of(EventKind.PROGRESS, EventKind.START);
  }

  @Override
  protected FileSystem createFileSystem() {
    return new UnixFileSystem(DigestHashFunction.SHA256, /*hashAttributeName=*/ "") {
      private void recordAccess(PathOp op, PathFragment path) {
        if (receiver != null) {
          receiver.accept(path, op);
        }
      }

      @Override
      protected boolean delete(PathFragment path) throws IOException {
        recordAccess(PathOp.DELETE, path);
        return super.delete(path);
      }
    };
  }

  /**
   * Tests that [for tool] tags are added to the progress messages of actions in the exec
   * configuration, but not in the target configuration.
   */
  @Test
  public void testAdditionalInfo() throws Exception {
    AnalysisMock.get().pySupport().setup(mockToolsConfig);
    write(
        "x/BUILD",
        "genrule(name = 'tool',",
        "          outs = ['sometool'],",
        "          cmd = 'touch $@')",
        "genrule(name = 'x',",
        "        outs = ['out'],"
            + "        cmd = 'echo test > $@',"
            + "        tools = [':tool'])");

    buildTarget("//x");

    assertContainsEvent("Executing genrule //x:tool [for tool]");
    assertContainsEvent("Executing genrule //x:x");
    assertDoesNotContainEvent("Executing genrule //x:x [for tool]");
  }

  @Test
  public void testPreparingMessage() throws Exception {
    write(
        "x/BUILD",
        """
        genrule(
            name = "x",
            outs = ["slowdelete"],
            cmd = "touch $@",
        )
        """);
    buildTarget("//x");
    Path output = Iterables.getOnlyElement(getArtifacts("//x:x")).getPath();
    assertThat(output.delete()).isTrue();
    receiver =
        (path, op) -> {
          if (output.asFragment().equals(path) && op == PathOp.DELETE) {
            try {
              // When the action tries to delete its outputs (during the "preparing" stage of action
              // execution), we block on the deletion for enough time that the status reporter
              // prints out a "Preparing:" progress message.
              Thread.sleep(4000);
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          }
        };
    addOptions("--progress_report_interval=1");

    buildTarget("//x");
    assertContainsEvent("Preparing:");
    assertContainsEvent("Executing genrule //x:x");
  }

  @Test
  public void testWaitForResources() throws Exception {
    write(
        "x/BUILD",
        """
        genrule(
            name = "x",
            outs = ["x.out"],
            cmd = "sleep 3; touch $@",
            local = 1,
        )

        genrule(
            name = "y",
            outs = ["y.out"],
            cmd = "sleep 3; touch $@",
            local = 1,
        )
        """);
    // GenRuleAction currently specifies 300,1.0,0.0. If that changes, this may have to be changed
    // in order to keep exactly one genrule running at a time.
    addOptions(
        "--progress_report_interval=1",
        "--local_resources=memory=1000",
        "--local_resources=cpu=1",
        "--show_progress_rate_limit=-1");
    buildTarget("//x:x", "//x:y");

    assertContainsEvent("Scheduling:");
    assertContainsEvent("Executing genrule //x:x");
    assertContainsEvent("Executing genrule //x:y");
  }
}
