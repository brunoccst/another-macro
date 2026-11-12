---
name: capture-templates
description: Capture missing screen templates for another-macro from the live phone screen through the Another Macro connector, verify they match, and add them to the repo's template library.
---

# Capture templates from the phone

This needs the live phone through the **Another Macro** connector (the app open with screen
capture, taps and the connector on). See `docs/setup-android.md`.

1. Find what is missing: entries in a `library.yaml` (`src/another_macro/assets/templates/`
   or `src/another_macro/scenarios/<folder>/templates/`) with no `file`/`ref` yet. Read each
   one's `notes:`.
2. Get the game onto the screen that shows the element: ask the user, or navigate with the
   connector's `tap`, `swipe`, `walk` and `back`. Take a `screenshot` (1024 x 461 pixels)
   and look at it. `read_text` gives exact positions of labels.
3. Pick a tight box around a stable element: text or an icon, with no changing numbers,
   player names or animated parts.
4. Try it on the phone first: `save_template` with that box saves it to the user's own
   templates, and `find_template` must find it on this screen and **not** on other screens.
   If it shows up elsewhere, pick a more distinctive element.
5. For the repo (so every user gets it through the macro pack), cut the same box from the
   screenshot saved as a PNG and upscale it to the 2400 x 1080 reference:

   ```bash
   python -c "import cv2,sys; i=cv2.imread(sys.argv[1]); L,T,W,H=map(int,sys.argv[2].split(',')); s=2400/i.shape[1]; c=i[T:T+H,L:L+W]; cv2.imwrite(sys.argv[3], cv2.resize(c,(round(W*s),round(H*s)),interpolation=cv2.INTER_CUBIC))" shot.png L,T,W,H out.png
   ```

   Put the PNG next to the `library.yaml` and add the entry with `file`, `ref: [2400, 1080]`,
   a `region` a little larger than the box (fractions of the screen), and, because it was
   upscaled from a connector screenshot, `threshold: 0.75` and `scales: [0.9, 1.0, 1.1]`
   (see `src/another_macro/scenarios/inbox/templates/library.yaml`).
6. `python -m pytest -q` checks the macro pack. After the push, the phone picks up the new
   pack (`update_macros`); run the macro to confirm the template matches there.
7. Never commit screenshots of the user's account, only the small template crops.
