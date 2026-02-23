'use strict';

// Example script: analog stick response curves
//
// Change CURVE to compare different feels:
//
//   'linear'    — 1:1 mapping, equal sensitivity everywhere (default)
//   'quadratic' — x²  precision near center, snappier edges; good for FPS aim
//   'cubic'     — x³  maximum center precision; good for racing / flight
//   'expo'      — blend of linear and cubic, tunable with EXPO_STRENGTH
//   'smoothstep'— smooth S-curve: precise center, soft edges (no snapping)
//
// Visualization (output vs input, right half):
//
//   1.0 ┤         ..smoothstep
//       │      ...·  ··cubic
//       │    ..·  ·  · ·expo (0.5)
//       │  ..·    · ·   ·linear
//       │ .·      ··     ·quadratic
//   0.0 ┼──────────────────── 1.0

const CURVE         = 'expo';
const DEADBAND      = 0.08;   // normalized dead zone around center
const EXPO_STRENGTH = 0.6;    // [0..1]: 0 = linear, 1 = pure cubic

function curve(x) {
    const s = x < 0 ? -1 : 1;
    const a = Math.abs(x);
    switch (CURVE) {
        case 'linear':     return x;
        case 'quadratic':  return s * a * a;
        case 'cubic':      return x * x * x;
        case 'expo':       return s * (EXPO_STRENGTH * a * a * a + (1 - EXPO_STRENGTH) * a);
        case 'smoothstep': return s * a * a * (3 - 2 * a);
        default:           return x;
    }
}

function axis(raw, min, max) {
    return curve(filters.deadband(filters.ensureMapRange(raw, min, max, -1, 1), DEADBAND));
}

const pad = joystick.find();

module.exports = {
    loop() {
        if (!pad) return;

        // Sticks — curve applied
        vjoyA.x  = axis(pad.axes[0] ?? 0, -32767, 32767);
        vjoyA.y  = axis(pad.axes[1] ?? 0, -32767, 32767);
        vjoyA.rx = axis(pad.axes[3] ?? 0, -32767, 32767);
        vjoyA.ry = axis(pad.axes[4] ?? 0, -32767, 32767);

        // Triggers — no curve, just normalize (0..255 → -1..1)
        vjoyA.z  = filters.ensureMapRange(pad.axes[2] ?? 0, 0, 255, -1, 1);
        vjoyA.rz = filters.ensureMapRange(pad.axes[5] ?? 0, 0, 255, -1, 1);

        // Buttons pass-through
        vjoyB.a      = pad.buttons[0x130] ?? 0;
        vjoyB.b      = pad.buttons[0x131] ?? 0;
        vjoyB.x      = pad.buttons[0x133] ?? 0;
        vjoyB.y      = pad.buttons[0x134] ?? 0;
        vjoyB.tl     = pad.buttons[0x136] ?? 0;
        vjoyB.tr     = pad.buttons[0x137] ?? 0;
        vjoyB.select = pad.buttons[0x13a] ?? 0;
        vjoyB.start  = pad.buttons[0x13b] ?? 0;
        vjoyB.mode   = pad.buttons[0x13c] ?? 0;
        vjoyB.thumbl = pad.buttons[0x13d] ?? 0;
        vjoyB.thumbr = pad.buttons[0x13e] ?? 0;

        vjoyA.hat0x = pad.axes[16] ?? 0;
        vjoyA.hat0y = pad.axes[17] ?? 0;
    }
};
