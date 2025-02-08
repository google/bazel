// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionValue;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.repository.RequestRepositoryInformationEvent;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.skyframe.CycleInfo;
import com.google.devtools.build.skyframe.CyclesReporter;
import com.google.devtools.build.skyframe.SkyKey;

/** Reports cycles introduced by module extensions and .bzl files where they are declared. */
public class BzlmodRepoCycleReporter implements CyclesReporter.SingleCycleReporter {

  private static final Predicate<SkyKey> IS_BZL_LOAD =
      SkyFunctions.isSkyFunction(SkyFunctions.BZL_LOAD);

  private static final Predicate<SkyKey> IS_PACKAGE_LOOKUP =
      SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE_LOOKUP);

  private static final Predicate<SkyKey> IS_CONTAINING_PACKAGE =
      SkyFunctions.isSkyFunction(SkyFunctions.CONTAINING_PACKAGE_LOOKUP);

  private static final Predicate<SkyKey> IS_REPOSITORY_DIRECTORY =
      SkyFunctions.isSkyFunction(SkyFunctions.REPOSITORY_DIRECTORY);

  private static final Predicate<SkyKey> IS_REPO_RULE =
      SkyFunctions.isSkyFunction(BzlmodRepoRuleValue.BZLMOD_REPO_RULE);

  private static final Predicate<SkyKey> IS_EXTENSION_IMPL =
      SkyFunctions.isSkyFunction(SkyFunctions.SINGLE_EXTENSION_EVAL);

  private static final Predicate<SkyKey> IS_EXTENSION_VALIDATION =
      SkyFunctions.isSkyFunction(SkyFunctions.SINGLE_EXTENSION);

  private static final Predicate<SkyKey> IS_REPO_MAPPING =
      SkyFunctions.isSkyFunction(SkyFunctions.REPOSITORY_MAPPING);

  private static final Predicate<SkyKey> IS_MODULE_EXTENSION_REPO_MAPPING_ENTRIES =
      SkyFunctions.isSkyFunction(SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES);

  private static final Predicate<SkyKey> IS_PACKAGE =
      SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE);

  private static final Predicate<SkyKey> IS_EXTERNAL_PACKAGE =
      SkyFunctions.isSkyFunction(SkyFunctions.EXTERNAL_PACKAGE);

  private static final Predicate<SkyKey> IS_WORKSPACE_FILE =
      SkyFunctions.isSkyFunction(WorkspaceFileValue.WORKSPACE_FILE);

  private static final Predicate<SkyKey> IS_MODULE_RESOLUTION =
      SkyFunctions.isSkyFunction(SkyFunctions.BAZEL_MODULE_RESOLUTION);

  private static final Predicate<SkyKey> IS_DEP_GRAPH =
      SkyFunctions.isSkyFunction(SkyFunctions.BAZEL_DEP_GRAPH);

  private static final Predicate<SkyKey> IS_MODULE_FILE =
      SkyFunctions.isSkyFunction(SkyFunctions.MODULE_FILE);

  private static void requestRepoDefinitions(
      ExtendedEventHandler eventHandler, Iterable<SkyKey> repos) {
    for (SkyKey repo : repos) {
      if (repo instanceof RepositoryDirectoryValue.Key) {
        eventHandler.post(
            new RequestRepositoryInformationEvent(
                ((RepositoryDirectoryValue.Key) repo).argument().getName()));
      }
    }
  }

  @Override
  public boolean maybeReportCycle(
      SkyKey topLevelKey,
      CycleInfo cycleInfo,
      boolean alreadyReported,
      ExtendedEventHandler eventHandler) {
    ImmutableList<SkyKey> cycle = cycleInfo.getCycle();
    if (alreadyReported) {
      return true;
    }

    // This cycle reporter is aimed to handle cycles between any chaining of general .bzl
    // files, extension-generated repositories, extension evaluations, and the .bzl files where
    // they are declared. The state machine that describes this kind of cycles is:
    //    ________________________
    //   V                        |
    // PACKAGE -> REPOSITORY -> EXT -> BZL_LOAD -
    //                   ^                   ^   \
    //                   |___________________|___|
    // TODO(andreisolo): Figure out how to detect and print this kind of cycles more specifically.
    if (Iterables.all(
            cycle,
            Predicates.or(
                IS_REPOSITORY_DIRECTORY,
                IS_PACKAGE_LOOKUP,
                IS_REPO_RULE,
                IS_EXTENSION_IMPL,
                IS_EXTENSION_VALIDATION,
                IS_BZL_LOAD,
                IS_CONTAINING_PACKAGE,
                IS_REPO_MAPPING,
                IS_MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                IS_PACKAGE,
                IS_EXTERNAL_PACKAGE,
                IS_WORKSPACE_FILE,
                IS_MODULE_RESOLUTION,
                IS_DEP_GRAPH,
                IS_MODULE_FILE))
        && Iterables.any(cycle, Predicates.or(IS_REPO_RULE, IS_EXTENSION_IMPL))) {
      StringBuilder cycleMessage =
          new StringBuilder(
              "Circular definition of repositories generated by module extensions and/or .bzl"
                  + " files:");
      Iterable<SkyKey> repos =
          Iterables.filter(
              cycle,
              Predicates.or(
                  IS_REPOSITORY_DIRECTORY,
                  IS_EXTENSION_IMPL,
                  IS_BZL_LOAD,
                  IS_REPO_MAPPING,
                  IS_WORKSPACE_FILE,
                  IS_MODULE_RESOLUTION,
                  IS_DEP_GRAPH,
                  IS_MODULE_FILE));
      Function<Object, String> printer =
          rawInput -> {
            SkyKey input = (SkyKey) rawInput;
            if (input instanceof RepositoryDirectoryValue.Key) {
              return ((RepositoryDirectoryValue.Key) input).argument().toString();
            } else if (input.argument() instanceof ModuleExtensionId id) {
              return "module extension " + id;
            } else if (input.argument() instanceof RepositoryMappingValue.Key key) {
              if (key == RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS) {
                return "repository mapping of @@ without WORKSPACE repos";
              }
              return String.format("repository mapping of %s", key.repoName());
            } else if (input.argument() instanceof WorkspaceFileValue.WorkspaceFileKey) {
              return "WORKSPACE file";
            } else if (input.argument() == BazelModuleResolutionValue.KEY) {
              return "module resolution";
            } else if (input.argument() == BazelDepGraphValue.KEY) {
              return "module dependency graph";
            } else if (input.argument() instanceof ModuleFileValue.Key) {
              return "module file of " + input.argument();
            } else {
              Preconditions.checkArgument(input.argument() instanceof BzlLoadValue.Key);
              return ((BzlLoadValue.Key) input.argument()).getLabel().toString();
            }
          };
      AbstractLabelCycleReporter.printCycle(ImmutableList.copyOf(repos), cycleMessage, printer);
      eventHandler.handle(Event.error(null, cycleMessage.toString()));
      // To help debugging, request that the information be printed about where the respective
      // repositories were defined.
      requestRepoDefinitions(eventHandler, repos);
      return true;
    } else if (Iterables.any(cycle, IS_BZL_LOAD)) {
      Label fileLabel =
          ((BzlLoadValue.Key) Iterables.getLast(Iterables.filter(cycle, IS_BZL_LOAD))).getLabel();
      final String errorMessage;
      if (cycle.get(0).equals(StarlarkBuiltinsValue.key(true))) {
        // We know `fileLabel` is the last .bzl visited in the cycle. We also know that
        // BzlLoadFunction triggered the cycle by requesting StarlarkBuiltinsValue w/autoloads.
        // We know that we're not in builtins .bzls, because they don't request w/autoloads.
        // Thus, `fileLabel` is a .bzl transitively depended on by an autoload.
        errorMessage =
            String.format(
                "Cycle caused by autoloads, failed to load .bzl file '%s'.\n"
                    + "Add '%s' to --repositories_without_autoloads or disable autoloads by setting"
                    + " '--incompatible_autoload_externally='\n"
                    + "More information on https://github.com/bazelbuild/bazel/issues/23043.\n",
                fileLabel, fileLabel.getRepository().getName());
      } else {
        errorMessage =
            String.format(
                "Failed to load .bzl file '%s': possible dependency cycle detected.\n", fileLabel);
      }
      eventHandler.handle(Event.error(null, errorMessage));
      return true;
    } else if (Iterables.any(cycle, IS_PACKAGE_LOOKUP)) {
      PackageIdentifier pkg =
          (PackageIdentifier)
              Iterables.getLast(Iterables.filter(cycle, IS_PACKAGE_LOOKUP)).argument();
      eventHandler.handle(
          Event.error(
              null,
              String.format(
                  "cannot load package '%s': possible dependency cycle detected.\n", pkg)));
      return true;
    }
    return false;
  }
}
