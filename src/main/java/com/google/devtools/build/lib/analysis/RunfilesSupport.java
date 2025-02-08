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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.RunfilesTreeAction;
import com.google.devtools.build.lib.analysis.SourceManifestAction.ManifestType;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.analysis.config.RunUnder;
import com.google.devtools.build.lib.analysis.config.RunUnder.LabelRunUnder;
import com.google.devtools.build.lib.analysis.test.TestActionBuilder;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * This class manages the creation of the runfiles symlink farms.
 *
 * <p>For executables that might depend on the existence of files at run-time, we create a symlink
 * farm: a directory which contains symlinks to the right locations for those runfiles.
 *
 * <p>The runfiles symlink farm serves two purposes. The first is to allow programs (and
 * programmers) to refer to files using their workspace-relative paths, regardless of whether the
 * files were source files or generated files, and regardless of which part of the package path they
 * came from. The second purpose is to ensure that all run-time dependencies are explicitly declared
 * in the BUILD files; programs may only use files which the build system knows that they depend on.
 *
 * <p>The symlink farm contains a MANIFEST file which describes its contents. The MANIFEST file
 * lists the names and contents of all of the symlinks in the symlink farm. For efficiency, Blaze's
 * dependency analysis ignores the actual symlinks and just looks at the MANIFEST file. It is an
 * invariant that the MANIFEST file should accurately represent the contents of the symlinks
 * whenever the MANIFEST file is present. build_runfile_links.py preserves this invariant (modulo
 * bugs - currently it has a bug where it may fail to preserve that invariant if it gets
 * interrupted). So the Blaze dependency analysis looks only at the MANIFEST file, rather than at
 * the individual symlinks.
 *
 * <p>We create an Artifact for the MANIFEST file and a RunfilesAction Action to create it. This
 * action does not depend on any other Artifacts.
 */
@Immutable
public final class RunfilesSupport {
  private static final String RUNFILES_DIR_EXT = ".runfiles";
  private static final String INPUT_MANIFEST_EXT = ".runfiles_manifest";
  private static final String OUTPUT_MANIFEST_BASENAME = "MANIFEST";
  private static final String REPO_MAPPING_MANIFEST_EXT = ".repo_mapping";

  /** The implementation of {@link RunfilesTree}. */
  @VisibleForTesting
  public static class RunfilesTreeImpl implements RunfilesTree {

    private static final WeakReference<Map<PathFragment, Artifact>> NOT_YET_COMPUTED =
        new WeakReference<>(null);

    private final PathFragment execPath;
    private final Runfiles runfiles;
    @Nullable private final Artifact repoMappingManifest;

    /**
     * The cached runfiles mapping. Possible values:
     *
     * <ul>
     *   <li>null if caching is not desired
     *   <li>A weak reference pointing to null if the cached value is not available (either {@link
     *       #NOT_YET_COMPUTED} or flushed from RAM)
     *   <li>A weak reference to the cached value
     * </ul>
     *
     * <p>Using weak references is preferable to soft references because {@link
     * com.google.devtools.build.lib.runtime.GcThrashingDetector} may throw a manual OOM before all
     * soft references are collected. See b/322474776.
     */
    @Nullable private volatile WeakReference<Map<PathFragment, Artifact>> cachedMapping;

    private final boolean buildRunfileLinks;
    private final RunfileSymlinksMode runfileSymlinksMode;

    private RunfilesTreeImpl(
        PathFragment execPath,
        Runfiles runfiles,
        @Nullable Artifact repoMappingManifest,
        boolean buildRunfileLinks,
        boolean cacheMapping,
        RunfileSymlinksMode runfileSymlinksMode) {
      this.execPath = execPath;
      this.runfiles = runfiles;
      this.repoMappingManifest = repoMappingManifest;
      this.buildRunfileLinks = buildRunfileLinks;
      this.runfileSymlinksMode = runfileSymlinksMode;
      this.cachedMapping = cacheMapping ? NOT_YET_COMPUTED : null;
    }

    @VisibleForTesting
    public RunfilesTreeImpl(PathFragment execPath, Runfiles runfiles) {
      this(
          execPath,
          runfiles,
          /* repoMappingManifest= */ null,
          /* buildRunfileLinks= */ false,
          /* cacheMapping= */ false,
          RunfileSymlinksMode.EXTERNAL);
    }

