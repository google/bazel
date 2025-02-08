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
# Test //external mechanisms
#

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "${CURRENT_DIR}/remote_helpers.sh" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }

set_up() {
  bazel clean --expunge >& $TEST_log
  mkdir -p zoo
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@endangered//jar"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF
}

tear_down() {
  shutdown_server
  if [ -d "${TEST_TMPDIR}/server_dir" ]; then
    rm -fr "${TEST_TMPDIR}/server_dir"
  fi
}

function zip_up() {
  repo2_zip=$TEST_TMPDIR/fox.zip
  zip -0 -ry $repo2_zip MODULE.bazel fox
}

function tar_gz_up() {
  repo2_zip=$TEST_TMPDIR/fox.tar.gz
  tar czf $repo2_zip MODULE.bazel fox
}

function tar_xz_up() {
  repo2_zip=$TEST_TMPDIR/fox.tar.xz
  tar cJf $repo2_zip MODULE.bazel fox
}

# Test downloading a file from a repository.
# This creates a simple repository containing:
#
# repo/
#   WORKSPACE
#   zoo/
#     BUILD
#     female.sh
#
# And a .zip file, which contains:
#
# WORKSPACE
# fox/
#   BUILD
#   male
#   male_relative -> male
#   male_absolute -> /fox/male
function http_archive_helper() {
  zipper=$1
  local write_workspace
  [[ $# -gt 1 ]] && [[ "$2" = "nowrite" ]] && write_workspace=1 || write_workspace=0

  if [[ $write_workspace -eq 0 ]]; then
    # Create a zipped-up repository HTTP response.
    repo2=$TEST_TMPDIR/repo2
    rm -rf $repo2
    mkdir -p $repo2/fox
    cd $repo2
    setup_module_dot_bazel
    cat > fox/BUILD <<EOF
filegroup(
    name = "fox",
    srcs = ["male"],
    visibility = ["//visibility:public"],
)
EOF
    what_does_the_fox_say="Fraka-kaka-kaka-kaka-kow"
    cat > fox/male <<EOF
#!/bin/sh
echo $what_does_the_fox_say
EOF
    chmod +x fox/male
    touch -t 200403010021.42 fox/male
    ln -s male fox/male_relative
    ln -s /fox/male fox/male_absolute
    # Add some padding to the .zip to test that Bazel's download logic can
    # handle breaking a response into chunks.
    dd if=/dev/zero of=fox/padding bs=1024 count=10240 >& $TEST_log
    $zipper >& $TEST_log
    repo2_name=$(basename $repo2_zip)
    sha256=$(sha256sum $repo2_zip | cut -f 1 -d ' ')
  fi
  serve_file $repo2_zip

  cd ${WORKSPACE_DIR}
  if [[ $write_workspace = 0 ]]; then
    cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'endangered',
    url = 'http://127.0.0.1:$nc_port/$repo2_name',
    sha256 = '$sha256'
)
EOF

    cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

    cat > zoo/female.sh <<EOF
#!/bin/sh
../+_repo_rules+endangered/fox/male
EOF
    chmod +x zoo/female.sh
fi

  bazel run //zoo:breeding-program >& $TEST_log --show_progress_rate_limit=0 \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say

  base_external_path=bazel-out/../external/+_repo_rules+endangered/fox
  assert_files_same ${base_external_path}/male ${base_external_path}/male_relative
  assert_files_same ${base_external_path}/male ${base_external_path}/male_absolute
  case "${PLATFORM}" in
    darwin)
      ts="$(stat -f %m ${base_external_path}/male)"
      ;;
    *)
      ts="$(stat -c %Y ${base_external_path}/male)"
      ;;
  esac
  assert_equals "1078100502" "$ts"
}

function assert_files_same() {
  assert_contains "$(cat $1)" $2 && return 0
  echo "Expected these to be the same:"
  echo "---------------------------"
  cat $1
  echo "==========================="
  cat $2
  echo "---------------------------"
  return 1
}

function test_http_archive_zip() {
  http_archive_helper zip_up

  # Test with the extension
  serve_file $repo2_zip
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'endangered',
    url = 'http://127.0.0.1:$nc_port/bleh',
    sha256 = '$sha256',
    type = 'zip',
)
EOF
  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_http_archive_tgz() {
  http_archive_helper tar_gz_up
  bazel shutdown
  http_archive_helper tar_gz_up
}

function test_http_archive_tar_xz() {
  http_archive_helper tar_xz_up
}

function test_http_archive_tar_zstd() {
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'test_zstd_repo',
    url = 'file://$(rlocation io_bazel/src/test/shell/bazel/testdata/zstd_test_archive.tar.zst)',
    sha256 = '12b0116f2a3c804859438e102a8a1d5f494c108d1b026da9f6ca55fb5107c7e9',
    build_file_content = 'filegroup(name="x", srcs=glob(["*"]))',
)
EOF
  bazel build @test_zstd_repo//...

  base_external_path=bazel-out/../external/+_repo_rules+test_zstd_repo
  assert_contains "test content" "${base_external_path}/test_dir/test_file"
}

function test_http_archive_upper_case_sha() {
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'test_zstd_repo',
    url = 'file://$(rlocation io_bazel/src/test/shell/bazel/testdata/zstd_test_archive.tar.zst)',
    sha256 = '12B0116F2A3C804859438E102A8A1D5F494C108D1B026DA9F6CA55FB5107C7E9',
    build_file_content = 'filegroup(name="x", srcs=glob(["*"]))',
)
EOF
  bazel build @test_zstd_repo//...

  base_external_path=bazel-out/../external/+_repo_rules+test_zstd_repo
  assert_contains "test content" "${base_external_path}/test_dir/test_file"
}

function test_http_archive_no_server() {
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(name = 'endangered', url = 'http://bad.example/repo.zip',
    sha256 = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9826')
EOF

  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/sh
cat fox/male
EOF
  chmod +x zoo/female.sh

  bazel fetch //zoo:breeding-program >& $TEST_log && fail "Expected fetch to fail"
  expect_log "Unknown host: bad.example"
}

function test_http_archive_mismatched_sha256() {
  # Create a zipped-up repository HTTP response.
  repo2=$TEST_TMPDIR/repo2
  rm -rf $repo2
  mkdir -p $repo2
  cd $repo2
  setup_module_dot_bazel
  repo2_zip=$TEST_TMPDIR/fox.zip
  zip -r $repo2_zip MODULE.bazel
  serve_file $repo2_zip
  wrong_sha256=0000000000000000000000000000000000000000

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'endangered',
    url = 'http://127.0.0.1:$nc_port/repo.zip',
    sha256 = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9826',
)
EOF

  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/sh
cat fox/male
EOF
  chmod +x zoo/female.sh

  bazel fetch //zoo:breeding-program >& $TEST_log && echo "Expected fetch to fail"
  kill_nc
  expect_log "Checksum"
}

