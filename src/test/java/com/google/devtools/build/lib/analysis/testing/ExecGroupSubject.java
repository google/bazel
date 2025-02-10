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
package com.google.devtools.build.lib.analysis.testing;

import static com.google.common.truth.Truth.assertAbout;

import com.google.common.truth.FailureMetadata;
import com.google.common.truth.IterableSubject;
import com.google.common.truth.Subject;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.ExecGroup;
import java.util.stream.Collectors;

/** A Truth {@link Subject} for {@link ExecGroup}. */
public class ExecGroupSubject extends Subject {
  // Static data.

  /** Entry point for test assertions related to {@link ExecGroup}. */
  public static ExecGroupSubject assertThat(ExecGroup execGroup) {
    return assertAbout(ExecGroupSubject::new).that(execGroup);
  }

  // Instance fields.

  private final ExecGroup actual;

  protected ExecGroupSubject(FailureMetadata failureMetadata, ExecGroup subject) {
    super(failureMetadata, subject);
    this.actual = subject;
  }

  public ToolchainTypeRequirementSubject toolchainType(String toolchainTypeLabel) {
    return toolchainType(Label.parseCanonicalUnchecked(toolchainTypeLabel));
  }

  public ToolchainTypeRequirementSubject toolchainType(Label toolchainType) {
    return check("toolchainType(%s)", toolchainType)
        .about(ToolchainTypeRequirementSubject.toolchainTypeRequirements())
        .that(actual.toolchainType(toolchainType));
  }

  public void hasToolchainType(String toolchainTypeLabel) {
    toolchainType(toolchainTypeLabel).isNotNull();
  }

  public void hasToolchainType(Label toolchainType) {
    toolchainType(toolchainType).isNotNull();
  }

  public IterableSubject execCompatibleWith() {
    return check("execCompatibleWith()")
        .that(actual.execCompatibleWith().stream().collect(Collectors.toList()));
  }

  public void hasExecCompatibleWith(String constraintLabel) {
    hasExecCompatibleWith(Label.parseCanonicalUnchecked(constraintLabel));
  }

  public void hasExecCompatibleWith(Label constraintLabel) {
    execCompatibleWith().contains(constraintLabel);
  }

  public void copiesFromDefault() {
    check("copyFromDefault()").that(actual.copyFromDefault()).isTrue();
  }
}
