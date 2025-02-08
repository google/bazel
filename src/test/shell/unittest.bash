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
# Common utility file for Bazel shell tests
#
# unittest.bash: a unit test framework in Bash.
#
# A typical test suite looks like so:
#
#   ------------------------------------------------------------------------
#   #!/bin/bash
#
#   source path/to/unittest.bash || exit 1
#
#   # Test that foo works.
#   function test_foo() {
#     foo >$TEST_log || fail "foo failed";
#     expect_log "blah" "Expected to see 'blah' in output of 'foo'."
#   }
#
#   # Test that bar works.
#   function test_bar() {
#     bar 2>$TEST_log || fail "bar failed";
#     expect_not_log "ERROR" "Unexpected error from 'bar'."
#     ...
#     assert_equals $x $y
#   }
#
#   run_suite "Test suite for blah"
#   ------------------------------------------------------------------------
#
# Each test function is considered to pass iff fail() is not called
# while it is active.  fail() may be called directly, or indirectly
# via other assertions such as expect_log().  run_suite must be called
# at the very end.
#
# A test suite may redefine functions "set_up" and/or "tear_down";
# these functions are executed before and after each test function,
# respectively.  Similarly, "cleanup" and "timeout" may be redefined,
# and these function are called upon exit (of any kind) or a timeout.
#
# The user can pass --test_filter to blaze test to select specific tests
# to run with Bash globs. A union of tests matching any of the provided globs
# will be run. Additionally the user may define TESTS=(test_foo test_bar ...) to
# specify a subset of test functions to execute, for example, a working set
# during debugging. By default, all functions called test_* will be executed.
#
# This file provides utilities for assertions over the output of a
# command.  The output of the command under test is directed to the
# file $TEST_log, and then the expect_log* assertions can be used to
# test for the presence of certain regular expressions in that file.
#
# The test framework is responsible for restoring the original working
# directory before each test.
#
# The order in which test functions are run is not defined, so it is
# important that tests clean up after themselves.
#
# Each test will be run in a new subshell.
#
# Functions named __* are not intended for use by clients.
#
# This framework implements the "test sharding protocol".
#

[[ -n "$BASH_VERSION" ]] ||
  { echo "unittest.bash only works with bash!" >&2; exit 1; }

export BAZEL_SHELL_TEST=1

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Load the environment support utilities.
source "${DIR}/unittest_utils.sh" || { echo "unittest_utils.sh not found" >&2; exit 1; }

#### Global variables:

TEST_name=""                    # The name of the current test.

TEST_log=$TEST_TMPDIR/log       # The log file over which the
                                # expect_log* assertions work.  Must
                                # be absolute to be robust against
                                # tests invoking 'cd'!

TEST_passed="true"              # The result of the current test;
                                # failed assertions cause this to
                                # become false.

# These variables may be overridden by the test suite:

TESTS=()                        # A subset or "working set" of test
                                # functions that should be run.  By
                                # default, all tests called test_* are
                                # run.

_TEST_FILTERS=()                # List of globs to use to filter the tests.
                                # If non-empty, all tests matching at least one
                                # of the globs are run and test list provided in
                                # the arguments is ignored if present.

__in_tear_down=0                # Indicates whether we are in `tear_down` phase
                                # of test. Used to avoid re-entering `tear_down`
                                # on failures within it.

