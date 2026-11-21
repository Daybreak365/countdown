import * as THREE from 'three';
import { BoxBuilder, hash3 } from './voxel.js';
import { ROUND, BIGROUND, SQUARE, LONG, USHAPE, TABLES, chairPose } from './tables.js';
import { venueById, DEFAULT_VENUE } from './venues.js';
import { makeDevice } from './music3d.js';
import { addDecor } from './decor.js';

export const TABLE_Y = 75;

const _tint = new THREE.Color();

// 바 상판 범위 (모든 가게가 같은 자리에 바가 있다: 바텐더와 잔이 움직이는 길이 같게)
export const BAR = { x0: -96, x1: 96, z0: -168, z1: -126 };

export const LAYOUT = {
  single: {
    home: new THREE.Vector3(0, TABLE_Y, -140),
    // 바 상판 안쪽 끝 (바텐더 쪽)
    backZ: -174,
    bounds: { x0: -60, x1: 60, z1: -131 },
    bartender: new THREE.Vector3(0, 0, -180),
    anju: new THREE.Vector3(24, TABLE_Y, -134),
  },
  multi: {
    tableR: ROUND.r,
    seatR: ROUND.home,
    reach: ROUND.reach,
    bigReach: BIGROUND.reach,
  },
};

// 방 크기: 벽 안쪽 면
const W = { x0: -240, x1: 240, z0: -200, z1: 220, h: 260 };
// 노래 기물 자리: 오른쪽 앞 구석, 방 가운데를 보게
const DEVICE_AT = { x: 176, z: 158, yaw: Math.atan2(-176, -158) };

const pick = (list, i) => list[((i % list.length) + list.length) % list.length];

// 계단식 원판
function disc(b, cx, y, cz, r, h, step, colorAt) {
  for (let z = -r; z < r; z += step) {
    for (let x = -r; x < r; x += step) {
      const d = Math.hypot(x + step / 2, z + step / 2);
      if (d >= r) continue;
      const c = colorAt(x, z, d);
      if (c == null) continue;
      b.box(cx + x, y, cz + z, step, h, step, c, 0.03);
    }
  }
}

// 벽에 붙이는 상자. side: back(z=-200) front(z=220) left(x=-240) right(x=240), u: 벽을 따라가는 좌표, depth: 방 안쪽으로 튀어나온 두께
function put(b, side, u, y, w, h, depth, color, j = 0.03, off = 0) {
  switch (side) {
    case 'back': b.box(u, y, W.z0 + off, w, h, depth, color, j); break;
    case 'front': b.box(u, y, W.z1 - depth - off, w, h, depth, color, j); break;
    case 'left': b.box(W.x0 + off, y, u, depth, h, w, color, j); break;
    case 'right': b.box(W.x1 - depth - off, y, u, depth, h, w, color, j); break;
  }
}
const SPAN = { back: [W.x0, W.x1], front: [W.x0, W.x1], left: [W.z0, W.z1], right: [W.z0, W.z1] };

// 바깥 껍데기 (틈새로 하늘이 보이지 않게)
function outerShell(b, color) {
  b.box(-248, 0, -208, 496, 268, 8, color, 0);
  b.box(-248, 0, 220, 496, 268, 8, color, 0);
  b.box(-248, 0, -208, 8, 268, 436, color, 0);
  b.box(240, 0, -208, 8, 268, 436, color, 0);
  b.box(-250, -6, -210, 500, 2, 440, 0x1c140f, 0);
}

// 판자 바닥
function plankFloor(b, colors, { w = 18, min = 70, var: v = 70 } = {}) {
  for (let x = W.x0, i = 0; x < W.x1; x += w, i++) {
    let z = W.z0 - (i % 3) * 37;
    while (z < W.z1) {
      const len = min + hash3(i, Math.round(z), 3) * v;
      const z0 = Math.max(W.z0, z), z1 = Math.min(W.z1, z + len);
      if (z1 > z0) b.box(x + 0.25, -4, z0 + 0.25, w - 0.5, 4, z1 - z0 - 0.5, colors[((i + Math.floor(z / 50)) & 3) % colors.length], 0.06);
      z += len;
    }
  }
}

// 타일 바닥
function tileFloor(b, size, colorAt, grout) {
  b.box(W.x0, -4.4, W.z0, W.x1 - W.x0, 4, W.z1 - W.z0, grout, 0);
  for (let x = W.x0, i = 0; x < W.x1; x += size, i++) {
    for (let z = W.z0, k = 0; z < W.z1; z += size, k++) {
      b.box(x + 0.6, -4, z + 0.6, Math.min(size, W.x1 - x) - 1.2, 4, Math.min(size, W.z1 - z) - 1.2, colorAt(i, k), 0.05);
    }
  }
}

// "짠" 픽셀 글자 (분필)
const JJAN = [
  '###.###.#.',
  '.#...#..#.',
  '#.#.#.#.##',
  '........#.',
  '.#......#.',
  '.#........',
  '.#######..',
];
// 픽셀 글자 몇 개 (네온 간판)
const GLYPH = {
  H: ['#..#', '#..#', '####', '#..#', '#..#'],
  O: ['.##.', '#..#', '#..#', '#..#', '.##.'],
  F: ['####', '#...', '###.', '#...', '#...'],
  P: ['###.', '#..#', '###.', '#...', '#...'],
  U: ['#..#', '#..#', '#..#', '#..#', '.##.'],
  B: ['###.', '#..#', '###.', '#..#', '###.'],
  W: ['#...#', '#...#', '#.#.#', '#.#.#', '.#.#.'],
  I: ['###', '.#.', '.#.', '.#.', '###'],
  N: ['#..#', '##.#', '#.##', '#..#', '#..#'],
  E: ['####', '#...', '###.', '#...', '####'],
  R: ['###.', '#..#', '###.', '#.#.', '#..#'],
};
// 방 안에서 봤을 때 왼쪽에서 오른쪽으로 읽히게: 앞벽과 왼벽은 벽 좌표가 줄어드는 쪽으로 쓴다 (u0은 글의 왼쪽 끝)
function pixelText(b, side, text, u0, y0, px, color, depth = 0.6, off = 1) {
  const dir = side === 'left' || side === 'front' ? -1 : 1;
  let u = 0;
  for (const ch of text) {
    const g = GLYPH[ch];
    if (!g) { u += px * 3; continue; }
    g.forEach((row, j) => {
      [...row].forEach((c, i) => {
        if (c !== '#') return;
        const a = u + i * px;
        put(b, side, dir > 0 ? u0 + a : u0 - a - px, y0 + (g.length - 1 - j) * px, px, px, depth, color, 0, off);
      });
    });
    u += (g[0].length + 1) * px;
  }
}

// ── 글자 간판 ───────────────────────────────────────────
// 캔버스에 갈무리(픽셀 글꼴)로 써서 판에 붙인다. 글꼴이 늦게 불러와지면 다시 그린다.
const FONT = 'Galmuri11';
function txt(g, text, x, y, color, size = 12, align = 'center') {
  g.font = `${size}px ${FONT}, sans-serif`;
  g.textAlign = align;
  g.textBaseline = 'middle';
  g.fillStyle = color;
  g.fillText(text, Math.round(x), Math.round(y));
}
// 네온: 글자 둘레에 번지는 빛
function neon(g, text, x, y, color, size = 12) {
  g.save();
  g.shadowColor = color;
  g.shadowBlur = 5;
  for (let i = 0; i < 3; i++) txt(g, text, x, y, color, size);
  g.restore();
  txt(g, text, x, y, '#fff6ee', size);
}

// 포장마차: 세로로 써서 줄줄이 붙인 차림표
const POCHA_MENU = [['꼼장어', '#f3d24a'], ['닭발', '#f6f1e0'], ['오돌뼈', '#f3d24a'], ['계란말이', '#f6f1e0'], ['어묵탕', '#f4b8a8'], ['우동', '#f6f1e0'], ['라면', '#f3d24a'], ['소주', '#f6f1e0'], ['맥주', '#f4b8a8']];
function pochaStrip(name, paper, i) {
  return (g, w, h) => {
    g.fillStyle = paper;
    g.fillRect(0, 0, w, h);
    g.fillStyle = 'rgba(0,0,0,0.12)';
    g.fillRect(0, h - 2, w, 2);
    const chars = [...name];
    const step = Math.min(13, (h - 18) / chars.length);
    chars.forEach((c, k) => txt(g, c, w / 2, 9 + k * step, i % 3 === 1 ? '#2a2420' : '#c0392b', 12));
    // 아래 값: 손글씨 숫자
    txt(g, ['15', '13', '12', '9', '12', '6', '5', '5', '5'][i] ?? '', w / 2, h - 7, '#2a2420', 8);
  };
}

// 호프집 텔레비전: 축구 중계
function soccer(g, w, h, t) {
  g.fillStyle = '#2f7a3a';
  g.fillRect(0, 0, w, h);
  for (let x = 0; x < w; x += 8) { g.fillStyle = (x / 8) % 2 ? '#2f7a3a' : '#2a7034'; g.fillRect(x, 5, 8, h - 5); }
  g.fillStyle = '#d8eed8';
  g.fillRect(2, 6, w - 4, 1); g.fillRect(2, h - 2, w - 4, 1); g.fillRect(2, 6, 1, h - 8); g.fillRect(w - 3, 6, 1, h - 8);
  g.fillRect(w / 2, 6, 1, h - 8);
  g.fillRect(2, 13, 6, 1); g.fillRect(2, h - 9, 6, 1); g.fillRect(8, 13, 1, h - 21);
  g.fillRect(w - 9, 13, 6, 1); g.fillRect(w - 9, h - 9, 6, 1); g.fillRect(w - 9, 13, 1, h - 21);
  const bx = w / 2 + Math.sin(t * 0.9) * (w * 0.35), by = 6 + (h - 8) / 2 + Math.sin(t * 1.7) * (h * 0.25);
  for (let i = 0; i < 10; i++) {
    const home = i < 5;
    const px = bx + Math.sin(t * (0.6 + i * 0.13) + i) * 14 + (home ? -6 : 6);
    const py = by + Math.cos(t * (0.8 + i * 0.11) + i * 2) * 8;
    g.fillStyle = home ? '#e04848' : '#3a6ee0';
    g.fillRect(Math.round(px), Math.round(Math.max(7, Math.min(h - 3, py))), 2, 2);
  }
  g.fillStyle = '#ffffff';
  g.fillRect(Math.round(bx), Math.round(by), 1, 1);
  // 점수판
  g.fillStyle = '#101418';
  g.fillRect(0, 0, w, 5);
  g.fillStyle = '#e04848'; g.fillRect(2, 1, 3, 3);
  g.fillStyle = '#3a6ee0'; g.fillRect(w - 5, 1, 3, 3);
  g.fillStyle = '#ffe9a0';
  const goals = Math.floor(t / 47) % 4;
  g.fillRect(w / 2 - 5, 1, 2, 3); if (goals) g.fillRect(w / 2 - 5, 1, 2, 3);
  g.fillRect(w / 2 - 1, 2, 2, 1);
  g.fillRect(w / 2 + 3, 1, 2, 3);
}

// 가게마다 다른 바 겉모습
function buildBar(b, glow, S) {
  const T = TABLE_Y;
  const { x0, x1, z0, z1 } = BAR;
  b.box(x0, T - 5, z0, x1 - x0, 4.4, z1 - z0, S.topBase, 0.02);
  for (let x = x0, i = 0; x < x1; x += S.topStep, i++) {
    b.box(x, T - 0.6, z0, Math.min(S.topStep, x1 - x), 0.6, z1 - z0, pick(S.top, i), S.topJitter ?? 0.03);
  }
  if (S.veins) {
    // 대리석 결
    for (let i = 0; i < 26; i++) {
      const x = x0 + 4 + hash3(i, 1, 21) * (x1 - x0 - 30), z = z0 + 2 + hash3(i, 2, 21) * (z1 - z0 - 6);
      b.box(x, T - 0.55, z, 6 + hash3(i, 3, 21) * 18, 0.6, 0.6, S.veins, 0.02);
    }
  }
  b.box(x0 - 1.5, T - 6, z1 - 1, x1 - x0 + 3, 6.2, 3, S.trim, 0.02);
  b.box(x0 - 1.5, T - 6, z0 - 1, 3, 6.2, z1 - z0 + 1, S.trim, 0.02);
  b.box(x1 - 1.5, T - 6, z0 - 1, 3, 6.2, z1 - z0 + 1, S.trim, 0.02);
  // 앞판
  if (S.frontKind === 'pallet') {
    for (let y = 0, i = 0; y < T - 7; y += 9, i++) b.box(x0, y + 0.6, z1 - 4, x1 - x0, 7.8, 3, pick(S.front, i), 0.06);
    for (const x of [x0 + 2, -2, x1 - 8]) b.box(x, 0, z1 - 1.5, 6, T - 6, 2, S.trim, 0.04);
  } else if (S.frontKind === 'steel') {
    for (let x = x0, i = 0; x < x1; x += 32, i++) b.box(x + 0.3, 0, z1 - 4, Math.min(32, x1 - x) - 0.6, T - 6, 3, pick(S.front, i), 0.015);
    b.box(x0, T - 16, z1 - 1.2, x1 - x0, 5, 1.4, S.band, 0.02);
  } else if (S.frontKind === 'logs') {
    for (let y = 0, i = 0; y < T - 7; y += 8, i++) {
      b.box(x0, y + 1, z1 - 4, x1 - x0, 6.6, 3.4, pick(S.front, i), 0.05);
      b.box(x0, y + 2, z1 - 0.8, x1 - x0, 4.6, 1, pick(S.front, i + 1), 0.05);
    }
  } else {
    for (let x = x0, i = 0; x < x1; x += 8, i++) b.box(x, 0, z1 - 4, Math.min(8, x1 - x), T - 6, 3, pick(S.front, i), 0.04);
    if (S.molding) {
      for (let x = x0 + 5; x + 26 <= x1 - 3; x += 32) {
        const y = 16, w = 26, h = 40;
        b.box(x, y, z1 - 1, w, 2, 1.4, S.molding, 0.02);
        b.box(x, y + h - 2, z1 - 1, w, 2, 1.4, S.molding, 0.02);
        b.box(x, y, z1 - 1, 2, h, 1.4, S.molding, 0.02);
        b.box(x + w - 2, y, z1 - 1, 2, h, 1.4, S.molding, 0.02);
      }
    }
  }
  b.box(x0, 0, z1 - 5, x1 - x0, 6, 5, S.kick, 0.02);
  // 발걸이
  if (S.rail) {
    b.box(x0 + 4, 12, z1 + 3, x1 - x0 - 8, 2.2, 2.2, S.rail, 0.02);
    for (let x = x0 + 10; x < x1 - 6; x += 30) b.box(x, 12, z1 - 1, 1.6, 2.2, 4, S.rail, 0.02);
  }
  b.box(x0, 0, z0, 4, T - 6, z1 - z0 - 4, S.side, 0.04);
  b.box(x1 - 4, 0, z0, 4, T - 6, z1 - z0 - 4, S.side, 0.04);

  // 뒤 작업대
  b.box(x0, 0, -200, x1 - x0, 64, 14, S.back, 0.03);
  b.box(x0, 64, -200, x1 - x0, 3, 15, S.backTop, 0.03);
  for (let x = x0 + 4; x < x1 - 20; x += 24) {
    b.box(x, 8, -186.2, 20, 50, 0.6, S.door, 0.03);
    b.cbox(x + 16, 34, -185.6, 1.2, 4, 0.8, S.knob ?? 0xc9a24a, 0);
  }
}

// 뒷벽 선반 두 단 (술병, 와인, 소주 …)
function backShelves(b, glow, { plank, bracket, light, colors, kind = 'mixed', from = -62, to = 62, rows = [100, 128] }) {
  rows.forEach((y, row) => {
    b.box(from, y, -200, to - from, 2.5, 11, plank, 0.03);
    for (const x of [from + 2, to - 6]) b.box(x, y - 6, -200, 3, 6, 9, bracket, 0.03);
    if (light) glow.box(from + 2, y - 0.5, -199.4, to - from - 4, 0.5, 1, light, 0);
    let x = from + 4, i = 0;
    while (x < to - 8) {
      const c = colors[Math.floor(hash3(row, i, 11) * colors.length)];
      if (kind === 'soju') {
        // 초록 병이 줄지어
        b.cbox(x + 2.5, y + 2.5, -194.5, 5, 13, 5, 0x2f8f4e, 0.04);
        b.cbox(x + 2.5, y + 15.5, -194.5, 2.4, 4, 2.4, 0x2f8f4e, 0.04);
        b.cbox(x + 2.5, y + 19.5, -194.5, 2.8, 1.2, 2.8, i % 4 ? 0x2b7a42 : 0xc0392b, 0.02);
        b.cbox(x + 2.5, y + 6, -192, 5.2, 4, 0.4, 0xeef2ea, 0.03);
        x += 7;
      } else if (kind === 'wine') {
        const h = 16 + Math.floor(hash3(i, row, 12) * 3);
        b.cbox(x + 3, y + 2.5, -194.5, 6, h, 6, c, 0.03);
        b.cbox(x + 3, y + 2.5 + h, -194.5, 2.4, 7, 2.4, c, 0.03);
        b.cbox(x + 3, y + 8.5 + h, -194.5, 2.8, 1.6, 2.8, hash3(i, row, 14) < 0.5 ? 0x7a1c2b : 0xc9a24a, 0.02);
        b.cbox(x + 3, y + 5, -191.4, 5.2, 6, 0.4, hash3(i, row, 16) < 0.6 ? 0xefe2c4 : 0xd8c6a5, 0.03);
        x += 8.4;
      } else if (kind === 'mugs') {
        // 맥주잔과 갈색 병
        if (i % 3 === 0) {
          b.cbox(x + 3.5, y + 2.5, -194.5, 7, 11, 7, 0xdcefee, 0.02);
          b.box(x + 7, y + 5, -195.5, 2, 6, 2, 0xdcefee, 0.02);
          x += 11;
        } else {
          b.cbox(x + 2.5, y + 2.5, -194.5, 5, 13, 5, i % 2 ? 0x6b3a12 : 0x2f5d3a, 0.04);
          b.cbox(x + 2.5, y + 15.5, -194.5, 2.4, 5, 2.4, i % 2 ? 0x6b3a12 : 0x2f5d3a, 0.04);
          b.cbox(x + 2.5, y + 6, -192, 5.2, 5, 0.4, 0xe9d9a8, 0.03);
          x += 7.4;
        }
      } else if (kind === 'attic') {
        // 술병 사이사이 피규어와 게임 상자
        const r = hash3(i, row, 17);
        if (r < 0.22) {
          b.cbox(x + 3, y + 2.5, -194, 6, 8, 5, c, 0.03);
          b.cbox(x + 3, y + 10.5, -194, 5, 5, 5, 0xf0c49c, 0.02);
          b.cbox(x + 3, y + 14.5, -194, 5.4, 2, 5.4, pick([0x2a1d16, 0xe0b030, 0x3a6ea5], i), 0.02);
          x += 9;
        } else if (r < 0.4) {
          b.box(x, y + 2.5, -199, 3, 18, 13, pick([0x2f6fb0, 0xc0392b, 0x2a2a2e, 0x7ac74f], i), 0.03);
          x += 4;
        } else {
          const h = 12 + Math.floor(hash3(i, row, 12) * 9);
          b.cbox(x + 3, y + 2.5, -194.5, 6, h, 6, c, 0.04);
          b.cbox(x + 3, y + 2.5 + h, -194.5, 2.6, 5, 2.6, c, 0.04);
          x += 8.4;
        }
      } else {
        const h = 12 + Math.floor(hash3(i, row, 12) * 9);
        const w = 5 + (i % 3 === 0 ? 1 : 0);
        const square = hash3(i, row, 13) < 0.25;
        b.cbox(x + w / 2, y + 2.5, -194.5, w, h, square ? w : w - 1, c, 0.04);
        b.cbox(x + w / 2, y + 2.5 + h, -194.5, 2.6, 5, 2.6, c, 0.04);
        b.cbox(x + w / 2, y + 7.5 + h, -194.5, 3, 1.2, 3, hash3(i, row, 14) < 0.5 ? 0x2b2420 : 0xc9a24a, 0.02);
        if (hash3(i, row, 15) < 0.7) b.cbox(x + w / 2, y + 6, -194.5 + (w - 1) / 2 + 0.1, w - 1, 5, 0.4, hash3(i, row, 16) < 0.5 ? 0xefe2c4 : 0x2d2a26, 0.03);
        x += w + 2.4;
      }
      i++;
    }
  });
}

// 칠판 (왼쪽 뒷벽): 오늘 짠 한 횟수를 여기에 긋는다. 가게마다 테두리와 판 색만 다르다
function tallyBoard(b, { frame, face, ledge }) {
  const x = -100, y = 104, w = 36, h = 34, t = 2, z = -200;
  b.box(x, y, z, w, t, 2.6, frame, 0.03);
  b.box(x, y + h - t, z, w, t, 2.6, frame, 0.03);
  b.box(x, y + t, z, t, h - 2 * t, 2.6, frame, 0.03);
  b.box(x + w - t, y + t, z, t, h - 2 * t, 2.6, frame, 0.03);
  b.box(-98, 106, -200, 32, 30, 2, face, 0.02);
  b.box(-98, 103, -200, 32, 1.6, 4.4, ledge ?? frame, 0.03);
}

// 북쪽 벽 액자
function wallFrame(b, x, y, w, h, color) {
  const z = -200, t = 2;
  b.box(x, y, z, w, t, 2.8, color, 0.03);
  b.box(x, y + h - t, z, w, t, 2.8, color, 0.03);
  b.box(x, y + t, z, t, h - 2 * t, 2.8, color, 0.03);
  b.box(x + w - t, y + t, z, t, h - 2 * t, 2.8, color, 0.03);
}

// 탁자 겉모습
const TABLE_LOOKS = {
  wood: { top: [0x9a6a43, 0x8f6240, 0xa37148, 0x94653f], edge: 0x6f4a2e, leg: 0x5f3d26, chair: 'chair', seat: [0xb5523b, 0x8f6a3a], frame: 0x7a4f30 },
  steel: { top: [0xc9cdd1, 0xbfc3c7, 0xd2d6da, 0xc4c8cc], edge: 0x9aa0a5, leg: 0x8d9296, chair: 'plastic', seat: [0xc8392f, 0x2f5fb0, 0xc8392f, 0x2f8f4e], frame: 0x8d9296 },
  hof: { top: [0xb07a48, 0xa8723f, 0xb8824e, 0xa06c3c], edge: 0x7a5232, leg: 0x5a3a24, chair: 'stool', seat: [0x8a5a34, 0x9c6a3c], frame: 0x6b4529 },
  dark: { top: [0x4a2e20, 0x45291c, 0x52331f, 0x4d2f20], edge: 0x2e1c14, leg: 0x24160f, chair: 'chair', seat: [0x6b1f2a, 0x4a4a2a], frame: 0x2e1c14 },
  rustic: { top: [0x8a5a33, 0x7d5030, 0x94633a, 0x87582f], edge: 0x5a3a20, leg: 0x4a2f1a, chair: 'chair', seat: [0xb3282e, 0x2f6b3a], frame: 0x5a3a20 },
  pine: { top: [0xc49a6c, 0xb98f60, 0xcca474, 0xbf9566], edge: 0x9a7048, leg: 0x7a5636, chair: 'stool', seat: [0x2f8f8a, 0xd29b2e, 0x8a4a9a], frame: 0x7a5636 },
  hinoki: { top: [0xd8b98a, 0xd2b282, 0xdcbf92, 0xcfae7c], edge: 0x8a6a42, leg: 0x3a2618, chair: 'stool', seat: [0x6b3a22, 0x23305a], frame: 0x3a2618 },
  lounge: { top: [0x5a3e2c, 0x4e3626, 0x634430, 0x553a2a], edge: 0x2a2a30, leg: 0x1c1c22, chair: 'chair', seat: [0xe8dcc4, 0x2f6a6a], frame: 0x2a2a30 },
  patio: { top: [0xdcdcd6, 0xd4d4ce, 0xe0e0da, 0xd8d8d2], edge: 0x2f8f6a, leg: 0xd8d8d2, chair: 'plastic', seat: [0x2f8f6a, 0xf2f2ee, 0x2f6fb0, 0x2f8f6a], frame: 0xd8d8d2 },
};

// ── 가게별 짓기 ───────────────────────────────────────────
// 각 함수는 lit(그림자 드리움), shell(천장처럼 그림자 안 드리움), glow(스스로 빛남)에 상자를 쌓고, 불빛과 소품 위치를 room에 남긴다

const BAR_LOOK = {
  topBase: 0x5f3a22, top: [0x8b5a36, 0x84542f, 0x91603a], topStep: 12, trim: 0x5a3520,
  front: [0x5e3a24, 0x684129], molding: 0x7a4d2f, kick: 0x3d2416, rail: 0xc9a24a, side: 0x5e3a24,
  back: 0x4a2e1c, backTop: 0x6b4529, door: 0x553521,
};

