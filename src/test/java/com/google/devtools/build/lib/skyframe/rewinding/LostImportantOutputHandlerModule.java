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
package com.google.devtools.build.lib.skyframe.rewinding;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactExpander.MissingExpansionException;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputSymlink;
import com.google.devtools.build.lib.actions.ImportantOutputHandler;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.ForOverride;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Installs an {@link ImportantOutputHandler} that allows customizing lost outputs for rewinding
 * tests.
 */
public class LostImportantOutputHandlerModule extends BlazeModule {

  private final Set<String> pathsToConsiderLost = Sets.newConcurrentHashSet();
  private final Function<byte[], String> digestFn;
  private boolean outputHandlerEnabled = true;

  protected LostImportantOutputHandlerModule(Function<byte[], String> digestFn) {
    this.digestFn = checkNotNull(digestFn);
  }

  /** Controls whether an {@link ImportantOutputHandler} will be installed. */
  public final void setOutputHandlerEnabled(boolean enabled) {
    outputHandlerEnabled = enabled;
  }

  final void addLostOutput(String execPath) {
    pathsToConsiderLost.add(execPath);
  }

  final void verifyAllLostOutputsConsumed() {
    assertThat(pathsToConsiderLost).isEmpty();
  }

  @Override
  public final void registerActionContexts(
      ModuleActionContextRegistry.Builder registryBuilder,
      CommandEnvironment env,
      BuildRequest buildRequest) {
    if (outputHandlerEnabled) {
      registryBuilder.register(ImportantOutputHandler.class, createOutputHandler(env));
    }
  }

  @ForOverride
  protected ImportantOutputHandler createOutputHandler(CommandEnvironment env) {
    return new MockImportantOutputHandler(env.getExecRoot().asFragment());
  }

  /**
   * Returns whether the given output should be treated as lost.
   *
   * <p>If {@code true} is returned, the given output is removed from the set of lost outputs so
   * that a subsequent call to this method with the same output will return {@code false}.
   */
  protected final boolean outputIsLost(PathFragment execPath) {
    return pathsToConsiderLost.remove(execPath.getPathString());
  }

  private final class MockImportantOutputHandler implements ImportantOutputHandler {
    private final PathFragment execRoot;

    MockImportantOutputHandler(PathFragment execRoot) {
      this.execRoot = execRoot;
    }

    @Override
    public LostArtifacts processOutputsAndGetLostArtifacts(
        Iterable<Artifact> outputs,
        ArtifactExpander expander,
        InputMetadataProvider metadataProvider) {
      return getLostOutputs(outputs, expander, metadataProvider);
    }

    @Override
    public LostArtifacts processRunfilesAndGetLostArtifacts(
        PathFragment runfilesDir,
        Map<PathFragment, Artifact> runfiles,
        ArtifactExpander expander,
        InputMetadataProvider metadataProvider) {
      return getLostOutputs(runfiles.values(), expander, metadataProvider);
    }

    @Override
    public void processTestOutputs(Collection<Path> testOutputs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void processWorkspaceStatusOutputs(Path stableOutput, Path volatileOutput) {
      throw new UnsupportedOperationException();
    }

    private LostArtifacts getLostOutputs(
        Iterable<Artifact> outputs,
        ArtifactExpander expander,
        InputMetadataProvider metadataProvider) {
      ImmutableMap.Builder<String, ActionInput> lost = ImmutableMap.builder();
      ImmutableSetMultimap.Builder<ActionInput, Artifact> owners = ImmutableSetMultimap.builder();
      for (OutputAndOwner outputAndOwner : expand(outputs, expander)) {
        ActionInput output = outputAndOwner.output;
        Artifact owner = outputAndOwner.owner;
        PathFragment execPath = output.getExecPath();
        if (execPath.isAbsolute()) {
          execPath = execPath.relativeTo(execRoot);
        }
        if (!outputIsLost(execPath)) {
          continue;
        }
        FileArtifactValue metadata;
        try {
          metadata = metadataProvider.getInputMetadata(output);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
        lost.put(digestFn.apply(metadata.getDigest()), output);
        if (owner != null) {
          owners.put(output, owner);
        }
      }
      return new LostArtifacts(lost.buildKeepingLast(), owners.build()::get);
    }

    private ImmutableList<OutputAndOwner> expand(
        Iterable<Artifact> outputs, ArtifactExpander expander) {
      return stream(outputs)
          .flatMap(artifact -> expand(artifact, expander))
          .collect(toImmutableList());
    }

    private Stream<OutputAndOwner> expand(Artifact output, ArtifactExpander expander) {
      if (output.isTreeArtifact()) {
        var children = expander.tryExpandTreeArtifact(output).stream();
        var archivedTreeArtifact = expander.getArchivedTreeArtifact(output);
        var expansion =
            archivedTreeArtifact == null
                ? children
                : Stream.concat(children, Stream.of(archivedTreeArtifact));
        return expansion.map(child -> new OutputAndOwner(child, output));
      }
      if (output.isFileset()) {
        ImmutableList<FilesetOutputSymlink> links;
        try {
          links = expander.expandFileset(output).symlinks();
        } catch (MissingExpansionException e) {
          throw new IllegalStateException(e);
        }
        return links.stream()
            .filter(FilesetOutputSymlink::relativeToExecRoot)
            .map(
                link ->
                    new OutputAndOwner(
                        ActionInputHelper.fromPath(link.reconstituteTargetPath(execRoot)), output));
      }
      return Stream.of(new OutputAndOwner(output, null));
    }

    private record OutputAndOwner(ActionInput output, @Nullable Artifact owner) {}
  }
}
