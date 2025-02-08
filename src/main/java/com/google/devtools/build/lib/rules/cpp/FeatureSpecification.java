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
package com.google.devtools.build.lib.rules.cpp;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;

/** Object encapsulating which additional toolchain features should be enabled and/or disabled. */
public record FeatureSpecification(
    ImmutableSet<String> requestedFeatures, ImmutableSet<String> unsupportedFeatures) {
  public FeatureSpecification {
    requireNonNull(requestedFeatures, "requestedFeatures");
    requireNonNull(unsupportedFeatures, "unsupportedFeatures");
  }

  public static final FeatureSpecification EMPTY =
      create(ImmutableSet.<String>of(), ImmutableSet.<String>of());

  public static final FeatureSpecification create(
      ImmutableSet<String> requestedFeatures, ImmutableSet<String> unsupportedFeatures) {
    return new FeatureSpecification(requestedFeatures, unsupportedFeatures);
  }

}
