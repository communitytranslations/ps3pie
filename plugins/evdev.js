'use strict';

// Generic joystick input via evdev (/dev/input/eventN)
//
// input_event struct (24 bytes, Linux 64-bit):
//   offset  0 : tv_sec  (int64)
//   offset  8 : tv_usec (int64)
//   offset 16 : type    (uint16)  — EV_SYN=0, EV_KEY=1, EV_ABS=3
//   offset 18 : code    (uint16)  — axis (ABS_*) or button (BTN_*)
//   offset 20 : value   (int32)   — raw: -32767..32767 for axes, 0/1 for buttons
//
// Axes (ABS_*):   X=0, Y=1, Z=2, RX=3, RY=4, RZ=5, HAT0X=16, HAT0Y=17
// Buttons (BTN_*): SOUTH=0x130, EAST=0x131, NORTH=0x133, WEST=0x134,
//                  TL=0x136, TR=0x137, TL2=0x138, TR2=0x139,
//                  SELECT=0x13a, START=0x13b, MODE=0x13c,
//                  THUMBL=0x13d, THUMBR=0x13e

const fs           = require('fs');
const koffi        = require('koffi');
const EventEmitter = require('events');
const Plugin       = require('../plugin');

const EV_SYN = 0;
const EV_KEY = 1;
const EV_ABS = 3;

const BUF_SIZE = 24;
const POLL_MS  = 4;   // 250 Hz — sufficient for any gamepad (typical rate: 125 Hz)

// O_NONBLOCK: fs.readSync returns EAGAIN when no data is available instead of blocking.
const O_RDONLY_NONBLOCK = fs.constants.O_RDONLY | fs.constants.O_NONBLOCK;

// EVIOCGRAB = _IOW('E', 0x90, int) = 0x40044590
// Grants exclusive access to the device: no other process receives its events.
const EVIOCGRAB = 0x40044590;

const lib   = koffi.load('libc.so.6');
const ioctl = lib.func('ioctl', 'int', ['int', 'ulong', 'long']);

class EvdevDevice {
    constructor(n) {
        this._path    = `/dev/input/event${n}`;
        this._fd      = -1;
        this._timer   = null;
        this._buf     = Buffer.allocUnsafe(BUF_SIZE * 32);  // up to 32 events per tick
        this._partial = null;
        this._emitter = new EventEmitter();
        this.axes     = {};
        this.buttons  = {};
    }

    open() {
        try {
            this._fd = fs.openSync(this._path, O_RDONLY_NONBLOCK);
        } catch (err) {
            console.warn(`[evdev] Cannot open ${this._path}: ${err.message}`);
            return;
        }
        try {
            const ret = ioctl(this._fd, EVIOCGRAB, 1);
            if (ret < 0) {
                console.warn(`[evdev] EVIOCGRAB failed on ${this._path} (ret=${ret}; device may be grabbed by another process)`);
            } else {
                console.info(`[evdev] Grabbed ${this._path} exclusively`);
            }
        } catch (err) {
            console.warn(`[evdev] EVIOCGRAB error on ${this._path}: ${err.message ?? err}`);
        }
        this._timer = setInterval(() => this._poll(), POLL_MS);
    }

    // Drain all available events in this tick. Each call is O(1) amortized.
    _poll() {
        while (this._fd >= 0) {
            let n;
            try {
                n = fs.readSync(this._fd, this._buf, 0, this._buf.length, null);
            } catch (err) {
                if (err.code === 'EAGAIN' || err.code === 'EWOULDBLOCK') return;  // no data available, end of drain
                console.warn(`[evdev] ${this._path}: ${err.message}`);
                return;
            }
            if (n === 0) return;
            this._onChunk(this._buf.subarray(0, n));
        }
    }

    _onChunk(chunk) {
        const buf  = this._partial ? Buffer.concat([this._partial, chunk]) : chunk;
        const full = Math.floor(buf.length / BUF_SIZE) * BUF_SIZE;
        for (let i = 0; i < full; i += BUF_SIZE) {
            this._processEvent(buf.subarray(i, i + BUF_SIZE));
        }
        this._partial = buf.length > full ? buf.subarray(full) : null;
    }

    _processEvent(buf) {
        const type  = buf.readUInt16LE(16);
        const code  = buf.readUInt16LE(18);
        const value = buf.readInt32LE(20);

        if (type === EV_ABS) {
            this.axes[code] = value;
        } else if (type === EV_KEY) {
            this.buttons[code] = value ? 1 : 0;
        } else if (type === EV_SYN) {
            this._emitter.emit('data');
        }
    }

    close() {
        if (this._timer) {
            clearInterval(this._timer);   // stop polling first
            this._timer = null;
        }
        if (this._fd >= 0) {
            const fd = this._fd;
            this._fd = -1;
            ioctl(fd, EVIOCGRAB, 0);      // release grab before closing
            fs.closeSync(fd);             // safe: no concurrent reads in thread pool
        }
        this._partial = null;
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

class EvdevPlugin extends Plugin {
    constructor() {
        super();
        this._devices = {};
        this._emitter = new EventEmitter();
        this._proxy   = new Proxy({}, {
            get: (_, n) => {
                if (typeof n === 'symbol' || !/^\d+$/.test(String(n))) return undefined;
                const i = Number(n);
                if (!(i in this._devices)) {
                    const dev = new EvdevDevice(i);
                    dev.on('data', () => this._emitter.emit('data'));
                    dev.open();
                    this._devices[i] = dev;
                }
                return this._devices[i];
            }
        });
    }

    get friendlyName() { return 'Generic Joystick (evdev)'; }
    get globalName()   { return 'joystick'; }
    createGlobal()     { return this._proxy; }

    async start() {}

    async stop() {
        for (const dev of Object.values(this._devices)) dev.close();
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

module.exports = EvdevPlugin;
