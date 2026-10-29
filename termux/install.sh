#!/data/data/com.termux/files/usr/bin/bash
# One-time setup inside Termux on the phone. Safe to re-run (it also updates).
#   curl -fsSL https://raw.githubusercontent.com/brunoccst/another-macro/main/termux/install.sh | bash
set -euo pipefail

pkg update -y
pkg install -y python git android-tools opencv-python

REPO="$HOME/another-macro"
if [ -d "$REPO/.git" ]; then
  git -C "$REPO" pull --ff-only
else
  git clone https://github.com/brunoccst/another-macro "$REPO"
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
