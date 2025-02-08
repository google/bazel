// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.starlarkbuildapi.test.ExecutionInfoApi;
import java.util.Map;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;

/**
 * This provider can be implemented by rules which need special environments to run in (especially
 * tests).
 */
@Immutable
public final class ExecutionInfo extends NativeInfo implements ExecutionInfoApi {

  /** Starlark constructor and identifier for ExecutionInfo. */
  public static final ExecutionInfoProvider PROVIDER = new ExecutionInfoProvider();

  private final ImmutableMap<String, String> executionInfo;
  private final String execGroup;

  public ExecutionInfo(Map<String, String> requirements) {
    this(requirements, RuleClass.DEFAULT_TEST_RUNNER_EXEC_GROUP_NAME);
  }

  public ExecutionInfo(Map<String, String> requirements, String execGroup) {
    this.executionInfo = ImmutableMap.copyOf(requirements);
    this.execGroup = Preconditions.checkNotNull(execGroup);
  }

  @Override
  public BuiltinProvider<ExecutionInfo> getProvider() {
    return PROVIDER;
  }

  /**
   * Returns a map to indicate special execution requirements, such as hardware
   * platforms, etc. Rule tags, such as "requires-XXX", may also be added
   * as keys to the map.
   */
  @Override
  public ImmutableMap<String, String> getExecutionInfo() {
    return executionInfo;
  }

  /** Returns the name of the exec group that is used to execute the test. */
  @Override
  public String getExecGroup() {
    return execGroup;
  }

  public static class ExecutionInfoProvider extends BuiltinProvider<ExecutionInfo>
      implements ExecutionInfoApi.ExecutionInfoApiProvider {

    private ExecutionInfoProvider() {
      super("ExecutionInfo", ExecutionInfo.class);
    }

    @Override
    public ExecutionInfoApi constructor(
        Dict<?, ?> requirements /* <String, String> */, String execGroup) throws EvalException {
      return new ExecutionInfo(
          Dict.cast(requirements, String.class, String.class, "requirements"), execGroup);
    }
  }
}
