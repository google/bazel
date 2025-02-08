Project: /_project.yaml
Book: /_book.yaml
keywords: bzlmod

# Bzlmod Migration Guide

{% include "_buttons.html" %}

Due to the [shortcomings of
WORKSPACE](/external/overview#workspace-shortcomings), Bzlmod is going to
replace the legacy WORKSPACE system. The WORKSPACE file will be disabled by
default in Bazel 8 (late 2024) and will be removed in Bazel 9 (late 2025).
This guide helps you migrate your project to Bzlmod and drop WORKSPACE for
fetching external dependencies.

## WORKSPACE vs Bzlmod {:#workspace-vs-bzlmod}

Bazel's WORKSPACE and Bzlmod offer similar features with different syntax. This
section explains how to migrate from specific WORKSPACE functionalities to
Bzlmod.

### Define the root of a Bazel workspace {:#define-root}

The WORKSPACE file marks the source root of a Bazel project, this responsibility
is replaced by MODULE.bazel in Bazel version 6.3 and later. With Bazel versions
prior to 6.3, there should still be a `WORKSPACE` or `WORKSPACE.bazel` file at
your workspace root, maybe with comments like:

*   **WORKSPACE**

    ```python
    # This file marks the root of the Bazel workspace.
    # See MODULE.bazel for external dependencies setup.
    ```

### Enable Bzlmod in your bazelrc {:#enable-bzlmod}

`.bazelrc` lets you set flags that apply every time your run Bazel. To enable
Bzlmod, use the `--enable_bzlmod` flag, and apply it to the `common` command so
it applies to every command:

* **.bazelrc**

    ```
    # Enable Bzlmod for every Bazel command
    common --enable_bzlmod
    ```

### Specify repository name for your workspace {:#specify-repo-name}

*   **WORKSPACE**

    The [`workspace`](/rules/lib/globals/workspace#workspace) function is used
    to specify a repository name for your workspace. This allows a target
    `//foo:bar` in the workspace to be referenced as `@<workspace
    name>//foo:bar`. If not specified, the default repository name for your
    workspace is `__main__`.

    ```python
    ## WORKSPACE
    workspace(name = "com_foo_bar")
    ```

*   **Bzlmod**

    It's recommended to reference targets in the same workspace with the
    `//foo:bar` syntax without `@<repo name>`. But if you do need the old syntax
    , you can use the module name specified by the
    [`module`](/rules/lib/globals/module#module) function as the repository
    name. If the module name is different from the needed repository name, you
    can use `repo_name` attribute of the
    [`module`](/rules/lib/globals/module#module) function to override the
    repository name.

    ```python
    ## MODULE.bazel
    module(
        name = "bar",
        repo_name = "com_foo_bar",
    )
    ```

### Fetch external dependencies as Bazel modules {:#fetch-bazel-modules}

If your dependency is a Bazel project, you should be able to depend on it as a
Bazel module when it also adopts Bzlmod.

*   **WORKSPACE**

    With WORKSPACE, it's common to use the
    [`http_archive`](/rules/lib/repo/http#http_archive) or
    [`git_repository`](/rules/lib/repo/git#git_repository) repository rules to
    download the sources of the Bazel project.

    ```python
    ## WORKSPACE
    load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

    http_archive(
        name = "bazel_skylib",
        urls = ["https://github.com/bazelbuild/bazel-skylib/releases/download/1.4.2/bazel-skylib-1.4.2.tar.gz"],
        sha256 = "66ffd9315665bfaafc96b52278f57c7e2dd09f5ede279ea6d39b2be471e7e3aa",
    )
    load("@bazel_skylib//:workspace.bzl", "bazel_skylib_workspace")
    bazel_skylib_workspace()

    http_archive(
        name = "rules_java",
        urls = ["https://github.com/bazelbuild/rules_java/releases/download/6.1.1/rules_java-6.1.1.tar.gz"],
        sha256 = "76402a50ae6859d50bd7aed8c1b8ef09dae5c1035bb3ca7d276f7f3ce659818a",
    )
    load("@rules_java//java:repositories.bzl", "rules_java_dependencies", "rules_java_toolchains")
    rules_java_dependencies()
    rules_java_toolchains()
    ```

    As you can see, it's a common pattern that users need to load transitive
    dependencies from a macro of the dependency. Assume both `bazel_skylib` and
    `rules_java` depends on `platform`, the exact version of the `platform`
    dependency is determined by the order of the macros.

*   **Bzlmod**

    With Bzlmod, as long as your dependency is available in [Bazel Central
    Registry](https://registry.bazel.build) or your custom [Bazel
    registry](/external/registry), you can simply depend on it with a
    [`bazel_dep`](/rules/lib/globals/module#bazel_dep) directive.

    ```python
    ## MODULE.bazel
    bazel_dep(name = "bazel_skylib", version = "1.4.2")
    bazel_dep(name = "rules_java", version = "6.1.1")
    ```

    Bzlmod resolves Bazel module dependencies transitively using the
    [MVS](https://research.swtch.com/vgo-mvs) algorithm. Therefore, the maximal
    required version of `platform` is selected automatically.

### Override a dependency as a Bazel module{:#override-modules}

As the root module, you can override Bazel module dependencies in different
ways.

Please read the [overrides](/external/module#overrides) section for more
information.

You can find some example usages in the
[examples][override-examples]
repository.

[override-examples]: https://github.com/bazelbuild/examples/blob/main/bzlmod/02-override_bazel_module

### Fetch external dependencies with module extensions{:#fetch-deps-module-extensions}

If your dependency is not a Bazel project or not yet available in any Bazel
registry, you can introduce it using
[`use_repo_rule`](/external/module#use_repo_rule) or [module
extensions](/external/extension).

*   **WORKSPACE**

    Download a file using the [`http_file`](/rules/lib/repo/http#http_file)
    repository rule.

    ```python
    ## WORKSPACE
    load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

    http_file(
        name = "data_file",
        url = "http://example.com/file",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    )
    ```

*   **Bzlmod**

    With Bzlmod, you can use the `use_repo_rule` directive in your MODULE.bazel
    file to directly instantiate repos:

    ```python
    ## MODULE.bazel
    http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
    http_file(
        name = "data_file",
        url = "http://example.com/file",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    )
    ```

    Under the hood, this is implemented using a module extension. If you need to
    perform more complex logic than simply invoking a repo rule, you could also
    implement a module extension yourself. You'll need to move the definition
    into a `.bzl` file, which also lets you share the definition between
    WORKSPACE and Bzlmod during the migration period.

    ```python
    ## repositories.bzl
    load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
    def my_data_dependency():
        http_file(
            name = "data_file",
            url = "http://example.com/file",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
    ```

    Implement a module extension to load the dependencies macro. You can define
    it in the same `.bzl` file of the macro, but to keep compatibility with
    older Bazel versions, it's better to define it in a separate `.bzl` file.

    ```python
    ## extensions.bzl
    load("//:repositories.bzl", "my_data_dependency")
    def _non_module_dependencies_impl(_ctx):
        my_data_dependency()

    non_module_dependencies = module_extension(
        implementation = _non_module_dependencies_impl,
    )
    ```

    To make the repository visible to the root project, you should declare the
    usages of the module extension and the repository in the MODULE.bazel file.

    ```python
    ## MODULE.bazel
    non_module_dependencies = use_extension("//:extensions.bzl", "non_module_dependencies")
    use_repo(non_module_dependencies, "data_file")
    ```

### Resolve conflict external dependencies with module extension {:#conflict-deps-module-extension}

A project can provide a macro that introduces external repositories based on
inputs from its callers. But what if there are multiple callers in the
dependency graph and they cause a conflict?

Assume the project `foo` provides the following macro which takes `version` as
an argument.

```python
## repositories.bzl in foo {:#repositories.bzl-foo}
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
def data_deps(version = "1.0"):
    http_file(
        name = "data_file",
        url = "http://example.com/file-%s" % version,
        # Omitting the "sha256" attribute for simplicity
    )
```

*   **WORKSPACE**

    With WORKSPACE, you can load the macro from `@foo` and specify the version
    of the data dependency you need. Assume you have another dependency `@bar`,
    which also depends on `@foo` but requires a different version of the data
    dependency.

    ```python
    ## WORKSPACE

    # Introduce @foo and @bar.
    ...

    load("@foo//:repositories.bzl", "data_deps")
    data_deps(version = "2.0")

    load("@bar//:repositories.bzl", "bar_deps")
    bar_deps() # -> which calls data_deps(version = "3.0")
    ```

    In this case, the end user must carefully adjust the order of macros in the
    WORKSPACE to get the version they need. This is one of the biggest pain
    points with WORKSPACE since it doesn't really provide a sensible way to
    resolve dependencies.

*   **Bzlmod**

    With Bzlmod, the author of project `foo` can use module extension to resolve
    conflicts. For example, let's assume it makes sense to always select the
    maximal required version of the data dependency among all Bazel modules.

    ```python
    ## extensions.bzl in foo
    load("//:repositories.bzl", "data_deps")

    data = tag_class(attrs={"version": attr.string()})

    def _data_deps_extension_impl(module_ctx):
        # Select the maximal required version in the dependency graph.
        version = "1.0"
        for mod in module_ctx.modules:
            for data in mod.tags.data:
                version = max(version, data.version)
        data_deps(version)

    data_deps_extension = module_extension(
        implementation = _data_deps_extension_impl,
        tag_classes = {"data": data},
    )
    ```

    ```python
    ## MODULE.bazel in bar
    bazel_dep(name = "foo", version = "1.0")

    foo_data_deps = use_extension("@foo//:extensions.bzl", "data_deps_extension")
    foo_data_deps.data(version = "3.0")
    use_repo(foo_data_deps, "data_file")
    ```

    ```python
    ## MODULE.bazel in root module
    bazel_dep(name = "foo", version = "1.0")
    bazel_dep(name = "bar", version = "1.0")

    foo_data_deps = use_extension("@foo//:extensions.bzl", "data_deps_extension")
    foo_data_deps.data(version = "2.0")
    use_repo(foo_data_deps, "data_file")
    ```

    In this case, the root module requires data version `2.0`, while its
    dependency `bar` requires `3.0`. The module extension in `foo` can correctly
    resolve this conflict and automatically select version `3.0` for the data
    dependency.

### Integrate third party package manager {:#integrate-package-manager}

Following the last section, since module extension provides a way to collect
information from the dependency graph, perform custom logic to resolve
dependencies and call repository rules to introduce external repositories, this
provides a great way for rules authors to enhance the rulesets that integrate
package managers for specific languages.

Please read the [module extensions](/external/extension) page to learn more
about how to use module extensions.

Here is a list of the rulesets that already adopted Bzlmod to fetch dependencies
from different package managers:

- [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external/blob/master/docs/bzlmod.md)
- [rules_go](https://github.com/bazelbuild/rules_go/blob/master/docs/go/core/bzlmod.md)
- [rules_python](https://github.com/bazelbuild/rules_python/blob/main/BZLMOD_SUPPORT.md)

A minimal example that integrates a pseudo package manager is available at the
[examples][pkg-mgr-example]
repository.

[pkg-mgr-example]: https://github.com/bazelbuild/examples/tree/main/bzlmod/05-integrate_third_party_package_manager

### Detect toolchains on the host machine {:#detect-toolchain}

When Bazel build rules need to detect what toolchains are available on your host
machine, they use repository rules to inspect the host machine and generate
toolchain info as external repositories.

*   **WORKSPACE**

    Given the following repository rule to detect a shell toolchain.

    ```python
    ## local_config_sh.bzl
    def _sh_config_rule_impl(repository_ctx):
        sh_path = get_sh_path_from_env("SH_BIN_PATH")

        if not sh_path:
            sh_path = detect_sh_from_path()

        if not sh_path:
            sh_path = "/shell/binary/not/found"

        repository_ctx.file("BUILD", """
    load("@bazel_tools//tools/sh:sh_toolchain.bzl", "sh_toolchain")
    sh_toolchain(
        name = "local_sh",
        path = "{sh_path}",
        visibility = ["//visibility:public"],
    )
    toolchain(
        name = "local_sh_toolchain",
        toolchain = ":local_sh",
        toolchain_type = "@bazel_tools//tools/sh:toolchain_type",
    )
    """.format(sh_path = sh_path))

    sh_config_rule = repository_rule(
        environ = ["SH_BIN_PATH"],
        local = True,
        implementation = _sh_config_rule_impl,
    )
    ```

    You can load the repository rule in WORKSPACE.

    ```python
    ## WORKSPACE
    load("//:local_config_sh.bzl", "sh_config_rule")
    sh_config_rule(name = "local_config_sh")
    ```

*   **Bzlmod**

    With Bzlmod, you can introduce the same repository using a module extension,
    which is similar to introducing the `@data_file` repository in the last
    section.

    ```
    ## local_config_sh_extension.bzl
    load("//:local_config_sh.bzl", "sh_config_rule")

    sh_config_extension = module_extension(
        implementation = lambda ctx: sh_config_rule(name = "local_config_sh"),
    )
    ```

    Then use the extension in the MODULE.bazel file.

    ```python
    ## MODULE.bazel
    sh_config_ext = use_extension("//:local_config_sh_extension.bzl", "sh_config_extension")
    use_repo(sh_config_ext, "local_config_sh")
    ```

### Register toolchains & execution platforms {:#register-toolchains}

Following the last section, after introducing a repository hosting toolchain
information (e.g. `local_config_sh`), you probably want to register the
toolchain.

*   **WORKSPACE**

    With WORKSPACE, you can register the toolchain in the following ways.

    1.  You can register the toolchain the `.bzl` file and load the macro in the
    WORKSPACE file.

        ```python
        ## local_config_sh.bzl
        def sh_configure():
            sh_config_rule(name = "local_config_sh")
            native.register_toolchains("@local_config_sh//:local_sh_toolchain")
        ```

        ```python
        ## WORKSPACE
        load("//:local_config_sh.bzl", "sh_configure")
        sh_configure()
        ```

    2.  Or register the toolchain in the WORKSPACE file directly.

        ```python
        ## WORKSPACE
        load("//:local_config_sh.bzl", "sh_config_rule")
        sh_config_rule(name = "local_config_sh")
        register_toolchains("@local_config_sh//:local_sh_toolchain")
        ```

*   **Bzlmod**

    With Bzlmod, the
    [`register_toolchains`](/rules/lib/globals/module#register_toolchains) and
    [`register_execution_platforms`][register_execution_platforms]
    APIs are only available in the MODULE.bazel file. You cannot call
    `native.register_toolchains` in a module extension.

    ```python
    ## MODULE.bazel
    sh_config_ext = use_extension("//:local_config_sh_extension.bzl", "sh_config_extension")
    use_repo(sh_config_ext, "local_config_sh")
    register_toolchains("@local_config_sh//:local_sh_toolchain")
    ```

The toolchains and execution platforms registered in `WORKSPACE`,
`WORKSPACE.bzlmod` and each Bazel module's `MODULE.bazel` file follow this
order of precedence during toolchain selection (from highest to lowest):

1. toolchains and execution platforms registered in the root module's
   `MODULE.bazel` file.
2. toolchains and execution platforms registered in the `WORKSPACE` or
   `WORKSPACE.bzlmod` file.
3. toolchains and execution platforms registered by modules that are
   (transitive) dependencies of the root module.
4. when not using `WORKSPACE.bzlmod`: toolchains registered in the `WORKSPACE`
   [suffix](/external/migration#builtin-default-deps).

[register_execution_platforms]: /rules/lib/globals/module#register_execution_platforms

### Introduce local repositories {:#introduce-local-deps}

You may need to introduce a dependency as a local repository when you need a
local version of the dependency for debugging or you want to incorporate a
directory in your workspace as external repository.

*   **WORKSPACE**

    With WORKSPACE, this is achieved by two native repository rules,
    [`local_repository`](/reference/be/workspace#local_repository) and
    [`new_local_repository`](/reference/be/workspace#new_local_repository).

    ```python
    ## WORKSPACE
    local_repository(
        name = "rules_java",
        path = "/Users/bazel_user/workspace/rules_java",
    )
    ```

*   **Bzlmod**

    With Bzlmod, you can use
    [`local_path_override`](/rules/lib/globals/module#local_path_override) to
    override a module with a local path.

    ```python
    ## MODULE.bazel
    bazel_dep(name = "rules_java")
    local_path_override(
        module_name = "rules_java",
        path = "/Users/bazel_user/workspace/rules_java",
    )
    ```

    Note: With `local_path_override`, you can only introduce a local directory
    as a Bazel module, which means it should have a MODULE.bazel file and its
    transitive dependencies are taken into consideration during dependency
    resolution. In addition, all module override directives can only be used by
    the root module.

    It is also possible to introduce a local repository with module extension.
    However, you cannot call `native.local_repository` in module extension,
    there is ongoing effort on starlarkifying all native repository rules (check
    [#18285](https://github.com/bazelbuild/bazel/issues/18285) for progress).
    Then you can call the corresponding starlark `local_repository` in a module
    extension. It's also trivial to implement a custom version of
    `local_repository` repository rule if this is a blocking issue for you.

### Bind targets {:#bind-targets}

The [`bind`](/reference/be/workspace#bind) rule in WORKSPACE is deprecated and
not supported in Bzlmod. It was introduced to give a target an alias in the
special `//external` package. All users depending on this should migrate away.

For example, if you have

```python
## WORKSPACE
bind(
    name = "openssl",
    actual = "@my-ssl//src:openssl-lib",
)
```

This allows other targets to depend on `//external:openssl`. You can migrate
away from this by:

*   Replace all usages of `//external:openssl` with
    `@my-ssl//src:openssl-lib`.

*   Or use the [`alias`](/reference/be/general#alias) build rule
    *   Define the following target in a package (e.g. `//third_party`)

        ```python
        ## third_party/BUILD
        alias(
            name = "openssl",
            actual = "@my-ssl//src:openssl-lib",
        )
        ```

    *   Replace all usages of `//external:openssl` with `//third_party:openssl`.

### Fetch versus Sync {:#fetch-sync}

Fetch and sync commands are used to download external repos locally and keep
them updated. Sometimes also to allow building offline using the `--nofetch`
flag after fetching all repos needed for a build.

*   **WORKSPACE**

    Sync performs a force fetch for all repositories, or for a specific
    configured set of repos, while fetch is _only_ used to fetch for a specific
    target.

*   **Bzlmod**

    The sync command is no longer applicable, but fetch offers
    [various options](/reference/command-line-reference#fetch-options).
    You can fetch a target, a repository, a set of configured repos or all
    repositories involved in your dependency resolution and module extensions.
    The fetch result is cached and to force a fetch you must include the
    `--force` option during the fetch process.

## Migration {:#migration}

This section provides useful information and guidance for your Bzlmod migration
process.

### Know your dependencies in WORKSPACE {:#know-deps-in-workspace}

The first step of migration is to understand what dependencies you have. It
could be hard to figure out what exact dependencies are introduced in the
WORKSPACE file because transitive dependencies are often loaded with `*_deps`
macros.

#### Inspect external dependency with workspace resolved file

Fortunately, the flag
[`--experimental_repository_resolved_file`][resolved_file_flag]
can help. This flag essentially generates a "lock file" of all fetched external
dependencies in your last Bazel command. You can find more details in this [blog
post](https://blog.bazel.build/2018/07/09/bazel-sync-and-resolved-file.html).

[resolved_file_flag]: /reference/command-line-reference#flag--experimental_repository_resolved_file

It can be used in two ways:

1.  To fetch info of external dependencies needed for building certain targets.

    ```shell
    bazel clean --expunge
    bazel build --nobuild --experimental_repository_resolved_file=resolved.bzl //foo:bar
    ```

2.  To fetch info of all external dependencies defined in the WORKSPACE file.

    ```shell
    bazel clean --expunge
    bazel sync --experimental_repository_resolved_file=resolved.bzl
    ```

    With the `bazel sync` command, you can fetch all dependencies defined in the
    WORKSPACE file, which include:

    *   `bind` usages
    *   `register_toolchains` & `register_execution_platforms` usages

    However, if your project is cross platforms, bazel sync may break on certain
    platforms because some repository rules may only run correctly on supported
    platforms.

After running the command, you should have information of your external
dependencies in the `resolved.bzl` file.

#### Inspect external dependency with `bazel query`

You may also know `bazel query` can be used for inspecting repository rules with

```shell
bazel query --output=build //external:<repo name>
```

While it is more convenient and much faster, [bazel query can lie about
external dependency version](https://github.com/bazelbuild/bazel/issues/12947),
so be careful using it! Querying and inspecting external
dependencies with Bzlmod is going to achieved by a [new
subcommand](https://github.com/bazelbuild/bazel/issues/15365).

#### Built-in default dependencies {:#builtin-default-deps}

If you check the file generated by `--experimental_repository_resolved_file`,
you are going to find many dependencies that are not defined in your WORKSPACE.
This is because Bazel in fact adds prefixes and suffixes to the user's WORKSPACE
file content to inject some default dependencies, which are usually required by
native rules (e.g. `@bazel_tools`, `@platforms` and `@remote_java_tools`). With
Bzlmod, those dependencies are introduced with a built-in module
[`bazel_tools`][bazel_tools] , which is a default dependency for every other
Bazel module.

[bazel_tools]: https://github.com/bazelbuild/bazel/blob/master/src/MODULE.tools

### Hybrid mode for gradual migration {:#hybrid-mode}

Bzlmod and WORKSPACE can work side by side, which allows migrating dependencies
from the WORKSPACE file to Bzlmod to be a gradual process.

Note: In practice, loading "*_deps" macros in WORKSPACE often causes confusions
with Bzlmod dependencies, therefore we recommend starting with a
WORKSPACE.bzlmod file and avoid loading transitive dependencies with macros.

#### WORKSPACE.bzlmod {:#workspace.bzlmod}

During the migration, Bazel users may need to switch between builds with and
without Bzlmod enabled. WORKSPACE.bzlmod support is implemented to make the
process smoother.

WORKSPACE.bzlmod has the exact same syntax as WORKSPACE. When Bzlmod is enabled,
if a WORKSPACE.bzlmod file also exists at the workspace root:

*   `WORKSPACE.bzlmod` takes effect and the content of `WORKSPACE` is ignored.
*   No [prefixes or suffixes](/external/migration#builtin-default-deps) are
    added to the WORKSPACE.bzlmod file.

Using the WORKSPACE.bzlmod file can make the migration easier because:

*   When Bzlmod is disabled, you fall back to fetching dependencies from the
    original WORKSPACE file.
*   When Bzlmod is enabled, you can better track what dependencies are left to
    migrate with WORKSPACE.bzlmod.

#### Repository visibility {:#repository-visibility}

Bzlmod is able to control which other repositories are visible from a given
repository, check [repository names and strict
deps](/external/module#repository_names_and_strict_deps) for more details.

Here is a summary of repository visibilities from different types of
repositories when also taking WORKSPACE into consideration.

| | From the main repo | From Bazel module repos | From module extension repos | From WORKSPACE repos |
|----------------|--------------------|-------------------------|---------------------------------------------------------------------------------------------------------------------|----------------------|
| The main repo  | Visible | If the root module is a direct dependency | If the root module is a direct dependency of the module hosting the module extension | Visible              |
| Bazel module repos | Direct deps | Direct deps | Direct deps of the module hosting the module extension | Direct deps of the root module |
| Module extension repos | Direct deps | Direct deps | Direct deps of the module hosting the module extension + all repos generated by the same module extension | Direct deps of the root module |
| WORKSPACE Repos | All visible | Not visible | Not visible | All visible |

Note: For the root module, if a repository `@foo` is defined in WORKSPACE and
`@foo` is also used as an [apparent repository
name](/external/overview#apparent-repo-name) in MODULE.bazel, then `@foo`
refers to the one introduced in MODULE.bazel.

Note: For a module extension generated repository `@bar`, if `@foo` is used as
an [apparent repository name](/external/overview#apparent-repo-name) of
another repository generated by the same module extension and direct
dependencies of the module hosting the module extension, then for repository
`@bar`, `@foo` refers to the latter.

### Migration process {:#migration-process}

A typical Bzlmod migration process can look like this:

1.  Understand what dependencies you have in WORKSPACE.
1.  Add an empty MODULE.bazel file at your project root.
1.  Add an empty WORKSPACE.bzlmod file to override the WORKSPACE file content.
1.  Build your targets with Bzlmod enabled and check which repository is
    missing.
1.  Check the definition of the missing repository in the resolved dependency
    file.
1.  Introduce the missing dependency as a Bazel module, through a module
    extension, or leave it in the WORKSPACE.bzlmod for later migration.
1.  Go back to 4 and repeat until all dependencies are available.

#### Migration tool {:#migration-tool}

There is an interactive Bzlmod migration [helper script][migration_script] that
can get you started.

[migration_script]: https://github.com/bazelbuild/bazel-central-registry/blob/main/tools/migrate_to_bzlmod.py

The script does the following things:

*   Generate and parse the WORKSPACE resolved file.
*   Print repository info from the resolved file in a human readable way.
*   Run bazel build command, detect recognized error messages, and recommend a
    way to migrate.
*   Check if a dependency is already available in the BCR.
*   Add a dependency to MODULE.bazel file.
*   Add a dependency through a module extension.
*   Add a dependency to WORKSPACE.bzlmod file.

To use it, make sure you have the latest Bazel release installed, and run the
following command:

```shell
git clone https://github.com/bazelbuild/bazel-central-registry.git
cd <your workspace root>
<BCR repo root>/tools/migrate_to_bzlmod.py -t <your build targets>
```

Note: The migration script is not perfect and may not be up-to-date since Bzlmod
is evolving, always double check if the recommended solution is correct.

## Publish Bazel modules {:#publish-modules}

If your Bazel project is a dependency for other projects, you can publish your
project in the [Bazel Central Registry](https://registry.bazel.build/).

To be able to check in your project in the BCR, you need a source archive URL of
the project. Take note of a few things when creating the source archive:

*   **Make sure the archive is pointing to a specific version.**

    The BCR can only accept versioned source archives because Bzlmod needs to
    conduct version comparison during dependency resolution.

*   **Make sure the archive URL is stable.**

    Bazel verifies the content of the archive by a hash value, so you should
    make sure the checksum of the downloaded file never changes. If the URL is
    from GitHub, please create and upload a release archive in the release page.
    GitHub isn't going to guarantee the checksum of source archives generated on
    demand. In short, URLs in the form of
    `https://github.com/<org>/<repo>/releases/download/...` is considered stable
    while `https://github.com/<org>/<repo>/archive/...` is not. Check [GitHub
    Archive Checksum
    Outage](https://blog.bazel.build/2023/02/15/github-archive-checksum.html)
    for more context.

*   **Make sure the source tree follows the layout of the original repository.**

    In case your repository is very large and you want to create a distribution
    archive with reduced size by stripping out unnecessary sources, please make
    sure the stripped source tree is a subset of the original source tree. This
    makes it easier for end users to override the module to a non-release
    version by [`archive_override`](/rules/lib/globals/module#archive_override)
    and [`git_override`](/rules/lib/globals/module#git_override).

*   **Include a test module in a subdirectory that tests your most common
    APIs.**

    A test module is a Bazel project with its own WORKSPACE and MODULE.bazel
    file located in a subdirectory of the source archive which depends on the
    actual module to be published. It should contain examples or some
    integration tests that cover your most common APIs. Check
    [test module][test_module] to learn how to set it up.

[test_module]: https://github.com/bazelbuild/bazel-central-registry/tree/main/docs#test-module

When you have your source archive URL ready, follow the [BCR contribution
guidelines][bcr_contrib_guide] to submit your module to the BCR with a GitHub
Pull Request.

[bcr_contrib_guide]: https://github.com/bazelbuild/bazel-central-registry/tree/main/docs#contribute-a-bazel-module

It is **highly recommended** to set up the [Publish to
BCR](https://github.com/bazel-contrib/publish-to-bcr) GitHub App for your
repository to automate the process of submitting your module to the BCR.

## Best practices {:#best-practices}

This section documents a few best practices you should follow for better
managing your external dependencies.

#### Split targets into different packages to avoid fetching unnecessary dependencies.

Check [#12835](https://github.com/bazelbuild/bazel/issues/12835), where dev
dependencies for tests are forced to be fetched unnecessarily for building
targets that don't need them. This is actually not Bzlmod specific, but
following this practices makes it easier to specify dev dependencies correctly.

#### Specify dev dependencies

You can set the `dev_dependency` attribute to true for
[`bazel_dep`](/rules/lib/globals/module#bazel_dep) and
[`use_extension`](/rules/lib/globals/module#use_extension) directives so that
they don't propagate to dependent projects. As the root module, you can use the
[`--ignore_dev_dependency`][ignore_dev_dep_flag] flag to verify if your targets
still build without dev dependencies and overrides.

[ignore_dev_dep_flag]: /reference/command-line-reference#flag--ignore_dev_dependency

{# More best practices here !!! #}

## Community migration progress {:#migration-progress}

You can check the [Bazel Central Registry](https://registry.bazel.build) to find
out if your dependencies are already available. Otherwise feel free to join this
[GitHub discussion](https://github.com/bazelbuild/bazel/discussions/18329) to
upvote or post the dependencies that are blocking your migration.

## Report issues {:#reporting-issues}

Please check the [Bazel GitHub issue list][bzlmod_github_issue] for known Bzlmod
issues. Feel free to file new issues or feature requests that can help unblock
your migration!

[bzlmod_github_issue]: https://github.com/bazelbuild/bazel/issues?q=is%3Aopen+is%3Aissue+label%3Aarea-Bzlmod
