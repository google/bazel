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

"""Python interface to interact with a service exposing the starlark_debugging protocol

This could either be used stand alone as an interactive debugger or as a Starlark debugger
interface from another python script.

The debugger API consists of the following:
* A StarlarkDebugger class to interact with the Bazel Starlark debugger service
  - Keep track of status messages sent from Bazel
  - Keep track of ongoing requests
  - Keep tack of breakpoints
  - Exposes one method per debugger message (set_breakpoints, evaluate, get_children, etc)
  - Higher level functions like iterate_paused_threads
* Python Starlark wrapper classes to wrap Starlark objects to python objects
  - Since Starlark is python-based these are mostly 1-1 mappings to the Starlark objects
  """

import argparse
import collections
import functools
import logging
import queue
import re
import socket
import sys
import timeit
import threading
import traceback
import starlark_debugging_pb2 as starlark_debugging

IS_INTERACTIVE = hasattr(sys, 'ps1') or sys.flags.interactive != 0
PAUSE_REASONS = {index: name for name, index in starlark_debugging.PauseReason.items()}

logging.basicConfig(format='%(message)s', stream=sys.stdout)
logger = logging.getLogger(__name__)
if IS_INTERACTIVE:
    logger.setLevel(logging.INFO)
else:
    logger.setLevel(logging.WARNING)


def recv_varint64(recv_function):
    """Decode a varint64 as sent by the Starlark debugger

    Args:
        recv_function:  Callback used to read data from stream

    Returns:
        Tuple of decoded value and the time the first byte arrived
    """
    shift = 0
    value = 0
    data = recv_function(1)
    t = timeit.default_timer()
    if len(data) == 0:
        return -1, t
    x = data[0]
    while x & 0x80:
        value |= (x & 0x7f) << shift
        shift += 7
        data = recv_function(1)
        x = data[0]
    value |= x << shift
    return value, t


