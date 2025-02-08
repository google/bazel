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

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
set -euo pipefail
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# `uname` returns the current platform, e.g "MSYS_NT-10.0" or "Linux".
# `tr` converts all upper case letters to lower case.
# `case` matches the result if the `uname | tr` expression to string prefixes
# that use the same wildcards as names do in Bash, i.e. "msys*" matches strings
# starting with "msys", and "*" matches everything (it's the default case).
case "$(uname -s | tr [:upper:] [:lower:])" in
msys*)
  # As of 2018-08-14, Bazel on Windows only supports MSYS Bash.
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

if $is_windows; then
  export LC_ALL=C.utf8
elif [[ "$(uname -s)" == "Linux" ]]; then
  export LC_ALL=C.UTF-8
else
  export LC_ALL=en_US.UTF-8
fi

source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

mock_rules_java_to_avoid_downloading

# Make sure no repository cache is used in this test
add_to_bazelrc "common --repository_cache="

# Basic test.
function test_macro_local_repository() {
  create_new_workspace
  repo2=$new_workspace_dir

  mkdir -p carnivore
  cat > carnivore/BUILD <<'EOF'
genrule(
    name = "mongoose",
    cmd = "echo 'Tra-la!' | tee $@",
    outs = ["moogoose.txt"],
    visibility = ["//visibility:public"],
)
EOF

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
ext = use_extension("//:test.bzl", "repo_ext")
use_repo(ext, "endangered")
EOF

  # Empty package for the .bzl file
  echo -n >BUILD

  # Our macro
  cat >test.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
def macro(path):
  print('bleh')
  local_repository(name='endangered', path=path)

repo_ext = module_extension(implementation = lambda ctx: macro('$repo2'))
EOF
  mkdir -p zoo
  cat > zoo/BUILD <<'EOF'
genrule(
    name = "ball-pit",
    srcs = ["@endangered//carnivore:mongoose"],
    outs = ["ball-pit.txt"],
    cmd = "cat $< >$@",
)
EOF

  bazel build //zoo:ball-pit >& $TEST_log || fail "Failed to build"
  expect_log "bleh"
  expect_log "Tra-la!"  # Invalidation
  cat bazel-bin/zoo/ball-pit.txt >$TEST_log
  expect_log "Tra-la!"

  bazel build //zoo:ball-pit >& $TEST_log || fail "Failed to build"
  expect_not_log "Tra-la!"  # No invalidation

  # Test invalidation of the WORKSPACE file
  create_new_workspace
  repo2=$new_workspace_dir

  mkdir -p carnivore
  cat > carnivore/BUILD <<'EOF'
genrule(
    name = "mongoose",
    cmd = "echo 'Tra-la-la!' | tee $@",
    outs = ["moogoose.txt"],
    visibility = ["//visibility:public"],
)
EOF
  cd ${WORKSPACE_DIR}
  cat >test.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
def macro():
  print('blah')
  local_repository(name='endangered', path='$repo2')

repo_ext = module_extension(implementation = lambda ctx: macro())
EOF
  bazel build //zoo:ball-pit >& $TEST_log || fail "Failed to build"
  expect_log "blah"
  expect_log "Tra-la-la!"  # Invalidation
  cat bazel-bin/zoo/ball-pit.txt >$TEST_log
  expect_log "Tra-la-la!"

  bazel build //zoo:ball-pit >& $TEST_log || fail "Failed to build"
  expect_not_log "Tra-la-la!"  # No invalidation
}

function test_starlark_local_repository() {
  create_new_workspace
  repo2=$new_workspace_dir

  cat > BUILD <<'EOF'
genrule(name='bar', cmd='echo foo | tee $@', outs=['bar.txt'])
EOF

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
repo = use_repo_rule('//:test.bzl', 'repo')
repo(name='foo', path='$repo2')
EOF

  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.symlink(repository_ctx.path(repository_ctx.attr.path), repository_ctx.path(""))

repo = repository_rule(
    implementation=_impl,
    local=True,
    attrs={"path": attr.string(mandatory=True)})
EOF
  # Need to be in a package
  cat > BUILD

  bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "foo"
  cat bazel-bin/external/+_repo_rules+foo/bar.txt >$TEST_log
  expect_log "foo"
}

function setup_starlark_repository() {
  create_new_workspace
  repo2=$new_workspace_dir

  cat > bar.txt
  echo "filegroup(name='bar', srcs=['bar.txt'])" > BUILD

  cd "${WORKSPACE_DIR}"
  cat > $(setup_module_dot_bazel) <<EOF
repo = use_repo_rule('//:test.bzl', 'repo')
repo(name = 'foo')
EOF
  # Need to be in a package
  cat > BUILD
}

function test_starlark_flags_affect_repository_rule() {
  setup_starlark_repository

  cat >test.bzl <<EOF
def _impl(repository_ctx):
  print("In repo rule: ")
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))

repo = repository_rule(implementation=_impl, local=True)
EOF

  MARKER="<== Starlark flag test ==>"

  bazel build @foo//:bar >& $TEST_log \
    || fail "Expected build to succeed"
  expect_log "In repo rule: " "Did not find repository rule print output"
  expect_not_log "$MARKER" \
      "Marker string '$MARKER' was seen even though \
      --internal_starlark_flag_test_canary wasn't passed"

  # Build with the special testing flag that appends a marker string to all
  # print() calls.
  bazel build @foo//:bar --internal_starlark_flag_test_canary >& $TEST_log \
    || fail "Expected build to succeed"
  expect_log "In repo rule: $MARKER" \
      "Starlark flags are not propagating to repository rule implementation \
      function evaluation"
}

function test_starlark_repository_which_and_execute() {
  setup_starlark_repository

  echo "#!/bin/sh" > bin.sh
  echo "exit 0" >> bin.sh
  chmod +x bin.sh

  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
  bash = repository_ctx.which("bash")
  if bash == None:
    fail("Bash not found!")
  bin = repository_ctx.which("bin.sh")
  if bin == None:
    fail("bin.sh not found!")
  result = repository_ctx.execute([bash, "--version"], 10, {"FOO": "BAR"})
  if result.return_code != 0:
    fail("Non-zero return code from bash: " + str(result.return_code))
  if result.stderr != "":
    fail("Non-empty error output: " + result.stderr)
  print(result.stdout)
repo = repository_rule(implementation=_impl, local=True)
EOF

  # Test we are using the client environment, not the server one
  bazel info &> /dev/null  # Start up the server.

  FOO="BAZ" PATH="${PATH}:${PWD}" bazel build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "version"
}

function test_starlark_repository_execute_stderr() {
  setup_starlark_repository

  cat >test.bzl <<EOF
def _impl(repository_ctx):
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
  result = repository_ctx.execute([str(repository_ctx.which("bash")), "-c", "echo erf >&2; exit 1"])
  if result.return_code != 1:
    fail("Incorrect return code from bash: %s != 1\n%s" % (result.return_code, result.stderr))
  if result.stdout != "":
    fail("Non-empty output: %s (stderr was %s)" % (result.stdout, result.stderr))
  print(result.stderr)
  repository_ctx.execute([str(repository_ctx.which("bash")), "-c", "echo shhhh >&2"], quiet = False)

repo = repository_rule(implementation=_impl, local=True)
EOF

  bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "erf"
  expect_log "shhhh"
}

function test_starlark_repository_execute_env_and_workdir() {
  setup_starlark_repository

  cat >test.bzl <<EOF
def _impl(repository_ctx):
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
  result = repository_ctx.execute(
    [str(repository_ctx.which("bash")), "-c", "echo PWD=\$PWD TOTO=\$TOTO"],
    1000000,
    { "TOTO": "titi" },
    working_directory = "$repo2")
  if result.return_code != 0:
    fail("Incorrect return code from bash: %s != 0\n%s" % (result.return_code, result.stderr))
  print(result.stdout)
repo = repository_rule(implementation=_impl, local=True)
EOF

  bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  if "$is_windows"; then
    repo2="$(cygpath $repo2)"
  fi
  expect_log "PWD=$repo2 TOTO=titi"
}

function test_starlark_repository_unicode() {
  setup_starlark_repository

  if "$is_windows"; then
    # äöüÄÖÜß in UTF-8
    local unicode=$(echo -e '\xC3\xA4\xC3\xB6\xC3\xBC\xC3\x84\xC3\x96\xC3\x9C\xC3\x9F')
  else
    # äöüÄÖÜß🌱 in UTF-8
    local unicode=$(echo -e '\xC3\xA4\xC3\xB6\xC3\xBC\xC3\x84\xC3\x96\xC3\x9C\xC3\x9F\xF0\x9F\x8C\xB1')
  fi

  tmpdir="$(mktemp -d ${TEST_TMPDIR}/test.XXXXXXXX)"
  input_file="${tmpdir}/input$unicode"
  echo -n "$unicode" > "${input_file}"

  cat >test.bzl <<EOF
UNICODE = "$unicode"

def _impl(repository_ctx):
  repository_ctx.file("BUILD", "filegroup(name='bar', srcs=[])")

  input_file = repository_ctx.getenv("INPUT_" + UNICODE)
  if not input_file:
    fail("INPUT_%s not found in environment" % UNICODE)
  input_content = repository_ctx.read(input_file)
  if input_content != UNICODE:
    fail("Incorrect content in input file: %s != %s" % (input_content, UNICODE))

  repository_ctx.template(
      "template" + UNICODE + ".txt",
      input_file,
      substitutions = {
        UNICODE: UNICODE + "_replaced_" + UNICODE,
      },
  )

  repository_ctx.file("direct" + UNICODE + ".txt", UNICODE)

  result = repository_ctx.execute(
      [str(repository_ctx.which("bash")), "-c", "echo '%s' >indirect%s.txt" % (UNICODE, UNICODE)]
  )
  if result.return_code != 0:
    fail("Incorrect return code from bash: %s != 0\n%s" % (result.return_code, result.stderr))

  result = repository_ctx.execute([str(repository_ctx.which("bash")), "-c", "echo '%s'" % UNICODE])
  if result.return_code != 0:
    fail("Incorrect return code from bash: %s != 0\n%s" % (result.return_code, result.stderr))
  if result.stdout.strip() != UNICODE:
    fail("Incorrect output from bash: %s != %s\n%s" % (result.stdout.strip(), UNICODE, result.stderr))

  result = repository_ctx.execute([str(repository_ctx.which("bash")), "-c", "echo '%s' && exit 123" % UNICODE])
  if result.return_code != 123:
    fail("Incorrect return code from bash: %s != 123\n%s" % (result.return_code, result.stderr))
  if result.stdout.strip() != UNICODE:
    fail("Incorrect output from bash: %s != %s\n%s" % (result.stdout.strip(), UNICODE, result.stderr))

  repository_ctx.file("foo.txt", UNICODE)
  read_content = repository_ctx.read("foo.txt")
  if read_content != UNICODE:
    fail("Incorrect content in foo.txt: %s != %s" % (read_content, UNICODE))

  print("UNICODE = %s" % UNICODE)
repo = repository_rule(implementation=_impl)
EOF

  bazel build "--repo_env=INPUT_$unicode=${input_file}" @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "UNICODE = $unicode"
  output_base="$(bazel info output_base)"
  assert_contains "$unicode" "$output_base/external/+_repo_rules+foo/direct${unicode}.txt"
  assert_contains "$unicode" "$output_base/external/+_repo_rules+foo/indirect${unicode}.txt"
  assert_contains "${unicode}_replaced_${unicode}" "$output_base/external/+_repo_rules+foo/template${unicode}.txt"

  # The repo rule should not be re-run on server restart
  bazel shutdown
  bazel build "--repo_env=INPUT_${unicode}=${input_file}" @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_not_log "UNICODE"
}

