"""L4, the background and cold start tests: the preferred codec is applied without the UI or the snippet.

Each test saves a preferred codec through the snippet, unloads it, which ends
the app's process, puts the process in the state the test needs, and only
then causes the stimulus. No label is sent inside that window, since a label
broadcast would start the process; the log is read with run-as instead.
"""

import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import headset
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest
from lib.device_test import NotApplicable
from lib.device_test import recorded

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
AAC_44_16 = Codec(bt_oracle.AAC, 0x1, 0x1)
APPLY_TIMEOUT = 60.0
# A device seen through BLE scans took from seconds to over 90 s to appear, on API 32 before its
# companion service was disabled
APPEARANCE_TIMEOUT = 180.0

# The receiver notes a connect for the Saved devices list, then loads the log, which in a new process takes a
# moment, and logs its trigger
NOTED_BEFORE_TRIGGER_MS = 5000

# Characterization tests (D8), their results recorded per range of API levels on the testbed (6.7),
# see device_test.recorded().
# C3: whether the preferred codec is applied when A2DP connects after the process was killed while
# the device was present. On API 36 too since the manifest receiver is enabled there (D17).
C3_APPLIED = {(36, None): True}
# C9: whether the preferred codec is applied when the device connects after a force stop. Recorded
# on API 28, 32 and 36; 33 to 35 aren't.
C9_FORCE_STOPPED_APPLIED = {(28, 32): False, (36, None): True}


class ColdStartBase(BaselineTest):
    """The helpers of the L4 tests, also for the reboot tests."""

    def kill_app(self, once_cached=True):
        """Ends the app's process like the system does, see app.kill(). A phone that can't is not
        applicable to the test."""
        try:
            app.kill(self.adb, once_cached=once_cached)
        except app.CantKill as e:
            raise NotApplicable(str(e)) from None

    @property
    def address(self):
        return self.headset.address

    def preferred_codec(self):
        """A codec other than the phone's own choice (D11), so applying it shows."""
        own = self.baseline.own_codec
        return AAC_44_16 if own is not None and own.codec_type == bt_oracle.SBC else SBC_44_16

    def save_preferred(self, codec):
        """Saves [codec] as preferred through the snippet, which also observes the device since API 33."""
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        try:
            getattr(self.ad, SNIPPET).setPreferredCodec(self.address, codec.to_app())
            # The device may appear right after observing starts. The service only logs that, it
            # applies the codec when A2DP connects, so unloading the snippet interrupts nothing
            self.wait_until_resolved(timeout=15)
        finally:
            self.ad.unload_snippet(SNIPPET)
        self.kill_app()

    def wait_for_applied(self, after, codec, timeout=APPLY_TIMEOUT):
        """Waits until A2DP uses [codec] and an operation after seq [after] verified it, returns the operations.

        dumpsys decides: an operation may also have verified the codec before the
        stimulus, when the app started observing again.
        """
        end = time.time() + timeout
        while True:
            dumped = bt_oracle.active_codec(self.adb, self.address)
            ops = app_log.operations(self.log.events_after(after))
            if same_format(dumped, codec) and any(op.resolution == "CODEC_VERIFIED" for op in ops):
                return ops
            if time.time() >= end:
                asserts.fail(f"{codec} wasn't applied within {timeout:.0f} s, dumpsys shows {dumped}: {self.describe(ops)}")
            time.sleep(1)

    def check_one_appearance(self, after):
        """Since API 36 the service counts a device present while any source sees it (D18), so a
        reconnect after seq [after] is one appearance, not one per source."""
        if self.sdk >= 36:
            appeared = [e for e in self.log.events_after(after) if e["type"] == "DEVICE_APPEARED"]
            asserts.assert_equal(1, len(appeared), f"appearances from the sources {[e.get('message') for e in appeared]}")

    def receiver_operation(self, after, timeout=10.0):
        """The manifest receiver's resolved operation for A2DP connecting after seq [after]."""
        end = time.time() + timeout
        while True:
            ops = self.resolved_after(after)
            op = next((o for o in ops if o.trigger.get("message") == "manifest receiver"), None)
            if op is not None:
                return op
            if time.time() >= end:
                asserts.fail(f"the manifest receiver didn't handle A2DP connecting: {self.describe(ops)}")
            time.sleep(1)

    def check_connect_noted(self, op):
        """The manifest receiver noted the connect of its operation [op] for the Saved devices list. It does so
        just before it logs the trigger, so the times compare on the phone's clock, and the disconnect a
        reconnect starts with can't pass for it."""
        noted = self.last_connected()
        trigger = op.trigger["time"]
        self.ad.log.info("the connect was noted %s ms before its trigger", None if noted is None else trigger - noted)
        asserts.assert_true(noted is not None and trigger - NOTED_BEFORE_TRIGGER_MS <= noted <= trigger,
                            f"the connect wasn't noted: last connected {noted}, the receiver's trigger at {trigger}")

    def resolved_after(self, seq):
        return [op for op in app_log.operations(self.log.events_after(seq)) if op.resolution]

    def describe(self, ops):
        return "; ".join(f"{op.trigger['type']} ({op.trigger.get('message', '')}) -> {op.resolution}" for op in ops)

    def wait_until_applying(self, after, timeout=60.0):
        """Waits for an operation after seq [after] that has set a codec and is still open, returns it."""
        end = time.time() + timeout
        while True:
            ops = app_log.operations(self.log.events_after(after))
            for op in ops:
                if op.of_type("SET_CODEC_CONFIG") and not op.resolution:
                    return op
            if time.time() >= end:
                asserts.fail(f"no operation applied a codec within {timeout:.0f} s: {self.describe(ops)}")
            time.sleep(0.2)

    def wait_until_resolved(self, timeout=30.0):
        """Waits until no operation is open, returns the log."""
        end = time.time() + timeout
        while True:
            events = self.log.read()
            if not app_log.check_structure(events) or time.time() >= end:
                return events
            time.sleep(1)