    @Override
    public PathFragment getExecPath() {
      return execPath;
    }

    @Override
    public Map<PathFragment, Artifact> getMapping() {
      if (cachedMapping == null) {
        return runfiles.getRunfilesInputs(repoMappingManifest);
      }

      Map<PathFragment, Artifact> result = cachedMapping.get();
      if (result != null) {
        return result;
      }

      synchronized (this) {
        result = cachedMapping.get();
        if (result != null) {
          return result;
        }

        result = runfiles.getRunfilesInputs(repoMappingManifest);
        cachedMapping = new WeakReference<>(result);
        return result;
      }
    }

    @Override
    public NestedSet<Artifact> getArtifacts() {
      return runfiles.getAllArtifacts();
    }

    @Override
    public NestedSet<Artifact> getArtifactsAtCanonicalLocationsForLogging() {
      return runfiles.getArtifacts();
    }

    @Override
    public Iterable<PathFragment> getEmptyFilenamesForLogging() {
      return runfiles.getEmptyFilenames();
    }

    @Override
    public NestedSet<SymlinkEntry> getSymlinksForLogging() {
      return runfiles.getSymlinks();
    }

    @Override
    public NestedSet<SymlinkEntry> getRootSymlinksForLogging() {
      return runfiles.getRootSymlinks();
    }

    @Nullable
    @Override
    public Artifact getRepoMappingManifestForLogging() {
      return repoMappingManifest;
    }

    @Override
    public boolean isLegacyExternalRunfiles() {
      return runfiles.isLegacyExternalRunfiles();
    }

    @Override
    public boolean isMappingCached() {
      return cachedMapping != null;
    }

    @Override
    public void fingerprint(
        ActionKeyContext actionKeyContext, Fingerprint fp, boolean digestAbsolutePaths) {
      runfiles.fingerprint(actionKeyContext, fp, digestAbsolutePaths);
    }

    @Override
    public RunfileSymlinksMode getSymlinksMode() {
      return runfileSymlinksMode;
    }

    @Override
    public boolean isBuildRunfileLinks() {
      return buildRunfileLinks;
    }

    @Override
    public String getWorkspaceName() {
      return runfiles.getPrefix();
    }
  }

  private final RunfilesTreeImpl runfilesTree;

  private final Artifact runfilesInputManifest;
  private final Artifact runfilesManifest;
  private final Artifact runfilesTreeArtifact;
  private final Artifact owningExecutable;
  private final CommandLine args;
  private final ActionEnvironment actionEnvironment;

  // Only cache mappings if there is a chance that more than one action will use it within a single
  // build. This helps reduce peak memory usage, especially when the value of --jobs is high, but
  // avoids the additional overhead of a weak reference when it is not needed.
  private static boolean cacheRunfilesMappings(RuleContext ruleContext) {
    if (!TargetUtils.isTestRule(ruleContext.getTarget())) {
      // Runfiles trees of non-test rules are tools and can thus be used by multiple actions.
      return true;
    }

    // Test runfiles are only used by a single test runner action unless there are multiple runs or
    // shards.
    if (TestActionBuilder.getRunsPerTest(ruleContext) > 1) {
      return true;
    }

    if (TestActionBuilder.getShardCount(ruleContext) > 1) {
      return true;
    }

    return false;
  }

