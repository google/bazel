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
package com.google.devtools.build.lib.query2.query.output;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.compat.FakeLoadTarget;
import com.google.devtools.build.lib.query2.engine.OutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.SynchronizedDelegatingOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** An output formatter that prints the result as XML. */
class XmlOutputFormatter extends AbstractUnorderedFormatter {

  private AspectResolver aspectResolver;
  private DependencyFilter dependencyFilter;
  private boolean packageGroupIncludesDoubleSlash;
  private boolean relativeLocations;
  private QueryOptions queryOptions;
  @Nullable private PathFragment overrideSourceRoot;

  @Override
  public String getName() {
    return "xml";
  }

  @Override
  public ThreadSafeOutputFormatterCallback<Target> createStreamCallback(
      OutputStream out, QueryOptions options, QueryEnvironment<?> env) {
    return new SynchronizedDelegatingOutputFormatterCallback<>(
        createPostFactoStreamCallback(out, options, env.getLabelPrinter()));
  }

  @Override
  public void setOptions(
      CommonQueryOptions options, AspectResolver aspectResolver, HashFunction hashFunction) {
    super.setOptions(options, aspectResolver, hashFunction);
    this.aspectResolver = aspectResolver;
    this.dependencyFilter = FormatUtils.getDependencyFilter(options);
    this.packageGroupIncludesDoubleSlash = options.incompatiblePackageGroupIncludesDoubleSlash;
    this.relativeLocations = options.relativeLocations;

    Preconditions.checkArgument(options instanceof QueryOptions);
    this.queryOptions = (QueryOptions) options;
  }

  @Override
  public void setOverrideSourceRoot(PathFragment overrideSourceRoot) {
    this.overrideSourceRoot = overrideSourceRoot;
  }

