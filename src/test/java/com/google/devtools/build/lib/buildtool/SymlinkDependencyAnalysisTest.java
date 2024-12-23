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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test that symbolic links are handled correctly by the dependency analysis:
 * that changes of the link target cause a rebuild.
 */
@RunWith(JUnit4.class)
public class SymlinkDependencyAnalysisTest extends BuildIntegrationTestCase {

  private String buildAndReturnOutput() throws Exception {
    buildTarget("//symlink");
    return readToString(getOnlyElement(getArtifacts("//symlink:out")));
  }

  @Test
  public void testSymlinkTargetChangeCausesRebuild() throws Exception {
    Path buildFile =
        write(
            "symlink/BUILD",
            """
            genrule(
                name = "symlink",
                srcs = ["link"],
                outs = ["out"],
                cmd = "/bin/cp $(location link) $(location out)",
            )
            """);
    Path target = write("symlink/target", "foo");

    Path link = buildFile.getParentDirectory().getChild("link");
    link.createSymbolicLink(target);

    target.setLastModifiedTime(10000);
    assertThat(buildAndReturnOutput()).isEqualTo("foo\n"); // first build

    write("symlink/target", "bar");
    target.setLastModifiedTime(20000);
    assertThat(buildAndReturnOutput()).isEqualTo("bar\n"); // should do a rebuild
  }
}
