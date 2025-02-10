# pylint: disable=g-bad-file-header
# Copyright 2017 The Bazel Authors. All rights reserved.
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

import os
from absl.testing import absltest
from src.test.py.bazel import test_base


class RunfilesTest(test_base.TestBase):

  def _AssertRunfilesLibraryInBazelToolsRepo(self, family, lang_name):
    for s, t, exe in [("MODULE.bazel.mock", "MODULE.bazel",
                       False), ("foo/BUILD.mock", "foo/BUILD",
                                False), ("foo/foo.py", "foo/foo.py", True),
                      ("foo/Foo.java", "foo/Foo.java",
                       False), ("foo/foo.sh", "foo/foo.sh",
                                True), ("foo/foo.cc", "foo/foo.cc", False),
                      ("foo/datadep/hello.txt", "foo/datadep/hello.txt",
                       False), ("bar/BUILD.mock", "bar/BUILD",
                                False), ("bar/bar.py", "bar/bar.py", True),
                      ("bar/bar-py-data.txt", "bar/bar-py-data.txt",
                       False), ("bar/Bar.java", "bar/Bar.java",
                                False), ("bar/bar-java-data.txt",
                                         "bar/bar-java-data.txt", False),
                      ("bar/bar.sh", "bar/bar.sh",
                       True), ("bar/bar-sh-data.txt", "bar/bar-sh-data.txt",
                               False), ("bar/bar.cc", "bar/bar.cc",
                                        False), ("bar/bar-cc-data.txt",
                                                 "bar/bar-cc-data.txt", False)]:
      self.CopyFile(
          self.Rlocation("io_bazel/src/test/py/bazel/testdata/runfiles_test/" +
                         s), t, exe)

    _, stdout, _ = self.RunBazel(["info", "bazel-bin"])
    bazel_bin = stdout[0]

    self.RunBazel(["build", "--verbose_failures", "//foo:runfiles-" + family])

    if test_base.TestBase.IsWindows():
      bin_path = os.path.join(bazel_bin, "foo/runfiles-%s.exe" % family)
    else:
      bin_path = os.path.join(bazel_bin, "foo/runfiles-" + family)

    self.assertTrue(os.path.exists(bin_path))

    _, stdout, _ = self.RunProgram(
        [bin_path], env_add={"TEST_SRCDIR": "__ignore_me__"}
    )
    # 10 output lines: 2 from foo-<family>, and 2 from each of bar-<lang>.
    if len(stdout) != 10:
      self.fail("stdout: %s" % stdout)

    self.assertEqual(stdout[0], "Hello %s Foo!" % lang_name)
    self.assertRegex(stdout[1], "^rloc=.*/foo/datadep/hello.txt")
    self.assertNotIn("__ignore_me__", stdout[1])

    with open(stdout[1].split("=", 1)[1], "r") as f:
      lines = [l.strip() for l in f.readlines()]
    if len(lines) != 1:
      self.fail("lines: %s" % lines)
    self.assertEqual(lines[0], "world")

    i = 2
    for lang in [("py", "Python", "bar.py"), ("java", "Java", "Bar.java"),
                 ("sh", "Bash", "bar.sh"), ("cc", "C++", "bar.cc")]:
      self.assertEqual(stdout[i], "Hello %s Bar!" % lang[1])
      self.assertRegex(stdout[i + 1], "^rloc=.*/bar/bar-%s-data.txt" % lang[0])
      self.assertNotIn("__ignore_me__", stdout[i + 1])

      with open(stdout[i + 1].split("=", 1)[1], "r") as f:
        lines = [l.strip() for l in f.readlines()]
      if len(lines) != 1:
        self.fail("lines(%s): %s" % (lang[0], lines))
      self.assertEqual(lines[0], "data for " + lang[2])

      i += 2

  def testPythonRunfilesLibraryInBazelToolsRepo(self):
    self._AssertRunfilesLibraryInBazelToolsRepo("py", "Python")

  def testJavaRunfilesLibraryInBazelToolsRepo(self):
    self._AssertRunfilesLibraryInBazelToolsRepo("java", "Java")

  def testBashRunfilesLibraryInBazelToolsRepo(self):
    self._AssertRunfilesLibraryInBazelToolsRepo("sh", "Bash")

  def testCppRunfilesLibraryInBazelToolsRepo(self):
    self._AssertRunfilesLibraryInBazelToolsRepo("cc", "C++")

  def testRunfilesLibrariesFindRunfilesWithoutEnvvars(self):
    for s, t, exe in [
        ("MODULE.bazel.mock", "MODULE.bazel", False),
        ("bar/BUILD.mock", "bar/BUILD", False),
        ("bar/bar.py", "bar/bar.py", True),
        ("bar/bar-py-data.txt", "bar/bar-py-data.txt", False),
        ("bar/Bar.java", "bar/Bar.java", False),
        ("bar/bar-java-data.txt", "bar/bar-java-data.txt", False),
        ("bar/bar.sh", "bar/bar.sh", True),
        ("bar/bar-sh-data.txt", "bar/bar-sh-data.txt", False),
        ("bar/bar.cc", "bar/bar.cc", False),
        ("bar/bar-cc-data.txt", "bar/bar-cc-data.txt", False),
    ]:
      self.CopyFile(
          self.Rlocation("io_bazel/src/test/py/bazel/testdata/runfiles_test/" +
                         s), t, exe)

    _, stdout, _ = self.RunBazel(["info", "bazel-bin"])
    bazel_bin = stdout[0]

    self.RunBazel([
        "build",
        "--verbose_failures",
        "//bar:bar-py",
        "//bar:bar-java",
        "//bar:bar-sh",
        "//bar:bar-cc",
    ])

    for lang in [("py", "Python", "bar.py"), ("java", "Java", "Bar.java"),
                 ("sh", "Bash", "bar.sh"), ("cc", "C++", "bar.cc")]:
      if test_base.TestBase.IsWindows():
        bin_path = os.path.join(bazel_bin, "bar/bar-%s.exe" % lang[0])
      else:
        bin_path = os.path.join(bazel_bin, "bar/bar-" + lang[0])

      self.assertTrue(os.path.exists(bin_path))

      _, stdout, _ = self.RunProgram(
          [bin_path],
          env_remove=set([
              "RUNFILES_MANIFEST_FILE",
              "RUNFILES_MANIFEST_ONLY",
              "RUNFILES_DIR",
              "JAVA_RUNFILES",
          ]),
          env_add={"TEST_SRCDIR": "__ignore_me__"},
      )
      if len(stdout) < 2:
        self.fail("stdout(%s): %s" % (lang[0], stdout))
      self.assertEqual(stdout[0], "Hello %s Bar!" % lang[1])
      self.assertRegex(stdout[1], "^rloc=.*/bar/bar-%s-data.txt" % lang[0])
      self.assertNotIn("__ignore_me__", stdout[1])

      with open(stdout[1].split("=", 1)[1], "r") as f:
        lines = [l.strip() for l in f.readlines()]
      if len(lines) != 1:
        self.fail("lines(%s): %s" % (lang[0], lines))
      self.assertEqual(lines[0], "data for " + lang[2])

  def testRunfilesLibrariesFindRunfilesWithRunfilesManifestEnvvar(self):
    for s, t, exe in [
        ("MODULE.bazel.mock", "MODULE.bazel", False),
        ("bar/BUILD.mock", "bar/BUILD", False),
        # Note: do not test Python here, because py_binary always needs a
        # runfiles tree, even on Windows, because it needs __init__.py files in
        # every directory where there may be importable modules, so Bazel always
        # needs to create a runfiles tree for py_binary.
        ("bar/Bar.java", "bar/Bar.java", False),
        ("bar/bar-java-data.txt", "bar/bar-java-data.txt", False),
        ("bar/bar.sh", "bar/bar.sh", True),
        ("bar/bar-sh-data.txt", "bar/bar-sh-data.txt", False),
        ("bar/bar.cc", "bar/bar.cc", False),
        ("bar/bar-cc-data.txt", "bar/bar-cc-data.txt", False),
    ]:
      self.CopyFile(
          self.Rlocation("io_bazel/src/test/py/bazel/testdata/runfiles_test/" +
                         s), t, exe)

    _, stdout, _ = self.RunBazel(["info", "bazel-bin"])
    bazel_bin = stdout[0]

    for lang in [("java", "Java"), ("sh", "Bash"), ("cc", "C++")]:
      self.RunBazel([
          "build",
          "--verbose_failures",
          "--enable_runfiles=no",
          "//bar:bar-" + lang[0],
      ])

      if test_base.TestBase.IsWindows():
        bin_path = os.path.join(bazel_bin, "bar/bar-%s.exe" % lang[0])
      else:
        bin_path = os.path.join(bazel_bin, "bar/bar-" + lang[0])

      manifest_path = bin_path + ".runfiles_manifest"
      self.assertTrue(os.path.exists(bin_path))
      self.assertTrue(os.path.exists(manifest_path))

      # Create a copy of the runfiles manifest, replacing
      # "bar/bar-<lang>-data.txt" with a custom file.
      mock_bar_dep = self.ScratchFile("bar-%s-mockdata.txt" % lang[0],
                                      ["mock %s data" % lang[0]])
      if test_base.TestBase.IsWindows():
        # Runfiles manifests use forward slashes as path separators, even on
        # Windows.
        mock_bar_dep = mock_bar_dep.replace("\\", "/")
      manifest_key = "_main/bar/bar-%s-data.txt" % lang[0]
      mock_manifest_line = manifest_key + " " + mock_bar_dep
      with open(manifest_path, "rt") as f:
        # Only rstrip newlines. Do not rstrip() completely, because that would
        # remove spaces too. This is necessary in order to have at least one
        # space in every manifest line.
        # Some manifest entries don't have any path after this space, namely the
        # "__init__.py" entries. (Bazel writes such manifests on every
        # platform). The reason is that these files are never symlinks in the
        # runfiles tree, Bazel actually creates empty __init__.py files (again
        # on every platform). However to keep these manifest entries correct,
        # they need to have a space character.
        # We could probably strip these lines completely, but this test doesn't
        # aim to exercise what would happen in that case.
        mock_manifest_data = [
            mock_manifest_line
            if line.split(" ", 1)[0] == manifest_key else line.rstrip("\n\r")
            for line in f
        ]

      substitute_manifest = self.ScratchFile(
          "mock-%s.runfiles/MANIFEST" % lang[0], mock_manifest_data)

      _, stdout, _ = self.RunProgram(
          [bin_path],
          env_remove=set(["RUNFILES_DIR"]),
          env_add={
              # On Linux/macOS, the Java launcher picks up JAVA_RUNFILES and
              # ignores RUNFILES_MANIFEST_FILE.
              "JAVA_RUNFILES": substitute_manifest[: -len("/MANIFEST")],
              # On Windows, the Java launcher picks up RUNFILES_MANIFEST_FILE.
              # The C++ runfiles library picks up RUNFILES_MANIFEST_FILE on all
              # platforms.
              "RUNFILES_MANIFEST_FILE": substitute_manifest,
              "RUNFILES_MANIFEST_ONLY": "1",
              "TEST_SRCDIR": "__ignore_me__",
          },
      )

      if len(stdout) < 2:
        self.fail("stdout: %s" % stdout)
      self.assertEqual(stdout[0], "Hello %s Bar!" % lang[1])
      self.assertRegex(stdout[1], "^rloc=" + mock_bar_dep)
      self.assertNotIn("__ignore_me__", stdout[1])

      with open(stdout[1].split("=", 1)[1], "r") as f:
        lines = [l.strip() for l in f.readlines()]
      if len(lines) != 1:
        self.fail("lines: %s" % lines)
      self.assertEqual(lines[0], "mock %s data" % lang[0])

  def testRunfilesLibrariesFindRlocationpathExpansion(self):
    self.ScratchDir("A")
    self.ScratchFile("A/REPO.bazel")
    self.ScratchFile("A/p/BUILD", ["exports_files(['foo.txt'])"])
    self.ScratchFile("A/p/foo.txt", ["Hello, World!"])
    self.ScratchFile("MODULE.bazel", [
        'local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")',  # pylint: disable=line-too-long
        'local_repository(name = "A", path = "A")',
    ])
    self.ScratchFile("pkg/BUILD", [
        "py_binary(",
        "  name = 'bin',",
        "  srcs = ['bin.py'],",
        "  args = [",
        "    '$(rlocationpath bar.txt)',",
        "    '$(rlocationpath @A//p:foo.txt)',",
        "  ],",
        "  data = [",
        "    'bar.txt',",
        "    '@A//p:foo.txt'",
        "  ],",
        "  deps = ['@bazel_tools//tools/python/runfiles'],",
        ")",
    ])
    self.ScratchFile("pkg/bar.txt", ["Hello, Bazel!"])
    self.ScratchFile("pkg/bin.py", [
        "import sys",
        "from tools.python.runfiles import runfiles",
        "r = runfiles.Create()",
        "for arg in sys.argv[1:]:",
        "  print(open(r.Rlocation(arg)).read().strip())",
    ])
    _, stdout, _ = self.RunBazel(["run", "//pkg:bin"])
    if len(stdout) != 2:
      self.fail("stdout: %s" % stdout)
    self.assertEqual(stdout[0], "Hello, Bazel!")
    self.assertEqual(stdout[1], "Hello, World!")

  def setUpRunfilesDirectoryIncrementalityTest(self):
    self.ScratchFile("MODULE.bazel")
    self.ScratchFile(
        "BUILD",
        [
            "sh_test(",
            "  name = 'test',",
            "  srcs = ['test.sh'],",
            "  data = ['data.txt'],",
            ")",
        ],
    )
    self.ScratchFile("data.txt")
    self.ScratchFile("test.sh", ["[[ -f data.txt ]]"], executable=True)
    self.ScratchFile(
        ".bazelrc",
        [
            "startup --nowindows_enable_symlinks",
            "common --spawn_strategy=local",
        ],
    )

  def testRunfilesDirectoryIncrementalityEnableRunfilesFlippedOn(self):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--noenable_runfiles"], allow_failure=True
    )
    self.assertEqual(exit_code, 3)
    exit_code, _, _ = self.RunBazel(["test", ":test", "--enable_runfiles"])
    self.assertEqual(exit_code, 0)

  def testRunfilesDirectoryIncrementalityEnableRunfilesFlippedOff(self):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(["test", ":test", "--enable_runfiles"])
    self.assertEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--noenable_runfiles"], allow_failure=True
    )
    self.assertEqual(exit_code, 3)

  def testRunfilesDirectoryIncrementalityNoBuildRunfileLinksEnableRunfilesFlippedOn(
      self,
  ):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--nobuild_runfile_links", "--noenable_runfiles"],
        allow_failure=True,
    )
    self.assertEqual(exit_code, 3)
    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--nobuild_runfile_links", "--enable_runfiles"]
    )
    self.assertEqual(exit_code, 0)

  def testRunfilesDirectoryIncrementalityNoBuildRunfileLinksEnableRunfilesFlippedOff(
      self,
  ):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--nobuild_runfile_links", "--enable_runfiles"]
    )
    self.assertEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(
        ["test", ":test", "--nobuild_runfile_links", "--noenable_runfiles"],
        allow_failure=True,
    )
    self.assertEqual(exit_code, 3)

  def testRunfilesDirectoryIncrementalityEnableRunfilesFlippedOnRun(self):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--noenable_runfiles"], allow_failure=True
    )
    self.assertNotEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(["run", ":test", "--enable_runfiles"])
    self.assertEqual(exit_code, 0)

  def testRunfilesDirectoryIncrementalityEnableRunfilesFlippedOffRun(self):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(["run", ":test", "--enable_runfiles"])
    self.assertEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--noenable_runfiles"], allow_failure=True
    )
    self.assertNotEqual(exit_code, 0)

  def testRunfilesDirectoryIncrementalityNoBuildRunfileLinksEnableRunfilesFlippedOnRun(
      self,
  ):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--nobuild_runfile_links", "--noenable_runfiles"],
        allow_failure=True,
    )
    self.assertNotEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--nobuild_runfile_links", "--enable_runfiles"]
    )
    self.assertEqual(exit_code, 0)

  def testRunfilesDirectoryIncrementalityNoBuildRunfileLinksEnableRunfilesFlippedOffRun(
      self,
  ):
    self.setUpRunfilesDirectoryIncrementalityTest()

    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--nobuild_runfile_links", "--enable_runfiles"]
    )
    self.assertEqual(exit_code, 0)
    exit_code, _, _ = self.RunBazel(
        ["run", ":test", "--nobuild_runfile_links", "--noenable_runfiles"],
        allow_failure=True,
    )
    self.assertNotEqual(exit_code, 0)

  def testTestsRunWithNoBuildRunfileLinksAndNoEnableRunfiles(self):
    self.ScratchFile("MODULE.bazel")
    self.ScratchFile(
        "BUILD",
        [
            "sh_test(",
            "  name = 'test',",
            "  srcs = ['test.sh'],",
            ")",
        ],
    )
    self.ScratchFile("test.sh", executable=True)
    self.ScratchFile(".bazelrc", ["common --spawn_strategy=local"])
    self.RunBazel(
        ["test", ":test", "--nobuild_runfile_links", "--noenable_runfiles"]
    )

  def testWrappedShBinary(self):
    self.writeWrapperRule()
    self.ScratchFile("MODULE.bazel")
    self.ScratchFile(
        "BUILD",
        [
            "sh_binary(",
            "  name = 'binary',",
            "  srcs = ['binary.sh'],",
            "  visibility = ['//visibility:public'],",
            ")",
        ],
    )
    self.ScratchFile(
        "binary.sh",
        [
            "echo Hello, World!",
        ],
        executable=True,
    )

    _, stdout, _ = self.RunBazel(["run", "//wrapped"])
    self.assertEqual(stdout, ["Hello, World!"])

  def disabledWrappedPyBinary(self):
    # https://github.com/bazelbuild/rules_python/issues/2250
    # wrapping py_binary fails with rules_python 0.36.0 on Windows
    self.writeWrapperRule()
    self.ScratchFile("MODULE.bazel")
    self.ScratchFile(
        "BUILD",
        [
            "py_binary(",
            "  name = 'binary',",
            "  srcs = ['binary.py'],",
            "  visibility = ['//visibility:public'],",
            ")",
        ],
    )
    self.ScratchFile(
        "binary.py",
        [
            "print('Hello, World!')",
        ],
    )

    _, stdout, _ = self.RunBazel(["run", "//wrapped"])
    self.assertEqual(stdout, ["Hello, World!"])

  def testWrappedJavaBinary(self):
    self.writeWrapperRule()
    self.ScratchFile("MODULE.bazel")
    self.ScratchFile(
        "BUILD",
        [
            "java_binary(",
            "  name = 'binary',",
            "  srcs = ['Binary.java'],",
            "  main_class = 'Binary',",
            "  visibility = ['//visibility:public'],",
            ")",
        ],
    )
    self.ScratchFile(
        "Binary.java",
        [
            "public class Binary {",
            "  public static void main(String[] args) {",
            '    System.out.println("Hello, World!");',
            "  }",
            "}",
        ],
    )

    _, stdout, _ = self.RunBazel(["run", "//wrapped"])
    self.assertEqual(stdout, ["Hello, World!"])

  def writeWrapperRule(self):
    self.ScratchFile("rules/BUILD")
    self.ScratchFile(
        "rules/wrapper.bzl",
        [
            "def _wrapper_impl(ctx):",
            "    target = ctx.attr.target",
            (
                "    original_executable ="
                " target[DefaultInfo].files_to_run.executable"
            ),
            (
                "    executable ="
                " ctx.actions.declare_file(original_executable.basename)"
            ),
            (
                "    ctx.actions.symlink(output = executable, target_file ="
                " original_executable)"
            ),
            (
                "    data_runfiles ="
                " ctx.runfiles([executable]).merge(target[DefaultInfo].data_runfiles)"
            ),
            (
                "    default_runfiles ="
                " ctx.runfiles([executable]).merge(target[DefaultInfo].default_runfiles)"
            ),
            "    return [",
            "        DefaultInfo(",
            "            executable = executable,",
            "            files = target[DefaultInfo].files,",
            "            data_runfiles = data_runfiles,",
            "            default_runfiles = default_runfiles,",
            "        ),    ]",
            "wrapper = rule(",
            "    implementation = _wrapper_impl,",
            "    attrs = {",
            "        'target': attr.label(",
            "            cfg = 'target',",
            "            executable = True,",
            "        ),",
            "    },",
            "    executable = True,",
            ")",
        ],
    )
    self.ScratchFile(
        "wrapped/BUILD",
        [
            "load('//rules:wrapper.bzl', 'wrapper')",
            "wrapper(",
            "  name = 'wrapped',",
            "  target = '//:binary',",
            ")",
        ],
    )


if __name__ == "__main__":
  absltest.main()
