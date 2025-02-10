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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.AbstractCommandLine;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.actions.CommandLineItem.ExceptionlessMapFn;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.SingleStringArgFormatter;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OnDemandString;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CompileTimeConstant;
import com.google.errorprone.annotations.ForOverride;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** A customizable, serializable class for building memory efficient command lines. */
@Immutable
public class CustomCommandLine extends AbstractCommandLine {
  private interface ArgvFragment {
    /**
     * Expands this fragment into the passed command line vector.
     *
     * @param arguments The command line's argument vector.
     * @param argi The index of the next available argument.
     * @param builder The command line builder to which we should add arguments.
     * @param pathMapper Logic for stripping output path config prefixes
     * @return The index of the next argument, after the ArgvFragment has consumed its args. If the
     *     ArgvFragment doesn't have any args, it should return {@code argi} unmodified.
     */
    int eval(
        List<Object> arguments,
        int argi,
        ImmutableList.Builder<String> builder,
        PathMapper pathMapper)
        throws CommandLineExpansionException, InterruptedException;

    int addToFingerprint(
        List<Object> arguments,
        int argi,
        ActionKeyContext actionKeyContext,
        Fingerprint fingerprint)
        throws CommandLineExpansionException, InterruptedException;
  }

  /**
   * Helper base class for an ArgvFragment that doesn't use the input argument vector.
   *
   * <p>This can be used for any ArgvFragments that self-contain all the necessary state.
   */
  private abstract static class StandardArgvFragment implements ArgvFragment {
    @Override
    public final int eval(
        List<Object> arguments,
        int argi,
        ImmutableList.Builder<String> builder,
        PathMapper pathMapper) {
      eval(builder);
      return argi; // Doesn't consume any arguments, so return argi unmodified
    }

    abstract void eval(ImmutableList.Builder<String> builder);

    @Override
    public int addToFingerprint(
        List<Object> arguments,
        int argi,
        ActionKeyContext actionKeyContext,
        Fingerprint fingerprint) {
      addToFingerprint(actionKeyContext, fingerprint);
      return argi; // Doesn't consume any arguments, so return argi unmodified
    }

    abstract void addToFingerprint(ActionKeyContext actionKeyContext, Fingerprint fingerprint);
  }

  /**
   * An ArgvFragment that expands a collection of objects in a user-specified way.
   *
   * <p>Vector args support formatting, interspersing args (adding strings before each value),
   * joining, and mapping custom types. Please use this whenever you need to transform lists or
   * nested sets instead of doing it manually, as use of this class is more memory efficient.
   *
   * <p>The order of evaluation is:
   *
   * <ul>
   *   <li>Map the type T to a string using a custom map function, if any, or
   *   <li>Map any non-string type {PathFragment, Artifact} to their path/exec path
   *   <li>Format the string using the supplied format string, if any
   *   <li>Add the arguments each prepended by the before string, if any, or
   *   <li>Join the arguments with the join string, if any, or
   *   <li>Simply add all arguments
   * </ul>
   *
   * <pre>{@code
   * Examples:
   *
   * List<String> values = ImmutableList.of("1", "2", "3");
   *
   * commandBuilder.addAll(VectorArg.format("-l%s").each(values))
   * -> ["-l1", "-l2", "-l3"]
   *
   * commandBuilder.addAll(VectorArg.addBefore("-l").each(values))
   * -> ["-l", "1", "-l", "2", "-l", "3"]
   *
   * commandBuilder.addAll(VectorArg.join(":").each(values))
   * -> ["1:2:3"]
   * }</pre>
   */
  public static class VectorArg<T> {
    final boolean isNestedSet;
    final boolean isEmpty;
    final int count;
    final String formatEach;
    final String beforeEach;
    final String joinWith;

    private VectorArg(
        boolean isNestedSet,
        boolean isEmpty,
        int count,
        String formatEach,
        String beforeEach,
        String joinWith) {
      this.isNestedSet = isNestedSet;
      this.isEmpty = isEmpty;
      this.count = count;
      this.formatEach = formatEach;
      this.beforeEach = beforeEach;
      this.joinWith = joinWith;
    }

    /**
     * A vector arg that doesn't map its parameters.
     *
     * <p>Call {@link SimpleVectorArg#mapped} to produce a vector arg that maps from a given type to
     * a string.
     */
    public static class SimpleVectorArg<T> extends VectorArg<T> {
      private final Object values;

      private SimpleVectorArg(Builder builder, @Nullable Collection<T> values) {
        this(
            /* isNestedSet= */ false,
            values == null || values.isEmpty(),
            values != null ? values.size() : 0,
            builder.formatEach,
            builder.beforeEach,
            builder.joinWith,
            values);
      }

      private SimpleVectorArg(Builder builder, @Nullable NestedSet<T> values) {
        this(
            /* isNestedSet= */ true,
            values == null || values.isEmpty(),
            /* count= */ -1,
            builder.formatEach,
            builder.beforeEach,
            builder.joinWith,
            values);
      }

      private SimpleVectorArg(
          boolean isNestedSet,
          boolean isEmpty,
          int count,
          String formatEach,
          String beforeEach,
          String joinWith,
          @Nullable Object values) {
        super(isNestedSet, isEmpty, count, formatEach, beforeEach, joinWith);
        this.values = values;
      }

      /** Each argument is mapped using the supplied map function */
      public MappedVectorArg<T> mapped(CommandLineItem.MapFn<? super T> mapFn) {
        return new MappedVectorArg<>(this, mapFn);
      }
    }

    /** A vector arg that maps some type T to strings. */
    static class MappedVectorArg<T> extends VectorArg<String> {
      private final Object values;
      private final CommandLineItem.MapFn<? super T> mapFn;

      private MappedVectorArg(SimpleVectorArg<T> other, CommandLineItem.MapFn<? super T> mapFn) {
        super(
            other.isNestedSet,
            other.isEmpty,
            other.count,
            other.formatEach,
            other.beforeEach,
            other.joinWith);
        this.values = other.values;
        this.mapFn = mapFn;
      }
    }

