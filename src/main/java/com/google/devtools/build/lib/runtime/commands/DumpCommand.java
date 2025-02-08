// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper;
import com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DisplayMode;
import com.google.devtools.build.lib.buildtool.SkyframeMemoryDumper.DumpFailedException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker.RuleBytes;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeCommandUtils;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlazeWorkspace;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InstrumentationOutput;
import com.google.devtools.build.lib.runtime.InstrumentationOutputFactory.DestinationRelativeTo;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DumpCommand.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyKeyStats;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeStats;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.MemoryAccountant.Stats;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.util.RegexFilter.RegexFilterConverter;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/** Implementation of the dump command. */
@Command(
    name = "dump",
    mustRunInWorkspace = false,
    buildPhase = NONE,
    options = {DumpCommand.DumpOptions.class},
    help =
        "Usage: %{product} dump <options>\n"
            + "Dumps the internal state of the %{product} server process.  This command is provided"
            + " as an aid to debugging, not as a stable interface, so users should not try to parse"
            + " the output; instead, use 'query' or 'info' for this purpose.\n"
            + "%{options}",
    shortDescription = "Dumps the internal state of the %{product} server process.",
    binaryStdOut = true)
public class DumpCommand implements BlazeCommand {

  /** How to dump Skyframe memory. */
  private enum MemoryCollectionMode {
    /** Dump the objects owned by a single SkyValue */
    SHALLOW,
    /** Dump objects reachable from a single SkyValue */
    DEEP,
    /** Dump objects in the Skyframe transitive closure of a SkyValue */
    TRANSITIVE,
    /** Dump every object in Skyframe in "shallow" mode. */
    FULL,
  }

  /** Whose memory use we should measure. */
  private enum MemorySubjectType {
    /** Starlark module */
    STARLARK_MODULE,
    /* Build package */
    PACKAGE,
    /* Configured target */
    CONFIGURED_TARGET,
  }

  private record MemoryMode(
      MemoryCollectionMode collectionMode,
      DisplayMode displayMode,
      MemorySubjectType type,
      String needle,
      boolean reportTransient,
      boolean reportConfiguration,
      boolean reportPrecomputed,
      boolean reportWorkspaceStatus,
      String subject) {}

  /** Converter for {@link MemoryCollectionMode}. */
  public static final class MemoryModeConverter extends Converter.Contextless<MemoryMode> {
    @Override
    public String getTypeDescription() {
      return "memory mode";
    }

    @Override
    public MemoryMode convert(String input) throws OptionsParsingException {
      // The SkyKey designator is frequently a Label, which usually contains a colon so we must not
      // split the argument into an unlimited number of elements
      String[] items = input.split(":", 3);
      if (items.length > 3) {
        throw new OptionsParsingException("Should contain at most three segments separated by ':'");
      }

      MemoryCollectionMode collectionMode = null;
      DisplayMode displayMode = null;
      String needle = null;
      boolean reportTransient = true;
      boolean reportConfiguration = true;
      boolean reportPrecomputed = true;
      boolean reportWorkspaceStatus = true;

      for (String word : Splitter.on(",").split(items[0])) {
        if (word.startsWith("needle=")) {
          needle = word.split("=", 2)[1];
          continue;
        }

        switch (word) {
          case "shallow" -> collectionMode = MemoryCollectionMode.SHALLOW;
          case "deep" -> collectionMode = MemoryCollectionMode.DEEP;
          case "transitive" -> collectionMode = MemoryCollectionMode.TRANSITIVE;
          case "full" -> collectionMode = MemoryCollectionMode.FULL;
          case "summary" -> displayMode = DisplayMode.SUMMARY;
          case "count" -> displayMode = DisplayMode.COUNT;
          case "bytes" -> displayMode = DisplayMode.BYTES;
          case "notransient" -> reportTransient = false;
          case "noconfig" -> reportConfiguration = false;
          case "noprecomputed" -> reportPrecomputed = false;
          case "noworkspacestatus" -> reportWorkspaceStatus = false;
          default -> throw new OptionsParsingException("Unrecognized word '" + word + "'");
        }
      }

      if (collectionMode == null) {
        throw new OptionsParsingException("No collection type specified");
      }

      if (displayMode == null) {
        throw new OptionsParsingException("No display mode specified");
      }

      if (collectionMode == MemoryCollectionMode.FULL) {
        return new MemoryMode(
            collectionMode,
            displayMode,
            null,
            needle,
            reportTransient,
            reportConfiguration,
            reportPrecomputed,
            reportWorkspaceStatus,
            null);
      }

      if (items.length != 3) {
        throw new OptionsParsingException("Should be in the form: <flags>:<node type>:<node>");
      }

      MemorySubjectType subjectType;

      try {
        subjectType = MemorySubjectType.valueOf(items[1].toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new OptionsParsingException("Invalid subject type", e);
      }

      return new MemoryMode(
          collectionMode,
          displayMode,
          subjectType,
          needle,
          reportTransient,
          reportConfiguration,
          reportPrecomputed,
          reportWorkspaceStatus,
          items[2]);
    }
  }

