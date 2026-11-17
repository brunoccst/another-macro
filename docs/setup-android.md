# Phone setup (Android, no PC)

Everything runs on the phone, in the **Another Macro** app. It sees the game through
Android's screen capture and taps through an accessibility service. No PC and no root are
needed.

You do steps 1–3 once. Step 4 (Claude) is optional.

## 1. Install the app

There is no official release yet, so install the development build that follows `main`:

1. On the phone, open the
   [`dev-main` pre-release](../../../releases/tag/dev-main)
   and download `another-macro.apk`.
2. Open it. Android asks once to allow your browser (or file manager) to **install unknown
   apps**: allow it, then **Install**.
3. **Play Protect** may warn that the app is unknown. Choose **More details → Install
   anyway**. The app is signed with the same key on every build, so updates install over it
   and keep your settings.

Once there is an official release, its APK will be on the repository's
[latest release](../../../releases/latest) as `another-macro.apk`.

## 2. Turn on taps and screen capture

Open Another Macro. Use **≡** at the top left to move between the **Home**, **Macros** and
**System** pages. On **System → Setup**:

1. **Turn on taps.** This opens Accessibility: open **Another Macro** (on Samsung it is under
   **Installed apps**) and switch it on.
   - Greyed out with "Restricted setting"? Android 13+ blocks accessibility for apps installed
     outside the Play Store. Tap **App info** (or Settings → Apps → Another Macro), then
     **⋮ → Allow restricted settings**, and switch it on again.
2. **Allow screen capture** → **Start now**. Android
   asks again after every app update and restart; the app never turns capture on by itself.

The Home page shows what is still missing.

## 3. Play a macro

1. Open **Macros**. The app downloads the macro library from GitHub by itself the first time
   (it needs internet once, then checks for updates every 15 minutes).
2. The tabs group the macros: **In use**, **Daily**, **Another Dungeon**, **Farming**,
   **Game**, **Basic**, **Other** and **Custom**. Each macro's switch decides whether it is in
   use; the macros in use form the "In use" tab and the floating buttons' list.
3. Tap a macro to see what it does (a diagram), set its options and tap **Select this macro**.
   Options are kept per macro. Set the party's skills once on **Basic → battle**: every macro
   that fights uses them.
4. Open the game. While screen capture is on, the **floating buttons** show over it: the
   selected macro's name (tap it to pick another), ▶ start, ❚❚ pause, ■ stop, ↻ restart and
   ⌂ (switch between the app and the game). Drag ≡ to move them, tap it to fold them.
   Closing the app (swiping it away in recents) closes the buttons too, and with them any
   running macro, the connector and screen capture. Sending it to the background doesn't.

A line at the bottom right (the **live log**, System → Settings) says what the macro is
doing. The full log is on **System → Log**.

Where to start each macro:

| Macro | Start from |
|---|---|
| `daily-routine`, `start-game`, `stop-game` | anywhere (the game may be closed) |
| `daily-ads`, `shop-ad`, `inbox`, `cat-scratchbook` | the game's field (or closed: they start it) |
| `dogma-tower`, `burning-beast-kings-castle` | the field, next to the Another Dungeon door |
| `phantom-crystal` | next to the shining Another Dungeon door, holding a White Key |
| `grind-battles` | a map with random encounters |

The app never spends premium currency or confirms a purchase, and the dungeon macros stop at
0 keys instead of buying more.

## 4. Optional: let Claude drive the phone

The app can act as a **phone connector** for your own Claude chat (claude.ai or the Claude
apps, any plan, no API key). Claude can then look at the game, tap, walk, start and stop
macros, capture templates and write new macros.

Set it up once with the steps in **[the relay guide](../../../../macro-forge/blob/main/relay/README.md)**: make tokens on
**System → Claude**, deploy the small relay Worker to your free Cloudflare account, paste the
relay address into the app and add the connector address in claude.ai under **Settings →
Connectors → Add custom connector**.

Then, whenever Claude should use the phone:

- Keep Another Macro running with **taps** and **screen capture** on.
- On **System → Claude**, tap **Turn on**. It turns itself off after 30 minutes without use,
  unless **Stay on until I turn it off** is on.
- In claude.ai, set the connector's tool permission to **Always approved** (Settings →
  Connectors → the connector), so its tools load in every chat without a prompt per call.
- Let only one chat drive the phone at a time.

Claude can't tap the app's own floating buttons; it folds and unfolds them with its own
tool. The floating ■ stops whatever Claude is doing.

When Claude asks to tap something risky (sell, discard, exchange...), the app asks you on the
screen first. If nobody answers within 2 minutes, the question moves to a notification with
**Allow** and **Deny** for 15 minutes; Claude asks you in the chat and tries the same tap again
after you answer. Claude's `macro_status` also lists what happened on the phone since a chat
last looked (macro outcomes, scheduled runs, your answers), once each, so a new chat picks up
where things stand.

## 5. Optional: runs with nobody watching

On **System → Settings**:

- **Notifications:** when a macro ends (how it went) or a scheduled run can't start, a
  notification says so.
- **Daily schedule:** runs one macro (picked from the list of installed macros and routines;
  by default `daily-routine`) every day a few minutes
  after the game's daily reset (15:00 UTC by default, set in `game.yaml` `daily_reset:`; the reset time can be changed under Daily schedule), with that macro's own saved options. Screen
  capture must be on at that time; if it is off, the run is skipped and a notification says
  so. If another macro is running, it tries again every 15 minutes, up to an hour.

## Updates

- **Macros** update by themselves between runs (Macros → Macro library card shows the
  installed pack).
- **The app** follows its branch's builds (normally `main`): it checks every 3 minutes while
  the connector is on and idle, or on demand on **System → Updates → Install the newest
  build**.
  - Most changes only touch the app's logic and load as a **logic pack**, with no install and
    no prompt ([the logic pack](../../../../macro-forge/blob/main/docs/logic-pack.md)).
  - Other changes need a real install. If Play Protect blocks it, the app does not retry that
    version: install it from System → Updates and choose **Install anyway**.
  - After any install or restart, allow screen capture again (step 2.2) and turn the
    connector back on.
- **Test builds:** System → Updates → **Build to follow** lists `main` and the newest build of
  every open pull request. Tap one to install it (or only load its logic, when that is all
  that changed). The app then follows that branch, its macros included, until you pick
  `main` again. A build marked "older app than this one" can't be installed over a newer
  app, so the app only follows it and takes its next build.

## Tips

- **Screen lock:** macros and Claude wake the screen and keep it on while they work. With a
  PIN or pattern lock they can't get past the lock screen, so either use no screen lock or
  unlock the phone before starting.
- **Samsung Game Booster:** the app ignores its in-game panel and toasts, but its touch
  lock swallows every tap. Keep the touch lock off while a macro runs.
- **Battery:** let Another Macro run unrestricted in the battery settings so Android
  doesn't stop it in the background.
