// Copyright 2016 The Bazel Authors. All rights reserved.
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

/**
 * This is PID 1 inside the sandbox environment and runs in a separate user,
 * mount, UTS, IPC and PID namespace.
 */

#include "src/main/tools/linux-sandbox-pid1.h"

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <libgen.h>
#include <math.h>
#include <mntent.h>
#include <net/if.h>
#include <pwd.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <string>
#include <unordered_set>

#ifndef MS_REC
// Some systems do not define MS_REC in sys/mount.h. We might be able to grab it
// from linux/fs.h instead (cf. #2667).
#include <linux/fs.h>
#endif

#ifndef TEMP_FAILURE_RETRY
// Some C standard libraries like musl do not define this macro, so we'll
// include our own version for compatibility.
#define TEMP_FAILURE_RETRY(exp)            \
  ({                                       \
    decltype(exp) _rc;                     \
    do {                                   \
      _rc = (exp);                         \
    } while (_rc == -1 && errno == EINTR); \
    _rc;                                   \
  })
#endif  // TEMP_FAILURE_RETRY

#include "src/main/tools/linux-sandbox-options.h"
#include "src/main/tools/linux-sandbox.h"
#include "src/main/tools/logging.h"
#include "src/main/tools/process-tools.h"


static int global_child_pid;

// Helper methods
static void CreateFile(const char *path) {
  int handle = open(path, O_CREAT | O_WRONLY | O_TRUNC | O_NOFOLLOW, 0666);
  if (handle < 0) {
    DIE("open");
  }
  if (close(handle) < 0) {
    DIE("close");
  }
}

// Creates an empty file at 'path' by hard linking it from a known empty file.
// This is over two times faster than creating empty files via open() on
// certain filesystems (e.g. XFS).
static void LinkFile(const char *path) {
  if (link("tmp/empty_file", path) < 0) {
    if (errno == EEXIST) {
      // Try to remove the existing file first.
      if (unlink(path) < 0) {
        DIE("unlink %s", path);
      }
      if (link("tmp/empty_file", path) == 0) {
        return;
      }
    }
    DIE("link %s", path);
  }
}

// Recursively creates the file or directory specified in "path" and its parent
// directories.
// Return -1 on failure and sets errno to:
//    EINVAL   path is null
//    ENOTDIR  path exists and is not a directory
//    EEXIST   path exists and is a directory
//    ENOENT   stat call with the path failed
static int CreateTarget(const char *path, bool is_directory) {
  if (path == NULL) {
    errno = EINVAL;
    return -1;
  }

  struct stat sb;
  // If the path already exists...

  if (stat(path, &sb) == 0) {
    if (is_directory && S_ISDIR(sb.st_mode)) {
      // and it's a directory and supposed to be a directory, we're done here.
      return 0;
    } else if (!is_directory && S_ISREG(sb.st_mode)) {
      // and it's a regular file and supposed to be one, we're done here.
      return 0;
    } else {
      // otherwise something is really wrong.
      errno = is_directory ? ENOTDIR : EEXIST;
      return -1;
    }
  } else {
    // If stat failed because of any error other than "the path does not exist",
    // this is an error.
    if (errno != ENOENT) {
      return -1;
    }
  }

  // Create the parent directory.
  {
    char *buf, *dir;

    if (!(buf = strdup(path))) DIE("strdup");

    dir = dirname(buf);
    if (CreateTarget(dir, true) < 0) {
      DIE("CreateTarget %s", dir);
    }

    free(buf);
  }

  if (is_directory) {
    if (mkdir(path, 0755) < 0) {
      DIE("mkdir(%s)", path);
    }
  } else {
    LinkFile(path);
  }

  return 0;
}

static void SetupSelfDestruction(int *pipe_to_parent) {
  // We could also poll() on the pipe fd to find out when the parent goes away,
  // and rely on SIGCHLD interrupting that otherwise. That might require us to
  // install some trivial handler for SIGCHLD. Using O_ASYNC to turn the pipe
  // close into SIGIO may also work. Another option is signalfd, although that's
  // almost as obscure as this prctl.
  if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) {
    DIE("prctl");
  }

  // Switch to a new process group, otherwise our process group will still refer
  // to the outer PID namespace. We might then accidentally kill our parent by a
  // call to e.g. `kill(0, sig)`.
  if (setpgid(0, 0) < 0) {
    DIE("setpgid");
  }

  // Verify that the parent still lives.
  SignalPipe(pipe_to_parent);
}

