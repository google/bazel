// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.SymlinkEntry;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;

/** Lazy wrapper for a single runfiles tree. */
// TODO(bazel-team): Ideally we could refer to Runfiles objects directly here, but current package
// structure makes this difficult. Consider moving things around to make this possible.
public interface RunfilesTree {
  /** Returns the exec path of the root directory of the runfiles tree. */
  PathFragment getExecPath();

  /** Returns the mapping from the location in the runfiles tree to the artifact that's there. */
  Map<PathFragment, Artifact> getMapping();

  /**
   * Returns artifacts the runfiles tree contain symlinks to.
   *
   * <p>This includes artifacts that the symlinks and root symlinks point to, not just artifacts at
   * their canonical location.
   */
  NestedSet<Artifact> getArtifacts();

  /** Returns the {@link RunfileSymlinksMode} for this runfiles tree. */
  RunfileSymlinksMode getSymlinksMode();

  /** Returns whether the runfile symlinks should be materialized during the build. */
  boolean isBuildRunfileLinks();

  /** Returns the name of the workspace that the build is occurring in. */
  String getWorkspaceName();

  /**
   * Returns artifacts the runfiles tree contain symlinks to at their canonical locations.
   *
   * <p>This does <b>not</b> include artifacts that only the symlinks and root symlinks point to.
   */
  NestedSet<Artifact> getArtifactsAtCanonicalLocationsForLogging();

  /**
   * Returns the set of names of implicit empty files to materialize.
   *
   * <p>If this runfiles tree does not implicitly add empty files, implementations should have a
   * dedicated fast path that returns an empty set without traversing the tree.
   */
  Iterable<PathFragment> getEmptyFilenamesForLogging();

  /** Returns the set of custom symlink entries. */
  NestedSet<SymlinkEntry> getSymlinksForLogging();

  /** Returns the set of root symlinks. */
  NestedSet<SymlinkEntry> getRootSymlinksForLogging();

  /** Returns the repo mapping manifest if it exists. */
  @Nullable
  Artifact getRepoMappingManifestForLogging();

  /** Whether this runfiles tree materializes external runfiles also at their legacy locations. */
  boolean isLegacyExternalRunfiles();

  /** Whether {@link #getMapping()} is cached due to potential reuse within a single build. */
  boolean isMappingCached();

  /** Fingerprints this runfiles tree. */
  void fingerprint(ActionKeyContext actionKeyContext, Fingerprint fp, boolean digestAbsolutePaths);
}
