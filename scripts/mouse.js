'use strict';

// Example script: generic gamepad → mouse control
//
// Right stick  → mouse movement
// Left stick Y → scroll wheel
// A button     → left click
// B button     → right click
// Y button     → middle click
//
// The script auto-detects the first gamepad or joystick found.
// To target a specific device, replace joystick.find() with joystick[N]
// (find the N with: grep -A5 "Gamepad\|Joystick" /proc/bus/input/devices)
//
// To target a specific controller by name:
//   joystick.find('8BitDo')
//   joystick.find('Xbox')
//   joystick.find('DualShock')

const MOUSE_SPEED  = 15;    // pixels per frame at full stick deflection
const SCROLL_SPEED = 0.15;  // scroll ticks per frame at full stick deflection (~9 ticks/s max)
const DEADBAND     = 0.1;   // stick dead zone (normalized)

const pad = joystick.find();   // auto-detect first gamepad

// Fractional accumulator: collects sub-tick scroll increments across frames
// and emits an integer tick only when the total reaches ±1.
let scrollAccum = 0;

module.exports = {
    loop() {
        if (!pad) return;

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

        scrollAccum += -ly * SCROLL_SPEED;
        const ticks = Math.trunc(scrollAccum);
        scrollAccum -= ticks;
        mouse.wheel = ticks;

        // Face buttons → mouse buttons
        mouse.left   = pad.buttons[0x130] ?? 0;  // BTN_SOUTH (A / Cross)   → left click
        mouse.right  = pad.buttons[0x131] ?? 0;  // BTN_EAST  (B / Circle)  → right click
        mouse.middle = pad.buttons[0x133] ?? 0;  // BTN_NORTH (Y / Triangle) → middle click
    }
};
