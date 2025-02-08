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

package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link PlatformLookupUtil}. */
@RunWith(JUnit4.class)
public class PlatformLookupUtilTest extends ToolchainTestCase {

  /**
   * An {@link AnalysisMock} that injects {@link GetPlatformInfoFunction} into the Skyframe
   * executor.
   */
  private static final class AnalysisMockWithGetPlatformInfoFunction extends AnalysisMock.Delegate {
    AnalysisMockWithGetPlatformInfoFunction() {
      super(AnalysisMock.get());
    }

    @Override
    public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
        BlazeDirectories directories) {
      return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
          .putAll(super.getSkyFunctions(directories))
          .put(GET_PLATFORM_INFO_FUNCTION, new GetPlatformInfoFunction())
          .buildOrThrow();
    }
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    return new AnalysisMockWithGetPlatformInfoFunction();
  }

  @Test
  public void testPlatformLookup() throws Exception {
    ConfiguredTargetKey linuxKey =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//platforms:linux"))
            .setConfigurationKey(targetConfigKey)
            .build();
    ConfiguredTargetKey macKey =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//platforms:mac"))
            .setConfigurationKey(targetConfigKey)
            .build();
    GetPlatformInfoKey key = GetPlatformInfoKey.create(ImmutableList.of(linuxKey, macKey));

    EvaluationResult<GetPlatformInfoValue> result = getPlatformInfo(key);

    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(key).isNotNull();

    Map<ConfiguredTargetKey, PlatformInfo> platforms = result.get(key).platforms();
    assertThat(platforms).containsEntry(linuxKey, linuxPlatform);
    assertThat(platforms).containsEntry(macKey, macPlatform);
    assertThat(platforms).hasSize(2);
  }

  @Test
  public void testPlatformLookup_targetNotPlatform() throws Exception {
    scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')");

    ConfiguredTargetKey targetKey =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//invalid:not_a_platform"))
            .setConfigurationKey(targetConfigKey)
            .build();
    GetPlatformInfoKey key = GetPlatformInfoKey.create(ImmutableList.of(targetKey));

    EvaluationResult<GetPlatformInfoValue> result = getPlatformInfo(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("//invalid:not_a_platform");
  }

  @Test
  public void testPlatformLookup_targetDoesNotExist() throws Exception {
    ConfiguredTargetKey targetKey =
        ConfiguredTargetKey.builder()
            .setLabel(Label.parseCanonicalUnchecked("//fake:missing"))
            .setConfigurationKey(targetConfigKey)
            .build();
    GetPlatformInfoKey key = GetPlatformInfoKey.create(ImmutableList.of(targetKey));

    EvaluationResult<GetPlatformInfoValue> result = getPlatformInfo(key);

    assertThatEvaluationResult(result).hasError();
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .isInstanceOf(InvalidPlatformException.class);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(key)
        .hasExceptionThat()
        .hasMessageThat()
        .contains("no such package 'fake': BUILD file not found");
  }

  // Calls PlatformLookupUtil.getPlatformInfo.
  private static final SkyFunctionName GET_PLATFORM_INFO_FUNCTION =
      SkyFunctionName.createHermetic("GET_PLATFORM_INFO_FUNCTION");

  @AutoCodec
  record GetPlatformInfoKey(ImmutableList<ConfiguredTargetKey> platformKeys) implements SkyKey {
    GetPlatformInfoKey {
      requireNonNull(platformKeys, "platformKeys");
    }

    @Override
    public SkyFunctionName functionName() {
      return GET_PLATFORM_INFO_FUNCTION;
    }

    public static GetPlatformInfoKey create(ImmutableList<ConfiguredTargetKey> platformKeys) {
      return new GetPlatformInfoKey(platformKeys);
    }
  }

  private EvaluationResult<GetPlatformInfoValue> getPlatformInfo(GetPlatformInfoKey key)
      throws InterruptedException {
    try {
      // Must re-enable analysis for Skyframe functions that create configured targets.
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(true);
      return SkyframeExecutorTestUtils.evaluate(
          skyframeExecutor, key, /*keepGoing=*/ false, reporter);
    } finally {
      skyframeExecutor.getSkyframeBuildView().enableAnalysis(false);
    }
  }

  @AutoCodec
  record GetPlatformInfoValue(Map<ConfiguredTargetKey, PlatformInfo> platforms)
      implements SkyValue {
    GetPlatformInfoValue {
      requireNonNull(platforms, "platforms");
    }

    static GetPlatformInfoValue create(Map<ConfiguredTargetKey, PlatformInfo> platforms) {
      return new GetPlatformInfoValue(platforms);
    }
  }

  private static final class GetPlatformInfoFunction implements SkyFunction {

    @Nullable
    @Override
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws SkyFunctionException, InterruptedException {
      GetPlatformInfoKey key = (GetPlatformInfoKey) skyKey;
      try {
        Map<ConfiguredTargetKey, PlatformInfo> platforms =
            PlatformLookupUtil.getPlatformInfo(key.platformKeys(), env);
        if (env.valuesMissing()) {
          return null;
        }
        return GetPlatformInfoValue.create(platforms);
      } catch (InvalidPlatformException e) {
        throw new GetPlatformInfoFunctionException(e);
      }
    }
  }

  private static final class GetPlatformInfoFunctionException extends SkyFunctionException {
    GetPlatformInfoFunctionException(InvalidPlatformException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