  /**
   * NB! Any changes to this class must be kept in sync with anyOutput variable value in the {@link
   * DumpCommand#exec} method below.
   */
  public static class DumpOptions extends OptionsBase {

    @Option(
        name = "packages",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump package cache content.")
    public boolean dumpPackages;

    @Option(
        name = "action_cache",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump action cache content.")
    public boolean dumpActionCache;

    @Option(
        name = "rule_classes",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump rule classes.")
    public boolean dumpRuleClasses;

    @Option(
        name = "rules",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump rules, including counts and memory usage (if memory is tracked).")
    public boolean dumpRules;

    @Option(
        name = "skylark_memory",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Dumps a pprof-compatible memory profile to the specified path. To learn more please"
                + " see https://github.com/google/pprof.")
    public String starlarkMemory;

    @Option(
        name = "skyframe",
        defaultValue = "off",
        converter = SkyframeDumpEnumConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump the Skyframe graph.")
    public SkyframeDumpOption dumpSkyframe;

    @Option(
        name = "skykey_filter",
        defaultValue = ".*",
        converter = RegexFilterConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Regex filter of SkyKey names to output. Only used with --skyframe=deps, rdeps,"
                + " function_graph.")
    public RegexFilter skyKeyFilter;

    @Option(
        name = "memory",
        defaultValue = "null",
        converter = MemoryModeConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump the memory use of the given Skyframe node.")
    public MemoryMode memory;
  }

  /** Different ways to dump information about Skyframe. */
  public enum SkyframeDumpOption {
    OFF,
    SUMMARY,
    COUNT,
    VALUE,
    DEPS,
    RDEPS,
    FUNCTION_GRAPH,
    WORKING_SET,
    WORKING_SET_FRONTIER_DEPS,
  }

  /** Enum converter for SkyframeDumpOption. */
  public static class SkyframeDumpEnumConverter extends EnumConverter<SkyframeDumpOption> {
    public SkyframeDumpEnumConverter() {
      super(SkyframeDumpOption.class, "Skyframe Dump option");
    }
  }

  public static final String WARNING_MESSAGE =
      "This information is intended for consumption by developers "
          + "only, and may change at any time. Script against it at your own risk!";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();
    DumpOptions dumpOptions = options.getOptions(DumpOptions.class);

    boolean anyOutput =
        dumpOptions.dumpPackages
            || dumpOptions.dumpActionCache
            || dumpOptions.dumpRuleClasses
            || dumpOptions.dumpRules
            || dumpOptions.starlarkMemory != null
            || dumpOptions.dumpSkyframe != SkyframeDumpOption.OFF
            || dumpOptions.memory != null;
    if (!anyOutput) {
      Collection<Class<? extends OptionsBase>> optionList = new ArrayList<>();
      optionList.add(DumpOptions.class);

      env.getReporter()
          .getOutErr()
          .printErrLn(
              BlazeCommandUtils.expandHelpTopic(
                  getClass().getAnnotation(Command.class).name(),
                  getClass().getAnnotation(Command.class).help(),
                  getClass(),
                  optionList,
                  OptionsParser.HelpVerbosity.LONG,
                  runtime.getProductName()));
      return createFailureResult("no output specified", Code.NO_OUTPUT_SPECIFIED);
    }
    PrintStream out =
        new PrintStream(
            new BufferedOutputStream(env.getReporter().getOutErr().getOutputStream(), 1024 * 1024));
    try {
      env.getReporter().handle(Event.warn(WARNING_MESSAGE));
      Optional<BlazeCommandResult> failure = Optional.empty();

      if (dumpOptions.dumpPackages) {
        env.getPackageManager().dump(out);
        out.println();
      }

      if (dumpOptions.dumpActionCache) {
        if (!dumpActionCache(env, out)) {
          failure =
              Optional.of(
                  createFailureResult("action cache dump failed", Code.ACTION_CACHE_DUMP_FAILED));
        }
        out.println();
      }

      if (dumpOptions.dumpRuleClasses) {
        dumpRuleClasses(runtime, out);
        out.println();
      }

      if (dumpOptions.dumpRules) {
        dumpRuleStats(env.getReporter(), env.getBlazeWorkspace(), env.getSkyframeExecutor(), out);
        out.println();
      }

      if (dumpOptions.starlarkMemory != null) {
        try {
          InstrumentationOutput starlarkHeapOutput =
              runtime
                  .getInstrumentationOutputFactory()
                  .createInstrumentationOutput(
                      /* name= */ "starlark_heap",
                      PathFragment.create(dumpOptions.starlarkMemory),
                      DestinationRelativeTo.WORKSPACE_OR_HOME,
                      env,
                      env.getReporter(),
                      /* append= */ null,
                      /* internal= */ null);
          dumpStarlarkHeap(
              env.getBlazeWorkspace(), starlarkHeapOutput, dumpOptions.starlarkMemory, out);
        } catch (IOException e) {
          String message = "Could not dump Starlark memory";
          env.getReporter().error(null, message, e);
          failure = Optional.of(createFailureResult(message, Code.STARLARK_HEAP_DUMP_FAILED));
        }
      }

      if (dumpOptions.memory != null) {
        failure = dumpSkyframeMemory(env, dumpOptions, out);
      }

      MemoizingEvaluator evaluator = env.getSkyframeExecutor().getEvaluator();
      switch (dumpOptions.dumpSkyframe) {
        case SUMMARY -> evaluator.dumpSummary(out);
        case COUNT -> evaluator.dumpCount(out);
        case VALUE -> evaluator.dumpValues(out, dumpOptions.skyKeyFilter);
        case DEPS -> evaluator.dumpDeps(out, dumpOptions.skyKeyFilter);
        case RDEPS -> evaluator.dumpRdeps(out, dumpOptions.skyKeyFilter);
        case FUNCTION_GRAPH -> evaluator.dumpFunctionGraph(out, dumpOptions.skyKeyFilter);
        case WORKING_SET -> env.getSkyframeExecutor().getSkyfocusState().dumpWorkingSet(out);
        case WORKING_SET_FRONTIER_DEPS ->
            env.getSkyframeExecutor().getSkyfocusState().dumpFrontierSet(out);
        case OFF -> {}
      }

      return failure.orElse(BlazeCommandResult.success());
    } catch (InterruptedException e) {
      env.getReporter().error(null, "Interrupted", e);
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setInterrupted(
                  FailureDetails.Interrupted.newBuilder()
                      .setCode(FailureDetails.Interrupted.Code.INTERRUPTED))
              .build());
    } finally {
      out.flush();
    }
  }

  private static boolean dumpActionCache(CommandEnvironment env, PrintStream out) {
    Reporter reporter = env.getReporter();
    try {
      env.getBlazeWorkspace().getOrLoadPersistentActionCache(reporter).dump(out);
    } catch (IOException e) {
      reporter.handle(Event.error("Cannot dump action cache: " + e.getMessage()));
      return false;
    }
    return true;
  }

  private static void dumpRuleClasses(BlazeRuntime runtime, PrintStream out) {
    ImmutableMap<String, RuleClass> ruleClassMap = runtime.getRuleClassProvider().getRuleClassMap();
    List<String> ruleClassNames = new ArrayList<>(ruleClassMap.keySet());
    Collections.sort(ruleClassNames);
    for (String name : ruleClassNames) {
      if (name.startsWith("$")) {
        continue;
      }
      RuleClass ruleClass = ruleClassMap.get(name);
      out.print(ruleClass + "(");
      boolean first = true;
      for (Attribute attribute : ruleClass.getAttributes()) {
        if (attribute.isImplicit()) {
          continue;
        }
        if (first) {
          first = false;
        } else {
          out.print(", ");
        }
        out.print(attribute.getName());
      }
      out.println(")");
    }
  }

  private static void dumpRuleStats(
      ExtendedEventHandler eventHandler,
      BlazeWorkspace workspace,
      SkyframeExecutor executor,
      PrintStream out)
      throws InterruptedException {
    SkyframeStats skyframeStats = executor.getSkyframeStats(eventHandler);
    if (skyframeStats.ruleStats().isEmpty()) {
      out.print("No rules in Bazel server, please run a build command first.");
      return;
    }
    ImmutableList<SkyKeyStats> rules = skyframeStats.ruleStats();
    ImmutableList<SkyKeyStats> aspects = skyframeStats.aspectStats();
    Map<String, RuleBytes> ruleBytes = new HashMap<>();
    Map<String, RuleBytes> aspectBytes = new HashMap<>();
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker != null) {
      allocationTracker.getRuleMemoryConsumption(ruleBytes, aspectBytes);
    }
    printRuleStatsOfType(rules, "RULE", out, ruleBytes, allocationTracker != null, false);
    printRuleStatsOfType(aspects, "ASPECT", out, aspectBytes, allocationTracker != null, true);
  }

  private static void printRuleStatsOfType(
      ImmutableList<SkyKeyStats> ruleStats,
      String type,
      PrintStream out,
      Map<String, RuleBytes> ruleToBytes,
      boolean bytesEnabled,
      boolean trimKey) {
    if (ruleStats.isEmpty()) {
      return;
    }
    // ruleStats are already sorted.
    int longestName =
        ruleStats.stream().map(r -> r.getName().length()).max(Integer::compareTo).get();
    int maxNameWidth = 30;
    int nameColumnWidth = Math.min(longestName, maxNameWidth);
    int numberColumnWidth = 10;
    int bytesColumnWidth = 13;
    int eachColumnWidth = 11;
    printWithPadding(out, type, nameColumnWidth);
    printWithPaddingBefore(out, "COUNT", numberColumnWidth);
    printWithPaddingBefore(out, "ACTIONS", numberColumnWidth);
    if (bytesEnabled) {
      printWithPaddingBefore(out, "BYTES", bytesColumnWidth);
      printWithPaddingBefore(out, "EACH", eachColumnWidth);
    }
    out.println();
    for (SkyKeyStats ruleStat : ruleStats) {
      printWithPadding(
          out, truncateName(ruleStat.getName(), trimKey, maxNameWidth), nameColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getCount()), numberColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getActionCount()), numberColumnWidth);
      if (bytesEnabled) {
        RuleBytes ruleBytes = ruleToBytes.get(ruleStat.getKey());
        long bytes = ruleBytes != null ? ruleBytes.getBytes() : 0L;
        printWithPaddingBefore(out, formatLong(bytes), bytesColumnWidth);
        printWithPaddingBefore(out, formatLong(bytes / ruleStat.getCount()), eachColumnWidth);
      }
      out.println();
    }
    out.println();
  }

