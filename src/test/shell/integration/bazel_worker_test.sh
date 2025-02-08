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
WORKER_TYPE=$2
WORKER_PROTOCOL=$3
shift 3

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

# TODO(philwo): Change this so the path to the custom worker gets passed in as an argument to the
# test, once the bug that makes using the "args" attribute with sh_tests in Bazel impossible is
# fixed.
example_worker=$(find $BAZEL_RUNFILES -name ExampleWorker_deploy.jar)

add_to_bazelrc "build -s"
add_to_bazelrc "build --spawn_strategy=worker,standalone"
add_to_bazelrc "build --worker_verbose --worker_max_instances=1"
add_to_bazelrc "build --noworker_multiplex"
add_to_bazelrc "build ${ADDITIONAL_BUILD_FLAGS}"

function set_up() {
  # Run each test in a separate folder so that their output files don't get cached.
  WORKSPACE_SUBDIR=$(basename $(mktemp -d ${WORKSPACE_DIR}/testXXXXXX))
  cd ${WORKSPACE_SUBDIR}
  BINS=$(bazel info $PRODUCT_NAME-bin)/${WORKSPACE_SUBDIR}
  OUTPUT_BASE="$(bazel info output_base)"

  add_rules_java ${WORKSPACE_DIR}/MODULE.bazel

  # Tell Bazel to shut down all running workers. Faster than a full shutdown.
  bazel build --worker_quit_after_build &> $TEST_log \
    || fail "'bazel build --worker_quit_after_build' during test set_up failed"
}

function tear_down() {
  # This makes sure the blocker from test_missing_worker_directory is gone.
  rm -rf "${OUTPUT_BASE}/{blaze,bazel}-workers"
}

function write_hello_library_files() {
  mkdir -p java/main
  cat >java/main/BUILD <<EOF
load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_library.bzl", "java_library")
java_binary(name = 'main',
    deps = [':hello_library'],
    srcs = ['Main.java'],
    main_class = 'main.Main')

java_library(name = 'hello_library',
             srcs = ['HelloLibrary.java']);
EOF

  cat >java/main/Main.java <<EOF
package main;
import main.HelloLibrary;
public class Main {
  public static void main(String[] args) {
    HelloLibrary.funcHelloLibrary();
    System.out.println("Hello, World!");
  }
}
EOF

  cat >java/main/HelloLibrary.java <<EOF
package main;
public class HelloLibrary {
  public static void funcHelloLibrary() {
    System.out.print("Hello, Library!;");
  }
}
EOF
}

function test_compiles_hello_library_using_persistent_javac() {
  write_hello_library_files

  bazel build java/main:main &> "$TEST_log" \
    || fail "build failed"
  expect_log "Created new ${WORKER_TYPE} Javac worker (id [0-9]\+, key hash -\?[0-9]\+)"
  $BINS/java/main/main | grep -q "Hello, Library!;Hello, World!" \
    || fail "comparison failed"
}

function test_compiles_hello_library_using_persistent_javac_sibling_layout() {
  write_hello_library_files

  bazel build \
    --experimental_sibling_repository_layout java/main:main \
    --worker_max_instances=Javac=1 \
    &> "$TEST_log" || fail "build failed"
  expect_log "Created new ${WORKER_TYPE} Javac worker (id [0-9]\+, key hash -\?[0-9]\+)"
  $BINS/java/main/main | grep -q "Hello, Library!;Hello, World!" \
    || fail "comparison failed"
}

function prepare_example_worker() {
  cp ${example_worker} worker_lib.jar
  chmod +w worker_lib.jar
  echo "exampledata" > worker_data.txt

  mkdir worker_data_dir
  echo "veryexample" > worker_data_dir/more_data.txt

  cat >work.bzl <<EOF
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

  execution_requirements = {"supports-workers": "1", "requires-worker-protocol": "$WORKER_PROTOCOL"}
  if ctx.attr.worker_key_mnemonic:
    execution_requirements["worker-key-mnemonic"] = ctx.attr.worker_key_mnemonic

  ctx.actions.run(
      inputs=argfile_inputs + ctx.files.srcs,
      outputs=[output],
      executable=worker,
      progress_message="Working on %s" % ctx.label.name,
      mnemonic=ctx.attr.action_mnemonic,
      execution_requirements=execution_requirements,
      arguments=ctx.attr.worker_args + argfile_arguments,
  )

work = rule(
    implementation=_impl,
    attrs={
        "worker": attr.label(cfg="exec", mandatory=True, allow_files=True, executable=True),
        "worker_args": attr.string_list(),
        "worker_key_mnemonic": attr.string(),
        "action_mnemonic": attr.string(default = "Work"),
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
  main_class = "com.google.devtools.build.lib.worker.ExampleWorker",
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

function test_example_worker() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["hello world"],
)

work(
  name = "hello_world_uppercase",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--uppercase", "hello world"],
)
EOF

  bazel build  :hello_world &> "$TEST_log" \
    || fail "build failed"
  assert_equals "hello world" "$(cat $BINS/hello_world.out)"

  bazel build  :hello_world_uppercase &> "$TEST_log" \
    || fail "build failed"
  assert_equals "HELLO WORLD" "$(cat $BINS/hello_world_uppercase.out)"
}

function test_worker_requests() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["hello world", "--print_requests"],
)

