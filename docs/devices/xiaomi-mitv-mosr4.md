# Xiaomi TV (MiTV-MOSR4)

A 720p Xiaomi TV with Google TV. Everything works except the Google Assistant button (see below).

| | |
|---|---|
| Model | Xiaomi MiTV-MOSR4 (`irobot`, MediaTek `mt5867`) |
| Android | Google TV 11 (SDK 30), `RTM5.220609.003.1585` |
| Stock home | `com.google.android.apps.tv.launcherx` |
| Live TV app | `com.mitv.livetv` |
| Wi-Fi saved by | `com.google.android.tungsten.setupwraith` at first: forgetting the network and joining it again moved it to `com.android.tv.settings` |
| Screen | 1280×720, density 213 |

## Install

`install` worked as is. `home --takeover` first refused, because the Wi-Fi belonged to Google TV's setup wizard; after
the network was forgotten and joined again it disabled `launcherx` and `com.google.android.tungsten.setupwraith` and
made Bare Home.

## TV inputs

- `com.mediatek.tvinput/.tuner.TunerInputService/HW0` and `HW6` (tuners)
- `com.mediatek.tvinput/.hdmi.HDMIInputService/HW4`, `HW5` (HDMI 1 and 2)
- `com.mediatek.tvinput/.composite.CompositeInputService/HW1` (AV)

Bare shows TV, HDMI 1, HDMI 2 and AV.

## Remote buttons

The same Xiaomi global keys as the [Mi Box S](xiaomi-mi-box-s-2nd-gen.md): the app buttons go to
`com.xiaomi.android.tvsetup.partnercustomizer` (or straight to `launcherx`), which does nothing once `launcherx` is
disabled. The same theme `remote` entry (193 Netflix, 194 Prime Video, 190 YouTube, 192 Mi TV+) brings them back.

| Button | With Bare |
|---|---|
| Netflix, Prime Video, YouTube, Mi TV+ | Work with the theme's `remote` entry |
| Apps (`KEYCODE_ALL_APPS`) | Nothing: it goes to `launcherx` only |
| Google Assistant | Nothing: `katniss` logs "Voice input disabled, stopping Assistant interaction" over Bare and over other apps (not over YouTube, which has its own voice search). Not solved yet. |

## Cleaned up

The tracking group, as on the Mi Box S: `com.miui.tv.analytics`, `com.xiaomi.statistic`, `com.xiaomi.tvqs.overseas`
and `com.google.android.feedback`. Home, Wi-Fi and the internet kept working.

## Speed

`tv.py bench`: half the frames under 6 ms, 9 in 10 under 8 ms (GPU 6 ms), no missed frames. 42 MB.
