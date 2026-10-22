#!/usr/bin/env python3
"""Bare's companion on your computer: set up, install, theme, back up and restore a TV over adb.

    python tools/tv.py setup                          # the whole thing, step by step (start here)

    python tools/tv.py --tv 192.168.1.50 connect      # once; accept "Allow USB debugging?" on the TV
    python tools/tv.py pair 192.168.1.50:37123 123456 # TVs with only Wireless debugging: pair first
    python tools/tv.py --tv 192.168.1.50 info         # model, Android, who owns Home, what's on screen
    python tools/tv.py --tv 192.168.1.50 install      # backs up, builds if needed, installs (changes nothing else)
    python tools/tv.py --tv 192.168.1.50 home         # makes Bare the home screen (checked, rolled back if not)
    python tools/tv.py --tv 192.168.1.50 theme themes/oled
    python tools/tv.py --tv 192.168.1.50 keys listen  # press remote buttons, see their codes for the theme
    python tools/tv.py --tv 192.168.1.50 disable --list debloat/tcl-google-tv.txt
    python tools/tv.py --tv 192.168.1.50 bench        # how smooth Home runs (every frame under 16 ms is 60 fps)
    python tools/tv.py --tv 192.168.1.50 report       # writes docs/devices/<your-tv>.md
    python tools/tv.py --tv 192.168.1.50 restore      # the TV exactly as it was
    python tools/tv.py --tv 192.168.1.50 close        # when done: network debugging off, checked

Set BARE_TV=192.168.1.50 to skip --tv. Only the Python standard library and adb are needed.
"""
import argparse
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

PKG = "io.github.luisandrelemos.bare"
HOME_ACTIVITY = f"{PKG}/.HomeActivity"
MIN_SDK = 26
THEME_URI = f"content://{PKG}.theme"
THEME_FILES = ("theme.json", "wallpaper.jpg")
FAVORITES_URI = f"{THEME_URI}/favorites.txt"
# The remote's buttons (app/.../RemoteKeys.kt): an accessibility service that reads only the keys the theme maps.
REMOTE_KEYS = f"{PKG}/{PKG}.RemoteKeys"
KEY_LOG = "BareKeys"
HOME_QUERY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
HOMES_QUERY = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

# Stock home screens seen on TVs: Google TV, Android TV, the first Android TV, Xiaomi PatchWall, TCL, CVTE boards,
# Hisense, Whale OS, Verizon, Coocaa.
STOCK_HOMES = [
    "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
    "com.mitv.tvhome", "com.tcl.cyberui", "com.cvte.tv.launcher", "com.jamdeo.tv.vod", "com.zeasn.whale.saas",
    "com.verizon.tv.launcher", "com.tianci.movieplatform",
]
# Google TV's setup wizard: its RecoveryActivity takes Home once the stock home is off.
SETUPWRAITH = "com.google.android.tungsten.setupwraith"
# Fire OS keeps Home for Amazon's launcher whatever an app does (nothing can be disabled or overridden).
FIRE_OS_HOME = "com.amazon.tv.launcher"

# Never disabled, whatever is asked: picture, sound, inputs and tuner, remote and pairing, Wi-Fi, keyboard, Play.
# On TCL G08 boards, providers.config + initsetup + factory.view together dropped Wi-Fi and needed a re-pair.
PROTECTED = {
    "com.tcl.tv", "com.tcl.tvinput", "com.tcl.providers.config", "com.tcl.initsetup", "com.tcl.factory.view",
    "com.tcl.systemserver", "com.tcl.dashboard", "com.tcl.globalkeyoverlay", "com.tcl.systemui.plugin",
    "com.tcl.autopair", "com.tcl.suspension", "com.tcl.useragreement",
    "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
    "com.google.android.leanbackime", "com.google.android.inputmethod.latin",
    "com.sony.dtv.tvx", "com.mediatek.wwtv.tvcenter", "com.realtek.tv", "com.mitv.livetv", "android", PKG,
}
PROTECTED_PREFIXES = ("android.auto_generated", "android.ext", "com.android.", "com.mediatek.")

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
BACKUPS = ROOT / "backups"
SHOTS = ROOT / "shots"
MY_THEME = ROOT / "themes" / "mine"


# --- Tools on this computer ---

