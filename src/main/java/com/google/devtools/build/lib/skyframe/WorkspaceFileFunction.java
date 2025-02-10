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

package com.google.devtools.build.lib.skyframe;

import static com.google.devtools.build.lib.packages.WorkspaceFactoryHelper.DEFAULT_WORKSPACE_SUFFIX_FILE;
import static com.google.devtools.build.lib.rules.repository.ResolvedFileValue.ATTRIBUTES;
import static com.google.devtools.build.lib.rules.repository.ResolvedFileValue.NATIVE;
import static com.google.devtools.build.lib.rules.repository.ResolvedFileValue.REPOSITORIES;
import static com.google.devtools.build.lib.rules.repository.ResolvedFileValue.RULE_CLASS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.TargetRecorder.NameConflictException;
import com.google.devtools.build.lib.packages.WorkspaceFactory;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.WorkspaceFileValue.WorkspaceFileKey;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.rules.repository.ResolvedFileValue;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.Comment;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;

/**
 * A SkyFunction to read, parse, and resolve a WORKSPACE file, divide it into chunks, then execute a
 * single chunk. (The read/parse/resolve work is done repeatedly.)
 */
public class WorkspaceFileFunction implements SkyFunction {

  private final PackageFactory packageFactory;
  private final BlazeDirectories directories;
  private final RuleClassProvider ruleClassProvider;
  private final BzlLoadFunction bzlLoadFunctionForInlining;
  private static final PackageIdentifier rootPackage = PackageIdentifier.createInMainRepo("");

  public WorkspaceFileFunction(
      RuleClassProvider ruleClassProvider,
      PackageFactory packageFactory,
      BlazeDirectories directories,
      BzlLoadFunction bzlLoadFunctionForInlining) {
    this.packageFactory = packageFactory;
    this.directories = directories;
    this.ruleClassProvider = ruleClassProvider;
    this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws WorkspaceFileFunctionException, InterruptedException {
    WorkspaceFileKey key = (WorkspaceFileKey) skyKey.argument();

    if (key.getIndex() == 0 && directories.getProductName().equals("bazel")) {
      env.getListener()
          .handle(
              Event.warn(
                  "WORKSPACE support will be removed in Bazel 9 (late 2025), please migrate to"
                      + " Bzlmod, see https://bazel.build/external/migration."));
    }

    RootedPath workspaceFile = key.getPath();
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }

    // The final content of the WORKSPACE is calculated in the following ways:
    // 1. If --resolved_file_instead_of_workspace is enabled, the final content will be:
    //    getDefaultWorkspacePrefix() + workspaceFromResolvedValue().
    // 2. Otherwise, if --experimental_enable_bzlmod is enabled and WORKSPACE.bzlmod exists,
    //    the final content will be:
    //    WORKSPACE.bzlmod (Neither of prefix or suffix are added)
    // 3. Otherwise, the final content will be:
    //    getDefaultWorkspacePrefix() + WORKSPACE + getDefaultWorkspaceSuffix()

    Optional<RootedPath> resolvedFile =
        Preconditions.checkNotNull(
            RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.get(env));
    boolean useWorkspaceResolvedFile = resolvedFile.isPresent();

    final boolean bzlmod = starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD);
    boolean useWorkspaceBzlmodFile = false;
    RootedPath workspaceBzlmodFile =
        RootedPath.toRootedPath(
            workspaceFile.getRoot(),
            workspaceFile.getRootRelativePath().replaceName("WORKSPACE.bzlmod"));
    // We only need to check WORKSPACE.bzlmod when the resolved file isn't used.
    if (!useWorkspaceResolvedFile && bzlmod) {
      FileValue workspaceBzlmodFileValue =
          (FileValue) env.getValue(FileValue.key(workspaceBzlmodFile));
      if (workspaceBzlmodFileValue == null) {
        return null;
      }
      useWorkspaceBzlmodFile = workspaceBzlmodFileValue.isFile();
    }

