'use strict';

// Example script: Android phone → gyroscopic mouse aiming
//
// Hold the phone and move it to aim — yaw controls horizontal movement,
// pitch controls vertical. Useful for FPS games or desktop pointer control.
//
// Requirements:
//   - Install "FreePIE IMU sender" APK (at /opt/FreePIE/Lib/Android/)
//   - Open the app, set the destination IP to this machine's IP
//   - Enable "Send Orientation" in the app settings
//   - Both devices must be on the same network
//
// Tuning:
//   SENSITIVITY — increase for faster movement, decrease for precision
//   SMOOTHING   — increase to reduce jitter, decrease for lower latency

const SENSITIVITY = 0.4;   // pixels per degree of rotation per frame
const SMOOTHING   = 0.3;   // EMA smoothing coefficient (0=none, 1=frozen)

const phone = android[0];  // device index 0

module.exports = {
    loop() {
        // continuousRotation unwraps the angle to prevent jumps at the ±180° boundary
        // delta gives the frame-to-frame change in the unwrapped angle
        const yawCont   = filters.continuousRotation(phone.yaw,   'degrees', 'yaw');
        const pitchCont = filters.continuousRotation(phone.pitch, 'degrees', 'pitch');

        const dyaw   = filters.delta(yawCont,   'yaw');
        const dpitch = filters.delta(pitchCont, 'pitch');

        // Optional EMA smoothing to reduce sensor noise
        const smoothYaw   = filters.simple(dyaw,   SMOOTHING, 'syaw');
        const smoothPitch = filters.simple(dpitch, SMOOTHING, 'spitch');

        mouse.x = Math.round(smoothYaw   * SENSITIVITY);
        mouse.y = Math.round(smoothPitch * SENSITIVITY);
    }
};
