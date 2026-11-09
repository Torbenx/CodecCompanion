"""What the system does to an unused app, and how the tests cause it (TestConcept.md D28).

Since API 30 the permission controller's job takes the runtime permissions of
an app that wasn't used for the threshold, 90 days, unless the app is exempt
by AUTO_REVOKE_PERMISSIONS_IF_UNUSED. Since API 31 it also hibernates the app:
the package is force-stopped, so its manifest receivers get nothing, and once
it's hibernated for every user also globally, which deletes its compiled
code. Opening the app ends both.

The threshold is a device_config value for the whole phone, so forcing the
job with a short threshold would hit every unused app. isolate() first
exempts every other package with a uid mode, the way the user's switch in an
app's settings does, and leaves a marker on the phone holding the original
threshold; clean_up() puts the threshold back and removes the uid mode of
every package. Only for pure test devices, since it also removes the uid
modes a user chose. A marker still on the phone means a cleanup didn't
finish, which the baseline repairs.

simulate() puts only the app into that state, for phones the job mustn't run
on. It can't set AUTO_REVOKED, which no shell command offers, and on API 32
the revoke keeps USER_SET; the app only looks at the permission request's
result, so neither matters to it.

Checked on API 30 (Galaxy A50), 32 (Pixel 3a) and 37 (Galaxy S24).
"""

import dataclasses
import re
import time

from lib import app
from lib import device_state

OP = app.AUTO_REVOKE_OP
THRESHOLD_NAMESPACE = "permissions"
THRESHOLD_KEY = "auto_revoke_unused_threshold_millis2"
# The job counts an app unused once it wasn't used for the threshold. With 5 s and a 10 s wait it hit the app
# every time on API 32, and ran in about 1 s
TEST_THRESHOLD_MS = 5000
UNUSED_WAIT = 10.0
# A threshold this short can only be a test's leftover; the phones store 90 days or nothing
SAFE_THRESHOLD_MS = 24 * 3600 * 1000
# Written before isolate() changes anything, deleted once clean_up() is done
MARKER = "/data/local/tmp/abc_unused_apps"
JOB_TIMEOUT = 30.0
# Every package's uid mode, one appops call each: about 14 s for the 250 packages of the API 32 Pixel
LOOP_TIMEOUT = 180


@dataclasses.dataclass(frozen=True)
class UnusedAppState:
    """What the baseline checks about the app and the job, from one shell call, see read_state()."""
    uid_mode: str | None
    """The user's choice in the app's settings, None if there is none."""
    package_mode: str
    """"ignore" while a link exempts the app."""
    threshold: int | None
    """In ms, None while the phone stores none and the job uses its default of 90 days."""
    marker: str | None
    """The marker's content, the threshold to restore or "null"; None without a marker."""
    hibernated: bool | None
    """None before API 31."""
    globally_hibernated: bool | None


def state_command(sdk, package=app.PACKAGE):
    """The shell command whose output parse_state() reads, for API 30 and later."""
    command = (f"appops get {package} {OP}; echo '#threshold'; device_config get {THRESHOLD_NAMESPACE} {THRESHOLD_KEY};"
               f" echo '#marker'; cat {MARKER} 2>/dev/null || echo absent")
    if sdk >= 31:
        command += (f"; echo '#hibernation'; cmd app_hibernation get-state {package};"
                    f" cmd app_hibernation get-state --global {package}")
    return command


def parse_state(output):
    """Parses the output of state_command()."""
    sections = re.split(r"^#(\w+)\s*$", output, flags=re.MULTILINE)
    parts = {"appops": sections[0]}
    parts.update(zip(sections[1::2], sections[2::2]))
    uid_mode, package_mode = app.parse_auto_revoke_modes(parts["appops"])
    marker = parts["marker"].strip()
    hibernated = globally = None
    if "hibernation" in parts:
        hibernated, globally = (parse_hibernated(line) for line in parts["hibernation"].split())
    return UnusedAppState(uid_mode, package_mode, parse_threshold(parts["threshold"]),
                          None if marker == "absent" else marker, hibernated, globally)


def read_state(adb, sdk):
    return parse_state(device_state.shell(adb, state_command(sdk)))


def parse_hibernated(output):
    """`cmd app_hibernation get-state [--global] PACKAGE`."""
    value = output.strip()
    if value not in ("true", "false"):
        raise ValueError(f"not a hibernation state: {output!r}")
    return value == "true"


def is_hibernated(adb, package=app.PACKAGE, globally=False):
    flag = "--global " if globally else ""
    return parse_hibernated(device_state.shell(adb, f"cmd app_hibernation get-state {flag}{package}"))


def set_hibernated(adb, hibernating, package=app.PACKAGE):
    """Sets both the user's and the global hibernation; the user's force-stops the package."""
    value = "true" if hibernating else "false"
    device_state.shell(adb, f"cmd app_hibernation set-state {package} {value}")
    device_state.shell(adb, f"cmd app_hibernation set-state --global {package} {value}")