function paperWalls(b, sides) {
  // 아래 판벽, 몰딩, 위 벽지 줄무늬
  for (const [side, from, to] of sides) {
    for (let u = from, i = 0; u < to; u += 10, i++) put(b, side, u, 0, Math.min(10, to - u), 90, 1.2, i % 2 ? 0x664330 : 0x5e3d28, 0.05);
    put(b, side, from, 90, to - from, 5, 3, 0x4a2f1f, 0.02);
    put(b, side, from, 0, to - from, 9, 2.2, 0x4a2f1f, 0.02);
    for (let u = from, i = 0; u < to; u += 16, i++) put(b, side, u, 95, Math.min(8, to - u), 165, 0.3 + (i % 2) * 0.05, i % 2 ? 0xd8c6a5 : 0xe8dcc4, 0.015);
    put(b, side, from, 252, to - from, 8, 2, 0x4a2f1f, 0.02);
  }
}

function fireplace(b, glow, room, stones) {
  const x0 = -240, front = -218;
  for (let y = 0, row = 0; y < 110; y += 6, row++) {
    for (let z = -52 + (row % 2) * 7; z < 52; z += 14) {
      const z0 = Math.max(-52, z), z1 = Math.min(52, z + 13.4);
      const inOpening = y >= 6 && y < 60 && z1 > -26 && z0 < 26;
      if (inOpening) continue;
      b.box(front - 1.2, y, z0, 1.2, 5.4, z1 - z0, stones[Math.floor(hash3(row, Math.round(z), 9) * stones.length)], 0.06);
    }
  }
  const depth = front - 1.2 - x0;
  b.box(x0, 0, -52, depth, 110, 26, 0x5a3a2c, 0);
  b.box(x0, 0, 26, depth, 110, 26, 0x5a3a2c, 0);
  b.box(x0, 60, -26, depth, 50, 52, 0x5a3a2c, 0);
  b.box(x0, 0, -26, depth, 6, 52, 0x2a1d16, 0);
  b.box(x0, 6, -26, 4, 54, 52, 0x1d1410, 0);
  b.box(x0, 6, -26, depth + 1, 54, 1, 0x241913, 0);
  b.box(x0, 6, 25, depth + 1, 54, 1, 0x241913, 0);
  b.box(x0, 59, -26, depth + 1, 1, 52, 0x241913, 0);
  b.box(front - 6, 110, -58, 12, 6, 116, 0x5b3a26, 0.03);
  b.box(front - 1.2, 0, -60, 16, 3, 120, 0x6b6460, 0.05);
  b.box(front - 16, 6, -18, 12, 5, 36, 0x5a3720, 0.08);
  b.box(front - 14, 11, -14, 9, 4, 28, 0x4a2c19, 0.08);
  for (let i = 0; i < 18; i++) {
    const z = -16 + hash3(i, 3, 3) * 32;
    glow.box(front - 14 + hash3(i, 7, 1) * 9, 10.5, z, 1.4, 1, 1.4, i % 3 ? 0xff5a1a : 0xffa83a, 0);
  }
  room.firePos = new THREE.Vector3(front - 9, 15, 0);
}

// 오른쪽 벽 창문 (밤하늘)
function nightWindow(b, glow, { frame = 0x6b4529, curtain = [0x7d342e, 0x8f3f37], sky = 0x1d2a4a, snow = false } = {}) {
  const x = 240;
  b.box(x - 4, 108, -52, 4, 96, 104, frame, 0.03);
  for (const [z0, z1] of [[-48, -2], [2, 48]]) glow.box(x - 4.6, 112, z0, 0.5, 88, z1 - z0, sky, 0);
  b.box(x - 6, 154, -48, 2, 3, 96, frame, 0.02);
  b.box(x - 12, 104, -58, 12, 4, 116, frame, 0.03);
  glow.box(x - 5, 180, 22, 0.5, 7, 7, 0xf6e7b0, 0);
  glow.box(x - 5.1, 182, 24, 0.5, 4, 4, sky, 0);
  for (let i = 0; i < 26; i++) {
    const z = -46 + hash3(i, 9, 9) * 92, y = 114 + hash3(i, 8, 8) * 84;
    if (Math.abs(z) < 3) continue;
    glow.box(x - 5, y, z, 0.4, 0.9, 0.9, i % 4 ? 0xcfd8ff : 0xfff3c4, 0);
  }
  if (snow) {
    // 창틀에 쌓인 눈, 바깥 눈 덮인 언덕
    b.box(x - 13, 108, -58, 13, 3, 116, 0xf4f6fa, 0.04);
    for (const [z0, z1] of [[-48, -2], [2, 48]]) glow.box(x - 4.7, 112, z0, 0.5, 18, z1 - z0, 0xb8c6dc, 0);
    for (const [z0, z1] of [[-48, -2], [2, 48]]) b.box(x - 6, 112, z0, 1.2, 3 + hash3(z0, 1, 1) * 3, z1 - z0, 0xf4f6fa, 0.05);
  }
  for (const side of [-1, 1]) {
    for (let i = 0; i < 6; i++) {
      const z = side * (52 + i * 4);
      b.box(x - 10 - (i % 2) * 2, 92, z - 2, 4, 128, 4, curtain[i % 2], 0.04);
    }
  }
  b.box(x - 14, 220, -80, 4, 3, 160, 0x4a2f1f, 0.02);
}

// 앞벽 책장과 술병
function bookshelf(b) {
  const z = 220;
  b.box(-84, 0, z - 22, 168, 4, 22, 0x5b3a26, 0.03);
  b.box(-84, 0, z - 22, 4, 172, 22, 0x5b3a26, 0.03);
  b.box(80, 0, z - 22, 4, 172, 22, 0x5b3a26, 0.03);
  const shelves = [40, 82, 124, 168];
  for (const y of shelves) b.box(-84, y, z - 22, 168, 3, 22, 0x6b4529, 0.03);
  const colors = [0x2f5d3a, 0xa45a1c, 0xcfe3e0, 0x7a1c2b, 0x3f8f4f, 0x6b3a12, 0xe9e2d3, 0x233d26, 0xb8641c];
  shelves.slice(0, 3).forEach((y, row) => {
    let x = -76, i = 0;
    while (x < 70) {
      const k = Math.floor(hash3(row, i, 4) * colors.length);
      const h = 14 + Math.floor(hash3(i, row, 5) * 10);
      const w = 6 + (i % 2);
      if (hash3(i, row, 6) < 0.18) {
        b.cbox(x + 3, y + 3, z - 11, 6, 8, 6, 0xdcefee, 0.02);
        x += 9;
      } else {
        b.cbox(x + w / 2, y + 3, z - 11, w, h, w, colors[k], 0.04);
        b.cbox(x + w / 2, y + 3 + h, z - 11, 3, 6, 3, colors[k], 0.04);
        b.cbox(x + w / 2, y + 9 + h, z - 11, 3.4, 1.4, 3.4, 0x2b2420, 0.02);
        if (hash3(i, row, 8) < 0.6) b.cbox(x + w / 2, y + 7, z - 11 - w / 2, w + 0.2, 6, 0.4, 0xefe2c4, 0.03);
        x += w + 3;
      }
      i++;
    }
  });
  let bx = -76;
  for (let i = 0; bx < 72; i++) {
    const w = 3 + Math.floor(hash3(i, 1, 1) * 3), h = 16 + Math.floor(hash3(i, 2, 2) * 9);
    b.box(bx, 171, z - 19, w, h, 15, [0x7a2e2a, 0x2f4a5a, 0x5a6b3a, 0xc9a24e, 0x4a3a5a][i % 5], 0.05);
    bx += w + 0.4;
  }
}

// 매다는 전등. kind: glass(초록 유리 겹갓), cone(호프집 법랑갓), globe(와인바 둥근 전구), bulb(포차 알전구), lantern(산장 등불), paper(다락방 종이갓)
function pendant(b, glow, x, z, s, kind, top = 260) {
  const y = 200;
  if (kind === 'bulb') {
    b.cbox(x, y, z, 0.6, top - y, 0.6, 0x1f1a17, 0);
    b.cbox(x, y - 3, z, 2.6, 3, 2.6, 0x3a3634, 0);
    glow.cbox(x, y - 9 * s, z, 5 * s, 6 * s, 5 * s, 0xffc46a, 0);
    glow.cbox(x, y - 10 * s, z, 3.4 * s, 8 * s, 3.4 * s, 0xffe2a6, 0);
    return;
  }
  b.cbox(x, y, z, 0.8, top - y, 0.8, 0x1f1a17, 0);
  if (kind === 'glass') {
    for (let i = 0; i < 6; i++) {
      const r = (6 + i * 2.2) * s, yy = y - (i + 1) * 2.2 * s;
      const n = Math.ceil((Math.PI * 2 * r) / (2.4 * s));
      for (let a = 0; a < n; a++) {
        const ang = (a / n) * Math.PI * 2;
        b.cbox(x + Math.cos(ang) * r, yy, z + Math.sin(ang) * r, 3.4 * s, 2.4 * s, 3.4 * s, i === 5 ? 0x24382e : 0x2f4a3c, 0.03);
      }
    }
    glow.cbox(x, y - 15 * s, z, 5 * s, 7 * s, 5 * s, 0xfff1c9, 0);
    glow.cbox(x, y - 6 * s, z, 10 * s, 1, 10 * s, 0xffd99a, 0);
  } else if (kind === 'cone') {
    for (let i = 0; i < 5; i++) {
      const r = (3 + i * 3) * s;
      b.cbox(x, y - (i + 1) * 2.6 * s, z, r * 2, 2.6 * s, r * 2, i === 4 ? 0xe9e2d3 : 0x2f6b4a, 0.02);
    }
    glow.cbox(x, y - 16 * s, z, 7 * s, 3 * s, 7 * s, 0xfff1c9, 0);
  } else if (kind === 'globe') {
    disc(glow, x, y - 14 * s, z, 4.5 * s, 9 * s, 1.5 * s, () => 0xffd28a);
    glow.cbox(x, y - 13 * s, z, 3 * s, 11 * s, 3 * s, 0xffe8b8, 0);
    b.cbox(x, y - 5 * s, z, 3 * s, 3 * s, 3 * s, 0xc9a24a, 0.02);
  } else if (kind === 'lantern') {
    b.cbox(x, y - 4 * s, z, 10 * s, 2 * s, 10 * s, 0x2a2420, 0.02);
    for (const [dx, dz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) b.cbox(x + dx * 4.4 * s, y - 16 * s, z + dz * 4.4 * s, 1.2 * s, 12 * s, 1.2 * s, 0x2a2420, 0);
    glow.cbox(x, y - 15 * s, z, 7 * s, 10 * s, 7 * s, 0xffc070, 0);
    b.cbox(x, y - 18 * s, z, 10 * s, 2 * s, 10 * s, 0x2a2420, 0.02);
  } else if (kind === 'paper') {
    disc(b, x, y - 16 * s, z, 9 * s, 12 * s, 2 * s, (dx, dz, d) => (d > 7 * s ? 0xf0e6cf : null));
    glow.cbox(x, y - 14 * s, z, 9 * s, 8 * s, 9 * s, 0xffe6b8, 0);
  }
}

// ── 1. 단골 바 ───────────────────────────────────────────
function buildBarVenue(room, lit, shell, glow) {
  outerShell(lit, 0xe8dcc4);
  plankFloor(lit, [0x7a5236, 0x6e4a31, 0x85593a, 0x73503a]);
  room.rug = 'round';
  paperWalls(lit, [['back', -240, 240], ['front', -240, 240], ['left', -200, -52], ['left', 52, 220], ['right', -200, 220]]);
  shell.box(-248, 260, -208, 496, 6, 436, 0x2a1c14, 0);
  for (let x = -220; x <= 220; x += 62) shell.box(x - 4, 250, -208, 8, 10, 436, 0x3b281b, 0.04);
  buildBar(lit, glow, BAR_LOOK);
  // 생맥주 꼭지, 엎어 둔 잔
  lit.box(-30, 67, -196, 26, 6, 6, 0x9a9da0, 0.02);
  for (let i = 0; i < 3; i++) {
    const x = -26 + i * 8;
    lit.cbox(x, 73, -193, 2, 4, 2, 0xc9a24a, 0);
    lit.cbox(x, 77, -193, 1.6, 10, 1.6, [0x2f5d3a, 0x7a1c2b, 0x2d2a26][i], 0.02);
  }
  for (let i = 0; i < 6; i++) lit.cbox(24 + i * 8, 67, -193, 5, 7, 5, 0xdcefee, 0.02);
  backShelves(lit, glow, { plank: 0x6b4529, bracket: 0x5b3a26, light: 0xffc982, colors: [0x2f5d3a, 0xa45a1c, 0xcfe3e0, 0x7a1c2b, 0x3f8f4f, 0x6b3a12, 0xe9e2d3, 0x233d26, 0xb8641c, 0x3a2414, 0x8a0f22] });
  tallyBoard(lit, { frame: 0x6b4529, face: 0x2f3a33 });
  mountainFrame(lit, glow);
  candle(room, lit, -76, -150);
  plant(lit, 80, -158);
  coasters(lit);
  barStools(lit, 'wood');
  fireplace(lit, glow, room, [0x9b4a32, 0x8a3f2b, 0xa95a3c]);
  nightWindow(lit, glow);
  bookshelf(lit);
  pendant(shell, glow, 0, 0, 1, 'glass');
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.6, 'glass');
}

function mountainFrame(b, glow) {
  const fx0 = 66;
  wallFrame(b, fx0, 108, 30, 24, 0x5b3a26);
  b.box(fx0 + 2, 110, -200, 26, 20, 1.6, 0x26345a, 0.02);
  const hills = [[0, 0, 26, 4, 0x3f6b44], [2, 4, 10, 3, 0x4f7d4f], [12, 4, 12, 5, 0x355c3b], [16, 9, 6, 3, 0x355c3b]];
  hills.forEach(([dx, dy, w, h, c]) => b.box(fx0 + 2 + dx, 110 + dy, -198.4, w, h, 0.6, c, 0.03));
  glow.box(fx0 + 21, 124, -198.4, 3, 3, 0.4, 0xf6e7b0, 0);
  for (const [x, y] of [[6, 126], [11, 123], [16, 128], [9, 129]]) glow.box(fx0 + x, y, -198.4, 0.8, 0.8, 0.4, 0xfff3c4, 0);
}

function candle(room, b, cx, cz, color = 0xf3e6c8) {
  const T = TABLE_Y;
  b.cbox(cx, T, cz, 8, 1, 8, 0xb8893a, 0.04);
  b.cbox(cx, T + 1, cz, 4, 8, 4, color, 0.03);
  b.cbox(cx, T + 9, cz, 0.6, 1.2, 0.6, 0x2a1f1a, 0);
  room.candlePos = new THREE.Vector3(cx, T + 11, cz);
}

function plant(b, px, pz) {
  const T = TABLE_Y;
  b.cbox(px, T, pz, 10, 9, 10, 0xb0603a, 0.05);
  b.cbox(px, T + 8, pz, 11, 2, 11, 0xa0522d, 0.03);
  b.cbox(px, T + 9.5, pz, 9, 0.5, 9, 0x3b2a1e, 0.05);
  const leaves = [[0, 6, 0, 3, 14], [-3, 5, 2, 3, 11], [3, 4, -2, 3, 12], [-2, 6, -3, 2, 16], [2, 5, 3, 2, 9]];
  leaves.forEach(([dx, w, dz, d, h], i) => {
    b.cbox(px + dx, T + 10, pz + dz, d, h, d, i % 2 ? 0x4f7d3a : 0x5f9445, 0.06);
    b.cbox(px + dx * 1.6, T + 10 + h - 3, pz + dz * 1.6, w, 3, w * 0.7, i % 2 ? 0x5f9445 : 0x6aa64d, 0.06);
  });
}

function coasters(b, a = 0xd8c39a, c = 0xc9b58a) {
  b.cbox(0, TABLE_Y, -140, 12, 0.4, 12, a, 0.02);
  b.cbox(-28, TABLE_Y, -156, 11, 0.4, 11, c, 0.02);
}

// 손님 쪽 바 스툴 (혼자 짠에서 보이는 둘)
function barStools(b, kind) {
  for (const sx of [-48, 48]) {
    const sz = -104;
    if (kind === 'plastic') {
      stoolPlastic(b, sx, sz, sx < 0 ? 0xc8392f : 0x2f5fb0, 46);
      continue;
    }
    const seat = kind === 'hinoki' ? [0x3a2618, 0x6b3a22] : kind === 'wood' ? [0x5a3520, 0x9c3a2c] : kind === 'dark' ? [0x24160f, 0x5a1a24] : kind === 'pine' ? [0x7a5636, 0x2f8f8a] : kind === 'xmas' ? [0x4a2f1a, 0xb3282e] : [0x5a3a24, 0x8a5a34];
    disc(b, sx, 60, sz, 11, 4, 2, (x, z, d) => (d > 9 ? seat[0] : seat[1]));
    b.cbox(sx, 0, sz, 3, 60, 3, kind === 'pine' ? 0x2a2a2e : 0x3a3634, 0.02);
    b.cbox(sx, 0, sz, 16, 2, 16, 0x2d2a28, 0.02);
    b.cbox(sx, 24, sz, 12, 1.5, 1.5, kind === 'dark' ? 0x9a9da0 : 0xc9a24a, 0);
    b.cbox(sx, 24, sz, 1.5, 1.5, 12, kind === 'dark' ? 0x9a9da0 : 0xc9a24a, 0);
  }
}

// 플라스틱 원형 의자 (포장마차)
function stoolPlastic(b, x, z, color, h = 42) {
  disc(b, x, h, z, 13, 3, 2, (dx, dz, d) => (d > 11 ? color : d < 3 ? 0x1c1c1e : color));
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + Math.PI / 4;
    b.cbox(x + Math.cos(a) * 8, 0, z + Math.sin(a) * 8, 3.6, h, 3.6, color, 0.02);
  }
  b.cbox(x, 12, z, 18, 2.4, 2.4, color, 0.02);
  b.cbox(x, 12, z, 2.4, 2.4, 18, color, 0.02);
}

// ── 2. 포장마차 ─────────────────────────────────────────
function buildPocha(room, lit, shell, glow) {
  outerShell(lit, 0x1c1a20);
  // 콘크리트 바닥
  tileFloor(lit, 40, (i, k) => [0x5f5f5c, 0x575754, 0x63625e, 0x5a5955][Math.floor(hash3(i, k, 31) * 4)], 0x3f3f3d);
  for (let i = 0; i < 14; i++) lit.box(-200 + hash3(i, 1, 33) * 400, -0.1, -180 + hash3(i, 2, 33) * 380, 10 + hash3(i, 3, 33) * 24, 0.2, 6 + hash3(i, 4, 33) * 16, 0x4a4a47, 0.05);
  // 주황 천막 벽, 투명 비닐 창
  const tarp = [0xf0882c, 0xe57d26, 0xf59536];
  const windows = { back: [], front: [[-200, -70], [70, 200]], left: [[-170, -40], [40, 170]], right: [[-170, -40], [40, 170]] };
  for (const side of ['back', 'front', 'left', 'right']) {
    const [from, to] = SPAN[side];
    for (let u = from, i = 0; u < to; u += 30, i++) {
      const w = Math.min(30, to - u);
      // 앞쪽 가운데는 드나드는 곳 (비닐 커튼)
      if (side === 'front' && u >= -60 && u + w <= 60) continue;
      const win = windows[side].find(([a, z]) => u >= a && u + w <= z);
      if (win) {
        put(lit, side, u, 0, w, 86, 1.4, pick(tarp, i), 0.03);
        put(lit, side, u, 186, w, 54, 1.4, pick(tarp, i), 0.03);
        // 비닐 너머 밤거리: 어두운 파랑에 번진 간판 불빛
        put(glow, side, u, 86, w, 100, 0.5, 0x141c30, 0, 0.2);
        for (let k = 0; k < 4; k++) {
          const c = pick([0xff5a6a, 0x5ad1ff, 0xffd25a, 0x8aff8a, 0xffffff], i * 3 + k + side.length);
          if (hash3(i, k, side.length) < 0.55) put(glow, side, u + 3 + hash3(i, k, 41) * (w - 8), 92 + hash3(k, i, 42) * 80, 3 + hash3(i, k, 43) * 4, 2 + hash3(i, k, 44) * 4, 0.4, c, 0, 0.5);
        }
        put(lit, side, u, 85, w, 2, 2, 0xb9bec2, 0.02, 0);
        put(lit, side, u, 185, w, 2, 2, 0xb9bec2, 0.02, 0);
      } else {
        put(lit, side, u, 0, w, 240, 1.4, pick(tarp, i), 0.03);
      }
      put(lit, side, u + w - 1, 0, 1.2, 240, 1.8, 0xb5531c, 0.02);
    }
    // 아래 고정줄
    put(lit, side, from, 0, to - from, 5, 2.4, 0x8a3a18, 0.02);
  }
  // 앞 출입구 비닐 커튼
  for (let x = -60, i = 0; x < 60; x += 10, i++) glow.box(x, 6, 218.8, 9, 196, 0.4, i % 2 ? 0x2a3550 : 0x253049, 0);
  lit.box(-64, 0, 216, 4, 206, 4, 0xb9bec2, 0.02);
  lit.box(60, 0, 216, 4, 206, 4, 0xb9bec2, 0.02);
  lit.box(-64, 202, 216, 128, 4, 4, 0xb9bec2, 0.02);
  // 천막 지붕: 쇠파이프 틀에 처진 천
  for (let x = W.x0, i = 0; x < W.x1; x += 30, i++) {
    const sag = Math.sin(((x + 15 - W.x0) / (W.x1 - W.x0)) * Math.PI);
    shell.box(x, 236 + sag * 18, -208, 30, 4, 436, pick(tarp, i), 0.02);
  }
  for (const x of [-240, -120, 0, 120, 236]) shell.box(x - 2, 232, -208, 4, 4, 436, 0xb9bec2, 0.02);
  for (const [x, z] of [[-238, -198], [236, -198], [-238, 216], [236, 216]]) lit.box(x, 0, z, 4, 236, 4, 0xb9bec2, 0.02);
  // 바 → 스테인리스 조리대
  buildBar(lit, glow, {
    topBase: 0x9aa0a5, top: [0xc4c8cc, 0xbcc0c4, 0xcaced2], topStep: 16, topJitter: 0.015, trim: 0x8d9296,
    frontKind: 'steel', front: [0xa7adb2, 0x9fa5aa], band: 0xc0392b, kick: 0x5a5e62, rail: null, side: 0x9aa0a5,
    back: 0x8d9296, backTop: 0xb9bec2, door: 0x9fa5aa, knob: 0x5a5e62,
  });
  // 어묵 통: 김이 오르는 국물, 꼬치
  const T = TABLE_Y;
  lit.box(-92, T, -166, 34, 9, 26, 0xb9bec2, 0.02);
  lit.box(-90, T + 7.6, -164, 30, 0.6, 22, 0x9a6a36, 0.03);
  for (let i = 0; i < 14; i++) {
    const x = -88 + (i % 7) * 4, z = -160 + Math.floor(i / 7) * 9;
    lit.box(x, T + 4, z, 3.6, 5, 2, 0xd9b071, 0.05);
    lit.box(x + 1.4, T + 9, z + 0.6, 0.6, 12, 0.6, 0xc9a26b, 0);
  }
  room.steam.push(new THREE.Vector3(-75, T + 10, -153));
  // 오른쪽 끝: 양념통, 휴지, 소주 상자
  for (const [x, c] of [[70, 0xc0392b], [76, 0xe0b030]]) { lit.cbox(x, T, -160, 5, 12, 5, c, 0.03); lit.cbox(x, T + 12, -160, 2, 3, 2, 0xf2ede0, 0.02); }
  lit.cbox(86, T, -158, 8, 10, 8, 0xf2ede0, 0.02);
  lit.cbox(86, T + 10, -158, 2, 2, 2, 0xf2ede0, 0);
  coasters(lit, 0xf2ede0, 0xe9e4d8);
  // 뒤 작업대 위: 가스 화구와 웍, 큰 냄비
  lit.box(-80, 67, -198, 28, 3, 12, 0x2a2a2d, 0.02);
  disc(lit, -66, 70, -192, 10, 4, 2, (x, z, d) => (d > 8.5 ? 0x2a2a2d : 0x3a3a3d));
  lit.box(-56, 72, -193, 12, 1.4, 2, 0x3a2a1a, 0);
  disc(lit, 40, 67, -193, 9, 16, 2, (x, z, d) => (d > 7.5 ? 0xb9bec2 : 0xa0a5a9));
  room.steam.push(new THREE.Vector3(40, 86, -193));
  // 소주 상자 쌓기
  for (const [x, y] of [[60, 67], [74, 67], [67, 85]]) {
    lit.box(x, y, -199, 13, 18, 12, 0x2f8f4e, 0.03);
    for (let k = 0; k < 3; k++) lit.cbox(x + 2.5 + k * 4, y + 18, -193, 3, 4, 3, 0x2b7a42, 0.03);
  }
  backShelves(lit, glow, { plank: 0x9aa0a5, bracket: 0x8d9296, light: null, colors: [0x2f8f4e], kind: 'soju', rows: [88, 112] });
  // 차림표: 세로로 쓴 종이를 줄줄이, 위엔 집게 줄
  lit.box(-62, 180, -198.4, 124, 0.8, 0.8, 0x3a3634, 0);
  POCHA_MENU.forEach(([name, paper], i) => {
    const x = -58 + i * 13.5 + 5.75;
    room.signs.push({ x, y: 157, z: -198.2, w: 11.5, h: 42, cw: 16, ch: 60, draw: pochaStrip(name, paper, i) });
    lit.cbox(x, 177, -198.2, 2.4, 3, 1, 0xb9bec2, 0);
  });
  tallyBoard(lit, { frame: 0xb9bec2, face: 0xf4f4f0 });
  barStools(lit, 'plastic');
  // 알전구 줄
  for (const x of [-150, -75, 0, 75, 150]) for (const z of [-60, 30, 120]) pendant(shell, glow, x, z, 1, 'bulb', 236 + Math.sin(((x + 15 - W.x0) / 480) * Math.PI) * 18);
  for (const x of [-75, -25, 25, 75]) pendant(shell, glow, x, -150, 1, 'bulb', 250);
  // 왼쪽: 꼼장어 굽는 화로와 연기
  lit.box(-232, 0, -20, 26, 46, 40, 0x5a5e62, 0.02);
  lit.box(-230, 46, -18, 22, 2, 36, 0x2a2a2d, 0.02);
  for (let i = 0; i < 10; i++) glow.box(-228 + hash3(i, 2, 51) * 18, 48, -16 + hash3(i, 3, 51) * 32, 2, 1, 2, i % 2 ? 0xff5a1a : 0xff9a3a, 0);
  for (let i = 0; i < 6; i++) lit.box(-228 + i * 3.4, 49, -12, 2.6, 1, 24, 0x6a3a22, 0.04);
  room.smoke.push(new THREE.Vector3(-219, 52, 0));
  room.grillPos = new THREE.Vector3(-219, 60, 0);
}

