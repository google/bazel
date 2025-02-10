Project: /_project.yaml
Book: /_book.yaml

# Visibility

{% include "_buttons.html" %}

This page covers Bazel's two visibility systems:
[target visibility](#target-visibility) and [load visibility](#load-visibility).

Both types of visibility help other developers distinguish between your
library's public API and its implementation details, and help enforce structure
as your workspace grows. You can also use visibility when deprecating a public
API to allow current users while denying new ones.

## Target visibility {:#target-visibility}

**Target visibility** controls who may depend on your target — that is, who may
use your target's label inside an attribute such as `deps`. A target will fail
to build during the [analysis](/reference/glossary#analysis-phase) phase if it
violates the visibility of one of its dependencies.

Generally, a target `A` is visible to a target `B` if they are in the same
location, or if `A` grants visibility to `B`'s location. In the absence of
[symbolic macros](/extending/macros), the term "location" can be simplified
to just "package"; see [below](#symbolic-macros) for more on symbolic macros.

Visibility is specified by listing allowed packages. Allowing a package does not
necessarily mean that its subpackages are also allowed. For more details on
packages and subpackages, see [Concepts and terminology](/concepts/build-ref).

For prototyping, you can disable target visibility enforcement by setting the
flag `--check_visibility=false`. This shouldn't be done for production usage in
submitted code.

The primary way to control visibility is with a rule's
[`visibility`](/reference/be/common-definitions#common.visibility) attribute.
The following subsections describe the attribute's format, how to apply it to
various kinds of targets, and the interaction between the visibility system and
symbolic macros.

### Visibility specifications {:#visibility-specifications}

All rule targets have a `visibility` attribute that takes a list of labels. Each
label has one of the following forms. With the exception of the last form, these
are just syntactic placeholders that don't correspond to any actual target.

*   `"//visibility:public"`: Grants access to all packages.

*   `"//visibility:private"`: Does not grant any additional access; only targets
    in this location's package can use this target.

*   `"//foo/bar:__pkg__"`: Grants access to `//foo/bar` (but not its
    subpackages).

*   `"//foo/bar:__subpackages__"`: Grants access `//foo/bar` and all of its
    direct and indirect subpackages.

*   `"//some_pkg:my_package_group"`: Grants access to all of the packages that
    are part of the given [`package_group`](/reference/be/functions#package_group).

    *   Package groups use a
        [different syntax](/reference/be/functions#package_group.packages) for
        specifying packages. Within a package group, the forms
        `"//foo/bar:__pkg__"` and `"//foo/bar:__subpackages__"` are respectively
        replaced by `"//foo/bar"` and `"//foo/bar/..."`. Likewise,
        `"//visibility:public"` and `"//visibility:private"` are just `"public"`
        and `"private"`.

For example, if `//some/package:mytarget` has its `visibility` set to
`[":__subpackages__", "//tests:__pkg__"]`, then it could be used by any target
that is part of the `//some/package/...` source tree, as well as targets
declared in `//tests/BUILD`, but not by targets defined in
`//tests/integration/BUILD`.

**Best practice:** To make several targets visible to the same set
of packages, use a `package_group` instead of repeating the list in each
target's `visibility` attribute. This increases readability and prevents the
lists from getting out of sync.

**Best practice:** When granting visibility to another team's project, prefer
`__subpackages__` over `__pkg__` to avoid needless visibility churn as that
project evolves and adds new subpackages.

Note: The `visibility` attribute may not specify non-`package_group` targets.
Doing so triggers a "Label does not refer to a package group" or "Cycle in
dependency graph" error.

### Rule target visibility {:#rule-target-visibility}

A rule target's visibility is determined by taking its `visibility` attribute
-- or a suitable default if not given -- and appending the location where the
target was declared. For targets not declared in a symbolic macro, if the
package specifies a [`default_visibility`](/reference/be/functions#package.default_visibility),
this default is used; for all other packages and for targets declared in a
symbolic macro, the default is just `["//visibility:private"]`.

```starlark
# //mypkg/BUILD

package(default_visibility = ["//friend:__pkg__"])

cc_library(
    name = "t1",
    ...
    # No visibility explicitly specified.
    # Effective visibility is ["//friend:__pkg__", "//mypkg:__pkg__"].
    # If no default_visibility were given in package(...), the visibility would
    # instead default to ["//visibility:private"], and the effective visibility
    # would be ["//mypkg:__pkg__"].
)

cc_library(
    name = "t2",
    ...
    visibility = [":clients"],
    # Effective visibility is ["//mypkg:clients, "//mypkg:__pkg__"], which will
    # expand to ["//another_friend:__subpackages__", "//mypkg:__pkg__"].
)

cc_library(
    name = "t3",
    ...
    visibility = ["//visibility:private"],
    # Effective visibility is ["//mypkg:__pkg__"]
)

package_group(
    name = "clients",
    packages = ["//another_friend/..."],
)
```

**Best practice:** Avoid setting `default_visibility` to public. It may be
convenient for prototyping or in small codebases, but the risk of inadvertently
creating public targets increases as the codebase grows. It's better to be
explicit about which targets are part of a package's public interface.

### Generated file target visibility {:#generated-file-target-visibility}

A generated file target has the same visibility as the rule target that
generates it.

```starlark
# //mypkg/BUILD

java_binary(
    name = "foo",
    ...
    visibility = ["//friend:__pkg__"],
)
```

```starlark
# //friend/BUILD

some_rule(
    name = "bar",
    deps = [
        # Allowed directly by visibility of foo.
        "//mypkg:foo",
        # Also allowed. The java_binary's "_deploy.jar" implicit output file
        # target the same visibility as the rule target itself.
        "//mypkg:foo_deploy.jar",
    ]
    ...
)
```

### Source file target visibility {:#source-file-target-visibility}

Source file targets can either be explicitly declared using
[`exports_files`](/reference/be/functions#exports_files), or implicitly created
by referring to their filename in a label attribute of a rule (outside of a
symbolic macro). As with rule targets, the location of the call to
`exports_files`, or the BUILD file that referred to the input file, is always
automatically appended to the file's visibility.

Files declared by `exports_files` can have their visibility set by the
`visibility` parameter to that function. If this parameter is not given, the visibility is public.

Note: `exports_files` may not be used to override the visibility of a generated
file.

For files that do not appear in a call to `exports_files`, the visibility
depends on the value of the flag
[`--incompatible_no_implicit_file_export`](https://github.com/bazelbuild/bazel/issues/10225){: .external}:

*   If the flag is true, the visibility is private.

*   Else, the legacy behavior applies: The visibility is the same as the
    `BUILD` file's `default_visibility`, or private if a default visibility is
    not specified.

Avoid relying on the legacy behavior. Always write an `exports_files`
declaration whenever a source file target needs non-private visibility.

**Best practice:** When possible, prefer to expose a rule target rather than a
source file. For example, instead of calling `exports_files` on a `.java` file,
wrap the file in a non-private `java_library` target. Generally, rule targets
should only directly reference source files that live in the same package.

#### Example {:#source-file-visibility-example}

File `//frobber/data/BUILD`:

```starlark
exports_files(["readme.txt"])
```

File `//frobber/bin/BUILD`:

```starlark
cc_binary(
  name = "my-program",
  data = ["//frobber/data:readme.txt"],
)
```

### Config setting visibility {:#config-setting-visibility}

Historically, Bazel has not enforced visibility for
[`config_setting`](/reference/be/general#config_setting) targets that are
referenced in the keys of a [`select()`](/reference/be/functions#select). There
are two flags to remove this legacy behavior:

*   [`--incompatible_enforce_config_setting_visibility`](https://github.com/bazelbuild/bazel/issues/12932){: .external}
    enables visibility checking for these targets. To assist with migration, it
    also causes any `config_setting` that does not specify a `visibility` to be
    considered public (regardless of package-level `default_visibility`).

*   [`--incompatible_config_setting_private_default_visibility`](https://github.com/bazelbuild/bazel/issues/12933){: .external}
    causes `config_setting`s that do not specify a `visibility` to respect the
    package's `default_visibility` and to fallback on private visibility, just
    like any other rule target. It is a no-op if
    `--incompatible_enforce_config_setting_visibility` is not set.

Avoid relying on the legacy behavior. Any `config_setting` that is intended to
be used outside the current package should have an explicit `visibility`, if the
package does not already specify a suitable `default_visibility`.

### Package group target visibility {:#package-group-target-visibility}

`package_group` targets do not have a `visibility` attribute. They are always
publicly visible.

### Visibility of implicit dependencies {:#visibility-implicit-dependencies}

Some rules have [implicit dependencies](/extending/rules#private_attributes_and_implicit_dependencies) —
dependencies that are not spelled out in a `BUILD` file but are inherent to
every instance of that rule. For example, a `cc_library` rule might create an
implicit dependency from each of its rule targets to an executable target
representing a C++ compiler.

The visibility of such an implicit dependency is checked with respect to the
package containing the `.bzl` file in which the rule (or aspect) is defined. In
our example, the C++ compiler could be private so long as it lives in the same
package as the definition of the `cc_library` rule. As a fallback, if the
implicit dependency is not visible from the definition, it is checked with
respect to the `cc_library` target.

If you want to restrict the usage of a rule to certain packages, use
[load visibility](#load-visibility) instead.

### Visibility and symbolic macros {:#symbolic-macros}

This section describes how the visibility system interacts with
[symbolic macros](/extending/macros).

#### Locations within symbolic macros {:#locations-within-symbolic-macros}

A key detail of the visibility system is how we determine the location of a
declaration. For targets that are not declared in a symbolic macro, the location
is just the package where the target lives -- the package of the `BUILD` file.
But for targets created in a symbolic macro, the location is the package
containing the `.bzl` file where the macro's definition (the
`my_macro = macro(...)` statement) appears. When a target is created inside
multiple nested targets, it is always the innermost symbolic macro's definition
that is used.

The same system is used to determine what location to check against a given
dependency's visibility. If the consuming target was created inside a macro, we
look at the innermost macro's definition rather than the package the consuming
target lives in.

This means that all macros whose code is defined in the same package are
automatically "friends" with one another. Any target directly created by a macro
defined in `//lib:defs.bzl` can be seen from any other macro defined in `//lib`,
regardless of what packages the macros are actually instantiated in. Likewise,
they can see, and can be seen by, targets declared directly in `//lib/BUILD` and
its legacy macros. Conversely, targets that live in the same package cannot
necessarily see one another if at least one of them is created by a symbolic
macro.

Within a symbolic macro's implementation function, the `visibility` parameter
has the effective value of the macro's `visibility` attribute after appending
the location where the macro was called. The standard way for a macro to export
one of its targets to its caller is to forward this value along to the target's
declaration, as in `some_rule(..., visibility = visibility)`. Targets that omit
this attribute won't be visible to the caller of the macro unless the caller
happens to be in the same package as the macro definition. This behavior
composes, in the sense that a chain of nested calls to submacros may each pass
`visibility = visibility`, re-exporting the inner macro's exported targets to
the caller at each level, without exposing any of the macros' implementation
details.

#### Delegating privileges to a submacro {:#delegating-privileges-to-a-submacro}

The visibility model has a special feature to allow a macro to delegate its
permissions to a submacro. This is important for factoring and composing macros.

Suppose you have a macro `my_macro` that creates a dependency edge using a rule
`some_library` from another package:

```starlark
# //macro/defs.bzl
load("//lib:defs.bzl", "some_library")

def _impl(name, visibility, ...):
    ...
    native.genrule(
        name = name + "_dependency"
        ...
    )
    some_library(
        name = name + "_consumer",
        deps = [name + "_dependency"],
        ...
    )

my_macro = macro(implementation = _impl, ...)
```

```starlark
# //pkg/BUILD

load("//macro:defs.bzl", "my_macro")

my_macro(name = "foo", ...)
```

The `//pkg:foo_dependency` target has no `visibility` specified, so it is only
visible within `//macro`, which works fine for the consuming target. Now, what
happens if the author of `//lib` refactors `some_library` to instead be
implemented using a macro?

```starlark
# //lib:defs.bzl

def _impl(name, visibility, deps, ...):
    some_rule(
        # Main target, exported.
        name = name,
        visibility = visibility,
        deps = deps,
        ...)

some_library = macro(implementation = _impl, ...)
```

With this change, `//pkg:foo_consumer`'s location is now `//lib` rather than
`//macro`, so its usage of `//pkg:foo_dependency` violates the dependency's
visibility. The author of `my_macro` can't be expected to pass
`visibility = ["//lib"]` to the declaration of the dependency just to work
around this implementation detail.

For this reason, when a dependency of a target is also an attribute value of the
macro that declared the target, we check the dependency's visibility against the
location of the macro instead of the location of the consuming target.

In this example, to validate whether `//pkg:foo_consumer` can see
`//pkg:foo_dependency`, we see that `//pkg:foo_dependency` was also passed as an
input to the call to `some_library` inside of `my_macro`, and instead check the
dependency's visibility against the location of this call, `//macro`.

This process can repeat recursively, as long as a target or macro declaration is
inside of another symbolic macro taking the dependency's label in one of its
label-typed attributes.

Note: Visibility delegation does not work for labels that were not passed into
the macro, such as labels derived by string manipulation.

## Load visibility {:#load-visibility}

**Load visibility** controls whether a `.bzl` file may be loaded from other
`BUILD` or `.bzl` files outside the current package.

In the same way that target visibility protects source code that is encapsulated
by targets, load visibility protects build logic that is encapsulated by `.bzl`
files. For instance, a `BUILD` file author might wish to factor some repetitive
target declarations into a macro in a `.bzl` file. Without the protection of
load visibility, they might find their macro reused by other collaborators in
the same workspace, so that modifying the macro breaks other teams' builds.

Note that a `.bzl` file may or may not have a corresponding source file target.
If it does, there is no guarantee that the load visibility and the target
visibility coincide. That is, the same `BUILD` file might be able to load the
`.bzl` file but not list it in the `srcs` of a [`filegroup`](/reference/be/general#filegroup),
or vice versa. This can sometimes cause problems for rules that wish to consume
`.bzl` files as source code, such as for documentation generation or testing.

For prototyping, you may disable load visibility enforcement by setting
`--check_bzl_visibility=false`. As with `--check_visibility=false`, this should
not be done for submitted code.

Load visibility is available as of Bazel 6.0.

### Declaring load visibility {:#declaring-load-visibility}

To set the load visibility of a `.bzl` file, call the
[`visibility()`](/rules/lib/globals/bzl#visibility) function from within the file.
The argument to `visibility()` is a list of package specifications, just like
the [`packages`](/reference/be/functions#package_group.packages) attribute of
`package_group`. However, `visibility()` does not accept negative package
specifications.

The call to `visibility()` must only occur once per file, at the top level (not
inside a function), and ideally immediately following the `load()` statements.

Unlike target visibility, the default load visibility is always public. Files
that do not call `visibility()` are always loadable from anywhere in the
workspace. It is a good idea to add `visibility("private")` to the top of any
new `.bzl` file that is not specifically intended for use outside the package.

### Example {:#load-visibility-example}

```starlark
# //mylib/internal_defs.bzl

# Available to subpackages and to mylib's tests.
visibility(["//mylib/...", "//tests/mylib/..."])

def helper(...):
    ...
```

```starlark
# //mylib/rules.bzl

load(":internal_defs.bzl", "helper")
# Set visibility explicitly, even though public is the default.
# Note the [] can be omitted when there's only one entry.
visibility("public")

myrule = rule(
    ...
)
```

```starlark
# //someclient/BUILD

load("//mylib:rules.bzl", "myrule")          # ok
load("//mylib:internal_defs.bzl", "helper")  # error

...
```

### Load visibility practices {:#load-visibility-practices}

This section describes tips for managing load visibility declarations.

#### Factoring visibilities {:#factoring-visibilities}

When multiple `.bzl` files should have the same visibility, it can be helpful to
factor their package specifications into a common list. For example:

```starlark
# //mylib/internal_defs.bzl

visibility("private")

clients = [
    "//foo",
    "//bar/baz/...",
    ...
]
```

```starlark
# //mylib/feature_A.bzl

load(":internal_defs.bzl", "clients")
visibility(clients)

...
```

```starlark
# //mylib/feature_B.bzl

load(":internal_defs.bzl", "clients")
visibility(clients)

...
```

This helps prevent accidental skew between the various `.bzl` files'
visibilities. It also is more readable when the `clients` list is large.

#### Composing visibilities {:#composing-visibilities}

Sometimes a `.bzl` file might need to be visible to an allowlist that is
composed of multiple smaller allowlists. This is analogous to how a
`package_group` can incorporate other `package_group`s via its
[`includes`](/reference/be/functions#package_group.includes) attribute.

Suppose you are deprecating a widely used macro. You want it to be visible only
to existing users and to the packages owned by your own team. You might write:

```starlark
# //mylib/macros.bzl

load(":internal_defs.bzl", "our_packages")
load("//some_big_client:defs.bzl", "their_remaining_uses")

# List concatenation. Duplicates are fine.
visibility(our_packages + their_remaining_uses)
```

#### Deduplicating with package groups {:#deduplicating-with-package-groups}

Unlike target visibility, you cannot define a load visibility in terms of a
`package_group`. If you want to reuse the same allowlist for both target
visibility and load visibility, it's best to move the list of package
specifications into a .bzl file, where both kinds of declarations may refer to
it. Building off the example in [Factoring visibilities](#factoring-visibilities)
above, you might write:

```starlark
# //mylib/BUILD

load(":internal_defs", "clients")

package_group(
    name = "my_pkg_grp",
    packages = clients,
)
```

This only works if the list does not contain any negative package
specifications.

#### Protecting individual symbols {:#protecting-individual-symbols}

Any Starlark symbol whose name begins with an underscore cannot be loaded from
another file. This makes it easy to create private symbols, but does not allow
you to share these symbols with a limited set of trusted files. On the other
hand, load visibility gives you control over what other packages may see your
`.bzl file`, but does not allow you to prevent any non-underscored symbol from
being loaded.

Luckily, you can combine these two features to get fine-grained control.

```starlark
# //mylib/internal_defs.bzl

# Can't be public, because internal_helper shouldn't be exposed to the world.
visibility("private")

# Can't be underscore-prefixed, because this is
# needed by other .bzl files in mylib.
def internal_helper(...):
    ...

def public_util(...):
    ...
```

```starlark
# //mylib/defs.bzl

load(":internal_defs", "internal_helper", _public_util="public_util")
visibility("public")

# internal_helper, as a loaded symbol, is available for use in this file but
# can't be imported by clients who load this file.
...

# Re-export public_util from this file by assigning it to a global variable.
# We needed to import it under a different name ("_public_util") in order for
# this assignment to be legal.
public_util = _public_util
```

#### bzl-visibility Buildifier lint {:#bzl-visibility-buildifier-lint}

There is a [Buildifier lint](https://github.com/bazelbuild/buildtools/blob/master/WARNINGS.md#bzl-visibility)
that provides a warning if users load a file from a directory named `internal`
or `private`, when the user's file is not itself underneath the parent of that
directory. This lint predates the load visibility feature and is unnecessary in
workspaces where `.bzl` files declare visibilities.