    public static <T> SimpleVectorArg<T> of(Collection<T> values) {
      return new Builder().each(values);
    }

    public static <T> SimpleVectorArg<T> of(NestedSet<T> values) {
      return new Builder().each(values);
    }

    /** Each argument is formatted via {@link SingleStringArgFormatter#format}. */
    public static Builder format(@CompileTimeConstant String formatEach) {
      return new Builder().format(formatEach);
    }
    /** Each argument is prepended by the beforeEach param. */
    public static Builder addBefore(@CompileTimeConstant String beforeEach) {
      return new Builder().addBefore(beforeEach);
    }

    /** Once all arguments have been evaluated, they are joined with this delimiter */
    public static Builder join(String delimiter) {
      return new Builder().join(delimiter);
    }

    /** Builder for {@link VectorArg}. */
    public static class Builder {
      private String formatEach;
      private String beforeEach;
      private String joinWith;

      /** Each argument is formatted via {@link SingleStringArgFormatter#format}. */
      @CanIgnoreReturnValue
      public Builder format(@CompileTimeConstant String formatEach) {
        Preconditions.checkNotNull(formatEach);
        this.formatEach = formatEach;
        return this;
      }

      /** Each argument is prepended by the beforeEach param. */
      @CanIgnoreReturnValue
      public Builder addBefore(@CompileTimeConstant String beforeEach) {
        Preconditions.checkNotNull(beforeEach);
        this.beforeEach = beforeEach;
        return this;
      }

      /** Once all arguments have been evaluated, they are joined with this delimiter */
      @CanIgnoreReturnValue
      public Builder join(String delimiter) {
        Preconditions.checkNotNull(delimiter);
        this.joinWith = delimiter;
        return this;
      }

      public <T> SimpleVectorArg<T> each(Collection<T> values) {
        return new SimpleVectorArg<>(this, values);
      }

      public <T> SimpleVectorArg<T> each(NestedSet<T> values) {
        return new SimpleVectorArg<>(this, values);
      }
    }

    private static void push(List<Object> arguments, VectorArg<?> vectorArg) {
      // This is either a Collection or a NestedSet.
      Object values;
      CommandLineItem.MapFn<?> mapFn;
      if (vectorArg instanceof SimpleVectorArg) {
        values = ((SimpleVectorArg<?>) vectorArg).values;
        mapFn = null;
      } else {
        values = ((MappedVectorArg<?>) vectorArg).values;
        mapFn = ((MappedVectorArg<?>) vectorArg).mapFn;
      }
      VectorArgFragment vectorArgFragment =
          new VectorArgFragment(
              vectorArg.isNestedSet,
              mapFn != null,
              vectorArg.formatEach != null,
              vectorArg.beforeEach != null,
              vectorArg.joinWith != null);
      if (vectorArgFragment.hasBeforeEach && vectorArgFragment.hasJoinWith) {
        throw new IllegalArgumentException("Cannot use both 'before' and 'join' in vector arg.");
      }
      vectorArgFragment = VectorArgFragment.interner.intern(vectorArgFragment);
      arguments.add(vectorArgFragment);
      if (vectorArgFragment.hasMapEach) {
        arguments.add(mapFn);
      }
      if (vectorArgFragment.isNestedSet) {
        arguments.add(values);
      } else {
        // Simply expand any ordinary collection into the argv
        arguments.add(vectorArg.count);
        arguments.addAll((Collection<?>) values);
      }
      if (vectorArgFragment.hasFormatEach) {
        arguments.add(vectorArg.formatEach);
      }
      if (vectorArgFragment.hasBeforeEach) {
        arguments.add(vectorArg.beforeEach);
      }
      if (vectorArgFragment.hasJoinWith) {
        arguments.add(vectorArg.joinWith);
      }
    }

    private static final class VectorArgFragment implements ArgvFragment {
      private static final Interner<VectorArgFragment> interner =
          BlazeInterners.newStrongInterner();
      private static final UUID FORMAT_EACH_UUID =
          UUID.fromString("f830781f-2e0d-4e3b-9b99-ece7f249e0f3");
      private static final UUID BEFORE_EACH_UUID =
          UUID.fromString("07d22a0d-2691-4f1c-9f47-5294de1f94e4");
      private static final UUID JOIN_WITH_UUID =
          UUID.fromString("c96ed6f0-9220-40f6-9e0c-1c0c5e0b47e4");

      private final boolean isNestedSet;
      private final boolean hasMapEach;
      private final boolean hasFormatEach;
      private final boolean hasBeforeEach;
      private final boolean hasJoinWith;

      VectorArgFragment(
          boolean isNestedSet,
          boolean hasMapEach,
          boolean hasFormatEach,
          boolean hasBeforeEach,
          boolean hasJoinWith) {
        this.isNestedSet = isNestedSet;
        this.hasMapEach = hasMapEach;
        this.hasFormatEach = hasFormatEach;
        this.hasBeforeEach = hasBeforeEach;
        this.hasJoinWith = hasJoinWith;
      }

      private static String expandToCommandLine(Object object, PathMapper pathMapper) {
        // It'd be nice to build this into ActionInput's CommandLine interface so we don't have
        // to explicitly check if an object is a ActionInput. Unfortunately that would require
        // a lot more dependencies on the Java library ActionInput is built into.
        return pathMapper != null && object instanceof ActionInput actionInput
            ? pathMapper.getMappedExecPathString(actionInput)
            : CommandLineItem.expandToCommandLine(object);
      }

