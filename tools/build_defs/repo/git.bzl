# Copyright 2015 The Bazel Authors. All rights reserved.
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

"""Rules for cloning external git repositories."""

load(":git_worker.bzl", "git_repo")
load(
    ":utils.bzl",
    "patch",
    "update_attrs",
    "workspace_and_buildfile",
)

def _clone_or_update_repo(ctx):
    if ((not ctx.attr.tag and not ctx.attr.commit and not ctx.attr.branch) or
        (ctx.attr.tag and ctx.attr.commit) or
        (ctx.attr.tag and ctx.attr.branch) or
        (ctx.attr.commit and ctx.attr.branch)):
        fail("Exactly one of commit, tag, or branch must be provided")

    root = ctx.path(".")
    directory = str(root)
    if ctx.attr.strip_prefix:
        directory = root.get_child(".tmp_git_root")

    git_ = git_repo(ctx, directory)

    if ctx.attr.strip_prefix:
        dest_link = "{}/{}".format(directory, ctx.attr.strip_prefix)
        if not ctx.path(dest_link).exists:
            fail("strip_prefix at {} does not exist in repo".format(ctx.attr.strip_prefix))
        for item in ctx.path(dest_link).readdir():
            ctx.symlink(item, root.get_child(item.basename))

    if ctx.attr.shallow_since:
        return {"commit": git_.commit, "shallow_since": git_.shallow_since}
    else:
        return {"commit": git_.commit}

def _update_git_attrs(orig, keys, override):
    result = update_attrs(orig, keys, override)

    # if we found the actual commit, remove all other means of specifying it,
    # like tag or branch.
    if "commit" in result:
        result.pop("tag", None)
        result.pop("branch", None)
    return result

_common_attrs = {
    "remote": attr.string(
        mandatory = True,
        doc = "The URI of the remote Git repository",
    ),
    "commit": attr.string(
        default = "",
        doc =
            "specific commit to be checked out." +
            " Precisely one of branch, tag, or commit must be specified.",
    ),
    "shallow_since": attr.string(
        default = "",
        doc =
            "an optional date, not after the specified commit; the argument " +
            "is not allowed if a tag or branch is specified (which can " +
            "always be cloned with --depth=1). Setting such a date close to " +
            "the specified commit may allow for a shallow clone of the " +
            "repository even if the server does not support shallow fetches " +
            "of arbitrary commits. Due to bugs in git's --shallow-since " +
            "implementation, using this attribute is not recommended as it " +
            "may result in fetch failures.",
    ),
    "tag": attr.string(
        default = "",
        doc =
            "tag in the remote repository to checked out." +
            " Precisely one of branch, tag, or commit must be specified.",
    ),
    "branch": attr.string(
        default = "",
        doc =
            "branch in the remote repository to checked out." +
            " Precisely one of branch, tag, or commit must be specified.",
    ),
    "init_submodules": attr.bool(
        default = False,
        doc = "Whether to clone submodules in the repository.",
    ),
    "recursive_init_submodules": attr.bool(
        default = False,
        doc = "Whether to clone submodules recursively in the repository.",
    ),
    "verbose": attr.bool(default = False),
    "strip_prefix": attr.string(
        default = "",
        doc = "A directory prefix to strip from the extracted files.",
    ),
    "patches": attr.label_list(
        default = [],
        doc =
            "A list of files that are to be applied as patches after " +
            "extracting the archive. By default, it uses the Bazel-native patch implementation " +
            "which doesn't support fuzz match and binary patch, but Bazel will fall back to use " +
            "patch command line tool if `patch_tool` attribute is specified or there are " +
            "arguments other than `-p` in `patch_args` attribute.",
    ),
    "patch_tool": attr.string(
        default = "",
        doc = "The patch(1) utility to use. If this is specified, Bazel will use the specified " +
              "patch tool instead of the Bazel-native patch implementation.",
    ),
    "patch_args": attr.string_list(
        default = [],
        doc =
            "The arguments given to the patch tool. Defaults to -p0 (see the `patch_strip` " +
            "attribute), however -p1 will usually be needed for patches generated by " +
            "git. If multiple -p arguments are specified, the last one will take effect." +
            "If arguments other than -p are specified, Bazel will fall back to use patch " +
            "command line tool instead of the Bazel-native patch implementation. When falling " +
            "back to patch command line tool and patch_tool attribute is not specified, " +
            "`patch` will be used.",
    ),
    "patch_strip": attr.int(
        default = 0,
        doc = "When set to `N`, this is equivalent to inserting `-pN` to the beginning of `patch_args`.",
    ),
    "patch_cmds": attr.string_list(
        default = [],
        doc = "Sequence of Bash commands to be applied on Linux/Macos after patches are applied.",
    ),
    "patch_cmds_win": attr.string_list(
        default = [],
        doc = "Sequence of Powershell commands to be applied on Windows after patches are " +
              "applied. If this attribute is not set, patch_cmds will be executed on Windows, " +
              "which requires Bash binary to exist.",
    ),
    "build_file": attr.label(
        allow_single_file = True,
        doc =
            "The file to use as the BUILD file for this repository." +
            "This attribute is an absolute label (use '@//' for the main " +
            "repo). The file does not need to be named BUILD, but can " +
            "be (something like BUILD.new-repo-name may work well for " +
            "distinguishing it from the repository's actual BUILD files. ",
    ),
    "build_file_content": attr.string(
        doc =
            "The content for the BUILD file for this repository. ",
    ),
    "workspace_file": attr.label(
        doc =
            "The file to use as the `WORKSPACE` file for this repository. " +
            "Either `workspace_file` or `workspace_file_content` can be " +
            "specified, or neither, but not both.",
    ),
    "workspace_file_content": attr.string(
        doc =
            "The content for the WORKSPACE file for this repository. " +
            "Either `workspace_file` or `workspace_file_content` can be " +
            "specified, or neither, but not both.",
    ),
}

def _git_repository_implementation(ctx):
    if ctx.attr.build_file and ctx.attr.build_file_content:
        fail("Only one of build_file and build_file_content can be provided.")
    update = _clone_or_update_repo(ctx)
    workspace_and_buildfile(ctx)
    patch(ctx)
    if ctx.attr.strip_prefix:
        ctx.delete(ctx.path(".tmp_git_root/.git"))
    else:
        ctx.delete(ctx.path(".git"))
    return _update_git_attrs(ctx.attr, _common_attrs.keys(), update)

git_repository = repository_rule(
    implementation = _git_repository_implementation,
    attrs = _common_attrs,
    doc = """Clone an external git repository.

Clones a Git repository, checks out the specified tag, or commit, and
makes its targets available for binding. Also determine the id of the
commit actually checked out and its date, and return a dict with parameters
that provide a reproducible version of this rule (which a tag not necessarily
is).

Bazel will first try to perform a shallow fetch of only the specified commit.
If that fails (usually due to missing server support), it will fall back to a
full fetch of the repository.

Prefer [`http_archive`](/rules/lib/repo/http#http_archive) to `git_repository`.
The reasons are:

* Git repository rules depend on system `git(1)` whereas the HTTP downloader is built
  into Bazel and has no system dependencies.
* `http_archive` supports a list of `urls` as mirrors, and `git_repository` supports only
  a single `remote`.
* `http_archive` works with the [repository cache](/run/build#repository-cache), but not
  `git_repository`. See
   [#5116](https://github.com/bazelbuild/bazel/issues/5116){: .external} for more information.
""",
)

# TODO(bazel_7.x): Remove before bazel 7.x
new_git_repository = git_repository
