import * as THREE from 'three';
import { BoxBuilder, VoxelGrid, meshGrid, hash3 } from './voxel.js';

// ── 따르는 그릇 (병, 캔, 피처, 셰이커 …) ──────────────────────
const S = (n, r) => new Array(n).fill(r);
const NO_LABEL = [0, -1, 0];

// label: [아래층, 위층, 색] 바깥 껍질에만 칠한다. liquidLabel이면 그 띠를 담긴 술 색으로 칠한다(투명한 그릇).
const VESSELS = {
  beer: { color: 0x6b3a12, radii: [...S(14, 3.0), 2.8, 2.4, 2.0, 1.6, 1.4, ...S(5, 1.2), 1.5], label: [4, 10, 0xefe2c4], cap: [1, 0xc9a23a], angle: 2.1 },
  wine: { color: 0x233d26, radii: [...S(17, 3.3), 3.0, 2.4, 1.8, ...S(8, 1.3), 1.5], label: [5, 12, 0xf2ead8], cap: [3, 0x6e1424], angle: 2.0 },
  champagne: { color: 0x2f4a2a, radii: [...S(15, 3.4), 3.2, 2.8, 2.3, 1.9, 1.6, ...S(6, 1.4), 1.7], label: [4, 9, 0xf3e7c9], cap: [7, 0xd9b452], angle: 2.0 },
  whisky: { color: 0xb8641c, square: true, radii: [...S(12, 3.0), 2.6, 2.0, ...S(4, 1.2), 1.5, 1.5], label: [3, 9, 0x2d2a26], cap: [2, 0x1f1a17], angle: 2.0 },
  // 진, 보드카, 럼, 데킬라 같은 증류주 병
  spirit: { color: 0xd9e8e4, radii: [...S(12, 2.9), 2.8, 2.4, 1.8, 1.3, ...S(4, 1.05), 1.3], label: [3, 9, 0x2f5d8a], cap: [2, 0x2b2420], angle: 2.05 },
  // 리큐어, 시럽처럼 작은 병
  liqueur: { color: 0x3a2414, radii: [...S(8, 2.4), 2.3, 2.0, 1.5, 1.1, ...S(3, 0.95), 1.15], label: [2, 6, 0xefe2c4], cap: [1, 0x2b2420], angle: 2.1 },
  soju: { color: 0x3f8f4f, radii: [...S(11, 2.7), 2.4, 2.0, 1.6, ...S(5, 1.1), 1.3, 1.3], label: [3, 8, 0xe8f2ec], cap: [2, 0x2f6e3c], angle: 2.15 },
  makgeolli: { color: 0xf2efe6, radii: [...S(13, 3.0), 2.6, 2.0, ...S(3, 1.4), 1.6, 1.6], label: [3, 10, 0x3c7a46], cap: [2, 0x2f6e3c], angle: 2.0 },
  tokkuri: { color: 0xe9e2d3, radii: [2.4, 3.2, 3.6, 3.7, 3.7, 3.6, 3.2, 2.6, 1.9, 1.4, 1.2, 1.2, 1.5], label: [3, 4, 0x2f5d9c], cap: [0, 0], angle: 1.6 },
  shaker: { color: 0xc9ccce, metal: true, radii: [...Array.from({ length: 10 }, (_, i) => 2.8 + i * 0.05), 3.4, 2.7, 2.1, 1.5, 1.0], label: [9, 10, 0xa9adb0], cap: [1, 0xa9adb0], angle: 2.0 },
  // 저어서 만드는 칵테일용 믹싱 글라스
  mixglass: { glassy: true, color: 0xdcefee, radii: [3.3, 3.5, ...S(8, 3.6), 3.7], label: [0, 6, 0], liquidLabel: true, cap: [0, 0], angle: 1.9 },
  // 블렌더 통 (받침은 따로)
  blender: { glassy: true, color: 0xe2f0ef, radii: [2.4, 2.7, 3.0, 3.2, 3.4, 3.6, 3.8, 3.9, 4.0, 4.0, 4.0, 4.0, 4.1, 4.1], label: [0, 9, 0], liquidLabel: true, cap: [0, 0], angle: 2.0 },
  // 손잡이 달린 유리 피처: 술이 비치고 위엔 거품
  pitcher: { glassy: true, color: 0xdcefee, radii: [3.8, 4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.9, 4.8, 4.7], label: [0, 10, 0], liquidLabel: true, band: [11, 12, 0xfdf6e6], cap: [0, 0], handle: { from: 3, to: 11, out: 3 }, angle: 1.7 },
  can: { color: 0xc4161c, radii: [2.3, ...S(10, 2.6), 2.3], label: [3, 8, 0xf3efe6], cap: [1, 0xc9cdd0], angle: 2.0 },
  // 주스 팩
  carton: { color: 0xf8f4ea, square: true, radii: [...S(12, 2.5), 2.1, 1.5, 0.8], label: [2, 9, 0xf3a12a], cap: [1, 0x3f8f3f], angle: 1.9 },
  // 크림 담은 작은 우유 주전자
  creamer: { color: 0xf4efe4, radii: [2.0, 2.4, 2.6, 2.7, 2.7, 2.6, 2.4, 2.3, 2.4], label: NO_LABEL, cap: [0, 0], handle: { from: 2, to: 7, out: 2 }, angle: 1.7 },
  // 모래시계 모양 드립 서버: 아래엔 커피가 비치고 허리엔 나무 띠
  carafe: { glassy: true, color: 0xd6eae8, radii: [3.4, 3.7, 3.8, 3.7, 3.4, 2.9, 2.3, 1.8, 1.7, 1.9, 2.4, 2.9, 3.2], label: [0, 4, 0x4a2412], band: [7, 8, 0xa8743f], cap: [0, 0], angle: 1.9 },
};

