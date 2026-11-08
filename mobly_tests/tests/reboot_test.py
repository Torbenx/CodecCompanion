"""L4 tests that reboot the phone: C7 without a lock screen, C14, C15 and C16
with a secure lock screen that is still locked when A2DP connects.

Before the first unlock after a boot, the app's credential protected storage
is encrypted. Its manifest receiver and companion service are direct boot
aware, so the system starts them anyway: the receiver applies the preferred
codec when A2DP connects, and the log keeps the events in a device protected
file until the unlock merges them. What happens per API level is in
TestConcept.md, below the C14 and C15 rows.

Which phones are rebooted depends on their lock screen (D24): a pure test
device gets the PIN 0000 for the locked reboot and loses it again, a
personal phone's password is typed, and a phone whose password isn't known
isn't rebooted. Every reboot is an unattended one, so a SIM with a PIN
unlocks by itself.
"""

import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import device_state
from lib import lock_screen
from lib import sim_helper
from lib.bt_oracle import same_format
from tests.cold_start_test import APPEARANCE_TIMEOUT
from tests.cold_start_test import APPLY_TIMEOUT
from tests.cold_start_test import ColdStartBase

# How long after the unlock the preferred codec may take, measured from the unlock
UNLOCK_APPLY_TIMEOUT = 60.0
# Since API 35 the system reports the device right at the unlock, from the presence events it held back
UNLOCK_APPEARANCE_TIMEOUT = 60.0
# How long the SIMs may take to unlock with their stored PINs, measured from the storage's unlock
SIM_UNLOCK_TIMEOUT = 60.0
# How long after the unlock nothing may start the app's process, which would merge the log
UNLOCK_IDLE = 15.0
# How long the main screen may take to merge the log, measured from its start
MERGE_TIMEOUT = 15.0


