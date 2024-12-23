// Copyright 2023 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.gson.JsonSyntaxException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Reads the contents of the lock file into its value. */
public class BazelLockFileFunction implements SkyFunction {

  public static final Precomputed<LockfileMode> LOCKFILE_MODE = new Precomputed<>("lockfile_mode");

  private static final Pattern LOCKFILE_VERSION_PATTERN =
      Pattern.compile("\"lockFileVersion\":\\s*(\\d+)");

  private static final Pattern POSSIBLE_MERGE_CONFLICT_PATTERN =
      Pattern.compile("<<<<<<<|=======|" + Pattern.quote("|||||||") + "|>>>>>>>");

  private static final BazelLockFileValue EMPTY_LOCKFILE = BazelLockFileValue.builder().build();

  private final Path rootDirectory;

  public BazelLockFileFunction(Path rootDirectory) {
    this.rootDirectory = rootDirectory;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BazelLockfileFunctionException, InterruptedException {
    RootedPath lockfilePath =
        RootedPath.toRootedPath(Root.fromPath(rootDirectory), LabelConstants.MODULE_LOCKFILE_NAME);

    // Add dependency on the lockfile to recognize changes to it
    if (env.getValue(FileValue.key(lockfilePath)) == null) {
      return null;
    }

    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.BZLMOD, "parse lockfile")) {
      return getLockfileValue(lockfilePath, LOCKFILE_MODE.get(env));
    } catch (IOException
        | JsonSyntaxException
        | NullPointerException
        | IllegalArgumentException e) {
      String actionSuffix;
      if (POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()) {
        actionSuffix =
            " This looks like a merge conflict. See"
                + " https://bazel.build/external/lockfile#merge-conflicts for advice.";
      } else {
        actionSuffix = " Try deleting it and rerun the build.";
      }
      throw new BazelLockfileFunctionException(
          ExternalDepsException.withMessage(
              Code.BAD_LOCKFILE,
              "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
              e.getMessage(),
              actionSuffix),
          Transience.PERSISTENT);
    }
  }

  public static BazelLockFileValue getLockfileValue(
      RootedPath lockfilePath, LockfileMode lockfileMode)
      throws IOException, BazelLockfileFunctionException {
    try {
      String json = FileSystemUtils.readContentToString(lockfilePath.asPath());
      Matcher matcher = LOCKFILE_VERSION_PATTERN.matcher(json);
      int version = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
      if (version == BazelLockFileValue.LOCK_FILE_VERSION) {
        return GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson(json, BazelLockFileValue.class);
      } else {
        // This is an old version, its information can't be used.
        if (lockfileMode == LockfileMode.ERROR) {
          throw new BazelLockfileFunctionException(
              ExternalDepsException.withMessage(
                  Code.BAD_LOCKFILE,
                  "The version of MODULE.bazel.lock is not supported by this version of Bazel."
                      + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                      + " lockfile."),
              Transience.PERSISTENT);
        }
        return EMPTY_LOCKFILE;
      }
    } catch (FileNotFoundException e) {
      return EMPTY_LOCKFILE;
    }
  }


  static final class BazelLockfileFunctionException extends SkyFunctionException {

    BazelLockfileFunctionException(ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
