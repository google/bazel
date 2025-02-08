#!/bin/bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
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
#
# Test runfiles creation
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_runfiles_bzlmod() {
  cat > MODULE.bazel <<EOF
module(name="blep")
EOF

  mkdir foo
  cat > foo/BUILD <<EOF
java_test(
    name = "foo",
    srcs = ["Noise.java"],
    test_class = "Noise",
)
EOF
  cat > foo/Noise.java <<EOF
public class Noise {
  public static void main(String[] args) {
    System.err.println(System.getenv("I'm a test."));
  }
}
EOF

  bazel build --enable_bzlmod //foo:foo >& $TEST_log || fail "Build failed"
  [[ -d bazel-bin/foo/foo.runfiles/_main ]] || fail "_main runfiles directory not created"
  [[ -d bazel-bin/foo/foo.runfiles/_main/foo ]] || fail "No foo subdirectory under _main"
  [[ -x bazel-bin/foo/foo.runfiles/_main/foo/foo ]] || fail "No foo executable under _main"
}

function test_legacy_runfiles_change() {
  cat >> MODULE.bazel <<EOF
new_local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "new_local_repository")
new_local_repository(
    name = "bar",
    path = ".",
    build_file = "//:BUILD",
)
EOF
  cat > BUILD <<EOF
exports_files(glob(["*"]))

cc_binary(
    name = "thing",
    srcs = ["thing.cc"],
    data = ["@bar//:thing.cc"],
)
EOF
  cat > thing.cc <<EOF
int main() { return 0; }
EOF
  bazel build --legacy_external_runfiles //:thing &> $TEST_log \
    || fail "Build failed"
  [[ -d bazel-bin/thing.runfiles/_main/external/+_repo_rules+bar ]] \
    || fail "bar not found"

  bazel build --nolegacy_external_runfiles //:thing &> $TEST_log \
    || fail "Build failed"
  [[ ! -d bazel-bin/thing.runfiles/_main/external/+_repo_rules+bar ]] \
    || fail "Old bar still found"

  bazel build --legacy_external_runfiles //:thing &> $TEST_log \
    || fail "Build failed"
  [[ -d bazel-bin/thing.runfiles/_main/external/+_repo_rules+bar ]] \
    || fail "bar not recreated"
}

function test_enable_runfiles_change() {

  mkdir data && echo "hello" > data/hello && echo "world" > data/world

  touch bin.sh
  chmod 755 bin.sh

  cat > BUILD <<'EOF'
sh_binary(
  name = "bin",
  srcs = ["bin.sh"],
  data = glob(["data/*"]),
)
EOF

  bazel build --enable_runfiles //:bin || fail "Building //:bin failed"

  [[ -f bazel-bin/bin.runfiles/_main/data/hello ]] || fail "expected runfile data/hello"
  [[ -f bazel-bin/bin.runfiles/_main/data/world ]] || fail "expected runfile data/world"
  [[ -f bazel-bin/bin.runfiles/MANIFEST ]] || fail "expected output manifest to exist"

  bazel build --noenable_runfiles //:bin || fail "Building //:bin failed"

  [[ ! -f bazel-bin/bin.runfiles/_main/data/hello ]] || fail "expected no runfile data/hello"
  [[ ! -f bazel-bin/bin.runfiles/_main/data/world ]] || fail "expected no runfile data/world"
  [[ -f bazel-bin/bin.runfiles/MANIFEST ]] || fail "expected output manifest to exist"
}

