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

import com.google.devtools.build.lib.cmdline.Label;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/** A generated file that is the output of a rule. */
public abstract class OutputFile extends FileTarget {

  /**
   * Constructs an implicit output file with the given label, which must be in the generating rule's
   * package.
   *
   * @param outputKey either the map key returned by {@link
   *     ImplicitOutputsFunction.StarlarkImplicitOutputsFunction#calculateOutputs} or the empty
   *     string for natively defined implicit outputs
   */
  static OutputFile createImplicit(Label label, Rule generatingRule, String outputKey) {
    return new Implicit(label, generatingRule, outputKey);
  }

  /**
   * Constructs an explicit output file with the given label, which must be in the generating rule's
   * package.
   *
   * @param attrName the output attribute's name; used as the {@linkplain #getOutputKey output key}
   */
  static OutputFile createExplicit(Label label, Rule generatingRule, String attrName) {
    return new Explicit(label, generatingRule, attrName);
  }

  private final Rule generatingRule;
  private final String outputKey;

  private OutputFile(Label label, Rule generatingRule, String outputKey) {
    super(generatingRule.getPackage(), label);
    this.generatingRule = generatingRule;
    this.outputKey = outputKey;
  }

  @Override
  @Nullable
  public final RuleVisibility getRawVisibility() {
    return generatingRule.getRawVisibility();
  }

  @Override
  public final RuleVisibility getDefaultVisibility() {
    return generatingRule.getDefaultVisibility();
  }

  @Override
  public final RuleVisibility getVisibility() {
    return generatingRule.getVisibility();
  }

  @Override
  public final Iterable<Label> getVisibilityDependencyLabels() {
    return generatingRule.getVisibilityDependencyLabels();
  }

  @Override
  public final List<Label> getVisibilityDeclaredLabels() {
    return generatingRule.getVisibilityDeclaredLabels();
  }

  @Override
  public final boolean isConfigurable() {
    return true;
  }

  /** Returns the rule which generates this output file. */
  public final Rule getGeneratingRule() {
    return generatingRule;
  }

  @Override
  public final Package getPackage() {
    return generatingRule.getPackage();
  }

  /**
   * A kind of output file.
   *
   * <p>The FILESET kind is only supported for a non-open-sourced {@code fileset} rule.
   */
  public enum Kind {
    FILE,
    FILESET
  }

  /** Returns the kind of this output file. */
  public final Kind getKind() {
    return generatingRule.getRuleClassObject().getOutputFileKind();
  }

  @Override
  public final String getTargetKind() {
    return targetKind();
  }

  @Override
  public final Rule getAssociatedRule() {
    return generatingRule;
  }

  @Override
  public final Location getLocation() {
    return generatingRule.getLocation();
  }

  @Override
  public final boolean isOutputFile() {
    return true;
  }

  @Override
  public final Label getGeneratingRuleLabel() {
    return generatingRule.getLabel();
  }

  @Override
  public final String getDeprecationWarning() {
    return generatingRule.getDeprecationWarning();
  }

  @Override
  public final boolean isTestOnly() {
    return generatingRule.isTestOnly();
  }

  @Override
  public final boolean satisfies(RequiredProviders required) {
    return generatingRule.satisfies(required);
  }

  @Override
  public final TestTimeout getTestTimeout() {
    return TestTimeout.getTestTimeout(generatingRule);
  }

  @Override
  public AdvertisedProviderSet getAdvertisedProviders() {
    return generatingRule.getAdvertisedProviders();
  }

  /**
   * Returns this output file's output key.
   *
   * <p>An output key is an identifier used to access the output in {@code ctx.outputs}, or the
   * empty string in the case of an output that's not exposed there. For explicit outputs, the
   * output key is the name of the attribute under which that output appears. For Starlark-defined
   * implicit outputs, the output key is determined by the dict returned from the Starlark function.
   * Native-defined implicit outputs are not named in this manner, and so are invisible to {@code
   * ctx.outputs} and use the empty string key. (It'd be pathological for the empty string to be
   * used as a key in the other two cases, but this class makes no attempt to prohibit that.)
   */
  final String getOutputKey() {
    return outputKey;
  }

  abstract boolean isImplicit();

  /** Returns the target kind for all output files. */
  public static String targetKind() {
    return "generated file";
  }

  @Override
  public TargetData reduceForSerialization() {
    return new OutputFileData(getLocation(), getLabel(), generatingRule.reduceForSerialization());
  }

  private static final class Implicit extends OutputFile {

    Implicit(Label label, Rule generatingRule, String outputKey) {
      super(label, generatingRule, outputKey);
    }

    @Override
    boolean isImplicit() {
      return true;
    }
  }

  private static final class Explicit extends OutputFile {

    Explicit(Label label, Rule generatingRule, String attrName) {
      super(label, generatingRule, attrName);
    }

    @Override
    boolean isImplicit() {
      return false;
    }
  }

  private static class OutputFileData implements TargetData {
    private final Location location;
    private final Label label;
    // TODO(b/297857068): ensure this is not duplicated on deserialization.
    private final TargetData generatingRuleData;

    private OutputFileData(Location location, Label label, TargetData generatingRuleData) {
      this.location = location;
      this.label = label;
      this.generatingRuleData = generatingRuleData;
    }

    @Override
    public String getTargetKind() {
      return targetKind();
    }

    @Override
    public Location getLocation() {
      return location;
    }

    @Override
    public Label getLabel() {
      return label;
    }

    @Override
    public boolean isFile() {
      return true;
    }

    @Override
    public boolean isOutputFile() {
      return true;
    }

    @Override
    public Label getGeneratingRuleLabel() {
      return generatingRuleData.getLabel();
    }

    @Override
    public String getDeprecationWarning() {
      return generatingRuleData.getDeprecationWarning();
    }

    @Override
    public boolean isTestOnly() {
      return generatingRuleData.isTestOnly();
    }

    @Override
    public final boolean satisfies(RequiredProviders required) {
      return generatingRuleData.satisfies(required);
    }

    @Override
    public TestTimeout getTestTimeout() {
      return generatingRuleData.getTestTimeout();
    }

    @Override
    public AdvertisedProviderSet getAdvertisedProviders() {
      return generatingRuleData.getAdvertisedProviders();
    }
  }
}
