"""The app under test on one phone: its build, process, data and permission.

Every function takes a Mobly AdbProxy, like device_state. Killing the app
like the system does uses run-as, or su where run-as can't see the process,
which also ends a process the companion service keeps bound, unlike `am
kill`. It doesn't put the package in the stopped state, unlike `am
force-stop`.
"""

import pathlib
import re
import subprocess
import time

from lib import device_state

PACKAGE = "com.tt.autobluetoothcodec"
SNIPPET_PACKAGE = PACKAGE + ".snippet"
TEST_PACKAGE = PACKAGE + ".test"
PERMISSION = "android.permission.BLUETOOTH_CONNECT"
# The device protected data directory, which holds the preferences; run-as starts in the user protected one
DE_DATA_DIR = f"/data/user_de/0/{PACKAGE}"

ROOT = pathlib.Path(__file__).resolve().parents[2]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SNIPPET_APK = ROOT / "snippet/build/outputs/apk/debug/snippet-debug.apk"


def expected_version():
    """The version name a debug build of the working tree has, see app/build.gradle.kts."""
    description = subprocess.run(["git", "describe", "--always", "--dirty"], cwd=ROOT,
                                 capture_output=True, text=True, check=True).stdout.strip()
    return "1.0-" + description


def installed_version(adb, package=PACKAGE):
    out = device_state.shell(adb, f"dumpsys package {package} | grep -m1 versionName= || true")
    match = re.search(r"versionName=(\S+)", out)
    return match.group(1) if match else None


def is_installed(adb, package=PACKAGE):
    return device_state.shell(adb, f"pm path {package} || true").startswith("package:")


INSTALL_TIMEOUT = 180


def install(adb, apk=APK):
    """Installs or updates [apk], keeping the app's data, its links and its permission.

    Raises AdbTimeoutError when a dialog on the phone holds the install, like
    Play Protect's offer to scan an unknown app, instead of waiting forever.
    """
    adb.install(["-r", "-t", str(apk)], timeout=INSTALL_TIMEOUT)


def uninstall(adb):
    """Removes the app, its data, its links and its appops, so the next install is a fresh one."""
    device_state.shell(adb, f"pm uninstall {PACKAGE}", timeout=60)


def is_stopped(adb):
    """Whether the package is in the stopped state, in which manifest receivers get nothing."""
    out = device_state.shell(adb, f"dumpsys package {PACKAGE} | grep -m1 -o 'stopped=[a-z]*' || true")
    return out.strip() == "stopped=true"


def unstop(adb):
    """Takes the package out of the stopped state without writing to the log.

    A broadcast to TestLabelReceiver without a label starts the process and
    returns before the receiver touches the log.
    """
    device_state.shell(adb, f"am broadcast -f 0x20 -n {PACKAGE}/.TestLabelReceiver")


def pid(adb):
    out = device_state.shell(adb, f"pidof {PACKAGE} || true")
    return int(out.split()[0]) if out.strip() else None


def is_running(adb):
    return pid(adb) is not None


class CantKill(RuntimeError):
    """The phone can't end the app's process like the system does: no root, and run-as isn't allowed to."""


def kill(adb, timeout=10.0, once_cached=True):
    """Ends the app's process like the system does, without stopping the package.

    run-as works without root on the S24. On the API 32 Pixel and the A50's
    API 30, SELinux doesn't let it signal the app's process, which toybox's
    kill reports as "unknown pid", so it needs root there. Without root the
    process is ended with `am kill` once it's cached, like the system ends idle
    ones, unless [once_cached] is false; if it doesn't get cached in time, or
    [once_cached] is false, this raises CantKill.
    """
    end = time.time() + timeout
    while (process := pid(adb)) is not None:
        out = device_state.shell(adb, f"run-as {PACKAGE} kill -9 {process} 2>&1 || true")
        if "unknown pid" in out or "denied" in out.lower():
            if device_state.has_su(adb):
                device_state.shell(adb, f"su -c 'kill -9 {process}' || true")
            elif not once_cached:
                raise CantKill(f"can't end the busy app's process without root, run-as said {out.strip()!r}")
            else:
                try:
                    kill_cached(adb, timeout=max(1.0, end - time.time()))
                except TimeoutError as e:
                    raise CantKill(f"can't end the app's process without root, run-as said "
                                   f"{out.strip()!r}, and {e}") from None
                return
        if time.time() >= end:
            raise TimeoutError(f"the app's process {process} is still running")
        time.sleep(0.2)