# Bazel should not re-download the .zip unless the user requests it or the
# WORKSPACE file changes, so doing a BUILD where the "wrong" .zip is available
# on the server should work if the correct .zip is already available.
function test_sha256_caching() {
  # Download with correct sha256.
  http_archive_helper zip_up

  echo 'not a zip file' > "${TEST_TMPDIR}/wrong.zip"
  serve_file "${TEST_TMPDIR}/wrong.zip"

  bazel fetch //zoo:breeding-program || fail "Fetch failed"
  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_cached_across_server_restart() {
  http_archive_helper zip_up
  local marker_file=$(bazel info output_base)/external/\@+_repo_rules+endangered.marker
  echo "<MARKER>"
  cat "${marker_file}"
  echo "</MARKER>"
  bazel shutdown >& $TEST_log || fail "Couldn't shut down"
  bazel run //zoo:breeding-program >& $TEST_log --show_progress_rate_limit=0 \
    || echo "Expected build/run to succeed"
  echo "<MARKER>"
  cat "${marker_file}"
  echo "</MARKER>"
  expect_log $what_does_the_fox_say
  expect_not_log "Downloading from"
}

# Tests downloading a jar and using it as a Java dependency.
function test_jar_download() {
  serve_jar

  cat > $(setup_module_dot_bazel) <<EOF
http_jar = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")
http_jar(name = 'endangered', url = 'http://127.0.0.1:$nc_port/lib.jar',
         sha256='$sha256', downloaded_file_name="foo.jar")
EOF

  mkdir -p zoo
  cat > zoo/BUILD <<EOF
java_binary(
    name = "ball-pit",
    srcs = ["BallPit.java"],
    main_class = "BallPit",
    deps = ["@endangered//jar"],
)
EOF

  cat > zoo/BallPit.java <<EOF
import carnivore.Mongoose;

public class BallPit {
    public static void main(String args[]) {
        Mongoose.frolic();
    }
}
EOF

  bazel run //zoo:ball-pit >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
  output_base=$(bazel info output_base)
  jar_dir=$output_base/external/+_repo_rules+endangered/jar
  [[ -f ${jar_dir}/foo.jar ]] || fail "${jar_dir}/foo.jar not found"
}

function test_http_to_https_redirect() {
  serve_redirect https://127.0.0.1:123456789/bad-port-shouldnt-work

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(
    name = 'toto',
    urls = ['http://127.0.0.1:$redirect_port/toto'],
    sha256 = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9826'
)
EOF
  bazel build @toto//file &> $TEST_log && fail "Expected run to fail"
  kill_nc
  # Observes that we tried to follow redirect, but failed due to ridiculous
  # port.
  expect_log "port out of range"
}

function test_http_404() {
  serve_not_found "Help, I'm lost!"

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(
    name = 'toto',
    urls = ['http://127.0.0.1:$nc_port/toto'],
    sha256 = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9826'
)
EOF
  bazel build @toto//file &> $TEST_log && fail "Expected run to fail"
  kill_nc
  expect_log "404 Not Found"
}

function test_deferred_download_unwaited() {
  cat >> $(setup_module_dot_bazel) <<'EOF'
hang = use_repo_rule("//:hang.bzl", "hang")

hang(name="hang")
EOF

  cat > hang.bzl <<'EOF'
def _hang_impl(rctx):
  hangs = rctx.download(
    # This URL will definitely not work, but that's OK -- we don't need a
    # successful request for this test
    url = "https://127.0.0.1:0/does_not_exist",
    output = "does_not_exist",
    block = False)

hang = repository_rule(implementation = _hang_impl)
EOF

  touch BUILD
  bazel query @hang//:all >& $TEST_log && fail "Bazel unexpectedly succeeded"
  expect_log "Pending asynchronous work"
}

function test_deferred_download_two_parallel_downloads() {
  local server_dir="${TEST_TMPDIR}/server_dir"
  local gate_socket="${server_dir}/gate_socket"
  local served_apple="APPLE"
  local served_banana="BANANA"
  local apple_sha256=$(echo "${served_apple}" | sha256sum | cut -f1 -d' ')
  local banana_sha256=$(echo "${served_banana}" | sha256sum | cut -f1 -d' ')

  mkdir -p "${server_dir}"

  mkfifo "${server_dir}/apple" || fail "cannot mkfifo"
  mkfifo "${server_dir}/banana" || fail "cannot mkfifo"
  mkfifo $gate_socket || fail "cannot mkfifo"

  startup_server "${server_dir}"

  cat > $(setup_module_dot_bazel) <<'EOF'
defer = use_repo_rule("//:defer.bzl", "defer")
defer(name="defer")
EOF

  cat > defer.bzl <<EOF
def _defer_impl(rctx):
  requests = [
    ["apple", "${apple_sha256}"],
    ["banana", "${banana_sha256}"]
  ]
  pending = [
    rctx.download(
        url = "http://127.0.0.1:${fileserver_port}/" + name,
        sha256 = sha256,
        output = name,
        block = False)
    for name, sha256 in requests]

  # Tell the rest of the test to unblock the HTTP server
  rctx.execute(["/bin/sh", "-c", "echo ok > ${server_dir}/gate_socket"])

  # Wait until the requess are done
  [p.wait() for p in pending]

  rctx.file("WORKSPACE", "")
  rctx.file("BUILD", "filegroup(name='f', srcs=glob(['**']))")

defer = repository_rule(implementation = _defer_impl)
EOF

  touch BUILD

  # Start Bazel
  bazel query @defer//:all >& $TEST_log &
  local bazel_pid=$!

  # Wait until the .download() calls return
  cat "${server_dir}/gate_socket"

  # Tell the test server the strings it should serve. In parallel because the
  # test server apparently cannot serve two HTTP requests in parallel, so if we
  # wait for request A to be completely served while unblocking request B, it is
  # possible that the test server wants to serve request B first, which is a
  # deadlock.
  echo "${served_apple}" > "${server_dir}/apple" &
  local apple_pid=$!
  echo "${served_banana}" > "${server_dir}/banana" &
  local banana_pid=$!
  wait $apple_pid
  wait $banana_pid

  # Wait until Bazel returns
  wait "${bazel_pid}" || fail "Bazel failed"
  expect_log "@defer//:f"
}

function test_deferred_download_error() {
  cat > $(setup_module_dot_bazel) <<'EOF'
defer = use_repo_rule("//:defer.bzl", "defer")
defer(name="defer")
EOF

  cat > defer.bzl <<EOF
def _defer_impl(rctx):
  deferred = rctx.download(
    url = "https://127.0.0.1:0/doesnotexist",
    output = "deferred",
    block = False)

  deferred.wait()
  print("survived wait")
  rctx.file("BUILD", "filegroup(name='f', srcs=glob(['**']))")

defer = repository_rule(implementation = _defer_impl)
EOF

  touch BUILD

  # Start Bazel
  bazel query @defer//:all >& $TEST_log && fail "Bazel unexpectedly succeeded"
  expect_log "Error downloading.*doesnotexist"
  expect_not_log "survived wait"
}

function test_deferred_download_smoke() {
  local server_dir="${TEST_TMPDIR}/server_dir"
  local served_socket="${server_dir}/served_socket"
  local gate_socket="${server_dir}/gate_socket"
  local served_string="DEFERRED"
  local served_sha256=$(echo "${served_string}" | sha256sum | cut -f1 -d' ')

  mkdir -p "${server_dir}"

  mkfifo $served_socket || fail "cannot mkfifo"
  mkfifo $gate_socket || fail "cannot mkfifo"

  startup_server "${server_dir}"

  cat > $(setup_module_dot_bazel) <<'EOF'
defer = use_repo_rule("//:defer.bzl", "defer")
defer(name="defer")
EOF

  cat > defer.bzl <<EOF
def _defer_impl(rctx):
  deferred = rctx.download(
    url = "http://127.0.0.1:${fileserver_port}/served_socket",
    sha256 = "${served_sha256}",
    output = "deferred",
    block = False)

  # Tell the rest of the test to unblock the HTTP server
  rctx.execute(["/bin/sh", "-c", "echo ok > ${server_dir}/gate_socket"])
  deferred.wait()
  rctx.file("WORKSPACE", "")
  rctx.file("BUILD", "filegroup(name='f', srcs=glob(['**']))")

defer = repository_rule(implementation = _defer_impl)
EOF

  touch BUILD

  # Start Bazel
  bazel query @defer//:all-targets >& $TEST_log &
  local bazel_pid=$!

  # Wait until the .download() call returns
  cat "${server_dir}/gate_socket"

  # Tell the test server the string it should serve
  echo "${served_string}" > "${server_dir}/served_socket"

  # Wait until Bazel returns
  wait "${bazel_pid}" || fail "Bazel failed"
  expect_log "@defer//:deferred"
}

# Tests downloading a file and using it as a dependency.
function test_http_download() {
  local test_file=$TEST_TMPDIR/toto
  cat > $test_file <<EOF
#!/bin/sh
echo "Tra-la!"
EOF
  local sha256=$(sha256sum $test_file | cut -f 1 -d ' ')
  serve_file $test_file
  cd ${WORKSPACE_DIR}

  cat > $(setup_module_dot_bazel) <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(name = 'toto', urls = ['http://127.0.0.1:$nc_port/toto'],
    sha256 = '$sha256', executable = True)
EOF

  mkdir -p test
  cat > test/BUILD <<'EOF'
sh_binary(
    name = "test",
    srcs = ["test.sh"],
    data = ["@toto//file"],
)

genrule(
  name = "test_sh",
  outs = ["test.sh"],
  srcs = ["@toto//file"],
  cmd = "echo '#!/bin/sh' > $@ && echo $(rootpath @toto//file) >> $@",
)
EOF

  bazel run //test >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
}

function test_http_timeout() {
  serve_timeout

  cd ${WORKSPACE_DIR}
  cat > $(setup_module_dot_bazel) <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(
    name = 'toto',
    urls = ['http://127.0.0.1:$nc_port/toto'],
    sha256 = "01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b",
)
EOF
  date
  bazel build --http_timeout_scaling=0.03 @toto//file > $TEST_log 2>&1 \
      && fail "Expected failure" || :
  date
  kill_nc

  expect_log '[Tt]imed\? \?out'
  expect_not_log "interrupted"
}

# Tests downloading a file with a redirect.
function test_http_redirect() {
  local test_file=$TEST_TMPDIR/toto
  echo "Tra-la!" >$test_file
  local sha256=$(sha256sum $test_file | cut -f 1 -d ' ')
  serve_file $test_file
  cd ${WORKSPACE_DIR}
  serve_redirect "http://127.0.0.1:$nc_port/toto"

  cat > $(setup_module_dot_bazel) <<EOF
http_file = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")
http_file(name = 'toto', urls = ['http://127.0.0.1:$redirect_port/toto'],
    sha256 = '$sha256')
EOF

  mkdir -p test
  cat > test/BUILD <<'EOF'
sh_binary(
    name = "test",
    srcs = ["test.sh"],
    data = ["@toto//file"],
)

genrule(
  name = "test_sh",
  outs = ["test.sh"],
  srcs = ["@toto//file"],
  cmd = "echo '#!/bin/sh' > $@ && echo cat $(rootpath @toto//file) >> $@",
)
EOF

  bazel run //test >& $TEST_log || echo "Expected run to succeed"
  kill_nc
  expect_log "Tra-la!"
}

function test_empty_file() {
  rm -f empty
  touch empty
  tar czf x.tar.gz empty
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    build_file = "@//:x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
exports_files(["empty"])
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "rule",
  srcs = ["@x//:empty"],
  outs = ["timestamp"],
  cmd = "date > $@",
)
EOF

  bazel build //:rule || fail "Build failed"
  bazel shutdown || fail "Shutdown failed"
  cp bazel-genfiles/timestamp first_timestamp || fail "No output"
  sleep 1 # Make sure we're on a different second to avoid false passes.
  bazel build //:rule || fail "Build failed"
  diff bazel-genfiles/timestamp first_timestamp || fail "Output was built again"
}