      @Override
      @SuppressWarnings("unchecked")
      public int eval(
          List<Object> arguments,
          int argi,
          ImmutableList.Builder<String> builder,
          PathMapper pathMapper)
          throws CommandLineExpansionException, InterruptedException {
        final List<String> mutatedValues;
        CommandLineItem.MapFn<Object> mapFn;
        if (hasMapEach) {
          mapFn = (CommandLineItem.MapFn<Object>) arguments.get(argi++);
        } else if (!pathMapper.isNoop() && !isNestedSet) {
          // Allow the PathMapper to apply a map function to string arguments depending on the
          // previous argument (e.g. to modify exec paths obtained in string form from location
          // expansion).
          String previousArg;
          if (argi > 0 && arguments.get(argi - 1) instanceof String) {
            previousArg = (String) arguments.get(argi - 1);
          } else {
            previousArg = null;
          }
          mapFn = pathMapper.getMapFn(previousArg);
        } else {
          mapFn = null;
        }
        if (isNestedSet) {
          NestedSet<Object> values = (NestedSet<Object>) arguments.get(argi++);
          ImmutableList<Object> list = values.toList();
          mutatedValues = new ArrayList<>(list.size());
          if (mapFn != null) {
            Consumer<String> args = mutatedValues::add; // Hoist out of loop to reduce GC
            for (Object object : list) {
              mapFn.expandToCommandLine(object, args);
            }
          } else {
            for (Object object : list) {
              mutatedValues.add(expandToCommandLine(object, pathMapper));
            }
          }
        } else {
          int count = (Integer) arguments.get(argi++);
          mutatedValues = new ArrayList<>(count);
          if (mapFn != null) {
            Consumer<String> args = mutatedValues::add; // Hoist out of loop to reduce GC
            for (int i = 0; i < count; ++i) {
              mapFn.expandToCommandLine(arguments.get(argi++), args);
            }
          } else {
            for (int i = 0; i < count; ++i) {
              mutatedValues.add(expandToCommandLine(arguments.get(argi++), pathMapper));
            }
          }
        }
        final int count = mutatedValues.size();
        if (hasFormatEach) {
          String formatStr = (String) arguments.get(argi++);
          for (int i = 0; i < count; ++i) {
            mutatedValues.set(i, SingleStringArgFormatter.format(formatStr, mutatedValues.get(i)));
          }
        }
        if (hasBeforeEach) {
          String beforeEach = (String) arguments.get(argi++);
          for (int i = 0; i < count; ++i) {
            builder.add(beforeEach);
            builder.add(mutatedValues.get(i));
          }
        } else if (hasJoinWith) {
          String joinWith = (String) arguments.get(argi++);
          builder.add(Joiner.on(joinWith).join(mutatedValues));
        } else {
          builder.addAll(mutatedValues);
        }
        return argi;
      }

      @SuppressWarnings("unchecked")
      @Override
      public int addToFingerprint(
          List<Object> arguments,
          int argi,
          ActionKeyContext actionKeyContext,
          Fingerprint fingerprint)
          throws CommandLineExpansionException, InterruptedException {
        CommandLineItem.MapFn<Object> mapFn =
            hasMapEach ? (CommandLineItem.MapFn<Object>) arguments.get(argi++) : null;
        if (isNestedSet) {
          NestedSet<Object> values = (NestedSet<Object>) arguments.get(argi++);
          if (mapFn != null) {
            actionKeyContext.addNestedSetToFingerprint(mapFn, fingerprint, values);
          } else {
            actionKeyContext.addNestedSetToFingerprint(fingerprint, values);
          }
        } else {
          int count = (Integer) arguments.get(argi++);
          if (mapFn != null) {
            for (int i = 0; i < count; ++i) {
              mapFn.expandToCommandLine(arguments.get(argi++), fingerprint::addString);
            }
          } else {
            for (int i = 0; i < count; ++i) {
              fingerprint.addString(CommandLineItem.expandToCommandLine(arguments.get(argi++)));
            }
          }
        }
        if (hasFormatEach) {
          fingerprint.addUUID(FORMAT_EACH_UUID);
          fingerprint.addString((String) arguments.get(argi++));
        }
        if (hasBeforeEach) {
          fingerprint.addUUID(BEFORE_EACH_UUID);
          fingerprint.addString((String) arguments.get(argi++));
        } else if (hasJoinWith) {
          fingerprint.addUUID(JOIN_WITH_UUID);
          fingerprint.addString((String) arguments.get(argi++));
        }
        return argi;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        VectorArgFragment vectorArgFragment = (VectorArgFragment) o;
        return isNestedSet == vectorArgFragment.isNestedSet
            && hasMapEach == vectorArgFragment.hasMapEach
            && hasFormatEach == vectorArgFragment.hasFormatEach
            && hasBeforeEach == vectorArgFragment.hasBeforeEach
            && hasJoinWith == vectorArgFragment.hasJoinWith;
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(isNestedSet, hasMapEach, hasFormatEach, hasBeforeEach, hasJoinWith);
      }
    }
  }

  @VisibleForSerialization
  static class FormatArg implements ArgvFragment {
    @SerializationConstant @VisibleForSerialization
    static final FormatArg INSTANCE = new FormatArg();

    private static final UUID FORMAT_UUID = UUID.fromString("377cee34-e947-49e0-94a2-6ab95b396ec4");

    private static void push(List<Object> arguments, String formatStr, Object[] args) {
      arguments.add(INSTANCE);
      arguments.add(args.length);
      arguments.add(formatStr);
      Collections.addAll(arguments, args);
    }

    @Override
    public int eval(
        List<Object> arguments,
        int argi,
        ImmutableList.Builder<String> builder,
        PathMapper pathMapper) {
      int argCount = (Integer) arguments.get(argi++);
      String formatStr = (String) arguments.get(argi++);
      Object[] args = new Object[argCount];
      for (int i = 0; i < argCount; ++i) {
        args[i] = CommandLineItem.expandToCommandLine(arguments.get(argi++));
      }
      builder.add(String.format(formatStr, args));
      return argi;
    }

