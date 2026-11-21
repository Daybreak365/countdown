'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), vm = require('node:vm');
const {EventEmitter} = require('node:events');
const {isLoginUrl} = require('../app/backend/portablemc.cjs');
const {resolveLocations} = require('../app/backend/paths.cjs');

async function host() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'countdown-window-test-'));
  const windows = [], views = [], ipc = new Map();
  let engine, copied;
  const app = new EventEmitter();
  Object.assign(app, {isPackaged: false, setName() {}, getPath: () => root, setPath() {}, requestSingleInstanceLock: () => true, whenReady: () => Promise.resolve(), exit() {assert.fail('unexpected exit');}, quit() {}});
  class Contents extends EventEmitter {
    constructor() { super(); this.session = new EventEmitter(); Object.assign(this.session, {setPermissionRequestHandler() {}, setPermissionCheckHandler() {}, clearStorageData: async () => {}}); this.loaded = []; this.destroyed = false; }
    send(channel, value) {this.last = {channel, value};}
    setWindowOpenHandler(fn) {this.popup = fn;}
    loadURL(url) {this.loaded.push(url); return Promise.resolve();}
    isDestroyed() {return this.destroyed;}
    close() {this.destroyed = true;}
  }
  class Window extends EventEmitter {
    constructor(options) {super(); this.options = options; this.size = [1280, 820]; this.webContents = new Contents(); this.destroyed = false; this.children = new Set(); this.contentView = {addChildView: v => this.children.add(v), removeChildView: v => this.children.delete(v)}; windows.push(this);}
    isDestroyed() {return this.destroyed;}
    getContentSize() {return this.size;}
    setMenu() {} loadFile() {} show() {} focus() {} isMinimized() {return false;}
    close() {const event = {prevented: false, preventDefault() {this.prevented = true;}}; this.emit('close', event); if (!event.prevented) {this.destroyed = true; this.emit('closed'); app.emit('window-all-closed');}}
  }
  class View {
    constructor(options) {this.options = options; this.webContents = new Contents(); views.push(this);}
    setBounds(bounds) {this.bounds = bounds;}
  }
  class Engine extends EventEmitter {
    constructor() {super(); this.busy = false; engine = this;}
    state() {return {busy: this.busy};}
    cancel() {this.cancelled = true; queueMicrotask(() => {this.busy = false; this.emit('state', this.state());});}
  }
  const electron = {app, BrowserWindow: Window, WebContentsView: View, ipcMain: {handle: (key, fn) => ipc.set(key, fn)}, nativeImage: {createFromPath: () => null}, safeStorage: {}, session: {fromPartition: () => ({clearStorageData: async () => {}})}, clipboard: {writeText: s => {copied = s;}}};
  const context = vm.createContext({URL, process, console, __dirname: path.join(__dirname, '../app'), require: name => {
    if (name === 'electron') return electron;
    if (name === './backend/engine.cjs') return {Engine};
    if (name === './backend/auth.cjs') return {isLoginUrl};
    if (name === './backend/paths.cjs') return {resolveLocations};
    if (name === './backend/skin.cjs') return require('../app/backend/skin.cjs');
    return require(name);
  }});
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../app/main.cjs'), 'utf8'), context);
  await new Promise(resolve => setImmediate(resolve));
  return {context, windows, views, ipc, engine, copied: () => copied, cleanup: () => fs.rmSync(root, {recursive: true, force: true})};
}

test('Microsoft login and popups use one existing window with a protected clipboard action', async () => {
  const h = await host();
  try {
    const controller = new AbortController();
    const close = h.context.showLogin({url: 'https://www.microsoft.com/link', userCode: 'ABCD12345', signal: controller.signal, onError: assert.fail});
    const view = h.views[0], window = h.windows[0];
    assert.equal(h.windows.length, 1); assert.equal(window.children.size, 1);
    assert.equal(view.options.webPreferences.nodeIntegration, false); assert.equal(view.options.webPreferences.sandbox, true);
    assert.deepEqual({...view.bounds}, {x: 244, y: 154, width: 1012, height: 634});
    const popup = view.webContents.popup({url: 'https://login.live.com/login.srf'});
    assert.equal(popup.action, 'deny'); assert.equal(h.windows.length, 1); assert.equal(view.webContents.loaded.length, 2);
    assert.equal((await h.ipc.get('auth-copy')({sender: window.webContents})).ok, true); assert.equal(h.copied(), 'ABCD12345');
    assert.equal((await h.ipc.get('auth-copy')({sender: view.webContents})).ok, false);
    window.size = [1100, 740]; window.emit('resize'); assert.deepEqual({...view.bounds}, {x: 244, y: 154, width: 832, height: 554});
    close(); assert.equal(window.children.size, 0); assert(view.webContents.destroyed);
  } finally {h.cleanup();}
});

test('the window opens at 16:9 sized to the monitor', async () => {
  const h = await host();
  try {
    const ratio = s => Math.abs(s.width / s.height - 16 / 9) < .01;
    for (const area of [{width: 1920, height: 1040}, {width: 2560, height: 1400}, {width: 3440, height: 1400}, {width: 1366, height: 728}, {width: 1024, height: 728}, {width: 1080, height: 1880}]) {
      const size = h.context.initialSize(area);
      assert(ratio(size), JSON.stringify([area, size])); assert(size.width <= area.width && size.height <= area.height);
    }
    assert.deepEqual({...h.context.initialSize({width: 1920, height: 1040})}, {width: 960, height: 540});
    assert.deepEqual({...h.context.initialSize({width: 1366, height: 728})}, {width: 960, height: 540});
    assert.deepEqual({...h.context.initialSize({width: 1024, height: 728})}, {width: 960, height: 540});
    const options = h.windows[0].options; assert(ratio(options)); assert(options.minWidth <= options.width && options.minHeight <= options.height);
  } finally {h.cleanup();}
});

test('cancel and untrusted navigation remove the login view without another window', async () => {
  const h = await host();
  try {
    const controller = new AbortController();
    h.context.showLogin({url: 'https://www.microsoft.com/link', userCode: 'ABCD12345', signal: controller.signal, onError: assert.fail});
    controller.abort(); assert.equal(h.windows[0].children.size, 0);
    let error, blocked = false;
    h.context.showLogin({url: 'https://www.microsoft.com/link', userCode: 'ABCD12345', signal: new AbortController().signal, onError: e => {error = e;}});
    h.views[1].webContents.emit('will-redirect', {preventDefault() {blocked = true;}}, 'https://attacker.example');
    assert(blocked); assert(error); assert.equal(h.windows[0].children.size, 0); assert.equal(h.windows.length, 1);
  } finally {h.cleanup();}
});

test('closing during authentication waits for cancellation before quitting the window', async () => {
  const h = await host();
  try {
    h.engine.busy = true;
    h.context.showLogin({url: 'https://www.microsoft.com/link', userCode: 'ABCD12345', signal: new AbortController().signal, onError: assert.fail});
    h.windows[0].close(); assert(h.engine.cancelled); assert(!h.windows[0].destroyed); assert.equal(h.windows[0].children.size, 0);
    await new Promise(resolve => setImmediate(resolve)); assert(h.windows[0].destroyed);
  } finally {h.cleanup();}
});
