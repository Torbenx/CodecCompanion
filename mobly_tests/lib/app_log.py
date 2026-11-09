"""The app's event log, the oracle for what the app did.

The log is a JSON Lines file in the app's private storage, read with run-as.
Reading it doesn't start or touch the app's process, so it works the same
with the app in front, in the background, killed or restarted. A label
broadcast marks a test step, but it starts the process if it isn't running,
so cold start tests don't send one inside their stimulus window.

Events are dicts as the app writes them: v, seq, time, type, opUID, device,
config, actual, attempt, message.
"""

import dataclasses
import json
import pathlib
import re
import time

from lib import device_state

PACKAGE = "com.tt.autobluetoothcodec"
LOG_FILE = "files/logs/event_log.jsonl"
LOG_EVENT_KT = (pathlib.Path(__file__).resolve().parents[2]
                / "app/src/main/java/com/tt/autobluetoothcodec/LogEvent.kt")


def read_event_flows():
    """Returns {type: flow} as declared in LogEvent.kt."""
    text = LOG_EVENT_KT.read_text(encoding="utf-8")
    return dict(re.findall(r"^\s+([A-Z0-9_]+)\(OperationFlow\.([A-Z]+)", text, re.MULTILINE))


FLOWS = read_event_flows()


def parse(text):
    """Parses the log's lines, skipping a partial last line like the app does."""
    events = []
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return events


@dataclasses.dataclass
class Operation:
    uid: str
    events: list

    @property
    def trigger(self):
        return self.events[0]

    @property
    def resolution(self):
        """The resolution's type, or None while the operation is open."""
        last = self.events[-1]
        return last["type"] if FLOWS.get(last["type"]) == "RESOLUTION" else None

    @property
    def resolution_event(self):
        return self.events[-1] if self.resolution else None

    @property
    def device(self):
        return (self.trigger.get("device") or {}).get("address")

    def of_type(self, type_):
        return [e for e in self.events if e["type"] == type_]

    def __str__(self):
        steps = ", ".join(e["type"] + (f"#{e['attempt']}" if "attempt" in e else "") for e in self.events)
        return f"operation {self.uid[:8]} ({self.device}): {steps}"


def operations(events):
    """Groups the events by operation, in the order the operations started."""
    grouped = {}
    for event in events:
        uid = event.get("opUID")
        if uid is not None:
            grouped.setdefault(uid, Operation(uid, [])).events.append(event)
    return [op for op in grouped.values() if FLOWS.get(op.trigger["type"]) == "TRIGGER"]


def check_structure(events, allow_open=0):
    """Returns the problems with the log's structure, an empty list if there are none.

    Every operation starts with its trigger and ends with at most one
    resolution, every event has a known type and seq increases. More than
    [allow_open] open operations is a problem too.
    """
    problems = []
    open_ops = set()
    last_seq = None
    for event in events:
        type_, uid, seq = event.get("type"), event.get("opUID"), event.get("seq")
        if last_seq is not None and seq is not None and seq <= last_seq:
            problems.append(f"seq {seq} after {last_seq}")
        last_seq = seq
        flow = FLOWS.get(type_)
        if flow is None:
            problems.append(f"event {seq} has unknown type {type_}")
            continue
        if flow == "STANDALONE":
            ok = uid is None
        elif flow == "TRIGGER":
            ok = uid is not None and uid not in open_ops
            open_ops.add(uid)
        else:
            ok = uid in open_ops
            if flow == "RESOLUTION":
                open_ops.discard(uid)
        if not ok:
            problems.append(f"event {seq} ({type_}) breaks the operation structure")
    if len(open_ops) > allow_open:
        problems.append(f"{len(open_ops)} operations are still open: {sorted(open_ops)}")
    return problems


class AppLog:
    """The log of the app on one phone."""

    def __init__(self, adb, package=PACKAGE):
        self.adb = adb
        self.package = package

    def read(self):
        out = device_state.shell(self.adb, f"run-as {self.package} cat {LOG_FILE} 2>/dev/null || true", timeout=30)
        return parse(out)

    def exists(self):
        out = device_state.shell(self.adb, f"run-as {self.package} ls {LOG_FILE} 2>/dev/null || true")
        return out.strip() != ""

    def locked_line_count(self):
        """The lines of the device protected file, in which the events logged before the first unlock
        wait for the merge, or None if there is none. Readable once the user is unlocked.

        Its lines are encrypted, so only whether it exists, how many lines it has and how long they are
        are checked.
        """
        lengths = self.locked_line_lengths()
        return None if lengths is None else len(lengths)

    def locked_line_lengths(self):
        """The length of each line of the device protected file, or None if there is none. The app pads
        the events to the same length before it encrypts them."""
        path = f"/data/user_de/0/{self.package}/{LOG_FILE}"
        out = device_state.shell(
            # Without awk, which API 28's toybox doesn't have
            self.adb, f"run-as {self.package} sh -c 'if test -f {path}; then"
                      f" while IFS= read -r line; do echo ${{#line}}; done < {path}; else echo missing; fi'").strip()
        return None if out == "missing" else [int(line) for line in out.splitlines() if line.strip()]

    def last_seq(self):
        events = self.read()
        return events[-1]["seq"] if events else -1

    def label(self, text):
        """Marks a test step and returns the label's seq. Starts the app if it isn't running.

        The receiver flushes the log before the broadcast completes, so events
        logged before the label are in the file when this returns.
        """
        device_state.shell(self.adb, f"am broadcast -f 0x20 -n {self.package}/.TestLabelReceiver --es label {_quote(text)}")
        for event in reversed(self.read()):
            if event["type"] == "TEST_LABEL" and event.get("message") == text:
                return event["seq"]
        raise RuntimeError(f"label {text!r} isn't in the log")

    def events_after(self, seq):
        return [e for e in self.read() if e["seq"] > seq]

    def wait_for_operation(self, after=-1, trigger=None, device=None, resolved=True, timeout=30.0, interval=0.5):
        """Waits for the first operation started after seq [after] that matches, and its resolution."""
        end = time.time() + timeout
        while True:
            for op in operations(self.events_after(after)):
                if trigger is not None and op.trigger["type"] != trigger:
                    continue
                if device is not None and (op.device or "").upper() != device.upper():
                    continue
                if not resolved or op.resolution:
                    return op
            if time.time() >= end:
                raise TimeoutError(f"no {'resolved ' if resolved else ''}{trigger or 'operation'} "
                                   f"for {device or 'any device'} after seq {after} within {timeout:.0f} s; "
                                   f"log tail: {[e['type'] for e in self.read()[-10:]]}")
            time.sleep(interval)

    def wait_for_event(self, type_, after=-1, device=None, timeout=30.0, interval=0.5):
        end = time.time() + timeout
        while True:
            for event in self.events_after(after):
                if event["type"] != type_:
                    continue
                if device is not None and (event.get("device") or {}).get("address", "").upper() != device.upper():
                    continue
                return event
            if time.time() >= end:
                raise TimeoutError(f"no {type_} for {device or 'any device'} after seq {after} within {timeout:.0f} s; "
                                   f"log tail: {[e['type'] for e in self.read()[-10:]]}")
            time.sleep(interval)


def _quote(text):
    return "'" + text.replace("'", "'\\''") + "'"
