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
# Test rules provided in Bazel not tested by examples
#

set -u
ADDITIONAL_BUILD_FLAGS=$1
WORKER_TYPE_LOG_STRING=$2
shift 2

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# TODO(philwo): Change this so the path to the custom worker gets passed in as an argument to the
# test, once the bug that makes using the "args" attribute with sh_tests in Bazel impossible is
# fixed.
example_worker=$(find $BAZEL_RUNFILES -name ExampleWorkerMultiplexer_deploy.jar)

add_to_bazelrc "build -s"
add_to_bazelrc "build --spawn_strategy=worker,standalone"
add_to_bazelrc "build --worker_verbose --worker_max_instances=3"
add_to_bazelrc "build --worker_multiplex"
add_to_bazelrc "build ${ADDITIONAL_BUILD_FLAGS}"

function set_up() {
  # Run each test in a separate folder so that their output files don't get cached.
  WORKSPACE_SUBDIR=$(basename $(mktemp -d ${WORKSPACE_DIR}/testXXXXXX))
  cd ${WORKSPACE_SUBDIR}
  BINS=$(bazel info $PRODUCT_NAME-bin)/${WORKSPACE_SUBDIR}

  add_rules_java ${WORKSPACE_DIR}/MODULE.bazel

  # This causes Bazel to shut down all running workers.
  bazel build --worker_quit_after_build &> $TEST_log \
    || fail "'bazel build --worker_quit_after_build' during test set_up failed"
}

function prepare_example_worker() {
  cp ${example_worker} worker_lib.jar
  chmod +w worker_lib.jar
  echo "exampledata" > worker_data.txt

  mkdir worker_data_dir
  echo "veryexample" > worker_data_dir/more_data.txt

  cat >work.bzl <<'EOF'
def _impl(ctx):
  worker = ctx.executable.worker
  output = ctx.outputs.out

  argfile_inputs = []
  argfile_arguments = []
  if ctx.attr.multiflagfiles:
    # Generate one flagfile per command-line arg, alternate between @ and --flagfile= style.
    # This is used to test the code that handles multiple flagfiles and the --flagfile= style.
    idx = 1
    for arg in ["--output_file=" + output.path] + ctx.attr.args:
      argfile = ctx.actions.declare_file("%s_worker_input_%s" % (ctx.label.name, idx))
      ctx.actions.write(output=argfile, content=arg)
      argfile_inputs.append(argfile)
      flagfile_prefix = "@" if (idx % 2 == 0) else "--flagfile="
      argfile_arguments.append(flagfile_prefix + argfile.path)
      idx += 1
  else:
    # Generate the "@"-file containing the command-line args for the unit of work.
    argfile = ctx.actions.declare_file("%s_worker_input" % ctx.label.name)
    argfile_contents = "\n".join(["--output_file=" + output.path] + ctx.attr.args)
    ctx.actions.write(output=argfile, content=argfile_contents)
    argfile_inputs.append(argfile)
    argfile_arguments.append("@" + argfile.path)

  ctx.actions.run(
      inputs=argfile_inputs + ctx.files.srcs,
      outputs=[output],
      executable=worker,
      progress_message="Working on %s" % ctx.label.name,
      mnemonic="Work",
      execution_requirements={
        "supports-multiplex-workers": "1",
        "supports-multiplex-sandboxing": "1",
        "requires-worker-protocol": "proto",
      },
      arguments=ctx.attr.worker_args + argfile_arguments,
  )

work = rule(
    implementation=_impl,
    attrs={
        "worker": attr.label(cfg="exec", mandatory=True, allow_files=True, executable=True),
        "worker_args": attr.string_list(),
        "args": attr.string_list(),
        "srcs": attr.label_list(allow_files=True),
        "multiflagfiles": attr.bool(default=False),
    },
    outputs = {"out": "%{name}.out"},
)
EOF
  cat >BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_import.bzl", "java_import")
load(":work.bzl", "work")

java_import(
  name = "worker_lib",
  jars = ["worker_lib.jar"],
)

java_binary(
  name = "worker",
  main_class = "com.google.devtools.build.lib.worker.ExampleWorkerMultiplexer",
  runtime_deps = [
    ":worker_lib",
  ],
  data = [
    ":worker_data.txt",
    ":worker_data_dir",
  ]
)
EOF
}

function test_example_worker_multiplexer() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  args = ["hello world"],
)