  private static String truncateName(String name, boolean trimKey, int maxNameWidth) {
    // If this is an aspect, we'll chop off everything except the aspect name
    if (trimKey) {
      int dividerIndex = name.lastIndexOf('%');
      if (dividerIndex >= 0) {
        name = name.substring(dividerIndex + 1);
      }
    }
    if (name.length() <= maxNameWidth) {
      return name;
    }
    int starti = name.length() - maxNameWidth + "...".length();
    return "..." + name.substring(starti);
  }

  private static void printWithPadding(PrintStream out, String str, int columnWidth) {
    out.print(str);
    pad(out, columnWidth + 2, str.length());
  }

  private static void printWithPaddingBefore(PrintStream out, String str, int columnWidth) {
    pad(out, columnWidth, str.length());
    out.print(str);
    pad(out, 2, 0);
  }

  private static void pad(PrintStream out, int columnWidth, int consumed) {
    for (int i = 0; i < columnWidth - consumed; ++i) {
      out.print(' ');
    }
  }

  private static String formatLong(long number) {
    return String.format("%,d", number);
  }

  @Nullable
  private static BuildConfigurationKey getConfigurationKey(CommandEnvironment env, String hash) {
    if (hash == null) {
      // Use the target configuration
      return env.getSkyframeBuildView().getBuildConfiguration().getKey();
    }

    ImmutableList<BuildConfigurationKey> candidates =
        env.getSkyframeExecutor().getEvaluator().getDoneValues().entrySet().stream()
            .filter(e -> e.getKey().functionName().equals(SkyFunctions.BUILD_CONFIGURATION))
            .map(e -> (BuildConfigurationKey) e.getKey())
            .filter(k -> k.getOptions().checksum().startsWith(hash))
            .collect(ImmutableList.toImmutableList());

    if (candidates.size() != 1) {
      env.getReporter().error(null, "ambiguous configuration, use 'blaze config' to list them");
      return null;
    }

    return candidates.get(0);
  }

