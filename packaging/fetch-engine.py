"""Download the pinned upstream distribution, checking its published SHA-256 before extraction."""
import argparse
import hashlib
import io
from pathlib import Path
import tarfile
import time
import urllib.error
import urllib.request
import zipfile

VERSION = "1.14.2"
ARTIFACTS = {
    "linux-amd64": ("tar.gz", "a684484d7477d1437282ee411f4d131d0340aaad60a7868841ebd5d87dd8a0c6"),
    "windows-amd64": ("zip", "c2d8bfff918755808781dfdeeb8581b6c91eb3a243d9a7b55483cfc0c0684d32"),
    "darwin-amd64": ("tar.gz", "b0bfb0dc70a5fc708710b9f5ea98b9ee76d40fa4169928d25d73edc4331df2fe"),
    "darwin-arm64": ("tar.gz", "925c5382eca8492b0150f868a6db20b18290a38700e621724b3703fd453e032d"),
}


def download(url):
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read(200 * 1024 * 1024)
        except urllib.error.HTTPError as failure:
            if failure.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise
        except (TimeoutError, urllib.error.URLError):
            if attempt == 2:
                raise
        print(f"Engine download temporarily unavailable; retry {attempt + 1}/2", flush=True)
        time.sleep(attempt + 1)
    raise RuntimeError("Engine download failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=ARTIFACTS, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "target" / "engine")
    args = parser.parse_args()
    extension, expected = ARTIFACTS[args.platform]
    filename = f"sing-box-{VERSION}-{args.platform}.{extension}"
    url = f"https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/{filename}"
    archive = download(url)
    if hashlib.sha256(archive).hexdigest() != expected:
        raise SystemExit("Upstream archive checksum mismatch; nothing extracted")
    args.output.mkdir(parents=True, exist_ok=True)
    if extension == "zip":
        with zipfile.ZipFile(io.BytesIO(archive)) as package:
            for member in package.infolist():
                if not member.is_dir():
                    (args.output / Path(member.filename).name).write_bytes(package.read(member))
    else:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
            for member in package.getmembers():
                if member.isfile():
                    stream = package.extractfile(member)
                    if stream is None:
                        raise SystemExit("Invalid upstream archive member")
                    with stream:
                        target = args.output / Path(member.name).name
                        target.write_bytes(stream.read())
                        target.chmod(0o755 if target.name == "sing-box" else 0o644)
    executable = args.output / ("sing-box.exe" if args.platform.startswith("windows") else "sing-box")
    (args.output / "sing-box.sha256").write_text(hashlib.sha256(executable.read_bytes()).hexdigest(), encoding="ascii")
    print(f"Verified sing-box {VERSION}: {args.platform}")


if __name__ == "__main__":
    main()
