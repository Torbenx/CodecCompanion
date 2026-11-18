"""Generate a sample event log that contains every event type, to check how
the app's log screen renders them.

The events are spread over three days ending at --now, so the day headers
"Today", "Yesterday" and a date all appear. They use two named devices, one
without a name and one with a codec the app doesn't know. The last operation
is left open, so loading the log exercises the repair that resolves it with
OPERATION_INTERRUPTED.

The script reads the event types from LogEvent.kt, like the test harness, and fails if the sample
misses one or breaks the operation structure (trigger, elements, resolution).

Load it on a phone with a debug build installed:
  python tools/sample_event_log.py --now-ms <phone time> --utc-offset <phone offset> -o sample.jsonl
  adb push sample.jsonl /data/local/tmp/sample.jsonl
  adb shell am force-stop com.tt.codeccompanion
  adb shell run-as com.tt.codeccompanion sh -c "'mkdir -p files/logs && cat /data/local/tmp/sample.jsonl > files/logs/event_log.jsonl'"
"""

import argparse
import json
import pathlib
import sys
import time
import uuid

# The structure check is shared with the test harness
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "mobly_tests"))
from lib.app_log import FLOWS, check_structure, operations  # noqa: E402

DAY_MS = 24 * 60 * 60 * 1000

# Android's BluetoothCodecConfig constants, see CodecConfig.kt
CODEC_TYPES = {"SBC": 0, "AAC": 1, "APTX": 2, "APTX_HD": 3, "LDAC": 4, "LC3": 5, "OPUS": 6}
SAMPLE_RATES = {44100: 0x1, 48000: 0x2, 88200: 0x4, 96000: 0x8}
BITS_PER_SAMPLE = {16: 0x1, 24: 0x2, 32: 0x4}
LDAC_BITRATES = {"HIGH": 0, "MID": 1, "LOW": 2, "ADAPTIVE": 3}
AAC_VARIABLE_BITRATES = {"DISABLED": 0, "VERY_LOW": 1, "LOW": 2, "MEDIUM": 3, "HIGH": 4, "VERY_HIGH": 5}

HEADPHONES = {"address": "00:1B:66:A1:B2:C3", "name": "WH-1000XM4"}
DAC = {"address": "F4:4E:FD:12:34:56", "name": "Qudelix-5K with a rather long device name"}
NAMELESS = {"address": "AC:80:0A:11:22:33"}
LC3_EARBUDS = {"address": "C8:7B:23:44:55:66", "name": "Galaxy Buds3"}


def codec(name, sample_rate, bits, ldac_bitrate=None, aac_vbr=None, opus_complexity=None):
    """Both models of a codec configuration, like LogCodecConfig."""
    android = {
        "codecType": CODEC_TYPES[name],
        "sampleRate": SAMPLE_RATES[sample_rate],
        "bitsPerSample": BITS_PER_SAMPLE[bits],
        "codecSpecific1": 0,
        "codecSpecific2": 0,
        "codecSpecific3": 0,
        "codecSpecific4": 0,
    }
    own = {"type": name, "sampleRate": sample_rate, "bitsPerSample": bits}
    if ldac_bitrate is not None:
        android["codecSpecific1"] = 10 + LDAC_BITRATES[ldac_bitrate]
        own["bitrate"] = ldac_bitrate
    if aac_vbr is not None:
        android["codecSpecific1"] = 128 + AAC_VARIABLE_BITRATES[aac_vbr]
        own["variableBitrate"] = aac_vbr
    if opus_complexity is not None:
        android["codecSpecific3"] = 10 + opus_complexity
        own["encodeComplexity"] = opus_complexity
    if name == "LC3":
        # Unknown to the app, so there is no own model
        return {"android": android}
    return {"android": android, "own": own}


LDAC_96 = codec("LDAC", 96000, 24, ldac_bitrate="ADAPTIVE")
LDAC_48 = codec("LDAC", 48000, 24, ldac_bitrate="HIGH")
AAC = codec("AAC", 44100, 16, aac_vbr="HIGH")
APTX_HD = codec("APTX_HD", 48000, 24)
SBC = codec("SBC", 44100, 16)
OPUS = codec("OPUS", 48000, 16, opus_complexity=7)
LC3 = codec("LC3", 48000, 16)


class Builder:
    def __init__(self, now_ms, utc_offset_minutes):
        self.now_ms = now_ms
        self.offset_ms = utc_offset_minutes * 60 * 1000
        self.events = []
        self.time = 0

    def at(self, days_ago, hour, minute):
        """Moves the clock to a local time of day, relative to the day of --now."""
        midnight = self.now_ms - (self.now_ms + self.offset_ms) % DAY_MS
        self.time = min(midnight - days_ago * DAY_MS + (hour * 60 + minute) * 60 * 1000, self.now_ms - 60_000)

    def event(self, type_, op_uid=None, device=None, delay_ms=0, **fields):
        self.time += delay_ms
        event = {"v": 1, "seq": len(self.events), "time": self.time, "type": type_}
        if op_uid:
            event["opUID"] = op_uid
        if device:
            event["device"] = device
        event.update({k: v for k, v in fields.items() if v is not None})
        self.events.append(event)
        return event

    def operation(self, trigger, device, steps, resolution=None, **trigger_fields):
        """steps and resolution are (type, delay_ms, fields) tuples."""
        uid = str(uuid.uuid4())
        self.event(trigger, uid, device, **trigger_fields)
        for type_, delay, fields in steps:
            self.event(type_, uid, device, delay, **fields)
        if resolution:
            type_, delay, fields = resolution
            self.event(type_, uid, device, delay, **fields)
        return uid


