#!/bin/sh
set -eu
architecture=$1
snapshot=$2
mkdir /work/source
python3 - "$snapshot" <<'PY'
import hashlib, json, pathlib, sys, tarfile
source = pathlib.Path('/work/source')
with tarfile.open('/snapshot/source.tar') as archive:
    archive.extractall(source, filter='data')
manifest = json.loads((source / 'source-manifest.json').read_text(encoding='utf-8-sig'))
lines = []
for entry in manifest['files']:
    path = source / entry['path']
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != entry['sha256']:
        raise SystemExit('Source snapshot mismatch: ' + entry['path'])
    lines.append(entry['path'] + ' ' + digest)
actual = hashlib.sha256('\n'.join(lines).encode()).hexdigest()
if actual != sys.argv[1] or actual != manifest['sha256']:
    raise SystemExit('Source manifest identity mismatch')
# Windows checkouts may use CRLF. Only shell inputs need a Linux execution copy.
for path in [source / 'mvnw', *source.glob('deploy/**/*.sh')]:
    path.write_bytes(path.read_bytes().replace(b'\r\n', b'\n'))
PY
cd /work/source
python3 -B deploy/macos/cross/test_package.py
python3 deploy/fetch-engine.py --platform linux-amd64 --output /work/host-engine
xvfb-run -a -s '-screen 0 1280x1024x24' sh ./mvnw -B -ntp \
    -Dmaven.repo.local=/maven -Djavafx.platform=linux -Dprism.order=sw \
    -Dwisprail.engine=/work/host-engine/sing-box clean verify
# Preserve host test evidence before the target build cleans its own outputs.
mkdir -p /result/checks/backend /result/checks/frontend
cp -R backend/target/surefire-reports backend/target/failsafe-reports /result/checks/backend/
cp -R frontend/target/surefire-reports /result/checks/frontend/
sh deploy/package-macos.sh --cross --architecture "$architecture" \
    --source-snapshot "$snapshot" --output /work/package
# Keep .app, symlinks, chmod and PKG staging on Linux. Only completed archives cross NTFS.
mkdir /result/package
cp /work/package/wisprail-*-macos-"$architecture".zip /result/package/
cp /work/package/distribution/Wisprail/build-info.json /result/package/