static void SetupMountNamespace() {
  // Fully isolate our mount namespace private from outside events, so that
  // mounts in the outside environment do not affect our sandbox.
  if (mount(nullptr, "/", nullptr, MS_REC | MS_PRIVATE, nullptr) < 0) {
    DIE("mount");
  }
}

static void SetupUserNamespace() {
  // Disable needs for CAP_SETGID.
  struct stat sb;
  if (stat("/proc/self/setgroups", &sb) == 0) {
    WriteFile("/proc/self/setgroups", "deny");
  } else {
    // Ignore ENOENT, because older Linux versions do not have this file (but
    // also do not require writing to it).
    if (errno != ENOENT) {
      DIE("stat(/proc/self/setgroups");
    }
  }

  uid_t inner_uid;
  gid_t inner_gid;
  if (opt.fake_root) {
    // Change our username to 'root'.
    inner_uid = 0;
    inner_gid = 0;
  } else if (opt.fake_username) {
    // Change our username to 'nobody'.
    struct passwd *pwd = getpwnam("nobody");
    if (pwd == nullptr) {
      DIE("unable to find passwd entry for user nobody")
    }

    inner_uid = pwd->pw_uid;
    inner_gid = pwd->pw_gid;
  } else {
    // Do not change the username inside the sandbox.
    inner_uid = global_outer_uid;
    inner_gid = global_outer_gid;
  }
  if (opt.enable_pty) {
    // Change the group to "tty" regardless of what was previously set
    struct group grp;
    char buf[256];
    size_t buflen = sizeof(buf);
    struct group *result;
    getgrnam_r("tty", &grp, buf, buflen, &result);
    if (result == nullptr) {
      DIE("getgrnam_r");
    }
    inner_gid = grp.gr_gid;
  }

  WriteFile("/proc/self/uid_map", "%u %u 1\n", inner_uid, global_outer_uid);
  WriteFile("/proc/self/gid_map", "%u %u 1\n", inner_gid, global_outer_gid);
}

static void SetupUtsNamespace() {
  if (sethostname("localhost", 9) < 0) {
    DIE("sethostname");
  }

  if (setdomainname("localdomain", 11) < 0) {
    DIE("setdomainname");
  }
}

static void MountFilesystems() {
  // An attempt to mount the sandbox in tmpfs will always fail, so this block is
  // slightly redundant with the next mount() check, but dumping the mount()
  // syscall is incredibly cryptic, so we explicitly check against and warn
  // about attempts to use tmpfs.
  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    if (opt.working_dir.find(tmpfs_dir) == 0) {
      DIE("The sandbox working directory cannot be below a path where we mount "
          "tmpfs (you requested mounting %s in %s). Is your --output_base= "
          "below one of your --sandbox_tmpfs_path values?",
          opt.working_dir.c_str(), tmpfs_dir.c_str());
    }
  }

  std::unordered_set<std::string> bind_mount_sources;

  for (size_t i = 0; i < opt.bind_mount_sources.size(); i++) {
    const std::string &source = opt.bind_mount_sources.at(i);
    bind_mount_sources.insert(source);
    const std::string &target = opt.bind_mount_targets.at(i);
    PRINT_DEBUG("bind mount: %s -> %s", source.c_str(), target.c_str());
    if (mount(source.c_str(), target.c_str(), nullptr, MS_BIND | MS_REC,
              nullptr) < 0) {
      DIE("mount(%s, %s, nullptr, MS_BIND | MS_REC, nullptr)", source.c_str(),
          target.c_str());
    }
  }

  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    PRINT_DEBUG("tmpfs: %s", tmpfs_dir.c_str());
    if (mount("tmpfs", tmpfs_dir.c_str(), "tmpfs",
              MS_NOSUID | MS_NODEV | MS_NOATIME, nullptr) < 0) {
      DIE("mount(tmpfs, %s, tmpfs, MS_NOSUID | MS_NODEV | MS_NOATIME, nullptr)",
          tmpfs_dir.c_str());
    }
  }

  for (const std::string &writable_file : opt.writable_files) {
    PRINT_DEBUG("writable: %s", writable_file.c_str());
    if (bind_mount_sources.find(writable_file) != bind_mount_sources.end()) {
      // Bind mount sources contained in writable_files will be kept writable in
      // MakeFileSystemMostlyReadOnly, but have already been mounted at this
      // point.
      continue;
    }
    if (mount(writable_file.c_str(), writable_file.c_str(), nullptr,
              MS_BIND | MS_REC, nullptr) < 0) {
      DIE("mount(%s, %s, nullptr, MS_BIND | MS_REC, nullptr)",
          writable_file.c_str(), writable_file.c_str());
    }
  }

  // Make sure that the working directory is writable (unlike most of the rest
  // of the file system, which is read-only by default). The easiest way to do
  // this is by bind-mounting it upon itself.
  PRINT_DEBUG("working dir: %s", opt.working_dir.c_str());

  if (mount(opt.working_dir.c_str(), opt.working_dir.c_str(), nullptr, MS_BIND,
            nullptr) < 0) {
    DIE("mount(%s, %s, nullptr, MS_BIND, nullptr)", opt.working_dir.c_str(),
        opt.working_dir.c_str());
  }
}

