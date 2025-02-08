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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.BatchCallback;
import com.google.devtools.build.lib.cmdline.BatchCallback.NullCallback;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPatternResolver;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.io.ProcessPackageDirectoryException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.AbstractRecursivePackageProvider.MissingDepException;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternResolverUtil;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.GraphTraversingHelper;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * PrepareDepsOfPatternFunction ensures the graph loads targets matching the pattern and its
 * transitive dependencies.
 */
public class PrepareDepsOfPatternFunction implements SkyFunction {
  private final AtomicReference<PathPackageLocator> pkgPath;

  public PrepareDepsOfPatternFunction(AtomicReference<PathPackageLocator> pkgPath) {
    this.pkgPath = pkgPath;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws PrepareDepsOfPatternFunctionException, InterruptedException {
    TargetPatternValue.TargetPatternKey patternKey =
        ((TargetPatternValue.TargetPatternKey) key.argument());

    // DepsOfPatternPreparer below expects to be able to ignore the filtering policy from the
    // TargetPatternKey, which should be valid because PrepareDepsOfPatternValue.keys
    // unconditionally creates TargetPatternKeys with the NO_FILTER filtering policy. (Compare
    // with SkyframeTargetPatternEvaluator, which can create TargetPatternKeys with other
    // filtering policies like FILTER_TESTS or FILTER_MANUAL.) This check makes sure that the
    // key's filtering policy is NO_FILTER as expected.
    Preconditions.checkState(
        patternKey.getPolicy().equals(FilteringPolicies.NO_FILTER), patternKey.getPolicy());

    TargetPattern parsedPattern = patternKey.getParsedPattern();

    IgnoredSubdirectoriesValue repositoryIgnoredPrefixes =
        (IgnoredSubdirectoriesValue)
            env.getValue(IgnoredSubdirectoriesValue.key(parsedPattern.getRepository()));
    if (repositoryIgnoredPrefixes == null) {
      return null;
    }
    // This SkyFunction is used to load the universe, so we want both the ignored directories from
    // the global list of exclusions (set with .bazelignore in Bazel and set statically in other
    // binaries) and the excluded directories from the TargetPatternKey itself to be embedded in the
    // SkyKeys created and used by the DepsOfPatternPreparer. The DepsOfPatternPreparer ignores
    // excludedSubdirectories and embeds repositoryIgnoredPatterns in SkyKeys it creates and uses.
    //
    // This consolidation of excluded into ignored means that parsedPattern.eval below does a bit of
    // extra work when parsePattern is a TargetsBelowDirectory and there are excluded directories,
    // since it has to iterate over those exclusions to see if they fully exclude the directory even
    // though TargetPatternKey guarantees that the exclusions will not fully exclude the directory.
    ImmutableSet<PathFragment> excludedPatterns = patternKey.getExcludedSubdirectories();
    IgnoredSubdirectories repositoryIgnoredPatterns =
        repositoryIgnoredPrefixes
            .asIgnoredSubdirectories()
            .union(IgnoredSubdirectories.of(excludedPatterns));

    DepsOfPatternPreparer preparer = new DepsOfPatternPreparer(env, pkgPath.get());

    try {
      parsedPattern.eval(
          preparer,
          () -> repositoryIgnoredPatterns,
          ImmutableSet.of(),
          NullCallback.instance(),
          QueryExceptionMarkerInterface.MarkerRuntimeException.class);
    } catch (TargetParsingException e) {
      throw new PrepareDepsOfPatternFunctionException(e);
    } catch (MissingDepException e) {
      // The DepsOfPatternPreparer constructed above might throw MissingDepException to signal
      // when it has a dependency on a missing Environment value.
      return null;
    } catch (ProcessPackageDirectoryException e) {
      throw new PrepareDepsOfPatternFunctionException(parsedPattern, e);
    } catch (InconsistentFilesystemException e) {
      throw new PrepareDepsOfPatternFunctionException(parsedPattern, e);
    }
    return PrepareDepsOfPatternValue.INSTANCE;
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * PrepareDepsOfPatternFunction#compute}.
   */
  private static final class PrepareDepsOfPatternFunctionException extends SkyFunctionException {
    PrepareDepsOfPatternFunctionException(TargetParsingException e) {
      super(e, Transience.PERSISTENT);
    }

    PrepareDepsOfPatternFunctionException(
        TargetPattern pattern, ProcessPackageDirectoryException e) {
      super(new PrepareDepsOfPatternException(pattern, e), Transience.PERSISTENT);
    }

    PrepareDepsOfPatternFunctionException(
        TargetPattern pattern, InconsistentFilesystemException e) {
      super(new PrepareDepsOfPatternException(pattern, e), Transience.PERSISTENT);
    }
  }

  private static final class PrepareDepsOfPatternException extends Exception
      implements DetailedException {
    private final DetailedExitCode detailedExitCode;

    PrepareDepsOfPatternException(TargetPattern pattern, ProcessPackageDirectoryException e) {
      super(
          "Preparing deps of pattern '"
              + pattern.getOriginalPattern()
              + "' failed: "
              + e.getMessage(),
          e);
      detailedExitCode = e.getDetailedExitCode();
    }

    public PrepareDepsOfPatternException(TargetPattern pattern, InconsistentFilesystemException e) {
      super(
          "Preparing deps of pattern '"
              + pattern.getOriginalPattern()
              + "' failed: "
              + e.getMessage(),
          e);
      detailedExitCode =
          DetailedExitCode.of(
              FailureDetails.FailureDetail.newBuilder()
                  .setMessage(getMessage())
                  .setPackageLoading(
                      FailureDetails.PackageLoading.newBuilder()
                          .setCode(
                              FailureDetails.PackageLoading.Code
                                  .TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR))
                  .build());
    }

    @Override
    public DetailedExitCode getDetailedExitCode() {
      return detailedExitCode;
    }
  }

  /**
   * A {@link TargetPatternResolver} backed by an {@link
   * com.google.devtools.build.skyframe.SkyFunction.Environment} whose methods do not actually
   * return resolved targets, but that ensures the graph loads the matching targets <b>and</b> their
   * transitive dependencies. Its methods may throw {@link MissingDepException} if the package
   * values this depends on haven't been calculated and added to its environment.
   */
  static class DepsOfPatternPreparer extends TargetPatternResolver<Void> {

    // Because PrepareDepsOfPatternFunction's only goal is to ensure the proper Skyframe nodes and
    // edges are in the graph, we don't need to worry about
    // EnvironmentBackedRecursivePackageProvider#encounteredPackageErrors.
    private final EnvironmentBackedRecursivePackageProvider packageProvider;
    private final Environment env;
    private final ImmutableList<Root> pkgRoots;

    DepsOfPatternPreparer(Environment env, PathPackageLocator pkgPath) {
      this.env = env;
      this.packageProvider = new EnvironmentBackedRecursivePackageProvider(env);
      this.pkgRoots = pkgPath.getPathEntries();
    }

    @Override
    public void warn(String msg) {
      env.getListener().handle(Event.warn(msg));
    }

    @Override
    public Void getTargetOrNull(Label label) {
      // Note:
      // This method is used in just one place, TargetPattern.TargetsInPackage#getWildcardConflict.
      // Returning null tells #getWildcardConflict that there is not a target with a name like
      // "all" or "all-targets", which means that TargetPattern.TargetsInPackage will end up
      // calling DepsOfTargetPreparer#getTargetsInPackage.
      // TODO (bazel-team): Consider replacing this with an isTarget method on the interface.
      return null;
    }

    @Override
    public ResolvedTargets<Void> getExplicitTarget(Label label)
        throws TargetParsingException, InterruptedException {
      try {
        Target target = packageProvider.getTarget(env.getListener(), label);
        SkyKey key = TransitiveTraversalValue.key(target.getLabel());
        SkyValue token =
            env.getValueOrThrow(key, NoSuchPackageException.class, NoSuchTargetException.class);
        if (token == null) {
          throw new MissingDepException();
        }
        return ResolvedTargets.empty();
      } catch (NoSuchThingException e) {
        throw new TargetParsingException(e.getMessage(), e, e.getDetailedExitCode());
      }
    }

    @Override
    public Collection<Void> getTargetsInPackage(
        String originalPattern, PackageIdentifier packageIdentifier, boolean rulesOnly)
        throws TargetParsingException, InterruptedException {
      FilteringPolicy policy =
          rulesOnly ? FilteringPolicies.RULES_ONLY : FilteringPolicies.NO_FILTER;
      return getTargetsInPackage(originalPattern, packageIdentifier, policy);
    }

    private Collection<Void> getTargetsInPackage(
        String originalPattern, PackageIdentifier packageIdentifier, FilteringPolicy policy)
        throws TargetParsingException, InterruptedException {
      try {
        Package pkg = packageProvider.getPackage(env.getListener(), packageIdentifier);
        Collection<Target> packageTargets =
            TargetPatternResolverUtil.resolvePackageTargets(pkg, policy);
        ImmutableList.Builder<SkyKey> builder = ImmutableList.builder();
        for (Target target : packageTargets) {
          builder.add(TransitiveTraversalValue.key(target.getLabel()));
        }
        ImmutableList<SkyKey> skyKeys = builder.build();
        if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
            env, skyKeys, NoSuchPackageException.class, NoSuchTargetException.class)) {
          throw new MissingDepException();
        }
        return ImmutableSet.of();
      } catch (NoSuchThingException e) {
        String message =
            TargetPatternResolverUtil.getParsingErrorMessage(
                "package contains errors", originalPattern);
        throw new TargetParsingException(message, e, e.getDetailedExitCode());
      }
    }

    @Override
    public boolean isPackage(PackageIdentifier packageIdentifier)
        throws InterruptedException, InconsistentFilesystemException {
      return packageProvider.isPackage(env.getListener(), packageIdentifier);
    }

    @Override
    public String getTargetKind(Void target) {
      // Note:
      // This method is used in just one place, TargetPattern.TargetsInPackage#getWildcardConflict.
      // Because DepsOfPatternPreparer#getTargetOrNull always returns null, this method is never
      // called.
      throw new UnsupportedOperationException();
    }

    @Override
    public <E extends Exception & QueryExceptionMarkerInterface> void findTargetsBeneathDirectory(
        RepositoryName repository,
        String originalPattern,
        String directory,
        boolean rulesOnly,
        IgnoredSubdirectories repositoryIgnoredSubdirectories,
        ImmutableSet<PathFragment> excludedSubdirectories,
        BatchCallback<Void, E> callback,
        Class<E> exceptionClass)
        throws TargetParsingException, E, InterruptedException {
      PathFragment directoryPathFragment = TargetPatternResolverUtil.getPathFragment(directory);
      Preconditions.checkArgument(excludedSubdirectories.isEmpty(), excludedSubdirectories);
      FilteringPolicy policy =
          rulesOnly ? FilteringPolicies.RULES_ONLY : FilteringPolicies.NO_FILTER;
      List<Root> roots = new ArrayList<>();
      if (repository.isMain()) {
        roots.addAll(pkgRoots);
      } else {
        RepositoryDirectoryValue repositoryValue =
            (RepositoryDirectoryValue) env.getValue(RepositoryDirectoryValue.key(repository));
        if (repositoryValue == null) {
          throw new MissingDepException();
        }

        if (!repositoryValue.repositoryExists()) {
          // This shouldn't be possible; we're given a repository, so we assume that the caller has
          // already checked for its existence.
          throw new IllegalStateException(
              String.format(
                  "No such repository '%s': %s", repository, repositoryValue.getErrorMsg()));
        }
        roots.add(Root.fromPath(repositoryValue.getPath()));
      }

      for (Root root : roots) {
        RootedPath rootedPath = RootedPath.toRootedPath(root, directoryPathFragment);
        if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
            env, getDeps(repository, repositoryIgnoredSubdirectories, policy, rootedPath))) {
          throw new MissingDepException();
        }
      }
    }

    private ImmutableList<SkyKey> getDeps(
        RepositoryName repository,
        IgnoredSubdirectories repositoryIgnoredSubdirectories,
        FilteringPolicy policy,
        RootedPath rootedPath) {
      List<SkyKey> keys = new ArrayList<>();
      keys.add(
          PrepareDepsOfTargetsUnderDirectoryValue.key(
              repository, rootedPath, repositoryIgnoredSubdirectories, policy));
      keys.add(
          CollectPackagesUnderDirectoryValue.key(
              repository, rootedPath, repositoryIgnoredSubdirectories));
      return ImmutableList.copyOf(keys);
    }
  }
}
