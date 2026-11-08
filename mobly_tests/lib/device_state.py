"""Read and change the Bluetooth, screen and lock state of a test phone.

Every function takes a Mobly AdbProxy, so the session script can pass
AdbProxy(serial) and tests can pass an AndroidDevice's ad.adb.

Checked on Android 9 and 12 (Pixel 3a), 11 (Galaxy A50) and 16 and 17 (Galaxy S24).
"""

import re
import time

# Keeps the screen on while the phone is plugged in to AC, USB or a wireless charger
STAY_ON_ALL_CHARGERS = "7"

# The snippet's receiver that switches Bluetooth where the shell isn't allowed to, and up to which API it can
BLUETOOTH_SWITCH = "com.tt.autobluetoothcodec.snippet/.BluetoothSwitch"
BLUETOOTH_SWITCH_MAX_SDK = 30


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


def sdk(adb):
    return int(shell(adb, "getprop ro.build.version.sdk"))


def needs_bluetooth_switch(adb):
    """Whether set_bluetooth() switches Bluetooth with the snippet's BluetoothSwitch on this phone."""
    return not has_su(adb) and sdk(adb) <= BLUETOOTH_SWITCH_MAX_SDK


def switch_bluetooth_with_snippet(adb, on):
    """Asks the snippet's BluetoothSwitch to turn Bluetooth on or off, without waiting for it."""
    # The background queue held it for over 10 s once, behind the broadcasts of a Bluetooth change
    out = shell(adb, f"am broadcast --receiver-foreground -n {BLUETOOTH_SWITCH} --ez on {'true' if on else 'false'}",
                timeout=45)
    result = parse_broadcast_result(out)
    # BluetoothSwitch.RESULT_ACCEPTED; a broadcast nothing received completes with 0
    if result == 0:
        raise RuntimeError(f"nothing received {BLUETOOTH_SWITCH}, is the snippet APK "
                           f"({BLUETOOTH_SWITCH.split('/')[0]}) installed? {out!r}")
    if result != 1:
        raise RuntimeError(f"{BLUETOOTH_SWITCH} couldn't turn Bluetooth {'on' if on else 'off'}: {out!r}")


def parse_broadcast_result(output):
    """The result code am broadcast printed, or None if there's none."""
    match = re.search(r"^Broadcast completed: result=(-?\d+)", output, re.MULTILINE)
    return int(match.group(1)) if match else None


def set_bluetooth(adb, on, timeout=20.0):
    """Turns Bluetooth on or off and waits until the adapter has finished.

    On Android 9, and on the Galaxy A50's Android 11, the adb shell isn't
    allowed to. So this uses su where it's available, like on the rooted
    Pixels, and otherwise up to API 30 the snippet's BluetoothSwitch, which
    needs the snippet APK installed. svc's exit code doesn't tell whether it
    worked: on Android 12 it's 137 (killed) even when it did.
    """
    target = ("ON",) if on else ("OFF", "BLE_ON")
    command = f"svc bluetooth {'enable' if on else 'disable'}"
    if has_su(adb):
        shell(adb, f"su -c '{command}' || true")
    elif needs_bluetooth_switch(adb):
        switch_bluetooth_with_snippet(adb, on)
    else:
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
    out = shell(adb, "dumpsys power | grep -m1 -E '(mWakefulness|getWakefulnessLocked\\(\\))=' || true")
    wakefulness = parse_wakefulness(out)
    if wakefulness is None:
        raise RuntimeError(f"no wakefulness in dumpsys power: {out!r}")
    return wakefulness == "Awake"


def parse_wakefulness(dump):
    """The power manager's wakefulness, like Awake, Asleep or Dozing, from dumpsys power, or None.

    AOSP prints "mWakefulness=Awake", the Galaxy A50 on Android 11 "getWakefulnessLocked()=Awake".
    """
    match = re.search(r"^\s*(?:mWakefulness|getWakefulnessLocked\(\))=(\w+)", dump, re.MULTILINE)
    return match.group(1) if match else None


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


def clock(adb):
    """The phone's clock in whole seconds since the epoch. The phones' clocks differ, the API 28 Pixel
    is in 2019, so times the app stores are compared with this, never with the host's."""
    return int(shell(adb, "date +%s"))


def boot_time(adb):
    """When the phone booted, in seconds since the epoch on its own clock."""
    now = clock(adb)
    uptime = float(shell(adb, "cat /proc/uptime").split()[0])
    return now - uptime


def wake(adb):
    """Turns the screen on. It shows the lock screen if the phone is locked."""
    shell(adb, "input keyevent KEYCODE_WAKEUP")
