"""L3, the Bluetooth logic tests: the app's logic against the real stack, with the snippet loaded.

The snippet is loaded for each test after the baseline check and unloaded
before the restore, which resets the app's data and so ends its process.
"""

import time

from mobly import asserts
from mobly.snippet import errors as snippet_errors

from lib import app
from lib import app_log
from lib import baseline
from lib import bt_oracle
from lib import companion
from lib import device_state
from lib import headset
from lib.bt_oracle import Codec
from lib.bt_oracle import same_format
from lib.device_test import BaselineTest
from lib.device_test import recorded

SNIPPET = "snippet"
SBC_44_16 = Codec(bt_oracle.SBC, 0x1, 0x1)
LDAC_96_32_ADAPTIVE = Codec(bt_oracle.LDAC, 0x8, 0x4, codec_specific1=13)
LDAC_44_24_909 = Codec(bt_oracle.LDAC, 0x1, 0x2, codec_specific1=10)
RATE_ORDER = (0x1, 0x2, 0x4, 0x8)
BITS_ORDER = (0x1, 0x2, 0x4)

# Since API 33 B9 can see observing in dumpsys. Whether clearing the app's data keeps its link is
# recorded per range of API levels on the testbed (6.7), see device_test.recorded().
B9_LINK_KEPT_AFTER_CLEAR = {(36, None): False}
# B7: how a request can end when the phone's Bluetooth turns off during it, depending on whether
# the profile or the device's connection goes first. On the S24 either does.
B7_OFF_DURING_REQUEST = ("PROFILE_DISCONNECTED", "NOT_CONNECTED")


class SnippetTest(BaselineTest):
    def setup_test(self):
        super().setup_test()
        self.ad.load_snippet(SNIPPET, app.SNIPPET_PACKAGE)
        self.snippet = getattr(self.ad, SNIPPET)

    def teardown_test(self):
        try:
            # A test may have unloaded it already
            if getattr(self.ad, SNIPPET, None) is not None:
                self.ad.unload_snippet(SNIPPET)
        except Exception:
            self.ad.log.exception("unloading the snippet failed")
        super().teardown_test()

    @property
    def address(self):
        return self.headset.address

    def apply(self, codec):
        """Applies [codec] through the app's CodecApplier, returns the resolved operation."""
        result = self.snippet.applyCodec(self.address, codec.to_app())
        asserts.assert_is_not_none(result["resolution"], f"applying {codec} didn't resolve")
        return self.operation(result["operation"])

    def operation(self, uid, timeout=5.0):
        """The resolved operation [uid]; the app writes its log on a background thread."""
        end = time.time() + timeout
        while True:
            op = next((op for op in app_log.operations(self.log.read()) if op.uid == uid), None)
            if op is not None and op.resolution:
                return op
            if time.time() >= end:
                raise AssertionError(f"operation {uid} isn't resolved in the log: {op}")
            time.sleep(0.2)

    def dumped_codec(self):
        codec = bt_oracle.active_codec(self.adb, self.address)
        asserts.assert_is_not_none(codec, "A2DP isn't connected")
        return codec

    def capabilities(self):
        return bt_oracle.device(self.adb, self.address).capabilities

    def other_codec(self, *besides):
        """A codec to change to, other than the current one and [besides]."""
        current = self.dumped_codec()
        candidates = [bt_oracle.other_codec(current, self.capabilities())] + [
            Codec(c.codec_type, next(r for r in RATE_ORDER if c.sample_rates & r),
                  next(b for b in BITS_ORDER if c.bits_per_sample & b))
            for c in self.capabilities() if c.codec_type in bt_oracle.CODEC_NAMES]
        for codec in candidates:
            if not same_format(codec, current) and not any(same_format(codec, b) for b in besides):
                return codec
        raise AssertionError(f"the device offers no other codec than {current} and {besides}")

    def wait_until_applying(self, after, timeout=60.0):
        """Waits for an operation after seq [after], not the snippet's, that has set a codec and is
        still open, and returns it."""
        end = time.time() + timeout
        while True:
            ops = app_log.operations(self.log.events_after(after))
            for op in ops:
                if op.trigger.get("message") != "snippet" and op.of_type("SET_CODEC_CONFIG") and not op.resolution:
                    return op
            if time.time() >= end:
                asserts.fail(f"no operation applied a codec within {timeout:.0f} s: {[str(op) for op in ops]}")
            time.sleep(0.2)