def find_adb():
    found = shutil.which("adb")
    if found:
        return found
    exe = "adb.exe" if os.name == "nt" else "adb"
    sdks = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.name == "nt":
        sdks.append(str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"))
    else:
        sdks += [str(Path.home() / "Library" / "Android" / "sdk"), str(Path.home() / "Android" / "Sdk")]
    for sdk in filter(None, sdks):
        candidate = Path(sdk) / "platform-tools" / exe
        if candidate.exists():
            return str(candidate)
    sys.exit("adb not found: install Android Studio (it brings adb), or set ANDROID_HOME.")


def build_env():
    """The environment for Gradle: JAVA_HOME as set, else the JDK bundled with Android Studio."""
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        return env
    studio = None
    if os.name == "nt":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Android Studio") as key:
                studio = Path(winreg.QueryValueEx(key, "Path")[0])
        except OSError:
            pass
    elif sys.platform == "darwin":
        studio = Path("/Applications/Android Studio.app/Contents")
    else:
        studio = Path("/opt/android-studio")
    for jbr in filter(None, [studio and studio / "jbr", studio and studio / "jbr" / "Contents" / "Home"]):
        if (jbr / "bin").is_dir():
            env["JAVA_HOME"] = str(jbr)
            break
    if not env.get("ANDROID_HOME"):
        adb = Path(find_adb())
        env["ANDROID_HOME"] = str(adb.parent.parent)
    return env


# --- The TV ---

class TV:
    def __init__(self, address):
        self.adb = find_adb()
        if address and ":" not in address:
            address += ":5555"
        self.address = address

    def run(self, *args, check=True, device=True, timeout=120):
        cmd = [self.adb]
        if device and self.address:
            cmd += ["-s", self.address]
        try:
            result = subprocess.run(cmd + list(args), capture_output=True, text=True, timeout=timeout,
                                    encoding="utf-8", errors="replace")
        except subprocess.TimeoutExpired:
            sys.exit(f"adb {' '.join(args)} timed out: is the TV on and on the same network?")
        out = (result.stdout + result.stderr).strip()
        if check and result.returncode != 0:
            sys.exit(f"adb {' '.join(args)} failed:\n{out}")
        return out

    def ready(self):
        """Makes sure the TV answers, reconnecting once if the connection dropped (standby, a network change)."""
        if self.run("get-state", check=False) == "device":
            return
        if self.address:
            self.run("disconnect", self.address, device=False, check=False)
            self.run("connect", self.address, device=False, check=False, timeout=20)
            if self.run("get-state", check=False) == "device":
                return
        sys.exit("The TV doesn't answer. Is it on (not in standby), on the same network, with USB debugging on?\n"
                 "Run `connect` (or `setup`) to set the connection up.")

    def shell(self, command, check=True):
        # One command per call: chained pm commands can silently stop after the first on some firmware.
        return self.run("shell", command, check=check)

    def prop(self, name):
        return self.shell(f"getprop {name}", check=False)

    def sdk(self):
        try:
            return int(self.prop("ro.build.version.sdk"))
        except ValueError:
            return 0

    def installed(self, package):
        return f"package:{package}" in lines(self.shell(f"pm list packages {package}", check=False))

    def disabled(self, package):
        return f"package:{package}" in lines(self.shell(f"pm list packages -d {package}", check=False))

    def home(self):
        """Who Home resolves to now, as package/activity, or None when no default is set (Android asks)."""
        out = lines(self.shell(HOME_QUERY, check=False))
        last = out[-1] if out else ""
        return last if "/" in last and not last.startswith("android/") else None

    def homes(self):
        """Every activity that claims Home."""
        return [h for h in lines(self.shell(HOMES_QUERY, check=False)) if "/" in h]

    def focused(self):
        out = self.shell("dumpsys activity activities", check=False)
        match = re.search(r"(?:topResumedActivity|mFocusedApp)=\S*\{[^}]*?\s(\S+/\S+)", out)
        return match.group(1) if match else None

    def wifi_owners(self):
        """The apps that saved each Wi-Fi network. Disabling one makes Android delete its networks."""
        return set(re.findall(r"cname=([^\s,]+)", self.shell("dumpsys wifi", check=False)))

    def name(self):
        return f"{self.prop('ro.product.manufacturer')} {self.prop('ro.product.model')}".strip()

    def backup_dir(self):
        name = self.prop("ro.serialno") or self.address or "default"
        return BACKUPS / "".join(c if c.isalnum() else "_" for c in name)

    def open_bare(self):
        # No force-stop: it would also unbind the remote buttons service. Bare reloads a new theme by itself.
        return self.shell(f"am start -W -n {HOME_ACTIVITY}", check=False)


def lines(text):
    return [line.strip() for line in text.splitlines() if line.strip()]


def is_bare(component):
    return bool(component) and component.split("/")[0] == PKG


def protected(package):
    return (package in PROTECTED or package.startswith(PROTECTED_PREFIXES)
            or "factory" in package or package.endswith(".fac"))


def ask(question, default=""):
    answer = input(f"{question}{f' [{default}]' if default else ''}: ").strip()
    return answer or default


# --- Packages Bare disables (always logged, always undone by restore) ---

def disabled_log(tv):
    return tv.backup_dir() / "disabled-by-bare.txt"


def disable(tv, packages):
    """pm disable-user, one by one, checked against the TV's own list and logged so `restore` can undo it."""
    log = disabled_log(tv)
    done = set(lines(log.read_text(encoding="utf-8"))) if log.exists() else set()
    newly = []
    for package in packages:
        if protected(package):
            print(f"Kept {package}: the TV needs it")
            continue
        if not tv.installed(package):
            print(f"Skipped {package}: not on this TV")
            continue
        tv.shell(f"pm disable-user --user 0 {package}", check=False)
        if tv.disabled(package):
            done.add(package)
            newly.append(package)
            print(f"Disabled {package} (not uninstalled)")
        else:
            print(f"Could not disable {package}: the firmware refused it")
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text("".join(p + "\n" for p in sorted(done)), encoding="utf-8")
    return newly


def enable(tv, packages):
    log = disabled_log(tv)
    done = set(lines(log.read_text(encoding="utf-8"))) if log.exists() else set()
    for package in packages:
        tv.shell(f"pm enable --user 0 {package}", check=False)
        done.discard(package)
        print(f"Enabled {package}")
    if log.exists():
        log.write_text("".join(p + "\n" for p in sorted(done)), encoding="utf-8")


def read_list(path):
    """A debloat list: one package per line, `#` starts a comment."""
    packages = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        package = line.split("#", 1)[0].strip()
        if re.fullmatch(r"[A-Za-z][\w]*(\.[\w]+)+", package):
            packages.append(package)
    return packages


# --- The remote's buttons ---

def remote_keys_bound(tv):
    """Whether the system runs the service (dumpsys lists running services by label, which starts with "Bare")."""
    bound = re.search(r"Bound services:\{(.*)", tv.shell("dumpsys accessibility", check=False))
    return bool(bound) and "label=Bare" in bound.group(1)


def set_remote_keys(tv, on):
    """Adds Bare's button service to the enabled accessibility services, or takes it out; others are left alone."""
    current = tv.shell("settings get secure enabled_accessibility_services", check=False).strip()
    others = [s for s in current.split(":") if s and s != "null" and s != REMOTE_KEYS]

    def put(services):
        if services:
            tv.shell(f"settings put secure enabled_accessibility_services {':'.join(services)}", check=False)
        else:
            tv.shell("settings delete secure enabled_accessibility_services", check=False)

    # Off first: the system only binds a service when it newly appears in the list.
    put(others)
    if on:
        # TCL's TclAppBoot refuses to start an app's services without AUTO_START ("forbid bind service" in the log);
        # some firmware calls the operation APP_AUTO_START. Elsewhere both fail quietly.
        tv.shell(f"appops set {PKG} AUTO_START allow || appops set {PKG} APP_AUTO_START allow", check=False)
        put(others + [REMOTE_KEYS])
        tv.shell("settings put secure accessibility_enabled 1", check=False)


def turn_on_remote_keys(tv):
    """Right after an update the system clears the app's services from the list a moment later: check, and retry."""
    for _ in range(3):
        set_remote_keys(tv, True)
        time.sleep(2)
        if remote_keys_bound(tv):
            return True
    print("The remote buttons service didn't start; `keys on` retries it.")
    return False


# --- Commands ---

def cmd_connect(tv, args):
    if not tv.address:
        sys.exit("Which TV? Pass --tv <IP> or set BARE_TV.")
    # After the TV changes network, adb keeps a dead "offline" connection that a plain connect won't replace.
    if "offline" in tv.run("get-state", check=False):
        tv.run("disconnect", tv.address, device=False, check=False)
    out = tv.run("connect", tv.address, device=False, timeout=20)
    print(out)
    # "failed to authenticate" or "unauthorized": the port is open and the TV is asking to allow this computer.
    if "authenticate" in out or "unauthorized" in out + tv.run("get-state", check=False):
        print("The TV is asking \"Allow USB debugging?\": tick \"Always allow\", accept, then run this again.")
        return False
    if "connected" not in out:
        print("\nThe TV didn't answer. Check that it's on, on the same network, and that USB debugging is on.\n"
              "Google TV sets often only offer Wireless debugging instead:\n"
              "  TV: Developer options > Wireless debugging > Pair device with pairing code\n"
              "  PC: python tools/tv.py pair <IP>:<pairing port> <code>\n"
              "  then connect to the port shown under Wireless debugging: --tv <IP>:<port>")
        return False
    print(f"Connected: {tv.name()}, Android {tv.prop('ro.build.version.release')} (SDK {tv.sdk()})")
    if tv.prop("ro.adb.secure") == "0":
        print("Careful: this firmware lets any device on your network into port 5555 without asking. "
              "Turn debugging off on the TV when you're done.")
    return True


def cmd_pair(tv, args):
    print(tv.run("pair", args.address, args.code, device=False, timeout=30))
    print("Paired. Now connect to the port shown under Wireless debugging (not the pairing port):\n"
          "  python tools/tv.py --tv <IP>:<port> connect")


def cmd_info(tv, args):
    print(f"Model:    {tv.name()} ({tv.prop('ro.product.device')}, {tv.prop('ro.build.display.id')})")
    print(f"Android:  {tv.prop('ro.build.version.release')} (SDK {tv.sdk()})")
    print(f"Home:     {tv.home() or 'no default (Android asks)'}")
    print(f"On screen: {tv.focused() or 'unknown'}")
    print(f"Bare:     {'installed' if tv.installed(PKG) else 'not installed'}")
    print(f"Wi-Fi saved by: {', '.join(sorted(tv.wifi_owners())) or 'unknown'}")
    print("Apps that claim Home:\n  " + "\n  ".join(tv.homes()))


def cmd_build(tv, args):
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if subprocess.run([str(gradlew), "assembleRelease", "-q"], cwd=ROOT, env=build_env()).returncode != 0:
        sys.exit("Build failed.")
    print(f"Built {APK.relative_to(ROOT)} ({APK.stat().st_size // 1024} KB)")


def cmd_backup(tv, args):
    folder = tv.backup_dir()
    if (folder / "packages.txt").exists() and not getattr(args, "force", False):
        print(f"Backup already in {folder.relative_to(ROOT)} (the first one is the factory state; --force to redo it).")
        return
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "packages.txt").write_text(tv.shell("pm list packages") + "\n", encoding="utf-8")
    (folder / "disabled-before.txt").write_text(tv.shell("pm list packages -d", check=False) + "\n", encoding="utf-8")
    (folder / "getprop.txt").write_text(tv.shell("getprop", check=False) + "\n", encoding="utf-8")
    for namespace in ("global", "secure", "system"):
        (folder / f"settings-{namespace}.txt").write_text(
            tv.shell(f"settings list {namespace}", check=False) + "\n", encoding="utf-8")
    home = tv.home()
    if home and not is_bare(home):
        (folder / "home.txt").write_text(home + "\n", encoding="utf-8")
    print(f"Backed up to {folder.relative_to(ROOT)} (home: {home or 'no default'})")