work(
  name = "hello_world_uppercase",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--uppercase", "hello world", "--print_requests"],
)
EOF

  bazel build  :hello_world &> "$TEST_log" \
    || fail "build failed"
  assert_contains "hello world" "$BINS/hello_world.out"
  assert_contains "arguments: \"hello world\"" "$BINS/hello_world.out"
  assert_contains "path:.*hello_world_worker_input" "$BINS/hello_world.out"
  assert_not_contains "request_id" "$BINS/hello_world.out"

  bazel build  :hello_world_uppercase &> "$TEST_log" \
    || fail "build failed"
  assert_contains "HELLO WORLD" "$BINS/hello_world_uppercase.out"
  assert_contains "arguments: \"hello world\"" "$BINS/hello_world_uppercase.out"
  assert_contains "path:.*hello_world_uppercase_worker_input" "$BINS/hello_world_uppercase.out"
  assert_not_contains "request_id" "$BINS/hello_world_uppercase.out"
}

function test_missing_worker_directory() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
)
work(
  name = "hello_world2",
  worker = ":worker",
)
EOF

  if bazel info bazel-bin >/dev/null 2>&1; then
    WORKER_DIR="${OUTPUT_BASE}/bazel-workers"
  else
    WORKER_DIR="${OUTPUT_BASE}/blaze-workers"
  fi

  # Old worker dirs can survive a regular `bazel clean`.
  bazel clean --expunge || fail "initial clean failed"
  mkdir -p "${OUTPUT_BASE}" || fail "Can't create outputbase"
  echo "FOO" > "${WORKER_DIR}" || fail "Can't block worker dir"
  assert_contains "FOO" "${WORKER_DIR}"
  # It should be OK to run without workers even without a worker directory.
  bazel build :hello_world --spawn_strategy=standalone \
      --strategy=Javac=standalone &> "$TEST_log" \
    || fail "local build failed"

  assert_contains "FOO" "${WORKER_DIR}"

  # ...but once we try to create a worker, we should get an error.
  bazel build :hello_world2 --spawn_strategy=worker &> "$TEST_log" \
    && fail "expected worker build to fail" || true

  expect_log "IOException.*-workers"
  rm -rf "${WORKER_DIR}"
}

function test_shared_worker() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  action_mnemonic = "Hello",
  worker_key_mnemonic = "SharedWorker",
  args = ["--write_uuid"],
)

work(
  name = "goodbye_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  action_mnemonic = "Goodbye",
  worker_key_mnemonic = "SharedWorker",
  args = ["--write_uuid"],
)
EOF
  bazel build :hello_world :goodbye_world &> "$TEST_log" \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world.out | grep UUID | cut -d' ' -f2)
  worker_uuid_2=$(cat $BINS/goodbye_world.out | grep UUID | cut -d' ' -f2)
  assert_equals "$worker_uuid_1" "$worker_uuid_2"
}

function test_worker_extra_flag() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  action_mnemonic = "Hello",
  worker_key_mnemonic = "World",
)
EOF

  bazel build :hello_world --worker_extra_flag=World=--ignored_argument \
      &> "$TEST_log" || fail "build failed"

  local -r worker_log=$(egrep -o -- 'logging to .*/b(azel|laze)-workers/worker-[0-9]+-World.log' "$TEST_log" | sed 's/^logging to //')

  if ! [[ -e "$worker_log" ]]; then
    fail "Worker log was not found"
  fi

  assert_contains "Worker args: .* --ignored_argument" "$worker_log"
}

function test_multiple_flagfiles() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "multi_hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["hello", "world", "nice", "to", "meet", "you"],
  multiflagfiles = True,
)
EOF

  bazel build  :multi_hello_world &> "$TEST_log" \
    || fail "build failed"
  assert_equals "hello world nice to meet you" "$(cat $BINS/multi_hello_world.out)"
}

