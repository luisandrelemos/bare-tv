# Xiaomi Mi Box S 2nd Gen

A streaming box: no TV inputs, so Home shows no Inputs button or section. Everything else works.

| | |
|---|---|
| Model | Xiaomi MiTV-AFKR0 (`jaws`, Amlogic `sc2`) |
| Android | Google TV 11 (SDK 30), `RTT0.211222.001.737 release-keys` |
| Stock home | `com.google.android.apps.tv.launcherx` |
| Live TV app | none |
| Wi-Fi saved by | `android.uid.system:1000` (no need to join the network again) |
| Screen | 1920×1080, density 320 |

## Install

`install` and `home --takeover` worked as they are: `launcherx` and `com.google.android.tungsten.setupwraith` are
disabled, and Home, the remote buttons service and the theme all came up on the first try.

## Remote buttons

The app buttons are global keys: the system hands them to `com.xiaomi.android.tvsetup.partnercustomizer`, which asks
Google TV's home (`launcherx`) to open the app. With `launcherx` disabled they do nothing ("startAppByLauncherX() can
not handle" in `logcat`, tag `GlobalKey`). They never reach an app, so `keys listen` doesn't see them either: the codes
come from `logcat` (`interceptKeyTq keycode=`) or `dumpsys window` (the global key list).

Bare's remote buttons service catches them first. This in the theme brings them back:

```json
"remote": {
  "apps": {
    "193": "com.netflix.ninja",
    "194": "com.amazon.amazonvideo.livingroom",
    "190": "com.google.android.youtube.tv",
    "192": "com.mitv.tvhome.mitvplus"
  }
}
```

| Button | Code | With Bare |
|---|---|---|
| Netflix | 193 (`KEYCODE_BUTTON_6`) | Works with the theme entry above |
| Prime Video | 194 (`KEYCODE_BUTTON_7`) | Works with the theme entry above |
| YouTube | 190 (`KEYCODE_BUTTON_3`) | Opens what the theme names (here YouTube, or SmartTube instead) |
| Mi TV+ | 192 (`KEYCODE_BUTTON_5`) | Works with the theme entry above |
| Apps | 284 (`KEYCODE_ALL_APPS`) | The system's app list, as with the stock home |

## Cleaned up

With `debloat/xiaomi-mi-box-s.txt`, the tracking group: `com.miui.tv.analytics`, `com.xiaomi.statistic`,
`com.xiaomi.tvqs.overseas` and `com.google.android.feedback`. Home, Wi-Fi and the remote kept working.

## Speed

`tv.py bench`: half the frames under 7 ms, 9 in 10 under 8 ms (GPU 8 ms), smooth at 60 fps. About 66 MB (PSS).
