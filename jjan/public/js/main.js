import { Game, ThumbRenderer } from './game.js';
import { cleanEmoji, findEmojis, withTone, TONES, QUICK_EMOJI } from './emoji.js';
import { BARTENDER_LINES } from './bartender.js';
import { ANJU, anjuById, pairAnju } from './anju.js';
import { iconSVG } from './icons.js';
import { Net } from './net.js';
import { CATEGORIES, DRINKS, GLASSES, PHRASES, SAY_EXTRA, drinkById, glassById, variantOf } from './data.js';
import { REMEDIES } from './remedy.js';
import { TABLES, TABLE_TYPES, MAX_PLAYERS, seatPose, chairPose, relation, ROUND, BIGROUND, USHAPE } from './tables.js';
import { VENUES, venueById, DEFAULT_VENUE } from './venues.js';
import { cleanName, nameWidth, NAME_WIDTH } from './names.js';
import { drawReceipt } from './receipt.js';
import { barkeepById, barkeepsFor, barkeepFits, DEFAULT_BARKEEP } from './barkeeps.js';
import { MusicBox } from './music.js';

const $ = (s) => document.querySelector(s);

const store = {
  get(k, d) {
    try { const v = localStorage.getItem('jjan:' + k); return v == null ? d : JSON.parse(v); } catch { return d; }
  },
  set(k, v) {
    try { localStorage.setItem('jjan:' + k, JSON.stringify(v)); } catch { /* 저장 못 해도 괜찮다 */ }
  },
};

// 이모지 반응 (😀 버튼): 잠시 꺼 둔다. true로 바꾸면 다시 나온다
const EMOJI_ON = false;

const state = {
  drink: drinkById[store.get('drink')] ? store.get('drink') : 'lager',
  glass: null,
  pickDrink: null,
  pickGlass: null,
  pending: null,
  cat: null,
  mode: 'title',
  count: 0,
  room: null,
  // 직접 고르기 전까지는 술에 어울리는 안주가 나온다
  anju: anjuById[store.get('anju')] ? store.get('anju') : null,
  anjuChosen: !!store.get('anjuChosen', false),
  pickAnju: null,
  // 같이 짠: 방 만들 때 고른 탁자, 앉을 자리, 들어갈 방
  table: TABLES[store.get('table')] ? store.get('table') : 'round',
  seat: null,
  seatKind: null,
  seatTable: 'round',
  seatTaken: new Map(),
  roomInfo: null,
  joinCode: null,
  roomTable: 'round',
  invite: null,
  // 술마다 고른 상품 (콜라 → 펩시 제로 라임 …)
  variants: store.get('variants', {}),
  pickVariant: null,
  // 가게: 혼자 짠·방 만들 때 고른 곳(venue), 같이 짠 방이 지금 있는 곳(roomVenue)
  venue: venueById[store.get('venue')] ? store.get('venue') : DEFAULT_VENUE,
  roomVenue: null,
  // 바텐더: 혼자 짠은 가게마다 고른 사람(barkeeps), 같이 짠은 방에서 정한 사람(roomBarkeep)
  barkeeps: store.get('barkeeps', {}),
  roomBarkeep: null,
};
function barkeepFor(venue) {
  // 방에 들어와 있으면(들어오는 중 포함) 방에서 정한 사람
  const id = state.roomBarkeep ?? state.barkeeps[venue];
  return barkeepFits(id, venue) ? id : DEFAULT_BARKEEP;
}

// 받침 (을/를, 으로/로)
function josa(word, pair) {
  const c = word.charCodeAt(word.length - 1);
  const jong = c >= 0xac00 && c <= 0xd7a3 ? (c - 0xac00) % 28 : 0;
  if (pair === '으로') return word + (jong && jong !== 8 ? '으로' : '로');
  const [a, b] = pair.split('/');
  return word + (jong ? a : b);
}

// 이 술의 상품: 고른 게 있으면 그것, 없으면 첫 번째
function variantFor(drinkId) {
  const d = drinkById[drinkId];
  if (!d?.variants) return null;
  return variantOf(drinkId, state.variants[drinkId])?.id ?? d.variants[0].id;
}

function currentAnju() {
  return state.anjuChosen && state.anju ? state.anju : pairAnju(drinkById[state.drink]);
}

// 아래 버튼에 픽셀 아이콘
for (const b of document.querySelectorAll('.act[data-icon]')) b.insertAdjacentHTML('afterbegin', iconSVG(b.dataset.icon, 32));
state.glass = glassById[store.get('glass')] && !glassById[store.get('glass')].hidden ? store.get('glass') : drinkById[state.drink].glass;



const game = new Game($('#scene'), {
  labelLayer: $('#labels'),
  phrase: (i) => PHRASES[i] ?? SAY_EXTRA[i] ?? '짠!',
  onClink,
  onGrab() {
    $('#hint').classList.add('gone');
  },
  onDevice: () => openMusic(),
  onLook: (on) => {
    document.body.classList.toggle('looking', on);
    $('#btn-look').setAttribute('aria-pressed', String(on));
    $('#look-bar').hidden = !on;
  },
  // 남의 안주 뺏어 먹기
  onSteal: (name) => {
    if (store.get('stealTip', false)) return;
    store.set('stealTip', true);
    toast(name ? `${name} 님 안주를 한 입 뺏어 먹었어요.` : `${game.hostName} 몫 안주를 한 입 뺏어 먹었어요.`);
  },
  onStolen: (name) => toast(`${name} 님이 내 안주를 뺏어 먹었어요!`),
  onStealEmpty: (name) => toast(`${name} 님 접시가 비었어요.`),
});
game.barkeepFor = barkeepFor;
game.setVenue(state.venue);

// 같이 듣는 노래 (혼자 짠은 이 브라우저가, 같이 짠은 서버가 목록을 들고 있다)
const music = new MusicBox({
  mount: 'yt-frame',
  onChange: () => renderMusic(),
  onError: (code, item) => musicError(code, item),
});
game.music = music;
music.onTick = () => renderMusicTime();

let net = null;
if (new URLSearchParams(location.search).has('debug')) window.jjan = { game, state };

// ── 타이틀 ───────────────────────────────────────────────
const nameInput = $('#name');
nameInput.value = store.get('name', '');
const params = new URLSearchParams(location.search);
const cleanCode = (v) => String(v || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 4);

function myName() {
  const n = cleanName(nameInput.value, '');
  store.set('name', n);
  return n || '손님';
}
// 이름은 화면에 보이는 폭으로 자른다 (한글은 7자, 영문은 14자쯤). 한글 조합 중에는 건드리지 않는다.
nameInput.addEventListener('input', (e) => {
  if (e.isComposing || nameWidth(nameInput.value) <= NAME_WIDTH) return;
  nameInput.value = cleanName(nameInput.value, '');
});
nameInput.addEventListener('change', () => { nameInput.value = cleanName(nameInput.value, ''); });

function titleMsg(text) {
  $('#title-msg').textContent = text;
}

$('#btn-single').addEventListener('click', () => {
  game.audio.unlock();
  openPicker('single');
});
$('#btn-multi').addEventListener('click', () => {
  game.audio.unlock();
  titleMsg('');
  openSeats('create');
});
$('#btn-join').addEventListener('click', () => {
  game.audio.unlock();
  const code = cleanCode($('#code').value);
  if (code.length !== 4) { titleMsg('방 코드 네 글자를 넣어 주세요.'); $('#code').focus(); return; }
  joinFlow(code);
});
$('#code').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#btn-join').click(); });

// ── 초대 링크로 왔을 때: 들어가기가 먼저 ─────────────────
function setInvite(code) {
  state.invite = code;
  const on = !!code;
  $('#invite').hidden = !on;
  $('#invite-actions').hidden = !on;
  $('#title-sub').hidden = on;
  $('#choices').hidden = on;
  $('#choices').classList.toggle('subtle', on);
  $('#btn-other').hidden = false;
  $('#btn-enter').disabled = false;
  if (!on) return;
  $('#invite-code').textContent = code;
  $('#code').value = code;
  $('#invite-info').textContent = '방을 들여다보는 중...';
  peek(code).then(
    (info) => {
      if (state.invite !== code) return;
      $('#invite-info').textContent = describeRoom(info);
      // 꽉 찬 방이면 다른 걸 고르게
      if (info.full) {
        $('#invite-actions').hidden = true;
        $('#choices').classList.remove('subtle');
        showChoices();
      }
    },
    (err) => {
      if (state.invite !== code) return;
      if (err === 'no-room') {
        $('#invite-info').textContent = '이 방은 벌써 문을 닫았어요. 새로 방을 만들거나 혼자 한 잔 해요.';
        // 들어갈 방이 없으니 평소 첫 화면처럼
        $('#invite-actions').hidden = true;
        $('#choices').classList.remove('subtle');
        $('#code').value = '';
        showChoices();
      } else {
        $('#invite-info').textContent = '친구들이 기다리고 있어요.';
      }
    },
  );
}

function showChoices() {
  $('#choices').hidden = false;
  $('#btn-other').hidden = true;
}

