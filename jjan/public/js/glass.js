import * as THREE from 'three';
import { glassById, resolveDrink } from './data.js';
import { buildShape, glassMaterials, VoxelGrid, meshGrid, hash3 } from './voxel.js';
import { buildGarnish, buildIceGeometry, buildVessel, buildBarSpoon, buildBlenderBase, buildBlenderLid, buildShakerParts, buildStrainer, buildStraw, FLOATERS } from './props.js';

// 잔 복셀 1칸의 월드 크기
export const SCALE = 1.25;

const _v = new THREE.Vector3();
const _c = new THREE.Color();
const _c2 = new THREE.Color();
const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _s = new THREE.Vector3();
const _e = new THREE.Euler();
const _qf = new THREE.Quaternion();
const _rim = new THREE.Vector3();
const _upF = new THREE.Vector3();
const _pt = new THREE.Vector3();
const UNIT_BOX = new THREE.BoxGeometry(1, 1, 1);

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smooth = (t) => t * t * (3 - 2 * t);
const rand = (a, b) => a + Math.random() * (b - a);

// 병 종류마다 따르기 전에 하는 일과 걸리는 시간(초)
const PREP_BY_VESSEL = { shaker: 'shake', mixglass: 'stir', blender: 'blend', champagne: 'cork', can: 'can', makgeolli: 'flip', soju: 'swirl' };
// 마지막에 집어 먹는 장식, 수면에 뜨는 장식
const EDIBLE = new Set(['lime', 'lemon', 'orange', 'olive', 'cherry']);
const FLOATING = new Set(['beans']);
const PREP_TIME = { shake: 1.15, stir: 1.3, blend: 1.4, cork: 1.0, can: 0.6, flip: 1.35, swirl: 1.2 };

function gradientLUT(drink) {
  const lut = new Int32Array(17);
  const g = drink.gradient;
  for (let n = 0; n <= 16; n++) {
    const t = n / 16;
    if (!g) { lut[n] = drink.color; continue; }
    let i = 1;
    while (i < g.length - 1 && t > g[i][0]) i++;
    const a = g[i - 1], b = g[i];
    const u = clamp((t - a[0]) / (b[0] - a[0] || 1), 0, 1);
    lut[n] = _c.set(a[1]).lerp(_c2.set(b[1]), u).getHex();
  }
  return lut;
}

const glassGeoCache = new Map();
function glassGeometry(shape) {
  if (!glassGeoCache.has(shape)) glassGeoCache.set(shape, meshGrid(shape.grid, { jitter: shape.transparent ? 0.025 : 0.05 }));
  return glassGeoCache.get(shape);
}

export class GlassEntity {
  // env: { envMap, fx, audio, tableY }
  constructor(env, { drinkId, glassId, variant = null, yaw = 0, local = false }) {
    this.env = env;
    this.local = local;
    this.yaw = yaw;
    this.root = new THREE.Group();
    this.root.rotation.y = yaw;
    this.pivot = new THREE.Group();
    this.body = new THREE.Group();
    this.body.scale.setScalar(SCALE);
    this.root.add(this.pivot);
    this.pivot.add(this.body);

    this.vel = new THREE.Vector3();
    this.acc = new THREE.Vector3();
    this.prevPos = null;
    this.prevVel = new THREE.Vector3();
    this.lean = { x: 0, z: 0, vx: 0, vz: 0 };
    this.netLean = { x: 0, z: 0 };
    this.extraTilt = { x: 0, z: 0 };
    this.offset = new THREE.Vector3(); // 마시기 동작 오프셋(잔 기준)
    this.slosh = { x: 0, z: 0, vx: 0, vz: 0 };
    this.fill = 1;
    this.netFill = null;
    this.foamBoost = 0;
    this.swirl = 0;
    this.bubbleBurst = 0;
    this.steamAcc = 0;
    this.spillCooldown = 0;
    this.rattleCd = 0;
    this.flameOn = false;
    this.flameFill = 0;
    this.flameAcc = 0;
    this.panHint = 0;
    this.seq = [];
    this.renderBase = 0;
    // 바텐더가 따라 줄 때 손이 갈 곳 (월드 좌표, 없으면 null)
    this.hands = { right: null, left: null };
    this.handR = new THREE.Vector3();
    this.handL = new THREE.Vector3();
    // 바텐더가 맥주를 따를 때 잔을 기울여 받는 각도
    this.serveTilt = { x: 0, z: 0 };
    this.set(drinkId, glassId, variant);
  }

  // 회오리·스월링처럼 가벼운 연출 중에는 마시거나 채울 수 있다
  get busy() {
    return this.waitingServe || this.seq.some((s) => !s.soft);
  }

