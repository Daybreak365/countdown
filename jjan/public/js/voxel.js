import * as THREE from 'three';

const tmpColor = new THREE.Color();

// 결정적인 해시 → [0, 1)
export function hash3(x, y, z) {
  let h = (x * 374761393 + y * 668265263 + z * 2147483647) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

// ── 복셀 격자 ─────────────────────────────────────────────
// x, z ∈ [-r, r), y ∈ [y0, y1)
export class VoxelGrid {
  constructor(r, y0, y1) {
    this.r = r;
    this.y0 = y0;
    this.sx = r * 2;
    this.sy = y1 - y0;
    this.data = new Int32Array(this.sx * this.sx * this.sy).fill(-1);
    this.count = 0;
  }
  index(x, y, z) {
    const i = x + this.r, k = z + this.r, j = y - this.y0;
    if (i < 0 || k < 0 || j < 0 || i >= this.sx || k >= this.sx || j >= this.sy) return -1;
    return (j * this.sx + k) * this.sx + i;
  }
  set(x, y, z, color) {
    const n = this.index(x, y, z);
    if (n < 0) return;
    if (this.data[n] < 0) this.count++;
    this.data[n] = color;
  }
  get(x, y, z) {
    const n = this.index(x, y, z);
    return n < 0 ? -1 : this.data[n];
  }
  clear() {
    this.data.fill(-1);
    this.count = 0;
  }
}

const FACES = [
  // 방향, 네 꼭짓점(반시계)
  { n: [1, 0, 0], v: [[1, 0, 0], [1, 1, 0], [1, 1, 1], [1, 0, 1]] },
  { n: [-1, 0, 0], v: [[0, 0, 1], [0, 1, 1], [0, 1, 0], [0, 0, 0]] },
  { n: [0, 1, 0], v: [[0, 1, 1], [1, 1, 1], [1, 1, 0], [0, 1, 0]] },
  { n: [0, -1, 0], v: [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]] },
  { n: [0, 0, 1], v: [[1, 0, 1], [1, 1, 1], [0, 1, 1], [0, 0, 1]] },
  { n: [0, 0, -1], v: [[0, 0, 0], [0, 1, 0], [1, 1, 0], [1, 0, 0]] },
];

// 면마다 삼각형 두 개(정점 6개)의 꼭짓점 순서
const FACE_VERTS = FACES.map(({ v: [a, b, c, d] }) => [a, b, c, a, c, d]);

// 바깥으로 드러난 면만 뽑아 지오메트리를 만든다.
// reuse: out 지오메트리의 버퍼를 그대로 다시 채운다 (술처럼 자주 다시 만드는 것: 새 배열·새 GPU 버퍼를 만들지 않는다)
export function meshGrid(grid, { jitter = 0.05, out, reuse = false } = {}) {
  const { r, y0, sx, sy, data } = grid;
  const plane = sx * sx;
  // 1) 칸마다 드러난 면을 비트로 표시하고 수를 센다
  const n = data.length;
  if (!grid.mask || grid.mask.length !== n) grid.mask = new Uint8Array(n);
  const mask = grid.mask;
  let faces = 0;
  for (let j = 0; j < sy; j++) {
    for (let k = 0; k < sx; k++) {
      for (let i = 0; i < sx; i++) {
        const idx = (j * sx + k) * sx + i;
        if (data[idx] < 0) { mask[idx] = 0; continue; }
        let m = 0;
        if (i + 1 >= sx || data[idx + 1] < 0) m |= 1;
        if (i - 1 < 0 || data[idx - 1] < 0) m |= 2;
        if (j + 1 >= sy || data[idx + plane] < 0) m |= 4;
        if (j - 1 < 0 || data[idx - plane] < 0) m |= 8;
        if (k + 1 >= sx || data[idx + sx] < 0) m |= 16;
        if (k - 1 < 0 || data[idx - sx] < 0) m |= 32;
        mask[idx] = m;
        faces += BIT_COUNT[m];
      }
    }
  }
  // 2) 배열 준비
  const verts = faces * 6;
  const geo = out || new THREE.BufferGeometry();
  let pos, nor, col;
  if (reuse && geo.userData.cap >= verts) {
    pos = geo.attributes.position.array;
    nor = geo.attributes.normal.array;
    col = geo.attributes.color.array;
  } else {
    const cap = reuse ? Math.max(64, Math.ceil(verts * 1.3)) : verts;
    // 버퍼가 모자라 키울 때만 예전 GPU 버퍼를 버린다
    if (reuse && geo.userData.cap) geo.dispose();
    pos = new Float32Array(cap * 3);
    nor = new Float32Array(cap * 3);
    col = new Float32Array(cap * 3);
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    geo.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
    geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
    geo.userData.cap = cap;
    if (reuse) {
      // 격자 크기로 넉넉하게 잡은 경계 구 (매번 다시 계산하지 않는다)
      geo.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, y0 + sy / 2, 0), Math.hypot(r, r, sy / 2));
    }
  }
  // 3) 채우기 (예전과 같은 순서)
  let o = 0;
  for (let j = 0; j < sy; j++) {
    for (let k = 0; k < sx; k++) {
      for (let i = 0; i < sx; i++) {
        const idx = (j * sx + k) * sx + i;
        const m = mask[idx];
        if (!m) continue;
        const x = i - r, y = j + y0, z = k - r;
        tmpColor.setHex(data[idx]);
        const s = 1 + (hash3(x, y, z) - 0.5) * 2 * jitter;
        const cr = tmpColor.r * s, cg = tmpColor.g * s, cb = tmpColor.b * s;
        for (let f = 0; f < 6; f++) {
          if (!(m & (1 << f))) continue;
          const fn = FACES[f].n, fv = FACE_VERTS[f];
          for (let q = 0; q < 6; q++) {
            const v = fv[q];
            pos[o] = x + v[0]; pos[o + 1] = y + v[1]; pos[o + 2] = z + v[2];
            nor[o] = fn[0]; nor[o + 1] = fn[1]; nor[o + 2] = fn[2];
            col[o] = cr; col[o + 1] = cg; col[o + 2] = cb;
            o += 3;
          }
        }
      }
    }
  }
  if (reuse) {
    geo.setDrawRange(0, verts);
    for (const name of ['position', 'normal', 'color']) {
      const attr = geo.attributes[name];
      attr.clearUpdateRanges();
      if (verts) attr.addUpdateRange(0, verts * 3);
      attr.needsUpdate = true;
    }
  } else {
    geo.computeBoundingSphere();
  }
  return geo;
}