work(
  name = "hello_world_uppercase",
  worker = ":worker",
  args = ["--uppercase", "hello world"],
)
EOF

  bazel build  :hello_world &> $TEST_log \
    || fail "build failed"
  assert_equals "hello world" "$(cat $BINS/hello_world.out)"

  bazel build  :hello_world_uppercase &> $TEST_log \
    || fail "build failed"
  assert_equals "HELLO WORLD" "$(cat $BINS/hello_world_uppercase.out)"
}

function test_multiple_flagfiles() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "multi_hello_world",
  worker = ":worker",
  args = ["hello", "world", "nice", "to", "meet", "you"],
  multiflagfiles = True,
)
EOF

  bazel build  :multi_hello_world &> $TEST_log \
    || fail "build failed"
  assert_equals "hello world nice to meet you" "$(cat $BINS/multi_hello_world.out)"
}

function test_workers_quit_after_build() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> $TEST_log \
    || fail "build failed"
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  bazel build --worker_quit_after_build :hello_world_2 &> $TEST_log \
    || fail "build failed"
  work_count=$(cat $BINS/hello_world_2.out | grep COUNTER | cut -d' ' -f2)
  # If the worker hadn't quit as we told it, it would have been reused, causing this to be a "2".
  assert_equals "1" $work_count
}

# Flaky
function DISABLED_test_build_fails_when_worker_exits() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--exit_after=1"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> $TEST_log \
    || fail "build failed"

  bazel build --worker_verbose :hello_world_2 >> $TEST_log 2>&1 \
    && fail "expected build to fail" || true

  error_msgs=$(egrep -o -- 'Worker process (did not return a|returned an unparseable) WorkResponse' "$TEST_log")

  [ -n "$error_msgs" ] \
    || fail "expected error message not found"
}

function test_worker_restarts_when_worker_binary_changes() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  echo "First run" >> $TEST_log
  bazel build :hello_world_1 &> $TEST_log \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  echo "Second run" >> $TEST_log
  bazel build :hello_world_2 &> $TEST_log \
    || fail "build failed"
  worker_uuid_2=$(cat $BINS/hello_world_2.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_2.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "2" $work_count

  # Check that the same worker was used twice.
  assert_equals "$worker_uuid_1" "$worker_uuid_2"

  # Modify the example worker jar to trigger a rebuild of the worker.
  tr -cd '[:alnum:]' < /dev/urandom | head -c32 > dummy_file || true
  zip worker_lib.jar dummy_file
  rm dummy_file

  bazel build :hello_world_3 &> $TEST_log \
    || fail "build failed"
  worker_uuid_3=$(cat $BINS/hello_world_3.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_3.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  expect_log "worker .* can no longer be used, because its files have changed on disk"
  expect_log "worker_lib.jar: .* -> .*"

  # Check that we used a new worker.
  assert_not_equals "$worker_uuid_2" "$worker_uuid_3"
}

function test_worker_restarts_when_worker_runfiles_change() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> $TEST_log \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  bazel build :hello_world_2 &> $TEST_log \
    || fail "build failed"
  worker_uuid_2=$(cat $BINS/hello_world_2.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_2.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "2" $work_count

  # Check that the same worker was used twice.
  assert_equals "$worker_uuid_1" "$worker_uuid_2"

  # "worker_data.txt" is included in the "data" attribute of the example worker.
  echo "changeddata" > worker_data.txt

  bazel build :hello_world_3 &> $TEST_log \
    || fail "build failed"
  worker_uuid_3=$(cat $BINS/hello_world_3.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_3.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  expect_log "worker .* can no longer be used, because its files have changed on disk"
  expect_log "worker_data.txt: .* -> .*"

  # Check that we used a new worker.
  assert_not_equals "$worker_uuid_2" "$worker_uuid_3"
}

# When a worker does not conform to the protocol and returns a response that is not a parseable
# protobuf, it must be killed and a helpful error message should be printed.
function test_build_fails_when_worker_returns_junk() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--poison_after=1"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> $TEST_log \
    || fail "build failed"

  # A failing worker should cause the build to fail.
  bazel build :hello_world_2 &> $TEST_log \
    && fail "expected build to fail" || true

  # Check that a helpful error message was printed.
  expect_log "Worker process returned an unparseable WorkResponse!"
  expect_log "Did you try to print something to stdout"
  expect_log "I'm a  poisone"  # Hexdump
}

function test_input_digests() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_uuid", "--print_inputs"],
  srcs = [":input.txt"],
) for idx in range(10)]
EOF

  echo "hello world" > input.txt
  bazel build :hello_world_1 &> $TEST_log \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  hash1=$(egrep "INPUT .*/input.txt " $BINS/hello_world_1.out | cut -d' ' -f3)

  bazel build :hello_world_2 >> $TEST_log 2>&1 \
    || fail "build failed"
  worker_uuid_2=$(cat $BINS/hello_world_2.out | grep UUID | cut -d' ' -f2)
  hash2=$(egrep "INPUT .*/input.txt " $BINS/hello_world_2.out | cut -d' ' -f3)

  assert_equals "$worker_uuid_1" "$worker_uuid_2"
  assert_equals "$hash1" "$hash2"

  echo "changeddata" > input.txt

  bazel build :hello_world_3 >> $TEST_log 2>&1 \
    || fail "build failed"
  worker_uuid_3=$(cat $BINS/hello_world_3.out | grep UUID | cut -d' ' -f2)
  hash3=$(egrep "INPUT .*/input.txt " $BINS/hello_world_3.out | cut -d' ' -f3)

  assert_equals "$worker_uuid_2" "$worker_uuid_3"
  assert_not_equals "$hash2" "$hash3"
}

