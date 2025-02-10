// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Streams.stream;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionContext.LostInputsCheck;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputPrefetcher;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactOwner;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.BuildConfigurationEvent;
import com.google.devtools.build.lib.actions.DiscoveredModulesPruner;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.actions.cache.OutputMetadataStore;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissDetail;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** A bunch of utilities that are useful for tests concerning actions, artifacts, etc. */
public final class ActionsTestUtil {

  private final ActionGraph actionGraph;

  public ActionsTestUtil(ActionGraph actionGraph) {
    this.actionGraph = actionGraph;
  }

  private static final Label NULL_LABEL = Label.parseCanonicalUnchecked("//null/action:owner");

  public static ActionExecutionContext createContext(
      Executor executor,
      ExtendedEventHandler eventHandler,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      Path execRoot,
      OutputMetadataStore outputMetadataStore) {
    return createContext(
        executor,
        eventHandler,
        actionKeyContext,
        fileOutErr,
        new SingleBuildFileCache(
            execRoot.getPathString(), execRoot.getFileSystem(), SyscallCache.NO_CACHE),
        outputMetadataStore,
        /* clientEnv= */ ImmutableMap.of());
  }

  public static ActionExecutionContext createContext(
      Executor executor,
      ExtendedEventHandler eventHandler,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      Map<String, String> clientEnv) {
    return new ActionExecutionContext(
        executor,
        inputMetadataProvider,
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        outputMetadataStore,
        /* rewindingEnabled= */ false,
        LostInputsCheck.NONE,
        fileOutErr,
        eventHandler,
        ImmutableMap.copyOf(clientEnv),
        /* topLevelFilesets= */ ImmutableMap.of(),
        treeArtifact -> ImmutableSortedSet.of(),
        /* actionFileSystem= */ null,
        /* skyframeDepsResult= */ null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  public static ActionExecutionContext createContext(ExtendedEventHandler eventHandler) {
    return createContext(new DummyExecutor(), eventHandler);
  }

  public static ActionExecutionContext createContext(
      Executor executor, ExtendedEventHandler eventHandler) {
    return new ActionExecutionContext(
        executor,
        /* inputMetadataProvider= */ null,
        ActionInputPrefetcher.NONE,
        new ActionKeyContext(),
        /* outputMetadataStore= */ null,
        /* rewindingEnabled= */ false,
        LostInputsCheck.NONE,
        /* fileOutErr= */ null,
        eventHandler,
        /* clientEnv= */ ImmutableMap.of(),
        /* topLevelFilesets= */ ImmutableMap.of(),
        treeArtifact -> ImmutableSortedSet.of(),
        /* actionFileSystem= */ null,
        /* skyframeDepsResult= */ null,
        DiscoveredModulesPruner.DEFAULT,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  public static ActionExecutionContext createContextForInputDiscovery(
      Executor executor,
      ExtendedEventHandler eventHandler,
      ActionKeyContext actionKeyContext,
      FileOutErr fileOutErr,
      Path execRoot,
      OutputMetadataStore outputMetadataStore,
      Environment environment,
      DiscoveredModulesPruner discoveredModulesPruner) {
    return ActionExecutionContext.forInputDiscovery(
        executor,
        new SingleBuildFileCache(
            execRoot.getPathString(), execRoot.getFileSystem(), SyscallCache.NO_CACHE),
        ActionInputPrefetcher.NONE,
        actionKeyContext,
        outputMetadataStore,
        /* rewindingEnabled= */ false,
        LostInputsCheck.NONE,
        fileOutErr,
        eventHandler,
        ImmutableMap.of(),
        environment,
        /* actionFileSystem= */ null,
        discoveredModulesPruner,
        SyscallCache.NO_CACHE,
        ThreadStateReceiver.NULL_INSTANCE);
  }

  /** Creates an {@link ActionExecutionValue} with only file outputs. */
  public static ActionExecutionValue createActionExecutionValue(
      ImmutableMap<Artifact, FileArtifactValue> artifactData) {
    return createActionExecutionValue(artifactData, /* treeArtifactData= */ ImmutableMap.of());
  }

  /** Creates an {@link ActionExecutionValue} with only file and tree artifact outputs. */
  public static ActionExecutionValue createActionExecutionValue(
      ImmutableMap<Artifact, FileArtifactValue> artifactData,
      ImmutableMap<Artifact, TreeArtifactValue> treeArtifactData) {
    return ActionExecutionValue.createFromOutputMetadataStore(
        artifactData,
        treeArtifactData,
        FilesetOutputTree.EMPTY,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  public static Artifact createArtifact(ArtifactRoot root, Path path) {
    return createArtifactWithRootRelativePath(root, root.getRoot().relativize(path));
  }

  public static Artifact createArtifact(ArtifactRoot root, String path) {
    return createArtifactWithRootRelativePath(root, PathFragment.create(path));
  }

  public static Artifact createArtifactWithRootRelativePath(
      ArtifactRoot root, PathFragment rootRelativePath) {
    PathFragment execPath = root.getExecPath().getRelative(rootRelativePath);
    return createArtifactWithExecPath(root, execPath);
  }

  public static Artifact createArtifactWithExecPath(ArtifactRoot root, PathFragment execPath) {
    return root.isSourceRoot()
        ? new Artifact.SourceArtifact(root, execPath, ArtifactOwner.NULL_OWNER)
        : DerivedArtifact.create(root, execPath, NULL_ARTIFACT_OWNER);
  }

  public static SpecialArtifact createRunfilesArtifact(ArtifactRoot root, String execPath) {
    return SpecialArtifact.create(
        root, PathFragment.create(execPath), NULL_ARTIFACT_OWNER, SpecialArtifactType.RUNFILES);
  }

  public static SpecialArtifact createTreeArtifactWithGeneratingAction(
      ArtifactRoot root, PathFragment execPath) {
    SpecialArtifact treeArtifact =
        SpecialArtifact.create(root, execPath, NULL_ARTIFACT_OWNER, SpecialArtifactType.TREE);
    treeArtifact.setGeneratingActionKey(NULL_ACTION_LOOKUP_DATA);
    return treeArtifact;
  }

  public static SpecialArtifact createTreeArtifactWithGeneratingAction(
      ArtifactRoot root, String rootRelativePath) {
    return createTreeArtifactWithGeneratingAction(
        root, root.getExecPath().getRelative(rootRelativePath));
  }

  public static SpecialArtifact createUnresolvedSymlinkArtifact(
      ArtifactRoot root, String execPath) {
    return createUnresolvedSymlinkArtifactWithExecPath(
        root, root.getExecPath().getRelative(execPath));
  }

  public static SpecialArtifact createUnresolvedSymlinkArtifactWithExecPath(
      ArtifactRoot root, PathFragment execPath) {
    return SpecialArtifact.create(
        root, execPath, NULL_ARTIFACT_OWNER, SpecialArtifactType.UNRESOLVED_SYMLINK);
  }

  public static void assertNoArtifactEndingWith(RuleConfiguredTarget target, String path) {
    Pattern endPattern = Pattern.compile(path + "$");
    for (ActionAnalysisMetadata action : target.getActions()) {
      for (Artifact output : action.getOutputs()) {
        assertThat(output.getExecPathString()).doesNotMatch(endPattern);
      }
    }
  }

  public static ArtifactRoot createArtifactRootFromTwoPaths(Path root, Path execPath) {
    return ArtifactRoot.asDerivedRoot(root, RootType.Output, execPath.relativeTo(root));
  }

  /**
   * Creates a {@link VirtualActionInput} with given string as contents and provided relative path.
   */
  public static VirtualActionInput createVirtualActionInput(String relativePath, String contents) {
    return createVirtualActionInput(PathFragment.create(relativePath), contents);
  }

  /** Creates a {@link VirtualActionInput} with given string as contents and provided path. */
  public static VirtualActionInput createVirtualActionInput(PathFragment path, String contents) {
    return new VirtualActionInput() {
      @Override
      public String getExecPathString() {
        return path.getPathString();
      }

      @Override
      public PathFragment getExecPath() {
        return path;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(contents.getBytes(UTF_8));
      }
    };
  }

  @SerializationConstant
  public static final ActionLookupKey NULL_ARTIFACT_OWNER =
      new ActionLookupKey() {

        @Override
        public SkyFunctionName functionName() {
          return null;
        }

        @Override
        public Label getLabel() {
          return NULL_LABEL;
        }

        @Nullable
        @Override
        public BuildConfigurationKey getConfigurationKey() {
          return null;
        }

        @Override
        public String toString() {
          return "NULL_ARTIFACT_OWNER";
        }
      };

  public static final ActionTemplateExpansionKey NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER =
      ActionTemplateExpansionValue.key(NULL_ARTIFACT_OWNER, /*actionIndex=*/ 0);

  public static final Artifact DUMMY_ARTIFACT =
      new Artifact.SourceArtifact(
          ArtifactRoot.asSourceRoot(
              Root.absoluteRoot(new InMemoryFileSystem(DigestHashFunction.SHA256))),
          PathFragment.create("/dummy"),
          NULL_ARTIFACT_OWNER);

  public static final ActionOwner NULL_ACTION_OWNER =
      ActionOwner.createDummy(
          NULL_LABEL,
          new Location("dummy-file", 0, 0),
          /* targetKind= */ "dummy-kind",
          /* buildConfigurationMnemonic= */ "dummy-configuration-mnemonic",
          /* configurationChecksum= */ "dummy-configuration",
          new BuildConfigurationEvent(
              BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
              BuildEventStreamProtos.BuildEvent.getDefaultInstance()),
          /* isToolConfiguration= */ false,
          /* executionPlatform= */ PlatformInfo.EMPTY_PLATFORM_INFO,
          /* aspectDescriptors= */ ImmutableList.of(),
          /* execProperties= */ ImmutableMap.of());

  @SerializationConstant
  public static final ActionLookupData NULL_ACTION_LOOKUP_DATA =
      ActionLookupData.create(NULL_ARTIFACT_OWNER, 0);

  /** An unchecked exception class for action conflicts. */
  public static class UncheckedActionConflictException extends RuntimeException {
    public UncheckedActionConflictException(ActionConflictException e) {
      super(e);
    }
  }

  /** A dummy Action class for use in tests. */
  public static class NullAction extends AbstractAction {

    public NullAction() {
      super(
          NULL_ACTION_OWNER,
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          ImmutableList.of(DUMMY_ARTIFACT));
    }

    public NullAction(ActionOwner owner, Artifact... outputs) {
      super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), ImmutableList.copyOf(outputs));
    }

    public NullAction(Artifact... outputs) {
      super(
          NULL_ACTION_OWNER,
          NestedSetBuilder.emptySet(Order.STABLE_ORDER),
          ImmutableList.copyOf(outputs));
    }

    public NullAction(List<Artifact> inputs, Artifact... outputs) {
      super(
          NULL_ACTION_OWNER,
          NestedSetBuilder.wrap(Order.STABLE_ORDER, inputs),
          ImmutableList.copyOf(outputs));
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext) {
      return ActionResult.EMPTY;
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString("action");
    }

    @Override
    public String getMnemonic() {
      return "Null";
    }
  }

  /**
   * A mocked action containing the inputs and outputs of the action. Used for tests that do not
   * need to execute the action.
   */
  public static class MockAction extends AbstractAction {
    private final boolean isShareable;

    public MockAction(Iterable<Artifact> inputs, ImmutableSet<Artifact> outputs) {
      this(inputs, outputs, /* isShareable= */ true);
    }

    public MockAction(
        Iterable<Artifact> inputs,
        ImmutableSet<Artifact> outputs,
        boolean isShareable) {
      super(
          NULL_ACTION_OWNER,
          NestedSetBuilder.<Artifact>stableOrder().addAll(inputs).build(),
          outputs);
      this.isShareable = isShareable;
    }

    @Override
    public String getMnemonic() {
      return "Mock action";
    }

    @Override
    protected void computeKey(
        ActionKeyContext actionKeyContext,
        @Nullable ArtifactExpander artifactExpander,
        Fingerprint fp) {
      fp.addString("Mock Action " + getPrimaryOutput());
    }

    @Override
    public ActionResult execute(ActionExecutionContext actionExecutionContext) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShareable() {
      return isShareable;
    }
  }

  /**
   * For a bunch of actions, gets the basenames of the paths and accumulates them in a space
   * separated string, like <code>foo.o bar.o baz.a</code>.
   */
  public static String baseNamesOf(NestedSet<Artifact> artifacts) {
    return baseNamesOf(artifacts.toList());
  }

  /**
   * For a bunch of actions, gets the basenames of the paths and accumulates them in a space
   * separated string, like <code>foo.o bar.o baz.a</code>.
   */
  public static String baseNamesOf(Iterable<Artifact> artifacts) {
    List<String> baseNames = baseArtifactNames(artifacts);
    return Joiner.on(' ').join(baseNames);
  }

  /**
   * For a bunch of actions, gets the basenames of the paths, sorts them in alphabetical order and
   * accumulates them in a space separated string, for example <code>bar.o baz.a foo.o</code>.
   */
  public static String sortedBaseNamesOf(NestedSet<Artifact> artifacts) {
    return sortedBaseNamesOf(artifacts.toList());
  }

  /**
   * For a bunch of actions, gets the basenames of the paths, sorts them in alphabetical order and
   * accumulates them in a space separated string, for example <code>bar.o baz.a foo.o</code>.
   */
  public static String sortedBaseNamesOf(Iterable<Artifact> artifacts) {
    List<String> baseNames = baseArtifactNames(artifacts);
    Collections.sort(baseNames);
    return Joiner.on(' ').join(baseNames);
  }

  /** For a bunch of artifacts, gets the basenames and accumulates them in a List. */
  public static List<String> baseArtifactNames(NestedSet<Artifact> artifacts) {
    return transform(artifacts.toList(), artifact -> artifact.getExecPath().getBaseName());
  }

  /** For a bunch of artifacts, gets the basenames and accumulates them in a List. */
  public static List<String> baseArtifactNames(Iterable<Artifact> artifacts) {
    return transform(artifacts, artifact -> artifact.getExecPath().getBaseName());
  }

  /** For a bunch of artifacts, gets the exec paths and accumulates them in a List. */
  public static List<String> execPaths(NestedSet<Artifact> artifacts) {
    return execPaths(artifacts.toList());
  }

  /** For a bunch of artifacts, gets the exec paths and accumulates them in a List. */
  public static List<String> execPaths(Iterable<Artifact> artifacts) {
    return transform(artifacts, Artifact::getExecPathString);
  }

  /**
   * For a bunch of artifacts, gets the pretty printed names and accumulates them in a List. Note
   * that this returns the root-relative paths, not the exec paths.
   */
  public static List<String> prettyArtifactNames(NestedSet<Artifact> artifacts) {
    return prettyArtifactNames(artifacts.toList());
  }

  /**
   * For a bunch of artifacts, gets the pretty printed names and accumulates them in a List. Note
   * that this returns the root-relative paths, not the exec paths.
   */
  public static List<String> prettyArtifactNames(Iterable<Artifact> artifacts) {
    return transform(artifacts, Artifact::prettyPrint);
  }

  public static <T, R> List<R> transform(Iterable<T> iterable, Function<T, R> mapper) {
    // Can not use com.google.common.collect.Iterables.transform() there, as it returns Iterable.
    return stream(iterable).map(mapper).collect(Collectors.toList());
  }

  /**
   * Returns the closure of the predecessors of any of the given types, joining the basenames of the
   * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
   */
  public String predecessorClosureOf(Artifact artifact, FileType... types) {
    return predecessorClosureOf(Collections.singleton(artifact), types);
  }

  /**
   * Returns the closure of the predecessors of any of the given types, joining the basenames of the
   * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
   */
  public String predecessorClosureOf(NestedSet<Artifact> artifacts, FileType... types) {
    return predecessorClosureOf(artifacts.toList(), types);
  }

  /**
   * Returns the closure of the predecessors of any of the given types, joining the basenames of the
   * artifacts into a space-separated string like "libfoo.a libbar.a libbaz.a".
   */
  public String predecessorClosureOf(Iterable<Artifact> artifacts, FileType... types) {
    Set<Artifact> visited = artifactClosureOf(artifacts);
    return baseNamesOf(FileType.filter(visited, types));
  }

  /** Returns the closure of the predecessors of any of the given types. */
  public Collection<String> predecessorClosureAsCollection(Artifact artifact, FileType... types) {
    return predecessorClosureAsCollection(Collections.singleton(artifact), types);
  }

  /** Returns the closure of the predecessors of any of the given types. */
  public Collection<String> predecessorClosureAsCollection(
      NestedSet<Artifact> artifacts, FileType... types) {
    return predecessorClosureAsCollection(artifacts.toList(), types);
  }

  /** Returns the closure of the predecessors of any of the given types. */
  public Collection<String> predecessorClosureAsCollection(
      Iterable<Artifact> artifacts, FileType... types) {
    return baseArtifactNames(FileType.filter(artifactClosureOf(artifacts), types));
  }

  /** Returns the closure over the input files of an action. */
  public Set<Artifact> inputClosureOf(ActionAnalysisMetadata action) {
    return artifactClosureOf(action.getInputs().toList());
  }

  /** Returns the closure over the input files of an artifact. */
  public Set<Artifact> artifactClosureOf(Artifact artifact) {
    return artifactClosureOf(Collections.singleton(artifact));
  }

  /** Returns the closure over the input files of a set of artifacts. */
  public Set<Artifact> artifactClosureOf(NestedSet<Artifact> artifacts) {
    return artifactClosureOf(artifacts.toList());
  }

  /** Returns the closure over the input files of a set of artifacts. */
  public Set<Artifact> artifactClosureOf(Iterable<Artifact> artifacts) {
    Set<Artifact> visited = new LinkedHashSet<>();
    List<Artifact> toVisit = Lists.newArrayList(artifacts);
    while (!toVisit.isEmpty()) {
      Artifact current = toVisit.remove(0);
      if (!visited.add(current)) {
        continue;
      }
      ActionAnalysisMetadata generatingAction = actionGraph.getGeneratingAction(current);
      if (generatingAction != null) {
        toVisit.addAll(generatingAction.getInputs().toList());
      }
    }
    return visited;
  }

  /** Returns the closure over the input files of an artifact, filtered by the given matcher. */
  public ImmutableSet<Artifact> filteredArtifactClosureOf(
      Artifact artifact, Predicate<Artifact> matcher) {
    return artifactClosureOf(artifact).stream().filter(matcher).collect(toImmutableSet());
  }

  /** Returns a predicate to match {@link Artifact}s with the given root-relative path suffix. */
  public static Predicate<Artifact> getArtifactSuffixMatcher(String suffix) {
    return input -> input.getRootRelativePath().getPathString().endsWith(suffix);
  }

  /**
   * Finds all the actions that are instances of {@code actionClass} in the transitive closure of
   * prerequisites.
   */
  public <A extends Action> List<A> findTransitivePrerequisitesOf(
      Artifact artifact, Class<A> actionClass, Predicate<Artifact> allowedArtifacts) {
    List<A> actions = new ArrayList<>();
    Set<Artifact> visited = new HashSet<>();
    Queue<Artifact> toVisit = new ArrayDeque<>();
    toVisit.add(artifact);
    while (!toVisit.isEmpty()) {
      Artifact current = toVisit.remove();
      if (!visited.add(current)) {
        continue;
      }
      ActionAnalysisMetadata generatingAction = actionGraph.getGeneratingAction(current);
      if (generatingAction != null) {
        generatingAction.getInputs().toList().stream()
            .filter(allowedArtifacts)
            .forEach(toVisit::add);
        if (actionClass.isInstance(generatingAction)) {
          actions.add(actionClass.cast(generatingAction));
        }
      }
    }
    return actions;
  }

  public <A extends Action> List<A> findTransitivePrerequisitesOf(
      Artifact artifact, Class<A> actionClass) {
    return findTransitivePrerequisitesOf(artifact, actionClass, Predicates.alwaysTrue());
  }

  /**
   * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
   * suffix and returns its generating Action.
   */
  public Action getActionForArtifactEndingWith(NestedSet<Artifact> artifacts, String suffix) {
    return getActionForArtifactEndingWith(artifacts.toList(), suffix);
  }

  /**
   * Looks in the given artifacts Iterable for the first Artifact whose path ends with the given
   * suffix and returns its generating Action.
   */
  public Action getActionForArtifactEndingWith(Iterable<Artifact> artifacts, String suffix) {
    Artifact a = getFirstArtifactEndingWith(artifacts, suffix);

    if (a == null) {
      return null;
    }

    ActionAnalysisMetadata action = actionGraph.getGeneratingAction(a);
    if (action != null) {
      Preconditions.checkState(
          action instanceof Action, "%s is not a proper Action object", action.prettyPrint());
      return (Action) action;
    } else {
      return null;
    }
  }

  /** Returns the first artifact found in the given set whose path ends with the given suffix. */
  public static Artifact getFirstArtifactEndingWith(
      NestedSet<? extends Artifact> artifacts, String suffix) {
    return getFirstArtifactEndingWith(artifacts.toList(), suffix);
  }

  /**
   * Returns the first artifact found in the given Iterable whose path ends with the given suffix.
   */
  public static Artifact getFirstArtifactEndingWith(
      Iterable<? extends Artifact> artifacts, String suffix) {
    return getFirstArtifactMatching(
        artifacts, artifact -> artifact.getExecPath().getPathString().endsWith(suffix));
  }

  public static Artifact getFirstDerivedArtifactEndingWith(
      NestedSet<? extends Artifact> artifacts, String suffix) {
    return getFirstArtifactMatching(
        artifacts.toList(),
        artifact ->
            artifact instanceof DerivedArtifact
                && artifact.getExecPath().getPathString().endsWith(suffix));
  }

  /** Returns the first Artifact in the provided Iterable that matches the specified predicate. */
  public static Artifact getFirstArtifactMatching(
      Iterable<? extends Artifact> artifacts, Predicate<Artifact> predicate) {
    for (Artifact a : artifacts) {
      if (predicate.test(a)) {
        return a;
      }
    }
    return null;
  }

  /**
   * Returns a list of the Artifacts in <code>artifacts</code> whose paths end with the given
   * suffix.
   */
  public static List<Artifact> getArtifactsEndingWith(
      Iterable<? extends Artifact> artifacts, String suffix) {
    List<Artifact> result = new ArrayList<>();
    for (Artifact a : artifacts) {
      if (a.getExecPath().getPathString().endsWith(suffix)) {
        result.add(a);
      }
    }
    return result;
  }

  /**
   * Returns the first artifact which is an input to "action" and has the specified basename. An
   * assertion error is raised if none is found.
   */
  public static Artifact getInput(ActionAnalysisMetadata action, String basename) {
    for (Artifact artifact : action.getInputs().toList()) {
      if (artifact.getExecPath().getBaseName().equals(basename)) {
        return artifact;
      }
    }

    throw new AssertionError("No input with basename '" + basename + "' in action " + action);
  }

  /** Returns true if an artifact that is an input to "action" with the specific basename exists. */
  public static boolean hasInput(ActionAnalysisMetadata action, String basename) {
    try {
      getInput(action, basename);
      return true;
    } catch (AssertionError e) {
      return false;
    }
  }

  /**
   * Returns the first artifact which is an output of "action" and has the specified basename. An
   * assertion error is raised if none is found.
   */
  public static Artifact getOutput(ActionAnalysisMetadata action, String basename) {
    for (Artifact artifact : action.getOutputs()) {
      if (artifact.getExecPath().getBaseName().equals(basename)) {
        return artifact;
      }
    }
    throw new AssertionError("No output with basename '" + basename + "' in action " + action);
  }

  public static SpawnActionTemplate createDummySpawnActionTemplate(
      SpecialArtifact inputTreeArtifact, SpecialArtifact outputTreeArtifact) {
    return new SpawnActionTemplate.Builder(inputTreeArtifact, outputTreeArtifact)
        .setCommandLineTemplate(CustomCommandLine.builder().build())
        .setExecutable(PathFragment.create("bin/executable"))
        .setOutputPathMapper(TreeFileArtifact::getParentRelativePath)
        .build(NULL_ACTION_OWNER);
  }

  /** Builder for a list of {@link MissDetail}s with defaults set to zero for all possible items. */
  public static class MissDetailsBuilder {
    private final Map<MissReason, Integer> details = new EnumMap<>(MissReason.class);

    /** Constructs a new builder with all possible cache miss reasons set to zero counts. */
    public MissDetailsBuilder() {
      for (MissReason reason : MissReason.values()) {
        if (reason == MissReason.UNRECOGNIZED) {
          // The presence of this enum value is a protobuf artifact and not part of our metrics
          // collection. Just skip it.
          continue;
        }
        details.put(reason, 0);
      }
    }

    /** Sets the count of the given miss reason to the given value. */
    @CanIgnoreReturnValue
    public MissDetailsBuilder set(MissReason reason, int count) {
      checkArgument(details.containsKey(reason));
      details.put(reason, count);
      return this;
    }

    /** Constructs the list of {@link MissDetail}s. */
    public Iterable<MissDetail> build() {
      List<MissDetail> result = new ArrayList<>(details.size());
      for (Map.Entry<MissReason, Integer> entry : details.entrySet()) {
        MissDetail detail =
            MissDetail.newBuilder().setReason(entry.getKey()).setCount(entry.getValue()).build();
        result.add(detail);
      }
      return result;
    }
  }

  /**
   * An {@link ArtifactResolver} all of whose operations throw an exception.
   *
   * <p>This is to be used as a base class by other test programs that need to implement only a few
   * of the hooks required by the scenario under test.
   */
  public static class FakeArtifactResolverBase implements ArtifactResolver {
    @Override
    public SourceArtifact getSourceArtifact(PathFragment execPath, Root root, ArtifactOwner owner) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SourceArtifact getSourceArtifact(PathFragment execPath, Root root) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SourceArtifact resolveSourceArtifact(
        PathFragment execPath, RepositoryName repositoryName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Map<PathFragment, SourceArtifact> resolveSourceArtifacts(
        Iterable<PathFragment> execPaths, PackageRootResolver resolver) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Path getPathFromSourceExecPath(Path execRoot, PathFragment execPath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isDerivedArtifact(PathFragment execPath) {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A {@link OutputMetadataStore} for tests that throws {@link UnsupportedOperationException} for
   * its operations.
   */
  public static final OutputMetadataStore THROWING_METADATA_HANDLER =
      new FakeInputMetadataHandlerBase() {
        @Override
        public String toString() {
          return "THROWING_METADATA_HANDLER";
        }
      };

  /**
   * A {@link OutputMetadataStore} all of whose operations throw an exception.
   *
   * <p>This is to be used as a base class by other test programs that need to implement only a few
   * of the hooks required by the scenario under test. Tests that need an instance but do not need
   * any functionality can use {@link #THROWING_METADATA_HANDLER}.
   */
  public static class FakeInputMetadataHandlerBase
      implements InputMetadataProvider, OutputMetadataStore {
    @Override
    public FileArtifactValue getInputMetadataChecked(ActionInput input) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    @Nullable
    public RunfilesArtifactValue getRunfilesMetadata(ActionInput input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableList<RunfilesTree> getRunfilesTrees() {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileArtifactValue getOutputMetadata(ActionInput input)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public ActionInput getInput(String execPath) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TreeArtifactValue getTreeArtifactValue(SpecialArtifact treeArtifact)
        throws IOException, InterruptedException {
      throw new UnsupportedOperationException();
    }

    @Override
    public void injectFile(Artifact output, FileArtifactValue metadata) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void injectTree(SpecialArtifact treeArtifact, TreeArtifactValue tree) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void markOmitted(Artifact output) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean artifactOmitted(Artifact artifact) {
      return false;
    }

    @Override
    public void resetOutputs(Iterable<? extends Artifact> outputs) {
      throw new UnsupportedOperationException();
    }
  }
}