function describeRoom(info) {
  const names = info.seats.map((s) => s.name);
  if (info.full) return `자리가 꽉 찼어요. (최대 ${MAX_PLAYERS}명)`;
  const who = names.length <= 3 ? names.join(', ') : `${names.slice(0, 2).join(', ')} 외 ${names.length - 2}명`;
  const v = venueById[info.venue]?.name;
  const song = info.song ? ` ♪ ${info.song}` : '';
  return `${v ? `${v} ` : ''}${TABLES[info.from].name}에서 ${who} 님이 마시는 중이에요.${song}`;
}

$('#btn-enter').addEventListener('click', () => {
  game.audio.unlock();
  if (state.invite) joinFlow(state.invite);
});
$('#btn-other').addEventListener('click', showChoices);

const inviteCode = cleanCode(params.get('room'));
if (inviteCode.length === 4) setInvite(inviteCode);

// ── 서버 ─────────────────────────────────────────────────
async function ensureNet() {
  if (net?.open) return net;
  net?.close();
  net = new Net();
  try {
    await net.connect();
  } catch (e) {
    net = null;
    throw e;
  }
  return net;
}

// 들어가기 전에 방 모습(탁자, 앉은 사람)을 본다
async function peek(code) {
  try { await ensureNet(); } catch { throw 'connect'; }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject('timeout'), 8000);
    net.on('room', (msg) => { if (msg.room === code) { clearTimeout(timer); resolve(msg); } });
    net.on('error', (msg) => { clearTimeout(timer); reject(msg.code); });
    net.send({ t: 'peek', room: code });
  });
}

const ERRORS = {
  'no-room': '그런 방이 없어요. 코드를 다시 확인해 주세요.',
  full: `방이 꽉 찼어요. (최대 ${MAX_PLAYERS}명)`,
  connect: '서버에 연결하지 못했어요. 잠시 뒤에 다시 해 주세요.',
  timeout: '서버가 대답이 없어요. 잠시 뒤에 다시 해 주세요.',
};

async function joinFlow(code) {
  titleMsg('방을 들여다보는 중...');
  let info;
  try {
    info = await peek(code);
  } catch (err) {
    titleMsg(ERRORS[err] || ERRORS.connect);
    return;
  }
  titleMsg('');
  if (info.full) { titleMsg(ERRORS.full); return; }
  state.joinCode = code;
  openSeats('join', info);
}

// ── 탁자와 자리 고르기 ───────────────────────────────────
function openSeats(kind, info = null) {
  state.seatKind = kind;
  state.roomInfo = info;
  state.seatTable = kind === 'create' ? state.table : info.table;
  state.seatTaken = new Map((info?.seats || []).map((s) => [s.seat, s]));
  pickFirstSeat();
  $('#seats').hidden = false;
  hideSound(true);
  $('#title').hidden = true;
  $('#table-tabs').hidden = kind !== 'create';
  $('#seat-title').textContent = kind === 'create' ? '탁자랑 자리 고르기' : '어디 앉을까요?';
  // 들어갈 방의 가게를 미리 보여 준다
  if (kind === 'join' && info?.venue) game.setVenue(info.venue);
  game.previewTable(state.seatTable);
  renderSeats();
}

function pickFirstSeat() {
  state.seat = TABLES[state.seatTable].order.find((s) => !state.seatTaken.has(s)) ?? null;
}

function closeSeats() {
  hideSound(false);
  $('#seats').hidden = true;
  if (state.mode === 'title') {
    $('#title').hidden = false;
    game.setVenue(state.venue);
  }
}

function renderSeats() {
  renderTableTabs();
  renderSeatMap();
  const t = state.seatTable, info = state.roomInfo;
  const note = $('#seat-note');
  if (state.seatKind === 'create') {
    note.textContent = `${TABLES[t].desc}. 사람이 더 오면 탁자가 알아서 커져요.`;
  } else if (info.grow) {
    note.innerHTML = '';
    const b = document.createElement('b');
    b.textContent = `${TABLES[info.from].name} 자리가 다 찼어요.`;
    note.append(b, ` 들어가면 다 같이 ${TABLES[t].name}로 옮겨 앉아요.`);
  } else {
    note.textContent = `${TABLES[t].name} · ${state.seatTaken.size}명이 마시는 중`;
  }
  const s = state.seat;
  $('#btn-seat-ok').disabled = s == null;
  if (s == null) {
    $('#seat-name').textContent = '빈자리가 없어요';
    $('#seat-desc').textContent = '';
    return;
  }
  $('#seat-name').textContent = `${TABLES[t].name} · ${s + 1}번 자리`;
  const near = [];
  for (const [seat, who] of state.seatTaken) {
    const r = relation(t, s, seat);
    if (r) near.push(`${who.name} 님 ${r === 'across' ? '맞은편' : '옆자리'}`);
  }
  $('#seat-desc').textContent = near.length ? `${near.join(', ')}이에요.` : state.seatTaken.size ? '조금 떨어진 자리예요. 잔은 탁자 어디로든 밀 수 있어요.' : '먼저 앉아서 친구를 기다려요.';
}

function renderTableTabs() {
  const nav = $('#table-tabs');
  nav.textContent = '';
  if (state.seatKind !== 'create') return;
  for (const t of TABLE_TYPES) {
    const b = document.createElement('button');
    b.className = 'tab';
    b.type = 'button';
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-selected', String(t === state.seatTable));
    b.append(TABLES[t].short);
    b.title = TABLES[t].name;
    const small = document.createElement('small');
    small.textContent = `${TABLES[t].cap}명`;
    b.appendChild(small);
    b.addEventListener('click', () => {
      state.table = state.seatTable = t;
      pickFirstSeat();
      game.previewTable(t);
      renderSeats();
    });
    nav.appendChild(b);
  }
}

// 위에서 내려다본 탁자 배치도 (복셀 느낌이 나게 계단식으로)
const SVGNS = 'http://www.w3.org/2000/svg';
const VIEW = { round: [-134, -134, 268, 268], square: [-98, -98, 196, 196], long: [-158, -88, 316, 176], bar: [-112, -192, 224, 112], biground: [-152, -152, 304, 304], ushape: [-188, -112, 376, 288] };

function svgEl(tag, attrs, parent) {
  const e = document.createElementNS(SVGNS, tag);
  for (const k in attrs) e.setAttribute(k, attrs[k]);
  parent?.appendChild(e);
  return e;
}

const hex = (c) => `#${(c >>> 0).toString(16).padStart(6, '0')}`;

function renderSeatMap() {
  const box = $('#seat-map');
  box.textContent = '';
  const t = state.seatTable;
  const [vx, vy, vw, vh] = VIEW[t];
  const svg = svgEl('svg', { viewBox: `${vx} ${vy} ${vw} ${vh}`, 'shape-rendering': 'crispEdges', role: 'group', 'aria-label': `${TABLES[t].name} 자리 배치` }, box);
  svgEl('rect', { x: vx, y: vy, width: vw, height: vh, class: 'floor' }, svg);
  if (t === 'bar') {
    // 바 상판과 그 뒤의 바텐더
    svgEl('rect', { x: -14, y: -190, width: 24, height: 14, class: 'barkeep' }, svg);
    svgEl('rect', { x: -9, y: -186, width: 14, height: 8, class: 'barkeep-face' }, svg);
    svgEl('rect', { x: -96, y: -168, width: 192, height: 42, class: 'counter' }, svg);
    svgEl('rect', { x: -94, y: -166, width: 188, height: 36, class: 'counter-top' }, svg);
    const label = svgEl('text', { x: 36, y: -183, class: 'who' }, svg);
    label.textContent = '바텐더';
  } else if (t === 'round' || t === 'biground') {
    const R = (t === 'round' ? ROUND : BIGROUND).r;
    for (let z = -R, i = 0; z < R; z += 5, i++) {
      const hw = Math.floor(Math.sqrt(Math.max(0, R * R - (z + 2.5) ** 2)) / 5) * 5;
      if (hw > 0) svgEl('rect', { x: -hw, y: z, width: hw * 2, height: 5, class: i % 2 ? 'wood' : 'wood-2' }, svg);
    }
  } else if (t === 'ushape') {
    const { base, armL, armR } = USHAPE;
    for (const r of [base, { ...armL, z1: base.z0 }, { ...armR, z1: base.z0 }]) {
      svgEl('rect', { x: r.x0 - 2, y: r.z0 - 2, width: r.x1 - r.x0 + 4, height: r.z1 - r.z0 + 4, class: 'edge' }, svg);
    }
    for (const [r, i] of [[base, 0], [{ ...armL, z1: base.z0 }, 1], [{ ...armR, z1: base.z0 }, 1]]) {
      svgEl('rect', { x: r.x0, y: r.z0, width: r.x1 - r.x0, height: r.z1 - r.z0, class: i ? 'wood-2' : 'wood' }, svg);
    }
    // 바는 위쪽(바 쪽)에 있다
    svgEl('rect', { x: -96, y: -112, width: 192, height: 10, class: 'counter' }, svg);
  } else {
    const [hx, hz] = t === 'square' ? [36, 36] : [122, 32];
    svgEl('rect', { x: -hx - 2, y: -hz - 2, width: hx * 2 + 4, height: hz * 2 + 4, class: 'edge' }, svg);
    for (let x = -hx, i = 0; x < hx; x += 12, i++) svgEl('rect', { x, y: -hz, width: Math.min(12, hx - x), height: hz * 2, class: i % 2 ? 'wood' : 'wood-2' }, svg);
  }
  const { cap } = TABLES[t];
  for (let s = 0; s < cap; s++) {
    const c = chairPose(t, s), p = seatPose(t, s);
    const who = state.seatTaken.get(s), mine = state.seat === s;
    const g = svgEl('g', { class: `seat ${who ? 'taken' : 'free'}${mine ? ' mine' : ''}`, role: 'button', tabindex: who ? '-1' : '0', 'aria-label': who ? `${who.name} 님 자리` : `${s + 1}번 빈자리` }, svg);
    const size = t === 'bar' ? 20 : 22;
    svgEl('rect', { x: c.x - size / 2, y: c.z - size / 2, width: size, height: size, class: 'chair' }, g);
    const num = svgEl('text', { x: c.x, y: c.z + 0.5, class: 'num' }, g);
    num.textContent = String(s + 1);
    if (who || mine) {
      const color = who ? drinkById[who.drink]?.color ?? 0xd9a441 : drinkById[state.drink]?.color ?? 0xd9a441;
      svgEl('rect', { x: p.x - 4, y: p.z - 5, width: 8, height: 10, class: 'cup', fill: hex(color) }, svg);
      // 이름은 의자 바깥쪽에
      let dx = c.x - p.x, dz = c.z - p.z;
      const d = Math.hypot(dx, dz) || 1;
      dx /= d; dz /= d;
      const lx = c.x + dx * 19, lz = c.z + dz * 19;
      const tag = svgEl('text', { x: lx, y: lz, class: `who${mine ? ' me' : ''}` }, svg);
      tag.textContent = mine ? '나' : who.name.length > 6 ? `${who.name.slice(0, 6)}…` : who.name;
    }
    if (!who) {
      const choose = () => { state.seat = s; renderSeats(); };
      g.addEventListener('click', choose);
      g.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(); } });
    }
  }
}

