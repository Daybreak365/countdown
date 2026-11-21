import * as THREE from 'three';
import { BoxBuilder, hash3 } from './voxel.js';

const TABLE_Y = 75;

// 가게마다 벽과 구석을 채우는 소품. 둘러보면 그 가게다운 물건이 보이게.
// 탁자(가장 큰 ㄷ자, 큰 원탁)와 의자, 자리마다의 카메라를 피해서 벽 쪽 띠(|x| ≥ 172), 앞쪽(z ≥ 150), 바 양옆 구석에만 놓는다.
// 좌표: 바닥 y=0, 방 벽 안쪽 면 x ±240, z -200(바 쪽) ~ 220(앞).

const pick = (list, i) => list[((i % list.length) + list.length) % list.length];

function disc(b, cx, y, cz, r, h, step, colorAt) {
  for (let z = -r; z < r; z += step) {
    for (let x = -r; x < r; x += step) {
      const d = Math.hypot(x + step / 2, z + step / 2);
      if (d >= r) continue;
      const c = colorAt(x, z, d);
      if (c != null) b.box(cx + x, y, cz + z, step, h, step, c, 0.03);
    }
  }
}

// 가구 놓기: 가구의 앞은 로컬 +z. (ox, oz)에 놓고 k번 90도 돌린다.
// k=0 앞이 +z(뒷벽에 붙임), k=1 앞이 +x(왼벽), k=2 앞이 -z(앞벽), k=3 앞이 -x(오른벽)
function at(b, ox, oz, k = 0) {
  k = ((k % 4) + 4) % 4;
  const box = (x, y, z, w, h, d, c, j = 0.03) => {
    let X, Z, Wd, Dd;
    if (k === 0) { X = x; Z = z; Wd = w; Dd = d; }
    else if (k === 1) { X = z; Z = -x - w; Wd = d; Dd = w; }
    else if (k === 2) { X = -x - w; Z = -z - d; Wd = w; Dd = d; }
    else { X = -z - d; Z = x; Wd = d; Dd = w; }
    b.box(ox + X, y, oz + Z, Wd, h, Dd, c, j);
  };
  const pt = (x, z) => {
    if (k === 0) return [ox + x, oz + z];
    if (k === 1) return [ox + z, oz - x];
    if (k === 2) return [ox - x, oz - z];
    return [ox - z, oz + x];
  };
  const yaw = [0, Math.PI / 2, Math.PI, -Math.PI / 2][k];
  return { box, cbox: (cx, y, cz, w, h, d, c, j) => box(cx - w / 2, y, cz - d / 2, w, h, d, c, j), pt, yaw, b };
}

// 움직이는 소품: 따로 메시를 만들어 방에 붙이고, 매 프레임 update
function mover(room, build, mat) {
  const b = new BoxBuilder();
  build(b);
  const mesh = new THREE.Mesh(b.geometry(), mat ?? room.roomMat);
  mesh.castShadow = mat !== room.glowMat;
  mesh.receiveShadow = true;
  const g = new THREE.Group();
  g.add(mesh);
  room.group.add(g);
  return g;
}

// ── 같이 쓰는 소품 ─────────────────────────────────────
// 벽에 거는 액자 (그림은 캔버스로)
function framed(room, lit, side, u, y, w, h, frame, draw) {
  const T = 2.2;
  const put = room.kit.put;
  put(lit, side, u - w / 2 - T, y - T, w + T * 2, h + T * 2, 1.6, frame, 0.03);
  const p = wallPoint(side, u, 1.8);
  room.signs.push({ x: p.x, y: y + h / 2, z: p.z, yaw: p.yaw, w, h, cw: Math.round(w * 1.2), ch: Math.round(h * 1.2), draw });
}

// 벽 앞 한 점 (off: 벽에서 떨어진 만큼)과 벽을 등진 방향
function wallPoint(side, u, off) {
  if (side === 'back') return { x: u, z: -200 + off, yaw: 0 };
  if (side === 'front') return { x: u, z: 220 - off, yaw: Math.PI };
  if (side === 'left') return { x: -240 + off, z: u, yaw: Math.PI / 2 };
  return { x: 240 - off, z: u, yaw: -Math.PI / 2 };
}

// 벽등: 쇠 받침에 촛불 같은 불빛
function sconce(room, lit, glow, side, u, y, light = 0xffc070) {
  const p = wallPoint(side, u, 0);
  const k = { back: 0, left: 1, front: 2, right: 3 }[side];
  const A = at(lit, p.x, p.z, k);
  A.cbox(0, y - 14, 0.5, 4, 10, 1.4, 0x2a2420, 0.02);
  A.cbox(0, y - 10, 3, 1.6, 1.6, 5, 0x2a2420, 0);
  A.cbox(0, y - 9, 6, 6, 1.4, 6, 0x2a2420, 0.02);
  const G = at(glow, p.x, p.z, k);
  // 벽에 번진 불빛 (그림자 없는 은은한 색 판)
  G.cbox(0, y - 16, 0.3, 16, 26, 0.4, 0x5a3218, 0);
  G.cbox(0, y - 7.6, 6, 2.6, 6, 2.6, 0xf3e6c8, 0);
  G.cbox(0, y - 1.6, 6, 1.4, 2.4, 1.4, light, 0);
}

// 화분 (바닥)
function bigPlant(lit, x, z, s = 1) {
  disc(lit, x, 0, z, 9 * s, 18 * s, 2, (dx, dz, d) => (d > 7.5 * s ? 0xa0522d : 0xb0603a));
  disc(lit, x, 18 * s, z, 7.5 * s, 1, 2, () => 0x3b2a1e);
  for (let i = 0; i < 9; i++) {
    const a = i * 2.4, r = (2 + (i % 3) * 3) * s, h = (26 + hash3(i, 1, 9) * 30) * s;
    const lx = x + Math.cos(a) * r, lz = z + Math.sin(a) * r;
    lit.cbox(lx, 19 * s, lz, 1.6, h, 1.6, 0x4f7d3a, 0.04);
    lit.cbox(lx + Math.cos(a) * 3 * s, 19 * s + h - 6 * s, lz + Math.sin(a) * 3 * s, 7 * s, 4 * s, 5 * s, i % 2 ? 0x5f9445 : 0x6aa64d, 0.06);
    lit.cbox(lx + Math.cos(a) * 5 * s, 19 * s + h - 12 * s, lz + Math.sin(a) * 5 * s, 6 * s, 3 * s, 4 * s, i % 2 ? 0x4f7d3a : 0x5f9445, 0.06);
  }
}

// 등받이 의자 하나 (바 의자 높이 아님)
function stool(lit, x, z, top, leg, h = 46) {
  disc(lit, x, h, z, 8, 3, 2, () => top);
  for (const [dx, dz] of [[-4, -4], [4, -4], [-4, 4], [4, 4]]) lit.cbox(x + dx, 0, z + dz, 2, h, 2, leg, 0.03);
}

// 소주병 (작은 초록 병)
function sojuBottle(b, x, y, z, empty = false) {
  b.cbox(x, y, z, 3.4, 9, 3.4, empty ? 0x5fae78 : 0x2f8f4e, 0.02);
  b.cbox(x, y + 9, z, 2, 3.4, 2, empty ? 0x5fae78 : 0x2f8f4e, 0.02);
  b.cbox(x, y + 12.4, z, 2.2, 1, 2.2, 0x2a7a3a, 0);
  b.cbox(x, y + 3, z, 3.6, 3, 3.6, 0xf2ede0, 0);
}

// ── 1. 단골 바 ─────────────────────────────────────────
function decorBar(room, lit, shell, glow) {
  // 벽난로 옆 가죽 안락의자와 협탁(스탠드, 읽던 책)
  const C = at(lit, -196, 96, 1);
  C.cbox(0, 0, 0, 30, 22, 26, 0x6b2420, 0.04);
  C.cbox(0, 22, 2, 24, 6, 20, 0x7d2c26, 0.05);
  C.cbox(0, 22, -10, 30, 34, 7, 0x6b2420, 0.04);
  for (const s of [-1, 1]) C.cbox(s * 13, 22, 0, 5, 14, 26, 0x5e1f1b, 0.04);
  for (const s of [-1, 1]) for (const t of [-1, 1]) C.cbox(s * 12, -0.01, t * 10, 3, 3, 3, 0x2a1a12, 0);
  disc(lit, -206, 0, 130, 3, 44, 2, () => 0x4a2f1f);
  disc(lit, -206, 44, 130, 9, 2, 2, () => 0x6b4529);
  lit.cbox(-204, 46, 128, 9, 2, 6, 0x2f4a6e, 0.03);
  lit.cbox(-204, 48, 128, 8.6, 0.6, 5.6, 0xefe2c4, 0.02);
  lit.cbox(-208, 46, 133, 2, 12, 2, 0xc9a24a, 0);
  disc(glow, -208, 58, 133, 5, 7, 1, (dx, dz, d) => (d > 3.6 ? 0xf3d6a0 : null));
  glow.cbox(-208, 57, 133, 3, 2, 3, 0xffe8b8, 0);
  // 오른쪽 뒤 구석: 괘종시계 (진자는 흔들리고, 바늘은 지금 시각)
  const K = at(lit, 224, -168, 3);
  K.cbox(0, 0, -2, 22, 10, 14, 0x4a2a18, 0.03);
  K.cbox(0, 10, -2, 18, 104, 12, 0x5a3420, 0.03);
  K.cbox(0, 114, -2, 22, 30, 14, 0x4a2a18, 0.03);
  K.cbox(0, 144, -2, 16, 6, 12, 0x3a2012, 0.03);
  K.cbox(0, 30, 4.2, 12, 70, 0.6, 0x2a1810, 0);
  const [fx, fz] = K.pt(0, 5.4);
  room.signs.push({ x: fx, y: 129, z: fz, yaw: K.yaw, w: 17, h: 17, cw: 34, ch: 34, anim: 1, draw: clockFace });
  const pend = mover(room, (b) => {
    b.cbox(0, -50, 0, 0.8, 50, 0.8, 0xc9a24a, 0);
    disc(b, 0, -56, 0, 4, 6, 1, () => 0xd9b04a);
  });
  const [px, pz] = K.pt(0, 5.2);
  pend.position.set(px, 106, pz);
  pend.rotation.y = K.yaw;
  room.movers.push({ update: (dt, t) => { pend.rotation.z = Math.sin(t * Math.PI) * 0.18; } });
  // 왼쪽 뒤 구석: 술통 하이테이블과 높은 의자
  barrel(lit, -200, -152, 0x6b4229, 0x2a2a2d);
  lit.cbox(-200, 78, -152, 34, 2, 34, 0x4a2e1e, 0.03);
  lit.cbox(-206, 80, -148, 3, 8, 3, 0xf3e6c8, 0.02);
  glow.cbox(-206, 89, -148, 1, 1.6, 1, 0xffc14d, 0);
  for (const [dx, dz] of [[6, -6], [-2, 8]]) {
    lit.cbox(-200 + dx, 80, -152 + dz, 3, 6, 3, 0xdcefee, 0.02);
    lit.cbox(-200 + dx, 81, -152 + dz, 2.4, 3, 2.4, 0xb8641c, 0.02);
  }
  for (const [x, z] of [[-168, -160], [-188, -122]]) stool(lit, x, z, 0x7a2e2a, 0x3a2414, 56);
  // 벽 액자: 왼쪽 벽 (오래된 풍경, 단골들 사진)
  framed(room, lit, 'left', -132, 132, 44, 30, 0x6b4529, landscape(['#2a3a5a', '#6a8aa8', '#e9c88a'], '#3a5a3a'));
  framed(room, lit, 'left', -170, 176, 22, 28, 0x6b4529, portrait('#a8784a'));
  framed(room, lit, 'left', 120, 150, 30, 22, 0x6b4529, photoGroup());
  framed(room, lit, 'left', 170, 150, 20, 26, 0x6b4529, portrait('#7a5a8a'));
  // 오른쪽 벽: 다트판과 분필 점수판
  dartboard(lit, 'right', 104, 150, 12);
  room.kit.put(lit, 'right', 124, 132, 22, 32, 1.4, 0x3a2414, 0.02);
  room.kit.put(lit, 'right', 126, 134, 18, 28, 1.6, 0x2f3a33, 0.02);
  for (let i = 0; i < 4; i++) room.kit.put(glow, 'right', 128 + (i % 2) * 6, 156 - i * 6, 4 + (i % 3), 1, 1.8, 0xe8e2d0, 0);
  // 앞쪽 왼편: 외투 걸이와 우산꽂이, 큰 화분
  disc(lit, -150, 0, 204, 8, 3, 2, () => 0x3a2414);
  lit.cbox(-150, 3, 204, 3, 150, 3, 0x4a2f1f, 0.03);
  for (const [dx, dz, c] of [[5, 0, 0x2f3a4a], [-5, 2, 0x7a5a3a], [0, -5, 0x5a2a2a]]) {
    lit.cbox(-150 + dx, 112, 204 + dz, 8, 32, 6, c, 0.05);
    lit.cbox(-150 + dx * 1.2, 140, 204 + dz * 1.2, 7, 6, 6, c, 0.05);
  }
  lit.cbox(-150, 152, 204, 12, 4, 12, 0x2a2420, 0.03);
  disc(lit, -128, 0, 208, 6, 26, 2, (dx, dz, d) => (d > 4.8 ? 0x2a3a2a : null));
  for (const [dx, c] of [[-2, 0x1c1c1e], [2, 0x7a2e2a]]) lit.cbox(-128 + dx, 20, 208, 1.4, 22, 1.4, c, 0);
  bigPlant(lit, -220, 200, 1.1);
  // 벽등 (방을 둘러 은은하게)
  sconce(room, lit, glow, 'left', -80, 150);
  sconce(room, lit, glow, 'left', 80, 150);
  sconce(room, lit, glow, 'right', -110, 150);
  sconce(room, lit, glow, 'front', -110, 150);
  sconce(room, lit, glow, 'front', 110, 150);
  // 책장 위 간판
  room.signs.push({ x: 0, y: 200, z: 220 - 2, yaw: Math.PI, w: 70, h: 14, cw: 84, ch: 17, draw: (g, w, h) => {
    g.fillStyle = '#3a2414'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#c9a24a'; g.fillRect(1, 1, w - 2, 1); g.fillRect(1, h - 2, w - 2, 1);
    room.kit.txt(g, '단골 바 · SINCE 1987', w / 2, h / 2 + 0.5, '#f3e2b8', 10);
  } });
}