// ── 3. 호프집 ───────────────────────────────────────────
function buildHof(room, lit, shell, glow) {
  outerShell(lit, 0xe9dcc0);
  tileFloor(lit, 24, (i, k) => ((i + k) % 2 ? 0x8a4f32 : 0x7a4429), 0x4a2a1a);
  // 아래 밝은 판벽, 위 크림색 벽
  for (const [side, from, to] of [['back', -240, 240], ['front', -240, 240], ['left', -200, 220], ['right', -200, 220]]) {
    for (let u = from, i = 0; u < to; u += 12, i++) put(lit, side, u, 0, Math.min(12, to - u), 100, 1.4, pick([0xa8743f, 0x9c6a38, 0xb07a48], i), 0.04);
    put(lit, side, from, 100, to - from, 5, 3, 0x6b4529, 0.02);
    put(lit, side, from, 105, to - from, 155, 0.4, 0xe9dcc0, 0.015);
    put(lit, side, from, 0, to - from, 8, 2.2, 0x5a3a24, 0.02);
  }
  shell.box(-248, 260, -208, 496, 6, 436, 0x4a3426, 0);
  for (let z = -200; z < 220; z += 40) shell.box(-248, 254, z, 496, 6, 6, 0x6b4a30, 0.03);
  buildBar(lit, glow, {
    topBase: 0x7a5232, top: [0xb8824e, 0xb07a48, 0xc08a56], topStep: 12, trim: 0x6b4529,
    front: [0x9c6a38, 0xa8743f], molding: 0x7a5232, kick: 0x4a2f1f, rail: 0xc9a24a, side: 0x9c6a38,
    back: 0x5a3a24, backTop: 0x7a5232, door: 0x6b4529,
  });
  // 바 위 생맥주 타워
  const T = TABLE_Y;
  lit.box(56, T, -164, 8, 30, 8, 0xc9ccce, 0.02);
  lit.box(48, T + 26, -164, 24, 6, 8, 0xd2d6da, 0.02);
  for (let i = 0; i < 3; i++) { lit.cbox(52 + i * 8, T + 18, -158, 2, 8, 2, 0x9a9da0, 0); lit.cbox(52 + i * 8, T + 32, -160, 2.4, 10, 2.4, [0xe0b030, 0x2a2a2d, 0xc0392b][i], 0.02); }
  lit.box(46, T, -168, 28, 1, 16, 0x9a9da0, 0.02);
  // 뒤: 유리문 맥주 냉장고, 맥주통
  lit.box(-96, 0, -200, 44, 64, 15, 0xd8dcdf, 0.02);
  glow.box(-93, 6, -185.1, 38, 52, 0.4, 0x9fc4d8, 0);
  for (let r = 0; r < 3; r++) for (let i = 0; i < 9; i++) lit.cbox(-90 + i * 4, 8 + r * 17, -188, 3, 12, 3, pick([0x6b3a12, 0x2f5d3a, 0xe0b030], i + r), 0.03);
  for (const x of [-30, -12]) {
    disc(lit, x, 64 + 3, -192, 8, 16, 2, (dx, dz, d) => (d > 6.8 ? 0x9a9da0 : 0xb9bec2));
    lit.cbox(x, 67 + 5, -184, 16.5, 2, 1, 0x7a7d80, 0);
  }
  for (let i = 0; i < 5; i++) lit.cbox(24 + i * 9, 67, -193, 7, 11, 7, 0xdcefee, 0.02);
  backShelves(lit, glow, { plank: 0x6b4529, bracket: 0x5b3a26, light: 0xffe0a8, colors: [0x6b3a12], kind: 'mugs', rows: [92, 118] });
  tallyBoard(lit, { frame: 0x6b4529, face: 0x2f3a33 });
  // 뒷벽 가운데 네온 간판, 오른쪽 축구 중계, 왼쪽 위 안주 칠판
  lit.box(-40, 152, -200, 80, 28, 1.2, 0x2a1e18, 0.02);
  room.signs.push({ x: 0, y: 166, z: -198.6, w: 76, h: 25, cw: 76, ch: 25, glow: true, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    neon(g, '호프', w / 2 - 12, 10, '#ff5a9a', 15);
    neon(g, 'HOF', w / 2 + 22, 9, '#ffc24a', 11);
    neon(g, '시원한 생맥주', w / 2, 20, '#7ae0ff', 9);
  } });
  lit.box(62, 133, -200, 38, 24, 3, 0x111113, 0.01);
  lit.box(78, 126, -199, 6, 7, 1.4, 0x2a2a2e, 0);
  room.signs.push({ x: 81, y: 145, z: -196.9, w: 34, h: 20, cw: 64, ch: 38, glow: true, anim: true, draw: soccer });
  lit.box(-100, 141, -200, 36, 34, 2, 0x6b4529, 0.03);
  room.signs.push({ x: -82, y: 158, z: -197.9, w: 32, h: 30, cw: 40, ch: 38, draw: (g, w, h) => {
    g.fillStyle = '#2f3a33'; g.fillRect(0, 0, w, h);
    txt(g, '안주', w / 2, 6, '#ffe9a0', 9);
    ['치킨', '노가리', '골뱅이', '감자튀김'].forEach((m, i) => txt(g, m, w / 2, 15 + i * 7, '#eeeadf', 8));
  } });
  // 벽 포스터: 맥주잔 그림 (상표 없음)
  for (const [side, u] of [['back', 140], ['back', -160], ['front', -150], ['front', 110]]) {
    put(lit, side, u, 130, 40, 56, 1, 0x2a2420, 0.02);
    put(lit, side, u + 2, 132, 36, 52, 1.2, u > 0 ? 0xc0392b : 0x2f4a6e, 0.02);
    put(lit, side, u + 12, 140, 14, 22, 1.6, 0xe0b030, 0.02);
    put(lit, side, u + 12, 162, 14, 6, 1.7, 0xfffaf0, 0.02);
    put(lit, side, u + 26, 146, 4, 12, 1.6, 0xdcefee, 0.02);
    put(lit, side, u + 6, 172, 24, 4, 1.6, 0xfffaf0, 0.02);
  }
  // 왼쪽 벽: 축구 중계 텔레비전, 네온 간판
  lit.box(-240, 128, -50, 4, 60, 100, 0x111113, 0.01);
  glow.box(-235.8, 132, -46, 0.4, 52, 92, 0x2f7a3a, 0);
  glow.box(-235.6, 157, -46, 0.4, 1, 92, 0xcfe8cf, 0);
  glow.box(-235.6, 132, -1, 0.4, 52, 1, 0xcfe8cf, 0);
  glow.box(-235.6, 150, -8, 0.4, 14, 14, 0x3f8a4a, 0);
  room.tvPos = new THREE.Vector3(-230, 158, 0);
  pixelText(glow, 'left', 'HOF', 155, 140, 5, 0xffb84a, 0.6, 1.2);
  pixelText(glow, 'left', 'BEER', -102, 178, 3.4, 0xff5a8a, 0.6, 1.2);
  // 앞쪽: 맥주통 쌓기
  for (const [x, z, y] of [[-200, 190, 0], [-170, 196, 0], [-185, 192, 30]]) {
    disc(lit, x, y, z, 13, 30, 2, (dx, dz, d) => (d > 11.5 ? 0x9a9da0 : 0xb9bec2));
    lit.cbox(x, y + 8, z, 26.4, 2, 26.4, 0x7a7d80, 0);
    lit.cbox(x, y + 21, z, 26.4, 2, 26.4, 0x7a7d80, 0);
  }
  nightWindow(lit, glow, { frame: 0x6b4529, curtain: [0x2f4a6e, 0x2a4262] });
  // 바 왼쪽 끝: 땅콩 바구니
  lit.cbox(-76, TABLE_Y, -150, 14, 4, 10, 0xb88a4a, 0.05);
  for (let i = 0; i < 10; i++) lit.cbox(-81 + (i % 5) * 2.6, TABLE_Y + 4, -152 + Math.floor(i / 5) * 3, 2.2, 1.6, 2.2, 0xd9b071, 0.06);
  coasters(lit, 0xf2ede0, 0xe9e4d8);
  barStools(lit, 'hof');
  pendant(shell, glow, 0, 0, 1.1, 'cone');
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'cone');
  for (const [x, z] of [[-150, 100], [150, -80], [-150, -80]]) pendant(shell, glow, x, z, 0.9, 'cone');
}

// ── 4. 와인바 ───────────────────────────────────────────
function brickWall(b, side, from, to, y0, y1, colors, mortar) {
  put(b, side, from, y0, to - from, y1 - y0, 0.6, mortar, 0.02);
  for (let y = y0, row = 0; y < y1; y += 6, row++) {
    for (let u = from - (row % 2) * 7; u < to; u += 14) {
      const a = Math.max(from, u), z = Math.min(to, u + 13);
      if (z > a) put(b, side, a, y + 0.5, z - a, 5, 1.6, colors[Math.floor(hash3(row, Math.round(u), side.length) * colors.length)], 0.05);
    }
  }
}

function buildWine(room, lit, shell, glow) {
  outerShell(lit, 0x2a1c16);
  // 짙은 나무 바닥 (빗살처럼 엇갈린 판자)
  for (let x = W.x0, i = 0; x < W.x1; x += 24, i++) {
    for (let z = W.z0, k = 0; z < W.z1; z += 24, k++) {
      const c = [0x3e2619, 0x4a2d1d, 0x35211a, 0x442a1b][(i + k * 3) & 3];
      if ((i + k) % 2) for (let s = 0; s < 3; s++) lit.box(x + 0.3, -4, z + s * 8 + 0.3, 23.4, 4, 7.4, s % 2 ? c : 0x3a2418, 0.05);
      else for (let s = 0; s < 3; s++) lit.box(x + s * 8 + 0.3, -4, z + 0.3, 7.4, 4, 23.4, s % 2 ? c : 0x3a2418, 0.05);
    }
  }
  const bricks = [0x7a3b2a, 0x6b3324, 0x854232, 0x733728];
  for (const [side, from, to] of [['back', -240, 240], ['front', -240, 240], ['right', -200, 220]]) {
    for (let u = from, i = 0; u < to; u += 12, i++) put(lit, side, u, 0, Math.min(12, to - u), 90, 1.4, i % 2 ? 0x2c1d16 : 0x33221a, 0.04);
    put(lit, side, from, 90, to - from, 4, 3, 0x1e140f, 0.02);
    brickWall(lit, side, from, to, 94, 260, bricks, 0x4a2a20);
  }
  // 왼쪽 벽: 와인 칸 선반 (칸마다 병 머리)
  lit.box(-240, 0, -200, 14, 260, 420, 0x24160f, 0);
  for (let y = 6, r = 0; y < 230; y += 14, r++) {
    for (let z = -190, k = 0; z < 210; z += 14, k++) {
      lit.box(-227, y, z, 4, 1.4, 14, 0x3a2418, 0.02);
      lit.box(-227, y, z, 4, 14, 1.4, 0x3a2418, 0.02);
      if (hash3(r, k, 61) < 0.82) {
        const c = pick([0x1d2e1a, 0x2a0f14, 0x1a1f14, 0x3a1a10], r + k * 3);
        lit.box(-227, y + 4, z + 4, 3, 6, 6, c, 0.03);
        lit.box(-224.4, y + 5.6, z + 5.6, 1.4, 2.8, 2.8, hash3(k, r, 62) < 0.5 ? 0x7a1c2b : 0xc9a24a, 0.02);
      }
    }
  }
  shell.box(-248, 260, -208, 496, 6, 436, 0x1c1410, 0);
  for (let x = -200; x <= 200; x += 80) shell.box(x - 4, 248, -208, 8, 12, 436, 0x141010, 0.02);
  buildBar(lit, glow, {
    topBase: 0xb9b4ab, top: [0xe9e6df, 0xe2ded6, 0xece9e3], topStep: 24, topJitter: 0.012, veins: 0xb0aba2, trim: 0x2e1c14,
    front: [0x3a2418, 0x33201a], molding: 0x4a2e1e, kick: 0x1c120d, rail: 0xc9a24a, side: 0x3a2418,
    back: 0x2e1c14, backTop: 0x3a2418, door: 0x33201a,
  });
  // 뒷벽 가득 와인 칸: 칸마다 누운 병의 목과 포일
  // 칸 안쪽은 은은하게 빛나서 병이 그림자처럼 도드라진다
  glow.box(-60, 86, -199.6, 120, 92, 0.5, 0x5a2c12, 0);
  for (let r = 0; r < 7; r++) {
    glow.box(-60, 86 + r * 13 + 1.5, -197.8, 120, 0.8, 0.8, 0xffa850, 0);
    for (let c = 0; c < 10; c++) {
      const x = -60 + c * 12, y = 86 + r * 13;
      lit.box(x, y, -199, 12, 1.4, 8.4, 0x4a2e1e, 0.03);
      lit.box(x, y, -199, 1.4, 13, 8.4, 0x3a2418, 0.03);
      if (hash3(r, c, 81) < 0.86) {
        const glass = pick([0x1d2e1a, 0x2a0f14, 0x1a1f14, 0x26301a], r * 3 + c);
        lit.box(x + 2.8, y + 3, -197, 7.6, 7.6, 4, glass, 0.03);
        lit.box(x + 4.9, y + 5.1, -193, 3.4, 3.4, 3, glass, 0.02);
        lit.box(x + 5.1, y + 5.3, -190, 3, 3, 1.2, pick([0x8a1c2b, 0xd9b04a, 0xe9e2d3, 0x5a1a4a], r + c * 2), 0.02);
      }
    }
  }
  lit.box(-60, 86 + 7 * 13, -192, 121.4, 1.4, 1.4, 0x4a2e1e, 0.03);
  lit.box(60, 86, -192, 1.4, 92, 1.4, 0x4a2e1e, 0.03);
  for (let i = 0; i < 5; i++) lit.cbox(24 + i * 9, 67, -193, 5, 14, 5, pick([0x1d2e1a, 0x2a0f14], i), 0.03);
  tallyBoard(lit, { frame: 0x2e1c14, face: 0x1f2422 });
  // 바 위에 매단 잔걸이: 거꾸로 꽂힌 와인잔 두 줄
  // (그림자를 드리우면 바 위 불빛을 가려서 그림자 없이)
  for (const x of [-70, 70]) shell.cbox(x, 178, -152, 1, 82, 1, 0x1c1c1e, 0);
  shell.box(-74, 176, -160, 148, 2.4, 16, 0x24160f, 0.02);
  for (const z of [-157, -149]) {
    for (let i = 0; i < 15; i++) {
      const x = -63 + i * 9;
      shell.cbox(x, 175, z, 5, 1, 5, 0xdcefee, 0);
      shell.cbox(x, 167, z, 0.9, 8, 0.9, 0xdcefee, 0);
      shell.cbox(x, 159, z, 4.6, 8, 4.6, 0xdcefee, 0.02);
    }
  }
  // 오늘의 와인 칠판
  lit.box(-100, 141, -200, 36, 34, 2, 0x2e1c14, 0.03);
  room.signs.push({ x: -82, y: 158, z: -197.9, w: 32, h: 30, cw: 40, ch: 38, draw: (g, w, h) => {
    g.fillStyle = '#1f2422'; g.fillRect(0, 0, w, h);
    txt(g, '오늘의 와인', w / 2, 6, '#f0d9a8', 8);
    [['레드', '#e88a8a'], ['화이트', '#eeeadf'], ['로제', '#f2b6c0']].forEach(([m, c], i) => { g.fillStyle = c; g.fillRect(6, 15 + i * 8, 3, 3); txt(g, m, 12, 16 + i * 8, '#eeeadf', 8, 'left'); });
  } });
  // 바 위: 초, 디캔터, 포도
  candle(room, lit, -76, -150);
  lit.cbox(78, TABLE_Y, -158, 10, 3, 10, 0x6b1f2a, 0.04);
  lit.cbox(78, TABLE_Y + 3, -158, 4, 12, 4, 0xdcefee, 0.02);
  for (let i = 0; i < 7; i++) lit.cbox(88 + (i % 3) * 2.2 - (i > 4 ? 1 : 0), TABLE_Y + Math.floor(i / 3) * 2, -152 + (i % 2), 2.4, 2.4, 2.4, 0x5a1f4a, 0.06);
  coasters(lit, 0x2a1a14, 0x2a1a14);
  barStools(lit, 'dark');
  nightWindow(lit, glow, { frame: 0x24160f, curtain: [0x5a1a24, 0x4a141e] });
  // 앞쪽: 오크통 둘 (기댈 수 있는 높이)
  for (const [x, z] of [[-190, 180], [-150, 196]]) {
    for (let y = 0; y < 76; y += 4) {
      const r = 15 + Math.sin((y / 76) * Math.PI) * 3;
      disc(lit, x, y, z, r, 4, 2, (dx, dz, d) => (d > r - 2 ? (y % 24 < 4 ? 0x2a2a2d : 0x6b4229) : 0x5a3622));
    }
    lit.cbox(x, 76, z, 30, 2, 30, 0x4a2e1e, 0.03);
  }
  pendant(shell, glow, 0, 0, 1, 'globe');
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'globe');
  for (const [x, z] of [[-140, 110], [140, -90]]) pendant(shell, glow, x, z, 0.8, 'globe');
}

// ── 5. 크리스마스 산장 ───────────────────────────────────
function logWall(b, side, from, to, y0, y1, thin = 1) {
  for (let y = y0, row = 0; y < y1; y += 10, row++) {
    const c = pick([0x8a5a33, 0x7d5030, 0x94633a, 0x845632], row);
    put(b, side, from, y + 1.2, to - from, 7.6, 3.6 * thin, c, 0.05);
    put(b, side, from, y + 2.4, to - from, 5.2, 5 * thin, c, 0.05);
    put(b, side, from, y, to - from, 1.2, 1.4, 0x3a2414, 0.02);
  }
}

// 크리스마스 트리: 끊김 없는 원뿔에 층층이 처진 가지, 반짝이 줄, 장식 구슬, 꼭대기 별.
// s는 크기(1이면 바닥에서 별까지 2m쯤), 돌려주는 건 반짝이는 전구 자리들
function xmasTree(lit, glow, tx, tz, s = 1, y0 = 0) {
  const step = s < 0.5 ? 1 : 3;
  const greens = [0x2f7a3e, 0x2a6e37, 0x358542, 0x27663a, 0x3d8c47];
  const H = 150 * s, Rmax = 46 * s, base = y0 + 26 * s, tiers = 5;
  // 높이 y에서 가지 끝 반지름: 위로 갈수록 좁아지고, 층마다 아래쪽이 넓게 처진다
  const radius = (y) => {
    const u = (y - base) / H;
    if (u < 0 || u > 1) return 0;
    const f = (u * tiers) % 1;
    return Rmax * (1 - u * 0.94) * (0.6 + 0.4 * Math.pow(1 - f, 1.4));
  };
  // 받침: 빨간 통과 금테, 줄기, 깔개
  if (s >= 0.5) {
    disc(lit, tx, y0, tz, 34 * s, 0.8, 3, (x, z, d) => (d > 30 * s ? 0xf4f6fa : (Math.floor((Math.atan2(z, x) + Math.PI) * 4) % 2 ? 0xb3282e : 0x9a2026)));
  }
  lit.cbox(tx, y0, tz, 18 * s + 2, 12 * s, 18 * s + 2, 0xa8242f, 0.03);
  lit.cbox(tx, y0 + 10 * s, tz, 19 * s + 2, 2 * s + 0.5, 19 * s + 2, 0xd9a92e, 0.02);
  lit.cbox(tx, y0 + 12 * s, tz, 7 * s + 1, 18 * s, 7 * s + 1, 0x5a3a22, 0.04);
  // 가지: 겉껍질만 (속은 보이지 않는다)
  for (let y = base, li = 0; y < base + H; y += step, li++) {
    const r = radius(y);
    if (r < step * 0.6) continue;
    const shell = Math.min(r, step * 1.6);
    for (let z = -r; z < r; z += step) {
      for (let x = -r; x < r; x += step) {
        const d = Math.hypot(x + step / 2, z + step / 2);
        if (d >= r || d < r - shell - step) continue;
        const h = hash3(Math.round(x + tx), li, Math.round(z + tz));
        // 가지 끝은 밝게, 가끔 눈이 살짝 앉은 끝
        const tip = d > r - step;
        const c = tip && h > 0.62 ? 0x4a9a52 : pick(greens, Math.floor(h * 97));
        lit.box(tx + x, y, tz + z, step, step, step, c, 0.05);
      }
    }
  }
  if (s < 0.5) {
    // 작은 트리: 장식 몇 개와 별
    for (const [a, k, c] of [[0.4, 0.15, 0xb3282e], [2.3, 0.3, 0xe0b030], [4.1, 0.45, 0x2f6fb0], [5.5, 0.62, 0xb3282e], [1.3, 0.75, 0xe9e2d3]]) {
      const y = base + H * k, r = radius(y) + 0.6;
      lit.cbox(tx + Math.cos(a) * r, y, tz + Math.sin(a) * r, 2, 2, 2, c, 0);
    }
    glow.cbox(tx, base + H + 1, tz, 3.4, 3.4, 1.4, 0xffd84a, 0);
    return [];
  }
  // 반짝이 줄 (금빛, 나선)
  for (let k = 0; k < 900; k++) {
    const u = k / 900, y = base + 6 + u * (H - 14);
    const a = u * Math.PI * 2 * 4.2 + 0.6;
    const r = radius(y) + 0.8;
    if (r < 3) continue;
    lit.box(tx + Math.cos(a) * r - 1, y, tz + Math.sin(a) * r - 1, 2, 1.4, 2, k % 3 ? 0xd9a92e : 0xf2d26a, 0.02);
  }
  // 장식 구슬: 층의 처진 끝 가까이 (반짝이는 흰 점 하나씩)
  const colors = [0xc0283a, 0xd9a92e, 0x2f6fb0, 0xd8dde3, 0x8a2a8a, 0xc0283a];
  for (let i = 0; i < 30; i++) {
    const u = 0.05 + (i / 30) * 0.86 + hash3(i, 3, 41) * 0.03;
    const tierBottom = Math.floor(u * tiers) / tiers + 0.06 + hash3(i, 5, 41) * 0.08;
    const y = base + Math.min(u, tierBottom + 0.12) * H;
    const a = i * 2.39996 + hash3(i, 7, 41);
    const r = radius(y) + 1.2;
    const x = tx + Math.cos(a) * r, z = tz + Math.sin(a) * r;
    const c = pick(colors, i);
    lit.cbox(x, y - 4, z, 4.4, 4.4, 4.4, c, 0.02);
    lit.cbox(x, y + 0.4, z, 1.6, 1.4, 1.6, 0xc9a24a, 0);
    lit.cbox(x + Math.cos(a) * 1.8, y - 1.6, z + Math.sin(a) * 1.8, 1, 1, 1, 0xffffff, 0);
  }
  // 꼭대기 별: 방 가운데를 보는 다섯 꼭지 별
  const top = base + H + 2;
  const ang = Math.atan2(-tx, -tz);
  const ux = Math.cos(ang), uz = -Math.sin(ang);
  const R1 = 9.5, R2 = 4.2;
  const inStar = (px, py) => {
    // 별 모양 안인지: 각도에 따라 바깥/안쪽 반지름 사이를 잇는다
    const t = Math.atan2(px, py), d = Math.hypot(px, py);
    const k = ((t / (Math.PI * 2 / 5)) % 1 + 1) % 1;
    const tri = Math.abs(k - 0.5) * 2;
    return d < R2 + (R1 - R2) * Math.pow(tri, 1.6);
  };
  for (let py = -R1; py <= R1; py += 1.4) {
    for (let pu = -R1; pu <= R1; pu += 1.4) {
      if (!inStar(pu + 0.7, py + 0.7)) continue;
      const edge = !inStar(pu + 0.7, py + 0.7 - 1.8) || !inStar(pu + 0.7 + 1.8, py + 0.7) || !inStar(pu + 0.7 - 1.8, py + 0.7);
      glow.box(tx + ux * pu - 0.7, top + R1 + py, tz + uz * pu - 0.7, 1.4, 1.4, 1.4, edge ? 0xffb830 : 0xffe680, 0);
    }
  }
  lit.cbox(tx, top - 2, tz, 2, 6, 2, 0xc9a24a, 0);
  // 반짝이는 전구 줄: 반짝이 줄과 엇갈린 나선
  const bulbs = [];
  for (let k = 0; k < 64; k++) {
    const u = (k + 0.5) / 64, y = base + 4 + u * (H - 12);
    const a = -u * Math.PI * 2 * 5.5;
    const r = radius(y) + 0.6;
    if (r < 3) continue;
    bulbs.push([tx + Math.cos(a) * r, y, tz + Math.sin(a) * r]);
  }
  return bulbs;
}