    @Override
    public int addToFingerprint(
        List<Object> arguments,
        int argi,
        ActionKeyContext actionKeyContext,
        Fingerprint fingerprint) {
      int argCount = (Integer) arguments.get(argi++);
      fingerprint.addUUID(FORMAT_UUID);
      fingerprint.addString((String) arguments.get(argi++));
      for (int i = 0; i < argCount; ++i) {
        fingerprint.addString(CommandLineItem.expandToCommandLine(arguments.get(argi++)));
      }
      return argi;
    }
  }

  @VisibleForSerialization
  static class PrefixArg implements ArgvFragment {
    @SerializationConstant @VisibleForSerialization
    static final PrefixArg INSTANCE = new PrefixArg();

    private static final UUID PREFIX_UUID = UUID.fromString("a95eccdf-4f54-46fc-b925-c8c7e1f50c95");

    private static void push(
        List<Object> arguments,
        String before,
        Object arg,
        @Nullable RepositoryMapping mainRepoMapping) {
      arguments.add(INSTANCE);
      arguments.add(before);
      if (mainRepoMapping != null) {
        arguments.add(mainRepoMapping);
      }
      arguments.add(arg);
    }

    @Override
    public int eval(
        List<Object> arguments,
        int argi,
        ImmutableList.Builder<String> builder,
        PathMapper pathMapper) {
      String before = (String) arguments.get(argi++);
      Object arg = arguments.get(argi++);
      if (arg instanceof RepositoryMapping mainRepoMapping) {
        arg = ((Label) arguments.get(argi++)).getDisplayForm(mainRepoMapping);
      }
      builder.add(before + CommandLineItem.expandToCommandLine(arg));
      return argi;
    }

    @Override
    public int addToFingerprint(
        List<Object> arguments,
        int argi,
        ActionKeyContext actionKeyContext,
        Fingerprint fingerprint) {
      fingerprint.addUUID(PREFIX_UUID);
      fingerprint.addString((String) arguments.get(argi++));
      Object arg = arguments.get(argi++);
      if (arg instanceof RepositoryMapping mainRepoMapping) {
        arg = ((Label) arguments.get(argi++)).getDisplayForm(mainRepoMapping);
      }
      fingerprint.addString(CommandLineItem.expandToCommandLine(arg));
      return argi;
    }
  }

  /**
   * A command line argument for {@link TreeFileArtifact}.
   *
   * <p>Since {@link TreeFileArtifact} is not known or available at analysis time, subclasses should
   * enclose its parent TreeFileArtifact instead at analysis time. This interface provides method
   * {@link #substituteTreeArtifact} to generate another argument object that replaces the enclosed
   * TreeArtifact with one of its {@link TreeFileArtifact} at execution time.
   */
  private abstract static class TreeFileArtifactArgvFragment {
    /**
     * Substitutes this ArgvFragment with another arg object, with the original TreeArtifacts
     * contained in this ArgvFragment replaced by their associated TreeFileArtifacts.
     *
     * @param substitutionMap A map between TreeArtifacts and their associated TreeFileArtifacts
     *     used to replace them.
     */
    abstract Object substituteTreeArtifact(Map<Artifact, TreeFileArtifact> substitutionMap);
  }

  /**
   * A command line argument that can expand enclosed TreeArtifacts into a list of child {@link
   * TreeFileArtifact}s at execution time before argument evaluation.
   *
   * <p>The main difference between this class and {@link TreeFileArtifactArgvFragment} is that
   * {@link TreeFileArtifactArgvFragment} is used in {@link SpawnActionTemplate} to substitutes a
   * TreeArtifact with *one* of its child TreeFileArtifacts, while this class expands a TreeArtifact
   * into *all* of its child TreeFileArtifacts.
   */
  private abstract static class TreeArtifactExpansionArgvFragment extends StandardArgvFragment {
    /**
     * Evaluates this argument fragment into an argument string and adds it into {@code builder}.
     * The enclosed TreeArtifact will be expanded using {@code artifactExpander}.
     */
    abstract void eval(ImmutableList.Builder<String> builder, ArtifactExpander artifactExpander);

    /**
     * Evaluates this argument fragment by serializing it into a string. Note that the returned
     * argument is not suitable to be used as part of an actual command line. The purpose of this
     * method is to provide a unique command line argument string to be used as part of an action
     * key at analysis time.
     *
     * <p>Internally this method just calls {@link #describe}.
     */
    @Override
    void eval(ImmutableList.Builder<String> builder) {
      builder.add(describe());
    }

    /**
     * Returns a string that describes this argument fragment. The string can be used as part of an
     * action key for the command line at analysis time.
     */
    abstract String describe();
  }

  private static final class ExpandedTreeArtifactArg extends TreeArtifactExpansionArgvFragment {
    private static final UUID TREE_UUID = UUID.fromString("13b7626b-c77d-4a30-ad56-ff08c06b1cee");
    private final Artifact treeArtifact;

    ExpandedTreeArtifactArg(Artifact treeArtifact) {
      Preconditions.checkArgument(
          treeArtifact.isTreeArtifact(), "%s is not a TreeArtifact", treeArtifact);
      this.treeArtifact = treeArtifact;
    }

    @Override
    void eval(ImmutableList.Builder<String> builder, ArtifactExpander artifactExpander) {
      for (TreeFileArtifact child : artifactExpander.tryExpandTreeArtifact(treeArtifact)) {
        builder.add(child.getExecPathString());
      }
    }

    @Override
    public String describe() {
      return String.format(
          "ExpandedTreeArtifactArg{ treeArtifact: %s}", treeArtifact.getExecPathString());
    }

    @Override
    void addToFingerprint(ActionKeyContext actionKeyContext, Fingerprint fingerprint) {
      fingerprint.addUUID(TREE_UUID);
      fingerprint.addPath(treeArtifact.getExecPath());
    }
  }

  /**
   * An argument object that evaluates to the exec path of a {@link TreeFileArtifact}, enclosing the
   * associated {@link TreeFileArtifact}.
   */
  private static final class TreeFileArtifactExecPathArg extends TreeFileArtifactArgvFragment {
    private final Artifact placeHolderTreeArtifact;