// We later remount everything read-only, except the paths for which this method
// returns true.
static bool ShouldBeWritable(const std::string &mnt_dir) {
  if (mnt_dir == opt.working_dir) {
    return true;
  }

  if (opt.enable_pty && mnt_dir == "/dev/pts") {
    return true;
  }

  for (const std::string &writable_file : opt.writable_files) {
    if (mnt_dir == writable_file) {
      return true;
    }
  }

  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    if (mnt_dir == tmpfs_dir) {
      return true;
    }
  }

  return false;
}

// Makes the whole filesystem read-only, except for the paths for which
// ShouldBeWritable returns true.
static void MakeFilesystemMostlyReadOnly() {
  FILE *mounts = setmntent("/proc/self/mounts", "r");
  if (mounts == nullptr) {
    DIE("setmntent");
  }

  struct mntent *ent;
  while ((ent = getmntent(mounts)) != nullptr) {
    int mountFlags = MS_BIND | MS_REMOUNT;

    // MS_REMOUNT does not allow us to change certain flags. This means, we have
    // to first read them out and then pass them in back again. There seems to
    // be no better way than this (an API for just getting the mount flags of a
    // mount entry as a bitmask would be great).
    if (hasmntopt(ent, "nodev") != nullptr) {
      mountFlags |= MS_NODEV;
    }
    if (hasmntopt(ent, "noexec") != nullptr) {
      mountFlags |= MS_NOEXEC;
    }
    if (hasmntopt(ent, "nosuid") != nullptr) {
      mountFlags |= MS_NOSUID;
    }
    if (hasmntopt(ent, "noatime") != nullptr) {
      mountFlags |= MS_NOATIME;
    }
    if (hasmntopt(ent, "nodiratime") != nullptr) {
      mountFlags |= MS_NODIRATIME;
    }
    if (hasmntopt(ent, "relatime") != nullptr) {
      mountFlags |= MS_RELATIME;
    }

    if (!ShouldBeWritable(ent->mnt_dir)) {
      mountFlags |= MS_RDONLY;
    }

    PRINT_DEBUG("remount %s: %s", (mountFlags & MS_RDONLY) ? "ro" : "rw",
                ent->mnt_dir);
    if (mount(nullptr, ent->mnt_dir, nullptr, mountFlags, nullptr) < 0) {
      // If we get EACCES or EPERM, this might be a mount-point for which we
      // don't have read access. Not much we can do about this, but it also
      // won't do any harm, so let's go on. The same goes for EINVAL or ENOENT,
      // which are fired in case a later mount overlaps an earlier mount, e.g.
      // consider the case of /proc, /proc/sys/fs/binfmt_misc and /proc, with
      // the latter /proc being the one that an outer sandbox has mounted on
      // top of its parent /proc. In that case, we're not allowed to remount
      // /proc/sys/fs/binfmt_misc, because it is hidden. If we get ESTALE, the
      // mount is a broken NFS mount. In the ideal case, the user would either
      // fix or remove that mount, but in cases where that's not possible, we
      // should just ignore it. Similarly, one can get ENODEV in case of
      // autofs/automount failure.
      switch (errno) {
        case EACCES:
        case EPERM:
        case EINVAL:
        case ENOENT:
        case ESTALE:
        case ENODEV:
          PRINT_DEBUG(
              "remount(nullptr, %s, nullptr, %d, nullptr) failure (%m) ignored",
              ent->mnt_dir, mountFlags);
          break;
        default:
          DIE("remount(nullptr, %s, nullptr, %d, nullptr)", ent->mnt_dir,
              mountFlags);
      }
    }
  }

  endmntent(mounts);
}