    String workspaceFromResolvedFile = null;
    FileValue workspaceFileValue = null;
    if (useWorkspaceResolvedFile) {
      workspaceFromResolvedFile = workspaceFromResolvedValue(resolvedFile.get(), env);
      if (workspaceFromResolvedFile == null) {
        return null;
      }
    } else if (!useWorkspaceBzlmodFile) {
      workspaceFileValue = (FileValue) env.getValue(FileValue.key(workspaceFile));
      if (workspaceFileValue == null) {
        return null;
      }
    }

    FileOptions options =
        FileOptions.builder()
            // Repository declarations in WORKSPACE have side effects on
            // the set of valid load labels, so load statements cannot all
            // be migrated to the top of the file.
            .requireLoadStatementsFirst(false)
            // Bindings created by load statements in one
            // chunk must be accessible to later chunks.
            .loadBindsGlobally(true)
            // Top-level rebinding is permitted because historically
            // WORKSPACE files followed BUILD norms, but this should
            // probably be flipped.
            .allowToplevelRebinding(true)
            .build();

    // Calculate user workspace file content
    StarlarkFile userWorkspaceFile = null;
    if (useWorkspaceResolvedFile) {
      // WORKSPACE.resolved file.
      userWorkspaceFile =
          StarlarkFile.parse(
              ParserInput.fromString(
                  workspaceFromResolvedFile, resolvedFile.get().asPath().toString()),
              // The WORKSPACE.resolved file breaks through the usual privacy mechanism.
              options.toBuilder().allowLoadPrivateSymbols(true).build());
    } else if (useWorkspaceBzlmodFile) {
      // If Bzlmod is enabled and WORKSPACE.bzlmod exists, then use this file instead of the
      // original WORKSPACE file.
      userWorkspaceFile = parseWorkspaceFile(workspaceBzlmodFile, options, starlarkSemantics, env);
    } else if (workspaceFileValue.exists()) {
      // normal WORKSPACE file
      userWorkspaceFile = parseWorkspaceFile(workspaceFile, options, starlarkSemantics, env);
    }

    boolean shouldSkipWorkspacePrefix = useWorkspaceResolvedFile || useWorkspaceBzlmodFile;
    boolean shouldSkipWorkspaceSuffix = useWorkspaceResolvedFile || useWorkspaceBzlmodFile;
    if (userWorkspaceFile != null) {
      for (Comment comment : userWorkspaceFile.getComments()) {
        shouldSkipWorkspacePrefix |= comment.getText().contains("__SKIP_WORKSPACE_PREFIX__");
        shouldSkipWorkspaceSuffix |= comment.getText().contains("__SKIP_WORKSPACE_SUFFIX__");
      }
    }

    // Accumulate workspace files (prefix + main + suffix).
    ArrayList<StarlarkFile> files = new ArrayList<>();

    // 1. Add WORKSPACE prefix (DEFAULT.WORKSPACE)
    if (!shouldSkipWorkspacePrefix) {
      StarlarkFile file =
          StarlarkFile.parse(
              ParserInput.fromString(
                  ruleClassProvider.getDefaultWorkspacePrefix(), "/DEFAULT.WORKSPACE"),
              options);
      if (!file.ok()) {
        Event.replayEventsOn(env.getListener(), file.errors());
        throw resolvedValueError("Failed to parse default WORKSPACE file");
      }
      files.add(file);
    }

    // 2. Add user workspace content
    if (userWorkspaceFile != null) {
      files.add(userWorkspaceFile);
    }

    // 3. Add WORKSPACE suffix (DEFAULT.WORKSPACE.SUFFIX)
    if (!shouldSkipWorkspaceSuffix) {
      StarlarkFile file =
          StarlarkFile.parse(
              ParserInput.fromString(
                  ruleClassProvider.getDefaultWorkspaceSuffix(), DEFAULT_WORKSPACE_SUFFIX_FILE),
              // The DEFAULT.WORKSPACE.SUFFIX file breaks through the usual privacy mechanism.
              options.toBuilder().allowLoadPrivateSymbols(true).build());
      if (!file.ok()) {
        Event.replayEventsOn(env.getListener(), file.errors());
        throw resolvedValueError("Failed to parse default WORKSPACE file suffix");
      }
      files.add(file);
    }

