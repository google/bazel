Project: /_project.yaml
Book: /_book.yaml
{# disableFinding("Currently") #}
{# disableFinding(TODO) #}

# Macros

{% include "_buttons.html" %}

This page covers the basics of using macros and includes typical use cases,
debugging, and conventions.

A macro is a function called from the `BUILD` file that can instantiate rules.
Macros are mainly used for encapsulation and code reuse of existing rules and
other macros.

Macros come in two flavors: symbolic macros, which are described on this page,
and [legacy macros](legacy-macros.md). Where possible, we recommend using
symbolic macros for code clarity.

Symbolic macros offer typed arguments (string to label conversion, relative to
where the macro was called) and the ability to restrict and specify the
visibility of targets created. They are designed to be amenable to lazy
evaluation (which will be added in a future Bazel release). Symbolic macros are
available by default in Bazel 8. Where this document mentions `macros`, it's
referring to **symbolic macros**.

An executable example of symbolic macros can be found in the
[examples repository](https://github.com/bazelbuild/examples/tree/main/macros).

## Usage {:#usage}

Macros are defined in `.bzl` files by calling the
[`macro()`](https://bazel.build/rules/lib/globals/bzl.html#macro) function with
two required parameters: `attrs` and `implementation`.

### Attributes {:#attributes}

`attrs` accepts a dictionary of attribute name to [attribute
types](https://bazel.build/rules/lib/toplevel/attr#members), which represents
the arguments to the macro. Two common attributes – `name` and `visibility` –
are implicitly added to all macros and are not included in the dictionary passed
to `attrs`.

```starlark
# macro/macro.bzl
my_macro = macro(
    attrs = {
        "deps": attr.label_list(mandatory = True, doc = "The dependencies passed to the inner cc_binary and cc_test targets"),
        "create_test": attr.bool(default = False, configurable = False, doc = "If true, creates a test target"),
    },
    implementation = _my_macro_impl,
)
```

Attribute type declarations accept the
[parameters](https://bazel.build/rules/lib/toplevel/attr#parameters),
`mandatory`, `default`, and `doc`. Most attribute types also accept the
`configurable` parameter, which determines wheher the attribute accepts
`select`s. If an attribute is `configurable`, it will parse non-`select` values
as an unconfigurable `select` – `"foo"` will become
`select({"//conditions:default": "foo"})`. Learn more in [selects](#selects).

#### Attribute inheritance {:#attribute-inheritance}

Macros are often intended to wrap a rule (or another macro), and the macro's
author often wants to forward the bulk of the wrapped symbol's attributes
unchanged, using `**kwargs`, to the macro's main target (or main inner macro).

To support this pattern, a macro can *inherit attributes* from a rule or another
macro by passing the [rule](https://bazel.build/rules/lib/builtins/rule) or
[macro symbol](https://bazel.build/rules/lib/builtins/macro) to `macro()`'s
`inherit_attrs` argument. (You can also use the special string `"common"`
instead of a rule or macro symbol to inherit the [common attributes defined for
all Starlark build
rules](https://bazel.build/reference/be/common-definitions#common-attributes).)
Only public attributes get inherited, and the attributes in the macro's own
`attrs` dictionary override inherited attributes with the same name. You can
also *remove* inherited attributes by using `None` as a value in the `attrs`
dictionary:

```starlark
# macro/macro.bzl
my_macro = macro(
    inherit_attrs = native.cc_library,
    attrs = {
        # override native.cc_library's `local_defines` attribute
        "local_defines": attr.string_list(default = ["FOO"]),
        # do not inherit native.cc_library's `defines` attribute
        "defines": None,
    },
    ...
)
```

The default value of non-mandatory inherited attributes is always overridden to
be `None`, regardless of the original attribute definition's default value. If
you need to examine or modify an inherited non-mandatory attribute – for
example, if you want to add a tag to an inherited `tags` attribute – you must
make sure to handle the `None` case in your macro's implementation function:

```starlark
# macro/macro.bzl
_my_macro_implementation(name, visibility, tags, **kwargs):
    # Append a tag; tags attr is an inherited non-mandatory attribute, and
    # therefore is None unless explicitly set by the caller of our macro.
    my_tags = (tags or []) + ["another_tag"]
    native.cc_library(
        ...
        tags = my_tags,
        **kwargs,
    )
    ...
```

### Implementation {:#implementation}

`implementation` accepts a function which contains the logic of the macro.
Implementation functions often create targets by calling one or more rules, and
they are are usually private (named with a leading underscore). Conventionally,
they are named the same as their macro, but prefixed with `_` and suffixed with
`_impl`.

Unlike rule implementation functions, which take a single argument (`ctx`) that
contains a reference to the attributes, macro implementation functions accept a
parameter for each argument.

```starlark
# macro/macro.bzl
def _my_macro_impl(name, visibility, deps, create_test):
    cc_library(
        name = name + "_cc_lib",
        deps = deps,
    )

    if create_test:
        cc_test(
            name = name + "_test",
            srcs = ["my_test.cc"],
            deps = deps,
        )
```

If a macro inherits attributes, its implementation function *must* have a
`**kwargs` residual keyword parameter, which can be forwarded to the call that
invokes the inherited rule or submacro. (This helps ensure that your macro won't
be broken if the rule or macro which from which you are inheriting adds a new
attribute.)

### Declaration {:#declaration}

Macros are declared by loading and calling their definition in a `BUILD` file.

```starlark

# pkg/BUILD

my_macro(
    name = "macro_instance",
    deps = ["src.cc"] + select(
        {
            "//config_setting:special": ["special_source.cc"],
            "//conditions:default": [],
        },
    ),
    create_tests = True,
)
```

This would create targets
`//pkg:macro_instance_cc_lib` and`//pkg:macro_instance_test`.

Just like in rule calls, if an attribute value in a macro call is set to `None`,
that attribute is treated as if it was omitted by the macro's caller. For
example, the following two macro calls are equivalent:

```starlark
# pkg/BUILD
my_macro(name = "abc", srcs = ["src.cc"], deps = None)
my_macro(name = "abc", srcs = ["src.cc"])
```

This is generally not useful in `BUILD` files, but is helpful when
programmatically wrapping a macro inside another macro.

## Details {:#usage-details}

### Naming conventions for targets created {:#naming}

The names of any targets or submacros created by a symbolic macro must
either match the macro's `name` parameter or must be prefixed by `name` followed
by `_` (preferred), `.` or `-`. For example, `my_macro(name = "foo")` may only
create files or targets named `foo`, or prefixed by `foo_`, `foo-` or `foo.`,
for example, `foo_bar`.

Targets or files that violate macro naming convention can be declared, but
cannot be built and cannot be used as dependencies.

Non-macro files and targets within the same package as a macro instance should
*not* have names that conflict with potential macro target names, though this
exclusivity is not enforced. We are in the progress of implementing
[lazy evaluation](#laziness) as a performance improvement for Symbolic macros,
which will be impaired in packages that violate the naming schema.

### Restrictions {:#restrictions}

Symbolic macros have some additional restrictions compared to legacy macros.

Symbolic macros

*   must take a `name` argument and a `visibility` argument
*   must have an `implementation` function
*   may not return values
*   may not mutate their arguments
*   may not call `native.existing_rules()` unless they are special `finalizer`
    macros
*   may not call `native.package()`
*   may not call `glob()`
*   may not call `native.environment_group()`
*   must create targets whose names adhere to the [naming schema](#naming)
*   can't refer to input files that weren't declared or passed in as an argument
*   can't refer to private targets of their callers (see
    [visibility and macros](#visibility) for more details).

### Visibility and macros {:#visibility}

The [visibility](/concepts/visibility) system helps protect the implementation
details of both (symbolic) macros and their callers.

By default, targets created in a symbolic macro are visible within the macro
itself, but not necessarily to the macro's caller. The macro can "export" a
target as a public API by forwarding the value of its own `visibility`
attribute, as in `some_rule(..., visibility = visibility)`.

The key ideas of macro visibility are:

1. Visibility is checked based on what macro declared the target, not what
   package called the macro.

   * In other words, being in the same package does not by itself make one
     target visible to another. This protects the macro's internal targets
     from becoming dependencies of other macros or top-level targets in the
     package.

1. All `visibility` attributes, on both rules and macros, automatically
   include the place where the rule or macro was called.

   * Thus, a target is unconditionally visible to other targets declared in the
     same macro (or the `BUILD` file, if not in a macro).

In practice, this means that when a macro declares a target without setting its
`visibility`, the target defaults to being internal to the macro. (The package's
[default visibility](/reference/be/functions#package.default_visibility) does
not apply within a macro.) Exporting the target means that the target is visible
to whatever the macro's caller specified in the macro's `visibility` attribute,
plus the package of the macro's caller itself, as well as the macro's own code.
Another way of thinking of it is that the visibility of a macro determines who
(aside from the macro itself) can see the macro's exported targets.

```starlark
# tool/BUILD
...
some_rule(
    name = "some_tool",
    visibility = ["//macro:__pkg__"],
)
```

```starlark
# macro/macro.bzl

def _impl(name, visibility):
    cc_library(
        name = name + "_helper",
        ...
        # No visibility passed in. Same as passing `visibility = None` or
        # `visibility = ["//visibility:private"]`. Visible to the //macro
        # package only.
    )
    cc_binary(
        name = name + "_exported",
        deps = [
            # Allowed because we're also in //macro. (Targets in any other
            # instance of this macro, or any other macro in //macro, can see it
            # too.)
            name + "_helper",
            # Allowed by some_tool's visibility, regardless of what BUILD file
            # we're called from.
            "//tool:some_tool",
        ],
        ...
        visibility = visibility,
    )

my_macro = macro(implementation = _impl, ...)
```

```starlark
# pkg/BUILD
load("//macro:macro.bzl", "my_macro")
...

my_macro(
    name = "foo",
    ...
)

some_rule(
    ...
    deps = [
        # Allowed, its visibility is ["//pkg:__pkg__", "//macro:__pkg__"].
        ":foo_exported",
        # Disallowed, its visibility is ["//macro:__pkg__"] and
        # we are not in //macro.
        ":foo_helper",
    ]
)
```

If `my_macro` were called with `visibility = ["//other_pkg:__pkg__"]`, or if
the `//pkg` package had set its `default_visibility` to that value, then
`//pkg:foo_exported` could also be used within `//other_pkg/BUILD` or within a
macro defined in `//other_pkg:defs.bzl`, but `//pkg:foo_helper` would remain
protected.

A macro can declare that a target is visible to a friend package by passing
`visibility = ["//some_friend:__pkg__"]` (for an internal target) or
`visibility = visibility + ["//some_friend:__pkg__"]` (for an exported one).
Note that it is an antipattern for a macro to declare a target with public
visibility (`visibility = ["//visibility:public"]`). This is because it makes
the target unconditionally visible to every package, even if the caller
specified a more restricted visibility.

All visibility checking is done with respect to the innermost currently running
symbolic macro. However, there is a visibility delegation mechanism: If a macro
passes a label as an attribute value to an inner macro, any usages of the label
in the inner macro are checked with respect to the outer macro. See the
[visibility page](/concepts/visibility#symbolic-macros) for more details.

Remember that legacy macros are entirely transparent to the visibility system,
and behave as though their location is whatever BUILD file or symbolic macro
they were called from.

### Selects {:#selects}

If an attribute is `configurable` (the default) and its value is not `None`,
then the macro implementation function will see the attribute value as wrapped
in a trivial `select`. This makes it easier for the macro author to catch bugs
where they did not anticipate that the attribute value could be a `select`.

For example, consider the following macro:

```starlark
my_macro = macro(
    attrs = {"deps": attr.label_list()},  # configurable unless specified otherwise
    implementation = _my_macro_impl,
)
```

If `my_macro` is invoked with `deps = ["//a"]`, that will cause `_my_macro_impl`
to be invoked with its `deps` parameter set to `select({"//conditions:default":
["//a"]})`. If this causes the implementation function to fail (say, because the
code tried to index into the value as in `deps[0]`, which is not allowed for
`select`s), the macro author can then make a choice: either they can rewrite
their macro to only use operations compatible with `select`, or they can mark
the attribute as nonconfigurable (`attr.label_list(configurable = False)`). The
latter ensures that users are not permitted to pass a `select` value in.

Rule targets reverse this transformation, and store trivial `select`s as their
unconditional values; in the above example, if `_my_macro_impl` declares a rule
target `my_rule(..., deps = deps)`, that rule target's `deps` will be stored as
`["//a"]`. This ensures that `select`-wrapping does not cause trivial `select`
values to be stored in all targets instantiated by macros.

If the value of a configurable attribute is `None`, it does not get wrapped in a
`select`. This ensures that tests like `my_attr == None` still work, and that
when the attribute is forwarded to a rule with a computed default, the rule
behaves properly (that is, as if the attribute were not passed in at all). It is
not always possible for an attribute to take on a `None` value, but it can
happen for the `attr.label()` type, and for any inherited non-mandatory
attribute.

## Finalizers {:#finalizers}

A rule finalizer is a special symbolic macro which – regardless of its lexical
position in a BUILD file – is evaluated in the final stage of loading a package,
after all non-finalizer targets have been defined. Unlike ordinary symbolic
macros, a finalizer can call `native.existing_rules()`, where it behaves
slightly differently than in legacy macros: it only returns the set of
non-finalizer rule targets. The finalizer may assert on the state of that set or
define new targets.

To declare a finalizer, call `macro()` with `finalizer = True`:

```starlark
def _my_finalizer_impl(name, visibility, tags_filter):
    for r in native.existing_rules().values():
        for tag in r.get("tags", []):
            if tag in tags_filter:
                my_test(
                    name = name + "_" + r["name"] + "_finalizer_test",
                    deps = [r["name"]],
                    data = r["srcs"],
                    ...
                )
                continue

my_finalizer = macro(
    attrs = {"tags_filter": attr.string_list(configurable = False)},
    implementation = _impl,
    finalizer = True,
)
```

## Laziness {:#laziness}

IMPORTANT: We are in the process of implementing lazy macro expansion and
evaluation. This feature is not available yet.

Currently, all macros are evaluated as soon as the BUILD file is loaded, which
can negatively impact performance for targets in packages that also have costly
unrelated macros. In the future, non-finalizer symbolic macros will only be
evaluated if they're required for the build. The prefix naming schema helps
Bazel determine which macro to expand given a requested target.

## Migration troubleshooting {:#troubleshooting}

Here are some common migration headaches and how to fix them.

*   Legacy macro calls `glob()`

Move the `glob()` call to your BUILD file (or to a legacy macro called from the
BUILD file), and pass the `glob()` value to the symbolic macro using a
label-list attribute:

```starlark
# BUILD file
my_macro(
    ...,
    deps = glob(...),
)
```

*   Legacy macro has a parameter that isn't a valid starlark `attr` type.

Pull as much logic as possible into a nested symbolic macro, but keep the
top level macro a legacy macro.

*  Legacy macro calls a rule that creates a target that breaks the naming schema

That's okay, just don't depend on the "offending" target. The naming check will
be quietly ignored.

