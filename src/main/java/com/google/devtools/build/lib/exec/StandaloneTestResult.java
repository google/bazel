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

package com.google.devtools.build.lib.exec;

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.analysis.test.TestActionContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;

/**
 * Contains information about the results of test execution.
 *
 * @param spawnResults Returns the SpawnResults created by the test, if any.
 * @param testResultDataBuilder Returns the TestResultData for the test.
 */
public record StandaloneTestResult(
    @Override ImmutableList<SpawnResult> spawnResults,
    TestResultData.Builder testResultDataBuilder,
    BuildEventStreamProtos.TestResult.ExecutionInfo executionInfo)
    implements TestActionContext.TestAttemptResult {
  public StandaloneTestResult {
    requireNonNull(spawnResults, "spawnResults");
    requireNonNull(testResultDataBuilder, "testResultDataBuilder");
    requireNonNull(executionInfo, "executionInfo");
  }

  @Override
  public TestActionContext.TestAttemptResult.Result result() {
    // TODO(b/148785690): Establish proper retry policy for flaky tests in StandaloneTestStrategy.
    return testResultDataBuilder().getStatus() == BlazeTestStatus.PASSED
        ? Result.PASSED
        : Result.FAILED_CAN_RETRY;
  }

  /** Returns a builder that can be used to construct a {@link StandaloneTestResult} object. */
  public static Builder builder() {
    return new AutoBuilder_StandaloneTestResult_Builder();
  }

  /** Builder for a {@link StandaloneTestResult} instance, which is immutable once built. */
  @AutoBuilder
  public abstract static class Builder {

    /** Returns the SpawnResults for the test, if any. */
    abstract ImmutableList<SpawnResult> spawnResults();

    /** Sets the SpawnResults for the test. */
    public abstract Builder setSpawnResults(ImmutableList<SpawnResult> spawnResults);

    /** Sets the TestResultData for the test. */
    public abstract Builder setTestResultDataBuilder(TestResultData.Builder testResultDataBuilder);

    public abstract Builder setExecutionInfo(
        BuildEventStreamProtos.TestResult.ExecutionInfo executionInfo);

    abstract StandaloneTestResult realBuild();

    /**
     * Returns an immutable StandaloneTestResult object.
     *
     * <p>The list of SpawnResults is also made immutable here.
     */
    public StandaloneTestResult build() {
      return this.setSpawnResults(spawnResults()).realBuild();
    }
  }
}