    // Split concatenated WORKSPACE files into chunks.
    //
    // A chunk is a sequence of statements in which loads precede non-loads.
    // A chunk may span file boundaries, hence it is a list of partial files.
    //
    // The alternative, having chunks respect file boundaries, with a single
    // call to 'execute' per WorkspaceFileValue, was investigated but found
    // to require either (a) invasive changes to tests, or (b) user-visible
    // changes to WORKSPACE semantics. This is because the lookup logic in
    // repository.ExternalPackageHelper.processAndShouldContinue always
    // returns the latest definition of a given name within the *first chunk
    // that defines that name*. Consequently, adding a new chunk boundary
    // between the prefix file and the main workspace file would break tests
    // that exploit the current semantics to override the definitions of the
    // prefix file. At the same time, changing it so the last definition wins
    // would change the meaning of users' WORKSPACE files, which often rely on
    // macro-like logic that may yield competing definitions for the same
    // transitive dependency.
    //
    // (Within a chunk, before the first load statement, rules may be
    // redefined, in which case they override earlier rules.)
    List<List<StarlarkFile>> chunks = splitChunks(files);

    // -- end   of historical WorkspaceASTFunction --
    // -- start of historical WorkspaceFileFunction --
    // TODO(adonovan): reorganize and simplify.

    // Get the state at the end of the previous chunk.
    WorkspaceFileValue prevValue = null;
    if (key.getIndex() > 0) {
      prevValue =
          (WorkspaceFileValue)
              env.getValue(WorkspaceFileValue.key(workspaceFile, key.getIndex() - 1));
      if (prevValue == null) {
        return null;
      }
      if (prevValue.next() == null) {
        return prevValue;
      }
    }
    RepositoryMapping repoMapping;
    if (prevValue == null) {
      repoMapping = RepositoryMapping.ALWAYS_FALLBACK;
    } else {
      repoMapping =
          RepositoryMapping.createAllowingFallback(
              prevValue
                  .getRepositoryMapping()
                  .getOrDefault(RepositoryName.MAIN, ImmutableMap.of()));
    }
    if (bzlmod) {
      RepositoryMappingValue rootModuleMapping =
          (RepositoryMappingValue)
              env.getValue(RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS);
      if (rootModuleMapping == null) {
        return null;
      }
      repoMapping = repoMapping.composeWith(rootModuleMapping.repositoryMapping());
    }

    // The default 'workspace name' is "__main__". Note that this is different from the "workspace
    // name" returned by WorkspaceNameFunction, which is a fixed string when Bzlmod is enabled.
    Package.Builder builder =
        packageFactory.newExternalPackageBuilder(key, "__main__", repoMapping, starlarkSemantics);

    if (chunks.isEmpty()) {
      builder.setLoads(ImmutableList.of());
      return new WorkspaceFileValue(
          buildAndReportEvents(builder, env),
          /* loadedModules= */ ImmutableMap.of(),
          /* loadToChunkMap= */ ImmutableMap.of(),
          /* bindings= */ ImmutableMap.of(),
          workspaceFile,
          /* idx= */ 0, // first fragment
          /* hasNext= */ false);
    }

    List<StarlarkFile> chunk = chunks.get(key.getIndex());

