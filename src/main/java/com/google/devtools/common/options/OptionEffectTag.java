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
package com.google.devtools.common.options;

// TODO(bazel-team) - remove the transition-period waive of compatibility requirements.
/**
 * These tags should describe the intent and effects of an option.
 *
 * <p>These will be used for filtering noise in long and complex command-lines, to help provide an
 * overview of which options were likely to have an effect on an issue. This should help reproduce
 * both working or broken behavior, as we want this to be useful both for debugging and for
 * avoiding users blindly copying command lines. For this reason, even experimental and undocumented
 * flags should list their effects. All flags have at least one effect (if not, NO_OP is provided)
 * so all @Options will require at least one value.
 *
 * <p>This file must be kept in sync with the matching proto. The information is duplicated to keep
 * the proto dependency out of users of this options library.
 *
 * <p>IMPORTANT NOTE**: Changing this enum has specific compatibility requirements:
 *
 * <p>These tags are used for flag filtering, so are consumed by tools that process bazel's
 * output, and for this reason must be kept backwards compatible until the build horizon has passed.
 *
 * <ul>
 *   <li>To add a new tag, add it here and to all flags it applies to. If you cannot do this in a
 *       single change, mark it as "deprecated" until it has been applied everywhere. Once it can be
 *       relied upon, remove the deprecation mark.
 *   <li>To remove a tag, remove it from all flags and mark it as deprecated for 6 months before
 *       removing it entirely from the list below.
 *   <li>To change the intent of a tag (i.e. to tighten or loosen its definition), make sure that
 *       the new scope doesn't include untagged options or exclude options that still have this tag.
 *       Please try not to do this. Create a new value and deprecate the old one instead, to avoid
 *       confusion.
 * </ul>
 *
 * <p>** Waived during the transition phase : The proto is not yet depended on externally and none
 * of these constraints are rigid until that switch is flipped. Generally, during the transition
 * phase while we go through the depot, you can safely assume that the list of categories and tags
 * is incomplete. If you see a hole, fill it! Please still do make an effort to go through
 * already-categorized options bases that have options that your new/altered tag would apply to,
 * file a bug against flag owners or go through them yourself. This is not meant to block you from
 * adding tags, just to keep the end state sane.
 */
public enum OptionEffectTag {
  /**
   * This option's effect or intent is unknown.
   *
   * <p>Please do not use this value for new flags. This is meant to aid transition and for a very
   * specific set of flags that actually have unknown effect, such as --config where the effect
   * depends on what other options are triggered.
   */
  UNKNOWN(0),

  /**
   * This flag has literally no effect.
   *
   * <p>Kept here for completeness and for deprecated flags. No new flag should set this tag.
   */
  NO_OP(1),

  /**
   * Using this option causes Bazel to lose potentially significant incremental state, which may
   * make this or following builds slower. State could be lost due to a server restart or to
   * invalidation of a large part of the dependency graph.
   */
  LOSES_INCREMENTAL_STATE(2),

  /**
   * This option affects the inputs to the command. For example, it might affect Bazel's interaction
   * with repository versions, or be a meta-option that affects the options set for a given
   * invocation.
   *
   * <p>Yes, all options are technically inputs, but only options that affect inputs other
   * than itself should be tagged.
   */
  CHANGES_INPUTS(3),

  /**
   * This option affects bazel's outputs. Which outputs exist and where they go are both relevant
   * here. This tag is intentionally broad, as many different types of flags will affect the output
   * of the invocation.
   */
  AFFECTS_OUTPUTS(4),

  /** This option affects the semantics of MODULE.bazel, BUILD, or bzl files. */
  BUILD_FILE_SEMANTICS(5),

  /**
   * This option affects settings of Bazel-internal machinery. This tag does not, on its own, mean
   * that external artifacts are affected, but the route taken to make them might have differed.
   */
  BAZEL_INTERNAL_CONFIGURATION(6),

  /**
   * This option affects the loading and analysis of dependencies, and the building of the
   * dependency graph.
   */
  LOADING_AND_ANALYSIS(7),

  /**
   * This option affects the execution phase. Sandboxing or remote execution related options should
   * use this category.
   */
  EXECUTION(8),

  /**
   * This option triggers an optimization that may be machine specific and is not guaranteed to work
   * on all machines. Depending on what is being optimized for, this could be a tradeoff with other
   * aspects of performance, such as memory or cpu cost.
   */
  HOST_MACHINE_RESOURCE_OPTIMIZATIONS(9),

  /** This option changes how eagerly a Bazel invocation will exit from a failure. */
  EAGERNESS_TO_EXIT(10),

  /**
   * This option is used for the purposes of monitoring Bazel behavior or performance. The
   * information collected might have effect on logging output, but should not be relevant for the
   * majority of Bazel users that aren't also Bazel developers.
   */
  BAZEL_MONITORING(11),

  /**
   * This option affects Bazel's terminal output, but should not affect its operations. Verbosity
   * and formatting options should have this tag.
   */
  TERMINAL_OUTPUT(12),

  /**
   * This option is used to change command line arguments of one or more actions during the build.
   *
   * <p>Even though many options implicitly change command line arguments because they change
   * configured target analysis, this setting is intended for options specifically meant for
   * for that purpose.
   */
  ACTION_COMMAND_LINES(13),

  /** This option is used to change the testrunner environment of the build. */
  TEST_RUNNER(14);

  private final int value;

  OptionEffectTag(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
