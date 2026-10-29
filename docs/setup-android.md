# Phone setup (Android, no PC)

Everything runs on the phone. Termux (a Linux terminal app) runs the macro, and the
macro controls the game through Android's own **Wireless debugging**: the phone
connects to itself over ADB, so it can take screenshots and tap. No root needed.

You do steps 1–4 once. After a reboot, only step 5 is needed again.

## 1. Install the apps (from F-Droid, not the Play Store)

The Play Store builds of Termux are outdated, and Termux add-ons only work when they
come from the same store as Termux.

- [Termux](https://f-droid.org/packages/com.termux/): required.
- [Termux:Widget](https://f-droid.org/packages/com.termux.widget/): the home-screen
  **play** and **stop** buttons.
- [Termux:API](https://f-droid.org/packages/com.termux.api/) (optional): posts a
  notification with the result when a run ends.

## 2. Install Another Macro

Open Termux and run:

```bash
pkg install -y curl
curl -fsSL https://raw.githubusercontent.com/brunoccst/another-macro/main/termux/install.sh | bash
```

This installs Python, OpenCV, ADB and git, clones this repo to `~/another-macro`, and
copies the widget buttons. Re-run it any time to update.

## 3. Turn on Wireless debugging

1. **Settings → About phone →** tap **Build number** 7 times to unlock Developer options.
2. **Settings → System → Developer options →** turn on **Wireless debugging**. You
   need to be on Wi-Fi; it doesn't need internet, only a Wi-Fi connection.
3. Optional but recommended: turn on **Disable adb authorization timeout** in the same
   menu, so you don't have to pair again every week.

## 4. Pair the phone with itself (once)

The pairing code dialog closes if you switch apps, so open **Settings and Termux in
split screen** (or as a pop-up window):

1. In Wireless debugging, tap **Pair device with pairing code**. It shows a
   six-digit code and `IP address & Port`, for example `192.168.1.5:41234`.
2. In Termux, use that port and code:

   ```bash
   another-macro connect --pair-port 41234 --code 123456
   ```

3. Check it with:

   ```bash
   another-macro doctor
   ```

   It should print `device localhost:<port>: <width>x<height>`.

## 5. After a reboot or Wi-Fi change

Android turns Wireless debugging off when Wi-Fi drops or the phone restarts. Turn it
back on (step 3.2) and run `another-macro connect`. The play widget also tries to
reconnect on its own.

If `connect` can't find the port, pass the one shown on the Wireless debugging screen
under **IP address & Port**: `another-macro connect --port 37215`.

## 6. Add the buttons

Long-press the home screen → **Widgets → Termux:Widget**, and place it. It lists:

- **Grind battles.sh**: stand on a map with random encounters, tap it, and switch to
  the game within a few seconds.
- **Stop macro.sh**: stops whatever is running at its next step.

Each run writes a log and any problem screenshots to `~/.another-macro/runs/`.

## Letting Claude drive the phone (optional)

To let Claude build and test scenarios directly on your phone, run Claude Code in
Termux and start Remote Control:

```bash
pkg install -y nodejs
npm install -g @anthropic-ai/claude-code
cd ~/another-macro && claude        # log in once, then exit
claude remote-control
```

Claude Code isn't officially supported on Android, but it generally runs in Termux.
