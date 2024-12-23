// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TrackSourceDirectoriesFlag}. */
@RunWith(TestParameterInjector.class)
public final class TrackSourceDirectoriesIntegrationTest extends BuildIntegrationTestCase {

  @Override
  protected ImmutableSet<EventKind> additionalEventsToCollect() {
    return ImmutableSet.of(EventKind.FINISH);
  }

  @BeforeClass
  public static void checkTrackSourceDirectoriesFlag() {
    // Enabled via jvm_flags.
    assertThat(TrackSourceDirectoriesFlag.trackSourceDirectories()).isTrue();
  }

  @Test
  public void build_unchangedSourceDirectory_doesNotRebuild() throws Exception {
    getWorkspace().getRelative("pkg/dir/empty_dir").createDirectoryAndParents();
    write("pkg/dir/file", "foo");
    write(
        "pkg/BUILD",
        """
        genrule(
            name = "a",
            srcs = ["dir"],
            outs = ["out"],
            cmd = "touch $@",
        )
        """);

    String testTarget = "//pkg:a";
    String testTargetRebuildsEvent = "Executing genrule " + testTarget;

    // Initial build
    buildTarget(testTarget);
    assertContainsEvent(testTargetRebuildsEvent);
    events.collector().clear();

    // Verify that the target doesn't rebuild without changes.
    buildTarget(testTarget);

    assertDoesNotContainEvent(testTargetRebuildsEvent);
  }

  private enum Change {
    CREATE_EMPTY_DIRECTORY {
      @Override
      void apply(Path sourceDirectory) throws IOException {
        sourceDirectory.getRelative("empty_dir/nested_empty_dir").createDirectory();
      }
    },
    CREATE_EMPTY_FILE_IN_EMPTY_DIRECTORY {
      @Override
      void apply(Path sourceDirectory) throws IOException {
        FileSystemUtils.createEmptyFile(sourceDirectory.getRelative("empty_dir/file"));
      }
    },
    REMOVE_EMPTY_DIRECTORY {
      @Override
      void apply(Path sourceDirectory) throws IOException {
        sourceDirectory.getChild("empty_dir").deleteTree();
      }
    },
    REPLACE_EMPTY_DIRECTORY_WITH_EMPTY_FILE {
      @Override
      void apply(Path sourceDirectory) throws IOException {
        sourceDirectory.getChild("empty_dir").deleteTree();
        FileSystemUtils.createEmptyFile(sourceDirectory.getChild("empty_dir"));
      }
    },
    CHANGE_FILE_CONTENT {
      @Override
      void apply(Path sourceDirectory) throws IOException {
        FileSystemUtils.writeContent(sourceDirectory.getChild("file"), "changed");
      }
    };

    abstract void apply(Path sourceDirectory) throws IOException;
  }

  @Test
  public void build_changedSourceDirectory_rebuildsTarget(@TestParameter Change change)
      throws Exception {
    getWorkspace().getRelative("pkg/dir/empty_dir").createDirectoryAndParents();
    write("pkg/dir/file", "foo");
    write(
        "pkg/BUILD",
        """
        genrule(
            name = "a",
            srcs = ["dir"],
            outs = ["out"],
            cmd = "touch $@",
        )
        """);

    String testTarget = "//pkg:a";
    String testTargetRebuildsEvent = "Executing genrule " + testTarget;

    // Initial build
    buildTarget(testTarget);
    assertContainsEvent(testTargetRebuildsEvent);
    events.collector().clear();

    // Change source directory and verify that the target is rebuilt as expected.
    change.apply(getWorkspace().getRelative("pkg/dir"));
    buildTarget(testTarget);

    assertContainsEvent(testTargetRebuildsEvent);
  }
}
