// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkdocextract;

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_KEYWORD_ONLY;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_KWARGS;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_ORDINARY;
import static com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole.PARAM_ROLE_VARARGS;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionDeprecationInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionParamRole;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.FunctionReturnInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.OriginKey;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.StarlarkFunctionInfo;
import com.google.devtools.build.lib.util.StringEncoding;
import com.google.devtools.starlark.common.DocstringUtils;
import com.google.devtools.starlark.common.DocstringUtils.DocstringInfo;
import com.google.devtools.starlark.common.DocstringUtils.DocstringParseError;
import com.google.devtools.starlark.common.DocstringUtils.ParameterDoc;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.syntax.Location;

/** Contains a number of utility methods for functions and parameters. */
public final class StarlarkFunctionInfoExtractor {
  private final LabelRenderer labelRenderer;

  private StarlarkFunctionInfoExtractor(LabelRenderer labelRenderer) {
    this.labelRenderer = labelRenderer;
  }

  /**
   * Create and return a {@link StarlarkFunctionInfo} object encapsulating information obtained from
   * the given function and from its parsed docstring.
   *
   * @param functionName the name of the function in the target scope. (Note this is not necessarily
   *     the original exported function name; the function may have been renamed in the target
   *     Starlark file's scope)
   * @param fn the function object
   * @param labelRenderer a string renderer for {@link Label} values in argument defaults and for
   *     the {@link OriginKey}'s file
   * @throws ExtractionException if the function's docstring is malformed
   */
  public static StarlarkFunctionInfo fromNameAndFunction(
      String functionName, StarlarkFunction fn, LabelRenderer labelRenderer)
      throws ExtractionException {
    return new StarlarkFunctionInfoExtractor(labelRenderer).extract(functionName, fn);
  }

  private StarlarkFunctionInfo extract(String functionName, StarlarkFunction fn)
      throws ExtractionException {
    Map<String, String> paramNameToDocMap = Maps.newLinkedHashMap();
    StarlarkFunctionInfo.Builder functionInfoBuilder =
        StarlarkFunctionInfo.newBuilder().setFunctionName(internalToUnicode(functionName));
    functionInfoBuilder.setOriginKey(getFunctionOriginKey(fn));
    String doc = fn.getDocumentation();
    if (doc != null) {
      List<DocstringParseError> parseErrors = Lists.newArrayList();
      DocstringInfo docstringInfo = DocstringUtils.parseDocstring(doc, parseErrors);
      if (!parseErrors.isEmpty()) {
        throw makeDocstringExtractionException(functionName, fn.getLocation(), parseErrors);
      }
      StringBuilder functionDescription = new StringBuilder(docstringInfo.getSummary());
      if (!docstringInfo.getSummary().isEmpty() && !docstringInfo.getLongDescription().isEmpty()) {
        functionDescription.append("\n\n");
      }
      functionDescription.append(docstringInfo.getLongDescription());
      functionInfoBuilder.setDocString(internalToUnicode(functionDescription.toString()));
      for (ParameterDoc paramDoc : docstringInfo.getParameters()) {
        paramNameToDocMap.put(paramDoc.getParameterName(), paramDoc.getDescription());
      }
      String returns = docstringInfo.getReturns();
      if (!returns.isEmpty()) {
        functionInfoBuilder.setReturn(
            FunctionReturnInfo.newBuilder().setDocString(internalToUnicode(returns)).build());
      }
      String deprecated = docstringInfo.getDeprecated();
      if (!deprecated.isEmpty()) {
        functionInfoBuilder.setDeprecated(
            FunctionDeprecationInfo.newBuilder()
                .setDocString(internalToUnicode(deprecated))
                .build());
      }
    }
    functionInfoBuilder.addAllParameter(parameterInfos(fn, paramNameToDocMap));
    return functionInfoBuilder.build();
  }

  private static ExtractionException makeDocstringExtractionException(
      String functionName, Location definedLocation, List<DocstringParseError> parseErrors) {
    StringBuilder message = new StringBuilder();
    message.append(
        String.format(
            "Unable to generate documentation for function %s (defined at %s) "
                + "due to malformed docstring. Parse errors:\n",
            functionName, definedLocation));
    for (DocstringParseError parseError : parseErrors) {
      message.append(
          String.format(
              "  %s line %s: %s\n",
              definedLocation,
              parseError.getLineNumber(),
              parseError.getMessage().replace('\n', ' ')));
    }
    return new ExtractionException(message.toString());
  }

