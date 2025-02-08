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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.vfs.Path;
import java.io.ByteArrayOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test that SUBCOMMAND events report command lines in a form than can be "replayed" by copy+paste
 * to the shell.
 */
@RunWith(JUnit4.class)
public class SubcommandEventTest extends BuildIntegrationTestCase {

  @Before
  public void stageEmbeddedTools() throws Exception {
    addOptions("--spawn_strategy=standalone");
  }

  @Test
  public void testSubcommandEvent() throws Exception {
    EventCollector eventCollector = new EventCollector(EventKind.SUBCOMMAND);
    events.addHandler(eventCollector);
    runtimeWrapper.addOptions("--subcommands");

    write(
        "hello/BUILD",
        """
        genrule(
            name = "hello",
            outs = ["hello.out"],
            cmd = 'echo "Hello, World!" > $(location hello.out)',
        )
        """);

    // (1) Ensure that building the target creates the output:
    buildTarget("//hello");
    Path helloOut = Iterables.getOnlyElement(getArtifacts("//hello:hello.out")).getPath();
    assertThat(helloOut.isFile()).isTrue();
    assertThat(helloOut.getFileSize()).isEqualTo(14);

    // (2) Delete the output:
    helloOut.delete();
    assertThat(helloOut.exists()).isFalse();

    // (3) Test that the message in the SUBCOMMAND event replays the action:
    String command = null;
    for (Event event : eventCollector) {
      command = event.getMessage();
      if (command.contains("World")) {
        break;
      }
    }
    assertThat(
            new Command(new String[] {"/bin/sh", "-c", command})
                .execute(new ByteArrayOutputStream(), new ByteArrayOutputStream())
                .terminationStatus()
                .success())
        .isTrue();
    assertThat(helloOut.isFile()).isTrue();
    assertThat(helloOut.getFileSize()).isEqualTo(14);
  }
}
