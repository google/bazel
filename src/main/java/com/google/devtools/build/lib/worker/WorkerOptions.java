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
package com.google.devtools.build.lib.worker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.util.RamResourceConverter;
import com.google.devtools.build.lib.util.ResourceConverter;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionSetConverter;
import com.google.devtools.common.options.Converters.DurationConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/** Options related to worker processes. */
public class WorkerOptions extends OptionsBase {
  public static final WorkerOptions DEFAULTS = Options.getDefaults(WorkerOptions.class);

  /**
   * Defines a resource converter for named values in the form [name=]value, where the value is
   * {@link ResourceConverter.FLAG_SYNTAX}. If no name is provided (used when setting a default),
   * the empty string is used as the key. The default value for unspecified mnemonics is defined in
   * {@link WorkerPoolImpl.createPool}. "auto" currently returns the default.
   */
  public static class MultiResourceConverter extends Converter.Contextless<Entry<String, Integer>> {

    static final ResourceConverter.IntegerConverter valueConverter =
        new ResourceConverter.IntegerConverter(() -> 0, 0, Integer.MAX_VALUE);

    @Override
    public Map.Entry<String, Integer> convert(String input) throws OptionsParsingException {
      // TODO(steinman): Make auto value return a reasonable multiplier of host capacity.
      if (input == null || input.equals("null") || input.equals("auto")) {
        return Maps.immutableEntry(null, null);
      }
      int pos = input.indexOf('=');
      if (pos < 0) {
        return Maps.immutableEntry(
            "", valueConverter.convert(input, /* conversionContext= */ null));
      }
      String name = input.substring(0, pos);
      String value = input.substring(pos + 1);
      if (value.equals("auto")) {
        return Maps.immutableEntry(name, null);
      }

      return Maps.immutableEntry(
          name, valueConverter.convert(value, /* conversionContext= */ null));
    }

    @Override
    public String getTypeDescription() {
      return "[name=]value, where value is " + ResourceConverter.FLAG_SYNTAX;
    }
  }

  @Option(
      name = "worker_max_instances",
      converter = MultiResourceConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "How many instances of each kind of persistent worker may be "
              + "launched if you use the 'worker' strategy. May be specified as [name=value] to "
              + "give a different value per mnemonic. The limit is based on worker keys, which are "
              + "differentiated based on mnemonic, but also on startup flags and environment, so "
              + "there can in some cases be more workers per mnemonic than this flag specifies. "
              + "Takes "
              + ResourceConverter.FLAG_SYNTAX
              + ". 'auto' calculates a reasonable default based on machine capacity. "
              + "\"=value\" sets a default for unspecified mnemonics.",
      allowMultiple = true)
  public List<Map.Entry<String, Integer>> workerMaxInstances;

  @Option(
      name = "worker_max_multiplex_instances",
      oldName = "experimental_worker_max_multiplex_instances",
      converter = MultiResourceConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "How many WorkRequests a multiplex worker process may receive in parallel if you use the"
              + " 'worker' strategy with --worker_multiplex. May be specified as "
              + "[name=value] to give a different value per mnemonic. The limit is based on worker "
              + "keys, which are differentiated based on mnemonic, but also on startup flags and "
              + "environment, so there can in some cases be more workers per mnemonic than this "
              + "flag specifies. Takes "
              + ResourceConverter.FLAG_SYNTAX
              + ". 'auto' calculates a reasonable default based on machine capacity. "
              + "\"=value\" sets a default for unspecified mnemonics.",
      allowMultiple = true)
  public List<Map.Entry<String, Integer>> workerMaxMultiplexInstances;

  @Option(
      name = "worker_quit_after_build",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help = "If enabled, all workers quit after a build is done.")
  public boolean workerQuitAfterBuild;

