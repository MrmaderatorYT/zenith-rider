# AGENTS.md

Guidance for AI coding agents (and humans) changing this repository. Read it before editing.

Zenith Rider is an endless 2D arcade game for Android: pure Java, native SDK only, every pixel and every sound generated procedurally. See [README.md](README.md) for the player-facing description.

## Hard constraints

These are requirements of the project, not preferences. A change that breaks one of them is wrong even if it compiles.

1. **Java only.** Java 17. No `.kt` files, no Kotlin plugin, no Kotlin libraries.
2. **No third-party dependencies.** The `dependencies {}` block in `app/build.gradle` stays empty. No game engines, no AndroidX.
3. **No binary assets.** No `.png`, `.jpg`, `.webp`, `.mp3`, `.wav`, `.ogg`, `.ttf` anywhere in `res/` or `assets/`. Graphics are drawn with `Canvas`, `Path`, `Paint`, gradients and `Matrix`; audio is synthesised into an `AudioTrack`. XML vector drawables are allowed (the launcher icon is one).
4. **Zero allocation in the game loop.** No `new` — and nothing that allocates implicitly — in anything reachable from `GameView.run()`, `GameEngine.update()` or `GameEngine.render()`, nor in the audio thread's `renderBlock()`. See "Zero-allocation rules" below.
5. **Two languages, always.** Every user-visible string exists in both `res/values/strings.xml` (English) and `res/values-uk/strings.xml` (Ukrainian).
6. **Original content.** The game is inspired by the mood of minimalist endless runners, but it must not reproduce another game's characters, names, art, icon or levels. Use the Zenith setting: snow dunes, fir groves, ancient arches, beacon towers, sun rings, lantern posts, rope lines, crystals. The scenery is grounded and quiet: no levitating stones and nothing neon.
7. **Package and SDK levels are fixed:** `com.ccs.zenith_rider`, `minSdk` 24, `targetSdk` 36, `compileSdk` 36 (Google Play requires a recent target; raise it together with the platform installed under the SDK).

## Build and verify

Requires JDK 17 on `JAVA_HOME` and an Android SDK path in `local.properties`.

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

```bash
adb shell am start -n com.ccs.zenith_rider/.MainActivity
```

There are no unit tests. Verify on a device:

- **Crashes:** `adb logcat -d -s AndroidRuntime:E` must be empty after exercising the change.
- **Screens:** `adb exec-out screencap -p > shot.png` and look at the result. Virtual coordinates map to pixels by `screenHeightPx / 720`.
- **One hour, one kind of weather:** stop the app and start it with extras, e.g. `adb shell am start -n com.ccs.zenith_rider/.MainActivity --ef zr_cycle 3 --ei zr_weather 6 --ez zr_demo true`. `zr_cycle` 0..6 pins the day cycle (0 dawn, 1 day, 2 golden hour, 3 sunset, 4 dusk, 5 night), `zr_weather` 0..6 pins the weather (clear, fair, snow, blizzard, fog, rain, storm), `zr_demo` hides the main menu so the attract-mode world fills the screen. A visual change has to be looked at in every phase and in the heavy weather kinds, not only at noon.
- **Motion:** a still frame does not show whether an animation works. Record with `adb shell screenrecord --time-limit 6 /sdcard/x.mp4`, pull the file and review a contact sheet of frames (for example `ffmpeg -i x.mp4 -vf "fps=10,scale=560:-1,tile=4x6" sheet.png`).
- **Frame rate:** `adb shell dumpsys SurfaceFlinger --timestats -clear -enable`, wait a few seconds, then `-dump` and read `totalFrames` / `droppedFrames` for the `SurfaceView[com.ccs.zenith_rider...]` layer.
- **Garbage collection:** `adb logcat -d | grep "zenith_rider.*GC freed"` should show nothing during play.

`physics/TerrainGenerator`, `physics/FastRandom` and `model/WorldObjects` have no Android imports on purpose: copy them out, compile them with plain `javac` next to a small `main`, call `reset(seed)` and `ensure(x)` over a few hundred seeds and count what appears in the pools. That is how to check a change to spawn rates (orbs, ropes, crystals per km) without a device.

Say plainly what you verified on a device and what you only compiled.

## Architecture

```
MainActivity ── GameView (SurfaceView + game thread)
                   └── GameEngine (state machine, rules, PhysicsSystem.Listener)
                         ├── physics/   TerrainGenerator, PhysicsSystem, FastRandom
                         ├── model/     Player, WorldObjects, UpgradeManager, MissionManager
                         ├── graphics/  ProceduralRenderer, EnvironmentPalette, WeatherSystem, ParticlePool
                         ├── audio/     ProceduralAudioEngine (own thread)
                         ├── ui/        HudAndMenuRenderer, Strings, TextBuf
                         └── storage/   SaveManager
```

