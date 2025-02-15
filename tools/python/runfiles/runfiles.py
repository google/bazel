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
"""Deprecated forwarder for the rules_python runfiles library.

Depend on @rules_python//python/runfiles instead and refer to its documentation:
https://github.com/bazelbuild/rules_python/blob/main/python/runfiles/README.md
"""
from python.runfiles import Create as _Create
from python.runfiles import CreateDirectoryBased as _CreateDirectoryBased
from python.runfiles import CreateManifestBased as _CreateManifestBased

Create = _Create
CreateDirectoryBased = _CreateDirectoryBased
CreateManifestBased = _CreateManifestBased
