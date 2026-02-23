'use strict';

// Example script: MIDI controller → virtual joystick + keyboard shortcuts
//
// CC knobs/sliders → vjoyA axes
// Note pads        → vjoyB buttons
//
// List available MIDI ports:  aconnect -i
// Set DEVICE to the port index shown there (0 = first port)

const DEVICE = 0;

// MIDI status types (high nibble of the status byte)
const NOTE_OFF = 0x8;
const NOTE_ON  = 0x9;
const CONTROL  = 0xB;

const controller = midi[DEVICE];

// Persistent button state — updated on Note On/Off messages
// Needed because the status holds the last message, not a live level
const noteState = {};

module.exports = {
    loop() {
        // --- CC messages → axes ---
        // CC values are 0-127; mapRange converts them to [-1, 1]
        if (controller.status === CONTROL) {
            switch (controller.cc) {
                case 1:    // Mod Wheel → X axis
                    vjoyA.x = filters.mapRange(controller.value, 0, 127, -1, 1);
                    break;
                case 2:    // CC2 (any knob/slider) → Y axis
                    vjoyA.y = filters.mapRange(controller.value, 0, 127, -1, 1);
                    break;
                case 7:    // Volume → Z axis
                    vjoyA.z = filters.mapRange(controller.value, 0, 127, -1, 1);
                    break;
                case 10:   // Pan → RX axis
                    vjoyA.rx = filters.mapRange(controller.value, 0, 127, -1, 1);
                    break;
            }
        }

        // --- Note messages → buttons ---
        // Track note state across frames so buttons work as level triggers
        if (controller.status === NOTE_ON)  noteState[controller.note] = 1;
        if (controller.status === NOTE_OFF) noteState[controller.note] = 0;

        vjoyB.a = noteState[36] ?? 0;   // C2 → button A
        vjoyB.b = noteState[38] ?? 0;   // D2 → button B
        vjoyB.x = noteState[40] ?? 0;   // E2 → button X
        vjoyB.y = noteState[41] ?? 0;   // F2 → button Y

        // One-shot keyboard shortcuts on specific notes
        // (fires once per Note On message — safe because the loop only runs on new MIDI events)
        if (controller.status === NOTE_ON) {
            if (controller.note === 48) keyboardEvents.push('enter');    // C3
            if (controller.note === 50) keyboardEvents.push('escape');   // D3
            if (controller.note === 52) keyboardEvents.push(['ctrl', 's']); // E3 → Ctrl+S
        }
    }
};
