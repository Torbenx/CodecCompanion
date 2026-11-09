"""Self-tests of the harness, against outputs recorded on the phones. No phone needed:

  cd mobly_tests
  python -m unittest tests.harness_test
"""

import json
import logging
import pathlib
import re
import tempfile
import unittest

from mobly import config_parser
from mobly import records
from mobly import signals

import run_suite
from lib import app, app_log, baseline, bt_oracle, companion, device_state, device_test, hibernation, lock_screen, sim_helper

MOBLY_TESTS = pathlib.Path(__file__).resolve().parents[1]
TESTDATA = MOBLY_TESTS / "testdata"
SAMPLE_LOG = pathlib.Path(__file__).resolve().parents[2] / "app/src/sharedTest/resources/sample_event_log.jsonl"
QUDELIX = "0A:1B:2C:3D:4E:5F"


def read(*parts):
    return TESTDATA.joinpath(*parts).read_text(encoding="utf-8")


class BtOracleTest(unittest.TestCase):
    def check_connected(self, name, codec, capabilities):
        state = bt_oracle.parse(read("bluetooth_manager", name))
        self.assertEqual("ON", state.adapter_state)
        self.assertTrue(state.is_active(QUDELIX))
        device = state.device(QUDELIX)
        self.assertIsNotNone(device)
        self.assertTrue(device.connected)
        self.assertTrue(codec.same_config(device.codec), device.codec)
        self.assertEqual(capabilities, [c.codec_type for c in device.capabilities])
        self.assertIsNone(state.device("AA:BB:CC:DD:EE:FF"))
        return device

    def test_api28(self):
        device = self.check_connected("api28_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4),
                                      [bt_oracle.LDAC, bt_oracle.APTX_HD, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertTrue(device.capabilities[0].supports(0x8, 0x4))

    def test_api30Samsung_matchesShortAddresses(self):
        # Samsung turned LDAC off when it connected, the phone's own choice is aptX
        device = self.check_connected("api30_samsung_connected.txt", bt_oracle.Codec(bt_oracle.APTX, 0x1, 0x1),
                                      [bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC, bt_oracle.LDAC])
        self.assertEqual("XX:XX:XX:3D:4X:XX", device.address)
        self.assertTrue(device.capabilities[3].supports(0x8, 0x4))

    def test_api32(self):
        device = self.check_connected("api32_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4),
                                      [bt_oracle.LDAC, bt_oracle.APTX_HD, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertEqual(5001, device.codec.priority)
        self.assertFalse(device.capabilities[1].supports(0x8, 0x2))

    def test_api36_matchesMaskedAddresses(self):
        device = self.check_connected("api36_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x1, 0x2, codec_specific1=10),
                                      [bt_oracle.LDAC, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertEqual("XX:XX:XX:XX:4E:5F", device.address)
        # Set by the app, with the highest priority
        self.assertEqual(1000000, device.codec.priority)

    def test_api37_hasTheNewStatusHeader(self):
        device = self.check_connected("api37_connected.txt", bt_oracle.Codec(bt_oracle.LDAC, 0x1, 0x2, codec_specific1=10),
                                      [bt_oracle.LDAC, bt_oracle.APTX, bt_oracle.AAC, bt_oracle.SBC])
        self.assertEqual("XX:XX:XX:XX:4E:5F", device.address)

    def test_adapterState(self):
        self.assertEqual("ON", device_state.parse_bluetooth_state("  state: ON"))
        self.assertEqual("BLE_ON", device_state.parse_bluetooth_state("  State:         BLE_ON"))
        # Only the adapter's line, not the state machines'
        self.assertIsNone(device_state.parse_bluetooth_state("  State Machine State: INIT\n"))
        self.assertIsNone(device_state.parse_bluetooth_state(""))

    def test_broadcastResult(self):
        self.assertEqual(1, device_state.parse_broadcast_result(
            "Broadcasting: Intent { flg=0x400000 cmp=com.tt.autobluetoothcodec.snippet/.BluetoothSwitch (has extras) }\n"
            "Broadcast completed: result=1"))
        # What nothing receiving it looks like
        self.assertEqual(0, device_state.parse_broadcast_result(
            "Broadcasting: Intent { flg=0x400000 cmp=com.tt.autobluetoothcodec.snippet/.BluetoothSwitch (has extras) }\n"
            "Broadcast completed: result=0"))
        self.assertEqual(2, device_state.parse_broadcast_result("Broadcast completed: result=2, data=\"no Bluetooth adapter\""))
        self.assertIsNone(device_state.parse_broadcast_result("Security exception: Permission Denial: ..."))

    def test_wakefulness(self):
        self.assertEqual("Awake", device_state.parse_wakefulness("  mWakefulness=Awake"))
        # The Galaxy A50 on Android 11
        self.assertEqual("Asleep", device_state.parse_wakefulness("  getWakefulnessLocked()=Asleep\n"))
        self.assertIsNone(device_state.parse_wakefulness("  mWakefulnessChanging=false"))
        self.assertIsNone(device_state.parse_wakefulness(""))

    def test_sameDevice(self):
        self.assertTrue(bt_oracle.same_device("XX:XX:XX:XX:4E:5F", "0a:1b:2c:3d:4e:5f"))
        self.assertFalse(bt_oracle.same_device("XX:XX:XX:XX:4E:5E", QUDELIX))
        self.assertTrue(bt_oracle.same_device(QUDELIX, QUDELIX.lower()))

    def test_disconnectedDump(self):
        dump = read("bluetooth_manager", "api32_connected.txt").replace("mConnectionState: CONNECTED", "mConnectionState: DISCONNECTED")
        self.assertFalse(bt_oracle.parse(dump).device(QUDELIX).connected)
        self.assertEqual((), bt_oracle.parse("Bluetooth Status\n  state: OFF\n").devices)

    def test_codecJsonRoundTrips(self):
        codec = bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x2, codec_specific1=13)
        self.assertEqual(codec, bt_oracle.Codec.from_app(codec.to_app()))

    def test_codecsToChangeTo(self):
        capabilities = bt_oracle.parse(read("bluetooth_manager", "api36_connected.txt")).device(QUDELIX).capabilities
        outside = bt_oracle.codec_outside(capabilities)
        self.assertEqual(bt_oracle.OPUS, outside.codec_type)
        self.assertFalse(any(c.codec_type == outside.codec_type for c in capabilities))
        only_sbc = (bt_oracle.Capability(bt_oracle.SBC, 0x3, 0x1),)
        self.assertEqual(bt_oracle.Codec(bt_oracle.OPUS, 0x2, 0x1), bt_oracle.codec_outside(only_sbc))
        ldac = bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4)
        self.assertEqual(bt_oracle.Codec(bt_oracle.SBC, 0x1, 0x1), bt_oracle.other_codec(ldac))
        self.assertEqual(bt_oracle.LDAC, bt_oracle.other_codec(bt_oracle.Codec(bt_oracle.SBC, 0x1, 0x1), capabilities).codec_type)
        self.assertTrue(bt_oracle.same_format(ldac, bt_oracle.Codec(bt_oracle.LDAC, 0x8, 0x4, codec_specific1=1003)))
        self.assertFalse(bt_oracle.same_format(ldac, None))


class BaselineTest(unittest.TestCase):
    def test_elementBoundsIgnoresDirectionMarks(self):
        xml = ('<hierarchy><node text="Gekoppelte Ger&#228;te" bounds="[0,100][1080,160]" />'
               '<node text="\u200eQudelix-5K\u200e" bounds="[160,620][700,680]" />'
               '<node text="R&amp;D Buds" bounds="[160,700][700,760]" /></hierarchy>')
        self.assertEqual((430, 650), baseline.element_bounds(xml, "Qudelix-5K"))
        self.assertEqual((430, 730), baseline.element_bounds(xml, "R&D Buds"))
        self.assertIsNone(baseline.element_bounds(xml, "Qudelix"))

    def test_preferencesAreUnescaped(self):
        # In the format SharedPreferences writes, which escapes quotes and ampersands
        xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               f'    <string name="{QUDELIX}">{{&quot;name&quot;:&quot;R&amp;D Buds&quot;,&quot;lastConnected&quot;:'
               '1762560000123,&quot;capabilities&quot;:[{&quot;codecType&quot;:0,&quot;sampleRateMask&quot;:3,'
               '&quot;bitsPerSampleMask&quot;:1}]}</string>\n'
               '    <boolean name="enabled" value="true" />\n</map>\n')
        values = baseline.parse_preferences(xml)
        self.assertEqual([QUDELIX], list(values))
        info = json.loads(values[QUDELIX])
        self.assertEqual("R&D Buds", info["name"])
        self.assertEqual(1762560000123, info["lastConnected"])
        self.assertEqual([{"codecType": 0, "sampleRateMask": 3, "bitsPerSampleMask": 1}], info["capabilities"])

    def test_noPreferencesFile(self):
        self.assertEqual({}, baseline.parse_preferences(""))


class CompanionTest(unittest.TestCase):
    def test_listUpTo35(self):
        for name in ("api28_list.txt", "api32_list.txt"):
            self.assertEqual([companion.Association(app.PACKAGE, QUDELIX)],
                             companion.parse_list(read("companiondevice", name)))

    def test_listOn36HasIds(self):
        self.assertEqual([companion.Association(app.PACKAGE, QUDELIX, 7)],
                         companion.parse_list(read("companiondevice", "api36_list.txt")))

    def test_listSince37IsTheAssociations(self):
        self.assertEqual([companion.Association(app.PACKAGE, QUDELIX, 7), companion.Association("com.example.other", QUDELIX, 5)],
                         companion.parse_list(read("companiondevice", "api37_list.txt")))
        # Removed associations have no address
        self.assertEqual([], companion.parse_list("Association{mId=6, mUserId=0, mPackageName='p', mDeviceMacAddress=null, mDisplayName='x'}"))

    def test_observingAndPresence(self):
        dump = read("companiondevice", "api36_dumpsys.txt")
        self.assertTrue(companion.parse_observing(dump, app.PACKAGE, QUDELIX))
        self.assertIsNone(companion.parse_observing(dump, "other.package", QUDELIX))
        self.assertTrue(companion.parse_present(dump, QUDELIX))
        # API 32 shows neither
        dump32 = read("companiondevice", "api32_dumpsys.txt")
        self.assertIsNone(companion.parse_observing(dump32, app.PACKAGE, QUDELIX))
        self.assertIsNone(companion.parse_present(dump32, QUDELIX))


class LockScreenTest(unittest.TestCase):
    def test_credential(self):
        self.assertFalse(lock_screen.parse_has_credential(read("lock_settings", "api28_none.txt")))
        self.assertFalse(lock_screen.parse_has_credential(read("lock_settings", "api32_none.txt")))
        self.assertTrue(lock_screen.parse_has_credential(read("lock_settings", "api36_pin.txt")))
        self.assertTrue(lock_screen.parse_has_credential(read("lock_settings", "api28_none.txt").replace("SID = 0", "SID = 5f3e")))
        with self.assertRaises(RuntimeError):
            lock_screen.parse_has_credential("Current lock settings service state:\n")

    def test_userState(self):
        self.assertEqual(lock_screen.RUNNING_LOCKED, lock_screen.parse_user_state("RUNNING_LOCKED\n"))
        self.assertEqual(lock_screen.RUNNING_UNLOCKED, lock_screen.parse_user_state("RUNNING_UNLOCKED"))
        with self.assertRaises(RuntimeError):
            lock_screen.parse_user_state("User is not started: 0")

    def test_lockClass(self):
        self.assertEqual(lock_screen.PURE_TEST_DEVICE, lock_screen.lock_class({"serial": "1", "pure_test_device": True}))
        self.assertEqual(lock_screen.KNOWN_PASSWORD, lock_screen.lock_class({"serial": "1", "lock_password": "1234"}))
        self.assertEqual(lock_screen.UNKNOWN_PASSWORD, lock_screen.lock_class({"serial": "1"}))
        self.assertEqual(lock_screen.UNKNOWN_PASSWORD, lock_screen.lock_class({"serial": "1", "pure_test_device": False}))

    def test_onlyThePin0000IsEverSetOrCleared(self):
        """D24: no command of the suite sets or clears a credential, except the two hardcoded ones."""
        changing = re.compile(r"(locksettings|cmd lock_settings)\s+(set-|clear)")
        found = []
        for path in MOBLY_TESTS.rglob("*.py"):
            relative = path.relative_to(MOBLY_TESTS)
            if relative.parts[0] in (".venv", "results") or path == pathlib.Path(__file__).resolve():
                continue
            found += [(relative.as_posix(), line.strip()) for line in path.read_text(encoding="utf-8").splitlines()
                      if changing.search(line)]
        self.assertEqual([("lib/lock_screen.py", '_SET_PIN = "locksettings set-pin 0000"'),
                          ("lib/lock_screen.py", '_CLEAR_PIN = "locksettings clear --old 0000"')], sorted(found, reverse=True))

    def test_pinOnlyOnPureTestDevices(self):
        for phone_class in (lock_screen.KNOWN_PASSWORD, lock_screen.UNKNOWN_PASSWORD):
            with self.assertRaises(RuntimeError):
                lock_screen.set_pin(None, phone_class)
            with self.assertRaises(RuntimeError):
                lock_screen.clear_pin(None, phone_class)

    def test_passwordStaysOutOfTheMoblyConfig(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            testbed = directory / "testbed.yaml"
            testbed.write_text('phones:\n  - serial: R1\n    lock_password: "0123"\n  - serial: R2\n    lock_password: 0123\n',
                               encoding="utf-8")
            path = run_suite.mobly_config(directory, "R1", {"serial": "R1", "lock_password": "0123"},
                                          "qudelix", {"backend": "qudelix"}, testbed)
            # The tests get the original testbed.yaml to read the password from
            self.assertEqual(str(testbed), config_parser.load_test_config_file(str(path))[0].user_params["testbed"])
            config = path.read_text(encoding="utf-8")
            self.assertNotIn("0123", config)
            self.assertNotIn("lock_password", config)
            self.assertIn(f"lock_class: {lock_screen.KNOWN_PASSWORD}", config)
            self.assertEqual("0123", lock_screen.password(testbed, "R1"))
            # YAML reads it as the octal number 83
            with self.assertRaises(ValueError):
                lock_screen.password(testbed, "R2")


class SimHelperTest(unittest.TestCase):
    def test_simStates(self):
        self.assertEqual(["LOADED", "ABSENT"], sim_helper.parse_sim_states("LOADED,ABSENT\n"))
        self.assertEqual(["ABSENT"], sim_helper.parse_sim_states("ABSENT"))
        # The API 28 Pixel, whose radio doesn't run
        self.assertEqual([], sim_helper.parse_sim_states(""))
        self.assertEqual(["PIN_REQUIRED", ""], sim_helper.parse_sim_states("PIN_REQUIRED,"))

    def test_prepareResult(self):
        self.assertEqual(0, sim_helper.parse_prepare_result("0\n"))
        self.assertEqual(1, sim_helper.parse_prepare_result("WARNING: linker: something\n1"))
        for output in ("", "Aborted"):
            with self.assertRaises(RuntimeError):
                sim_helper.parse_prepare_result(output)


class AppLogTest(unittest.TestCase):
    def setUp(self):
        self.events = app_log.parse(SAMPLE_LOG.read_text(encoding="utf-8"))

    def test_sampleLogHasOneOpenOperation(self):
        self.assertEqual([], app_log.check_structure(self.events, allow_open=1))
        self.assertEqual(1, len(app_log.check_structure(self.events)))

    def test_brokenStructure(self):
        broken = [e for e in self.events if e["type"] != "A2DP_CONNECTED"]
        self.assertTrue(any("breaks the operation structure" in p for p in app_log.check_structure(broken, allow_open=99)))
        reordered = self.events[1:2] + self.events[0:1]
        self.assertTrue(any("seq" in p for p in app_log.check_structure(reordered, allow_open=99)))

    def test_operations(self):
        ops = app_log.operations(self.events)
        first = ops[0]
        self.assertEqual("A2DP_CONNECTED", first.trigger["type"])
        self.assertEqual("CODEC_VERIFIED", first.resolution)
        self.assertEqual(2, len(first.of_type("SET_CODEC_CONFIG")))
        self.assertIsNone(ops[-1].resolution)

    def test_partialLastLine(self):
        self.assertEqual(1, len(app_log.parse('{"v":1,"seq":0,"time":0,"type":"TEST_LABEL"}\n{"v":1,"se')))


class InstrumentationTest(unittest.TestCase):
    def test_results(self):
        self.assertEqual((38, 0, []), app.parse_instrumentation("INSTRUMENTATION_RESULT: stream=\n\nOK (38 tests)\n"))
        output = ("INSTRUMENTATION_STATUS: class=com.tt.autobluetoothcodec.MainScreenTest\n"
                  "INSTRUMENTATION_STATUS: test=noCodecInformation\n"
                  "INSTRUMENTATION_STATUS_CODE: -2\n"
                  "Tests run: 38,  Failures: 1\n")
        self.assertEqual((38, 1, ["com.tt.autobluetoothcodec.MainScreenTest#noCodecInformation"]),
                         app.parse_instrumentation(output))


class AutoRevokeTest(unittest.TestCase):
    """`appops get com.tt.autobluetoothcodec AUTO_REVOKE_PERMISSIONS_IF_UNUSED`, on API 32 unless noted."""

    def test_linked(self):
        self.assertEqual("ignore", app.parse_auto_revoke_mode("AUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\n"))

    def test_theUsersChoiceWins(self):
        allow = "Uid mode: AUTO_REVOKE_PERMISSIONS_IF_UNUSED: allow\nAUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\n"
        self.assertEqual("allow", app.parse_auto_revoke_mode(allow))
        ignore = "Uid mode: AUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\nAUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\n"
        self.assertEqual("ignore", app.parse_auto_revoke_mode(ignore))

    def test_neverSet(self):
        self.assertEqual("default", app.parse_auto_revoke_mode("No operations.\nDefault mode: default\n"))

    def test_api28HasNoSuchOp(self):
        self.assertIsNone(app.parse_auto_revoke_mode("Error: Unknown operation string: AUTO_REVOKE_PERMISSIONS_IF_UNUSED\n"))
        self.assertIsNone(app.parse_auto_revoke_modes("Error: Unknown operation string: AUTO_REVOKE_PERMISSIONS_IF_UNUSED\n"))

    def test_bothModes(self):
        allow = "Uid mode: AUTO_REVOKE_PERMISSIONS_IF_UNUSED: allow\nAUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\n"
        self.assertEqual(("allow", "ignore"), app.parse_auto_revoke_modes(allow))
        self.assertEqual((None, "ignore"), app.parse_auto_revoke_modes("AUTO_REVOKE_PERMISSIONS_IF_UNUSED: ignore\n"))
        self.assertEqual((None, "default"), app.parse_auto_revoke_modes("No operations.\nDefault mode: default\n"))


class HibernationTest(unittest.TestCase):
    """hibernation.py's parsers, against outputs of state_command() and dumpsys jobscheduler."""

    def test_baselineOnApi32(self):
        state = hibernation.parse_state(read("hibernation", "state_api32.txt"))
        self.assertEqual(hibernation.UnusedAppState(None, "ignore", 7776000000, None, False, False), state)
        self.assertEqual([], baseline.unused_app_problems(state))

    def test_api30HasNoHibernationAndStoresNoThreshold(self):
        state = hibernation.parse_state(read("hibernation", "state_api30.txt"))
        self.assertEqual(hibernation.UnusedAppState(None, "ignore", None, None, None, None), state)
        self.assertEqual([], baseline.unused_app_problems(state))

    def test_leftoverOfAnUnfinishedTest(self):
        state = hibernation.parse_state(read("hibernation", "state_api32_leftover.txt"))
        self.assertEqual(hibernation.UnusedAppState("allow", "ignore", 5000, "7776000000", True, True), state)
        self.assertEqual(5, len(baseline.unused_app_problems(state)))

    def test_freshInstall(self):
        state = hibernation.parse_state("No operations.\nDefault mode: default\n#threshold\nnull\n#marker\nabsent\n"
                                        "#hibernation\nfalse\nfalse\n")
        self.assertEqual(["not exempt from removing the permissions of the unused app, its mode is default"],
                         baseline.unused_app_problems(state))

    def test_threshold(self):
        self.assertIsNone(hibernation.parse_threshold("null\n"))
        self.assertEqual(5000, hibernation.parse_threshold("5000\n"))
        with self.assertRaises(ValueError):
            hibernation.parse_threshold("Error\n")

    def test_hibernated(self):
        self.assertTrue(hibernation.parse_hibernated("true\n"))
        self.assertFalse(hibernation.parse_hibernated("false"))
        with self.assertRaises(ValueError):
            hibernation.parse_hibernated("cmd: Can't find service: app_hibernation")

    def test_job(self):
        lines = read("hibernation", "jobscheduler.txt").splitlines()
        self.assertEqual(("com.google.android.permissioncontroller", 2), hibernation.parse_job(lines[0]))
        self.assertEqual(("com.google.android.permissioncontroller", 2), hibernation.parse_job(lines[2]))
        self.assertIsNone(hibernation.parse_job("  JOB #u0a194/0: 6809e9f com.google.android.permissioncontroller/"
                                                "com.android.permissioncontroller.permission.service.LocationAccessCheck"
                                                "$LocationAccessCheckJobService"))

    def test_jobRun(self):
        self.assertEqual((True, True), hibernation.parse_job_run("Running job [FORCED]\nactive\nactive ready\nwaiting\n"))
        self.assertEqual((True, False), hibernation.parse_job_run("Running job [FORCED]\nactive\nactive\n"))
        self.assertEqual((False, True), hibernation.parse_job_run("Running job [FORCED]\nwaiting\nwaiting\n"))
        self.assertEqual((False, False), hibernation.parse_job_run(""))


class ResolveActivityTest(unittest.TestCase):
    """`cmd package resolve-activity --brief -a android.settings.BLUETOOTH_SETTINGS`."""

    def test_component(self):
        out = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n" \
              "com.android.settings/.Settings$BluetoothSettingsActivity\n"
        self.assertEqual("com.android.settings", app.parse_resolved_package(out))

    def test_nothingResolves(self):
        self.assertIsNone(app.parse_resolved_package("No activity found\n"))
        self.assertIsNone(app.parse_resolved_package(""))


class RecordedTest(unittest.TestCase):
    def test_laterLevelsBehaveLikeTheLatestRecorded(self):
        ranges = {(28, 32): False, (36, None): True}
        self.assertFalse(device_test.recorded(ranges, 28))
        self.assertFalse(device_test.recorded(ranges, 32))
        self.assertIsNone(device_test.recorded(ranges, 27))
        self.assertIsNone(device_test.recorded(ranges, 33))
        self.assertTrue(device_test.recorded(ranges, 36))
        self.assertTrue(device_test.recorded(ranges, 37))


class NotRunTest(unittest.TestCase):
    def record(self, signal):
        record = records.TestResultRecord("test_x", "ColdStartTest")
        record.test_skip(signal)
        return record

    def test_onlyRequireIsNotApplicable(self):
        self.assertTrue(device_test.is_not_applicable(self.record(device_test.NotApplicable("API 31+"))))
        for signal in (signals.TestAbortClass("the baseline can't be established"),
                       signals.TestSkip("asserts.skip()"), RuntimeError("setup_class failed")):
            self.assertFalse(device_test.is_not_applicable(self.record(signal)))

    def test_moblySkipLinesHidden(self):
        hide = run_suite.HideMoblySkips()

        def shown(message, *args):
            return hide.filter(logging.LogRecord("", logging.INFO, "", 0, message, args, None))
        self.assertFalse(shown("Summary for test class %s: %s", "ColdStartTest", "Skipped 11"))
        self.assertFalse(shown("Summary for test run x:\nTest results: Skipped 11"))
        self.assertFalse(shown("Reason to skip: %s", "API 31+"))
        self.assertFalse(shown("[Test] %s %s", "test_x", "SKIP"))
        self.assertTrue(shown("[Test] %s %s", "test_x", "PASS"))
        self.assertTrue(shown("test_x is not applicable: API 31+"))


if __name__ == "__main__":
    unittest.main()
