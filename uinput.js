'use strict';

const koffi = require('koffi');
const fs = require('fs');

// --- Linux input constants ---
const EV_SYN = 0;
const EV_KEY = 1;
const EV_ABS = 3;
const SYN_REPORT = 0;
const BUS_VIRTUAL = 6;

// uinput ioctl request codes (from linux/uinput.h)
// _IO('U', 1)          = (0 << 30) | (0x55 << 8) | 1
// _IOW('U', N, int)    = (1 << 30) | (4 << 16) | (0x55 << 8) | N
const UI_DEV_CREATE  = 0x5501;
const UI_DEV_DESTROY = 0x5502;
const UI_SET_EVBIT   = 0x40045564;
const UI_SET_KEYBIT  = 0x40045565;
const UI_SET_ABSBIT  = 0x40045567;

// --- Key codes (linux/input-event-codes.h) ---
const keyCodes = {
    esc:      1,
    one:      2,
    two:      3,
    three:    4,
    four:     5,
    five:     6,
    six:      7,
    seven:    8,
    eight:    9,
    nine:     10,
    zero:     11,
    minus:    12,
    equals:   13,
    backspace:14,
    tab:      15,
    // Letters (KEY_Q=16 .. KEY_M=50)
    q:        16,
    w:        17,
    e:        18,
    r:        19,
    t:        20,
    y:        21,
    u:        22,
    i:        23,
    o:        24,
    p:        25,
    a:        30,
    s:        31,
    d:        32,
    f:        33,
    g:        34,
    h:        35,
    j:        36,
    k:        37,
    l:        38,
    z:        44,
    x:        45,
    c:        46,
    v:        47,
    b:        48,
    n:        49,
    m:        50,
    comma:    51,
    period:   52,
    enter:    28,
    lctrl:    29,
    capslock: 58,
    lshift:   42,
    shift:    54,   // alias for rshift (backward compat)
    lalt:     56,
    space:    57,
    rctrl:    97,
    ralt:     100,
    super:    125,
    // Function keys
    f1:       59,
    f2:       60,
    f3:       61,
    f4:       62,
    f5:       63,
    f6:       64,
    f7:       65,
    f8:       66,
    f9:       67,
    f10:      68,
    f11:      87,
    f12:      88,
    // Navigation
    home:     102,
    up:       103,
    pageUp:   104,
    left:     105,
    right:    106,
    end:      107,
    down:     108,
    pageDown: 109,
    insert:   110,
    del:      111,
};

// --- Button codes (BTN_*) ---
const buttonCodes = {
    a:      0x130,
    b:      0x131,
    x:      0x133,
    y:      0x134,
    tl:     0x136,
    tr:     0x137,
    tl2:    0x138,
    tr2:    0x139,
    select: 0x13a,
    start:  0x13b,
    mode:   0x13c,
    thumbl: 0x13d,
    thumbr: 0x13e,
    up:     0x220,
    down:   0x221,
    left:   0x222,
    right:  0x223,
};

// --- Axis codes (ABS_*) ---
const axisCodes = {
    x:     0,   // ABS_X     left stick horizontal
    y:     1,   // ABS_Y     left stick vertical
    z:     2,   // ABS_Z     left trigger
    rx:    3,   // ABS_RX    right stick horizontal
    ry:    4,   // ABS_RY    right stick vertical
    rz:    5,   // ABS_RZ    right trigger
    hat0x: 16,  // ABS_HAT0X d-pad horizontal (-1=left, 0=center, 1=right)
    hat0y: 17,  // ABS_HAT0Y d-pad vertical   (-1=up,   0=center, 1=down)
};

// --- koffi FFI: ioctl from libc ---
const lib = koffi.load('libc.so.6');
// int ioctl(int fd, unsigned long request, long value)
// Using fixed 3-arg form (not variadic) — sufficient for all uinput ioctls.
const ioctl = lib.func('ioctl', 'int', ['int', 'ulong', 'long']);

// --- Struct builders ---

