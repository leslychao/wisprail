"""Assemble the local Linux cross toolchain from the verified inputs."""

import lzma
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile


def run(*command, **options):
    subprocess.run(command, check=True, **options)


def unpack_sdk(inputs):
    directory = inputs / "sdk"
    directory.mkdir()
    run("bsdtar", "-xf", str(inputs / "sdk.pkg"), "-C", str(directory), "Payload")
    payload = directory / "Payload"
    # CLT uses PBZX: a header followed by independently compressed XZ/raw chunks.
    with payload.open("rb") as source, (directory / "payload.cpio").open("wb") as target:
        if source.read(4) != b"pbzx":
            raise RuntimeError("Expected PBZX SDK payload")
        source.read(8)
        while header := source.read(16):
            if len(header) != 16:
                raise RuntimeError("Truncated PBZX header")
            plain_size, stored_size = struct.unpack(">QQ", header)
            data = source.read(stored_size)
            if len(data) != stored_size:
                raise RuntimeError("Truncated PBZX chunk")
            if data.startswith(b"\xfd7zXZ\x00"):
                data = lzma.decompress(data)
            if len(data) != plain_size:
                raise RuntimeError("Incorrect PBZX uncompressed size")
            target.write(data)
    run("bsdtar", "-xf", str(directory / "payload.cpio"), "-C", str(directory))
    sdks = list(directory.glob("Library/Developer/CommandLineTools/SDKs/MacOSX*.sdk"))
    sdks = [sdk for sdk in sdks if not sdk.is_symlink()]
    if len(sdks) != 1:
        raise RuntimeError(f"Expected one versioned SDK: {sdks}")
    return sdks[0]


def main():
    inputs = Path("/opt/inputs")
    sdk = unpack_sdk(inputs)
    osxcross = next((inputs / "osxcross").iterdir())
    with tarfile.open(osxcross / "tarballs" / f"{sdk.name}.tar.gz", "w:gz", compresslevel=1) as archive:
        archive.add(sdk, arcname=sdk.name)
    environment = dict(os.environ, UNATTENDED="1", BUILD_FLAVOR="llvm",
                       ENABLE_ARCHS="arm64 x86_64", OSX_VERSION_MIN="13.0",
                       TARGET_DIR="/opt/osxcross", USE_SYSTEM_COMPILER="1")
    run("bash", "build.sh", cwd=osxcross, env=environment)
    bomutils = next((inputs / "bomutils").iterdir())
    # Release 0.2 predates std::data; build it with its original C++ language generation.
    run("make", "-j2", "CXX=g++ -std=c++11", cwd=bomutils)
    shutil.copy2(bomutils / "build/bin/mkbom", "/usr/local/bin/mkbom")
    shutil.copy2(bomutils / "build/bin/lsbom", "/usr/local/bin/lsbom")
    shutil.copy2(next((inputs / "rcodesign").rglob("rcodesign")), "/usr/local/bin/rcodesign")
    linux_jdk = next((inputs / "jdk-linux").glob("*/bin/java")).parent.parent
    Path("/opt/java").symlink_to(linux_jdk, target_is_directory=True)
    for architecture in ("arm64", "x64"):
        jdk = next((inputs / f"jdk-{architecture}").rglob("Contents/Home/release")).parent
        Path(f"/opt/jdk-{architecture}").symlink_to(jdk, target_is_directory=True)
    run("rcodesign", "--version")
    run("/opt/java/bin/java", "-version")


if __name__ == "__main__":
    main()
