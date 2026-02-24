'use strict';

// Input plugin: OpenTrack / FreeTrack head-tracking via UDP
//
// Binary Little-Endian protocol, port 4242 (OpenTrack "UDP over network" output):
//   bytes  0- 7 : x     (float64) — position, cm
//   bytes  8-15 : y     (float64) — position, cm
//   bytes 16-23 : z     (float64) — position, cm
//   bytes 24-31 : yaw   (float64) — rotation, degrees  (-180..+180)
//   bytes 32-39 : pitch (float64) — rotation, degrees  (-90..+90)
//   bytes 40-47 : roll  (float64) — rotation, degrees  (-180..+180)
//
// Compatible senders:
//   - OpenTrack  (Output → "UDP over network", host=<this machine>, port=4242)
//   - HeadMob    (Android head-tracker app, sends OpenTrack UDP format)
//   - Any sender implementing the standard 48-byte OpenTrack UDP packet
//
// Usage in scripts:
//   opentrack.yaw, opentrack.pitch, opentrack.roll   // degrees
//   opentrack.x,   opentrack.y,     opentrack.z      // cm
//
// Environment variables:
//   PS3PIE_OPENTRACK_PORT=4242   — UDP port (default: 4242)
//   PS3PIE_BIND_HOST=0.0.0.0    — accept from network (default: 127.0.0.1)

const dgram        = require('dgram');
const EventEmitter = require('events');
const Plugin       = require('../plugin');

const DEFAULT_PORT = parseInt(process.env.PS3PIE_OPENTRACK_PORT, 10) || 4242;
const PACKET_SIZE  = 48;   // 6 × float64

// Bind to localhost by default. Set PS3PIE_BIND_HOST=0.0.0.0 to accept from network.
const BIND_HOST = process.env.PS3PIE_BIND_HOST || '127.0.0.1';

class OpentrackPlugin extends Plugin {
    constructor() {
        super();
        this._global  = { x: 0, y: 0, z: 0, yaw: 0, pitch: 0, roll: 0 };
        this._emitter = new EventEmitter();
        this._socket  = null;
    }

    get friendlyName() { return 'OpenTrack (UDP)'; }
    get globalName()   { return 'opentrack'; }
    createGlobal()     { return this._global; }

    async start() {
        this._socket = dgram.createSocket('udp4');
        this._socket.on('message', (msg) => this._onMessage(msg));
        this._socket.on('error',   (err) => console.warn(`[opentrack] UDP error: ${err.message}`));
        await new Promise(resolve => {
            this._socket.once('error', err => {
                console.warn(`[opentrack] Cannot bind port ${DEFAULT_PORT}: ${err.message}`);
                this._socket = null;
                resolve();   // non-fatal
            });
            this._socket.bind(DEFAULT_PORT, BIND_HOST, () => {
                console.info(`[opentrack] Listening on UDP ${BIND_HOST}:${DEFAULT_PORT}`);
                resolve();
            });
        });
    }

    _onMessage(msg) {
        if (msg.length < PACKET_SIZE) return;

        const x     = msg.readDoubleLe(0);
        const y     = msg.readDoubleLe(8);
        const z     = msg.readDoubleLe(16);
        const yaw   = msg.readDoubleLe(24);
        const pitch = msg.readDoubleLe(32);
        const roll  = msg.readDoubleLe(40);

        if (isFinite(x))     this._global.x     = x;
        if (isFinite(y))     this._global.y     = y;
        if (isFinite(z))     this._global.z     = z;
        if (isFinite(yaw))   this._global.yaw   = yaw;
        if (isFinite(pitch)) this._global.pitch = pitch;
        if (isFinite(roll))  this._global.roll  = roll;

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

module.exports = OpentrackPlugin;