function test_new_remote_repo_with_build_file() {
  do_new_remote_repo_test "build_file"
}

function test_new_remote_repo_with_build_file_content() {
  do_new_remote_repo_test "build_file_content"
}

function test_new_remote_repo_with_workspace_file() {
  do_new_remote_repo_test "workspace_file"
}

function test_new_remote_repo_with_workspace_file_content() {
  do_new_remote_repo_test "workspace_file_content"
}

function do_new_remote_repo_test() {
  # Create a zipped-up repository HTTP response.
  local repo2=$TEST_TMPDIR/repo2
  rm -rf $repo2
  mkdir -p $repo2/fox
  cd $repo2
  local what_does_the_fox_say="Fraka-kaka-kaka-kaka-kow"
  echo $what_does_the_fox_say > fox/male
  local repo2_zip=$TEST_TMPDIR/fox.zip
  rm -f $repo2_zip
  zip -r $repo2_zip fox
  local sha256=$(sha256sum $repo2_zip | cut -f 1 -d ' ')
  serve_file $repo2_zip

  cd ${WORKSPACE_DIR}

  # Create the build file for the http archive based on the requested attr style.
  local build_file_attr=""
  local workspace_file_attr=""

  local build_file_content="
filegroup(
    name = \"fox\",
    srcs = [\"fox/male\"],
    visibility = [\"//visibility:public\"],
)
  "

  if [ "$1" = "build_file" ] ; then
    touch BUILD
    echo ${build_file_content} > fox.BUILD
    build_file_attr="build_file = '@//:fox.BUILD'"
  else
    build_file_attr="build_file_content=\"\"\"${build_file_content}\"\"\""
  fi

  if [ "$1" = "workspace_file" ]; then
    touch BUILD
    cat > fox.WORKSPACE <<EOF
workspace(name="endangered-fox")
EOF
    workspace_file_attr="workspace_file = '@//:fox.WORKSPACE'"
  elif [ "$1" = "workspace_file_content" ]; then
    workspace_file_attr="workspace_file_content = 'workspace(name=\"endangered-fox\")'"
  fi

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = 'endangered',
    url = 'http://127.0.0.1:$nc_port/repo.zip',
    sha256 = '$sha256',
    ${build_file_attr},
    ${workspace_file_attr}
)
EOF

  mkdir -p zoo
  cat > zoo/BUILD <<EOF
sh_binary(
    name = "breeding-program",
    srcs = ["female.sh"],
    data = ["@endangered//:fox"],
)
EOF

  cat > zoo/female.sh <<EOF
#!/bin/sh
cat ../+_repo_rules+endangered/fox/male
EOF
  chmod +x zoo/female.sh

  bazel run //zoo:breeding-program >& $TEST_log \
    || echo "Expected build/run to succeed"
  kill_nc
  expect_log $what_does_the_fox_say
}

function test_fetch() {
  serve_jar

  cat > $(setup_module_dot_bazel) <<EOF
ext = use_extension("//:ext.bzl", "ext")
use_repo(ext, "endangered")
EOF

  touch BUILD
  cat > ext.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:jvm.bzl", "jvm_maven_import_external")

def repo():
  jvm_maven_import_external(
      name = 'endangered',
      artifact = "com.example.carnivore:carnivore:1.23",
      server_urls = ['http://127.0.0.1:$nc_port/'],
      artifact_sha256 = '$sha256',
  )

ext = module_extension(implementation = lambda ctx: repo())
EOF

  output_base=$(bazel info output_base)
  external_dir=$output_base/external
  needle=endangered
  [[ -d $external_dir/$needle ]] \
      && fail "$needle already exists in $external_dir" || true
  bazel fetch //zoo:ball-pit >& $TEST_log || fail "Fetch failed"
  [[ $(ls $external_dir | grep $needle) ]] || fail "$needle not added to $external_dir"

  bazel query --output=build --nohost_deps --noimplicit_deps 'deps(//zoo:ball-pit)' >& $TEST_log \
    || fail "bazel query failed"
  expect_log "maven_coordinates=com.example.carnivore:carnivore:1.23"

  # Rerun fetch while nc isn't serving anything to make sure the fetched result
  # is cached.
  bazel fetch //zoo:ball-pit >& $TEST_log || fail "Incremental fetch failed"

  # Make sure fetch isn't needed after a bazel restart.
  bazel shutdown
  bazel build //zoo:ball-pit >& $TEST_log || fail "Fetch shouldn't be required"

  # But it is required after a clean.
  bazel clean --expunge || fail "Clean failed"
  bazel build --fetch=false //zoo:ball-pit >& $TEST_log && fail "Expected build to fail"
  expect_log "fetching repositories is disabled"
}

function test_prefix_stripping_tar_gz() {
  mkdir -p x/y/z
  echo "abc" > x/y/z/w
  tar czf x.tar.gz x
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    strip_prefix = "x/y/z",
    build_file = "@//:x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF
  touch BUILD

  bazel build @x//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
}

function test_prefix_stripping_zip() {
  mkdir -p x/y/z
  echo "abc" > x/y/z/w
  zip -r x x
  local sha256=$(sha256sum x.zip | cut -f 1 -d ' ')
  serve_file x.zip

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.zip",
    sha256 = "$sha256",
    strip_prefix = "x/y/z",
    build_file = "@//:x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF
  touch BUILD

  bazel build @x//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
}

function test_prefix_stripping_existing_repo() {
  mkdir -p x/y/z
  touch x/y/z/WORKSPACE
  echo "abc" > x/y/z/w
  cat > x/y/z/BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF
  zip -r x x
  local sha256=$(sha256sum x.zip | cut -f 1 -d ' ')
  serve_file x.zip

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.zip",
    sha256 = "$sha256",
    strip_prefix = "x/y/z",
)
EOF

  bazel build @x//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
}

function test_adding_prefix_zip() {
  mkdir -p z
  echo "abc" > z/w
  zip -r z z
  local sha256=$(sha256sum z.zip | cut -f 1 -d ' ')
  serve_file z.zip

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "ws",
    url = "http://127.0.0.1:$nc_port/z.zip",
    sha256 = "$sha256",
    add_prefix = "x/y",
    build_file = "@//:ws.BUILD",
)
EOF
  cat > ws.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["x/y/z/w"],
)
EOF
  touch BUILD

  bazel build @ws//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+ws/catter.out
}

