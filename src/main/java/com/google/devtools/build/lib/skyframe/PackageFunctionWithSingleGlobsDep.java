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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.io.FileSymlinkException;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.packages.GlobberUtils;
import com.google.devtools.build.lib.packages.NonSkyframeGlobber;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.skyframe.GlobsValue.GlobRequest;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Computes the {@link PackageValue} which depends on a single GLOBS node.
 *
 * <p>{@link PackageFunctionWithSingleGlobsDep} subclass is created when the globbing strategy is
 * {@link
 * com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy#SINGLE_GLOBS_HYBRID}. All
 * globs defined in the package's {@code BUILD} file are combined into a single GLOBS node.
 *
 * <p>For an overview of the problem space and our approach, see the https://youtu.be/ZrevTeuU-gQ
 * talk from BazelCon 2024 (slides:
 * https://docs.google.com/presentation/d/e/2PACX-1vSjmiGyHDiCDowgc5ar7f7MLAPCzYAAoH1APmnTjqdTpcWv12ysFvgT_aVwj82vLa7JJA8esnp2jtMJ/pub).
 */
final class PackageFunctionWithSingleGlobsDep extends PackageFunction {

  PackageFunctionWithSingleGlobsDep(
      PackageFactory packageFactory,
      CachingPackageLocator pkgLocator,
      AtomicBoolean showLoadingProgress,
      AtomicInteger numPackagesSuccessfullyLoaded,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining,
      @Nullable PackageProgressReceiver packageProgress,
      ActionOnIOExceptionReadingBuildFile actionOnIoExceptionReadingBuildFile,
      ActionOnFilesystemErrorCodeLoadingBzlFile actionOnFilesystemErrorCodeLoadingBzlFile,
      boolean shouldUseRepoDotBazel,
      Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics,
      AtomicReference<Semaphore> cpuBoundSemaphore) {
    super(
        packageFactory,
        pkgLocator,
        showLoadingProgress,
        numPackagesSuccessfullyLoaded,
        bzlLoadFunctionForInlining,
        packageProgress,
        actionOnIoExceptionReadingBuildFile,
        actionOnFilesystemErrorCodeLoadingBzlFile,
        shouldUseRepoDotBazel,
        threadStateReceiverFactoryForMetrics,
        cpuBoundSemaphore);
  }

  private static final class LoadedPackageWithGlobRequests extends LoadedPackage {
    private final ImmutableSet<GlobRequest> globRequests;

    private LoadedPackageWithGlobRequests(
        Package.Builder builder, long loadTimeNanos, ImmutableSet<GlobRequest> globRequests) {
      super(builder, loadTimeNanos);
      this.globRequests = globRequests;
    }
  }

  /**
   * Performs non-Skyframe globbing operations and prepares the {@link GlobRequest}s set for
   * subsequent Skyframe-based globbing.
   */
  private static final class GlobsGlobber implements Globber {
    private final NonSkyframeGlobber nonSkyframeGlobber;
    private final Set<GlobRequest> globRequests = Sets.newConcurrentHashSet();

    private GlobsGlobber(NonSkyframeGlobber nonSkyframeGlobber) {
      this.nonSkyframeGlobber = nonSkyframeGlobber;
    }

    @Override
    public Token runAsync(
        List<String> includes, List<String> excludes, Operation operation, boolean allowEmpty)
        throws BadGlobException {
      for (String pattern : includes) {
        try {
          globRequests.add(GlobRequest.create(pattern, operation));
        } catch (InvalidGlobPatternException e) {
          throw new BadGlobException(e.getMessage());
        }
      }

      NonSkyframeGlobber.Token nonSkyframeGlobToken =
          nonSkyframeGlobber.runAsync(includes, excludes, operation, allowEmpty);
      return new GlobsToken(nonSkyframeGlobToken, operation, allowEmpty);
    }

    @Override
    public List<String> fetchUnsorted(Token token)
        throws BadGlobException, IOException, InterruptedException {
      Set<String> matches = Sets.newHashSet();
      matches.addAll(
          nonSkyframeGlobber.fetchUnsorted(((GlobsToken) token).nonSkyframeGlobberIncludesToken));

      List<String> result = new ArrayList<>(matches);
      if (!((GlobsToken) token).allowEmpty && result.isEmpty()) {
        GlobberUtils.throwBadGlobExceptionAllExcluded(((GlobsToken) token).globberOperation);
      }
      return result;
    }

    @Override
    public void onInterrupt() {
      nonSkyframeGlobber.onInterrupt();
    }

    @Override
    public void onCompletion() {
      nonSkyframeGlobber.onCompletion();
    }

    /**
     * Returns an {@link ImmutableSet} of all package's globs, which will be used to construct
     * {@link GlobsValue.Key} to be requested in Skyframe downstream.
     *
     * <p>An empty {@link ImmutableSet} is returned if there is no glob is defined in the package's
     * BUILD file. Hence, requesting GLOBS in Skyframe is skipped downstream.
     */
    public ImmutableSet<GlobRequest> getGlobRequests() {
      return ImmutableSet.copyOf(globRequests);
    }

    private static class GlobsToken extends Globber.Token {
      private final NonSkyframeGlobber.Token nonSkyframeGlobberIncludesToken;
      private final Globber.Operation globberOperation;
      private final boolean allowEmpty;

      private GlobsToken(
          NonSkyframeGlobber.Token nonSkyframeGlobberIncludesToken,
          Globber.Operation globberOperation,
          boolean allowEmpty) {
        this.nonSkyframeGlobberIncludesToken = nonSkyframeGlobberIncludesToken;
        this.globberOperation = globberOperation;
        this.allowEmpty = allowEmpty;
      }
    }
  }

  @Override
  protected void handleGlobDepsAndPropagateFilesystemExceptions(
      PackageIdentifier packageIdentifier,
      Root packageRoot,
      LoadedPackage loadedPackage,
      Environment env,
      boolean packageWasInError)
      throws InterruptedException, InternalInconsistentFilesystemException, FileSymlinkException {
    ImmutableSet<GlobRequest> globRequests =
        ((LoadedPackageWithGlobRequests) loadedPackage).globRequests;
    if (globRequests.isEmpty()) {
      return;
    }

    GlobsValue.Key globsKey = GlobsValue.key(packageIdentifier, packageRoot, globRequests);
    try {
      env.getValueOrThrow(globsKey, IOException.class, BuildFileNotFoundException.class);
    } catch (InconsistentFilesystemException e) {
      throw new InternalInconsistentFilesystemException(packageIdentifier, e);
    } catch (FileSymlinkException e) {
      // Please note that GlobsFunction or its deps FileFunction throws the first
      // `FileSymlinkException` discovered, which is consistent with how
      // PackageFunctionWithMultipleGlobDeps#handleGlobDepsAndPropagateFilesystemExceptions handles
      // FileSymlinkException caught.
      throw e;
    } catch (IOException | BuildFileNotFoundException e) {
      maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError);
    }
  }

  @Override
  protected GlobsGlobber makeGlobber(
      NonSkyframeGlobber nonSkyframeGlobber,
      PackageIdentifier packageId,
      Root packageRoot,
      Environment env) {
    return new GlobsGlobber(nonSkyframeGlobber);
  }

  @Override
  protected LoadedPackage newLoadedPackage(
      Package.Builder packageBuilder, @Nullable Globber globber, long loadTimeNanos) {
    return new LoadedPackageWithGlobRequests(
        packageBuilder, loadTimeNanos, ((GlobsGlobber) globber).getGlobRequests());
  }
}