function test_worker_verbose() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> $TEST_log \
    || fail "build failed"
  expect_log "Created new ${WORKER_TYPE_LOG_STRING} Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_log "Destroying Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_log "Build completed, shutting down worker pool..."
}

function test_logs_are_deleted_on_server_restart() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> $TEST_log \
    || fail "build failed"

  expect_log "Created new ${WORKER_TYPE_LOG_STRING} Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"

  worker_log=$(egrep -o -- 'logging to .*/b(azel|laze)-workers/multiplex-worker-[0-9]-Work.log' "$TEST_log" | sed 's/^logging to //')

  [ -e "$worker_log" ] \
    || fail "Worker log was not found"

  # Running a build after a server shutdown should trigger the removal of old worker log files.
  bazel shutdown &> $TEST_log
  bazel build &> $TEST_log

  [ ! -e "$worker_log" ] \
    || fail "Worker log was not deleted"
}

function test_missing_execution_requirements_fallback_to_standalone() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
work(
  name = "hello_world",
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
)
EOF

  # Delete entire execution_requirements block
  sed -i.bak '/execution_requirements/,+4d' work.bzl
  rm -f work.bzl.bak

  bazel build --worker_quit_after_build :hello_world &> $TEST_log \
    || fail "build failed"

  expect_not_log "Created new ${WORKER_TYPE_LOG_STRING} Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_not_log "Destroying Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"

  # WorkerSpawnStrategy falls back to standalone strategy, so we still expect the output to be generated.
  [ -e "$BINS/hello_world.out" ] \
    || fail "Worker did not produce output"
}

function test_environment_is_clean() {
  prepare_example_worker
  cat >>BUILD <<'EOF'
work(
  name = "hello_world",
  worker = ":worker",
  args = ["--print_env"],
)
EOF

  bazel shutdown &> $TEST_log \
    || fail "shutdown failed"
  CAKE=LIE bazel build --worker_quit_after_build :hello_world &> $TEST_log \
    || fail "build failed"

  fgrep CAKE=LIE $BINS/hello_world.out \
    && fail "environment variable leaked into worker env" || true
}

function test_workers_quit_on_clean() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_clean",
  worker = ":worker",
  args = ["hello clean"],
)
EOF

  bazel build :hello_clean &> $TEST_log \
    || fail "build failed"
  assert_equals "hello clean" "$(cat $BINS/hello_clean.out)"
  expect_log "Created new ${WORKER_TYPE_LOG_STRING} Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"

  bazel clean &> $TEST_log \
    || fail "clean failed"
  expect_log "Clean command is running, shutting down worker pool..."
  expect_log "Destroying Work multiplex-worker (id [0-9]\+, key hash -\?[0-9]\+)"
}

