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

"""Helper functions for Bzlmod build"""

load(":blazel_utils.bzl", _get_canonical_repo_name = "get_canonical_repo_name", _get_repo_root = "get_repo_root")

get_canonical_repo_name = _get_canonical_repo_name
get_repo_root = _get_repo_root

def extract_url(attributes):
    """Extracts the url from the given attributes.

    Args:
        attributes: The attributes to extract the url from.

    Returns:
        The url extracted from the given attributes.
    """
    if "urls" in attributes:
        return attributes["urls"][0]
    elif "url" in attributes:
        return attributes["url"]
    else:
        fail("Could not find url in attributes %s" % attributes)

def parse_http_artifacts(ctx, lockfile_path, required_repos):
    """Parses the http artifacts required from for fetching the given repos from the lockfile.

    Args:
        ctx: the repository / module extension ctx object.
        lockfile_path: The path of the lockfile to extract the http artifacts from.
        required_repos: The list of required repos to extract the http artifacts for,
                        only support `http_archive`, `http_file` and `http_jar` repo rules.

    Returns:
        A list of http artifacts in the form of
        [{"integrity": <integrity value>, "url": <url>}, {"sha256": <sha256 value>, "url": <url>}, ...]
    """
    lockfile = json.decode(ctx.read(lockfile_path))
    http_artifacts = []
    found_repos = []
    for url, sha256 in lockfile["registryFileHashes"].items():
        if not url.endswith("/source.json"):
            continue
        segments = url.split("/")
        module = {
            "name": segments[-3],
            "version": segments[-2],
        }
        repo_name = _module_repo_name(module)
        if repo_name not in required_repos:
            continue
        found_repos.append(repo_name)

        ctx.delete("./tempfile")
        ctx.download(url, "./tempfile", executable = False, sha256 = sha256)
        source_json = json.decode(ctx.read("./tempfile"))

        http_artifacts.append({
            "integrity": source_json["integrity"],
            "url": source_json["url"],
        })

        for patch, integrity in source_json.get("patches", {}).items():
            http_artifacts.append({
                "integrity": integrity,
                "url": url.rsplit("/", 1)[0] + "/patches/" + patch,
            })

    for extension_id, extension_entry in lockfile["moduleExtensions"].items():
        if extension_id.startswith("@@"):
            # "@@rules_foo+//:extensions.bzl%foo" --> "rules_foo+"
            module_repo_name = extension_id.removeprefix("@@").partition("//")[0]
        else:
            # "//:extensions.bzl%foo" --> ""
            module_repo_name = ""
        extension_name = extension_id.partition("%")[2]
        repo_name_prefix = "{}+{}+".format(module_repo_name, extension_name)
        extensions = []
        for _, extension_per_platform in extension_entry.items():
            extensions.append(extension_per_platform)
        for extension in extensions:
            for local_name, repo_spec in extension["generatedRepoSpecs"].items():
                rule_class = repo_spec["ruleClassName"] if "ruleClassName" in repo_spec else repo_spec["repoRuleId"]

                if rule_class.endswith("http_archive") or rule_class.endswith("http_file") or rule_class.endswith("http_jar"):
                    attributes = repo_spec["attributes"]
                    repo_name = repo_name_prefix + local_name

                    if repo_name not in required_repos:
                        continue
                    found_repos.append(repo_name)

                    http_artifacts.append({
                        "sha256": attributes["sha256"],
                        "url": extract_url(attributes),
                    })

    missing_repos = [repo for repo in required_repos if repo not in found_repos]
    if missing_repos:
        fail("Could not find all required repos, missing: %s" % missing_repos)

    return http_artifacts

BCR_URL_SCHEME = "https://bcr.bazel.build/modules/{name}/{version}/{file}"

def parse_registry_files(ctx, lockfile_path, module_files):
    """Parses the registry files referenced by the given lockfile and returns them in http_file form.

    Args:
        ctx: the repository / module extension ctx object.
        lockfile_path: The path of the lockfile to extract the registry files from.
        module_files: The paths of non-registry module files to use during fake module resolution.

    Returns:
        A list of http artifacts in the form of
        [{"sha256": <sha256 value>, "url": <url>}, ...]
    """
    lockfile = json.decode(ctx.read(lockfile_path))
    registry_file_hashes = lockfile.get("registryFileHashes", {})
    return [
        {"sha256": sha256, "url": url}
        for url, sha256 in registry_file_hashes.items()
    ]

def parse_bazel_module_repos(ctx, lockfile_path):
    """Parse repo names of http_archive backed Bazel modules from the given lockfile.

    Args:
        ctx: the repository / module extension ctx object.
        lockfile_path: The path of the lockfile to extract the repo names from.

    Returns:
        A list of canonical repository names
    """

    lockfile = json.decode(ctx.read(lockfile_path))
    repos = []
    for url in lockfile["registryFileHashes"].keys():
        if not url.endswith("/source.json"):
            continue
        segments = url.split("/")
        module = {
            "name": segments[-3],
            "version": segments[-2],
        }
        repo_name = _module_repo_name(module)
        repos.append(repo_name)
    return {repo: None for repo in repos}.keys()

# Keep in sync with ModuleKey.
_WELL_KNOWN_MODULES = ["bazel_tools", "local_config_platform", "platforms"]

def _module_repo_name(module):
    module_name = module["name"]
    if module_name in _WELL_KNOWN_MODULES:
        return module_name

    return "{}+".format(module_name)
