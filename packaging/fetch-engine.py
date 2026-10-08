#!/usr/bin/env python3
"""Download the pinned upstream engine and verify it before extracting."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import zipfile

VERSION = "1.14.2"
ASSETS = {
    "windows-amd64": ("zip", "c2d8bfff918755808781dfdeeb8581b6c91eb3a243d9a7b55483cfc0c0684d32"),
    "windows-arm64": ("zip", "2bb467039310452380958b821983d5bb4f78fb70a2583914e4bea8b011582b64"),
    "darwin-amd64": ("tar.gz", "b0bfb0dc70a5fc708710b9f5ea98b9ee76d40fa4169928d25d73edc4331df2fe"),
    "darwin-arm64": ("tar.gz", "925c5382eca8492b0150f868a6db20b18290a38700e621724b3703fd453e032d"),
}
MAX_ARCHIVE = 300 * 1024 * 1024
MAX_BINARY = 400 * 1024 * 1024


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def fetch(platform):
    extension, checksum = ASSETS[platform]
    name = f"sing-box-{VERSION}-{platform}"
    root = Path(__file__).resolve().parent / "target" / "engine" / platform
    root.mkdir(parents=True, exist_ok=True)
    archive = root / f"{name}.{extension}"
    if not archive.exists() or digest(archive) != checksum:
        url = f"https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/{archive.name}"
        with tempfile.NamedTemporaryFile(dir=root, delete=False, suffix=".download") as target:
            pending = Path(target.name)
            try:
                request = urllib.request.Request(url, headers={"User-Agent": "Wisprail-packaging"})
                with urllib.request.urlopen(request, timeout=60) as response:
                    count = 0
                    while block := response.read(1024 * 1024):
                        count += len(block)
                        if count > MAX_ARCHIVE:
                            raise ValueError("Engine archive exceeds the allowed size")
                        target.write(block)
                target.flush()
                os.fsync(target.fileno())
            except BaseException:
                target.close()
                pending.unlink(missing_ok=True)
                raise
        if digest(pending) != checksum:
            pending.unlink()
            raise ValueError("Engine SHA-256 does not match the pinned release")
        pending.replace(archive)

    binary_name = "sing-box.exe" if platform.startswith("windows-") else "sing-box"
    binary = root / binary_name
    for filename, size_limit in [(binary_name, MAX_BINARY), ("LICENSE", 1024 * 1024)]:
        pending_file = root / (filename + ".extracting")
        member_name = f"{name}/{filename}"
        try:
            if extension == "zip":
                with zipfile.ZipFile(archive) as source:
                    member = source.getinfo(member_name)
                    if member.file_size > size_limit:
                        raise ValueError("Engine file exceeds the allowed size")
                    with source.open(member) as content, pending_file.open("wb") as target:
                        shutil.copyfileobj(content, target, 1024 * 1024)
            else:
                with tarfile.open(archive, "r:gz") as source:
                    member = source.getmember(member_name)
                    if not member.isfile() or member.size > size_limit:
                        raise ValueError("Invalid engine file in archive")
                    with source.extractfile(member) as content, pending_file.open("wb") as target:
                        shutil.copyfileobj(content, target, 1024 * 1024)
            pending_file.chmod(0o755 if filename == binary_name else 0o644)
            pending_file.replace(root / filename)
        finally:
            pending_file.unlink(missing_ok=True)
    (root / "sing-box.sha256").write_text(digest(binary) + "\n", encoding="ascii")
    print(binary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True, choices=ASSETS)
    fetch(parser.parse_args().platform)
