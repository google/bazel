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

package com.google.devtools.build.lib.rules.repository;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.cmdline.LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.repository.ExternalPackageException;
import com.google.devtools.build.lib.repository.RepositoryFetchProgress;
import com.google.devtools.build.lib.skyframe.ActionEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.AlreadyReportedException;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Implementation of fetching various external repository types.
 *
 * <p>These objects are called from {@link RepositoryDelegatorFunction}.
 *
 * <p>External repositories come in two flavors: local and non-local.
 *
 * <p>Local ones are those whose fetching does not require access to any external resources (e.g.
 * network). These are always re-fetched on Bazel server restarts. This operation is fast (usually
 * just a few symlinks and maybe writing a BUILD file). {@code --nofetch} does not apply to local
 * repositories.
 *
 * <p>The up-to-dateness of non-local repositories is checked using a marker file under the output
 * base. When such a repository is fetched, data from the rule in the WORKSPACE file is written to
 * the marker file which is consulted on next server startup. If the rule hasn't changed, the
 * repository is not re-fetched.
 *
 * <p>Fetching repositories can be disabled using the {@code --nofetch} command line option. If a
 * repository is on the file system, Bazel just tries to use it and hopes for the best. If the
 * repository has never been fetched, Bazel errors out for lack of a better option. This is
 * implemented using {@link
 * com.google.devtools.build.lib.bazel.BazelRepositoryModule#REPOSITORY_VALUE_CHECKER} and a flag in
 * {@link RepositoryDirectoryValue} that tells Bazel whether the value in Skyframe is stale
 * according to the value of {@code --nofetch} or not.
 *
 * <p>When a rule in the WORKSPACE file is changed, the corresponding {@link
 * RepositoryDirectoryValue} is invalidated using the usual Skyframe route.
 */
public abstract class RepositoryFunction {

  protected Map<String, String> clientEnvironment;

  /**
   * Exception thrown when something goes wrong accessing a remote repository.
   *
   * <p>This exception should be used by child classes to limit the types of exceptions {@link
   * RepositoryDelegatorFunction} has to know how to catch.
   */
  public static class RepositoryFunctionException extends SkyFunctionException {

    /** Error reading or writing to the filesystem. */
    public RepositoryFunctionException(IOException cause, Transience transience) {
      super(cause, transience);
    }

    /** For errors in WORKSPACE file rules (e.g., malformed paths or URLs). */
    public RepositoryFunctionException(EvalException cause, Transience transience) {
      super(cause, transience);
    }

    public RepositoryFunctionException(
        AlreadyReportedRepositoryAccessException cause, Transience transience) {
      super(cause, transience);
    }

    public RepositoryFunctionException(ExternalPackageException e) {
      super(e.getCause(), e.isTransient() ? Transience.TRANSIENT : Transience.PERSISTENT);
    }
  }

  /**
   * Encapsulates the exceptions that arise when accessing a repository. Error reporting should ONLY
   * be handled in {@link RepositoryDelegatorFunction#fetchRepository}.
   */
  public static class AlreadyReportedRepositoryAccessException extends AlreadyReportedException {
    public AlreadyReportedRepositoryAccessException(Exception e) {
      super(e.getMessage(), e.getCause());
      checkState(
          e instanceof NoSuchPackageException
              || e instanceof IOException
              || e instanceof EvalException
              || e instanceof ExternalPackageException,
          e);
    }
  }

  public static void setupRepoRoot(Path repoRoot) throws RepositoryFunctionException {
    try {
      repoRoot.deleteTree();
      Preconditions.checkNotNull(repoRoot.getParentDirectory()).createDirectoryAndParents();
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  public static boolean isWorkspaceRepo(Rule rule) {
    // All workspace repos are under //external, while bzlmod repo rules are not
    return rule.getPackage().getPackageIdentifier().equals(EXTERNAL_PACKAGE_IDENTIFIER);
  }

  protected void setupRepoRootBeforeFetching(Path repoRoot) throws RepositoryFunctionException {
    setupRepoRoot(repoRoot);
  }

  public void reportSkyframeRestart(Environment env, RepositoryName repoName) {
    env.getListener().post(RepositoryFetchProgress.ongoing(repoName, "Restarting."));
  }

  /**
   * Fetch the remote repository represented by the given rule.
   *
   * <p>When this method is called, it has already been determined that the repository is stale and
   * that it needs to be re-fetched.
   *
   * <p>The {@code env} argument can be used to fetch Skyframe dependencies the repository
   * implementation needs on the following conditions:
   *
   * <ul>
   *   <li>When a Skyframe value is missing, fetching must be restarted, thus, in order to avoid
   *       doing duplicate work, it's better to first request the Skyframe dependencies you need and
   *       only then start doing anything costly.
   *   <li>The output directory must be populated from within this method (and not from within
   *       another SkyFunction). This is because if it was populated in another SkyFunction, the
   *       repository function would be restarted <b>after</b> that SkyFunction has been run, and it
   *       would wipe the output directory clean.
   * </ul>
   */
  @ThreadSafe
  @Nullable
  public abstract FetchResult fetch(
      Rule rule, Path outputDirectory, BlazeDirectories directories, Environment env, SkyKey key)
      throws InterruptedException, RepositoryFunctionException;

  /**
   * The result of the {@link #fetch} method.
   *
   * @param repoBuilder A builder for the eventual {@link RepositoryDirectoryValue} with necessary
   *     fields set.
   * @param recordedInputValues Any recorded inputs (and their values) encountered during the fetch
   *     of the repo. Changes to these inputs will result in the repo being refetched in the future.
   *     The {@link #isAnyRecordedInputOutdated} method is responsible for checking the value added
   *     to that map when checking the content of a marker file. Not an ImmutableMap, because
   *     regrettably the values can be null sometimes.
   */
  public record FetchResult(
      RepositoryDirectoryValue.Builder repoBuilder,
      Map<? extends RepoRecordedInput, String> recordedInputValues) {}

  protected static void ensureNativeRepoRuleEnabled(Rule rule, Environment env, String replacement)
      throws RepositoryFunctionException, InterruptedException {
    if (!isWorkspaceRepo(rule)) {
      // If this native repo rule is used in a Bzlmod context, always allow it. This is because
      // we're still using the native repo rule `local_config_platform` for the builtin module with
      // the same name. We should just get rid of that.
      return;
    }
    if (!RepositoryDelegatorFunction.DISABLE_NATIVE_REPO_RULES.get(env)) {
      return;
    }
    throw new RepositoryFunctionException(
        Starlark.errorf(
            "Native repo rule %s is disabled since the flag "
                + "--incompatible_disable_native_repo_rules is set. Native repo rules are "
                + "deprecated; please migrate to their Starlark counterparts. For %s, please use "
                + "%s.",
            rule.getRuleClass(), rule.getRuleClass(), replacement),
        Transience.PERSISTENT);
  }

  /**
   * Verify the data provided by the marker file to check if a refetch is needed. Returns an empty
   * Optional if the data is up to date and no refetch is needed and an Optional with a
   * human-readable reason if the data is obsolete and a refetch is needed.
   */
  public Optional<String> isAnyRecordedInputOutdated(
      BlazeDirectories directories,
      Map<RepoRecordedInput, String> recordedInputValues,
      Environment env)
      throws InterruptedException {
    return RepoRecordedInput.isAnyValueOutdated(env, directories, recordedInputValues);
  }

  public static RootedPath getRootedPathFromLabel(Label label, Environment env)
      throws InterruptedException, EvalException {
    SkyKey pkgSkyKey = PackageLookupValue.key(label.getPackageIdentifier());
    PackageLookupValue pkgLookupValue = (PackageLookupValue) env.getValue(pkgSkyKey);
    if (pkgLookupValue == null) {
      throw new NeedsSkyframeRestartException();
    }
    if (!pkgLookupValue.packageExists()) {
      String message = pkgLookupValue.getErrorMsg();
      if (pkgLookupValue == PackageLookupValue.NO_BUILD_FILE_VALUE) {
        message = PackageLookupFunction.explainNoBuildFileValue(label.getPackageIdentifier(), env);
      }
      throw Starlark.errorf("Unable to load package for %s: %s", label, message);
    }

    // And now for the file
    Root packageRoot = pkgLookupValue.getRoot();
    return RootedPath.toRootedPath(packageRoot, label.toPathFragment());
  }

  /**
   * Returns the values of the environment variables in the given set as an immutable map while
   * registering them as dependencies.
   */
  @Nullable
  public static ImmutableMap<String, Optional<String>> getEnvVarValues(
      Environment env, Set<String> keys) throws InterruptedException {
    Map<String, Optional<String>> environ = ActionEnvironmentFunction.getEnvironmentView(env, keys);
    if (environ == null) {
      return null;
    }
    Map<String, String> repoEnvOverride = PrecomputedValue.REPO_ENV.get(env);
    if (repoEnvOverride == null) {
      return null;
    }

    // Only depend on --repo_env values that are specified in keys.
    ImmutableMap.Builder<String, Optional<String>> repoEnv = ImmutableMap.builder();
    repoEnv.putAll(environ);
    for (String key : keys) {
      String value = repoEnvOverride.get(key);
      if (value != null) {
        repoEnv.put(key, Optional.of(value));
      }
    }
    return repoEnv.buildKeepingLast();
  }

  /**
   * Whether fetching is done using local operations only.
   *
   * <p>If this is false, Bazel may decide not to re-fetch the repository, for example when the
   * {@code --nofetch} command line option is used.
   */
  protected abstract boolean isLocal(Rule rule);

  /** Wheather the rule declares it inspects the local environment for configure purpose. */
  protected boolean isConfigure(Rule rule) {
    return false;
  }

  protected static RepositoryDirectoryValue.Builder writeFile(
      Path repositoryDirectory, String filename, String contents)
      throws RepositoryFunctionException {
    Path filePath = repositoryDirectory.getRelative(filename);
    try {
      // The repository could have an existing file that's either a regular file (for remote
      // repositories) or a symlink (for local repositories). Either way, we want to remove it and
      // write our own.
      if (filePath.exists(Symlinks.NOFOLLOW)) {
        filePath.delete();
      }
      FileSystemUtils.writeContentAsLatin1(filePath, contents);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    return RepositoryDirectoryValue.builder().setPath(repositoryDirectory);
  }

  protected static RepositoryDirectoryValue.Builder writeBuildFile(
      Path repositoryDirectory, String contents) throws RepositoryFunctionException {
    return writeFile(repositoryDirectory, "BUILD.bazel", contents);
  }

  protected static String getPathAttr(Rule rule) throws RepositoryFunctionException {
    WorkspaceAttributeMapper mapper = WorkspaceAttributeMapper.of(rule);
    try {
      return mapper.get("path", Type.STRING);
    } catch (EvalException e) {
      throw new RepositoryFunctionException(e, Transience.PERSISTENT);
    }
  }

  @VisibleForTesting
  protected static PathFragment getTargetPath(String userDefinedPath, Path workspace) {
    PathFragment pathFragment = PathFragment.create(userDefinedPath);
    return workspace.getRelative(pathFragment).asFragment();
  }

  /**
   * Given a targetDirectory /some/path/to/y that contains files z, w, and v, create the following
   * directory structure:
   *
   * <pre>
   * .external-repository/
   *   x/
   *     WORKSPACE
   *     BUILD -> &lt;build_root&gt;/x.BUILD
   *     z -> /some/path/to/y/z
   *     w -> /some/path/to/y/w
   *     v -> /some/path/to/y/v
   * </pre>
   */
  public static boolean symlinkLocalRepositoryContents(
      Path repositoryDirectory, Path targetDirectory, String userDefinedPath)
      throws RepositoryFunctionException {
    try {
      repositoryDirectory.createDirectoryAndParents();
      for (Path target : targetDirectory.getDirectoryEntries()) {
        Path symlinkPath = repositoryDirectory.getRelative(target.getBaseName());
        createSymbolicLink(symlinkPath, target);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              String.format(
                  "The repository's path is \"%s\" (absolute: \"%s\") "
                      + "but a symlink could not be created for it, because: %s",
                  userDefinedPath, targetDirectory, e.getMessage())),
          Transience.TRANSIENT);
    }

    return true;
  }

  static void createSymbolicLink(Path from, Path to) throws RepositoryFunctionException {
    try {
      // Remove not-symlinks that are already there.
      if (from.exists()) {
        from.delete();
      }
      FileSystemUtils.ensureSymbolicLink(from, to);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              String.format(
                  "Error creating symbolic link from %s to %s: %s", from, to, e.getMessage())),
          Transience.TRANSIENT);
    }
  }

  protected static Path getExternalRepositoryDirectory(BlazeDirectories directories) {
    return directories.getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION);
  }

  /**
   * For files that are under $OUTPUT_BASE/external, add a dependency on the corresponding rule so
   * that if the WORKSPACE file changes, the File/DirectoryStateValue will be re-evaluated.
   *
   * <p>Note that: - We don't add a dependency on the parent directory at the package root boundary,
   * so the only transitive dependencies from files inside the package roots to external files are
   * through symlinks. So the upwards transitive closure of external files is small. - The only way
   * other than external repositories for external source files to get into the skyframe graph in
   * the first place is through symlinks outside the package roots, which we neither want to
   * encourage nor optimize for since it is not common. So the set of external files is small.
   */
  public static void addExternalFilesDependencies(
      RootedPath rootedPath, BlazeDirectories directories, Environment env)
      throws InterruptedException {
    Path externalRepoDir = getExternalRepositoryDirectory(directories);
    PathFragment repositoryPath = rootedPath.asPath().relativeTo(externalRepoDir);
    if (repositoryPath.isEmpty()) {
      // We are the top of the repository path (<outputBase>/external), not in an actual external
      // repository path.
      return;
    }
    RepositoryName repositoryName;
    try {
      repositoryName = RepositoryName.create(repositoryPath.getSegment(0));
    } catch (LabelSyntaxException ignored) {
      // The directory of a repository with an invalid name can never exist, so we don't need to
      // add a dependency on it.
      return;
    }
    env.getValue(RepositoryDirectoryValue.key(repositoryName));
  }

  /** Sets up a mapping of environment variables to use. */
  public void setClientEnvironment(Map<String, String> clientEnvironment) {
    this.clientEnvironment = clientEnvironment;
  }

  /** Returns the RuleDefinition class for this type of repository. */
  public abstract Class<? extends RuleDefinition> getRuleDefinition();
}