  /**
   * Creates the RunfilesSupport helper with the given executable and runfiles.
   *
   * @param ruleContext the rule context to create the runfiles support for
   * @param executable the executable for whose runfiles this runfiles support is responsible
   * @param runfiles the runfiles
   */
  private static RunfilesSupport create(
      RuleContext ruleContext,
      Artifact executable,
      Runfiles runfiles,
      CommandLine args,
      ActionEnvironment actionEnvironment) {
    checkNotNull(executable);
    RunfileSymlinksMode runfileSymlinksMode =
        ruleContext.getConfiguration().getRunfileSymlinksMode();
    boolean buildRunfileManifests = ruleContext.getConfiguration().buildRunfileManifests();
    boolean buildRunfileLinks = ruleContext.getConfiguration().buildRunfileLinks();

    // Adding run_under target to the runfiles manifest so it would become part
    // of runfiles tree and would be executable everywhere.
    RunUnder runUnder = ruleContext.getConfiguration().getRunUnder();
    if (runUnder instanceof LabelRunUnder && TargetUtils.isTestRule(ruleContext.getRule())) {
      TransitiveInfoCollection runUnderTarget = ruleContext.getRunUnderPrerequisite();
      runfiles =
          new Runfiles.Builder(
                  ruleContext.getWorkspaceName(),
                  ruleContext.getConfiguration().legacyExternalRunfiles())
              .merge(getRunfiles(runUnderTarget, ruleContext.getWorkspaceName()))
              .merge(runfiles)
              .build();
    }
    checkState(!runfiles.isEmpty(), "Empty runfiles");

    Artifact repoMappingManifest =
        createRepoMappingManifestAction(ruleContext, runfiles, executable);

    Artifact runfilesTreeArtifact = declareRunfilesTreeArtifact(ruleContext, executable);

    Artifact runfilesInputManifest;
    Artifact runfilesManifest;
    if (buildRunfileManifests) {
      runfilesInputManifest = createRunfilesInputManifestArtifact(ruleContext, executable);
      runfilesManifest =
          createRunfilesAction(
              ruleContext,
              runfiles,
              runfilesTreeArtifact,
              buildRunfileLinks,
              runfilesInputManifest,
              repoMappingManifest);
    } else {
      runfilesInputManifest = null;
      runfilesManifest = null;
    }

    RunfilesTreeImpl runfilesTree =
        new RunfilesTreeImpl(
            runfilesTreeArtifact.getExecPath(),
            runfiles,
            repoMappingManifest,
            buildRunfileLinks,
            cacheRunfilesMappings(ruleContext),
            runfileSymlinksMode);

    createRunfilesTreeArtifactAction(
        ruleContext, runfilesTreeArtifact, runfilesTree, runfilesManifest, repoMappingManifest);

    return new RunfilesSupport(
        runfilesTree,
        runfilesInputManifest,
        runfilesManifest,
        runfilesTreeArtifact,
        executable,
        args,
        actionEnvironment);
  }

  private RunfilesSupport(
      RunfilesTreeImpl runfilesTree,
      Artifact runfilesInputManifest,
      Artifact runfilesManifest,
      Artifact runfilesTreeArtifact,
      Artifact owningExecutable,
      CommandLine args,
      ActionEnvironment actionEnvironment) {
    this.runfilesTree = runfilesTree;
    this.runfilesInputManifest = runfilesInputManifest;
    this.runfilesManifest = runfilesManifest;
    this.runfilesTreeArtifact = runfilesTreeArtifact;
    this.owningExecutable = owningExecutable;
    this.args = args;
    this.actionEnvironment = actionEnvironment;
  }

  /** Returns the executable owning this RunfilesSupport. */
  public Artifact getExecutable() {
    return owningExecutable;
  }

  public Runfiles getRunfiles() {
    return runfilesTree.runfiles;
  }

  /**
   * Helper method that returns a collection of artifacts that are necessary for the runfiles of the
   * given target. Note that the runfile symlink tree is never built, so this may include artifacts
   * that end up not being used (see {@link Runfiles}).
   *
   * @return the Runfiles object
   */
  private static Runfiles getRunfiles(TransitiveInfoCollection target, String workspaceName) {
    RunfilesProvider runfilesProvider = target.getProvider(RunfilesProvider.class);
    if (runfilesProvider != null) {
      return runfilesProvider.getDefaultRunfiles();
    } else {
      return new Runfiles.Builder(workspaceName)
          .addTransitiveArtifacts(target.getProvider(FileProvider.class).getFilesToBuild())
          .build();
    }
  }

  /**
   * Returns the .runfiles_manifest file outside of the runfiles symlink farm. Returns null if
   * --nobuild_runfile_manifests is in effect.
   *
   * <p>The MANIFEST file represents the contents of all of the symlinks in the symlink farm. For
   * efficiency, Blaze's dependency analysis ignores the actual symlinks and just looks at the
   * MANIFEST file. It is an invariant that the MANIFEST file should accurately represent the
   * contents of the symlinks whenever the MANIFEST file is present.
   */
  @Nullable
  public Artifact getRunfilesInputManifest() {
    return runfilesInputManifest;
  }

  private static Artifact createRunfilesInputManifestArtifact(
      RuleContext context, Artifact owningExecutable) {
    PathFragment relativePath =
        owningExecutable.getOutputDirRelativePath(
            context.getConfiguration().isSiblingRepositoryLayout());
    String basename = relativePath.getBaseName();
    PathFragment inputManifestPath = relativePath.replaceName(basename + INPUT_MANIFEST_EXT);
    return context.getDerivedArtifact(inputManifestPath, context.getBinDirectory());
  }

