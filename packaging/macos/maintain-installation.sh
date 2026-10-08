#!/bin/bash
set -euo pipefail

[[ $(id -u) == 0 ]] || { echo 'Run installer maintenance as root.' >&2; exit 1; }
case "${1:-}" in
  --prepare-update|--finish-update|--uninstall-component) action=$1 ;;
  *) echo 'Unknown installer maintenance operation.' >&2; exit 2 ;;
esac
app=/Applications/Wisprail.app
helper="$app/Contents/MacOS/wisprail-maintenance"
if [[ ! -e "$app" ]]; then
  exit 0
fi
for path in "$app" "$app/Contents" "$app/Contents/MacOS" "$helper"; do
  [[ ! -L "$path" && -e "$path" && $(stat -f %u "$path") == 0 ]] || {
    echo 'Existing application is not a trusted system installation.' >&2
    exit 1
  }
done
[[ -x "$helper" ]] || { echo 'Existing application has no maintenance component.' >&2; exit 1; }
owner_file='/Library/Application Support/Wisprail/agent/owner.txt'
if [[ -f "$owner_file" ]]; then
  for path in '/Library/Application Support/Wisprail' '/Library/Application Support/Wisprail/agent' "$owner_file"; do
    [[ ! -L "$path" && $(stat -f %u "$path") == 0 ]] || {
      echo 'Service ownership cannot be confirmed.' >&2
      exit 1
    }
  done
  owner=$(cat "$owner_file")
else
  owner=$(stat -f %Su /dev/console)
fi
uid=$(id -u "$owner")
[[ "$uid" != 0 && "$owner" != loginwindow ]] || {
  echo 'Sign in as the application owner before installation maintenance.' >&2
  exit 1
}
/bin/launchctl asuser "$uid" /usr/bin/sudo -u "$owner" "$helper" "$action"
if [[ "$action" != --finish-update ]]; then
  "$helper" --cleanup-system
fi
