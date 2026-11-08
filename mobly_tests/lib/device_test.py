"""The base class of the device tests: one phone, one headset, artifacts on failure.

run_suite.py writes a Mobly config per phone with exactly one AndroidDevice
and one Headset, see testbed.yaml.
"""

import pathlib
import sys
import time

from mobly import asserts
from mobly import base_test
from mobly import signals
from mobly.controllers import android_device

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import headset as headset_controller
from lib import lock_screen


class NotApplicable(signals.TestSkip):
    """Raised by require(): the phone or headset can't do what the test needs.

    Mobly records it as a SKIP like any test it didn't run. The suite calls a
    test not applicable only for this signal, and any other test that didn't
    run aborted, see is_not_applicable().
    """


def is_not_applicable(record):
    """Whether a record Mobly counts as SKIP is a test that doesn't apply, not an aborted one.

    Aborts end up there too: Mobly records the tests after a class abort, or
    after an error in setup_class, with the abort's signal.
    """
    return record.termination_signal_type == NotApplicable.__name__


def recorded(ranges, sdk):
    """The outcome a characterization test recorded for API level [sdk] (6.7), or None if there is none.

    [ranges] maps (first, last) API levels, both included, to the outcome. A last of None covers every
    later level: a newer Android is expected to behave like the latest one recorded.
    """
    for (first, last), outcome in ranges.items():
        if first <= sdk and (last is None or sdk <= last):
            return outcome
    return None


def is_failing():
    """Whether the test is failing, asked from teardown_test.

    Mobly calls the teardown in a finally block, so the exception the setup or
    the test raised is still the one being handled. Passes and not applicable
    tests are exceptions too.
    """
    error = sys.exc_info()[1]
    return error is not None and not isinstance(error, (signals.TestPass, NotApplicable))


class DeviceTest(base_test.BaseTestClass):
    def setup_class(self):
        self.ad = self.register_controller(android_device)[0]
        self.headset = self.register_controller(headset_controller)[0]
        self.adb = self.ad.adb
        self.log = app_log.AppLog(self.adb)
        self.sdk = app.sdk(self.adb)
        # Declared per phone in testbed.yaml, run_suite.py passes on only the class (D24, D25).
        # Without one a phone has a lock screen with an unknown password
        self.lock_class = getattr(self.ad, "lock_class", lock_screen.UNKNOWN_PASSWORD)
        # Where a known password is read from when it's typed
        self.testbed = self.user_params.get("testbed")
        self.ad.log.info("API %d, headset %s (%s)", self.sdk, self.headset.name, self.headset.backend)

    def setup_test(self):
        self._logcat_since = device_state.shell(self.adb, "date +'%m-%d %H:%M:%S.000'")
        self._artifacts = None
        # What the log may have at the end of the test, see check_invariants()
        self.allowed_open = 0
        self.allowed_interrupted = 0

    def require(self, condition, reason):
        """Ends the test as not applicable unless the phone or headset can do what it needs."""
        if not condition:
            raise NotApplicable(reason)

    def require_capability(self, capability):
        self.require(capability in self.headset.capabilities,
                     f"the {self.headset.backend} headset has no {capability}")

    def on_fail(self, record):
        if getattr(self, "_artifacts", None) is None:
            self.collect_artifacts()

    def on_skip(self, record):
        # Mobly's own lines for it say SKIP, run_suite.py hides them on the console
        if is_not_applicable(record):
            self.ad.log.info("%s is not applicable: %s", record.test_name, record.details)
        else:
            self.ad.log.warning("%s was aborted: %s", record.test_name, record.details)

    def check_invariants(self, settle=10.0):
        """The log's problems at the end of a test, 6.5: its structure, open and interrupted
        operations, and crashes of the app. Waits up to [settle] s for operations to resolve."""
        end = time.time() + settle
        while True:
            events = self.log.read()
            problems = app_log.check_structure(events, allow_open=self.allowed_open)
            if not problems or time.time() >= end:
                break
            time.sleep(1)
        interrupted = [e["seq"] for e in events if e["type"] == "OPERATION_INTERRUPTED"]
        if len(interrupted) > self.allowed_interrupted:
            problems.append(f"operations interrupted at seq {interrupted}")
        crashes = device_state.shell(self.adb, f"logcat -d -b crash -T '{self._logcat_since}'", timeout=30)
        if app.PACKAGE in crashes:
            problems.append("the app crashed, see the crash buffer in logcat.txt")
        return problems

    def collect_artifacts(self):
        """Saves what helps to understand a failure: log, Logcat, dumps, screen, headset."""
        directory = pathlib.Path(self.current_test_info.output_path) / "artifacts"
        self._artifacts = directory
        directory.mkdir(parents=True, exist_ok=True)
        steps = {
            "event_log.jsonl": lambda: device_state.shell(
                self.adb, f"run-as {app.PACKAGE} cat {app_log.LOG_FILE} 2>/dev/null || true", timeout=30),
            "logcat.txt": lambda: device_state.shell(
                self.adb, f"logcat -d -v threadtime -T '{self._logcat_since}'", timeout=60),
            "bluetooth_manager.txt": lambda: device_state.shell(self.adb, bt_oracle.DUMP_COMMAND, timeout=30),
            "companiondevice.txt": lambda: device_state.shell(self.adb, "dumpsys companiondevice", timeout=30),
            "ui.xml": lambda: device_state.shell(
                self.adb, "uiautomator dump /data/local/tmp/ui.xml >/dev/null && cat /data/local/tmp/ui.xml", timeout=30),
            "headset.txt": lambda: f"{self.headset.info()}: {self.headset.status()}",
        }
        for name, step in steps.items():
            try:
                (directory / name).write_text(step(), encoding="utf-8")
            except Exception as e:  # An artifact that can't be collected mustn't hide the failure
                (directory / name).write_text(f"couldn't collect: {e!r}", encoding="utf-8")
        try:
            self.ad.take_screenshot(str(directory))
        except Exception as e:
            (directory / "screenshot.txt").write_text(f"couldn't collect: {e!r}", encoding="utf-8")


