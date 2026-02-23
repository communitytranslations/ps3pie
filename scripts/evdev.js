'use strict';

// Example script: generic gamepad (evdev) → virtual joystick
//
// Find your device index:
//   grep -A5 "Gamepad\|Joystick\|Xbox\|8BitDo\|DualShock" /proc/bus/input/devices
//   Look for: H: Handlers=... eventN  → use N below

const DEVICE   = 0;     // change to your /dev/input/eventN index
const DEADBAND = 0.05;  // stick dead zone (normalized)

// Accessing joystick[N] at top level opens the device immediately on startup
const pad = joystick[DEVICE];

module.exports = {
    loop() {
        // Left stick → vjoyA XY (raw range: -32767..32767 → normalized -1..1)
        vjoyA.x = filters.deadband(
            filters.ensureMapRange(pad.axes[0], -32767, 32767, -1, 1), DEADBAND);
        vjoyA.y = filters.deadband(
            filters.ensureMapRange(pad.axes[1], -32767, 32767, -1, 1), DEADBAND);

        // Right stick → vjoyA RX/RY
        vjoyA.rx = filters.deadband(
            filters.ensureMapRange(pad.axes[3], -32767, 32767, -1, 1), DEADBAND);
        vjoyA.ry = filters.deadband(
            filters.ensureMapRange(pad.axes[4], -32767, 32767, -1, 1), DEADBAND);

        // Triggers → vjoyA Z/RZ (most gamepads report 0..255)
        vjoyA.z  = filters.ensureMapRange(pad.axes[2] ?? 0, 0, 255, -1, 1);
        vjoyA.rz = filters.ensureMapRange(pad.axes[5] ?? 0, 0, 255, -1, 1);

        // D-pad (hat switches) → vjoyB dpad buttons
        // Hat axes: -1=negative direction, 0=center, 1=positive direction
        vjoyB.left  = (pad.axes[16] ?? 0) < 0 ? 1 : 0;
        vjoyB.right = (pad.axes[16] ?? 0) > 0 ? 1 : 0;
        vjoyB.up    = (pad.axes[17] ?? 0) < 0 ? 1 : 0;
        vjoyB.down  = (pad.axes[17] ?? 0) > 0 ? 1 : 0;

        // Face buttons
        vjoyB.a = pad.buttons[0x130] ?? 0;   // BTN_SOUTH (A / Cross)
        vjoyB.b = pad.buttons[0x131] ?? 0;   // BTN_EAST  (B / Circle)
        vjoyB.x = pad.buttons[0x133] ?? 0;   // BTN_NORTH (Y / Triangle)
        vjoyB.y = pad.buttons[0x134] ?? 0;   // BTN_WEST  (X / Square)

        // Shoulder buttons
        vjoyB.tl = pad.buttons[0x136] ?? 0;  // BTN_TL  (L1 / LB)
        vjoyB.tr = pad.buttons[0x137] ?? 0;  // BTN_TR  (R1 / RB)

        // Stick clicks
        vjoyB.thumbl = pad.buttons[0x13d] ?? 0;  // BTN_THUMBL (L3)
        vjoyB.thumbr = pad.buttons[0x13e] ?? 0;  // BTN_THUMBR (R3)

        // Menu buttons
        vjoyB.select = pad.buttons[0x13a] ?? 0;  // BTN_SELECT (Back)
        vjoyB.start  = pad.buttons[0x13b] ?? 0;  // BTN_START  (Start / Menu)
    }
};
