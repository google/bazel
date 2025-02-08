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
package com.google.devtools.build.lib.buildtool;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AspectCollection;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.ProviderCollection;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.TopLevelArtifactHelper;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.configuredtargets.OutputFileConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.io.OutErr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

/**
 * Handles --show_result and --experimental_show_artifacts.
 */
class BuildResultPrinter {
  private final CommandEnvironment env;

  BuildResultPrinter(CommandEnvironment env) {
    this.env = env;
  }

  /**
   * Shows the result of the build. Information includes the list of up-to-date and failed targets
   * and list of output artifacts for successful targets
   *
   * <p>This corresponds to the --show_result flag.
   */
  void showBuildResult(
      BuildRequest request,
      BuildResult result,
      Collection<ConfiguredTarget> configuredTargets,
      Collection<ConfiguredTarget> configuredTargetsToSkip,
      ImmutableMap<AspectKey, ConfiguredAspect> aspects) {
    // NOTE: be careful what you print!  We don't want to create a consistency
    // problem where the summary message and the exit code disagree.  The logic
    // here is already complex.
    boolean ok =
        outputTargets(request, result, configuredTargets, configuredTargetsToSkip, aspects);
    if (!ok && !request.getOptions(ExecutionOptions.class).verboseFailures) {
      request
          .getOutErr()
          .printErr("Use --verbose_failures to see the command lines of failed build steps.\n");
    }
  }

  /**
   * Outputs the targets, omitting values with {@code (nothing to build)} when it allows staying
   * under the --show_result limit.
   *
   * <p>This method exits early if there are too many results.
   *
   * @return {@code true} if no errors were detected among the results inspected, this can be a
   *     false positive on early exit.
   */
  private boolean outputTargets(
      BuildRequest request,
      BuildResult result,
      Collection<ConfiguredTarget> configuredTargets,
      Collection<ConfiguredTarget> configuredTargetsToSkip,
      ImmutableMap<AspectKey, ConfiguredAspect> aspects) {
    BlazeRuntime runtime = env.getRuntime();
    String productName = runtime.getProductName();
    PathPrettyPrinter prettyPrinter =
        new PathPrettyPrinter(
            request.getBuildOptions().getSymlinkPrefix(productName),
            result.getConvenienceSymlinks());
    OutErr outErr = request.getOutErr();

    // Filter and split aspects to display.
    ImmutableSet<String> aspectsToIgnore =
        ImmutableSet.copyOf(request.getBuildOptions().hideAspectResults);
    PartitionedAspectKeys partitionedAspectKeys =
        partitionAspectKeys(
            request.useValidationAspect(),
            aspects.keySet().stream()
                .filter(k -> !aspectsToIgnore.contains(k.getAspectClass().getName()))
                .collect(toImmutableSet()));

    Collection<ConfiguredTarget> targetsToPrint = filterTargetsToPrint(configuredTargets);
    TopLevelArtifactContext context = request.getTopLevelArtifactContext();

    // `essentialBudget` tracks the number of non-empty results that can be printed.
    int essentialBudget = request.getBuildOptions().maxResultTargets;

    // Splits the targets we care about into three buckets. Targets are only considered successful
    // if they and their validation aspects succeeded.
    var skipped = new ArrayList<ConfiguredTarget>();
    var succeeded = new ArrayList<ConfiguredTarget>();
    var artifactsToPrintPerTarget = new ArrayList<ArrayList<Artifact>>();
    var failed = new ArrayList<ConfiguredTarget>();
    essentialBudget =
        splitConfiguredTargetsByResultReturnRemaining(
            targetsToPrint,
            result,
            context,
            configuredTargetsToSkip,
            partitionedAspectKeys.validationAspects,
            skipped,
            succeeded,
            artifactsToPrintPerTarget,
            failed,
            essentialBudget);
    if (essentialBudget < 0) {
      return failed.isEmpty();
    }

    // Splits the aspects we care about into two buckets.
    var successfulAspects = new ArrayList<AspectKey>();
    var failedAspects = new ArrayList<AspectKey>();
    var artifactsToPrintPerAspect = new ArrayList<ArrayList<Artifact>>(successfulAspects.size());
    essentialBudget =
        splitAspectsByResultReturnRemaining(
            partitionedAspectKeys.aspectsToPrint,
            aspects,
            context,
            result.getSuccessfulAspects(),
            successfulAspects,
            artifactsToPrintPerAspect,
            failedAspects,
            essentialBudget);
    if (essentialBudget < 0) {
      return failed.isEmpty() && failedAspects.isEmpty();
    }

    // Omits "nothing to build" values if it enables staying under --show_result.
    boolean omitNothingToBuild =
        (targetsToPrint.size() + partitionedAspectKeys.aspectsToPrint.size())
            > request.getBuildOptions().maxResultTargets;

    outputConfiguredTargets(
        outErr,
        prettyPrinter,
        succeeded,
        artifactsToPrintPerTarget,
        failed,
        skipped,
        omitNothingToBuild);
    outputAspects(
        outErr,
        prettyPrinter,
        successfulAspects,
        artifactsToPrintPerAspect,
        failedAspects,
        omitNothingToBuild);

    return failed.isEmpty() && failedAspects.isEmpty();
  }