def cmd_install(tv, args):
    sdk = tv.sdk()
    if 0 < sdk < MIN_SDK:
        sys.exit(f"This TV runs SDK {sdk}; Bare needs {MIN_SDK} (Android 8) or newer. Nothing was changed.")
    if getattr(args, "build", False) or not APK.exists():
        cmd_build(tv, args)
    cmd_backup(tv, args)
    # adb install can hang on Android 14 waiting for an on-screen confirmation; pm install over the shell doesn't.
    staged = "/data/local/tmp/bare.apk"
    tv.run("push", str(APK), staged)
    out = tv.shell(f"pm install -r {staged}", check=False)
    tv.shell(f"rm -f {staged}", check=False)
    if "Success" not in out:
        hints = {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE": "a Bare signed with another key is installed: `restore --uninstall` first.",
            "INSTALL_FAILED_VERIFICATION_FAILURE": "this TV refuses apps from outside its store altogether.",
            "INSTALL_FAILED_OLDER_SDK": "the TV's Android is too old for Bare.",
        }
        hint = next((h for code, h in hints.items() if code in out), "")
        sys.exit(f"The TV refused the install: {out}\n{hint}")
    # Compile ahead of time: the first start is fast instead of interpreted.
    tv.shell(f"cmd package compile -m speed -f {PKG}", check=False)
    if is_bare(tv.home()):
        turn_on_remote_keys(tv)
    print("Installed. Nothing else on the TV changed: open Bare from the TV's app list to try it, then run `home`.")


