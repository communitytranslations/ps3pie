'use strict';

// Example script: OpenTrack / HeadMob head-tracker → mouse aiming
//
// Yaw controls horizontal mouse movement, pitch controls vertical.
// Useful for head-tracking in flight simulators, FPS games, or VR.
//
// Requirements:
//   - OpenTrack: Output → "UDP over network", host = this machine's IP, port = 4242
//   - HeadMob (Android): set destination IP and port 4242 in the app
//   - Both devices must be on the same network
//
// To receive packets from the network (OpenTrack on another PC, or HeadMob):
//   PS3PIE_BIND_HOST=0.0.0.0 node index.js scripts/opentrack.js
//
// Tuning:
//   SENSITIVITY — pixels per degree per frame
//   SMOOTHING   — increase to reduce jitter, decrease for lower latency

const SENSITIVITY = 10;   // pixels per degree of head rotation per frame
const SMOOTHING   = 0.2;  // EMA smoothing coefficient (0=none, 1=frozen)

module.exports = {
    loop() {
        const dyaw   = filters.delta(opentrack.yaw,   'yaw');
        const dpitch = filters.delta(opentrack.pitch, 'pitch');

        const smoothYaw   = filters.simple(dyaw,   SMOOTHING, 'syaw');
        const smoothPitch = filters.simple(dpitch, SMOOTHING, 'spitch');

        mouse.x = Math.round(smoothYaw   * SENSITIVITY);
        mouse.y = Math.round(smoothPitch * SENSITIVITY);
    }
};
