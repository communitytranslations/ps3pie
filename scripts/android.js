'use strict';

// Example script: Android phone → gyroscopic mouse aiming + fire button
//
// Move the phone to aim — yaw controls horizontal, pitch controls vertical.
// Press the FIRE button in the WishIMU app to left-click (bit 0 of buttons bitmask).
//
// Compatible with:
//   - WishIMU app (github.com/communitytranslations/ps3pie)
//   - FreePIE IMU sender APK (at /opt/FreePIE/Lib/Android/)
//
// Setup:
//   PS3PIE_BIND_HOST=0.0.0.0 node index.js scripts/android.js
//   Open WishIMU, set destination IP to this machine's IP, tap Start.
//
// Tuning:
//   SENSITIVITY — pixels per radian per frame (22.9 ≈ 0.4 px/°)
//   SMOOTHING   — increase to reduce jitter, decrease for lower latency

const SENSITIVITY = 2500;  // pixels per radian of rotation per frame (22.9 default)
                           // (≈ 0.4 px/° × 57.3 °/rad — the Android app sends radians)
const SMOOTHING   = 0.3;   // EMA smoothing coefficient (0=none, 1=frozen)

const phone = android[0];  // device index 0

module.exports = {
    loop() {
        // continuousRotation unwraps the angle to prevent jumps at the ±π boundary.
        // The FreePIE Android app uses SensorManager.getOrientation() which returns radians.
        const yawCont   = filters.continuousRotation(phone.yaw,   'radians', 'yaw');
        const pitchCont = filters.continuousRotation(phone.pitch, 'radians', 'pitch');

        const dyaw   = filters.delta(yawCont,   'yaw');
        const dpitch = filters.delta(pitchCont, 'pitch');

        // Optional EMA smoothing to reduce sensor noise
        const smoothYaw   = filters.simple(dyaw,   SMOOTHING, 'syaw');
        const smoothPitch = filters.simple(dpitch, SMOOTHING, 'spitch');

        mouse.x = Math.round(smoothYaw   * SENSITIVITY);
        mouse.y = Math.round(smoothPitch * SENSITIVITY);

        // Bit 0 = left click, bit 1 = right click
        mouse.left  = phone.buttons & 0x01;
        mouse.right = (phone.buttons >> 1) & 0x01;
    }
};