static void MountProcAndSys() {
  // Mount a new proc on top of the old one, because the old one still refers to
  // our parent PID namespace.
  if (mount("/proc", "/proc", "proc", MS_NODEV | MS_NOEXEC | MS_NOSUID,
            nullptr) < 0) {
    DIE("mount /proc");
  }

  if (opt.create_netns == NO_NETNS) {
    return;
  }

  // Same for sys, but only if a separate network namespace was requested.
  if (mount("none", "/sys", "sysfs",
            MS_NOEXEC | MS_NOSUID | MS_NODEV | MS_RDONLY, nullptr) < 0) {
    DIE("mount /sys");
  }
}

static void SetupNetworking() {
  // When running in a separate network namespace, enable the loopback interface
  // because some application may want to use it.
  if (opt.create_netns == NETNS_WITH_LOOPBACK) {
    int fd;
    fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
      DIE("socket");
    }

    struct ifreq ifr = {};
    strncpy(ifr.ifr_name, "lo", IF_NAMESIZE);

    // Verify that name is valid.
    if (if_nametoindex(ifr.ifr_name) == 0) {
      DIE("if_nametoindex");
    }

    // Enable the interface.
    ifr.ifr_flags |= IFF_UP;
    if (ioctl(fd, SIOCSIFFLAGS, &ifr) < 0) {
      DIE("ioctl");
    }

    if (close(fd) < 0) {
      DIE("close");
    }
  }

  if (opt.create_netns != NO_NETNS && opt.fake_root) {
    // Allow IPPROTO_ICMP sockets when already allowed outside of the namespace.
    // In a namespace, /proc/sys/net/ipv4/ping_group_range is reset to the
    // default of 1 0, which does not match any groups. However, it can only be
    // overridden when the namespace has a fake root. This may be a kernel bug.
    WriteFile("/proc/sys/net/ipv4/ping_group_range", "0 0");
  }
}

static void EnterWorkingDirectory() {
  std::string path = opt.working_dir;
  if (opt.hermetic) {
    path = path.substr(opt.sandbox_root.size() + 1);
  }

  if (chdir(path.c_str()) < 0) {
    DIE("chdir(%s)", path.c_str());
  }
}

static void ForwardSignal(int signum) {
  kill(-global_child_pid, signum);
}

static void SpawnChild() {
  PRINT_DEBUG("calling fork...");
  global_child_pid = fork();

  if (global_child_pid < 0) {
    DIE("fork()");
  } else if (global_child_pid == 0) {
    // Put the child into its own process group.
    if (setpgid(0, 0) < 0) {
      DIE("setpgid");
    }

    // Try to assign our terminal to the child process.
    if (tcsetpgrp(STDIN_FILENO, getpgrp()) < 0 && errno != ENOTTY) {
      DIE("tcsetpgrp");
    }

    // Unblock all signals, restore default handlers.
    ClearSignalMask();

    // Close the file PRINT_DEBUG writes to.
    // Must happen late enough so we don't lose any debugging output.
    if (global_debug) {
      fclose(global_debug);
      global_debug = nullptr;
    }

    // Force umask to include read and execute for everyone, to make output
    // permissions predictable.
    umask(022);

    // argv[] passed to execve() must be a null-terminated array.
    opt.args.push_back(nullptr);

    if (execvp(opt.args[0], opt.args.data()) < 0) {
      DIE("execvp(%s, %p)", opt.args[0], opt.args.data());
    }
  } else {
    PRINT_DEBUG("child started with PID %d", global_child_pid);
  }
}

