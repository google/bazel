<html devsite>
<head>
  <meta name="project_path" value="/_project.yaml">
  <meta name="book_path" value="/_book.yaml">
</head>
<body>

# Bazel flag cheat sheet

Navigating Bazel's extensive list of command line flags can be a challenge.
This page focuses on the most crucial flags you'll need to know.

<style>

table {
  width: 100%;
}
.flag {
  width: 28%'
  align: left;
}
.description {
  width: 72%;
  align:left;
}

</style>

<aside class="tip">
  <b>Tip:</b> Select the flag name in table to navigate to its entry in the
  command line reference.
</aside>

## Useful general options {:#useful-command}

The following flags are meant to be set explicitly on the command line.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
      <h3 id="flag-config" data-text="config"><code><a href="https://bazel.build/reference/command-line-reference#flag--config">--config</a></code></h3>
    </td>
    <td>

You can organize flags in a <strong>.bazelrc</strong> file into configurations,
like ones for debugging or release builds. Additional configuration groups can
be selected with <code>--config=<strong>&lt;group&gt;</strong></code>.

</td>

  </tr>

  <tr>
    <td>
      <h3 id="flag-keep-going" data-text="keep_going"><code><a href="https://bazel.build/reference/command-line-reference#flag--keep_going">--keep_going</a></code></h3>
    </td>
    <td>

Bazel should try as much as possible to continue with build and test execution.
By default, Bazel fails eagerly.

    </td>
  </tr>

  <tr>
    <td>
      <h3 id="flag-remote-download-outputs" data-text="remote_download_outputs"><code><a href="https://bazel.build/reference/command-line-reference#flag--remote_download_outputs">--remote_download_outputs</a></code></h3>
    </td>
    <td>

When using remote execution or caching (both disk and remote), you can signal to
Bazel that you
want to download <strong>all</strong> (intermediate) build artifacts as follows:

<pre class="prettyprint lang-sh">
--remote_download_outputs=<strong>all</strong>
</pre>

By default, Bazel only downloads top-level artifacts, such as the final binary,
and intermediate artifacts that are necessary for local actions.

</td>

  </tr>

  <tr>
    <td>
      <h3 id="flag-stamp" data-text="stamp"><code><a href="https://bazel.build/reference/command-line-reference#flag--stamp">--stamp</a></code></h3>
    </td>
    <td>

Adds build info (user, timestamp) to binaries.

<aside class="note">
  <b>Note:</b> Because this increases build time, it's only intended for release
  builds.
</aside>

</td>
</tr>
</table>

## Uncover Build & Test Issues {:#uncover-build}

The following flags can help you better understand Bazel build or test errors.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
       <h3 id="flag-announce-rc" data-text="announce_rc"><code><a href="https://bazel.build/reference/command-line-reference#flag--announce_rc">--announce_rc</a></code></h3>
    </td>
    <td>

Shows which flags are implicitly set through user-defined,
machine-defined, or project-defined <strong>.bazelrc</strong> files.

</td>

  </tr>

  <tr>
    <td>
      <h3 id="flag-auto-output-filter" data-text="auto_output_filter"><code><a href="https://bazel.build/reference/command-line-reference#flag--auto_output_filter">--auto_output_filter</a></code></h3>
    </td>
    <td>

By default, Bazel tries to prevent log spam and does only print compiler
warnings and Starlark debug output for packages and subpackages requested on the
command line. To disable all filtering, set
<code>--auto_output_filter=<strong>none</strong></code>.

</td>

  </tr>

  <tr>
    <td>
      <h3 id="flag-sandbox-debug" data-text="sandbox_debug"><code><a href="https://bazel.build/reference/command-line-reference#flag--sandbox_debug">--sandbox_debug</a></code></h3>
    </td>
    <td>

Lets you drill into sandboxing errors. For details on why Bazel sandboxes
builds by default and what gets sandboxed, see our
<a href="https://bazel.build/docs/sandboxing">sandboxing documentation</a>.

<aside class="tip">
  <b>Tip:</b> If you think the error might be caused by sandboxing,
  try turning sandboxing off temporarily.

  <p>To do this, add <code>--spawn_strategy=<strong>standalone</strong></code>
  to your command.</p>

</aside>

</td>

  </tr>

  <tr>
    <td>
      <h3 id="flag-subcommands" data-text="subcommands"><code><a href="https://bazel.build/reference/command-line-reference#flag--subcommands">--subcommands (-s)</a></code></h3>
    </td>
    <td>

Displays a comprehensive list of every command that Bazel runs during a build,
regardless of whether it succeeds or fails

</td>
 </tr>
 </table>

## Startup {:#startup}

Caution: Startup flags need to be passed before the command and cause
a server restart. Toggle these flags with caution.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
       <h3 id="flag-bazelrc" data-text="bazelrc"><code><a href="https://bazel.build/reference/command-line-reference#flag--bazelrc">--bazelrc</a></code></h3>
    </td>
    <td>