  private static int splitConfiguredTargetsByResultReturnRemaining(
      Collection<ConfiguredTarget> configuredTargets,
      BuildResult result,
      TopLevelArtifactContext context,
      Collection<ConfiguredTarget> configuredTargetsToSkip,
      ImmutableList<AspectKey> validationAspects,
      ArrayList<ConfiguredTarget> skipped,
      ArrayList<ConfiguredTarget> succeeded,
      ArrayList<ArrayList<Artifact>> artifactsToPrintPerTarget,
      ArrayList<ConfiguredTarget> failed,
      int essentialBudget) {
    ImmutableSet<ConfiguredTargetKey> validationFailures =
        validationAspects.stream()
            .filter(k -> !result.getSuccessfulAspects().contains(k))
            .map(AspectKey::getBaseConfiguredTargetKey)
            .collect(toImmutableSet());
    Collection<ConfiguredTarget> successfulTargets = result.getSuccessfulTargets();
    for (ConfiguredTarget target : configuredTargets) {
      if (configuredTargetsToSkip.contains(target)) {
        skipped.add(target);
        if (--essentialBudget < 0) {
          return essentialBudget;
        }
      } else if (successfulTargets.contains(target)
          && !validationFailures.contains(ConfiguredTargetKey.fromConfiguredTarget(target))) {
        succeeded.add(target);
        ArrayList<Artifact> artifactsToPrint = getArtifactsToPrint(target, context);
        artifactsToPrintPerTarget.add(artifactsToPrint);
        if (!artifactsToPrint.isEmpty()) {
          if (--essentialBudget < 0) {
            return essentialBudget;
          }
        }
      } else {
        failed.add(target);
        if (--essentialBudget < 0) {
          return essentialBudget;
        }
      }
    }
    return essentialBudget;
  }

  private static ArrayList<Artifact> getArtifactsToPrint(
      ProviderCollection target, TopLevelArtifactContext context) {
    var artifacts = new ArrayList<Artifact>();
    // For up-to-date targets report generated artifacts, but only if they have associated action
    // and not runfiles trees.
    for (Artifact artifact :
        TopLevelArtifactHelper.getAllArtifactsToBuild(target, context)
            .getImportantArtifacts()
            .toList()) {
      if (TopLevelArtifactHelper.shouldDisplay(artifact)) {
        artifacts.add(artifact);
      }
    }
    return artifacts;
  }

  private static int splitAspectsByResultReturnRemaining(
      Collection<AspectKey> aspectsToPrint,
      ImmutableMap<AspectKey, ConfiguredAspect> aspects,
      TopLevelArtifactContext context,
      ImmutableSet<AspectKey> successfulAspects,
      ArrayList<AspectKey> succeeded,
      ArrayList<ArrayList<Artifact>> artifactsToPrintPerAspect,
      ArrayList<AspectKey> failed,
      int essentialBudget) {
    for (AspectKey aspect : aspectsToPrint) {
      if (successfulAspects.contains(aspect)) {
        succeeded.add(aspect);
        ArrayList<Artifact> artifactsToPrint = getArtifactsToPrint(aspects.get(aspect), context);
        artifactsToPrintPerAspect.add(artifactsToPrint);
        if (!artifactsToPrint.isEmpty()) {
          if (--essentialBudget < 0) {
            return essentialBudget;
          }
        }
      } else {
        failed.add(aspect);
        if (--essentialBudget < 0) {
          return essentialBudget;
        }
      }
    }
    return essentialBudget;
  }

  private static void outputConfiguredTargets(
      OutErr outErr,
      PathPrettyPrinter prettyPrinter,
      ArrayList<ConfiguredTarget> succeeded,
      ArrayList<ArrayList<Artifact>> artifactsToPrintPerTarget,
      ArrayList<ConfiguredTarget> failed,
      ArrayList<ConfiguredTarget> skipped,
      boolean omitNothingToBuild) {
    for (ConfiguredTarget target : skipped) {
      outErr.printErr("Target " + target.getOriginalLabel() + " was skipped\n");
    }
    for (int i = 0; i < succeeded.size(); ++i) {
      ConfiguredTarget target = succeeded.get(i);
      Label label = target.getLabel();
      ArrayList<Artifact> artifacts = artifactsToPrintPerTarget.get(i);
      if (artifacts.isEmpty()) {
        if (!omitNothingToBuild) {
          outErr.printErr("Target " + label + " up-to-date (nothing to build)\n");
        }
        continue;
      }
      outErr.printErr("Target " + label + " up-to-date:\n");
      for (Artifact artifact : artifacts) {
        outErr.printErrLn(formatArtifactForShowResults(prettyPrinter, artifact));
      }
    }
    for (ConfiguredTarget target : failed) {
      outErr.printErr("Target " + target.getLabel() + " failed to build\n");

      // For failed compilation, it is still useful to examine temp artifacts, (ie, preprocessed and
      // assembler files).
      OutputGroupInfo topLevelProvider = OutputGroupInfo.get(target);
      if (topLevelProvider != null) {
        for (Artifact temp : topLevelProvider.getOutputGroup(OutputGroupInfo.TEMP_FILES).toList()) {
          if (temp.getPath().exists()) {
            outErr.printErrLn(
                "  See temp at " + prettyPrinter.getPrettyPath(temp.getPath().asFragment()));
          }
        }
      }
    }
  }