function buildXmas(room, lit, shell, glow) {
  outerShell(lit, 0x5a3a22);
  plankFloor(lit, [0x9a6a40, 0x8f6240, 0xa37148, 0x926341], { w: 22 });
  room.rug = 'plaid';
  // 뒷벽은 선반과 칠판이 붙도록 통나무를 얕게
  for (const [side, from, to] of [['back', -240, 240], ['front', -240, 240], ['left', -200, -52], ['left', 52, 220], ['right', -200, 220]]) logWall(lit, side, from, to, 0, 250, side === 'back' ? 0.3 : 1);
  // 통나무 끝 (모서리)
  for (const [x, z] of [[-240, -200], [234, -200], [-240, 214], [234, 214]]) for (let y = 0; y < 250; y += 20) lit.box(x, y + 1, z, 6, 8, 6, 0x6b4428, 0.05);
  shell.box(-248, 258, -208, 496, 8, 436, 0x4a2e1a, 0);
  for (let x = -220; x <= 220; x += 55) shell.box(x - 5, 244, -208, 10, 14, 436, 0x6b4428, 0.05);
  // 들보 아래 늘어진 솔잎 장식과 붉은 리본
  for (let z = -180; z <= 200; z += 95) {
    for (let x = -220; x <= 220; x += 10) {
      const sag = Math.abs(Math.sin(((x + 220) / 110) * Math.PI)) * 10;
      shell.box(x - 3, 240 - sag, z - 3, 8, 6, 6, (x / 10) % 2 ? 0x2f6b3a : 0x24582f, 0.06);
    }
    for (let x = -220; x <= 220; x += 110) { shell.box(x - 4, 236, z - 4, 8, 8, 8, 0xb3282e, 0.03); shell.box(x - 2, 228, z - 2, 4, 8, 4, 0xb3282e, 0.03); }
  }
  buildBar(lit, glow, {
    topBase: 0x4a2f1a, top: [0x7a4d2c, 0x6e4528, 0x85552f], topStep: 12, trim: 0x4a2f1a,
    frontKind: 'logs', front: [0x7d5030, 0x6e4528, 0x845632], kick: 0x3a2414, rail: 0xc9a24a, side: 0x6e4528,
    back: 0x4a2f1a, backTop: 0x6b4428, door: 0x5a3a22,
  });
  // 바 앞 솔잎 띠와 장식 구슬
  for (let x = -96; x < 96; x += 6) lit.box(x, TABLE_Y - 9, -127, 7, 5, 4, (x / 6) % 2 ? 0x2f6b3a : 0x24582f, 0.06);
  for (let x = -84; x < 90; x += 24) lit.box(x, TABLE_Y - 14, -125.6, 3.4, 3.4, 3.4, pick([0xb3282e, 0xe0b030, 0x2f6fb0], x / 24), 0.02);
  backShelves(lit, glow, { plank: 0x6b4428, bracket: 0x4a2f1a, light: 0xffc982, colors: [0x2f5d3a, 0xa45a1c, 0x7a1c2b, 0x3f8f4f, 0x6b3a12, 0xe9e2d3, 0xb3282e] });
  tallyBoard(lit, { frame: 0x6b4428, face: 0x2f3a33 });
  for (let i = 0; i < 9; i++) lit.box(-100 + i * 4.4, 138, -197.6, 4, 3, 2, i % 3 === 1 ? 0xb3282e : 0x2f6b3a, 0.05);
  // 선반 위 현수막
  room.signs.push({ x: 0, y: 170, z: -198.4, w: 112, h: 11, cw: 112, ch: 11, draw: (g, w, h) => {
    g.fillStyle = '#a8242f'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#7d1a22'; g.fillRect(0, h - 1, w, 1);
    for (let x = 2; x < w; x += 6) { g.fillStyle = '#e0b030'; g.fillRect(x, 1, 1, 1); g.fillRect(x + 3, h - 2, 1, 1); }
    txt(g, 'MERRY CHRISTMAS', w / 2, h / 2 + 0.5, '#fdf6e6', 11);
  } });
  for (const x of [-58, 58]) for (let i = 0; i < 4; i++) lit.box(x - 1, 161 + i * 2.6, -198.6, 3, 2.6, 2, i % 2 ? 0x2f6b3a : 0x24582f, 0.06);
  // 뒤 작업대 오른쪽 작은 트리
  room.twinkleSmall = xmasTree(lit, glow, 82, -192, 0.24, 67);
  // 바 위: 초, 진저브레드 집
  candle(room, lit, -76, -150, 0xb3282e);
  const gx = 78, gz = -158, T = TABLE_Y;
  lit.cbox(gx, T, gz, 18, 1, 14, 0xe9e2d3, 0.02);
  lit.cbox(gx, T + 1, gz, 14, 10, 10, 0xa8642e, 0.04);
  for (let i = 0; i < 4; i++) lit.cbox(gx, T + 11 + i * 2, gz, 16 - i * 4, 2, 12, i === 0 ? 0xf4f6fa : 0x8a4f22, 0.03);
  lit.cbox(gx, T + 1, gz + 5, 3, 5, 0.6, 0x5a2a12, 0);
  for (const dx of [-4, 4]) lit.cbox(gx + dx, T + 5, gz + 5, 2.4, 2.4, 0.6, 0xe0b030, 0);
  coasters(lit, 0xb3282e, 0x2f6b3a);
  barStools(lit, 'xmas');
  // 돌 벽난로와 양말
  fireplace(lit, glow, room, [0x7a7670, 0x8c8780, 0x6a6660, 0x908a82]);
  for (let z = -56; z <= 56; z += 6) lit.box(-226, 116, z, 8, 6, 7, (z / 6) % 2 ? 0x2f6b3a : 0x24582f, 0.06);
  for (const z of [-34, 0, 34]) {
    lit.box(-225, 94, z - 4, 3, 16, 8, 0xb3282e, 0.03);
    lit.box(-225, 92, z - 4, 3, 5, 13, 0xb3282e, 0.03);
    lit.box(-225.4, 106, z - 4.4, 3.8, 5, 8.8, 0xf4f6fa, 0.03);
  }
  nightWindow(lit, glow, { frame: 0x5a3a22, curtain: [0x2f6b3a, 0x24582f], sky: 0x22304e, snow: true });
  room.snowWindow = true;
  // 크리스마스 트리 (왼쪽 앞 구석)
  const tx = -178, tz = 158;
  room.twinkle = xmasTree(lit, glow, tx, tz, 1);
  // 선물 상자
  // (방 가운데 쪽, 가지 밖으로 나오게)
  for (const [dx, dz, w, h, c, r] of [[42, -10, 16, 13, 0x2f6fb0, 0xe0b030], [30, -34, 13, 17, 0xb3282e, 0xf4f6fa], [52, 14, 12, 9, 0x2f6b3a, 0xb3282e], [10, -48, 11, 8, 0xe0b030, 0xb3282e], [58, -28, 9, 12, 0xe9e2d3, 0xb3282e]]) {
    lit.cbox(tx + dx, 0, tz + dz, w, h, w, c, 0.03);
    lit.cbox(tx + dx, 0, tz + dz, 2.4, h + 0.4, w + 0.4, r, 0.01);
    lit.cbox(tx + dx, 0, tz + dz, w + 0.4, h + 0.4, 2.4, r, 0.01);
    lit.cbox(tx + dx, h, tz + dz, 5, 3, 5, r, 0.03);
  }
  // 앞벽 문에 걸린 화환
  for (let x = -40, i = 0; x < 40; x += 10, i++) lit.box(x, 0, 214, 10, 200, 6, i % 2 ? 0x6e4528 : 0x7d5030, 0.04);
  lit.box(28, 96, 210, 4, 6, 4, 0xc9a24a, 0.02);
  for (let a = 0; a < 24; a++) {
    const ang = (a / 24) * Math.PI * 2;
    lit.box(Math.cos(ang) * 16 - 3, 150 + Math.sin(ang) * 16 - 3, 208, 7, 7, 6, a % 2 ? 0x2f6b3a : 0x24582f, 0.06);
  }
  lit.box(-5, 128, 206, 10, 8, 4, 0xb3282e, 0.03);
  pendant(shell, glow, 0, 0, 1, 'lantern', 244);
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'lantern', 244);
}

// ── 6. 다락방 아지트 ─────────────────────────────────────
// 지붕: 양옆 벽은 낮고(100), 가운데로 갈수록 높아진다(260)
const roofY = (x) => 100 + (240 - Math.min(240, Math.abs(x))) * (160 / 240);

function buildAttic(room, lit, shell, glow) {
  outerShell(lit, 0x2a2420);
  plankFloor(lit, [0xb98f60, 0xc49a6c, 0xae8558, 0xbf9566], { w: 20 });
  room.rug = 'shag';
  const pine = [0xc49a6c, 0xb98f60, 0xcca474, 0xbd9364];
  // 앞뒤 박공벽: 세로 판자를 지붕선까지
  for (const side of ['back', 'front']) {
    for (let u = W.x0, i = 0; u < W.x1; u += 12, i++) put(lit, side, u, 0, 12, roofY(u + 6) + 4, 1.4, pick(pine, i), 0.04);
    put(lit, side, W.x0, 0, W.x1 - W.x0, 8, 2.2, 0x7a5636, 0.02);
  }
  // 낮은 옆벽
  for (const side of ['left', 'right']) {
    for (let u = W.z0, i = 0; u < W.z1; u += 12, i++) put(lit, side, u, 0, 12, 104, 1.4, pick(pine, i + 1), 0.04);
    put(lit, side, W.z0, 0, W.z1 - W.z0, 8, 2.2, 0x7a5636, 0.02);
  }
  // 경사 지붕 판자, 서까래, 용마루
  for (let x = W.x0, i = 0; x < W.x1; x += 12, i++) {
    const y = roofY(x + 6);
    shell.box(x, y, W.z0 - 8, 12, 6, W.z1 - W.z0 + 16, pick([0x9a7048, 0x8a6440, 0x94693f], i), 0.03);
  }
  for (let z = -190; z <= 210; z += 50) {
    for (let x = W.x0; x < W.x1; x += 6) shell.box(x, roofY(x + 3) - 7, z, 6, 7, 6, 0x6b4a2e, 0.02);
  }
  shell.box(-6, 252, W.z0, 12, 10, W.z1 - W.z0, 0x5a3e26, 0.02);
  // 지붕 창 (오른쪽 경사면): 별
  for (let x = 140; x < 200; x += 6) glow.box(x, roofY(x + 3) - 1, -40, 6, 1, 80, 0x18213e, 0);
  for (let i = 0; i < 18; i++) {
    const x = 142 + hash3(i, 1, 71) * 54;
    glow.box(x, roofY(x) - 1.6, -36 + hash3(i, 2, 71) * 72, 0.9, 0.6, 0.9, i % 3 ? 0xcfd8ff : 0xfff3c4, 0);
  }
  for (const z of [-42, 40]) for (let x = 138; x < 202; x += 6) shell.box(x, roofY(x + 3) - 4, z, 6, 4, 2, 0x5a3e26, 0.02);
  // 앞 박공 위 동그란 창 (달)
  for (let y = -16; y < 16; y += 2) for (let x = -16; x < 16; x += 2) {
    const d = Math.hypot(x + 1, y + 1);
    if (d < 16) glow.box(x, 196 + y, 218, 2, 2, 0.5, d < 6 && x > -4 ? 0xf6e7b0 : 0x1d2a4a, 0);
    else if (d < 19) lit.box(x, 196 + y, 216.6, 2, 2, 3, 0x7a5636, 0.02);
  }
  // DIY 바: 팔레트 앞판, 두꺼운 상판
  buildBar(lit, glow, {
    topBase: 0x8a5a33, top: [0xb07a48, 0xa8723f, 0xb8824e], topStep: 8, trim: 0x7a5636,
    frontKind: 'pallet', front: [0xc9a273, 0xb88f62, 0xbf9866], kick: 0x5a3e26, rail: null, side: 0xb88f62,
    back: 0x6b4a2e, backTop: 0x8a6440, door: 0x7a5636,
  });
  // 뒤: 작은 냉장고, 맥주 상자, 보라 네온 띠
  lit.box(-96, 0, -200, 30, 60, 16, 0xe8e8e8, 0.02);
  lit.box(-70, 30, -184.2, 2, 20, 1.6, 0xb9bec2, 0);
  lit.box(-96, 30, -184.4, 30, 0.8, 0.6, 0xc8c8c8, 0);
  for (let i = 0; i < 4; i++) lit.cbox(28 + i * 8, 67, -193, 5, 13, 5, pick([0x6b3a12, 0x2f5d3a], i), 0.03);
  glow.box(-96, 66.6, -185, 192, 0.8, 0.8, 0xa25bff, 0);
  glow.box(-62, 99.4, -199.3, 124, 0.6, 0.8, 0x4ad8ff, 0);
  backShelves(lit, glow, { plank: 0x8a6440, bracket: 0x7a5636, light: null, colors: [0x2f5d3a, 0xa45a1c, 0x7a1c2b, 0x6b3a12, 0xe9e2d3, 0x2f6fb0], kind: 'attic' });
  // 선반 위: 직접 칠한 나무 간판 (삐뚤게 박은 판자 두 장)
  for (const [y, w, c] of [[160, 74, 0x9a6a3a], [171, 70, 0x8a5e32]]) lit.box(-w / 2, y, -199.4, w, 10, 1.6, c, 0.05);
  for (const [x, y] of [[-33, 164], [33, 164], [-31, 175], [31, 175]]) lit.cbox(x, y, -197.6, 1.4, 1.4, 1, 0x3a3a40, 0);
  room.signs.push({ x: 0, y: 170, z: -197.7, w: 72, h: 20, cw: 108, ch: 30, glow: true, draw: (g, w, h) => {
    // 판자 두 장 (나뭇결). 앞에 걸린 전구 빛을 받아 잘 보이게 그림자 없는 판으로
    g.fillStyle = '#2a1c12'; g.fillRect(0, 0, w, h);
    for (const [y0, hh, c] of [[1, h / 2 - 1, '#7a5230'], [h / 2 + 1, h / 2 - 2, '#6e4a2a']]) {
      g.fillStyle = c; g.fillRect(2, y0, w - 4, hh);
      g.fillStyle = 'rgba(60,36,18,0.35)';
      for (let k = 0; k < 4; k++) g.fillRect(4 + k * 23, y0 + 2 + (k % 2) * 4, 14, 1);
    }
    g.fillStyle = '#5a5a60';
    for (const [x, y] of [[5, 4], [w - 6, 4], [5, h - 6], [w - 6, h - 6]]) g.fillRect(x, y, 2, 2);
    g.save();
    g.translate(w / 2, h / 2);
    g.rotate(-0.05);
    txt(g, '회원 전용!', -16, -7, '#e8dcc4', 13);
    g.rotate(0.09);
    txt(g, '출입 금지', 20, 7, '#e0503e', 11);
    g.restore();
    // 붓 자국
    g.fillStyle = 'rgba(244,240,230,0.5)';
    g.fillRect(8, h - 5, 22, 1);
  } });
  // 지붕선 따라 LED 띠
  for (const side of ['back', 'front']) {
    for (let x = W.x0; x < W.x1; x += 6) put(glow, side, x, roofY(x + 3) - 4.5, 6, 1, 0.6, side === 'back' ? 0xa25bff : 0x5af0ff, 0, 1.5);
  }
  // 맥주캔 피라미드, 게임 패드
  for (let r = 0; r < 4; r++) for (let i = 0; i < 4 - r; i++) lit.cbox(28 + r * 2.8 + i * 5.6, 67 + r * 7.2, -193, 5, 7, 5, pick([0xc0392b, 0x2f6fb0, 0xe0b030, 0xe9e2d3], r + i), 0.02);
  lit.cbox(60, TABLE_Y, -160, 12, 2.2, 7, 0x2a2a2e, 0.02);
  lit.cbox(56, TABLE_Y + 2.2, -160, 2, 0.8, 2, 0x5af0ff, 0);
  lit.cbox(64, TABLE_Y + 2.2, -161, 1.4, 0.8, 1.4, 0xc0392b, 0);
  lit.cbox(66, TABLE_Y + 2.2, -159, 1.4, 0.8, 1.4, 0x7ac74f, 0);
  // 코르크판 (점수판)
  tallyBoard(lit, { frame: 0x8a6440, face: 0xb88a5a });
  // 다트판과 포스터
  for (let y = -12; y < 12; y += 2) for (let x = -12; x < 12; x += 2) {
    const d = Math.hypot(x + 1, y + 1);
    if (d < 12) lit.box(-140 + x, 136 + y, -200, 2, 2, 2 + (d < 2 ? 1 : 0), d < 2 ? 0xc0392b : d < 5 ? 0x2f8f4e : d < 8 ? 0xe9e2d3 : d < 10 ? 0x1c1c1e : 0xc0392b, 0.02);
  }
  put(lit, 'back', 104, 112, 30, 40, 1, 0xf2ede0, 0.02);
  put(lit, 'back', 107, 115, 24, 34, 1.2, 0x2f6fb0, 0.02);
  put(lit, 'back', 113, 124, 12, 12, 1.4, 0xe0b030, 0.02);
  put(lit, 'back', 111, 140, 16, 3, 1.4, 0xf2ede0, 0.02);
  // 왼쪽 낮은 벽: 소파와 빈백
  lit.box(-236, 0, -70, 34, 24, 120, 0x3a4a5a, 0.03);
  lit.box(-236, 24, -70, 12, 26, 120, 0x34424f, 0.03);
  for (const z of [-66, -26, 14]) lit.box(-222, 24, z, 18, 6, 36, 0x44566a, 0.04);
  for (const z of [-70, 46]) lit.box(-236, 24, z, 34, 14, 4, 0x34424f, 0.03);
  lit.box(-214, 30, -50, 8, 12, 12, 0xd29b2e, 0.04);
  disc(lit, -190, 0, 120, 20, 22, 3, (dx, dz, d) => (d > 17 ? 0x8a4a9a : 0x9a5aaa));
  disc(lit, -190, 22, 122, 13, 6, 3, () => 0x9a5aaa);
  // 바 왼쪽 끝: 조명 스탠드
  lit.cbox(-78, TABLE_Y, -152, 8, 1.4, 8, 0x2a2a2e, 0.02);
  lit.cbox(-78, TABLE_Y + 1.4, -152, 1.4, 18, 1.4, 0x2a2a2e, 0);
  lit.cbox(-76, TABLE_Y + 18, -152, 8, 5, 6, 0xd29b2e, 0.02);
  glow.cbox(-76, TABLE_Y + 17.4, -152, 5, 0.6, 4, 0xfff0c8, 0);
  coasters(lit, 0xd29b2e, 0x2f8f8a);
  barStools(lit, 'pine');
  pendant(shell, glow, 0, 0, 1, 'paper', 252);
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'paper', roofY(x) + 2);
}

// 가게마다: 짓는 함수, 불빛, 탁자, 전구 줄, 가게 소리
// ── 7. 이자카야 ─────────────────────────────────────────
// 꼬치 이름표: 나무판에 세로 글씨
const IZA_MENU = [['닭꼬치', '2.5'], ['염통', '2.5'], ['껍질', '2.0'], ['츠쿠네', '3.0'], ['대파', '2.0'], ['은행', '2.0'], ['연골', '2.5'], ['하이볼', '7.0'], ['사케', '9.0']];
function izaStrip(name, price, i) {
  return (g, w, h) => {
    g.fillStyle = i % 2 ? '#d8b98a' : '#cfae7c'; g.fillRect(0, 0, w, h);
    g.fillStyle = 'rgba(90,60,30,0.25)'; g.fillRect(0, 0, 1, h); g.fillRect(w - 1, 0, 1, h);
    const chars = [...name];
    const step = Math.min(13, (h - 20) / chars.length);
    chars.forEach((c, k) => txt(g, c, w / 2, 9 + k * step, i === 7 || i === 8 ? '#a8241e' : '#2a1a10', 12));
    txt(g, price, w / 2, h - 7, '#a8241e', 8);
  };
}

// 창호지 미닫이 (뒤에서 빛이 비친다)
function shoji(lit, glow, side, a, b, y0 = 100, h = 84) {
  put(glow, side, a, y0, b - a, h, 0.4, 0xf3dfb0, 0, 0.5);
  for (let u = a; u <= b; u += 10) put(lit, side, Math.min(u, b - 1.6), y0, 1.6, h, 0.8, 0x3a2414, 0.02, 0.9);
  for (let y = y0; y <= y0 + h; y += 14) put(lit, side, a, Math.min(y, y0 + h - 1.6), b - a, 1.6, 0.8, 0x3a2414, 0.02, 0.9);
}

