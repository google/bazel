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
//

package com.google.devtools.build.lib.rules.repository;

import static com.google.devtools.build.lib.skyframe.RepositoryMappingFunction.REPOSITORY_OVERRIDES;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.VendorFileValue;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleFormatter;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.repository.ExternalPackageException;
import com.google.devtools.build.lib.repository.ExternalPackageHelper;
import com.google.devtools.build.lib.repository.ExternalRuleNotFoundException;
import com.google.devtools.build.lib.repository.RepositoryFailedEvent;
import com.google.devtools.build.lib.repository.RepositoryFetchProgress;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.NeverUpToDateRepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.NoRepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.AlreadyReportedRepositoryAccessException;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.skyframe.AlreadyReportedException;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * A {@link SkyFunction} that implements delegation to the correct repository fetcher.
 *
 * <p>Each repository in the WORKSPACE file is represented by a {@link SkyValue} that is computed by
 * this function.
 */
public final class RepositoryDelegatorFunction implements SkyFunction {
  public static final String FORCE_FETCH_DISABLED = "";

  public static final Precomputed<String> FORCE_FETCH =
      new Precomputed<>("dependency_for_force_fetching_repository");

  public static final Precomputed<String> FORCE_FETCH_CONFIGURE =
      new Precomputed<>("dependency_for_force_fetching_configure_repositories");

  public static final Precomputed<Optional<RootedPath>> RESOLVED_FILE_INSTEAD_OF_WORKSPACE =
      new Precomputed<>("resolved_file_instead_of_workspace");

  public static final Precomputed<Boolean> IS_VENDOR_COMMAND =
      new Precomputed<>("is_vendor_command");

  public static final Precomputed<Optional<Path>> VENDOR_DIRECTORY =
      new Precomputed<>("vendor_directory");

  public static final Precomputed<Boolean> DISABLE_NATIVE_REPO_RULES =
      new Precomputed<>("disable_native_repo_rules");

  // The marker file version is inject in the rule key digest so the rule key is always different
  // when we decide to update the format.
  private static final int MARKER_FILE_VERSION = 7;

  // Mapping of rule class name to RepositoryFunction.
  private final ImmutableMap<String, RepositoryFunction> handlers;
  // Delegate function to handle Starlark remote repositories
  private final RepositoryFunction starlarkHandler;
  // This is a reference to isFetch in BazelRepositoryModule, which tracks whether the current
  // command is a fetch. Remote repository lookups are only allowed during fetches.
  private final AtomicBoolean isFetch;
  private final BlazeDirectories directories;
  private final ExternalPackageHelper externalPackageHelper;
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;