  @Option(
      name = "worker_verbose",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.UNKNOWN},
      help = "If enabled, prints verbose messages when workers are started, shutdown, ...")
  public boolean workerVerbose;

  @Option(
      name = "worker_extra_flag",
      converter = Converters.AssignmentConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "Extra command-flags that will be passed to worker processes in addition to "
              + "--persistent_worker, keyed by mnemonic (e.g. --worker_extra_flag=Javac=--debug.",
      allowMultiple = true)
  public List<Map.Entry<String, String>> workerExtraFlags;

  @Option(
      name = "worker_sandboxing",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "If enabled, singleplex workers will run in a sandboxed environment. Singleplex workers"
              + " are always sandboxed when running under the dynamic execution strategy,"
              + " irrespective of this flag.")
  public boolean workerSandboxing;

  @Option(
      name = "worker_multiplex",
      oldName = "experimental_worker_multiplex",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help = "If enabled, workers will use multiplexing if they support it. ")
  public boolean workerMultiplex;

  @Option(
      name = "experimental_worker_cancellation",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help = "If enabled, Bazel may send cancellation requests to workers that support them.")
  public boolean workerCancellation;

  @Option(
      name = "experimental_worker_multiplex_sandboxing",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "If enabled, multiplex workers with a 'supports-multiplex-sandboxing' execution"
              + " requirement will run in a sandboxed environment, using a separate sandbox"
              + " directory per work request. Multiplex workers with the execution requirement are"
              + " always sandboxed when running under the dynamic execution strategy,"
              + " irrespective of this flag.")
  public boolean multiplexSandboxing;

  @Option(
      name = "experimental_worker_strict_flagfiles",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "If enabled, actions arguments for workers that do not follow the worker specification"
              + " will cause an error. Worker arguments must have exactly one @flagfile argument"
              + " as the last of its list of arguments.")
  public boolean strictFlagfiles;

  @Option(
      name = "experimental_total_worker_memory_limit_mb",
      converter = RamResourceConverter.class,
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "If this limit is greater than zero idle workers might be killed if the total memory"
              + " usage of all  workers exceed the limit.")
  public int totalWorkerMemoryLimitMb;

  @Option(
      name = "experimental_worker_use_cgroups_on_linux",
      defaultValue = "false",
      // List as undocumented since we will want to make this the default eventually.
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "On linux, run all workers in its own cgroup (without any limits set) and use the"
              + " cgroup's own resource accounting for memory measurements. This is overridden by"
              + " --experimental_worker_sandbox_hardening for sandboxed workers.")
  public boolean useCgroupsOnLinux;

  @Option(
      name = "experimental_worker_sandbox_hardening",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "If enabled, workers are run in a hardened sandbox, if the implementation allows it. If"
              + " hardening is enabled then tmp directories are distinct for different workers.")
  public boolean sandboxHardening;

  @Option(
      name = "experimental_shrink_worker_pool",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "If enabled, could shrink worker pool if worker memory pressure is high. This flag works"
              + " only when flag experimental_total_worker_memory_limit_mb is enabled.")
  public boolean shrinkWorkerPool;

  @Option(
      name = "experimental_worker_metrics_poll_interval",
      converter = DurationConverter.class,
      defaultValue = "5s",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "The interval between collecting worker metrics and possibly attempting evictions. "
              + "Cannot effectively be less than 1s for performance reasons.")
  public Duration workerMetricsPollInterval;

  @Option(
      name = "experimental_worker_memory_limit_mb",
      converter = RamResourceConverter.class,
      defaultValue = "0",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "If this limit is greater than zero, workers might be killed if the memory usage of the "
              + "worker exceeds the limit. If not used together with dynamic execution and "
              + "`--experimental_dynamic_ignore_local_signals=9`, this may crash your build.")
  public int workerMemoryLimitMb;

  @Option(
      name = "experimental_worker_sandbox_inmemory_tracking",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION},
      help =
          "A worker key mnemonic for which the contents of the sandbox directory are tracked in"
              + " memory. This may improve build performance at the cost of additional memory"
              + " usage. Only affects sandboxed workers. May be specified multiple times for"
              + " different mnemonics.")
  public List<String> workerSandboxInMemoryTracking;

  @Option(
      name = "experimental_worker_allowlist",
      converter = CommaSeparatedOptionSetConverter.class,
      defaultValue = "null",
      documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
      effectTags = {OptionEffectTag.EXECUTION, OptionEffectTag.HOST_MACHINE_RESOURCE_OPTIMIZATIONS},
      help =
          "If non-empty, only allow using persistent workers with the given worker key mnemonic.")
  public ImmutableList<String> allowlist;
}
