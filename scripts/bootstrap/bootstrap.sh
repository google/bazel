#!/bin/bash

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

# Use bazel to bootstrap various tools
# Configuration:
#   BAZEL: path to the bazel binary
#   EMBED_LABEL: the label to embed in tools using --embed_label (optional)
#   BAZELRC: the rc file to use

: ${BAZELRC:="/dev/null"}
: ${EMBED_LABEL:=""}
: ${SOURCE_DATE_EPOCH:=""}

EMBED_LABEL_ARG=()
if [ -n "${EMBED_LABEL}" ]; then
    EMBED_LABEL_ARG=(--stamp --embed_label "${EMBED_LABEL}")
fi

: ${JAVA_VERSION:="21"}

# TODO: remove `--repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0` once all dependencies are
#  mirrored. See https://github.com/bazelbuild/bazel/pull/19549 for more context.
_BAZEL_ARGS="--spawn_strategy=standalone \
      --nojava_header_compilation \
      --strategy=Javac=worker --worker_quit_after_build --ignore_unsupported_sandboxing \
      --compilation_mode=opt \
      --repository_cache=derived/repository_cache \
      --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0 \
      --extra_toolchains=//scripts/bootstrap:all \
      --extra_toolchains=@rules_python//python/runtime_env_toolchains:all \
      --enable_bzlmod \
      --check_direct_dependencies=error \
      --lockfile_mode=update \
      --override_repository=$(cat derived/maven/MAVEN_CANONICAL_REPO_NAME)=derived/maven \
      --java_runtime_version=${JAVA_VERSION} \
      --java_language_version=${JAVA_VERSION} \
      --tool_java_runtime_version=${JAVA_VERSION} \
      --tool_java_language_version=${JAVA_VERSION} \
      ${DIST_BOOTSTRAP_ARGS:-} \
      ${EXTRA_BAZEL_ARGS:-}"

cp scripts/bootstrap/BUILD.bootstrap scripts/bootstrap/BUILD

if [ -z "${BAZEL-}" ]; then
  function _run_bootstrapping_bazel() {
    local command=$1
    shift
    run_bazel_jar $command \
        ${_BAZEL_ARGS} --verbose_failures \
        --javacopt="-g" "${@}"
  }
else
  function _run_bootstrapping_bazel() {
    local command=$1
    shift
    ${BAZEL} --bazelrc=${BAZELRC} ${BAZEL_DIR_STARTUP_OPTIONS} $command \
        ${_BAZEL_ARGS} --verbose_failures \
        --javacopt="-g" "${@}"
  }
fi

function bazel_build() {
  _run_bootstrapping_bazel build "${EMBED_LABEL_ARG[@]}" "$@"
}

function get_bazel_bin_path() {
  _run_bootstrapping_bazel info "bazel-bin" || echo "bazel-bin"
}