function barrel(lit, x, z, wood, hoop, h = 76) {
  for (let y = 0; y < h; y += 4) {
    const r = 15 + Math.sin((y / h) * Math.PI) * 3;
    disc(lit, x, y, z, r, 4, 2, (dx, dz, d) => (d > r - 2 ? (y % 24 < 4 ? hoop : wood) : 0x5a3622));
  }
}

function dartboard(lit, side, u, y, R) {
  const p = wallPoint(side, u, 0);
  const A = at(lit, p.x, p.z, { back: 0, left: 1, front: 2, right: 3 }[side]);
  for (let yy = -R; yy < R; yy += 2) for (let x = -R; x < R; x += 2) {
    const d = Math.hypot(x + 1, yy + 1);
    if (d >= R) continue;
    const sector = Math.floor(((Math.atan2(yy + 1, x + 1) + Math.PI) / (Math.PI * 2)) * 20) % 2;
    const c = d < 1.6 ? 0xc0392b : d < 3 ? 0x2f8f4e : d > R - 2.4 ? 0x1c1c1e : (d > R * 0.55 && d < R * 0.68) || d > R - 4.4 ? (sector ? 0xc0392b : 0x2f8f4e) : sector ? 0x1c1c1e : 0xe9e2d0;
    A.box(x, y + yy, 0, 2, 2, 2.4, c, 0.02);
  }
  // 꽂힌 다트 셋
  for (const [dx, dy] of [[-3, 2], [2, -4], [5, 3]]) {
    A.cbox(dx, y + dy, 3, 1, 1, 5, 0x9a9da0, 0);
    A.cbox(dx, y + dy - 1, 6, 2.4, 2.4, 0.6, 0xc0392b, 0);
  }
}

// 지금 시각을 가리키는 시계판
function clockFace(g, w, h) {
  const cx = w / 2, cy = h / 2, r = w / 2 - 1;
  g.fillStyle = '#3a2012'; g.fillRect(0, 0, w, h);
  g.fillStyle = '#efe2c4';
  g.beginPath(); g.arc(cx, cy, r, 0, Math.PI * 2); g.fill();
  g.fillStyle = '#3a2a1e';
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2;
    g.fillRect(Math.round(cx + Math.sin(a) * (r - 3)) - 1, Math.round(cy - Math.cos(a) * (r - 3)) - 1, 2, 2);
  }
  const now = new Date();
  const hand = (frac, len, wd, col) => {
    const a = frac * Math.PI * 2;
    g.strokeStyle = col; g.lineWidth = wd;
    g.beginPath(); g.moveTo(cx, cy); g.lineTo(cx + Math.sin(a) * len, cy - Math.cos(a) * len); g.stroke();
  };
  hand(((now.getHours() % 12) + now.getMinutes() / 60) / 12, r * 0.5, 2, '#2a1a10');
  hand((now.getMinutes() + now.getSeconds() / 60) / 60, r * 0.78, 1.5, '#2a1a10');
  g.fillStyle = '#c9a24a'; g.fillRect(cx - 1, cy - 1, 2, 2);
}

// 그림들
function landscape(sky, ground) {
  return (g, w, h) => {
    const grd = g.createLinearGradient(0, 0, 0, h);
    grd.addColorStop(0, sky[0]); grd.addColorStop(0.6, sky[1]); grd.addColorStop(1, sky[2]);
    g.fillStyle = grd; g.fillRect(0, 0, w, h);
    g.fillStyle = '#4a5a6a';
    g.beginPath(); g.moveTo(0, h * 0.7); g.lineTo(w * 0.3, h * 0.35); g.lineTo(w * 0.55, h * 0.62); g.lineTo(w * 0.75, h * 0.4); g.lineTo(w, h * 0.66); g.lineTo(w, h); g.lineTo(0, h); g.fill();
    g.fillStyle = ground; g.fillRect(0, h * 0.78, w, h * 0.22);
    g.fillStyle = '#f0e6c8'; g.fillRect(Math.round(w * 0.78), Math.round(h * 0.14), 3, 3);
  };
}
function portrait(bg) {
  return (g, w, h) => {
    g.fillStyle = bg; g.fillRect(0, 0, w, h);
    g.fillStyle = '#2a1a12'; g.fillRect(w * 0.25, h * 0.62, w * 0.5, h * 0.38);
    g.fillStyle = '#e8c09a'; g.fillRect(w * 0.34, h * 0.26, w * 0.32, h * 0.36);
    g.fillStyle = '#3a2418'; g.fillRect(w * 0.32, h * 0.2, w * 0.36, h * 0.12);
    g.fillStyle = '#2a1a12'; g.fillRect(w * 0.42, h * 0.4, 1, 1); g.fillRect(w * 0.56, h * 0.4, 1, 1);
  };
}
function photoGroup() {
  return (g, w, h) => {
    g.fillStyle = '#d8c9a8'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#8a7a5a'; g.fillRect(0, h * 0.7, w, h * 0.3);
    for (let i = 0; i < 4; i++) {
      const x = 3 + i * (w - 6) / 4;
      g.fillStyle = ['#5a3a2a', '#3a4a5a', '#6a2a2a', '#2a4a3a'][i]; g.fillRect(x, h * 0.45, (w - 6) / 4 - 2, h * 0.35);
      g.fillStyle = '#e0b890'; g.fillRect(x + 1, h * 0.25, (w - 6) / 4 - 4, h * 0.2);
    }
  };
}

// ── 2. 포장마차 ────────────────────────────────────────
// 플라스틱 원탁 (다른 손님 자리): 다 마신 소주병, 작은 잔, 먹다 남은 접시
function plasticTable(lit, x, z, color, seats) {
  disc(lit, x, 60, z, 26, 3, 2, () => color);
  disc(lit, x, 58, z, 22, 2, 2, () => color);
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + 0.4;
    lit.cbox(x + Math.cos(a) * 14, 0, z + Math.sin(a) * 14, 3, 58, 3, color, 0.02);
  }
  for (const [a, c] of seats) {
    const sx = x + Math.cos(a) * 36, sz = z + Math.sin(a) * 36;
    disc(lit, sx, 40, sz, 12, 3, 2, () => c);
    for (let i = 0; i < 4; i++) {
      const b = (i / 4) * Math.PI * 2 + Math.PI / 4;
      lit.cbox(sx + Math.cos(b) * 7.5, 0, sz + Math.sin(b) * 7.5, 3, 40, 3, c, 0.02);
    }
  }
  const T = 63;
  sojuBottle(lit, x - 6, T, z - 4, true);
  sojuBottle(lit, x - 1, T, z - 9, true);
  sojuBottle(lit, x + 8, T, z + 6);
  for (const [dx, dz] of [[4, -2], [-10, 6]]) lit.cbox(x + dx, T, z + dz, 3, 3.4, 3, 0xdcefee, 0.02);
  disc(lit, x + 2, T, z + 10, 8, 1, 1, (dx, dz, d) => (d > 7 ? 0xe9e4d8 : 0xf2ede0));
  for (let i = 0; i < 5; i++) lit.cbox(x + 2 + Math.cos(i * 2.1) * 3.5, T + 1, z + 10 + Math.sin(i * 2.1) * 3, 2.4, 1.2, 1.6, 0xb5651d, 0.08);
  lit.cbox(x - 12, T, z - 8, 5, 5, 5, 0xf2ede0, 0.02);
}

