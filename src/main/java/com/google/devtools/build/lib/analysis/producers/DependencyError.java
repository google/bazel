// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers;

import com.google.auto.value.AutoOneOf;
import com.google.devtools.build.lib.analysis.InvalidVisibilityDependencyException;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.TransitionCreationException;
import com.google.devtools.build.lib.analysis.producers.DependencyMapProducer.MaterializerException;
import com.google.devtools.build.lib.analysis.starlark.StarlarkTransition.TransitionException;
import com.google.devtools.build.lib.skyframe.AspectCreationException;
import com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.BuildOptionsScopeFunctionException;
import com.google.devtools.build.lib.skyframe.config.PlatformMappingException;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.common.options.OptionsParsingException;

/** Tagged union of exceptions thrown by {@link DependencyProducer}. */
@AutoOneOf(DependencyError.Kind.class)
public abstract class DependencyError {
  /**
   * Tags for different error types.
   *
   * <p>The earlier in this list, the higher the priority when there are multiple errors. See {@link
   * #isSecondErrorMoreImportant}.
   */
  public enum Kind {
    DEPENDENCY_OPTIONS_PARSING,
    DEPENDENCY_TRANSITION,
    MATERIALIZER,
    INVALID_VISIBILITY,
    /** An error occurred either computing the aspect collection or merging the aspect values. */
    ASPECT_EVALUATION,
    /** An error occurred during evaluation of the aspect using Skyframe. */
    ASPECT_CREATION,
    /** An error occurred during evaluation of platform mappings. */
    PLATFORM_MAPPING,
    /** An error occurred while looking up the target platform. */
    INVALID_PLATFORM,
    /** An error occurred while creating a transition. */
    TRANSITION_CREATION,
    /** An error occurred during evaluation of build options scopes. */
    BUILD_OPTIONS_SCOPE,
  }

  public abstract Kind kind();

  public abstract OptionsParsingException dependencyOptionsParsing();

  public abstract TransitionException dependencyTransition();

  public abstract MaterializerException materializer();

  public abstract InvalidVisibilityDependencyException invalidVisibility();

  public abstract DependencyEvaluationException aspectEvaluation();

  public abstract AspectCreationException aspectCreation();

  public abstract PlatformMappingException platformMapping();

  public abstract InvalidPlatformException invalidPlatform();

  public abstract TransitionCreationException transitionCreation();

  public abstract BuildOptionsScopeFunctionException buildOptionsScope();

  public static boolean isSecondErrorMoreImportant(DependencyError first, DependencyError second) {
    // There isn't a good way to prioritize when the type matches, so we just keep the first.
    return first.kind().compareTo(second.kind()) > 0;
  }

  public Exception getException() {
    return switch (kind()) {
      case DEPENDENCY_OPTIONS_PARSING -> dependencyOptionsParsing();
      case DEPENDENCY_TRANSITION -> dependencyTransition();
      case MATERIALIZER -> materializer();
      case INVALID_VISIBILITY -> invalidVisibility();
      case ASPECT_EVALUATION -> aspectEvaluation();
      case ASPECT_CREATION -> aspectCreation();
      case PLATFORM_MAPPING -> platformMapping();
      case INVALID_PLATFORM -> invalidPlatform();
      case TRANSITION_CREATION -> transitionCreation();
      case BUILD_OPTIONS_SCOPE -> buildOptionsScope();
    };
  }

  static DependencyError of(TransitionException e) {
    return AutoOneOf_DependencyError.dependencyTransition(e);
  }

  static DependencyError of(OptionsParsingException e) {
    return AutoOneOf_DependencyError.dependencyOptionsParsing(e);
  }

  static DependencyError of(MaterializerException e) {
    return AutoOneOf_DependencyError.materializer(e);
  }

  static DependencyError of(InvalidVisibilityDependencyException e) {
    return AutoOneOf_DependencyError.invalidVisibility(e);
  }

  static DependencyError of(DependencyEvaluationException e) {
    return AutoOneOf_DependencyError.aspectEvaluation(e);
  }

  static DependencyError of(AspectCreationException e) {
    return AutoOneOf_DependencyError.aspectCreation(e);
  }

  static DependencyError of(PlatformMappingException e) {
    return AutoOneOf_DependencyError.platformMapping(e);
  }

  static DependencyError of(InvalidPlatformException e) {
    return AutoOneOf_DependencyError.invalidPlatform(e);
  }

  static DependencyError of(TransitionCreationException e) {
    return AutoOneOf_DependencyError.transitionCreation(e);
  }

  static DependencyError of(BuildOptionsScopeFunctionException e) {
    return AutoOneOf_DependencyError.buildOptionsScope(e);
  }
}
