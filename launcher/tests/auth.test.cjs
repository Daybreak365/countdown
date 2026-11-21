'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const {AccountAuth} = require('../app/backend/auth.cjs');
const {PortableMC, isLoginUrl, bundledBinary} = require('../app/backend/portablemc.cjs');
const {Engine} = require('../app/backend/engine.cjs');
const fixture = path.join(__dirname, 'fixtures/auth-cli.cjs');
const storage = {isEncryptionAvailable: () => true, encryptString: s => Buffer.from('sealed:' + Buffer.from(s).toString('base64')), decryptString: b => Buffer.from(b.toString().slice(7), 'base64').toString()};
const make = (scenario = 'success', extra = {}) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'countdown-auth-test-'));
  let opened = 0, closed = 0, observed, launchOptions;
  const backend = new PortableMC({binary: {path: fixture, sha256: crypto.createHash('sha256').update(fs.readFileSync(fixture)).digest('hex')}, spawnImpl: (_exe, args, options) => {
    launchOptions = {windowsHide: options.windowsHide, shell: options.shell, stdio: options.stdio};
    return spawn(process.execPath, [fixture, ...args, '--scenario', scenario], options);
  }});
  const auth = new AccountAuth({root, backend, secureStorage: storage, showLogin: params => {
    opened++;
    assert.equal(params.url, 'https://www.microsoft.com/link'); assert.equal(params.userCode, 'ABCD12345');
    observed = JSON.parse(fs.readFileSync(path.join(auth.workRoot, 'observed.json')));
    extra.onOpen?.(params, auth);
    return () => { closed++; };
  }, ...extra.auth});
  return {root, auth, counts: () => ({opened, closed}), observed: () => observed, launchOptions: () => launchOptions, cleanup: () => {fs.rmSync(root, {recursive: true, force: true}); fs.rmSync(auth.workRoot, {recursive: true, force: true});}};
};

test('CLI subprocess protocol completes login without an app override and seals its cache', async () => {
  const f = make(); const old = process.env.PMC_MSA_AZURE_APP_ID, oldLower = process.env.pmc_output;
  process.env.PMC_MSA_AZURE_APP_ID = 'untrusted-override'; process.env.pmc_output = 'human';
  try {
    const result = await f.auth.login(new AbortController().signal);
    assert.equal(result.accessToken, 'TEST_MINECRAFT_SECRET');
    assert.deepEqual(f.auth.account(), {id: '11111111222233334444555555555555', name: 'TestPlayer'});
    assert.deepEqual(f.counts(), {opened: 1, closed: 1});
    assert(f.observed().args.includes('--no-browser')); assert(f.observed().args.includes('machine'));
    assert(!f.observed().args.includes('--msa-azure-app-id')); assert.equal(f.observed().injectedOverride, null);
    assert.equal(f.launchOptions().windowsHide, true); assert.equal(f.launchOptions().shell, false);
    assert(!fs.readFileSync(f.auth.file, 'utf8').includes('TEST_MINECRAFT_SECRET'));
    assert(!fs.existsSync(f.auth.workRoot));
    const engine = new Engine({appRoot: path.join(__dirname, '../app'), dataRoot: f.root, distributionRoot: f.root, auth: f.auth});
    assert(!JSON.stringify(engine.state()).includes('SECRET'));
  } finally {
    if (old === undefined) delete process.env.PMC_MSA_AZURE_APP_ID; else process.env.PMC_MSA_AZURE_APP_ID = old;
    if (oldLower === undefined) delete process.env.pmc_output; else process.env.pmc_output = oldLower;
    f.cleanup();
  }
});

test('an encrypted session reloads and refreshes without another login view', async () => {
  const f = make();
  try {
    await f.auth.login(new AbortController().signal);
    const reload = new AccountAuth({root: f.root, backend: f.auth.backend, secureStorage: storage, showLogin: () => assert.fail('must refresh silently')});
    await reload.login(new AbortController().signal);
    assert.equal(reload.account().name, 'TestPlayer'); assert(!fs.existsSync(reload.workRoot));
    reload.logout(); assert(!fs.existsSync(reload.file)); assert.equal(reload.account(), null);
  } finally { f.cleanup(); }
});

