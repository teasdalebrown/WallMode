#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${1:-${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk}"
ADB_BIN="${ADB_BIN:-adb}"
PACKAGE_ID="io.github.rvbcrs.wallmode.debug"
ACTIVITY="io.github.rvbcrs.wallmode.MainActivity"
COMPONENT="${PACKAGE_ID}/${ACTIVITY}"

adb_cmd=("${ADB_BIN}")
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  adb_cmd+=("-s" "${ANDROID_SERIAL}")
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

"${adb_cmd[@]}" wait-for-device
"${adb_cmd[@]}" install -r "${APK_PATH}"

# MagicOS may restore its own launcher when a kiosk APK is replaced. Reassert
# WallMode after every install rather than waiting for the next reboot to reveal
# that the Home role was lost.
"${adb_cmd[@]}" shell cmd package set-home-activity "${COMPONENT}"

resolved_home="$(
  "${adb_cmd[@]}" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN \
    -c android.intent.category.HOME \
    | tr -d '\r' \
    | tail -n 1
)"
if [[ "${resolved_home}" != "${COMPONENT}" ]]; then
  echo "Home-role verification failed: expected ${COMPONENT}, got ${resolved_home}" >&2
  exit 1
fi

"${adb_cmd[@]}" shell am start -W -n "${COMPONENT}"
echo "Installed ${APK_PATH} and verified ${COMPONENT} as Android Home."
