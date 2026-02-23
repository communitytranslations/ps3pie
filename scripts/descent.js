'use strict';

// PS3 DualShock 3 → virtual joystick + keyboard mapping for Descent / Overload
//
// Shift modifiers (hold button, press another):
//   PS     + face/dpad → various keyboard shortcuts (esc, tab, page up/down, home/end)
//   Start  + dpad      → weapon select (1-4) / secondary
//   Select + face      → weapon select (6-9) / zero
//
// Hold PS+Start+Select simultaneously to re-calibrate stick centers.

const buttonMappings = {
    r1: "a",
    r2: "b",
    leftStickButton: "x",
    rightStickButton: "y",
    circle: "tl",
    triangle: "tr"
};

const keyMappings = {
    up: "up",
    down: "down",
    left: "left",
    right: "right",
    cross: "enter",
    square: "space"
};

let shiftState;
let shiftAction;

const shiftMappings = {
    ps: {
        cross:    [ "ralt", "f2" ],
        circle:   [ "ralt", "f3" ],
        square:   "f2",
        triangle: "tab",
        up:       "pageUp",
        down:     "pageDown",
        left:     "home",
        right:    "end"
    },

    start: {
        down:   "one",
        left:   "two",
        up:     "three",
        right:  "four",
        select: "five"
    },

    select: {
        cross:    "six",
        circle:   "seven",
        triangle: "eight",
        square:   "nine",
        start:    "zero"
    }
};

const buttonState = {};
for (const button in buttonMappings) {
    buttonState[button] = 0;
}
for (const button in keyMappings) {
    buttonState[button] = 0;
}
for (const shiftButton in shiftMappings) {
    buttonState[shiftButton] = 0;
    for (const button in shiftMappings[shiftButton]) {
        buttonState[button] = 0;
    }
}

const axisMappings = {
    x:  "leftStickX",
    y:  "leftStickY",
    z:  "rightStickX",
    rx: "rightStickY"
};

const axisInvertion = {
    x:  1,
    y: -1,
    z:  1,
    rx: -1
};

const axisCenters = {};
const axisFactors = {};
for (const axis in axisMappings) {
    axisCenters[axis] = 0;
    axisFactors[axis] = axisInvertion[axis];
}

let vulcanState  = false;
let defaultWeapon = "one";


module.exports = {
    loop() {
        // Hold PS+Start+Select to re-calibrate stick centers
        if (ps3.ps && ps3.start && ps3.select && !(buttonState["ps"] && buttonState["start"] && buttonState["select"])) {
            console.info("Calibrating...");
            for (const axis in axisMappings) {
                const mapping = axisMappings[axis];
                const center  = ps3[mapping];
                const factor  = (1 / (1 - Math.abs(center))) * axisInvertion[axis];

                console.info("Axis: " + axis + ", Mapping: " + mapping + ", Center: " + center + ", Factor: " + factor);

                axisCenters[axis] = center;
                axisFactors[axis] = factor;
            }
            console.info("Calibrated.");
            shiftAction = "calibrate";
        }

        // Apply stick axes with calibration and quadratic curve
        for (const axis in axisMappings) {
            const input  = ps3[axisMappings[axis]];
            let   output = (input - axisCenters[axis]) * axisFactors[axis];
            output = Math.max(-1, Math.min(1, output));
            output = output * Math.abs(output);   // quadratic feel
            vjoyA[axis] = output;
        }

        // L1+L2 combined → slide up; solo L1/L2 → throttle
        if (ps3.l1 && ps3.l2) {
            vjoyB.tl2 = 1;
            vjoyA.ry  = -ps3.l2Analog;
        } else {
            vjoyB.tl2 = 0;
            vjoyA.ry  = ps3.l1Analog - ps3.l2Analog;
        }

        // Shift state machine
        if (!shiftState) {
            for (const shiftButton in shiftMappings) {
                if (!buttonState[shiftButton] && ps3[shiftButton]) {
                    shiftState = shiftButton;
                    console.debug("Shift: " + shiftState);
                }
            }
        } else if (!ps3[shiftState]) {
            if (!shiftAction) {
                if (shiftState === "ps") {
                    keyboardEvents.push("esc");
                } else if (shiftState === "select") {
                    if (vulcanState) {
                        keyboardEvents.push(defaultWeapon);
                    } else {
                        keyboardEvents.push("two");
                    }
                    vulcanState = !vulcanState;
                }
            }

            shiftState  = null;
            shiftAction = null;
            console.debug("No shift");
        } else {
            for (const button in shiftMappings[shiftState]) {
                if (!buttonState[button] && ps3[button]) {
                    shiftAction = button;
                    const action = shiftMappings[shiftState][button];
                    keyboardEvents.push(action);
                    switch (action) {
                        case "one":
                        case "three":
                        case "four":
                        case "five":
                            defaultWeapon = action;
                            vulcanState   = false;
                            break;
                        case "two":
                            vulcanState = true;
                            break;
                    }
                }
            }
        }

        // Direct button → vjoyB (only when no shift active)
        for (const button in buttonMappings) {
            if (!buttonState[button] && ps3[button]) {
                if (!shiftState) vjoyB[buttonMappings[button]] = 1;
            } else if (buttonState[button] && !ps3[button]) {
                vjoyB[buttonMappings[button]] = 0;
            }
        }

        // Direct button → keyboard (only when no shift active)
        for (const button in keyMappings) {
            if (!buttonState[button] && ps3[button]) {
                if (!shiftState) keyboard[keyMappings[button]] = 1;
            } else if (buttonState[button] && !ps3[button]) {
                keyboard[keyMappings[button]] = 0;
            }
        }

        // Update edge-detection state
        for (const button in buttonState) {
            buttonState[button] = ps3[button];
        }
    }
};
