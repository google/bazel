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
package com.google.devtools.build.lib.exec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander.MissingExpansionException;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.RunningActionEvent;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeAction;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeActionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.profiler.AutoProfiler;
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements SymlinkTreeAction by using the output service or by running an embedded script to
 * create the symlink tree.
 */
public final class SymlinkTreeStrategy implements SymlinkTreeActionContext {
  private static final Duration MIN_LOGGING = Duration.ofMillis(100);

  @VisibleForTesting
  static final Function<Artifact, PathFragment> TO_PATH =
      (artifact) -> artifact == null ? null : artifact.getPath().asFragment();

  private final OutputService outputService;
  private final Path execRoot;
  private final BinTools binTools;
  private final String workspaceName;

  public SymlinkTreeStrategy(
      OutputService outputService, Path execRoot, BinTools binTools, String workspaceName) {
    this.outputService = outputService;
    this.execRoot = execRoot;
    this.binTools = binTools;
    this.workspaceName = workspaceName;
  }

  @Override
  public void createSymlinks(
      SymlinkTreeAction action, ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    actionExecutionContext.getEventHandler().post(new RunningActionEvent(action, "local"));
    try (AutoProfiler p =
        GoogleAutoProfilerUtils.logged("running " + action.prettyPrint(), MIN_LOGGING)) {
      // TODO(tjgq): Respect RunfileSymlinksMode.SKIP even in the presence of an OutputService.
      try {
        if (outputService.canCreateSymlinkTree()) {
          Path inputManifest = actionExecutionContext.getInputPath(action.getInputManifest());

          Map<PathFragment, PathFragment> symlinks;
          if (action.isFilesetTree()) {
            symlinks = getFilesetMap(action, actionExecutionContext);
          } else {
            // TODO(tjgq): This produces an incorrect path for unresolved symlinks, which should be
            // created textually.
            symlinks = Maps.transformValues(getRunfilesMap(action), TO_PATH);
          }

          outputService.createSymlinkTree(
              symlinks, action.getOutputManifest().getExecPath().getParentDirectory());

          createOutput(action, actionExecutionContext, inputManifest);
        } else if (action.getRunfileSymlinksMode() == RunfileSymlinksMode.SKIP) {
          // Delete symlinks possibly left over by a previous invocation with a different mode.
          // This is required because only the output manifest is considered an action output, so
          // Skyframe does not clear the directory for us.
          createSymlinkTreeHelper(action).clearRunfilesDirectory();
        } else if (action.getRunfileSymlinksMode() == RunfileSymlinksMode.INTERNAL) {
          try {
            SymlinkTreeHelper helper = createSymlinkTreeHelper(action);
            if (action.isFilesetTree()) {
              helper.createFilesetSymlinksDirectly(getFilesetMap(action, actionExecutionContext));
            } else {
              helper.createRunfilesSymlinksDirectly(getRunfilesMap(action));
            }
          } catch (IOException e) {
            throw ActionExecutionException.fromExecException(
                new EnvironmentalExecException(e, Code.SYMLINK_TREE_CREATION_IO_EXCEPTION), action);
          }

          Path inputManifest = actionExecutionContext.getInputPath(action.getInputManifest());
          createOutput(action, actionExecutionContext, inputManifest);
        } else {
          Map<String, String> resolvedEnv = new LinkedHashMap<>();
          action.getEnvironment().resolve(resolvedEnv, actionExecutionContext.getClientEnv());
          createSymlinkTreeHelper(action)
              .createSymlinksUsingCommand(
                  binTools, resolvedEnv, actionExecutionContext.getFileOutErr());
        }
      } catch (ExecException e) {
        throw ActionExecutionException.fromExecException(e, action);
      }
    }
  }

  private ImmutableMap<PathFragment, PathFragment> getFilesetMap(
      SymlinkTreeAction action, ActionExecutionContext actionExecutionContext) {
    ImmutableList<FilesetOutputSymlink> filesetLinks;
    try {
      filesetLinks =
          actionExecutionContext
              .getArtifactExpander()
              .expandFileset(action.getInputManifest())
              .symlinks();
    } catch (MissingExpansionException e) {
      throw new IllegalStateException(e);
    }

    return SymlinkTreeHelper.processFilesetLinks(
        filesetLinks, action.getWorkspaceNameForFileset(), execRoot.asFragment());
  }

  private static Map<PathFragment, Artifact> getRunfilesMap(SymlinkTreeAction action) {
    // This call outputs warnings about overlapping symlinks. However, since this has already been
    // called by the SourceManifestAction, we silence the warnings here.
    return action.getRunfiles().getRunfilesInputs(action.getRepoMappingManifest());
  }

  private static void createOutput(
      SymlinkTreeAction action, ActionExecutionContext actionExecutionContext, Path inputManifest)
      throws EnvironmentalExecException {
    Path outputManifest = actionExecutionContext.getInputPath(action.getOutputManifest());
    // Link output manifest on success. We avoid a file copy as these manifests may be
    // large. Note that this step has to come last because the OutputService may delete any
    // pre-existing symlink tree before creating a new one.
    try {
      outputManifest.createSymbolicLink(inputManifest);
    } catch (IOException e) {
      throw createLinkFailureException(outputManifest, e);
    }
  }

  private SymlinkTreeHelper createSymlinkTreeHelper(SymlinkTreeAction action) {
    // Do not indirect paths through the action filesystem, for two reasons:
    // (1) we always want to create the symlinks on disk, even if the action filesystem creates them
    //     in memory (at the time of writing, no action filesystem implementations do so, but this
    //     may change in the future).
    // (2) current action filesystem implementations are not a true overlay filesystem, so errors
    //     might occur in an incremental build when the parent directory of a symlink exists on disk
    //     but not in memory (see https://github.com/bazelbuild/bazel/issues/24867).
    return new SymlinkTreeHelper(
        execRoot,
        action.getInputManifest().getPath(),
        action.getOutputManifest().getPath(),
        action.getOutputManifest().getPath().getParentDirectory(),
        action.isFilesetTree(),
        workspaceName);
  }

  private static EnvironmentalExecException createLinkFailureException(
      Path outputManifest, IOException e) {
    return new EnvironmentalExecException(
        e,
        FailureDetail.newBuilder()
            .setMessage("Failed to link output manifest '" + outputManifest.getPathString() + "'")
            .setExecution(
                Execution.newBuilder().setCode(Code.SYMLINK_TREE_MANIFEST_LINK_IO_EXCEPTION))
            .build());
  }
}
