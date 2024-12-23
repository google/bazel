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

package com.google.devtools.build.lib.buildtool.util;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Infrastructure to support Skyframe integration tests. */
public abstract class SkyframeIntegrationTestBase extends BuildIntegrationTestCase {

  protected SkyframeExecutor skyframeExecutor() {
    return runtimeWrapper.getSkyframeExecutor();
  }

  protected static List<WeakReference<?>> weakRefs(Object... strongRefs) throws Exception {
    List<WeakReference<?>> result = new ArrayList<>();
    for (Object ref : strongRefs) {
      result.add(new WeakReference<>(ref));
    }
    return result;
  }

  protected static void assertAllReleased(Iterable<WeakReference<?>> refs) {
    for (WeakReference<?> ref : refs) {
      GcFinalization.awaitClear(ref);
    }
  }

  private String makeGenruleContents(String value) {
    return String.format(
        "genrule(name='target', outs=['out'], cmd='/bin/echo %s > $(location out)')", value);
  }

  protected void writeGenrule(String filename, String value) throws Exception {
    write(filename, makeGenruleContents(value));
  }

  protected void writeGenruleAbsolute(Path file, String value) throws Exception {
    writeAbsolute(file, makeGenruleContents(value));
  }

  protected void assertCharContentsIgnoringOrderAndWhitespace(
      String expectedCharContents, String target) throws Exception {
    Path path = Iterables.getOnlyElement(getArtifacts(target)).getPath();
    char[] actualChars = FileSystemUtils.readContentToString(path).toCharArray();
    char[] expectedChars = expectedCharContents.toCharArray();
    Arrays.sort(actualChars);
    Arrays.sort(expectedChars);
    assertThat(new String(actualChars).trim()).isEqualTo(new String(expectedChars).trim());
  }

  protected ImmutableList<String> getOnlyOutputContentAsLines(String target) throws Exception {
    return FileSystemUtils.readLines(Iterables.getOnlyElement(getArtifacts(target)).getPath());
  }
}