    // Parse the labels in the chunk's load statements.
    ImmutableList<Pair<String, Location>> programLoads =
        BzlLoadFunction.getLoadsFromStarlarkFiles(chunk);
    ImmutableList<Label> loadLabels =
        BzlLoadFunction.getLoadLabels(
            env.getListener(), programLoads, rootPackage, repoMapping, starlarkSemantics);
    if (loadLabels == null) {
      NoSuchPackageException e =
          PackageFunction.PackageFunctionException.builder()
              .setType(PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
              .setPackageIdentifier(rootPackage)
              .setMessage("malformed load statements")
              .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
              .buildCause();
      throw new WorkspaceFileFunctionException(e, Transience.PERSISTENT);
    }

    // Compute key for each load label.
    ImmutableList.Builder<BzlLoadValue.Key> keys =
        ImmutableList.builderWithExpectedSize(loadLabels.size());
    for (Label loadLabel : loadLabels) {
      keys.add(
          BzlLoadValue.keyForWorkspace(
              loadLabel,
              getOriginalWorkspaceChunk(env, workspaceFile, key.getIndex(), loadLabel),
              workspaceFile));
    }

    // Load .bzl modules in parallel.
    ImmutableMap<String, Module> loadedModules;
    try {
      loadedModules =
          PackageFunction.loadBzlModules(
              env,
              rootPackage,
              // In error messages, attribute the blame to "WORKSPACE content" since we're not sure
              // at this point what the actual source of the content was.
              "WORKSPACE content",
              programLoads,
              keys.build(),
              starlarkSemantics,
              bzlLoadFunctionForInlining,
              /* checkVisibility= */ true,
              BazelSkyframeExecutorConstants.ACTION_ON_FILESYSTEM_ERROR_CODE_LOADING_BZL_FILE);
    } catch (NoSuchPackageException e) {
      throw new WorkspaceFileFunctionException(e, Transience.PERSISTENT);
    }
    if (loadedModules == null) {
      return null;
    }

    // Execute one workspace file chunk.
    WorkspaceFactory parser;
    try (Mutability mu = Mutability.create("workspace", workspaceFile)) {
      parser =
          new WorkspaceFactory(
              builder,
              ruleClassProvider,
              mu,
              key.getIndex() == 0,
              // Due to rules_java_builtin in WORKSPACE prefix, we allow workspace() in the first 2
              // load statement separated chunks
              key.getIndex() <= 1,
              directories.getEmbeddedBinariesRoot(),
              directories.getWorkspace(),
              directories.getLocalJavabase(),
              starlarkSemantics);
      if (prevValue != null) {
        try {
          parser.setParent(
              prevValue.getPackage(), prevValue.getLoadedModules(), prevValue.getBindings());
        } catch (NameConflictException e) {
          throw new WorkspaceFileFunctionException(e, Transience.PERSISTENT);
        }
        builder.setLoads(
            Iterables.concat(prevValue.getLoadedModules().values(), loadedModules.values()));
      } else {
        builder.setLoads(loadedModules.values());
      }
      // Execute the partial files that comprise this chunk.
      for (StarlarkFile partialFile : chunk) {
        parser.execute(partialFile, loadedModules);
      }
    }

    // Return the Skyframe value for this workspace file chunk.
    return new WorkspaceFileValue(
        buildAndReportEvents(builder, env),
        parser.getLoadedModules(),
        createLoadToChunkMap(prevValue, parser, key),
        parser.getVariableBindings(),
        workspaceFile,
        key.getIndex(),
        key.getIndex() < chunks.size() - 1);
  }

  private static StarlarkFile parseWorkspaceFile(
      RootedPath workspaceFile,
      FileOptions options,
      StarlarkSemantics starlarkSemantics,
      Environment env)
      throws WorkspaceFileFunctionException {
    Path workspacePath = workspaceFile.asPath();
    byte[] bytes;
    try {
      bytes = FileSystemUtils.readWithKnownFileSize(workspacePath, workspacePath.getFileSize());
    } catch (IOException ex) {
      throw new WorkspaceFileFunctionException(ex, Transience.TRANSIENT);
    }
    ParserInput parserInput;
    try {
      parserInput =
          StarlarkUtil.createParserInput(
              bytes,
              workspacePath.toString(),
              starlarkSemantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
              env.getListener());
    } catch (
        @SuppressWarnings("UnusedException") // createParserInput() reports its own error message
        StarlarkUtil.InvalidUtf8Exception e) {
      throw resolvedValueError("Failed to read WORKSPACE file");
    }
    StarlarkFile file = StarlarkFile.parse(parserInput);
    if (!file.ok()) {
      Event.replayEventsOn(env.getListener(), file.errors());
      throw resolvedValueError("Failed to parse WORKSPACE file");
    }
    return file;
  }