function test_starlark_repository_environ() {
  setup_starlark_repository

  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  print(repository_ctx.os.environ["FOO"])
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
repo = repository_rule(implementation=_impl, local=False)
EOF

  # TODO(dmarting): We should seriously have something better to force a refetch...
  bazel clean --expunge
  FOO=BAR bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "BAR"

  FOO=BAR bazel clean --expunge >& $TEST_log
  FOO=BAR bazel info >& $TEST_log

  FOO=BAZ bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "BAZ"

  # Test that we don't re-run on server restart.
  FOO=BEZ bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_not_log "BEZ"
  bazel shutdown >& $TEST_log
  FOO=BEZ bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_not_log "BEZ"

  # Test that --action_env value is taken
  # TODO(dmarting): The current implemnentation cannot invalidate on environment
  # but the incoming change can declare environment dependency, once this is
  # done, maybe we should update this test to remove clean --expunge and use the
  # invalidation mechanism instead?
  bazel clean --expunge
  FOO=BAZ bazel build --action_env=FOO=BAZINGA @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "BAZINGA"

  bazel clean --expunge
  FOO=BAZ bazel build --action_env=FOO @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "BAZ"
  expect_not_log "BAZINGA"

  # Test modifying test.bzl invalidate the repository
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  print(repository_ctx.os.environ["BAR"])
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
repo = repository_rule(implementation=_impl, local=True)
EOF
  BAR=BEZ bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "BEZ"

  # Shutdown and modify again
  bazel shutdown
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  print(repository_ctx.os.environ["BAZ"])
  # Symlink so a repository is created
  repository_ctx.symlink(repository_ctx.path("$repo2"), repository_ctx.path(""))
repo = repository_rule(implementation=_impl, local=True)
EOF
  BAZ=BOZ bazel build @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "BOZ"
}

function write_environ_starlark() {
  local execution_file="$1"
  local environ="$2"

  cat >test.bzl <<EOF
load("//:environ.bzl", "environ")

def _impl(repository_ctx):
  # This might cause a function restart, do it first
  foo = environ(repository_ctx, "FOO")
  bar = environ(repository_ctx, "BAR")
  baz = environ(repository_ctx, "BAZ")
  repository_ctx.template("bar.txt", Label("//:bar.tpl"), {
      "%{FOO}": foo,
      "%{BAR}": bar,
      "%{BAZ}": baz}, False)

  exe_result = repository_ctx.execute(["cat", "${execution_file}"]);
  execution = int(exe_result.stdout.strip()) + 1
  repository_ctx.execute(["bash", "-c", "echo %s >${execution_file}" % execution])
  print("<%s> FOO=%s BAR=%s BAZ=%s" % (execution, foo, bar, baz))
  repository_ctx.file("BUILD", "filegroup(name='bar', srcs=['bar.txt'])")

repo = repository_rule(implementation=_impl, environ=[${environ}])
EOF
}

function setup_invalidation_test() {
  local startup_flag="${1-}"
  setup_starlark_repository

  # We use a counter to avoid other invalidation to hide repository
  # invalidation (e.g., --action_env will cause all action to re-run).
  local execution_file="${TEST_TMPDIR}/execution"

  # Our custom repository rule
  cat >environ.bzl <<EOF
def environ(r_ctx, var):
  return r_ctx.os.environ[var] if var in r_ctx.os.environ else "undefined"
EOF

  write_environ_starlark "${execution_file}" '"FOO", "BAR"'

  cat <<EOF >bar.tpl
FOO=%{FOO} BAR=%{BAR} BAZ=%{BAZ}
EOF

  bazel ${startup_flag} clean --expunge
  echo 0 >"${execution_file}"
  echo "${execution_file}"
}

# Test invalidation based on environment variable
function environ_invalidation_test_template() {
  local startup_flag="${1-}"
  local execution_file="$(setup_invalidation_test)"
  FOO=BAR bazel ${startup_flag} build @foo//:bar >& $TEST_log \
     || fail "Failed to build"
  expect_log "<1> FOO=BAR BAR=undefined BAZ=undefined"
  assert_equals 1 $(cat "${execution_file}")
  FOO=BAR bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 1 $(cat "${execution_file}")

  # Test that changing FOO is causing a refetch
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<2> FOO=BAZ BAR=undefined BAZ=undefined"
  assert_equals 2 $(cat "${execution_file}")
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 2 $(cat "${execution_file}")

  # Test that changing BAR is causing a refetch
  FOO=BAZ BAR=FOO bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<3> FOO=BAZ BAR=FOO BAZ=undefined"
  assert_equals 3 $(cat "${execution_file}")
  FOO=BAZ BAR=FOO bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 3 $(cat "${execution_file}")

  # Test that changing BAZ is not causing a refetch
  FOO=BAZ BAR=FOO BAZ=BAR bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 3 $(cat "${execution_file}")

  # Test more change in the environment
  FOO=BAZ BAR=FOO BEZ=BAR bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 3 $(cat "${execution_file}")

  # Test that removing BEZ is not causing a refetch
  FOO=BAZ BAR=FOO bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 3 $(cat "${execution_file}")

  # Test that removing BAR is causing a refetch
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<4> FOO=BAZ BAR=undefined BAZ=undefined"
  assert_equals 4 $(cat "${execution_file}")
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 4 $(cat "${execution_file}")

  # Now try to depends on more variables
  write_environ_starlark "${execution_file}" '"FOO", "BAR", "BAZ"'

  # The Starlark rule has changed, so a rebuild should happen
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<5> FOO=BAZ BAR=undefined BAZ=undefined"
  assert_equals 5 $(cat "${execution_file}")
  FOO=BAZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 5 $(cat "${execution_file}")

  # Now a change to BAZ should trigger a rebuild
  FOO=BAZ BAZ=BEZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<6> FOO=BAZ BAR=undefined BAZ=BEZ"
  assert_equals 6 $(cat "${execution_file}")
  FOO=BAZ BAZ=BEZ bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  assert_equals 6 $(cat "${execution_file}")
}

function environ_invalidation_action_env_test_template() {
  local startup_flag="${1-}"
  setup_starlark_repository

  # We use a counter to avoid other invalidation to hide repository
  # invalidation (e.g., --action_env will cause all action to re-run).
  local execution_file="$(setup_invalidation_test)"

  # Set to FOO=BAZ BAR=FOO
  FOO=BAZ BAR=FOO bazel ${startup_flag} build @foo//:bar >& $TEST_log \
      || fail "Failed to build"
  expect_log "<1> FOO=BAZ BAR=FOO BAZ=undefined"
  assert_equals 1 $(cat "${execution_file}")

  # Test with changing using --action_env
  bazel ${startup_flag} build \
      --action_env FOO=BAZ --action_env BAR=FOO  --action_env BEZ=BAR \
      @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 1 $(cat "${execution_file}")
  bazel ${startup_flag} build \
      --action_env FOO=BAZ --action_env BAR=FOO --action_env BAZ=BAR \
      @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 1 $(cat "${execution_file}")
  bazel ${startup_flag} build \
      --action_env FOO=BAR --action_env BAR=FOO --action_env BAZ=BAR \
      @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<2> FOO=BAR BAR=FOO BAZ=BAR"
  assert_equals 2 $(cat "${execution_file}")
}

function test_starlark_repository_environ_invalidation() {
  environ_invalidation_test_template
}

# Same test as previous but with server restart between each invocation
function test_starlark_repository_environ_invalidation_batch() {
  environ_invalidation_test_template --batch
}

function test_starlark_repository_environ_invalidation_action_env() {
  environ_invalidation_action_env_test_template
}

function test_starlark_repository_environ_invalidation_action_env_batch() {
  environ_invalidation_action_env_test_template --batch
}

# Test invalidation based on change to the bzl files
function bzl_invalidation_test_template() {
  local startup_flag="${1-}"
  local execution_file="$(setup_invalidation_test)"
  local flags="--action_env FOO=BAR --action_env BAR=BAZ --action_env BAZ=FOO"

  local bazel_build="bazel ${startup_flag} build ${flags}"

  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<1> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 1 $(cat "${execution_file}")
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 1 $(cat "${execution_file}")

  # Changing the Starlark file cause a refetch
  cat <<EOF >>test.bzl

# Just add a comment
EOF
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<2> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 2 $(cat "${execution_file}")
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 2 $(cat "${execution_file}")

  # But also changing the environ.bzl file does a refetch
  cat <<EOF >>environ.bzl

# Just add a comment
EOF
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<3> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 3 $(cat "${execution_file}")
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 3 $(cat "${execution_file}")
}

function test_starlark_repository_bzl_invalidation() {
  bzl_invalidation_test_template
}

# Same test as previous but with server restart between each invocation
function test_starlark_repository_bzl_invalidation_batch() {
  bzl_invalidation_test_template --batch
}

