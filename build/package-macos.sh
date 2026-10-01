#!/bin/sh
set -eu
# Cross mode runs inside the pinned Linux builder; the native release path stays available.
if [ "${1:-}" = --cross ]; then
    shift
    exec python3 "$(dirname -- "$0")/macos/cross/package.py" "$@"
fi
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
architecture=$(uname -m)
output=''
snapshot=working-tree
release=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --architecture) architecture=$2; shift 2;;
        --output) output=$2; shift 2;;
        --source-snapshot) snapshot=$2; shift 2;;
        --release) release=true; shift;;
        *) echo "Unknown packaging argument: $1" >&2; exit 2;;
    esac
done
case "$architecture" in
    arm64) jna_arch=aarch64; engine_platform=darwin-arm64; javafx_platform=mac-aarch64; java_arch=aarch64;;
    x64|x86_64) architecture=x64; jna_arch=x86-64; engine_platform=darwin-amd64; javafx_platform=mac; java_arch=x86_64;;
    *) echo 'Unsupported architecture' >&2; exit 2;;
esac
if $release; then
    : "${WISPRAIL_APP_SIGN_IDENTITY:?Developer ID Application identity is required for release}"
    : "${WISPRAIL_INSTALLER_SIGN_IDENTITY:?Developer ID Installer identity is required for release}"
    : "${WISPRAIL_NOTARY_PROFILE:?Keychain notarytool profile is required for release}"
fi
repository=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repository"
grep -q 'JAVA_VERSION="21.0.11"' "$JAVA_HOME/release"
grep -q "OS_ARCH=\"$java_arch\"" "$JAVA_HOME/release"
version=$(python3 -c 'import xml.etree.ElementTree as E; print(E.parse("pom.xml").findtext("{*}version"))')
package_version=${version%%-*}
engine="$repository/build/target/engine/macos-$architecture"
python3 build/fetch-engine.py --platform "$engine_platform" --output "$engine"
sh ./mvnw -B -ntp -Djavafx.platform="$javafx_platform" -Dwisprail.engine="$engine/sing-box" clean verify
if [ -z "$output" ]; then output="$repository/build/target/macos-$architecture-$(date +%Y%m%d-%H%M%S)"; fi
if [ -e "$output" ]; then echo 'Packaging output must be a new directory.' >&2; exit 1; fi
input="$output/input"
mkdir -p "$input"
cp "frontend/target/wisprail-desktop-$version.jar" "$input/"
for jar in frontend/target/lib/*.jar; do
    case "$jar" in */javafx-*) continue;; esac
    cp "$jar" "$input/"
done
cp -R frontend/target/javafx "$input/javafx"
cp -R "$engine" "$input/engine"
cp build/THIRD_PARTY_NOTICES.md "$input/"
test -f "$input/engine/sing-box"
chmod 755 "$input/engine/sing-box"
printf '%s\n' "main-jar=wisprail-backend-$version.jar" 'main-class=app.wisprail.agent.AgentMain' > "$output/agent.properties"
"$JAVA_HOME/bin/jlink" --add-modules java.base,java.desktop,java.logging,java.naming,java.net.http,java.security.jgss,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.net,jdk.management --strip-debug --no-header-files --no-man-pages --output "$output/runtime"
"$JAVA_HOME/bin/jpackage" --type app-image --name Wisprail --app-version "$package_version" --vendor Wisprail --input "$input" --dest "$output" --main-jar "wisprail-desktop-$version.jar" --main-class app.wisprail.ui.DesktopLauncher --runtime-image "$output/runtime" --add-launcher "WisprailAgent=$output/agent.properties" --mac-package-identifier app.wisprail --java-options '-Dfile.encoding=UTF-8' --java-options '--module-path=$APPDIR/javafx' --java-options '--add-modules=javafx.controls' --java-options '-Djava.library.path=$APPDIR/../Frameworks' --java-options '-Djna.boot.library.path=$APPDIR/../Frameworks'
app="$output/Wisprail.app"
mkdir -p "$app/Contents/Frameworks" "$app/Contents/Library/LaunchDaemons"
for jar in "$input"/jna-[0-9]*.jar; do
    unzip -q -j "$jar" "com/sun/jna/darwin-$jna_arch/libjnidispatch.jnilib" -d "$app/Contents/Frameworks"
done
for jar in "$input"/javafx/javafx-graphics-*.jar; do
    unzip -q -j "$jar" '*.dylib' -d "$app/Contents/Frameworks"
done
clang_arch=$architecture
if [ "$architecture" = x64 ]; then clang_arch=x86_64; fi
xcrun clang -arch "$clang_arch" -dynamiclib -fobjc-arc -mmacosx-version-min=13.0 -framework Foundation -framework ServiceManagement build/macos/services.m -o "$app/Contents/Frameworks/libwisprail.dylib"
cp build/macos/app.wisprail.agent.plist "$app/Contents/Library/LaunchDaemons/"
# The user identity is written by the root installer, outside the signed application bundle.
if $release; then
    set -- --force --timestamp --options runtime --entitlements build/macos/entitlements.plist --sign "$WISPRAIL_APP_SIGN_IDENTITY"
else
    # Local ad-hoc signing needs no identity and makes no Gatekeeper/notarization claim.
    set -- --force --sign -
fi
find "$app/Contents" -type f \( -name '*.dylib' -o -name '*.jnilib' -o -name 'sing-box' -o -name 'java' -o -name 'jspawnhelper' -o -name 'WisprailAgent' \) > "$output/native-files.txt"
while IFS= read -r binary; do
    lipo "$binary" -verify_arch "$clang_arch"
    codesign "$@" "$binary"
done < "$output/native-files.txt"
shasum -a 256 "$app/Contents/app/engine/sing-box" | awk '{print $1}' > "$app/Contents/app/engine/sing-box.sha256"
codesign "$@" "$app/Contents/runtime"
codesign "$@" "$app"
codesign --verify --deep --strict "$app"
mkdir -p "$output/root/Applications"
cp -R "$app" "$output/root/Applications/"
cp -R build/macos/installer-scripts "$output/scripts"
chmod 755 "$output/scripts/preinstall" "$output/scripts/postinstall"
set -- --root "$output/root" --scripts "$output/scripts" --identifier app.wisprail --version "$package_version" --install-location /
if $release; then set -- "$@" --sign "$WISPRAIL_INSTALLER_SIGN_IDENTITY"; fi
pkgbuild "$@" "$output/Wisprail.pkg"
if $release; then
    xcrun notarytool submit "$output/Wisprail.pkg" --keychain-profile "$WISPRAIL_NOTARY_PROFILE" --wait
    xcrun stapler staple "$output/Wisprail.pkg"
fi
distribution="$output/distribution/Wisprail"
mkdir -p "$distribution"
ditto "$app" "$distribution/Wisprail.app"
cp "$output/Wisprail.pkg" build/THIRD_PARTY_NOTICES.md "$distribution/"
python3 - "$distribution/build-info.json" "$version" "$architecture" "$snapshot" "$release" <<'PY'
import json, pathlib, sys
path, version, architecture, snapshot, release = sys.argv[1:]
pathlib.Path(path).write_text(json.dumps(dict(version=version, platform="macos", architecture=architecture,
    sourceSnapshot=snapshot, engineVersion="1.14.2", applicationSigned=release == "true",
    signing="developer-id" if release == "true" else "ad-hoc", installerNotarized=release == "true"), indent=2))
PY
archive="$output/wisprail-$version-macos-$architecture.zip"
ditto -c -k --sequesterRsrc --keepParent "$distribution" "$archive"
printf '%s\n' "$archive"