function test_crashed_worker_causes_log_dump() {
  prepare_example_worker
  # TODO(larsrc): Spread this pattern to other tests
  func_name=${FUNCNAME[0]##test*:}  # Test name without generated prefix
  cat | sed "s/##FUNCNAME##/$func_name/;" >>BUILD <<'EOF'
[work(
  name = "##FUNCNAME##_%s" % idx,
  worker = ":worker",
  worker_args = ["--poison_after=1", "--hard_poison"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :${func_name}_1 &> $TEST_log \
    || fail "build failed"

  bazel build :${func_name}_2 &> $TEST_log \
    && fail "expected build to fail" || true

  expect_log "Worker process returned an unparseable WorkResponse!"
  expect_log "^---8<---8<--- Start of log, file at /"
  expect_log "I'm a very poisoned worker and will just crash."
  expect_log "^---8<---8<--- End of log ---8<---8<---"
}

# Flaky
function DISABLED_test_multiple_target_without_delay() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world_1",
  worker = ":worker",
  args = ["hello world 1"],
)

work(
  name = "hello_world_2",
  worker = ":worker",
  args = ["hello world 2"],
)

work(
  name = "hello_world_3",
  worker = ":worker",
  args = ["hello world 3"],
)
EOF

  bazel build  :hello_world_1 :hello_world_2 :hello_world_3 &> $TEST_log \
    || fail "build failed"
  assert_equals "hello world 1" "$(cat $BINS/hello_world_1.out)"
  assert_equals "hello world 2" "$(cat $BINS/hello_world_2.out)"
  assert_equals "hello world 3" "$(cat $BINS/hello_world_3.out)"
}

# We just need to test the build completion, no assertion is needed.
# Flaky
function DISABLED_test_multiple_target_with_delay() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world_1",
  worker = ":worker",
  args = ["--delay", "hello world 1"],
)

work(
  name = "hello_world_2",
  worker = ":worker",
  args = ["--delay", "hello world 2"],
)

work(
  name = "hello_world_3",
  worker = ":worker",
  args = ["--delay", "hello world 3"],
)
EOF

  bazel build  :hello_world_1 :hello_world_2 :hello_world_3 &> $TEST_log \
    || fail "build failed"
}

# This is just to test that file handling in multiplex example worker works.
function test_multiplexer_files() {
  prepare_example_worker

  mkdir -p dir1/dir2
  echo "base file" > file.txt
  echo "subdir file" > dir1/dir2/file.txt

  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  srcs = [":file.txt", ":dir1/dir2/file.txt"],
  args = ["hello world", "FILE:${WORKSPACE_SUBDIR}/file.txt", "FILE:${WORKSPACE_SUBDIR}/dir1/dir2/file.txt"],
)
EOF

  bazel build  :hello_world &> $TEST_log \
    || (sleep 1 && fail "build failed")
  assert_equals "hello world base file subdir file" "$( echo $(cat $BINS/hello_world.out))"
}

function test_sandboxed_multiplexer_files() {
  prepare_example_worker
  echo "base file" > file.txt

  mkdir -p ${PWD}/dir1/dir2
  echo "base file" > file.txt
  echo "subdir file" > dir1/dir2/file.txt
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  srcs = [":file.txt", ":dir1/dir2/file.txt"],
  args = ["hello world", "FILE:${WORKSPACE_SUBDIR}/file.txt", "FILE:${WORKSPACE_SUBDIR}/dir1/dir2/file.txt"],
)

EOF

  bazel build :hello_world --experimental_worker_multiplex_sandboxing &> $TEST_log \
    || fail "build failed"
  assert_equals "hello world base file subdir file" "$( echo $(cat $BINS/hello_world.out))"
}

function test_sandboxed_multiplexer_files_fails_if_ignoring_sandbox() {
  prepare_example_worker
  echo "base file" > file.txt

  mkdir -p ${PWD}/dir1/dir2
  echo "base file" > file.txt
  echo "subdir file" > dir1/dir2/file.txt
  cat >>BUILD <<EOF
work(
  name = "hello_world_ignore_sandbox",
  worker = ":worker",
  srcs = [":file.txt", ":dir1/dir2/file.txt"],
  args = ["--ignore_sandbox", "hello world", "FILE:${WORKSPACE_SUBDIR}/file.txt", "FILE:${WORKSPACE_SUBDIR}/dir1/dir2/file.txt"],
)

EOF

  bazel build :hello_world_ignore_sandbox --experimental_worker_multiplex_sandboxing &> $TEST_log \
    && fail "expected build to fail"
  expect_log "java.nio.file.NoSuchFileException"
}

run_suite "Worker multiplexer integration tests"
