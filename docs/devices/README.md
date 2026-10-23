# TVs

One page per TV model Bare has run on: what it is, how install and `home` went, its inputs and its remote's
buttons, and anything particular about it.

| TV | Android | Status |
|---|---|---|
| [TCL 55C6K](tcl-55c6k.md) | Google TV 14 | Everything works (`home --takeover`) |
| [Xiaomi Mi Box S 2nd Gen](xiaomi-mi-box-s-2nd-gen.md) | Google TV 11 | Everything works (`home --takeover`; app buttons need the theme's `remote`) |
| [Xiaomi TV MiTV-MOSR4](xiaomi-mitv-mosr4.md) | Google TV 11 | Works; the Assistant and Apps buttons don't yet |

## Add yours

```bash
python tools/tv.py report <model on the box>
```

writes `<make>-<model>.md` here with what Bare can read about the TV, changing nothing on it. Then fill in:

- how `install` and `home` went (`--takeover` needed? anything refused?);
- the remote's buttons: `python tools/tv.py keys listen`, press each, note what it does with Bare, and the theme
  `remote` entry that fixed any that did nothing;
- anything that surprised you.

Add a row to the table above, and open a pull request (or an issue with the page).
