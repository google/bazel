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

package com.google.devtools.build.lib.bazel.coverage;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory;
import com.google.devtools.build.lib.analysis.test.CoverageReportActionFactory.CoverageReportActionsWrapper;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsProvider;
import java.util.Collection;
import javax.annotation.Nullable;

/** Adds support for coverage report generation. */
public class BazelCoverageReportModule extends BlazeModule {

  /** Options that affect coverage report generation. */
  public static class Options extends OptionsBase {

    @Option(
        name = "combined_report",
        converter = ReportTypeConverter.class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = {OptionEffectTag.UNKNOWN},
        defaultValue = "none",
        help =
            "Specifies desired cumulative coverage report type. At this point only LCOV "
                + "is supported.")
    public ReportType combinedReport;
  }

  /** Possible values for the --combined_report option. */
  public enum ReportType {
    NONE,
    LCOV,
  }

  /** Converter for the --combined_report option. */
  public static class ReportTypeConverter extends EnumConverter<ReportType> {
    public ReportTypeConverter() {
      super(ReportType.class, "combined coverage report type");
    }
  }

  @Override
  public ImmutableList<Class<? extends OptionsBase>> getCommandOptions(Command command) {
    return command.name().equals("build") ? ImmutableList.of(Options.class) : ImmutableList.of();
  }

  @Override
  public CoverageReportActionFactory getCoverageReportFactory(OptionsProvider commandOptions) {
    Options options = commandOptions.getOptions(Options.class);
    return new CoverageReportActionFactory() {
      @Override
      @Nullable
      public CoverageReportActionsWrapper createCoverageReportActionsWrapper(
          EventHandler eventHandler,
          EventBus eventBus,
          BlazeDirectories directories,
          Collection<ConfiguredTarget> targetsToTest,
          NestedSet<Artifact> baselineCoverageArtifacts,
          ArtifactFactory artifactFactory,
          ActionKeyContext actionKeyContext,
          ActionLookupKey actionLookupKey,
          String workspaceName)
          throws InterruptedException {
        if (options == null || options.combinedReport == ReportType.NONE) {
          return null;
        }
        Preconditions.checkArgument(options.combinedReport == ReportType.LCOV);
        CoverageReportActionBuilder builder = new CoverageReportActionBuilder();
        CoverageReportActionsWrapper wrapper =
            builder.createCoverageActionsWrapper(
                eventHandler,
                directories,
                targetsToTest,
                baselineCoverageArtifacts,
                artifactFactory,
                actionKeyContext,
                actionLookupKey,
                workspaceName,
                this::getArgs,
                this::getLocationMessage,
                /* htmlReport= */ false);
        eventBus.register(new CoverageReportCollector(wrapper));
        return wrapper;
      }

      private ImmutableList<String> getArgs(CoverageArgs args) {
        ImmutableList.Builder<String> argsBuilder =
            ImmutableList.<String>builder()
                .add(
                    args.reportGenerator().getExecutable().getExecPathString(),
                    // A file that contains all the exec paths to the coverage artifacts
                    "--reports_file=" + args.lcovArtifact().getExecPathString(),
                    "--output_file=" + args.lcovOutput().getExecPathString());
        return argsBuilder.build();
      }

      private String getLocationMessage(CoverageArgs args) {
        return "LCOV coverage report is located at "
            + args.lcovOutput().getPath().getPathString()
            + "\n and execpath is "
            + args.lcovOutput().getExecPathString();
      }
    };
  }

  private record CoverageReportCollector(CoverageReportActionsWrapper wrapper) {
    @Subscribe
    public void buildComplete(BuildCompleteEvent event) {
      event
          .getResult()
          .getBuildToolLogCollection()
          .addLocalFile("coverage_report.lcov", wrapper.getCoverageReportArtifact().getPath());
    }
  }
}
