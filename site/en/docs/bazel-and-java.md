Project: /_project.yaml
Book: /_book.yaml

# Java and Bazel

{% include "_buttons.html" %}

This page contains resources that help you use Bazel with Java projects. It
links to a tutorial, build rules, and other information specific to building
Java projects with Bazel.

## Working with Bazel {:#working-with-bazel}

The following resources will help you work with Bazel on Java projects:

*   [Tutorial: Building a Java Project](/start/java)
*   [Java rules](/reference/be/java)

## Migrating to Bazel {:#migrating-to-bazel}

If you currently build your Java projects with Maven, follow the steps in the
migration guide to start building your Maven projects with Bazel:

*   [Migrating from Maven to Bazel](/migrate/maven)

## Java versions {:#java-versions}

There are two relevant versions of Java that are set with configuration flags:

*   the version of the source files in the repository
*   the version of the Java runtime that is used to execute the code and to test
    it

### Configuring the version of the source code in your repository {:#config-source-code}

Without an additional configuration, Bazel assumes all Java source files in the
repository are written in a single Java version. To specify the version of the
sources in the repository add `build --java_language_version={ver}` to
`.bazelrc` file, where `{ver}` is for example `11`. Bazel repository owners
should set this flag so that Bazel and its users can reference the source code's
Java version number. For more details, see
[Java language version flag](/docs/user-manual#java-language-version).

### Configuring the JVM used to execute and test the code {:#config-jvm}

Bazel uses one JDK for compilation and another JVM to execute and test the code.

By default Bazel compiles the code using a JDK it downloads and it executes and
tests the code with the JVM installed on the local machine. Bazel searches for
the JVM using `JAVA_HOME` or path.

The resulting binaries are compatible with locally installed JVM in system
libraries, which means the resulting binaries depend on what is installed on the
machine.

To configure the JVM used for execution and testing use `--java_runtime_version`
flag. The default value is `local_jdk`.

### Hermetic testing and compilation {:#hermetic-testing}

To create a hermetic compile, you can use command line flag
`--java_runtime_version=remotejdk_11`. The code is compiled for, executed, and
tested on the JVM downloaded from a remote repository. For more details, see
[Java runtime version flag](/docs/user-manual#java_runtime_version).

### Configuring compilation and execution of build tools in Java {:#config-build-tools-java}

There is a second pair of JDK and JVM used to build and execute tools, which are
used in the build process, but are not in the build results. That JDK and JVM
are controlled using `--tool_java_language_version` and
`--tool_java_runtime_version`. Default values are `11` and `remotejdk_11`,
respectively.

#### Compiling using locally installed JDK {:#compile-using-jdk}

Bazel by default compiles using remote JDK, because it is overriding JDK's
internals. The compilation toolchains using locally installed JDK are configured,
however not used.

To compile using locally installed JDK, that is use the compilation toolchains
for local JDK, use additional flag `--extra_toolchains=@local_jdk//:all`,
however, mind that this may not work on JDK of arbitrary vendors.

For more details, see
[configuring Java toolchains](#config-java-toolchains).

## Best practices {:#best-practices}

In addition to [general Bazel best practices](/configure/best-practices), below are
best practices specific to Java projects.

### Directory structure {:#directory-structure}

Prefer Maven's standard directory layout (sources under `src/main/java`, tests
under `src/test/java`).

### BUILD files {:#build-files}

Follow these guidelines when creating your `BUILD` files:

*   Use one `BUILD` file per directory containing Java sources, because this
    improves build performance.

*   Every `BUILD` file should contain one `java_library` rule that looks like
    this:

    ```python
    java_library(
        name = "directory-name",
        srcs = glob(["*.java"]),
        deps = [...],
    )
    ```

*   The name of the library should be the name of the directory containing the
    `BUILD` file. This makes the label of the library shorter, that is use
    `"//package"` instead of `"//package:package"`.

*   The sources should be a non-recursive [`glob`](/reference/be/functions#glob) of
    all Java files in the directory.

*   Tests should be in a matching directory under `src/test` and depend on this
    library.

## Creating new rules for advanced Java builds {:#rules-advanced-java-builds}

**Note**: Creating new rules is for advanced build and test scenarios. You do
not need it when getting started with Bazel.

The following modules, configuration fragments, and providers will help you
[extend Bazel's capabilities](/extending/concepts) when building your Java
projects:

*   Main Java module: [`java_common`](/rules/lib/toplevel/java_common)
*   Main Java provider: [`JavaInfo`](/rules/lib/providers/JavaInfo)
*   Configuration fragment: [`java`](/rules/lib/fragments/java)
*   Other modules:

    *   [`java_annotation_processing`](/rules/lib/builtins/java_annotation_processing)
    *   [`java_compilation_info`](/rules/lib/providers/java_compilation_info)
    *   [`java_output_jars`](/rules/lib/providers/java_output_jars)
    *   [`JavaRuntimeInfo`](/rules/lib/providers/JavaRuntimeInfo)
    *   [`JavaToolchainInfo`](/rules/lib/providers/JavaToolchainInfo)

## Configuring the Java toolchains {:#config-java-toolchains}

Bazel uses two types of Java toolchains:
- execution, used to execute and test Java binaries, controlled with
  `--java_runtime_version` flag
- compilation, used to compile Java sources, controlled with
  `--java_language_version` flag

### Configuring additional execution toolchains {:#config-execution-toolchains}

Execution toolchain is the JVM, either local or from a repository, with some
additional information about its version, operating system, and CPU
architecture.

Java execution toolchains may added using the `local_java_repository` or
`remote_java_repository` repo rules in a module extension. Adding the rule makes
the JVM available using a flag. When multiple definitions for the same operating
system and CPU architecture are given, the first one is used.

Example configuration of local JVM:

```python
load("@rules_java//toolchains:local_java_repository.bzl", "local_java_repository")

local_java_repository(
  name = "additionaljdk",          # Can be used with --java_runtime_version=additionaljdk, --java_runtime_version=11 or --java_runtime_version=additionaljdk_11
  version = 11,                    # Optional, if not set it is autodetected
  java_home = "/usr/lib/jdk-15/",  # Path to directory containing bin/java
)
```

Example configuration of remote JVM:

```python
load("@rules_java//toolchains:remote_java_repository.bzl", "remote_java_repository")

remote_java_repository(
  name = "openjdk_canary_linux_arm",
  prefix = "openjdk_canary", # Can be used with --java_runtime_version=openjdk_canary_11
  version = "11",            # or --java_runtime_version=11
  target_compatible_with = [ # Specifies constraints this JVM is compatible with
    "@platforms//cpu:arm",
    "@platforms//os:linux",
  ],
  urls = ...,               # Other parameters are from http_repository rule.
  sha256 = ...,
  strip_prefix = ...
)
```

### Configuring additional compilation toolchains {:#config-compilation-toolchains}

Compilation toolchain is composed of JDK and multiple tools that Bazel uses
during the compilation and that provides additional features, such as: Error
Prone, strict Java dependencies, header compilation, Android desugaring,
coverage instrumentation, and genclass handling for IDEs.

JavaBuilder is a Bazel-bundled tool that executes compilation, and provides the
aforementioned features. Actual compilation is executed using the internal
compiler by the JDK. The JDK used for compilation is specified by `java_runtime`
attribute of the toolchain.

Bazel overrides some JDK internals. In case of JDK version > 9,
`java.compiler` and `jdk.compiler` modules are patched using JDK's flag
`--patch_module`. In case of JDK version 8, the Java compiler is patched using
`-Xbootclasspath` flag.

VanillaJavaBuilder is a second implementation of JavaBuilder,
which does not modify JDK's internal compiler and does not have any of the
additional features. VanillaJavaBuilder is not used by any of the built-in
toolchains.

In addition to JavaBuilder, Bazel uses several other tools during compilation.

The `ijar` tool processes `jar` files to remove everything except call
signatures. Resulting jars are called header jars. They are used to improve the
compilation incrementality by only recompiling downstream dependents when the
body of a function changes.

The `singlejar` tool packs together multiple `jar` files into a single one.

The `genclass` tool post-processes the output of a Java compilation, and produces
a `jar` containing only the class files for sources that were generated by
annotation processors.

The `JacocoRunner` tool runs Jacoco over instrumented files and outputs results in
LCOV format.

The `TestRunner` tool executes JUnit 4 tests in a controlled environment.

You can reconfigure the compilation by adding `default_java_toolchain` macro to
a `BUILD` file and registering it either by adding `register_toolchains` rule to
the `MODULE.bazel` file or by using
[`--extra_toolchains`](/docs/user-manual#extra-toolchains) flag.

The toolchain is only used when the `source_version` attribute matches the
value specified by `--java_language_version` flag.

Example toolchain configuration:

```python
load(
  "@rules_java//toolchains:default_java_toolchain.bzl",
  "default_java_toolchain", "DEFAULT_TOOLCHAIN_CONFIGURATION", "BASE_JDK9_JVM_OPTS", "DEFAULT_JAVACOPTS"
)

default_java_toolchain(
  name = "repository_default_toolchain",
  configuration = DEFAULT_TOOLCHAIN_CONFIGURATION,        # One of predefined configurations
                                                          # Other parameters are from java_toolchain rule:
  java_runtime = "@rules_java//toolchains:remote_jdk11", # JDK to use for compilation and toolchain's tools execution
  jvm_opts = BASE_JDK9_JVM_OPTS + ["--enable_preview"],   # Additional JDK options
  javacopts = DEFAULT_JAVACOPTS + ["--enable_preview"],   # Additional javac options
  source_version = "9",
)
```

which can be used using `--extra_toolchains=//:repository_default_toolchain_definition`
or by adding `register_toolchains("//:repository_default_toolchain_definition")`
to the workpace.

Predefined configurations:

-   `DEFAULT_TOOLCHAIN_CONFIGURATION`: all features, supports JDK versions >= 9
-   `VANILLA_TOOLCHAIN_CONFIGURATION`: no additional features, supports JDKs of
    arbitrary vendors.
-   `PREBUILT_TOOLCHAIN_CONFIGURATION`: same as default, but only use prebuilt
    tools (`ijar`, `singlejar`)
-   `NONPREBUILT_TOOLCHAIN_CONFIGURATION`: same as default, but all tools are
    built from sources (this may be useful on operating system with different
    libc)

#### Configuring JVM and Java compiler flags {:#config-jvm}

You may configure JVM and javac flags either with flags or with
 `default_java_toolchain` attributes.

The relevant flags are `--jvmopt`, `--host_jvmopt`, `--javacopt`,  and
`--host_javacopt`.

The relevant `default_java_toolchain` attributes are `javacopts`, `jvm_opts`,
`javabuilder_jvm_opts`, and `turbine_jvm_opts`.

#### Package specific Java compiler flags configuration {:#package-java-compiler-flags}

You can configure different Java compiler flags for specific source
files using `package_configuration` attribute of `default_java_toolchain`.
Please refer to the example below.

```python
load("@rules_java//toolchains:default_java_toolchain.bzl", "default_java_toolchain")

# This is a convenience macro that inherits values from Bazel's default java_toolchain
default_java_toolchain(
    name = "toolchain",
    package_configuration = [
        ":error_prone",
    ],
    visibility = ["//visibility:public"],
)

# This associates a set of javac flags with a set of packages
java_package_configuration(
    name = "error_prone",
    javacopts = [
        "-Xep:MissingOverride:ERROR",
    ],
    packages = ["error_prone_packages"],
)

# This is a regular package_group, which is used to specify a set of packages to apply flags to
package_group(
    name = "error_prone_packages",
    packages = [
        "//foo/...",
        "-//foo/bar/...", # this is an exclusion
    ],
)
```

#### Multiple versions of Java source code in a single repository {:#java-source-single-repo}

Bazel only supports compiling a single version of Java sources in a build.
build. This means that when building a Java test or an application, all
 dependencies are built against the same Java version.

However, separate builds may be executed using different flags.

To make the task of using different flags easier, sets of flags for a specific
version may be grouped with `.bazelrc` configs":

```python
build:java8 --java_language_version=8
build:java8 --java_runtime_version=local_jdk_8
build:java11 --java_language_version=11
build:java11 --java_runtime_version=remotejdk_11
```

These configs can be used with the `--config` flag, for example
`bazel test --config=java11 //:java11_test`.
