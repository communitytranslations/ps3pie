# ps3pie

A **Programmable Input Emulator** for Linux, written in Node.js — the Linux equivalent of [FreePIE](https://andersmalmgren.github.io/FreePIE/).

ps3pie reads from physical input devices, runs a user-provided JavaScript script to transform and remap the inputs, and outputs events to virtual devices via the Linux `uinput` kernel subsystem. This allows complex, programmable mappings that go beyond what fixed-remapping tools can offer: pressure curves, multi-mode shift patterns, signal filtering, gyroscopic mouse aiming, and arbitrary logic.

> **This fork** has been developed into a general-purpose programmable input emulator for Linux with a plugin architecture, support for multiple input and output devices, and a full scripting API.

## How it works

```
Physical devices (/dev/hidrawX, /dev/input/eventN, UDP, serial, MIDI...)
    → Input plugins    (normalize device state before each loop)
    → User script      (JavaScript — transforms inputs to outputs)
    → Output plugins   (emit changed events to virtual devices)
Virtual devices (/dev/uinput)
```

Scripts are plain JavaScript files with a `loop()` export. The engine calls `loop()` every time any connected device produces new data. Output state is diffed each loop — only changed events are emitted.

## Security

**Scripts are trusted code.** Running `node index.js script.js` is equivalent to running `node script.js` directly — the script has full access to the system. Do not run scripts from untrusted sources.

**UDP plugins bind to `127.0.0.1` by default.** The Android and iPhone IMU plugins only accept packets from localhost. To receive data from a real phone over WiFi you must explicitly opt in:

```bash
PS3PIE_BIND_HOST=0.0.0.0 node index.js scripts/android.js
```

On shared or public networks, combine this with an OS-level firewall rule to allow only your device's IP:

```bash
# Allow only the phone (replace with its actual IP):
sudo ufw allow from 192.168.1.42 to any port 5555 proto udp
sudo ufw allow from 192.168.1.42 to any port 10552 proto udp
```

The FreePIE network protocol has no built-in authentication. The firewall rule is the correct solution — it enforces source IP at the OS level without requiring any protocol changes or breaking compatibility with the FreePIE app.

## Quick start

```bash
npm install
node index.js scripts/evdev.js        # generic gamepad
node index.js scripts/mouse.js        # gamepad as mouse
node index.js scripts/android.js      # Android phone as gyro-mouse
node index.js scripts/midi.js         # MIDI controller
```

## Permissions

Install the bundled udev rules once (no reboot required):

```bash
sudo cp udev/60-ps3pie.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger
```

This grants access to `uinput`, `hidraw`, and `evdev` devices for the logged-in user via `TAG+="uaccess"` — no groups, no `chmod`.

## Input plugins

### `joystick[N]` — Generic gamepad (evdev)

Reads any gamepad or joystick from `/dev/input/eventN`.

**Auto-detect** (recommended):

```js
const pad = joystick.find();            // first gamepad/joystick/controller found
const pad = joystick.find('8BitDo');    // first device whose name contains '8BitDo'
const pad = joystick.find('Xbox');
const pad = joystick.find('DualShock');
```

**By index** (when you know the device number):

```bash
grep -A5 "Gamepad\|Joystick\|Xbox\|8BitDo\|DualShock" /proc/bus/input/devices
# Look for: H: Handlers=... eventN  → use N as the index
```

```js
const pad = joystick[4];   // opens /dev/input/event4

pad.axes[0]          // ABS_X  — raw int32, typically -32767..32767
pad.axes[1]          // ABS_Y
pad.axes[3]          // ABS_RX
pad.axes[4]          // ABS_RY
pad.axes[2]          // ABS_Z   (left trigger on most gamepads, 0..255)
pad.axes[5]          // ABS_RZ  (right trigger)
pad.axes[16]         // ABS_HAT0X  (-1=left, 0=center, 1=right)
pad.axes[17]         // ABS_HAT0Y  (-1=up,   0=center, 1=down)

pad.buttons[0x130]   // BTN_SOUTH  (A / Cross)
pad.buttons[0x131]   // BTN_EAST   (B / Circle)
pad.buttons[0x133]   // BTN_NORTH  (Y / Triangle)
pad.buttons[0x134]   // BTN_WEST   (X / Square)
pad.buttons[0x136]   // BTN_TL     (L1 / LB)
pad.buttons[0x137]   // BTN_TR     (R1 / RB)
pad.buttons[0x138]   // BTN_TL2    (L2 digital)
pad.buttons[0x139]   // BTN_TR2    (R2 digital)
pad.buttons[0x13a]   // BTN_SELECT (Back / Select)
pad.buttons[0x13b]   // BTN_START  (Start / Menu)
pad.buttons[0x13c]   // BTN_MODE   (Guide / PS / Xbox)
pad.buttons[0x13d]   // BTN_THUMBL (L3)
pad.buttons[0x13e]   // BTN_THUMBR (R3)

// Rising-edge detection — true only on the frame the button is pressed
pad.getPressed(0x130)   // true once per press of BTN_SOUTH (A / Cross)
pad.getPressed(0x131)   // BTN_EAST, etc.
```

The device is grabbed exclusively (`EVIOCGRAB`) — the physical gamepad is hidden from all other applications while ps3pie runs.

### `ps3` — PS3 DualShock 3 (HID raw)

Reads the PS3 controller directly via `/dev/hidrawX`. Connects automatically when the controller is plugged in (does not block startup).

```js
// Sticks [-1, 1]
ps3.leftStickX,  ps3.leftStickY
ps3.rightStickX, ps3.rightStickY

// Digital buttons [0, 1]
ps3.cross, ps3.circle, ps3.square, ps3.triangle
ps3.l1, ps3.r1, ps3.l2, ps3.r2
ps3.leftStickButton, ps3.rightStickButton   // L3 / R3
ps3.select, ps3.start, ps3.ps
ps3.up, ps3.down, ps3.left, ps3.right       // d-pad

// Pressure-sensitive analog buttons [0, 1]
ps3.crossAnalog,    ps3.circleAnalog
ps3.squareAnalog,   ps3.triangleAnalog
ps3.l1Analog,       ps3.r1Analog
ps3.l2Analog,       ps3.r2Analog
ps3.upAnalog,       ps3.downAnalog
ps3.leftAnalog,     ps3.rightAnalog
```

### `android[N]` — Android IMU (UDP)

Receives data from an Android phone over WiFi. Listens on UDP port 5555. Up to 16 devices indexed `android[0]`..`android[15]`.

**Compatible apps:**

- **WishIMU** (included in this repository under `WishIMU Compatible With FreePIE/`) — the recommended companion app, built for Android 9–16. Features: orientation + raw sensor streaming, on-screen Left Click / Right Click touch buttons, volume hardware buttons as mouse clicks (works over the lock screen). See [scripts/android.js](scripts/android.js) for the matching ps3pie script.
- **FreePIE IMU sender** (legacy APK) — the original FreePIE companion app; compatible with the same protocol.

```js
android[0].yaw          // radians — Euler Z (from SensorManager.getOrientation)
android[0].pitch        // radians — Euler X
android[0].roll         // radians — Euler Y
android[0].raw.ax       // accelerometer m/s²
android[0].raw.ay
android[0].raw.az
android[0].raw.gx       // gyroscope rad/s
android[0].raw.gy
android[0].raw.gz
android[0].raw.mx       // magnetometer µT
android[0].raw.my
android[0].raw.mz
android[0].buttons      // bitmask — bit 0 = left click, bit 1 = right click
```

### `iphone` — iPhone/iPad IMU (UDP)

Receives data from the **FreePIE** iOS app. Listens on UDP port 10552. Note: FreePIE iOS swaps Roll and Pitch columns — ps3pie corrects this automatically.

```js
iphone.yaw     // degrees
iphone.pitch
iphone.roll
```

### `opentrack` — OpenTrack / FreeTrack head-tracker (UDP)

Receives data from **OpenTrack** or any compatible sender (e.g. **HeadMob** Android app). Listens on UDP port 4242 using the standard OpenTrack "UDP over network" binary format.

```js
opentrack.yaw      // degrees (-180..+180)
opentrack.pitch    // degrees (-90..+90)
opentrack.roll     // degrees (-180..+180)
opentrack.x        // cm — head position left/right
opentrack.y        // cm — head position up/down
opentrack.z        // cm — head position forward/back
```

To receive from the network (OpenTrack on another PC, or HeadMob on Android):

```bash
PS3PIE_BIND_HOST=0.0.0.0 node index.js scripts/opentrack.js
```

To change the port (default 4242):

```bash
PS3PIE_OPENTRACK_PORT=4242 node index.js scripts/opentrack.js
```

**OpenTrack setup**: Output → UDP over network → Host = this machine's IP, Port = 4242.

### `midi[N]` — MIDI input (ALSA)

Reads from MIDI input port N. Port 0 is the first available MIDI device. Requires `libasound2-dev` (see Dependencies).

```js
midi[0].status    // message type: 0x8=NoteOff, 0x9=NoteOn, 0xB=Control,
                  //               0xC=ProgramChange, 0xE=PitchBend
midi[0].channel   // MIDI channel 0-15
midi[0].data      // [byte, byte] raw data bytes

// Shorthands:
midi[0].note      // data[0] — note number  (NoteOn/NoteOff)
midi[0].velocity  // data[1] — velocity     (NoteOn/NoteOff)
midi[0].cc        // data[0] — CC number    (Control Change)
midi[0].value     // data[1] — CC value     (Control Change)
```

List available MIDI ports:

```bash
aconnect -i
```

### `ahrsImu` — AHRS IMU (serial)

Reads yaw/pitch/roll from a SparkFun 9DOF / SEN-10736 AHRS sensor over serial. Binary protocol: 3 × float32 LE per frame (radians).

```js
ahrsImu.yaw     // radians (-π..+π)
ahrsImu.pitch
ahrsImu.roll
```

```bash
PS3PIE_AHRS_PORT=/dev/ttyUSB0 node index.js scripts/imu.js   # default port
```

### `freeImu` — FreeIMU (serial)

Reads yaw/pitch/roll from a FreeIMU sensor over serial. ASCII CSV protocol: `yaw,pitch,roll\n` (radians).

```js
freeImu.yaw     // radians (-π..+π)
freeImu.pitch
freeImu.roll
```

```bash
PS3PIE_FREEIMU_PORT=/dev/ttyUSB1 node index.js scripts/imu.js
```

## Output plugins

### `vjoyA` — Virtual joystick axes

Values in `[-1, 1]` (scaled to `[0, 1000]` internally).

```js
vjoyA.x     = 0.5;   // ABS_X     left stick horizontal
vjoyA.y     = 0.0;   // ABS_Y     left stick vertical
vjoyA.rx    = 0.0;   // ABS_RX    right stick horizontal
vjoyA.ry    = 0.0;   // ABS_RY    right stick vertical
vjoyA.z     = 0.0;   // ABS_Z     left trigger
vjoyA.rz    = 0.0;   // ABS_RZ    right trigger
vjoyA.hat0x = 0.0;   // ABS_HAT0X d-pad horizontal (-1=left, 0=center, 1=right)
vjoyA.hat0y = 0.0;   // ABS_HAT0Y d-pad vertical   (-1=up,   0=center, 1=down)
```

### `vjoyB` — Virtual joystick buttons

Valid button names (from `uinput.js`):

```js
vjoyB.a      // BTN_A      0x130
vjoyB.b      // BTN_B      0x131
vjoyB.x      // BTN_X      0x133
vjoyB.y      // BTN_Y      0x134
vjoyB.tl     // BTN_TL     0x136  (L1 / LB)
vjoyB.tr     // BTN_TR     0x137  (R1 / RB)
vjoyB.tl2    // BTN_TL2    0x138  (L2 digital)
vjoyB.tr2    // BTN_TR2    0x139  (R2 digital)
vjoyB.select // BTN_SELECT 0x13a
vjoyB.start  // BTN_START  0x13b
vjoyB.mode   // BTN_MODE   0x13c  (Guide / PS / Xbox)
vjoyB.thumbl // BTN_THUMBL 0x13d  (L3)
vjoyB.thumbr // BTN_THUMBR 0x13e  (R3)
vjoyB.up     // BTN_DPAD_UP    0x220
vjoyB.down   // BTN_DPAD_DOWN  0x221
vjoyB.left   // BTN_DPAD_LEFT  0x222
vjoyB.right  // BTN_DPAD_RIGHT 0x223
```

### `mouse` — Virtual mouse

Relative axes reset to 0 after each loop. Buttons are level-triggered.

```js
mouse.x      = 5;    // pixels, relative, reset each frame
mouse.y      = -3;
mouse.wheel  = 1;    // scroll ticks, reset each frame
mouse.left   = 1;    // 0/1, level
mouse.right  = 0;
mouse.middle = 0;
```

### `keyboard` — Sustained key state

Key names must be from the supported set (see below). Key is held while value is `1`.

```js
keyboard['space'] = 1;
keyboard['shift'] = 0;
```

### `keyboardEvents` — One-shot key presses

```js
keyboardEvents.push('enter');           // single key
keyboardEvents.push(['ralt', 'f2']);    // combo (all keys pressed then released together)
```

### Supported key names

Both `keyboard` and `keyboardEvents` only accept names defined in `uinput.js`:

```
esc   one  two  three  four  five  six  seven  eight  nine  zero
minus  equals  backspace
tab   enter  space  capslock
a  b  c  d  e  f  g  h  i  j  k  l  m
n  o  p  q  r  s  t  u  v  w  x  y  z
comma  period

lctrl  lshift  lalt  shift  rctrl  ralt  super

f1  f2  f3  f4  f5  f6  f7  f8  f9  f10  f11  f12

home  end  pageUp  pageDown
up  down  left  right
insert  del
```

## Filters API

```js
// Exponential Moving Average — smoothing ∈ [0,1]: 0=none, 1=frozen
filters.simple(x, smoothing)        // key auto-derived from call site
filters.simple(x, smoothing, key)   // explicit key

// Frame-to-frame delta (returns 0 on first call)
filters.delta(x)       // key auto-derived
filters.delta(x, key)  // explicit key

// Dead zone: returns 0 if |x| < zone, else x unchanged
filters.deadband(x, zone)
// Scaled deadband (remaps to [minY, maxY]):
filters.deadband(x, zone, minY, maxY)

// Linear remap without clamping
filters.mapRange(x, x0, x1, y0, y1)

// Linear remap with clamping to [y0, y1]
filters.ensureMapRange(x, x0, x1, y0, y1)

// Boolean timer: returns true once after ms milliseconds of active=true
filters.stopWatch(active, ms)       // key auto-derived
filters.stopWatch(active, ms, key)  // explicit key

// Unwrap a rotating sensor — prevents jumps at ±180°/±π
filters.continuousRotation(x)                   // radians, auto-key
filters.continuousRotation(x, 'degrees')        // degrees, auto-key
filters.continuousRotation(x, unit, key)        // explicit unit and key
```

Stateful filters (`simple`, `delta`, `stopWatch`, `continuousRotation`) maintain separate state per call site. The `key` parameter is optional — when omitted, it is automatically derived from the source location so each call site has its own independent state. Pass an explicit `key` string when you want two call sites to share state.

## Example scripts

| Script | Description |
|---|---|
| `scripts/evdev.js` | Generic gamepad → virtual joystick with deadband and axis curves |
| `scripts/mouse.js` | Gamepad right stick → mouse, face buttons → mouse clicks |
| `scripts/android.js` | Android phone gyroscope → gyroscopic mouse aiming |
| `scripts/opentrack.js` | OpenTrack / HeadMob head-tracker → mouse aiming |
| `scripts/midi.js` | MIDI CC knobs → axes, MIDI notes → buttons |
| `scripts/descent.js` | Original PS3 → Descent/Overload mapping |

## Writing a script

```js
// scripts/example.js
const DEVICE = 4;          // change to your /dev/input/eventN index
const pad = joystick[DEVICE];

module.exports = {
    loop() {
        vjoyA.x = filters.ensureMapRange(pad.axes[0], -32767, 32767, -1, 1);
        vjoyA.y = filters.ensureMapRange(pad.axes[1], -32767, 32767, -1, 1);
        vjoyB.a = pad.buttons[0x130];
    }
};
```

## Dependencies

| Package | Version | Notes |
|---|---|---|
| `koffi` | ^2.9.0 | FFI for uinput and evdev ioctls (no native compilation) |
| `node-hid` | ^3.3.0 | PS3 HID raw input (NAPI, Node 22 compatible) |
| `midi` | ^2.0.0 | MIDI input via RtMidi/ALSA — requires `libasound2-dev` |
| `serialport` | ^13.0.0 | Serial IMU input (NAPI, Node 22 compatible) |

System packages (Ubuntu/Debian):

```bash
sudo apt install libasound2-dev   # required for the midi package
```

## AI assistance

The fork development (plugin architecture, koffi migration, filters API, WishIMU app, and all code added after the initial commit) was written in collaboration with [Claude Code](https://claude.ai/code) (Anthropic). Commits that include AI-generated code are marked with `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>` in the git log.

## Legal

Original work: Copyright 2019–2021 David Meyer
Fork development: Community Translations Project
License: GNU General Public License v3.0
