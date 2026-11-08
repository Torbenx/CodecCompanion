"""Companion device links and observing, from cmd companiondevice and dumpsys.

`cmd companiondevice list` prints "PACKAGE ADDRESS" lines up to API 35, a
table with association ids and lower case addresses on API 36, and since
API 37 the associations as dumpsys companiondevice shows them. Observing is
shown by dumpsys companiondevice since API 33 (mNotifyOnDeviceNearby); API 31
and 32 show it nowhere, not even in the system's associations file.
"""

import dataclasses
import re
import time

from lib import device_state

USER = 0


@dataclasses.dataclass(frozen=True)
class Association:
    package: str
    address: str
    """Upper case."""
    id: int | None = None
    """Only listed since API 36."""


def parse_list(output):
    """Parses `cmd companiondevice list USER`."""
    associations = []
    for line in output.splitlines():
        record = re.match(r"^\s*Association\{mId=(\d+),.*?mPackageName='([^']*)', mDeviceMacAddress=([0-9A-Fa-f:]{17}),", line)
        if record:
            associations.append(Association(record.group(2), record.group(3).upper(), int(record.group(1))))
            continue
        table = re.match(r"^\s*(\d+)\s*\|\s*(\S+)\s*\|\s*([0-9A-Fa-f:]{17})\s*$", line)
        if table:
            associations.append(Association(table.group(2), table.group(3).upper(), int(table.group(1))))
            continue
        plain = re.match(r"^\s*(\S+)\s+([0-9A-Fa-f:]{17})\s*$", line)
        if plain:
            associations.append(Association(plain.group(1), plain.group(2).upper()))
    return associations


def parse_observing(dump, package, address):
    """Whether dumpsys companiondevice shows the association as observed, None if it doesn't say."""
    # One association per line, with nested braces like "AssociatedDevice { ... }"
    for match in re.finditer(r"Association\{(.*)$", dump.split("Last Removed Association", 1)[0], re.MULTILINE):
        fields = match.group(1)
        if f"mPackageName='{package}'" not in fields:
            continue
        mac = re.search(r"mDeviceMacAddress=([0-9A-Fa-f:]{17})", fields)
        if not mac or mac.group(1).upper() != address.upper():
            continue
        nearby = re.search(r"mNotifyOnDeviceNearby=(true|false)", fields)
        return None if nearby is None else nearby.group(1) == "true"
    return None


def parse_present(dump, address):
    """Whether dumpsys companiondevice lists the device as present, None if it has no such section."""
    if "Companion Device Present" not in dump:
        return None
    section = dump.split("Companion Device Present", 1)[1].split("Companion Device Application Controller", 1)[0]
    return re.search(rf"addr={re.escape(address)}", section, re.IGNORECASE) is not None


def associations(adb, package):
    output = device_state.shell(adb, f"cmd companiondevice list {USER}")
    return [a for a in parse_list(output) if a.package == package]


def is_linked(adb, package, address):
    return any(a.address == address.upper() for a in associations(adb, package))


def link(adb, package, address):
    device_state.shell(adb, f"cmd companiondevice associate {USER} {package} {address.upper()}")
    if not is_linked(adb, package, address):
        raise RuntimeError(f"couldn't link {address} to {package}")


def wait_unlinked(adb, package, address, timeout):
    """Waits until the device isn't linked to the package, returns whether it is still."""
    end = time.time() + timeout
    while is_linked(adb, package, address):
        if time.time() >= end:
            return True
        time.sleep(0.1)
    return False


def unlink(adb, package, address):
    device_state.shell(adb, f"cmd companiondevice disassociate {USER} {package} {address.upper()}")
    if is_linked(adb, package, address):
        raise RuntimeError(f"couldn't unlink {address} from {package}")


def is_observing(adb, package, address):
    """Whether the app observes the device's presence, None where the phone doesn't show it."""
    return parse_observing(device_state.shell(adb, "dumpsys companiondevice"), package, address)


def is_present(adb, address):
    return parse_present(device_state.shell(adb, "dumpsys companiondevice"), address)
