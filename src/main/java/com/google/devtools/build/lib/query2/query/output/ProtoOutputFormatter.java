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

import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.ENVIRONMENT_GROUP;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.GENERATED_FILE;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.PACKAGE_GROUP;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.RULE;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.SOURCE_FILE;
import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashFunction;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeFormatter;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.ProtoUtils;
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
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.Attribute.Discriminator;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.GeneratedFile;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.QueryResult;
import com.google.devtools.build.lib.query2.proto.proto2api.Build.SourceFile;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.starlarkdocextract.ExtractorContext;
import com.google.devtools.build.lib.starlarkdocextract.LabelRenderer;
import com.google.devtools.build.lib.starlarkdocextract.RuleInfoExtractor;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkThread;

/**
 * An output formatter that outputs a protocol buffer representation of a query result and outputs
 * the proto bytes to the output print stream. By taking the bytes and calling {@code mergeFrom()}
 * on a {@code Build.QueryResult} object the full result can be reconstructed.
 */
public class ProtoOutputFormatter extends AbstractUnorderedFormatter {

  /** A special attribute name for the rule implementation hash code. */
  protected static final String RULE_IMPLEMENTATION_HASH_ATTR_NAME = "$rule_implementation_hash";

  private static final Comparator<Build.Attribute> ATTRIBUTE_NAME =
      Comparator.comparing(Build.Attribute::getName);

  private static final ImmutableSet<Type<?>> SCALAR_TYPES =
      ImmutableSet.of(
          Type.INTEGER,
          Type.STRING,
          BuildType.LABEL,
          BuildType.NODEP_LABEL,
          BuildType.DORMANT_LABEL,
          BuildType.OUTPUT,
          Type.BOOLEAN,
          BuildType.TRISTATE,
          BuildType.LICENSE);

  private AspectResolver aspectResolver;
  private DependencyFilter dependencyFilter;
  private boolean packageGroupIncludesDoubleSlash;
  private boolean relativeLocations;
  private boolean includeDefaultValues = true;
  private Predicate<String> ruleAttributePredicate = Predicates.alwaysTrue();
  private boolean flattenSelects = true;
  private boolean includeLocations = true;
  private boolean includeRuleInputsAndOutputs = true;
  private boolean includeSyntheticAttributeHash = false;
  private boolean includeInstantiationStack = false;
  private boolean includeDefinitionStack = false;
  private boolean includeStarlarkRuleEnv = true;
  protected boolean includeAttributeSourceAspects = false;
  private HashFunction hashFunction = null;

  /** Non-null if and only if --proto:rule_classes option is set. */
  @Nullable private RuleClassInfoFormatter ruleClassInfoFormatter;

  @Nullable private PathFragment overrideSourceRoot;

  @Nullable private EventHandler eventHandler;

  @Override
  public String getName() {
    return "proto";
  }

  @Override
  public void setOptions(
      CommonQueryOptions options, AspectResolver aspectResolver, HashFunction hashFunction) {
    super.setOptions(options, aspectResolver, hashFunction);
    this.aspectResolver = aspectResolver;
    this.dependencyFilter = FormatUtils.getDependencyFilter(options);
    this.packageGroupIncludesDoubleSlash = options.incompatiblePackageGroupIncludesDoubleSlash;
    this.relativeLocations = options.relativeLocations;
    this.includeDefaultValues = options.protoIncludeDefaultValues;
    this.ruleAttributePredicate = newAttributePredicate(options.protoOutputRuleAttributes);
    this.flattenSelects = options.protoFlattenSelects;
    this.includeLocations = options.protoIncludeLocations;
    this.includeRuleInputsAndOutputs = options.protoIncludeRuleInputsAndOutputs;
    this.includeSyntheticAttributeHash = options.protoIncludeSyntheticAttributeHash;
    this.includeInstantiationStack = options.protoIncludeInstantiationStack;
    this.includeDefinitionStack = options.protoIncludeDefinitionStack;
    this.includeAttributeSourceAspects = options.protoIncludeAttributeSourceAspects;
    this.includeStarlarkRuleEnv = options.protoIncludeStarlarkRuleEnv;
    this.hashFunction = hashFunction;
    this.ruleClassInfoFormatter = options.protoRuleClasses ? new RuleClassInfoFormatter() : null;
  }

