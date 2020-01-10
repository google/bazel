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

"""
'jvm_import_external' offers additional functionality above what maven_jar has to offer.
In addition to downloading the jars, it allows to define this jar's dependencies.
thus it enables the explicit definition of the entire transitive dependency graph.

The rule achieves this by writing 'import' build rules in BUILD files next to the downloaded jars.
The name of the underlying 'import' rule needs to be specified.
An optional 'load' statement can also be provided, along with any other relevant custom attribute.
These import rules must have the following attributes:
- "jars"
- "deps"
- "runtime_deps"
- "exports"

the following macros are defined below that utilize jvm_import_external:

- jvm_maven_import_external - offers a 'maven' like api for identifying jars using 'artifact' format
- java_import_external - uses `java_import` as the underlying build rule
"""

_HEADER = "# DO NOT EDIT: generated by jvm_import_external()"

_PASS_PROPS = (
    "neverlink",
    "testonly_",
    "visibility",
    "exports",
    "runtime_deps",
    "deps",
    "tags",
)

_FETCH_SOURCES_ENV_VAR = "BAZEL_JVM_FETCH_SOURCES"

def _jvm_import_external(repository_ctx):
    """Implementation of `java_import_external` rule."""
    if (repository_ctx.attr.generated_linkable_rule_name and
        not repository_ctx.attr.neverlink):
        fail("Only use generated_linkable_rule_name if neverlink is set")
    name = repository_ctx.attr.generated_rule_name or repository_ctx.name
    urls = repository_ctx.attr.artifact_urls
    sha = repository_ctx.attr.artifact_sha256
    extension = repository_ctx.attr.rule_metadata["extension"]
    file_extension = "." + extension
    path = repository_ctx.name + file_extension
    for url in urls:
        if url.endswith(file_extension):
            path = url[url.rindex("/") + 1:]
            break
    srcurls = repository_ctx.attr.srcjar_urls
    srcsha = repository_ctx.attr.srcjar_sha256
    srcpath = repository_ctx.name + "-src.jar" if srcurls else ""
    for url in srcurls:
        if url.endswith(file_extension):
            srcpath = url[url.rindex("/") + 1:].replace("-sources.jar", "-src.jar")
            break
    lines = [_HEADER, ""]
    if repository_ctx.attr.rule_load:
        lines.append(repository_ctx.attr.rule_load)
        lines.append("")
    if repository_ctx.attr.default_visibility:
        lines.append("package(default_visibility = %s)" % (
            repository_ctx.attr.default_visibility
        ))
        lines.append("")
    lines.append("licenses(%s)" % repr(repository_ctx.attr.licenses))
    lines.append("")
    lines.extend(_serialize_given_rule_import(
        name = name,
        additional_rule_attrs = repository_ctx.attr.additional_rule_attrs,
        attrs = repository_ctx.attr,
        import_attr = repository_ctx.attr.rule_metadata["import_attr"],
        path = path,
        props = _PASS_PROPS,
        rule_name = repository_ctx.attr.rule_name,
        srcpath = srcpath,
    ))
    if (repository_ctx.attr.neverlink and
        repository_ctx.attr.generated_linkable_rule_name):
        lines.extend(_serialize_given_rule_import(
            name = repository_ctx.attr.generated_linkable_rule_name,
            additional_rule_attrs = repository_ctx.attr.additional_rule_attrs,
            attrs = repository_ctx.attr,
            import_attr = repository_ctx.attr.rule_metadata["import_attr"],
            path = path,
            props = [p for p in _PASS_PROPS if p != "neverlink"],
            rule_name = repository_ctx.attr.rule_name,
            srcpath = srcpath,
        ))
    extra = repository_ctx.attr.extra_build_file_content
    if extra:
        lines.append(extra)
        if not extra.endswith("\n"):
            lines.append("")
    repository_ctx.download(urls, path, sha)
    if srcurls and _should_fetch_sources_in_current_env(repository_ctx):
        repository_ctx.download(srcurls, srcpath, srcsha)
    repository_ctx.file("BUILD", "\n".join(lines))
    repository_ctx.file("%s/BUILD" % extension, "\n".join([
        _HEADER,
        "",
        "package(default_visibility = %r)" % (
            repository_ctx.attr.visibility or
            repository_ctx.attr.default_visibility
        ),
        "",
        "alias(",
        "    name = \"%s\"," % extension,
        "    actual = \"@%s\"," % repository_ctx.name,
        ")",
        "",
        "filegroup(",
        "    name = \"file\",",
        "    srcs = [\"//:%s\"]," % path,
        ")",
    ]))

def _should_fetch_sources_in_current_env(repository_ctx):
    return repository_ctx.os.environ.get(_FETCH_SOURCES_ENV_VAR, "true").lower() == "true"