    private TreeFileArtifactExecPathArg(Artifact artifact) {
      Preconditions.checkArgument(artifact.isTreeArtifact(), "%s must be a TreeArtifact", artifact);
      placeHolderTreeArtifact = artifact;
    }

    @Override
    Object substituteTreeArtifact(Map<Artifact, TreeFileArtifact> substitutionMap) {
      Artifact artifact = substitutionMap.get(placeHolderTreeArtifact);
      Preconditions.checkNotNull(artifact, "Artifact to substitute: %s", placeHolderTreeArtifact);
      return artifact.getExecPath();
    }
  }

  /**
   * A Builder class for CustomCommandLine with the appropriate methods.
   *
   * <p>{@link Collection} instances passed to {@code add*} methods will copied internally. If you
   * have a {@link NestedSet}, these should never be flattened to a collection before being passed
   * to the command line.
   *
   * <p>Try to avoid coercing items to strings unnecessarily. Instead, use a more memory-efficient
   * form that defers the string coercion until the last moment. In particular, avoid flattening
   * lists and nested sets (see {@link VectorArg}).
   *
   * <p>Three types are given special consideration:
   *
   * <ul>
   *   <li>Any labels added will be added using {@link Label#getCanonicalForm()}
   *   <li>Path fragments will be added using {@link PathFragment#toString}
   *   <li>Artifacts will be added using {@link Artifact#getExecPathString()}.
   * </ul>
   *
   * <p>Any other type must be mapped to a string. For collections, please use {@link
   * VectorArg.SimpleVectorArg#mapped}.
   */
  public static final class Builder {
    // In order to avoid unnecessary wrapping, we keep raw objects here, but these objects are
    // always either ArgvFragments or objects whose desired string representations are just their
    // toString() results.
    private final List<Object> arguments = new ArrayList<>();

    /**
     * Adds a constant-value string.
     *
     * <p>Prefer this over its dynamic cousin, as using static strings saves memory.
     */
    @CanIgnoreReturnValue
    public Builder add(@CompileTimeConstant String value) {
      return addObjectInternal(value);
    }

    /**
     * Adds a string argument to the command line.
     *
     * <p>If the value is null, neither the arg nor the value is added.
     */
    @CanIgnoreReturnValue
    public Builder add(@CompileTimeConstant String arg, @Nullable String value) {
      return addObjectInternal(arg, value);
    }

    /**
     * Adds a single argument to the command line, which is lazily converted to string.
     *
     * <p>If the value is null, this method is a no-op.
     *
     * <p>Passing a {@link Collection} containing multiple elements to this method instead of {@link
     * #addAll(Collection)} and similar is preferable if the caller knows that the given instance
     * will be retained elsewhere. This method spends a single array slot on the {@link Collection}
     * instead of copying over all of its elements, potentially saving memory if it is retained
     * elsewhere.
     */
    @CanIgnoreReturnValue
    public Builder addObject(@Nullable Object value) {
      return addObjectInternal(value);
    }

    /**
     * Adds a dynamically calculated string.
     *
     * <p>Consider whether using another method could be more efficient. For instance, rather than
     * calling this method with an Artifact's exec path, just add the artifact itself. It will
     * lazily get converted to its exec path. Same with labels, path fragments, and many other
     * objects.
     *
     * <p>If you are joining some list into a single argument, consider using {@link VectorArg}.
     *
     * <p>If you are formatting a string, consider using {@link Builder#addFormatted(String,
     * Object...)}.
     *
     * <p>There are many other ways you can try to avoid calling this. In general, try to use
     * constants or objects that are already on the heap elsewhere.
     */
    @CanIgnoreReturnValue
    public Builder addDynamicString(@Nullable String value) {
      return addObjectInternal(value);
    }

    /**
     * Adds a label value by calling {@link Label#getCanonicalForm}.
     *
     * <p>Prefer this over manually calling {@link Label#getCanonicalForm}, as it avoids a copy of
     * the label value.
     */
    @CanIgnoreReturnValue
    public Builder addLabel(@Nullable Label value) {
      return addObjectInternal(value);
    }

    /**
     * Adds a label value by calling {@link Label#getCanonicalForm}.
     *
     * <p>Prefer this over manually calling {@link Label#getCanonicalForm}, as it avoids storing a
     * copy of the label value.
     *
     * <p>If the value is null, neither the arg nor the value is added.
     */
    @CanIgnoreReturnValue
    public Builder addLabel(@CompileTimeConstant String arg, @Nullable Label value) {
      return addObjectInternal(arg, value);
    }

    /**
     * Adds an artifact by calling {@link PathFragment#getPathString}.
     *
     * <p>Prefer this over manually calling {@link PathFragment#getPathString}, as it avoids storing
     * a copy of the path string.
     */
    @CanIgnoreReturnValue
    public Builder addPath(@Nullable PathFragment value) {
      return addObjectInternal(value);
    }

    /**
     * Adds an artifact by calling {@link PathFragment#getPathString}.
     *
     * <p>Prefer this over manually calling {@link PathFragment#getPathString}, as it avoids storing
     * a copy of the path string.
     *
     * <p>If the value is null, neither the arg nor the value is added.
     */
    @CanIgnoreReturnValue
    public Builder addPath(@CompileTimeConstant String arg, @Nullable PathFragment value) {
      return addObjectInternal(arg, value);
    }

    /**
     * Adds an artifact by calling {@link Artifact#getExecPath}.
     *
     * <p>Prefer this over manually calling {@link Artifact#getExecPath}, as it avoids storing a
     * copy of the artifact path string.
     */
    @CanIgnoreReturnValue
    public Builder addExecPath(@Nullable Artifact value) {
      return addObjectInternal(value);
    }