def stock_home(tv, override=None):
    """The package of the TV's own home screen: as given, Home's owner now, or a known one among the candidates."""
    if override:
        return override
    home = tv.home()
    if home and not is_bare(home) and home.split("/")[0] != SETUPWRAITH:
        return home.split("/")[0]
    candidates = [h.split("/")[0] for h in tv.homes()]
    known = next((p for p in STOCK_HOMES if p in candidates), None)
    # Already taken over: the stock home is disabled, so no longer a candidate; the backup remembers it.
    return known or read_home(tv).split("/")[0] or None


def cmd_home(tv, args):
    if not tv.installed(PKG):
        sys.exit("Bare isn't installed yet: run `install` first.")
    if tv.installed(FIRE_OS_HOME):
        sys.exit("This is a Fire TV: Fire OS keeps Home for Amazon's launcher whatever an app does, so Bare can't\n"
                 "replace it. Bare still works as an app: open it from Your apps. Nothing was changed.")
    cmd_backup(tv, args)
    started = tv.open_bare()
    if "Error" in started:
        sys.exit(f"Bare didn't start, so Home stays as it is:\n{started}")

    tv.shell(f"cmd package set-home-activity --user 0 {HOME_ACTIVITY}", check=False)
    if is_bare(tv.home()):
        return finish_home(tv)

    stock = stock_home(tv, args.stock)
    if not stock:
        sys.exit("Couldn't tell which app is this TV's own home screen. Run `info`, then `home --stock <package>`.")
    if not args.takeover:
        print(f"Android said yes, but Home still opens {tv.home() or 'its chooser'}: the TV's own home ({stock})\n"
              "outranks any other. `home --takeover` disables it (and Google TV's setup wizard, which takes Home\n"
              "next); nothing is uninstalled and `restore` brings them back.")
        return False

    targets = [p for p in (stock, SETUPWRAITH) if tv.installed(p) and not tv.disabled(p)]
    owners = tv.wifi_owners()
    if SETUPWRAITH in targets and not args.ethernet:
        if not owners:
            sys.exit("Couldn't read who saved the TV's Wi-Fi, so nothing was changed. Try again, or add --ethernet "
                     "if the TV is on a cable.")
        if SETUPWRAITH in owners:
            sys.exit("The Wi-Fi network was saved by Google TV's setup wizard, and disabling the wizard would make\n"
                     "Android delete it (the TV drops off Wi-Fi, and adb with it). On the TV: Settings > Network &\n"
                     "Internet, forget the network and join it again, then run this once more. Nothing was changed.")

    # Order matters: Bare is already running; disable the others, then set Home, because Android forgets the
    # preference whenever the set of Home apps changes.
    newly = disable(tv, targets)
    tv.shell(f"cmd package set-home-activity --user 0 {HOME_ACTIVITY}", check=False)
    time.sleep(1)
    if is_bare(tv.home()):
        return finish_home(tv)

    print(f"Home still resolves to {tv.home() or 'nothing'}: putting everything back.")
    enable(tv, newly)
    tv.shell(f"cmd package set-home-activity --user 0 {read_home(tv)}", check=False)
    sys.exit(f"Home is back to {tv.home() or 'unknown'}. Run `report` and open an issue with it.")


