'use strict';

// Input plugin: Android IMU via UDP (FreePIE Android IMU protocol)
//
// Binary Little-Endian protocol, port 5555:
//   byte  0     : device index (0-15)
//   byte  1     : flags  (0x01 = SEND_RAW, 0x02 = SEND_ORIENTATION, 0x04 = SEND_BUTTONS)
//   bytes 2-37  : raw sensor data (only if flag 0x01)
//                   floatLE × 3 : acc  (ax, ay, az)   — m/s²
//                   floatLE × 3 : gyro (gx, gy, gz)   — rad/s
//                   floatLE × 3 : mag  (mx, my, mz)   — µT
//   bytes 38-49 : orientation   (only if flag 0x02, after raw if both flags set)
//                   floatLE     : yaw   (Euler Z) — radians
//                   floatLE     : pitch (Euler X) — radians
//                   floatLE     : roll  (Euler Y) — radians
//   byte  N     : button bitmask (only if flag 0x04; bit 0 = fire/left-click)
//
// Usage in scripts:
//   const phone = android[0];     // device index 0
//   phone.yaw, phone.pitch, phone.roll          // orientation (radians)
//   phone.raw.ax, .ay, .az                      // accelerometer
//   phone.raw.gx, .gy, .gz                      // gyroscope
//   phone.raw.mx, .my, .mz                      // magnetometer
//   phone.buttons                               // bitmask (bit 0 = fire)
//
// Official app: "FreePIE IMU sender" (APK at /opt/FreePIE/Lib/Android/)
// Also compatible with WishIMU (github.com/communitytranslations/ps3pie)

const dgram       = require('dgram');
const EventEmitter = require('events');
const Plugin       = require('../plugin');

const DEFAULT_PORT          = 5555;
const FLAG_SEND_RAW         = 0x01;
const FLAG_SEND_ORIENTATION = 0x02;
const FLAG_SEND_BUTTONS     = 0x04;

// Bind to localhost by default — prevents any host on the network from injecting
// orientation data and controlling mouse/joystick movement.
// Set PS3PIE_BIND_HOST=0.0.0.0 to accept packets from the network (e.g. real phone).
const BIND_HOST = process.env.PS3PIE_BIND_HOST || '127.0.0.1';

function makeDevice() {
    return {
        yaw: 0, pitch: 0, roll: 0,
        raw: { ax: 0, ay: 0, az: 0, gx: 0, gy: 0, gz: 0, mx: 0, my: 0, mz: 0 },
        buttons: 0,
    };
}

class AndroidPlugin extends Plugin {
    constructor() {
        super();
        this._devices = {};
        this._emitter = new EventEmitter();
        this._socket  = null;
        this._proxy   = new Proxy({}, {
            get: (_, n) => {
                if (typeof n === 'symbol' || !/^\d+$/.test(String(n))) return undefined;
                const i = Number(n);
                if (!(i in this._devices)) this._devices[i] = makeDevice();
                return this._devices[i];
            }
        });
    }

    get friendlyName() { return 'Android IMU (UDP)'; }
    get globalName()   { return 'android'; }
    createGlobal()     { return this._proxy; }

    async start() {
        this._socket = dgram.createSocket('udp4');
        this._socket.on('message', (msg) => this._onMessage(msg));
        this._socket.on('error',   (err) => console.warn(`[android] UDP error: ${err.message}`));
        await new Promise(resolve => {
            this._socket.once('error', err => {
                console.warn(`[android] Cannot bind port ${DEFAULT_PORT}: ${err.message}`);
                this._socket = null;
                resolve();   // non-fatal: remaining plugins keep working
            });
            this._socket.bind(DEFAULT_PORT, BIND_HOST, () => {
                console.info(`[android] Listening on UDP ${BIND_HOST}:${DEFAULT_PORT}`);
                resolve();
            });
        });
    }

    _onMessage(msg) {
        if (msg.length < 2) return;
        const idx   = msg[0];
        const flags = msg[1];

        if (!(idx in this._devices)) this._devices[idx] = makeDevice();
        const dev = this._devices[idx];

        let offset = 2;

        if ((flags & FLAG_SEND_RAW) && msg.length >= offset + 36) {
            dev.raw.ax = msg.readFloatLE(offset);  offset += 4;
            dev.raw.ay = msg.readFloatLE(offset);  offset += 4;
            dev.raw.az = msg.readFloatLE(offset);  offset += 4;
            dev.raw.gx = msg.readFloatLE(offset);  offset += 4;
            dev.raw.gy = msg.readFloatLE(offset);  offset += 4;
            dev.raw.gz = msg.readFloatLE(offset);  offset += 4;
            dev.raw.mx = msg.readFloatLE(offset);  offset += 4;
            dev.raw.my = msg.readFloatLE(offset);  offset += 4;
            dev.raw.mz = msg.readFloatLE(offset);  offset += 4;
        }

        if ((flags & FLAG_SEND_ORIENTATION) && msg.length >= offset + 12) {
            const yaw   = msg.readFloatLE(offset);
            const pitch = msg.readFloatLE(offset + 4);
            const roll  = msg.readFloatLE(offset + 8);
            if (isFinite(yaw))   dev.yaw   = yaw;
            if (isFinite(pitch)) dev.pitch = pitch;
            if (isFinite(roll))  dev.roll  = roll;
            offset += 12;
        }

        if ((flags & FLAG_SEND_BUTTONS) && msg.length >= offset + 1) {
            dev.buttons = msg[offset];
        }

        this._emitter.emit('data');
    }

    async stop() {
        if (this._socket) {
            await new Promise(resolve => this._socket.close(resolve));
            this._socket = null;
        }
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

module.exports = AndroidPlugin;
