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

package com.google.devtools.build.lib.analysis.config;


import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import javax.annotation.Nullable;

/**
 * Logic for figuring out what base directories to place outputs generated from a given
 * configuration.
 *
 * <p>In other words, when your output ends up in <code>blaze-out/x86-fastbuild/...</code>, this
 * class is why.
 */
public class OutputDirectories {
  /**
   * Directories in the output tree.
   *
   * <p>The computation of the output directory should be a non-injective mapping from
   * BuildConfigurationValue instances to strings. The result should identify the aspects of the
   * configuration that should be reflected in the output file names. Furthermore the returned
   * string must not contain shell metacharacters.
   *
   * <p>For configuration settings which are NOT part of the output directory name, rebuilding with
   * a different value of such a setting will build in the same output directory. This means that
   * any actions whose keys (see Action.getKey()) have changed will be rerun. That may result in a
   * lot of recompilation.
   *
   * <p>For configuration settings which ARE part of the output directory name, rebuilding with a
   * different value of such a setting will rebuild in a different output directory; this will
   * result in higher disk usage and more work the <i>first</i> time you rebuild with a different
   * setting, but will result in less work if you regularly switch back and forth between different
   * settings.
   *
   * <p>With one important exception, it's sound to choose any subset of the config's components for
   * this string, it just alters the dimensionality of the cache. In other words, it's a trade-off
   * on the "injectiveness" scale: at one extreme (output directory name contains all data in the
   * config, and is thus injective) you get extremely precise caching (no competition for the same
   * output-file locations) but you have to rebuild for even the slightest change in configuration.
   * At the other extreme (the output (directory name is a constant) you have very high competition
   * for output-file locations, but if a slight change in configuration doesn't affect a particular
   * build step, you're guaranteed not to have to rebuild it. The important exception has to do with
   * multiple configurations: every configuration in the build must have a different output
   * directory name so that their artifacts do not conflict.
   */
  public enum OutputDirectory {
    BIN("bin"),
    GENFILES("genfiles"),
    TESTLOGS("testlogs"),
    COVERAGE("coverage-metadata"),
    OUTPUT("");

    private final String name;

    OutputDirectory(String name) {
      // Must be a legal basename for root - multiple segments not allowed.
      if (!name.isEmpty()) {
        FileSystemUtils.checkBaseName(name);
      }
      this.name = name;
    }

    public ArtifactRoot getRoot(
        String outputDirName, BlazeDirectories directories, String workspaceName) {
      // e.g., execroot/my_workspace
      Path execRoot = directories.getExecRoot(workspaceName);
      // e.g., [[execroot/my_workspace]/bazel-out/config/bin]
      return ArtifactRoot.asDerivedRoot(
          execRoot, RootType.Output, directories.getRelativeOutputPath(), outputDirName, name);
    }
  }

  private final BlazeDirectories directories;
  private final String mnemonic;

  private final ArtifactRoot outputDirectory;
  private final ArtifactRoot binDirectory;
  private final ArtifactRoot genfilesDirectory;
  private final ArtifactRoot coverageDirectory;
  private final ArtifactRoot testlogsDirectory;

  private final boolean mergeGenfilesDirectory;

  private final boolean siblingRepositoryLayout;

  private final Path execRoot;

  OutputDirectories(
      BlazeDirectories directories,
      CoreOptions options,
      @Nullable PlatformOptions platformOptions,
      String mnemonic,
      String workspaceName,
      boolean siblingRepositoryLayout) {
    this.directories = directories;
    this.mnemonic = mnemonic;

    this.outputDirectory = OutputDirectory.OUTPUT.getRoot(mnemonic, directories, workspaceName);
    this.binDirectory = OutputDirectory.BIN.getRoot(mnemonic, directories, workspaceName);
    this.genfilesDirectory = OutputDirectory.GENFILES.getRoot(mnemonic, directories, workspaceName);
    this.coverageDirectory = OutputDirectory.COVERAGE.getRoot(mnemonic, directories, workspaceName);
    this.testlogsDirectory = OutputDirectory.TESTLOGS.getRoot(mnemonic, directories, workspaceName);

    this.mergeGenfilesDirectory = options.mergeGenfilesDirectory;
    this.siblingRepositoryLayout = siblingRepositoryLayout;
    this.execRoot = directories.getExecRoot(workspaceName);
  }

  private ArtifactRoot buildDerivedRoot(String nameFragment, RepositoryName repository) {
    return ArtifactRoot.asDerivedRoot(
        execRoot,
        // e.g., execroot/mainRepoName/bazel-out/[repoName/]config/bin
        // TODO(jungjw): Ideally, we would like to do execroot_base/repoName/bazel-out/config/bin
        // instead. However, it requires individually symlinking the top-level elements of external
        // repositories, which is blocked by a Windows symlink issue #8704.
        repository.isMain() ? RootType.SiblingMainOutput : RootType.SiblingExternalOutput,
        directories.getRelativeOutputPath(),
        repository.getName(),
        mnemonic,
        nameFragment);
  }

  /** Returns the output directory for this build configuration. */
  ArtifactRoot getOutputDirectory(RepositoryName repositoryName) {
    return siblingRepositoryLayout ? buildDerivedRoot("", repositoryName) : outputDirectory;
  }

  /** Returns the bin directory for this build configuration. */
  ArtifactRoot getBinDirectory(RepositoryName repositoryName) {
    return siblingRepositoryLayout ? buildDerivedRoot("bin", repositoryName) : binDirectory;
  }

  /** Returns the genfiles directory for this build configuration. */
  ArtifactRoot getGenfilesDirectory(RepositoryName repositoryName) {
    return mergeGenfilesDirectory
        ? getBinDirectory(repositoryName)
        : siblingRepositoryLayout
            ? buildDerivedRoot("genfiles", repositoryName)
            : genfilesDirectory;
  }

  /**
   * Returns the directory where coverage-related artifacts and metadata files should be stored.
   * This includes for example uninstrumented class files needed for Jacoco's coverage reporting
   * tools.
   */
  ArtifactRoot getCoverageMetadataDirectory(RepositoryName repositoryName) {
    return siblingRepositoryLayout
        ? buildDerivedRoot("coverage-metadata", repositoryName)
        : coverageDirectory;
  }

  /** Returns the testlogs directory for this build configuration. */
  ArtifactRoot getTestLogsDirectory(RepositoryName repositoryName) {
    return siblingRepositoryLayout
        ? buildDerivedRoot("testlogs", repositoryName)
        : testlogsDirectory;
  }

  /** Returns a relative path to the genfiles directory at execution time. */
  PathFragment getGenfilesFragment(RepositoryName repositoryName) {
    return getGenfilesDirectory(repositoryName).getExecPath();
  }

  /**
   * Returns the path separator for the host platform. This is basically the same as {@link
   * java.io.File#pathSeparator}, except that that returns the value for this JVM, which may or may
   * not match the host platform. You should only use this when invoking tools that are known to use
   * the native path separator, i.e., the path separator for the machine that they run on.
   */
  String getHostPathSeparator() {
    // TODO(bazel-team): Maybe do this in the constructor instead? This isn't serialization-safe.
    return OS.getCurrent() == OS.WINDOWS ? ";" : ":";
  }

  String getMnemonic() {
    return mnemonic;
  }

  String getOutputDirName() {
    return getMnemonic();
  }

  boolean mergeGenfilesDirectory() {
    return mergeGenfilesDirectory;
  }

  BlazeDirectories getDirectories() {
    return directories;
  }
}
