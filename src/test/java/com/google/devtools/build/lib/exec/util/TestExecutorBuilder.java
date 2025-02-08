// Copyright 2009 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec.util;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionContext;
import com.google.devtools.build.lib.actions.SpawnStrategy;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.actions.FileWriteActionContext;
import com.google.devtools.build.lib.analysis.actions.LocalTemplateExpansionStrategy;
import com.google.devtools.build.lib.analysis.actions.SymlinkTreeActionContext;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionContext;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.BinTools;
import com.google.devtools.build.lib.exec.BlazeExecutor;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.exec.FileWriteStrategy;
import com.google.devtools.build.lib.exec.ModuleActionContextRegistry;
import com.google.devtools.build.lib.exec.SpawnStrategyRegistry;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.exec.SymlinkTreeStrategy;
import com.google.devtools.build.lib.runtime.CommonCommandOptions;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;

/**
 * Builder for the test instance of the {@link BlazeExecutor} class.
 */
public class TestExecutorBuilder {
  public static final ImmutableList<Class<? extends OptionsBase>> DEFAULT_OPTIONS =
      ImmutableList.of(ExecutionOptions.class, CommonCommandOptions.class, CoreOptions.class);
  private final FileSystem fileSystem;
  private final Path execRoot;
  private Reporter reporter = new Reporter(new EventBus());
  private OptionsParser optionsParser =
      OptionsParser.builder().optionsClasses(DEFAULT_OPTIONS).build();
  private final ModuleActionContextRegistry.Builder actionContextRegistryBuilder =
      ModuleActionContextRegistry.builder();
  private final SpawnStrategyRegistry.Builder strategyRegistryBuilder =
      SpawnStrategyRegistry.builder();

  public TestExecutorBuilder(
      FileSystem fileSystem, BlazeDirectories directories, BinTools binTools) {
    this(fileSystem, directories.getExecRoot(TestConstants.WORKSPACE_NAME), binTools);
  }

  public TestExecutorBuilder(FileSystem fileSystem, Path execRoot, BinTools binTools) {
    this.fileSystem = fileSystem;
    this.execRoot = execRoot;
    addContext(FileWriteActionContext.class, new FileWriteStrategy());
    addContext(TemplateExpansionContext.class, new LocalTemplateExpansionStrategy());
    addContext(
        SymlinkTreeActionContext.class,
        new SymlinkTreeStrategy(null, execRoot, binTools, "__main__"));
    addContext(SpawnStrategyResolver.class, new SpawnStrategyResolver());
  }

  @CanIgnoreReturnValue
  public TestExecutorBuilder setReporter(Reporter reporter) {
    this.reporter = reporter;
    return this;
  }

  @CanIgnoreReturnValue
  public TestExecutorBuilder setOptionsParser(OptionsParser optionsParser) {
    this.optionsParser = optionsParser;
    return this;
  }

  @CanIgnoreReturnValue
  public TestExecutorBuilder parseOptions(String... options) throws OptionsParsingException {
    this.optionsParser.parse(options);
    return this;
  }

  @CanIgnoreReturnValue
  public TestExecutorBuilder parseOptions(List<String> options) throws OptionsParsingException {
    this.optionsParser.parse(options);
    return this;
  }

  /**
   * Makes the given action context available in the execution phase.
   *
   * <p>If two action contexts are registered with the same identifying type and commandline
   * identifier the last registered will take precedence.
   */
  @CanIgnoreReturnValue
  public <T extends ActionContext> TestExecutorBuilder addContext(
      Class<T> identifyingType, T context, String... commandlineIdentifiers) {
    actionContextRegistryBuilder.register(identifyingType, context, commandlineIdentifiers);
    return this;
  }

  /** Makes the given strategy available in the execution phase. */
  @CanIgnoreReturnValue
  public TestExecutorBuilder addStrategy(SpawnStrategy strategy, String... commandlineIdentifiers) {
    strategyRegistryBuilder.registerStrategy(strategy, commandlineIdentifiers);
    return this;
  }

  /**
   * Sets the default strategies to use if none are supplied by the user.
   *
   * <p>Replaces any previously set default strategies.
   */
  @CanIgnoreReturnValue
  public TestExecutorBuilder setDefaultStrategies(String... strategies) {
    strategyRegistryBuilder.setDefaultStrategies(ImmutableList.copyOf(strategies));
    return this;
  }

  @CanIgnoreReturnValue
  public TestExecutorBuilder setExecution(String mnemonic, String strategy) {
    strategyRegistryBuilder.addMnemonicFilter(mnemonic, ImmutableList.of(strategy));
    return this;
  }

  public BlazeExecutor build() throws AbruptExitException {
    SpawnStrategyRegistry strategyRegistry = strategyRegistryBuilder.build();
    addContext(SpawnStrategyRegistry.class, strategyRegistry);
    ModuleActionContextRegistry actionContextRegistry = actionContextRegistryBuilder.build();
    return new BlazeExecutor(
        fileSystem,
        execRoot,
        reporter,
        BlazeClock.instance(),
        BugReporter.defaultInstance(),
        optionsParser,
        actionContextRegistry,
        strategyRegistry);
  }
}
