'use strict';

const uinput = require('../uinput');
const Plugin = require('../plugin');

class VjoyPlugin extends Plugin {
    constructor() {
        super();
        this._keyboard       = {};
        this._keyboardPrev   = {};
        this._keyboardEvents = [];
        this._vjoyA          = {};
        this._vjoyAPrev      = {};
        this._vjoyB          = {};
        this._vjoyBPrev      = {};
    }

    get friendlyName() { return 'Virtual Joystick + Keyboard'; }

    createGlobals() {
        return {
            keyboard:       this._keyboard,
            keyboardEvents: this._keyboardEvents,
            vjoyA:          this._vjoyA,
            vjoyB:          this._vjoyB,
        };
    }

    async start() { await uinput.setup(); }
    stop()        { uinput.teardown(); }

    doAfterExecute() {
        // keyboardEvents: one-shot
        for (const code of this._keyboardEvents) {
            if (typeof code === 'string') uinput.keyPress(code);
            else                          uinput.keyCombo(code);
        }
        this._keyboardEvents.splice(0);

        // keyboard: sustained diff
        for (const code in this._keyboard) {
            const next = this._keyboard[code];
            if (next !== this._keyboardPrev[code]) {
                uinput.key(code, next);
                this._keyboardPrev[code] = next;
            }
        }

        // vjoyA: axes — scale from [-1,1] to [0,1000] (center=500)
        for (const code in this._vjoyA) {
            const next = this._vjoyA[code];
            if (next !== this._vjoyAPrev[code]) {
                uinput.axis(code, next * 500 + 500);
                this._vjoyAPrev[code] = next;
            }
        }

        // vjoyB: buttons
        for (const code in this._vjoyB) {
            const next = this._vjoyB[code];
            if (next !== this._vjoyBPrev[code]) {
                uinput.button(code, next);
                this._vjoyBPrev[code] = next;
            }
        }

        uinput.sync();
    }
}

module.exports = VjoyPlugin;