const propCache = new Map();

function revolveProp(spec, opts) {
  const H = spec.radii.length;
  const maxR = Math.max(...spec.radii);
  const R = Math.ceil(maxR) + (spec.handle ? spec.handle.out + 2 : 1);
  const grid = new VoxelGrid(R, 0, H + 1);
  const color = opts.color ?? spec.color;
  const [lf, lt, specLabel] = spec.label;
  const lc = spec.liquidLabel ? opts.liquid ?? 0xe5a227 : opts.label ?? specLabel;
  const [capN, capColor] = spec.cap;
  const band = opts.noBand ? null : spec.band;
  spec.radii.forEach((r, y) => {
    for (let x = -R; x < R; x++) {
      for (let z = -R; z < R; z++) {
        const d = spec.square ? Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5)) : Math.hypot(x + 0.5, z + 0.5);
        if (d >= r) continue;
        let c = color;
        if (y >= lf && y <= lt && d > r - 1) c = lc;
        if (capN && y >= H - capN) c = opts.cap ?? capColor;
        if (band && y >= band[0] && y <= band[1]) c = band[2];
        grid.set(x, y, z, c);
      }
    }
  });
  // 손잡이는 잔에서 먼 쪽(-x)
  if (spec.handle) {
    const { from, to, out } = spec.handle;
    const wall = (y) => Math.floor(spec.radii[y] - 0.5);
    const bar = Math.max(wall(from), wall(to)) + out;
    for (const z of [-1, 0]) {
      for (let y = from + 1; y < to; y++) grid.set(-bar - 1, y, z, color);
      for (const y of [from, to]) for (let x = wall(y); x <= bar; x++) grid.set(-x - 1, y, z, color);
    }
  }
  return { grid, H, mouth: new THREE.Vector3(0, H, 0) };
}

function teapot({ body = 0xd9c9a8, accent = 0x5a7a6a } = {}) {
  const radii = [3.0, 3.8, 4.3, 4.6, 4.7, 4.7, 4.5, 4.1, 3.4, 2.4, 1.0];
  const H = radii.length;
  const grid = new VoxelGrid(11, 0, H + 1);
  radii.forEach((r, y) => {
    for (let x = -6; x < 6; x++) {
      for (let z = -6; z < 6; z++) {
        const d = Math.hypot(x + 0.5, z + 0.5);
        if (d < r) grid.set(x, y, z, y === 8 || y >= 9 ? accent : body);
      }
    }
  });
  // 주둥이(+x)와 손잡이(-x)
  for (let t = 0; t < 6; t++) for (const z of [-1, 0]) {
    grid.set(4 + t, 2 + t, z, body);
    grid.set(4 + t, 3 + t, z, body);
  }
  for (const z of [-1, 0]) {
    for (let y = 3; y <= 7; y++) grid.set(-7, y, z, accent);
    grid.set(-6, 2, z, accent);
    grid.set(-6, 8, z, accent);
  }
  return { grid, H, mouth: new THREE.Vector3(10, 9, 0), spout: true };
}

