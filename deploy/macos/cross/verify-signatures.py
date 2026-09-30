"""Check ad-hoc CodeDirectory page hashes and bundle seals, not Apple trust/Gatekeeper.

Format: Apple's Security/OSX/libsecurity_codesigning/lib/codedirectory.h.
rcodesign 0.29's `verify` incorrectly parses the empty CMS blob of an ad-hoc signature.
This bounded check only accepts the formats emitted by our pinned signing tool.
"""

import hashlib
from pathlib import Path
import plistlib
import struct
import sys


def section(data, offset, size):
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError("Signature range exceeds its container")
    return data[offset:offset + size]


def macho_slices(data):
    if data[:4] == b"\xcf\xfa\xed\xfe":
        return [data]
    if data[:4] not in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        raise ValueError("Expected a 64-bit Mach-O or universal binary")
    wide = data[:4] == b"\xca\xfe\xba\xbf"
    count = struct.unpack_from(">I", data, 4)[0]
    if not 1 <= count <= 16:
        raise ValueError("Unexpected universal architecture count")
    slices = []
    for index in range(count):
        entry = 8 + index * (32 if wide else 20)
        offset, size = struct.unpack_from(">QQ" if wide else ">II", data, entry + 8)
        slices.append(section(data, offset, size))
    return slices


def signature_blobs(data):
    if data[:4] != b"\xcf\xfa\xed\xfe":
        raise ValueError("Unsupported Mach-O slice")
    count, commands_size = struct.unpack_from("<II", data, 16)
    commands = section(data, 32, commands_size)
    position = 0
    signature = None
    signature_offset = None
    embedded_info = None
    for _ in range(count):
        command, command_size = struct.unpack_from("<II", commands, position)
        entry = section(commands, position, command_size)
        if command_size < 8:
            raise ValueError("Invalid load command")
        if command == 0x1D:  # LC_CODE_SIGNATURE
            if signature is not None:
                raise ValueError("Duplicate code signature")
            signature_offset, length = struct.unpack_from("<II", entry, 8)
            signature = section(data, signature_offset, length)
        elif command == 0x19:  # LC_SEGMENT_64, e.g. the JDK launcher's embedded Info.plist
            sections = struct.unpack_from("<I", entry, 64)[0]
            for index in range(sections):
                segment_section = section(entry, 72 + index * 80, 80)
                if segment_section[:16].rstrip(b"\0") == b"__info_plist":
                    info_size = struct.unpack_from("<Q", segment_section, 40)[0]
                    offset = struct.unpack_from("<I", segment_section, 48)[0]
                    embedded_info = section(data, offset, info_size)
        position += command_size
    if signature is None:
        raise ValueError("Missing code signature")
    magic, length, count = struct.unpack_from(">III", signature)
    if magic != 0xFADE0CC0 or count > 32:
        raise ValueError("Unsupported signature SuperBlob")
    signature = section(signature, 0, length)
    blobs = {}
    for index in range(count):
        slot, offset = struct.unpack_from(">II", signature, 12 + index * 8)
        blob_length = struct.unpack_from(">I", signature, offset + 4)[0]
        if slot in blobs:
            raise ValueError("Duplicate signature slot")
        blobs[slot] = section(signature, offset, blob_length)
    if embedded_info is not None:
        blobs[1] = embedded_info
    return signature_offset, blobs


