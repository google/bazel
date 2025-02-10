// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.github.difflib.patch.PatchFailedException;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleEvent;
import com.google.devtools.build.lib.bazel.repository.PatchUtil;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.repository.RepositoryFetchProgress;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.RepoCacheFriendlyPath;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;

/** Starlark API for the repository_rule's context. */
@StarlarkBuiltin(
    name = "repository_ctx",
    category = DocCategory.BUILTIN,
    doc =
        """
        The context of the repository rule containing \
        helper functions and information about attributes. You get a repository_ctx object \
        as an argument to the <code>implementation</code> function when you create a \
        repository rule.
        """)
public class StarlarkRepositoryContext extends StarlarkBaseExternalContext {
  private final Rule rule;
  private final PathPackageLocator packageLocator;
  private final StructImpl attrObject;
  private final IgnoredSubdirectories ignoredSubdirectories;
  private final SyscallCache syscallCache;
  private final HashMap<RepoRecordedInput.DirTree, String> recordedDirTreeInputs = new HashMap<>();

  /**
   * Create a new context (repository_ctx) object for a Starlark repository rule ({@code rule}
   * argument).
   */
  StarlarkRepositoryContext(
      Rule rule,
      PathPackageLocator packageLocator,
      Path outputDirectory,
      IgnoredSubdirectories ignoredSubdirectories,
      Environment environment,
      ImmutableMap<String, String> env,
      DownloadManager downloadManager,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      StarlarkSemantics starlarkSemantics,
      @Nullable RepositoryRemoteExecutor remoteExecutor,
      SyscallCache syscallCache,
      BlazeDirectories directories)
      throws EvalException {
    super(
        outputDirectory,
        directories,
        environment,
        env,
        downloadManager,
        timeoutScaling,
        processWrapper,
        starlarkSemantics,
        RepositoryFetchProgress.repositoryFetchContextString(
            RepositoryName.createUnvalidated(rule.getName())),
        remoteExecutor,
        /* allowWatchingPathsOutsideWorkspace= */ true);
    this.rule = rule;
    this.packageLocator = packageLocator;
    this.ignoredSubdirectories = ignoredSubdirectories;
    this.syscallCache = syscallCache;
    WorkspaceAttributeMapper attrs = WorkspaceAttributeMapper.of(rule);
    ImmutableMap.Builder<String, Object> attrBuilder = new ImmutableMap.Builder<>();
    for (String name : attrs.getAttributeNames()) {
      if (!name.equals("$local")) {
        // Attribute values should be type safe
        attrBuilder.put(
            Attribute.getStarlarkName(name), Attribute.valueToStarlark(attrs.getObject(name)));
      }
    }
    attrObject = StructProvider.STRUCT.create(attrBuilder.buildOrThrow(), "No such attribute '%s'");
  }

  @Override
  protected boolean shouldDeleteWorkingDirectoryOnClose(boolean successful) {
    return !successful;
  }

  public ImmutableMap<RepoRecordedInput.DirTree, String> getRecordedDirTreeInputs() {
    return ImmutableMap.copyOf(recordedDirTreeInputs);
  }

  @StarlarkMethod(
      name = "name",
      structField = true,
      doc =
          "The canonical name of the external repository created by this rule. This name is"
              + " guaranteed to be unique among all external repositories, but its exact format is"
              + " not specified. Use <a href='#original_name'><code>original_name</code></a>"
              + " instead to get the name that was originally specified as the <code>name</code>"
              + " when this repository rule was instantiated.")
  public String getName() {
    return rule.getName();
  }

  @StarlarkMethod(
      name = "original_name",
      structField = true,
      doc =
          "The name that was originally specified as the <code>name</code> attribute when this"
              + " repository rule was instantiated. This name is not necessarily unique among"
              + " external repositories. Use <a href='#name'><code>name</code></a> instead to get"
              + " the canonical name of the external repository.")
  public String getOriginalName() {
    String originalName = (String) rule.getAttr("$original_name", Type.STRING);
    // The original name isn't set for WORKSPACE-defined repositories as well as repositories
    // backing Bazel modules. In case of the former, the original name is the same as the name, in
    // the latter the original name doesn't matter as the restricted set of rules that can back
    // Bazel modules do not use the name.
    return originalName != null ? originalName : rule.getName();
  }

  @StarlarkMethod(
      name = "workspace_root",
      structField = true,
      doc = "The path to the root workspace of the bazel invocation.")
  public StarlarkPath getWorkspaceRoot() {
    return new StarlarkPath(this, directories.getWorkspace());
  }

  @StarlarkMethod(
      name = "attr",
      structField = true,
      doc =
          """
          A struct to access the values of the attributes. The values are provided by \
          the user (if not, a default value is used).
          """)
  public StructImpl getAttr() {
    return attrObject;
  }

