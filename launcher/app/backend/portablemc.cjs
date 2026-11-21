'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const readline = require('node:readline');
const {spawn} = require('node:child_process');

function isLoginUrl(value) {
  try {
    const u = new URL(value);
    return u.protocol === 'https:' && !u.username && !u.password && !u.port &&
      ['microsoftonline.com', 'live.com', 'microsoft.com'].some(h => u.hostname === h || u.hostname.endsWith('.' + h));
  } catch { return false; }
}

function parseMachineLine(line) {
  if (line.length > 32768) throw new Error('인증 응답의 크기 제한을 초과했습니다.');
  const [code, ...fields] = line.replace(/\r$/, '').split('\t');
  if (!/^[a-z][a-z0-9_]*$/.test(code)) return null;
  return {code, fields: fields.map(s => s.replace(/\\([nt])/g, (_, c) => c === 'n' ? '\n' : '\t'))};
}

function authError(event) {
  const e = new Error('Microsoft 인증을 완료하지 못했습니다. 다시 시도하세요.');
  e.code = event?.code || 'backend_failed';
  e.reauthenticate = false;
  switch (e.code) {
    case 'error_auth_declined': e.message = 'Microsoft 로그인이 취소되거나 거부되었습니다.'; break;
    case 'error_auth_timed_out': e.message = '로그인 시간이 만료되었습니다. 다시 로그인하세요.'; break;
    case 'error_auth_does_not_own_game': e.message = 'Minecraft Java Edition을 플레이할 수 있는 계정이 필요합니다.'; break;
    case 'error_auth_outdated_token': e.message = '다시 로그인해야 합니다.'; e.reauthenticate = true; break;
    case 'error_auth_invalid_status': {
      const status = Number(event.fields[0]);
      if (status === 401) { e.message = '다시 로그인해야 합니다.'; e.reauthenticate = true; }
      else if (status === 403) e.message = '계정 또는 인증 앱의 Minecraft 접근 권한이 거부되었습니다.';
      else if (status >= 400 && status <= 599) e.message = `Microsoft 인증 실패 (HTTP ${status}).`;
      break;
    }
    case 'error_auth_unknown': {
      // Upstream diagnostics can contain account information. Classify them,
      // but never forward or persist the raw diagnostic.
      const text = event.fields.join(' ');
      if (/invalid_grant|AADSTS70008|AADSTS50173|expired|revoked/i.test(text)) {
        e.message = '다시 로그인해야 합니다.'; e.reauthenticate = true;
      } else if (/lacks approval|Forbidden access/i.test(text)) {
        e.message = '인증 앱의 Minecraft API 접근 권한이 거부되었습니다.';
      } else if (/2148916233/.test(text)) e.message = '이 Microsoft 계정에 Xbox 프로필을 먼저 만들어야 합니다.';
      else if (/2148916238/.test(text)) e.message = 'Microsoft 가족 설정에서 이 계정의 Xbox 이용을 허용해야 합니다.';
      break;
    }
    case 'error_msa_database_corrupted':
    case 'error_msa_database_write_failed': e.message = '로그인 정보를 저장하지 못했습니다.'; break;
  }
  return e;
}

function bundledBinary(appRoot) {
  const sub = path.join('vendor', 'portablemc', 'windows-x64');
  const diskRoot = appRoot.endsWith('.asar') ? appRoot + '.unpacked' : appRoot;
  let meta;
  try { meta = JSON.parse(fs.readFileSync(path.join(appRoot, sub, 'metadata.json'), 'utf8')); }
  catch { throw new Error('배포본에 인증 실행 파일 정보가 없습니다.'); }
  if (meta.version !== '5.0.5' || !/^[a-f0-9]{64}$/.test(meta.binarySha256 || '')) throw new Error('인증 실행 파일 정보가 올바르지 않습니다.');
  if (meta.runtime?.name !== 'vcruntime140.dll' || !/^[a-f0-9]{64}$/.test(meta.runtime.sha256 || '')) throw new Error('인증 런타임 정보가 올바르지 않습니다.');
  return {path: path.join(diskRoot, sub, 'portablemc.exe'), sha256: meta.binarySha256,
    runtime: {path: path.join(diskRoot, sub, meta.runtime.name), sha256: meta.runtime.sha256}};
}

