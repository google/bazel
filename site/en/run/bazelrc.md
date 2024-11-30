Project: /_project.yaml
Book: /_book.yaml

# Write bazelrc configuration files

{% include "_buttons.html" %}

Bazel accepts many options. Some options are varied frequently (for example,
`--subcommands`) while others stay the same across several builds (such as
`--package_path`). To avoid specifying these unchanged options for every build
(and other commands), you can specify options in a configuration file, called
`.bazelrc`.

### Where are the `.bazelrc` files? {:#bazelrc-file-locations}

Bazel looks for optional configuration files in the following locations,
in the order shown below. The options are interpreted in this order, so
options in later files can override a value from an earlier file if a
conflict arises. All options that control which of these files are loaded are
startup options, which means they must occur after `bazel` and
before the command (`build`, `test`, etc).

1.  **The system RC file**, unless `--nosystem_rc` is present.

    Path:

    - On Linux/macOS/Unixes: `/etc/bazel.bazelrc`
    - On Windows: `%ProgramData%\bazel.bazelrc`

    It is not an error if this file does not exist.

    If another system-specified location is required, you must build a custom
    Bazel binary, overriding the `BAZEL_SYSTEM_BAZELRC_PATH` value in
    [`//src/main/cpp:option_processor`](https://github.com/bazelbuild/bazel/blob/0.28.0/src/main/cpp/BUILD#L141).
    The system-specified location may contain environment variable references,
    such as `${VAR_NAME}` on Unix or `%VAR_NAME%` on Windows.

2.  **The low-priority user-specified RC file**, if specified with
    <code>--low_priority_bazelrc=<var>file</var></code>

    This flag is optional but can also be specified multiple times.

    `/dev/null` indicates that all further `--low_priority_bazelrc`s will
    be ignored, which is useful to disable the search for user-supplied
    low-priority rc files, such as in release builds.

    For example:

    ```
    --low_priority_bazelrc=x.rc --low_priority_bazelrc=y.rc --low_priority_bazelrc=/dev/null --low_priority_bazelrc=z.rc
    ```

    - `x.rc` and `y.rc` are read.
    - `z.rc` is ignored due to the prior `/dev/null`.

3.  **The workspace RC file**, unless `--noworkspace_rc` is present.

    Path: `.bazelrc` in your workspace directory (next to the main
    `MODULE.bazel` file).

    It is not an error if this file does not exist.

4.  **The home RC file**, unless `--nohome_rc` is present.

    Path:

    - On Linux/macOS/Unixes: `$HOME/.bazelrc`
    - On Windows: `%USERPROFILE%\.bazelrc` if exists, or `%HOME%/.bazelrc`

    It is not an error if this file does not exist.

5.  **The user-specified RC file**, if specified with
    <code>--bazelrc=<var>file</var></code>

    This flag is optional but can also be specified multiple times.

    `/dev/null` indicates that all further `--bazelrc`s will be ignored, which
     is useful to disable the search for a user rc file, such as in release
     builds.

    For example:

    ```
    --bazelrc=x.rc --bazelrc=y.rc --bazelrc=/dev/null --bazelrc=z.rc
    ```

    - `x.rc` and `y.rc` are read.
    - `z.rc` is ignored due to the prior `/dev/null`.

In addition to this optional configuration file, Bazel looks for a global rc
file. For more details, see the [global bazelrc section](#global-bazelrc).


### `.bazelrc` syntax and semantics {:#bazelrc-syntax-semantics}

Like all UNIX "rc" files, the `.bazelrc` file is a text file with a line-based
grammar. Empty lines and lines starting with `#` (comments) are ignored. Each
line contains a sequence of words, which are tokenized according to the same
rules as the Bourne shell.

#### Imports {:#imports}

Lines that start with `import` or `try-import` are special: use these to load
other "rc" files. To specify a path that is relative to the workspace root,
write `import %workspace%/path/to/bazelrc`.

The difference between `import` and `try-import` is that Bazel fails if the
`import`'ed file is missing (or can't be read), but not so for a `try-import`'ed
file.

Import precedence:

-   Options in the imported file take precedence over options specified before
    the import statement.
-   Options specified after the import statement take precedence over the
    options in the imported file.
-   Options in files imported later take precedence over files imported earlier.

#### Option defaults {:#option-defaults}

Most lines of a bazelrc define default option values. The first word on each
line specifies when these defaults are applied:

-   `startup`: startup options, which go before the command, and are described
    in `bazel help startup_options`.
-   `common`: options that should be applied to all Bazel commands that support
    them. If a command does not support an option specified in this way, the
    option is ignored so long as it is valid for *some* other Bazel command.
    Note that this only applies to option names: If the current command accepts
    an option with the specified name, but doesn't support the specified value,
    it will fail.
-   `always`: options that apply to all Bazel commands. If a command does not
    support an option specified in this way, it will fail.
-   _`command`_: Bazel command, such as `build` or `query` to which the options
    apply. These options also apply to all commands that inherit from the
    specified command. (For example, `test` inherits from `build`.)

Each of these lines may be used more than once and the arguments that follow the
first word are combined as if they had appeared on a single line. (Users of CVS,
another tool with a "Swiss army knife" command-line interface, will find the
syntax similar to that of `.cvsrc`.) For example, the lines:

```posix-terminal
build --test_tmpdir=/tmp/foo --verbose_failures

build --test_tmpdir=/tmp/bar
```

are combined as:

```posix-terminal
build --test_tmpdir=/tmp/foo --verbose_failures --test_tmpdir=/tmp/bar
```

so the effective flags are `--verbose_failures` and `--test_tmpdir=/tmp/bar`.

Option precedence:

-   Options on the command line always take precedence over those in rc files.
    For example, if a rc file says `build -c opt` but the command line flag is
    `-c dbg`, the command line flag takes precedence.
-   Within the rc file, precedence is governed by specificity: lines for a more
    specific command take precedence over lines for a less specific command.

    Specificity is defined by inheritance. Some commands inherit options from
    other commands, making the inheriting command more specific than the base
    command. For example `test` inherits from the `build` command, so all `bazel
    build` flags are valid for `bazel test`, and all `build` lines apply also to
    `bazel test` unless there's a `test` line for the same option. If the rc
    file says:

    ```posix-terminal
    test -c dbg --test_env=PATH

    build -c opt --verbose_failures
    ```

    then `bazel build //foo` will use `-c opt --verbose_failures`, and `bazel
    test //foo` will use `--verbose_failures -c dbg --test_env=PATH`.

    The inheritance (specificity) graph is:

    *   Every command inherits from `common`
    *   The following commands inherit from (and are more specific than)
        `build`: `test`, `run`, `clean`, `mobile-install`, `info`,
        `print_action`, `config`, `cquery`, and `aquery`
    *   `coverage`, `fetch`, and `vendor` inherit from `test`

-   Two lines specifying options for the same command at equal specificity are
    parsed in the order in which they appear within the file.

-   Because this precedence rule does not match the file order, it helps
    readability if you follow the precedence order within rc files: start with
    `common` options at the top, and end with the most-specific commands at the
    bottom of the file. This way, the order in which the options are read is the
    same as the order in which they are applied, which is more intuitive.

The arguments specified on a line of an rc file may include arguments that are
not options, such as the names of build targets, and so on. These, like the
options specified in the same files, have lower precedence than their siblings
on the command line, and are always prepended to the explicit list of non-
option arguments.

#### `--config` {:#config}

In addition to setting option defaults, the rc file can be used to group options
and provide a shorthand for common groupings. This is done by adding a `:name`
suffix to the command. These options are ignored by default, but will be
included when the option <code>--config=<var>name</var></code> is present,
either on the command line or in a `.bazelrc` file, recursively, even inside of
another config definition. The options specified by `command:name` will only be
expanded for applicable commands, in the precedence order described above.

Note: Configs can be defined in any `.bazelrc` file, and that all lines of
the form `command:name` (for applicable commands) will be expanded, across the
different rc files. In order to avoid name conflicts, we suggest that configs
defined in personal rc files start with an underscore (`_`) to avoid
unintentional name sharing.

`--config=foo` expands to the options defined in
[the rc files](#bazelrc-file-locations) "in-place" so that the options
specified for the config have the same precedence that the `--config=foo` option
had.

This syntax does not extend to the use of `startup` to set
[startup options](#option-defaults). Setting
`startup:config-name --some_startup_option` in the .bazelrc will be ignored.

#### `--enable_platform_specific_config` {:#enable_platform_specific_config}

Platform specific configs in the `.bazelrc` can be automatically enabled using
`--enable_platform_specific_config`. For example, if the host OS is Linux and
the `build` command is run, the `build:linux` configuration will be
automatically enabled. Supported OS identifiers are `linux`, `macos`, `windows`,
`freebsd`, and `openbsd`. Enabling this flag is equivalent to using
`--config=linux` on Linux, `--config=windows` on Windows, and so on.

See [--enable_platform_specific_config](/reference/command-line-reference#flag--enable_platform_specific_config).

#### Example {:#bazelrc-example}

Here's an example `~/.bazelrc` file:

```
# Bob's Bazel option defaults

startup --host_jvm_args=-XX:-UseParallelGC
import /home/bobs_project/bazelrc
build --show_timestamps --keep_going --jobs 600
build --color=yes
query --keep_going

# Definition of --config=memcheck
build:memcheck --strip=never --test_timeout=3600
```

### Other files governing Bazel's behavior {:#bazel-behavior-files}

#### `.bazelignore` {:#bazelignore}

You can specify directories within the workspace
that you want Bazel to ignore, such as related projects
that use other build systems. Place a file called
`.bazelignore` at the root of the workspace
and add the directories you want Bazel to ignore, one per
line. Entries are relative to the workspace root.

### The global bazelrc file {:#global-bazelrc}

Bazel reads optional bazelrc files in this order:

1.  System rc-file located at `etc/bazel.bazelrc`.
2.  Workspace rc-file located at `$workspace/tools/bazel.rc`.
3.  Home rc-file located at `$HOME/.bazelrc`

Each bazelrc file listed here has a corresponding flag which can be used to
disable them (e.g. `--nosystem_rc`, `--noworkspace_rc`, `--nohome_rc`). You can
also make Bazel ignore all bazelrcs by passing the `--ignore_all_rc_files`
startup option.