def finish_home(tv):
    turn_on_remote_keys(tv)
    tv.shell("input keyevent KEYCODE_HOME", check=False)
    time.sleep(2)
    print(f"Bare is the home screen. On screen now: {tv.focused() or 'unknown'}")
    print("Press the real remote's Home button to confirm, and try its other buttons. A button that does nothing:\n"
          "`keys listen` shows its code for the theme. `restore` undoes all of it.")
    return True


def read_home(tv):
    """The home screen to go back to: the one backed up, else the first known stock home on the TV."""
    home_file = tv.backup_dir() / "home.txt"
    if home_file.exists():
        return home_file.read_text(encoding="utf-8").strip()
    for package in STOCK_HOMES:
        if tv.installed(package):
            out = lines(tv.shell(f"{HOME_QUERY} {package}", check=False))
            if out and "/" in out[-1]:
                return out[-1]
    return ""


def cmd_theme(tv, args):
    if not args.reset and not args.folder:
        sys.exit("Which theme? e.g. `theme themes/oled`, or `theme --reset`.")
    folder = Path(args.folder) if args.folder else None
    if folder and not any((folder / name).exists() for name in THEME_FILES):
        sys.exit(f"{folder} has no theme.json or wallpaper.jpg.")
    if folder and (folder / "theme.json").exists():
        try:
            json.loads((folder / "theme.json").read_text(encoding="utf-8"))
        except ValueError as error:
            sys.exit(f"{folder / 'theme.json'} isn't valid JSON ({error}). Nothing was sent.")
    for name in THEME_FILES:
        source = folder / name if folder else None
        if source and source.exists():
            staged = f"/data/local/tmp/bare-{name}"
            tv.run("push", str(source), staged)
            # Right after an install the provider can take a few seconds to register: try again a few times.
            for _ in range(5):
                out = tv.shell(f"content write --uri {THEME_URI}/{name} < {staged}", check=False)
                if "Could not find provider" not in out:
                    break
                time.sleep(2)
            tv.shell(f"rm -f {staged}", check=False)
            if "Exception" in out or "Error" in out:
                sys.exit(f"The TV refused {name}: {out}")
        else:
            # Switching themes removes the old theme's files, so nothing is left over.
            tv.shell(f"content delete --uri {THEME_URI}/{name}", check=False)
    tv.open_bare()
    print("Default theme back." if args.reset else f"Theme {folder.name} on the TV.")


def cmd_keys(tv, args):
    if args.action == "on":
        if turn_on_remote_keys(tv):
            print("Remote buttons service on.")
    elif args.action == "off":
        set_remote_keys(tv, False)
        print("Remote buttons service off: the TV's own handling of its buttons is back.")
    else:
        # Home logs every key that isn't for moving around: open it, then read what the presses logged.
        tv.shell(f"am start -n {HOME_ACTIVITY}", check=False)
        tv.shell("logcat -c", check=False)
        print(f"Press the remote's buttons now, one at a time ({args.seconds} s)...")
        time.sleep(args.seconds)
        found = re.findall(rf"{KEY_LOG}\s*:\s*(\d+) (\S+) scan=(\d+)", tv.shell(f"logcat -d -s {KEY_LOG}:I", check=False))
        if not found:
            print("No buttons seen. Some buttons never reach apps (Home, volume); the rest show here while Bare is open.")
            return
        for code, name, scan in dict.fromkeys(found):
            print(f"  {code:>5}  {name}  (scan code {scan})")
        print('Put them in your theme.json, e.g. "remote": {"settings": [83], "live_tv": [4001], '
              '"apps": {"4062": "com.netflix.ninja"}}')