static int WaitForChild() {
  while (true) {
    // Wait for some process to exit. This includes reparented processes in our
    // PID namespace.
    int status;
    const pid_t pid = TEMP_FAILURE_RETRY(wait(&status));

    if (pid < 0) {
      // We don't expect any errors besides EINTR. In particular, ECHILD should
      // be impossible because we haven't yet seen global_child_pid exit.
      DIE("wait");
    }

    PRINT_DEBUG("wait returned pid=%d, status=0x%02x", pid, status);

    // If this isn't our child's PID, there's nothing further to do; we've
    // successfully reaped a zombie.
    if (pid != global_child_pid) {
      continue;
    }

    // If the child exited due to a signal, log that fact and exit with the same
    // status.
    if (WIFSIGNALED(status)) {
      const int signal = WTERMSIG(status);
      PRINT_DEBUG("child exited due to signal %d", WTERMSIG(status));
      return 128 + signal;
    }

    // Otherwise it must have exited normally.
    const int exit_code = WEXITSTATUS(status);
    PRINT_DEBUG("child exited normally with code %d", exit_code);
    return exit_code;
  }
}

static void MountSandboxAndGoThere() {
  if (mount(opt.sandbox_root.c_str(), opt.sandbox_root.c_str(), nullptr,
            MS_BIND | MS_NOSUID, nullptr) < 0) {
    DIE("mount");
  }
  if (chdir(opt.sandbox_root.c_str()) < 0) {
    DIE("chdir(%s)", opt.sandbox_root.c_str());
  }
}

static void CreateEmptyFile() {
  // This is used as the base for bind mounting.
  if (CreateTarget("tmp", true) < 0) {
    DIE("CreateTarget tmp")
  }
  CreateFile("tmp/empty_file");
}

static void MountDev() {
  if (CreateTarget("dev", true) < 0) {
    DIE("CreateTarget /dev");
  }
  const char *devs[] = {"/dev/null", "/dev/random", "/dev/urandom", "/dev/zero",
                        NULL};
  for (int i = 0; devs[i] != NULL; i++) {
    LinkFile(devs[i] + 1);
    if (mount(devs[i], devs[i] + 1, NULL, MS_BIND, NULL) < 0) {
      DIE("mount");
    }
  }
  if (symlink("/proc/self/fd", "dev/fd") < 0) {
    DIE("symlink");
  }
}

