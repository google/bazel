# Starlark debug

This is the debugger and trace tool presented at Build Meetup 2021 (tutorial in video):
https://www.youtube.com/watch?v=D7-BbD6QjeU

Note that this is experimental and may break due to lack of verification. That
said it have "just worked" for three years (except known issues) using
Bazel ranging from 4 to 8, probably due to low activity in the debugger
protocol.

## Preconditions

Since you cannot start python in interactive mode using `bazel run` you need
to build the starlark_debugger protobuf interface and then either copy it
alongside the debugger tool or pass it's location in the PYTHONPATH
environment variable.

```
STARLARK_DEBUGGER_PROTO=src/main/java/com/google/devtools/build/lib/starlarkdebug/proto
bazel build //${STARLARK_DEBUGGER_PROTO}:starlark_debugging_py_proto
export PYTHONPATH="$(realpath bazel-bin/${STARLARK_DEBUGGER_PROTO})"
python3 tools/starlarkdebug/debugger.py --help
```

Note that above will use bazel to build the python interface but uses a local
python installation to run the debugger. This installation needs the protobuf
package installed which may fail due to mismatching protobuf versions in which
you can:
* Create a virtual python environment and install the protobuf version bazel uses there
* Use protoc tool to generate the debugging interface with your version

## Setup bazel for debugging

Bazel needs to be running with the debugger active for the debugger tool to run
otherwise you will get a `connection refused` message or similar. 

Execute the command to debug as normal but add a few extra build flags for
it to wait for the debugger tool to connect. Here is a sample bazelrc file:
```
build:starlark_debug # Enable the skylark debugger
build:starlark_debug --experimental_skylark_debug
build:starlark_debug --experimental_skylark_debug_server_port=7200
build:starlark_debug --keep_state_after_build=false
```

You can optionally add `--build=false` to the command
line as the build phase is not relevant for the starlark debug.

Note if bazel exits immediately without triggering a breakpoint you may
need to run `bazel shutdown` to clear the analysis cache. Disabling  the
`keep_state_after_build` flag (as in the example above) will prevent bazel
to cache the execution in memory.

## Interactive debugger

The easiest usage as shown in the tutorial is to first execute the command
to debug as normal with additional debug flags and then execute the debugger
in interactive mode in another terminal while bazel waits for a debugger to
attach.

```
python3 -i tools/starlarkdebug/debugger.py
```

## TODO:s (good place to chime in)

* Sandboxed tests for the StarlarkDebugger API
  with sandboxed means not allowing python code to have side effects
* Integration test with the Starlark debugger
* Could we interact with the java debugger as well?
