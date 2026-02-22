'use strict';

class Plugin {
    get friendlyName() { return this.constructor.name; }
    get globalName()   { return null; }
    createGlobal()     { return {}; }
    async start()      {}
    async stop()       {}
    doBeforeNextExecute() {}
    doAfterExecute()      {}
}

module.exports = Plugin;
