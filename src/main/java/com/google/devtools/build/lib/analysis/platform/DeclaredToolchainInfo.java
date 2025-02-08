// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.platform;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import javax.annotation.Nullable;

/**
 * Provider for a toolchain declaration, which associates a toolchain type, the execution and target
 * constraints, and the actual toolchain label. The toolchain is then available for use but will be
 * lazily resolved only when it is actually needed for toolchain-aware rules. Toolchain definitions
 * are exposed to Starlark and Bazel via {@link ToolchainInfo} providers.
 *
 * @param toolchainType The type of the toolchain being declared. This will be a label of a
 *     toolchain_type() target.
 * @param execConstraints The constraints describing the execution environment.
 * @param targetConstraints The constraints describing the target environment.
 * @param targetSettings The setting, that target build configuration needs to satisfy.
 * @param targetLabel The label of the {@code toolchain} target itself.
 * @param resolvedToolchainLabel The label of the toolchain to resolve for use in toolchain-aware
 *     rules.
 */
@AutoCodec
public record DeclaredToolchainInfo(
    ToolchainTypeInfo toolchainType,
    ConstraintCollection execConstraints,
    ConstraintCollection targetConstraints,
    ImmutableList<ConfigMatchingProvider> targetSettings,
    Label targetLabel,
    Label resolvedToolchainLabel)
    implements TransitiveInfoProvider {
  public DeclaredToolchainInfo {
    requireNonNull(toolchainType, "toolchainType");
    requireNonNull(execConstraints, "execConstraints");
    requireNonNull(targetConstraints, "targetConstraints");
    requireNonNull(targetSettings, "targetSettings");
    requireNonNull(targetLabel, "targetLabel");
    requireNonNull(resolvedToolchainLabel, "resolvedToolchainLabel");
  }

  /** Builder class to assist in creating {@link DeclaredToolchainInfo} instances. */
  public static class Builder {
    private ToolchainTypeInfo toolchainType;
    private final ConstraintCollection.Builder execConstraints = ConstraintCollection.builder();
    private final ConstraintCollection.Builder targetConstraints = ConstraintCollection.builder();
    private final ImmutableList.Builder<ConfigMatchingProvider> targetSettings =
        new ImmutableList.Builder<>();
    private Label targetLabel;
    private Label resolvedToolchainLabel;

    /** Sets the type of the toolchain being declared. */
    @CanIgnoreReturnValue
    public Builder toolchainType(ToolchainTypeInfo toolchainType) {
      this.toolchainType = toolchainType;
      return this;
    }

    /** Adds constraints describing the execution environment. */
    @CanIgnoreReturnValue
    public Builder addExecConstraints(Iterable<ConstraintValueInfo> constraints) {
      this.execConstraints.addConstraints(constraints);
      return this;
    }

    /** Adds constraints describing the execution environment. */
    @CanIgnoreReturnValue
    public Builder addExecConstraints(ConstraintValueInfo... constraints) {
      return addExecConstraints(ImmutableList.copyOf(constraints));
    }

    /** Adds constraints describing the target environment. */
    @CanIgnoreReturnValue
    public Builder addTargetConstraints(Iterable<ConstraintValueInfo> constraints) {
      this.targetConstraints.addConstraints(constraints);
      return this;
    }

    /** Adds constraints describing the target environment. */
    @CanIgnoreReturnValue
    public Builder addTargetConstraints(ConstraintValueInfo... constraints) {
      return addTargetConstraints(ImmutableList.copyOf(constraints));
    }

    @CanIgnoreReturnValue
    public Builder addTargetSettings(Iterable<ConfigMatchingProvider> targetSettings) {
      this.targetSettings.addAll(targetSettings);
      return this;
    }

    /** Sets the label of the {@code toolchain} target itself. */
    @CanIgnoreReturnValue
    public Builder targetLabel(Label targetLabel) {
      this.targetLabel = targetLabel;
      return this;
    }

    /** Sets the label of the toolchain to resolve for use in toolchain-aware rules. */
    @CanIgnoreReturnValue
    public Builder resolvedToolchainLabel(Label resolvedToolchainLabel) {
      this.resolvedToolchainLabel = resolvedToolchainLabel;
      return this;
    }

    /** Returns the newly created {@link DeclaredToolchainInfo} instance. */
    public DeclaredToolchainInfo build() throws DuplicateConstraintException {
      // Handle constraint duplication in attributes separately, so they can be reported correctly.
      ConstraintCollection.DuplicateConstraintException execConstraintsException = null;
      ConstraintCollection execConstraints;
      try {
        execConstraints = this.execConstraints.build();
      } catch (ConstraintCollection.DuplicateConstraintException e) {
        execConstraints = null;
        execConstraintsException = e;
      }
      ConstraintCollection.DuplicateConstraintException targetConstraintsException = null;
      ConstraintCollection targetConstraints;
      try {
        targetConstraints = this.targetConstraints.build();
      } catch (ConstraintCollection.DuplicateConstraintException e) {
        targetConstraints = null;
        targetConstraintsException = e;
      }
      if (execConstraintsException != null || targetConstraintsException != null) {
        throw new DuplicateConstraintException(
            execConstraintsException, targetConstraintsException);
      }
      return new DeclaredToolchainInfo(
          toolchainType,
          execConstraints,
          targetConstraints,
          targetSettings.build(),
          targetLabel,
          resolvedToolchainLabel);
    }
  }

  /** Returns a new {@link Builder} for creating {@link DeclaredToolchainInfo} instances. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Exception for reporting duplicated constraints from declared toolchains.
   *
   * <p>Contains distinct fields for errors from the execution constraints or target constraints, so
   * that these can be reported separately.
   */
  public static class DuplicateConstraintException extends Exception {
    @Nullable
    private final ConstraintCollection.DuplicateConstraintException execConstraintsException;

    @Nullable
    private final ConstraintCollection.DuplicateConstraintException targetConstraintsException;

    private DuplicateConstraintException(
        @Nullable ConstraintCollection.DuplicateConstraintException execConstraintsException,
        @Nullable ConstraintCollection.DuplicateConstraintException targetConstraintsException) {
      // At least one should be non-null.
      super(formatError(execConstraintsException, targetConstraintsException));
      this.execConstraintsException = execConstraintsException;
      this.targetConstraintsException = targetConstraintsException;
    }

    public ConstraintCollection.DuplicateConstraintException execConstraintsException() {
      return execConstraintsException;
    }

    public ConstraintCollection.DuplicateConstraintException targetConstraintsException() {
      return targetConstraintsException;
    }

    public static String formatError(
        @Nullable ConstraintCollection.DuplicateConstraintException execConstraintsException,
        @Nullable ConstraintCollection.DuplicateConstraintException targetConstraintsException) {
      StringBuilder message = new StringBuilder();
      message.append("Duplicate constraints detected[");
      if (execConstraintsException != null) {
        message.append("in execution constraints: ").append(execConstraintsException.getMessage());
      }
      if (targetConstraintsException != null) {
        if (execConstraintsException != null) {
          message.append(", ");
        }
        message.append("in target constraints: ").append(targetConstraintsException.getMessage());
      }
      message.append("]");
      return message.toString();
    }
  }
}
