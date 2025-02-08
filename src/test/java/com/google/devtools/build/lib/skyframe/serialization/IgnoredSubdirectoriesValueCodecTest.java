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
package com.google.devtools.build.lib.skyframe.serialization;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@code IgnoredSubdirectoriesValueCodec}. */
@RunWith(JUnit4.class)
public class IgnoredSubdirectoriesValueCodecTest {

  private static ImmutableSet<PathFragment> prefixes(String... prefixes) {
    return Arrays.stream(prefixes).map(PathFragment::create).collect(ImmutableSet.toImmutableSet());
  }

  private static ImmutableList<String> patterns(String... patterns) {
    return ImmutableList.copyOf(patterns);
  }

  @Test
  public void testCodec() throws Exception {
    new SerializationTester(
            IgnoredSubdirectoriesValue.of(prefixes(), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes("foo"), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes("foo", "bar/moo"), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo")),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo")),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo/**")),
            IgnoredSubdirectoriesValue.of(prefixes("foo"), patterns("foo/**")))
        .runTests();
  }
}