- **`GameView`** runs the loop: drain input, run fixed steps of `1/60 s` from a time accumulator, render with the leftover fraction as the interpolation factor.
- **`GameEngine`** owns every subsystem and the state machine (`ST_MENU`, `ST_SHOP`, `ST_MISSIONS`, `ST_PERKS`, `ST_PLAY`, `ST_PAUSE`, `ST_DYING`, `ST_OVER`). It turns physics events into score, combo, Zenith Flow, missions, particles and sound. The menu background is the same simulation with an immortal autopilot rider (`player.godMode`).
- **`PhysicsSystem`** moves the rider and reports through `PhysicsSystem.Listener`. It never touches score, UI or audio.
- **`TerrainGenerator`** produces spline control points ahead of the rider and, with a lagging second cursor, decorates already generated terrain with objects from `WorldObjects`.
- **`ProceduralRenderer`** draws the world; **`HudAndMenuRenderer`** draws HUD and all menus and does touch hit-testing with the same rectangle table it draws from.

### Threads

| Thread | Does | Talks to others via |
|---|---|---|
| UI | touch events, lifecycle | `GameEngine.queueEvent()` (synchronised ring) |
| Game (`ZenithGameLoop`) | input processing, update, render | volatile setters and trigger counters on the audio engine |
| Audio (`ZenithAudio`) | sample synthesis | reads volatile parameters only |

Button actions run on the game thread, inside `processInput()`.

## Conventions

### Coordinates and units

- **Virtual screen:** height is always `720`; width is `720 * aspect`. All HUD/menu layout is in these units. Lay out relative to `vw` — do not assume a width.
- **World:** y grows downwards. `TerrainGenerator.UNITS_PER_METER` is `20`. The rider's `(x, y)` is the board contact point while alive and the **centre of mass** once `player.ragdoll` is set.
- **Terrain queries:** `heightAt` / `slopeAt` return the *ridable* surface (snow or ramp deck) and are what physics uses. `groundAt` is the bare snow and is what the renderer uses for the terrain polygon and for props.
- **Angles** are radians, clockwise positive on screen; a backflip is negative rotation.

### Zero-allocation rules

- State lives in primitive fields and preallocated arrays. Pools (`ParticlePool`, `WorldObjects`) are structure-of-arrays with `active` flags or swap-remove.
- Reuse the existing `Path`, `Paint`, `Matrix` and `TextBuf` fields: `path.rewind()` instead of a new `Path`.
- **Text with numbers:** build it in `TextBuf` and draw the `char[]`. Never concatenate strings or call `String.valueOf` / `String.format` in a draw path.
- **Shaders** are created only in `EnvironmentPalette.build()` (called on resize). To vary a gradient over time, cross-fade two cached shaders with paint alpha. Weather never rebuilds a shader either: see "Atmosphere" below.
- No lambdas, iterators, boxing, varargs or enhanced-for over collections in the loop.
- Allocation is acceptable only on discrete events outside the hot path: surface resize, and `SaveManager.save()` after a purchase, a settings change or the end of a run.

### Atmosphere: day cycle and weather

- **Palette.** `EnvironmentPalette.KEY` holds one complete palette per phase: dawn, day, golden hour, sunset, dusk, night. Every phase is muted and cohesive — the snow, the ridges and the haze all take the tint of the sky. Saturated colour is reserved for what the player must read: orbs (gold), the crystal glow (teal), the scarf. To add a colour slot, append a constant, bump `COLORS` and add one value to **every** row.
- **Depth.** Five ridges are drawn back to front, from nearly sky-coloured to `MID`, and a band of mist (`mist()`) is laid over each one before the next is drawn. Keep that order; a new background layer needs its own mist band or it will look pasted on.
- **Light.** `updateCelestial()` places the sun and the moon and derives the light direction. The rim light on the slope, the snow on the lit side of the firs and the lit flank of the landmarks all use it. `env.lights` (0..1) switches lanterns and windows on at dusk and under heavy cloud.
- **Weather is cosmetic.** `WeatherSystem` eases six values (cloud, snow, rain, fog, wind, storm) towards the targets of the current kind. It must never change physics, scoring or hit boxes. Draw from the six values, not from the kind, so every transition is gradual.
- **Stateless effects.** Snow, rain, fog banks and fireflies are not simulated: each element's position is a function of time, the camera and a fixed seed. There is nothing to pool.
- **Grading.** Cached gradients cannot be recoloured, so weather greys the solid colours in `EnvironmentPalette.applyWeather()` (call it right after `update()`), and the renderer lays translucent overlays over the gradients (`drawSky`, `drawBackGrade`, `drawFrontGrade`). A solid colour that has to match the graded sky goes through `gradeLikeSky()`.
- **Scenery is a pure function** of the cell index and `terrain.scenerySeed`: groves, landmarks and props are re-derived from hashes every frame and never stored, so each run looks different at no cost. Place them with `groundAt()` and skip cells near chasms and ramps.

### Strings

