Project: /_project.yaml
Book: /_book.yaml

# Creating a Symbolic Macro

{% include "_buttons.html" %}

IMPORTANT: This tutorial is for [*symbolic macros*](/extending/macros) – the new
macro system introduced in Bazel 8. If you need to support older Bazel versions,
you will want to write a [legacy macro](/extending/legacy-macros) instead; take
a look at [Creating a Legacy Macro](../legacy-macro-tutorial).

Imagine that you need to run a tool as part of your build. For example, you
may want to generate or preprocess a source file, or compress a binary. In this
tutorial, you are going to create a symbolic macro that resizes an image.

Macros are suitable for simple tasks. If you want to do anything more
complicated, for example add support for a new programming language, consider
creating a [rule](/extending/rules). Rules give you more control and flexibility.

The easiest way to create a macro that resizes an image is to use a `genrule`:

```starlark
genrule(
    name = "logo_miniature",
    srcs = ["logo.png"],
    outs = ["small_logo.png"],
    cmd = "convert $< -resize 100x100 $@",
)

cc_binary(
    name = "my_app",
    srcs = ["my_app.cc"],
    data = [":logo_miniature"],
)
```

If you need to resize more images, you may want to reuse the code. To do that,
define an *implementation function* and a *macro declaration* in a separate
`.bzl` file, and call the file `miniature.bzl`:

```starlark
# Implementation function
def _miniature_impl(name, visibility, src, size, **kwargs):
    native.genrule(
        name = name,
        visibility = visibility,
        srcs = [src],
        outs = [name + "_small_" + src.name],
        cmd = "convert $< -resize " + size + " $@",
        **kwargs,
    )

# Macro declaration
miniature = macro(
    doc = """Create a miniature of the src image.

    The generated file name will be prefixed with `name + "_small_"`.
    """,
    implementation = _miniature_impl,
    # Inherit most of genrule's attributes (such as tags and testonly)
    inherit_attrs = native.genrule,
    attrs = {
        "src": attr.label(
            doc = "Image file",
            allow_single_file = True,
            # Non-configurable because our genrule's output filename is
            # suffixed with src's name. (We want to suffix the output file with
            # srcs's name because some tools that operate on image files expect
            # the files to have the right file extension.)
            configurable = False,
        ),
        "size": attr.string(
            doc = "Output size in WxH format",
            default = "100x100",
        ),
        # Do not allow callers of miniature() to set srcs, cmd, or outs -
        # _miniature_impl overrides their values when calling native.genrule()
        "srcs": None,
        "cmd": None,
        "outs": None,
    },
)
```

A few remarks:

  * Symbolic macro implementation functions must have `name` and `visibility`
    parameters. They should used for the macro's main target.

  * To document the behavior of a symbolic macro, use `doc` parameters for
    `macro()` and its attributes.

  * To call a `genrule`, or any other native rule, use `native.`.

  * Use `**kwargs` to forward the extra inherited arguments to the underlying
    `genrule` (it works just like in
    [Python](https://docs.python.org/3/tutorial/controlflow.html#keyword-arguments)).
    This is useful so that a user can set standard attributes like `tags` or
    `testonly`.

Now, use the macro from the `BUILD` file:

```starlark
load("//path/to:miniature.bzl", "miniature")

miniature(
    name = "logo_miniature",
    src = "image.png",
)

cc_binary(
    name = "my_app",
    srcs = ["my_app.cc"],
    data = [":logo_miniature"],
)
```