class BtLogicTest(SnippetTest):
    def test_b1_read_contract(self):
        """The codec and capabilities the app reads are what dumpsys shows."""
        read = Codec.from_app(self.snippet.getCurrentCodec(self.address))
        dumped = self.dumped_codec()
        asserts.assert_true(read.same_config(dumped), f"the app reads {read}, dumpsys shows {dumped}")
        app_caps = {(c["codecType"], c["sampleRates"], c["bitsPerSample"]) for c in self.snippet.getCapabilities(self.address)}
        dumped_caps = {(c.codec_type, c.sample_rates, c.bits_per_sample) for c in self.capabilities()}
        asserts.assert_equal(dumped_caps, app_caps, "capabilities")
        asserts.assert_true(any(c.codec_type == dumped.codec_type and c.supports(dumped.sample_rate, dumped.bits_per_sample)
                                for c in self.capabilities()), f"the active codec {dumped} isn't offered")

    def test_b2_apply_every_codec_type(self):
        """Every codec type the phone and headset offer is applied and verified, and dumpsys agrees."""
        current = self.dumped_codec()
        failures = []
        for capability in self.capabilities():
            rate = current.sample_rate if capability.sample_rates & current.sample_rate else \
                next(r for r in RATE_ORDER if capability.sample_rates & r)
            bits = current.bits_per_sample if capability.bits_per_sample & current.bits_per_sample else \
                next(b for b in BITS_ORDER if capability.bits_per_sample & b)
            target = Codec(capability.codec_type, rate, bits)
            op = self.apply(target)
            dumped = self.dumped_codec()
            self.ad.log.info("%s: %s after %s attempts, dumpsys %s", target, op.resolution,
                             (op.resolution_event or {}).get("attempt"), dumped)
            if op.resolution != "CODEC_VERIFIED" or not same_format(target, dumped):
                failures.append(f"{target}: {op.resolution}, dumpsys {dumped}")
            current = dumped
        asserts.assert_equal([], failures, "codec types that weren't applied")

    def test_b3_multi_step_change_with_the_main_screen_open(self):
        """LDAC 96 kHz, 32-bit, adaptive to 44.1 kHz, 24-bit, 909 kbps within 5 attempts (manual 1.3),
        and the main screen logs none of the codecs on the way as external changes."""
        ldac = next((c for c in self.capabilities() if c.codec_type == bt_oracle.LDAC), None)
        self.require(ldac and ldac.supports(0x8, 0x4) and ldac.supports(0x1, 0x2), "LDAC 96/32 and 44.1/24 aren't offered")
        app.launch(self.adb)
        # The first start in a new process logs the current codec
        self.log.wait_for_event("ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE", timeout=10)
        self.snippet.setCodecDirectly(self.address, LDAC_96_32_ADAPTIVE.to_app())
        bt_oracle.wait_for(self.adb, lambda s: same_format(LDAC_96_32_ADAPTIVE, s.device(self.address).codec), 10,
                           what="LDAC 96/32")
        # The main screen polls every second and logs the direct change
        time.sleep(3)
        mark = self.log.last_seq()
        op = self.apply(LDAC_44_24_909)
        attempts = op.resolution_event.get("attempt")
        self.ad.log.info("multi-step change: %s after %s attempts", op.resolution, attempts)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        asserts.assert_true(same_format(LDAC_44_24_909, self.dumped_codec()), f"dumpsys shows {self.dumped_codec()}")
        time.sleep(3)
        external = [e for e in self.log.events_after(mark) if e["type"] == "ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE"]
        asserts.assert_equal([], external, "the app's own changes were logged as external")

    def test_b8_links(self):
        """Without a link: up to API 33 everything works, on 34-35 changing needs it, on 36 reading too."""
        companion.unlink(self.adb, app.PACKAGE, self.address)
        if self.sdk >= 36:
            with asserts.assert_raises(snippet_errors.ApiError):
                self.snippet.getCurrentCodec(self.address)
        else:
            asserts.assert_is_not_none(self.snippet.getCurrentCodec(self.address), "the codec isn't readable")
        op = self.apply(SBC_44_16)
        expected = "NO_ASSOCIATION" if self.sdk >= 34 else "CODEC_VERIFIED"
        asserts.assert_equal(expected, op.resolution, str(op))
        if self.sdk >= 36:
            app.launch(self.adb)
            time.sleep(4)
            read_failed = [e for e in self.log.read() if e["type"] == "READ_FAILED"]
            asserts.assert_equal(1, len(read_failed), "READ_FAILED is logged once while the screen polls")
            app.go_home(self.adb)
        companion.link(self.adb, app.PACKAGE, self.address)
        op = self.apply(SBC_44_16)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, f"with a link: {op}")

    def test_b2_apply_every_rate_size_and_ldac_bitrate(self):
        """Every rate and size of every codec the phone and headset offer, and every LDAC bitrate."""
        failures = []
        for capability in self.capabilities():
            if capability.codec_type not in bt_oracle.CODEC_NAMES:
                continue
            targets = [Codec(capability.codec_type, rate, bits) for rate in RATE_ORDER for bits in BITS_ORDER
                       if capability.supports(rate, bits)]
            if capability.codec_type == bt_oracle.LDAC:
                rate, bits = targets[-1].sample_rate, targets[-1].bits_per_sample
                targets += [Codec(bt_oracle.LDAC, rate, bits, codec_specific1=10 + bitrate) for bitrate in range(4)]
            for target in targets:
                op = self.apply(target)
                dumped = self.dumped_codec()
                self.ad.log.info("%s: %s after %s attempts, dumpsys %s", target, op.resolution,
                                 (op.resolution_event or {}).get("attempt"), dumped)
                applied = same_format(target, dumped)
                # The stack reports the LDAC bitrate it uses as 1000 to 1003
                if target.codec_specific1:
                    applied = applied and dumped.codec_specific1 % 10 == target.codec_specific1 % 10
                if op.resolution != "CODEC_VERIFIED" or not applied:
                    failures.append(f"{target}: {op.resolution}, dumpsys {dumped}")
        asserts.assert_equal([], failures, "configurations that weren't applied")

    def test_b4_a_codec_outside_the_capabilities(self):
        """A codec the headset doesn't offer resolves MAX_ATTEMPTS after 5 attempts, the codec stays as it was."""
        before = self.dumped_codec()
        target = bt_oracle.codec_outside(self.capabilities())
        op = self.apply(target)
        asserts.assert_equal("MAX_ATTEMPTS", op.resolution, str(op))
        asserts.assert_equal(5, op.resolution_event.get("attempt"), str(op))
        after = self.dumped_codec()
        asserts.assert_true(same_format(before, after), f"applying {target} changed {before} to {after}")

    def test_b5_already_pending_and_superseded(self):
        """The same codec again while it's applied is ALREADY_PENDING, another one supersedes it (manual 1.4, 1.5)."""
        first = self.other_codec()
        second = self.other_codec(first)
        uid = self.snippet.startApplyCodec(self.address, first.to_app())
        again = self.apply(first)
        asserts.assert_equal("ALREADY_PENDING", again.resolution, str(again))
        asserts.assert_equal(f"pending in operation {uid}", again.resolution_event.get("message"), str(again))
        newer = self.snippet.startApplyCodec(self.address, second.to_app())
        # Replaced in one step, the device never shows as idle
        asserts.assert_true(self.address in [a.upper() for a in self.snippet.getPending()], "the device isn't pending")
        superseded = self.operation(uid)
        asserts.assert_equal("SUPERSEDED", superseded.resolution,
                             f"{superseded}, was it verified before the second request?")
        asserts.assert_equal(f"superseded by operation {newer}", superseded.resolution_event.get("message"))
        op = self.operation(newer, timeout=20)
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        asserts.assert_true(same_format(second, self.dumped_codec()), f"dumpsys shows {self.dumped_codec()}")

    def test_b6_a_disconnected_device(self):
        """A device without A2DP resolves NOT_CONNECTED."""
        self.require_capability(headset.A2DP_SWITCH)
        self.headset.disconnect_a2dp()
        bt_oracle.wait_connected(self.adb, self.address, timeout=30, connected=False)
        op = self.apply(SBC_44_16)
        asserts.assert_equal("NOT_CONNECTED", op.resolution, str(op))

    def test_b7_bluetooth_off_and_on(self):
        """Bluetooth off: PROFILE_UNAVAILABLE; off during a request: PROFILE_DISCONNECTED or
        NOT_CONNECTED; back on the next request opens a new proxy and verifies (manual 2.3)."""
        first = self.other_codec()
        # The applier's proxy is open when Bluetooth turns off
        asserts.assert_equal("CODEC_VERIFIED", self.apply(first).resolution)
        device_state.set_bluetooth(self.adb, False)
        op = self.apply(first)
        asserts.assert_equal("PROFILE_UNAVAILABLE", op.resolution, str(op))
        baseline.connect(self.adb, self.headset)
        # A codec the headset doesn't offer keeps the request open for 5 attempts
        uid = self.snippet.startApplyCodec(self.address, bt_oracle.codec_outside(self.capabilities()).to_app())
        time.sleep(1)
        device_state.set_bluetooth(self.adb, False)
        op = self.operation(uid, timeout=20)
        self.ad.log.info("B7 off during a request: %s", op)
        asserts.assert_true(op.resolution in B7_OFF_DURING_REQUEST, str(op))
        baseline.connect(self.adb, self.headset)
        op = self.apply(self.other_codec())
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, f"after Bluetooth came back: {op}")

    def test_b9_observing(self):
        """Observing: on with a preferred codec, off when it's deleted, and after clearing the app's
        data the next start turns it off. The preferences file holds the preferred codec while it's
        saved. Whether the link stays is recorded (6.7)."""
        self.require(self.sdk >= 33, "observing only shows in dumpsys since API 33")

        def wait_observing(expected, what):
            end = time.time() + 10
            # Without an association there's nothing to observe, and dumpsys doesn't say
            while bool(companion.is_observing(self.adb, app.PACKAGE, self.address)) != expected:
                if time.time() >= end:
                    asserts.fail(f"{what}: observing isn't {expected}")
                time.sleep(0.5)

        def wait_stored(expected, what):
            """Waits until the file holds [expected] for the headset, None for no preferred codec."""
            end = time.time() + 5
            # SharedPreferences writes the file on a background thread
            while True:
                stored = baseline.preferred_codecs(self.adb)
                asserts.assert_is_not_none(stored, f"{what}: the preferences are unreadable")
                codec = stored.get(self.address.upper())
                if codec == expected:
                    return
                if time.time() >= end:
                    asserts.fail(f"{what}: the file holds {codec}, not {expected}")
                time.sleep(0.2)

        self.snippet.setPreferredCodec(self.address, SBC_44_16.to_app())
        wait_stored(SBC_44_16, "with a preferred codec")
        wait_observing(True, "with a preferred codec")
        self.snippet.deletePreferredCodec(self.address)
        wait_stored(None, "after deleting it")
        wait_observing(False, "after deleting it")
        self.snippet.setPreferredCodec(self.address, SBC_44_16.to_app())
        wait_stored(SBC_44_16, "with a preferred codec again")
        wait_observing(True, "with a preferred codec again")
        self.ad.unload_snippet(SNIPPET)
        app.clear_data(self.adb)
        wait_stored(None, "after clearing the data")
        linked = companion.is_linked(self.adb, app.PACKAGE, self.address)
        observing = linked and companion.is_observing(self.adb, app.PACKAGE, self.address)
        self.ad.log.info("B9 after clearing the data on API %d: linked %s, observing %s", self.sdk, linked, observing)
        app.grant_permission(self.adb)
        app.launch(self.adb)
        wait_observing(False, "after the next start")
        app.go_home(self.adb)
        expected = recorded(B9_LINK_KEPT_AFTER_CLEAR, self.sdk)
        if expected is None:
            asserts.explicit_pass(f"recorded: the link is {'kept' if linked else 'removed'}; "
                                  f"add it to B9_LINK_KEPT_AFTER_CLEAR for API {self.sdk}")
        asserts.assert_equal(expected, linked, "the link after clearing the app's data")

    def test_b11_an_external_change_is_logged_once(self):
        """A codec set bypassing the applier, with the main screen open, is logged once as an external
        change; the app's own changes never are (manual 2.2)."""
        app.launch(self.adb)
        # The first start in a new process logs the current codec
        self.log.wait_for_event("ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE", timeout=10)
        time.sleep(2)
        external = self.other_codec()
        mark = self.log.last_seq()
        self.snippet.setCodecDirectly(self.address, external.to_app())
        bt_oracle.wait_for(self.adb, lambda s: same_format(external, s.device(self.address).codec), 10,
                           what=str(external))
        # The main screen polls every second
        time.sleep(3)
        logged = [e for e in self.log.events_after(mark) if e["type"] == "ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE"]
        asserts.assert_equal(1, len(logged), f"external changes logged: {logged}")
        asserts.assert_equal(external.codec_type, logged[0]["actual"]["android"]["codecType"], str(logged[0]))
        mark = self.log.last_seq()
        op = self.apply(self.other_codec(external))
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        time.sleep(3)
        logged = [e for e in self.log.events_after(mark) if e["type"] == "ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE"]
        asserts.assert_equal([], logged, "the app's own change was logged as external")
        app.go_home(self.adb)

    def test_b12_a_request_supersedes_the_preferred_codec_on_connect(self):
        """A request while the service or receiver applies the preferred codec on connect supersedes
        it, and the requested codec wins."""
        # A preferred codec the headset doesn't offer keeps the background operation open for 5 attempts
        self.snippet.setPreferredCodec(self.address, bt_oracle.codec_outside(self.capabilities()).to_app())
        mark = self.log.last_seq()
        self.headset.reconnect(self.adb)
        background = self.wait_until_applying(mark)
        requested = self.other_codec()
        op = self.apply(requested)
        background = self.operation(background.uid)
        self.ad.log.info("B12: %s; %s", background, op)
        asserts.assert_equal("SUPERSEDED", background.resolution, str(background))
        asserts.assert_equal("CODEC_VERIFIED", op.resolution, str(op))
        asserts.assert_true(same_format(requested, self.dumped_codec()), f"dumpsys shows {self.dumped_codec()}")
