#!/data/data/com.termux/files/usr/bin/bash
# One-time setup inside Termux on the phone. Safe to re-run (it also updates).
#   curl -fsSL https://raw.githubusercontent.com/brunoccst/another-macro/main/termux/install.sh | bash
# Set BRANCH=<name> to install a branch other than main (e.g. to try a PR).
set -euo pipefail
BRANCH="${BRANCH:-main}"

pkg update -y
# Termux ships OpenCV's Python bindings in its x11 repository
pkg install -y x11-repo
pkg update -y
pkg install -y python python-numpy git android-tools opencv-python

REPO="$HOME/another-macro"
if [ -d "$REPO/.git" ]; then
  git -C "$REPO" fetch origin "$BRANCH"
  git -C "$REPO" checkout -B "$BRANCH" "origin/$BRANCH"
else
  git clone -b "$BRANCH" https://github.com/brunoccst/another-macro "$REPO"
fi
# numpy/OpenCV come from pkg above, so only the pure-Python deps come from pip
pip install -e "$REPO"

# home-screen buttons (Termux:Widget): tasks/ run in the background so the game stays in front
mkdir -p "$HOME/.shortcuts/tasks"
cp "$REPO"/termux/shortcuts/*.sh "$HOME/.shortcuts/tasks/"
chmod +x "$HOME"/.shortcuts/tasks/*.sh

echo
echo "Installed. Next: pair Wireless debugging once (docs/setup-android.md), then run:"
echo "  another-macro connect && another-macro doctor"
