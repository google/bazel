# pylint: disable=g-direct-third-party-import
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

import io
import os
import unittest

from third_party.py import mock

from src.main.protobuf import analysis_v2_pb2
from tools.aquery_differ import aquery_differ


def make_aquery_output(action_objs, artifact_objs, path_fragment_objs):
  action_graph = analysis_v2_pb2.ActionGraphContainer()

  for path_fragment_obj in path_fragment_objs:
    path_fragment = action_graph.path_fragments.add()
    path_fragment.id = path_fragment_obj["id"]
    path_fragment.label = path_fragment_obj["label"]
    if "parent_id" in path_fragment_obj:
      path_fragment.parent_id = path_fragment_obj["parent_id"]

  for artifact_obj in artifact_objs:
    artifact = action_graph.artifacts.add()
    artifact.id = artifact_obj["id"]
    artifact.path_fragment_id = artifact_obj["path_fragment_id"]

  for action_obj in action_objs:
    action = action_graph.actions.add()
    action.output_ids.extend(action_obj["output_ids"])
    action.arguments.extend(action_obj["arguments"])

    if "input_dep_set_ids" in action_obj:
      action.input_dep_set_ids.extend(action_obj["input_dep_set_ids"])

  return action_graph


def make_aquery_output_with_dep_set(action_objs, artifact_objs,
                                    path_fragment_objs, dep_set_objs):
  action_graph = make_aquery_output(action_objs, artifact_objs,
                                    path_fragment_objs)

  for ds in dep_set_objs:
    dep_set = action_graph.dep_set_of_files.add()
    dep_set.id = ds["id"]
    dep_set.direct_artifact_ids.extend(ds["direct_artifact_ids"])
    dep_set.transitive_dep_set_ids.extend(ds["transitive_dep_set_ids"])

  return action_graph


class CmdLineDifferTest(unittest.TestCase):

  def test_no_difference(self):
    action_graph = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [1, 2]
            },
            {
                "arguments": ["-c"],
                "output_ids": [3]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }, {
            "id": 3,
            "path_fragment_id": 4
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
            {
                "id": 4,
                "label": "baz",
                "parent_id": 1
            },
        ])
    mock_stdout = io.StringIO()
    attrs = ["cmdline"]
    with mock.patch("sys.stdout", mock_stdout):
      aquery_differ._aquery_diff(action_graph, action_graph, attrs, "before",
                                 "after")
      self.assertEqual(mock_stdout.getvalue(), "No difference\n")

  def test_no_difference_different_output_files_order(self):
    first = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [1, 2]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
        ])

    second = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [2, 1]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
        ])

    mock_stdout = io.StringIO()
    attrs = ["cmdline"]
    with mock.patch("sys.stdout", mock_stdout):
      aquery_differ._aquery_diff(first, second, attrs, "before", "after")
      self.assertEqual(mock_stdout.getvalue(), "No difference\n")

  def test_first_has_extra_output_files(self):
    first = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [1, 2]
            },
            {
                "arguments": ["-c"],
                "output_ids": [3]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }, {
            "id": 3,
            "path_fragment_id": 4
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
            {
                "id": 4,
                "label": "baz",
                "parent_id": 1
            },
        ])
    second = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [1, 2]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }],
        path_fragment_objs=[{
            "id": 1,
            "label": "root"
        }, {
            "id": 2,
            "label": "foo",
            "parent_id": 1
        }, {
            "id": 3,
            "label": "bar",
            "parent_id": 1
        }])

    baz_path = os.path.join("root", "baz")
    expected_error = ("Aquery output 'before' change contains an action "
                      "that generates the following outputs that aquery "
                      "output 'after' change doesn't:\n{}\n\n".format(baz_path))
    mock_stdout = io.StringIO()
    attrs = ["cmdline"]
    with mock.patch("sys.stdout", mock_stdout):
      aquery_differ._aquery_diff(first, second, attrs, "before", "after")
      self.assertEqual(mock_stdout.getvalue(), expected_error)

  def test_different_command_lines(self):
    first = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-d"],
                "output_ids": [1, 2]
            },
            {
                "arguments": ["-c"],
                "output_ids": [3]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }, {
            "id": 3,
            "path_fragment_id": 4
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
            {
                "id": 4,
                "label": "baz",
                "parent_id": 1
            },
        ])
    second = make_aquery_output(
        action_objs=[
            {
                "arguments": ["-a", "-b"],
                "output_ids": [1, 2]
            },
            {
                "arguments": ["-c", "-d"],
                "output_ids": [3]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }, {
            "id": 3,
            "path_fragment_id": 4
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
            {
                "id": 4,
                "label": "baz",
                "parent_id": 1
            },
        ])

    foo_path = os.path.join("root", "foo")
    bar_path = os.path.join("root", "bar")
    baz_path = os.path.join("root", "baz")

    expected_error_one = "\n".join([
        "Difference in the action that generates the following output(s):",
        "\t{}".format(baz_path), "--- before", "+++ after", "@@ -1 +1,2 @@",
        " -c", "+-d", "\n"
    ])
    expected_error_two = "\n".join([
        "Difference in the action that generates the following output(s):",
        "\t{}".format(bar_path), "\t{}".format(foo_path), "--- before",
        "+++ after", "@@ -1,2 +1,2 @@", " -a", "--d", "+-b", "\n"
    ])
    attrs = ["cmdline"]

    mock_stdout = io.StringIO()
    with mock.patch("sys.stdout", mock_stdout):
      aquery_differ._aquery_diff(first, second, attrs, "before", "after")
      self.assertIn(expected_error_one, mock_stdout.getvalue())
      self.assertIn(expected_error_two, mock_stdout.getvalue())

  def test_different_inputs(self):
    first = make_aquery_output_with_dep_set(
        action_objs=[{
            "arguments": [],
            "output_ids": [1, 2],
            "input_dep_set_ids": [2]
        }],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
        ],
        dep_set_objs=[{
            "id": 1,
            "transitive_dep_set_ids": [],
            "direct_artifact_ids": [1]
        }, {
            "id": 2,
            "transitive_dep_set_ids": [1],
            "direct_artifact_ids": [2]
        }])
    second = make_aquery_output_with_dep_set(
        action_objs=[
            {
                "arguments": [],
                "output_ids": [1, 2],
                "input_dep_set_ids": [1]
            },
        ],
        artifact_objs=[{
            "id": 1,
            "path_fragment_id": 2
        }, {
            "id": 2,
            "path_fragment_id": 3
        }],
        path_fragment_objs=[
            {
                "id": 1,
                "label": "root"
            },
            {
                "id": 2,
                "label": "foo",
                "parent_id": 1
            },
            {
                "id": 3,
                "label": "bar",
                "parent_id": 1
            },
        ],
        dep_set_objs=[{
            "id": 1,
            "transitive_dep_set_ids": [],
            "direct_artifact_ids": [1]
        }])

    foo_path = os.path.join("root", "foo")
    bar_path = os.path.join("root", "bar")
    expected_error_one = "\n".join([
        "Difference in the action that generates the following output(s):",
        "\t{}".format(bar_path), "\t{}".format(foo_path), "--- before",
        "+++ after", "@@ -1,2 +1 @@", "-{}".format(bar_path),
        " {}".format(foo_path), "\n"
    ])
    attrs = ["inputs"]

    mock_stdout = io.StringIO()
    with mock.patch("sys.stdout", mock_stdout):
      aquery_differ._aquery_diff(first, second, attrs, "before", "after")
      self.assertIn(expected_error_one, mock_stdout.getvalue())


if __name__ == "__main__":
  unittest.main()
