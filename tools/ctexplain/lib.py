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
"""General-purpose business logic."""
from typing import Tuple

import tools.ctexplain.bazel_api as bazel_api
from tools.ctexplain.types import ConfiguredTarget


def analyze_build(bazel: bazel_api.BazelApi, labels: Tuple[str, ...],
                  build_flags: Tuple[str, ...]) -> Tuple[ConfiguredTarget, ...]:
  """Gets a build invocation's configured targets.

  Args:
    bazel: API for invoking Bazel.
    labels: The targets to build.
    build_flags: The build flags to use.

  Returns:
    Configured targets representing the build.

  Raises:
    RuntimeError: On any invocation errors.
  """
  cquery_args = [f'deps({",".join(labels)})']
  cquery_args.extend(build_flags)
  (success, stderr, cts) = bazel.cquery(cquery_args)
  if not success:
    raise RuntimeError("invocation failed: " + stderr.decode("utf-8"))

  # We have to do separate calls to "bazel config" to get the actual configs
  # from their hashes.
  hashes_to_configs = {}
  cts_with_configs = []
  for ct in cts:
    # Don't use dict.setdefault because that unconditionally calls get_config
    # as one of its parameters and that's an expensive operation to waste.
    if ct.config_hash not in hashes_to_configs:
      hashes_to_configs[ct.config_hash] = bazel.get_config(ct.config_hash)
    config = hashes_to_configs[ct.config_hash]
    cts_with_configs.append(
        ConfiguredTarget(ct.label, config, ct.config_hash,
                         ct.transitive_fragments))

  return tuple(cts_with_configs)
