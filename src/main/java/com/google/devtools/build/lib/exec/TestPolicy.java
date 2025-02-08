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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.util.UserUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A policy for running tests. It currently only encompasses the environment computation for the
 * test.
 */
public class TestPolicy {
  /**
   * The user name of the user running Bazel; this may differ from ${USER} for tests that are run
   * remotely.
   */
  public static final String SYSTEM_USER_NAME = "${SYSTEM_USER_NAME}";

  /** An absolute path to a writable directory that is reserved for the current test. */
  public static final String TEST_TMP_DIR = "${TEST_TMP_DIR}";

  /** The path of the runfiles directory. */
  public static final String RUNFILES_DIR = "${RUNFILES_DIR}";

  public static final String INHERITED = "${inherited}";

  public static final TestPolicy EMPTY_POLICY = new TestPolicy(ImmutableMap.of());

  private final ImmutableMap<String, String> envVariables;

  /**
   * Creates a new instance. The map's keys are the names of the environment variables, while the
   * values can be either fixed values, or one of the constants in this class, specifically {@link
   * #SYSTEM_USER_NAME}, {@link #TEST_TMP_DIR}, {@link #RUNFILES_DIR}, or {@link #INHERITED}.
   */
  public TestPolicy(ImmutableMap<String, String> envVariables) {
    this.envVariables = envVariables;
  }

  /**
   * Returns a mutable map of the environment variables for a specific test. This is intended to be
   * the final, complete environment - callers should avoid relying on the mutability of the return
   * value, and instead change the policy itself.
   */
  public Map<String, String> computeTestEnvironment(
      TestRunnerAction testAction,
      Map<String, String> clientEnv,
      PathFragment relativeRunfilesDir,
      PathFragment tmpDir) {
    Map<String, String> env = new LinkedHashMap<>();

    // Add all env variables, allow some string replacements and inheritance.
    String userProp = UserUtils.getUserName();
    String tmpDirPath = tmpDir.getPathString();
    String runfilesDirPath = relativeRunfilesDir.getPathString();
    for (Map.Entry<String, String> entry : envVariables.entrySet()) {
      String val = entry.getValue();
      if (val.contains("${")) {
        if (val.equals(INHERITED)) {
          if (!clientEnv.containsKey(entry.getKey())) {
            continue;
          }
          val = clientEnv.get(entry.getKey());
        } else {
          val = val.replace(SYSTEM_USER_NAME, userProp);
          val = val.replace(TEST_TMP_DIR, tmpDirPath);
          val = val.replace(RUNFILES_DIR, runfilesDirPath);
        }
      }
      env.put(entry.getKey(), val);
    }

    // Overwrite with the environment common to all actions, see --action_env.
    testAction.getConfiguration().getActionEnvironment().resolve(env, clientEnv);

    // Overwrite with the environment common to all tests, see --test_env.
    testAction.getConfiguration().getTestActionEnvironment().resolve(env, clientEnv);

    // Rule-specified test env.
    testAction.getExtraTestEnv().resolve(env, clientEnv);

    // Setup any test-specific env variables; note that this does not overwrite existing values for
    // TEST_RANDOM_SEED or TEST_SIZE if they're already set.
    testAction.setupEnvVariables(env);

    return env;
  }
}