function test_adding_and_stripping_prefix_zip() {
  mkdir -p z
  echo "abc" > z/w
  zip -r z z
  local sha256=$(sha256sum z.zip | cut -f 1 -d ' ')
  serve_file z.zip

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "ws",
    url = "http://127.0.0.1:$nc_port/z.zip",
    sha256 = "$sha256",
    strip_prefix = "z",
    add_prefix = "x",
    build_file = "@//:ws.BUILD",
)
EOF
  cat > ws.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["x/w"],
)
EOF
  touch BUILD

  bazel build @ws//:catter &> $TEST_log || fail "Build failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+ws/catter.out
}

function test_moving_build_file() {
  echo "abc" > w
  tar czf x.tar.gz w
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    build_file = "@//:x.BUILD",
)
EOF
  touch BUILD
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  bazel build @x//:catter &> $TEST_log || fail "Build 1 failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
  mv x.BUILD x.BUILD.new || fail "Moving x.BUILD failed"
  sed 's/x.BUILD/x.BUILD.new/g' MODULE.bazel > MODULE.bazel.tmp || \
    fail "Editing MODULE.bazel failed"
  mv MODULE.bazel.tmp MODULE.bazel
  bazel build @x//:catter &> $TEST_log || fail "Build 2 failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
}

function test_changing_build_file() {
  echo "abc" > w
  echo "def" > w.new
  echo "I'm a build file" > BUILD
  tar czf x.tar.gz w w.new BUILD
  local sha256=$(sha256sum x.tar.gz | cut -f 1 -d ' ')
  serve_file x.tar.gz

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "x",
    url = "http://127.0.0.1:$nc_port/x.tar.gz",
    sha256 = "$sha256",
    build_file = "@//:x.BUILD",
)
EOF
  cat > x.BUILD <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w"],
)
EOF

  cat > x.BUILD.new <<EOF
genrule(
    name = "catter",
    cmd = "cat \$< > \$@",
    outs = ["catter.out"],
    srcs = ["w.new"],
)
EOF

  bazel build @x//:catter || fail "Build 1 failed"
  assert_contains "abc" bazel-genfiles/external/+_repo_rules+x/catter.out
  sed 's/x.BUILD/x.BUILD.new/g' MODULE.bazel > MODULE.bazel.tmp || \
    fail "Editing MODULE.bazel failed"
  mv MODULE.bazel.tmp MODULE.bazel
  bazel build @x//:catter &> $TEST_log || fail "Build 2 failed"
  assert_contains "def" bazel-genfiles/external/+_repo_rules+x/catter.out
}

function test_flip_flopping() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  touch BUILD foo
  zip -r repo.zip *
  sha256=$(sha256sum repo.zip | head -c 64)
  startup_server $PWD
  # Make the remote repo and local repo slightly different.
  rm foo
  touch bar
  cd -

  cat > local_ws <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
    name = "repo",
    path = "$REPO_PATH",
)
EOF
  cat > remote_ws <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
    sha256 = "$sha256",
)
EOF
  external_dir=$(bazel info output_base)/external
  for i in $(seq 1 3); do
    cp local_ws MODULE.bazel
    bazel build @repo//:all &> $TEST_log || fail "Build failed"
    test -L "$external_dir/+_repo_rules+repo" || fail "creating local symlink failed"
    test -a "$external_dir/+_repo_rules+repo/bar" || fail "bar not found"
    cp remote_ws MODULE.bazel
    bazel build @repo//:all &> $TEST_log || fail "Build failed"
    test -d "$external_dir/+_repo_rules+repo" || fail "creating remote repo failed"
    test -a "$external_dir/+_repo_rules+repo/foo" || fail "foo not found"
  done

  shutdown_server
}

function test_sha256_weird() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  zip -r repo.zip *
  startup_server $PWD
  cd -

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    sha256 = "a random string",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
)
EOF
  bazel build @repo//... &> $TEST_log && fail "Expected to fail"
  expect_log "[Ii]nvalid SHA-256 checksum"
  shutdown_server
}

function test_sha256_incorrect() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  zip -r repo.zip *
  startup_server $PWD
  cd -

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    sha256 = "61a6f762aaf60652cbf332879b8dcc2cfd81be2129a061da957d039eae77f0b0",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
)
EOF
  bazel build @repo//... &> $TEST_log 2>&1 && fail "Expected to fail"
  expect_log "Error downloading \\[http://127.0.0.1:$fileserver_port/repo.zip\\] to"
  expect_log "but wanted 61a6f762aaf60652cbf332879b8dcc2cfd81be2129a061da957d039eae77f0b0"
  shutdown_server
}

function test_integrity_correct() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  touch BUILD
  zip -r repo.zip *
  integrity="sha256-$(cat repo.zip | openssl dgst -sha256 -binary | openssl base64 -A)"
  startup_server $PWD
  cd -

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    integrity = "$integrity",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
)
EOF
  bazel build @repo//... || fail "Expected integrity check to succeed"
  shutdown_server
}

function test_integrity_weird() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  touch BUILD
  zip -r repo.zip *
  startup_server $PWD
  cd -

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    integrity = "a random string",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
)
EOF
  bazel build @repo//... &> $TEST_log 2>&1 && fail "Expected to fail"
  expect_log "Unsupported checksum algorithm: 'a random string'"
  shutdown_server
}

function test_integrity_incorrect() {
  REPO_PATH=$TEST_TMPDIR/repo
  mkdir -p "$REPO_PATH"
  cd "$REPO_PATH"
  setup_module_dot_bazel
  touch BUILD
  zip -r repo.zip *
  integrity="sha256-$(cat repo.zip | openssl dgst -sha256 -binary | openssl base64 -A)"
  startup_server $PWD
  cd -

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    integrity = "sha256-Yab3Yqr2BlLL8zKHm43MLP2BviEpoGHalX0Dnq538LA=",
    url = "http://127.0.0.1:$fileserver_port/repo.zip",
)
EOF
  bazel build @repo//... &> $TEST_log 2>&1 && fail "Expected to fail"
  expect_log "Error downloading \\[http://127.0.0.1:$fileserver_port/repo.zip\\] to"
  # Bazel translates the integrity value back to the sha256 checksum.
  expect_log "Checksum was $integrity but wanted sha256-Yab3Yqr2BlLL8zKHm43MLP2BviEpoGHalX0Dnq538LA="
  shutdown_server
}

function test_integrity_ill_formed_base64() {
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "repo",
    integrity = "sha256-Yab3Yqr2BlLL8zKHm43MLP2BviEpoGHalX0Dnq538L=",
    url = "file:///dev/null",
)
EOF
  bazel build @repo//... &> $TEST_log 2>&1 && fail "Expected to fail"
  expect_log "Invalid base64 'Yab3Yqr2BlLL8zKHm43MLP2BviEpoGHalX0Dnq538L='"
  shutdown_server
}