function decorPocha(room, lit, shell, glow) {
  plasticTable(lit, -186, -146, 0xc8392f, [[0.5, 0x2f5fb0], [2.2, 0xc8392f], [-1.4, 0x2f5fb0]]);
  plasticTable(lit, 192, -112, 0x2f5fb0, [[2.6, 0xc8392f], [4.2, 0x2f5fb0], [1.2, 0xc8392f]]);
  // 석유난로와 양은 주전자 (겨울 포차)
  const hx = -196, hz = 108;
  disc(lit, hx, 0, hz, 13, 3, 2, () => 0x3a3a3d);
  disc(lit, hx, 3, hz, 11, 12, 2, (dx, dz, d) => (d > 9.5 ? 0x8a8e92 : null));
  disc(glow, hx, 15, hz, 11, 20, 2, (dx, dz, d) => (d > 9.5 ? ((Math.round(dx / 2) + Math.round(dz / 2)) % 2 ? 0xff7a2a : 0x2a1a12) : null));
  disc(glow, hx, 18, hz, 6, 14, 2, () => 0xffa040);
  disc(lit, hx, 35, hz, 12, 4, 2, () => 0x5a5e62);
  disc(lit, hx, 39, hz, 12, 1, 2, (dx, dz, d) => (d > 10 ? 0x2a2a2d : 0x3a3a3d));
  for (const s of [-1, 1]) lit.cbox(hx + s * 12, 30, hz, 2, 4, 6, 0x2a2a2d, 0);
  disc(lit, hx, 40, hz, 7, 9, 1, (dx, dz, d) => (d > 6 ? 0xd9b04a : 0xc9a04a));
  lit.cbox(hx + 8, 44, hz, 6, 2, 2, 0xd9b04a, 0);
  lit.cbox(hx, 50, hz, 9, 1.4, 1.4, 0x2a2a2d, 0);
  room.steam.push(new THREE.Vector3(hx + 11, 46, hz));
  // 가스통
  disc(lit, -224, 0, 42, 8, 46, 2, (dx, dz, d) => (d > 6.5 ? 0x5a7a9a : 0x6a8aaa));
  disc(lit, -224, 46, 42, 4, 4, 1, () => 0x9a9da0);
  lit.cbox(-224, 50, 42, 2, 3, 2, 0xc9a24a, 0);
  for (let i = 0; i < 6; i++) lit.cbox(-224 + i * 0.6, 50 - i * 6, 34 - i * 2, 1, 1, 6, 0x1c1c1e, 0);
  // 소주 상자, 맥주 상자
  for (const [x, y, z, c] of [[-218, 0, 168, 0x2f8f4e], [-218, 18, 168, 0x2f8f4e], [-218, 36, 170, 0x2f8f4e], [-198, 0, 172, 0xe0b030], [-198, 18, 170, 0xe0b030]]) {
    lit.cbox(x, y, z, 18, 18, 14, c, 0.04);
    lit.cbox(x, y + 13, z - 7.2, 10, 3, 0.4, 0x1c1c1e, 0);
    for (let k = 0; k < 4; k++) lit.cbox(x - 6 + k * 4, y + 18, z, 3, 3, 3, c === 0xe0b030 ? 0x6b3a12 : 0x2b7a42, 0.03);
  }
  // 아이스박스와 소주
  lit.cbox(214, 0, 62, 26, 24, 18, 0x2f6fb0, 0.03);
  lit.cbox(214, 24, 62, 27, 3, 19, 0xe9e2d3, 0.02);
  for (let i = 0; i < 4; i++) sojuBottle(lit, 206 + i * 5, 20, 58 + (i % 2) * 6);
  for (let i = 0; i < 6; i++) lit.cbox(206 + hash3(i, 1, 2) * 16, 25, 56 + hash3(i, 2, 2) * 12, 3, 2, 3, 0xdff0f4, 0.02);
  // 홍등: 천막 앞쪽과 옆을 따라
  for (const [x, z] of [[-170, 200], [-100, 204], [100, 204], [170, 200], [-216, -60], [216, -10], [-216, 60]]) redLantern(lit, glow, x, z);
  // 입간판 (오늘의 안주) 과 출입구 위 영업중
  const S = at(lit, 98, 196, 2);
  S.cbox(0, 0, 0, 30, 2, 8, 0x5a3a22, 0.03);
  S.cbox(0, 2, 2, 28, 52, 2, 0x5a3a22, 0.03);
  S.cbox(0, 2, -2, 28, 52, 2, 0x5a3a22, 0.03);
  room.signs.push({ x: 98, y: 30, z: 196 - 3.2, yaw: Math.PI, w: 24, h: 44, cw: 30, ch: 56, draw: (g, w, h) => {
    g.fillStyle = '#1f2422'; g.fillRect(0, 0, w, h);
    room.kit.txt(g, '오늘의', w / 2, 7, '#f3d24a', 9);
    room.kit.txt(g, '안주', w / 2, 17, '#f3d24a', 10);
    ['닭발', '꼼장어', '홍합탕', '계란말이'].forEach((m, i) => room.kit.txt(g, m, w / 2, 29 + i * 7, '#eeeadf', 7));
  } });
  room.signs.push({ x: 0, y: 212, z: 214, yaw: Math.PI, w: 30, h: 10, cw: 36, ch: 12, glow: true, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    room.kit.neon(g, '영업중', w / 2, h / 2, '#ff4a4a', 9);
  } });
  // 천막 기둥에 걸린 두루마리 휴지
  for (const [x, z] of [[-192, -170], [196, -136]]) {
    lit.cbox(x, 63, z, 6, 7, 6, 0xf6f2e8, 0.02);
  }
}

function redLantern(lit, glow, x, z) {
  const y = 196;
  lit.cbox(x, y + 16, z, 0.8, 22, 0.8, 0x1f1a17, 0);
  lit.cbox(x, y + 14, z, 8, 2, 8, 0x3a1410, 0.02);
  disc(glow, x, y, z, 7, 14, 1, (dx, dz, d) => (d > 6 ? ((Math.round(dz + dx) % 3) ? 0xd8302a : 0xa81e1a) : null));
  glow.cbox(x, y + 2, z, 6, 10, 6, 0xffa060, 0);
  lit.cbox(x, y - 2, z, 8, 2, 8, 0x3a1410, 0.02);
  for (const dx of [-1, 0, 1]) lit.cbox(x + dx * 1.5, y - 10, z, 0.8, 8, 0.8, 0xe0b030, 0);
}

// ── 3. 호프집 ───────────────────────────────────────────
function decorHof(room, lit, shell, glow) {
  // 왼쪽 뒤 구석: 펀치 기계 (점수판이 깜빡인다)
  const P = at(lit, -210, -152, 1);
  P.cbox(0, 0, -4, 34, 10, 30, 0x1c1c1e, 0.02);
  P.cbox(0, 10, -8, 30, 96, 22, 0xb02a30, 0.03);
  P.cbox(0, 106, -8, 34, 40, 24, 0x1c1c1e, 0.02);
  P.cbox(0, 146, -8, 30, 6, 20, 0xe0b030, 0.03);
  for (const s of [-1, 1]) P.cbox(s * 15.4, 12, 3.6, 1.6, 92, 1.6, 0xe0b030, 0);
  P.cbox(0, 70, 6, 10, 8, 16, 0x2a2a2d, 0.02);
  const [bx, bz] = P.pt(0, 22);
  disc(lit, bx, 66, bz, 7, 14, 1, (dx, dz, d) => (d > 6 ? 0xc0392b : 0xd04a3a));
  P.cbox(0, 40, 12, 26, 3, 8, 0x2a2a2d, 0.02);
  const [sx, sz] = P.pt(0, 4.4);
  room.signs.push({ x: sx, y: 126, z: sz, yaw: P.yaw, w: 26, h: 26, cw: 52, ch: 52, glow: true, anim: 1, draw: punchScore });
  // 오른쪽 뒤 구석: 다트 기계
  const D = at(lit, 212, -156, 3);
  D.cbox(0, 0, -4, 32, 14, 26, 0x1c1c1e, 0.02);
  D.cbox(0, 14, -8, 28, 120, 18, 0x2f5fb0, 0.03);
  D.cbox(0, 134, -8, 32, 20, 22, 0x1c1c1e, 0.02);
  const [dx2, dz2] = D.pt(0, 1.4);
  room.signs.push({ x: dx2, y: 96, z: dz2, yaw: D.yaw, w: 24, h: 24, cw: 48, ch: 48, glow: true, anim: 1, draw: dartLights });
  const [ex, ez] = D.pt(0, 1.6);
  room.signs.push({ x: ex, y: 144, z: ez, yaw: D.yaw, w: 24, h: 12, cw: 48, ch: 24, glow: true, anim: 1, draw: dartScreen });
  D.cbox(0, 50, 4, 20, 3, 10, 0x1c1c1e, 0.02);
  for (let i = 0; i < 3; i++) D.cbox(-5 + i * 5, 53, 6, 1.2, 1.2, 6, pick([0xc0392b, 0xe0b030, 0x2f8f4e], i), 0);
  // 오른쪽 벽: 액자에 넣은 축구 유니폼, 큰 메뉴판
  framed(room, lit, 'right', -112, 128, 34, 40, 0x2a2a2d, jersey);
  framed(room, lit, 'right', 96, 140, 44, 50, 0x6b4529, (g, w, h) => {
    g.fillStyle = '#2f3a33'; g.fillRect(0, 0, w, h);
    room.kit.txt(g, '메 뉴', w / 2, 6, '#ffe9a0', 9);
    [['후라이드', '18.0'], ['양념치킨', '19.0'], ['반반', '19.0'], ['골뱅이', '17.0'], ['노가리', '9.0'], ['생맥 500', '4.0']].forEach(([m, p], i) => {
      room.kit.txt(g, m, 4, 16 + i * 8, '#eeeadf', 7, 'left');
      room.kit.txt(g, p, w - 4, 16 + i * 8, '#ffd08a', 7, 'right');
    });
  });
  // 왼쪽 앞: 벽 붙은 칸막이석 (먹다 남은 치킨 상자와 잔)
  const B = at(lit, -222, 102, 1);
  B.cbox(0, 0, -2, 60, 22, 16, 0x6b4529, 0.03);
  B.cbox(0, 22, -6, 60, 6, 10, 0x8a3a2a, 0.04);
  B.cbox(0, 22, -12, 60, 40, 5, 0x6b4529, 0.03);
  B.cbox(0, 28, -12.6, 56, 30, 5, 0x9a4a32, 0.04);
  const [tx, tz] = B.pt(0, 22);
  lit.cbox(tx, 0, tz, 6, 66, 6, 0x3a2414, 0.03);
  lit.cbox(tx, 66, tz, 26, 4, 46, 0xb07a48, 0.03);
  lit.cbox(tx, 70, tz - 6, 14, 6, 14, 0xd84a3a, 0.03);
  lit.cbox(tx, 76, tz - 6, 14.4, 0.6, 14.4, 0xf2ede0, 0.02);
  for (const [ddx, ddz] of [[-6, 10], [6, 12]]) {
    lit.cbox(tx + ddx, 70, tz + ddz, 6, 12, 6, 0xdcefee, 0.02);
    lit.cbox(tx + ddx, 70.6, tz + ddz, 5, 4, 5, 0xe0a02a, 0.02);
  }
  // 응원 깃발 줄 (천장 아래)
  for (const z of [-110, 70]) {
    for (let x = -236, i = 0; x < 236; x += 12, i++) {
      const sag = Math.sin(((x + 236) / 472) * Math.PI) * 14;
      const y = 224 - sag;
      shell.box(x, y, z - 0.3, 12, 0.6, 0.6, 0x2a2420, 0);
      const c = pick([0xc0392b, 0xf2ede0, 0x2f5fb0, 0xe0b030], i);
      for (let k = 0; k < 4; k++) shell.box(x + 2 + k, y - 1 - k * 2.4, z - 0.4, 8 - k * 2, 2.4, 0.8, c, 0.02);
    }
  }
  // 맥주 상자 쌓기 (다트 기계 옆)
  for (const [x, y, z] of [[214, 0, -108], [214, 16, -108], [214, 0, -88]]) {
    lit.cbox(x, y, z, 22, 16, 18, 0x8a5a2a, 0.04);
    for (let k = 0; k < 4; k++) lit.cbox(x - 7 + k * 4.6, y + 16, z, 3.4, 3, 3.4, 0x6b3a12, 0.03);
  }
}

