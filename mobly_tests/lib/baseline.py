"""The state every device test starts from and leaves behind, TestConcept.md 6.2.

A user who has set the app up and listens with the headset, but hasn't saved
a preferred codec: log empty, settings at their defaults, package not
stopped, permission granted without flags, headset linked and not observed,
exempt from what the system does to unused apps and not hibernated, the
unused app job's threshold untouched (D28), phone Bluetooth on, A2DP connected, headset normal, the codec the phone picks
on its own (D11), screen on and unlocked with the launcher in front.

check() reads every part, repair() puts back what differs, and restore() does
both. A check takes about 1.5 s, so a teardown only repairs: the next test's
setup checks anyway.
Whether the app process runs isn't part of the baseline; restoring ends it,
so no state survives in memory.
"""

import html
import json
import re
import time

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib import device_state
from lib import hibernation

# Flags a user's choice leaves on the permission, the reset clears them
USER_FLAGS = {"USER_SET", "USER_FIXED"}
CONNECT_TIMEOUT = 40.0
# After a reconnect the Qudelix pages the phone it served last for up to a minute, refusing others
SETTINGS_TIMEOUT = 90.0
# pm clear removes the link on API 36 within about 0.1 s, measured on the S24
UNLINK_TIMEOUT = 5.0
# Printed by read_preferences() once the app's data directory is open
READABLE = "readable"


def foreground_activity(adb):
    out = device_state.shell(adb, "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity' || true")
    match = re.search(r"\{\S+ u\d+ ([^ }]+)", out)
    return match.group(1) if match else None


def read_preferences(adb, name, device_protected=True):
    """The string values of one of the app's preferences files in device protected storage, or in
    credential protected storage if not [device_protected], by key, or None if its data directory can't
    be read. Credential protected storage can only be read once the user unlocked.

    The marker tells a missing file, which means no values, from a failed run-as or a wrong
    directory, which would otherwise look the same.
    """
    # run-as starts in the credential protected directory
    directory = app.DE_DATA_DIR if device_protected else "."
    out = device_state.shell(
        adb, f"run-as {app.PACKAGE} sh -c 'cd {directory} && echo {READABLE}"
             f" && cat shared_prefs/{name}.xml 2>/dev/null' || true")
    if not out.startswith(READABLE):
        return None
    return parse_preferences(out)


def parse_preferences(xml):
    """The string values of a SharedPreferences file, by key, unescaped."""
    return {key: html.unescape(value) for key, value in re.findall(r'<string name="([^"]+)">([^<]*)</string>', xml)}


def preferred_codecs(adb):
    """The app's preferred codecs from its preferences file, a Codec for each upper case address, or None
    if its data directory can't be read."""
    values = read_preferences(adb, "preferred_codecs")
    # Each value is the JSON of an AndroidCodecConfig
    return None if values is None else {
        address: bt_oracle.Codec.from_app(json.loads(value)) for address, value in values.items()}


def saved_device_info(adb):
    """What the app keeps of each saved device for the Saved devices list, by upper case address, or None
    if its data directory can't be read. Each is the JSON of SavedDeviceStore.Info: name, lastConnected
    in ms on the phone's clock, and capabilities, the masks the device offered.

    They're in credential protected storage, the app merges what it noted before the unlock once it
    notices the unlock."""
    values = read_preferences(adb, "saved_device_info", device_protected=False)
    return None if values is None else {address: json.loads(value) for address, value in values.items()}


def unused_app_problems(state):
    """How [state], a hibernation.UnusedAppState, differs from the baseline."""
    problems = []
    if state.uid_mode is not None:
        problems.append(f"the user's choice {state.uid_mode} for removing the permissions of the unused app")
    if state.package_mode != "ignore":
        problems.append(f"not exempt from removing the permissions of the unused app, its mode is {state.package_mode}")
    if state.marker is not None:
        problems.append("a test's isolation of the unused app job wasn't cleaned up")
    if state.threshold is not None and state.threshold < hibernation.SAFE_THRESHOLD_MS:
        problems.append(f"the unused app job's threshold is {state.threshold} ms")
    if state.hibernated:
        problems.append("hibernated")
    if state.globally_hibernated:
        problems.append("globally hibernated")
    return problems


