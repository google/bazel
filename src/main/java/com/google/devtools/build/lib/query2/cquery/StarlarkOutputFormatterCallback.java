// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.query2.cquery;

import static com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition.COMMAND_LINE_OPTION_PREFIX;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.server.FailureDetails.ConfigurableQuery;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.common.options.OptionDefinition;
import com.google.devtools.common.options.OptionsParser;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/**
 * Starlark output formatter for cquery results. Each configured target will result in an evaluation
 * of the Starlark expression specified by {@code --expr}.
 */
public class StarlarkOutputFormatterCallback extends CqueryThreadsafeCallback {
  private class CqueryDialectGlobals {
    @StarlarkMethod(
        name = "build_options",
        documented = false,
        parameters = {
          @Param(name = "target"),
        })
    public Object buildOptions(CqueryNode target) {
      BuildConfigurationValue config = getConfiguration(target.getConfigurationKey());

      if (config == null) {
        // config is null for input file configured targets.
        return Starlark.NONE;
      }

      BuildOptions buildOptions = config.getOptions();
      ImmutableMap.Builder<String, Object> result = ImmutableMap.builder();

      // Add all build options from each native configuration fragment.
      for (FragmentOptions fragmentOptions : buildOptions.getNativeOptions()) {
        Class<? extends FragmentOptions> optionClass = fragmentOptions.getClass();
        for (OptionDefinition def : OptionsParser.getOptionDefinitions(optionClass)) {
          String optionName = def.getOptionName();
          String optionKey = COMMAND_LINE_OPTION_PREFIX + optionName;

          FragmentOptions options = buildOptions.get(optionClass);
          Object optionValue = def.getValue(options);

          try {
            // fromJava is not a deep validity check.
            // It is not guaranteed to catch all errors,
            // nor does it specify how it reports the errors it does find.
            // Passing arbitrary Java values into the Starlark interpreter
            // is not safe.
            // TODO(cparsons,twigg): fix it: convert value by explicit cases.
            result.put(optionKey, Starlark.fromJava(optionValue, null));
          } catch (IllegalArgumentException | NullPointerException ex) {
            // optionValue is not a valid Starlark value, so skip this option.
            // (e.g. tristate; a map with null values)
          }
        }
      }

      // Add Starlark options.
      for (Map.Entry<Label, Object> e : buildOptions.getStarlarkOptions().entrySet()) {
        result.put(e.getKey().toString(), Starlark.fromJava(e.getValue(), null));
      }
      return result.buildOrThrow();
    }

    @StarlarkMethod(
        name = "providers",
        documented = false,
        parameters = {
          @Param(name = "target"),
        })
    public Object providers(CqueryNode target) {
      Dict<String, Object> ret = target.getProvidersDictForQuery();
      if (ret == null) {
        return Starlark.NONE;
      }
      return ret;
    }
  }

  // Starlark function with single required parameter "target", a CqueryNode query result.
  private final StarlarkFunction formatFn;
  private final StarlarkSemantics starlarkSemantics;

  StarlarkOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<CqueryNode> accessor,
      StarlarkSemantics starlarkSemantics)
      throws QueryException, InterruptedException {
    super(eventHandler, options, out, skyframeExecutor, accessor, /* uniquifyResults= */ false);
    this.starlarkSemantics = starlarkSemantics;

    ParserInput input = null;
    String exceptionMessagePrefix;
    if (!options.file.isEmpty()) {
      if (!options.expr.isEmpty()) {
        throw new QueryException(
            "You must not specify both --starlark:expr and --starlark:file",
            Query.Code.ILLEGAL_FLAG_COMBINATION);
      }
      exceptionMessagePrefix = "invalid --starlark:file: ";
      try {
        input = ParserInput.readFile(options.file);
      } catch (IOException ex) {
        throw new QueryException(
            exceptionMessagePrefix + "failed to read " + ex.getMessage(),
            Query.Code.QUERY_FILE_READ_FAILURE);
      }
    } else {
      exceptionMessagePrefix = "invalid --starlark:expr: ";
      String expr = options.expr.isEmpty() ? "str(target.label)" : options.expr;
      // Validate that options.expr is a pure expression (for example, that it does not attempt
      // to escape its scope via unbalanced parens).
      ParserInput exprParserInput = ParserInput.fromString(expr, "--starlark:expr");
      try {
        Expression.parse(exprParserInput);
      } catch (SyntaxError.Exception ex) {
        throw new QueryException(
            exceptionMessagePrefix + ex.getMessage(), ConfigurableQuery.Code.STARLARK_SYNTAX_ERROR);
      }

      // Create a synthetic file that defines a function with single parameter "target",
      // whose body is provided by the user's expression. Dynamic errors will have the wrong column.
      String fileBody = "def format(target): return (" + expr + ")";
      input = ParserInput.fromString(fileBody, "--starlark:expr");
    }

    StarlarkFile file = StarlarkFile.parse(input, FileOptions.DEFAULT);
    if (!file.ok()) {
      Event.replayEventsOn(eventHandler, file.errors());
    }
    try (Mutability mu = Mutability.create("formatter")) {
      ImmutableMap.Builder<String, Object> env = ImmutableMap.builder();
      Starlark.addMethods(env, new CqueryDialectGlobals(), starlarkSemantics);
      env.putAll(StarlarkGlobalsImpl.INSTANCE.getUtilToplevelsForCquery());
      Module module = Module.withPredeclared(starlarkSemantics, env.buildOrThrow());

      StarlarkThread thread = StarlarkThread.createTransient(mu, starlarkSemantics);
      Starlark.execFile(input, FileOptions.DEFAULT, module, thread);
      Object formatFn = module.getGlobal("format");
      if (formatFn == null) {
        throw new QueryException(
            exceptionMessagePrefix + "file does not define 'format'",
            ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR);
      }
      if (!(formatFn instanceof StarlarkFunction)) {
        throw new QueryException(
            exceptionMessagePrefix
                + "got "
                + Starlark.type(formatFn)
                + " for 'format', want function",
            ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR);
      }
      this.formatFn = (StarlarkFunction) formatFn;
      if (this.formatFn.getParameterNames().size() != 1) {
        throw new QueryException(
            exceptionMessagePrefix + "'format' function must take exactly 1 argument",
            ConfigurableQuery.Code.FORMAT_FUNCTION_ERROR);
      }
    } catch (SyntaxError.Exception ex) {
      throw new QueryException(
          exceptionMessagePrefix + ex.getMessage(), ConfigurableQuery.Code.STARLARK_SYNTAX_ERROR);
    } catch (EvalException ex) {
      throw new QueryException(
          exceptionMessagePrefix + ex.getMessageWithStack(),
          ConfigurableQuery.Code.STARLARK_EVAL_ERROR);
    }
  }

  @Override
  public String getName() {
    return "starlark";
  }

  @Override
  public void processOutput(Iterable<CqueryNode> partialResult) throws InterruptedException {
    for (CqueryNode target : partialResult) {
      try {
        StarlarkThread thread =
            StarlarkThread.createTransient(
                Mutability.create("cquery evaluation"), starlarkSemantics);
        thread.setMaxExecutionSteps(500_000L);

        // Invoke formatFn with `target` argument.
        Object result = Starlark.positionalOnlyCall(thread, this.formatFn, target);

        addResult(Starlark.str(result, thread.getSemantics()));
      } catch (EvalException ex) {
        eventHandler.handle(
            Event.error(
                String.format(
                    "Starlark evaluation error for %s: %s",
                    target.getOriginalLabel(), ex.getMessageWithStack())));
        continue;
      }
    }
  }
}