class PortableMC {
  constructor({appRoot, binary, spawnImpl = spawn}) {
    this.appRoot = appRoot;
    this.binary = binary;
    this.spawn = spawnImpl;
  }

  verify() {
    const binary = this.binary || bundledBinary(this.appRoot);
    for (const file of [binary, binary.runtime].filter(Boolean)) {
      let actual;
      try { actual = crypto.createHash('sha256').update(fs.readFileSync(file.path)).digest('hex'); }
      catch { throw new Error('인증 실행 파일 또는 런타임을 찾지 못했습니다.'); }
      if (actual !== file.sha256) throw new Error('인증 실행 파일의 해시가 일치하지 않습니다.');
    }
    return binary.path;
  }

  run(args, {databaseFile, mainDir, signal, openLogin, timeoutMs = 120000}) {
    signal.throwIfAborted();
    const executable = this.verify();
    // The original PortableMC executable owns its OAuth identity and token flow.
    // Do not override its OAuth client ID, and ignore ambient PMC overrides.
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.toUpperCase().startsWith('PMC_')));
    const argv = ['--main-dir', mainDir, '--msa-db-file', databaseFile, '--output', 'machine', ...args];
    return new Promise((resolve, reject) => {
      let child, lines, failure, errorEvent, result, closeLogin, closed = false, killTimer;
      let receivedBytes = 0, showedCode = false;
      const cleanup = () => {
        clearTimeout(timer); clearTimeout(killTimer);
        signal.removeEventListener('abort', abort);
        lines?.close();
        closeLogin?.(); closeLogin = null;
      };
      const stop = error => {
        if (closed || failure) return;
        failure = error;
        child?.kill();
        killTimer = setTimeout(() => child?.kill('SIGKILL'), 1500);
        killTimer.unref();
      };
      const abort = () => stop(new Error('로그인이 취소되었습니다.'));
      const timer = setTimeout(() => stop(new Error('로그인 응답 시간이 만료되었습니다. 다시 시도하세요.')), timeoutMs);
      timer.unref();
      try {
        child = this.spawn(executable, argv, {
          cwd: mainDir, env, shell: false, windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe']
        });
      } catch {
        closed = true; cleanup(); reject(new Error('인증 실행 파일을 시작하지 못했습니다.')); return;
      }
      signal.addEventListener('abort', abort, {once: true});
      child.once('error', () => { failure ||= new Error('인증 실행 파일을 시작하지 못했습니다.'); });
      child.stderr.on('data', chunk => {
        receivedBytes += chunk.length;
        if (receivedBytes > 1024 * 1024) stop(new Error('인증 응답의 크기 제한을 초과했습니다.'));
      });
      child.stdout.on('data', chunk => {
        receivedBytes += chunk.length;
        if (receivedBytes > 1024 * 1024) stop(new Error('인증 응답의 크기 제한을 초과했습니다.'));
      });
      lines = readline.createInterface({input: child.stdout, crlfDelay: Infinity, terminal: false});
      lines.on('line', line => {
        if (failure || closed) return;
        try {
          const event = parseMachineLine(line);
          if (!event) return;
          if (event.code.startsWith('error_')) errorEvent ||= event;
          if (event.code === 'auth_device_code') {
            const [url, userCode] = event.fields;
            if (showedCode || !isLoginUrl(url) || !/^[a-z0-9-]{6,20}$/i.test(userCode || '') || !openLogin) throw new Error('Microsoft 로그인 요청이 올바르지 않습니다.');
            showedCode = true;
            Promise.resolve(openLogin({url, userCode, signal, onError: stop})).then(close => {
              if (closed || failure) close?.();
              else closeLogin = close;
            }).catch(() => stop(new Error('Microsoft 로그인 화면을 열지 못했습니다.')));
          }
          if (['auth_account_authenticated', 'auth_account_refreshed'].includes(event.code)) result = {uuid: event.fields[0], username: event.fields[1]};
        } catch (error) { stop(error); }
      });
      child.once('close', code => {
        closed = true; cleanup();
        if (failure) reject(failure);
        else if (code !== 0 || errorEvent) reject(authError(errorEvent));
        else if (!result) reject(new Error('Minecraft 계정 인증 결과가 없습니다.'));
        else resolve(result);
      });
      if (signal.aborted) abort();
    });
  }
}

module.exports = {PortableMC, isLoginUrl, parseMachineLine, authError, bundledBinary};
