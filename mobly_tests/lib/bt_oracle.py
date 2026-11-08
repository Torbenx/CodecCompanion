"""The phone's A2DP state as dumpsys bluetooth_manager reports it.

This is the oracle for the physical result: it doesn't depend on the app's
code. Every function takes a Mobly AdbProxy, like device_state.

The A2DP service section differs per API level:
  * API 28 lists each device as "mDevice: ADDRESS" with a state machine line
    "state=Connected", and its capabilities only in the state machine log.
  * API 32 and later have an "=== A2dpStateMachine for ADDRESS ===" block with
    "mConnectionState: CONNECTED" (STATE_CONNECTED since 36) and a list of
    "mCodecsSelectableCapabilities".
  * API 36 and later mask addresses as XX:XX:XX:XX:4E:5F, so devices are
    matched by their last two bytes there.
"""

import dataclasses
import re
import time

from lib import device_state

# The status header and the A2DP service section, the full dump is 2 MB on the S24
DUMP_COMMAND = "dumpsys bluetooth_manager | sed -n '1,8p;/^Profile: A2dpService/,/^Profile: /p'"

_CODEC = re.compile(
    r"\{codecName:(?P<name>[^,]*),mCodecType:(?P<type>-?\d+),mCodecPriority:(?P<priority>-?\d+),"
    r"mSampleRate:0x(?P<rate>[0-9a-fA-F]+)\([^)]*\),mBitsPerSample:0x(?P<bits>[0-9a-fA-F]+)\([^)]*\),"
    r"mChannelMode:0x(?P<mode>[0-9a-fA-F]+)\([^)]*\),"
    r"mCodecSpecific1:(?P<cs1>-?\d+),mCodecSpecific2:(?P<cs2>-?\d+),"
    r"mCodecSpecific3:(?P<cs3>-?\d+),mCodecSpecific4:(?P<cs4>-?\d+)\}")
_ADDRESS = r"[0-9A-FX]{2}(?::[0-9A-FX]{2}){5}"

# The app's CodecConfig constants, which are Android's
SBC, AAC, APTX, APTX_HD, LDAC, OPUS = 0, 1, 2, 3, 4, 6
CODEC_NAMES = {SBC: "SBC", AAC: "AAC", APTX: "aptX", APTX_HD: "aptX HD", LDAC: "LDAC", OPUS: "Opus"}
SAMPLE_RATES = {0x1: 44100, 0x2: 48000, 0x4: 88200, 0x8: 96000}
BITS_PER_SAMPLE = {0x1: 16, 0x2: 24, 0x4: 32}


@dataclasses.dataclass(frozen=True)
class Codec:
    """A codec configuration with Android's values, like the app's AndroidCodecConfig."""
    codec_type: int
    sample_rate: int
    bits_per_sample: int
    codec_specific1: int = 0
    codec_specific2: int = 0
    codec_specific3: int = 0
    codec_specific4: int = 0
    priority: int = 0

    @property
    def name(self):
        return CODEC_NAMES.get(self.codec_type, f"codec {self.codec_type}")

    def to_app(self):
        """The JSON the app's snippet takes and returns for an AndroidCodecConfig."""
        return {
            "codecType": self.codec_type,
            "sampleRate": self.sample_rate,
            "bitsPerSample": self.bits_per_sample,
            "codecSpecific1": self.codec_specific1,
            "codecSpecific2": self.codec_specific2,
            "codecSpecific3": self.codec_specific3,
            "codecSpecific4": self.codec_specific4,
        }

    @classmethod
    def from_app(cls, config):
        return cls(config.get("codecType", -1), config.get("sampleRate", 0), config.get("bitsPerSample", 0),
                   config.get("codecSpecific1", 0), config.get("codecSpecific2", 0),
                   config.get("codecSpecific3", 0), config.get("codecSpecific4", 0))

    def same_config(self, other):
        """Whether both are the same configuration, ignoring the priority."""
        return dataclasses.replace(self, priority=0) == dataclasses.replace(other, priority=0)

    def __str__(self):
        rate = SAMPLE_RATES.get(self.sample_rate, f"rate 0x{self.sample_rate:x}")
        bits = BITS_PER_SAMPLE.get(self.bits_per_sample, f"bits 0x{self.bits_per_sample:x}")
        extra = "".join(f", cs{i}={v}" for i, v in enumerate(
            (self.codec_specific1, self.codec_specific2, self.codec_specific3, self.codec_specific4), 1) if v)
        return f"{self.name} {rate} Hz {bits}-bit{extra}"


