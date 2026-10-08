#!/usr/bin/env python3
"""Copy the current allowed sources before building, with an exact content manifest."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

SOURCE_DIRECTORIES = ("core", "desktop", "packaging", "docs", ".mvn", ".run")
SOURCE_FILES = ("pom.xml", "mvnw", "mvnw.cmd", "README.md", "AGENTS.md", ".gitignore")
EXCLUDED_DIRECTORIES = {"target", "node_modules", "__pycache__", ".git", ".idea"}
EXCLUDED_SUFFIXES = {".key", ".p12", ".pfx", ".log"}


def source_files(root):
    for name in SOURCE_FILES:
        path = root / name
        if path.is_file():
            yield path
    for name in SOURCE_DIRECTORIES:
        for directory, subdirectories, names in os.walk(root / name, followlinks=False):
            subdirectories[:] = sorted(child for child in subdirectories
                                       if child not in EXCLUDED_DIRECTORIES
                                       and not (Path(directory) / child).is_symlink())
            for filename in sorted(names):
                path = Path(directory) / filename
                if (not path.is_symlink() and not filename.startswith(".env")
                        and path.suffix.lower() not in EXCLUDED_SUFFIXES):
                    yield path


def snapshot(destination):
    root = Path(__file__).resolve().parent.parent
    destination.mkdir(parents=True, exist_ok=False)
    entries = []
    for source in source_files(root):
        relative = source.relative_to(root)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        with target.open("rb") as content:
            checksum = hashlib.file_digest(content, "sha256").hexdigest()
        entries.append({"path": relative.as_posix(), "sha256": checksum,
                        "bytes": target.stat().st_size})
    pom = ET.parse(destination / "pom.xml")
    properties = pom.find("{*}properties")
    versions = {element.tag.split("}")[-1]: element.text for element in properties
                if element.tag.endswith(".version") or element.tag.endswith("compiler.release")}
    versions["application"] = pom.findtext("{*}version")
    versions["engine"] = "1.14.2"
    versions["packaging-jdk"] = "21.0.11"
    manifest = {"versions": versions, "files": entries}
    (destination / "source-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    archive = destination.parent / "sources.zip"
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as output:
        for entry in entries:
            output.write(destination / entry["path"], entry["path"])
        output.write(destination / "source-manifest.json", "source-manifest.json")
    print(destination)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    snapshot(parser.parse_args().output.resolve())
