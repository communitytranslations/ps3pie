'use strict';

const Plugin = require('../plugin');

// Internal helper — not exposed to scripts
function _ensureMapRange(x, x0, x1, y0, y1) {
    return Math.max(Math.min(((x - x0) / (x1 - x0)) * (y1 - y0) + y0, y1), y0);
}

class FiltersPlugin extends Plugin {
    constructor() {
        super();
        this._simpleLast  = {};   // key → last EMA value
        this._deltaLast   = {};   // key → last sample
        this._stopwatches = {};   // key → startMs (Date.now())
        this._rotations   = {};   // key → { prev, out }
    }

    get friendlyName() { return 'Filters'; }
    get globalName()   { return 'filters'; }

    createGlobal() {
        const s = this;
        return {
            /**
             * Exponential Moving Average.
             * smoothing ∈ [0, 1]: 0 = no smoothing, 1 = frozen.
             */
            simple(x, smoothing, key) {
                if (smoothing < 0 || smoothing > 1)
                    throw new RangeError('filters.simple: smoothing must be between 0 and 1');
                const last = key in s._simpleLast ? s._simpleLast[key] : x;
                const out  = last * smoothing + x * (1 - smoothing);
                s._simpleLast[key] = out;
                return out;
            },

            /**
             * Returns x minus the previous sample (delta per frame).
             * Returns 0 on the first call.
             */
            delta(x, key) {
                const last = key in s._deltaLast ? s._deltaLast[key] : x;
                s._deltaLast[key] = x;
                return x - last;
            },

            /**
             * Simple dead zone: returns 0 if |x| < |zone|, else returns x unchanged.
             * Optional 4-arg form: deadband(x, zone, minY, maxY) — scaled deadband
             * that operates in [-1,1] space and remaps back to [minY, maxY].
             */
            deadband(x, zone, minY, maxY) {
                if (arguments.length >= 4) {
                    const scaled = _ensureMapRange(x, minY, maxY, -1, 1);
                    let y = 0;
                    if (Math.abs(scaled) > zone)
                        y = _ensureMapRange(Math.abs(scaled), zone, 1, 0, 1) * Math.sign(x);
                    return _ensureMapRange(y, -1, 1, minY, maxY);
                }
                return Math.abs(x) >= Math.abs(zone) ? x : 0;
            },

            /**
             * Linear range remap without clamping.
             * Maps x ∈ [x0, x1] → [y0, y1].
             */
            mapRange(x, x0, x1, y0, y1) {
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
            },

            /**
             * Linear range remap with clamping to [y0, y1].
             */
            ensureMapRange(x, x0, x1, y0, y1) {
                return _ensureMapRange(x, x0, x1, y0, y1);
            },

            /**
             * Boolean timer. Returns true once when ms milliseconds have elapsed
             * since active first became true. Resets if active goes false.
             */
            stopWatch(active, ms, key) {
                if (active && !(key in s._stopwatches))
                    s._stopwatches[key] = Date.now();

                if (!active && key in s._stopwatches)
                    delete s._stopwatches[key];

                if (!active) return false;

                if (Date.now() - s._stopwatches[key] >= ms) {
                    delete s._stopwatches[key];
                    return true;
                }
                return false;
            },

            /**
             * Unwraps a rotating sensor value into a continuous (unbounded) output.
             * Prevents jumps when the sensor wraps at ±180° or ±π.
             *
             * continuousRotation(x, key)            — radians (default)
             * continuousRotation(x, unit, key)      — unit: 'radians' | 'degrees'
             */
            continuousRotation(x, unitOrKey, key) {
                let unit;
                if (key === undefined) {
                    key  = unitOrKey;
                    unit = 'radians';
                } else {
                    unit = unitOrKey;
                }

                const halfCircle = unit === 'degrees' ? 180 : Math.PI;

                if (!(key in s._rotations))
                    s._rotations[key] = { prev: x, out: 0 };

                const state = s._rotations[key];
                let delta = x - state.prev;

                if (Math.abs(delta) > halfCircle)
                    delta += delta > 0 ? -(2 * halfCircle) : (2 * halfCircle);

                state.out += delta;
                state.prev = x;
                return state.out;
            },
        };
    }
}

module.exports = FiltersPlugin;