function test_workers_quit_after_build() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  bazel build --worker_quit_after_build :hello_world_2 &> "$TEST_log" \
    || fail "build failed"
  work_count=$(cat $BINS/hello_world_2.out | grep COUNTER | cut -d' ' -f2)
  # If the worker hadn't quit as we told it, it would have been reused, causing this to be a "2".
  assert_equals "1" $work_count
}

# Disabled for being flaky, see b/182373389
function DISABLED_test_build_succeeds_even_if_worker_exits() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--exit_after=1", "--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF
  # The worker dies after finishing the action, so the build succeeds.
  bazel build --worker_verbose :hello_world_1 &> "$TEST_log" \
    || fail "build failed"

  # This time, the worker is dead before the build starts, so a new one is made.
  bazel build --worker_verbose :hello_world_2 &> "$TEST_log" \
    || fail "build failed"

  expect_log "Work worker (id [0-9]\+, key hash -\?[0-9]\+) has unexpectedly died with exit code 0."
}

function test_build_fails_if_worker_dies_during_action() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}","--exit_during=1"],
  args = [
    "--write_uuid",
    "--write_counter",
  ],
) for idx in range(10)]
EOF

  bazel build --worker_verbose :hello_world_1 &> "$TEST_log" \
    && fail "expected build to fail" || true

  expect_log "Worker process did not return a WorkResponse:"
  # Worker log gets displayed on error, including verbosity messages.
  expect_log "VERBOSE: Pretending to do work."
}

function test_worker_restarts_when_worker_binary_changes() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  echo "First run" >> $TEST_log
  bazel build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  echo "Second run" >> $TEST_log
  bazel build :hello_world_2 &> "$TEST_log" \
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

  bazel build :hello_world_3 &> "$TEST_log" \
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
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_1.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "1" $work_count

  bazel build :hello_world_2 &> "$TEST_log" \
    || fail "build failed"
  worker_uuid_2=$(cat $BINS/hello_world_2.out | grep UUID | cut -d' ' -f2)
  work_count=$(cat $BINS/hello_world_2.out | grep COUNTER | cut -d' ' -f2)
  assert_equals "2" $work_count

  # Check that the same worker was used twice.
  assert_equals "$worker_uuid_1" "$worker_uuid_2"

  # "worker_data.txt" is included in the "data" attribute of the example worker.
  echo "changeddata" > worker_data.txt

  bazel build :hello_world_3 &> "$TEST_log" \
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
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--poison_after=1", "--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"

  # A failing worker should cause the build to fail.
  bazel build :hello_world_2 &> "$TEST_log" \
    && fail "expected build to fail" || true

  # Check that a helpful error message was printed.
  expect_log "Worker process returned an unparseable WorkResponse!"
  expect_log "Did you try to print something to stdout"
  expect_log "Not UTF-8, printing first 1024 bytes as hex"
  expect_log "49 27 6D 20 61 20 70 6F  69 73 6F 6E 65 64 20 77  |I'm a po isoned w|"
}

function test_input_digests() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--print_inputs"],
  srcs = [":input.txt"],
) for idx in range(10)]
EOF

  echo "hello world" > input.txt
  bazel build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  worker_uuid_1=$(cat $BINS/hello_world_1.out | grep UUID | cut -d' ' -f2)
  hash1=$(egrep "INPUT .*/input.txt " $BINS/hello_world_1.out | cut -d' ' -f3)

  bazel build :hello_world_2 >> "$TEST_log" 2>&1 \
    || fail "build failed"
  worker_uuid_2=$(cat $BINS/hello_world_2.out | grep UUID | cut -d' ' -f2)
  hash2=$(egrep "INPUT .*/input.txt " $BINS/hello_world_2.out | cut -d' ' -f3)

  assert_equals "$worker_uuid_1" "$worker_uuid_2"
  assert_equals "$hash1" "$hash2"

  echo "changeddata" > input.txt

  bazel build :hello_world_3 >> "$TEST_log" 2>&1 \
    || fail "build failed"
  worker_uuid_3=$(cat $BINS/hello_world_3.out | grep UUID | cut -d' ' -f2)
  hash3=$(egrep "INPUT .*/input.txt " $BINS/hello_world_3.out | cut -d' ' -f3)

  assert_equals "$worker_uuid_2" "$worker_uuid_3"
  assert_not_equals "$hash2" "$hash3"
}

function test_worker_verbose() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  expect_log "Created new ${WORKER_TYPE} Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_log "Destroying Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_log "Build completed, shutting down worker pool..."
}

