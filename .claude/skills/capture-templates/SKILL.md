---
name: capture-templates
description: Capture missing screen templates for another-macro from the live phone screen (needs a Remote Control session in Termux on the phone) and verify they match.
---

# Capture templates on the phone

Run this inside the Termux session on the phone, from `~/another-macro`.

1. `another-macro doctor` shows which templates each scenario still needs. Read
   each one's `notes:` in its `library.yaml`.
2. Get the game onto the screen that shows the element. Ask Bruno to do it, or
   navigate yourself with `another-macro tap X Y` / `hold` / `back`. Coordinates
   from 0 to 1 are fractions of the screen.
3. `another-macro screenshot -o /tmp/shot.png`, then view it with the Read tool.
   The image you see may be downscaled. The command prints the real size, so
   convert your estimate to real pixels.
4. Before registering, crop the candidate box and look at it:
   `python -c "import cv2;i=cv2.imread('/tmp/shot.png');cv2.imwrite('/tmp/crop.png',i[TOP:TOP+H,LEFT:LEFT+W])"`,
   then Read `/tmp/crop.png`. It should be tightly around a stable element: text or
   an icon, with no changing numbers or animated parts.
5. `another-macro capture-template <name> --from /tmp/shot.png --box LEFT,TOP,W,H`
   (add `--scenario <name>` for a scenario's own template).
6. Verify: `another-macro check --from /tmp/shot.png` must show the template FOUND
   (score at least 0.9 is comfortable) and the right screen. Then take screenshots of
   *other* screens and confirm it is **not** found there. If it is, pick a more
   distinctive element or raise `threshold`.
7. Never commit screenshots of Bruno's account, only the small template crops.
   Commit the PNGs and the updated `library.yaml`.
