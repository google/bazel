Project: /_project.yaml
Book: /_book.yaml

# Using Macros to Create Custom Verbs

{% include "_buttons.html" %}

Day-to-day interaction with Bazel happens primarily through a few commands:
`build`, `test`, and `run`. At times, though, these can feel limited: you may
want to push packages to a repository, publish documentation for end-users, or
deploy an application with Kubernetes. But Bazel doesn't have a `publish` or
`deploy` command – where do these actions fit in?

## The bazel run command

Bazel's focus on hermeticity, reproducibility, and incrementality means the
`build` and `test` commands aren't helpful for the above tasks. These actions
may run in a sandbox, with limited network access, and aren't guaranteed to be
re-run with every `bazel build`.

Instead, rely on `bazel run`: the workhorse for tasks that you *want* to have
side effects. Bazel users are accustomed to rules that create executables, and
rule authors can follow a common set of patterns to extend this to
"custom verbs".

### In the wild: rules_k8s
For example, consider [`rules_k8s`](https://github.com/bazelbuild/rules_k8s),
the Kubernetes rules for Bazel. Suppose you have the following target:

```python
# BUILD file in //application/k8s
k8s_object(
    name = "staging",
    kind = "deployment",
    cluster = "testing",
    template = "deployment.yaml",
)
```

The [`k8s_object` rule](https://github.com/bazelbuild/rules_k8s#usage) builds a
standard Kubernetes YAML file when `bazel build` is used on the `staging`
target. However, the additional targets are also created by the `k8s_object`
macro with names like `staging.apply` and `:staging.delete`. These build
scripts to perform those actions, and when executed with `bazel run
staging.apply`, these behave like our own `bazel k8s-apply` or `bazel
k8s-delete` commands.

### Another example: ts_api_guardian_test

This pattern can also be seen in the Angular project. The
[`ts_api_guardian_test` macro](https://github.com/angular/angular/blob/16ac611a8410e6bcef8ffc779f488ca4fa102155/tools/ts-api-guardian/index.bzl#L22)
produces two targets. The first is a standard `nodejs_test` target which compares
some generated output against a "golden" file (that is, a file containing the
expected output). This can be built and run with a normal `bazel
test` invocation. In `angular-cli`, you can run [one such
target](https://github.com/angular/angular-cli/blob/e1269cb520871ee29b1a4eec6e6c0e4a94f0b5fc/etc/api/BUILD)
with `bazel test //etc/api:angular_devkit_core_api`.

Over time, this golden file may need to be updated for legitimate reasons.
Updating this manually is tedious and error-prone, so this macro also provides
a `nodejs_binary` target that updates the golden file, instead of comparing
against it. Effectively, the same test script can be written to run in "verify"
or "accept" mode, based on how it's invoked. This follows the same pattern
you've learned already: there is no native `bazel test-accept` command, but the
same effect can be achieved with
`bazel run //etc/api:angular_devkit_core_api.accept`.

This pattern can be quite powerful, and turns out to be quite common once you
learn to recognize it.

## Adapting your own rules

[Macros](/extending/macros) are the heart of this pattern. Macros are used like
rules, but they can create several targets. Typically, they will create a
target with the specified name which performs the primary build action: perhaps
it builds a normal binary, a Docker image, or an archive of source code. In
this pattern, additional targets are created to produce scripts performing side
effects based on the output of the primary target, like publishing the
resulting binary or updating the expected test output.

To illustrate this, wrap an imaginary rule that generates a website with
[Sphinx](https://www.sphinx-doc.org) with a macro to create an additional
target that allows the user to publish it when ready. Consider the following
existing rule for generating a website with Sphinx:

```python
_sphinx_site = rule(
     implementation = _sphinx_impl,
     attrs = {"srcs": attr.label_list(allow_files = [".rst"])},
)
```

Next, consider a rule like the following, which builds a script that, when run,
publishes the generated pages:

```python
_sphinx_publisher = rule(
    implementation = _publish_impl,
    attrs = {
        "site": attr.label(),
        "_publisher": attr.label(
            default = "//internal/sphinx:publisher",
            executable = True,
        ),
    },
    executable = True,
)
```

Finally, define the following symbolic macro (available in Bazel 8 or newer) to
create targets for both of the above rules together:

```starlark
def _sphinx_site_impl(name, visibility, srcs, **kwargs):
    # This creates the primary target, producing the Sphinx-generated HTML. We
    # set `visibility = visibility` to make it visible to callers of the
    # macro.
    _sphinx_site(name = name, visibility = visibility, srcs = srcs, **kwargs)
    # This creates the secondary target, which produces a script for publishing
    # the site generated above. We don't want it to be visible to callers of
    # our macro, so we omit visibility for it.
    _sphinx_publisher(name = "%s.publish" % name, site = name, **kwargs)

sphinx_site = macro(
    implementation = _sphinx_site_impl,
    attrs = {"srcs": attr.label_list(allow_files = [".rst"])},
    # Inherit common attributes like tags and testonly
    inherit_attrs = "common",
)
```

Or, if you need to support Bazel releases older than Bazel 8, you would instead
define a legacy macro:

```starlark
def sphinx_site(name, srcs = [], **kwargs):
    # This creates the primary target, producing the Sphinx-generated HTML.
    _sphinx_site(name = name, srcs = srcs, **kwargs)
    # This creates the secondary target, which produces a script for publishing
    # the site generated above.
    _sphinx_publisher(name = "%s.publish" % name, site = name, **kwargs)
```

In the `BUILD` files, use the macro as though it just creates the primary
target:

```python
sphinx_site(
    name = "docs",
    srcs = ["index.md", "providers.md"],
)
```

In this example, a "docs" target is created, just as though the macro were a
standard, single Bazel rule. When built, the rule generates some configuration
and runs Sphinx to produce an HTML site, ready for manual inspection. However,
an additional "docs.publish" target is also created, which builds a script for
publishing the site. Once you check the output of the primary target, you can
use `bazel run :docs.publish` to publish it for public consumption, just like
an imaginary `bazel publish` command.

It's not immediately obvious what the implementation of the `_sphinx_publisher`
rule might look like. Often, actions like this write a _launcher_ shell script.
This method typically involves using
[`ctx.actions.expand_template`](lib/actions#expand_template)
to write a very simple shell script, in this case invoking the publisher binary
with a path to the output of the primary target. This way, the publisher
implementation can remain generic, the `_sphinx_site` rule can just produce
HTML, and this small script is all that's necessary to combine the two
together.

In `rules_k8s`, this is indeed what `.apply` does:
[`expand_template`](https://github.com/bazelbuild/rules_k8s/blob/f10e7025df7651f47a76abf1db5ade1ffeb0c6ac/k8s/object.bzl#L213-L241)
writes a very simple Bash script, based on
[`apply.sh.tpl`](https://github.com/bazelbuild/rules_k8s/blob/f10e7025df7651f47a76abf1db5ade1ffeb0c6ac/k8s/apply.sh.tpl),
which runs `kubectl` with the output of the primary target. This script can
then be build and run with `bazel run :staging.apply`, effectively providing a
`k8s-apply` command for `k8s_object` targets.
