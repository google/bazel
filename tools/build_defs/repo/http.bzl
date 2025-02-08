# Copyright 2016 The Bazel Authors. All rights reserved.
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

"""Rules for downloading files and archives over HTTP.

### Setup

To use these rules in a module extension, load them in your .bzl file and then call them from your
extension's implementation function. For example, to use `http_archive`:

```python
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def _my_extension_impl(mctx):
  http_archive(name = "foo", urls = [...])

my_extension = module_extension(implementation = _my_extension_impl)
```

Alternatively, you can directly call these repo rules in your MODULE.bazel file with
`use_repo_rule`:

```python
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(name = "foo", urls = [...])
```
"""

load(
    ":cache.bzl",
    "CANONICAL_ID_DOC",
    "DEFAULT_CANONICAL_ID_ENV",
    "get_default_canonical_id",
)
load(
    ":utils.bzl",
    "download_remote_files",
    "get_auth",
    "patch",
    "update_attrs",
    "workspace_and_buildfile",
)

# Shared between http_jar, http_file and http_archive.

_URL_DOC = """A URL to a file that will be made available to Bazel.

This must be a file, http or https URL. Redirections are followed.
Authentication is not supported.

More flexibility can be achieved by the urls parameter that allows
to specify alternative URLs to fetch from."""

_URLS_DOC = """A list of URLs to a file that will be made available to Bazel.

Each entry must be a file, http or https URL. Redirections are followed.
Authentication is not supported.

URLs are tried in order until one succeeds, so you should list local mirrors first.
If all downloads fail, the rule will fail."""

def _get_source_urls(ctx):
    """Returns source urls provided via the url, urls attributes.

    Also checks that at least one url is provided."""
    if not ctx.attr.url and not ctx.attr.urls:
        fail("At least one of url and urls must be provided")

    source_urls = []
    if ctx.attr.urls:
        source_urls = ctx.attr.urls
    if ctx.attr.url:
        source_urls = [ctx.attr.url] + source_urls
    return source_urls

_AUTH_PATTERN_DOC = """An optional dict mapping host names to custom authorization patterns.

If a URL's host name is present in this dict the value will be used as a pattern when
generating the authorization header for the http request. This enables the use of custom
authorization schemes used in a lot of common cloud storage providers.

The pattern currently supports 2 tokens: <code>&lt;login&gt;</code> and
<code>&lt;password&gt;</code>, which are replaced with their equivalent value
in the netrc file for the same host name. After formatting, the result is set
as the value for the <code>Authorization</code> field of the HTTP request.

Example attribute and netrc for a http download to an oauth2 enabled API using a bearer token:

<pre>
auth_patterns = {
    "storage.cloudprovider.com": "Bearer &lt;password&gt;"
}
</pre>

netrc:

<pre>
machine storage.cloudprovider.com
        password RANDOM-TOKEN
</pre>

The final HTTP request would have the following header:

<pre>
Authorization: Bearer RANDOM-TOKEN
</pre>
"""

def _update_integrity_attr(ctx, attrs, download_info):
    # We don't need to override the integrity attribute if sha256 is already specified.
    integrity_override = {} if ctx.attr.sha256 else {"integrity": download_info.integrity}
    return update_attrs(ctx.attr, attrs.keys(), integrity_override)

def _http_archive_impl(ctx):
    """Implementation of the http_archive rule."""
    if ctx.attr.build_file and ctx.attr.build_file_content:
        fail("Only one of build_file and build_file_content can be provided.")

    source_urls = _get_source_urls(ctx)
    download_info = ctx.download_and_extract(
        source_urls,
        ctx.attr.add_prefix,
        ctx.attr.sha256,
        ctx.attr.type,
        ctx.attr.strip_prefix,
        canonical_id = ctx.attr.canonical_id or get_default_canonical_id(ctx, source_urls),
        auth = get_auth(ctx, source_urls),
        integrity = ctx.attr.integrity,
    )
    workspace_and_buildfile(ctx)

    download_remote_files(ctx)
    patch(ctx)

    return _update_integrity_attr(ctx, _http_archive_attrs, download_info)

_HTTP_FILE_BUILD = """\
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "file",
    srcs = ["{}"],
)
"""