  /**
   * Returns the MANIFEST file in the runfiles symlink farm if Bazel is run with
   * --build_runfile_links. Returns the .runfiles_manifest file outside of the symlink farm, if
   * Bazel is run with --nobuild_runfile_links. Returns null if --nobuild_runfile_manifests is
   * passed.
   *
   * <p>Beware: In most cases {@link #getRunfilesInputManifest} is the more appropriate function.
   */
  @Nullable
  public Artifact getRunfilesManifest() {
    return runfilesManifest;
  }

  /**
   * Returns the foo.repo_mapping file if Bazel is run with transitive package tracking turned on
   * (see {@code SkyframeExecutor#getForcedSingleSourceRootIfNoExecrootSymlinkCreation}) and any of
   * the transitive packages come from a repository with strict deps (see {@code
   * #collectRepoMappings}). Otherwise, returns null.
   */
  @Nullable
  public Artifact getRepoMappingManifest() {
    return runfilesTree.repoMappingManifest;
  }

  /** Returns the root directory of the runfiles symlink farm; otherwise, returns null. */
  @Nullable
  public Path getRunfilesDirectory() {
    if (runfilesInputManifest == null) {
      return null;
    }
    return FileSystemUtils.replaceExtension(runfilesInputManifest.getPath(), RUNFILES_DIR_EXT);
  }

  /**
   * Returns the files pointed to by the symlinks in the runfiles symlink farm. This method is slow.
   */
  @VisibleForTesting
  public Collection<Artifact> getRunfilesSymlinkTargets() {
    return getRunfilesSymlinks().values();
  }

  /**
   * Returns the names of the symlinks in the runfiles symlink farm as a Set of PathFragments. This
   * method is slow.
   */
  // We should make this VisibleForTesting, but it is still used by TestHelper
  public Set<PathFragment> getRunfilesSymlinkNames() {
    return getRunfilesSymlinks().keySet();
  }

  /**
   * Returns the names of the symlinks in the runfiles symlink farm as a Set of PathFragments. This
   * method is slow.
   */
  @VisibleForTesting
  public Map<PathFragment, Artifact> getRunfilesSymlinks() {
    return runfilesTree.runfiles.asMapWithoutRootSymlinks();
  }

  /**
   * Returns the runfiles tree artifact that depends on getExecutable(), getRunfilesManifest(), and
   * getRunfilesSymlinkTargets(). Anything which needs to actually run the executable should depend
   * on this.
   */
  public Artifact getRunfilesTreeArtifact() {
    return runfilesTreeArtifact;
  }

  private static Artifact declareRunfilesTreeArtifact(
      RuleContext ruleContext, Artifact owningExecutable) {
    PathFragment executableRootRelativePath = owningExecutable.getRootRelativePath();
    PathFragment runfilesRootRelativePath =
        executableRootRelativePath.replaceName(
            executableRootRelativePath.getBaseName() + RUNFILES_DIR_EXT);
    return ruleContext
        .getAnalysisEnvironment()
        .getRunfilesArtifact(runfilesRootRelativePath, owningExecutable.getRoot());
  }

  public static void createRunfilesTreeArtifactAction(
      ActionConstructionContext context,
      Artifact runfilesTreeArtifact,
      RunfilesTree runfilesTree,
      @Nullable Artifact runfilesManifest,
      @Nullable Artifact repoMappingManifest) {
    NestedSetBuilder<Artifact> contentsBuilder = NestedSetBuilder.stableOrder();
    contentsBuilder.addTransitive(runfilesTree.getArtifacts());
    if (runfilesManifest != null) {
      contentsBuilder.add(runfilesManifest);
    }
    if (repoMappingManifest != null) {
      contentsBuilder.add(repoMappingManifest);
    }

    NestedSet<Artifact> contents = contentsBuilder.build();

    RunfilesTreeAction runfilesTreeAction =
        new RunfilesTreeAction(
            context.getActionOwner(),
            runfilesTree,
            contents,
            ImmutableSet.of(runfilesTreeArtifact));
    context.registerAction(runfilesTreeAction);
  }

