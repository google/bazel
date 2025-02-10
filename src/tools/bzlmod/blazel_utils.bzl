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

"""Helper functions for Bzlmod build that are safe to use both internally and externally."""

def get_canonical_repo_name(apparent_repo_name):
    """Returns the canonical repo name for the given apparent repo name seen by the module this bzl file belongs to."""
    if not apparent_repo_name.startswith("@"):
        apparent_repo_name = "@" + apparent_repo_name

    return Label(apparent_repo_name).workspace_name

def get_repo_root(apparent_repo_name):
    """Returns the directory of the repo with the given apparent repo name seen by the module this bzl file belongs to."""
    if not apparent_repo_name.startswith("@"):
        apparent_repo_name = "@" + apparent_repo_name

    return Label(apparent_repo_name).workspace_root
