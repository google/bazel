Project: /_project.yaml
Book: /_book.yaml

# Creating a Legacy Macro

{% include "_buttons.html" %}

IMPORTANT: This tutorial is for [*legacy macros*](/extending/legacy-macros). If
you only need to support Bazel 8 or newer, we recommend using [symbolic
macros](/extending/macros) instead; take a look at [Creating a Symbolic
Macro](../macro-tutorial).

Imagine that you need to run a tool as part of your build. For example, you
may want to generate or preprocess a source file, or compress a binary. In this
tutorial, you are going to create a legacy macro that resizes an image.

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
define a function in a separate `.bzl` file, and call the file `miniature.bzl`:

```starlark
def miniature(name, src, size = "100x100", **kwargs):
    """Create a miniature of the src image.

    The generated file is prefixed with 'small_'.
    """
    native.genrule(
        name = name,
        srcs = [src],
        # Note that the line below will fail if `src` is not a filename string
        outs = ["small_" + src],
        cmd = "convert $< -resize " + size + " $@",
        **kwargs
    )
```

A few remarks:

  * By convention, legacy macros have a `name` argument, just like rules.

  * To document the behavior of a legacy macro, use
    [docstring](https://www.python.org/dev/peps/pep-0257/) like in Python.

  * To call a `genrule`, or any other native rule, prefix with `native.`.

  * Use `**kwargs` to forward the extra arguments to the underlying `genrule`
    (it works just like in
    [Python](https://docs.python.org/3/tutorial/controlflow.html#keyword-arguments)).
    This is useful, so that a user can use standard attributes like
    `visibility`, or `tags`.

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

And finally, a **warning note**: the macro assumes that `src` is a filename
string (otherwise, `outs = ["small_" + src]` will fail). So `src = "image.png"`
works; but what happens if the `BUILD` file instead used `src =
"//other/package:image.png"`, or even `src = select(...)`?

You should make sure to declare such assumptions in your macro's documentation.
Unfortunately, legacy macros, especially large ones, tend to be fragile because
it can be hard to notice and document all such assumptions in your code – and,
of course, some users of the macro won't read the documentation. We recommend,
if possible, instead using [symbolic macros](/extending/macros), which have
built\-in checks on attribute types.
