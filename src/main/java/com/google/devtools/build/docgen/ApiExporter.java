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
package com.google.devtools.build.docgen;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.ApiContext;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.Builtins;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.Callable;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.Param;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.Type;
import com.google.devtools.build.docgen.builtin.BuiltinProtos.Value;
import com.google.devtools.build.docgen.starlark.StarlarkConstructorMethodDoc;
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander;
import com.google.devtools.build.docgen.starlark.StarlarkDocPage;
import com.google.devtools.build.docgen.starlark.StarlarkMethodDoc;
import com.google.devtools.build.docgen.starlark.StarlarkParamDoc;
import com.google.devtools.common.options.OptionsParser;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import net.starlark.java.annot.StarlarkAnnotations;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.BuiltinFunction;
import net.starlark.java.eval.GuardedValue;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;

/** The main class for the Starlark documentation generator. */
public class ApiExporter {

  private static void appendTypes(
      Builtins.Builder builtins, StarlarkDocPage docPage, List<RuleDocumentation> nativeRules)
      throws BuildEncyclopediaDocException {
    Type.Builder type = Type.newBuilder();
    type.setName(docPage.getName());
    type.setDoc(docPage.getDocumentation());
    for (StarlarkMethodDoc meth : docPage.getJavaMethods()) {
      // Constructors are exported as global symbols.
      if (!(meth instanceof StarlarkConstructorMethodDoc)) {
        Value.Builder value = collectMethodInfo(meth);
        if (type.getName().equals("native")) {
          // Methods from the native package are available as top level functions in BUILD files.
          value.setApiContext(ApiContext.BUILD);
          builtins.addGlobal(value);

          value.setApiContext(ApiContext.BZL);
          type.addField(value);
        } else {
          value.setApiContext(ApiContext.ALL);
          type.addField(value);
        }
      }
    }
    if (type.getName().equals("native")) {
      for (RuleDocumentation rule : nativeRules) {
        Value.Builder field = collectRuleInfo(rule);
        field.setApiContext(ApiContext.BZL);
        type.addField(field);
      }
    }
    builtins.addType(type);
  }

  private static void appendGlobals(
      Builtins.Builder builtins,
      Map<String, Object> globals,
      Map<String, StarlarkMethodDoc> globalToDoc,
      Map<String, StarlarkConstructorMethodDoc> typeNameToConstructor,
      ApiContext context) {
    for (Entry<String, Object> entry : globals.entrySet()) {
      String name = entry.getKey();
      Object obj = entry.getValue();
      if (obj instanceof GuardedValue guardedValue) {
        obj = guardedValue.getObject();
      }

      Value.Builder value = Value.newBuilder();
      if (obj instanceof StarlarkCallable) {
        StarlarkMethodDoc meth = globalToDoc.get(name);
        if (meth != null) {
          value = collectMethodInfo(meth);
        } else {
          value = valueFromCallable((StarlarkCallable) obj);
        }
      } else {
        StarlarkBuiltin typeModule = StarlarkAnnotations.getStarlarkBuiltin(obj.getClass());
        if (typeModule != null) {
          Method selfCallMethod =
              Starlark.getSelfCallMethod(StarlarkSemantics.DEFAULT, obj.getClass());
          if (selfCallMethod != null) {
            // selfCallMethod may be from a subclass of the annotated method.
            StarlarkMethod annotation = StarlarkAnnotations.getStarlarkMethod(selfCallMethod);
            value = valueFromAnnotation(annotation);
            // For constructors, we can also set the return type.
            StarlarkConstructorMethodDoc constructor = typeNameToConstructor.get(entry.getKey());
            if (constructor != null && value.hasCallable()) {
              value.getCallableBuilder().setReturnType(constructor.getReturnType());
            }
          } else {
            value.setName(name);
            // TODO(b/255647089): We should use the type module's type here, since it will more
            // accurately represent Providers, but has some issues with builtins. For now, just
            // special case None which has type NoneType.
            if (!name.equals("None")) {
              value.setType(name);
              value.setDoc(typeModule.doc());
            } else {
              value.setType("NoneType");
            }
          }
        } else if (!name.equals("_builtins_dummy")) { // Ignore the test only dummy global.
          // Special case bool since we can't infer the type module for it.
          if (name.equals("True") || name.equals("False")) {
            value.setType("bool");
          }
          value.setName(name);
        }
      }
      value.setApiContext(context);
      builtins.addGlobal(value);
    }
  }