const BIT_COUNT = new Uint8Array(64).map((_, m) => [0, 1, 2, 3, 4, 5].reduce((a, b) => a + ((m >> b) & 1), 0));

// ── 상자 쌓기 (가구, 소품) ────────────────────────────────
export class BoxBuilder {
  constructor() {
    this.pos = [];
    this.nor = [];
    this.col = [];
  }
  // (x, y, z)는 최소 모서리. skip: 그리지 않을 면 비트 (+x 1, -x 2, +y 4, -y 8, +z 16, -z 32) — 옆 상자에 가려 안 보이는 면
  box(x, y, z, w, h, d, color, jitter = 0.04, skip = 0) {
    tmpColor.setHex(color);
    const s = 1 + (hash3(Math.round(x * 7), Math.round(y * 7), Math.round(z * 7)) - 0.5) * 2 * jitter;
    const cr = tmpColor.r * s, cg = tmpColor.g * s, cb = tmpColor.b * s;
    for (let fi = 0; fi < 6; fi++) {
      if (skip & (1 << fi)) continue;
      const f = FACES[fi];
      const [a, b, c, e] = f.v;
      for (const v of [a, b, c, a, c, e]) {
        this.pos.push(x + v[0] * w, y + v[1] * h, z + v[2] * d);
        this.nor.push(f.n[0], f.n[1], f.n[2]);
        this.col.push(cr, cg, cb);
      }
    }
    return this;
  }
  // 중심 기준
  cbox(cx, y, cz, w, h, d, color, jitter) {
    return this.box(cx - w / 2, y, cz - d / 2, w, h, d, color, jitter);
  }
  get empty() {
    return this.pos.length === 0;
  }
  geometry() {
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    geo.setAttribute('normal', new THREE.Float32BufferAttribute(this.nor, 3));
    geo.setAttribute('color', new THREE.Float32BufferAttribute(this.col, 3));
    geo.computeBoundingSphere();
    return geo;
  }
}

// ── 회전체(잔, 병) → 복셀 ─────────────────────────────────
const MATERIAL_COLORS = {
  glass: 0xdcefee,
  thick: 0xd3ebe8,
  crystal: 0xeef8fb,
  ceramic: 0xf2ece0,
  metal: 0xd8ac4c,
  wood: 0x8a5a33,
};

export const TRANSPARENT_MATERIALS = new Set(['glass', 'thick', 'crystal']);

const shapeCache = new Map();

