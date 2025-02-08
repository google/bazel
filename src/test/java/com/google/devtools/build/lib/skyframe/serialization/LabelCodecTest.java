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

package com.google.devtools.build.lib.skyframe.serialization;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Basic tests for {@link Label}'s codec. */
@RunWith(TestParameterInjector.class)
public class LabelCodecTest {

  @Test
  public void testCodec(@TestParameter boolean useSharedValues) throws Exception {
    var tester = new SerializationTester(Label.parseCanonical("//foo/bar:baz"));

    if (useSharedValues) {
      tester
          .addCodec(new ValueSharingAdapter<>(Label.deferredCodec()))
          .makeMemoizingAndAllowFutureBlocking(true);
    }

    tester.runTests();
  }

}