  @Override
  public void setOverrideSourceRoot(PathFragment overrideSourceRoot) {
    this.overrideSourceRoot = overrideSourceRoot;
  }

  @Override
  public void setEventHandler(@Nullable EventHandler eventHandler) {
    this.eventHandler = eventHandler;
  }

  private static Predicate<String> newAttributePredicate(List<String> outputAttributes) {
    if (outputAttributes.equals(ImmutableList.of("all"))) {
      return Predicates.alwaysTrue();
    } else if (outputAttributes.isEmpty()) {
      return Predicates.alwaysFalse();
    } else {
      return Predicates.in(ImmutableSet.copyOf(outputAttributes));
    }
  }

  @Override
  public OutputFormatterCallback<Target> createPostFactoStreamCallback(
      OutputStream out, QueryOptions options, LabelPrinter labelPrinter) {
    return new StreamedQueryResultFormatter(out, labelPrinter);
  }

  @Override
  public ThreadSafeOutputFormatterCallback<Target> createStreamCallback(
      OutputStream out, QueryOptions options, QueryEnvironment<?> env) {
    return new SynchronizedDelegatingOutputFormatterCallback<>(
        createPostFactoStreamCallback(out, options, env.getLabelPrinter()));
  }

  /** Converts a logical {@link Target} object into a {@link Build.Target} protobuffer. */
  public Build.Target toTargetProtoBuffer(Target target, LabelPrinter labelPrinter)
      throws InterruptedException {
    return toTargetProtoBuffer(target, labelPrinter, /* extraDataForAttrHash= */ "");
  }

