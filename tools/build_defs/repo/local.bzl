# Copyright 2024 The Bazel Authors. All rights reserved.
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

# WARNING:
# https://github.com/bazelbuild/bazel/issues/17713
# .bzl files in this package (tools/build_defs/repo) are evaluated
# in a Starlark environment without "@_builtins" injection, and must not refer
# to symbols associated with build/workspace .bzl files

"""Rules for making directories in the local filesystem available as repos.

### Setup

To use these rules in a module extension, load them in your .bzl file and then call them from your
extension's implementation function. For example, to use `local_repository`:

```python
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")

def _my_extension_impl(mctx):
  local_repository(name = "foo", path = "foo")

my_extension = module_extension(implementation = _my_extension_impl)
```

Alternatively, you can directly call these repo rules in your MODULE.bazel file with
`use_repo_rule`:

```python
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "foo", path = "foo")
```
"""

# The default value for the string attr `build_file_content`. String attrs default to the empty
# string by default, but we need another default because the empty string is perfectly valid build
# file content and we need to know whether the attribute is actually set.
_UNSET = "_UNSET"

def _get_dir_path(rctx):
    """Turns the string attr `path` into a path object, ensuring that it's a directory."""
    path = rctx.workspace_root.get_child(rctx.attr.path)
    if not path.is_dir:
        fail(
            ("The repository's path is \"%s\" (absolute: \"%s\") but it does not exist or is not " +
             "a directory.") % (rctx.attr.path, path),
        )
    return path

def _local_repository_impl(rctx):
    rctx.symlink(_get_dir_path(rctx), ".")

local_repository = repository_rule(
    implementation = _local_repository_impl,
    attrs = {
        "path": attr.string(
            doc =
                "The path to the directory to make available as a repo.\n\nThe path can be " +
                "either absolute, or relative to the workspace root.",
            mandatory = True,
        ),
    },
    doc =
        "Makes a local directory that already contains Bazel files available as a repo. This " +
        "directory should contain Bazel BUILD files and a repo boundary file already. If it " +
        "doesn't contain these files, consider using " +
        "[`new_local_repository`](#new_local_repository) instead.",
    local = True,
)

def _new_local_repository_impl(rctx):
    if (rctx.attr.build_file == None) == (rctx.attr.build_file_content == _UNSET):
        fail("exactly one of `build_file` and `build_file_content` must be specified")

    children = _get_dir_path(rctx).readdir()
    for child in children:
        rctx.symlink(child, child.basename)

        # On Windows, `rctx.symlink` actually does a copy for files (for directories, it uses
        # junctions which basically behave like symlinks as far as we're concerned). So we need to
        # watch the symlink target as well.
        if rctx.os.name.startswith("windows") and not child.is_dir:
            rctx.watch(child)

    if rctx.attr.build_file != None:
        # Remove any existing BUILD.bazel in the repository to ensure
        # the symlink to the defined build_file doesn't fail.
        rctx.delete("BUILD.bazel")
        rctx.symlink(rctx.attr.build_file, "BUILD.bazel")
        if rctx.os.name.startswith("windows"):
            rctx.watch(rctx.attr.build_file)  # same reason as above
    else:
        rctx.file("BUILD.bazel", rctx.attr.build_file_content)

new_local_repository = repository_rule(
    implementation = _new_local_repository_impl,
    attrs = {
        "path": attr.string(
            doc =
                "The path to the directory to make available as a repo.\n\nThe path can be " +
                "either absolute, or relative to the workspace root.",
            mandatory = True,
        ),
        "build_file": attr.label(
            doc =
                "A file to use as a BUILD file for this repo.\n\nExactly one of `build_file` and " +
                "`build_file_content` must be specified.\n\nThe file addressed by this label " +
                "does not need to be named BUILD, but can be. Something like " +
                "`BUILD.new-repo-name` may work well to distinguish it from actual BUILD files.",
        ),
        "build_file_content": attr.string(
            doc =
                "The content of the BUILD file to be created for this repo.\n\nExactly one of " +
                "`build_file` and `build_file_content` must be specified.",
            default = _UNSET,
        ),
    },
    doc =
        "Makes a local directory that doesn't contain Bazel files available as a repo. This " +
        "directory need not contain Bazel BUILD files or a repo boundary file; they will be " +
        "created by this repo rule. If the directory already contains Bazel files, consider " +
        "using [`local_repository`](#local_repository) instead.",
    local = True,
)