static void MountAllMounts() {
  for (const std::string &tmpfs_dir : opt.tmpfs_dirs) {
    PRINT_DEBUG("tmpfs: %s", tmpfs_dir.c_str());
    if (mount("tmpfs", tmpfs_dir.c_str(), "tmpfs",
              MS_NOSUID | MS_NODEV | MS_NOATIME, nullptr) < 0) {
      DIE("mount(tmpfs, %s, tmpfs, MS_NOSUID | MS_NODEV | MS_NOATIME, nullptr)",
          tmpfs_dir.c_str());
    }
  }

  // Make sure that the working directory is writable (unlike most of the rest
  // of the file system, which is read-only by default). The easiest way to do
  // this is by bind-mounting it upon itself.
  if (mount(opt.working_dir.c_str(), opt.working_dir.c_str(), nullptr, MS_BIND,
            nullptr) < 0) {
    DIE("mount(%s, %s, nullptr, MS_BIND, nullptr)", opt.working_dir.c_str(),
        opt.working_dir.c_str());
  }

  for (int i = 0; i < (signed)opt.bind_mount_sources.size(); i++) {
    if (global_debug) {
      if (strcmp(opt.bind_mount_sources[i].c_str(),
                 opt.bind_mount_targets[i].c_str()) == 0) {
        // The file is mounted to the same path inside the sandbox, as outside
        // (e.g. /home/user -> <sandbox>/home/user), so we'll just show a
        // simplified version of the mount command.
        PRINT_DEBUG("mount: %s\n", opt.bind_mount_sources[i].c_str());
      } else {
        // The file is mounted to a custom location inside the sandbox.
        // Create a user-friendly string for the sandboxed path and show it.
        const std::string user_friendly_mount_target("<sandbox>" +
                                                     opt.bind_mount_targets[i]);
        PRINT_DEBUG("mount: %s -> %s\n", opt.bind_mount_sources[i].c_str(),
                    user_friendly_mount_target.c_str());
      }
    }
    const std::string full_sandbox_path(opt.sandbox_root +
                                        opt.bind_mount_targets[i]);

    struct stat sb;
    if (stat(opt.bind_mount_sources[i].c_str(), &sb) < 0) {
      DIE("stat");
    }
    bool IsDirectory = S_ISDIR(sb.st_mode);
    if (CreateTarget(full_sandbox_path.c_str(), IsDirectory) < 0) {
      DIE("CreateTarget %s", full_sandbox_path.c_str());
    }
    int result =
        mount(opt.bind_mount_sources[i].c_str(), full_sandbox_path.c_str(),
              NULL, MS_REC | MS_BIND | MS_RDONLY, NULL);
    if (result != 0) {
      DIE("mount");
    }
  }
  for (const std::string &writable_file : opt.writable_files) {
    PRINT_DEBUG("writable: %s", writable_file.c_str());
    if (mount(writable_file.c_str(), writable_file.c_str(), nullptr,
              MS_BIND | MS_REC, nullptr) < 0) {
      DIE("mount(%s, %s, nullptr, MS_BIND | MS_REC, nullptr)",
          writable_file.c_str(), writable_file.c_str());
    }
  }
}

static void ChangeRoot() {
  // move the real root to old_root, then detach it
  char old_root[16] = "old-root-XXXXXX";
  if (mkdtemp(old_root) == NULL) {
    perror("mkdtemp");
    DIE("mkdtemp returned NULL\n");
  }
  // pivot_root has no wrapper in libc, so we need syscall()
  if (syscall(SYS_pivot_root, ".", old_root) < 0) {
    DIE("syscall");
  }
  if (chroot(".") < 0) {
    DIE("chroot");
  }
  if (umount2(old_root, MNT_DETACH) < 0) {
    DIE("umount2");
  }
  if (rmdir(old_root) < 0) {
    DIE("rmdir");
  }
}

int Pid1Main(void *args) {
  PRINT_DEBUG("Pid1Main started");

  Pid1Args pid1Args = *(static_cast<Pid1Args *>(args));

  if (getpid() != 1) {
    DIE("Using PID namespaces, but we are not PID 1");
  }

  // Before pid1 spawns a child pid2, we want to wait for the parent process to
  // move pid1 to the cgroup so that pid2 will be created in the same cgroup.
  WaitPipe(pid1Args.pipe_from_parent);

  // Start with default signal handlers and an empty signal mask.
  ClearSignalMask();

  SetupSelfDestruction(pid1Args.pipe_to_parent);

  // Sandbox ourselves.
  SetupMountNamespace();
  SetupUserNamespace();
  if (opt.fake_hostname) {
    SetupUtsNamespace();
  }

  if (opt.hermetic) {
    MountSandboxAndGoThere();
    CreateEmptyFile();
    MountDev();
    MountProcAndSys();
    MountAllMounts();
    ChangeRoot();
  } else {
    MountFilesystems();
    MakeFilesystemMostlyReadOnly();
    MountProcAndSys();
  }
  SetupNetworking();
  EnterWorkingDirectory();

  // Ignore terminal signals; we hand off the terminal to the child in
  // SpawnChild below.
  IgnoreSignal(SIGTTIN);
  IgnoreSignal(SIGTTOU);

  // Fork the child process.
  SpawnChild();

  // Forward requests to shut down gracefully to the child.
  InstallSignalHandler(SIGTERM, ForwardSignal);

  // Note that there's no need to kill any remaining descendant processes; they
  // are in our PID namespace and the kernel will send them SIGKILL
  // automatically once we exit.
  return WaitForChild();
}