function test_same_name() {
  mkdir ext
  echo foo> ext/foo
  EXTREPODIR=`pwd`
  zip ext.zip ext/*
  rm -rf ext

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  url="file://${EXTREPODIR}/ext.zip",
  build_file_content="exports_files([\"foo\"])",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "localfoo",
  srcs = ["@ext//:foo"],
  outs = ["foo"],
  cmd = "cp $< $@",
)
EOF

  bazel build //:localfoo \
    || fail 'Expected @ext//:foo and //:foo not to conflict'
}

function test_missing_build() {
  mkdir ext
  echo foo> ext/foo
  EXTREPODIR=`pwd`
  rm -f ext.zip
  zip ext.zip ext/*
  rm -rf ext

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${EXTREPODIR}/ext.zip"],
  build_file="@//:ext.BUILD",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "localfoo",
  srcs = ["@ext//:foo"],
  outs = ["foo"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:localfoo && fail 'Expected failure' || :

  cat > ext.BUILD <<'EOF'
exports_files(["foo"])
EOF

  bazel build //:localfoo || fail 'Expected success'

  # Changes to the BUILD file in the external repository should be tracked
  rm -f ext.BUILD && touch ext.BUILD

  bazel build //:localfoo && fail 'Expected failure' || :

  # Verify that we don't call out unconditionally
  cat > ext.BUILD <<'EOF'
exports_files(["foo"])
EOF
  bazel build //:localfoo || fail 'Expected success'
  rm -f "${EXTREPODIR}/ext.zip"
  bazel build //:localfoo || fail 'Expected success'
}


function test_inherit_build() {
  # Verify that http_archive can use a BUILD file shipped with the
  # external archive.
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="hello",
  outs=["hello.txt"],
  cmd="echo Hello World > $@",
)
EOF
  EXTREPODIR=`pwd`
  rm -f ext.zip
  zip ext.zip ext/*
  rm -rf ext

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${EXTREPODIR}/ext.zip"],
)
EOF

  bazel build '@ext//:hello' || fail "expected success"
}

function test_failing_fetch_with_keep_going() {
  setup_module_dot_bazel
  cat > BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

cc_binary(
    name = "hello-world",
    srcs = ["hello-world.cc"],
    deps = ["@fake//:fake"],
)
EOF
  touch hello-world.cc

  bazel fetch --keep_going //... >& $TEST_log && fail "Expected to fail" || true
}


function test_query_cached() {
  # Verify that external repositories are cached after being used once.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
)
genrule(
  name="bar",
  outs=["bar.txt"],
  srcs=[":foo"],
  cmd="cp $< $@",
)
EOF
  EXTREPODIR=`pwd`
  rm -f ext.zip
  zip ext.zip ext/*
  rm -rf ext

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${EXTREPODIR}/ext.zip"],
)
EOF
  bazel build '@ext//:bar' || fail "expected success"

  # Simulate going offline by removing the external archive
  rm -f "${EXTREPODIR}/ext.zip"
  bazel query 'deps("@ext//:bar")' > "${TEST_log}" 2>&1 \
    || fail "expected success"
  expect_log '@ext//:foo'
  bazel shutdown
  bazel query 'deps("@ext//:bar")' > "${TEST_log}" 2>&1 \
    || fail "expected success"
  expect_log '@ext//:foo'
}

function test_repository_cache_relative_path() {
  # Verify that --repository_cache works for query and caches soly
  # based on the predicted hash, for a repository-cache location given as path
  # relative to the WORKSPACE

  add_to_bazelrc "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0"

  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
)
genrule(
  name="bar",
  outs=["bar.txt"],
  srcs=[":foo"],
  cmd="cp $< $@",
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  rm -rf cache
  mkdir cache

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF
  # Use the external repository once to make sure it is cached.
  bazel build --repository_cache="../cache" '@ext//:bar' \
      || fail "expected success"

  # Now "go offline" and clean local resources.
  rm -f "${WRKDIR}/ext.zip"
  bazel clean --expunge
  bazel query 'deps("@ext//:bar")' && fail "Couldn't clean local cache" || :

  # The value should still be available from the repository cache
  bazel query 'deps("@ext//:bar")' \
        --repository_cache="../cache" > "${TEST_log}" \
      || fail "Expected success"
  expect_log '@ext//:foo'

  # Clean again.
  bazel clean --expunge
  # Even with a different source URL, the cache should be consulted.

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/invalidpath/othername.zip"],
  sha256="${sha256}",
)
EOF
  bazel query 'deps("@ext//:bar")' \
        --repository_cache="../cache" > "${TEST_log}" \
      || fail "Expected success"
  expect_log '@ext//:foo'
}

test_default_cache()
{
  # Verify that the default cache works for query and caches soly
  # based on the predicted hash, for a repository-cache location given as path
  # relative to the WORKSPACE

  add_to_bazelrc "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0"

  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
)
genrule(
  name="bar",
  outs=["bar.txt"],
  srcs=[":foo"],
  cmd="cp $< $@",
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF
  # Use the external repository once to make sure it is cached.
  bazel build '@ext//:bar' || fail "expected success"

  # Now "go offline" and clean local resources.
  rm -f "${WRKDIR}/ext.zip"
  bazel clean --expunge

  # The value should still be available from the repository cache
  bazel query 'deps("@ext//:bar")' > "${TEST_log}" || fail "Expected success"
  expect_log '@ext//:foo'

  # Clean again.
  bazel clean --expunge
  # Even with a different source URL, the cache should be consulted.

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/invalidpath/othername.zip"],
  sha256="${sha256}",
)
EOF
  bazel query 'deps("@ext//:bar")' > "${TEST_log}" || fail "Expected success"
  expect_log '@ext//:foo'
}

function test_repository_cache_default() {
  # Verify that the repository cache is enabled by default.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  TOPDIR=`pwd`
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${TOPDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF
  # Use the external repository once to make sure it is cached.
  bazel build '@ext//:foo' || fail "expected success"

  # Now "go offline" and clean local resources.
  rm -f "${TOPDIR}/ext.zip"
  bazel clean --expunge

  # Still, the file should be cached.
  bazel build '@ext//:foo' || fail "expected success"
}

function test_cache_split() {
  # Verify that the canonical_id is honored to logically split the cache

  add_to_bazelrc "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0"

  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  canonical_id = "canonical_ext_zip",
  sha256="${sha256}",
)
EOF
  # Use the external repository once to make sure it is cached.
  bazel build '@ext//:foo' || fail "expected success"

  # Now "go offline" and clean local resources.
  rm -f "${WRKDIR}/ext.zip"
  bazel clean --expunge

  # Still, the file should be cached.
  bazel build '@ext//:foo' || fail "expected success"

  # Now, change the canonical_id
  ed MODULE.bazel <<'EOF'
/canonical_id
s|"|"modified_
w
q
EOF
  # The build should fail now
  bazel build '@ext//:foo' && fail "should not have a cache hit now" || :

  # However, removing the canonical_id, we should get a cache hit again
  ed MODULE.bazel <<'EOF'
/canonical_id
d
w
q
EOF
  bazel build '@ext//:foo' || fail "expected success"
}

function test_cache_disable {
  # Verify that the repository cache can be disabled.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  TOPDIR=`pwd`
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${TOPDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF

  # Prime the repository cache.
  bazel build '@ext//:foo' || fail "expected success"

  # Use `--repository_cache` with no path to explicitly disable repository cache
  bazel build --repository_cache= '@ext//:foo' || fail "expected success"

  # make sure, the empty path is not interpreted relative to `pwd`; i.e., we do
  # not expect any new directories generated in the workspace, in particular
  # none named conent_addressable, which is the directory where the cache puts
  # its artifacts into.
  ls -al | grep content_addressable \
      && fail "Should not interpret empty path as cache directly in the work space" || :

  # Now "go offline" and clean local resources.
  rm -f "${TOPDIR}/ext.zip"
  bazel clean --expunge

  # Do a noop build with the cache enabled to ensure the cache can be disabled
  # after the server starts.
  bazel build

  # The build should fail since we are not using the repository cache, but the
  # original file can no longer be "downloaded".
  bazel build --repository_cache= '@ext//:foo' \
      && fail "Should fail for lack of fetchable archive" || :
}

function test_repository_cache() {
  # Verify that --repository_cache works for query and caches soly
  # based on the predicted hash.

  add_to_bazelrc "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0"

  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
)
genrule(
  name="bar",
  outs=["bar.txt"],
  srcs=[":foo"],
  cmd="cp $< $@",
)
EOF
  TOPDIR=`pwd`
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  rm -rf cache
  mkdir cache

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${TOPDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF
  # Use the external repository once to make sure it is cached.
  bazel build --repository_cache="${TOPDIR}/cache}" '@ext//:bar' \
      || fail "expected success"

  # Now "go offline" and clean local resources.
  rm -f "${TOPDIR}/ext.zip"
  bazel clean --expunge
  bazel query 'deps("@ext//:bar")' && fail "Couldn't clean local cache" || :

  # The value should still be available from the repository cache
  bazel query 'deps("@ext//:bar")' \
        --repository_cache="${TOPDIR}/cache}" > "${TEST_log}" \
      || fail "Expected success"
  expect_log '@ext//:foo'

  # Clean again.
  bazel clean --expunge
  # Even with a different source URL, the cache should be consulted.

  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/invalidpath/othername.zip"],
  sha256="${sha256}",
)
EOF
  bazel query 'deps("@ext//:bar")' \
        --repository_cache="${TOPDIR}/cache}" > "${TEST_log}" \
      || fail "Expected success"
  expect_log '@ext//:foo'
}

function test_cache_probe() {
  # Verify that repository rules are able to probe for cache hits.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name = "ext",
  outs = ["ext.txt"],
  cmd = "echo True external file > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
probe = use_repo_rule("//:rule.bzl", "probe")

http_archive(
  name = "ext",
  url = "file://${WRKDIR}/ext.zip",
  strip_prefix="ext",
)

probe(
  name = "probe",
  sha256 = "${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  outs = ["it.txt"],
  srcs = ["@probe//:ext"],
  cmd = "cp $< $@",
)
EOF
  cat > rule.bzl <<'EOF'
def _rule_impl(ctx):
  result = ctx.download_and_extract(
    url = [],
    type = "zip",
    strip_prefix="ext",
    sha256 = ctx.attr.sha256,
    allow_fail = True,
  )
  if not result.success:
    # provide default implementation; a real probe
    # should ask for credentials here and then download
    # the actual (restricted) file.
    ctx.file(
      "BUILD",
      "genrule(name='ext', outs = ['ext.txt'], cmd = 'echo no cache hit > $@', "
      + "visibility = ['//visibility:public'],)" ,
    )

probe = repository_rule(
  implementation = _rule_impl,
  attrs = {"sha256" : attr.string()},
  environ = ["ATTEMPT"],
)
EOF

  echo; echo initial build; echo
  # initially, no cache hit, should show default
  env ATTEMPT=1 bazel build //:it
  grep -q 'no cache hit' `bazel info bazel-genfiles`/it.txt \
      || fail "didn't find the default implementation"

  echo; echo build of ext; echo
  # ensure the cache is filled
  bazel build @ext//...

  echo; echo build with cache hit; echo
  # now we should get the real external dependency
  env ATTEMPT=2 bazel build //:it
  grep -q 'True external file' `bazel info bazel-genfiles`/it.txt \
      || fail "didn't find the default implementation"
}

function test_cache_hit_reported() {
  # Verify that information about a cache hit is reported
  # if an error happened in that repository. This information
  # is useful as users sometimes change the URL but do not
  # update the hash.

  add_to_bazelrc "common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0"

  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext-1.1
  cat > ext-1.1/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext-1.1.zip ext-1.1/*
  rm -rf ext-1.1
  sha256=$(sha256sum ext-1.1.zip | head -c 64)

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext-1.1",
  urls=["file://${WRKDIR}/ext-1.1.zip"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//:foo"],
  outs = ["it.txt"],
  cmd = "cp $< $@"
)
EOF

  echo "Build #1"

  # build to fill the cache
  bazel build //:it || fail "Expected success"

  # go offline and clean everything
  bazel clean --expunge
  rm "${WRKDIR}/ext-1.1.zip"

  echo "Build #2"

  # We still should be able to build, as the file is in cache
  bazel build //:it > "${TEST_log}" 2>&1 || fail "Expected success"
  # As a cache hit is a perfectly normal thing, we don't expect it to be
  # reported.
  expect_not_log 'cache hit'
  expect_not_log "${sha256}"
  expect_not_log 'file:.*/ext-1.1.zip'

  # Now update ext-1.1 to ext-1.2, while forgetting to update the checksum
  ed MODULE.bazel <<EOI
%s/ext-1\.1/ext-1\.2/g
w
q
EOI

  echo "Build #3"

  # The build should fail, as the prefix is not found. The available prefix
  # should be reported as well as the information that the file was taken
  # from cache.
  bazel build //:it > "${TEST_log}" 2>&1 && fail "Should not succeed" || :
  expect_log 'ext-1.2.*not found'
  expect_log 'prefixes.*ext-1.1'
  expect_log 'cache hit'
  expect_log "${sha256}"
  expect_log 'file:.*/ext-1.2.zip'

  # Now consider the case where no prefix is specified (and hence, the
  # download_and_extract call will succeed), but a patch command has
  # an assumption on a wrong path. As the fetching of the external
  # repository will fail, we still expect being hinted at the
  # cache hit.
  ed MODULE.bazel <<'EOI'
/strip_prefix
d
a
patch_cmds = ["cp ext-1.2/foo.txt ext-1.2/BUILD ."],
.
w
q
EOI

  echo "Build #4"

  bazel build //:it > "${TEST_log}" 2>&1 && fail "Should not succeed" || :
  expect_not_log 'prefix'
  expect_log 'cp ext-1.2/foo.txt ext-1.2/BUILD'
  expect_log 'cache hit'
  expect_log "${sha256}"
  expect_log 'file:.*/ext-1.2.zip'
}