function punchScore(g, w, h, t) {
  g.fillStyle = '#120808'; g.fillRect(0, 0, w, h);
  // 테두리 전구: 돌아가며 켜진다
  const n = 20, on = Math.floor(t * 8) % 4;
  for (let i = 0; i < n; i++) {
    const u = i / n, p = u * 4, side = Math.floor(p), f = p - side;
    const x = side === 0 ? f * (w - 4) : side === 1 ? w - 4 : side === 2 ? (1 - f) * (w - 4) : 0;
    const y = side === 0 ? 0 : side === 1 ? f * (h - 4) : side === 2 ? h - 4 : (1 - f) * (h - 4);
    g.fillStyle = i % 4 === on ? '#ffe680' : '#5a3a10';
    g.fillRect(Math.round(x), Math.round(y), 4, 4);
  }
  // 점수: 가끔 올라갔다 멈춘다
  const cycle = t % 7, score = cycle < 2 ? Math.floor(cycle * 400) : 800 + (Math.floor(t / 7) * 37) % 199;
  g.save();
  g.shadowColor = '#ff3a2a'; g.shadowBlur = 4;
  g.fillStyle = '#ff5a3a';
  g.font = `bold 16px Galmuri11, monospace`;
  g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(String(Math.min(999, score)).padStart(3, '0'), w / 2, h / 2 - 3);
  g.restore();
  g.fillStyle = '#ffd25a';
  g.font = `8px Galmuri11, sans-serif`;
  g.textAlign = 'center';
  g.fillText('PUNCH', w / 2, h - 10);
}

function dartLights(g, w, h, t) {
  g.clearRect(0, 0, w, h);
  const cx = w / 2, cy = h / 2, R = w / 2 - 1;
  const spin = Math.floor(t * 6);
  for (let s = 0; s < 20; s++) {
    const a0 = (s / 20) * Math.PI * 2, a1 = ((s + 1) / 20) * Math.PI * 2;
    const lit = (s + spin) % 5 === 0;
    for (const [r0, r1, c] of [[R * 0.15, R * 0.55, s % 2 ? '#1c1c1e' : '#e9e2d0'], [R * 0.55, R * 0.65, s % 2 ? '#c0392b' : '#2f8f4e'], [R * 0.65, R * 0.9, s % 2 ? '#1c1c1e' : '#e9e2d0'], [R * 0.9, R, s % 2 ? '#c0392b' : '#2f8f4e']]) {
      g.fillStyle = lit ? '#ffe680' : c;
      g.beginPath(); g.arc(cx, cy, r1, a0, a1); g.arc(cx, cy, r0, a1, a0, true); g.fill();
    }
  }
  g.fillStyle = '#c0392b'; g.beginPath(); g.arc(cx, cy, R * 0.15, 0, Math.PI * 2); g.fill();
}

function dartScreen(g, w, h, t) {
  g.fillStyle = '#0a1420'; g.fillRect(0, 0, w, h);
  g.fillStyle = '#5ad1ff';
  g.font = `bold 11px Galmuri11, monospace`;
  g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(Math.floor(t / 3) % 2 ? '301' : 'DART', w / 2, h / 2);
}

function jersey(g, w, h) {
  g.fillStyle = '#f2ede0'; g.fillRect(0, 0, w, h);
  g.fillStyle = '#c0392b';
  g.fillRect(w * 0.25, h * 0.12, w * 0.5, h * 0.78);
  g.fillRect(w * 0.08, h * 0.12, w * 0.2, h * 0.26);
  g.fillRect(w * 0.72, h * 0.12, w * 0.2, h * 0.26);
  g.fillStyle = '#f2ede0'; g.fillRect(w * 0.42, h * 0.12, w * 0.16, h * 0.06);
  g.font = `bold 14px Galmuri11, sans-serif`;
  g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText('10', w / 2, h * 0.52);
}

// ── 4. 와인바 ───────────────────────────────────────────
function decorWine(room, lit, shell, glow) {
  // 한가운데 샹들리에: 쇠 고리에 초 여덟 개
  const cy = 216;
  shell.cbox(0, cy + 6, 0, 1, 260 - cy - 6, 1, 0x1c1c1e, 0);
  for (let a = 0; a < 40; a++) {
    const ang = (a / 40) * Math.PI * 2;
    shell.cbox(Math.cos(ang) * 26, cy, Math.sin(ang) * 26, 3, 1.6, 3, 0x2a2420, 0);
  }
  for (let i = 0; i < 4; i++) {
    const ang = (i / 4) * Math.PI * 2 + 0.4;
    for (let k = 0; k < 8; k++) shell.cbox(Math.cos(ang) * 26 * (k / 8), cy + 6 - k * 0.6, Math.sin(ang) * 26 * (k / 8), 1.2, 1.2, 1.2, 0x2a2420, 0);
  }
  for (let i = 0; i < 8; i++) {
    const ang = (i / 8) * Math.PI * 2;
    const x = Math.cos(ang) * 26, z = Math.sin(ang) * 26;
    shell.cbox(x, cy + 1.6, z, 3, 1, 3, 0xc9a24a, 0);
    glow.cbox(x, cy + 2.6, z, 1.8, 6, 1.8, 0xf3e6c8, 0);
    glow.cbox(x, cy + 9, z, 1, 1.8, 1, 0xffc14d, 0);
  }
  // 벽등 (벽돌벽을 따라)
  for (const [side, u] of [['right', -130], ['right', 120], ['front', -110], ['front', 110], ['back', -205], ['back', 205]]) sconce(room, lit, glow, side, u, 156);
  // 오른쪽 벽: 술통 하이테이블 (초, 와인잔 둘, 병)
  barrel(lit, 200, -112, 0x6b4229, 0x2a2a2d);
  lit.cbox(200, 78, -112, 32, 2, 32, 0x3a2418, 0.03);
  lit.cbox(194, 80, -106, 3, 9, 3, 0xf3e6c8, 0.02);
  glow.cbox(194, 90, -106, 1, 1.6, 1, 0xffc14d, 0);
  lit.cbox(206, 80, -118, 4.6, 16, 4.6, 0x2a0f14, 0.03);
  lit.cbox(206, 96, -118, 2.4, 6, 2.4, 0x2a0f14, 0.03);
  for (const [dx, dz] of [[-8, -6], [4, 8]]) {
    lit.cbox(200 + dx, 80, -112 + dz, 4, 0.8, 4, 0xdcefee, 0);
    lit.cbox(200 + dx, 80.8, -112 + dz, 0.8, 6, 0.8, 0xdcefee, 0);
    lit.cbox(200 + dx, 86.8, -112 + dz, 4, 5, 4, 0xdcefee, 0.02);
    lit.cbox(200 + dx, 87, -112 + dz, 3.4, 2, 3.4, 0x6b1f2a, 0.02);
  }
  for (const [x, z] of [[172, -128], [182, -84]]) stool(lit, x, z, 0x6b1f2a, 0x24160f, 58);
  // 오른쪽 뒤 구석: 와인 나무 상자 쌓기
  for (const [x, y, z] of [[214, 0, -182], [214, 20, -182], [190, 0, -186], [214, 40, -180]]) {
    lit.cbox(x, y, z, 30, 20, 22, 0x9a6a3a, 0.05);
    lit.cbox(x, y + 7, z + 11.2, 18, 6, 0.4, 0x5a3a1a, 0);
    for (let k = 0; k < 3; k++) lit.cbox(x - 8 + k * 8, y + 20, z, 3.4, 2, 3.4, 0x2a0f14, 0.02);
  }
  // 왼쪽 뒤 구석: 치즈 진열장
  const Q = at(lit, -190, -184, 0);
  Q.cbox(0, 0, 0, 44, 46, 26, 0x3a2418, 0.03);
  Q.cbox(0, 46, 0, 44, 2, 26, 0x24160f, 0.02);
  Q.cbox(0, 48, -11, 44, 36, 2, 0x24160f, 0.02);
  Q.cbox(0, 84, 0, 44, 3, 26, 0x24160f, 0.02);
  for (const s of [-1, 1]) Q.cbox(s * 21, 48, 0, 2, 36, 26, 0x24160f, 0.02);
  Q.cbox(0, 64, 0, 40, 1.4, 22, 0x6b4229, 0.02);
  for (const [dx, y, dz, r, c] of [[-12, 48, 0, 7, 0xe8c45a], [4, 48, 2, 6, 0xf2e2a8], [14, 48, -2, 5, 0xd8a43a], [-8, 65.4, 0, 5, 0xf2ede0], [8, 65.4, 2, 6, 0xe8c45a]]) {
    const [cx, cz] = Q.pt(dx, dz);
    disc(lit, cx, y, cz, r, 6, 1, (x, z, d) => (d > r - 1 ? (c === 0xf2ede0 ? 0xe9e2d3 : 0xc89a3a) : c));
  }
  const [gx, gz] = Q.pt(0, 0);
  glow.cbox(gx, 82, gz, 38, 0.6, 18, 0xfff0c8, 0);
  // 앞쪽: 가죽 안락의자 둘과 작은 탁자
  for (const [x, k] of [[82, 2], [124, 2]]) {
    const A = at(lit, x, 200, k);
    A.cbox(0, 0, 0, 28, 20, 24, 0x5a2a1a, 0.04);
    A.cbox(0, 20, 2, 22, 5, 18, 0x6b3420, 0.05);
    A.cbox(0, 20, -9, 28, 30, 6, 0x5a2a1a, 0.04);
    for (const s of [-1, 1]) A.cbox(s * 12, 20, 0, 4, 12, 24, 0x4a2214, 0.04);
  }
  disc(lit, 103, 0, 176, 2, 40, 2, () => 0x24160f);
  disc(lit, 103, 40, 176, 9, 2, 2, () => 0x3a2418);
  lit.cbox(100, 42, 176, 4.6, 14, 4.6, 0x1d2e1a, 0.03);
  lit.cbox(100, 56, 176, 2.4, 5, 2.4, 0x1d2e1a, 0.03);
  lit.cbox(106, 42, 178, 3, 6, 3, 0xf3e6c8, 0.02);
  glow.cbox(106, 49, 178, 1, 1.4, 1, 0xffc14d, 0);
  // 포도밭 그림
  framed(room, lit, 'right', 80, 150, 50, 34, 0xc9a24a, vineyard);
  framed(room, lit, 'back', -150, 150, 40, 28, 0xc9a24a, landscape(['#e8a868', '#f0c890', '#f6e0b0'], '#5a6a2a'));
  // 벽 위를 두르는 포도 넝쿨
  for (const side of ['right', 'front', 'back']) {
    const [from, to] = side === 'right' ? [-200, 220] : [-240, 240];
    for (let u = from, i = 0; u < to; u += 6, i++) {
      const y = 236 + Math.sin(i * 0.7) * 3;
      room.kit.put(shell, side, u, y, 6, 4, 2.4, i % 3 ? 0x3a6a2a : 0x4a7a32, 0.05);
      if (i % 9 === 4) for (let k = 0; k < 6; k++) room.kit.put(shell, side, u + (k % 2) * 2, y - 3 - Math.floor(k / 2) * 2.4, 2.2, 2.2, 3, k % 2 ? 0x5a1a4a : 0x6a2a5a, 0.04);
    }
  }
}

function vineyard(g, w, h) {
  const grd = g.createLinearGradient(0, 0, 0, h);
  grd.addColorStop(0, '#f0b070'); grd.addColorStop(0.45, '#f6d8a0'); grd.addColorStop(0.46, '#7a8a3a'); grd.addColorStop(1, '#4a5a22');
  g.fillStyle = grd; g.fillRect(0, 0, w, h);
  g.fillStyle = '#a06a3a';
  for (let i = 0; i < 6; i++) {
    const y = h * 0.5 + i * (h * 0.09);
    g.fillRect(0, Math.round(y), w, 1);
    for (let x = (i % 2) * 3; x < w; x += 6) { g.fillStyle = '#3a5a1a'; g.fillRect(x, Math.round(y) - 2, 3, 2); g.fillStyle = '#a06a3a'; }
  }
  g.fillStyle = '#e8d8b0'; g.fillRect(w * 0.7, h * 0.3, w * 0.14, h * 0.12);
  g.fillStyle = '#a03a2a'; g.fillRect(w * 0.68, h * 0.26, w * 0.18, h * 0.05);
}

