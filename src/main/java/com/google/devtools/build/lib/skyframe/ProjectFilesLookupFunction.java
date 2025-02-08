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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.ArrayList;
import javax.annotation.Nullable;

/** {@link SkyFunction} for {@link ProjectFilesLookupValue}. */
public class ProjectFilesLookupFunction implements SkyFunction {
  /** Name of project metadata files. See {@link com.google.devtools.build.lib.analysis.Project}. */
  @VisibleForTesting public static final String PROJECT_FILE_NAME = "PROJECT.scl";

  private static class State implements SkyKeyComputeState {
    /** Which directory up the package path are we currently examining? */
    private PackageIdentifier currentDir = null;

    /** Which project files have we discovered so far? */
    private final ArrayList<Label> projectFiles = new ArrayList<>();
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, ProjectFilesLookupException {
    State state = env.getState(State::new);
    // Tracks the current directory we're checking for BUILD and PROJECT.scl files. According to the
    // ProjectFilesLookupValue API contract, the original value from the skykey should be a package
    // (i.e. has a BUILD file). But this code doesn't require that: if the directory doesn't have a
    // BUILD file the code still gracefully finds the nearest enclosing package. This is especially
    // important when walking up the directory path, since the parent directory of a package isn't
    // necessarily another package.
    if (state.currentDir == null) {
      state.currentDir = (PackageIdentifier) skyKey.argument();
    }
    while (true) {
      ContainingPackageLookupValue innermostPkgLookupValue =
          (ContainingPackageLookupValue)
              env.getValue(ContainingPackageLookupValue.key(state.currentDir));
      if (innermostPkgLookupValue == null) {
        return null;
      }
      if (!innermostPkgLookupValue.hasContainingPackage()) {
        // We've reached the root directory: nothing left. This list may be empty but that's okay.
        // That just means the input package has no associated project files.
        return ProjectFilesLookupValue.of(state.projectFiles);
      }

      // Now that we've found a BUILD file, determine the project file path to look for.
      PackageIdentifier innermostPkgId = innermostPkgLookupValue.getContainingPackageName();
      Label projectFileLabel;
      try {
        projectFileLabel = Label.create(innermostPkgId, PROJECT_FILE_NAME);
      } catch (LabelSyntaxException e) {
        throw new IllegalStateException("Unexpected failure parsing " + PROJECT_FILE_NAME, e);
      }

      // Lookup the project file.
      PathFragment projectFilePath =
          innermostPkgId.getPackageFragment().getRelative(PROJECT_FILE_NAME);
      SkyKey fileSkyKey =
          FileValue.key(
              RootedPath.toRootedPath(
                  innermostPkgLookupValue.getContainingPackageRoot(), projectFilePath));
      FileValue fileValue;
      try {
        fileValue = (FileValue) env.getValueOrThrow(fileSkyKey, IOException.class);
      } catch (IOException e) {
        throw new ProjectFilesLookupException(e);
      }
      if (fileValue == null) {
        return null;
      }

      if (fileValue.isFile()) {
        state.projectFiles.add(projectFileLabel);
      } else if (fileValue.exists()) {
        throw new ProjectFilesLookupException(
            new UnexpectedProjectFileTypeException(
                projectFilePath.getPathString() + " isn't a file"));
      }

      PathFragment parentDir = innermostPkgId.getPackageFragment().getParentDirectory();
      if (parentDir == null) {
        // Hit the root directory. Returns the results we've collected.
        return ProjectFilesLookupValue.of(state.projectFiles);
      }
      state.currentDir = PackageIdentifier.create(state.currentDir.getRepository(), parentDir);
    }
  }

  /** A project file exists but isn't a file. */
  private static final class UnexpectedProjectFileTypeException extends Exception {
    UnexpectedProjectFileTypeException(String msg) {
      super(msg);
    }
  }

  /** Exception thrown by {@link ProjectFilesLookupFunction}. */
  public static final class ProjectFilesLookupException extends SkyFunctionException {
    ProjectFilesLookupException(IOException e) {
      super(e, Transience.PERSISTENT);
    }

    ProjectFilesLookupException(UnexpectedProjectFileTypeException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
