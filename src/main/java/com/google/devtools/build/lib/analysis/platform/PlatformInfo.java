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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Strings;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.platform.PlatformInfoApi;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import net.starlark.java.eval.Printer;
import net.starlark.java.syntax.Location;

/** Provider for a platform, which is a group of constraints and values. */
@Immutable
public class PlatformInfo extends NativeInfo
    implements PlatformInfoApi<ConstraintSettingInfo, ConstraintValueInfo> {

  /**
   * The literal key that will be used to copy the {@link #remoteExecutionProperties} from the
   * parent {@link PlatformInfo} into a new {@link PlatformInfo}'s {@link
   * #remoteExecutionProperties}.
   */
  public static final String PARENT_REMOTE_EXECUTION_KEY = "{PARENT_REMOTE_EXECUTION_PROPERTIES}";

  /** Name used in Starlark for accessing this provider. */
  public static final String STARLARK_NAME = "PlatformInfo";

  /**
   * Empty {@link PlatformInfo} instance for a invalid or empty (e.g. builtin) actions. See also
   * src/main/starlark/builtins_bzl/platforms/BUILD#empty
   */
  public static final PlatformInfo EMPTY_PLATFORM_INFO;

  static {
    try {
      EMPTY_PLATFORM_INFO =
          PlatformInfo.builder()
              .setLabel(Label.parseCanonicalUnchecked(PlatformConstants.INTERNAL_PLATFORM))
              .build();
    } catch (DuplicateConstraintException | ExecPropertiesException e) {
      // This can never happen since we're not passing any values to the builder.
      throw new VerifyException(e);
    }
  }

  /** Provider singleton constant. */
  public static final BuiltinProvider<PlatformInfo> PROVIDER =
      new BuiltinProvider<PlatformInfo>(STARLARK_NAME, PlatformInfo.class) {};

  private final Label label;
  private final ConstraintCollection constraints;
  private final String remoteExecutionProperties;

  /** execProperties will deprecate and replace remoteExecutionProperties */
  // TODO(blaze-configurability): If we want to remove remoteExecutionProperties, we need to fix
  // PlatformUtils.getPlatformProto to use the dict-typed execProperties and do a migration.
  private final PlatformProperties execProperties;

  private final ImmutableList<String> flags;

  private final ImmutableList<ConfigMatchingProvider> requiredSettings;

  private final boolean checkToolchainTypes;
  private final ImmutableList<Label> allowedToolchainTypes;

  private PlatformInfo(
      Label label,
      ConstraintCollection constraints,
      String remoteExecutionProperties,
      PlatformProperties execProperties,
      ImmutableList<String> flags,
      ImmutableList<ConfigMatchingProvider> requiredSettings,
      boolean checkToolchainTypes,
      ImmutableList<Label> allowedToolchainTypes,
      Location creationLocation) {
    super(creationLocation);
    this.label = label;
    this.constraints = constraints;
    this.remoteExecutionProperties = Strings.nullToEmpty(remoteExecutionProperties);
    this.execProperties = execProperties;
    this.flags = flags;
    this.requiredSettings = requiredSettings;
    this.checkToolchainTypes = checkToolchainTypes;
    this.allowedToolchainTypes = allowedToolchainTypes;
  }

  @Override
  public BuiltinProvider<PlatformInfo> getProvider() {
    return PROVIDER;
  }

  @Override
  public Label label() {
    return label;
  }

  @Override
  public ConstraintCollection constraints() {
    return constraints;
  }

  public String remoteExecutionProperties() {
    return remoteExecutionProperties;
  }

  public ImmutableMap<String, String> execProperties() {
    return execProperties.properties();
  }

  public ImmutableList<String> flags() {
    return flags;
  }

  public ImmutableList<ConfigMatchingProvider> requiredSettings() {
    return requiredSettings;
  }

  public boolean checkToolchainTypes() {
    return checkToolchainTypes;
  }

  public ImmutableList<Label> allowedToolchainTypes() {
    return allowedToolchainTypes;
  }

  @Override
  public void repr(Printer printer) {
    printer.append(String.format("PlatformInfo(%s, constraints=%s)", label, constraints));
  }

  /** Add this platform to the given fingerprint. */
  public void addTo(Fingerprint fp) {
    fp.addString(label.toString());
    constraints.addToFingerprint(fp);
    fp.addNullableString(remoteExecutionProperties);
    fp.addStringMap(execProperties.properties());
    fp.addStrings(flags);
    fp.addStrings(
        requiredSettings.stream()
            .map(ConfigMatchingProvider::label)
            .map(Label::toString)
            .collect(toImmutableList()));
    fp.addStrings(allowedToolchainTypes.stream().map(Label::toString).collect(toImmutableList()));
    fp.addBoolean(checkToolchainTypes);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof PlatformInfo that)) {
      return false;
    }
    return Objects.equals(label, that.label)
        && Objects.equals(constraints, that.constraints)
        && Objects.equals(remoteExecutionProperties, that.remoteExecutionProperties)
        && Objects.equals(execProperties, that.execProperties)
        && Objects.equals(flags, that.flags)
        && Objects.equals(requiredSettings, that.requiredSettings)
        && (checkToolchainTypes == that.checkToolchainTypes)
        && Objects.equals(allowedToolchainTypes, that.allowedToolchainTypes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        label,
        constraints,
        remoteExecutionProperties,
        execProperties,
        flags,
        requiredSettings,
        checkToolchainTypes,
        allowedToolchainTypes);
  }

  /** Returns a new {@link Builder} for creating a fresh {@link PlatformInfo} instance. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder class to facilitate creating valid {@link PlatformInfo} instances. */
  public static class Builder {

    @Nullable private PlatformInfo parent = null;
    private Label label;
    private final ConstraintCollection.Builder constraints = ConstraintCollection.builder();
    private String remoteExecutionProperties = null;
    private final PlatformProperties.Builder execPropertiesBuilder = PlatformProperties.builder();
    private final ImmutableList.Builder<String> flags = new ImmutableList.Builder<>();
    private final ImmutableList.Builder<ConfigMatchingProvider> requiredSettings =
        new ImmutableList.Builder<>();
    private boolean checkToolchainTypes = false;
    private final ImmutableList.Builder<Label> allowedToolchainTypes =
        new ImmutableList.Builder<>();
    private Location creationLocation = Location.BUILTIN;

    /**
     * Sets the parent {@link PlatformInfo} that this platform inherits from. Constraint values set
     * directly on this instance will be kept, but any other constraint settings will be found from
     * the parent, if set.
     *
     * @param parent the platform that is the parent of this platform
     * @return the {@link Builder} instance for method chaining
     */
    @CanIgnoreReturnValue
    public Builder setParent(@Nullable PlatformInfo parent) {
      this.parent = parent;
      if (parent == null) {
        this.constraints.parent(null);
        this.execPropertiesBuilder.setParent(null);
      } else {
        this.constraints.parent(parent.constraints);
        this.execPropertiesBuilder.setParent(parent.execProperties);
      }
      return this;
    }

    /**
     * Sets the {@link Label} for this {@link PlatformInfo}.
     *
     * @param label the label identifying this platform
     * @return the {@link Builder} instance for method chaining
     */
    @CanIgnoreReturnValue
    public Builder setLabel(Label label) {
      this.label = label;
      return this;
    }

    /**
     * Adds the given constraint value to the constraints that define this {@link PlatformInfo}.
     *
     * @param constraint the constraint to add
     * @return the {@link Builder} instance for method chaining
     */
    @CanIgnoreReturnValue
    public Builder addConstraint(ConstraintValueInfo constraint) {
      this.constraints.addConstraints(constraint);
      return this;
    }

    /**
     * Adds the given constraint values to the constraints that define this {@link PlatformInfo}.
     *
     * @param constraints the constraints to add
     * @return the {@link Builder} instance for method chaining
     */
    @CanIgnoreReturnValue
    public Builder addConstraints(Iterable<ConstraintValueInfo> constraints) {
      this.constraints.addConstraints(constraints);
      return this;
    }

    /**
     * Sets the data being sent to a potential remote executor. If there is a parent {@link
     * PlatformInfo} set, the literal string "{PARENT_REMOTE_EXECUTION_PROPERTIES}" will be replaced
     * by the {@link #remoteExecutionProperties} from that parent. Also if the parent is set, and
     * this instance's {@link #remoteExecutionProperties} is blank or unset, the parent's will be
     * used directly.
     *
     * <p>Specific examples:
     *
     * <ul>
     *   <li>parent.remoteExecutionProperties is unset: use the child's value
     *   <li>parent.remoteExecutionProperties is set, child.remoteExecutionProperties is unset: use
     *       the parent's value
     *   <li>parent.remoteExecutionProperties is set, child.remoteExecutionProperties is set, and
     *       does not contain {PARENT_REMOTE_EXECUTION_PROPERTIES}: use the child's value
     *   <li>parent.remoteExecutionProperties is set, child.remoteExecutionProperties is set, and
     *       does contain {PARENT_REMOTE_EXECUTION_PROPERTIES}: use the child's value, but
     *       substitute the parent's value for {PARENT_REMOTE_EXECUTION_PROPERTIES}
     * </ul>
     *
     * @param properties the properties to be added
     * @return the {@link Builder} instance for method chaining
     */
    @CanIgnoreReturnValue
    public Builder setRemoteExecutionProperties(String properties) {
      this.remoteExecutionProperties = properties;
      return this;
    }

    /**
     * Sets the execution properties.
     *
     * <p>If there is a parent {@link PlatformInfo} set, then all parent's properties will be
     * inherited. Any properties included in both will use the child's value. Use the value of empty
     * string to unset a property.
     */
    @CanIgnoreReturnValue
    public Builder setExecProperties(@Nullable ImmutableMap<String, String> properties) {
      this.execPropertiesBuilder.setProperties(properties);
      return this;
    }

    /** Add the given flags to this {@link PlatformInfo}. */
    @CanIgnoreReturnValue
    public Builder addFlags(Iterable<String> flags) {
      this.flags.addAll(flags);
      return this;
    }

    /** Add the given settings to this {@link PlatformInfo}. */
    @CanIgnoreReturnValue
    public Builder addRequiredSettings(List<ConfigMatchingProvider> requiredSettings) {
      this.requiredSettings.addAll(requiredSettings);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder checkToolchainTypes(boolean checkToolchainTypes) {
      this.checkToolchainTypes = checkToolchainTypes;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addAllowedToolchainTypes(List<Label> allowedToolchainTypes) {
      this.allowedToolchainTypes.addAll(allowedToolchainTypes);
      return this;
    }

    private static void checkRemoteExecutionProperties(
        PlatformInfo parent,
        String remoteExecutionProperties,
        ImmutableMap<String, String> execProperties)
        throws ExecPropertiesException {
      if (!execProperties.isEmpty() && !Strings.isNullOrEmpty(remoteExecutionProperties)) {
        throw new ExecPropertiesException(
            "Platform contains both remote_execution_properties and exec_properties. Prefer"
                + " exec_properties over the deprecated remote_execution_properties.");
      }
      if (!execProperties.isEmpty()
          && parent != null
          && !Strings.isNullOrEmpty(parent.remoteExecutionProperties())) {
        throw new ExecPropertiesException(
            "Platform specifies exec_properties but its parent "
                + parent.label()
                + " specifies remote_execution_properties. Prefer exec_properties over the"
                + " deprecated remote_execution_properties.");
      }
      if (!Strings.isNullOrEmpty(remoteExecutionProperties)
          && parent != null
          && !parent.execProperties().isEmpty()) {
        throw new ExecPropertiesException(
            "Platform specifies remote_execution_properties but its parent specifies"
                + " exec_properties. Prefer exec_properties over the deprecated"
                + " remote_execution_properties.");
      }
    }

    /**
     * Returns the new {@link PlatformInfo} instance.
     *
     * @throws DuplicateConstraintException if more than one constraint value exists for the same
     *     constraint setting
     */
    public PlatformInfo build() throws DuplicateConstraintException, ExecPropertiesException {
      checkRemoteExecutionProperties(
          this.parent, this.remoteExecutionProperties, execPropertiesBuilder.getProperties());

      // Merge the remote execution properties.
      String remoteExecutionProperties =
          mergeRemoteExecutionProperties(parent, this.remoteExecutionProperties);

      // Merge parent flags and this builder's flags. Parent flags always come first so that flags
      // from this builder will override or combine, depending on the flag type.
      ImmutableList.Builder<String> flagBuilder = new ImmutableList.Builder<>();
      if (this.parent != null) {
        flagBuilder.addAll(this.parent.flags);
      }
      flagBuilder.addAll(this.flags.build());

      // Required settings are explicitly **not** inherited from the parent, so do not merge.
      ImmutableList<ConfigMatchingProvider> settings = requiredSettings.build();

      return new PlatformInfo(
          label,
          constraints.build(),
          remoteExecutionProperties,
          execPropertiesBuilder.build(),
          flagBuilder.build(),
          settings,
          checkToolchainTypes,
          allowedToolchainTypes.build(),
          creationLocation);
    }

    private static String mergeRemoteExecutionProperties(
        PlatformInfo parent, String remoteExecutionProperties) {
      String parentRemoteExecutionProperties = "";
      if (parent != null) {
        parentRemoteExecutionProperties = parent.remoteExecutionProperties();
      }

      if (remoteExecutionProperties == null) {
        return parentRemoteExecutionProperties;
      }

      return StringUtilities.replaceAllLiteral(
          remoteExecutionProperties, PARENT_REMOTE_EXECUTION_KEY, parentRemoteExecutionProperties);
    }
  }

  /** Exception that indicates something is wrong in exec_properties configuration. */
  public static class ExecPropertiesException extends Exception {
    ExecPropertiesException(String message) {
      super(message);
    }
  }
}
