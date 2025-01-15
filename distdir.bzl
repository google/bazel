# Copyright 2018 The Bazel Authors. All rights reserved.
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
"""Defines a repository rule that generates an archive consisting of the specified files to fetch"""

load("//src/tools/bzlmod:utils.bzl", "parse_http_artifacts", "parse_registry_files")

_BUILD = """
load("@rules_pkg//pkg:tar.bzl", "pkg_tar")

filegroup(
  name="files",
  srcs = {srcs},
  visibility = ["//visibility:public"],
)

pkg_tar(
  name="archives",
  srcs = [":files"],
  strip_prefix = "{strip_prefix}",
  package_dir = "{dirname}",
  visibility = ["//visibility:public"],
)

"""

def _distdir_tar_impl(ctx):
    for name in ctx.attr.archives:
        ctx.download(ctx.attr.urls[name], name, ctx.attr.sha256[name], False)
    ctx.file("WORKSPACE", "")
    ctx.file(
        "BUILD",
        _BUILD.format(srcs = ctx.attr.archives, strip_prefix = "", dirname = ctx.attr.dirname),
    )

_distdir_tar_attrs = {
    "archives": attr.string_list(),
    "sha256": attr.string_dict(),
    "urls": attr.string_list_dict(),
    "dirname": attr.string(default = "distdir"),
}

_distdir_tar = repository_rule(
    implementation = _distdir_tar_impl,
    attrs = _distdir_tar_attrs,
)

def distdir_tar(name, dist_deps):
    """Creates a repository whose content is a set of tar files.

    Args:
      name: repo name.
      dist_deps: map of repo names to dict of archive, sha256, and urls.
    """
    archives = []
    sha256 = {}
    urls = {}
    for _, info in dist_deps.items():
        archive_file = info["archive"]
        archives.append(archive_file)
        sha256[archive_file] = info["sha256"]
        urls[archive_file] = info["urls"]
    _distdir_tar(
        name = name,
        archives = archives,
        sha256 = sha256,
        urls = urls,
    )

def _repo_cache_tar_impl(ctx):
    """Generate a repository cache as a tar file.

    This repository rule does the following:
        1. parse all http artifacts required for generating the given list of repositories from the lock file.
        2. downloads all http artifacts to create a repository cache directory structure.
        3. creates a pkg_tar target which packages the repository cache directory structure.
    """
    lockfile_path = ctx.path(ctx.attr.lockfile)
    http_artifacts = parse_http_artifacts(ctx, lockfile_path, ctx.attr.repos)
    registry_files = parse_registry_files(ctx, lockfile_path, ctx.attr.module_files)

    archive_files = []
    readme_content = "This directory contains repository cache artifacts for the following URLs:\n\n"
    for artifact in http_artifacts + registry_files:
        url = artifact["url"]
        if "integrity" in artifact:
            # ./tempfile could be a hard link if --experimental_repository_cache_hardlinks is used,
            # therefore we must delete it before creating or writing it again.
            ctx.delete("./tempfile")
            checksum = ctx.download(url, "./tempfile", executable = False, integrity = artifact["integrity"])
            artifact["sha256"] = checksum.sha256

        if "sha256" in artifact:
            sha256 = artifact["sha256"]
            if sha256 == "not found" and "localhost:8000" in url:
                continue
            output_file = "content_addressable/sha256/%s/file" % sha256
            ctx.download(url, output_file, sha256, executable = False)
            archive_files.append(output_file)
            readme_content += "- %s (SHA256: %s)\n" % (url, sha256)
        else:
            fail("Could not find integrity or sha256 hash for artifact %s" % url)

    ctx.file("README.md", readme_content)
    ctx.file(
        "BUILD",
        _BUILD.format(
            srcs = archive_files + ["README.md"],
            strip_prefix = "external/" + ctx.attr.name,
            dirname = ctx.attr.dirname,
        ),
    )

_repo_cache_tar_attrs = {
    "lockfile": attr.label(default = Label("//:MODULE.bazel.lock")),
    "dirname": attr.string(default = "repository_cache"),
    "repos": attr.string_list(),
    "module_files": attr.label_list(),
}

repo_cache_tar = repository_rule(
    implementation = _repo_cache_tar_impl,
    attrs = _repo_cache_tar_attrs,
)