  @Override
  public OutputFormatterCallback<Target> createPostFactoStreamCallback(
      OutputStream out, QueryOptions options, LabelPrinter labelPrinter) {
    return new OutputFormatterCallback<Target>() {

      private Document doc;
      private Element queryElem;

      @Override
      public void start() {
        try {
          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          doc = factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
          // This shouldn't be possible: all the configuration is hard-coded.
          throw new IllegalStateException("XML output failed", e);
        }
        doc.setXmlVersion("1.1");
        queryElem = doc.createElement("query");
        queryElem.setAttribute("version", "2");
        doc.appendChild(queryElem);
      }

      @Override
      public void processOutput(Iterable<Target> partialResult) throws InterruptedException {
        for (Target target : partialResult) {
          queryElem.appendChild(createTargetElement(doc, target, labelPrinter));
        }
      }

      @Override
      public void close(boolean failFast) {
        if (!failFast) {
          try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(doc), new StreamResult(out));
          } catch (TransformerFactoryConfigurationError | TransformerException e) {
            // This shouldn't be possible: all the configuration is hard-coded.
            throw new IllegalStateException("XML output failed", e);
          }
        }
      }
    };
  }

  /**
   * Creates and returns a new DOM tree for the specified build target.
   *
   * <p>XML structure: - element tag is &lt;source-file>, &lt;generated-file> or &lt;rule
   * class="cc_library">, following the terminology of {@link Target#getTargetKind()}. - 'name'
   * attribute is target's label. - 'location' attribute is consistent with output of --output
   * location. - rule attributes are represented in the DOM structure.
   */
  private Element createTargetElement(Document doc, Target target, LabelPrinter labelPrinter)
      throws InterruptedException {
    Element elem;
    if (target instanceof Rule rule) {
      elem = doc.createElement("rule");
      elem.setAttribute("class", rule.getRuleClass());
      for (Attribute attr : rule.getAttributes()) {
        if (rule.isAttributeValueExplicitlySpecified(attr) || queryOptions.xmlShowDefaultValues) {
          // TODO(b/162524370): mayTreatMultipleAsNone should be true for types that drop multiple
          //  values.
          Iterable<Object> values =
              PossibleAttributeValues.forRuleAndAttribute(
                  rule, attr, /* mayTreatMultipleAsNone= */ false);
          Element attrElem = createValueElement(doc, attr.getType(), values, labelPrinter);
          attrElem.setAttribute("name", attr.getName());
          elem.appendChild(attrElem);
        }
      }

      // Include explicit elements for all direct inputs and outputs of a rule; this goes beyond
      // what is available from the attributes above, since it may also (depending on options)
      // include implicit outputs, exec-configuration outputs, and default values.
      for (Label label : rule.getSortedLabels(dependencyFilter)) {
        Element inputElem = doc.createElement("rule-input");
        inputElem.setAttribute("name", labelPrinter.toString(label));
        elem.appendChild(inputElem);
      }

      aspectResolver.computeAspectDependencies(target, dependencyFilter).values().stream()
          .flatMap(m -> m.values().stream())
          .distinct()
          .forEach(
              label -> {
                Element inputElem = doc.createElement("rule-input");
                inputElem.setAttribute("name", labelPrinter.toString(label));
                elem.appendChild(inputElem);
              });

      for (OutputFile outputFile : rule.getOutputFiles()) {
        Element outputElem = doc.createElement("rule-output");
        outputElem.setAttribute("name", labelPrinter.toString(outputFile.getLabel()));
        elem.appendChild(outputElem);
      }
      for (String feature : rule.getPackage().getPackageArgs().features().toStringList()) {
        Element outputElem = doc.createElement("rule-default-setting");
        outputElem.setAttribute("name", feature);
        elem.appendChild(outputElem);
      }
    } else if (target instanceof PackageGroup packageGroup) {
      elem = doc.createElement("package-group");
      elem.setAttribute("name", packageGroup.getName());
      Element includes =
          createValueElement(doc, BuildType.LABEL_LIST, packageGroup.getIncludes(), labelPrinter);
      includes.setAttribute("name", "includes");
      elem.appendChild(includes);
      Element packages =
          createValueElement(
              doc,
              Types.STRING_LIST,
              packageGroup.getContainedPackages(packageGroupIncludesDoubleSlash),
              labelPrinter);
      packages.setAttribute("name", "packages");
      elem.appendChild(packages);
    } else if (target instanceof OutputFile outputFile) {
      elem = doc.createElement("generated-file");
      elem.setAttribute(
          "generating-rule", labelPrinter.toString(outputFile.getGeneratingRule().getLabel()));
    } else if (target instanceof InputFile inputFile) {
      elem = doc.createElement("source-file");
      if (inputFile.getName().equals("BUILD")) {
        addStarlarkFilesToElement(doc, elem, inputFile, labelPrinter);
        addFeaturesToElement(doc, elem, inputFile);
        elem.setAttribute("package_contains_errors",
            String.valueOf(inputFile.getPackage().containsErrors()));
      }

      // TODO(bazel-team): We're being inconsistent about whether we include the package's
      // default_visibility in the target. For files we do, but for rules we don't.
      addPackageGroupsToElement(doc, elem, inputFile, labelPrinter);
    } else if (target instanceof EnvironmentGroup envGroup) {
      elem = doc.createElement("environment-group");
      elem.setAttribute("name", envGroup.getName());
      Element environments =
          createValueElement(doc, BuildType.LABEL_LIST, envGroup.getEnvironments(), labelPrinter);
      environments.setAttribute("name", "environments");
      elem.appendChild(environments);
      Element defaults =
          createValueElement(doc, BuildType.LABEL_LIST, envGroup.getDefaults(), labelPrinter);
      defaults.setAttribute("name", "defaults");
      elem.appendChild(defaults);
    } else if (target instanceof FakeLoadTarget) {
      elem = doc.createElement("source-file");
    } else {
      throw new IllegalArgumentException(target.toString());
    }

    elem.setAttribute("name", labelPrinter.toString(target.getLabel()));
    String location = FormatUtils.getLocation(target, relativeLocations, overrideSourceRoot);
    if (!queryOptions.xmlLineNumbers) {
      int firstColon = location.indexOf(':');
      if (firstColon != -1) {
        location = location.substring(0, firstColon);
      }
    }

    elem.setAttribute("location", location);
    return elem;
  }

  private static void addPackageGroupsToElement(
      Document doc, Element parent, Target target, LabelPrinter labelPrinter) {
    for (Label visibilityDependency : target.getVisibilityDependencyLabels()) {
      Element elem = doc.createElement("package-group");
      elem.setAttribute("name", labelPrinter.toString(visibilityDependency));
      parent.appendChild(elem);
    }

    for (Label visibilityDeclaration : target.getVisibilityDeclaredLabels()) {
      Element elem = doc.createElement("visibility-label");
      elem.setAttribute("name", labelPrinter.toString(visibilityDeclaration));
      parent.appendChild(elem);
    }
  }

  private static void addFeaturesToElement(Document doc, Element parent, InputFile inputFile) {
    for (String feature : inputFile.getPackage().getPackageArgs().features().toStringList()) {
      Element elem = doc.createElement("feature");
      elem.setAttribute("name", feature);
      parent.appendChild(elem);
    }
  }

  private void addStarlarkFilesToElement(
      Document doc, Element parent, InputFile inputFile, LabelPrinter labelPrinter)
      throws InterruptedException {
    Iterable<Label> dependencies =
        aspectResolver.computeBuildFileDependencies(inputFile.getPackage());

    for (Label starlarkFileDep : dependencies) {
      Element elem = doc.createElement("load");
      elem.setAttribute("name", labelPrinter.toString(starlarkFileDep));
      parent.appendChild(elem);
    }
  }

  /**
   * Creates and returns a new DOM tree for the specified attribute values. For non-configurable
   * attributes, this is a single value. For configurable attributes, this contains one value for
   * each configuration. (Only toplevel values are named attributes; list elements are unnamed.)
   *
   * <p>In the case of configurable attributes, multi-value attributes (e.g. lists) merge all
   * configured lists into an aggregate flattened list. Single-value attributes simply refrain to
   * set a value and annotate the DOM element as configurable.
   *
   * <p>(The ungainly qualified class name is required to avoid ambiguity with
   * OutputFormatter.OutputType.)
   */
  private static Element createValueElement(
      Document doc, Type<?> type, Iterable<Object> values, LabelPrinter labelPrinter) {
    final Element elem;
    final boolean hasMultipleValues = Iterables.size(values) > 1;
    Type<?> elemType = type.getListElementType();
    if (elemType != null) { // it's a list (includes "distribs")
      elem = doc.createElement("list");
      for (Object value : values) {
        for (Object elemValue : (Collection<?>) value) {
          elem.appendChild(createValueElement(doc, elemType, elemValue, labelPrinter));
        }
      }
    } else if (type instanceof Type.DictType<?, ?> dictType) {
      Set<Object> visitedValues = new HashSet<>();
      elem = doc.createElement("dict");
      for (Object value : values) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          if (visitedValues.add(entry.getKey())) {
            Element pairElem = doc.createElement("pair");
            elem.appendChild(pairElem);
            pairElem.appendChild(
                createValueElement(doc, dictType.getKeyType(), entry.getKey(), labelPrinter));
            pairElem.appendChild(
                createValueElement(doc, dictType.getValueType(), entry.getValue(), labelPrinter));
          }
        }
      }
    } else if (type == BuildType.LICENSE) {
      elem = createSingleValueElement(doc, "license", hasMultipleValues);
      if (!hasMultipleValues) {
        License license = (License) Iterables.getOnlyElement(values);

        Element exceptions =
            createValueElement(doc, BuildType.LABEL_LIST, license.getExceptions(), labelPrinter);
        exceptions.setAttribute("name", "exceptions");
        elem.appendChild(exceptions);

        Element licenseTypes =
            createValueElement(doc, Types.STRING_LIST, license.getLicenseTypes(), labelPrinter);
        licenseTypes.setAttribute("name", "license-types");
        elem.appendChild(licenseTypes);
      }
    } else { // INTEGER STRING LABEL DISTRIBUTION OUTPUT
      elem = createSingleValueElement(doc, type.toString(), hasMultipleValues);
      if (!hasMultipleValues && !Iterables.isEmpty(values)) {
        Object value = Iterables.getOnlyElement(values);
        // Values such as those of attribute "linkstamp" may be null.
        if (value != null) {
          try {
            if (value instanceof Label label) {
              elem.setAttribute("value", labelPrinter.toString(label));
            } else {
              elem.setAttribute("value", value.toString());
            }
          } catch (DOMException e) {
            elem.setAttribute("value", "[[[ERROR: could not be encoded as XML]]]");
          }
        }
      }
    }
    return elem;
  }

  private static Element createValueElement(
      Document doc, Type<?> type, Object value, LabelPrinter labelPrinter) {
    return createValueElement(doc, type, ImmutableList.of(value), labelPrinter);
  }

  /**
   * Creates the given DOM element, adding <code>configurable="yes"</code> if it represents a
   * configurable single-value attribute (configurable list attributes simply have their lists
   * merged into an aggregate flat list).
   */
  private static Element createSingleValueElement(Document doc, String name, boolean configurable) {
    Element elem = doc.createElement(name);
    if (configurable) {
      elem.setAttribute("configurable", "yes");
    }
    return elem;
  }
}
