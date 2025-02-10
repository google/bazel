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
package com.google.devtools.build.lib.versioning;

import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;

/** Strategy for retrieving the version number for paths. */
public interface LongVersionGetter {

  /**
   * Indicates the item was affected in currently evaluated versions. Outside of tests, it can only
   * be returned for changes in current client snapshot.
   */
  // TODO(b/151473808): Do not request xattrs for paths without it outside of client snapshots in
  //  tests. Rename the constant accordingly once we do so.
  long CURRENT_VERSION = Long.MAX_VALUE;

  /**
   * Version for a file that has never changed.
   *
   * <p>We use -1 because valid versions are positive longs.
   */
  long MINIMAL = -1;

  /**
   * Returns version number when the provided file/symlink was last modified (or added).
   *
   * <p>Special value of {@link #CURRENT_VERSION} is used to indicate a file/symlink modified in
   * current client snapshot.
   */
  long getFilePathOrSymlinkVersion(Path path) throws IOException;

  /**
   * Returns version number when the listing of given directory has last changed (or when the
   * directory was created if there were no changes since then).
   *
   * <p>Special value of {@link #CURRENT_VERSION} is used to indicate the listing has changed in
   * current client snapshot.
   */
  long getDirectoryListingVersion(Path path) throws IOException;

  /**
   * Returns a version number for a currently nonexistent item.
   *
   * <p>This can be the version at which it was most recently deleted or one of the special cases
   * below.
   *
   * <ul>
   *   <li><b>Deleted in Current Snapshot</b>: returns {@link #CURRENT_VERSION}
   *   <li><b>External, unversioned, paths</b>: returns {@link #CURRENT_VERSION}
   *   <li><b>Never existed in the first place</b>: returns {@link #MINIMAL}
   *   <li><b>Parent directory doesn't exist</b>: returns {@link #MINIMAL}
   * </ul>
   */
  long getNonexistentPathVersion(Path path) throws IOException;
}