$('#btn-close-seat').addEventListener('click', closeSeats);
$('#btn-seat-ok').addEventListener('click', () => {
  if (state.seat == null) return;
  if (state.seatKind === 'create') store.set('table', state.table);
  $('#seats').hidden = true;
  openPicker(state.seatKind);
});

function showTitle() {
  state.mode = 'title';
  $('#hud').hidden = true;
  $('#title').hidden = false;
  game.picking = false;
}

function showHud(mode) {
  state.mode = mode;
  $('#title').hidden = true;
  $('#hud').hidden = false;
  $('#room-info').hidden = mode !== 'multi';
  const hint = $('#hint');
  hint.classList.remove('gone');
  hint.textContent = mode === 'single'
    ? `잔을 꾹 누른 채 끌어서 ${game.hostName} 잔에 부딪혀 보세요.`
    : '잔을 끌어서 친구 잔에 부딪혀 보세요. 서로 잔을 들어야 닿아요.';
  state.count = 0;
  $('#count').textContent = '0';
  renderPhrases();
}

// ── 게임 화면 ────────────────────────────────────────────
function onClink() {
  state.count++;
  const c = $('#counter');
  $('#count').textContent = String(state.count);
  c.classList.remove('bump');
  void c.offsetWidth;
  c.classList.add('bump');
}

// 건배사 줄 맨 앞 😀 버튼
function emojiButton() {
  const emoBtn = document.createElement('button');
  emoBtn.className = 'chip emo-toggle';
  emoBtn.id = 'btn-emo';
  emoBtn.textContent = '😀';
  emoBtn.title = '이모지 반응';
  emoBtn.setAttribute('aria-expanded', 'false');
  emoBtn.setAttribute('aria-controls', 'emo-tray');
  emoBtn.addEventListener('click', () => toggleEmo());
  return emoBtn;
}

function renderPhrases() {
  const box = $('#phrases');
  box.textContent = '';
  // 이모지 반응은 잠시 꺼 둔다 (EMOJI_ON을 true로 바꾸면 다시 나온다)
  if (EMOJI_ON) box.appendChild(emojiButton());
  PHRASES.forEach((text, i) => {
    const b = document.createElement('button');
    b.className = 'chip';
    b.textContent = text;
    b.addEventListener('click', () => {
      game.say(i, text);
      if (state.mode === 'single' && Math.random() < 0.8) {
        const lines = BARTENDER_LINES.reply;
        setTimeout(() => game.bartenderSay(lines[Math.floor(Math.random() * lines.length)]), 900 + Math.random() * 600);
      }
    });
    box.appendChild(b);
  });
}

$('#btn-pick').addEventListener('click', () => openPicker('change'));
$('#btn-anju').addEventListener('click', () => openPicker('anju'));
// 마시기: 톡 누르면 한 모금, 꾹 누르고 있으면 버튼이 차오르다가 다 차면 원샷
const sipBtn = $('#btn-sip');
const HOLD_START = 0.18, HOLD_FULL = 0.85; // 초
let hold = null;
function holdTick() {
  if (!hold) return;
  const el = (performance.now() - hold.t0) / 1000;
  const k = Math.max(0, Math.min(1, (el - HOLD_START) / (HOLD_FULL - HOLD_START)));
  sipBtn.style.setProperty('--hold', k.toFixed(3));
  if (k >= 1) {
    hold.fired = true;
    endHold();
    if (game.oneShot()) {
      sipBtn.classList.remove('shot');
      void sipBtn.offsetWidth;
      sipBtn.classList.add('shot');
    }
    return;
  }
  hold.raf = requestAnimationFrame(holdTick);
}
function endHold() {
  if (!hold) return;
  cancelAnimationFrame(hold.raf);
  sipBtn.style.setProperty('--hold', '0');
}
sipBtn.addEventListener('pointerdown', (e) => {
  if (sipBtn.disabled || e.button > 0) return;
  e.preventDefault();
  game.audio.unlock();
  try { sipBtn.setPointerCapture(e.pointerId); } catch { /* 괜찮다 */ }
  hold = { t0: performance.now(), raf: 0, fired: false, id: e.pointerId };
  hold.raf = requestAnimationFrame(holdTick);
});
const releaseSip = (e, cancel) => {
  if (!hold || e.pointerId !== hold.id) return;
  const h = hold;
  endHold();
  hold = null;
  if (!h.fired && !cancel) game.sip();
};
sipBtn.addEventListener('pointerup', (e) => releaseSip(e, false));
sipBtn.addEventListener('pointercancel', (e) => releaseSip(e, true));
sipBtn.addEventListener('contextmenu', (e) => e.preventDefault());
// 키보드: Enter/Space는 한 모금, Shift+Enter는 원샷
sipBtn.addEventListener('click', (e) => {
  if (e.detail !== 0) return;
  if (e.shiftKey) game.oneShot();
  else game.sip();
});

// 해장: 숙취해소제 고르기
const remedyPanel = $('#remedy-panel');
for (const r of REMEDIES) {
  const b = document.createElement('button');
  b.className = 'btn';
  b.type = 'button';
  const sw = `<span class="sw" style="background:#${r.color.toString(16).padStart(6, '0')}"></span>`;
  b.innerHTML = `<span>${sw}${r.name}</span><small>${r.desc}</small>`;
  b.addEventListener('click', () => {
    game.audio.unlock();
    toggleRemedy(false);
    if (!game.drinkRemedy(r.id)) toast(`조금 있다가 또 먹을 수 있어요. (${Math.ceil(game.remedyWait)}초)`);
  });
  $('#remedy-list').appendChild(b);
}
function toggleRemedy(open = remedyPanel.hidden) {
  remedyPanel.hidden = !open;
  $('#btn-remedy').setAttribute('aria-expanded', String(open));
}
$('#btn-remedy').addEventListener('click', () => {
  if (game.remedyWait > 0) { toast(`조금 있다가 또 먹을 수 있어요. (${Math.ceil(game.remedyWait)}초)`); return; }
  toggleRemedy();
});
document.addEventListener('pointerdown', (e) => {
  if (!remedyPanel.hidden && !e.target.closest('#remedy-panel, #btn-remedy')) toggleRemedy(false);
});
$('#btn-refill').addEventListener('click', () => game.refill());
$('#btn-leave').addEventListener('click', () => openReceipt());

// ── 소리 (첫 화면에서도) ─────────────────────────────────
const sound = {
  vol: store.get('vol', 80),
  music: store.get('musicVol', 70),
  fire: store.get('fireVol', store.get('fire', true) ? 70 : 0),
  muted: store.get('muted', false),
};

function applySound(save = true) {
  const a = game.audio;
  a.muted = sound.muted;
  a.setVolume(sound.vol / 100);
  a.ambienceOn = true;
  a.setAmbienceVolume(sound.fire / 100);
  if (a.master) a.setMuted(sound.muted);
  $('#vol-all').value = String(sound.vol);
  $('#vol-fire').value = String(sound.fire);
  $('#vol-music').value = String(sound.music);
  $('#vol-all-v').textContent = String(sound.vol);
  $('#vol-fire-v').textContent = String(sound.fire);
  $('#vol-music-v').textContent = String(sound.music);
  music.setVolume((sound.music / 100) * (sound.vol / 100), sound.muted);
  const off = sound.muted || sound.vol === 0;
  $('#btn-sound .ic').innerHTML = iconSVG(off ? 'mute' : 'speaker', 18);
  $('#btn-sound .lbl').textContent = off ? '소리 끔' : '소리';
  $('#btn-mute').setAttribute('aria-pressed', String(sound.muted));
  $('#btn-mute').textContent = sound.muted ? '소리 다시 켜기' : '음소거';
  if (save) {
    store.set('vol', sound.vol);
    store.set('fireVol', sound.fire);
    store.set('musicVol', sound.music);
    store.set('muted', sound.muted);
  }
}

