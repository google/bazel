// Copyright 2014 The Bazel Authors. All rights reserved.
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

#ifndef BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_H_
#define BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_H_

#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "src/main/cpp/rc_file.h"
#include "src/main/cpp/startup_options.h"
#include "src/main/cpp/util/exit_code.h"

namespace blaze {

class WorkspaceLayout;

// Broken down structure of the command line into logical components. The raw
// arguments should not be referenced after this structure exists. This
// breakdown should suffice to access the parts of the command line that the
// client cares about, notably the binary and startup startup options.
struct CommandLine {
  std::string path_to_binary;
  std::vector<std::string> startup_args;
  std::string command;
  std::vector<std::string> command_args;

  CommandLine(std::string path_to_binary_arg,
              std::vector<std::string> startup_args_arg,
              std::string command_arg,
              std::vector<std::string> command_args_arg)
      : path_to_binary(std::move(path_to_binary_arg)),
        startup_args(std::move(startup_args_arg)),
        command(std::move(command_arg)),
        command_args(std::move(command_args_arg)) {}
};

// Represents an option parsed from a blazerc file.
struct BlazercOption {
  std::string source_path;
  std::string command;
  std::string option;
};

// This class is responsible for parsing the command line of the Blaze binary,
// parsing blazerc files, and putting together the command that should be sent
// to the server.
class OptionProcessor {
 public:
  OptionProcessor(const WorkspaceLayout* workspace_layout,
                  std::unique_ptr<StartupOptions> default_startup_options);

  OptionProcessor(const WorkspaceLayout* workspace_layout,
                  std::unique_ptr<StartupOptions> default_startup_options,
                  const std::string& system_bazelrc_path);

  virtual ~OptionProcessor() {}

  // Returns the lower-case product name associated with this options processor.
  std::string GetLowercaseProductName() const;

  // Splits the arguments of a command line invocation.
  //
  // For instance:
  // output/bazel --foo --bar=42 --bar blah build --myflag value :mytarget
  //
  // returns a CommandLine structure with the following values:
  // result.path_to_binary = "output/bazel"
  // result.startup_args = {"--foo", "--bar=42", "--bar=blah"}
  // result.command = "build"
  // result.command_args = {"--myflag", "value", ":mytarget"}
  //
  // Note that result.startup_args is guaranteed to contain only valid
  // startup options (w.r.t. StartupOptions::IsUnary and
  // StartupOptions::IsNullary) and unary startup args of the form '--bar blah'
  // are rewritten as '--bar=blah' for uniformity.
  // In turn, the command and command args are not rewritten nor validated.
  //
  // If the method fails then error will contain the cause, otherwise error
  // remains untouched.
  virtual std::unique_ptr<CommandLine> SplitCommandLine(
      std::vector<std::string> args, std::string* error) const;

  // Parse a command line and the appropriate blazerc files and stores the
  // results. This should be invoked only once per OptionProcessor object.
  blaze_exit_code::ExitCode ParseOptions(const std::vector<std::string>& args,
                                         const std::string& workspace,
                                         const std::string& cwd,
                                         std::string* error);

  // Get the Blaze command to be executed.
  // Returns an empty string if no command was found on the command line.
  std::string GetCommand() const;

  // Gets the arguments to the command. This is put together from the default
  // options specified in the blazerc file(s), the command line, and various
  // bits and pieces of information about the environment the blaze binary is
  // executed in.
  std::vector<std::string> GetCommandArguments() const;

  // Gets the arguments explicitly provided by the user's command line.
  std::vector<std::string> GetExplicitCommandArguments() const;

  // Gets the options extracted from the blazerc files. Must only be called
  // after ParseOptions.
  std::vector<BlazercOption> GetParsedBlazercOptions() const;

  // Returns the underlying StartupOptions object with parsed values. Must
  // only be called after ParseOptions.
  virtual StartupOptions* GetParsedStartupOptions() const;

  // Prints a message about the origin of startup options. This should be called
  // if the server is not started or called, in case the options are related to
  // the failure. Otherwise, the server will handle any required logging.
  void PrintStartupOptionsProvenanceMessage() const;

  // Parse the files in `blazercs` and return all options that need to be passed
  // to the server. The options are returned in the order they should be appear
  // on the command line (later options have precedence over earlier ones).
  static std::vector<BlazercOption> GetBlazercOptions(
      const std::string& cwd, const std::vector<RcFile*>& blazercs);

  // Constructs all synthetic command args that should be passed to the
  // server to configure blazerc options and client environment.
  static std::vector<std::string> GetBlazercAndEnvCommandArgs(
      const std::string& cwd, const std::vector<BlazercOption>& blazerc_options,
      const std::vector<std::string>& env);

  // Finds and parses the appropriate RcFiles:
  //   - system rc (unless --nosystem_rc)
  //   - workspace, %workspace%/.bazelrc (unless --noworkspace_rc, or we aren't
  //     in a workspace directory, indicated by an empty workspace parameter)
  //   - user, $HOME/.bazelrc (unless --nohome_rc)
  //   - command-line provided, if a value is passed with --bazelrc.
  virtual blaze_exit_code::ExitCode GetRcFiles(
      const WorkspaceLayout* workspace_layout, const std::string& workspace,
      const std::string& cwd, const CommandLine* cmd_line,
      std::vector<std::unique_ptr<RcFile>>* result_rc_files,
      std::string* error) const;

 private:
  blaze_exit_code::ExitCode ParseStartupOptions(
      const std::vector<RcFile*>& rc_files, std::string* error);

  // An ordered list of command args that contain information about the
  // execution environment and the flags passed via the bazelrc files.
  std::vector<std::string> blazerc_and_env_command_args_;

  // After calling ParseOptions, this contains a list of options that originate
  // from bazelrc files. If multiple options are specified for the same command,
  // they are in the order they appear in the files.
  std::vector<BlazercOption> parsed_blazercs_;

  // The command line constructed after calling ParseOptions.
  std::unique_ptr<CommandLine> cmd_line_;

  const WorkspaceLayout* workspace_layout_;

  // The StartupOptions object defining the startup options which are accepted,
  // and, after ParseOptions has been called, their values.
  const std::unique_ptr<StartupOptions> startup_options_;

  // Whether or not ParseOptions has been called.
  bool parse_options_called_;

  // Path to the system-wide bazelrc configuration file.
  // This is configurable for testing purposes only.
  const std::string system_bazelrc_path_;
};

// Parses and returns the contents of the rc file.
blaze_exit_code::ExitCode ParseRcFile(const WorkspaceLayout* workspace_layout,
                                      const std::string& workspace,
                                      const std::string& rc_file_path,
                                      std::unique_ptr<RcFile>* result_rc_file,
                                      std::string* error);

}  // namespace blaze

#endif  // BAZEL_SRC_MAIN_CPP_OPTION_PROCESSOR_H_