  public RepositoryDelegatorFunction(
      ImmutableMap<String, RepositoryFunction> handlers,
      @Nullable RepositoryFunction starlarkHandler,
      AtomicBoolean isFetch,
      Supplier<Map<String, String>> clientEnvironmentSupplier,
      BlazeDirectories directories,
      ExternalPackageHelper externalPackageHelper) {
    this.handlers = handlers;
    this.starlarkHandler = starlarkHandler;
    this.isFetch = isFetch;
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
    this.directories = directories;
    this.externalPackageHelper = externalPackageHelper;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, RepositoryFunctionException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    RepositoryName repositoryName = (RepositoryName) skyKey.argument();
    if (!repositoryName.isVisible()) {
      String workspaceDeprecationMsg =
          externalPackageHelper.getWorkspaceDeprecationErrorMessage(
              env,
              starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE),
              repositoryName.isOwnerRepoMainRepo());
      if (env.valuesMissing()) {
        return null;
      }
      return new NoRepositoryDirectoryValue(
          String.format(
              "No repository visible as '@%s' from %s%s",
              repositoryName.getName(),
              repositoryName.getOwnerRepoDisplayString(),
              workspaceDeprecationMsg));
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.REPOSITORY_FETCH, repositoryName.toString())) {
      Path repoRoot =
          RepositoryFunction.getExternalRepositoryDirectory(directories)
              .getRelative(repositoryName.getName());
      Map<RepositoryName, PathFragment> overrides = REPOSITORY_OVERRIDES.get(env);
      if (Preconditions.checkNotNull(overrides).containsKey(repositoryName)) {
        return setupOverride(overrides.get(repositoryName), env, repoRoot, repositoryName);
      }
      if (repositoryName.equals(RepositoryName.BAZEL_TOOLS)) {
        return setupOverride(
            directories.getEmbeddedBinariesRoot().getRelative("embedded_tools").asFragment(),
            env,
            repoRoot,
            repositoryName);
      }

      Rule rule = getRepositoryRule(env, repositoryName, starlarkSemantics);
      if (env.valuesMissing()) {
        return null;
      }
      if (rule == null) {
        return new NoRepositoryDirectoryValue(
            String.format("Repository '%s' is not defined", repositoryName.getCanonicalForm()));
      }

      RepositoryFunction handler = getHandler(rule);
      if (handler == null) {
        // If we refer to a non repository rule then the repository does not exist.
        return new NoRepositoryDirectoryValue(
            String.format("'%s' is not a repository rule", repositoryName.getCanonicalForm()));
      }

      DigestWriter digestWriter =
          new DigestWriter(directories, repositoryName, rule, starlarkSemantics);

      boolean excludeRepoFromVendoring = true;
      if (VENDOR_DIRECTORY.get(env).isPresent()) { // If vendor mode is on
        VendorFileValue vendorFile = (VendorFileValue) env.getValue(VendorFileValue.KEY);
        if (env.valuesMissing()) {
          return null;
        }
        boolean excludeRepoByDefault = isRepoExcludedFromVendoringByDefault(handler, rule);
        if (!excludeRepoByDefault && !vendorFile.ignoredRepos().contains(repositoryName)) {
          RepositoryDirectoryValue repositoryDirectoryValue =
              tryGettingValueUsingVendoredRepo(
                  env, rule, repoRoot, repositoryName, handler, digestWriter, vendorFile);
          if (env.valuesMissing()) {
            return null;
          }
          if (repositoryDirectoryValue != null) {
            return repositoryDirectoryValue;
          }
        }
        excludeRepoFromVendoring =
            excludeRepoByDefault
                || vendorFile.ignoredRepos().contains(repositoryName)
                || vendorFile.pinnedRepos().contains(repositoryName);
      }

      if (shouldUseCachedRepos(env, handler, repoRoot, rule)) {
        // Make sure marker file is up-to-date; correctly describes the current repository state
        var repoState = digestWriter.areRepositoryAndMarkerFileConsistent(handler, env);
        if (repoState == null) {
          return null;
        }
        if (repoState instanceof DigestWriter.RepoDirectoryState.UpToDate(byte[] markerDigest)) {
          return RepositoryDirectoryValue.builder()
              .setPath(repoRoot)
              .setDigest(markerDigest)
              .setExcludeFromVendoring(excludeRepoFromVendoring)
              .build();
        }
      }

      /* At this point: This is a force fetch, a local repository, OR The repository cache is old or
      didn't exist. In any of those cases, we initiate the fetching process UNLESS this is offline
      mode (fetching is disabled) */
      if (isFetch.get()) {
        // Fetching a repository is a long-running operation that can easily be interrupted. If it
        // is and the marker file exists on disk, a new call of this method may treat this
        // repository as valid even though it is in an inconsistent state. Clear the marker file and
        // only recreate it after fetching is done to prevent this scenario.
        DigestWriter.clearMarkerFile(directories, repositoryName);
        RepositoryFunction.FetchResult result =
            fetchRepository(skyKey, repoRoot, env, handler, rule);
        if (result == null) {
          return null;
        }
        // No new Skyframe dependencies must be added between calling the repository implementation
        // and writing the marker file because if they aren't computed, it would cause a Skyframe
        // restart thus calling the possibly very slow (networking, decompression...) fetch()
        // operation again. So we write the marker file here immediately.
        byte[] digest = digestWriter.writeMarkerFile(result.recordedInputValues());
        return result
            .repoBuilder()
            .setDigest(digest)
            .setExcludeFromVendoring(excludeRepoFromVendoring)
            .build();
      }

      if (!repoRoot.exists()) {
        // The repository isn't on the file system, there is nothing we can do.
        throw new RepositoryFunctionException(
            new IOException(
                "to fix, run\n\tbazel fetch //...\nExternal repository "
                    + repositoryName
                    + " not found and fetching repositories is disabled."),
            Transience.TRANSIENT);
      }

      // Try to build with whatever is on the file system and emit a warning.
      env.getListener()
          .handle(
              Event.warn(
                  rule.getLocation(),
                  String.format(
                      "External repository '%s' is not up-to-date and fetching is disabled. To"
                          + " update, run the build without the '--nofetch' command line option.",
                      rule.getName())));

      return RepositoryDirectoryValue.builder()
          .setPath(repoRoot)
          .setFetchingDelayed()
          .setDigest(new Fingerprint().digestAndReset())
          .setExcludeFromVendoring(excludeRepoFromVendoring)
          .build();
    }
  }

  @Nullable
  private RepositoryDirectoryValue tryGettingValueUsingVendoredRepo(
      Environment env,
      Rule rule,
      Path repoRoot,
      RepositoryName repositoryName,
      RepositoryFunction handler,
      DigestWriter digestWriter,
      VendorFileValue vendorFile)
      throws RepositoryFunctionException, InterruptedException {
    Path vendorPath = VENDOR_DIRECTORY.get(env).get();
    Path vendorRepoPath = vendorPath.getRelative(repositoryName.getName());
    if (vendorRepoPath.exists()) {
      Path vendorMarker = vendorPath.getChild(repositoryName.getMarkerFileName());
      if (vendorFile.pinnedRepos().contains(repositoryName)) {
        // pinned repos are used as they are without checking their marker file
        try {
          // delete the marker as it may become out-of-date while it's pinned (old version or
          // manual changes)
          vendorMarker.delete();
        } catch (IOException e) {
          throw new RepositoryFunctionException(e, Transience.TRANSIENT);
        }
        return setupOverride(vendorRepoPath.asFragment(), env, repoRoot, repositoryName);
      }

      DigestWriter.RepoDirectoryState vendoredRepoState =
          digestWriter.areRepositoryAndMarkerFileConsistent(handler, env, vendorMarker);
      if (vendoredRepoState == null) {
        return null;
      }
      // If our repo is up-to-date, or this is an offline build (--nofetch), then the vendored repo
      // is used.
      if (vendoredRepoState instanceof DigestWriter.RepoDirectoryState.UpToDate
          || (!IS_VENDOR_COMMAND.get(env).booleanValue() && !isFetch.get())) {
        if (vendoredRepoState instanceof DigestWriter.RepoDirectoryState.OutOfDate(String reason)) {
          env.getListener()
              .handle(
                  Event.warn(
                      rule.getLocation(),
                      String.format(
                          "Vendored repository '%s' is out-of-date (%s) and fetching is disabled."
                              + " Run build without the '--nofetch' option or run"
                              + " the bazel vendor command to update it",
                          rule.getName(), reason)));
        }
        return setupOverride(vendorRepoPath.asFragment(), env, repoRoot, repositoryName);
      } else if (!IS_VENDOR_COMMAND.get(env).booleanValue()) { // build command & fetch enabled
        // We will continue fetching but warn the user that we are not using the vendored repo
        env.getListener()
            .handle(
                Event.warn(
                    rule.getLocation(),
                    String.format(
                        "Vendored repository '%s' is out-of-date (%s). The up-to-date version will"
                            + " be fetched into the external cache and used. To update the repo"
                            + " in the vendor directory, run the bazel vendor command",
                        rule.getName(),
                        ((DigestWriter.RepoDirectoryState.OutOfDate) vendoredRepoState).reason())));
      }
    } else if (vendorFile.pinnedRepos().contains(repositoryName)) {
      throw new RepositoryFunctionException(
          new IOException(
              "Pinned repository "
                  + repositoryName.getName()
                  + " not found under the vendor directory"),
          Transience.PERSISTENT);
    } else if (!isFetch.get()) { // repo not vendored & fetching is disabled (--nofetch)
      throw new RepositoryFunctionException(
          new IOException(
              "Vendored repository "
                  + repositoryName.getName()
                  + " not found under the vendor directory and fetching is disabled."
                  + " To fix, run the bazel vendor command or build without the '--nofetch'"),
          Transience.TRANSIENT);
    }
    return null;
  }

  @Nullable
  private Rule getRepositoryRule(
      Environment env, RepositoryName repositoryName, StarlarkSemantics starlarkSemantics)
      throws InterruptedException, RepositoryFunctionException {
    if (starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD)) {
      // Tries to get a repository rule instance from Bzlmod generated repos.
      SkyKey key = BzlmodRepoRuleValue.key(repositoryName);
      BzlmodRepoRuleValue value = (BzlmodRepoRuleValue) env.getValue(key);
      if (env.valuesMissing()) {
        return null;
      }
      if (value != BzlmodRepoRuleValue.REPO_RULE_NOT_FOUND_VALUE) {
        return value.getRule();
      }
    }

    if (starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE)) {
      // fallback to look up the repository in the WORKSPACE file.
      try {
        return getRepoRuleFromWorkspace(repositoryName, env);
      } catch (NoSuchRepositoryException e) {
        return null;
      }
    }

    return null;
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  private RepositoryFunction getHandler(Rule rule) {
    RepositoryFunction handler;
    if (rule.getRuleClassObject().isStarlark()) {
      handler = starlarkHandler;
    } else {
      handler = handlers.get(rule.getRuleClass());
    }
    if (handler != null) {
      handler.setClientEnvironment(clientEnvironmentSupplier.get());
    }

    return handler;
  }

  /* Determines whether we should use the cached repositories */
  private boolean shouldUseCachedRepos(
      Environment env, RepositoryFunction handler, Path repoRoot, Rule rule)
      throws InterruptedException {
    boolean forceFetchEnabled = !FORCE_FETCH.get(env).isEmpty();
    boolean forceFetchConfigureEnabled =
        handler.isConfigure(rule) && !FORCE_FETCH_CONFIGURE.get(env).isEmpty();

    /* If fetching is enabled & this is a local repo: do NOT use cache!
     * Local repository are generally fast and do not rely on non-local data, making caching them
     * across server instances impractical. */
    if (isFetch.get() && handler.isLocal(rule)) {
      return false;
    }

    /* For the non-local repositories, do NOT use cache if:
     * 1) Force fetch is enabled (bazel sync, or bazel fetch --force), OR
     * 2) Force fetch configure is enabled (bazel sync --configure), OR
     * 3) Repository directory does not exist */
    if (forceFetchEnabled || forceFetchConfigureEnabled || !repoRoot.exists()) {
      return false;
    }

    return true;
  }

  private boolean isRepoExcludedFromVendoringByDefault(RepositoryFunction handler, Rule rule) {
    return handler.isLocal(rule)
        || handler.isConfigure(rule)
        || RepositoryFunction.isWorkspaceRepo(rule);
  }

  @Nullable
  private RepositoryFunction.FetchResult fetchRepository(
      SkyKey skyKey, Path repoRoot, Environment env, RepositoryFunction handler, Rule rule)
      throws InterruptedException, RepositoryFunctionException {

    handler.setupRepoRootBeforeFetching(repoRoot);

    RepositoryName repoName = (RepositoryName) skyKey.argument();
    env.getListener().post(RepositoryFetchProgress.ongoing(repoName, "starting"));

    RepositoryFunction.FetchResult result;
    try {
      result = handler.fetch(rule, repoRoot, directories, env, skyKey);
    } catch (RepositoryFunctionException e) {
      // Upon an exceptional exit, the fetching of that repository is over as well.
      env.getListener().post(RepositoryFetchProgress.finished(repoName));
      env.getListener().post(new RepositoryFailedEvent(repoName, e.getMessage()));

      if (e.getCause() instanceof AlreadyReportedException) {
        throw e;
      }
      env.getListener()
          .handle(
              Event.error(
                  rule.getLocation(), String.format("fetching %s: %s", rule, e.getMessage())));

      // Rewrap the underlying exception to signal callers not to re-report this error.
      throw new RepositoryFunctionException(
          new AlreadyReportedRepositoryAccessException(e.getCause()),
          e.isTransient() ? Transience.TRANSIENT : Transience.PERSISTENT);
    }

    if (env.valuesMissing()) {
      handler.reportSkyframeRestart(env, repoName);
      return null;
    }
    env.getListener().post(RepositoryFetchProgress.finished(repoName));
    return Preconditions.checkNotNull(result);
  }

  /**
   * Uses a remote repository name to fetch the corresponding Rule describing how to get it. This
   * should be called from {@link SkyFunction#compute} functions, which should return null if this
   * returns null.
   */
  @Nullable
  private Rule getRepoRuleFromWorkspace(RepositoryName repositoryName, Environment env)
      throws InterruptedException, RepositoryFunctionException, NoSuchRepositoryException {
    try {
      return externalPackageHelper.getRuleByName(repositoryName.getName(), env);
    } catch (ExternalRuleNotFoundException e) {
      // This is caught and handled immediately in compute().
      throw new NoSuchRepositoryException();
    } catch (ExternalPackageException e) {
      throw new RepositoryFunctionException(e);
    }
  }

  @Nullable
  private RepositoryDirectoryValue setupOverride(
      PathFragment sourcePath, Environment env, Path repoRoot, RepositoryName repoName)
      throws RepositoryFunctionException, InterruptedException {
    DigestWriter.clearMarkerFile(directories, repoName);
    RepositoryDirectoryValue.Builder directoryValue =
        symlinkRepoRoot(
            directories,
            repoRoot,
            directories.getWorkspace().getRelative(sourcePath),
            repoName.getName(),
            env);
    if (directoryValue == null) {
      return null;
    }
    byte[] digest = new Fingerprint().addPath(sourcePath).digestAndReset();
    return directoryValue.setDigest(digest).build();
  }

  @Nullable
  public static RepositoryDirectoryValue.Builder symlinkRepoRoot(
      BlazeDirectories directories,
      Path source,
      Path destination,
      String userDefinedPath,
      Environment env)
      throws RepositoryFunctionException, InterruptedException {
    if (source.isDirectory(Symlinks.NOFOLLOW)) {
      try {
        source.deleteTree();
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }
    try {
      FileSystemUtils.ensureSymbolicLink(source, destination);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              String.format(
                  "Could not create symlink to repository \"%s\" (absolute path: \"%s\"): %s",
                  userDefinedPath, destination, e.getMessage()),
              e),
          Transience.TRANSIENT);
    }

    // Check that the target directory exists and is a directory.
    // Note that we have to check `destination` and not `source` here, otherwise we'd have a
    // circular dependency between SkyValues.
    RootedPath targetDirRootedPath;
    if (destination.startsWith(directories.getInstallBase())) {
      // The install base only changes with the Bazel binary so it's acceptable not to add its
      // ancestors as Skyframe dependencies.
      targetDirRootedPath =
          RootedPath.toRootedPath(Root.fromPath(destination), PathFragment.EMPTY_FRAGMENT);
    } else {
      targetDirRootedPath =
          RootedPath.toRootedPath(Root.absoluteRoot(destination.getFileSystem()), destination);
    }

    FileValue targetDirValue;
    try {
      targetDirValue =
          (FileValue) env.getValueOrThrow(FileValue.key(targetDirRootedPath), IOException.class);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException("Could not access " + destination + ": " + e.getMessage()),
          Transience.PERSISTENT);
    }
    if (targetDirValue == null) {
      // TODO(bazel-team): If this returns null, we unnecessarily recreate the symlink above on the
      // second execution.
      return null;
    }

    if (!targetDirValue.isDirectory()) {
      throw new RepositoryFunctionException(
          new IOException(
              String.format(
                  "The repository's path is \"%s\" (absolute: \"%s\") "
                      + "but it does not exist or is not a directory.",
                  userDefinedPath, destination)),
          Transience.PERSISTENT);
    }

    // Check that the directory contains a repo boundary file.
    // Note that we need to do this here since we're not creating a repo boundary file ourselves,
    // but entrusting the entire contents of the repo root to this target directory.
    if (!WorkspaceFileHelper.isValidRepoRoot(destination)) {
      throw new RepositoryFunctionException(
          new IOException("No MODULE.bazel, REPO.bazel, or WORKSPACE file found in " + destination),
          Transience.TRANSIENT);
    }
    return RepositoryDirectoryValue.builder().setExcludeFromVendoring(true).setPath(source);
  }

  // Escape a value for the marker file
  @VisibleForTesting
  static String escape(String str) {
    return str == null ? "\\0" : str.replace("\\", "\\\\").replace("\n", "\\n").replace(" ", "\\s");
  }

  // Unescape a value from the marker file
  @Nullable
  @VisibleForTesting
  static String unescape(String str) {
    if (str.equals("\\0")) {
      return null; // \0 == null string
    }
    StringBuilder result = new StringBuilder();
    boolean escaped = false;
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      if (escaped) {
        if (c == 'n') { // n means new line
          result.append("\n");
        } else if (c == 's') { // s means space
          result.append(" ");
        } else { // Any other escaped characters are just un-escaped
          result.append(c);
        }
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }

  private static class DigestWriter {
    // Input value map to force repo invalidation upon an invalid marker file.
    private static final ImmutableMap<RepoRecordedInput, String> PARSE_FAILURE =
        ImmutableMap.of(NeverUpToDateRepoRecordedInput.PARSE_FAILURE, "");

    private final BlazeDirectories directories;
    private final Path markerPath;
    private final String ruleKey;

    DigestWriter(
        BlazeDirectories directories,
        RepositoryName repositoryName,
        Rule rule,
        StarlarkSemantics starlarkSemantics) {
      this.directories = directories;
      ruleKey = computeRuleKey(rule, starlarkSemantics);
      markerPath = getMarkerPath(directories, repositoryName);
    }

    byte[] writeMarkerFile(Map<? extends RepoRecordedInput, String> recordedInputValues)
        throws RepositoryFunctionException {
      StringBuilder builder = new StringBuilder();
      builder.append(ruleKey).append("\n");
      for (Map.Entry<RepoRecordedInput, String> recordedInput :
          new TreeMap<RepoRecordedInput, String>(recordedInputValues).entrySet()) {
        String key = recordedInput.getKey().toString();
        String value = recordedInput.getValue();
        builder.append(escape(key)).append(" ").append(escape(value)).append("\n");
      }
      String content = builder.toString();
      try {
        FileSystemUtils.writeContent(markerPath, UTF_8, content);
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
      return new Fingerprint().addString(content).digestAndReset();
    }

    private sealed interface RepoDirectoryState {
      @SuppressWarnings("ArrayRecordComponent")
      record UpToDate(byte[] markerDigest) implements RepoDirectoryState {}

      record OutOfDate(String reason) implements RepoDirectoryState {}
    }

    RepoDirectoryState areRepositoryAndMarkerFileConsistent(
        RepositoryFunction handler, Environment env)
        throws InterruptedException, RepositoryFunctionException {
      return areRepositoryAndMarkerFileConsistent(handler, env, markerPath);
    }

    /**
     * Checks if the state of the repository in the file system is consistent with the rule in the
     * WORKSPACE file.
     *
     * <p>Returns null if a Skyframe status is needed.
     *
     * <p>We check the repository root for existence here, but we can't depend on the FileValue,
     * because it's possible that we eventually create that directory in which case the FileValue
     * and the state of the file system would be inconsistent.
     */
    @Nullable
    RepoDirectoryState areRepositoryAndMarkerFileConsistent(
        RepositoryFunction handler, Environment env, Path markerPath)
        throws RepositoryFunctionException, InterruptedException {
      if (!markerPath.exists()) {
        return new RepoDirectoryState.OutOfDate("repo hasn't been fetched yet");
      }

      try {
        String content = FileSystemUtils.readContent(markerPath, UTF_8);
        Map<RepoRecordedInput, String> recordedInputValues =
            readMarkerFile(content, Preconditions.checkNotNull(ruleKey));
        Optional<String> outdatedReason =
            handler.isAnyRecordedInputOutdated(directories, recordedInputValues, env);
        if (env.valuesMissing()) {
          return null;
        }
        if (outdatedReason.isPresent()) {
          return new RepoDirectoryState.OutOfDate(outdatedReason.get());
        }
        return new RepoDirectoryState.UpToDate(
            new Fingerprint().addString(content).digestAndReset());
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }

    private static Map<RepoRecordedInput, String> readMarkerFile(
        String content, String expectedRuleKey) {
      Iterable<String> lines = Splitter.on('\n').split(content);

      @Nullable Map<RepoRecordedInput, String> recordedInputValues = null;
      boolean firstLineVerified = false;
      for (String line : lines) {
        if (line.isEmpty()) {
          continue;
        }
        if (!firstLineVerified) {
          if (!line.equals(expectedRuleKey)) {
            // Break early, need to reload anyway. This also detects marker file version changes
            // so that unknown formats are not parsed.
            return ImmutableMap.of(
                new NeverUpToDateRepoRecordedInput(
                    "Bazel version, flags, repo rule definition or attributes changed"),
                "");
          }
          firstLineVerified = true;
          recordedInputValues = new TreeMap<>();
        } else {
          int sChar = line.indexOf(' ');
          if (sChar > 0) {
            RepoRecordedInput input = RepoRecordedInput.parse(unescape(line.substring(0, sChar)));
            if (!input.equals(NeverUpToDateRepoRecordedInput.PARSE_FAILURE)) {
              recordedInputValues.put(input, unescape(line.substring(sChar + 1)));
              continue;
            }
          }
          // On parse failure, just forget everything else and mark the whole input out of date.
          return PARSE_FAILURE;
        }
      }
      if (!firstLineVerified) {
        return PARSE_FAILURE;
      }
      return Preconditions.checkNotNull(recordedInputValues);
    }

    private String computeRuleKey(Rule rule, StarlarkSemantics starlarkSemantics) {
      return new Fingerprint()
          .addBytes(RuleFormatter.serializeRule(rule).build().toByteArray())
          .addInt(MARKER_FILE_VERSION)
          // TODO: Using the hashCode() method for StarlarkSemantics here is suboptimal as
          //   it doesn't include any default values.
          .addInt(starlarkSemantics.hashCode())
          .hexDigestAndReset();
    }

    private static Path getMarkerPath(BlazeDirectories directories, RepositoryName repo) {
      return RepositoryFunction.getExternalRepositoryDirectory(directories)
          .getChild(repo.getMarkerFileName());
    }

    static void clearMarkerFile(BlazeDirectories directories, RepositoryName repo)
        throws RepositoryFunctionException {
      try {
        getMarkerPath(directories, repo).delete();
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }
  }

  /** Marker exception for the case where a repository is not defined. */
  private static final class NoSuchRepositoryException extends Exception {}
}