function toggleSoundPanel(open = $('#sound-panel').hidden) {
  $('#sound-panel').hidden = !open;
  $('#btn-sound').setAttribute('aria-expanded', String(open));
}

$('#btn-sound').addEventListener('click', () => {
  // 처음 누를 때 소리를 깨운다 (브라우저는 사용자가 누르기 전엔 소리를 막는다)
  game.audio.unlock();
  applySound(false);
  toggleSoundPanel();
});
$('#vol-all').addEventListener('input', (e) => {
  game.audio.unlock();
  sound.vol = Number(e.target.value);
  if (sound.vol > 0) sound.muted = false;
  applySound();
});
$('#vol-music').addEventListener('input', (e) => {
  sound.music = Number(e.target.value);
  applySound();
});
$('#vol-fire').addEventListener('input', (e) => {
  game.audio.unlock();
  sound.fire = Number(e.target.value);
  applySound();
});
// 슬라이더를 놓으면 그 크기로 한 번 짠 (얼마나 큰지 들어 보게)
$('#vol-all').addEventListener('change', () => game.audio.clink?.({ material: 'glass', size: 4 }, { material: 'glass', size: 4 }, 0.5, 0));
$('#btn-mute').addEventListener('click', () => {
  game.audio.unlock();
  sound.muted = !sound.muted;
  applySound();
});
document.addEventListener('pointerdown', (e) => {
  if (!$('#sound-panel').hidden && !e.target.closest('#sound')) toggleSoundPanel(false);
});
applySound(false);

$('#btn-invite').addEventListener('click', async () => {
  const url = `${location.origin}/?room=${state.room}`;
  try {
    await navigator.clipboard.writeText(url);
    toast('초대 링크를 복사했어요.');
  } catch {
    toast(url);
  }
});

// 마시기/채우기 버튼 상태
setInterval(() => {
  updateTipsy();
  const me = game.me;
  if (!me) return;
  const empty = me.fill < 0.02;
  $('#btn-sip').disabled = empty || me.busy;
  $('#btn-refill').disabled = me.busy || me.fill > 0.97;
  $('#btn-refill').classList.toggle('warm', empty);
  $('#btn-refill').classList.toggle('pulse', empty && !me.busy);
}, 150);

// 아래 버튼 줄 높이 (알림이 그 위에 뜨게)
const barEl = document.querySelector('.bar');
if (window.ResizeObserver) {
  new ResizeObserver(() => {
    const h = barEl.offsetHeight ? Math.round(innerHeight - barEl.getBoundingClientRect().top) : 0;
    document.documentElement.style.setProperty('--bar-h', `${h}px`);
  }).observe(barEl);
}

// 취기 표시: 단계(0 말짱 … 4 만취), 칸 다섯 개, 가장자리 붉은 기운
const TIPSY = ['말짱', '기분 좋음', '알딸딸', '어질어질', '만취'];
let tipsyStage = -1;
function updateTipsy() {
  const d = game.drunk;
  const stage = d < 0.1 ? 0 : d < 0.3 ? 1 : d < 0.55 ? 2 : d < 0.8 ? 3 : 4;
  const cells = Math.min(5, Math.ceil(d / 0.2 - 0.001));
  const el = $('#tipsy');
  el.querySelectorAll('i').forEach((c, i) => c.classList.toggle('on', i < cells));
  if (stage !== tipsyStage) {
    // 더 취했을 때만 바텐더가 한마디
    if (tipsyStage >= 0 && stage > tipsyStage && state.mode === 'single') game.bartender.noticeGuest(stage);
    tipsyStage = stage;
    $('#tipsy-lbl').textContent = TIPSY[stage];
    el.className = `tipsy l${stage}`;
  }
  const fx = $('#drunk-fx');
  const on = state.mode !== 'title' ? Math.max(0, Math.min(1, (d - 0.25) / 0.8)) : 0;
  fx.style.opacity = on.toFixed(2);
  fx.classList.toggle('wavy', on > 0.3);
  $('#btn-remedy').classList.toggle('warm', stage >= 3 && game.remedyWait <= 0);
}

