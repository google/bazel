// Copyright 2023 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.util.io.AsynchronousMessageOutputStream;
import com.google.devtools.build.lib.util.io.MessageInputStream;
import com.google.devtools.build.lib.util.io.MessageInputStreamWrapper.BinaryInputStreamWrapper;
import com.google.devtools.build.lib.util.io.MessageOutputStream;
import com.google.devtools.build.lib.util.io.MessageOutputStreamWrapper.BinaryOutputStreamWrapper;
import com.google.devtools.build.lib.util.io.MessageOutputStreamWrapper.JsonOutputStreamWrapper;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.XattrProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** A {@link SpawnLogContext} implementation that produces a log in expanded format. */
public class ExpandedSpawnLogContext extends SpawnLogContext {

  /** The log encoding. */
  public enum Encoding {
    /** Length-delimited binary protos. */
    BINARY,
    /** Newline-delimited JSON messages. */
    JSON
  }

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Encoding encoding;
  private final boolean sorted;

  private final Path tempPath;
  private final Path outputPath;

  private final PathFragment execRoot;
  @Nullable private final RemoteOptions remoteOptions;
  private final DigestHashFunction digestHashFunction;
  private final XattrProvider xattrProvider;

  /** Output stream to write directly into during execution. */
  private final MessageOutputStream<SpawnExec> rawOutputStream;

  public ExpandedSpawnLogContext(
      Path outputPath,
      Path tempPath,
      Encoding encoding,
      boolean sorted,
      PathFragment execRoot,
      @Nullable RemoteOptions remoteOptions,
      DigestHashFunction digestHashFunction,
      XattrProvider xattrProvider)
      throws IOException {
    this.encoding = encoding;
    this.sorted = sorted;
    this.tempPath = tempPath;
    this.outputPath = outputPath;
    this.execRoot = execRoot;
    this.remoteOptions = remoteOptions;
    this.digestHashFunction = digestHashFunction;
    this.xattrProvider = xattrProvider;

    if (needsConversion()) {
      // Write the unsorted binary format into a temporary path first, then convert into the output
      // format after execution. Delete a preexisting output file so that an incomplete invocation
      // doesn't appear to produce a nonsensical log.
      outputPath.delete();
      rawOutputStream = getRawOutputStream(tempPath);
    } else {
      // The unsorted binary format can be written directly into the output path during execution.
      rawOutputStream = getRawOutputStream(outputPath);
    }
  }

  private boolean needsConversion() {
    return encoding != Encoding.BINARY || sorted;
  }

  private static MessageOutputStream<SpawnExec> getRawOutputStream(Path path) throws IOException {
    // Use an AsynchronousMessageOutputStream so that writes occur in a separate thread.
    // This ensures concurrent writes don't tear and avoids blocking execution.
    return new AsynchronousMessageOutputStream<>(path);
  }

  private MessageOutputStream<SpawnExec> getConvertedOutputStream(Path path) throws IOException {
    return switch (encoding) {
      case BINARY -> new BinaryOutputStreamWrapper<>(path.getOutputStream());
      case JSON -> new JsonOutputStreamWrapper<>(path.getOutputStream());
    };
  }

  @Override
  public boolean shouldPublish() {
    // The expanded log tends to be too large to be uploaded to a remote store.
    return false;
  }

