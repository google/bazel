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
package com.google.devtools.build.lib.runtime;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR;

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Creates different types of {@link InstrumentationOutputBuilder}. */
public final class InstrumentationOutputFactory {
  private final Supplier<LocalInstrumentationOutput.Builder>
      localInstrumentationOutputBuilderSupplier;

  private final Supplier<BuildEventArtifactInstrumentationOutput.Builder>
      buildEventArtifactInstrumentationOutputBuilderSupplier;

  @Nullable
  final Supplier<InstrumentationOutputBuilder> redirectInstrumentationOutputBuilderSupplier;

  private final String localTempLoggingDirPathStr;

  private InstrumentationOutputFactory(
      Supplier<LocalInstrumentationOutput.Builder> localInstrumentationOutputBuilderSupplier,
      Supplier<BuildEventArtifactInstrumentationOutput.Builder>
          buildEventArtifactInstrumentationOutputBuilderSupplier,
      @Nullable Supplier<InstrumentationOutputBuilder> redirectInstrumentationOutputBuilderSupplier,
      String localTempLoggingDirPathStr) {
    this.localInstrumentationOutputBuilderSupplier = localInstrumentationOutputBuilderSupplier;
    this.buildEventArtifactInstrumentationOutputBuilderSupplier =
        buildEventArtifactInstrumentationOutputBuilderSupplier;
    this.redirectInstrumentationOutputBuilderSupplier =
        redirectInstrumentationOutputBuilderSupplier;
    this.localTempLoggingDirPathStr = localTempLoggingDirPathStr;
  }

  /**
   * Creates a {@link LocalInstrumentationOutput} located at {@code path}, which could future call
   * {@link LocalInstrumentationOutput#makeConvenienceLink()} to make a symlink with the simplified
   * {@code convenienceName} pointing to the local output. The symlink locates under the same
   * directory as the output.
   *
   * <p>Should only be used when an output MUST be written locally or is otherwise incompatible with
   * the flexible destinations supported by the preferred generic {@link
   * #createInstrumentationOutput}.
   */
  public LocalInstrumentationOutput createLocalOutputWithConvenientName(
      String name, Path path, String convenienceName) {
    return localInstrumentationOutputBuilderSupplier
        .get()
        .setName(name)
        .setPath(path)
        .setConvenienceName(convenienceName)
        .build();
  }

  /** Defines types of directory the {@link InstrumentationOutput} path is relative to. */
  // TODO: b/379723545 - Eventually, we want to deprecate WORKSPACE_OR_HOME and make path always
  // relative to user's current working directory.
  public enum DestinationRelativeTo {
    /** Output is relative to the bazel workspace or user's home directory. */
    WORKSPACE_OR_HOME,

    /** Output is relative to user's current working or home directory */
    WORKING_DIRECTORY_OR_HOME,

    /** Output is relative to the {@code output_base} directory. */
    OUTPUT_BASE,

    /**
     * Output is relative to the specified system logging directory.
     *
     * <p>Used only when {@link #localTempLoggingDirPathStr} is set.
     */
    TEMP_LOGGING_DIRECTORY
  }

  public InstrumentationOutput createInstrumentationOutput(
      String name,
      PathFragment destination,
      DestinationRelativeTo destinationRelativeTo,
      CommandEnvironment env,
      EventHandler eventHandler,
      @Nullable Boolean append,
      @Nullable Boolean internal) {
    return createInstrumentationOutput(
        name,
        destination,
        destinationRelativeTo,
        env,
        eventHandler,
        append,
        internal,
        /* createParent= */ false);
  }

