/**
 * remote-control.js — WishIMU phone as wireless lightgun / desktop remote
 *
 * Input:   android[0]  (WishIMU app, UDP port 5555)
 * Outputs: mouse
 *
 * ─── AIM ────────────────────────────────────────────────────────────────────
 *   Tilt phone → mouse cursor (gyroscope angular rate, EMA-smoothed)
 *
 * ─── FIRE ───────────────────────────────────────────────────────────────────
 *   Vol Up  / on-screen "Left Click"  → mouse.left   (sustained, no edge detection)
 *   Vol Down / on-screen "Right Click" → mouse.right
 *
 *   Direct bit mapping: the mouse button is held exactly as long as the
 *   physical/on-screen button is held.  No edge detection, no state machine,
 *   no minimum press duration — rapid fire works at sensor polling rate.
 *
 * ─── SETUP ──────────────────────────────────────────────────────────────────
 *   PS3PIE_BIND_HOST=0.0.0.0 node index.js scripts/remote-control.js
 *
 *   In WishIMU: set IP = this machine's IP, Port = 5555.
 *   Enable "Orientation" + "Raw Data" + "Mouse Buttons".
 *   Vol Up = left click (fire), Vol Down = right click (alt fire / reload).
 */

'use strict';

// ── Sensitivity ──────────────────────────────────────────────────────────────
// Angular rate from gyroscope (rad/s) → pixels.
// Higher = faster cursor for the same wrist movement.
// Tune empirically: try 400–1200 depending on screen size and play distance.
const SENSITIVITY_X = 600;   // horizontal (yaw)
const SENSITIVITY_Y = 600;   // vertical   (pitch)

// EMA smoothing [0 = instant, 1 = frozen].
// Lower values = more responsive, more jitter. 0.15–0.30 is a good range for gaming.
const SMOOTHING = 0.2;

// Deadband (rad/s): ignore micro-tremor below this angular rate.
// Increase if the cursor drifts when the phone is at rest.
const DEADBAND = 0.004;

// ── Main loop ────────────────────────────────────────────────────────────────

module.exports = {
    loop() {
        const phone = android[0];

        // ── Buttons ──────────────────────────────────────────────────────────
        // Direct mapping: no edge detection.  mouse.left/right are sustained
        // (diff-based in vjoy.js), so setting them to 1 each loop keeps the
        // button held for the exact duration of the physical press.
        const bits = phone.buttons ?? 0;
        mouse.left  = (bits & 0x01) ? 1 : 0;
        mouse.right = (bits & 0x02) ? 1 : 0;

        // ── Gyro aiming ──────────────────────────────────────────────────────
        // android[0].raw.gx / gy are in rad/s (angular rate).
        // No need for continuousRotation — rate is already a per-frame delta.
        const rawGy = phone.raw?.gy ?? 0;   // yaw rate   → horizontal cursor
        const rawGx = phone.raw?.gx ?? 0;   // pitch rate → vertical cursor

        // Deadband suppresses hand tremor; simple() smooths jitter.
        const smoothGy = filters.simple(filters.deadband(rawGy, DEADBAND), SMOOTHING, 'gy');
        const smoothGx = filters.simple(filters.deadband(rawGx, DEADBAND), SMOOTHING, 'gx');

        mouse.x = Math.round(smoothGy * SENSITIVITY_X);
        mouse.y = Math.round(smoothGx * SENSITIVITY_Y);
    }
};
