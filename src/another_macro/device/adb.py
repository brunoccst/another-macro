"""ADB driver.

Works in two setups with the same code:

* **On the phone itself (no PC):** Termux + `android-tools`, with Android's
  Wireless debugging paired to `localhost`. The phone ADB-connects to itself and
  this driver runs locally. See docs/setup-android.md.
* **From a PC:** the phone on USB or Wi-Fi ADB.

Screenshots use `exec-out screencap -p` (~0.3-1s per frame), which is plenty for
Another Eden's pace. Input goes through a persistent `adb shell`, so a tap costs
a pipe write instead of spawning a process.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import threading
import time

import cv2
import numpy as np

from .base import Device


class AdbError(RuntimeError):
    pass


def adb_path() -> str:
    path = shutil.which("adb")
    if not path:
        raise AdbError("adb not found. On the phone: `pkg install android-tools`; on a PC: install Android platform-tools.")
    return path


def run_adb(*args: str, serial: str | None = None, timeout: float = 20, binary: bool = False):
    cmd = [adb_path()] + (["-s", serial] if serial else []) + list(args)
    res = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if res.returncode != 0:
        raise AdbError(f"{' '.join(cmd)} failed: {res.stderr.decode(errors='replace').strip()}")
    return res.stdout if binary else res.stdout.decode(errors="replace")


def list_devices() -> list[tuple[str, str]]:
    """[(serial, state)] from `adb devices`."""
    out = run_adb("devices")
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def pick_serial(preferred: str | None = None) -> str:
    devices = [s for s, state in list_devices() if state == "device"]
    if preferred:
        if preferred not in devices:
            raise AdbError(f"device {preferred} not connected (connected: {devices or 'none'})")
        return preferred
    if not devices:
        raise AdbError("no ADB device connected. On the phone run `another-macro connect` (see docs/setup-android.md).")
    if len(devices) > 1:
        raise AdbError(f"several devices connected, pass --serial: {devices}")
    return devices[0]


class AdbDevice(Device):
    def __init__(self, serial: str | None = None) -> None:
        self.serial = pick_serial(serial)
        self._size: tuple[int, int] | None = None
        self._shell: subprocess.Popen | None = None
        self._lock = threading.Lock()

    # --- capture -------------------------------------------------------------
    def screenshot(self) -> np.ndarray:
        png = run_adb("exec-out", "screencap", "-p", serial=self.serial, binary=True)
        img = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise AdbError("screencap returned no image (is the screen on and unlocked?)")
        h, w = img.shape[:2]
        self._size = (w, h)  # follows rotation: the game runs in landscape
        return img

    def size(self) -> tuple[int, int]:
        if self._size is None:
            self.screenshot()
        return self._size  # type: ignore[return-value]

    # --- input -----------------------------------------------------------------
    def _send(self, command: str, wait_s: float = 0.0) -> None:
        with self._lock:
            if self._shell is None or self._shell.poll() is not None:
                self._shell = subprocess.Popen(
                    [adb_path(), "-s", self.serial, "shell"],
                    stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            assert self._shell.stdin is not None
            self._shell.stdin.write((command + "\n").encode())
            self._shell.stdin.flush()
        # commands run asynchronously in the shell; wait out gestures so the
        # caller's next screenshot sees their result
        if wait_s:
            time.sleep(wait_s)

    def tap_px(self, x: int, y: int) -> None:
        self._send(f"input tap {x} {y}", wait_s=0.1)

    def swipe_px(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> None:
        self._send(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}", wait_s=duration_ms / 1000 + 0.1)

    def back(self) -> None:
        self._send("input keyevent 4", wait_s=0.1)

    def start_app(self, package: str) -> None:
        run_adb("shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1", serial=self.serial)

    def find_packages(self, pattern: str = "eden") -> list[str]:
        out = run_adb("shell", "pm", "list", "packages", serial=self.serial)
        return [m for m in re.findall(r"package:(\S+)", out) if pattern.lower() in m.lower()]

    def close(self) -> None:
        if self._shell and self._shell.poll() is None:
            try:
                self._shell.stdin.close()  # type: ignore[union-attr]
                self._shell.wait(timeout=2)
            except Exception:
                self._shell.kill()


def connect_local(port: int | None = None, pairing: tuple[int, str] | None = None) -> str:
    """Pair (optional) and connect the phone's ADB to itself over Wireless debugging.

    `pairing` is (pairing_port, six_digit_code) from Settings → Developer options
    → Wireless debugging → "Pair device with pairing code". `port` is the
    connect port shown on the Wireless debugging screen (it changes when
    Wireless debugging is toggled).
    """
    if pairing:
        out = run_adb("pair", f"localhost:{pairing[0]}", pairing[1])
        if "Successfully" not in out:
            raise AdbError(f"pairing failed: {out.strip()}")
    if port is None:
        port = _discover_connect_port()
    out = run_adb("connect", f"localhost:{port}")
    if "connected" not in out:
        raise AdbError(f"connect failed: {out.strip()}")
    return f"localhost:{port}"


def _discover_connect_port() -> int:
    """Find the Wireless debugging port via mDNS (adb's own service discovery)."""
    try:
        out = run_adb("mdns", "services", timeout=10)
    except AdbError:
        out = ""
    m = re.search(r"_adb-tls-connect\._tcp\.?\s+\S+:(\d+)", out)
    if not m:
        raise AdbError("couldn't discover the Wireless debugging port; pass it explicitly: `another-macro connect --port <port>`")
    return int(m.group(1))