  private FunctionParamInfo forParam(
      String name,
      Optional<String> docString,
      @Nullable Object defaultValue,
      FunctionParamRole role) {
    FunctionParamInfo.Builder paramBuilder =
        FunctionParamInfo.newBuilder().setName(internalToUnicode(name)).setRole(role);
    docString.map(StringEncoding::internalToUnicode).ifPresent(paramBuilder::setDocString);
    if (defaultValue == null) {
      paramBuilder.setMandatory(true);
    } else {
      paramBuilder
          .setDefaultValue(internalToUnicode(labelRenderer.repr(defaultValue)))
          .setMandatory(false);
    }
    return paramBuilder.build();
  }

  /** Constructor to be used for *args or **kwargs. */
  private static FunctionParamInfo forSpecialParam(
      String name, Optional<String> docString, FunctionParamRole role) {
    FunctionParamInfo.Builder paramBuilder =
        FunctionParamInfo.newBuilder()
            .setName(internalToUnicode(name))
            .setRole(role)
            .setMandatory(false);
    docString.map(StringEncoding::internalToUnicode).ifPresent(paramBuilder::setDocString);
    return paramBuilder.build();
  }

  private ImmutableList<FunctionParamInfo> parameterInfos(
      StarlarkFunction fn, Map<String, String> parameterDoc) {
    ImmutableList<String> names = fn.getParameterNames();
    int numOrdinaryParams = fn.getNumOrdinaryParameters();
    int numKeywordOnlyParams = fn.getNumKeywordOnlyParameters();
    int varargsIndex = fn.hasVarargs() ? numOrdinaryParams + numKeywordOnlyParams : -1;
    int kwargsIndex = fn.hasKwargs() ? names.size() - 1 : -1;
    checkState(varargsIndex == -1 || varargsIndex < names.size());
    checkState(kwargsIndex == -1 || varargsIndex == -1 || kwargsIndex == varargsIndex + 1);

    ImmutableList.Builder<FunctionParamInfo> infos = ImmutableList.builder();
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      FunctionParamInfo info;
      if (i == varargsIndex) {
        // *args
        Optional<String> doc = Optional.ofNullable(parameterDoc.get("*" + name));
        info = forSpecialParam(name, doc, PARAM_ROLE_VARARGS);
      } else if (i == kwargsIndex) {
        // **kwargs
        Optional<String> doc = Optional.ofNullable(parameterDoc.get("**" + name));
        info = forSpecialParam(name, doc, PARAM_ROLE_KWARGS);
      } else {
        // regular parameter
        Optional<String> doc = Optional.ofNullable(parameterDoc.get(name));
        info =
            forParam(
                name,
                doc,
                fn.getDefaultValue(i),
                i < numOrdinaryParams ? PARAM_ROLE_ORDINARY : PARAM_ROLE_KEYWORD_ONLY);
      }
      infos.add(info);
    }
    return infos.build();
  }

  private OriginKey getFunctionOriginKey(StarlarkFunction fn) {
    OriginKey.Builder builder = OriginKey.newBuilder();
    // We can't just `builder.setName(fn.getName())` - fn could be a nested function or a lambda, so
    // fn.getName() may not be a unique name in fn's module. Instead, we look for fn in the module's
    // globals, and if we fail to find it, we leave OriginKey.name unset.
    // For nested functions and lambdas, we could theoretically derive OriginKey.name from
    // fn.getName() and fn.getLocation(), e.g. "<foo at 123:4>". It's unclear how useful this would
    // be in practice; and the location would be highly likely (as compared to docstring content) to
    // change with any edits to the .bzl file, resulting in lots of churn in golden tests.
    for (Map.Entry<String, Object> entry : fn.getModule().getGlobals().entrySet()) {
      if (fn.equals(entry.getValue())) {
        builder.setName(internalToUnicode(entry.getKey()));
        break;
      }
    }
    // TODO(arostovtsev): also recurse into global structs/dicts/lists

    BazelModuleContext moduleContext = BazelModuleContext.of(fn.getModule());
    if (moduleContext != null) {
      builder.setFile(internalToUnicode(labelRenderer.render(moduleContext.label())));
    }
    return builder.build();
  }
}