- `ui/Strings.java` loads both languages once into arrays indexed by integer constants. The order of `IDS` must match the constants exactly.
- **Append only.** Add new constants at the end and bump `COUNT`; do not renumber existing ones. Indexed families that grew later use helper methods (`upgradeName(i)`, `perkDesc(i)`, `hint(i)`), so call those instead of doing index arithmetic.
- Add the string to **both** XML files in the same change. Escape apostrophes (`\'`).
- Mission templates contain one `%d`; they are split into prefix and suffix at load time.

### Save data

- `SaveManager` keys are a compatibility surface — players have saves. Do not rename keys or change the meaning of stored indices (upgrade index, perk index, rider bit).
- New upgrades, perks and riders are appended at the end of their arrays.
- Call `save()` only on discrete events, never per frame.

### Gameplay rules worth knowing before changing them

- **Crystals are the only ground hazard.** Nothing else drawn on the slope may look like one: no rocks, boulders or dark stones, no small or hovering dark shapes near the rider's line, and no teal glow on the ground. Decorative props must be thin (grass, wind poles, lantern posts, bare trees) or clearly background: trees and landmarks stand behind the terrain line, and a landmark (arch gate, beacon tower, sun ring) is several times taller than the rider and drawn in the weathered-stone mid tone.
- **Crystal contact:** landing on one from the air smashes it and bounces the rider (`SMASH_STOMP`); riding into its side is fatal unless Zenith Flow, the dash after a landed trick, a smashing rider at speed, the shield or hover applies.
- **Dash.** Landing a trick on the snow — a flip, or coming off a rope — starts a dash of `PhysicsSystem.DASH_TIME` (one second): a small speed kick, a raised cruise speed, and crystals shatter on contact (`SMASH_DASH`). Landing on a rope does not start it.
- **Rope lines** are the grindable objects: a dark, taut rope between two posts (the pools and the physics still call them `rail*`). They belong to the world — never draw them as glowing or neon lines.
- **Orbs are scarce:** about 8 per km, down from about 88. They come as a trio on the open slope, or two or three with a rope, a ramp, an updraft or a chasm. Orb mission targets (`BASE_TARGET[ORBS]`) and mission rewards (`MissionManager.rewardFor`: 5 to 21 orbs, about one good run's worth) are scaled to that density; `MissionManager` rescales values saved on the old scale when it loads. Wisp flocks are loose swarms so that they do not read as rows of orbs.
- **Orbs are taken by touch only.** The magnet pull exists only while the magnet power-up runs (`player.magnetRadius`). `player.collecting` is false outside a live run, so nothing is picked up after a crash.
- **Flags are rare:** a wind pole with a streamer about once per 700 m, and one ribbon on an arch gate. More than that distracts from the slope.
- **Zenith Flow is a one-time purchase** (`UpgradeManager.FLOW_COST`). Tricks raise the combo, the combo lengthens the scarf, and at `FLOW_COMBO` the scarf ignites Flow — only if it is owned. When Flow ends the combo resets to 1. Flow and glide missions are not handed out before the unlock.
- **Flip speed:** `UpgradeManager.rotationSpeed()` is about 4.2 rad/s (one backflip ~1.5 s) and is multiplied by the rider's rotation factor. Do not raise it back to the old 7.6, which felt twitchy.
- **Holding in the air** flips the rider; while Zenith Flow is active it opens the glide wing instead.
- **Aegis Shield** covers crystal collisions and bad landings; **Chasm Tether** covers one chasm fall. They are separate modifiers.
- **Missions** stay ticked across runs; the set of three is replaced only when all three are done.
- **Score** is the sum of components (`GameEngine.totalScore()`): distance, trick score, orbs × 10, chasms × 250, goals × 1000. Combo multiplies tricks only.

### Adding things

- **A new world object type:** arrays and spawn/recycle in `WorldObjects`, placement in `TerrainGenerator.decorate()`, interaction in `PhysicsSystem`, consequence in a `GameEngine` listener callback, drawing in `ProceduralRenderer`.
- **A new upgrade, modifier or rider:** data in `UpgradeManager`, count in `SaveManager`, strings in both languages, an icon in `HudAndMenuRenderer.itemIcon()`. The shop list sizes its rows from `TAB_CARDS`, and at most `CARDS` rows fit.
- **A new kind of weather:** a constant, a row in `WeatherSystem.TARGET` and a weight in `WEIGHT`.
- **A new tree or landmark:** a branch in `drawGroves()` / `drawLandmarks()`; it must obey the crystal rule above.
- **A new screen or button:** add the id, lay it out in `layout()`, make it visible for its state in `visible()`, draw it, and handle it in `GameEngine.onButton()`.

## Style

- Match the surrounding code: explicit braces, `final` locals where already used, `f` suffix on float literals, no wildcard imports.
- Comments explain why, briefly. No `TODO`, no stubs, no placeholder implementations — every feature is finished when it lands.
- Keep visual tuning numbers next to the code that uses them; promote to a named constant only when shared.