// ── 5. 크리스마스 산장 ───────────────────────────────────
function decorXmas(room, lit, shell, glow) {
  const tx = -178, tz = 158, R = 56;
  // 트리를 도는 장난감 기차: 레일과 침목
  for (let a = 0; a < 120; a++) {
    const ang = (a / 120) * Math.PI * 2;
    const x = tx + Math.cos(ang) * R, z = tz + Math.sin(ang) * R;
    if (a % 2 === 0) lit.cbox(x, 0, z, 3, 0.8, 3, 0x5a3a22, 0.04);
    for (const rr of [-2.6, 2.6]) lit.cbox(tx + Math.cos(ang) * (R + rr), 0.8, tz + Math.sin(ang) * (R + rr), 1.4, 1, 1.4, 0x9a9da0, 0);
  }
  const cars = [];
  const carColors = [[0x1c1c1e, 0xb3282e], [0xb3282e, 0xe0b030], [0x2f6b3a, 0xb3282e], [0x2f6fb0, 0xe0b030]];
  carColors.forEach(([body, trim], i) => {
    const g = mover(room, (b) => {
      b.cbox(0, 1.8, 0, 6, 1.2, 12, 0x2a2a2d, 0.02);
      for (const s of [-1, 1]) for (const t of [-1, 1]) b.cbox(s * 3, 0.6, t * 3.6, 1, 3, 3, 0x2a2a2d, 0);
      if (i === 0) {
        // 기관차: 보일러, 굴뚝, 운전석
        b.cbox(0, 3, 1, 5, 5, 8, body, 0.02);
        b.cbox(0, 8, 3.6, 2, 4, 2, 0x2a2a2d, 0);
        b.cbox(0, 3, -4, 6, 8, 4, trim, 0.02);
        b.cbox(0, 11, -4, 7, 1, 5, 0x1c1c1e, 0);
        b.cbox(0, 3, 5.6, 5.4, 2, 1, 0xe0b030, 0);
      } else {
        b.cbox(0, 3, 0, 6, 5, 11, body, 0.02);
        b.cbox(0, 8, 0, 6.4, 1, 11.4, trim, 0.02);
        b.cbox(0, 9, 0, 3, 3, 3, pick([0xe0b030, 0xe9e2d3, 0xb3282e], i), 0.02);
      }
    });
    cars.push(g);
  });
  let ang0 = 0;
  room.movers.push({ update: (dt, t) => {
    ang0 += dt * 0.32;
    cars.forEach((g, i) => {
      const a = ang0 - i * 0.24;
      g.position.set(tx + Math.cos(a) * R, 0, tz + Math.sin(a) * R);
      g.rotation.y = -a;
    });
    // 굴뚝 연기
    room.trainPuff = (room.trainPuff || 0) + dt * 5;
    while (room.trainPuff >= 1) {
      room.trainPuff -= 1;
      const p = cars[0].localToWorld(new THREE.Vector3(0, 13, 3.6));
      room.fx.steam.spawn({ x: p.x, y: p.y, z: p.z, vx: (Math.random() - 0.5) * 2, vy: 6 + Math.random() * 3, vz: (Math.random() - 0.5) * 2, wobble: 2, drag: 0.5, life: 0.9 + Math.random() * 0.5, size: 1 + Math.random() * 0.8, env: 'steam', color: 0xf2f2f2 });
    }
  } });
  // 벽난로 앞 흔들의자와 담요
  const rock = mover(room, (b) => {
    const A = at(b, 0, 0, 2);
    for (const s of [-1, 1]) {
      for (let i = -9; i <= 9; i += 1.5) A.cbox(s * 10, 1.4 - Math.cos((i / 9) * 0.9) * 1.4 + 1.2, i, 2, 1.4, 1.6, 0x6b4428, 0.03);
      A.cbox(s * 10, 2, 6, 2, 24, 2, 0x6b4428, 0.03);
      A.cbox(s * 10, 2, -6, 2, 56, 2, 0x6b4428, 0.03);
      A.cbox(s * 10, 34, 0, 2, 2, 14, 0x6b4428, 0.03);
    }
    A.cbox(0, 24, 0, 22, 3, 16, 0x7d5030, 0.03);
    for (let y = 30; y < 56; y += 5) A.cbox(0, y, -6.5, 18, 2, 1.6, 0x7d5030, 0.03);
    // 체크무늬 담요
    for (let x = -9; x < 9; x += 3) for (let k = 0; k < 6; k++) A.box(x, 27 - (k > 3 ? (k - 3) * 3 : 0), -6 + k * 2.6, 3, 1.4, 2.6, ((x + 9) / 3 + k) % 2 ? 0xb3282e : 0x2f6b3a, 0.03);
  });
  rock.position.set(-182, 0, 84);
  room.movers.push({ update: (dt, t) => { rock.rotation.x = Math.sin(t * 1.3) * 0.06; } });
  // 장작 더미
  for (let r = 0; r < 4; r++) for (let i = 0; i < 6 - r; i++) {
    const z = -122 + i * 7 + r * 3.5, y = r * 6;
    lit.box(-236, y, z, 22, 6, 6, pick([0x8a5a33, 0x7d5030, 0x94633a], i + r), 0.06);
    lit.box(-214.4, y + 1, z + 1, 0.6, 4, 4, 0xc9a26b, 0.04);
  }
  // 벽난로 옆 큰 지팡이 사탕
  candyCane(lit, -214, -74);
  candyCane(lit, -214, 74);
  // 벽난로 선반: 스노볼과 초
  disc(lit, -218, 116, 30, 4, 2, 1, () => 0x6b4428);
  disc(glow, -218, 118, 30, 4, 6, 1, (dx, dz, d) => (d > 3 ? 0xdff0ff : 0xb8d8f0));
  lit.cbox(-218, 118, 30, 2, 3, 2, 0x2f6b3a, 0);
  for (const [z, h] of [[-30, 8], [-24, 6]]) { lit.cbox(-218, 116, z, 2.4, h, 2.4, 0xb3282e, 0.02); glow.cbox(-218, 116 + h, z, 0.8, 1.4, 0.8, 0xffc14d, 0); }
  // 오른쪽 벽: 엇갈려 건 스키와 폴
  const sk = (z0, z1, c) => { for (let k = 0; k <= 30; k++) { const u = k / 30; room.kit.put(lit, 'right', z0 + (z1 - z0) * u - 1.5, 96 + u * 110, 3, 4, 2.2, c, 0.02, 0.2); } };
  sk(-150, -110, 0xb3282e);
  sk(-110, -150, 0x2f6fb0);
  for (const z of [-158, -102]) room.kit.put(lit, 'right', z, 100, 1, 96, 1.6, 0x9a9da0, 0.02, 0.4);
  // 오른쪽 뒤 구석: 나무 썰매와 선물
  const Sl = at(lit, 206, -168, 3);
  for (const s of [-1, 1]) for (let i = -16; i <= 18; i += 2) Sl.cbox(s * 9, i > 14 ? (i - 14) * 1.2 : 0, i, 2, 2, 2, 0xb3282e, 0.02);
  Sl.cbox(0, 6, 0, 22, 2, 32, 0x8a5a33, 0.04);
  for (const s of [-1, 1]) for (const t of [-10, 8]) Sl.cbox(s * 9, 2, t, 2, 4, 2, 0x6b4428, 0);
  for (const [dx, dz, w, h, c] of [[-4, -6, 10, 9, 0x2f6b3a], [5, 4, 9, 12, 0xe0b030], [-3, 8, 7, 6, 0xb3282e]]) { Sl.cbox(dx, 8, dz, w, h, w, c, 0.03); Sl.cbox(dx, 8, dz, 1.6, h + 0.4, w + 0.4, 0xf4f6fa, 0); }
  // 문 양옆 호두까기 병정
  nutcracker(lit, -58, 204);
  nutcracker(lit, 58, 204);
  // 문 위 겨우살이
  shell.cbox(0, 214, 206, 0.6, 12, 0.6, 0x2a2420, 0);
  for (let i = 0; i < 7; i++) shell.cbox(Math.cos(i) * 3, 206 + Math.sin(i * 1.7) * 2, 206 + Math.sin(i) * 3, 3, 2, 3, i % 2 ? 0x3a7a42 : 0x2f6b3a, 0.04);
  for (const [dx, dz] of [[1, 1], [-1, 0], [0, -1]]) shell.cbox(dx * 1.4, 204, 206 + dz * 1.4, 1.4, 1.4, 1.4, 0xf4f6fa, 0);
  shell.cbox(0, 211, 206, 5, 2, 2, 0xb3282e, 0);
}

function candyCane(lit, x, z) {
  for (let y = 0; y < 52; y += 2) lit.cbox(x, y, z, 3, 2, 3, Math.floor(y / 4) % 2 ? 0xf4f6fa : 0xb3282e, 0.02);
  for (let k = 0; k <= 8; k++) {
    const a = (k / 8) * Math.PI;
    lit.cbox(x + 0, 52 + Math.sin(a) * 6, z + (1 - Math.cos(a)) * 6 * Math.sign(-z || 1), 3, 2.4, 3, k % 2 ? 0xf4f6fa : 0xb3282e, 0.02);
  }
}

function nutcracker(lit, x, z) {
  lit.cbox(x, 0, z, 12, 3, 12, 0x2a2420, 0.02);
  for (const s of [-1, 1]) lit.cbox(x + s * 2, 3, z, 3, 12, 3, 0x1c1c1e, 0.02);
  lit.cbox(x, 15, z, 9, 14, 7, 0xb3282e, 0.03);
  lit.cbox(x, 18, z - 3.7, 1.4, 8, 0.6, 0xe0b030, 0);
  for (const s of [-1, 1]) lit.cbox(x + s * 5.6, 17, z, 2.4, 12, 3, 0xb3282e, 0.03);
  lit.cbox(x, 29, z, 6, 7, 6, 0xf0c8a0, 0.02);
  lit.cbox(x, 29, z - 3.1, 4, 2, 0.6, 0xf4f6fa, 0);
  lit.cbox(x, 36, z, 7, 12, 7, 0x1c1c1e, 0.02);
  lit.cbox(x, 37, z - 3.6, 2, 2, 0.6, 0xe0b030, 0);
}

