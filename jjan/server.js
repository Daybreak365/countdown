// 짠 — 정적 파일 + 실시간 방(WebSocket) 서버
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { TABLES, UPGRADE, MAX_PLAYERS, isTable, reseat } from './public/js/tables.js';
import { cleanName } from './public/js/names.js';
import { cleanEmoji, QUICK_EMOJI } from './public/js/emoji.js';
import { barkeepFits, DEFAULT_BARKEEP } from './public/js/barkeeps.js';
import { isVenue, DEFAULT_VENUE } from './public/js/venues.js';
import { newMusic, musicApply, youtubeId } from './public/js/musicq.js';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT) || 3000;

// URL 접두사 → 실제 디렉터리
const MOUNTS = [
  ['/vendor/three/', path.join(ROOT, 'node_modules', 'three')],
  ['/vendor/galmuri/', path.join(ROOT, 'node_modules', 'galmuri', 'dist')],
  ['/', path.join(ROOT, 'public')],
];

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
};

function resolveFile(urlPath) {
  let decoded;
  try { decoded = decodeURIComponent(urlPath); } catch { return null; }
  for (const [prefix, dir] of MOUNTS) {
    if (!decoded.startsWith(prefix)) continue;
    const rel = decoded.slice(prefix.length) || 'index.html';
    const full = path.resolve(dir, rel);
    if (full !== dir && !full.startsWith(dir + path.sep)) return null;
    return full;
  }
  return null;
}

// 글자 파일은 gzip으로 줄여 보낸다 (three.js만 2MB라서, 무료 호스팅 전송량을 아끼려고)
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg']);
const gzCache = new Map(); // 경로 → { mtime, buf }

function gzipped(file, st, cb) {
  const hit = gzCache.get(file);
  if (hit && hit.mtime === st.mtimeMs) { cb(null, hit.buf); return; }
  fs.readFile(file, (err, raw) => {
    if (err) { cb(err); return; }
    zlib.gzip(raw, { level: 9 }, (err2, buf) => {
      if (!err2) gzCache.set(file, { mtime: st.mtimeMs, buf });
      cb(err2, buf);
    });
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/healthz') { res.writeHead(200, { 'content-type': 'text/plain' }).end('ok'); return; }
  // 혼자 짠 신청곡 제목 (브라우저는 유튜브에 직접 묻기 어렵다)
  if (url.pathname === '/api/yt-title') {
    const id = youtubeId(url.searchParams.get('id'));
    if (!id) { res.writeHead(400, { 'content-type': 'application/json' }).end('{}'); return; }
    fetchTitle(id).then((info) => {
      res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=86400' });
      res.end(JSON.stringify(info || {}));
    });
    return;
  }
  const file = resolveFile(url.pathname);
  if (!file) { res.writeHead(403).end(); return; }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404, { 'content-type': 'text/plain' }).end('not found'); return; }
    const ext = path.extname(file).toLowerCase();
    const type = MIME[ext] || 'application/octet-stream';
    const cache = url.pathname.startsWith('/vendor/') ? 'public, max-age=604800' : 'no-cache';
    const head = req.method === 'HEAD';
    if (COMPRESSIBLE.has(ext) && /\bgzip\b/.test(req.headers['accept-encoding'] || '')) {
      gzipped(file, st, (gzErr, buf) => {
        if (gzErr) { res.writeHead(500).end(); return; }
        res.writeHead(200, { 'content-type': type, 'content-encoding': 'gzip', 'content-length': buf.length, 'cache-control': cache, vary: 'Accept-Encoding' });
        res.end(head ? undefined : buf);
      });
      return;
    }
    res.writeHead(200, { 'content-type': type, 'content-length': st.size, 'cache-control': cache });
    if (head) { res.end(); return; }
    fs.createReadStream(file).pipe(res);
  });
});

// ── 방 관리 ────────────────────────────────────────────────
// 방마다 탁자가 하나. 자리가 다 차면 한 단계 큰 탁자로 옮겨 앉는다 (바 2 → 사각 4 → 원형 8 → 긴 탁자 12)
const CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const rooms = new Map();

function makeCode() {
  for (;;) {
    let code = '';
    for (let i = 0; i < 4; i++) code += CODE_CHARS[crypto.randomInt(CODE_CHARS.length)];
    if (!rooms.has(code)) return code;
  }
}

const cleanId = (s) => String(s ?? '').replace(/[^a-z0-9_-]/gi, '').slice(0, 24);

function publicPlayer(p) {
  return { id: p.id, name: p.name, seat: p.seat, drink: p.drink, variant: p.variant, glass: p.glass, anju: p.anju, fill: p.fill };
}

function send(ws, msg) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function broadcast(room, msg, exceptId) {
  const data = JSON.stringify(msg);
  for (const p of room.players.values()) {
    if (p.id !== exceptId && p.ws.readyState === p.ws.OPEN) p.ws.send(data);
  }
}