def cmd_favorites(tv, args):
    """The favorites row: show it, set it (packages in order), or clear it (back to the theme's choice)."""
    if args.action == "set":
        if not args.packages:
            sys.exit("Which apps? `favorites set com.netflix.ninja com.spotify.tv.android ...`")
        staged = "/data/local/tmp/bare-favorites.txt"
        tv.shell(f"printf '%s\\n' {' '.join(args.packages)} > {staged}")
        out = tv.shell(f"content write --uri {FAVORITES_URI} < {staged}", check=False)
        tv.shell(f"rm -f {staged}", check=False)
        if "Exception" in out or "Error" in out:
            sys.exit(f"The TV refused the favorites: {out}")
    elif args.action == "clear":
        tv.shell(f"content delete --uri {FAVORITES_URI}", check=False)
    current = tv.shell(f"content read --uri {FAVORITES_URI}", check=False)
    if "Exception" in current or not current.strip():
        print("No favorites chosen: the row follows the theme's `pinned` list, or the streaming apps the TV has.")
    else:
        print("Favorites: " + " ".join(lines(current)))
    if args.action != "show":
        tv.open_bare()


def cmd_screenshot(tv, args):
    SHOTS.mkdir(exist_ok=True)
    target = SHOTS / (args.name or time.strftime("%Y%m%d-%H%M%S.png"))
    tv.shell("screencap -p /data/local/tmp/bare-shot.png")
    tv.run("pull", "/data/local/tmp/bare-shot.png", str(target))
    tv.shell("rm -f /data/local/tmp/bare-shot.png", check=False)
    if target.stat().st_size == 0:
        sys.exit(f"Empty screenshot: Live TV and HDMI can't be captured. On screen: {tv.focused() or 'unknown'}")
    print(f"Saved {target.relative_to(ROOT)}")


def cmd_disable(tv, args):
    packages = list(args.packages) + (read_list(args.list) if args.list else [])
    if not packages:
        sys.exit("Which apps? `disable <package> ...` or `disable --list <file>`.")
    cmd_backup(tv, args)
    owners = tv.wifi_owners()
    risky = [p for p in packages if p in owners]
    if risky:
        print(f"Kept {', '.join(risky)}: it saved a Wi-Fi network, which Android would delete. Forget and re-join "
              "the network on the TV first.")
    disable(tv, [p for p in packages if p not in owners])


def cmd_bench(tv, args):
    """The same walk through Home every time, then how long its frames took. Under 16 ms a frame is a smooth 60 fps."""
    tv.shell("input keyevent KEYCODE_HOME", check=False)
    time.sleep(2)
    tv.shell(f"dumpsys gfxinfo {PKG} reset", check=False)
    for key in ["RIGHT"] * 4 + ["LEFT"] * 4 + ["DOWN"] * 3 + ["UP"] * 3:
        tv.shell(f"input keyevent KEYCODE_DPAD_{key}", check=False)
        time.sleep(0.7)
    frames = tv.shell(f"dumpsys gfxinfo {PKG}", check=False)
    memory = tv.shell(f"dumpsys meminfo {PKG}", check=False)
    tv.shell("input keyevent KEYCODE_HOME", check=False)

    def number(pattern, text):
        match = re.search(pattern, text)
        return int(match.group(1)) if match else None

    total = number(r"Total frames rendered: (\d+)", frames)
    median = number(r"50th percentile: (\d+)ms", frames)
    slow = number(r"90th percentile: (\d+)ms", frames)
    gpu = number(r"50th gpu percentile: (\d+)ms", frames)
    janky = re.search(r"Janky frames: \d+ \(([\d.]+)%\)", frames)
    pss = number(r"TOTAL PSS:\s+(\d+)", memory)
    if not total:
        sys.exit("No frames recorded: is Bare the home screen?")
    print(f"Frames:  {total}, half of them under {median} ms, 9 in 10 under {slow} ms (GPU {gpu} ms)")
    print(f"Janky:   {janky.group(1) if janky else '?'}% of frames missed the screen's deadline")
    if pss:
        print(f"Memory:  {pss // 1024} MB")
    print("Smooth (60 fps)." if slow and slow <= 16 else "Some frames take longer than 16 ms: see CLAUDE.md, Light on a slow TV.")


def cmd_close(tv, args):
    """Turns network debugging off on the TV, then checks from here that its port really refuses connections."""
    host = tv.address.split(":")[0] if tv.address else None
    if not host:
        sys.exit("Which TV? Pass --tv <IP> or set BARE_TV.")
    print("Turning debugging off on the TV. tv.py can't reach it again until USB debugging is turned back on.")
    tv.shell("settings put global adb_wifi_enabled 0", check=False)
    tv.run("shell", "settings put global adb_enabled 0", check=False, timeout=10)
    tv.run("disconnect", tv.address, device=False, check=False)
    time.sleep(3)
    try:
        socket.create_connection((host, 5555), timeout=3).close()
        print("Port 5555 still answers: turn USB debugging off by hand in Developer options.")
    except ConnectionRefusedError:
        print("Closed: the TV refuses debugging connections. To also forget this computer: Developer options >\n"
              "Revoke USB debugging authorizations.")
    except OSError:
        print("No answer from the TV (closed, or off). Check Developer options > USB debugging is off.")