// ── 6. 다락방 아지트 ─────────────────────────────────────
function decorAttic(room, lit, shell, glow) {
  const roofY = room.kit.roofY;
  // 왼쪽 뒤: 오락실 기계 (화면 속 게임이 돌아간다)
  const A = at(lit, -186, -150, 1);
  A.cbox(0, 0, -2, 30, 6, 28, 0x1c1c1e, 0.02);
  A.cbox(0, 6, -4, 28, 54, 22, 0x2a2a6a, 0.03);
  A.cbox(0, 60, 2, 28, 10, 14, 0x1c1c1e, 0.02);
  A.cbox(0, 70, -6, 28, 34, 18, 0x2a2a6a, 0.03);
  A.cbox(0, 104, -4, 30, 10, 22, 0x1c1c1e, 0.02);
  for (const s of [-1, 1]) A.cbox(s * 14.4, 6, -4, 1.4, 98, 22, pick([0xe04a8a, 0x5af0ff], s + 1), 0.02);
  A.cbox(-6, 70, 6, 2, 6, 2, 0x1c1c1e, 0);
  A.cbox(-6, 76, 6, 3.4, 3, 3.4, 0xc0392b, 0);
  for (const [dx, c] of [[3, 0xe0b030], [7, 0x5af0ff], [11, 0xe04a8a]]) A.cbox(dx, 70, 4, 2.4, 1.6, 2.4, c, 0);
  const [ax, az] = A.pt(0, 3.2);
  room.signs.push({ x: ax, y: 87, z: az, yaw: A.yaw, w: 22, h: 18, cw: 44, ch: 36, glow: true, anim: 1, draw: invaders });
  const [mx, mz] = A.pt(0, 7.4);
  room.signs.push({ x: mx, y: 109, z: mz, yaw: A.yaw, w: 26, h: 7, cw: 52, ch: 14, glow: true, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    room.kit.neon(g, 'ARCADE', w / 2, h / 2, '#ff5aa8', 10);
  } });
  // 뒷벽 왼쪽: 낮은 장 위 옛날 텔레비전과 게임기
  lit.cbox(-150, 0, -190, 48, 26, 18, 0x8a6440, 0.04);
  lit.cbox(-150, 26, -192, 34, 28, 22, 0x6a6460, 0.03);
  room.signs.push({ x: -150, y: 40, z: -180.8, w: 26, h: 20, cw: 52, ch: 40, glow: true, anim: 1, draw: retroGame });
  lit.cbox(-150, 54, -194, 2, 10, 2, 0x2a2a2e, 0);
  lit.cbox(-150, 64, -194, 14, 1, 1, 0x2a2a2e, 0);
  lit.cbox(-132, 26, -186, 10, 4, 8, 0x3a3a40, 0.02);
  lit.cbox(-132, 30, -186, 10.4, 0.6, 3, 0x8a4a9a, 0);
  for (const [x, z, c] of [[-162, -170, 0x2a2a2e], [-140, -166, 0x8a8a90]]) {
    lit.cbox(x, 0, z, 10, 2.2, 6, c, 0.02);
    lit.cbox(x - 3, 2.2, z, 2, 0.8, 2, 0xc0392b, 0);
  }
  // 오른쪽: 기타와 앰프, 지붕 창을 향한 망원경
  const G = at(lit, 212, -104, 3);
  G.cbox(0, 0, 0, 24, 26, 14, 0x1c1c1e, 0.02);
  G.cbox(0, 4, 7.2, 20, 18, 0.4, 0x3a3a40, 0);
  G.cbox(-6, 22, 7.4, 2, 2, 0.6, 0xe0b030, 0);
  G.cbox(18, 0, 4, 10, 2, 10, 0x2a2a2e, 0);
  G.cbox(18, 2, 4, 1.6, 20, 1.6, 0x2a2a2e, 0);
  for (let y = 10; y < 38; y += 2) {
    const r = y < 20 ? 7 : y < 24 ? 5 : 6.4;
    G.cbox(18, y, 6, r * 2, 2, 3, 0xc0602a, 0.04);
  }
  G.cbox(18, 18, 7.6, 3, 3, 0.6, 0x1c1c1e, 0);
  G.cbox(18, 38, 6, 2.4, 30, 2, 0x5a3a22, 0.02);
  G.cbox(18, 68, 6, 4, 6, 2.4, 0x2a2a2e, 0.02);
  const tsx = 176, tsz = 6;
  for (const [dx, dz] of [[-9, -6], [9, -6], [0, 10]]) for (let k = 0; k < 14; k++) lit.cbox(tsx + dx * (1 - k / 14), k * 4, tsz + dz * (1 - k / 14), 1.6, 4, 1.6, 0x3a3a40, 0);
  for (let k = 0; k < 10; k++) lit.cbox(tsx - 10 + k * 2.2, 58 + k * 2.6, tsz, 5, 5, 5, k < 2 ? 0x1c1c1e : 0xe9e2d3, 0.02);
  // 장난감 비행기 (지붕 아래 매달려 천천히 돈다)
  const top = roofY(-70);
  shell.cbox(-70, top - 22, 70, 0.4, 22, 0.4, 0xe9e2d3, 0);
  const plane = mover(room, (b) => {
    b.cbox(0, -2, 0, 4, 4, 20, 0xc0392b, 0.02);
    b.cbox(0, -1, 2, 26, 1.2, 6, 0xe9e2d3, 0.02);
    b.cbox(0, -1, -8, 10, 1, 3, 0xe9e2d3, 0.02);
    b.cbox(0, 1, -9, 1, 5, 3, 0xc0392b, 0.02);
    b.cbox(0, -2, 10, 1, 6, 0.6, 0x2a2a2e, 0);
  });
  plane.position.set(-70, top - 22, 70);
  room.movers.push({ update: (dt, t) => { plane.rotation.y = t * 0.25; plane.rotation.z = Math.sin(t * 0.7) * 0.08; } });
  // 소파 옆 나무 상자 위 용암 램프 (방울이 오르내린다)
  lit.cbox(-212, 0, 64, 16, 16, 16, 0x9a7048, 0.05);
  lit.cbox(-212, 16, 64, 6, 3, 6, 0x9a9da0, 0.02);
  disc(glow, -212, 19, 64, 3, 18, 1, (dx, dz, d) => (d > 2.2 ? 0x8a2a8a : 0xb04ab0));
  lit.cbox(-212, 37, 64, 4.4, 3, 4.4, 0x9a9da0, 0.02);
  const blobs = mover(room, (b) => {
    b.cbox(0, 0, 0, 2.4, 2.4, 2.4, 0xffa040, 0);
    b.cbox(0.4, 6, 0.4, 1.8, 1.8, 1.8, 0xff8a2a, 0);
  }, room.glowMat);
  blobs.position.set(-212, 20, 64);
  room.movers.push({ update: (dt, t) => { blobs.position.y = 21 + (Math.sin(t * 0.6) * 0.5 + 0.5) * 9; } });
  // 왼쪽 낮은 벽: 스케이트보드
  for (let k = 0; k < 18; k++) lit.box(-238, 6 + k * 3.4, 164 + k * 0.6, 3, 3.6, 10, k > 15 || k < 2 ? 0x2a2a2e : 0x2f8f8a, 0.03);
  for (const k of [2, 15]) for (const s of [-1, 1]) lit.cbox(-234, 8 + k * 3.4, 169 + k * 0.6 + s * 4, 2, 2.4, 2.4, 0xe0b030, 0);
  // 오른쪽 뒤 구석: 상자 더미 (잡동사니)
  for (const [x, y, z, w, h] of [[218, 0, -180, 30, 22], [192, 0, -184, 22, 18], [216, 22, -180, 22, 16]]) {
    lit.cbox(x, y, z, w, h, 22, 0xc49a5a, 0.06);
    lit.cbox(x, y + h - 0.2, z, w + 0.2, 0.6, 4, 0xd8c08a, 0);
    lit.cbox(x, y + h * 0.4, z + 11.1, w * 0.5, h * 0.25, 0.4, 0xf2ede0, 0);
  }
  // 앞 박공벽: 작은 농구대와 쓰레기통, 포스터
  const hoopX = 112;
  room.kit.put(lit, 'front', hoopX - 14, 150, 28, 20, 1.6, 0xf2ede0, 0.02);
  room.kit.put(lit, 'front', hoopX - 5, 152, 10, 8, 1.8, 0xc0392b, 0.02);
  for (let a = 0; a < 16; a++) {
    const ang = (a / 16) * Math.PI * 2;
    lit.cbox(hoopX + Math.cos(ang) * 6, 148, 220 - 8 + Math.sin(ang) * 6, 1.4, 1, 1.4, 0xe0602a, 0);
  }
  for (let k = 0; k < 4; k++) for (let a = 0; a < 8; a++) {
    const ang = (a / 8) * Math.PI * 2;
    lit.cbox(hoopX + Math.cos(ang) * (5 - k * 0.6), 144 - k * 3, 212 + Math.sin(ang) * (5 - k * 0.6), 0.6, 2.4, 0.6, 0xf2ede0, 0);
  }
  disc(lit, hoopX, 0, 206, 9, 28, 2, (dx, dz, d) => (d > 7.5 ? 0x5a6a7a : null));
  disc(lit, hoopX, 0, 206, 8, 2, 2, () => 0x4a5a6a);
  for (let i = 0; i < 6; i++) lit.cbox(hoopX - 5 + hash3(i, 1, 5) * 10, 20 + hash3(i, 2, 5) * 8, 202 + hash3(i, 3, 5) * 8, 4, 4, 4, 0xf2ede0, 0.08);
  for (let i = 0; i < 3; i++) lit.cbox(hoopX - 20 + i * 9, 0, 196 - i * 4, 4, 4, 4, 0xf2ede0, 0.08);
  framed(room, lit, 'front', -110, 120, 30, 42, 0x1c1c1e, (g, w, h) => {
    g.fillStyle = '#1a1030'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#ff5aa8'; g.fillRect(w * 0.2, h * 0.2, w * 0.6, h * 0.3);
    g.fillStyle = '#5af0ff'; g.fillRect(w * 0.3, h * 0.55, w * 0.4, h * 0.15);
    room.kit.txt(g, 'LEVEL UP', w / 2, h * 0.84, '#ffe680', 7);
  });
  hideout(room, lit, shell, glow);
  // 서까래를 따라 늘어진 전구 줄
  for (const z of [-60, 140]) {
    for (let x = -200; x <= 200; x += 16) {
      const y = roofY(x) - 14 - Math.abs(Math.sin(x / 32)) * 6;
      shell.cbox(x, y, z, 16, 0.6, 0.6, 0x2a2420, 0);
      glow.cbox(x, y - 4, z, 3.4, 4, 3.4, pick([0xfff0c0, 0xffc46a, 0xffe2a6], x / 16), 0);
    }
  }
}