// 주둥이로 따르는 것들
const SPOUTED = {
  teapot: { body: 0xd9c9a8, accent: 0x5a7a6a },
  // 양은 주전자 (막걸리, 동동주)
  kettle: { body: 0xd8ac4c, accent: 0xc2943a, metal: true },
};

// step: { v: 그릇 종류, color: 몸통 색, label: 라벨 색, liquid: 비치는 술 색, cap: 뚜껑 색 }
export function buildVessel(step) {
  const kind = SPOUTED[step.v] || VESSELS[step.v] ? step.v : 'beer';
  const key = `${kind}|${step.color ?? ''}|${step.label ?? ''}|${step.liquid ?? ''}|${step.cap ?? ''}|${step.noBand ? 1 : 0}`;
  if (propCache.has(key)) return propCache.get(key);
  let built, metal = false, angle = 1.0, glassy = false;
  const opts = step;
  if (SPOUTED[kind]) {
    built = teapot(SPOUTED[kind]);
    metal = !!SPOUTED[kind].metal;
  } else {
    const spec = VESSELS[kind];
    built = revolveProp(spec, opts);
    metal = !!spec.metal || kind === 'can';
    angle = spec.angle;
    glassy = !!spec.glassy;
  }
  const geo = meshGrid(built.grid, { jitter: 0.03 });
  // 입구가 원점에 오도록 옮긴다
  geo.translate(-built.mouth.x, -built.mouth.y, -built.mouth.z);
  geo.computeBoundingBox();
  const result = { geo, metal, angle, glassy, spout: !!built.spout, box: geo.boundingBox.clone(), kind };
  propCache.set(key, result);
  return result;
}

// ── 칵테일 도구 ───────────────────────────────────────────
// 셰이커: 통(위가 열린 깡통) + 거름망 달린 뚜껑 + 꼭대기 작은 마개.
// 셋 다 원점이 '마개를 뺀 뚜껑 꼭대기'(따르는 입구)라서 같은 홀더에 그대로 겹쳐 놓으면 된다.
let shakerParts = null;
export function buildShakerParts() {
  if (shakerParts) return shakerParts;
  const metal = 0xc9ccce, inner = 0x8f9396, rim = 0xa9adb0;
  const TIN = Array.from({ length: 10 }, (_, i) => 2.8 + i * 0.05);
  const tinH = TIN.length + 1, top = tinH + 3;
  const fill = (grid, y, r, color, hole = 0) => {
    for (let x = -5; x < 5; x++) for (let z = -5; z < 5; z++) {
      const d = Math.hypot(x + 0.5, z + 0.5);
      if (d < r && d >= hole) grid.set(x, y, z, color);
    }
  };
  // 통: 바닥 한 층, 나머지는 벽만 (위에서 들여다보면 속이 보인다)
  const tg = new VoxelGrid(5, 0, tinH + 1);
  TIN.forEach((r, y) => fill(tg, y, r, y === 0 ? metal : metal, y === 0 ? 0 : r - 1));
  for (let x = -5; x < 5; x++) for (let z = -5; z < 5; z++) if (Math.hypot(x + 0.5, z + 0.5) < TIN[0] - 1) tg.set(x, 1, z, inner);
  fill(tg, TIN.length, 3.4, rim, 2.4);
  // 거름망 뚜껑: 통 테두리에 끼워지고 위로 갈수록 좁아진다. 꼭대기엔 구멍 무늬
  const lg = new VoxelGrid(5, tinH, top + 1);
  fill(lg, tinH, 3.3, metal);
  fill(lg, tinH + 1, 2.7, metal);
  fill(lg, tinH + 2, 2.1, metal);
  for (const [x, z] of [[-1, -1], [0, 0], [-1, 0], [0, -1]]) lg.set(x, tinH + 2, z, 0x55595c);
  // 마개
  const cg = new VoxelGrid(3, top, top + 2);
  fill(cg, top, 1.6, rim);
  fill(cg, top + 1, 1.0, rim);
  const geo = (grid) => {
    const g = meshGrid(grid, { jitter: 0.03 });
    g.translate(0, -top, 0);
    return g;
  };
  const tin = geo(tg), lid = geo(lg), cap = geo(cg);
  const box = new THREE.Box3(new THREE.Vector3(-3.4, -top, -3.4), new THREE.Vector3(3.4, 2, 3.4));
  // 통 입구 높이(원점 기준)
  shakerParts = { tin, lid, cap, box, angle: 2.0, metal: true, glassy: false, spout: false, kind: 'shaker', tinTop: tinH - top };
  return shakerParts;
}

