// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider;
import com.google.devtools.build.lib.analysis.config.CoreOptions.IncludeConfigFragmentsEnum;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.ClassName;
import java.io.OutputStream;

/** Default Output callback for cquery. Prints a label and configuration pair per result. */
public class LabelAndConfigurationOutputFormatterCallback extends CqueryThreadsafeCallback {
  private final boolean showKind;
  private final LabelPrinter labelPrinter;

  LabelAndConfigurationOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<CqueryNode> accessor,
      boolean showKind,
      LabelPrinter labelPrinter) {
    super(eventHandler, options, out, skyframeExecutor, accessor, /* uniquifyResults= */ false);
    this.showKind = showKind;
    this.labelPrinter = labelPrinter;
  }

  @Override
  public String getName() {
    return this.showKind ? "label_kind" : "label";
  }

  @Override
  public void processOutput(Iterable<CqueryNode> partialResult) {
    for (CqueryNode keyedConfiguredTarget : partialResult) {
      StringBuilder output = new StringBuilder();
      if (showKind) {
        Target actualTarget = accessor.getTarget(keyedConfiguredTarget);
        output = output.append(actualTarget.getTargetKind()).append(" ");
      }
      output =
          output
              .append(keyedConfiguredTarget.getDescription(labelPrinter))
              .append(" (")
              .append(shortId(getConfiguration(keyedConfiguredTarget.getConfigurationKey())))
              .append(")");

      if (options.showRequiredConfigFragments != IncludeConfigFragmentsEnum.OFF) {
        output.append(' ').append(requiredFragmentStrings(keyedConfiguredTarget));
      }

      addResult(output.toString());
    }
  }

  private static ImmutableSortedSet<String> requiredFragmentStrings(
      CqueryNode keyedConfiguredTarget) {
    if (!(keyedConfiguredTarget instanceof ConfiguredTarget)) {
      return ImmutableSortedSet.of();
    }

    RequiredConfigFragmentsProvider requiredFragments =
        ((ConfiguredTarget) keyedConfiguredTarget)
            .getProvider(RequiredConfigFragmentsProvider.class);
    if (requiredFragments == null) {
      return ImmutableSortedSet.of();
    }

    return ImmutableSortedSet.<String>naturalOrder()
        .addAll(
            Iterables.transform(
                requiredFragments.optionsClasses(), ClassName::getSimpleNameWithOuter))
        .addAll(
            Iterables.transform(
                requiredFragments.fragmentClasses(), ClassName::getSimpleNameWithOuter))
        .addAll(Iterables.transform(requiredFragments.defines(), define -> "--define:" + define))
        .addAll(Iterables.transform(requiredFragments.starlarkOptions(), Label::toString))
        .build();
  }
}
