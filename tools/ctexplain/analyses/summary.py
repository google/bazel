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
"""Analysis that summarizes basic graph info."""
from typing import Tuple

from dataclasses import dataclass

from tools.ctexplain.types import ConfiguredTarget
import tools.ctexplain.util as util


@dataclass(frozen=True)
class _Summary():
  """Analysis result."""
  # Number of configurations in the build's configured target graph.
  configurations: int
  # Number of unique target labels.
  targets: int
  # Number of configured targets.
  configured_targets: int
  # Number of targets that produce multiple configured targets. This is more
  # subtle than computing configured_targets - targets. For example, if
  # targets=2 and configured_targets=4, that could mean both targets are
  # configured twice. Or it could mean the first target is configured 3 times.
  repeated_targets: int


def analyze(cts: Tuple[ConfiguredTarget, ...]) -> _Summary:
  """Runs the analysis on a build's configured targets."""
  configurations = set()
  targets = set()
  label_count = {}
  for ct in cts:
    configurations.add(ct.config_hash)
    targets.add(ct.label)
    label_count[ct.label] = label_count.setdefault(ct.label, 0) + 1
  configured_targets = len(cts)
  repeated_targets = sum([1 for count in label_count.values() if count > 1])

  return _Summary(
      len(configurations), len(targets), configured_targets, repeated_targets)


def report(result: _Summary) -> None:
  """Reports analysis results to the user.

  We intentionally make this its own function to make it easy to support other
  output formats (like machine-readable) if we ever want to do that.

  Args:
    result: the analysis result
  """
  ct_surplus = util.percent_diff(result.targets, result.configured_targets)
  print(f"""
Configurations: {result.configurations}
Targets: {result.targets}
Configured targets: {result.configured_targets} ({ct_surplus} vs. targets)
Targets with multiple configs: {result.repeated_targets}
""")
