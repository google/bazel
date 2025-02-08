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
package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.vfs.PathFragment;

/** The file (BUILD, WORKSPACE, etc.) that defines this package, referred to as the "build file". */
public enum BuildFileName {
  WORKSPACE("WORKSPACE") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return getFilenameFragment();
    }
  },
  WORKSPACE_DOT_BAZEL("WORKSPACE.bazel") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return getFilenameFragment();
    }
  },
  WORKSPACE_DOT_BZLMOD("WORKSPACE.bzlmod") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return getFilenameFragment();
    }
  },
  MODULE_DOT_BAZEL("MODULE.bazel") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return getFilenameFragment();
    }
  },
  BUILD("BUILD") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return packageIdentifier.getPackageFragment().getRelative(getFilenameFragment());
    }
  },
  BUILD_DOT_BAZEL("BUILD.bazel") {
    @Override
    public PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier) {
      return packageIdentifier.getPackageFragment().getRelative(getFilenameFragment());
    }
  };

  private final PathFragment filenameFragment;

  BuildFileName(String filename) {
    this.filenameFragment = PathFragment.create(filename);
  }

  public PathFragment getFilenameFragment() {
    return filenameFragment;
  }

  /**
   * Returns a {@link PathFragment} to the build file that defines the package.
   *
   * @param packageIdentifier the identifier for this package
   */
  public abstract PathFragment getBuildFileFragment(PackageIdentifier packageIdentifier);
}