// 다락방 아지트다움: 손으로 만든 것들
function hideout(room, lit, shell, glow) {
  const put = room.kit.put;
  // 소파 위 이불 텐트: 낮은 벽 꼭대기에서 장대 둘까지 처진 줄무늬 천
  const cloth = [0xd8a03a, 0xe9e2d0, 0xc0602a, 0xe9e2d0];
  for (let z = -76, i = 0; z < 56; z += 6, i++) {
    for (let k = 0; k <= 8; k++) {
      const u = k / 8, x = -238 + u * 44, y = 102 - u * 24 - Math.sin(u * Math.PI) * 6;
      shell.box(x, y, z, 5.6, 2, 6, pick(cloth, i), 0.04);
    }
    if (i % 4 === 0) glow.cbox(-212, 88 - (i % 8 ? 2 : 0), z + 3, 2.2, 2.6, 2.2, pick([0xffe2a6, 0xff9ad0, 0x9af0ff], i / 4), 0);
  }
  for (const z of [-76, 54]) {
    lit.cbox(-194, 0, z + 1, 2.4, 80, 2.4, 0x8a6440, 0.03);
    lit.cbox(-194, 0, z + 1, 8, 2, 8, 0x6b4a2e, 0.02);
  }
  for (let k = 0; k < 10; k++) lit.cbox(-194 + k * 0.2, 78 - k * 7.8, 56.5, 0.6, 1.6, 0.6, 0xe9e2d0, 0);
  // 바닥 출입구: 열린 뚜껑과 올라온 사다리
  lit.box(-172, 0, 178, 40, 0.6, 30, 0x1a120c, 0);
  for (const [x, z, w, d] of [[-174, 176, 44, 2], [-174, 208, 44, 2], [-174, 176, 2, 34], [-132, 176, 2, 34]]) lit.box(x, 0, z, w, 1.6, d, 0x6b4a2e, 0.03);
  for (let i = 0; i < 6; i++) lit.box(-172 + i * 6.6, 0, 210, 6, 30, 2, pick([0x9a7048, 0x8a6440], i), 0.04);
  for (const x of [-150, -142]) lit.cbox(x, 0, 186, 2, 46, 2, 0x8a6440, 0.03);
  for (let y = 8; y < 46; y += 9) lit.cbox(-146, y, 186, 9, 1.6, 1.6, 0x8a6440, 0.03);
  // 뒷벽 끈에 집게로 꽂은 즉석 사진들
  // (선반 오른쪽, 기운 지붕과 포스터 사이)
  for (let x = 64; x < 102; x += 2) shell.box(x, 174 - Math.sin(((x - 64) / 38) * Math.PI) * 4, -199.2, 2, 0.5, 0.5, 0x2a2420, 0);
  [71, 83, 95].forEach((x, i) => {
    const y = 174 - Math.sin(((x + 1 - 64) / 38) * Math.PI) * 4;
    lit.box(x - 0.6, y - 1.5, -198.8, 1.2, 3, 1, 0xd8a03a, 0);
    room.signs.push({ x: x, y: y - 9, z: -198.4, w: 11, h: 13, cw: 22, ch: 26, draw: (g, w, h) => {
      g.fillStyle = '#f6f2e8'; g.fillRect(0, 0, w, h);
      g.fillStyle = ['#5a8acf', '#e8a04a', '#6aa04a', '#c05a8a'][i]; g.fillRect(2, 2, w - 4, h - 9);
      g.fillStyle = '#f0c8a0';
      for (let k = 0; k < 2 + (i % 2); k++) g.fillRect(4 + k * 5, h - 13, 3, 4);
      g.fillStyle = '#3a2a20'; g.fillRect(3, h - 4, w - 10, 1);
    } });
  });
  // 앞 박공벽: 손으로 그린 해골 깃발
  put(lit, 'front', 36, 116, 1.6, 40, 2, 0x6b4a2e, 0.02, 0);
  room.signs.push({ x: 56, y: 140, z: 220 - 1.4, yaw: Math.PI, w: 36, h: 24, cw: 54, ch: 36, draw: (g, w, h) => {
    g.fillStyle = '#1c1a20'; g.fillRect(0, 0, w, h);
    g.fillStyle = '#f4f0e6';
    g.beginPath(); g.arc(w / 2, h * 0.42, 8, 0, Math.PI * 2); g.fill();
    g.fillRect(w / 2 - 5, h * 0.52, 10, 6);
    g.fillStyle = '#1c1a20';
    g.fillRect(w / 2 - 5, h * 0.36, 3, 3); g.fillRect(w / 2 + 2, h * 0.36, 3, 3);
    g.fillRect(w / 2 - 3, h * 0.66, 1, 2); g.fillRect(w / 2, h * 0.66, 1, 2); g.fillRect(w / 2 + 3, h * 0.66, 1, 2);
    g.strokeStyle = '#f4f0e6'; g.lineWidth = 2;
    g.beginPath(); g.moveTo(8, h - 6); g.lineTo(w - 8, h - 14); g.moveTo(8, h - 14); g.lineTo(w - 8, h - 6); g.stroke();
  } });
  // 바 옆 칠판: 아지트 규칙
  framed(room, lit, 'back', -84, 146, 28, 34, 0x6b4a2e, (g, w, h) => {
    g.fillStyle = '#2f3a33'; g.fillRect(0, 0, w, h);
    room.kit.txt(g, '규칙', w / 2, 6, '#ffe9a0', 8);
    ['1. 비밀 지키기', '2. 마지막 잔', '   같이 비우기', '3. 게임은', '   한 판만…'].forEach((m, i) => room.kit.txt(g, m, 3, 15 + i * 6.4, '#eeeadf', 6, 'left'));
  });
}

function invaders(g, w, h, t) {
  g.fillStyle = '#05050f'; g.fillRect(0, 0, w, h);
  const dx = Math.round(Math.sin(t * 0.8) * 6);
  const frame = Math.floor(t * 2) % 2;
  for (let r = 0; r < 3; r++) for (let c = 0; c < 5; c++) {
    if ((r * 5 + c + Math.floor(t / 4)) % 11 === 0) continue;
    const x = 6 + c * 7 + dx, y = 4 + r * 6;
    g.fillStyle = ['#ff5aa8', '#5af0ff', '#8aff8a'][r];
    g.fillRect(x, y, 5, 3);
    g.fillRect(x + (frame ? 0 : 1), y + 3, 1, 1);
    g.fillRect(x + (frame ? 4 : 3), y + 3, 1, 1);
  }
  const px = Math.round(w / 2 + Math.sin(t * 1.3) * 14);
  g.fillStyle = '#ffe680'; g.fillRect(px - 3, h - 6, 7, 3); g.fillRect(px, h - 8, 1, 2);
  const by = h - 10 - ((t * 30) % (h - 12));
  g.fillRect(px, Math.round(by), 1, 2);
}

function retroGame(g, w, h, t) {
  g.fillStyle = '#5a8aff'; g.fillRect(0, 0, w, h);
  g.fillStyle = '#e8f0ff';
  for (let i = 0; i < 3; i++) { const cx = ((i * 19 - t * 6) % (w + 12) + w + 12) % (w + 12) - 6; g.fillRect(Math.round(cx), 6 + i * 3, 9, 3); }
  g.fillStyle = '#3aa040'; g.fillRect(0, h - 8, w, 8);
  g.fillStyle = '#8a5a2a'; g.fillRect(0, h - 4, w, 4);
  for (let x = 0; x < w; x += 8) { g.fillStyle = '#c0602a'; g.fillRect(((x - t * 20) % w + w) % w, h - 16, 6, 6); }
  const jump = Math.abs(Math.sin(t * 3)) * 9;
  g.fillStyle = '#e04a3a'; g.fillRect(14, Math.round(h - 16 - jump), 5, 5);
  g.fillStyle = '#f0c8a0'; g.fillRect(15, Math.round(h - 19 - jump), 3, 3);
  // 화면 줄무늬 (브라운관)
  g.fillStyle = 'rgba(0,0,0,0.18)';
  for (let y = 0; y < h; y += 2) g.fillRect(0, y, w, 1);
}

// ── 7. 이자카야 ─────────────────────────────────────────
function chochin(lit, glow, x, y, z, s = 1, c = 0xd8302a) {
  lit.cbox(x, y + 16 * s, z, 0.8, 14, 0.8, 0x1f1a17, 0);
  lit.cbox(x, y + 13 * s, z, 7 * s, 2 * s, 7 * s, 0x1c1c1e, 0.02);
  for (let k = 0; k < 6; k++) {
    const r = (6 + Math.sin((k / 5) * Math.PI) * 2.6) * s;
    disc(glow, x, y + k * 2 * s, z, r, 2 * s, 1, (dx, dz, d) => (d > r - 1.2 ? (k % 2 ? c : 0xa81e1a) : null));
  }
  glow.cbox(x, y + 1 * s, z, 7 * s, 10 * s, 7 * s, 0xffb070, 0);
  lit.cbox(x, y - 2 * s, z, 7 * s, 2 * s, 7 * s, 0x1c1c1e, 0.02);
}

function decorIzakaya(room, lit, shell, glow) {
  // 화로 위 꼬치: 몇 초마다 하나씩 뒤집힌다
  const G = room.grillAt;
  const sticks = [];
  for (let i = 0; i < 8; i++) {
    const g = mover(room, (b) => {
      b.cbox(0, -0.3, 0, 0.6, 0.6, 15, 0xd8c08a, 0);
      for (let k = 0; k < 4; k++) b.cbox(0, -1.2, -4.5 + k * 3, 2.6, 2.4, 2.6, pick([0xa8642e, 0x8a4a22, 0xc07a3a, 0x6b8a3a], i + k * (i % 3 === 0 ? 3 : 1)), 0.06);
    });
    g.position.set(G.x0 + 3 + i * ((G.x1 - G.x0 - 6) / 7), G.y + 1.4, G.z);
    sticks.push({ g, ph: i * 1.7, flip: 0 });
  }
  room.movers.push({ update: (dt, t) => {
    for (const s of sticks) {
      const cyc = (t + s.ph) % 9;
      const target = Math.floor((t + s.ph) / 9) % 2 ? Math.PI : 0;
      s.flip += (target - s.flip) * Math.min(1, dt * (cyc < 0.6 ? 10 : 2));
      s.g.rotation.z = s.flip;
      s.g.position.y = G.y + 1.4 + (cyc < 0.5 ? Math.sin(cyc / 0.5 * Math.PI) * 3 : 0);
    }
  } });
  // 손님 쪽 카운터 끝: 손 흔드는 복고양이
  const nx = 84, nz = -160, T = TABLE_Y;
  lit.cbox(nx, T, nz, 9, 10, 8, 0xf4f0e8, 0.03);
  lit.cbox(nx, T + 10, nz, 9, 8, 8, 0xf4f0e8, 0.03);
  for (const s of [-1, 1]) lit.cbox(nx + s * 3, T + 18, nz, 2.4, 2.4, 2, 0xf4f0e8, 0.02);
  for (const s of [-1, 1]) lit.cbox(nx + s * 2, T + 13.6, nz + 4.1, 1.2, 1, 0.4, 0x2a2420, 0);
  lit.cbox(nx, T + 8.6, nz + 4.1, 6, 1.4, 0.4, 0xd8302a, 0);
  lit.cbox(nx, T + 6.4, nz + 4.2, 2, 2, 0.6, 0xe0b030, 0);
  lit.cbox(nx - 2, T + 3, nz + 4.1, 4, 4, 0.6, 0xe0b030, 0);
  const paw = mover(room, (b) => {
    b.cbox(0, 0, 0, 3, 7, 3, 0xf4f0e8, 0.02);
    b.cbox(0, 7, 0, 3.4, 2.4, 3.4, 0xf4f0e8, 0.02);
  });
  paw.position.set(nx + 4.6, T + 11, nz);
  room.movers.push({ update: (dt, t) => { paw.rotation.x = -0.4 + Math.sin(t * 3) * 0.5; } });
  // 초롱: 카운터 양 끝, 문 양옆
  chochin(lit, glow, -104, 150, -132);
  chochin(lit, glow, 104, 150, -132);
  chochin(lit, glow, -60, 150, 206, 1.2);
  chochin(lit, glow, 60, 150, 206, 1.2);
  // 왼쪽 뒤 구석: 짚으로 싼 술통 쌓기
  for (const [x, y, z] of [[-210, 0, -170], [-180, 0, -176], [-195, 30, -173]]) {
    for (let k = 0; k < 30; k += 2) disc(lit, x, y + k, z, 14, 2, 2, (dx, dz, d) => (d > 12.5 ? (k % 10 < 2 ? 0x2a2a30 : 0xd8c48a) : 0xc8b07a));
    lit.cbox(x, y + 12, z + 13.6, 12, 8, 0.6, 0xf4f0e8, 0);
    lit.cbox(x, y + 14, z + 13.9, 5, 4, 0.4, 0xd8302a, 0);
  }
  // 오른쪽 뒤 구석: 맥주 상자와 됫병
  for (const [x, y, z] of [[210, 0, -176], [210, 18, -176], [186, 0, -180]]) {
    lit.cbox(x, y, z, 22, 18, 18, 0x2a4a8a, 0.04);
    for (let k = 0; k < 4; k++) lit.cbox(x - 7 + k * 4.6, y + 18, z, 3, 4, 3, 0x6b3a12, 0.03);
  }
  // 왼쪽 앞: 오뚝이(다루마)와 작은 장식장
  lit.cbox(-215, 0, 150, 30, 60, 22, 0x3a2618, 0.03);
  lit.cbox(-215, 60, 150, 32, 2, 24, 0x2a1a10, 0.02);
  disc(lit, -220, 62, 150, 6, 12, 1, (dx, dz, d) => (d > 5 ? 0xd8302a : 0xc8281f));
  lit.cbox(-220, 66, 155.6, 6, 5, 0.6, 0xf4f0e8, 0);
  for (const dx of [-1.4, 1.4]) lit.cbox(-220 + dx, 68, 156, 1, 1, 0.4, 0x1c1c1e, 0);
  lit.cbox(-208, 62, 150, 5, 10, 5, 0xd8c08a, 0.03);
  lit.cbox(-208, 72, 150, 2.4, 5, 2.4, 0xd8c08a, 0.03);
  // 오른쪽 벽: 손글씨 오늘의 추천
  framed(room, lit, 'right', 150, 130, 40, 44, 0x3a2618, (g, w, h) => {
    g.fillStyle = '#efe6d0'; g.fillRect(0, 0, w, h);
    room.kit.txt(g, '오늘의 추천', w / 2, 7, '#a8241e', 8);
    ['참치 뱃살', '고등어구이', '모츠나베', '타코와사비'].forEach((m, i) => room.kit.txt(g, m, w / 2, 19 + i * 8, '#2a1a10', 8));
  });
}