def element_bounds(xml, text):
    """The center of the first element of a UiAutomator dump that shows [text], or None.

    Ignores the direction marks the S24's Bluetooth settings put around device names.
    """
    for node in re.findall(r"<node [^>]*>", xml):
        shown = re.search(r'text="([^"]*)"', node)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if shown and bounds and html.unescape(shown.group(1)).strip("\u200e\u200f") == text:
            x1, y1, x2, y2 = map(int, bounds.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def connect_from_settings(adb, headset, timeout=SETTINGS_TIMEOUT):
    """Taps the headset in the phone's Bluetooth settings until A2DP connects (D16)."""
    device_state.shell(adb, "am start -a android.settings.BLUETOOTH_SETTINGS")
    end = time.time() + timeout
    try:
        while True:
            xml = device_state.shell(
                adb, "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml", timeout=30)
            center = element_bounds(xml, headset.name)
            if center:
                device_state.shell(adb, f"input tap {center[0]} {center[1]}")
            try:
                bt_oracle.wait_connected(adb, headset.address, timeout=min(20.0, max(1.0, end - time.time())))
                return
            except TimeoutError:
                if time.time() >= end:
                    raise TimeoutError(f"{headset.name} didn't connect from the Bluetooth settings"
                                       + ("" if center else ", it isn't listed")) from None
    finally:
        app.go_home(adb)


def connect(adb, headset, timeout=CONNECT_TIMEOUT):
    """The connect fixture, 6.6: done once A2DP connects, else one reconnect of the headset, else
    a tap on it in the phone's Bluetooth settings.

    It waits a little first: after the phone's Bluetooth or the headset's A2DP
    comes back, A2DP connects on its own within seconds. A phone that the
    headset didn't serve last, like the S24 after a Pixel's run, needs the
    tap.
    """
    if not device_state.is_bluetooth_on(adb):
        device_state.set_bluetooth(adb, True)
    try:
        bt_oracle.wait_connected(adb, headset.address, timeout=15)
        return
    except TimeoutError:
        pass
    headset.reconnect(adb)
    try:
        bt_oracle.wait_connected(adb, headset.address, timeout=timeout)
        return
    except TimeoutError:
        pass
    connect_from_settings(adb, headset)


class Baseline:
    def __init__(self, adb, headset, sdk):
        self.adb = adb
        self.headset = headset
        self.sdk = sdk
        self.log = app_log.AppLog(adb)
        # The phone's own choice, recorded by record_own_codec()
        self.own_codec = None

    def check(self):
        """The parts that differ from the baseline, as readable problems."""
        adb, problems = self.adb, []
        events = self.log.read()
        if events:
            problems.append(f"log has {len(events)} events, the last {events[-1]['type']}")
        preferred = preferred_codecs(adb)
        if preferred is None:
            problems.append("preferred codecs unreadable")
        elif preferred:
            problems.append(f"preferred codecs for {', '.join(preferred)}")
        info = saved_device_info(adb)
        if info:
            problems.append(f"saved device info for {', '.join(info)}")
        if app.is_stopped(adb):
            problems.append("package is stopped")
        permission = app.permission_state(adb)
        if permission is not None:
            granted, flags = permission
            if not granted:
                problems.append("permission not granted")
            if flags & USER_FLAGS:
                problems.append(f"permission flags {sorted(flags & USER_FLAGS)}")
        if self.sdk >= 30:
            problems += unused_app_problems(hibernation.read_state(adb, self.sdk))
        if not companion.is_linked(adb, app.PACKAGE, self.headset.address):
            problems.append("headset not linked")
        elif companion.is_observing(adb, app.PACKAGE, self.headset.address):
            problems.append("headset observed")
        if not device_state.is_bluetooth_on(adb):
            problems.append(f"phone Bluetooth {device_state.bluetooth_state(adb)}")
        else:
            state = bt_oracle.read(adb)
            device = state.device(self.headset.address)
            if device is None or not device.connected or not state.is_active(self.headset.address):
                problems.append("A2DP not connected")
            elif self.own_codec is not None and not self.own_codec.same_config(device.codec):
                problems.append(f"codec {device.codec}, the phone's own is {self.own_codec}")
        if not self.headset.is_normal():
            problems.append(f"headset {self.headset.status()}")
        if not device_state.is_awake(adb) or not device_state.is_unlocked(adb):
            problems.append("screen off or locked")
        if (foreground_activity(adb) or "").startswith(app.PACKAGE + "/"):
            problems.append("the app is in front")
        return problems

    def restore(self):
        """Restores the baseline and returns what still differs, an empty list if nothing."""
        self.repair()
        return self.check()

    def repair(self):
        """Puts back the parts that differ, without checking the result.

        For a caller that checks the baseline next anyway, like the next test's setup.
        """
        adb = self.adb
        unused = hibernation.read_state(adb, self.sdk) if self.sdk >= 30 else None
        if unused is not None and unused.marker is not None:
            # A test's isolation of the unused app job didn't finish, every other app is still exempt
            hibernation.clean_up(adb)
        elif unused is not None and unused.threshold is not None and unused.threshold < hibernation.SAFE_THRESHOLD_MS:
            hibernation.set_threshold(adb, None)
        # Relinking first ends observing; any event it causes is deleted with the app data next.
        # API 31 and 32 don't show observing, but the app doesn't observe there.
        linked = companion.is_linked(adb, app.PACKAGE, self.headset.address)
        if not linked or companion.is_observing(adb, app.PACKAGE, self.headset.address):
            if linked:
                companion.unlink(adb, app.PACKAGE, self.headset.address)
            companion.link(adb, app.PACKAGE, self.headset.address)
        # Always, so nothing survives in the process's memory either
        app.clear_data(adb)
        # On API 36 clearing the data also removes the link, about 0.1 s after pm clear returns.
        # Checking right away can still see it, and then the link is missing for the test.
        if self.sdk >= 36:
            companion.wait_unlinked(adb, app.PACKAGE, self.headset.address, timeout=UNLINK_TIMEOUT)
        if not companion.is_linked(adb, app.PACKAGE, self.headset.address):
            companion.link(adb, app.PACKAGE, self.headset.address)
        app.grant_permission(adb)
        if unused is not None:
            self.repair_unused_app()
        if app.is_stopped(adb):
            app.unstop(adb)
        if not self.headset.is_normal():
            self.headset.restore_normal()
        connect(adb, self.headset)
        if self.own_codec is not None and not self.own_codec.same_config(bt_oracle.active_codec(adb, self.headset.address)):
            # Only a full reconnect brings back the phone's own choice
            self.headset.reconnect(adb)
            bt_oracle.wait_connected(adb, self.headset.address, timeout=CONNECT_TIMEOUT)
        if (foreground_activity(adb) or "").startswith(app.PACKAGE + "/"):
            app.go_home(adb)

    def repair_unused_app(self):
        """Exempts the app from what the system does to unused apps, as the link does, without the user's choice,
        and ends its hibernation. After the relink, which sets the package mode on its own where it relinked."""
        adb, state = self.adb, hibernation.read_state(self.adb, self.sdk)
        if state.uid_mode is not None:
            app.set_auto_revoke_uid_mode(adb, "default")
        if state.package_mode != "ignore":
            app.set_auto_revoke_package_mode(adb, "ignore")
        # Hibernation stops the package, ending it doesn't take the package out of the stopped state
        if state.hibernated or state.globally_hibernated:
            hibernation.set_hibernated(adb, False)

    def record_own_codec(self):
        """Records the codec the phone picks on a full reconnect while the app has no preferred codec."""
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.headset.address, timeout=CONNECT_TIMEOUT)
        self.own_codec = bt_oracle.active_codec(self.adb, self.headset.address)
        return self.own_codec