You can specify default Bazel options in <strong>.bazelrc</strong> files. If
multiple <strong>.bazelrc</strong> files exist, you can select which
<strong>.bazelrc</strong> file is used by adding <code>--bazelrc=<strong>&lt;path to
the .bazelrc file&gt;</strong></code>.

<aside class="tip">
  <b>Tip:</b> <code>--bazelrc=<strong>dev/null</strong></code> disables the
  search for <strong>.bazelrc</strong> files.

  <p>This is ideal for scenarios where you want to ensure a clean build
  environment, such as release builds, and prevent any unintended configuration
  changes from <strong>.bazelrc</strong> files</p>

</aside>
</td>
</tr>

  <tr>
    <td>
    <h3 id="flag-host-jvm-args" data-text="host_jvm_args"><code><a href="https://bazel.build/docs/user-manual#host-jvm-args">--host_jvm_args</a></code></h3>
    </td>
    <td>

Limits the amount of RAM the Bazel server uses.

For example, the following limits the Bazel heap size to <strong>3</strong>GB:

<pre class="prettyprint lang-sh">
--host_jvm_args=<strong>-Xmx3g</strong>
</pre>

<aside class="note">
  <b>Note:</b> <code>-Xmx</code> is used to set the maximum heap size for the
  Java Virtual Machine (JVM). The heap is the area of memory where objects are
  allocated. The correct format for this option is <code>-Xmx&lt;size&gt;</code>
  , where <code>&lt;size&gt;</code> is the maximum heap size, specified with a
  unit such as:

<ul>
  <li>m for megabytes</li>
  <li>g for gigabytes</li>
  <li>k for kilobytes</li>
</ul>

</aside>

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-output-base" data-text="output_base"><code><a href="https://bazel.build/reference/command-line-reference#flag--output_base">--output_base</a></code></h3>
    </td>
    <td>

Controls Bazel's output tree. Bazel doesn't store build outputs, including logs,
within the source tree itself. Instead, it uses a distinct output tree for this
purpose.

<aside class="tip">
  <b>Tip:</b> Using multiple output bases in one Bazel workspace lets you run
multiple Bazel servers concurrently. This can be useful when trying to avoid
analysis thrashing. For more information,
see <a href="https://bazel.build/run/scripts#output-base-option">Choosing the output base</a>.
</aside>

</td>

  </tr>
 </table>

## Bazel tests {:#bazel-tests}

The following flags are related to Bazel test

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
       <h3 id="flag-java-debug" data-text="java_debug"><code><a href="https://bazel.build/reference/command-line-reference#flag--java_debug">--java_debug</a></code></h3>
    </td>
    <td>

Causes Java tests to wait for a debugger connection before being executed.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-runs-per-test" data-text="runs_per_test"><code><a href="https://bazel.build/reference/command-line-reference#flag--runs_per_test">--runs_per_test</a></code></h3>
    </td>
    <td>

The number of times to run tests. For example, to run tests N times, add
<code>--runs_per_test=<strong>N</strong></code>. This can be useful to debug
flaky tests and see whether a fix causes a test to pass consistently.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-test-filter" data-text="test_filter"><code><a href="https://bazel.build/reference/command-line-reference#flag--test_filter">--test_filter</a></code></h3>
    </td>
    <td>

This flag is particularly useful when iterating on a single test method, such as
when a change you made breaks a test. Instead of re-running
all the test methods in the test suite, you can focus solely on the specific
test(s) that failed. This allows for faster feedback and more efficient
debugging. This flag is often used in conjunction with
<code>--test_output=<strong>streamed</strong></code> for real-time test output.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-test-output" data-text="test_output"><code><a href="https://bazel.build/reference/command-line-reference#flag--test_output">--test_output</a></code></h3>
    </td>
    <td>

Specifies the output mode. By default, Bazel captures test output in
local log files. When iterating on a broken test, you typically want to use
<code>--test_output=<strong>streamed</strong></code> to see the test output in
real time.

</td>

  </tr>
 </table>

## Bazel run {:#bazel-run}

The following flags are related to Bazel run.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
        <h3 id="flag-run-under" data-text="run_under"><code><a href="https://bazel.build/reference/command-line-reference#flag--run_under">--run_under</a></code></h3>
    </td>
    <td>

Changes how executables are invoked. For example <code>--run_under=<strong>"strace -c"</strong></code> is
commonly used for debugging.

</td>

  </tr>

 </table>

## User-specific bazelrc options {:#user-specific-bazelrc}

The following flags are related to user-specific **.bazelrc**
options.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
       <h3 id="flag-disk-cache" data-text="disk_cache"><code><a href="https://bazel.build/reference/command-line-reference#flag--disk_cache">--disk_cache</a></code></h3>
    </td>
    <td>

