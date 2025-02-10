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

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.BazelCompileContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.AutoloadSymbols;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * A Skyframe function that compiles the .bzl file denoted by a Label.
 *
 * <p>Given a {@link Label} referencing a Starlark file, BzlCompileFunction loads, parses, resolves,
 * and compiles it. The Label must be absolute, and must not reference the special {@code external}
 * package. If the file (or the package containing it) doesn't exist, the function doesn't fail, but
 * instead returns a specific {@code NO_FILE} {@link BzlCompileValue}.
 */
// TODO(adonovan): actually compile. The name is a step ahead of the implementation.
public class BzlCompileFunction implements SkyFunction {

  private final BazelStarlarkEnvironment bazelStarlarkEnvironment;
  private final HashFunction hashFunction;

  public BzlCompileFunction(
      BazelStarlarkEnvironment bazelStarlarkEnvironment, HashFunction hashFunction) {
    this.bazelStarlarkEnvironment = bazelStarlarkEnvironment;
    this.hashFunction = hashFunction;
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    try {
      return computeInline(
          (BzlCompileValue.Key) skyKey.argument(), env, bazelStarlarkEnvironment, hashFunction);
    } catch (FailedIOException e) {
      throw new FunctionException(e);
    }
  }

  @Nullable
  static BzlCompileValue computeInline(
      BzlCompileValue.Key key,
      Environment env,
      BazelStarlarkEnvironment bazelStarlarkEnvironment,
      HashFunction hashFunction)
      throws FailedIOException, InterruptedException {
    byte[] bytes;
    byte[] digest;
    String inputName;

    if (key.kind == BzlCompileValue.Kind.EMPTY_PRELUDE) {
      // Default prelude is empty.
      bytes = new byte[] {};
      digest = null;
      inputName = "<default prelude>";
    } else {

      // Obtain the file.
      RootedPath rootedPath = RootedPath.toRootedPath(key.root, key.label.toPathFragment());
      SkyKey fileSkyKey = FileValue.key(rootedPath);
      FileValue fileValue = null;
      try {
        fileValue = (FileValue) env.getValueOrThrow(fileSkyKey, IOException.class);
      } catch (IOException e) {
        throw new FailedIOException(e, Transience.PERSISTENT);
      }
      if (fileValue == null) {
        return null;
      }

      if (fileValue.exists()) {
        if (!fileValue.isFile()) {
          return fileValue.isDirectory()
              ? BzlCompileValue.noFile("cannot load '%s': is a directory", key.label)
              : BzlCompileValue.noFile(
                  "cannot load '%s': not a regular file (dangling link?)", key.label);
        }

        // Read the file.
        Path path = rootedPath.asPath();
        try {
          bytes =
              fileValue.isSpecialFile()
                  ? FileSystemUtils.readContent(path)
                  : FileSystemUtils.readWithKnownFileSize(path, fileValue.getSize());
        } catch (IOException e) {
          throw new FailedIOException(e, Transience.TRANSIENT);
        }
        digest = fileValue.getDigest(); // may be null
        inputName = path.toString();
      } else {
        if (key.kind == BzlCompileValue.Kind.PRELUDE) {
          // A non-existent prelude is fine.
          bytes = new byte[] {};
          digest = null;
          inputName = "<default prelude>";
        } else {
          return BzlCompileValue.noFile("cannot load '%s': no such file", key.label);
        }
      }
    }

    // Compute digest if we didn't already get it from a fileValue.
    if (digest == null) {
      digest = hashFunction.hashBytes(bytes).asBytes();
    }

    StarlarkSemantics semantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (semantics == null) {
      return null;
    }

    ImmutableMap<String, Object> predeclared;
    if (key.isSclDialect()) {
      predeclared = bazelStarlarkEnvironment.getStarlarkGlobals().getSclToplevels();
    } else if (key.kind == BzlCompileValue.Kind.BUILTINS) {
      predeclared = bazelStarlarkEnvironment.getBuiltinsBzlEnv();
    } else {
      // Use the predeclared environment for BUILD-loaded bzl files, ignoring injection. It is not
      // the right env for the actual evaluation of BUILD-loaded bzl files because it doesn't
      // map to the injected symbols. But the names of the symbols are the same, and the names are
      // all we need to do symbol resolution.
      //
      // For WORKSPACE-loaded bzl files, the env isn't quite right not because of injection but
      // because the "native" object is different. But A) that will be fixed with #11954, and B) we
      // don't care for the same reason as above.

      // Takes into account --incompatible_autoload_externally, similarly to the comment above, this
      // only defines the correct set of symbols, but does not load them yet.
      AutoloadSymbols autoloadSymbols = AutoloadSymbols.AUTOLOAD_SYMBOLS.get(env);
      if (autoloadSymbols == null) {
        return null;
      }
      predeclared = autoloadSymbols.getUninjectedBuildBzlEnv(key.getLabel());
    }

    // We have all deps. Parse, resolve, and return.
    ParserInput input;
    try {
      input =
          StarlarkUtil.createParserInput(
              bytes,
              inputName,
              semantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
              env.getListener());
    } catch (
        @SuppressWarnings("UnusedException") // createParserInput() reports its own error message
        StarlarkUtil.InvalidUtf8Exception e) {
      return BzlCompileValue.noFile("compilation of '%s' failed", inputName);
    }
    FileOptions options =
        FileOptions.builder()
            // By default, Starlark load statements create file-local bindings.
            // However, the BUILD prelude typically contains nothing but load
            // statements whose bindings are intended to be visible in all BUILD
            // files. The loadBindsGlobally flag allows us to retrieve them.
            .loadBindsGlobally(key.isBuildPrelude())
            // .scl files should be ASCII-only in string literals.
            // TODO(bazel-team): It'd be nice if we could intercept non-ASCII errors from the lexer,
            // and modify the displayed message to clarify to the user that the string would be
            // permitted in a .bzl file. But there's no easy way to do that short of either string
            // matching the error message or reworking the interpreter API to put more structured
            // detail in errors (i.e. new fields or error subclasses).
            .stringLiteralsAreAsciiOnly(key.isSclDialect())
            .build();
    StarlarkFile file = StarlarkFile.parse(input, options);

    // compile
    final Module module;

    if (key.kind == BzlCompileValue.Kind.EMPTY_PRELUDE) {
      // The empty prelude has no label, so we can't use it to filter the predeclareds.
      // This doesn't matter since the empty prelude doesn't attempt to access any predeclareds
      // anyway.
      module = Module.withPredeclared(semantics, predeclared);
    } else {
      // The BazelCompileContext holds additional contextual info to be associated with the Module
      // The information is used to filter predeclareds
      BazelCompileContext bazelCompileContext =
          BazelCompileContext.create(key.label, file.getName());
      module = Module.withPredeclaredAndData(semantics, predeclared, bazelCompileContext);
    }
    try {
      Program prog = Program.compileFile(file, module);
      return BzlCompileValue.withProgram(prog, digest);
    } catch (SyntaxError.Exception ex) {
      Event.replayEventsOn(env.getListener(), ex.errors());
      return BzlCompileValue.noFile(
          "compilation of module '%s'%s failed",
          key.label.toPathFragment(),
          StarlarkBuiltinsValue.isBuiltinsRepo(key.label.getRepository()) ? " (internal)" : "");
    }
  }

  static final class FailedIOException extends Exception {
    private final Transience transience;

    private FailedIOException(IOException cause, Transience transience) {
      super(cause.getMessage(), cause);
      this.transience = transience;
    }

    Transience getTransience() {
      return transience;
    }
  }

  private static final class FunctionException extends SkyFunctionException {
    private FunctionException(FailedIOException cause) {
      super(cause, cause.transience);
    }
  }
}
