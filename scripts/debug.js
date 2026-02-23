'use strict';

// Diagnostic script: print all button presses and axis movements
//
// Usage:
//   node index.js scripts/debug.js
//   node index.js scripts/debug.js 2>/dev/null   (suppress startup noise)
//
// Output example:
//   BTN  0x13b (315)  value=1   ← pressed
//   BTN  0x13b (315)  value=0   ← released
//   AXIS 0     (0)    value=16384
//
// Pass the hex code to joystick.find() or use it directly in your script:
//   vjoyB.start = pad.buttons[0x13b] ?? 0;

const pad = joystick.find();

const prevButtons = {};
const prevAxes    = {};
const axisMax     = {};  // observed peak per axis — used to adapt the log threshold

module.exports = {
    loop() {
        if (!pad) return;

        // Print each button transition
        for (const rawCode in pad.buttons) {
            const code = Number(rawCode);
            const val  = pad.buttons[code];
            if (val !== prevButtons[code]) {
                const label = val ? 'pressed ' : 'released';
                console.log(`BTN  0x${code.toString(16).padStart(3, '0')} (${String(code).padStart(3)})  ${label}`);
                prevButtons[code] = val;
            }
        }

        // Print axis changes with adaptive threshold:
        //   - axes with small range (d-pad ±1, triggers 0..255): log every change
        //   - axes with large range (sticks ±32767): log only zero transitions or ≥10% jumps
        // This avoids spam from analog sticks while still showing the full trigger travel.
        for (const rawCode in pad.axes) {
            const code = Number(rawCode);
            const val  = pad.axes[code];

            if (!(code in prevAxes)) {   // first frame: record baseline, don't log
                prevAxes[code] = val;
                axisMax[code]  = Math.abs(val);
                continue;
            }

            const prev = prevAxes[code];
            if (val === prev) continue;

            // Track largest absolute value seen on this axis
            if (Math.abs(val) > axisMax[code]) axisMax[code] = Math.abs(val);
            const range = axisMax[code] || 1;

            // Log if: zero transition (always), or small-range axis, or 10% jump on large axis
            if (val === 0 || prev === 0 || range < 500 || Math.abs(val - prev) >= range * 0.1) {
                console.log(`AXIS ${String(code).padStart(2)} (${String(code).padStart(2)})  value=${val}  range≈${range}`);
                prevAxes[code] = val;
            }
        }
    }
};
