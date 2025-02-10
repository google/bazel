// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.UnmodifiableIterator;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ArgChunk;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.ArtifactExpander.MissingExpansionException;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.actions.CommandLineLimits;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.FilesetOutputTree.RelativeSymlinkBehaviorWithoutError;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.SingleStringArgFormatter;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.DirectoryExpander;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.FileRootApi;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.HashCodes;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * Supports {@code ctx.actions.args()} from Starlark.
 *
 * <p>To be as memory-friendly as possible, expansion happens in three stages. First, when a
 * Starlark rule is analyzed, its {@code Args} are built into a {@code StarlarkCustomCommandLine}.
 * This is retained in Skyframe, so care is taken to be as compact as possible. At this point, the
 * {@linkplain #arguments representation} is just a "recipe" to compute the full command line later
 * on. Additionally, {@link CommandLine#addToFingerprint} supports computing a fingerprint without
 * actually constructing the expanded command line.
 *
 * <p>Second, right before an action executes, {@link #expand(ArtifactExpander, PathMapper)} is
 * called to "preprocess" the recipe into a {@link PreprocessedCommandLine}. This step includes
 * flattening nested sets and applying any operations that can throw an exception, such as expanding
 * directories and invoking {@code map_each} functions. At this point, the representation stores a
 * string for each individual argument, but string formatting (including {@code format}, {@code
 * format_each}, {@code before_each}, {@code join_with}, {@code format_joined}, and {@code
 * flag_per_line}), is not yet applied. This means that in the common case of an {@link Artifact}
 * with no {@code map_each} function, the string representation is still its {@link
 * Artifact#getExecPathString}, which is not a novel string instance - it is already stored in the
 * {@link Artifact}. This is crucial because for param files (the longest command lines), the
 * preprocessed representation is retained throughout the action's execution.
 *
 * <p>Finally, string formatting is applied lazily during iteration over a {@link
 * PreprocessedCommandLine}. When there is no param file, this happens up front during {@link
 * CommandLines#expand(ArtifactExpander, PathFragment, PathMapper, CommandLineLimits)}. When a param
 * file is used, the lazy {@link PreprocessedCommandLine#arguments} is stored in a {@link
 * ParamFileActionInput}, which is processed by the action execution strategy. Strategies should
 * respect the laziness of {@link ParamFileActionInput#getArguments} by iterating as few times as
 * possible and not retaining elements longer than necessary.
 *
 * <p>As an example, consider this common usage pattern, where {@code inputs} is a {@code depset} of
 * artifacts:
 *
 * <pre>{@code
 * args = ctx.actions.args()
 * args.use_param_file("--flagfile=%s")
 * args.add_all(inputs, format_each = "--input=%s")
 * }</pre>
 *
 * During analysis, the nested set is stored without flattening. During preprocessing, the nested
 * set is flattened and {@link Artifact#expandToCommandLine} is called for each element, but this
 * returns an exec path string instance already stored inside the artifact. {@code format_each} is
 * not yet applied, so no new strings are created. {@link SingleStringArgFormatter#format} is only
 * called during iteration over the {@link PreprocessedCommandLine#arguments}.
 */
// TODO: b/327187486 - PathMapper is currently invoked during the preprocessing step. If path
//  stripping is enabled, this means that the lazy approach to string formatted described above is
//  defeated. Ideally, PathMapper should be invoked lazily during iteration over a
//  PreprocessedCommandLine.
public class StarlarkCustomCommandLine extends CommandLine {

  private static final Joiner LINE_JOINER = Joiner.on("\n").skipNulls();
  private static final Joiner FIELD_JOINER = Joiner.on(": ").skipNulls();

  // Used to distinguish command line arguments that are potentially subject to special default
  // stringification (such as Artifacts when path mapped or Labels when not main repo labels) from
  // strings that happen to be identical to their string representations.
  private enum StringificationType {
    DEFAULT,
    FILE,
    LABEL
  }

  /**
   * Representation of a sequence of arguments originating from {@code Args.add_all} or {@code
   * Args.add_joined}.
   */
  @AutoCodec
  static final class VectorArg {

    // The strong interner is used when StarlarkSemantics is null. The weak interner is used when
    // StarlarkSemantics is present (implying a map_each), since StarlarkSemantics can change
    // between builds.
    private static final Interner<VectorArg> strongInterner = BlazeInterners.newStrongInterner();
    private static final Interner<VectorArg> weakInterner = BlazeInterners.newWeakInterner();

    private static final int HAS_MAP_EACH = 1;
    private static final int IS_NESTED_SET = 1 << 1;
    private static final int EXPAND_DIRECTORIES = 1 << 2;
    private static final int UNIQUIFY = 1 << 3;
    private static final int OMIT_IF_EMPTY = 1 << 4;
    private static final int HAS_ARG_NAME = 1 << 5;
    private static final int HAS_FORMAT_EACH = 1 << 6;
    private static final int HAS_BEFORE_EACH = 1 << 7;
    private static final int HAS_JOIN_WITH = 1 << 8;
    private static final int HAS_FORMAT_JOINED = 1 << 9;
    private static final int HAS_TERMINATE_WITH = 1 << 10;
    private static final int HAS_SINGLE_ARG = 1 << 11;

    private static final UUID EXPAND_DIRECTORIES_UUID =
        UUID.fromString("9d7520d2-a187-11e8-98d0-529269fb1459");
    private static final UUID UNIQUIFY_UUID =
        UUID.fromString("7f494c3e-faea-4498-a521-5d3bc6ee19eb");
    private static final UUID OMIT_IF_EMPTY_UUID =
        UUID.fromString("923206f1-6474-4a8f-b30f-4dd3143622e6");
    private static final UUID ARG_NAME_UUID =
        UUID.fromString("2bc00382-7199-46ec-ad52-1556577cde1a");
    private static final UUID FORMAT_EACH_UUID =
        UUID.fromString("8e974aec-df07-4a51-9418-f4c1172b4045");
    private static final UUID BEFORE_EACH_UUID =
        UUID.fromString("f7e101bc-644d-4277-8562-6515ad55a988");
    private static final UUID JOIN_WITH_UUID =
        UUID.fromString("c227dbd3-edad-454e-bc8a-c9b5ba1c38a3");
    private static final UUID FORMAT_JOINED_UUID =
        UUID.fromString("528af376-4233-4c27-be4d-b0ff24ed68db");
    private static final UUID TERMINATE_WITH_UUID =
        UUID.fromString("a4e5e090-0dbd-4d41-899a-77cfbba58655");

    private final int features;
    private final StringificationType stringificationType;
    // Null unless map_each is present.
    @Nullable private final StarlarkSemantics starlarkSemantics;
    // Null unless map_each is a global function.
    @Nullable private final StarlarkFunction mapEachGlobalFunction;

    private VectorArg(
        int features,
        StringificationType stringificationType,
        @Nullable StarlarkSemantics starlarkSemantics,
        @Nullable StarlarkFunction mapEachGlobalFunction) {
      this.features = features;
      this.stringificationType = stringificationType;
      this.starlarkSemantics = starlarkSemantics;
      this.mapEachGlobalFunction = mapEachGlobalFunction;
    }

    private static VectorArg create(
        int features,
        StringificationType stringificationType,
        @Nullable StarlarkSemantics starlarkSemantics,
        @Nullable StarlarkFunction mapEachGlobalFunction) {
      return intern(
          new VectorArg(features, stringificationType, starlarkSemantics, mapEachGlobalFunction));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static VectorArg intern(VectorArg vectorArg) {
      var interner = vectorArg.starlarkSemantics == null ? strongInterner : weakInterner;
      return interner.intern(vectorArg);
    }

    private static void push(
        List<Object> arguments, Builder arg, StarlarkSemantics starlarkSemantics) {
      // The location is really only needed if map_each is present, but it's easy enough to require
      // it unconditionally.
      checkNotNull(arg.location);

      int features = 0;
      features |= arg.mapEach != null ? HAS_MAP_EACH : 0;
      features |= arg.nestedSet != null ? IS_NESTED_SET : 0;
      features |= arg.expandDirectories ? EXPAND_DIRECTORIES : 0;
      features |= arg.uniquify ? UNIQUIFY : 0;
      features |= arg.omitIfEmpty ? OMIT_IF_EMPTY : 0;
      features |= arg.argName != null ? HAS_ARG_NAME : 0;
      features |= arg.formatEach != null ? HAS_FORMAT_EACH : 0;
      features |= arg.beforeEach != null ? HAS_BEFORE_EACH : 0;
      features |= arg.joinWith != null ? HAS_JOIN_WITH : 0;
      features |= arg.formatJoined != null ? HAS_FORMAT_JOINED : 0;
      features |= arg.terminateWith != null ? HAS_TERMINATE_WITH : 0;
      features |= arg.nestedSet == null && arg.list.size() == 1 ? HAS_SINGLE_ARG : 0;
      // Intern global Starlark functions in the VectorArg as they can be reused for all rule
      // instances (and possibly even across multiple Args.add_all calls), saving a slot in
      // arguments.
      StarlarkFunction mapEachGlobalFunction =
          arg.mapEach instanceof StarlarkFunction sfn && sfn.isGlobal() ? sfn : null;
      arguments.add(
          VectorArg.create(
              features,
              arg.nestedSetStringificationType,
              arg.mapEach != null ? starlarkSemantics : null,
              mapEachGlobalFunction));
      if (arg.mapEach != null) {
        if (mapEachGlobalFunction == null) {
          arguments.add(arg.mapEach);
        }
        arguments.add(arg.location);
      }
      if (arg.nestedSet != null) {
        arguments.add(arg.nestedSet);
      } else {
        List<?> list = arg.list;
        int count = list.size();
        if (count != 1) {
          // A count of 1 is encoded via the HAS_SINGLE_ARG feature.
          arguments.add(count);
        }
        for (int i = 0; i < count; ++i) {
          arguments.add(list.get(i));
        }
      }
      if (arg.argName != null) {
        arguments.add(arg.argName);
      }
      if (arg.formatEach != null) {
        arguments.add(arg.formatEach);
      }
      if (arg.beforeEach != null) {
        checkState(arg.joinWith == null, "before_each and join_with are mutually exclusive");
        checkState(
            arg.formatJoined == null, "before_each and format_joined are mutually exclusive");
        arguments.add(arg.beforeEach);
      }
      if (arg.joinWith != null) {
        arguments.add(arg.joinWith);
      }
      if (arg.formatJoined != null) {
        checkNotNull(arg.joinWith, "format_joined requires join_with");
        arguments.add(arg.formatJoined);
      }
      if (arg.terminateWith != null) {
        arguments.add(arg.terminateWith);
      }
    }

    /**
     * Adds this {@link VectorArg} to the given {@link PreprocessedCommandLine.Builder}.
     *
     * @param arguments result of {@link #rawArgsAsList}
     * @param argi index in {@code arguments} at which this {@link VectorArg} begins; should be
     *     directly preceded by {@code this}
     * @param builder the {@link PreprocessedCommandLine.Builder} in which to add a preprocessed
     *     representation of this arg
     * @param pathMapper mapper for exec paths
     * @return index in {@code arguments} where the next arg begins, or {@code arguments.size()} if
     *     this is the last argument
     */
    private int preprocess(
        List<Object> arguments,
        int argi,
        PreprocessedCommandLine.Builder builder,
        @Nullable ArtifactExpander artifactExpander,
        PathMapper pathMapper,
        @Nullable RepositoryMapping mainRepoMapping)
        throws CommandLineExpansionException, InterruptedException {
      StarlarkCallable mapEach = null;
      Location location = null;
      if ((features & HAS_MAP_EACH) != 0) {
        if (mapEachGlobalFunction != null) {
          mapEach = mapEachGlobalFunction;
        } else {
          mapEach = (StarlarkCallable) arguments.get(argi++);
        }
        location = (Location) arguments.get(argi++);
      }

      List<Object> originalValues;
      if ((features & IS_NESTED_SET) != 0) {
        @SuppressWarnings("unchecked")
        NestedSet<Object> nestedSet = (NestedSet<Object>) arguments.get(argi++);
        originalValues = nestedSet.toList();
      } else {
        int count = (features & HAS_SINGLE_ARG) != 0 ? 1 : (Integer) arguments.get(argi++);
        originalValues = arguments.subList(argi, argi + count);
        argi += count;
      }
      List<Object> expandedValues =
          maybeExpandDirectories(artifactExpander, originalValues, pathMapper);
      List<String> stringValues;
      if (mapEach != null) {
        stringValues = new ArrayList<>(expandedValues.size());
        applyMapEach(
            mapEach,
            expandedValues,
            stringValues::add,
            location,
            artifactExpander,
            pathMapper,
            starlarkSemantics);
      } else {
        int count = expandedValues.size();
        stringValues = new ArrayList<>(expandedValues.size());
        for (int i = 0; i < count; ++i) {
          stringValues.add(expandToCommandLine(expandedValues.get(i), pathMapper, mainRepoMapping));
        }
      }
      // It's safe to uniquify at this stage, any transformations after this
      // will ensure continued uniqueness of the values
      if ((features & UNIQUIFY) != 0) {
        int count = stringValues.size();
        HashSet<String> seen = Sets.newHashSetWithExpectedSize(count);
        int addIndex = 0;
        for (int i = 0; i < count; ++i) {
          String val = stringValues.get(i);
          if (seen.add(val)) {
            stringValues.set(addIndex++, val);
          }
        }
        stringValues = stringValues.subList(0, addIndex);
      }
      boolean omitIfEmpty = (features & OMIT_IF_EMPTY) != 0;
      boolean isEmptyAndShouldOmit = omitIfEmpty && stringValues.isEmpty();
      if ((features & HAS_ARG_NAME) != 0) {
        String argName = (String) arguments.get(argi++);
        if (!isEmptyAndShouldOmit) {
          builder.addString(argName);
        }
      }

      String formatEach = null;
      String beforeEach = null;
      String joinWith = null;
      String formatJoined = null;
      if ((features & HAS_FORMAT_EACH) != 0) {
        formatEach = (String) arguments.get(argi++);
      }
      if ((features & HAS_BEFORE_EACH) != 0) {
        beforeEach = (String) arguments.get(argi++);
      } else if ((features & HAS_JOIN_WITH) != 0) {
        joinWith = (String) arguments.get(argi++);
        if ((features & HAS_FORMAT_JOINED) != 0) {
          formatJoined = (String) arguments.get(argi++);
        }
      }

      // If !omitIfEmpty, joining yields a single argument even if stringValues is empty. Note that
      // the argument may still be non-empty if format_joined is used.
      if (!stringValues.isEmpty() || (!omitIfEmpty && joinWith != null)) {
        PreprocessedArg arg =
            joinWith != null
                ? new JoinedPreprocessedVectorArg(stringValues, formatEach, joinWith, formatJoined)
                : new UnjoinedPreprocessedVectorArg(stringValues, formatEach, beforeEach);
        builder.addPreprocessedArg(arg);
      }

      if ((features & HAS_TERMINATE_WITH) != 0) {
        String terminateWith = (String) arguments.get(argi++);
        if (!isEmptyAndShouldOmit) {
          builder.addString(terminateWith);
        }
      }
      return argi;
    }

    /**
     * Expands the directories if {@code expand_directories} feature is enabled and a
     * ArtifactExpander is available.
     *
     * <p>Technically, we should always expand the directories if the feature is requested, however
     * we cannot do that in the absence of the {@link ArtifactExpander}.
     */
    private List<Object> maybeExpandDirectories(
        @Nullable ArtifactExpander artifactExpander,
        List<Object> originalValues,
        PathMapper pathMapper)
        throws CommandLineExpansionException {
      if ((features & EXPAND_DIRECTORIES) == 0
          || artifactExpander == null
          || !hasDirectory(originalValues)) {
        return originalValues;
      }

      return expandDirectories(artifactExpander, originalValues, pathMapper);
    }

    private static boolean hasDirectory(List<Object> originalValues) {
      int n = originalValues.size();
      for (int i = 0; i < n; ++i) {
        Object object = originalValues.get(i);
        if (isDirectory(object)) {
          return true;
        }
      }
      return false;
    }

    private static boolean isDirectory(Object object) {
      return object instanceof Artifact artifact && artifact.isDirectory();
    }

    private static List<Object> expandDirectories(
        ArtifactExpander artifactExpander, List<Object> originalValues, PathMapper pathMapper)
        throws CommandLineExpansionException {
      List<Object> expandedValues = new ArrayList<>(originalValues.size());
      for (Object object : originalValues) {
        if (isDirectory(object)) {
          Artifact artifact = (Artifact) object;
          if (artifact.isTreeArtifact()) {
            try {
              expandedValues.addAll(artifactExpander.expandTreeArtifact(artifact));
            } catch (MissingExpansionException e) {
              throw new CommandLineExpansionException(
                  String.format(
                      "Failed to expand directory %s. Either add the directory as an input of the"
                          + " action or set 'expand_directories = False' in the 'add_all' or"
                          + " 'add_joined' call to have the path of the directory added to the"
                          + " command line instead of its contents.",
                      Starlark.repr(artifact)),
                  e);
            }
          } else if (artifact.isFileset()) {
            expandFileset(artifactExpander, artifact, expandedValues, pathMapper);
          } else {
            throw new AssertionError("Unknown artifact type.");
          }
        } else {
          expandedValues.add(object);
        }
      }
      return expandedValues;
    }

    private static void expandFileset(
        ArtifactExpander artifactExpander,
        Artifact fileset,
        List<Object> expandedValues,
        PathMapper pathMapper)
        throws CommandLineExpansionException {
      FilesetOutputTree filesetOutput;
      try {
        filesetOutput = artifactExpander.expandFileset(fileset);
      } catch (MissingExpansionException e) {
        throw new CommandLineExpansionException(
            String.format(
                "Could not expand fileset: %s. Did you forget to add it as an input of the action?",
                fileset),
            e);
      }
      PathFragment mappedExecPath = pathMapper.map(fileset.getExecPath());
      filesetOutput.visitSymlinks(
          RelativeSymlinkBehaviorWithoutError.IGNORE,
          (name, target, metadata) ->
              expandedValues.add(
                  new FilesetSymlinkFile(fileset, mappedExecPath.getRelative(name))));
    }


    private int addToFingerprint(
        List<Object> arguments,
        int argi,
        ActionKeyContext actionKeyContext,
        Fingerprint fingerprint,
        @Nullable ArtifactExpander artifactExpander,
        CoreOptions.OutputPathsMode outputPathsMode)
        throws CommandLineExpansionException, InterruptedException {
      StarlarkCallable mapEach = null;
      Location location = null;
      if ((features & HAS_MAP_EACH) != 0) {
        if (mapEachGlobalFunction != null) {
          mapEach = mapEachGlobalFunction;
        } else {
          mapEach = (StarlarkCallable) arguments.get(argi++);
        }
        location = (Location) arguments.get(argi++);
      }

      // NestedSets and lists never result in the same fingerprint as the
      // ActionKeyContext#addNestedSetToFingerprint call below always adds the order of the
      // NestedSet to the fingerprint.
      //
      // Path mapping may affect the default stringification of Artifact instances at execution
      // time, but the effect of path mapping on an individual command line element is a pure
      // function of:
      // * whether the element is of type Artifact (FileApi in Starlark), which is fingerprinted
      //   via the elementType UUID below;
      // * the path of the artifact, which is fingerprinted via its default string representation
      //   below;
      // * the paths and possibly the digests of all input artifacts as well as the path mapping
      //   mode, which are fingerprinted by SpawnAction.
      // It is thus safe to ignore pathMapper below for anything that relies on the default
      // stringification behavior (which excludes custom mapEach functions).
      if ((features & IS_NESTED_SET) != 0) {
        NestedSet<?> values = (NestedSet<?>) arguments.get(argi++);
        if (mapEach != null) {
          // mapEach functions do not rely on default stringification behavior, so we can omit
          // fingerprinting stringificationType here.
          CommandLineItemMapEachAdaptor commandLineItemMapFn =
              new CommandLineItemMapEachAdaptor(
                  mapEach,
                  location,
                  starlarkSemantics,
                  (features & EXPAND_DIRECTORIES) != 0 ? artifactExpander : null,
                  outputPathsMode);
          try {
            actionKeyContext.addNestedSetToFingerprint(commandLineItemMapFn, fingerprint, values);
          } finally {
            // The cache holds an entry for a NestedSet for every (map_fn, hasArtifactExpanderBit,
            // pathMapperCacheKey).
            // Clearing the artifactExpander itself saves us from storing the contents of it in the
            // cache keys (it is no longer needed after we evaluate the value).
            // NestedSet cache is cleared after every build, which means that the artifactExpander
            // for a given action, if present, cannot change within the lifetime of the fingerprint
            // cache (we call getKey with artifactExpander to check action key, when we are ready to
            // execute the action in case of a cache miss).
            commandLineItemMapFn.clearArtifactExpander();
          }
        } else {
          fingerprint.addInt(stringificationType.ordinal());
          actionKeyContext.addNestedSetToFingerprint(fingerprint, values);
        }
      } else {
        int count = (features & HAS_SINGLE_ARG) != 0 ? 1 : (Integer) arguments.get(argi++);
        List<Object> maybeExpandedValues =
            maybeExpandDirectories(
                artifactExpander,
                arguments.subList(argi, argi + count),
                PathMapper.forActionKey(outputPathsMode));
        argi += count;
        if (mapEach != null) {
          // TODO(b/160181927): If artifactExpander == null (which happens in the analysis phase)
          // but expandDirectories is true, we run the map_each function on directory values without
          // actually expanding them. This differs from the real evaluation behavior. This means
          // that we can erroneously produce the same digest for two command lines that differ only
          // in their directory expansion. Fortunately, this is only a problem for shared action
          // conflict checking/aquery result, since at execution time we have an artifactExpander.
          applyMapEach(
              mapEach,
              maybeExpandedValues,
              fingerprint::addString,
              location,
              artifactExpander,
              PathMapper.forActionKey(outputPathsMode),
              starlarkSemantics);
        } else {
          for (Object value : maybeExpandedValues) {
            addSingleObjectToFingerprint(fingerprint, value);
          }
        }
      }
      if ((features & EXPAND_DIRECTORIES) != 0) {
        fingerprint.addUUID(EXPAND_DIRECTORIES_UUID);
      }
      if ((features & UNIQUIFY) != 0) {
        fingerprint.addUUID(UNIQUIFY_UUID);
      }
      if ((features & OMIT_IF_EMPTY) != 0) {
        fingerprint.addUUID(OMIT_IF_EMPTY_UUID);
      }
      if ((features & HAS_ARG_NAME) != 0) {
        String argName = (String) arguments.get(argi++);
        fingerprint.addUUID(ARG_NAME_UUID);
        fingerprint.addString(argName);
      }
      if ((features & HAS_FORMAT_EACH) != 0) {
        String formatStr = (String) arguments.get(argi++);
        fingerprint.addUUID(FORMAT_EACH_UUID);
        fingerprint.addString(formatStr);
      }
      if ((features & HAS_BEFORE_EACH) != 0) {
        String beforeEach = (String) arguments.get(argi++);
        fingerprint.addUUID(BEFORE_EACH_UUID);
        fingerprint.addString(beforeEach);
      } else if ((features & HAS_JOIN_WITH) != 0) {
        String joinWith = (String) arguments.get(argi++);
        fingerprint.addUUID(JOIN_WITH_UUID);
        fingerprint.addString(joinWith);
        if ((features & HAS_FORMAT_JOINED) != 0) {
          String formatJoined = (String) arguments.get(argi++);
          fingerprint.addUUID(FORMAT_JOINED_UUID);
          fingerprint.addString(formatJoined);
        }
      }
      if ((features & HAS_TERMINATE_WITH) != 0) {
        String terminateWith = (String) arguments.get(argi++);
        fingerprint.addUUID(TERMINATE_WITH_UUID);
        fingerprint.addString(terminateWith);
      }
      return argi;
    }

    static final class Builder {
      @Nullable private final Sequence<?> list;
      @Nullable private final NestedSet<?> nestedSet;
      private final StringificationType nestedSetStringificationType;
      private Location location;
      private String argName;
      private boolean expandDirectories;
      private StarlarkCallable mapEach;
      private String formatEach;
      private String beforeEach;
      private String joinWith;
      private String formatJoined;
      private boolean omitIfEmpty;
      private boolean uniquify;
      private String terminateWith;

      Builder(Sequence<?> list) {
        this.list = list;
        this.nestedSet = null;
        this.nestedSetStringificationType = StringificationType.DEFAULT;
      }

      Builder(NestedSet<?> nestedSet, Class<?> nestedSetElementType) {
        this.list = null;
        this.nestedSet = nestedSet;
        if (nestedSetElementType == FileApi.class) {
          this.nestedSetStringificationType = StringificationType.FILE;
        } else if (nestedSetElementType == Label.class) {
          this.nestedSetStringificationType = StringificationType.LABEL;
        } else {
          this.nestedSetStringificationType = StringificationType.DEFAULT;
        }
      }

      @CanIgnoreReturnValue
      Builder setLocation(Location location) {
        this.location = location;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setArgName(String argName) {
        this.argName = argName;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setExpandDirectories(boolean expandDirectories) {
        this.expandDirectories = expandDirectories;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setMapEach(StarlarkCallable mapEach) {
        this.mapEach = mapEach;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setFormatEach(String format) {
        this.formatEach = format;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setBeforeEach(String beforeEach) {
        this.beforeEach = beforeEach;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setJoinWith(String joinWith) {
        this.joinWith = joinWith;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setFormatJoined(String formatJoined) {
        this.formatJoined = formatJoined;
        return this;
      }

      @CanIgnoreReturnValue
      Builder omitIfEmpty(boolean omitIfEmpty) {
        this.omitIfEmpty = omitIfEmpty;
        return this;
      }

      @CanIgnoreReturnValue
      Builder uniquify(boolean uniquify) {
        this.uniquify = uniquify;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setTerminateWith(String terminateWith) {
        this.terminateWith = terminateWith;
        return this;
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof VectorArg that)) {
        return false;
      }
      return features == that.features
          && stringificationType.equals(that.stringificationType)
          && Objects.equals(starlarkSemantics, that.starlarkSemantics)
          // Use reference equality to avoid resurrecting a weakly-reachable but value-equal
          // StarlarkFunction instance. Value-equal instances may be created when a .bzl file is
          // re-evaluated.
          && mapEachGlobalFunction == that.mapEachGlobalFunction;
    }

    @Override
    public int hashCode() {
      int result = HashCodes.hashObjects(stringificationType, starlarkSemantics);
      result = 31 * result + Integer.hashCode(features);
      result = 31 * result + System.identityHashCode(mapEachGlobalFunction);
      return result;
    }
  }

  /** Representation of a single formatted argument originating from {@code Args.add} */
  private static final class SingleFormattedArg {

    /** Denotes that the following two elements are an object and format string. */
    private static final Object MARKER =
        new Object() {
          @Override
          public String toString() {
            return "SINGLE_FORMATTED_ARG_MARKER";
          }
        };

    private static final UUID SINGLE_FORMATTED_ARG_UUID =
        UUID.fromString("8cb96642-a235-4fe0-b3ed-ebfdae8a0bd9");

    static void push(List<Object> arguments, Object object, String format) {
      arguments.add(MARKER);
      arguments.add(object);
      arguments.add(format);
    }

    /**
     * Adds a {@link SingleFormattedArg} to the given {@link PreprocessedCommandLine.Builder}.
     *
     * @param arguments result of {@link #rawArgsAsList}
     * @param argi index in {@code arguments} at which the {@link SingleFormattedArg} begins; should
     *     be directly preceded by {@link #MARKER}
     * @param builder the {@link PreprocessedCommandLine.Builder} in which to add a preprocessed
     *     representation of this arg
     * @param pathMapper mapper for exec paths
     * @param mainRepoMapping the repository mapping to use for formatting labels if needed
     * @return index in {@code arguments} where the next arg begins, or {@code arguments.size()} if
     *     there are no more arguments
     */
    static int preprocess(
        List<Object> arguments,
        int argi,
        PreprocessedCommandLine.Builder builder,
        PathMapper pathMapper,
        @Nullable RepositoryMapping mainRepoMapping) {
      Object object = arguments.get(argi++);
      String formatStr = (String) arguments.get(argi++);
      String stringValue =
          StarlarkCustomCommandLine.expandToCommandLine(object, pathMapper, mainRepoMapping);
      builder.addPreprocessedArg(new PreprocessedSingleFormattedArg(formatStr, stringValue));
      return argi;
    }

    static int addToFingerprint(List<Object> arguments, int argi, Fingerprint fingerprint) {
      Object object = arguments.get(argi++);
      addSingleObjectToFingerprint(fingerprint, object);
      String formatStr = (String) arguments.get(argi++);
      fingerprint.addString(formatStr);
      fingerprint.addUUID(SINGLE_FORMATTED_ARG_UUID);
      return argi;
    }
  }

  static final class Builder {
    private final StarlarkSemantics starlarkSemantics;
    private final List<Object> arguments = new ArrayList<>();
    // Indexes in arguments list where individual args begin
    private final ImmutableList.Builder<Integer> argStartIndexes = ImmutableList.builder();

    public Builder(StarlarkSemantics starlarkSemantics) {
      this.starlarkSemantics = checkNotNull(starlarkSemantics);
    }

    @CanIgnoreReturnValue
    Builder recordArgStart() {
      if (!arguments.isEmpty()) {
        argStartIndexes.add(arguments.size());
      }
      return this;
    }

    @CanIgnoreReturnValue
    Builder add(Object object) {
      arguments.add(object);
      return this;
    }

    @CanIgnoreReturnValue
    Builder add(VectorArg.Builder vectorArg) {
      VectorArg.push(arguments, vectorArg, starlarkSemantics);
      return this;
    }

    @CanIgnoreReturnValue
    Builder addFormatted(Object object, String format) {
      checkNotNull(object);
      checkNotNull(format);
      SingleFormattedArg.push(arguments, object, format);
      return this;
    }

    CommandLine build(boolean flagPerLine, @Nullable RepositoryMapping mainRepoMapping) {
      if (arguments.isEmpty()) {
        return CommandLine.empty();
      }
      Object[] args;
      if (mainRepoMapping != null) {
        args = arguments.toArray(new Object[arguments.size() + 1]);
        args[arguments.size()] = mainRepoMapping;
      } else {
        args = arguments.toArray();
      }
      return flagPerLine
          ? new StarlarkCustomCommandLineWithIndexes(args, argStartIndexes.build())
          : new StarlarkCustomCommandLine(args);
    }
  }

  /**
   * Stored as an {@code Object[]} instead of an {@link ImmutableList} to save memory, but is never
   * modified. Access via {@link #rawArgsAsList} for an unmodifiable {@link List} view.
   */
  private final Object[] arguments;

  private StarlarkCustomCommandLine(Object[] arguments) {
    this.arguments = arguments;
  }

  /** Wraps {@link #arguments} in an unmodifiable {@link List} view. */
  private List<Object> rawArgsAsList() {
    return Collections.unmodifiableList(Arrays.asList(arguments));
  }

  @Override
  public final ArgChunk expand() throws CommandLineExpansionException, InterruptedException {
    return expand(null, PathMapper.NOOP);
  }

  @Override
  public ArgChunk expand(@Nullable ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    PreprocessedCommandLine.Builder builder = new PreprocessedCommandLine.Builder();
    List<Object> arguments = rawArgsAsList();

    RepositoryMapping mainRepoMapping;
    int size;
    // Added in #build() if any labels in the command line require this to be formatted with an
    // apparent repository name.
    if (arguments.getLast() instanceof RepositoryMapping) {
      mainRepoMapping = (RepositoryMapping) arguments.getLast();
      size = arguments.size() - 1;
    } else {
      mainRepoMapping = null;
      size = arguments.size();
    }

    for (int argi = 0; argi < size; ) {
      Object arg = arguments.get(argi++);
      if (arg instanceof VectorArg) {
        argi =
            ((VectorArg) arg)
                .preprocess(
                    arguments, argi, builder, artifactExpander, pathMapper, mainRepoMapping);
      } else if (arg == SingleFormattedArg.MARKER) {
        argi = SingleFormattedArg.preprocess(arguments, argi, builder, pathMapper, mainRepoMapping);
      } else {
        builder.addString(expandToCommandLine(arg, pathMapper, mainRepoMapping));
      }
    }
    return pathMapper.mapCustomStarlarkArgs(builder.build());
  }

  @Override
  public final Iterable<String> arguments()
      throws CommandLineExpansionException, InterruptedException {
    return expand().arguments();
  }

  @Override
  public final Iterable<String> arguments(ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    return expand(artifactExpander, pathMapper).arguments();
  }

  private static String expandToCommandLine(
      Object object, PathMapper pathMapper, @Nullable RepositoryMapping mainRepoMapping) {
    if (mainRepoMapping != null && object instanceof Label label) {
      return label.getDisplayForm(mainRepoMapping);
    }

    // It'd be nice to build this into DerivedArtifact's CommandLine interface so we don't have
    // to explicitly check if an object is a DerivedArtifact. Unfortunately that would require
    // a lot more dependencies on the Java library DerivedArtifact is built into.
    return object instanceof DerivedArtifact derivedArtifact
        ? pathMapper.map(derivedArtifact.getExecPath()).getPathString()
        : CommandLineItem.expandToCommandLine(object);
  }

  private static void addSingleObjectToFingerprint(Fingerprint fingerprint, Object object) {
    StringificationType stringificationType =
        switch (object) {
          case FileApi ignored -> StringificationType.FILE;
          case Label ignored -> StringificationType.LABEL;
          default -> StringificationType.DEFAULT;
        };
    fingerprint.addInt(stringificationType.ordinal());
    fingerprint.addString(CommandLineItem.expandToCommandLine(object));
  }

  private static class StarlarkCustomCommandLineWithIndexes extends StarlarkCustomCommandLine {
    /**
     * An extra level of grouping on top of the 'arguments' list. Each element is the start of a
     * group of args, with index 0 omitted. For example, if this contains 3, then arguments 0, 1 and
     * 2 constitute the first group, and arguments 3 to the end constitute the next. The expanded
     * version of these arguments will be concatenated together to support {@code flag_per_line}
     * format.
     */
    private final ImmutableList<Integer> argStartIndexes;

    StarlarkCustomCommandLineWithIndexes(
        Object[] arguments, ImmutableList<Integer> argStartIndexes) {
      super(arguments);
      this.argStartIndexes = argStartIndexes;
    }

    @Override
    public ArgChunk expand(@Nullable ArtifactExpander artifactExpander, PathMapper pathMapper)
        throws CommandLineExpansionException, InterruptedException {
      PreprocessedCommandLine.Builder builder = new PreprocessedCommandLine.Builder();
      List<Object> arguments = ((StarlarkCustomCommandLine) this).rawArgsAsList();
      Iterator<Integer> startIndexIterator = argStartIndexes.iterator();

      RepositoryMapping mainRepoMapping;
      int size;
      if (arguments.getLast() instanceof RepositoryMapping) {
        mainRepoMapping = (RepositoryMapping) arguments.getLast();
        size = arguments.size() - 1;
      } else {
        mainRepoMapping = null;
        size = arguments.size();
      }

      for (int argi = 0; argi < size; ) {
        int nextStartIndex = startIndexIterator.hasNext() ? startIndexIterator.next() : size;
        PreprocessedCommandLine.Builder line = new PreprocessedCommandLine.Builder();

        while (argi < nextStartIndex) {
          Object arg = arguments.get(argi++);
          if (arg instanceof VectorArg) {
            argi =
                ((VectorArg) arg)
                    .preprocess(
                        arguments, argi, line, artifactExpander, pathMapper, mainRepoMapping);
          } else if (arg == SingleFormattedArg.MARKER) {
            argi =
                SingleFormattedArg.preprocess(arguments, argi, line, pathMapper, mainRepoMapping);
          } else {
            line.addString(expandToCommandLine(arg, pathMapper, mainRepoMapping));
          }
        }

        builder.addLineForFlagPerLine(line);
      }

      return pathMapper.mapCustomStarlarkArgs(builder.build());
    }
  }

  @Override
  public void addToFingerprint(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      CoreOptions.OutputPathsMode effectiveOutputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException {
    List<Object> arguments = rawArgsAsList();
    int size;
    if (arguments.getLast() instanceof RepositoryMapping mainRepoMapping) {
      fingerprint.addStringMap(
          Maps.transformValues(mainRepoMapping.entries(), RepositoryName::getName));
      size = arguments.size() - 1;
    } else {
      size = arguments.size();
    }
    for (int argi = 0; argi < size; ) {
      Object arg = arguments.get(argi++);
      if (arg instanceof VectorArg) {
        argi =
            ((VectorArg) arg)
                .addToFingerprint(
                    arguments,
                    argi,
                    actionKeyContext,
                    fingerprint,
                    artifactExpander,
                    effectiveOutputPathsMode);
      } else if (arg == SingleFormattedArg.MARKER) {
        argi = SingleFormattedArg.addToFingerprint(arguments, argi, fingerprint);
      } else {
        addSingleObjectToFingerprint(fingerprint, arg);
      }
    }
  }

  /** Used during action key evaluation when we don't have an artifact expander. */
  private static class NoopExpander implements DirectoryExpander {
    @Override
    public ImmutableList<FileApi> list(FileApi file) {
      return ImmutableList.of(file);
    }

    static final DirectoryExpander INSTANCE = new NoopExpander();
  }

  private static final class FullExpander implements DirectoryExpander {
    private final ArtifactExpander expander;

    FullExpander(ArtifactExpander expander) {
      this.expander = expander;
    }

    @Override
    public ImmutableList<FileApi> list(FileApi file) throws EvalException {
      Artifact artifact = (Artifact) file;
      if (artifact.isTreeArtifact()) {
        try {
          return ImmutableList.copyOf(expander.expandTreeArtifact(artifact));
        } catch (MissingExpansionException unused) {
          throw Starlark.errorf(
              "Failed to expand directory %s. Only directories that are action inputs can be"
                  + " expanded.",
              Starlark.repr(artifact));
        }
      } else {
        return ImmutableList.of(file);
      }
    }
  }

  private static void applyMapEach(
      StarlarkCallable mapFn,
      List<Object> originalValues,
      Consumer<String> consumer,
      Location loc,
      @Nullable ArtifactExpander artifactExpander,
      PathMapper pathMapper,
      StarlarkSemantics starlarkSemantics)
      throws CommandLineExpansionException, InterruptedException {
    try (Mutability mu = Mutability.create("map_each")) {
      // This computation produces only a String list, which doesn't require reference semantics,
      // so createTransient() is safe.
      StarlarkThread thread =
          StarlarkThread.createTransient(mu, pathMapper.storeIn(starlarkSemantics));
      // TODO(b/77140311): Error if we issue print statements.
      thread.setPrintHandler((th, msg) -> {});
      int count = originalValues.size();
      // map_each can accept either each object, or each object + a directory expander.
      boolean wantsDirectoryExpander =
          mapFn instanceof StarlarkFunction starlarkFunction
              && starlarkFunction.getParameterNames().size() >= 2;
      // We create a list that we reuse for the args to map_each
      List<Object> args = new ArrayList<>(2);
      args.add(null); // This will be overwritten each iteration.
      if (wantsDirectoryExpander) {
        DirectoryExpander expander;
        if (artifactExpander != null) {
          expander = new FullExpander(artifactExpander);
        } else {
          expander = NoopExpander.INSTANCE;
        }
        args.add(expander); // This will remain constant each iteration
      }
      for (int i = 0; i < count; ++i) {
        args.set(0, originalValues.get(i));
        Object ret = Starlark.call(thread, mapFn, args, /* kwargs= */ ImmutableMap.of());
        if (ret instanceof String string) {
          consumer.accept(string);
        } else if (ret instanceof Sequence<?> sequence) {
          for (Object val : sequence) {
            if (!(val instanceof String)) {
              throw new CommandLineExpansionException(
                  "Expected map_each to return string, None, or list of strings, "
                      + "found list containing "
                      + Starlark.type(val));
            }
            consumer.accept((String) val);
          }
        } else if (ret != Starlark.NONE) {
          throw new CommandLineExpansionException(
              "Expected map_each to return string, None, or list of strings, found "
                  + Starlark.type(ret));
        }
      }
    } catch (EvalException e) {
      // TODO(adonovan): consider calling a wrapper function to interpose a fake stack
      // frame that establishes the args.add_all call at loc. Or manipulating the stack
      // before printing it.
      throw new CommandLineExpansionException(
          errorMessage(e.getMessageWithStack(), loc, e.getCause()));
    }
  }

  private static class CommandLineItemMapEachAdaptor
      extends CommandLineItem.ParametrizedMapFn<Object> {
    private final StarlarkCallable mapFn;
    private final Location location;
    private final StarlarkSemantics starlarkSemantics;

    /**
     * Indicates whether artifactExpander was provided on construction. This is used to distinguish
     * the case where it's not provided from the case where it was provided but subsequently
     * cleared.
     */
    private final boolean hasArtifactExpander;

    private final CoreOptions.OutputPathsMode outputPathsMode;

    @Nullable private ArtifactExpander artifactExpander;

    CommandLineItemMapEachAdaptor(
        StarlarkCallable mapFn,
        Location location,
        StarlarkSemantics starlarkSemantics,
        @Nullable ArtifactExpander artifactExpander,
        CoreOptions.OutputPathsMode outputPathsMode) {
      this.mapFn = mapFn;
      this.location = location;
      this.starlarkSemantics = starlarkSemantics;
      this.hasArtifactExpander = artifactExpander != null;
      this.artifactExpander = artifactExpander;
      this.outputPathsMode = outputPathsMode;
    }

    @Override
    public void expandToCommandLine(Object object, Consumer<String> args)
        throws CommandLineExpansionException, InterruptedException {
      checkState(artifactExpander != null || !hasArtifactExpander);
      applyMapEach(
          mapFn,
          maybeExpandDirectory(object),
          args,
          location,
          artifactExpander,
          PathMapper.forActionKey(outputPathsMode),
          starlarkSemantics);
    }

    private List<Object> maybeExpandDirectory(Object object) throws CommandLineExpansionException {
      if (artifactExpander == null || !VectorArg.isDirectory(object)) {
        return ImmutableList.of(object);
      }

      return VectorArg.expandDirectories(
          artifactExpander, ImmutableList.of(object), PathMapper.forActionKey(outputPathsMode));
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof CommandLineItemMapEachAdaptor other)) {
        return false;
      }
      // Instance compare intentional
      // The normal implementation uses location + name of function,
      // which can conceivably conflict in tests
      // We only compare presence of artifactExpander vs absence of it since the nestedset
      // fingerprint cache is emptied after every build, therefore if the artifact expander is
      // provided, it will be the same.
      return mapFn == other.mapFn
          && hasArtifactExpander == other.hasArtifactExpander
          && outputPathsMode == other.outputPathsMode;
    }

    @Override
    public int hashCode() {
      // Force use of identityHashCode, in case the callable uses a custom hash function. (As of
      // this writing, only providers seem to have a custom hashCode, and those shouldn't be used
      // as map_each functions, but doesn't hurt to be safe...).
      return outputPathsMode.hashCode()
          + 31
              * (Boolean.hashCode(hasArtifactExpander) + 31 * (System.identityHashCode(mapFn) + 1));
    }

    @Override
    public int maxInstancesAllowed() {
      // No limit to these, as this is just a wrapper for Starlark functions, which are
      // always static
      return Integer.MAX_VALUE;
    }

    /**
     * Clears the artifact expander in order not to prolong the lifetime of it unnecessarily.
     *
     * <p>Although this operation technically changes this object, it can be called after we add the
     * object to a {@link HashSet}. Clearing the artifactExpander does not affect the result of
     * {@link #equals} or {@link #hashCode}. Please note that once we call this function, we can no
     * longer call {@link #expandToCommandLine}.
     */
    void clearArtifactExpander() {
      artifactExpander = null;
    }
  }

  private static String errorMessage(
      String message, @Nullable Location location, @Nullable Throwable cause) {
    return LINE_JOINER.join(
        "\n", FIELD_JOINER.join(location, message), getCauseMessage(cause, message));
  }

  @Nullable
  private static String getCauseMessage(@Nullable Throwable cause, String message) {
    if (cause == null) {
      return null;
    }
    String causeMessage = cause.getMessage();
    if (causeMessage == null) {
      return null;
    }
    if (message == null) {
      return causeMessage;
    }
    // Skip the cause if it is redundant with the message so far.
    if (message.contains(causeMessage)) {
      return null;
    }
    return causeMessage;
  }

  /**
   * When we expand filesets the user might still expect a File object (since the results may be fed
   * into map_each. Therefore we synthesize a File object from the fileset symlink.
   */
  static class FilesetSymlinkFile implements FileApi, CommandLineItem {
    private final Artifact fileset;
    private final PathFragment execPath;

    FilesetSymlinkFile(Artifact fileset, PathFragment execPath) {
      this.fileset = fileset;
      this.execPath = execPath;
    }

    @Override
    public String getDirnameForStarlark(StarlarkSemantics semantics) {
      PathFragment parent = execPath.getParentDirectory();
      return (parent == null) ? "/" : parent.getSafePathString();
    }

    @Override
    public String getFilename() {
      return execPath.getBaseName();
    }

    @Override
    public String getExtension() {
      return execPath.getFileExtension();
    }

    @Override
    public Label getOwnerLabel() {
      return fileset.getOwnerLabel();
    }

    @Override
    public FileRootApi getRootForStarlark(StarlarkSemantics semantics) {
      return fileset.getRoot();
    }

    @Override
    public boolean isSourceArtifact() {
      // This information is lost to us.
      // Since the symlinks are always in the output tree, settle for saying "no"
      return false;
    }

    @Override
    public boolean isDirectory() {
      return false;
    }

    @Override
    public boolean isSymlink() {
      return false;
    }

    @Override
    public String getRunfilesPathString() {
      PathFragment relativePath = execPath.relativeTo(fileset.getExecPath());
      return fileset.getRunfilesPath().getRelative(relativePath).getPathString();
    }

    @Override
    public String getExecPathStringForStarlark(StarlarkSemantics semantics) {
      return execPath.getPathString();
    }

    @Override
    public String getTreeRelativePathString() throws EvalException {
      throw Starlark.errorf(
          "tree_relative_path not allowed for files that are not tree artifact files.");
    }

    @Override
    public String expandToCommandLine() {
      return execPath.getPathString();
    }

    @Override
    public void repr(Printer printer) {
      if (isSourceArtifact()) {
        printer.append("<source file " + getRunfilesPathString() + ">");
      } else {
        printer.append("<generated file " + getRunfilesPathString() + ">");
      }
    }
  }

  /** An element in a {@link PreprocessedCommandLine}. */
  private interface PreprocessedArg extends Iterable<String> {
    int numArgs();

    int totalArgLength();
  }

  /**
   * Intermediate command line representation with directory expansion and {@code map_each} already
   * applied, but with string formatting not yet applied. See {@link StarlarkCustomCommandLine}
   * class-level documentation for details.
   *
   * <p>Implements {@link #totalArgLength} without applying string formatting so that the total
   * command line length can be efficiently tested against {@link CommandLineLimits} and param file
   * thresholds.
   */
  private static final class PreprocessedCommandLine implements ArgChunk {
    private final ImmutableList<PreprocessedArg> preprocessedArgs;

    PreprocessedCommandLine(ImmutableList<PreprocessedArg> preprocessedArgs) {
      this.preprocessedArgs = preprocessedArgs;
    }

    @Override
    public Iterable<String> arguments() {
      return Iterables.concat(preprocessedArgs);
    }

    @Override
    public int totalArgLength() {
      int total = 0;
      for (PreprocessedArg arg : preprocessedArgs) {
        total += arg.totalArgLength();
      }
      return total;
    }

    static final class Builder {
      private final ImmutableList.Builder<PreprocessedArg> preprocessedArgs =
          ImmutableList.builder();
      private int numArgs = 0;

      void addPreprocessedArg(PreprocessedArg arg) {
        preprocessedArgs.add(arg);
        numArgs += arg.numArgs();
      }

      void addString(String arg) {
        addPreprocessedArg(new PreprocessedStringArg(arg));
      }

      void addLineForFlagPerLine(PreprocessedCommandLine.Builder line) {
        ImmutableList<PreprocessedArg> group = line.preprocessedArgs.build();
        if (line.numArgs < 2) {
          for (PreprocessedArg arg : group) {
            addPreprocessedArg(arg);
          }
        } else {
          addPreprocessedArg(new GroupedPreprocessedArgs(group));
        }
      }

      PreprocessedCommandLine build() {
        return new PreprocessedCommandLine(preprocessedArgs.build());
      }
    }
  }

  /** Preprocessed version a single string argument. */
  private static final class PreprocessedStringArg implements PreprocessedArg {
    private final String arg;

    PreprocessedStringArg(String arg) {
      this.arg = arg;
    }

    @Override
    public Iterator<String> iterator() {
      return Iterators.singletonIterator(arg);
    }

    @Override
    public int numArgs() {
      return 1;
    }

    @Override
    public int totalArgLength() {
      return arg.length() + 1;
    }
  }

  /** Preprocessed version of a {@link SingleFormattedArg}. */
  private static final class PreprocessedSingleFormattedArg implements PreprocessedArg {
    private final String format;
    private final String stringValue;

    PreprocessedSingleFormattedArg(String format, String stringValue) {
      this.format = format;
      this.stringValue = stringValue;
    }

    @Override
    public Iterator<String> iterator() {
      return Iterators.singletonIterator(SingleStringArgFormatter.format(format, stringValue));
    }

    @Override
    public int numArgs() {
      return 1;
    }

    @Override
    public int totalArgLength() {
      return SingleStringArgFormatter.formattedLength(format) + stringValue.length() + 1;
    }
  }

  /** Preprocessed version of a {@link VectorArg} originating from {@code Args.add_all}. */
  private static final class UnjoinedPreprocessedVectorArg implements PreprocessedArg {
    private final List<String> stringValues;
    @Nullable private final String formatEach;
    @Nullable private final String beforeEach;

    UnjoinedPreprocessedVectorArg(
        List<String> stringValues, @Nullable String formatEach, @Nullable String beforeEach) {
      this.stringValues = stringValues;
      this.formatEach = formatEach;
      this.beforeEach = beforeEach;
    }

    @Override
    public Iterator<String> iterator() {
      Iterator<String> it = stringValues.iterator();
      if (formatEach != null) {
        it = Iterators.transform(it, s -> SingleStringArgFormatter.format(formatEach, s));
      }
      if (beforeEach != null) {
        it = new BeforeEachIterator(it, beforeEach);
      }
      return it;
    }

    @Override
    public int numArgs() {
      return (beforeEach != null ? 2 : 1) * stringValues.size();
    }

    @Override
    public int totalArgLength() {
      int total = 0;
      for (String arg : stringValues) {
        total += arg.length();
      }
      if (formatEach != null) {
        total += SingleStringArgFormatter.formattedLength(formatEach) * stringValues.size();
      }
      if (beforeEach != null) {
        total += beforeEach.length() * stringValues.size();
      }
      return total + numArgs();
    }
  }

  /** Preprocessed version of a {@link VectorArg} originating from {@code Args.add_joined}. */
  private static final class JoinedPreprocessedVectorArg implements PreprocessedArg {
    private final List<String> stringValues;
    @Nullable private final String formatEach;
    private final String joinWith;
    @Nullable private final String formatJoined;

    JoinedPreprocessedVectorArg(
        List<String> stringValues,
        @Nullable String formatEach,
        String joinWith,
        @Nullable String formatJoined) {
      this.stringValues = stringValues;
      this.formatEach = formatEach;
      this.joinWith = joinWith;
      this.formatJoined = formatJoined;
    }

    @Override
    public Iterator<String> iterator() {
      Iterator<String> it = stringValues.iterator();
      if (formatEach != null) {
        it = Iterators.transform(it, s -> SingleStringArgFormatter.format(formatEach, s));
      }
      String result = Joiner.on(joinWith).join(it);
      if (formatJoined != null) {
        result = SingleStringArgFormatter.format(formatJoined, result);
      }
      return Iterators.singletonIterator(result);
    }

    @Override
    public int numArgs() {
      return 1;
    }

    @Override
    public int totalArgLength() {
      int total = 0;
      for (String arg : stringValues) {
        total += arg.length();
      }
      if (formatEach != null) {
        total += SingleStringArgFormatter.formattedLength(formatEach) * stringValues.size();
      }
      if (stringValues.size() > 1) {
        total += joinWith.length() * (stringValues.size() - 1);
      }
      if (formatJoined != null) {
        total += SingleStringArgFormatter.formattedLength(formatJoined);
      }
      return total + 1;
    }
  }

  /** Preprocessed representation of a single line in {@code flag_per_line} format. */
  private static final class GroupedPreprocessedArgs implements PreprocessedArg {
    private static final Joiner SPACE_JOINER = Joiner.on(' ');

    private final ImmutableList<PreprocessedArg> args;

    GroupedPreprocessedArgs(ImmutableList<PreprocessedArg> args) {
      this.args = args;
    }

    @Override
    public Iterator<String> iterator() {
      Iterator<String> it = Iterables.concat(args).iterator();
      String first = it.next();
      String rest = SPACE_JOINER.join(it);
      String line = first.isEmpty() ? rest : first + '=' + rest;
      return Iterators.singletonIterator(line);
    }

    @Override
    public int numArgs() {
      return 1;
    }

    @Override
    public int totalArgLength() {
      int total = 0;
      for (PreprocessedArg arg : args) {
        total += arg.totalArgLength();
      }
      String first = Iterables.concat(args).iterator().next();
      if (first.isEmpty()) {
        total--;
      }
      return total;
    }
  }

  /** Implements the {@code before_each} behavior of {@code Args.add_all}. */
  private static final class BeforeEachIterator extends UnmodifiableIterator<String> {
    private final Iterator<String> strings;
    private final String beforeEach;
    private boolean before = true;

    BeforeEachIterator(Iterator<String> strings, String beforeEach) {
      this.strings = strings;
      this.beforeEach = beforeEach;
    }

    @Override
    public boolean hasNext() {
      return strings.hasNext();
    }

    @Override
    public String next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      String next = before ? beforeEach : strings.next();
      before = !before;
      return next;
    }
  }
}
