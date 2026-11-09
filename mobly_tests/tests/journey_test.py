"""L5, the journeys: through the UI with snippet-uiautomator, which runs in its own process.

They check that the parts work together, including the system's dialog that
links a device, not every detail on the way; the UI tests cover those.
"""

import datetime
import re
import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import companion
from lib import device_state
from lib import headset
from lib import hibernation
from lib import lock_screen
from lib import screens
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest
from lib.device_test import recorded

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
AAC_44_16 = Codec(bt_oracle.AAC, 0x1, 0x1)
SBC_TEXTS = ("SBC", "44100 Hz", "16-bit")
APPLY_TIMEOUT = 60.0
EXPORTS = "cache/exports"
MAC = re.compile(r"([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
# The app's locale is English whatever the phone's language, the S24 showed "Heute" here before 307d970. A
# disconnect just before midnight is yesterday's by the time the banner is read.
LAST_CONNECTED_TODAY = re.compile(r"Last connected: (Today|Yesterday), .+")
OBSERVING_TIMEOUT = 10.0
# Bluetooth turned on by the app's dialog, then the main screen waits for the profile before it says there are
# no devices
BLUETOOTH_ON_TIMEOUT = 20.0
NO_DEVICES_TIMEOUT = datetime.timedelta(seconds=30)
# The headset switched on reconnects to the phone it served last, this one
HEADSET_ON_TIMEOUT = datetime.timedelta(seconds=60)
# J13, J14: how long a hibernated app has to apply the preferred codec after the headset connected
UNUSED_SETTLE = 10.0

# Characterization (D8), recorded per range of API levels (6.7), see device_test.recorded().
# J13, J14: (whether the preferred codec is applied, whether the app is still hibernated) once the headset
# connected while the app was hibernated, linked and without the permission. On 31 and 32 the package stays
# stopped, and the companion service is disabled (D27). On 37 the device appearing starts the app's process,
# which ends the hibernation and the stopped state, but without the permission nothing applies the codec.
UNUSED_APPLIED = {(31, 32): (False, True), (37, None): (False, False)}


def chips(codec):
    """The chips the collapsed card shows for [codec]."""
    return (codec.name, f"{bt_oracle.SAMPLE_RATES[codec.sample_rate]} Hz",
            f"{bt_oracle.BITS_PER_SAMPLE[codec.bits_per_sample]}-bit")


class JourneyTest(BaselineTest):
    def setup_class(self):
        super().setup_class()
        screens.register(self.ad)

    @property
    def address(self):
        return self.headset.address

    def main_screen(self):
        return screens.MainScreen(self.ad, self.headset).open()

    def choose(self, screen, chip, codec, link=False):
        """Chooses a codec with the chips of the expanded card, and checks it's applied. With [link] the
        device isn't linked where changing the codec needs it, API 34 and 35, so the system's dialog links it first."""
        mark = self.log.last_seq()
        screen.tap_chip(chip)
        if link:
            screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SET_CODEC", device=self.address, timeout=30)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        if link:
            asserts.assert_true(op.of_type("ASSOCIATION_REQUESTED"), f"changing the codec didn't ask for the link: {op}")
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(same_format(dumped, codec), f"dumpsys shows {dumped}")
        screen.collapse()

    def wait_for_applied(self, after, codec, timeout=APPLY_TIMEOUT):
        """Waits until A2DP is connected with [codec] and an operation after seq [after] verified it.

        A disconnected device keeps its last codec in dumpsys, so only a connected one counts.
        """
        end = time.time() + timeout
        while True:
            dumped = bt_oracle.active_codec(self.adb, self.address)
            ops = app_log.operations(self.log.events_after(after))
            if same_format(dumped, codec) and any(op.resolution == "CODEC_VERIFIED" for op in ops):
                return ops
            if time.time() >= end:
                asserts.fail(f"{codec} wasn't applied within {timeout:.0f} s, dumpsys shows {dumped}: "
                             + "; ".join(map(str, ops)))
            time.sleep(1)

    def save_preferred(self, screen):
        """Saves the card's codec as preferred with Save as preferred; the baseline's link means no dialog."""
        mark = self.log.last_seq()
        asserts.assert_equal("none", screen.preferred_status(), "the banner before saving")
        screen.tap_save()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        self.assert_preferred_active(screen, "after saving")

    def assert_preferred_active(self, screen, when):
        """The banner says the preferred codec is active, and the collapsed card has its chip. The card offers Save
        as preferred exactly when the banner doesn't say so, the JVM tests cover that."""
        asserts.assert_true(screen.wait_preferred("active"), f"the banner {when}: {screen.preferred_status()}")
        asserts.assert_true(screen.preferred_chip_shown(), f"no Preferred Codec chip {when}")

    def test_j2_choose_a_codec_with_the_chips(self):
        """Choosing SBC on the card changes the codec, and the card and the log show it (manual 1.1)."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        asserts.assert_true(screen.shows(*SBC_TEXTS), "the card doesn't show SBC")

    def test_j3_save_as_preferred_and_reconnect(self):
        """Saving the codec as preferred, linking where needed; after a reconnect it's applied and shown."""
        # Since API 33 saving links the device, on 31 and 32 only an app without any link, J9. On API 34
        # and 35 changing the codec already links it. On API 36 an unlinked device's codec is unreadable,
        # so there is nothing to save; J6 covers that.
        linking = 33 <= self.sdk < 36
        if linking:
            companion.unlink(self.adb, app.PACKAGE, self.address)
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16, link=linking and self.sdk >= 34)
        mark = self.log.last_seq()
        asserts.assert_equal("none", screen.preferred_status(), "the banner before saving")
        screen.tap_save()
        if linking and self.sdk == 33:
            screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        self.assert_preferred_active(screen, "after saving")
        if linking:
            asserts.assert_true(companion.is_linked(self.adb, app.PACKAGE, self.address), "the device isn't linked")
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        self.wait_for_applied(mark, SBC_44_16)
        screen = self.main_screen()
        asserts.assert_true(screen.shows(*SBC_TEXTS), "the card doesn't show SBC")
        self.assert_preferred_active(screen, "after the reconnect")

    def test_j6_an_unlinked_device_asks_to_be_linked(self):
        """Since API 34 an unlinked device asks for the link; on 36 its codec only shows once it's linked."""
        self.require(self.sdk >= 34, "a link is only needed since API 34")
        companion.unlink(self.adb, app.PACKAGE, self.address)
        screen = self.main_screen()
        if self.sdk >= 36:
            asserts.assert_true(screen.shows_link_prompt(), "the card doesn't ask for the link")
            asserts.assert_false(screen.chip("96000 Hz").exists, "the codec shows without a link")
        mark = self.log.last_seq()
        screen.tap_link()
        screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_LINK_DEVICE", device=self.address, timeout=30)
        asserts.assert_equal("ASSOCIATION_CREATED", op.resolution, str(op))
        asserts.assert_true(screen.shows("LDAC", "96000 Hz"), "the codec doesn't show after linking")
        self.choose(screen, "SBC", SBC_44_16)

    def test_j9_a_link_is_asked_for_once_on_api_31_and_32(self):
        """On API 31 and 32 saving asks for a link only while the app has none and isn't exempt from losing
        its permission when unused. The link exempts it; after unlinking, saving doesn't ask again."""
        self.require(31 <= self.sdk <= 32, "only API 31 and 32 link for the exemption")
        # A fresh install that was never linked; the baseline links again afterwards
        companion.unlink(self.adb, app.PACKAGE, self.address)
        app.reset_auto_revoke(self.adb)
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        mark = self.log.last_seq()
        screen.tap_save()
        screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_true(op.of_type("ASSOCIATION_REQUESTED"), f"saving didn't ask for the link: {op}")
        asserts.assert_true(companion.is_linked(self.adb, app.PACKAGE, self.address), "the device isn't linked")
        asserts.assert_equal("ignore", app.auto_revoke_mode(self.adb), "linking didn't exempt the app")

        companion.unlink(self.adb, app.PACKAGE, self.address)
        asserts.assert_equal("ignore", app.auto_revoke_mode(self.adb), "unlinking ended the exemption")
        screen.tap_delete()
        asserts.assert_true(screen.wait_preferred("none"), f"the banner after deleting: {screen.preferred_status()}")
        mark = self.log.last_seq()
        screen.tap_save()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_false(op.of_type("ASSOCIATION_REQUESTED"), f"saving asked for the link again: {op}")
        asserts.assert_false(companion.is_linked(self.adb, app.PACKAGE, self.address), "the device was linked again")

    def test_j13_an_unused_app_and_the_systems_job(self):
        """The system's job for unused apps, forced: it hibernates the fresh install and takes its permission,
        opening the app brings it back, the first link exempts it, so the job leaves it alone and the preferred codec
        is applied. With the exemption turned off in the app's settings the job hibernates it again (D28)."""
        self.require(self.sdk >= 31, "the system hibernates unused apps since API 31")
        self.require(self.lock_class == lock_screen.PURE_TEST_DEVICE,
                     "the job's threshold is the whole phone's, so the job only runs on a pure test device (D28)")
        self.unused_app_journey(real=True)

    def test_j14_an_unused_app_simulated(self):
        """J13 with the unused app's state put on the app alone instead of the system's job, on every phone: what
        it means for the app and how it gets back, not whether the system exempts it (D28)."""
        self.require(self.sdk >= 31, "the system hibernates unused apps since API 31")
        self.unused_app_journey(real=False)

    def unused_app_journey(self, real):
        """J13 with the system's job if [real], else J14."""
        try:
            # A fresh install was never linked, so nothing exempts it
            app.uninstall(self.adb)
            app.install(self.adb)
            asserts.assert_equal((None, "default"), app.auto_revoke_modes(self.adb), "the fresh install is exempt")
            asserts.assert_false(companion.is_linked(self.adb, app.PACKAGE, self.address), "the fresh install is linked")
            app.launch(self.adb)
            screen = screens.MainScreen(self.ad, self.headset)
            screen.answer_permission(allow=True)
            screen.wait_for_card()
            if real:
                hibernation.isolate(self.adb, keep=[app.PACKAGE])

            self.make_unused(real, "the fresh install")
            self.check_unused(real, "the fresh install")

            # Opening the app ends its hibernation, and it asks for the permission again
            app.launch(self.adb)
            screen = screens.MainScreen(self.ad, self.headset)
            asserts.assert_false(hibernation.is_hibernated(self.adb), "opening the app didn't end its hibernation")
            asserts.assert_false(hibernation.is_hibernated(self.adb, globally=True),
                                 "opening the app didn't end its global hibernation")
            asserts.assert_false(app.is_stopped(self.adb), "the opened app's package is stopped")
            screen.answer_permission(allow=True)
            screen.wait_for_card()

            self.link_and_save(screen)
            asserts.assert_true(companion.is_linked(self.adb, app.PACKAGE, self.address), "the device isn't linked")
            asserts.assert_equal((None, "ignore"), app.auto_revoke_modes(self.adb), "the link didn't exempt the app")
            if real:
                self.make_unused(real, "the linked app")
                asserts.assert_false(hibernation.is_hibernated(self.adb), "the job hibernated the linked app")
                asserts.assert_false(hibernation.is_hibernated(self.adb, globally=True),
                                     "the job hibernated the linked app globally")
                asserts.assert_false(app.is_stopped(self.adb), "the job stopped the linked app")
                asserts.assert_true(app.permission_state(self.adb)[0], "the job took the linked app's permission")
            else:
                app.go_home(self.adb)
                app.kill(self.adb)
            mark = self.log.last_seq()
            self.headset.reconnect(self.adb)
            self.wait_for_applied(mark, SBC_44_16)

            # The user turns on "Remove permissions and free up space" in the app's settings, which writes this
            app.set_auto_revoke_uid_mode(self.adb, "allow")
            self.make_unused(real, "the app with the exemption turned off")
            self.check_unused(real, "the app with the exemption turned off")
            asserts.assert_true(companion.is_linked(self.adb, app.PACKAGE, self.address), "the link is gone")
            mark = self.log.last_seq()
            self.headset.reconnect(self.adb)
            bt_oracle.wait_connected(self.adb, self.address, timeout=baseline.CONNECT_TIMEOUT)
            time.sleep(UNUSED_SETTLE)
            dumped = bt_oracle.active_codec(self.adb, self.address)
            outcome = (same_format(dumped, SBC_44_16), hibernation.is_hibernated(self.adb))
            events = self.log.events_after(mark)
            ops = app_log.operations(events)
            self.ad.log.info("hibernated on API %d: (applied, still hibernated) %s, process running %s, stopped %s, "
                             "permission %s, codec %s, events %s: %s", self.sdk, outcome, app.is_running(self.adb),
                             app.is_stopped(self.adb), app.permission_state(self.adb), dumped,
                             [e["type"] for e in events], "; ".join(map(str, ops)))
            expected = recorded(UNUSED_APPLIED, self.sdk)
            if expected is None:
                asserts.explicit_pass(f"recorded: (applied, still hibernated) {outcome}; "
                                      f"add it to UNUSED_APPLIED for API {self.sdk}")
            asserts.assert_equal(expected, outcome,
                                 f"(applied, still hibernated) after the reconnect: {'; '.join(map(str, ops))}")
        finally:
            if real:
                hibernation.clean_up(self.adb)

    def make_unused(self, real, what):
        """[what] isn't used any more and the system's job runs, or with [real] false its result is put on the app."""
        app.go_home(self.adb)
        app.kill(self.adb)
        if not real:
            hibernation.simulate(self.adb)
            return
        hibernation.wait()
        since = device_state.shell(self.adb, "date +'%m-%d %H:%M:%S.000'")
        ran = hibernation.run_job(self.adb)
        self.ad.log.info("the job about %s: %s", what, hibernation.job_log(self.adb, since) or "nothing")
        asserts.assert_true(ran, f"the job didn't run for {what}")

    def check_unused(self, real, what):
        """[what] is in the state the job leaves an app in that isn't exempt."""
        asserts.assert_true(hibernation.is_hibernated(self.adb), f"{what} isn't hibernated")
        asserts.assert_true(hibernation.is_hibernated(self.adb, globally=True), f"{what} isn't hibernated globally")
        asserts.assert_true(app.is_stopped(self.adb), f"the package of {what} isn't stopped")
        asserts.assert_false(app.is_running(self.adb), f"{what} runs")
        granted, flags = app.permission_state(self.adb)
        asserts.assert_false(granted, f"{what} still has the permission")
        if real:
            # The shell can't set these, simulate() leaves them out
            asserts.assert_true("AUTO_REVOKED" in flags and "USER_SET" not in flags,
                                f"the permission's flags for {what}: {sorted(flags)}")

    def link_and_save(self, screen):
        """Links the device the first time, the way each API level asks for it, and saves SBC as preferred."""
        if self.sdk >= 36:
            # An unlinked device's codec is unreadable, so its card asks for the link first (J6)
            asserts.assert_true(screen.shows_link_prompt(), "the card doesn't ask for the link")
            mark = self.log.last_seq()
            screen.tap_link()
            screen.accept_link_dialog()
            op = self.log.wait_for_operation(after=mark, trigger="USER_LINK_DEVICE", device=self.address, timeout=30)
            asserts.assert_equal("ASSOCIATION_CREATED", op.resolution, str(op))
        # On 34 and 35 changing the codec asks for the link, before saving does (J3)
        self.choose(screen, "SBC", SBC_44_16, link=34 <= self.sdk < 36)
        mark = self.log.last_seq()
        asserts.assert_equal("none", screen.preferred_status(), "the banner before saving")
        screen.tap_save()
        # On 31 and 32 because the app isn't exempt and has no link, on 33 because the device has none
        if self.sdk < 34:
            screen.accept_link_dialog()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        if self.sdk < 34:
            asserts.assert_true(op.of_type("ASSOCIATION_REQUESTED"), f"saving didn't ask for the link: {op}")
        self.assert_preferred_active(screen, "after saving")

    def test_j1_first_start(self):
        """First start: the permission is granted in the system's dialog, and the card shows the codec
        dumpsys reports. Denying it twice shows the settings hint."""
        self.require(self.sdk >= 31, "the permission exists since API 31")

        def first_start():
            # A fresh start: the permission is revoked and its flags cleared
            app.clear_data(self.adb)
            if not companion.is_linked(self.adb, app.PACKAGE, self.address):
                companion.link(self.adb, app.PACKAGE, self.address)
            app.launch(self.adb)
            return screens.MainScreen(self.ad, self.headset)

        screen = first_start()
        screen.answer_permission(allow=True)
        screen.wait_for_card()
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(screen.shows(*chips(dumped)), f"the card doesn't show {dumped}")
        screen = first_start()
        screen.answer_permission(allow=False)
        screen.tap_text("Grant permission")
        screen.answer_permission(allow=False)
        asserts.assert_true(screen.shows_text("Allow it in the app settings under Permissions."), "no settings hint")
        asserts.assert_true(screen.shows_text("Open settings"), "no Open settings button")

    def test_j4_an_external_change_shows_and_the_preferred_codec_is_restored(self):
        """A codec set by another app shows on the card within 2 s; Restore Preferred Codec brings the
        preferred codec back (manual 1.2, 2.2). The snippet sets it the way the developer options do."""
        own = bt_oracle.active_codec(self.adb, self.address)
        # Loading the snippet restarts the app's process, so it's loaded first
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        try:
            screen = self.main_screen()
            self.save_preferred(screen)
            # Collapsed before the change, so only the change is timed
            screen.collapse()
            getattr(self.ad, SNIPPET).setCodecDirectly(self.address, SBC_44_16.to_app())
            changed = time.time()
            bt_oracle.wait_for(self.adb, lambda s: same_format(SBC_44_16, s.device(self.address).codec), 10, what="SBC")
            asserts.assert_true(screen.shows(*SBC_TEXTS), "the card doesn't show SBC")
            self.ad.log.info("J4: the card showed SBC %.1f s after the change", time.time() - changed)
            asserts.assert_true(time.time() - changed <= 2.0 + 1.5, "the card took longer than 2 s, plus the reads")
            asserts.assert_true(screen.preferred_chip_shown(False), "the Preferred Codec chip shows after the change")
            asserts.assert_equal("saved", screen.preferred_status(), "the banner after the change")
            mark = self.log.last_seq()
            screen.tap_restore()
            op = self.log.wait_for_operation(after=mark, trigger="USER_RESTORE_PREFERRED", device=self.address, timeout=30)
            asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
            asserts.assert_true(same_format(own, bt_oracle.active_codec(self.adb, self.address)), "not restored")
            asserts.assert_true(screen.shows(*chips(own)), f"the card doesn't show {own}")
            self.assert_preferred_active(screen, "after restoring")
        finally:
            self.ad.unload_snippet(SNIPPET)

    def test_j5_delete_the_preferred_codec(self):
        """After deleting the preferred codec, the card offers to save it again and a reconnect leaves the
        codec alone."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        self.save_preferred(screen)
        mark = self.log.last_seq()
        screen.tap_delete()
        self.log.wait_for_event("PREFERRED_DELETED", after=mark, timeout=10)
        asserts.assert_true(screen.wait_preferred("none"), f"the banner after deleting: {screen.preferred_status()}")
        asserts.assert_false(screen.banner().child(res="restore_preferred").exists, "Restore is offered after deleting")
        asserts.assert_true(screen.scroll_to(res="save_preferred"), "Save as preferred isn't offered after deleting")
        asserts.assert_true(screen.preferred_chip_shown(False), "the Preferred Codec chip shows after deleting")
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        bt_oracle.wait_connected(self.adb, self.address, timeout=60)
        time.sleep(10)
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(self.baseline.own_codec.same_config(dumped), f"dumpsys shows {dumped}")
        set_codec = [e for e in self.log.events_after(mark) if e["type"] == "SET_CODEC_CONFIG"]
        asserts.assert_equal([], set_codec, "a codec was set after the reconnect")

    def test_j7_rotate_while_applying(self):
        """Rotating while a codec is applied: no crash, the codec is verified (manual 1.6)."""
        saved = {key: device_state.get_setting(self.adb, "system", key) for key in ("accelerometer_rotation", "user_rotation")}
        device_state.put_setting(self.adb, "system", "accelerometer_rotation", "0")
        device_state.put_setting(self.adb, "system", "user_rotation", "0")
        try:
            screen = self.main_screen()
            pid = app.pid(self.adb)
            mark = self.log.last_seq()
            screen.tap_chip("SBC")
            device_state.put_setting(self.adb, "system", "user_rotation", "1")
            op = self.log.wait_for_operation(after=mark, trigger="USER_SET_CODEC", device=self.address, timeout=30)
            self.ad.log.info("J7: %s", op)
            asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
            asserts.assert_true(same_format(SBC_44_16, bt_oracle.active_codec(self.adb, self.address)), "SBC isn't active")
            screen.wait_for_card()
            asserts.assert_equal(pid, app.pid(self.adb), "the app's process changed")
        finally:
            for key, value in saved.items():
                device_state.put_setting(self.adb, "system", key, value)

    def test_j8_the_log_shows_a_reconnect_and_exports_it_anonymised(self):
        """The log screen shows a real reconnect with its attempts; the anonymised export contains no
        addresses or names (log 2.5)."""
        screen = self.main_screen()
        self.choose(screen, "SBC", SBC_44_16)
        self.save_preferred(screen)
        app.go_home(self.adb)
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        ops = self.wait_for_applied(mark, SBC_44_16)
        verified = next(op for op in ops if op.resolution == "CODEC_VERIFIED")
        log_screen = self.main_screen().open_log()
        seq = verified.trigger["seq"]
        asserts.assert_true(log_screen.entry_shows(seq, r"(?s).*Codec applied: SBC.*"), f"entry {seq} isn't shown")
        log_screen.toggle(seq)
        for event in verified.of_type("SET_CODEC_CONFIG"):
            asserts.assert_true(log_screen.entry_shows(seq, rf"Setting codec, attempt {event['attempt']}: SBC.*"),
                                f"attempt {event['attempt']} isn't shown")
        device_state.shell(self.adb, f"run-as {app.PACKAGE} rm -rf {EXPORTS}")
        log_screen.share()
        files = device_state.shell(self.adb, f"run-as {app.PACKAGE} ls {EXPORTS}").split()
        asserts.assert_equal(1, len(files), f"exports: {files}")
        export = device_state.shell(self.adb, f"run-as {app.PACKAGE} cat {EXPORTS}/{files[0]}", timeout=30)
        asserts.assert_true(any(e["seq"] == seq for e in app_log.parse(export)), "the reconnect isn't exported")
        asserts.assert_is_none(MAC.search(export), "the export contains an address")
        asserts.assert_true(self.headset.name not in export, "the export contains the headset's name")
        asserts.assert_true('"address":"device-' in export, "the export has no device ids")

    def stored(self):
        """What the app stores of the headset as a saved device: its preferred codec and its info, None for each
        that isn't stored."""
        preferred, info = baseline.preferred_codecs(self.adb), baseline.saved_device_info(self.adb)
        asserts.assert_true(preferred is not None and info is not None, "the app's data directory can't be read")
        return preferred.get(self.address), info.get(self.address)

    def wait_observing(self, observing):
        """Since API 33 the app observes exactly its saved devices; waits until it does or doesn't observe the
        headset."""
        end = time.time() + OBSERVING_TIMEOUT
        while companion.is_observing(self.adb, app.PACKAGE, self.address) != observing:
            if time.time() >= end:
                asserts.fail(f"the headset is {'not ' if observing else ''}observed {OBSERVING_TIMEOUT:.0f} s later")
            time.sleep(1)

    def wait_in_front(self, package, timeout=10.0):
        """Waits until an activity of [package] is in front."""
        end = time.time() + timeout
        while not (activity := baseline.foreground_activity(self.adb) or "").startswith(package + "/"):
            if time.time() >= end:
                asserts.fail(f"{package} isn't in front within {timeout:.0f} s, {activity!r} is")
            time.sleep(0.5)

    def test_j10_change_a_saved_devices_preferred_codec_while_its_away(self):
        """The Saved devices list shows a saved device with its preferred codec, connected or not; View device
        opens its card. While it's away, the banner says when it was last connected, in English on any phone,
        and its chips change the preferred codec: the next connect applies the new one."""
        own = bt_oracle.active_codec(self.adb, self.address)
        chip, codec = ("SBC", SBC_44_16) if own.codec_type != bt_oracle.SBC else ("AAC", AAC_44_16)
        screen = self.main_screen()
        self.save_preferred(screen)
        saved = screen.saved()
        asserts.assert_true(saved.shows(*chips(own)), f"the saved card doesn't show {own}")
        asserts.assert_true(saved.connected_chip_shown(), "no Connected chip while the headset is connected")
        asserts.assert_true(saved.wait_banner(screens.SAVED_CONNECTED), "the banner doesn't say it's connected")
        screen = saved.tap_view()
        asserts.assert_true(screen.card().child(text=screens.EXPANDED_MARK).wait.exists(screens.TOGGLE_TIMEOUT),
                            "View device didn't expand the device's card")

        off = device_state.clock(self.adb)
        self.headset_off()
        self.check_last_connected(off, "the disconnect")
        saved = screen.saved()
        asserts.assert_true(saved.connected_chip_shown(False), "the Connected chip shows after the headset went away")
        asserts.assert_true(saved.wait_banner(screens.SAVED_NOT_CONNECTED), "the banner doesn't say it's not connected")
        text = saved.last_connected_text()
        asserts.assert_true(text is not None and LAST_CONNECTED_TODAY.fullmatch(text), f"the banner says {text!r}")

        mark = self.log.last_seq()
        saved.tap_chip(chip)
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_equal("saved devices", op.trigger.get("message"), str(op))
        preferred, _ = self.stored()
        asserts.assert_true(same_format(codec, preferred), f"the preferred codec is {preferred}")
        asserts.assert_true(saved.shows(*chips(codec)), f"the saved card doesn't show {codec}")

        mark = self.log.last_seq()
        self.headset_on()
        self.wait_for_applied(mark, codec)
        asserts.assert_true(saved.connected_chip_shown(), "no Connected chip after the headset came back")

    def test_j11_delete_a_saved_device_and_undo(self):
        """Deleting a saved device that isn't connected removes its preferred codec and its info, and since API 33
        ends observing it; Undo puts everything back. Without Undo the Saved list is empty."""
        screen = self.main_screen()
        self.save_preferred(screen)
        observing = self.sdk >= 33
        if observing:
            self.wait_observing(True)
        off = device_state.clock(self.adb)
        self.headset_off()
        # The disconnect changes the info, so it's noted before what's stored is kept for comparing
        self.check_last_connected(off, "the disconnect")
        before = self.stored()
        saved = screen.saved()

        mark = self.log.last_seq()
        # The snackbar only shows for 4 s, so the state in between is checked after the second delete
        saved.tap_delete()
        saved.undo()
        op = self.log.wait_for_operation(after=mark, trigger="USER_SAVE_PREFERRED", device=self.address, timeout=30)
        asserts.assert_equal("PREFERRED_SAVED", op.resolution, str(op))
        asserts.assert_equal("undo delete", op.trigger.get("message"), str(op))
        deleted = [e for e in self.log.events_after(mark) if e["type"] == "PREFERRED_DELETED"]
        asserts.assert_equal(1, len(deleted), "deletions before the undo")
        asserts.assert_true(deleted[0]["seq"] < op.trigger["seq"], "the undo came before the deletion")
        asserts.assert_equal(before, self.stored(), "what the undo put back")
        saved.wait_for_card()
        if observing:
            self.wait_observing(True)

        mark = self.log.last_seq()
        saved.tap_delete()
        self.log.wait_for_event("PREFERRED_DELETED", after=mark, timeout=10)
        saved.wait_for_empty_state(screens.NO_SAVED_DEVICES)
        asserts.assert_equal((None, None), self.stored(), "what's stored after deleting")
        if observing:
            self.wait_observing(False)
        asserts.assert_true(self.ad.ui(pkg=app.PACKAGE, text=screens.UNDO).wait.gone(screens.TIMEOUT),
                            "the snackbar is still shown")
        asserts.assert_equal((None, None), self.stored(), "what's stored once the snackbar is gone")

    def test_j12_turn_bluetooth_on_and_find_the_headset(self):
        """The main screen's empty states lead the way: with Bluetooth off the app turns it on through the
        system's dialog, without devices it opens the Bluetooth settings, and the headset's card shows once it
        connects."""
        self.require_capability(headset.POWER_SWITCH)
        self.headset.power_off()
        bt_oracle.wait_connected(self.adb, self.address, timeout=20, connected=False)
        device_state.set_bluetooth(self.adb, False)
        app.launch(self.adb)
        screen = screens.MainScreen(self.ad, self.headset)
        screen.wait_for_empty_state(screens.ASLEEP)

        screen.tap_text(screens.TURN_ON_BLUETOOTH)
        screen.accept_bluetooth_dialog()
        end = time.time() + BLUETOOTH_ON_TIMEOUT
        while not device_state.is_bluetooth_on(self.adb):
            if time.time() >= end:
                asserts.fail(f"Bluetooth isn't on {BLUETOOTH_ON_TIMEOUT:.0f} s after the dialog, "
                             f"it's {device_state.bluetooth_state(self.adb)}")
            time.sleep(0.5)
        screen.wait_for_empty_state(screens.NO_DEVICES, timeout=NO_DEVICES_TIMEOUT)

        settings = app.resolve_activity(self.adb, "android.settings.BLUETOOTH_SETTINGS")
        asserts.assert_is_not_none(settings, "nothing handles the Bluetooth settings")
        screen.tap_text(screens.OPEN_BLUETOOTH_SETTINGS)
        self.wait_in_front(settings)
        device_state.shell(self.adb, "input keyevent KEYCODE_BACK")
        self.wait_in_front(app.PACKAGE)
        screen.wait_for_empty_state(screens.NO_DEVICES)

        self.headset.power_on()
        screen.wait_for_card(timeout=HEADSET_ON_TIMEOUT)
        dumped = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_true(screen.shows(*chips(dumped)), f"the card doesn't show {dumped}")
