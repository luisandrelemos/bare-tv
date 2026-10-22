# Bare: notes for Claude

A home screen (launcher) for Android TV and Google TV. Kotlin, built with Gradle, in `app/`. The companion script
`tools/tv.py` (Python standard library + adb) builds, installs, themes, backs up and restores a TV.

## Installing Bare for someone

The README tells people to ask you to set Bare up. They are often not technical: keep it short and friendly, say
what you're about to do on their TV before you do it, and let them do the taps on the TV.

1. **Ask** for what they didn't say: the TV's IP address (TV: Settings > Network & Internet > their network), their
   name for the greeting and their city for the weather (both optional).
2. **The TV** (they do it): Settings > System > About > press OK on *Android TV OS build* 7 times; back; Settings >
   System > Developer options > turn on *USB debugging* (and *Network/ADB debugging* if there is one). On some TVs
   it's Settings > Device Preferences > About > *Build*.
3. **Tools on the computer**: Python 3, a JDK 17 or newer, and the Android SDK with `platform-tools`,
   `platforms;android-34` and `build-tools;34.0.0`. Check what's there first (`python --version`, `java -version`,
   `adb version`, `$ANDROID_HOME`; `tools/tv.py` also finds the SDK under `%LOCALAPPDATA%\Android\Sdk` and
   `~/Library/Android/sdk`, and Android Studio's JDK) and install only what's missing. Ask before installing.
   - Windows: `winget install -e --id EclipseAdoptium.Temurin.17.JDK`, `winget install -e --id Python.Python.3.12`;
     the SDK command-line tools zip from developer.android.com/studio#command-line-tools-only, unpacked to
     `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, then `sdkmanager` as below.
   - macOS: `brew install --cask temurin android-commandlinetools`, then `sdkmanager` as below.
   - Linux: OpenJDK 17 and Python 3 from the package manager, the command-line tools zip as on Windows (into
     `~/Android/Sdk/cmdline-tools/latest`), then `sdkmanager` as below.
   - Then: `sdkmanager --sdk_root=<sdk> "platform-tools" "platforms;android-34" "build-tools;34.0.0"` and accept the
     licenses (`sdkmanager --licenses`). Set `ANDROID_HOME=<sdk>` for the session if it isn't set.
4. **Set it up**, unattended:
   `python tools/tv.py --tv <IP> setup --name "<name>" --city "<city>" --yes`
   (`--name ""` / `--city ""` for none). It connects, writes `themes/mine/theme.json`, builds, installs, makes Bare
   Home (with `--takeover`) and applies the theme. Read its output:
   - "Allow USB debugging?": tell them to tick *Always allow* on the TV and accept, then run it again.
   - It picked the first place matching the city: check it's the right one (`themes/mine/theme.json`), fix the
     coordinates if not, and `theme themes/mine`.
   - "The Wi-Fi network was saved by Google TV's setup wizard": nothing changed. Tell them to forget the network on
     the TV (Settings > Network & Internet > the network > Forget) and join it again, then run it again.
   - The TV doesn't answer: Wireless debugging only? `tv.py pair <IP:port> <code>` with what the TV shows under
     Developer options > Wireless debugging > Pair device with pairing code, then connect to the port shown there.
   - Fire TV, or the install refused: say plainly what the message means; nothing was changed.
5. **Offer to turn off tracking and ads.** If `debloat/` has a list for their TV, go through it with them: the
   active lines (tracking, ads, shop demo) after a yes, and each commented group only if they don't use it. Run it
   with `disable --list` (or `disable <packages>` for their picks), then check Home, Wi-Fi and the inputs still
   work. For a TV without a list, look at `pm list packages` with them and keep to what's clearly tracking or ads.
6. **Tell them** how to use it (README "Using Bare"), that they can ask you to change anything, and that
   `python tools/tv.py restore` (or asking you) puts the TV back as it was. When they're done changing things,
   offer `tv.py close`: it turns network debugging off and checks the port is closed.

## Changing it for someone

They'll ask in plain words: "make the background black", "hide the TCL apps", "put Netflix first", "the gear button
does nothing". Edit `themes/mine/theme.json` (keys in `themes/README.md`), then `python tools/tv.py theme themes/mine`.
Favorites: `tv.py favorites set <packages in order>`. A dead remote button: `tv.py keys listen` (Bare open, they
press it), then the code into `remote` in the theme. Package names: `tv.py info` or `adb shell pm list packages`.

## Rules that define the project

- **Zero dependencies.** Only the Android SDK and Kotlin's stdlib: no AndroidX, Compose, Leanback, RecyclerView,
  coroutines or JSON libraries (`org.json` is in Android). If something seems to need a library, write the small
  part you need instead.
- **One network request, and only if asked for.** The INTERNET permission exists for the weather alone (Open-Meteo,
  no key, only coordinates sent), and only when the theme sets `weather`. No analytics, accounts, ads or other calls.
- **Light on a slow TV.** Load apps and images off the main thread, decode bitmaps no bigger than the screen, animate
  only transforms (scale, translation, alpha), no blur or live effects. The TV's GPU is the limit, not the CPU:
  - no rounded clipping (`clipToOutline`, `clipPath`): round art with a `BitmapShader` (see `Tile.RoundedArt`);
  - no elevation or `translationZ` (shadows);
  - few full-screen layers: the window paints the background once, nothing else covers the whole screen twice;
  - fade view groups without offscreen layers (`Section`, `Faded` in `Ui.kt`), and never `withLayer()` on big ones;
  - no full-screen gradients redrawn per frame: paint once into a small bitmap and stretch it, animate a tint
    (`Glow`). A full-screen radial gradient crossfade alone took 22 ms a frame.
  Measure with `adb shell dumpsys gfxinfo io.github.luisandrelemos.bare` (reset, use the remote, read): every
  frame should stay under 16 ms (60 fps).
- **Everything reversible.** Never uninstall system apps: `pm disable-user` only, logged in `backups/`, undone by
  `restore`. Back up before any change to the TV.
- **A theme can never break Home.** Every theme value falls back to its default when missing or wrong.
- **Written from scratch.** Other launchers can be read for how a TV behaves, but no code or assets are copied.

## Layout

`app/src/main/java/io/github/luisandrelemos/bare/`, one job per file:

- `HomeActivity.kt`: lifecycle, loading (all off the main thread, into one `Content`), building the page, keys,
  focus memory, favorites. One page: the first screen, then inputs, the forecast and every app below it.
- `Header.kt`: greeting with the time of day's icon, clock (`res/font/clock.ttf`), date, the weather line.
- `WeatherCard.kt`: the forecast card. `Weather.kt`: Open-Meteo, cached on disk, refreshed every 30 min.
- `Tile.kt`: one app card: its art (pre-drawn at tile size by `Apps.load`), focus, long press, the dock's move state.
- `Page.kt`: a ScrollView that never scrolls by itself; Home glides it to center what has focus.
- `Glow.kt`: the background light in the focused app's color: one small white bitmap, stretched and tinted.
- `Apps.kt`: TV apps (`LEANBACK_LAUNCHER`), their order, the dock. `Favorites.kt`: the dock chosen on the TV or with `tv.py favorites`.
- `Inputs.kt`: HDMI/AV inputs and live TV, opened with `ACTION_VIEW` on the passthrough channel URI.
- `RemoteKeys.kt`: the remote's Settings and TV buttons from any app (accessibility service, keys only).
- `Theme.kt` + `ThemeProvider.kt`: defaults, the `theme.json` overlay, the wallpaper, and how `tv.py` writes them.
  Theme keys are documented in `themes/README.md`; keep both in sync.
- `CrashGuard.kt`: three crashes in five minutes and Home starts without the theme.
- `Ui.kt`: the small view helpers everything shares.

Checks: `./gradlew lintRelease assembleRelease` must pass with no lint warnings (`app/lint.xml` lists the few
that don't apply, each with its reason).

## Different TVs

Bare runs on any Android TV or Google TV 8+; what differs between makers lives in the theme, never in code paths for
one brand:

- Whatever differs (the remote's key codes, the live TV app, how inputs open) is a theme key with sensible defaults
  (`Keys` in `RemoteKeys.kt`, `liveTvPackage` / `inputAction` in `Theme.kt`). Known values for more makers go in the
  default lists (`Inputs.LIVE_TV_APPS`, `Inputs.AIRPLAY`, `tv.py STOCK_HOMES`), with where they were seen.
- `tv.py home` finds the TV's own home itself (Home's owner, or a known one among the candidates; `--stock` names it)
  and refuses Fire TV with an explanation. Every change it makes is checked and rolled back if it didn't take.
- Never force-stop Bare from `tv.py`: it unbinds `RemoteKeys`. Bare reloads a new theme on its own.
- What's learned about a model goes in `docs/devices/<make>-<model>.md` (`tv.py report <model>` starts one).
- Debloat lists are data, read by `tv.py disable --list`: one package per line, `#` comments.

## Testing on the TV

`python tools/tv.py setup` does everything; `install`, `home`, `theme themes/mine` one at a time. Say what will
change on the TV before running anything that changes it, and remember `restore`. `keys listen` shows what a remote
sends; `screenshot` shows what's on screen.