def _http_file_impl(ctx):
    """Implementation of the http_file rule."""
    repo_root = ctx.path(".")
    forbidden_files = [
        repo_root,
        ctx.path("WORKSPACE"),
        ctx.path("BUILD"),
        ctx.path("BUILD.bazel"),
        ctx.path("file/BUILD"),
        ctx.path("file/BUILD.bazel"),
    ]
    downloaded_file_path = ctx.attr.downloaded_file_path
    download_path = ctx.path("file/" + downloaded_file_path)
    if download_path in forbidden_files or not str(download_path).startswith(str(repo_root)):
        fail("'%s' cannot be used as downloaded_file_path in http_file" % ctx.attr.downloaded_file_path)
    source_urls = _get_source_urls(ctx)
    download_info = ctx.download(
        source_urls,
        "file/" + downloaded_file_path,
        ctx.attr.sha256,
        ctx.attr.executable,
        canonical_id = ctx.attr.canonical_id or get_default_canonical_id(ctx, source_urls),
        auth = get_auth(ctx, source_urls),
        integrity = ctx.attr.integrity,
    )
    ctx.file("WORKSPACE", "workspace(name = \"{name}\")".format(name = ctx.name))
    ctx.file("file/BUILD", _HTTP_FILE_BUILD.format(downloaded_file_path))

    return _update_integrity_attr(ctx, _http_file_attrs, download_info)

_HTTP_JAR_BUILD = """\
package(default_visibility = ["//visibility:public"])

java_import(
  name = 'jar',
  jars = ["{file_name}"],
  visibility = ['//visibility:public'],
)

filegroup(
  name = 'file',
  srcs = ["{file_name}"],
  visibility = ['//visibility:public'],
)

"""

def _http_jar_impl(ctx):
    """Implementation of the http_jar rule."""
    source_urls = _get_source_urls(ctx)
    downloaded_file_name = ctx.attr.downloaded_file_name
    download_info = ctx.download(
        source_urls,
        "jar/" + downloaded_file_name,
        ctx.attr.sha256,
        canonical_id = ctx.attr.canonical_id or get_default_canonical_id(ctx, source_urls),
        auth = get_auth(ctx, source_urls),
        integrity = ctx.attr.integrity,
    )
    ctx.file("WORKSPACE", "workspace(name = \"{name}\")".format(name = ctx.name))
    ctx.file("jar/BUILD", _HTTP_JAR_BUILD.format(
        file_name = downloaded_file_name,
    ))

    return _update_integrity_attr(ctx, _http_jar_attrs, download_info)

