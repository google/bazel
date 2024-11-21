# Copyright 2018 The Bazel Authors. All rights reserved.
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

# Runfiles lookup library for Bazel-built Bash binaries and tests, version 3.
#
# VERSION HISTORY:
# - version 3: Fixes a bug in the init code on macOS and makes the library aware
#              of Bzlmod repository mappings.
#   Features:
#     - With Bzlmod enabled, rlocation now takes the repository mapping of the
#       Bazel repository containing the calling script into account when
#       looking up runfiles. The new, optional second argument to rlocation can
#       be used to specify the canonical name of the Bazel repository to use
#       instead of this default. The new runfiles_current_repository function
#       can be used to obtain the canonical name of the N-th caller's Bazel
#       repository.
#   Fixed:
#     - Sourcing a shell script that contains the init code from a shell script
#       that itself contains the init code no longer fails on macOS.
#   Compatibility:
#     - The init script and the runfiles library are backwards and forwards
#       compatible with version 2.
# - version 2: Shorter init code.
#   Features:
#     - "set -euo pipefail" only at end of init code.
#       "set -e" breaks the source <path1> || source <path2> || ... scheme on
#       macOS, because it terminates if path1 does not exist.
#     - Not exporting any environment variables in init code.
#       This is now done in runfiles.bash itself.
#   Compatibility:
#     - The v1 init code can load the v2 library, i.e. if you have older source
#       code (still using v1 init) then you can build it with newer Bazel (which
#       contains the v2 library).
#     - The reverse is not true: the v2 init code CANNOT load the v1 library,
#       i.e. if your project (or any of its external dependencies) use v2 init
#       code, then you need a newer Bazel version (which contains the v2
#       library).
# - version 1: Original Bash runfiles library.
#
# ENVIRONMENT:
# - If RUNFILES_LIB_DEBUG=1 is set, the script will print diagnostic messages to
#   stderr.
#
# USAGE:
# 1.  Depend on this runfiles library from your build rule:
#
#       sh_binary(
#           name = "my_binary",
#           ...
#           deps = ["@bazel_tools//tools/bash/runfiles"],
#       )
#
# 2.  Source the runfiles library.
#
#     The runfiles library itself defines rlocation which you would need to look
#     up the library's runtime location, thus we have a chicken-and-egg problem.
#     Insert the following code snippet to the top of your main script:
#
#       # --- begin runfiles.bash initialization v3 ---
#       # Copy-pasted from the Bazel Bash runfiles library v3.
#       set -uo pipefail; set +e; f=bazel_tools/tools/bash/runfiles/runfiles.bash
#       # shellcheck disable=SC1090
#       source "${RUNFILES_DIR:-/dev/null}/$f" 2>/dev/null || \
#         source "$(grep -sm1 "^$f " "${RUNFILES_MANIFEST_FILE:-/dev/null}" | cut -f2- -d' ')" 2>/dev/null || \
#         source "$0.runfiles/$f" 2>/dev/null || \
#         source "$(grep -sm1 "^$f " "$0.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
#         source "$(grep -sm1 "^$f " "$0.exe.runfiles_manifest" | cut -f2- -d' ')" 2>/dev/null || \
#         { echo>&2 "ERROR: cannot find $f"; exit 1; }; f=; set -e
#       # --- end runfiles.bash initialization v3 ---
#
#
# 3.  Use rlocation to look up runfile paths.
#
#       cat "$(rlocation my_workspace/path/to/my/data.txt)"
#

if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi

case "$(uname -s | tr [:upper:] [:lower:])" in
msys*|mingw*|cygwin*)
  # matches an absolute Windows path
  export _RLOCATION_ISABS_PATTERN="^[a-zA-Z]:[/\\]"
  # Windows paths are case insensitive and Bazel and MSYS2 capitalize differently, so we can't
  # assume that all paths are in the same native case.
  export _RLOCATION_GREP_CASE_INSENSITIVE_ARGS=-i
  ;;
