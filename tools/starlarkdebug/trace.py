# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""Use iterate_all_breakpoints interface to print traces (python expressions)

Example from tuorial:
python3  tools/starlarkdebug/trace.py \
    -b'/path/to/tensorflow/tensorflow.bzl:2642' \
    -f'0:"pywrap" in so_file' \
    -t"0:so_file"
"""

import argparse
import inspect
import re
import sys
from debugger import StarlarkDebugger, IS_INTERACTIVE, setup_interactive_mode

EXPR_PATTERN = re.compile(r"^(?P<level>\d+):(?P<expr>.*)$")


class Expression(str):
    def __new__(cls, level, expression):
        obj = str.__new__(cls, expression)
        obj.level = level
        return obj


def _read_expressions(arglist):
    result = []
    if arglist is not None:
        for arg in arglist:
            res = EXPR_PATTERN.search(arg)
            if res is None:
                raise Exception(f"Argument {arg} does not follow pattern {EXPR_PATTERN.pattern}")
            result.append(Expression(int(res.group("level")), res.group("expr")))
    return result


def main():
    """"Main will create a debugger instance.
    """
    errorcode = 0
    debug = True
    debugger = None
    try:
        parser = argparse.ArgumentParser(
            description="Print tracer expressions on breakpoints. If no tracers is specified"
                        ", output mber of breakpoints hit")
        StarlarkDebugger.add_parser_arguments(parser)
        parser.add_argument('-f', '--filters', dest='filters', nargs="*",
                            help='Additional client side conditions, example \'0:value==4\'.')
        parser.add_argument('-t', '--traces', dest='traces', nargs="*",
                            help='Expressions to output when breakpoint is hit, example \'0:value\'.')
        parser.add_argument('-e', '--expression', dest='expression',
                            help='Server side condition to hit breakpoint.')
        parser.add_argument('-b', '--breakpoints', dest='breakpoints', nargs="*",
                            help='Breakpoints to trace.')
        parser.add_argument('--debug', dest='debug', action='store_true',
                            help='Use this flag in combination with interactive mode (python -i) to'
                                 ' get a global debug environment.')

        args = parser.parse_args()
        debug = args.debug
        filters = _read_expressions(args.filters)
        traces = _read_expressions(args.traces)

        debugger = StarlarkDebugger.from_parser_args(args)
        debugger.initialize()

        if args.breakpoints:
            debugger.set_breakpoints(args.breakpoints, args.expression or "")

        n = 0
        for thread in debugger.iterate_paused_threads():
            if len(filters) > 0 or len(traces) > 0:
                frames = debugger.list_frames(thread.id)
                trace = True
                for expression in filters:
                    if expression.level <= len(frames):
                        frame = frames[expression.level]
                        if not frame.evaluate(expression):
                            trace = False
                            break
                if trace:
                    if len(traces) > 0:
                        for expression in traces:
                            print(frames[expression.level].evaluate(expression))
                    else:
                        n += 1
            else:
                n += 1

        if len(traces) == 0:
            print(n)
    finally:
        if debug and IS_INTERACTIVE:
            globals().update(inspect.currentframe().f_locals)
            setup_interactive_mode(debugger)
        else:
            if debugger is not None:
                debugger.shutdown()
    return errorcode


if __name__ == '__main__':
    EXITCODE = main()
    if not IS_INTERACTIVE:
        sys.exit(EXITCODE)
