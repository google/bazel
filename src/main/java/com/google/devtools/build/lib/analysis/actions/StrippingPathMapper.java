// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper.BasicActionInput;
import com.google.devtools.build.lib.actions.ArgChunk;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.CommandLine.SimpleArgChunk;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.actions.CommandLineItem.ExceptionlessMapFn;
import com.google.devtools.build.lib.actions.CommandLineItem.MapFn;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.starlarkbuildapi.FileRootApi;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Main logic for experimental config-stripped execution paths:
 * https://github.com/bazelbuild/bazel/issues/6526.
 *
 * <p>The actions executors run look like: {@code tool_pkg/mytool src/source.file
 * bazel-out/x86-opt/pkg/gen.file -o bazel-out/x86-opt/pkg/myout}.
 *
 * <p>The "x86-opt" part is a path's "configuration prefix": information describing the build
 * configuration of the action creating the artifact. This example shows artifacts created with
 * {@code --cpu=x86 --compilation_mode=opt}.
 *
 * <p>Executors cache actions based on their a) command line, b) input and output paths, c) input
 * digests. Configuration prefixes harm caching because even if an action behaves exactly the same
 * for different CPU architectures, {@code <cpu>-opt} guarantees the paths will differ.
 *
 * <p>Config-stripping is an experimental feature that strips the configuration prefix from
 * qualifying actions before running them, thus improving caching. "Qualifying" actions are actions
 * known not to depend on the names of their input and output paths. Non-qualifying actions include
 * manifest generators and compilers that store debug symbol source paths.
 *
 * <p>As an experimental feature, most logic is centralized here to provide easy hooks into executor
 * and action code and avoid complicating large swaths of the code base.
 *
 * <p>Enable this feature by setting {@code --experimental_output_paths=strip}. This activates two
 * effects:
 *
 * <ol>
 *   <li>"Qualifying" actions strip config paths from their command lines. An action qualifies if
 *       its implementation logic uses {@link PathMappers#create} as described in its javadocs and
 *       has its mnemonic listed in {@link PathMappers#SUPPORTED_MNEMONICS}. Such an action must
 *       pass the {@link PathMapper} to all structured command line constructions. If any
 *       unstructured command line arguments refer to artifact paths, custom handling needs to be
 *       added to {@code mapCustomStarlarkArgv} or {@code getMapFn} below.
 *   <li>A supporting executor strips paths from qualifying actions' inputs and outputs before
 *       staging for execution by taking {@link Spawn#getPathMapper()} into account.
 * </ol>
 *
 * <p>So an action is responsible for declaring that it strips paths and adjusting its command line
 * accordingly. The executor is responsible for remapping action inputs and outputs to match.
 */
public final class StrippingPathMapper implements PathMapper {
  public static final String GUID = "8eb2ad5a-85d4-435b-858f-5c192e91997d";

  private static final String FIXED_CONFIG_SEGMENT = "cfg";

  private final PathFragment outputRoot;
  private final String mnemonic;
  private final boolean isStarlarkAction;
  private final boolean isJavaAction;
  private final ExceptionlessMapFn<Object> structuredArgStripper;
  private final StringStripper argStripper;
  private final ArtifactRoot outputArtifactRoot;
  private final MappedArtifactRoot strippedOutputArtifactRoot;

  private StrippingPathMapper(Artifact primaryOutput, String mnemonic, boolean isStarlarkAction) {
    // This is expected to always be "(bazel|blaze)-out".
    this.outputRoot = primaryOutput.getExecPath().subFragment(0, 1);
    this.mnemonic = mnemonic;
    this.isStarlarkAction = isStarlarkAction;
    this.argStripper = new StringStripper(outputRoot.getPathString());
    this.structuredArgStripper =
        (object, args) -> {
          if (object instanceof String str) {
            args.accept(this.argStripper.strip(str));
          } else {
            args.accept(CommandLineItem.expandToCommandLine(object));
          }
        };
    // This kind of special handling should not be extended. It is a hack that works around a
    // limitation of the native implementation of location expansion: The output is just a list of
    // strings, not a structured command line that would allow transparent path mapping.
    // Instead, reimplement location expansion in Starlark and have it return an Args object.
    this.isJavaAction =
        mnemonic.equals("Javac")
            || mnemonic.equals("JavacTurbine")
            || mnemonic.equals("Turbine")
            || mnemonic.equals("JavaResourceJar");
    this.outputArtifactRoot = primaryOutput.getRoot();
    this.strippedOutputArtifactRoot = new MappedArtifactRoot(map(outputArtifactRoot.getExecPath()));
  }

  /**
   * Creates a new {@link PathMapper} that strips config prefixes if the particular action instance
   * supports it.
   *
   * @param action the action to potentially strip paths from
   * @param isStarlarkAction whether the action is a Starlark action
   * @return a {@link StrippingPathMapper} if the action supports it, else {@link Optional#empty()}.
   */
  static Optional<PathMapper> tryCreate(AbstractAction action, boolean isStarlarkAction) {
    PathFragment outputRoot = action.getPrimaryOutput().getExecPath().subFragment(0, 1);
    // Additional artifacts to map are not part of the action's inputs, but may still lead to
    // path collisions after stripping. It is thus important to include them in this check.
    if (isPathStrippable(
        Iterables.concat(
            action.getInputs().toList(), action.getAdditionalArtifactsForPathMapping().toList()),
        outputRoot)) {
      return Optional.of(
          new StrippingPathMapper(
              action.getPrimaryOutput(), action.getMnemonic(), isStarlarkAction));
    }
    return Optional.empty();
  }

  @Override
  public String getMappedExecPathString(ActionInput artifact) {
    if (isSupportedInputType(artifact) && isOutputPath(artifact, outputRoot)) {
      return strip(artifact.getExecPath()).getPathString();
    } else {
      return artifact.getExecPathString();
    }
  }

  @Override
  public PathFragment map(PathFragment execPath) {
    return isOutputPath(execPath, outputRoot) ? strip(execPath) : execPath;
  }

  @Override
  public ArgChunk mapCustomStarlarkArgs(ArgChunk chunk) {
    if (!isStarlarkAction) {
      return chunk;
    }
    // Add your favorite Starlark mnemonic that needs custom arg processing here.
    if (!mnemonic.contains("Android")
        && !mnemonic.equals("MergeManifests")
        && !mnemonic.equals("StarlarkRClassGenerator")
        && !mnemonic.equals("StarlarkAARGenerator")
        && !mnemonic.equals("JetifySrcs")
        && !mnemonic.equals("Desugar")) {
      return chunk;
    }

    // TODO: b/327187486 - This materializes strings when totalArgLength() is called. Can it
    //  compute the total arg length without creating garbage strings?
    Iterable<String> args = chunk.arguments();
    return new SimpleArgChunk(() -> new CustomStarlarkArgsIterator(args.iterator(), argStripper));
  }

  @Override
  public ExceptionlessMapFn<Object> getMapFn(@Nullable String previousFlag) {
    if (isJavaAction) {
      if (Objects.equals(previousFlag, "--javacopts")
          || Objects.equals(previousFlag, "--resources")) {
        return structuredArgStripper;
      }
    }
    return MapFn.DEFAULT;
  }

  @Override
  public String mapHeuristically(String arg) {
    return argStripper.strip(arg);
  }

  @Override
  public FileRootApi mapRoot(Artifact artifact) {
    if (Objects.equals(artifact.getRoot(), outputArtifactRoot)) {
      // The mapped root's path does not depend on the artifact, so we can share an instance.
      return strippedOutputArtifactRoot;
    }
    // Fall back for source roots as well as runfiles tree artifacts, which should be very rare.
    return PathMapper.super.mapRoot(artifact);
  }

  private boolean isSupportedInputType(ActionInput artifact) {
    return artifact instanceof DerivedArtifact
        || artifact instanceof ParamFileActionInput
        || artifact instanceof BasicActionInput;
  }

  private static final class CustomStarlarkArgsIterator implements Iterator<String> {
    // Add your favorite arg to custom-process here. When Bazel finds one of these in the argument
    // list (an argument name), it strips output path prefixes from the following argument (the
    // argument value).
    private static final ImmutableSet<String> STARLARK_ARGS_TO_STRIP =
        ImmutableSet.of(
            "--mainData",
            "--primaryData",
            "--directData",
            "--data",
            "--resources",
            "--mergeeManifests",
            "--library",
            "-i",
            "--input");

    private final Iterator<String> args;
    private final StringStripper argStripper;
    private boolean stripNext = false;

    CustomStarlarkArgsIterator(Iterator<String> args, StringStripper argStripper) {
      this.args = args;
      this.argStripper = argStripper;
    }

    @Override
    public boolean hasNext() {
      return args.hasNext();
    }

    @Override
    public String next() {
      String next = args.next();
      if (stripNext) {
        next = argStripper.strip(next);
      }
      stripNext = STARLARK_ARGS_TO_STRIP.contains(next);
      return next;
    }
  }

  /** Utility class to strip output path configuration prefixes from arbitrary strings. */
  private static class StringStripper {
    private final Pattern pattern;
    private final String outputRoot;

    StringStripper(String outputRoot) {
      this.outputRoot = outputRoot;
      this.pattern = stripPathsPattern(outputRoot);
    }

    /**
     * Returns the regex to strip output paths from a string.
     *
     * <p>Supports strings with multiple output paths in arbitrary places. For example
     * "/path/to/compiler bazel-out/x86-fastbuild/foo src/my.src -Dbazel-out/arm-opt/bar".
     *
     * <p>Doesn't strip paths that would be non-existent without config prefixes. For example, these
     * are unchanged: "bazel-out/x86-fastbuild", "bazel-out;foo", "/path/to/compiler bazel-out".
     *
     * @param outputRoot root segment of output paths (e.g. "bazel-out")
     */
    private static Pattern stripPathsPattern(String outputRoot) {
      // Match "bazel-out" followed by a slash followed by any combination of word characters, "_",
      // and "-", followed by another slash. This would miss substrings like
      // "bazel-out/k8-fastbuild". But those don't represent actual outputs (all outputs would have
      // to have names beneath that path). So we're not trying to replace those.
      return Pattern.compile(outputRoot + "/[\\w_-]+/");
    }

    public String strip(String str) {
      return pattern.matcher(str).replaceAll(outputRoot + "/" + FIXED_CONFIG_SEGMENT + "/");
    }
  }

  /**
   * Is this a strippable path?
   *
   * @param artifact artifact whose path to check
   * @param outputRoot the output tree's execPath-relative root (e.g. "bazel-out")
   */
  private static boolean isOutputPath(ActionInput artifact, PathFragment outputRoot) {
    // We can't simply check for DerivedArtifact. Output paths can also appear, for example, in
    // ParamFileActionInput and ActionInputHelper.BasicActionInput.
    return isOutputPath(artifact.getExecPath(), outputRoot);
  }

  /** Private utility method: Is this a strippable path? */
  private static boolean isOutputPath(PathFragment pathFragment, PathFragment outputRoot) {
    return pathFragment.startsWith(outputRoot);
  }

  /**
   * Is this action safe to strip?
   *
   * <p>This is distinct from whether we <b>should</b> strip it. An action is stripped if a) the
   * action is explicitly supported (see {@link PathMappers#SUPPORTED_MNEMONICS}) and b) it's safe
   * to do that (for example, the action doesn't have two inputs in different configurations that
   * would resolve to the same path if prefixes were removed).
   *
   * <p>This method checks b).
   */
  private static boolean isPathStrippable(
      Iterable<? extends ActionInput> actionInputs, PathFragment outputRoot) {
    // For qualifying action types, check that no inputs or outputs would clash if config segments
    // were removed, e.g. "bazel-out/k8-fastbuild/bin/foo" and
    // "bazel-out/k8-fastbuild-ST-1234/bin/foo".
    //
    // A more clever algorithm could remap these with custom prefixes - "bazel-out/1/bin/foo" and
    // "bazel-out/2/bin/foo" - if experience shows that would help.
    HashMap<PathFragment, ActionInput> rootRelativePaths = new HashMap<>();
    for (ActionInput input : actionInputs) {
      if (!isOutputPath(input, outputRoot)) {
        continue;
      }
      // For "bazel-out/k8-fastbuild/bin/foo/bar", get "bin/foo/bar".
      if (!rootRelativePaths
          .computeIfAbsent(input.getExecPath().subFragment(2), k -> input)
          .equals(input)) {
        return false;
      }
    }
    return true;
  }

  /*
   * Strips the configuration prefix from an output artifact's exec path.
   */
  private static PathFragment strip(PathFragment execPath) {
    if (execPath.subFragment(1, 2).getPathString().equals("tmp")) {
      return execPath
          .subFragment(0, 2)
          .getRelative(FIXED_CONFIG_SEGMENT)
          .getRelative(execPath.subFragment(3));
    }
    return execPath
        .subFragment(0, 1)
        // Keep the config segment, but replace it with a fixed string to improve cacheability while
        // still preserving the general segment structure of the execpath.
        .getRelative(FIXED_CONFIG_SEGMENT)
        .getRelative(execPath.subFragment(2));
  }
}