  // Native rules are available as top level functions in BUILD files.
  private static void appendNativeRules(
      Builtins.Builder builtins, List<RuleDocumentation> nativeRules)
      throws BuildEncyclopediaDocException {
    for (RuleDocumentation rule : nativeRules) {
      Value.Builder global = collectRuleInfo(rule);
      global.setApiContext(ApiContext.BUILD);
      builtins.addGlobal(global);
    }
  }

  private static Value.Builder valueFromCallable(StarlarkCallable x) {
    // Starlark def statement?
    if (x instanceof StarlarkFunction fn) {
      Signature sig = new Signature();
      sig.name = fn.getName();
      sig.doc = fn.getDocumentation();
      sig.parameterNames = fn.getParameterNames();
      sig.hasVarargs = fn.hasVarargs();
      sig.hasKwargs = fn.hasKwargs();
      sig.getDefaultValue =
          (i) -> {
            Object v = fn.getDefaultValue(i);
            return v == null ? null : Starlark.repr(v);
          };
      return signatureToValue(sig);
    }

    // annotated Java method?
    if (x instanceof BuiltinFunction builtinFunction) {
      return valueFromAnnotation(builtinFunction.getAnnotation());
    }

    // application-defined callable?  Treat as def f(**kwargs).
    Signature sig = new Signature();
    sig.name = x.getName();
    sig.parameterNames = ImmutableList.of("kwargs");
    sig.hasKwargs = true;
    return signatureToValue(sig);
  }

  private static Value.Builder valueFromAnnotation(StarlarkMethod annot) {
    return signatureToValue(getSignature(annot));
  }

  private static class Signature {
    String name;
    List<String> parameterNames;
    boolean hasVarargs;
    boolean hasKwargs;
    String doc;

    // Returns the string form of the ith default value, using the
    // index, ordering, and null Conventions of StarlarkFunction.getDefaultValue.
    Function<Integer, String> getDefaultValue = (i) -> null;
  }

  private static Value.Builder signatureToValue(Signature sig) {
    Value.Builder value = Value.newBuilder();
    value.setName(sig.name);
    value.setDoc(sig.doc);

    int nparams = sig.parameterNames.size();
    int kwargsIndex = sig.hasKwargs ? --nparams : -1;
    int varargsIndex = sig.hasVarargs ? --nparams : -1;
    // Inv: nparams is number of regular parameters.

    Callable.Builder callable = Callable.newBuilder();
    for (int i = 0; i < sig.parameterNames.size(); i++) {
      String name = sig.parameterNames.get(i);
      Param.Builder param = Param.newBuilder();
      if (i == varargsIndex) {
        // *args
        param.setName("*" + name); // * seems redundant
        param.setIsStarArg(true);
      } else if (i == kwargsIndex) {
        // **kwargs
        param.setName("**" + name); // ** seems redundant
        param.setIsStarStarArg(true);
      } else {
        // regular parameter
        param.setName(name);
        String v = sig.getDefaultValue.apply(i);
        if (v != null) {
          param.setDefaultValue(v);
        } else {
          param.setIsMandatory(true); // bool seems redundant
        }
      }
      callable.addParam(param);
    }
    value.setCallable(callable);
    return value;
  }