test('revoked sessions return to the same login route; network failures do not erase them', async () => {
  const f = make('refresh-expired');
  try {
    await f.auth.login(new AbortController().signal);
    await f.auth.login(new AbortController().signal);
    assert.deepEqual(f.counts(), {opened: 2, closed: 2});
    f.auth.backend.spawn = (_exe, args, options) => spawn(process.execPath, [fixture, ...args, '--scenario', 'network'], options);
    await assert.rejects(f.auth.login(new AbortController().signal), error => !error.message.includes('TEST_SECRET'));
    assert.equal(f.auth.account().name, 'TestPlayer'); assert(!fs.existsSync(f.auth.workRoot));
  } finally { f.cleanup(); }
});

test('cancel stops the child, closes the login view, removes plaintext, and permits retry', async () => {
  const controller = new AbortController();
  const f = make('cancel', {onOpen: () => setTimeout(() => controller.abort(), 30)});
  try {
    await assert.rejects(f.auth.login(controller.signal), /취소/);
    assert.deepEqual(f.counts(), {opened: 1, closed: 1});
    assert(!fs.existsSync(f.auth.workRoot)); assert(!fs.existsSync(f.auth.file)); assert.equal(f.auth.active, false);
    f.auth.backend.spawn = (_exe, args, options) => spawn(process.execPath, [fixture, ...args, '--scenario', 'success'], options);
    await f.auth.login(new AbortController().signal);
    assert.equal(f.auth.account().name, 'TestPlayer'); assert(!fs.existsSync(f.auth.workRoot));
  } finally { f.cleanup(); }
});

test('missing game ownership leaves the account signed out', async () => {
  const f = make('unowned');
  try {
    await assert.rejects(f.auth.login(new AbortController().signal), /Java Edition/);
    assert.equal(f.auth.account(), null); assert(!fs.existsSync(f.auth.file)); assert(!fs.existsSync(f.auth.workRoot));
  } finally { f.cleanup(); }
});

test('device code redirects cannot open an unrelated host', async () => {
  const f = make('bad-url');
  try {
    await assert.rejects(f.auth.login(new AbortController().signal), /요청이 올바르지/);
    assert.equal(f.counts().opened, 0); assert(!fs.existsSync(f.auth.workRoot));
    assert(isLoginUrl('https://login.live.com/login.srf'));
    for (const url of ['https://login.live.com.attacker.example', 'http://login.live.com', 'file:///C:/Windows', 'https://user:pass@login.live.com']) assert(!isLoginUrl(url));
  } finally { f.cleanup(); }
});

test('tampered helpers and unavailable OS encryption stop before login', async () => {
  const f = make();
  try {
    f.auth.backend.binary.sha256 = '0'.repeat(64);
    await assert.rejects(f.auth.login(new AbortController().signal), /해시/);
    assert.equal(f.counts().opened, 0); assert(!fs.existsSync(f.auth.workRoot));
    f.auth.storage = {isEncryptionAvailable: () => false};
    await assert.rejects(f.auth.login(new AbortController().signal), /암호화/);
  } finally { f.cleanup(); }
});

test('a failed Windows-user decryption starts signed out and removes crash leftovers', async () => {
  const f = make();
  try {
    await f.auth.login(new AbortController().signal);
    fs.mkdirSync(f.auth.workRoot); fs.writeFileSync(path.join(f.auth.workRoot, 'session.json'), 'TEST_LEFTOVER_TOKEN');
    const reload = new AccountAuth({root: f.root, backend: f.auth.backend, secureStorage: {...storage, decryptString: () => {throw new Error('different user');}}});
    assert.equal(reload.account(), null); assert(!fs.existsSync(reload.workRoot));
  } finally { f.cleanup(); }
});

test('a corrupt CLI cache cannot expose token text through an error', async () => {
  const f = make('corrupt');
  try {
    await assert.rejects(f.auth.login(new AbortController().signal), error => !error.message.includes('TEST_SECRET') && /로그인 정보를/.test(error.message));
    assert.equal(f.auth.account(), null); assert(!fs.existsSync(f.auth.workRoot));
  } finally { f.cleanup(); }
});

test('the bundled Windows CLI and its local runtime are x64 and match pinned hashes', () => {
  const appRoot = path.join(__dirname, '../app');
  const binary = bundledBinary(appRoot);
  assert.equal(new PortableMC({appRoot}).verify(), binary.path);
  for (const file of [binary.path, binary.runtime.path]) {
    const b = fs.readFileSync(file); assert.equal(b.subarray(0, 2).toString(), 'MZ');
    assert.equal(b.readUInt16LE(b.readUInt32LE(60) + 4), 0x8664);
  }
});