let toastTimer;
function toast(text, ms = 2400) {
  const t = $('#toast');
  t.textContent = text;
  t.classList.add('show');
  // 가게 창처럼 화면을 덮는 창이 열려 있으면 그 창 아래쪽 가장자리로 비켜 선다
  t.classList.toggle('over-sheet', !$('#venue-sheet').hidden);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

function updatePeople() {
  $('#people').textContent = `${game.remotes.size + 1}명 · ${TABLES[state.roomTable]?.name ?? ''}`;
}

// ── 혼자 / 같이 ──────────────────────────────────────────
function startSingle() {
  game.setVenue(state.venue);
  game.setBarkeep(barkeepFor(state.venue), false);
  game.startSingle(state.drink, state.glass, currentAnju(), variantFor(state.drink));
  showHud('single');
  anjuTip();
  musicTip();
}

// 처음 한 번 알려 주는 말: 창이 열려 있으면 (가게 창, 신청곡 창 …) 닫힐 때까지 미뤘다가 띄운다
const PANELS = ['#venue-sheet', '#music', '#picker', '#remedy-panel', '#sound-panel', '#receipt', '#seats'];
function panelOpen() {
  return PANELS.some((sel) => { const el = $(sel); return el && !el.hidden; });
}
function tip(key, delay, text) {
  if (store.get(key, false)) return;
  store.set(key, true);
  const show = () => {
    if (state.mode === 'title') return;
    if (panelOpen()) { setTimeout(show, 1200); return; }
    toast(text(), 3600);
  };
  setTimeout(show, delay);
}

function anjuTip() {
  tip('anjuTip', 4200, () => (state.mode === 'multi' ? '안주 접시를 누르면 한 입. 친구 접시를 누르면 뺏어 먹어요.' : '안주 접시를 누르면 한 입. 바텐더 몫도 몰래 집어 먹을 수 있어요.'));
}

async function startMulti(kind) {
  const code = state.joinCode;
  titleMsg('연결하는 중...');
  try {
    await ensureNet();
  } catch {
    titleMsg(ERRORS.connect);
    showTitle();
    return;
  }
  const names = new Map();
  net
    .on('joined', (msg) => {
      titleMsg('');
      state.room = msg.room;
      state.roomTable = msg.table;
      state.roomVenue = msg.venue || DEFAULT_VENUE;
      state.roomBarkeep = msg.barkeep || DEFAULT_BARKEEP;
      game.setVenue(state.roomVenue);
      game.setBarkeep(barkeepFor(state.roomVenue), false);
      music.useNet(net, msg.music, msg.now);
      $('#room-code').textContent = msg.room;
      game.startMulti(msg.you, msg.players, net, msg.table);
      for (const p of msg.players) names.set(p.id, p.name);
      showHud('multi');
      updatePeople();
      history.replaceState(null, '', `/?room=${msg.room}`);
      musicTip();
      if (kind === 'create') toast('방을 만들었어요. 초대 링크를 친구에게 보내 주세요.');
      else if (msg.moved) toast('고른 자리에 먼저 온 분이 있어서 다른 자리에 앉았어요.');
      anjuTip();
    })
    .on('error', (msg) => {
      titleMsg(ERRORS[msg.code] || ERRORS['no-room']);
      net?.close();
      net = null;
      showTitle();
    })
    .on('table', (msg) => {
      game.onNet(msg);
      state.roomTable = msg.table;
      // 바로 뒤에 오는 '들어왔어요'와 한 번에 알린다
      state.moved = msg.table;
      updatePeople();
    })
    .on('closed', () => {
      if (state.mode !== 'multi') return;
      toast('연결이 끊겼어요.');
      leave();
    })
    .on('venue', (msg) => {
      state.roomVenue = msg.v;
      if (msg.barkeep) state.roomBarkeep = msg.barkeep;
      game.setVenue(msg.v);
      game.setBarkeep(barkeepFor(msg.v), false);
      renderBarkeepCards();
      renderVenueCards();
      updateMusicButton();
      toast(`${msg.by} 님이 ${josa(venueById[msg.v].name, '으로')} 옮겼어요.`);
    })
    .on('barkeep', (msg) => {
      state.roomBarkeep = msg.id;
      game.setBarkeep(barkeepFor(game.venue), false);
      renderBarkeepCards();
      toast(`${msg.by} 님이 바텐더를 ${barkeepById[msg.id].name}${josa(barkeepById[msg.id].name, '으로').slice(barkeepById[msg.id].name.length)} 바꿨어요.`);
    })
    .on('music', (msg) => {
      music.setState(msg.m, msg.now);
      if (msg.by && msg.by !== myName()) {
        if (msg.a === 'add') toast(`${msg.by} 님이 신청곡을 넣었어요.`);
        else if (msg.a === 'next') toast(`${msg.by} 님이 다음 곡으로 넘겼어요.`);
        else if (msg.a === 'pause') toast(`${msg.by} 님이 노래를 잠깐 멈췄어요.`);
        else if (msg.a === 'play') toast(`${msg.by} 님이 노래를 다시 틀었어요.`);
      }
    })
    .on('music-err', (msg) => musicNote(MUSIC_ERR[msg.code] || MUSIC_ERR['bad-url']));
  for (const t of ['peer-join', 'peer-leave', 'pose', 'set', 'act', 'say', 'emo', 'clink']) {
    net.on(t, (msg) => {
      game.onNet(msg);
      if (t === 'peer-join') {
        names.set(msg.player.id, msg.player.name);
        if (state.moved) toast(`${msg.player.name} 님이 와서 다 같이 ${TABLES[state.moved].name}로 옮겨 앉았어요.`);
        else toast(`${msg.player.name} 님이 들어왔어요.`);
        state.moved = null;
        updatePeople();
      } else if (t === 'peer-leave') {
        toast(`${names.get(msg.id) ?? '누군가'} 님이 나갔어요.`);
        names.delete(msg.id);
        updatePeople();
      }
    });
  }
  net.send({ t: kind, room: code, table: state.table, venue: state.venue, barkeep: state.barkeeps[state.venue] ?? DEFAULT_BARKEEP, seat: state.seat, name: myName(), drink: state.drink, variant: variantFor(state.drink) ?? '', glass: state.glass, anju: currentAnju() });
}

function leave() {
  toggleEmo(false);
  const wasMulti = state.mode === 'multi';
  net?.close();
  net = null;
  closeMusic();
  closeVenueSheet();
  game.setLook(false);
  if (wasMulti) music.useLocal();
  game.leave();
  state.roomBarkeep = null;
  game.setVenue(state.venue);
  game.setBarkeep(barkeepFor(state.venue), false);
  state.roomVenue = null;
  showTitle();
  if (wasMulti) {
    history.replaceState(null, '', '/');
    setInvite(null);
  }
}

// ── 술 고르기 ────────────────────────────────────────────
let thumbs = null;
const queue = [];
let queueRunning = false;

function enqueue(canvas, drink, glass) {
  canvas.dataset.want = `${drink}|${glass}`;
  queue.push({ canvas, drink, glass });
  if (!queueRunning) { queueRunning = true; requestAnimationFrame(drainQueue); }
}

function enqueueAnju(canvas, id) {
  canvas.dataset.want = `anju|${id}`;
  queue.push({ canvas, anju: id });
  if (!queueRunning) { queueRunning = true; requestAnimationFrame(drainQueue); }
}

// 한 번 그린 미리보기는 기억해 두고 다시 그리지 않는다 (카드를 고를 때마다 목록 전체가 다시 만들어진다)
const thumbCache = new Map();
const THUMB_CACHE_MAX = 240;

function drawThumb(canvas, key, paint) {
  const ctx = canvas.getContext('2d');
  let hit = thumbCache.get(key);
  if (hit) {
    thumbCache.delete(key);
    thumbCache.set(key, hit);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(hit, 0, 0);
    return;
  }
  paint();
  hit = document.createElement('canvas');
  hit.width = canvas.width;
  hit.height = canvas.height;
  hit.getContext('2d').drawImage(canvas, 0, 0);
  thumbCache.set(key, hit);
  if (thumbCache.size > THUMB_CACHE_MAX) thumbCache.delete(thumbCache.keys().next().value);
}

function drainQueue() {
  thumbs ||= new ThumbRenderer(112);
  const start = performance.now();
  while (queue.length && performance.now() - start < 12) {
    const { canvas, drink, glass, anju } = queue.shift();
    const key = anju ? `anju|${anju}` : `${drink}|${glass}`;
    if (canvas.dataset.want !== key || canvas.dataset.have === canvas.dataset.want) continue;
    drawThumb(canvas, key, () => (anju ? thumbs.drawAnju(canvas, anju) : thumbs.draw(canvas, drink, glass)));
    canvas.dataset.have = canvas.dataset.want;
  }
  if (queue.length) requestAnimationFrame(drainQueue);
  else queueRunning = false;
}

function makeCard(label, onClick) {
  const b = document.createElement('button');
  b.className = 'card';
  b.type = 'button';
  const c = document.createElement('canvas');
  c.width = c.height = 112;
  const s = document.createElement('span');
  s.className = 'name';
  s.textContent = label;
  b.append(c, s);
  b.addEventListener('click', onClick);
  return b;
}

function renderCats() {
  const nav = $('#cats');
  nav.textContent = '';
  for (const cat of CATEGORIES) {
    const b = document.createElement('button');
    b.className = 'tab';
    b.type = 'button';
    b.textContent = cat.name;
    b.setAttribute('role', 'tab');
    b.setAttribute('aria-selected', String(cat.id === state.cat));
    b.addEventListener('click', () => { state.cat = cat.id; renderCats(); renderDrinks(); });
    nav.appendChild(b);
  }
}

function renderDrinks() {
  const grid = $('#drinks');
  grid.textContent = '';
  grid.scrollTop = 0;
  if (state.pending === 'anju') {
    for (const a of ANJU) {
      const card = makeCard(a.name, () => {
        state.pickAnju = a.id;
        renderDrinks();
        renderDetail();
      });
      card.setAttribute('aria-selected', String(a.id === state.pickAnju));
      if (a.id === pairAnju(drinkById[state.drink])) {
        const badge = document.createElement('span');
        badge.className = 'badge';
        badge.textContent = '찰떡';
        card.appendChild(badge);
      }
      grid.appendChild(card);
      enqueueAnju(card.querySelector('canvas'), a.id);
    }
    return;
  }
  for (const d of DRINKS.filter((x) => x.cat === state.cat)) {
    const card = makeCard(d.name, () => {
      state.pickDrink = d.id;
      state.pickGlass = d.glass;
      state.pickVariant = variantFor(d.id);
      renderDrinks();
      renderVariants();
      renderGlasses();
      renderDetail();
    });
    card.setAttribute('aria-selected', String(d.id === state.pickDrink));
    grid.appendChild(card);
    enqueue(card.querySelector('canvas'), d.id, d.glass);
  }
}

function renderGlasses() {
  const row = $('#glasses');
  const keep = row.scrollLeft;
  row.textContent = '';
  const rec = drinkById[state.pickDrink].glass;
  const list = GLASSES.filter((g) => !g.hidden);
  list.sort((a, b) => (b.id === rec) - (a.id === rec));
  for (const g of list) {
    const card = makeCard(g.name, () => {
      state.pickGlass = g.id;
      renderGlasses();
      renderDetail();
    });
    card.setAttribute('aria-selected', String(g.id === state.pickGlass));
    if (g.id === rec) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = '추천';
      card.appendChild(badge);
    }
    row.appendChild(card);
    enqueue(card.querySelector('canvas'), state.pickDrink, g.id);
  }
  row.scrollLeft = keep;
}

function renderDetail() {
  if (state.pending === 'anju') {
    const a = anjuById[state.pickAnju];
    $('#pick-name').textContent = a.name;
    $('#pick-desc').textContent = a.desc;
    return;
  }
  const d = drinkById[state.pickDrink], g = glassById[state.pickGlass];
  const v = variantOf(d.id, state.pickVariant);
  $('#pick-name').textContent = v ? `${v.name} · ${g.name}` : `${d.name} · ${g.name}`;
  $('#pick-desc').textContent = v ? `${d.name}. ${v.desc}` : d.desc;
}

// 같은 술의 실제 상품들 (카스·테라·켈리, 코카콜라·펩시 제로 라임 …)
function renderVariants() {
  const nav = $('#variants');
  nav.textContent = '';
  const d = drinkById[state.pickDrink];
  $('#picker').classList.toggle('no-variant', !d?.variants);
  if (!d?.variants) return;
  for (const v of d.variants) {
    const b = document.createElement('button');
    b.className = 'tab';
    b.type = 'button';
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(v.id === state.pickVariant));
    b.setAttribute('aria-selected', String(v.id === state.pickVariant));
    b.textContent = v.name;
    b.addEventListener('click', () => {
      state.pickVariant = v.id;
      renderVariants();
      renderDetail();
    });
    nav.appendChild(b);
  }
}

function openPicker(action) {
  game.setLook(false);
  hideSound(true);
  closeMusic();
  closeVenueSheet();
  state.pending = action;
  state.pickDrink = state.drink;
  state.pickGlass = state.glass;
  state.pickVariant = variantFor(state.drink);
  state.cat = drinkById[state.drink].cat;
  $('#picker').hidden = false;
  if (state.mode === 'title') $('#title').hidden = true;
  const anju = action === 'anju';
  game.picking = state.mode === 'title' ? false : anju ? 'anju' : true;
  $('#picker').classList.toggle('anju-mode', anju);
  $('#pick-title').textContent = anju ? '오늘의 안주' : '오늘의 한 잔';
  state.pickAnju = currentAnju();
  $('#btn-confirm').textContent = anju ? '이걸로 주세요' : action === 'single' ? '이걸로 혼자 짠' : action === 'create' ? '이걸로 방 만들기' : action === 'join' ? '이걸로 들어가기' : '이걸로 바꿀게요';
  if (!anju) {
    renderCats();
    renderVariants();
    renderGlasses();
  }
  renderDrinks();
  renderDetail();
}

