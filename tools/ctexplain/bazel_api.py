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
"""API for Bazel calls for config, cquery, and required fragment info.

There's no Python Bazel API so we invoke Bazel as a subprocess.
"""
import functools
import json
import os
import re
import subprocess
from typing import Callable
from typing import List
from typing import Tuple
# Do not edit this line. Copybara replaces it with PY2 migration helper.
from frozendict import frozendict
from tools.ctexplain.types import Configuration
from tools.ctexplain.types import ConfiguredTarget
from tools.ctexplain.types import HostConfiguration
from tools.ctexplain.types import NullConfiguration

CQUERY_RESULT_LINE_REGEX = re.compile(r"^(.*?) \((\S+)\) \[([^[]*)\]$")
DEFAULT_BAZEL_BINARY = "bazel"


def run_bazel_in_client(args: List[str],
                        bazel: str = DEFAULT_BAZEL_BINARY) -> Tuple[int, List[str], List[str]]:
  """Calls bazel within the current workspace.

  For production use. Tests use an alternative invoker that goes through test
  infrastructure.

  Args:
    args: the arguments to call Bazel with

  Returns:
    Tuple of (return code, stdout, stderr)
  """
  result = subprocess.run(
      [bazel] + args,
      cwd=os.getcwd(),
      stdout=subprocess.PIPE,
      stderr=subprocess.PIPE,
      check=False)
  return (result.returncode, result.stdout.decode("utf-8").split(os.linesep),
          result.stderr)


class BazelApi():
  """API that accepts injectable Bazel invocation logic."""

  def __init__(self,
               run_bazel: Callable[[List[str]],
                                   Tuple[int, List[str],
                                         List[str]]] = None,
               bazel: str = DEFAULT_BAZEL_BINARY):
    self.bazel = bazel
    self.run_bazel = run_bazel
    if run_bazel is None:
      self.run_bazel = functools.partial(run_bazel_in_client, bazel=self.bazel)

  def cquery(self,
             args: List[str]) -> Tuple[bool, str, Tuple[ConfiguredTarget, ...]]:
    """Calls cquery with the given arguments.

    Args:
      args: A list of cquery command-line arguments, one argument per entry.

    Returns:
      (success, stderr, cts), where success is True iff the query succeeded,
      stderr contains the query's stderr (regardless of success value), and cts
      is the configured targets found by the query if successful, empty
      otherwise.

      ct order preserves cquery's output order. This is topologically sorted
      with duplicates removed. So no unique configured target appears twice and
      if A depends on B, A appears before B.
    """
    base_args = ["cquery", "--show_config_fragments=transitive"]
    (returncode, stdout, stderr) = self.run_bazel(base_args + args)
    if returncode != 0:
      return (False, stderr, ())

    cts = []
    for line in stdout:
      if not line.strip():
        continue
      ctinfo = _parse_cquery_result_line(line)
      if ctinfo is not None:
        cts.append(ctinfo)

    return (True, stderr, tuple(cts))

  def get_config(self, config_hash: str) -> Configuration:
    """Calls "bazel config" with the given config hash.

    Args:
      config_hash: A config hash as reported by "bazel cquery".

    Returns:
      The matching configuration or None if no match is found.

    Raises:
      ValueError: On any parsing problems.
    """
    if config_hash == "HOST":
      return HostConfiguration()
    elif config_hash == "null":
      return NullConfiguration()

    base_args = ["config", "--output=json"]
    (returncode, stdout, stderr) = self.run_bazel(base_args + [config_hash])
    if returncode != 0:
      raise ValueError("Could not get config: " + stderr)
    config_json = json.loads(os.linesep.join(stdout))
    fragments = frozendict({
        _base_name(entry["name"]):
        tuple(_base_name(clazz) for clazz in entry["fragmentOptions"])
        for entry in config_json["fragments"]
    })
    options = frozendict({
        _base_name(entry["name"]): frozendict(entry["options"])
        for entry in config_json["fragmentOptions"]
    })
    return Configuration(fragments, options)


# TODO(gregce): have cquery --output=jsonproto support --show_config_fragments
# so we can replace all this regex parsing with JSON reads.
def _parse_cquery_result_line(line: str) -> ConfiguredTarget:
  """Converts a cquery output line to a ConfiguredTarget.

  Expected input is:

      "<label> (<config hash>) [configFragment1, configFragment2, ...]"

  or:
      "<label> (null)"

  Args:
    line: The expected input.

  Returns:
    Corresponding ConfiguredTarget if the line matches else None.
  """
  result = CQUERY_RESULT_LINE_REGEX.search(line)
  if result is None:
    raise ValueError(f"{repr(line)} does not match {repr(CQUERY_RESULT_LINE_REGEX.pattern)}")
  label, config_hash, fragments_str = result.groups()
  if fragments_str:
    # The fragments list looks like 'Fragment1, Fragment2, ...'. Split on
    # ', ' to convert it to a structured tuple.
    fragments = tuple(fragments_str.split(", "))
  else:
    fragments = ()
  return ConfiguredTarget(
      label=label,
      config=None,  # Not yet available: we'll need `bazel config` to get this.
      config_hash=config_hash,
      transitive_fragments=fragments)


def _base_name(full_name: str) -> str:
  """Strips a fully qualified Java class name to the file scope.

  Examples:
    - "A.B.OuterClass" -> "OuterClass"
    - "A.B.OuterClass$InnerClass" -> "OuterClass$InnerClass"

  Args:
    full_name: Fully qualified class name.

  Returns:
    Stripped name.
  """
  return full_name.split(".")[-1]
