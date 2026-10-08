#!/bin/bash
set -euo pipefail
directory=$(cd "$(dirname "$0")" && pwd -P)
[[ "$directory" == /Applications/Wisprail.app/Contents/Resources ]] || {
  echo 'Uninstall only supports /Applications/Wisprail.app.' >&2
  exit 1
}
"$directory/maintain-installation.sh" --uninstall-component
# The fixed application bundle is removed only after verified service/network cleanup.
/bin/rm -rf /Applications/Wisprail.app