*)
  # matches an absolute Unix path
  export _RLOCATION_ISABS_PATTERN="^/[^/].*"
  export _RLOCATION_GREP_CASE_INSENSITIVE_ARGS=
  ;;
esac

# Does not exit with a non-zero exit code if no match is found and performs a case-insensitive
# search on Windows.
function __runfiles_maybe_grep() {
  grep $_RLOCATION_GREP_CASE_INSENSITIVE_ARGS "$@" || test $? = 1;
}
export -f __runfiles_maybe_grep

# Prints to stdout the runtime location of a data-dependency.
# The optional second argument can be used to specify the canonical name of the
# repository whose repository mapping should be used to resolve the repository
# part of the provided path. If not specified, the repository of the caller is
# used.
function rlocation() {
  if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
    echo >&2 "INFO[runfiles.bash]: rlocation($1): start"
  fi
  if [[ "$1" =~ $_RLOCATION_ISABS_PATTERN ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: rlocation($1): absolute path, return"
    fi
    # If the path is absolute, print it as-is.
    echo "$1"
    return 0
  elif [[ "$1" == ../* || "$1" == */../* || "$1" == */.. || "$1" == ./* || "$1" == */./* || "$1" == */. || "$1" == *//* ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "ERROR[runfiles.bash]: rlocation($1): path is not normalized"
    fi
    return 1
  elif [[ "$1" == \\* ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "ERROR[runfiles.bash]: rlocation($1): absolute path without" \
               "drive name"
    fi
    return 1
  fi

  if [[ -f "$RUNFILES_REPO_MAPPING" ]]; then
    local -r target_repo_apparent_name=$(echo "$1" | cut -d / -f 1)
     # Use -s to get an empty remainder if the argument does not contain a slash.
    # The repo mapping should not be applied to single segment paths, which may
    # be root symlinks.
    local -r remainder=$(echo "$1" | cut -s -d / -f 2-)
    if [[ -n "$remainder" ]]; then
      if [[ -z "${2+x}" ]]; then
        local -r source_repo=$(runfiles_current_repository 2)
      else
        local -r source_repo=$2
      fi
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: rlocation($1): looking up canonical name for ($target_repo_apparent_name) from ($source_repo) in ($RUNFILES_REPO_MAPPING)"
      fi
      local -r target_repo=$(__runfiles_maybe_grep -m1 "^$source_repo,$target_repo_apparent_name," "$RUNFILES_REPO_MAPPING" | cut -d , -f 3)
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: rlocation($1): canonical name of target repo is ($target_repo)"
      fi
      if [[ -n "$target_repo" ]]; then
        local -r rlocation_path="$target_repo/$remainder"
      else
        local -r rlocation_path="$1"
      fi
    else
      local -r rlocation_path="$1"
    fi
  else
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: rlocation($1): not using repository mapping ($RUNFILES_REPO_MAPPING) since it does not exist"
    fi
    local -r rlocation_path="$1"
  fi

  runfiles_rlocation_checked "$rlocation_path"
}
export -f rlocation

# Exports the environment variables that subprocesses need in order to use
# runfiles.
# If a subprocess is a Bazel-built binary rule that also uses the runfiles
# libraries under @bazel_tools//tools/<lang>/runfiles, then that binary needs
# these envvars in order to initialize its own runfiles library.
function runfiles_export_envvars() {
  if [[ ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" \
        && ! -d "${RUNFILES_DIR:-/dev/null}" ]]; then
    return 1
  fi

  if [[ ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
    if [[ -f "$RUNFILES_DIR/MANIFEST" ]]; then
      export RUNFILES_MANIFEST_FILE="$RUNFILES_DIR/MANIFEST"
    elif [[ -f "${RUNFILES_DIR}_manifest" ]]; then
      export RUNFILES_MANIFEST_FILE="${RUNFILES_DIR}_manifest"
    else
      export RUNFILES_MANIFEST_FILE=
    fi
  elif [[ ! -d "${RUNFILES_DIR:-/dev/null}" ]]; then
    if [[ "$RUNFILES_MANIFEST_FILE" == */MANIFEST \
          && -d "${RUNFILES_MANIFEST_FILE%/MANIFEST}" ]]; then
      export RUNFILES_DIR="${RUNFILES_MANIFEST_FILE%/MANIFEST}"
      export JAVA_RUNFILES="$RUNFILES_DIR"
    elif [[ "$RUNFILES_MANIFEST_FILE" == *_manifest \
          && -d "${RUNFILES_MANIFEST_FILE%_manifest}" ]]; then
      export RUNFILES_DIR="${RUNFILES_MANIFEST_FILE%_manifest}"
      export JAVA_RUNFILES="$RUNFILES_DIR"
    else
      export RUNFILES_DIR=
    fi
  fi
}
export -f runfiles_export_envvars

# Returns the canonical name of the Bazel repository containing the script that
# calls this function.
# The optional argument N, which defaults to 1, can be used to return the
# canonical name of the N-th caller instead.
#
# Note: This function only works correctly with Bzlmod enabled. Without Bzlmod,
# its return value is ignored if passed to rlocation.
function runfiles_current_repository() {
  local -r idx=${1:-1}
  local -r raw_caller_path="${BASH_SOURCE[$idx]}"
  # Make the caller path absolute if needed to handle the case where the script is run directly
  # from bazel-bin, with working directory a subdirectory of bazel-bin.
  if [[ "$raw_caller_path" =~ $_RLOCATION_ISABS_PATTERN ]]; then
    local -r caller_path="$raw_caller_path"
  else
    local -r caller_path="$(cd $(dirname "$raw_caller_path"); pwd)/$(basename "$raw_caller_path")"
  fi
  if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
    echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): caller's path is ($caller_path)"
  fi

  local rlocation_path=

  # If the runfiles manifest exists, search for an entry with target the caller's path.
  if [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
    # Escape $caller_path for use in the grep regex below. Also replace \ with / since the manifest
    # uses / as the path separator even on Windows.
    local -r normalized_caller_path="$(echo "$caller_path" | sed 's|\\\\*|/|g')"
    local -r escaped_caller_path="$(echo "$normalized_caller_path" | sed 's/[.[\*^$]/\\&/g')"
    rlocation_path=$(__runfiles_maybe_grep -m1 "^[^ ]* ${escaped_caller_path}$" "${RUNFILES_MANIFEST_FILE}" | cut -d ' ' -f 1)
    if [[ -z "$rlocation_path" ]]; then
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "ERROR[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) is not the target of an entry in the runfiles manifest ($RUNFILES_MANIFEST_FILE)"
      fi
      # The binary may also be run directly from bazel-bin or bazel-out.
      local -r repository=$(echo "$normalized_caller_path" | __runfiles_maybe_grep -E -o '(^|/)(bazel-out/[^/]+/bin|bazel-bin)/external/[^/]+/' | tail -1 | awk -F/ '{print $(NF-1)}')
      if [[ -n "$repository" ]]; then
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) lies in repository ($repository) (parsed exec path)"
        fi
        echo "$repository"
      else
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) lies in the main repository (parsed exec path)"
        fi
        echo ""
      fi
      return 1
    else
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) is the target of ($rlocation_path) in the runfiles manifest"
      fi
    fi
  fi

  # If the runfiles directory exists, check if the caller's path is of the form
  # $RUNFILES_DIR/rlocation_path and if so, set $rlocation_path.
  if [[ -z "$rlocation_path" && -d "${RUNFILES_DIR:-/dev/null}" ]]; then
    normalized_caller_path="$(echo "$caller_path" | sed 's|\\\\*|/|g')"
    normalized_dir="$(echo "${RUNFILES_DIR%[\/]}" | sed 's|\\\\*|/|g')"
    if [[ -n "${_RLOCATION_GREP_CASE_INSENSITIVE_ARGS}" ]]; then
      # When comparing file paths insensitively, also normalize the case of the prefixes.
      normalized_caller_path=$(echo "$normalized_caller_path" | tr '[:upper:]' '[:lower:]')
      normalized_dir=$(echo "$normalized_dir" | tr '[:upper:]' '[:lower:]')
    fi
    if [[ "$normalized_caller_path" == "$normalized_dir"/* ]]; then
      rlocation_path=${normalized_caller_path:${#normalized_dir}}
      rlocation_path=${rlocation_path:1}
    fi
    if [[ -z "$rlocation_path" ]]; then
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) does not lie under the runfiles directory ($normalized_dir)"
      fi
      # The only shell script that is not executed from the runfiles directory (if it is populated)
      # is the sh_binary entrypoint. Parse its path under the execroot, using the last match to
      # allow for nested execroots (e.g. in Bazel integration tests). The binary may also be run
      # directly from bazel-bin.
      local -r repository=$(echo "$normalized_caller_path" | __runfiles_maybe_grep -E -o '(^|/)(bazel-out/[^/]+/bin|bazel-bin)/external/[^/]+/' | tail -1 | awk -F/ '{print $(NF-1)}')
      if [[ -n "$repository" ]]; then
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) lies in repository ($repository) (parsed exec path)"
        fi
        echo "$repository"
      else
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($normalized_caller_path) lies in the main repository (parsed exec path)"
        fi
        echo ""
      fi
      return 0
    else
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($caller_path) has path ($rlocation_path) relative to the runfiles directory ($RUNFILES_DIR)"
      fi
    fi
  fi

  if [[ -z "$rlocation_path" ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "ERROR[runfiles.bash]: runfiles_current_repository($idx): cannot determine repository for ($caller_path) since neither the runfiles directory (${RUNFILES_DIR:-}) nor the runfiles manifest (${RUNFILES_MANIFEST_FILE:-}) exist"
    fi
    return 1
  fi

  if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
    echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($caller_path) corresponds to rlocation path ($rlocation_path)"
  fi
  # Normalize the rlocation path to be of the form repo/pkg/file.
  rlocation_path=${rlocation_path#_main/external/}
  rlocation_path=${rlocation_path#_main/../}
  local -r repository=$(echo "$rlocation_path" | cut -d / -f 1)
  if [[ "$repository" == _main ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($rlocation_path) lies in the main repository"
    fi
    echo ""
  else
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: runfiles_current_repository($idx): ($rlocation_path) lies in repository ($repository)"
    fi
    echo "$repository"
  fi
}
export -f runfiles_current_repository

function runfiles_rlocation_checked() {
  # FIXME: If the runfiles lookup fails, the exit code of this function is 0 if
  #  and only if the runfiles manifest exists. In particular, the exit code
  #  behavior is not consistent across platforms.
  if [[ -e "${RUNFILES_DIR:-/dev/null}/$1" ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: rlocation($1): found under RUNFILES_DIR ($RUNFILES_DIR), return"
    fi
    echo "${RUNFILES_DIR}/$1"
  elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "INFO[runfiles.bash]: rlocation($1): looking in RUNFILES_MANIFEST_FILE ($RUNFILES_MANIFEST_FILE)"
    fi
    # If the rlocation path contains a space or newline, it needs to be prefixed
    # with a space and spaces, newlines, and backslashes have to be escaped as
    # \s, \n, and \b.
    if [[ "$1" == *" "* || "$1" == *$'\n'* ]]; then
      local search_prefix=" $(echo -n "$1" | sed 's/\\/\\b/g; s/ /\\s/g')"
      search_prefix="${search_prefix//$'\n'/\\n}"
      local escaped=true
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: rlocation($1): using escaped search prefix ($search_prefix)"
      fi
    else
      local search_prefix="$1"
      local escaped=false
    fi
    # The extra space below is added because cut counts from 1.
    local trim_length=$(echo -n "$search_prefix  " | wc -c | tr -d ' ')
    # Escape the search prefix for use in the grep regex below *after*
    # determining the trim length.
    local result=$(__runfiles_maybe_grep -m1 "^$(echo -n "$search_prefix" | sed 's/[.[\*^$]/\\&/g') " "${RUNFILES_MANIFEST_FILE}" | cut -b "${trim_length}-")
    if [[ -z "$result" ]]; then
      # If path references a runfile that lies under a directory that itself
      # is a runfile, then only the directory is listed in the manifest. Look
      # up all prefixes of path in the manifest and append the relative path
      # from the prefix if there is a match.
      local prefix="$1"
      local prefix_result=
      local new_prefix=
      while true; do
        new_prefix="${prefix%/*}"
        [[ "$new_prefix" == "$prefix" ]] && break
        prefix="$new_prefix"
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: rlocation($1): looking for prefix ($prefix)"
        fi
        if [[ "$prefix" == *" "* || "$prefix" == *$'\n'* ]]; then
          search_prefix=" $(echo -n "$prefix" | sed 's/\\/\\b/g; s/ /\\s/g')"
          search_prefix="${search_prefix//$'\n'/\\n}"
          escaped=true
          if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
            echo >&2 "INFO[runfiles.bash]: rlocation($1): using escaped search prefix ($search_prefix)"
          fi
        else
          search_prefix="$prefix"
          escaped=false
        fi
        # The extra space below is added because cut counts from 1.
        trim_length=$(echo -n "$search_prefix  " | wc -c)
        prefix_result=$(__runfiles_maybe_grep -m1 "$(echo -n "$search_prefix" | sed 's/[.[\*^$]/\\&/g') " "${RUNFILES_MANIFEST_FILE}" | cut -b ${trim_length}-)
        if [[ "$escaped" = true ]]; then
          prefix_result="${prefix_result//\\n/$'\n'}"
          prefix_result="${prefix_result//\\b/\\}"
        fi
        [[ -z "$prefix_result" ]] && continue
        local -r candidate="${prefix_result}${1#"${prefix}"}"
        if [[ -e "$candidate" ]]; then
          if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
            echo >&2 "INFO[runfiles.bash]: rlocation($1): found in manifest as ($candidate) via prefix ($prefix)"
          fi
          echo "$candidate"
          return 0
        fi
        # At this point, the manifest lookup of prefix has been successful,
        # but the file at the relative path given by the suffix does not
        # exist. We do not continue the lookup with a shorter prefix for two
        # reasons:
        # 1. Manifests generated by Bazel never contain a path that is a
        #    prefix of another path.
        # 2. Runfiles libraries for other languages do not check for file
        #    existence and would have returned the non-existent path. It seems
        #    better to return no path rather than a potentially different,
        #    non-empty path.
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: rlocation($1): found in manifest as ($candidate) via prefix ($prefix), but file does not exist"
        fi
        break
      done
      if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
        echo >&2 "INFO[runfiles.bash]: rlocation($1): not found in manifest"
      fi
      echo ""
    else
      if [[ "$escaped" = true ]]; then
        result="${result//\\n/$'\n'}"
        result="${result//\\b/\\}"
      fi
      if [[ -e "$result" ]]; then
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: rlocation($1): found in manifest as ($result)"
        fi
        echo "$result"
      else
        if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
          echo >&2 "INFO[runfiles.bash]: rlocation($1): found in manifest as ($result), but file does not exist"
        fi
        echo ""
      fi
    fi
  else
    if [[ "${RUNFILES_LIB_DEBUG:-}" == 1 ]]; then
      echo >&2 "ERROR[runfiles.bash]: cannot look up runfile \"$1\" " \
               "(RUNFILES_DIR=\"${RUNFILES_DIR:-}\"," \
               "RUNFILES_MANIFEST_FILE=\"${RUNFILES_MANIFEST_FILE:-}\")"
    fi
    return 1
  fi
}
export -f runfiles_rlocation_checked

export RUNFILES_REPO_MAPPING=$(runfiles_rlocation_checked _repo_mapping 2> /dev/null)
