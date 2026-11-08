# Test concept

How Auto Bluetooth Codec is tested: which test levels exist, what each one
covers, which information and control channels they use, what the harness
needs, and in which order it's built. The decisions behind it, D1 to D27, are
listed in [section 13](#13-decisions) and referenced where they matter.

Two manual test records, `tools/codec-applier-tests.md` and
`tools/log-ui-tests.md`, informed the automated tests. They were deleted once
their cases were automated (D10) and remain in the Git history, see
[section 11](#11-existing-manual-tests).

## 1. What needs testing

The app's promise is one sentence: when a device connects, its preferred codec
is applied, even if the app isn't running. Most of the risk sits in the parts
between the app and the system, which unit tests can't see.

| Part | What can go wrong | Tested best by |
| --- | --- | --- |
| `CodecConfig` model, serializers | Wrong mapping to the stack's values, wrong verification (`isFulfilledBy`), old stored formats | Unit tests |
| `CodecApplier` | Retries, supersede and pending rules, profile proxy lifecycle, resolutions, the order in which `pending` is published | Component tests with a fake Bluetooth backend, a few device tests |
| `A2dpProfileUtil` | Reflection breaks on a version or vendor stack, values read differently | Device contract tests on every phone |
| `CodecCompanionService`, `A2dpConnectReceiver` | Not started, started too late, killed mid-apply, process restarted without state, a saved device's connect or disconnect not noted | Background and cold start tests on the phones |
| `SavedDeviceStore` | Preferred codecs or the saved devices' info lost, the info read before the unlock | Unit and component tests, the cold start and reboot tests |
| `CompanionPresence`, `PermissionUtil` | Wrong branch for an API level or a phone without the companion feature, observing drifts from the stored state | Component tests across SDK levels, device tests on 31+ |
| `EventLog`, `LogExport` | Broken operation structure, lost or partial lines, trimming, anonymisation | Unit and component tests |
| `MainActivity` | Wrong UI for a device state, chip logic, permission and linking flows, logging observed codecs, saving, changing and deleting saved devices, the empty states' ways out | UI tests with fake state, Bluetooth logic tests with the main screen open, a few journeys |
| `LogActivity` | Wrong texts, grouping, expand and collapse, export and clear | UI tests with the sample log |

## 2. Testbed

| Phone | Serial | Android | API | Notes |
| --- | --- | --- | --- | --- |
| Pixel 3a | `0000000028` | 9 | 28 | Rooted, pure test device, clock set to 2019 |
| Pixel 3a | `0000000032` | 12L | **32** | Rooted, pure test device |
| Galaxy S24 | `R0000000036` | 17 | 37 | Not rooted, personal phone with a known PIN, Samsung Bluetooth stack. API 36 (Android 16) until November 2026 |

| Headset | Address | Controlled by |
| --- | --- | --- |
| Qudelix-5K | `0A:1B:2C:3D:4E:5F` | USB |
| Any other headset | from the testbed config | The phone's Bluetooth adapter, see [6.3](#63-headsets) |

The second Pixel is API 32 (build `SP2A.220505.008`), not 31. The app has no
branch between 31 and 32, so it covers the API 31 paths.

Assumptions:

- A headset can only connect to the phones on adb (D6). Phones that aren't on
  adb are out of range or have Bluetooth off; the harness doesn't check.
- A headset serves one phone at a time, so the phones are tested one after the
  other. [testbed_session.py](mobly_tests/testbed_session.py) runs during
  testing: it keeps Bluetooth off on every phone on adb, keeps the screens on
  while plugged in, and warns when a phone is locked, unplugged or unreachable.
  Tests turn Bluetooth on for the phone under test and off again.
- The phones are unlocked during a run, the S24 included (D15). Only the
  reboot tests lock them, and unlock them again (D24).

### Lock screen classes

Each phone in `testbed.yaml` is in one of three classes (D24). They decide
which phones may be rebooted, since a reboot ends at the lock screen.

| Class | `testbed.yaml` | Lock screen | Reboot tests |
| --- | --- | --- | --- |
| Pure test device | `pure_test_device: true` | None by default. C14 to C16 set the PIN 0000 and clear it again | C7, C14, C15, C16 |
| Personal phone | `lock_password: "…"`, quoted | Its own; the password is only typed, never set or cleared | C14, C15, C16 |
| Unknown password | neither | Its own | none |

The suite only ever sets or clears the PIN 0000, with two hardcoded commands
in [lock_screen.py](mobly_tests/lib/lock_screen.py); a harness self-test
fails if any other command sets or clears a credential. A personal phone's
password never reaches the results (D25).

### Phone capabilities

Tests don't name phones (D7). They name the API levels they apply to and the
capabilities they need. On a phone without one the test is not applicable,
with the reason in the report. Only what genuinely can't work without root or
with the phone's lock screen is a capability:

| Capability | Known from | Needed by | Testbed |
| --- | --- | --- | --- |
| Switch the phone's Bluetooth | API 29+, or root (`su`) on API 28, where the shell isn't allowed to | The testbed itself; a non-rooted API 28 phone can't take part | all |
| No lock screen | A pure test device | C7: with a secure lock screen the app can't run after a reboot until the phone is unlocked, because its storage stays encrypted until then | both Pixels |
| A lock screen the suite can unlock | A pure test device or a known password | C14, C15, C16: the phone is rebooted with a secure lock screen and unlocked by typing its PIN or password | all |
| End the app's process like the system | `run-as … kill` on API 28 and 36; API 32 needs root, `run-as` can't see the process there | C1, C3, C4 | all |
| LDAC | The codec capabilities of phone and headset, at run time | B3 | all, with the Qudelix |

### Code paths per API level

| Path | API 28 Pixel | API 32 Pixel | API 33 | API 34–35 | API 36 S24 | 31+ without companion feature |
| --- | --- | --- | --- | --- | --- | --- |
| Applies on connect | `A2dpConnectReceiver` | `A2dpConnectReceiver`; `CodecCompanionService` disabled (D27) | `CodecCompanionService` when A2DP connects while the device is present, observed by address; `A2dpConnectReceiver` too | by address | by association id | `A2dpConnectReceiver` |
| Link asked for | – | Only while the app has no link and isn't exempt from losing its permission when unused (D27) | per device | per device | per device | – |
| Codec classes | reflection | reflection | public | public | public | – |
| Runtime permission | – | yes | yes | yes | yes | yes |
| Association needed to change the codec | no | no | no | yes | yes | – |
| Association needed to read the codec | no | no | no | no | yes | – |
| Presence comes from | – | not observed (D27) | ? | ? | appears before A2DP (manual test 2.1) | – |

Gaps no phone covers: API 33, API 34–35 (the only versions where the codec
can be read but not changed without a link, so the card shows the codec and
the link prompt together), and API 31+ phones without the companion feature.
Component tests (K6) and UI tests (V5) cover their logic. Borrowing an API
34 or 35 phone would close the biggest remaining gap; an emulator can't, it
has no A2DP sink with real codecs.

## 3. Channels

### 3.1 Information channels

| Channel | Tells | Strengths | Limits | Use as |
| --- | --- | --- | --- | --- |
| Event log file, read with `run-as … cat` | What the app did, why, with which codec, how many attempts | Records background work. Reading it doesn't start or touch the app process. Its format is a product feature (export), so it's stable | Lines are written on a background thread, the last one may be partial or missing | **Main oracle for behaviour** |
| Preferences files, read with `run-as … cat` | The preferred codecs, and each saved device's info: its name, when it last connected or disconnected, the codecs it offered | Like the log, reading doesn't touch the app process. Device protected, so readable once the user unlocked, also for what was written before | Written in the background, so a read right after a change may see the old value | Oracle for what the app stores |
| `TestLabelReceiver` broadcast | Marks a test step with a known `seq` | Flushes the log before the broadcast completes, so "label, then read" is consistent | Starts the app process if it isn't running | Step marker and flush barrier, outside of cold start windows |
| App snippet | Preferred codecs, `pending`, associations and the codec as the app sees them | Exact internal state, no UI involved | Runs in the app's process as instrumentation: elevates its priority and restarts it when loaded and unloaded | Setup and inspection in Bluetooth logic tests |
| `dumpsys bluetooth_manager` | Adapter state, A2DP connection and active codec per device | Independent of the app's code, so it can check the app's own reads | Format differs per API level, 2 MB on the S24 | **Main oracle for the physical result** |
| `cmd companiondevice list`, `dumpsys companiondevice` | Associations, on 33+ also observing | Independent of the app | Observing isn't shown on API 32 (root can read the file) | Oracle for linking and observing |
| UI hierarchy (UiAutomator) | What the user sees | The only proof that the screen shows the state | Polled every second by the app, slow to query, changes with the planned UI polish | Oracle only for what the UI derives or drives |
| Logcat | Everything, including the stack | Diagnoses failures | Noisy, not a contract | Artifact on failure, never an oracle |
| Headset status | The Qudelix's Bluetooth mode | | Doesn't say which phone it's connected to; other headsets have none | Precondition check |

### 3.2 Control channels

| Channel | Does | Notes |
| --- | --- | --- |
| UI (UiAutomator) | Every user action, including system dialogs (permission, companion link) | Only in UI tests and journeys |
| App snippet | Apply a codec through `CodecApplier`, set a codec bypassing it (like another app would), set or delete preferred codecs (setting notes the device's info like the app's Save), sync presence, reset the app's state | Not in background or cold start tests |
| adb, Bluetooth | The phone's adapter on and off (`svc bluetooth`, with su on API 28) | See [device_state.py](mobly_tests/lib/device_state.py). A2DP alone can't be switched from adb: `cmd bluetooth_manager` on API 36 only offers the adapter and its BLE mode (D14) |
| adb, app lifecycle | Install, update, clear data, kill the process, grant and revoke the permission, launch activities | Revoking a permission kills the process |
| adb, companion | `cmd companiondevice associate/disassociate` (checked on 28 and 32, to check on 36) | Links a device without the system dialog (D9) |
| Headset | Depends on the backend, see [6.3](#63-headsets). Qudelix: `bt-off` (device disappears), `no-a2dp` (A2DP down, link and BLE stay), `normal`, `reboot` | `no-a2dp` gives the state "present but A2DP not connected" that cold start tests need |
| Developer options | Change the codec like a user would | Only by UI; the snippet's direct set is the faster equivalent |

### 3.3 What to assert where

Each fact is checked at the cheapest level that produces it, and through the
channel that doesn't depend on the code under test:

1. **The codec that is actually active** is read from `dumpsys`, never only
   from the app, whose read path is code under test. The contract test B1
   compares both once per phone; when they agree, both are trusted.
2. **What the app did** is read from the event log: trigger, attempts and
   resolution of an operation. The log is the only channel that works the
   same with the app in the foreground, in the background or restarted.
3. **App state that isn't logged** (preferred codecs, `pending`, what the app
   thinks is linked) comes from the snippet, but only in tests where the
   process priority doesn't matter.
4. **The UI** is asserted only for what it computes or drives. The main
   screen is a function of `DeviceData`, re-read every second. That function is
   tested once with fake data (L2). Journeys then only check that the loop is
   closed: one assertion that the card shows the codec `dumpsys` reports. Full
   UI checks in every Bluetooth test would add run time and break with the UI
   polish, without finding anything the L2 tests miss.
5. **Logcat, screenshots and UI dumps** are collected when a test fails.

Pitfalls the harness has to handle:

- A label broadcast to a dead app starts its process. Cold start tests send no
  label between killing the app and the stimulus, and read the log with
  `run-as` instead.
- `am instrument` force-stops the target before it starts, so loading the
  snippet or running the UI tests on a phone restarts the app, and unloading
  the snippet normally ends the process. Instrumented phases and phases without
  instrumentation are separated by restarts. The spike confirms this, and that
  it doesn't leave the package stopped.
- `am force-stop` puts the package in the stopped state, so manifest receivers
  get nothing until a component of the app is started again. A broadcast to
  `TestLabelReceiver` without a label does that without writing to the log.
- A process killed by the system is simulated with `run-as … kill -9`, which
  works without root on API 28 and 36 and doesn't stop the package; on API 32
  SELinux keeps `run-as` from seeing the process, so it takes `su`. `am kill`
  doesn't end a process the companion service keeps bound. On the S24 the
  system binds the service again within about 3 s and delivers
  `onDeviceAppeared` again; on the API 32 Pixel it doesn't (C3).
- Before the first unlock `run-as` may fail, since it changes into the
  app's credential protected directory, and on API 32 SELinux denies its
  `sigkill` anyway, so C16 ends the process with `am kill`, which only ends
  a cached one. Once the
  user is unlocked, `run-as` reads the app's device protected directory too.
- Mobly calls `on_fail` after `teardown_test`, when the restore has already
  reset the app, so `BaselineTest` collects the artifacts in its teardown,
  before the restore. Mobly calls the teardown in a `finally` block, so the
  exception the setup or test raised tells it the test is failing; broken
  invariants are found in the teardown itself. Passing tests don't pay for the
  UI dump and screenshot, about 6 s each.
- The phones' clocks differ (the API 28 Pixel is in 2019). Events are ordered
  by `seq` and labels, never compared with the host's time. The times the app
  notes for the Saved devices list are compared with the phone's own clock
  (`device_state.clock`, whole seconds) or with the time of the receiver's
  trigger in the log, which it logs right after noting a connect. A reconnect
  is a disconnect and a connect, so only the trigger tells that the connect
  was noted.
- `dumpsys bluetooth_manager` has several per-device tables, like the AVRCP
  volume table with its own connection column. The A2DP connection and codec
  come from the A2DP service section only. Its format differs: API 28 has an
  `mDevice:` line and `state=Connected`, API 32 and later have an
  `A2dpStateMachine for …` block with `mConnectionState: CONNECTED` and
  `STATE_CONNECTED`. Since API 36 addresses are masked as `XX:XX:XX:XX:4E:5F`,
  so there the device is matched by its last two bytes. The status header
  has the adapter's state as `state: ON` up to API 36 and as
  `State:         ON` since API 37.
- The device usually appears before A2DP connects, so since D23
  `DEVICE_APPEARED` is a standalone event and the service applies nothing
  until A2DP connects. On API 31/32 it appears when its BLE advertisements
  are seen, even without any connection. On the S24 it's seen even with the
  phone's Bluetooth off, and appears and disappears every 15–20 s (C13, D20).
- Only a full reconnect brings the codec back to the phone's own choice: the
  headset going away (a Qudelix `reboot` or `cycle`) or the phone's Bluetooth off and on.
  After an A2DP-only reconnect (`no-a2dp`, `normal`) the stack keeps the codec
  that was set before.
- Opening the main screen logs the current codec as
  `ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE` when it differs from the last one
  `CodecApplier` knows for the device. That knowledge only lasts for the
  process, so the first start in a new process always logs one, and the
  baseline starts a new process. Tests expect it instead of ignoring it.
- The baseline links the headset, so the companion dialog only appears in tests
  that unlink it first (J3, J6).

## 4. Test levels

| Level | Name | Runs on | Stimulus | Oracle | Snippet | Size, time |
| --- | --- | --- | --- | --- | --- | --- |
| L0 | Unit | JVM | Function calls | Return values | – | ~50 tests, seconds |
| L1 | Component | JVM, Robolectric | Fake Bluetooth, fake time, SDK levels 28–36 | State, log events | – | ~55 tests, < 1 min |
| L2 | UI | JVM with Robolectric, and every phone | Fake screen state, clicks | Semantics tree, callbacks | – | ~90 tests, ~1 min on the JVM, a few minutes per phone |
| L3 | Bluetooth logic | Phones | Snippet, adb, headset | Log, `dumpsys`, snippet | yes | ~15 tests, ~10 min per phone |
| L4 | Background and cold start | Phones | adb, headset | Log, `dumpsys` | no | ~13 tests, ~15 min per phone |
| L5 | Journeys | Phones | UI, headset | `dumpsys`, log, preferences, UI | no | ~12 tests, ~15 min per phone |
| – | Harness self-tests | Host, Python | Recorded dumps and logs | Parsed values | – | seconds |

L0 to L2 need no phone and run on every build, later also in CI. L2 also runs
on each phone (D2, [section 7](#7-ui-tests-on-the-jvm-and-the-phones)). L3 to
L5 run on the testbed, one phone at a time, each test starting from the
baseline ([6.2](#62-baseline-state)).

In the device catalogs, "Runs on" gives API levels, not phones. A test runs on
every phone that matches and asserts the behaviour of that phone's API level.
"Needs" lists phone capabilities ([section 2](#phone-capabilities)) and headset
capabilities ([6.3](#63-headsets)); without them the test is not applicable.

Priorities: **P1** first, they cover the core promise and the bugs already
found; **P2** next; **P3** when there's time.

### L0 Unit tests

Pure JVM. Three test files exist; `ExampleUnitTest` and
`ExampleInstrumentedTest` go.

| ID | Test | Prio |
| --- | --- | --- |
| U1 | `toAndroid`/`fromAndroid` for every codec and option, including values the stack reports but the app never writes (LDAC `codecSpecific1` above 13, AAC below 128, Opus `% 10`) | P1 |
| U2 | `isFulfilledBy`: options at the system default accept any reported value, explicit options must match, a different codec, rate or size never fulfils. Regression test for manual 1.1 | P1 |
| U3 | `equals`/`hashCode` through `toAndroid`, clones are independent (the chips modify clones) | P2 |
| U4 | `describe()`: LDAC fixed bitrates per sample rate family, unknown rate and size, AAC and Opus options | P2 |
| U5 | Serializers: unknown Hz and bits read as NONE, constant names, numeric fallback, unknown names throw | P2 |
| U6 | `EventLog` functions: `trim` at the boundary, interleaved operations | P2 |
| U7 | `LogExport`: names containing other names, lower case addresses in messages | P3 |

Codec selection and the log texts stay in the activity files (D1), so they're
tested through the UI, in V6, V8 and V9.

### L1 Component tests

JVM with Robolectric, for `Handler`, `SharedPreferences`, files, services and
`@Config(sdk = …)`. Bluetooth is replaced by a fake behind a small interface
(D1). Robolectric's `ShadowPackageManager` can remove the companion feature,
which no real phone can. These tests stay on the JVM: they depend on SDK
levels and fakes that a phone can't provide.

| ID | Test | Prio |
| --- | --- | --- |
| K1 | `CodecApplier` resolutions: `CODEC_VERIFIED` on attempt 1 and N after two matching reads, a codec changed back after the first matching read set again (D19), `MAX_ATTEMPTS` after 5, `NOT_CONNECTED`, `NO_ASSOCIATION` (34, 36), `NO_PERMISSION` (31+), `NO_PREFERRED_CODEC`, `PROFILE_UNAVAILABLE` (Bluetooth off, no proxy), `PROFILE_DISCONNECTED`, `SECURITY_EXCEPTION`, hand-back to `onSecurityException`, `ERROR` | P1 |
| K2 | `CodecApplier` coordination: `ALREADY_PENDING`, `SUPERSEDED` with `pending` never showing the device idle in between (regression test for manual 1.5), cancel by device and by operation, two devices with their own timing, `onDone` exactly once per request, `knownCodec` is the last read (or observed codec) by the time the request ends | P1 |
| K3 | `CodecApplier` proxy lifecycle: requested once, closed on disconnect, requested again by the next request (manual 2.3) | P2 |
| K4 | `A2dpConnectReceiver`: ignores other actions, states and devices without a preferred codec; deadline resolves `TIMEOUT`; an exception resolves `ERROR`; the broadcast finishes once, after the flush; notes when a saved device connects and disconnects, but not a failed connection attempt | P1 |
| K5 | `CodecCompanionService`: no preferred codec registers no receiver; appearing registers the receiver once and applies nothing, even with A2DP connected (D23); A2DP connecting applies only for present devices; disappearing cancels; `onDestroy` unregisters; a restarted service knows no present devices (see C3); since API 36 a device is present while any source reports it (D18) | P1 |
| K6 | `CompanionPresence` and `PermissionUtil` on SDK 28, 31, 32, 33, 34, 36, with and without the companion feature: observing by address and by id since 33 and nothing before, `syncAll` repairs, the receiver enabled on every level and the service since 33, `isAssociationRequired(ForAutoApply)`, on 31–32 a link only while the app has none and isn't exempt from auto revoke, address case | P1 |
| K7 | `EventLog`: structure violations throw in debuggable builds, append and rewrite at 2 × `MAX_EVENTS`, repair with `OPERATION_INTERRUPTED` on load, `clear` keeps open operations, `flush` order, partial lines; disabling deletes the log, stays disabled after a restart and keeps operations that span it out of the log; loading on the background thread: `events` is null until loaded, events logged meanwhile follow the loaded ones, numbered on, `clear` and disabling meanwhile apply to the loaded events, `awaitWritten` waits for the thread | P1 |
| K8 | `SavedDeviceStore`: invalid JSON reads as none, address case; only saved devices have info, updating it keeps the values not given, removing a device removes both tiers and restoring puts them back; before the unlock the info can be written but not read | P2 |
| K9 | Direct boot: while the user is locked the log appends to its device protected file and can't be read; unlocking merges those events after the old ones, numbered on, also when reading notices the unlock, also across locked restarts; the unlock broadcast merges them without another event or read, its receiver is only registered while locked and unregistered once the load is queued; the device protected file stays until the merged log is written; operations left open by an earlier process are interrupted, those of this process continue; trimming and partial lines; disabled logging and the preferred codecs are known while locked | P1 |

The main screen's own logic stays in `MainActivity` (D1, D13), with its own
profile proxy. Its cases are covered on the phones: the permission states by
V1 and J1, logging observed codecs by B3 and B11, read failures by B8,
saving, changing and deleting saved devices and their info by J10 and J11,
and the ways out of the empty states, the system's dialog that turns
Bluetooth on and the Bluetooth settings, by J12.

### L2 UI tests

Compose tests of the screens with fake state, no Bluetooth. They need the
composables to take their state and callbacks as parameters (D1). Assertions
use texts, content descriptions and test tags, not positions, so they survive
the UI polish. The same tests run on the JVM and on every phone
([section 7](#7-ui-tests-on-the-jvm-and-the-phones)).

| ID | Test | Prio |
| --- | --- | --- |
| V1 | Permission screens: "Grant permission", and after a permanent denial the settings hint with "Open settings" | P2 |
| V2 | Collapsed card: chips for codec, rate, size, LDAC adaptive or kbps; "Unsupported codec"; "No codec information" | P1 |
| V3 | Collapsed card: the "Preferred Codec" chip after the codec's chips while the preferred codec is active, none when the codec is unreadable or unknown | P1 |
| V4 | Expanded card's preferred codec actions, only what's available: a wide Save with nothing saved, "Active" and Clear while it's active, Save, Restore and Clear otherwise, Restore and Clear for an unknown codec; saving passes the current codec. On a small phone with the largest font the one-slot buttons only show their icons and keep their names (JVM only). The device menu only in debug builds, for unlinking a linked device | P1 |
| V5 | Linking: an unreadable codec's card doesn't expand and always shows its text and Link Device; a readable codec without a link (the API 34–35 state) has the "Not linked" chip collapsed and, expanded, the banner without a button, with its own text when a preferred codec is saved; without the companion feature the "can't link" texts and no button; Link Device spins while its device links and waits while another one does, and the chip says "Linking…" | P1 |
| V6 | Expanded card and codec selection: chips enabled from the capabilities, the selected chip, the LDAC bitrate row. Choosing a codec keeps the current rate and size if the new codec supports them, otherwise takes the first supported ones, preferring the capability that supports both; the codec passed to the callback | P1 |
| V7 | Several devices, long names | P3 |
| V8 | Log texts, with the sample log: the text of every event type, both validation texts, duration thresholds, interrupted operations | P1 |
| V9 | Log structure: day headers, grouping of interleaved operations and of elements whose trigger was trimmed, standalone rows, "in progress", empty state, expand and collapse, JSON on long press, expanded state kept while scrolling | P1 |
| V10 | Log screen updates while it's open | P2 |
| V11 | Clear dialog (open operations stay), share dialog (the chosen mode is exported), disable logging dialog and enabling again; an opened log file is read only, shows nothing while loading and has its own empty text, and Open Log is only in debug builds | P2 |
| V12 | Screenshots in light and dark, font scales, landscape, screen sizes; after the UI polish (D5) | P3 |
| V13 | About screen: reached from the main screen's menu; the version, and the source code, license and third-party licenses items open their targets; the build collected the third-party licenses | P2 |
| V14 | Empty states: Bluetooth off asks to turn it on, after the permission screens; no audio devices offers the Bluetooth settings, but not while the profile is still connecting; the Saved list with nothing saved leads back to the connected devices, and with something saved has no empty state | P2 |
| V15 | Saved devices list: each saved device with its preferred codec, and the Connected chip while it's connected; expanded, View device for a connected one, which opens its expanded card in the Connected list, otherwise when it was last connected (its text on the JVM only, `LastConnectedTextTest`) and Delete, which the snackbar can undo; the chips change the preferred codec, only to values the device offered; without the permission the list asks for it, with Bluetooth off it still shows | P1 |
| V16 | Top bar and lists: the title names the list and opens the switch, which shows either list; a swipe moves to the other one and the switch's selection with it; cards start collapsed, and each list keeps its expanded cards while the other shows (the bar on the JVM only, `MainTopBarTest`) | P2 |
| V17 | A pending codec change: the chips show its target, the rows the device doesn't report yet as pending, and the bitrate left to the system as reported; choices build on the target; the banner and Save compare with it | P2 |

### L3 Bluetooth logic tests

On the phones, with the app snippet loaded. The headset is connected over A2DP
unless a test says otherwise.

| ID | Test | Runs on | Needs | Prio |
| --- | --- | --- | --- | --- |
| B1 | Read contract: the codec the app reads equals `dumpsys`, the capabilities include the active codec | all | | P1 |
| B2 | Apply each codec type the phone offers, parametrized from the capabilities: verified, `dumpsys` agrees. Then every offered rate, size and LDAC bitrate | all | | P1 types, P2 the rest |
| B3 | Multi-step change with the main screen open: from LDAC 96 kHz, 32-bit, adaptive to 44.1 kHz, 24-bit, 909 kbps verifies within 5 attempts, and the intermediate codecs aren't logged as external changes (manual 1.3) | all | LDAC | P1 |
| B4 | A target outside the capabilities resolves `MAX_ATTEMPTS` after 5 attempts and leaves the codec as it was | all | | P2 |
| B5 | `ALREADY_PENDING` and `SUPERSEDED` with the real stack's timing (manual 1.4, 1.5) | all | | P2 |
| B6 | A disconnected device resolves `NOT_CONNECTED` | all | A2DP switch | P2 |
| B7 | The phone's Bluetooth off resolves `PROFILE_UNAVAILABLE`; off during a request `PROFILE_DISCONNECTED`, or `NOT_CONNECTED` when the device's connection goes before the profile, which the S24 does in some runs; back on, the next request opens a new proxy and verifies (manual 2.3) | all | | P2 |
| B8 | Links, with the main screen open. Without a link: up to 33 reading and changing work; on 34–35 changing resolves `NO_ASSOCIATION`; on 36 the codec is also unreadable, and the screen logs `READ_FAILED` once. With a link everything works | all | | P1 |
| B9 | Observing: on with a preferred codec, off when it's deleted, and after clearing app data the next start turns it off (whether the link stays is recorded, 6.7). API 31–32 show observing nowhere, C1 and C3 depend on it there | 33+ | | P2 |
| B10 | A device disappearing during a request resolves `CANCELLED` | 33+ | power switch | P3 |
| B11 | A codec set bypassing the applier, with the main screen open, is logged once as `ACTIVITY_OBSERVED_EXTERNAL_CODEC_CHANGE`; the app's own changes never are (manual 2.2) | all | | P2 |
| B12 | A request while the service or receiver applies the preferred codec on connect supersedes it, and the requested codec wins. The preferred codec is one the headset doesn't offer, which keeps the background operation open for its 5 attempts; B7 and C4 use the same trick | all | | P2 |

### L4 Background and cold start tests

Without the snippet and without touching the UI between the setup and the
check. Each test starts from a defined process state: killed like the system
does, never launched, force-stopped, after a reboot or after an update.

| ID | Test | Runs on | Needs | Prio |
| --- | --- | --- | --- | --- |
| C1 | App process killed, the headset reconnects: the preferred codec is applied, by the service from 33 and by the manifest receiver on every level (D17). The receiver notes the disconnect and the connect for the Saved devices list | all | | P1 |
| C2 | The same with the app in the foreground and in the background but alive (manual 2.1), and the receiver notes the connect | all | | P1 |
| C3 | App process killed while the device is present but A2DP is down, then A2DP connects: records whether the codec is applied. The service keeps the present devices in memory, and the system may restart it without calling `onDeviceAppeared` again. Characterization (D8) | 33+ | A2DP switch | P1 |
| C4 | Process killed during an operation: the next start resolves it `OPERATION_INTERRUPTED`, nothing stays unresolved | all | | P2 |
| C5 | Permission revoked, the device connects: its appearance is logged since 33, nothing is applied, anything that tries resolves `NO_PERMISSION`, no crash (D23) | 31+ | | P2 |
| C6 | No preferred codec: the codec isn't touched, nothing is logged and nothing is noted for the Saved devices list; the manifest receiver starts the process and returns | all | | P2 |
| C7 | After a reboot, without opening the app: applied | all | no lock screen | P2 |
| C8 | After an update (`install -r`), without opening the app: applied | all | | P2 |
| C9 | Never launched (fresh install) and force-stopped, then the device connects: records what happens. Characterization (D8) | all | | P2 |
| C10 | The phone's Bluetooth turned off and on while the app is dead, then the device connects: applied | all | | P3 |
| C11 | 20 reconnects in a row: every one verified, time to verified recorded | all | | P3 |
| C12 | Doze and the `rare` and `restricted` standby buckets: still applied | all | | P3 |
| C14 | After a reboot with a secure lock screen, A2DP connects before the first unlock: applied within 60 s of the unlock, without another connect; the time is recorded. The receiver noted the connect before the unlock | all | a lock screen the suite can unlock | P1 |
| C15 | After the same reboot, the system reports the device to the companion service: after the unlock, and when it next connects | 33+ | a lock screen the suite can unlock | P1 |
| C16 | After the same reboot, the device goes away and the app's process ends before the unlock: the unlock merges nothing, opening the main screen without a device merges the locked events. The disconnect before the unlock is noted, and the main screen reads the saved devices' info, which only works once the user is unlocked, without failing | all | a lock screen the suite can unlock | P1 |

C7, C14, C15 and C16 are in `tests/reboot_test.py`, which runs last (6.9).
C14 to C16 check that the scenario happened: the user is still
`RUNNING_LOCKED` (`am get-started-user-state`) when A2DP has connected. If
A2DP doesn't connect before the unlock, they reconnect the headset once; the
connect fixture's last resort, the Bluetooth settings, is behind the lock
screen.

Every reboot is an unattended one (`sim_helper.unattended_reboot`), so a
SIM with a PIN unlocks by itself: the phone stores the SIM PINs before the
reboot and enters them before the first unlock. A reboot while the user is
still locked, C16's second one, is a plain reboot: the preparation of the
first reboot stays valid until the unlock. Below API 30, which has no
unattended reboot, only a phone without a SIM is rebooted. The unlock
first unlocks the storage with `locksettings verify`, waits until every SIM
is loaded or absent, waits another 5 s for the lock screen to catch up, and
only then types the password; a password typed while the lock screen asks
for a SIM PIN would count as a wrong SIM PIN.

A test ends with the phone unlocked and, on a pure test device, the PIN
cleared again; the next test runs in the same boot (D26).

C14 usually merges the log through the receiver the log registers for the
unlock, since the process that applied the codec still runs then. C16
covers the main screen's merge: the codec is applied before the unlock,
dumpsys shows it, and the process is ended with `am kill`, which only
succeeds once the receiver's broadcast is finished, so after its operation
is resolved and written. Then the device goes away, and the process the
disconnect's broadcast starts is ended too. Since API 35 the phone is
rebooted once more instead, still locked: the companion device manager
keeps the presence events it holds back in memory, and queues an
appearance for a BLE device lost while locked, which would start the
process at the unlock. After the unlock no process may start for 15 s, the log stays
unchanged and the device protected file still has the locked events. Its
lines will be encrypted, so only its line count is checked. Opening the
main screen with no device connected then merges that many events, dated
before the unlock, the receiver's operation resolved `CODEC_VERIFIED`, and
deletes the file. If a process starts at the unlock, the failure names
why, from `am_proc_start`.

Before the first unlock the app's credential protected storage is
encrypted. The manifest receiver and the companion service are direct boot
aware, so the system starts them anyway; the preferred codecs are in device
protected storage, and the log keeps its events in a device protected file
until the unlock merges them (K9). Per API level, from the AOSP sources and
the runs on the testbed:

| API | Before the unlock | At the unlock | C14 | C15 | C16 |
| --- | --- | --- | --- | --- | --- |
| 28–32 | The receiver applies the codec when A2DP connects | The log merges the locked events | passes (28, 32) | – (no service before 33, D27) | passes (28, 32) |
| 33–34 | The receiver applies it; the service is found while locked and told when the device appears | – | not on the testbed | not on the testbed | not on the testbed |
| 35–36 | The receiver applies it; all presence events held back | Replayed: the service is bound | passes (36) | passes (36) | passes (36), with the second reboot |

Without direct boot awareness, the manager looked the service up while the
user was locked and kept the empty result on API 31–33, and nothing applied
the codec after the unlock.

On the S24, Samsung's freezer freezes the app's process about 3 s after it
starts in the background, even while the companion device manager has the
service bound, until a broadcast or a new binding thaws it. When the process
only started at the unlock, it was frozen before it handled the replayed
presence event, and C15 missed the appearance by a second. Since the
receiver runs before the unlock, the process is usually ready at the unlock
and handles the event in time.

### L5 Journeys

Through the UI with UiAutomator, which runs in its own process (D4). They
check that the parts work together, not every detail on the way.

| ID | Journey | Runs on | Prio |
| --- | --- | --- | --- |
| J1 | First start: grant the permission in the system dialog, the card shows the codec `dumpsys` reports. Denying twice shows the settings hint | 31+ | P2 |
| J2 | Choose codecs with the chips: `dumpsys`, the card and the log agree (manual 1.1) | all | P1 |
| J3 | Save as preferred; on 33–35 the device starts unlinked, so the companion dialog appears and is accepted (on 34–35 already when choosing the codec); the banner says the preferred codec is active and the collapsed card has the "Preferred Codec" chip; after a reconnect the preferred codec is active and shown | all | P1 |
| J4 | A codec change by another app shows on the card within 2 s; the banner's Restore brings it back (manual 1.2, 2.2). The snippet's `setCodecDirectly` makes the change, the same call the developer options make: their screens differ too much between the phones, and the API 28 Pixel doesn't open them from their intent | all | P2 |
| J5 | Delete the preferred codec with the banner's Delete: the card offers Save as preferred again, without Restore or the "Preferred Codec" chip, and after a reconnect the codec is left alone | all | P2 |
| J6 | An unlinked device asks to be linked (on 36 because its codec is unreadable, on 34–35 once it has a preferred codec); Link Device and the dialog make its codec appear and changeable | 34+ | P1 |
| J7 | Rotating while a codec is applied: no crash, verified (manual 1.6) | all | P2 |
| J8 | The log screen shows a real reconnect with its attempts; the anonymised export contains no addresses or names (log 2.5) | all | P2 |
| J9 | On 31–32, unlinked and not exempt from auto revoke (`appops`), saving asks for the link and the dialog is accepted: `AUTO_REVOKE_PERMISSIONS_IF_UNUSED` is `ignore`. After unlinking it stays `ignore`, and saving again asks for no link (D27) | 31–32 | P1 |
| J10 | A saved device in the Saved devices list: connected, its card has the Connected chip and View device opens its expanded card in the Connected list; away, the banner says when it last connected, in English on any phone (the S24 runs in German), and its chips change the preferred codec; the next connect applies the new one | all | P1 |
| J11 | Delete a saved device that isn't connected and undo it: its preferred codec, its info and, on 33+, observing come back; deleted again without undo, nothing is stored or observed and the Saved list is empty | all | P2 |
| J12 | Empty states with the headset off: with Bluetooth off the screen is asleep, Turn on Bluetooth and the system's dialog turn it on; without devices Open Bluetooth settings opens them; back in the app, the headset switched on shows its card. Needs the power switch | all | P2 |

## 5. Where journeys are worth it

Journeys are slow and break with UI changes, so they're kept to what only they
can show:

- **System UI the app depends on**: the permission dialog, the companion link
  dialog, the dialog that turns Bluetooth on, the Bluetooth settings and their
  results. No other level sees them.
- **Closing the loop**: a choice on the screen changes the codec on the device,
  and the device's codec shows on the screen. One journey per direction is
  enough to prove the wiring.
- **The core promise from the user's view**: save, reconnect, the codec is
  there (J3), also when the preferred codec was changed in the Saved devices
  list while the device was away (J10).

Everything else is covered more cheaply and more thoroughly elsewhere:

| Behaviour | Covered by | Why not by journeys |
| --- | --- | --- |
| Every codec, rate, size and bitrate on the device | B2 | Through the snippet in seconds, through the UI in minutes per combination |
| Retries, supersede, pending, timeouts | K1, K2, B3–B5 | Timing can't be controlled through the UI; the fake makes it deterministic |
| Which chips, menus and prompts a device state shows, and which codec a chip selects | V2–V6 | Needs device states the testbed can't produce (API 34–35, no companion feature, unknown codec) |
| Which saved devices the Saved list shows and how, and the empty states | V14, V15 | Needs states the testbed can't hold, like a device last connected days ago or several saved devices; J10–J12 check the wiring |
| A pending codec change in the chips | V17 | The pending window is a second or two, too short to inspect through UiAutomator |
| Log texts for every event type | V8, V9 | Needs events that real Bluetooth rarely produces; the sample log has all of them |
| Applying on connect in the background | C1–C9 | There must be no UI or snippet involved |
| Anonymisation | `LogExportTest`, J8 once | Pure logic |

## 6. Harness

### 6.1 Layout

```
mobly_tests/
  testbed.yaml            phones and their capabilities, headsets
  run_suite.py            runs the suites one phone at a time, for one headset
  lib/
    device_state.py       exists: Bluetooth, screen, lock state
    lock_screen.py        lock screen classes, the PIN 0000, unlocking like a user
    baseline.py           checks and restores the baseline state
    headset.py            the Headset controller and its backends
    app.py                install, verify the build, reset, launch, kill, permissions
    app_log.py            read, parse and wait for events, labels, invariant checks
    bt_oracle.py          A2DP connection and active codec from dumpsys, per API level
    companion.py          links and observing, from cmd companiondevice and dumpsys
    screens.py            screen objects for the main and log screens
  tests/
    bt_logic_test.py      L3
    cold_start_test.py    L4
    reboot_test.py        L4 tests that reboot the phone
    journey_test.py       L5
    harness_test.py       self-tests against testdata/
  testdata/               recorded dumpsys output and logs
snippet/                  the app snippet module (section 8)
app/src/sharedTest/       the UI tests, for the JVM and the phones (section 7)
```

### 6.2 Baseline state

Every Mobly test starts from the same state and leaves it behind. It's the
state of a user who has set the app up and listens with the headset, but
hasn't saved a preferred codec:

| Part | Baseline | Checked with | Restored by |
| --- | --- | --- | --- |
| Build | The build under test, its version stamp matches the working tree | `dumpsys package` | Installing, only in the suite setup, never between tests |
| App data | Log empty, all settings at their defaults, so no preferred codecs and no saved devices' info | `run-as` reading the log and the preferences files | Resetting the app data, see below |
| Package | Not stopped, so manifest receivers get broadcasts | `dumpsys package` | A broadcast to `TestLabelReceiver` without a label |
| Nearby devices permission (31+) | Granted, not marked user-set or user-fixed | `dumpsys package` | `pm grant` after the reset, which clears the flags |
| Link | The headset is linked on every phone, also where the app doesn't need it; nothing is observed | `cmd companiondevice list`; observing from `dumpsys companiondevice` on 33+. API 31–32 show observing nowhere, not even in the system's file | `cmd companiondevice disassociate` and `associate`, which also ends observing. The app doesn't observe on 31–32 (D27) |
| Phone Bluetooth | On; every other phone on adb off | `device_state` | `device_state.set_bluetooth` |
| A2DP | The headset is connected and the active device | `bt_oracle` | The connect fixture, [6.6](#66-connecting-the-headset) |
| Headset | Its normal state (Qudelix: `normal`) | The backend | The backend |
| Codec | The phone's own choice: what the stack picks on connect when the app has no preferred codec, recorded per phone in `setup_class` (D11) | `bt_oracle` | A full reconnect, the headset's `reconnect()` |
| Screen | On and unlocked, the launcher in front | `device_state` | Home key; on and unlocked are only checked (D15) |

Not part of the baseline:

- Whether the app process runs. Tests that care set it: killed, in the
  background, in front. Resetting the app data ends it, so no state survives
  in memory.
- Settings a test changes for itself, like Doze or the standby bucket. That
  test puts them back in its own teardown.

**Checking and restoring.**

- `setup_class` brings the phone into the baseline: it checks the build, then
  restores everything else.
- Before each test, `baseline.check()` reads every part. They're all shell
  reads, about 1.5 s together, and none of them starts the app process.
- After each test, `baseline.repair()` puts back the parts that differ. It
  doesn't check again, since the next test's check follows right after;
  `baseline.restore()` is a repair followed by a check.
- A difference found before a test means the previous test's restore missed
  something. It's repaired, and reported as a leak against that test. If the
  repair fails, the rest of the class is aborted instead of failing every test
  for the same reason (D12).
- The order of a restore matters: app data first, so the reconnect doesn't
  apply a preferred codec; then permission, link and package; then the phone's
  Bluetooth, the headset and A2DP; then the codec; the screen last.

**Resetting the app data.** `pm clear` resets everything the app stores,
including settings added later, and ends the process in the same step. It
also revokes the permission and clears its flags, and doesn't stop the
package. On API 28 and 32 it keeps the link and observing; on API 36 it
removes the link. So the restore relinks first, which ends observing and lets
the reset delete anything relinking made the app log, then resets the data,
links again where the reset removed the link, and grants the permission. The
check runs again after it.

The L3 tests load the snippet in their setup, after the baseline check, and
unload it in their teardown, before the restore. Loading it restarts the app
anyway, and this way the restore works the same for every test. The
snippet's `resetForTest` is there for tests that need a clean state in the
middle.

### 6.3 Headsets

Most connect and disconnect tests can be driven from two sides: by the headset
itself (the Qudelix over USB), or by the phone (its Bluetooth adapter over adb),
which works with any headset. Running the tests with another headset is useful
to diagnose a problem with that headset, so the harness abstracts it.

`headset.py` is a Mobly controller module (`MOBLY_CONTROLLER_CONFIG_NAME =
"Headset"`). Tests only use its interface:

| Operation | For the app | Qudelix backend | Phone backend (any headset) |
| --- | --- | --- | --- |
| `reconnect()` | The device goes away and comes back, like switching it off and on | `normal`, then `reboot` (D21) | The phone's Bluetooth off and on |
| `disconnect_a2dp()`, `connect_a2dp()` | A2DP goes down and up, the device stays present; the codec stays as it was | `no-a2dp`, `normal` | – |
| `power_off()`, `power_on()` | The device disappears and appears, the phone's Bluetooth stays on | `bt-off`, reset to `normal` | – |
| `ensure_connected()` | The connect fixture, 6.6 | | |
| `check()`, `restore()` | Its part of the baseline | Mode is `normal` | Nothing to check |
| `address`, `name` | | From `testbed.yaml` | From `testbed.yaml` |

The rows with a dash are the headset capabilities **A2DP switch** and **power
switch**. A test that needs one is not applicable with a headset that lacks it. With
the phone backend, `reconnect()` also turns the app's profile proxy off and on;
tests whose subject is the phone's adapter (B7, C10) call `device_state`
directly and run with every headset.

**Separate testbeds, not separate tests.** The headset is part of the testbed
configuration, and the same tests run with each:

- The tests are about how the app reacts when a device connects. How that's
  caused is hardware, which belongs in the testbed config.
- Separate tests would duplicate every connect test per backend, and every run
  would need both kinds of hardware.
- A new headset is a new entry in `testbed.yaml`, no code. Its results are
  reported under its own testbed name, which is what diagnosing it needs.
- What only one backend can do is either a capability (the test is not
  applicable elsewhere) or a test of its own (the phone's adapter: B7, C10).

```yaml
phones:
  - serial: 0000000028
    pure_test_device: true
  - serial: 0000000032
    pure_test_device: true
  - serial: R0000000036
    lock_password: "…"
headsets:
  qudelix:
    backend: qudelix
    address: "0A:1B:2C:3D:4E:5F"
  my-headphones:
    backend: phone
    address: "…"
```

`run_suite.py --headset qudelix` writes a Mobly config per phone, with that
phone and that headset, and names the testbed after both.

### 6.4 Waiting

Tests poll with a timeout instead of sleeping. The timeouts come from the app's
constants: a request verifies or gives up within about 3 s once the profile
proxy is connected (5 attempts, 500 ms apart; a codec counts as applied after
two matching reads, so at least 1 s), the receiver gives up after
15 s, the main screen reads the codec every second. On API 32 a device
disappears 7–8 s after its BLE advertisements stop. A Qudelix reconnect is a
reboot; it answers on USB again after about 4 s. A timeout fails the test with
the log tail and the current `dumpsys` state.

The unlock after a reboot waits a fixed 5 s before typing the password:
the lock screen has no state adb can poll for being ready to take it, and a password typed too early fails the unlock,
which isn't retried (D24).

### 6.5 Log assertions

`app_log.py` returns operations, not lines:

```python
mark = log.label("C1 cold connect")          # not inside cold start windows
headset.reconnect()
op = log.wait_for_operation(after=mark, trigger="A2DP_CONNECTED", device=headset.address, timeout=30)
assert op.resolution == "CODEC_VERIFIED"
assert bt_oracle.active_codec(ad, headset.address) == preferred
```

After every device test the whole log is checked for invariants: every
operation starts with its trigger and has at most one resolution, `seq`
increases, nothing stays unresolved, no unexpected `OPERATION_INTERRUPTED`,
and the crash buffer of Logcat has nothing from the app. A structure violation
throws in debug builds, so it shows up as a crash. The structure check already
exists in [sample_event_log.py](tools/sample_event_log.py) and moves to the
shared library.

### 6.6 Connecting the headset

"A2DP connected to the phone under test" is the hardest part of the baseline.
The harness assumes a headset can only connect to the phones on adb (D6), and
`testbed_session.py` keeps all of them with Bluetooth off until a test turns
it on for the phone under test. The fixture:

1. Checks `dumpsys`, done if connected.
2. Calls the backend's `reconnect()`, then waits.
3. Taps the headset by name in the phone's Bluetooth settings, through a
   UiAutomator dump and `input tap`, until it connects (D16).
4. Fails with diagnostics.

The sanity checks (6.9) make sure step 2 works for a phone before its tests
start.

When Bluetooth turns on, a Pixel only connects on its own to its most recently
active A2DP device, and after a reconnect the Qudelix only reconnects to the
phone it served last. After one connect from its Bluetooth settings, turning a
Pixel's Bluetooth on connects within 6–8 s, whichever phone the Qudelix served
before.

The S24 doesn't connect on its own when its Bluetooth is turned on over adb
while the Qudelix last served another phone, like after a Pixel's run; a
reconnect of the headset doesn't connect it then either, since the Qudelix
only reconnects to the phone it served last. So after a Pixel's run, step 3
connects it from its settings. Once it was the last phone, it reconnects like
the Pixels, but tests don't rely on either: J12 waits for the headset that
was just switched on, which reconnects to the phone it served last, the one
under test. After a
reconnect the Qudelix pages its last phone for up to a minute and refuses
others, so the taps go on for 90 s. The fixture waits 15 s after Bluetooth turns on,
then reconnects the headset once, then taps; the sanity check uses the same
fixture. On the S24 after a Pixel's run it took 99 s.

Fixture steps like these are retried; assertions never are. A test that fails
is rerun by hand or with a flag, and tests known to be flaky are listed with
their cause instead of being retried silently.

### 6.7 Characterization tests

C3 and C9 assert how the app and the system behave today, as the baseline
for later changes (D8). The same goes for any other test whose outcome on a
phone isn't known in advance, like what `pm clear` does to the link in B9.

Each starts as an observation. The first run on each phone records what
happens: the operations in the log with their resolutions, and `dumpsys`. That
result is written into the test as its expectation, keyed by a range of API
levels and, where it differs, by headset backend. The latest range is open
ended: a newer Android version is expected to behave like the latest one
recorded. A later run that differs fails, so a change in the app or a new
Android version shows up as a difference someone decides about. The component tests (K5) describe the same behaviour from the
code's side.

### 6.8 On failure

Collected per test: the event log, Logcat since the test started, the A2DP part
of `dumpsys bluetooth_manager`, `dumpsys companiondevice`, a screenshot and a UI
dump, and the headset's status.

### 6.9 Running

Prepared once per phone, outside the suite: developer options and USB
debugging on, the headsets paired with the phone; the connect fixture
connects them (6.6). On phones with Google Play Protect, its option to send
unknown apps to Google for a scan is off: otherwise installing a new build
waits on its dialog until the install times out. It's off on the S24. On
Samsung phones "USB-Verbindungen blockieren, wenn gesperrt" (Sicherheit und
Datenschutz > Weitere Sicherheitseinstellungen, the secure setting
`block_usb_lock`) is off: otherwise adb stays blocked after a reboot until
the phone is unlocked, and the locked reboot tests can't see the phone before
the unlock. It's on by default, and `settings put` refuses the key, so it's
turned off by hand. A SIM with a PIN has had its PIN entered by hand once
since the phone's last boot, so the unattended reboot can store it. Saving and restoring a phone's own app data,
like the S24's, is outside the suite too (D7).

1. Start `testbed_session.py` in one terminal.
2. `run_suite.py --headset qudelix` runs, on each phone on adb, one after the
   other: the sanity checks, Bluetooth on, the L2 tests (section 7), the
   journeys, then L3 and L4 with the reboot tests last, Bluetooth off. The
   journeys need the screen on, so they run before the tests that turn it
   off or reboot.
3. Every phone runs every test its API level and capabilities allow. A suite
   passes when no test failed or was aborted; the tests that are not
   applicable are listed with their reasons (D22).

The suite doesn't change the settings `testbed_session.py` takes care of
(D15). It checks them, and a phone that fails a sanity check fails right away
instead of failing every test:

- The phone is on adb, plugged in, its screen on and unlocked, and
  `stay_on_while_plugged_in` includes USB.
- `block_usb_lock` isn't 1, so adb is up after a reboot before the unlock.
- Before the device suites, not the L2 tests: no other phone on adb has
  Bluetooth on.
- The headset is paired with the phone.
- With the phone's Bluetooth turned on, the headset connects over A2DP.
- The installed build's version stamp matches the working tree.

L0 to L2 on the JVM: `./gradlew testDebugUnitTest`.

## 7. UI tests on the JVM and the phones

The L2 tests are written once and run in two places: on the JVM with
Robolectric on every build, and on each phone during a suite run (D2). The
setup:

1. **One source directory for both.** The tests live in
   `app/src/sharedTest/java`, which `app/build.gradle.kts` adds to both test
   source sets:

   ```kotlin
   sourceSets {
       getByName("test") {
           java.srcDir("src/sharedTest/java")
           resources.srcDir("src/sharedTest/resources")
       }
       getByName("androidTest") {
           java.srcDir("src/sharedTest/java")
           resources.srcDir("src/sharedTest/resources")
       }
   }
   ```

   Android Studio indexes a directory shared by two source sets for only one of
   them ("Duplicate content roots detected"). Gradle builds and runs both; in
   the IDE they show up as unit tests.
2. **Only what exists in both places.** The tests use
   `@RunWith(AndroidJUnit4::class)`, which runs them with Robolectric on the JVM
   and with `AndroidJUnitRunner` on a phone, and `createComposeRule()`, whose
   empty activity comes from the test manifest already in the debug build. No
   Robolectric shadows and no `@Config` in shared tests. The screens get their
   state as parameters, so the UI tests don't depend on the SDK level; the SDK
   level logic is tested in L1, which stays on the JVM.
3. **Dependencies.** `testImplementation` of Robolectric, `androidx.test.ext:junit`
   and `ui-test-junit4` (with the Compose BOM); `androidTestImplementation`
   already has the last two, and `debugImplementation` the test manifest.
   `testOptions.unitTests.isIncludeAndroidResources = true` lets Robolectric
   see the app's strings and themes. Robolectric's SDK 36 needs
   `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21. The SDK
   level of the JVM run goes into
   `app/src/test/resources/robolectric.properties`.
4. **An unlocked phone.** Compose tests need a visible window. The phones are
   unlocked during a run (D15), so no activity that shows over the lock screen
   is needed.
5. **Independent of the phone's clock, time zone and locale.** The phones'
   clocks differ, and their locales may. The log screen takes the current date
   as a parameter, and texts with dates and times are compared with values
   from the same formatter. The sample log is a test resource with fixed times,
   generated once by [sample_event_log.py](tools/sample_event_log.py) and read
   through the class loader, which works on the JVM and in the test APK.
6. **Running on a phone.** `run_suite.py` installs the app and the test APK
   (`installDebug installDebugAndroidTest`) and runs
   `am instrument -w -r com.tt.autobluetoothcodec.test/androidx.test.runner.AndroidJUnitRunner`
   on the phone under test. Not `connectedDebugAndroidTest`: it runs on all
   phones at once and uninstalls the app afterwards, which drops its links.
   `am instrument` force-stops the app, so the baseline restore runs after the
   UI tests.
7. **Screenshot tests** (V12) come after the UI polish (D5): on the JVM with
   Robolectric's native graphics, for example with Roborazzi. On the phones
   the pixels differ per device, so screenshots there are for looking at, not
   for comparing.

## 8. Snippet module

The snippet needs the app's internals: the `CodecApplier` singleton, the
stores and the log (D3). A separate module gives it that:

- `:snippet` uses the `com.android.test` plugin with `targetProjectPath =
  ":app"`. It compiles against the app's classes, and its APK runs as
  instrumentation inside the app's process, with the app's class loader.
  `CodecApplier.get(context)` in the snippet returns the instance the app's
  service and activities use (checked on every phone).
- Only the app's own classes are on its compile classpath. The libraries it
  shares with the app, like coroutines and serialization, are `compileOnly`,
  since they come from the app at runtime. The class loader looks in the
  snippet's APK first, so libraries both contain must have the same version,
  which the version catalog keeps.
- The snippet runs with the app's permissions, and its server needs a local
  socket. The debug build's manifest adds `INTERNET` for that; the release
  build doesn't have it.
- `testInstrumentationRunner = "com.google.android.mobly.snippet.SnippetRunner"`,
  the dependency `com.google.android.mobly:mobly-snippet-lib`, the snippet
  classes in the manifest's `mobly-snippets` meta-data, application id
  `com.tt.autobluetoothcodec.snippet`. Mobly loads it with
  `ad.load_snippet("app", "com.tt.autobluetoothcodec.snippet")`.
- It reaches the app's public declarations. Kotlin's `internal` is visible to
  the app's own test source sets, not to another module. Everything the RPCs
  need is public today (`CodecApplier.apply`, `cancel` and `pending`,
  `A2dpProfileUtil`, `SavedDeviceStore`, `EventLog.get`,
  `CompanionPresence.syncAll`). The reset functions (section 9) are public and
  marked `@VisibleForTesting`.
- A module has one test runner. The app's own `androidTest` keeps
  `AndroidJUnitRunner` for the UI tests, which is the other reason the snippet
  doesn't go there.
- The release build contains none of it; the debug build only adds
  `TestLabelReceiver` and the `INTERNET` permission.

RPCs: `applyCodec` (through `CodecApplier` like a choice on the main screen,
waits and returns the operation and its resolution), `startApplyCodec` (the
same without waiting), `setCodecDirectly` (bypassing the applier, like another
app would), `getCurrentCodec`, `getCapabilities`, `getPreferredCodec`,
`setPreferredCodec` and `deletePreferredCodec` (which observe or stop
observing the device, like the app does; setting also notes the device's name
and, while it's connected, the time and the codecs it offers, like the app's
Save, so the Saved devices list shows it), `getPending`, `syncPresence` and
`resetForTest` (log, stores and singletons back to their defaults). Codecs pass
as the app's `AndroidCodecConfig` JSON. Calls into the app run on the main
thread, like the app's own calls.

## 9. App changes for testability

1. A version stamp in debug builds, for example the commit as `versionNameSuffix`,
   so the harness can tell what's installed.
2. `testTagsAsResourceId` on the root of both screens, and test tags on the
   elements tests use: the device card per address, chips, the preferred codec's chip, banner and
   buttons, menu items, the link button, log entries per `seq`.
3. Composables as top-level functions that take state and callbacks, so UI
   tests can render them with fake data. The activities keep their logic (D1);
   the helpers the composables call move with them (D13). The log screen takes
   the current date as a parameter.
4. `CodecApplier` gets its Bluetooth access through a small interface
   (connection state, read, write, adapter enabled), so component tests can
   replace it. Its `get()` stays as it is for the app.
5. A way to reset the `EventLog` and `CodecApplier` singletons in tests, public
   for the snippet.
6. The snippet module (section 8).
7. `TestLabelReceiver` stays a broadcast receiver: it has to work without the
   snippet, in cold start tests.

When R8 is enabled for release builds, a smoke test of the release build is
needed too: `A2dpProfileUtil` uses reflection and the codecs are serialized by
name, both of which minification can break.

## 10. Implementation order

Each step ends with something that runs and is committed on its own. A step
needs the ones before it unless it says otherwise.

0. **Testbed preparation** (by hand, once): pair every phone with the
   Qudelix and connect it once from the phone's Bluetooth settings. Done.
1. **Spike** — answer the unknowns that change the design, and write the
   answers into this document. Done; the answers are in the sections above
   and in section 12:
   1. Snippet: a `:snippet` module with one RPC (`getPending`) loads on every
      phone and sees the app's instance. What loading and unloading do to the
      process and the stopped state.
   2. Shared UI tests: one Compose test runs with `testDebugUnitTest` and with
      `am instrument` on every phone.
   3. Baseline: what `pm clear` resets besides the data; whether
      `pm clear-permission-flags` works; how to end a process the companion
      service keeps bound (`am kill`, `run-as … kill`); `cmd companiondevice
      associate` on the S24; whether a reconnect brings the codec back to the
      phone's own choice (D11).
   4. Headset: why the Qudelix didn't connect to the API 32 Pixel; whether it
      connects to whichever paired phone has Bluetooth on; whether any adb
      command switches A2DP alone (D14).
   5. Recordings: `dumpsys bluetooth_manager` and `dumpsys companiondevice`
      with the Qudelix connected, from every phone, as test data for the
      parsers.
2. **App changes** in small commits: version stamp; test tags; reset
   functions; the `CodecApplier` Bluetooth interface; composables as top-level
   functions (D13). The composable split happens now,
   not with the UI polish: the UI tests then guard the polish, and since they
   assert texts and tags, not layout, the polish changes few of them.
3. **JVM setup and L0**: Robolectric, the shared source directory,
   `robolectric.properties`; U1–U7; the example tests go.
4. **L1 P1**: K1, K2, K4, K5, K6, K7, K9.
5. **L2 P1 on the JVM**: V2–V6, V8, V9. With this step everything that runs
   without a phone has its P1 tests.
6. **Harness core**, testable without phones first: the `bt_oracle` and
   `companion` parsers with self-tests against the recordings; `app_log` with
   the invariant checks; the `Headset` controller with the Qudelix backend;
   `app.py`; `testbed.yaml`; `run_suite.py` with per-phone configs and failure
   artifacts. Steps 3 to 5 and this one don't depend on each other.
7. **Baseline** (done): the sanity checks, `baseline.py` with check and
   restore, and the connect fixture. A smoke class whose empty tests only run check,
   restore and check on every phone, many times in a row. The baseline has to
   be stable before tests build on it.
8. **L2 on the phones** (done): `run_suite.py --suite ui` runs the shared UI
   tests on every phone; all 38 pass on all three.
9. **Snippet module and L3 P1** (done): the RPCs; B1, B2 (codec types), B3,
   B8 pass on all three phones.
10. **L4 P1** (done): C1, C2 and C3 pass on every phone they apply to; C3
    records what happens per API level.
11. **L5 P1** (done): `snippet-uiautomator`, `screens.py`; J2, J3, J6 pass on
    every phone they apply to. With this step the core promise is covered end
    to end on every phone.
12. **P2 of all levels** (done), including C9, C13 (since removed, D20) and
    the phone backend of the `Headset` controller, checked with the Qudelix as
    `qudelix-phone` on the API 28 Pixel. Every P2 test passes, or is not applicable for its API level or
    headset, on every phone. Every case of the manual test records is
    automated, and they're deleted (D10).
13. **Locked reboot**: the lock screen classes (D24–D26), C14, C15 and C16.
    C14 and C15 pass where they apply since the receiver and the service are
    direct boot aware.
14. **UI polish** (done): the journeys drive the new device card. J10 to J12
    cover the Saved devices list and the empty states on the phones, and C1,
    C2, C6, C14 and C16 check the saved devices' info the receiver notes. They
    pass on all three phones.
15. **P3**; screenshot tests (V12) after the UI polish (D5); the release smoke
    test once R8 is enabled.

## 11. Existing manual tests

| Manual test | Becomes |
| --- | --- |
| Codec applier 1.1 Choose a codec | B2, J2, U2 |
| 1.2 Restore | J4 |
| 1.3 Restore over several attempts | B3 |
| 1.4 Restore twice | B5, K2 |
| 1.5 Choose while restoring | B5, K2 |
| 1.6 Rotate while restoring | J7 |
| 2.1 Reconnect with the app open | C2 |
| 2.2 Developer options change | B11, J4 |
| 2.3 Bluetooth off and on | B7, K3 |
| Not covered: linking before applying, refusal hand-back, receiver deadline, `PROFILE_UNAVAILABLE` | J6, K1, K4, B7 |
| Not covered: the receiver or the service superseded by a choice on the main screen | B12 |
| Not covered: toasts for failed changes | Not automated |
| Log UI 1.1–1.6 smoke tests | V9, V11, J8 |
| 2.1 Load the sample log | K7 |
| 2.2–2.4 Entries | V8, V9 |
| 2.5 Anonymised export | `LogExportTest`, J8 |
| Not covered: clearing, live updates | V11, V10 |
| Not covered: screen sizes, landscape, font sizes, dark mode | V12 (P3) |

The records were deleted with step 12 of the implementation order, once all
their cases were automated (D10). What stays manual: looking at the
screenshots, and the toasts, which no test checks.

## 12. Observations from the testbed

Checked on 2026-10-27, while writing this concept and in the spike (step 1):

- The second Pixel runs API 32, not 31.
- The debug build on the API 32 Pixel predated the shared `CodecApplier`: its
  log has `CANCELLED` events with the message "applier closed", which the
  current code doesn't write.
- On the API 32 Pixel, turning Bluetooth on made the companion device manager
  start a BLE scan filtered on the Qudelix's address. `DEVICE_APPEARED` came
  less than a second later, without a classic connection; `DEVICE_DISAPPEARED`
  came 7–8 s after the Qudelix or the phone turned Bluetooth off.
- Neither turning Bluetooth on nor `qudelix5k.py cycle` connected A2DP to the
  API 32 Pixel or the S24: their most recently active A2DP device wasn't the
  Qudelix. After one connect from the Bluetooth settings, both connect within
  6–8 s of Bluetooth turning on, and a `cycle` reconnects within seconds.
- The phones' own codec with the Qudelix, recorded by the baseline, is LDAC
  96 kHz, 32-bit with the system default bitrate on all three phones.
- The baseline smoke suite (step 7) passes on all three phones: each test
  breaks one part of the baseline, and the next finds it restored.
- Every codec type the Qudelix offers is applied on the first attempt on all
  three phones (B2). The multi-step LDAC change of manual 1.3 takes one attempt
  on the API 32 Pixel and two on the S24 (B3). The S24 picks AAC's variable
  bitrate itself (codecSpecific1 133) when the app leaves it at the default.
- After a `cycle`, the Qudelix keeps paging the phone it served last for up to
  a minute and refuses connections from other phones meanwhile.
- Bug found by C1 on the API 32 Pixel, and fixed: the system binds
  `CodecCompanionService` only for `onDeviceAppeared`, and the process is gone
  within 3 s. The device usually appears before A2DP connects, so nobody
  applied the preferred codec when A2DP connected. The S24 keeps the service
  bound while the device is present. The manifest receiver then stayed
  enabled on API 31 and 32, like on API 28, and since D17 it's enabled on
  every level, so API 33 to 35 no longer depend on keeping the binding.
- C3, recorded as today's behaviour (D8): the device is present without A2DP,
  the app's process is killed, then A2DP connects. On the API 32 Pixel the
  receiver applies the preferred codec. On the S24 the system doesn't start
  the process again; the receiver was disabled there, and the codec wasn't
  applied. That's the risk section 1 names. Since D17 the receiver is
  enabled on every level, and the codec is applied on the S24 too.
- When observing starts while the device's link is already up, the S24
  reports no appearance; there's no change to report. C3 switches the headset
  off and back on without A2DP to get one. BLE sightings on the API 32 Pixel
  took from seconds to over a minute.
- `pm clear` on API 36 also removes the app's companion links.
- On the S24, turning Bluetooth on over adb doesn't reconnect the Qudelix,
  even right after it was connected. A `cycle` does, if the S24 was the last
  phone. It also offers `cmd companiondevice simulate-device-appeared` and
  `simulate-device-disappeared`, which could drive presence without a power
  switch.
- `pm clear` on API 28 and 32: data deleted, process ended, permission revoked
  with its flags cleared, link kept, package not stopped. On API 32 the app
  still got `DEVICE_APPEARED` afterwards, so observing survives it too.
- `pm set-permission-flags` and `clear-permission-flags` don't exist on API 32.
- On the S24 `am kill` didn't end the app while its companion service was
  bound (oom adj 201); `run-as … kill -9` did, without root. The system bound
  the service again 3 s later and delivered `onDeviceAppeared`.
- On the S24 with its Bluetooth off, the companion device manager still sees
  the Qudelix over BLE. The app's log has `DEVICE_APPEARED` and
  `DEVICE_DISAPPEARED` every 15–20 s, each operation resolving
  `PROFILE_UNAVAILABLE`.
- Espresso 3.5.1 fails every UI test on API 37: it calls the removed
  `InputManager.getInstance()`. 3.7.0 works.
- `cmd companiondevice` offers `list`, `associate` and `disassociate` on API 28
  and 32; associating works on 28. Its `list` output differs on API 36 (a
  table with ids and lower case addresses) and on API 37 (each association's
  `Association{…}` record, like `dumpsys companiondevice`). `cmd bluetooth_manager` doesn't
  exist on API 28 and 32; on the S24 it only switches the adapter and its BLE
  mode.
- The snippet loads on every phone, shares the app's class loader, restarts
  the app at foreground priority (oom adj 0), and unloading it ends the
  process without stopping the package. It needed `INTERNET` in the app's
  debug manifest.
- The shared UI test passes with Robolectric and with `am instrument` on every
  phone; `am instrument` doesn't leave the package stopped either.
- `TestLabelReceiver` returns before touching the log when the broadcast has
  no label, but the broadcast still starts the process. That's what takes the
  package out of the stopped state without writing to the log.
- The journeys pass on all three phones. The system's link dialog appears on
  the API 32 Pixel and the S24, in English and German; `screens.py` accepts
  both.
- Installing the debug test APK on the S24 blocked on a Play Protect dialog
  asking to send the unknown app for a scan, until "Nicht senden" was tapped.
  `adb install` has no timeout, so the run hung instead of failing.
- Every rate, size and LDAC bitrate the Qudelix offers is verified on the
  first attempt on both Pixels (B2). API 28 reports the LDAC bitrate as the
  10 to 13 the app set, not 1000 to 1003.
- Turning Bluetooth off during a request resolves it `PROFILE_DISCONNECTED`
  on both Pixels, after 3 or 4 attempts (B7). On the S24 it resolved
  `NOT_CONNECTED` in one run and `PROFILE_DISCONNECTED` in the next.
- A force-stopped app doesn't apply the preferred codec when the device
  connects on API 28 and 32 (C9): the stopped package gets no broadcast, and
  on 32 the companion device manager doesn't start it either. On the S24 the
  companion device manager binds the service anyway, the codec is applied and
  the package leaves the stopped state. A fresh install over adb isn't in the
  stopped state on the S24.
- The S24 reports the headset's presence from two sources: it appears twice, a
  second apart, when the headset comes on, and disappears twice. Without A2DP
  it also reports the headset gone and back every minute or two (C13, D20).
  Since D18 the service counts it present while any source sees it: a
  reconnect is one appearance (C1, C2). Without A2DP, BLE is the only source,
  and its gaps remain.
- In one run of J3 on the S24 the app set the preferred codec 13 ms after
  A2DP connected, while the S24 was still making the headset its active
  device. The stack reported SBC, then switched back to LDAC 96 kHz by itself
  6 ms later. The app's check 500 ms after the change still read SBC and
  resolved `CODEC_VERIFIED`, while LDAC stayed active. The next two runs of J3
  and every other reconnect on the S24 applied the codec. Since D19 a codec
  counts as applied only after two matching reads.
- Play Protect asked again for the snippet APK of a new build. The install
  timeout stopped the run with its message, and declining the upload let the
  install finish. Since then its option to send unknown apps is off on the
  S24 (6.9).
- The S24's Bluetooth settings show the headset's name between invisible
  left-to-right marks, `‎Qudelix-5K‎`.
- On API 32 the system sometimes reports a device that stays on without A2DP
  as gone and back within seconds (C13, D20). The app logs each appearance as
  one operation that resolves `NOT_CONNECTED`.
- Compose's dropdown menus are popups, outside the semantics of the screen
  that exposes test tags as resource ids, so UiAutomator didn't find their
  items until the menus set it themselves.
- Unloading the snippet ends the app's process. On API 32 saving a preferred
  codec makes the device appear at once and the service apply the codec, so a
  test waits for that operation before unloading, or the log check reports it
  interrupted. Since D23 the service only logs the appearance.
- A `cycle` doesn't always make the API 32 Pixel see the headset go: it
  counts a device as gone 7–8 s after its BLE advertisements stop. C5, which
  without the permission depends on the device appearing again, switches the
  headset off for 15 s instead, like C1.
- A plain reboot of the Qudelix compared with a `cycle`, on all three phones:
  A2DP is down within 1.3 s either way and dumpsys shows it disconnected by
  the time the reboot returns. Once the S24 still showed the old connection
  about 6 s after the reboot started, 1–2 s after the 5K answered on USB
  again, so `reconnect()` waits for dumpsys to show A2DP down. It polls from
  the start of the reboot, before waiting for the 5K on USB, so a slow USB
  can't let A2DP come back unseen. The codec comes back as the phone's own choice
  every time; A2DP is back 1–3.5 s sooner (7–8 s on the Pixels, 11–12 s on
  the S24). With the app's process alive, neither makes the app log
  `DEVICE_APPEARED` on API 32 or 36. After C2 restarts the process, the S24
  logs one appearance per reconnect with either, from the source `Bluetooth`
  or `BLE`.
- The Qudelix once didn't answer its mode query for about 3 minutes. The
  backend retries it for 20 s; beyond that the class is aborted, like any
  baseline that can't be checked, instead of every test failing on it.
- On the API 32 Pixel a permission change reaches
  `runtime-permissions.xml` about 10.6 s after `pm grant`, and later changes
  push it back. A reboot before that loses it, `adb reboot` and
  `svc power reboot` alike. C7 failed once that way: the reboot came 17 s
  after the fixture's grant, and the manifest receiver was refused A2DP
  connecting for the missing permission. C7 now waits until the grant is
  written, by reading the file with root, or 15 s without.
- The first run of the reboot tests on both Pixels: with the PIN 0000 set,
  Mobly's `reboot()` returns at the lock screen, so `sys.boot_completed` is
  set before the unlock, and the user is `RUNNING_LOCKED` until it. A2DP
  connected before the unlock on its own, with the phone's own codec. C14
  failed on both: within 60 s of the unlock nothing applied the preferred
  codec and the app logged nothing. C15 failed on API 32: no appearance
  after the unlock, and none when the headset was off for 15 s and on again,
  although the companion device manager logged `onDeviceConnected`; only the
  manifest receiver applied the codec. C7 passed after each clean reboot,
  and no test left the baseline broken. On Android 9 the Gatekeeper SID in
  `dumpsys lock_settings` tells whether the PIN is set.
- The reboot tests with the receiver and the companion service direct boot
  aware: C14 passed on all three phones, the receiver applied the codec
  before the unlock and the merged log had it. C15 passed on the S24 and
  failed on API 32 without any appearance: Android 12 and 12L only write
  whether a device is observed (`notify_device_nearby`) for associations
  with a device profile, so every reboot forgets it; the stored association
  had no such attribute. Observing again at `LOCKED_BOOT_COMPLETED` came too
  late: in a boot recorded from the start, the manager logged
  `onDeviceConnected` 0.4 s before the broadcast was sent, and reported the
  device only after it had gone and come back. Mobly's logcat misses the
  start of a boot, the Pixel's buffer is overwritten by then. This led to
  D27.
- The first runs of C16 on both Pixels: passed. The receiver had applied the
  codec by the time A2DP was seen connected, while locked, and the first
  `am kill` ended the process. Nothing started it again before
  or in the 15 s after the unlock, and the log stayed unchanged until the main
  screen opened; the 5 locked events were merged by the first read after
  `am start -W` returned. On
  API 32 `run-as` can't end the process: SELinux denies `runas_app` the
  `sigkill` on `untrusted_app`, while reading the app's device protected
  files works.
- The first run of C16 on the S24 failed twice over. The test turned the
  headset off as soon as dumpsys showed the preferred codec, 0.5 s after the
  receiver set it and before it verified it, so the operation resolved
  `NOT_CONNECTED`. C16 now ends the process before the device goes away,
  which waits for the receiver; both Pixels pass with it. And the system
  started the app's process at the unlock: while locked, the companion
  device manager held back `onBluetoothCompanionDeviceConnected` and
  `onBleCompanionDeviceLost` ("Notify the application when the phone is
  unlocked"), and 0.2 s after `onUserUnlocking` it started the process for
  the service, which logged `DEVICE_APPEARED` (BLE) for the headset that
  had been off for about 13 s. No `DEVICE_DISAPPEARED` followed until the
  artifacts were collected, about 15 s later. So on API 36 the unlock merges the log whenever the device was
  seen while locked, and C16's scenario can't happen there. The AOSP source
  (`DevicePresenceProcessor`, main) explains it: the held-back events are
  kept in `mPendingDevicePresenceEvents`, in memory; a Bluetooth disconnect
  removes the queued connect, but `onBleCompanionDeviceLost` queues
  `EVENT_BLE_APPEARED`, so the unlock reports a device that is gone, and no
  disappearance follows. Since API 35 C16 reboots again, still locked,
  after the device is gone, which drops the queue. With that C16 passed on
  the S24: the user was still `RUNNING_LOCKED` after the second boot,
  nothing started the process at the unlock, and the main screen merged the
  5 events of the first boot. The first `am kill` there needed a second try,
  0.6 s later.
- In rare runs the unlock after a reboot typed the password before the lock
  screen was ready for it, right after the storage and the SIMs had
  unlocked, and the unlock failed. The unlock now waits 5 s before typing.
- Recordings of `dumpsys bluetooth_manager` (status and A2DP sections) and of
  the companion device outputs from every phone are in `mobly_tests/testdata`.

## 13. Decisions

- **D1** Refactor for testability: yes, except moving logic out of the
  activities (section 9).
- **D2** Robolectric for L1 and L2, and L2 also runs on every phone
  (section 7).
- **D3** The snippet is a separate `com.android.test` module; it reaches the
  app's internals (section 8).
- **D4** `snippet-uiautomator` with test tags exposed as resource ids, for the
  journeys.
- **D5** Screenshot tests after the UI polish; noted in the
  [development plan](DevelopmentPlan.md).
- **D6** A headset can only connect to the phones on adb.
- **D7** No special cases per phone. Tests are not applicable only for what
  can't work without root or with a secure lock screen. Saving and restoring a
  phone's own data is outside the suite.
- **D8** Everything that was in question gets tested: C3 and C9 assert
  today's behaviour as the baseline for later changes (6.7).
- **D9** Fixtures link with `cmd companiondevice associate`; the system dialog
  only appears in J3 and J6.
- **D10** The manual test records are deleted once their cases are automated.
- **D11** The baseline codec is the phone's own choice, restored by a full
  reconnect. Tests that need another codec use SBC 44.1 kHz, 16-bit, or, where
  the phone's own choice is SBC, the first other codec in the capabilities.
- **D12** A broken baseline before a test is repaired and reported as a leak of
  the previous test; if the repair fails, the class is aborted, so one failure
  doesn't spread to every following test. App data is reset with `pm clear`,
  with deleting the files as the fallback.
- **D13** The helpers the composables call (codec selection, log texts and
  grouping) move with them to the top level of the same file, as private
  functions where possible. If that turns out not to work or to be more
  complicated than expected, it's raised again.
- **D14** The phone backend switches only the phone's Bluetooth adapter. More
  for other headsets, like the "Media audio" switch or an operator backend, is
  out of scope.
- **D15** `testbed_session.py` keeps the phones awake and their other
  Bluetooth off, and warns when one is locked. The suite doesn't change these
  settings; it runs sanity checks and fails early (6.9). The phones, the S24
  included, are unlocked during a run.
- **D16** When reconnecting the headset doesn't connect it, the connect
  fixture taps it in the phone's Bluetooth settings (6.6).
- **D17** The manifest receiver is enabled on every API level, so the
  preferred codec is also applied when A2DP connects while the companion
  service doesn't run (C3). When both apply it, the second request resolves
  `ALREADY_PENDING`.
- **D18** Since API 36 the service follows `onDevicePresenceEvent` and counts
  a device present while any source reports it; the String callbacks are
  ignored there, since the system calls them once per source. A gap with A2DP
  connecting can't be reproduced on demand, the gaps come at random times, so
  the component tests (K5) cover it; C1 and C2 check one appearance per
  reconnect on API 36.
- **D19** A codec counts as applied once two reads in a row, 500 ms apart,
  report it; a codec that changed back after the first matching read is set
  again, as the next attempt. It covers the S24 changing the codec back right
  after A2DP connects, while the codec it reports lags behind. The race is too
  rare to reproduce on the phones, so the component tests (K1) make the fake
  stack change back.
- **D20** C13, presence without A2DP, is removed. Watching for five minutes
  whether the system keeps reporting the headset present made it about 410 s
  of a 30 minute run on the API 32 Pixel. What it recorded on API 32 and 36
  stays in section 12.
- **D21** The Qudelix backend's `reconnect()` is a reboot of the 5K instead
  of `bt-off`, 5 s and `normal`. It's a full reconnect on every phone and
  saves about 3 s each time, some 30 times a run (section 12). A reboot keeps
  the mode, so the headset is set to `normal` first.
- **D22** Mobly records every test that didn't run as SKIP, which hid a class
  abort behind a passing suite. The suite tells them apart: a test is **not
  applicable** when `require()` ends it (`NotApplicable`), for its API level,
  a capability or a secure lock screen, and **aborted** otherwise, like after
  a class abort (D12). An aborted test fails the suite; `run_suite.py` lists
  both with their reasons and hides Mobly's console lines that say SKIP.
- **D23** The companion service no longer applies the preferred codec when a
  device appears, only when A2DP connects while the device is present, and
  `DEVICE_APPEARED` is a standalone event. Saving a preferred codec starts
  observing; on API 32 the device appeared at once, and applying the preferred
  codec then raced a codec the user chose right after. C1 and C2 count
  appearance events, C3 and C5 wait for one instead of an operation. Without
  the permission no receiver gets A2DP connecting, so C5 no longer sees
  `NO_PERMISSION`, only the appearance.
- **D24** Every phone is in one of three lock screen classes in
  `testbed.yaml`: a pure test device (`pure_test_device: true`), which has
  no lock screen by default, a personal phone with a known password
  (`lock_password`), and a phone whose password isn't known, which is never
  rebooted. The suite only ever sets or clears the PIN 0000, and only on a
  pure test device, with two hardcoded commands. Unlocking is tried once:
  a wrong password counts as a failed attempt, which brings lockout delays
  and, on some phones, a factory reset. If it fails, the run is aborted. A
  PIN a run left behind on a pure test device is cleared once when the
  reboot tests start.
- **D25** A personal phone's password stays out of the results. Mobly
  records an AndroidDevice's config in every test summary, so
  `run_suite.py` passes only the lock screen class and the path of
  `testbed.yaml`; the password is read from it when it's typed, with a
  plain adb call, since Mobly's AdbProxy logs every command.
- **D26** A test that rebooted with a secure lock screen ends with the
  phone unlocked and, on a pure test device, the PIN cleared; no clean
  reboot follows. It used to: Bluetooth off, the PIN cleared, reboot,
  unlock, Bluetooth on, because on API 31–33, and on 34 after a BLE
  sighting, the companion device manager doesn't find a service that isn't
  direct boot aware for the rest of a boot in which the device appeared
  before the unlock, which broke C3 and C5 in this run and the next. The
  service is direct boot aware now and disabled on 31–32 (D27), so the next
  test runs in the boot the locked reboot left. On 33–34 this follows from
  the AOSP sources; the testbed has no phone there. The test images turn the swipe lock screen off at every boot,
  so the suite needs no command for that. Their `locksettings set-disabled`
  at boot counts as a failed attempt on Android 9 and 12L while the PIN is
  set; the unlock right after resets the count.
- **D27** On API 31 and 32 the companion service is disabled and nothing is
  observed: the system unbinds the service right after reporting a device,
  before A2DP connects, so it applies nothing the manifest receiver doesn't,
  and it forgets observing at every reboot, which only a boot receiver could
  repair, and those are unreliable on some OEMs. A link is still asked for
  there, for its side effect: it sets `AUTO_REVOKE_PERMISSIONS_IF_UNUSED` to
  `ignore`, so the system doesn't revoke the Nearby devices permission of an
  unused app. Unlinking keeps that, so a link is only asked for while the
  app has none and `isAutoRevokeWhitelisted` is false; with a link, the user
  turned the exemption off in the app's settings, and linking again wouldn't
  change it. API 30 has auto revoke too, but the app has no runtime
  permission there.