// 들어오기 전에 보는 방 모습. 지금 탁자가 꽉 찼으면 옮겨 갈 탁자 기준으로 보여 준다.
function roomInfo(room) {
  const players = [...room.players.values()];
  const grow = players.length >= TABLES[room.table].cap && UPGRADE[room.table];
  const table = grow ? UPGRADE[room.table] : room.table;
  return {
    t: 'room',
    room: room.code,
    table,
    from: room.table,
    grow: !!grow,
    full: players.length >= MAX_PLAYERS,
    venue: room.venue,
    song: room.music.list[0]?.title || '',
    seats: players.map((p) => ({ seat: grow ? reseat(room.table, p.seat) : p.seat, name: p.name, drink: p.drink })),
  };
}

function growTable(room) {
  const from = room.table, to = UPGRADE[from];
  if (!to) return;
  for (const p of room.players.values()) p.seat = reseat(from, p.seat);
  room.table = to;
  const seats = {};
  for (const p of room.players.values()) seats[p.id] = p.seat;
  broadcast(room, { t: 'table', table: to, seats });
}

function joinRoom(ws, room, msg) {
  if (room.players.size >= MAX_PLAYERS) { send(ws, { t: 'error', code: 'full' }); return; }
  if (room.players.size >= TABLES[room.table].cap) growTable(room);
  const { cap, order } = TABLES[room.table];
  const taken = new Set([...room.players.values()].map((p) => p.seat));
  const want = Number.isInteger(msg.seat) ? msg.seat : -1;
  const seat = want >= 0 && want < cap && !taken.has(want) ? want : order.find((s) => !taken.has(s));
  const player = {
    id: crypto.randomBytes(4).toString('hex'),
    name: cleanName(msg.name),
    drink: cleanId(msg.drink),
    variant: cleanId(msg.variant),
    glass: cleanId(msg.glass),
    anju: cleanId(msg.anju),
    fill: 1,
    seat,
    ws,
  };
  room.players.set(player.id, player);
  ws.player = player;
  ws.room = room;
  send(ws, {
    t: 'joined',
    room: room.code,
    table: room.table,
    // 고른 자리에 누가 먼저 앉았으면 다른 자리로
    moved: want >= 0 && want !== seat,
    you: publicPlayer(player),
    players: [...room.players.values()].filter((p) => p !== player).map(publicPlayer),
    venue: room.venue,
    barkeep: room.barkeep,
    music: room.music,
    now: Date.now(),
  });
  broadcast(room, { t: 'peer-join', player: publicPlayer(player) }, player.id);
}

function leaveRoom(ws) {
  const { room, player } = ws;
  if (!room || !player) return;
  room.players.delete(player.id);
  broadcast(room, { t: 'peer-leave', id: player.id });
  if (room.players.size === 0) rooms.delete(room.code);
  ws.room = ws.player = null;
}