function test_starlark_repo_bzl_invalidation_wrong_digest() {
  # regression test for https://github.com/bazelbuild/bazel/pull/21131#discussion_r1471924084
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
ext = use_extension("//:r.bzl", "ext")
use_repo(ext, "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
load(":make_repo_rule.bzl", "make_repo_rule")
def _r(rctx):
  print("I'm here")
  rctx.file("BUILD", "filegroup(name='r')")
r=make_repo_rule(_r)
ext=module_extension(lambda mctx: r(name='r'))
EOF
  cat > make_repo_rule.bzl << EOF
def make_repo_rule(impl):
  return repository_rule(impl)
EOF

  bazel build @r >& $TEST_log || fail "Failed to build"
  expect_log "I'm here"

  cat <<EOF >>r.bzl

# Just add a comment
EOF
  # the above SHOULD trigger a refetch.
  bazel build @r >& $TEST_log || fail "failed to build"
  expect_log "I'm here"
}

# Test invalidation based on change to the bzl files
function file_invalidation_test_template() {
  local startup_flag="${1-}"
  local execution_file="$(setup_invalidation_test)"
  local flags="--action_env FOO=BAR --action_env BAR=BAZ --action_env BAZ=FOO"

  local bazel_build="bazel ${startup_flag} build ${flags}"

  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<1> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 1 $(cat "${execution_file}")
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 1 $(cat "${execution_file}")

  # Changing the Starlark file cause a refetch
  cat <<EOF >>bar.tpl
Add more stuff
EOF
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  expect_log "<2> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 2 $(cat "${execution_file}")
  ${bazel_build} @foo//:bar >& $TEST_log || fail "Failed to build"
  assert_equals 2 $(cat "${execution_file}")
}

function test_starlark_repository_file_invalidation() {
  file_invalidation_test_template
}

# Same test as previous but with server restart between each invocation
function test_starlark_repository_file_invalidation_batch() {
  file_invalidation_test_template --batch
}

# Test invalidation based on changes of the Starlark semantics
function starlark_invalidation_test_template() {
  local startup_flag="${1-}"
  local execution_file="$(setup_invalidation_test)"
  local flags="--action_env FOO=BAR --action_env BAR=BAZ --action_env BAZ=FOO"
  local bazel_build="bazel ${startup_flag} build ${flags}"

  ${bazel_build} --noincompatible_run_shell_command_string @foo//:bar \
    >& ${TEST_log} || fail "Expected success"
  expect_log "<1> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 1 $(cat "${execution_file}")

  echo; cat ${TEST_log}; echo

  ${bazel_build} --noincompatible_run_shell_command_string @foo//:bar \
    >& ${TEST_log} || fail "Expected success"
  assert_equals 1 $(cat "${execution_file}")

  echo; cat ${TEST_log}; echo

  # Changing the starlark semantics should invalidate once
  ${bazel_build} --incompatible_run_shell_command_string @foo//:bar \
    >& ${TEST_log} || fail "Expected success"
  expect_log "<2> FOO=BAR BAR=BAZ BAZ=FOO"
  assert_equals 2 $(cat "${execution_file}")
  ${bazel_build} --incompatible_run_shell_command_string @foo//:bar \
    >& ${TEST_log} || fail "Expected success"
  assert_equals 2 $(cat "${execution_file}")
}

function test_starlark_invalidation() {
    starlark_invalidation_test_template
}

function test_starlark_invalidation_batch() {
    starlark_invalidation_test_template --batch
}


function test_repo_env() {
  setup_starlark_repository

  cat > test.bzl <<'EOF'
def _impl(ctx):
  # Make a rule depending on the environment variable FOO,
  # properly recording its value. Also add a time stamp
  # to verify that the rule is rerun.
  ctx.execute(["bash", "-c", "echo FOO=$FOO > env.txt"])
  ctx.execute(["bash", "-c", "date +%s >> env.txt"])
  ctx.file("BUILD", 'exports_files(["env.txt"])')

repo = repository_rule(
  implementation = _impl,
  environ = ["FOO"],
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "repoenv",
  outs = ["repoenv.txt"],
  srcs = ["@foo//:env.txt"],
  cmd = "cp $< $@",
)

# Have a normal rule, unrelated to the external repository.
# To test if it was rerun, make it non-hermetic and record a
# time stamp.
genrule(
  name = "unrelated",
  outs = ["unrelated.txt"],
  cmd = "date +%s > $@",
)
EOF
  cat > .bazelrc <<EOF
common:foo --repo_env=FOO=foo
build:bar --repo_env=FOO=bar
common:qux --repo_env=FOO
EOF

  bazel build --config=foo //:repoenv //:unrelated
  cp `bazel info bazel-bin 2>/dev/null`/repoenv.txt repoenv1.txt
  cp `bazel info bazel-bin 2> /dev/null`/unrelated.txt unrelated1.txt
  echo; cat repoenv1.txt; echo; cat unrelated1.txt; echo

  grep -q 'FOO=foo' repoenv1.txt \
      || fail "Expected FOO to be visible to repo rules"

  sleep 2 # ensure any rerun will have a different time stamp

  FOO=CHANGED bazel build --config=foo //:repoenv //:unrelated
  # nothing should change, as actions don't see FOO and for repositories
  # the value is fixed by --repo_env
  cp `bazel info bazel-bin 2>/dev/null`/repoenv.txt repoenv2.txt
  cp `bazel info bazel-bin 2> /dev/null`/unrelated.txt unrelated2.txt
  echo; cat repoenv2.txt; echo; cat unrelated2.txt; echo

  diff repoenv1.txt repoenv2.txt \
      || fail "Expected repository to not change"
  diff unrelated1.txt unrelated2.txt \
      || fail "Expected unrelated action to not be rerun"

  bazel build --config=bar //:repoenv //:unrelated
  # The new config should be picked up, but the unrelated target should
  # not be rerun
  cp `bazel info bazel-bin 3>/dev/null`/repoenv.txt repoenv3.txt
  cp `bazel info bazel-bin 3> /dev/null`/unrelated.txt unrelated3.txt
  echo; cat repoenv3.txt; echo; cat unrelated3.txt; echo

  grep -q 'FOO=bar' repoenv3.txt \
      || fail "Expected FOO to be visible to repo rules"
  diff unrelated1.txt unrelated3.txt \
      || fail "Expected unrelated action to not be rerun"

  FOO=qux bazel build --config=qux //:repoenv //:unrelated
  # The new config should be picked up, but the unrelated target should
  # not be rerun
  cp `bazel info bazel-genfiles 3>/dev/null`/repoenv.txt repoenv4.txt
  cp `bazel info bazel-genfiles 3> /dev/null`/unrelated.txt unrelated4.txt
  echo; cat repoenv4.txt; echo; cat unrelated4.txt; echo

  grep -q 'FOO=qux' repoenv4.txt \
      || fail "Expected FOO to be visible to repo rules"
  diff unrelated1.txt unrelated4.txt \
      || fail "Expected unrelated action to not be rerun"
}

function test_repo_env_inverse() {
  # This test makes sure that a repository rule that has no dependencies on
  # environment variables does _not_ get refetched when --repo_env changes.
  setup_starlark_repository

  cat > test.bzl <<'EOF'
def _impl(ctx):
  # Record a time stamp to verify that the rule is not rerun.
  ctx.execute(["bash", "-c", "date +%s >> env.txt"])
  ctx.file("BUILD", 'exports_files(["env.txt"])')

repo = repository_rule(
  implementation = _impl,
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "repoenv",
  outs = ["repoenv.txt"],
  srcs = ["@foo//:env.txt"],
  cmd = "cp $< $@",
)
EOF
  cat > .bazelrc <<EOF
build:foo --repo_env=FOO=foo
build:bar --repo_env=FOO=bar
EOF

  bazel build --config=foo //:repoenv
  cp `bazel info bazel-bin 2>/dev/null`/repoenv.txt repoenv1.txt
  echo; cat repoenv1.txt; echo;

  sleep 2 # ensure any rerun will have a different time stamp

  bazel build --config=bar //:repoenv
  # The new config should not trigger a rerun of repoenv.
  cp `bazel info bazel-bin 2>/dev/null`/repoenv.txt repoenv2.txt
  echo; cat repoenv2.txt; echo;

  diff repoenv1.txt repoenv2.txt \
      || fail "Expected repository to not change"
}

function test_repo_env_invalidation() {
    # regression test for https://github.com/bazelbuild/bazel/issues/8869
    WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
    cd "${WRKDIR}"
    cat > $(setup_module_dot_bazel) <<'EOF'
my_repository_rule = use_repo_rule("//:my_repository_rule.bzl", "my_repository_rule")

my_repository_rule(
    name = "my_repository_rule",
)
EOF
    cat > my_repository_rule.bzl <<'EOF'
def _my_repository_rule_impl(rctx):
    foo = rctx.os.environ.get("foo", default = "")

    print('foo = ' + foo)

    rctx.file("BUILD.bazel",
              "exports_files(['update_time'], visibility=['//visibility:public'])")
    rctx.execute(["bash", "-c", "date +%s > update_time"])

my_repository_rule = repository_rule(
    environ = ["foo"],
    implementation = _my_repository_rule_impl,
)
EOF
    cat > BUILD <<'EOF'
genrule(
  name = "repotime",
  outs = ["repotime.txt"],
  srcs = ["@my_repository_rule//:update_time"],
  cmd = "cp $< $@",
)
EOF

    bazel build //:repotime
    cp `bazel info bazel-bin 2>/dev/null`/repotime.txt time1.txt

    sleep 2;
    bazel build --repo_env=foo=bar //:repotime
    cp `bazel info bazel-bin 2>/dev/null`/repotime.txt time2.txt
    diff time1.txt time2.txt && fail "Expected repo to be refetched" || :

    bazel shutdown
    sleep 2;

    bazel build --repo_env=foo=bar //:repotime
    cp `bazel info bazel-bin 2>/dev/null`/repotime.txt time3.txt
    diff time2.txt time3.txt || fail "Expected repo to not be refetched"
}

function test_starlark_repository_executable_flag() {
  if "$is_windows"; then
    # There is no executable flag on Windows.
    echo "Skipping test_starlark_repository_executable_flag on Windows"
    return
  fi
  setup_starlark_repository

  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("test.sh", "exit 0")
  repository_ctx.file("BUILD", "genrule(name='bar',cmd='touch \$@',outs=['bar'],executable=True)", False)
  repository_ctx.template("test2", Label("//:bar"), {}, False)
  repository_ctx.template("test2.sh", Label("//:bar"), {}, True)
repo = repository_rule(implementation=_impl, local=True)
EOF
  cat >bar

  bazel run @foo//:bar >& $TEST_log || fail "Execution of @foo//:bar failed"
  output_base=$(bazel info output_base)
  test -x "${output_base}/external/+_repo_rules+foo/test.sh" || fail "test.sh is not executable"
  test -x "${output_base}/external/+_repo_rules+foo/test2.sh" || fail "test2.sh is not executable"
  test ! -x "${output_base}/external/+_repo_rules+foo/BUILD" || fail "BUILD is executable"
  test ! -x "${output_base}/external/+_repo_rules+foo/test2" || fail "test2 is executable"
}

function test_starlark_repository_download() {
  # Prepare HTTP server with Python
  local server_dir="${TEST_TMPDIR}/server_dir"
  mkdir -p "${server_dir}"
  local download_with_sha256="${server_dir}/download_with_sha256.txt"
  local download_executable_file="${server_dir}/download_executable_file.sh"
  echo "This is a file" > "${download_with_sha256}"
  echo "echo 'I am executable'" > "${download_executable_file}"
  file_sha256="$(sha256sum "${download_with_sha256}" | head -c 64)"
  file_exec_sha256="$(sha256sum "${download_executable_file}" | head -c 64)"

  # Start HTTP server with Python
  startup_server "${server_dir}"

  setup_starlark_repository
  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.download(
    "http://localhost:${fileserver_port}/download_with_sha256.txt",
    "download_with_sha256.txt", "${file_sha256}")
  repository_ctx.download(
    "http://localhost:${fileserver_port}/download_executable_file.sh",
    "download_executable_file.sh", executable=True, sha256="$file_exec_sha256")
  repository_ctx.file("BUILD")  # necessary directories should already created by download function
repo = repository_rule(implementation=_impl, local=False)
EOF

  bazel build @foo//:all >& $TEST_log && shutdown_server \
    || fail "Execution of @foo//:all failed"

  output_base="$(bazel info output_base)"
  # Test download
  test -e "${output_base}/external/+_repo_rules+foo/download_with_sha256.txt" \
    || fail "download_with_sha256.txt is not downloaded"
  test -e "${output_base}/external/+_repo_rules+foo/download_executable_file.sh" \
    || fail "download_executable_file.sh is not downloaded"
  # Test download
  diff "${output_base}/external/+_repo_rules+foo/download_with_sha256.txt" \
    "${download_with_sha256}" >/dev/null \
    || fail "download_with_sha256.txt is not downloaded successfully"
  diff "${output_base}/external/+_repo_rules+foo/download_executable_file.sh" \
    "${download_executable_file}" >/dev/null \
    || fail "download_executable_file.sh is not downloaded successfully"

  # No executable flag for file on Windows
  if "$is_windows"; then
    return
  fi

  # Test executable
  test ! -x "${output_base}/external/+_repo_rules+foo/download_with_sha256.txt" \
    || fail "download_with_sha256.txt is executable"
  test -x "${output_base}/external/+_repo_rules+foo/download_executable_file.sh" \
    || fail "download_executable_file.sh is not executable"
}

function test_starlark_repository_context_downloads_return_struct() {
   # Prepare HTTP server with Python
  local server_dir="${TEST_TMPDIR}/server_dir"
  mkdir -p "${server_dir}"
  local download_with_sha256="${server_dir}/download_with_sha256.txt"
  local download_no_sha256="${server_dir}/download_no_sha256.txt"
  local compressed_with_sha256="${server_dir}/compressed_with_sha256.txt"
  local compressed_no_sha256="${server_dir}/compressed_no_sha256.txt"
  echo "This is one file" > "${download_no_sha256}"
  echo "This is another file" > "${download_with_sha256}"
  echo "Compressed file with sha" > "${compressed_with_sha256}"
  echo "Compressed file no sha" > "${compressed_no_sha256}"
  zip "${compressed_with_sha256}".zip "${compressed_with_sha256}"
  zip "${compressed_no_sha256}".zip "${compressed_no_sha256}"

  provided_sha256="$(sha256sum "${download_with_sha256}" | head -c 64)"
  not_provided_sha256="$(sha256sum "${download_no_sha256}" | head -c 64)"
  compressed_provided_sha256="$(sha256sum "${compressed_with_sha256}.zip" | head -c 64)"
  compressed_not_provided_sha256="$(sha256sum "${compressed_no_sha256}.zip" | head -c 64)"

  # Start HTTP server with Python
  startup_server "${server_dir}"

  # On Windows, a file url should be file:///C:/foo/bar,
  # we need to add one more slash at the beginning.
  if "$is_windows"; then
    server_dir="/${server_dir}"
  fi

  setup_starlark_repository
  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  no_sha_return = repository_ctx.download(
    url = "file://${server_dir}/download_no_sha256.txt",
    output = "download_no_sha256.txt")
  with_sha_return = repository_ctx.download(
    url = "http://localhost:${fileserver_port}/download_with_sha256.txt",
    output = "download_with_sha256.txt",
    sha256 = "${provided_sha256}")
  compressed_no_sha_return = repository_ctx.download_and_extract(
    url = "file://${server_dir}/compressed_no_sha256.txt.zip",
    output = "compressed_no_sha256.txt.zip")
  compressed_with_sha_return = repository_ctx.download_and_extract(
      url = "http://localhost:${fileserver_port}/compressed_with_sha256.txt.zip",
      output = "compressed_with_sha256.txt.zip",
      sha256 = "${compressed_provided_sha256}")

  file_content = "no_sha_return " + no_sha_return.sha256 + "\n"
  file_content += "with_sha_return " + with_sha_return.sha256 + "\n"
  file_content += "compressed_no_sha_return " + compressed_no_sha_return.sha256 + "\n"
  file_content += "compressed_with_sha_return " + compressed_with_sha_return.sha256
  repository_ctx.file("returned_shas.txt", content = file_content, executable = False)
  repository_ctx.file("BUILD")  # necessary directories should already created by download function
repo = repository_rule(implementation = _impl, local = False)
EOF

  # This test case explicitly verifies that a checksum is returned, even if
  # none was provided by the call to download_and_extract. So we do have to
  # allow a download without provided checksum, even though it is plain http;
  # nevertheless, localhost is pretty safe against man-in-the-middle attacks.
  bazel build @foo//:all \
        >& $TEST_log && shutdown_server || fail "Execution of @foo//:all failed"

  output_base="$(bazel info output_base)"
  grep "no_sha_return $not_provided_sha256" $output_base/external/+_repo_rules+foo/returned_shas.txt \
      || fail "expected calculated sha256 $not_provided_sha256"
  grep "with_sha_return $provided_sha256" $output_base/external/+_repo_rules+foo/returned_shas.txt \
      || fail "expected provided sha256 $provided_sha256"
  grep "compressed_with_sha_return $compressed_provided_sha256" $output_base/external/+_repo_rules+foo/returned_shas.txt \
      || fail "expected provided sha256 $compressed_provided_sha256"
  grep "compressed_no_sha_return $compressed_not_provided_sha256" $output_base/external/+_repo_rules+foo/returned_shas.txt \
      || fail "expected compressed calculated sha256 $compressed_not_provided_sha256"
}

function test_starlark_repository_download_args() {
  # Prepare HTTP server with Python
  local server_dir="${TEST_TMPDIR}/server_dir"
  mkdir -p "${server_dir}"
  local download_1="${server_dir}/download_1.txt"
  local download_2="${server_dir}/download_2.txt"
  echo "The file's content" > "${download_1}"
  echo "The file's content" > "${download_2}"
  file_sha256="$(sha256sum "${download_1}" | head -c 64)"

  # Start HTTP server with Python
  startup_server "${server_dir}"

  create_new_workspace
  repo2=$new_workspace_dir

  cat > bar.txt
  echo "filegroup(name='bar', srcs=['bar.txt'])" > BUILD

  cat > $(setup_module_dot_bazel) <<EOF
repo = use_repo_rule('//:test.bzl', 'repo')
repo(name = 'foo',
     urls = [
       "http://localhost:${fileserver_port}/download_1.txt",
       "http://localhost:${fileserver_port}/download_2.txt",
     ],
     sha256 = "${file_sha256}",
     output = "whatever.txt"
)
EOF

  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download(
    repository_ctx.attr.urls,
    sha256 = repository_ctx.attr.sha256,
    output=repository_ctx.attr.output,
  )

repo = repository_rule(implementation=_impl,
      local=False,
      attrs = {
        "urls" : attr.string_list(),
        "output" : attr.string(),
        "sha256" : attr.string(),
     }
)
EOF

  bazel build @foo//:all >& $TEST_log && shutdown_server \
    || fail "Execution of @foo//:all failed"

  output_base="$(bazel info output_base)"
  # Test download
  test -e "${output_base}/external/+_repo_rules+foo/whatever.txt" \
    || fail "whatever.txt is not downloaded"
}


function test_starlark_repository_download_and_extract() {
  # Prepare HTTP server with Python
  local server_dir="${TEST_TMPDIR}/server_dir"
  mkdir -p "${server_dir}"
  local file_prefix="${server_dir}/download_and_extract"

  pushd ${TEST_TMPDIR}
  echo "This is one file" > server_dir/download_and_extract1.txt
  echo "This is another file" > server_dir/download_and_extract2.txt
  echo "This is a third file" > server_dir/download_and_extract3.txt
  tar -zcvf server_dir/download_and_extract1.tar.gz server_dir/download_and_extract1.txt
  zip server_dir/download_and_extract2.zip server_dir/download_and_extract2.txt
  zip server_dir/download_and_extract3.zip server_dir/download_and_extract3.txt
  file1_sha256="$(sha256sum server_dir/download_and_extract1.tar.gz | head -c 64)"
  file2_sha256="$(sha256sum server_dir/download_and_extract2.zip | head -c 64)"
  file3_sha256="$(sha256sum server_dir/download_and_extract3.zip | head -c 64)"
  popd

  # Start HTTP server with Python
  startup_server "${server_dir}"

  setup_starlark_repository
  # Our custom repository rule
  cat >test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download_and_extract(
    "http://localhost:${fileserver_port}/download_and_extract1.tar.gz", "", sha256="${file1_sha256}")
  repository_ctx.download_and_extract(
    "http://localhost:${fileserver_port}/download_and_extract2.zip", "", "${file2_sha256}")
  repository_ctx.download_and_extract(
    "http://localhost:${fileserver_port}/download_and_extract1.tar.gz", "some/path", sha256="${file1_sha256}")
  repository_ctx.download_and_extract(
    "http://localhost:${fileserver_port}/download_and_extract3.zip", ".", "${file3_sha256}", "", "")
  repository_ctx.download_and_extract(
    url = ["http://localhost:${fileserver_port}/download_and_extract3.zip"],
    output = "other/path",
    sha256 = "${file3_sha256}"
  )
repo = repository_rule(implementation=_impl, local=False)
EOF

  bazel clean --expunge_async >& $TEST_log || fail "bazel clean failed"
  bazel build @foo//:all >& $TEST_log && shutdown_server \
    || fail "Execution of @foo//:all failed"

  output_base="$(bazel info output_base)"
  # Test cleanup
  test -e "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract1.tar.gz" \
    && fail "temp file was not deleted successfully" || true
  test -e "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract2.zip" \
    && fail "temp file was not deleted successfully" || true
  test -e "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract3.zip" \
    && fail "temp file was not deleted successfully" || true
  # Test download_and_extract
  diff "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract1.txt" \
    "${file_prefix}1.txt" >/dev/null \
    || fail "download_and_extract1.tar.gz was not extracted successfully"
  diff "${output_base}/external/+_repo_rules+foo/some/path/server_dir/download_and_extract1.txt" \
    "${file_prefix}1.txt" >/dev/null \
    || fail "download_and_extract1.tar.gz was not extracted successfully in some/path"
  diff "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract2.txt" \
    "${file_prefix}2.txt" >/dev/null \
    || fail "download_and_extract2.zip was not extracted successfully"
  diff "${output_base}/external/+_repo_rules+foo/server_dir/download_and_extract3.txt" \
    "${file_prefix}3.txt" >/dev/null \
    || fail "download_and_extract3.zip was not extracted successfully"
  diff "${output_base}/external/+_repo_rules+foo/other/path/server_dir/download_and_extract3.txt" \
    "${file_prefix}3.txt" >/dev/null \
    || fail "download_and_extract3.tar.gz was not extracted successfully"
}


function test_timeout_tunable() {
  cat >> $(setup_module_dot_bazel)  <<'EOF'
with_timeout = use_repo_rule("//:repo.bzl", "with_timeout")

with_timeout(name="maytimeout")
EOF
  touch BUILD
  cat > repo.bzl <<'EOF'
def _impl(ctx):
  st =ctx.execute(["bash", "-c", "sleep 10 && echo Hello world > data.txt"],
                  timeout=1)
  if st.return_code:
    fail("Command did not succeed")
  ctx.file("BUILD", "exports_files(['data.txt'])")

with_timeout = repository_rule(attrs = {}, implementation = _impl)
EOF
  bazel fetch --repo=@maytimeout && fail "expected timeout" || :

  bazel fetch --repo=@maytimeout --experimental_scale_timeouts=100 \
      || fail "expected success now the timeout is scaled"

  bazel build @maytimeout//... \
      || fail "expected success after successful sync"
}

# Common setup logic for test_auth_*.
# Receives as arguments the username and password for basic authentication.
# If no arguments are provided, no authentication is used.
function setup_auth() {
  if [[ $# -ne 0 && $# -ne 2 ]]; then
    fail "setup_auth expects exactly zero or two arguments"
  fi

  mkdir -p x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256="$(sha256sum x.tar | head -c 64)"
  serve_file_auth x.tar

  if [[ $# -eq 2 ]]; then
    local -r username=$1
    local -r password=$2
    cat > auth_attrs.bzl <<EOF
AUTH_ATTRS = {
  "type": "basic",
  "login" : "$username",
  "password" : "$password",
}
EOF
  else
    cat > auth_attrs.bzl <<'EOF'
AUTH_ATTRS = None
EOF
  fi

  cat > auth.bzl <<'EOF'
load("//:auth_attrs.bzl", "AUTH_ATTRS")
def _impl(ctx):
  ctx.download_and_extract(
    url = ctx.attr.url,
    sha256 = ctx.attr.sha256,
    auth = { ctx.attr.url: AUTH_ATTRS } if AUTH_ATTRS else {},
  )

maybe_with_auth = repository_rule(
  implementation = _impl,
  attrs = {
    "url" : attr.string(),
    "sha256" : attr.string(),
  }
)
EOF

  cat > $(setup_module_dot_bazel) <<EOF
maybe_with_auth = use_repo_rule("//:auth.bzl", "maybe_with_auth")
maybe_with_auth(
  name = "ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  sha256 = "$sha256",
)
EOF

  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
}

function test_auth_from_starlark() {
  setup_auth foo bar

  bazel build //:it \
      || fail "Expected success when downloading repo with basic auth"
}

function test_auth_from_credential_helper() {
  if "$is_windows"; then
    # Skip on Windows: credential helper is a Python script.
    return
  fi

  setup_credential_helper

  setup_auth # no auth

  bazel build //:it \
      && fail "Expected failure when downloading repo without credential helper"

  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" //:it \
      || fail "Expected success when downloading repo with credential helper"

  expect_credential_helper_calls 1

  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" //:it \
      || fail "Expected success when downloading repo with credential helper"

  expect_credential_helper_calls 1 # expect credentials to have been cached
}

function test_auth_from_credential_helper_overrides_starlark() {
  if "$is_windows"; then
    # Skip on Windows: credential helper is a Python script.
    return
  fi

  setup_credential_helper

  setup_auth baduser badpass

  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" //:it \
      || fail "Expected success when downloading repo with credential helper overriding basic auth"
}

function test_netrc_reading() {
  # Write a badly formatted, but correct, .netrc file
  cat > .netrc <<'EOF'
machine ftp.example.com
macdef init
cd pub
mget *
quit

# this is a comment
machine example.com login
myusername password mysecret default
login anonymous password myusername@example.com
EOF
  # We expect that `read_netrc` can parse this file...
  cat > def.bzl <<'EOF'
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "read_netrc")
def _impl(ctx):
  rc = read_netrc(ctx, ctx.attr.path)
  ctx.file("data.bzl", "netrc = %s" % (rc,))
  ctx.file("BUILD", "")
  ctx.file("REPO.bazel", "")

netrcrepo = repository_rule(
  implementation = _impl,
  attrs = {"path": attr.string()},
)
EOF

  netrc_dir="$(pwd)"
  if "$is_windows"; then
    netrc_dir="$(cygpath -m ${netrc_dir})"
  fi

  cat > $(setup_module_dot_bazel) <<EOF
netrcrepo = use_repo_rule("//:def.bzl", "netrcrepo")

netrcrepo(name = "netrc", path="${netrc_dir}/.netrc")
EOF
  # ...and that from the parse result, we can read off the
  # credentials for example.com.
  cat > BUILD <<'EOF'
load("@netrc//:data.bzl", "netrc")

[genrule(
  name = name,
  outs = [ "%s.txt" % (name,)],
  cmd = "echo %s > $@" % (netrc["example.com"][name],),
) for name in ["login", "password"]]
EOF
  bazel build //:login //:password
  grep 'myusername' `bazel info bazel-bin`/login.txt \
       || fail "Username not parsed correctly"
  grep 'mysecret' `bazel info bazel-bin`/password.txt \
       || fail "Password not parsed correctly"

  # Also check the precise value of parsed file
  cat > expected.bzl <<'EOF'
expected = {
  "ftp.example.com" : { "macdef init" : "cd pub\nmget *\nquit\n" },
  "example.com" : { "login" : "myusername",
                    "password" : "mysecret",
                  },
  "" : { "login": "anonymous",
          "password" : "myusername@example.com" },
}
EOF
  cat > verify.bzl <<'EOF'
load("@netrc//:data.bzl", "netrc")
load("//:expected.bzl", "expected")

def check_equal_expected():
  print("Parsed value:   %s" % (netrc,))
  print("Expected value: %s" % (expected,))
  if netrc == expected:
    return "OK"
  else:
    return "BAD"
EOF
  cat > BUILD <<'EOF'
load ("//:verify.bzl", "check_equal_expected")
genrule(
  name = "check_expected",
  outs = ["check_expected.txt"],
  cmd = "echo %s > $@" % (check_equal_expected(),)
)
EOF
  bazel build //:check_expected
  grep 'OK' `bazel info bazel-bin`/check_expected.txt \
       || fail "Parsed dict not equal to expected value"
}

function test_use_netrc() {
    # Test the starlark utility function use_netrc.
  cat > .netrc <<'EOF'
machine foo.example.org
login foousername
password foopass

machine bar.example.org
login barusername
password passbar🌱

# following lines mix tabs and spaces
machine	  oauthlife.com
	password	TOKEN
EOF
  # Read a given .netrc file and combine it with a list of URL,
  # and write the obtained authentication dictionary to disk; this
  # is not the intended way of using, but makes testing easy.
  cat > def.bzl <<'EOF'
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "read_netrc", "use_netrc")
def _impl(ctx):
  print("authrepo is being evaluated")
  rc = read_netrc(ctx, ctx.attr.path)
  auth = use_netrc(rc, ctx.attr.urls, {"oauthlife.com": "Bearer <password>",})
  ctx.file("data.bzl", "auth = %s" % (auth,))
  ctx.file("BUILD", "")
  ctx.file("WORKSPACE", "")

authrepo = repository_rule(
  implementation = _impl,
  attrs = {"path": attr.string(),
           "urls": attr.string_list()
  },
)
EOF

  netrc_dir="$(pwd)"
  if "$is_windows"; then
    netrc_dir="$(cygpath -m ${netrc_dir})"
  fi

  cat > $(setup_module_dot_bazel) <<EOF
authrepo = use_repo_rule("//:def.bzl", "authrepo")

authrepo(
  name = "auth",
  path="${netrc_dir}/.netrc",
  urls = [
    "http://example.org/public/null.tar",
    "https://foo.example.org/file1.tar",
    "https://foo.example.org:8080/file2.tar",
    "https://bar.example.org/file3.tar",
    "https://evil.com/bar.example.org/file4.tar",
    "https://oauthlife.com/fizz/buzz/file5.tar",
  ],
)
EOF
  # Here dicts give us the correct notion of equality, so we can simply
  # compare against the expected value.
  cat > expected.bzl <<'EOF'
expected = {
    "https://foo.example.org/file1.tar" : {
      "type" : "basic",
      "login": "foousername",
      "password" : "foopass",
    },
    "https://foo.example.org:8080/file2.tar" : {
      "type" : "basic",
      "login": "foousername",
      "password" : "foopass",
    },
    "https://bar.example.org/file3.tar" : {
      "type" : "basic",
      "login": "barusername",
      "password" : "passbar🌱",
    },
    "https://oauthlife.com/fizz/buzz/file5.tar": {
      "type" : "pattern",
      "pattern" : "Bearer <password>",
      "password" : "TOKEN",
    },
}
EOF
  cat > verify.bzl <<'EOF'
load("@auth//:data.bzl", "auth")
load("//:expected.bzl", "expected")

def check_equal_expected():
  print("Computed value: %s" % (auth,))
  print("Expected value: %s" % (expected,))
  if auth == expected:
    return "OK"
  else:
    return "BAD"
EOF
  cat > BUILD <<'EOF'
load ("//:verify.bzl", "check_equal_expected")
genrule(
  name = "check_expected",
  outs = ["check_expected.txt"],
  cmd = "echo %s > $@" % (check_equal_expected(),)
)
EOF
  bazel build //:check_expected &> $TEST_log || fail "Expected success"
  grep 'OK' `bazel info bazel-bin`/check_expected.txt \
       || fail "Authentication merged incorrectly"
  expect_log "authrepo is being evaluated"

  echo "modified" > .netrc
  bazel build //:check_expected &> $TEST_log || fail "Expected success"
  grep 'OK' `bazel info bazel-bin`/check_expected.txt \
       || fail "Authentication information should not have been reevaluated"
  expect_not_log "authrepo is being evaluated"
}

function test_disallow_unverified_http() {
  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256="$(sha256sum x.tar | head -c 64)"
  serve_file x.tar
  cat > MODULE.bazel <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it > "${TEST_log}" 2>&1 && fail "Expected failure" || :
  expect_log 'plain http.*missing checksum'

  # After adding a good checksum, we expect success
  ed MODULE.bazel <<EOF
/url
a
sha256 = "$sha256",
.
w
q
EOF
  bazel build //:it || fail "Expected success one the checksum is given"

}

function tear_down() {
  shutdown_server
  if [ -d "${TEST_TMPDIR}/server_dir" ]; then
    rm -fr "${TEST_TMPDIR}/server_dir"
  fi
  true
}

function test_http_archive_netrc() {
  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file_auth x.tar
  netrc_dir="$(pwd)"
  if "$is_windows"; then
    netrc_dir="$(cygpath -m ${netrc_dir})"
  fi
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  netrc = "${netrc_dir}/.netrc",
  sha256="$sha256",
)
EOF
  cat > .netrc <<'EOF'
machine 127.0.0.1
login foo
password bar
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it \
      || fail "Expected success despite needing a file behind basic auth"
}

function test_http_archive_auth_patterns() {
  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file_auth x.tar
  netrc_dir="$(pwd)"
  if "$is_windows"; then
    netrc_dir="$(cygpath -m ${netrc_dir})"
  fi
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  netrc = "${netrc_dir}/.netrc",
  sha256="$sha256",
  auth_patterns = {
    "127.0.0.1": "Bearer <password>"
  }
)
EOF
  cat > .netrc <<'EOF'
machine 127.0.0.1
password TOKEN
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it \
      || fail "Expected success despite needing a file behind bearer auth"
}

function test_http_archive_implicit_netrc() {
  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file_auth x.tar

  export HOME=`pwd`
  if "$is_windows"; then
    export USERPROFILE="$(cygpath -m ${HOME})"
  fi
  cat > .netrc <<'EOF'
machine 127.0.0.1
login foo
password bar
EOF

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  sha256="$sha256",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it \
      || fail "Expected success despite needing a file behind basic auth"
}

function test_http_archive_credential_helper() {
  if "$is_windows"; then
    # Skip on Windows: credential helper is a Python script.
    return
  fi

  setup_credential_helper

  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file_auth x.tar
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  sha256="$sha256",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" //:it \
      || fail "Expected success despite needing a file behind credential helper"
}

function test_http_archive_credential_helper_overrides_netrc() {
  if "$is_windows"; then
    # Skip on Windows: credential helper is a Python script.
    return
  fi

  setup_credential_helper

  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file_auth x.tar

  export HOME=`pwd`
  if "$is_windows"; then
    export USERPROFILE="$(cygpath -m ${HOME})"
  fi
  cat > .netrc <<'EOF'
machine 127.0.0.1
login badusername
password badpassword
EOF

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  sha256="$sha256",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" //:it \
      || fail "Expected success despite needing a file behind credential helper"
}

function test_disable_download_should_prevent_downloading() {
  mkdir x
  echo 'exports_files(["file.txt"])' > x/BUILD
  echo 'Hello World' > x/file.txt
  tar cvf x.tar x
  sha256=$(sha256sum x.tar | head -c 64)
  serve_file x.tar

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  url = "http://127.0.0.1:$nc_port/x.tar",
  sha256="$sha256",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//x:file.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel build --repository_disable_download --repository_cache= //:it > "${TEST_log}" 2>&1 \
      && fail "Expected failure" || :
  expect_log "Failed to download repository @.*: download is disabled"
}

function test_no_restarts_fetching_with_worker_thread() {
  setup_starlark_repository

  echo foo > file1
  echo bar > file2

  cat >test.bzl <<EOF
def _impl(rctx):
  print("hello world!")
  print(rctx.read(Label("//:file1")))
  print(rctx.read(Label("//:file2")))
  rctx.file("BUILD", "filegroup(name='bar')")

repo = repository_rule(implementation=_impl, local=True)
EOF

  # no worker thread, restarts twice
  bazel build @foo//:bar --experimental_worker_for_repo_fetching=off >& $TEST_log \
    || fail "Expected build to succeed"
  expect_log_n "hello world!" 3

  # platform worker thread, never restarts
  bazel shutdown
  bazel build @foo//:bar --experimental_worker_for_repo_fetching=platform >& $TEST_log \
    || fail "Expected build to succeed"
  expect_log_n "hello world!" 1

  # virtual worker thread, never restarts
  bazel shutdown
  bazel build @foo//:bar --experimental_worker_for_repo_fetching=virtual >& $TEST_log \
    || fail "Expected build to succeed"
  expect_log_n "hello world!" 1
}

function test_duplicate_value_in_environ() {
  cat > MODULE.bazel <<EOF
repo = use_repo_rule('//:def.bzl', 'repo')
repo(name='foo')
EOF

  touch BUILD
  cat > def.bzl <<'EOF'
def _impl(repository_ctx):
  repository_ctx.file("REPO.bazel")
  repository_ctx.file("BUILD", """filegroup(name="bar",srcs=[])""")

repo = repository_rule(
    implementation=_impl,
    environ=["FOO", "FOO"],
)
EOF

  FOO=bar bazel build @foo//:bar >& $TEST_log \
    || fail "Expected build to succeed"
}


function test_cred_helper_overrides_starlark_headers() {
  if "$is_windows"; then
    # Skip on Windows: credential helper is a Python script.
    return
  fi

  setup_credential_helper

  filename="cred_helper_starlark.txt"
  echo $filename > $filename
  sha256="$(sha256sum $filename | head -c 64)"
  serve_file_header_dump $filename credhelper_headers.json

  setup_starlark_repository

  cat > test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download(
    url = "http://127.0.0.1:$nc_port/$filename",
    output = "test.txt",
    sha256 = "$sha256",
    headers = {
      "Authorization": ["should be overidden"],
      "X-Custom-Token": ["foo"]
    }
  )

repo = repository_rule(implementation=_impl)
EOF


  bazel build --credential_helper="${TEST_TMPDIR}/credhelper" @foo//:all || fail "expected bazel to succeed"

  headers="${TEST_TMPDIR}/credhelper_headers.json"
  assert_contains '"Authorization": "Bearer TOKEN"' "$headers"
  assert_contains '"X-Custom-Token": "foo"' "$headers"
  assert_not_contains "should be overidden" "$headers"
}

function test_netrc_overrides_starlark_headers() {
  filename="netrc_headers.txt"
  echo $filename > $filename
  sha256="$(sha256sum $filename | head -c 64)"
  serve_file_header_dump $filename netrc_headers.json

  setup_starlark_repository

  cat > .netrc <<EOF
machine 127.0.0.1
login foo
password bar
EOF

  cat > test.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "read_netrc", "use_netrc")

def _impl(repository_ctx):
  url = "http://127.0.0.1:$nc_port/$filename"
  netrc = read_netrc(repository_ctx, repository_ctx.attr.netrc)
  auth = use_netrc(netrc, [url], {})
  repository_ctx.file("BUILD")
  repository_ctx.download(
    url = url,
    output = "$filename",
    sha256 = "$sha256",
    headers = {
      "Authorization": ["should be overidden"],
      "X-Custom-Token": ["foo"]
    },
    auth = auth
  )

repo = repository_rule(implementation=_impl, attrs = {"netrc": attr.label(default = ":.netrc")})
EOF


  bazel build @foo//:all || fail "expected bazel to succeed"

  headers="${TEST_TMPDIR}/netrc_headers.json"
  assert_contains '"Authorization": "Basic Zm9vOmJhcg=="' "$headers"
  assert_contains '"X-Custom-Token": "foo"' "$headers"
  assert_not_contains "should be overidden" "$headers"
}


