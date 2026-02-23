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

Reads any gamepad or joystick from `/dev/input/eventN`. Find your device index:

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
pad.buttons[0x133]   // BTN_NORTH  (X / Triangle)
pad.buttons[0x134]   // BTN_WEST   (Y / Square)
pad.buttons[0x136]   // BTN_TL     (L1 / LB)
pad.buttons[0x137]   // BTN_TR     (R1 / RB)
pad.buttons[0x138]   // BTN_TL2    (L2 digital)
pad.buttons[0x139]   // BTN_TR2    (R2 digital)
pad.buttons[0x13a]   // BTN_SELECT (Back / Select)
pad.buttons[0x13b]   // BTN_START  (Start / Menu)
pad.buttons[0x13c]   // BTN_MODE   (Guide / PS / Xbox)
pad.buttons[0x13d]   // BTN_THUMBL (L3)
pad.buttons[0x13e]   // BTN_THUMBR (R3)
```

The device is grabbed exclusively (`EVIOCGRAB`) — the physical gamepad is hidden from all other applications while ps3pie runs.

### `ps3` — PS3 DualShock 3 (HID raw)

Reads the PS3 controller directly via `/dev/hidrawX`. Connects automatically when the controller is plugged in (does not block startup).

```js
ps3.lx, ps3.ly        // left stick  [-1, 1]
ps3.rx, ps3.ry        // right stick [-1, 1]
ps3.cross             // face buttons [0, 1]
ps3.circle, ps3.square, ps3.triangle
ps3.l1, ps3.r1, ps3.l2, ps3.r2
ps3.l3, ps3.r3        // stick clicks
ps3.select, ps3.start, ps3.ps
ps3.up, ps3.down, ps3.left, ps3.right  // d-pad
```

### `android[N]` — Android IMU (UDP)

Receives data from the **FreePIE IMU sender** app (APK at `/opt/FreePIE/Lib/Android/`). Listens on UDP port 5555. Up to 16 devices indexed `android[0]`..`android[15]`.

```js
android[0].yaw          // degrees
android[0].pitch
android[0].roll
android[0].raw.ax       // accelerometer m/s²
android[0].raw.ay
android[0].raw.az
android[0].raw.gx       // gyroscope rad/s
android[0].raw.gy
android[0].raw.gz
android[0].raw.mx       // magnetometer µT
android[0].raw.my
android[0].raw.mz
```

### `iphone` — iPhone/iPad IMU (UDP)

Receives data from the **FreePIE** iOS app. Listens on UDP port 10552. Note: FreePIE iOS swaps Roll and Pitch columns — ps3pie corrects this automatically.

```js
iphone.yaw     // degrees
iphone.pitch
iphone.roll
```

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

```js
vjoyA.x  = 0.5;    // [-1, 1]
vjoyA.y  = -1.0;
vjoyA.rx = 0.0;
vjoyA.ry = 0.0;
vjoyA.z  = 0.0;    // left trigger
vjoyA.rz = 0.0;    // right trigger
```

### `vjoyB` — Virtual joystick buttons

```js
vjoyB.a = 1;    // any string key, any value 0/1
vjoyB.b = 0;
vjoyB.start = pad.buttons[0x13b];
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

```js
keyboard['w'] = 1;     // key held while value is 1
keyboard['shift'] = 0;
```

### `keyboardEvents` — One-shot key presses

```js
keyboardEvents.push('enter');          // single key
keyboardEvents.push(['ctrl', 's']);    // combo
```

## Filters API

```js
// Exponential Moving Average — smoothing ∈ [0,1]: 0=none, 1=frozen
filters.simple(x, smoothing, key)

// Frame-to-frame delta (returns 0 on first call)
filters.delta(x, key)

// Dead zone: returns 0 if |x| < zone, else x unchanged
filters.deadband(x, zone)
// Scaled deadband (remaps to [minY, maxY]):
filters.deadband(x, zone, minY, maxY)

// Linear remap without clamping
filters.mapRange(x, x0, x1, y0, y1)

// Linear remap with clamping to [y0, y1]
filters.ensureMapRange(x, x0, x1, y0, y1)

// Boolean timer: returns true once after ms milliseconds of active=true
filters.stopWatch(active, ms, key)

// Unwrap a rotating sensor — prevents jumps at ±180°/±π
filters.continuousRotation(x, key)              // radians (default)
filters.continuousRotation(x, 'degrees', key)   // degrees
```

Each stateful filter (`simple`, `delta`, `stopWatch`, `continuousRotation`) requires a unique string `key` per usage site to keep its state.

## Example scripts

| Script | Description |
|---|---|
| `scripts/evdev.js` | Generic gamepad → virtual joystick with deadband and axis curves |
| `scripts/mouse.js` | Gamepad right stick → mouse, face buttons → mouse clicks |
| `scripts/android.js` | Android phone gyroscope → gyroscopic mouse aiming |
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

## Legal

Original work: Copyright 2019–2021 David Meyer
Fork development: Community Translations Project
License: GNU General Public License v3.0
