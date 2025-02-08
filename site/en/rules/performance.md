Project: /_project.yaml
Book: /_book.yaml

# Optimizing Performance

{% include "_buttons.html" %}

When writing rules, the most common performance pitfall is to traverse or copy
data that is accumulated from dependencies. When aggregated over the whole
build, these operations can easily take O(N^2) time or space. To avoid this, it
is crucial to understand how to use depsets effectively.

This can be hard to get right, so Bazel also provides a memory profiler that
assists you in finding spots where you might have made a mistake. Be warned:
The cost of writing an inefficient rule may not be evident until it is in
widespread use.

## Use depsets {:#use-depsets}

Whenever you are rolling up information from rule dependencies you should use
[depsets](lib/depset). Only use plain lists or dicts to publish information
local to the current rule.

A depset represents information as a nested graph which enables sharing.

Consider the following graph:

```
C -> B -> A
D ---^
```

Each node publishes a single string. With depsets the data looks like this:

```
a = depset(direct=['a'])
b = depset(direct=['b'], transitive=[a])
c = depset(direct=['c'], transitive=[b])
d = depset(direct=['d'], transitive=[b])
```

Note that each item is only mentioned once. With lists you would get this:

```
a = ['a']
b = ['b', 'a']
c = ['c', 'b', 'a']
d = ['d', 'b', 'a']
```

Note that in this case `'a'` is mentioned four times! With larger graphs this
problem will only get worse.

Here is an example of a rule implementation that uses depsets correctly to
publish transitive information. Note that it is OK to publish rule-local
information using lists if you want since this is not O(N^2).

```
MyProvider = provider()

def _impl(ctx):
  my_things = ctx.attr.things
  all_things = depset(
      direct=my_things,
      transitive=[dep[MyProvider].all_things for dep in ctx.attr.deps]
  )
  ...
  return [MyProvider(
    my_things=my_things,  # OK, a flat list of rule-local things only
    all_things=all_things,  # OK, a depset containing dependencies
  )]
```

See the [depset overview](/extending/depsets) page for more information.

### Avoid calling `depset.to_list()` {:#avoid-depset-to-list}

