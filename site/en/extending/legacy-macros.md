Project: /_project.yaml
Book: /_book.yaml
{# disableFinding("native") #}
{# disableFinding("Native") #}
{# disableFinding(LINE_OVER_80_LINK) #}

# Legacy Macros

{% include "_buttons.html" %}

Legacy macros are unstructured functions called from `BUILD` files that can
create targets. By the end of the
[loading phase](/extending/concepts#evaluation-model), legacy macros don't exist
anymore, and Bazel sees only the concrete set of instantiated rules.

## Why you shouldn't use legacy macros (and should use Symbolic macros instead) {:#no-legacy-macros}

Where possible you should use [symbolic macros](macros.md#macros).

Symbolic macros

*   Prevent action at a distance
*   Make it possible to hide implementation details through granular visibility
*   Take typed attributes, which in turn means automatic label and select
    conversion.
*   Are more readable
*   Will soon have [lazy evaluation](macros.md/laziness)

## Usage {:#usage}

The typical use case for a macro is when you want to reuse a rule.

For example, genrule in a `BUILD` file generates a file using `//:generator`
with a `some_arg` argument hardcoded in the command:

```python
genrule(
    name = "file",
    outs = ["file.txt"],
    cmd = "$(location //:generator) some_arg > $@",
    tools = ["//:generator"],
)
```

Note: `$@` is a
[Make variable](/reference/be/make-variables#predefined_genrule_variables) that
refers to the execution-time locations of the files in the `outs` attribute
list. It is equivalent to `$(locations :file.txt)`.

If you want to generate more files with different arguments, you may want to
extract this code to a macro function. To create a macro called
`file_generator`, which has `name` and `arg` parameters, we can replace the
genrule with the following:

```python
load("//path:generator.bzl", "file_generator")

file_generator(
    name = "file",
    arg = "some_arg",
)

file_generator(
    name = "file-two",
    arg = "some_arg_two",
)

file_generator(
    name = "file-three",
    arg = "some_arg_three",
)
```

Here, you load the `file_generator` symbol from a `.bzl` file located in the
`//path` package. By putting macro function definitions in a separate `.bzl`
file, you keep your `BUILD` files clean and declarative, The `.bzl` file can be
loaded from any package in the workspace.

Finally, in `path/generator.bzl`, write the definition of the macro to
encapsulate and parameterize the original genrule definition:

```python
def file_generator(name, arg, visibility=None):
  native.genrule(
    name = name,
    outs = [name + ".txt"],
    cmd = "$(location //:generator) %s > $@" % arg,
    tools = ["//:generator"],
    visibility = visibility,
  )
```

You can also use macros to chain rules together. This example shows chained
genrules, where a genrule uses the outputs of a previous genrule as inputs:

```python
def chained_genrules(name, visibility=None):
  native.genrule(
    name = name + "-one",
    outs = [name + ".one"],
    cmd = "$(location :tool-one) $@",
    tools = [":tool-one"],
    visibility = ["//visibility:private"],
  )

  native.genrule(
    name = name + "-two",
    srcs = [name + ".one"],
    outs = [name + ".two"],
    cmd = "$(location :tool-two) $< $@",
    tools = [":tool-two"],
    visibility = visibility,
  )
```

The example only assigns a visibility value to the second genrule. This allows
macro authors to hide the outputs of intermediate rules from being depended upon
by other targets in the workspace.

Note: Similar to `$@` for outputs, `$<` expands to the locations of files in the
`srcs` attribute list.

## Expanding macros {:#expanding-macros}

When you want to investigate what a macro does, use the `query` command with
`--output=build` to see the expanded form:

```none
$ bazel query --output=build :file
# /absolute/path/test/ext.bzl:42:3
genrule(
  name = "file",
  tools = ["//:generator"],
  outs = ["//test:file.txt"],
  cmd = "$(location //:generator) some_arg > $@",
)
```

## Instantiating native rules {:#instantiating-native-rules}

Native rules (rules that don't need a `load()` statement) can be instantiated
from the [native](/rules/lib/toplevel/native) module:

```python
def my_macro(name, visibility=None):
  native.cc_library(
    name = name,
    srcs = ["main.cc"],
    visibility = visibility,
  )
```

If you need to know the package name (for example, which `BUILD` file is calling
the macro), use the function
[native.package_name()](/rules/lib/toplevel/native#package_name). Note that
`native` can only be used in `.bzl` files, and not in `BUILD` files.

## Label resolution in macros {:#label-resolution}

Since legacy macros are evaluated in the
[loading phase](concepts.md#evaluation-model), label strings such as
`"//foo:bar"` that occur in a legacy macro are interpreted relative to the
`BUILD` file in which the macro is used rather than relative to the `.bzl` file
in which it is defined. This behavior is generally undesirable for macros that
are meant to be used in other repositories, such as because they are part of a
published Starlark ruleset.

To get the same behavior as for Starlark rules, wrap the label strings with the
[`Label`](/rules/lib/builtins/Label#Label) constructor:

```python
# @my_ruleset//rules:defs.bzl
def my_cc_wrapper(name, deps = [], **kwargs):
  native.cc_library(
    name = name,
    deps = deps + select({
      # Due to the use of Label, this label is resolved within @my_ruleset,
      # regardless of its site of use.
      Label("//config:needs_foo"): [
        # Due to the use of Label, this label will resolve to the correct target
        # even if the canonical name of @dep_of_my_ruleset should be different
        # in the main repo, such as due to repo mappings.
        Label("@dep_of_my_ruleset//tools:foo"),
      ],
      "//conditions:default": [],
    }),
    **kwargs,
  )
```

## Debugging {:#debugging}

*   `bazel query --output=build //my/path:all` will show you how the `BUILD`
    file looks after evaluation. All legacy macros, globs, loops are expanded.
    Known limitation: `select` expressions are not shown in the output.

*   You may filter the output based on `generator_function` (which function
    generated the rules) or `generator_name` (the name attribute of the macro):
    `bash $ bazel query --output=build 'attr(generator_function, my_macro,
    //my/path:all)'`

*   To find out where exactly the rule `foo` is generated in a `BUILD` file, you
    can try the following trick. Insert this line near the top of the `BUILD`
    file: `cc_library(name = "foo")`. Run Bazel. You will get an exception when
    the rule `foo` is created (due to a name conflict), which will show you the
    full stack trace.

*   You can also use [print](/rules/lib/globals/all#print) for debugging. It
    displays the message as a `DEBUG` log line during the loading phase. Except
    in rare cases, either remove `print` calls, or make them conditional under a
    `debugging` parameter that defaults to `False` before submitting the code to
    the depot.

## Errors {:#errors}

If you want to throw an error, use the [fail](/rules/lib/globals/all#fail)
function. Explain clearly to the user what went wrong and how to fix their
`BUILD` file. It is not possible to catch an error.

```python
def my_macro(name, deps, visibility=None):
  if len(deps) < 2:
    fail("Expected at least two values in deps")
  # ...
```

## Conventions {:#conventions}

*   All public functions (functions that don't start with underscore) that
    instantiate rules must have a `name` argument. This argument should not be
    optional (don't give a default value).

*   Public functions should use a docstring following
    [Python conventions](https://www.python.org/dev/peps/pep-0257/#one-line-docstrings).

*   In `BUILD` files, the `name` argument of the macros must be a keyword
    argument (not a positional argument).

*   The `name` attribute of rules generated by a macro should include the name
    argument as a prefix. For example, `macro(name = "foo")` can generate a
    `cc_library` `foo` and a genrule `foo_gen`.

*   In most cases, optional parameters should have a default value of `None`.
    `None` can be passed directly to native rules, which treat it the same as if
    you had not passed in any argument. Thus, there is no need to replace it
    with `0`, `False`, or `[]` for this purpose. Instead, the macro should defer
    to the rules it creates, as their defaults may be complex or may change over
    time. Additionally, a parameter that is explicitly set to its default value
    looks different than one that is never set (or set to `None`) when accessed
    through the query language or build-system internals.

*   Macros should have an optional `visibility` argument.