// ── 8. 루프탑 바 ────────────────────────────────────────
function patioHeater(lit, glow, room, x, z) {
  disc(lit, x, 0, z, 9, 3, 2, () => 0x3a3a40);
  lit.cbox(x, 3, z, 3, 150, 3, 0x9a9da0, 0.02);
  disc(glow, x, 150, z, 5, 12, 1, (dx, dz, d) => (d > 4 ? 0xff7a2a : 0xffb050));
  disc(lit, x, 164, z, 14, 2, 2, () => 0x9a9da0);
  lit.cbox(x, 166, z, 4, 3, 4, 0x9a9da0, 0);
}

function decorRooftop(room, lit, shell, glow) {
  for (const [x, z] of [[-205, 30], [205, -60], [-120, 200]]) patioHeater(lit, glow, room, x, z);
  // 왼쪽 앞: ㄱ자 소파와 낮은 탁자
  const S = at(lit, -200, 170, 1);
  S.cbox(0, 0, 0, 70, 18, 26, 0x2a2a30, 0.03);
  S.cbox(0, 18, 3, 66, 6, 20, 0xe8dcc4, 0.04);
  S.cbox(0, 18, -11, 70, 26, 6, 0x2a2a30, 0.03);
  for (const [dx, c] of [[-22, 0x2f6a6a], [10, 0xd8a050]]) S.cbox(dx, 24, -6, 14, 12, 5, c, 0.05);
  lit.cbox(-160, 0, 186, 28, 22, 22, 0x5a3e2c, 0.04);
  lit.cbox(-166, 22, 186, 3, 9, 3, 0xdcefee, 0.02);
  lit.cbox(-166, 23, 186, 2.4, 4, 2.4, 0xd8302a, 0.02);
  lit.cbox(-154, 22, 182, 6, 3, 6, 0xf3e6c8, 0.02);
  glow.cbox(-154, 26, 182, 1, 1.4, 1, 0xffc14d, 0);
  // 난간 따라 화분: 키 작은 나무와 억새
  for (const [x, z] of [[-224, -150], [224, -150], [-224, 90], [224, 100], [0, 206], [-80, 206], [90, 206]]) {
    lit.cbox(x, 0, z, 18, 22, 18, 0x3a3a40, 0.03);
    lit.cbox(x, 22, z, 16, 1, 16, 0x3b2a1e, 0.05);
    for (let k = 0; k < 7; k++) {
      const a = k * 0.9, h = 20 + hash3(k, Math.round(x), 3) * 26;
      lit.cbox(x + Math.cos(a) * 4, 23, z + Math.sin(a) * 4, 1.6, h, 1.6, 0x4f7d3a, 0.04);
      lit.cbox(x + Math.cos(a) * 6, 23 + h, z + Math.sin(a) * 6, 5, 4, 5, k % 2 ? 0x5f9445 : 0x6aa64d, 0.06);
    }
  }
  // 오른쪽: 높은 원탁 둘
  for (const [x, z] of [[200, 20], [200, 80]]) {
    disc(lit, x, 0, z, 10, 2, 2, () => 0x2a2a30);
    lit.cbox(x, 2, z, 3, 96, 3, 0x2a2a30, 0.02);
    disc(lit, x, 98, z, 12, 2, 2, () => 0x5a3e2c);
    lit.cbox(x - 4, 100, z, 3, 7, 3, 0xdcefee, 0.02);
    lit.cbox(x - 4, 100.6, z, 2.4, 3, 2.4, 0xd8a050, 0.02);
  }
}

// ── 9. 편의점 앞 파라솔 ─────────────────────────────────
function patioSet(lit, glow, x, z, canopy) {
  disc(lit, x, 64, z, 24, 3, 2, () => 0xf2f2ee);
  for (let i = 0; i < 4; i++) {
    const a = (i / 4) * Math.PI * 2 + 0.4;
    lit.cbox(x + Math.cos(a) * 14, 0, z + Math.sin(a) * 14, 3, 64, 3, 0xd8d8d2, 0.02);
  }
  lit.cbox(x, 0, z, 2.4, 200, 2.4, 0xd8d8d2, 0.02);
  for (let k = 0; k < 7; k++) {
    const r = 62 * (1 - k / 7);
    disc(canopy, x, 192 + k * 2.6, z, r, 2.6, 4, (dx, dz, d) => (d < r - 12 && k < 6 ? null : Math.floor(((Math.atan2(dz, dx) + Math.PI) / (Math.PI * 2)) * 10) % 2 ? 0xe05a3a : 0xf2f2ee));
  }
  for (const [a, c] of [[0.6, 0x2f8f6a], [2.4, 0xf2f2ee], [4.2, 0x2f6fb0]]) {
    const sx = x + Math.cos(a) * 34, sz = z + Math.sin(a) * 34;
    disc(lit, sx, 40, sz, 12, 3, 2, () => c);
    for (let i = 0; i < 4; i++) {
      const b2 = (i / 4) * Math.PI * 2 + Math.PI / 4;
      lit.cbox(sx + Math.cos(b2) * 7.5, 0, sz + Math.sin(b2) * 7.5, 3, 40, 3, c, 0.02);
    }
  }
  // 빈 캔, 컵라면 용기, 나무젓가락
  const T = 67;
  for (const [dx, dz, c] of [[-8, -4, 0xc0392b], [-3, -9, 0x2f6fb0], [6, 8, 0xe0b030]]) { lit.cbox(x + dx, T, z + dz, 4.6, 8, 4.6, c, 0.03); lit.cbox(x + dx, T + 8, z + dz, 4, 0.6, 4, 0xb9bec2, 0); }
  lit.cbox(x + 9, T, z - 6, 8, 7, 8, 0xf2f2ee, 0.02);
  lit.cbox(x + 9, T + 7, z - 6, 8.4, 0.6, 8.4, 0xe05a2a, 0.02);
  lit.cbox(x + 9.5, T + 7.6, z - 6, 0.6, 0.6, 13, 0xd8c08a, 0);
}

function decorConv(room, lit, shell, glow) {
  patioSet(lit, glow, -186, -142, shell);
  patioSet(lit, glow, 190, -140, shell);
  // 가게 앞: 생수 묶음, 쓰레기통과 분리수거함
  for (let r = 0; r < 3; r++) for (let i = 0; i < 3 - r; i++) {
    const x = -222 + i * 14 + r * 7, y = r * 24;
    lit.box(x - 6.5, y, -188, 13, 24, 13, 0xcfe6f0, 0.03);
    lit.box(x - 6.6, y + 8, -188.1, 13.2, 6, 13.2, 0x3a8ae0, 0.02);
  }
  for (const [x, c, lid] of [[208, 0x2f8f6a, 0x1c5a40], [224, 0x2f6fb0, 0x1c3a6a]]) {
    lit.cbox(x, 0, -96, 14, 44, 16, c, 0.03);
    lit.cbox(x, 44, -96, 15, 3, 17, lid, 0.02);
    lit.cbox(x, 38, -87.6, 8, 2, 0.6, 0x1c1c1e, 0);
  }
  for (let i = 0; i < 5; i++) lit.cbox(200 + hash3(i, 1, 9) * 30, 0, -78 + hash3(i, 2, 9) * 14, 4.6, 8, 4.6, pick([0xc0392b, 0x2f6fb0, 0xe0b030, 0xb9bec2], i), 0.04);
  // 배달 오토바이 (앞 왼쪽, 가로등 옆)
  const M = at(lit, -170, 168, 1);
  for (const z of [-26, 26]) for (let a = 0; a < 16; a++) {
    const ang = (a / 16) * Math.PI * 2;
    M.cbox(0, 12 + Math.sin(ang) * 11, z + Math.cos(ang) * 11, 4, 3, 3, 0x1c1c1e, 0.02);
  }
  M.cbox(0, 20, 0, 12, 14, 40, 0xe8e8e4, 0.03);
  M.cbox(0, 34, -6, 14, 4, 24, 0x2a2a30, 0.03);
  M.cbox(0, 20, 22, 10, 30, 6, 0xe8e8e4, 0.03);
  M.cbox(0, 50, 24, 26, 2, 2, 0x2a2a30, 0);
  M.cbox(0, 38, -26, 26, 26, 22, 0xd8302a, 0.03);
  M.cbox(0, 46, -14.6, 20, 6, 0.6, 0xf2f2ee, 0);
  // 오른쪽 벽돌 골목: 실외기 둘, 배관
  for (const [z, y] of [[40, 0], [80, 0]]) {
    lit.box(214, y, z - 16, 24, 30, 32, 0xd8d8d2, 0.03);
    for (let a = 0; a < 18; a++) {
      const ang = (a / 18) * Math.PI * 2;
      lit.box(213.4, y + 15 + Math.sin(ang) * 10, z + Math.cos(ang) * 10, 0.8, 1.6, 1.6, 0x5a5e62, 0);
    }
  }
  room.kit.put(lit, 'right', -200, 200, 420, 3, 4, 0x9aa0a8, 0.02, 2);
  // 왼쪽 셔터에 붙은 전단과 낙서
  framed(room, lit, 'left', -60, 90, 22, 30, 0xf2f2ee, (g, w, h) => {
    g.fillStyle = '#f2f2ee'; g.fillRect(0, 0, w, h);
    room.kit.txt(g, '임대', w / 2, 9, '#c0201a', 11);
    room.kit.txt(g, '문의', w / 2, 21, '#1a1a1a', 8);
    g.fillStyle = '#1a1a1a'; for (let i = 0; i < 4; i++) g.fillRect(3 + i * 4, h - 6, 2, 4);
  });
  room.signs.push({ x: -237.2, y: 70, z: 60, yaw: Math.PI / 2, w: 60, h: 24, cw: 60, ch: 24, glow: false, draw: (g, w, h) => {
    g.clearRect(0, 0, w, h);
    g.fillStyle = 'rgba(0,0,0,0)'; g.fillRect(0, 0, w, h);
    g.save(); g.rotate(-0.08);
    room.kit.txt(g, '짠!', w / 2 - 8, h / 2 + 2, '#ff5aa8', 18);
    g.restore();
  } });
}

const DECOR = { bar: decorBar, pocha: decorPocha, hof: decorHof, wine: decorWine, xmas: decorXmas, attic: decorAttic, izakaya: decorIzakaya, rooftop: decorRooftop, conv: decorConv };

export function addDecor(id, room, lit, shell, glow) {
  DECOR[id]?.(room, lit, shell, glow);
}