function test_logs_are_deleted_on_server_restart() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build --worker_quit_after_build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"

  expect_log "Created new ${WORKER_TYPE} Work worker (id [0-9]\+, key hash -\?[0-9]\+)"

  local -r worker_log=$(egrep -o -- 'logging to .*/b(azel|laze)-workers/worker-[0-9]+-Work.log' "$TEST_log" | sed 's/^logging to //')

  if ! [[ -e "$worker_log" ]]; then
    fail "Worker log was not found"
  fi

  # Running a build after a server shutdown should trigger the removal of old worker log files.
  bazel shutdown &> $TEST_log
  bazel build &> $TEST_log

  [ ! -e "$worker_log" ] \
    || fail "Worker log was not deleted"
}

function test_requires_worker_protocol_missing_defaults_to_proto {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world_proto",
  worker = ":worker",
  worker_args = ["--worker_protocol=proto"],
  args = ["hello world"],
)
work(
  name = "hello_world_json",
  worker = ":worker",
  worker_args = ["--worker_protocol=json"],
)
EOF

  sed -i.bak 's/=execution_requirements/={"supports-workers": "1"}/g' work.bzl
  rm -f work.bzl.bak

  bazel build :hello_world_proto &> "$TEST_log" \
    || fail "build failed"
  assert_equals "hello world" "$(cat $BINS/hello_world_proto.out)"

  bazel build :hello_world_json &> "$TEST_log" \
    && fail "expected proto build with json worker to fail" || true
}

function test_missing_execution_requirements_fallback_to_standalone() {
  prepare_example_worker
  # This test ignores the WORKER_PROTOCOL test arg since it doesn't use the
  # persistent worker when execution falls back to standalone.
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  args = ["--write_uuid", "--write_counter"],
)
EOF

  sed -i.bak '/execution_requirements=execution_requirements/d' work.bzl
  rm -f work.bzl.bak

  bazel build --worker_quit_after_build :hello_world &> "$TEST_log" \
    || fail "build failed"

  expect_not_log "Created new ${WORKER_TYPE} Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_not_log "Destroying Work worker (id [0-9]\+, key hash -\?[0-9]\+)"

  # WorkerSpawnStrategy falls back to standalone strategy, so we still expect the output to be generated.
  [ -e "$BINS/hello_world.out" ] \
    || fail "Worker did not produce output"
}

function test_environment_is_clean() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--print_env"],
)
EOF

  bazel shutdown &> "$TEST_log" \
    || fail "shutdown failed"
  CAKE=LIE bazel build --worker_quit_after_build :hello_world &> "$TEST_log" \
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
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["hello clean"],
)
EOF

  bazel build :hello_clean &> "$TEST_log" \
    || fail "build failed"
  assert_equals "hello clean" "$(cat $BINS/hello_clean.out)"
  expect_log "Created new ${WORKER_TYPE} Work worker (id [0-9]\+, key hash -\?[0-9]\+)"

  bazel clean &> "$TEST_log" \
    || fail "clean failed"
  expect_log "Clean command is running, shutting down worker pool..."
  expect_log "Destroying Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
}

function test_crashed_worker_causes_log_dump() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = [
    "--poison_after=1",
    "--hard_poison",
    "--worker_protocol=${WORKER_PROTOCOL}"
  ],
  args = ["--write_uuid", "--write_counter"],
) for idx in range(10)]
EOF

  bazel build :hello_world_1 &> "$TEST_log" \
    || fail "build failed"

  bazel build :hello_world_2 &> "$TEST_log" \
    && fail "expected build to fail" || true

  expect_log "^---8<---8<--- Start of log, file at /"
  expect_log "Worker process did not return a WorkResponse:"
  expect_log "I'm a very poisoned worker and will just crash."
  expect_log "^---8<---8<--- End of log ---8<---8<---"
}

function test_worker_memory_limit() {
  prepare_example_worker
  cat >>BUILD <<EOF
work(
  name = "hello_world",
  worker = ":worker",
  worker_args = [
    "--worker_protocol=${WORKER_PROTOCOL}",
  ],
  args = [
    "--work_time=3s",
  ]
)
EOF

  bazel build --experimental_worker_memory_limit_mb=1000 \
    --experimental_worker_metrics_poll_interval=1s :hello_world &> "$TEST_log" \
    || fail "build failed"
  bazel clean
  bazel build --experimental_worker_memory_limit_mb=1 \
    --experimental_worker_metrics_poll_interval=1s :hello_world &> "$TEST_log" \
    && fail "expected build to fail" || true

  expect_log "^---8<---8<--- Start of log, file at /"
  expect_log "Worker process did not return a WorkResponse:"
  expect_log "Killing [a-zA-Z]\+ worker [0-9]\+ (pid [0-9]\+) because it is using more memory than the limit ([0-9]\+ KB > 1 MB)"
  expect_log "^---8<---8<--- End of log ---8<---8<---"
}