def _decode_maven_coordinates(artifact, default_packaging):
    parts = artifact.split(":")
    group_id = parts[0]
    artifact_id = parts[1]
    version = parts[2]
    classifier = None
    packaging = default_packaging
    if len(parts) == 4:
        packaging = parts[2]
        version = parts[3]
    elif len(parts) == 5:
        packaging = parts[2]
        classifier = parts[3]
        version = parts[4]

    return struct(
        group_id = group_id,
        artifact_id = artifact_id,
        version = version,
        classifier = classifier,
        packaging = packaging,
    )

# This method is public for usage in android.bzl macros
def convert_artifact_coordinate_to_urls(artifact, server_urls, packaging):
    """This function converts a Maven artifact coordinate into URLs."""
    coordinates = _decode_maven_coordinates(artifact, packaging)
    return _convert_coordinates_to_urls(coordinates, server_urls)

def _convert_coordinates_to_urls(coordinates, server_urls):
    group_id = coordinates.group_id.replace(".", "/")
    classifier = coordinates.classifier

    if classifier:
        classifier = "-" + classifier
    else:
        classifier = ""

    final_name = coordinates.artifact_id + "-" + coordinates.version + classifier + "." + coordinates.packaging
    url_suffix = group_id + "/" + coordinates.artifact_id + "/" + coordinates.version + "/" + final_name

    urls = []
    for server_url in server_urls:
        urls.append(_concat_with_needed_slash(server_url, url_suffix))
    return urls

def _concat_with_needed_slash(server_url, url_suffix):
    if server_url.endswith("/"):
        return server_url + url_suffix
    else:
        return server_url + "/" + url_suffix

def _serialize_given_rule_import(rule_name, import_attr, name, path, srcpath, attrs, props, additional_rule_attrs):
    lines = [
        "%s(" % rule_name,
        "    name = %s," % repr(name),
        "    " + import_attr % repr(path) + ",",
    ]
    if srcpath:
        lines.append("    srcjar = %s," % repr(srcpath))
    for prop in props:
        value = getattr(attrs, prop, None)
        if value:
            if prop.endswith("_"):
                prop = prop[:-1]
            lines.append("    %s = %s," % (prop, repr(value)))
    for attr_key in additional_rule_attrs:
        lines.append("    %s = %s," % (attr_key, additional_rule_attrs[attr_key]))
    lines.append(")")
    lines.append("")
    return lines

jvm_import_external = repository_rule(
    attrs = {
        "rule_name": attr.string(mandatory = True),
        "licenses": attr.string_list(default = ["none"]),
        "artifact_urls": attr.string_list(
            mandatory = True,
            allow_empty = False,
        ),
        "artifact_sha256": attr.string(),
        "rule_metadata": attr.string_dict(
            default = {
                "extension": "jar",
                "import_attr": "jars = [%s]",
            },
        ),
        "rule_load": attr.string(),
        "additional_rule_attrs": attr.string_dict(),
        "srcjar_urls": attr.string_list(),
        "srcjar_sha256": attr.string(),
        "deps": attr.string_list(),
        "runtime_deps": attr.string_list(),
        "testonly_": attr.bool(),
        "exports": attr.string_list(),
        "neverlink": attr.bool(),
        "generated_rule_name": attr.string(),
        "generated_linkable_rule_name": attr.string(),
        "default_visibility": attr.string_list(default = ["//visibility:public"]),
        "extra_build_file_content": attr.string(),
    },
    environ = [_FETCH_SOURCES_ENV_VAR],
    implementation = _jvm_import_external,
)

def jvm_maven_import_external(
        artifact,
        server_urls,
        fetch_sources = False,
        **kwargs):
    if kwargs.get("srcjar_urls") and fetch_sources:
        fail("Either use srcjar_urls or fetch_sources but not both")

    coordinates = _decode_maven_coordinates(artifact, default_packaging = "jar")

    jar_urls = _convert_coordinates_to_urls(coordinates, server_urls)

    srcjar_urls = kwargs.pop("srcjar_urls", None)

    rule_name = kwargs.pop("rule_name", "java_import")
    rule_load = kwargs.pop(
        "rule_load",
        'load("@rules_java//java:defs.bzl", "java_import")',
    )

    if fetch_sources:
        src_coordinates = struct(
            group_id = coordinates.group_id,
            artifact_id = coordinates.artifact_id,
            version = coordinates.version,
            classifier = "sources",
            packaging = "jar",
        )

        srcjar_urls = _convert_coordinates_to_urls(src_coordinates, server_urls)

    jvm_import_external(
        artifact_urls = jar_urls,
        srcjar_urls = srcjar_urls,
        rule_name = rule_name,
        rule_load = rule_load,
        **kwargs
    )