function hideSound(h) {
  $('#sound').hidden = h;
  if (h) toggleSoundPanel(false);
}

function closePicker() {
  hideSound(false);
  $('#picker').hidden = true;
  game.picking = false;
  if (state.mode === 'title') $('#title').hidden = false;
}

$('#btn-close-pick').addEventListener('click', closePicker);
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (!$('#picker').hidden) closePicker();
  else if (!$('#seats').hidden) closeSeats();
});

$('#btn-confirm').addEventListener('click', () => {
  if (state.pending === 'anju') {
    state.anju = state.pickAnju;
    state.anjuChosen = true;
    store.set('anju', state.anju);
    store.set('anjuChosen', true);
    closePicker();
    game.setMyAnju(state.anju);
    return;
  }
  state.drink = state.pickDrink;
  state.glass = state.pickGlass;
  if (state.pickVariant) {
    state.variants[state.drink] = state.pickVariant;
    store.set('variants', state.variants);
  }
  store.set('drink', state.drink);
  store.set('glass', state.glass);
  const action = state.pending;
  closePicker();
  if (action === 'single') startSingle();
  else if (action === 'create' || action === 'join') startMulti(action);
  else if (action === 'change') {
    game.setMyDrink(state.drink, state.glass, variantFor(state.drink));
    // 안주를 따로 고른 적 없으면 술에 맞춰 바꿔 준다
    if (!state.anjuChosen) game.setMyAnju(pairAnju(drinkById[state.drink]));
  }
});

showTitle();

// ── 가게 ─────────────────────────────────────────────────
for (const [sel, icon] of [['#btn-venue .ic', 'shop'], ['#btn-music .ic', 'note']]) $(sel).innerHTML = iconSVG(icon, 18);

// 첫 화면: 가게 고르기 (뒤 배경이 바로 바뀐다)
function renderVenuePick() {
  const box = $('#venue-list');
  box.textContent = '';
  for (const v of VENUES) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'venue-chip';
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(v.id === state.venue));
    b.title = v.name;
    b.innerHTML = `${iconSVG('v-' + v.id, 24)}<span>${v.short}</span>`;
    b.addEventListener('click', () => {
      game.audio.unlock();
      state.venue = v.id;
      store.set('venue', v.id);
      game.setVenue(v.id);
      game.setBarkeep(barkeepFor(v.id), false);
      renderVenuePick();
    });
    box.appendChild(b);
  }
  const v = venueById[state.venue];
  $('#venue-desc').innerHTML = '';
  const name = document.createElement('b');
  name.textContent = v.name;
  $('#venue-desc').append(name, v.desc);
  renderBarkeepPick();
}

// 첫 화면: 바텐더 고르기 (이 가게에서 고를 수 있는 사람만, ★는 이 가게 전용)
function renderBarkeepPick() {
  const box = $('#barkeep-list');
  box.textContent = '';
  const cur = barkeepFor(state.venue);
  for (const k of barkeepsFor(state.venue)) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'bk-chip' + (k.venue ? ' only' : '');
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(k.id === cur));
    b.textContent = k.name;
    b.title = k.desc + (k.venue ? ` (${venueById[k.venue].name} 전용)` : '');
    b.addEventListener('click', () => pickBarkeep(k.id));
    box.appendChild(b);
  }
}

function pickBarkeep(id) {
  game.audio.unlock();
  const venue = game.venue ?? state.venue;
  if (state.mode === 'multi') {
    if (state.roomBarkeep === id) return;
    state.roomBarkeep = id;
    net?.send({ t: 'barkeep', id });
  } else {
    state.barkeeps[venue] = id;
    store.set('barkeeps', state.barkeeps);
  }
  game.setBarkeep(id);
  renderBarkeepPick();
  renderBarkeepCards();
  if (state.mode !== 'title') toast(`${barkeepById[id].name}${barkeepById[id].venue ? ' (가게 전용)' : ''} 님이 바에 섰어요.`);
}

// 게임 중: 가게와 바텐더 창의 바텐더 칸
function renderBarkeepCards() {
  const box = $('#barkeep-cards');
  if (!box) return;
  box.textContent = '';
  const venue = game.venue ?? state.venue;
  const cur = game.bartender.person;
  for (const k of barkeepsFor(venue)) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'barkeep-card' + (k.venue ? ' only' : '');
    b.setAttribute('aria-current', String(k.id === cur));
    const t = document.createElement('b');
    t.textContent = k.name;
    const d = document.createElement('small');
    d.textContent = k.desc;
    b.append(t, d);
    b.addEventListener('click', () => pickBarkeep(k.id));
    box.appendChild(b);
  }
  $('#barkeep-note').textContent = `★는 ${venueById[venue].name}에서만 만날 수 있어요.` + (state.mode === 'multi' ? ' 바꾸면 방 사람 모두에게 같이 바뀌어요.' : '');
}
renderVenuePick();

// 게임 중: 가게 옮기기
function renderVenueCards() {
  const box = $('#venue-cards');
  box.textContent = '';
  const cur = game.venue;
  for (const v of VENUES) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'venue-card';
    b.setAttribute('aria-current', String(v.id === cur));
    b.innerHTML = `${iconSVG('v-' + v.id, 32)}<b></b><small></small>`;
    b.querySelector('b').textContent = v.name;
    b.querySelector('small').textContent = `${v.desc} · ${v.device.name}`;
    b.addEventListener('click', () => changeVenue(v.id));
    box.appendChild(b);
  }
  $('#venue-note').textContent = state.mode === 'multi' ? '옮기면 방 사람 모두 같이 옮겨 가요. 노래는 이어서 나와요.' : '노래는 이어서 나와요.';
}

function changeVenue(id) {
  if (id === game.venue) { closeVenueSheet(); return; }
  if (state.mode === 'multi') {
    state.roomVenue = id;
    // 그 가게 전용 바텐더였으면 새 가게에선 기본 바텐더 (서버도 같이 맞춘다)
    if (!barkeepFits(state.roomBarkeep, id)) state.roomBarkeep = DEFAULT_BARKEEP;
    game.setVenue(id);
    net?.send({ t: 'venue', v: id });
  } else {
    state.venue = id;
    store.set('venue', id);
    game.setVenue(id);
    renderVenuePick();
    // 바텐더도 새 가게 인사
    game.bartenderSay(game.bartender.lines.hello[0]);
  }
  renderVenueCards();
  updateMusicButton();
  closeVenueSheet();
  toast(`${josa(venueById[id].name, '으로')} 옮겼어요.`);
}

function openVenueSheet() {
  closeMusic();
  remedyPanel.hidden = true;
  renderVenueCards();
  renderBarkeepCards();
  $('#venue-sheet').hidden = false;
  hideSound(true);
  $('#toast').classList.add('over-sheet');
}
function closeVenueSheet() {
  if ($('#venue-sheet').hidden) return;
  $('#venue-sheet').hidden = true;
  hideSound(false);
}
$('#btn-venue').addEventListener('click', () => ($('#venue-sheet').hidden ? openVenueSheet() : closeVenueSheet()));
$('#btn-close-venue').addEventListener('click', closeVenueSheet);

// ── 신청곡 ───────────────────────────────────────────────
const MUSIC_ERR = {
  'bad-url': '유튜브나 유튜브 뮤직의 곡 링크를 넣어 주세요.',
  playlist: '재생목록 말고 곡 하나의 링크를 넣어 주세요. (주소에 v= 가 있는 링크)',
  full: '신청곡이 꽉 찼어요. 몇 곡 듣고 다시 넣어 주세요.',
};

function openMusic() {
  if (state.mode === 'title') return;
  closeVenueSheet();
  remedyPanel.hidden = true;
  if (!$('#picker').hidden) closePicker();
  const d = game.venueInfo.device;
  $('#music-title').textContent = d.ask;
  $('#music').hidden = false;
  hideSound(true);
  game.musicView = true;
  $('#hint').classList.add('gone');
  musicNote('');
  renderMusic();
  placeScreen();
  // 링크를 바로 붙여 넣을 수 있게 (폰은 자판이 화면을 가리니 안 한다)
  if (matchMedia('(pointer: fine)').matches && !music.current) setTimeout(() => $('#music-url').focus(), 280);
}

function closeMusic() {
  if ($('#music').hidden) return;
  $('#music').hidden = true;
  game.musicView = false;
  hideSound(false);
  placeScreen();
}

$('#btn-music').addEventListener('click', () => {
  game.audio.unlock();
  if (music.blocked) { music.unblock(); return; }
  if ($('#music').hidden) openMusic();
  else closeMusic();
});
$('#btn-close-music').addEventListener('click', closeMusic);
$('#music-form').addEventListener('submit', (e) => {
  e.preventDefault();
  game.audio.unlock();
  const input = $('#music-url');
  const err = music.add(input.value, myName());
  if (err) { musicNote(MUSIC_ERR[err]); return; }
  input.value = '';
  musicNote(music.queue.length > 1 ? '다음 곡으로 넣었어요.' : '');
  // 누른 순간이 소리를 틀어도 되는 순간이다
  music.unblock();
});
$('#btn-music-play').addEventListener('click', () => music.togglePause());
$('#btn-music-next').addEventListener('click', () => music.next());
$('#btn-music-unblock').addEventListener('click', () => music.unblock());
$('#music-mini').checked = store.get('musicMini', false);
$('#music-mini').addEventListener('change', (e) => { store.set('musicMini', e.target.checked); placeScreen(); });

