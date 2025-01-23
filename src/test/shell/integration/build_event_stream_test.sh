#!/bin/bash
#
# Copyright 2016 The Bazel Authors. All rights reserved.
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

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

add_to_bazelrc "build --experimental_build_event_upload_strategy=local"

#### SETUP #############################################################

set -e

function set_up() {
  setup_skylib_support

  mkdir -p pkg
  touch pkg/somesourcefile
  cat > pkg/true.sh <<'EOF'
#!/bin/sh
[ -n "${XML_OUTPUT_FILE}" ] && touch "${XML_OUTPUT_FILE}"
exit 0
EOF
  chmod 755 pkg/true.sh
  cat > pkg/false.sh <<'EOF'
#!/bin/sh
[ -n "${XML_OUTPUT_FILE}" ] && touch "${XML_OUTPUT_FILE}"
exit 1
EOF
  chmod 755 pkg/false.sh
  cat > pkg/slowtest.sh <<'EOF'
#!/bin/sh
sleep 1
exit 0
EOF
  chmod 755 pkg/slowtest.sh
  touch pkg/sourcefileA pkg/sourcefileB pkg/sourcefileC
  cat > pkg/BUILD <<'EOF'
exports_files(["somesourcefile"])
sh_test(
  name = "true-bin",
  srcs = ["true.sh"],
)
sh_test(
  name = "true",
  srcs = ["true.sh"],
  size = "small",
)
sh_test(
  name = "slow",
  srcs = ["slowtest.sh"],
)
test_suite(
  name = "suite",
  tests = ["true"],
)
sh_test(
  name = "flaky",
  srcs = ["false.sh"],
  flaky = True,
)
genrule(
  name = "fails_to_build",
  outs = ["fails_to_build.txt"],
  cmd = "echo This build will fail && false",
  executable = 1,
)
sh_test(
  name = "test_that_fails_to_build",
  srcs = [":fails_to_build"],
)
genrule(
  name = "output_files_and_tags",
  outs = ["out1.txt"],
  cmd = "echo foo > \"$@\"",
  tags = ["tag1", "tag2"]
)
action_listener(
    name = "listener",
    mnemonics = ["Genrule"],
    extra_actions = [":extra"],
    visibility = ["//visibility:public"],
)
extra_action(
   name = "extra",
   cmd = "echo Hello World",
)
filegroup(
  name = "innergroup",
  srcs = ["sourcefileA", "sourcefileB"],
)
filegroup(
  name = "outergroup",
  srcs = ["sourcefileC", ":innergroup"],
)
genrule(
  name = "not_a_test",
  outs = ["not_a_test.txt"],
  cmd = "touch $@",
)
EOF
cat > simpleaspect.bzl <<'EOF'
def _simple_aspect_impl(target, ctx):
    for orig_out in ctx.rule.attr.outs:
        aspect_out = ctx.actions.declare_file(orig_out.name + ".aspect")
        ctx.actions.write(
            output=aspect_out,
            content = "Hello from aspect")
    return struct(output_groups={
        "aspect-out" : depset([aspect_out]) })

simple_aspect = aspect(implementation=_simple_aspect_impl)
EOF
cat > failingaspect.bzl <<'EOF'
def _failing_aspect_impl(target, ctx):
    for orig_out in ctx.rule.attr.outs.to_list():
        aspect_out = ctx.actions.declare_file(orig_out.name + ".aspect")
        ctx.actions.run_shell(
            inputs = [],
            outputs = [aspect_out],
            command = "false",
        )
    return struct(output_groups={
        "aspect-out" : depset([aspect_out]) })

failing_aspect = aspect(implementation=_failing_aspect_impl)
EOF
cat > semifailingaspect.bzl <<'EOF'
def _semifailing_aspect_impl(target, ctx):
    if not ctx.rule.attr.outs:
        return struct(output_groups = {})
    bad_outputs = list()
    good_outputs = list()
    mixed_outputs = list()
    for out in ctx.rule.attr.outs:
        if out.name[0] == "f":
            aspect_out = ctx.actions.declare_file(out.name + ".aspect.bad")
            bad_outputs.append(aspect_out)
            cmd = "false"
        else:
            aspect_out = ctx.actions.declare_file(out.name + ".aspect.good")
            good_outputs.append(aspect_out)
            cmd = "echo %s > %s" % (out.name, aspect_out.path)
        ctx.actions.run_shell(
            inputs = [],
            outputs = [aspect_out],
            command = cmd,
        )
        mixed_out = ctx.actions.declare_file(out.name + ".aspect.mixed")
        if out.name[3] == "2":  # only matches "out2.txt"
            cmd = "false"
        else:
            cmd = "echo %s > %s" % (out.name, mixed_out.path)
        mixed_outputs.append(mixed_out)
        ctx.actions.run_shell(
            inputs = [],
            outputs = [mixed_out],
            command = cmd,
        )
    return [OutputGroupInfo(**{
        "bad-aspect-out": depset(bad_outputs),
        "good-aspect-out": depset(good_outputs),
        "mixed-aspect-out": depset(mixed_outputs),
    })]

semifailing_aspect = aspect(implementation = _semifailing_aspect_impl)
EOF
mkdir -p semifailingpkg/
cat > semifailingpkg/BUILD <<'EOF'
genrule(
  name = "semifail",
  outs = ["out1.txt", "out2.txt", "failingout1.txt"],
  cmd = "for f in $(OUTS); do echo foo > $(RULEDIR)/$$f; done"
)
EOF
touch BUILD
cat > sample_workspace_status <<'EOF'
#!/bin/sh
echo SAMPLE_WORKSPACE_STATUS workspace_status_value
EOF
chmod  755 sample_workspace_status
mkdir -p visibility/hidden
cat > visibility/hidden/BUILD <<'EOF'
genrule(
    name = "hello",
    outs = ["hello.txt"],
    cmd = "echo Hello World > $@",
)
EOF
cat > visibility/BUILD <<'EOF'
genrule(
    name = "cannotsee",
    outs = ["cannotsee.txt"],
    srcs = ["//visibility/hidden:hello"],
    cmd = "cp $< $@",
)

genrule(
    name = "indirect",
    outs = ["indirect.txt"],
    srcs = [":cannotsee"],
    cmd = "cp $< $@",
)
genrule(
    name = "cannotsee2",
    outs = ["cannotsee2.txt"],
    srcs = ["//visibility/hidden:hello"],
    cmd = "cp $< $@",
)
genrule(
    name = "indirect2",
    outs = ["indirect2.txt"],
    srcs = [":cannotsee2.txt"],
    cmd = "cp $< $@",
)
EOF
mkdir -p failingtool
cat > failingtool/BUILD <<'EOF'
genrule(
    name = "tool",
    outs = ["tool.sh"],
    cmd = "false",
)
genrule(
    name = "usestool",
    outs = ["out.txt"],
    tools = [":tool"],
    cmd = "$(location :tool) > $@",
)
EOF
mkdir -p alias/actual
cat > alias/actual/BUILD <<'EOF'
genrule(
  name = "it",
  outs = ["it.txt"],
  cmd = "touch $@",
  visibility = ["//:__subpackages__"],
)
EOF
cat > alias/BUILD <<'EOF'
alias(
  name = "it",
  actual = "//alias/actual:it",
)
EOF
mkdir -p chain
cat > chain/BUILD <<'EOF'
genrule(
  name = "entry0",
  outs = ["entry0.txt"],
  cmd = "echo Hello0; touch $@",
)
EOF
for i in `seq 1 10`
do
    cat >> chain/BUILD <<EOF
genrule(
  name = "entry$i",
  outs = ["entry$i.txt"],
  srcs = [ "entry$(( $i - 1)).txt" ],
  cmd = "echo Hello$i; cp \$< \$@",
)
EOF
done
mkdir -p outputgroups
cat > outputgroups/rules.bzl <<EOF
def _my_rule_impl(ctx):
    group_kwargs = {}
    for name, exit in (("foo", 0), ("bar", 0), ("baz", 1), ("skip", 0)):
        outfile = ctx.actions.declare_file(ctx.label.name + "-" + name + ".out")
        ctx.actions.run_shell(
            outputs = [outfile],
            command = "printf %s > %s && exit %d" % (name, outfile.path, exit),
        )
        group_kwargs[name + "_outputs"] = depset([outfile])
    for name, exit, suffix in (
      ("foo", 1, ".fail.out"), ("bar", 0, ".ok.out"), ("bar", 0, ".ok.out2"),
      ("bar", 0, ".ok.out3"), ("bar", 0, ".ok.out4"), ("bar", 0, ".ok.out5")):
        outfile = ctx.actions.declare_file(ctx.label.name + "-" + name + suffix)
        ctx.actions.run_shell(
            outputs = [outfile],
            command = "printf %s > %s && exit %d" % (name, outfile.path, exit),
        )
        group_kwargs[name + "_outputs"] = depset(
            [outfile], transitive=[group_kwargs[name + "_outputs"]])
    valid = ctx.actions.declare_file(ctx.label.name + "-valid")
    ctx.actions.run_shell(
        outputs = [valid],
        command = "printf valid > %s && exit %d" % (valid.path, 0),
    )
    group_kwargs["_validation"] = depset([valid])
    return [OutputGroupInfo(**group_kwargs)]

my_rule = rule(implementation = _my_rule_impl, attrs = {
    "outs": attr.output_list(),
})
EOF
cat > outputgroups/BUILD <<EOF
load("//outputgroups:rules.bzl", "my_rule")
my_rule(name = "my_lib", outs=[])
EOF
}

