"""The SIM cards of a test phone: whether they're unlocked, and rebooting without losing their PIN.

A SIM with a PIN asks for it after every boot, on the lock screen before
the phone's own PIN or password. Whatever is typed while it asks counts as
a wrong SIM PIN, and the third one locks the SIM until its PUK is entered.
So the lock screen password is only typed once every SIM is loaded or absent.

Every reboot goes through unattended_reboot(): it stores the SIM PINs the
user entered, and after the reboot the phone enters them itself, before the
first unlock. The reboot has to follow within 20 s, the stored PINs stay
valid for 15 minutes. The phone's own unlock doesn't depend on it.

Checked on Android 9 and 12L (Pixel 3a, no SIM) and 16 (Galaxy S24, a SIM
with a PIN and an inactive eSIM).
"""

from lib import app
from lib import device_state
from lib import lock_screen

# One state per slot, comma separated, like "LOADED,ABSENT". Empty on the API 28 Pixel, whose radio doesn't run
SIM_STATE_PROPERTY = "gsm.sim.state"
LOADED = "LOADED"
ABSENT = "ABSENT"

# TelephonyManager.prepareForUnattendedReboot, the A50's API 30 has no such method
UNATTENDED_REBOOT_SDK = 31
HELPER_CLASS = "com.tt.autobluetoothcodec.snippet.UnattendedReboot"
# TelephonyManager.PREPARE_UNATTENDED_REBOOT_*
PREPARE_RESULTS = {0: "success", 1: "a SIM's PIN isn't stored, enter it once by hand", 2: "error"}


def parse_sim_states(value):
    """The state of each SIM slot in the value of gsm.sim.state, none if it's empty."""
    value = value.strip()
    return [state.strip() for state in value.split(",")] if value else []


def sim_states(adb):
    return parse_sim_states(device_state.shell(adb, f"getprop {SIM_STATE_PROPERTY}"))


def all_sims_loaded_or_absent(adb):
    """Whether every SIM slot is LOADED or ABSENT, so the lock screen doesn't ask for a SIM PIN.

    Any other state isn't safe: PIN_REQUIRED and PUK_REQUIRED ask for one,
    and READY or NOT_READY may still change to it.
    """
    return all(state in (LOADED, ABSENT) for state in sim_states(adb))


def parse_prepare_result(output):
    """The result code the helper printed."""
    lines = output.strip().splitlines()
    try:
        return int(lines[-1])
    except (IndexError, ValueError):
        raise RuntimeError(f"no result from {HELPER_CLASS}: {output!r}") from None


def snippet_apk_path(adb):
    """The installed snippet APK on the phone, which the shell can read."""
    out = device_state.shell(adb, f"pm path {app.SNIPPET_PACKAGE}")
    paths = [line[len("package:"):] for line in out.splitlines() if line.startswith("package:")]
    if not paths:
        raise RuntimeError(f"{app.SNIPPET_PACKAGE} isn't installed: {out!r}")
    return next((p for p in paths if p.endswith("/base.apk")), paths[0])


def unattended_reboot(ad):
    """Prepares the SIMs for an unattended reboot and reboots the phone [ad] with Mobly.

    If the user is locked a plain reboot is performed without preparation. Any previous
    preparation remains valid until the user is unlocked.

    Doesn't reboot if the SIMs couldn't be prepared: the lock screen would ask
    for a SIM PIN the suite doesn't know. Below API 31, which has no unattended
    reboot, only a phone without a SIM is rebooted. Doesn't wait for the SIMs.
    """
    adb = ad.adb
    if lock_screen.user_state(adb) == lock_screen.RUNNING_LOCKED:
        ad.reboot()
        return

    if app.sdk(adb) < UNATTENDED_REBOOT_SDK:
        states = sim_states(adb)
        if any(state != ABSENT for state in states):
            raise RuntimeError(f"API {app.sdk(adb)} has no unattended reboot, and the phone has a SIM: {states}")
    else:
        output = device_state.shell(adb, f"CLASSPATH={snippet_apk_path(adb)} app_process / {HELPER_CLASS}")
        result = parse_prepare_result(output)
        if result != 0:
            raise RuntimeError(f"preparing the unattended reboot failed with {result}, "
                               f"{PREPARE_RESULTS.get(result, 'unknown')}")
    ad.reboot()