  /**
   * Creates a runfiles action for all of the specified files, and returns the output artifact (the
   * artifact for the MANIFEST file).
   *
   * <p>The "runfiles" action creates a symlink farm that links all the runfiles (which may come
   * from different places, e.g. different package paths, generated files, etc.) into a single tree,
   * so that programs can access them using the workspace-relative name.
   */
  private static Artifact createRunfilesAction(
      ActionConstructionContext context,
      Runfiles runfiles,
      Artifact runfilesTreeArtifact,
      boolean createSymlinks,
      Artifact inputManifest,
      @Nullable Artifact repoMappingManifest) {
    // Compute the names of the runfiles directory and its MANIFEST file.
    context
        .getAnalysisEnvironment()
        .registerAction(
            new SourceManifestAction(
                ManifestType.SOURCE_SYMLINKS,
                context.getActionOwner(),
                inputManifest,
                runfiles,
                repoMappingManifest,
                context.getConfiguration().remotableSourceManifestActions()));

    if (!createSymlinks) {
      // Just return the manifest if that's all the build calls for.
      return inputManifest;
    }

    PathFragment runfilesDir =
        runfilesTreeArtifact.getOutputDirRelativePath(
            context.getConfiguration().isSiblingRepositoryLayout());
    PathFragment outputManifestPath = runfilesDir.getRelative(OUTPUT_MANIFEST_BASENAME);

    BuildConfigurationValue config = context.getConfiguration();
    Artifact outputManifest =
        context.getDerivedArtifact(outputManifestPath, context.getBinDirectory());
    context
        .getAnalysisEnvironment()
        .registerAction(
            new SymlinkTreeAction(
                context.getActionOwner(),
                config,
                inputManifest,
                runfiles,
                outputManifest,
                repoMappingManifest));
    return outputManifest;
  }

  /** Returns the unmodifiable list of expanded and tokenized 'args' attribute values. */
  public CommandLine getArgs() {
    return args;
  }

  /** Returns the immutable environment from the 'env' and 'env_inherit' attribute values. */
  public ActionEnvironment getActionEnvironment() {
    return actionEnvironment;
  }

  public static void createSymlinkTree(
      RuleContext ruleContext, Artifact runfilesTreeArtifact, Runfiles runfiles) {
    // We always want symlinks to be created because that's the point of a symlink tree.
    boolean buildRunfilesLinks = true;
    RunfilesTreeImpl runfilesTree =
        new RunfilesTreeImpl(
            runfilesTreeArtifact.getExecPath(),
            runfiles,
            null,
            buildRunfilesLinks,
            false,
            ruleContext.getConfiguration().getRunfileSymlinksMode());
    PathFragment rootRelativePath = runfilesTreeArtifact.getRootRelativePath();
    PathFragment manifestPath =
        rootRelativePath.replaceName(rootRelativePath.getBaseName() + ".symlink_tree_manifest");
    Artifact inputManifest =
        ruleContext.getDerivedArtifact(manifestPath, ruleContext.getBinDirectory());
    Artifact runfilesManifest =
        createRunfilesAction(
            ruleContext, runfiles, runfilesTreeArtifact, buildRunfilesLinks, inputManifest, null);
    createRunfilesTreeArtifactAction(
        ruleContext, runfilesTreeArtifact, runfilesTree, runfilesManifest, null);
  }

  /**
   * Creates and returns a {@link RunfilesSupport} object for the given rule and executable. Note
   * that this method calls back into the passed in rule to obtain the runfiles.
   *
   * <p>If the executable is a test, runfiles mappings are cached and re-used between shards. It's a
   * win since when there is a large number of test shards and/or runs per test, the same runfiles
   * tree is needed many times.
   */
  public static RunfilesSupport withExecutable(
      RuleContext ruleContext, Runfiles runfiles, Artifact executable) throws InterruptedException {
    return RunfilesSupport.create(
        ruleContext,
        executable,
        runfiles,
        computeArgs(ruleContext),
        computeActionEnvironment(ruleContext));
  }

  private static CommandLine computeArgs(RuleContext ruleContext) throws InterruptedException {
    if (!ruleContext.getRule().isAttrDefined("args", Types.STRING_LIST)) {
      // Some non-_binary rules create RunfilesSupport instances; it is fine to not have an args
      // attribute here.
      return CommandLine.empty();
    }
    ImmutableList<String> args = ruleContext.getExpander().withDataLocations().tokenized("args");
    return args.isEmpty() ? CommandLine.empty() : CommandLine.of(args);
  }

