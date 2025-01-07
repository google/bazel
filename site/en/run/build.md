Project: /_project.yaml
Book: /_book.yaml

# Build programs with Bazel

{% include "_buttons.html" %}

This page covers how to build a program with Bazel, build command syntax, and
target pattern syntax.

## Quickstart {:#quickstart}

To run Bazel, go to your base [workspace](/concepts/build-ref#workspace) directory
or any of its subdirectories and type `bazel`. See [build](#bazel-build) if you
need to make a new workspace.

```posix-terminal
bazel help
                             [Bazel release bazel {{ "<var>" }}version{{ "</var>" }}]
Usage: bazel {{ "<var>" }}command{{ "</var>" }} {{ "<var>" }}options{{ "</var>" }} ...
```

### Available commands {:#available-commands}

* [`analyze-profile`](/docs/user-manual#analyze-profile): Analyzes build profile data.
* [`aquery`](/docs/user-manual#aquery): Executes a query on the [post-analysis](#analysis) action graph.
* [`build`](#bazel-build): Builds the specified targets.
* [`canonicalize-flags`](/docs/user-manual#canonicalize-flags): Canonicalize Bazel flags.
* [`clean`](/docs/user-manual#clean): Removes output files and optionally stops the server.
* [`cquery`](/query/cquery): Executes a [post-analysis](#analysis) dependency graph query.
* [`dump`](/docs/user-manual#dump): Dumps the internal state of the Bazel server process.
* [`help`](/docs/user-manual#help): Prints help for commands, or the index.
* [`info`](/docs/user-manual#info): Displays runtime info about the bazel server.
* [`fetch`](#fetching-external-dependencies): Fetches all external dependencies of a target.
* [`mobile-install`](/docs/user-manual#mobile-install): Installs apps on mobile devices.
* [`query`](/query/guide): Executes a dependency graph query.
* [`run`](/docs/user-manual#running-executables): Runs the specified target.
* [`shutdown`](/docs/user-manual#shutdown): Stops the Bazel server.
* [`test`](/docs/user-manual#running-tests): Builds and runs the specified test targets.
* [`version`](/docs/user-manual#version): Prints version information for Bazel.

### Getting help {:#getting-help}

* `bazel help {{ '<var>' }}command{{ '</var>' }}`: Prints help and options for
  `{{ '<var>' }}command{{ '</var>' }}`.
* `bazel help `[`startup_options`](/docs/user-manual#startup-options): Options for the JVM hosting Bazel.
* `bazel help `[`target-syntax`](#specifying-build-targets): Explains the syntax for specifying targets.
* `bazel help info-keys`: Displays a list of keys used by the info command.

The `bazel` tool performs many functions, called commands. The most commonly
used ones are `bazel build` and `bazel test`. You can browse the online help
messages using `bazel help`.


### Building one target {:#bazel-build}

Before you can start a build, you need a _workspace_. A workspace is a
directory tree that contains all the source files needed to build your
application. Bazel allows you to perform a build from a completely read-only
volume.

To build a program with Bazel, type `bazel build` followed by the
[target](#specifying-build-targets) you want to build.

```posix-terminal
bazel build //foo
```

After issuing the command to build `//foo`, you'll see output similar to this:

```
INFO: Analyzed target //foo:foo (14 packages loaded, 48 targets configured).
INFO: Found 1 target...
Target //foo:foo up-to-date:
  bazel-bin/foo/foo
INFO: Elapsed time: 9.905s, Critical Path: 3.25s
INFO: Build completed successfully, 6 total actions
```


First, Bazel **loads** all packages in your target's dependency graph. This
includes _declared dependencies_, files listed directly in the target's `BUILD`
file, and _transitive dependencies_, files listed in the `BUILD` files of your
target's dependencies. After identifying all dependencies, Bazel **analyzes**
them for correctness and creates the _build actions_. Last, Bazel **executes**
the compilers and other tools of the build.

During the build's execution phase, Bazel prints progress messages. The progress
messages include the current build step (such as, compiler or linker) as it
starts, and the number completed over the total number of build actions. As the
build starts, the number of total actions  often increases as Bazel discovers
the entire action graph, but the number stabilizes within a few seconds.

At the end of the build, Bazel prints which targets were requested, whether or
not they were successfully built, and if so, where the output files can be
found. Scripts that run builds can reliably parse this output; see
[`--show_result`](/docs/user-manual#show-result) for more details.

If you type the same command again, the build finishes much faster.

```posix-terminal
bazel build //foo
INFO: Analyzed target //foo:foo (0 packages loaded, 0 targets configured).
INFO: Found 1 target...
Target //foo:foo up-to-date:
  bazel-bin/foo/foo
INFO: Elapsed time: 0.144s, Critical Path: 0.00s
INFO: Build completed successfully, 1 total action
```

This is a _null build_. Because nothing changed, there are no packages to reload
and no build steps to execute. If something changed in 'foo' or its
dependencies, Bazel would re-execute some build actions, or complete an
_incremental build_.

### Building multiple targets {:#specifying-build-targets}

Bazel allows a number of ways to specify the targets to be built. Collectively,
these are known as _target patterns_. This syntax is used in commands like
`build`, `test`, or `query`.

Whereas [labels](/concepts/labels) are used to specify individual
targets, such as for declaring dependencies in `BUILD` files, Bazel's target
patterns specify multiple targets. Target patterns are a generalization of the
label syntax for _sets_ of targets, using wildcards. In the simplest case, any
valid label is also a valid target pattern, identifying a set of exactly one
target.

All target patterns starting with `//` are resolved relative to the current
workspace.

<table>
<tr>
  <td><code>//foo/bar:wiz</code></td>
  <td>Just the single target <code>//foo/bar:wiz</code>.</td>
</tr>
<tr>
  <td><code>//foo/bar</code></td>
  <td>Equivalent to <code>//foo/bar:bar</code>.</td>
</tr>
<tr>
  <td><code>//foo/bar:all</code></td>
  <td>All rule targets in the package <code>foo/bar</code>.</td>
</tr>
<tr>
  <td><code>//foo/...</code></td>
  <td>All rule targets in all packages beneath the directory <code>foo</code>.</td>
</tr>
<tr>
  <td><code>//foo/...:all</code></td>
  <td>All rule targets in all packages beneath the directory <code>foo</code>.</td>
</tr>
<tr>
  <td><code>//foo/...:*</code></td>
  <td>All targets (rules and files) in all packages beneath the directory <code>foo</code>.</td>
</tr>
<tr>
  <td><code>//foo/...:all-targets</code></td>
  <td>All targets (rules and files) in all packages beneath the directory <code>foo</code>.</td>
</tr>
<tr>
  <td><code>//...</code></td>
  <td>All rule targets in packages in the main repository. Does not include targets
  from <a href="/docs/external">external repositories</a>.</td>
</tr>
<tr>
  <td><code>//:all</code></td>
  <td>All rule targets in the top-level package, if there is a `BUILD` file at the
  root of the workspace.</td>
</tr>
</table>

Target patterns that do not begin with `//` are resolved relative to the
current _working directory_. These examples assume a working directory of `foo`:

<table>
<tr>
  <td><code>:foo</code></td>
  <td>Equivalent to  <code>//foo:foo</code>.</td>
</tr>
<tr>
  <td><code>bar:wiz</code></td>
  <td>Equivalent to <code>//foo/bar:wiz</code>.</td>
</tr>
<tr>
  <td><code>bar/wiz</code></td>
  <td>Equivalent to:
    <ul>
      <li><code>//foo/bar/wiz:wiz</code> if <code>foo/bar/wiz</code> is a package</li>
      <li><code>//foo/bar:wiz</code> if <code>foo/bar</code> is a package</li>
      <li><code>//foo:bar/wiz</code> otherwise</li>
    </ul>
  </td>
</tr>
<tr>
  <td><code>bar:all</code></td>
  <td>Equivalent to  <code>//foo/bar:all</code>.</td>
</tr>
<tr>
  <td><code>:all</code></td>
  <td>Equivalent to  <code>//foo:all</code>.</td>
</tr>
<tr>
  <td><code>...:all</code></td>
  <td>Equivalent to  <code>//foo/...:all</code>.</td>
</tr>
<tr>
  <td><code>...</code></td>
  <td>Equivalent to  <code>//foo/...:all</code>.</td>
</tr>
<tr>
  <td><code>bar/...:all</code></td>
  <td>Equivalent to  <code>//foo/bar/...:all</code>.</td>
</tr>
</table>

By default, directory symlinks are followed for recursive target patterns,
except those that point to under the output base, such as the convenience
symlinks that are created in the root directory of the workspace.

In addition, Bazel does not follow symlinks when evaluating recursive target
patterns in any directory that contains a file named as follows:
`DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY_VIA_A_RECURSIVE_TARGET_PATTERN`

`foo/...` is a wildcard over _packages_, indicating all packages recursively
beneath directory `foo` (for all roots of the package path). `:all` is a
wildcard over _targets_, matching all rules within a package. These two may be
combined, as in `foo/...:all`, and when both wildcards are used, this may be
abbreviated to `foo/...`.

In addition, `:*` (or `:all-targets`) is a wildcard that matches _every target_
in the matched packages, including files that aren't normally built by any rule,
such as `_deploy.jar` files associated with `java_binary` rules.

This implies that `:*` denotes a _superset_ of `:all`; while potentially
confusing, this syntax does allow the familiar `:all` wildcard to be used for
typical builds, where building targets like the `_deploy.jar` is not desired.

In addition, Bazel allows a slash to be used instead of the colon required by
the label syntax; this is often convenient when using Bash filename expansion.
For example, `foo/bar/wiz` is equivalent to `//foo/bar:wiz` (if there is a
package `foo/bar`) or to `//foo:bar/wiz` (if there is a package `foo`).

Many Bazel commands accept a list of target patterns as arguments, and they all
honor the prefix negation operator `-`. This can be used to subtract a set of
targets from the set specified by the preceding arguments. Note that this means
order matters. For example,

```posix-terminal
bazel build foo/... bar/...
```

means "build all targets beneath `foo` _and_ all targets beneath `bar`", whereas

```posix-terminal
bazel build -- foo/... -foo/bar/...
```

means "build all targets beneath `foo` _except_ those beneath `foo/bar`". (The
`--` argument is required to prevent the subsequent arguments starting with `-`
from being interpreted as additional options.)

It's important to point out though that subtracting targets this way will not
guarantee that they are not built, since they may be dependencies of targets
that weren't subtracted. For example, if there were a target `//foo:all-apis`
that among others depended on `//foo/bar:api`, then the latter would be built as
part of building the former.

Targets with `tags = ["manual"]` are not included in wildcard target patterns
(`...`, `:*`, `:all`, etc.) when specified in commands like
`bazel build` and `bazel test` (but they are included in
negative wildcard target patterns, that is they will be subtracted). You should
specify such test targets with explicit target patterns on the command line if
you want Bazel to build/test them. In contrast, `bazel query` doesn't perform
any such filtering automatically (that would defeat the purpose of
`bazel query`).

### Fetching external dependencies {:#fetching-external-dependencies}

By default, Bazel will download and symlink external dependencies during the
build. However, this can be undesirable, either because you'd like to know
when new external dependencies are added or because you'd like to
"prefetch" dependencies (say, before a flight where you'll be offline). If you
would like to prevent new dependencies from being added during builds, you
can specify the `--fetch=false` flag. Note that this flag only
applies to repository rules that do not point to a directory in the local
file system. Changes, for example, to `local_repository`,
`new_local_repository` and Android SDK and NDK repository rules
will always take effect regardless of the value `--fetch` .

If you disallow fetching during builds and Bazel finds new external
dependencies, your build will fail.

You can manually fetch dependencies by running `bazel fetch`. If
you disallow during-build fetching, you'll need to run `bazel fetch`:

- Before you build for the first time.
- After you add a new external dependency.

Once it has been run, you should not need to run it again until the MODULE.bazel
file changes.

`fetch` takes a list of targets to fetch dependencies for. For
example, this would fetch dependencies needed to build `//foo:bar`
and `//bar:baz`:

```posix-terminal
bazel fetch //foo:bar //bar:baz
```

To fetch all external dependencies for a workspace, run:

```posix-terminal
bazel fetch //...
```

With Bazel 7 or later, if you have Bzlmod enabled, you can also fetch all
external dependencies by running

```posix-terminal
bazel fetch
```

You do not need to run bazel fetch at all if you have all of the tools you are
using (from library jars to the JDK itself) under your workspace root.
However, if you're using anything outside of the workspace directory then Bazel
will automatically run `bazel fetch` before running
`bazel build`.

#### The repository cache {:#repository-cache}

Bazel tries to avoid fetching the same file several times, even if the same
file is needed in different workspaces, or if the definition of an external
repository changed but it still needs the same file to download. To do so,
bazel caches all files downloaded in the repository cache which, by default,
is  located at `~/.cache/bazel/_bazel_$USER/cache/repos/v1/`. The
location can be changed by the `--repository_cache` option. The
cache is shared between all workspaces and installed versions of bazel.
An entry is taken from the cache if
Bazel knows for sure that it has a copy of the correct file, that is, if the
download request has a SHA256 sum of the file specified and a file with that
hash is in the cache. So specifying a hash for each external file is
not only a good idea from a security perspective; it also helps avoiding
unnecessary downloads.

Upon each cache hit, the modification time of the file in the cache is
updated. In this way, the last use of a file in the cache directory can easily
be determined, for example to manually clean up the cache. The cache is never
cleaned up automatically, as it might contain a copy of a file that is no
longer available upstream.

#### [Deprecated] Distribution files directories {:#distribution-directory}

**Deprecated**: *Using repository cache is preferred to achieve offline build.*

The distribution directory is another Bazel mechanism to avoid unnecessary
downloads. Bazel searches distribution directories before the repository cache.
The primary difference is that the distribution directory requires manual
preparation.

Using the
[`--distdir=/path/to-directory`](/reference/command-line-reference#flag--distdir)
option, you can specify additional read-only directories to look for files
instead of fetching them. A file is taken from such a directory if the file name
is equal to the base name of the URL and additionally the hash of the file is
equal to the one specified in the download request. This only works if the
file hash is specified in the repo rule declaration.

While the condition on the file name is not necessary for correctness, it
reduces the number of candidate files to one per specified directory. In this
way, specifying distribution files directories remains efficient, even if the
number of files in such a directory grows large.

#### Running Bazel in an airgapped environment {:#running-bazel-airgapped}

To keep Bazel's binary size small, Bazel's implicit dependencies are fetched
over the network while running for the first time. These implicit dependencies
contain toolchains and rules that may not be necessary for everyone. For
example, Android tools are unbundled and fetched only when building Android
projects.

However, these implicit dependencies may cause problems when running Bazel in an
airgapped environment, even if you have vendored all of your external
dependencies. To solve that, you can prepare a repository cache (with Bazel 7 or
later) or distribution directory (with Bazel prior to 7) containing these
dependencies on a machine with network access, and then transfer them to the
airgapped environment with an offline approach.

##### Repository cache (with Bazel 7 or later)

To prepare the [repository cache](#repository-cache), use the
[`--repository_cache`](/reference/command-line-reference#flag--repository_cache)
flag. You will need to do this once for every new Bazel binary version, since
the implicit dependencies can be different for every release.

To fetch those dependencies outside of your airgapped environment, first create
an empty workspace:

```posix-terminal
mkdir empty_workspace && cd empty_workspace

touch MODULE.bazel
```

To fetch built-in Bzlmod dependencies, run

```posix-terminal
bazel fetch --repository_cache="path/to/repository/cache"
```

If you still rely on the legacy WORKSPACE file, to fetch built-in WORKSPACE
dependencies, run

```posix-terminal
bazel sync --repository_cache="path/to/repository/cache"
```

Finally, when you use Bazel in your airgapped environment, pass the same
`--repository_cache` flag. For convenience, you can add it as an `.bazelrc`
entry:

```python
common --repository_cache="path/to/repository/cache"
```

In addition, you may also need to clone the
[BCR](https://github.com/bazelbuild/bazel-central-registry) locally and use
`--registry` flag to point your local copy to prevent Bazel from accessing the
BCR through internet. Add the following line to your `.bazelrc`:

```python
common --registry="path/to/local/bcr/registry"
```
Note that in case you don't have an 'source' server with Internet connectivity to execute the above procedure, the --dist_dir option still works in Bazel 7. This might be a path to follow in case if you don't have access. The --repository_cache option is preferred in case you don't experience this constraint. Some considerations :
- The packages will need to be downloaded manually using a web browser. This sounds more dramatic as it sounds as only a dozen or so packages are to be downloaded. The following entries could be added to the .bazelrc file or the ~/.bazelrc can be used instead on each of the target platforms without the _:windows_ and _:linux_ extensions on the tags :
```
build:windows --distdir=C:\\path\\to\\repository
test:linux --distdir=/path/to/repository
```
- Packages are to be kept compressed (either _tar.gz_ or _zip_).
- Some packages (e.g. rules_proto-4.0.0.zip) are not picked up automatically. A workaround is to add the following entries in the MODULE.bazel file and create a symbolic link in the third_party directory at the project root directory :
```
bazel_dep(name = "rules_proto", version = "4.0.0")
local_path_override(module_name = "rules_proto", path = "third_party/rules_proto")
rules_proto = use_extension("@rules_proto//:*", "rules_proto")
```
The location of the repository cache can be found by using `bazel info repository_cache`.

##### Distribution directory (with Bazel prior to 7)

To prepare the [distribution directory](#distribution-directory), use the
[`--distdir`](/reference/command-line-reference#flag--distdir)
flag. You will need to do this once for every new Bazel binary version, since
the implicit dependencies can be different for every release.

To build these dependencies outside of your airgapped environment, first
checkout the Bazel source tree at the right version:

```posix-terminal
git clone https://github.com/bazelbuild/bazel "$BAZEL_DIR"

cd "$BAZEL_DIR"

git checkout "$BAZEL_VERSION"
```

Then, build the tarball containing the implicit runtime dependencies for that
specific Bazel version:

```posix-terminal
bazel build @additional_distfiles//:archives.tar
```

Export this tarball to a directory that can be copied into your airgapped
environment. Note the `--strip-components` flag, because `--distdir` can be
quite finicky with the directory nesting level:

```posix-terminal
tar xvf bazel-bin/external/additional_distfiles/archives.tar \
  -C "$NEW_DIRECTORY" --strip-components=3
```

Finally, when you use Bazel in your airgapped environment, pass the `--distdir`
flag pointing to the directory. For convenience, you can add it as an `.bazelrc`
entry:

```posix-terminal
build --distdir={{ '<var>' }}path{{ '</var>' }}/to/{{ '<var>' }}directory{{ '</var>' }}
```

### Build configurations and cross-compilation {:#build-config-cross-compilation}

All the inputs that specify the behavior and result of a given build can be
divided into two distinct categories. The first kind is the intrinsic
information stored in the `BUILD` files of your project: the build rule, the
values of its attributes, and the complete set of its transitive dependencies.
The second kind is the external or environmental data, supplied by the user or
by the build tool: the choice of target architecture, compilation and linking
options, and other toolchain configuration options. We refer to a complete set
of environmental data as a **configuration**.

In any given build, there may be more than one configuration. Consider a
cross-compile, in which you build a `//foo:bin` executable for a 64-bit
architecture, but your workstation is a 32-bit machine. Clearly, the build will
require building `//foo:bin` using a toolchain capable of creating 64-bit
executables, but the build system must also build various tools used during the
build itself—for example tools that are built from source, then subsequently
used in, say, a genrule—and these must be built to run on your workstation. Thus
we can identify two configurations: the **exec configuration**, which is used
for building tools that run during the build, and the **target configuration**
(or _request configuration_, but we say "target configuration" more often even
though that word already has many meanings), which is used for building the
binary you ultimately requested.

Typically, there are many libraries that are prerequisites of both the requested
build target (`//foo:bin`) and one or more of the exec tools, for example some
base libraries. Such libraries must be built twice, once for the exec
configuration, and once for the target configuration. Bazel takes care of
ensuring that both variants are built, and that the derived files are kept
separate to avoid interference; usually such targets can be built concurrently,
since they are independent of each other. If you see progress messages
indicating that a given target is being built twice, this is most likely the
explanation.

The exec configuration is derived from the target configuration as follows:

-   Use the same version of Crosstool (`--crosstool_top`) as specified in the
    request configuration, unless `--host_crosstool_top` is specified.
-   Use the value of `--host_cpu` for `--cpu` (default: `k8`).
-   Use the same values of these options as specified in the request
    configuration: `--compiler`, `--use_ijars`, and if `--host_crosstool_top` is
    used, then the value of `--host_cpu` is used to look up a
    `default_toolchain` in the Crosstool (ignoring `--compiler`) for the exec
    configuration.
-   Use the value of `--host_javabase` for `--javabase`
-   Use the value of `--host_java_toolchain` for `--java_toolchain`
-   Use optimized builds for C++ code (`-c opt`).
-   Generate no debugging information (`--copt=-g0`).
-   Strip debug information from executables and shared libraries
    (`--strip=always`).
-   Place all derived files in a special location, distinct from that used by
    any possible request configuration.
-   Suppress stamping of binaries with build data (see `--embed_*` options).
-   All other values remain at their defaults.

There are many reasons why it might be preferable to select a distinct exec
configuration from the request configuration. Most importantly:

Firstly, by using stripped, optimized binaries, you reduce the time spent
linking and executing the tools, the disk space occupied by the tools, and the
network I/O time in distributed builds.

Secondly, by decoupling the exec and request configurations in all builds, you
avoid very expensive rebuilds that would result from minor changes to the
request configuration (such as changing a linker options does), as described
earlier.

### Correct incremental rebuilds {:#correct-incremental-rebuilds}

One of the primary goals of the Bazel project is to ensure correct incremental
rebuilds. Previous build tools, especially those based on Make, make several
unsound assumptions in their implementation of incremental builds.

Firstly, that timestamps of files increase monotonically. While this is the
typical case, it is very easy to fall afoul of this assumption; syncing to an
earlier revision of a file causes that file's modification time to decrease;
Make-based systems will not rebuild.

More generally, while Make detects changes to files, it does not detect changes
to commands. If you alter the options passed to the compiler in a given build
step, Make will not re-run the compiler, and it is necessary to manually discard
the invalid outputs of the previous build using `make clean`.

Also, Make is not robust against the unsuccessful termination of one of its
subprocesses after that subprocess has started writing to its output file. While
the current execution of Make will fail, the subsequent invocation of Make will
blindly assume that the truncated output file is valid (because it is newer than
its inputs), and it will not be rebuilt. Similarly, if the Make process is
killed, a similar situation can occur.

Bazel avoids these assumptions, and others. Bazel maintains a database of all
work previously done, and will only omit a build step if it finds that the set
of input files (and their timestamps) to that build step, and the compilation
command for that build step, exactly match one in the database, and, that the
set of output files (and their timestamps) for the database entry exactly match
the timestamps of the files on disk. Any change to the input files or output
files, or to the command itself, will cause re-execution of the build step.

The benefit to users of correct incremental builds is: less time wasted due to
confusion. (Also, less time spent waiting for rebuilds caused by use of `make
clean`, whether necessary or pre-emptive.)

#### Build consistency and incremental builds {:#build-consistency}

Formally, we define the state of a build as _consistent_ when all the expected
output files exist, and their contents are correct, as specified by the steps or
rules required to create them. When you edit a source file, the state of the
build is said to be _inconsistent_, and remains inconsistent until you next run
the build tool to successful completion. We describe this situation as _unstable
inconsistency_, because it is only temporary, and consistency is restored by
running the build tool.

There is another kind of inconsistency that is pernicious: _stable
inconsistency_. If the build reaches a stable inconsistent state, then repeated
successful invocation of the build tool does not restore consistency: the build
has gotten "stuck", and the outputs remain incorrect. Stable inconsistent states
are the main reason why users of Make (and other build tools) type `make clean`.
Discovering that the build tool has failed in this manner (and then recovering
from it) can be time consuming and very frustrating.

Conceptually, the simplest way to achieve a consistent build is to throw away
all the previous build outputs and start again: make every build a clean build.
This approach is obviously too time-consuming to be practical (except perhaps
for release engineers), and therefore to be useful, the build tool must be able
to perform incremental builds without compromising consistency.

Correct incremental dependency analysis is hard, and as described above, many
other build tools do a poor job of avoiding stable inconsistent states during
incremental builds. In contrast, Bazel offers the following guarantee: after a
successful invocation of the build tool during which you made no edits, the
build will be in a consistent state. (If you edit your source files during a
build, Bazel makes no guarantee about the consistency of the result of the
current build. But it does guarantee that the results of the _next_ build will
restore consistency.)

As with all guarantees, there comes some fine print: there are some known ways
of getting into a stable inconsistent state with Bazel. We won't guarantee to
investigate such problems arising from deliberate attempts to find bugs in the
incremental dependency analysis, but we will investigate and do our best to fix
all stable inconsistent states arising from normal or "reasonable" use of the
build tool.

If you ever detect a stable inconsistent state with Bazel, please report a bug.

#### Sandboxed execution {:#sandboxed-execution}

Note: Sandboxing is enabled by default for local execution.

Bazel uses sandboxes to guarantee that actions run hermetically and
correctly. Bazel runs _spawns_ (loosely speaking: actions) in sandboxes that
only contain the minimal set of files the tool requires to do its job. Currently
sandboxing works on Linux 3.12 or newer with the `CONFIG_USER_NS` option
enabled, and also on macOS 10.11 or newer.

Bazel will print a warning if your system does not support sandboxing to alert
you to the fact that builds are not guaranteed to be hermetic and might affect
the host system in unknown ways. To disable this warning you can pass the
`--ignore_unsupported_sandboxing` flag to Bazel.

Note: Hermeticity means that the action only uses its declared input
files and no other files in the filesystem, and it only produces its declared
output files. See [Hermeticity](/basics/hermeticity) for more details.

On some platforms such as [Google Kubernetes
Engine](https://cloud.google.com/kubernetes-engine/){: .external} cluster nodes or Debian,
user namespaces are deactivated by default due to security
concerns. This can be checked by looking at the file
`/proc/sys/kernel/unprivileged_userns_clone`: if it exists and contains a 0,
then user namespaces can be activated with
`sudo sysctl kernel.unprivileged_userns_clone=1`.

In some cases, the Bazel sandbox fails to execute rules because of the system
setup. The symptom is generally a failure that output a message similar to
`namespace-sandbox.c:633: execvp(argv[0], argv): No such file or directory`.
In that case, try to deactivate the sandbox for genrules with
`--strategy=Genrule=standalone` and for other rules with
`--spawn_strategy=standalone`. Also please report a bug on our
issue tracker and mention which Linux distribution you're using so that we can
investigate and provide a fix in a subsequent release.

### Phases of a build {:#build-phases}

In Bazel, a build occurs in three distinct phases; as a user, understanding the
difference between them provides insight into the options which control a build
(see below).

#### Loading phase {:#loading}

The first is **loading** during which all the necessary BUILD files for the
initial targets, and their transitive closure of dependencies, are loaded,
parsed, evaluated and cached.

For the first build after a Bazel server is started, the loading phase typically
takes many seconds as many BUILD files are loaded from the file system. In
subsequent builds, especially if no BUILD files have changed, loading occurs
very quickly.

Errors reported during this phase include: package not found, target not found,
lexical and grammatical errors in a BUILD file, and evaluation errors.

#### Analysis phase {:#analysis}

The second phase, **analysis**, involves the semantic analysis and validation of
each build rule, the construction of a build dependency graph, and the
determination of exactly what work is to be done in each step of the build.

Like loading, analysis also takes several seconds when computed in its entirety.
However, Bazel caches the dependency graph from one build to the next and only
reanalyzes what it has to, which can make incremental builds extremely fast in
the case where the packages haven't changed since the previous build.

Errors reported at this stage include: inappropriate dependencies, invalid
inputs to a rule, and all rule-specific error messages.

The loading and analysis phases are fast because Bazel avoids unnecessary file
I/O at this stage, reading only BUILD files in order to determine the work to be
done. This is by design, and makes Bazel a good foundation for analysis tools,
such as Bazel's [query](/query/guide) command, which is implemented atop the loading
phase.

#### Execution phase {:#execution}

The third and final phase of the build is **execution**. This phase ensures that
the outputs of each step in the build are consistent with its inputs, re-running
compilation/linking/etc. tools as necessary. This step is where the build spends
the majority of its time, ranging from a few seconds to over an hour for a large
build. Errors reported during this phase include: missing source files, errors
in a tool executed by some build action, or failure of a tool to produce the
expected set of outputs.