def build(now_ms, utc_offset_minutes):
    b = Builder(now_ms, utc_offset_minutes)
    set_ = "SET_CODEC_CONFIG"
    get = "VALIDATE_CODEC_CONFIG"

    b.at(2, 8, 15)
    b.event("PERMISSION_CHANGED", message="granted")
    b.at(2, 8, 30)
    b.operation("A2DP_CONNECTED", HEADPHONES, [
        (set_, 40, {"config": LDAC_96, "attempt": 1}),
        (get, 500, {"config": LDAC_96, "actual": LDAC_48, "attempt": 1}),
        (set_, 2, {"config": LDAC_96, "attempt": 2}),
        (get, 500, {"config": LDAC_96, "actual": LDAC_96, "attempt": 2}),
    ], ("CODEC_VERIFIED", 1, {"actual": LDAC_96, "attempt": 2}), message="legacy receiver")
    b.at(2, 12, 5)
    steps = [(set_, 40, {"config": LDAC_96, "attempt": 1})]
    for attempt in range(1, 6):
        steps.append((get, 500, {"config": LDAC_96, "actual": LDAC_48, "attempt": attempt}))
        if attempt < 5:
            steps.append((set_, 2, {"config": LDAC_96, "attempt": attempt + 1}))
    b.operation("A2DP_CONNECTED", HEADPHONES, steps,
                ("MAX_ATTEMPTS", 1, {"config": LDAC_96, "actual": LDAC_48, "attempt": 5}), message="legacy receiver")
    b.at(2, 18, 40)
    b.operation("A2DP_CONNECTED", DAC, [(set_, 40, {"config": APTX_HD, "attempt": 1})],
                ("TIMEOUT", 15_000, {"attempt": 1}), message="legacy receiver")

    b.at(1, 7, 50)
    b.event("OBSERVING_CHANGED", device=HEADPHONES, message="observing presence")
    b.event("OBSERVING_FAILED", device=DAC, delay_ms=5,
            message="android.companion.DeviceNotAssociatedException: Association for F4:4E:FD:12:34:56 doesn't exist")
    b.at(1, 8, 0)
    b.event("DEVICE_APPEARED", device=HEADPHONES, message="Bluetooth")
    pending = b.operation("A2DP_CONNECTED", HEADPHONES, [
        (set_, 40, {"config": LDAC_96, "attempt": 1}),
        (get, 500, {"config": LDAC_96, "actual": LDAC_96, "attempt": 1}),
    ], ("CODEC_VERIFIED", 1, {"actual": LDAC_96, "attempt": 1}), message="companion service")
    b.operation("A2DP_CONNECTED", HEADPHONES, [], ("ALREADY_PENDING", 1, {"message": f"pending in operation {pending}"}),
                message="companion service")
    b.at(1, 9, 10)
    b.event("DEVICE_APPEARED", device=DAC, message="BLE")
    b.operation("A2DP_CONNECTED", LC3_EARBUDS, [], ("NO_PREFERRED_CODEC", 20, {}), message="manifest receiver")
    b.operation("A2DP_CONNECTED", DAC, [], ("NOT_CONNECTED", 30, {"attempt": 0}), message="companion service")
    b.operation("A2DP_CONNECTED", DAC, [], ("NO_ASSOCIATION", 20, {}), message="companion service")
    b.operation("A2DP_CONNECTED", DAC, [], ("NO_PERMISSION", 20, {}), message="manifest receiver")
    b.operation("A2DP_CONNECTED", DAC, [], ("PROFILE_UNAVAILABLE", 20, {}), message="manifest receiver")
    b.at(1, 13, 45)
    b.operation("A2DP_CONNECTED", DAC, [(set_, 40, {"config": APTX_HD, "attempt": 1})],
                ("PROFILE_DISCONNECTED", 300, {"attempt": 1}), message="companion service")
    b.operation("A2DP_CONNECTED", DAC, [(set_, 40, {"config": APTX_HD, "attempt": 1})], ("SECURITY_EXCEPTION", 5, {
        "attempt": 1,
        "message": "java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission or a companion "
                   "association for device F4:4E:FD:12:34:56 (Qudelix-5K with a rather long device name)",
    }), message="companion service")
    b.at(1, 17, 20)
    b.operation("A2DP_CONNECTED", HEADPHONES, [(set_, 40, {"config": LDAC_96, "attempt": 1})],
                ("CANCELLED", 200, {"attempt": 1, "message": "device disappeared"}), message="companion service")
    b.event("DEVICE_DISAPPEARED", device=HEADPHONES)
    b.event("DEVICE_APPEARED", device=NAMELESS)
    b.at(1, 22, 55)
    b.operation("A2DP_CONNECTED", HEADPHONES, [(set_, 40, {"config": LDAC_96, "attempt": 1})],
                ("OPERATION_INTERRUPTED", 3_600_000, {}), message="companion service")

    b.at(0, 0, 5)
    b.event("TEST_LABEL", message="reconnect_test start")
    # The codec chosen while restoring supersedes the restore, so the two operations interleave
    restore, chosen = str(uuid.uuid4()), str(uuid.uuid4())
    b.event("USER_RESTORE_PREFERRED", restore, HEADPHONES, config=LDAC_96)
    b.event(set_, restore, HEADPHONES, 5, config=LDAC_96, attempt=1)
    b.event("USER_SET_CODEC", chosen, HEADPHONES, 300, config=AAC)
    b.event("SUPERSEDED", restore, HEADPHONES, 1, attempt=1, message=f"superseded by operation {chosen}")
    b.event(set_, chosen, HEADPHONES, 1, config=AAC, attempt=1)
    b.event(get, chosen, HEADPHONES, 500, config=AAC, actual=AAC, attempt=1)
    b.event("CODEC_VERIFIED", chosen, HEADPHONES, 1, actual=AAC, attempt=1)
    b.operation("USER_SET_CODEC", DAC, [("ASSOCIATION_REQUESTED", 5, {})],
                ("ASSOCIATION_FAILED", 8000, {"message": "association request already in progress"}), config=APTX_HD)
    b.operation("USER_SET_CODEC", HEADPHONES, [(set_, 5, {"config": OPUS})], ("ERROR", 20, {
        "message": "java.lang.reflect.InvocationTargetException: java.lang.IllegalArgumentException: "
                   "codec type 6 is not supported by this Bluetooth stack",
    }), config=OPUS)
    b.operation("USER_SET_CODEC", DAC, [(set_, 5, {"config": SBC})], ("SECURITY_EXCEPTION", 20, {
        "message": "java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission",
    }), config=SBC)
    b.event("TEST_LABEL", message="reconnect_test end")
    b.at(0, 0, 20)
    b.operation("USER_SAVE_PREFERRED", DAC, [("ASSOCIATION_REQUESTED", 5, {})], ("PREFERRED_SAVED", 6000, {}), config=APTX_HD)
    b.operation("USER_LINK_DEVICE", LC3_EARBUDS, [], ("ASSOCIATION_CREATED", 7000, {}))
    b.event("PREFERRED_DELETED", device=HEADPHONES, delay_ms=60_000, config=SBC)
    b.event("DEVICE_UNLINKED", device=LC3_EARBUDS, delay_ms=10_000)
    b.event("READ_FAILED", device=LC3_EARBUDS, delay_ms=1000,
            message="java.lang.SecurityException: Need BLUETOOTH_PRIVILEGED permission")
    b.event("ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE", device=LC3_EARBUDS, delay_ms=5000, actual=LC3)
    b.event("PERMISSION_CHANGED", delay_ms=60_000, message="permanently denied")
    # Left open on purpose, loading the log resolves it with OPERATION_INTERRUPTED
    b.operation("A2DP_CONNECTED", HEADPHONES, [(set_, 40, {"config": LDAC_96, "attempt": 1})],
                message="companion service")
    return b.events