class BaselineTest(DeviceTest):
    """A device test class whose tests start from the baseline and restore it, TestConcept.md 6.2.

    A difference found before a test means the previous test's restore missed
    something. It's repaired and reported as a leak of that test; if the
    repair fails, the rest of the class is aborted instead of failing every
    test for the same reason (D12).
    """

    def setup_class(self):
        self.leaks = []
        self._previous_test = None
        super().setup_class()
        installed, expected = app.installed_version(self.adb), app.expected_version()
        if installed != expected:
            asserts.abort_class(f"installed build {installed} isn't the working tree's {expected}")
        self.baseline = baseline.Baseline(self.adb, self.headset, self.sdk)
        self.baseline.repair()
        codec = self.baseline.record_own_codec()
        self.ad.log.info("the phone's own codec is %s", codec)
        # Also checks that the reconnect didn't make the app log anything, there is no preferred codec
        problems = self.baseline.check()
        if problems:
            asserts.abort_class(f"the baseline can't be established: {problems}")

    def setup_test(self):
        super().setup_test()
        try:
            problems = self.baseline.check()
            if problems:
                self.leaks.append((self._previous_test, problems))
                self.ad.log.warning("the baseline was left broken by %s: %s", self._previous_test, problems)
                problems = self.baseline.restore()
        except Exception as e:
            # Like a part that can't be restored, for example a headset that doesn't answer
            problems = [repr(e)]
        if problems:
            asserts.abort_class(f"the baseline can't be restored: {problems}")

    def teardown_test(self):
        self._previous_test = self.current_test_info.name
        # Mobly only calls on_fail after the teardown, when the restore has reset the app
        failing = is_failing()
        if failing:
            self.collect_artifacts()
        invariants = self.check_invariants()
        if invariants and not failing:
            self.collect_artifacts()
        # The next test's setup checks the result and reports what the repair missed as a leak
        try:
            self.reset_phone()
            self.baseline.repair()
        except signals.TestAbortSignal:
            raise
        except Exception:
            self.ad.log.exception("restoring the baseline after %s failed", self._previous_test)
        if invariants:
            raise signals.TestFailure(f"the log's invariants are broken: {invariants}")

    def reset_phone(self):
        """Undoes what a test changed outside the baseline, after the artifacts are collected and before
        the repair. Nothing by default; the reboot tests unlock the phone and clear the PIN (D26)."""

    def teardown_class(self):
        if self.leaks:
            path = pathlib.Path(self.log_path) / f"{self.TAG}-leaks.txt"
            path.write_text("".join(f"{test}: {problems}\n" for test, problems in self.leaks), encoding="utf-8")
            self.ad.log.warning("%d baseline leaks, see %s", len(self.leaks), path)