function musicNote(text) {
  $('#music-note').textContent = text;
}

function musicError(code, item) {
  if (code === 'api') { musicNote('유튜브에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.'); return; }
  const name = item?.title || '이 영상';
  toast(code === 'embed' ? `${name}: 다른 곳에서 틀 수 없게 막힌 영상이라 넘겼어요.` : `${name}: 영상을 찾을 수 없어서 넘겼어요.`);
}

const fmtTime = (s) => (s > 0 ? `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}` : '');

function renderMusic() {
  const cur = music.current;
  $('#music-empty').hidden = !!cur;
  $('#music-now').textContent = cur ? cur.title || '제목 불러오는 중…' : '아직 조용해요';
  $('#btn-music-play').disabled = !cur;
  $('#btn-music-next').disabled = !cur;
  $('#btn-music-play').textContent = cur && music.paused ? '다시 틀기' : '일시정지';
  $('#btn-music-unblock').hidden = !music.blocked;
  const list = $('#music-queue');
  list.textContent = '';
  const rest = music.queue.slice(1);
  $('#music-queue-h').hidden = !rest.length;
  for (const it of rest) {
    const li = document.createElement('li');
    const t = document.createElement('span');
    t.className = 't';
    t.textContent = it.title || '제목 불러오는 중…';
    const by = document.createElement('small');
    by.textContent = it.by ? `${it.by} 님` : '';
    const x = document.createElement('button');
    x.type = 'button';
    x.textContent = '빼기';
    x.setAttribute('aria-label', `${t.textContent} 빼기`);
    x.addEventListener('click', () => music.remove(it.key));
    li.append(t, by, x);
    list.appendChild(li);
  }
  renderMusicTime();
  updateMusicButton();
  placeScreen();
}

function renderMusicTime() {
  const cur = music.current;
  const pos = cur ? music.position : 0, dur = cur ? music.duration : 0;
  $('#music-prog').style.width = dur ? `${Math.min(100, (pos / dur) * 100).toFixed(1)}%` : '0%';
  const parts = [];
  if (cur?.author) parts.push(cur.author);
  if (cur?.by) parts.push(`${cur.by} 님 신청`);
  if (dur) parts.push(`${fmtTime(pos)} / ${fmtTime(dur)}`);
  else if (cur && !music.paused && !music.playing) parts.push(music.blocked ? '소리가 막혔어요' : '불러오는 중…');
  if (cur && music.paused) parts.push('멈춤');
  $('#music-sub').textContent = parts.join(' · ');
  updateMusicButton();
}

// 위 음악 버튼: 지금 곡 이름, 소리가 막혔으면 눌러 달라고
function updateMusicButton() {
  const cur = music.current;
  const b = $('#btn-music');
  b.classList.toggle('on', music.playing);
  b.classList.toggle('ask', music.blocked);
  $('#np-text').textContent = music.blocked ? '눌러서 듣기' : cur ? cur.title || '♪' : '음악';
  b.title = `${game.venueInfo.device.name}: 신청곡`;
}

// 유튜브 화면 자리: 창이 열려 있으면 창 안에, 아니면 작은 화면이나 거의 안 보이게
let screenRaf = 0;
function placeScreen() {
  const wrap = $('#yt-wrap');
  const open = !$('#music').hidden && !!music.current;
  cancelAnimationFrame(screenRaf);
  wrap.classList.remove('mini', 'off');
  wrap.style.cssText = '';
  if (open) {
    // 창이 미끄러져 들어오는 동안에도 따라가게 매 프레임 맞춘다
    const follow = () => {
      if ($('#music').hidden) return;
      const r = $('#music-screen').getBoundingClientRect();
      wrap.style.cssText = `left:${r.left}px;top:${r.top}px;width:${r.width}px;height:${r.height}px`;
      screenRaf = requestAnimationFrame(follow);
    };
    follow();
  } else if (music.current && $('#music-mini').checked && state.mode !== 'title') {
    wrap.classList.add('mini');
  } else {
    wrap.classList.add('off');
  }
}

// 처음 들어오면 노래 기물을 알려 준다
function musicTip() {
  tip('deviceTip', 9000, () => `${josa(game.venueInfo.device.name, '을/를')} 누르면 같이 들을 노래를 틀 수 있어요.`);
}

// ── 둘러보기 ─────────────────────────────────────────────
$('#btn-look .ic').innerHTML = iconSVG('eye', 18);
$('#btn-look').addEventListener('click', () => {
  game.audio.unlock();
  if (!game.lookMode) { closeMusic(); closeVenueSheet(); remedyPanel.hidden = true; }
  game.setLook(!game.lookMode);
});
$('#btn-look-done').addEventListener('click', () => game.setLook(false));

// ── 계산하고 나가기: 영수증 ────────────────────────────────
const receiptCanvas = $('#receipt-canvas');
let receiptStamp = '';

async function openReceipt() {
  if (state.mode === 'title') return;
  game.setLook(false);
  closeMusic();
  closeVenueSheet();
  remedyPanel.hidden = true;
  if (!$('#picker').hidden) closePicker();
  const tab = game.tab, now = new Date();
  const tableName = state.mode === 'multi' ? `${TABLES[state.roomTable]?.short ?? ''} ${(game.mySeat ?? 0) + 1}번` : '바 자리';
  try { await document.fonts?.load('11px Galmuri11'); } catch { /* 글꼴이 없어도 그린다 */ }
  drawReceipt(receiptCanvas, { tab, host: game.hostName, tableName, now, minutes: Math.round((Date.now() - tab.start) / 60000) });
  const pad = (n) => String(n).padStart(2, '0');
  receiptStamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
  const box = $('#receipt');
  box.classList.remove('printing');
  box.hidden = false;
  void box.offsetWidth;
  box.classList.add('printing');
  $('#receipt .receipt-slot').scrollTop = 0;
  if (state.mode === 'single') game.bartenderSay(tab.venue === 'attic' ? '돈은 됐고, 다음엔 네가 사!' : tab.total ? '계산 도와드릴게요. 감사합니다!' : '오늘은 제가 살게요!');
  $('#btn-receipt-share').hidden = !canShareFiles();
  $('#btn-receipt-leave').focus();
}

function closeReceipt() {
  $('#receipt').hidden = true;
}

function canShareFiles() {
  try { return !!navigator.canShare?.({ files: [new File([''], 'x.png', { type: 'image/png' })] }); } catch { return false; }
}

function receiptBlob() {
  return new Promise((resolve) => receiptCanvas.toBlob(resolve, 'image/png'));
}

$('#btn-receipt-save').addEventListener('click', async () => {
  const blob = await receiptBlob();
  if (!blob) return;
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `짠-영수증-${receiptStamp}.png`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
  toast('영수증을 저장했어요.');
});
$('#btn-receipt-share').addEventListener('click', async () => {
  const blob = await receiptBlob();
  if (!blob) return;
  const file = new File([blob], `짠-영수증-${receiptStamp}.png`, { type: 'image/png' });
  try { await navigator.share({ files: [file], title: '오늘의 짠 영수증' }); } catch { /* 공유 창을 닫았다 */ }
});
$('#btn-receipt-stay').addEventListener('click', () => {
  closeReceipt();
  if (state.mode === 'single') game.bartenderSay('그럼 한 잔 더!');
});
$('#btn-receipt-leave').addEventListener('click', () => {
  closeReceipt();
  leave();
});
$('#receipt').addEventListener('click', (e) => { if (e.target.id === 'receipt') closeReceipt(); });
addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('#receipt').hidden) closeReceipt(); });

// ── 이모지 반응 ─────────────────────────────────────────
// 쟁반: 최근에 쓴 8개 + "전부" 버튼. 전부 열면 묶음별 이모지 전체, 찾기, 직접 입력, 피부색
const emoTray = $('#emo-tray');
const emoPicker = $('#emo-picker');
const emoGrid = $('#emo-grid');
const emoSearch = $('#emo-search');
let emoIdle = null;
let emoTone = Math.max(0, Math.min(5, store.get('emoTone', 0) | 0));
let emoRecent = (store.get('emoRecent', []) || []).map(cleanEmoji).filter(Boolean).slice(0, 32);

function recentEmojis(n) {
  const out = [...emoRecent];
  for (const e of QUICK_EMOJI) if (out.length < n && !out.includes(e)) out.push(e);
  return out.slice(0, n);
}

function sendEmoji(e) {
  e = cleanEmoji(e);
  if (!e) return false;
  if (!game.react(e)) { toast('조금 천천히 눌러 주세요.'); return false; }
  emoRecent = [e, ...emoRecent.filter((x) => x !== e)].slice(0, 32);
  store.set('emoRecent', emoRecent);
  return true;
}

function renderTray() {
  emoTray.textContent = '';
  for (const e of recentEmojis(8)) {
    const b = document.createElement('button');
    b.textContent = e;
    b.setAttribute('aria-label', `${e} 반응`);
    b.addEventListener('click', () => { sendEmoji(e); armEmoIdle(); });
    emoTray.appendChild(b);
  }
  const more = document.createElement('button');
  more.className = 'emo-more';
  more.textContent = '전부';
  more.title = '이모지 전부 보기';
  more.addEventListener('click', () => openEmoPicker());
  emoTray.appendChild(more);
}

