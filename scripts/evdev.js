'use strict';

// Example script: generic gamepad (evdev) → virtual joystick
//
// joystick.find() auto-detects the first gamepad or joystick connected.
// To target a specific controller by name:
//   joystick.find('8BitDo')
//   joystick.find('Xbox')
//   joystick.find('DualShock')
// To target by event number explicitly:
//   joystick[4]   (opens /dev/input/event4)

const DEADBAND = 0.05;  // stick dead zone (normalized)

const pad = joystick.find();   // auto-detect first gamepad

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

        // D-pad (hat switches) → vjoyA hat axes (ABS_HAT0X/Y, same as real Xbox pad)
        // Hat raw values: -1=negative direction, 0=center, 1=positive direction
        vjoyA.hat0x = pad.axes[16] ?? 0;
        vjoyA.hat0y = pad.axes[17] ?? 0;

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
        vjoyB.mode   = pad.buttons[0x13c] ?? 0;  // BTN_MODE   (Home / Guide / Xbox)
    }
};
