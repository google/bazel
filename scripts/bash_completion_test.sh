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
# bash_completion_test.sh: tests of bash command completion.

# --- begin runfiles.bash initialization v3 ---
# Copy-pasted from the Bazel Bash runfiles library v3.
set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
  source "$0.runfiles/$f" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
  { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
# --- end runfiles.bash initialization v3 ---

: ${DIR:=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}
source ${DIR}/testenv.sh || { echo "testenv.sh not found!" >&2; exit 1; }

# List of command to test completion for
: ${COMMAND_ALIASES:=bazel}

# Completion script
: ${COMPLETION:="$(rlocation io_bazel/scripts/bazel-complete.bash)"}

# Set this to test completion with package path (if enabled)
: ${PACKAGE_PATH_PREFIX:=}

#### UTILITIES #########################################################

# Usage: array_join join_on array
#
# Joins all arguments using the first argument as separator
function array_join {
  local joiner="$1"
  shift
  echo -n "$1"
  shift
  for i in "$@"; do
    echo -n "${joiner}${i}"
  done
}

# Usage: expand <terminal-input> <flags> <stderr-file>
#
# Prints the string resulting from command expansion after the
# specified terminal input is typed at the shell.  The argument
# is evaluated using 'echo -e', so \t can be used to invoke
# command expansion. STDERR output from the call to bash is
# sent to stderr-file so it can be inspected, if desired.
#
# This approach is rather gnarly, but guarantees good test fidelity,
# unlike "unit test" approaches based on invoking the completion function
# directly with COMP_WORDS etc defined.
expand() {
    local input="$1" flags="$2" stderr_file="$3"
    {
        # The flags for blaze autocomplete script.
        echo "$flags"
        # This script is already sourced in a normal bash shell, but we need it
        # for the tests, too.
        echo "source $COMPLETION"
        # Tricky!  We turn "bazel" into a self-quoting command
        # that echoes its argument string exactly, spaces and all.
        # We assume no single-quotes in the input, though.
        #
        # Alias expansion still inserts an extra space after 'blaze',
        # though, hence the following sed.  Not sure why.
        for i in "${COMMAND_ALIASES[@]}"; do
          echo "alias $i=\"echo $i'\""
        done
        echo -en "$input'"
    } | bash --norc -i 2>"$stderr_file" |
      sed -e 's/^\('"$(array_join "\|" ${COMMAND_ALIASES[@]})"'\)  /\1 /'
}

# Usage: assert_expansion <prefix> <expected-expansion> <optional-flags>
#
# For multiple flags separate with semicolon.
# e.g. assert_expansion 'foo' 'foo_expand' 'flag1=bar;flag2=baz'
assert_expansion() {
    local prefix=$1 expected=$2 flags=${3:-}
    for i in "${COMMAND_ALIASES[@]}"; do
      local nprefix="$i $prefix"
      local nexpected="$i $expected"
      assert_equals "$nexpected" "$(expand "$nprefix\t" "$flags" "/dev/null")"
    done
}


# Usage: assert_expansion_error_not_contains <prefix> <unexpected-error>
#                                            <optional-flags>
#
# For multiple flags separate with semicolon.
#
# Asserts that tab-completing after typing the prefix will not result
# in STDERR receiving a string containing regex unexpected-error.
assert_expansion_error_not_contains() {
  local prefix=$1 not_expected=$2 flags=${3:-}
  local temp_file="$(mktemp "${TEST_TMPDIR}/tmp.stderr.XXXXXX")"
  for i in "${COMMAND_ALIASES[@]}"; do
    local nprefix="$i "
    expand "$nprefix\t" "$flags" "$temp_file" > /dev/null
    assert_not_contains "$not_expected" "$temp_file"
  done
}

#### FIXTURES ##########################################################

make_empty_packages() {
    touch video/streamer2/testing/BUILD
    touch ${PACKAGE_PATH_PREFIX:-}video/streamer2/stuff/BUILD
    touch video/streamer2/names/BUILD
}

make_packages() {
    mkdir -p video/streamer2/testing || fail "mkdir failed"
    cat >video/streamer2/BUILD <<EOF
cc_library(name='task_lib', ...)
cc_library(name='token_bucket', ...)
cc_library(name='with_special+_,=-.@~chars', ...)
#cc_library(name='comment_build_target_1old', ...)
#cc_library(name='comment_build_target_2old', ...)
cc_library(name='comment_build_target_2new', ...)
#cc_test(name='token_bucket_t_1old', ...)
#cc_test(name='token_bucket_t_2old', ...)
cc_test(name='token_bucket_test', ...)
cc_binary(name='token_bucket_binary', ...)
java_binary ( name = 'JavaBinary', ...)
java_binary (
  name = 'AnotherJavaBinary'
  ...
)
cc_binary(other='thing', name='pybin', ...)
genrule(name='checks/thingy', ...)
#cc_binary(name='comment_run_target_1old', ...)
#cc_binary(name='comment_run_target_2old', ...)
cc_binary(name='comment_run_target_2new', ...)
EOF

    mkdir -p ${PACKAGE_PATH_PREFIX:-}video/streamer2/stuff || fail "mkdir failed"
    cat >${PACKAGE_PATH_PREFIX:-}video/streamer2/stuff/BUILD <<EOF
cc_library(name='stuff', ...)
EOF

    mkdir -p video/streamer2/names || fail "mkdir failed"
    cat >video/streamer2/names/BUILD <<EOF
genrule(
  name = 'foo',
  cmd = ('name=foo'),
)
EOF

    mkdir -p dash || fail "mkdir failed"
    cat >dash/BUILD <<EOF
cc_library(
    name = "mia-bid-multiplier-mixer-module",
)
EOF

    mkdir -p video/notapackage
}

#### UNIT TESTS ########################################################

source ${COMPLETION}

assert_expansion_function() {
  local ws=${PWD}
  local function="$1" displacement="$2" type="$3" expected="$4" current="$5"
  # Disable the test ERR trap for the generated function itself.
  local actual_result=$(trap - ERR; "_bazel__${function}" "${ws}" "${displacement}" "${current}" "${type}" | sort)
  assert_equals "$(echo -ne "${expected}")" "${actual_result}"
}

test_expand_rules_in_package() {
    make_packages

    assert_expansion_function "expand_rules_in_package" "" label \
    "stuff " "//video/streamer2/stuff:"
    assert_expansion_function "expand_rules_in_package" "" label \
    'task_lib ' 'video/streamer2:ta'
    assert_expansion_function "expand_rules_in_package" "" label \
    'with_special+_,=-.@~chars ' 'video/streamer2:with_s'

    # From a different directory
    assert_expansion_function "expand_rules_in_package" "video/" label \
    'task_lib ' 'streamer2:ta'
    assert_expansion_function "expand_rules_in_package" "video/" label \
    '' 'video/streamer2:ta'
    assert_expansion_function "expand_rules_in_package" "video/" label \
    'with_special+_,=-.@~chars ' 'streamer2:with_s'

    # label should match test and non-test rules
    assert_expansion_function "expand_rules_in_package" "" label \
    'token_bucket_binary \ntoken_bucket_test ' \
    'video/streamer2:token_bucket_'
    assert_expansion_function "expand_rules_in_package" "" label \
    'stuff ' 'video/streamer2/stuff:s'
    # Test that label does not match commented-out rules.
    assert_expansion_function "expand_rules_in_package" "" label \
    '' 'video/streamer2:comment_build_target_1o'
    assert_expansion_function "expand_rules_in_package" "" label \
    'comment_build_target_2new ' 'video/streamer2:comment_build_target_2'

    # Test that 'label-test' expands only test rules.
    assert_expansion_function "expand_rules_in_package" "" label-test \
    'token_bucket_test ' 'video/streamer2:to'

    # Test that 'label-test' does not match commented-out rules.
    assert_expansion_function "expand_rules_in_package" "" label-test \
    '' 'video/streamer2:token_bucket_t_1o'
    assert_expansion_function "expand_rules_in_package" "" label-test \
    'token_bucket_test ' 'video/streamer2:token_bucket_t'

    # Test that :all wildcard is expanded when there is more than one
    # match.
    #
    # One match => no :all.
    assert_expansion_function "expand_rules_in_package" "" label-test \
    'token_bucket_test ' 'video/streamer2:'
    # Multiple matches => :all.
    assert_expansion_function "expand_rules_in_package" "" label-test \
       'all ' 'video/streamer2:a'

    # Test that label-bin expands only non-test binary rules.
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'token_bucket_binary ' 'video/streamer2:to'

    # Test that label-bin expands for binary and test rules, but not library
    # with BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN set.
    BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=true \
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'token_bucket_binary \ntoken_bucket_test ' 'video/streamer2:to'

    # Test the label-bin expands for test rules, with
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN set.
    BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=1 \
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'token_bucket_test ' 'video/streamer2:token_bucket_t'

    # Test that 'label-bin' expands only non-test binary rules when the
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN is false.
    BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=false \
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'token_bucket_binary ' 'video/streamer2:to'

    # Test that 'label-bin' does not match commented-out rules.
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    '' 'video/streamer2:comment_run_target_1o'
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'comment_run_target_2new ' 'video/streamer2:comment_run_target_2'

    # Test that 'label-bin' expands binaries with spaces in the build rules
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'JavaBinary ' 'video/streamer2:J'

    # Test that 'label-bin' expands targets when the name attribute is not first
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'pybin ' 'video/streamer2:py'

    # Test that 'label-bin' expands binaries with newlines in the build rules
    assert_expansion_function "expand_rules_in_package" "" label-bin \
    'AnotherJavaBinary ' 'video/streamer2:A'

    # Test that the expansion of rules with 'name=...' strings isn't messed up.
    assert_expansion_function "expand_rules_in_package" "" label \
    'foo ' 'video/streamer2/names:'
}

test_expand_package_name() {
    make_packages
    assert_expansion_function "expand_package_name" "" "" \
    "//video/streamer2/stuff/\n//video/streamer2/stuff:" \
    "//video/streamer2/stu"
    assert_expansion_function "expand_package_name" "" "" \
    "//video/notapackage/" \
    "//video/nota"

    assert_expansion_function "expand_package_name" "" "" \
    "video/streamer2/stuff/\nvideo/streamer2/stuff:" \
    "video/streamer2/stu"
    assert_expansion_function "expand_package_name" "" "" \
    "video/notapackage/" \
    "video/nota"

    # From another directory
    assert_expansion_function "expand_package_name" "video/" "" \
    "" \
    "video/streamer2/stu"
    assert_expansion_function "expand_package_name" "video/" "" \
    "" \
    "video/nota"
    assert_expansion_function "expand_package_name" "video/" "" \
    "streamer2/stuff/\nstreamer2/stuff:" \
    "streamer2/stu"
    assert_expansion_function "expand_package_name" "video/" "" \
    "notapackage/" \
    "nota"

    # label-package
    assert_expansion_function "expand_package_name" "" "label-package" \
    "//video/streamer2/stuff \n//video/streamer2/stuff/" \
    "//video/streamer2/stu"
    assert_expansion_function "expand_package_name" "" "label-package" \
    "//video/notapackage/" \
    "//video/nota"
}

test_expand_target_pattern() {
    make_packages
    assert_expansion_function "expand_target_pattern" "" label \
    "stuff " "//video/streamer2/stuff:"

    assert_expansion_function "expand_target_pattern" "" label \
    "//video/streamer2/stuff/\n//video/streamer2/stuff:" \
    "//video/streamer2/stu"

    assert_expansion_function "expand_target_pattern" "" label \
    "stuff " "video/streamer2/stuff:"

    assert_expansion_function "expand_target_pattern" "" label \
    "video/streamer2/stuff/\nvideo/streamer2/stuff:" \
    "video/streamer2/stu"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "stuff " "streamer2/stuff:"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "streamer2/stuff/\nstreamer2/stuff:" \
    "streamer2/stu"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "stuff " "//video/streamer2/stuff:"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "//video/streamer2/stuff/\n//video/streamer2/stuff:" \
    "//video/streamer2/stu"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "" "video/streamer2/stuff:"

    assert_expansion_function "expand_target_pattern" "video/" label \
    "" "video/streamer2/stu"
}

test_complete_pattern() {
  make_packages
  assert_expansion_function "complete_pattern" "" label \
      "stuff " "//video/streamer2/stuff:"

  assert_expansion_function "complete_pattern" "" label \
      "//video/streamer2/stuff/\n//video/streamer2/stuff:" \
      "//video/streamer2/stu"

  assert_expansion_function "complete_pattern" "" label-package \
      "//video/streamer2/stuff \n//video/streamer2/stuff/" \
      "//video/streamer2/stu"

  assert_expansion_function "complete_pattern" "" command \
      "clean " "clea"

  assert_expansion_function "complete_pattern" "" info-key \
      "install_base " "install_b"

  assert_expansion_function "complete_pattern" "" '{clean,add}' \
      "clean " "clea"

  assert_expansion_function "complete_pattern" "" 'command|{abc,def}' \
      "abc " "ab"

  assert_expansion_function "complete_pattern" "" 'command|{abc,def}' \
      "clean " "clea"

  # Assert label expansion
  assert_expansion_function "complete_pattern" "" label \
      "stuff " "//video/streamer2/stuff:"
  assert_expansion_function "complete_pattern" "" label \
      'task_lib ' 'video/streamer2:ta'
  assert_expansion_function "complete_pattern" "" label \
      'with_special+_,=-.@~chars ' 'video/streamer2:with_s'

  # From a different directory
  assert_expansion_function "complete_pattern" "video/" label \
      "stuff " "//video/streamer2/stuff:"
  assert_expansion_function "complete_pattern" "video/" label \
      'task_lib ' 'streamer2:ta'
  assert_expansion_function "complete_pattern" "video/" label \
      '' 'video/streamer2:ta'
  assert_expansion_function "complete_pattern" "video/" label \
      'with_special+_,=-.@~chars ' 'streamer2:with_s'

  # Path expansion
  if [[ -z $PACKAGE_PATH_PREFIX ]]; then
      assert_expansion_function "complete_pattern" "" path \
          "video/streamer2/BUILD \nvideo/streamer2/names/\nvideo/streamer2/stuff/\nvideo/streamer2/testing/" \
          "video/streamer2/"
  else
      # When $PACKAGE_PATH_PREFIX is set, the "stuff" directory will not be in
      # the same directory as the others, so we have to omit it.
      assert_expansion_function "complete_pattern" "" path \
          "video/streamer2/BUILD \nvideo/streamer2/names/\nvideo/streamer2/testing/" \
          "video/streamer2/"
  fi
}

#### TESTS #############################################################

test_basic_subcommand_expansion() {
    # 'Test basic subcommand completion'
    assert_expansion 'bui' \
                     'build '
    assert_expansion 'hel' \
                     'help '
    assert_expansion 'shut' \
                     'shutdown '
}

test_common_startup_options() {
    # 'Test common startup option completion'
    assert_expansion '--hos' \
                     '--host_jvm_'
    assert_expansion '--host_jvm_a' \
                     '--host_jvm_args='
}

test_build_options() {
    # 'Test build option completion'
    assert_expansion 'build --keep_g' \
                     'build --keep_going '
    assert_expansion 'build --expe' \
                     'build --experimental_'
    # ...but 'help' doesn't expand this option:
    assert_expansion 'help --cros' \
                     'help --cros'
    assert_expansion 'build --test_stra' \
                     'build --test_strategy='
}

test_query_options() {
    assert_expansion 'query --out' \
                     'query --output'

    # Basic label expansion works for query, too.
    make_packages
    assert_expansion 'query video/streamer2:ta' \
                     'query video/streamer2:task_lib '
    assert_expansion 'query //video/streamer2:ta'\
                     'query //video/streamer2:task_lib '
}

test_run_options() {
    # Should be the same as the build options.
    # 'Test run option completion'
    assert_expansion 'run --keep_g' \
                     'run --keep_going '
    assert_expansion 'run --expe' \
                     'run --experimental_'
}

test_tristate_option() {
    # 'Test tristate option completion'
    assert_expansion 'build --nocache_test_result' \
                     'build --nocache_test_results '
}

make_dirs() {
    mkdir -p video/streamer2/testing || fail "mkdir failed"
    mkdir -p ${PACKAGE_PATH_PREFIX:-}video/streamer2/stuff || fail "mkdir failed"
    mkdir -p video/streamer2/names || fail "mkdir failed"
}


test_directory_expansion() {
    # 'Test expansion of directory names, even across package_path'

    make_dirs

    assert_expansion 'build vide' \
                     'build video/'
    assert_expansion 'build video/' \
                     'build video/streamer2/'
    assert_expansion 'build video/streamer2/t' \
                     'build video/streamer2/testing/'
    assert_expansion 'build video/streamer2/s' \
                     'build video/streamer2/stuff/'

    # Now add BUILD files; it should no longer expand the trailing slashes:
    make_empty_packages

    assert_expansion 'build video/streamer2/t' \
                     'build video/streamer2/testing'
    assert_expansion 'build video/streamer2/s' \
                     'build video/streamer2/stuff'

    # Use of absolute forms of labels:
    assert_expansion 'build //vide' \
                     'build //video/'
    assert_expansion 'build //video/' \
                     'build //video/streamer2/'
    assert_expansion 'build //video/streamer2/t' \
                     'build //video/streamer2/testing'
    assert_expansion 'build //video/streamer2/s' \
                     'build //video/streamer2/stuff'
}

test_directory_expansion_in_subdir() {
    # 'Test expansion of directory names, when in a subdir of the workspace.'

    make_dirs
    cd video 2>/dev/null || exit

    # Use of "video" while in "video" => no match:
    assert_expansion 'build vide' \
                     'build vide'
    assert_expansion 'build video/' \
                     'build video/'
    assert_expansion 'build video/streamer2/t' \
                     'build video/streamer2/t'
    assert_expansion 'build video/streamer2/s' \
                     'build video/streamer2/s'

    # Use of "//video" while in "video" => matches absolute:
    assert_expansion 'build //vide' \
                     'build //video/'
    assert_expansion 'build //video/' \
                     'build //video/streamer2/'
    assert_expansion 'build //video/streamer2/t' \
                     'build //video/streamer2/testing/'
    assert_expansion 'build //video/streamer2/s' \
                     'build //video/streamer2/stuff/'

    # Use of relative paths => matches
    assert_expansion 'build streamer2/t' \
                     'build streamer2/testing/'
    assert_expansion 'build streamer2/s' \
                     'build streamer2/stuff/'
}

test_target_expansion() {
    # 'Test expansion of target names within packages'

    make_packages

    # TODO(bazel-team): (2009) it would be good to test that "streamer2\t"
    # yielded a menu of "streamer2:" and "streamer2/", but testing the
    # terminal output (as opposed to the result of expansion) is
    # beyond our ability right now.

    assert_expansion 'build video/streamer2:ta' \
                     'build video/streamer2:task_lib '

    # Special characters
    assert_expansion 'build video/streamer2:with_s' \
                     'build video/streamer2:with_special+_,=-.@~chars '

    # Also, that 'bazel build' matches test and non-test rules (lack
    # of trailing space after match => not unique match).
    assert_expansion 'build video/streamer2:to' \
                     'build video/streamer2:token_bucket'

    assert_expansion 'build video/streamer2/s' \
                     'build video/streamer2/stuff'

    assert_expansion 'build video/streamer2/stuff:s' \
                     'build video/streamer2/stuff:stuff '

    # Test that 'bazel build' does not match commented-out rules.
    assert_expansion 'build video/streamer2:comment_build_target_1o' \
                     'build video/streamer2:comment_build_target_1o'

    assert_expansion 'build video/streamer2:comment_build_target_2' \
                     'build video/streamer2:comment_build_target_2new '

    # Test that 'bazel test' expands only test rules.
    assert_expansion 'test video/streamer2:to' \
                     'test video/streamer2:token_bucket_test '

    # Test that 'blaze test' does not match commented-out rules.
    assert_expansion 'test video/streamer2:token_bucket_t_1o' \
                     'test video/streamer2:token_bucket_t_1o'

    assert_expansion 'test video/streamer2:token_bucket_t' \
                     'test video/streamer2:token_bucket_test '

    assert_expansion_error_not_contains 'test video/streamer2:match' \
                                        'syntax error'

    # Test that :all wildcard is expanded when there is more than one
    # match.
    #
    # One match => no :all.
    assert_expansion 'test video/streamer2:' \
                     'test video/streamer2:token_bucket_test '
    # Multiple matches => :all.
    assert_expansion 'build video/streamer2:a' \
                     'build video/streamer2:all '

    # Test that 'bazel run' expands only non-test binary rules.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_binary '

    # Test that 'bazel run' expands for binary and test rules, but not library
    # with BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN set.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=true'

    # Test the 'bazel run' expands for test rules, with
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN set.
    assert_expansion 'run video/streamer2:token_bucket_t' \
                     'run video/streamer2:token_bucket_test ' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=1'

    # Test that 'bazel run' expands only non-test binary rules when the
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN is false.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_binary ' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=false'

    # Test that 'bazel run' expands only non-test binary rules when the
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN is false.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_binary ' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=0'

    # Test that 'bazel run' expands only non-test binary rules when the
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN is invalid.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_binary ' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN=junk'

    # Test that 'bazel run' expands only non-test binary rules when the
    # BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN is empty.
    assert_expansion 'run video/streamer2:to' \
                     'run video/streamer2:token_bucket_binary ' \
                     'BAZEL_COMPLETION_ALLOW_TESTS_FOR_RUN='

    # Test that 'bazel run' does not match commented-out rules.
    assert_expansion 'run video/streamer2:comment_run_target_1o' \
                     'run video/streamer2:comment_run_target_1o'

    assert_expansion 'run video/streamer2:comment_run_target_2' \
                     'run video/streamer2:comment_run_target_2new '

    # Test that 'bazel run' expands binaries with spaces in the build rules
    assert_expansion 'run video/streamer2:J' \
                     'run video/streamer2:JavaBinary '

    # Test that 'bazel run' expands targets when the name attribute is not first
    assert_expansion 'run video/streamer2:py' \
                     'run video/streamer2:pybin '

    # Test that 'bazel run' expands binaries with newlines in the build rules
    assert_expansion 'run video/streamer2:A' \
                     'run video/streamer2:AnotherJavaBinary '

    # Test that the expansion of rules with 'name=...' strings isn't messed up.
    assert_expansion 'build video/streamer2/names:' \
                     'build video/streamer2/names:foo '

    # Test that dashes are matched even when locale isn't C.
    LC_ALL=en_US.UTF-8 \
    assert_expansion 'build dash:m' \
                     'build dash:mia-bid-multiplier-mixer-module '
}

test_target_expansion_in_subdir() {
    # 'Test expansion of targets when in a subdir of the workspace.'

    make_packages
    cd video 2>/dev/null

    # Relative labels:
    assert_expansion 'build streamer2:ta' \
                     'build streamer2:task_lib '

    assert_expansion 'build streamer2:to' \
                     'build streamer2:token_bucket'

    assert_expansion 'build streamer2/s' \
                     'build streamer2/stuff'

    assert_expansion 'build streamer2/stuff:s' \
                     'build streamer2/stuff:stuff '

    # (no match)
    assert_expansion 'build video/streamer2:ta' \
                     'build video/streamer2:ta'

    # Absolute labels work as usual:
    assert_expansion 'build //video/streamer2:ta' \
                     'build //video/streamer2:task_lib '
}

test_target_expansion_in_package() {
    # 'Test expansion of targets when in a package.'

    make_packages
    cd video/streamer2 2>/dev/null

    assert_expansion 'build :ta' \
                     'build :task_lib '

    assert_expansion 'build :to' \
                     'build :token_bucket'

    # allow slashes in rule names
    assert_expansion 'build :checks/th' \
                     'build :checks/thingy '

    assert_expansion 'build s' \
                     'build stuff'

    # (no expansion)
    assert_expansion 'build :s' \
                     'build :s'
}

test_filename_expansion_after_double_dash() {
    make_packages
    assert_expansion 'run :target -- vid' \
                     'run :target -- video/'
    assert_expansion 'run :target -- video/st' \
                     'run :target -- video/streamer2/'
    assert_expansion 'run :target -- video/streamer2/B' \
                     'run :target -- video/streamer2/BUILD '
    assert_expansion 'run :target -- video/streamer2/n' \
                     'run :target -- video/streamer2/names/'

    # Autocomplete arguments as well.
    assert_expansion 'run :target -- --arg=video/streamer2/n' \
                     'run :target -- --arg=video/streamer2/names/'
}

test_help() {
    # "Test that bazel help expands subcommand names"
    assert_expansion 'help qu' \
                     'help query '
    assert_expansion 'help bui' \
                     'help build '
    assert_expansion 'help shut' \
                     'help shutdown '
    assert_expansion 'help start' \
                     'help startup_options '
}

test_info() {
    # "Test that bazel info keys are expanded"
    assert_expansion 'info commi' \
                     'info committed-heap-size '
    assert_expansion 'info i' \
                     'info install_base '
    assert_expansion 'info --show_m' \
                     'info --show_make_env '
}

test_workspace_boundary() {
    # "Test that workspace boundary files are recognized"
    # this test only works for Bazel
    if [[ ! " ${COMMAND_ALIASES[*]} " =~ " bazel " ]]; then return; fi

    mkdir -p sub_repo/some/pkg
    touch sub_repo/some/pkg/BUILD
    cd sub_repo 2>/dev/null

    touch WORKSPACE.bazel
    assert_expansion 'build //s' \
                     'build //some/'

    mv WORKSPACE.bazel MODULE.bazel
    assert_expansion 'build //s' \
                     'build //some/'

    mv MODULE.bazel REPO.bazel
    assert_expansion 'build //s' \
                     'build //some/'

    rm REPO.bazel
    assert_expansion 'build //s' \
                     'build //sub_repo/'
}

test_complete_root_package() {
    # This test only works for Bazel
    if [[ ! " ${COMMAND_ALIASES[*]} " =~ " bazel " ]]; then return; fi

    mkdir pkgs_repo
    touch pkgs_repo/WORKSPACE
    cat > pkgs_repo/BUILD <<'EOF'
cc_binary(name = "main")
EOF
    cd pkgs_repo 2>/dev/null

    assert_expansion 'build //' \
                     'build //:'
}

run_suite "Tests of bash completion of 'blaze' command."
