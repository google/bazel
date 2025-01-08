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
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolver that looks up the right strategy for a spawn during {@link #exec} (via a {@link
 * SpawnStrategyRegistry}) and uses it to execute the spawn.
 */
public final class SpawnStrategyResolver implements ActionContext {
  /**
   * Executes the given spawn with the {@linkplain SpawnStrategyRegistry highest priority strategy}
   * that can be found for it.
   *
   * @param actionExecutionContext context in which to execute the spawn
   * @return result(s) from the spawn's execution
   */
  public ImmutableList<SpawnResult> exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    return resolveOne(spawn, actionExecutionContext).exec(spawn, actionExecutionContext);
  }

  private SpawnStrategy resolveOne(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws UserExecException {
    List<? extends SpawnStrategy> strategies = resolve(spawn, actionExecutionContext);

    // Because the strategies are ordered by preference, we can execute the spawn with the best
    // possible one by simply filtering out the ones that can't execute it and then picking the
    // first one from the remaining strategies in the list.
    return strategies.get(0);
  }

  /**
   * Returns the list of {@link SpawnStrategy}s that should be used to execute the given spawn.
   *
   * @param spawn spawn for which the correct {@link SpawnStrategy} should be determined
   */
  @VisibleForTesting
  public List<? extends SpawnStrategy> resolve(
      Spawn spawn, ActionExecutionContext actionExecutionContext) throws UserExecException {
    List<? extends SpawnStrategy> strategies =
        actionExecutionContext
            .getContext(SpawnStrategyRegistry.class)
            .getStrategies(spawn, actionExecutionContext.getEventHandler());

    List<? extends SpawnStrategy> execableStrategies =
        strategies.stream()
            .filter(spawnActionContext -> spawnActionContext.canExec(spawn, actionExecutionContext))
            .collect(Collectors.toList());

    if (execableStrategies.isEmpty()) {
      // Legacy implicit fallbacks should be a last-ditch option after all other strategies are
      // found non-executable.
      List<? extends SpawnStrategy> fallbackStrategies =
          strategies.stream()
              .filter(
                  spawnActionContext ->
                      spawnActionContext.canExecWithLegacyFallback(spawn, actionExecutionContext))
              .collect(Collectors.toList());

      if (fallbackStrategies.isEmpty()) {
        String message =
            String.format(
                """
                %s spawn cannot be executed with any of the available strategies: %s. Your \
                --spawn_strategy, --genrule_strategy, strategy and/or \
                --allowed_strategies_by_exec_platform flags are probably too strict. \
                Visit https://github.com/bazelbuild/bazel/issues/7480 for advice.
                """,
                spawn.getMnemonic(), strategies);
        throw new UserExecException(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NO_USABLE_STRATEGY_FOUND))
                .build());
      }
      return fallbackStrategies;
    }

    return execableStrategies;
  }
}
