// Copyright 2019 The Bazel Authors. All rights reserved.
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

// daemonize [-a] -l log_path -p pid_path [-c cgroup] [-s systemd_wrapper_path]
// -- binary_path binary_name [args]
//
// daemonize spawns a program as a daemon, redirecting all of its output to the
// given log_path and writing the daemon's PID to pid_path.  binary_path
// specifies the full location of the program to execute and binary_name
// indicates its display name (aka argv[0], so the optional args do not have to
// specify it again).  log_path is created/truncated unless the -a (append) flag
// is specified.  Also note that pid_path is guaranteed to exists when this
// program terminates successfully.
//
// Some important details about the implementation of this program:
//
// * No threads to ensure the use of fork below does not cause trouble.
//
// * Pure C, no C++. This is intentional to keep the program low overhead
//   and to avoid the accidental introduction of heavy dependencies that
//   could spawn threads.
//
// * Error handling is extensive but there is no error propagation.  Given
//   that the goal of this program is just to spawn another one as a daemon,
//   we take the freedom to immediatey exit from anywhere as soon as we
//   hit an error.

#include <assert.h>
#include <err.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#include "src/main/tools/process-tools.h"

// Configures std{in,out,err} of the current process to serve as a daemon.
//
// stdin is configured to read from /dev/null.
//
// stdout and stderr are configured to write to log_path, which is created and
// truncated unless log_append is set to true, in which case it is open for
// append if it exists.
static void SetupStdio(const char* log_path, bool log_append) {
  close(STDIN_FILENO);
  int fd = open("/dev/null", O_RDONLY);
  if (fd == -1) {
    err(EXIT_FAILURE, "Failed to open /dev/null");
  }
  assert(fd == STDIN_FILENO);

  close(STDOUT_FILENO);
  int flags = O_WRONLY | O_CREAT | (log_append ? O_APPEND : O_TRUNC);
  fd = open(log_path, flags, 0666);
  if (fd == -1) {
    err(EXIT_FAILURE, "Failed to create log file %s", log_path);
  }
  assert(fd == STDOUT_FILENO);

  close(STDERR_FILENO);
  fd = dup(STDOUT_FILENO);
  if (fd == -1) {
    err(EXIT_FAILURE, "dup failed");
  }
  assert(fd == STDERR_FILENO);
}

// Writes the given pid to a new file at pid_path.
//
// Once the pid file has been created, this notifies pid_done_fd by writing a
// dummy character to it and closing it.
static void WritePidFile(pid_t pid, const char* pid_path, int pid_done_fd) {
  FILE* pid_file = fopen(pid_path, "w");
  if (pid_file == NULL) {
    err(EXIT_FAILURE, "Failed to create %s", pid_path);
  }
  if (fprintf(pid_file, "%d", pid) < 0) {
    err(EXIT_FAILURE, "Failed to write pid %d to %s", pid, pid_path);
  }
  if (fclose(pid_file) < 0) {
    err(EXIT_FAILURE, "Failed to write pid %d to %s", pid, pid_path);
  }

  char dummy = '\0';
  int ret = 0;
  while (ret == 0) {
    ret = write(pid_done_fd, &dummy, sizeof(dummy));
    if (ret == -1 && errno == EINTR) {
      ret = 0;
    }
  }
  if (ret != 1) {
    err(EXIT_FAILURE, "Failed to signal pid done");
  }
  close(pid_done_fd);
}