def kill_cached(adb, timeout=10.0):
    """Ends the app's process once the system counts it cached, without root or run-as.

    For a locked user, where run-as may fail: it changes into the app's
    credential protected directory. `am kill` only ends a process the system
    considers safe to kill: right after a receiver finished it may not be
    cached yet, so this retries; a bound service or a running receiver keeps it
    alive, which times out. It doesn't stop the package.
    """
    end = time.time() + timeout
    while (process := pid(adb)) is not None:
        device_state.shell(adb, f"am kill {PACKAGE}")
        if time.time() >= end:
            raise TimeoutError(f"am kill didn't end the app's process {process}, is it bound or running a receiver?")
        time.sleep(0.5)


def force_stop(adb):
    device_state.shell(adb, f"am force-stop {PACKAGE}")


def clear_data(adb):
    """Deletes the app's data and ends its process.

    It also revokes the permission and clears its flags, and doesn't stop the
    package. On API 28 and 32 it keeps the link and observing, on API 36 it
    removes the link, shortly after pm clear returns.
    """
    out = device_state.shell(adb, f"pm clear {PACKAGE}")
    if "Success" not in out:
        raise RuntimeError(f"pm clear failed: {out}")


AUTO_REVOKE_OP = "AUTO_REVOKE_PERMISSIONS_IF_UNUSED"


def parse_auto_revoke_modes(output):
    """(uid mode, package mode) of AUTO_REVOKE_PERMISSIONS_IF_UNUSED, from `appops get PACKAGE OP`.

    The uid mode is the user's choice in the app's settings, None if there is
    none; the system's switch "Remove permissions and free up space" writes
    allow. The package mode is "default" unless set; a link sets it to ignore.
    None where the op doesn't exist (API 28).
    """
    if "Unknown operation" in output:
        return None
    uid = re.search(rf"^Uid mode: {AUTO_REVOKE_OP}: (\w+)", output, re.MULTILINE)
    package = re.search(rf"^{AUTO_REVOKE_OP}: (\w+)", output, re.MULTILINE)
    return uid.group(1) if uid else None, package.group(1) if package else "default"


def parse_auto_revoke_mode(output):
    """The mode of AUTO_REVOKE_PERMISSIONS_IF_UNUSED that applies, from `appops get PACKAGE OP`.

    A uid mode wins over the package's mode. "default" when neither is set,
    None where the op doesn't exist (API 28).
    """
    modes = parse_auto_revoke_modes(output)
    if modes is None:
        return None
    uid, package = modes
    return uid if uid is not None else package


def auto_revoke_mode(adb):
    """"ignore" while the app is exempt from losing its permissions when unused, see parse_auto_revoke_mode."""
    return parse_auto_revoke_mode(device_state.shell(adb, f"appops get {PACKAGE} {AUTO_REVOKE_OP}"))


def auto_revoke_modes(adb):
    """(uid mode, package mode), see parse_auto_revoke_modes."""
    return parse_auto_revoke_modes(device_state.shell(adb, f"appops get {PACKAGE} {AUTO_REVOKE_OP}"))


def set_auto_revoke_uid_mode(adb, mode):
    """Sets the uid mode like the user's switch in the app's settings: allow when it's turned on, default
    removes the choice."""
    device_state.shell(adb, f"appops set --uid {PACKAGE} {AUTO_REVOKE_OP} {mode}")


def set_auto_revoke_package_mode(adb, mode):
    """Sets the package mode, which a link sets to ignore."""
    device_state.shell(adb, f"appops set {PACKAGE} {AUTO_REVOKE_OP} {mode}")


def reset_auto_revoke(adb):
    """Removes the exemption and the user's choice, as on a fresh install that was never linked."""
    device_state.shell(adb, f"appops set --uid {PACKAGE} {AUTO_REVOKE_OP} default")
    device_state.shell(adb, f"appops set {PACKAGE} {AUTO_REVOKE_OP} default")