// 믹싱 글라스에 얹는 줄렙 스트레이너 (구멍 뚫린 판 + 손잡이). 원점이 판 가운데
let strainerGeo = null;
export function buildStrainer() {
  if (strainerGeo) return strainerGeo;
  const b = new BoxBuilder();
  for (let x = -3; x < 3; x++) for (let z = -3; z < 3; z++) {
    const d = Math.hypot(x + 0.5, z + 0.5);
    if (d >= 3.3) continue;
    b.box(x, -0.4, z, 1, 0.5, 1, (x + z) % 2 ? 0xd5d8da : 0x9ea2a5, 0.01);
  }
  for (let i = 0; i < 6; i++) b.box(-3.3 - i, -0.3 + i * 0.25, -0.5, 1, 0.5, 1, 0xc4c7c9, 0.01);
  strainerGeo = b.geometry();
  return strainerGeo;
}

// 블렌더 뚜껑: 원점이 통 입구
let blenderLid = null;
export function buildBlenderLid() {
  if (blenderLid) return blenderLid;
  const b = new BoxBuilder();
  for (let x = -5; x < 5; x++) for (let z = -5; z < 5; z++) {
    const d = Math.hypot(x + 0.5, z + 0.5);
    if (d < 4.4) b.box(x, 0, z, 1, 1.4, 1, 0x2b2b2b, 0.03);
    if (d < 1.6) b.box(x, 1.4, z, 1, 1.2, 1, 0x3a3a3a, 0.03);
  }
  blenderLid = b.geometry();
  return blenderLid;
}

// 블렌더 받침
let blenderBase = null;
export function buildBlenderBase() {
  if (blenderBase) return blenderBase;
  const b = new BoxBuilder();
  b.cbox(0, 0, 0, 9, 5, 9, 0x2f2f33, 0.03);
  b.cbox(0, 5, 0, 7, 1, 7, 0x1f1f22, 0.02);
  b.cbox(0, 1.5, 4.6, 2, 2, 0.5, 0xd84a3a, 0);
  b.cbox(-2.6, 2, 4.6, 1.2, 1.2, 0.5, 0xd8d8d8, 0);
  b.cbox(2.6, 2, 4.6, 1.2, 1.2, 0.5, 0xd8d8d8, 0);
  blenderBase = b.geometry();
  return blenderBase;
}

// 바 스푼: 손잡이 위쪽이 +y, 숟가락 오목한 쪽이 원점
let barSpoon = null;
export function buildBarSpoon() {
  if (barSpoon) return barSpoon;
  const b = new BoxBuilder();
  for (let y = 0; y < 26; y++) b.cbox(0, 1 + y, 0, 0.45, 1, 0.45, y % 3 === 0 ? 0xb9bcbf : 0xd5d8da, 0.01);
  b.cbox(0, 0, 0, 1.6, 0.5, 2.2, 0xd5d8da, 0.01);
  b.cbox(0, 27, 0, 0.9, 0.9, 0.9, 0xb9bcbf, 0.01);
  barSpoon = b.geometry();
  return barSpoon;
}

// 빨대 (B-52처럼 불붙은 술용): 아래 끝이 원점
let strawGeo = null;
export function buildStraw() {
  if (strawGeo) return strawGeo;
  const b = new BoxBuilder();
  for (let y = 0; y < 22; y++) b.cbox(0, y, 0, 0.7, 1, 0.7, Math.floor(y / 2) % 2 ? 0xffffff : 0x2f7fd8, 0.01);
  strawGeo = b.geometry();
  return strawGeo;
}

// ── 장식 ──────────────────────────────────────────────────
const CITRUS = {
  lime: [0x4e8f2b, 0xb8d86a],
  lemon: [0xe8c62a, 0xfaeb8a],
  orange: [0xe8781e, 0xf6a94a],
};