def encode_varint64(value):
    """Encode a varint64 to send to the Starlark debugger

    Args:
        value:  Value to decode

    Returns:
        Encoded bytes
    """
    result = []
    while value >= 0x80:
        result.append((value & 0x7f) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


class Location:
    """Represents a code location, e.g. file path, line number, etc."""
    hash_pattern = re.compile(r'[A-Za-z0-9]{32}')

    @staticmethod
    def from_ref(debugger, ref):
        parts = ref.split(":")
        if len(parts) == 2:
            path, lno = parts
            col = 0
        elif len(parts) == 3:
            path, lno, col = parts
        else:
            raise Exception("Invalid location reference %r" % ref)
        if not path.startswith("/"):
            if debugger and debugger.base_path:
                path = debugger.base_path + "/" + path
            else:
                raise Exception("Need base_path to set relative breakpoints")
        message = starlark_debugging.Location(
            column_number=int(col),
            line_number=int(lno),
            path=path,
        )
        return Location(message)

    def __init__(self, message):
        self.path = message.path
        self.message = message
        self.base_path = None
        self.short_path = message.path
        self.path = message.path
        parts = message.path.split("/")

        # TODO: Get base_path from Bazel, check for "$HOME/.cache" or "$XDG_CONFIG_HOME"
        if ".cache" in parts:
            pos = parts.index(".cache") + 4
            if self.hash_pattern.search(parts[pos - 1]):
                self.base_path = "/".join(parts[:pos])
                self.short_path = "/".join(parts[pos:])
        self.line = message.line_number
        self.col = message.column_number
        self.__repr__ = message.__repr__

    def short_repr(self):
        return f"{self.path}:{self.line}:{self.col}"


class StarlarkThread:
    """Represents a Starlark thread"""
    def __init__(self, debugger, payload, message):
        self.debugger = debugger
        self.paused = payload == "thread_paused"
        self.continued = payload == "thread_continued"
        self.payload = payload
        self.message = message
        self.base_path = None
        if self.paused:
            self.name = message.thread.name
            self.id = message.thread.id
            self.location = Location(message.thread.location)
            self.base_path = self.location.base_path
            self.pause_reason = PAUSE_REASONS[message.thread.pause_reason]
        elif self.continued:
            self.id = message.thread_id
        self.__repr__ = message.__repr__
        self.children = {}  # cache for "get children" command
        self.children_lock = threading.RLock()

    def list_repr(self):
        if self.paused:
            extra = f" {self.name} {self.pause_reason}\n    {self.location.short_repr()}"
        else:
            extra = ""
        return f"{self.id} {self.payload}{extra}"

    def get_children(self, value_id):
        child = None
        with self.children_lock:
            if value_id in self.children:
                child = self.children[value_id]
        if child is None:
            child = self.debugger.get_children(thread_id=self.id, value_id=value_id)
            with self.children_lock:
                if value_id not in self.children:
                    self.children[value_id] = child
                else:
                    child = self.children[value_id]
        return child


class ThreadList(list):
    """Represents a list of threads"""
    def __init__(self, debugger):
        with debugger.status_lock:
            threads = [debugger.threads[tid] for tid in sorted(debugger.threads)]
        list.__init__(self, threads)

    def __repr__(self):
        return "\n".join(thread.list_repr() for thread in self)


class StarlarkBreakpoint:
    """Represents a breakpoint"""
    def __init__(self, message):
        self.message = message
        self.location = Location(message.location)
        self.expression = message.expression

    def list_repr(self):
        postfix = ""
        if self.expression:
            postfix = f"\n    expression={repr(self.expression)}"
        return f"{self.location.short_repr()}{postfix}"


class BreakpointList(list):
    """Represents a list of breakpoints"""
    def __init__(self, debugger):
        with debugger.status_lock:
            bps = [debugger.breakpoints[bpid] for bpid in sorted(debugger.breakpoints)]
        list.__init__(self, bps)

    def __repr__(self):
        return "\n".join(bp.list_repr() for bp in self)


class StarlarkBinding:
    """Base class for the python representation of Starlark objects"""
    def __init__(self, debugger, thread, message):
        self._debugger = debugger
        self._thread = thread
        self._message = message
        self._label = message.label
        self._type = message.type
        self._id = message.id
        self._has_children = message.has_children

    def __verbose_repr__(self):
        name = self.__class__.__name__
        prefix = f"<{name} {self._label} type={self._type} id={self._id}"
        return f"{prefix} has_children={self._has_children} {repr_type(self)}>"

    def __repr__(self):
        if self._debugger.options.verbose:
            return self.__verbose_repr__()
        else:
            return self.__repr_type__()


class UnknownType(StarlarkBinding):
    """Rerpresent a Starlark object that we don't have a python representation for"""
    def __repr_type__(self):
        return self._message.description

    def __hash__(self):  # To support dict keys on types not yet wrapped that'd otherwise crash
        return hash((self._thread.id, self._id, self._message.description))

    def __repr__(self):
        return self.__verbose_repr__()


class DictBinding(StarlarkBinding, dict):
    """Python representation of a Starlark dict object"""
    def __init__(self, debugger, thread, message):
        StarlarkBinding.__init__(self, debugger, thread, message)
        if message.id == 0:
            assert message.description == "{}"
        else:
            for child in thread.get_children(message.id).children:
                key_message, value_messge = thread.get_children(child.id).children
                key = binding_from_message(debugger, thread, key_message)
                value = binding_from_message(debugger, thread, value_messge)
                self[key] = value

    def __repr_type__(self):
        return "{%s}" % ", ".join("%s: %s" % (repr_type(k), repr_type(v)) for k, v in self.items())


class ListBinding(StarlarkBinding, list):
    """Python representation of a Starlark list object"""
    def __init__(self, debugger, thread, message):
        StarlarkBinding.__init__(self, debugger, thread, message)
        if message.id == 0:
            assert message.description == "[]"
        else:
            for child in thread.get_children(message.id).children:
                item = binding_from_message(debugger, thread, child)
                self.append(item)

    def __repr_type__(self):
        return "[%s]" % ", ".join(repr_type(item) for item in self)


class StringBinding(StarlarkBinding, str):
    """Python representation of a Starlark string object"""
    def __new__(cls, debugger, thread, message):
        return str.__new__(cls, message.description)

    def __repr_type__(self):
        return str.__repr__(self)


class StructBinding(StarlarkBinding):
    """Python representation of a Starlark struct object"""
    _display_name = "struct"

    def __init__(self, debugger, thread, message):
        StarlarkBinding.__init__(self, debugger, thread, message)
        self._items = {
            child.label: binding_from_message(debugger, thread, child)
            for child in thread.get_children(message.id).children
        }
        for key, value in self._items.items():
            setattr(self, key, value)

    def __repr_type__(self):
        item_msg = ", ".join("%s=%s" % (k, repr_type(v)) for k, v in self._items.items())
        return "%s(%s)" % (self._display_name, item_msg)


class LabelBinding(StructBinding, str):
    """Python representation of a Starlark label object"""
    def __new__(cls, debugger, thread, message):
        obj = str.__new__(cls, eval(message.description, {"Label": lambda x: x}))
        return obj

    def __repr_type__(self):
        return "Label(%s)" % str.__repr__(self)


class RuleContextBinding(StructBinding):
    """Python representation of a Starlark rule context object"""
    def __repr_type__(self):
        return "<rule context for %s>" % str(self._items["label"])


class TargetBinding(StructBinding):
    """Python representation of a Starlark target object"""
    def __repr_type__(self):
        # Providers not available due to https://github.com/bazelbuild/bazel/issues/13380
        # TODO: Add keys when above issue is solved
        return "<target %s, keys=[]>" % str(self._items["label"])


[struct_type] = [lambda name: type(name, (StructBinding,), {"_display_name": name})]
binding_class = collections.defaultdict(lambda: UnknownType, {
    "bool": lambda debugger, thread, message: bool(message.description),
    "ctx": RuleContextBinding,
    "depset": struct_type("depset"),
    "dict": DictBinding,
    "list": ListBinding,
    "string": StringBinding,
    "struct": StructBinding,
    "CcCompilationOutputs": struct_type("CcCompilationOutputs"),
    "CcInfo": struct_type("CcInfo"),
    "CompilationContext": struct_type("CompilationContext"),
    "File": struct_type("File"),
    "Label": LabelBinding,
    "LinkingContext": struct_type("LinkingContext"),
    "NoneType": lambda debugger, thread, messages: None,
    "Target": TargetBinding,
})


def binding_from_message(debugger, thread, message):
    """Helper function to create python representation of a debugger message"""
    return binding_class[message.type](debugger, thread, message)


def repr_type(obj):
    """Helper function to get the string representation of a python Starlark binding object"""
    return obj.__repr_type__() if hasattr(obj, "__repr_type__") else repr(obj)


class StarlarkScope(dict):
    """Represents a scope defined by the Starlark debugger API"""
    def __init__(self, debugger, thread, message):
        self.debugger = debugger
        self.thread = thread
        self.message = message
        self.name = message.name
        self.bindings = {b.label: b for b in message.binding}

    def __getitem__(self, key):
        if key in self.bindings and not dict.__contains__(self, key):
            message = self.bindings[key]
            self[key] = binding_from_message(self.debugger, self.thread, message)
        return dict.__getitem__(self, key)

    def __contains__(self, key):
        return key in self.bindings

    def __len__(self):
        return len(self.bindings)

    def keys(self):
        return self.bindings.keys()

    def __repr__(self):
        keys = " ".join(repr(x) for x in self.bindings)
        return f"<Scope {self.name} keys=[{keys}]>"


class StarlarkFrame(dict):
    """Represents a frame defined by the Starlark debugger API"""
    def __init__(self, debugger, thread, message):
        self.deugger = debugger
        self.thread = thread
        self.message = message
        self.location = Location(message.location)
        self.function_name = message.function_name
        dict.__init__(self, ((scope.name, StarlarkScope(debugger, thread, scope)) for scope in message.scope))

    def list_repr(self):
        return "%s %s %s" % (
            self.function_name,
            " ".join("%s:%d" % (name, len(scope)) for name, scope in self.items()),
            self.location.short_repr(),
        )

    def __repr__(self):
        return "<Frame %s>" % self.list_repr()

    def evaluate(self, expression):
        return eval(expression, self["global"], self["local"])


class FrameList(list):
    """Represents a list of frames defined by the Starlark debugger API"""
    def __init__(self, debugger, thread, message):
        self.debugger = debugger
        self.message = message
        self.thread = thread
        frames = [StarlarkFrame(debugger, thread, frame_message) for frame_message in message.frame]
        list.__init__(self, frames)

    def __repr__(self):
        return "\n".join("%d:%s" % (i, frame.list_repr()) for i, frame in enumerate(self))


class EventHandler:
    """Basic event framework where consumers can wait for events from producers

    Events can be any hashable object, normally debugger status update strings like
    "thread_paused" or "thread_continued".

    Consumers create an EventListener class, register it on wanted events and use the wait method
    Producers notify all listeners registered on the applicable events
    """
    class EventListener:
        def __init__(self):
            self.condition = threading.Condition()
            self.is_triggered = False
            self.is_shut_downed = False
            self.listener_id = None
            self.events = set()

        def wait(self, timeout=None, expect_shutdown=False):
            t0 = timeit.default_timer()
            with self.condition:
                while not self.is_triggered and not self.is_shut_downed:
                    if timeout is not None:
                        elapsed = timeit.default_timer() - t0
                        wait_timeout = timeout - elapsed
                        if wait_timeout <= 0.0:
                            break
                    else:
                        wait_timeout = timeout
                    self.condition.wait(timeout=wait_timeout)
                    if self.is_shut_downed and not expect_shutdown:
                        raise Exception("Debugger has shut down")

        def notify(self, shutdowned=False):
            if shutdowned:
                self.is_shut_downed = True
            else:
                self.is_triggered = True
            self.condition.notify()

    def __init__(self):
        self.listener_lock = threading.RLock()
        self.listeners = {}
        self.event_listeners = collections.defaultdict(set)
        self.next_listener_id = 1
        self.is_shut_downed = False

    def shutdown(self):
        with self.listener_lock:
            self.is_shut_downed = True
            for listener_id, listener in self.listeners.items():
                with listener.condition:
                    listener.notify(shutdowned=True)
                listener.events.clear()
            self.event_listeners.clear()

    def register(self, listener, event):
        with self.listener_lock:
            if self.is_shut_downed:
                raise Exception("Cannot register event listeners after shutdown")
            listener.events.add(event)
            if listener.listener_id is None:
                listener.listener_id = self.next_listener_id
                self.next_listener_id += 1
            self.event_listeners[event].add(listener.listener_id)
            self.listeners[listener.listener_id] = listener

    def unregister(self, listener, event=None):
        with self.listener_lock:
            if self.is_shut_downed:
                raise Exception("Cannot unregister event listeners after shutdown")
            if event is None and len(listener.events) > 0:
                for evt in listener.events:
                    self.event_listeners[evt].remove(listener.listener_id)
                listener.events.clear()
                del self.listeners[listener.listener_id]
            elif event in listener.events:
                self.event_listeners[event].remove(listener)
                listener.events.remove(event)
                if len(listener.events) == 0:
                    del self.listeners[listener.listener_id]

    def get_event_listeners(self, event):
        result = []
        with self.listener_lock:
            if not self.is_shut_downed:
                for listener_id in self.event_listeners[event]:
                    result.append(self.listeners[listener_id])
        return result


class DebuggerOptions(object):
    """Thread safe object to store debugger options with type-checking
    """
    class Option:
        def __init__(self, value):
            self.value = value
            self.lock = threading.RLock()

    class BoolOption(Option):
        def validate(self, value):
            return isinstance(value, bool)

    def __init__(self, **kwargs):
        self._lock = threading.RLock()
        self._options = {
            "verbose": self.BoolOption(False),
        }

    def __getattr__(self, key):
        with self.__dict__["_lock"]:
            if key in self.__dict__["_options"]:
                return self.__dict__["_options"][key].value
            return self.__dict__[key]

    def __setattr__(self, key, value):
        if "_options" in self.__dict__:
            with self.__dict__["_lock"]:
                if key in self.__dict__["_options"]:
                    option = self.__dict__["_options"][key]
                    if not option.validate(value):
                        raise Exception("Unexpected value %r for %s %r" % (
                            value, option.__class__.__name__, key))
                    option.value = value
                else:
                    self.__dict__[key] = value
        else:
            self.__dict__[key] = value

    def __repr__(self):
        return "<%s %s>" % (
            self.__class__.__name__,
            ", ".join("%s=%s" % (name, option.value) for name, option in self._options.items()),
        )


class StarlarkDebugger:
    """Main Starlark debugger API

    This class operates through the following mechanisms:
    * An EventHandler class for thread communication (wait/notify)
    * A sendqueue for pending requests to send
    * A RequestDispatcher thread that consumes the sendqueue
    * An EventReceiver thread that listens for incoming messages and notifies users
    * A StatusUpdater thread that listens to server status messages like errors and
      thread status updates
    """
    @staticmethod
    def add_parser_arguments(parser):
        parser.add_argument('--host', dest='host', default='127.0.0.1', required=False,
                            help='Bazel server (default 127.0.0.1).')
        parser.add_argument('--port', type=int, dest='port', default=7200, required=False,
                            help='Bazel server port (default 7200).')
        parser.add_argument('--base_path', dest='base_path',
                            help='Base path to use for relative breakpoint paths.')
        parser.add_argument('--request_log_oputput_path', dest='request_log_oputput_path',
                            help='Debugger debug only. Store statistics about requests.')

    @staticmethod
    def from_parser_args(args):
        debugger = StarlarkDebugger(
            host=args.host,
            port=args.port,
            base_path=args.base_path,
            request_log_oputput_path=args.request_log_oputput_path,
        )
        return debugger

    class Request:
        """Bookkeeping information for a request sent to the debugger service
        """
        def __init__(self, payload, message, sticky=False, on_debug_message_sent=None):
            self.condition = threading.Condition()
            self.error = None
            self.payload = payload
            self.message = message
            self.events = []
            self.sticky = sticky
            self.on_debug_message_sent = on_debug_message_sent
            self.log_data = {}

        log_items = [
            ("t_send", lambda self: self.log_data["t_sent"] - self.log_data["t_start"]),
            ("t_latency", lambda self: self.log_data["t_recv_start"] - self.log_data["t_sent"]),
            ("t_recv", lambda self: self.log_data["t_recv_end"] - self.log_data["t_recv_start"]),
            ("t_return", lambda self: self.log_data["t_end"] - self.log_data["t_recv_end"]),
            ("bytes", lambda self: sum(e.message_size for e in self.events)),
            ("messages", lambda self: len(self.events)),
            ("payload", lambda self: self.payload),
        ]

        @staticmethod
        def write_log_header(request_log):
            if request_log is not None:
                request_log.write("\t".join(tag for tag, fn in StarlarkDebugger.Request.log_items) + "\n")

        def add_log_data(self, tag, value):
            self.log_data[tag] = value

        def write_log_data(self, request_log):
            if request_log is not None and "t_start" in self.log_data and "t_end" in self.log_data:
                request_log.write("\t".join(str(fn(self)) for tag, fn in StarlarkDebugger.Request.log_items) + "\n")

    class ThreadEventListener(EventHandler.EventListener):
        """Used by methods that need to wait for thread status updates"""
        def __init__(self, *, thread_id, sticky=False):
            EventHandler.EventListener.__init__(self)
            self.thread_id = thread_id
            self.sticky = sticky
            self.threads = []

    class DebugThread(threading.Thread):
        """Base class for debugger threads"""
        def __init__(self, debugger):
            threading.Thread.__init__(self)
            self.daemon = True
            self.debugger = debugger

    class RequestDispatcher(DebugThread):
        """Thread that sends requests to the debugger service"""
        def run(self):
            while True:
                request = self.debugger.sendqueue.get()
                with self.debugger.status_lock:
                    is_shut_downed = self.debugger.is_shut_downed
                if is_shut_downed:
                    break
                with self.debugger.request_lock:
                    if self.debugger.requests is not None:
                        sequence_number = self.debugger.next_sequence_number
                        self.debugger.next_sequence_number += 1
                        assert sequence_number not in self.debugger.requests
                        self.debugger.requests[sequence_number] = request
                with request.condition:
                    request.sequence_number = sequence_number
                    request.debug_request = starlark_debugging.DebugRequest(
                        sequence_number=sequence_number, **{request.payload: request.message})
                    msg = request.debug_request.SerializeToString()
                    encoded_len = encode_varint64(len(msg))
                    if request.on_debug_message_sent:
                        with self.debugger.status_lock:
                            # waiting for breakpoints to trigger, needs to be under lock
                            # to avoid race conditions
                            request.on_debug_message_sent()
                            self.debugger.socket.send(encoded_len)
                            self.debugger.socket.send(msg)
                    else:
                        self.debugger.socket.send(encoded_len)
                        self.debugger.socket.send(msg)
                    request.add_log_data("t_sent", timeit.default_timer())

    class EventReceiver(DebugThread):
        """Thread that listens for incoming messages from the debugger service"""
        class ReceivedEvent:
            def __init__(self, message, message_size):
                self.message = message
                self.message_size = message_size

        def run(self):
            while True:
                n, t_recv_start = recv_varint64(self.debugger.socket.recv)
                if n < 0:
                    self.debugger.shutdown()
                    break
                msg_parts = []
                to_read = n
                while to_read > 0:
                    msg_part = self.debugger.socket.recv(to_read)
                    if len(msg_part) == 0:
                        logger.error(f"Dropped incomplete message: received {n_read} bytes, expected {n}")
                        break
                    to_read -= len(msg_part)
                    msg_parts.append(msg_part)
                if to_read > 0:
                    break
                msg = b"".join(msg_parts)
                t_recv_end = timeit.default_timer()
                self.debugger.messages.append(msg)
                event = starlark_debugging.DebugEvent.FromString(msg)

                request = None
                with self.debugger.request_lock:
                    if event.sequence_number in self.debugger.requests:
                        request = self.debugger.requests[event.sequence_number]
                        if not request.sticky:
                            del self.debugger.requests[event.sequence_number]
                if request is None:
                    self.debugger.unhandled_events.put(event)
                    payload = event.WhichOneof("payload")
                    logger.warn(f"Unhandled event: sequenceNumber={event.sequenceNumber} payload={payload}")
                else:
                    with request.condition:
                        request.add_log_data("t_recv_start", t_recv_start)
                        request.add_log_data("t_recv_end", t_recv_end)
                        request.events.append(self.ReceivedEvent(event, len(msg)))
                        request.condition.notify()

    class StatusUpdater(DebugThread):
        """Thread that listens for status messages from the debugger service"""
        def run(self):
            while True:
                with self.debugger.status_request.condition:
                    self.debugger.status_request.condition.wait()
                    events = self.debugger.status_request.events[:]
                    self.debugger.status_request.events[:] = []
                with self.debugger.status_lock:
                    is_shut_downed = self.debugger.is_shut_downed
                if is_shut_downed:
                    break
                for received_event in events:
                    event = received_event.message
                    payload = event.WhichOneof("payload")
                    if payload is None:
                        self.debugger.eventhandler.shutdown()
                        continue  # Debugger exists
                    response = getattr(event, payload)
                    if payload == "thread_paused" or payload == "thread_continued":
                        thread = StarlarkThread(self.debugger, payload, response)
                        new_base_path = None
                        with self.debugger.status_lock:
                            if self.debugger.base_path is None and thread.base_path is not None:
                                new_base_path = thread.base_path
                                self.debugger.base_path = new_base_path
                            self.debugger.threads[thread.id] = thread
                        if new_base_path:
                            logger.info(f"base_path: {new_base_path}")
                        for listener in self.debugger.eventhandler.get_event_listeners(payload):
                            if listener.thread_id == 0 or listener.thread_id == thread.id:
                                with listener.condition:
                                    listener.threads.append(thread)
                                    listener.notify()
                                if not listener.sticky:
                                    self.debugger.eventhandler.unregister(listener)
                    elif payload == "error":
                        logger.error(f"Bazel error: f{repr(event.error.message)}")
                    else:
                        self.debugger.unhandled_events.put(event)
                        payload = event.WhichOneof("payload")
                        logger.warn(f"Unhandled event: sequenceNumber={event.sequenceNumber} payload={payload}")

    def __init__(self, host='127.0.0.1', port=7200, base_path=None, request_log_oputput_path=None):
        self.host = host
        self.port = port
        self.request_log = None
        self.request_log_oputput_path = request_log_oputput_path
        self.options = DebuggerOptions()
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sendqueue = queue.Queue()
        self.unhandled_events = queue.Queue()
        self.is_shut_downed = False
        self.status_request = self.Request(None, None, sticky=True)
        self.status_lock = threading.RLock()
        self.request_lock = threading.RLock()
        self.init_cond = threading.Condition()
        self.threads = {}
        self.breakpoints = {}
        self.messages = []
        self.eventhandler = EventHandler()
        self.next_sequence_number = 1
        self.requests = {
            0: self.status_request,
        }
        self.base_path = base_path  # Will potentially be set by status thread if None
        self.status_updater = self.StatusUpdater(self)
        self.request_dispatcher = self.RequestDispatcher(self)
        self.event_receiver = self.EventReceiver(self)

    def initialize(self):
        """Connects to the debugger service and starts the debugger threads"""
        if self.request_log_oputput_path is not None:
            self.request_log = open(self.request_log_oputput_path, "w")
            self.Request.write_log_header(self.request_log)
        init_event_listener = self.ThreadEventListener(thread_id=0)
        self.eventhandler.register(init_event_listener, "thread_paused")
        self.socket.connect((self.host, self.port))
        logger.info(f"Starlark debugger {self.host}:{self.port} connnected")
        if self.base_path is not None:
            logger.info(f"base_path: {self.base_path}")
        self.status_updater.start()
        self.request_dispatcher.start()
        self.event_receiver.start()
        init_event_listener.wait(timeout=1.0)
        if self.base_path is None:
            logger.info(f"base_path: {self.base_path}")
        logger.info("Starlark debugger initialized.")

        for thread in init_event_listener.threads:
            logger.info(thread.list_repr())

    def shutdown(self):
        """Tells all threads to exit and flushes potential log writes"""
        with self.status_lock:
            is_shut_downed = self.is_shut_downed
            if not is_shut_downed:
                self.is_shut_downed = True
        if not is_shut_downed:
            try:
                self.eventhandler.shutdown()
            except Exception:
                traceback.print_exc()
            try:
                with self.status_request.condition:
                    self.status_request.condition.notify_all()
            except Exception:
                traceback.print_exc()
            try:
                self.sendqueue.put(None)
            except Exception:
                traceback.print_exc()
            try:
                with self.status_lock:
                    request_log = self.request_log
                    self.request_log = None
                if request_log is not None:
                    request_log.close()
            except Exception:
                traceback.print_exc()

    def _send_request(self, payload, message, timeout=None, **kwargs):
        """Helper function to send a request to the debugger service and wait for response"""
        request = self.Request(payload, message, **kwargs)
        request.add_log_data("t_start", timeit.default_timer())
        self.sendqueue.put(request)
        with request.condition:
            request.condition.wait(timeout=timeout)
        request.add_log_data("t_end", timeit.default_timer())
        request.write_log_data(self.request_log)
        if len(request.events) != 1:
            raise Exception("Response from %s: expected 1 event, got %d" % (
                payload, len(request.events)))
        event = request.events[0].message
        event_payload = event.WhichOneof("payload")
        if event_payload == "error":
            raise Exception(f"Bazel error: {repr(event.error.message)}")
        assert event_payload == payload
        message = getattr(event, payload)
        return message

    def set_breakpoints(self, breakpoints, expression=""):
        """Send SetBreakpointsRequest to debug server

        Sends one or more breakpoints to server.

        Args:
            breakpoints:    Breakpoint reference or list of string breakpoint references
                            Breakpoint reference (string): <file>:<line>[:<col>]
            expression:     (optional) String with condition to trigger

        Returns:
            SetBreakpointsResponse message
        """
        if isinstance(breakpoints, str):
            breakpoints = [breakpoints]
        messages = []
        for bp_ref in breakpoints:
            location = Location.from_ref(self, bp_ref)
            message = starlark_debugging.Breakpoint(
                location=location.message,
                expression=expression
            )
            messages.append(message)
            bp = StarlarkBreakpoint(message)
            with self.status_lock:
                self.breakpoints[bp.location.short_repr()] = bp
        req = starlark_debugging.SetBreakpointsRequest(breakpoint=messages)
        return self._send_request("set_breakpoints", req)

    def _register_event_listener(self, listener, event):
        """Callback function to register a listener when the message is sent to the server"""
        self.eventhandler.register(listener, event)

    def continue_execution(self, thread_id=0, stepping=0, wait_for_breakpoint=False):
        """Send ContinueExecutionRequest to debug server

        Args:
            thread_id:              Thread to continue or 0 for all threads (default)
            stepping:               Set to 1 to step, 0 to run (default)
            wait_for_breakpoint:    True - Wait for thread to pause (or any thread if thread_id is 0)
                                    False - Return as soon as response is received (default)

        Returns:
            ContinueExecutionResponse message
        """
        req = starlark_debugging.ContinueExecutionRequest(thread_id=thread_id, stepping=stepping)
        on_debug_message_sent = None
        if wait_for_breakpoint:
            listener = self.ThreadEventListener(thread_id=thread_id)
            on_debug_message_sent = functools.partial(self._register_event_listener, listener, "thread_paused")
        try:
            message = self._send_request("continue_execution", req, on_debug_message_sent=on_debug_message_sent)
            if wait_for_breakpoint:
                listener.wait()
        finally:
            if wait_for_breakpoint:
                self.eventhandler.unregister(listener)
        return message

    def continue_execution_and_wait_for_breakpoint(self, thread_id=0, stepping=0):
        """Send ContinueExecutionRequest to debug server and wait for thread to pause

        If thread_id is 0, wait for any thread to pause.

        Args:
            thread_id:  Thread to continue or 0 for all threads (default)
            stepping:   Set to 1 to step, 0 to run (default)

        Returns:
            ContinueExecutionResponse message
        """
        return self.continue_execution(thread_id=thread_id, stepping=stepping, wait_for_breakpoint=True)

    def evaluate(self, thread_id, statement):
        """Send EvaluateRequest to debug server

        Args:
            thread_id:  Thread to evaluate statement in
            statement:  Statement to evaluate

        Returns:
            EvaluateResponse message
        """
        req = starlark_debugging.EvaluateRequest(thread_id=thread_id, statement=statement)
        return self._send_request("evaluate", req)

    def list_frames(self, thread_id):
        """Send ListFramesRequest to debug server

        Args:
            thread_id:  Thread to get frames from

        Returns:
            FrameList object with thread frames
        """
        req = starlark_debugging.ListFramesRequest(thread_id=thread_id)
        thread = None
        with self.status_lock:
            if thread_id in self.threads:
                thread = self.threads[thread_id]
        return FrameList(self, thread, self._send_request("list_frames", req))

    def start_debugging(self):
        """Send StartDebuggingRequest to debug server

        This seems to continue all threads
        TODO: Find out what it really does besides just continuing threads

        Returns:
            StartDebuggingResponse message
        """
        req = starlark_debugging.StartDebuggingRequest()
        return self._send_request("start_debugging", req)

    def pause_thread(self, thread_id=0):
        """Send PauseThreadRequest to debug server

        Args:
            thread_id:  Thread to pause or 0 to pause all threads (default)

        Returns:
            PauseThreadResponse message
        """
        req = starlark_debugging.PauseThreadRequest(thread_id=thread_id)
        return self._send_request("pause_thread", req)

    def get_children(self, thread_id, value_id):
        """Send GetChildrenRequest to debug server

        Args:
            thread_id:  Thread to get children from
            value_id:   Value id from FrameList object (ListFramesResponse message)

        Returns:
            GetChildrenResponse message
        """
        req = starlark_debugging.GetChildrenRequest(thread_id=thread_id, value_id=value_id)
        return self._send_request("get_children", req)

    def list_threads(self):
        """Lists all threads known to the debugger with their status

        Returns:
            ThreadList object
        """
        return ThreadList(self)

    def list_breakpoints(self):
        """Lists all breakpoints known to the debugger

        Returns:
            BreakpointList object
        """
        return BreakpointList(self)

    def set_options(self, **kwargs):
        """Set debugger options
        """
        for key, value in kwargs.items():
            setattr(self.options, key, value)

    def iterate_paused_threads(self):
        """Helper function to iterate over all breakpoint stops

        If run directly after initializing debugger and breakpoints it'll
        run all threads and yield the threads as they pause to let user examine
        its state, when user returns thread is continued.
        """
        listener = self.ThreadEventListener(thread_id=0, sticky=True)
        self.eventhandler.register(listener, "thread_paused")
        try:
            self.continue_execution()
            with listener.condition:
                is_shut_downed = listener.is_shut_downed
            while not is_shut_downed:
                with listener.condition:
                    listener.wait(expect_shutdown=True)
                    is_shut_downed = listener.is_shut_downed
                    threads = listener.threads[:]
                    listener.threads[:] = []
                if not is_shut_downed:
                    for thread in threads:
                        yield thread
                        self.continue_execution(thread_id=thread.id)
        finally:
            self.eventhandler.unregister(listener)


class Command:
    def __init__(self, short, name, fn, *args, **kwargs):
        self.short = short
        self.name = name
        self.orig_func = fn
        self.fn = functools.partial(fn, *args, **kwargs)

    def __repr__(self):
        return repr(self.__call__())

    def __call__(self, *args, **kwargs):
        return self.fn(*args, **kwargs)


def define_command(short, name, function, *args, **kwargs):
    cmd = Command(short, name, function, *args, **kwargs)
    globals()[short] = cmd
    commands.append(cmd)
    return cmd


class ObjectHelper:
    def __init__(self, obj):
        self.obj = obj

    def __repr__(self):
        obj = self.obj
        if isinstance(obj, Command):
            obj = obj.orig_func
        __help__(obj)


class InteractiveModeHelp:
    class ConsoleHelp:
        pass

    console_help = ConsoleHelp()

    def __call__(self, obj=console_help):
        if isinstance(obj, self.ConsoleHelp):
            __help__()
        else:
            return ObjectHelper(obj)

    def __repr__(self):
        msg = "\n".join(f"{cmd.short:8}{cmd.name}" for cmd in commands)
        msg += "\nType help for this text, help() for interactive help, or help(object) for help about object."
        return msg


def setup_interactive_mode(debugger):
    globals()["__help__"] = help
    globals()["debugger"] = debugger
    globals()["commands"] = []
    define_command("b", "set_breakpoints", debugger.set_breakpoints)
    define_command("c", "continue_execution", debugger.continue_execution)
    define_command("cw", "continue_execution_and_wait_for_breakpoint",
                   debugger.continue_execution_and_wait_for_breakpoint)
    define_command("e", "evaluate", debugger.evaluate)
    define_command("lf", "list_frames", debugger.list_frames)
    define_command("sd", "start_debugging", debugger.start_debugging)
    define_command("pt", "pause_thread", debugger.pause_thread)
    define_command("gc", "get_children", debugger.get_children)
    define_command("lt", "list_threads", debugger.list_threads)
    define_command("lb", "list_breakpoints", debugger.list_breakpoints)
    globals()["help"] = InteractiveModeHelp()
    print(repr(help))


def main():
    """"Main will create a debugger instance and if run in interactive mode setup
    a debug environment.
    """
    errorcode = 0
    debug = True
    debugger = None
    try:
        parser = argparse.ArgumentParser()
        StarlarkDebugger.add_parser_arguments(parser)
        parser.add_argument('--debug', dest='debug', action='store_true',
                            help='Use this flag in combination with interactive mode (python -i) to'
                                 ' get a global debug environment.')
        args = parser.parse_args()
        debug = args.debug
        debugger = StarlarkDebugger.from_parser_args(args)
        debugger.initialize()

        if IS_INTERACTIVE:
            setup_interactive_mode(debugger)
    finally:
        if IS_INTERACTIVE:
            if debug:
                import inspect
                globals().update(inspect.currentframe().f_locals)
        else:
            if debugger is not None:
                debugger.shutdown()
    return errorcode


if __name__ == '__main__':
    EXITCODE = main()
    if not IS_INTERACTIVE:
        sys.exit(EXITCODE)