  /** Converts a logical {@link Target} object into a {@link Build.Target} protobuffer. */
  public Build.Target toTargetProtoBuffer(
      Target target, LabelPrinter labelPrinter, Object extraDataForAttrHash)
      throws InterruptedException {
    Build.Target.Builder targetPb = Build.Target.newBuilder();

    if (target instanceof Rule rule) {
      Build.Rule.Builder rulePb =
          Build.Rule.newBuilder()
              .setName(internalToUnicode(labelPrinter.toString(rule.getLabel())))
              .setRuleClass(internalToUnicode(rule.getRuleClass()));
      if (includeLocations) {
        rulePb.setLocation(
            internalToUnicode(
                FormatUtils.getLocation(target, relativeLocations, overrideSourceRoot)));
      }
      addAttributes(rulePb, rule, extraDataForAttrHash, labelPrinter);
      byte[] transitiveDigest = rule.getRuleClassObject().getRuleDefinitionEnvironmentDigest();
      if (transitiveDigest != null && includeRuleDefinitionEnvironment()) {
        // The RuleDefinitionEnvironment is always defined for Starlark rules and
        // always null for non Starlark rules.
        rulePb.addAttribute(
            Build.Attribute.newBuilder()
                .setName(RULE_IMPLEMENTATION_HASH_ATTR_NAME)
                .setType(ProtoUtils.getDiscriminatorFromType(Type.STRING))
                .setStringValue(
                    BaseEncoding.base16().lowerCase().encode(transitiveDigest))); // hexify
      }

      ImmutableMap<Aspect, ImmutableMultimap<Attribute, Label>> aspectsDependencies =
          aspectResolver.computeAspectDependencies(target, dependencyFilter);
      if (!aspectsDependencies.isEmpty()) {
        // Add information about additional attributes from aspects.
        List<Build.Attribute> attributes = new ArrayList<>();
        for (Map.Entry<Aspect, ImmutableMultimap<Attribute, Label>> aspectAttributes :
            aspectsDependencies.entrySet()) {
          Aspect aspect = aspectAttributes.getKey();
          for (Map.Entry<Attribute, Collection<Label>> entry :
              aspectAttributes.getValue().asMap().entrySet()) {
            Attribute attribute = entry.getKey();
            Collection<Label> labels = entry.getValue();
            if (!includeAspectAttribute(attribute, labels)) {
              continue;
            }
            Object attributeValue = getAspectAttributeValue(target, attribute, labels);
            Build.Attribute serializedAttribute =
                AttributeFormatter.getAttributeProto(
                    attribute,
                    attributeValue,
                    /* explicitlySpecified= */ false,
                    /* encodeBooleanAndTriStateAsIntegerAndString= */ true,
                    /* sourceAspect= */ aspect,
                    includeAttributeSourceAspects,
                    labelPrinter);
            attributes.add(serializedAttribute);
          }
        }

        rulePb.addAllAttribute(
            attributes.stream().distinct().sorted(ATTRIBUTE_NAME).collect(Collectors.toList()));
      }
      if (includeRuleInputsAndOutputs) {
        // Add all deps from aspects as rule inputs of current target.
        if (!aspectsDependencies.isEmpty()) {
          aspectsDependencies.values().stream()
              .flatMap(m -> m.values().stream())
              .distinct()
              .forEach(dep -> rulePb.addRuleInput(internalToUnicode(labelPrinter.toString(dep))));
        }
        // Include explicit elements for all direct inputs and outputs of a rule; this goes beyond
        // what is available from the attributes above, since it may also (depending on options)
        // include implicit outputs, exec-configuration outputs, and default values.
        rule.getSortedLabels(dependencyFilter)
            .forEach(input -> rulePb.addRuleInput(internalToUnicode(labelPrinter.toString(input))));
        rule.getOutputFiles().stream()
            .distinct()
            .forEach(
                output ->
                    rulePb.addRuleOutput(
                        internalToUnicode(labelPrinter.toString(output.getLabel()))));
      }
      for (String feature : rule.getPackage().getPackageArgs().features().toStringList()) {
        rulePb.addDefaultSetting(internalToUnicode(feature));
      }

      if (includeInstantiationStack) {
        for (StarlarkThread.CallStackEntry fr : rule.reconstructCallStack()) {
          // Always report relative locations.
          // (New fields needn't honor relativeLocations.)
          rulePb.addInstantiationStack(
              internalToUnicode(
                  FormatUtils.getRootRelativeLocation(fr.location, rule.getPackage())
                      + ": "
                      + fr.name));
        }
      }

      if (includeDefinitionStack && rule.getRuleClassObject().isStarlark()) {
        for (StarlarkThread.CallStackEntry fr : rule.getRuleClassObject().getCallStack()) {
          // Always report relative locations.
          // (New fields needn't honor relativeLocations.)
          rulePb.addDefinitionStack(
              internalToUnicode(
                  FormatUtils.getRootRelativeLocation(fr.location, rule.getPackage())
                      + ": "
                      + fr.name));
        }
      }

      if (ruleClassInfoFormatter != null) {
        ruleClassInfoFormatter.addRuleClassKeyAndInfoIfNeeded(rulePb, rule);
      }
      targetPb.setType(RULE);
      targetPb.setRule(rulePb);
    } else if (target instanceof OutputFile outputFile) {
      Label label = outputFile.getLabel();

      Rule generatingRule = outputFile.getGeneratingRule();
      GeneratedFile.Builder output =
          GeneratedFile.newBuilder()
              .setGeneratingRule(
                  internalToUnicode(labelPrinter.toString(generatingRule.getLabel())))
              .setName(internalToUnicode(labelPrinter.toString(label)));

      if (includeLocations) {
        output.setLocation(
            internalToUnicode(
                FormatUtils.getLocation(target, relativeLocations, overrideSourceRoot)));
      }
      targetPb.setType(GENERATED_FILE);
      targetPb.setGeneratedFile(output.build());
    } else if (target instanceof InputFile inputFile) {
      Label label = inputFile.getLabel();

      Build.SourceFile.Builder input =
          Build.SourceFile.newBuilder().setName(internalToUnicode(labelPrinter.toString(label)));

      if (includeLocations) {
        input.setLocation(
            internalToUnicode(
                FormatUtils.getLocation(target, relativeLocations, overrideSourceRoot)));
      }

      if (inputFile.getName().equals("BUILD")) {
        Iterable<Label> starlarkLoadLabels =
            aspectResolver == null
                ? inputFile.getPackage().getOrComputeTransitivelyLoadedStarlarkFiles()
                : aspectResolver.computeBuildFileDependencies(inputFile.getPackage());

        for (Label starlarkLoadLabel : starlarkLoadLabels) {
          input.addSubinclude(internalToUnicode(labelPrinter.toString(starlarkLoadLabel)));
        }

        for (String feature : inputFile.getPackage().getPackageArgs().features().toStringList()) {
          input.addFeature(internalToUnicode(feature));
        }

        input.setPackageContainsErrors(inputFile.getPackage().containsErrors());
      }

      // TODO(bazel-team): We're being inconsistent about whether we include the package's
      // default_visibility in the target. For files we do, but for rules we don't.

      for (Label visibilityDependency : target.getVisibilityDependencyLabels()) {
        input.addPackageGroup(internalToUnicode(labelPrinter.toString(visibilityDependency)));
      }

      for (Label visibilityDeclaration : target.getVisibilityDeclaredLabels()) {
        input.addVisibilityLabel(internalToUnicode(labelPrinter.toString(visibilityDeclaration)));
      }

      targetPb.setType(SOURCE_FILE);
      targetPb.setSourceFile(input);
    } else if (target instanceof FakeLoadTarget) {
      Label label = target.getLabel();
      SourceFile.Builder input =
          SourceFile.newBuilder().setName(internalToUnicode(labelPrinter.toString(label)));

      if (includeLocations) {
        input.setLocation(
            internalToUnicode(
                FormatUtils.getLocation(target, relativeLocations, overrideSourceRoot)));
      }
      targetPb.setType(SOURCE_FILE);
      targetPb.setSourceFile(input.build());
    } else if (target instanceof PackageGroup packageGroup) {
      Build.PackageGroup.Builder packageGroupPb =
          Build.PackageGroup.newBuilder()
              .setName(internalToUnicode(labelPrinter.toString(packageGroup.getLabel())));
      for (String containedPackage :
          packageGroup.getContainedPackages(packageGroupIncludesDoubleSlash)) {
        packageGroupPb.addContainedPackage(internalToUnicode(containedPackage));
      }
      for (Label include : packageGroup.getIncludes()) {
        packageGroupPb.addIncludedPackageGroup(internalToUnicode(labelPrinter.toString(include)));
      }

      targetPb.setType(PACKAGE_GROUP);
      targetPb.setPackageGroup(packageGroupPb);
    } else if (target instanceof EnvironmentGroup envGroup) {
      Build.EnvironmentGroup.Builder envGroupPb =
          Build.EnvironmentGroup.newBuilder()
              .setName(internalToUnicode(labelPrinter.toString(envGroup.getLabel())));
      for (Label env : envGroup.getEnvironments()) {
        envGroupPb.addEnvironment(internalToUnicode(labelPrinter.toString(env)));
      }
      for (Label defaultEnv : envGroup.getDefaults()) {
        envGroupPb.addDefault(internalToUnicode(labelPrinter.toString(defaultEnv)));
      }
      targetPb.setType(ENVIRONMENT_GROUP);
      targetPb.setEnvironmentGroup(envGroupPb);
    } else {
      throw new IllegalArgumentException(target.toString());
    }

    return targetPb.build();
  }

