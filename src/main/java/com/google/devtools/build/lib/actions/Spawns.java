// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.actions.ExecutionRequirements.ParseableRequirement.ValidationException;
import com.google.devtools.build.lib.actions.ExecutionRequirements.ParseableRequirement;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/** Helper methods relating to implementations of {@link Spawn}. */
public final class Spawns {
  private Spawns() {}

  /** Returns {@code true} if the result of {@code spawn} may be cached. */
  public static boolean mayBeCached(Spawn spawn) {
    return mayBeCached(spawn.getExecutionInfo());
  }

  /** Returns {@code true} if the result of {@code spawn} may be cached. */
  public static boolean mayBeCached(Map<String, String> executionInfo) {
    return !executionInfo.containsKey(ExecutionRequirements.NO_CACHE)
        && !executionInfo.containsKey(ExecutionRequirements.LOCAL);
  }

  /** Returns {@code true} if the result of {@code spawn} may be cached remotely. */
  public static boolean mayBeCachedRemotely(Spawn spawn) {
    return mayBeCachedRemotely(spawn.getExecutionInfo());
  }

  /** Returns {@code true} if the result of {@code spawn} may be cached remotely. */
  public static boolean mayBeCachedRemotely(Map<String, String> executionInfo) {
    return mayBeCached(executionInfo)
        && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE)
        && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE_CACHE);
  }

  /** Returns {@code true} if {@code spawn} may be executed remotely. */
  public static boolean mayBeExecutedRemotely(Spawn spawn) {
    return !spawn.getExecutionInfo().containsKey(ExecutionRequirements.LOCAL)
        && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.NO_REMOTE)
        && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.NO_REMOTE_EXEC);
  }

  /** Returns {@code true} if {@code spawn} may be executed locally. */
  public static boolean mayBeExecutedLocally(Spawn spawn) {
    return !spawn.getExecutionInfo().containsKey(ExecutionRequirements.NO_LOCAL);
  }

  /** Returns whether a Spawn can be executed in a sandbox environment. */
  public static boolean mayBeSandboxed(Spawn spawn) {
    return !spawn.getExecutionInfo().containsKey(ExecutionRequirements.LEGACY_NOSANDBOX)
        && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.NO_SANDBOX)
        && !spawn.getExecutionInfo().containsKey(ExecutionRequirements.LOCAL);
  }

  /** Returns whether a Spawn needs network access in order to run successfully. */
  public static boolean requiresNetwork(Spawn spawn, boolean defaultSandboxDisallowNetwork) {
    if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.BLOCK_NETWORK)) {
      return false;
    }
    if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_NETWORK)) {
      return true;
    }

    return defaultSandboxDisallowNetwork;
  }

  /**
   * Returns whether a Spawn claims to support being executed with the persistent worker strategy
   * according to its execution info tags.
   */
  public static boolean supportsWorkers(Spawn spawn) {
    return "1".equals(spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_WORKERS));
  }

  /**
   * Returns whether a Spawn claims to support being executed with the persistent multiplex worker
   * strategy according to its execution info tags.
   */
  public static boolean supportsMultiplexWorkers(Spawn spawn) {
    return "1"
        .equals(spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS));
  }

  public static boolean supportsWorkerCancellation(Spawn spawn) {
    return "1"
        .equals(spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION));
  }

  /**
   * Returns whether the {@link Spawn} supports sandboxing for multiplex workers through the {@code
   * WorkRequest.sandbox_dir} field.
   */
  public static boolean supportsMultiplexSandboxing(Spawn spawn) {
    return "1"
        .equals(spawn.getExecutionInfo().get(ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING));
  }

  /**
   * Returns which worker protocol format a Spawn claims a persistent worker uses. Defaults to proto
   * if the protocol format is not specified.
   */
  public static ExecutionRequirements.WorkerProtocolFormat getWorkerProtocolFormat(Spawn spawn)
      throws IOException {
    String protocolFormat =
        spawn.getExecutionInfo().get(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL);

    if (protocolFormat != null) {
      return switch (protocolFormat) {
        case "json" -> ExecutionRequirements.WorkerProtocolFormat.JSON;
        case "proto" -> ExecutionRequirements.WorkerProtocolFormat.PROTO;
        default ->
            throw new IOException(
                "requires-worker-protocol must be set to a valid worker protocol format: json or"
                    + " proto");
      };
    } else {
      return ExecutionRequirements.WorkerProtocolFormat.PROTO;
    }
  }

  /** Returns the mnemonic that should be used in the worker's key. */
  public static String getWorkerKeyMnemonic(Spawn spawn) {
    String customValue = spawn.getExecutionInfo().get(ExecutionRequirements.WORKER_KEY_MNEMONIC);
    return customValue != null ? customValue : spawn.getMnemonic();
  }

  /**
   * Parse the timeout key in the spawn execution info, if it exists. Otherwise, return {@link
   * Duration#ZERO}.
   */
  public static Duration getTimeout(Spawn spawn) throws ExecException {
    return getTimeout(spawn, Duration.ZERO);
  }

  /**
   * Parse the timeout key in the spawn execution info, if it exists. Otherwise, return
   * defaultTimeout, or {@code Duration.ZERO} if that is null.
   */
  public static Duration getTimeout(Spawn spawn, Duration defaultTimeout) throws ExecException {
    Duration timeout = null;

    for (Map.Entry<String, String> entry: spawn.getExecutionInfo().entrySet()) {
        String value = getTimeoutValueFromExecutionInfoKeyOrValue(spawn,entry);
        if (value != null) {
            if (timeout != null) {
                throw new UserExecException(
                    FailureDetail.newBuilder()
                        .setMessage("multiple timeout values specified")
                        .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.DUPLICATE_TIMEOUT_TAGS))
                        .build());
            }
            timeout = parseTimeoutValue(value);
        }
    }

    if (timeout == null) {
        timeout = defaultTimeout == null ? Duration.ZERO : defaultTimeout;
    }
    return timeout;
}

