# The logic pack: app changes without an install

Installing a new APK needs Play Protect's approval on the phone every time, so an unattended
test of an app change would stall at the first install. To avoid that, the app is split in two:

- **The shell** is the installed APK. It holds the permissions, taps, screen capture, OCR, vision,
  the phone connector, the UI, the updater and the macro runner. Every file of
  `android/app/src/main` outside `logic/` belongs to it.
- **The logic** is the `io.github.brunoccst.anothermacro.logic` package
  (`android/app/src/main/java/.../logic/`). It holds the macro steps (`FlowRun`), battles, Another
  Dungeons, routes, area navigation, the cat scratchbook and the Phantom Crystal. It is built into
  the APK as a fallback, and CI also ships it on its own as `logic.zip`.

## How a build reaches the phone

`android.yml` builds the APK and `logic.zip` (`gradle -p android logicPack`) from the same
compile. It attaches both to the branch's `dev-<branch>` pre-release, and its notes carry
`shell: <SHELL_ID>` and `commit: <sha>`.

A dev build checks its branch every 3 minutes while the connector is on and idle. Claude's
`update_app` tool and System → Updates check it on demand.

- **Same shell** (the notes' `shell` equals the installed app's `BuildConfig.SHELL_ID`): the app
  downloads `logic.zip` and loads it with a class loader that takes the `logic` package from the
  pack and everything else from the app. There is no install, no restart and no Play Protect
  prompt, and the next macro run uses the new logic. A macro that is already running keeps the
  logic it started with.
- **Shell changed:** the APK has to be installed as before, which needs the user's approval
  while Play Protect blocks it. A pack built for another shell is never loaded.

`SHELL_ID` is a hash of every shell file, plus the Gradle build files. Any change to them, even
a comment, means a new install.

## Rules for code

- **Overnight or unattended tests:** keep the change inside `logic/`. If the shell must change,
  batch those changes and ask the user for one install.
- **Shell code never names a class in `logic`.** It reaches the logic only through the
  `LogicEntry` / `FlowEngine` interfaces (`Logic.kt`), and creates `logic.Entry` by name.
  `LogicPackTest` enforces this.
- **Logic code may use anything in the shell.** The shell is its API, and changing a shell
  signature that the logic uses means a new app build anyway.
- **A new macro step** goes in `FlowRun.runAction` and in `Entry.ACTIONS`. Macros that use it
  stop being "unsupported" as soon as the pack loads.
- **Stored state:** the loaded pack lives in the app's files (`files/logic/current`). If it fails
  to load, the app logs why and runs the built-in logic.
