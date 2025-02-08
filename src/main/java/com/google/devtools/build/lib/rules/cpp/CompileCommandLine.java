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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.AbstractCommandLine;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.rules.cpp.CcCommon.CoptsFilter;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** The compile command line for the C++ compile action. */
public final class CompileCommandLine {
  private final Artifact sourceFile;
  private final CoptsFilter coptsFilter;
  private final FeatureConfiguration featureConfiguration;
  private final CcToolchainVariables variables;
  private final String actionName;
  private final Artifact dotdFile;

  private CompileCommandLine(
      Artifact sourceFile,
      CoptsFilter coptsFilter,
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables variables,
      String actionName,
      Artifact dotdFile) {
    this.sourceFile = Preconditions.checkNotNull(sourceFile);
    this.coptsFilter = coptsFilter;
    this.featureConfiguration = Preconditions.checkNotNull(featureConfiguration);
    this.variables = variables;
    this.actionName = actionName;
    this.dotdFile = isGenerateDotdFile(sourceFile) ? dotdFile : null;
  }

  /** Returns true if Dotd file should be generated. */
  private boolean isGenerateDotdFile(Artifact sourceArtifact) {
    return CppFileTypes.headerDiscoveryRequired(sourceArtifact)
        && !featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES);
  }

  /** Returns the environment variables that should be set for C++ compile actions. */
  ImmutableMap<String, String> getEnvironment(PathMapper pathMapper)
      throws CommandLineExpansionException {
    try {
      return featureConfiguration.getEnvironmentVariables(actionName, variables, pathMapper);
    } catch (ExpansionException e) {
      throw new CommandLineExpansionException(e.getMessage());
    }
  }

  /** Returns the tool path for the compilation based on the current feature configuration. */
  @VisibleForTesting
  public String getToolPath() {
    Preconditions.checkArgument(
        featureConfiguration.actionIsConfigured(actionName),
        "Expected action_config for '%s' to be configured",
        actionName);
    return featureConfiguration.getToolPathForAction(actionName);
  }

  /**
   * @param overwrittenVariables: Variables that will overwrite original build variables. When null,
   *     unmodified original variables are used.
   */
  List<String> getArguments(
      @Nullable PathFragment parameterFilePath,
      @Nullable CcToolchainVariables overwrittenVariables,
      PathMapper pathMapper)
      throws CommandLineExpansionException {
    List<String> commandLine = new ArrayList<>();

    // first: The command name.
    if (pathMapper.isNoop()) {
      commandLine.add(getToolPath());
    } else {
      // getToolPath() ultimately returns a PathFragment's getSafePathString(), so its safe to
      // reparse it here with no risk of e.g. altering a user-specified absolute path.
      commandLine.add(pathMapper.map(PathFragment.create(getToolPath())).getSafePathString());
    }

    // second: The compiler options.
    if (parameterFilePath != null) {
      commandLine.add("@" + parameterFilePath.getSafePathString());
    } else {
      commandLine.addAll(getCompilerOptions(overwrittenVariables, pathMapper));
    }
    return commandLine;
  }

  /**
   * Returns {@link CommandLine} instance that contains the exactly same command line as the {@link
   * CppCompileAction}.
   *
   * @param cppCompileAction - {@link CppCompileAction} owning this {@link CompileCommandLine}.
   */
  public CommandLine getFilteredFeatureConfigurationCommandLine(CppCompileAction cppCompileAction) {
    return new AbstractCommandLine() {

      @Override
      public Iterable<String> arguments() throws CommandLineExpansionException {
        CcToolchainVariables overwrittenVariables = cppCompileAction.getOverwrittenVariables();
        List<String> compilerOptions = getCompilerOptions(overwrittenVariables, PathMapper.NOOP);
        return ImmutableList.<String>builder().add(getToolPath()).addAll(compilerOptions).build();
      }
    };
  }

  public List<String> getCompilerOptions(
      @Nullable CcToolchainVariables overwrittenVariables, PathMapper pathMapper)
      throws CommandLineExpansionException {
    try {
      List<String> options = new ArrayList<>();

      CcToolchainVariables updatedVariables = variables;
      if (variables != null && overwrittenVariables != null) {
        CcToolchainVariables.Builder variablesBuilder = CcToolchainVariables.builder(variables);
        variablesBuilder.addAllNonTransitive(overwrittenVariables);
        updatedVariables = variablesBuilder.build();
      }
      addFilteredOptions(
          options,
          featureConfiguration.getPerFeatureExpansions(actionName, updatedVariables, pathMapper));

      return options;
    } catch (ExpansionException e) {
      throw new CommandLineExpansionException(e.getMessage());
    }
  }

  // For each option in 'in', add it to 'out' unless it is matched by the 'coptsFilter' regexp.
  private void addFilteredOptions(
      List<String> out, List<Pair<String, List<String>>> expandedFeatures) {
    for (Pair<String, List<String>> pair : expandedFeatures) {
      if (pair.getFirst().equals(CppRuleClasses.UNFILTERED_COMPILE_FLAGS_FEATURE_NAME)) {
        out.addAll(pair.getSecond());
        continue;
      }
      // We do not uses Java's stream API here as it causes a substantial overhead compared to the
      // very little work that this is actually doing.
      for (String flag : pair.getSecond()) {
        if (coptsFilter.passesFilter(flag)) {
          out.add(flag);
        }
      }
    }
  }

  public Artifact getSourceFile() {
    return sourceFile;
  }

  public Artifact getDotdFile() {
    return dotdFile;
  }

  public CcToolchainVariables getVariables() {
    return variables;
  }

  /**
   * Returns all user provided copts flags.
   *
   * <p>TODO(b/64108724): Get rid of this method when we don't need to parse copts to collect
   * include directories anymore (meaning there is a way of specifying include directories using an
   * explicit attribute, not using platform-dependent garbage bag that copts is).
   */
  public ImmutableList<String> getCopts(PathMapper pathMapper) {
    if (variables.isAvailable(CompileBuildVariables.USER_COMPILE_FLAGS.getVariableName())) {
      try {
        return CcToolchainVariables.toStringList(
            variables, CompileBuildVariables.USER_COMPILE_FLAGS.getVariableName(), pathMapper);
      } catch (ExpansionException e) {
        throw new IllegalStateException(
            "Should not happen - 'user_compile_flags' should be a string list, but wasn't.", e);
      }
    } else {
      return ImmutableList.of();
    }
  }

  public static Builder builder(
      Artifact sourceFile, CoptsFilter coptsFilter, String actionName, Artifact dotdFile) {
    return new Builder(sourceFile, coptsFilter, actionName, dotdFile);
  }

  /** A builder for a {@link CompileCommandLine}. */
  public static final class Builder {
    private final Artifact sourceFile;
    private CoptsFilter coptsFilter;
    private FeatureConfiguration featureConfiguration;
    private CcToolchainVariables variables = CcToolchainVariables.EMPTY;
    private final String actionName;
    @Nullable private final Artifact dotdFile;

    public CompileCommandLine build() {
      return new CompileCommandLine(
          Preconditions.checkNotNull(sourceFile),
          Preconditions.checkNotNull(coptsFilter),
          Preconditions.checkNotNull(featureConfiguration),
          Preconditions.checkNotNull(variables),
          Preconditions.checkNotNull(actionName),
          dotdFile);
    }

    private Builder(
        Artifact sourceFile, CoptsFilter coptsFilter, String actionName, Artifact dotdFile) {
      this.sourceFile = sourceFile;
      this.coptsFilter = coptsFilter;
      this.actionName = actionName;
      this.dotdFile = dotdFile;
    }

    /** Sets the feature configuration for this compile action. */
    @CanIgnoreReturnValue
    public Builder setFeatureConfiguration(FeatureConfiguration featureConfiguration) {
      this.featureConfiguration = featureConfiguration;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setVariables(CcToolchainVariables variables) {
      this.variables = variables;
      return this;
    }

    @CanIgnoreReturnValue
    @VisibleForTesting
    Builder setCoptsFilter(CoptsFilter filter) {
      this.coptsFilter = Preconditions.checkNotNull(filter);
      return this;
    }
  }
}
