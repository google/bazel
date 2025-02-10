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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.includescanning.IncludeParser.Inclusion;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Creates include scanner instances.
 *
 * <p>Each include scanner is specific to a given triplet (-I, -isystem, -iquote) of include paths.
 */
public class IncludeScannerSupplier {
  private static class IncludeScannerParams {
    final List<PathFragment> quoteIncludePaths;
    final List<PathFragment> includePaths;
    final List<PathFragment> frameworkIncludePaths;

    IncludeScannerParams(
        List<PathFragment> quoteIncludePaths,
        List<PathFragment> includePaths,
        List<PathFragment> frameworkIncludePaths) {
      this.quoteIncludePaths = quoteIncludePaths;
      this.includePaths = includePaths;
      this.frameworkIncludePaths = frameworkIncludePaths;
    }

    @Override
    public int hashCode() {
      return Objects.hash(quoteIncludePaths, includePaths, frameworkIncludePaths);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof IncludeScannerParams that)) {
        return false;
      }
      return this.quoteIncludePaths.equals(that.quoteIncludePaths)
          && this.includePaths.equals(that.includePaths)
          && this.frameworkIncludePaths.equals(that.frameworkIncludePaths);
    }
  }

  private IncludeParser includeParser;

  /**
   * Cache of include scan results mapping source paths to sets of scanned inclusions. Shared by all
   * scanner instances.
   */
  private final ConcurrentMap<Artifact, ListenableFuture<Collection<Inclusion>>> includeParseCache =
      new ConcurrentHashMap<>();

  /** Cache of include scanner instances mapped by include-path hashes. */
  private final LoadingCache<IncludeScannerParams, IncludeScanner> scanners;

  public IncludeScannerSupplier(
      BlazeDirectories directories,
      ExecutorService includePool,
      boolean shouldShuffle,
      ArtifactFactory artifactFactory,
      Supplier<SpawnIncludeScanner> spawnIncludeScannerSupplier,
      Path execRoot) {
    // Map of grepped include files from input (.cc or .h) to a header-grepped file.
    PathExistenceCache pathCache = new PathExistenceCache(execRoot, artifactFactory);
    scanners =
        Caffeine.newBuilder()
            // We choose to make cache values weak referenced due to LegacyIncludeScanner can hold
            // on to a memory expensive InclusionCache. However, a lot of IncludeScannerParams are
            // not in use so they are eligible for garbage collection. As a matter of fact, this
            // reduces peak heap on an example cpp-heavy build by ~5%.

            //
            // We could also choose to use softValues() but avoid doing so. The reason is that we
            // want to keep blaze memory usage deterministic and to guarantee collection before
            // blaze initiated-OOMs.
            .weakValues()
            .build(
                key ->
                    new LegacyIncludeScanner(
                        includeParser,
                        includePool,
                        shouldShuffle,
                        includeParseCache,
                        pathCache,
                        key.quoteIncludePaths,
                        key.includePaths,
                        key.frameworkIncludePaths,
                        directories.getOutputPath(execRoot.getBaseName()),
                        execRoot,
                        artifactFactory,
                        spawnIncludeScannerSupplier));
  }

  /**
   * Returns the possibly shared scanner to be used for a given triplet of include paths. The paths
   * are specified as PathFragments relative to the execution root.
   */
  public IncludeScanner scannerFor(
      List<PathFragment> quoteIncludePaths,
      List<PathFragment> includePaths,
      List<PathFragment> frameworkPaths) {
    Preconditions.checkNotNull(includeParser);
    return scanners.get(new IncludeScannerParams(quoteIncludePaths, includePaths, frameworkPaths));
  }

  public void init(IncludeParser includeParser) {
    Preconditions.checkState(
        this.includeParser == null,
        "Must only be initialized once: %s %s",
        this.includeParser,
        includeParser);
    Preconditions.checkState(includeParseCache.isEmpty(), includeParseCache);
    Preconditions.checkState(scanners.asMap().isEmpty(), scanners);
    this.includeParser = Preconditions.checkNotNull(includeParser);
  }
}