class ColdStartTest(ColdStartBase):
    def test_c1_killed_app_applies_on_reconnect(self):
        """App process killed, the headset reconnects: applied. The manifest receiver handles A2DP connecting
        on every API level (D17), since API 33 the service may apply it first. The receiver also notes the
        disconnect and the connect for the Saved devices list."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        off = device_state.clock(self.adb)
        self.headset_off()
        self.check_last_connected(off, "the disconnect")
        # Since API 33 the system unbinds the service once the device is gone, after some seconds
        time.sleep(15)
        self.kill_app()
        time.sleep(3)
        asserts.assert_false(app.is_running(self.adb), "the system started the app's process again")
        mark = self.log.last_seq()
        self.headset_on()
        self.wait_for_applied(mark, codec)
        self.check_connect_noted(self.receiver_operation(mark))
        self.ad.log.info("C1: %s", self.describe(self.resolved_after(mark)))
        self.check_one_appearance(mark)

    def test_c2_app_in_front_applies_on_reconnect(self):
        """The same with the main screen open (manual 2.1), and the receiver notes the connect."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.launch(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.check_connect_noted(self.receiver_operation(mark))
        self.ad.log.info("C2 in front: %s", self.describe(ops))
        self.check_one_appearance(mark)

    def test_c2_app_in_background_applies_on_reconnect(self):
        """The same with the process alive in the background."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.launch(self.adb)
        app.go_home(self.adb)
        asserts.assert_true(app.is_running(self.adb), "the app's process isn't running")
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.check_connect_noted(self.receiver_operation(mark))
        self.ad.log.info("C2 in background: %s", self.describe(ops))
        self.check_one_appearance(mark)

    def test_c3_killed_while_present_then_a2dp_connects(self):
        """Characterization: the process is killed while the device is present with A2DP down, then A2DP connects."""
        self.require(self.sdk >= 33, "presence is only observed since API 33")
        self.require_capability(headset.A2DP_SWITCH)
        self.require_capability(headset.POWER_SWITCH)
        codec = self.preferred_codec()
        self.save_preferred(codec)
        self.headset_off()
        time.sleep(15)
        # Back on without A2DP: the device appears, and the service keeps it as present
        mark = self.log.last_seq()
        self.headset.power_on(a2dp=False)
        # Seen through BLE scans or the link
        appeared = self.log.wait_for_event("DEVICE_APPEARED", after=mark, device=self.address, timeout=APPEARANCE_TIMEOUT)
        self.ad.log.info("C3: %s", appeared)
        self.kill_app()
        time.sleep(5)
        restarted = app.is_running(self.adb)
        mark = self.log.last_seq()
        self.headset.connect_a2dp()
        bt_oracle.wait_connected(self.adb, self.address, timeout=30)
        time.sleep(10)
        ops = app_log.operations(self.log.events_after(mark))
        dumped = bt_oracle.active_codec(self.adb, self.address)
        applied = dumped is not None and dumped.codec_type == codec.codec_type
        self.ad.log.info("C3 on API %d: process restarted by the system %s, applied %s, codec %s: %s",
                         self.sdk, restarted, applied, dumped, self.describe(ops))
        expected = recorded(C3_APPLIED, self.sdk)
        if expected is None:
            asserts.explicit_pass(f"recorded: applied {applied}, process restarted {restarted}; "
                                  f"add it to C3_APPLIED for API {self.sdk}")
        asserts.assert_equal(expected, applied, f"applied after a kill while present: {self.describe(ops)}")

    def test_c4_killed_during_an_operation(self):
        """The process is killed while it applies the preferred codec: the next start resolves the
        operation OPERATION_INTERRUPTED, and nothing stays open."""
        self.allowed_interrupted = 1
        # A codec the headset doesn't offer keeps the operation open for 5 attempts
        self.save_preferred(bt_oracle.codec_outside(bt_oracle.device(self.adb, self.address).capabilities))
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        op = self.wait_until_applying(mark)
        # Right away: a busy process isn't cached until the operation is over
        self.kill_app(once_cached=False)
        # The label starts the process, which repairs the log when it loads it
        self.log.label("C4 next start")
        events = self.wait_until_resolved()
        killed = next(o for o in app_log.operations(events) if o.uid == op.uid)
        self.ad.log.info("C4: %s", killed)
        asserts.assert_equal("OPERATION_INTERRUPTED", killed.resolution, str(killed))
        asserts.assert_equal([], app_log.check_structure(events), "the log after the next start")

    def test_c5_permission_revoked(self):
        """Without the permission, the device connects: since API 33 the service logs its appearance,
        nothing is applied, no crash."""
        self.require(self.sdk >= 31, "the permission exists since API 31")
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.revoke_permission(self.adb)
        # Without the permission the receivers get no broadcast, only the device appearing again starts
        # the service, which applies nothing until A2DP connects. A device is gone some seconds after
        # its BLE advertisements stop.
        self.headset_off()
        time.sleep(15)
        mark = self.log.last_seq()
        self.headset_on()
        if self.sdk >= 33:
            self.log.wait_for_event("DEVICE_APPEARED", after=mark, device=self.address, timeout=APPEARANCE_TIMEOUT)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        time.sleep(10)
        ops = app_log.operations(self.log.events_after(mark))
        self.ad.log.info("C5: %s", self.describe(ops))
        # Anything that tries to apply the codec without the permission resolves NO_PERMISSION
        asserts.assert_true(all(o.resolution == "NO_PERMISSION" for o in ops), self.describe(ops))
        asserts.assert_false(same_format(codec, bt_oracle.active_codec(self.adb, self.address)), "the codec was applied")

    def test_c6_no_preferred_codec(self):
        """Without a preferred codec the codec isn't touched and nothing is logged or noted. The manifest
        receiver starts the process and returns."""
        self.kill_app()
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        time.sleep(10)
        events = self.log.events_after(mark)
        running = app.is_running(self.adb)
        self.ad.log.info("C6: process running %s, events %s", running, [e["type"] for e in events])
        asserts.assert_equal([], events, "the app logged something")
        # The receiver notes connects only for saved devices
        asserts.assert_equal({}, baseline.saved_device_info(self.adb), "the app keeps info of an unsaved device")
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(self.baseline.own_codec.same_config(dumped), f"dumpsys shows {dumped}")

    def test_c8_after_an_update(self):
        """After an update, without opening the app: applied."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.install(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, codec)
        self.ad.log.info("C8: %s", self.describe(ops))

    def test_c9_fresh_install(self):
        """Characterization: installed and never launched, the device connects. The app has nothing to
        apply and isn't started. Whether the install is in the stopped state is recorded."""
        device_state.shell(self.adb, f"pm uninstall {app.PACKAGE}", timeout=60)
        app.install(self.adb)
        stopped = app.is_stopped(self.adb)
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        time.sleep(10)
        running, logged = app.is_running(self.adb), self.log.exists()
        self.ad.log.info("C9 fresh install on API %d: stopped %s, process running %s, log written %s",
                         self.sdk, stopped, running, logged)
        asserts.assert_false(running, "the app's process was started")
        asserts.assert_false(logged, "the app logged something")

    def test_c9_force_stopped(self):
        """Characterization: the app is force-stopped, then the device connects: records whether the
        preferred codec is applied."""
        codec = self.preferred_codec()
        self.save_preferred(codec)
        app.force_stop(self.adb)
        asserts.assert_true(app.is_stopped(self.adb), "the package isn't stopped")
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        end = time.time() + 30
        while not (applied := same_format(codec, bt_oracle.active_codec(self.adb, self.address))) and time.time() < end:
            time.sleep(1)
        ops = app_log.operations(self.log.events_after(mark))
        self.ad.log.info("C9 force-stopped on API %d: applied %s, still stopped %s: %s",
                         self.sdk, applied, app.is_stopped(self.adb), self.describe(ops))
        expected = recorded(C9_FORCE_STOPPED_APPLIED, self.sdk)
        if expected is None:
            asserts.explicit_pass(f"recorded: applied {applied}; add it to C9_FORCE_STOPPED_APPLIED for API {self.sdk}")
        asserts.assert_equal(expected, applied, f"applied after a force stop: {self.describe(ops)}")