A path to a directory where Bazel can read and write actions and action outputs.
If the directory doesn't exist, it will be created.

You can share build artifacts between multiple branches or workspaces and speed
up Bazel builds by adding
<code>--disk_cache=<strong>&lt;path&gt;</strong></code> to your command.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-jobs" data-text="jobs"><code><a href="https://bazel.build/reference/command-line-reference#flag--jobs">--jobs</a></code></h3>
    </td>
    <td>

The number of concurrent jobs to run.

This is typically only required when using remote execution where a remote build
cluster executes more jobs than you have cores locally.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-local-resources" data-text="local_resources"><code><a href="https://bazel.build/reference/command-line-reference#flag--local_resources">--local_resources</a></code></h3>
    </td>
    <td>

Limits how much CPU or RAM is consumed by locally running actions.

<aside class="note">
  <b>Note:</b> This has no impact on the amount of CPU or RAM that the Bazel
  server itself consumes for tasks like analysis and build orchestration.
</aside>

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-sandbox-base" data-text="sandbox_base"><code><a href="https://bazel.build/reference/command-line-reference#flag--sandbox_base">--sandbox_base</a></code></h3>
    </td>
    <td>

Lets the sandbox create its sandbox directories underneath this path. By
default, Bazel executes local actions sandboxed which adds some overhead to the
build.

<aside class="tip">
  <b>Tip:</b> Specify a path on tmpfs, for example <code>/run/shm</code>, to
  possibly improve performance a lot when your build or tests have many input
  files.
</aside>

</td>
  </tr>
 </table>

## Project-specific bazelrc options {:#project-specific-bazelrc}

The following flags are related to project-specific <strong>.bazelrc</strong>
options.

<table>
  <tr>
    <th class="flag" >Flag</th>
    <th class="description" >Description</th>
  </tr>

  <tr>
    <td>
       <h3 id="flag-flaky-test-attempts" data-text="flaky_test_attempts"><code><a href="https://bazel.build/reference/command-line-reference#flag--flaky_test_attempts">--flaky_test_attempts</a></code></h3>
    </td>
    <td>

Retry each test up to the specified number of times in case of any
test failure. This is especially useful on Continuous Integration. Tests that
require more than one attempt to pass are marked as <strong>FLAKY</strong> in
the test summary.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-remote-cache" data-text="remote_cache"><code><a href="https://bazel.build/reference/command-line-reference#flag--remote_cache">--remote_cache</a></code></h3>
    </td>
    <td>

A URI of a caching endpoint. Setting up remote caching can be a great way to
speed up Bazel builds. It can be combined with a local disk cache.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-remote-download-regex" data-text="remote_download_regex"><code><a href="https://bazel.build/reference/command-line-reference#flag--remote_download_regex">--remote_download_regex</a></code></h3>
    </td>
    <td>

Force remote build outputs whose path matches this pattern to be downloaded,
irrespective of the <code>--remote_download_outputs</code> setting. Multiple
patterns may be specified by repeating this flag.

</td>

  </tr>

  <tr>
    <td>
     <h3 id="flag-remote-executor" data-text="remote_executor"><code><a href="https://bazel.build/reference/command-line-reference#flag--remote_executor">--remote_executor</a></code></h3>
    </td>
    <td>

<code>HOST</code> or <code>HOST:PORT</code> of a remote execution endpoint. Pass this if you are using
a remote execution service. You'll often need to Add
<code>--remote_instance_name=<strong>&lt;name&gt;</strong></code>.

</td>

  </tr>

  <tr>
    <td>
     <h3 id="flag-remote-instance-name" data-text="remote_instance_name"><code><a href="https://bazel.build/reference/command-line-reference#flag--remote_instance_name">--remote_instance_name</a></code></h3>
    </td>
    <td>

The value to pass as <code>instance_name</code> in the remote execution API.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-show-timestamps" data-text="show-timestamps"><code><a href="https://bazel.build/docs/user-manual#show-timestamps">--show-timestamps</a></code></h3>
    </td>
    <td>

If specified, a timestamp is added to each message generated by Bazel specifying
the time at which the message was displayed. This is useful on CI systems to
quickly understand what step took how long.

</td>

  </tr>

  <tr>
    <td>
    <h3 id="flag-spawn-strategy" data-text="spawn_strategy"><code><a href="https://bazel.build/reference/command-line-reference#flag--spawn_strategy">--spawn_strategy</a></code></h3>
    </td>
    <td>

Even with remote execution, running some build actions locally might be faster.
This depends on factors like your build cluster's capacity, network speed, and
network delays.

<aside class="tip">
  <b>Tip:</b> To run actions both locally and remotely and accept
the faster result add <code>--spawn_strategy=<strong>dynamic</strong></code>
to your build command.
</aside>

</td>

  </tr>

 </table>
</body>
</html>