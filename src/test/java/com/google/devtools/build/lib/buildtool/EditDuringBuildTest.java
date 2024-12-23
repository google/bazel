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

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.vfs.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Regression test for a stable inconsistent state.
 *
 * <p>This shows that the problem described in bug #730818 and discussed in the reviewlog for OCL
 * 4645304 is fixed for the case where the inputs are known prior to execution (i.e. currently all
 * actions except C++ compilation)--see testEditDuringBuild.
 *
 * <p>However, in the case of C++ compilation, since we don't know the inputs ahead of time, must
 * stat the input files after execution, which leads to the race condition. The long term fix is to
 * use a precise C++ dependency scanner and eliminate support for the {@code !inputsDiscovered()}
 * case; in the meantime, things are unsound. Luckily, the risk is small, since it's only the clean
 * build that is exposed to the problem, and the odds of editing a sourcefile just as it is being
 * compiled is much smaller in a clean (long) build than in an incremental (short) build.
 */
@RunWith(JUnit4.class)
public class EditDuringBuildTest extends BuildIntegrationTestCase {

  @Test
  public void testEditDuringBuild() throws Exception {
    Path in = write("edit/in", "line1");
    in.setLastModifiedTime(123456789);

    // Modify the actual source file, not a sandboxed copy.
    addOptions("--spawn_strategy=local");

    // The "echo" effects editing of the source file during the build:
    write("edit/BUILD",
          "genrule(name = 'edit',",
          "        srcs = ['in'],",
          "        outs = ['out'],",
          "        cmd = '/bin/cp $(location in) $(location out) && "
                       + "echo line2 >>$(location in)')");

    // Edit during build => undefined result (in fact, "line1")
    String out = buildAndReadOutputFile();
    assertThat(out).isEqualTo("line1\n");

    // No edit during build => build should restore consistency.
    out = buildAndReadOutputFile();
    assertThat(out).isEqualTo("line1\nline2\n");
  }

  private String buildAndReadOutputFile() throws Exception {
    buildTarget("//edit:out");
    return readToString(Iterables.getOnlyElement(getArtifacts("//edit:out")));
  }
}
