Project: /_project.yaml
Book: /_book.yaml

# Dynamic Execution

{% include "_buttons.html" %}

__Dynamic execution__ is a feature in Bazel where local and remote execution of
the same action are started in parallel, using the output from the first branch
that finishes, cancelling the other branch. It combines the execution power
and/or large shared cache of a remote build system with the low latency of local
execution, providing the best of both worlds for clean and incremental builds
alike.

This page describes how to enable, tune, and debug dynamic execution. If you
have both local and remote execution set up and are trying to adjust Bazel
settings for better performance, this page is for you. If you don't already have
remote execution set up, go to the Bazel [Remote Execution
Overview](/remote/rbe) first.

## Enabling dynamic execution? {:#enabling-dynamic-execution}

The dynamic execution module is part of Bazel, but to make use of dynamic
execution, you must already be able to compile both locally and remotely from
the same Bazel setup.

To enable the dynamic execution module, pass the `--internal_spawn_scheduler`
flag to Bazel. This adds a new execution strategy called `dynamic`. You can now
use this as your strategy for the mnemonics you want to run dynamically, such as
`--strategy=Javac=dynamic`. See the next section for how to pick which mnemonics
to enable dynamic execution for.

For any mnemonic using the dynamic strategy, the remote execution strategies are
taken from the `--dynamic_remote_strategy` flag, and local strategies from the
`--dynamic_local_strategy` flag. Passing
`--dynamic_local_strategy=worker,sandboxed` sets the default for the local
branch of dynamic execution to try with workers or sandboxed execution in that
order. Passing `--dynamic_local_strategy=Javac=worker` overrides the default for
the Javac mnemonic only. The remote version works the same way. Both flags can
be specified multiple times. If an action cannot be executed locally, it is
executed remotely as normal, and vice-versa.

