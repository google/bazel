Project: /_project.yaml
Book: /_book.yaml

# External dependencies overview

{% include "_buttons.html" %}

Bazel supports *external dependencies*, source files (both text and binary) used
in your build that are not from your workspace. For example, they could be a
ruleset hosted in a GitHub repo, a Maven artifact, or a directory on your local
machine outside your current workspace.

As of Bazel 6.0, there are two ways to manage external dependencies with Bazel:
the traditional, repository-focused [`WORKSPACE` system](#workspace-system), and
the newer module-focused [`MODULE.bazel` system](#bzlmod) (codenamed *Bzlmod*,
and enabled with the flag `--enable_bzlmod`). The two systems can be used
together, but Bzlmod is replacing the `WORKSPACE` system in future Bazel
releases, check the [Bzlmod migration guide](/external/migration) on how to
migrate.

This document explains the concepts surrounding external dependency management
in Bazel, before going into a bit more detail about the two systems in order.

## Concepts {:#concepts}

### Repository {:#repository}

A directory tree with a boundary marker file at its root, containing source
files that can be used in a Bazel build. Often shortened to just **repo**.

A repo boundary marker file can be `MODULE.bazel` (signaling that this repo
represents a Bazel module), `REPO.bazel` (see [below](#repo.bazel)), or in
legacy contexts, `WORKSPACE` or `WORKSPACE.bazel`. Any repo boundary marker file
will signify the boundary of a repo; multiple such files can coexist in a
directory.

### Main repository {:#main-repository}

The repository in which the current Bazel command is being run.

The root of the main repository is also known as the
<span id="#workspace-root">**workspace root**</span>.

### Workspace {:#workspace}

The environment shared by all Bazel commands run in the same main repository. It
encompasses the main repo and the set of all defined external repos.

Note that historically the concepts of "repository" and "workspace" have been
conflated; the term "workspace" has often been used to refer to the main
repository, and sometimes even used as a synonym of "repository".

### Canonical repository name {:#canonical-repo-name}

The canonical name a repository is addressable by. Within the context of a
workspace, each repository has a single canonical name. A target inside a repo
whose canonical name is `canonical_name` can be addressed by the label
`@@canonical_name//package:target` (note the double `@`).

The main repository always has the empty string as the canonical name.

### Apparent repository name {:#apparent-repo-name}

The name a repository is addressable by in the context of a certain other repo.
This can be thought of as a repo's "nickname": The repo with the canonical name
`michael` might have the apparent name `mike` in the context of the repo
`alice`, but might have the apparent name `mickey` in the context of the repo
`bob`. In this case, a target inside `michael` can be addressed by the label
`@mike//package:target` in the context of `alice` (note the single `@`).

Conversely, this can be understood as a **repository mapping**: each repo
maintains a mapping from "apparent repo name" to a "canonical repo name".

### Repository rule {:#repo-rule}

A schema for repository definitions that tells Bazel how to materialize a
repository. For example, it could be "download a zip archive from a certain URL
and extract it", or "fetch a certain Maven artifact and make it available as a
`java_import` target", or simply "symlink a local directory". Every repo is
**defined** by calling a repo rule with an appropriate number of arguments.

See [Repository rules](/extending/repo) for more information about how to write
your own repository rules.

The most common repo rules by far are
[`http_archive`](/rules/lib/repo/http#http_archive), which downloads an archive
from a URL and extracts it, and
[`local_repository`](/reference/be/workspace#local_repository), which symlinks a
local directory that is already a Bazel repository.

### Fetch a repository {:#fetch-repository}

The action of making a repo available on local disk by running its associated
repo rule. The repos defined in a workspace are not available on local disk
before they are fetched.

Normally, Bazel only fetches a repo when it needs something from the repo,
and the repo hasn't already been fetched. If the repo has already been fetched
before, Bazel only re-fetches it if its definition has changed.

The `fetch` command can be used to initiate a pre-fetch for a repository,
target, or all necessary repositories to perform any build. This capability
enables offline builds using the `--nofetch` option.

The `--fetch` option serves to manage network access. Its default value is true.
However, when set to false (`--nofetch`), the command will utilize any cached
version of the dependency, and if none exists, the command will result in
failure.

See [fetch options](/reference/command-line-reference#fetch-options) for more
information about controlling fetch.

### Directory layout {:#directory-layout}

After being fetched, the repo can be found in the subdirectory `external` in the
[output base](/remote/output-directories), under its canonical name.

You can run the following command to see the contents of the repo with the
canonical name `canonical_name`:

```posix-terminal
ls $(bazel info output_base)/external/{{ '<var>' }} canonical_name {{ '</var>' }}
```

### REPO.bazel file {:#repo.bazel}

The [`REPO.bazel`](/rules/lib/globals/repo) file is used to mark the topmost
boundary of the directory tree that constitutes a repo. It doesn't need to
contain anything to serve as a repo boundary file; however, it can also be used
to specify some common attributes for all build targets inside the repo.

The syntax of a `REPO.bazel` file is similar to `BUILD` files, except that no
`load` statements are supported. The `repo()` function takes the same arguments as the [`package()`
function](/reference/be/functions#package) in `BUILD` files; whereas `package()`
specifies common attributes for all build targets inside the package, `repo()`
analogously does so for all build targets inside the repo.

For example, you can specify a common license for all targets in your repo by
having the following `REPO.bazel` file:

```python
repo(
    default_package_metadata = ["//:my_license"],
)
```

## Manage external dependencies with Bzlmod {:#bzlmod}

Bzlmod, the new external dependency subsystem, does not directly work with repo
definitions. Instead, it builds a dependency graph from _modules_, runs
_extensions_ on top of the graph, and defines repos accordingly.

A [Bazel **module**](/external/module) is a Bazel project that can have multiple
versions, each of which publishes metadata about other modules that it depends
on. A module must have a `MODULE.bazel` file at its repo root, next to the
`WORKSPACE` file. This file is the module's manifest, declaring its name,
version, list of dependencies, among other information. The following is a basic
example:

```python
module(name = "my-module", version = "1.0")

bazel_dep(name = "rules_cc", version = "0.0.1")
bazel_dep(name = "protobuf", version = "3.19.0")
```

A module must only list its direct dependencies, which Bzlmod looks up in a
[Bazel registry](/external/registry) — by default, the [Bazel Central
Registry](https://bcr.bazel.build/){:.external}. The registry provides the
dependencies' `MODULE.bazel` files, which allows Bazel to discover the entire
transitive dependency graph before performing version resolution.

After version resolution, in which one version is selected for each module,
Bazel consults the registry again to learn how to define a repo for each module
(in most cases, using `http_archive`).

Modules can also specify customized pieces of data called *tags*, which are
consumed by [**module extensions**](/external/extension) after module resolution
to define additional repos. These extensions have capabilities similar to repo
rules, enabling them to perform actions like file I/O and sending network
requests. Among other things, they allow Bazel to interact with other package
management systems while also respecting the dependency graph built out of Bazel
modules.

### External links on Bzlmod {:#external-links}

*   [Bzlmod usage examples in bazelbuild/examples](https://github.com/bazelbuild/examples/tree/main/bzlmod){:.external}
*   [Bazel External Dependencies Overhaul](https://docs.google.com/document/d/1moQfNcEIttsk6vYanNKIy3ZuK53hQUFq1b1r0rmsYVg/edit){: .external}
    (original Bzlmod design doc)
*   [BazelCon 2021 talk on Bzlmod](https://www.youtube.com/watch?v=TxOCKtU39Fs){: .external}
*   [Bazel Community Day talk on Bzlmod](https://www.youtube.com/watch?v=MB6xxis9gWI){: .external}

## Define repos with `WORKSPACE` {:#workspace-system}

Historically, you can manage external dependencies by defining repos in the
`WORKSPACE` (or `WORKSPACE.bazel`) file. This file has a similar syntax to
`BUILD` files, employing repo rules instead of build rules.

The following snippet is an example to use the `http_archive` repo rule in the
`WORKSPACE` file:

```python
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "foo",
    urls = ["https://example.com/foo.zip"],
    sha256 = "c9526390a7cd420fdcec2988b4f3626fe9c5b51e2959f685e8f4d170d1a9bd96",
)
```

The snippet defines a repo whose canonical name is `foo`. In the `WORKSPACE`
system, by default, the canonical name of a repo is also its apparent name to
all other repos.

See the [full list](/rules/lib/globals/workspace) of functions available in
`WORKSPACE` files.

### Shortcomings of the `WORKSPACE` system {:#workspace-shortcomings}

In the years since the `WORKSPACE` system was introduced, users have reported
many pain points, including:

*   Bazel does not evaluate the `WORKSPACE` files of any dependencies, so all
    transitive dependencies must be defined in the `WORKSPACE` file of the main
    repo, in addition to direct dependencies.
*   To work around this, projects have adopted the "deps.bzl" pattern, in which
    they define a macro which in turn defines multiple repos, and ask users to
    call this macro in their `WORKSPACE` files.
    *   This has its own problems: macros cannot `load` other `.bzl` files, so
        these projects have to define their transitive dependencies in this
        "deps" macro, or work around this issue by having the user call multiple
        layered "deps" macros.
    *   Bazel evaluates the `WORKSPACE` file sequentially. Additionally,
        dependencies are specified using `http_archive` with URLs, without any
        version information. This means that there is no reliable way to perform
        version resolution in the case of diamond dependencies (`A` depends on
        `B` and `C`; `B` and `C` both depend on different versions of `D`).

Due to the shortcomings of WORKSPACE, Bzlmod is going to replace the legacy
WORKSPACE system in future Bazel releases. Please read the [Bzlmod migration
guide](/external/migration) on how to migrate to Bzlmod.