function test_total_worker_memory_limit_log_starting() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter", "--work_time=1s"],
) for idx in range(10)]
EOF

  bazel build --experimental_total_worker_memory_limit_mb=10000 \
  --experimental_worker_memory_limit_mb=5000 --noexperimental_shrink_worker_pool :hello_world_1 &> "$TEST_log" \
  || fail "build failed"


  expect_log "Worker Lifecycle Manager starts work with (total limit: 10000 MB, individual limit: 5000 MB, shrinking: disabled)"

  bazel build --experimental_total_worker_memory_limit_mb=15000 \
  --experimental_worker_memory_limit_mb=7000 --experimental_shrink_worker_pool :hello_world_2 &> "$TEST_log" \
  || fail "build failed"

  expect_not_log "Destroying Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
  expect_log "Worker Lifecycle Manager starts work with (total limit: 15000 MB, individual limit: 7000 MB, shrinking: enabled)"
}

function test_worker_metrics_collection() {
  prepare_example_worker
  cat >>BUILD <<EOF
[work(
  name = "hello_world_%s" % idx,
  worker = ":worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--write_uuid", "--write_counter", "--work_time=1s"],
) for idx in range(10)]
EOF

  bazel build \
      --build_event_text_file="${TEST_log}".build.json \
      --profile="${TEST_log}".profile \
      --experimental_worker_metrics_poll_interval=400ms \
      --experimental_collect_worker_data_in_profiler \
      :hello_world_1 &> "$TEST_log" \
    || fail "build failed"
  expect_log "Created new ${WORKER_TYPE} Work worker (id [0-9]\+, key hash -\?[0-9]\+)"
  # Now see that we have metrics in the build event log.
  mv "${TEST_log}".build.json "${TEST_log}"
  expect_log "mnemonic: \"Work\""
  expect_log "worker_memory_in_kb: [0-9][0-9]*"
  # And see that we collected metrics several times
  mv "${TEST_log}".profile "${TEST_log}"
  local metric_events=$(grep -sc -- "Workers memory usage" $TEST_log)
  (( metric_events >= 2 )) || fail "Expected at least 2 worker metric collections"
}

function do_test_sandbox_cleanup {
  # Skip if worker sandboxing isn't enabled.
  if [[ "${WORKER_TYPE}" != "sandboxed" ]]; then
    return 0
  fi

  prepare_example_worker

  mkdir -p a b c
  touch a/1.txt b/2.txt b/3.txt b/4.txt c/5.txt

  cat >>BUILD <<EOF
work(
  name = "first",
  worker = ":worker",
  worker_key_mnemonic = "Worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--print_dir_listing=${WORKSPACE_SUBDIR}"],
  srcs = ["a/1.txt", "b/2.txt", "b/3.txt"],
)
work(
  name = "second",
  worker = ":worker",
  worker_key_mnemonic = "Worker",
  worker_args = ["--worker_protocol=${WORKER_PROTOCOL}"],
  args = ["--print_dir_listing=${WORKSPACE_SUBDIR}"],
  srcs = ["b/3.txt", "b/4.txt", "c/5.txt", "first.out"],
)
EOF

  bazel build "$@" \
    :first :second &> "$TEST_log" || fail "Build failed"

  assert_contains "DIRENT a/1.txt" $BINS/first.out
  assert_contains "DIRENT b/2.txt" $BINS/first.out
  assert_contains "DIRENT b/3.txt" $BINS/first.out
  assert_not_contains "DIRENT b/4.txt" $BINS/first.out
  assert_not_contains "DIRENT c" $BINS/first.out

  assert_not_contains "DIRENT a" $BINS/second.out
  assert_not_contains "DIRENT b/2.txt" $BINS/second.out
  assert_contains "DIRENT b/3.txt" $BINS/second.out
  assert_contains "DIRENT b/4.txt" $BINS/second.out
  assert_contains "DIRENT c/5.txt" $BINS/second.out

  assert_equals \
    "$(grep "DIRENT b/3.txt" $BINS/first.out | cut -d' ' -f3)" \
    "$(grep "DIRENT b/3.txt" $BINS/second.out | cut -d' ' -f3)"
}

function test_sandbox_cleanup_without_tracking() {
  do_test_sandbox_cleanup
}

function test_sandbox_cleanup_with_tracking() {
  do_test_sandbox_cleanup --experimental_worker_sandbox_inmemory_tracking=Worker
}

run_suite "Worker integration tests"
