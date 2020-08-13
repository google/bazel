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

import glob
import os
import unittest
from src.test.py.bazel import test_base


class BazelWindowsCppTest(test_base.TestBase):

  def createProjectFiles(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'package(',
        '  default_visibility = ["//visibility:public"],',
        '  features=["windows_export_all_symbols"]',
        ')',
        '',
        'cc_library(',
        '  name = "A",',
        '  srcs = ["a.cc"],',
        '  hdrs = ["a.h"],',
        '  copts = ["/DCOMPILING_A_DLL"],',
        '  features = ["no_windows_export_all_symbols"],',
        ')',
        '',
        'cc_library(',
        '  name = "B",',
        '  srcs = ["b.cc"],',
        '  hdrs = ["b.h"],',
        '  deps = [":A"],',
        '  copts = ["/DNO_DLLEXPORT"],',
        ')',
        '',
        'cc_binary(',
        '  name = "C",',
        '  srcs = ["c.cc"],',
        '  deps = [":A", ":B" ],',
        '  linkstatic = 0,',
        ')',
    ])

    self.ScratchFile('a.cc', [
        '#include <stdio.h>',
        '#include "a.h"',
        'int a = 0;',
        'void hello_A() {',
        '  a++;',
        '  printf("Hello A, %d\\n", a);',
        '}',
    ])

    self.ScratchFile('b.cc', [
        '#include <stdio.h>',
        '#include "a.h"',
        '#include "b.h"',
        'void hello_B() {',
        '  hello_A();',
        '  printf("Hello B\\n");',
        '}',
    ])
    header_temp = [
        '#ifndef %{name}_H',
        '#define %{name}_H',
        '',
        '#if NO_DLLEXPORT',
        '  #define DLLEXPORT',
        '#elif COMPILING_%{name}_DLL',
        '  #define DLLEXPORT __declspec(dllexport)',
        '#else',
        '  #define DLLEXPORT __declspec(dllimport)',
        '#endif',
        '',
        'DLLEXPORT void hello_%{name}();',
        '',
        '#endif',
    ]
    self.ScratchFile('a.h',
                     [line.replace('%{name}', 'A') for line in header_temp])
    self.ScratchFile('b.h',
                     [line.replace('%{name}', 'B') for line in header_temp])

    c_cc_content = [
        '#include <stdio.h>',
        '#include "a.h"',
        '#include "b.h"',
        '',
        'void hello_C() {',
        '  hello_A();',
        '  hello_B();',
        '  printf("Hello C\\n");',
        '}',
        '',
        'int main() {',
        '  hello_C();',
        '  return 0;',
        '}',
    ]

    self.ScratchFile('c.cc', c_cc_content)

    self.ScratchFile('lib/BUILD', [
        'cc_library(',
        '  name = "A",',
        '  srcs = ["dummy.cc"],',
        '  features = ["windows_export_all_symbols"],',
        '  visibility = ["//visibility:public"],',
        ')',
    ])
    self.ScratchFile('lib/dummy.cc', ['void dummy() {}'])

    self.ScratchFile('main/main.cc', c_cc_content)

  def getBazelInfo(self, info_key):
    exit_code, stdout, stderr = self.RunBazel(['info', info_key])
    self.AssertExitCode(exit_code, 0, stderr)
    return stdout[0]

  def testBuildDynamicLibraryWithUserExportedSymbol(self):
    self.createProjectFiles()
    bazel_bin = self.getBazelInfo('bazel-bin')

    # //:A export symbols by itself using __declspec(dllexport), so it doesn't
    # need Bazel to export symbols using DEF file.
    exit_code, _, stderr = self.RunBazel(
        ['build', '//:A', '--output_groups=dynamic_library'])
    self.AssertExitCode(exit_code, 0, stderr)

    # TODO(pcloudy): change suffixes to .lib and .dll after making DLL
    # extensions correct on Windows.
    import_library = os.path.join(bazel_bin, 'A.if.lib')
    shared_library = os.path.join(bazel_bin, 'A.dll')
    empty_def_file = os.path.join(bazel_bin, 'A.gen.empty.def')

    self.assertTrue(os.path.exists(import_library))
    self.assertTrue(os.path.exists(shared_library))
    # An empty DEF file should be generated for //:A
    self.assertTrue(os.path.exists(empty_def_file))

  def testBuildDynamicLibraryWithExportSymbolFeature(self):
    self.createProjectFiles()
    bazel_bin = self.getBazelInfo('bazel-bin')

    # //:B doesn't export symbols by itself, so it need Bazel to export symbols
    # using DEF file.
    exit_code, _, stderr = self.RunBazel(
        ['build', '//:B', '--output_groups=dynamic_library'])
    self.AssertExitCode(exit_code, 0, stderr)

    # TODO(pcloudy): change suffixes to .lib and .dll after making DLL
    # extensions correct on Windows.
    import_library = os.path.join(bazel_bin, 'B.if.lib')
    shared_library = os.path.join(bazel_bin, 'B.dll')
    def_file = os.path.join(bazel_bin, 'B.gen.def')
    self.assertTrue(os.path.exists(import_library))
    self.assertTrue(os.path.exists(shared_library))
    # DEF file should be generated for //:B
    self.assertTrue(os.path.exists(def_file))

    # Test build //:B if windows_export_all_symbols feature is disabled by
    # no_windows_export_all_symbols.
    exit_code, _, stderr = self.RunBazel([
        'build', '//:B', '--output_groups=dynamic_library',
        '--features=no_windows_export_all_symbols'
    ])
    self.AssertExitCode(exit_code, 0, stderr)
    import_library = os.path.join(bazel_bin, 'B.if.lib')
    shared_library = os.path.join(bazel_bin, 'B.dll')
    empty_def_file = os.path.join(bazel_bin, 'B.gen.empty.def')
    self.assertTrue(os.path.exists(import_library))
    self.assertTrue(os.path.exists(shared_library))
    # An empty DEF file should be generated for //:B
    self.assertTrue(os.path.exists(empty_def_file))
    self.AssertFileContentNotContains(empty_def_file, 'hello_B')

  def testBuildCcBinaryWithDependenciesDynamicallyLinked(self):
    self.createProjectFiles()
    bazel_bin = self.getBazelInfo('bazel-bin')

    # Since linkstatic=0 is specified for //:C, it's dependencies should be
    # dynamically linked.
    exit_code, _, stderr = self.RunBazel(['build', '//:C'])
    self.AssertExitCode(exit_code, 0, stderr)

    # TODO(pcloudy): change suffixes to .lib and .dll after making DLL
    # extensions correct on
    # Windows.
    # a_import_library
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'A.if.lib')))
    # a_shared_library
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'A.dll')))
    # a_def_file
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'A.gen.empty.def')))
    # b_import_library
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'B.if.lib')))
    # b_shared_library
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'B.dll')))
    # b_def_file
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'B.gen.def')))
    # c_exe
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'C.exe')))

  def testBuildCcBinaryFromDifferentPackage(self):
    self.createProjectFiles()
    self.ScratchFile('main/BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["//:B"],',
        '  linkstatic = 0,'
        ')',
    ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel(['build', '//main:main'])
    self.AssertExitCode(exit_code, 0, stderr)

    # Test if A.dll and B.dll are copied to the directory of main.exe
    main_bin = os.path.join(bazel_bin, 'main/main.exe')
    self.assertTrue(os.path.exists(main_bin))
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'main/A.dll')))
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'main/B.dll')))

    # Run the binary to see if it runs successfully
    exit_code, stdout, stderr = self.RunProgram([main_bin])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertEqual(['Hello A, 1', 'Hello A, 2', 'Hello B', 'Hello C'], stdout)

  def testBuildCcBinaryDependsOnConflictDLLs(self):
    self.createProjectFiles()
    self.ScratchFile(
        'main/BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["//:B", "//lib:A"],',  # Transitively depends on //:A
            '  linkstatic = 0,'
            ')',
        ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    # //main:main depends on both //lib:A and //:A
    exit_code, _, stderr = self.RunBazel(
        ['build', '//main:main', '--incompatible_avoid_conflict_dlls'])
    self.AssertExitCode(exit_code, 0, stderr)

    # Run the binary to see if it runs successfully
    main_bin = os.path.join(bazel_bin, 'main/main.exe')
    exit_code, stdout, stderr = self.RunProgram([main_bin])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertEqual(['Hello A, 1', 'Hello A, 2', 'Hello B', 'Hello C'], stdout)
    # There are 2 A_{hash}.dll since //main:main depends on both //lib:A and
    # //:A
    self.assertEqual(
        len(glob.glob(os.path.join(bazel_bin, 'main', 'A_*.dll'))), 2)
    # There is only 1 B_{hash}.dll
    self.assertEqual(
        len(glob.glob(os.path.join(bazel_bin, 'main', 'B_*.dll'))), 1)

  def testBuildDifferentCcBinariesDependOnConflictDLLs(self):
    self.createProjectFiles()
    self.ScratchFile(
        'main/BUILD',
        [
            'cc_binary(',
            '  name = "main",',
            '  srcs = ["main.cc"],',
            '  deps = ["//:B"],',  # Transitively depends on //:A
            '  linkstatic = 0,'
            ')',
            '',
            'cc_binary(',
            '  name = "other_main",',
            '  srcs = ["other_main.cc"],',
            '  deps = ["//lib:A"],',
            '  linkstatic = 0,'
            ')',
        ])
    bazel_bin = self.getBazelInfo('bazel-bin')
    self.ScratchFile('main/other_main.cc', ['int main() {return 0;}'])

    # Building //main:main should succeed
    exit_code, _, stderr = self.RunBazel(
        ['build', '//main:main', '--incompatible_avoid_conflict_dlls'])
    self.AssertExitCode(exit_code, 0, stderr)
    main_bin = os.path.join(bazel_bin, 'main/main.exe')

    # Run the main_bin binary to see if it runs successfully
    exit_code, stdout, stderr = self.RunProgram([main_bin])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertEqual(['Hello A, 1', 'Hello A, 2', 'Hello B', 'Hello C'], stdout)
    # There is only 1 A_{hash}.dll since //main:main depends transitively on
    # //:A
    self.assertEqual(
        len(glob.glob(os.path.join(bazel_bin, 'main', 'A_*.dll'))), 1)
    # There is only 1 B_{hash}.dll
    self.assertEqual(
        len(glob.glob(os.path.join(bazel_bin, 'main', 'B_*.dll'))), 1)

    # Building //main:other_main should succeed
    exit_code, _, stderr = self.RunBazel([
        'build', '//main:main', '//main:other_main',
        '--incompatible_avoid_conflict_dlls'
    ])
    self.AssertExitCode(exit_code, 0, stderr)
    other_main_bin = os.path.join(bazel_bin, 'main/other_main.exe')

    # Run the other_main_bin binary to see if it runs successfully
    exit_code, stdout, stderr = self.RunProgram([other_main_bin])
    self.AssertExitCode(exit_code, 0, stderr)
    # There are 2 A_{hash}.dll since //main:main depends on //:A
    # and //main:other_main depends on //lib:A
    self.assertEqual(
        len(glob.glob(os.path.join(bazel_bin, 'main', 'A_*.dll'))), 2)

  def testDLLIsCopiedFromExternalRepo(self):
    self.ScratchFile('ext_repo/WORKSPACE')
    self.ScratchFile('ext_repo/BUILD', [
        'cc_library(',
        '  name = "A",',
        '  srcs = ["a.cc"],',
        '  features = ["windows_export_all_symbols"],',
        '  visibility = ["//visibility:public"],',
        ')',
    ])
    self.ScratchFile('ext_repo/a.cc', [
        '#include <stdio.h>',
        'void hello_A() {',
        '  printf("Hello A\\n");',
        '}',
    ])
    self.ScratchFile('WORKSPACE', [
        'local_repository(',
        '  name = "ext_repo",',
        '  path = "ext_repo",',
        ')',
    ])
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        '  deps = ["@ext_repo//:A"],',
        '  linkstatic = 0,',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'extern void hello_A();',
        '',
        'int main() {',
        '  hello_A();',
        '  return 0;',
        '}',
    ])

    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel(['build', '//:main'])
    self.AssertExitCode(exit_code, 0, stderr)

    # Test if A.dll is copied to the directory of main.exe
    main_bin = os.path.join(bazel_bin, 'main.exe')
    self.assertTrue(os.path.exists(main_bin))
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'A.dll')))

    # Run the binary to see if it runs successfully
    exit_code, stdout, stderr = self.RunProgram([main_bin])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertEqual(['Hello A'], stdout)

  def testDynamicLinkingMSVCRT(self):
    self.createProjectFiles()
    bazel_output = self.getBazelInfo('output_path')

    # By default, it should link to msvcrt dynamically.
    exit_code, _, stderr = self.RunBazel(
        ['build', '//:A', '--output_groups=dynamic_library', '-s'])
    paramfile = os.path.join(
        bazel_output, 'x64_windows-fastbuild/bin/A.dll-2.params')
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('/MD', ''.join(stderr))
    self.AssertFileContentContains(paramfile, '/DEFAULTLIB:msvcrt.lib')
    self.assertNotIn('/MT', ''.join(stderr))
    self.AssertFileContentNotContains(paramfile, '/DEFAULTLIB:libcmt.lib')

    # Test build in debug mode.
    exit_code, _, stderr = self.RunBazel(
        ['build', '-c', 'dbg', '//:A', '--output_groups=dynamic_library', '-s'])
    paramfile = os.path.join(bazel_output, 'x64_windows-dbg/bin/A.dll-2.params')
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('/MDd', ''.join(stderr))
    self.AssertFileContentContains(paramfile, '/DEFAULTLIB:msvcrtd.lib')
    self.assertNotIn('/MTd', ''.join(stderr))
    self.AssertFileContentNotContains(paramfile, '/DEFAULTLIB:libcmtd.lib')

  def testStaticLinkingMSVCRT(self):
    self.createProjectFiles()
    bazel_output = self.getBazelInfo('output_path')

    # With static_link_msvcrt feature, it should link to msvcrt statically.
    exit_code, _, stderr = self.RunBazel([
        'build', '//:A', '--output_groups=dynamic_library',
        '--features=static_link_msvcrt', '-s'
    ])
    paramfile = os.path.join(
        bazel_output, 'x64_windows-fastbuild/bin/A.dll-2.params')
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertNotIn('/MD', ''.join(stderr))
    self.AssertFileContentNotContains(paramfile, '/DEFAULTLIB:msvcrt.lib')
    self.assertIn('/MT', ''.join(stderr))
    self.AssertFileContentContains(paramfile, '/DEFAULTLIB:libcmt.lib')

    # Test build in debug mode.
    exit_code, _, stderr = self.RunBazel([
        'build', '-c', 'dbg', '//:A', '--output_groups=dynamic_library',
        '--features=static_link_msvcrt', '-s'
    ])
    paramfile = os.path.join(bazel_output, 'x64_windows-dbg/bin/A.dll-2.params')
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertNotIn('/MDd', ''.join(stderr))
    self.AssertFileContentNotContains(paramfile, '/DEFAULTLIB:msvcrtd.lib')
    self.assertIn('/MTd', ''.join(stderr))
    self.AssertFileContentContains(paramfile, '/DEFAULTLIB:libcmtd.lib')

  def testBuildSharedLibraryFromCcBinaryWithStaticLink(self):
    self.createProjectFiles()
    self.ScratchFile(
        'main/BUILD',
        [
            'cc_binary(',
            '  name = "main.dll",',
            '  srcs = ["main.cc"],',
            '  deps = ["//:B"],',  # Transitively depends on //:A
            '  linkstatic = 1,'
            '  linkshared = 1,'
            '  features=["windows_export_all_symbols"]',
            ')',
        ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel([
        'build', '//main:main.dll',
        '--output_groups=default,runtime_dynamic_libraries,interface_library'
    ])
    self.AssertExitCode(exit_code, 0, stderr)

    main_library = os.path.join(bazel_bin, 'main/main.dll')
    main_interface = os.path.join(bazel_bin, 'main/main.dll.if.lib')
    def_file = os.path.join(bazel_bin, 'main/main.dll.gen.def')
    self.assertTrue(os.path.exists(main_library))
    self.assertTrue(os.path.exists(main_interface))
    self.assertTrue(os.path.exists(def_file))
    # A.dll and B.dll should not be copied.
    self.assertFalse(os.path.exists(os.path.join(bazel_bin, 'main/A.dll')))
    self.assertFalse(os.path.exists(os.path.join(bazel_bin, 'main/B.dll')))
    self.AssertFileContentContains(def_file, 'hello_A')
    self.AssertFileContentContains(def_file, 'hello_B')
    self.AssertFileContentContains(def_file, 'hello_C')

  def testBuildSharedLibraryFromCcBinaryWithDynamicLink(self):
    self.createProjectFiles()
    self.ScratchFile(
        'main/BUILD',
        [
            'cc_binary(',
            '  name = "main.dll",',
            '  srcs = ["main.cc"],',
            '  deps = ["//:B"],',  # Transitively depends on //:A
            '  linkstatic = 0,'
            '  linkshared = 1,'
            '  features=["windows_export_all_symbols"]',
            ')',
            '',
            'genrule(',
            '  name = "renamed_main",',
            '  srcs = [":main.dll"],',
            '  outs = ["main_renamed.dll"],',
            '  cmd = "cp $< $@",',
            ')',
        ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel([
        'build', '//main:main.dll',
        '--output_groups=default,runtime_dynamic_libraries,interface_library'
    ])
    self.AssertExitCode(exit_code, 0, stderr)

    main_library = os.path.join(bazel_bin, 'main/main.dll')
    main_interface = os.path.join(bazel_bin, 'main/main.dll.if.lib')
    def_file = os.path.join(bazel_bin, 'main/main.dll.gen.def')
    self.assertTrue(os.path.exists(main_library))
    self.assertTrue(os.path.exists(main_interface))
    self.assertTrue(os.path.exists(def_file))
    # A.dll and B.dll should be built and copied because they belong to
    # runtime_dynamic_libraries output group.
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'main/A.dll')))
    self.assertTrue(os.path.exists(os.path.join(bazel_bin, 'main/B.dll')))
    # hello_A and hello_B should not be exported.
    self.AssertFileContentNotContains(def_file, 'hello_A')
    self.AssertFileContentNotContains(def_file, 'hello_B')
    self.AssertFileContentContains(def_file, 'hello_C')

    # The copy should succeed since //main:main.dll is only supposed to refer to
    # main.dll, A.dll and B.dll should be in a separate output group.
    exit_code, _, stderr = self.RunBazel(['build', '//main:renamed_main'])
    self.AssertExitCode(exit_code, 0, stderr)

  def testGetDefFileOfSharedLibraryFromCcBinary(self):
    self.createProjectFiles()
    self.ScratchFile(
        'main/BUILD',
        [
            'cc_binary(',
            '  name = "main.dll",',
            '  srcs = ["main.cc"],',
            '  deps = ["//:B"],',  # Transitively depends on //:A
            '  linkstatic = 1,'
            '  linkshared = 1,'
            ')',
        ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel(
        ['build', '//main:main.dll', '--output_groups=def_file'])
    self.AssertExitCode(exit_code, 0, stderr)

    # Although windows_export_all_symbols is not specified for this target,
    # we should still be able to get the DEF file by def_file output group.
    def_file = os.path.join(bazel_bin, 'main/main.dll.gen.def')
    self.assertTrue(os.path.exists(def_file))
    self.AssertFileContentContains(def_file, 'hello_A')
    self.AssertFileContentContains(def_file, 'hello_B')
    self.AssertFileContentContains(def_file, 'hello_C')

  def testBuildSharedLibraryWithoutAnySymbolExported(self):
    self.createProjectFiles()
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "A.dll",',
        '  srcs = ["a.cc", "a.h"],',
        '  copts = ["/DNO_DLLEXPORT"],',
        '  linkshared = 1,'
        ')',
    ])
    bazel_bin = self.getBazelInfo('bazel-bin')

    exit_code, _, stderr = self.RunBazel(['build', '//:A.dll'])
    self.AssertExitCode(exit_code, 0, stderr)

    # Although windows_export_all_symbols is not specified for this target,
    # we should still be able to build a DLL without any symbol exported.
    empty_def_file = os.path.join(bazel_bin, 'A.dll.gen.empty.def')
    self.assertTrue(os.path.exists(empty_def_file))
    self.AssertFileContentNotContains(empty_def_file, 'hello_A')

  def testUsingDefFileGeneratedFromCcLibrary(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('lib_A.cc', ['void hello_A() {}'])
    self.ScratchFile('lib_B.cc', ['void hello_B() {}'])
    self.ScratchFile('BUILD', [
        'cc_library(',
        '  name = "lib_A",',
        '  srcs = ["lib_A.cc"],',
        ')',
        '',
        'cc_library(',
        '  name = "lib_B",',
        '  srcs = ["lib_B.cc"],',
        '  deps = [":lib_A"]',
        ')',
        '',
        'filegroup(',
        '  name = "lib_B_symbols",',
        '  srcs = [":lib_B"],',
        '  output_group = "def_file",',
        ')',
        '',
        'cc_binary(',
        '  name = "lib.dll",',
        '  deps = [":lib_B"],',
        '  win_def_file = ":lib_B_symbols",',
        '  linkshared = 1,',
        ')',
    ])
    # Test specifying DEF file in cc_binary
    bazel_bin = self.getBazelInfo('bazel-bin')
    exit_code, _, stderr = self.RunBazel(['build', '//:lib.dll', '-s'])
    self.AssertExitCode(exit_code, 0, stderr)
    def_file = bazel_bin + '/lib_B.gen.def'
    self.assertTrue(os.path.exists(def_file))
    # hello_A should not be exported
    self.AssertFileContentNotContains(def_file, 'hello_A')
    # hello_B should be exported
    self.AssertFileContentContains(def_file, 'hello_B')

  def testWinDefFileAttribute(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('lib.cc', ['void hello() {}'])
    self.ScratchFile('my_lib.def', [
        'EXPORTS',
        '        ?hello@@YAXXZ',
    ])
    self.ScratchFile('BUILD', [
        'cc_library(',
        '  name = "lib",',
        '  srcs = ["lib.cc"],',
        '  win_def_file = "my_lib.def",',
        ')',
        '',
        'cc_binary(',
        '  name = "lib_dy.dll",',
        '  srcs = ["lib.cc"],',
        '  win_def_file = "my_lib.def",',
        '  linkshared = 1,',
        ')',
    ])

    # Test exporting symbols using custom DEF file in cc_library.
    # Auto-generating DEF file should be disabled when custom DEF file specified
    exit_code, _, stderr = self.RunBazel([
        'build', '//:lib', '-s', '--output_groups=dynamic_library',
        '--features=windows_export_all_symbols'
    ])
    self.AssertExitCode(exit_code, 0, stderr)

    bazel_bin = self.getBazelInfo('bazel-bin')
    lib_if = os.path.join(bazel_bin, 'lib.if.lib')
    lib_def = os.path.join(bazel_bin, 'lib.gen.def')
    self.assertTrue(os.path.exists(lib_if))
    self.assertFalse(os.path.exists(lib_def))

    # Test specifying DEF file in cc_binary
    exit_code, _, stderr = self.RunBazel(['build', '//:lib_dy.dll', '-s'])
    self.AssertExitCode(exit_code, 0, stderr)
    filepath = bazel_bin + '/lib_dy.dll-2.params'
    with open(filepath, 'r', encoding='latin-1') as param_file:
      self.assertIn('/DEF:my_lib.def', param_file.read())

  def testCcImportRule(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_import(',
        '  name = "a_import",',
        '  static_library = "A.lib",',
        '  shared_library = "A.dll",',
        '  interface_library = "A.if.lib",',
        '  hdrs = ["a.h"],',
        '  alwayslink = 1,',
        ')',
    ])
    exit_code, _, stderr = self.RunBazel([
        'build', '//:a_import',
    ])
    self.AssertExitCode(exit_code, 0, stderr)

  def testCopyDLLAsSource(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_import(',
        '  name = "a_import",',
        '  shared_library = "A.dll",',
        ')',
        '',
        'cc_binary(',
        '  name = "bin",',
        '  srcs = ["bin.cc"],',
        '  deps = ["//:a_import"],',
        ')',
    ])
    self.ScratchFile('A.dll')
    self.ScratchFile('bin.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel([
        'build',
        '//:bin',
    ])
    self.AssertExitCode(exit_code, 0, stderr)

    bazel_bin = self.getBazelInfo('bazel-bin')
    a_dll = os.path.join(bazel_bin, 'A.dll')
    # Even though A.dll is in the same package as bin.exe, but it still should
    # be copied to the output directory of bin.exe.
    self.assertTrue(os.path.exists(a_dll))

  def testCppErrorShouldBeVisible(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "bad",',
        '  srcs = ["bad.cc"],',
        ')',
    ])
    self.ScratchFile('bad.cc', [
        'int main(int argc, char** argv) {',
        '  this_is_an_error();',
        '}',
    ])
    exit_code, stdout, stderr = self.RunBazel(['build', '//:bad'])
    self.AssertExitCode(exit_code, 1, stderr)
    self.assertIn('this_is_an_error', ''.join(stdout))

  def testBuildWithClangClByCompilerFlag(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel([
        'build', '-s', '--compiler=clang-cl',
        '--incompatible_enable_cc_toolchain_resolution=false', '//:main'
    ])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('clang-cl.exe', ''.join(stderr))

  def testBuildWithClangClByToolchainResolution(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE', [
        'register_execution_platforms(',
        '  ":windows_clang"',
        ')',
        '',
        'register_toolchains(',
        '  "@local_config_cc//:cc-toolchain-x64_windows-clang-cl",',
        ')',
    ])
    self.ScratchFile('BUILD', [
        'platform(',
        '  name = "windows_clang",',
        '  constraint_values = [',
        '    "@platforms//cpu:x86_64",',
        '    "@platforms//os:windows",',
        '    "@bazel_tools//tools/cpp:clang-cl",',
        '  ]',
        ')',
        '',
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel([
        'build', '-s', '--incompatible_enable_cc_toolchain_resolution=true',
        '//:main'
    ])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('clang-cl.exe', ''.join(stderr))

  def createSimpleCppWorkspace(self, name):
    work_dir = self.ScratchDir(name)
    self.ScratchFile(name + '/WORKSPACE', ['workspace(name = "%s")' % name])
    self.ScratchFile(
        name + '/BUILD',
        ['cc_library(name = "lib", srcs = ["lib.cc"], hdrs = ["lib.h"])'])
    self.ScratchFile(name + '/lib.h', ['void hello();'])
    self.ScratchFile(name + '/lib.cc', ['#include "lib.h"', 'void hello() {}'])
    return work_dir

  # Regression test for https://github.com/bazelbuild/bazel/issues/9172
  def testCacheBetweenWorkspaceWithDifferentNames(self):
    cache_dir = self.ScratchDir('cache')
    dir_a = self.createSimpleCppWorkspace('A')
    dir_b = self.createSimpleCppWorkspace('B')
    exit_code, _, stderr = self.RunBazel(
        ['build', '--disk_cache=' + cache_dir, ':lib'], cwd=dir_a)
    self.AssertExitCode(exit_code, 0, stderr)
    exit_code, _, stderr = self.RunBazel(
        ['build', '--disk_cache=' + cache_dir, ':lib'], cwd=dir_b)
    self.AssertExitCode(exit_code, 0, stderr)

  # Regression test for https://github.com/bazelbuild/bazel/issues/9321
  def testCcCompileWithTreeArtifactAsSource(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'load(":genccs.bzl", "genccs")',
        '',
        'genccs(',
        '    name = "gen_tree",',
        ')',
        '',
        'cc_library(',
        '    name = "main",',
        '    srcs = [ "gen_tree" ]',
        ')',
        '',
        'cc_binary(',
        '    name = "genccs",',
        '    srcs = [ "genccs.cpp" ],',
        ')',
    ])
    self.ScratchFile('genccs.bzl', [
        'def _impl(ctx):',
        '  tree = ctx.actions.declare_directory(ctx.attr.name + ".cc")',
        '  ctx.actions.run(',
        '    inputs = [],',
        '    outputs = [ tree ],',
        '    arguments = [ tree.path ],',
        '    progress_message = "Generating cc files into \'%s\'" % tree.path,',
        '    executable = ctx.executable._tool,',
        '  )',
        '',
        '  return [ DefaultInfo(files = depset([ tree ])) ]',
        '',
        'genccs = rule(',
        '  implementation = _impl,',
        '  attrs = {',
        '    "_tool": attr.label(',
        '      executable = True,',
        '      cfg = "host",',
        '      allow_files = True,',
        '      default = Label("//:genccs"),',
        '    )',
        '  }',
        ')',
    ])
    self.ScratchFile('genccs.cpp', [
        '#include <fstream>',
        '#include <Windows.h>',
        'using namespace std;',
        '',
        'int main (int argc, char *argv[]) {',
        '  CreateDirectory(argv[1], NULL);',
        '  ofstream myfile;',
        '  myfile.open(string(argv[1]) + string("/foo.cpp"));',
        '  myfile << "int main() { return 42; }";',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel(['build', '//:main'])
    self.AssertExitCode(exit_code, 0, stderr)

  def testBuild32BitCppBinaryWithMsvcCL(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel(
        ['build', '-s', '--cpu=x64_x86_windows', '//:main'])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('x86/cl.exe', ''.join(stderr))

  def testBuildArmCppBinaryWithMsvcCL(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel(
        ['build', '-s', '--cpu=x64_arm_windows', '//:main'])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('arm/cl.exe', ''.join(stderr))

  def testBuildArm64CppBinaryWithMsvcCL(self):
    self.CreateWorkspaceWithDefaultRepos('WORKSPACE')
    self.ScratchFile('BUILD', [
        'cc_binary(',
        '  name = "main",',
        '  srcs = ["main.cc"],',
        ')',
    ])
    self.ScratchFile('main.cc', [
        'int main() {',
        '  return 0;',
        '}',
    ])
    exit_code, _, stderr = self.RunBazel(
        ['build', '-s', '--cpu=x64_arm64_windows', '//:main'])
    self.AssertExitCode(exit_code, 0, stderr)
    self.assertIn('arm64/cl.exe', ''.join(stderr))

if __name__ == '__main__':
  unittest.main()