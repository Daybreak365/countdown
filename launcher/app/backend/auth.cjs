'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const {PortableMC, isLoginUrl} = require('./portablemc.cjs');

function validatedAccount(database, selected) {
  const account = Array.isArray(database?.accounts) ? database.accounts.find(a => a.uuid === selected) : null;
  if (!account || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(account.uuid || '') ||
      !/^[a-z0-9_]{1,16}$/i.test(account.username || '') ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(account.app_id || '') ||
      !['access_token', 'refresh_token'].every(k => typeof account[k] === 'string' && account[k].length > 0 && account[k].length < 32768) ||
      typeof account.xuid !== 'string' || !/^\d{1,30}$/.test(account.xuid)) {
    throw new Error('저장된 Minecraft 계정 정보가 올바르지 않습니다. 다시 로그인하세요.');
  }
  return account;
}

class AccountAuth {
  constructor({root, appRoot, secureStorage, showLogin, backend}) {
    this.root = root;
    this.file = path.join(root, 'portablemc-credentials.bin');
    this.workRoot = path.join(os.tmpdir(), 'Countdown2027Auth-' + crypto.createHash('sha256').update(path.resolve(root)).digest('hex').slice(0, 20));
    this.storage = secureStorage;
    this.showLogin = showLogin;
    this.backend = backend || new PortableMC({appRoot});
    this.data = null;
    this.active = false;
    // A crashed previous authentication must not leave its plaintext CLI cache.
    fs.rmSync(this.workRoot, {recursive: true, force: true});
    fs.rmSync(this.file + '.tmp', {force: true});
    try {
      if (this.storage.isEncryptionAvailable() && fs.existsSync(this.file) && fs.statSync(this.file).size < 1024 * 1024) {
        const data = JSON.parse(this.storage.decryptString(fs.readFileSync(this.file)));
        if (data.format !== 1) throw new Error('Unsupported credential cache');
        validatedAccount(data.database, data.selected);
        this.data = data;
      }
    } catch { /* A different Windows user or invalid cache requires a new login. */ }
  }

  account() {
    if (!this.data) return null;
    const a = validatedAccount(this.data.database, this.data.selected);
    return {id: a.uuid.replaceAll('-', ''), name: a.username};
  }

  save() {
    if (!this.storage.isEncryptionAvailable()) throw new Error('Windows 계정 암호화를 사용할 수 없습니다.');
    fs.mkdirSync(path.dirname(this.file), {recursive: true});
    const temp = this.file + '.tmp';
    try {
      fs.writeFileSync(temp, this.storage.encryptString(JSON.stringify(this.data)), {mode: 0o600});
      fs.renameSync(temp, this.file);
    } finally { fs.rmSync(temp, {force: true}); }
  }

  logout() {
    if (this.active) throw new Error('로그인을 종료한 후 다시 시도하세요.');
    this.data = null;
    for (const file of [this.file, this.file + '.tmp', path.join(this.root, 'credentials.bin')]) fs.rmSync(file, {force: true});
    fs.rmSync(this.workRoot, {recursive: true, force: true});
  }

  async login(signal, {interactive = false} = {}) {
    if (this.active) throw new Error('이미 로그인 중입니다.');
    if (!this.storage.isEncryptionAvailable()) throw new Error('Windows 계정 암호화를 사용할 수 없습니다.');
    signal.throwIfAborted();
    this.active = true;
    try {
      fs.mkdirSync(this.workRoot, {recursive: true, mode: 0o700});
      const mainDir = path.join(this.workRoot, 'launcher');
      fs.mkdirSync(mainDir, {mode: 0o700});
      const databaseFile = path.join(this.workRoot, 'session.json');
      const opts = {mainDir, databaseFile, signal};
      let result;
      if (this.data && !interactive) {
        const cached = validatedAccount(this.data.database, this.data.selected);
        fs.writeFileSync(databaseFile, JSON.stringify({accounts: [cached]}), {mode: 0o600});
        try {
          result = await this.backend.run(['auth', 'refresh', cached.uuid], opts);
        } catch (error) {
          signal.throwIfAborted();
          if (!error.reauthenticate) throw error;
          this.data = null;
          fs.rmSync(this.file, {force: true});
          fs.rmSync(databaseFile, {force: true});
        }
      }
      if (!result) {
        // An interactive login should replace, rather than accumulate, accounts.
        fs.rmSync(databaseFile, {force: true});
        result = await this.backend.run(['auth', 'login', '--no-browser'], {
          ...opts, timeoutMs: 15 * 60 * 1000, openLogin: this.showLogin
        });
      }
      signal.throwIfAborted();
      if (!fs.existsSync(databaseFile) || fs.statSync(databaseFile).size > 1024 * 1024) throw new Error('Minecraft 로그인 정보를 읽지 못했습니다.');
      let database;
      try { database = JSON.parse(fs.readFileSync(databaseFile, 'utf8')); }
      catch { throw new Error('Minecraft 로그인 정보를 읽지 못했습니다. 다시 로그인하세요.'); }
      const account = validatedAccount(database, result.uuid);
      if (account.username !== result.username) throw new Error('Minecraft 계정 인증 결과가 일치하지 않습니다.');
      const previous = this.data;
      this.data = {format: 1, database: {accounts: [account]}, selected: account.uuid};
      try { this.save(); }
      catch (error) { this.data = previous; throw error; }
      return {profile: this.account(), accessToken: account.access_token, xuid: account.xuid, clientId: account.app_id};
    } finally {
      try { fs.rmSync(this.workRoot, {recursive: true, force: true}); }
      finally { this.active = false; }
    }
  }
}

module.exports = {AccountAuth, isLoginUrl, validatedAccount};
