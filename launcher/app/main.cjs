'use strict';
const {app, BrowserWindow, WebContentsView, ipcMain, nativeImage, safeStorage, session, clipboard, screen} = require('electron');
const path = require('node:path'), fs = require('node:fs');
const {Engine} = require('./backend/engine.cjs');
const {resolveLocations} = require('./backend/paths.cjs');
const {isLoginUrl} = require('./backend/auth.cjs');
const {fetchFace} = require('./backend/skin.cjs');

app.setName('2027 Countdown');
const locations = resolveLocations({executable: process.execPath, portableDir: process.env.PORTABLE_EXECUTABLE_DIR, packaged: app.isPackaged, appRoot: __dirname, defaultDataRoot: app.getPath('userData')});
if (locations.portable) {
  const browserData = path.join(locations.dataRoot, 'browser-data');
  fs.mkdirSync(browserData, {recursive: true});
  app.setPath('userData', locations.dataRoot);
  app.setPath('sessionData', browserData);
}
if (!app.requestSingleInstanceLock()) app.exit(0);
let win, engine, loginView, loginCleanup, loginUserCode, closeWhenIdle = false;
app.on('second-instance', () => {
  if (win && !win.isDestroyed()) { if (win.isMinimized()) win.restore(); win.show(); win.focus(); }
});
function send(channel, value) { if (win && !win.isDestroyed()) win.webContents.send(channel, value); }
function loginBounds() {
  if (!loginView || !win || win.isDestroyed()) return;
  const [w, h] = win.getContentSize();
  loginView.setBounds({x: 244, y: 154, width: Math.max(1, w - 268), height: Math.max(1, h - 186)});
}

// A native web view is a child of the existing launcher window. PortableMC
// polls independently, so no OAuth redirect or external browser is necessary.
function showLogin({url, userCode, signal, onError}) {
  if (!isLoginUrl(url) || signal.aborted || !win || win.isDestroyed()) throw new Error('로그인 요청이 올바르지 않습니다.');
  if (loginView) throw new Error('이미 로그인 중입니다.');
  const view = new WebContentsView({webPreferences: {
    sandbox: true, contextIsolation: true, nodeIntegration: false,
    webSecurity: true, partition: 'countdown-login'
  }});
  loginView = view; loginUserCode = userCode;
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    signal.removeEventListener('abort', close);
    view.webContents.session.removeListener('will-download', denyDownload);
    if (win && !win.isDestroyed()) { win.off('resize', loginBounds); win.contentView.removeChildView(view); }
    if (!view.webContents.isDestroyed()) view.webContents.close();
    if (loginView === view) { loginView = null; loginCleanup = null; loginUserCode = null; send('auth', {open: false}); }
  };
  const fail = message => { if (!closed) { close(); onError(new Error(message)); } };
  const navigate = (event, details) => {
    const target = typeof details === 'string' ? details : details?.url || event.url;
    if (!isLoginUrl(target)) { event.preventDefault(); fail('Microsoft 로그인이 허용된 인증 주소를 벗어났습니다.'); return; }
    send('auth', {open: true, origin: new URL(target).hostname});
  };
  const denyDownload = (event, _item, contents) => { if (contents === view.webContents) event.preventDefault(); };
  const load = target => view.webContents.loadURL(target).catch(error => {
    if (error?.code !== 'ERR_ABORTED') fail('Microsoft 로그인 화면을 불러오지 못했습니다.');
  });
  loginCleanup = close;
  view.webContents.on('will-navigate', navigate);
  view.webContents.on('will-redirect', navigate);
  view.webContents.setWindowOpenHandler(({url: target}) => { if (isLoginUrl(target)) load(target); return {action: 'deny'}; });
  view.webContents.session.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  view.webContents.session.setPermissionCheckHandler(() => false);
  view.webContents.session.on('will-download', denyDownload);
  view.webContents.on('did-fail-load', (_event, code, _description, _url, isMainFrame) => {
    if (isMainFrame && code !== -3) fail('Microsoft 로그인 화면을 불러오지 못했습니다.');
  });
  signal.addEventListener('abort', close, {once: true});
  win.contentView.addChildView(view); win.on('resize', loginBounds); loginBounds();
  send('auth', {open: true, origin: new URL(url).hostname, userCode});
  load(url);
  return close;
}

