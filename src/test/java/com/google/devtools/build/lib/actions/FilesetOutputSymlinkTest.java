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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FilesetOutputSymlink}. */
@RunWith(JUnit4.class)
public final class FilesetOutputSymlinkTest {

  private static final PathFragment EXEC_ROOT = PathFragment.create("/example/execroot");

  private static FilesetOutputSymlink createSymlinkTo(String path) {
    return FilesetOutputSymlink.createForTesting(
        PathFragment.create("any/path"), PathFragment.create(path), EXEC_ROOT);
  }

  @Test
  public void stripsExecRootFromTarget() {
    FilesetOutputSymlink symlink = createSymlinkTo("/example/execroot/some/path");
    PathFragment targetPath = symlink.targetPath();
    assertThat(targetPath.getPathString()).isEqualTo("some/path");
  }

  @Test
  public void reconstitutesTargetPath_underExecRoot() {
    FilesetOutputSymlink symlink = createSymlinkTo("/example/execroot/some/path");
    PathFragment targetPath = symlink.reconstituteTargetPath(EXEC_ROOT);
    assertThat(targetPath.getPathString()).isEqualTo("/example/execroot/some/path");
  }

  @Test
  public void reconstitutesTargetPath_differentExecRoot() throws Exception {
    FilesetOutputSymlink symlink = createSymlinkTo("/example/execroot/some/path");
    PathFragment targetPath = symlink.reconstituteTargetPath(PathFragment.create("/new/execroot"));
    assertThat(targetPath.getPathString()).isEqualTo("/new/execroot/some/path");
  }

  @Test
  public void reconstitutesTargetPath_notUnderExecRoot() {
    FilesetOutputSymlink symlink = createSymlinkTo("some/path");
    PathFragment targetPath = symlink.reconstituteTargetPath(EXEC_ROOT);
    assertThat(targetPath.getPathString()).isEqualTo("some/path");
  }
}
