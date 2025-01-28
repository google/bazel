// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkThread;

/**
 * Converts a label literal string into a {@link Label} object, using the appropriate base package
 * and repo mapping.
 */
public class LabelConverter {

  /**
   * Returns a label converter for the given thread, which MUST be currently evaluating Starlark
   * code in a .bzl file (top-level, macro, rule implementation function, etc.). It uses the package
   * containing the .bzl file as the base package, and the repo mapping of the repo containing the
   * .bzl file.
   */
  public static LabelConverter forBzlEvaluatingThread(StarlarkThread thread) {
    BazelModuleContext moduleContext = BazelModuleContext.ofInnermostBzlOrThrow(thread);
    return new LabelConverter(moduleContext.packageContext());
  }

  private final Label.PackageContext packageContext;
  private final Map<String, Label> labelCache = new HashMap<>();
  @Nullable private final Label.RepoMappingRecorder repoMappingRecorder;

  private LabelConverter(
      Label.PackageContext packageContext,
      @Nullable Label.RepoMappingRecorder repoMappingRecorder) {
    this.packageContext = packageContext;
    this.repoMappingRecorder = repoMappingRecorder;
  }

  public LabelConverter(Label.PackageContext packageContext) {
    this(packageContext, null);
  }

  /** Creates a label converter using the given base package and repo mapping. */
  public LabelConverter(PackageIdentifier base, RepositoryMapping repositoryMapping) {
    this(Label.PackageContext.of(base, repositoryMapping));
  }

  /**
   * Creates a label converter using the given base package and repo mapping, recording all repo
   * mapping lookups in the given recorder.
   */
  public LabelConverter(
      PackageIdentifier base,
      RepositoryMapping repositoryMapping,
      Label.RepoMappingRecorder repoMappingRecorder) {
    this(Label.PackageContext.of(base, repositoryMapping), repoMappingRecorder);
  }

  /** Returns the base package identifier that relative labels will be resolved against. */
  PackageIdentifier getBasePackage() {
    return packageContext.packageIdentifier();
  }

  /** Returns the Label corresponding to the input, using the current conversion context. */
  public Label convert(String input) throws LabelSyntaxException {
    // Optimization: First check the package-local map, avoiding Label validation, Label
    // construction, and global Interner lookup. This approach tends to be very profitable
    // overall, since it's common for the targets in a single package to have duplicate
    // label-strings across all their attribute values.
    Label converted = labelCache.get(input);
    if (converted == null) {
      converted = Label.parseWithPackageContext(input, packageContext, repoMappingRecorder);
      labelCache.put(input, converted);
    }
    return converted;
  }

  @Override
  public String toString() {
    return getBasePackage().toString();
  }
}
