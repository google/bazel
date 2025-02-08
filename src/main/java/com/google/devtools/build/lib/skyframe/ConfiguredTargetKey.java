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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.util.HashCodes.hashObjects;

import com.google.common.base.MoreObjects;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.Keep;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * In simple form, a ({@link Label}, {@link BuildConfigurationValue}) pair used to trigger immediate
 * dependency resolution and the rule analysis.
 *
 * <p>In practice, a ({@link Label} and post-transition {@link BuildConfigurationKey}) pair plus a
 * possible execution platform override {@link Label} with special constraints described as follows.
 *
 * <p>A build should not request keys with equal ({@link Label}, {@link BuildConfigurationValue})
 * pairs but different execution platform override {@link Label} if the invoked rule will register
 * actions. (This is potentially OK if all outputs of all registered actions incorporate the
 * execution platform in their name unless the build also requests keys without an override that
 * happen to resolve to the same execution platform.) In practice, this issue has not been seen in
 * any 'real' builds; however, pathologically failure could lead to multiple (potentially different)
 * ConfiguredTarget that have the same ({@link Label}, {@link BuildConfigurationValue}) pair.
 *
 * <p>Note that this key may be used to look up the generating action of an artifact.
 *
 * <p>TODO(blaze-configurability-team): Consider just using BuildOptions over a
 * BuildConfigurationKey.
 */
public class ConfiguredTargetKey implements ActionLookupKey {
  /**
   * Cache so that the number of ConfiguredTargetKey instances is {@code O(configured targets)} and
   * not {@code O(edges between configured targets)}.
   */
  private static final SkyKey.SkyKeyInterner<ConfiguredTargetKey> interner = SkyKey.newInterner();

  private final Label label;
  @Nullable private final BuildConfigurationKey configurationKey;
  private final int hashCode;

  private ConfiguredTargetKey(
      Label label, @Nullable BuildConfigurationKey configurationKey, int hashCode) {
    this.label = label;
    this.configurationKey = configurationKey;
    this.hashCode = hashCode;
  }

  @Override
  public final SkyFunctionName functionName() {
    return SkyFunctions.CONFIGURED_TARGET;
  }

  @Override
  public SkyKeyInterner<?> getSkyKeyInterner() {
    return interner;
  }

  @Override
  public Label getLabel() {
    return label;
  }

  @Nullable
  @Override
  public final BuildConfigurationKey getConfigurationKey() {
    return configurationKey;
  }

  @Nullable
  public Label getExecutionPlatformLabel() {
    return null;
  }

  /**
   * True if the target's rule transition should be applied.
   *
   * <p>True by default but set false when a non-idempotent rule transition is detected. It prevents
   * over-application of such transitions.
   */
  public boolean shouldApplyRuleTransition() {
    return true;
  }

  public final String prettyPrint() {
    if (getLabel() == null) {
      return "null";
    }
    return String.format("%s (%s)", getLabel(), formatConfigurationKey(configurationKey));
  }

  @Override
  public final int hashCode() {
    return hashCode;
  }

