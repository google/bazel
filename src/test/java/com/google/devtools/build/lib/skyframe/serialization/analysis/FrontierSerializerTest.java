// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting;
import static org.mockito.Mockito.mock;

import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FrontierSerializerTest extends FrontierSerializerTestBase {
  private final LongVersionGetter versionGetter = mock(LongVersionGetter.class);

  @Before
  public void injectVersionGetter() {
    injectVersionGetterForTesting(versionGetter);
  }

  private class ModuleWithOverrides extends SerializationModule {

    @Override
    protected FingerprintValueService.Factory getFingerprintValueServiceFactory() {
      // service is re-instantiated for each test case with a @Before setup step.
      return (unused) -> service;
    }
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder().addBlazeModule(new ModuleWithOverrides());
  }

  @Test
  public void buildCommand_uploadsFrontierBytesWithUploadMode() throws Exception {
    setupScenarioWithAspects();

    var listener = getCommandEnvironment().getRemoteAnalysisCachingEventListener();
    assertThat(listener.getSerializedKeysCount()).isAtLeast(9); // for Bazel
    assertThat(listener.getSkyfunctionCounts().count(SkyFunctions.CONFIGURED_TARGET))
        .isAtLeast(9); // for Bazel

    assertContainsEvent("Waiting for write futures took an additional");
  }
}