static bool ShellEscapeNeeded(const char* arg) {
  static const char kDontNeedShellEscapeChars[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
      "abcdefghijklmnopqrstuvwxyz"
      "0123456789+-_.=/:,@";

  for (int i = 0; arg[i]; i++) {
    if (strchr(kDontNeedShellEscapeChars, arg[i]) == NULL) {
      // If any character is not in the list, we need to escape the string.
      return true;
    }
  }
  return false;
}

// Similar to what absl::ShellEscape does. We'd like to escape the arguments
// passed to the shell script, but we cannot use absl::ShellEscape because we
// want to keep this tool pure C.
static char* ShellEscape(const char* arg) {
  if (!arg) {
    return NULL;
  }

  if (!ShellEscapeNeeded(arg)) {
    return strdup(arg);
  }

  bool has_single_quotes = false;
  for (size_t i = 0; arg[i]; i++) {
    if (arg[i] == '\'') {
      has_single_quotes = true;
      break;
    }
  }

  if (!has_single_quotes) {
    // When there are no single quotes, we can just escape the string with
    // single quotes.
    char* escaped_string;
    asprintf(&escaped_string, "'%s'", arg);
    return escaped_string;
  }

  // For all other cases, we wrap everything in double quotes.
  size_t escaped_len = 0;

  for (size_t i = 0; arg[i]; i++) {
    switch (arg[i]) {
      case '\\':
      case '$':
      case '"':
      case '`':
        escaped_len++;
    }
    escaped_len++;
  }

  char* escaped_string = (char*)malloc(
      escaped_len +
      3);  // +2 for double quotes, and +1 for the null terminator.

  size_t j = 0;
  escaped_string[j++] = '"';
  for (size_t i = 0; arg[i]; i++) {
    switch (arg[i]) {
      case '\\':
      case '$':
      case '"':
      case '`':
        escaped_string[j++] = '\\';
    }
    escaped_string[j++] = arg[i];
  }
  escaped_string[j++] = '"';
  escaped_string[j] = '\0';

  return escaped_string;
}

// Prepares the shell script for systemd-run to execute.
//
// We wrap everything inside a shell script file for systemd-run in order to
// create a transient cgroup for the Java server to use. With a user cgroup
// created like this, we can control the resources without root permission.
//
// We cannot do this directly with a command because we'll get a "Filename too
// long" error, which indicates that the command is too long as we have almost
// 100 arguments in the list.
static void WriteSystemdWrapper(const char* systemd_wrapper_path,
                                const char* exe, char** argv) {
  FILE* systemd_wrapper_fp = fopen(systemd_wrapper_path, "w");
  if (systemd_wrapper_fp == NULL) {
    err(EXIT_FAILURE, "Failed to create %s", systemd_wrapper_path);
  }

  char* escaped_argv0 = ShellEscape(argv[0]);
  if (fprintf(systemd_wrapper_fp, "#!/bin/bash\nexec -a %s %s", escaped_argv0,
              exe) < 0) {
    err(EXIT_FAILURE, "Failed to write content to %s", systemd_wrapper_path);
  }
  free(escaped_argv0);

  int argc = 1;
  while (argv[argc]) {
    char* escaped_arg = ShellEscape(argv[argc]);
    if (fprintf(systemd_wrapper_fp, " %s", escaped_arg) < 0) {
      err(EXIT_FAILURE, "Failed to write content to %s", systemd_wrapper_path);
    }
    free(escaped_arg);
    argc++;
  }

  if (fclose(systemd_wrapper_fp) < 0) {
    err(EXIT_FAILURE, "Failed to fclose file %s", systemd_wrapper_path);
  }
}

static bool IsBinaryExecutable(const char* binary_path) {
  return access(binary_path, X_OK) == 0;
}

static void ExecAsDaemon(const char* log_path, bool log_append,
                         const char* systemd_wrapper_path, int pid_done_fd,
                         const char* exe, char** argv)
    __attribute__((noreturn));

// Executes the requested binary configuring it to behave as a daemon.
//
// The stdout and stderr of the current process are redirected to the given
// log_path.  See SetupStdio for details on how this is handled.
//
// This blocks execution until pid_done_fd receives a write.  We do this
// because the Bazel server process (which is what we start with this helper
// binary) requires the PID file to be present at startup time so we must
// wait until the parent process has created it.
//
// This function never returns.
static void ExecAsDaemon(const char* log_path, bool log_append,
                         const char* systemd_wrapper_path, int pid_done_fd,
                         const char* exe, char** argv) {
  char dummy;
  if (read(pid_done_fd, &dummy, sizeof(dummy)) == -1) {
    err(EXIT_FAILURE, "Failed to wait for pid file creation");
  }
  close(pid_done_fd);

  if (signal(SIGHUP, SIG_IGN) == SIG_ERR) {
    err(EXIT_FAILURE, "Failed to install SIGHUP handler");
  }

  if (setsid() == -1) {
    err(EXIT_FAILURE, "setsid failed");
  }

  SetupStdio(log_path, log_append);

#ifdef __linux__
  // When it's running on linux, and the systemd wrapper path is provided, we
  // try to replace the current process with systemd-run. In all other cases,
  // including the cases when systemd-run is not available, we replace it with
  // the original exe.
  const char* systemd_run_path = "/usr/bin/systemd-run";
  if (systemd_wrapper_path != NULL && IsBinaryExecutable(systemd_run_path)) {
    // Even if systemd-run is present and executable, we still need to run a
    // command first to check if we can use it. There are some cases when the
    // environment is not set up correctly, e.g. no DBUS available.
    char* systemd_test_command;
    asprintf(&systemd_test_command, "%s --user --scope -- /bin/true",
             systemd_run_path);
    int status = system(systemd_test_command);
    free(systemd_test_command);

    if (status == 0) {
      WriteSystemdWrapper(systemd_wrapper_path, exe, argv);

      execl(systemd_run_path, systemd_run_path, "--user", "--scope", "--",
            "/bin/bash", systemd_wrapper_path, NULL);
      err(EXIT_FAILURE, "Failed to execute %s with systemd-run.", exe);
    }
  }

#endif

  execv(exe, argv);
  err(EXIT_FAILURE, "Failed to execute %s", exe);
}

#ifdef __linux__
// Moves the bazel server into the specified cgroup for all the discovered
// cgroups. This is useful when using the cgroup features in bazel and thus the
// server must be started in a user-writable cgroup. Users can specify a
// pre-setup cgroup where the server will be moved to. This is enabled by
// the --experimental_cgroup_parent startup flag.
static void MoveToCgroup(pid_t pid, const char* cgroup_path) {
  FILE* mounts_fp = fopen("/proc/self/mounts", "r");
  if (mounts_fp == NULL) {
    err(EXIT_FAILURE, "Failed to open /proc/self/mounts");
  }

  char* line = NULL;
  size_t len = 0;
  while (getline(&line, &len, mounts_fp) != -1) {
    char* saveptr;
    strtok_r(line, " ", &saveptr);
    char* fs_file = strtok_r(NULL, " ", &saveptr);
    char* fs_vfstype = strtok_r(NULL, " ", &saveptr);
    if (strcmp(fs_vfstype, "cgroup") == 0 ||
        strcmp(fs_vfstype, "cgroup2") == 0) {
      char* procs_path;
      asprintf(&procs_path, "%s%s/cgroup.procs", fs_file, cgroup_path);
      WriteFile(procs_path, "%d", pid);
      free(procs_path);
    }
  }
  free(line);
  fclose(mounts_fp);
}
#endif

// Starts the given process as a daemon.
//
// This spawns a subprocess that will be configured to run the desired program
// as a daemon.  The program to run is supplied in exe and the arguments to it
// are given in the NULL-terminated argv.  argv[0] must be present and
// contain the program name (which may or may not match the basename of exe).
static void Daemonize(const char* log_path, bool log_append,
                      const char* pid_path, const char* cgroup_path,
                      const char* systemd_wrapper_path, const char* exe,
                      char** argv) {
  assert(argv[0] != NULL);

  int pid_done_fds[2];
  if (pipe(pid_done_fds) == -1) {
    err(EXIT_FAILURE, "pipe failed");
  }

  pid_t pid = fork();
  if (pid == -1) {
    err(EXIT_FAILURE, "fork failed");
  } else if (pid == 0) {
    close(pid_done_fds[1]);
#ifdef __linux__
    if (cgroup_path != NULL) {
      MoveToCgroup(pid, cgroup_path);
    }
#endif
    ExecAsDaemon(log_path, log_append, systemd_wrapper_path, pid_done_fds[0],
                 exe, argv);
    abort();  // NOLINT Unreachable.
  }
  close(pid_done_fds[0]);

  WritePidFile(pid, pid_path, pid_done_fds[1]);
}

// Program entry point.
//
// The primary responsibility of this function is to parse program options.
// Once that is done, delegates all work to Daemonize.
int main(int argc, char** argv) {
  bool log_append = false;
  const char* log_path = NULL;
  const char* pid_path = NULL;
  const char* cgroup_path = NULL;
  const char* systemd_wrapper_path = NULL;
  int opt;
  while ((opt = getopt(argc, argv, ":al:p:c:s:")) != -1) {
    switch (opt) {
      case 'a':
        log_append = true;
        break;

      case 'l':
        log_path = optarg;
        break;

      case 'p':
        pid_path = optarg;
        break;

      case 'c':
        cgroup_path = optarg;
        break;

      case 's':
        systemd_wrapper_path = optarg;
        break;

      case ':':
        errx(EXIT_FAILURE, "Option -%c requires an argument", optopt);

      case '?':
      default:
        errx(EXIT_FAILURE, "Unknown option -%c", optopt);
    }
  }
  argc -= optind;
  argv += optind;

  if (log_path == NULL) {
    errx(EXIT_FAILURE, "Must specify a log file with -l");
  }
  if (pid_path == NULL) {
    errx(EXIT_FAILURE, "Must specify a pid file with -p");
  }

  if (argc < 2) {
    errx(EXIT_FAILURE, "Must provide at least an executable name and arg0");
  }
  Daemonize(log_path, log_append, pid_path, cgroup_path, systemd_wrapper_path,
            argv[0], argv + 1);
  return EXIT_SUCCESS;
}