if (( $# > 0 )); then
  (
    IFS=':'
    echo "WARNING: Passing test names in arguments (--test_arg) is deprecated, please use --test_filter='$*' instead." >&2
  )

  # Legacy behavior is to ignore missing regexp, but with errexit
  # the following line fails without || true.
  # TODO(dmarting): maybe we should revisit the way of selecting
  # test with that framework (use Bazel's environment variable instead).
  TESTS=($(for i in "$@"; do echo $i; done | grep ^test_ || true))
  if (( ${#TESTS[@]} == 0 )); then
    echo "WARNING: Arguments do not specify tests!" >&2
  fi
fi
# TESTBRIDGE_TEST_ONLY contains the value of --test_filter, if any. We want to
# preferentially use that instead of $@ to determine which tests to run.
if [[ ${TESTBRIDGE_TEST_ONLY:-} != "" ]]; then
  if (( ${#TESTS[@]} != 0 )); then
    echo "WARNING: Both --test_arg and --test_filter specified, ignoring --test_arg" >&2
    TESTS=()
  fi
  # Split TESTBRIDGE_TEST_ONLY on colon and store it in `_TEST_FILTERS` array.
  IFS=':' read -r -a _TEST_FILTERS <<< "$TESTBRIDGE_TEST_ONLY"
fi

TEST_verbose="true"             # Whether or not to be verbose.  A
                                # command; "true" or "false" are
                                # acceptable.  The default is: true.

TEST_script="$0"                # Full path to test script
# Check if the script path is absolute, if not prefix the PWD.
if [[ ! "$TEST_script" = /* ]]; then
  TEST_script="${PWD}/$0"
fi


#### Internal functions

function __show_log() {
    echo "-- Test log: -----------------------------------------------------------"
    [[ -e $TEST_log ]] && cat "$TEST_log" || echo "(Log file did not exist.)"
    echo "------------------------------------------------------------------------"
}

# Usage: __pad <title> <pad-char>
# Print $title padded to 80 columns with $pad_char.
function __pad() {
    local title=$1
    local pad=$2
    # Ignore the subshell error -- `head` closes the fd before reading to the
    # end, therefore the subshell will get SIGPIPE while stuck in `write`.
    {
        echo -n "${pad}${pad} ${title} "
        printf "%80s" " " | tr ' ' "$pad"
    } | head -c 80 || true
    echo
}

#### Exported functions

# Usage: init_test ...
# Deprecated.  Has no effect.
function init_test() {
    :
}


# Usage: set_up
# Called before every test function.  May be redefined by the test suite.
function set_up() {
    :
}

# Usage: tear_down
# Called after every test function.  May be redefined by the test suite.
function tear_down() {
    :
}

# Usage: cleanup
# Called upon eventual exit of the test suite.  May be redefined by
# the test suite.
function cleanup() {
    :
}

# Usage: timeout
# Called upon early exit from a test due to timeout.
function timeout() {
    :
}

# Usage: testenv_set_up
# Called prior to set_up. For use by testenv.sh.
function testenv_set_up() {
    :
}

# Usage: testenv_tear_down
# Called after tear_down. For use by testenv.sh.
function testenv_tear_down() {
    :
}

# Usage: fail <message> [<message> ...]
# Print failure message with context information, and mark the test as
# a failure.  The context includes a stacktrace including the longest sequence
# of calls outside this module.  (We exclude the top and bottom portions of
# the stack because they just add noise.)  Also prints the contents of
# $TEST_log.
function fail() {
    __show_log >&2
    echo "${TEST_name} FAILED: $*." >&2
    # Keep the original error message if we fail in `tear_down` after a failure.
    [[ "${TEST_passed}" == "true" ]] && echo "$@" >"$TEST_TMPDIR"/__fail
    TEST_passed="false"
    __show_stack
    # Cleanup as we are leaving the subshell now
    __run_tear_down_after_failure
    exit 1
}

function __run_tear_down_after_failure() {
    # Skip `tear_down` after a failure in `tear_down` to prevent infinite
    # recursion.
    (( __in_tear_down )) && return
    __in_tear_down=1
    echo -e "\nTear down:\n" >&2
    tear_down
    testenv_tear_down
}

# Usage: warn <message>
# Print a test warning with context information.
# The context includes a stacktrace including the longest sequence
# of calls outside this module.  (We exclude the top and bottom portions of
# the stack because they just add noise.)
function warn() {
    __show_log >&2
    echo "${TEST_name} WARNING: $1." >&2
    __show_stack

    if [[ -n "${TEST_WARNINGS_OUTPUT_FILE:-}" ]]; then
      echo "${TEST_name} WARNING: $1." >> "$TEST_WARNINGS_OUTPUT_FILE"
    fi
}

# Usage: show_stack
# Prints the portion of the stack that does not belong to this module,
# i.e. the user's code that called a failing assertion.  Stack may not
# be available if Bash is reading commands from stdin; an error is
# printed in that case.
__show_stack() {
    local i=0
    local trace_found=0

    # Skip over active calls within this module:
    while (( i < ${#FUNCNAME[@]} )) && [[ ${BASH_SOURCE[i]:-} == "${BASH_SOURCE[0]}" ]]; do
       (( ++i ))
    done

    # Show all calls until the next one within this module (typically run_suite):
    while (( i < ${#FUNCNAME[@]} )) && [[ ${BASH_SOURCE[i]:-} != "${BASH_SOURCE[0]}" ]]; do
        # Read online docs for BASH_LINENO to understand the strange offset.
        # Undefined can occur in the BASH_SOURCE stack apparently when one exits from a subshell
        echo "${BASH_SOURCE[i]:-"Unknown"}:${BASH_LINENO[i - 1]:-"Unknown"}: in call to ${FUNCNAME[i]:-"Unknown"}" >&2
        (( ++i ))
        trace_found=1
    done

    (( trace_found )) || echo "[Stack trace not available]" >&2
}

# Usage: expect_log <regexp> [error-message]
# Asserts that $TEST_log matches regexp.  On failure prints the specified
# (optional) error message, and returns non-zero.
function expect_log() {
    local pattern=$1
    local message=${2:-"Expected regexp '$pattern' not found"}
    grep -sq -- "$pattern" $TEST_log && return 0

    fail "$message"
    return 1
}

# Usage: expect_log_warn <regexp> [error-message]
# Warns if $TEST_log does not match regexp.  On failure prints the specified
# (optional) warning message, and returns non-zero.
function expect_log_warn() {
    local pattern=$1
    local message=${2:-"Expected regexp '$pattern' not found"}
    grep -sq -- "$pattern" $TEST_log && return 0

    warn "$message"
    return 1
}

# Usage: expect_log_once <regexp> [error-message]
# Asserts that $TEST_log contains one line matching <regexp>.
# On failure prints the specified (optional) error message, and returns
# non-zero.
function expect_log_once() {
    local pattern=$1
    local message=${2:-"Expected regexp '$pattern' not found exactly once"}
    expect_log_n "$pattern" 1 "$message"
}

# Usage: expect_log_n <regexp> <count> [error-message]
# Asserts that $TEST_log contains <count> lines matching <regexp>.
# On failure prints the specified (optional) error message, and returns
# non-zero.
function expect_log_n() {
    local pattern=$1
    local expectednum=${2:-1}
    local message=${3:-"Expected regexp '$pattern' not found exactly $expectednum times"}
    local count=$(grep -sc -- "$pattern" $TEST_log)
    (( count == expectednum )) && return 0
    fail "$message"
    return 1
}

# Usage: expect_not_log <regexp> [error-message]
# Asserts that $TEST_log does not match regexp.
# On failure prints the specified (optional) error message, and returns
# non-zero.
function expect_not_log() {
    local pattern=$1
    local message=${2:-"Unexpected regexp '$pattern' found"}
    grep -sq -- "$pattern" $TEST_log || return 0

    fail "$message"
    return 1
}

# Usage: expect_query_targets <arguments>
# Checks that log file contains exactly the targets in the argument list.
function expect_query_targets() {
  for arg in "$@"; do
    expect_log_once "^$arg$"
  done

# Checks that the number of lines started with '//' equals to the number of
# arguments provided.
  expect_log_n "^//[^ ]*$" $#
}

# Usage: expect_log_with_timeout <regexp> <timeout> [error-message]
# Waits for the given regexp in the $TEST_log for up to timeout seconds.
# On failure prints the specified (optional) error message, and returns
# non-zero.
function expect_log_with_timeout() {
    local pattern=$1
    local timeout=$2
    local message=${3:-"Regexp '$pattern' not found in $timeout seconds"}
    local count=0
    while (( count < timeout )); do
      grep -sq -- "$pattern" "$TEST_log" && return 0
      let count=count+1
      sleep 1
    done

    grep -sq -- "$pattern" "$TEST_log" && return 0
    fail "$message"
    return 1
}

# Usage: expect_cmd_with_timeout <expected> <cmd> [timeout]
# Repeats the command once a second for up to timeout seconds (10s by default),
# until the output matches the expected value. Fails and returns 1 if
# the command does not return the expected value in the end.
function expect_cmd_with_timeout() {
    local expected="$1"
    local cmd="$2"
    local timeout=${3:-10}
    local count=0
    while (( count < timeout )); do
      local actual="$($cmd)"
      [[ "$expected" == "$actual" ]] && return 0
      (( ++count ))
      sleep 1
    done

    [[ "$expected" == "$actual" ]] && return 0
    fail "Expected '${expected}' within ${timeout}s, was '${actual}'"
    return 1
}

# Usage: assert_one_of <expected_list>... <actual>
# Asserts that actual is one of the items in expected_list
#
# Example:
#     local expected=( "foo", "bar", "baz" )
#     assert_one_of $expected $actual
function assert_one_of() {
    local args=("$@")
    local last_arg_index=$((${#args[@]} - 1))
    local actual=${args[last_arg_index]}
    unset args[last_arg_index]
    for expected_item in "${args[@]}"; do
      [[ "$expected_item" == "$actual" ]] && return 0
    done;

    fail "Expected one of '${args[*]}', was '$actual'"
    return 1
}

# Usage: assert_not_one_of <expected_list>... <actual>
# Asserts that actual is not one of the items in expected_list
#
# Example:
#     local unexpected=( "foo", "bar", "baz" )
#     assert_not_one_of $unexpected $actual
function assert_not_one_of() {
    local args=("$@")
    local last_arg_index=$((${#args[@]} - 1))
    local actual=${args[last_arg_index]}
    unset args[last_arg_index]
    for expected_item in "${args[@]}"; do
      if [[ "$expected_item" == "$actual" ]]; then
        fail "'${args[*]}' contains '$actual'"
        return 1
      fi
    done;

    return 0
}

# Usage: assert_equals <expected> <actual>
# Asserts [[ expected == actual ]].
function assert_equals() {
    local expected=$1 actual=$2
    [[ "$expected" == "$actual" ]] && return 0

    local context=""
    if [[ "$#" -eq "3" ]]
    then
      context="${3}: "
    fi
    fail "${context}Expected '$expected', was '$actual'"
    return 1
}

# Usage: assert_not_equals <unexpected> <actual>
# Asserts [[ unexpected != actual ]].
function assert_not_equals() {
    local unexpected=$1 actual=$2
    [[ "$unexpected" != "$actual" ]] && return 0;

    fail "Expected not '${unexpected}', was '${actual}'"
    return 1
}

# Usage: assert_contains <regexp> <file> [error-message]
# Asserts that file matches regexp.  On failure copies the file to undeclared
# outputs, prints the specified (optional) error message, and returns non-zero.
function assert_contains() {
    local pattern=$1
    local file=$2
    local message=${3:-"Expected regexp '$pattern' not found in '$file'"}
    grep -sq -- "$pattern" "$file" && return 0

    fail "$message" $(__copy_to_undeclared_outputs "$file")
    return 1
}

# Usage: assert_not_contains <regexp> <file> [error-message]
# Asserts that file does not match regexp.  On failure copies the file to
# undeclared outputs, prints the specified (optional) error message, and returns
# non-zero.
function assert_not_contains() {
    local pattern=$1
    local file=$2
    local message=${3:-"Expected regexp '$pattern' found in '$file'"}

    if [[ -f "$file" ]]; then
      grep -sq -- "$pattern" "$file" || return 0
    else
      fail "$file is not a file: $message"
      return 1
    fi

    fail "$message" $(__copy_to_undeclared_outputs "$file")
    return 1
}

function assert_contains_n() {
    local pattern=$1
    local expectednum=${2:-1}
    local file=$3
    local message=${4:-"Expected regexp '$pattern' not found exactly $expectednum times in '$file'"}
    local count
    if [[ -f "$file" ]]; then
      count=$(grep -sc -- "$pattern" "$file")
    else
      fail "$file is not a file: $message"
      return 1
    fi
    (( count == expectednum )) && return 0

    fail "$message" $(__copy_to_undeclared_outputs "$file")
    return 1
}

# Usage: assert_exists <file> [error-message]
# Asserts that the file exists.
function assert_exists() {
    local file=$1
    local message=${2:-"Expected '$file' to exist"}
    if [[ -f "$file" ]]; then
        return 0
    fi

    fail "$message"
    return 1
}

# Usage: assert_not_exists <file> [error-message]
# Asserts that the file does not exist.
function assert_not_exists() {
    local file=$1
    local message=${2:-"Expected '$file' to not exist"}
    if ! [[ -f "$file" ]]; then
        return 0
    fi

    fail "$message"
    return 1
}

# Usage: assert_empty_file <file> [error-message]
# Asserts that the file exists and is empty. On failure copies the file to
# undeclared outputs, prints the specified (optional) error message, and returns
# non-zero.
function assert_empty_file() {
    local file=$1
    local message=${2:-"Expected '$file' to exist and be empty"}
    if [[ -f "$file" && ! -s "$file" ]]; then
        return 0
    fi

    fail "$message" $(__copy_to_undeclared_outputs "$file")
    return 1
}

# Usage: assert_nonempty_file <file> [error-message]
# Asserts that the file exists and is nonempty. On failure copies the file to
# undeclared outputs, prints the specified (optional) error message, and returns
# non-zero.
function assert_nonempty_file() {
    local file=$1
    local message=${2:-"Expected '$file' to exist and be nonempty"}
    if [[ -f "$file" && -s "$file" ]]; then
        return 0
    fi

    fail "$message" $(__copy_to_undeclared_outputs "$file")
    return 1
}

# Copies $1 to undeclared outputs with a unique path and logs the mapping
# between the original file and the new file.
function __copy_to_undeclared_outputs() {
  local name=$(basename "$1")
  local uuid=$(uuidgen)
  local testdir="${TEST_UNDECLARED_OUTPUTS_DIR}/${TEST_name}/${uuid}"
  mkdir -p "$testdir"
  local newname="${testdir}/${name}"
  cp "$1" "$newname"
  echo "Copied '$1' to '$newname'"
}

# Updates the global variables TESTS if
# sharding is enabled, i.e. ($TEST_TOTAL_SHARDS > 0).
function __update_shards() {
    [[ -z "${TEST_TOTAL_SHARDS-}" ]] && return 0

    (( TEST_TOTAL_SHARDS > 0 )) ||
      { echo "Invalid total shards ${TEST_TOTAL_SHARDS}" >&2; exit 1; }

    (( TEST_SHARD_INDEX < 0 || TEST_SHARD_INDEX >= TEST_TOTAL_SHARDS )) &&
      { echo "Invalid shard ${TEST_SHARD_INDEX}" >&2; exit 1; }

    IFS=$'\n' read -rd $'\0' -a TESTS < <(
        for test in "${TESTS[@]}"; do echo "$test"; done |
            awk "NR % ${TEST_TOTAL_SHARDS} == ${TEST_SHARD_INDEX}" &&
            echo -en '\0')

    [[ -z "${TEST_SHARD_STATUS_FILE-}" ]] || touch "$TEST_SHARD_STATUS_FILE"
}

# Usage: __test_terminated <signal-number>
# Handler that is called when the test terminated unexpectedly
function __test_terminated() {
    __show_log >&2
    echo "$TEST_name FAILED: terminated by signal $1." >&2
    TEST_passed="false"
    __show_stack
    timeout
    exit 1
}

# Usage: __test_terminated_err
# Handler that is called when the test terminated unexpectedly due to "errexit".
function __test_terminated_err() {
    # When a subshell exits due to signal ERR, its parent shell also exits,
    # thus the signal handler is called recursively and we print out the
    # error message and stack trace multiple times. We're only interested
    # in the first one though, as it contains the most information, so ignore
    # all following.
    if [[ -f $TEST_TMPDIR/__err_handled ]]; then
      exit 1
    fi
    __show_log >&2
    if [[ ! -z "$TEST_name" ]]; then
      echo -n "$TEST_name " >&2
    fi
    echo "FAILED: terminated because this command returned a non-zero status:" >&2
    touch $TEST_TMPDIR/__err_handled
    TEST_passed="false"
    __show_stack
    # If $TEST_name is still empty, the test suite failed before we even started
    # to run tests, so we shouldn't call tear_down.
    if [[ -n "$TEST_name" ]]; then
      __run_tear_down_after_failure
    fi
    exit 1
}

# Usage: __trap_with_arg <handler> <signals ...>
# Helper to install a trap handler for several signals preserving the signal
# number, so that the signal number is available to the trap handler.
function __trap_with_arg() {
    func="$1" ; shift
    for sig ; do
        trap "$func $sig" "$sig"
    done
}

# Usage: <node> <block>
# Adds the block to the given node in the report file. Quotes in the in
# arguments need to be escaped.
function __log_to_test_report() {
    local node="$1"
    local block="$2"
    if [[ ! -e "$XML_OUTPUT_FILE" ]]; then
        local xml_header='<?xml version="1.0" encoding="UTF-8"?>'
        echo "${xml_header}<testsuites></testsuites>" > "$XML_OUTPUT_FILE"
    fi

    # replace match on node with block and match
    # replacement expression only needs escaping for quotes
    perl -e "\
\$input = @ARGV[0]; \
\$/=undef; \
open FILE, '+<$XML_OUTPUT_FILE'; \
\$content = <FILE>; \
if (\$content =~ /($node.*)\$/) { \
  seek FILE, 0, 0; \
  print FILE \$\` . \$input . \$1; \
}; \
close FILE" "$block"
}

# Usage: <total> <passed>
# Adds the test summaries to the xml nodes.
function __finish_test_report() {
    local suite_name="$1"
    local total="$2"
    local passed="$3"
    local failed=$((total - passed))

    # Update the xml output with the suite name and total number of
    # passed/failed tests.
    cat "$XML_OUTPUT_FILE" | \
      sed \
        "s/<testsuites>/<testsuites tests=\"$total\" failures=\"0\" errors=\"$failed\">/" | \
      sed \
        "s/<testsuite>/<testsuite name=\"${suite_name}\" tests=\"$total\" failures=\"0\" errors=\"$failed\">/" \
        > "${XML_OUTPUT_FILE}.bak"

    rm -f "$XML_OUTPUT_FILE"
    mv "${XML_OUTPUT_FILE}.bak" "$XML_OUTPUT_FILE"
}

# Multi-platform timestamp function
UNAME=$(uname -s | tr 'A-Z' 'a-z')
if [[ "$UNAME" == "linux" ]] || [[ "$UNAME" =~ msys_nt* ]]; then
    function timestamp() {
      echo $(($(date +%s%N)/1000000))
    }
else
    function timestamp() {
      # macOS and BSDs do not have %N, so Python is the best we can do.
      # LC_ALL=C works around python 3.8 and 3.9 crash on macOS when the
      # filesystem encoding is unspecified (e.g. when LANG=en_US).
      local PYTHON=python
      command -v python3 &> /dev/null && PYTHON=python3
      LC_ALL=C "${PYTHON}" -c 'import time; print(int(round(time.time() * 1000)))'
    }
fi

function get_run_time() {
  local ts_start=$1
  local ts_end=$2
  run_time_ms=$((ts_end - ts_start))
  echo $((run_time_ms / 1000)).${run_time_ms: -3}
}

# Usage: run_tests <suite-comment>
# Must be called from the end of the user's test suite.
# Calls exit with zero on success, non-zero otherwise.
function run_suite() {
  local message="$1"
  # The name of the suite should be the script being run, which
  # will be the filename with the ".sh" extension removed.
  local suite_name="$(basename "$0")"

  echo >&2
  echo "$message" >&2
  echo >&2

  __log_to_test_report "<\/testsuites>" "<testsuite></testsuite>"

  local total=0
  local passed=0

  atexit "cleanup"

  # If the user didn't specify an explicit list of tests (e.g. a
  # working set), use them all.
  if (( ${#TESTS[@]} == 0 )); then
    # Even if there aren't any tests, this needs to succeed.
    local all_tests=()
    IFS=$'\n' read -d $'\0' -ra all_tests < <(
        declare -F | awk '{print $3}' | grep ^test_ || true; echo -en '\0')

    if (( "${#_TEST_FILTERS[@]}" == 0 )); then
      # Use ${array[@]+"${array[@]}"} idiom to avoid errors when running with
      # Bash version <= 4.4 with `nounset` when `all_tests` is empty (
      # https://github.com/bminor/bash/blob/a0c0a00fc419b7bc08202a79134fcd5bc0427071/CHANGES#L62-L63).
      TESTS=("${all_tests[@]+${all_tests[@]}}")
    else
      for t in "${all_tests[@]+${all_tests[@]}}"; do
        local matches=0
        for f in "${_TEST_FILTERS[@]}"; do
          # We purposely want to glob match.
          # shellcheck disable=SC2053
          [[ "$t" = $f ]] && matches=1 && break
        done
        if (( matches )); then
          TESTS+=("$t")
        fi
      done
    fi

  elif [[ -n "${TEST_WARNINGS_OUTPUT_FILE:-}" ]]; then
    if grep -q "TESTS=" "$TEST_script" ; then
      echo "TESTS variable overridden in sh_test. Please remove before submitting" \
        >> "$TEST_WARNINGS_OUTPUT_FILE"
    fi
  fi

  # Reset TESTS in the common case where it contains a single empty string.
  if [[ -z "${TESTS[*]-}" ]]; then
    TESTS=()
  fi
  local original_tests_size=${#TESTS[@]}

  __update_shards

  if [[ "${#TESTS[@]}" -ne 0 ]]; then
    for TEST_name in "${TESTS[@]}"; do
      >"$TEST_log" # Reset the log.
      TEST_passed="true"

      (( ++total ))
      if [[ "$TEST_verbose" == "true" ]]; then
          date >&2
          __pad "$TEST_name" '*' >&2
      fi

      local run_time="0.0"
      rm -f "${TEST_TMPDIR}"/{__ts_start,__ts_end}

      if [[ "$(type -t "$TEST_name")" == function ]]; then
        # Save exit handlers eventually set.
        local SAVED_ATEXIT="$ATEXIT";
        ATEXIT=

        # Run test in a subshell.
        rm -f "${TEST_TMPDIR}"/__err_handled
        __trap_with_arg __test_terminated INT KILL PIPE TERM ABRT FPE ILL QUIT SEGV

        # Remember -o pipefail value and disable it for the subshell result
        # collection.
        if [[ "${SHELLOPTS}" =~ (^|:)pipefail(:|$) ]]; then
          local __opt_switch=-o
        else
          local __opt_switch=+o
        fi
        set +o pipefail
        (
          set "${__opt_switch}" pipefail
          # if errexit is enabled, make sure we run cleanup and collect the log.
          if [[ "$-" = *e* ]]; then
            set -E
            trap __test_terminated_err ERR
          fi
          timestamp >"${TEST_TMPDIR}"/__ts_start
          testenv_set_up
          set_up
          eval "$TEST_name"
          __in_tear_down=1
          tear_down
          testenv_tear_down
          timestamp >"${TEST_TMPDIR}"/__ts_end
          test "$TEST_passed" == "true"
        ) 2>&1 | tee "${TEST_TMPDIR}"/__log
        # Note that tee will prevent the control flow continuing if the test
        # spawned any processes which are still running and have not closed
        # their stdout.

        test_subshell_status=${PIPESTATUS[0]}
        set "${__opt_switch}" pipefail
        if (( test_subshell_status != 0 )); then
          TEST_passed="false"
          # Ensure that an end time is recorded in case the test subshell
          # terminated prematurely.
          [[ -f "$TEST_TMPDIR"/__ts_end ]] || timestamp >"$TEST_TMPDIR"/__ts_end
        fi

        # Calculate run time for the testcase.
        local ts_start
        ts_start=$(<"${TEST_TMPDIR}"/__ts_start)
        local ts_end
        ts_end=$(<"${TEST_TMPDIR}"/__ts_end)
        run_time=$(get_run_time $ts_start $ts_end)

        # Eventually restore exit handlers.
        if [[ -n "$SAVED_ATEXIT" ]]; then
          ATEXIT="$SAVED_ATEXIT"
          trap "$ATEXIT" EXIT
        fi
      else # Bad test explicitly specified in $TESTS.
        fail "Not a function: '$TEST_name'"
      fi

      local testcase_tag=""

      local red='\033[0;31m'
      local green='\033[0;32m'
      local no_color='\033[0m'

      if [[ "$TEST_verbose" == "true" ]]; then
          echo >&2
      fi

      if [[ "$TEST_passed" == "true" ]]; then
        if [[ "$TEST_verbose" == "true" ]]; then
          echo -e "${green}PASSED${no_color}: ${TEST_name}" >&2
        fi
        (( ++passed ))
        testcase_tag="<testcase name=\"${TEST_name}\" status=\"run\" time=\"${run_time}\" classname=\"\"></testcase>"
      else
        echo -e "${red}FAILED${no_color}: ${TEST_name}" >&2
        # end marker in CDATA cannot be escaped, we need to split the CDATA sections
        log=$(sed 's/]]>/]]>]]&gt;<![CDATA[/g' "${TEST_TMPDIR}"/__log)
        fail_msg=$(cat "${TEST_TMPDIR}"/__fail 2> /dev/null || echo "No failure message")
        # Replacing '&' with '&amp;', '<' with '&lt;', '>' with '&gt;', and '"' with '&quot;'
        escaped_fail_msg=$(echo "$fail_msg" | sed 's/&/\&amp;/g' | sed 's/</\&lt;/g' | sed 's/>/\&gt;/g' | sed 's/"/\&quot;/g')
        testcase_tag="<testcase name=\"${TEST_name}\" status=\"run\" time=\"${run_time}\" classname=\"\"><error message=\"${escaped_fail_msg}\"><![CDATA[${log}]]></error></testcase>"
      fi

      if [[ "$TEST_verbose" == "true" ]]; then
          echo >&2
      fi
      __log_to_test_report "<\/testsuite>" "$testcase_tag"
    done
  fi

  __finish_test_report "$suite_name" $total $passed
  __pad "${passed} / ${total} tests passed." '*' >&2
  if (( original_tests_size == 0 )); then
    __pad "No tests found." '*'
    exit 1
  elif (( total != passed )); then
    __pad "There were errors." '*' >&2
    exit 1
  elif (( total == 0 )); then
    __pad "No tests executed due to sharding. Check your test's shard_count." '*'
    __pad "Succeeding anyway." '*'
  fi

  exit 0
}
