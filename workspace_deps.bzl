# Copyright 2020 The Bazel Authors. All rights reserved.
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

"""WORKSPACE default repository definitions."""

WORKSPACE_REPOS = {
    # Used in src/main/java/com/google/devtools/build/lib/bazel/rules/cpp/cc_configure.WORKSPACE.
    # Used in src/main/java/com/google/devtools/build/lib/bazel/rules/java/jdk.WORKSPACE.
    # Used in src/test/java/com/google/devtools/build/lib/blackbox/framework/blackbox.WORKSAPCE
    "rules_cc": {
        "archive": "rules_cc-0.0.16.tar.gz",
        "sha256": "bbf1ae2f83305b7053b11e4467d317a7ba3517a12cef608543c1b1c5bf48a4df",
        "urls": ["https://github.com/bazelbuild/rules_cc/releases/download/0.0.16/rules_cc-0.0.16.tar.gz"],
        "strip_prefix": "rules_cc-0.0.16",
    },
    "com_google_protobuf": {
        "archive": "protobuf-29.0-rc3.zip",
        "sha256": "537d1c4edb6cbfa96d98a021650e3c455fffcf80dbdcea7fe46cb356e6e9732d",
        "urls": ["https://github.com/protocolbuffers/protobuf/releases/download/v29.0-rc3/protobuf-29.0-rc3.zip"],
        "strip_prefix": "protobuf-29.0-rc3",
    },
    "rules_java": {
        "archive": "382cc54efd103279e03e7c5b2a394694441d60ec.tar.gz",
        "sha256": "bff4c9c86a75cd0b7bd34e43a89ae6018319b6fbdc95957e838acff00044c1cd",
        "strip_prefix": "rules_java-382cc54efd103279e03e7c5b2a394694441d60ec",
        "urls": ["https://github.com/bazelbuild/rules_java/archive/382cc54efd103279e03e7c5b2a394694441d60ec.tar.gz"],
    },
    "bazel_skylib": {
        "archive": "bazel-skylib-1.6.1.tar.gz",
        "sha256": "9f38886a40548c6e96c106b752f242130ee11aaa068a56ba7e56f4511f33e4f2",
        "urls": ["https://github.com/bazelbuild/bazel-skylib/releases/download/1.6.1/bazel-skylib-1.6.1.tar.gz"],
    },
    "rules_license": {
        "archive": "rules_license-0.0.7.tar.gz",
        "sha256": "4531deccb913639c30e5c7512a054d5d875698daeb75d8cf90f284375fe7c360",
        "urls": ["https://github.com/bazelbuild/rules_license/releases/download/0.0.7/rules_license-0.0.7.tar.gz"],
    },
    "rules_python": {
        "archive": "rules_python-0.40.0.tar.gz",
        "sha256": "690e0141724abb568267e003c7b6d9a54925df40c275a870a4d934161dc9dd53",
        "strip_prefix": "rules_python-0.40.0",
        "urls": ["https://github.com/bazelbuild/rules_python/releases/download/0.40.0/rules_python-0.40.0.tar.gz"],
    },
    "rules_pkg": {
        "archive": "rules_pkg-0.9.1.tar.gz",
        "sha256": "8f9ee2dc10c1ae514ee599a8b42ed99fa262b757058f65ad3c384289ff70c4b8",
        "urls": ["https://github.com/bazelbuild/rules_pkg/releases/download/0.9.1/rules_pkg-0.9.1.tar.gz"],
    },
    "rules_shell": {
        "archive": "rules_shell-v0.1.1.tar.gz",
        "sha256": "0d0c56d01c3c40420bf7bf14d73113f8a92fbd9f5cd13205a3b89f72078f0321",
        "strip_prefix": "rules_shell-0.1.1",
        "urls": ["https://github.com/bazelbuild/rules_shell/releases/download/v0.1.1/rules_shell-v0.1.1.tar.gz"],
    },
    "rules_testing": {
        "archive": "rules_testing-v0.6.0.tar.gz",
        "sha256": "02c62574631876a4e3b02a1820cb51167bb9cdcdea2381b2fa9d9b8b11c407c4",
        "strip_prefix": "rules_testing-0.6.0",
        "urls": ["https://github.com/bazelbuild/rules_testing/releases/download/v0.6.0/rules_testing-v0.6.0.tar.gz"],
    },
    "remote_coverage_tools": {
        "archive": "coverage_output_generator-v2.7.zip",
        "sha256": "69fa4fdd887295e5a45e592352ad00ef6afa6a435d13e3f38f6ed338ded85dfd",
        "urls": ["https://mirror.bazel.build/bazel_coverage_output_generator/releases/coverage_output_generator-v2.7.zip"],
    },
}

def _gen_workspace_stanza_impl(ctx):
    if ctx.attr.template and (ctx.attr.preamble or ctx.attr.postamble):
        fail("Can not use template with either preamble or postamble")
    if ctx.attr.use_maybe and ctx.attr.repo_clause:
        fail("Can not use use_maybe with repo_clause")

    if ctx.attr.use_maybe:
        repo_clause = """
maybe(
    http_archive,
    name = "{repo}",
    sha256 = "{sha256}",
    strip_prefix = {strip_prefix},
    urls = {urls},
)
"""
    elif ctx.attr.repo_clause:
        repo_clause = ctx.attr.repo_clause
    else:
        repo_clause = """
http_archive(
    name = "{repo}",
    sha256 = "{sha256}",
    strip_prefix = {strip_prefix},
    urls = {urls},
)
"""

    repo_stanzas = {}
    for repo in ctx.attr.repos:
        info = WORKSPACE_REPOS[repo]
        strip_prefix = info.get("strip_prefix")
        if strip_prefix:
            strip_prefix = "\"%s\"" % strip_prefix
        else:
            strip_prefix = "None"

        repo_stanzas["{%s}" % repo] = repo_clause.format(
            repo = repo,
            sha256 = str(info["sha256"]),
            strip_prefix = strip_prefix,
            urls = info["urls"],
        )

    if ctx.attr.template:
        ctx.actions.expand_template(
            output = ctx.outputs.out,
            template = ctx.file.template,
            substitutions = repo_stanzas,
        )
    else:
        content = "\n".join([p.strip() for p in ctx.attr.preamble.strip().split("\n")])
        content += "\n"
        content += "".join(repo_stanzas.values())
        content += "\n"
        content += "\n".join([p.strip() for p in ctx.attr.postamble.strip().split("\n")])
        content += "\n"
        ctx.actions.write(ctx.outputs.out, content)

    return [DefaultInfo(files = depset([ctx.outputs.out]))]

gen_workspace_stanza = rule(
    attrs = {
        "repos": attr.string_list(doc = "Set of repos to include."),
        "out": attr.output(mandatory = True),
        "preamble": attr.string(doc = "Preamble."),
        "postamble": attr.string(doc = "Set of rules to follow repos."),
        "template": attr.label(
            doc = "Template WORKSPACE file. May not be used with preamble or postamble." +
                  "Repo stanzas can be included using the syntax '{repo name}'.",
            allow_single_file = True,
            mandatory = False,
        ),
        "use_maybe": attr.bool(doc = "Use maybe() invocation instead of http_archive."),
        "repo_clause": attr.string(doc = "Use a custom clause for each repository."),
    },
    doc = "Use specifications from WORKSPACE_REPOS to generate WORKSPACE http_archive stanzas or to" +
          "drop them into a template.",
    implementation = _gen_workspace_stanza_impl,
)
