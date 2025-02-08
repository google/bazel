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
package com.google.devtools.build.docgen;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.Attribute.StarlarkComputedDefaultTemplate;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.starlarkdocextract.LabelRenderer;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A class storing a rule attribute documentation along with some meta information. For native
 * attributes, the class provides functionality to compute the ancestry level of this attribute's
 * generator rule definition class compared to other rule definition classes.
 *
 * <p>Warning, two RuleDocumentationAttribute objects are equal based on only the attributeName.
 */
public class RuleDocumentationAttribute
    implements Comparable<RuleDocumentationAttribute>, Cloneable {

  private static final ImmutableMap<Type<?>, String> TYPE_DESC =
      ImmutableMap.<Type<?>, String>builder()
          .put(Type.BOOLEAN, "Boolean")
          .put(Type.INTEGER, "Integer")
          .put(Types.INTEGER_LIST, "List of integers")
          .put(Type.STRING, "String")
          .put(Types.STRING_DICT, "Dictionary: String -> String")
          .put(Types.STRING_LIST, "List of strings")
          .put(BuildType.TRISTATE, "Integer")
          .put(BuildType.LABEL, "<a href=\"${link build-ref#labels}\">Label</a>")
          .put(
              BuildType.LABEL_KEYED_STRING_DICT,
              "Dictionary: <a href=\"${link build-ref#labels}\">label</a> -> String")
          .put(BuildType.LABEL_LIST, "List of <a href=\"${link build-ref#labels}\">labels</a>")
          .put(
              BuildType.GENQUERY_SCOPE_TYPE_LIST,
              "List of <a href=\"${link build-ref#labels}\">labels</a>")
          .put(
              BuildType.LABEL_DICT_UNARY,
              "Dictionary mapping strings to <a href=\"${link build-ref#labels}\">labels</a>")
          .put(BuildType.LICENSE, "Licence type")
          .put(BuildType.NODEP_LABEL, "<a href=\"${link build-ref#name}\">Name</a>")
          .put(BuildType.NODEP_LABEL_LIST, "List of <a href=\"${link build-ref#name}\">names</a>")
          .put(BuildType.OUTPUT, "<a href=\"${link build-ref#filename}\">Filename</a>")
          .put(
              BuildType.OUTPUT_LIST, "List of <a href=\"${link build-ref#filename}\">filenames</a>")
          .buildOrThrow();

  @Nullable private final Class<? extends RuleDefinition> definitionClass;
  private final String attributeName;
  private final String htmlDocumentation;
  @Nullable private final String commonType;
  // Used to expand rule link references in the attribute documentation.
  private RuleLinkExpander linkExpander;
  private final String location; // for error messages
  private Set<String> flags;
  // The following are not set by create() or createCommon()
  @Nullable private final Type<?> type;
  @Nullable private final String defaultValue;
  private final boolean mandatory;
  private final boolean nonconfigurable;

  /**
   * Creates a RuleDocumentationAttribute from comments in Java sources. Additional metadata may be
   * filled in later via {@link copyAndUpdateFrom}.
   */
  static RuleDocumentationAttribute create(
      @Nullable Class<? extends RuleDefinition> definitionClass,
      String attributeName,
      String htmlDocumentation,
      String file,
      int lineNumber,
      Set<String> flags) {
    return new RuleDocumentationAttribute(
        definitionClass,
        attributeName,
        htmlDocumentation,
        BuildEncyclopediaDocException.formatLocation(file, lineNumber),
        flags,
        /* commonType= */ null,
        /* type= */ null,
        /* defaultValue= */ null,
        /* mandatory= */ false,
        /* nonconfigurable= */ false);
  }

  /**
   * Creates common RuleDocumentationAttribute such as deps or data. These attribute docs have no
   * definitionClass or htmlDocumentation (it's in the BE header).
   */
  static RuleDocumentationAttribute createCommon(
      String attributeName, String commonType, String htmlDocumentation) {
    return new RuleDocumentationAttribute(
        null,
        attributeName,
        htmlDocumentation,
        "",
        ImmutableSet.of(),
        commonType,
        /* type= */ null,
        /* defaultValue= */ null,
        /* mandatory= */ false,
        /* nonconfigurable= */ false);
  }

  /** Creates a RuleDocumentationAttribute from a stardoc_output.AttributeInfo proto. */
  static RuleDocumentationAttribute createFromAttributeInfo(
      AttributeInfo attributeInfo, String location, Set<String> flags)
      throws BuildEncyclopediaDocException {
    return new RuleDocumentationAttribute(
        null,
        attributeInfo.getName(),
        attributeInfo.getDocString(),
        location,
        flags,
        /* commonType= */ null,
        getAttributeInfoType(attributeInfo, location),
        attributeInfo.getDefaultValue(),
        attributeInfo.getMandatory(),
        attributeInfo.getNonconfigurable());
  }

  /**
   * Copies this RuleDocumentationAttribute and sets additional metadata (type, default value, and
   * whether the attribute is mandatory or nonconfigurable) from a native attribute object.
   */
  RuleDocumentationAttribute copyAndUpdateFrom(Attribute attribute) {
    return new RuleDocumentationAttribute(
        this.definitionClass,
        this.attributeName,
        this.htmlDocumentation,
        this.location,
        this.flags,
        this.commonType,
        attribute.getType(),
        reprDefaultValue(attribute),
        attribute.isMandatory(),
        !attribute.isConfigurable());
  }

  private static Type<?> getAttributeInfoType(AttributeInfo attributeInfo, String location)
      throws BuildEncyclopediaDocException {
    return switch (attributeInfo.getType()) {
      case INT -> Type.INTEGER;
      case LABEL -> BuildType.LABEL;
      case NAME, STRING -> Type.STRING;
      case STRING_LIST -> Types.STRING_LIST;
      case INT_LIST -> Types.INTEGER_LIST;
      case LABEL_LIST -> BuildType.LABEL_LIST;
      case BOOLEAN -> Type.BOOLEAN;
      case LABEL_STRING_DICT -> BuildType.LABEL_KEYED_STRING_DICT;
      case STRING_DICT -> Types.STRING_DICT;
      case STRING_LIST_DICT -> Types.STRING_LIST_DICT;
      case LABEL_DICT_UNARY -> BuildType.LABEL_DICT_UNARY;
      case LABEL_LIST_DICT -> BuildType.LABEL_LIST_DICT;
      case OUTPUT -> BuildType.OUTPUT;
      case OUTPUT_LIST -> BuildType.OUTPUT_LIST;
      default ->
          throw new BuildEncyclopediaDocException(
              location,
              String.format(
                  "attribute %s: unknown type %s",
                  attributeInfo.getName(), attributeInfo.getType()));
    };
  }

  private RuleDocumentationAttribute(
      @Nullable Class<? extends RuleDefinition> definitionClass,
      String attributeName,
      String htmlDocumentation,
      String location,
      Set<String> flags,
      @Nullable String commonType,
      @Nullable Type<?> type,
      @Nullable String defaultValue,
      boolean mandatory,
      boolean nonconfigurable) {
    Preconditions.checkNotNull(attributeName, "AttributeName must not be null.");
    this.definitionClass = definitionClass;
    this.attributeName = attributeName;
    this.htmlDocumentation = htmlDocumentation;
    this.location = location;
    this.flags = flags;
    this.commonType = commonType;
    this.type = type;
    this.defaultValue = defaultValue;
    this.mandatory = mandatory;
    this.nonconfigurable = nonconfigurable;
  }

  @Nullable
  private static String reprDefaultValue(Attribute attribute) {
    Object value = attribute.getDefaultValueUnchecked();
    if (value instanceof ComputedDefault || value instanceof StarlarkComputedDefaultTemplate) {
      // We cannot print anything useful here other than "optional". Let's assume the doc string for
      // the attribute explains the details.
      return null;
    } else if (value instanceof TriState triState) {
      return switch (triState) {
        case AUTO -> "-1";
        case NO -> "0";
        case YES -> "1";
      };
    }
    return LabelRenderer.DEFAULT.reprWithoutLabelConstructor(Attribute.valueToStarlark(value));
  }

  /**
   * Returns the name of the rule attribute.
   */
  public String getAttributeName() {
    return attributeName;
  }

  /**
   * Returns the file name or label, optionally with a line number, where the rule attribute is
   * defined.
   */
  public String getLocation() {
    return location;
  }

  /**
   * Returns whether this attribute is marked as deprecated.
   */
  public boolean isDeprecated() {
    return hasFlag(DocgenConsts.FLAG_DEPRECATED);
  }

  /**
   * Sets the {@link RuleLinkExpander} to be used to expand links in the HTML documentation.
   */
  public void setRuleLinkExpander(RuleLinkExpander linkExpander) {
    this.linkExpander = linkExpander;
  }

  /**
   * Returns the html documentation of the rule attribute.
   */
  public String getHtmlDocumentation() throws BuildEncyclopediaDocException {
    return tryExpand(htmlDocumentation);
  }

  public String tryExpand(String html) throws BuildEncyclopediaDocException {
    if (linkExpander == null) {
      return html;
    }
    try {
      return linkExpander.expand(html);
    } catch (IllegalArgumentException e) {
      throw new BuildEncyclopediaDocException(location, e.getMessage());
    }
  }

  /** Returns whether the param is required or optional. */
  public boolean isMandatory() {
    return mandatory;
  }

  /** Returns a string containing the synopsis for this attribute. */
  public String getSynopsis() throws BuildEncyclopediaDocException {
    if (type == null) {
      return "";
    }
    String rawType = TYPE_DESC.get(type);
    StringBuilder sb =
        new StringBuilder()
            .append(rawType == null ? null : tryExpand(rawType))
            .append(
                nonconfigurable
                    ? String.format(
                        "; <a href=\"%s#configurable-attributes\">nonconfigurable</a>",
                        RuleDocumentation.COMMON_DEFINITIONS_PAGE)
                    : "");
    if (isMandatory()) {
      sb.append("; required");
    } else if (defaultValue != null && !defaultValue.isEmpty()) {
      sb.append("; default is <code>").append(defaultValue).append("</code>");
    } else {
      // Computed default or other non-representable value
      sb.append("; optional");
    }
    return sb.toString();
  }

  /**
   * Returns true if the attribute doc is of a common attribute type.
   */
  public boolean isCommonType() {
    return commonType != null;
  }

  /**
   * Returns the common attribute type if this attribute doc is of a common type
   * otherwise actualRule.
   */
  String getGeneratedInRule(String actualRule) {
    return isCommonType() ? commonType : actualRule;
  }

  /**
   * Returns true if this attribute documentation has the parameter flag.
   */
  boolean hasFlag(String flag) {
    return flags.contains(flag);
  }

  /**
   * Returns the length of a shortest path from usingClass to the definitionClass of this
   * RuleDocumentationAttribute in the rule definition ancestry graph. Returns -1
   * if definitionClass is not the ancestor (transitively) of usingClass.
   */
  int getDefinitionClassAncestryLevel(Class<? extends RuleDefinition> usingClass,
      ConfiguredRuleClassProvider ruleClassProvider) {
    if (usingClass.equals(definitionClass)) {
      return 0;
    }
    // Storing nodes (rule class definitions) with the length of the shortest path from usingClass
    Map<Class<? extends RuleDefinition>, Integer> visited = new HashMap<>();
    LinkedList<Class<? extends RuleDefinition>> toVisit = new LinkedList<>();
    visited.put(usingClass, 0);
    toVisit.add(usingClass);
    // Searching the shortest path from usingClass to this.definitionClass using BFS
    do {
      Class<? extends RuleDefinition> ancestor = toVisit.removeFirst();
      visitAncestor(ancestor, visited, toVisit, ruleClassProvider);
      if (ancestor.equals(definitionClass)) {
        return visited.get(ancestor);
      }
    } while (!toVisit.isEmpty());
    return -1;
  }

  private void visitAncestor(
      Class<? extends RuleDefinition> usingClass,
      Map<Class<? extends RuleDefinition>, Integer> visited,
      LinkedList<Class<? extends RuleDefinition>> toVisit,
      ConfiguredRuleClassProvider ruleClassProvider) {
    RuleDefinition instance = getRuleDefinition(usingClass, ruleClassProvider);
    for (Class<? extends RuleDefinition> ancestor : instance.getMetadata().ancestors()) {
      if (!visited.containsKey(ancestor)) {
        toVisit.addLast(ancestor);
        visited.put(ancestor, visited.get(usingClass) + 1);
      }
    }
  }

  private RuleDefinition getRuleDefinition(Class<? extends RuleDefinition> usingClass,
      ConfiguredRuleClassProvider ruleClassProvider) {
    if (ruleClassProvider == null) {
      try {
        return usingClass.getConstructor().newInstance();
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        throw new IllegalStateException(e);
      }
    }
    return ruleClassProvider.getRuleClassDefinition(usingClass.getName());
  }

  private int getAttributeOrderingPriority(RuleDocumentationAttribute attribute) {
    if (DocgenConsts.ATTRIBUTE_ORDERING.containsKey(attribute.attributeName)) {
      return DocgenConsts.ATTRIBUTE_ORDERING.get(attribute.attributeName);
    } else {
      return 0;
    }
  }

  @Override
  public int compareTo(RuleDocumentationAttribute o) {
    int thisPriority = getAttributeOrderingPriority(this);
    int otherPriority = getAttributeOrderingPriority(o);
    if (thisPriority > otherPriority) {
      return 1;
    } else if (thisPriority < otherPriority) {
      return -1;
    } else {
      return this.attributeName.compareTo(o.attributeName);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RuleDocumentationAttribute)) {
      return false;
    }
    return attributeName.equals(((RuleDocumentationAttribute) obj).attributeName);
  }

  @Override
  public int hashCode() {
    return attributeName.hashCode();
  }
}