class RebootTest(ColdStartBase):
    def setup_class(self):
        super().setup_class()
        # Samsung only, adb can't change it: settings put refuses the key
        if device_state.get_setting(self.adb, "secure", "block_usb_lock") == "1":
            asserts.abort_class("block_usb_lock is 1, adb would stay blocked after a reboot until a manual the unlock")

        if self.lock_class == lock_screen.PURE_TEST_DEVICE and lock_screen.has_credential(self.adb):
            # Left by a run that ended before its teardown. The suite only ever sets 0000, so clearing it is tried once
            self.ad.log.warning("the pure test device has a credential, clearing the PIN 0000")
            try:
                lock_screen.clear_pin(self.adb, self.lock_class)
            except RuntimeError as e:
                asserts.abort_class(f"the pure test device has a credential other than the PIN 0000: {e}")

    def setup_test(self):
        super().setup_test()
        self._pin_set = False
        self._unlock_failed = False

    def require_lock_screen(self):
        self.require(self.lock_class != lock_screen.UNKNOWN_PASSWORD,
                     "the lock screen's password isn't known, the phone can't be unlocked after a reboot")

    def unlock(self):
        """Unlocks the phone like a user would and returns the time, or aborts the run if it stays locked.

        First the storage, with locksettings verify. Then the password is typed
        on the lock screen, but only once every SIM is loaded or absent: before,
        the lock screen asks for a SIM PIN, and the password would count as a
        wrong one. Tried once: a wrong password counts as a failed attempt,
        which brings lockout delays and, on some phones, a factory reset.
        """
        # A pure test device only has a PIN during a locked reboot test
        secret = lock_screen.password(self.testbed, self.ad.serial) if lock_screen.has_credential(self.adb) else None
        try:
            lock_screen.verify(self.ad.serial, secret)
            self.wait_for_sims()
            # The user and SIM unlocking can cause some lag as the phone catches up. In rare
            # cases this caused the lock screen password to be typed before the UI was ready,
            # failing the test.
            time.sleep(5)
            lock_screen.unlock(self.adb, self.ad.serial, secret)
        except lock_screen.UnlockFailed as e:
            self._unlock_failed = True
            asserts.abort_all(f"{e}. Unlock {self.ad.serial} by hand, the suite doesn't try again")
        return time.time()

    def wait_for_sims(self, timeout=SIM_UNLOCK_TIMEOUT):
        """Waits until every SIM is loaded or absent, or raises UnlockFailed."""
        end = time.time() + timeout
        while not sim_helper.all_sims_loaded_or_absent(self.adb):
            if time.time() >= end:
                raise lock_screen.UnlockFailed(f"the SIMs didn't unlock within {timeout:.0f} s: "
                                               f"{sim_helper.sim_states(self.adb)}, the password wasn't typed")
            time.sleep(0.5)

    def reboot_locked(self, codec):
        """Saves [codec] as preferred and reboots with a secure lock screen. Returns the log's last seq
        before the reboot once A2DP is connected while the user is still locked."""
        if self.lock_class == lock_screen.KNOWN_PASSWORD:
            # A testbed.yaml it can't be read from fails now, not at the lock screen
            lock_screen.verify(self.ad.serial, lock_screen.password(self.testbed, self.ad.serial))
        self.save_preferred(codec)
        # The fixture granted the permission just before, and a reboot loses it until the system writes it
        app.wait_permission_persisted(self.adb)
        if self.lock_class == lock_screen.PURE_TEST_DEVICE:
            lock_screen.set_pin(self.adb, self.lock_class)
            self._pin_set = True
        mark = self.log.last_seq()
        sim_helper.unattended_reboot(self.ad)
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked without the lock screen, does the phone have a secure one?")
        try:
            bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        except TimeoutError:
            # The connect fixture's last resort, the phone's Bluetooth settings, is behind the lock screen
            self.headset.reconnect(self.adb)
            try:
                bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
            except TimeoutError:
                asserts.fail("A2DP didn't connect before the unlock, not even after the headset reconnected")
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked before A2DP connected")
        dumped = bt_oracle.active_codec(self.adb, self.address)
        self.ad.log.info("A2DP connected before the unlock with %s, the preferred codec is %s", dumped, codec)
        return mark

    def wait_applied_while_locked(self, codec, timeout=APPLY_TIMEOUT):
        """Waits until A2DP uses [codec] while the user is still locked. The log can't be read before
        the unlock, so dumpsys decides."""
        end = time.time() + timeout
        while not same_format(codec, dumped := bt_oracle.active_codec(self.adb, self.address)):
            if time.time() >= end:
                asserts.fail(f"{codec} wasn't applied before the unlock within {timeout:.0f} s, dumpsys shows {dumped}")
            time.sleep(1)
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked before the codec was applied")

    def process_starts(self):
        """Why the system started the app's process since the boot, from am_proc_start in the events buffer."""
        out = device_state.shell(self.adb, "logcat -d -b events -s am_proc_start", timeout=30)
        return [line.split("am_proc_start: ", 1)[-1] for line in out.splitlines() if f",{app.PACKAGE}," in line]

    def appearance(self, after, timeout):
        """The first DEVICE_APPEARED after seq [after] within [timeout] s, or None."""
        try:
            return self.log.wait_for_event("DEVICE_APPEARED", after=after, device=self.address, timeout=timeout)
        except TimeoutError:
            return None

    def reset_phone(self):
        """Unlocks the phone if it's still locked and clears the PIN (D26).

        On API 31 to 33, and on 34 after a BLE sighting, the system can't find
        a companion service that isn't direct boot aware when it looks it up
        while the user is locked, until the next reboot. The app's service is
        direct boot aware, so the next test can run in the same boot.
        """
        if self._unlock_failed:
            asserts.abort_all(f"{self.ad.serial} is still locked")
        if not device_state.is_unlocked(self.adb) or lock_screen.user_state(self.adb) != lock_screen.RUNNING_UNLOCKED:
            self.unlock()
        if self._pin_set:
            lock_screen.clear_pin(self.adb, self.lock_class)
            self._pin_set = False

    def test_c7_after_a_reboot(self):
        """After a reboot, without opening the app: applied."""
        self.require(self.lock_class == lock_screen.PURE_TEST_DEVICE, "only a pure test device has no lock screen")
        codec = self.preferred_codec()
        self.save_preferred(codec)
        # The fixture granted the permission just before, and a reboot loses it until the system writes it
        app.wait_permission_persisted(self.adb)
        mark = self.log.last_seq()
        sim_helper.unattended_reboot(self.ad)
        # A pure test device has no lock screen, the keyguard only needs dismissing
        device_state.wake(self.adb)
        device_state.shell(self.adb, "wm dismiss-keyguard")
        baseline.connect(self.adb, self.headset)
        ops = self.wait_for_applied(mark, codec, timeout=120)
        self.ad.log.info("C7: %s", self.describe(ops))

    def test_c14_locked_reboot_applies_after_the_unlock(self):
        """After a reboot with a secure lock screen, A2DP connects before the first unlock: applied after
        the unlock, without another connect.

        The manifest receiver applies it before the unlock. The log is only read
        after it, so this also checks that the locked events are merged. The
        receiver also notes the connect for the Saved devices list before the
        unlock, in device protected storage.
        """
        self.require_lock_screen()
        codec = self.preferred_codec()
        mark = self.reboot_locked(codec)
        booted = int(device_state.boot_time(self.adb))
        locked_until = device_state.clock(self.adb)
        unlocked = self.unlock()
        ops = self.wait_for_applied(mark, codec, timeout=UNLOCK_APPLY_TIMEOUT)
        self.ad.log.info("C14: applied %.0f s after the unlock: %s", time.time() - unlocked, self.describe(ops))
        # After the boot, so not the save or the shutdown's disconnect, and before the unlock
        self.check_last_connected(booted, "the connect before the unlock", until=locked_until)

    def test_c15_companion_service_after_a_locked_reboot(self):
        """After the same locked reboot, the system reports the device to the companion service: after
        the unlock, since it's connected, and again when it next connects.

        The service is direct boot aware, so the system finds it while the user
        is locked. Up to API 34 it reports the device as soon as it sees it,
        since API 35 it holds the presence events back and reports them at the
        unlock.
        """
        self.require_lock_screen()
        self.require(self.sdk >= 33, "presence is only observed since API 33")
        codec = self.preferred_codec()
        mark = self.reboot_locked(codec)
        self.unlock()
        after_unlock = self.appearance(mark, UNLOCK_APPEARANCE_TIMEOUT)
        self.headset_off()
        # Gone for the system too: a device counts as gone some seconds after its BLE advertisements stop
        time.sleep(15)
        mark = self.log.last_seq()
        self.headset_on()
        bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
        on_connect = self.appearance(mark, APPEARANCE_TIMEOUT)
        events = self.log.events_after(mark)
        ops = self.resolved_after(mark)
        applied = same_format(codec, bt_oracle.active_codec(self.adb, self.address))
        self.ad.log.info("C15 on API %d: appeared after the unlock %s, on the next connect %s; applied %s: %s",
                         self.sdk, after_unlock is not None, on_connect is not None, applied, self.describe(ops))
        missing = [when for when, event in (("after the unlock", after_unlock), ("on the next connect", on_connect))
                   if event is None]
        asserts.assert_equal([], missing, f"the system didn't report the device to the companion service; "
                                          f"events since the connect: {[e['type'] for e in events]}")

    def test_c16_main_screen_merges_the_locked_log(self):
        """After the same locked reboot, the device goes away and the app's process ends before the
        unlock: the unlock doesn't merge the log, opening the main screen without a device does.

        Without a process at the unlock, no receiver of the log is registered for
        it, and with the device gone the system has no reason to start one. The
        main screen logs nothing without a connected device, so what merges the
        log is EventLog.get() in its onCreate.

        Since API 35 the companion device manager holds the presence events back
        until the unlock, and for a BLE device lost while locked it queues an
        appearance, which starts the process at the unlock. It keeps them in
        memory only, so there the phone is rebooted again, still locked, once the
        device is gone; that ends the process too.
        """
        self.require_lock_screen()
        codec = self.preferred_codec()
        mark = self.reboot_locked(codec)
        self.wait_applied_while_locked(codec)
        # The receiver's broadcast is finished once its operation is resolved and written, and only
        # then does am kill end the process. dumpsys shows the codec before it's verified, and the
        # device going away first would resolve the operation NOT_CONNECTED
        app.kill_cached(self.adb, timeout=30)
        off = device_state.clock(self.adb)
        self.headset_off()
        if self.sdk >= 35:
            sim_helper.unattended_reboot(self.ad)
        else:
            # The disconnect is broadcast to the manifest receiver too, which starts the process again
            # and logs nothing for it
            time.sleep(5)
            app.kill_cached(self.adb)
        time.sleep(3)
        if app.is_running(self.adb):
            asserts.fail(f"the system started the app's process again: {self.process_starts()}")
        asserts.assert_equal(lock_screen.RUNNING_LOCKED, lock_screen.user_state(self.adb),
                             "the user was unlocked before the app's process ended")
        # The phone's clock, in ms like the log. Rounded up, the events of the second it's read in count as before
        locked_until = (int(device_state.shell(self.adb, "date +%s")) + 1) * 1000
        self.unlock()
        time.sleep(UNLOCK_IDLE)
        if app.is_running(self.adb):
            asserts.fail(f"the app's process started after the unlock, which merges the log: {self.process_starts()}")
        asserts.assert_equal(mark, self.log.last_seq(), "the log changed without the app's process")
        lines = self.log.locked_line_count()
        # At least the receiver's operation: its trigger, the codec it set and its verification
        asserts.assert_true(lines is not None and lines >= 3, f"the device protected file has {lines} lines")
        connected = [d.address for d in bt_oracle.read(self.adb).devices if d.connected]
        asserts.assert_equal([], connected, "a device is connected, the main screen would log its codec")

        # The main screen reads the saved devices' info on every poll, which only works once the user is
        # unlocked; a crash would show in the invariants' crash buffer
        app.launch(self.adb)
        end = time.time() + MERGE_TIMEOUT
        while True:
            merged, remaining = self.log.events_after(mark), self.log.locked_line_count()
            # The device protected file is deleted once the merged log is written
            if len(merged) >= lines and remaining is None:
                break
            if time.time() >= end:
                asserts.fail(f"opening the main screen didn't merge the log within {MERGE_TIMEOUT:.0f} s: "
                             f"{len(merged)} of {lines} events, the device protected file has {remaining} lines")
            time.sleep(1)
        asserts.assert_true(app.is_running(self.adb), "the main screen's process ended")
        app.go_home(self.adb)
        # The receiver noted the disconnect before the unlock, and opening the main screen without the device
        # kept it
        self.check_last_connected(off, "the disconnect before the unlock", until=locked_until)
        # The merge appends the device protected events first, any interruptions follow them
        locked = merged[:lines]
        later = [e["type"] for e in locked if e["time"] > locked_until]
        asserts.assert_equal([], later, "merged events that were logged after the unlock")
        ops = app_log.operations(locked)
        self.ad.log.info("C16 on API %d: %d events merged when the main screen opened: %s",
                         self.sdk, lines, self.describe(ops))
        asserts.assert_true(
            any(op.trigger.get("message") == "manifest receiver" and op.resolution == "CODEC_VERIFIED" for op in ops),
            f"the receiver's operation before the unlock isn't merged: {self.describe(ops)}")