def cmd_report(tv, args):
    """A device page for docs/devices/: what Bare needs to know about this TV, read without changing anything."""
    make = tv.prop("ro.product.manufacturer") or "unknown"
    # Firmware often says only "Smart TV Pro": the name on the box tells TVs apart.
    model = args.model or tv.prop("ro.product.model") or tv.prop("ro.product.device") or "tv"
    slug = re.sub(r"[^a-z0-9]+", "-", f"{make} {model}".lower()).strip("-")
    inputs = re.findall(r"^\s{2,}(\S+/\S+): TvInputInfo", tv.shell("dumpsys tv_input", check=False), re.M)
    known_tv = [p for p in ("com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx",
                            "com.realtek.tv", "com.mitv.livetv", "com.smartdevice.livetv", "com.android.tv")
                if tv.installed(p)]
    page = ROOT / "docs" / "devices" / f"{slug}.md"
    page.parent.mkdir(parents=True, exist_ok=True)
    page.write_text(f"""# {make} {model}

Generated by `tools/tv.py report` on {time.strftime("%Y-%m-%d")}. Add what you find, and send it in.

| | |
|---|---|
| Model | {tv.name()} (`{tv.prop('ro.product.device')}`) |
| Android | {tv.prop('ro.build.version.release')} (SDK {tv.sdk()}), `{tv.prop('ro.build.display.id')}` |
| Home now | `{tv.home() or 'no default'}` |
| Stock home | `{stock_home(tv) or 'unknown'}` |
| Live TV app | {', '.join(f'`{p}`' for p in known_tv) or 'none known: set `live_tv_package` in the theme'} |
| Wi-Fi saved by | {', '.join(f'`{o}`' for o in sorted(tv.wifi_owners())) or 'unknown'} |

## Apps that claim Home

{chr(10).join(f'- `{h}`' for h in tv.homes()) or '- none listed'}

## TV inputs

{chr(10).join(f'- `{i}`' for i in sorted(set(inputs))) or '- none (a streaming box, or no TV input framework)'}

## Remote buttons

Run `python tools/tv.py keys listen` and press each button. List what each sends, what it did with the stock home,
and what it does with Bare (and the theme `remote` entry that fixed it, if one did).

| Button | Code | With Bare |
|---|---|---|
| Settings | | |
| TV / Input | | |

## Notes

- Install: worked / failed with ...
- `home`: worked as is / needed `--takeover` / ...
""", encoding="utf-8")
    print(f"Wrote {page.relative_to(ROOT)}")


def cmd_restore(tv, args):
    set_remote_keys(tv, False)
    log = disabled_log(tv)
    if log.exists():
        enable(tv, lines(log.read_text(encoding="utf-8")))
        log.unlink()
    home = read_home(tv)
    if home:
        tv.shell(f"cmd package set-home-activity --user 0 {home}", check=False)
    print(f"Home: {tv.home() or 'no default (the TV will ask which home to use)'}")
    if args.uninstall:
        print(tv.run("uninstall", PKG, check=False))
    tv.shell("input keyevent KEYCODE_HOME", check=False)
    print("Done. When you're finished with adb, turn USB debugging off on the TV.")


def find_place(city, pick_first=False):
    """A city's coordinates from Open-Meteo's geocoding (run here, on the computer), or None."""
    url = "https://geocoding-api.open-meteo.com/v1/search?" + urllib.parse.urlencode({"name": city, "count": 5})
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            results = json.load(response).get("results") or []
    except (OSError, ValueError):
        print("Couldn't look the city up (no internet?): the weather stays off for now.")
        return None
    for i, place in enumerate(results, 1):
        print(f"  {i}. {place['name']}, {place.get('admin1', '')}, {place.get('country', '')}")
    if not results:
        print("No place by that name.")
        return None
    choice = "1" if pick_first else ask("Which one", "1")
    place = results[int(choice) - 1] if choice.isdigit() and 0 < int(choice) <= len(results) else results[0]
    return {"city": place["name"], "latitude": round(place["latitude"], 4), "longitude": round(place["longitude"], 4)}


