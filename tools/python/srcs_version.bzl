# Copyright 2019 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Defines an aspect for finding constraints on the Python version."""

load("@rules_python//python:py_info.bzl", "PyInfo")

_PY2 = "PY2"
_PY3 = "PY3"

_TransitiveVersionInfo = provider(
    doc = """\
Propagates information about the Python version constraints of transitive
dependencies.

Canonically speaking, a target is considered to be PY2-only if it returns the
`py` provider with the `has_py2_only_sources` field set to `True`. Likewise, it
is PY3-only if `has_py3_only_sources` is `True`. Unless something weird is going
on with how the transitive sources are aggregated, it is expected that if any
target is PY2-only or PY3-only, then so are all of its reverse transitive deps.

The `py_library` rule becomes PY2-only or PY3-only when its `srcs_version`
attribute is respectively set to `PY2ONLY` or to either `PY3` or `PY3ONLY`.
(The asymmetry of not recongizing `PY2` is due to
[#1393](https://github.com/bazelbuild/bazel/issues/1393) and will be moot once
the `PY2ONLY` and `PY3ONLY` names are retired.) Therefore, if the transitive
deps of the root target are all `py_library` targets, we can look at the
`srcs_version` attribute to easily distinguish targets whose own sources
require a given Python version, from targets that only require it due to their
transitive deps.

If on the other hand there are other rule types in the transitive deps that do
not define `srcs_version`, then the only general way to tell that a dep
introduces a requirement on Python 2 or 3 is if it returns true in the
corresponding provider field and none of its direct dependencies returns true
in that field.

This `_TransitiveVersionInfo` provider reports transitive deps that satisfy
either of these criteria. But of those deps, it only reports those that are
"top-most" in relation to the root. The top-most deps are the ones that are
reachable from the root target by a path that does not involve any other
top-most dep (though it's possible for one top-most dep to have a separate path
to another). Reporting only the top-most deps ensures that we give the minimal
information needed to understand how the root target depends on PY2-only or
PY3-only targets.
""",
    fields = {
        "py2": """\
A `_DepsWithPathsInfo` object for transitive deps that are known to introduce a
PY2-only requirement.
""",
        "py3": """\
A `_DepsWithPathsInfo` object for transitive deps that are known to introduce a
PY3-only requirement.
""",
    },
)

_DepsWithPathsInfo = provider(
    fields = {
        "topmost": """\
A list of labels of all top-most transitive deps known to introduce a version
requirement. The deps appear in left-to-right order.
""",
        "paths": """\
A dictionary that maps labels appearing in `topmost` to their paths from the
root. Paths are represented as depsets with `preorder` order.
""",
        # It is technically possible for the depset keys to collide if the same
        # target appears multiple times in the build graph as different
        # configured targets, but this seems unlikely.
    },
)

def _join_lines(nodes):
    return "\n".join([str(n) for n in nodes]) if nodes else "<None>"

def _str_path(path):
    return " -> ".join([str(p) for p in path.to_list()])

def _str_tv_info(tv_info):
    """Returns a string representation of a `_TransitiveVersionInfo`."""
    path_lines = []
    path_lines.extend([_str_path(tv_info.py2.paths[n]) for n in tv_info.py2.topmost])
    path_lines.extend([_str_path(tv_info.py3.paths[n]) for n in tv_info.py3.topmost])
    return """\
Python 2-only deps:
{py2_nodes}

Python 3-only deps:
{py3_nodes}

Paths to these deps:
{paths}
""".format(
        py2_nodes = _join_lines(tv_info.py2.topmost),
        py3_nodes = _join_lines(tv_info.py3.topmost),
        paths = _join_lines(path_lines),
    )

def _has_version_requirement(target, version):
    """Returns whether a target has a version requirement, as per its provider.

    Args:
        target: the `Target` object to check
        version: either the string "PY2" or "PY3"

    Returns:
        `True` if `target` requires `version` according to the
        `has_py<?>_only_sources` fields
    """
    if version not in [_PY2, _PY3]:
        fail("Unrecognized version '%s'; must be 'PY2' or 'PY3'" % version)
    field = {
        _PY2: "has_py2_only_sources",
        _PY3: "has_py3_only_sources",
    }[version]

    if not PyInfo in target:
        return False
    field_value = getattr(target[PyInfo], field, False)
    if not type(field_value) == "bool":
        fail("Invalid type for provider field '%s': %r" % (field, field_value))
    return field_value

