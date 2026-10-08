#!/usr/bin/env bash
set -euo pipefail
umask 022

if [[ $# != 2 ]]; then
  echo 'Usage: build.sh arm64|amd64 /absolute/path/to/mac-jdk-21.0.11/Contents/Home' >&2
  exit 2
fi
architecture=$1
target_jdk=$(realpath "$2")
: "${JAVA_HOME:?Set JAVA_HOME to the host JDK 21.0.11}"
: "${OSXCROSS_ROOT:?Set OSXCROSS_ROOT to the installed OSXCross toolchain with an official Apple SDK}"
case "$architecture" in
  arm64) compiler_arch=arm64; javafx_platform=mac-aarch64; jdk_arch='aarch64|arm64' ;;
  amd64) compiler_arch=x86_64; javafx_platform=mac; jdk_arch='x86_64|amd64' ;;
  *) echo 'Supported architectures: arm64, amd64' >&2; exit 2 ;;
esac
grep -q '^JAVA_VERSION="21.0.11"' "$JAVA_HOME/release"
grep -q '^JAVA_VERSION="21.0.11"' "$target_jdk/release"
grep -q '^OS_NAME="Darwin"' "$target_jdk/release"
grep -Eq "^OS_ARCH=\"($jdk_arch)\"" "$target_jdk/release"
command -v rcodesign >/dev/null
command -v python3 >/dev/null
command -v zip >/dev/null
command -v mkbom >/dev/null
command -v xar >/dev/null
command -v cpio >/dev/null
compiler_candidates=()
for candidate in "$OSXCROSS_ROOT"/bin/"$compiler_arch"-apple-darwin*-clang; do
  if [[ ${candidate##*/} =~ ^${compiler_arch}-apple-darwin[0-9.]+-clang$ ]]; then
    compiler_candidates+=("$candidate")
  fi
done
[[ ${#compiler_candidates[@]} == 1 && -x "${compiler_candidates[0]}" ]]
compiler=${compiler_candidates[0]}
export MACOSX_DEPLOYMENT_TARGET=13.0
root=$(cd "$(dirname "$0")/../../.." && pwd)
mkdir -p "$root/packaging/target"
# Native Linux storage preserves POSIX modes; Docker Desktop Windows mounts may report 0777.
work=$(mktemp -d "/tmp/wisprail-macos-$architecture-XXXXXXXX")
python3 "$root/packaging/source-snapshot.py" --output "$work/source"
cd "$work/source"
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").findtext("{*}version"))')
bash ./mvnw -B -ntp -Djavafx.platform="$javafx_platform" -DskipTests install
python3 packaging/fetch-engine.py --platform "darwin-$architecture"
app="$work/Wisprail.app"
contents="$app/Contents"
mkdir -p "$contents/MacOS" "$contents/app/native" "$contents/app/engine" \
  "$contents/Library/LaunchDaemons" "$contents/Resources" "$contents/runtime/Contents" "$root/packaging/target/dist"
bash ./mvnw -B -ntp -pl desktop -Djavafx.platform="$javafx_platform" \
  dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$contents/app"
cp "desktop/target/wisprail-desktop-$version.jar" "$contents/app/"
cp packaging/macos/Info.plist "$contents/Info.plist"
python3 - "$contents/Info.plist" "$version" <<'PY'
import plistlib, sys
from pathlib import Path
path = Path(sys.argv[1])
with path.open('rb') as source:
    plist = plistlib.load(source)
plist['CFBundleVersion'] = sys.argv[2]
plist['CFBundleShortVersionString'] = sys.argv[2]
with path.open('wb') as target:
    plistlib.dump(plist, target)
PY
cp packaging/macos/native/app.wisprail.agent.plist "$contents/Library/LaunchDaemons/"
cp "packaging/target/engine/darwin-$architecture/sing-box" \
  "packaging/target/engine/darwin-$architecture/sing-box.sha256" \
  "packaging/target/engine/darwin-$architecture/LICENSE" "$contents/app/engine/"
"$JAVA_HOME/bin/jlink" --module-path "$target_jdk/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.naming.dns,jdk.security.auth,jdk.net \
  --strip-debug --no-header-files --no-man-pages --output "$contents/runtime/Contents/Home"
"$compiler" -O2 -mmacosx-version-min=13.0 -I"$target_jdk/include" -I"$target_jdk/include/darwin" \
  packaging/macos/wisprail-launcher.c -o "$contents/MacOS/Wisprail"
"$compiler" -O2 -mmacosx-version-min=13.0 -DWISPRAIL_AGENT -I"$target_jdk/include" \
  -I"$target_jdk/include/darwin" packaging/macos/wisprail-launcher.c -o "$contents/MacOS/wisprail-agent"
"$compiler" -O2 -mmacosx-version-min=13.0 -DWISPRAIL_MAINTENANCE -I"$target_jdk/include" \
  -I"$target_jdk/include/darwin" packaging/macos/wisprail-launcher.c -o "$contents/MacOS/wisprail-maintenance"
"$compiler" -O2 -dynamiclib -fobjc-arc -mmacosx-version-min=13.0 \
  -framework Foundation -framework Security -framework SystemConfiguration -framework ServiceManagement \
  packaging/macos/native/wisprail-platform.m -o "$contents/app/native/libwisprail-platform.dylib"
cp packaging/macos/uninstall.command "$contents/Resources/uninstall.command"
cp packaging/macos/maintain-installation.sh "$contents/Resources/maintain-installation.sh"
find "$app" -type d -exec chmod 755 {} +
find "$app" -type f -exec chmod 644 {} +
chmod 755 "$contents/MacOS/"* "$contents/app/engine/sing-box" \
  "$contents/runtime/Contents/Home/bin/"* "$contents/Resources/"*
find "$contents/runtime" -type f -name jspawnhelper -exec chmod 755 {} +
signing_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)
rcodesign sign --timestamp-url none --signing-time "$signing_time" "$app"
# The engine checksum is of the distributed, ad-hoc-signed executable.
sha256sum "$contents/app/engine/sing-box" | cut -d' ' -f1 > "$contents/app/engine/sing-box.sha256"
# Updating the checksum changes a sealed bundle resource, so seal and verify again.
rcodesign sign --timestamp-url none --signing-time "$signing_time" "$app"
expected_engine_hash=$(cat "$contents/app/engine/sing-box.sha256")
actual_engine_hash=$(sha256sum "$contents/app/engine/sing-box" | cut -d' ' -f1)
[[ "$expected_engine_hash" == "$actual_engine_hash" ]]
# rcodesign 0.29.0 verify rejects the intentionally empty ad-hoc CMS and does not
# check external bundle resources. Recompute the complete seal using its signer,
# then require byte equality; Apple codesign trust/runtime checks need macOS.
mkdir -p "$work/signature-check"
rcodesign sign --timestamp-url none --signing-time "$signing_time" \
  "$app" "$work/signature-check/Wisprail.app"
diff -qr --no-dereference "$app" "$work/signature-check/Wisprail.app"
echo 'Ad-hoc code and resource seal recomputation matched; Apple codesign verification NOT_RUN.'
package="$work/Wisprail-$version-macos-$architecture.pkg"
payload="$work/package-root"
flat="$work/package-flat"
mkdir -p "$payload/Applications" "$flat"
cp -a "$app" "$payload/Applications/"
mkdir -p "$work/package-scripts"
cp packaging/macos/preinstall packaging/macos/postinstall packaging/macos/maintain-installation.sh \
  "$work/package-scripts/"
chmod 755 "$work/package-scripts/"*
(cd "$work/package-scripts" && find . -print0 | cpio --null -o --format odc --owner 0:80 | gzip -9 > "$flat/Scripts")
mkbom -u 0 -g 80 "$payload" "$flat/Bom"
(cd "$payload" && find . -print0 | cpio --null -o --format odc --owner 0:80 | gzip -9 > "$flat/Payload")
python3 - "$payload" "$flat/PackageInfo" "$version" <<'PY'
import os, sys
import xml.etree.ElementTree as ET
from pathlib import Path
payload = Path(sys.argv[1])
count, size = 0, 0
for directory, subdirectories, filenames in os.walk(payload, followlinks=False):
    count += len(subdirectories) + len(filenames)
    for filename in filenames:
        size += (Path(directory) / filename).lstat().st_size
package = ET.Element('pkg-info', {'format-version': '2', 'identifier': 'app.wisprail.desktop',
    'version': sys.argv[3], 'install-location': '/', 'auth': 'root', 'relocatable': 'false'})
ET.SubElement(package, 'payload', {'installKBytes': str((size + 1023) // 1024),
    'numberOfFiles': str(count)})
ET.SubElement(package, 'bundle-version')
scripts = ET.SubElement(package, 'scripts')
ET.SubElement(scripts, 'preinstall', {'file': './preinstall'})
ET.SubElement(scripts, 'postinstall', {'file': './postinstall'})
ET.ElementTree(package).write(sys.argv[2], encoding='utf-8', xml_declaration=True)
PY
(cd "$flat" && xar --compression none -cf "$package" Bom PackageInfo Payload Scripts)
archive="$root/packaging/target/dist/Wisprail-$version-macos-$architecture.zip"
[[ ! -e "$archive" ]] || { echo "Artifact already exists: $archive" >&2; exit 1; }
(cd "$work" && zip -q -r -y "$archive" Wisprail.app "$(basename "$package")")
cp "$work/sources.zip" "$root/packaging/target/dist/Wisprail-$version-macos-$architecture-sources.zip"
cp "$work/source/source-manifest.json" \
  "$root/packaging/target/dist/Wisprail-$version-macos-$architecture-source-manifest.json"
python3 - "$archive" "$architecture" "$version" <<'PY'
import hashlib, json, sys
from pathlib import Path
archive = Path(sys.argv[1])
manifest = {'application': sys.argv[3], 'target': 'macos-' + sys.argv[2],
            'jdk': '21.0.11', 'engine': '1.14.2', 'signing': 'ad-hoc',
            'appleSdk': '15.5', 'rcodesign': '0.29.0',
            'osxcrossCommit': '27d21e4977c9751d01199c7a226a6faf494c3dd9',
            'verification': 'cross compilation; deterministic ad-hoc code/resource seal recomputation matched; Apple codesign and macOS runtime acceptance NOT_RUN'}
archive.with_suffix('.build.json').write_text(json.dumps(manifest, indent=2) + '\n')
with (archive.parent / ('SHA256SUMS-macos-' + sys.argv[2])).open('w') as output:
    for path in sorted(archive.parent.iterdir()):
        if path.is_file() and ('macos-' + sys.argv[2]) in path.name and path.name.startswith('Wisprail-'):
            with path.open('rb') as content:
                checksum = hashlib.file_digest(content, 'sha256').hexdigest()
            output.write(checksum + '  ' + path.name + '\n')
PY
sha256sum "$archive"
