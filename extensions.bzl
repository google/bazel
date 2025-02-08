# Copyright 2023 The Bazel Authors. All rights reserved.
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

"""Module extensions for loading dependencies we need to build Bazel.

"""

load("//:distdir.bzl", "distdir_tar", "repo_cache_tar")
load("//:repositories.bzl", "DIST_ARCHIVE_REPOS", "embedded_jdk_repositories")
load("//:workspace_deps.bzl", "WORKSPACE_REPOS")
load("//src/tools/bzlmod:utils.bzl", "parse_bazel_module_repos")
load("//tools/distributions/debian:deps.bzl", "debian_deps")

### Dependencies for building Bazel
def _bazel_build_deps(ctx):
    ctx.path(Label("//:MODULE.bazel"))  # Make sure the `bootstrap_repo_cache` repo is updated when MODULE.bazel changes.
    embedded_jdk_repositories()
    debian_deps()
    repo_cache_tar(
        name = "bootstrap_repo_cache",
        repos = DIST_ARCHIVE_REPOS,
        dirname = "derived/repository_cache",
        module_files = [
            "//:MODULE.bazel",
            "//third_party/remoteapis:MODULE.bazel",
            "//src:MODULE.tools",
        ],
    )
    BAZEL_TOOLS_DEPS_REPOS = parse_bazel_module_repos(ctx, ctx.path(Label("//src/test/tools/bzlmod:MODULE.bazel.lock")))
    repo_cache_tar(name = "bazel_tools_repo_cache", repos = BAZEL_TOOLS_DEPS_REPOS, lockfile = "//src/test/tools/bzlmod:MODULE.bazel.lock")
    distdir_tar(name = "workspace_repo_cache", dist_deps = WORKSPACE_REPOS)
    return ctx.extension_metadata(reproducible = True)

bazel_build_deps = module_extension(implementation = _bazel_build_deps)