# Test that the local strategy creates a runfiles tree during test if no --nobuild_runfile_links
# is specified.
function test_nobuild_runfile_links() {

  mkdir data && echo "hello" > data/hello && echo "world" > data/world

  cat > test.sh <<'EOF'
#!/bin/bash
set -e
[[ -f ${RUNFILES_DIR}/_main/data/hello ]]
[[ -f ${RUNFILES_DIR}/_main/data/world ]]
exit 0
EOF

  chmod 755 test.sh

  cat > BUILD <<'EOF'
sh_test(
  name = "test",
  srcs = ["test.sh"],
  data = glob(["data/*"]),
)
EOF

  bazel build --spawn_strategy=local --nobuild_runfile_links //:test \
    || fail "Building //:test failed"

  [[ ! -f bazel-bin/test.runfiles/_main/data/hello ]] || fail "expected no runfile data/hello"
  [[ ! -f bazel-bin/test.runfiles/_main/data/world ]] || fail "expected no runfile data/world"
  [[ ! -f bazel-bin/test.runfiles/MANIFEST ]] || fail "expected output manifest to not exist"

  bazel test --spawn_strategy=local --nobuild_runfile_links //:test \
    || fail "Testing //:test failed"

  [[ -f bazel-bin/test.runfiles/_main/data/hello ]] || fail "expected runfile data/hello to exist"
  [[ -f bazel-bin/test.runfiles/_main/data/world ]] || fail "expected runfile data/world to exist"
  [[ -f bazel-bin/test.runfiles/MANIFEST ]] || fail "expected output manifest to exist"
}

# When --nobuild_runfile_links is used, "bazel run --run_under" should still
# attempt to create the runfiles directory both for the target to run and the
# --run_under target.
function test_nobuild_runfile_links_with_run_under() {

  mkdir data && echo "hello" > data/hello && echo "world" > data/world

  cat > hello.sh <<'EOF'
#!/bin/bash
set -ex
[[ -f $0.runfiles/_main/data/hello ]]
exec "$@"
EOF

  cat > world.sh <<'EOF'
#!/bin/bash
set -ex
[[ -f $0.runfiles/_main/data/world ]]
exit 0
EOF

  chmod 755 hello.sh world.sh

  cat > BUILD <<'EOF'
sh_binary(
  name = "hello",
  srcs = ["hello.sh"],
  data = ["data/hello"],
)

sh_binary(
  name = "world",
  srcs = ["world.sh"],
  data = ["data/world"],
)
EOF

  bazel build --spawn_strategy=local --nobuild_runfile_links //:hello //:world \
    || fail "Building //:hello and //:world failed"

  [[ ! -f bazel-bin/hello.runfiles/_main/data/hello ]] || fail "expected no runfile data/hello"
  [[ ! -f bazel-bin/hello.runfiles/MANIFEST ]] || fail "expected output manifest hello to not exist"
  [[ ! -f bazel-bin/world.runfiles/_main/data/world ]] || fail "expected no runfile data/world"
  [[ ! -f bazel-bin/world.runfiles/MANIFEST ]] || fail "expected output manifest world to not exist"

  bazel run --spawn_strategy=local --nobuild_runfile_links --run_under //:hello //:world \
    || fail "Running //:hello and //:world failed"

  [[ -f bazel-bin/hello.runfiles/_main/data/hello ]] || fail "expected runfile data/hello to exist"
  [[ -f bazel-bin/hello.runfiles/MANIFEST ]] || fail "expected output manifest hello to exist"
  [[ -f bazel-bin/world.runfiles/_main/data/world ]] || fail "expected runfile data/world to exist"
  [[ -f bazel-bin/world.runfiles/MANIFEST ]] || fail "expected output manifest world to exist"
}

function test_switch_runfiles_from_enabled_to_disabled {
    echo '#!/bin/bash' > cmd.sh
    chmod 755 cmd.sh
    cat > BUILD <<'EOF'
sh_binary(
  name = "cmd",
  srcs = ["cmd.sh"],
  data = glob(["data-*"], allow_empty = True),
)
genrule(
  name = "g",
  cmd = "$(location :cmd) > $@",
  outs = ["out"],
  tools = [":cmd"],
)
EOF

    bazel build --spawn_strategy=local --nobuild_runfile_links //:out
    touch data-1
    bazel build --spawn_strategy=local --nobuild_runfile_links --enable_runfiles=false //:out
}