    /**
     * Adds an artifact by calling {@link Artifact#getExecPath}.
     *
     * <p>Prefer this over manually calling {@link Artifact#getExecPath}, as it avoids storing a
     * copy of the artifact path string.
     *
     * <p>If the value is null, neither the arg nor the value is added.
     */
    @CanIgnoreReturnValue
    public Builder addExecPath(@CompileTimeConstant String arg, @Nullable Artifact value) {
      return addObjectInternal(arg, value);
    }

    /** Adds a lazily expanded string. */
    @CanIgnoreReturnValue
    public Builder addLazyString(@Nullable OnDemandString value) {
      return addObjectInternal(value);
    }

    /** Adds a lazily expanded string. */
    @CanIgnoreReturnValue
    public Builder addLazyString(@CompileTimeConstant String arg, @Nullable OnDemandString value) {
      return addObjectInternal(arg, value);
    }

    /** Calls {@link String#format} at command line expansion time. */
    @CanIgnoreReturnValue
    @FormatMethod
    public Builder addFormatted(@FormatString String formatStr, Object... args) {
      Preconditions.checkNotNull(formatStr);
      FormatArg.push(arguments, formatStr, args);
      return this;
    }

    /** Concatenates the passed prefix string and the string. */
    @CanIgnoreReturnValue
    public Builder addPrefixed(@CompileTimeConstant String prefix, @Nullable String arg) {
      return addPrefixedInternal(prefix, arg, /* mainRepoMapping= */ null);
    }

    /**
     * Concatenates the passed prefix string and the label using {@link Label#getDisplayForm}, which
     * is identical to {@link Label#getCanonicalForm()} for main repo labels.
     */
    @CanIgnoreReturnValue
    public Builder addPrefixedLabel(
        @CompileTimeConstant String prefix,
        @Nullable Label arg,
        RepositoryMapping mainRepoMapping) {
      return addPrefixedInternal(prefix, arg, mainRepoMapping);
    }

    /** Concatenates the passed prefix string and the path. */
    @CanIgnoreReturnValue
    public Builder addPrefixedPath(@CompileTimeConstant String prefix, @Nullable PathFragment arg) {
      return addPrefixedInternal(prefix, arg, /* mainRepoMapping= */ null);
    }

    /** Concatenates the passed prefix string and the artifact's exec path. */
    @CanIgnoreReturnValue
    public Builder addPrefixedExecPath(@CompileTimeConstant String prefix, @Nullable Artifact arg) {
      return addPrefixedInternal(prefix, arg, /* mainRepoMapping= */ null);
    }

    /**
     * Adds the passed strings to the command line.
     *
     * <p>If you are converting long lists or nested sets of a different type to string lists,
     * please try to use a different method that supports what you are trying to do directly.
     */
    @CanIgnoreReturnValue
    public Builder addAll(@Nullable Collection<String> values) {
      return addCollectionInternal(values);
    }

    /**
     * Adds the passed strings to the command line.
     *
     * <p>If you are converting long lists or nested sets of a different type to string lists,
     * please try to use a different method that supports what you are trying to do directly.
     */
    @CanIgnoreReturnValue
    public Builder addAll(@Nullable NestedSet<String> values) {
      return addNestedSetInternal(values);
    }

    /**
     * Adds the arg followed by the passed strings.
     *
     * <p>If you are converting long lists or nested sets of a different type to string lists,
     * please try to use a different method that supports what you are trying to do directly.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addAll(@CompileTimeConstant String arg, @Nullable Collection<String> values) {
      return addCollectionInternal(arg, values);
    }

    /**
     * Adds the arg followed by the passed strings.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addAll(@CompileTimeConstant String arg, @Nullable NestedSet<String> values) {
      return addNestedSetInternal(arg, values);
    }

    /** Adds the passed vector arg. See {@link VectorArg}. */
    @CanIgnoreReturnValue
    public Builder addAll(VectorArg<String> vectorArg) {
      return addVectorArgInternal(vectorArg);
    }

    /**
     * Adds the arg followed by the passed vector arg. See {@link VectorArg}.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addAll(@CompileTimeConstant String arg, VectorArg<String> vectorArg) {
      return addVectorArgInternal(arg, vectorArg);
    }

    /** Adds the passed paths to the command line. */
    @CanIgnoreReturnValue
    public Builder addPaths(@Nullable Collection<PathFragment> values) {
      return addCollectionInternal(values);
    }

    /** Adds the passed paths to the command line. */
    @CanIgnoreReturnValue
    public Builder addPaths(@Nullable NestedSet<PathFragment> values) {
      return addNestedSetInternal(values);
    }

    /**
     * Adds the arg followed by the path strings.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addPaths(
        @CompileTimeConstant String arg, @Nullable Collection<PathFragment> values) {
      return addCollectionInternal(arg, values);
    }

    /**
     * Adds the arg followed by the path fragments.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addPaths(
        @CompileTimeConstant String arg, @Nullable NestedSet<PathFragment> values) {
      return addNestedSetInternal(arg, values);
    }

    /** Adds the passed vector arg. See {@link VectorArg}. */
    @CanIgnoreReturnValue
    public Builder addPaths(VectorArg<PathFragment> vectorArg) {
      return addVectorArgInternal(vectorArg);
    }

    /**
     * Adds the arg followed by the passed vector arg. See {@link VectorArg}.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addPaths(@CompileTimeConstant String arg, VectorArg<PathFragment> vectorArg) {
      return addVectorArgInternal(arg, vectorArg);
    }

    /**
     * Adds the artifacts' exec paths to the command line.
     *
     * <p>Do not use this method if the list is derived from a flattened nested set. Instead, figure
     * out how to avoid flattening the set and use {@link #addExecPaths(NestedSet)}.
     */
    @CanIgnoreReturnValue
    public Builder addExecPaths(@Nullable Collection<Artifact> values) {
      return addCollectionInternal(values);
    }

