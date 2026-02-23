'use strict';

// Plugin de entrada: iPhone/iPad IMU vía UDP (protocolo FreePIE iOS IMU)
//
// Protocolo ASCII CSV, puerto 10552:
//   Primera línea (cabecera): "Timestamp,Roll,Pitch,Yaw,UserAcceleration.X,...\r\n"
//   Líneas sucesivas (datos):  "1234567890,1.23,-0.45,0.78,...\r\n"
//
// NOTA: FreePIE iOS invierte Roll y Pitch respecto a Android:
//   columna "Roll"  → iphone.pitch
//   columna "Pitch" → iphone.roll
//   columna "Yaw"   → iphone.yaw
//
// Los campos opcionales (UserAcceleration, Gravity, RotationRate, Attitude)
// se ignoran en esta implementación — solo se parsea orientación.
//
// Uso en scripts:
//   iphone.yaw, iphone.pitch, iphone.roll   // orientación en grados

const dgram       = require('dgram');
const EventEmitter = require('events');
const Plugin       = require('../plugin');

const DEFAULT_PORT = 10552;

class IosPlugin extends Plugin {
    constructor() {
        super();
        this._global  = { yaw: 0, pitch: 0, roll: 0 };
        this._emitter = new EventEmitter();
        this._socket  = null;
        this._rollIdx  = -1;
        this._pitchIdx = -1;
        this._yawIdx   = -1;
        this._headerParsed = false;
    }

    get friendlyName() { return 'iPhone IMU (UDP)'; }
    get globalName()   { return 'iphone'; }
    createGlobal()     { return this._global; }

    async start() {
        this._socket = dgram.createSocket('udp4');
        this._socket.on('message', (msg) => this._onMessage(msg));
        this._socket.on('error',   (err) => console.warn(`[ios] UDP error: ${err.message}`));
        await new Promise(resolve => {
            this._socket.once('error', err => {
                console.warn(`[ios] Cannot bind port ${DEFAULT_PORT}: ${err.message}`);
                this._socket = null;
                resolve();   // no fatal
            });
            this._socket.bind(DEFAULT_PORT, '0.0.0.0', () => {
                console.info(`[ios] Listening on UDP port ${DEFAULT_PORT}`);
                resolve();
            });
        });
    }

    _onMessage(msg) {
        const line = msg.toString('utf8').replace(/\r?\n$/, '');
        if (!line) return;

        if (!this._headerParsed) {
            // Primera línea: cabecera CSV
            const cols = line.split(',');
            this._rollIdx  = cols.indexOf('Roll');
            this._pitchIdx = cols.indexOf('Pitch');
            this._yawIdx   = cols.indexOf('Yaw');
            if (this._rollIdx < 0 || this._pitchIdx < 0 || this._yawIdx < 0) {
                console.warn(`[ios] Unexpected header: ${line}`);
            } else {
                this._headerParsed = true;
                console.info(`[ios] Header parsed: Roll@${this._rollIdx} Pitch@${this._pitchIdx} Yaw@${this._yawIdx}`);
            }
            return;
        }

        const cols = line.split(',');
        // FreePIE iOS: columna "Roll" → pitch, columna "Pitch" → roll (inversión intencional)
        const roll  = parseFloat(cols[this._rollIdx]);
        const pitch = parseFloat(cols[this._pitchIdx]);
        const yaw   = parseFloat(cols[this._yawIdx]);

        if (!isNaN(roll))  this._global.pitch = roll;
        if (!isNaN(pitch)) this._global.roll  = pitch;
        if (!isNaN(yaw))   this._global.yaw   = yaw;

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

module.exports = IosPlugin;
