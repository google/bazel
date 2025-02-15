Project: /_project.yaml
Book: /_book.yaml

# Code coverage with Bazel

{% include "_buttons.html" %}

Bazel features a `coverage` sub-command to produce code coverage
reports on repositories that can be tested with `bazel coverage`. Due
to the idiosyncrasies of the various language ecosystems, it is not
always trivial to make this work for a given project.

This page documents the general process for creating and viewing
coverage reports, and also features some language-specific notes for
languages whose configuration is well-known. It is best read by first
reading [the general section](#creating-a-coverage-report), and then
reading about the requirements for a specific language. Note also the
[remote execution section](#remote-execution), which requires some
additional considerations.

While a lot of customization is possible, this document focuses on
producing and consuming [`lcov`][lcov] reports, which is currently the
most well-supported route.

## Creating a coverage report {:#creating-a-coverage-report}

### Preparation

The basic workflow for creating coverage reports requires the
following:

- A basic repository with test targets
- A toolchain with the language-specific code coverage tools installed
- A correct "instrumentation" configuration

The former two are language-specific and mostly straightforward,
however the latter can be more difficult for complex projects.

"Instrumentation" in this case refers to the coverage tools that are
used for a specific target. Bazel allows turning this on for a
specific subset of files using the
[`--instrumentation_filter`](/reference/command-line-reference#flag--instrumentation_filter)
flag, which specifies a filter for targets that are tested with the
instrumentation enabled. To enable instrumentation for tests, the
[`--instrument_test_targets`](/reference/command-line-reference#flag--instrument_test_targets)
flag is required.

By default, bazel tries to match the target package(s), and prints the
relevant filter as an `INFO` message.

### Running coverage

To produce a coverage report, use [`bazel coverage
--combined_report=lcov
[target]`](/reference/command-line-reference#coverage). This runs the
tests for the target, generating coverage reports in the lcov format
for each file.

Once finished, bazel runs an action that collects all the produced
coverage files, and merges them into one, which is then finally
created under `$(bazel info
output_path)/_coverage/_coverage_report.dat`.

Coverage reports are also produced if tests fail, though note that
this does not extend to the failed tests - only passing tests are
reported.

### Viewing coverage

The coverage report is only output in the non-human-readable `lcov`
format. From this, we can use the `genhtml` utility (part of [the lcov
project][lcov]) to produce a report that can be viewed in a web
browser:

```console
genhtml --branch-coverage --output genhtml "$(bazel info output_path)/_coverage/_coverage_report.dat"
```

Note that `genhtml` reads the source code as well, to annotate missing
coverage in these files. For this to work, it is expected that
`genhtml` is executed in the root of the bazel project.

To view the result, simply open the `index.html` file produced in the
`genhtml` directory in any web browser.

For further help and information around the `genhtml` tool, or the
`lcov` coverage format, see [the lcov project][lcov].

## Remote execution {:#remote-execution}

Running with remote test execution currently has a few caveats:

- The report combination action cannot yet run remotely. This is
  because Bazel does not consider the coverage output files as part of
  its graph (see [this issue][remote_report_issue]), and can therefore
  not correctly treat them as inputs to the combination action. To
  work around this, use `--strategy=CoverageReport=local`.
  - Note: It may be necessary to specify something like
    `--strategy=CoverageReport=local,remote` instead, if Bazel is set
    up to try `local,remote`, due to how Bazel resolves strategies.
- `--remote_download_minimal` and similar flags can also not be used
  as a consequence of the former.
- Bazel will currently fail to create coverage information if tests
  have been cached previously. To work around this,
  `--nocache_test_results` can be set specifically for coverage runs,
  although this of course incurs a heavy cost in terms of test times.
- `--experimental_split_coverage_postprocessing` and
  `--experimental_fetch_all_coverage_outputs`
  - Usually coverage is run as part of the test action, and so by
    default, we don't get all coverage back as outputs of the remote
    execution by default. These flags override the default and obtain
    the coverage data. See [this issue][split_coverage_issue] for more
    details.

## Language-specific configuration

### Java

Java should work out-of-the-box with the default configuration. The
[bazel toolchains][bazel_toolchains] contain everything necessary for
remote execution, as well, including JUnit.

### Python

See the [`rules_python` coverage docs](https://rules-python.readthedocs.io/en/latest/coverage.html)
for additional steps needed to enable coverage support in Python.

[lcov]: https://github.com/linux-test-project/lcov
[bazel_toolchains]: https://github.com/bazelbuild/bazel-toolchains
[remote_report_issue]: https://github.com/bazelbuild/bazel/issues/4685
[split_coverage_issue]: https://github.com/bazelbuild/bazel/issues/4685
