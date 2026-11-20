# Playing the game on the phone: what the project learned

How Another Eden behaves under the app's taps, holds and screen reads, found while building and
testing macros. Per-dungeon notes: [dungeons/](dungeons/README.md); the cat routine:
[cat-scratchbook.md](cat-scratchbook.md); the Phantom Crystal Dimension:
[phantom-crystal.md](phantom-crystal.md).

## Input, battles and ads

- **Walking is a virtual stick.** The party walks in the direction a held finger *slides*, not
  toward the side of the screen you press. Press mid-screen and slide a little left or right
  (`walk:` or `drag:`); a hold that drifts the wrong way walks the wrong way.
- **Battles start with an intro animation** with no HUD; it shows as `unknown` for a second or
  two before the command screen.
- **Closing the game:** Android back on the field opens "Close the app?"; Yes exits cleanly
  (`stop-game`). Starting: splash → title ("Game Id..." while connecting, then "Continue") → tap
  → field (`start-game`).
- **Ads:** the first `start-game` of the day always takes "Watch Video" on the login-bonus ad
  offer; that reward goes to the inbox. Each watch button (login, the 5 daily chests, the shop)
  can play 1-3 commercials before the reward. Some end with "Reward granted", others only with a
  progress bar; `watch-ad` closes them with Back (also a Google Play sheet a commercial opens)
  and takes the reward dialog. With the premium subscription a "Thank you for subscribing"
  dialog replaces the ad (Close).
- **Battles (`Battle.kt`):** each front-row character's skill row is 5 boxes: normal attack,
  then skills 1-4 (x 0.177 / 0.346 / 0.499 / 0.653 / 0.807, y 0.759 on a 20:9 screen). Not every
  character has 4 skills; a missing or unusable one leaves the list open, and the normal attack
  closes it. Tapping the portrait again does not close it.

## Walking and encounters (behind `Route.kt` and `walk.yaml`)

- The party accelerates from rest, so distance isn't time × speed (the table in `walk.yaml`).
  After a battle the party stands still, so the rest of a walk starts from rest again.
- The party can start walking late (after a chest's popup a hold did nothing for ~1 s,
  2026-11-12); a walk usually shows on screen by 0.5-0.9 s.
- A battle is told by its own look (the screen going dark, the battle HUD), never by the
  field's buttons: the game hides them while the party walks. The encounter sequence and the
  frame-change numbers are in [the castle's notes](dungeons/burning-beast-kings-castle.md); dark
  scenes where the camera lags are in [Sea of Ice's](dungeons/sea-of-ice.md).
- A hold right after a flick onto a street can walk nothing: it is held again, pressed a little
  higher.
- The walk table for the cat maps was measured in Unigan.

## Menus

- **Party page** (Menu → Party, 2026-11-15): one party at a time, a big arrow at each side
  (drawn, not text), a row of dots at the bottom, one per party (10), the one in front marked.
  The arrows wrap around (right of the last party is the first). The party left in front is the
  active one: Back needs no confirmation. The dots were measured on a 2400x1080 phone
  (2026-11-16): small grey discs ~0.017 of the width apart at y ~0.948; the party in front is a
  grey ring with a white middle. Inside a dungeon the menu has no party list (Party / Food /
  Quit / Other).
- **Inbox**: no "receive all" button; opened items stay in the list for 90 days, marked
  "Opened"; the items not opened yet come first when the inbox opens. Opening an item asks the
  game server first (a dimmed "Connecting..." overlay for a few seconds).
- **Shop**: often opens under a "Connecting" overlay that hides the play button. Its daily
  commercial gives 10 Chronos Stones.
- **Starting**: a game data update asks "Confirming Downloaded Data ... Download Size: Approx.
  644.5 MB" (seen once). Some popups ignore taps (after the login-bonus ad when the game stayed
  open over the daily reset) and only Back closes them; a cold start's loading screens can take
  ~40 s of taps.
- **Commercials**: a commercial that opens a Google Play app sheet by itself does so often ~45 s
  in, and tapping its X only pulled down the status bar. Once left with Home, a commercial still
  on 20 s later came back by itself. A blind Back after the commercials once reached the field
  and left "Close the app?" open for the next macro.
