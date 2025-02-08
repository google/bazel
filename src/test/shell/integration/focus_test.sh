#!/bin/bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
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
# An end-to-end test for Skyfocus & working sets.

# --- begin runfiles.bash initialization ---
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

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  declare -r is_windows=true
  ;;
*)
  declare -r is_windows=false
  ;;
esac

add_to_bazelrc "build --experimental_enable_skyfocus"
add_to_bazelrc "build --genrule_strategy=local"
add_to_bazelrc "test --test_strategy=standalone"
add_to_bazelrc "test --strategy=TestRunner=local"

function set_up() {
  # Ensure we always start with a fresh server so that the following
  # env vars are picked up on startup. This could also be `bazel shutdown`,
  # but clean is useful for stateless tests.
  bazel clean --expunge

  # The focus command is currently implemented for InMemoryGraphImpl,
  # not SerializationCheckingGraph. This env var disables
  # SerializationCheckingGraph from being used as the evaluator.
  export DONT_SANITY_CHECK_SERIALIZATION=1
}

function test_print_info_about_graph() {
  local -r pkg=${FUNCNAME[0]}
  mkdir ${pkg}|| fail "cannot mkdir ${pkg}"
  mkdir -p ${pkg}
  echo "input" > ${pkg}/in.txt
  cat > ${pkg}/BUILD <<EOF
genrule(
  name = "g",
  srcs = ["in.txt"],
  outs = ["out.txt"],
  cmd = "cp \$< \$@",
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt
  bazel build //${pkg}:g\
    --experimental_skyfocus_dump_keys=count \
    --experimental_skyfocus_dump_post_gc_stats \
    --experimental_working_set=${pkg}/in.txt >$TEST_log 2>&1

  expect_log "Focusing on .\+ roots, .\+ leafs"
  expect_log "Rdep edges: .\+ -> .\+"
  expect_log "Heap: .\+MB -> .\+MB"
  expect_log "Node count: .\+ -> .\+"
}

function test_dump_keys_verbose() {
  local -r pkg=${FUNCNAME[0]}
  mkdir ${pkg}|| fail "cannot mkdir ${pkg}"
  mkdir -p ${pkg}
  echo "input" > ${pkg}/in.txt
  cat > ${pkg}/BUILD <<'EOF'
genrule(
  name = "g",
  srcs = ["in.txt"],
  outs = ["out.txt"],
  cmd = "cp $< $@",
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt
  bazel build //${pkg}:g \
    --experimental_skyfocus_dump_keys=verbose \
    --experimental_working_set=${pkg}/in.txt >$TEST_log 2>&1

  expect_log "Focusing on .\+ roots, .\+ leafs"

  # Dumps headers
  expect_log "Rdeps kept:"
  expect_log "Deps kept:"
  expect_log "Verification set:"

  # Dumps SkyKey strings
  expect_log "BUILD_DRIVER:BuildDriverKey"
  expect_log "BUILD_CONFIGURATION:BuildConfigurationKey"
  expect_log "FILE_STATE:\[.\+\]"

  # Doesn't dump counts
  expect_not_log "FILE_STATE: .\+ -> .\+ (-.\+%)"
}

function test_dump_keys_count() {
  local -r pkg=${FUNCNAME[0]}
  mkdir ${pkg}|| fail "cannot mkdir ${pkg}"
  mkdir -p ${pkg}
  echo "input" > ${pkg}/in.txt
  cat > ${pkg}/BUILD <<'EOF'
genrule(
  name = "g",
  srcs = ["in.txt"],
  outs = ["out.txt"],
  cmd = "cp $< $@",
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt
  bazel build //${pkg}:g \
    --experimental_skyfocus_dump_keys=count \
    --experimental_working_set=${pkg}/in.txt >$TEST_log 2>&1

  # Dumps counts
  expect_log "Roots kept: .\+"
  expect_log "Leafs kept: .\+"
  expect_log "CONFIGURED_TARGET: .\+ -> .\+ (-.\+%)"
  expect_log "FILE_STATE: .\+ -> .\+ (-.\+%)"

  # Doesn't dump SkyKey strings
  expect_not_log "FILE_STATE:[.\+]"
}

function test_focus_emits_profile_data() {
  if "$is_windows"; then
    # TODO(b/332825970): fix this
    return
  fi

  local -r pkg=${FUNCNAME[0]}
  mkdir ${pkg}|| fail "cannot mkdir ${pkg}"
  mkdir -p ${pkg}
  echo "input" > ${pkg}/in.txt
  cat > ${pkg}/BUILD <<EOF
genrule(
  name = "g",
  srcs = ["in.txt"],
  outs = ["out.txt"],
  cmd = "cp \$< \$@",
)
EOF

  bazel build //${pkg}:g \
    --experimental_working_set=${pkg}/in.txt \
    --profile=/tmp/profile.log &> "$TEST_log" || fail "expected success"
  grep '"ph":"X"' /tmp/profile.log > "$TEST_log" \
    || fail "Missing profile file."

  expect_log '"SkyframeFocuser"'
  expect_log '"focus.mark"'
  expect_log '"focus.sweep"'
}

function test_info_supports_printing_working_set() {
  local -r pkg=${FUNCNAME[0]}
  mkdir ${pkg}|| fail "cannot mkdir ${pkg}"
  mkdir -p ${pkg}
  touch ${pkg}/in.txt
  touch ${pkg}/in2.txt
  touch ${pkg}/not.used
  cat > ${pkg}/BUILD <<EOF
genrule(
  name = "g",
  srcs = ["in.txt", "in2.txt"],
  outs = ["out.txt"],
  cmd = "cat \$(location in.txt) \$(location in2.txt) > \$@",
)
EOF

  # Initial build with working set.
  bazel build //${pkg}:g --experimental_working_set=${pkg}/in.txt
  bazel dump --skyframe=working_set &> "$TEST_log"
  expect_log "${pkg}/in.txt"

  # Working set is expanded.
  bazel build //${pkg}:g --experimental_working_set=${pkg}/in.txt,${pkg}/in2.txt
  bazel dump --skyframe=working_set &> "$TEST_log"
  expect_log "${pkg}/in.txt"
  expect_log "${pkg}/in2.txt"

  # Working set can be defined with files not in the downward transitive
  # closure but `dump --skyframe=working_set` will not report it.
  bazel build //${pkg}:g --experimental_working_set=${pkg}/in.txt,${pkg}/in2.txt,${pkg}/not.used
  bazel dump --skyframe=working_set &> "$TEST_log"
  expect_log "${pkg}/in.txt"
  expect_log "${pkg}/in2.txt"
  expect_not_log "${pkg}/not.used"

  # The active set is retained for subsequent builds that don't pass the flag.
  bazel build //${pkg}:g
  bazel dump --skyframe=working_set &> "$TEST_log"
  expect_log "${pkg}/in.txt"
  expect_log "${pkg}/in2.txt"
  expect_not_log "${pkg}/not.used"
}

function test_glob_inputs_change_with_dir_in_working_set() {
  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}
  touch ${pkg}/in.txt ${pkg}/in2.txt ${pkg}/in3.txt
  cat > ${pkg}/BUILD <<EOF
genrule(
  name = "g",
  outs = ["out.txt"],
  cmd = "echo %s > \$@" % glob(["*.txt"]),
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt

  # Define the working set as ${pkg}, which will exclude the
  # DIRECTORY_LISTING_STATE($pkg) in the verification set.
  bazel build //${pkg}:g --experimental_working_set=${pkg}
  assert_contains "in.txt" $out
  assert_contains "in2.txt" $out
  assert_contains "in3.txt" $out

  # Remove in3.txt from the glob, invalidating DIRECTORY_LISTING_STATE($pkg),
  # and build should work.
  rm ${pkg}/in3.txt
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "in.txt" $out
  assert_contains "in2.txt" $out
  assert_not_contains "in3.txt" $out
}

function test_errors_after_glob_inputs_change_without_dir_in_working_set() {
  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}
  touch ${pkg}/in.txt ${pkg}/in2.txt ${pkg}/in3.txt
  cat > ${pkg}/BUILD <<EOF
genrule(
  name = "g",
  outs = ["out.txt"],
  cmd = "echo %s > \$@" % glob(["*.txt"]),
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt

  # Define the working set as ${pkg}/BUILD only, which will cause
  # DIRECTORY_LISTING_STATE($pkg) to be in the verification set.
  bazel build //${pkg}:g --experimental_working_set=${pkg}/BUILD
  assert_contains "in.txt" $out
  assert_contains "in2.txt" $out
  assert_contains "in3.txt" $out

  # Remove in3.txt from the glob, and expect the build to fail because the
  # DIRECTORY_LISTING_STATE($pkg) in the verification set has changed.
  rm ${pkg}/in3.txt
  bazel build //${pkg}:g &>"$TEST_log" && fail "expected build to fail"
  expect_log "detected changes outside of the working set"
  expect_log "${pkg}"
}

function test_reanalysis_with_label_flag_change() {
  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}
  touch ${pkg}/in.txt

  cat > ${pkg}/BUILD <<EOF
load("//${pkg}:rules.bzl", "my_rule", "simple_rule")

my_rule(name = "my_rule", src = "in.txt")

simple_rule(name = "default", value = "default_val")

simple_rule(name = "command_line", value = "command_line_val")

label_flag(
    name = "my_label_build_setting",
    build_setting_default = ":default"
)
EOF

  cat > ${pkg}/rules.bzl <<EOF
def _impl(ctx):
    _setting = "value=" + ctx.attr._label_flag[SimpleRuleInfo].value

    out = ctx.actions.declare_file(ctx.attr.name + ".txt")
    ctx.actions.run_shell(
        inputs = [ctx.file.src],
        outputs = [out],
        command = " ".join(["cat", ctx.file.src.path, ">", out.path, "&&", "echo", _setting, ">>", out.path]),
        execution_requirements = {"no-remote": "true"},
    )

    return [DefaultInfo(files = depset([out]))]

my_rule = rule(
    implementation = _impl,
    attrs = {
        "src": attr.label(allow_single_file = True),
        "_label_flag": attr.label(default = Label("//${pkg}:my_label_build_setting")),
    },
)

SimpleRuleInfo = provider(fields = ['value'])

def _simple_rule_impl(ctx):
    return [SimpleRuleInfo(value = ctx.attr.value)]

simple_rule = rule(
    implementation = _simple_rule_impl,
    attrs = {
        "value": attr.string(),
    },
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-bin")/${pkg}/my_rule.txt
  bazel build //${pkg}:my_rule --experimental_working_set=${pkg}/in.txt \
    || fail "expected build to succeed"

  assert_contains "value=default_val" ${out}

  # Change the configuration dep, and rely on bazel's configuration invalidation
  # mechanism to rebuild the graph.
  bazel build //${pkg}:my_rule --//${pkg}:my_label_build_setting=//${pkg}:command_line \
    --experimental_frontier_violation_check=warn \
    &> "$TEST_log" || fail "expected build to succeed"

  # Analysis cache should be dropped due to the changed configuration.
  expect_log "WARNING: Build option --//${pkg}:my_label_build_setting has changed, discarding analysis cache"

  # Skyfocus should rerun due to the dropped analysis cache.
  expect_log "Focusing on .\+ roots, .\+ leafs"

  # New result.
  assert_contains "value=command_line_val" ${out}
}

function test_changes_with_symlinks_are_detected() {
  if "$is_windows"; then
    # TODO(b/332825970): fix this
    return
  fi

  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}/subdir

  echo "input" > ${pkg}/in.txt
  ln -s in.txt ${pkg}/single.symlink
  ln -s single.symlink ${pkg}/double.symlink

  echo "subdir_input" > ${pkg}/subdir/in.txt
  ln -s subdir ${pkg}/dir.symlink

  cat > ${pkg}/BUILD <<EOF
genrule(
    name = "g",
    srcs = [
        "single.symlink",
        "double.symlink",
        "dir.symlink/in.txt",
    ],
    outs = ["out.txt"],
    cmd = "cat \$(location single.symlink) \$(location double.symlink) \$(location dir.symlink/in.txt) > \$@",
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt
  # Verify that Skyfocus handles the symlinks/files edges correctly, and that
  # using the linked file in the working set should work, even though the
  # symlinks are used as the genrule inputs.
  bazel build //${pkg}:g --experimental_working_set=${pkg}/in.txt,${pkg}/subdir/in.txt \
    || fail "expected build to succeed"

  echo "a change" >> ${pkg}/in.txt
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "a change" ${out}

  echo "final change" >> ${pkg}/subdir/in.txt
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "final change" ${out}

  # Yes, this means that you symlinks that used to link to the working set
  # can be relinked to something else, and the build will still work.
  # Not a correctness issue.
  mkdir -p ${pkg}/new_subdir
  echo "new file" > ${pkg}/new_subdir/new_file
  ln -sf new_subdir/new_file ${pkg}/single.symlink
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "new file" ${out}

  echo "new file 2" > ${pkg}/new_subdir/new_file
  ln -sf new_subdir ${pkg}/dir.symlink
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "new file 2" ${out}
}

function test_symlinks_as_working_set() {
  if "$is_windows"; then
    # TODO(b/332825970): fix this
    return
  fi

  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}/subdir

  echo "input" > ${pkg}/in.txt
  ln -s in.txt ${pkg}/single.symlink

  echo "subdir_input" > ${pkg}/subdir/in.txt
  ln -s subdir ${pkg}/dir.symlink

  cat > ${pkg}/BUILD <<EOF
genrule(
    name = "g",
    srcs = [
        "single.symlink",
        "dir.symlink/in.txt",
    ],
    outs = ["out.txt"],
    cmd = "cat \$(location single.symlink) \$(location dir.symlink/in.txt) > \$@",
)
EOF

  out=$(bazel info "${PRODUCT_NAME}-genfiles")/${pkg}/out.txt
  bazel build //${pkg}:g --experimental_working_set=${pkg}/single.symlink,${pkg}/dir.symlink \
    || fail "expected build to succeed"

  echo "a change" >> ${pkg}/in.txt
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "a change" ${out}

  echo "another change" >> ${pkg}/subdir/in.txt
  bazel build //${pkg}:g || fail "expected build to succeed"
  assert_contains "another change" ${out}
}

function test_test_command_runs_skyfocus() {
  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}
  cat > ${pkg}/in.sh <<EOF
exit 0
EOF
  chmod +x ${pkg}/in.sh
  cat > ${pkg}/BUILD <<EOF
sh_test(
  name = "g",
  srcs = ["in.sh"],
)
EOF

  bazel test //${pkg}:g || fail "expected to succeed"
  bazel dump --skyframe=working_set &> "$TEST_log" || fail "expected to succeed"
  expect_log "${pkg}/in.sh"
  expect_log "${pkg}/BUILD"
}

function test_disallowed_commands_after_focus() {
  local -r pkg=${FUNCNAME[0]}
  mkdir -p ${pkg}
  cat > ${pkg}/in.sh <<EOF
exit 0
EOF
  chmod +x ${pkg}/in.sh
  cat > ${pkg}/BUILD <<EOF
sh_test(
  name = "g",
  srcs = ["in.sh"],
)
EOF

  bazel build //${pkg}:g || fail "expected to succeed"

  bazel query //${pkg}:g &> "$TEST_log" && fail "expected to fail"
  expect_log "query is not supported after using Skyfocus"

  bazel cquery //${pkg}:g &> "$TEST_log" && fail "expected to fail"
  expect_log "cquery is not supported after using Skyfocus"

  bazel aquery //${pkg}:g &> "$TEST_log" && fail "expected to fail"
  expect_log "aquery is not supported after using Skyfocus"

  bazel print_action //${pkg}:g &> "$TEST_log" && fail "expected to fail"
  expect_log "print_action is not supported after using Skyfocus"

  bazel info || fail "expected to succeed"
  bazel dump --skyframe=summary || fail "expected to succeed"

  bazel build //${pkg}:g || fail "expected to succeed"
}

run_suite "Tests for Skyfocus"
