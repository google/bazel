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

package com.google.devtools.build.lib.analysis.starlark;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.SymlinkAction.Code;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Action to create a possibly unresolved symbolic link to a raw path.
 *
 * <p>To create a symlink to a known-to-exist target with alias semantics similar to a true copy of
 * the input, use {@link SymlinkAction} instead.
 */
public final class UnresolvedSymlinkAction extends AbstractAction {
  private static final String GUID = "0f302651-602c-404b-881c-58913193cfe7";

  private final String target;
  private final String progressMessage;

  private UnresolvedSymlinkAction(
      ActionOwner owner, Artifact primaryOutput, String target, String progressMessage) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), ImmutableSet.of(primaryOutput));
    this.target = target;
    this.progressMessage = progressMessage;
  }

  public static UnresolvedSymlinkAction create(
      ActionOwner owner, Artifact primaryOutput, String target, String progressMessage) {
    Preconditions.checkArgument(primaryOutput.isSymlink());
    return new UnresolvedSymlinkAction(owner, primaryOutput, target, progressMessage);
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException {

    Path outputPath = actionExecutionContext.getInputPath(getPrimaryOutput());
    try {
      outputPath.createSymbolicLink(getTargetPathFragment());
    } catch (IOException e) {
      String message =
          String.format(
              "failed to create symbolic link '%s' with target '%s' due to I/O error: %s",
              getPrimaryOutput().getExecPathString(), target, e.getMessage());
      DetailedExitCode code = createDetailedExitCode(message, Code.LINK_CREATION_IO_EXCEPTION);
      throw new ActionExecutionException(message, e, this, false, code);
    }

    return ActionResult.EMPTY;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    fp.addString(GUID);
    fp.addString(target);
  }

  @Override
  public String describeKey() {
    return String.format("GUID: %s\ntarget: %s\n", GUID, target);
  }

  @Override
  public String getMnemonic() {
    return "UnresolvedSymlink";
  }

  @Override
  protected String getRawProgressMessage() {
    return progressMessage;
  }

  public String getTarget() {
    return getTargetPathFragment().getPathString();
  }

  private PathFragment getTargetPathFragment() {
    // TODO: PathFragment#create normalizes the symlink target, which may change how it resolves
    //  when combined with directory symlinks. Ideally, Bazel's file system abstraction would
    //  offer a way to create symlinks without any preprocessing of the target.
    return PathFragment.create(target);
  }

  private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setSymlinkAction(FailureDetails.SymlinkAction.newBuilder().setCode(detailedCode))
            .build());
  }
}
