# ps3pie

A **Programmable Input Emulator** for Linux, written in Node.js — the Linux equivalent of [FreePIE](https://andersmalmgren.github.io/FreePIE/) or GlovePIE.

ps3pie reads from physical input devices, runs a user-provided JavaScript script to transform and remap the inputs, and outputs events to virtual devices via the Linux `uinput` kernel subsystem. This allows complex, programmable mappings that go beyond what fixed-remapping tools can offer: pressure-sensitive buttons, axis curves, multi-mode shift patterns, signal filtering, and arbitrary logic.

> **This fork** is an active development effort to bring ps3pie to feature parity with FreePIE, with a plugin-based architecture, support for multiple input and output devices, and a clean scripting API.

## Status

**Work in progress.** The original MVP supported only the PS3 controller with a hardcoded mapping script. This fork is being developed into a general-purpose programmable input emulator for Linux.

See [roadmap](#roadmap) for the planned work.

## How it works

```
Physical devices (/dev/hidrawX, /dev/input/eventN, UDP, serial...)
    → Input plugins    (normalize device state each loop)
    → User script      (JavaScript — transforms inputs to outputs)
    → Output plugins   (emit changed events to virtual devices)
Virtual devices (/dev/uinput)
```

User scripts write to named output objects (`vjoy`, `keyboard`, `mouse`) and read from named input objects (`ps3`, `joystick`, `android`). The engine diffs state each loop and emits only changed events, keeping latency low.

## Running

```bash
node index.js <script.js>
```

```bash
# Create a dummy virtual device to pre-occupy a joystick slot (workaround for game mapping)
node dummy.js
```

## Permissions

### hidraw (PS3 controller input)

```
KERNEL=="hidraw*", ATTRS{idVendor}=="054c", ATTRS{idProduct}=="0268", MODE="0666"
```

Save to `/etc/udev/rules.d/99-ps3.rules`.

### uinput (virtual device output)

```bash
sudo groupadd uinput
sudo usermod -a -G uinput "$USER"
```

```
# /etc/udev/rules.d/99-uinput.rules
SUBSYSTEM=="misc", KERNEL=="uinput", MODE="0660", GROUP="uinput"
```

```
# /etc/modules-load.d/uinput.conf
uinput
```

Reboot and verify with `ls -l /dev/uinput`.

## Roadmap

- [x] MVP: PS3 controller → virtual joystick + keyboard (original)
- [ ] **Replace `ioctl`/`uinput2` with `koffi`** — fix compatibility with Node.js 20+ (current blocker)
- [ ] Update `node-hid` 2.x → 3.x (NAPI, Node 20+ compatible)
- [ ] Plugin architecture — input and output as independent modules
- [ ] User-specified script via CLI argument
- [ ] Signal filtering API (`filters.simple`, `filters.deadband`, `filters.delta`, ...)
- [ ] Generic joystick/gamepad input plugin (evdev)
- [ ] Mouse output plugin (uinput)
- [ ] Exclusive device grab (`EVIOCGRAB`) — prevents games from seeing the physical controller
- [ ] Network input plugins: Android/iOS sensors over UDP
- [ ] Serial input plugins: MIDI, IMU sensors

## Why JavaScript?

ps3pie is the only programmable input emulator for Linux that uses JavaScript as its scripting language. The Node.js async event model maps naturally to input processing, and the `vm` sandbox provides safe script isolation. For users already familiar with web or Node.js development, writing mapping scripts requires no new language.

## Legal

Original work: Copyright 2019–2021 David Meyer
Fork development: Community Translations Project
License: GNU General Public License v3.0