    /** Adds the artifacts' exec paths to the command line. */
    @CanIgnoreReturnValue
    public Builder addExecPaths(@Nullable NestedSet<Artifact> values) {
      return addNestedSetInternal(values);
    }

    /**
     * Adds the arg followed by the artifacts' exec paths.
     *
     * <p>Do not use this method if the list is derived from a flattened nested set. Instead, figure
     * out how to avoid flattening the set and use {@link #addExecPaths(String, NestedSet)}.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addExecPaths(
        @CompileTimeConstant String arg, @Nullable Collection<Artifact> values) {
      return addCollectionInternal(arg, values);
    }

    /**
     * Adds the arg followed by the artifacts' exec paths.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addExecPaths(
        @CompileTimeConstant String arg, @Nullable NestedSet<Artifact> values) {
      return addNestedSetInternal(arg, values);
    }

    /** Adds the passed vector arg. See {@link VectorArg}. */
    @CanIgnoreReturnValue
    public Builder addExecPaths(VectorArg<Artifact> vectorArg) {
      return addVectorArgInternal(vectorArg);
    }

    /**
     * Adds the arg followed by the passed vector arg. See {@link VectorArg}.
     *
     * <p>If values is empty, the arg isn't added.
     */
    @CanIgnoreReturnValue
    public Builder addExecPaths(@CompileTimeConstant String arg, VectorArg<Artifact> vectorArg) {
      return addVectorArgInternal(arg, vectorArg);
    }

    /**
     * Adds a placeholder TreeArtifact exec path. When the command line is used in an action
     * template, the placeholder will be replaced by the exec path of a {@link TreeFileArtifact}
     * inside the TreeArtifact at execution time for each expanded action.
     *
     * @param treeArtifact the TreeArtifact that will be evaluated to one of its child {@link
     *     TreeFileArtifact} at execution time
     */
    @CanIgnoreReturnValue
    public Builder addPlaceholderTreeArtifactExecPath(@Nullable Artifact treeArtifact) {
      if (treeArtifact != null) {
        arguments.add(new TreeFileArtifactExecPathArg(treeArtifact));
      }
      return this;
    }

    /**
     * Adds a flag with the exec path of a placeholder TreeArtifact. When the command line is used
     * in an action template, the placeholder will be replaced by the exec path of a {@link
     * TreeFileArtifact} inside the TreeArtifact at execution time for each expanded action.
     *
     * @param arg the name of the argument
     * @param treeArtifact the TreeArtifact that will be evaluated to one of its child {@link
     *     TreeFileArtifact} at execution time
     */
    @CanIgnoreReturnValue
    public Builder addPlaceholderTreeArtifactExecPath(String arg, @Nullable Artifact treeArtifact) {
      Preconditions.checkNotNull(arg);
      if (treeArtifact != null) {
        arguments.add(arg);
        arguments.add(new TreeFileArtifactExecPathArg(treeArtifact));
      }
      return this;
    }

    /**
     * Adds the exec paths (one argument per exec path) of all {@link TreeFileArtifact}s under
     * {@code treeArtifact}.
     *
     * @param treeArtifact the TreeArtifact containing the {@link TreeFileArtifact}s to add.
     */
    @CanIgnoreReturnValue
    public Builder addExpandedTreeArtifactExecPaths(Artifact treeArtifact) {
      Preconditions.checkNotNull(treeArtifact);
      arguments.add(new ExpandedTreeArtifactArg(treeArtifact));
      return this;
    }

    public CustomCommandLine build() {
      return new CustomCommandLine(arguments.toArray());
    }

    @CanIgnoreReturnValue
    private Builder addObjectInternal(@Nullable Object value) {
      if (value != null) {
        arguments.add(value);
      }
      return this;
    }

