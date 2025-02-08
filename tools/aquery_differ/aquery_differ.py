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
r"""Command line diffing tool that compares two bazel aquery invocations.

This script compares the proto or textproto output of two bazel aquery
invocations. For each set of output files of an action, it compares the command
lines that generated the files.

Example usage:

1. Prepare 2 aquery output files:
bazel aquery //path/to:target_one --output=textproto > \
    /path/to/output_one.textproto
bazel aquery //path/to:target_two --output=textproto > \
    /path/to/output_two.textproto

2. Run the differ from a bazel repo:
bazel run //tools/aquery_differ:aquery_differ -- \
--before=/path/to/output_one.textproto \
--after=/path/to/output_two.textproto \
--input_type=textproto \
--attrs=cmdline \
--attrs=inputs
"""

import difflib
import os
import sys

from absl import app
from absl import flags
from google.protobuf import text_format
from src.main.protobuf import analysis_v2_pb2
from tools.aquery_differ.resolvers.dep_set_resolver import DepSetResolver
from tools.aquery_differ.resolvers.path_fragment_resolver import PathFragmentResolver
# pylint: disable=g-import-not-at-top
# resource lib isn't available on Windows.
if os.name != "nt":
  import resource
# pylint: enable=g-import-not-at-top

flags.DEFINE_string("before", None, "Aquery output before the change")
flags.DEFINE_string("after", None, "Aquery output after the change")
flags.DEFINE_enum(
    "input_type", "proto", ["proto", "textproto"],
    "The format of the aquery proto input. One of 'proto' and 'textproto.")
flags.DEFINE_multi_enum("attrs", ["cmdline"], ["inputs", "cmdline"],
                        "Attributes of the actions to be compared.")
flags.DEFINE_integer(
    "max_mem_alloc_mb", 3072,
    "Amount of max memory available for aquery_differ, in MB.")
flags.mark_flag_as_required("before")
flags.mark_flag_as_required("after")

WHITE = "\033[37m%s\033[0m"
CYAN = "\033[36m%s\033[0m"
RED = "\033[31m%s\033[0m"
GREEN = "\033[32m%s\033[0m"


def _colorize(line):
  """Add color to the input string."""
  if not sys.stdout.isatty():
    return line

  if line.startswith("+++") or line.startswith("---"):
    return WHITE % line

  if line.startswith("@@"):
    return CYAN % line

  if line.startswith("+"):
    return GREEN % line

  if line.startswith("-"):
    return RED % line

  return line


def _print_diff(output_files, before_val, after_val, attr, before_file,
                after_file):
  diff = "\n".join(
      map(_colorize, [
          s.strip("\n") for s in difflib.unified_diff(before_val, after_val,
                                                      before_file, after_file)
      ]))
  print(("[%s]\n"
         "Difference in the action that generates the following output(s):"
         "\n\t%s\n%s\n") % (attr, "\n\t".join(output_files.split()), diff))


def _map_artifact_id_to_path(artifacts, path_fragments):
  path_fragment_resolver = PathFragmentResolver(path_fragments)
  return {
      artifact.id: path_fragment_resolver.resolve(artifact.path_fragment_id)
      for artifact in artifacts
  }


def _map_action_index_to_output_files(actions, artifacts):
  """Constructs a map from action index to output files.

  Args:
    actions: a list of actions from the action graph container
    artifacts: a map {artifact_id: artifact path}

  Returns:
    A map from action index (in action graph container) to a string of
    concatenated output artifacts paths.
  """
  action_index_to_output_files = {}
  for i, action in enumerate(actions):
    output_files = " ".join(
        sorted([artifacts[output_id] for output_id in action.output_ids]))
    action_index_to_output_files[i] = output_files
  return action_index_to_output_files


# output files -> input artifacts
def _map_output_files_to_input_artifacts(action_graph_container,
                                         artifact_id_to_path,
                                         action_index_to_output_files):
  """Constructs a map from output files to input artifacts.

  Args:
    action_graph_container: the full action graph container object
    artifact_id_to_path: a map {artifact_id: artifact path}
    action_index_to_output_files: a map from action index (in action graph
      container) to a string of concatenated output artifacts paths.

  Returns:
    A map from output files (string of concatenated output artifacts paths) to a
    list of input artifacts.
  """
  actions = action_graph_container.actions
  dep_set_of_files = action_graph_container.dep_set_of_files
  id_to_dep_set = {dep_set.id: dep_set for dep_set in dep_set_of_files}
  dep_set_resolver = DepSetResolver(dep_set_of_files, artifact_id_to_path)

  output_files_to_input_artifacts = {}
  for i, action in enumerate(actions):
    input_artifacts = []

    for dep_set_id in action.input_dep_set_ids:
      input_artifacts.extend(
          dep_set_resolver.resolve(id_to_dep_set[dep_set_id]))

    output_files_to_input_artifacts[action_index_to_output_files[i]] = list(
        sorted(input_artifacts))

  return output_files_to_input_artifacts


