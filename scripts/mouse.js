'use strict';

// Example script: generic gamepad → mouse control
//
// Right stick  → mouse movement
// Left stick Y → scroll wheel
// A button     → left click
// B button     → right click
// X button     → middle click
//
// Find your device index:
//   grep -A5 "Gamepad\|Joystick\|Xbox\|8BitDo" /proc/bus/input/devices
//   Look for: H: Handlers=... eventN  → use N below

const DEVICE       = 0;    // change to your /dev/input/eventN index
const MOUSE_SPEED  = 15;   // pixels per frame at full stick deflection
const SCROLL_SPEED = 3;    // scroll ticks per frame at full stick deflection
const DEADBAND     = 0.1;  // stick dead zone (normalized)

const pad = joystick[DEVICE];

module.exports = {
    loop() {
        // Right stick → mouse XY movement
        const rx = filters.deadband(
            filters.ensureMapRange(pad.axes[3], -32767, 32767, -1, 1), DEADBAND);
        const ry = filters.deadband(
            filters.ensureMapRange(pad.axes[4], -32767, 32767, -1, 1), DEADBAND);

        mouse.x = Math.round(rx * MOUSE_SPEED);
        mouse.y = Math.round(ry * MOUSE_SPEED);

        // Left stick Y → scroll (inverted: push up = scroll up)
        const ly = filters.deadband(
            filters.ensureMapRange(pad.axes[1], -32767, 32767, -1, 1), DEADBAND);

        mouse.wheel = Math.round(-ly * SCROLL_SPEED);

        // Face buttons → mouse buttons
        mouse.left   = pad.buttons[0x130] ?? 0;  // A / Cross   → left click
        mouse.right  = pad.buttons[0x131] ?? 0;  // B / Circle  → right click
        mouse.middle = pad.buttons[0x133] ?? 0;  // X / Triangle → middle click
    }
};
