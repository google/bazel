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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.runtime.ConfigFlagDefinitions;
import com.google.devtools.build.lib.server.FailureDetails.BuildConfiguration.Code;
import com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue;
import com.google.devtools.build.lib.skyframe.ProjectValue;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.config.FlagSetValue;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Container for reading project data.
 *
 * <p>A "project" is a set of related packages that support a common piece of software. For example,
 * "bazel" is a project that includes packages {@code src/main/java/com/google/devtools/build/lib},
 * {@code src/main/java/com/google/devtools/build/lib/analysis}, {@code src/test/cpp}, and more.
 *
 * <p>"Project data" is any useful information that might be associated with a project. Possible
 * consumers include <a
 * href="https://github.com/bazelbuild/bazel/commit/693215317a6732085731809266f63ff0e7fc31a5">
 * Skyfocus</a>> and project-sanctioned build flags (i.e. "these are the correct flags to use with
 * this project").
 *
 * <p>Projects are defined in .scl files that are checked into source control with BUILD files and
 * code. scl stands for "Starlark configuration language". This is a limited subset of Starlark
 * intended to model generic configuration without requiring Bazel to parse it (similar to JSON).
 *
 * <p>This is not the same as {@link com.google.devtools.build.lib.runtime.ProjectFile}. That's an
 * older implementation of the same idea that was built before .scl and .bzl existed. The code here
 * is a rejuvenation of these ideas with more modern APIs.
 */
// TODO: b/324127050 - Make the co-existence of this and ProjectFile less confusing. ProjectFile is
//   an outdated API that should be removed.
public final class Project {
  private Project() {}

  /**
   * Returns the canonical project file for a set of targets, or null if the targets have no
   * canonical project file.
   *
   * <p>"Canonical" means the single project file all targets resolve to after alias project files
   * resolve: see {@link ProjectValue#actualProjectFile}.
   *
   * <p>If a target matches multiple project files (like {@code a/PROJECT.scl} and {@code
   * a/b/PROJECT.scl}), only the innermost is considered.
   *
   * @param targets targets to resolve project files for
   * @param skyframeExecutor support for SkyFunctions that look up project files
   * @param eventHandler event handler
   * @throws ProjectParseException on any of the following:
   *     <ol>
   *       <li>Some targets resolve to project files and some don't (so not every target is part of
   *           a project)
   *       <li>Some targets resolve to different project files after alias resolution. Alias
   *           resolution means that if a project file sets {@code project = { "actual":
   *           "//other:PROJECT.scl"}}, it's replaced by the file it references.
   *     </ol>
   */
  // TODO: b/324127375 - Support hierarchical project files: [foo/project.scl, foo/bar/project.scl].
  @Nullable
  @VisibleForTesting
  public static Label getProjectFile(
      Collection<Label> targets,
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler eventHandler)
      throws ProjectResolutionException {
    // Map targets to their innermost matching project file. Omits targets with no project files.
    ImmutableMap<Label, Label> targetsToProjectFiles =
        // findProjectFiles returns all project files up a target's path (and omits targets with
        // no project files). We just use the first entry, which is the innermost file. For
        // example, given [a/b/PROJECT.scl, a/PROJECT.scl], we just use a/b/PROJECT.scl.
        findProjectFiles(targets, skyframeExecutor, eventHandler).asMap().entrySet().stream()
            .collect(
                toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().iterator().next()));

    if (targetsToProjectFiles.isEmpty()) {
      // None of the targets have project files.
      return null;
    }
    Set<Label> targetsWithNoProjectFiles =
        Sets.difference(ImmutableSet.copyOf(targets), targetsToProjectFiles.keySet());

    // Since project files can be aliases to other files, we need to parse them to potentially remap
    // to their references. Also remember the targets that resolved to that project file for clean
    // error reporting.
    LinkedListMultimap<ProjectValue.Key, Label> projectFileKeysToTargets =
        Multimaps.invertFrom(
            Multimaps.forMap(
                targetsToProjectFiles.entrySet().stream()
                    .collect(
                        toImmutableMap(
                            Map.Entry::getKey, entry -> new ProjectValue.Key(entry.getValue())))),
            LinkedListMultimap.create());

    // Load project file content from Skyframe.
    EvaluationResult<SkyValue> evalResult =
        skyframeExecutor.evaluateSkyKeys(
            eventHandler, projectFileKeysToTargets.keySet(), /* keepGoing= */ false);
    if (evalResult.hasError()) {
      throw new ProjectResolutionException(
          "error loading project files: " + evalResult.getError().getException().getMessage(),
          evalResult.getError().getException());
    }

    // De-duplicate the projectFileKeysToTargets keys by resolving project aliases, and store the
    // resulting canonicalized project-to-targets mapping in canonicalProjectToTargets.
    LinkedHashMap<Label, Collection<Label>> canonicalProjectsToTargets = new LinkedHashMap<>();
    for (var keyToTargets : projectFileKeysToTargets.asMap().entrySet()) {
      canonicalProjectsToTargets.put(
          ((ProjectValue) evalResult.get(keyToTargets.getKey())).getActualProjectFile(),
          keyToTargets.getValue());
    }

    if (canonicalProjectsToTargets.size() == 1 && targetsWithNoProjectFiles.isEmpty()) {
      // All targets resolve to the same canonical project file.
      Label projectFile = Iterables.getOnlyElement(canonicalProjectsToTargets.keySet());
      eventHandler.handle(
          Event.info(String.format("Reading project settings from %s.", projectFile)));
      return projectFile;
    }
    // Either some targets resolve to different files or a distinct subset resolve to no files.
    throw new ProjectResolutionException(
        differentProjectFilesError(canonicalProjectsToTargets, targetsWithNoProjectFiles), null);
  }

  /**
   * User-friendly error message for when targets resolve to different project files or only some
   * targets have project files.
   */
  private static String differentProjectFilesError(
      Map<Label, Collection<Label>> canonicalProjectsToTargets,
      Set<Label> targetsWithNoProjectFiles) {
    StringBuilder msgBuilder =
        new StringBuilder("This build doesn't support automatic project resolution. ")
            .append("Targets have different project settings:");
    // Maximum number of "//foo:target -> //foo:PROJECT.scl" entries to show.
    final int maxToShow = 5;

    // Iterate through each project file group (and also the "no project file" group), adding one
    // entry from each group until we reach the max. This samples each group as evenly as possible.
    ListMultimap<Label, Label> groupedResults = LinkedListMultimap.create();
    LinkedHashMap<Label, Iterator<Label>> projectFileToNextTarget = new LinkedHashMap<>();
    for (var entry : canonicalProjectsToTargets.entrySet()) {
      projectFileToNextTarget.put(entry.getKey(), entry.getValue().iterator());
    }
    if (!targetsWithNoProjectFiles.isEmpty()) {
      projectFileToNextTarget.put(null, targetsWithNoProjectFiles.iterator());
    }
    int totalResults = 0;
    LinkedHashMap<Label, Iterator<Label>> nextGroupIteration = projectFileToNextTarget;
    while (totalResults < maxToShow && !nextGroupIteration.isEmpty()) {
      projectFileToNextTarget = nextGroupIteration;
      nextGroupIteration = new LinkedHashMap<>();
      for (var entry : projectFileToNextTarget.entrySet()) {
        Iterator<Label> nextTarget = entry.getValue();
        groupedResults.put(entry.getKey(), nextTarget.next());
        if (nextTarget.hasNext()) {
          nextGroupIteration.put(entry.getKey(), nextTarget);
        }
        totalResults++;
        if (totalResults == maxToShow) {
          break;
        }
      }
    }

    // Report results grouped by PROJECT file.
    for (var group : groupedResults.asMap().entrySet()) {
      String projectFile = group.getKey() == null ? "no project file" : group.getKey().toString();
      for (var target : group.getValue()) {
        msgBuilder.append(String.format("\n  - %s -> %s", target, projectFile));
      }
    }
    int resultsLeft = projectFileToNextTarget.values().stream().mapToInt(Iterators::size).sum();
    if (resultsLeft > 0) {
      msgBuilder.append(String.format("\n  (...and %d more)", resultsLeft));
    }
    return msgBuilder.toString();
  }

  /**
   * Finds and returns the project files for a set of build targets.
   *
   * <p>This walks up each target's package path looking for {@link
   * com.google.devtools.build.lib.skyframe.ProjectFilesLookupFunction#PROJECT_FILE_NAME} files. For
   * example, for {@code //foo/bar/baz:mytarget}, this might look in {@code foo/bar/baz}, {@code
   * foo/bar}, and {@code foo} ("might" because it skips directories that don't have BUILD files -
   * those directories aren't packages).
   *
   * <p>This method doesn't read project file content so it doesn't resolve project file aliases.
   *
   * @return a map from each target to its set of project files, ordered by reverse package depth.
   *     So a project file in {@code foo/bar} appears before a project file in {@code foo}. Targets
   *     with no matching project files aren't in the map.
   */
  // TODO: b/324127050 - Document resolution semantics when this is less experimental.
  @VisibleForTesting
  static ImmutableMultimap<Label, Label> findProjectFiles(
      Collection<Label> targets,
      SkyframeExecutor skyframeExecutor,
      ExtendedEventHandler eventHandler)
      throws ProjectResolutionException {
    // TODO: b/324127050 - Support other repos.
    ImmutableMap<Label, ProjectFilesLookupValue.Key> targetsToSkyKeys =
        targets.stream()
            .collect(
                toImmutableMap(
                    target -> target,
                    target -> ProjectFilesLookupValue.key(target.getPackageIdentifier())));
    var evalResult =
        skyframeExecutor.evaluateSkyKeys(
            eventHandler, targetsToSkyKeys.values(), /* keepGoing= */ false);
      if (evalResult.hasError()) {
      throw new ProjectResolutionException(
          "Error finding project files", evalResult.getError().getException());
      }

    ImmutableMultimap.Builder<Label, Label> ans = ImmutableMultimap.builder();
    for (var entry : targetsToSkyKeys.entrySet()) {
      ProjectFilesLookupValue containingProjects =
          (ProjectFilesLookupValue) evalResult.get(entry.getValue());
      ans.putAll(entry.getKey(), containingProjects.getProjectFiles());
    }
    return ans.build();
  }

  /**
   * Applies {@link CoreOptions.sclConfig} to the top-level {@link BuildOptions}.
   *
   * <p>Given an existing PROJECT.scl file and an {@link CoreOptions.sclConfig}, the method creates
   * a {@link SkyKey} containing the {@link PathFragment} of the scl file and the config name which
   * is evaluated by the {@link FlagSetFunction}.
   *
   * @return {@link FlagSetValue} which has the effective top-level {@link BuildOptions} after
   *     project file resolution.
   */
  public static FlagSetValue modifyBuildOptionsWithFlagSets(
      Label projectFile,
      BuildOptions targetOptions,
      ImmutableMap<String, String> userOptions,
      ConfigFlagDefinitions configFlagDefinitions,
      boolean enforceCanonicalConfigs,
      ExtendedEventHandler eventHandler,
      SkyframeExecutor skyframeExecutor)
      throws InvalidConfigurationException {

    FlagSetValue.Key flagSetKey =
        FlagSetValue.Key.create(
            projectFile,
            targetOptions.get(CoreOptions.class).sclConfig,
            targetOptions,
            userOptions,
            configFlagDefinitions,
            enforceCanonicalConfigs);

    EvaluationResult<SkyValue> result =
        skyframeExecutor.evaluateSkyKeys(
            eventHandler, ImmutableList.of(flagSetKey), /* keepGoing= */ false);
    if (result.hasError()) {
      throw new InvalidConfigurationException(
          "Cannot parse options: " + result.getError().getException().getMessage(),
          Code.INVALID_BUILD_OPTIONS);
    }
    FlagSetValue value = (FlagSetValue) result.get(flagSetKey);
    value.getPersistentMessages().forEach(eventHandler::handle);
    return value;
  }
}