If your remote system has a cache, the `--dynamic_local_execution_delay` flag
adds a delay in milliseconds to the local execution after the remote system has
indicated a cache hit. This avoids running local execution when more cache hits
are likely. The default value is 1000ms, but should be tuned to being just a bit
longer than cache hits usually take. The actual time depends both on the remote
system and on how long a round-trip takes. Usually, the value will be the same
for all users of a given remote system, unless some of them are far enough away
to add roundtrip latency. You can use the [Bazel profiling
features](/rules/performance#performance-profiling) to look at how long typical
cache hits take.

Dynamic execution can be used with local sandboxed strategy as well as with
[persistent workers](/remote/persistent). Persistent workers will automatically
run with sandboxing when used with dynamic execution, and cannot use [multiplex
workers](/remote/multiplex). On Darwin and Windows systems, the sandboxed
strategy can be slow; you can pass `--reuse_sandbox_directories` to reduce
overhead of creating sandboxes on these systems.

Dynamic execution can also run with the `standalone` strategy, though since the
`standalone` strategy must take the output lock when it starts executing, it
effectively blocks the remote strategy from finishing first. The
`--experimental_local_lockfree_output` flag enables a way around this problem by
allowing the local execution to write directly to the output, but be aborted by
the remote execution, should that finish first.

If one of the branches of dynamic execution finishes first but is a failure, the
entire action fails. This is an intentional choice to prevent differences
between local and remote execution from going unnoticed.

For more background on how dynamic execution and its locking works, see Julio
Merino's excellent [blog
posts](https://jmmv.dev/series/bazel-dynamic-execution/){: .external}

## When should I use dynamic execution? {:#when-to-use}

Dynamic execution requires some form of [remote execution system](/remote/rbe).
It is not currently possible to use a cache-only remote system, as a cache miss
would be considered a failed action.

Not all types of actions are well suited for remote execution. The best
candidates are those that are inherently faster locally, for instance through
the use of [persistent workers](/remote/persistent), or those that run fast
enough that the overhead of remote execution dominates execution time. Since
each locally executed action locks some amount of CPU and memory resources,
running actions that don't fall into those categories merely delays execution
for those that do.

As of release
[5.0.0-pre.20210708.4](https://github.com/bazelbuild/bazel/releases/tag/5.0.0-pre.20210708.4){: .external},
[performance profiling](/rules/performance#performance-profiling) contains data
about worker execution, including time spent finishing a work request after
losing a dynamic execution race. If you see dynamic execution worker threads
spending significant time acquiring resources, or a lot of time in the
`async-worker-finish`, you may have some slow local actions delaying the worker
threads.

<p align="center">
<img width="596px" alt="Profiling data with poor dynamic execution performance"
 src="/docs/images/dyn-trace-alldynamic.png">
</p>

In the profile above, which uses 8 Javac workers, we see many Javac workers
having lost the races and finishing their work on the `async-worker-finish`
threads. This was caused by a non-worker mnemonic taking enough resources to
delay the workers.

<p align="center">
<img width="596px" alt="Profiling data with better dynamic execution performance"
 src="/docs/images/dyn-trace-javaconly.png">
</p>

When only Javac is run with dynamic execution, only about half of the started
workers end up losing the race after starting their work.

The previously recommended `--experimental_spawn_scheduler` flag is deprecated.
It turns on dynamic execution and sets `dynamic` as the default strategy for all
mnemonics, which would often lead to these kinds of problems.

## Performance {:#performance}

The dynamic execution approach assumes there are enough resources available
locally and remotely that it's worth spending some extra resources to improve
overall performance. But excessive resource usage may slow down Bazel itself or
the machine it runs on, or put unexpected pressure on a remote system. There are
several options for changing the behaviour of dynamic execution:

`--dynamic_local_execution_delay` delays the start of a local branch by a number
of milliseconds after the remote branch has started, but only if there has been
a remote cache hit during the current build. This makes builds that benefit
from remote caching not waste local resources when it is likely that most
outputs can be found in the cache. Depending on the quality of the cache,
reducing this might improve build speeds, at the cost of using more local
resources.

`--experimental_dynamic_local_load_factor` is an experimental advanced resource
management option. It takes a value from 0 to 1, 0 turning off this feature.
When set to a value above 0, Bazel adjusts the number of
locally scheduled actions when many actions waiting to
be scheduled. Setting it to 1 allows as many actions to be scheduled as there
are CPUs available (as per `--local_resources`). Lower values set the number
of actions scheduled to correspondingly fewer as higher numbers of actions are
available to run. This may sound counter-intuitive, but with a good remote
system, local execution does not help much when many actions are being run, and
the local CPU is better spent managing remote actions.

`--experimental_dynamic_slow_remote_time` prioritizes starting local branches
when the remote branch has been running for at least this long. Normally the
most recently scheduled action gets priority, as it has the greatest chance of
winning the race, but if the remote system sometimes hangs or takes extra long,
this can get a build to move along. This is not enabled by default, because it
could hide issues with the remote system that should rather be fixed. Make sure
to monitor your remote system performance if you enable this option.

`--experimental_dynamic_ignore_local_signals` can be used to let the remote
branch take over when a local spawn exits due to a given signal. This is
is mainly useful together with worker resource limits (see
[`--experimental_worker_memory_limit_mb`](https://bazel.build/reference/command-line-reference#flag--experimental_worker_memory_limit_mb),
[`--experimental_worker_sandbox_hardening`](https://bazel.build/reference/command-line-reference#flag--experimental_worker_sandbox_hardening),
and
[`--experimental_sandbox_memory_limit_mb`)](https://bazel.build/reference/command-line-reference#flag--experimental_sandbox_memory_limit_mb)),
where worker processes may be killed when they use too many resources.

The [JSON trace profile](/advanced/performance/json-trace-profile) contains a
number of performance-related graphs that can help identify ways to improve the
trade-off of performance and resource usage.

## Troubleshooting {:#troubleshooting}

Problems with dynamic execution can be subtle and hard to debug, as they can
manifest only under some specific combinations of local and remote execution.
The `--debug_spawn_scheduler` adds extra output from the dynamic execution
system that can help debug these problems. You can also adjust the
`--dynamic_local_execution_delay` flag and number of remote vs. local jobs to
make it easier to reproduce the problems.

If you are experiencing problems with dynamic execution using the `standalone`
strategy, try running without `--experimental_local_lockfree_output`, or run
your local actions sandboxed. This may slow down your build a bit (see above if
you're on Mac or Windows), but removes some possible causes for failures.