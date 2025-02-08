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
package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction.VENDOR_DIRECTORY;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import com.google.common.io.LineProcessor;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.InvalidIgnorePathException;
import com.google.devtools.build.lib.skyframe.RepoFileFunction.BadRepoFileException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import javax.annotation.Nullable;

/**
 * A {@link SkyFunction} for {@link IgnoredSubdirectoriesValue}.
 *
 * <p>It is used to compute which directories should be ignored in a package. These either come from
 * the {@code .bazelignore} file or from the {@code ignored_directories()} function in {@code
 * REPO.bazel}.
 *
 * <p>This is intended for directories containing non-bazel sources (either generated, or versioned
 * sources built by other tools) that happen to contain a file called BUILD.
 *
 * <p>For the time being, this ignore functionality is limited by the fact that it is applied only
 * after pattern expansion. So if a pattern expansion fails (e.g., due to symlink-cycles) and
 * therefore fails the build, this ignore functionality currently has no chance to kick in.
 */
public class IgnoredSubdirectoriesFunction implements SkyFunction {
  /** Repository-relative path of the bazelignore file. */
  public static final PathFragment BAZELIGNORE_REPOSITORY_RELATIVE_PATH =
      PathFragment.create(".bazelignore");

  /** Singleton instance of this {@link SkyFunction}. */
  public static final IgnoredSubdirectoriesFunction INSTANCE = new IgnoredSubdirectoriesFunction();

  /**
   * A version of {@link IgnoredSubdirectoriesFunction} that always returns the empty value.
   *
   * <p>Used for tests where the extra complications incurred by evaluating the function are
   * undesired.
   */
  public static final SkyFunction NOOP = (skyKey, env) -> IgnoredSubdirectoriesValue.EMPTY;

  private IgnoredSubdirectoriesFunction() {}

  public static void getIgnoredPrefixes(
      RootedPath patternFile, ImmutableSet.Builder<PathFragment> ignoredDirectoriesBuilder)
      throws IgnoredSubdirectoriesFunctionException {
    try (InputStreamReader reader =
        new InputStreamReader(patternFile.asPath().getInputStream(), StandardCharsets.UTF_8)) {
      for (PathFragment ignored : CharStreams.readLines(reader, new PathFragmentLineProcessor())) {
        if (ignored.isAbsolute()) {
          throw new IgnoredSubdirectoriesFunctionException(
              new InvalidIgnorePathException(
                  patternFile.asPath().toString(),
                  String.format("'%s': cannot be an absolute path", ignored)));
        }

        ignoredDirectoriesBuilder.add(ignored);
      }
    } catch (IOException e) {
      String errorMessage = e.getMessage() != null ? "error '" + e.getMessage() + "'" : "an error";
      throw new IgnoredSubdirectoriesFunctionException(
          new InconsistentFilesystemException(
              patternFile.asPath()
                  + " is not readable because: "
                  + errorMessage
                  + ". Was it modified mid-build?"));
    } catch (InvalidPathException e) {
      throw new IgnoredSubdirectoriesFunctionException(
          new InvalidIgnorePathException(patternFile.asPath().toString(), e.getMessage()));
    }
  }

  @Nullable
  private ImmutableList<String> computeIgnoredPatterns(
      Environment env, RepositoryName repositoryName)
      throws IgnoredSubdirectoriesFunctionException, InterruptedException {

    try {
      RepoFileValue repoFileValue =
          (RepoFileValue)
              env.getValueOrThrow(
                  RepoFileValue.key(repositoryName), IOException.class, BadRepoFileException.class);

      if (env.valuesMissing()) {
        return null;
      }

      return repoFileValue.ignoredDirectories();
    } catch (IOException e) {
      throw new IgnoredSubdirectoriesFunctionException(e);
    } catch (BadRepoFileException e) {
      throw new IgnoredSubdirectoriesFunctionException(e);
    }
  }