def data_files(adb):
    """The files the app stores in its user protected and device protected data directories."""
    out = device_state.shell(
        adb, f"run-as {PACKAGE} sh -c 'find files shared_prefs {DE_DATA_DIR}/files {DE_DATA_DIR}/shared_prefs"
             f" -type f 2>/dev/null' || true")
    return [line for line in out.splitlines() if line]


sdk = device_state.sdk


def permission_state(adb):
    """(granted, flags) of the Nearby devices permission, None before API 31 where it doesn't exist."""
    if sdk(adb) < 31:
        return None
    out = device_state.shell(adb, f"dumpsys package {PACKAGE} | grep -m1 '{PERMISSION}: granted=' || true")
    match = re.search(r"granted=(true|false), flags=\[\s*([^\]]*)\]", out)
    if not match:
        return False, set()
    return match.group(1) == "true", {f for f in match.group(2).split("|") if f.strip()}


def grant_permission(adb):
    if sdk(adb) >= 31:
        device_state.shell(adb, f"pm grant {PACKAGE} {PERMISSION}")


# The system writes runtime permissions here about 10.6 s after the last change, on API 32. A reboot
# before that, even through the framework, loses the change.
RUNTIME_PERMISSIONS_FILE = "/data/misc_de/0/apexdata/com.android.permission/runtime-permissions.xml"
PERMISSION_WRITE_DELAY = 15.0


def wait_permission_persisted(adb, timeout=30.0):
    """Waits until the permission's current state is written, so it survives a reboot.

    With root this reads the file the system writes, without it waits longer
    than the system's write delay.
    """
    state = permission_state(adb)
    if state is None:
        return
    if not device_state.has_su(adb):
        time.sleep(PERMISSION_WRITE_DELAY)
        return
    expected = "true" if state[0] else "false"
    end = time.time() + timeout
    while True:
        out = device_state.shell(adb, f"su -c \"grep -A40 'name=\\\"{PACKAGE}\\\"' {RUNTIME_PERMISSIONS_FILE}\" || true")
        match = re.search(rf'name="{re.escape(PERMISSION)}" granted="(true|false)"', out.split("</package>")[0])
        if match and match.group(1) == expected:
            return
        if time.time() >= end:
            raise TimeoutError(f"the permission's state granted={expected} wasn't written within {timeout:.0f} s")
        time.sleep(0.5)


def revoke_permission(adb):
    """Revokes the permission, which kills the app's process."""
    if sdk(adb) >= 31:
        device_state.shell(adb, f"pm revoke {PACKAGE} {PERMISSION}")


def launch(adb, activity=".MainActivity"):
    device_state.shell(adb, f"am start -W -n {PACKAGE}/{activity}", timeout=30)


def go_home(adb):
    device_state.shell(adb, "input keyevent KEYCODE_HOME")


def resolve_activity(adb, action):
    """The package of the activity that handles [action], or None if there is none."""
    out = device_state.shell(adb, f"cmd package resolve-activity --brief -a {action} || true")
    return parse_resolved_package(out)


def parse_resolved_package(output):
    """The package from the output of cmd package resolve-activity --brief: its last line is the component."""
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines or "/" not in lines[-1]:
        return None
    return lines[-1].split("/", 1)[0]


def run_instrumentation(adb, runner=f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner", timeout=900):
    """Runs the app's instrumented tests, the shared UI tests, and returns the raw output.

    Not through Gradle's connectedDebugAndroidTest, which would run them on all
    phones at once and uninstall the app afterwards, dropping its links.
    """
    return device_state.shell(adb, f"am instrument -w -r {runner}", timeout=timeout)


def parse_instrumentation(output):
    """(tests run, failures) from `am instrument -r`, and the names of the failed tests."""
    failed = []
    current = {}
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            current["class"] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            current["test"] = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code in (-1, -2):
                failed.append(f"{current.get('class')}#{current.get('test')}")
    ran = re.search(r"Tests run: (\d+),\s+Failures: (\d+)", output)
    ok = re.search(r"^OK \((\d+) tests?\)", output, re.MULTILINE)
    if ok:
        return int(ok.group(1)), 0, failed
    if ran:
        return int(ran.group(1)), int(ran.group(2)), failed
    return 0, len(failed) or 1, failed or ["the instrumentation didn't report results"]