  /**
   * Creates {@link LocalInstrumentationOutput} or an {@link InstrumentationOutput} object
   * redirecting outputs to be written on a different machine.
   *
   * <p>If {@link #redirectInstrumentationOutputBuilderSupplier} is not provided but {@code
   * --redirect_local_instrumentation_output_writes} is set, this method will default to return
   * {@link LocalInstrumentationOutput}.
   *
   * @param append Whether to open the {@link LocalInstrumentationOutput} file in append mode
   * @param internal Whether the {@link LocalInstrumentationOutput} file is a Bazel internal file.
   * @param createParent Whether to recursively create parent directories when the file path's
   *     parent directory does not exist.
   */
  public InstrumentationOutput createInstrumentationOutput(
      String name,
      PathFragment destination,
      DestinationRelativeTo destinationRelativeTo,
      CommandEnvironment env,
      EventHandler eventHandler,
      @Nullable Boolean append,
      @Nullable Boolean internal,
      boolean createParent) {
    boolean isRedirect =
        env.getOptions()
            .getOptions(CommonCommandOptions.class)
            .redirectLocalInstrumentationOutputWrites;
    if (isRedirect) {
      if (redirectInstrumentationOutputBuilderSupplier != null) {
        return redirectInstrumentationOutputBuilderSupplier
            .get()
            .setName(name)
            .setDestination(destination)
            .setDestinationRelatedToType(destinationRelativeTo)
            .setOptions(env.getOptions())
            .setCreateParent(createParent)
            .build();
      }
      eventHandler.handle(
          Event.warn(
              "Redirecting to write Instrumentation Output on a different machine is not"
                  + " supported. Defaulting to writing output locally."));
    }

    // Since PathFragmentConverter for flag value replaces prefixed `~/` with user's home path, the
    // destination path could be (1) an absolute path, or (2) a path relative to
    // output_base, workspace, cwd or some temporary logging directory.
    Path localOutputPath =
        (switch (destinationRelativeTo) {
              case OUTPUT_BASE -> env.getOutputBase();
              case WORKSPACE_OR_HOME -> env.getWorkspace();
              case WORKING_DIRECTORY_OR_HOME -> env.getWorkingDirectory();
              case TEMP_LOGGING_DIRECTORY ->
                  env.getRuntime().getFileSystem().getPath(localTempLoggingDirPathStr);
            })
            .getRelative(destination);
    return localInstrumentationOutputBuilderSupplier
        .get()
        .setName(name)
        .setPath(localOutputPath)
        .setAppend(append)
        .setInternal(internal)
        .setCreateParent(createParent)
        .build();
  }

  public BuildEventArtifactInstrumentationOutput createBuildEventArtifactInstrumentationOutput(
      String name, BuildEventArtifactUploader uploader) {
    return buildEventArtifactInstrumentationOutputBuilderSupplier
        .get()
        .setName(name)
        .setUploader(uploader)
        .build();
  }

  /** Builder for {@link InstrumentationOutputFactory}. */
  public static class Builder {
    @Nullable
    private Supplier<LocalInstrumentationOutput.Builder> localInstrumentationOutputBuilderSupplier;

    @Nullable
    private Supplier<BuildEventArtifactInstrumentationOutput.Builder>
        buildEventArtifactInstrumentationOutputBuilderSupplier;

    @Nullable
    private Supplier<InstrumentationOutputBuilder> redirectInstrumentationOutputBuilderSupplier;

    @Nullable private String localTempLoggingDirPathStr;

    @CanIgnoreReturnValue
    public Builder setLocalInstrumentationOutputBuilderSupplier(
        Supplier<LocalInstrumentationOutput.Builder> localInstrumentationOutputBuilderSupplier) {
      this.localInstrumentationOutputBuilderSupplier = localInstrumentationOutputBuilderSupplier;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setBuildEventArtifactInstrumentationOutputBuilderSupplier(
        Supplier<BuildEventArtifactInstrumentationOutput.Builder>
            buildEventArtifactInstrumentationOutputBuilderSupplier) {
      this.buildEventArtifactInstrumentationOutputBuilderSupplier =
          buildEventArtifactInstrumentationOutputBuilderSupplier;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setRedirectInstrumentationOutputBuilderSupplier(
        Supplier<InstrumentationOutputBuilder> redirectInstrumentationOutputBuilderSupplier) {
      this.redirectInstrumentationOutputBuilderSupplier =
          redirectInstrumentationOutputBuilderSupplier;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setLocalTempLoggingDirPathStr(String localTempLoggingDirPathStr) {
      this.localTempLoggingDirPathStr = localTempLoggingDirPathStr;
      return this;
    }

    public InstrumentationOutputFactory build() {
      return new InstrumentationOutputFactory(
          checkNotNull(
              localInstrumentationOutputBuilderSupplier,
              "Cannot create InstrumentationOutputFactory without localOutputBuilderSupplier"),
          checkNotNull(
              buildEventArtifactInstrumentationOutputBuilderSupplier,
              "Cannot create InstrumentationOutputFactory without bepOutputBuilderSupplier"),
          redirectInstrumentationOutputBuilderSupplier,
          localTempLoggingDirPathStr != null ? localTempLoggingDirPathStr : JAVA_IO_TMPDIR.value());
    }
  }
}