  @Nullable
  private static SkyKey getMemoryDumpSkyKey(CommandEnvironment env, MemoryMode memoryMode) {
    try {
      switch (memoryMode.type()) {
        case PACKAGE -> {
          return PackageIdentifier.parse(memoryMode.subject);
        }
        case STARLARK_MODULE -> {
          return BzlLoadValue.keyForBuild(Label.parseCanonical(memoryMode.subject));
        }
        case CONFIGURED_TARGET -> {
          String[] labelAndConfig = memoryMode.subject.split("@", 2);
          BuildConfigurationKey configurationKey =
              getConfigurationKey(env, labelAndConfig.length == 2 ? labelAndConfig[1] : null);
          return ConfiguredTargetKey.builder()
              .setConfigurationKey(configurationKey)
              .setLabel(Label.parseCanonical(labelAndConfig[0]))
              .build();
        }
      }
    } catch (LabelSyntaxException e) {
      env.getReporter().error(null, "Cannot parse label: " + e.getMessage());
      return null;
    }

    throw new IllegalStateException();
  }

  private static Optional<BlazeCommandResult> dumpSkyframeMemory(
      CommandEnvironment env, DumpOptions dumpOptions, PrintStream out)
      throws InterruptedException {

    InMemoryGraph graph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();
    SkyframeMemoryDumper dumper =
        new SkyframeMemoryDumper(
            dumpOptions.memory.displayMode,
            dumpOptions.memory.needle,
            env.getRuntime().getRuleClassProvider(),
            graph,
            dumpOptions.memory.reportTransient,
            dumpOptions.memory.reportConfiguration,
            dumpOptions.memory.reportPrecomputed,
            dumpOptions.memory.reportWorkspaceStatus);

    if (dumpOptions.memory.collectionMode == MemoryCollectionMode.FULL) {
      try {
        // FULL mode doesn't have SkyKey as an argument, nor does it need a NodeEntry.
        dumper.dumpFull(out);
        return Optional.empty();
      } catch (DumpFailedException e) {
        return Optional.of(
            DumpCommand.createFailureResult(e.getMessage(), Code.SKYFRAME_MEMORY_DUMP_FAILED));
      }
    }

    SkyKey skyKey = getMemoryDumpSkyKey(env, dumpOptions.memory);
    if (skyKey == null) {
      return Optional.of(
          createFailureResult("Cannot dump Skyframe memory", Code.SKYFRAME_MEMORY_DUMP_FAILED));
    }

    NodeEntry nodeEntry = graph.get(null, Reason.OTHER, skyKey);
    if (nodeEntry == null) {
      env.getReporter().error(null, "The requested node is not present.");
      return Optional.of(
          createFailureResult(
              "The requested node is not present", Code.SKYFRAME_MEMORY_DUMP_FAILED));
    }

    Stats stats =
        switch (dumpOptions.memory.collectionMode) {
          case DEEP -> dumper.dumpReachable(nodeEntry);
          case SHALLOW -> dumper.dumpShallow(nodeEntry);
          case TRANSITIVE -> dumper.dumpTransitive(skyKey);
          case FULL -> throw new IllegalStateException();
        };

    switch (dumpOptions.memory.displayMode) {
      case SUMMARY ->
          out.printf("%d objects, %d bytes retained", stats.getObjectCount(), stats.getMemoryUse());
      case COUNT -> SkyframeMemoryDumper.printByClass("", stats.getObjectCountByClass(), out);
      case BYTES -> SkyframeMemoryDumper.printByClass("", stats.getMemoryByClass(), out);
    }

    out.println();
    return Optional.empty();
  }

  private static void dumpStarlarkHeap(
      BlazeWorkspace workspace,
      InstrumentationOutput starlarkHeapOutput,
      String path,
      PrintStream out)
      throws IOException {
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker == null) {
      out.println(
          "Cannot dump Starlark heap without running in memory tracking mode. "
              + "Please refer to the user manual for the dump commnd "
              + "for information how to turn on memory tracking.");
      return;
    }
    out.println("Dumping Starlark heap to: " + path);

    // OutputStream is expected to be closed when allocationTracker.dumpStarlarkAllocations()
    // returns.
    allocationTracker.dumpStarlarkAllocations(starlarkHeapOutput.createOutputStream());
  }

  static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setDumpCommand(FailureDetails.DumpCommand.newBuilder().setCode(detailedCode))
            .build());
  }
}