// ── 같이 듣는 노래 ─────────────────────────────────────────
// 유튜브 제목은 서버가 oEmbed로 알아 온다 (브라우저는 다른 사이트에 묻기 어렵다)
const titleCache = new Map();
async function fetchTitle(id) {
  if (titleCache.has(id)) return titleCache.get(id);
  try {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 5000);
    const res = await fetch(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent(`https://www.youtube.com/watch?v=${id}`)}`, { signal: ctl.signal });
    clearTimeout(timer);
    if (!res.ok) return null;
    const j = await res.json();
    const info = { title: String(j.title || ''), author: String(j.author_name || '') };
    if (titleCache.size > 500) titleCache.delete(titleCache.keys().next().value);
    titleCache.set(id, info);
    return info;
  } catch {
    return null;
  }
}

function sendMusic(room, a, by) {
  broadcast(room, { t: 'music', m: room.music, now: Date.now(), a, by });
}

function handleMusic(ws, room, player, msg) {
  const a = String(msg.a || '');
  const now = Date.now();
  if (a === 'add') {
    const id = youtubeId(msg.url);
    if (!id) { send(ws, { t: 'music-err', code: 'bad-url' }); return; }
    const item = { key: crypto.randomBytes(4).toString('hex'), id, title: '', author: '', by: player.name };
    if (!musicApply(room.music, { a: 'add', item }, now)) { send(ws, { t: 'music-err', code: 'full' }); return; }
    sendMusic(room, 'add', player.name);
    fetchTitle(id).then((info) => {
      if (info?.title && musicApply(room.music, { a: 'title', key: item.key, title: info.title, author: info.author }, Date.now())) sendMusic(room, 'title');
    });
    return;
  }
  if (['pause', 'play', 'next', 'ended', 'error', 'remove', 'seek', 'title'].includes(a)) {
    const act = { a, key: cleanId(msg.key), pos: num(msg.pos), title: msg.title, author: msg.author };
    if (musicApply(room.music, act, now)) sendMusic(room, a, player.name);
  }
}

const num = (v) => (Number.isFinite(v) ? Math.round(v * 100) / 100 : 0);
const vec = (a, n) => (Array.isArray(a) ? a.slice(0, n).map(num) : new Array(n).fill(0));

function handle(ws, msg) {
  const { room, player } = ws;
  switch (msg.t) {
    case 'create': {
      if (room) return;
      const code = makeCode();
      const venue = isVenue(msg.venue) ? msg.venue : DEFAULT_VENUE;
      const r = { code, table: isTable(msg.table) ? msg.table : 'round', venue, barkeep: barkeepFits(msg.barkeep, venue) ? msg.barkeep : DEFAULT_BARKEEP, music: newMusic(), players: new Map() };
      rooms.set(code, r);
      joinRoom(ws, r, msg);
      return;
    }
    case 'peek': {
      const r = rooms.get(String(msg.room ?? '').toUpperCase().trim());
      if (!r) { send(ws, { t: 'error', code: 'no-room', peek: true }); return; }
      send(ws, roomInfo(r));
      return;
    }
    case 'join': {
      if (room) return;
      const r = rooms.get(String(msg.room ?? '').toUpperCase().trim());
      if (!r) { send(ws, { t: 'error', code: 'no-room' }); return; }
      joinRoom(ws, r, msg);
      return;
    }
  }
  if (!room || !player) return;
  switch (msg.t) {
    case 'pose':
      player.fill = num(msg.f);
      broadcast(room, { t: 'pose', id: player.id, p: vec(msg.p, 3), r: vec(msg.r, 2), f: player.fill, h: !!msg.h, d: Math.max(0, Math.min(2, num(msg.d))) }, player.id);
      break;
    case 'set':
      player.drink = cleanId(msg.drink);
      player.variant = cleanId(msg.variant);
      player.glass = cleanId(msg.glass);
      if (msg.anju) player.anju = cleanId(msg.anju);
      broadcast(room, { t: 'set', id: player.id, drink: player.drink, variant: player.variant, glass: player.glass, anju: player.anju }, player.id);
      break;
    case 'clink':
      broadcast(room, { t: 'clink', from: player.id, with: cleanId(msg.with), s: num(msg.s) }, player.id);
      break;
    case 'say':
      broadcast(room, { t: 'say', id: player.id, i: Math.max(0, Math.min(20, msg.i | 0)) }, player.id);
      break;
    case 'emo': {
      // 이모지 반응: 3초에 8개까지만 돌린다
      const now = Date.now();
      player.emo = (player.emo || []).filter((t) => now - t < 3000);
      if (player.emo.length >= 8) break;
      // 아무 이모지나 하나 (이모지가 아닌 글자는 버린다). 예전 브라우저는 번호(i)로 보낸다
      const e = msg.e !== undefined ? cleanEmoji(msg.e) : QUICK_EMOJI[msg.i];
      if (!e) break;
      player.emo.push(now);
      broadcast(room, { t: 'emo', id: player.id, e }, player.id);
      break;
    }
    case 'act':
      // sip 마시기, shot 원샷, refill 채우기, eat 안주, remedy 숙취해소제(v: 어떤 것), steal 남의 안주 뺏어 먹기(v: 누구 것)
      if (['sip', 'shot', 'refill', 'eat', 'remedy', 'steal'].includes(msg.a)) broadcast(room, { t: 'act', id: player.id, a: msg.a, v: cleanId(msg.v) }, player.id);
      break;
    // 가게 옮기기: 방 사람 모두 같이 옮겨 간다
    case 'venue':
      if (!isVenue(msg.v) || msg.v === room.venue) break;
      room.venue = msg.v;
      // 그 가게 전용 바텐더였으면 기본 바텐더로
      if (!barkeepFits(room.barkeep, room.venue)) room.barkeep = DEFAULT_BARKEEP;
      broadcast(room, { t: 'venue', v: room.venue, barkeep: room.barkeep, by: player.name }, player.id);
      break;
    // 바텐더 바꾸기: 방 사람 모두 같은 바텐더를 본다
    case 'barkeep':
      if (!barkeepFits(msg.id, room.venue) || msg.id === room.barkeep) break;
      room.barkeep = msg.id;
      broadcast(room, { t: 'barkeep', id: room.barkeep, by: player.name }, player.id);
      break;
    case 'music':
      handleMusic(ws, room, player, msg);
      break;
  }
}

const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 4096 });

wss.on('connection', (ws) => {
  ws.alive = true;
  ws.budget = 60;
  ws.on('pong', () => { ws.alive = true; });
  ws.on('message', (data) => {
    // 초당 메시지 수 제한(포즈는 20Hz)
    if (--ws.budget < 0) return;
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    if (msg && typeof msg.t === 'string') handle(ws, msg);
  });
  ws.on('close', () => leaveRoom(ws));
});

setInterval(() => {
  for (const ws of wss.clients) ws.budget = 60;
}, 1000);

setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.alive) { ws.terminate(); continue; }
    ws.alive = false;
    ws.ping();
  }
}, 20000);

server.listen(PORT, () => {
  console.log(`짠 서버가 열렸어요 → http://localhost:${PORT}`);
});
