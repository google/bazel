#!/usr/bin/env python3

"""
This is a helper script to generate the log4j2 test files.

Run it like this from the root of the repository:
python3 src/tools/singlejar/data/log4j2_plugins_gen.py
"""

import argparse
import struct
import os
import sys
from io import BytesIO
import zipfile


class PluginEntry:
    def __init__(self, key, class_name, name, printable, defer, category):
        self.key = key
        self.class_name = class_name
        self.name = name
        self.printable = printable
        self.defer = defer
        self.category = category

    def __repr__(self):
        return (
            f"PluginEntry(key={self.key}, class_name={self.class_name}, "
            f"name={self.name}, printable={self.printable}, defer={self.defer}, category={self.category})"
        )


def read_utf_string(buffer):
    length = struct.unpack(">H", buffer.read(2))[0]
    return buffer.read(length).decode("utf-8")


def write_utf_string(buffer, string):
    encoded = string.encode("utf-8")
    buffer.write(struct.pack(">H", len(encoded)))
    buffer.write(encoded)


def write_cache_file(categories, output_path):
    buffer = BytesIO()
    buffer.write(struct.pack(">I", len(categories)))

    for category, entries in categories.items():
        write_utf_string(buffer, category)
        buffer.write(struct.pack(">I", len(entries)))

        for key, entry in entries.items():
            write_utf_string(buffer, entry.key)
            write_utf_string(buffer, entry.class_name)
            write_utf_string(buffer, entry.name)
            buffer.write(struct.pack(">?", entry.printable))
            buffer.write(struct.pack(">?", entry.defer))

    with open(output_path, "wb") as f:
        f.write(buffer.getvalue())


def load_cache_files_from_bytes(byte_data, consolidated_plugins):
    buffer = BytesIO(byte_data)

    count = struct.unpack(">I", buffer.read(4))[0]
    for _ in range(count):
        category = read_utf_string(buffer)
        category_map = consolidated_plugins.setdefault(category, {})

        entries = struct.unpack(">I", buffer.read(4))[0]
        for _ in range(entries):
            key = read_utf_string(buffer)
            class_name = read_utf_string(buffer)
            name = read_utf_string(buffer)
            printable = struct.unpack(">?", buffer.read(1))[0]
            defer = struct.unpack(">?", buffer.read(1))[0]

            if key in category_map:
                print(
                    f"Warning: Collision detected for key '{key}' in category '{category}'. Existing entry will be overwritten."
                )

            entry = PluginEntry(key, class_name, name, printable, defer, category)
            category_map[key] = entry


def create_jar_with_data(dat_file_path, jar_file_path):
    jar_internal_path = (
        "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"
    )
    with zipfile.ZipFile(jar_file_path, "w", zipfile.ZIP_DEFLATED) as jar:
        jar.write(dat_file_path, arcname=jar_internal_path)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_dir", default="src/tools/singlejar/data")
    parser.add_argument("--dump")
    args = parser.parse_args()

    if args.dump:
        with open(args.dump, "rb") as f:
            byte_data = f.read()

        consolidated_plugins = {}
        load_cache_files_from_bytes(byte_data, consolidated_plugins)
        for category, entries in consolidated_plugins.items():
            print(f"Category: {category}")
            for key, entry in entries.items():
                print(f"  {key}: {entry}")
        sys.exit(0)

    values = [
        (
            "log4j2_plugins_set_1.jar",
            {
                "cat1": {
                    "key1": PluginEntry("key1", "class1", "name1", True, False, "cat1"),
                    "key2": PluginEntry("key2", "class2", "name2", False, True, "cat1"),
                },
                "cat2": {
                    "key3": PluginEntry("key3", "class3", "name3", True, True, "cat2")
                },
            },
        ),
        (
            "log4j2_plugins_set_2.jar",
            {
                "cat1": {
                    "key11": PluginEntry(
                        "key11", "class1", "name1", True, False, "cat1"
                    ),
                    "key12": PluginEntry(
                        "key12", "class2", "name2", False, True, "cat1"
                    ),
                },
                "cat3": {
                    "key13": PluginEntry(
                        "key13", "class3", "name3", True, True, "cat3"
                    ),
                },
            },
        ),
    ]

    for v in values:
        dat = os.path.join(args.data_dir, "temp.dat")
        write_cache_file(v[1], dat)
        create_jar_with_data(dat, os.path.join(args.data_dir, v[0]))
        os.remove(dat)

    write_cache_file(
        {
            "cat1": {
                "key1": PluginEntry("key1", "class1", "name1", True, False, "cat1"),
                "key11": PluginEntry("key11", "class1", "name1", True, False, "cat1"),
                "key12": PluginEntry("key12", "class2", "name2", False, True, "cat1"),
                "key2": PluginEntry("key2", "class2", "name2", False, True, "cat1"),
            },
            "cat2": {
                "key3": PluginEntry("key3", "class3", "name3", True, True, "cat2"),
            },
            "cat3": {
                "key13": PluginEntry("key13", "class3", "name3", True, True, "cat3"),
            },
        },
        os.path.join(args.data_dir, "log4j2_plugins_set_result.dat"),
    )