function tear_down() {
  bazel shutdown
  rm -f bep.txt
}

#### TESTS #############################################################

function test_basic() {
  # Basic properties of the event stream
  # - a completed target explicitly requested should be reported
  # - after success the stream should close naturally, without any
  #   reports about aborted events
  # - the command line is reported in structured and unstructured form
  # - the target_kind is reported
  # - for single-configuration builds, there is precisely one configuration
  #   event reported; also make variables are shown
  bazel test -k --build_event_text_file=$TEST_log --tool_tag=MyFancyTool pkg:true \
    || fail "bazel test failed"
  expect_log 'pkg:true'
  # Command line
  expect_log_once 'args: "test"'
  expect_log_once 'args: "--build_event_text_file='
  expect_log_once 'args: "-k"'
  expect_log_once 'args: "--tool_tag=MyFancyTool"'
  expect_log_once 'args: "pkg:true"'

  # Options parsed. Since cmd_line lines are a substring of the equivalent
  # explicit_cmd_line lines, we expect 2 instances for these.
  expect_log_n 'cmd_line: "--tool_tag=MyFancyTool"' 2
  expect_log_n 'cmd_line: "--keep_going"' 2
  expect_log_once 'explicit_cmd_line: "--keep_going"'
  expect_log_once 'explicit_cmd_line: "--tool_tag=MyFancyTool"'
  expect_log_once 'tool_tag: "MyFancyTool"'

  # Structured command line. Expect the explicit flags to appear twice,
  # in the canonical and original command lines. We did not pass a tool
  # command line, but still expect an empty report.
  expect_log 'command_line_label: "original"'
  expect_log 'command_line_label: "canonical"'
  expect_log 'command_line_label: "tool"'

  expect_log_n 'combined_form: "-k"' 2
  expect_log_n 'option_name: "keep_going"' 2
  expect_log 'option_value: "1"' # too vague to count.

  expect_log_n 'combined_form: "--tool_tag=MyFancyTool"' 2
  expect_log_n 'option_name: "tool_tag"' 2
  expect_log_n 'option_value: "MyFancyTool"' 2

  expect_log_n "combined_form: \"--build_event_text_file=${TEST_log}\"" 2
  expect_log_n 'option_name: "build_event_text_file"' 2
  expect_log_n "option_value: \"${TEST_log}\"" 2

  expect_log_n 'chunk: "test"' 2
  expect_log_n 'chunk: "pkg:true"' 2

  # Build Finished
  expect_log 'build_finished'
  expect_log 'SUCCESS'
  expect_log 'finish_time'
  expect_log_once 'last_message: true'
  expect_not_log 'aborted'
  expect_log_once '^build_tool_logs'

  # Target kind for the sh_test
  expect_log 'target_kind:.*sh'

  # Test size should be reported
  expect_log 'test_size: SMALL'

  # Configuration reported with make variables
  expect_log_once '^configuration '
  expect_log 'key: "TARGET_CPU"'
}

function test_target_information_early() {
  # Verify that certain information is present in the log as part of
  # the TargetConfigured event (verifying that it comes at least before
  # the first TargetCompleted event, which is fine, if we only ask for
  # a single target).
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log '^completed'
  ed $TEST_log <<'EOF'
1
/^completed/+1,$d
a
...[cut here]
.
w
q
EOF
  expect_log 'target_kind:.*sh'
  expect_log 'test_size: SMALL'

  bazel build --verbose_failures --build_event_text_file=$TEST_log \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_log '^completed'
  ed $TEST_log <<'EOF'
1
/^completed/+1,$d
a
...[cut here]
.
w
q
EOF
  expect_log 'tag1'
  expect_log 'tag2'
}

function test_workspace_status() {
  bazel test --build_event_text_file=$TEST_log \
     --workspace_status_command=sample_workspace_status pkg:true \
    || fail "bazel test failed"
  expect_log_once '^workspace_status'
  expect_log 'key.*SAMPLE_WORKSPACE_STATUS'
  expect_log 'value.*workspace_status_value'
}

function test_target_summary() {
  bazel test --experimental_bep_target_summary \
      --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log_once '^test_summary '
  expect_log_once '^target_summary '
  expect_log_once 'overall_test_status: PASSED'
}

function test_suite() {
  # ...same true when running a test suite containing that test
  bazel test --build_event_text_file=$TEST_log pkg:suite \
    || fail "bazel test failed"
  expect_log 'pkg:true'
  expect_not_log 'aborted'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
  expect_log_once 'test_suite_expansions'
  expect_log_once 'suite_label: "//pkg:suite"'
  expect_log_once 'test_labels: "//pkg:true"'
}

function test_test_summary() {
  # Requesting a test, we expect
  # - precisely one test summary (for the single test we run)
  # - that is properly chained (no additional progress events)
  # - the correct overall status being reported
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log_once '^test_summary '
  expect_not_log 'aborted'
  expect_log 'status.*PASSED'
  expect_not_log 'status.*FAILED'
  expect_not_log 'status.*FLAKY'
}