# output files -> command line
def _map_output_files_to_command_line(actions, action_index_to_output_files):
  """Constructs a map from output files to command line.

  Args:
    actions: a list of actions from the action graph container
    action_index_to_output_files: a map from action index (in action graph
      container) to a string of concatenated output artifacts paths.

  Returns:
    A map from output files (string of concatenated output artifacts paths)
    to the command line (a list of arguments).
  """
  output_files_to_command_line = {}
  for i, action in enumerate(actions):
    output_files_to_command_line[
        action_index_to_output_files[i]] = action.arguments
  return output_files_to_command_line


def _aquery_diff(before_proto, after_proto, attrs, before_file, after_file):
  """Returns differences between command lines that generate same outputs."""
  found_difference = False
  artifacts_before = _map_artifact_id_to_path(before_proto.artifacts,
                                              before_proto.path_fragments)
  artifacts_after = _map_artifact_id_to_path(after_proto.artifacts,
                                             after_proto.path_fragments)

  action_to_output_files_before = _map_action_index_to_output_files(
      before_proto.actions, artifacts_before)
  action_to_output_files_after = _map_action_index_to_output_files(
      after_proto.actions, artifacts_after)

  # There's a 1-to-1 mapping between action and outputs
  output_files_before = set(action_to_output_files_before.values())
  output_files_after = set(action_to_output_files_after.values())

  before_after_diff = output_files_before - output_files_after
  after_before_diff = output_files_after - output_files_before

  if before_after_diff:
    print(("Aquery output 'before' change contains an action that generates "
           "the following outputs that aquery output 'after' change doesn't:"
           "\n%s\n") % "\n".join(before_after_diff))
    found_difference = True
  if after_before_diff:
    print(("Aquery output 'after' change contains an action that generates "
           "the following outputs that aquery output 'before' change doesn't:"
           "\n%s\n") % "\n".join(after_before_diff))
    found_difference = True

  if "cmdline" in attrs:
    output_to_command_line_before = _map_output_files_to_command_line(
        before_proto.actions, action_to_output_files_before)
    output_to_command_line_after = _map_output_files_to_command_line(
        after_proto.actions, action_to_output_files_after)
    for output_files in output_to_command_line_before:
      arguments = output_to_command_line_before[output_files]
      after_arguments = output_to_command_line_after.get(output_files, None)
      if after_arguments and arguments != after_arguments:
        _print_diff(output_files, arguments, after_arguments, "cmdline",
                    before_file, after_file)
        found_difference = True

  if "inputs" in attrs:
    output_to_input_files_before = _map_output_files_to_input_artifacts(
        before_proto, artifacts_before, action_to_output_files_before)
    output_to_input_files_after = _map_output_files_to_input_artifacts(
        after_proto, artifacts_after, action_to_output_files_after)
    for output_files in output_to_input_files_before:
      before_inputs = output_to_input_files_before[output_files]
      after_inputs = output_to_input_files_after.get(output_files, None)
      if after_inputs and before_inputs != after_inputs:
        _print_diff(output_files, before_inputs, after_inputs, "inputs",
                    before_file, after_file)
        found_difference = True

  if not found_difference:
    print("No difference")


def to_absolute_path(path):
  path = os.path.expanduser(path)
  if os.path.isabs(path):
    return path
  else:
    if "BUILD_WORKING_DIRECTORY" in os.environ:
      return os.path.join(os.environ["BUILD_WORKING_DIRECTORY"], path)
    else:
      return path


def main(unused_argv):
  before_file = to_absolute_path(flags.FLAGS.before)
  after_file = to_absolute_path(flags.FLAGS.after)
  input_type = flags.FLAGS.input_type
  attrs = flags.FLAGS.attrs
  max_mem_alloc_mb = flags.FLAGS.max_mem_alloc_mb

  # resource lib isn't available on Windows.
  if os.name != "nt":
    max_heap_bytes = max_mem_alloc_mb * 1024 * 1024
    resource.setrlimit(resource.RLIMIT_AS, (max_heap_bytes, max_heap_bytes))

  before_proto = analysis_v2_pb2.ActionGraphContainer()
  after_proto = analysis_v2_pb2.ActionGraphContainer()
  try:
    if input_type == "proto":
      with open(before_file, "rb") as f:
        before_proto.ParseFromString(f.read())
      with open(after_file, "rb") as f:
        after_proto.ParseFromString(f.read())
    else:
      with open(before_file, "r") as f:
        before_text = f.read()
        text_format.Merge(before_text, before_proto)
      with open(after_file, "r") as f:
        after_text = f.read()
        text_format.Merge(after_text, after_proto)

    _aquery_diff(before_proto, after_proto, attrs, before_file, after_file)
  except MemoryError:
    print(
        "aquery_differ is known to cause OOM issue with large inputs. More details: b/154620006.",
        file=sys.stderr)
    print(
        "Max mem space of {}MB exceeded".format(max_mem_alloc_mb),
        file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
  app.run(main)
