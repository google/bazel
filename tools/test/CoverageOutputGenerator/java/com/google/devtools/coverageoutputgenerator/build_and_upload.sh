#!/bin/bash
#
# Copyright 2019 The Bazel Authors. All rights reserved.
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

# A script to build, package, and upload a copy of
# coverage_output_generator.zip to bazel-mirror.
#
# To make a "release" of the coverage tools, it should be then copied to
# bazel_coverage_output_generator/release/coverage_output_generator-v[major].[minor].zip

commit_hash=$(git rev-parse HEAD)
timestamp=$(date +%s)
bazel_version=$(bazel info release | cut -d' ' -f2)

bazel build \
    --java_language_version=8 \
    --tool_java_language_version=8 \
    //tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator.zip

cov_gen_zip="bazel-bin/tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator/coverage_output_generator.zip"

# copy the built zip to a temp location so we can add the README.md file
tmp_dir=$(mktemp -d -t "tmp_bazel_cov_gen_XXXXXX")
trap "rm -rf ${tmp_dir}" EXIT
tmp_zip="${tmp_dir}/coverage_output_generator.zip"
cp "${cov_gen_zip}" "${tmp_zip}"
chmod +w "${tmp_zip}"

readme_file="${tmp_dir}/README.md"
cat >${readme_file} <<EOF
This coverage_output_generator version was built from the Bazel repository
at commit hash ${commit_hash} using Bazel version ${bazel_version}.
To build the same zip from source, run the commands:

$ git clone https://github.com/bazelbuild/bazel.git
$ git checkout ${commit_hash}
$ bazel build --java_language_version=8 --tool_java_language_version=8 //tools/test/CoverageOutputGenerator/java/com/google/devtools/coverageoutputgenerator:coverage_output_generator.zip
EOF

zip -rjv "${tmp_zip}" "${readme_file}"

DEST="bazel_coverage_output_generator/coverage_output_generator-${commit_hash}-${timestamp}.zip"
gsutil cp ${tmp_zip} "gs://bazel-mirror/${DEST}"
echo "Uploaded to ${DEST}"
