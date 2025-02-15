Project: /_project.yaml
Book: /_book.yaml

# Commands and Options

{% include "_buttons.html" %}

This page covers the options that are available with various Bazel commands,
such as `bazel build`, `bazel run`, and `bazel test`. This page is a companion
to the list of Bazel's commands in [Build with Bazel](/run/build).

## Target syntax {:#target-syntax}

Some commands, like `build` or `test`, can operate on a list of targets. They
use a syntax more flexible than labels, which is documented in
[Specifying targets to build](/run/build#specifying-build-targets).

## Options {:#build-options}

The following sections describe the options available during a
build. When `--long` is used on a help command, the on-line
help messages provide summary information about the meaning, type and
default value for each option.

Most options can only be specified once. When specified multiple times, the
last instance wins. Options that can be specified multiple times are
identified in the on-line help with the text 'may be used multiple times'.

### Package location {:#package-location}

#### `--package_path` {:#package-path}

**WARNING:** The `--package_path` option is deprecated. Bazel prefers packages
in the main repository to be under the workspace root.

This option specifies the set of directories that are searched to
find the BUILD file for a given package.

Bazel finds its packages by searching the package path. This is a colon
separated ordered list of bazel directories, each being the root of a
partial source tree.

_To specify a custom package path_ using the `--package_path` option:

<pre>
  % bazel build --package_path %workspace%:/some/other/root
</pre>

Package path elements may be specified in three formats:

1.  If the first character is `/`, the path is absolute.
2.  If the path starts with `%workspace%`, the path is taken relative
    to the nearest enclosing bazel directory.
    For instance, if your working directory
    is `/home/bob/clients/bob_client/bazel/foo`, then the
    string `%workspace%` in the package-path is expanded
    to `/home/bob/clients/bob_client/bazel`.
3.  Anything else is taken relative to the working directory.
    This is usually not what you mean to do,
    and may behave unexpectedly if you use Bazel from directories below the bazel workspace.
    For instance, if you use the package-path element `.`,
    and then cd into the directory
    `/home/bob/clients/bob_client/bazel/foo`, packages
    will be resolved from the
    `/home/bob/clients/bob_client/bazel/foo` directory.

If you use a non-default package path, specify it in your
[Bazel configuration file](/run/bazelrc) for convenience.

_Bazel doesn't require any packages to be in the
current directory_, so you can do a build from an empty bazel
workspace if all the necessary packages can be found somewhere else
on the package path.

Example: Building from an empty client

<pre>
  % mkdir -p foo/bazel
  % cd foo/bazel
  % touch MODULE.bazel
  % bazel build --package_path /some/other/path //foo
</pre>

#### `--deleted_packages` {:flag--deleted_packages}

This option specifies a comma-separated list of packages which Bazel
should consider deleted, and not attempt to load from any directory
on the package path. This can be used to simulate the deletion of packages without
actually deleting them. This option can be passed multiple times, in which case
the individual lists are concatenated.

### Error checking {:#error-checking}

These options control Bazel's error-checking and/or warnings.

#### `--[no]check_visibility` {:#check-visibility}

If this option is set to false, visibility checks are demoted to warnings.
The default value of this option is true, so that by default, visibility
checking is done.

#### `--output_filter={{ "<var>" }}regex{{ "</var>" }}` {:#output-filter}

The `--output_filter` option will only show build and compilation
warnings for targets that match the regular expression. If a target does not
match the given regular expression and its execution succeeds, its standard
output and standard error are thrown away.

Here are some typical values for this option:

<table>
  <tr>
    <td>`--output_filter='^//(first/project|second/project):'`</td>
    <td>Show the output for the specified packages.</td>
  </tr>
  <tr>
    <td>`--output_filter='^//((?!(first/bad_project|second/bad_project):).)*$'`</td>
    <td>Don't show output for the specified packages.</td>
  </tr>
  <tr>
    <td>`--output_filter=`</td>
    <td>Show everything.
    </td>
  </tr>
  <tr>
    <td>`--output_filter=DONT_MATCH_ANYTHING`</td>
    <td>Show nothing.
    </td>
  </tr>
</table>

### Tool flags {:#tool-flags}

These options control which options Bazel will pass to other tools.

#### `--copt={{ "<var>" }}cc-option{{ "</var>" }}` {:#copt}

This option takes an argument which is to be passed to the compiler.
The argument will be passed to the compiler whenever it is invoked
for preprocessing, compiling, and/or assembling C, C++, or
assembler code. It will not be passed when linking.

This option can be used multiple times. For example:

<pre>
  % bazel build --copt="-g0" --copt="-fpic" //foo
</pre>

will compile the `foo` library without debug tables, generating
position-independent code.

Note: Changing `--copt` settings will force a recompilation
of all affected object files. Also note that copts values listed in specific
cc_library or cc_binary build rules will be placed on the compiler command line
_after_ these options.

Warning: C++-specific options (such as `-fno-implicit-templates`)
should be specified in `--cxxopt`, not in
`--copt`. Likewise, C-specific options (such as -Wstrict-prototypes)
should be specified in `--conlyopt`, not in `copt`.
Similarly, compiler options that only have an
effect at link time (such as `-l`) should be specified in
`--linkopt`, not in `--copt`.

#### `--host_copt={{ "<var>" }}cc-option{{ "</var>" }}` {:#host-copt}

This option takes an argument which is to be passed to the compiler for source files
that are compiled in the exec configuration. This is analogous to
the [`--copt`](#copt) option, but applies only to the
exec configuration.

#### `--host_conlyopt={{ "<var>" }}cc-option{{ "</var>" }}` {:#host-conlyopt}

This option takes an argument which is to be passed to the compiler for C source files
that are compiled in the exec configuration. This is analogous to
the [`--conlyopt`](#cconlyopt) option, but applies only
to the exec configuration.

#### `--host_cxxopt={{ "<var>" }}cc-option{{ "</var>" }}` {:#host-cxxopt}

This option takes an argument which is to be passed to the compiler for C++ source files
that are compiled in the exec configuration. This is analogous to
the [`--cxxopt`](#cxxopt) option, but applies only to the
exec configuration.

#### `--host_linkopt={{ "<var>" }}linker-option{{ "</var>" }}` {:#host-linkopt}

This option takes an argument which is to be passed to the linker for source files
that are compiled in the exec configuration. This is analogous to
the [`--linkopt`](#linkopt) option, but applies only to
the exec configuration.

#### `--conlyopt={{ "<var>" }}cc-option{{ "</var>" }}` {:#cconlyopt}

This option takes an argument which is to be passed to the compiler when compiling C source files.

This is similar to `--copt`, but only applies to C compilation,
not to C++ compilation or linking. So you can pass C-specific options
(such as `-Wno-pointer-sign`) using `--conlyopt`.

Note: copts parameters listed in specific cc_library or cc_binary build rules
are placed on the compiler command line _after_ these options.

#### `--cxxopt={{ "<var>" }}cc-option{{ "</var>" }}` {:#cxxopt}

This option takes an argument which is to be passed to the compiler when
compiling C++ source files.

This is similar to `--copt`, but only applies to C++ compilation,
not to C compilation or linking. So you can pass C++-specific options
(such as `-fpermissive` or `-fno-implicit-templates`) using `--cxxopt`.

For example:

<pre>
  % bazel build --cxxopt="-fpermissive" --cxxopt="-Wno-error" //foo/cruddy_code
</pre>

Note: copts parameters listed in specific cc_library or cc_binary build rules
are placed on the compiler command line _after_ these options.

#### `--linkopt={{ "<var>" }}linker-option{{ "</var>" }}` {:#linkopt}

This option takes an argument which is to be passed to the compiler when linking.

This is similar to `--copt`, but only applies to linking,
not to compilation. So you can pass compiler options that only make sense
at link time (such as `-lssp` or `-Wl,--wrap,abort`)
using `--linkopt`. For example:

<pre>
  % bazel build --copt="-fmudflap" --linkopt="-lmudflap" //foo/buggy_code
</pre>

Build rules can also specify link options in their attributes. This option's
settings always take precedence. Also see
[cc_library.linkopts](/reference/be/c-cpp#cc_library.linkopts).

#### `--strip (always|never|sometimes)` {:#strip}

This option determines whether Bazel will strip debugging information from
all binaries and shared libraries, by invoking the linker with the `-Wl,--strip-debug` option.
`--strip=always` means always strip debugging information.
`--strip=never` means never strip debugging information.
The default value of `--strip=sometimes` means strip if the `--compilation_mode`
is `fastbuild`.

<pre>
  % bazel build --strip=always //foo:bar
</pre>

will compile the target while stripping debugging information from all generated
binaries.

Note: If you want debugging information, it's not enough to disable stripping;
you also need to make sure that the debugging information was generated by the
compiler, which you can do by using either `-c dbg` or `--copt -g`.

Bazel's `--strip` option corresponds with ld's `--strip-debug` option:
it only strips debugging information. If for some reason you want to strip _all_ symbols,
not just _debug_ symbols, you would need to use ld's `--strip-all` option,
which you can do by passing `--linkopt=-Wl,--strip-all` to Bazel. Also be
aware that setting Bazel's `--strip` flag will override
`--linkopt=-Wl,--strip-all`, so you should only set one or the other.

If you are only building a single binary and want all symbols stripped, you could also
pass `--stripopt=--strip-all` and explicitly build the
`//foo:bar.stripped` version of the target. As described in the section on
`--stripopt`, this applies a strip action after the final binary is
linked rather than including stripping in all of the build's link actions.

#### `--stripopt={{ "<var>" }}strip-option{{ "</var>" }}` {:#stripopt}

This is an additional option to pass to the `strip` command when generating
a [`*.stripped` binary](/reference/be/c-cpp#cc_binary_implicit_outputs). The default
is `-S -p`. This option can be used multiple times.

Note: `--stripopt` does not apply to the stripping of the main
binary with `[--strip](#flag--strip)=(always|sometimes)`.

#### `--fdo_instrument={{ "<var>" }}profile-output-dir{{ "</var>" }}` {:#fdo-instrument}

The `--fdo_instrument` option enables the generation of
FDO (feedback directed optimization) profile output when the
built C/C++ binary is executed. For GCC, the argument provided is used as a
directory prefix for a per-object file directory tree of .gcda files
containing profile information for each .o file.

Once the profile data tree has been generated, the profile tree
should be zipped up, and provided to the
`--fdo_optimize={{ "<var>" }}profile-zip{{ "</var>" }}`
Bazel option to enable the FDO-optimized compilation.

For the LLVM compiler the argument is also the directory under which the raw LLVM profile
data file(s) is dumped. For example:
`--fdo_instrument={{ "<var>" }}/path/to/rawprof/dir/{{ "</var>" }}`.

The options `--fdo_instrument` and `--fdo_optimize` cannot be used at the same time.

#### `--fdo_optimize={{ "<var>" }}profile-zip{{ "</var>" }}` {:#fdo-optimize}

The `--fdo_optimize` option enables the use of the
per-object file profile information to perform FDO (feedback
directed optimization) optimizations when compiling. For GCC, the argument
provided is the zip file containing the previously-generated file tree
of .gcda files containing profile information for each .o file.

Alternatively, the argument provided can point to an auto profile
identified by the extension .afdo.

Note: This option also accepts labels that resolve to source files. You
may need to add an `exports_files` directive to the corresponding package to
make the file visible to Bazel.

For the LLVM compiler the argument provided should point to the indexed LLVM
profile output file prepared by the llvm-profdata tool, and should have a .profdata
extension.

The options `--fdo_instrument` and `--fdo_optimize` cannot be used at the same time.

#### `--java_language_version={{ "<var>" }}version{{ "</var>" }}` {:#java-language-version}

This option specifies the version of Java sources. For example:

<pre>
  % bazel build --java_language_version=8 java/com/example/common/foo:all
</pre>

compiles and allows only constructs compatible with Java 8 specification.
Default value is 11. -->
Possible values are: 8, 9, 10, 11, 17, and 21 and may be extended by
registering custom Java toolchains using `default_java_toolchain`.

#### `--tool_java_language_version={{ "<var>" }}version{{ "</var>" }}` {:#tool-java-language-version}

The Java language version used to build tools that are executed during a build.
Default value is 11.

#### `--java_runtime_version={{ "<var>" }}version{{ "</var>" }}` {:#java-runtime-version}

This option specifies the version of JVM to use to execute the code and run the tests. For
example:

<pre>
  % bazel run --java_runtime_version=remotejdk_11 java/com/example/common/foo:java_application
</pre>

downloads JDK 11 from a remote repository and run the Java application using it.

Default value is `local_jdk`.
Possible values are: `local_jdk`, `local_jdk_{{ "<var>" }}version{{ "</var>" }}`,
`remotejdk_11`, `remotejdk_17`, and `remotejdk_21`.
You can extend the values by registering custom JVM using either
`local_java_repository` or `remote_java_repository` repository rules.

#### `--tool_java_runtime_version={{ "<var>" }}version{{ "</var>" }}` {:#tool-java-runtime-version}

The version of JVM used to execute tools that are needed during a build.
Default value is `remotejdk_11`.

#### `--jvmopt={{ "<var>" }}jvm-option{{ "</var>" }}` {:#jvmopt}

This option allows option arguments to be passed to the Java VM. It can be used
with one big argument, or multiple times with individual arguments. For example:

<pre>
  % bazel build --jvmopt="-server -Xms256m" java/com/example/common/foo:all
</pre>

will use the server VM for launching all Java binaries and set the
startup heap size for the VM to 256 MB.

#### `--javacopt={{ "<var>" }}javac-option{{ "</var>" }}` {:#javacopt}

This option allows option arguments to be passed to javac. It can be used
with one big argument, or multiple times with individual arguments. For example:

<pre>
  % bazel build --javacopt="-g:source,lines" //myprojects:prog
</pre>

will rebuild a java_binary with the javac default debug info
(instead of the bazel default).

The option is passed to javac after the Bazel built-in default options for
javac and before the per-rule options. The last specification of
any option to javac wins. The default options for javac are:

<pre>
  -source 8 -target 8 -encoding UTF-8
</pre>

Note: Changing `--javacopt` settings will force a recompilation
of all affected classes. Also note that javacopts parameters listed in
specific java_library or java_binary build rules will be placed on the javac
command line _after_ these options.

#### `--strict_java_deps (default|strict|off|warn|error)` {:#strict-java-deps}

This option controls whether javac checks for missing direct dependencies.
Java targets must explicitly declare all directly used targets as
dependencies. This flag instructs javac to determine the jars actually used
for type checking each java file, and warn/error if they are not the output
of a direct dependency of the current target.

* `off` means checking is disabled.
* `warn` means javac will generate standard java warnings of
  type `[strict]` for each missing direct dependency.
* `default`, `strict` and `error` all
  mean javac will generate errors instead of warnings, causing the current
  target to fail to build if any missing direct dependencies are found.
  This is also the default behavior when the flag is unspecified.

### Build semantics {:#build-semantics}

These options affect the build commands and/or the output file contents.

#### `--compilation_mode (fastbuild|opt|dbg)` (-c) {:#compilation-mode}

The `--compilation_mode` option (often shortened to `-c`,
especially `-c opt`) takes an argument of `fastbuild`, `dbg`
or `opt`, and affects various C/C++ code-generation
options, such as the level of optimization and the completeness of
debug tables. Bazel uses a different output directory for each
different compilation mode, so you can switch between modes without
needing to do a full rebuild _every_ time.

* `fastbuild` means build as fast as possible:
  generate minimal debugging information (`-gmlt
  -Wl,-S`), and don't optimize. This is the
  default. Note: `-DNDEBUG` will **not** be set.
* `dbg` means build with debugging enabled (`-g`),
  so that you can use gdb (or another debugger).
* `opt` means build with optimization enabled and
  with `assert()` calls disabled (`-O2 -DNDEBUG`).
  Debugging information will not be generated in `opt` mode
  unless you also pass `--copt -g`.

#### `--cpu={{ "<var>" }}cpu{{ "</var>" }}` {:#cpu}

This option specifies the target CPU architecture to be used for
the compilation of binaries during the build.

Note: A particular combination of crosstool version, compiler version,
and target CPU is allowed only if it has been specified in the currently
used CROSSTOOL file.

#### `--action_env={{ "<var>" }}VAR=VALUE{{ "</var>" }}` {:#action-env}

Specifies the set of environment variables available during the execution of all actions.
Variables can be either specified by name, in which case the value will be taken from the
invocation environment, or by the `name=value` pair which sets the value independent of the
invocation environment.

This `--action_env` flag can be specified multiple times. If a value is assigned to the same
variable across multiple `--action_env` flags, the latest assignment wins.

#### `--experimental_action_listener={{ "<var>" }}label{{ "</var>" }}` {:#experimental-action-listener}

Warning: Extra actions are deprecated. Use
[aspects](/extending/aspects)
instead.

The `experimental_action_listener` option instructs Bazel to use
details from the [`action_listener`](/reference/be/extra-actions#action_listener) rule specified by {{ "<var>" }}label{{ "</var>" }} to
insert [`extra_actions`](/reference/be/extra-actions#extra_action) into the build graph.

#### `--[no]experimental_extra_action_top_level_only` {:experimental-extra-action-top-level-only}

Warning: Extra actions are deprecated. Use
[aspects](/extending/aspects) instead.

If this option is set to true, extra actions specified by the
[ `--experimental_action_listener`](#experimental-action-listener) command
line option will only be scheduled for top level targets.

#### `--experimental_extra_action_filter={{ "<var>" }}regex{{ "</var>" }}` {:#experimental-extra-action-filter}

Warning: Extra actions are deprecated. Use
[aspects](/extending/aspects) instead.

The `experimental_extra_action_filter` option instructs Bazel to
filter the set of targets to schedule `extra_actions` for.

This flag is only applicable in combination with the
[`--experimental_action_listener`](#experimental-action-listener) flag.

By default all `extra_actions` in the transitive closure of the
requested targets-to-build get scheduled for execution.
`--experimental_extra_action_filter` will restrict scheduling to
`extra_actions` of which the owner's label matches the specified
regular expression.

The following example will limit scheduling of `extra_actions`
to only apply to actions of which the owner's label contains '/bar/':

<pre>% bazel build --experimental_action_listener=//test:al //foo/... \
  --experimental_extra_action_filter=.*/bar/.*
</pre>

#### `--host_cpu={{ "<var>" }}cpu{{ "</var>" }}` {:#host-cpu}

This option specifies the name of the CPU architecture that should be
used to build host tools.

#### `--android_platforms={{ "<var>" }}platform[,platform]*{{ "</var>" }}` {:#android-platforms}

The platforms to build the transitive `deps` of
`android_binary` rules (specifically for native dependencies like C++). For
example, if a `cc_library` appears in the transitive `deps` of an
`android_binary` rule it is be built once for each platform specified with
`--android_platforms` for the `android_binary` rule, and included in the final
output.

There is no default value for this flag: a custom Android platform must be
defined and used.

One `.so` file is created and packaged in the APK for each platform specified
with `--android_platforms`. The `.so` file's name prefixes the name of the
`android_binary` rule with "lib". For example, if the name of the
`android_binary` is "foo", then the file is `libfoo.so`.

#### `--per_file_copt={{ "<var>" }}[+-]regex[,[+-]regex]...@option[,option]...{{ "</var>" }}` {:#per-file-copt}

When present, any C++ file with a label or an execution path matching one of the inclusion regex
expressions and not matching any of the exclusion expressions will be built
with the given options. The label matching uses the canonical form of the label
(i.e //`package`:`label_name`).

The execution path is the relative path to your workspace directory including the base name
(including extension) of the C++ file. It also includes any platform dependent prefixes.

Note: If only one of the label or the execution path matches the options will be used.

To match the generated files (such as genrule outputs)
Bazel can only use the execution path. In this case the regexp shouldn't start with '//'
since that doesn't match any execution paths. Package names can be used like this:
`--per_file_copt=base/.*\.pb\.cc@-g0`. This will match every
`.pb.cc` file under a directory called `base`.

This option can be used multiple times.

The option is applied regardless of the compilation mode used. For example, it is possible
to compile with `--compilation_mode=opt` and selectively compile some
files with stronger optimization turned on, or with optimization disabled.

**Caveat**: If some files are selectively compiled with debug symbols the symbols
might be stripped during linking. This can be prevented by setting
`--strip=never`.

**Syntax**: `[+-]regex[,[+-]regex]...@option[,option]...` Where
`regex` stands for a regular expression that can be prefixed with
a `+` to identify include patterns and with `-` to identify
exclude patterns. `option` stands for an arbitrary option that is passed
to the C++ compiler. If an option contains a `,` it has to be quoted like so
`\,`. Options can also contain `@`, since only the first
`@` is used to separate regular expressions from options.

**Example**:
`--per_file_copt=//foo:.*\.cc,-//foo:file\.cc@-O0,-fprofile-arcs`
adds the `-O0` and the `-fprofile-arcs` options to the command
line of the C++ compiler for all `.cc` files in `//foo/` except `file.cc`.

#### `--dynamic_mode={{ "<var>" }}mode{{ "</var>" }}` {:#dynamic-mode}

Determines whether C++ binaries will be linked dynamically, interacting with
the [linkstatic attribute](/reference/be/c-cpp#cc_binary.linkstatic) on build rules.

Modes:

* `default`: Allows bazel to choose whether to link dynamically.
  See [linkstatic](/reference/be/c-cpp#cc_binary.linkstatic) for more
  information.
* `fully`: Links all targets dynamically. This will speed up
  linking time, and reduce the size of the resulting binaries.
* `off`: Links all targets in
  [mostly static](/reference/be/c-cpp#cc_binary.linkstatic) mode.
  If `-static` is set in linkopts, targets will change to fully static.

#### `--fission (yes|no|[dbg][,opt][,fastbuild])` {:#fission}

Enables [Fission](https://gcc.gnu.org/wiki/DebugFission){: .external},
which writes C++ debug information to dedicated .dwo files instead of .o files, where it would
otherwise go. This substantially reduces the input size to links and can reduce link times.

When set to `[dbg][,opt][,fastbuild]` (example:
`--fission=dbg,fastbuild`), Fission is enabled
only for the specified set of compilation modes. This is useful for bazelrc
settings. When set to `yes`, Fission is enabled
universally. When set to `no`, Fission is disabled
universally. Default is <code class='flag'>no</code>.

#### `--force_ignore_dash_static` {:#force-ignore-dash-static}

If this flag is set, any `-static` options in linkopts of
`cc_*` rules BUILD files are ignored. This is only intended as a
workaround for C++ hardening builds.

#### `--[no]force_pic` {:#force-pic}

If enabled, all C++ compilations produce position-independent code ("-fPIC"),
links prefer PIC pre-built libraries over non-PIC libraries, and links produce
position-independent executables ("-pie"). Default is disabled.

Note: Dynamically linked binaries (for example `--dynamic_mode fully`)
generate PIC code regardless of this flag's setting. So this flag is for cases
where users want PIC code explicitly generated for static links.

#### `--android_resource_shrinking` {:#flag--android_resource_shrinking}

Selects whether to perform resource shrinking for android_binary rules. Sets the default for the
[shrink_resources attribute](/reference/be/android#android_binary.shrink_resources) on
android_binary rules; see the documentation for that rule for further details. Defaults to off.

#### `--custom_malloc={{ "<var>" }}malloc-library-target{{ "</var>" }}` {:#custom-malloc}

When specified, always use the given malloc implementation, overriding all
`malloc="target"` attributes, including in those targets that use the
default (by not specifying any `malloc`).

#### `--crosstool_top={{ "<var>" }}label{{ "</var>" }}` {:#crosstool-top}

This option specifies the location of the crosstool compiler suite
to be used for all C++ compilation during a build. Bazel will look in that
location for a CROSSTOOL file and uses that to automatically determine
settings for `--compiler`.

#### `--host_crosstool_top={{ "<var>" }}label{{ "</var>" }}` {:#host-crosstool-top}

If not specified, Bazel uses the value of `--crosstool_top` to compile
code in the exec configuration, such as tools run during the build. The main purpose of this flag
is to enable cross-compilation.

#### `--apple_crosstool_top={{ "<var>" }}label{{ "</var>" }}` {:#apple-crosstool-top}

The crosstool to use for compiling C/C++ rules in the transitive `deps` of
objc_*, ios__*, and apple_* rules. For those targets, this flag overwrites
`--crosstool_top`.

#### `--compiler={{ "<var>" }}version{{ "</var>" }}` {:#compiler}

This option specifies the C/C++ compiler version (such as `gcc-4.1.0`)
to be used for the compilation of binaries during the build. If you want to
build with a custom crosstool, you should use a CROSSTOOL file instead of
specifying this flag.

Note: Only certain combinations of crosstool version, compiler version,
and target CPU are allowed.

#### `--android_sdk={{ "<var>" }}label{{ "</var>" }}` {:#android-sdk}

Deprecated. This shouldn't be directly specified.

This option specifies the Android SDK/platform toolchain
and Android runtime library that will be used to build any Android-related
rule.

The Android SDK will be automatically selected if an `android_sdk_repository`
rule is defined in the WORKSPACE file.

#### `--java_toolchain={{ "<var>" }}label{{ "</var>" }}` {:#java-toolchain}

No-op. Kept only for backwards compatibility.

#### `--host_java_toolchain={{ "<var>" }}label{{ "</var>" }}` {:#host-java-toolchain}

No-op. Kept only for backwards compatibility.

#### `--javabase=({{ "<var>" }}label{{ "</var>" }})` {:#javabase}

No-op. Kept only for backwards compatibility.

#### `--host_javabase={{ "<var>" }}label{{ "</var>" }}` {:#host-javabase}

No-op. Kept only for backwards compatibility.

### Execution strategy {:#execution-strategy}

These options affect how Bazel will execute the build.
They should not have any significant effect on the output files
generated by the build. Typically their main effect is on the
speed of the build.

#### `--spawn_strategy={{ "<var>" }}strategy{{ "</var>" }}` {:#spawn-strategy}

This option controls where and how commands are executed.

* `standalone` causes commands to be executed as local subprocesses. This value is
  deprecated. Please use `local` instead.
* `sandboxed` causes commands to be executed inside a sandbox on the local machine.
  This requires that all input files, data dependencies and tools are listed as direct
  dependencies in the `srcs`, `data` and `tools` attributes.
  Bazel enables local sandboxing by default, on systems that support sandboxed execution.
* `local` causes commands to be executed as local subprocesses.
* `worker` causes commands to be executed using a persistent worker, if available.
* `docker` causes commands to be executed inside a docker sandbox on the local machine.
  This requires that docker is installed.
* `remote` causes commands to be executed remotely; this is only available if a
  remote executor has been configured separately.

#### `--strategy {{ "<var>" }}mnemonic{{ "</var>" }}={{ "<var>" }}strategy{{ "</var>" }}` {:#strategy}

This option controls where and how commands are executed, overriding the
[--spawn_strategy](#spawn-strategy) (and
[--genrule_strategy](#genrule-strategy) with mnemonic
Genrule) on a per-mnemonic basis. See
[--spawn_strategy](#spawn-strategy) for the supported
strategies and their effects.

#### `--strategy_regexp={{ "<var>" }}<filter,filter,...>=<strategy>{{ "</var>" }}` {:#strategy-regexp}

This option specifies which strategy should be used to execute commands that have descriptions
matching a certain `regex_filter`. See
[--per_file_copt](#per-file-copt) for details on
regex_filter matching. See
[--spawn_strategy](#spawn-strategy) for the supported
strategies and their effects.

The last `regex_filter` that matches the description is used. This option overrides
other flags for specifying strategy.

* Example: `--strategy_regexp=//foo.*\\.cc,-//foo/bar=local` means to run actions using
  `local` strategy if their descriptions match //foo.*.cc but not //foo/bar.
* Example:
  `--strategy_regexp='Compiling.*/bar=local' --strategy_regexp=Compiling=sandboxed`
  runs 'Compiling //foo/bar/baz' with the `sandboxed` strategy, but reversing
  the order runs it with `local`.
* Example: `--strategy_regexp='Compiling.*/bar=local,sandboxed'` runs
  'Compiling //foo/bar/baz' with the `local` strategy and falls back to
  `sandboxed` if it fails.

#### `--genrule_strategy={{ "<var>" }}strategy{{ "</var>" }}` {:#genrule-strategy}

This is a deprecated short-hand for `--strategy=Genrule={{ "<var>" }}strategy{{ "</var>" }}`.

#### `--jobs={{ "<var>" }}n{{ "</var>" }}` (-j) {:#jobs}

This option, which takes an integer argument, specifies a limit on
the number of jobs that should be executed concurrently during the
execution phase of the build.

Note : The number of concurrent jobs that Bazel will run
is determined not only by the `--jobs` setting, but also
by Bazel's scheduler, which tries to avoid running concurrent jobs
that will use up more resources (RAM or CPU) than are available,
based on some (very crude) estimates of the resource consumption
of each job. The behavior of the scheduler can be controlled by
the `--local_resources` option.

#### `--progress_report_interval={{ "<var>" }}n{{ "</var>" }}` {:progress-report-interval}

Bazel periodically prints a progress report on jobs that are not
finished yet (such as long running tests). This option sets the
reporting frequency, progress will be printed every `n`
seconds.

The default is 0, that means an incremental algorithm: the first
report will be printed after 10 seconds, then 30 seconds and after
that progress is reported once every minute.

When bazel is using cursor control, as specified by
[`--curses`](#curses), progress is reported every second.

#### `--local_resources {{ "<var>" }}resources or resource expression{{ "</var>" }}` {:#local-resources}

These options specify the amount of local resources (RAM in MB and number of CPU logical cores)
that Bazel can take into consideration when scheduling build and test activities to run locally. They take
an float, or a keyword (HOST_RAM or HOST_CPUS) optionally followed by `[-|*`float`]`
(for example, `--local_resources=cpu=2`, `--local_resources=memory=HOST_RAM*.5`,
`--local_resources=cpu=HOST_CPUS-1`).
The flags are independent; one or both may be set. By default, Bazel estimates
the amount of RAM and number of CPU cores directly from the local system's configuration.

#### `--[no]build_runfile_links` {:#build-runfile-links}

This option, which is enabled by default, specifies whether the runfiles
symlinks for tests and binaries should be built in the output directory.
Using `--nobuild_runfile_links` can be useful
to validate if all targets compile without incurring the overhead
for building the runfiles trees.

When tests (or applications) are executed, their run-time data
dependencies are gathered together in one place. Within Bazel's
output tree, this "runfiles" tree is typically rooted as a sibling of
the corresponding binary or test.
During test execution, runfiles may be accessed using paths of the form
`$TEST_SRCDIR/{{ "<var>" }}canonical_repo_name{{ "</var>" }}/{{ "<var>" }}packagename{{ "</var>" }}/{{ "<var>" }}filename{{ "</var>" }}`.
The runfiles tree ensures that tests have access to all the files
upon which they have a declared dependence, and nothing more. By
default, the runfiles tree is implemented by constructing a set of
symbolic links to the required files. As the set of links grows, so
does the cost of this operation, and for some large builds it can
contribute significantly to overall build time, particularly because
each individual test (or application) requires its own runfiles tree.

#### `--[no]build_runfile_manifests` {:#build-runfile-manifests}

This option, which is enabled by default, specifies whether runfiles manifests
should be written to the output tree.
Disabling it implies `--nobuild_runfile_links`.

It can be disabled when executing tests remotely, as runfiles trees will
be created remotely from in-memory manifests.

#### `--[no]discard_analysis_cache` {:#discard-analysis-cache}

When this option is enabled, Bazel will discard the analysis cache
right before execution starts, thus freeing up additional memory
(around 10%) for the [execution phase](/run/build#execution).
The drawback is that further incremental builds will be slower. See also
[memory-saving mode](/configure/memory).

#### `--[no]keep_going`  (-k) {:#keep-going}

As in GNU Make, the execution phase of a build stops when the first
error is encountered. Sometimes it is useful to try to build as
much as possible even in the face of errors. This option enables
that behavior, and when it is specified, the build will attempt to
build every target whose prerequisites were successfully built, but
will ignore errors.

While this option is usually associated with the execution phase of
a build, it also affects the analysis phase: if several targets are
specified in a build command, but only some of them can be
successfully analyzed, the build will stop with an error
unless `--keep_going` is specified, in which case the
build will proceed to the execution phase, but only for the targets
that were successfully analyzed.

#### `--[no]use_ijars` {:#use-ijars}

This option changes the way `java_library` targets are
compiled by Bazel. Instead of using the output of a
`java_library` for compiling dependent
`java_library` targets, Bazel will create interface jars
that contain only the signatures of non-private members (public,
protected, and default (package) access methods and fields) and use
the interface jars to compile the dependent targets. This makes it
possible to avoid recompilation when changes are only made to
method bodies or private members of a class.

Note: Using `--use_ijars` might give you a different
error message when you are accidentally referring to a non visible
member of another class: Instead of getting an error that the member
is not visible you will get an error that the member does not exist.
Changing the `--use_ijars` setting will force a recompilation of all affected
classes.

#### `--[no]interface_shared_objects` {:#interface-shared-objects}

This option enables _interface shared objects_, which makes binaries and
other shared libraries depend on the _interface_ of a shared object,
rather than its implementation. When only the implementation changes, Bazel
can avoid rebuilding targets that depend on the changed shared library
unnecessarily.

### Output selection {:#output-selection}

These options determine what to build or test.

#### `--[no]build` {:#build}

This option causes the execution phase of the build to occur; it is
on by default. When it is switched off, the execution phase is
skipped, and only the first two phases, loading and analysis, occur.

This option can be useful for validating BUILD files and detecting
errors in the inputs, without actually building anything.

#### `--[no]build_tests_only` {:#build-tests-only}

If specified, Bazel will build only what is necessary to run the `*_test`
and `test_suite` rules that were not filtered due to their
[size](#test-size-filters),
[timeout](#test-timeout-filters),
[tag](#test-tag-filters), or
[language](#test-lang-filters).
If specified, Bazel will ignore other targets specified on the command line.
By default, this option is disabled and Bazel will build everything
requested, including `*_test` and `test_suite` rules that are filtered out from
testing. This is useful because running
`bazel test --build_tests_only foo/...` may not detect all build
breakages in the `foo` tree.

#### `--[no]check_up_to_date` {:#check-up-to-date}

This option causes Bazel not to perform a build, but merely check
whether all specified targets are up-to-date. If so, the build
completes successfully, as usual. However, if any files are out of
date, instead of being built, an error is reported and the build
fails. This option may be useful to determine whether a build has
been performed more recently than a source edit (for example, for pre-submit
checks) without incurring the cost of a build.

See also [`--check_tests_up_to_date`](#check-tests-up-to-date).

#### `--[no]compile_one_dependency` {:#compile-one-dependency}

Compile a single dependency of the argument files. This is useful for
syntax checking source files in IDEs, for example, by rebuilding a single
target that depends on the source file to detect errors as early as
possible in the edit/build/test cycle. This argument affects the way all
non-flag arguments are interpreted: each argument must be a
file target label or a plain filename relative to the current working
directory, and one rule that depends on each source filename is built. For
C++ and Java
sources, rules in the same language space are preferentially chosen. For
multiple rules with the same preference, the one that appears first in the
BUILD file is chosen. An explicitly named target pattern which does not
reference a source file results in an error.

#### `--save_temps` {:#save-temps}

The `--save_temps` option causes temporary outputs from the compiler to be
saved. These include .s files (assembler code), .i (preprocessed C) and .ii
(preprocessed C++) files. These outputs are often useful for debugging. Temps will only be
generated for the set of targets specified on the command line.

Note: The implementation of `--save_temps` does not use the compiler's
`-save-temps` flag. Instead, there are two passes, one with `-S`
and one with `-E`. A consequence of this is that if your build fails,
Bazel may not yet have produced the ".i" or ".ii" and ".s" files.
If you're trying to use `--save_temps` to debug a failed compilation,
you may need to also use `--keep_going` so that Bazel will still try to
produce the preprocessed files after the compilation fails.

The `--save_temps` flag currently works only for cc_* rules.

To ensure that Bazel prints the location of the additional output files, check that
your [`--show_result {{ "<var>" }}n{{ "</var>" }}`](#show-result)
setting is high enough.

#### `--build_tag_filters={{ "<var>" }}tag[,tag]*{{ "</var>" }}` {:#build-tag-filters}

If specified, Bazel will build only targets that have at least one required tag
(if any of them are specified) and does not have any excluded tags. Build tag
filter is specified as comma delimited list of tag keywords, optionally
preceded with '-' sign used to denote excluded tags. Required tags may also
have a preceding '+' sign.

When running tests, Bazel ignores `--build_tag_filters` for test targets,
which are built and run even if they do not match this filter. To avoid building them, filter
test targets using `--test_tag_filters` or by explicitly excluding them.

#### `--test_size_filters={{ "<var>" }}size[,size]*{{ "</var>" }}` {:#test-size-filters}

If specified, Bazel will test (or build if `--build_tests_only`
is also specified) only test targets with the given size. Test size filter
is specified as comma delimited list of allowed test size values (small,
medium, large or enormous), optionally preceded with '-' sign used to denote
excluded test sizes. For example,

<pre>
  % bazel test --test_size_filters=small,medium //foo:all
</pre>

and

<pre>
  % bazel test --test_size_filters=-large,-enormous //foo:all
</pre>

will test only small and medium tests inside //foo.

By default, test size filtering is not applied.

#### `--test_timeout_filters={{ "<var>" }}timeout[,timeout]*{{ "</var>" }}` {:#test-timeout-filters}

If specified, Bazel will test (or build if `--build_tests_only`
is also specified) only test targets with the given timeout. Test timeout filter
is specified as comma delimited list of allowed test timeout values (short,
moderate, long or eternal), optionally preceded with '-' sign used to denote
excluded test timeouts. See [--test_size_filters](#test-size-filters)
for example syntax.

By default, test timeout filtering is not applied.

#### `--test_tag_filters={{ "<var>" }}tag[,tag]*{{ "</var>" }}` {:#test-tag-filters}

If specified, Bazel will test (or build if `--build_tests_only`
is also specified) only test targets that have at least one required tag
(if any of them are specified) and does not have any excluded tags. Test tag
filter is specified as comma delimited list of tag keywords, optionally
preceded with '-' sign used to denote excluded tags. Required tags may also
have a preceding '+' sign.

For example,

<pre>
  % bazel test --test_tag_filters=performance,stress,-flaky //myproject:all
</pre>

will test targets that are tagged with either `performance` or
`stress` tag but are **not** tagged with the `flaky` tag.

By default, test tag filtering is not applied. Note that you can also filter
on test's `size` and `local` tags in
this manner.

#### `--test_lang_filters={{ "<var>" }}string[,string]*{{ "</var>" }}` {:#test-lang-filters}

Specifies a comma-separated list of strings referring to names of test rule
classes. To refer to the rule class `foo_test`, use the string "foo". Bazel will
test (or build if `--build_tests_only` is also specified) only
targets of the referenced rule classes. To instead exclude those targets, use
the string "-foo". For example,

</p>
<pre>
  % bazel test --test_lang_filters=foo,bar //baz/...
</pre>
<p>
  will test only targets that are instances of `foo_test` or `bar_test` in
  `//baz/...`, while
</p>
<pre>
  % bazel test --test_lang_filters=-foo,-bar //baz/...
</pre>
<p>
  will test all the targets in `//baz/...` except for the `foo_test` and
  `bar_test` instances.
</p>

Tip: You can use `bazel query --output=label_kind "//p:t"` to
learn the rule class name of the target `//p:t`. And you can
look at the pair of instantiation stacks in the output of
`bazel query --output=build "//p:t"` to learn why that target
is an instance of that rule class.

Warning: The option name "--test_lang_filter" is vestigal and is therefore
unfortunately misleading; don't make assumptions about the semantics based on
the name.

#### `--test_filter={{ "<var>" }}filter-expression{{ "</var>" }}` {:#test-filter}

Specifies a filter that the test runner may use to pick a subset of tests for
running. All targets specified in the invocation are built, but depending on
the expression only some of them may be executed; in some cases, only certain
test methods are run.

The particular interpretation of {{ "<var>" }}filter-expression{{ "</var>" }} is up to
the test framework responsible for running the test. It may be a glob,
substring, or regexp. `--test_filter` is a convenience
over passing different `--test_arg` filter arguments,
but not all frameworks support it.

### Verbosity {:#verbosity}

These options control the verbosity of Bazel's output,
either to the terminal, or to additional log files.

#### `--explain={{ "<var>" }}logfile{{ "</var>" }}` {:#explain}

This option, which requires a filename argument, causes the
dependency checker in `bazel build`'s execution phase to
explain, for each build step, either why it is being executed, or
that it is up-to-date. The explanation is written
to _logfile_.

If you are encountering unexpected rebuilds, this option can help to
understand the reason. Add it to your `.bazelrc` so that
logging occurs for all subsequent builds, and then inspect the log
when you see an execution step executed unexpectedly. This option
may carry a small performance penalty, so you might want to remove
it when it is no longer needed.

#### `--verbose_explanations` {:#verbose-explanations}

This option increases the verbosity of the explanations generated
when the [--explain](#explain) option is enabled.

In particular, if verbose explanations are enabled,
and an output file is rebuilt because the command used to
build it has changed, then the output in the explanation file will
include the full details of the new command (at least for most
commands).

Using this option may significantly increase the length of the
generated explanation file and the performance penalty of using
`--explain`.

If `--explain` is not enabled, then
`--verbose_explanations` has no effect.

#### `--profile={{ "<var>" }}file{{ "</var>" }}` {:#profile}

This option, which takes a filename argument, causes Bazel to write
profiling data into a file. The data then can be analyzed or parsed using the
`bazel analyze-profile` command. The Build profile can be useful in
understanding where Bazel's `build` command is spending its time.

#### `--[no]show_loading_progress` {:#show-loading-progress}

This option causes Bazel to output package-loading progress
messages. If it is disabled, the messages won't be shown.

#### `--[no]show_progress` {:#show-progress}

This option causes progress messages to be displayed; it is on by
default. When disabled, progress messages are suppressed.

#### `--show_progress_rate_limit={{ "<var>" }}n{{ "</var>" }}` {:#show-progress-rate}

This option causes bazel to display at most one progress message per `n` seconds,
where {{ "<var>" }}n{{ "</var>" }} is a real number.
The default value for this option is 0.02, meaning bazel will limit the progress
messages to one per every 0.02 seconds.

#### `--show_result={{ "<var>" }}n{{ "</var>" }}` {:#show-result}

This option controls the printing of result information at the end
of a `bazel build` command. By default, if a single
build target was specified, Bazel prints a message stating whether
or not the target was successfully brought up-to-date, and if so,
the list of output files that the target created. If multiple
targets were specified, result information is not displayed.

While the result information may be useful for builds of a single
target or a few targets, for large builds (such as an entire top-level
project tree), this information can be overwhelming and distracting;
this option allows it to be controlled. `--show_result`
takes an integer argument, which is the maximum number of targets
for which full result information should be printed. By default,
the value is 1. Above this threshold, no result information is
shown for individual targets. Thus zero causes the result
information to be suppressed always, and a very large value causes
the result to be printed always.

Users may wish to choose a value in-between if they regularly
alternate between building a small group of targets (for example,
during the compile-edit-test cycle) and a large group of targets
(for example, when establishing a new workspace or running
regression tests). In the former case, the result information is
very useful whereas in the latter case it is less so. As with all
options, this can be specified implicitly via
the [`.bazelrc`](/run/bazelrc) file.

The files are printed so as to make it easy to copy and paste the
filename to the shell, to run built executables. The "up-to-date"
or "failed" messages for each target can be easily parsed by scripts
which drive a build.

#### `--sandbox_debug` {:#sandbox-debug}

This option causes Bazel to print extra debugging information when using sandboxing for action
execution. This option also preserves sandbox directories, so that the files visible to actions
during execution can be examined.

#### `--subcommands` (`-s`) {:#subcommands}

This option causes Bazel's execution phase to print the full command line
for each command prior to executing it.

<pre>
  &gt;&gt;&gt;&gt;&gt; # //examples/cpp:hello-world [action 'Linking examples/cpp/hello-world']
  (cd /home/johndoe/.cache/bazel/_bazel_johndoe/4c084335afceb392cfbe7c31afee3a9f/bazel && \
    exec env - \
    /usr/bin/gcc -o bazel-out/local-fastbuild/bin/examples/cpp/hello-world -B/usr/bin/ -Wl,-z,relro,-z,now -no-canonical-prefixes -pass-exit-codes -Wl,-S -Wl,@bazel-out/local_linux-fastbuild/bin/examples/cpp/hello-world-2.params)
</pre>

Where possible, commands are printed in a Bourne shell compatible syntax,
so that they can be easily copied and pasted to a shell command prompt.
(The surrounding parentheses are provided to protect your shell from the
`cd` and `exec` calls; be sure to copy them!)
However some commands are implemented internally within Bazel, such as
creating symlink trees. For these there's no command line to display.

`--subcommands=pretty_print` may be passed to print
the arguments of the command as a list rather than as a single line. This may
help make long command lines more readable.

See also [--verbose_failures](#verbose-failures), below.

For logging subcommands to a file in a tool-friendly format, see
[--execution_log_json_file](/reference/command-line-reference#flag--execution_log_json_file)
and
[--execution_log_binary_file](/reference/command-line-reference#flag--execution_log_binary_file).

#### `--verbose_failures` {:#verbose-failures}

This option causes Bazel's execution phase to print the full command line
for commands that failed. This can be invaluable for debugging a
failing build.

Failing commands are printed in a Bourne shell compatible syntax, suitable
for copying and pasting to a shell prompt.

### Workspace status {:#workspace-status}

Use these options to "stamp" Bazel-built binaries: to embed additional information into the
binaries, such as the source control revision or other workspace-related information. You can use
this mechanism with rules that support the `stamp` attribute, such as
`genrule`, `cc_binary`, and more.

#### `--workspace_status_command={{ "<var>" }}program{{ "</var>" }}` {:#workspace-status-command}

This flag lets you specify a binary that Bazel runs before each build. The program can report
information about the status of the workspace, such as the current source control revision.

The flag's value must be a path to a native program. On Linux/macOS this may be any executable.
On Windows this must be a native binary, typically an ".exe", ".bat", or a ".cmd" file.

The program should print zero or more key/value pairs to standard output, one entry on each line,
then exit with zero (otherwise the build fails). The key names can be anything but they may only
use upper case letters and underscores. The first space after the key name separates it from the
value. The value is the rest of the line (including additional whitespaces). Neither the key nor
the value may span multiple lines. Keys must not be duplicated.

Bazel partitions the keys into two buckets: "stable" and "volatile". (The names "stable" and
"volatile" are a bit counter-intuitive, so don't think much about them.)

Bazel then writes the key-value pairs into two files:

*   `bazel-out/stable-status.txt`
    contains all keys and values where the key's name starts with `STABLE_`
*   `bazel-out/volatile-status.txt`
    contains the rest of the keys and their values

The contract is:

*   "stable" keys' values should change rarely, if possible. If the contents of
    `bazel-out/stable-status.txt`
      change, Bazel invalidates the actions that depend on them. In
      other words, if a stable key's value changes, Bazel will rerun stamped actions.
      Therefore the stable status should not contain things like timestamps, because they change all
      the time, and would make Bazel rerun stamped actions with each build.

    Bazel always outputs the following stable keys:
    *   `BUILD_EMBED_LABEL`: value of `--embed_label`
    *   `BUILD_HOST`: the name of the host machine that Bazel is running on
    *   `BUILD_USER`: the name of the user that Bazel is running as
*   "volatile" keys' values may change often. Bazel expects them to change all the time, like
      timestamps do, and duly updates the
    `bazel-out/volatile-status.txt`
      file. In order to avoid
      rerunning stamped actions all the time though, **Bazel pretends that the volatile file never
      changes**. In other words, if the volatile status file is the only file whose contents has
      changed, Bazel will not invalidate actions that depend on it. If other inputs of the actions
      have changed, then Bazel reruns that action, and the action will see the updated volatile
      status, but just the volatile status changing alone will not invalidate the action.

    Bazel always outputs the following volatile keys:
      *   `BUILD_TIMESTAMP`: time of the build in seconds since the Unix Epoch (the value
        of `System.currentTimeMillis()` divided by a thousand)
      *   `FORMATTED_DATE`: time of the build Formatted as
        `yyyy MMM d HH mm ss EEE`(for example 2023 Jun 2 01 44 29 Fri) in UTC.

On Linux/macOS you can pass `--workspace_status_command=/bin/true` to
disable retrieving workspace status, because `true` does nothing, successfully (exits
with zero) and prints no output. On Windows you can pass the path of MSYS's `true.exe`
for the same effect.

If the workspace status command fails (exits non-zero) for any reason, the build will fail.

Example program on Linux using Git:

<pre>
#!/bin/bash
echo "CURRENT_TIME $(date +%s)"
echo "RANDOM_HASH $(cat /proc/sys/kernel/random/uuid)"
echo "STABLE_GIT_COMMIT $(git rev-parse HEAD)"
echo "STABLE_USER_NAME $USER"
</pre>

Pass this program's path with `--workspace_status_command`, and the stable status file
will include the STABLE lines and the volatile status file will include the rest of the lines.

#### `--[no]stamp` {:#stamp}

This option, in conjunction with the `stamp` rule attribute, controls whether to
embed build information in binaries.

Stamping can be enabled or disabled explicitly on a per-rule basis using the
`stamp` attribute. Please refer to the Build Encyclopedia for details. When
a rule sets `stamp = -1` (the default for `*_binary` rules), this option
determines whether stamping is enabled.

Bazel never stamps binaries that are built for the exec configuration,
regardless of this option or the `stamp` attribute. For rules that set `stamp =
0` (the default for `*_test` rules), stamping is disabled regardless of
`--[no]stamp`. Specifying `--stamp` does not force targets to be rebuilt if
their dependencies have not changed.

Setting `--nostamp` is generally desireable for build performance, as it
reduces input volatility and maximizes build caching.

### Platform {:#platform}

Use these options to control the host and target platforms that configure how builds work, and to
control what execution platforms and toolchains are available to Bazel rules.

Please see background information on [Platforms](/extending/platforms) and [Toolchains](/extending/toolchains).

#### `--platforms={{ "<var>" }}labels{{ "</var>" }}` {:#platforms}

The labels of the platform rules describing the target platforms for the
current command.

#### `--host_platform={{ "<var>" }}label{{ "</var>" }}` {:#host-platform}

The label of a platform rule that describes the host system.

#### `--extra_execution_platforms={{ "<var>" }}labels{{ "</var>" }}` {:#extra-execution-platforms}

The platforms that are available as execution platforms to run actions.
Platforms can be specified by exact target, or as a target pattern. These
platforms will be considered before those declared in MODULE.bazel files by
[register_execution_platforms()](/rules/lib/globals/module#register_execution_platforms).
This option accepts a comma-separated list of platforms in order of priority.
If the flag is passed multiple times, the most recent overrides.

#### `--extra_toolchains={{ "<var>" }}labels{{ "</var>" }}` {:#extra-toolchains}

The toolchain rules to be considered during toolchain resolution. Toolchains
can be specified by exact target, or as a target pattern. These toolchains will
be considered before those declared in MODULE.bazel files by
[register_toolchains()](/rules/lib/globals/module#register_toolchains).

#### `--toolchain_resolution_debug={{ "<var>" }}regex{{ "</var>" }}` {:#toolchain-resolution-debug}

Print debug information while finding toolchains if the toolchain type matches
the regex. Multiple regexes can be separated by commas. The regex can be
negated by using a `-` at the beginning. This might help developers
of Bazel or Starlark rules with debugging failures due to missing toolchains.

### Miscellaneous {:#miscellaneous}

#### `--flag_alias={{ "<var>" }}alias_name=target_path{{ "</var>" }}` {:#flag-alias}

A convenience flag used to bind longer Starlark build settings to a shorter name. For more
details, see the
[Starlark Configurations](/extending/config#using-build-setting-aliases).

#### `--symlink_prefix={{ "<var>" }}string{{ "</var>" }}` {:#symlink-prefix}

Changes the prefix of the generated convenience symlinks. The
default value for the symlink prefix is `bazel-` which
will create the symlinks `bazel-bin`, `bazel-testlogs`, and
`bazel-genfiles`.

If the symbolic links cannot be created for any reason, a warning is
issued but the build is still considered a success. In particular,
this allows you to build in a read-only directory or one that you have no
permission to write into. Any paths printed in informational
messages at the conclusion of a build will only use the
symlink-relative short form if the symlinks point to the expected
location; in other words, you can rely on the correctness of those
paths, even if you cannot rely on the symlinks being created.

Some common values of this option:

*   **Suppress symlink creation:**
      `--symlink_prefix=/` will cause Bazel to not
      create or update any symlinks, including the `bazel-out` and
      `bazel-<workspace>`
      symlinks. Use this option to suppress symlink creation entirely.

*   **Reduce clutter:**
      `--symlink_prefix=.bazel/` will cause Bazel to create
      symlinks called `bin` (etc) inside a hidden directory `.bazel`.

#### `--platform_suffix={{ "<var>" }}string{{ "</var>" }}` {:#platform-suffix}

Adds a suffix to the configuration short name, which is used to determine the
output directory. Setting this option to different values puts the files into
different directories, for example to improve cache hit rates for builds that
otherwise clobber each others output files, or to keep the output files around
for comparisons.

#### `--default_visibility={{ "<var>" }}(private|public){{ "</var>" }}` {:#default-visibility}

Temporary flag for testing bazel default visibility changes. Not intended for general use
but documented for completeness' sake.

#### `--starlark_cpu_profile=_file_` {:#starlark-cpu-profile}

This flag, whose value is the name of a file, causes Bazel to gather
statistics about CPU usage by all Starlark threads,
and write the profile, in [pprof](https://github.com/google/pprof){: .external} format,
to the named file.

Use this option to help identify Starlark functions that
make loading and analysis slow due to excessive computation. For example:

<pre>
$ bazel build --nobuild --starlark_cpu_profile=/tmp/pprof.gz my/project/...
$ pprof /tmp/pprof.gz
(pprof) top
Type: CPU
Time: Feb 6, 2020 at 12:06pm (PST)
Duration: 5.26s, Total samples = 3.34s (63.55%)
Showing nodes accounting for 3.34s, 100% of 3.34s total
      flat  flat%   sum%        cum   cum%
     1.86s 55.69% 55.69%      1.86s 55.69%  sort_source_files
     1.02s 30.54% 86.23%      1.02s 30.54%  expand_all_combinations
     0.44s 13.17% 99.40%      0.44s 13.17%  range
     0.02s   0.6%   100%      3.34s   100%  sorted
         0     0%   100%      1.38s 41.32%  my/project/main/BUILD
         0     0%   100%      1.96s 58.68%  my/project/library.bzl
         0     0%   100%      3.34s   100%  main
</pre>

For different views of the same data, try the `pprof` commands `svg`,
`web`, and `list`.

## Using Bazel for releases {:#bazel-for-releases}

Bazel is used both by software engineers during the development
cycle, and by release engineers when preparing binaries for deployment
to production. This section provides a list of tips for release
engineers using Bazel.

### Significant options {:#significant-options}

When using Bazel for release builds, the same issues arise as for other scripts
that perform a build. For more details, see
[Call Bazel from scripts](/run/scripts). In particular, the following options
are strongly recommended:

*   [`--bazelrc=/dev/null`](/run/bazelrc)
*   [`--nokeep_state_after_build`](/reference/command-line-reference#flag--keep_state_after_build)

These options are also important:

*   [`--package_path`](#package-path)
*   [`--symlink_prefix`](#symlink-prefix):
    for managing builds for multiple configurations,
    it may be convenient to distinguish each build
    with a distinct identifier, such as "64bit" vs. "32bit". This option
    differentiates the `bazel-bin` (etc.) symlinks.

## Running tests {:#running-tests}

To build and run tests with bazel, type `bazel test` followed by
the name of the test targets.

By default, this command performs simultaneous build and test
activity, building all specified targets (including any non-test
targets specified on the command line) and testing
`*_test` and `test_suite` targets as soon as
their prerequisites are built, meaning that test execution is
interleaved with building. Doing so usually results in significant
speed gains.

### Options for `bazel test` {:#bazel-test-options}

#### `--cache_test_results=(yes|no|auto)` (`-t`) {:#cache-test-results}

If this option is set to 'auto' (the default) then Bazel will only rerun a test if any of the
following conditions applies:

*   Bazel detects changes in the test or its dependencies
*   the test is marked as `external`
*   multiple test runs were requested with `--runs_per_test`
*   the test failed.

If 'no', all tests will be executed unconditionally.

If 'yes', the caching behavior will be the same as auto
except that it may cache test failures and test runs with
`--runs_per_test`.

Note: Test results are _always_ saved in Bazel's output tree,
regardless of whether this option is enabled, so
you needn't have used `--cache_test_results` on the
prior run(s) of `bazel test` in order to get cache hits.
The option only affects whether Bazel will _use_ previously
saved results, not whether it will save results of the current run.

Users who have enabled this option by default in
their `.bazelrc` file may find the
abbreviations `-t` (on) or `-t-` (off)
convenient for overriding the default on a particular run.

#### `--check_tests_up_to_date` {:#check-tests-up-to-date}

This option tells Bazel not to run the tests, but to merely check and report
the cached test results. If there are any tests which have not been
previously built and run, or whose tests results are out-of-date (for example, because
the source code or the build options have changed), then Bazel will report
an error message ("test result is not up-to-date"), will record the test's
status as "NO STATUS" (in red, if color output is enabled), and will return
a non-zero exit code.

This option also implies
[`--check_up_to_date`](#check-up-to-date) behavior.

This option may be useful for pre-submit checks.

#### `--test_verbose_timeout_warnings` {:#test-verbose-timeout-warnings}

This option tells Bazel to explicitly warn the user if a test's timeout is
significantly longer than the test's actual execution time. While a test's
timeout should be set such that it is not flaky, a test that has a highly
over-generous timeout can hide real problems that crop up unexpectedly.

For instance, a test that normally executes in a minute or two should not have
a timeout of ETERNAL or LONG as these are much, much too generous.

This option is useful to help users decide on a good timeout value or
sanity check existing timeout values.

Note: Each test shard is allotted the timeout of the entire
`XX_test` target. Using this option does not affect a test's timeout
value, merely warns if Bazel thinks the timeout could be restricted further.

#### `--[no]test_keep_going` {:#test-keep-going}

By default, all tests are run to completion. If this flag is disabled,
however, the build is aborted on any non-passing test. Subsequent build steps
and test invocations are not run, and in-flight invocations are canceled.
Do not specify both `--notest_keep_going` and `--keep_going`.

#### `--flaky_test_attempts={{ "<var>" }}attempts{{ "</var>" }}` {:#flaky-test-attempts}

This option specifies the maximum number of times a test should be attempted
if it fails for any reason. A test that initially fails but eventually
succeeds is reported as `FLAKY` on the test summary. It is,
however, considered to be passed when it comes to identifying Bazel exit code
or total number of passed tests. Tests that fail all allowed attempts are
considered to be failed.

By default (when this option is not specified, or when it is set to
default), only a single attempt is allowed for regular tests, and
3 for test rules with the `flaky` attribute set. You can specify
an integer value to override the maximum limit of test attempts. Bazel allows
a maximum of 10 test attempts in order to prevent abuse of the system.

#### `--runs_per_test={{ "<var>" }}[regex@]number{{ "</var>" }}` {:#runs-per-test}

This option specifies the number of times each test should be executed. All
test executions are treated as separate tests (fallback functionality
will apply to each of them independently).

The status of a target with failing runs depends on the value of the
`--runs_per_test_detects_flakes` flag:

*  If absent, any failing run causes the entire test to fail.
*  If present and two runs from the same shard return PASS and FAIL, the test
   will receive a status of flaky (unless other failing runs cause it to
   fail).

If a single number is specified, all tests will run that many times.
Alternatively, a regular expression may be specified using the syntax
regex@number. This constrains the effect of `--runs_per_test` to targets
which match the regex (`--runs_per_test=^//pizza:.*@4` runs all tests
under `//pizza/` 4 times).
This form of `--runs_per_test` may be specified more than once.

#### `--[no]runs_per_test_detects_flakes` {:#run-per-test-detects-flakes}

If this option is specified (by default it is not), Bazel will detect flaky
test shards through `--runs_per_test`. If one or more runs for a single shard
fail and one or more runs for the same shard pass, the target will be
considered flaky with the flag. If unspecified, the target will report a
failing status.

#### `--test_summary={{ "<var>" }}output_style{{ "</var>" }}` {:#test-summary}

Specifies how the test result summary should be displayed.

*   `short` prints the results of each test along with the name of
    the file containing the test output if the test failed. This is the default
    value.
*   `terse` like `short`, but even shorter: only print
    information about tests which did not pass.
*   `detailed` prints each individual test case that failed, not
    only each test. The names of test output files are omitted.
*   `none` does not print test summary.

#### `--test_output={{ "<var>" }}output_style{{ "</var>" }}` {:#test-output}

Specifies how test output should be displayed:

*   `summary` shows a summary of whether each test passed or
    failed. Also shows the output log file name for failed tests. The summary
    will be printed at the end of the build (during the build, one would see
    just simple progress messages when tests start, pass or fail).
    This is the default behavior.
*   `errors` sends combined stdout/stderr output from failed tests
    only into the stdout immediately after test is completed, ensuring that
    test output from simultaneous tests is not interleaved with each other.
    Prints a summary at the build as per summary output above.
*   `all` is similar to `errors` but prints output for
    all tests, including those which passed.
*   `streamed` streams stdout/stderr output from each test in
    real-time.

#### `--java_debug` {:#java-debug}

This option causes the Java virtual machine of a java test to wait for a connection from a
JDWP-compliant debugger before starting the test. This option implies `--test_output=streamed`.

#### `--[no]verbose_test_summary` {:#verbose-test-summary}

By default this option is enabled, causing test times and other additional
information (such as test attempts) to be printed to the test summary. If
`--noverbose_test_summary` is specified, test summary will
include only test name, test status and cached test indicator and will
be formatted to stay within 80 characters when possible.

#### `--test_tmpdir={{ "<var>" }}path{{ "</var>" }}` {:#test-tmpdir}

Specifies temporary directory for tests executed locally. Each test will be
executed in a separate subdirectory inside this directory. The directory will
be cleaned at the beginning of the each `bazel test` command.
By default, bazel will place this directory under Bazel output base directory.

Note: This is a directory for running tests, not storing test results
(those are always stored under the `bazel-out` directory).

#### `--test_timeout={{ "<var>" }}seconds{{ "</var>" }}` OR `--test_timeout={{ "<var>" }}seconds{{ "</var>" }},{{ "<var>" }}seconds{{ "</var>" }},{{ "<var>" }}seconds{{ "</var>" }},{{ "<var>" }}seconds{{ "</var>" }}` {:#test-timeout}

Overrides the timeout value for all tests by using specified number of
seconds as a new timeout value. If only one value is provided, then it will
be used for all test timeout categories.

Alternatively, four comma-separated values may be provided, specifying
individual timeouts for short, moderate, long and eternal tests (in that
order).
In either form, zero or a negative value for any of the test sizes will
be substituted by the default timeout for the given timeout categories as
defined by the page [Writing Tests](/reference/test-encyclopedia).
By default, Bazel will use these timeouts for all tests by
inferring the timeout limit from the test's size whether the size is
implicitly or explicitly set.

Tests which explicitly state their timeout category as distinct from their
size will receive the same value as if that timeout had been implicitly set by
the size tag. So a test of size 'small' which declares a 'long' timeout will
have the same effective timeout that a 'large' tests has with no explicit
timeout.

#### `--test_arg={{ "<var>" }}arg{{ "</var>" }}` {:#test-arg}

Passes command-line options/flags/arguments to each test process. This
option can be used multiple times to pass several arguments. For example,
`--test_arg=--logtostderr --test_arg=--v=3`.

Note that, unlike the `bazel run` command, you can't pass test arguments
directly as in `bazel test -- target --logtostderr --v=3`. That's because
extraneous arguments passed to `bazel test` are interpreted as additional test
targets. That is, `--logtostderr` and `--v=3` would each be interpreted as a
test target. This ambiguity doesn't exist for a `bazel run` command, which only
accepts one target.

`--test_arg` can be passed to a `bazel run` command, but it's ignored unless the
target being run is a test target. (As with any other flag, if it's passed in a
`bazel run` command after a `--` token, it's not processed by Bazel but
forwarded verbatim to the executed target.)

#### `--test_env={{ "<var>" }}variable{{ "</var>" }}=_value_` OR `--test_env={{ "<var>" }}variable{{ "</var>" }}` {:#test-env}

Specifies additional variables that must be injected into the test
environment for each test. If {{ "<var>" }}value{{ "</var>" }} is not specified it will be
inherited from the shell environment used to start the `bazel test`
command.

The environment can be accessed from within a test by using
`System.getenv("var")` (Java), `getenv("var")` (C or C++),

#### `--run_under={{ "<var>" }}command-prefix{{ "</var>" }}` {:#test-run-under}

This specifies a prefix that the test runner will insert in front
of the test command before running it. The
{{ "<var>" }}command-prefix{{ "</var>" }} is split into words using Bourne shell
tokenization rules, and then the list of words is prepended to the
command that will be executed.

If the first word is a fully-qualified label (starts with
`//`) it is built. Then the label is substituted by the
corresponding executable location that is prepended to the command
that will be executed along with the other words.

Some caveats apply:

*   The PATH used for running tests may be different than the PATH in your environment,
    so you may need to use an **absolute path** for the `--run_under`
    command (the first word in {{ "<var>" }}command-prefix{{ "</var>" }}).
*   **`stdin` is not connected**, so `--run_under`
    can't be used for interactive commands.

Examples:

<pre>
        --run_under=/usr/bin/strace
        --run_under='/usr/bin/strace -c'
        --run_under=/usr/bin/valgrind
        --run_under='/usr/bin/valgrind --quiet --num-callers=20'
</pre>

#### Test selection {:#test-selection}

As documented under [Output selection options](#output-selection),
you can filter tests by [size](#test-size-filters),
[timeout](#test-timeout-filters),
[tag](#test-tag-filters), or
[language](#test-lang-filters). A convenience
[general name filter](#test-filter) can forward particular
filter args to the test runner.

#### Other options for `bazel test` {:#bazel-test-other-options}

The syntax and the remaining options are exactly like
[`bazel build`](/run/build).

## Running executables {:#running-executables}

The `bazel run` command is similar to `bazel build`, except
it is used to build _and run_ a single target. Here is a typical session
(`//java/myapp:myapp` says hello and prints out its args):

<pre>
  % bazel run java/myapp:myapp -- --arg1 --arg2
  INFO: Analyzed target //java/myapp:myapp (13 packages loaded, 27 targets configured).
  INFO: Found 1 target...
  Target //java/myapp:myapp up-to-date:
    bazel-bin/java/myapp/myapp
  INFO: Elapsed time: 14.290s, Critical Path: 5.54s, ...
  INFO: Build completed successfully, 4 total actions
  INFO: Running command line: bazel-bin/java/myapp/myapp &lt;args omitted&gt;
  Hello there
  $EXEC_ROOT/java/myapp/myapp
  --arg1
  --arg2
</pre>

Note: `--` is needed so that Bazel
does not interpret `--arg1` and `--arg2` as
Bazel options, but rather as part of the command line for running the binary.
Additionally, Bazel will avoid logging these arguments to the console in case
they contain sensitive information.

`bazel run` is similar, but not identical, to directly invoking
the binary built by Bazel and its behavior is different depending on whether the
binary to be invoked is a test or not.

When the binary is not a test, the current working directory will be the
runfiles tree of the binary.

When the binary is a test, the current working directory will be the exec root
and a good-faith attempt is made to replicate the environment tests are usually
run in. The emulation is not perfect, though, and tests that have multiple
shards cannot be run this way (the
`--test_sharding_strategy=disabled` command line option can be used
to work around this)

The following extra environment variables are also available to the binary:

*   `BUILD_WORKSPACE_DIRECTORY`: the root of the workspace where the
    build was run.
*   `BUILD_WORKING_DIRECTORY`: the current working directory where
    Bazel was run from.
*   `BUILD_ID`: the build ID of the `bazel run` invocation. This is usually
    unique, except if Bazel was run with `--script_path` and the resulting
    script is re-used.
*   `BUILD_EXECROOT`: the execution root of the `bazel run` invocation.

These can be used, for example, to interpret file names on the command line in
a user-friendly way.

### Options for `bazel run` {:#bazel-run-options}

#### `--run_under={{ "<var>" }}command-prefix{{ "</var>" }}` {:#run-run-under}

This has the same effect as the `--run_under` option for
`bazel test` ([see above](#test-run-under)),
except that it applies to the command being run by `bazel
run` rather than to the tests being run by `bazel test`
and cannot run under label.

#### Filtering logging outputs from Bazel

When invoking a binary with `bazel run`, Bazel prints logging output from Bazel
itself and the binary under invocation. To make the logs less noisy, you can
suppress the outputs from Bazel itself with the `--ui_event_filters` and
`--noshow_progress` flags.

For example:
`bazel run --ui_event_filters=-info,-stdout,-stderr --noshow_progress //java/myapp:myapp`

### Executing tests {:#executing-tests}

`bazel run` can also execute test binaries, which has the effect of
running the test in a close approximation of the environment described at
[Writing Tests](/reference/test-encyclopedia). Note that none of the
`--test_*` arguments have an effect when running a test in this manner except
`--test_arg` .

## Cleaning build outputs {:#cleaning-build-outputs}

### The `clean` command {:#clean}

Bazel has a `clean` command, analogous to that of Make.
It deletes the output directories for all build configurations performed
by this Bazel instance, or the entire working tree created by this
Bazel instance, and resets internal caches. If executed without any
command-line options, then the output directory for all configurations
will be cleaned.

Recall that each Bazel instance is associated with a single workspace, thus the
`clean` command will delete all outputs from all builds you've done
with that Bazel instance in that workspace.

To completely remove the entire working tree created by a Bazel
instance,  you can specify the `--expunge` option. When
executed with `--expunge`, the clean command simply
removes the entire output base tree which, in addition to the build
output, contains all temp files created by Bazel. It also
stops the Bazel server after the clean, equivalent to the [`shutdown`](#shutdown) command. For example, to
clean up all disk and memory traces of a Bazel instance, you could
specify:

<pre>
  % bazel clean --expunge
</pre>

Alternatively, you can expunge in the background by using
`--expunge_async`. It is safe to invoke a Bazel command
in the same client while the asynchronous expunge continues to run.

Note: This may introduce IO contention.

The `clean` command is provided primarily as a means of
reclaiming disk space for workspaces that are no longer needed.
Bazel's incremental rebuilds may not be
perfect so `clean` can be used to recover a consistent
state when problems arise.

Bazel's design is such that these problems are fixable and
these bugs are a high priority to be fixed. If you
ever find an incorrect incremental build, file a bug report, and report bugs in the tools
rather than using `clean`.

## Querying the dependency graph {:#querying-dependency-graph}

Bazel includes a query language for asking questions about the
dependency graph used during the build. The query language is used
by two commands: query and cquery. The major difference between the
two commands is that query runs after the [loading phase](/run/build#loading)
and cquery runs after the [analysis phase](/run/build#analysis). These tools are an
invaluable aid to many software engineering tasks.

The query language is based on the idea of
algebraic operations over graphs; it is documented in detail in

[Bazel Query Reference](/query/language).
Please refer to that document for reference, for
examples, and for query-specific command-line options.

The query tool accepts several command-line
option. `--output` selects the output format.
`--[no]keep_going` (disabled by default) causes the query
tool to continue to make progress upon errors; this behavior may be
disabled if an incomplete result is not acceptable in case of errors.

The `--[no]tool_deps` option,
enabled by default, causes dependencies in non-target configurations to be included in the
dependency graph over which the query operates.

The `--[no]implicit_deps` option, enabled by default, causes
implicit dependencies to be included in the dependency graph over which the query operates. An
implicit dependency is one that is not explicitly specified in the BUILD file
but added by bazel.

Example: "Show the locations of the definitions (in BUILD files) of
all genrules required to build all the tests in the PEBL tree."

<pre>
  bazel query --output location 'kind(genrule, deps(kind(".*_test rule", foo/bar/pebl/...)))'
</pre>

## Querying the action graph {:#aquery}

Caution: The aquery command is still experimental and its API will change.

The `aquery` command allows you to query for actions in your build graph.
It operates on the post-analysis configured target graph and exposes
information about actions, artifacts and their relationships.

The tool accepts several command-line options.
`--output` selects the output format. The default output format
(`text`) is human-readable, use `proto` or `textproto` for
machine-readable format.
Notably, the aquery command runs on top of a regular Bazel build and inherits
the set of options available during a build.

It supports the same set of functions that is also available to traditional
`query` but `siblings`, `buildfiles` and
`tests`.

For more details, see [Action Graph Query](/query/aquery).

## Miscellaneous commands and options {:#misc-commands-options}

### `help` {:#help}

The `help` command provides on-line help. By default, it
shows a summary of available commands and help topics, as shown in
[Building with Bazel](/run/build#quickstart).
Specifying an argument displays detailed help for a particular
topic. Most topics are Bazel commands, such as `build`
or `query`, but there are some additional help topics
that do not correspond to commands.

#### `--[no]long` (`-l`) {:#long}

By default, `bazel help [{{ "<var>" }}topic{{ "</var>" }}]` prints only a
summary of the relevant options for a topic. If
the `--long` option is specified, the type, default value
and full description of each option is also printed.

### `shutdown` {:#shutdown}

Bazel server processes may be stopped by using the `shutdown`
command. This command causes the Bazel server to exit as soon as it
becomes idle (for example, after the completion of any builds or other
commands that are currently in progress). For more details, see
[Client/server implementation](/run/client-server).

Bazel servers stop themselves after an idle timeout, so this command
is rarely necessary; however, it can be useful in scripts when it is
known that no further builds will occur in a given workspace.

`shutdown` accepts one
option, `--iff_heap_size_greater_than _n_`, which
requires an integer argument (in MB). If specified, this makes the shutdown
conditional on the amount of memory already consumed. This is
useful for scripts that initiate a lot of builds, as any memory
leaks in the Bazel server could cause it to crash spuriously on
occasion; performing a conditional restart preempts this condition.

### `info` {:#info}

The `info` command prints various values associated with
the Bazel server instance, or with a specific build configuration.
(These may be used by scripts that drive a build.)

The `info` command also permits a single (optional)
argument, which is the name of one of the keys in the list below.
In this case, `bazel info {{ "<var>" }}key{{ "</var>" }}` will print only
the value for that one key. (This is especially convenient when
scripting Bazel, as it avoids the need to pipe the result
through `sed -ne /key:/s/key://p`:

#### Configuration-independent data {:#configuration-independent-data}

*   `release`: the release label for this Bazel
    instance, or "development version" if this is not a released
    binary.
*   `workspace` the absolute path to the base workspace
    directory.
*   `install_base`: the absolute path to the installation
    directory used by this Bazel instance for the current user. Bazel
    installs its internally required executables below this directory.

*   `output_base`: the absolute path to the base output
    directory used by this Bazel instance for the current user and
    workspace combination. Bazel puts all of its scratch and build
    output below this directory.
*   `execution_root`: the absolute path to the execution
    root directory under output_base. This directory is the root for all files
    accessible to commands executed during the build, and is the working
    directory for those commands. If the workspace directory is writable, a
    symlink named `bazel-<workspace>`
    is placed there pointing to this directory.
*   `output_path`: the absolute path to the output
    directory beneath the execution root used for all files actually
    generated as a result of build commands. If the workspace directory is
    writable, a symlink named `bazel-out` is placed there pointing
    to this directory.
*   `server_pid`: the process ID of the Bazel server
     process.
*   `server_log`: the absolute path to the Bazel server's debug log file.
    This file contains debugging information for all commands over the lifetime of the
    Bazel server, and is intended for human consumption by Bazel developers and power users.
*   `command_log`: the absolute path to the command log file;
    this contains the interleaved stdout and stderr streams of the most recent
    Bazel command. Note that running `bazel info` will overwrite the
    contents of this file, since it then becomes the most recent Bazel command.
    However, the location of the command log file will not change unless you
    change the setting of the `--output_base` or
    `--output_user_root` options.
*   `used-heap-size`,
      `committed-heap-size`,
      `max-heap-size`: reports various JVM heap size
    parameters. Respectively: memory currently used, memory currently
    guaranteed to be available to the JVM from the system, maximum
    possible allocation.
*   `gc-count`, `gc-time`: The cumulative count of
    garbage collections since the start of this Bazel server and the time spent
    to perform them. Note that these values are not reset at the start of every
    build.
*   `package_path`: A colon-separated list of paths which would be
    searched for packages by bazel. Has the same format as the
    `--package_path` build command line argument.

Example: the process ID of the Bazel server.

<pre>% bazel info server_pid
1285
</pre>

#### Configuration-specific data {:#configuration-specific-data}

These data may be affected by the configuration options passed
to `bazel info`, for
example `--cpu`, `--compilation_mode`,
etc. The `info` command accepts all
the options that control dependency
analysis, since some of these determine the location of the
output directory of a build, the choice of compiler, etc.

*   `bazel-bin`, `bazel-testlogs`,
    `bazel-genfiles`: reports the absolute path to
    the `bazel-*` directories in which programs generated by the
    build are located. This is usually, though not always, the same as
    the `bazel-*` symlinks created in the base workspace directory after a
    successful build. However, if the workspace directory is read-only,
    no `bazel-*` symlinks can be created. Scripts that use
    the value reported by `bazel info`, instead of assuming the
    existence of the symlink, will be more robust.
*   The complete
    ["Make" environment](/reference/be/make-variables). If the `--show_make_env` flag is
    specified, all variables in the current configuration's "Make" environment
    are also displayed (such as `CC`, `GLIBC_VERSION`, etc).
    These are the variables accessed using the `$(CC)`
    or `varref("CC")` syntax inside BUILD files.

Example: the C++ compiler for the current configuration.
This is the `$(CC)` variable in the "Make" environment,
so the `--show_make_env` flag is needed.

<pre>
  % bazel info --show_make_env -c opt COMPILATION_MODE
  opt
</pre>

Example: the `bazel-bin` output directory for the current
configuration. This is guaranteed to be correct even in cases where
the `bazel-bin` symlink cannot be created for some reason
(such as if you are building from a read-only directory).

<pre>% bazel info --cpu=piii bazel-bin
/var/tmp/_bazel_johndoe/fbd0e8a34f61ce5d491e3da69d959fe6/execroot/io_bazel/bazel-out/piii-opt/bin
% bazel info --cpu=k8 bazel-bin
/var/tmp/_bazel_johndoe/fbd0e8a34f61ce5d491e3da69d959fe6/execroot/io_bazel/bazel-out/k8-opt/bin
</pre>

### `version` and `--version` {:#version}

The version command prints version details about the built Bazel
binary, including the changelist at which it was built and the date.
These are particularly useful in determining if you have the latest
Bazel, or if you are reporting bugs. Some of the interesting values
are:

*   `changelist`: the changelist at which this version of
    Bazel was released.
*   `label`: the release label for this Bazel
    instance, or "development version" if this is not a released
    binary. Very useful when reporting bugs.

`bazel --version`, with no other args, will emit the same output as
`bazel version --gnu_format`, except without the side-effect of potentially starting
a Bazel server or unpacking the server archive. `bazel --version` can be run from
anywhere - it does not require a workspace directory.

### `mobile-install` {:#mobile-install}

The `mobile-install` command installs apps to mobile devices.
Currently only Android devices running ART are supported.

See [bazel mobile-install](/docs/mobile-install) for more information.

Note: This command does not install the same thing that
`bazel build` produces: Bazel tweaks the app so that it can be
built, installed and re-installed quickly. This should, however, be mostly
transparent to the app.

The following options are supported:

#### `--incremental` {:#incremental}

If set, Bazel tries to install the app incrementally, that is, only those
parts that have changed since the last build. This cannot update resources
referenced from `AndroidManifest.xml`, native code or Java
resources (such as those referenced by `Class.getResource()`). If these
things change, this option must be omitted. Contrary to the spirit of Bazel
and due to limitations of the Android platform, it is the
**responsibility of the user** to know when this command is good enough and
when a full install is needed.

If you are using a device with Marshmallow or later, consider the
[`--split_apks`](#split-apks) flag.

#### `--split_apks` {:#split-apks}

Whether to use split apks to install and update the application on the device.
Works only with devices with Marshmallow or later. Note that the
[`--incremental`](#incremental) flag
is not necessary when using `--split_apks`.

#### `--start_app` {:#start-app}

Starts the app in a clean state after installing. Equivalent to `--start=COLD`.

#### `--debug_app` {:#debug-app}

Waits for debugger to be attached before starting the app in a clean state after installing.
Equivalent to `--start=DEBUG`.

#### `--start=_start_type_` {:#start}

How the app should be started after installing it. Supported _start_type_s are:

*   `NO` Does not start the app. This is the default.
*   `COLD` Starts the app from a clean state after install.
*   `WARM` Preserves and restores the application state on incremental installs.
*   `DEBUG` Waits for the debugger before starting the app in a clean state after
    install.

Note: If more than one of `--start=_start_type_`, `--start_app` or
`--debug_app` is set, the last value is used.

#### `--adb={{ "<var>" }}path{{ "</var>" }}` {:#adb}

Indicates the `adb` binary to be used.

The default is to use the adb in the Android SDK specified by
[`--android_sdk`](#android-sdk).

#### `--adb_arg={{ "<var>" }}serial{{ "</var>" }}` {:#adb-arg}

Extra arguments to `adb`. These come before the subcommand in the
command line and are typically used to specify which device to install to.
For example, to select the Android device or emulator to use:

<pre>% bazel mobile-install --adb_arg=-s --adb_arg=deadbeef
</pre>

invokes `adb` as

<pre>
adb -s deadbeef install ...
</pre>

#### `--incremental_install_verbosity={{ "<var>" }}number{{ "</var>" }}` {:#incremental-install-verbosity}

The verbosity for incremental install. Set to 1 for debug logging to be
printed to the console.

### `dump` {:#dump}

The `dump` command prints to stdout a dump of the
internal state of the Bazel server. This command is intended
primarily for use by Bazel developers, so the output of this command
is not specified, and is subject to change.

By default, command will just print help message outlining possible
options to dump specific areas of the Bazel state. In order to dump
internal state, at least one of the options must be specified.

Following options are supported:

*   `--action_cache` dumps action cache content.
*   `--packages` dumps package cache content.
*   `--skyframe` dumps state of internal Bazel dependency graph.
*   `--rules` dumps rule summary for each rule and aspect class,
    including counts and action counts. This includes both native and Starlark rules.
    If memory tracking is enabled, then the rules' memory consumption is also printed.
*   `--skylark_memory` dumps a
    [pprof](https://github.com/google/pprof) compatible .gz file to the specified path.
    You must enable memory tracking for this to work.

#### Memory tracking {:#memory-tracking}

Some `dump` commands require memory tracking. To turn this on, you have to pass
startup flags to Bazel:

*   `--host_jvm_args=-javaagent:$BAZEL/third_party/allocation_instrumenter/java-allocation-instrumenter-3.3.4.jar`
*   `--host_jvm_args=-DRULE_MEMORY_TRACKER=1`

The java-agent is checked into Bazel at
`third_party/allocation_instrumenter/java-allocation-instrumenter-3.3.4.jar`, so
make sure you adjust `$BAZEL` for where you keep your Bazel repository.

Do not forget to keep passing these options to Bazel for every command or the server will
restart.

Example:

<pre>
    % bazel --host_jvm_args=-javaagent:$BAZEL/third_party/allocation_instrumenter/java-allocation-instrumenter-3.3.4.jar \
    --host_jvm_args=-DRULE_MEMORY_TRACKER=1 \
    build --nobuild &lt;targets&gt;

    # Dump rules
    % bazel --host_jvm_args=-javaagent:$BAZEL/third_party/allocation_instrumenter/java-allocation-instrumenter-3.3.4.jar \
    --host_jvm_args=-DRULE_MEMORY_TRACKER=1 \
    dump --rules

    # Dump Starlark heap and analyze it with pprof
    % bazel --host_jvm_args=-javaagent:$BAZEL/third_party/allocation_instrumenter/java-allocation-instrumenter-3.3.4.jar \
    --host_jvm_args=-DRULE_MEMORY_TRACKER=1 \
    dump --skylark_memory=$HOME/prof.gz
    % pprof -flame $HOME/prof.gz
</pre>

### `analyze-profile` {:#analyze-profile}

The `analyze-profile` command analyzes a
[JSON trace profile](/advanced/performance/json-trace-profile) previously
gathered during a Bazel invocation.

### `canonicalize-flags` {:#canonicalize-flags}

The [`canonicalize-flags`](/reference/command-line-reference#canonicalize-flags-options)
command, which takes a list of options for a Bazel command and returns a list of
options that has the same effect. The new list of options is canonical. For example,
two lists of options with the same effect are canonicalized to the same new list.

The `--for_command` option can be used to select between different
commands. At this time, only `build` and `test` are
supported. Options that the given command does not support cause an error.

Note: A small number of options cannot be reordered, because Bazel cannot
ensure that the effect is identical. Also note that this command
_does not_ expand flags from `--config`.

As an example:

<pre>
  % bazel canonicalize-flags -- --config=any_name --test_tag_filters="-lint"
  --config=any_name
  --test_tag_filters=-lint
</pre>

### Startup options {:#startup-options}

The options described in this section affect the startup of the Java
virtual machine used by Bazel server process, and they apply to all
subsequent commands handled by that server. If there is an already
running Bazel server and the startup options do not match, it will
be restarted.

All of the options described in this section must be specified using the
`--key=value` or `--key value`
syntax. Also, these options must appear _before_ the name of the Bazel
command. Use `startup --key=value` to list these in a `.bazelrc` file.

#### `--output_base={{ "<var>" }}dir{{ "</var>" }}` {:#output-base}

This option requires a path argument, which must specify a
writable directory. Bazel will use this location to write all its
output. The output base is also the key by which the client locates
the Bazel server. By changing the output base, you change the server
which will handle the command.

By default, the output base is derived from the user's login name,
and the name of the workspace directory (actually, its MD5 digest),
so a typical value looks like:
`/var/tmp/google/_bazel_johndoe/d41d8cd98f00b204e9800998ecf8427e`.

Note: The client uses the output base to find the Bazel server
instance, so if you specify a different output base in a Bazel
command, a different server will be found (or started) to handle the
request. It's possible to perform two concurrent builds in the same
workspace directory by varying the output base.

For example:

<pre>
 OUTPUT_BASE=/var/tmp/google/_bazel_johndoe/custom_output_base
% bazel --output_base ${OUTPUT_BASE}1 build //foo  &amp;  bazel --output_base ${OUTPUT_BASE}2 build //bar
</pre>

In this command, the two Bazel commands run concurrently (because of
the shell `&amp;` operator), each using a different Bazel
server instance (because of the different output bases).
In contrast, if the default output base was used in both commands,
then both requests would be sent to the same server, which would
handle them sequentially: building `//foo` first, followed
by an incremental build of `//bar`.

Note: We recommend you do not use an NFS or similar networked file system for the root
directory, as the higher access latency will cause noticeably slower builds.

#### `--output_user_root={{ "<var>" }}dir{{ "</var>" }}` {:#output-user-root}

Points to the root directory where output and install bases are created. The directory
must either not exist or be owned by the calling user. In the past,
this was allowed to point to a directory shared among various users
but it's not allowed any longer. This may be allowed once
[issue #11100](https://github.com/bazelbuild/bazel/issues/11100){: .external} is addressed.

If the `--output_base` option is specified, it overrides
using `--output_user_root` to calculate the output base.

The install base location is calculated based on
`--output_user_root`, plus the MD5 identity of the Bazel embedded
binaries.

You can use the `--output_user_root` option to choose an
alternate base location for all of Bazel's output (install base and output
base) if there is a better location in your filesystem layout.

Note: We recommend you do not use an NFS or similar networked file system for the root
directory, as the higher access latency will cause noticeably slower builds.

#### `--server_javabase={{ "<var>" }}dir{{ "</var>" }}` {:#server-javabase}

Specifies the Java virtual machine in which _Bazel itself_ runs. The value must be a path to
the directory containing a JDK or JRE. It should not be a label.
This option should appear before any Bazel command, for example:

<pre>
  % bazel --server_javabase=/usr/local/buildtools/java/jdk build //foo
</pre>

This flag does _not_ affect the JVMs used by Bazel subprocesses such as applications, tests,
tools, and so on. Use build options [--javabase](#javabase) or
[--host_javabase](#host-javabase) instead.

This flag was previously named `--host_javabase` (sometimes referred to as the
'left-hand side' `--host_javabase`), but was renamed to avoid confusion with the
build flag [--host_javabase](#host-javabase) (sometimes referred to as the
'right-hand side' `--host_javabase`).

#### `--host_jvm_args={{ "<var>" }}string{{ "</var>" }}` {:#host-jvm-args}

Specifies a startup option to be passed to the Java virtual machine in which _Bazel itself_
runs. This can be used to set the stack size, for example:

<pre>
  % bazel --host_jvm_args="-Xss256K" build //foo
</pre>

This option can be used multiple times with individual arguments. Note that
setting this flag should rarely be needed. You can also pass a space-separated list of strings,
each of which will be interpreted as a separate JVM argument, but this feature will soon be
deprecated.

That this does _not_ affect any JVMs used by
subprocesses of Bazel: applications, tests, tools, and so on. To pass
JVM options to executable Java programs, whether run by `bazel
run` or on the command-line, you should use
the `--jvm_flags` argument which
all `java_binary` and `java_test` programs
support. Alternatively for tests, use `bazel test --test_arg=--jvm_flags=foo ...`.

#### `--host_jvm_debug` {:#host-java-debug}

This option causes the Java virtual machine to wait for a connection
from a JDWP-compliant debugger before
calling the main method of _Bazel itself_. This is primarily
intended for use by Bazel developers.

Note: This does _not_ affect any JVMs used by subprocesses of Bazel:
applications, tests, tools, etc.

#### `--autodetect_server_javabase` {:#autodetect-server-javabase}

This option causes Bazel to automatically search for an installed JDK on startup,
and to fall back to the installed JRE if the embedded JRE isn't available.
`--explicit_server_javabase` can be used to pick an explicit JRE to
run Bazel with.

#### `--batch` {:#batch}

Batch mode causes Bazel to not use the
[standard client/server mode](/run/client-server), but instead runs a bazel
java process for a single command, which has been used for more predictable
semantics with respect to signal handling, job control, and environment
variable inheritance, and is necessary for running bazel in a chroot jail.

Batch mode retains proper queueing semantics within the same output_base.
That is, simultaneous invocations will be processed in order, without overlap.
If a batch mode Bazel is run on a client with a running server, it first
kills the server before processing the command.

Bazel will run slower in batch mode, or with the alternatives described above.
This is because, among other things, the build file cache is memory-resident, so it is not
preserved between sequential batch invocations.
Therefore, using batch mode often makes more sense in cases where performance
is less critical, such as continuous builds.

Warning: `--batch` is sufficiently slower than standard
client/server mode. Additionally it might not support all of the features and optimizations which
are made possible by a persistent Bazel server. If you're using `--batch`
for the purpose of build isolation, you should use the command option
`--nokeep_state_after_build`, which guarantees that no incremental
in-memory state is kept between builds. In order to restart the Bazel server and JVM after a
build, please explicitly do so using the "shutdown" command.

#### `--max_idle_secs={{ "<var>" }}n{{ "</var>" }}` {:#max-idle-secs}

This option specifies how long, in seconds, the Bazel server process
should wait after the last client request, before it exits. The
default value is 10800 (3 hours). `--max_idle_secs=0` will cause the
Bazel server process to persist indefinitely.

Note: this flag is only read if Bazel needs
to start a new server. Changing this option will not cause the server to restart.

Note: system sleep time where a build is not running is counted as idle time.

This option may be used by scripts that invoke Bazel to ensure that
they do not leave Bazel server processes on a user's machine when they
would not be running otherwise.
For example, a presubmit script might wish to
invoke `bazel query` to ensure that a user's pending
change does not introduce unwanted dependencies. However, if the
user has not done a recent build in that workspace, it would be
undesirable for the presubmit script to start a Bazel server just
for it to remain idle for the rest of the day.
By specifying a small value of `--max_idle_secs` in the
query request, the script can ensure that _if_ it caused a new
server to start, that server will exit promptly, but if instead
there was already a server running, that server will continue to run
until it has been idle for the usual time. Of course, the existing
server's idle timer will be reset.

#### `--[no]shutdown_on_low_sys_mem` {:#shutdown-on-low-sys-mem}

If enabled and `--max_idle_secs` is set to a positive duration,
after the build server has been idle for a while, shut down the server when the system is
low on memory. Linux only.

In addition to running an idle check corresponding to max_idle_secs, the build server will
starts monitoring available system memory after the server has been idle for some time.
If the available system memory becomes critically low, the server will exit.

#### `--[no]block_for_lock` {:#block-for-lock}

If enabled, Bazel will wait for other Bazel commands holding the
server lock to complete before progressing. If disabled, Bazel will
exit in error if it cannot immediately acquire the lock and
proceed.

Developers might use this in presubmit checks to avoid long waits caused
by another Bazel command in the same client.

#### `--io_nice_level={{ "<var>" }}n{{ "</var>" }}` {:#io-nice-level}

Sets a level from 0-7 for best-effort IO scheduling. 0 is highest priority,
7 is lowest. The anticipatory scheduler may only honor up to priority 4.
Negative values are ignored.

#### `--batch_cpu_scheduling` {:#batch-cpu-scheduling}

Use `batch` CPU scheduling for Bazel. This policy is useful for
workloads that are non-interactive, but do not want to lower their nice value.
See 'man 2 sched_setscheduler'. This policy may provide for better system
interactivity at the expense of Bazel throughput.

### Miscellaneous options {:#misc-options}

#### `--[no]announce_rc` {:#announce-rc}

Controls whether Bazel announces startup options and command options read from
the bazelrc files when starting up.

#### `--color (yes|no|auto)` {:#color}

This option determines whether Bazel will use colors to highlight
its output on the screen.

If this option is set to `yes`, color output is enabled.
If this option is set to `auto`, Bazel will use color output only if
the output is being sent to a terminal and the TERM environment variable
is set to a value other than `dumb`, `emacs`, or `xterm-mono`.
If this option is set to `no`, color output is disabled,
regardless of whether the output is going to a terminal and regardless
of the setting of the TERM environment variable.

#### `--config={{ "<var>" }}name{{ "</var>" }}` {:#config}

Selects additional config section from
[the rc files](/run/bazelrc#bazelrc-file-locations); for the current `command`,
it also pulls in the options from `command:name` if such a section exists. Can be
specified multiple times to add flags from several config sections. Expansions can refer to other
definitions (for example, expansions can be chained).

#### `--curses (yes|no|auto)` {:#curses}

This option determines whether Bazel will use cursor controls
in its screen output. This results in less scrolling data, and a more
compact, easy-to-read stream of output from Bazel. This works well with
`--color`.

If this option is set to `yes`, use of cursor controls is enabled.
If this option is set to `no`, use of cursor controls is disabled.
If this option is set to `auto`, use of cursor controls will be
enabled under the same conditions as for `--color=auto`.

#### `--[no]show_timestamps` {:#show-timestamps}

If specified, a timestamp is added to each message generated by
Bazel specifying the time at which the message was displayed.