function test_starlark_headers_override_default_headers() {

  filename="default_headers.txt"
  echo $filename > $filename
  sha256="$(sha256sum $filename | head -c 64)"
  serve_file_header_dump $filename default_headers.json

  setup_starlark_repository

  cat > test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download(
    url = "http://127.0.0.1:$nc_port/$filename",
    output = "$filename",
    sha256 = "$sha256",
    headers = {
      "Accept": ["application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json"],
    }
  )

repo = repository_rule(implementation=_impl)
EOF

  bazel build @foo//:all || fail "expected bazel to succeed"

  headers="${TEST_TMPDIR}/default_headers.json"
  assert_contains '"Accept": "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json"' "$headers"
  assert_not_contains '"Accept": "text/html, image/gif, image/jpeg, */*"' "$headers"
}

function test_invalid_starlark_headers() {

  filename="invalid_headers.txt"
  echo $filename > $filename
  sha256="$(sha256sum $filename | head -c 64)"
  serve_file_header_dump $filename invalid_headers.json

  setup_starlark_repository

  cat > test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download(
    url = "http://127.0.0.1:$nc_port/$filename",
    output = "$filename",
    sha256 = "$sha256",
    headers = {
      "Accept": 1,
    }
  )

repo = repository_rule(implementation=_impl)
EOF

  bazel build @foo//:all >& $TEST_log && fail "expected bazel to fail" || :
  expect_log "headers argument must be a dict whose keys are string and whose values are either string or sequence of string"
}