  @Nullable
  private ImmutableSet<PathFragment> computeIgnoredPrefixes(
      Environment env, RepositoryName repositoryName)
      throws IgnoredSubdirectoriesFunctionException, InterruptedException {
    ImmutableSet.Builder<PathFragment> ignoredPrefixesBuilder = ImmutableSet.builder();
    PathPackageLocator pkgLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    if (env.valuesMissing()) {
      return null;
    }

    if (repositoryName.isMain()) {
      PathFragment vendorDir = null;
      if (VENDOR_DIRECTORY.get(env).isPresent()) {
        vendorDir = VENDOR_DIRECTORY.get(env).get().asFragment();
      }

      for (Root packagePathEntry : pkgLocator.getPathEntries()) {
        PathFragment workspaceRoot = packagePathEntry.asPath().asFragment();
        if (vendorDir != null && vendorDir.startsWith(workspaceRoot)) {
          ignoredPrefixesBuilder.add(vendorDir.relativeTo(workspaceRoot));
        }

        RootedPath rootedPrefixFile =
            RootedPath.toRootedPath(packagePathEntry, BAZELIGNORE_REPOSITORY_RELATIVE_PATH);
        FileValue prefixFileValue = (FileValue) env.getValue(FileValue.key(rootedPrefixFile));
        if (prefixFileValue == null) {
          return null;
        }
        if (prefixFileValue.isFile()) {
          getIgnoredPrefixes(rootedPrefixFile, ignoredPrefixesBuilder);
          break;
        }
      }
    } else {
      // Make sure the repository is fetched.
      RepositoryDirectoryValue repositoryValue =
          (RepositoryDirectoryValue) env.getValue(RepositoryDirectoryValue.key(repositoryName));
      if (repositoryValue == null) {
        return null;
      }
      if (repositoryValue.repositoryExists()) {
        RootedPath rootedPrefixFile =
            RootedPath.toRootedPath(
                Root.fromPath(repositoryValue.getPath()), BAZELIGNORE_REPOSITORY_RELATIVE_PATH);
        FileValue prefixFileValue = (FileValue) env.getValue(FileValue.key(rootedPrefixFile));
        if (prefixFileValue == null) {
          return null;
        }
        if (prefixFileValue.isFile()) {
          getIgnoredPrefixes(rootedPrefixFile, ignoredPrefixesBuilder);
        }
      }
    }

    return ignoredPrefixesBuilder.build();
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws IgnoredSubdirectoriesFunctionException, InterruptedException {
    RepositoryName repositoryName = (RepositoryName) key.argument();

    ImmutableList<String> ignoredPatterns = computeIgnoredPatterns(env, repositoryName);
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableSet<PathFragment> ignoredPrefixes = computeIgnoredPrefixes(env, repositoryName);
    if (env.valuesMissing()) {
      return null;
    }

    return IgnoredSubdirectoriesValue.of(ignoredPrefixes, ignoredPatterns);
  }

  private static final class PathFragmentLineProcessor
      implements LineProcessor<ImmutableSet<PathFragment>> {
    private final ImmutableSet.Builder<PathFragment> fragments = ImmutableSet.builder();

    @Override
    public boolean processLine(String line) {
      if (!line.isEmpty() && !line.startsWith("#")) {
        fragments.add(PathFragment.create(line));

        // This is called for its side-effects rather than its output.
        // Specifically, it validates that the line is a valid path. This
        // doesn't do much on UNIX machines where only NUL is an invalid
        // character but can reject paths on Windows.
        //
        // This logic would need to be adjusted if wildcards are ever supported
        // (https://github.com/bazelbuild/bazel/issues/7093).
        var unused = Path.of(line);
      }
      return true;
    }

    @Override
    public ImmutableSet<PathFragment> getResult() {
      return fragments.build();
    }
  }

  private static final class IgnoredSubdirectoriesFunctionException extends SkyFunctionException {
    public IgnoredSubdirectoriesFunctionException(InconsistentFilesystemException e) {
      super(e, Transience.TRANSIENT);
    }

    public IgnoredSubdirectoriesFunctionException(InvalidIgnorePathException e) {
      super(e, Transience.PERSISTENT);
    }

    public IgnoredSubdirectoriesFunctionException(IOException e) {
      super(e, Transience.TRANSIENT);
    }

    public IgnoredSubdirectoriesFunctionException(BadRepoFileException e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
