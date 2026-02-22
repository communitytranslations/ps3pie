'use strict';

// dummy.js — creates a persistent virtual "ps3pie" uinput device with no
// activity, to pre-occupy a joystick slot before plugging the physical
// controller (workaround for games that assign fixed joystick indices).

const koffi = require('koffi');
const fs = require('fs');

// --- Linux constants ---
const EV_KEY = 1;
const EV_ABS = 3;
const BUS_VIRTUAL = 6;

const UI_DEV_CREATE  = 0x5501;
const UI_SET_EVBIT   = 0x40045564;
const UI_SET_KEYBIT  = 0x40045565;
const UI_SET_ABSBIT  = 0x40045567;

const BUTTON_CODES = [
    0x130, // BTN_A
    0x131, // BTN_B
    0x133, // BTN_X
    0x134, // BTN_Y
    0x136, // BTN_TL
    0x137, // BTN_TR
    0x138, // BTN_TL2
    0x139, // BTN_TR2
    0x13a, // BTN_SELECT
    0x13b, // BTN_START
    0x13c, // BTN_MODE
    0x13d, // BTN_THUMBL
    0x13e, // BTN_THUMBR
    0x220, // BTN_DPAD_UP
    0x221, // BTN_DPAD_DOWN
    0x222, // BTN_DPAD_LEFT
    0x223, // BTN_DPAD_RIGHT
];

const AXIS_CODES = [0, 1, 2, 3, 4, 5]; // ABS_X through ABS_RZ

// --- koffi ---
const lib = koffi.load('libc.so.6');
const ioctl = lib.func('ioctl', 'int', ['int', 'ulong', 'long']);

// uinput_user_dev struct (1116 bytes)
function makeUinputUserDev(name, absMax) {
    const buf = Buffer.alloc(1116, 0);
    buf.write(name, 0, 'ascii');
    buf.writeUInt16LE(BUS_VIRTUAL, 80);
    buf.writeUInt16LE(0x1, 82);
    buf.writeUInt16LE(0x1, 84);
    buf.writeUInt16LE(1, 86);
    for (const [code, max] of Object.entries(absMax)) {
        buf.writeInt32LE(max, 92 + parseInt(code) * 4);
    }
    return buf;
}

async function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function main() {
    const fd = fs.openSync('/dev/uinput', fs.constants.O_WRONLY);

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    for (const code of BUTTON_CODES) {
        ioctl(fd, UI_SET_KEYBIT, code);
    }
    for (const code of AXIS_CODES) {
        ioctl(fd, UI_SET_ABSBIT, code);
    }

    const absMax = {};
    for (const code of AXIS_CODES) {
        absMax[code] = 255;
    }

    const devBuf = makeUinputUserDev('ps3pie', absMax);
    fs.writeSync(fd, devBuf, 0, devBuf.length);
    ioctl(fd, UI_DEV_CREATE, 0);

    console.log("Dummy ps3pie device created. Press Ctrl+C to exit.");

    while (true) {
        await sleep(1000);
    }
}

(async () => {
    try {
        await main();
    } catch (err) {
        console.log(err);
    }
})();
