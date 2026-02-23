'use strict';

// Plugin de entrada: MIDI vía ALSA (node-midi)
//
// Expone puertos MIDI como proxy indexado midi[N]:
//   midi[0].status   — tipo de mensaje (nibble alto del status byte)
//                        0x8=NoteOff, 0x9=NoteOn, 0xA=PolyAftertouch,
//                        0xB=Control, 0xC=ProgramChange,
//                        0xD=ChannelAftertouch, 0xE=PitchBend
//   midi[0].channel  — canal MIDI 0-15
//   midi[0].data     — [byte, byte]  datos del mensaje
//
// Atajos semánticos (mismos valores, nombres más legibles):
//   midi[0].note     — nota  (NoteOn/NoteOff: data[0])
//   midi[0].velocity — velocidad (NoteOn/NoteOff: data[1])
//   midi[0].cc       — número de controlador (Control Change: data[0])
//   midi[0].value    — valor del controlador (Control Change: data[1])
//
// Uso en scripts:
//   if (midi[0].status === 0x9 && midi[0].note === 60) { ... }  // Middle C
//   vjoyA.x = filters.mapRange(midi[0].value, 0, 127, -1, 1);   // CC → eje

const midi        = require('midi');
const EventEmitter = require('events');
const Plugin       = require('../plugin');

// Constantes de tipo de mensaje (nibble alto del status byte)
const STATUS_NOTE_OFF            = 0x8;
const STATUS_NOTE_ON             = 0x9;
const STATUS_CONTROL             = 0xB;
const STATUS_PROGRAM_CHANGE      = 0xC;
const STATUS_PITCH_BEND          = 0xE;

class MidiDevice {
    constructor(n) {
        this._n      = n;
        this._input  = new midi.Input();
        this._queue  = [];
        this._emitter = new EventEmitter();

        // Estado expuesto al script
        this.status   = 0;
        this.channel  = 0;
        this.data     = [0, 0];
    }

    // Atajos — acceden a data[], siempre actualizados
    get note()     { return this.data[0]; }
    get velocity() { return this.data[1]; }
    get cc()       { return this.data[0]; }
    get value()    { return this.data[1]; }

    open() {
        const count = this._input.getPortCount();
        if (this._n >= count) {
            console.warn(`[midi] Port ${this._n} not available (${count} MIDI port(s) found)`);
            return;
        }
        const name = this._input.getPortName(this._n);
        // ignoreTypes: sysex=true, timing=true, activeSensing=true (reduce ruido)
        this._input.ignoreTypes(true, true, true);
        this._input.on('message', (_delta, msg) => {
            this._queue.push(msg);
            this._emitter.emit('data');
        });
        this._input.openPort(this._n);
        console.info(`[midi] Opened port ${this._n}: ${name}`);
    }

    // Llamado desde MidiPlugin.doBeforeNextExecute()
    flush() {
        if (this._queue.length === 0) return;
        // Drena toda la cola; el script ve el último mensaje del frame
        while (this._queue.length > 0) {
            const msg = this._queue.shift();
            const statusByte = msg[0] ?? 0;
            this.status   = (statusByte >> 4) & 0xF;
            this.channel  = statusByte & 0xF;
            this.data[0]  = msg[1] ?? 0;
            this.data[1]  = msg[2] ?? 0;
        }
    }

    close() {
        try { this._input.closePort(); } catch (_) {}
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

class MidiPlugin extends Plugin {
    constructor() {
        super();
        this._devices = {};
        this._emitter = new EventEmitter();
        this._proxy   = new Proxy({}, {
            get: (_, n) => {
                if (typeof n === 'symbol' || !/^\d+$/.test(String(n))) return undefined;
                const i = Number(n);
                if (!(i in this._devices)) {
                    const dev = new MidiDevice(i);
                    dev.on('data', () => this._emitter.emit('data'));
                    dev.open();
                    this._devices[i] = dev;
                }
                return this._devices[i];
            }
        });
    }

    get friendlyName() { return 'MIDI Input (ALSA)'; }
    get globalName()   { return 'midi'; }
    createGlobal()     { return this._proxy; }

    async start() {}

    doBeforeNextExecute() {
        for (const dev of Object.values(this._devices)) dev.flush();
    }

    async stop() {
        for (const dev of Object.values(this._devices)) dev.close();
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

// Exportar también las constantes de status para uso en scripts
MidiPlugin.STATUS_NOTE_OFF       = STATUS_NOTE_OFF;
MidiPlugin.STATUS_NOTE_ON        = STATUS_NOTE_ON;
MidiPlugin.STATUS_CONTROL        = STATUS_CONTROL;
MidiPlugin.STATUS_PROGRAM_CHANGE = STATUS_PROGRAM_CHANGE;
MidiPlugin.STATUS_PITCH_BEND     = STATUS_PITCH_BEND;

module.exports = MidiPlugin;
