'use strict';

// Example script: turbo / auto-fire buttons
//
// Buttons listed with turbo:true fire repeatedly at TURBO_HZ while held.
// All other buttons pass through normally.
//
// Change TURBO_HZ to adjust fire rate.
// Set turbo:true/false per button in the BUTTONS table below.

const TURBO_HZ = 10;              // fires per second (10 = 100 ms on / 100 ms off)
const TURBO_MS = 1000 / TURBO_HZ;

const BUTTONS = [
    { code: 0x130, name: 'a',      turbo: true  },  // BTN_SOUTH  (A / Cross)
    { code: 0x131, name: 'b',      turbo: true  },  // BTN_EAST   (B / Circle)
    { code: 0x133, name: 'x',      turbo: false },  // BTN_NORTH  (Y / Triangle)
    { code: 0x134, name: 'y',      turbo: false },  // BTN_WEST   (X / Square)
    { code: 0x136, name: 'tl',     turbo: false },  // BTN_TL     (L1 / LB)
    { code: 0x137, name: 'tr',     turbo: false },  // BTN_TR     (R1 / RB)
    { code: 0x13a, name: 'select', turbo: false },  // BTN_SELECT (Back)
    { code: 0x13b, name: 'start',  turbo: false },  // BTN_START  (Start / Menu)
    { code: 0x13c, name: 'mode',   turbo: false },  // BTN_MODE   (Home / Guide / Xbox)
    { code: 0x13d, name: 'thumbl', turbo: false },  // L3
    { code: 0x13e, name: 'thumbr', turbo: false },  // R3
];

const pad = joystick.find();

// Per-button timestamps: record when each turbo button was first pressed
const turboStart = {};

module.exports = {
    loop() {
        if (!pad) return;

        const now = Date.now();

        for (const btn of BUTTONS) {
            const held = pad.buttons[btn.code] ?? 0;

            if (!held) {
                vjoyB[btn.name] = 0;
                delete turboStart[btn.code];
                continue;
            }

            if (btn.turbo) {
                // Start timer on first press
                if (!turboStart[btn.code]) turboStart[btn.code] = now;
                // Oscillate at TURBO_HZ: first half of cycle = pressed, second = released
                const phase = ((now - turboStart[btn.code]) % TURBO_MS) / TURBO_MS;
                vjoyB[btn.name] = phase < 0.5 ? 1 : 0;
            } else {
                vjoyB[btn.name] = 1;
            }
        }

        // Axes pass-through
        vjoyA.x  = filters.ensureMapRange(pad.axes[0] ?? 0, -32767, 32767, -1, 1);
        vjoyA.y  = filters.ensureMapRange(pad.axes[1] ?? 0, -32767, 32767, -1, 1);
        vjoyA.rx = filters.ensureMapRange(pad.axes[3] ?? 0, -32767, 32767, -1, 1);
        vjoyA.ry = filters.ensureMapRange(pad.axes[4] ?? 0, -32767, 32767, -1, 1);
        vjoyA.z  = filters.ensureMapRange(pad.axes[2] ?? 0, 0, 255, -1, 1);
        vjoyA.rz = filters.ensureMapRange(pad.axes[5] ?? 0, 0, 255, -1, 1);

        vjoyA.hat0x = pad.axes[16] ?? 0;
        vjoyA.hat0y = pad.axes[17] ?? 0;
    }
};
