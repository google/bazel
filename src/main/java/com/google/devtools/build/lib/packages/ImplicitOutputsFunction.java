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
package com.google.devtools.build.lib.packages;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.Structure;

/**
 * A function interface allowing rules to specify their set of implicit outputs in a more dynamic
 * way than just simple template-substitution. For example, the set of implicit outputs may be a
 * function of rule attributes.
 *
 * <p>In the case that attribute placeholders are configurable attributes, errors will be thrown as
 * output templates are expanded before configurable attributes are resolved.
 *
 * <p>In the case that attribute placeholders are invalid, the template string will be left
 * unexpanded.
 */
// TODO(http://b/69387932): refactor this entire class and all callers.
public abstract class ImplicitOutputsFunction {

  /**
   * Implicit output functions for Starlark supporting key value access of expanded implicit
   * outputs.
   */
  public abstract static class StarlarkImplicitOutputsFunction extends ImplicitOutputsFunction {

    public abstract ImmutableMap<String, String> calculateOutputs(
        EventHandler eventHandler, AttributeMap map) throws EvalException, InterruptedException;

    @Override
    public Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap map)
        throws EvalException, InterruptedException {
      return calculateOutputs(eventHandler, map).values();
    }
  }

  /** Implicit output functions executing Starlark code. */
  public static final class StarlarkImplicitOutputsFunctionWithCallback
      extends StarlarkImplicitOutputsFunction {

    private final StarlarkCallbackHelper callback;

    public StarlarkImplicitOutputsFunctionWithCallback(StarlarkCallbackHelper callback) {
      this.callback = callback;
    }

    @Override
    public ImmutableMap<String, String> calculateOutputs(
        EventHandler eventHandler, AttributeMap map) throws EvalException, InterruptedException {
      Map<String, Object> attrValues = new HashMap<>();
      for (String attrName : map.getAttributeNames()) {
        Type<?> attrType = map.getAttributeType(attrName);
        // Don't include configurable attributes: we don't know which value they might take
        // since we don't yet have a build configuration.
        if (!map.isConfigurable(attrName)) {
          Object value = map.get(attrName, attrType);
          attrValues.put(Attribute.getStarlarkName(attrName), Attribute.valueToStarlark(value));
        }
      }
      Structure attrs =
          StructProvider.STRUCT.create(
              attrValues,
              "Attribute '%s' either doesn't exist "
                  + "or uses a select() (i.e. could have multiple values)");
      try {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Map.Entry<String, String> entry :
            Dict.cast(
                    callback.call(eventHandler, attrs),
                    String.class,
                    String.class,
                    "implicit outputs function return value")
                .entrySet()) {

          // Returns empty string only in case of invalid templates
          Iterable<String> substitutions =
              fromTemplates(entry.getValue()).getImplicitOutputs(eventHandler, map);
          if (Iterables.isEmpty(substitutions)) {
            throw Starlark.errorf(
                "For attribute '%s' in outputs: Invalid placeholder(s) in template",
                entry.getKey());
          }

          builder.put(entry.getKey(), Iterables.getOnlyElement(substitutions));
        }
        return builder.buildOrThrow();
      } catch (IllegalArgumentException ex) {
        throw new EvalException(ex);
      }
    }
  }

  /** Implicit output functions using a simple an output map. */
  public static final class StarlarkImplicitOutputsFunctionWithMap
      extends StarlarkImplicitOutputsFunction {

    private final ImmutableMap<String, String> outputMap;

    public StarlarkImplicitOutputsFunctionWithMap(ImmutableMap<String, String> outputMap) {
      this.outputMap = outputMap;
    }

    @Override
    public ImmutableMap<String, String> calculateOutputs(
        EventHandler eventHandler, AttributeMap map) throws EvalException, InterruptedException {

      ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
      for (Map.Entry<String, String> entry : outputMap.entrySet()) {
        // Empty iff invalid placeholders present.
        ImplicitOutputsFunction outputsFunction =
            fromUnsafeTemplates(ImmutableList.of(entry.getValue()));
        Iterable<String> substitutions = outputsFunction.getImplicitOutputs(eventHandler, map);
        if (Iterables.isEmpty(substitutions)) {
          throw Starlark.errorf(
              "For attribute '%s' in outputs: Invalid placeholder(s) in template", entry.getKey());
        }

        builder.put(entry.getKey(), Iterables.getOnlyElement(substitutions));
      }
      return builder.buildOrThrow();
    }
  }

  /**
   * Implicit output functions which can not throw an EvalException.
   */
  public abstract static class SafeImplicitOutputsFunction extends ImplicitOutputsFunction {

    /** The implicit output function that returns no files. */
    @SerializationConstant
    public static final SafeImplicitOutputsFunction NONE =
        new SafeImplicitOutputsFunction() {
          @Override
          public Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap rule) {
            return Collections.emptyList();
          }
        };

    @Override
    public abstract Iterable<String> getImplicitOutputs(
        EventHandler eventHandler, AttributeMap map);
  }

  /**
   * An interface to objects that can retrieve rule attributes.
   */
  public interface AttributeValueGetter {
    /**
     * Returns the value(s) of attribute "attr" in "rule", or empty set if attribute unknown.
     */
    Set<String> get(AttributeMap rule, String attr);
  }

  private static final Escaper PERCENT_ESCAPER = Escapers.builder().addEscape('%', "%%").build();

  /**
   * Given a newly-constructed Rule instance (with attributes populated), returns the list of output
   * files that this rule produces implicitly.
   */
  public abstract Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap rule)
      throws EvalException, InterruptedException;

  /**
   * A convenience wrapper for {@link #fromTemplates(Iterable)}.
   */
  public static SafeImplicitOutputsFunction fromTemplates(String... templates) {
    return fromTemplates(Arrays.asList(templates));
  }

  /**
   * The implicit output function that generates files based on a set of template substitutions
   * using rule attribute values.
   *
   * <p>This is not, actually, safe, and any use of configurable attributes will cause a hard
   * failure.
   *
   * @param templates The templates used to construct the name of the implicit output file target.
   *     The substring "%{foo}" will be replaced by the value of the attribute "foo". If multiple
   *     %{} substrings exist, the cross-product of them is generated.
   */
  public static SafeImplicitOutputsFunction fromTemplates(final Iterable<String> templates) {
    return new TemplateImplicitOutputsFunction(templates);
  }

  private static class TemplateImplicitOutputsFunction extends SafeImplicitOutputsFunction {

    private final Iterable<String> templates;

    TemplateImplicitOutputsFunction(Iterable<String> templates) {
      this.templates = templates;
    }

    // TODO(bazel-team): parse the templates already here
    @Override
    public Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap rule) {
        ImmutableSet.Builder<String> result = new ImmutableSet.Builder<>();
        for (String template : templates) {
          List<String> substitutions = substitutePlaceholderIntoTemplate(template, rule);
          if (substitutions.isEmpty()) {
            continue;
          }
          result.addAll(substitutions);
        }

        return result.build();
      }

      @Override
      public String toString() {
        return StringUtil.joinEnglishList(templates);
      }
  }

  private static class UnsafeTemplatesImplicitOutputsFunction extends ImplicitOutputsFunction {

    private final Iterable<String> templates;

    UnsafeTemplatesImplicitOutputsFunction(Iterable<String> templates) {
      this.templates = templates;
    }

    // TODO(bazel-team): parse the templates already here
    @Override
    public Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap rule)
        throws EvalException {
      ImmutableSet.Builder<String> result = new ImmutableSet.Builder<>();
      for (String template : templates) {
        List<String> substitutions =
            substitutePlaceholderIntoUnsafeTemplate(
                template, rule, ImplicitOutputsFunction::attributeValues);
        if (substitutions.isEmpty()) {
          continue;
        }
        result.addAll(substitutions);
      }

      return result.build();
    }

    @Override
    public String toString() {
      return StringUtil.joinEnglishList(templates);
    }
  }

  /**
   * The implicit output function that generates files based on a set of template substitutions
   * using rule attribute values.
   *
   * <p>This is not, actually, safe, and any use of configurable attributes will cause a hard
   * failure.
   *
   * @param templates The templates used to construct the name of the implicit output file target.
   *     The substring "%{foo}" will be replaced by the value of the attribute "foo". If multiple
   *     %{} substrings exist, the cross-product of them is generated.
   */
  // It would be nice to unify this with fromTemplates above, but that's not possible because
  // substitutePlaceholderIntoUnsafeTemplate can throw an exception.
  private static ImplicitOutputsFunction fromUnsafeTemplates(Iterable<String> templates) {
    return new UnsafeTemplatesImplicitOutputsFunction(templates);
  }

  /** A convenience wrapper for {@link #fromFunctions(Iterable)}. */
  public static SafeImplicitOutputsFunction fromFunctions(
      SafeImplicitOutputsFunction... functions) {
    return fromFunctions(Arrays.asList(functions));
  }

  /**
   * The implicit output function that generates files based on a set of template substitutions
   * using rule attribute values.
   *
   * @param functions The functions used to construct the name of the implicit output file target.
   *     The substring "%{name}" will be replaced by the actual name of the rule, the substring
   *     "%{srcs}" will be replaced by the name of each source file without its extension. If
   *     multiple %{} substrings exist, the cross-product of them is generated.
   */
  public static SafeImplicitOutputsFunction fromFunctions(
      final Iterable<SafeImplicitOutputsFunction> functions) {
    return new FunctionCombinationImplicitOutputsFunction(functions);
  }

  private static class FunctionCombinationImplicitOutputsFunction
      extends SafeImplicitOutputsFunction {

    private final Iterable<SafeImplicitOutputsFunction> functions;

    FunctionCombinationImplicitOutputsFunction(Iterable<SafeImplicitOutputsFunction> functions) {
      this.functions = functions;
    }

    @Override
    public Iterable<String> getImplicitOutputs(EventHandler eventHandler, AttributeMap rule) {
      Collection<String> result = new LinkedHashSet<>();
      for (SafeImplicitOutputsFunction function : functions) {
        Iterables.addAll(result, function.getImplicitOutputs(eventHandler, rule));
      }
      return result;
    }

    @Override
    public String toString() {
      return StringUtil.joinEnglishList(functions);
    }
  }

  /**
   * Coerces attribute "attrName" of the specified rule into a sequence of strings. Helper function
   * for {@link #fromTemplates(Iterable)}.
   */
  private static ImmutableSet<String> attributeValues(AttributeMap rule, String attrName) {
    if (attrName.equals("dirname")) {
      PathFragment dir = PathFragment.create(rule.getLabel().getName()).getParentDirectory();
      return dir.isEmpty() ? ImmutableSet.of("") : ImmutableSet.of(dir.getPathString() + "/");
    } else if (attrName.equals("basename")) {
      return ImmutableSet.of(PathFragment.create(rule.getLabel().getName()).getBaseName());
    }

    Type<?> attrType = rule.getAttributeType(attrName);
    if (attrType == null) {
      return ImmutableSet.of();
    }
    // String attributes and lists are easy.
    if (Type.STRING == attrType) {
      return ImmutableSet.of(rule.get(attrName, Type.STRING));
    } else if (Type.STRING_NO_INTERN == attrType) {
      return ImmutableSet.of(rule.get(attrName, Type.STRING_NO_INTERN));
    } else if (Types.STRING_LIST == attrType) {
      return ImmutableSet.copyOf(rule.get(attrName, Types.STRING_LIST));
    } else if (BuildType.LABEL == attrType) {
      // Labels are most often used to change the extension,
      // e.g. %.foo -> %.java, so we return the basename w/o extension.
      Label label = rule.get(attrName, BuildType.LABEL);
      return ImmutableSet.of(FileSystemUtils.removeExtension(label.getName()));
    } else if (BuildType.LABEL_LIST == attrType) {
      // Labels are most often used to change the extension,
      // e.g. %.foo -> %.java, so we return the basename w/o extension.
      return rule.get(attrName, BuildType.LABEL_LIST).stream()
          .map(label -> FileSystemUtils.removeExtension(label.getName()))
          .collect(toImmutableSet());
    } else if (BuildType.OUTPUT == attrType) {
      Label out = rule.get(attrName, BuildType.OUTPUT);
      return ImmutableSet.of(out.getName());
    } else if (BuildType.OUTPUT_LIST == attrType) {
      return rule.get(attrName, BuildType.OUTPUT_LIST).stream()
          .map(Label::getName)
          .collect(toImmutableSet());
    }
    throw new IllegalArgumentException(
        "Don't know how to handle " + attrName + " : " + attrType);
  }

  /**
   * Collects all named placeholders from the template while replacing them with %s.
   *
   * <p>Example: for {@code template} "%{name}_%{locales}.foo", it will return "%s_%s.foo" and
   * store "name" and "locales" in {@code placeholders}.
   *
   * <p>Incomplete placeholders are treated like text: for "a-%{x}-%{y" this method returns
   * "a-%s-%%{y" and stores "x" in {@code placeholders}.
   *
   * @param template a string with placeholders of the format %{...}
   * @param placeholders a collection to collect placeholders into; may contain duplicates if not a
   *     Set
   * @return a format string for {@link String#format}, created from the template string with every
   *     placeholder replaced by %s
   */
  public static String createPlaceholderSubstitutionFormatString(String template,
      Collection<String> placeholders) {
    return createPlaceholderSubstitutionFormatStringRecursive(template, placeholders,
        new StringBuilder());
  }

  private static String createPlaceholderSubstitutionFormatStringRecursive(String template,
      Collection<String> placeholders, StringBuilder formatBuilder) {
    int start = template.indexOf("%{");
    if (start < 0) {
      return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString();
    }

    int end = template.indexOf('}', start + 2);
    if (end < 0) {
      return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString();
    }

    formatBuilder.append(PERCENT_ESCAPER.escape(template.substring(0, start))).append("%s");
    placeholders.add(template.substring(start + 2, end));
    return createPlaceholderSubstitutionFormatStringRecursive(template.substring(end + 1),
        placeholders, formatBuilder);
  }

  /**
   * Given a template string, replaces all placeholders of the form %{...} with the values from
   * attributeSource. If there are multiple placeholders, then the output is the cross product of
   * substitutions.
   */
  public static ImmutableList<String> substitutePlaceholderIntoTemplate(
      String template, AttributeMap rule) {
    return substitutePlaceholderIntoTemplate(
        template, rule, ImplicitOutputsFunction::attributeValues);
  }

  /**
   * Substitutes attribute-placeholders in a template string, producing all possible combinations.
   *
   * @param template the template string, may contain named placeholders for rule attributes, like
   *     <code>%{name}</code> or <code>%{deps}</code>
   * @param rule the rule whose attributes the placeholders correspond to
   * @param attributeGetter a helper for fetching attribute values
   * @return all possible combinations of the attributes referenced by the placeholders, substituted
   *     into the template; empty if any of the placeholders expands to no values
   */
  public static ImmutableList<String> substitutePlaceholderIntoTemplate(
      String template, AttributeMap rule, AttributeValueGetter attributeGetter) {
    // Parse the template to get the attribute names and format string.
    ParsedTemplate parsedTemplate = ParsedTemplate.parse(template);

    // Return the substituted strings.
    return parsedTemplate.substituteAttributes(rule, attributeGetter);
  }

  record ParsedTemplate(String template, String formatStr, List<String> attributeNames) {
    ParsedTemplate {
      requireNonNull(template, "template");
      requireNonNull(formatStr, "formatStr");
      requireNonNull(attributeNames, "attributeNames");
    }

    static ParsedTemplate parse(String rawTemplate) {
      List<String> placeholders = Lists.newArrayList();
      String formatStr = createPlaceholderSubstitutionFormatString(rawTemplate, placeholders);
      if (placeholders.isEmpty()) {
        placeholders = ImmutableList.of();
      }
      return new ParsedTemplate(rawTemplate, formatStr, placeholders);
    }

    ImmutableList<String> substituteAttributes(
        AttributeMap attributeMap, AttributeValueGetter attributeGetter) {
      if (attributeNames().isEmpty()) {
        return ImmutableList.of(template());
      }

      List<Set<String>> values = Lists.newArrayListWithCapacity(attributeNames().size());
      for (String placeholder : attributeNames()) {
        Set<String> attrValues = attributeGetter.get(attributeMap, placeholder);
        if (attrValues.isEmpty()) {
          return ImmutableList.of();
        }
        values.add(attrValues);
      }
      ImmutableList.Builder<String> out = new ImmutableList.Builder<>();
      for (List<String> combination : Sets.cartesianProduct(values)) {
        out.add(String.format(formatStr(), combination.toArray()));
      }
      return out.build();
    }
  }

  private static ImmutableList<String> substitutePlaceholderIntoUnsafeTemplate(
      String unsafeTemplate, AttributeMap rule, AttributeValueGetter attributeGetter)
      throws EvalException {
    // Parse the template to get the attribute names and format string.
    ParsedTemplate parsedTemplate = ParsedTemplate.parse(unsafeTemplate);

    // Make sure all attributes are valid.
    for (String placeholder : parsedTemplate.attributeNames()) {
      if (rule.isConfigurable(placeholder)) {
        throw Starlark.errorf(
            "Attribute %s is configurable and cannot be used in outputs", placeholder);
      }
    }

    // Return the substituted strings.
    return parsedTemplate.substituteAttributes(rule, attributeGetter);
  }
}