function test_distdir() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  mkdir distfiles
  mv ext.zip distfiles

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/outdatedpath/ext.zip"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:foo"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel clean --expunge
  bazel build --distdir="${WRKDIR}/distfiles" //:local \
    || fail "expected success"
}


function test_distdir_outputname() {
  # Verify that distdir searches at least for the local part of the URL,
  # even if the output is renamed.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  date +%s > actual_file_name.txt
  sha256=$(sha256sum actual_file_name.txt | head -c 64)

  mkdir distfiles
  mv actual_file_name.txt distfiles

  mkdir main
  cd main
  cat > ext_file.bzl <<'EOF'
def _impl(ctx):
  ctx.download(
    url = ctx.attr.urls,
    output = 'foo',
    sha256 = ctx.attr.sha256,
  )
  ctx.file(
    "BUILD",
    "exports_files(['foo'], visibility=['//visibility:public'])",
  )

ext_file = repository_rule(
  implementation = _impl,
  attrs = { "urls" : attr.string_list(), "sha256" : attr.string() },
)
EOF
  cat > $(setup_module_dot_bazel) <<EOF
ext_file = use_repo_rule("//:ext_file.bzl", "ext_file")
ext_file(
  name="ext",
  urls=["http://doesnotexist.example.com/outdatedpath/actual_file_name.txt"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:foo"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel clean --expunge
  bazel build --distdir="${WRKDIR}/distfiles" //:local \
    || fail "expected success"
}

function test_distdir_relative_path() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  mkdir distfiles
  mv ext.zip distfiles

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/outdatedpath/ext.zip"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:foo"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel clean --expunge
  bazel build --distdir="../distfiles" //:local \
    || fail "expected success"
}

function test_distdir_non_existent() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  touch thisisafile
  mkdir thisisempty

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:foo"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel clean --expunge
  # The local distdirs all do no provide the file; still, it should work by fetching
  # the file from upstream.
  bazel build --distdir=does/not/exist --distdir=/global/does/not/exist --distdir=../thisisafile --distdir=../thisisempty //:local \
    || fail "expected success"
}

function test_good_symlinks() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir -p ext/subdir
  echo foo > ext/file.txt
  ln -s ../file.txt ext/subdir/symlink.txt
  ls -alR ext
  zip -r --symlinks ext.zip ext

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  build_file="@//:ext.BUILD"
)
EOF
  cat > ext.BUILD <<'EOF'
exports_files(["subdir/symlink.txt"])
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:subdir/symlink.txt"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel build //:local || fail "Expected success"
}

function test_distdir_option_not_sticky() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"
  mkdir ext
  cat > ext/BUILD <<'EOF'
