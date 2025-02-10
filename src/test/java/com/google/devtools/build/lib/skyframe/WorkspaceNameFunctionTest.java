// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkspaceNameValue} and {@link WorkspaceNameFunction}. */
@RunWith(JUnit4.class)
public class WorkspaceNameFunctionTest extends BuildViewTestCase {
  private final SkyKey key = WorkspaceNameValue.key();

  private EvaluationResult<WorkspaceNameValue> eval()
      throws InterruptedException, AbruptExitException {
    getSkyframeExecutor()
        .invalidateFilesUnderPathForTesting(
            reporter,
            ModifiedFileSet.builder().modify(PathFragment.create("WORKSPACE")).build(),
            Root.fromPath(rootDirectory));
    return SkyframeExecutorTestUtils.evaluate(
        getSkyframeExecutor(), key, /*keepGoing=*/ false, reporter);
  }

  @Test
  public void bzlmod() throws Exception {
    scratch.overwriteFile("WORKSPACE", "workspace(name = 'good')");
    assertThatEvaluationResult(eval())
        .hasEntryThat(key)
        .isEqualTo(WorkspaceNameValue.withName(ruleClassProvider.getRunfilesPrefix()));
  }

  @Test
  public void testEqualsAndHashCode() {
    new EqualsTester()
        .addEqualityGroup(WorkspaceNameValue.withName("foo"), WorkspaceNameValue.withName("foo"))
        .addEqualityGroup(WorkspaceNameValue.withName("bar"), WorkspaceNameValue.withName("bar"))
        .testEquals();
  }
}
