Project: /_project.yaml
Book: /_book.yaml

# Common C++ Build Use Cases

{% include "_buttons.html" %}

Here you will find some of the most common use cases for building C++ projects
with Bazel. If you have not done so already, get started with building C++
projects with Bazel by completing the tutorial
[Introduction to Bazel: Build a C++ Project](/start/cpp).

For information on cc_library and hdrs header files, see
<a href="/reference/be/c-cpp#cc_library">cc_library</a>.

## Including multiple files in a target {:#multiple-files-target}

You can include multiple files in a single target with
<a href="/reference/be/functions#glob">glob</a>.
For example:

```python
cc_library(
    name = "build-all-the-files",
    srcs = glob(["*.cc"]),
    hdrs = glob(["*.h"]),
)
```

With this target, Bazel will build all the `.cc` and `.h` files it finds in the
same directory as the `BUILD` file that contains this target (excluding
subdirectories).

## Using transitive includes {:#transitive-includes}

If a file includes a header, then any rule with that file as a source (that is,
having that file in the `srcs`, `hdrs`, or `textual_hdrs` attribute) should
depend on the included header's library rule. Conversely, only direct
dependencies need to be specified as dependencies. For example, suppose
`sandwich.h` includes `bread.h` and `bread.h` includes `flour.h`. `sandwich.h`
doesn't include `flour.h` (who wants flour in their sandwich?), so the `BUILD`
file would look like this:

```python
cc_library(
    name = "sandwich",
    srcs = ["sandwich.cc"],
    hdrs = ["sandwich.h"],
    deps = [":bread"],
)

cc_library(
    name = "bread",
    srcs = ["bread.cc"],
    hdrs = ["bread.h"],
    deps = [":flour"],
)

cc_library(
    name = "flour",
    srcs = ["flour.cc"],
    hdrs = ["flour.h"],
)
```

Here, the `sandwich` library depends on the `bread` library, which depends
on the `flour` library.

## Adding include paths {:#add-include-paths}

Sometimes you cannot (or do not want to) root include paths at the workspace
root. Existing libraries might already have an include directory that doesn't
match its path in your workspace. For example, suppose you have the following
directory structure:

```
└── my-project
    ├── legacy
    │   └── some_lib
    │       ├── BUILD
    │       ├── include
    │       │   └── some_lib.h
    │       └── some_lib.cc
    └── MODULE.bazel
```

Bazel will expect `some_lib.h` to be included as
`legacy/some_lib/include/some_lib.h`, but suppose `some_lib.cc` includes
`"some_lib.h"`. To make that include path valid,
`legacy/some_lib/BUILD` will need to specify that the `some_lib/include`
directory is an include directory:

```python
cc_library(
    name = "some_lib",
    srcs = ["some_lib.cc"],
    hdrs = ["include/some_lib.h"],
    copts = ["-Ilegacy/some_lib/include"],
)
```

This is especially useful for external dependencies, as their header files
must otherwise be included with a `/` prefix.

## Include external libraries {:#include-external-libraries}

Suppose you are using [Google Test](https://github.com/google/googletest)
{: .external}.
You can add a dependency on it in the `MODULE.bazel` file to
download Google Test and make it available in your repository:

```python
bazel_dep(name = "googletest", version = "1.15.2")
```

## Writing and running C++ tests {:#run-c-tests}

For example, you could create a test `./test/hello-test.cc`, such as:

```cpp
#include "gtest/gtest.h"
#include "main/hello-greet.h"

TEST(HelloTest, GetGreet) {
  EXPECT_EQ(get_greet("Bazel"), "Hello Bazel");
}
```

Then create `./test/BUILD` file for your tests:

```python
cc_test(
    name = "hello-test",
    srcs = ["hello-test.cc"],
    copts = [
      "-Iexternal/gtest/googletest/include",
      "-Iexternal/gtest/googletest",
    ],
    deps = [
        "@googletest//:gtest_main",
        "//main:hello-greet",
    ],
)
```

To make `hello-greet` visible to `hello-test`, you must add
`"//test:__pkg__",` to the `visibility` attribute in `./main/BUILD`.

Now you can use `bazel test` to run the test.

```
bazel test test:hello-test
```

This produces the following output:

```
INFO: Found 1 test target...
Target //test:hello-test up-to-date:
  bazel-bin/test/hello-test
INFO: Elapsed time: 4.497s, Critical Path: 2.53s
//test:hello-test PASSED in 0.3s

Executed 1 out of 1 tests: 1 test passes.
```


## Adding dependencies on precompiled libraries {:#precompiled-libraries}

If you want to use a library of which you only have a compiled version (for
example, headers and a `.so` file) wrap it in a `cc_library` rule:

```python
cc_library(
    name = "mylib",
    srcs = ["mylib.so"],
    hdrs = ["mylib.h"],
)
```

This way, other C++ targets in your workspace can depend on this rule.
