Project: /_project.yaml
Book: /_book.yaml

# Using Bazel on Windows

{% include "_buttons.html" %}

This page covers Best Practices for using Bazel on Windows. For installation
instructions, see [Install Bazel on Windows](/install/windows).

## Known issues {:#known-issues}

Windows-related Bazel issues are marked with the "area-Windows" label on GitHub.
[GitHub-Windows].

[GitHub-Windows]: https://github.com/bazelbuild/bazel/issues?q=is%3Aopen+is%3Aissue+label%3Aarea-Windows

## Best practices {:#best-practices}

### Avoid long path issues {:#long-path-issues}

Some tools have the [Maximum Path Length Limitation](https://docs.microsoft.com/en-us/windows/win32/fileio/naming-a-file#maximum-path-length-limitation){: .external} on Windows, including the MSVC compiler.
To avoid hitting this issue, you can specify a short output directory for Bazel by the [\-\-output_user_root](/reference/command-line-reference#flag--output_user_root) flag.

For example, add the following line to your bazelrc file:

```none
startup --output_user_root=C:/tmp
```

### Enable 8.3 filename support {:#filename-support}

Bazel attempts to create a short name version for long file paths. But to do so the [8.3 filename support](https://docs.microsoft.com/en-us/windows-server/administration/windows-commands/fsutil-8dot3name){: .external} needs to be enabled for the volume in which the file with the long path resides. You can enable 8.3 name creation in all volumes by running the following command:

```posix-terminal
fsutil 8dot3name set 0
```

### Enable symlink support {:#symlink}

Some features require Bazel to be able to create file symlinks on Windows,
either by enabling
[Developer Mode](https://docs.microsoft.com/en-us/windows/uwp/get-started/enable-your-device-for-development){: .external}
(on Windows 10 version 1703 or newer), or by running Bazel as an administrator.
This enables the following features:

* [\-\-windows_enable_symlinks](/reference/command-line-reference#flag--windows_enable_symlinks)
* [\-\-enable_runfiles](/reference/command-line-reference#flag--enable_runfiles)

To make it easier, add the following lines to your bazelrc file:

```none
startup --windows_enable_symlinks

build --enable_runfiles
```

**Note**: Creating symlinks on Windows is an expensive operation. The `--enable_runfiles` flag can potentially create a large amount of file symlinks. Only enable this feature when you need it.

<!-- TODO(pcloudy): https://github.com/bazelbuild/bazel/issues/6402
                    Write a doc about runfiles library and add a link to it here -->

### Running Bazel: MSYS2 shell vs. command prompt vs. PowerShell {:#running-bazel-shells}

**Recommendation:** Run Bazel from the command prompt (`cmd.exe`) or from
PowerShell.

As of 2020-01-15, **do not** run Bazel from `bash` -- either
from MSYS2 shell, or Git Bash, or Cygwin, or any other Bash variant. While Bazel
may work for most use cases, some things are broken, like
[interrupting the build with Ctrl+C from MSYS2](https://github.com/bazelbuild/bazel/issues/10573){: .external}).
Also, if you choose to run under MSYS2, you need to disable MSYS2's
automatic path conversion, otherwise MSYS will convert command line arguments
that _look like_ Unix paths (such as `//foo:bar`) into Windows paths. See
[this StackOverflow answer](https://stackoverflow.com/a/49004265/7778502){: .external}
for details.

### Using Bazel without Bash (MSYS2) {:#using-bazel-without-bash}

#### Using bazel build without Bash {:#bazel-build-without-bash}

Bazel versions before 1.0 used to require Bash to build some rules.

Starting with Bazel 1.0, you can build any rule without Bash unless it is a:

- `genrule`, because genrules execute Bash commands
- `sh_binary` or `sh_test` rule, because these inherently need Bash
- Starlark rule that uses `ctx.actions.run_shell()` or `ctx.resolve_command()`

However, `genrule` is often used for simple tasks like
[copying a file](https://github.com/bazelbuild/bazel-skylib/blob/main/rules/copy_file.bzl){: .external}
or [writing a text file](https://github.com/bazelbuild/bazel-skylib/blob/main/rules/write_file.bzl){: .external}.
Instead of using `genrule` (and depending on Bash) you may find a suitable rule
in the
[bazel-skylib repository](https://github.com/bazelbuild/bazel-skylib/tree/main/rules){: .external}.
When built on Windows, **these rules do not require Bash**.

#### Using bazel test without Bash {:#bazel-test-without-bash}

Bazel versions before 1.0 used to require Bash to `bazel test` anything.

Starting with Bazel 1.0, you can test any rule without Bash, except when:

- you use `--run_under`
- the test rule itself requires Bash (because its executable is a shell script)

#### Using bazel run without Bash {:#bazel-run-without-bash}

Bazel versions before 1.0 used to require Bash to `bazel run` anything.

Starting with Bazel 1.0, you can run any rule without Bash, except when:

- you use `--run_under` or `--script_path`
- the test rule itself requires Bash (because its executable is a shell script)

#### Using sh\_binary and sh\_* rules, and ctx.actions.run_shell() without Bash {:#sh-rules-without-bash}

You need Bash to build and test `sh_*` rules, and to build and test Starlark
rules that use `ctx.actions.run_shell()` and `ctx.resolve_command()`. This
applies not only to rules in your project, but to rules in any of the external
repositories your project depends on (even transitively).

In the future, there may be an option to use Windows Subsystem for
Linux (WSL) to build these rules, but currently it is not a priority for
the Bazel-on-Windows subteam.

### Setting environment variables {:#set-environment-variables}

Environment variables you set in the Windows Command Prompt (`cmd.exe`) are only
set in that command prompt session. If you start a new `cmd.exe`, you need to
set the variables again. To always set the variables when `cmd.exe` starts, you
can add them to the User variables or System variables in the `Control Panel >
System Properties > Advanced > Environment Variables...` dialog box.

## Build on Windows {:#using}

### Build C++ with MSVC {:#build_cpp}

To build C++ targets with MSVC, you need:

*   [The Visual C++ compiler](/install/windows#install-vc).

*   (Optional) The `BAZEL_VC` and `BAZEL_VC_FULL_VERSION` environment variable.

    Bazel automatically detects the Visual C++ compiler on your system.
    To tell Bazel to use a specific VC installation, you can set the
    following environment variables:

    For Visual Studio 2017 and 2019, set one of `BAZEL_VC`. Additionally you may also set `BAZEL_VC_FULL_VERSION`.

    *   `BAZEL_VC` the Visual C++ Build Tools installation directory

        ```
        set BAZEL_VC=C:\Program Files (x86)\Microsoft Visual Studio\2017\BuildTools\VC
        ```

    *   `BAZEL_VC_FULL_VERSION` (Optional) Only for Visual Studio 2017 and 2019, the full version
        number of your Visual C++ Build Tools. You can choose the exact Visual C++ Build Tools
        version via `BAZEL_VC_FULL_VERSION` if more than one version are installed, otherwise Bazel
        will choose the latest version.

        ```
        set BAZEL_VC_FULL_VERSION=14.16.27023
        ```

    For Visual Studio 2015 or older, set `BAZEL_VC`. (`BAZEL_VC_FULL_VERSION` is not supported.)

    *   `BAZEL_VC` the Visual C++ Build Tools installation directory

        ```
        set BAZEL_VC=C:\Program Files (x86)\Microsoft Visual Studio 14.0\VC
        ```

*   The [Windows
    SDK](https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk){: .external}.

    The Windows SDK contains header files and libraries you need when building
    Windows applications, including Bazel itself. By default, the latest Windows SDK installed will
    be used. You also can specify Windows SDK version by setting `BAZEL_WINSDK_FULL_VERSION`. You
    can use a full Windows 10 SDK number such as 10.0.10240.0, or specify 8.1 to use the Windows 8.1
    SDK (only one version of Windows 8.1 SDK is available). Please make sure you have the specified
    Windows SDK installed.

    **Requirement**: This is supported with VC 2017 and 2019. The standalone VC 2015 Build Tools doesn't
    support selecting Windows SDK, you'll need the full Visual Studio 2015 installation, otherwise
    `BAZEL_WINSDK_FULL_VERSION` will be ignored.

    ```
    set BAZEL_WINSDK_FULL_VERSION=10.0.10240.0
    ```

If everything is set up, you can build a C++ target now!

Try building a target from one of our [sample
projects](https://github.com/bazelbuild/bazel/tree/master/examples):

<pre class="devsite-click-to-copy">
<code class="devsite-terminal"
            data-terminal-prefix="C:\projects\bazel&gt; ">bazel build //examples/cpp:hello-world</code>
<code class="devsite-terminal"
            data-terminal-prefix="C:\projects\bazel&gt; ">bazel-bin\examples\cpp\hello-world.exe</code>
</pre>

By default, the built binaries target x64 architecture. To specify a different
target architecture, set the `--cpu` build option for your target architecture:
*  x64 (default):  `--cpu=x64_windows` or no option
*  x86: `--cpu=x64_x86_windows`
*  ARM: `--cpu=x64_arm_windows`
*  ARM64: `--cpu=arm64_windows`

Note: `--cpu=x64_arm64_windows` to target ARM64 architecture is deprecated. Please use `--cpu=arm64_windows`

For example, to build targets for ARM architecture, run:

<pre class="devsite-terminal devsite-click-to-copy"
     data-terminal-prefix="C:\projects\bazel&gt; ">
bazel build //examples/cpp:hello-world --cpu=x64_arm_windows
</pre>

To build and use Dynamically Linked Libraries (DLL files), see [this
example](https://github.com/bazelbuild/bazel/tree/master/examples/windows/dll){: .external}.

**Command Line Length Limit**: To prevent the
[Windows command line length limit issue](https://github.com/bazelbuild/bazel/issues/5163){: .external},
enable the compiler parameter file feature via `--features=compiler_param_file`.

### Build C++ with Clang {:#clang}

From 0.29.0, Bazel supports building with LLVM's MSVC-compatible compiler driver (`clang-cl.exe`).

**Requirement**: To build with Clang, you have to install **both**
[LLVM](http://releases.llvm.org/download.html){: .external} and Visual C++ Build tools,
because although you use `clang-cl.exe` as compiler, you still need to link to
Visual C++ libraries.

Bazel can automatically detect LLVM installation on your system, or you can explicitly tell
Bazel where LLVM is installed by `BAZEL_LLVM`.

*   `BAZEL_LLVM` the LLVM installation directory

    ```posix-terminal
    set BAZEL_LLVM=C:\Program Files\LLVM
    ```

To enable the Clang toolchain for building C++, there are several situations.

* In bazel 0.28 and older: Clang is not supported.

* Without `--incompatible_enable_cc_toolchain_resolution`:
  You can enable the Clang toolchain by a build flag `--compiler=clang-cl`.

* With `--incompatible_enable_cc_toolchain_resolution`:
  You have to add a platform target to your `BUILD file` (eg. the top level `BUILD` file):

    ```
    platform(
        name = "x64_windows-clang-cl",
        constraint_values = [
            "@platforms//cpu:x86_64",
            "@platforms//os:windows",
            "@bazel_tools//tools/cpp:clang-cl",
        ],
    )
    ```

    Then you can enable the Clang toolchain by either of the following two ways:
    * Specify the following build flags:

    ```
    --extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows-clang-cl --extra_execution_platforms=//:x64_windows-clang-cl
    ```

    * Register the platform and toolchain in your `MODULE.bazel` file:

    ```
    register_execution_platforms(
        ":x64_windows-clang-cl"
    )

    register_toolchains(
        "@local_config_cc//:cc-toolchain-x64_windows-clang-cl",
    )
    ```

    The [\-\-incompatible_enable_cc_toolchain_resolution](https://github.com/bazelbuild/bazel/issues/7260){: .external}
    flag is planned to be enabled by default in future Bazel release. Therefore,
    it is recommended to enable Clang support with the second approach.

### Build Java {:#java}

To build Java targets, you need:

*   [The Java SE Development Kit](/install/windows#install-jdk)

On Windows, Bazel builds two output files for `java_binary` rules:

*   a `.jar` file
*   a `.exe` file that can set up the environment for the JVM and run the binary

Try building a target from one of our [sample
projects](https://github.com/bazelbuild/bazel/tree/master/examples){: .external}:

<pre class="devsite-click-to-copy">
  <code class="devsite-terminal"
        data-terminal-prefix="C:\projects\bazel&gt; ">bazel build //examples/java-native/src/main/java/com/example/<var>myproject</var>:hello-world</code>
  <code class="devsite-terminal"
        data-terminal-prefix="C:\projects\bazel&gt; ">bazel-bin\examples\java-native\src\main\java\com\example\<var>myproject</var>\hello-world.exe</code>
</pre>

### Build Python {:#python}

To build Python targets, you need:

*   The [Python interpreter](/install/windows#install-python)

On Windows, Bazel builds two output files for `py_binary` rules:

*   a self-extracting zip file
*   an executable file that can launch the Python interpreter with the
    self-extracting zip file as the argument

You can either run the executable file (it has a `.exe` extension) or you can run
Python with the self-extracting zip file as the argument.

Try building a target from one of our [sample
projects](https://github.com/bazelbuild/bazel/tree/master/examples){: .external}:

<pre class="devsite-click-to-copy">
  <code class="devsite-terminal"
        data-terminal-prefix="C:\projects\bazel&gt; ">bazel build //examples/py_native:bin</code>
  <code class="devsite-terminal"
        data-terminal-prefix="C:\projects\bazel&gt; ">bazel-bin\examples\py_native\bin.exe</code>
  <code class="devsite-terminal"
        data-terminal-prefix="C:\projects\bazel&gt; ">python bazel-bin\examples\py_native\bin.zip</code>
</pre>

If you are interested in details about how Bazel builds Python targets on
Windows, check out this [design
doc](https://github.com/bazelbuild/bazel-website/blob/master/designs/_posts/2016-09-05-build-python-on-windows.md){: .external}.
