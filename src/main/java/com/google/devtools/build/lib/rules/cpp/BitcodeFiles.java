// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.compacthashmap.CompactHashMap;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.lang.ref.WeakReference;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Wrapper around a map of bitcode files for purposes of caching its fingerprint.
 *
 * <p>Each instance is potentially shared by many {@link LtoBackendAction} instances.
 */
final class BitcodeFiles {

  private final NestedSet<Artifact> files;
  @Nullable private volatile byte[] fingerprint = null;

  private volatile WeakReference<Map<PathFragment, Artifact>> filesArtifactPathMapReference =
      new WeakReference<>(null);

  BitcodeFiles(NestedSet<Artifact> files) {
    this.files = files;
  }

  NestedSet<Artifact> getFiles() {
    return files;
  }

  /** Helper function to get a map from path to artifact */
  Map<PathFragment, Artifact> getFilesArtifactPathMap() {
    // This method is called once per LtoBackendAction instance that shares this BitcodeFiles
    // instance. Therefore we weakly cache the result.
    //
    // It's a garbage hotspot, so we deliberately use a presized CompactHashMap instead of
    // streams and ImmutableMap. In a build with many LtoBackendAction instances, this approach
    // reduced garbage allocated by this method by ~65%. The approach of caching the result further
    // reduced garbage up to a total reduction of >99%.

    Map<PathFragment, Artifact> result = filesArtifactPathMapReference.get();
    if (result != null) {
      return result;
    }

    synchronized (this) {
      result = filesArtifactPathMapReference.get();
      if (result != null) {
        return result;
      }
      ImmutableList<Artifact> filesList = getFiles().toList();
      result = CompactHashMap.createWithExpectedSize(filesList.size());
      for (Artifact file : filesList) {
        result.put(file.getExecPath(), file);
      }
      filesArtifactPathMapReference = new WeakReference<>(result);
      return result;
    }
  }

  void addToFingerprint(Fingerprint fp) {
    if (fingerprint == null) {
      synchronized (this) {
        if (fingerprint == null) {
          fingerprint = computeFingerprint();
        }
      }
    }
    fp.addBytes(fingerprint);
  }

  private byte[] computeFingerprint() {
    Fingerprint fp = new Fingerprint();
    for (Artifact path : files.toList()) {
      fp.addPath(path.getExecPath());
    }
    return fp.digestAndReset();
  }
}