function test_string_starlark_headers() {

  filename="string_headers.txt"
  echo $filename > $filename
  sha256="$(sha256sum $filename | head -c 64)"
  serve_file_header_dump $filename string_headers.json

  setup_starlark_repository

  cat > test.bzl <<EOF
def _impl(repository_ctx):
  repository_ctx.file("BUILD")
  repository_ctx.download(
    url = "http://127.0.0.1:$nc_port/$filename",
    output = "$filename",
    sha256 = "$sha256",
    headers = {
      "Accept": "application/text",
    }
  )

repo = repository_rule(implementation=_impl)
EOF

  bazel build @foo//:all || fail "expected bazel to succeed"
  headers="${TEST_TMPDIR}/string_headers.json"
  assert_contains '"Accept": "application/text"' "$headers"
}

function test_repo_boundary_files() {
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r', srcs=glob(['*']))")
r = repository_rule(_r)
EOF

  bazel query --output=build @r > output || fail "expected bazel to succeed"
  assert_contains 'REPO.bazel' output
}

function test_repo_mapping_change_in_rule_impl() {
  # regression test for #20722
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
bazel_dep(name="foo", repo_name="data")
local_path_override(module_name="foo", path="foo")
bazel_dep(name="bar")
local_path_override(module_name="bar", path="bar")
EOF
  touch BUILD
  echo 'load("@r//:r.bzl", "pi"); print(pi)' > WORKSPACE.bzlmod
  cat > r.bzl <<EOF
def _r(rctx):
  print("I see: " + str(Label("@data")))
  rctx.file("BUILD", "filegroup(name='r')")
  rctx.file("r.bzl", "pi=3.14")
r=repository_rule(_r)
EOF
  mkdir foo
  cat > foo/MODULE.bazel <<EOF
module(name="foo")
EOF
  mkdir bar
  cat > bar/MODULE.bazel <<EOF
module(name="bar")
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: @@foo+//:data"

  # So far, so good. Now we make `@data` point to bar instead!
  cat > MODULE.bazel <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
bazel_dep(name="foo")
local_path_override(module_name="foo", path="foo")
bazel_dep(name="bar", repo_name="data")
local_path_override(module_name="bar", path="bar")
EOF
  # for the repo `r`, nothing except the repo mapping has changed.
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: @@bar+//:data"
}