@dataclasses.dataclass(frozen=True)
class Capability:
    """What a device offers for a codec, the masks of rates and sizes."""
    codec_type: int
    sample_rates: int
    bits_per_sample: int

    def supports(self, sample_rate, bits_per_sample):
        return bool(self.sample_rates & sample_rate) and bool(self.bits_per_sample & bits_per_sample)


@dataclasses.dataclass(frozen=True)
class DeviceState:
    address: str
    """As dumped, masked since API 36."""
    connected: bool
    codec: Codec | None
    capabilities: tuple[Capability, ...]


@dataclasses.dataclass(frozen=True)
class A2dpState:
    adapter_state: str | None
    active_device: str | None
    devices: tuple[DeviceState, ...]

    def device(self, address):
        """The state of the device with [address], or None if the A2DP service doesn't list it."""
        return next((d for d in self.devices if same_device(d.address, address)), None)

    def is_active(self, address):
        return self.active_device is not None and same_device(self.active_device, address)


def same_format(a, b):
    """Whether both have the same codec, rate and size, the options aside. None matches nothing."""
    return a is not None and b is not None and         (a.codec_type, a.sample_rate, a.bits_per_sample) == (b.codec_type, b.sample_rate, b.bits_per_sample)


def other_codec(current, capabilities=()):
    """A codec to change to from [current]: SBC 44.1 kHz, 16-bit, which every headset has (D11), or
    where [current] is SBC the first other codec of [capabilities]."""
    if current is None or current.codec_type != SBC:
        return Codec(SBC, 0x1, 0x1)
    for capability in capabilities:
        if capability.codec_type != SBC and capability.codec_type in CODEC_NAMES:
            rate = next(r for r in SAMPLE_RATES if capability.sample_rates & r)
            bits = next(b for b in BITS_PER_SAMPLE if capability.bits_per_sample & b)
            return Codec(capability.codec_type, rate, bits)
    raise ValueError(f"no codec other than {current} in {capabilities}")


def codec_outside(capabilities):
    """A codec the app knows but the device doesn't offer, so applying it never verifies.

    Preferably a codec type the device lacks, otherwise a sample rate one of its codecs lacks.
    """
    offered = {c.codec_type for c in capabilities}
    for codec_type in (OPUS, APTX_HD, APTX, AAC, LDAC):
        if codec_type not in offered:
            return Codec(codec_type, 0x2, 0x1)
    for capability in capabilities:
        for rate in SAMPLE_RATES:
            if not capability.sample_rates & rate:
                bits = next(b for b in BITS_PER_SAMPLE if capability.bits_per_sample & b)
                return Codec(capability.codec_type, rate, bits)
    raise ValueError(f"the device offers everything: {capabilities}")


def same_device(dumped, address):
    """Whether an address from the dump is [address]; masked parts match anything."""
    dumped, address = dumped.upper(), address.upper()
    return len(dumped) == len(address) and all(a == b or a == "X" for a, b in zip(dumped, address))


def _codec(match):
    return Codec(int(match["type"]), int(match["rate"], 16), int(match["bits"], 16),
                 int(match["cs1"]), int(match["cs2"]), int(match["cs3"]), int(match["cs4"]), int(match["priority"]))


def _capability(match):
    return Capability(int(match["type"]), int(match["rate"], 16), int(match["bits"], 16))