  private static Value.Builder collectMethodInfo(StarlarkMethodDoc meth) {
    Value.Builder field = Value.newBuilder();
    field.setName(meth.getShortName());
    field.setDoc(meth.getDocumentation());
    if (meth.isCallable()) {
      Callable.Builder callable = Callable.newBuilder();
      for (StarlarkParamDoc par : meth.getParams()) {
        Param.Builder param = newParam(par.getName(), par.getDefaultValue().isEmpty());
        param.setType(par.getType());
        param.setDoc(par.getDocumentation());
        param.setDefaultValue(par.getDefaultValue());
        switch (par.getKind()) {
          case NORMAL -> {}
          case EXTRA_POSITIONALS -> {
            param.setName("*" + par.getName());
            param.setIsStarArg(true);
          }
          case EXTRA_KEYWORDS -> {
            param.setName("**" + par.getName());
            param.setIsStarStarArg(true);
          }
        }
        callable.addParam(param);
      }
      callable.setReturnType(meth.getReturnType());
      field.setCallable(callable);
    } else {
      field.setType(meth.getReturnType());
    }
    return field;
  }

  private static Param.Builder newParam(String name, Boolean isMandatory) {
    Param.Builder param = Param.newBuilder();
    param.setName(name);
    param.setIsMandatory(isMandatory);
    return param;
  }

  private static Value.Builder collectRuleInfo(RuleDocumentation rule)
      throws BuildEncyclopediaDocException {
    Value.Builder value = Value.newBuilder();
    value.setName(rule.getRuleName());
    value.setDoc(rule.getHtmlDocumentation());
    Callable.Builder callable = Callable.newBuilder();
    // All native rules have attribute "name". It is not included in the attributes list and needs
    // to be added separately.
    callable.addParam(newParam("name", true));
    for (RuleDocumentationAttribute attr : rule.getAttributes()) {
      Param.Builder param = newParam(attr.getAttributeName(), attr.isMandatory());
      param.setType(attr.getTypeDesc());
      param.setDefaultValue(attr.getDefaultValue());
      param.setDoc(attr.getUnexpandedHtmlDocumentation());
      callable.addParam(param);
    }
    value.setCallable(callable);
    return value;
  }