function test_repo_mapping_change_in_bzl_init() {
  # same as above, but tests .bzl init time repo mapping usages
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
bazel_dep(name="foo", repo_name="data")
local_path_override(module_name="foo", path="foo")
bazel_dep(name="bar")
local_path_override(module_name="bar", path="bar")
EOF
  touch BUILD
  echo 'load("@r//:r.bzl", "pi"); print(pi)' > WORKSPACE.bzlmod
  cat > r.bzl <<EOF
CONSTANT = Label("@data")
def _r(rctx):
  print("I see: " + str(CONSTANT))
  rctx.file("BUILD", "filegroup(name='r')")
  rctx.file("r.bzl", "pi=3.14")
r=repository_rule(_r)
EOF
  mkdir foo
  cat > foo/MODULE.bazel <<EOF
module(name="foo")
EOF
  mkdir bar
  cat > bar/MODULE.bazel <<EOF
module(name="bar")
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: @@foo+//:data"

  # So far, so good. Now we make `@data` point to bar instead!
  cat > MODULE.bazel <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
bazel_dep(name="foo")
local_path_override(module_name="foo", path="foo")
bazel_dep(name="bar", repo_name="data")
local_path_override(module_name="bar", path="bar")
EOF
  # for the repo `r`, nothing except the repo mapping has changed.
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: @@bar+//:data"
}