// uinput_user_dev: 1116 bytes (linux/uinput.h)
//   offset   0: name[80]        char
//   offset  80: input_id        { bustype, vendor, product, version } uint16×4
//   offset  88: ff_effects_max  uint32 = 0
//   offset  92: absmax[64]      int32×64
//   offset 348: absmin[64]      int32×64
//   offset 604: absfuzz[64]     int32×64 (zeros)
//   offset 860: absflat[64]     int32×64 (zeros)
function makeUinputUserDev(name, absMax, absMin) {
    const buf = Buffer.alloc(1116, 0);
    buf.write(name, 0, 'ascii');
    buf.writeUInt16LE(BUS_VIRTUAL, 80);  // bustype
    buf.writeUInt16LE(0x1, 82);          // vendor
    buf.writeUInt16LE(0x1, 84);          // product
    buf.writeUInt16LE(1, 86);            // version
    // ff_effects_max at 88 = 0 (already zeroed)
    for (const [code, max] of Object.entries(absMax)) {
        buf.writeInt32LE(max, 92 + parseInt(code) * 4);
    }
    for (const [code, min] of Object.entries(absMin)) {
        buf.writeInt32LE(min, 348 + parseInt(code) * 4);
    }
    return buf;
}

// input_event: 24 bytes
//   offset  0: timeval (16 bytes, zeros)
//   offset 16: type   uint16
//   offset 18: code   uint16
//   offset 20: value  int32
function makeInputEvent(type, code, value) {
    const buf = Buffer.alloc(24, 0);
    buf.writeUInt16LE(type, 16);
    buf.writeUInt16LE(code, 18);
    buf.writeInt32LE(value, 20);
    return buf;
}

// --- State ---
var fd = -1;

function writeEvent(type, code, value) {
    const buf = makeInputEvent(type, code, value);
    fs.writeSync(fd, buf, 0, buf.length);
}

// --- Setup / teardown ---
var setupPromise;

async function setup() {
    console.info("Setting up uinput...");

    fd = fs.openSync('/dev/uinput', fs.constants.O_WRONLY);

    // Enable event types
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    // Enable key codes
    for (const code of Object.values(keyCodes)) {
        ioctl(fd, UI_SET_KEYBIT, code);
    }

    // Enable button codes
    for (const code of Object.values(buttonCodes)) {
        ioctl(fd, UI_SET_KEYBIT, code);
    }

    // Enable axis codes
    for (const code of Object.values(axisCodes)) {
        ioctl(fd, UI_SET_ABSBIT, code);
    }

    // Build absmax / absmin maps keyed by axis code number
    const absMax = {};
    const absMin = {};
    for (const code of Object.values(axisCodes)) {
        absMax[code] = 1000;
        absMin[code] = 0;
    }

    // Write device descriptor and create
    const devBuf = makeUinputUserDev('ps3pie', absMax, absMin);
    fs.writeSync(fd, devBuf, 0, devBuf.length);
    ioctl(fd, UI_DEV_CREATE, 0);

    console.info("uinput device created");
}

function setupSync() {
    setupPromise = setup();
    return setupPromise;
}

// --- Public API (interface identical to original uinput.js) ---
module.exports = {
    setup() {
        return setupPromise || setupSync();
    },

    teardown() {
        if (fd >= 0) {
            console.log("Destroying uinput device...");
            ioctl(fd, UI_DEV_DESTROY, 0);
            fs.closeSync(fd);
            fd = -1;
        }
    },

    async keyPress(code) {
        writeEvent(EV_KEY, keyCodes[code], 1);
        writeEvent(EV_KEY, keyCodes[code], 0);
    },

    async keyCombo(codes) {
        for (const c of codes) {
            writeEvent(EV_KEY, keyCodes[c], 1);
        }
        for (const c of codes) {
            writeEvent(EV_KEY, keyCodes[c], 0);
        }
        writeEvent(EV_SYN, SYN_REPORT, 0);
    },

    async key(code, value) {
        writeEvent(EV_KEY, keyCodes[code], value);
    },

    async button(code, value) {
        writeEvent(EV_KEY, buttonCodes[code], value);
    },

    async axis(code, value) {
        writeEvent(EV_ABS, axisCodes[code], value);
    },

    async sync() {
        writeEvent(EV_SYN, SYN_REPORT, 0);
    },
};
