Project: /_project.yaml
Book: /_book.yaml

# Automatic Execution Groups (AEGs)

{% include "_buttons.html" %}

Automatic execution groups select an [execution platform][exec_platform]
for each toolchain type. In other words, one target can have multiple
execution platforms without defining execution groups.

## Quick summary {:#quick-summary}

Automatic execution groups are closely connected to toolchains. If you are using
toolchains, you need to set them on the affected actions (actions which use an
executable or a tool from a toolchain) by adding `toolchain` parameter. For
example:

```python
ctx.actions.run(
    ...,
    executable = ctx.toolchain['@bazel_tools//tools/jdk:toolchain_type'].tool,
    ...,
    toolchain = '@bazel_tools//tools/jdk:toolchain_type',
)
```
If the action does not use a tool or executable from a toolchain, and Blaze
doesn't detect that ([the error](#first-error-message) is raised), you can set
`toolchain = None`.

If you need to use multiple toolchains on a single execution platform (an action
uses executable or tools from two or more toolchains), you need to manually
define [exec_groups][exec_groups] (check
[When should I use a custom exec_group?][multiple_toolchains_exec_groups]
section).

## History {:#history}

Before AEGs, the execution platform was selected on a rule level. For example:

```python
my_rule = rule(
    _impl,
    toolchains = ['//tools:toolchain_type_1', '//tools:toolchain_type_2'],
)
```

Rule `my_rule` registers two toolchain types. This means that the [Toolchain
Resolution](https://bazel.build/extending/toolchains#toolchain-resolution) used
to find an execution platform which supports both toolchain types. The selected
execution platform was used for each registered action inside the rule, unless
specified differently with [exec_groups][exec_groups].
In other words, all actions inside the rule used to have a single execution
platform even if they used tools from different toolchains (execution platform
is selected for each target). This resulted in failures when there was no
execution platform supporting all toolchains.

## Current state {:#current-state}

With AEGs, the execution platform is selected for each toolchain type. The
implementation function of the earlier example, `my_rule`, would look like:

```python
def _impl(ctx):
    ctx.actions.run(
      mnemonic = "First action",
      executable = ctx.toolchain['//tools:toolchain_type_1'].tool,
      toolchain = '//tools:toolchain_type_1',
    )

    ctx.actions.run(
      mnemonic = "Second action",
      executable = ctx.toolchain['//tools:toolchain_type_2'].tool,
      toolchain = '//tools:toolchain_type_2',
    )
```

This rule creates two actions, the `First action` which uses executable from a
`//tools:toolchain_type_1` and the `Second action` which uses executable from a
`//tools:toolchain_type_2`. Before AEGs, both of these actions would be executed
on a single execution platform which supports both toolchain types. With AEGs,
by adding the `toolchain` parameter inside the actions, each action executes on
the execution platform that provides the toolchain. The actions may be executed
on different execution platforms.

The same is effective with [ctx.actions.run_shell][run_shell] where `toolchain`
parameter should be added when `tools` are from a toolchain.

## Difference between custom exec groups and automatic exec groups {:#difference-custom}

As the name suggests, AEGs are exec groups created automatically for each
toolchain type registered on a rule. There is no need to manually specify them,
unlike the "classic" exec groups.

### When should I use a custom exec_group? {:#when-should-use-exec-groups}

Custom exec_groups are needed only in case where multiple toolchains need to
execute on a single execution platform. In all other cases there's no need to
define custom exec_groups. For example:

```python
def _impl(ctx):
    ctx.actions.run(
      ...,
      executable = ctx.toolchain['//tools:toolchain_type_1'].tool,
      tools = [ctx.toolchain['//tools:toolchain_type_2'].tool],
      exec_group = 'two_toolchains',
    )
```

```python
my_rule = rule(
    _impl,
    exec_groups = {
        "two_toolchains": exec_group(
            toolchains = ['//tools:toolchain_type_1', '//tools:toolchain_type_2'],
        ),
    }
)
```

## Migration of AEGs {:#migration-aegs}

Internally in google3, Blaze is already using AEGs.
Externally for Bazel, migration is in the process. Some rules are already using
this feature (e.g. Java and C++ rules).

### Which Bazel versions support this migration? {:#which-bazel}

AEGs are fully supported from Bazel 7.

### How to enable AEGs? {:#how-enable}

Set `--incompatible_auto_exec_groups` to true. More information about the flag
on [the GitHub issue][github_flag].

### How to enable AEGs inside a particular rule? {:#how-enable-particular-rule}

Set the `_use_auto_exec_groups` attribute on a rule.

```python
my_rule = rule(
    _impl,
    attrs = {
      "_use_auto_exec_groups": attr.bool(default = True),
    }
)
```
This enables AEGs only in `my_rule` and its actions start using the new logic
when selecting the execution platform. Incompatible flag is overridden with this
attribute.

### How to disable AEGs in case of an error? {:#how-disable}

Set `--incompatible_auto_exec_groups` to false to completely disable AEGs in
your project ([flag's GitHub issue][github_flag]), or disable a particular rule
by setting `_use_auto_exec_groups` attribute to `False`
([more details about the attribute](#how-enable-particular-rule)).

### Error messages while migrating to AEGs {:#potential-problems}

#### Couldn't identify if tools are from implicit dependencies or a toolchain. Please set the toolchain parameter. If you're not using a toolchain, set it to 'None'. {:#first-error-message}
  * In this case you get a stack of calls before the error happened and you can
    clearly see which exact action needs the toolchain parameter. Check which
    toolchain is used for the action and set it with the toolchain param. If no
    toolchain is used inside the action for tools or executable, set it to
    `None`.

#### Action declared for non-existent toolchain '[toolchain_type]'.
  * This means that you've set the toolchain parameter on the action but didn't
register it on the rule. Register the toolchain or set `None` inside the action.

## Additional material {:#additional-material}

For more information, check design document:
[Automatic exec groups for toolchains][aegs_design_doc].

[exec_platform]: https://bazel.build/extending/platforms#:~:text=Execution%20%2D%20a%20platform%20on%20which%20build%20tools%20execute%20build%20actions%20to%20produce%20intermediate%20and%20final%20outputs.
[exec_groups]: https://bazel.build/extending/exec-groups
[github_flag]: https://github.com/bazelbuild/bazel/issues/17134
[aegs_design_doc]: https://docs.google.com/document/d/1-rbP_hmKs9D639YWw5F_JyxPxL2bi6dSmmvj_WXak9M/edit#heading=h.5mcn15i0e1ch
[run_shell]: https://bazel.build/rules/lib/builtins/actions#run_shell
[multiple_toolchains_exec_groups]: /extending/auto-exec-groups#when-should-use-exec-groups