  @Override
  public final boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ConfiguredTargetKey other)) {
      return false;
    }
    return hashCode == other.hashCode
        && getLabel().equals(other.getLabel())
        && Objects.equals(configurationKey, other.configurationKey)
        && Objects.equals(getExecutionPlatformLabel(), other.getExecutionPlatformLabel())
        && shouldApplyRuleTransition() == other.shouldApplyRuleTransition();
  }

  @Override
  public final String toString() {
    // TODO(b/162809183): consider reverting to less verbose toString when bug is resolved.
    MoreObjects.ToStringHelper helper =
        MoreObjects.toStringHelper(this).add("label", getLabel()).add("config", configurationKey);
    if (getExecutionPlatformLabel() != null) {
      helper.add("executionPlatformLabel", getExecutionPlatformLabel());
    }
    return helper.toString();
  }

  /**
   * Key indicating that no rule transition should be applied to the configuration.
   *
   * <p>NOTE: although it's true that no rule transition is applied when there is a null
   * configuration, this key type is used to handle a special edge case described below. It should
   * only be used with a non-null configuration.
   *
   * <p>When a non-noop rule transition occurs, it creates a new <i>delegation</i> {@link
   * ConfiguredTargetKey} with the resulting configuration. This is so if different starting
   * configurations result in the same configuration after transition, they converge on the same
   * key-value entry in Skyframe.
   *
   * <p>This can be problematic when transitions are not idempotent because evaluation of the
   * <i>delegate</i> repeats the transition, resulting in a another <i>delegate</i>. In cases of
   * non-convergent transitions, this may lead to infinite expansion.
   *
   * <p>To ensure that transitions are effectively only applied once, prior to delegation, the
   * {@link ConfiguredTargetFunction} applies the transition a second time to check it for
   * idempotency. It sets {@link ConfiguredTargetKey#shouldApplyRuleTransition} false when it is not
   * idempotent.
   */
  private static final class ConfiguredTargetKeyWithFinalConfiguration extends ConfiguredTargetKey {
    // This is implemented using subtypes instead of adding a boolean field to `ConfiguredTargetKey`
    // to reduce memory cost.

    private ConfiguredTargetKeyWithFinalConfiguration(
        Label label, BuildConfigurationKey configurationKey, int hashCode) {
      super(label, checkNotNull(configurationKey), hashCode);
    }

    @Override
    public boolean shouldApplyRuleTransition() {
      return false;
    }
  }

  private static class ToolchainDependencyConfiguredTargetKey extends ConfiguredTargetKey {
    private final Label executionPlatformLabel;

    private ToolchainDependencyConfiguredTargetKey(
        Label label,
        @Nullable BuildConfigurationKey configurationKey,
        int hashCode,
        Label executionPlatformLabel) {
      super(label, configurationKey, hashCode);
      this.executionPlatformLabel = checkNotNull(executionPlatformLabel);
    }

    @Override
    public Label getExecutionPlatformLabel() {
      return executionPlatformLabel;
    }
  }

  private static final class ToolchainDependencyConfiguredTargetKeyWithFinalConfiguration
      extends ToolchainDependencyConfiguredTargetKey {
    private ToolchainDependencyConfiguredTargetKeyWithFinalConfiguration(
        Label label,
        BuildConfigurationKey configurationKey,
        int hashCode,
        Label executionPlatformLabel) {
      super(label, checkNotNull(configurationKey), hashCode, executionPlatformLabel);
    }

    @Override
    public boolean shouldApplyRuleTransition() {
      return false;
    }
  }

  public Builder toBuilder() {
    return builder()
        .setConfigurationKey(configurationKey)
        .setLabel(getLabel())
        .setExecutionPlatformLabel(getExecutionPlatformLabel())
        .setShouldApplyRuleTransition(shouldApplyRuleTransition());
  }

  /** Returns a new {@link Builder} to create instances of {@link ConfiguredTargetKey}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the {@link ConfiguredTargetKey} that owns {@code configuredTarget}. */
  public static ConfiguredTargetKey fromConfiguredTarget(CqueryNode configuredTarget) {
    // If configuredTarget is a MergedConfiguredTarget unwraps it first. MergedConfiguredTarget is
    // ephemeral and does not have a directly corresponding entry in Skyframe.
    //
    // The cast exists because the key passes through parts of analysis that work on both aspects
    // and configured targets. This process discards the key's specific type information.
    return (ConfiguredTargetKey) configuredTarget.unwrapIfMerged().getLookupKey();
  }

  /** A helper class to create instances of {@link ConfiguredTargetKey}. */
  public static final class Builder
      implements DeferredObjectCodec.DeferredValue<ConfiguredTargetKey> {
    private Label label = null;
    private BuildConfigurationKey configurationKey = null;
    private Label executionPlatformLabel = null;
    private boolean shouldApplyRuleTransition = true;

    private Builder() {}

    /** Sets the label for the target. */
    @CanIgnoreReturnValue
    public Builder setLabel(Label label) {
      this.label = label;
      return this;
    }

    /** Sets the {@link BuildConfigurationValue} for the configured target. */
    @CanIgnoreReturnValue
    public Builder setConfiguration(@Nullable BuildConfigurationValue buildConfiguration) {
      return setConfigurationKey(buildConfiguration == null ? null : buildConfiguration.getKey());
    }

    /** Sets the configuration key for the configured target. */
    @CanIgnoreReturnValue
    public Builder setConfigurationKey(@Nullable BuildConfigurationKey configurationKey) {
      this.configurationKey = configurationKey;
      return this;
    }

    /**
     * Sets the execution platform {@link Label} this configured target should use for toolchain
     * resolution. When present, this overrides the normally determined execution platform.
     */
    @CanIgnoreReturnValue
    public Builder setExecutionPlatformLabel(@Nullable Label executionPlatformLabel) {
      this.executionPlatformLabel = executionPlatformLabel;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setShouldApplyRuleTransition(boolean shouldApplyRuleTransition) {
      this.shouldApplyRuleTransition = shouldApplyRuleTransition;
      return this;
    }

    /** Builds a new {@link ConfiguredTargetKey} based on the supplied data. */
    public ConfiguredTargetKey build() {
      int hashCode =
          computeHashCode(
              label, configurationKey, executionPlatformLabel, shouldApplyRuleTransition);
      ConfiguredTargetKey newKey;
      if (executionPlatformLabel == null) {
        newKey =
            shouldApplyRuleTransition
                ? new ConfiguredTargetKey(label, configurationKey, hashCode)
                : new ConfiguredTargetKeyWithFinalConfiguration(label, configurationKey, hashCode);
      } else {
        newKey =
            shouldApplyRuleTransition
                ? new ToolchainDependencyConfiguredTargetKey(
                    label, configurationKey, hashCode, executionPlatformLabel)
                : new ToolchainDependencyConfiguredTargetKeyWithFinalConfiguration(
                    label, configurationKey, hashCode, executionPlatformLabel);
      }
      return interner.intern(newKey);
    }

    /** Implements the {@link DeferredObjectCodec.DeferredValue} used for deserialization. */
    @Override
    public ConfiguredTargetKey call() {
      return build();
    }
  }

  private static int computeHashCode(
      Label label,
      @Nullable BuildConfigurationKey configurationKey,
      @Nullable Label executionPlatformLabel,
      boolean shouldApplyRuleTransition) {
    int hashCode = hashObjects(label, configurationKey, executionPlatformLabel);
    if (!shouldApplyRuleTransition) {
      hashCode = ~hashCode;
    }
    return hashCode;
  }

  private static String formatConfigurationKey(@Nullable BuildConfigurationKey key) {
    if (key == null) {
      return "null";
    }
    return key.getOptions().checksum();
  }

  public static ConfiguredTargetKeyValueSharingCodec valueSharingCodec() {
    return ConfiguredTargetKeyValueSharingCodec.INSTANCE;
  }

  private static class ConfiguredTargetKeyValueSharingCodec
      extends DeferredObjectCodec<ConfiguredTargetKey> {

    private static final byte LABEL_MASK = (byte) 0b1000;
    private static final byte CONFIGURATION_KEY_MASK = (byte) 0b0100;
    private static final byte EXECUTION_PLATFORM_MASK = (byte) 0b0010;
    private static final byte SHOULD_APPLY_RULE_TRANSITION_MASK = (byte) 0b0001;

    private static final ConfiguredTargetKeyValueSharingCodec INSTANCE =
        new ConfiguredTargetKeyValueSharingCodec();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<ConfiguredTargetKey> getEncodedClass() {
      return ConfiguredTargetKey.class;
    }

    @Override
    public void serialize(
        SerializationContext context, ConfiguredTargetKey key, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      Label label = key.getLabel();
      BuildConfigurationKey configurationKey = key.getConfigurationKey();
      Label executionPlatformLabel = key.getExecutionPlatformLabel();
      // This is an int because Java converts bytes to ints when performing binary bitwise
      // operations, but it's really only a byte.
      int presenceMask =
          ((label != null ? LABEL_MASK : (byte) 0)
              | (configurationKey != null ? CONFIGURATION_KEY_MASK : (byte) 0)
              | (executionPlatformLabel != null ? EXECUTION_PLATFORM_MASK : (byte) 0)
              | (key.shouldApplyRuleTransition() ? SHOULD_APPLY_RULE_TRANSITION_MASK : (byte) 0));
      codedOut.writeRawByte((byte) presenceMask);

      if (label != null) {
        context.putSharedValue(label, /* distinguisher= */ null, Label.deferredCodec(), codedOut);
      }
      if (configurationKey != null) {
        context.putSharedValue(
            configurationKey, /* distinguisher= */ null, BuildConfigurationKey.codec(), codedOut);
      }
      if (executionPlatformLabel != null) {
        context.putSharedValue(
            executionPlatformLabel, /* distinguisher= */ null, Label.deferredCodec(), codedOut);
      }
    }

    @Override
    public DeferredValue<ConfiguredTargetKey> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      byte presenceMask = codedIn.readRawByte();
      var builder = builder();
      if ((presenceMask & LABEL_MASK) != 0) {
        context.getSharedValue(
            codedIn,
            /* distinguisher= */ null,
            Label.deferredCodec(),
            builder,
            ConfiguredTargetKeyCodec::setLabel);
      }
      if ((presenceMask & CONFIGURATION_KEY_MASK) != 0) {
        context.getSharedValue(
            codedIn,
            /* distinguisher= */ null,
            BuildConfigurationKey.codec(),
            builder,
            ConfiguredTargetKeyCodec::setConfigurationKey);
      }
      if ((presenceMask & EXECUTION_PLATFORM_MASK) != 0) {
        context.getSharedValue(
            codedIn,
            /* distinguisher= */ null,
            Label.deferredCodec(),
            builder,
            ConfiguredTargetKeyCodec::setExecutionPlatformLabel);
      }
      return builder.setShouldApplyRuleTransition(
          (presenceMask & SHOULD_APPLY_RULE_TRANSITION_MASK) != 0);
    }
  }

  /** Codec for all {@link ConfiguredTargetKey} subtypes. */
  @Keep
  private static class ConfiguredTargetKeyCodec extends DeferredObjectCodec<ConfiguredTargetKey> {
    @Override
    public Class<ConfiguredTargetKey> getEncodedClass() {
      return ConfiguredTargetKey.class;
    }

    @Override
    public void serialize(
        SerializationContext context, ConfiguredTargetKey key, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      context.serialize(key.getLabel(), codedOut);
      context.serialize(key.getConfigurationKey(), codedOut);
      context.serialize(key.getExecutionPlatformLabel(), codedOut);
      codedOut.writeBoolNoTag(key.shouldApplyRuleTransition());
    }

    @Override
    public DeferredValue<ConfiguredTargetKey> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      Builder builder = builder();
      context.deserialize(codedIn, builder, ConfiguredTargetKeyCodec::setLabel);
      context.deserialize(codedIn, builder, ConfiguredTargetKeyCodec::setConfigurationKey);
      context.deserialize(codedIn, builder, ConfiguredTargetKeyCodec::setExecutionPlatformLabel);
      return builder.setShouldApplyRuleTransition(codedIn.readBool());
    }

    private static final void setLabel(Builder builder, Object value) {
      builder.setLabel((Label) value);
    }

    private static final void setConfigurationKey(Builder builder, Object value) {
      builder.setConfigurationKey((BuildConfigurationKey) value);
    }

    private static final void setExecutionPlatformLabel(Builder builder, Object value) {
      builder.setExecutionPlatformLabel((Label) value);
    }
  }
}
