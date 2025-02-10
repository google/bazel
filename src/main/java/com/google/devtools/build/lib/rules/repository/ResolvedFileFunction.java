// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.rules.repository.ResolvedFileValue.ResolvedFileKey;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.StarlarkUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/** Function computing the Starlark value of 'resolved' in a file. */
public class ResolvedFileFunction implements SkyFunction {

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, SkyFunctionException {

    ResolvedFileKey key = (ResolvedFileKey) skyKey;
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (starlarkSemantics == null) {
      return null;
    }
    FileValue fileValue = (FileValue) env.getValue(FileValue.key(key.getPath()));
    if (fileValue == null) {
      return null;
    }
    try {
      if (!fileValue.exists()) {
        throw new ResolvedFileFunctionException(
            new NoSuchThingException("Specified resolved file '" + key.getPath() + "' not found."));
      } else {
        // read
        byte[] bytes =
            FileSystemUtils.readWithKnownFileSize(
                key.getPath().asPath(), key.getPath().asPath().getFileSize());

        // parse
        ParserInput parserInput;
        try {
          parserInput =
              StarlarkUtil.createParserInput(
                  bytes,
                  key.getPath().asPath().toString(),
                  starlarkSemantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
                  env.getListener());
        } catch (
            @SuppressWarnings(
                "UnusedException") // createParserInput() reports its own error message
            StarlarkUtil.InvalidUtf8Exception e) {
          throw resolvedValueError("Failed to read resolved file " + key.getPath());
        }
        StarlarkFile file = StarlarkFile.parse(parserInput);
        if (!file.ok()) {
          Event.replayEventsOn(env.getListener(), file.errors());
          throw resolvedValueError("Failed to parse resolved file " + key.getPath());
        }

        Module module = Module.create();

        // resolve & compile
        Program prog;
        try {
          prog = Program.compileFile(file, module);
        } catch (SyntaxError.Exception ex) {
          Event.replayEventsOn(env.getListener(), ex.errors());
          throw resolvedValueError("Failed to validate resolved file " + key.getPath());
        }

        // execute
        try (Mutability mu = Mutability.create("resolved file", key.getPath())) {
          StarlarkThread thread =
              StarlarkThread.create(
                  mu,
                  starlarkSemantics,
                  /* contextDescription= */ "",
                  SymbolGenerator.create(skyKey));
          Starlark.execFileProgram(prog, module, thread);
        } catch (EvalException ex) {
          env.getListener()
              .handle(Event.error(ex.getInnermostLocation(), ex.getMessageWithStack()));
          throw resolvedValueError("Failed to evaluate resolved file " + key.getPath());
        }
        Object resolved = module.getGlobal("resolved");
        if (resolved == null) {
          throw resolvedValueError(
              "Symbol 'resolved' not exported in resolved file " + key.getPath());
        }
        if (!(resolved instanceof List)) {
          throw resolvedValueError(
              "Symbol 'resolved' in resolved file " + key.getPath() + " not a list");
        }
        ImmutableList.Builder<Map<String, Object>> result
            = new ImmutableList.Builder<Map<String, Object>>();
        for (Object entry : (List) resolved) {
          if (!(entry instanceof Map)) {
            throw resolvedValueError(
                "Symbol 'resolved' in resolved file "
                    + key.getPath()
                    + " contains a non-map entry");
          }
          ImmutableMap.Builder<String, Object> entryBuilder
              = new ImmutableMap.Builder<String, Object>();
          for (Map.Entry<?, ?> keyValue : ((Map<?, ?>) entry).entrySet()) {
            Object attribute = keyValue.getKey();
            if (!(attribute instanceof String)) {
              throw resolvedValueError(
                  "Symbol 'resolved' in resolved file "
                      + key.getPath()
                      + " contains a non-string key in one of its entries");
            }
            entryBuilder.put((String) attribute, keyValue.getValue());
          }
          result.add(entryBuilder.build());
        }
        return new ResolvedFileValue(result.build());
      }
    } catch (IOException e) {
      throw new ResolvedFileFunctionException(e);
    }
  }

  private static ResolvedFileFunctionException resolvedValueError(String message) {
    return new ResolvedFileFunctionException(
        new BuildFileContainsErrorsException(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER, message));
  }

  private static final class ResolvedFileFunctionException extends SkyFunctionException {
    ResolvedFileFunctionException(IOException e) {
      super(e, SkyFunctionException.Transience.PERSISTENT);
    }

    ResolvedFileFunctionException(NoSuchThingException e) {
      super(e, SkyFunctionException.Transience.PERSISTENT);
    }
  }
}
