// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/** Include scanner for swig files. */
public class SwigIncludeScanner extends LegacyIncludeScanner {

  /**
   * Constructs a new SwigIncludeScanner used to parse swig include statements (%include / %extern /
   * %import).
   *
   * @param spawnIncludeScanner
   * @param cache externally scoped cache of file-path to inclusion-set mappings
   * @param includePaths the list of search path dirs
   * @param execRoot
   */
  public SwigIncludeScanner(
      ExecutorService includePool,
      boolean shouldShuffle,
      SpawnIncludeScanner spawnIncludeScanner,
      ConcurrentMap<Artifact, ListenableFuture<Collection<Inclusion>>> cache,
      List<PathFragment> includePaths,
      BlazeDirectories directories,
      ArtifactFactory artifactFactory,
      Path execRoot) {
    super(
        new SwigIncludeParser(),
        includePool,
        shouldShuffle,
        cache,
        new PathExistenceCache(execRoot, artifactFactory),
        /* quoteIncludePaths= */ ImmutableList.of(),
        includePaths,
        /* frameworkIncludePaths= */ ImmutableList.of(),
        directories.getOutputPath(execRoot.getBaseName()),
        execRoot,
        artifactFactory,
        () -> spawnIncludeScanner);
  }
}