  protected void addAttributes(
      Build.Rule.Builder rulePb,
      Rule rule,
      Object extraDataForAttrHash,
      LabelPrinter labelPrinter) {
    Map<Attribute, Build.Attribute> serializedAttributes = Maps.newHashMap();
    AggregatingAttributeMapper attributeMapper = AggregatingAttributeMapper.of(rule);
    for (Attribute attr : rule.getAttributes()) {
      if (!shouldIncludeAttribute(rule, attr)) {
        continue;
      }
      Object attributeValue;
      if (flattenSelects || !attributeMapper.isConfigurable(attr.getName())) {
        attributeValue = getFlattenedAttributeValues(attr.getType(), rule, attr);
      } else {
        attributeValue = attributeMapper.getSelectorList(attr.getName(), attr.getType());
      }
      Build.Attribute serializedAttribute =
          AttributeFormatter.getAttributeProto(
              attr,
              attributeValue,
              rule.isAttributeValueExplicitlySpecified(attr),
              /* encodeBooleanAndTriStateAsIntegerAndString= */ true,
              /* sourceAspect= */ null,
              includeAttributeSourceAspects,
              labelPrinter);
      serializedAttributes.put(attr, serializedAttribute);
    }
    rulePb.addAllAttribute(
        serializedAttributes.values().stream()
            .distinct()
            .sorted(ATTRIBUTE_NAME)
            .collect(Collectors.toList()));

    if (includeSyntheticAttributeHash) {
      rulePb.addAttribute(
          Build.Attribute.newBuilder()
              .setName("$internal_attr_hash")
              .setStringValue(
                  SyntheticAttributeHashCalculator.compute(
                      rule,
                      serializedAttributes,
                      extraDataForAttrHash,
                      hashFunction,
                      includeAttributeSourceAspects,
                      includeStarlarkRuleEnv))
              .setType(Discriminator.STRING));
    }
  }

