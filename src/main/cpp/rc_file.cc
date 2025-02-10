// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "src/main/cpp/rc_file.h"

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/file_platform.h"
#include "src/main/cpp/util/logging.h"
#include "src/main/cpp/util/strings.h"
#include "src/main/cpp/workspace_layout.h"
#include "absl/algorithm/container.h"
#include "absl/functional/function_ref.h"
#include "absl/memory/memory.h"
#include "absl/strings/match.h"
#include "absl/strings/str_cat.h"
#include "absl/strings/str_format.h"
#include "absl/strings/str_split.h"
#include "absl/strings/string_view.h"
#include "absl/types/span.h"

namespace blaze {

static constexpr absl::string_view kCommandImport = "import";
static constexpr absl::string_view kCommandTryImport = "try-import";

/*static*/ std::unique_ptr<RcFile> RcFile::Parse(
    const std::string& filename, const WorkspaceLayout* workspace_layout,
    const std::string& workspace, ParseError* error, std::string* error_text,
    ReadFileFn read_file, CanonicalizePathFn canonicalize_path) {
  auto rcfile = absl::WrapUnique(new RcFile());
  std::vector<std::string> initial_import_stack = {filename};
  *error = rcfile->ParseFile(filename, workspace, *workspace_layout, read_file,
                             canonicalize_path, initial_import_stack,
                             error_text);
  return (*error == ParseError::NONE) ? std::move(rcfile) : nullptr;
}

RcFile::ParseError RcFile::ParseFile(const std::string& filename,
                                     const std::string& workspace,
                                     const WorkspaceLayout& workspace_layout,
                                     ReadFileFn read_file,
                                     CanonicalizePathFn canonicalize_path,
                                     std::vector<std::string>& import_stack,
                                     std::string* error_text) {
  BAZEL_LOG(INFO) << "Parsing the RcFile " << filename;
  std::string contents;
  if (std::string error_msg; !read_file(filename, &contents, &error_msg)) {
    *error_text = absl::StrFormat(
        "Unexpected error reading config file '%s': %s", filename, error_msg);
    return ParseError::UNREADABLE_FILE;
  }
  const std::string canonical_filename = canonicalize_path(filename);

  int rcfile_index = canonical_rcfile_paths_.size();
  canonical_rcfile_paths_.push_back(canonical_filename);

  // A '\' at the end of a line continues the line.
  blaze_util::Replace("\\\r\n", "", &contents);
  blaze_util::Replace("\\\n", "", &contents);

  std::vector<std::string> lines = absl::StrSplit(contents, '\n');
  for (std::string& line : lines) {
    blaze_util::StripWhitespace(&line);

    // Check for an empty line.
    if (line.empty()) continue;

    std::vector<std::string> words;

    // This will treat "#" as a comment, and properly
    // quote single and double quotes, and treat '\'
    // as an escape character.
    // TODO(bazel-team): This function silently ignores
    // dangling backslash escapes and missing end-quotes.
    blaze_util::Tokenize(line, '#', &words);

    // Could happen if line starts with "#"
    if (words.empty()) continue;

    const absl::string_view command = words[0];
    if (command != kCommandImport && command != kCommandTryImport) {
      for (absl::string_view word : absl::MakeConstSpan(words).subspan(1)) {
        options_[command].push_back({std::string(word), rcfile_index});
      }
      continue;
    }

    if (words.size() != 2) {
      *error_text = absl::StrFormat(
          "Invalid import declaration in config file '%s': '%s'",
          canonical_filename, line);
      return ParseError::INVALID_FORMAT;
    }

    std::string& import_filename = words[1];
    if (absl::StartsWith(import_filename, WorkspaceLayout::kWorkspacePrefix)) {
      const auto resolved_filename =
          workspace_layout.ResolveWorkspaceRelativeRcFilePath(workspace,
                                                              import_filename);
      if (!resolved_filename.has_value()) {
        if (command == kCommandImport) {
          *error_text = absl::StrFormat(
              "Nonexistent path in import declaration in config file '%s': '%s'"
              " (are you in your source checkout/WORKSPACE?)",
              canonical_filename, line);
          return ParseError::INVALID_FORMAT;
        }
        // For try-import, we ignore it if we couldn't find a file.
        BAZEL_LOG(INFO) << "Skipped optional import of " << import_filename
                        << ", the specified rc file either does not exist or"
                        << "is not readable.";
        continue;
      }

      import_filename = resolved_filename.value();
    }

    if (absl::c_linear_search(import_stack, import_filename)) {
      std::string loop;
      for (const std::string& imported_rc : import_stack) {
        absl::StrAppend(&loop, "  ", imported_rc, "\n");
      }
      absl::StrAppend(&loop, "  ", import_filename, "\n");  // Include the loop.
      *error_text = absl::StrCat("Import loop detected:\n", loop);
      return ParseError::IMPORT_LOOP;
    }

    import_stack.push_back(import_filename);
    if (ParseError parse_error =
            ParseFile(import_filename, workspace, workspace_layout, read_file,
                      canonicalize_path, import_stack, error_text);
        parse_error != ParseError::NONE) {
      if (parse_error == ParseError::UNREADABLE_FILE &&
          command == kCommandTryImport) {
        // For try-import, we ignore it if we couldn't find a file.
        BAZEL_LOG(INFO) << "Skipped optional import of " << import_filename
                        << ", the specified rc file either does not exist or "
                           "is not readable.";
        *error_text = "";
      } else {
        // Files that are there but are malformed or introduce a loop are
        // still a problem, though, so perpetuate those errors as we would
        // for a normal import statement.
        return parse_error;
      }
    }
    import_stack.pop_back();
  }

  return ParseError::NONE;
}

bool RcFile::ReadFileDefault(const std::string& filename, std::string* contents,
                             std::string* error_msg) {
  return blaze_util::ReadFile(filename, contents, error_msg);
}

std::string RcFile::CanonicalizePathDefault(const std::string& filename) {
  return blaze_util::MakeCanonical(filename.c_str());
}

}  // namespace blaze
