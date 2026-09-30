"""Fetch pinned build inputs over HTTPS; no Apple credentials or SDK redistribution."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    inputs = json.loads(Path(__file__).with_name("inputs.json").read_text())
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    cache = Path("/var/cache/wisprail-inputs")
    cache.mkdir(parents=True, exist_ok=True)
    for name, entry in inputs.items():
        archive = cache / entry["sha256"]
        if not archive.is_file():
            print(f"Downloading pinned {name}", flush=True)
            partial = archive.with_suffix(".partial")
            subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error",
                            "--proto", "=https", "--proto-redir", "=https",
                            "--retry", "3", "--retry-connrefused", "--max-time", "300",
                            "--retry-max-time", "600", "--max-filesize", "838860800",
                            "--output", str(partial), entry["url"]], check=True)
            if checksum(partial) != entry["sha256"]:
                raise RuntimeError(f"Checksum mismatch for {name}")
            partial.rename(archive)
        digest = checksum(archive)
        if digest != entry["sha256"]:
            raise RuntimeError(f"Checksum mismatch for {name}: {digest}")
        if name == "sdk":
            shutil.copyfile(archive, output / "sdk.pkg")
            continue
        directory = output / name
        directory.mkdir()
        with tarfile.open(archive) as package:
            package.extractall(directory, filter="data")
        print(f"Verified {name}: {digest}", flush=True)


if __name__ == "__main__":
    main()
