# Copyright 2025 The Bazel Authors. All rights reserved.
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

import socket
import sys
import threading
import unittest

from testsupport.python_sandbox import ModulesUnderTestSandBox

ALLOWED_MODULES = [
    'argparse',
    'collections',
    'functools',
    'logging',  # TODO: We should probably watch over this
    'queue',  # TODO: Can we mock this and check for race conditions?
    're',
    'starlark_debugging_pb2',
    'timeit',  # TODO: Mock this to test statistics collection
    'threading',  # TODO: Can we mock this, control execution and check for race conditions?
]

ALLOWED_BUILTINS = [
    "dict",
    "hasattr",
    "list",
    "object",
    "staticmethod",
    "set",
    "str",
    "type",
]


def _print(*args, **kwargs):
    print("code under test print: %s" % " ".join(args))


class SysFlags:
    """Sys flags for code under test."""

    def __init__(self):
        self.interactive = 0  # If we should test the interactive mode or not


class SocketMock:
    """Mock socket to check connections and communicate with code under test."""

    class Socket:
        # Allow shadowing of global variable "type" as this call needs to match socket.socket() API
        def __init__(self, family=-1, type=-1, proto=-1, fileno=None):  # noqa
            assert family == socket.AF_INET
            assert type == socket.SOCK_STREAM
            assert proto == -1
            assert fileno is None
            self.connect_address = None
            self.recv_condition = threading.Condition()

        def connect(self, address):
            self.connect_address = address

        def recv(self, num_bytes):
            with self.recv_condition:
                self.recv_condition.wait()
            return b""

        def tearDown(self):
            with self.recv_condition:
                self.recv_condition.notify_all()

    def __init__(self):
        self.sockets = []

    # Allow shadowing of global variable "type" as this call needs to match socket.socket() API
    def __call__(self, family=-1, type=-1, proto=-1, fileno=None):  # noqa
        sock = SocketMock.Socket(family, type, proto, fileno)
        self.sockets.append(sock)
        return sock

    def tearDown(self):
        for sock in self.sockets:
            sock.tearDown()


env = ModulesUnderTestSandBox(['debugger'])
builtins = sys.modules['builtins']
for module in ALLOWED_MODULES:
    env[module] = __import__(module)
env['builtins'].print = _print
for builtin in ALLOWED_BUILTINS:
    setattr(env['builtins'], builtin, getattr(builtins, builtin))
env['socket'] = env.new_module('socket')
env['socket'].AF_INET = socket.AF_INET
env['socket'].SOCK_STREAM = socket.SOCK_STREAM
env['sys'] = env.new_module('sys')
env['sys'].flags = SysFlags()
env['sys'].stdout = sys.stdout
env['traceback'] = env.new_module('traceback')


class TestDebugger(unittest.TestCase):
    def __init__(self, *args, **kwargs):
        super(TestDebugger, self).__init__(*args, **kwargs)

    def setUp(self):
        unittest.TestCase.setUp(self)
        self.socket_mock = SocketMock()
        env['socket'].socket = self.socket_mock
        env.start()
        self.debugger_module = env.get_module_under_test('debugger')

    def tearDown(self):
        self.socket_mock.tearDown()
        try:
            env.stop()
        finally:
            unittest.TestCase.tearDown(self)

    def test_initialize(self):
        host = "1.2.3.4"
        port = 1234
        debugger = self.debugger_module.StarlarkDebugger(
            host=host,
            port=port,
            base_path=None,
            request_log_oputput_path=None,
        )
        debugger_exception = None
        try:
            try:
                debugger.initialize()
            # Allow blind exception here as code under test could throw anything
            except Exception as e:  # noqa
                debugger_exception = e
        finally:
            # TODO: Check that all threads are stopped
            debugger.shutdown()
        assert debugger_exception is None, f"Exception during debugger initialization: {debugger_exception}"
        num_sockets = len(self.socket_mock.sockets)
        assert num_sockets == 1, f"Expected to create one socket, created {num_sockets}"
        chost, cport = self.socket_mock.sockets[0].connect_address
        assert (chost, cport) == (host, port), f"Expected to connect to {host}:{port}, connected to {chost}:{cport}"


if __name__ == '__main__':
    unittest.main()