  @Override
  public void logSpawn(
      Spawn spawn,
      InputMetadataProvider inputMetadataProvider,
      SortedMap<PathFragment, ActionInput> inputMap,
      FileSystem fileSystem,
      Duration timeout,
      SpawnResult result)
      throws IOException, ExecException {
    try (SilentCloseable c = Profiler.instance().profile("logSpawn")) {
      SpawnExec.Builder builder = SpawnExec.newBuilder();
      builder.addAllCommandArgs(spawn.getArguments());
      builder.addAllEnvironmentVariables(getEnvironmentVariables(spawn));

      ImmutableSet<? extends ActionInput> toolFiles = spawn.getToolFiles().toSet();
      ImmutableList<PathFragment> toolRunfilesDirectories =
          toolFiles.stream()
              .filter(
                  actionInput ->
                      actionInput instanceof Artifact artifact && artifact.isRunfilesTree())
              .map(inputMetadataProvider::getRunfilesMetadata)
              .map(RunfilesArtifactValue::getRunfilesTree)
              .map(RunfilesTree::getExecPath)
              .collect(toImmutableList());

      try (SilentCloseable c1 = Profiler.instance().profile("logSpawn/inputs")) {
        for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
          PathFragment displayPath = e.getKey();
          ActionInput input = e.getValue();

          if (input instanceof VirtualActionInput.EmptyActionInput) {
            // Do not include a digest, as it's a waste of space.
            builder
                .addInputsBuilder()
                .setPath(displayPath.getPathString())
                .setIsTool(toolRunfilesDirectories.stream().anyMatch(displayPath::startsWith));
            continue;
          }

          boolean isTool =
              toolFiles.contains(input)
                  || (input instanceof TreeFileArtifact treeFileArtifact
                      && toolFiles.contains(treeFileArtifact.getParent()))
                  || toolRunfilesDirectories.stream().anyMatch(displayPath::startsWith);

          Path contentPath = fileSystem.getPath(execRoot.getRelative(input.getExecPathString()));

          if (isInputDirectory(input, inputMetadataProvider)) {
            listDirectoryContents(
                displayPath, contentPath, builder::addInputs, inputMetadataProvider, isTool);
            continue;
          }

          if (input.isSymlink()) {
            FileArtifactValue metadata = inputMetadataProvider.getInputMetadata(input);
            checkState(metadata.getType().isSymlink(), metadata);
            builder
                .addInputsBuilder()
                .setPath(displayPath.getPathString())
                .setSymlinkTargetPath(metadata.getUnresolvedSymlinkTarget())
                .setIsTool(isTool);
            continue;
          }

          Digest digest =
              computeDigest(
                  input,
                  contentPath,
                  inputMetadataProvider,
                  xattrProvider,
                  digestHashFunction,
                  /* includeHashFunctionName= */ true);

          builder
              .addInputsBuilder()
              .setPath(displayPath.getPathString())
              .setDigest(digest)
              .setIsTool(isTool);
        }
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Error computing spawn input properties");
      }
      try (SilentCloseable c1 = Profiler.instance().profile("logSpawn/outputs")) {
        ArrayList<String> outputPaths = new ArrayList<>();
        for (ActionInput output : spawn.getOutputFiles()) {
          outputPaths.add(output.getExecPathString());
        }
        Collections.sort(outputPaths);
        builder.addAllListedOutputs(outputPaths);
        try {
          for (ActionInput output : spawn.getOutputFiles()) {
            Path path = fileSystem.getPath(execRoot.getRelative(output.getExecPathString()));
            if (!output.isDirectory() && !output.isSymlink() && path.isFile()) {
              builder
                  .addActualOutputsBuilder()
                  .setPath(output.getExecPathString())
                  .setDigest(
                      computeDigest(
                          output,
                          path,
                          inputMetadataProvider,
                          xattrProvider,
                          digestHashFunction,
                          /* includeHashFunctionName= */ true));
            } else if (output.isDirectory() && path.isDirectory()) {
              listDirectoryContents(
                  output.getExecPath(),
                  path,
                  builder::addActualOutputs,
                  inputMetadataProvider,
                  /* isTool= */ false);
            } else if (output.isSymlink() && path.isSymbolicLink()) {
              builder
                  .addActualOutputsBuilder()
                  .setPath(output.getExecPathString())
                  .setSymlinkTargetPath(path.readSymbolicLink().getPathString());
            }
          }
        } catch (IOException ex) {
          logger.atWarning().withCause(ex).log("Error computing spawn output properties");
        }
      }
      builder.setRemotable(Spawns.mayBeExecutedRemotely(spawn));

      Platform platform = getPlatform(spawn, remoteOptions);
      if (platform != null) {
        builder.setPlatform(platform);
      }
      if (result.status() != SpawnResult.Status.SUCCESS) {
        builder.setStatus(result.status().toString());
      }
      if (!timeout.isZero()) {
        builder.setTimeoutMillis(timeout.toMillis());
      }
      builder.setCacheable(Spawns.mayBeCached(spawn));
      builder.setRemoteCacheable(Spawns.mayBeCachedRemotely(spawn));
      builder.setExitCode(result.exitCode());
      builder.setCacheHit(result.isCacheHit());
      builder.setRunner(result.getRunnerName());

      if (result.getDigest() != null) {
        builder.setDigest(result.getDigest());
      }

      builder.setMnemonic(spawn.getMnemonic());

      if (spawn.getTargetLabel() != null) {
        builder.setTargetLabel(spawn.getTargetLabel().toString());
      }

      builder.setMetrics(getSpawnMetricsProto(result));

      try (SilentCloseable c1 = Profiler.instance().profile("logSpawn/write")) {
        rawOutputStream.write(builder.build());
      }
    }
  }

  @Override
  public void logSymlinkAction(AbstractAction action) {
    // The expanded log does not report symlink actions.
  }

  @Override
  public void close() throws IOException {
    rawOutputStream.close();

    if (!needsConversion()) {
      return;
    }

    try (MessageInputStream<SpawnExec> rawInputStream =
            new BinaryInputStreamWrapper<>(
                tempPath.getInputStream(), SpawnExec.getDefaultInstance());
        MessageOutputStream<SpawnExec> convertedOutputStream =
            getConvertedOutputStream(outputPath)) {
      if (sorted) {
        StableSort.stableSort(rawInputStream, convertedOutputStream);
      } else {
        SpawnExec ex;
        while ((ex = rawInputStream.read()) != null) {
          convertedOutputStream.write(ex);
        }
      }
    } finally {
      try {
        tempPath.delete();
      } catch (IOException e) {
        // Intentionally ignored.
      }
    }
  }

  /**
   * Expands a directory into its contents.
   *
   * <p>Note the difference between {@code displayPath} and {@code contentPath}: the first is where
   * the spawn can find the directory, while the second is where Bazel can find it. They're not the
   * same for a directory appearing in a runfiles or fileset tree.
   */
  private void listDirectoryContents(
      PathFragment displayPath,
      Path contentPath,
      Consumer<File> addFile,
      InputMetadataProvider inputMetadataProvider,
      boolean isTool)
      throws IOException {
    List<Dirent> sortedDirent = new ArrayList<>(contentPath.readdir(Symlinks.NOFOLLOW));
    sortedDirent.sort(Comparator.comparing(Dirent::getName));

    for (Dirent dirent : sortedDirent) {
      String name = dirent.getName();
      PathFragment childDisplayPath = displayPath.getChild(name);
      Path childContentPath = contentPath.getChild(name);

      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        listDirectoryContents(
            childDisplayPath, childContentPath, addFile, inputMetadataProvider, isTool);
        continue;
      }

      addFile.accept(
          File.newBuilder()
              .setPath(childDisplayPath.getPathString())
              .setDigest(
                  computeDigest(
                      null,
                      childContentPath,
                      inputMetadataProvider,
                      xattrProvider,
                      digestHashFunction,
                      /* includeHashFunctionName= */ true))
              .setIsTool(isTool)
              .build());
    }
  }
}
