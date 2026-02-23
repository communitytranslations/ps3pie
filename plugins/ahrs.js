'use strict';

// Input plugin: AHRS IMU via serial port (SEN-10736 / SparkFun 9DOF protocol)
//
// Port: PS3PIE_AHRS_PORT environment variable (default: /dev/ttyUSB0)
// Baud rate: 57600
//
// Initialization commands (ASCII, sent to device on open):
//   #ob  — enable binary output
//   #o1  — enable continuous streaming mode
//   #oe0 — disable error messages
//
// Binary protocol (12 bytes per sample, Little-Endian):
//   bytes 0-3  : yaw   (float32) — radians
//   bytes 4-7  : pitch (float32) — radians
//   bytes 8-11 : roll  (float32) — radians
//
// Usage in scripts:
//   const y = ahrsImu.yaw;    // −π..+π
//   const p = ahrsImu.pitch;
//   const r = ahrsImu.roll;

const { SerialPort }    = require('serialport');
const EventEmitter       = require('events');
const Plugin             = require('../plugin');

const DEFAULT_PORT  = process.env.PS3PIE_AHRS_PORT || '/dev/ttyUSB0';
const BAUD_RATE     = 57600;
const FRAME_SIZE    = 12;   // 3 × float32

class AhrsPlugin extends Plugin {
    constructor() {
        super();
        this._global  = { yaw: 0, pitch: 0, roll: 0 };
        this._emitter = new EventEmitter();
        this._port    = null;
        this._buf     = Buffer.allocUnsafe(0);
    }

    get friendlyName() { return 'AHRS IMU (serial)'; }
    get globalName()   { return 'ahrsImu'; }
    createGlobal()     { return this._global; }

    async start() {
        const sp = new SerialPort({ path: DEFAULT_PORT, baudRate: BAUD_RATE, autoOpen: false });

        await new Promise(resolve => {
            sp.open(err => {
                if (err) {
                    console.warn(`[ahrs] Cannot open ${DEFAULT_PORT}: ${err.message}`);
                    resolve();
                    return;
                }
                console.info(`[ahrs] Opened ${DEFAULT_PORT} at ${BAUD_RATE} baud`);
                this._port = sp;
                resolve();
            });
        });

        if (!this._port) return;

        // Initialization sequence — 100 ms gap between commands
        const write = (cmd) => new Promise(r => this._port.write(cmd, r));
        await write('#ob\r');    // enable binary output
        await new Promise(r => setTimeout(r, 100));
        await write('#o1\r');    // enable continuous streaming
        await new Promise(r => setTimeout(r, 100));
        await write('#oe0\r');   // disable error messages
        await new Promise(r => setTimeout(r, 100));

        this._port.on('data', chunk => this._onData(chunk));
        this._port.on('error', err => console.warn(`[ahrs] ${err.message}`));
    }

    _onData(chunk) {
        this._buf = Buffer.concat([this._buf, chunk]);
        while (this._buf.length >= FRAME_SIZE) {
            const yaw   = this._buf.readFloatLE(0);
            const pitch = this._buf.readFloatLE(4);
            const roll  = this._buf.readFloatLE(8);
            if (isFinite(yaw) && isFinite(pitch) && isFinite(roll)) {
                this._global.yaw   = yaw;
                this._global.pitch = pitch;
                this._global.roll  = roll;
                this._emitter.emit('data');
            }
            this._buf = this._buf.subarray(FRAME_SIZE);
        }
    }

    async stop() {
        if (this._port) {
            await new Promise(resolve => this._port.close(resolve));
            this._port = null;
        }
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

module.exports = AhrsPlugin;
