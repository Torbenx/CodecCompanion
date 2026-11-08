"""L5, the journeys: through the UI with snippet-uiautomator, which runs in its own process.

They check that the parts work together, including the system's dialog that
links a device, not every detail on the way; the UI tests cover those.
"""

import re
import time

from mobly import asserts

from lib import app
from lib import app_log
from lib import bt_oracle
from lib import companion
from lib import device_state
from lib import screens
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
SBC_TEXTS = ("SBC", "44100 Hz", "16-bit")
APPLY_TIMEOUT = 60.0
EXPORTS = "cache/exports"
MAC = re.compile(r"([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")


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