    /** Adds the arg and the passed value if the value is non-null. */
    @CanIgnoreReturnValue
    private Builder addObjectInternal(@CompileTimeConstant String arg, @Nullable Object value) {
      Preconditions.checkNotNull(arg);
      if (value != null) {
        arguments.add(arg);
        addObjectInternal(value);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addPrefixedInternal(
        String prefix, @Nullable Object arg, @Nullable RepositoryMapping mainRepoMapping) {
      Preconditions.checkNotNull(prefix);
      if (arg != null) {
        PrefixArg.push(arguments, prefix, arg, mainRepoMapping);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addCollectionInternal(@Nullable Collection<?> values) {
      if (values != null) {
        arguments.addAll(values);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addCollectionInternal(
        @CompileTimeConstant String arg, @Nullable Collection<?> values) {
      Preconditions.checkNotNull(arg);
      if (values != null && !values.isEmpty()) {
        arguments.add(arg);
        addCollectionInternal(values);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addNestedSetInternal(@Nullable NestedSet<?> values) {
      if (values != null) {
        arguments.add(values);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addNestedSetInternal(
        @CompileTimeConstant String arg, @Nullable NestedSet<?> values) {
      Preconditions.checkNotNull(arg);
      if (values != null && !values.isEmpty()) {
        arguments.add(arg);
        addNestedSetInternal(values);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addVectorArgInternal(VectorArg<?> vectorArg) {
      if (!vectorArg.isEmpty) {
        VectorArg.push(arguments, vectorArg);
      }
      return this;
    }

    @CanIgnoreReturnValue
    private Builder addVectorArgInternal(@CompileTimeConstant String arg, VectorArg<?> vectorArg) {
      Preconditions.checkNotNull(arg);
      if (!vectorArg.isEmpty) {
        arguments.add(arg);
        addVectorArgInternal(vectorArg);
      }
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Stored as an {@code Object[]} instead of an {@link ImmutableList} to save memory, but is never
   * modified. Access via {@link #rawArgsAsList} for an unmodifiable {@link List} view.
   */
  private final Object[] arguments;

  private CustomCommandLine(Object[] arguments) {
    this.arguments = arguments;
  }

  /** Wraps {@link #arguments} in an unmodifiable {@link List} view. */
  private List<Object> rawArgsAsList() {
    return Collections.unmodifiableList(Arrays.asList(arguments));
  }

  /**
   * Given the list of {@link TreeFileArtifact}s, returns another CustomCommandLine that replaces
   * their parent TreeArtifacts with the TreeFileArtifacts in all {@link
   * TreeFileArtifactArgvFragment} argument objects.
   */
  @VisibleForTesting
  public CustomCommandLine evaluateTreeFileArtifacts(Iterable<TreeFileArtifact> treeFileArtifacts) {
    return new TreeArtifactSubstitutionCustomCommandLine(
        arguments, Maps.uniqueIndex(treeFileArtifacts, TreeFileArtifact::getParent));
  }

  @Override
  public ImmutableList<String> arguments()
      throws CommandLineExpansionException, InterruptedException {
    return arguments(null, PathMapper.NOOP);
  }

  /**
   * @param pathMapper a {@link PathMapper} that rewrites the config parts of artifact paths to
   *     improve caching. This only affects {@link Builder#addExecPath} and {@link
   *     Builder#addPath(PathFragment)} entries. Output paths embedded in larger strings and added
   *     via {@link Builder#add(String)} or other variants must be handled separately.
   */
  @Override
  public ImmutableList<String> arguments(
      @Nullable ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException, InterruptedException {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    List<Object> arguments = rawArgsAsList();
    int count = arguments.size();
    // Track the last scalar, non-path argument (e.g. "--javacopts") so that the PathMapper can
    // heuristically map subsequent argument collections that contain paths.
    String previousFlag = null;
    for (int i = 0; i < count; ) {
      Object arg = arguments.get(i++);
      if (arg instanceof TreeFileArtifactArgvFragment treeFileArtifactArgvFragment) {
        arg = substituteTreeFileArtifactArgvFragment(treeFileArtifactArgvFragment);
      }
      if (arg instanceof NestedSet<?> nestedSet) {
        evalSimpleVectorArg(nestedSet.toList(), builder, pathMapper, previousFlag);
      } else if (arg instanceof Iterable) {
        evalSimpleVectorArg((Iterable<?>) arg, builder, pathMapper, previousFlag);
      } else if (arg instanceof ArgvFragment) {
        if (artifactExpander != null
            && arg instanceof TreeArtifactExpansionArgvFragment expansionArg) {
          expansionArg.eval(builder, artifactExpander);
        } else {
          i = ((ArgvFragment) arg).eval(arguments, i, builder, pathMapper);
        }
      } else if (arg instanceof ActionInput actionInput) {
        builder.add(pathMapper.getMappedExecPathString(actionInput));
      } else if (arg instanceof PathFragment pathFragment) {
        builder.add(pathMapper.map(pathFragment).getPathString());
      } else {
        builder.add(CommandLineItem.expandToCommandLine(arg));
      }
      // Track the last scalar string argument (e.g. "--javacopts") so that the PathMapper can
      // heuristically map subsequent argument collections that contain paths.
      if (arg instanceof String string) {
        previousFlag = string;
      } else {
        previousFlag = null;
      }
    }
    return builder.build();
  }

  private void evalSimpleVectorArg(
      Iterable<?> arg,
      ImmutableList.Builder<String> builder,
      PathMapper pathMapper,
      String previousFlag) {
    ExceptionlessMapFn<Object> mapFn = pathMapper.getMapFn(previousFlag);
    for (Object value : arg) {
      if (value instanceof ActionInput actionInput) {
        builder.add(pathMapper.getMappedExecPathString(actionInput));
      } else {
        mapFn.expandToCommandLine(value, builder::add);
      }
    }
  }

  /**
   * Returns another argument object that has its enclosing tree artifact substituted by a {@link
   * TreeFileArtifact}.
   */
  @ForOverride
  Object substituteTreeFileArtifactArgvFragment(TreeFileArtifactArgvFragment argvFragment) {
    throw new IllegalStateException("Unexpected " + argvFragment);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void addToFingerprint(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      CoreOptions.OutputPathsMode effectiveOutputPathsMode,
      Fingerprint fingerprint)
      throws CommandLineExpansionException, InterruptedException {
    List<Object> arguments = rawArgsAsList();
    int count = arguments.size();
    for (int i = 0; i < count; ) {
      Object arg = arguments.get(i++);
      if (arg instanceof TreeFileArtifactArgvFragment treeFileArtifactArgvFragment) {
        arg = substituteTreeFileArtifactArgvFragment(treeFileArtifactArgvFragment);
      }
      if (arg instanceof NestedSet) {
        actionKeyContext.addNestedSetToFingerprint(fingerprint, (NestedSet<Object>) arg);
      } else if (arg instanceof Iterable) {
        for (Object value : (Iterable<Object>) arg) {
          fingerprint.addString(CommandLineItem.expandToCommandLine(value));
        }
      } else if (arg instanceof ArgvFragment argvFragment) {
        i = argvFragment.addToFingerprint(arguments, i, actionKeyContext, fingerprint);
      } else {
        fingerprint.addString(CommandLineItem.expandToCommandLine(arg));
      }
    }
  }

  /**
   * Supports {@link #substituteTreeFileArtifactArgvFragment} by maintaining a map from tree
   * artifact to {@link TreeFileArtifact}.
   */
  private static final class TreeArtifactSubstitutionCustomCommandLine extends CustomCommandLine {
    private final ImmutableMap<Artifact, TreeFileArtifact> substitutionMap;

    private TreeArtifactSubstitutionCustomCommandLine(
        Object[] arguments, ImmutableMap<Artifact, TreeFileArtifact> substitutionMap) {
      super(arguments);
      this.substitutionMap = substitutionMap;
    }

    @Override
    Object substituteTreeFileArtifactArgvFragment(TreeFileArtifactArgvFragment argvFragment) {
      return argvFragment.substituteTreeArtifact(substitutionMap);
    }
  }
}
