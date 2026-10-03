"""Build the existing Mac application layout on Linux without executing Mac binaries."""

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import shutil
import stat
import subprocess
import xml.etree.ElementTree as ET
import zipfile

MODULES = ("java.base,java.desktop,java.logging,java.naming,java.net.http,java.security.jgss,"
           "java.xml,jdk.crypto.ec,jdk.unsupported,jdk.net,jdk.management")
MACH_MAGICS = (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe",
               b"\xca\xfe\xba\xbf")


def run(*command, **options):
    subprocess.run([str(argument) for argument in command], check=True, **options)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def is_macho(path):
    if path.is_symlink() or not path.is_file():
        return False
    with path.open("rb") as stream:
        return stream.read(4) in MACH_MAGICS


def write_plist(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as stream:
        plistlib.dump(values, stream)


def copy_jdk_resource(jmod, name, destination):
    with zipfile.ZipFile(jmod) as archive:
        entry = f"classes/jdk/jpackage/internal/resources/{name}"
        destination.write_bytes(archive.read(entry))


def assemble_app(repository, output, architecture, version):
    jdk = Path(f"/opt/jdk-{architecture}")
    javafx = "mac-aarch64" if architecture == "arm64" else "mac"
    jna = "aarch64" if architecture == "arm64" else "x86-64"
    engine_platform = "darwin-arm64" if architecture == "arm64" else "darwin-amd64"
    # All tests ran with Linux JavaFX in build.sh. Mac native libraries cannot run on Linux.
    run("sh", "./mvnw", "-B", "-ntp", "-Dmaven.repo.local=/maven",
        f"-Djavafx.platform={javafx}", "-DskipTests", "clean", "package", cwd=repository)
    distribution = output / "distribution/Wisprail"
    app = distribution / "Wisprail.app"
    contents = app / "Contents"
    appdir = contents / "app"
    for name in ("app/javafx", "MacOS", "Resources", "Frameworks", "Library/LaunchDaemons"):
        (contents / name).mkdir(parents=True, exist_ok=True)
    desktop = repository / "desktop/target"
    shutil.copy2(desktop / f"wisprail-desktop-{version}.jar", appdir)
    for library in sorted((desktop / "lib").glob("*.jar")):
        shutil.copy2(library, appdir)
    shutil.copytree(desktop / "javafx", appdir / "javafx", dirs_exist_ok=True)
    run("python3", repository / "packaging/fetch-engine.py", "--platform", engine_platform,
        "--output", appdir / "engine")
    shutil.copy2(repository / "packaging/THIRD_PARTY_NOTICES.md", appdir)
    shutil.copy2(repository / "packaging/THIRD_PARTY_NOTICES.md", distribution)
    shutil.copytree(jdk / "legal/jdk.jpackage", appdir / "licenses/jdk.jpackage")
    runtime = contents / "runtime/Contents/Home"
    run("/opt/java/bin/jlink", "--module-path", jdk / "jmods", "--add-modules", MODULES,
        "--strip-debug", "--no-header-files", "--no-man-pages", "--output", runtime)
    runtime_macos = runtime.parent / "MacOS"
    runtime_macos.mkdir()
    shutil.copy2(runtime / "lib/libjli.dylib", runtime_macos)
    package_version = version.split("-", 1)[0]
    common = dict(CFBundleDevelopmentRegion="English", CFBundleInfoDictionaryVersion="6.0",
                  CFBundleShortVersionString=package_version, CFBundleVersion=package_version,
                  CFBundleSignature="????")
    write_plist(contents / "Info.plist", dict(common, CFBundleExecutable="Wisprail",
        CFBundleName="Wisprail", CFBundleIdentifier="app.wisprail", CFBundlePackageType="APPL",
        CFBundleIconFile="Wisprail.icns", LSMinimumSystemVersion="13.0",
        NSHighResolutionCapable=True, CFBundleAllowMixedLocalizations=True,
        LSApplicationCategoryType="public.app-category.utilities"))
    write_plist(runtime.parent / "Info.plist", dict(common, CFBundleExecutable="libjli.dylib",
        CFBundleName="Wisprail Runtime", CFBundleIdentifier="app.wisprail.runtime",
        CFBundlePackageType="BNDL"))
    (contents / "PkgInfo").write_bytes(b"APPL????")
    jmod = jdk / "jmods/jdk.jpackage.jmod"
    copy_jdk_resource(jmod, "JavaApp.icns", contents / "Resources/Wisprail.icns")
    jars = sorted(path.name for path in appdir.glob("*.jar"))
    for launcher, main in (("Wisprail", "app.wisprail.ui.DesktopLauncher"),
                           ("WisprailAgent", "app.wisprail.agent.AgentMain")):
        executable = contents / "MacOS" / launcher
        copy_jdk_resource(jmod, "jpackageapplauncher", executable)
        executable.chmod(0o755)
        options = [f"-Djpackage.app-version={package_version}", "-Dfile.encoding=UTF-8",
                   "--module-path=$APPDIR/javafx", "--add-modules=javafx.controls",
                   "-Djava.library.path=$APPDIR/../Frameworks",
                   "-Djna.boot.library.path=$APPDIR/../Frameworks"]
        config = ["[Application]", f"app.mainclass={main}"]
        config += [f"app.classpath=$APPDIR/{name}" for name in jars]
        config += ["", "[JavaOptions]"] + [f"java-options={option}" for option in options]
        (appdir / f"{launcher}.cfg").write_text("\n".join(config) + "\n", encoding="utf-8")
    for library in appdir.glob("jna-[0-9]*.jar"):
        with zipfile.ZipFile(library) as archive:
            (contents / "Frameworks/libjnidispatch.jnilib").write_bytes(
                archive.read(f"com/sun/jna/darwin-{jna}/libjnidispatch.jnilib"))
    for library in (appdir / "javafx").glob("javafx-graphics-*.jar"):
        with zipfile.ZipFile(library) as archive:
            for entry in archive.namelist():
                if entry.endswith(".dylib"):
                    (contents / "Frameworks" / Path(entry).name).write_bytes(archive.read(entry))
    native_arch = "arm64" if architecture == "arm64" else "x86_64"
    run(f"{native_arch}-apple-darwin23.5-clang", "-dynamiclib", "-fobjc-arc",
        "-mmacosx-version-min=13.0", "-framework", "Foundation", "-framework", "ServiceManagement",
        "-Wl,-install_name,@rpath/libwisprail.dylib", repository / "packaging/macos/services.m",
        "-o", contents / "Frameworks/libwisprail.dylib")
    shutil.copy2(repository / "packaging/macos/app.wisprail.agent.plist",
                 contents / "Library/LaunchDaemons")
    run("rcodesign", "sign", app)
    # The signature changes engine bytes. Seal its final digest without re-signing nested code.
    (appdir / "engine/sing-box.sha256").write_text(sha256(appdir / "engine/sing-box"))
    run("rcodesign", "sign", "--shallow", app)
    return app


def make_cpio(directory, destination):
    names = ["."] + ["./" + path.relative_to(directory).as_posix()
                     for path in sorted(directory.rglob("*"))]
    listing = "\0".join(names).encode() + b"\0"
    with destination.open("wb") as stream:
        run("bsdtar", "--format=odc", "--uid=0", "--gid=0", "-czf", "-",
            "--no-recursion", "--null", "-T", "-", cwd=directory, input=listing, stdout=stream)


def build_pkg(repository, app, output, version):
    root = output / "payload/Applications"
    root.mkdir(parents=True)
    shutil.copytree(app, root / app.name, symlinks=True)
    package = output / "flat-package"
    package.mkdir()
    make_cpio(root.parent, package / "Payload")
    run("mkbom", "-u", "0", "-g", "0", root.parent, package / "Bom")
    scripts = output / "scripts"
    scripts.mkdir()
    for name in ("preinstall", "postinstall"):
        target = scripts / name
        target.write_bytes((repository / "packaging/macos/installer-scripts" / name)
                           .read_bytes().replace(b"\r\n", b"\n"))
        target.chmod(0o755)
    make_cpio(scripts, package / "Scripts")
    entries = list(root.parent.rglob("*"))
    total = sum(path.stat().st_size for path in entries if path.is_file() and not path.is_symlink())
    info = ET.Element("pkg-info", {"format-version": "2", "identifier": "app.wisprail",
        "version": version.split("-", 1)[0], "install-location": "/", "auth": "root"})
    ET.SubElement(info, "payload", {"numberOfFiles": str(len(entries)),
                                   "installKBytes": str((total + 1023) // 1024)})
    script_info = ET.SubElement(info, "scripts")
    for name in ("preinstall", "postinstall"):
        ET.SubElement(script_info, name, {"file": f"./{name}"})
    # Fixed /Applications ownership matches the native installer and service contract.
    ET.SubElement(info, "bundle-version")
    ET.ElementTree(info).write(package / "PackageInfo", encoding="utf-8", xml_declaration=True)
    installer = app.parent / "Wisprail.pkg"
    run("bsdtar", "--format=xar", "-cf", installer, "Bom", "PackageInfo", "Payload", "Scripts",
        cwd=package)
    return installer


def inventory(directory):
    result = {}
    for path in sorted(directory.rglob("*")):
        name = path.relative_to(directory).as_posix()
        mode = stat.S_IMODE(path.lstat().st_mode)
        if path.is_symlink():
            result[name] = ("link", mode, str(path.readlink()))
        elif path.is_dir():
            result[name] = ("directory", mode)
        else:
            result[name] = ("file", mode, sha256(path))
    return result


def verify_pkg(installer, app, output):
    unpacked = output / "checked-package"
    unpacked.mkdir()
    run("bsdtar", "-xf", installer, "-C", unpacked)
    info = ET.parse(unpacked / "PackageInfo").getroot()
    if (info.get("identifier"), info.get("auth"), info.get("install-location")) != (
            "app.wisprail", "root", "/"):
        raise RuntimeError("Installer changed the privileged installation contract")
    payload = unpacked / "payload"
    payload.mkdir()
    run("bsdtar", "-xf", unpacked / "Payload", "-C", payload)
    if inventory(app) != inventory(payload / "Applications/Wisprail.app"):
        raise RuntimeError("Installer payload differs from the checked application")
    scripts = unpacked / "scripts"
    scripts.mkdir()
    run("bsdtar", "-xf", unpacked / "Scripts", "-C", scripts)
    if inventory(scripts) != inventory(output / "scripts"):
        raise RuntimeError("Installer scripts lost contents or executable permissions")
    bom = subprocess.check_output(["lsbom", str(unpacked / "Bom")], text=True)
    for line in bom.splitlines():
        fields = line.split("\t")
        if len(fields) < 3 or fields[2] != "0/0":
            raise RuntimeError(f"Installer BOM does not assign root:wheel ownership: {line}")


def verify_app(app, architecture):
    native_arch = "arm64" if architecture == "arm64" else "x86_64"
    binaries = [path for path in app.rglob("*") if is_macho(path)]
    if not binaries:
        raise RuntimeError("Application contains no Mach-O binaries")
    for binary in binaries:
        run("llvm-lipo", binary, "-verify_arch", native_arch)
    run("python3", Path(__file__).with_name("verify-signatures.py"), app)
    contents = app / "Contents"
    engine = contents / "app/engine/sing-box"
    if sha256(engine) != engine.with_suffix(".sha256").read_text().strip():
        raise RuntimeError("Signed engine digest does not match the agent contract")
    for name in ("Wisprail", "WisprailAgent"):
        if not (contents / "MacOS" / name).stat().st_mode & stat.S_IXUSR:
            raise RuntimeError(f"Launcher is not executable: {name}")
    for path in app.rglob("*"):
        if path.is_symlink() and not path.resolve().is_relative_to(app.resolve()):
            raise RuntimeError(f"Bundle symlink escapes the application: {path}")
    return len(binaries)


def zip_distribution(directory, archive):
    # zip's Unix attributes preserve executable bits and symlinks (including link text).
    run("zip", "-qry", archive, directory.name, cwd=directory.parent)
    with zipfile.ZipFile(archive) as package:
        for path in directory.rglob("*"):
            if path.is_dir() and not path.is_symlink():
                continue
            relative = path.relative_to(directory.parent).as_posix()
            entry = package.getinfo(relative)
            if stat.S_IMODE(entry.external_attr >> 16) != stat.S_IMODE(path.lstat().st_mode):
                raise RuntimeError(f"ZIP lost Unix permissions: {relative}")
            expected = str(path.readlink()).encode() if path.is_symlink() else path.read_bytes()
            if package.read(entry) != expected:
                raise RuntimeError(f"ZIP contents differ: {relative}")
            if path.is_symlink() and not stat.S_ISLNK(entry.external_attr >> 16):
                raise RuntimeError(f"ZIP lost symbolic link: {relative}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--architecture", choices=("arm64", "x64"), required=True)
    parser.add_argument("--source-snapshot", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[3]
    version = ET.parse(repository / "pom.xml").findtext("{*}version")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    app = assemble_app(repository, output, args.architecture, version)
    native_count = verify_app(app, args.architecture)
    installer = build_pkg(repository, app, output, version)
    verify_pkg(installer, app, output)
    information = dict(version=version, platform="macos", architecture=args.architecture,
        sourceSnapshot=args.source_snapshot, engineVersion="1.14.2", javaVersion="21.0.11",
        sdkVersion="14.5", minimumMacosVersion="13.0",
        toolInputsSha256=sha256(Path(__file__).with_name("inputs.json")),
        builder="linux-osxcross", signing="ad-hoc", applicationSigned=False,
        installerNotarized=False, nativeBinariesChecked=native_count, installerPayloadChecked=True,
        signatureIntegrity="PASS", appleCodesignVerification="NOT_RUN",
        macosLaunch="NOT_RUN", macosInstallation="NOT_RUN")
    (app.parent / "build-info.json").write_text(json.dumps(information, indent=2) + "\n")
    archive = output / f"wisprail-{version}-macos-{args.architecture}.zip"
    zip_distribution(app.parent, archive)
    print(f"Built and statically checked: {archive}", flush=True)


if __name__ == "__main__":
    main()
