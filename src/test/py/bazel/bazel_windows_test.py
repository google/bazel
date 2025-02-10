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


class BazelWindowsTest(test_base.TestBase):

  def createProjectFiles(self):
    self.ScratchFile(
        'MODULE.bazel',
        [
            'bazel_dep(name = "platforms", version = "0.0.9")',
            'bazel_dep(name = "rules_cc", version = "0.0.12")',
            (
                'cc_configure ='
                ' use_extension("@rules_cc//cc:extensions.bzl",'
                ' "cc_configure_extension")'
            ),
            'use_repo(cc_configure, "local_config_cc")',
        ],
    )
    self.ScratchFile('foo/BUILD', [
        'platform(',
        '    name = "x64_windows-msys-gcc",',
        '    constraint_values = [',
        '        "@platforms//cpu:x86_64",',
        '        "@platforms//os:windows",',
        '        "@bazel_tools//tools/cpp:msys",',
        '    ],',
        ')',
        'cc_binary(name="x", srcs=["x.cc"])',
    ])
    self.ScratchFile('foo/x.cc', [
        '#include <stdio.h>',
        'int main(int, char**) {',
        '  printf("hello\\n");',
        '  return 0;',
        '}',
    ])

  def testWindowsUnixRoot(self):
    self.createProjectFiles()

    exit_code, _, stderr = self.RunBazel(
        [
            '--batch',
            '--host_jvm_args=-Dbazel.windows_unix_root=',
            'build',
            '//foo:x',
            '--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows_msys',
            '--extra_execution_platforms=//foo:x64_windows-msys-gcc',
        ],
        allow_failure=True,
    )
    self.AssertExitCode(exit_code, 37, stderr)
    self.assertIn(
        '"bazel.windows_unix_root" JVM flag is not set', '\n'.join(stderr)
    )

    self.RunBazel([
        '--batch', 'build',
        '--extra_toolchains=@local_config_cc//:cc-toolchain-x64_windows_msys',
        '--extra_execution_platforms=//foo:x64_windows-msys-gcc',
        '//foo:x',
    ])

  def testWindowsParameterFile(self):
    self.createProjectFiles()

    _, stdout, _ = self.RunBazel(['info', 'bazel-bin'], allow_failure=True)
    bazel_bin = stdout[0]

    self.RunBazel([
        'build',
        '--materialize_param_files',
        '--features=compiler_param_file',
        '//foo:x',
    ])
    self.assertTrue(
        os.path.exists(os.path.join(bazel_bin, 'foo\\_objs\\x\\x.obj.params')))

  def testWindowsCompilesAssembly(self):
    _, stdout, _ = self.RunBazel(['info', 'bazel-bin'])
    bazel_bin = stdout[0]
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '    name="x",',
        '    srcs=['
        '        "inc.asm",',  # Test assemble action_config
        '        "dec.S",',    # Test preprocess-assemble action_config
        '        "y.cc",',
        '    ],',
        ')',
    ])
    self.ScratchFile('inc.asm', [
        '.code',
        'PUBLIC increment',
        'increment PROC x:WORD',
        '  xchg rcx,rax',
        '  inc rax',
        '  ret',
        'increment EndP',
        'END',
    ])
    self.ScratchFile('dec.S', [
        '.code',
        'PUBLIC decrement',
        'decrement PROC x:WORD',
        '  xchg rcx,rax',
        '  dec rax',
        '  ret',
        'decrement EndP',
        'END',
    ])
    self.ScratchFile('y.cc', [
        '#include <stdio.h>',
        'extern "C" int increment(int);',
        'extern "C" int decrement(int);',
        'int main(int, char**) {'
        '  int x = 5;',
        '  x = increment(x);',
        '  printf("%d\\n", x);',
        '  x = decrement(x);',
        '  printf("%d\\n", x);',
        '  return 0;',
        '}',
    ])

    self.RunBazel(['build', '//:x'])
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'x.exe')))

  def testWindowsEnvironmentVariablesSetting(self):
    self.ScratchFile('BUILD')
    rule_definition = [
        'my_repo = use_repo_rule("//:repo.bzl", "my_repo")',
        'my_repo(name = "env_test")',
    ]
    self.ScratchFile('MODULE.bazel', rule_definition)
    self.ScratchFile('repo.bzl', [
        'def my_repo_impl(repository_ctx):',
        '  repository_ctx.file("env.bat", "set FOO\\n")',
        '  env = {"foo" : "bar2", "Foo": "bar3",}',
        '  result = repository_ctx.execute(["./env.bat"], environment = env)',
        '  print(result.stdout)',
        '  repository_ctx.file("BUILD")',
        '',
        'my_repo = repository_rule(',
        '    implementation = my_repo_impl,',
        ')',
    ])

    _, _, stderr = self.RunBazel(
        [
            'build',
            '@env_test//...',
        ],
        env_add={'FOO': 'bar1'},
    )
    result_in_lower_case = ''.join(stderr).lower()
    self.assertNotIn('foo=bar1', result_in_lower_case)
    self.assertNotIn('foo=bar2', result_in_lower_case)
    self.assertIn('foo=bar3', result_in_lower_case)

  def testRunPowershellInAction(self):
    self.ScratchFile('BUILD', [
        'load(":execute.bzl", "run_powershell")',
        'run_powershell(name = "powershell_test", out = "out.txt")',
    ])
    self.ScratchFile('write.bat', [
        'powershell.exe -NoP -NonI -Command "Add-Content \'%1\' \'%2\'"',
    ])
    self.ScratchFile('execute.bzl', [
        'def _impl(ctx):',
        '    ctx.actions.run(',
        '        outputs = [ctx.outputs.out],',
        '        arguments = [ctx.outputs.out.path, "hello-world"],',
        '        use_default_shell_env = True,',
        '        executable = ctx.executable.tool,',
        '    )',
        'run_powershell = rule(',
        '    implementation = _impl,',
        '    attrs = {',
        '        "out": attr.output(mandatory = True),',
        '        "tool": attr.label(',
        '            executable = True,',
        '            cfg = "exec",',
        '            allow_files = True,',
        '            default = Label("//:write.bat"),',
        '        ),',
        '    },',
        ')',
    ])

    self.RunBazel(
        [
            'build',
            '//:powershell_test',
            '--incompatible_strict_action_env',
        ],
    )

  def testAnalyzeCcRuleWithoutVCInstalled(self):
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "bin",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'void main() {',
        '  printf("Hello world");',
        '}',
    ])
    self.RunBazel(
        [
            'build',
            '--nobuild',
            '//...',
        ],
        # Set BAZEL_VC to a non-existing path,
        # Bazel should still work when analyzing cc rules .
        env_add={'BAZEL_VC': 'C:/not/exists/VC'},
    )

  def testBuildNonCcRuleWithoutVCInstalled(self):
    self.ScratchFile('BUILD', [
        'genrule(',
        '  name="gen",',
        '  outs = ["hello"],',
        '  cmd = "touch $@",',
        ')',
        '',
        'java_binary(',
        '  name = "bin_java",',
        '  srcs = ["Main.java"],',
        '  main_class = "Main",',
        ')',
        '',
        'py_binary(',
        '  name = "bin_py",',
        '  srcs = ["bin_py.py"],',
        ')',
        '',
        'sh_binary(',
        '  name = "bin_sh",',
        '  srcs = ["main.sh"],',
        ')',
    ])
    self.ScratchFile('Main.java', [
        'public class Main {',
        '  public static void main(String[] args) {',
        '    System.out.println("hello java");',
        '  }',
        '}',
    ])
    self.ScratchFile('bin_py.py', [
        'print("Hello world")',
    ])
    self.ScratchFile('main.sh', [
        'echo "Hello world"',
    ])
    self.RunBazel(
        [
            'build',
            '//...',
        ],
        # Set BAZEL_VC to a non-existing path,
        # Bazel should still work when building rules that doesn't
        # require cc toolchain.
        env_add={'BAZEL_VC': 'C:/not/exists/VC'},
    )

  def testDeleteReadOnlyFile(self):
    self.ScratchFile(
        'BUILD',
        [
            'genrule(',
            '  name = "gen_read_only_file",',
            '  cmd_bat = "echo hello > $@ && attrib +r $@",',
            '  outs = ["file_foo"],',
            ')',
        ],
    )

    self.RunBazel(['build', '//...'])
    self.RunBazel(['clean'])

  def testDeleteReadOnlyDirectory(self):
    self.ScratchFile(
        'defs.bzl',
        [
            'def _impl(ctx):',
            '  dir = ctx.actions.declare_directory(ctx.label.name)',
            '  bat = ctx.actions.declare_file(ctx.label.name + ".bat")',
            '  ctx.actions.write(',
            '    output = bat,',
            '    content = "attrib +r " + dir.path,',
            '    is_executable = True,',
            '  )',
            '  ctx.actions.run(',
            '    outputs = [dir],',
            '    executable = bat,',
            '    use_default_shell_env = True,',
            '  )',
            '  return DefaultInfo(files = depset([dir]))',
            'read_only_dir = rule(_impl)',
        ],
    )
    self.ScratchFile(
        'BUILD',
        [
            'load(":defs.bzl", "read_only_dir")',
            'read_only_dir(',
            '  name = "gen_read_only_dir",',
            ')',
        ],
    )

    self.RunBazel(['build', '--subcommands', '--verbose_failures', '//...'])
    self.RunBazel(['clean'])

  def testBuildJavaTargetWithClasspathJar(self):
    self.ScratchFile('BUILD', [
        'java_binary(',
        '  name = "java_bin",',
        '  srcs = ["Main.java"],',
        '  main_class = "Main",',
        '  deps = ["java_lib"],',
        ')',
        '',
        'java_library(',
        '  name = "java_lib",',
        '  srcs = ["Greeting.java"],',
        ')',
        '',
        'java_binary(',
        '  name = "special_java_bin",',
        '  srcs = ["Main.java"],',
        '  main_class = "Main",',
        '  deps = [":special%java%lib"],',
        ')',
        '',
        'java_library(',
        '  name = "special%java%lib",',
        '  srcs = ["Greeting.java"],',
        ')',
        '',
    ])
    self.ScratchFile('Main.java', [
        'public class Main {',
        '  public static void main(String[] args) {',
        '    Greeting.sayHi();',
        '  }',
        '}',
    ])
    self.ScratchFile('Greeting.java', [
        'public class Greeting {',
        '  public static void sayHi() {',
        '    System.out.println("Hello World!");',
        '  }',
        '}',
    ])
    _, stdout, _ = self.RunBazel(
        [
            'run',
            '//:java_bin',
            '--',
            '--wrapper_script_flag=--classpath_limit=0',
        ],
    )
    self.assertIn('Hello World!', '\n'.join(stdout))

    _, stdout, _ = self.RunBazel(
        [
            'run',
            '//:special_java_bin',
            '--',
            '--wrapper_script_flag=--classpath_limit=0',
        ],
    )
    self.assertIn('Hello World!', '\n'.join(stdout))

  def testRunWithScriptPath(self):
    self.ScratchFile('BUILD', [
        'sh_binary(',
        '  name = "foo_bin",',
        '  srcs = ["foo.sh"],',
        ')',
        '',
        'sh_test(',
        '  name = "foo_test",',
        '  srcs = ["foo.sh"],',
        ')',
        '',
    ])
    self.ScratchFile('foo.sh', [
        'echo "Hello from $1!"',
    ])

    # Test generating a script from binary run
    self.RunBazel(
        [
            'run',
            '--script_path=bin_output_script.bat',
            '//:foo_bin',
        ],
    )

    _, stdout, _ = self.RunProgram(
        ['bin_output_script.bat', 'binary'], allow_failure=True
    )
    self.assertIn('Hello from binary!', '\n'.join(stdout))

    # Test generating a script from test run
    self.RunBazel(
        [
            'run',
            '--script_path=test_output_script.bat',
            '//:foo_test',
        ],
        allow_failure=True,
    )

    _, stdout, _ = self.RunProgram(
        ['test_output_script.bat', 'test'], allow_failure=True
    )
    self.assertIn('Hello from test!', '\n'.join(stdout))

  def testZipUndeclaredTestOutputs(self):
    self.ScratchFile(
        'BUILD',
        [
            'sh_test(',
            '  name = "foo_test",',
            '  srcs = ["foo.sh"],',
            ')',
            '',
        ],
    )
    self.ScratchFile(
        'foo.sh',
        [
            'touch "$TEST_UNDECLARED_OUTPUTS_DIR/foo.txt"',
        ],
    )

    _, stdout, _ = self.RunBazel(['info', 'bazel-testlogs'])
    bazel_testlogs = stdout[0]

    output_file = os.path.join(bazel_testlogs, 'foo_test/test.outputs/foo.txt')
    output_zip = os.path.join(
        bazel_testlogs, 'foo_test/test.outputs/outputs.zip'
    )

    # Run the test with undeclared outputs zipping.
    self.RunBazel(
        [
            'test',
            '--zip_undeclared_test_outputs',
            '//:foo_test',
        ],
    )
    self.assertFalse(os.path.exists(output_file))
    self.assertTrue(os.path.exists(output_zip))

    # Run the test without undeclared outputs zipping.
    self.RunBazel(
        [
            'test',
            '--nozip_undeclared_test_outputs',
            '//:foo_test',
        ],
    )
    self.assertTrue(os.path.exists(output_file))
    self.assertFalse(os.path.exists(output_zip))

  def testBazelForwardsRequiredEnvVariable(self):
    self.ScratchFile(
        'BUILD',
        [
            'sh_test(',
            '  name = "foo_test",',
            '  srcs = ["foo.sh"],',
            ')',
            '',
        ],
    )
    self.ScratchFile(
        'foo.sh',
        [
            """
            if [[ "$BAZEL_TEST" == "1" ]]; then
                exit 0
            else
                echo "BAZEL_TEST is not set to 1"
                exit 1
            fi
            """,
        ],
    )

    exit_code, stdout, stderr = self.RunBazel(
        [
            'test',
            '//:foo_test',
        ],
    )
    self.AssertExitCode(exit_code, 0, stderr, stdout)

  def testTestShardStatusFile(self):
    self.ScratchFile(
        'BUILD',
        [
            'sh_test(',
            '  name = "foo_test",',
            '  srcs = ["foo.sh"],',
            '  shard_count = 2,',
            ')',
        ],
    )
    self.ScratchFile('foo.sh')

    exit_code, stdout, stderr = self.RunBazel(
        ['test', '--incompatible_check_sharding_support', '//:foo_test'],
        allow_failure=True,
    )
    # Check for "tests failed" exit code
    self.AssertExitCode(exit_code, 3, stderr, stdout)
    self.assertTrue(
        any(
            'Sharding requested, but the test runner did not advertise support'
            ' for it by touching TEST_SHARD_STATUS_FILE.'
            in line
            for line in stderr
        )
    )

    self.ScratchFile('foo.sh', ['touch "$TEST_SHARD_STATUS_FILE"'])

    self.RunBazel(
        ['test', '--incompatible_check_sharding_support', '//:foo_test']
    )

  def testTestPrematureExitFile(self):
    self.ScratchFile(
        'BUILD',
        [
            'sh_test(',
            '  name = "foo_test",',
            '  srcs = ["foo.sh"],',
            ')',
        ],
    )
    self.ScratchFile(
        'foo.sh',
        [
            '#!/bin/sh',
            'touch "$TEST_PREMATURE_EXIT_FILE"',
            'echo "fake pass"',
            'exit 0',
        ],
    )

    exit_code, stdout, stderr = self.RunBazel(
        ['test', '--test_output=errors', '//:foo_test'],
        allow_failure=True,
    )
    # Check for "tests failed" exit code
    self.AssertExitCode(exit_code, 3, stderr, stdout)
    self.assertIn(
        '-- Test exited prematurely (TEST_PREMATURE_EXIT_FILE exists) --',
        stdout,
    )

  def testMakeVariableForDumpbinExecutable(self):
    if not self.IsWindows():
      return

    self.ScratchFile(
        'BUILD',
        [
            'cc_binary(',
            '    name = "test_dll",',
            '    linkshared = 1,',
            '    srcs = ["dllexport.c"],',
            ')',
            'genrule(',
            '    name = "dumpbin",',
            '    srcs = [":test_dll"],',
            '    outs = ["dumpbin_out.txt"],',
            # We have to use double quotes due to /S argument in cmd.exe call
            (
                '    cmd_bat = \'""$(DUMPBIN)"" /EXPORTS $(location :test_dll)'
                " > $@',"
            ),
            (
                '    toolchains ='
                ' ["@bazel_tools//tools/cpp:toolchain_type"],'
            ),
            ')',
        ],
    )
    self.ScratchFile(
        'dllexport.c',
        [
            '__declspec(dllexport) int windows_dllexport_test() { return 1; }',
        ],
    )

    _, stdout, _ = self.RunBazel(['info', 'bazel-bin'])
    bazel_bin = stdout[0]

    self.RunBazel(['build', ':dumpbin'])

    dumpbin_out = os.path.join(bazel_bin, 'dumpbin_out.txt')
    self.assertTrue(os.path.exists(dumpbin_out))
    self.AssertFileContentContains(dumpbin_out, 'windows_dllexport_test')


if __name__ == '__main__':
  absltest.main()
