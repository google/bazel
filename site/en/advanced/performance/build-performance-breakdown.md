Project: /_project.yaml
Book: /_book.yaml

# Breaking down build performance

{% include "_buttons.html" %}

Bazel is complex and does a lot of different things over the course of a build,
some of which can have an impact on build performance. This page attempts to map
some of these Bazel concepts to their implications on build performance. While
not extensive, we have included some examples of how to detect build performance
issues through [extracting metrics](/configure/build-performance-metrics)
and what you can do to fix them. With this, we hope you can apply these concepts
when investigating build performance regressions.

### Clean vs Incremental builds

A clean build is one that builds everything from scratch, while an incremental
build reuses some already completed work.

We suggest looking at clean and incremental builds separately, especially when
you are collecting / aggregating metrics that are dependent on the state of
Bazel’s caches (for example
[build request size metrics](#deterministic-build-metrics-as-a-proxy-for-build-performance)
). They also represent two different user experiences. As compared to starting
a clean build from scratch (which takes longer due to a cold cache), incremental
builds happen far more frequently as developers iterate on code (typically
faster since the cache is usually already warm).

You can use the `CumulativeMetrics.num_analyses` field in the BEP to classify
builds. If `num_analyses <= 1`, it is a clean build; otherwise, we can broadly
categorize it to likely be an incremental build - the user could have switched
to different flags or different targets causing an effectively clean build. Any
more rigorous definition of incrementality will likely have to come in the form
of a heuristic, for example looking at the number of packages loaded
(`PackageMetrics.packages_loaded`).

### Deterministic build metrics as a proxy for build performance

Measuring build performance can be difficult due to the non-deterministic nature
of certain metrics (for example Bazel’s CPU time or queue times on a remote
cluster). As such, it can be useful to use deterministic metrics as a proxy for
the amount of work done by Bazel, which in turn affects its performance.

The size of a build request can have a significant implication on build
performance. A larger build could represent more work in analyzing and
constructing the build graphs. Organic growth of builds comes naturally with
development, as more dependencies are added/created, and thus grow in complexity
and become more expensive to build.

We can slice this problem into the various build phases, and use the following
metrics as proxy metrics for work done at each phase:

1. `PackageMetrics.packages_loaded`: the number of packages successfully loaded.
  A regression here represents more work that needs to be done to read and parse
  each additional BUILD file in the loading phase.
   - This is often due to the addition of dependencies and having to load their
     transitive closure.
   - Use [query](/query/quickstart) / [cquery](/query/cquery) to find
     where new dependencies might have been added.

2. `TargetMetrics.targets_configured`: representing the number of targets and
  aspects configured in the build. A regression represents more work in
  constructing and traversing the configured target graph.
   - This is often due to the addition of dependencies and having to construct
     the graph of their transitive closure.
   - Use [cquery](/query/cquery) to find where new
     dependencies might have been added.

3. `ActionSummary.actions_created`: represents the actions created in the build,
  and a regression represents more work in constructing the action graph. Note
  that this also includes unused actions that might not have been executed.
   - Use [aquery](/query/aquery) for debugging regressions;
     we suggest starting with
     [`--output=summary`](/reference/command-line-reference#flag--output)
     before further drilling down with
     [`--skyframe_state`](/reference/command-line-reference#flag--skyframe_state).

4. `ActionSummary.actions_executed`: the number of actions executed, a
  regression directly represents more work in executing these actions.
   - The [BEP](/remote/bep) writes out the action statistics
     `ActionData` that shows the most executed action types. By default, it
     collects the top 20 action types, but you can pass in the
     [`--experimental_record_metrics_for_all_mnemonics`](/reference/command-line-reference#flag--experimental_record_metrics_for_all_mnemonics)
     to collect this data for all action types that were executed.
   - This should help you to figure out what kind of actions were executed
     (additionally).

5. `BuildGraphSummary.outputArtifactCount`: the number of artifacts created by
  the executed actions.
   - If the number of actions executed did not increase, then it is likely that
     a rule implementation was changed.


These metrics are all affected by the state of the local cache, hence you will
want to ensure that the builds you extract these metrics from are
**clean builds**.

We have noted that a regression in any of these metrics can be accompanied by
regressions in wall time, cpu time and memory usage.

### Usage of local resources

Bazel consumes a variety of resources on your local machine (both for analyzing
the build graph and driving the execution, and for running local actions), this
can affect the performance / availability of your machine in performing the
build, and also other tasks.

#### Time spent

Perhaps the metrics most susceptible to noise (and can vary greatly from build
to build) is time; in particular - wall time, cpu time and system time. You can
use [bazel-bench](https://github.com/bazelbuild/bazel-bench) to get
a benchmark for these metrics, and with a sufficient number of `--runs`, you can
increase the statistical significance of your measurement.

- **Wall time** is the real world time elapsed.
   - If _only_ wall time regresses, we suggest collecting a
     [JSON trace profile](/advanced/performance/json-trace-profile) and looking
     for differences. Otherwise, it would likely be more efficient to
     investigate other regressed metrics as they could have affected the wall
     time.

- **CPU time** is the time spent by the CPU executing user code.
   - If the CPU time regresses across two project commits, we suggest collecting
     a Starlark CPU profile. You should probably also use `--nobuild` to
     restrict the build to the analysis phase since that is where most of the
     CPU heavy work is done.

- System time is the time spent by the CPU in the kernel.
   - If system time regresses, it is mostly correlated with I/O when Bazel reads
     files from your file system.

#### System-wide load profiling

Using the
[`--experimental_collect_load_average_in_profiler`](https://github.com/bazelbuild/bazel/blob/6.0.0/src/main/java/com/google/devtools/build/lib/runtime/CommonCommandOptions.java#L306-L312)
flag introduced in Bazel 6.0, the
[JSON trace profiler](/advanced/performance/json-trace-profile) collects the
system load average during the invocation.

![Profile that includes system load average](/docs/images/json-trace-profile-system-load-average.png "Profile that includes system load average")

**Figure 1.** Profile that includes system load average.

A high load during a Bazel invocation can be an indication that Bazel schedules
too many local actions in parallel for your machine. You might want to look into
adjusting
[`--local_resources`](/reference/command-line-reference#flag--local_resources),
especially in container environments (at least until
[#16512](https://github.com/bazelbuild/bazel/pull/16512) is merged).


#### Monitoring Bazel memory usage

There are two main sources to get Bazel’s memory usage, Bazel `info` and the
[BEP](/remote/bep).

- `bazel info used-heap-size-after-gc`: The amount of used memory in bytes after
  a call to `System.gc()`.
   - [Bazel bench](https://github.com/bazelbuild/bazel-bench)
     provides benchmarks for this metric as well.
   - Additionally, there are `peak-heap-size`, `max-heap-size`, `used-heap-size`
     and `committed-heap-size` (see
     [documentation](/docs/user-manual#configuration-independent-data)), but are
     less relevant.

- [BEP](/remote/bep)’s
  `MemoryMetrics.peak_post_gc_heap_size`: Size of the peak JVM heap size in
  bytes post GC (requires setting
  [`--memory_profile`](/reference/command-line-reference#flag--memory_profile)
  that attempts to force a full GC).

A regression in memory usage is usually a result of a regression in
[build request size metrics](#deterministic_build_metrics_as_a_proxy_for_build_performance),
which are often due to addition of dependencies or a change in the rule
implementation.

To analyze Bazel’s memory footprint on a more granular level, we recommend using
the [built-in memory profiler](/rules/performance#memory-profiling)
for rules.

#### Memory profiling of persistent workers

While [persistent workers](/remote/persistent) can help to speed up builds
significantly (especially for interpreted languages) their memory footprint can
be problematic. Bazel collects metrics on its workers, in particular, the
`WorkerMetrics.WorkerStats.worker_memory_in_kb` field tells how much memory
workers use (by mnemonic).

The [JSON trace profiler](/advanced/performance/json-trace-profile) also
collects persistent worker memory usage during the invocation by passing in the
[`--experimental_collect_system_network_usage`](https://github.com/bazelbuild/bazel/blob/6.0.0/src/main/java/com/google/devtools/build/lib/runtime/CommonCommandOptions.java#L314-L320)
flag (new in Bazel 6.0).

![Profile that includes workers memory usage](/docs/images/json-trace-profile-workers-memory-usage.png "Profile that includes workers memory usage")

**Figure 2.** Profile that includes workers memory usage.

Lowering the value of
[`--worker_max_instances`](/reference/command-line-reference#flag--worker_max_instances)
(default 4) might help to reduce
the amount of memory used by persistent workers. We are actively working on
making Bazel’s resource manager and scheduler smarter so that such fine tuning
will be required less often in the future.

### Monitoring network traffic for remote builds

In remote execution, Bazel downloads artifacts that were built as a result of
executing actions. As such, your network bandwidth can affect the performance
of your build.

If you are using remote execution for your builds, you might want to consider
monitoring the network traffic during the invocation using the
`NetworkMetrics.SystemNetworkStats` proto from the [BEP](/remote/bep)
(requires passing `--experimental_collect_system_network_usage`).

Furthermore, [JSON trace profiles](/advanced/performance/json-trace-profile)
allow you to view system-wide network usage throughout the course of the build
by passing the `--experimental_collect_system_network_usage` flag (new in Bazel
6.0).

![Profile that includes system-wide network usage](/docs/images/json-trace-profile-network-usage.png "Profile that includes system-wide network usage")

**Figure 3.** Profile that includes system-wide network usage.

A high but rather flat network usage when using remote execution might indicate
that network is the bottleneck in your build; if you are not using it already,
consider turning on Build without the Bytes by passing
[`--remote_download_minimal`](/reference/command-line-reference#flag--remote_download_minimal).
This will speed up your builds by avoiding the download of unnecessary intermediate artifacts.

Another option is to configure a local
[disk cache](/reference/command-line-reference#flag--disk_cache) to save on
download bandwidth.