export function buildShape(def) {
  if (shapeCache.has(def)) return shapeCache.get(def);
  const H = def.layers.length;
  let maxR = 0;
  for (const layer of def.layers) for (const [o] of layer.rings) maxR = Math.max(maxR, o);
  const R = Math.ceil(maxR) + (def.handle ? def.handle.out + 2 : 1);
  const grid = new VoxelGrid(R, 0, H + 2);
  const base = def.color ?? MATERIAL_COLORS[def.material];
  const dist = def.square
    ? (x, z) => Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5))
    : (x, z) => Math.hypot(x + 0.5, z + 0.5);

  const cavityCols = []; // 층마다 액체 칸 목록
  const cavityR = [];
  let cavityStart = -1;

  def.layers.forEach((layer, y) => {
    const cols = [];
    for (let x = -R; x < R; x++) {
      for (let z = -R; z < R; z++) {
        const d = dist(x, z);
        const angle = Math.atan2(z + 0.5, x + 0.5);
        layer.rings.forEach(([outer, inner], ring) => {
          if (d < outer && d >= inner) {
            const edge = d >= outer - 1;
            const c = def.paint?.({ x, y, z, d, angle, edge, ring, h: hash3(x, y, z) }) ?? base;
            grid.set(x, y, z, c);
          }
        });
        if (layer.cavity > 0 && d < layer.cavity) cols.push(x, z);
      }
    }
    cavityCols.push(cols);
    cavityR.push(layer.cavity);
    if (layer.cavity > 0 && cavityStart < 0) cavityStart = y;
  });

  // 손잡이: 잔 오른쪽(+x)에 ㄷ자
  if (def.handle) {
    const { from, to, out } = def.handle;
    const wallX = (y) => {
      let m = 0;
      for (const [o] of def.layers[y].rings) m = Math.max(m, o);
      return Math.floor(Math.sqrt(Math.max(0, m * m - 0.25)) - 0.5);
    };
    const barX = Math.max(wallX(from), wallX(to)) + out;
    for (const z of [-1, 0]) {
      for (let y = from + 1; y < to; y++) grid.set(barX, y, z, def.paint?.({ x: barX, y, z, d: barX, angle: 0, edge: true, ring: 'handle', h: hash3(barX, y, z) }) ?? base);
      for (const y of [from, to]) {
        for (let x = wallX(y) + 1; x < barX; x++) grid.set(x, y, z, def.paint?.({ x, y, z, d: x, angle: 0, edge: true, ring: 'handle', h: hash3(x, y, z) }) ?? base);
      }
    }
  }

  // 부딪힘 판정용 치수
  const cs = Math.max(0, cavityStart);
  let contactR = 0;
  for (let y = Math.max(0, cs - 1); y < H; y++) for (const [o] of def.layers[y].rings) contactR = Math.max(contactR, o);
  let rimR = 0;
  for (const [o] of def.layers[H - 1].rings) rimR = Math.max(rimR, o);

  const shape = {
    def, grid, R, H,
    cavityCols, cavityR, cavityStart: cs,
    contactR, rimR,
    contactLow: Math.max(0, cs - 1),
    contactMid: (Math.max(0, cs - 1) + H) / 2,
    transparent: TRANSPARENT_MATERIALS.has(def.material),
  };
  shapeCache.set(def, shape);
  return shape;
}

// 잔 재질 (반투명은 뒷면/앞면을 나눠 그린다)
export function glassMaterials(shape, envMap) {
  const { def } = shape;
  if (shape.transparent) {
    const common = {
      vertexColors: true,
      transparent: true,
      opacity: def.material === 'thick' ? 0.24 : def.material === 'crystal' ? 0.13 : 0.17,
      roughness: 0.08,
      metalness: 0.05,
      envMap,
      // 반사가 세면 진한 술(흑맥주, 콜라)이 뿌옇게 보인다
      envMapIntensity: 0.32,
      depthWrite: false,
    };
    const front = new THREE.MeshStandardMaterial({ ...common, side: THREE.FrontSide });
    // 술이 비치는 부분(수면 아래)은 유리 빛을 줄여서 진한 술이 뿌옇게 바래지 않게 한다
    const level = { value: -1 }, dim = { value: 1 };
    front.userData.level = level;
    front.userData.dim = dim;
    front.onBeforeCompile = (sh) => {
      sh.uniforms.uLevel = level;
      sh.uniforms.uDim = dim;
      sh.vertexShader = sh.vertexShader
        .replace('#include <common>', '#include <common>\nvarying float vGlassY;')
        .replace('#include <begin_vertex>', '#include <begin_vertex>\nvGlassY = position.y;');
      sh.fragmentShader = sh.fragmentShader
        .replace('#include <common>', '#include <common>\nvarying float vGlassY;\nuniform float uLevel;\nuniform float uDim;')
        .replace('#include <dithering_fragment>', '#include <dithering_fragment>\nif (vGlassY < uLevel) gl_FragColor.rgb *= uDim;');
    };
    front.customProgramCacheKey = () => 'glass-dim';
    return {
      back: new THREE.MeshStandardMaterial({ ...common, side: THREE.BackSide }),
      front,
    };
  }
  const props = { ceramic: [0.45, 0], metal: [0.3, 0.75], wood: [0.85, 0] }[def.material] || [0.5, 0];
  return {
    front: new THREE.MeshStandardMaterial({ vertexColors: true, roughness: props[0], metalness: props[1], envMap, envMapIntensity: 0.6 }),
  };
}
