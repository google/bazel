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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.DotBazelFileSyntaxChecker;
import com.google.devtools.build.lib.packages.VendorThreadContext;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.StarlarkUtil;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * The function to evaluate the VENDOR.bazel file under the vendor directory specified by the flag:
 * --vendor_dir.
 */
public class VendorFileFunction implements SkyFunction {

  private static final String VENDOR_FILE_HEADER =
      StringEncoding.unicodeToInternal(
          """
###############################################################################
# This file is used to configure how external repositories are handled in vendor mode.
# ONLY the two following functions can be used:
#
# ignore('@@<canonical repo name>', ...) is used to completely ignore this repo from vendoring.
# Bazel will use the normal external cache and fetch process for this repo.
#
# pin('@@<canonical repo name>', ...) is used to pin the contents of this repo under the vendor
# directory as if there is a --override_repository flag for this repo.
# Note that Bazel will NOT update the vendored source for this repo while running vendor command
# unless it's unpinned. The user can modify and maintain the vendored source for this repo manually.
###############################################################################
""");

  private final BazelStarlarkEnvironment starlarkEnv;

  public VendorFileFunction(BazelStarlarkEnvironment starlarkEnv) {
    this.starlarkEnv = starlarkEnv;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    if (RepositoryDelegatorFunction.VENDOR_DIRECTORY.get(env).isEmpty()) {
      throw new VendorFileFunctionException(
          new IllegalStateException(
              "VENDOR.bazel file is not accessible with vendor mode off (without --vendor_dir"
                  + " flag)"),
          Transience.PERSISTENT);
    }

    Path vendorPath = RepositoryDelegatorFunction.VENDOR_DIRECTORY.get(env).get();
    RootedPath vendorFilePath =
        RootedPath.toRootedPath(Root.fromPath(vendorPath), LabelConstants.VENDOR_FILE_NAME);

    FileValue vendorFileValue = (FileValue) env.getValue(FileValue.key(vendorFilePath));
    if (vendorFileValue == null) {
      return null;
    }
    if (!vendorFileValue.exists()) {
      createVendorFile(vendorPath, vendorFilePath.asPath());
      return VendorFileValue.create(ImmutableList.of(), ImmutableList.of());
    }

    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    VendorThreadContext context =
        getVendorFileContext(env, skyKey, vendorFilePath.asPath(), starlarkSemantics);
    return VendorFileValue.create(context.getIgnoredRepos(), context.getPinnedRepos());
  }

  private VendorThreadContext getVendorFileContext(
      Environment env, SkyKey skyKey, Path vendorFilePath, StarlarkSemantics starlarkSemantics)
      throws VendorFileFunctionException, InterruptedException {
    try (Mutability mu = Mutability.create("vendor file")) {
      StarlarkFile vendorFile = readAndParseVendorFile(vendorFilePath, env, starlarkSemantics);
      new DotBazelFileSyntaxChecker("VENDOR.bazel files", /* canLoadBzl= */ false)
          .check(vendorFile);
      net.starlark.java.eval.Module predeclaredEnv =
          net.starlark.java.eval.Module.withPredeclared(
              starlarkSemantics, starlarkEnv.getStarlarkGlobals().getVendorToplevels());
      Program program = Program.compileFile(vendorFile, predeclaredEnv);
      StarlarkThread thread =
          StarlarkThread.create(
              mu, starlarkSemantics, /* contextDescription= */ "", SymbolGenerator.create(skyKey));
      VendorThreadContext context = new VendorThreadContext();
      context.storeInThread(thread);
      Starlark.execFileProgram(program, predeclaredEnv, thread);
      return context;
    } catch (SyntaxError.Exception | EvalException e) {
      throw new VendorFileFunctionException(
          new BadVendorFileException("error parsing VENDOR.bazel file: " + e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  private void createVendorFile(Path vendorPath, Path vendorFilePath)
      throws VendorFileFunctionException {
    try {
      vendorPath.createDirectoryAndParents();
      FileSystemUtils.writeContent(vendorFilePath, ISO_8859_1, VENDOR_FILE_HEADER);
    } catch (IOException e) {
      throw new VendorFileFunctionException(
          new IOException("error creating VENDOR.bazel file", e), Transience.TRANSIENT);
    }
  }

  private static StarlarkFile readAndParseVendorFile(
      Path path, Environment env, StarlarkSemantics starlarkSemantics)
      throws VendorFileFunctionException {
    byte[] contents;
    try {
      contents = FileSystemUtils.readWithKnownFileSize(path, path.getFileSize());
    } catch (IOException e) {
      throw new VendorFileFunctionException(
          new IOException("error reading VENDOR.bazel file", e), Transience.TRANSIENT);
    }
    ParserInput parserInput;
    try {
      parserInput =
          StarlarkUtil.createParserInput(
              contents,
              path.getPathString(),
              starlarkSemantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
              env.getListener());
    } catch (
        @SuppressWarnings("UnusedException") // createParserInput() reports its own error message
        StarlarkUtil.InvalidUtf8Exception e) {
      throw new VendorFileFunctionException(
          new BadVendorFileException("error reading VENDOR.bazel file"), Transience.PERSISTENT);
    }
    StarlarkFile starlarkFile = StarlarkFile.parse(parserInput);
    if (!starlarkFile.ok()) {
      Event.replayEventsOn(env.getListener(), starlarkFile.errors());
      throw new VendorFileFunctionException(
          new BadVendorFileException("error parsing VENDOR.bazel file"), Transience.PERSISTENT);
    }
    return starlarkFile;
  }

  /** Thrown when something is wrong with the contents of the VENDOR.bazel file. */
  public static class BadVendorFileException extends Exception {
    public BadVendorFileException(String message) {
      super(message);
    }
  }

  static class VendorFileFunctionException extends SkyFunctionException {
    private VendorFileFunctionException(Exception e, Transience transience) {
      super(e, transience);
    }
  }
}