def verify_binary(path, external=None):
    cdhashes = []
    for data in macho_slices(path.read_bytes()):
        signature_offset, blobs = signature_blobs(data)
        directories = [blob for slot, blob in blobs.items()
                       if slot == 0 or 0x1000 <= slot < 0x1005]
        if not directories:
            raise ValueError(f"No CodeDirectory: {path}")
        for directory in directories:
            magic, length, version, flags, hashes, identifier, special, pages, limit = (
                struct.unpack_from(">9I", directory))
            hash_size, algorithm, platform, exponent = struct.unpack_from("4B", directory, 36)
            if magic != 0xFADE0C02 or not 0x20001 <= version <= 0x20500 or not flags & 2:
                raise ValueError(f"Expected supported ad-hoc CodeDirectory: {path}")
            if (length != len(directory) or platform != 0 or identifier < 44
                    or directory.find(b"\0", identifier, hashes) < 0):
                raise ValueError(f"Invalid CodeDirectory identity: {path}")
            if version >= 0x20100 and struct.unpack_from(">I", directory, 44)[0]:
                raise ValueError("Scatter signatures are not supported by this checker")
            if version >= 0x20300:
                limit = struct.unpack_from(">Q", directory, 56)[0] or limit
            algorithms = {1: ("sha1", 20), 2: ("sha256", 32), 3: ("sha256", 20), 4: ("sha384", 48)}
            if algorithm not in algorithms or hash_size != algorithms[algorithm][1] or exponent > 20:
                raise ValueError("Unsupported signature digest or page size")
            algorithm_name = algorithms[algorithm][0]
            page_size = (1 << exponent) if exponent else limit
            if limit != signature_offset or not page_size or pages != (limit + page_size - 1) // page_size:
                raise ValueError(f"Signature does not cover the complete code: {path}")
            for index in range(pages):
                page = section(data, index * page_size, min(page_size, limit - index * page_size))
                expected = section(directory, hashes + index * hash_size, hash_size)
                if hashlib.new(algorithm_name, page).digest()[:hash_size] != expected:
                    raise ValueError(f"Code page hash mismatch: {path}, page {index}")
            for slot in range(1, special + 1):
                expected = section(directory, hashes - slot * hash_size, hash_size)
                content = (external or {}).get(slot, blobs.get(slot))
                if expected == bytes(hash_size) and content is None:
                    continue
                if content is None or hashlib.new(algorithm_name, content).digest()[:hash_size] != expected:
                    raise ValueError(f"Special slot hash mismatch: {path}, slot {slot}")
            cdhashes.append(hashlib.new(algorithm_name, directory).digest()[:20])
    return cdhashes


def bundle_executable(contents):
    info = plistlib.loads((contents / "Info.plist").read_bytes())
    return contents / "MacOS" / info["CFBundleExecutable"]


def verify_bundle(app):
    checked = {}
    bundle_contents = [app / "Contents"] + [path.parent.parent for path in
        app.rglob("_CodeSignature/CodeResources") if path.parent.parent != app / "Contents"]
    for contents in bundle_contents:
        resource_file = contents / "_CodeSignature/CodeResources"
        if not resource_file.is_file():
            raise ValueError(f"Missing bundle resource seal: {contents}")
        executable = bundle_executable(contents)
        checked[executable] = verify_binary(executable, {
            1: (contents / "Info.plist").read_bytes(), 3: resource_file.read_bytes()})
    for path in app.rglob("*"):
        if path.is_symlink() or not path.is_file() or path in checked:
            continue
        with path.open("rb") as stream:
            magic = stream.read(4)
        if magic in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
            checked[path] = verify_binary(path)
    for contents in bundle_contents:
        resources = plistlib.loads((contents / "_CodeSignature/CodeResources").read_bytes())
        for name, seal in resources["files2"].items():
            path = contents / name
            if "symlink" in seal:
                if not path.is_symlink() or str(path.readlink()) != seal["symlink"]:
                    raise ValueError(f"Sealed symlink changed: {path}")
            elif "cdhash" in seal:
                executable = bundle_executable(path / "Contents") if path.is_dir() else path
                if seal["cdhash"] not in checked.get(executable, []):
                    raise ValueError(f"Nested code seal changed: {path}")
            else:
                data = path.read_bytes()
                for key, algorithm in (("hash", "sha1"), ("hash2", "sha256")):
                    if key in seal and hashlib.new(algorithm, data).digest() != seal[key]:
                        raise ValueError(f"Resource seal changed: {path}")
    print(f"Checked ad-hoc code pages and resource seals: {len(checked)} binaries; Apple trust NOT_RUN")


if __name__ == "__main__":
    verify_bundle(Path(sys.argv[1]))
