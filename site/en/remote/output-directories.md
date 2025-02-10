Project: /_project.yaml
Book: /_book.yaml

# Output Directory Layout

{% include "_buttons.html" %}

This page covers requirements and layout for output directories.

## Requirements {:#requirements}

Requirements for an output directory layout:

* Doesn't collide if multiple users are building on the same box.
* Supports building in multiple workspaces at the same time.
* Supports building for multiple target configurations in the same workspace.
* Doesn't collide with any other tools.
* Is easy to access.
* Is easy to clean, even selectively.
* Is unambiguous, even if the user relies on symbolic links when changing into
  their client directory.
* All the build state per user should be underneath one directory ("I'd like to
  clean all the .o files from all my clients.")

## Current layout {:#layout}

The solution that's currently implemented:

* Bazel must be invoked from a directory containing a repo boundary file, or a
  subdirectory thereof. In other words, Bazel must be invoked from inside a
  [repository](../external/overview#repository). Otherwise, an error is
  reported.
* The _outputRoot_ directory defaults to `${XDG_CACHE_HOME}/bazel` (or
  `~/.cache/bazel`, if the `XDG_CACHE_HOME` environment variable is not set) on
  Linux, `/private/var/tmp` on macOS, and on Windows it defaults to `%HOME%` if
  set, else `%USERPROFILE%` if set, else the result of calling
  `SHGetKnownFolderPath()` with the `FOLDERID_Profile` flag set. If the
  environment variable `$TEST_TMPDIR` is set, as in a test of Bazel itself,
  then that value overrides the default.
* The Bazel user's build state is located beneath `outputRoot/_bazel_$USER`.
  This is called the _outputUserRoot_ directory.
* Beneath the `outputUserRoot` directory there is an `install` directory, and in
  it is an `installBase` directory whose name is the MD5 hash of the Bazel
  installation manifest.
* Beneath the `outputUserRoot` directory, an `outputBase` directory
  is also created whose name is the MD5 hash of the path name of the workspace
  root. So, for example, if Bazel is running in the workspace root
  `/home/user/src/my-project` (or in a directory symlinked to that one), then
  an output base directory is created called:
  `/home/user/.cache/bazel/_bazel_user/7ffd56a6e4cb724ea575aba15733d113`. You
  can also run `echo -n $(pwd) | md5sum` in the workspace root to get the MD5.
* You can use Bazel's `--output_base` startup option to override the default
  output base directory. For example,
  `bazel --output_base=/tmp/bazel/output build x/y:z`.
* You can also use Bazel's `--output_user_root` startup option to override the
  default install base and output base directories. For example:
  `bazel --output_user_root=/tmp/bazel build x/y:z`.

The symlinks for "bazel-&lt;workspace-name&gt;", "bazel-out", "bazel-testlogs",
and "bazel-bin" are put in the workspace directory; these symlinks point to some
directories inside a target-specific directory inside the output directory.
These symlinks are only for the user's convenience, as Bazel itself does not
use them. Also, this is done only if the workspace root is writable.

## Layout diagram {:#layout-diagram}

The directories are laid out as follows:

<pre>
&lt;workspace-name&gt;/                         <== The workspace root
  bazel-my-project => <..._main>          <== Symlink to execRoot
  bazel-out => <...bazel-out>             <== Convenience symlink to outputPath
  bazel-bin => <...bin>                   <== Convenience symlink to most recent written bin dir $(BINDIR)
  bazel-testlogs => <...testlogs>         <== Convenience symlink to the test logs directory

/home/user/.cache/bazel/                  <== Root for all Bazel output on a machine: outputRoot
  _bazel_$USER/                           <== Top level directory for a given user depends on the user name:
                                              outputUserRoot
    install/
      fba9a2c87ee9589d72889caf082f1029/   <== Hash of the Bazel install manifest: installBase
        _embedded_binaries/               <== Contains binaries and scripts unpacked from the data section of
                                              the bazel executable on first run (such as helper scripts and the
                                              main Java file BazelServer_deploy.jar)
    7ffd56a6e4cb724ea575aba15733d113/     <== Hash of the client's workspace root (such as
                                              /home/user/src/my-project): outputBase
      action_cache/                       <== Action cache directory hierarchy
                                              This contains the persistent record of the file
                                              metadata (timestamps, and perhaps eventually also MD5
                                              sums) used by the FilesystemValueChecker.
      command.log                         <== A copy of the stdout/stderr output from the most
                                              recent bazel command.
      external/                           <== The directory that remote repositories are
                                              downloaded/symlinked into.
      server/                             <== The Bazel server puts all server-related files (such
                                              as socket file, logs, etc) here.
        jvm.out                           <== The debugging output for the server.
      execroot/                           <== The working directory for all actions. For special
                                              cases such as sandboxing and remote execution, the
                                              actions run in a directory that mimics execroot.
                                              Implementation details, such as where the directories
                                              are created, are intentionally hidden from the action.
                                              Every action can access its inputs and outputs relative
                                              to the execroot directory.
        _main/                            <== Working tree for the Bazel build & root of symlink forest: execRoot
          _bin/                           <== Helper tools are linked from or copied to here.

          bazel-out/                      <== All actual output of the build is under here: outputPath
            _tmp/actions/                 <== Action output directory. This contains a file with the
                                              stdout/stderr for every action from the most recent
                                              bazel run that produced output.
            local_linux-fastbuild/        <== one subdirectory per unique target BuildConfiguration instance;
                                              this is currently encoded
              bin/                        <== Bazel outputs binaries for target configuration here: $(BINDIR)
                foo/bar/_objs/baz/        <== Object files for a cc_* rule named //foo/bar:baz
                  foo/bar/baz1.o          <== Object files from source //foo/bar:baz1.cc
                  other_package/other.o   <== Object files from source //other_package:other.cc
                foo/bar/baz               <== foo/bar/baz might be the artifact generated by a cc_binary named
                                              //foo/bar:baz
                foo/bar/baz.runfiles/     <== The runfiles symlink farm for the //foo/bar:baz executable.
                  MANIFEST
                  _main/
                    ...
              genfiles/                   <== Bazel puts generated source for the target configuration here:
                                              $(GENDIR)
                foo/bar.h                     such as foo/bar.h might be a headerfile generated by //foo:bargen
              testlogs/                   <== Bazel internal test runner puts test log files here
                foo/bartest.log               such as foo/bar.log might be an output of the //foo:bartest test with
                foo/bartest.status            foo/bartest.status containing exit status of the test (such as
                                              PASSED or FAILED (Exit 1), etc)
            host/                         <== BuildConfiguration for build host (user's workstation), for
                                              building prerequisite tools, that will be used in later stages
                                              of the build (ex: Protocol Compiler)
        &lt;packages&gt;/                       <== Packages referenced in the build appear as if under a regular workspace
</pre>

The layout of the \*.runfiles directories is documented in more detail in the places pointed to by RunfilesSupport.

## `bazel clean`

`bazel clean` does an `rm -rf` on the `outputPath` and the `action_cache`
directory. It also removes the workspace symlinks. The `--expunge` option
will clean the entire outputBase.
