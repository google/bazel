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
"""Contains resolvers for different attributes of Action in aquery output."""
import os


class PathFragmentResolver(object):
  """Utility class to resolve path fragments."""

  def __init__(self, path_fragments):
    self._id_to_path_fragment = {
        path_fragment.id: path_fragment for path_fragment in path_fragments
    }

  def resolve(self, path_fragment_id):
    """Given a path_fragment_id, return the full path.

    Args:
      path_fragment_id: an int id of the path fragment.

    Returns:
      The string representing the full exec path.
    """

    curr_id = path_fragment_id
    if not curr_id:
      # Root path fragment.
      return ""

    exec_path_tokens = []
    while curr_id:
      if curr_id not in self._id_to_path_fragment:
        raise ValueError('Path Fragment id not found.')

      entry = self._id_to_path_fragment[curr_id]
      exec_path_tokens.append(entry.label)
      curr_id = entry.parent_id

    return os.path.join(*reversed(exec_path_tokens))