  // ── 구성 ───────────────────────────────────────────────
  set(drinkId, glassId, variant = null) {
    this.cancelSequences();
    this.disposeParts();
    this.flameOn = false;
    this.garnishEaten = false;
    // 고른 상품(카스, 펩시 제로 라임 …)이 있으면 그 병·캔으로 따른다
    const drink = (this.drink = resolveDrink(drinkId, variant));
    const def = (this.glassDef = glassById[glassId] || glassById[drink.glass]);
    const shape = (this.shape = buildShape(def));
    const env = this.env.envMap;
    this.baseLUT = gradientLUT(drink);
    this.tint = null;
    this.sinkK = null;
    this.lut = this.baseLUT;
    this.lutKey = '';
    this.parts = [];

    const geo = glassGeometry(shape);
    const mats = glassMaterials(shape, env);
    this.glassMats = mats;
    if (mats.back) this.glassBack = this.add(new THREE.Mesh(geo, mats.back));
    this.glassFront = this.add(new THREE.Mesh(geo, mats.front));
    this.glassFront.castShadow = !shape.transparent;
    this.glassFront.receiveShadow = !shape.transparent;
    if (mats.front.userData.dim) {
      // 술이 어두울수록 앞 유리를 더 어둡게
      _c.set(drink.color);
      const lum = 0.2126 * _c.r + 0.7152 * _c.g + 0.0722 * _c.b; // 선형 휘도
      mats.front.userData.dim.value = clamp(0.3 + Math.sqrt(lum) * 0.9, 0.3, 1) ** (drink.op > 0.6 ? 1 : 0.5);
    }

    // 액체
    const transparent = drink.op < 0.98;
    this.liquidMat = new THREE.MeshStandardMaterial({
      vertexColors: true, transparent, opacity: drink.op, depthWrite: !transparent,
      roughness: 0.16, metalness: 0, envMap: env, envMapIntensity: drink.op > 0.85 ? 0.18 : 0.5,
    });
    this.liquid = this.add(new THREE.Mesh(new THREE.BufferGeometry(), this.liquidMat));
    this.liquid.castShadow = true;
    this.liquidGrid = new VoxelGrid(shape.R, 0, shape.H + 2);
    this.liquidKey = '';
    this.cavityTop = shape.cavityStart + (shape.H - shape.cavityStart) * drink.fill;
    // 가득 따랐을 때 담기는 양 (칸 하나 ≈ 1ml): 취기 계산에 쓴다
    let cells = 0;
    for (let y = shape.cavityStart; y < Math.floor(this.cavityTop); y++) cells += (shape.cavityCols[y]?.length ?? 0) / 2;
    this.capacity = cells;

    // 기포
    this.bubbleN = 0;
    if (drink.carb || drink.bomb) {
      const max = 110;
      const tint = drink.bubbleColor ?? _c.set(0xffffff).lerp(_c2.set(drink.color), 0.2).getHex();
      this.bubbleMat = new THREE.MeshBasicMaterial({ color: tint, transparent: true, opacity: 0.85, depthWrite: false });
      this.bubbles = this.add(new THREE.InstancedMesh(UNIT_BOX, this.bubbleMat, max));
      this.bubbles.count = 0;
      this.bubbles.frustumCulled = false;
      this.bub = { max, x: new Float32Array(max), y: new Float32Array(max), z: new Float32Array(max), v: new Float32Array(max), s: new Float32Array(max), ph: new Float32Array(max) };
      // 샴페인처럼 줄지어 오르는 기포의 시작점
      const bottom = shape.cavityCols[shape.cavityStart];
      this.nucleation = [];
      for (let i = 0; i < 3 && bottom.length; i++) {
        const k = Math.floor(hash3(i, 7, def.id.length) * (bottom.length / 2)) * 2;
        this.nucleation.push([bottom[k] + 0.5, bottom[k + 1] + 0.5]);
      }
    }

    // 떠다니는 것들
    const fl = drink.cloudy ? FLOATERS.cloudy : drink.floaters ? FLOATERS[drink.floaters] : null;
    if (fl) {
      this.floatSpec = fl;
      this.floatMat = new THREE.MeshStandardMaterial({ roughness: 0.6 });
      this.floaters = this.add(new THREE.InstancedMesh(UNIT_BOX, this.floatMat, fl.n));
      this.floaters.frustumCulled = false;
      this.fdata = Array.from({ length: fl.n }, (_, i) => ({
        a: Math.random() * Math.PI * 2,
        r: Math.sqrt(Math.random()) * 0.85,
        d: rand(fl.depth[0], fl.depth[1]),
        rot: Math.random() * Math.PI,
        sp: fl.speed * rand(0.6, 1.4) * (Math.random() < 0.5 ? -1 : 1),
      }));
      this.fdata.forEach((_, i) => this.floaters.setColorAt(i, _c.set(fl.colors[i % fl.colors.length])));
    }

    // 얼음
    this.ice = [];
    if (drink.ice) {
      const { geo: iceGeo, size } = buildIceGeometry(shape, drink);
      this.iceGeo = iceGeo;
      this.iceMat = new THREE.MeshStandardMaterial({ vertexColors: true, transparent: true, opacity: 0.5, roughness: 0.05, envMap: env, envMapIntensity: 1.2, depthWrite: false });
      const cr = shape.cavityR[shape.cavityStart + 1] || shape.cavityR[shape.cavityStart];
      if (cr >= size * 0.5) {
        const n = drink.ice;
        const ring = n === 1 ? 0 : Math.max(0, cr - size * 0.72);
        for (let i = 0; i < n; i++) {
          const mesh = this.add(new THREE.Mesh(iceGeo, this.iceMat));
          const a = (i / n) * Math.PI * 2 + 0.6;
          this.ice.push({ mesh, x: Math.cos(a) * ring, z: Math.sin(a) * ring, ph: i * 1.7, size, stack: i });
        }
      }
    }

    // 장식. 소금 테두리는 잔을 낼 때부터 묻어 있고, 나머지는 술을 다 만든 뒤에 얹는다.
    // 먹는 장식(과일, 올리브, 체리, 커피콩)은 마지막 모금에 먹고, 커피콩처럼 뜨는 건 수면을 따라 내려간다.
    const list = drink.garnish || [];
    const has = (fn) => list.some(fn);
    const gbuild = (fn) => (has(fn) ? buildGarnish(drink, shape, fn) : null);
    const eGeo = gbuild((g) => EDIBLE.has(g));
    const fGeo = gbuild((g) => FLOATING.has(g));
    const pGeo = gbuild((g) => !EDIBLE.has(g) && !FLOATING.has(g) && g !== 'salt');
    const rGeo = gbuild((g) => g === 'salt');
    this.garnishGeos = [eGeo, fGeo, pGeo, rGeo].filter(Boolean);
    const gMat = this.garnishGeos.length ? new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.55 }) : null;
    this.garnishMat = gMat;
    const mk = (g, shadow = true) => {
      if (!g) return null;
      const m = this.add(new THREE.Mesh(g, gMat));
      m.castShadow = shadow;
      return m;
    };
    this.garnish = mk(eGeo);
    this.garnishFloat = mk(fGeo);
    this.garnishProp = mk(pGeo);
    this.rim = mk(rGeo, false);

    // 폭탄주용 샷잔
    this.shot = null;
    if (drink.bomb?.type === 'drop') {
      const sShape = buildShape(glassById.dropshot);
      const fit = (shape.cavityR[shape.cavityStart] - 0.2) / sShape.rimR;
      if (fit > 0.55) {
        const g = new THREE.Group();
        const sMats = glassMaterials(sShape, env);
        const sGeo = glassGeometry(sShape);
        const parts = [];
        if (sMats.back) parts.push(new THREE.Mesh(sGeo, sMats.back));
        parts.push(new THREE.Mesh(sGeo, sMats.front));
        const lg = new VoxelGrid(sShape.R, 0, sShape.H);
        for (let y = sShape.cavityStart; y < sShape.H - 1; y++) {
          const cols = sShape.cavityCols[y];
          for (let i = 0; i < cols.length; i += 2) lg.set(cols[i], y, cols[i + 1], drink.bomb.shot);
        }
        this.shotLiquidGeo = meshGrid(lg, { jitter: 0.03 });
        parts.push(new THREE.Mesh(this.shotLiquidGeo, new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.2 })));
        parts.forEach((p) => g.add(p));
        g.scale.setScalar(Math.min(1, fit));
        this.shotMats = [...Object.values(sMats), parts[parts.length - 1].material];
        this.shotParts = parts;
        this.shot = { group: this.add(g), restY: shape.cavityStart, y: shape.cavityStart, vy: 0, h: sShape.H * Math.min(1, fit) };
        g.position.set(0, shape.cavityStart, 0);
      }
    }

    // 부딪힘 판정 치수(월드)
    this.radius = shape.contactR * SCALE;
    this.height = shape.H * SCALE;
    this.contactLow = shape.contactLow * SCALE;
    this.contactMid = shape.contactMid * SCALE;
    this.setRenderBase(this.renderBase);
    this.rebuildLiquid(0, true);
  }

  add(obj) {
    this.body.add(obj);
    this.parts.push(obj);
    return obj;
  }

  disposeParts() {
    if (!this.parts) return;
    for (const p of this.parts) this.body.remove(p);
    this.liquid?.geometry.dispose();
    this.garnishGeos?.forEach((g) => g.dispose());
    this.iceGeo?.dispose();
    this.shotLiquidGeo?.dispose();
    for (const m of [this.liquidMat, this.bubbleMat, this.floatMat, this.iceMat, this.garnishMat,
      this.glassMats?.back, this.glassMats?.front, ...(this.shotMats || [])]) m?.dispose();
    this.bubbles = this.floaters = this.garnish = this.garnishFloat = this.garnishProp = this.rim = this.shot = this.glassBack = null;
    this.garnishMat = this.garnishGeos = null;
    this.iceGeo = this.shotLiquidGeo = this.bubbleMat = this.floatMat = this.iceMat = null;
    this.shotMats = null;
    this.floatSpec = null;
    this.parts = [];
  }

  dispose() {
    this.cancelSequences();
    this.disposeParts();
    this.root.removeFromParent();
  }

  // 반투명끼리 겹칠 때 먼 잔부터 그리도록
  setRenderBase(base) {
    this.renderBase = base;
    if (this.glassBack) this.glassBack.renderOrder = base + 1;
    if (this.shot) this.shotParts.forEach((p) => (p.renderOrder = base + 1.5));
    this.liquid.renderOrder = base + 2;
    // 얼음은 액체보다 먼저 그려서 잠긴 부분이 술 색으로 물들게 한다
    this.ice.forEach((i) => (i.mesh.renderOrder = base + 1.8));
    if (this.bubbles) this.bubbles.renderOrder = base + 4;
    if (this.glassBack) this.glassFront.renderOrder = base + 5;
    for (const s of this.seq) if (s.stream) s.stream.renderOrder = base + 2;
  }

  // ── 상태 ───────────────────────────────────────────────
  get level() {
    const cs = this.shape.cavityStart;
    return cs + (this.cavityTop - cs) * this.fill;
  }

  surfaceAt(x, z) {
    return this.level + this.slosh.x * x + this.slosh.z * z + this.swirlTerm(x, z);
  }

  swirlTerm(x, z) {
    if (this.swirl < 0.01) return 0;
    const lv = clamp(Math.floor(this.level), this.shape.cavityStart, this.shape.H - 1);
    const cr = this.shape.cavityR[lv] || 1;
    return this.swirl * 2.6 * (Math.hypot(x, z) / cr - 0.55);
  }

  topLocal(out) {
    return out.set(0, this.shape.H + 2, 0);
  }

  rebuildLiquid(t, force = false) {
    const { shape, drink, slosh } = this;
    const lvU = this.glassMats?.front.userData.level;
    if (lvU) lvU.value = this.fill > 0.02 ? this.level - 0.3 : -1;
    const cs = shape.cavityStart, H = shape.H;
    const level = this.level;
    const foamH = drink.foam && this.fill > 0.03 ? drink.foam.h * (0.55 + 0.45 * this.fill) + this.foamBoost : 0;
    this.foamH = foamH;
    const phase = foamH > 0 ? Math.floor(t * 2.5) : 0;
    const key = `${Math.round(level * 3)}|${Math.round(slosh.x * 12)}|${Math.round(slosh.z * 12)}|${Math.round(foamH * 3)}|${phase}|${Math.round(this.swirl * 10)}|${this.lutKey}`;
    if (!force && key === this.liquidKey) return;
    this.liquidKey = key;
    const grid = this.liquidGrid;
    grid.clear();
    // 층으로 쌓은 술(B-52, 아이리시 커피)은 잔 높이 기준으로 색을 정해서 따르는 대로 층이 생긴다
    const span = Math.max(0.001, drink.layered ? this.cavityTop - cs : level - cs);
    if (this.fill > 0.004) {
      const foamColor = drink.foam?.color ?? 0xffffff;
      const yMax = foamH > 0 ? H + 1 : H;
      for (let y = cs; y < yMax; y++) {
        const cols = shape.cavityCols[Math.min(y, H - 1)];
        for (let i = 0; i < cols.length; i += 2) {
          const x = cols[i], z = cols[i + 1];
          const cx = x + 0.5, cz = z + 0.5;
          const surf = Math.min(H, level + slosh.x * cx + slosh.z * cz + this.swirlTerm(cx, cz));
          const yc = y + 0.5;
          if (yc < surf && y < H) {
            grid.set(x, y, z, this.lut[Math.round(clamp((yc - cs) / span, 0, 1) * 16)]);
          } else if (foamH > 0) {
            const bump = (hash3(x, z, phase) - 0.5) * 1.1;
            if (yc < Math.max(surf, cs + 0.5) + foamH + bump) grid.set(x, y, z, foamColor);
          }
        }
      }
    }
    // 같은 지오메트리 버퍼를 다시 채운다 (새 배열·새 GPU 버퍼 없이)
    meshGrid(grid, { jitter: drink.cloudy ? 0.07 : 0.03, out: this.liquid.geometry, reuse: true });
  }

  // ── 반응 ───────────────────────────────────────────────
  toLocal(wx, wz) {
    const c = Math.cos(this.yaw), s = Math.sin(this.yaw);
    return [wx * c - wz * s, wx * s + wz * c];
  }

  // n: 이 잔이 밀려나는 방향(월드)
  hit(strength, nx, nz) {
    const [lx, lz] = this.toLocal(nx, nz);
    this.lean.vx += lz * 7 * strength;
    this.lean.vz += -lx * 7 * strength;
    this.slosh.vx -= lx * 5 * strength;
    this.slosh.vz -= lz * 5 * strength;
    if (this.drink.carb) this.bubbleBurst += this.drink.carb * 32 * strength;
    if (this.drink.foam) this.foamBoost = Math.min(3, this.foamBoost + strength * 1.6);
    if (strength > 0.45 && this.fill > 0.5) this.splash(Math.round(3 + strength * 8), -nx, -nz, strength);
  }

  // 잔 테두리에서 방울이 튄다
  splash(n, dx, dz, strength = 0.6) {
    const fx = this.env.fx;
    if (!fx) return;
    this.root.updateMatrixWorld();
    const rim = this.shape.rimR - 0.5;
    const [lx, lz] = this.toLocal(dx, dz);
    for (let i = 0; i < n; i++) {
      const a = Math.atan2(lz, lx) + rand(-0.9, 0.9);
      _v.set(Math.cos(a) * rim * rand(0.3, 1), this.level + 0.5, Math.sin(a) * rim * rand(0.3, 1));
      this.body.localToWorld(_v);
      fx.drops.spawn({
        x: _v.x, y: _v.y, z: _v.z,
        vx: dx * rand(10, 30) * strength + rand(-8, 8), vy: rand(20, 55) * strength, vz: dz * rand(10, 30) * strength + rand(-8, 8),
        gravity: 420, life: 1.8, size: rand(0.7, 1.2), color: this.drink.foam && Math.random() < 0.4 ? this.drink.foam.color : this.drink.color,
        floor: (this.env.tableY ?? 0) + 0.3, env: 'hold',
      });
    }
  }

  // ── 연출 ───────────────────────────────────────────────
  cancelSequences() {
    for (const s of this.seq) s.cleanup?.();
    this.seq = [];
    this.offset.set(0, 0, 0);
    this.extraTilt.x = this.extraTilt.z = 0;
    this.swirl = 0;
  }

  // 따르기 → 폭탄주·스월링·불붙이기 같은 마무리
  // srv: 따라 주는 사람(바텐더), skipPrep: 이미 만들어 둔 걸 따르기만, handIn/keepLast: 그릇 넘겨주기, onServed: 다 따른 뒤
  serve({ fromEmpty = true, srv = null, skipPrep = false, handIn = null, keepLast = false, onServed = null } = {}) {
    this.cancelSequences();
    if (fromEmpty) this.fill = 0;
    // 장식은 다 만든 뒤에 (먹어 버린 장식도 새 잔에 다시)
    if (fromEmpty || this.garnishEaten) this.hideGarnish();
    if (this.flameOn) this.extinguish(true);
    this.removeStraw();
    if (this.shot) {
      this.shot.group.visible = this.drink.id === 'gojingamrae';
      this.shot.y = this.shot.restY;
    }
    if (fromEmpty) this.dropIce();
    this.sinkK = null;
    this.setTint(null);
    this.waitingServe = false;
    this.startPourSeq(fromEmpty, { srv, skipPrep, handIn, keepLast, onServed });
  }

  // 따르는 순서: data.js의 pour가 있으면 그대로, 없으면 병 하나
  pourSteps() {
    const d = this.drink;
    if (d.pour) return d.pour;
    return [{ v: d.bottle, color: d.bottleColor ?? d.canColor, prep: d.prep }];
  }

  startPourSeq(fresh, { srv = null, skipPrep = false, handIn = null, keepLast = false, onServed = null } = {}) {
    const steps = this.pourSteps();
    const f0 = this.fill;
    const run = (i, from) => {
      const st = steps[i];
      const last = i === steps.length - 1;
      const to = last ? 1 : f0 + (1 - f0) * (st.to ?? (i + 1) / steps.length);
      this.startPour(st, {
        fresh, first: i === 0, last, from, to, srv,
        skipPrep: skipPrep && i === 0,
        handIn: i === 0 ? handIn : null,
        keepLast: keepLast && last,
        onDone: (out) => {
          if (!last) { run(i + 1, to); return; }
          this.afterPour(st);
          onServed?.(out);
        },
      });
    };
    run(0, f0);
  }

  // 따르기 전에 그릇마다 하는 일
  prepKind(st, fresh) {
    let kind = st.prep !== undefined ? st.prep : PREP_BY_VESSEL[st.v];
    // 소주 회오리는 초록 소주병일 때만
    if (st.prep === undefined && st.v === 'soju' && st.color != null) kind = null;
    if (!kind || kind === 'none') return null;
    // 이미 딴 병은 다시 따지 않는다
    if (!fresh && (kind === 'cork' || kind === 'can' || kind === 'swirl')) return null;
    return kind;
  }

  // 잔에 보이는 술 색을 한 가지로 덮는다 (여러 번 나눠 따를 때 중간 색). null이면 원래 색/그라데이션
  setTint(hex) {
    this.tint = hex;
    this.refreshLUT();
  }

  refreshLUT() {
    const base = this.baseLUT;
    if (this.tint == null && this.sinkK == null) {
      this.lut = base;
      this.lutKey = '';
      return;
    }
    const lut = new Int32Array(17);
    for (let n = 0; n <= 16; n++) {
      if (this.sinkK != null) {
        // 그레나딘처럼 무거운 시럽이 바닥부터 깔리며 위로 번진다
        const h = n / 16;
        const w = smooth(clamp((this.sinkK * 1.5 - h) / 0.5, 0, 1));
        lut[n] = _c.set(this.sinkBase).lerp(_c2.set(base[n]), w).getHex();
      } else {
        lut[n] = this.tint;
      }
    }
    this.lut = lut;
    this.lutKey = `${this.tint ?? ''}|${this.sinkK == null ? '' : Math.round(this.sinkK * 40)}`;
  }

  // 병 입구(홀더 원점)의 월드 좌표
  mouthWorld(holder, out = _v) {
    holder.updateWorldMatrix(true, false);
    return holder.localToWorld(out.set(0, 0, 0));
  }

  // 그릇 하나로 잔에 따르기. 칵테일은 그 전에 셰이커·믹싱 글라스·블렌더에 재료를 넣고 섞는다.
  // srv(바텐더)가 있으면 그릇은 바 아래에서 손에 들려 올라오고, 잔 뒤쪽(바텐더 쪽)에서 따른다.
  // handIn: 앞 잔에서 넘겨받은 그릇 위치(월드), keepLast: 다 따른 뒤 그릇을 내려놓지 않고 다음 잔으로 넘긴다.
  startPour(st, { fresh, first, last, from, to, onDone, srv = null, skipPrep = false, handIn = null, keepLast = false }) {
    const drink = this.drink, shape = this.shape;
    const streamColor = st.stream ?? drink.color;
    const isShaker = st.v === 'shaker';
    const bottle = isShaker ? buildShakerParts() : buildVessel({ v: st.v, color: st.color, label: st.label, cap: st.cap, noBand: st.noBand, liquid: st.liquid ?? streamColor });
    // 믹싱 글라스, 피처처럼 유리로 된 그릇은 속이 비쳐 보이게
    const mat = new THREE.MeshStandardMaterial({
      vertexColors: true, roughness: bottle.metal ? 0.3 : bottle.glassy ? 0.08 : 0.22, metalness: bottle.metal ? 0.7 : 0.05,
      envMap: this.env.envMap, envMapIntensity: bottle.glassy ? 0.4 : 0.8,
      transparent: bottle.glassy, opacity: bottle.glassy ? 0.62 : 1, depthWrite: !bottle.glassy,
    });
    // frame: 따르는 방향을 정하는 틀. 바텐더는 잔 뒤에서(잔 기준 -z) 따른다
    const frame = new THREE.Group();
    frame.rotation.y = srv?.yawFor?.(this) ?? 0;
    const frameYaw = frame.quaternion.clone();
    this.body.add(frame);
    const holder = new THREE.Group();
    const mesh = new THREE.Mesh(isShaker ? bottle.tin : bottle.geo, mat);
    if (bottle.glassy) mesh.renderOrder = this.renderBase + 6;
    mesh.castShadow = true;
    holder.add(mesh);
    frame.add(holder);
    const extras = []; // 정리할 것들

    const box = bottle.box;
    const kind = skipPrep ? null : this.prepKind(st, fresh);
    const mixing = kind === 'shake' || kind === 'stir' || kind === 'blend';
    const build = mixing ? st.build ?? [] : [];
    const baseH = kind === 'blend' || (st.v === 'blender' && skipPrep) ? 6 : 0; // 블렌더 받침 높이
    // 처음엔 잔 옆 탁자 위에 서 있다가, 들어 올려 따른다.
    // 바텐더가 손님 잔 뒤에서 따를 땐, 재료를 섞는 동안 그릇이 잔 바로 뒤에 겹쳐 보이지 않게 옆(바텐더 오른손 쪽)에 둔다
    const behind = srv && frame.rotation.y !== 0;
    const rest = behind
      ? new THREE.Vector3(-box.max.x - 3.5, -box.min.y + baseH, shape.rimR + box.max.x + 3)
      : new THREE.Vector3(-shape.rimR - 1.6 - box.max.x, -box.min.y + baseH, -1.5);
    let mouthX = bottle.spout ? -shape.rimR * 0.2 : -shape.rimR * 0.35;
    if (st.layer) mouthX = -0.6; // 바 스푼 위로 살살
    const pourAt = new THREE.Vector3(mouthX, shape.H + (bottle.spout ? 5 : st.layer ? 4 : 6), 0);
    const aim = new THREE.Vector3(); // 이번 프레임에 그릇 입구가 갈 곳 (잔이 기울면 따라 옮겨 간다)
    const center = box.getCenter(new THREE.Vector3());
    holder.position.copy(rest);
    // 재료를 받는 입구 (셰이커는 뚜껑을 덮기 전 통 입구)
    const intake = new THREE.Vector3(0, isShaker ? bottle.tinTop : 0, 0);

    // 바텐더: 바 안쪽 아래(술 놓는 곳)에서 꺼내 온다
    let start = null, under = null;
    if (srv) {
      this.root.updateMatrixWorld(true);
      const rw = frame.localToWorld(rest.clone());
      // 바텐더 오른쪽 옆, 바 상판 높이 아래 (몸에 겹치지 않게 옆으로 비켜서)
      under = frame.worldToLocal(new THREE.Vector3(rw.x - 20, (this.env.tableY ?? 0) - 10, srv.wellZ ?? -171));
      start = handIn ? frame.worldToLocal(handIn.clone()) : under.clone();
    }
    // 바 위의 다른 잔(바텐더 잔, 손님 잔)을 스치는 길이면 그 잔 위로 넘길 높이 (그릇 입구 기준, 틀 좌표)
    const others = srv?.obstacles?.(this) ?? [];
    const overOthers = (a, b) => {
      let need = -Infinity;
      for (const o of others) {
        const c = frame.worldToLocal(_pt.copy(o.root.position));
        const k = o.root.scale.x / this.root.scale.x;
        const r = (o.shape.contactR || o.shape.rimR) * k + 1;
        for (let i = 0; i <= 16; i++) {
          const u = i / 16, x = a.x + (b.x - a.x) * u, z = a.z + (b.z - a.z) * u;
          const dx = Math.max(x + box.min.x - c.x, 0, c.x - (x + box.max.x));
          const dz = Math.max(z + box.min.z - c.z, 0, c.z - (z + box.max.z));
          if (Math.hypot(dx, dz) < r) { need = Math.max(need, c.y + o.shape.H * k + 1.5 - box.min.y); break; }
        }
      }
      return need;
    };
    const overIn = start ? overOthers(start, rest) : -Infinity;
    const overOut = under ? overOthers(rest, under) : -Infinity;
    // 앞 잔에서 넘겨받아 옮겨 올 때 이 잔 위를 지나가면, 밑동이 잔 입구보다 높게 들어서 넘는다
    let handBump = 6;
    if (start && handIn) {
      const dx = rest.x - start.x, dz = rest.z - start.z;
      const u = clamp(-(start.x * dx + start.z * dz) / Math.max(1e-6, dx * dx + dz * dz), 0, 1);
      const gap = Math.hypot(start.x + dx * u, start.z + dz * u);
      const vr = Math.max(box.max.x, -box.min.x, box.max.z, -box.min.z);
      if (gap < shape.rimR + vr + 1.5) handBump = Math.max(6, shape.H + 1.5 - box.min.y - Math.max(start.y, rest.y));
      handBump = Math.max(handBump, overIn - Math.max(start.y, rest.y));
    }

    // ── 시간표 ──
    const A = srv ? (handIn ? 0.6 : 0.8) : 0.3; // 나타나는(꺼내 오는) 시간
    const ACT = kind ? PREP_TIME[kind] : 0;
    const phases = [];
    const ph = (name, dur, extra) => phases.push({ name, dur, ...extra });
    if (mixing) {
      if (kind === 'stir') ph('ice', 0.75);
      build.forEach((ing, i) => ph('ing', ing.dash ? 0.8 : 1.1, { i }));
      if (kind !== 'stir') ph('ice', 0.75);
      if (kind !== 'stir') ph('cap', 0.45);
      ph('act', ACT);
      ph('fin', kind === 'stir' ? 0.4 : 0.45);
    } else if (kind) {
      ph('act', ACT);
    }
    let acc = 0;
    for (const p of phases) { p.t0 = acc; acc += p.dur; }
    const P = acc;
    const T0 = A + P; // 들어 올리기 시작
    const span = Math.max(0.05, to - from);
    const pourDur = st.dur ?? Math.max(0.55, 1.45 * span) * (st.layer ? 1.7 : 1);
    const pour0 = T0 + 0.42, pour1 = pour0 + pourDur;
    const end0 = pour1 + 0.6;
    const lower = srv && !keepLast ? 0.65 : 0;
    const end = end0 + lower;
    const carb = st.carb ?? (last ? drink.carb ?? 0 : 0);
    const hot = st.hot ?? (drink.hot && (bottle.spout || st.v === 'carafe'));
    // 층을 쌓는 술은 섞지 않고, 나머지는 따르는 동안 중간 색으로 물들인다
    const useTint = !drink.layered;
    const tintFrom = this.tint ?? (from > 0.02 ? drink.color : streamColor);
    const tintTo = useTint ? st.tint ?? (last ? null : streamColor) : null;

    const mkMat = (opts) => {
      const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.3, envMap: this.env.envMap, envMapIntensity: 0.7, ...opts });
      extras.push(m);
      return m;
    };

    // 셰이커 뚜껑과 마개: 재료를 다 넣은 뒤에 덮는다
    let lid = null, cap = null;
    if (isShaker) {
      lid = new THREE.Mesh(bottle.lid, mat);
      cap = new THREE.Mesh(bottle.cap, mat);
      lid.castShadow = cap.castShadow = true;
      holder.add(lid, cap);
      const open = mixing && build.length > 0;
      lid.visible = !open;
      cap.visible = !open && !skipPrep;
    }
    // 샴페인 코르크
    let cork = null;
    if (kind === 'cork') {
      cork = new THREE.Mesh(UNIT_BOX, mkMat({ vertexColors: false, color: 0xc9a26b, roughness: 0.8 }));
      cork.scale.set(1.8, 1.6, 1.8);
      cork.position.set(0, 0.7, 0);
      holder.add(cork);
    }
    // 믹싱 글라스 속 바 스푼
    let stirSpoon = null;
    if (kind === 'stir') {
      stirSpoon = new THREE.Mesh(buildBarSpoon(), mkMat({ metalness: 0.8, roughness: 0.25 }));
      stirSpoon.position.set(0, box.min.y + 1.2, 0);
      stirSpoon.visible = false;
      holder.add(stirSpoon);
    }
    // 믹싱 글라스에서 따를 땐 스트레이너로 얼음을 거른다
    let strainer = null;
    if (st.v === 'mixglass') {
      strainer = new THREE.Mesh(buildStrainer(), mkMat({ metalness: 0.8, roughness: 0.25 }));
      strainer.visible = !kind;
      holder.add(strainer);
    }
    // 블렌더 받침과 뚜껑
    let blendBase = null, blendLid = null;
    if (st.v === 'blender') {
      if (baseH) {
        blendBase = new THREE.Mesh(buildBlenderBase(), mkMat({ roughness: 0.5 }));
        blendBase.position.set(rest.x, 0, rest.z);
        blendBase.castShadow = true;
        frame.add(blendBase);
      }
      blendLid = new THREE.Mesh(buildBlenderLid(), mkMat({ roughness: 0.6 }));
      blendLid.visible = !(mixing && build.length);
      holder.add(blendLid);
    }
    // 섞는 그릇에 넣는 얼음
    const ices = [];
    if (mixing) {
      const im = mkMat({ vertexColors: false, color: 0xe4f5fb, transparent: true, opacity: 0.7, roughness: 0.05 });
      for (let i = 0; i < 4; i++) {
        const c = new THREE.Mesh(UNIT_BOX, im);
        c.scale.setScalar(1.7);
        c.visible = false;
        c.userData = { x: (i % 2 ? 0.9 : -0.9) * (i < 2 ? 1 : 0.6), z: (i < 2 ? 0.7 : -0.8), y: box.min.y + 1.6 + Math.floor(i / 2) * 1.6 };
        holder.add(c);
        ices.push(c);
      }
    }
    // 층 쌓기: 수면 바로 위에 바 스푼을 엎어 대고 그 위로 따른다
    let layerSpoon = null;
    if (st.layer) {
      layerSpoon = new THREE.Mesh(buildBarSpoon(), mkMat({ metalness: 0.8, roughness: 0.25 }));
      layerSpoon.rotation.z = -0.55;
      layerSpoon.scale.setScalar(0.001);
      layerSpoon.userData.size = clamp(shape.H / 16, 0.45, 0.8); // 샷 잔에 비해 너무 크지 않게
      frame.add(layerSpoon);
    }

    const streamMat = new THREE.MeshStandardMaterial({ color: streamColor, transparent: true, opacity: 0.75, roughness: 0.1, depthWrite: false });
    const stream = new THREE.Mesh(UNIT_BOX, streamMat);
    stream.visible = false;
    stream.renderOrder = this.renderBase + 2;
    frame.add(stream);
    // 재료 병에서 그릇으로 떨어지는 줄기
    const ingStreamMat = mkMat({ vertexColors: false, color: 0xffffff, transparent: true, opacity: 0.75, roughness: 0.1, depthWrite: false });
    const ingStream = new THREE.Mesh(UNIT_BOX, ingStreamMat);
    ingStream.visible = false;
    frame.add(ingStream);
    const ingBottles = [];

    const audio = this.env.audio, fx = this.env.fx;
    let poured = false;
    const once = new Set();
    const at = (key, cond) => (cond && !once.has(key) ? (once.add(key), true) : false);
    // 바텐더 손이 갈 곳 (월드)
    const hands = this.hands;
    // 오른손은 그릇 바깥쪽(-x)을, 왼손은 안쪽(+x, 바텐더 왼쪽)을 잡는다: 두 손이 한 점에 겹치지 않게
    const gripLocal = new THREE.Vector3(center.x + box.min.x * 0.45, center.y + (box.max.y - box.min.y) * 0.12, 0);
    const gripLeft = new THREE.Vector3(box.max.x + 1.4, center.y + (box.max.y - box.min.y) * 0.22, 0);
    const setHand = (which, obj, x, y, z) => {
      if (!srv) return;
      if (!obj) { hands[which] = null; return; }
      obj.updateWorldMatrix(true, false);
      hands[which] = obj.localToWorld((which === 'right' ? this.handR : this.handL).set(x, y, z));
    };
    const gripMain = (which = 'right') => (which === 'left'
      ? setHand('left', holder, gripLeft.x, gripLeft.y, gripLeft.z)
      : setHand('right', holder, gripLocal.x, gripLocal.y, gripLocal.z));

    // 들고 오기/내려놓기 경로: 먼저 바 위로 올린 다음 앞으로
    // 다른 잔 위로 넘겨야 할 땐: 먼저 높이 들고 → 그 높이로 옮기고 → 내려놓는다
    const overPath = (a, b, top, u, out) => {
      if (u < 0.3) return out.set(a.x, a.y + (top - a.y) * smooth(u / 0.3), a.z);
      if (u < 0.75) {
        const k = smooth((u - 0.3) / 0.45);
        return out.set(a.x + (b.x - a.x) * k, top, a.z + (b.z - a.z) * k);
      }
      return out.set(b.x, top + (b.y - top) * smooth((u - 0.75) / 0.25), b.z);
    };
    const carry = (a, b, u, out) => {
      const up = Math.max(a.y, b.y) + 4;
      if (overIn > up) return overPath(a, b, overIn, u, out);
      if (u < 0.45) {
        const k = smooth(u / 0.45);
        return out.set(a.x, a.y + (up - a.y) * k, a.z);
      }
      const k = smooth((u - 0.45) / 0.55);
      return out.set(a.x + (b.x - a.x) * k, up + (b.y - up) * k, a.z + (b.z - a.z) * k);
    };

    // 바텐더 왼손으로 잔을 받칠 곳 (잔 몸통 기준). 굽 달린 잔은 굽을, 아니면 몸통 아래쪽을
    const stemmed = shape.cavityStart > shape.H * 0.3;
    const holdY = stemmed ? Math.max(1.2, shape.cavityStart * 0.55) : Math.max(shape.cavityStart + 1, shape.H * 0.62);
    const holdX = (stemmed ? 1.2 : shape.cavityR[Math.floor(holdY)] || shape.rimR) + 2.6;
    // 맥주처럼 거품 나는 술은 잔을 그릇 쪽으로 기울여 받는다
    const beerPour = !!srv && !!drink.foam && carb > 0 && !st.layer && !drink.shot;
    const tiltDir = new THREE.Vector3(-1, 0, 0).applyQuaternion(frameYaw);

    const lift = (h, dx = 0) => holder.position.set(rest.x + dx, rest.y + h, rest.z);
    // (px, py)를 축으로 z 회전 (병 입구가 원점이라 위치를 보정한다)
    const spinAbout = (rz, px = center.x, py = center.y, ry = 0) => {
      const c = Math.cos(rz), s = Math.sin(rz);
      holder.position.x += px - (px * c - py * s);
      holder.position.y += py - (px * s + py * c);
      holder.rotation.set(0, ry, rz);
    };

    // 재료 하나: 병이 나와서 그릇 위로 기울여 붓고 돌아간다
    const ING_S = 0.9; // 재료 병은 조금 작게
    const ingBottle = (i) => {
      let g = ingBottles[i];
      if (g) return g;
      const ing = build[i];
      const v = buildVessel({ v: ing.v, color: ing.color, label: ing.label, cap: ing.cap, liquid: ing.liquid ?? ing.stream });
      const im = mkMat({ roughness: v.metal ? 0.3 : v.glassy ? 0.08 : 0.22, metalness: v.metal ? 0.7 : 0.05, transparent: v.glassy, opacity: v.glassy ? 0.62 : 1, depthWrite: !v.glassy, envMapIntensity: 0.8 });
      g = new THREE.Group();
      const m = new THREE.Mesh(v.geo, im);
      m.castShadow = true;
      g.add(m);
      g.userData.v = v;
      g.scale.setScalar(0.001);
      frame.add(g);
      ingBottles[i] = g;
      return g;
    };
    // 바텐더는 재료 병을 그릇 앞에 한 줄로 늘어놓고 하나씩 집어 붓는다 (손님 쪽, 그릇 왼쪽으로)
    const ingSpot = (i, v) => (srv
      ? new THREE.Vector3(rest.x + box.min.x + 1 - i * 6.4, -v.box.min.y * ING_S, rest.z + box.max.z + v.box.max.z * ING_S + 1.6)
      : new THREE.Vector3(rest.x + box.min.x - 2.2 - v.box.max.x * ING_S, -v.box.min.y * ING_S, rest.z + 2));
    const firstIng = phases.find((p) => p.name === 'ing');
    const lastIng = [...phases].reverse().find((p) => p.name === 'ing');

    // 기울인 재료 병이 옆의 다른 잔 위로 뻗으면 그 잔보다 높이 들어서 붓는다
    const ingLift = [];
    const ingClear = (i, v, mouth) => {
      if (ingLift[i] != null) return ingLift[i];
      let need = 0;
      // 기울이는 내내(살짝 기울었을 땐 병 밑동이 아래로 처진다) 다른 잔 입구보다 높게
      const b = v.box, z0 = b.min.z * ING_S, z1 = b.max.z * ING_S;
      for (const o of others) {
        const p = frame.worldToLocal(_pt.copy(o.root.position));
        const k = o.root.scale.x / this.root.scale.x;
        const r = (o.shape.contactR || o.shape.rimR) * k + 1, top = p.y + o.shape.H * k + 1.5;
        if (Math.max(mouth.z + z0 - p.z, 0, p.z - (mouth.z + z1)) >= r) continue;
        for (let f = 0; f <= 1.001; f += 0.1) {
          const a = -v.angle * f, c = Math.cos(a), sn = Math.sin(a);
          // 병을 길이 방향으로 몇 군데 잘라서, 그 자리가 잔 위면 그 높이를 본다
          for (let q = 0; q <= 1.001; q += 0.125) {
            const y = b.max.y + (b.min.y - b.max.y) * q;
            for (const x of [b.min.x, b.max.x]) {
              const rx = (x * c - y * sn) * ING_S, ry = (x * sn + y * c) * ING_S;
              if (Math.abs(mouth.x + rx - p.x) > r) continue;
              need = Math.max(need, top - (mouth.y + ry + (1 - f) * 6));
            }
          }
        }
      }
      return (ingLift[i] = need);
    };

    // 재료 하나: 병을 들어 그릇 위로 기울여 붓고 제자리에 둔다
    const ingredient = (i, u, tp) => {
      const ing = build[i];
      const g = ingBottle(i);
      const v = g.userData.v;
      const iRest = ingSpot(i, v);
      const mouth = new THREE.Vector3(rest.x + intake.x - 1.0, rest.y + intake.y + 3.5, rest.z);
      mouth.y += ingClear(i, v, mouth);
      const lifted = smooth(clamp((u - 0.12) / 0.2, 0, 1)) * (1 - smooth(clamp((u - 0.72) / 0.2, 0, 1)));
      const tilt = smooth(clamp((u - 0.26) / 0.16, 0, 1)) * (1 - smooth(clamp((u - 0.66) / 0.14, 0, 1)));
      let shown;
      if (srv) {
        // 늘어놓은 병들: 재료 넣기 시작할 때 꺼내 놓고, 다 넣으면 치운다
        const tb = tp - firstIng.t0, te = lastIng.t0 + lastIng.dur - tp;
        const all = smooth(clamp(tb / 0.3, 0, 1)) * smooth(clamp(te / 0.3, 0, 1));
        for (let k = 0; k < build.length; k++) {
          if (k === i) continue;
          const o = ingBottle(k);
          o.visible = true;
          o.position.copy(ingSpot(k, o.userData.v));
          o.rotation.set(0, 0, 0);
          o.scale.setScalar(Math.max(0.001, all * ING_S));
        }
        shown = all;
      } else {
        shown = smooth(clamp(u / 0.12, 0, 1)) * (1 - smooth(clamp((u - 0.88) / 0.12, 0, 1)));
      }
      g.visible = true;
      g.position.lerpVectors(iRest, mouth, lifted);
      g.position.y += lifted * (1 - tilt) * 6;
      g.scale.setScalar(Math.max(0.001, shown * ING_S));
      g.rotation.set(0, 0, -v.angle * tilt);
      const pouring = tilt > 0.85;
      ingStream.visible = pouring;
      if (pouring) {
        const top = g.position.y - 0.2, bottom = rest.y + intake.y - 1;
        ingStreamMat.color.setHex(ing.stream ?? 0xffffff);
        const w = ing.dash ? 0.35 : 0.55;
        ingStream.scale.set(w, Math.max(0.1, top - bottom), w);
        ingStream.position.set(g.position.x + 0.2, (top + bottom) / 2, rest.z);
        if (at('ip' + i, true)) audio?.startPour(ing.dash ? 0.25 : 0.45, { carb: 0 });
      }
      setHand('right', g, v.box.getCenter(_v).x, (v.box.min.y + v.box.max.y) / 2, 0);
      gripMain('left');
    };

    // 얼음 넣기: 위에서 하나씩 떨어진다
    const iceIn = (u) => {
      ices.forEach((c, k) => {
        const tk = clamp((u - k * 0.16) / 0.38, 0, 1);
        if (tk <= 0) return;
        const d = c.userData;
        const y0 = intake.y + 10, y1 = bottle.glassy || st.v === 'blender' ? d.y : intake.y - 1.5;
        const y = y0 + (y1 - y0) * tk * tk;
        c.position.set(d.x * (1 - tk * 0.2), y, d.z);
        c.rotation.set(k + tk * 2, k * 0.7, tk);
        // 불투명한 셰이커 속으로 들어가면 안 보인다
        c.visible = isShaker ? y > intake.y - 0.5 : true;
        if (at('ice' + k, tk >= 1)) audio?.iceDrop(0.6, this.panHint);
      });
      setHand('right', holder, 0, intake.y + 7, 0);
      gripMain('left');
    };

    // 뚜껑 덮기 / 열기
    const capOn = (u) => {
      const k = smooth(u);
      if (isShaker) {
        lid.visible = cap.visible = true;
        lid.position.y = cap.position.y = (1 - k) * 9;
        setHand('right', cap, 0, 1, 0);
      } else if (blendLid) {
        blendLid.visible = true;
        blendLid.position.y = (1 - k) * 9;
        setHand('right', blendLid, 0, 2.5, 0);
      }
      gripMain('left');
    };
    const finish = (u) => {
      const k = smooth(u);
      if (isShaker) {
        // 마개만 빼고, 거름망 뚜껑으로 따른다
        cap.position.set(-k * 5, k * 7, 0);
        cap.visible = k < 0.95;
        setHand('right', cap, 0, 1, 0);
        gripMain('left');
      } else if (kind === 'stir') {
        strainer.visible = true;
        strainer.position.y = (1 - k) * 8;
        setHand('right', strainer, -5, 0, 0);
        gripMain('left');
      } else if (blendLid) {
        blendLid.position.set(-k * 6, k * 8, 0);
        blendLid.visible = k < 0.95;
        setHand('right', blendLid, 0, 2.5, 0);
      }
    };

    // 섞기 동작 (u: 0 → 1)
    const action = (u, dt) => {
      switch (kind) {
        case 'shake': {
          // 셰이커를 들어 위아래로 흔든다, 얼음이 짤랑
          const up = smooth(clamp(u / 0.18, 0, 1)) * (1 - smooth(clamp((u - 0.86) / 0.14, 0, 1)));
          const w = Math.sin(u * ACT * Math.PI * 2 * 6.5);
          // 잔에서 조금 떨어져서 흔든다 (기울어진 셰이커 밑동이 잔 입구에 닿지 않게)
          lift(up * (8 + w * 2.6), up * (w * 0.9 - 4.5));
          spinAbout(up * (0.3 + w * 0.2));
          const beat = Math.floor(u * ACT * 6.5 * 2);
          if (up > 0.5 && at('b' + beat, true)) audio?.rattle(0.9, this.panHint, true);
          // 두 손으로: 오른손은 통, 왼손은 뚜껑
          gripMain('right');
          setHand('left', holder, 0, 1.2, 0);
          break;
        }
        case 'stir': {
          // 믹싱 글라스: 바 스푼으로 얼음과 함께 빙글빙글 저은 뒤 스푼을 뺀다
          lift(0);
          holder.rotation.set(0, 0, 0);
          const a = u * ACT * Math.PI * 2 * 2.4;
          const inside = smooth(clamp(u / 0.12, 0, 1)) * (1 - smooth(clamp((u - 0.82) / 0.18, 0, 1)));
          stirSpoon.position.set(Math.cos(a) * 1.7, box.min.y + 1.2 + (1 - inside) * 22, Math.sin(a) * 1.7);
          stirSpoon.rotation.set(Math.sin(a) * 0.12, 0, -Math.cos(a) * 0.12);
          stirSpoon.visible = inside > 0.05;
          const beat = Math.floor(u * ACT * 4);
          if (inside > 0.5 && at('t' + beat, true)) audio?.rattle(0.35, this.panHint, false);
          setHand('right', stirSpoon, 0, 24, 0);
          gripMain('left');
          break;
        }
        case 'blend': {
          // 블렌더: 위잉 하고 갈리는 동안 통이 덜덜 떨린다
          const on = u > 0.08 && u < 0.88;
          const j = on ? 0.25 : 0;
          lift(0, rand(-j, j));
          holder.rotation.set(rand(-j, j) * 0.1, 0, rand(-j, j) * 0.1);
          if (at('whirr', u > 0.08)) audio?.blend(ACT * 0.8, this.panHint);
          setHand('right', holder, 0, 3, 0);
          setHand('left', null);
          break;
        }
        case 'cork': {
          // 살살 비틀다가 펑
          const wig = u < 0.45 ? Math.sin(u * 60) * 0.06 * smooth(u / 0.45) : 0;
          const kick = u > 0.45 ? Math.exp(-(u - 0.45) * 14) * -1.2 : 0;
          lift(kick);
          const lean = smooth(clamp(u / 0.2, 0, 1)) * (1 - smooth(clamp((u - 0.82) / 0.18, 0, 1)));
          spinAbout(lean * 0.32 + wig, center.x, box.min.y, wig * 3);
          gripMain('right');
          setHand('left', holder, 0, 1, 0);
          if (at('pop', u > 0.45)) {
            const m = this.mouthWorld(holder);
            if (cork) cork.visible = false;
            audio?.pop(this.panHint);
            if (fx) {
              const dir = this.toWorldDir(-0.55, 0);
              fx.drops.spawn({ x: m.x, y: m.y + 1, z: m.z, vx: dir[0] * 40 + rand(-6, 6), vy: rand(85, 105), vz: dir[1] * 40 + rand(-6, 6), gravity: 260, life: 2.6, size: 2, color: 0xc9a26b, floor: (this.env.tableY ?? 0) + 1, env: 'hold' });
              for (let i = 0; i < 18; i++) {
                fx.drops.spawn({ x: m.x, y: m.y + 0.5, z: m.z, vx: dir[0] * rand(4, 18) + rand(-5, 5), vy: rand(14, 42), vz: dir[1] * rand(4, 18) + rand(-5, 5), gravity: 160, life: 1.4, size: rand(0.6, 1.2), color: i % 3 ? 0xfff8e3 : drink.color, floor: (this.env.tableY ?? 0) + 0.3, env: 'hold' });
              }
              for (let i = 0; i < 8; i++) {
                fx.steam.spawn({ x: m.x, y: m.y + 1, z: m.z, vx: rand(-4, 4), vy: rand(6, 14), vz: rand(-4, 4), drag: 2, life: rand(0.6, 1.1), size: rand(1, 2), color: 0xffffff, env: 'steam' });
              }
            }
          }
          // 터진 뒤 거품이 조금 흘러넘친다
          if (u > 0.47 && u < 0.85 && fx && Math.random() < dt * 30) {
            const m = this.mouthWorld(holder);
            fx.drops.spawn({ x: m.x + rand(-0.6, 0.6), y: m.y, z: m.z + rand(-0.6, 0.6), vx: rand(-3, 3), vy: rand(2, 8), vz: rand(-3, 3), gravity: 120, life: 1.2, size: rand(0.5, 0.9), color: 0xfff8e3, floor: (this.env.tableY ?? 0) + 0.3, env: 'hold' });
          }
          break;
        }
        case 'can': {
          // 딸깍, 치익
          const hop = u > 0.3 ? Math.exp(-(u - 0.3) * 12) * Math.sin((u - 0.3) * 40) * 0.8 : 0;
          lift(Math.max(0, hop));
          holder.rotation.set(0, 0, hop * 0.05);
          gripMain('left');
          setHand('right', holder, 0.6, 0.6, 0);
          if (at('open', u > 0.3)) {
            audio?.canOpen(this.panHint);
            if (fx) {
              const m = this.mouthWorld(holder);
              for (let i = 0; i < 10; i++) {
                fx.steam.spawn({ x: m.x, y: m.y + 0.5, z: m.z, vx: rand(-5, 5), vy: rand(10, 22), vz: rand(-5, 5), drag: 3, life: rand(0.35, 0.6), size: rand(0.5, 1.1), color: 0xffffff, env: 'steam' });
              }
            }
          }
          break;
        }
        case 'flip': {
          // 막걸리: 거꾸로 뒤집어 가라앉은 걸 섞는다
          const up = smooth(clamp(u / 0.15, 0, 1)) * (1 - smooth(clamp((u - 0.88) / 0.12, 0, 1)));
          // 사발과 부딪히지 않게 옆으로 비켜서 뒤집는다
          lift(up * 10, -up * (box.max.y - box.min.y) * 0.4);
          const v = clamp((u - 0.12) / 0.76, 0, 1);
          spinAbout((Math.PI * (1 - Math.cos(v * Math.PI * 2))) / 2);
          gripMain('right');
          setHand('left', null);
          if (at('s1', v > 0.18)) audio?.slosh(this.panHint);
          if (at('s2', v > 0.68)) audio?.slosh(this.panHint);
          break;
        }
        case 'swirl': {
          // 소주: 병목을 쥐고 휘휘 돌린 다음 바닥을 툭
          const up = smooth(clamp(u / 0.15, 0, 1)) * (1 - smooth(clamp((u - 0.9) / 0.1, 0, 1)));
          const spin = smooth(clamp((u - 0.1) / 0.6, 0, 1));
          const tilt = 0.4 * Math.sin(spin * Math.PI);
          const jolt = u > 0.76 ? Math.exp(-(u - 0.76) * 26) * 3 : 0;
          // 기울인 채 돌리니 병 밑동이 잔에 닿지 않게 옆으로 비켜서
          lift(up * 9 + jolt, -up * (3 + Math.max(0, (box.max.y - box.min.y) * 0.4 - 4)));
          holder.rotation.set(0, spin * Math.PI * 5, tilt);
          setHand('right', holder, 0, -2, 0);
          setHand('left', null);
          if (at('tap', u > 0.76)) audio?.tap(this.panHint);
          break;
        }
        default:
          lift(0);
      }
    };

    const ritual = (tp, dt) => {
      // tp: 준비 단계에서 흐른 시간
      let cur = null;
      for (const p of phases) if (tp >= p.t0) cur = p;
      if (!cur) { lift(0); return; }
      const u = clamp((tp - cur.t0) / cur.dur, 0, 1);
      if (cur.name !== 'act') { lift(0); holder.rotation.set(0, 0, 0); }
      if (cur.name !== 'ing') ingStream.visible = false;
      switch (cur.name) {
        case 'ing': ingredient(cur.i, u, tp); break;
        case 'ice': iceIn(u); break;
        case 'cap': capOn(u); break;
        case 'act': action(u, dt); break;
        case 'fin': finish(u); break;
      }
      // 다 쓴 재료 병은 치운다 (바텐더는 재료를 다 넣을 때까지 늘어놓는다)
      for (let k = 0; k < ingBottles.length; k++) {
        if (!ingBottles[k]) continue;
        if (cur.name !== 'ing' || (!srv && k < cur.i)) ingBottles[k].visible = false;
      }
    };

    const s = {
      t: 0, stream, blocking: true, pour: true, holder, ingStream, ingBottles,
      cleanup: () => {
        frame.removeFromParent();
        this.serveTilt.x = this.serveTilt.z = 0;
        mat.dispose(); streamMat.dispose();
        extras.forEach((m) => m.dispose());
        audio?.stopPour();
        hands.right = hands.left = null;
      },
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        // 잔이 흔들리거나 기울어도 병은 세상 기준으로 똑바로 (잔 몸통 기준 틀을 거꾸로 돌린다)
        frame.quaternion.copy(this.pivot.quaternion).invert().multiply(frameYaw);
        if (srv) {
          holder.scale.setScalar(1);
          if (blendBase) blendBase.scale.setScalar(Math.max(0.001, smooth(clamp(t / 0.3, 0, 1)) * (1 - smooth(clamp((t - end + 0.3) / 0.3, 0, 1)))));
        } else {
          const appear = smooth(clamp(t / A, 0, 1)) * (1 - smooth(clamp((t - (end - 0.25)) / 0.25, 0, 1)));
          holder.scale.setScalar(Math.max(0.001, appear * 0.95));
          if (blendBase) blendBase.scale.setScalar(Math.max(0.001, appear));
        }
        if (srv && t < A) {
          // 바 아래(또는 앞 잔)에서 들고 온다
          if (handIn) {
            // 들어 올리고 → 옆으로 옮기고 → 내려놓는다 (잔 위를 지날 땐 밑동이 잔 입구보다 높게)
            const u = t / A;
            const kx = smooth(clamp((u - 0.15) / 0.6, 0, 1));
            holder.position.lerpVectors(start, rest, kx);
            holder.position.y += smooth(clamp(u / 0.2, 0, 1)) * (1 - smooth(clamp((u - 0.78) / 0.22, 0, 1))) * handBump;
          } else {
            carry(start, rest, t / A, holder.position);
          }
          holder.rotation.set(0, 0, 0);
          gripMain('right');
          setHand('left', null);
        } else if (t < T0) {
          ritual(t - A, dt);
          if (!phases.length) { gripMain('right'); setHand('left', null); }
        } else if (srv && lower > 0 && t > end0) {
          // 다 따랐으면 바 아래로 도로 내려놓는다: 바 위로 옮긴 다음 내린다
          const u = clamp((t - end0) / lower, 0, 1);
          const up = rest.y + 4;
          if (overOut > up) overPath(rest, under, overOut, u, holder.position);
          else if (u < 0.55) {
            const k = smooth(u / 0.55);
            holder.position.set(rest.x + (under.x - rest.x) * k, rest.y + (up - rest.y) * Math.min(1, k * 2), rest.z + (under.z - rest.z) * k);
          } else {
            const k = smooth((u - 0.55) / 0.45);
            holder.position.set(under.x, up + (under.y - up) * k, under.z);
          }
          holder.rotation.set(0, 0, 0);
          gripMain('right');
          setHand('left', null);
        } else {
          // 들어 올리고 → 잔 위로 옮겨 → 기울여 따르고 → 바로 세워 → 비켜난 다음 → 내려놓는다
          const back = smooth(clamp((t - pour1 + 0.05) / 0.4, 0, 1));
          const move = smooth(clamp((t - T0) / 0.3, 0, 1)) * (1 - smooth(clamp((back - 0.45) / 0.55, 0, 1)));
          // 옆으로는 다 들어 올린 다음에 옮기고, 내릴 때도 잔에서 비켜난 다음에 내린다 (그릇이 잔을 긁지 않게)
          const hy = smooth(clamp((t - T0) / 0.2, 0, 1)) * (1 - smooth(clamp((back - 0.72) / 0.28, 0, 1)));
          const hx = smooth(clamp((t - T0 - 0.12) / 0.26, 0, 1)) * (1 - smooth(clamp((back - 0.4) / 0.35, 0, 1)));
          const tilt = smooth(clamp((t - T0 - 0.12) / 0.3, 0, 1)) * (1 - smooth(clamp(back / 0.6, 0, 1)));
          // 잔이 기울면(맥주를 받거나 흔들리면) 입구가 옮겨 간다: 그릇도 잔 입구를 따라간다
          _qf.copy(frame.quaternion).invert();
          _rim.set(0, shape.H, 0).applyQuaternion(_qf);
          _upF.set(0, 1, 0).applyQuaternion(_qf);
          const lean = Math.hypot(_upF.x, _upF.z);
          // 기울면 그릇 쪽 입구 가장자리가 올라오니 그만큼 높여서
          const rimTop = _rim.y + shape.rimR * lean;
          aim.set(pourAt.x + _rim.x, pourAt.y - shape.H + rimTop, pourAt.z + _rim.z);
          holder.position.set(rest.x + (aim.x - rest.x) * hx, rest.y + (aim.y - rest.y) * hy, rest.z + (aim.z - rest.z) * hx);
          // 세운 채로는 그릇 밑동이 잔 입구보다 높이 있어야 한다. 기울이면서 천천히 내린다
          const clear = Math.max(6, rimTop + 1.5 - (aim.y + box.min.y) + Math.max(0, rest.y - aim.y));
          holder.position.y += hy * (1 - tilt * tilt) * clear;
          holder.rotation.set(0, 0, -bottle.angle * tilt * (st.layer ? 0.85 : 1));
          gripMain('right');
          // 스트레이너·뚜껑을 왼손으로 누르고, 층 쌓을 땐 바 스푼을 잡는다. 나머지는 왼손으로 잔을 잡아 받친다
          if (layerSpoon) setHand('left', layerSpoon, 0, 20, 0);
          // (스트레이너·셰이커 뚜껑은 따르는 손 검지로 누른다)
          else if (move > 0.35) setHand('left', this.body, holdX, holdY, 0);
          else setHand('left', null);
          // 맥주: 잔을 45도쯤 기울여 벽을 타고 받다가, 차오를수록 세운다
          if (beerPour) {
            const p = clamp((t - pour0) / pourDur, 0, 1);
            // 그릇이 잔 위로 넘어온 다음에 기울인다 (먼저 기울이면 옆에 대기 중인 병에 닿는다)
            const k = hx * (1 - smooth(clamp((p - 0.25) / 0.6, 0, 1))) * 0.55;
            this.serveTilt.x = k * tiltDir.z;
            this.serveTilt.z = -k * tiltDir.x;
          }
        }
        if (layerSpoon) {
          const k = smooth(clamp((t - T0) / 0.3, 0, 1)) * (1 - smooth(clamp((t - pour1) / 0.3, 0, 1)));
          layerSpoon.scale.setScalar(Math.max(0.001, k * layerSpoon.userData.size));
          layerSpoon.position.set(0.2, this.level + 0.6, 0.2);
        }
        const pouring = t > pour0 && t < pour1;
        if (pouring && !poured) { poured = true; audio?.startPour(pourDur, { carb }); }
        stream.visible = pouring;
        if (pouring) {
          const p = (t - pour0) / pourDur;
          this.fill = from + (to - from) * smooth(p);
          if (useTint && (tintTo != null || this.tint != null)) {
            const tt = tintTo ?? drink.color;
            this.setTint(_c.set(tintFrom).lerp(_c2.set(tt), smooth(p)).getHex());
          }
          if (drink.foam && carb) this.foamBoost = Math.min(2.6, this.foamBoost + dt * 2.4);
          if (carb) this.bubbleBurst += dt * 24 * carb;
          const top = holder.position.y - 0.2;
          const sx = holder.position.x + 0.25, sz = holder.position.z;
          let bottom = st.layer ? this.level + 1.2 : Math.max(shape.cavityStart, this.level);
          if (!st.layer) {
            // 줄기 끝: 잔 입구로 들어가 수면이나 안쪽 벽에 닿는 곳. 잔을 기울여 받으면 벽을 타고 흐른다
            _qf.copy(frame.quaternion);
            for (let y = top; y > -2; y -= 0.25) {
              _pt.set(sx, y, sz).applyQuaternion(_qf);
              bottom = y;
              if (_pt.y > shape.H) continue;
              const cr = shape.cavityR[clamp(Math.floor(_pt.y), 0, shape.H - 1)] || 0;
              if (Math.hypot(_pt.x, _pt.z) > cr - 0.35 || _pt.y <= Math.max(shape.cavityStart, this.level)) {
                // 수면에 딱 맞게 조금 되올린다
                const floor = Math.max(shape.cavityStart, this.level);
                if (_pt.y < floor) bottom += Math.min(0.25, (floor - _pt.y) / Math.max(0.3, _upF.set(0, 1, 0).applyQuaternion(_qf).y));
                break;
              }
            }
          }
          const thin = st.layer ? 0.4 : 0.7;
          const wob = Math.sin(t * 40) * 0.08;
          stream.scale.set(thin + wob, Math.max(0.1, top - bottom), thin - wob);
          stream.position.set(sx, (top + bottom) / 2, sz);
          if (!st.layer) this.slosh.vx += Math.sin(t * 13) * dt * 1.2;
          // 뜨거운 걸 따르면 주둥이에서도 김이 난다
          if (hot && fx && Math.random() < dt * 14) {
            const m = this.mouthWorld(holder);
            fx.steam.spawn({ x: m.x, y: m.y, z: m.z, vx: rand(-1, 1), vy: rand(6, 10), vz: rand(-1, 1), wobble: 3, drag: 0.4, life: rand(1.4, 2.2), size: rand(1, 1.8), env: 'steam', color: 0xf5efe6 });
          }
        }
        if (t >= end) {
          // 다음 잔으로 넘길 그릇 위치
          const out = keepLast ? this.mouthWorld(holder).clone() : null;
          s.cleanup();
          this.fill = to;
          if (last && tintTo == null) this.setTint(null);
          onDone?.(out);
          return true;
        }
        return false;
      },
    };
    this.seq.push(s);
  }

  // 다 따른 뒤
  afterPour(st) {
    const drink = this.drink;
    this.fill = 1;
    this.popGarnish();
    const b = drink.bomb?.type;
    if (b === 'drop' && this.shot && drink.id !== 'gojingamrae') this.startDrop();
    else if (b === 'tornado') this.startTornado();
    else if (b === 'stir') this.startTornado({ peak: 0.55, dur: 2.2, fizz: false });
    else if (drink.bomb) this.startFizz();
    if (st.sink) this.startSink(st.sinkFrom ?? drink.color);
    if (drink.after === 'swirl') this.startTornado({ peak: 0.45, dur: 2.0, fizz: false });
    if (drink.after === 'stir') this.startStirGlass();
    if (drink.flame) this.ignite();
  }

  garnishParts() {
    return [this.garnish, this.garnishFloat, this.garnishProp].filter(Boolean);
  }

  hideGarnish() {
    for (const g of this.garnishParts()) g.scale.setScalar(0.001);
  }

  // 마지막에 장식을 톡 얹는다
  popGarnish() {
    const parts = this.garnishParts().filter((g) => g.scale.x < 0.9);
    this.garnishEaten = false;
    if (!parts.length) return;
    const s = {
      t: 0, soft: true,
      cleanup: () => parts.forEach((g) => g.scale.setScalar(1)),
      update: (dt) => {
        s.t += dt;
        const u = clamp(s.t / 0.32, 0, 1);
        // 살짝 커졌다가 제자리
        const k = Math.max(0.001, u < 0.7 ? smooth(u / 0.7) * 1.12 : 1.12 - 0.12 * smooth((u - 0.7) / 0.3));
        parts.forEach((g) => g.scale.setScalar(k));
        if (u >= 1) { s.cleanup(); return true; }
        return false;
      },
    };
    this.env.audio?.tap(this.panHint);
    this.seq.push(s);
  }

  // 마지막 모금 뒤에 장식을 집어 먹는다. dir: 마시는 사람 쪽 (잔 기준 +z가 1)
  eatGarnish(dir = 1) {
    if (this.garnishEaten) return;
    const parts = [this.garnish, this.garnishFloat].filter((g) => g && g.scale.x > 0.5);
    if (!parts.length) return;
    this.garnishEaten = true;
    const from = parts.map((g) => g.position.clone());
    const to = new THREE.Vector3(0, this.shape.H + 10, dir * 15);
    const soft = this.drink.garnish?.includes('olive') || this.drink.garnish?.includes('cherry') ? 0.4 : 0.8;
    let bit = false;
    const s = {
      t: 0, soft: true,
      cleanup: () => parts.forEach((g, i) => { g.position.copy(from[i]); g.scale.setScalar(0.001); g.rotation.set(0, 0, 0); }),
      update: (dt) => {
        s.t += dt;
        const u = clamp(s.t / 0.85, 0, 1);
        // 집어 들고(위로) → 입으로 가져가며 작아진다
        const lift = smooth(clamp(u / 0.35, 0, 1));
        const go = smooth(clamp((u - 0.25) / 0.6, 0, 1));
        parts.forEach((g, i) => {
          const f = from[i];
          g.position.set(f.x * (1 - go) + to.x * go, f.y + lift * 4 + (to.y - f.y - 4) * go, f.z * (1 - go) + to.z * go);
          g.rotation.set(go * 0.6 * dir, 0, lift * 0.3);
          g.scale.setScalar(Math.max(0.001, 1 - smooth(clamp((u - 0.6) / 0.4, 0, 1))));
        });
        if (!bit && u > 0.78) { bit = true; this.env.audio?.crunch(soft, this.panHint); }
        if (u >= 1) { s.cleanup(); return true; }
        return false;
      },
    };
    this.seq.push(s);
  }

  // 데킬라 선라이즈: 그레나딘이 바닥에 깔리며 노을이 생긴다
  startSink(fromColor) {
    this.tint = null;
    this.sinkBase = fromColor;
    this.sinkK = 0;
    this.refreshLUT();
    const s = {
      t: 0, soft: true,
      cleanup: () => { this.sinkK = null; this.refreshLUT(); },
      update: (dt) => {
        s.t += dt;
        this.sinkK = smooth(clamp(s.t / 2.6, 0, 1));
        this.refreshLUT();
        if (s.t > 2.7) { this.sinkK = null; this.refreshLUT(); return true; }
        return false;
      },
    };
    this.seq.push(s);
  }

  // 잔 안에서 바 스푼으로 젓기 (올드 패션드)
  startStirGlass() {
    const shape = this.shape;
    const spoon = new THREE.Mesh(buildBarSpoon(), new THREE.MeshStandardMaterial({ vertexColors: true, metalness: 0.8, roughness: 0.25, envMap: this.env.envMap }));
    spoon.scale.setScalar(0.001);
    this.body.add(spoon);
    const cr = (shape.cavityR[shape.cavityStart + 1] || 2) * 0.55;
    let beat = -1;
    const s = {
      t: 0, soft: true,
      cleanup: () => { spoon.removeFromParent(); spoon.material.dispose(); this.swirl = 0; },
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        const k = smooth(clamp(t / 0.25, 0, 1)) * (1 - smooth(clamp((t - 1.6) / 0.3, 0, 1)));
        const a = t * Math.PI * 2 * 1.6;
        spoon.scale.setScalar(Math.max(0.001, k));
        spoon.position.set(Math.cos(a) * cr, shape.cavityStart + 0.6 + (1 - k) * 10, Math.sin(a) * cr);
        spoon.rotation.set(Math.sin(a) * 0.15, 0, -Math.cos(a) * 0.15);
        this.swirl = 0.3 * k;
        const b = Math.floor(t * 3.2);
        if (k > 0.5 && b !== beat) { beat = b; this.env.audio?.rattle(0.3, this.panHint, false); }
        if (t > 1.95) { s.cleanup(); return true; }
        return false;
      },
    };
    this.seq.push(s);
  }

  // 로컬 방향 (x, z)를 월드 방향으로
  toWorldDir(lx, lz) {
    const c = Math.cos(this.yaw), s = Math.sin(this.yaw);
    return [lx * c + lz * s, -lx * s + lz * c];
  }

  // 얼음을 하나씩 떨어뜨린다
  dropIce() {
    this.ice.forEach((ice, i) => {
      ice.drop = { y: this.shape.H + 6 + i * 2.5, vy: 0, wait: 0.12 + i * 0.17 };
    });
  }

  // 불붙이기 (B-52)
  ignite(silent = false) {
    this.flameOn = true;
    this.flameFill = this.fill;
    this.flameAcc = 0;
    if (!silent) this.env.audio?.lighter(this.panHint);
  }

  extinguish(silent = false) {
    if (!this.flameOn) return;
    this.flameOn = false;
    const fx = this.env.fx;
    if (silent || !fx) return;
    this.env.audio?.puff(this.panHint);
    for (let i = 0; i < 9; i++) {
      _v.set(rand(-1.5, 1.5), this.level + 0.8, rand(-1.5, 1.5));
      this.body.localToWorld(_v);
      fx.steam.spawn({ x: _v.x, y: _v.y, z: _v.z, vx: rand(-2, 2), vy: rand(5, 10), vz: rand(-2, 2), wobble: 2, drag: 0.6, life: rand(1, 1.7), size: rand(1, 1.8), env: 'steam', color: i % 2 ? 0x9a948c : 0xb8b2aa });
    }
  }

  emitFlame(dt) {
    if (!this.flameOn) return;
    // 빨대로 다 마시면 꺼진다
    if (this.fill < 0.03) { this.extinguish(); return; }
    const fx = this.env.fx;
    if (!fx) return;
    const shape = this.shape;
    const lv = clamp(Math.floor(this.level), shape.cavityStart, shape.H - 1);
    const cr = (shape.cavityR[lv] || 1) * 0.8;
    this.flameAcc += dt * 55;
    while (this.flameAcc >= 1) {
      this.flameAcc -= 1;
      const a = Math.random() * Math.PI * 2, r = Math.sqrt(Math.random()) * cr;
      _v.set(Math.cos(a) * r, this.level + 0.3, Math.sin(a) * r);
      this.body.localToWorld(_v);
      // 바닥은 파란 불, 위로 갈수록 주황
      const blue = Math.random() < 0.4;
      const edge = 1 - (r / cr) * 0.5;
      fx.flames.spawn({
        x: _v.x, y: _v.y, z: _v.z,
        vx: -this.vel.x * 0.12 + rand(-1.2, 1.2), vy: (blue ? rand(5, 9) : rand(13, 24)) * edge, vz: -this.vel.z * 0.12 + rand(-1.2, 1.2),
        wobble: 5, life: blue ? rand(0.15, 0.3) : rand(0.28, 0.5), size: blue ? rand(0.9, 1.4) : rand(1.1, 2.0),
        color: blue ? (Math.random() < 0.6 ? 0x4f7dff : 0x8fb4ff) : Math.random() < 0.6 ? 0xffb347 : Math.random() < 0.5 ? 0xffd27a : 0xff7a2a,
      });
    }
  }

  startDrop() {
    const shot = this.shot;
    const shape = this.shape;
    shot.group.visible = true;
    shot.y = shape.H + 14;
    shot.vy = 0;
    let splashed = false;
    const s = {
      t: 0,
      update: (dt) => {
        s.t += dt;
        if (!splashed) {
          shot.vy -= 260 * dt;
          shot.y += shot.vy * dt;
          if (shot.y < this.level - 0.5) {
            splashed = true;
            shot.vy *= 0.25;
            this.splash(14, 0, 0, 0.9);
            this.bubbleBurst += 70;
            if (this.drink.foam) this.foamBoost = 3;
            this.slosh.vx += 4; this.slosh.vz -= 3;
            this.env.audio?.plop(this.panHint);
            this.env.audio?.fizz(2.2, 0.9, this.panHint);
          }
        } else {
          shot.vy += (-(shot.y - shot.restY) * 40 - shot.vy * 7) * dt;
          shot.y += shot.vy * dt;
          if (shot.y < shot.restY) { shot.y = shot.restY; shot.vy = 0; }
        }
        shot.group.position.y = shot.y;
        shot.group.rotation.z = splashed ? Math.sin(s.t * 9) * 0.08 * Math.exp(-s.t * 2) : 0;
        if (s.t > 3.2) { shot.y = shot.restY; shot.group.position.y = shot.y; return true; }
        return false;
      },
    };
    this.seq.push(s);
  }

  // 소맥 회오리 (와인 스월링, 살살 젓기도 같은 원리)
  startTornado({ peak = 1, dur = 2.8, fizz = true } = {}) {
    if (fizz) this.env.audio?.fizz(dur - 0.2, 1, this.panHint);
    const rise = 0.5, fall = Math.min(1, dur * 0.36);
    const s = {
      t: 0, soft: true,
      cleanup: () => { this.swirl = 0; },
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        this.swirl = peak * smooth(clamp(t / rise, 0, 1)) * (1 - smooth(clamp((t - (dur - fall)) / fall, 0, 1)));
        if (fizz || this.drink.carb) this.bubbleBurst += dt * 90 * this.swirl;
        if (this.drink.foam) this.foamBoost = Math.min(3, this.foamBoost + dt * 2 * this.swirl);
        if (t > dur) { this.swirl = 0; return true; }
        return false;
      },
    };
    this.seq.push(s);
  }

  startFizz() {
    this.bubbleBurst += 50;
    this.env.audio?.fizz(1.6, 0.6, this.panHint);
  }

  // 한 모금 (내 잔만 움직임이 있다)
  // 마신 양에 든 알코올 (ml)
  alcohol(df) {
    return Math.max(0, df) * this.capacity * (this.drink.abv || 0) / 100;
  }

  sip() {
    if (this.busy || this.fill < 0.02) return false;
    // 불붙은 술은 잔을 들면 데니까 빨대로 마신다
    if (this.flameOn) return this.strawSip();
    const f0 = this.fill;
    const f1 = this.drink.shot || this.glassDef.id === 'soju' || this.glassDef.id === 'shot' ? 0 : Math.max(0, f0 - 0.34);
    this.onDrink?.(this.alcohol(f0 - f1));
    let g1 = false, g2 = false;
    const s = {
      t: 0,
      blocking: true,
      cleanup: () => { this.offset.set(0, 0, 0); this.extraTilt.x = 0; },
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        const up = smooth(clamp(t / 0.4, 0, 1)) * (1 - smooth(clamp((t - 1.15) / 0.4, 0, 1)));
        this.offset.set(0, up * 16, up * 16);
        this.extraTilt.x = up * 0.9;
        if (t > 0.4 && t < 1.15) this.fill = f0 + (f1 - f0) * smooth((t - 0.4) / 0.75);
        if (t > 0.5 && !g1) { g1 = true; this.env.audio?.gulp(); }
        if (t > 0.85 && !g2 && f0 - f1 > 0.3) { g2 = true; this.env.audio?.gulp(); }
        if (t >= 1.55) {
          s.cleanup();
          this.fill = f1;
          if (f1 <= 0.001) this.eatGarnish(1);
          return true;
        }
        return false;
      },
    };
    this.seq.push(s);
    return true;
  }

  // 원샷: 잔을 높이 들어 끝까지 꿀꺽꿀꺽
  oneShot() {
    if (this.busy || this.fill < 0.02) return false;
    if (this.flameOn) return this.strawSip();
    const f0 = this.fill;
    this.onDrink?.(this.alcohol(f0));
    // 많이 남았을수록 오래, 꿀꺽도 여러 번
    const dur = 0.7 + f0 * 1.0;
    const gulps = Math.max(1, Math.round(1 + f0 * 3));
    let g = 0;
    const s = {
      t: 0,
      blocking: true,
      cleanup: () => { this.offset.set(0, 0, 0); this.extraTilt.x = 0; },
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        const up = smooth(clamp(t / 0.4, 0, 1)) * (1 - smooth(clamp((t - 0.4 - dur) / 0.45, 0, 1)));
        // 끝으로 갈수록 고개를 젖히듯 잔이 더 기운다
        const p = clamp((t - 0.4) / dur, 0, 1);
        this.offset.set(0, up * (18 + p * 4), up * 16);
        this.extraTilt.x = up * (0.95 + p * 0.55);
        if (t > 0.4 && t < 0.4 + dur) this.fill = f0 * (1 - smooth(p));
        if (g < gulps && t > 0.48 + (g * dur) / gulps) { g++; this.env.audio?.gulp(); }
        if (t >= 0.85 + dur) {
          s.cleanup();
          this.fill = 0;
          this.eatGarnish(1);
          return true;
        }
        return false;
      },
    };
    this.seq.push(s);
    return true;
  }

  // 빨대를 꽂고 잔은 그대로 둔 채 쭉 빨아 마신다. 불은 다 마셔야 꺼진다.
  strawSip() {
    if (this.busy || this.fill < 0.02) return false;
    const shape = this.shape;
    this.removeStraw();
    const straw = (this.straw = new THREE.Mesh(buildStraw(), new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.5 })));
    straw.rotation.z = 0.22;
    this.body.add(straw);
    const bottom = shape.cavityStart + 0.3;
    const x = -0.6;
    const f0 = this.fill;
    this.onDrink?.(this.alcohol(f0));
    let slurped = false;
    const s = {
      t: 0,
      blocking: true,
      cleanup: () => this.removeStraw(),
      update: (dt) => {
        s.t += dt;
        const t = s.t;
        // 위에서 쏙 꽂았다가, 다 마시면 뽑는다
        const down = smooth(clamp(t / 0.35, 0, 1)) * (1 - smooth(clamp((t - 1.7) / 0.35, 0, 1)));
        straw.position.set(x, bottom + (1 - down) * 18, 0);
        straw.visible = down > 0.02;
        if (t > 0.4 && t < 1.45) this.fill = f0 * (1 - smooth((t - 0.4) / 1.05));
        if (t > 0.42 && !slurped) { slurped = true; this.env.audio?.slurp(1.0, this.panHint); }
        if (t >= 2.1) { s.cleanup(); this.fill = 0; this.eatGarnish(1); return true; }
        return false;
      },
    };
    this.seq.push(s);
    return true;
  }

  removeStraw() {
    if (!this.straw) return;
    this.straw.removeFromParent();
    this.straw.material.dispose();
    this.straw = null;
  }

  // ── 매 프레임 ──────────────────────────────────────────
  update(dt, t) {
    if (dt <= 0) return;
    const p = this.root.position;
    if (!this.prevPos) this.prevPos = p.clone();
    _v.subVectors(p, this.prevPos).divideScalar(dt);
    this.vel.lerp(_v, 1 - Math.exp(-dt * 30));
    _v.subVectors(this.vel, this.prevVel).divideScalar(dt);
    this.acc.lerp(_v, 1 - Math.exp(-dt * 14));
    this.prevPos.copy(p);
    this.prevVel.copy(this.vel);
    const [ax, az] = this.toLocal(this.acc.x, this.acc.z);

    // 기울기: 내 잔은 관성으로, 남의 잔은 받은 값으로
    const L = this.lean;
    if (this.local) {
      const tx = clamp(-az * 0.00022, -0.3, 0.3);
      const tz = clamp(ax * 0.00022, -0.3, 0.3);
      L.vx += ((tx - L.x) * 170 - L.vx * 13) * dt;
      L.vz += ((tz - L.z) * 170 - L.vz * 13) * dt;
    } else {
      L.vx += ((this.netLean.x - L.x) * 170 - L.vx * 13) * dt;
      L.vz += ((this.netLean.z - L.z) * 170 - L.vz * 13) * dt;
    }
    L.x += L.vx * dt;
    L.z += L.vz * dt;
    const rx = L.x + this.extraTilt.x + this.serveTilt.x, rz = L.z + this.extraTilt.z + this.serveTilt.z;
    this.pivot.rotation.set(rx, 0, rz);

    // 출렁임: 잔이 기울어도 수면은 수평을 유지하려 한다
    const S = this.slosh;
    const sx = clamp(-Math.tan(rz) - ax * 0.00035, -1.6, 1.6);
    const sz = clamp(Math.tan(rx) - az * 0.00035, -1.6, 1.6);
    S.vx += ((sx - S.x) * 55 - S.vx * 3.2) * dt;
    S.vz += ((sz - S.z) * 55 - S.vz * 3.2) * dt;
    S.x = clamp(S.x + S.vx * dt, -1.8, 1.8);
    S.z = clamp(S.z + S.vz * dt, -1.8, 1.8);

    if (!this.local && this.netFill != null && !this.busy) {
      this.fill += (this.netFill - this.fill) * Math.min(1, dt * 6);
      // 친구가 마지막 모금을 마셨다
      if (this.netFill < 0.02 && (this.lastNetFill ?? 1) >= 0.05) this.eatGarnish(1);
      this.lastNetFill = this.netFill;
    }
    this.foamBoost *= Math.exp(-dt * 0.55);

    for (let i = 0; i < this.seq.length; i++) {
      let done;
      try {
        done = this.seq[i].update(dt);
      } catch (err) {
        // 연출 하나가 잘못돼도 게임 전체가 멈추지 않게 그 연출만 버린다
        console.error(err);
        try { this.seq[i].cleanup?.(); } catch { /* 정리 실패는 무시 */ }
        done = true;
      }
      if (done) { this.seq.splice(i, 1); i--; }
    }

    // 얼음 든 잔을 세게 흔들면 달그락
    this.rattleCd -= dt;
    if (this.local && this.ice.length && this.fill > 0.05 && this.rattleCd <= 0) {
      const a = Math.hypot(this.acc.x, this.acc.z);
      if (a > 1300) {
        this.rattleCd = 0.12 + Math.random() * 0.06;
        this.env.audio?.rattle(clamp((a - 1000) / 3000, 0.2, 0.8), this.panHint, false);
      }
    }

    // 커피콩처럼 뜨는 장식은 수면(거품 위)을 따라간다
    const fg = this.garnishFloat;
    if (fg && !this.garnishEaten) {
      fg.position.y = this.level + (this.foamH || 0) - 0.15;
      fg.visible = this.fill > 0.03;
    }
    this.root.updateMatrixWorld();
    this.updateBubbles(dt, t);
    this.updateFloaters(dt, t);
    this.updateIce(dt, t);
    this.emitSteam(dt);
    this.emitFlame(dt);
    this.checkSpill(dt);
    this.rebuildLiquid(t);
  }

  updateBubbles(dt, t) {
    if (!this.bubbles) return;
    const B = this.bub, d = this.drink, shape = this.shape;
    const cs = shape.cavityStart;
    const bottom = shape.cavityCols[cs];
    if (this.fill > 0.05 && bottom.length) {
      const rate = (d.carb || 0) * 13 + this.bubbleBurst * 3;
      this.bubbleBurst = Math.max(0, this.bubbleBurst - this.bubbleBurst * dt * 3 - dt * 2);
      let n = rate * dt + Math.random() * 0.999;
      while (n >= 1 && this.bubbleN < B.max) {
        n -= 1;
        const i = this.bubbleN;
        let x, y, z, v;
        if (d.bubble === 'stream' && this.nucleation.length && this.bubbleBurst < 1) {
          const [nx, nz] = this.nucleation[Math.floor(Math.random() * this.nucleation.length)];
          x = nx + rand(-0.1, 0.1); z = nz + rand(-0.1, 0.1); y = cs + 0.2; v = rand(7, 8);
        } else if (d.bubble === 'cascade' && Math.random() < 0.65) {
          // 흑맥주: 벽을 따라 내려가는 기포
          const a = Math.random() * Math.PI * 2;
          const lv = clamp(Math.floor(this.level) - 1, cs, shape.H - 1);
          const r = (shape.cavityR[lv] || 1) - 0.5;
          x = Math.cos(a) * r; z = Math.sin(a) * r; y = this.level - 0.6; v = -rand(2, 3.2);
        } else {
          const k = Math.floor(Math.random() * (bottom.length / 2)) * 2;
          x = bottom[k] + Math.random(); z = bottom[k + 1] + Math.random(); y = cs + 0.2; v = rand(3.5, 7.5);
        }
        B.x[i] = x; B.y[i] = y; B.z[i] = z; B.v[i] = v; B.s[i] = rand(0.28, 0.5); B.ph[i] = Math.random() * 6.28;
        this.bubbleN++;
      }
    } else {
      this.bubbleBurst = 0;
    }
    const sw = this.swirl;
    for (let i = 0; i < this.bubbleN; i++) {
      B.y[i] += B.v[i] * dt * (1 + sw);
      B.x[i] += Math.sin(t * 9 + B.ph[i]) * 0.5 * dt;
      if (sw > 0.01) {
        const a = sw * 5 * dt, c = Math.cos(a), s = Math.sin(a);
        const x = B.x[i], z = B.z[i];
        B.x[i] = x * c - z * s;
        B.z[i] = x * s + z * c;
      }
      const surf = this.surfaceAt(B.x[i], B.z[i]);
      const dead = B.v[i] > 0 ? B.y[i] > surf - 0.25 : B.y[i] < cs + 0.3;
      if (dead || this.fill < 0.02) {
        const j = --this.bubbleN;
        B.x[i] = B.x[j]; B.y[i] = B.y[j]; B.z[i] = B.z[j]; B.v[i] = B.v[j]; B.s[i] = B.s[j]; B.ph[i] = B.ph[j];
        i--;
        continue;
      }
      const lv = clamp(Math.floor(B.y[i]), cs, shape.H - 1);
      const lim = (shape.cavityR[lv] || 1) - 0.35;
      const r = Math.hypot(B.x[i], B.z[i]);
      if (r > lim) { B.x[i] *= lim / r; B.z[i] *= lim / r; }
      _s.setScalar(B.s[i]);
      _m.compose(_v.set(B.x[i], B.y[i], B.z[i]), _q.identity(), _s);
      this.bubbles.setMatrixAt(i, _m);
    }
    this.bubbles.count = this.bubbleN;
    if (this.bubbleN) {
      this.bubbles.instanceMatrix.addUpdateRange(0, this.bubbleN * 16);
      this.bubbles.instanceMatrix.needsUpdate = true;
    }
  }

  updateFloaters(dt, t) {
    if (!this.floaters) return;
    const spec = this.floatSpec, shape = this.shape;
    const cs = shape.cavityStart;
    const lv = clamp(Math.floor(this.level), cs, shape.H - 1);
    const cr = shape.cavityR[lv] || shape.cavityR[cs];
    const visible = this.fill > 0.03;
    this.fdata.forEach((f, i) => {
      f.a += f.sp * dt * (1 + this.swirl * 10);
      const r = f.r * Math.max(0.2, cr - spec.scale[0] * 0.6);
      const x = Math.cos(f.a) * r, z = Math.sin(f.a) * r;
      const y = Math.max(cs + spec.scale[1] * 0.5, this.surfaceAt(x, z) - f.d - spec.scale[1] * 0.3 + Math.sin(t * 1.3 + f.rot) * 0.08);
      _q.setFromEuler(_e.set(this.slosh.z * 0.4, f.a + f.rot, -this.slosh.x * 0.4));
      _s.set(spec.scale[0], spec.scale[1], spec.scale[2]).multiplyScalar(visible ? 1 : 0.001);
      _m.compose(_v.set(x, y, z), _q, _s);
      this.floaters.setMatrixAt(i, _m);
    });
    this.floaters.instanceMatrix.needsUpdate = true;
    if (this.floaters.instanceColor) this.floaters.instanceColor.needsUpdate = true;
  }

  updateIce(dt, t) {
    if (!this.ice.length) return;
    const cs = this.shape.cavityStart;
    for (const ice of this.ice) {
      const sw = this.swirl;
      if (sw > 0.01) {
        const a = sw * 3 * dt, c = Math.cos(a), s = Math.sin(a);
        [ice.x, ice.z] = [ice.x * c - ice.z * s, ice.x * s + ice.z * c];
      }
      const floor = cs + ice.size / 2 + ice.stack * 0.15;
      let y = Math.max(floor, this.surfaceAt(ice.x, ice.z) - ice.size * 0.38 + Math.sin(t * 1.6 + ice.ph) * 0.12);
      // 잔에 떨어지는 중
      const d = ice.drop;
      if (d) {
        if (d.wait > 0) {
          d.wait -= dt;
          ice.mesh.visible = false;
          continue;
        }
        ice.mesh.visible = true;
        d.vy -= 320 * dt;
        d.y += d.vy * dt;
        if (d.y <= y) {
          if (!d.bounced && d.vy < -20) {
            d.bounced = true;
            d.y = y;
            d.vy *= -0.22;
            this.env.audio?.iceDrop(0.8, this.panHint);
            if (this.fill > 0.1) this.splash(3, 0, 0, 0.4);
          } else {
            ice.drop = null;
          }
        }
        if (ice.drop) y = d.y;
      }
      ice.mesh.position.set(ice.x, y, ice.z);
      ice.mesh.rotation.set(this.slosh.z * 0.5 + Math.sin(t + ice.ph) * 0.05 + (d ? d.y * 0.15 : 0), ice.ph, -this.slosh.x * 0.5);
    }
  }

  emitSteam(dt) {
    if (!this.drink.hot || this.fill < 0.04 || !this.env.fx) return;
    this.steamAcc += dt * 7;
    const shape = this.shape;
    const lv = clamp(Math.floor(this.level), shape.cavityStart, shape.H - 1);
    const cr = shape.cavityR[lv] || 1;
    while (this.steamAcc >= 1) {
      this.steamAcc -= 1;
      const a = Math.random() * Math.PI * 2, r = Math.sqrt(Math.random()) * cr * 0.8;
      _v.set(Math.cos(a) * r, Math.max(this.level, shape.H - 1) + 0.5, Math.sin(a) * r);
      this.body.localToWorld(_v);
      this.env.fx.steam.spawn({
        x: _v.x, y: _v.y, z: _v.z, vx: rand(-1.5, 1.5), vy: rand(7, 11), vz: rand(-1.5, 1.5),
        wobble: 3.5, drag: 0.4, life: rand(2.2, 3.2), size: rand(1.3, 2.3), env: 'steam',
        color: Math.random() < 0.5 ? 0xfaf6f0 : 0xefe7dc,
      });
    }
  }

  // 너무 흔들면 넘친다
  checkSpill(dt) {
    this.spillCooldown -= dt;
    if (Math.abs(this.extraTilt.x) > 0.05 || this.busy || this.fill < 0.6) return;
    const lv = clamp(Math.floor(this.level), this.shape.cavityStart, this.shape.H - 1);
    const cr = this.shape.cavityR[lv] || 1;
    const S = this.slosh;
    const over = this.level + Math.hypot(S.x, S.z) * cr - this.shape.H;
    if (over > 0.8 && this.spillCooldown <= 0) {
      this.spillCooldown = 0.08;
      const m = Math.hypot(S.x, S.z) || 1;
      const c = Math.cos(this.yaw), s = Math.sin(this.yaw);
      const lx = S.x / m, lz = S.z / m;
      // 기울어진 쪽(수면이 높은 쪽)으로 흘러넘친다
      this.splash(2, lx * c + lz * s, -lx * s + lz * c, 0.35);
      this.fill = Math.max(0, this.fill - 0.006 * over);
    }
  }
}