  private StarlarkPath externalPath(String method, Object pathObject)
      throws EvalException, InterruptedException {
    StarlarkPath starlarkPath = getPath(pathObject);
    Path path = starlarkPath.getPath();
    if (packageLocator.getPathEntries().stream().noneMatch(root -> path.startsWith(root.asPath()))
        || path.startsWith(workingDirectory)) {
      return starlarkPath;
    }
    Path workspaceRoot = packageLocator.getWorkspaceFile(syscallCache).getParentDirectory();
    PathFragment relativePath = path.relativeTo(workspaceRoot);
    if (ignoredSubdirectories.matchingEntry(relativePath) != null) {
      return starlarkPath;
    }
    throw Starlark.errorf(
        "%s can only be applied to external paths (that is, outside the workspace or ignored in"
            + " .bazelignore)",
        method);
  }

  @StarlarkMethod(
      name = "symlink",
      doc = "Creates a symlink on the filesystem.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "target",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "The path that the symlink should point to."),
        @Param(
            name = "link_name",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "The path of the symlink to create."),
      })
  public void symlink(Object target, Object linkName, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath targetPath = getPath(target);
    StarlarkPath linkPath = getPath(linkName);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newSymlinkEvent(
            targetPath.toString(),
            linkPath.toString(),
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", linkPath);
      makeDirectories(linkPath.getPath());
      linkPath.getPath().createSymbolicLink(targetPath.getPath());
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Could not create symlink from "
                  + targetPath
                  + " to "
                  + linkPath
                  + ": "
                  + e.getMessage(),
              e),
          Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", linkPath, e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  @StarlarkMethod(
      name = "template",
      doc =
          """
          Generates a new file using a <code>template</code>. Every occurrence in \
          <code>template</code> of a key of <code>substitutions</code> will be replaced by \
          the corresponding value. The result is written in <code>path</code>. An optional \
          <code>executable</code> argument (default to true) can be set to turn on or off \
          the executable bit.
          """,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path of the file to create, relative to the repository directory."),
        @Param(
            name = "template",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path to the template file."),
        @Param(
            name = "substitutions",
            defaultValue = "{}",
            named = true,
            doc = "Substitutions to make when expanding the template."),
        @Param(
            name = "executable",
            defaultValue = "True",
            named = true,
            doc = "Set the executable flag on the created file, true by default."),
        @Param(
            name = "watch_template",
            defaultValue = "'auto'",
            positional = false,
            named = true,
            doc =
                """
                Whether to <a href="#watch">watch</a> the template file. Can be the string \
                'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking \
                the <a href="#watch"><code>watch()</code></a> method; passing 'no' does \
                not attempt to watch the file; passing 'auto' will only attempt to watch \
                the file when it is legal to do so (see <code>watch()</code> docs for more \
                information.
                """),
      })
  public void createFileFromTemplate(
      Object path,
      Object template,
      Dict<?, ?> substitutions, // <String, String> expected
      Boolean executable,
      String watchTemplate,
      StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath p = getPath(path);
    StarlarkPath t = getPath(template);
    Map<String, String> substitutionMap =
        Dict.cast(substitutions, String.class, String.class, "substitutions");
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newTemplateEvent(
            p.toString(),
            t.toString(),
            substitutionMap,
            executable,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    if (t.isDir()) {
      throw Starlark.errorf("attempting to use a directory as template: %s", t);
    }
    maybeWatch(t, ShouldWatch.fromString(watchTemplate));
    try {
      checkInOutputDirectory("write", p);
      makeDirectories(p.getPath());
      // Read and write files as raw bytes by using the Latin-1 encoding, which matches the encoding
      // used by Bazel for strings.
      String tpl = FileSystemUtils.readContent(t.getPath(), ISO_8859_1);
      for (Map.Entry<String, String> substitution : substitutionMap.entrySet()) {
        tpl =
            StringUtilities.replaceAllLiteral(tpl, substitution.getKey(), substitution.getValue());
      }
      p.getPath().delete();
      try (OutputStream stream = p.getPath().getOutputStream()) {
        stream.write(tpl.getBytes(ISO_8859_1));
      }
      if (executable) {
        p.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", p, e.getMessage()), Transience.PERSISTENT);
    }
  }

  @Override
  protected boolean isRemotable() {
    Object remotable = rule.getAttr("$remotable");
    if (remotable != null) {
      return (Boolean) remotable;
    }
    return false;
  }

  @Override
  protected ImmutableMap<String, String> getRemoteExecProperties() throws EvalException {
    return ImmutableMap.copyOf(
        Dict.cast(
            getAttr().getValue("exec_properties"), String.class, String.class, "exec_properties"));
  }

  @StarlarkMethod(
      name = "delete",
      doc =
          """
          Deletes a file or a directory. Returns a bool, indicating whether the file or directory \
          was actually deleted by this call.
          """,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = StarlarkPath.class)},
            doc =
                """
                Path of the file to delete, relative to the repository directory, or absolute. \
                Can be a path or a string.
                """),
      })
  public boolean delete(Object pathObject, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    StarlarkPath starlarkPath = externalPath("delete()", pathObject);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDeleteEvent(
            starlarkPath.toString(), identifyingStringForLogging, thread.getCallerLocation());
    env.getListener().post(w);
    try {
      Path path = starlarkPath.getPath();
      path.deleteTreesBelow();
      return path.delete();
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @StarlarkMethod(
      name = "rename",
      doc =
          """
          Renames the file or directory from <code>src</code> to <code>dst</code>. \
          Parent directories are created as needed. Fails if the destination path
          already exists. Both paths must be located within the repository.
          """,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "src",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc =
                """
                The path of the existing file or directory to rename, relative
                to the repository directory.
                """),
        @Param(
            name = "dst",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc =
                """
                The new name to which the file or directory will be renamed to,
                relative to the repository directory.
                """),
      })
  public void rename(Object srcName, Object dstName, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath srcPath = getPath(srcName);
    StarlarkPath dstPath = getPath(dstName);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newRenameEvent(
            srcPath.toString(),
            dstPath.toString(),
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", srcPath);
      checkInOutputDirectory("write", dstPath);
      if (dstPath.exists()) {
        throw new RepositoryFunctionException(
            new IOException("Could not rename " + srcPath + " to " + dstPath + ": already exists"),
            Transience.TRANSIENT);
      }
      makeDirectories(dstPath.getPath());
      srcPath.getPath().renameTo(dstPath.getPath());
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Could not rename " + srcPath + " to " + dstPath + ": " + e.getMessage(), e),
          Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not rename %s to %s: %s", srcPath, dstPath, e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  @StarlarkMethod(
      name = "patch",
      doc =
          """
          Apply a patch file to the root directory of external repository. \
          The patch file should be a standard \
          <a href="https://en.wikipedia.org/wiki/Diff#Unified_format"> \
          unified diff format</a> file. \
          The Bazel-native patch implementation doesn't support fuzz match and binary patch \
          like the patch command line tool.
          """,
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "patch_file",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc =
                """
                The patch file to apply, it can be label, relative path or absolute path. \
                If it's a relative path, it will resolve to the repository directory.
                """),
        @Param(
            name = "strip",
            named = true,
            defaultValue = "0",
            doc = "Strip the specified number of leading components from file names."),
        @Param(
            name = "watch_patch",
            defaultValue = "'auto'",
            positional = false,
            named = true,
            doc =
                """
                Whether to <a href="#watch">watch</a> the patch file. Can be the string \
                'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking \
                the <a href="#watch"><code>watch()</code></a> method; passing 'no' does \
                not attempt to watch the file; passing 'auto' will only attempt to watch \
                the file when it is legal to do so (see <code>watch()</code> docs for more \
                information.
                """),
      })
  public void patch(Object patchFile, StarlarkInt stripI, String watchPatch, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    int strip = Starlark.toInt(stripI, "strip");
    StarlarkPath starlarkPath = getPath(patchFile);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newPatchEvent(
            starlarkPath.toString(),
            strip,
            identifyingStringForLogging,
            thread.getCallerLocation());
    env.getListener().post(w);
    if (starlarkPath.isDir()) {
      throw Starlark.errorf("attempting to use a directory as patch file: %s", starlarkPath);
    }
    maybeWatch(starlarkPath, ShouldWatch.fromString(watchPatch));
    try {
      PatchUtil.apply(starlarkPath.getPath(), strip, workingDirectory);
    } catch (PatchFailedException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Error applying patch %s: %s", starlarkPath, e.getMessage()),
          Transience.TRANSIENT);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @StarlarkMethod(
      name = "watch_tree",
      doc =
          """
          Tells Bazel to watch for changes to any files or directories transitively under the \
          given path. Any changes to the contents of files, the existence of files or \
          directories, file names or directory names, will cause this repo to be \
          refetched.<p>Note that attempting to watch paths inside the repo currently being \
          fetched will result in an error.
          """,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "Path of the directory tree to watch."),
      })
  public void watchTree(Object path)
      throws EvalException, InterruptedException, RepositoryFunctionException {
    StarlarkPath p = getPath(path);
    if (!p.isDir()) {
      throw Starlark.errorf("can't call watch_tree() on non-directory %s", p);
    }
    RepoCacheFriendlyPath repoCacheFriendlyPath =
        toRepoCacheFriendlyPath(p.getPath(), ShouldWatch.YES);
    if (repoCacheFriendlyPath == null) {
      return;
    }
    try {
      var recordedInput = new RepoRecordedInput.DirTree(repoCacheFriendlyPath);
      DirectoryTreeDigestValue digestValue =
          (DirectoryTreeDigestValue)
              env.getValueOrThrow(recordedInput.getSkyKey(directories), IOException.class);
      if (digestValue == null) {
        throw new NeedsSkyframeRestartException();
      }

      recordedDirTreeInputs.put(recordedInput, digestValue.hexDigest());
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public String toString() {
    return "repository_ctx[" + rule.getLabel() + "]";
  }
}
