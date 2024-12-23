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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.WorkspaceStatus;
import com.google.devtools.build.lib.server.FailureDetails.WorkspaceStatus.Code;
import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An action writing the workspace status files.
 *
 * <p>These files represent information about the environment the build was run in. They are used by
 * language-specific build info factories to make the data in them available for individual
 * languages (e.g. by turning them into .h files for C++)
 *
 * <p>The format of these files a list of key-value pairs, one for each line. The key and the value
 * are separated by a space.
 *
 * <p>There are two of these files: volatile and stable. Changes in the volatile file do not cause
 * rebuilds if no other file is changed. This is useful for frequently-changing information that
 * does not significantly affect the build, e.g. the current time.
 *
 * <p>For more information, see {@link Factory}.
 */
public abstract class WorkspaceStatusAction extends AbstractAction {

  /** Options controlling the workspace status command. */
  public static class Options extends OptionsBase {
    @Option(
        name = "embed_label",
        defaultValue = "",
        converter = OneLineStringConverter.class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help = "Embed source control revision or release label in binary")
    public String embedLabel;

    @Option(
        name = "workspace_status_command",
        defaultValue = "",
        converter = OptionsUtils.PathFragmentConverter.class,
        valueHelp = "<path>",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        help =
            "A command invoked at the beginning of the build to provide status "
                + "information about the workspace in the form of key/value pairs.  "
                + "See the User's Manual for the full specification. Also see "
                + "tools/buildstamp/get_workspace_status for an example.")
    public PathFragment workspaceStatusCommand;
  }

  /**
   * Action context required by the workspace status action as well as language-specific actions
   * that write workspace status artifacts.
   */
  public interface Context extends ActionContext {

    // TODO(ulfjack): Maybe move these to a separate ActionContext interface?
    WorkspaceStatusAction.Options getOptions();

    ImmutableMap<String, String> getClientEnv();

    com.google.devtools.build.lib.shell.Command getCommand();
  }

  /**
   * Parses the output of the workspace status action.
   *
   * <p>The output is a text file with each line representing a workspace status info key. The key
   * is the part of the line before the first space and should consist of the characters [A-Z_]
   * (although this is not checked). Everything after the first space is the value.
   */
  public static Map<String, String> parseValues(Path file) throws IOException {
    HashMap<String, String> result = new HashMap<>();
    Splitter lineSplitter = Splitter.on(' ').limit(2);
    for (String line : Splitter.on('\n').split(FileSystemUtils.readContentToString(file))) {
      List<String> items = lineSplitter.splitToList(line);
      if (items.size() != 2) {
        continue;
      }

      result.put(items.get(0), items.get(1));
    }

    return ImmutableMap.copyOf(result);
  }

  /** Environment for the {@link Factory} to create the workspace status action. */
  public interface Environment {
    Artifact createStableArtifact(String name);

    Artifact createVolatileArtifact(String name);
  }

  /** Factory for {@link WorkspaceStatusAction}. */
  public interface Factory {
    /**
     * Creates the workspace status action.
     *
     * <p>The action is never re-created, but the same action object is executed on every build. Use
     * {@link Context} to access any non-hermetic data.
     */
    WorkspaceStatusAction createWorkspaceStatusAction(Environment env);

    /**
     * Returns a map containing any available workspace status information.
     *
     * <p>Used to construct a {@link BuildInfoEvent} at the end of builds in which no such event was
     * posted.
     */
    ImmutableSortedMap<String, String> createDummyWorkspaceStatus(
        @Nullable WorkspaceInfoFromDiff workspaceInfoFromDiff);
  }

  private final String workspaceStatusDescription;

  protected WorkspaceStatusAction(
      ActionOwner owner,
      NestedSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      String workspaceStatusDescription) {
    super(owner, inputs, outputs);
    this.workspaceStatusDescription = workspaceStatusDescription;
  }

  /**
   * The volatile status artifact containing items that may change even if nothing changed between
   * the two builds, e.g. current time.
   */
  public abstract Artifact getVolatileStatus();

  /**
   * The stable status artifact containing items that change only if information relevant to the
   * build changes, e.g. the name of the user running the build or the hostname.
   */
  public abstract Artifact getStableStatus();

  @Override
  public boolean executeUnconditionally() {
    return true;
  }

  @Override
  public boolean isVolatile() {
    return true;
  }

  @Override
  protected final void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    // Since executeUnconditionally() is true (and this action is special-cased anyway), there is no
    // point in calculating a fingerprint.
  }

  protected ActionExecutionException createExecutionException(Exception e, Code detailedCode) {
    String message = "Failed to determine " + workspaceStatusDescription + ": " + e.getMessage();
    DetailedExitCode code = createDetailedExitCode(message, detailedCode);
    return new ActionExecutionException(message, e, this, false, code);
  }

  public static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setWorkspaceStatus(WorkspaceStatus.newBuilder().setCode(detailedCode))
            .build());
  }

  /** Converter for {@code --embed_label} which rejects strings that span multiple lines. */
  public static final class OneLineStringConverter extends Converter.Contextless<String> {

    @Override
    public String convert(String input) throws OptionsParsingException {
      if (input.contains("\n")) {
        throw new OptionsParsingException("Value must not contain multiple lines");
      }
      return input;
    }

    @Override
    public String getTypeDescription() {
      return "a one-line string";
    }
  }
}
