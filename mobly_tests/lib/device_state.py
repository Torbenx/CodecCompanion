"""Read and change the Bluetooth, screen and lock state of a test phone.

Every function takes a Mobly AdbProxy, so the session script can pass
AdbProxy(serial) and tests can pass an AndroidDevice's ad.adb.

Checked on Android 9 and 12 (Pixel 3a) and 16 and 17 (Galaxy S24).
"""

import re
import time

# Keeps the screen on while the phone is plugged in to AC, USB or a wireless charger
STAY_ON_ALL_CHARGERS = "7"


def shell(adb, command, timeout=15):
    return adb.shell(command, timeout=timeout).decode("utf-8", "replace").strip()


def model(adb):
    return shell(adb, "getprop ro.product.model")


def get_setting(adb, namespace, key):
    """The value of a setting, or None if it isn't set."""
    value = shell(adb, f"settings get {namespace} {key}")
    return None if value == "null" else value


def put_setting(adb, namespace, key, value):
    """Sets a setting, or deletes it if value is None."""
    if value is None:
        shell(adb, f"settings delete {namespace} {key}")
    else:
        shell(adb, f"settings put {namespace} {key} {value}")


def bluetooth_state(adb):
    """The adapter's state, like ON, OFF, TURNING_ON or BLE_ON.

    Unlike the bluetooth_on setting, which is written as soon as Bluetooth is
    asked to turn on or off, this is the adapter's actual state.
    """
    # The full dump is almost 2 MB on the S24
    out = shell(adb, "dumpsys bluetooth_manager | grep -m1 -i -E '^ *state:' || true")
    state = parse_bluetooth_state(out)
    if state is None:
        raise RuntimeError(f"no Bluetooth state in dumpsys bluetooth_manager: {out!r}")
    return state


def parse_bluetooth_state(dump):
    """The adapter's state from the status header of dumpsys bluetooth_manager, or None.

    Up to API 36 the line is "state: ON", since API 37 "State:         ON".
    """
    match = re.search(r"^\s*state:\s*(\S+)", dump, re.MULTILINE | re.IGNORECASE)
    return match.group(1) if match else None


def is_bluetooth_on(adb):
    # BLE_ON is the BLE only mode, classic Bluetooth and A2DP are off in it
    return bluetooth_state(adb) == "ON"


def has_su(adb):
    return shell(adb, "command -v su || true") != ""


def set_bluetooth(adb, on, timeout=20.0):
    """Turns Bluetooth on or off and waits until the adapter has finished.

    On Android 9 the adb shell isn't allowed to, so this uses su where it's
    available, like on the rooted Pixels. svc's exit code doesn't tell whether
    it worked: on Android 12 it's 137 (killed) even when it did.
    """
    target = ("ON",) if on else ("OFF", "BLE_ON")
    command = f"svc bluetooth {'enable' if on else 'disable'}"
    if has_su(adb):
        command = f"su -c '{command}'"
    shell(adb, f"{command} || true")
    end = time.time() + timeout
    while True:
        state = bluetooth_state(adb)
        if state in target:
            return
        if time.time() >= end:
            raise TimeoutError(f"Bluetooth still {state} after {timeout:.0f} s")
        time.sleep(0.5)


def is_awake(adb):
    """Whether the screen is on, not off, dozing or showing a screen saver."""
    out = shell(adb, "dumpsys power | grep -m1 mWakefulness=")
    return out.strip() == "mWakefulness=Awake"


def is_powered(adb):
    """Whether the phone is plugged in, which keeping the screen on needs."""
    out = shell(adb, "dumpsys power | grep -m1 mIsPowered=")
    return out.strip() == "mIsPowered=true"


def is_unlocked(adb):
    """Whether the current user's lock screen is dismissed, or there is none."""
    out = shell(adb, "dumpsys trust | grep '(current)' || true")
    match = re.search(r"deviceLocked=(\d)", out)
    if not match:
        raise RuntimeError(f"no lock state in dumpsys trust: {out!r}")
    return match.group(1) == "0"


def wake(adb):
    """Turns the screen on. It shows the lock screen if the phone is locked."""
    shell(adb, "input keyevent KEYCODE_WAKEUP")