/**
 * Retrieves the timeout value from the execution info key or value.
 * Timeout may either be specified as a tag on format timeout:<int>
 * or as a key-value pair when specified as an execution_requirement.
 */
private static String getTimeoutValueFromExecutionInfoKeyOrValue(Spawn spawn, Map.Entry<String, String> entry) throws ExecException {
    ParseableRequirement requirement = ExecutionRequirements.TIMEOUT;
    String value = null;

    if (entry.getKey().equals("timeout")) {
        value = entry.getValue();
    } else {
        try {
            value = requirement.parseIfMatches(entry.getKey());
        } catch (ValidationException e) {
            throw new UserExecException(
                e,
                FailureDetail.newBuilder()
                    .setMessage(
                        String.format(
                            "%s has a '%s' tag, but its value '%s' didn't pass validation: %s",
                            spawn.getTargetLabel(), requirement.userFriendlyName(), e.getTagValue(), e.getMessage()))
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.INVALID_TIMEOUT_TAG))
                    .build());
        }
    }
    return value;
}

/**
 * Parses a timeout value from a string and returns it as a {@link Duration}.
 */
private static Duration parseTimeoutValue(String value) throws ExecException {
    try {
        return Duration.ofSeconds(Integer.parseInt(value));
    } catch (NumberFormatException e) {
        throw new UserExecException(
            e,
            FailureDetail.newBuilder()
                .setMessage("could not parse timeout")
                .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.INVALID_TIMEOUT))
                .build());
    }
}

  /**
   * Returns whether a local {@link Spawn} runner implementation should prefetch the inputs before
   * execution, based on the spawns execution info.
   */
  public static boolean shouldPrefetchInputsForLocalExecution(Spawn spawn) {
    String disablePrefetchRequest =
        spawn.getExecutionInfo().get(ExecutionRequirements.DISABLE_LOCAL_PREFETCH);
    return (disablePrefetchRequest == null) || disablePrefetchRequest.equals("0");
  }

  /**
   * Returns a (somewhat) human-readable string for the given {@code Spawn}. Meant to be used in
   * {@code toString()} of Spawns.
   */
  public static String prettyPrint(Spawn spawn) {
    if (spawn.getResourceOwner().getPrimaryOutput() != null) {
      return spawn.getClass().getSimpleName()
          + " for "
          + spawn.getResourceOwner().getPrimaryOutput().prettyPrint();
    } else {
      return spawn.getClass().getSimpleName()
          + " for "
          + spawn.getMnemonic()
          + " action without primary output";
    }
  }
}
