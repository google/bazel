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

import static java.util.Objects.requireNonNull;

import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * Contains information about the recursive digest of a directory tree, including all transitive
 * descendant files and their contents.
 */
public record DirectoryTreeDigestValue(String hexDigest) implements SkyValue {
  public DirectoryTreeDigestValue {
    requireNonNull(hexDigest, "hexDigest");
  }

  public static DirectoryTreeDigestValue of(String hexDigest) {
    return new DirectoryTreeDigestValue(hexDigest);
  }

  public static Key key(RootedPath path) {
    return new Key(path);
  }

  /** Key type for {@link DirectoryTreeDigestValue}. */
  public static class Key extends AbstractSkyKey<RootedPath> {

    private Key(RootedPath rootedPath) {
      super(rootedPath);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.DIRECTORY_TREE_DIGEST;
    }
  }
}