function reply(fn) {
  return async (event, ...args) => {
    try {
      if (event.sender !== win?.webContents) throw new Error('허용되지 않은 요청입니다.');
      return {ok: true, value: await fn(event, ...args)};
    } catch (e) { return {ok: false, error: e.message}; }
  };
}

// The window opens at 16:9, sized to about half of the monitor's work area.
const MIN_SIZE = {width: 960, height: 540};
function initialSize(area) {
  const fit = (w, h) => Math.min(w, h * 16 / 9);
  const width = Math.round(Math.min(fit(area.width, area.height), Math.max(MIN_SIZE.width, fit(area.width * .5, area.height * .5))));
  return {width, height: Math.round(width * 9 / 16)};
}

app.whenReady().then(() => {
  engine = new Engine({dataRoot: locations.dataRoot, distributionRoot: locations.distributionRoot, appRoot: __dirname, secureStorage: safeStorage, showLogin});
  const size = initialSize(screen?.getPrimaryDisplay?.().workAreaSize || {width: 1707, height: 960});
  win = new BrowserWindow({...size, minWidth: Math.min(MIN_SIZE.width, size.width), minHeight: Math.min(MIN_SIZE.height, size.height), center: true,
    title: '2027 카운트다운', frame: false, backgroundColor: '#10162a', show: false,
    autoHideMenuBar: true, icon: nativeImage.createFromPath(path.join(__dirname, 'assets', 'launcher-icon.png')),
    webPreferences: {preload: path.join(__dirname, 'preload.cjs'), sandbox: true, contextIsolation: true, nodeIntegration: false, webSecurity: true}
  });
  win.setMenu(null);
  win.webContents.setWindowOpenHandler(() => ({action: 'deny'}));
  win.webContents.on('will-navigate', event => event.preventDefault());
  win.webContents.session.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
  win.webContents.session.setPermissionCheckHandler(() => false);
  engine.on('progress', data => send('progress', data));
  engine.on('state', data => {
    send('state', data);
    if (closeWhenIdle && !data.busy && !win.isDestroyed()) win.close();
  });
  win.once('ready-to-show', () => win.show());
  win.on('close', event => {
    loginCleanup?.();
    if (engine.state().busy) { event.preventDefault(); closeWhenIdle = true; engine.cancel(); }
  });
  ipcMain.handle('state', reply(() => engine.state()));
  ipcMain.handle('settings', reply((_event, settings) => engine.saveSettings(settings)));
  ipcMain.handle('run', reply((_event, action) => engine.run(action)));
  ipcMain.handle('logout', reply(async () => {
    const state = engine.logout();
    await session.fromPartition('countdown-login').clearStorageData();
    return state;
  }));
  // Skin faces come from Mojang's official servers only; one lookup per account per session.
  const faces = new Map();
  ipcMain.handle('avatar', reply(() => {
    const id = engine.state().account?.id;
    if (!id) throw new Error('로그인이 필요합니다.');
    if (!faces.has(id)) faces.set(id, fetchFace(id, {decode: bytes => nativeImage.createFromBuffer(bytes)}).catch(error => { faces.delete(id); throw error; }));
    return faces.get(id);
  }));
  ipcMain.handle('auth-copy', reply(() => { if (loginView && loginUserCode) clipboard.writeText(loginUserCode); return true; }));
  ipcMain.handle('cancel', reply(() => { engine.cancel(); return true; }));
  ipcMain.handle('window', reply((_event, action) => {
    if (action === 'minimize') win.minimize();
    else if (action === 'maximize') win.isMaximized() ? win.unmaximize() : win.maximize();
    else if (action === 'close') win.close();
    return true;
  }));
  win.loadFile(path.join(__dirname, 'index.html'));
});
app.on('before-quit', event => {
  if (engine?.state().busy && win && !win.isDestroyed()) { event.preventDefault(); closeWhenIdle = true; win.close(); }
});
app.on('window-all-closed', () => app.quit());