  private static void writeBuiltins(String filename, Builtins.Builder builtins) throws IOException {
    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(filename))) {
      Builtins build = builtins.build();
      build.writeTo(out);
    }
  }

  private static void printUsage(OptionsParser parser) {
    System.err.println(
        "Usage: api_exporter_bin -m link_map_path -p rule_class_provider\n"
            + "    [-r input_root] (-i input_dir)+ (--input_stardoc_proto binproto)+\n"
            + "    -f outputFile [-b denylist] [-h]\n\n"
            + "Exports all Starlark builtins to a file including the embedded native rules.\n"
            + "The link map path (-m), rule class provider (-p), output file (-f), and at least\n"
            + " one input_dir (-i) or binproto (--input_stardoc_proto) must be specified.\n");
    System.err.println(
        parser.describeOptionsWithDeprecatedCategories(
            Collections.<String, String>emptyMap(), OptionsParser.HelpVerbosity.LONG));
  }

  public static void main(String[] args) {
    OptionsParser parser =
        OptionsParser.builder().optionsClasses(BuildEncyclopediaOptions.class).build();
    parser.parseAndExitUponError(args);
    BuildEncyclopediaOptions options = parser.getOptions(BuildEncyclopediaOptions.class);

    if (options.help) {
      printUsage(parser);
      Runtime.getRuntime().exit(0);
    }

    if (options.linkMapPath.isEmpty()
        || (options.inputJavaDirs.isEmpty() && options.inputStardocProtos.isEmpty())
        || options.provider.isEmpty()
        || options.outputFile.isEmpty()) {
      printUsage(parser);
      Runtime.getRuntime().exit(1);
    }

    try {
      DocLinkMap linkMap = DocLinkMap.createFromFile(options.linkMapPath);
      RuleLinkExpander ruleExpander = new RuleLinkExpander(true, linkMap);
      SourceUrlMapper urlMapper = new SourceUrlMapper(linkMap, options.inputRoot);
      SymbolFamilies symbols =
          new SymbolFamilies(
              new StarlarkDocExpander(ruleExpander),
              urlMapper,
              options.provider,
              options.inputJavaDirs,
              options.inputStardocProtos,
              options.denylist);
      ImmutableMap<Category, ImmutableList<StarlarkDocPage>> allDocPages = symbols.getAllDocPages();
      Builtins.Builder builtins = Builtins.newBuilder();

      ImmutableList<StarlarkDocPage> globalPages = allDocPages.get(Category.GLOBAL_FUNCTION);
      Map<String, StarlarkMethodDoc> globalToDoc = new HashMap<>();
      for (StarlarkDocPage globalPage : globalPages) {
        for (StarlarkMethodDoc meth : globalPage.getJavaMethods()) {
          globalToDoc.put(meth.getShortName(), meth);
        }
      }

      Iterator<StarlarkDocPage> typesIterator =
          allDocPages.entrySet().stream()
              .filter(e -> !e.getKey().equals(Category.GLOBAL_FUNCTION))
              .flatMap(e -> e.getValue().stream())
              .iterator();
      Map<String, StarlarkConstructorMethodDoc> typeNameToConstructor = new HashMap<>();
      while (typesIterator.hasNext()) {
        StarlarkDocPage typeDocPage = typesIterator.next();
        appendTypes(builtins, typeDocPage, symbols.getNativeRules());
        typeNameToConstructor.put(typeDocPage.getName(), typeDocPage.getConstructor());
      }
      appendGlobals(
          builtins, symbols.getGlobals(), globalToDoc, typeNameToConstructor, ApiContext.ALL);
      appendGlobals(
          builtins, symbols.getBzlGlobals(), globalToDoc, typeNameToConstructor, ApiContext.BZL);
      appendNativeRules(builtins, symbols.getNativeRules());
      writeBuiltins(options.outputFile, builtins);

    } catch (Throwable e) {
      System.err.println("ERROR: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // Extracts signature and parameter default value expressions from a StarlarkMethod annotation.
  private static Signature getSignature(StarlarkMethod annot) {
    // Build-time annotation processing ensures mandatory parameters do not follow optional ones.
    boolean hasStar = false;
    String star = null;
    String starStar = null;
    ArrayList<String> params = new ArrayList<>();
    ArrayList<String> defaults = new ArrayList<>();

    for (net.starlark.java.annot.Param param : annot.parameters()) {
      // Ignore undocumented parameters
      if (!param.documented()) {
        continue;
      }
      // Implicit * or *args parameter separates transition from positional to named.
      // f (..., *, ... )  or  f(..., *args, ...)
      // TODO(adonovan): this logic looks fishy. Clean it up.
      if (param.named() && !param.positional() && !hasStar) {
        hasStar = true;
        if (!annot.extraPositionals().name().isEmpty()) {
          star = annot.extraPositionals().name();
        }
      }
      params.add(param.name());
      defaults.add(param.defaultValue().isEmpty() ? null : param.defaultValue());
    }

    // f(..., *args, ...)
    if (!annot.extraPositionals().name().isEmpty() && !hasStar) {
      star = annot.extraPositionals().name();
    }
    if (star != null) {
      params.add(star);
    }

    // f(..., **kwargs)
    if (!annot.extraKeywords().name().isEmpty()) {
      starStar = annot.extraKeywords().name();
      params.add(starStar);
    }

    Signature sig = new Signature();
    sig.name = annot.name();
    sig.doc = annot.doc();
    sig.parameterNames = params;
    sig.hasVarargs = star != null;
    sig.hasKwargs = starStar != null;
    sig.getDefaultValue = defaults::get;
    return sig;
  }
}