def cmd_setup(tv, args):
    """Everything, step by step: connect, install, make it Home, and a theme of your own."""
    print("Bare setup. On the TV, turn on Developer options > USB debugging first (see README).\n")
    if not tv.address:
        tv = TV(ask("The TV's IP address (Settings > Network & Internet)"))
    while not cmd_connect(tv, args):
        if args.yes or ask("Try again? (y/n)", "y").lower() != "y":
            sys.exit("Stopped. Nothing on the TV was changed.")

    MY_THEME.mkdir(parents=True, exist_ok=True)
    theme_file = MY_THEME / "theme.json"
    theme = json.loads(theme_file.read_text(encoding="utf-8")) if theme_file.exists() else {}
    name = args.name if args.name is not None else ask("Your name for the greeting (empty for none)", theme.get("name", ""))
    if name:
        theme["name"] = name
    else:
        theme.pop("name", None)
    city = args.city if args.city is not None else ask("Your city for the weather (empty for none)",
                                                         theme.get("weather", {}).get("city", ""))
    if not city:
        theme.pop("weather", None)
    elif city != theme.get("weather", {}).get("city"):
        place = find_place(city, pick_first=args.yes)
        if place:
            theme["weather"] = place
    theme_file.write_text(json.dumps(theme, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Your theme: {theme_file.relative_to(ROOT)} (change anything in it: see themes/README.md)\n")

    args.build = True
    cmd_install(tv, args)
    cmd_theme(tv, argparse.Namespace(folder=str(MY_THEME), reset=False))
    if args.yes or ask("Make Bare the home screen now? (y/n)", "y").lower() == "y":
        args.takeover = False
        if cmd_home(tv, args) is False and (args.yes or ask("Take over from the TV's own home? (y/n)", "y").lower() == "y"):
            args.takeover = True
            cmd_home(tv, args)
    print("\nAll set. Change the look any time with `python tools/tv.py theme themes/mine`, and undo everything "
          "with `restore`.")


COMMANDS = {
    "setup": cmd_setup, "favorites": cmd_favorites, "connect": cmd_connect, "pair": cmd_pair, "info": cmd_info, "build": cmd_build,
    "backup": cmd_backup, "install": cmd_install, "home": cmd_home, "theme": cmd_theme, "keys": cmd_keys,
    "screenshot": cmd_screenshot, "disable": cmd_disable, "bench": cmd_bench, "report": cmd_report,
    "restore": cmd_restore, "close": cmd_close,
}


def main():
    parser = argparse.ArgumentParser(description="Set up, install and manage Bare on a TV over adb.")
    parser.add_argument("--tv", default=os.environ.get("BARE_TV"), help="the TV's IP (or IP:port); or set BARE_TV")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("setup", help="everything, step by step: connect, install, Home, your theme")
    p.add_argument("--name", help="your name for the greeting (\"\" for none); asked if not given")
    p.add_argument("--city", help="your city for the weather (\"\" for none); asked if not given")
    p.add_argument("--yes", action="store_true", help="no questions: yes to everything (for running it unattended)")
    sub.add_parser("connect", help="connect to the TV over the network")
    p = sub.add_parser("pair", help="pair with a TV that only offers Wireless debugging")
    p.add_argument("address", help="IP:port shown under Pair device with pairing code")
    p.add_argument("code", help="the pairing code")
    sub.add_parser("info", help="model, Android, who owns Home, what's on screen")
    sub.add_parser("build", help="build the app")
    p = sub.add_parser("backup", help="save packages, settings and the home screen (once: the factory state)")
    p.add_argument("--force", action="store_true", help="overwrite an existing backup")
    p = sub.add_parser("install", help="back up, then install Bare (changes nothing else)")
    p.add_argument("--build", action="store_true", help="rebuild even if an app is already built")
    p = sub.add_parser("home", help="make Bare the home screen, checked and rolled back if it doesn't take")
    p.add_argument("--takeover", action="store_true", help="also disable the TV's own home (and Google TV's setup wizard)")
    p.add_argument("--stock", help="the TV's own home package, if Bare can't tell")
    p.add_argument("--ethernet", action="store_true", help="the TV is on a cable: skip the Wi-Fi owner check")
    p = sub.add_parser("theme", help="push a theme folder, or --reset to the default look")
    p.add_argument("folder", nargs="?")
    p.add_argument("--reset", action="store_true")
    p = sub.add_parser("keys", help="the remote's buttons: listen (show their codes), on, off")
    p.add_argument("action", choices=["listen", "on", "off"])
    p.add_argument("--seconds", type=int, default=20)
    p = sub.add_parser("favorites", help="the favorites row: show, set <packages in order>, or clear")
    p.add_argument("action", choices=["show", "set", "clear"], nargs="?", default="show")
    p.add_argument("packages", nargs="*")
    p = sub.add_parser("screenshot", help="capture the TV's screen into shots/")
    p.add_argument("name", nargs="?")
    p = sub.add_parser("disable", help="disable apps (never uninstalls; protected apps are refused)")
    p.add_argument("packages", nargs="*")
    p.add_argument("--list", help="a debloat list: one package per line, # comments")
    sub.add_parser("bench", help="how smooth Home runs: frame times and memory")
    sub.add_parser("close", help="when you're done: turn network debugging off on the TV, and check it's closed")
    p = sub.add_parser("report", help="write docs/devices/<your-tv>.md about this TV, changing nothing")
    p.add_argument("model", nargs="?", help="the model name on the box, e.g. 55C6K")
    p = sub.add_parser("restore", help="re-enable what Bare disabled and bring the original home back")
    p.add_argument("--uninstall", action="store_true", help="also remove Bare")

    args = parser.parse_args()
    for flag in ("build", "takeover", "ethernet", "force", "yes"):
        setattr(args, flag, getattr(args, flag, False))
    for option in ("stock", "name", "city"):
        setattr(args, option, getattr(args, option, None))
    tv = TV(args.tv)
    # Every command but these talks to the TV: check it answers before anything, not halfway through.
    if args.command not in ("setup", "connect", "pair", "build"):
        tv.ready()
    COMMANDS[args.command](tv, args)


if __name__ == "__main__":
    main()