def _introduces_version_requirement(target, target_attr, version):
    """Returns whether a target introduces a PY2-only or PY3-only requirement.

    A target that has a version requirement is considered to introduce this
    requirement if either 1) its rule type has a `srcs_version` attribute and
    the target sets it to `PY2ONLY` (PY2), or `PY3` or `PY3ONLY` (PY3); or 2)
    none of its direct dependencies set `has_py2_only_sources` (PY2) or
    `has_py3_only_sources` (PY3) to `True`. A target that does not actually have
    the version requirement is never considered to introduce the requirement.

    Args:
        target: the `Target` object as passed to the aspect implementation
            function
        target_attr: the attribute struct as retrieved from `ctx.rule.attr` in
            the aspect implementation function
        version: either the string "PY2" or "PY3" indicating which constraint
            to test for

    Returns:
        `True` if `target` introduces the requirement on `version`, as per the
        above definition
    """
    if version not in [_PY2, _PY3]:
        fail("Unrecognized version '%s'; must be 'PY2' or 'PY3'" % version)

    # If we don't actually have the version requirement, we can't possibly
    # introduce it, regardless of our srcs_version or what our dependencies
    # return.
    if not _has_version_requirement(target, version):
        return False

    # Try the attribute, if present.
    if hasattr(target_attr, "srcs_version"):
        sv = target_attr.srcs_version
        if version == _PY2:
            if sv == "PY2ONLY":
                return True
        elif version == _PY3:
            if sv in ["PY3", "PY3ONLY"]:
                return True
        else:
            fail("Illegal state")

    # No good, check the direct deps' provider fields.
    if not hasattr(target_attr, "deps"):
        return True
    else:
        return not any([
            _has_version_requirement(dep, version)
            for dep in target_attr.deps
        ])

def _empty_depswithpaths():
    """Initializes an empty `_DepsWithPathsInfo` object."""
    return _DepsWithPathsInfo(topmost = [], paths = {})

def _init_depswithpaths_for_node(node):
    """Initialize a new `_DepsWithPathsInfo` object.

    The object will record just the given node as its sole entry.

    Args:
        node: a label

    Returns:
        a `_DepsWithPathsInfo` object
    """
    return _DepsWithPathsInfo(
        topmost = [node],
        paths = {node: depset(direct = [node], order = "preorder")},
    )

def _merge_depswithpaths_appending_node(depswithpaths, node_to_append):
    """Merge several `_DepsWithPathsInfo` objects and appends a path entry.

    Args:
        depswithpaths: a list of `_DepsWithPathsInfo` objects whose entries are
            to be merged
        node_to_append: a label to append to all the paths of the merged object

    Returns:
        a `_DepsWithPathsInfo` object
    """
    seen = {}
    topmost = []
    paths = {}
    for dwp in depswithpaths:
        for node in dwp.topmost:
            if node in seen:
                continue
            seen[node] = True

            topmost.append(node)
            path = dwp.paths[node]
            path = depset(
                direct = [node_to_append],
                transitive = [path],
                order = "preorder",
            )
            paths[node] = path
    return _DepsWithPathsInfo(topmost = topmost, paths = paths)

def _find_requirements_impl(target, ctx):
    # Determine whether this target introduces a requirement. If so, any deps
    # that introduce that requirement are not propagated, though they might
    # still be considered top-most if an alternate path exists.
    if not hasattr(ctx.rule.attr, "deps"):
        dep_tv_infos = []
    else:
        dep_tv_infos = [
            d[_TransitiveVersionInfo]
            for d in ctx.rule.attr.deps
            if _TransitiveVersionInfo in d
        ]

    if not _has_version_requirement(target, "PY2"):
        new_py2 = _empty_depswithpaths()
    elif _introduces_version_requirement(target, ctx.rule.attr, "PY2"):
        new_py2 = _init_depswithpaths_for_node(target.label)
    else:
        new_py2 = _merge_depswithpaths_appending_node(
            [i.py2 for i in dep_tv_infos],
            target.label,
        )

    if not _has_version_requirement(target, "PY3"):
        new_py3 = _empty_depswithpaths()
    elif _introduces_version_requirement(target, ctx.rule.attr, "PY3"):
        new_py3 = _init_depswithpaths_for_node(target.label)
    else:
        new_py3 = _merge_depswithpaths_appending_node(
            [i.py3 for i in dep_tv_infos],
            target.label,
        )

    tv_info = _TransitiveVersionInfo(py2 = new_py2, py3 = new_py3)

    output = ctx.actions.declare_file(target.label.name + "-pyversioninfo.txt")
    ctx.actions.write(output = output, content = _str_tv_info(tv_info))

    return [tv_info, OutputGroupInfo(pyversioninfo = depset(direct = [output]))]

find_requirements = aspect(
    implementation = _find_requirements_impl,
    attr_aspects = ["deps"],
    doc = """\
The aspect definition. Can be invoked on the command line as

    bazel build //pkg:my_py_binary_target \
        --aspects=@rules_python//python:defs.bzl%find_requirements \
        --output_groups=pyversioninfo
""",
)

def _apply_find_requirements_for_testing_impl(ctx):
    tv_info = ctx.attr.target[_TransitiveVersionInfo]
    ctx.actions.write(output = ctx.outputs.out, content = _str_tv_info(tv_info))

apply_find_requirements_for_testing = rule(
    implementation = _apply_find_requirements_for_testing_impl,
    attrs = {
        "target": attr.label(aspects = [find_requirements]),
        "out": attr.output(),
    },
    doc = """\
Writes the string output of `find_requirements` to a file.

This helper exists for the benefit of PythonSrcsVersionAspectTest.java. It is
useful because code outside this file cannot read the private
`_TransitiveVersionInfo` provider, and `BuildViewTestCase` cannot easily access
actions generated by an aspect.
""",
)