function buildIzakaya(room, lit, shell, glow) {
  outerShell(lit, 0x2a1c14);
  plankFloor(lit, [0x4a3020, 0x52361f, 0x45301e, 0x4e3422], { w: 16 });
  room.rug = null;
  // 아래는 짙은 판벽, 위는 흰 회벽에 기둥과 들보
  for (const [side, from, to] of [['back', -240, 240], ['front', -240, 240], ['left', -200, 220], ['right', -200, 220]]) {
    for (let u = from, i = 0; u < to; u += 10, i++) put(lit, side, u, 0, Math.min(10, to - u), 90, 1.4, pick([0x3a2618, 0x422c1c, 0x36231a], i), 0.04);
    put(lit, side, from, 90, to - from, 4, 3, 0x24160e, 0.02);
    put(lit, side, from, 94, to - from, 166, 0.4, 0xe8dcc0, 0.02);
    for (let u = from; u <= to - 6; u += 60) put(lit, side, u, 0, 6, 260, 3.4, 0x2a1a10, 0.03);
    put(lit, side, from, 206, to - from, 6, 3.4, 0x2a1a10, 0.03);
  }
  shell.box(-248, 260, -208, 496, 6, 436, 0x2a1c14, 0);
  for (let x = -220; x <= 220; x += 44) shell.box(x - 4, 248, -208, 8, 12, 436, 0x3a2618, 0.04);
  // 옆벽 창호지 미닫이
  for (const [side, a, b] of [['left', -170, -70], ['left', 70, 170], ['right', -170, -70], ['right', 10, 110]]) shoji(lit, glow, side, a, b);
  // 카운터: 밝은 편백 상판, 세로 살 앞판
  buildBar(lit, glow, {
    topBase: 0xb08a5a, top: [0xd8b98a, 0xd2b282, 0xdcbf92], topStep: 14, topJitter: 0.02, trim: 0x8a6a42,
    front: [0x4e321f, 0x5a3a24], kick: 0x2a1a10, rail: null, side: 0x5a3a24,
    back: 0x3a2618, backTop: 0x5a4030, door: 0x4a3020,
  });
  // 카운터 너머 숯불 화로: 달아오른 숯, 석쇠 (꼬치는 decor에서 돌려 굽는다)
  lit.box(-74, 67, -199, 62, 7, 13, 0x3a3a3d, 0.02);
  lit.box(-74, 74, -199, 62, 1, 1.4, 0x2a2a2d, 0);
  lit.box(-74, 74, -187.4, 62, 1, 1.4, 0x2a2a2d, 0);
  for (let i = 0; i < 46; i++) glow.box(-72 + hash3(i, 1, 71) * 56, 73, -197.6 + hash3(i, 2, 71) * 9, 2.2, 0.8, 2.2, pick([0xff5a1a, 0xff8a2a, 0xc0300e, 0xffb040], i), 0);
  for (let x = -72; x < -14; x += 2.6) lit.box(x, 74.6, -198, 0.5, 0.5, 11, 0x1c1c1e, 0);
  room.smoke.push(new THREE.Vector3(-44, 78, -193));
  room.grillPos = new THREE.Vector3(-44, 80, -186);
  room.grillAt = { x0: -70, x1: -18, y: 75.4, z: -193 };
  // 뒤 선반: 됫병 사케와 소주
  backShelves(lit, glow, { plank: 0x5a4030, bracket: 0x3a2618, light: 0xffd9a0, colors: [0x2f5d3a, 0x6b3a12, 0xd8e4dc, 0x1d2e1a, 0x8a5a2a], rows: [96, 122] });
  tallyBoard(lit, { frame: 0x3a2618, face: 0x2a3028 });
  // 꼬치 이름표 (나무판 줄)
  lit.box(-66, 182, -199, 132, 1, 1, 0x2a1a10, 0);
  IZA_MENU.forEach(([name, price], i) => {
    const x = -58 + i * 13.5 + 5.75;
    room.signs.push({ x, y: 159, z: -198.4, w: 11.5, h: 42, cw: 16, ch: 60, draw: izaStrip(name, price, i) });
  });
  coasters(lit, 0xd8b98a, 0xcfae7c);
  barStools(lit, 'hinoki');
  pendant(shell, glow, 0, 0, 1.1, 'paper', 248);
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'paper', 248);
  for (const [x, z] of [[-150, 110], [150, -80], [-150, -80]]) pendant(shell, glow, x, z, 0.9, 'paper', 248);
  // 앞 출입구: 미닫이 문틀과 남색 노렌
  lit.box(-44, 0, 214, 88, 4, 6, 0x2a1a10, 0.02);
  lit.box(-46, 0, 214, 4, 200, 6, 0x2a1a10, 0.02);
  lit.box(42, 0, 214, 4, 200, 6, 0x2a1a10, 0.02);
  lit.box(-46, 196, 212, 92, 6, 8, 0x2a1a10, 0.02);
  for (let i = 0; i < 4; i++) lit.box(-42 + i * 21, 150, 210, 20, 46, 1.2, 0x23305a, 0.03);
  lit.box(-46, 194, 209.4, 92, 2.4, 2.4, 0x6b4a2a, 0.02);
  room.signs.push({ x: 0, y: 172, z: 209.2, yaw: Math.PI, w: 84, h: 20, cw: 84, ch: 20, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    g.fillStyle = '#f4f0e6';
    g.beginPath(); g.arc(w / 2, h / 2, 7, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#23305a';
    txt(g, '酒', w / 2, h / 2 + 0.5, '#23305a', 10);
  } });
  glow.box(-38, 6, 219, 76, 140, 0.4, 0x1a1e2e, 0);
}

// ── 도시 야경: 멀리 늘어선 빌딩과 창문 불빛 ─────────────────────
// side: 'ring'(옥상에서 사방), 'front'(길 건너)
function skyline(lit, glow, { ring = false, near = 360, far = 560, base = -260, seed = 3, count = 46 } = {}) {
  for (let i = 0; i < count; i++) {
    const h1 = hash3(i, 1, seed), h2 = hash3(i, 2, seed), h3 = hash3(i, 3, seed);
    let cx, cz, faceX = 0, faceZ = 1;
    if (ring) {
      const a = (i / count) * Math.PI * 2 + h1 * 0.08;
      const r = near + h2 * (far - near);
      cx = Math.sin(a) * r * 1.15; cz = Math.cos(a) * r;
      // 방 쪽 면에만 창문을 단다
      if (Math.abs(cx) / 1.15 > Math.abs(cz)) { faceX = -Math.sign(cx); faceZ = 0; } else { faceX = 0; faceZ = -Math.sign(cz); }
    } else {
      cx = -620 + (i / count) * 1240 + h1 * 20;
      cz = near + h2 * (far - near);
      faceZ = -1;
    }
    const w = 40 + h3 * 60, d = 40 + h1 * 40, top = 60 + Math.pow(h2, 1.6) * 360 + (ring ? 0 : -80);
    lit.box(cx - w / 2, base, cz - d / 2, w, top - base, d, pick([0x1a1e2a, 0x20242e, 0x161a24, 0x242a36], i), 0.03);
    // 옥상 빨간 경고등
    if (h3 > 0.6) glow.cbox(cx, top, cz, 2, 2, 2, 0xff3a3a, 0);
    // 창문
    const fx = faceX !== 0;
    const span = fx ? d : w;
    for (let y = base + 30; y < top - 6; y += 9) {
      for (let u = -span / 2 + 4; u < span / 2 - 4; u += 7) {
        const r = hash3(Math.round(u * 3) + i * 97, Math.round(y), seed + 5);
        if (r > 0.42) continue;
        const c = r < 0.06 ? 0xbfe0ff : r < 0.3 ? 0xffd890 : 0xffe9b8;
        if (fx) glow.box(cx + faceX * (w / 2 + 0.3) - 0.2, y, cz + u, 0.4, 4, 4, c, 0);
        else glow.box(cx + u, y, cz + faceZ * (d / 2 + 0.3) - 0.2, 4, 4, 0.4, c, 0);
      }
    }
  }
}

function nightSky(glow, { moon = [-260, 330, -700], stars = 120, y0 = 260 } = {}) {
  for (let i = 0; i < stars; i++) {
    const a = hash3(i, 1, 13) * Math.PI * 2, r = 500 + hash3(i, 2, 13) * 300;
    glow.cbox(Math.sin(a) * r, y0 + 60 + hash3(i, 3, 13) * 320, Math.cos(a) * r, 1.6, 1.6, 1.6, i % 5 ? 0xcfd8ff : 0xfff3c4, 0);
  }
  const [mx, my, mz] = moon;
  for (let y = -16; y < 16; y += 2) for (let x = -16; x < 16; x += 2) {
    const d = Math.hypot(x + 1, y + 1);
    if (d < 16) glow.box(mx + x, my + y, mz, 2, 2, 1, d > 13 ? 0xe8dcb0 : (x + 4) * (x + 4) + (y - 3) * (y - 3) < 20 ? 0xd8cca0 : 0xf6ecc4, 0);
  }
}

// ── 8. 루프탑 바 ────────────────────────────────────────
function buildRooftop(room, lit, shell, glow) {
  lit.box(-252, -8, -212, 504, 2, 444, 0x14110e, 0);
  plankFloor(lit, [0x6a4a34, 0x5e422e, 0x725038, 0x654630], { w: 14 });
  room.rug = null;
  // 난간: 낮은 콘크리트 벽 위에 쇠 기둥과 손잡이 (바 뒤 벽 자리는 비운다)
  const parts = [['front', -240, 240], ['left', -200, 220], ['right', -200, 220], ['back', -240, -130], ['back', 130, 240]];
  for (const [side, from, to] of parts) {
    put(lit, side, from, 0, to - from, 34, 10, 0x8a8a86, 0.03);
    put(lit, side, from, 34, to - from, 3, 12, 0xa8a8a2, 0.02);
    for (let u = from + 4; u < to - 2; u += 30) put(lit, side, u, 37, 2, 56, 2, 0x2a2a2e, 0.02, 4);
    put(lit, side, from, 92, to - from, 2.4, 3, 0x3a3a40, 0.02, 3.4);
    // 유리 난간 (살짝 비치는 띠)
    put(glow, side, from, 40, to - from, 0.6, 0.3, 0x5a7090, 0, 5);
  }
  // 바 뒤 벽: 나무 살과 백라이트 선반
  lit.box(-130, 0, -210, 260, 182, 10, 0x2a2a30, 0.02);
  for (let x = -126, i = 0; x < 126; x += 8, i++) lit.box(x, 0, -200, 7, 178, 1.6, pick([0x5a3e2c, 0x4e3626, 0x634430], i), 0.04);
  lit.box(-130, 178, -201, 260, 4, 3, 0x1c1c22, 0.02);
  // 바 위 퍼걸러
  for (const x of [-118, 112]) for (const z of [-200, -128]) lit.box(x, 0, z, 6, 214, 6, 0x2a2a2e, 0.02);
  for (const z of [-200, -128]) shell.box(-124, 214, z, 248, 6, 6, 0x2a2a2e, 0.02);
  for (let x = -116; x < 118; x += 14) shell.box(x, 220, -204, 4, 3, 82, 0x5a3e2c, 0.03);
  buildBar(lit, glow, {
    topBase: 0x1c1c22, top: [0x2a2a32, 0x30303a, 0x26262e], topStep: 24, topJitter: 0.015, trim: 0xc9a24a,
    front: [0x3a3a44, 0x34343e], kick: 0x1c1c22, rail: 0xc9a24a, side: 0x34343e,
    back: 0x24242c, backTop: 0x3a3a44, door: 0x2e2e36, knob: 0xc9a24a,
  });
  glow.box(BAR.x0, 8, BAR.z1 - 0.5, BAR.x1 - BAR.x0, 0.8, 0.6, 0xff5ad0, 0);
  backShelves(lit, glow, { plank: 0x1c1c22, bracket: 0x2a2a30, light: 0xff8ad8, colors: [0x2f5d3a, 0xa45a1c, 0xcfe3e0, 0x7a1c2b, 0x3f8f4f, 0xe9e2d3, 0x2f6fb0, 0xc9a24a] });
  tallyBoard(lit, { frame: 0x1c1c22, face: 0x2a3028 });
  room.signs.push({ x: 0, y: 160, z: -198.2, w: 76, h: 18, cw: 76, ch: 18, glow: true, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    neon(g, 'SKY LOUNGE', w / 2, h / 2, '#ff6ad8', 11);
  } });
  coasters(lit, 0x2a2a32, 0x2a2a32);
  barStools(lit, 'dark');
  // 도시 불빛과 하늘
  skyline(lit, glow, { ring: true, near: 380, far: 600, base: -300, seed: 7, count: 54 });
  nightSky(glow, { moon: [-300, 360, -760] });
  // 덱 위로 늘어진 전구 줄: 네 모서리 기둥 사이
  const poles = [[-226, -110], [226, -110], [-226, 206], [226, 206]];
  for (const [x, z] of poles) lit.box(x - 2, 0, z - 2, 4, 214, 4, 0x2a2a2e, 0.02);
  const strand = (a, b) => {
    const n = 30;
    for (let k = 0; k <= n; k++) {
      const u = k / n, x = a[0] + (b[0] - a[0]) * u, z = a[1] + (b[1] - a[1]) * u;
      const y = 210 - Math.sin(u * Math.PI) * 26;
      shell.cbox(x, y, z, 2, 0.6, 2, 0x1c1c1e, 0);
      if (k % 2 === 0) glow.cbox(x, y - 3, z, 3, 3.4, 3, 0xffd890, 0);
    }
  };
  strand(poles[0], poles[3]); strand(poles[1], poles[2]); strand(poles[0], poles[1]); strand(poles[2], poles[3]);
  pendant(shell, glow, 0, 0, 1, 'globe', 214);
  for (const x of [-52, 0, 52]) pendant(shell, glow, x, -150, 0.7, 'globe', 214);
}

// ── 9. 편의점 앞 파라솔 ──────────────────────────────────
// ── 편의점 안 ───────────────────────────────────────────
// 창 너머로 보이는 진짜 가게 안. 형광등 아래 고르게 밝은 곳이라 바깥 불빛을 받지 않는 재질(면 방향으로만 명암)로 따로 짓는다.
// 앞면 안쪽 z -210부터 안쪽 벽 z -560까지, 폭은 바깥 벽과 같게.
const CV = {
  wall: 0xe6e9ea, floorA: 0xd8d6d0, floorB: 0xcfccc5, ceil: 0xeeefec, frame: 0x30343a, shelf: 0xc9cdd0, steel: 0xb4b9bd,
  rail: 0xf2f2ee, tag: 0xf3d23a, band: 0x2f8f6a, chill: 0xf1f5f7,
};
const SNACK = [0xd8402e, 0xf2c230, 0x2f6ec0, 0xe8782a, 0x3a9a4a, 0xb43a8a, 0xf2ead2, 0x6a3a2a, 0x1f8fb0];
const BAGRAMEN = [0xd42a24, 0xe8702a, 0xb81e1e, 0x2a2a2a, 0xf0b030, 0x2f6a3a];
const CUPRAMEN = [0xe0302a, 0xf2efe6, 0xe87a2a, 0xc8242a, 0x2a5aa8];
const CAN = [0xc8202a, 0x1a5ab0, 0xf0c020, 0x20a050, 0xe4e4e4, 0x262626, 0xf07a20, 0x7a3aa0, 0x2a8ad0];
const BEER = [0xd8b030, 0x1a3a8a, 0xc8202a, 0xe8e2c8, 0x2a6a3a, 0x8a5a2a];
const PET = [0x2a8ad0, 0xe8302a, 0x2fa04a, 0xf0c020, 0xf07a20, 0x7a3aa0, 0xeeeeee];
const MILK = [0xf6f6f2, 0x6aa8e0, 0xf2d0a0, 0xf6c8d8, 0xa8d870];
const LUNCH = [0xe8b040, 0xd8582a, 0x6a8a3a, 0xc8302a, 0xf0e0b0];
const CIG = [0xf4f4f0, 0x1a3a8a, 0xc8202a, 0xd8c8a0, 0x2a2a2a, 0x3a8a5a, 0xe8e0d0, 0x8aa8c8];
const ICE = [0xf2d23a, 0xe85a8a, 0x5ab0e8, 0xf4f0e8, 0x8a5a3a, 0x5ac08a, 0xe8702a];

// 한 칸에 상품을 늘어놓는다. axis 'x': x를 따라 (앞이 +z), 'z': z를 따라 (앞이 face 쪽 x)
function stock(b, { axis, a0, a1, y, d0, d1, kind, seed, gap = 1, hMax = 30 }) {
  let a = a0 + 1;
  for (let i = 0; a < a1 - 3 && i < 80; i++) {
    const h = hash3(seed, i, 77), h2 = hash3(seed, i, 78);
    const k = Math.floor(h * 97);
    let w = 8, hh = 10, c = 0xcccccc, top = null, band = null, cap = null;
    switch (kind) {
      case 'snack': w = 9 + (k % 3) * 2; hh = 11 + (k % 3) * 3; c = pick(SNACK, seed * 5 + k); band = h2 < 0.5 ? 0xf4f2ea : null; break;
      case 'bagramen': w = 13; hh = 4; c = pick(BAGRAMEN, seed + k); break;
      case 'cup': w = 9; hh = 8; c = pick(CUPRAMEN, seed + k); top = 0xf4f2ea; break;
      case 'bigcup': w = 12; hh = 9; c = pick(CUPRAMEN, seed + k + 1); top = 0xf4f2ea; break;
      case 'can': w = 5; hh = 9; c = pick(CAN, seed * 3 + k); top = 0xc8ccd0; break;
      case 'beer': w = 6; hh = 11; c = pick(BEER, seed + k); top = 0xc8ccd0; break;
      case 'pet': w = 6; hh = 19 + (k % 2) * 3; c = 0xcfe2ea; band = pick(PET, seed * 7 + k); cap = pick([0xf4f4f0, 0x2a6ac0, 0xd8302a, 0x2a2a2a], k); break;
      case 'soju': w = 6; hh = 18; c = 0x2e8a4a; band = 0xf2f2ee; cap = 0x2e8a4a; break;
      case 'carton': w = 7; hh = 12; c = pick(MILK, seed + k); top = 0xf4f4f0; break;
      case 'tri': w = 8; hh = 6; c = 0xf4f4f0; band = pick([0x2a8a3a, 0xd8302a, 0x2a5ab0, 0xe8a030], k); break;
      case 'lunch': w = 16; hh = 5; c = pick(LUNCH, seed + k); top = 0xe8f0f2; break;
      case 'sand': w = 9; hh = 9; c = 0xf2ead2; band = pick([0x3a9a4a, 0xe8b040, 0xd8582a], k); break;
      case 'cig': w = 4.4; hh = 7; c = pick(CIG, seed * 11 + k); band = h2 < 0.6 ? 0xf4f4f0 : null; gap = 0.6; break;
      case 'tissue': w = 14; hh = 9; c = pick([0xf4f4f0, 0x8ac0e8, 0xf0c8d8, 0xb8e0b0], k); break;
      case 'sauce': w = 5; hh = 13; c = pick([0xc8302a, 0xe8b040, 0x6a3a1a, 0x2a2a2a, 0xf0f0e8], k); cap = 0xd8302a; break;
      case 'candy': w = 6; hh = 9; c = pick(SNACK, seed + k * 3); break;
      case 'ice': w = 9; hh = 4; c = pick(ICE, seed + k); break;
    }
    hh = Math.min(hh, hMax);
    if (a + w > a1 - 1) break;
    if (h2 > 0.93 && kind !== 'cig') { a += w * 0.7; continue; } // 팔린 자리
    const dd = d1 - d0;
    const put3 = (u, yy, uw, yh, col, front = 0) => {
      // 선반에 놓인 물건이라 밑면은 그리지 않는다
      if (axis === 'x') b.box(u, yy, d0 + (front ? dd - 0.4 : 0), uw, yh, front ? 0.6 : dd, col, 0.05, 8);
      else b.box(d0, yy, u, dd, yh, uw, col, 0.05, 8);
    };
    if (kind === 'bagramen') {
      // 봉지라면: 납작하게 몇 개씩 쌓는다
      for (let s = 0; s < 4; s++) put3(a, y + s * 4.2, w, 4, s % 2 ? c : shade3(c, 0.9));
    } else if (kind === 'tri' || kind === 'lunch' || kind === 'ice') {
      for (let s = 0; s < 2; s++) put3(a, y + s * hh, w, hh - 0.4, c);
      if (band) put3(a + 1, y + hh + 1, w - 2, 1.2, band);
    } else {
      put3(a, y, w, hh, c);
      if (band) put3(a - 0.1, y + hh * 0.35, w + 0.2, hh * 0.35, band);
      if (top) put3(a + 0.5, y + hh, w - 1, 0.8, top);
      if (cap) put3(a + w / 2 - 1.2, y + hh, 2.4, 2.4, cap);
    }
    a += w + gap;
  }
}
function shade3(c, k) {
  const r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
  return (Math.round(r * k) << 16) | (Math.round(g * k) << 8) | Math.round(bl * k);
}

// 곤돌라 (가운데 양면 진열대): xc 가운데, z0 앞(창 쪽) ~ z1 뒤
function gondola(b, glow, room, { xc, z0, z1, kinds, seed, cap, capSign }) {
  const W = 26, H = 148;
  b.box(xc - W, 0, z1, W * 2, 8, z0 - z1, 0x9aa0a6, 0.02);
  b.box(xc - 1.2, 8, z1, 2.4, H - 8, z0 - z1, CV.shelf, 0.02);
  b.box(xc - W, H - 2, z1, W * 2, 2, z0 - z1, CV.steel, 0.02);
  const levels = [8, 40, 72, 104];
  levels.forEach((y, li) => {
    for (const s of [-1, 1]) {
      const x0 = s < 0 ? xc - W : xc + 1.2, x1 = s < 0 ? xc - 1.2 : xc + W;
      b.box(x0, y, z1, x1 - x0, 1.6, z0 - z1, CV.shelf, 0.02);
      // 가격표 띠와 노란 행사 딱지
      const fx = s < 0 ? xc - W - 0.6 : xc + W - 0.4;
      b.box(fx, y - 2.4, z1, 1, 4, z0 - z1, CV.rail, 0.01);
      for (let z = z1 + 6; z < z0 - 6; z += 22) if (hash3(li, z, seed) < 0.45) b.box(fx - 0.3 * -s, y - 2.2, z, 1.2, 3.4, 7, CV.tag, 0);
      stock(b, { axis: 'z', a0: z1, a1: z0, y: y + 1.6, d0: x0 + 1, d1: x1 - 1, kind: kinds[li], seed: seed * 10 + li * 2 + (s > 0 ? 1 : 0), hMax: 28 });
    }
  });
  // 앞 끝 (창 쪽으로 보이는 행사 매대)
  b.box(xc - W, 0, z0, W * 2, 8, 22, 0x9aa0a6, 0.02);
  b.box(xc - W, 8, z0 + 20, W * 2, 1, 2, CV.rail, 0.01);
  for (const [y, k] of [[8, cap[0]], [42, cap[1]], [76, cap[2]]]) {
    b.box(xc - W, y, z0, W * 2, 1.6, 22, CV.shelf, 0.02);
    stock(b, { axis: 'x', a0: xc - W, a1: xc + W, y: y + 1.6, d0: z0 + 2, d1: z0 + 20, kind: k, seed: seed * 7 + y, hMax: 30 });
  }
  b.box(xc - W, 8, z0, 1.6, 104, 22, CV.shelf, 0.02);
  b.box(xc + W - 1.6, 8, z0, 1.6, 104, 22, CV.shelf, 0.02);
  // 위에 꽂은 행사 머리판
  b.box(xc - 1, H, z0 + 4, 2, 14, 2, CV.steel, 0.02);
  room.signs.push({ x: xc, y: H + 24, z: z0 + 6, w: 46, h: 18, cw: 92, ch: 36, glow: true, draw: capSign });
}

