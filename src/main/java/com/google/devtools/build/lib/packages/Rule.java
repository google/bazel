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

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkPositionIndex;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper.attributeOrNull;
import static com.google.devtools.build.lib.util.HashCodes.hashObjects;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.NullEventHandler;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.StarlarkImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.License.DistributionType;
import com.google.devtools.build.lib.packages.Package.ConfigSettingVisibilityPolicy;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;

/**
 * An instance of a build rule in the build language. A rule has a name, a package to which it
 * belongs, a class such as <code>cc_library</code>, and set of typed attributes. The set of
 * attribute names and types is a property of the rule's class. The use of the term "class" here has
 * nothing to do with Java classes. All rules are implemented by the same Java classes, Rule and
 * RuleClass.
 *
 * <p>Here is a typical rule as it appears in a BUILD file:
 *
 * <pre>
 * cc_library(name = 'foo',
 *            defines = ['-Dkey=value'],
 *            srcs = ['foo.cc'],
 *            deps = ['bar'])
 * </pre>
 */
// Non-final only for mocking in tests. Do not subclass!
public class Rule implements Target, DependencyFilter.AttributeInfoProvider {

  /** Label predicate that allows every label. */
  public static final Predicate<Label> ALL_LABELS = Predicates.alwaysTrue();

  private static final String NAME = RuleClass.NAME_ATTRIBUTE.getName();
  private static final String GENERATOR_FUNCTION = "generator_function";
  private static final String GENERATOR_LOCATION = "generator_location";
  private static final String GENERATOR_NAME = "generator_name";

  private static final int ATTR_SIZE_THRESHOLD = 126;

  private static final OutputFile[] NO_OUTPUTS = new OutputFile[0];

  public static final String IS_EXECUTABLE_ATTRIBUTE_NAME = "$is_executable";

  private final Package pkg;
  private final Label label;
  private final RuleClass ruleClass;
  private final Location location;
  @Nullable private final CallStack.Node interiorCallStack;

  /**
   * The length of this rule's generator name if it is a prefix of its name, otherwise zero.
   *
   * <p>The generator name of a rule is the {@code name} parameter passed to a macro that
   * instantiates the rule. Most rules instantiated via macro follow this pattern:
   *
   * <pre>{@code
   * def some_macro(name):
   *   some_rule(name = name + '_some_suffix')
   * }</pre>
   *
   * thus resulting in a generator name which is a prefix of the rule name. In such a case, we save
   * memory by storing the length of the generator name instead of the string. Note that this saves
   * memory from both the storage in {@link #attrValues} and the string itself (if it is not
   * otherwise retained). This optimization works because this field does not push the shallow heap
   * cost of {@link Rule} beyond an 8-byte threshold. If it did, this optimization would be a net
   * loss.
   */
  private int generatorNamePrefixLength = 0;

  /**
   * Stores attribute values, taking on one of two shapes:
   *
   * <ol>
   *   <li>While the rule is mutable, the array length is equal to the number of attributes. Each
   *       array slot holds the attribute value for the corresponding index or null if not set.
   *   <li>After {@link #freeze}, the array is compacted to store only necessary values. Nulls and
   *       values that match {@link Attribute#getDefaultValue} are omitted to save space. Ordering
   *       of attributes by their index is preserved.
   * </ol>
   */
  private Object[] attrValues;

  /**
   * Holds bits of metadata about attributes, taking on one of three shapes:
   *
   * <ol>
   *   <li>While the rule is mutable, contains one bit for each attribute indicating whether it was
   *       explicitly set.
   *   <li>After {@link #freeze} for rules with fewer than 126 attributes (extremely common case),
   *       contains one byte dedicated to each value in the compact representation of {@link
   *       #attrValues}, at corresponding array indices. The first bit indicates whether the
   *       attribute was explicitly set. The remaining 7 bits represent the attribute's index (as
   *       per {@link RuleClass#getAttributeIndex}). See {@link #freezeSmall}.
   *   <li>After {@link #freeze} for rules with 126 or more attributes (rare case), contains the
   *       full set of bytes from the mutable representation, followed by the index of each
   *       attribute stored in the compact representation of {@link #attrValues}. Because attribute
   *       indices may require a full byte, there is no room to pack the explicit bit as we do for
   *       the small case. See {@link #freezeLarge}.
   * </ol>
   */
  private byte[] attrBytes;

  /**
   * Output files generated by this rule.
   *
   * <p>To save memory, this field is either {@link #NO_OUTPUTS} for zero outputs, an {@link
   * OutputFile} for a single output, or an {@code OutputFile[]} for multiple outputs.
   *
   * <p>In the case of multiple outputs, all implicit outputs come before any explicit outputs in
   * the array.
   *
   * <p>The order of the implicit outputs is the same as returned by the implicit output function.
   * This allows a native rule implementation and native implicit outputs function to agree on the
   * index of a given kind of output. The order of explicit outputs preserves the attribute
   * iteration order and the order of values in a list attribute; the latter is important so that
   * {@code ctx.outputs.some_list} has a well-defined order.
   */
  // Initialized by populateOutputFilesInternal().
  private Object outputFiles;

  Rule(
      Package pkg,
      Label label,
      RuleClass ruleClass,
      Location location,
      @Nullable CallStack.Node interiorCallStack) {
    this.pkg = checkNotNull(pkg);
    this.label = checkNotNull(label);
    this.ruleClass = checkNotNull(ruleClass);
    this.location = checkNotNull(location);
    this.interiorCallStack = interiorCallStack;
    this.attrValues = new Object[ruleClass.getAttributeCount()];
    this.attrBytes = new byte[bitSetSize()];
  }

