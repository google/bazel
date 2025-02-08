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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.docgen.DocgenConsts.RuleType;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.AttributeInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.ModuleInfo;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos.RuleInfo;
import com.google.protobuf.ExtensionRegistry;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Class that parses the documentation fragments of rule-classes and
 * generates the html format documentation.
 */
@VisibleForTesting
public class BuildDocCollector {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final Splitter SHARP_SPLITTER = Splitter.on('#').limit(2).trimResults();

  private final RuleLinkExpander linkExpander;
  private final SourceUrlMapper urlMapper;
  private final ConfiguredRuleClassProvider ruleClassProvider;

  public BuildDocCollector(
      RuleLinkExpander linkExpander,
      SourceUrlMapper urlMapper,
      ConfiguredRuleClassProvider ruleClassProvider) {
    this.linkExpander = linkExpander;
    this.urlMapper = urlMapper;
    this.ruleClassProvider = ruleClassProvider;
  }

  /**
   * Parse the file containing rules blocked from documentation. The list is simply a list of rules
   * separated by new lines. Line comments can be added to the file by starting them with #.
   *
   * @param denyList The name of the file containing the denylist.
   * @return The set of denylisted rules.
   * @throws IOException
   */
  @VisibleForTesting
  public static Set<String> readDenyList(String denyList) throws IOException {
    Set<String> result = new HashSet<String>();
    if (denyList != null && !denyList.isEmpty()) {
      File file = new File(denyList);
      try (BufferedReader reader = Files.newBufferedReader(file.toPath(), UTF_8)) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          String rule = SHARP_SPLITTER.split(line).iterator().next();
          if (!rule.isEmpty()) {
            result.add(rule);
          }
        }
      }
    }
    return result;
  }

  /**
   * Creates a map of rule names (keys) to rule documentation (values).
   *
   * <p>This method crawls the specified input directories for rule class definitions (as Java
   * source files) which contain the rules' and attributes' definitions as comments in a specific
   * format. The keys in the returned Map correspond to these rule classes.
   *
   * <p>In the Map's values, all references pointing to other rules, rule attributes, and general
   * documentation (e.g. common definitions, make variables, etc.) are expanded into hyperlinks. The
   * links generated follow either the multi-page or single-page Build Encyclopedia model depending
   * on the mode set for the {@link RuleLinkExpander} that was passed to the constructor.
   *
   * @param inputJavaDirs list of directories to scan for documentation in Java source code
   * @param inputStardocProtos list of file paths of stardoc_output.ModuleInfo binary proto files
   *     generated from Build Encyclopedia entry point .bzl files; documentation from these protos
   *     takes precedence over documentation from {@code inputJavaDirs}
   * @param denyList specify an optional denylist file that list some rules that should not be
   *     listed in the output.
   * @throws BuildEncyclopediaDocException
   * @throws IOException
   * @return Map of rule class to rule documentation.
   */
  public Map<String, RuleDocumentation> collect(
      List<String> inputJavaDirs, List<String> inputStardocProtos, String denyList)
      throws BuildEncyclopediaDocException, IOException {
    // Read the denyList file
    Set<String> denylistedRules = readDenyList(denyList);
    // RuleDocumentations are generated in order (based on rule type then alphabetically).
    // The ordering is also used to determine in which rule doc the common attribute docs are
    // generated (they are generated at the first appearance).
    Map<String, RuleDocumentation> ruleDocEntries = new TreeMap<>();
    // RuleDocumentationAttribute objects equal based on attributeName so they have to be
    // collected in a List instead of a Set.
    ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries =
        LinkedListMultimap.create();

    // Map of rule name to the file (Java source file or Build Encyclopedia entry point .bzl file)
    // and symbol from which its documentation was obtained.
    Map<String, DocumentationOrigin> ruleDocOrigin = new HashMap<>();

    // Set of files already processed. The same file may be encountered multiple times because
    // directories are processed recursively, and an input directory may be a subdirectory of
    // another one.
    Set<File> processedFiles = new HashSet<>();

    for (String inputJavaDir : inputJavaDirs) {
      logger.atFine().log("Processing input directory: %s", inputJavaDir);
      int ruleNum = ruleDocEntries.size();
      collectJavaSourceDocs(
          processedFiles,
          ruleDocOrigin,
          ruleDocEntries,
          denylistedRules,
          attributeDocEntries,
          new File(inputJavaDir));
      logger.atFine().log(
          "%d rule documentations found in %s", ruleDocEntries.size() - ruleNum, inputJavaDir);
    }
    processJavaSourceRuleAttributeDocs(ruleDocEntries.values(), attributeDocEntries);

    for (String stardocProtoPath : inputStardocProtos) {
      logger.atFine().log("Processing input file: %s", stardocProtoPath);
      int numRulesCollected =
          collectModuleInfoDocs(
              ruleDocOrigin,
              ruleDocEntries,
              denylistedRules,
              attributeDocEntries,
              ModuleInfo.parseFrom(
                  new FileInputStream(stardocProtoPath), ExtensionRegistry.getEmptyRegistry()),
              urlMapper);
      logger.atFine().log(
          "%d rule documentations found in %s", numRulesCollected, stardocProtoPath);
    }

    linkExpander.addIndex(buildRuleIndex(ruleDocEntries.values()));
    for (RuleDocumentation rule : ruleDocEntries.values()) {
      rule.setRuleLinkExpander(linkExpander);
    }
    return ruleDocEntries;
  }

  /**
   * Generates an index mapping rule name to its normalized rule family name.
   */
  private Map<String, String> buildRuleIndex(Iterable<RuleDocumentation> rules) {
    Map<String, String> index = new HashMap<>();
    for (RuleDocumentation rule : rules) {
      index.put(rule.getRuleName(), RuleFamily.normalize(rule.getRuleFamily()));
    }
    return index;
  }

  /**
   * Go through all attributes of native rules whose documentation was retrieved from Java sources,
   * and search the best attribute documentation if exists. The best documentation is the closest
   * documentation in the ancestor graph. E.g. if java_library.deps documented in $rule and
   * $java_rule then the one in $java_rule is going to apply since it's a closer ancestor of
   * java_library.
   *
   * <p>Note: this function should be called before any calls to collectModuleInfoDocs.
   */
  private void processJavaSourceRuleAttributeDocs(
      Iterable<RuleDocumentation> ruleDocEntries,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries)
      throws BuildEncyclopediaDocException {
    for (RuleDocumentation ruleDoc : ruleDocEntries) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName());
      if (ruleClass != null) {
        if (ruleClass.isDocumented()) {
          Class<? extends RuleDefinition> ruleDefinition =
              ruleClassProvider.getRuleClassDefinition(ruleDoc.getRuleName()).getClass();
          for (Attribute attribute : ruleClass.getAttributes()) {
            if (!attribute.isDocumented()) {
              continue;
            }
            String attrName = attribute.getName();
            List<RuleDocumentationAttribute> attributeDocList =
                attributeDocEntries.get(attrName);
            if (attributeDocList != null) {
              // There are attribute docs for this attribute.
              // Search the closest one in the ancestor graph.
              // Note that there can be only one 'closest' attribute since we forbid multiple
              // inheritance of the same attribute in RuleClass.
              int minLevel = Integer.MAX_VALUE;
              RuleDocumentationAttribute bestAttributeDoc = null;
              for (RuleDocumentationAttribute attributeDoc : attributeDocList) {
                int level = attributeDoc.getDefinitionClassAncestryLevel(
                    ruleDefinition,
                    ruleClassProvider);
                if (level >= 0 && level < minLevel) {
                  bestAttributeDoc = attributeDoc;
                  minLevel = level;
                }
              }
              if (bestAttributeDoc != null) {
                // We have to copy the matching RuleDocumentationAttribute here so that we don't
                // overwrite the reference to the actual attribute later by another attribute with
                // the same ancestor but different default values.
                ruleDoc.addAttribute(bestAttributeDoc.copyAndUpdateFrom(attribute));
              // If there is no matching attribute doc try to add the common.
              } else if (ruleDoc.getRuleType().equals(RuleType.BINARY)
                  && PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.BINARY_ATTRIBUTES.get(attrName));
              } else if (ruleDoc.getRuleType().equals(RuleType.TEST)
                  && PredefinedAttributes.TEST_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.TEST_ATTRIBUTES.get(attrName));
              } else if (PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.COMMON_ATTRIBUTES.get(attrName));
              } else if (PredefinedAttributes.TYPICAL_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.TYPICAL_ATTRIBUTES.get(attrName));
              }
            }
          }
        }
      } else {
        throw ruleDoc.createException("Can't find RuleClass for " + ruleDoc.getRuleName());
      }
    }
  }

  /**
   * Crawls the specified inputPath and collects the raw rule and rule attribute documentation.
   *
   * <p>This method crawls the specified input directory (recursively calling itself for all
   * subdirectories) and reads each Java source file using {@link SourceFileReader} to extract the
   * raw rule and attribute documentation embedded in comments in a specific format. The extracted
   * documentation is then further processed, such as by {@link
   * BuildDocCollector#collect(List<String>, String, RuleLinkExpander), collect}, in order to
   * associate each rule's documentation with its attribute documentation.
   *
   * <p>This method returns the following through its parameters: the set of Java source files
   * processed, a map of rule name to the source file it was extracted from, a map of rule name to
   * the documentation to the rule, and a multimap of attribute name to attribute documentation.
   *
   * @param processedFiles The set of Java source files files that have already been processed in
   *     order to avoid reprocessing the same file.
   * @param ruleDocOrigin Map of rule name to the file and symbol from which its documentation was
   *     obtained.
   * @param ruleDocEntries Map of rule name to rule documentation.
   * @param denyList The set of denylisted rules whose documentation should not be extracted.
   * @param attributeDocEntries Multimap of rule attribute name to attribute documentation.
   * @param inputPath The File representing the Java source file or directory to read.
   * @throws BuildEncyclopediaDocException
   * @throws IOException
   */
  public void collectJavaSourceDocs(
      Set<File> processedFiles,
      Map<String, DocumentationOrigin> ruleDocOrigin,
      Map<String, RuleDocumentation> ruleDocEntries,
      Set<String> denyList,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries,
      File inputPath)
      throws BuildEncyclopediaDocException, IOException {
    if (processedFiles.contains(inputPath)) {
      return;
    }

    if (inputPath.isFile()) {
      if (DocgenConsts.JAVA_SOURCE_FILE_SUFFIX.apply(inputPath.getName())) {
        SourceFileReader sfr =
            new SourceFileReader(
                ruleClassProvider, inputPath.getAbsolutePath(), urlMapper.urlOfFile(inputPath));
        sfr.readDocsFromComments();
        for (RuleDocumentation d : sfr.getRuleDocEntries()) {
          String ruleName = d.getRuleName();
          if (!denyList.contains(ruleName)) {
            if (ruleDocEntries.containsKey(ruleName)
                && !ruleDocOrigin.get(ruleName).file().equals(inputPath.toString())) {
              logger.atWarning().log(
                  "Rule '%s' from '%s' overrides previously seen rule '%s' from '%s'",
                  ruleName,
                  inputPath,
                  ruleDocOrigin.get(ruleName).symbol(),
                  ruleDocOrigin.get(ruleName).file());
            }
            ruleDocOrigin.put(ruleName, DocumentationOrigin.create(inputPath.toString(), ruleName));
            ruleDocEntries.put(ruleName, d);
          }
        }
        if (attributeDocEntries != null) {
          // Collect all attribute documentations from this file.
          attributeDocEntries.putAll(sfr.getAttributeDocEntries());
        }
      }
    } else if (inputPath.isDirectory()) {
      for (File childPath : inputPath.listFiles()) {
        collectJavaSourceDocs(
            processedFiles,
            ruleDocOrigin,
            ruleDocEntries,
            denyList,
            attributeDocEntries,
            childPath);
      }
    }

    processedFiles.add(inputPath);
  }

  /**
   * Collects rule and rule attribute documentation from a stardoc_output.ModuleInfo message
   * generated from a Build Encyclopedia entry point .bzl file.
   *
   * <p>The module doc string for the .bzl file is interpreted as the rule family name.
   *
   * <p>Any rule exported by the .bzl file is expected to be contained in a struct whose name is a
   * {@link DocgenConsts.RuleType} name suffixed with "_rules" - for example, "binary_rules",
   * "library_rules", etc.
   *
   * <p>This method returns the following through its parameters: a map of rule name to the file and
   * symbol it was extracted from, a map of rule name to the documentation of the rule, and a
   * multimap of attribute name to attribute documentation.
   *
   * @param ruleDocOrigin Map of rule name to the file and symbol from which its documentation was
   *     obtained.
   * @param ruleDocEntries Map of rule name to rule documentation.
   * @param denyList The set of denylisted rules whose documentation should not be extracted.
   * @param attributeDocEntries Multimap of rule attribute name to attribute documentation.
   * @param moduleInfo A stardoc_output.ModuleInfo message representing a Build Encyclopedia entry
   *     point .bzl file.
   * @param urlMapper Mapper from source labels to source code repository URLs
   * @return number of rules whose documentation was collected
   */
  @VisibleForTesting
  static int collectModuleInfoDocs(
      Map<String, DocumentationOrigin> ruleDocOrigin,
      Map<String, RuleDocumentation> ruleDocEntries,
      Set<String> denyList,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries,
      ModuleInfo moduleInfo,
      SourceUrlMapper urlMapper)
      throws BuildEncyclopediaDocException {
    String entryPointFileLabel = moduleInfo.getFile();

    Matcher familyMatcher =
        DocgenConsts.STARDOC_OUTPUT_FAMILY_NAME_AND_SUMMARY.matcher(
            moduleInfo.getModuleDocstring().strip());
    if (!familyMatcher.matches()) {
      throw new BuildEncyclopediaDocException(
          entryPointFileLabel,
          "Module doc string is expected to be a single line representing a rule family name, "
              + "optionally followed by a blank line and summary text; for example, "
              + "`\"\"\"C / C++\"\"\"`");
    }
    String ruleFamily = familyMatcher.group("family");
    String ruleFamilySummary = Strings.nullToEmpty(familyMatcher.group("summary"));

    int numRulesCollected = 0;
    for (RuleInfo ruleInfo : moduleInfo.getRuleInfoList()) {
      Matcher ruleNameMatcher =
          DocgenConsts.STARDOC_OUTPUT_RULE_NAME.matcher(ruleInfo.getRuleName());
      if (!ruleNameMatcher.matches()) {
        throw new BuildEncyclopediaDocException(
            entryPointFileLabel,
            String.format(
                "Unexpected rule symbol: %s; rules must be exported in structs, with the struct's"
                    + " name specifying the rule type, for example, `library_rules = struct("
                    + "java_import = _java_import, ...)`",
                ruleInfo.getRuleName()));
      }
      String ruleType = Ascii.toUpperCase(ruleNameMatcher.group("type"));
      String ruleName = ruleNameMatcher.group("name");
      if (!denyList.contains(ruleName)) {
        String ruleOriginFileLabel = ruleInfo.getOriginKey().getFile();
        if (ruleDocEntries.containsKey(ruleName)) {
          logger.atWarning().log(
              "Rule '%s' from '%s' (defined in '%s') overrides previously seen rule '%s' from '%s'",
              ruleInfo.getRuleName(),
              entryPointFileLabel,
              ruleOriginFileLabel,
              ruleDocOrigin.get(ruleName).symbol(),
              ruleDocOrigin.get(ruleName).file());
        }
        ImmutableSet.Builder<String> flags = ImmutableSet.builder();
        if (ruleType.equals(DocgenConsts.STARLARK_GENERIC_RULE_TYPE)) {
          // Note that if FLAG_GENERIC_RULE is set, RuleDocumentation constructor will set the rule
          // type to OTHER.
          flags.add(DocgenConsts.FLAG_GENERIC_RULE);
        }
        RuleDocumentation ruleDoc =
            new RuleDocumentation(
                ruleName,
                ruleType,
                ruleFamily,
                ruleInfo.getDocString(),
                ruleOriginFileLabel,
                urlMapper.urlOfLabel(ruleOriginFileLabel),
                flags.build(),
                // Add family summary only to the first rule encountered, to avoid duplication in
                // final rendered output
                numRulesCollected == 0 ? ruleFamilySummary : "");

        // Inject standard inherited attributes for Starlark rules (since they always inherit from
        // one of 3 possible base rule classes; see StarlarkRuleClassFunctions#createRule). If in
        // the future we want to document native rules via ModuleInfo protos, we will need to list
        // inherited attributes in the proto.
        ruleDoc.addAttributes(PredefinedAttributes.COMMON_ATTRIBUTES.values());
        if (ruleDoc.getRuleType().equals(RuleType.TEST)) {
          ruleDoc.addAttributes(PredefinedAttributes.TEST_ATTRIBUTES.values());
        } else if (ruleDoc.getRuleType().equals(RuleType.BINARY)) {
          ruleDoc.addAttributes(PredefinedAttributes.BINARY_ATTRIBUTES.values());
        }

        for (AttributeInfo attributeInfo : ruleInfo.getAttributeList()) {
          String attributeName = attributeInfo.getName();
          if (attributeName.equals("name")) {
            // We do not want the implicit "name" attribute injected into proto output by
            // starlark_doc_extract because we inject "name" at the template level in
            // templates/be/rules.vm
            continue;
          }
          if (attributeInfo.getDocString().isEmpty()
              && PredefinedAttributes.TYPICAL_ATTRIBUTES.containsKey(attributeName)) {
            // We link empty-docstring attributes to the common table based purely on attribute name
            // (same as processJavaSourceRuleAttributeDocs does for native rule attributes).
            // TODO(arostovtsev): should we verify attribute type and default value too? That would
            // require moving the definition of common attributes from a free-text velocity template
            // to a structured format.
            ruleDoc.addAttribute(PredefinedAttributes.TYPICAL_ATTRIBUTES.get(attributeName));
          } else {
            boolean deprecated =
                DocgenConsts.STARDOC_OUTPUT_DEPRECATED_DOCSTRING
                    .matcher(attributeInfo.getDocString())
                    .find();
            ruleDoc.addAttribute(
                RuleDocumentationAttribute.createFromAttributeInfo(
                    attributeInfo,
                    ruleOriginFileLabel,
                    deprecated
                        ? ImmutableSet.of(DocgenConsts.FLAG_DEPRECATED)
                        : ImmutableSet.of()));
          }
        }

        ruleDocOrigin.put(
            ruleName, DocumentationOrigin.create(entryPointFileLabel, ruleInfo.getRuleName()));
        ruleDocEntries.put(ruleName, ruleDoc);
        numRulesCollected++;
      }
    }
    return numRulesCollected;
  }

  /** The file and symbol from which documentation was obtained. */
  record DocumentationOrigin(String file, String symbol) {
    DocumentationOrigin {
      requireNonNull(file, "file");
      requireNonNull(symbol, "symbol");
    }

    static DocumentationOrigin create(String file, String symbol) {
      return new DocumentationOrigin(file, symbol);
    }
  }
}