function line(b, a, c, size, colorAt) {
  const len = a.distanceTo(c);
  const steps = Math.max(1, Math.ceil(len / (size * 0.7)));
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const x = a.x + (c.x - a.x) * t, y = a.y + (c.y - a.y) * t, z = a.z + (c.z - a.z) * t;
    b.box(x - size / 2, y - size / 2, z - size / 2, size, size, size, colorAt(t, i), 0.02);
  }
}

function wheel(b, kind, cx, cy, cz) {
  const [rind, flesh] = CITRUS[kind];
  for (let i = -3; i < 3; i++) {
    for (let j = -3; j < 3; j++) {
      const d = Math.hypot(i + 0.5, j + 0.5);
      if (d >= 2.7) continue;
      const a = Math.atan2(j + 0.5, i + 0.5);
      const seg = Math.abs(Math.sin(a * 3)) < 0.18 && d < 2;
      const c = d > 2 ? rind : seg ? 0xfff8dc : flesh;
      b.box(cx + i, cy + j, cz - 0.5, 1, 1, 1, c, 0.04);
    }
  }
}

// only: 이 장식만 만든다 (자리는 전체 목록 기준이라 따로 만들어도 위치가 같다)
export function buildGarnish(drink, shape, only = null) {
  const list = drink.garnish;
  if (!list?.length) return null;
  const b = new BoxBuilder();
  const { H, rimR, cavityStart } = shape;
  const V = THREE.Vector3;
  let wheelPlaced = false;
  for (const g of list) {
    if (only && !only(g)) {
      if (g === 'lime' || g === 'lemon' || g === 'orange') wheelPlaced = true;
      continue;
    }
    switch (g) {
      case 'lime':
      case 'lemon':
      case 'orange':
        wheel(b, g, -rimR + (wheelPlaced ? 0.6 : 0.3), H - 0.4, wheelPlaced ? 2.2 : 0);
        wheelPlaced = true;
        break;
      case 'olive': {
        const tip = new V(0.6, cavityStart + 1.5, 0.3);
        line(b, tip, new V(-rimR + 0.5, H + 5, 0.5), 0.5, () => 0xc79a5e);
        b.box(tip.x - 1, tip.y, tip.z - 1, 2, 2, 2, 0x7d8f2e, 0.06);
        b.box(tip.x - 0.4, tip.y + 2, tip.z - 0.4, 0.8, 0.4, 0.8, 0xc23a2a, 0);
        break;
      }
      case 'cherry': {
        const x = list.includes('umbrella') ? 1.0 : -rimR + 1.2;
        const z = list.includes('umbrella') ? 0.5 : -2.2;
        const y = list.includes('umbrella') ? H + 1.5 : H - 0.2;
        b.box(x - 1, y, z - 1, 2, 2, 2, 0xb3122a, 0.05);
        line(b, new V(x, y + 2, z), new V(x + 1.2, y + 4.5, z + 0.3), 0.35, () => 0x5b3a1e);
        break;
      }
      case 'umbrella': {
        const top = new V(3.2, H + 8, -1);
        line(b, new V(0.8, H - 4, 0), top, 0.45, () => 0xe9d2a6);
        const cols = [0xe86b8a, 0xfbe9d7];
        [[4.2, 0], [3.0, 1], [1.6, 2]].forEach(([r, dy]) => {
          for (let i = -5; i < 5; i++) for (let k = -5; k < 5; k++) {
            const d = Math.hypot(i + 0.5, k + 0.5);
            if (d >= r || (dy === 0 && d < 2.6)) continue;
            const seg = Math.floor(((Math.atan2(k + 0.5, i + 0.5) + Math.PI) / (Math.PI * 2)) * 8) % 2;
            b.box(top.x + i, top.y + dy - 1, top.z + k, 1, 1, 1, cols[seg], 0.03);
          }
        });
        break;
      }
      case 'straw': {
        const a = new V(1.0, cavityStart + 1, -1.2), c = new V(rimR + 1.6, H + 6, -2.2);
        line(b, a, c, 0.8, (t, i) => (Math.floor(i / 2) % 2 ? 0xffffff : 0xd8443a));
        break;
      }
      case 'salt': {
        const top = shape.def.layers[H - 1].rings[0];
        for (let x = -7; x < 7; x++) for (let z = -7; z < 7; z++) {
          const d = Math.hypot(x + 0.5, z + 0.5);
          if (d < top[1] || d >= top[0] + 0.2 || hash3(x, H, z) < 0.25) continue;
          b.box(x + 0.1, H, z + 0.1, 0.8, 0.5 + hash3(z, x, H) * 0.4, 0.8, 0xfbfaf4, 0.04);
        }
        break;
      }
      case 'beans': {
        // 에스프레소 마티니: 거품 위에 커피콩 세 알 (수면 기준 높이 0, 잔에서 수면을 따라 움직인다)
        const y = 0;
        [[-1.6, -0.4], [0.2, 0.9], [1.4, -0.9]].forEach(([x, z], i) => {
          b.box(x - 0.6, y, z - 0.8, 1.2, 0.6, 1.6, i % 2 ? 0x3a1d0c : 0x4a2610, 0.04);
          b.box(x - 0.1, y + 0.6, z - 0.7, 0.2, 0.05, 1.4, 0x24110a, 0);
        });
        break;
      }
      case 'cinnamon': {
        line(b, new V(1.2, cavityStart + 3, 1.2), new V(2.8, H + 5, 2.4), 1, (t, i) => (i % 3 === 0 ? 0x6a3519 : 0x7f4220));
        break;
      }
      case 'teabag': {
        const rim = -rimR + 0.5;
        line(b, new V(-1.2, cavityStart + 2.5, 0.2), new V(rim, H + 0.6, 0.2), 0.28, () => 0xf4efe2);
        line(b, new V(rim - 0.3, H + 0.6, 0.2), new V(rim - 1.2, H - 3, 0.2), 0.28, () => 0xf4efe2);
        b.box(rim - 2.5, H - 5.5, -0.1, 2.6, 2.6, 0.5, 0xe9b949, 0.02);
        b.box(-2.4, cavityStart + 0.2, -1, 2.4, 2.2, 1.6, 0x8a6236, 0.05);
        break;
      }
    }
  }
  return b.empty ? null : b.geometry();
}