function setup_runfiles_tree_file_type_changes {
  add_bazel_skylib "MODULE.bazel"
  mkdir -p rules
  touch rules/BUILD
  cat > rules/defs.bzl <<'EOF'
def _make_fake_executable(ctx):
    fake_executable = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        output = fake_executable,
        content = "echo 'i do nothing'",
        is_executable = True,
    )
    return fake_executable

def _tree_artifact(ctx):
    d = ctx.actions.declare_directory("lib")
    ctx.actions.run_shell(
        outputs = [d],
        arguments = [d.path],
        command = """
touch $1/sample1.txt
touch $1/sample2.txt
""",
    )

    return DefaultInfo(
        runfiles = ctx.runfiles(symlinks = {"lib": d}),
    )

tree_artifact = rule(implementation = _tree_artifact)

def _individual_files(ctx):
    symlinks = {}
    for file in ctx.files.srcs:
        _, relative_path = file.path.split("/", 1)
        symlinks[relative_path] = file
    return DefaultInfo(
        runfiles = ctx.runfiles(symlinks = symlinks),
    )

individual_files = rule(
    implementation = _individual_files,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
    },
)

def _output_impl(ctx):
    return DefaultInfo(
        runfiles = ctx.attr.src[DefaultInfo].default_runfiles,
        executable = _make_fake_executable(ctx),
    )

output = rule(
    implementation = _output_impl,
    executable = True,
    attrs = {
        "src": attr.label(),
    },
)
EOF

  mkdir -p pkg/lib
  touch pkg/lib/sample1.txt
  touch pkg/lib/sample2.txt
  cat > pkg/BUILD <<'EOF'
load("@bazel_skylib//rules:common_settings.bzl", "bool_flag")
load("//rules:defs.bzl", "tree_artifact", "individual_files", "output")

bool_flag(
    name = "use_tree",
    build_setting_default = False,
)

config_setting(
    name = "should_use_tree",
    flag_values = {"//pkg:use_tree": "True"},
)

tree_artifact(name = "tree_artifact")

individual_files(
    name = "individual_files",
    srcs = glob(["lib/*"]),
)

output(
    name = "output",
    src = select({
        "//pkg:should_use_tree": ":tree_artifact",
        "//conditions:default": ":individual_files",
    }),
)
EOF
}

function test_runfiles_tree_file_type_changes_tree_to_individual {
  setup_runfiles_tree_file_type_changes

  bazel build --noexperimental_inprocess_symlink_creation \
    --//pkg:use_tree=True //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"

  bazel build --noexperimental_inprocess_symlink_creation \
    --//pkg:use_tree=False //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"
}

function test_runfiles_tree_file_type_changes_individual_to_tree {
  setup_runfiles_tree_file_type_changes

  bazel build --noexperimental_inprocess_symlink_creation \
    --//pkg:use_tree=False //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"

  bazel build --noexperimental_inprocess_symlink_creation \
    --//pkg:use_tree=True //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"
}

function test_runfiles_tree_file_type_changes_tree_to_individual_inprocess {
  setup_runfiles_tree_file_type_changes

  bazel build --experimental_inprocess_symlink_creation \
    --//pkg:use_tree=True //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"

  bazel build --experimental_inprocess_symlink_creation \
    --//pkg:use_tree=False //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"
}

function test_runfiles_tree_file_type_changes_individual_to_tree_inprocess {
  setup_runfiles_tree_file_type_changes

  bazel build --experimental_inprocess_symlink_creation \
    --//pkg:use_tree=False //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"

  bazel build --experimental_inprocess_symlink_creation \
    --//pkg:use_tree=True //pkg:output || fail "Build failed"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample1.txt ]] || fail "sample1.txt not found"
  [[ -f bazel-bin/pkg/output.runfiles/_main/lib/sample2.txt ]] || fail "sample2.txt not found"
}

run_suite "runfiles tests"
