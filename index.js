#!/usr/bin/env node
'use strict';

const fs   = require('fs');
const path = require('path');
const vm   = require('vm');

const Ps3Plugin     = require('./plugins/ps3');
const VjoyPlugin    = require('./plugins/vjoy');
const FiltersPlugin = require('./plugins/filters');
const EvdevPlugin   = require('./plugins/evdev');

const PLUGINS = [
    new Ps3Plugin(),
    new VjoyPlugin(),
    new FiltersPlugin(),
    new EvdevPlugin(),
];

// Construir sandbox a partir de los globales de cada plugin
const sandbox = {
    console,
    exports:  {},
    module:   { exports: {} },
};
sandbox.exports = sandbox.module.exports;

for (const plugin of PLUGINS) {
    if (typeof plugin.createGlobals === 'function') {
        Object.assign(sandbox, plugin.createGlobals());  // multi-global
    } else if (plugin.globalName) {
        sandbox[plugin.globalName] = plugin.createGlobal();
    }
}

// Guard contra ejecuciones concurrentes
let running = false;

function onData() {
    if (running) return;
    running = true;
    try {
        for (const plugin of PLUGINS) plugin.doBeforeNextExecute();
        sandbox.module.exports.loop();
        for (const plugin of PLUGINS) plugin.doAfterExecute();
    } catch (err) {
        console.log(err);
    } finally {
        running = false;
    }
}

async function main() {
    for (const plugin of PLUGINS) {
        await plugin.start();
    }

    const scriptPath = process.argv[2]
        ? path.resolve(process.argv[2])
        : path.join(__dirname, 'scripts/descent.js');
    const scriptCode = fs.readFileSync(scriptPath);
    new vm.Script(scriptCode).runInNewContext(sandbox);

    console.info('Ready');

    for (const plugin of PLUGINS) {
        if (typeof plugin.on === 'function') plugin.on('data', onData);
    }
}

process.on('SIGINT', async () => {
    for (const plugin of PLUGINS) await plugin.stop();
    process.exit();
});

(async () => {
    try {
        await main();
    } catch (err) {
        console.log(err);
    }
})();
