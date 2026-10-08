#!/usr/bin/env python3
"""Fetch pinned portable packaging tools; this never installs them in the operating system."""

import argparse
import hashlib
import os
from pathlib import Path
import tempfile
import urllib.request

MICROSOFT = "https://aka.ms/download-jdk/"
TOOLS = {
    "windows": [
        ("wix314-binaries.zip",
         "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip",
         "6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31"),
    ],
    "macos": [
        ("microsoft-jdk-21.0.11-linux-x64.tar.gz", MICROSOFT + "microsoft-jdk-21.0.11-linux-x64.tar.gz",
         "1d58b1335d019bfe1c7c56979c927d0f51222c6dd41c33772d8396ea6cd409c1"),
        ("microsoft-jdk-21.0.11-macos-x64.tar.gz", MICROSOFT + "microsoft-jdk-21.0.11-macos-x64.tar.gz",
         "c0c2db2d9ec201ffec96cfa7a39b5e1ad486b6acf421a153651789da6a565fa3"),
        ("microsoft-jdk-21.0.11-macos-aarch64.tar.gz", MICROSOFT + "microsoft-jdk-21.0.11-macos-aarch64.tar.gz",
         "22eac07819b9fe6670cb026fd3fd7bddd31938ea3adad7b722ac0631d76d9667"),
        ("CLTools_macOSNMOS_SDK.pkg",
         "https://swcdn.apple.com/content/downloads/52/01/082-41241-A_0747ZN8FHV/dectd075r63pppkkzsb75qk61s0lfee22j/CLTools_macOSNMOS_SDK.pkg",
         "ba3453d62b3d2babf67f3a4a44e8073d6555c85f114856f4390a1f53bd76e24a"),
        ("apple-codesign-0.29.0-x86_64-unknown-linux-musl.tar.gz",
         "https://github.com/indygreg/apple-platform-rs/releases/download/apple-codesign%2F0.29.0/apple-codesign-0.29.0-x86_64-unknown-linux-musl.tar.gz",
         "dbe85cedd8ee4217b64e9a0e4c2aef92ab8bcaaa41f20bde99781ff02e600002"),
    ],
}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(target):
    root = Path(__file__).resolve().parent / "target" / "tools"
    root.mkdir(parents=True, exist_ok=True)
    for filename, url, checksum in TOOLS[target]:
        destination = root / filename
        if destination.is_file() and digest(destination) == checksum:
            continue
        with tempfile.NamedTemporaryFile(dir=root, suffix=".download", delete=False) as output:
            pending = Path(output.name)
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "Wisprail-packaging"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    size = 0
                    while block := response.read(1024 * 1024):
                        size += len(block)
                        if size > 1024 * 1024 * 1024:
                            raise ValueError("Packaging tool exceeds 1 GiB")
                        output.write(block)
                output.flush()
                os.fsync(output.fileno())
                output.close()
                if digest(pending) != checksum:
                    raise ValueError("Packaging tool SHA-256 mismatch: " + filename)
                pending.replace(destination)
            finally:
                output.close()
                pending.unlink(missing_ok=True)
    print(root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=TOOLS)
    fetch(parser.parse_args().target)