  private static int getOriginalWorkspaceChunk(
      Environment env, RootedPath workspacePath, int workspaceChunk, Label loadLabel)
      throws InterruptedException {
    if (workspaceChunk < 1) {
      return workspaceChunk;
    }
    // If we got here, we are already computing workspaceChunk "workspaceChunk", and so we know
    // that the value for "workspaceChunk-1" has already been computed so we don't need to check
    // for nullness
    SkyKey workspaceFileKey = WorkspaceFileValue.key(workspacePath, workspaceChunk - 1);
    WorkspaceFileValue workspaceFileValue = (WorkspaceFileValue) env.getValue(workspaceFileKey);
    ImmutableMap<String, Integer> loadToChunkMap = workspaceFileValue.getLoadToChunkMap();
    String loadString = loadLabel.toString();
    return loadToChunkMap.getOrDefault(loadString, workspaceChunk);
  }

  private static Package buildAndReportEvents(Package.Builder pkgBuilder, Environment env)
      throws WorkspaceFileFunctionException {
    Package result;
    try {
      result = pkgBuilder.build();
    } catch (NoSuchPackageException e) {
      throw new WorkspaceFileFunctionException(e, Transience.TRANSIENT);
    }

    pkgBuilder.getLocalEventHandler().replayOn(env.getListener());

    return result;
  }

  /**
   * This returns a map from load statement to the chunk the load statement originated from.
   *
   * <p>For example, if the WORKSPACE file looked like the following:
   *
   * <pre>
   * load(":a.bzl", "a")
   * x = 0
   * load(":b.bzl", "b")
   * x = 1
   * load(":a.bzl", "a1")
   * load(":c.bzl", "c")
   * x = 2
   * </pre>
   *
   * Then the map for chunk 0 would be {@code {":a.bzl" : 0}}, for chunk 1 it'd be: {@code {":a.bzl"
   * : 0, ":b.bzl" : 1}}, and for chunk 2 it'd be: {@code {":a.bzl" : 0, ":b.bzl" : 1, ":c.bzl" :
   * 2}}
   */
  private static ImmutableMap<String, Integer> createLoadToChunkMap(
      WorkspaceFileValue prevValue, WorkspaceFactory parser, WorkspaceFileKey key) {
    ImmutableMap.Builder<String, Integer> builder = new ImmutableMap.Builder<>();
    if (prevValue == null) {
      for (String loadString : parser.getLoadedModules().keySet()) {
        builder.put(loadString, key.getIndex());
      }
    } else {
      builder.putAll(prevValue.getLoadToChunkMap());
      for (String label : parser.getLoadedModules().keySet()) {
        if (!prevValue.getLoadToChunkMap().containsKey(label)) {
          builder.put(label, key.getIndex());
        }
      }
    }
    return builder.buildOrThrow();
  }

  private static final class WorkspaceFileFunctionException extends SkyFunctionException {
    WorkspaceFileFunctionException(NoSuchPackageException e, Transience transience) {
      super(e, transience);
    }

    WorkspaceFileFunctionException(NameConflictException e, Transience transience) {
      super(e, transience);
    }

    WorkspaceFileFunctionException(IOException e, Transience transience) {
      super(e, transience);
    }
  }

  private static WorkspaceFileFunctionException resolvedValueError(String message) {
    return new WorkspaceFileFunctionException(
        new BuildFileContainsErrorsException(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER, message),
        Transience.PERSISTENT);
  }