  void setContainsErrors() {
    pkg.setContainsErrors();
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Override
  public Package getPackage() {
    return pkg;
  }

  public RuleClass getRuleClassObject() {
    return ruleClass;
  }

  @Override
  public String getTargetKind() {
    return ruleClass.getTargetKind();
  }

  /** Returns the class of this rule. (e.g. "cc_library") */
  @Override
  public String getRuleClass() {
    return ruleClass.getName();
  }

  /**
   * Returns true iff the outputs of this rule should be created beneath the bin directory, false if
   * beneath genfiles. For most rule classes, this is constant, but for genrule, it is a property of
   * the individual target, derived from the 'output_to_bindir' attribute.
   */
  public boolean outputsToBindir() {
    return ruleClass.getName().equals("genrule") // this is unfortunate...
        ? NonconfigurableAttributeMapper.of(this).get("output_to_bindir", Type.BOOLEAN)
        : ruleClass.outputsToBindir();
  }

  /** Returns true if this rule is an analysis test (set by analysis_test = true). */
  public boolean isAnalysisTest() {
    return ruleClass.isAnalysisTest();
  }

  /**
   * Returns true if this rule has at least one attribute with an analysis test transition. (A
   * starlark-defined transition using analysis_test_transition()).
   */
  public boolean hasAnalysisTestTransition() {
    return ruleClass.hasAnalysisTestTransition();
  }

  public boolean isBuildSetting() {
    return ruleClass.getBuildSetting() != null;
  }

  /**
   * Returns true if this rule is in error.
   *
   * <p>Examples of rule errors include attributes with missing values or values of the wrong type.
   *
   * <p>Any error in a package means that all rules in the package are considered to be in error
   * (even if they were evaluated prior to the error). This policy is arguably stricter than need
   * be, but stopping a build only for some errors but not others creates user confusion.
   */
  public boolean containsErrors() {
    return pkg.containsErrors();
  }

  public boolean hasAspects() {
    return ruleClass.hasAspects();
  }

  /**
   * Returns an (unmodifiable, unordered) collection containing all the
   * Attribute definitions for this kind of rule.  (Note, this doesn't include
   * the <i>values</i> of the attributes, merely the schema.  Call
   * get[Type]Attr() methods to access the actual values.)
   */
  public Collection<Attribute> getAttributes() {
    return ruleClass.getAttributes();
  }

  /**
   * Returns true if the given attribute is configurable.
   */
  public boolean isConfigurableAttribute(String attributeName) {
    // TODO(murali): This method should be property of ruleclass not rule instance.
    // Further, this call to AbstractAttributeMapper.isConfigurable is delegated right back
    // to this instance!
    return AbstractAttributeMapper.isConfigurable(this, attributeName);
  }

  /**
   * Returns the attribute definition whose name is {@code attrName}, or null if not found. (Use
   * get[X]Attr for the actual value.)
   *
   * @deprecated use {@link AbstractAttributeMapper#getAttributeDefinition} instead
   */
  @Deprecated
  public Attribute getAttributeDefinition(String attrName) {
    return ruleClass.getAttributeByNameMaybe(attrName);
  }

  /**
   * Constructs and returns an immutable list containing all the declared output files of this rule.
   *
   * <p>There are two kinds of outputs. Explicit outputs are declared in attributes of type OUTPUT
   * or OUTPUT_LABEL. Implicit outputs are determined by custom rule logic in an "implicit outputs
   * function" (either defined natively or in Starlark), and are named following a template pattern
   * based on the target's attributes.
   *
   * <p>All implicit output files (declared in the {@link RuleClass}) are listed first, followed by
   * any explicit files (declared via output attributes). Additionally, both implicit and explicit
   * outputs will retain the relative order in which they were declared.
   */
  public ImmutableList<OutputFile> getOutputFiles() {
    return ImmutableList.copyOf(outputFilesArray());
  }

  /**
   * Constructs and returns an immutable list of all the implicit output files of this rule, in the
   * order they were declared.
   */
  ImmutableList<OutputFile> getImplicitOutputFiles() {
    ImmutableList.Builder<OutputFile> result = ImmutableList.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        break;
      }
      result.add(output);
    }
    return result.build();
  }

  /**
   * Constructs and returns an immutable multimap of the explicit outputs, from attribute name to
   * associated value.
   *
   * <p>Keys are listed in the same order as attributes. Order of attribute values (outputs in an
   * output list) is preserved.
   *
   * <p>Since this is a multimap, attributes that have no associated outputs are omitted from the
   * result.
   */
  public ImmutableListMultimap<String, OutputFile> getExplicitOutputFileMap() {
    ImmutableListMultimap.Builder<String, OutputFile> result = ImmutableListMultimap.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        result.put(output.getOutputKey(), output);
      }
    }
    return result.build();
  }

  /**
   * Returns a map of the Starlark-defined implicit outputs, from dict key to output file.
   *
   * <p>If there is no implicit outputs function, or it is a native one, an empty map is returned.
   *
   * <p>This is not a multimap because Starlark-defined implicit output functions return exactly one
   * output per key.
   */
  public ImmutableMap<String, OutputFile> getStarlarkImplicitOutputFileMap() {
    if (!(ruleClass.getDefaultImplicitOutputsFunction()
        instanceof StarlarkImplicitOutputsFunction)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, OutputFile> result = ImmutableMap.builder();
    for (OutputFile output : outputFilesArray()) {
      if (!output.isImplicit()) {
        break;
      }
      result.put(output.getOutputKey(), output);
    }
    return result.buildOrThrow();
  }

  private OutputFile[] outputFilesArray() {
    return outputFiles instanceof OutputFile
        ? new OutputFile[] {(OutputFile) outputFiles}
        : (OutputFile[]) outputFiles;
  }

  @Override
  public Location getLocation() {
    return location;
  }

  /**
   * Returns the stack of function calls active when this rule was instantiated.
   *
   * <p>Requires reconstructing the call stack from a compact representation, so should only be
   * called when the full call stack is needed.
   */
  public ImmutableList<StarlarkThread.CallStackEntry> reconstructCallStack() {
    ImmutableList.Builder<StarlarkThread.CallStackEntry> stack = ImmutableList.builder();
    stack.add(StarlarkThread.callStackEntry(StarlarkThread.TOP_LEVEL, location));
    for (CallStack.Node node = interiorCallStack; node != null; node = node.next()) {
      stack.add(node.toCallStackEntry());
    }
    return stack.build();
  }

  @Nullable
  CallStack.Node getInteriorCallStack() {
    return interiorCallStack;
  }

  @Override
  public Rule getAssociatedRule() {
    return this;
  }

  /*
   *******************************************************************
   * Attribute accessor functions.
   *
   * The below provide access to attribute definitions and other generic
   * metadata.
   *
   * For access to attribute *values* (e.g. "What's the value of attribute
   * X for Rule Y?"), go through {@link RuleContext#attributes}. If no
   * RuleContext is available, create a localized {@link AbstractAttributeMapper}
   * instance instead.
   *******************************************************************
   */

  /**
   * Returns true iff the rule class has an attribute with the given name and type.
   *
   * <p>Note: RuleContext also has isAttrDefined(), which takes Aspects into account. Whenever
   * possible, use RuleContext.isAttrDefined() instead of this method.
   */
  public boolean isAttrDefined(String attrName, Type<?> type) {
    return ruleClass.hasAttr(attrName, type);
  }

  @Nullable
  private String getRelativeLocation() {
    // Determining the workspace root only works reliably if both location and label point to files
    // in the same package.
    // It would be preferable to construct the path from the label itself, but this doesn't work for
    // rules created from function calls in a subincluded file, even if both files share a path
    // prefix (for example, when //a/package:BUILD subincludes //a/package/with/a/subpackage:BUILD).
    // We can revert to that approach once subincludes aren't supported anymore.
    //
    // TODO(b/151165647): this logic has always been wrong:
    // it spuriously matches occurrences of the package name earlier in the path.
    String absolutePath = location.toString();
    int pos = absolutePath.indexOf(label.getPackageName());
    return (pos < 0) ? null : absolutePath.substring(pos);
  }

  /** Copies attribute values from the given rule to this rule. */
  void copyAttributesFrom(Rule rule) {
    checkArgument(
        ruleClass.equals(rule.ruleClass),
        "Rule class mismatch: (this=%s, given=%s)",
        ruleClass,
        rule.ruleClass);
    checkArgument(rule.isFrozen(), "Not frozen: %s", rule);
    checkState(!isFrozen(), "Already frozen: %s", this);
    this.attrValues = rule.attrValues;
    this.attrBytes = rule.attrBytes;
  }

  void setAttributeValue(Attribute attribute, Object value, boolean explicit) {
    checkState(!isFrozen(), "Already frozen: %s", this);
    String attrName = attribute.getName();
    if (attrName.equals(NAME)) {
      // Avoid unnecessarily storing the name in attrValues - it's stored in the label.
      return;
    }
    if (attrName.equals(GENERATOR_NAME)) {
      String generatorName = (String) value;
      if (getName().startsWith(generatorName)) {
        generatorNamePrefixLength = generatorName.length();
        return;
      }
    }
    Integer attrIndex = ruleClass.getAttributeIndex(attrName);
    checkArgument(attrIndex != null, "Attribute %s is not valid for this rule", attrName);
    if (explicit) {
      checkState(!getExplicitBit(attrIndex), "Attribute %s already explicitly set", attrName);
      setExplicitBit(attrIndex);
    }
    attrValues[attrIndex] = value;
  }

  /**
   * Returns the value of the given attribute for this rule. Returns null for invalid attributes and
   * default value if attribute was not set.
   *
   * @param attrName the name of the attribute to lookup.
   */
  @Nullable
  public Object getAttr(String attrName) {
    if (attrName.equals(NAME)) {
      return getName();
    }
    Integer attrIndex = ruleClass.getAttributeIndex(attrName);
    return attrIndex == null ? null : getAttrWithIndex(attrIndex);
  }

  /**
   * Returns the value of the given attribute if it has the right type.
   *
   * @throws IllegalArgumentException if the attribute does not have the expected type.
   */
  @Nullable
  public <T> Object getAttr(String attrName, Type<T> type) {
    if (attrName.equals(NAME)) {
      checkAttrType(attrName, type, RuleClass.NAME_ATTRIBUTE);
      return getName();
    }

    Integer index = ruleClass.getAttributeIndex(attrName);
    if (index == null) {
      throw new IllegalArgumentException(
          "No such attribute " + attrName + " in " + ruleClass + " rule " + label);
    }
    checkAttrType(attrName, type, ruleClass.getAttribute(index));
    return getAttrWithIndex(index);
  }

  /**
   * Returns the value of the attribute with the given index. Returns null, if no such attribute
   * exists OR no value was set.
   */
  @Nullable
  private Object getAttrWithIndex(int attrIndex) {
    Object value = getAttrIfStored(attrIndex);
    if (value != null) {
      return value;
    }
    Attribute attr = ruleClass.getAttribute(attrIndex);
    if (attr.hasComputedDefault()) {
      // Frozen rules don't store computed defaults, so get it from the attribute. Mutable rules do
      // store computed defaults if they've been populated. If no value is stored for a mutable
      // rule, return null here since resolving the default could trigger reads of other attributes
      // which have not yet been populated. Note that in this situation returning null does not
      // result in a correctness issue, since the value for the attribute is actually a function to
      // compute the value.
      return isFrozen() ? attr.getDefaultValue(this) : null;
    }
    if (attr.isMaterializing()) {
      checkState(isFrozen(), "Mutable rule missing LateBoundDefault");
      return attr.getMaterializer();
    }
    if (attr.isLateBound()) {
      // Frozen rules don't store late bound defaults.
      checkState(isFrozen(), "Mutable rule missing LateBoundDefault");
      return attr.getLateBoundDefault();
    }
    return switch (attr.getName()) {
      case GENERATOR_FUNCTION -> interiorCallStack != null ? interiorCallStack.functionName() : "";
      case GENERATOR_LOCATION -> interiorCallStack != null ? getRelativeLocation() : "";
      case GENERATOR_NAME ->
          generatorNamePrefixLength > 0 ? getName().substring(0, generatorNamePrefixLength) : "";
      default -> attr.getDefaultValue(this);
    };
  }

  /**
   * Returns the attribute value at the specified index if stored in this rule, otherwise {@code
   * null}.
   *
   * <p>Unlike {@link #getAttr}, does not fall back to the default value.
   */
  @Nullable
  Object getAttrIfStored(int attrIndex) {
    checkPositionIndex(attrIndex, attrCount() - 1);
    return switch (getAttrState()) {
      case MUTABLE -> attrValues[attrIndex];
      case FROZEN_SMALL -> {
        int index = binarySearchAttrBytes(0, attrIndex, 0x7f);
        yield index < 0 ? null : attrValues[index];
      }
      case FROZEN_LARGE -> {
        if (attrBytes.length == 0) {
          yield null;
        }
        int bitSetSize = bitSetSize();
        int index = binarySearchAttrBytes(bitSetSize, attrIndex, 0xff);
        yield index < 0 ? null : attrValues[index - bitSetSize];
      }
    };
  }

  /**
   * Returns raw attribute values stored by this rule.
   *
   * <p>The indices of attribute values in the returned list are not guaranteed to be consistent
   * with the other methods of this class. If this is important, which is generally the case, avoid
   * this method.
   *
   * <p>The returned iterable may contain null values. Its {@link Iterable#iterator} is
   * unmodifiable.
   */
  Iterable<Object> getRawAttrValues() {
    return () -> Iterators.forArray(attrValues);
  }

  /** See {@link #isAttributeValueExplicitlySpecified(String)} */
  @Override
  public boolean isAttributeValueExplicitlySpecified(Attribute attribute) {
    return isAttributeValueExplicitlySpecified(attribute.getName());
  }

  /**
   * Returns true iff the value of the specified attribute is explicitly set in the BUILD file. This
   * returns true also if the value explicitly specified in the BUILD file is the same as the
   * attribute's default value. In addition, this method return false if the rule has no attribute
   * with the given name.
   */
  public boolean isAttributeValueExplicitlySpecified(String attrName) {
    if (attrName.equals(NAME)) {
      return true;
    }
    if (attrName.equals(GENERATOR_FUNCTION)
        || attrName.equals(GENERATOR_LOCATION)
        || attrName.equals(GENERATOR_NAME)) {
      return wasCreatedByMacro();
    }
    Integer attrIndex = ruleClass.getAttributeIndex(attrName);
    if (attrIndex == null) {
      return false;
    }
    return switch (getAttrState()) {
      case MUTABLE, FROZEN_LARGE -> getExplicitBit(attrIndex);
      case FROZEN_SMALL -> {
        int index = binarySearchAttrBytes(0, attrIndex, 0x7f);
        yield index >= 0 && (attrBytes[index] & 0x80) != 0;
      }
    };
  }

  /** Returns index into {@link #attrBytes} for {@code attrIndex}, or -1 if not found */
  private int binarySearchAttrBytes(int start, int attrIndex, int mask) {
    // Binary search, treating values as unsigned bytes.
    int lo = start;
    int hi = attrBytes.length - 1;
    while (hi >= lo) {
      int mid = (lo + hi) / 2;
      int midAttrIndex = attrBytes[mid] & mask;
      if (midAttrIndex == attrIndex) {
        return mid;
      } else if (midAttrIndex < attrIndex) {
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return -1;
  }

  private void checkAttrType(String attrName, Type<?> requestedType, Attribute attr) {
    if (requestedType != attr.getType()) {
      throw new IllegalArgumentException(
          "Attribute "
              + attrName
              + " is of type "
              + attr.getType()
              + " and not of type "
              + requestedType
              + " in "
              + ruleClass
              + " rule "
              + label);
    }
  }

  /**
   * Returns {@code true} if this rule's attributes are immutable.
   *
   * <p>Frozen rules optimize for space by omitting storage for non-explicit attribute values that
   * match the {@link Attribute} default. If {@link #getAttrIfStored} returns {@code null}, the
   * value should be taken from either {@link Attribute#getLateBoundDefault} for late-bound defaults
   * or {@link Attribute#getDefaultValue} for all other attributes (including computed defaults).
   *
   * <p>Mutable rules have no such optimization. During rule creation, this allows for
   * distinguishing whether a computed default (which may depend on other unset attributes) is
   * available.
   */
  boolean isFrozen() {
    return getAttrState() != AttrState.MUTABLE;
  }

  /** Makes this rule's attributes immutable and compacts their representation. */
  void freeze() {
    if (isFrozen()) {
      return;
    }

    BitSet indicesToStore = new BitSet();
    for (int i = 0; i < attrValues.length; i++) {
      Object value = attrValues[i];
      if (value == null) {
        continue;
      }
      if (!getExplicitBit(i)) {
        Attribute attr = ruleClass.getAttribute(i);
        if (value.equals(attr.getDefaultValueUnchecked())) {
          // Non-explicit value matches the attribute's default. Save space by omitting storage.
          continue;
        }
      }
      indicesToStore.set(i);
    }

    if (attrCount() < ATTR_SIZE_THRESHOLD) {
      freezeSmall(indicesToStore);
    } else {
      freezeLarge(indicesToStore);
    }
    // Sanity check to ensure mutable vs frozen is distinguishable.
    checkState(isFrozen(), "Freeze unsuccessful");
  }

  private void freezeSmall(BitSet indicesToStore) {
    int numToStore = indicesToStore.cardinality();
    Object[] compactValues = new Object[numToStore];
    byte[] compactBytes = new byte[numToStore];

    int attrIndex = 0;
    for (int i = 0; i < numToStore; i++) {
      attrIndex = indicesToStore.nextSetBit(attrIndex);
      byte byteValue = (byte) (0x7f & attrIndex);
      if (getExplicitBit(attrIndex)) {
        byteValue = (byte) (byteValue | 0x80);
      }
      compactBytes[i] = byteValue;
      compactValues[i] = attrValues[attrIndex];
      attrIndex++;
    }

    this.attrValues = compactValues;
    this.attrBytes = compactBytes;
  }

  private void freezeLarge(BitSet indicesToStore) {
    int numToStore = indicesToStore.cardinality();
    int bitSetSize = attrBytes.length;
    Object[] compactValues = new Object[numToStore];
    byte[] compactBytes = Arrays.copyOf(attrBytes, bitSetSize + numToStore);

    int attrIndex = 0;
    for (int i = 0; i < numToStore; i++) {
      attrIndex = indicesToStore.nextSetBit(attrIndex);
      compactBytes[i + bitSetSize] = (byte) attrIndex;
      compactValues[i] = attrValues[attrIndex];
      attrIndex++;
    }

    this.attrValues = compactValues;
    this.attrBytes = compactBytes;
  }

  private int attrCount() {
    return ruleClass.getAttributeCount();
  }

  private enum AttrState {
    MUTABLE,
    FROZEN_SMALL,
    FROZEN_LARGE
  }

  private AttrState getAttrState() {
    int attrCount = attrCount();
    // This check works because the name attribute is never stored, so the compact representation
    // of attrValues will always have length < attrCount.
    if (attrValues.length == attrCount) {
      return AttrState.MUTABLE;
    }
    return attrCount < ATTR_SIZE_THRESHOLD ? AttrState.FROZEN_SMALL : AttrState.FROZEN_LARGE;
  }

  /** Calculates the number of bytes necessary to have an explicit bit for each attribute. */
  private int bitSetSize() {
    // ceil(attrCount() / 8)
    return (attrCount() + 7) / 8;
  }

  private boolean getExplicitBit(int attrIndex) {
    int byteIndex = attrIndex / 8;
    int bitIndex = attrIndex % 8;
    byte byteValue = attrBytes[byteIndex];
    return (byteValue & (1 << bitIndex)) != 0;
  }

  private void setExplicitBit(int attrIndex) {
    int byteIndex = attrIndex / 8;
    int bitIndex = attrIndex % 8;
    byte byteValue = attrBytes[byteIndex];
    attrBytes[byteIndex] = (byte) (byteValue | (1 << bitIndex));
  }

  /**
   * Returns a {@link BuildType.SelectorList} for the given attribute if the attribute is
   * configurable for this rule, null otherwise.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public <T> BuildType.SelectorList<T> getSelectorList(String attributeName, Type<T> type) {
    Integer index = ruleClass.getAttributeIndex(attributeName);
    if (index == null) {
      return null;
    }
    Object attrValue = getAttrIfStored(index);
    if (!(attrValue instanceof BuildType.SelectorList)) {
      return null;
    }
    if (((BuildType.SelectorList<?>) attrValue).getOriginalType() != type) {
      throw new IllegalArgumentException(
          "Attribute "
              + attributeName
              + " is not of type "
              + type
              + " in "
              + ruleClass
              + " rule "
              + label);
    }
    return (BuildType.SelectorList<T>) attrValue;
  }

  /**
   * Returns whether this rule was created by a macro.
   */
  public boolean wasCreatedByMacro() {
    return interiorCallStack != null || hasStringAttribute(GENERATOR_NAME);
  }

  /** Returns the macro that generated this rule, or an empty string. */
  public String getGeneratorFunction() {
    Object value = getAttr(GENERATOR_FUNCTION);
    if (value instanceof String) {
      return (String) value;
    }
    return "";
  }

  private boolean hasStringAttribute(String attrName) {
    Object value = getAttr(attrName);
    if (value instanceof String) {
      return !((String) value).isEmpty();
    }
    return false;
  }

  /**
   * Returns a new list containing all direct dependencies (all types except outputs and nodeps).
   */
  public List<Label> getLabels() {
    List<Label> labels = new ArrayList<>();
    AggregatingAttributeMapper.of(this).visitAllLabels((attribute, label) -> labels.add(label));
    return labels;
  }

  /**
   * Returns a sorted set containing all labels that match a given {@link DependencyFilter}, not
   * including outputs.
   *
   * @param filter A dependency filter that determines whether a label should be included in the
   *     result. {@link DependencyFilter#test} is called with this rule and the attribute that
   *     contains the label. The label will be contained in the result iff the predicate returns
   *     {@code true} <em>and</em> the label is not an output.
   */
  public ImmutableSortedSet<Label> getSortedLabels(DependencyFilter filter) {
    ImmutableSortedSet.Builder<Label> labels = ImmutableSortedSet.naturalOrder();
    AggregatingAttributeMapper.of(this)
        .visitLabels(filter, (Attribute attribute, Label label) -> labels.add(label));
    return labels.build();
  }

  /**
   * Returns a {@link SetMultimap} containing all non-output labels matching a given {@link
   * DependencyFilter}, keyed by the corresponding attribute.
   *
   * <p>Labels that appear in multiple attributes will be mapped from each of their corresponding
   * attributes, provided they pass the {@link DependencyFilter}.
   *
   * @param filter A dependency filter that determines whether a label should be included in the
   *     result. {@link DependencyFilter#test} is called with this rule and the attribute that
   *     contains the label. The label will be contained in the result iff the predicate returns
   *     {@code true} <em>and</em> the label is not an output.
   */
  public SetMultimap<Attribute, Label> getTransitions(DependencyFilter filter) {
    SetMultimap<Attribute, Label> transitions = HashMultimap.create();
    AggregatingAttributeMapper.of(this).visitLabels(filter, transitions::put);
    return transitions;
  }

  /**
   * Collects the output files (both implicit and explicit). Must be called before the output
   * accessors methods can be used, and must be called only once.
   */
  void populateOutputFiles(EventHandler eventHandler, PackageIdentifier pkgId)
      throws LabelSyntaxException, InterruptedException {
    populateOutputFilesInternal(
        eventHandler,
        pkgId,
        ruleClass.getDefaultImplicitOutputsFunction(),
        /* checkLabels= */ true);
  }

  void populateOutputFilesUnchecked(
      Package.Builder pkgBuilder, ImplicitOutputsFunction implicitOutputsFunction)
      throws InterruptedException {
    try {
      populateOutputFilesInternal(
          NullEventHandler.INSTANCE,
          pkgBuilder.getPackageIdentifier(),
          implicitOutputsFunction,
          /* checkLabels= */ false);
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  @FunctionalInterface
  private interface ExplicitOutputHandler {
    void accept(Attribute attribute, Label outputLabel) throws LabelSyntaxException;
  }

  @FunctionalInterface
  private interface ImplicitOutputHandler {
    void accept(String outputKey, String outputName);
  }

  private void populateOutputFilesInternal(
      EventHandler eventHandler,
      PackageIdentifier pkgId,
      ImplicitOutputsFunction implicitOutputsFunction,
      boolean checkLabels)
      throws LabelSyntaxException, InterruptedException {
    Preconditions.checkState(outputFiles == null);

    List<OutputFile> outputs = new ArrayList<>();
    // Detects collisions where the same output key is used for both an explicit and implicit entry.
    HashSet<String> implicitOutputKeys = new HashSet<>();

    // We need the implicits to appear before the explicits in the final data structure, so we
    // process them first. We check for duplicates while handling the explicits.
    //
    // Each of these cases has two subcases, so we factor their bodies out into lambdas.

    ImplicitOutputHandler implicitOutputHandler =
        // outputKey: associated dict key if Starlark-defined, empty string otherwise
        // outputName: package-relative path fragment
        (outputKey, outputName) -> {
          Label label;
          if (checkLabels) { // controls label syntax validation only
            try {
              label = Label.create(pkgId, outputName);
            } catch (LabelSyntaxException e) {
              reportError(
                  String.format(
                      "illegal output file name '%s' in rule %s due to: %s",
                      outputName, this.label, e.getMessage()),
                  eventHandler);
              return;
            }
          } else {
            label = Label.createUnvalidated(pkgId, outputName);
          }
          validateOutputLabel(label, eventHandler);

          outputs.add(OutputFile.createImplicit(label, this, outputKey));
          implicitOutputKeys.add(outputKey);
        };

    // Populate the implicit outputs.
    try {
      RawAttributeMapper attributeMap = RawAttributeMapper.of(this);
      // TODO(bazel-team): Reconsider the ImplicitOutputsFunction abstraction. It doesn't seem to be
      // a good fit if it forces us to downcast in situations like this. It also causes
      // getImplicitOutputs() to declare that it throws EvalException (which then has to be
      // explicitly disclaimed by the subclass SafeImplicitOutputsFunction).
      if (implicitOutputsFunction instanceof StarlarkImplicitOutputsFunction) {
        for (Map.Entry<String, String> e :
            ((StarlarkImplicitOutputsFunction) implicitOutputsFunction)
                .calculateOutputs(eventHandler, attributeMap)
                .entrySet()) {
          implicitOutputHandler.accept(e.getKey(), e.getValue());
        }
      } else {
        for (String out : implicitOutputsFunction.getImplicitOutputs(eventHandler, attributeMap)) {
          implicitOutputHandler.accept(/*outputKey=*/ "", out);
        }
      }
    } catch (EvalException e) {
      reportError(String.format("In rule %s: %s", label, e.getMessageWithStack()), eventHandler);
    }

    ExplicitOutputHandler explicitOutputHandler =
        (attribute, outputLabel) -> {
          String attrName = attribute.getName();
          if (implicitOutputKeys.contains(attrName)) {
            reportError(
                String.format(
                    "Implicit output key '%s' collides with output attribute name", attrName),
                eventHandler);
          }
          if (checkLabels) {
            if (!outputLabel.getPackageIdentifier().equals(pkg.getPackageIdentifier())) {
              throw new IllegalStateException(
                  String.format(
                      "Label for attribute %s should refer to '%s' but instead refers to '%s'"
                          + " (label '%s')",
                      attribute,
                      pkg.getName(),
                      outputLabel.getPackageFragment(),
                      outputLabel.getName()));
            }
            if (outputLabel.getName().equals(".")) {
              throw new LabelSyntaxException("output file name can't be equal '.'");
            }
          }
          validateOutputLabel(outputLabel, eventHandler);

          outputs.add(OutputFile.createExplicit(outputLabel, this, attrName));
        };

    // Populate the explicit outputs.
    NonconfigurableAttributeMapper nonConfigurableAttributes =
        NonconfigurableAttributeMapper.of(this);
    for (Attribute attribute : ruleClass.getAttributes()) {
      String name = attribute.getName();
      Type<?> type = attribute.getType();
      if (type == BuildType.OUTPUT) {
        Label label = nonConfigurableAttributes.get(name, BuildType.OUTPUT);
        if (label != null) {
          explicitOutputHandler.accept(attribute, label);
        }
      } else if (type == BuildType.OUTPUT_LIST) {
        for (Label label : nonConfigurableAttributes.get(name, BuildType.OUTPUT_LIST)) {
          explicitOutputHandler.accept(attribute, label);
        }
      }
    }

    if (outputs.isEmpty()) {
      outputFiles = NO_OUTPUTS;
    } else if (outputs.size() == 1) {
      outputFiles = outputs.get(0);
    } else {
      outputFiles = outputs.toArray(OutputFile[]::new);
    }
  }

  private void validateOutputLabel(Label label, EventHandler eventHandler) {
    if (label.getName().equals(getName())) {
      // TODO(bazel-team): for now (23 Apr 2008) this is just a warning.  After
      // June 1st we should make it an error.
      reportWarning("target '" + getName() + "' is both a rule and a file; please choose "
                    + "another name for the rule", eventHandler);
    }
  }

  void reportError(String message, EventHandler eventHandler) {
    eventHandler.handle(Package.error(location, message, PackageLoading.Code.STARLARK_EVAL_ERROR));
    setContainsErrors();
  }

  private void reportWarning(String message, EventHandler eventHandler) {
    eventHandler.handle(Event.warn(location, message));
  }

  /** Returns a string of the form "cc_binary rule //foo:foo" */
  @Override
  public String toString() {
    return getRuleClass() + " rule " + label;
  }

  /**
   * Implementation of {@link #getRawVisibility} that avoids constructing a {@code RuleVisibility}.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private List<Label> getRawVisibilityLabels() {
    Integer visibilityIndex = ruleClass.getAttributeIndex("visibility");
    if (visibilityIndex == null) {
      return null;
    }
    return (List<Label>) getAttrIfStored(visibilityIndex);
  }

  @Override
  @Nullable
  public RuleVisibility getRawVisibility() {
    List<Label> rawLabels = getRawVisibilityLabels();
    // The attribute value was already validated when it was set, so call the unchecked method.
    return rawLabels != null ? RuleVisibility.parseUnchecked(rawLabels) : null;
  }

  /**
   * Retrieves the package's default visibility, or for certain rule classes, injects a different
   * default visibility.
   */
  @Override
  public RuleVisibility getDefaultVisibility() {
    if (ruleClass.getName().equals("bind")) {
      return RuleVisibility.PUBLIC; // bind rules are always public.
    }
    // Temporary logic to relax config_setting's visibility enforcement while depot migrations set
    // visibility settings properly (legacy code may have visibility settings that would break if
    // enforced). See https://github.com/bazelbuild/bazel/issues/12669. Ultimately this entire
    // conditional should be removed.
    if (ruleClass.getName().equals("config_setting")
        && pkg.getConfigSettingVisibilityPolicy() == ConfigSettingVisibilityPolicy.DEFAULT_PUBLIC) {
      return RuleVisibility.PUBLIC; // Default: //visibility:public.
    }

    return Target.super.getDefaultVisibility();
  }

  @Override
  public Iterable<Label> getVisibilityDependencyLabels() {
    List<Label> rawLabels = getRawVisibilityLabels();
    if (rawLabels == null) {
      return getDefaultVisibility().getDependencyLabels();
    }
    RuleVisibility constantVisibility = RuleVisibility.parseIfConstant(rawLabels);
    if (constantVisibility != null) {
      return constantVisibility.getDependencyLabels();
    }
    // Filter out labels like :__pkg__ and :__subpackages__.
    return Iterables.filter(rawLabels, label -> PackageSpecification.fromLabel(label) == null);
  }

  @Override
  public List<Label> getVisibilityDeclaredLabels() {
    List<Label> rawLabels = getRawVisibilityLabels();
    return rawLabels != null ? rawLabels : getDefaultVisibility().getDeclaredLabels();
  }

  @Override
  public boolean isConfigurable() {
    return true;
  }

  @Override
  public Set<DistributionType> getDistributions() {
    if (isAttrDefined("distribs", BuildType.DISTRIBUTIONS)
        && isAttributeValueExplicitlySpecified("distribs")) {
      return NonconfigurableAttributeMapper.of(this).get("distribs", BuildType.DISTRIBUTIONS);
    } else {
      return License.DEFAULT_DISTRIB;
    }
  }

  @Override
  public License getLicense() {
    // New style licenses defined by Starlark rules don't
    // have old-style licenses. This is hardcoding the representation
    // of new-style rules, but it's in the old-style licensing code path
    // and will ultimately be removed.
    if (ruleClass.isPackageMetadataRule()) {
      return License.NO_LICENSE;
    } else if (isAttrDefined("licenses", BuildType.LICENSE)
        && isAttributeValueExplicitlySpecified("licenses")) {
      return NonconfigurableAttributeMapper.of(this).get("licenses", BuildType.LICENSE);
    } else if (ruleClass.ignoreLicenses()) {
      return License.NO_LICENSE;
    } else {
      return pkg.getPackageArgs().license();
    }
  }

  /**
   * Returns the license of the output of the binary created by this rule, or null if it is not
   * specified.
   */
  @Nullable
  public License getToolOutputLicense(AttributeMap attributes) {
    if (isAttrDefined("output_licenses", BuildType.LICENSE)
        && attributes.isAttributeValueExplicitlySpecified("output_licenses")) {
      return attributes.get("output_licenses", BuildType.LICENSE);
    } else {
      return null;
    }
  }

  /** Returns the Set of all tags exhibited by this target. May be empty. */
  @Override
  public Set<String> getRuleTags() {
    Set<String> ruleTags = new LinkedHashSet<>();
    for (Attribute attribute : ruleClass.getAttributes()) {
      if (attribute.isTaggable()) {
        Type<?> attrType = attribute.getType();
        String name = attribute.getName();
        // This enforces the expectation that taggable attributes are non-configurable.
        Object value = NonconfigurableAttributeMapper.of(this).get(name, attrType);
        Set<String> tags = attrType.toTagSet(value, name);
        ruleTags.addAll(tags);
      }
    }
    return ruleTags;
  }

  @Override
  public boolean isRule() {
    return true;
  }

  @Override
  @Nullable
  public String getDeprecationWarning() {
    return attributeOrNull(this, "deprecation", Type.STRING);
  }

  @Override
  public boolean isTestOnly() {
    Boolean value = attributeOrNull(this, "testonly", Type.BOOLEAN);
    if (value == null) {
      return false;
    }
    return value;
  }

  @Override
  public boolean satisfies(RequiredProviders required) {
    return required.isSatisfiedBy(getRuleClassObject().getAdvertisedProviders());
  }

  @Override
  public TestTimeout getTestTimeout() {
    return TestTimeout.getTestTimeout(this);
  }

  @Override
  public boolean isForDependencyResolution() {
    return getRuleClassObject().isDependencyResolutionRule();
  }

  @Override
  public AdvertisedProviderSet getAdvertisedProviders() {
    return getRuleClassObject().getAdvertisedProviders();
  }

  /**
   * Computes labels of additional dependencies that can be provided by aspects that this rule can
   * require from its direct dependencies.
   */
  public Collection<Label> getAspectLabelsSuperset(DependencyFilter predicate) {
    if (!hasAspects()) {
      return ImmutableList.of();
    }
    SetMultimap<Attribute, Label> labels = LinkedHashMultimap.create();
    for (Attribute attribute : this.getAttributes()) {
      for (Aspect candidateClass : attribute.getAspects(this)) {
        AspectDefinition.addAllAttributesOfAspect(labels, candidateClass, predicate);
      }
    }
    return labels.values();
  }

  /**
   * Should this rule instance resolve toolchains?
   *
   * <p>This may happen for two reasons:
   *
   * <ol>
   *   <li>The rule uses toolchains by definition ({@link
   *       RuleClass.Builder#toolchainResolutionMode(ToolchainResolutionMode)}
   *   <li>The rule instance has a select() or target_compatible_with attribute, which means it may
   *       depend on target platform properties that are only provided when toolchain resolution is
   *       enabled.
   * </ol>
   */
  public boolean useToolchainResolution() {
    return ruleClass.useToolchainResolution(this);
  }

  public boolean isExecutable() {
    if (getRuleClassObject().hasAttr(IS_EXECUTABLE_ATTRIBUTE_NAME, Type.BOOLEAN)) {
      return NonconfigurableAttributeMapper.of(this)
          .get(IS_EXECUTABLE_ATTRIBUTE_NAME, Type.BOOLEAN);
    }
    return false;
  }

  public RepositoryName getRepository() {
    return label.getPackageIdentifier().getRepository();
  }

  /** Returns the suffix of target kind for all rules. */
  public static String targetKindSuffix() {
    return " rule";
  }

  @Override
  public TargetData reduceForSerialization() {
    return new RuleData(
        ruleClass,
        getLocation(),
        ImmutableSet.copyOf(getRuleTags()),
        getLabel(),
        getDeprecationWarning(),
        isTestOnly(),
        getTestTimeout());
  }

  @VisibleForSerialization // (private) allows RuleDataCodec visibility
  static class RuleData implements TargetData {
    private final RuleClassData ruleClassData;
    private final Location location;
    // TODO(b/297857068): this is only used to report TargetCompletion, so it should never be
    // read from a deserialized instance. Refine the ConfiguredTargetAndData API and delete this.
    private final ImmutableSet<String> ruleTags;
    private final Label label;
    @Nullable private final String deprecationWarning;
    private final boolean isTestOnly;
    @Nullable private final TestTimeout testTimeout;

    @VisibleForSerialization // (private) allows RuleDataCodec visibility
    RuleData(
        RuleClassData ruleClassData,
        Location location,
        ImmutableSet<String> ruleTags,
        Label label,
        @Nullable String deprecationWarning,
        boolean isTestOnly,
        @Nullable TestTimeout testTimeout) {
      this.ruleClassData = ruleClassData;
      this.location = location;
      this.ruleTags = ruleTags;
      this.label = label;
      this.deprecationWarning = deprecationWarning;
      this.isTestOnly = isTestOnly;
      this.testTimeout = testTimeout;
    }

    RuleClassData getRuleClassData() {
      return ruleClassData;
    }

    @Override
    public String getTargetKind() {
      return ruleClassData.getTargetKind();
    }

    @Override
    public Location getLocation() {
      return location;
    }

    @Override
    public String getRuleClass() {
      return ruleClassData.getName();
    }

    @Override
    public ImmutableSet<String> getRuleTags() {
      return ruleTags;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    @Override
    public boolean isRule() {
      return true;
    }

    @Override
    @Nullable
    public String getDeprecationWarning() {
      return deprecationWarning;
    }

    @Override
    public boolean satisfies(RequiredProviders required) {
      return required.isSatisfiedBy(ruleClassData.getAdvertisedProviders());
    }

    @Override
    public boolean isTestOnly() {
      return isTestOnly;
    }

    @Override
    public boolean isForDependencyResolution() {
      return ruleClassData.isDependencyResolutionRule();
    }

    @Override
    public AdvertisedProviderSet getAdvertisedProviders() {
      return ruleClassData.getAdvertisedProviders();
    }

    @Override
    @Nullable
    public TestTimeout getTestTimeout() {
      return testTimeout;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof RuleData)) {
        return false;
      }
      RuleData that = (RuleData) obj;
      return ruleClassData.equals(that.ruleClassData)
          && location.equals(that.location)
          && label.equals(that.label)
          && Objects.equals(deprecationWarning, that.deprecationWarning)
          && isTestOnly == that.isTestOnly
          && Objects.equals(testTimeout, that.testTimeout);
    }

    @Override
    public int hashCode() {
      // Extremely likely equal if this many fields match.
      return hashObjects(ruleClassData, location, label);
    }

    @Override
    public String toString() {
      return toStringHelper(this)
          .add("ruleClassData", ruleClassData)
          .add("location", location)
          .add("ruleTags", ruleTags)
          .add("label", label)
          .add("deprecationWarning", deprecationWarning)
          .add("isTestOnly", isTestOnly)
          .add("testTimeout", testTimeout)
          .toString();
    }
  }
}