function test_target_summary_for_test() {
  bazel test --experimental_bep_target_summary \
      --build_event_text_file="$TEST_log" //pkg:true \
    || fail "bazel test failed"
  expect_log_once '^test_summary '
  expect_not_log 'aborted'
  expect_not_log 'status.*FAILED'
  expect_not_log 'status.*FLAKY'
  expect_log_once '^target_summary '
  expect_log_once 'overall_test_status: PASSED'
}

function test_test_individual_results() {
  # Requesting a test, we expect
  # - precisely one test summary (for the single test we run)
  # - that is properly chained (no additional progress events)
  bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 pkg:true \
    || fail "bazel test failed"
  expect_log '^test_result'
  expect_log 'run.*1'
  expect_log 'status.*PASSED'
  expect_log_once '^test_summary '
  expect_not_log 'aborted'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_test_attempts() {
  # Run a failing test declared as flaky.
  # We expect to see 3 attempts to happen, and also find the 3 xml files
  # mentioned in the stream.
  # Moreover, as the test consistently fails, we expect the overall status
  # to be reported as failure.
  (bazel test --experimental_bep_target_summary \
      --build_event_text_file=$TEST_log pkg:flaky \
    && fail "test failure expected" ) || true
  expect_log 'attempt.*1$'
  expect_log 'attempt.*2$'
  expect_log 'attempt.*3$'
  expect_log_once '^test_summary '
  expect_log 'status.*FAILED'
  expect_log_once '^target_summary '
  expect_log_once 'overall_test_status.*FAILED'
  expect_not_log 'status.*PASSED'
  expect_not_log 'status.*FLAKY'
  expect_not_log 'aborted'
  expect_log '^test_result'
  expect_log 'test_action_output'
  expect_log 'flaky/.*_1.xml'
  expect_log 'flaky/.*_2.xml'
  expect_log 'flaky/.*test.xml'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_log 'name:.*TESTS_FAILED'
  expect_not_log 'name:.*SUCCESS'
}

function test_undeclared_output_annotations() {
  mkdir -p undeclared_annotations || fail "mkdir undeclared_annotations failed"
  cat > undeclared_annotations/undeclared_annotations_test.sh <<'EOF'
#!/bin/sh

base=$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR
[ -d $base ] || exit 2
[ ! -e $base/1.txt ]] | exit 2

echo "some information" > $base/something.part
EOF
  chmod u+x undeclared_annotations/undeclared_annotations_test.sh
  echo "sh_test(name='bep_undeclared_test', srcs=['undeclared_annotations_test.sh'], tags=['local'])" \
    > undeclared_annotations/BUILD
  bazel test  --build_event_text_file="${TEST_log}" //undeclared_annotations:bep_undeclared_test || fail "Expected success"
  expect_log 'test_result'
  expect_log 'test.outputs_manifest__ANNOTATIONS'
  expect_not_log 'test.outputs_manifest__ANNOTATIONS.pb'
}

function test_undeclared_output_annotations_pb() {
  mkdir -p undeclared_annotations || fail "mkdir undeclared_annotations failed"
  cat > undeclared_annotations/undeclared_annotations_test.sh <<'EOF'
#!/bin/sh

base=$TEST_UNDECLARED_OUTPUTS_ANNOTATIONS_DIR
[ -d $base ] || exit 2
[ ! -e $base/1.txt ]] | exit 2

echo "some information" > $base/something.pb
EOF
  chmod u+x undeclared_annotations/undeclared_annotations_test.sh
  echo "sh_test(name='bep_undeclared_pb_test', srcs=['undeclared_annotations_test.sh'], tags=['local'])" \
    > undeclared_annotations/BUILD
  bazel test --build_event_text_file="${TEST_log}" //undeclared_annotations:bep_undeclared_pb_test || fail "Expected success"
  expect_log 'test_result'
  expect_log 'test.outputs_manifest__ANNOTATIONS.pb'
}

function test_test_runtime() {
  bazel test --build_event_text_file=$TEST_log pkg:slow \
    || fail "bazel test failed"
  expect_log 'pkg:slow'
  expect_log '^test_result'
  expect_log 'test_attempt_duration_millis.*[1-9]'
  expect_log 'build_finished'
  expect_log 'SUCCESS'
  expect_log 'finish_time'
  expect_not_log 'aborted'
}

function test_test_start_times() {
  # Verify that the start time of a test is reported, regardless whether
  # it was cached or not.
  bazel clean
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log 'test_attempt_start_millis_epoch.*[1-9]'
  expect_not_log 'cached_locally'
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  expect_log 'test_attempt_start_millis_epoch.*[1-9]'
  expect_log 'cached_locally.*true'
}

function test_test_attempts_multi_runs() {
  # Check individual test attempts. Even in more complicated
  # situations, with some test rerun and some not, all events are properly
  # announced by the test actions (and not chained into the progress events).
  ( bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 pkg:true pkg:flaky \
    && fail "test failure expected" ) || true
  expect_log 'run.*1'
  expect_log 'attempt.*2'
  expect_not_log 'aborted'
}

function test_test_attempts_multi_runs_flake_detection() {
  # Check individual test attempts. Even in more complicated
  # situations, with some test rerun and some not, all events are properly
  # announced by the test actions (and not chained into the progress events).
  ( bazel test --build_event_text_file=$TEST_log \
    --runs_per_test=2 --runs_per_test_detects_flakes pkg:true pkg:flaky \
    && fail "test failure expected" ) || true
  expect_log 'run.*1'
  expect_log 'attempt.*2'
  expect_not_log 'aborted'
}

function test_cached_test_results() {
  # Verify that both, clean and cached test results are reported correctly,
  # including the appropriate reference to log files.
  bazel clean
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "Clean testing pkg:true failed"
  expect_log '^test_result'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_not_log 'cached_locally'
  expect_not_log 'aborted'
  bazel test --build_event_text_file=$TEST_log pkg:true \
    || fail "Second testing of pkg:true failed"
  expect_log '^test_result'
  expect_log 'name:.*test.log'
  expect_log 'name:.*test.xml'
  expect_log 'cached_locally'
  expect_not_log 'aborted'
}

function test_target_complete() {
  bazel build --verbose_failures --build_event_text_file=$TEST_log \
  pkg:output_files_and_tags || fail "bazel build failed"
  expect_log 'output_group'
  expect_log 'out1.txt'
  expect_log 'tag1'
  expect_log 'tag2'
}

function test_target_summary_for_build() {
  bazel build --experimental_bep_target_summary --verbose_failures \
      --build_event_text_file=$TEST_log pkg:output_files_and_tags \
    || fail "bazel build failed"
  expect_log 'output_group'
  expect_log 'out1.txt'
  expect_log 'tag1'
  expect_log 'tag2'
  expect_log_once '^target_summary '
  expect_log_once 'overall_build_success.*true'
  expect_not_log 'overall_test_status'
}

function test_test_target_complete() {
    bazel test --build_event_text_file="${TEST_log}" pkg:true \
          || tail "expected success"
    expect_log_once '^completed'

    cp "${TEST_log}" complete_event
    ed complete_event <<'EOF'
1
/^complete
1,.-1d
/^}
+1,$d
w
q
EOF
    grep -q 'output_group' complete_event \
        || fail "expected reference to output in complete event"

    expect_log 'name: *"pkg/true.sh"'
}

