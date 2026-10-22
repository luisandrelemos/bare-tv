# Themes

A theme is a folder with a `theme.json`, a `wallpaper.jpg`, or both. Pushing one changes Bare's look and behavior
without rebuilding the app:

```bash
python tools/tv.py theme themes/mine          # yours, made by `tv.py setup` (kept out of git)
python tools/tv.py theme themes/oled          # true black, six columns, no names
python tools/tv.py theme --reset              # the built-in look
```

| Folder | What it is |
|---|---|
| `default/` | The built-in look written out, as a starting point |
| `oled/` | True black, six columns, no app names |
| `example/` | Every option below in use: copy it, keep what you want |
| `mine/` | Yours, made by `tv.py setup`; not in git, so your name and city stay on your computer |

Every key is optional. A missing, wrongly typed or out-of-range value keeps the default, and a file that doesn't
parse is ignored as a whole, so a theme can never break Home (and `tv.py theme` refuses invalid JSON before sending
it). Pushing a theme replaces the previous one completely: a theme without a wallpaper removes the old wallpaper.

## Look

| Key | Type | Default | Meaning |
|---|---|---|---|
| `colors.background` | `"#RRGGBB"` | `#07090D` | Behind everything, and the bottom of the gradient when `glow` is off |
| `colors.background_top` | `"#RRGGBB"` | `#1A2333` | Top of the gradient when `glow` is off (same as `background` if only that is set) |
| `colors.text` | `"#RRGGBB"` | `#F2F4F7` | Clock, date, icons, focused app's name |
| `colors.dim` | `"#RRGGBB"` | `#8E96A3` | Greeting, hints, other apps' names |
| `colors.accent` | `"#RRGGBB"` | `#FFFFFF` | Ring around the focused app, favorite dots |
| `colors.tile` | `"#RRGGBB"` | `#1A1D22` | Card behind apps without a banner |
| `glow` | bool | `true` | A soft light in the color of the app with focus. Off: the gradient. A wallpaper replaces both |
| `corner_radius` | 0–48 | 14 | Tile corners, in dp |
| `focus_scale` | 1.0–1.2 | 1.08 | How much the focused tile grows |
| `clock` | bool | `true` | Clock and date |
| `greeting` | bool | `true` | Good morning / afternoon / evening, with the time of day's icon |
| `name` | text | none | Your name in the greeting: "Good evening, Luis" |

`wallpaper.jpg` is scaled down to the screen on load. A 1920×1080 JPEG is plenty; bigger only wastes the TV's memory.

## Layout

| Key | Type | Default | Meaning |
|---|---|---|---|
| `buttons` | list | `["inputs", "wifi", "settings"]` | The buttons top right, in this order; leave one out to hide it |
| `sections` | list | `["inputs", "weather", "apps"]` | What's below the favorites, in this order. `apps` always stays: it's the way to every app |
| `columns` | 2–8 | 5 | Tiles per row in All apps |
| `labels` | bool | `true` | App names under the tiles in All apps (the favorites show the focused one's) |
| `dock_size` | 1–8 | 6 | Apps in the favorites row, until favorites are chosen on the TV |
| `pinned` | list of packages | `[]` | The favorites, in this order, and first in All apps. Empty: the streaming apps the TV has |
| `hidden` | list of packages | `[]` | Never shown |
| `app_labels` | `{"package": "Name"}` | `{}` | A tile's name instead of the app's own |

The favorites chosen on the TV (hold OK on an app) win over `pinned` once you've made one; they're kept on the TV,
not in the theme.

## Weather

| Key | Type | Default | Meaning |
|---|---|---|---|
| `weather` | `{"city", "latitude", "longitude"}` | none | The weather under the date and its own section, from [Open-Meteo](https://open-meteo.com) (free, no account). Without it Bare makes no network request at all |

`tv.py setup` finds the coordinates from the city's name. By hand:
`https://geocoding-api.open-meteo.com/v1/search?name=Porto`.

## Your TV

For the parts that differ from TV to TV. `python tools/tv.py report` writes what Bare can tell about yours into
`docs/devices/`.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `remote.settings` | list of keys | `[83, 4077, "KEYCODE_SETTINGS"]` | Buttons that open the TV's settings |
| `remote.live_tv` | list of keys | `[4001, 4020]` | Buttons that open live TV |
| `remote.apps` | `{"key": "package"}` | `{}` | Buttons that open an app: `{"4062": "com.netflix.ninja"}` |
| `remote.enabled` | bool | `true` | `false` stops Bare reading the remote's buttons at all (see below) |
| `live_tv_package` | package | known ones | The TV's live TV app, if Bare doesn't know it |
| `input_action` | action | none | Sent with an HDMI/AV input on TVs where nothing answers the standard way (XGIMI: `com.xgimi.action.hdmiPlayer`) |

A key is its number or its name (`"KEYCODE_BUTTON_3"`). A list replaces the defaults. To find what your remote's
buttons send, open Bare and run `python tools/tv.py keys listen`, then press them.

Many remotes' Settings, TV and app buttons were answered only by the TV's own home screen; Bare's `RemoteKeys`
service answers them instead, from any app. On a few firmware builds (a TCL Google TV 12 was one) any such service
stops held buttons repeating: holding volume steps it once. If that happens, set `"remote": {"enabled": false}`, or
`python tools/tv.py keys off`.

Find package names with `python tools/tv.py info`, or `adb shell pm list packages`.