  private static ActionEnvironment computeActionEnvironment(RuleContext ruleContext)
      throws InterruptedException {
    // Executable Starlark rules can use RunEnvironmentInfo to specify environment variables.
    boolean isNativeRule =
        ruleContext.getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel() == null;
    if (!isNativeRule
        || (!ruleContext.getRule().isAttrDefined("env", Types.STRING_DICT)
            && !ruleContext.getRule().isAttrDefined("env_inherit", Types.STRING_LIST))) {
      return ActionEnvironment.EMPTY;
    }
    TreeMap<String, String> fixedEnv = new TreeMap<>();
    Set<String> inheritedEnv = new LinkedHashSet<>();
    if (ruleContext.isAttrDefined("env", Types.STRING_DICT)) {
      Expander expander = ruleContext.getExpander().withDataLocations();
      for (Map.Entry<String, String> entry :
          ruleContext.attributes().get("env", Types.STRING_DICT).entrySet()) {
        fixedEnv.put(entry.getKey(), expander.expand("env", entry.getValue()));
      }
    }
    if (ruleContext.isAttrDefined("env_inherit", Types.STRING_LIST)) {
      for (String key : ruleContext.attributes().get("env_inherit", Types.STRING_LIST)) {
        if (!fixedEnv.containsKey(key)) {
          inheritedEnv.add(key);
        }
      }
    }
    return ActionEnvironment.create(
        ImmutableMap.copyOf(fixedEnv), ImmutableSet.copyOf(inheritedEnv));
  }

  /** Returns the exec path of the {@code .runfiles} directory for the given executable. */
  public static PathFragment runfilesDirExecPath(Artifact executable) {
    PathFragment executablePath = executable.getExecPath();
    return executablePath.replaceName(executablePath.getBaseName() + RUNFILES_DIR_EXT);
  }

  /**
   * Returns the exec path of the corresponding {@code .runfiles_manifest} file for the given {@code
   * .runfiles} directory.
   *
   * <p>The input manifest is produced by {@link SourceManifestAction} and is an input to {@link
   * SymlinkTreeAction}.
   */
  public static PathFragment inputManifestExecPath(PathFragment runfilesDirExecPath) {
    return FileSystemUtils.replaceExtension(runfilesDirExecPath, INPUT_MANIFEST_EXT);
  }

  /**
   * Returns the exec path of the corresponding {@code MANIFEST} file for the given {@code
   * .runfiles} directory.
   *
   * <p>The output manifest is a symlink to the {@linkplain #inputManifestExecPath input manifest}.
   * It is located in the {@code .runfiles} directory and is the output of {@link
   * SymlinkTreeAction}.
   */
  public static PathFragment outputManifestExecPath(PathFragment runfilesDirExecPath) {
    return runfilesDirExecPath.getRelative(OUTPUT_MANIFEST_BASENAME);
  }

  @Nullable
  private static Artifact createRepoMappingManifestAction(
      RuleContext ruleContext, Runfiles runfiles, Artifact owningExecutable) {
    if (ruleContext.getTransitivePackagesForRunfileRepoMappingManifest() == null) {
      // If transitive packages are not tracked for repo mapping manifest, we don't need the action.
      return null;
    }

    PathFragment executablePath =
        (owningExecutable != null)
            ? owningExecutable.getOutputDirRelativePath(
                ruleContext.getConfiguration().isSiblingRepositoryLayout())
            : ruleContext.getPackageDirectory().getRelative(ruleContext.getLabel().getName());
    Artifact repoMappingManifest =
        ruleContext.getDerivedArtifact(
            executablePath.replaceName(executablePath.getBaseName() + REPO_MAPPING_MANIFEST_EXT),
            ruleContext.getBinDirectory());
    ruleContext
        .getAnalysisEnvironment()
        .registerAction(
            new RepoMappingManifestAction(
                ruleContext.getActionOwner(),
                repoMappingManifest,
                ruleContext.getTransitivePackagesForRunfileRepoMappingManifest(),
                runfiles.getArtifacts(),
                runfiles.getSymlinks(),
                runfiles.getRootSymlinks(),
                ruleContext.getWorkspaceName()));
    return repoMappingManifest;
  }

  public RunfilesTree getRunfilesTree() {
    return runfilesTree;
  }
}