  /**
   * Return the contents of the WORKSPACE file that is implicitly represented by the resolved value
   * found in the given file.
   *
   * <p>TODO(aehlig): at the moment we serialize the value as a string just to re-parse it
   * immediately again; we probably should construct the statements directly out of the value to
   * improve performance.
   */
  @Nullable
  private static String workspaceFromResolvedValue(RootedPath resolvedPath, Environment env)
      throws WorkspaceFileFunctionException, InterruptedException {
    ResolvedFileValue resolvedValue =
        (ResolvedFileValue) env.getValue(ResolvedFileValue.key(resolvedPath));
    if (resolvedValue == null) {
      return null;
    }
    List<Map<String, Object>> resolved = resolvedValue.getResolvedValue();
    StringBuilder builder = new StringBuilder();
    for (Map<String, Object> entry : resolved) {
      Object repositories = entry.get(REPOSITORIES);
      if (repositories != null) {
        if (!(repositories instanceof List)) {
          throw resolvedValueError(
              "In 'resolved' the " + REPOSITORIES + " entry is or not a list for item " + entry);
        }
        for (Object repo : (List) repositories) {
          if (!(repo instanceof Map)) {
            throw resolvedValueError("A description of an individual repository is not a map");
          }
          Object rule = ((Map) repo).get(RULE_CLASS);
          if (!(rule instanceof String)) {
            throw resolvedValueError("Expected " + RULE_CLASS + " to be a string.");
          }
          int separatorPosition = ((String) rule).lastIndexOf('%');
          if (separatorPosition < 0) {
            throw resolvedValueError("Malformed rule class: " + ((String) rule));
          }
          String fileName = ((String) rule).substring(0, separatorPosition);
          String symbol = ((String) rule).substring(separatorPosition + 1);

          Object args = ((Map) repo).get(ATTRIBUTES);
          if (!(args instanceof Map)) {
            throw resolvedValueError("Arguments for " + ((String) rule) + " not a dict.");
          }

          builder
              .append("load(\"")
              .append(fileName)
              .append("\", \"")
              .append(symbol)
              .append("\")\n");
          builder.append(symbol).append("(\n");
          for (Map.Entry<?, ?> arg : ((Map<?, ?>) args).entrySet()) {
            Object key = arg.getKey();
            if (!(key instanceof String)) {
              throw resolvedValueError(
                  "In arguments to " + ((String) rule) + " found a non-string key.");
            }
            builder.append("    ").append((String) key).append(" = ");
            builder.append(Starlark.repr(arg.getValue()));
            builder.append(",\n");
          }
          builder.append(")\n\n");
        }
      }
      Object nativeEntry = entry.get(NATIVE);
      if (nativeEntry != null) {
        if (!(nativeEntry instanceof String)) {
          throw resolvedValueError(
              "In 'resolved' the " + NATIVE + " entry is not a string for item " + entry);
        }
        builder.append(nativeEntry).append("\n");
      }
    }
    return builder.toString();
  }

  /**
   * Given a list of files whose concatenation represents the logical WORKSPACE content, returns its
   * partitioning into chunks.
   *
   * <p>Each chunk covers a piece of the WORKSPACE content in which all load statements precede
   * non-loads. Chunks are maximal in the sense that 1) they begin either at the beginning of the
   * logical WORKSPACE, or at a load statement that is preceded by a non-load; and 2) they extend up
   * to the end of the logical WORKSPACE, or up to but not including the next load statement
   * preceded by a non-load. Note that chunks may cross the boundaries of the files in the input
   * list, and chunks may subdivide individual files within the input list.
   *
   * <p>The returned list of chunks, each chunk in it, and each file in each chunk, are all
   * non-empty.
   */
  @VisibleForTesting
  static List<List<StarlarkFile>> splitChunks(List<StarlarkFile> files) {
    ArrayList<List<StarlarkFile>> chunks = new ArrayList<>();
    ArrayList<StarlarkFile> chunk = null; // allocated on demand
    for (StarlarkFile file : files) {
      int start = 0;
      boolean prevIsLoad = false;
      int nstmts = file.getStatements().size();
      for (int i = 0; i < nstmts; i++) {
        boolean isLoad = file.getStatements().get(i) instanceof LoadStatement;
        if (isLoad && !prevIsLoad) {
          // Load after non-load: finish current chunk and begin a new one.
          if (i > start) {
            if (chunk == null) {
              chunk = new ArrayList<>();
            }
            chunk.add(file.subTree(start, i));
          }
          start = i;
          if (chunk != null) {
            chunks.add(chunk);
          }
          chunk = null;
        }
        prevIsLoad = isLoad;
      }
      // End of file: add rest of file to current chunk but
      // leave it open for the next file
      if (nstmts > start) {
        if (chunk == null) {
          chunk = new ArrayList<>();
        }
        chunk.add(file.subTree(start, nstmts));
      }
    }
    // End of last file: dispatch current chunk.
    if (chunk != null) {
      chunks.add(chunk);
    }
    return chunks;
  }
}