function convInterior(room, glow) {
  const b = new BoxBuilder();
  room.inner = b;
  const Z0 = -210, Z1 = -560, X0 = -244, X1 = 244, CEIL = 232;
  // 바닥 타일, 벽, 천장
  for (let x = X0, i = 0; x < X1; x += 60, i++) for (let z = Z1, k = 0; z < Z0; z += 60, k++) {
    b.box(x, -0.5, z, Math.min(60, X1 - x) - 0.6, 0.5, Math.min(60, Z0 - z) - 0.6, (i + k) % 2 ? CV.floorA : CV.floorB, 0.015);
  }
  b.box(X0, -1.2, Z1, X1 - X0, 0.6, Z0 - Z1, 0x9a988f, 0);
  b.box(X0, 0, Z1 - 52, X1 - X0, CEIL, 8, CV.wall, 0.01);
  b.box(X0 - 8, 0, Z1 - 44, 8, CEIL, Z0 - Z1 + 44, CV.wall, 0.01);
  b.box(X1, 0, Z1 - 44, 8, CEIL, Z0 - Z1 + 44, CV.wall, 0.01);
  b.box(X0, CEIL, Z1 - 52, X1 - X0, 4, Z0 - Z1 + 52, CV.ceil, 0.01);
  // 천장 판 줄눈
  for (let z = Z1; z < Z0; z += 60) b.box(X0, CEIL - 0.4, z, X1 - X0, 0.4, 1, 0xd4d6d4, 0);
  // LED 등 (가게 안 불빛). 줄로 길게
  for (const x of [-170, -70, 30, 130]) {
    for (let z = Z1 + 20; z < Z0 - 20; z += 64) {
      glow.box(x, CEIL - 2.4, z, 8, 2.4, 52, 0xf8fbff, 0);
      b.box(x - 1, CEIL - 0.8, z - 1, 10, 0.8, 54, 0xb8bcc0, 0);
    }
  }
  // 벽 위쪽 초록 띠
  b.box(X0, 200, Z1 - 0.6, X1 - X0, 4, 0.8, CV.band, 0);
  b.box(X0 - 0.6, 200, Z1, 0.8, 4, Z0 - Z1, CV.band, 0);

  // ── 안쪽 벽: 문 달린 음료 냉장고 (워크인) ──
  const doorW = (X1 - 8 - (X0 + 8)) / 9;
  const DZ = Z1; // 문 유리면
  b.box(X0, 0, DZ - 44, X1 - X0, 10, 44, CV.frame, 0.02);
  b.box(X0, 196, DZ - 44, X1 - X0, 36, 44, CV.frame, 0.02);
  const drinkKinds = [['pet', 'pet', 'pet', 'pet', 'pet'], ['pet', 'pet', 'can', 'can', 'pet'], ['can', 'can', 'can', 'pet', 'pet'],
    ['carton', 'carton', 'carton', 'pet', 'carton'], ['can', 'can', 'pet', 'can', 'can'], ['can', 'pet', 'can', 'pet', 'pet'],
    ['beer', 'beer', 'beer', 'beer', 'beer'], ['beer', 'beer', 'beer', 'beer', 'beer'], ['soju', 'soju', 'soju', 'soju', 'soju']];
  for (let i = 0; i < 9; i++) {
    const x0 = X0 + 8 + i * doorW, x1 = x0 + doorW;
    // 안쪽 (환한 냉장고 속)
    glow.box(x0, 10, DZ - 44, doorW, 186, 0.6, 0xe6eef2, 0);
    for (const [li, y] of [12, 48, 84, 120, 156].entries()) {
      b.box(x0 + 1, y - 1.4, DZ - 40, doorW - 2, 1.4, 36, 0x9aa4ac, 0.01);
      stock(b, { axis: 'x', a0: x0 + 2, a1: x1 - 2, y, d0: DZ - 36, d1: DZ - 6, kind: drinkKinds[i][li], seed: i * 9 + li, gap: 0.8, hMax: 26 });
      b.box(x0 + 1, y - 3, DZ - 4.4, doorW - 2, 2.4, 1, CV.rail, 0);
    }
    glow.box(x0 + 2, 192, DZ - 40, doorW - 4, 1.2, 4, 0xf8fbff, 0);
    // 문틀, 손잡이
    b.box(x0 - 1.6, 10, DZ - 2, 3.2, 186, 4, CV.frame, 0.02);
    b.box(x1 - 6, 70, DZ + 1.4, 1.6, 50, 2.4, 0xd8dcde, 0);
    room.panes.push([x0 + 1.6, x1 - 1.6, 10, 196, DZ + 0.4]);
  }
  b.box(X1 - 9.6, 10, DZ - 2, 3.2, 186, 4, CV.frame, 0.02);
  // 냉장고 머리판 글씨
  const heads = [[0, 3, '생수 · 차'], [3, 6, '음료 · 우유 · 커피'], [6, 9, '맥주 · 소주']];
  for (const [i0, i1, label] of heads) {
    const cx = X0 + 8 + ((i0 + i1) / 2) * doorW;
    room.signs.push({ x: cx, y: 214, z: DZ + 0.2, w: (i1 - i0) * doorW - 8, h: 16, cw: 180, ch: 20, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#30343a'; g.fillRect(0, 0, w, h);
      txt(g, label, w / 2, h / 2 + 0.5, '#f4f6f2', 12);
    } });
  }

  // ── 왼쪽 벽: 문 없는 냉장 진열대 (삼각김밥, 도시락, 샌드위치, 우유) ──
  {
    const xa = X0, xb = X0 + 46, za = -540, zb = -300;
    b.box(xa, 0, za, 46, 22, zb - za, CV.frame, 0.02);
    b.box(xa, 190, za, 52, 20, zb - za, CV.frame, 0.02);
    b.box(xa, 22, za, 4, 168, zb - za, CV.frame, 0.02);
    glow.box(xa + 4, 22, za, 0.6, 168, zb - za, 0xe8f0f4, 0);
    for (const z of [za, zb - 2.4]) b.box(xa, 0, z, 48, 190, 2.4, CV.frame, 0.02);
    const rows = [[22, 'lunch'], [52, 'lunch'], [82, 'tri'], [112, 'sand'], [142, 'carton'], [166, 'carton']];
    rows.forEach(([y, kind], ri) => {
      b.box(xa + 4, y - 1.4, za + 2, 40 - ri * 2, 1.4, zb - za - 4, 0x9aa4ac, 0.01);
      b.box(xa + 44 - ri * 2, y - 3, za + 2, 1, 3, zb - za - 4, CV.rail, 0);
      stock(b, { axis: 'z', a0: za + 3, a1: zb - 3, y, d0: xa + 6, d1: xa + 40 - ri * 2, kind, seed: 40 + ri, gap: 0.8, hMax: kind === 'carton' ? 18 : 20 });
      glow.box(xa + 6, y + 24, za + 4, 30 - ri * 2, 0.6, zb - za - 8, 0xf8fbff, 0);
    });
    room.signs.push({ x: xa + 52.2, y: 200, z: (za + zb) / 2, yaw: Math.PI / 2, w: 110, h: 14, cw: 160, ch: 20, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#2f8f6a'; g.fillRect(0, 0, w, h);
      txt(g, '도시락 · 김밥 · 샌드위치', w / 2, h / 2 + 0.5, '#ffffff', 11);
    } });
  }

  // ── 왼쪽 앞: 창가 바 테이블 (컵라면 먹는 자리)과 라면 조리대 ──
  {
    b.box(X0, 98, -246, 130, 4, 34, 0xd8d4cc, 0.01);
    b.box(X0, 0, -246, 130, 98, 3, 0xc8ccd0, 0.01);
    for (const x of [-214, -178, -142]) {
      b.box(x - 1.5, 0, -266, 3, 66, 3, CV.steel, 0);
      b.box(x - 9, 0, -274, 18, 1.6, 18, CV.steel, 0);
      b.box(x - 8, 66, -273, 16, 5, 16, 0x2a2e34, 0.02);
    }
    // 테이블 위: 먹다 만 컵라면, 나무젓가락, 캔
    b.box(-200, 102, -236, 10, 9, 10, 0xe0302a, 0.02);
    b.box(-199.5, 111, -235.5, 9, 0.6, 9, 0xf4f2ea, 0);
    b.box(-196, 111.6, -238, 1, 1, 14, 0xd8b888, 0);
    b.box(-158, 102, -232, 5, 9, 5, 0x1a5ab0, 0.02);
    b.box(-126, 102, -236, 12, 6, 8, 0xf4f4f0, 0.01);
    // 조리대: 온수기 두 대, 전자레인지 두 대, 쓰레기통
    const zr0 = -296, zr1 = -252;
    b.box(X0, 0, zr0, 40, 86, zr1 - zr0, 0xdfe2e4, 0.01);
    b.box(X0, 86, zr0, 42, 3, zr1 - zr0, 0xbfc4c8, 0.01);
    for (const [k, z] of [[0, zr0 + 2], [1, zr0 + 22]]) {
      b.box(X0 + 4, 89, z, 30, 20, 18, 0x2a2e34, 0.02);
      glow.box(X0 + 34, 92, z + 2, 0.5, 14, 11, k ? 0x9ab0b8 : 0x8aa0a8, 0);
      b.box(X0 + 34, 92, z + 14, 0.8, 14, 2.4, 0x5a5e62, 0);
    }
    b.box(X0 + 6, 109, zr0 + 4, 22, 34, 18, 0xf2f2ee, 0.01);
    b.box(X0 + 28, 124, zr0 + 7, 2, 3, 3, 0xd8302a, 0);
    b.box(X0 + 28, 124, zr0 + 14, 2, 3, 3, 0x2a6ac0, 0);
    b.box(X0 + 6, 109, zr0 + 26, 22, 34, 18, 0xf2f2ee, 0.01);
    b.box(X0 + 28, 124, zr0 + 32, 2, 3, 3, 0xd8302a, 0);
    // 분리수거함
    b.box(X0, 0, zr0 - 26, 24, 54, 24, 0x2f8f6a, 0.02);
    b.box(X0 + 24, 0, zr0 - 26, 0.6, 54, 24, 0x2a2e34, 0);
    room.signs.push({ x: X0 + 0.4, y: 168, z: (zr0 + zr1) / 2, yaw: Math.PI / 2, w: 34, h: 14, cw: 68, ch: 28, draw: (g, w, h) => {
      g.fillStyle = '#f4f4f0'; g.fillRect(0, 0, w, h);
      txt(g, '뜨거운 물', w / 2, 9, '#d8302a', 11);
      txt(g, '조심하세요', w / 2, 21, '#30343a', 9);
    } });
  }

  // ── 가운데 앞: 아이스크림 냉동고 ──
  {
    const x0 = -26, x1 = 32, z0 = -282, z1 = -238;
    b.box(x0, 0, z0, x1 - x0, 6, z1 - z0, 0x5a5e62, 0.02);
    b.box(x0, 6, z0, x1 - x0, 70, z1 - z0, 0xeef0f2, 0.01);
    b.box(x0 - 0.4, 50, z0 - 0.4, x1 - x0 + 0.8, 8, z1 - z0 + 0.8, 0x2a6ac0, 0.01);
    b.box(x0, 76, z0, x1 - x0, 4, 3, 0xd8dcde, 0);
    b.box(x0, 76, z1 - 3, x1 - x0, 4, 3, 0xd8dcde, 0);
    glow.box(x0 + 3, 66, z0 + 3, x1 - x0 - 6, 0.5, z1 - z0 - 6, 0xd8ecf6, 0);
    for (let r = 0; r < 3; r++) stock(b, { axis: 'x', a0: x0 + 3, a1: x1 - 3, y: 66.6, d0: z0 + 4 + r * 12, d1: z0 + 14 + r * 12, kind: 'ice', seed: 60 + r });
    room.panes.push([x0 + 2, x1 - 2, z0 + 2, z1 - 2, 80, 'top']);
    room.signs.push({ x: (x0 + x1) / 2, y: 30, z: z1 + 0.2, w: 40, h: 12, cw: 80, ch: 24, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#2a6ac0'; g.fillRect(0, 0, w, h);
      txt(g, '아이스크림', w / 2, h / 2 + 0.5, '#ffffff', 12);
    } });
  }

  // ── 가운데: 곤돌라 진열대 둘 ──
  const capSign = (top, sub, bg, fg) => (g, w, h) => {
    g.fillStyle = bg; g.fillRect(0, 0, w, h);
    g.fillStyle = '#ffffff'; g.fillRect(2, 2, w - 4, h - 4);
    g.fillStyle = bg; g.fillRect(4, 4, w - 8, h - 8);
    txt(g, top, w / 2, h / 2 - 5, fg, 16);
    txt(g, sub, w / 2, h / 2 + 10, '#ffffff', 10);
  };
  gondola(b, glow, room, { xc: -62, z0: -300, z1: -470, seed: 3, kinds: ['bagramen', 'cup', 'snack', 'snack'], cap: ['beer', 'beer', 'beer'], capSign: capSign('4캔 만원', '수입 맥주', '#d8302a', '#ffe14a') });
  gondola(b, glow, room, { xc: 64, z0: -320, z1: -470, seed: 5, kinds: ['tissue', 'sauce', 'bigcup', 'candy'], cap: ['snack', 'snack', 'snack'], capSign: capSign('1+1', '과자 골라 담기', '#2f8f6a', '#ffe14a') });
  // 매달린 행사 안내판
  for (const [x, z, t, c] of [[-62, -380, '2+1', '#2a6ac0'], [64, -400, '1+1', '#d8302a'], [-150, -420, '도시락', '#2f8f6a']]) {
    b.box(x - 12, 172, z, 0.4, CEIL - 172, 0.4, 0x8a8e92, 0);
    b.box(x + 12, 172, z, 0.4, CEIL - 172, 0.4, 0x8a8e92, 0);
    room.signs.push({ x, y: 164, z: z + 0.3, w: 32, h: 16, cw: 64, ch: 32, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#ffffff'; g.fillRect(0, 0, w, h);
      g.fillStyle = c; g.fillRect(2, 2, w - 4, h - 4);
      txt(g, t, w / 2, h / 2 + 0.5, '#ffffff', 18);
    } });
  }

  // ── 오른쪽: 계산대와 담배 진열장 ──
  {
    const x0 = 170, x1 = 200, z0 = -430, z1 = -300;
    b.box(x0, 0, z0, x1 - x0, 6, z1 - z0, 0x5a5e62, 0.02);
    b.box(x0, 6, z0, x1 - x0, 92, z1 - z0, 0xf0f0ec, 0.01);
    b.box(x0 - 0.4, 60, z0, 0.6, 10, z1 - z0, CV.band, 0);
    b.box(x0 - 4, 98, z0 - 2, x1 - x0 + 6, 3, z1 - z0 + 4, 0xd8d4cc, 0.01);
    // 앞쪽 (창에서 보이는 끝) 껌·사탕 꽂이
    b.box(x0 - 2, 6, z1, x1 - x0 + 2, 92, 2, 0xf0f0ec, 0.01);
    for (let r = 0; r < 4; r++) stock(b, { axis: 'x', a0: x0, a1: x1, y: 22 + r * 18, d0: z1 + 2, d1: z1 + 6, kind: 'candy', seed: 80 + r });
    // 계산기 (POS) 화면, 카드 단말기
    b.box(x1 - 4, 101, -344, 6, 4, 14, 0x2a2e34, 0.01);
    b.box(x1 - 2, 105, -342, 2, 16, 2, 0x2a2e34, 0);
    b.box(x1 - 3, 112, -350, 3, 18, 22, 0x1c1e22, 0.01);
    glow.box(x1 - 3.3, 114, -348, 0.4, 14, 18, 0x7ab8e8, 0);
    b.box(x0 + 4, 101, -330, 7, 3, 10, 0x2a2e34, 0.01);
    b.box(x0 + 2, 101, -372, 14, 2, 20, 0xe4e2dc, 0);
    // 계산대 위 온장고 (따뜻한 음료)
    b.box(x0 + 2, 101, -420, 24, 30, 30, 0x30343a, 0.02);
    glow.box(x0 + 1.6, 104, -417, 0.5, 24, 24, 0xf2b060, 0);
    for (let i = 0; i < 4; i++) b.box(x0 + 1, 106 + (i % 2) * 11, -414 + Math.floor(i / 2) * 10, 1, 9, 6, pick([0x8a5a2a, 0xe8b040, 0xd8582a, 0xf2ead2], i), 0);
    // 담배 진열장 (오른쪽 벽, 계산대 뒤): 빛나는 칸에 갑이 줄줄이
    const cx0 = X1 - 18, cz0 = -440, cz1 = -282;
    b.box(cx0, 60, cz0, 18, 150, cz1 - cz0, CV.frame, 0.02);
    for (let r = 0; r < 9; r++) {
      const y = 66 + r * 14.6;
      glow.box(cx0 - 0.4, y - 1, cz0 + 2, 0.4, 0.8, cz1 - cz0 - 4, 0xf8fbff, 0);
      stock(b, { axis: 'z', a0: cz0 + 2, a1: cz1 - 2, y, d0: cx0 - 3, d1: cx0 + 4, kind: 'cig', seed: 100 + r, hMax: 8 });
    }
    b.box(cx0, 0, cz0, 18, 60, cz1 - cz0, 0xd8dcde, 0.01);
    b.box(cx0, 210, cz0, 18, 16, cz1 - cz0, CV.frame, 0.02);
    room.signs.push({ x: cx0 - 0.6, y: 218, z: (cz0 + cz1) / 2, yaw: -Math.PI / 2, w: 120, h: 14, cw: 160, ch: 20, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#30343a'; g.fillRect(0, 0, w, h);
      txt(g, '미성년자에게 술·담배를 팔지 않아요', w / 2, h / 2 + 0.5, '#ffe14a', 10);
    } });
  }
  // ATM (오른쪽 안쪽 구석)
  {
    const z0 = -520, z1 = -484, x0 = X1 - 26;
    b.box(x0, 0, z0, 26, 156, z1 - z0, 0x2a5aa8, 0.02);
    b.box(x0 - 0.4, 100, z0 + 4, 0.6, 30, z1 - z0 - 8, 0x1c1e22, 0);
    glow.box(x0 - 0.8, 104, z0 + 7, 0.4, 22, z1 - z0 - 14, 0x9ad0f0, 0);
    b.box(x0 - 6, 84, z0 + 4, 6, 4, z1 - z0 - 8, 0xd8dcde, 0.01);
    b.box(x0 - 0.4, 140, z0, 0.6, 16, z1 - z0, 0xf2f2ee, 0);
    room.signs.push({ x: x0 - 1, y: 148, z: (z0 + z1) / 2, yaw: -Math.PI / 2, w: 30, h: 12, cw: 60, ch: 24, glow: true, draw: (g, w, h) => {
      g.fillStyle = '#f2f2ee'; g.fillRect(0, 0, w, h);
      txt(g, 'ATM', w / 2, h / 2 + 0.5, '#2a5aa8', 14);
    } });
  }
  // 우산 꽂이 (문 안쪽)
  b.box(110, 0, -232, 14, 40, 14, 0x8a8e92, 0.02);
  for (const [dx, dz, c] of [[3, 3, 0x2a2a2e], [8, 6, 0x2a6ac0], [5, 10, 0xd8302a]]) b.box(110 + dx, 40, -232 + dz, 1.6, 30, 1.6, c, 0);

  // 손님 하나: 맥주 냉장고 앞에서 고르고 있다 (등을 보인다)
  {
    const px = 120, pz = -520;
    b.box(px - 7, 0, pz - 4, 6, 4, 9, 0x2a2a2e, 0.02);
    b.box(px + 1, 0, pz - 4, 6, 4, 9, 0x2a2a2e, 0.02);
    b.box(px - 7, 4, pz - 3, 6, 42, 6, 0x2e3a5a, 0.03);
    b.box(px + 1, 4, pz - 3, 6, 42, 6, 0x2e3a5a, 0.03);
    b.box(px - 9, 46, pz - 5, 18, 36, 10, 0x6a6e76, 0.03);
    b.box(px - 8.5, 76, pz - 1, 17, 8, 7, 0x5a5e66, 0.03); // 후드
    b.box(px - 13, 50, pz - 4, 4, 30, 6, 0x6a6e76, 0.03);
    b.box(px + 9, 60, pz - 16, 4, 6, 14, 0x6a6e76, 0.03); // 앞으로 뻗은 팔 (문 손잡이)
    b.box(px - 6, 82, pz - 6, 12, 13, 12, 0xe8c4a0, 0.02);
    b.box(px - 6.5, 89, pz - 6.5, 13, 7, 13, 0x1c1a18, 0.02);
    b.box(px - 6.5, 84, pz + 3, 13, 12, 3, 0x1c1a18, 0.02);
  }
}