def _parse_block_32(address, block):
    """An "=== A2dpStateMachine for ... ===" block, API 32 and later."""
    state = re.search(r"mConnectionState: (?:STATE_)?(\w+)", block)
    codec = re.search(r"mCodecConfig: (\{[^}]*\})", block)
    capabilities = []
    lines = block.splitlines()
    for i, line in enumerate(lines):
        if line.strip() == "mCodecsSelectableCapabilities:":
            for following in lines[i + 1:]:
                match = _CODEC.search(following)
                if not following.strip().startswith("{") or not match:
                    break
                capabilities.append(_capability(match))
            break
    return DeviceState(
        address=address,
        connected=bool(state) and state.group(1) == "CONNECTED",
        codec=_codec(_CODEC.search(codec.group(1))) if codec and _CODEC.search(codec.group(1)) else None,
        capabilities=tuple(capabilities),
    )


def _parse_block_28(address, block):
    """An "mDevice: ..." block, API 28."""
    state = re.search(r"StateMachine: name=A2dpStateMachine state=(\w+)", block)
    codec = re.search(r"^\s*mCodecConfig: (\{[^}]*\})", block, re.MULTILINE)
    capabilities = []
    # Only the state machine log has them, the last record is the current one
    lists = re.findall(r"mCodecsSelectableCapabilities:\[(.*?)\]", block)
    if lists:
        capabilities = [_capability(m) for m in _CODEC.finditer(lists[-1])]
    return DeviceState(
        address=address,
        connected=bool(state) and state.group(1) == "Connected",
        codec=_codec(_CODEC.search(codec.group(1))) if codec and _CODEC.search(codec.group(1)) else None,
        capabilities=tuple(capabilities),
    )


def parse(dump):
    """Parses the output of DUMP_COMMAND, or the full dump."""
    start = dump.find("Profile: A2dpService")
    section = ""
    if start >= 0:
        end = dump.find("\nProfile: ", start + 1)
        section = dump[start:end if end >= 0 else len(dump)]
    active = re.search(rf"mActiveDevice: ({_ADDRESS}|null)", section)
    devices = []
    blocks_32 = list(re.finditer(rf"=== A2dpStateMachine for ({_ADDRESS})[^=]*===", section))
    if blocks_32:
        for i, match in enumerate(blocks_32):
            end = blocks_32[i + 1].start() if i + 1 < len(blocks_32) else len(section)
            devices.append(_parse_block_32(match.group(1), section[match.end():end]))
    else:
        blocks_28 = list(re.finditer(rf"^  mDevice: ({_ADDRESS})", section, re.MULTILINE))
        for i, match in enumerate(blocks_28):
            end = blocks_28[i + 1].start() if i + 1 < len(blocks_28) else len(section)
            devices.append(_parse_block_28(match.group(1), section[match.end():end]))
    return A2dpState(
        adapter_state=device_state.parse_bluetooth_state(dump),
        active_device=active.group(1) if active and active.group(1) != "null" else None,
        devices=tuple(devices),
    )


def read(adb):
    return parse(device_state.shell(adb, DUMP_COMMAND, timeout=30))


def device(adb, address):
    return read(adb).device(address)


def is_connected(adb, address):
    state = device(adb, address)
    return state is not None and state.connected


def active_codec(adb, address):
    """The codec A2DP uses with the device, or None if it isn't connected."""
    state = device(adb, address)
    return state.codec if state is not None and state.connected else None


def wait_for(adb, condition, timeout, interval=1.0, what="condition"):
    """Polls read() until condition(state) is true, returns the state."""
    end = time.time() + timeout
    while True:
        state = read(adb)
        if condition(state):
            return state
        if time.time() >= end:
            raise TimeoutError(f"{what} not reached after {timeout:.0f} s: {state}")
        time.sleep(interval)


def wait_connected(adb, address, timeout=30.0, connected=True):
    """Waits until A2DP is connected to the device and it's the active one, or disconnected."""
    def reached(state):
        dev = state.device(address)
        if connected:
            return dev is not None and dev.connected and state.is_active(address)
        return dev is None or not dev.connected
    return wait_for(adb, reached, timeout, what=f"A2DP {'connected' if connected else 'disconnected'}")
