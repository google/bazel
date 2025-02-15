Project: /_project.yaml
Book: /_book.yaml

# Test encyclopedia

{% include "_buttons.html" %}

An exhaustive specification of the test execution environment.

## Background {:#background}

The Bazel BUILD language includes rules which can be used to define automated
test programs in many languages.

Tests are run using [`bazel test`](/docs/user-manual#test).

Users may also execute test binaries directly. This is allowed but not endorsed,
as such an invocation will not adhere to the mandates described below.

Tests should be *hermetic*: that is, they ought to access only those resources
on which they have a declared dependency. If tests are not properly hermetic
then they do not give historically reproducible results. This could be a
significant problem for culprit finding (determining which change broke a test),
release engineering auditability, and resource isolation of tests (automated
testing frameworks ought not DDOS a server because some tests happen to talk to
it).

## Objective {:#objective}

The goal of this page is to formally establish the runtime environment for and
expected behavior of Bazel tests. It will also impose requirements on the test
runner and the build system.

The test environment specification helps test authors avoid relying on
unspecified behavior, and thus gives the testing infrastructure more freedom to
make implementation changes. The specification tightens up some holes that
currently allow many tests to pass despite not being properly hermetic,
deterministic, and reentrant.

This page is intended to be both normative and authoritative. If this
specification and the implemented behavior of test runner disagree, the
specification takes precedence.

## Proposed Specification {:#proposed-specification}

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD",
"SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" are to be interpreted as
described in IETF RFC 2119.

## Purpose of tests {:#purpose-of-tests}

The purpose of Bazel tests is to confirm some property of the source files
checked into the repository. (On this page, "source files" includes test data,
golden outputs, and anything else kept under version control.) One user writes a
test to assert an invariant which they expect to be maintained. Other users
execute the test later to check whether the invariant has been broken. If the
test depends on any variables other than source files (non-hermetic), its value
is diminished, because the later users cannot be sure their changes are at fault
when the test stops passing.

Therefore the outcome of a test must depend only on:

*   source files on which the test has a declared dependency
*   products of the build system on which the test has a declared dependency
*   resources whose behavior is guaranteed by the test runner to remain constant

Currently, such behavior is not enforced. However, test runners reserve the
right to add such enforcement in the future.

## Role of the build system {:#role-build-system}

Test rules are analogous to binary rules in that each must yield an executable
program. For some languages, this is a stub program which combines a
language-specific harness with the test code. Test rules must produce other
outputs as well. In addition to the primary test executable, the test runner
will need a manifest of **runfiles**, input files which should be made available
to the test at runtime, and it may need information about the type, size, and
tags of a test.

The build system may use the runfiles to deliver code as well as data. (This
might be used as an optimization to make each test binary smaller by sharing
files across tests, such as through the use of dynamic linking.) The build system
should ensure that the generated executable loads these files via the runfiles
image provided by the test runner, rather than hardcoded references to absolute
locations in the source or output tree.

## Role of the test runner {:#role-test-runner}

From the point of view of the test runner, each test is a program which can be
invoked with `execve()`. There may be other ways to execute tests; for example,
an IDE might allow the execution of Java tests in-process. However, the result
of running the test as a standalone process must be considered authoritative. If
a test process runs to completion and terminates normally with an exit code of
zero, the test has passed. Any other result is considered a test failure. In
particular, writing any of the strings `PASS` or `FAIL` to stdout has no
significance to the test runner.

If a test takes too long to execute, exceeds some resource limit, or the test
runner otherwise detects prohibited behavior, it may choose to kill the test and
treat the run as a failure. The runner must not report the test as passing after
sending a signal to the test process or any children thereof.

The whole test target (not individual methods or tests) is given a limited
amount of time to run to completion. The time limit for a test is based on its
[`timeout`](/reference/be/common-definitions#test.timeout) attribute according
to the following table:

<table>
  <tr>
    <th>timeout</th>
    <th>Time Limit (sec.)</th>
  </tr>
  <tr>
    <td>short</td>
    <td>60</td>
  </tr>
  <tr>
    <td>moderate</td>
    <td>300</td>
  </tr>
  <tr>
    <td>long</td>
    <td>900</td>
  </tr>
  <tr>
    <td>eternal</td>
    <td>3600</td>
  </tr>
</table>

Tests which do not explicitly specify a timeout have one implied based on the
test's [`size`](/reference/be/common-definitions#test.size) as follows:

<table>
  <tr>
    <th>size</th>
    <th>Implied timeout label</th>
  </tr>
  <tr>
    <td>small</td>
    <td>short</td>
  </tr>
  <tr>
    <td>medium</td>
    <td>moderate</td>
  </tr>
  <tr>
    <td>large</td>
    <td>long</td>
  </tr>
  <tr>
    <td>enormous</td>
    <td>eternal</td>
  </tr>
</table>

A "large" test with no explicit timeout setting will be allotted 900
seconds to run. A "medium" test with a timeout of "short" will be allotted 60
seconds.

Unlike `timeout`, the `size` additionally determines the assumed peak usage of
other resources (like RAM) when running the test locally, as described in
[Common definitions](/reference/be/common-definitions#common-attributes-tests).

All combinations of `size` and `timeout` labels are legal, so an "enormous" test
may be declared to have a timeout of "short". Presumably it would do some really
horrible things very quickly.

Tests may return arbitrarily fast regardless of timeout. A test is not penalized
for an overgenerous timeout, although a warning may be issued: you should
generally set your timeout as tight as you can without incurring any flakiness.

The test timeout can be overridden with the `--test_timeout` bazel flag when
manually running under conditions that are known to be slow. The
`--test_timeout` values are in seconds. For example, `--test_timeout=120`
sets the test timeout to two minutes.

There is also a recommended lower bound for test timeouts as follows:

<table>
  <tr>
    <th>timeout</th>
    <th>Time minimum (sec.)</th>
  </tr>
  <tr>
    <td>short</td>
    <td>0</td>
  </tr>
  <tr>
    <td>moderate</td>
    <td>30</td>
  </tr>
  <tr>
    <td>long</td>
    <td>300</td>
  </tr>
  <tr>
    <td>eternal</td>
    <td>900</td>
  </tr>
</table>

For example, if a "moderate" test completes in 5.5s, consider setting `timeout =
"short"` or `size = "small"`. Using the bazel `--test_verbose_timeout_warnings`
command line option will show the tests whose specified size is too big.

Test sizes and timeouts are specified in the BUILD file according to the
specification [here](/reference/be/common-definitions#common-attributes-tests). If
unspecified, a test's size will default to "medium".

If the main process of a test exits, but some of its children are still running,
the test runner should consider the run complete and count it as a success or
failure based on the exit code observed from the main process. The test runner
may kill any stray processes. Tests should not leak processes in this fashion.

## Test sharding {:#test-sharding}

Tests can be parallelized via test sharding. See
[`--test_sharding_strategy`](/reference/command-line-reference#flag--test_sharding_strategy)
and [`shard_count`](/reference/be/common-definitions#common-attributes-tests) to
enable test sharding. When sharding is enabled, the test runner is launched once
per shard. The environment variable [`TEST_TOTAL_SHARDS`](#initial-conditions)
is the number of shards, and [`TEST_SHARD_INDEX`](#initial-conditions) is the
shard index, beginning at 0. Runners use this information to select which tests
to run - for example, using a round-robin strategy. Not all test runners support
sharding. If a runner supports sharding, it must create or update the last
modified date of the file specified by
[`TEST_SHARD_STATUS_FILE`](#initial-conditions). Otherwise, if
[`--incompatible_check_sharding_support`](/reference/command-line-reference#flag--incompatible_check_sharding_support)
is enabled, Bazel will fail the test if it is sharded.

## Initial conditions {:#initial-conditions}

When executing a test, the test runner must establish certain initial
conditions.

The test runner must invoke each test with the path to the test executable in
`argv[0]`. This path must be relative and beneath the test's current directory
(which is in the runfiles tree, see below). The test runner should not pass any
other arguments to a test unless the user explicitly requests it.

The initial environment block shall be composed as follows:

<table>
  <tr>
    <th>Variable</th>
    <th>Value</th>
    <th>Status</th>
  </tr>
  <tr>
    <td><code>HOME</code></td>
    <td>value of <code>$TEST_TMPDIR</code></td>
    <td>recommended</td>
  </tr>
  <tr>
    <td><code>LANG</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LANGUAGE</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_ALL</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_COLLATE</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_CTYPE</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_MESSAGES</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_MONETARY</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_NUMERIC</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LC_TIME</code></td>
    <td><em>unset</em></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>LD_LIBRARY_PATH</code></td>
    <td>colon-separated list of directories containing shared libraries</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>JAVA_RUNFILES</code></td>
    <td>value of <code>$TEST_SRCDIR</code></td>
    <td>deprecated</td>
  </tr>
  <tr>
    <td><code>LOGNAME</code></td>
    <td>value of <code>$USER</code></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>PATH</code></td>
    <td><code>/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin:.</code></td>
    <td>recommended</td>
  </tr>
  <tr>
    <td><code>PWD</code></td>
    <td><code>$TEST_SRCDIR/<var>workspace-name</var></code></td>
    <td>recommended</td>
  </tr>
  <tr>
    <td><code>SHLVL</code></td>
    <td><code>2</code></td>
    <td>recommended</td>
  </tr>
  <tr>
    <td><code>TEST_INFRASTRUCTURE_FAILURE_FILE</code></td>
    <td>absolute path to a private file in a writable directory (This file
      should only be used to report failures originating from the testing
      infrastructure, not as a general mechanism for reporting flaky failures
      of tests. In this context, testing infrastructure is defined as systems
      or libraries that are not test-specific, but can cause test failures by
      malfunctioning. The first line is the name of the testing infrastructure
      component that caused the failure, the second one a human-readable
      description of the failure. Additional lines are ignored.)</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_LOGSPLITTER_OUTPUT_FILE</code></td>
    <td>absolute path to a private file in a writable directory (used to write
      Logsplitter protobuffer log)</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_PREMATURE_EXIT_FILE</code></td>
    <td>absolute path to a private file in a writable directory (used for
      catching calls to <code>exit()</code>)</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_RANDOM_SEED</code></td>
    <td>If the <code>--runs_per_test</code> option is used,
      <code>TEST_RANDOM_SEED</code> is set to the <var>run number</var>
      (starting with 1) for each individual test run.</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_RUN_NUMBER</code></td>
    <td>If the <code>--runs_per_test</code> option is used,
      <code>TEST_RUN_NUMBER</code> is set to the <var>run number</var>
      (starting with 1) for each individual test run.</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_TARGET</code></td>
    <td>The name of the target being tested</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_SIZE</code></td>
    <td>The test <a href="#size"><code>size</code></a></td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_TIMEOUT</code></td>
    <td>The test <a href="#timeout"><code>timeout</code></a> in seconds</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_SHARD_INDEX</code></td>
    <td>shard index, if <a href="#test-sharding"><code>sharding</code></a> is used</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_SHARD_STATUS_FILE</code></td>
    <td>path to file to touch to indicate support for <a href="#test-sharding"><code>sharding</code></a></td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_SRCDIR</code></td>
    <td>absolute path to the base of the runfiles tree</td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>TEST_TOTAL_SHARDS</code></td>
    <td>total
      <a href="/reference/be/common-definitions#test.shard_count"><code>shard count</code></a>,
      if <a href="#test-sharding"><code>sharding</code></a> is used</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_TMPDIR</code></td>
    <td>absolute path to a private writable directory</td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>TEST_WORKSPACE</code></td>
    <td>the local repository's workspace name</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_UNDECLARED_OUTPUTS_DIR</code></td>
    <td>absolute path to a private writable directory (used to write undeclared
      test outputs). Any files written to the
      <code>TEST_UNDECLARED_OUTPUTS_DIR</code> directory will be zipped up and
      added to an <code>outputs.zip</code> file under
      <code>bazel-testlogs</code>.</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR</code></td>
    <td>absolute path to a private writable directory (used to write undeclared
      test output annotation <code>.part</code> and <code>.pb</code> files).</td>
    <td>optional</td>
  </tr>

  <tr>
    <td><code>TEST_WARNINGS_OUTPUT_FILE</code></td>
    <td>absolute path to a private file in a writable directory (used to write
      test target warnings)</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TESTBRIDGE_TEST_ONLY</code></td>
    <td>value of
      <a href="/docs/user-manual#flag--test_filter"><code>--test_filter</code></a>,
      if specified</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>TZ</code></td>
    <td><code>UTC</code></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>USER</code></td>
    <td>value of <code>getpwuid(getuid())-&gt;pw_name</code></td>
    <td>required</td>
  </tr>
  <tr>
    <td><code>XML_OUTPUT_FILE</code></td>
    <td>
      Location to which test actions should write a test result XML output file.
      Otherwise, Bazel generates a default XML output file wrapping the test log
      as part of the test action. The XML schema is based on the
      <a href="https://windyroad.com.au/dl/Open%20Source/JUnit.xsd"
        class="external">JUnit test result schema</a>.</td>
    <td>optional</td>
  </tr>
  <tr>
    <td><code>BAZEL_TEST</code></td>
    <td>Signifies test executable is being driven by <code>bazel test</code></td>
    <td>required</td>
  </tr>
</table>

The environment may contain additional entries. Tests should not depend on the
presence, absence, or value of any environment variable not listed above.

The initial working directory shall be `$TEST_SRCDIR/$TEST_WORKSPACE`.

The current process id, process group id, session id, and parent process id are
unspecified. The process may or may not be a process group leader or a session
leader. The process may or may not have a controlling terminal. The process may
have zero or more running or unreaped child processes. The process should not
have multiple threads when the test code gains control.

File descriptor 0 (`stdin`) shall be open for reading, but what it is attached to
is unspecified. Tests must not read from it. File descriptors 1 (`stdout`) and 2
(`stderr`) shall be open for writing, but what they are attached to is
unspecified. It could be a terminal, a pipe, a regular file, or anything else to
which characters can be written. They may share an entry in the open file table
(meaning that they cannot seek independently). Tests should not inherit any
other open file descriptors.

The initial umask shall be `022` or `027`.

No alarm or interval timer shall be pending.

The initial mask of blocked signals shall be empty. All signals shall be set to
their default action.

The initial resource limits, both soft and hard, should be set as follows:

<table>
  <tr>
    <th>Resource</th>
    <th>Limit</th>
  </tr>
  <tr>
    <td><code>RLIMIT_AS</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_CORE</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_CPU</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_DATA</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_FSIZE</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_LOCKS</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_MEMLOCK</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_MSGQUEUE</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_NICE</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_NOFILE</code></td>
    <td>at least 1024</td>
  </tr>
  <tr>
    <td><code>RLIMIT_NPROC</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_RSS</code></td>
    <td>unlimited</td>
  </tr>
  <tr>
    <td><code>RLIMIT_RTPRIO</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_SIGPENDING</code></td>
    <td>unspecified</td>
  </tr>
  <tr>
    <td><code>RLIMIT_STACK</code></td>
    <td>unlimited, or 2044KB &lt;= rlim &lt;= 8192KB</td>
  </tr>
</table>

The initial process times (as returned by `times()`) and resource utilization
(as returned by `getrusage()`) are unspecified.

The initial scheduling policy and priority are unspecified.

## Role of the host system {:#role-host-system}

In addition to the aspects of user context under direct control of the test
runner, the operating system on which tests execute must satisfy certain
properties for a test run to be valid.

#### Filesystem {:#filesystem}

The root directory observed by a test may or may not be the real root directory.

`/proc` shall be mounted.

All build tools shall be present at the absolute paths under `/usr` used by a
local installation.

Paths starting with `/home` may not be available. Tests should not access any
such paths.

`/tmp` shall be writable, but tests should avoid using these paths.

Tests must not assume that any constant path is available for their exclusive
use.

Tests must not assume that atimes are enabled for any mounted filesystem.

#### Users and groups {:#users-groups}

The users root, nobody, and unittest must exist. The groups root, nobody, and
eng must exist.

Tests must be executed as a non-root user. The real and effective user ids must
be equal; likewise for group ids. Beyond this, the current user id, group id,
user name, and group name are unspecified. The set of supplementary group ids is
unspecified.

The current user id and group id must have corresponding names which can be
retrieved with `getpwuid()` and `getgrgid()`. The same may not be true for
supplementary group ids.

The current user must have a home directory. It may not be writable. Tests must
not attempt to write to it.

#### Networking {:#networking}

The hostname is unspecified. It may or may not contain a dot. Resolving the
hostname must give an IP address of the current host. Resolving the hostname cut
after the first dot must also work. The hostname localhost must resolve.

#### Other resources {:#other-resources}

Tests are granted at least one CPU core. Others may be available but this is not
guaranteed. Other performance aspects of this core are not specified. You can
increase the reservation to a higher number of CPU cores by adding the tag
"cpu:n" (where n is a positive number) to a test rule. If a machine has less
total CPU cores than requested, Bazel will still run the test. If a test uses
[sharding](#test-sharding), each individual shard will reserve the number of CPU
cores specified here.

Tests may create subprocesses, but not process groups or sessions.

There is a limit on the number of input files a test may consume. This limit is
subject to change, but is currently in the range of tens of thousands of inputs.

#### Time and date {:#time-and-date}

The current time and date are unspecified. The system timezone is unspecified.

X Windows may or may not be available. Tests that need an X server should start
Xvfb.

## Test interaction with the filesystem {:#test-interaction-filesystem}

All file paths specified in test environment variables point to somewhere on the
local filesystem, unless otherwise specified.

Tests should create files only within the directories specified by
`$TEST_TMPDIR` and `$TEST_UNDECLARED_OUTPUTS_DIR` (if set).

These directories will be initially empty.

Tests must not attempt to remove, chmod, or otherwise alter these directories.

These directories may be a symbolic links.

The filesystem type of `$TEST_TMPDIR/.` remains unspecified.

Tests may also write .part files to the
`$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR` to annotate undeclared output files.

In rare cases, a test may be forced to create files in `/tmp`. For example,
[path length limits for Unix domain sockets](https://serverfault.com/questions/641347){: .external}
typically require creating the socket under `/tmp`. Bazel will be unable to
track such files; the test itself must take care to be hermetic, to use unique
paths to avoid colliding with other, simultaneously running tests and non-test
processes, and to clean up the files it creates in `/tmp`.

Some popular testing frameworks, such as
[JUnit4 `TemporaryFolder`](https://junit.org/junit4/javadoc/latest/org/junit/rules/TemporaryFolder.html){: .external}
or [Go `TempDir`](https://golang.org/pkg/testing/#T.TempDir){: .external}, have
their own ways to create a temporary directory under `/tmp`. These testing
frameworks include functionality that cleans up files in `/tmp`, so you may use
them even though they create files outside of `TEST_TMPDIR`.

Tests must access inputs through the **runfiles** mechanism, or other parts of
the execution environment which are specifically intended to make input files
available.

Tests must not access other outputs of the build system at paths inferred from
the location of their own executable.

It is unspecified whether the runfiles tree contains regular files, symbolic
links, or a mixture. The runfiles tree may contain symlinks to directories.
Tests should avoid using paths containing `..` components within the runfiles
tree.

No directory, file, or symlink within the runfiles tree (including paths which
traverse symlinks) should be writable. (It follows that the initial working
directory should not be writable.) Tests must not assume that any part of the
runfiles is writable, or owned by the current user (for example, `chmod` and `chgrp` may
fail).

The runfiles tree (including paths which traverse symlinks) must not change
during test execution. Parent directories and filesystem mounts must not change
in any way which affects the result of resolving a path within the runfiles
tree.

In order to catch early exit, a test may create a file at the path specified by
`TEST_PREMATURE_EXIT_FILE` upon start and remove it upon exit. If Bazel sees the
file when the test finishes, it will assume that the test exited prematurely and
mark it as having failed.

## Execution platform {:#execution-platform}

The [execution platform](/extending/platforms) for a test action is determined
via [toolchain resolution](/extending/toolchains#toolchain-resolution), just
like for any other action. Each test rule has an implicitly defined [
`test` exec group](/extending/exec-groups#exec-groups-for-native-rules) that,
unless overridden, has a mandatory toolchain requirement on
`@bazel_tools//tools/test:default_test_toolchain_type`. Toolchains of this type
do not carry any data in the form of providers, but can be used to influence the
execution platform of the test action. By default, Bazel registers two such
toolchains:

* If `--@bazel_tools//tools/test:incompatible_use_default_test_toolchain` is
  disabled (the current default), the active test toolchain is
  `@bazel_tools//tools/test:legacy_test_toolchain`. This toolchain does not
  impose any constraints and thus test actions without manually specified exec
  constraints are configured for the first registered execution platform. This
  is often not the intended behavior in multi-platform builds as it can result
  in e.g. a test binary built for Linux on a Windows machine to be executed on
  Windows.
* If `--@bazel_tools//tools/test:incompatible_use_default_test_toolchain` is
  enabled, the active test toolchain is
  `@bazel_tools//tools/test:default_test_toolchain`. This toolchain requires an
  execution platform to match all the constraints of the test rule's target
  platform. In particular, the target platform is compatible with this toolchain
  if it is also registered as an execution platform. If no such platform is
  found, the test rule fails with a toolchain resolution error.

Users can register additional toolchains for this type to influence this
behavior and their toolchains will take precedence over the default ones.
Test rule authors can define their own test toolchain type and also register
a default toolchain for it.

## Tag conventions {:#tag-conventions}

Some tags in the test rules have a special meaning. See also the
[Bazel Build Encyclopedia on the `tags` attribute](/reference/be/common-definitions#common.tags).

<table>
  <tr>
    <th>Tag</th>
    <th>Meaning</th>
  </tr>
  <tr>
    <th><code>exclusive</code></th>
      <td>run no other test at the same time</td>
    </tr>
    <tr>
      <td><code>external</code></td>
      <td>test has an external dependency; disable test caching</td>
    </tr>
    <tr>
      <td><code>large</code></td>
      <td><code>test_suite</code> convention; suite of large tests</td>
    </tr>
    <tr>
      <td><code>manual *</code></td>
      <td>don't include test target in wildcard target patterns like
        <code>:...</code>, <code>:*</code>, or <code>:all</code></td>
    </tr>
    <tr>
      <td><code>medium</code></td>
      <td><code>test_suite</code> convention; suite of medium tests</td>
    </tr>
    <tr>
      <td><code>small</code></td>
      <td><code>test_suite</code> convention; suite of small tests</td>
    </tr>
    <tr>
      <td><code>smoke</code></td>
      <td><code>test_suite</code> convention; means it should be run before
        committing code changes into the version control system</td>
    </tr>
</table>

Note: bazel `query` does not respect the manual tag.

## Runfiles {:#runfiles}

In the following, assume there is a *_binary() rule labeled
`//foo/bar:unittest`, with a run-time dependency on the rule labeled
`//deps/server:server`.

#### Location {:#runfiles-location}

The runfiles directory for a target `//foo/bar:unittest` is the directory
`$(WORKSPACE)/$(BINDIR)/foo/bar/unittest.runfiles`. This path is referred to as
the `runfiles_dir`.

#### Dependencies {:#runfiles-dependencies}

The runfiles directory is declared as a compile-time dependency of the
`*_binary()` rule. The runfiles directory itself depends on the set of BUILD
files that affect the `*_binary()` rule or any of its compile-time or run-time
dependencies. Modifying source files does not affect the structure of the
runfiles directory, and thus does not trigger any rebuilding.

#### Contents {:#runfiles-contents}

The runfiles directory contains the following:

*   **Symlinks to run-time dependencies**: each OutputFile and CommandRule that
    is a run-time dependency of the `*_binary()` rule is represented by one
    symlink in the runfiles directory. The name of the symlink is
    `$(WORKSPACE)/package_name/rule_name`. For example, the symlink for server
    would be named `$(WORKSPACE)/deps/server/server`, and the full path would be
    `$(WORKSPACE)/foo/bar/unittest.runfiles/$(WORKSPACE)/deps/server/server`.
    The destination of the symlink is the OutputFileName() of the OutputFile or
    CommandRule, expressed as an absolute path. Thus, the destination of the
    symlink might be `$(WORKSPACE)/linux-dbg/deps/server/42/server`.
