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

import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.packages.PackageArgs;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/** A {@link SkyFunction} that returns the {@link PackageArgs} for a given repository. */
public class RepoPackageArgsFunction implements SkyFunction {
  public static final RepoPackageArgsFunction INSTANCE = new RepoPackageArgsFunction();

  /** {@link SkyValue} wrapping a PackageArgs. */
  public static final class RepoPackageArgsValue implements SkyValue {
    public static final RepoPackageArgsValue EMPTY = new RepoPackageArgsValue(PackageArgs.EMPTY);

    private final PackageArgs packageArgs;

    public RepoPackageArgsValue(PackageArgs packageArgs) {
      this.packageArgs = packageArgs;
    }

    public PackageArgs getPackageArgs() {
      return packageArgs;
    }

    @Override
    public int hashCode() {
      return packageArgs.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof RepoPackageArgsValue that) {
        return that.packageArgs.equals(packageArgs);
      } else {
        return false;
      }
    }
  }

  public static Key key(RepositoryName repoName) {
    return new Key(repoName);
  }

  /** Thrown when there is something wrong with the arguments of the {@code repo()} function. */
  private static class BadPackageArgsException extends Exception {
    public BadPackageArgsException(String message, Exception cause) {
      super(message, cause);
    }
  }

  /** A {@link SkyFunctionException} for {@link RepoPackageArgsFunction}. */
  private static class RepoPackageArgsFunctionException extends SkyFunctionException {
    private RepoPackageArgsFunctionException(BadPackageArgsException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  /** Key type for {@link RepoPackageArgsValue}. */
  public static class Key extends AbstractSkyKey<RepositoryName> {

    private Key(RepositoryName repoName) {
      super(repoName);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.REPO_PACKAGE_ARGS;
    }
  }

  private RepoPackageArgsFunction() {}

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RepositoryName repositoryName = (RepositoryName) skyKey.argument();

    RepoFileValue repoFileValue = (RepoFileValue) env.getValue(RepoFileValue.key(repositoryName));
    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(repositoryName));
    RepositoryMappingValue mainRepoMapping =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));

    if (env.valuesMissing()) {
      return null;
    }

    String repoDisplayName =
        RepoFileFunction.getDisplayNameForRepo(repositoryName, mainRepoMapping.repositoryMapping());

    PackageArgs.Builder pkgArgsBuilder = PackageArgs.builder();
    LabelConverter labelConverter =
        new LabelConverter(
            PackageIdentifier.create(repositoryName, PathFragment.EMPTY_FRAGMENT),
            repositoryMappingValue.repositoryMapping());
    try {
      for (Map.Entry<String, Object> kwarg : repoFileValue.packageArgsMap().entrySet()) {
        PackageArgs.processParam(
            kwarg.getKey(),
            kwarg.getValue(),
            "repo() argument '" + kwarg.getKey() + "'",
            labelConverter,
            pkgArgsBuilder);
      }
    } catch (EvalException e) {
      env.getListener().handle(Event.error(e.getMessageWithStack()));
      throw new RepoPackageArgsFunctionException(
          new BadPackageArgsException(
              "error evaluating REPO.bazel file for " + repoDisplayName, e));
    }

    return new RepoPackageArgsValue(pkgArgsBuilder.build());
  }
}