// ── 얼음 ──────────────────────────────────────────────────
export function buildIceGeometry(shape, drink) {
  if (drink.iceShape === 'sphere') {
    const grid = new VoxelGrid(3, -3, 3);
    for (let x = -3; x < 3; x++) for (let y = -3; y < 3; y++) for (let z = -3; z < 3; z++) {
      if (Math.hypot(x + 0.5, y + 0.5, z + 0.5) < 2.45) grid.set(x, y, z, y > 0 ? 0xeaf7fc : 0xcfe7f0);
    }
    return { geo: meshGrid(grid, { jitter: 0.04 }), size: 4.9 };
  }
  const s = shape.cavityR[shape.cavityStart] >= 3 ? 2.6 : 2;
  const b = new BoxBuilder();
  b.box(-s / 2, -s / 2, -s / 2, s, s, s, 0xe4f5fb, 0);
  b.box(-s / 2 + 0.3, s / 2 - 0.2, -s / 2 + 0.3, s - 0.6, 0.3, s - 0.6, 0xffffff, 0);
  return { geo: b.geometry(), size: s };
}

// ── 둥둥 뜨는 것들 ────────────────────────────────────────
export const FLOATERS = {
  rice: { n: 14, scale: [1.0, 0.5, 0.55], colors: [0xfbf7ea, 0xf3ecd6], depth: [0, 0.5], speed: 0.12 },
  marshmallow: { n: 3, scale: [1.8, 1.5, 1.8], colors: [0xfffaf3, 0xf6d6dc], depth: [-0.6, -0.4], speed: 0.06 },
  peel: { n: 10, scale: [1.3, 0.35, 0.6], colors: [0xf0a020, 0xf5c242], depth: [0, 0.6], speed: 0.1 },
  mint: { n: 7, scale: [1.5, 0.35, 1.0], colors: [0x3f9a3a, 0x5cb84a, 0x2e7a30], depth: [0.3, 4], speed: 0.05 },
  fruit: { n: 9, scale: [1.5, 1.3, 1.5], colors: [0xf08a24, 0xe9d36a, 0xc8202f, 0x9ec24a], depth: [0.3, 6], speed: 0.05 },
  cloudy: { n: 28, scale: [0.7, 0.3, 0.7], colors: [0xd9cfb8, 0xfaf6ea, 0xe0d6bf], depth: [0, 0.2], speed: 0.18 },
};