You can coerce a depset to a flat list using
[`to_list()`](lib/depset#to_list), but doing so usually results in O(N^2)
cost. If at all possible, avoid any flattening of depsets except for debugging
purposes.

A common misconception is that you can freely flatten depsets if you only do it
at top-level targets, such as an `<xx>_binary` rule, since then the cost is not
accumulated over each level of the build graph. But this is *still* O(N^2) when
you build a set of targets with overlapping dependencies. This happens when
building your tests `//foo/tests/...`, or when importing an IDE project.

### Reduce the number of calls to `depset` {:#reduce-calls-depset}

Calling `depset` inside a loop is often a mistake. It can lead to depsets with
very deep nesting, which perform poorly. For example:

```python
x = depset()
for i in inputs:
    # Do not do that.
    x = depset(transitive = [x, i.deps])
```

This code can be replaced easily. First, collect the transitive depsets and
merge them all at once:

```python
transitive = []

for i in inputs:
    transitive.append(i.deps)

x = depset(transitive = transitive)
```

This can sometimes be reduced using a list comprehension:

```python
x = depset(transitive = [i.deps for i in inputs])
```

## Use ctx.actions.args() for command lines {:#ctx-actions-args}

When building command lines you should use [ctx.actions.args()](lib/Args).
This defers expansion of any depsets to the execution phase.

Apart from being strictly faster, this will reduce the memory consumption of
your rules -- sometimes by 90% or more.

Here are some tricks:

* Pass depsets and lists directly as arguments, instead of flattening them
yourself. They will get expanded by `ctx.actions.args()` for you.
If you need any transformations on the depset contents, look at
[ctx.actions.args#add](lib/Args#add) to see if anything fits the bill.

* Are you passing `File#path` as arguments? No need. Any
[File](lib/File) is automatically turned into its
[path](lib/File#path), deferred to expansion time.

* Avoid constructing strings by concatenating them together.
The best string argument is a constant as its memory will be shared between
all instances of your rule.

* If the args are too long for the command line an `ctx.actions.args()` object
can be conditionally or unconditionally written to a param file using
[`ctx.actions.args#use_param_file`](lib/Args#use_param_file). This is
done behind the scenes when the action is executed. If you need to explicitly
control the params file you can write it manually using
[`ctx.actions.write`](lib/actions#write).

Example:

```
def _impl(ctx):
  ...
  args = ctx.actions.args()
  file = ctx.declare_file(...)
  files = depset(...)

  # Bad, constructs a full string "--foo=<file path>" for each rule instance
  args.add("--foo=" + file.path)

  # Good, shares "--foo" among all rule instances, and defers file.path to later
  # It will however pass ["--foo", <file path>] to the action command line,
  # instead of ["--foo=<file_path>"]
  args.add("--foo", file)

  # Use format if you prefer ["--foo=<file path>"] to ["--foo", <file path>]
  args.add(format="--foo=%s", value=file)

  # Bad, makes a giant string of a whole depset
  args.add(" ".join(["-I%s" % file.short_path for file in files])

  # Good, only stores a reference to the depset
  args.add_all(files, format_each="-I%s", map_each=_to_short_path)

# Function passed to map_each above
def _to_short_path(f):
  return f.short_path
```

## Transitive action inputs should be depsets {:#transitive-action-inputs}

When building an action using [ctx.actions.run](lib/actions?#run), do not
forget that the `inputs` field accepts a depset. Use this whenever inputs are
collected from dependencies transitively.

```
inputs = depset(...)
ctx.actions.run(
  inputs = inputs,  # Do *not* turn inputs into a list
  ...
)
```

## Hanging {:#hanging}

If Bazel appears to be hung, you can hit <kbd>Ctrl-&#92;</kbd> or send
Bazel a `SIGQUIT` signal (`kill -3 $(bazel info server_pid)`) to get a thread
dump in the file `$(bazel info output_base)/server/jvm.out`.

Since you may not be able to run `bazel info` if bazel is hung, the
`output_base` directory is usually the parent of the `bazel-<workspace>`
symlink in your workspace directory.

## Performance profiling {:#performance-profiling}

The [JSON trace profile](/advanced/performance/json-trace-profile) can be very
useful to quickly understand what Bazel spent time on during the invocation.

The [`--experimental_command_profile`](https://bazel.build/reference/command-line-reference#flag--experimental_command_profile)
flag may be used to capture Java Flight Recorder profiles of various kinds
(cpu time, wall time, memory allocations and lock contention).

The [`--starlark_cpu_profile`](https://bazel.build/reference/command-line-reference#flag--starlark_cpu_profile)
flag may be used to write a pprof profile of CPU usage by all Starlark threads.

## Memory profiling {:#memory-profiling}

Bazel comes with a built-in memory profiler that can help you check your rule’s
memory use. If there is a problem you can dump the heap to find the
exact line of code that is causing the problem.

### Enabling memory tracking {:#enabling-memory-tracking}

You must pass these two startup flags to *every* Bazel invocation:

  ```
  STARTUP_FLAGS=\
  --host_jvm_args=-javaagent:<path to java-allocation-instrumenter-3.3.4.jar> \
  --host_jvm_args=-DRULE_MEMORY_TRACKER=1
  ```
Note: You can download the allocation instrumenter jar file from [Maven Central
Repository][allocation-instrumenter-link].

[allocation-instrumenter-link]: https://repo1.maven.org/maven2/com/google/code/java-allocation-instrumenter/java-allocation-instrumenter/3.3.4

These start the server in memory tracking mode. If you forget these for even
one Bazel invocation the server will restart and you will have to start over.

### Using the Memory Tracker {:#memory-tracker}

As an example, look at the target `foo` and see what it does. To only
run the analysis and not run the build execution phase, add the
`--nobuild` flag.

```
$ bazel $(STARTUP_FLAGS) build --nobuild //foo:foo
```

Next, see how much memory the whole Bazel instance consumes:

```
$ bazel $(STARTUP_FLAGS) info used-heap-size-after-gc
> 2594MB
```

Break it down by rule class by using `bazel dump --rules`:

```
$ bazel $(STARTUP_FLAGS) dump --rules
>

RULE                                 COUNT     ACTIONS          BYTES         EACH
genrule                             33,762      33,801    291,538,824        8,635
config_setting                      25,374           0     24,897,336          981
filegroup                           25,369      25,369     97,496,272        3,843
cc_library                           5,372      73,235    182,214,456       33,919
proto_library                        4,140     110,409    186,776,864       45,115
android_library                      2,621      36,921    218,504,848       83,366
java_library                         2,371      12,459     38,841,000       16,381
_gen_source                            719       2,157      9,195,312       12,789
_check_proto_library_deps              719         668      1,835,288        2,552
... (more output)
```

Look at where the memory is going by producing a `pprof` file
using `bazel dump --skylark_memory`:

```
$ bazel $(STARTUP_FLAGS) dump --skylark_memory=$HOME/prof.gz
> Dumping Starlark heap to: /usr/local/google/home/$USER/prof.gz
```

Use the `pprof` tool to investigate the heap. A good starting point is
getting a flame graph by using `pprof -flame $HOME/prof.gz`.

Get `pprof` from [https://github.com/google/pprof](https://github.com/google/pprof){: .external}.

Get a text dump of the hottest call sites annotated with lines:

```
$ pprof -text -lines $HOME/prof.gz
>
      flat  flat%   sum%        cum   cum%
  146.11MB 19.64% 19.64%   146.11MB 19.64%  android_library <native>:-1
  113.02MB 15.19% 34.83%   113.02MB 15.19%  genrule <native>:-1
   74.11MB  9.96% 44.80%    74.11MB  9.96%  glob <native>:-1
   55.98MB  7.53% 52.32%    55.98MB  7.53%  filegroup <native>:-1
   53.44MB  7.18% 59.51%    53.44MB  7.18%  sh_test <native>:-1
   26.55MB  3.57% 63.07%    26.55MB  3.57%  _generate_foo_files /foo/tc/tc.bzl:491
   26.01MB  3.50% 66.57%    26.01MB  3.50%  _build_foo_impl /foo/build_test.bzl:78
   22.01MB  2.96% 69.53%    22.01MB  2.96%  _build_foo_impl /foo/build_test.bzl:73
   ... (more output)
```
