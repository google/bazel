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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.rules.cpp.LinkBuildVariables.LINKER_PARAM_FILE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.AbstractCommandLine;
import com.google.devtools.build.lib.actions.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines;
import com.google.devtools.build.lib.actions.ParamFileInfo;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;

/**
 * Represents the command line of a linker invocation. It supports executables and dynamic libraries
 * as well as static libraries.
 */
@Immutable
public final class LinkCommandLine extends AbstractCommandLine {
  private final String actionName;
  private final String forcedToolPath;
  private final CcToolchainVariables variables;
  // The feature config can be null for tests.
  @Nullable private final FeatureConfiguration featureConfiguration;

  private final boolean splitCommandLine;
  private final ParameterFileType parameterFileType;

  private LinkCommandLine(
      String actionName,
      String forcedToolPath,
      boolean splitCommandLine,
      ParameterFileType parameterFileType,
      CcToolchainVariables variables,
      @Nullable FeatureConfiguration featureConfiguration) {

    this.actionName = actionName;
    this.forcedToolPath = forcedToolPath;
    this.variables = variables;
    this.featureConfiguration = featureConfiguration;
    this.splitCommandLine = splitCommandLine;
    this.parameterFileType = parameterFileType;
  }

  public String getActionName() {
    return actionName;
  }

  /** Returns the path to the linker. */
  public String getLinkerPathString() throws EvalException {
    if (forcedToolPath != null) {
      return forcedToolPath;
    } else {
      if (!featureConfiguration.actionIsConfigured(actionName)) {
        throw Starlark.errorf("Expected action_config for '%s' to be configured", actionName);
      }
      return featureConfiguration.getToolPathForAction(actionName);
    }
  }

  /** Returns the build variables used to template the crosstool for this linker invocation. */
  @VisibleForTesting
  public CcToolchainVariables getBuildVariables() {
    return this.variables;
  }

  public ImmutableList<String> getParamCommandLine(
      @Nullable ArtifactExpander expander, PathMapper pathMapper)
      throws CommandLineExpansionException {
    ImmutableList.Builder<String> argv = ImmutableList.builder();
    try {
      if (variables.isAvailable(LINKER_PARAM_FILE.getVariableName())) {
        // Filter out linker_param_file
        String linkerParamFile =
            variables
                .getVariable(LINKER_PARAM_FILE.getVariableName(), pathMapper)
                .getStringValue(LINKER_PARAM_FILE.getVariableName(), pathMapper);
        argv.addAll(
            featureConfiguration
                .getCommandLine(actionName, variables, expander, pathMapper)
                .stream()
                .filter(s -> !s.contains(linkerParamFile))
                .collect(toImmutableList()));
      } else {
        argv.addAll(
            featureConfiguration.getCommandLine(actionName, variables, expander, pathMapper));
      }
    } catch (ExpansionException e) {
      throw new CommandLineExpansionException(e.getMessage());
    }
    return argv.build();
  }

  CommandLines getCommandLines() throws EvalException {
    CommandLines.Builder builder = CommandLines.builder();
    builder.addSingleArgument(getLinkerPathString());
    builder.addCommandLine(this, getParamFileInfo());
    return builder.build();
  }

  @Nullable
  ParamFileInfo getParamFileInfo() throws EvalException {
    ParamFileInfo paramFileInfo = null;
    if (splitCommandLine) {
      try {
        Optional<String> formatString =
            featureConfiguration
                .getCommandLine(actionName, variables, null, PathMapper.NOOP)
                .stream()
                .filter(s -> s.contains("LINKER_PARAM_FILE_PLACEHOLDER"))
                .findAny();
        if (formatString.isPresent()) {
          paramFileInfo =
              ParamFileInfo.builder(parameterFileType)
                  .setFlagFormatString(
                      formatString
                          .get()
                          .replace("%", "%%")
                          .replace("LINKER_PARAM_FILE_PLACEHOLDER", "%s"))
                  .setUseAlways(true)
                  .build();
        }
      } catch (ExpansionException e) {
        throw new EvalException(e);
      }
    }
    return paramFileInfo;
  }

  @Override
  public List<String> arguments() throws CommandLineExpansionException {
    return arguments(null, PathMapper.NOOP);
  }

  @Override
  public List<String> arguments(ArtifactExpander artifactExpander, PathMapper pathMapper)
      throws CommandLineExpansionException {
    return getParamCommandLine(artifactExpander, pathMapper);
  }

  /** A builder for a {@link LinkCommandLine}. */
  public static final class Builder {

    private String forcedToolPath;
    private boolean splitCommandLine;
    private ParameterFileType parameterFileType = ParameterFileType.UNQUOTED;
    private CcToolchainVariables variables;
    private FeatureConfiguration featureConfiguration;
    private String actionName;

    public LinkCommandLine build() {
      if (variables == null) {
        variables = CcToolchainVariables.EMPTY;
      }

      return new LinkCommandLine(
          actionName,
          forcedToolPath,
          splitCommandLine,
          parameterFileType,
          variables,
          featureConfiguration);
    }

    /** Use given tool path instead of the one from feature configuration */
    @CanIgnoreReturnValue
    public Builder forceToolPath(String forcedToolPath) {
      this.forcedToolPath = forcedToolPath;
      return this;
    }

    /** Sets the feature configuration for this link action. */
    @CanIgnoreReturnValue
    public Builder setFeatureConfiguration(FeatureConfiguration featureConfiguration) {
      this.featureConfiguration = featureConfiguration;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setSplitCommandLine(boolean splitCommandLine) {
      this.splitCommandLine = splitCommandLine;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setParameterFileType(ParameterFileType parameterFileType) {
      this.parameterFileType = parameterFileType;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setBuildVariables(CcToolchainVariables variables) {
      this.variables = variables;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setActionName(String actionName) {
      this.actionName = actionName;
      return this;
    }
  }
}