  private static void outputAspects(
      OutErr outErr,
      PathPrettyPrinter prettyPrinter,
      ArrayList<AspectKey> succeeded,
      ArrayList<ArrayList<Artifact>> artifactsToPrintPerAspect,
      ArrayList<AspectKey> failed,
      boolean omitNothingToBuild) {
    for (int i = 0; i < succeeded.size(); ++i) {
      AspectKey aspect = succeeded.get(i);
      Label label = aspect.getLabel();
      String aspectName = aspect.getAspectClass().getName();
      ArrayList<Artifact> artifacts = artifactsToPrintPerAspect.get(i);
      if (artifacts.isEmpty()) {
        if (!omitNothingToBuild) {
          outErr.printErr(
              "Aspect " + aspectName + " of " + label + " up-to-date (nothing to build)\n");
        }
        continue;
      }
      outErr.printErr("Aspect " + aspectName + " of " + label + " up-to-date:\n");
      for (Artifact artifact : artifacts) {
        outErr.printErrLn(formatArtifactForShowResults(prettyPrinter, artifact));
      }
    }
    for (AspectKey aspect : failed) {
      Label label = aspect.getLabel();
      String aspectName = aspect.getAspectClass().getName();
      outErr.printErr("Aspect " + aspectName + " of " + label + " failed to build\n");
    }
  }

  private static String formatArtifactForShowResults(
      PathPrettyPrinter prettyPrinter, Artifact artifact) {
    return "  " + prettyPrinter.getPrettyPath(artifact.getPath().asFragment());
  }

  /**
   * Returns a list of configured targets that should participate in printing.
   *
   * <p>Hidden rules and other inserted targets are ignored.
   */
  private Collection<ConfiguredTarget> filterTargetsToPrint(
      Collection<ConfiguredTarget> configuredTargets) {
    ImmutableList.Builder<ConfiguredTarget> result = ImmutableList.builder();
    for (ConfiguredTarget configuredTarget : configuredTargets) {
      if (!TopLevelArtifactHelper.shouldConsiderForDisplay(configuredTarget)) {
        continue;
      }
      if (configuredTarget instanceof OutputFileConfiguredTarget) {
        // Suppress display of generated files (because they appear underneath
        // their generating rule), EXCEPT those ones which are not part of the
        // filesToBuild of their generating rule (e.g. .par, _deploy.jar
        // files), OR when a user explicitly requests an output file but not
        // its rule.
        TransitiveInfoCollection generatingRule =
            ((OutputFileConfiguredTarget) configuredTarget).getGeneratingRule();
        if (generatingRule
                .getProvider(FileProvider.class)
                .getFilesToBuild()
                .toSet()
                .containsAll(
                    configuredTarget.getProvider(FileProvider.class).getFilesToBuild().toList())
            && configuredTargets.contains(generatingRule)) {
          continue;
        }
      }

      result.add(configuredTarget);
    }
    return result.build();
  }

  /** Splits aspects based on whether they are validation aspects. */
  private static PartitionedAspectKeys partitionAspectKeys(
      boolean useValidationAspects, ImmutableSet<AspectKey> keys) {
    if (!useValidationAspects) {
      return new PartitionedAspectKeys(keys, ImmutableList.of());
    }

    var aspectsToPrintBuilder = ImmutableSet.<AspectKey>builder();
    var validationAspectsBuilder = ImmutableList.<AspectKey>builder();
    for (AspectKey key : keys) {
      if (Objects.equals(key.getAspectClass().getName(), AspectCollection.VALIDATION_ASPECT_NAME)) {
        validationAspectsBuilder.add(key);
      } else {
        aspectsToPrintBuilder.add(key);
      }
    }
    return new PartitionedAspectKeys(
        aspectsToPrintBuilder.build(), validationAspectsBuilder.build());
  }

  private static class PartitionedAspectKeys {
    private final ImmutableSet<AspectKey> aspectsToPrint;

    private final ImmutableList<AspectKey> validationAspects;

    private PartitionedAspectKeys(
        ImmutableSet<AspectKey> aspectsToPrint, ImmutableList<AspectKey> validationAspects) {
      this.aspectsToPrint = aspectsToPrint;
      this.validationAspects = validationAspects;
    }
  }
}
