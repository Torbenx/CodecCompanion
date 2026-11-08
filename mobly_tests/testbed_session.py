"""Prepare the test phones for a testing session and restore them afterwards.

Keep it running while testing, by hand or with Mobly, over as many attempts as
needed. Until Enter or Ctrl+C is pressed:

  * Bluetooth is off on every phone, so the Qudelix-5K can only connect to the
    phone under test. Tests turn it on for that phone and off again.
  * Screens stay on while plugged in, dimmed against OLED burn-in.
  * Every few seconds each phone is checked. A phone that isn't reachable over
    adb, isn't plugged in, has its screen off or is locked, and more than one
    phone with Bluetooth on, beeps and prints a warning. The end of the session
    lists every problem with its time, to tell which attempts it affected.

At the end every phone gets its original settings back. They're saved to a
state file before anything changes. If a session doesn't end normally, for
example because its window was closed, the next session reuses the saved
settings, or `restore` restores them right away.

The phones are all devices connected over adb when the session starts. Setup,
from this folder:
  python -m venv .venv
  .venv/Scripts/pip install -r requirements.txt   (.venv/bin/pip on Linux and macOS)

Usage:
  .venv/Scripts/python testbed_session.py
  .venv/Scripts/python testbed_session.py restore
"""

import argparse
import json
import os
import pathlib
import sys
import threading
import time

from mobly.controllers import android_device
from mobly.controllers.android_device_lib.adb import AdbError, AdbProxy, AdbTimeoutError

from lib import app
from lib import device_state

DEFAULT_STATE_FILE = pathlib.Path(__file__).resolve().parent / ".session_state.json"

STAY_ON = ("global", "stay_on_while_plugged_in")
BRIGHTNESS_MODE = ("system", "screen_brightness_mode")
BRIGHTNESS = ("system", "screen_brightness")
# Restored in reverse order, so the brightness is set before automatic brightness is turned back on
SETTINGS = [STAY_ON, BRIGHTNESS_MODE, BRIGHTNESS]
MANUAL_BRIGHTNESS = "0"

DEVICE_ERRORS = (AdbError, AdbTimeoutError, RuntimeError, TimeoutError)


def timestamp():
    return time.strftime("%H:%M:%S")


class Phone:
    def __init__(self, serial, model):
        self.serial = serial
        self.adb = AdbProxy(serial)
        self.name = f"{model} ({serial})"


class StateFile:
    """The original settings of each phone, as {serial: {"name", "bluetooth", "settings"}}."""

    def __init__(self, path):
        self.path = path
        self.phones = json.loads(path.read_text()) if path.exists() else {}

    def save(self):
        if not self.phones:
            self.path.unlink(missing_ok=True)
            return
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.phones, indent=2) + "\n")
        os.replace(tmp, self.path)


def save_originals(phone, state, settings):
    """Saves the settings this session changes, unless an earlier session already saved them."""
    saved = state.phones.setdefault(phone.serial, {"name": phone.name})
    if "bluetooth" not in saved:
        saved["bluetooth"] = device_state.is_bluetooth_on(phone.adb)
    saved_settings = saved.setdefault("settings", {})
    for namespace, key in settings:
        name = f"{namespace}/{key}"
        if name not in saved_settings:
            saved_settings[name] = device_state.get_setting(phone.adb, namespace, key)
    state.save()


def install_bluetooth_switch(phone):
    """Installs the snippet APK where switching Bluetooth needs it, unless it's installed already."""
    if not device_state.needs_bluetooth_switch(phone.adb) or app.is_installed(phone.adb, app.SNIPPET_PACKAGE):
        return
    if not app.SNIPPET_APK.exists():
        raise RuntimeError(f"{phone.name} needs the snippet APK to switch Bluetooth, "
                           f"build it first: gradlew :snippet:assembleDebug")
    print(f"Installing the snippet APK on {phone.name}, to switch its Bluetooth")
    app.install(phone.adb, app.SNIPPET_APK)


def prepare(phone, brightness):
    install_bluetooth_switch(phone)
    device_state.set_bluetooth(phone.adb, False)
    device_state.put_setting(phone.adb, *STAY_ON, device_state.STAY_ON_ALL_CHARGERS)
    if brightness is not None:
        device_state.put_setting(phone.adb, *BRIGHTNESS_MODE, MANUAL_BRIGHTNESS)
        device_state.put_setting(phone.adb, *BRIGHTNESS, brightness)
    device_state.wake(phone.adb)