// 유리 판들: [x0, x1, y0, y1, z] 세운 판, [x0, x1, z0, z1, y, 'top'] 눕힌 판
function buildPanes(list) {
  const pos = [];
  for (const p of list) {
    if (p[5] === 'top') {
      const [x0, x1, z0, z1, y] = p;
      pos.push(x0, y, z0, x0, y, z1, x1, y, z1, x0, y, z0, x1, y, z1, x1, y, z0);
    } else {
      const [x0, x1, y0, y1, z] = p;
      pos.push(x0, y0, z, x1, y0, z, x1, y1, z, x0, y0, z, x1, y1, z, x0, y1, z);
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  geo.computeVertexNormals();
  const mat = new THREE.MeshStandardMaterial({ color: 0xbfd8e2, transparent: true, opacity: 0.1, roughness: 0.04, metalness: 0.1, depthWrite: false, side: THREE.DoubleSide });
  const m = new THREE.Mesh(geo, mat);
  m.renderOrder = 2;
  return m;
}

// 가게 안 재질: 바깥 조명 대신 면 방향으로만 명암 (고르게 밝은 형광등 아래)
function shadeByNormal(geo) {
  const n = geo.getAttribute('normal'), c = geo.getAttribute('color');
  for (let i = 0; i < c.count; i++) {
    const nx = n.getX(i), ny = n.getY(i), nz = n.getZ(i);
    const k = ny > 0.5 ? 0.98 : ny < -0.5 ? 0.62 : nz > 0.5 ? 0.9 : nz < -0.5 ? 0.72 : nx > 0.5 ? 0.84 : 0.8;
    c.setXYZ(i, c.getX(i) * k * 0.9, c.getY(i) * k * 0.9, c.getZ(i) * k * 0.92);
  }
  c.needsUpdate = true;
}

function buildConv(room, lit, shell, glow) {
  lit.box(-252, -8, -212, 504, 2, 444, 0x14110e, 0);
  // 보도블록, 연석, 그 너머 찻길
  tileFloor(lit, 20, (i, k) => [0x7a7a76, 0x72726e, 0x80807a, 0x6e6e6a][Math.floor(hash3(i, k, 41) * 4)], 0x55554f);
  lit.box(-600, 0, 200, 1200, 3, 8, 0xa8a8a2, 0.02);
  lit.box(-900, -1, 208, 1800, 1, 700, 0x2a2a2e, 0.02);
  for (let x = -880; x < 880; x += 60) lit.box(x, 0.1, 380, 30, 0.4, 4, 0xe9e2c4, 0.02);
  for (let x = -880; x < 880; x += 60) lit.box(x, 0.1, 290, 30, 0.4, 3, 0xe0b030, 0.02);
  // 가게 앞면 (뒷벽): 창과 문은 뚫어 유리를 끼우고, 그 너머에 진짜 가게 안을 짓는다
  const F = 0x2a2e34;
  lit.box(-248, 0, -210, 496, 14, 10, F, 0.02);
  lit.box(-248, 186, -210, 496, 50, 10, F, 0.02);
  for (const [a, b] of [[-248, -240], [-112, -56], [240, 248]]) lit.box(a, 14, -210, b - a, 172, 10, F, 0.02);
  put(lit, 'back', -240, 0, 480, 14, 2.4, 0x3a4048, 0.02);
  const windows = [[-240, -112], [-56, 102], [176, 240]];
  for (const [a, b] of windows) {
    for (let u = a; u <= b - 2; u += (b - a) / 3) put(lit, 'back', Math.min(u, b - 2), 14, 2, 172, 1.4, 0x9aa0a8, 0.02, 0.4);
    put(lit, 'back', a, 186, b - a, 3, 1.6, 0x9aa0a8, 0.02, 0.4);
    room.panes.push([a, b, 14, 186, -200.6]);
  }
  // 유리문: 문틀, 가로 손잡이, 문 앞 매트
  for (const u of [102, 138, 174]) put(lit, 'back', u, 14, 2, 172, 1.6, 0x9aa0a8, 0.02, 0.4);
  put(lit, 'back', 102, 14, 74, 3, 1.6, 0x9aa0a8, 0.02, 0.4);
  for (const u of [110, 142]) put(lit, 'back', u, 96, 24, 2.4, 2.4, 0xd8dcde, 0.01, 0.8);
  room.panes.push([102, 176, 14, 186, -200.6]);
  lit.box(108, 0, -200, 62, 0.6, 22, 0x2a3a4a, 0.03);
  room.signs.push({ x: 122, y: 118, z: -198.8, w: 14, h: 7, cw: 42, ch: 21, glow: true, draw: (g, w, h) => {
    g.fillStyle = '#2f8f6a'; g.fillRect(0, 0, w, h);
    txt(g, '당기세요', w / 2, h / 2 + 0.5, '#ffffff', 11);
  } });
  room.signs.push({ x: 156, y: 118, z: -198.8, w: 14, h: 7, cw: 42, ch: 21, glow: true, draw: (g, w, h) => {
    g.fillStyle = '#d8302a'; g.fillRect(0, 0, w, h);
    txt(g, '24시간', w / 2, h / 2 + 0.5, '#ffffff', 11);
  } });
  convInterior(room, glow);
  // 위층 건물 (상가 2~3층, 불 켜진 창 몇 개)
  lit.box(-248, 236, -214, 496, 240, 14, 0x3a3e46, 0.02);
  for (let fl = 0; fl < 3; fl++) for (let i = 0; i < 6; i++) {
    const on = hash3(fl, i, 13) < 0.45;
    const x = -230 + i * 80, y = 256 + fl * 74;
    lit.box(x - 2, y - 2, -200.2, 56, 44, 1, 0x5a5e66, 0.02);
    (on ? glow : lit).box(x, y, -199.6, 52, 40, 0.6, on ? pick([0xf2e0b0, 0xd8e8f0, 0xf0d8a0], fl + i) : 0x1c2028, 0);
  }
  // 간판 띠
  put(lit, 'back', -240, 190, 480, 42, 3, 0xf2f2ee, 0.01);
  for (const [y, c] of [[190, 0x2f8f6a], [194, 0x4a6ac0], [198, 0xe08a3a]]) put(glow, 'back', -240, y, 480, 3, 0.4, c, 0, 3.2);
  room.signs.push({ x: 0, y: 216, z: -196.6, w: 140, h: 22, cw: 168, ch: 26, glow: true, draw: (g, w, h) => {
    g.fillStyle = '#f6f6f2'; g.fillRect(0, 0, w, h);
    txt(g, '짠마트', 56, h / 2 + 0.5, '#2f8f6a', 16);
    g.fillStyle = '#e08a3a'; g.fillRect(96, 4, 2, h - 8);
    txt(g, '24시간', 128, h / 2 + 0.5, '#e08a3a', 11);
  } });
  // 창에 붙인 행사 포스터
  room.signs.push({ x: 40, y: 120, z: -197.6, w: 24, h: 30, cw: 36, ch: 45, glow: true, draw: (g, w, h) => {
    g.fillStyle = '#ffe14a'; g.fillRect(0, 0, w, h);
    txt(g, '맥주', w / 2, 9, '#c0201a', 10);
    txt(g, '4캔', w / 2, 21, '#1a1a1a', 12);
    txt(g, '만원', w / 2, 33, '#c0201a', 12);
  } });
  room.signs.push({ x: -170, y: 120, z: -197.6, w: 26, h: 26, cw: 39, ch: 39, glow: true, draw: (g, w, h) => {
    g.fillStyle = '#e83a3a'; g.fillRect(0, 0, w, h);
    txt(g, '1+1', w / 2, h / 2 - 3, '#ffffff', 14);
    txt(g, '컵라면', w / 2, h - 7, '#ffe14a', 8);
  } });
  // 탈리 칠판 자리 (가게 벽 기둥)
  put(lit, 'back', -112, 14, 56, 172, 1.4, 0x3a4048, 0.02);
  tallyBoard(lit, { frame: 0x9aa0a8, face: 0x2a3028 });
  // 바 → 하얀 접이식 판매대
  buildBar(lit, glow, {
    topBase: 0xd8d8d2, top: [0xf2f2ee, 0xeeeee8, 0xf4f4f0], topStep: 24, topJitter: 0.01, trim: 0xb9bec2,
    frontKind: 'steel', front: [0xe8e8e4, 0xdcdcd8], band: 0x2f8f6a, kick: 0x5a5e62, rail: null, side: 0xdcdcd8,
    back: 0xd8dcdf, backTop: 0xb9bec2, door: 0xcfd4d8, knob: 0x5a5e62,
  });
  // 뒤 작업대 = 아이스크림 냉동고 (유리 뚜껑)
  glow.box(-94, 64.2, -199, 188, 0.6, 13, 0xbfe0f0, 0);
  coasters(lit, 0xf2f2ee, 0xeeeee8);
  barStools(lit, 'plastic');
  // 옆: 왼쪽은 문 닫은 옆 가게 셔터, 오른쪽은 벽돌 골목 벽
  for (let y = 0; y < 220; y += 4) put(lit, 'left', -200, y, 420, 3.4, 2, y % 8 ? 0x8a8e92 : 0x7a7e82, 0.02);
  put(lit, 'left', -200, 220, 420, 16, 4, 0x5a5e62, 0.02);
  for (let y = 0, r = 0; y < 236; y += 6, r++) for (let z = -200 + (r % 2) * 9; z < 220; z += 18) put(lit, 'right', Math.max(-200, z), y, Math.min(17.4, 220 - Math.max(-200, z)), 5.4, 2, pick([0x7a4434, 0x6e3c2e, 0x84503c], r + Math.round(z / 18)), 0.05);
  // 가로등 (앞 왼쪽)
  lit.box(-212, 0, 196, 5, 236, 5, 0x3a3a40, 0.02);
  lit.box(-212, 236, 196, 40, 4, 6, 0x3a3a40, 0.02);
  glow.box(-180, 230, 196.5, 10, 4, 5, 0xfff0c8, 0);
  // 길 건너 빌딩과 하늘
  skyline(lit, glow, { near: 520, far: 640, base: -2, seed: 11, count: 30 });
  nightSky(glow, { moon: [260, 380, 700], stars: 70 });
}

const LOOKS = {
  bar: {
    build: buildBarVenue, table: 'wood', ambience: 'fire', chalk: 0xeeeadf,
    fairy: { colors: [0xffcf7a, 0xffb36b, 0xffe6a8, 0xff9f6b] },
    light: { hemi: [0xffe6c8, 0x2a1a12, 0.42], amb: [0x3d2a1e, 0.2], spot: [0xffd3a0, 3.1, 2.6], fire: [0xff8a3d, 1.1], candle: 0.9, moon: [0x8fa6ff, 0.18], accent: null, bg: 0x1a120d, dev: [0xffd0a0, 1.5] },
  },
  pocha: {
    build: buildPocha, table: 'steel', ambience: 'street', chalk: 0xc0392b,
    fairy: null,
    light: { hemi: [0xffd2a0, 0x3a2414, 0.5], amb: [0x4a2a18, 0.28], spot: [0xffc27a, 3.2, 2.9], fire: [0xff6a2a, 0.8], candle: 0, moon: [0x6a8acf, 0.12], accent: [0xff7a3a, 0.6, -150, 150, 120], bg: 0x0e1018, dev: [0xffb870, 1.4] },
  },
  hof: {
    build: buildHof, table: 'hof', ambience: 'crowd', chalk: 0xeeeadf,
    fairy: null,
    light: { hemi: [0xfff0d8, 0x3a2414, 0.62], amb: [0x4a3424, 0.3], spot: [0xffe6c0, 3.4, 3.0], fire: null, candle: 0, moon: [0x8fa6ff, 0.16], accent: [0x8affa0, 0.22, -205, 158, 0, 170], bg: 0x1a140e, dev: [0xffe0c0, 2.2] },
  },
  wine: {
    build: buildWine, table: 'dark', ambience: 'quiet', chalk: 0xd8d2c4,
    fairy: { colors: [0xffc46a, 0xffb050, 0xffd890], dim: 0.7 },
    light: { hemi: [0xffd8b0, 0x2a1810, 0.7], amb: [0x3a2418, 0.36], spot: [0xffcc90, 3.0, 2.6], fire: null, candle: 1.2, moon: [0x6a7acf, 0.14], accent: [0xffa860, 2.4, 0, 205, 30, 340], bg: 0x0d0a08, dev: [0xffc890, 2.6] },
  },
  xmas: {
    build: buildXmas, table: 'rustic', ambience: 'fire', chalk: 0xeeeadf,
    fairy: { colors: [0xff4a4a, 0x5aff7a, 0xffd25a, 0x5aa8ff], top: 190, sag: 9 },
    light: { hemi: [0xffe0c0, 0x2a1a12, 0.44], amb: [0x3d2a1e, 0.22], spot: [0xffd3a0, 3.0, 2.6], fire: [0xff8a3d, 1.3], candle: 0.8, moon: [0xa0b8ff, 0.24], accent: [0xffc078, 0.95, -136, 118, 118, 210], bg: 0x141a24, dev: [0xffd8a8, 1.4] },
  },
  izakaya: {
    build: buildIzakaya, table: 'hinoki', ambience: 'crowd', chalk: 0xeeeadf,
    fairy: null,
    light: { hemi: [0xffe2c0, 0x2a1a12, 0.5], amb: [0x3d2a1e, 0.24], spot: [0xffd3a0, 3.0, 2.6], fire: [0xff6a2a, 1.0], candle: 0, moon: [0x8fa6ff, 0.12], accent: [0xffd08a, 0.7, 0, 150, 60, 300], bg: 0x14100c, dev: [0xffd0a0, 1.5] },
  },
  rooftop: {
    build: buildRooftop, table: 'lounge', ambience: 'wind', chalk: 0xeeeadf,
    fairy: null,
    light: { hemi: [0xc8d4ff, 0x2a2018, 0.55], amb: [0x2a3040, 0.3], spot: [0xffd6a8, 3.2, 2.8], fire: null, candle: 0, moon: [0xa8b8ff, 0.45], accent: [0xff8ad8, 0.6, 0, 120, -180, 180], bg: 0x0b1020, dev: [0xc8b0ff, 1.6] },
  },
  conv: {
    build: buildConv, table: 'patio', ambience: 'city', chalk: 0xeeeadf,
    fairy: null,
    light: { hemi: [0xd8e6ff, 0x24262a, 0.4], amb: [0x262c36, 0.24], spot: [0xeef4ff, 2.6, 2.4], fire: null, candle: 0, moon: [0xa8b8ff, 0.3], accent: [0xcfe6ff, 0.9, 0, 120, -165, 300], bg: 0x0a0e18, dev: [0xffe0c0, 1.4] },
  },
  attic: {
    build: buildAttic, table: 'pine', ambience: 'rain', chalk: 0x2a2420,
    fairy: { colors: [0xfff0c8, 0xffe6b0, 0xfff6dc], dim: 0.85 },
    light: { hemi: [0xffe8d0, 0x3a2a20, 0.5], amb: [0x2e2a30, 0.24], spot: [0xffe0b8, 3.0, 2.6], fire: null, candle: 0, moon: [0x9a8aff, 0.16], accent: [0xa25bff, 0.4, 0, 72, -182, 130], bg: 0x141018, dev: [0xffe0c0, 1.4] },
  },
};

export class Room {
  constructor(scene, fx) {
    this.scene = scene;
    this.fx = fx;
    this.group = new THREE.Group();
    scene.add(this.group);
    this.roomMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.85 });
    this.glowMat = new THREE.MeshBasicMaterial({ vertexColors: true, toneMapped: false });
    this.chalkMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1 });
    this.devMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.6 });
    this.want = 'round';
    this.buildLights();
    this.setVenue(DEFAULT_VENUE);
  }

  // 가게 통째로 다시 짓기
  setVenue(id) {
    if (!LOOKS[id]) id = DEFAULT_VENUE;
    if (this.venue === id) return false;
    this.venue = id;
    this.clear();
    const L = (this.look = LOOKS[id]);
    this.firePos = this.candlePos = null;
    this.grillPos = null;
    this.signs = [];
    this.signMeshes = [];
    this.steam = [];
    this.smoke = [];
    this.twinkle = null;
    this.rug = null;
    this.snowWindow = false;
    this.candleLess = false;
    this.tvPos = null;
    // 그림자를 드리우는 것(lit)과, 천장·들보·전등갓처럼 불빛보다 위에 있어 그림자를 만들 수 없는 것(shell)을 나눈다.
    const lit = new BoxBuilder(), shell = new BoxBuilder(), glow = new BoxBuilder();
    // 움직이는 소품 (기차, 흔들의자, 진자 …)과 소품 만들 때 쓰는 도구
    this.movers = [];
    this.kit = { put, txt, neon, roofY };
    // 창 너머 가게 안 (편의점): 바깥 불빛을 받지 않는 재질, 유리
    this.inner = null;
    this.panes = [];
    L.build(this, lit, shell, glow);
    addDecor(id, this, lit, shell, glow);
    const shellMesh = new THREE.Mesh(shell.geometry(), this.roomMat);
    shellMesh.receiveShadow = true;
    const litMesh = new THREE.Mesh(lit.geometry(), this.roomMat);
    litMesh.receiveShadow = true;
    litMesh.castShadow = true;
    const glowMesh = new THREE.Mesh(glow.geometry(), this.glowMat);
    this.group.add(shellMesh, litMesh, glowMesh);
    if (this.inner && !this.inner.empty) {
      const geo = this.inner.geometry();
      shadeByNormal(geo);
      this.group.add(new THREE.Mesh(geo, new THREE.MeshBasicMaterial({ vertexColors: true, toneMapped: false, fog: false })));
    }
    if (this.panes.length) this.group.add(buildPanes(this.panes));
    this.buildSigns();
    this.tableKind = null;
    this.setTable(this.want);
    if (L.fairy) this.buildFairyLights(L.fairy);
    this.buildChalk(L.chalk);
    if (this.twinkle) this.buildTwinkle(this.twinkle);
    if (this.snowWindow) this.buildSnow();
    this.applyLights(L.light);
    // 노래 기물
    const kind = venueById[id].device.kind;
    this.device = makeDevice(kind, this.devMat);
    this.device.root.position.set(DEVICE_AT.x, 0, DEVICE_AT.z);
    this.device.root.rotation.y = DEVICE_AT.yaw;
    this.group.add(this.device.root);
    this.device.root.traverse((o) => { if (o.isMesh) o.receiveShadow = true; });
    return true;
  }

  clear() {
    this.device?.dispose();
    this.device = null;
    const shared = [this.roomMat, this.glowMat, this.chalkMat, this.devMat];
    for (const c of [...this.group.children]) {
      c.traverse((o) => {
        o.geometry?.dispose();
        if (o.material && !shared.includes(o.material)) o.material.dispose();
      });
      this.group.remove(c);
    }
    for (const e of this.signMeshes || []) e.tex.dispose();
    this.signMeshes = [];
    this.fairy = null;
    this.twinkleMesh = null;
    this.snow = null;
    this.tableMesh = null;
    this.canopyMesh = null;
    this.tally = null;
  }

  buildSigns() {
    for (const sp of this.signs) {
      const c = document.createElement('canvas');
      c.width = sp.cw;
      c.height = sp.ch;
      const tex = new THREE.CanvasTexture(c);
      tex.magFilter = THREE.NearestFilter;
      tex.minFilter = THREE.NearestFilter;
      tex.generateMipmaps = false;
      tex.colorSpace = THREE.SRGBColorSpace;
      const mat = sp.glow
        ? new THREE.MeshBasicMaterial({ map: tex, transparent: true, toneMapped: false, depthWrite: false })
        : new THREE.MeshStandardMaterial({ map: tex, roughness: 0.9 });
      const m = new THREE.Mesh(new THREE.PlaneGeometry(sp.w, sp.h), mat);
      m.position.set(sp.x, sp.y, sp.z);
      m.rotation.y = sp.yaw ?? 0;
      m.receiveShadow = !sp.glow;
      this.group.add(m);
      const entry = { sp, c, g: c.getContext('2d'), tex, m, t: 0 };
      this.draw(entry, 0);
      this.signMeshes.push(entry);
    }
    // 글꼴이 아직이면 불러온 뒤 다시 쓴다
    if (this.signs.length && document.fonts?.load) {
      const list = this.signMeshes;
      document.fonts.load(`12px ${FONT}`).then(() => { if (this.signMeshes === list) list.forEach((e) => this.draw(e, e.t)); });
    }
  }

  draw(e, t) {
    e.g.imageSmoothingEnabled = false;
    e.g.clearRect(0, 0, e.sp.cw, e.sp.ch);
    e.sp.draw(e.g, e.sp.cw, e.sp.ch, t);
    e.tex.needsUpdate = true;
  }

  // 카메라가 올라갈 수 있는 높이 (다락방은 지붕이 기울어 벽 쪽이 낮다)
  ceilingAt(x) {
    return this.venue === 'attic' ? roofY(x) - 14 : 236;
  }

  // 노래 기물을 비추는 카메라 자리 (신청곡 창을 열면)
  deviceView(pos, look, narrow, side) {
    const d = this.device;
    if (!d) return false;
    const r = d.root.position, a = DEVICE_AT.yaw;
    const fx = Math.sin(a), fz = Math.cos(a);
    const h = d.size * 0.55;
    const dist = Math.max(70 + d.size * 0.9, (d.width ?? 0) * 1.25);
    look.set(r.x, h, r.z);
    pos.set(r.x + fx * dist, h + 22, r.z + fz * dist);
    // 창이 가리는 쪽을 피해서: 좁은 화면은 아래(창)를 피해 위로, 넓은 화면은 오른쪽(창)을 피해 왼쪽으로
    const rx = Math.cos(a), rz = -Math.sin(a);
    if (narrow) look.y -= dist * 0.3;
    else if (side) { look.x += rx * dist * 0.3; look.z += rz * dist * 0.3; }
    return true;
  }

  // 같이 짠 탁자. 바 테이블일 땐 가운데엔 원형 탁자를 그냥 둔다.
  setTable(type) {
    const kind = TABLES[type] && type !== 'bar' ? type : 'round';
    this.want = kind;
    if (this.tableKind === kind) return;
    this.tableKind = kind;
    if (this.tableMesh) {
      this.group.remove(this.tableMesh);
      this.tableMesh.geometry.dispose();
    }
    const b = new BoxBuilder();
    const S = TABLE_LOOKS[this.look.table];
    this.buildRug(b, kind);
    if (kind === 'square') this.buildSquareTable(b, S);
    else if (kind === 'long') this.buildLongTable(b, S);
    else if (kind === 'ushape') this.buildUTable(b, S);
    else this.buildRoundTable(b, S, kind === 'biground' ? BIGROUND : ROUND, TABLES[kind].cap, kind);
    this.centerpiece(b, kind);
    const m = new THREE.Mesh(b.geometry(), this.roomMat);
    m.castShadow = m.receiveShadow = true;
    this.group.add(m);
    this.tableMesh = m;
    // 파라솔 천: 그림자 없이 (위 불빛이 탁자를 비추게)
    if (this.canopyMesh) {
      this.group.remove(this.canopyMesh);
      this.canopyMesh.geometry.dispose();
      this.canopyMesh = null;
    }
    if (this.canopy.length) {
      const cb = new BoxBuilder();
      for (const [cx, cz, R] of this.canopy) {
        for (let k = 0; k < 8; k++) {
          const r = R * (1 - k / 8), y = 194 + k * 2.4;
          disc(cb, cx, y, cz, r, 2.4, 4, (dx, dz, d) => (d < r - R / 8 - 3 && k < 7 ? null : Math.floor(((Math.atan2(dz, dx) + Math.PI) / (Math.PI * 2)) * 10) % 2 ? 0x2f8f6a : 0xf2f2ee));
        }
        for (let a = 0; a < 10; a++) {
          const ang = (a / 10) * Math.PI * 2;
          cb.cbox(cx + Math.cos(ang) * R, 190, cz + Math.sin(ang) * R, 2, 4, 2, a % 2 ? 0x2f8f6a : 0xf2f2ee, 0);
        }
        cb.cbox(cx, 213, cz, 3, 4, 3, 0xd8d8d2, 0);
      }
      const cm = new THREE.Mesh(cb.geometry(), this.roomMat);
      cm.receiveShadow = true;
      this.group.add(cm);
      this.canopyMesh = cm;
    }
    // 큰 탁자는 끝까지 불빛이 닿게 조금 넓게 비춘다
    if (this.mainSpot) {
      this.mainSpot.angle = { long: 0.98, biground: 0.94, ushape: 1.0, round: 0.84 }[kind] ?? 0.78;
      const cz = kind === 'ushape' ? 24 : 0;
      this.mainSpot.position.set(0, 186, cz);
      this.mainSpot.target.position.set(0, TABLE_Y, cz + 4);
    }
  }

  // 탁자 아래 깔개 (가게마다)
  buildRug(b, kind) {
    if (!this.rug) return;
    // 깔개는 의자까지 덮게 탁자 크기에 맞춘다
    const big = kind === 'biground' || kind === 'ushape';
    if (this.rug === 'round') {
      const R = kind === 'biground' ? 132 : kind === 'round' ? 116 : 104, cz = kind === 'ushape' ? 20 : 0;
      disc(b, 0, 0, cz, kind === 'ushape' ? 150 : R, 0.8, 6, (x, z, d) => {
        const RR = kind === 'ushape' ? 150 : R;
        if (d > RR - 8) return 0xc98f4e;
        if (d > RR - 14) return 0x6e2b22;
        const m = (Math.abs(Math.round(x / 6)) + Math.abs(Math.round(z / 6))) % 5;
        if (d < 18) return 0xc98f4e;
        return m === 0 ? 0xc98f4e : m === 2 ? 0x8a3a2c : 0x9c4632;
      });
    } else if (this.rug === 'plaid') {
      const hx = kind === 'long' ? 170 : kind === 'ushape' ? 174 : big ? 138 : 122, hz = kind === 'long' ? 100 : kind === 'ushape' ? 120 : big ? 132 : 122;
      const oz = kind === 'ushape' ? 24 : 0;
      for (let x = -hx; x < hx; x += 6) for (let z = -hz; z < hz; z += 6) {
        const a = Math.floor((x + 600) / 18) % 3, c = Math.floor((z + 600) / 18) % 3;
        const col = a === 0 && c === 0 ? 0x1e4a28 : a === 0 || c === 0 ? 0x8a1e24 : (a + c) % 2 ? 0xb3282e : 0xa12429;
        b.box(x, 0, z + oz, 6, 0.8, 6, Math.abs(x) > hx - 6 || Math.abs(z) > hz - 6 ? 0xe9e2d3 : col, 0.02);
      }
    } else if (this.rug === 'shag') {
      const r = kind === 'long' ? 150 : kind === 'ushape' ? 160 : kind === 'biground' ? 136 : kind === 'round' ? 118 : 100;
      disc(b, 0, 0, kind === 'ushape' ? 24 : 0, r, 1.2, 4, (x, z, d) => (hash3(Math.round(x), Math.round(z), 7) < 0.5 ? 0x5a8a8a : 0x4f7d7d));
    }
  }

  // 탁자 가운데: 가게마다 그 집다운 것 하나 (포차는 버너 위 어묵탕, 호프는 맥주 타워 …)
  centerpiece(b, kind) {
    const pts = kind === 'long' ? [[-60, 0], [60, 0]] : kind === 'ushape' ? [[-100, -78], [100, -78]] : [[0, 0]];
    const T = 75;
    this.tableSteam = [];
    this.canopy = [];
    // 잔이 뚫고 지나가지 않게 (높이 h보다 높이 들면 위로 지나간다)
    this.obstacles = [];
    const ob = (x, z, r, h) => this.obstacles.push({ x, z, r, h });
    for (const [x, z] of pts) {
      switch (this.venue) {
        case 'pocha': {
          // 휴대용 가스버너와 어묵탕 냄비
          b.cbox(x, T, z, 20, 6, 16, 0x2a2a2d, 0.02);
          b.cbox(x + 7, T + 1, z + 8.2, 3, 3, 0.8, 0xc0392b, 0);
          disc(b, x, T + 6, z, 9, 7, 2, (dx, dz, d) => (d > 7.6 ? 0xb9bec2 : 0x9a6a36));
          for (let i = 0; i < 5; i++) {
            b.cbox(x - 4 + i * 2, T + 11, z - 2 + (i % 2) * 3, 2, 4, 1.2, 0xd9b071, 0.05);
            b.cbox(x - 4 + i * 2, T + 15, z - 2 + (i % 2) * 3, 0.5, 8, 0.5, 0xc9a26b, 0);
          }
          for (const s of [-1, 1]) b.cbox(x + s * 9.6, T + 10, z, 2, 1.4, 4, 0xb9bec2, 0);
          this.tableSteam.push(new THREE.Vector3(x, T + 14, z));
          ob(x, z, 11, 16);
          break;
        }
        case 'hof': {
          // 3000cc 맥주 타워
          b.cbox(x, T, z, 12, 3, 12, 0x2a2a2d, 0.02);
          for (let y = 0; y < 16; y += 2) {
            const beer = y < 12;
            disc(b, x, T + 3 + y, z, 5, 2, 1, (dx, dz, d) => (d > 4.2 ? 0xdcefee : beer ? (y > 8 ? 0xfff6dc : 0xe0a02a) : 0xdcefee));
          }
          b.cbox(x, T + 19, z, 7, 2, 7, 0x2a2a2d, 0.02);
          b.cbox(x + 6, T + 4, z, 3, 2, 2, 0xc9ccce, 0);
          ob(x, z, 7, 22);
          break;
        }
        case 'wine': {
          b.cbox(x, T, z, 6, 1, 6, 0xc9a24a, 0.03);
          b.cbox(x, T + 1, z, 3, 7, 3, 0xf3e6c8, 0.03);
          b.cbox(x + 9, T, z + 2, 5, 18, 5, 0x2a0f14, 0.03);
          b.cbox(x + 9, T + 18, z + 2, 2.4, 7, 2.4, 0x2a0f14, 0.03);
          b.cbox(x + 9, T + 23, z + 2, 2.8, 2, 2.8, 0x7a1c2b, 0.02);
          b.cbox(x + 9, T + 6, z + 4.6, 5.2, 6, 0.4, 0xefe2c4, 0.03);
          ob(x, z, 3.5, 8);
          ob(x + 9, z + 2, 3.5, 26);
          break;
        }
        case 'xmas': {
          // 솔가지 고리와 빨간 초
          for (let a = 0; a < 14; a++) {
            const ang = (a / 14) * Math.PI * 2;
            b.cbox(x + Math.cos(ang) * 7, T, z + Math.sin(ang) * 7, 4, 3, 4, a % 2 ? 0x2f6b3a : 0x24582f, 0.06);
            if (a % 4 === 1) b.cbox(x + Math.cos(ang) * 7, T + 3, z + Math.sin(ang) * 7, 2, 2, 2, a % 8 === 1 ? 0xb3282e : 0xe0b030, 0);
          }
          b.cbox(x, T, z, 3, 9, 3, 0xb3282e, 0.03);
          ob(x, z, 9, 6);
          break;
        }
        case 'attic': {
          // 젠가 탑
          for (let r = 0; r < 9; r++) {
            for (let k = 0; k < 3; k++) {
              if (hash3(r, k, 91) < 0.12 && r < 7) continue;
              if (r % 2) b.cbox(x - 3 + k * 3, T + r * 1.8, z, 2.8, 1.7, 9, pick([0xe0c08a, 0xd6b47c, 0xe8c896], r + k), 0.04);
              else b.cbox(x, T + r * 1.8, z - 3 + k * 3, 9, 1.7, 2.8, pick([0xe0c08a, 0xd6b47c, 0xe8c896], r + k), 0.04);
            }
          }
          ob(x, z, 6.5, 17);
          break;
        }
        case 'izakaya': {
          // 작은 숯 화로 시치린과 꼬치 셋
          disc(b, x, T, z, 7, 9, 1, (dx, dz, d) => (d > 5.8 ? 0xc89a6a : 0x3a2a20));
          disc(b, x, T + 9, z, 6, 0.6, 1, (dx, dz, d) => (d < 5 ? (Math.round(dx + dz) % 3 ? 0xff6a2a : 0x2a2a2d) : 0x2a2a2d));
          for (let i = 0; i < 3; i++) {
            b.cbox(x - 3 + i * 3, T + 10, z, 0.6, 0.6, 16, 0xd8c08a, 0);
            for (let k = 0; k < 3; k++) b.cbox(x - 3 + i * 3, T + 10.2, z - 4 + k * 3.4, 2.2, 2, 2.6, pick([0xa8642e, 0x8a4a22, 0xc07a3a], i + k), 0.06);
          }
          this.tableSteam.push(new THREE.Vector3(x, T + 13, z));
          ob(x, z, 8, 13);
          break;
        }
        case 'rooftop': {
          // 유리 등잔과 작은 다육 화분
          b.cbox(x - 3, T, z, 5, 1, 5, 0x1c1c22, 0.02);
          b.cbox(x - 3, T + 1, z, 2.4, 4, 2.4, 0xf3e6c8, 0.02);
          for (const [dx, dz] of [[-2.6, -2.6], [2.6, -2.6], [-2.6, 2.6], [2.6, 2.6]]) b.cbox(x - 3 + dx * 0.9, T + 1, z + dz * 0.9, 0.6, 9, 0.6, 0xdcefee, 0);
          b.cbox(x - 3, T + 10, z, 5.4, 1, 5.4, 0x1c1c22, 0.02);
          b.cbox(x + 6, T, z + 2, 6, 5, 6, 0xd8d0c0, 0.03);
          for (const [dx, dz] of [[-1, -1], [1, 1], [1, -1]]) b.cbox(x + 6 + dx * 1.4, T + 5, z + 2 + dz * 1.4, 2, 2.4, 2, 0x5a9a6a, 0.05);
          ob(x - 3, z, 3.4, 11);
          ob(x + 6, z + 2, 3.6, 8);
          break;
        }
        case 'conv': {
          // 탁자 가운데 꽂은 파라솔 (그림자는 드리우지 않는다: 위에서 비추는 불빛을 가리지 않게)
          const R = kind === 'biground' ? 96 : kind === 'round' ? 80 : kind === 'square' ? 58 : 52;
          b.cbox(x, T, z, 2.4, 128, 2.4, 0xd8d8d2, 0.02);
          this.canopy.push([x, z, R]);
          // 다 먹은 컵라면 하나와 나무젓가락
          b.cbox(x + 8, T, z - 6, 7, 7, 7, 0xf2f2ee, 0.02);
          b.cbox(x + 8, T + 7, z - 6, 7.4, 0.6, 7.4, 0xe05a2a, 0.02);
          b.cbox(x + 8.5, T + 7.6, z - 6, 0.6, 0.6, 12, 0xd8c08a, 0);
          ob(x, z, 2, 200);
          ob(x + 8, z - 6, 4.6, 8);
          break;
        }
        default: {
          // 단골 바: 땅콩 그릇
          disc(b, x, T, z, 6, 3, 1, (dx, dz, d) => (d > 5 ? 0x6b4529 : 0xc9a26b));
          ob(x, z, 6, 3);
        }
      }
    }
  }

  buildChair(b, x, z, s, S) {
    if (S.chair === 'plastic') { stoolPlastic(b, x, z, pick(S.seat, s), 42); return; }
    if (S.chair === 'stool') {
      disc(b, x, 42, z, 11, 4, 2, (dx, dz, d) => (d > 9 ? S.frame : pick(S.seat, s)));
      for (const [dx, dz] of [[-6, -6], [6, -6], [-6, 6], [6, 6]]) b.cbox(x + dx, 0, z + dz, 3, 42, 3, S.leg, 0.03);
      b.cbox(x, 16, z, 14, 2, 2, S.leg, 0.03);
      b.cbox(x, 16, z, 2, 2, 14, S.leg, 0.03);
      return;
    }
    b.cbox(x, 42, z, 22, 4, 22, S.frame, 0.04);
    b.cbox(x, 46, z, 19, 3, 19, pick(S.seat, s), 0.05);
    for (const [dx, dz] of [[-8, -8], [8, -8], [-8, 8], [8, 8]]) b.cbox(x + dx, 0, z + dz, 3, 42, 3, S.leg, 0.03);
    b.cbox(x, 16, z, 18, 2, 2, S.leg, 0.03);
  }

  buildRoundTable(b, S, T = ROUND, n = 8, kind = 'round') {
    const R = T.r;
    disc(b, 0, 71, 0, R, 4, 3, (x, z) => S.top[(Math.floor(z / 9) + 9) % 3]);
    disc(b, 0, 69.5, 0, R - 2, 1.5, 3, () => S.edge);
    if (this.look.table === 'steel') disc(b, 0, 75, 0, R, 0.6, 3, (x, z, d) => (d > R - 3 ? S.edge : null));
    // 큰 원탁은 가운데 기둥 하나로는 모자라 다리를 넷 더 단다
    const foot = R > 75 ? 70 : 54;
    b.cbox(0, 6, 0, R > 75 ? 16 : 12, 64, R > 75 ? 16 : 12, S.leg, 0.03);
    b.cbox(0, 0, 0, foot, 6, 8, S.leg, 0.03);
    b.cbox(0, 0, 0, 8, 6, foot, S.leg, 0.03);
    if (R > 75) for (const [x, z] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) b.cbox(x * R * 0.6, 0, z * R * 0.6, 6, 68, 6, S.leg, 0.03);
    for (let s = 0; s < n; s++) {
      const p = chairPose(kind, s);
      this.buildChair(b, p.x, p.z, s, S);
    }
  }

  // ㄷ자 단체석: 앞 탁자와 바 쪽으로 뻗은 두 팔
  buildUTable(b, S) {
    const U = USHAPE;
    const slab = (r, along) => {
      const w = r.x1 - r.x0, d = r.z1 - r.z0;
      if (along === 'x') for (let z = r.z0, i = 0; z < r.z1; z += 10, i++) b.box(r.x0, 71, z, w, 4, Math.min(10, r.z1 - z), S.top[i % S.top.length], 0.03);
      else for (let x = r.x0, i = 0; x < r.x1; x += 10, i++) b.box(x, 71, r.z0, Math.min(10, r.x1 - x), 4, d, S.top[(i + 1) % S.top.length], 0.03);
      b.box(r.x0 + 4, 66, r.z0 + 4, w - 8, 5, d - 8, S.edge, 0.03);
    };
    slab(U.base, 'x');
    slab({ ...U.armL, z1: U.base.z0 }, 'z');
    slab({ ...U.armR, z1: U.base.z0 }, 'z');
    // 둘레 테두리
    const rim = (x0, z0, x1, z1) => b.box(Math.min(x0, x1) - 1, 70, Math.min(z0, z1) - 1, Math.abs(x1 - x0) + 2, 2, Math.abs(z1 - z0) + 2, S.edge, 0.02);
    rim(U.base.x0, U.base.z1, U.base.x1, U.base.z1);
    rim(U.base.x0, U.armL.z0, U.base.x0, U.base.z1);
    rim(U.base.x1, U.armR.z0, U.base.x1, U.base.z1);
    rim(U.armL.x0, U.armL.z0, U.armL.x1, U.armL.z0);
    rim(U.armR.x0, U.armR.z0, U.armR.x1, U.armR.z0);
    rim(U.armL.x1, U.armL.z0, U.armL.x1, U.base.z0);
    rim(U.armR.x0, U.armR.z0, U.armR.x0, U.base.z0);
    rim(U.armL.x1, U.base.z0, U.armR.x0, U.base.z0);
    // 다리
    for (const [x, z] of [[-119, 104], [-60, 104], [60, 104], [119, 104], [-119, 66], [119, 66], [-81, 66], [81, 66], [-119, -86], [-81, -86], [119, -86], [81, -86], [-119, -12], [119, -12]]) b.cbox(x, 0, z, 6, 66, 6, S.leg, 0.03);
    for (let s = 0; s < 16; s++) {
      const p = chairPose('ushape', s);
      this.buildChair(b, p.x, p.z, s, S);
    }
  }

  // 마주 보는 사각 탁자
  buildSquareTable(b, S) {
    const H = SQUARE.half;
    for (let x = -H, i = 0; x < H; x += 12, i++) b.box(x, 71, -H, Math.min(12, H - x), 4, H * 2, S.top[i % 3], 0.03);
    b.box(-H - 1, 70, -H - 1, H * 2 + 2, 2, 2, S.edge, 0.02);
    b.box(-H - 1, 70, H - 1, H * 2 + 2, 2, 2, S.edge, 0.02);
    b.box(-H - 1, 70, -H + 1, 2, 2, H * 2 - 2, S.edge, 0.02);
    b.box(H - 1, 70, -H + 1, 2, 2, H * 2 - 2, S.edge, 0.02);
    b.box(-H + 3, 66, -H + 3, H * 2 - 6, 5, H * 2 - 6, S.edge, 0.03);
    for (const [x, z] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) b.cbox(x * (H - 6), 0, z * (H - 6), 6, 66, 6, S.leg, 0.03);
    b.cbox(0, 14, -(H - 6), H * 2 - 12, 2.5, 2.5, S.leg, 0.03);
    b.cbox(0, 14, H - 6, H * 2 - 12, 2.5, 2.5, S.leg, 0.03);
    for (let s = 0; s < 4; s++) {
      const p = chairPose('square', s);
      this.buildChair(b, p.x, p.z, s, S);
    }
  }

  // 열두 명이 앉는 긴 탁자
  buildLongTable(b, S) {
    const X = LONG.halfX, Z = LONG.halfZ;
    for (let z = -Z, i = 0; z < Z; z += 8, i++) {
      const cut = -40 + ((i * 37) % 80);
      b.box(-X, 71, z, cut + X, 4, 8, S.top[i % 4], 0.03);
      b.box(cut, 71, z, X - cut, 4, 8, S.top[(i + 2) % 4], 0.03);
    }
    b.box(-X - 1, 70, -Z - 1, X * 2 + 2, 2, 2, S.edge, 0.02);
    b.box(-X - 1, 70, Z - 1, X * 2 + 2, 2, 2, S.edge, 0.02);
    b.box(-X - 1, 70, -Z + 1, 2, 2, Z * 2 - 2, S.edge, 0.02);
    b.box(X - 1, 70, -Z + 1, 2, 2, Z * 2 - 2, S.edge, 0.02);
    b.box(-X + 4, 66, -Z + 4, X * 2 - 8, 5, Z * 2 - 8, S.edge, 0.03);
    for (const x of [-X + 10, 0, X - 10]) {
      for (const z of [-Z + 7, Z - 7]) b.cbox(x, 0, z, 6, 66, 6, S.leg, 0.03);
      b.cbox(x, 14, 0, 3, 2.5, Z * 2 - 14, S.leg, 0.03);
    }
    b.cbox(0, 14, 0, X * 2 - 20, 2.5, 2.5, S.leg, 0.03);
    for (let s = 0; s < 12; s++) {
      const p = chairPose('long', s);
      this.buildChair(b, p.x, p.z, s, S);
    }
  }

  buildFairyLights({ colors, dim = 1, top = 178, sag = 12 }) {
    const points = [];
    const segs = 3, x0 = -104, w = 208;
    for (let x = x0; x <= x0 + w; x += 2) {
      const u = ((x - x0) / (w / segs)) % 1;
      points.push([x, top - sag * (1 - (2 * u - 1) ** 2)]);
    }
    const wire = new BoxBuilder();
    for (const [x, y] of points) wire.box(x - 1, y - 0.3, -199.6, 2, 0.6, 0.6, 0x3a2a20, 0);
    this.group.add(new THREE.Mesh(wire.geometry(), this.roomMat));
    const bulbs = points.filter((_, i) => i % 5 === 2);
    const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(2.2, 2.8, 2.2), new THREE.MeshBasicMaterial({ toneMapped: false }), bulbs.length);
    const m = new THREE.Matrix4();
    bulbs.forEach(([x, y], i) => {
      mesh.setMatrixAt(i, m.makeTranslation(x, y - 2.2, -198.6));
      mesh.setColorAt(i, new THREE.Color(colors[0]));
    });
    this.fairy = { mesh, n: bulbs.length, dim, palette: colors.map((c) => new THREE.Color(c)) };
    this.group.add(mesh);
  }

  // 트리 전구 (반짝반짝)
  buildTwinkle(points) {
    const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(2, 2, 2), new THREE.MeshBasicMaterial({ toneMapped: false }), points.length);
    const m = new THREE.Matrix4();
    points.forEach(([x, y, z], i) => {
      mesh.setMatrixAt(i, m.makeTranslation(x, y + 1, z));
      mesh.setColorAt(i, new THREE.Color(0xffd25a));
    });
    this.twinkleMesh = { mesh, n: points.length, palette: [0xff4a4a, 0x5aff7a, 0xffd25a, 0x5aa8ff, 0xffffff].map((c) => new THREE.Color(c)) };
    this.group.add(mesh);
  }

  // 창밖에 내리는 눈
  buildSnow() {
    const n = 60;
    const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(0.4, 1.2, 1.2), new THREE.MeshBasicMaterial({ color: 0xf4f6fa, toneMapped: false }), n);
    const flakes = [];
    for (let i = 0; i < n; i++) flakes.push({ z: -46 + Math.random() * 92, y: 112 + Math.random() * 88, v: 6 + Math.random() * 8, ph: Math.random() * 6 });
    this.snow = { mesh, flakes, m: new THREE.Matrix4() };
    this.group.add(mesh);
  }

  buildChalk(color) {
    const chalk = new BoxBuilder();
    const px = 1.2, x0 = -96, y0 = 134;
    JJAN.forEach((row, j) => {
      [...row].forEach((ch, i) => {
        if (ch === '#') chalk.box(x0 + i * px, y0 - (j + 1) * px, -198, px, px, 0.3, color, 0.06);
      });
    });
    this.group.add(new THREE.Mesh(chalk.geometry(), this.chalkMat));
    this.chalkColor = color;
    this.tally = new THREE.Group();
    this.group.add(this.tally);
    // 가게를 옮겨도 그은 획은 다시 긋는다
    const n = this.tallyCount || 0;
    this.tallyCount = 0;
    for (let i = 0; i < n % 30; i++) this.addTally();
    this.tallyCount = n;
  }

  // 혼자 짠 할 때마다 칠판에 한 획
  addTally() {
    const n = this.tallyCount++;
    const perBoard = 30;
    if (n > 0 && n % perBoard === 0) {
      for (const c of this.tally.children) c.geometry.dispose();
      this.tally.clear();
    }
    const k = n % perBoard;
    const group = Math.floor(k / 5), mark = k % 5;
    const row = Math.floor(group / 3), col = group % 3;
    const bx = -96 + col * 10, by = 124 - row * 8;
    const b = new BoxBuilder();
    const c = this.chalkColor ?? 0xeeeadf;
    if (mark < 4) {
      b.box(bx + mark * 1.9, by - 6, -198, 0.8, 6, 0.3, c, 0.05);
    } else {
      for (let i = 0; i < 8; i++) b.box(bx - 0.8 + i, by - 6 + i * 0.75, -197.8, 1, 0.8, 0.3, c, 0.05);
    }
    this.tally.add(new THREE.Mesh(b.geometry(), this.chalkMat));
  }

  // 불빛은 늘 같은 개수를 두고 색과 세기만 바꾼다 (불빛 수가 바뀌면 셰이더를 다시 만들어야 해서 멈칫한다)
  buildLights() {
    const s = this.scene;
    this.hemi = new THREE.HemisphereLight(0xffe6c8, 0x2a1a12, 0.42);
    this.amb = new THREE.AmbientLight(0x3d2a1e, 0.2);
    s.add(this.hemi, this.amb);
    const spot = (x, z, intensity, angle) => {
      const l = new THREE.SpotLight(0xffd3a0, intensity, 0, angle, 0.65, 0);
      l.position.set(x, 186, z);
      l.target.position.set(x, TABLE_Y, z + 4);
      l.castShadow = true;
      l.shadow.mapSize.set(2048, 2048);
      l.shadow.camera.near = 40;
      l.shadow.camera.far = 260;
      l.shadow.bias = -0.0004;
      l.shadow.normalBias = 0.4;
      s.add(l, l.target);
      return l;
    };
    this.mainSpot = spot(0, 0, 3.1, 0.78);
    this.counterSpot = spot(0, -150, 2.6, 0.72);
    this.fireLight = new THREE.PointLight(0xff8a3d, 1.1, 380, 0);
    this.candleLight = new THREE.PointLight(0xffa552, 0.9, 130, 0);
    this.accent = new THREE.PointLight(0xffffff, 0, 320, 0);
    // 노래 기물을 비추는 불빛 (구석이라 큰 불빛이 닿지 않는다)
    this.devLight = new THREE.PointLight(0xffd8a8, 1.3, 260, 0);
    {
      const fx = Math.sin(DEVICE_AT.yaw), fz = Math.cos(DEVICE_AT.yaw);
      this.devLight.position.set(DEVICE_AT.x + fx * 60, 150, DEVICE_AT.z + fz * 60);
    }
    s.add(this.devLight);
    this.moon = new THREE.DirectionalLight(0x8fa6ff, 0.18);
    this.moon.position.set(300, 220, 40);
    s.add(this.fireLight, this.candleLight, this.accent, this.moon);
    this.flame = new THREE.Mesh(new THREE.BoxGeometry(1.6, 2.6, 1.6), new THREE.MeshBasicMaterial({ color: 0xffc14d, toneMapped: false }));
    s.add(this.flame);
  }

  applyLights(L) {
    this.hemi.color.setHex(L.hemi[0]);
    this.hemi.groundColor.setHex(L.hemi[1]);
    this.hemi.intensity = L.hemi[2];
    this.amb.color.setHex(L.amb[0]);
    this.amb.intensity = L.amb[1];
    for (const sp of [this.mainSpot, this.counterSpot]) sp.color.setHex(L.spot[0]);
    this.mainSpot.intensity = L.spot[1];
    this.counterSpot.intensity = L.spot[2];
    this.fireBase = L.fire && this.firePos ? L.fire[1] : 0;
    if (L.fire && this.firePos) {
      this.fireLight.color.setHex(L.fire[0]);
      this.fireLight.position.copy(this.firePos).add(new THREE.Vector3(12, 12, 0));
    } else if (L.fire && this.grillPos) {
      // 포장마차: 화로 불빛
      this.fireBase = L.fire[1];
      this.fireLight.color.setHex(L.fire[0]);
      this.fireLight.position.copy(this.grillPos).add(new THREE.Vector3(14, 6, 0));
    }
    this.fireLight.intensity = this.fireBase;
    this.candleBase = this.candlePos && !this.candleLess ? L.candle : 0;
    if (this.candlePos) {
      this.candleLight.position.copy(this.candlePos).add(new THREE.Vector3(0, 3, 4));
      this.flame.position.copy(this.candlePos).add(new THREE.Vector3(0, 0.6, 0));
    }
    this.candleLight.intensity = this.candleBase;
    this.flame.visible = this.candleBase > 0;
    if (L.accent) {
      const [c, i, x, y, z, d = 320] = L.accent;
      this.accent.color.setHex(c);
      this.accent.position.set(x, y, z);
      this.accent.distance = d;
      this.accentBase = i;
    } else this.accentBase = 0;
    this.accent.intensity = this.accentBase;
    this.devLight.color.setHex(L.dev?.[0] ?? 0xffd8a8);
    this.devBase = this.devLight.intensity = L.dev?.[1] ?? 1.3;
    this.moon.color.setHex(L.moon[0]);
    this.moon.intensity = L.moon[1];
    this.scene.background?.setHex?.(L.bg);
    this.scene.fog?.color.setHex(L.bg);
  }

  update(dt, t, playing = false) {
    const fx = this.fx;
    if (this.candleBase > 0) {
      const n1 = Math.sin(t * 13.1) * 0.5 + Math.sin(t * 7.3) * 0.3 + Math.sin(t * 23.7) * 0.2;
      this.candleLight.intensity = this.candleBase * (0.94 + n1 * 0.13);
      this.flame.scale.set(1, 1 + n1 * 0.18, 1);
      this.flame.position.x = this.candlePos.x + Math.sin(t * 5.1) * 0.15;
    }
    if (this.fireBase > 0) {
      const n2 = Math.sin(t * 9.7) * 0.4 + Math.sin(t * 4.1) * 0.35 + Math.sin(t * 17.3) * 0.25;
      this.fireLight.intensity = this.fireBase * (0.95 + n2 * 0.23);
    }
    if (this.firePos) {
      this.fireAcc = (this.fireAcc || 0) + dt * 40;
      while (this.fireAcc >= 1) {
        this.fireAcc -= 1;
        const r = Math.random();
        fx.flames.spawn({
          x: this.firePos.x + (Math.random() - 0.5) * 8, y: this.firePos.y - 2 + Math.random() * 2, z: this.firePos.z + (Math.random() - 0.5) * 26,
          vx: 0, vy: 14 + Math.random() * 16, vz: 0, wobble: 6,
          life: 0.5 + Math.random() * 0.7, size: 2 + Math.random() * 2.5,
          color: r < 0.35 ? 0xffd25a : r < 0.75 ? 0xff9a2e : 0xff5a1a,
        });
      }
    }
    // 어묵 국물, 냄비에서 오르는 김 / 화로 연기
    for (const p of this.steam) {
      if (Math.random() < dt * 9) fx.steam.spawn({ x: p.x + (Math.random() - 0.5) * 14, y: p.y, z: p.z + (Math.random() - 0.5) * 10, vx: (Math.random() - 0.5) * 2, vy: 7 + Math.random() * 5, vz: (Math.random() - 0.5) * 2, wobble: 3, drag: 0.4, life: 1.6 + Math.random(), size: 1.4 + Math.random() * 1.4, env: 'steam', color: 0xf5efe6 });
    }
    for (const p of this.tableSteam || []) {
      if (Math.random() < dt * 6) fx.steam.spawn({ x: p.x + (Math.random() - 0.5) * 8, y: p.y, z: p.z + (Math.random() - 0.5) * 8, vx: (Math.random() - 0.5) * 2, vy: 6 + Math.random() * 4, vz: (Math.random() - 0.5) * 2, wobble: 3, drag: 0.4, life: 1.4 + Math.random(), size: 1.2 + Math.random(), env: 'steam', color: 0xf5efe6 });
    }
    for (const p of this.smoke) {
      if (Math.random() < dt * 7) fx.steam.spawn({ x: p.x + (Math.random() - 0.5) * 16, y: p.y, z: p.z + (Math.random() - 0.5) * 26, vx: 2 + Math.random() * 2, vy: 9 + Math.random() * 6, vz: (Math.random() - 0.5) * 3, wobble: 4, drag: 0.3, life: 2 + Math.random(), size: 2 + Math.random() * 2, env: 'steam', color: 0xc8c2bc });
    }
    if (this.accentBase > 0) {
      // 텔레비전·네온은 살짝 일렁인다
      const k = this.venue === 'hof' ? 0.75 + 0.25 * Math.sin(t * 3.1) * Math.sin(t * 0.7) : 0.9 + 0.1 * Math.sin(t * 1.3);
      this.accent.intensity = this.accentBase * k;
    }
    const f = this.fairy;
    if (f) {
      for (let i = 0; i < f.n; i++) {
        const k = (0.75 + 0.25 * Math.sin(t * (1.3 + (i % 5) * 0.37) + i * 2.1)) * f.dim;
        const c = f.palette[i % f.palette.length];
        f.mesh.setColorAt(i, _tint.setRGB(c.r * k, c.g * k, c.b * k));
      }
      f.mesh.instanceColor.needsUpdate = true;
    }
    const tw = this.twinkleMesh;
    if (tw) {
      for (let i = 0; i < tw.n; i++) {
        const on = Math.sin(t * (0.9 + (i % 7) * 0.31) + i * 1.7) > -0.2 ? 1 : 0.25;
        const c = tw.palette[(i + Math.floor(t * 0.5)) % tw.palette.length];
        tw.mesh.setColorAt(i, _tint.setRGB(c.r * on, c.g * on, c.b * on));
      }
      tw.mesh.instanceColor.needsUpdate = true;
    }
    const sn = this.snow;
    if (sn) {
      sn.flakes.forEach((p, i) => {
        p.y -= p.v * dt;
        if (p.y < 111) { p.y = 200; p.z = -46 + Math.random() * 92; }
        sn.mesh.setMatrixAt(i, sn.m.makeTranslation(235.2, p.y, p.z + Math.sin(t + p.ph) * 1.5));
      });
      sn.mesh.instanceMatrix.needsUpdate = true;
    }
    for (const e of this.signMeshes) {
      if (!e.sp.anim) continue;
      e.t += dt;
      e.acc = (e.acc || 0) + dt;
      if (e.acc > 1 / 8) { e.acc = 0; this.draw(e, t); }
    }
    for (const m of this.movers) m.update(dt, t);
    this.device?.update(dt, t, playing);
    // 노래가 나오면 기물 쪽 불빛이 조금 밝아지고 박자에 맞춰 일렁인다
    const act = this.device?.act ?? 0;
    this.devLight.intensity = this.devBase * (1 + act * (0.25 + 0.12 * Math.sin(t * 7.4)));
  }
}