  protected boolean shouldIncludeAttribute(Rule rule, Attribute attr) {
    return (includeDefaultValues || rule.isAttributeValueExplicitlySpecified(attr))
        && ruleAttributePredicate.apply(attr.getName());
  }

  private Object getAspectAttributeValue(
      Target target, Attribute attribute, Collection<Label> labels) {
    Type<?> attributeType = attribute.getType();
    if (attributeType.equals(BuildType.LABEL)) {
      Preconditions.checkState(labels.size() == 1, "attribute=%s, labels=%s", attribute, labels);
      return Iterables.getOnlyElement(labels);
    } else if (attributeType.equals(BuildType.LABEL_KEYED_STRING_DICT)) {
      // Ideally we'd support LABEL_KEYED_STRING_DICT by getting the value directly from the aspect
      // definition vs. trying to reverse-construct it from the flattened labels as this method
      // does. Unfortunately any proper support surfaces a latent bug between --output=proto and
      // aspect attributes: "{@code labels} isn't the set of labels for a single attribute value but
      // for all values of all attributes with the same name. We can have multiple attributes with
      // the same name because multiple aspects may attach to a rule, and nothing is stopping them
      // from defining the same attribute names. That means the "Attribute" proto message doesn't
      // really represent a single attribute, in spite of its documented purpose. This all calls for
      // an API design upgrade to properly consider these relationships. Details at b/149982967.
      if (eventHandler != null) {
        eventHandler.handle(
            Event.error(
                String.format(
                    "Target \"%s\", aspect attribute \"%s\": type \"%s\" not yet supported with"
                        + " --output=proto.",
                    target.getLabel(), attribute.getName(), BuildType.LABEL_KEYED_STRING_DICT)));
      }
      // This return value is misleading when the above error isn't get triggered: it implies an
      // empty result with no signal that that result isn't accurate.
      // TODO(bazel-team): either make the result accurate or trigger an error universally. Letting
      // OutputFormatter.output() throw a QueryException is a promising approach.
      return ImmutableMap.of();
    } else {
      Preconditions.checkState(
          attributeType.equals(BuildType.LABEL_LIST),
          "attribute=%s, type=%s, labels=%s",
          attribute,
          attributeType,
          labels);
      return labels;
    }
  }

  /** Allow filtering of aspect attributes. */
  protected boolean includeAspectAttribute(Attribute attr, Collection<Label> value) {
    return true;
  }

  protected boolean includeRuleDefinitionEnvironment() {
    return true;
  }

