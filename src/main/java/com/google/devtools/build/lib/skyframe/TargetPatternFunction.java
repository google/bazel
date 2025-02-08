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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.concurrent.MultisetSemaphore;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.io.ProcessPackageDirectoryException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.AbstractRecursivePackageProvider.MissingDepException;
import com.google.devtools.build.lib.pkgcache.ParsingFailedEvent;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * TargetPatternFunction translates a target pattern (eg, "foo/...") into a set of resolved Targets.
 */
public class TargetPatternFunction implements SkyFunction {

  public TargetPatternFunction() {}

  @Override
  @Nullable
  public SkyValue compute(SkyKey key, Environment env)
      throws TargetPatternFunctionException, InterruptedException {
    TargetPatternValue.TargetPatternKey patternKey =
        ((TargetPatternValue.TargetPatternKey) key.argument());
    TargetPattern parsedPattern = patternKey.getParsedPattern();

    IgnoredSubdirectoriesValue ignoredSubdirectories =
        (IgnoredSubdirectoriesValue)
            env.getValue(IgnoredSubdirectoriesValue.key(parsedPattern.getRepository()));
    if (ignoredSubdirectories == null) {
      return null;
    }

    ResolvedTargets<Target> resolvedTargets;
    EnvironmentBackedRecursivePackageProvider provider =
        new EnvironmentBackedRecursivePackageProvider(env);
    try {
      RecursivePackageProviderBackedTargetPatternResolver resolver =
          new RecursivePackageProviderBackedTargetPatternResolver(
              provider,
              env.getListener(),
              patternKey.getPolicy(),
              MultisetSemaphore.unbounded(),
              /* maxConcurrentGetTargetsTasks= */ Optional.empty(),
              SimplePackageIdentifierBatchingCallback::new);
      ImmutableSet<PathFragment> excludedSubdirectories = patternKey.getExcludedSubdirectories();
      ResolvedTargets.Builder<Target> resolvedTargetsBuilder = ResolvedTargets.builder();
      SafeBatchCallback<Target> callback =
          partialResult -> {
            for (Target target : partialResult) {
              // TODO(b/156899726): This will go away as soon as we remove implicit outputs from
              //  cc_library completely. The only downside to doing this is that implicit outputs
              //  won't be listed when doing somepackage:* for the handful of cases still on the
              //  allowlist. This is only a Google-internal problem and the scale of it is
              //  acceptable in the short term while cleaning up the allowlist.
              if (target instanceof OutputFile outputFile
                  && outputFile.getGeneratingRule().getRuleClass().equals("cc_library")) {
                continue;
              }
              resolvedTargetsBuilder.add(target);
            }
          };
      try {
        parsedPattern.eval(
            resolver,
            () -> ignoredSubdirectories.asIgnoredSubdirectories(),
            excludedSubdirectories,
            callback,
            QueryExceptionMarkerInterface.MarkerRuntimeException.class);
      } catch (ProcessPackageDirectoryException e) {
        throw new TargetPatternFunctionException(e);
      } catch (InconsistentFilesystemException e) {
        throw new TargetPatternFunctionException(e);
      }
      if (provider.encounteredPackageErrors()) {
        resolvedTargetsBuilder.setError();
      }
      resolvedTargets = resolvedTargetsBuilder.build();
    } catch (TargetParsingException e) {
      env.getListener().post(new ParsingFailedEvent(patternKey.getPattern(), e.getMessage()));
      throw new TargetPatternFunctionException(e);
    } catch (MissingDepException e) {
      // If there is at least one missing dependency, we should eagerly throw any exception we might
      // have encountered: if we are in error bubbling, this could be our last chance.
      maybeThrowEncounteredException(parsedPattern, provider);
      // The EnvironmentBackedRecursivePackageProvider constructed above might throw
      // MissingDepException to signal when it has a dependency on a missing Environment value.
      // Note that MissingDepException extends RuntimeException because the methods called
      // on EnvironmentBackedRecursivePackageProvider all belong to an interface shared with other
      // implementations that are unconcerned with MissingDepExceptions.
      return null;
    } catch (InterruptedException e) {
      if (env.inErrorBubbling()) {
        maybeThrowEncounteredException(parsedPattern, provider);
      }
      throw e;
    }
    if (env.inErrorBubbling()) {
      maybeThrowEncounteredException(parsedPattern, provider);
    }
    Preconditions.checkNotNull(resolvedTargets, key);
    ResolvedTargets.Builder<Label> resolvedLabelsBuilder = ResolvedTargets.builder();
    if (resolvedTargets.hasError()) {
      resolvedLabelsBuilder.setError();
    }
    for (Target target : resolvedTargets.getTargets()) {
      resolvedLabelsBuilder.add(target.getLabel());
    }
    for (Target target : resolvedTargets.getFilteredTargets()) {
      resolvedLabelsBuilder.remove(target.getLabel());
    }
    return new TargetPatternValue(resolvedLabelsBuilder.build());
  }

  private static void maybeThrowEncounteredException(
      TargetPattern pattern, EnvironmentBackedRecursivePackageProvider provider)
      throws TargetPatternFunctionException {
    NoSuchPackageException e = provider.maybeGetNoSuchPackageException();
    if (e != null) {
      throw new TargetPatternFunctionException(
          new TargetParsingException(
              "Error evaluating '" + pattern.getOriginalPattern() + "': " + e.getMessage(),
              e,
              FailureDetails.TargetPatterns.Code.PACKAGE_NOT_FOUND));
    }
  }

  private static final class TargetPatternFunctionException extends SkyFunctionException {
    private final boolean isCatastrophic;

    TargetPatternFunctionException(TargetParsingException e) {
      super(e, Transience.PERSISTENT);
      this.isCatastrophic = false;
    }

    TargetPatternFunctionException(InconsistentFilesystemException e) {
      super(new TargetParsingException(e), Transience.PERSISTENT);
      this.isCatastrophic = true;
    }

    TargetPatternFunctionException(ProcessPackageDirectoryException e) {
      super(
          new TargetParsingException(e.getMessage(), e, e.getDetailedExitCode()),
          Transience.PERSISTENT);
      this.isCatastrophic = true;
    }

    @Override
    public boolean isCatastrophic() {
      return isCatastrophic;
    }
  }
}
