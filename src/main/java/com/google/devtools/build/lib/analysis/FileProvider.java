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

package com.google.devtools.build.lib.analysis;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.starlarkbuildapi.FileProviderApi;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkThread;

/**
 * A representation of the concept "this transitive info provider builds these files".
 *
 * <p>Every transitive info collection contains at least this provider.
 */
@Immutable
public final class FileProvider implements TransitiveInfoProvider, FileProviderApi {

  public static final FileProvider EMPTY =
      new FileProvider(NestedSetBuilder.emptySet(Order.STABLE_ORDER));

  public static FileProvider of(NestedSet<Artifact> filesToBuild) {
    return filesToBuild.isEmpty() ? EMPTY : new FileProvider(filesToBuild);
  }

  private final NestedSet<Artifact> filesToBuild;

  private FileProvider(NestedSet<Artifact> filesToBuild) {
    this.filesToBuild = filesToBuild;
  }

  @Override
  public boolean isImmutable() {
    return true; // immutable and Starlark-hashable
  }

  @Override
  public Depset /*<Artifact>*/ getFilesToBuildForStarlark() {
    return Depset.of(Artifact.class, filesToBuild);
  }

  @Override
  public void debugPrint(Printer printer, StarlarkThread thread) {
    printer.append("FileProvider(files_to_build = ");
    printer.debugPrint(getFilesToBuildForStarlark(), thread);
    printer.append(")");
  }

  public NestedSet<Artifact> getFilesToBuild() {
    return filesToBuild;
  }
}
