'use strict';

// Escritura de ratón virtual vía uinput (segundo dispositivo: "ps3pie-mouse")
//
// Expone el global `mouse` en los scripts:
//   mouse.x      — movimiento relativo horizontal (píxeles/frame, reset a 0 tras emit)
//   mouse.y      — movimiento relativo vertical   (píxeles/frame, reset a 0 tras emit)
//   mouse.wheel  — rueda de scroll (ticks/frame, reset a 0 tras emit)
//   mouse.left   — botón izquierdo  (0/1, nivel)
//   mouse.right  — botón derecho    (0/1, nivel)
//   mouse.middle — botón central    (0/1, nivel)

const fs     = require('fs');
const koffi  = require('koffi');
const Plugin = require('../plugin');

const EV_SYN = 0;
const EV_KEY = 1;
const EV_REL = 2;
const SYN_REPORT = 0;
const BUS_VIRTUAL = 6;

const REL_X     = 0;
const REL_Y     = 1;
const REL_WHEEL = 8;

const BTN_LEFT   = 0x110;
const BTN_RIGHT  = 0x111;
const BTN_MIDDLE = 0x112;

const UI_DEV_CREATE  = 0x5501;
const UI_DEV_DESTROY = 0x5502;
const UI_SET_EVBIT   = 0x40045564;
const UI_SET_KEYBIT  = 0x40045565;
const UI_SET_RELBIT  = 0x40045566;

const lib   = koffi.load('libc.so.6');
const ioctl = lib.func('ioctl', 'int', ['int', 'ulong', 'long']);

// uinput_user_dev: 1116 bytes. Sin ejes ABS → absmax/absmin/absfuzz/absflat quedan a cero.
function makeUinputUserDev(name) {
    const buf = Buffer.alloc(1116, 0);
    buf.write(name, 0, 'ascii');
    buf.writeUInt16LE(BUS_VIRTUAL, 80);  // bustype
    buf.writeUInt16LE(0x1, 82);          // vendor
    buf.writeUInt16LE(0x1, 84);          // product
    buf.writeUInt16LE(1,   86);          // version
    return buf;
}

function makeInputEvent(type, code, value) {
    const buf = Buffer.alloc(24, 0);
    buf.writeUInt16LE(type,  16);
    buf.writeUInt16LE(code,  18);
    buf.writeInt32LE(value,  20);
    return buf;
}

class MousePlugin extends Plugin {
    constructor() {
        super();
        this._fd     = -1;
        this._prev   = { left: 0, right: 0, middle: 0 };
        this._global = { x: 0, y: 0, wheel: 0, left: 0, right: 0, middle: 0 };
    }

    get friendlyName() { return 'Mouse (uinput)'; }
    get globalName()   { return 'mouse'; }
    createGlobal()     { return this._global; }

    async start() {
        console.info('Setting up mouse uinput device...');

        this._fd = fs.openSync('/dev/uinput', fs.constants.O_WRONLY);
        const fd = this._fd;

        ioctl(fd, UI_SET_EVBIT, EV_REL);
        ioctl(fd, UI_SET_RELBIT, REL_X);
        ioctl(fd, UI_SET_RELBIT, REL_Y);
        ioctl(fd, UI_SET_RELBIT, REL_WHEEL);

        ioctl(fd, UI_SET_EVBIT, EV_KEY);
        ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
        ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
        ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);

        const devBuf = makeUinputUserDev('ps3pie-mouse');
        fs.writeSync(fd, devBuf, 0, devBuf.length);
        ioctl(fd, UI_DEV_CREATE, 0);

        console.info('Mouse uinput device created');
    }

    async stop() {
        if (this._fd >= 0) {
            ioctl(this._fd, UI_DEV_DESTROY, 0);
            fs.closeSync(this._fd);
            this._fd = -1;
        }
    }

    _write(type, code, value) {
        const buf = makeInputEvent(type, code, value);
        fs.writeSync(this._fd, buf, 0, buf.length);
    }

    doAfterExecute() {
        if (this._fd < 0) return;

        const g = this._global;
        let dirty = false;

        // Ejes relativos: emitir si hay movimiento, luego resetear a 0
        if (g.x !== 0)     { this._write(EV_REL, REL_X,     g.x);     g.x     = 0; dirty = true; }
        if (g.y !== 0)     { this._write(EV_REL, REL_Y,     g.y);     g.y     = 0; dirty = true; }
        if (g.wheel !== 0) { this._write(EV_REL, REL_WHEEL, g.wheel); g.wheel = 0; dirty = true; }

        // Botones: emitir solo cuando cambia el estado
        for (const [name, code] of [['left', BTN_LEFT], ['right', BTN_RIGHT], ['middle', BTN_MIDDLE]]) {
            const val = g[name] ? 1 : 0;
            if (val !== this._prev[name]) {
                this._write(EV_KEY, code, val);
                this._prev[name] = val;
                dirty = true;
            }
        }

        if (dirty) this._write(EV_SYN, SYN_REPORT, 0);
    }
}

module.exports = MousePlugin;