function test_file_watching_inside_working_dir() {
  # when reading a file inside the working directory (where the repo
  # is to be fetched), we shouldn't watch it.
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  rctx.file("data.txt", "nothing")
  print("I see: " + rctx.read("data.txt"))
  rctx.file("data.txt", "something")
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"

  # Running Bazel again shouldn't cause a refetch, despite "data.txt"
  # having been written to after the read.
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"
}

function test_file_watching_inside_working_dir_forcing_error() {
  # when reading a file inside the working directory (where the repo
  # is to be fetched), we shouldn't watch it. Forcing the watch should
  # result in an error.
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  rctx.file("data.txt", "nothing")
  print("I see: " + rctx.read("data.txt", watch="yes"))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log && fail "expected bazel to fail"
  expect_log "attempted to watch path under working directory"
}

function test_file_watching_outside_workspace() {
  # when reading a file outside the Bazel workspace, we should watch it.
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  mkdir -p "${outside_dir}"
  echo nothing > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  print("I see: " + rctx.read("${outside_dir}/data.txt"))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"

  # Running Bazel again shouldn't cause a refetch.
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # But changing the outside file should cause a refetch.
  echo something > ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: something"
}

function test_file_watching_in_other_repo() {
  # when reading a file in another repo, we should watch it.
  local outside_dir="${TEST_TMPDIR}/outside_dir"
  mkdir -p "${outside_dir}"
  echo nothing > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "nothing")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  # intentionally grab a file that's not directly addressable by a label
  otherfile = rctx.path(Label("@bar//subpkg:BUILD")).dirname.dirname.get_child("data.txt")
  print("I see: " + rctx.read(otherfile))
foo=repository_rule(_foo)
def _bar(rctx):
  rctx.file("subpkg/BUILD")
  rctx.file("data.txt", rctx.attr.data)
bar=repository_rule(_bar, attrs={"data":attr.string()})
EOF

  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"

  # Running Bazel again shouldn't cause a refetch.
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # But changing the file inside @bar should cause @foo to refetch.
  cat > MODULE.bazel <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "something")
EOF
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: something"
}

function test_incompatible_no_implicit_watch_label() {
  # when reading a file through a label with watch="no", we should not watch it.
  local outside_dir="${TEST_TMPDIR}/outside_dir"
  mkdir -p "${outside_dir}"
  echo nothing > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "fire")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  # Resolve both through rctx.path and directly.
  label = Label("@bar//subpkg:data.txt")
  print("(path) I see: " + rctx.read(rctx.path(label), watch="no"))
  print("(direct) I see: " + rctx.read(label, watch="no"))
foo=repository_rule(_foo)
def _bar(rctx):
  rctx.file("subpkg/BUILD")
  rctx.file("subpkg/data.txt", rctx.attr.data)
bar=repository_rule(_bar, attrs={"data":attr.string()})
EOF

  bazel build --incompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "(path) I see: fire"
  expect_log "(direct) I see: fire"

  # Running Bazel again shouldn't cause a refetch.
  bazel build --incompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # Even changing the file shouldn't cause a refetch.
  cat > MODULE.bazel <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "nothing")
EOF
  bazel build --incompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"
}

function test_no_incompatible_no_implicit_watch_label() {
  # when reading a file through a label with watch="no", we do watch it.
  local outside_dir="${TEST_TMPDIR}/outside_dir"
  mkdir -p "${outside_dir}"
  echo nothing > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "fire")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  # Resolve both through rctx.path and directly.
  label = Label("@bar//subpkg:data.txt")
  print("(direct) I see: " + rctx.read(label, watch="no"))
foo=repository_rule(_foo)
def _bar(rctx):
  rctx.file("subpkg/BUILD")
  rctx.file("subpkg/data.txt", rctx.attr.data)
bar=repository_rule(_bar, attrs={"data":attr.string()})
EOF

  bazel build --noincompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "(direct) I see: fire"

  # Running Bazel again shouldn't cause a refetch.
  bazel build --noincompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # Changing the file should cause a refetch.
  cat > MODULE.bazel <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "nothing")
EOF
  bazel build --noincompatible_no_implicit_watch_label @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see:"
}

function test_bad_marker_file_ignored() {
  # when reading a file in another repo, we should watch it.
  local outside_dir="${TEST_TMPDIR}/outside_dir"
  mkdir -p "${outside_dir}"
  echo nothing > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar", data = "nothing")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  # intentionally grab a file that's not directly addressable by a label
  otherfile = rctx.path(Label("@bar//subpkg:BUILD")).dirname.dirname.get_child("data.txt")
  print("I see: " + rctx.read(otherfile))
foo=repository_rule(_foo)
def _bar(rctx):
  rctx.file("subpkg/BUILD")
  rctx.file("data.txt", rctx.attr.data)
bar=repository_rule(_bar, attrs={"data":attr.string()})
EOF

  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"

  local marker_file=$(bazel info output_base)/external/@+_repo_rules+foo.marker
  # the marker file for this repo should contain a reference to "@@+_repo_rules2+bar". Mangle that.
  sed -i'' -e 's/@@+_repo_rules2+bar/@@LOL@@LOL/g' ${marker_file}

  # Running Bazel again shouldn't crash, and should result in a refetch.
  bazel shutdown
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"

  # Test to clear the marker file.
  echo > ${marker_file}

  # Running Bazel again shouldn't crash, and should result in a refetch.
  bazel shutdown
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: nothing"
}

function test_file_watching_in_undefined_repo() {
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:foo.bzl", "foo")
foo(name = "foo")
EOF
  touch BUILD
  cat > foo.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  # this repo might not have been defined yet
  rctx.watch("../+_repo_rules2+bar/BUILD")
  print("I see something!")
foo=repository_rule(_foo)
EOF

  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see something!"

  # Defining @@+_repo_rules+bar should now cause @foo to refetch.
  cat >> MODULE.bazel <<EOF
bar = use_repo_rule("//:bar.bzl", "bar")
bar(name = "bar")
EOF
  cat > bar.bzl <<EOF
def _bar(rctx):
  rctx.file("BUILD", "filegroup(name='bar')")
bar=repository_rule(_bar)
EOF
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see something!"

  # However, just adding a random other repo shouldn't alert @foo.
  cat >> MODULE.bazel <<EOF
bar(name = "baz")
EOF
  bazel build @foo >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see something!"
}

function test_file_watching_in_other_repo_cycle() {
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
foo = use_repo_rule("//:r.bzl", "foo")
foo(name = "foo")
bar = use_repo_rule("//:r.bzl", "bar")
bar(name = "bar")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _foo(rctx):
  rctx.file("BUILD", "filegroup(name='foo')")
  print("I see: " + rctx.read(Label("@bar//:BUILD")))
foo=repository_rule(_foo)
def _bar(rctx):
  rctx.file("BUILD", "filegroup(name='bar')")
  print("I see: " + rctx.read(Label("@foo//:BUILD")))
bar=repository_rule(_bar)
EOF

  bazel build @foo >& $TEST_log && fail "expected bazel to fail!"
  expect_log "Circular definition of repositories"
}

function test_watch_file_status_change() {
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  mkdir -p "${outside_dir}"
  echo something > ${outside_dir}/data.txt

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  data_file = rctx.path("${outside_dir}/data.txt")
  rctx.watch(data_file)
  if not data_file.exists:
    print("I see nothing")
  elif data_file.is_dir:
    print("I see a directory")
  else:
    print("I see: " + rctx.read(data_file))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: something"

  # test that all kinds of transitions between file, dir, and noent are watched

  rm ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see nothing"

  mkdir ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see a directory"

  rm -r ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see nothing"

  echo something again > ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: something again"

  rm ${outside_dir}/data.txt
  mkdir ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see a directory"

  rm -r ${outside_dir}/data.txt
  echo something yet again > ${outside_dir}/data.txt
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: something yet again"
}