  /**
   * Coerces the list {@code possibleValues} of values of type {@code attrType} to a single value of
   * that type, in the following way:
   *
   * <p>If the list contains a single value, return that value.
   *
   * <p>If the list contains zero or multiple values and the type is a scalar type, return {@code
   * null}.
   *
   * <p>If the list contains zero or multiple values and the type is a collection or map type, merge
   * the collections/maps in the list and return the merged collection/map.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private static Object getFlattenedAttributeValues(Type<?> attrType, Rule rule, Attribute attr) {
    boolean treatMultipleAsNone = SCALAR_TYPES.contains(attrType);
    Iterable<Object> possibleValues =
        PossibleAttributeValues.forRuleAndAttribute(rule, attr, treatMultipleAsNone);

    // If there is only one possible value, return it.
    if (Iterables.size(possibleValues) == 1) {
      return Iterables.getOnlyElement(possibleValues);
    }

    // Otherwise, there are multiple possible values. To conform to the message shape expected by
    // query output's clients, we must transform the list of possible values. This transformation
    // will be lossy, but this is the best we can do.

    // If the attribute's type is not a collection type, return null. Query output's clients do
    // not support list values for scalar attributes.
    if (SCALAR_TYPES.contains(attrType)) {
      return null;
    }

    // If the attribute's type is a collection type, merge the list of collections into a single
    // collection. This is a sensible solution for query output's clients, which are happy to get
    // the union of possible values.
    // TODO(bazel-team): replace below with "is ListType" check (or some variant)
    if (attrType == Types.STRING_LIST
        || attrType == BuildType.LABEL_LIST
        || attrType == BuildType.NODEP_LABEL_LIST
        || attrType == BuildType.DORMANT_LABEL_LIST
        || attrType == BuildType.OUTPUT_LIST
        || attrType == BuildType.DISTRIBUTIONS
        || attrType == Types.INTEGER_LIST) {
      ImmutableList.Builder<Object> builder = ImmutableList.builder();
      for (Object possibleValue : possibleValues) {
        Collection<Object> collection = (Collection<Object>) possibleValue;
        for (Object o : collection) {
          builder.add(o);
        }
      }
      return builder.build();
    }

    // Same for maps as for collections.
    if (attrType == Types.STRING_DICT
        || attrType == Types.STRING_LIST_DICT
        || attrType == BuildType.LABEL_LIST_DICT
        || attrType == BuildType.LABEL_DICT_UNARY
        || attrType == BuildType.LABEL_KEYED_STRING_DICT) {
      Map<Object, Object> mergedDict = new HashMap<>();
      for (Object possibleValue : possibleValues) {
        Map<Object, Object> stringDict = (Map<Object, Object>) possibleValue;
        for (Map.Entry<Object, Object> entry : stringDict.entrySet()) {
          mergedDict.put(entry.getKey(), entry.getValue());
        }
      }
      return mergedDict;
    }

    throw new AssertionError("Unknown type: " + attrType);
  }

  private static class RuleClassInfoFormatter {
    private final HashSet<String> ruleClassKeys = new HashSet<>();
    private final ExtractorContext extractorContext =
        ExtractorContext.builder()
            .labelRenderer(LabelRenderer.DEFAULT)
            .extractNativelyDefinedAttrs(true)
            .build();

    /**
     * Sets the rule_class_key field, and if the rule class key has not been seen before, also sets
     * the rule_class_info field.
     */
    public void addRuleClassKeyAndInfoIfNeeded(Build.Rule.Builder rulePb, Rule rule) {
      String ruleClassKey = rule.getRuleClassObject().getKey();
      rulePb.setRuleClassKey(internalToUnicode(ruleClassKey));
      if (ruleClassKeys.add(ruleClassKey)) {
        // TODO(b/368091415): instead of rule.getRuleClass(), we should be using the rule's public
        // name. But to find the public name, we would need access to the globals dictionary of
        // the compiled Starlark module in which the rule class was defined, which would have to
        // be retrieved from skyframe.
        rulePb.setRuleClassInfo(
            RuleInfoExtractor.buildRuleInfo(
                extractorContext, rule.getRuleClass(), rule.getRuleClassObject()));
      }
    }
  }

  /**
   * Specialized {@link OutputFormatterCallback} implementation which produces a valid {@link
   * QueryResult} in streaming fashion. Internally this class makes some reasonably sound and stable
   * assumptions about the format of serialized protos in order to improve memory overhead and
   * performance.
   */
  private class StreamedQueryResultFormatter extends OutputFormatterCallback<Target> {

    /**
     * Pseudo-arbitrarily chosen buffer size for output. Chosen to be large enough to fit a handful
     * of targets without needing to flush to the underlying output, which may not be buffered.
     */
    private static final int OUTPUT_BUFFER_SIZE = 16384;

    private final CodedOutputStream codedOut;
    private final LabelPrinter labelPrinter;

    private StreamedQueryResultFormatter(OutputStream out, LabelPrinter labelPrinter) {
      this.codedOut = CodedOutputStream.newInstance(out, OUTPUT_BUFFER_SIZE);
      this.labelPrinter = labelPrinter;
    }

    @Override
    public void processOutput(Iterable<Target> partialResult)
        throws IOException, InterruptedException {
      // Write out targets with their tag (field number) as if they were serialized as part of a
      // QueryResult proto. The assumptions we make about this being compatible with actually
      // constructing and serializing a QueryResult proto are protected by test coverage and proto
      // best practices.
      for (Target target : partialResult) {
        codedOut.writeMessage(
            QueryResult.TARGET_FIELD_NUMBER, toTargetProtoBuffer(target, labelPrinter));
      }
    }

    @Override
    public void close(boolean failFast) throws IOException {
      codedOut.flush();
    }
  }
}
