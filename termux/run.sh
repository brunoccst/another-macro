#!/data/data/com.termux/files/usr/bin/bash
# Shared by the widget buttons: reconnect ADB to the phone itself, run a scenario,
# and report the result as a notification when Termux:API is installed.
#   run.sh <scenario> [another-macro play args...]
notify() {
  if command -v termux-notification >/dev/null; then
    termux-notification --id another-macro --title "Another Macro" --content "$1"
  fi
}
termux-wake-lock 2>/dev/null || true
trap 'termux-wake-unlock 2>/dev/null || true' EXIT

if ! another-macro doctor 2>&1 | grep -q '^device localhost'; then
  another-macro connect >/dev/null 2>&1 || { notify "Can't reach ADB — turn on Wireless debugging and try again"; exit 1; }
fi
notify "Running $1…"
out=$(another-macro play "$@" 2>&1 | tail -n 2)
notify "${out//$'\n'/ — }"