function armEmoIdle() {
  clearTimeout(emoIdle);
  emoIdle = setTimeout(() => toggleEmo(false), 6000);
}
function toggleEmo(on) {
  const tray = $('#emo-tray');
  if (on === undefined) on = tray.hidden && $('#emo-picker').hidden;
  if (state.mode === 'title') on = false;
  if (on) renderTray();
  tray.hidden = !on;
  if (!on) closeEmoPicker();
  document.body.classList.toggle('emo-open', on);
  $('#btn-emo')?.setAttribute('aria-expanded', String(on));
  if (on) armEmoIdle(); else clearTimeout(emoIdle);
}

// ── 전부 ──
const GROUP_ICONS = ['😀', '👋', '🐶', '🍔', '✈️', '⚽', '💡', '❤️', '🏁'];
let emoData = null; // { list: [{ e, g, tone, name, kw, en }], groups }
let emoLoading = null;

function loadEmojiData() {
  emoLoading ??= import('./emoji-data.js').then(({ EMOJI_DATA, GROUP_NAMES }) => {
    const list = EMOJI_DATA.split('\n').map((line) => {
      const [e, g, tone, name, kw, en] = line.split('\t');
      return { e, g: +g, tone: tone === '1', name, kw, en, hay: `${name} ${kw} ${en}`.toLowerCase() };
    });
    emoData = { list, groups: GROUP_NAMES, byE: new Map(list.map((x) => [x.e, x])) };
    return emoData;
  });
  return emoLoading;
}

const shown = (x) => (x.tone ? withTone(x.e, emoTone) : x.e);
const esc = (t) => t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
function cell(e, label) {
  return `<button data-e="${esc(e)}" title="${esc(label)}" aria-label="${esc(label)}">${e}</button>`;
}
function emoName(e) {
  if (!emoData) return '';
  const x = emoData.byE.get(e) ?? emoData.byE.get(e.replace(/[\u{1F3FB}-\u{1F3FF}]/gu, ''));
  return x?.name ?? '';
}

function renderEmoTabs() {
  const tabs = $('#emo-tabs');
  tabs.textContent = '';
  const add = (id, icon, label) => {
    const b = document.createElement('button');
    b.textContent = icon;
    b.title = label;
    b.setAttribute('aria-label', label);
    b.dataset.sec = id;
    b.addEventListener('click', () => {
      if (emoSearch.value) { emoSearch.value = ''; renderEmoGrid(); }
      const sec = emoGrid.querySelector(`[data-sec="${id}"]`);
      if (sec) emoGrid.scrollTop = sec.offsetTop - emoGrid.offsetTop;
      markTab(id);
    });
    tabs.appendChild(b);
  };
  add('recent', '🕘', '최근');
  emoData.groups.forEach((name, i) => add(`g${i}`, GROUP_ICONS[i] ?? '•', name));
}
function markTab(id) {
  for (const b of $('#emo-tabs').children) b.setAttribute('aria-current', String(b.dataset.sec === id));
}

function renderEmoGrid() {
  if (!emoData) return;
  const q = emoSearch.value.trim();
  let html = '';
  if (q) {
    // 직접 입력: 찾기 칸에 이모지를 넣으면 (휴대폰 이모지 자판, 붙여 넣기) 그대로 보낼 수 있다
    const typed = findEmojis(q);
    if (typed.length) html += `<section data-sec="typed"><h4>입력한 이모지</h4><div class="emo-cells">${typed.map((e) => cell(e, emoName(e) || e)).join('')}</div></section>`;
    const words = q.toLowerCase().replace(/\p{Extended_Pictographic}|\p{Emoji_Modifier}|\u200D|\uFE0F/gu, ' ').split(/\s+/).filter(Boolean);
    if (words.length) {
      const hits = emoData.list.filter((x) => words.every((w) => x.hay.includes(w)));
      // 이름이 딱 맞는 것을 앞으로
      hits.sort((a, b) => (b.name.includes(words[0]) ? 1 : 0) - (a.name.includes(words[0]) ? 1 : 0));
      html += hits.length
        ? `<section data-sec="hits"><h4>찾은 이모지 ${hits.length}개</h4><div class="emo-cells">${hits.slice(0, 240).map((x) => cell(shown(x), x.name)).join('')}</div></section>`
        : (typed.length ? '' : `<p class="emo-empty">"${esc(q)}"에 맞는 이모지가 없어요.<br>휴대폰 이모지 자판으로 직접 넣어도 돼요.</p>`);
    }
  } else {
    html += `<section data-sec="recent"><h4>최근</h4><div class="emo-cells">${recentEmojis(16).map((e) => cell(e, emoName(e) || e)).join('')}</div></section>`;
    emoData.groups.forEach((name, gi) => {
      const items = emoData.list.filter((x) => x.g === gi);
      html += `<section data-sec="g${gi}"><h4>${esc(name)}</h4><div class="emo-cells">${items.map((x) => cell(shown(x), x.name)).join('')}</div></section>`;
    });
  }
  emoGrid.innerHTML = html;
  emoGrid.scrollTop = 0;
  markTab(q ? '' : 'recent');
}

function renderTones() {
  const box = $('#emo-tones');
  box.textContent = '';
  TONES.forEach((m, i) => {
    const b = document.createElement('button');
    b.textContent = withTone('✋', i);
    b.setAttribute('role', 'radio');
    b.setAttribute('aria-checked', String(i === emoTone));
    b.setAttribute('aria-label', ['기본', '밝은 피부', '조금 밝은 피부', '중간 피부', '조금 어두운 피부', '어두운 피부'][i]);
    b.addEventListener('click', () => {
      emoTone = i;
      store.set('emoTone', i);
      $('#btn-emo-tone').textContent = withTone('✋', i);
      box.hidden = true;
      $('#btn-emo-tone').setAttribute('aria-expanded', 'false');
      renderTones();
      renderEmoGrid();
    });
    box.appendChild(b);
  });
}

async function openEmoPicker() {
  if (state.mode === 'title') return;
  clearTimeout(emoIdle);
  $('#emo-tray').hidden = true;
  emoPicker.hidden = false;
  document.body.classList.add('emo-open', 'emo-picking');
  $('#btn-emo')?.setAttribute('aria-expanded', 'true');
  $('#btn-emo-tone').textContent = withTone('✋', emoTone);
  const first = !emoData;
  await loadEmojiData();
  if (emoPicker.hidden) return;
  if (first) { renderEmoTabs(); renderTones(); }
  emoSearch.value = '';
  renderEmoGrid();
}
function closeEmoPicker() {
  if (emoPicker.hidden) return;
  emoPicker.hidden = true;
  emoSearch.blur();
  $('#emo-tones').hidden = true;
  document.body.classList.remove('emo-picking');
  if ($('#emo-tray').hidden) {
    document.body.classList.remove('emo-open');
    $('#btn-emo')?.setAttribute('aria-expanded', 'false');
  }
}

emoGrid.addEventListener('click', (e) => {
  const b = e.target.closest('button[data-e]');
  if (!b) return;
  if (sendEmoji(b.dataset.e)) $('#emo-foot').innerHTML = `<b>${b.dataset.e}</b>${esc(b.title)} 보냈어요`;
});
emoGrid.addEventListener('pointerover', (e) => {
  const b = e.target.closest?.('button[data-e]');
  if (b && e.pointerType === 'mouse') $('#emo-foot').innerHTML = `<b>${b.dataset.e}</b>${esc(b.title)}`;
});
// 내리면 지금 보이는 묶음 탭에 불
emoGrid.addEventListener('scroll', () => {
  if (emoSearch.value) return;
  let cur = 'recent';
  for (const sec of emoGrid.querySelectorAll('section')) if (sec.offsetTop - emoGrid.offsetTop <= emoGrid.scrollTop + 8) cur = sec.dataset.sec;
  markTab(cur);
}, { passive: true });
let searchT = null;
emoSearch.addEventListener('input', () => { clearTimeout(searchT); searchT = setTimeout(renderEmoGrid, 120); });
emoSearch.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    e.preventDefault();
    renderEmoGrid();
    const b = emoGrid.querySelector('button[data-e]');
    if (b && sendEmoji(b.dataset.e)) $('#emo-foot').innerHTML = `<b>${b.dataset.e}</b>${esc(b.title)} 보냈어요`;
  } else if (e.key === 'Escape') closeEmoPicker();
  e.stopPropagation();
});
$('#btn-emo-close').addEventListener('click', () => closeEmoPicker());
$('#btn-emo-tone').addEventListener('click', () => {
  const box = $('#emo-tones');
  box.hidden = !box.hidden;
  $('#btn-emo-tone').setAttribute('aria-expanded', String(!box.hidden));
});
addEventListener('keydown', (e) => { if (e.key === 'Escape' && !emoPicker.hidden) closeEmoPicker(); });

// 쟁반이나 고르기 창 밖을 누르면 닫는다
addEventListener('pointerdown', (e) => {
  if (emoTray.hidden && emoPicker.hidden) return;
  if (emoTray.contains(e.target) || emoPicker.contains(e.target) || e.target.closest?.('#btn-emo')) return;
  toggleEmo(false);
}, true);
