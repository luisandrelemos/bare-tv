# TCL 55C6K

The TV Bare was built and tested on. Status: **everything works**.

| | |
|---|---|
| Model | TCL Smart TV Pro (`G08` board, MediaTek), sold as 55C6K |
| Android | Google TV 14 (SDK 34), `tcl9618-user 14 UTT2.250416.001 AU02 release-keys` |
| Stock home | `com.google.android.apps.tv.launcherx` |
| Live TV app | `com.tcl.tv` (HDMI and tuner inputs from `com.tcl.tvinput`) |
| Screen | 3840×2160 panel, UI at 1920×1080 |

## Install

- `connect` on port 5555 worked as is (USB debugging on, then "Always allow" on the TV).
- `install`: fine.
- `home` alone: Android says `Success` and Home stays with launcherx. **`home --takeover` needed**: it disables
  launcherx and the setup wizard (`com.google.android.tungsten.setupwraith`).
- The Wi-Fi had been saved by the setup wizard during first setup, so `--takeover` stopped before changing anything.
  Forgetting the network and joining it again in Settings > Network & Internet fixed it (it then belongs to
  `com.android.tv.settings`).

## TV inputs

Tuner `com.tcl.tvinput/.TunerInputService/HW2`, HDMI 1–4 `com.tcl.tvinput/.TvPassThroughService/HW15`–`HW18`. A name
given in the TV's Settings > Inputs shows first ("PC · HDMI 1"). Every HDMI port reports the same state, so Bare
shows none.

AirPlay is MediaTek's `com.mediatek.AirplayAPK`, a software input with no app icon. Opening it directly gives a black
screen (its surface for a stream), and tuning its input with nothing playing goes back Home. The TV's Settings show it
with the broadcast `Show.Settings.AirplayAPK` to `com.tcl.airplay2`, which needs
`com.mediatek.permission.AirPlay.BroadCast` (a normal permission, which Bare declares): live TV switches to AirPlay
and AirPlay draws its own screen. That is on the video layer, so `tv.py screenshot` shows it black.

## Remote buttons

Read with `tools/tv.py keys listen` and the TV's own log (`logcat`, `TIDD: keyCode=`).

| Button | Code | Without Google TV's home | With Bare |
|---|---|---|---|
| Home | 3 | works | works |
| Settings (gear) | 83 `KEYCODE_NOTIFICATION` | does nothing | opens Settings (`RemoteKeys` default) |
| TV | 4001 (TCL_EPG) | does nothing | opens live TV (`RemoteKeys` default) |
| Input | 178 `KEYCODE_TV_INPUT` | works (TCL's own source panel) | works |
| Netflix | 4062 | works | works |
| Prime Video | 4099 | works | works |
| YouTube | 190 `KEYCODE_BUTTON_3` | works | works |
| Info | 165 `KEYCODE_INFO` | | |
| Number pad | 4056 | | |

## Cleaned up

`disable --list debloat/tcl-google-tv.txt` (tracking, ads, shop demo), plus the TCL utilities (Safety Guard, user
guide, repair guide, TCL account), Alexa and Play Games. Home, Wi-Fi, the HDMI inputs, live TV and the remote buttons
all still work, and after a reboot every app stayed disabled, Bare came back as Home and `RemoteKeys` bound again on
its own.

`tv.py bench`: half the frames under 11 ms, 9 in 10 under 14 ms, 0.2% janky, 38 MB.

## TCL quirks

- **Background services are blocked** by `TclAppBoot` unless the app has `AUTO_START` (`forbid bind service` in the
  log). `tv.py` grants it when it turns `RemoteKeys` on.
- **adb can't write Android/data** on some TCL firmware: the reason themes go through `ThemeProvider`.
- **Force-stopping Bare unbinds `RemoteKeys`**, so nothing in `tv.py` force-stops it.
- **The GPU is the bottleneck**: a full-screen radial gradient took 22 ms a frame; see CLAUDE.md for what keeps
  Home at 60 fps here.