def restore(state):
    """Restores every phone in the state file. Phones that fail stay in it."""
    for serial, saved in list(state.phones.items()):
        adb = AdbProxy(serial)
        try:
            for namespace, key in reversed(SETTINGS):
                name = f"{namespace}/{key}"
                if name in saved.get("settings", {}):
                    device_state.put_setting(adb, namespace, key, saved["settings"][name])
            if "bluetooth" in saved:
                device_state.set_bluetooth(adb, saved["bluetooth"])
        except DEVICE_ERRORS as e:
            print(f"Couldn't restore {saved['name']}: {e}")
            continue
        print(f"Restored {saved['name']}")
        del state.phones[serial]
        state.save()
    if state.phones:
        print(f"Not everything was restored. Run `restore` once the phones are back, "
              f"the settings are kept in {state.path}")
        return False
    return True


def check(phones):
    """The problems the phones have right now."""
    problems = []
    bluetooth_on = []
    for phone in phones:
        try:
            if not device_state.is_powered(phone.adb):
                problems.append(f"{phone.name} isn't plugged in")
            if not device_state.is_awake(phone.adb):
                problems.append(f"{phone.name} has its screen off")
            if not device_state.is_unlocked(phone.adb):
                problems.append(f"{phone.name} is locked")
            if device_state.is_bluetooth_on(phone.adb):
                bluetooth_on.append(phone.name)
        except DEVICE_ERRORS:
            problems.append(f"{phone.name} isn't reachable over adb")
    if len(bluetooth_on) > 1:
        problems.append(f"Bluetooth is on on {' and '.join(bluetooth_on)}")
    return problems


def wait_until_ready(phones):
    shown = None
    while True:
        problems = check(phones)
        if not problems:
            return
        if problems != shown:
            print("Waiting for the phones to be ready, unlock any that are locked:")
            for problem in problems:
                print(f"  {problem}")
            shown = problems
        time.sleep(1)


class Watcher(threading.Thread):
    """Checks the phones in the background and warns when a problem starts or ends."""

    def __init__(self, phones, interval):
        super().__init__(daemon=True)
        self.phones = phones
        self.interval = interval
        self.stop = threading.Event()
        self.active = {}
        self.history = []

    def run(self):
        while not self.stop.wait(self.interval):
            problems = set(check(self.phones))
            now = timestamp()
            for problem in sorted(problems - self.active.keys()):
                print(f"\a{now} WARNING: {problem}")
                self.active[problem] = now
            for problem in sorted(self.active.keys() - problems):
                print(f"{now} Fixed: {problem}")
                self.history.append((self.active.pop(problem), now, problem))

    def summary(self):
        history = self.history + [(start, "end", problem) for problem, start in self.active.items()]
        if not history:
            return "No problems during the session."
        lines = [f"  {start} to {end}: {problem}" for start, end, problem in sorted(history)]
        return "Problems during the session:\n" + "\n".join(lines)


def run_session(state, brightness, interval):
    serials = android_device.list_adb_devices()
    if not serials:
        sys.exit("No phones connected over adb. Nothing was changed.")
    if state.phones:
        print(f"A previous session wasn't restored, keeping its original settings from {state.path}")

    phones = [Phone(s, device_state.model(AdbProxy(s))) for s in serials]
    settings = [STAY_ON] + ([BRIGHTNESS_MODE, BRIGHTNESS] if brightness is not None else [])
    watcher = Watcher(phones, interval)
    try:
        for phone in phones:
            save_originals(phone, state, settings)
            prepare(phone, brightness)
            print(f"Prepared {phone.name}")
        wait_until_ready(phones)
        watcher.start()
        print(f"{timestamp()} Ready, checking the phones every {interval:g} s. "
              f"Press Enter to end the session and restore the phones.")
        input()
    except (KeyboardInterrupt, EOFError):
        print()
    except DEVICE_ERRORS as e:
        print(f"Failed: {e}")
    finally:
        watcher.stop.set()
        if watcher.is_alive():
            watcher.join()
            print(watcher.summary())
        print("Restoring the phones")
        ok = restore(state)
    return ok


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("action", nargs="?", choices=["start", "restore"], default="start",
                    help="start a session (default), or restore a session that didn't end normally")
    ap.add_argument("--brightness", type=int, default=30, help="screen brightness, 0 to 255 (default 30)")
    ap.add_argument("--no-dim", action="store_true", help="leave the screen brightness alone")
    ap.add_argument("--interval", type=float, default=10.0, help="seconds between checks (default 10)")
    ap.add_argument("--state-file", type=pathlib.Path, default=DEFAULT_STATE_FILE,
                    help=f"where the original settings are kept (default {DEFAULT_STATE_FILE.name})")
    args = ap.parse_args()

    state = StateFile(args.state_file)
    if args.action == "restore":
        if not state.phones:
            print("Nothing to restore")
            return
        ok = restore(state)
    else:
        brightness = None if args.no_dim else args.brightness
        ok = run_session(state, brightness, args.interval)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
