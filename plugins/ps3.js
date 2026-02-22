'use strict';

const EventEmitter = require('events');
const ps3Hid = require('../ps3');
const Plugin = require('../plugin');

class Ps3Plugin extends Plugin {
    constructor() {
        super();
        this._pending = null;
        this._global  = {};
        this._emitter = new EventEmitter();
    }

    get friendlyName() { return 'PS3 Controller'; }
    get globalName()   { return 'ps3'; }
    createGlobal()     { return this._global; }

    async start() {
        ps3Hid.on('data', data => {
            this._pending = data;
            this._emitter.emit('data');
        });
        await ps3Hid.setup();
    }

    doBeforeNextExecute() {
        if (this._pending !== null) {
            Object.assign(this._global, this._pending);
            this._pending = null;
        }
    }

    on(event, listener) { this._emitter.on(event, listener); }
}

module.exports = Ps3Plugin;