function test_extra_action() {
  # verify that normal successful actions are not reported, but extra actions
  # are
  bazel build --build_event_text_file=$TEST_log \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_not_log '^action'
  bazel build --build_event_text_file=$TEST_log \
    --experimental_action_listener=pkg:listener \
    pkg:output_files_and_tags || fail "bazel build with listener failed"
  expect_log '^action'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_action_ids() {
  bazel build --build_event_text_file=$TEST_log \
    --experimental_action_listener=pkg:listener \
    pkg:output_files_and_tags || fail "bazel build with listener failed"
  expect_log '^action'

  # Action ids should contain label and configuration if those exist.
  # Assumes action_completed id is 6 lines long
  for id_line in $(grep -n 'action_completed {' $TEST_log | cut -f 1 -d :)
  do
    sed -n "$id_line,$((id_line+6))p" $TEST_log > "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*primary_output: .*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*label: .*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*configuration.*' "$TEST_TMPDIR/event_id.txt"
    assert_contains '.*id: .*' "$TEST_TMPDIR/event_id.txt"
  done
}

function test_bep_output_groups() {
  # In outputgroups/rules.bzl, the `my_rule` definition defines four output
  # groups with different (successful/failed) action counts:
  #    1. foo_outputs (1 successful/1 failed)
  #    2. bar_outputs (6/0)
  #    3. baz_outputs (0/1)
  #    4. skip_outputs (1/0)
  #    5. _validation implicit with --experimental_run_validations (1/0)
  #
  # We request the first three output groups and expect foo_outputs and
  # bar_outputs to appear in BEP, because both groups have at least one
  # successful action.
  bazel build //outputgroups:my_lib \
   --keep_going\
   --build_event_text_file=bep_output \
   --build_event_json_file="$TEST_log" \
   --build_event_max_named_set_of_file_entries=1 \
   --experimental_run_validations \
   --output_groups=foo_outputs,bar_outputs,baz_outputs \
    && fail "expected failure" || true

  for name in foo bar; do
    expect_log "\"name\":\"${name}_outputs\""
    expect_log "\"name\":\"outputgroups/my_lib-${name}.out\""
  done
  expect_log "\"name\":\"foo_outputs\".*\"incomplete\":true"
  # Verify that a URI is produced for foo's successful action's output but not
  # its failed action's output.
  expect_log "\"name\":\"outputgroups/my_lib-foo.out\",\"uri\":"
  expect_not_log "\"name\":\"outputgroups/my_lib-foo.fail.out\",\"uri\":"
  # Verify that a URI is produced for all of bar's successful actions' outputs.
  for suffix in out out2 out3 out4 out5; do
    expect_log "\"name\":\"outputgroups/my_lib-bar.ok.${suffix}\",\"uri\":"
  done
  # Verify that nested NamedSetOfFiles structure is preserved in BEP.
  expect_log "namedSet.*\"1\".*bar.ok.*fileSets.*\"2\""
  expect_log "namedSet.*\"2\".*bar.ok.*fileSets.*\"3\""
  expect_log "namedSet.*\"3\".*bar.ok.*fileSets.*\"4\""

  for name in baz skip; do
    expect_not_log "\"name\":\"${name}_outputs\""
    expect_not_log "\"name\":\"outputgroups/my_lib-${name}.out\""
  done
  expect_not_log "-valid\""  # validation outputs shouldn't appear in BEP
}

function test_aspect_artifacts() {
  bazel build --build_event_text_file=$TEST_log \
    --aspects=simpleaspect.bzl%simple_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_log 'aspect.*simple_aspect'
  expect_log 'name.*aspect-out'
  expect_log 'name.*out1.txt.aspect'
  expect_not_log 'aborted'
  expect_log_n '^configured' 2
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_aspect_target_summary() {
  bazel build --build_event_text_file=$TEST_log \
    --experimental_bep_target_summary \
    --aspects=simpleaspect.bzl%simple_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags || fail "bazel build failed"
  expect_not_log 'aborted'
  expect_log_n '^configured' 2
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
  expect_log_n '^completed' 2
  expect_log_once '^target_summary '
  expect_log_once 'overall_build_success.*true'
}

function test_failing_aspect() {
  bazel build --build_event_text_file=$TEST_log \
    --aspects=failingaspect.bzl%failing_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags && fail "expected failure" || true
  expect_log 'aspect.*failing_aspect'
  expect_log '^finished'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_aspect_analysis_failure_no_target_summary() {
  bazel build -k --build_event_text_file=$TEST_log \
    --experimental_bep_target_summary \
    --aspects=failingaspect.bzl%failing_aspect \
    --output_groups=aspect-out \
    pkg:output_files_and_tags && fail "expected failure" || true
  expect_log 'aspect.*failing_aspect'
  expect_log '^finished'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
  expect_log_once '^completed '  # target completes due to -k
  # One "aborted" for failed aspect analysis, another for target_summary_id
  # announced by "completed" event asserted above
  expect_log_n 'aborted' 2
  expect_not_log '^target_summary '  # no summary due to analysis failure
}

function test_failing_aspect_bep_output_groups() {
  # In outputgroups/rules.bzl, the `my_rule` definition defines four output
  # groups with different (successful/failed) action counts:
  #    1. foo_outputs (1 successful/1 failed)
  #    2. bar_outputs (6/0)
  #    3. baz_outputs (0/1)
  #    4. skip_outputs (1/0)
  #    5. _validation implicit with --experimental_run_validations (1/0)
  #
  # We request the first two output groups and expect only bar_outputs to
  # appear in BEP, because all actions contributing to bar_outputs succeeded.
  #
  # Similarly, in semifailingaspect.bzl, the `semifailing_aspect` definition
  # defines two output groups: good-aspect-out and bad-aspect-out. We only
  # expect to see good-aspect-out in BEP because its actions all succeeded.
  bazel build //semifailingpkg:semifail //outputgroups:my_lib \
   --keep_going \
   --build_event_text_file=bep_output \
   --build_event_json_file="$TEST_log" \
   --build_event_max_named_set_of_file_entries=1 \
   --experimental_run_validations \
   --experimental_use_validation_aspect \
   --aspects=semifailingaspect.bzl%semifailing_aspect \
   --output_groups=foo_outputs,bar_outputs,good-aspect-out,bad-aspect-out,mixed-aspect-out \
    && fail "expected failure" || true

  for name in foo bar; do
    expect_log "\"name\":\"${name}_outputs\""
    expect_log "\"name\":\"outputgroups/my_lib-${name}.out\""
  done
  # Verify that a URI is produced for foo's successful action's output but not
  # its failed action's output.
  expect_log "\"name\":\"outputgroups/my_lib-foo.out\",\"uri\":"
  expect_not_log "\"name\":\"outputgroups/my_lib-foo.fail.out\",\"uri\":"

  for name in baz skip; do
    expect_not_log "\"name\":\"${name}_outputs\""
    expect_not_log "\"name\":\"outputgroups/my_lib-${name}.out\""
  done

  expect_log "\"name\":\"good-aspect-out\""
  expect_log "\"name\":\"mixed-aspect-out\""
  expect_not_log "\"name\":\"bad-aspect-out\""
  expect_log "\"name\":\"mixed-aspect-out\".*\"incomplete\":true"
  expect_log "\"name\":\"semifailingpkg/out1.txt.aspect.good\",\"uri\":"
  expect_log "\"name\":\"semifailingpkg/out2.txt.aspect.good\",\"uri\":"
  expect_log "\"name\":\"semifailingpkg/out1.txt.aspect.mixed\",\"uri\":"
  expect_not_log "\"name\":\"semifailingpkg/out2.txt.aspect.mixed\",\"uri\":"

  # Validation outputs shouldn't appear in BEP (incl. no output group)
  expect_not_log "-valid\""
  expect_log_n '^{"id":{"targetCompleted":{.*"aspect":"ValidateTarget".*}},"completed":{"success":true}}$' 2
}

function test_build_only() {
  # When building but not testing a test, there won't be a test summary
  # (as nothing was tested), so it should not be announced.
  # Still, no event should only be chained in by progress events.
  bazel build --experimental_bep_target_summary \
      --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel build failed"
  expect_not_log 'aborted'
  expect_not_log 'test_summary '
  expect_log_once '^target_summary '
  # Build Finished
  expect_log 'build_finished'
  expect_log 'finish_time'
  expect_log 'SUCCESS'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_command_whitelisting() {
  # We expect the "help" command to not generate a build-event stream,
  # but the "build" command to do.
  bazel help --build_event_text_file=bep.txt || fail "bazel help failed"
  ( [ -f bep.txt ] && fail "bazel help generated a build-event file" ) || :
  bazel version --build_event_text_file=bep.txt || fail "bazel help failed"
  ( [ -f bep.txt ] && fail "bazel version generated a build-event file" ) || :
  bazel build --build_event_text_file=bep.txt //pkg:true \
      || fail "bazel build failed"
  [ -f bep.txt ] || fail "build did not generate requested build-event file"
}

function test_multiple_transports() {
  # Verifies usage of multiple build event transports at the same time
    outdir=$(mktemp -d ${TEST_TMPDIR}/bazel.XXXXXXXX)
    bazel test \
      --build_event_text_file=${outdir}/test_multiple_transports.txt \
      --build_event_binary_file=${outdir}/test_multiple_transports.bin \
      --build_event_json_file=${outdir}/test_multiple_transports.json \
      pkg:suite || fail "bazel test failed"
  [ -f ${outdir}/test_multiple_transports.txt ] || fail "Missing expected file test_multiple_transports.txt"
  [ -f ${outdir}/test_multiple_transports.bin ] || fail "Missing expected file test_multiple_transports.bin"
  [ -f ${outdir}/test_multiple_transports.json ] || fail "Missing expected file test_multiple_transports.bin"
}

function test_basic_json() {
  # Verify that the json transport writes json files
  bazel test --build_event_json_file=$TEST_log pkg:true \
    || fail "bazel test failed"
  # check for some typical fragments that would be encoded differently in the
  # proto text format.
  expect_log '"started"'
  expect_log '"id"'
  expect_log '"children" *: *\['
  expect_log '"overallSuccess": *true'
}

function test_root_cause_early() {
  (bazel build --build_event_text_file=$TEST_log \
         pkg:fails_to_build && fail "build failure expected") || true
  # We expect precisely one action being reported (the failed one) and
  # precisely on report on a completed target; moreover, the action has
  # to be reported first.
  expect_log_once '^action'
  expect_log 'type: "Genrule"'
  expect_log_once '^completed'
  expect_not_log 'success: true'
  local naction=`grep -n '^action' $TEST_log | cut -f 1 -d :`
  local ncomplete=`grep -n '^completed' $TEST_log | cut -f 1 -d :`
  [ $naction -lt $ncomplete ] \
      || fail "failed action not before completed target"
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_root_cause_before_target_summary() {
  (bazel build --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         pkg:fails_to_build && fail "build failure expected") || true
  # We expect precisely one action being reported (the failed one) and
  # precisely on report on a completed target; moreover, the action has
  # to be reported first.
  expect_log_once '^action'
  expect_log 'type: "Genrule"'
  expect_log_once '^completed'
  expect_log_once '^target_summary'
  expect_not_log 'success: true'
  local naction=`grep -n '^action' $TEST_log | cut -f 1 -d :`
  local ncomplete=`grep -n '^completed' $TEST_log | cut -f 1 -d :`
  local nsummary=`grep -n '^target_summary' $TEST_log | cut -f 1 -d :`
  [ $naction -lt $ncomplete ] \
      || fail "failed action not before completed target"
  [ $ncomplete -lt $nsummary ] \
      || fail "completed not before target_summary"
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_action_conf() {
  # Verify that the expected configurations for actions are reported.
  # Expect the following configurations:
  # 1. The top-level target configuration
  # 2. Host configuration (since example contains transition to host).
  # 3. Trimmed top-level target configuration (since non-test rule).
  # As the action fails, we expect the configuration of the action to be
  # reported as well.
  # TODO(blaze-configurability-team): remove explicit trim_test_configuration
  # once it is (very soon) default true.
  (bazel build --trim_test_configuration --build_event_text_file=$TEST_log \
         -k failingtool/... && fail "build failure expected") || true
  count=`grep '^configuration' "${TEST_log}" | wc -l`
  [ "${count}" -eq 3 ] || fail "Expected 3 configurations, found $count."
}

function test_loading_failure() {
  # Verify that if loading fails, this is properly reported as the
  # reason for the target expansion event not resulting in targets
  # being expanded.
  (bazel build --build_event_text_file=$TEST_log \
         //does/not/exist && fail "build failure expected") || true
  expect_log_once 'aborted'
  expect_log_once 'reason: LOADING_FAILURE'
  expect_log 'description.*BUILD file not found'
  expect_not_log 'expanded'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_visibility_failure() {
  (bazel build --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         //visibility:cannotsee && fail "build failure expected") || true
  expect_log 'reason: ANALYSIS_FAILURE'
  expect_log '^aborted'
  expect_not_log '^completed'
  expect_not_log '^target_summary'

  # The same should hold true, if the server has already analyzed the target
  (bazel build --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         //visibility:cannotsee && fail "build failure expected") || true
  expect_log 'reason: ANALYSIS_FAILURE'
  expect_log '^aborted'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
  expect_not_log '^completed'
  expect_not_log '^target_summary'
}

function test_visibility_indirect() {
  # verify that an indirect visibility error is reported, including the
  # target that violates visibility constraints.
  (bazel build --build_event_text_file=$TEST_log \
         //visibility:indirect && fail "build failure expected") || true
  expect_log 'reason: ANALYSIS_FAILURE'
  expect_log '^aborted'
  expect_log '//visibility:cannotsee'
  # There should be precisely one event with target_configured as event id type
  (echo 'g/^id/+1p'; echo 'q') | ed "${TEST_log}" 2>&1 | tail -n +2 > event_id_types
  [ `grep target_configured event_id_types | wc -l` -eq 1 ] \
      || fail "not precisely one target_completed event id"
}

function test_independent_visibility_failures() {
  (bazel build -k --build_event_text_file=$TEST_log \
         //visibility:indirect //visibility:indirect2 \
       && fail "build failure expected") || true
  (echo 'g/^aborted/.,+2p'; echo 'q') | ed "${TEST_log}" 2>&1 | tail -n +2 \
     > aborted_events
  [ `grep '^aborted' aborted_events | wc -l` \
        -eq `grep ANALYSIS_FAILURE aborted_events | wc -l` ] \
      || fail "events should only be aborted due to analysis failure"
}

function test_loading_failure_keep_going() {
  (bazel build --build_event_text_file=$TEST_log \
         -k //does/not/exist && fail "build failure expected") || true
  expect_log_once 'aborted'
  expect_log_once 'reason: LOADING_FAILURE'
  # We don't expect an expanded message in this case, since all patterns failed.
  expect_log 'description.*BUILD file not found'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_loading_failure_keep_going_two_targets() {
  (bazel build --build_event_text_file=$TEST_log \
         -k //does/not/exist //pkg:somesourcefile && fail "build failure expected") || true
  expect_log_once 'aborted'
  expect_log_once 'reason: LOADING_FAILURE'
  expect_log_once '^expanded'
  expect_log 'description.*BUILD file not found'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

# TODO(aehlig): readd, once we stop reporting the important artifacts
#               for every target completion
#
# function test_artifact_dedup() {
#   bazel build --build_event_text_file=$TEST_log \
#       pkg:innergroup pkg:outergroup \
#   || fail "bazel build failed"
#   expect_log_once "name.*sourcefileA"
#   expect_log_once "name.*sourcefileB"
#   expect_log_once "name.*sourcefileC"
#   expect_not_log 'aborted'
# }

function test_stdout_stderr_reported() {
  # Verify that bazel's stdout/stderr is included in the build event stream.

  # Make sure we generate enough output on stderr
  bazel clean
  bazel test --build_event_text_file=$TEST_log --curses=no \
        pkg:slow 2>stderr.log || fail "slowtest failed"
  # Take a line that is likely not the output of an action (possibly reported
  # independently in the stream) and still characteristic enough to not occur
  # in the stream by accident. Taking the first line mentioning the test name
  # is likely some form of progress report.
  sample_line=`cat stderr.log | grep 'slow' | head -n 1 | tr '[]\r' '....'`
  echo "Sample regexp of stderr: ${sample_line}"
  expect_log "stderr.*${sample_line}"
}

function test_unbuffered_stdout_stderr() {
   # Verify that the option --bes_outerr_buffer_size ensures that messages are
   # flushed out to the BEP immediately
  bazel clean
  bazel build --build_event_text_file="${TEST_log}" \
        --bes_outerr_buffer_size=1 chain:entry10
  progress_count=$(grep '^progress' "${TEST_log}" | wc -l )
  # As we requested no buffereing, each action output has to be reported
  # immediately, creating an individual progress event.
  [ "${progress_count}" -gt 10 ] || fail "expected at least 10 progress events"
}

function test_srcfiles() {
  # Even if the build target is a source file, the stream should be correctly
  # and bazel shouldn't crash.
    bazel build --build_event_text_file=$TEST_log \
          pkg:somesourcefile || fail "build failed"
  expect_log 'SUCCESS'
  expect_log_n '^configuration' 2
  expect_not_log 'aborted'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_test_fails_to_build() {
  (bazel test --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         pkg:test_that_fails_to_build && fail "test failure expected") || true
  expect_not_log 'test_summary'  # no test_summary events or references to them
  expect_log_once '^target_summary '
  expect_not_log 'overall_build_success'
  expect_log 'last_message: true'
  expect_log 'BUILD_FAILURE'
  expect_log 'last_message: true'
  expect_log 'command_line:.*This build will fail'
  expect_log_once '^build_tool_logs'
}

function test_test_fails_to_build_without_default_output_group() {
  (bazel test --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         --output_groups=extra \
         pkg:test_that_fails_to_build && fail "test failure expected") || true
  expect_not_log 'test_summary'  # no test_summary events or references to them
  expect_log_once '^target_summary '
  expect_not_log 'overall_build_success'
  expect_log 'last_message: true'
  expect_log 'BUILD_FAILURE'
  expect_log 'last_message: true'
  expect_log 'command_line:.*This build will fail'
  expect_log_once '^build_tool_logs'
}

function test_no_tests_found() {
  (bazel test --experimental_bep_target_summary \
         --build_event_text_file=$TEST_log \
         pkg:not_a_test && fail "failure expected") || true
  expect_not_log '^test_summary'
  expect_log_once '^target_summary '
  expect_log 'overall_build_success: true'
  expect_log 'last_message: true'
  expect_log 'NO_TESTS_FOUND'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_no_tests_found_build_failure() {
  (bazel test -k --build_event_text_file=$TEST_log \
         pkg:not_a_test pkg:fails_to_build && fail "failure expected") || true
  expect_not_log '^test_summary'
  expect_log 'last_message: true'
  expect_log 'yet testing was requested'
  expect_log 'BUILD_FAILURE'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
}

function test_alias() {
  bazel build --build_event_text_file=$TEST_log alias/... \
    || fail "build failed"
  # If alias:it would be reported as the underlying alias/actual:it, then
  # there would be no event for alias:it. So we can check the correct reporting
  # by checking for aborted events.
  expect_not_log 'aborted'

  (echo 'g/^completed/?label?p'; echo 'q') | ed "${TEST_log}" 2>&1 | tail -n +2 > completed_labels
  cat completed_labels
  grep -q '//alias:it' completed_labels || fail "//alias:it not completed"
  grep -q '//alias/actual:it' completed_labels \
      || fail "//alias/actual:it not completed"
  [ `cat completed_labels | wc -l` -eq 2 ] \
      || fail "more than two targets completed"
  rm -f completed_labels
  bazel build --build_event_text_file=$TEST_log alias:it \
    || fail "build failed"
  expect_log 'label: "//alias:it"'
  expect_not_log 'label: "//alias/actual'
}

function test_circular_dep() {
  touch test.sh
  chmod u+x test.sh
  cat > BUILD <<'EOF'
sh_test(
  name = "circular",
  srcs = ["test.sh"],
  deps = ["circular"],
)
EOF
  (bazel build --build_event_text_file="${TEST_log}" :circular \
      && fail "Expected failure") || :
  expect_log_once 'last_message: true'
  expect_log 'name: "PARSING_FAILURE"'

  (bazel test --build_event_text_file="${TEST_log}" :circular \
      && fail "Expected failure") || :
  expect_log_once 'last_message: true'
  expect_log 'name: "PARSING_FAILURE"'
}

function test_missing_file() {
  cat > BUILD <<'EOF'
filegroup(
  name = "badfilegroup",
  srcs = ["doesnotexist"],
)
EOF
  (bazel build --noenable_bzlmod --build_event_text_file="${TEST_log}" :badfilegroup \
    && fail "Expected failure") || :
  # There should be precisely one event with target_completed as event id type
  (echo 'g/^id/+1p'; echo 'q') | ed "${TEST_log}" 2>&1 | tail -n +2 > event_id_types
  [ `grep target_completed event_id_types | wc -l` -eq 1 ] \
      || fail "not precisely one target_completed event id"
  # Moreover, we expect precisely one event identified by an unconfigured label
  [ `grep unconfigured_label event_id_types | wc -l` -eq 1 ] \
      || fail "not precisely one unconfigured_label event id"

  (bazel build --noenable_bzlmod --build_event_text_file="${TEST_log}" :badfilegroup :doesnotexist \
    && fail "Expected failure") || :
  # There should be precisely two events with target_completed as event id type
  (echo 'g/^id/+1p'; echo 'q') | ed "${TEST_log}" 2>&1 | tail -n +2 > event_id_types
  [ `grep target_completed event_id_types | wc -l` -eq 2 ] \
      || fail "not precisely one target_completed event id"
  # Moreover, we expect precisely one event identified by an unconfigured label
  [ `grep unconfigured_label event_id_types | wc -l` -eq 1 ] \
      || fail "not precisely one unconfigured_label event id"
}

function test_tool_command_line() {
  bazel build --experimental_tool_command_line="foo bar" --build_event_text_file=$TEST_log \
    || fail "build failed"

  # Check the arglist
  expect_log_once 'args: "build"'
  expect_log_once 'args: "--experimental_tool_command_line='

  # Structured command line. Expect the explicit flags to appear twice,
  # in the canonical and original command lines
  expect_log 'command_line_label: "original"'
  expect_log 'command_line_label: "canonical"'
  expect_log 'command_line_label: "tool"'

  # Expect the actual tool command line flag to appear twice, because of the two
  # bazel command lines that are reported
  expect_log_n 'combined_form: "--experimental_tool_command_line=' 2
  expect_log_n 'option_name: "experimental_tool_command_line"' 2
  expect_log_n 'option_value: "foo bar"' 2

  # Check the contents of the tool command line
  expect_log_once 'chunk: "foo bar"'
}

function test_noanalyze() {
  bazel build --noanalyze  --experimental_bep_target_summary \
      --build_event_text_file="${TEST_log}" pkg:true \
    || fail "build failed"
  expect_log_once '^aborted'
  expect_log 'reason: NO_ANALYZE'
  expect_log 'last_message: true'
  expect_log_once '^build_tool_logs'
  expect_not_log '^target_summary'
}

function test_nobuild() {
  bazel build --nobuild  --experimental_bep_target_summary \
      --build_event_text_file="${TEST_log}" pkg:true \
    || fail "build failed"
  expect_log_once '^aborted'
  expect_log 'reason: NO_BUILD'
  expect_not_log '^target_summary'
}

function test_server_pid() {
  bazel build --test_output=all --build_event_text_file=bep.txt \
    || fail "Build failed but should have succeeded"
  cat bep.txt | grep server_pid >> "$TEST_log"
  expect_log_once "server_pid:.*$(bazel info server_pid)$"
}

function test_bep_report_only_important_artifacts() {
  bazel build --test_output=all --build_event_text_file=bep.txt \
    //pkg:true || fail "Build failed but should have succeeded"
  cat bep.txt >> "$TEST_log"
  expect_not_log "_hidden_top_level_INTERNAL_"
}

function test_starlark_flags() {
  cat >> build_setting.bzl <<EOF
def _build_setting_impl(ctx):
  return []
int_setting = rule(
  implementation = _build_setting_impl,
  build_setting = config.int(flag=True)
)
EOF
  cat >> BUILD <<EOF
load('//:build_setting.bzl', 'int_setting')
int_setting(name = 'my_int_setting',
  build_setting_default = 42,
)
EOF

  bazel build --build_event_text_file=bep.txt \
    --//:my_int_setting=666 \
    //pkg:true || fail "Build failed but should have succeeded"
  cat bep.txt >> "$TEST_log"

  expect_log 'option_name: "//:my_int_setting"'
  expect_log 'option_value: "666"'
}

function test_empty_tree_in_named_files() {
  mkdir -p foo
  cat > foo/rule.bzl <<'EOF'
def _leaf_impl(ctx):
  ctx.actions.write(output = ctx.outputs.out2, content = 'hello\n')
  ctx.actions.write(output = ctx.outputs.out1, content = 'hello\n')
  return [DefaultInfo(files = depset([ctx.outputs.out1, ctx.outputs.out2]))]

def _top_impl(ctx):
  dir = ctx.actions.declare_directory('dir')
  ctx.actions.run_shell(outputs = [dir], command = 'true')
  return [DefaultInfo(files = depset([dir],
                      transitive = [dep[DefaultInfo].files
                                        for dep in ctx.attr.deps]))]

leaf = rule(
    implementation = _leaf_impl,
    attrs = {"out1": attr.output(), "out2": attr.output()},
)

top = rule(
    implementation = _top_impl,
    attrs = { "deps": attr.label_list()}
)
EOF
  cat > foo/BUILD <<'EOF'
load('//foo:rule.bzl', 'leaf', 'top')

leaf(name = 'leaf', out1 = '1.out', out2 = '2.out')
top(name = 'top', deps = [':leaf'])
EOF

  bazel build --build_event_text_file=bep.txt //foo:top >& "$TEST_log" \
      || fail "Expected success"
  expect_not_log ClassCastException
  cat bep.txt > "$TEST_log"
  expect_log "1.out"
  expect_log "2.out"
}

function test_tree_to_split_in_named_files() {
  mkdir -p foo
  cat > foo/rule.bzl <<'EOF'
def _leaf_impl(ctx):
  ctx.actions.write(output = ctx.outputs.out2, content = 'hello\n')
  ctx.actions.write(output = ctx.outputs.out1, content = 'hello\n')
  return [DefaultInfo(files = depset([ctx.outputs.out1, ctx.outputs.out2]))]

def _top_impl(ctx):
  dir = ctx.actions.declare_directory('dir')
  ctx.actions.run_shell(outputs = [dir],
                        command = 'touch %s/{out1,out2,out3}' % dir.path)
  return [DefaultInfo(files = depset([dir],
                      transitive = [dep[DefaultInfo].files
                                        for dep in ctx.attr.deps]))]

leaf = rule(
    implementation = _leaf_impl,
    attrs = {"out1": attr.output(), "out2": attr.output()},
)

top = rule(
    implementation = _top_impl,
    attrs = { "deps": attr.label_list()}
)
EOF
  cat > foo/BUILD <<'EOF'
load('//foo:rule.bzl', 'leaf', 'top')

leaf(name = 'leaf', out1 = '1.out', out2 = '2.out')
top(name = 'top', deps = [':leaf'])
EOF

  bazel build --build_event_text_file=bep.txt \
      --build_event_max_named_set_of_file_entries=2 //foo:top >& "$TEST_log" \
      || fail "Expected success"
  mv bep.txt "$TEST_log"
  # Depending on a hard-coded index is a bit brittle, but 0 should be the index
  # of the top-level nested set at least.
  # Strip newlines. BSD grep doesn't support -z, so use tr first.
  tr -s <"$TEST_log" '\n' ' ' |
      grep -q \
          'event { id { named_set { id: "0" } } named_set_of_files { file_sets { id: "[0-9]" } file_sets { id: "[0-9]" } } }' \
      || fail "Couldn't find top-level named set"
}

function test_memory_profile() {
  bazel build --build_event_text_file=bep.txt --memory_profile=/dev/null \
      >& "$TEST_log" || fail "Expected success"
  cp bep.txt "$TEST_log" || fail "cp failed"
  # Non-zero used heap size.
  expect_log 'used_heap_size_post_build: [1-9]'
}

function test_packages_loaded_contains_only_successfully_loaded_packages() {
  mkdir just-to-get-packages-needed-for-toolchain-resolution
  cat > just-to-get-packages-needed-for-toolchain-resolution/BUILD <<'EOF'
sh_library(name = 'whatever')
EOF
  # Do an initial invocation to get Bazel to load packages necessary for
  # toolchain resolution and also the //external package. This way we don't need
  # to bother making careful assertions about these packages in our actual test
  # logic below.
  bazel build --noenable_bzlmod --nobuild \
    //just-to-get-packages-needed-for-toolchain-resolution:whatever \
    >& "$TEST_log" || fail "Expected success"

  mkdir successful \
    dep-of-successful \
    unsuccessful-because-of-illegal-load \
    unsuccessful-because-of-BUILD-file-syntax-error \
    unsuccessful-because-of-BUILD-file-evaluation-error
  cat > successful/BUILD <<'EOF'
sh_library(
  name = 'successful',
  deps = ['//dep-of-successful:dep'],
)
EOF
  cat > dep-of-successful/BUILD <<'EOF'
sh_library(name = 'dep', visibility = ['//visibility:public'])
EOF
  # We use 3 different sorts of package loading errors to exercise different
  # parts of the code...
  #
  # ... for this sort of BUILD file, PackageFunction notices the illegal load
  # statement after parsing the file and doesn't proceed to BUILD file
  # evaluation. It then throws a Skyframe error.
  cat > unsuccessful-because-of-illegal-load/BUILD <<'EOF'
load('//no/such/package:f.bzl', 'doesntmatter')
EOF
  # ... for this sort of BUILD file, PackageFunction notices the illegal syntax
  # after parsing the BUILD file and doesn't proceed to BUILD file evaluation.
  # But it doesn't throw a Skyframe error (instead, it returns a PackageValue
  # that has an errorful Package).
  cat > unsuccessful-because-of-BUILD-file-syntax-error/BUILD <<'EOF'
@
EOF
  # ... for this sort of BUILD file, PackageFunction parses it successfully and
  # then evaluates it. PackageFunction then encounters the evaluation error and
  # returns a PackageValue that has an errorful Package.
  cat > unsuccessful-because-of-BUILD-file-evaluation-error/BUILD <<'EOF'
fail('bad')
EOF

  bazel build \
    --noenable_bzlmod \
    --nobuild \
    --keep_going \
    --build_event_text_file=bep.txt \
    --bes_upload_mode=wait_for_upload_complete \
    --experimental_ui_debug_all_events \
    --show_progress_rate_limit=-1 \
    //successful:all \
    //unsuccessful-because-of-illegal-load:all \
    //unsuccessful-because-of-BUILD-file-syntax-error:all \
    //unsuccessful-because-of-BUILD-file-evaluation-error:all \
    >& "$TEST_log" && fail "Expected failure"
  expect_log "unsuccessful-because-of-illegal-load.*Label '//no/such/package:f.bzl' is invalid because 'no/such/package' is not a package"
  expect_log "unsuccessful-because-of-BUILD-file-syntax-error.*invalid character: '@'"
  expect_log "Error in fail: bad"

  # On this invocation, Bazel attempts to load exactly 5 packages.
  expect_log_n "PROGRESS.*Loading package" 5
  # This package is attempted to be loaded during the target parsing phase.
  expect_log "Loading package: successful"
  # This package is attempted to be loaded during the target parsing phase.
  expect_log "Loading package: unsuccessful-because-of-illegal-load"
  # This package is attempted to be loaded during the target parsing phase.
  expect_log "Loading package: unsuccessful-because-of-BUILD-file-syntax-error"
  # This package is attempted to be loaded during the target parsing phase.
  expect_log "Loading package: unsuccessful-because-of-BUILD-file-evaluation-error"
  # This package is attempted to be loaded while traversing the dep edge
  # //successful:successful -> //dep-of-successful:dep during the analysis
  # phase.
  expect_log "Loading package: dep-of-successful"

  cp bep.txt "$TEST_log" || fail "cp failed"
  # In contrast, the metric in the BEP counts only successfully loaded packages
  # Of the 5 packages that are attempted to be loaded, 2 were successful:
  #   * //successful
  #   * //dep-of-successful
  expect_log 'packages_loaded: 2'
}

function test_secrets_in_env() {
  THE_SECRET=secret1234 bazel test -k --build_event_text_file=$TEST_log pkg:true \
    || fail "bazel test failed"

  # The obvious: make sure the secrets don't leak in any way and check that we did
  # not hit any unexpected corner cases. All other checks below are just "nice-to-have"s.
  expect_not_log 'secret1234'
  expect_not_log 'INVALID-OPTION-VALUE'

  # Check that some allowed environment variables are passed through.
  expect_log_n "--client_env=PATH=${PATH}" 3
  expect_log_once "args.*--client_env=PATH=${PATH}"
  expect_log_n "--client_env=USER=${USER}" 3
  expect_log_once "args.*--client_env=USER=${USER}"

  # Check argument rewrites in the unstructured and structured command lines.
  expect_log_n '--client_env=THE_SECRET= ' 3

  # Check that we also see the flag's value on its own.
  expect_log_n 'THE_SECRET= ' 5
}

function test_secrets_in_options() {
  bazel test -k --build_event_text_file=$TEST_log \
    --bes_header=NAME=the-secret-xyz \
    --test_env=HOME=/foo \
    --test_arg flat \
    --test_arg=PATH=/bar \
    --test_env=A_SECRET=secret1234 \
    --test_arg ANOTHER_SECRET=secret5678 \
    pkg:true \
    || fail "bazel test failed"

  # The obvious: make sure the secrets don't leak in any way and check that we did
  # not hit any unexpected corner cases. All other checks below are just "nice-to-have"s.
  expect_not_log 'secret1234'
  expect_not_log 'secret5678'
  expect_not_log 'the-secret-xyz'
  expect_not_log 'INVALID-OPTION-VALUE'

  # Check argument rewrites throughout the log.
  expect_log_n '--bes_header=NAME= ' 5
  expect_log_n '--test_env=HOME=/foo' 5
  expect_log_n '--test_arg REDACTED' 4
  expect_log_n '--test_env=A_SECRET= ' 5
  expect_log_n '--test_arg=REDACTED' 10

  # Check argument rewrites in the unstructured and structured command lines.
  expect_log_once "args.*--bes_header=NAME= "
  expect_log_once "args.*--test_env=HOME=/foo"
  expect_log "args.*\"REDACTED\""
  expect_not_log "args.*\"flat\""
  expect_log_once "args.*--test_arg=REDACTED"

  # Check the shell representation of the command line.
  expect_log_once "options_description.*--bes_header=\\\'NAME= \\\'"
  expect_log_once 'options_description.*--test_arg=REDACTED'
  expect_log_once "options_description.*--test_env=\\\'A_SECRET= \\\'"

  # Check that we also see the flags' values on their own.
  expect_log_n 'A_SECRET= ' 8
  expect_not_log 'ANOTHER_SECRET= '
}

run_suite "Integration tests for the build event stream"