function test_watch_file_status_change_dangling_symlink() {
  if "$is_windows"; then
    # symlinks on Windows... annoying
    return
  fi
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  mkdir -p "${outside_dir}"
  ln -s ${outside_dir}/pointee ${outside_dir}/pointer

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  data_file = rctx.path("${outside_dir}/pointer")
  rctx.watch(data_file)
  if not data_file.exists:
    print("I see nothing")
  elif data_file.is_dir:
    print("I see a directory")
  else:
    print("I see: " + rctx.read(data_file))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see nothing"

  echo haha > ${outside_dir}/pointee
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: haha"

  rm ${outside_dir}/pointee
  mkdir ${outside_dir}/pointee
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see a directory"
}

function test_watch_file_status_change_symlink_parent() {
  if "$is_windows"; then
    # symlinks on Windows... annoying
    return
  fi
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  mkdir -p "${outside_dir}/a"

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  data_file = rctx.path("${outside_dir}/a/b/c")
  rctx.watch(data_file)
  if not data_file.exists:
    print("I see nothing")
  elif data_file.is_dir:
    print("I see a directory")
  else:
    print("I see: " + rctx.read(data_file))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see nothing"

  mkdir -p ${outside_dir}/a/b
  echo blah > ${outside_dir}/a/b/c
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: blah"

  rm -rf ${outside_dir}/a/b
  ln -s ${outside_dir}/d ${outside_dir}/a/b
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see nothing"

  mkdir ${outside_dir}/d
  echo bleh > ${outside_dir}/d/c
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: bleh"
}

function test_path_readdir_watches_dirents() {
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  touch ${outside_dir}/foo
  touch ${outside_dir}/bar
  touch ${outside_dir}/baz

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  outside_dir = rctx.path("${outside_dir}")
  print("I see: " + ",".join(sorted([p.basename for p in outside_dir.readdir()])))
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: bar,baz,foo"

  # changing the contents of a file under there shouldn't trigger a refetch.
  echo haha > ${outside_dir}/foo
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # adding a file should trigger a refetch.
  touch ${outside_dir}/quux
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: bar,baz,foo,quux"

  # removing a file should trigger a refetch.
  rm ${outside_dir}/baz
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I see: bar,foo,quux"

  # changing a file to a directory shouldn't trigger a refetch.
  rm ${outside_dir}/bar
  mkdir ${outside_dir}/bar
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"

  # changing entries in subdirectories shouldn't trigger a refetch.
  touch ${outside_dir}/bar/inner
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I see:"
}

function test_watch_tree() {
  local outside_dir=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  touch ${outside_dir}/foo
  touch ${outside_dir}/bar
  touch ${outside_dir}/baz

  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r = use_repo_rule("//:r.bzl", "r")
r(name = "r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
def _r(rctx):
  rctx.file("BUILD", "filegroup(name='r')")
  print("I'm running!")
  rctx.watch_tree("${outside_dir}")
r=repository_rule(_r)
EOF

  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I'm running!"

  # changing the contents of a file under there should trigger a refetch.
  echo haha > ${outside_dir}/foo
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I'm running!"

  # adding a file should trigger a refetch.
  touch ${outside_dir}/quux
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I'm running!"

  # just touching an existing file shouldn't cause a refetch.
  touch ${outside_dir}/bar
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_not_log "I'm running!"

  # changing a file to a directory should trigger a refetch.
  rm ${outside_dir}/baz
  mkdir ${outside_dir}/baz
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I'm running!"

  # changing entries in subdirectories should trigger a refetch.
  touch ${outside_dir}/baz/inner
  bazel build @r >& $TEST_log || fail "expected bazel to succeed"
  expect_log "I'm running!"
}

# Regression test for https://github.com/bazelbuild/bazel/issues/21823.
function test_repository_cache_concurrency() {
  sha=cd55a062e763b9349921f0f5db8c3933288dc8ba4f76dd9416aac68acee3cb94

  cat > MODULE.bazel <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
[
  http_file(
    name = "repo" + str(i),
    url = "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/1.5.0/bazel-skylib-1.5.0.tar.gz",
    sha256 = "$sha",
  )
  for i in range(100)
]
EOF
  cat > BUILD <<'EOF'
FILES = ["@repo{}//file".format(i) for i in range(100)]
filegroup(
  name = "files",
  srcs = FILES,
)
genrule(
  name = "unique_hashes",
  srcs = [":files"],
  outs = ["unique_hashes"],
  cmd = """
# Get the unique sha256 hashes of the files.
sha256sum $(execpaths :files) |
  cut -d' ' -f1 |
  sort |
  uniq > $@
""".format(),
)
EOF

  repo_cache_dir=$TEST_TMPDIR/repository_cache
  trap 'rm -rf ${repo_cache_dir}' EXIT
  bazel build --repository_cache="$repo_cache_dir" \
    //:unique_hashes >& $TEST_log || fail "expected bazel to succeed"
  assert_equals 1 "$(wc -l < bazel-bin/unique_hashes | tr -d ' ')"
  assert_equals $sha "$(cat bazel-bin/unique_hashes)"

  # Verify that the repository cache entry has been created.
  cache_entry="$repo_cache_dir/content_addressable/sha256/$sha/file"
  echo "$sha $cache_entry" | sha256sum --check || fail "sha256 mismatch"
}

function test_keep_going_weird_deadlock() {
  # regression test for b/330892334
  if "$is_windows"; then
    # no symlinks on windows
    return
  fi
  create_new_workspace
  cat > $(setup_module_dot_bazel) <<EOF
r=use_repo_rule("//:r.bzl", "r")
r(name="r")
EOF
  touch BUILD
  cat > r.bzl <<EOF
r=repository_rule(lambda rctx: rctx.read(Label("//blarg")))
EOF

  # make //blarg a bad package by creating a symlink cycle (note that just the package not
  # existing is not enough to make the PackageLookupFunction fail)
  mkdir blarg
  cd blarg
  ln -s BUILD DUILB
  ln -s DUILB BUILD
  cd ..

  # now build both //blarg and @r. The latter depends on the former, which is in error.
  # with --keep_going, this could result in a deadlock.
  bazel build --keep_going //blarg @r >& $TEST_log && fail "bazel somehow succeeded"
  expect_log "no such package 'blarg': Symlink cycle detected while trying to find BUILD file"
}

function test_legacy_label_print() {
    WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
    cd "${WRKDIR}"
    cat > $(setup_module_dot_bazel) <<'EOF'
my_repository_rule = use_repo_rule("//:my_repository_rule.bzl", "my_repository_rule")

my_repository_rule(
    name = "my_first_repo",
)

my_repository_rule(
    name = "my_second_repo",
)
EOF
    cat > my_repository_rule.bzl <<'EOF'
def _my_repository_rule_impl(rctx):
    print("main repo:", Label("@@//:foo"), str(Label("@@//:foo")))
    print("my_first_repo:", Label("@my_first_repo//:foo"), str(Label("@my_first_repo//:foo")))
    print("my_second_repo:", Label("@my_first_repo//:foo"), str(Label("@my_first_repo//:foo")))
    rctx.file("WORKSPACE")
    rctx.file("BUILD", "filegroup(name='foo',visibility=['//visibility:public'])")

my_repository_rule = repository_rule(
    implementation = _my_repository_rule_impl,
)
EOF
    cat > BUILD <<'EOF'
filegroup(
    name = "foo",
    srcs = [
        "@my_first_repo//:foo",
        "@my_second_repo//:foo",
    ],
)
EOF
    bazel build //:foo >& $TEST_log || fail "expected bazel to succeed"
    expect_log "main repo: //:foo @@//:foo"
    expect_log "my_first_repo: @my_first_repo//:foo @@+_repo_rules+my_first_repo//:foo"
    expect_log "my_second_repo: @my_first_repo//:foo @@+_repo_rules+my_first_repo//:foo"
}

function test_execute_environment_remove_vars() {
  cat >> $(setup_module_dot_bazel)  <<'EOF'
my_repo = use_repo_rule("//:repo.bzl", "my_repo")
my_repo(name="repo")
EOF
  touch BUILD
  cat > repo.bzl <<'EOF'
def _impl(ctx):
  st = ctx.execute(
    ["env"],
    environment = {
      "CLIENT_ENV_REMOVED": None,
      "REPO_ENV_REMOVED": None,
    },
  )
  if st.return_code:
    fail("Command did not succeed")
  vars = {line.partition("=")[0]: line.partition("=")[-1] for line in st.stdout.split("\n")}
  if "CLIENT_ENV_REMOVED" in vars:
    fail("CLIENT_ENV_REMOVED should not be in the environment")
  if "REPO_ENV_REMOVED" in vars:
    fail("REPO_ENV_REMOVED should not be in the environment")
  if vars.get("CLIENT_ENV_PRESENT") != "value1":
    fail("CLIENT_ENV_PRESENT has wrong value: " + vars.get("CLIENT_ENV_PRESENT"))
  if vars.get("REPO_ENV_PRESENT") != "value3":
    fail("REPO_ENV_PRESENT has wrong value: " + vars.get("REPO_ENV_PRESENT"))

  ctx.file("BUILD", "exports_files(['data.txt'])")

my_repo = repository_rule(_impl)
EOF

  CLIENT_ENV_PRESENT=value1 CLIENT_ENV_REMOVED=value2 \
   bazel build \
    --repo_env=REPO_ENV_PRESENT=value3 --repo_env=REPO_ENV_REMOVED=value4 \
    @repo//... &> $TEST_log || fail "expected Bazel to succeed"
}

function test_dependency_on_repo_with_invalid_name() {
  cat >> $(setup_module_dot_bazel) <<'EOF'
my_repo = use_repo_rule("//:repo.bzl", "my_repo")
my_repo(name="repo")
EOF
  touch BUILD
  cat > repo.bzl <<'EOF'
def _impl(ctx):
  ctx.read("../@invalid_name@/file")

my_repo = repository_rule(_impl)
EOF

  bazel build @repo//... &> $TEST_log && fail "expected Bazel to fail"
  expect_not_log "Unrecoverable error"
  expect_log "attempted to watch path under external repository directory: invalid repository name '@invalid_name@'"
}

run_suite "local repository tests"
