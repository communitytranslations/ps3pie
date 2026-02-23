'use strict';

// Input plugin: FreeIMU via serial port (ASCII CSV protocol)
//
// Port: PS3PIE_FREEIMU_PORT environment variable (default: /dev/ttyUSB0)
// Baud rate: 115200
//
// Text streaming protocol, one line per sample:
//   "yaw,pitch,roll\r\n"
//   Values in radians, comma-separated, decimal point = dot.
//
// Usage in scripts:
//   const y = freeImu.yaw;    // −π..+π
//   const p = freeImu.pitch;
//   const r = freeImu.roll;

const { SerialPort }      = require('serialport');
const { ReadlineParser }  = require('@serialport/parser-readline');
const EventEmitter         = require('events');
const Plugin               = require('../plugin');

const DEFAULT_PORT = process.env.PS3PIE_FREEIMU_PORT || '/dev/ttyUSB0';
const BAUD_RATE    = 115200;

class FreeImuPlugin extends Plugin {
    constructor() {
        super();
        this._global  = { yaw: 0, pitch: 0, roll: 0 };
        this._emitter = new EventEmitter();
        this._port    = null;
    }

    get friendlyName() { return 'FreeIMU (serial)'; }
    get globalName()   { return 'freeImu'; }
    createGlobal()     { return this._global; }

    async start() {
        const sp = new SerialPort({ path: DEFAULT_PORT, baudRate: BAUD_RATE, autoOpen: false });

        await new Promise(resolve => {
            sp.open(err => {
                if (err) {
                    console.warn(`[freeimu] Cannot open ${DEFAULT_PORT}: ${err.message}`);
                    resolve();
                    return;
                }
                console.info(`[freeimu] Opened ${DEFAULT_PORT} at ${BAUD_RATE} baud`);
                this._port = sp;
                resolve();
            });
        });

        if (!this._port) return;

        const parser = this._port.pipe(new ReadlineParser({ delimiter: '\n' }));
        parser.on('data', line => this._onLine(line.trim()));
        this._port.on('error', err => console.warn(`[freeimu] ${err.message}`));
    }

    _onLine(line) {
        const parts = line.split(',');
        if (parts.length < 3) return;
        const yaw   = parseFloat(parts[0]);
        const pitch = parseFloat(parts[1]);
        const roll  = parseFloat(parts[2]);
        if (!isFinite(yaw) || !isFinite(pitch) || !isFinite(roll)) return;
        this._global.yaw   = yaw;
        this._global.pitch = pitch;
        this._global.roll  = roll;
        this._emitter.emit('data');
    }

    async stop() {
        if (this._port) {
            await new Promise(resolve => this._port.close(resolve));
            this._port = null;
        }
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

module.exports = FreeImuPlugin;