def parse_threshold(output):
    """`device_config get permissions auto_revoke_unused_threshold_millis2`: ms, None if not set."""
    value = output.strip()
    if value == "null":
        return None
    if not value.isdigit():
        raise ValueError(f"not a threshold: {output!r}")
    return int(value)


def threshold(adb):
    return parse_threshold(device_state.shell(adb, f"device_config get {THRESHOLD_NAMESPACE} {THRESHOLD_KEY}"))


def set_threshold(adb, value):
    """Sets the threshold in ms, or deletes it if [value] is None."""
    if value is None:
        device_state.shell(adb, f"device_config delete {THRESHOLD_NAMESPACE} {THRESHOLD_KEY}")
    else:
        device_state.shell(adb, f"device_config put {THRESHOLD_NAMESPACE} {THRESHOLD_KEY} {value}")


def parse_job(dump):
    """The permission controller's package and the id of its HibernationJobService job, from a line of
    `dumpsys jobscheduler`, or None."""
    match = re.search(r"JOB #\S+/(\d+): \S+ ([\w.]+)/[\w.$]*\.HibernationJobService\b", dump)
    return (match.group(2), int(match.group(1))) if match else None


def job(adb):
    out = device_state.shell(adb, "dumpsys jobscheduler 2>/dev/null | grep -m1 -E 'JOB #.*HibernationJobService' || true",
                             timeout=30)
    found = parse_job(out)
    if found is None:
        raise RuntimeError(f"no HibernationJobService job in dumpsys jobscheduler: {out!r}")
    return found


def parse_job_run(output):
    """Whether the job ran and finished, from the states run_job() polled after forcing it, one line each.

    `cmd jobscheduler get-job-state` prints words like "waiting", "active" or "ready".
    """
    states = [line.split() for line in output.splitlines() if line.strip() and "Running job" not in line]
    ran = any("active" in words for words in states)
    finished = bool(states) and "active" not in states[-1]
    return ran, finished


def run_job(adb, timeout=JOB_TIMEOUT):
    """Forces the job and polls its state on the phone until it was active and isn't any more.

    Returns whether it ran; polling from the host could miss a run of about 1 s.
    """
    package, job_id = job(adb)
    polls = int(timeout / 0.1)
    out = device_state.shell(
        adb, f"cmd jobscheduler run -u 0 -f {package} {job_id}; i=0; seen=; while [ $i -lt {polls} ]; do"
             f" s=$(cmd jobscheduler get-job-state {package} {job_id}); echo $s;"
             f" case \"$s\" in *active*) seen=1;; *) [ -n \"$seen\" ] && break;; esac; sleep 0.1; i=$((i+1)); done",
        timeout=timeout + 15)
    ran, finished = parse_job_run(out)
    if ran and not finished:
        raise TimeoutError(f"the job still ran after {timeout:.0f} s")
    return ran


def job_log(adb, since):
    """The job's log lines about the app since [since], as `logcat -T` takes it, for diagnosis only."""
    # The lists of all apps take several lines; only the app's own lines, not those of its snippet or tests
    package = app.PACKAGE.replace(".", "\\.")
    return device_state.shell(
        adb, f"logcat -d -T '{since}' -s HibernationPolicy HibernationController AutoRevokePermissions"
             f" | grep -E '{package}([^.]|$)' | grep -vE ', .*, .*, ' | cut -c1-300 || true",
        timeout=30)


def isolate(adb, keep):
    """Makes the job hit only the packages in [keep]: leaves the marker with the original threshold, exempts
    every other package by its uid mode and shortens the threshold."""
    original = threshold(adb)
    device_state.shell(adb, f"echo {'null' if original is None else original} > {MARKER}")
    skip = "|".join(keep)
    device_state.shell(adb, f"for p in $(pm list packages | cut -d: -f2); do case $p in {skip}) continue;; esac;"
                            f" appops set --uid $p {OP} ignore; done", timeout=LOOP_TIMEOUT)
    set_threshold(adb, TEST_THRESHOLD_MS)


def clean_up(adb):
    """Undoes isolate(): the threshold from the marker first, then every package's uid mode, then the
    marker. Also the uid mode a user chose, so only for pure test devices."""
    stored = device_state.shell(adb, f"cat {MARKER} 2>/dev/null || true").strip()
    set_threshold(adb, parse_threshold(stored) if stored else None)
    device_state.shell(adb, f"for p in $(pm list packages | cut -d: -f2); do appops set --uid $p {OP} default; done",
                       timeout=LOOP_TIMEOUT)
    device_state.shell(adb, f"rm -f {MARKER}")


def simulate(adb, package=app.PACKAGE):
    """Puts only [package] into the state the job leaves an app in that isn't exempt, see the module's doc."""
    device_state.shell(adb, f"pm revoke {package} {app.PERMISSION}")
    set_hibernated(adb, True, package)


def wait(seconds=UNUSED_WAIT):
    """Until an app that was just used counts as unused with TEST_THRESHOLD_MS."""
    time.sleep(seconds)
