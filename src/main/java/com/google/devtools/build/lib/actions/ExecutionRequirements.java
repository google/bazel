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

package com.google.devtools.build.lib.actions;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Strings used to express requirements on action execution environments.
 *
 * <ol>
 *   If you are adding a new execution requirement, pay attention to the following:
 *   <li>If its name starts with one of the supported prefixes, then it can be also used as a tag on
 *       a target and will be propagated to the execution requirements, see for prefixes {@link
 *       com.google.devtools.build.lib.packages.TargetUtils#getExecutionInfo}
 *   <li>If this is a potentially conflicting execution requirements, e.g. you are adding a pair
 *       'requires-x' and 'block-x', you MUST take care of a potential conflict in the Executor that
 *       is using new execution requirements. As an example, see {@link
 *       Spawns#requiresNetwork(com.google.devtools.build.lib.actions.Spawn, boolean)}.
 * </ol>
 */
public class ExecutionRequirements {

  /** An execution requirement that can be split into a key and a value part using a regex. */
  public record ParseableRequirement(
      String userFriendlyName, Pattern detectionPattern, Function<String, String> validator) {
    public ParseableRequirement {
      requireNonNull(userFriendlyName, "userFriendlyName");
      requireNonNull(detectionPattern, "detectionPattern");
      requireNonNull(validator, "validator");
    }

    /**
     * Thrown when a {@link ParseableRequirement} feels responsible for a tag, but the {@link
     * #validator()} method returns an error.
     */
    public static class ValidationException extends Exception {
      private final String tagValue;

      /**
       * Creates a new {@link ValidationException}.
       *
       * @param tagValue the erroneous value that was parsed from the tag.
       * @param errorMsg an error message that tells the user what's wrong with the value.
       */
      public ValidationException(String tagValue, String errorMsg) {
        super(errorMsg);
        this.tagValue = tagValue;
      }

      /**
       * Returns the erroneous value of the parsed tag.
       *
       * <p>Useful to put in error messages shown to the user.
       */
      public String getTagValue() {
        return tagValue;
      }
    }

    /**
     * Create a new parseable execution requirement definition.
     *
     * <p>If a tag doesn't match the detectionPattern, it will be ignored. If a tag matches the
     * detectionPattern, but not the validationPattern, it is assumed that the value is somehow
     * wrong (e.g. the user put a float or random string where we expected an integer).
     *
     * @param userFriendlyName a human readable name of the tag and its format, e.g. "cpu:<int>"
     * @param detectionPattern a regex that will be used to detect whether a tag matches this
     *     execution requirement. It should have one capture group that grabs the value of the tag.
     *     This should be general enough to permit even wrong value types. Example: "cpu:(.+)".
     * @param validator a Function that will be used to validate the value of the tag. It should
     *     return null if the value is fine to use or a human-friendly error message describing why
     *     the value is not valid.
     */
    static ParseableRequirement create(
        String userFriendlyName, Pattern detectionPattern, Function<String, String> validator) {
      return new ParseableRequirement(userFriendlyName, detectionPattern, validator);
    }

    /**
     * Returns the parsed value from a tag, if this {@link ParseableRequirement} detects that it is
     * responsible for it, otherwise returns {@code null}.
     *
     * @throws ValidationException if the value parsed out of the tag doesn't pass the validator.
     */
    @Nullable
    public String parseIfMatches(String tag) throws ValidationException {
      Matcher matcher = detectionPattern().matcher(tag);
      if (!matcher.matches()) {
        return null;
      }
      String tagValue = matcher.group(1);
      String errorMsg = validator().apply(tagValue);
      if (errorMsg != null) {
        throw new ValidationException(tagValue, errorMsg);
      }
      return tagValue;
    }
  }

  /** If specified, the timeout of this action in seconds. Must be decimal integer. */
  public static final String TIMEOUT = "timeout";

  /** If an action would not successfully run other than on Darwin. */
  public static final String REQUIRES_DARWIN = "requires-darwin";

  /** Whether we should disable prefetching of inputs before running a local action. */
  public static final String DISABLE_LOCAL_PREFETCH = "disable-local-prefetch";

  /** How many hardware threads an action requires for execution. */
  public static final ParseableRequirement CPU =
      ParseableRequirement.create(
          "cpu:<int>",
          Pattern.compile("cpu:(.+)"),
          s -> {
            Preconditions.checkNotNull(s);

            int value;
            try {
              value = Integer.parseInt(s);
            } catch (NumberFormatException e) {
              return "can't be parsed as an integer";
            }

            // De-and-reserialize & compare to only allow canonical integer formats.
            if (!Integer.toString(value).equals(s)) {
              return "must be in canonical format (e.g. '4' instead of '+04')";
            }

            if (value < 1) {
              return "can't be zero or negative";
            }

            return null;
          });

  /** How many extra resources an action requires for execution. */
  public static final ParseableRequirement RESOURCES =
      ParseableRequirement.create(
          "resources:<str>:<float>",
          Pattern.compile("resources:(.+:.+)"),
          s -> {
            Preconditions.checkNotNull(s);

            int splitIndex = s.indexOf(":");
            String resourceCount = s.substring(splitIndex + 1);
            float value;
            try {
              value = Float.parseFloat(resourceCount);
            } catch (NumberFormatException e) {
              return "can't be parsed as a float";
            }

            if (value < 0) {
              return "can't be negative";
            }

            return null;
          });

  /** If an action supports running in persistent worker mode. */
  public static final String SUPPORTS_WORKERS = "supports-workers";

  public static final String SUPPORTS_MULTIPLEX_WORKERS = "supports-multiplex-workers";

  /** Specify the type of worker protocol the worker uses. */
  public static final String REQUIRES_WORKER_PROTOCOL = "requires-worker-protocol";

  public static final String SUPPORTS_WORKER_CANCELLATION = "supports-worker-cancellation";

  public static final String SUPPORTS_MULTIPLEX_SANDBOXING = "supports-multiplex-sandboxing";

  /** Denotes what the type of worker protocol the worker uses. */
  public enum WorkerProtocolFormat {
    JSON,
    PROTO,
  }

  /** Override for the action's mnemonic to allow for better worker process reuse. */
  public static final String WORKER_KEY_MNEMONIC = "worker-key-mnemonic";

  public static final ImmutableMap<String, String> WORKER_MODE_ENABLED =
      ImmutableMap.of(SUPPORTS_WORKERS, "1");

  public static final ImmutableMap<String, String> WORKER_MULTIPLEX_MODE_ENABLED =
      ImmutableMap.of(SUPPORTS_MULTIPLEX_WORKERS, "1");

  /**
   * Requires local execution without sandboxing for a spawn.
   *
   * <p>This tag is deprecated; use no-cache, no-remote, or no-sandbox instead.
   */
  public static final String LOCAL = "local";

  /**
   * Disables local and remote caching for a spawn, but note that the local action cache may still
   * apply.
   *
   * <p>This tag can also be set on an action, in which case it completely disables all caching for
   * that action, but note that action-generated spawns may still be cached, unless they also carry
   * this tag.
   */
  public static final String NO_CACHE = "no-cache";

  /** Disables remote caching of a spawn. Note: does not disable remote execution */
  public static final String NO_REMOTE_CACHE = "no-remote-cache";

  /** Disables upload part of remote caching of a spawn. Note: does not disable remote execution */
  public static final String NO_REMOTE_CACHE_UPLOAD = "no-remote-cache-upload";

  /** Disables remote execution of a spawn. Note: does not disable remote caching */
  public static final String NO_REMOTE_EXEC = "no-remote-exec";

  /** Tag for Google internal use. Requires local execution with correct permissions. */
  public static final String NO_TESTLOASD = "no-testloasd";

  /**
   * Disables both remote execution and remote caching of a spawn. This is the equivalent of using
   * no-remote-cache and no-remote-exec together.
   */
  public static final String NO_REMOTE = "no-remote";

  /** Disables local execution of a spawn. */
  public static final String NO_LOCAL = "no-local";

  /** Disables local sandboxing of a spawn. */
  public static final String LEGACY_NOSANDBOX = "nosandbox";

  /** Disables local sandboxing of a spawn. */
  public static final String NO_SANDBOX = "no-sandbox";

  /**
   * Set for Xcode-related rules. Used for quality control to make sure that all Xcode-dependent
   * rules propagate the necessary configurations. Begins with "supports" so as not to be filtered
   * out for Bazel by {@code TargetUtils}.
   */
  public static final String REQUIREMENTS_SET = "supports-xcode-requirements-set";

  /**
   * Enables networking for a spawn if possible (only if sandboxing is enabled and if the sandbox
   * supports it).
   */
  public static final String REQUIRES_NETWORK = "requires-network";

  /**
   * Disables networking for a spawn if possible (only if sandboxing is enabled and if the sandbox
   * supports it).
   */
  public static final String BLOCK_NETWORK = "block-network";

  /**
   * On linux, if sandboxing is enabled, ensures that a spawn is run with uid 0, i.e., root. Has no
   * effect otherwise.
   */
  public static final String REQUIRES_FAKEROOT = "requires-fakeroot";

  /** Suppress CLI reporting for this spawn - it's part of another action. */
  public static final String DO_NOT_REPORT = "internal-do-not-report";

  /** Use this to request eager fetching of a single remote output into local memory. */
  public static final String REMOTE_EXECUTION_INLINE_OUTPUTS = "internal-inline-outputs";

  /** Tag for Google internal use. Indicates a memory estimate in bytes. */
  public static final String MEMORY_ESTIMATE = "internal-memory-estimate";

  /**
   * Request graceful termination of subprocesses on interrupt (that is, an initial {@code SIGTERM}
   * followed by a {@code SIGKILL} after a grace period).
   */
  public static final String GRACEFUL_TERMINATION = "supports-graceful-termination";

  /** Requires the execution service to support a given Xcode version e.g. "xcode_version:1.0". */
  public static final String REQUIRES_XCODE = "requires-xcode";

  /**
   * Requires the execution service to support a "label" in addition to the Xcode version. The user
   * specifies the label as a hyphenated extension to their requested version. For example, if the
   * user requests "--xcode_version=1.0-unstable", the action request will include
   * "requires-xcode-label:unstable" and "requires-xcode:1.0".
   */
  public static final String REQUIRES_XCODE_LABEL = "requires-xcode-label";

  /** Requires the execution service do NOT share caches across different workspace. */
  public static final String DIFFERENTIATE_WORKSPACE_CACHE =
      "internal-differentiate-workspace-cache";

  /**
   * Indicates that the action is compatible with path mapping, e.g., removing the configuration
   * segment from the paths of all inputs and outputs.
   */
  public static final String SUPPORTS_PATH_MAPPING = "supports-path-mapping";

  /** Disables cgroups for a sandbox spawn */
  public static final String NO_SUPPORTS_CGROUPS = "no-supports-cgroups";
}
