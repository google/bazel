Project: /_project.yaml
Book: /_book.yaml
keywords: product:Bazel,lockfile,Bzlmod

# Bazel Lockfile

{% include "_buttons.html" %}

The lockfile feature in Bazel enables the recording of specific versions or
dependencies of software libraries or packages required by a project. It
achieves this by storing the result of module resolution and extension
evaluation. The lockfile promotes reproducible builds, ensuring consistent
development environments. Additionally, it enhances build efficiency by allowing
Bazel to skip the parts of the resolution process that are unaffected by changes
in project dependencies. Furthermore, the lockfile improves stability by
preventing unexpected updates or breaking changes in external libraries, thereby
reducing the risk of introducing bugs.

## Lockfile Generation {:#lockfile-generation}

The lockfile is generated under the workspace root with the name
`MODULE.bazel.lock`. It is created or updated during the build process,
specifically after module resolution and extension evaluation. Importantly, it
only includes dependencies that are included in the current invocation of the
build.

When changes occur in the project that affect its dependencies, the lockfile is
automatically updated to reflect the new state. This ensures that the lockfile
remains focused on the specific set of dependencies required for the current
build, providing an accurate representation of the project's resolved
dependencies.

## Lockfile Usage {:#lockfile-usage}

The lockfile can be controlled by the flag
[`--lockfile_mode`](/reference/command-line-reference#flag--lockfile_mode) to
customize the behavior of Bazel when the project state differs from the
lockfile. The available modes are:

*   `update` (Default): Use the information that is present in the lockfile to
    skip downloads of known registry files and to avoid re-evaluating extensions
    whose results are still up-to-date. If information is missing, it will
    be added to the lockfile. In this mode, Bazel also avoids refreshing
    mutable information, such as yanked versions, for dependencies that haven't
    changed.
*   `refresh`: Like `update`, but mutable information is always refreshed when
    switching to this mode and roughly every hour while in this mode.
*   `error`: Like `update`, but if any information is missing or out-of-date,
    Bazel will fail with an error. This mode never changes the lockfile or
    performs network requests during resolution. Module extensions that marked
    themselves as `reproducible` may still perform network requests, but are
    expected to always produce the same result.
*   `off`: The lockfile is neither checked nor updated.

## Lockfile Benefits {:#lockfile-benefits}

The lockfile offers several benefits and can be utilized in various ways:

-   **Reproducible builds.** By capturing the specific versions or dependencies
    of software libraries, the lockfile ensures that builds are reproducible
    across different environments and over time. Developers can rely on
    consistent and predictable results when building their projects.

-   **Fast incremental resolutions.** The lockfile enables Bazel to avoid
    downloading registry files that were already used in a previous build.
    This significantly improves build efficiency, especially in scenarios where
    resolution can be time-consuming.

-   **Stability and risk reduction.** The lockfile helps maintain stability by
    preventing unexpected updates or breaking changes in external libraries. By
    locking the dependencies to specific versions, the risk of introducing bugs
    due to incompatible or untested updates is reduced.
-

### Persistent lockfile {:#persistent-lockfile}

Bazel also maintains another lockfile at
`"$(bazel info output_base)"/MODULE.bazel.lock`. The format and contents of this
lockfile are explicitly unspecified. It is only used as a performance
optimization. While it can be deleted together with the output base, any need to
do so is a bug in either Bazel itself or a module extension.

## Lockfile Contents {:#lockfile-contents}

The lockfile contains all the necessary information to determine whether the
project state has changed. It also includes the result of building the project
in the current state. The lockfile consists of two main parts:

1.   Hashes of all remote files that are inputs to module resolution.
2.   For each module extension, the lockfile includes inputs that affect it,
     represented by `bzlTransitiveDigest`, `usagesDigest` and other fields, as
     well as the output of running that extension, referred to as
     `generatedRepoSpecs`

Here is an example that demonstrates the structure of the lockfile, along with
explanations for each section:

```json
{
  "lockFileVersion": 10,
  "registryFileHashes": {
    "https://bcr.bazel.build/bazel_registry.json": "8a28e4af...5d5b3497",
    "https://bcr.bazel.build/modules/foo/1.0/MODULE.bazel": "7cd0312e...5c96ace2",
    "https://bcr.bazel.build/modules/foo/2.0/MODULE.bazel": "70390338... 9fc57589",
    "https://bcr.bazel.build/modules/foo/2.0/source.json": "7e3a9adf...170d94ad",
    "https://registry.mycorp.com/modules/foo/1.0/MODULE.bazel": "not found",
    ...
  },
  "selectedYankedVersions": {
    "foo@2.0": "Yanked for demo purposes"
  },
  "moduleExtensions": {
    "//:extension.bzl%lockfile_ext": {
      "general": {
        "bzlTransitiveDigest": "oWDzxG/aLnyY6Ubrfy....+Jp6maQvEPxn0pBM=",
        "usagesDigest": "aLmqbvowmHkkBPve05yyDNGN7oh7QE9kBADr3QIZTZs=",
        ...,
        "generatedRepoSpecs": {
          "hello": {
            "bzlFile": "@@//:extension.bzl",
            ...
          }
        }
      }
    },
    "//:extension.bzl%lockfile_ext2": {
      "os:macos": {
        "bzlTransitiveDigest": "oWDzxG/aLnyY6Ubrfy....+Jp6maQvEPxn0pBM=",
        "usagesDigest": "aLmqbvowmHkkBPve05y....yDNGN7oh7r3QIZTZs=",
        ...,
        "generatedRepoSpecs": {
          "hello": {
            "bzlFile": "@@//:extension.bzl",
            ...
          }
        }
      },
      "os:linux": {
        "bzlTransitiveDigest": "eWDzxG/aLsyY3Ubrto....+Jp4maQvEPxn0pLK=",
        "usagesDigest": "aLmqbvowmHkkBPve05y....yDNGN7oh7r3QIZTZs=",
        ...,
        "generatedRepoSpecs": {
          "hello": {
            "bzlFile": "@@//:extension.bzl",
            ...
          }
        }
      }
    }
  }
}
```

### Registry File Hashes {:#registry-file-hashes}

The `registryFileHashes` section contains the hashes of all files from
remote registries accessed during module resolution. Since the resolution
algorithm is fully deterministic when given the same inputs and all remote
inputs are hashed, this ensures a fully reproducible resolution result while
avoiding excessive duplication of remote information in the lockfile. Note that
this also requires recording when a particular registry didn't contain a certain
module, but a registry with lower precedence did (see the "not found" entry in
the example). This inherently mutable information can be updated via
`bazel mod deps --lockfile_mode=refresh`.

Bazel uses the hashes from the lockfile to look up registry files in the
repository cache before downloading them, which speeds up subsequent
resolutions.

### Selected Yanked Versions {:#selected-yanked-versions}

The `selectedYankedVersions` section contains the yanked versions of modules
that were selected by module resolution. Since this usually result in an error
when trying to build, this section is only non-empty when yanked versions are
explicitly allowed via `--allow_yanked_versions` or
`BZLMOD_ALLOW_YANKED_VERSIONS`.

This field is needed since, compared to module files, yanked version information
is inherently mutable and thus can't be referenced by a hash. This information
can be updated via `bazel mod deps --lockfile_mode=refresh`.

### Module Extensions {:#module-extensions}

The `moduleExtensions` section is a map that includes only the extensions used
in the current invocation or previously invoked, while excluding any extensions
that are no longer utilized. In other words, if an extension is not being used
anymore across the dependency graph, it is removed from the `moduleExtensions`
map.

If an extension is independent of the operating system or architecture type,
this section features only a single "general" entry. Otherwise, multiple
entries are included, named after the OS, architecture, or both, with each
corresponding to the result of evaluating the extension on those specifics.

Each entry in the extension map corresponds to a used extension and is
identified by its containing file and name. The corresponding value for each
entry contains the relevant information associated with that extension:

1. The `bzlTransitiveDigest` is the digest of the extension implementation
   and the .bzl files transitively loaded by it.
2. The `usagesDigest` is the digest of the _usages_ of the extension in the
   dependency graph, which includes all tags.
3. Further unspecified fields that track other inputs to the extension,
   such as contents of files or directories it reads or environment
   variables it uses.
4. The `generatedRepoSpecs` encode the repositories created by the
   extension with the current input.
5. The optional `moduleExtensionMetadata` field contains metadata provided by
   the extension such as whether certain repositories it created should be
   imported via `use_repo` by the root module. This information powers the
   `bazel mod tidy` command.

Module extensions can opt out of being included in the lockfile by setting the
returning metadata with `reproducible = True`. By doing so, they promise that
they will always create the same repositories when given the same inputs.

## Best Practices {:#best-practices}

To maximize the benefits of the lockfile feature, consider the following best
practices:

*   Regularly update the lockfile to reflect changes in project dependencies or
    configuration. This ensures that subsequent builds are based on the most
    up-to-date and accurate set of dependencies. To lock down all extensions
    at once, run `bazel mod deps --lockfile_mode=update`.

*   Include the lockfile in version control to facilitate collaboration and
    ensure that all team members have access to the same lockfile, promoting
    consistent development environments across the project.

*   Use [`bazelisk`](/install/bazelisk) to run Bazel, and include a
    `.bazelversion` file in version control that specifies the Bazel version
    corresponding to the lockfile. Because Bazel itself is a dependency of
    your build, the lockfile is specific to the Bazel version, and will
    change even between [backwards compatible](/release/backward-compatibility)
    Bazel releases. Using `bazelisk` ensures that all developers are using
    a Bazel version that matches the lockfile.

By following these best practices, you can effectively utilize the lockfile
feature in Bazel, leading to more efficient, reliable, and collaborative
software development workflows.

## Merge Conflicts {:#merge-conflicts}

The lockfile format is designed to minimize merge conflicts, but they can still
happen.

### Automatic Resolution {:#automatic-resolution}

Bazel provides a custom
[git merge driver](https://git-scm.com/docs/gitattributes#_defining_a_custom_merge_driver)
to help resolve these conflicts automatically.

Set up the driver by adding this line to a `.gitattributes` file in the root of
your git repository:

```gitattributes
# A custom merge driver for the Bazel lockfile.
# https://bazel.build/external/lockfile#automatic-resolution
MODULE.bazel.lock merge=bazel-lockfile-merge
```

Then each developer who wants to use the driver has to register it once by
following these steps:

1. Install [jq](https://jqlang.github.io/jq/download/) (1.5 or higher).
2. Run the following commands:

```bash
jq_script=$(curl https://raw.githubusercontent.com/bazelbuild/bazel/master/scripts/bazel-lockfile-merge.jq)
printf '%s\n' "${jq_script}" | less # to optionally inspect the jq script
git config --global merge.bazel-lockfile-merge.name   "Merge driver for the Bazel lockfile (MODULE.bazel.lock)"
git config --global merge.bazel-lockfile-merge.driver "jq -s '${jq_script}' -- %O %A %B > %A.jq_tmp && mv %A.jq_tmp %A"
```

### Manual Resolution {:#manual-resolution}

Simple merge conflicts in the `registryFileHashes` and `selectedYankedVersions`
fields can be safely resolved by keeping all the entries from both sides of the
conflict.

Other types of merge conflicts should not be resolved manually. Instead:

1. Restore the previous state of the lockfile
   via `git reset MODULE.bazel.lock && git checkout MODULE.bazel.lock`.
2. Resolve any conflicts in the `MODULE.bazel` file.
3. Run `bazel mod deps` to update the lockfile.