_http_archive_attrs = {
    "url": attr.string(doc = _URL_DOC),
    "urls": attr.string_list(doc = _URLS_DOC),
    "sha256": attr.string(
        doc = """The expected SHA-256 of the file downloaded.

This must match the SHA-256 of the file downloaded. _It is a security risk
to omit the SHA-256 as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but either this attribute or `integrity` should be set before shipping.""",
    ),
    "integrity": attr.string(
        doc = """Expected checksum in Subresource Integrity format of the file downloaded.

This must match the checksum of the file downloaded. _It is a security risk
to omit the checksum as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but either this attribute or `sha256` should be set before shipping.""",
    ),
    "netrc": attr.string(
        doc = "Location of the .netrc file to use for authentication",
    ),
    "auth_patterns": attr.string_dict(
        doc = _AUTH_PATTERN_DOC,
    ),
    "canonical_id": attr.string(
        doc = CANONICAL_ID_DOC,
    ),
    "strip_prefix": attr.string(
        doc = """A directory prefix to strip from the extracted files.

Many archives contain a top-level directory that contains all of the useful
files in archive. Instead of needing to specify this prefix over and over
in the `build_file`, this field can be used to strip it from all of the
extracted files.

For example, suppose you are using `foo-lib-latest.zip`, which contains the
directory `foo-lib-1.2.3/` under which there is a `WORKSPACE` file and are
`src/`, `lib/`, and `test/` directories that contain the actual code you
wish to build. Specify `strip_prefix = "foo-lib-1.2.3"` to use the
`foo-lib-1.2.3` directory as your top-level directory.

Note that if there are files outside of this directory, they will be
discarded and inaccessible (e.g., a top-level license file). This includes
files/directories that start with the prefix but are not in the directory
(e.g., `foo-lib-1.2.3.release-notes`). If the specified prefix does not
match a directory in the archive, Bazel will return an error.""",
    ),
    "add_prefix": attr.string(
        default = "",
        doc = """Destination directory relative to the repository directory.

The archive will be unpacked into this directory, after applying `strip_prefix`
(if any) to the file paths within the archive. For example, file
`foo-1.2.3/src/foo.h` will be unpacked to `bar/src/foo.h` if `add_prefix = "bar"`
and `strip_prefix = "foo-1.2.3"`.""",
    ),
    "type": attr.string(
        doc = """The archive type of the downloaded file.

By default, the archive type is determined from the file extension of the
URL. If the file has no extension, you can explicitly specify one of the
following: `"zip"`, `"jar"`, `"war"`, `"aar"`, `"tar"`, `"tar.gz"`, `"tgz"`,
`"tar.xz"`, `"txz"`, `"tar.zst"`, `"tzst"`, `"tar.bz2"`, `"ar"`, or `"deb"`.""",
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
    "remote_file_urls": attr.string_list_dict(
        default = {},
        doc =
            "A map of relative paths (key) to a list of URLs (value) that are to be downloaded " +
            "and made available as overlaid files on the repo. This is useful when you want " +
            "to add WORKSPACE or BUILD.bazel files atop an existing repository. The files " +
            "are downloaded before applying the patches in the `patches` attribute and the list of URLs " +
            "should all be possible mirrors of the same file. The URLs are tried in order until one succeeds. ",
    ),
    "remote_file_integrity": attr.string_dict(
        default = {},
        doc =
            "A map of file relative paths (key) to its integrity value (value). These relative paths should map " +
            "to the files (key) in the `remote_file_urls` attribute.",
    ),
    "remote_patches": attr.string_dict(
        default = {},
        doc =
            "A map of patch file URL to its integrity value, they are applied after extracting " +
            "the archive and before applying patch files from the `patches` attribute. " +
            "It uses the Bazel-native patch implementation, you can specify the patch strip " +
            "number with `remote_patch_strip`",
    ),
    "remote_patch_strip": attr.int(
        default = 0,
        doc =
            "The number of leading slashes to be stripped from the file name in the remote patches.",
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
            "`patch` will be used. This only affects patch files in the `patches` attribute.",
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
            "distinguishing it from the repository's actual BUILD files. " +
            "Either build_file or build_file_content can be specified, but " +
            "not both.",
    ),
    "build_file_content": attr.string(
        doc =
            "The content for the BUILD file for this repository. " +
            "Either build_file or build_file_content can be specified, but " +
            "not both.",
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

http_archive = repository_rule(
    implementation = _http_archive_impl,
    attrs = _http_archive_attrs,
    environ = [DEFAULT_CANONICAL_ID_ENV],
    doc =
        """Downloads a Bazel repository as a compressed archive file, decompresses it,
and makes its targets available for binding.

It supports the following file extensions: `"zip"`, `"jar"`, `"war"`, `"aar"`, `"tar"`,
`"tar.gz"`, `"tgz"`, `"tar.xz"`, `"txz"`, `"tar.zst"`, `"tzst"`, `tar.bz2`, `"ar"`,
or `"deb"`.

Examples:
  Suppose the current repository contains the source code for a chat program,
  rooted at the directory `~/chat-app`. It needs to depend on an SSL library
  which is available from http://example.com/openssl.zip. This `.zip` file
  contains the following directory structure:

  ```
  WORKSPACE
  src/
    openssl.cc
    openssl.h
  ```

  In the local repository, the user creates a `openssl.BUILD` file which
  contains the following target definition:

  ```python
  cc_library(
      name = "openssl-lib",
      srcs = ["src/openssl.cc"],
      hdrs = ["src/openssl.h"],
  )
  ```

  Targets in the `~/chat-app` repository can depend on this target if the
  following lines are added to `~/chat-app/WORKSPACE`:

  ```python
  load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

  http_archive(
      name = "my_ssl",
      url = "http://example.com/openssl.zip",
      sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      build_file = "@//:openssl.BUILD",
  )
  ```

  Then targets would specify `@my_ssl//:openssl-lib` as a dependency.
""",
)

_http_file_attrs = {
    "executable": attr.bool(
        doc = "If the downloaded file should be made executable.",
    ),
    "downloaded_file_path": attr.string(
        default = "downloaded",
        doc = "Path assigned to the file downloaded",
    ),
    "sha256": attr.string(
        doc = """The expected SHA-256 of the file downloaded.

This must match the SHA-256 of the file downloaded. _It is a security risk
to omit the SHA-256 as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but should be set before shipping.""",
    ),
    "integrity": attr.string(
        doc = """Expected checksum in Subresource Integrity format of the file downloaded.

This must match the checksum of the file downloaded. _It is a security risk
to omit the checksum as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but either this attribute or `sha256` should be set before shipping.""",
    ),
    "canonical_id": attr.string(
        doc = CANONICAL_ID_DOC,
    ),
    "url": attr.string(doc = _URL_DOC),
    "urls": attr.string_list(doc = _URLS_DOC),
    "netrc": attr.string(
        doc = "Location of the .netrc file to use for authentication",
    ),
    "auth_patterns": attr.string_dict(
        doc = _AUTH_PATTERN_DOC,
    ),
}

http_file = repository_rule(
    implementation = _http_file_impl,
    attrs = _http_file_attrs,
    environ = [DEFAULT_CANONICAL_ID_ENV],
    doc =
        """Downloads a file from a URL and makes it available to be used as a file
group.

Examples:
  Suppose you need to have a debian package for your custom rules. This package
  is available from http://example.com/package.deb. Then you can add to your
  WORKSPACE file:

  ```python
  load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

  http_file(
      name = "my_deb",
      url = "http://example.com/package.deb",
      sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  )
  ```

  Targets would specify `@my_deb//file` as a dependency to depend on this file.
""",
)

_http_jar_attrs = {
    "sha256": attr.string(
        doc = """The expected SHA-256 of the file downloaded.

This must match the SHA-256 of the file downloaded. _It is a security risk
to omit the SHA-256 as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but either this attribute or `integrity` should be set before shipping.""",
    ),
    "integrity": attr.string(
        doc = """Expected checksum in Subresource Integrity format of the file downloaded.

This must match the checksum of the file downloaded. _It is a security risk
to omit the checksum as remote files can change._ At best omitting this
field will make your build non-hermetic. It is optional to make development
easier but either this attribute or `sha256` should be set before shipping.""",
    ),
    "canonical_id": attr.string(
        doc = CANONICAL_ID_DOC,
    ),
    "url": attr.string(doc = _URL_DOC + "\n\nThe URL must end in `.jar`."),
    "urls": attr.string_list(doc = _URLS_DOC + "\n\nAll URLs must end in `.jar`."),
    "netrc": attr.string(
        doc = "Location of the .netrc file to use for authentication",
    ),
    "auth_patterns": attr.string_dict(
        doc = _AUTH_PATTERN_DOC,
    ),
    "downloaded_file_name": attr.string(
        default = "downloaded.jar",
        doc = "Filename assigned to the jar downloaded",
    ),
}

http_jar = repository_rule(
    implementation = _http_jar_impl,
    attrs = _http_jar_attrs,
    environ = [DEFAULT_CANONICAL_ID_ENV],
    doc =
        """Downloads a jar from a URL and makes it available as java_import

Downloaded files must have a .jar extension.

Examples:
  Suppose the current repository contains the source code for a chat program, rooted at the
  directory `~/chat-app`. It needs to depend on an SSL library which is available from
  `http://example.com/openssl-0.2.jar`.

  Targets in the `~/chat-app` repository can depend on this target if the following lines are
  added to `~/chat-app/WORKSPACE`:

  ```python
  load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

  http_jar(
      name = "my_ssl",
      url = "http://example.com/openssl-0.2.jar",
      sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  )
  ```

  Targets would specify `@my_ssl//jar` as a dependency to depend on this jar.

  You may also reference files on the current system (localhost) by using "file:///path/to/file"
  if you are on Unix-based systems. If you're on Windows, use "file:///c:/path/to/file". In both
  examples, note the three slashes (`/`) -- the first two slashes belong to `file://` and the third
  one belongs to the absolute path to the file.
""",
)
