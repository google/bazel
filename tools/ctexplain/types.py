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
"""The core data types ctexplain manipulates."""

from typing import Mapping
from typing import Optional
from typing import Tuple
from dataclasses import dataclass
from dataclasses import field
from frozendict import frozendict


@dataclass(frozen=True)
class Configuration():
  """Stores a build configuration as a collection of fragments and options."""
  # Mapping of each BuildConfiguration.Fragment in this configuration to the
  # FragmentOptions it requires.
  #
  # All names are qualified up to the base file name, without package prefixes.
  # For example, foo.bar.BazConfiguration appears as "BazConfiguration".
  # foo.bar.BazConfiguration$Options appears as "BazeConfiguration$Options".
  fragments: Mapping[str, Tuple[str, ...]]
  # Mapping of FragmentOptions to option key/value pairs. For example:
  # {"CoreOptions": {"action_env": "[]", "cpu": "x86", ...}, ...}.
  #
  # Option values are stored as strings of whatever "bazel config" outputs.
  #
  # Note that Fragment and FragmentOptions aren't the same thing.
  options: Mapping[str, Mapping[str, str]]


@dataclass(frozen=True)
class ConfiguredTarget():
  """Encapsulates a target + configuration + required fragments."""
  # Label of the target this represents.
  label: str
  # Configuration this target is applied to. May be None.
  config: Optional[Configuration]
  # The hash of this configuration as reported by Bazel.
  config_hash: str
  # Fragments required by this configured target and its transitive
  # dependencies. Stored as base names without packages. For example:
  # "PlatformOptions" or "FooConfiguration$Options".
  transitive_fragments: Tuple[str, ...]


@dataclass(frozen=True)
class HostConfiguration(Configuration):
  """Special marker for the host configuration.

  There's exactly one host configuration per build, so we shouldn't suggest
  merging it with other configurations.

  TODO(gregce): suggest host configuration trimming once we figure out the right
  criteria. Even if Bazel's not technically equipped to do the trimming, it's
  still theoretically valuable information. Note that moving from host to exec
  configurations make this all a little less relevant, since exec configurations
  aren't "special" compared to normal configurations.
  """
  # We don't currently read the host config's fragments or option values.
  fragments: Tuple[str, ...] = ()
  options: Mapping[str,
                   Mapping[str,
                           str]] = field(default_factory=lambda: frozendict({}))


@dataclass(frozen=True)
class NullConfiguration(Configuration):
  """Special marker for the null configuration.

  By definition this has no fragments or options.
  """
  fragments: Tuple[str, ...] = ()
  options: Mapping[str,
                   Mapping[str,
                           str]] = field(default_factory=lambda: frozendict({}))
