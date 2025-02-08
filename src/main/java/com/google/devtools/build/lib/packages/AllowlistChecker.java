// Copyright 2021 The Bazel Authors. All rights reserved.
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

import static java.util.Objects.requireNonNull;

import com.google.auto.value.AutoBuilder;
import javax.annotation.Nullable;

/**
 * Set of classes for RuleClass to use to track how an Allowlist must be checked.
 *
 * <p>This class is used at both loading time in packages an thus cannot actually refer to {@link
 * Allowlist}. These are processed in {@link RuleConfiguredTargetBuilder}.
 *
 * @param allowlistAttr Return attribute name containing the allowlist to check against.
 * @param errorMessage Return error message to print if allowlist check fails.
 * @param locationCheck Return what rule location to check against allowlist.
 * @param attributeSetTrigger If non-null, check that the attribute is explicitly set before
 *     checking allowlist.
 */
public record AllowlistChecker(
    String allowlistAttr,
    String errorMessage,
    LocationCheck locationCheck,
    @Nullable String attributeSetTrigger) {
  public AllowlistChecker {
    requireNonNull(allowlistAttr, "allowlistAttr");
    requireNonNull(errorMessage, "errorMessage");
    requireNonNull(locationCheck, "locationCheck");
  }

  /** Track whether checking rule instance or rule definition location */
  public enum LocationCheck {
    INSTANCE, // pass if rule instance in allowlist
    DEFINITION, // pass if rule definition in allowlist
    INSTANCE_OR_DEFINITION // pass if either in allowlist
  }

  public static Builder builder() {
    return new AutoBuilder_AllowlistChecker_Builder();
  }

  /** Standard builder class. */
  @AutoBuilder
  public abstract static class Builder {
    public abstract Builder setAllowlistAttr(String allowlistAttr);

    public abstract Builder setErrorMessage(String errorMessage);

    public abstract Builder setAttributeSetTrigger(String attributeSetTrigger);

    public abstract Builder setLocationCheck(LocationCheck locationCheck);

    public abstract AllowlistChecker build();
  }
}