def check(events):
    """Raises if an event type is missing or unknown, or operations are malformed."""
    used = {e["type"] for e in events}
    missing = set(FLOWS) - used
    unknown = used - set(FLOWS)
    if missing or unknown:
        raise SystemExit(f"missing event types: {sorted(missing)}, unknown: {sorted(unknown)}")
    problems = check_structure(events, allow_open=1)
    if problems:
        raise SystemExit("; ".join(problems))
    open_ops = [op for op in operations(events) if not op.resolution]
    if len(open_ops) != 1:
        raise SystemExit(f"expected exactly one open operation, got {len(open_ops)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--now-ms", type=int, default=int(time.time() * 1000),
                    help="end of the log in milliseconds since the epoch, use the phone's clock")
    ap.add_argument("--utc-offset", default=time.strftime("%z"),
                    help="the phone's UTC offset like +0200, for the day boundaries")
    ap.add_argument("-o", "--output", type=pathlib.Path, help="output file, standard output if omitted")
    args = ap.parse_args()

    sign = -1 if args.utc_offset.startswith("-") else 1
    digits = args.utc_offset.lstrip("+-")
    events = build(args.now_ms, sign * (int(digits[:2]) * 60 + int(digits[2:])))
    check(events)
    text = "".join(json.dumps(e, separators=(",", ":")) + "\n" for e in events)
    if args.output:
        args.output.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {len(events)} events to {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