genrule(
  name="foo",
  outs=["foo.txt"],
  cmd="echo Hello World > $@",
  visibility = ["//visibility:public"],
)
EOF
  zip ext.zip ext/*
  rm -rf ext
  sha256=$(sha256sum ext.zip | head -c 64)

  mkdir distfiles
  mv ext.zip distfiles

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["http://doesnotexist.example.com/outdatedpath/ext.zip"],
  sha256="${sha256}",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:foo"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)

genrule(
  name = "unrelated",
  outs = ["unrelated.txt"],
  cmd = "echo Something > $@",
)
EOF

  bazel clean --expunge
  bazel build --distdir="../distfiles" //:unrelated \
    || fail "expected success"
  # As no --distdir option is given and upstream not available,
  # we expect the build to fail
  bazel build //:local && fail "Expected failure" || :
}

function test_bad_symlinks() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir -p ext/subdir
  echo foo > ext/file.txt
  ln -s ../file.txt ext/symlink.txt
  ls -alR ext
  zip -r --symlinks ext.zip ext

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  urls=["file://${WRKDIR}/ext.zip"],
  build_file="@//:ext.BUILD"
)
EOF
  cat > ext.BUILD <<'EOF'
exports_files(["file.txt"])
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@ext//:file.txt"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel build //:local \
    && fail "Expected failure due to unsupported symlink" || :
}

function test_progress_reporting() {
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  cat > rule.bzl <<'EOF'
def _rule_impl(ctx):
  ctx.report_progress("First action")
  ctx.execute(["/bin/sh", "-c", "sleep 5"])
  ctx.report_progress("Second action")
  ctx.execute(["/bin/sh", "-c", "sleep 5"])
  ctx.report_progress("Actual files")
  ctx.file("data", "Hello world")
  ctx.file("BUILD", "exports_files(['data'])")

with_progress = repository_rule(
  implementation = _rule_impl,
  attrs = {},
)
EOF

  cat >> $(setup_module_dot_bazel) <<'EOF'
with_progress = use_repo_rule("//:rule.bzl", "with_progress")
with_progress(name="foo")
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "local",
  srcs = ["@foo//:data"],
  outs = ["local.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build --curses=yes //:local > "${TEST_log}" 2>&1 \
      || fail "expected success"
  expect_log "foo.*First action"
  expect_log "foo.*Second action"
}

function test_progress_reporting() {
  # Isse 7353 requested that even in the case of a syntactically invalid
  # checksum, the file still should be fetched and its checksum computed.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  touch ext.tar

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name = "ext",
  urls = ["file://${WRKDIR}/ext.tar"],
  sha256 = "badargument",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//:in.txt"],
  outs = ["out.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel build //:it > "${TEST_log}" 2>&1 && fail "Expected failure" || :

  expect_log '@@+_repo_rules+ext.*badargument'
}

function test_prefix_suggestions() {
  # Verify that useful suggestions are made if an expected prefix
  # is not found in an archive.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir -p ext-1.1/foo
  touch ext-1.1/foo.txt

  tar cvf ext.tar ext-1.1
  rm -rf ext-1

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext-1.0",
  urls=["file://${WRKDIR}/ext.tar"],
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//:foo.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it > "${TEST_log}" 2>&1 && fail "expected failure" || :
  expect_log "ext-1.0.*not found"
  expect_log "prefixes.*ext-1.1"
}

function test_suggest_nostripprefix() {
  # Verify that a suggestion is made about dropping an unnecessary
  # `strip_prefix` argument.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir ext
  touch ext/foo.txt
  (cd ext && tar cvf "${WRKDIR}/ext.tar" foo.txt)

  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext-1.0",
  urls=["file://${WRKDIR}/ext.tar"],
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "it",
  srcs = ["@ext//:foo.txt"],
  outs = ["it.txt"],
  cmd = "cp $< $@",
)
EOF
  bazel build //:it > "${TEST_log}" 2>&1 && fail "expected failure" || :
  expect_log "ext-1.0.*not found"
  expect_log "not.*any directory"
  expect_log 'no need for `strip_prefix`'
}

function test_report_files_searched() {
  # Verify that upon  a missing package, the places where a BUILD file was
  # searched for are reported.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir ext
  echo 'data' > ext/foo.txt
  tar cvf ext.tar ext
  rm -rf ext

  mkdir -p path/to/workspace
  cd path/to/workspace
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  urls=["file://${WRKDIR}/ext.tar"],
  build_file = "@//path/to:ext.BUILD",
)
EOF
  mkdir -p path/to
  echo 'exports_files(["foo.txt"])' > path/to/ext.BUILD

  bazel build @ext//... > "${TEST_log}" 2>&1 \
      && fail "Expected failure" || :

  expect_log 'BUILD file not found'
  expect_log '- path/to'
}

function test_report_package_external() {
  # Verify that a useful error message is shown for a BUILD
  # file not found at the expected location in an external repository.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  mkdir -p ext/path/too/deep
  echo 'data' > ext/path/too/deep/foo.txt
  echo 'exports_files(["deep/foo.txt"])' > ext/path/too/BUILD
  tar cvf ext.tar ext
  rm -rf ext

  mkdir -p path/to/workspace
  cd path/to/workspace
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  urls=["file://${WRKDIR}/ext.tar"],
)
EOF
  cat > BUILD <<EOF
genrule(
  name = "it",
  outs = ["it.txt"],
  srcs = ["@ext//path/too/deep:foo.txt"],
  cmd = "cp $< $@",
)
EOF

  bazel build //:it > "${TEST_log}" 2>&1 \
      && fail "Expected failure" || :

  expect_log 'BUILD file not found.*path/too/deep'
}

function test_overwrite_existing_workspace_build() {
  # Verify that the WORKSPACE and BUILD files provided by
  # the invocation of an http_archive rule correctly
  # overwritel any such file packed in the archive.
  WRKDIR=$(mktemp -d "${TEST_TMPDIR}/testXXXXXX")
  cd "${WRKDIR}"

  for bad_file in \
      'echo BAD content > $1' \
      'ln -s /does/not/exist/BAD $1'

  do
    rm -rf ext ext.tar
    mkdir ext
    sh -c "${bad_file}" -- ext/WORKSPACE
    sh -c "${bad_file}" -- ext/BUILD.bazel
    echo hello world > ext/data
    tar cvf ext.tar ext

    for BUILD_FILE in \
      'build_file_content = '\''exports_files(["data", "WORKSPACE"])'\' \
      'build_file = "@//:external_build_file"'
    do
      rm -rf main
      mkdir main
      cd main

      cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
   name = "ext",
   strip_prefix = "ext",
   url = 'file://${WRKDIR}/ext.tar',
   ${BUILD_FILE},
)
EOF
      echo
      ls -al ${WRKDIR}/ext
      echo
      cat MODULE.bazel
      echo

      cat > external_build_file <<'EOF'
exports_files(["data", "WORKSPACE"])
EOF

      cat > BUILD <<'EOF'
genrule(
  name = "it",
  cmd = "cp $< $@",
  srcs = ["@ext//:data"],
  outs = ["it.txt"],
)

genrule(
  name = "ws",
  cmd = "cp $< $@",
  srcs = ["@ext//:WORKSPACE"],
  outs = ["ws.txt"],
)
EOF

      bazel build //:it || fail "Expected success"
      grep 'world' `bazel info bazel-genfiles`/it.txt \
          || fail "Wrong content of data file"
      bazel build //:ws || fail "Expected success"
      grep 'BAD' `bazel info bazel-genfiles`/ws.txt \
          && fail "WORKSPACE file not overwritten" || :

      cd ..
    done
  done
}

function test_external_java_target_depends_on_external_resources() {
  local test_repo1=$TEST_TMPDIR/repo1
  local test_repo2=$TEST_TMPDIR/repo2

  mkdir -p $test_repo1/a
  mkdir -p $test_repo2

  cat > $(setup_module_dot_bazel) <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = 'repo1', path='$test_repo1')
local_repository(name = 'repo2', path='$test_repo2')
EOF
  cat > BUILD <<'EOF'
java_binary(
    name = "a_bin",
    runtime_deps = ["@repo1//a:a"],
    main_class = "a.A",
)
EOF

  touch $test_repo1/REPO.bazel
  cat > $test_repo1/a/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

java_library(
    name = "a",
    srcs = ["A.java"],
    resources = ["@repo2//:resource_files"],
)
EOF
  cat > $test_repo1/a/A.java <<EOF
package a;

public class A {
    public static void main(String args[]) {
    }
}
EOF

  touch $test_repo2/REPO.bazel
  cat > $test_repo2/BUILD <<'EOF'
package(default_visibility = ["//visibility:public"])

filegroup(
    name = "resource_files",
    srcs = ["resource.txt"]
)
EOF

  cat > $test_repo2/resource.txt <<EOF
RESOURCE
EOF

  bazel build a_bin >& $TEST_log  || fail "Expected build/run to succeed"
}

function test_query_external_packages() {
  mkdir -p external/nested
  mkdir -p not-external

  cat > external/BUILD <<EOF
filegroup(
    name = "a1",
    srcs = [],
)
EOF
  cat > external/nested/BUILD <<EOF
filegroup(
    name = "a2",
    srcs = [],
)
EOF

  cat > not-external/BUILD <<EOF
filegroup(
    name = "b",
    srcs = [],
)
EOF

  # Remove tools directory set up by copy_tools_directory in testenv.sh
  rm -rf tools/

  bazel query //... >& $TEST_log || fail "Expected build/run to succeed"
  expect_log "//not-external:b"
  expect_not_log "//external:a1"
  expect_not_log "//external/nested:a2"

  bazel query --experimental_sibling_repository_layout //... >& $TEST_log \ ||
    fail "Expected build/run to succeed"
  expect_log "//not-external:b"
  # Targets in //external aren't supported yet.
  expect_not_log "//external:a1"
  expect_log "//external/nested:a2"
}

function test_query_external_packages_in_other_repo() {
  cat > $(setup_module_dot_bazel) <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name="other_repo",
  path="other_repo",
)
EOF

  mkdir -p other_repo/external/nested
  mkdir -p other_repo/not-external

  touch other_repo/REPO.bazel

  cat > other_repo/external/BUILD <<EOF
filegroup(
    name = "a1",
    srcs = [],
)
EOF
  cat > other_repo/external/nested/BUILD <<EOF
filegroup(
    name = "a2",
    srcs = [],
)
EOF

  cat > other_repo/not-external/BUILD <<EOF
filegroup(
    name = "b",
    srcs = [],
)
EOF

  bazel query @other_repo//... >& $TEST_log || fail "Expected build/run to succeed"
  expect_log "@other_repo//not-external:b"
  expect_log "@other_repo//external:a1"
  expect_log "@other_repo//external/nested:a2"

  bazel query --experimental_sibling_repository_layout @other_repo//... >& $TEST_log \ ||
    fail "Expected build/run to succeed"
  expect_log "@other_repo//not-external:b"
  expect_log "@other_repo//external:a1"
  expect_log "@other_repo//external/nested:a2"
}

function test_query_external_all_targets() {
  mkdir -p external/nested
  mkdir -p not-external

  cat > external/nested/BUILD <<EOF
filegroup(
    name = "a",
    srcs = glob(["**"]),
)
EOF
  touch external/nested/A

  cat > not-external/BUILD <<EOF
filegroup(
    name = "b",
    srcs = glob(["**"]),
)
EOF
  touch not-external/B

  # Remove tools directory set up by copy_tools_directory in testenv.sh
  rm -rf tools/

  bazel query //...:all-targets >& $TEST_log \
    || fail "Expected build/run to succeed"
  expect_log "//not-external:b"
  expect_log "//not-external:B"
  expect_not_log "//external/nested:a"
  expect_not_log "//external/nested:A"

  bazel query --experimental_sibling_repository_layout //...:all-targets \
    >& $TEST_log || fail "Expected build/run to succeed"
  expect_log "//not-external:b"
  expect_log "//not-external:B"
  expect_log "//external/nested:a"
  expect_log "//external/nested:A"
}

function test_external_deps_skymeld() {
  # A minimal build to make sure bazel in Skymeld mode can build with external
  # dependencies.
  mkdir ext
  echo content > ext/ext
  EXTREPODIR=`pwd`
  zip ext.zip ext/*
  rm -rf ext

  rm -rf main
  mkdir main
  cd main
  cat > $(setup_module_dot_bazel) <<EOF
http_archive = use_repo_rule("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
  name="ext",
  strip_prefix="ext",
  url="file://${EXTREPODIR}/ext.zip",
  build_file_content="exports_files([\"ext\"])",
)
EOF
  cat > BUILD <<'EOF'
genrule(
  name = "foo",
  srcs = ["@ext//:ext"],
  outs = ["foo"],
  cmd = "cp $< $@",
)
EOF
  execroot="$(bazel info execution_root)"

  bazel build --experimental_merged_skyframe_analysis_execution //:foo \
    || fail 'Expected build to succeed with Skymeld'

  test -h "$execroot/external/+_repo_rules+ext" || fail "Expected symlink to external repo."
}

function test_default_canonical_id_enabled() {
    cat > repo.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:cache.bzl", "get_default_canonical_id")

def _impl(rctx):
  print("canonical_id", repr(get_default_canonical_id(rctx, ["url-1", "url-2"])))
  rctx.file("BUILD", "")

dummy_repository = repository_rule(_impl)
EOF
  touch BUILD
  cat > MODULE.bazel <<EOF
dummy_repository = use_repo_rule('//:repo.bzl', 'dummy_repository')
dummy_repository(name = 'foo')
EOF

  # NOTE: Test environment modifies defaults, so --repo_env must be explicitly set
  bazel query @foo//:all --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=1 \
    2>$TEST_log || fail 'Expected fetch to succeed'
  expect_log "canonical_id \"url-1 url-2\""
}

function test_default_canonical_id_disabled() {
    cat > repo.bzl <<EOF
load("@bazel_tools//tools/build_defs/repo:cache.bzl", "get_default_canonical_id")

def _impl(rctx):
  print("canonical_id", repr(get_default_canonical_id(rctx, ["url-1", "url-2"])))
  rctx.file("BUILD", "")

dummy_repository = repository_rule(_impl)
EOF
  touch BUILD
  cat > MODULE.bazel <<EOF
dummy_repository = use_repo_rule('//:repo.bzl', 'dummy_repository')
dummy_repository(name = 'foo')
EOF

  # NOTE: Test environment modifies defaults, so --repo_env must be explicitly set
  bazel query @foo//:all --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0 \
    2>$TEST_log || fail 'Expected fetch to succeed'
  expect_log "canonical_id \"\""
}

function test_environ_incrementally() {
  # Set up workspace with a repository rule to examine env vars.  Assert that undeclared
  # env vars don't trigger reevaluations.
  cat > repo.bzl <<EOF
def _impl(rctx):
  rctx.symlink(rctx.attr.build_file, 'BUILD')
  print('UNDECLARED_KEY=%s' % rctx.os.environ.get('UNDECLARED_KEY'))
  print('PREDECLARED_KEY=%s' % rctx.os.environ.get('PREDECLARED_KEY'))
  print('LAZYEVAL_KEY=%s' % rctx.getenv('LAZYEVAL_KEY'))

dummy_repository = repository_rule(
  implementation = _impl,
  attrs = {'build_file': attr.label()},
  environ = ['PREDECLARED_KEY'],  # sic
)
EOF
  cat > BUILD.dummy <<EOF
filegroup(name='dummy', srcs=['BUILD'])
EOF
  touch BUILD
  cat > $(setup_module_dot_bazel) <<EOF
dummy_repository = use_repo_rule('//:repo.bzl', 'dummy_repository')
dummy_repository(name = 'foo', build_file = '@@//:BUILD.dummy')
EOF

  # Baseline: DEBUG: UNDECLARED_KEY is logged to stderr.
  UNDECLARED_KEY=val1 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "UNDECLARED_KEY=val1"

  # UNDECLARED_KEY is, well, undeclared.  This will be a no-op.
  UNDECLARED_KEY=val2 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_not_log "UNDECLARED_KEY"

  #---

  # Predeclared key.
  PREDECLARED_KEY=wal1 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "PREDECLARED_KEY=wal1"

  # Predeclared key, no-op build.
  PREDECLARED_KEY=wal1 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_not_log "PREDECLARED_KEY"

  # Predeclared key, new value -> refetch.
  PREDECLARED_KEY=wal2 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "PREDECLARED_KEY=wal2"

  #---

  # Side-effect key.
  LAZYEVAL_KEY=xal1 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "PREDECLARED_KEY=None"
  expect_log "LAZYEVAL_KEY=xal1"

  # Side-effect key, no-op build.
  LAZYEVAL_KEY=xal1 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_not_log "LAZYEVAL_KEY"

  # Side-effect key, new value -> refetch.
  LAZYEVAL_KEY=xal2 bazel query @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "LAZYEVAL_KEY=xal2"

  # Ditto, but with --repo_env overriding environment.
  LAZYEVAL_KEY=xal2 bazel query --repo_env=LAZYEVAL_KEY=xal3 @foo//:BUILD 2>$TEST_log || fail 'Expected no-op build to succeed'
  expect_log "LAZYEVAL_KEY=xal3"
}

function test_external_package_in_other_repo() {
  cat > $(setup_module_dot_bazel) <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name="other_repo",
  path="other_repo",
)
EOF

  mkdir -p other_repo/external/java/a
  touch other_repo/REPO.bazel

  cat > other_repo/external/java/a/BUILD <<EOF
java_library(name='a', srcs=['A.java'])
EOF

  cat > other_repo/external/java/a/A.java << EOF
package a;
public class A {
  public static void main(String[] args) {
    System.out.println("hello world");
  }
}
EOF

  bazel build @other_repo//external/java/a:a || fail "build failed"
}

function test_external_dir_in_other_repo() {
  cat > $(setup_module_dot_bazel) <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name="other_repo",
  path="other_repo",
)
EOF

  mkdir -p other_repo/external/java/a
  touch other_repo/REPO.bazel

  cat > other_repo/BUILD <<EOF
java_library(name='a', srcs=['external/java/a/A.java'])
EOF

  cat > other_repo/external/java/a/A.java << EOF
package a;
public class A {
  public static void main(String[] args) {
    System.out.println("hello world");
  }
}
EOF

  bazel build @other_repo//:a || fail "build failed"
}

run_suite "external tests"
