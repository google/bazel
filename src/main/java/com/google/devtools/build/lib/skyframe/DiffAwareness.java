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
package com.google.devtools.build.lib.skyframe;

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.common.options.OptionsProvider;
import java.io.Closeable;
import javax.annotation.Nullable;

/**
 * Interface for computing modifications of files under a package path entry.
 *
 * <p> Skyframe has a {@link DiffAwareness} instance per package-path entry, and each instance is
 * responsible for all files under its path entry. At the beginning of each incremental build,
 * skyframe queries for changes using {@link #getDiff}. Ideally, {@link #getDiff} should be
 * constant-time; if it were linear in the number of files of interest, we might as well just
 * detect modifications manually.
 */
public interface DiffAwareness extends Closeable {

  /** Factory for creating {@link DiffAwareness} instances. */
  interface Factory {
    /**
     * Returns a {@link DiffAwareness} instance suitable for managing changes to files under the
     * given package path entry, or {@code null} if this factory cannot create such an instance. The
     * instance will not report any changes to files within the given set of ignored paths.
     *
     * <p>Skyframe has a collection of factories, and will create a {@link DiffAwareness} instance
     * per package path entry using one of the factories that returns a non-null value.
     */
    @Nullable
    DiffAwareness maybeCreate(
        Root pathEntry, IgnoredSubdirectories ignoredPaths, OptionsProvider optionsProvider);
  }

  /** Opaque view of the filesystem under a package path entry at a specific point in time. */
  interface View {
    /** Returns workspace info unanimously associated with the package path or null. */
    @Nullable
    default WorkspaceInfoFromDiff getWorkspaceInfo() {
      return null;
    }
  }

  /**
   * Returns the live view of the filesystem under the package path entry.
   *
   * @throws BrokenDiffAwarenessException if something is wrong and the caller should discard this
   *     {@link DiffAwareness} instance. The {@link DiffAwareness} is expected to close itself in
   *     this case.
   */
  View getCurrentView(OptionsProvider options) throws BrokenDiffAwarenessException;

  /**
   * Returns the set of files of interest that have been modified between the given two views.
   *
   * <p>The given views must have come from previous calls to {@link #getCurrentView} on the {@link
   * DiffAwareness} instance (i.e. using a {@link View} from another instance is not supported).
   *
   * @throws IncompatibleViewException if the given views are not compatible with this {@link
   *     DiffAwareness} instance. This probably indicates a bug.
   * @throws BrokenDiffAwarenessException if something is wrong and the caller should discard this
   *     {@link DiffAwareness} instance. The {@link DiffAwareness} is expected to close itself in
   *     this case.
   */
  ModifiedFileSet getDiff(@Nullable View oldView, View newView)
      throws IncompatibleViewException, InterruptedException, BrokenDiffAwarenessException;

  /**
   * Returns the set of files of interest that have been modified between the current view and the
   * evaluating version.
   *
   * <p>This is loosely defined as the set of changed but unsubmitted files relative to the current
   * "commit". These can include both tracked and untracked files by a version control system.
   */
  ModifiedFileSet getDiffFromEvaluatingVersion(OptionsProvider options, FileSystem fs)
      throws BrokenDiffAwarenessException;

  /** @return the name of this implementation */
  String name();

  /**
   * Must be called whenever the {@link DiffAwareness} object is to be discarded. Using a
   * {@link DiffAwareness} instance after calling {@link #close} on it is unspecified behavior.
   */
  @Override
  void close();
}
