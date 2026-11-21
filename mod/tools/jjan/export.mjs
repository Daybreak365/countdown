// 짠(jjan) 웹 원본의 술·잔 데이터를 Minecraft 모드용 JSON 으로 내보낸다.
// 원본 data.js(술·잔 정의)와 voxel.js(buildShape: 잔 복셀·액체 칸)를 그대로 불러 쓰므로 값이 원본과 어긋나지 않는다.
//
//   node mod/tools/jjan/export.mjs [jjan 폴더]     (기본: ../../../jjan)
//   → mod/src/main/resources/assets/newyearcountdown/jjan/catalog.json
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const jjan = resolve(process.argv[2] ?? resolve(here, '../../../jjan'));
const js = (f) => pathToFileURL(resolve(jjan, 'public/js', f)).href;
const { GLASSES, CATEGORIES, DRINKS, PHRASES, resolveDrink } = await import(js('data.js'));
const { buildShape } = await import(js('voxel.js'));
const { buildVessel, buildShakerParts, FLOATERS } = await import(js('props.js'));

const HIDDEN = new Set(['dropshot']);
const glasses = GLASSES.map((g) => {
  const s = buildShape(g);
  const vox = [];
  const grid = s.grid;
  for (let y = grid.y0; y < grid.y0 + grid.sy; y++)
    for (let z = -grid.r; z < grid.r; z++)
      for (let x = -grid.r; x < grid.r; x++) {
        const c = grid.get(x, y, z);
        if (c >= 0) vox.push(x, y, z, c);
      }
  return {
    id: g.id, name: g.name, material: g.material, hidden: HIDDEN.has(g.id),
    H: s.H, R: s.R, cavityStart: s.cavityStart,
    cavity: s.cavityCols.map((cols) => cols.slice()),
    cavityR: s.cavityR.slice(), rimRing: g.layers[s.H - 1].rings[0].slice(),
    contactR: s.contactR, contactLow: s.contactLow, contactMid: s.contactMid, rimR: s.rimR,
    voxels: vox,
  };
});

const MIX = new Set(['shaker', 'mixglass', 'blender']);
function prepKind(d) {
  const steps = d.pour ?? [{ v: d.bottle }];
  if (steps.some((st) => MIX.has(st.v))) return 'mix';
  if (d.bomb) return 'bomb';
  if (d.flame) return 'flame';
  if (d.hot) return 'hot';
  if (d.carb) return 'carb';
  return 'pour';
}
// ── 따르는 그릇(병·캔·주전자 …): 원본 props.js 의 buildVessel 이 만든 겉면을 그대로 옮긴다 ──
// 원본은 그릇 모양 표를 내보내지 않으므로, 표식 색(몸통·라벨·뚜껑·비치는 술)으로 한 번, 기본 색으로 한 번 만들어
// 면마다 "어느 색 자리인지"와 원본의 복셀별 밝기 차이(jitter)를 읽어 낸다. 고정 색(띠, 주전자 등)은 그대로 굽는다.
const lin2srgb = (v) => Math.round(255 * Math.min(1, Math.max(0, v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055)));
const hexOf = (r, g, b) => (lin2srgb(r) << 16) | (lin2srgb(g) << 8) | lin2srgb(b);
const SENT = { color: 0xff0000, label: 0x00ff00, cap: 0x0000ff, liquid: 0xff00ff };
function facesOf(geo, defGeo) {
  const pos = geo.attributes.position.array, nor = geo.attributes.normal.array, col = geo.attributes.color.array;
  const dcol = defGeo ? defGeo.attributes.color.array : col;
  const faces = [];
  const sum = [[0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0]];
  for (let o = 0; o < pos.length; o += 18) {
    let x = Infinity, y = Infinity, z = Infinity;
    for (let q = 0; q < 18; q += 3) { x = Math.min(x, pos[o + q]); y = Math.min(y, pos[o + q + 1]); z = Math.min(z, pos[o + q + 2]); }
    const nx = nor[o], ny = nor[o + 1], nz = nor[o + 2];
    const dir = nx > 0 ? 0 : nx < 0 ? 1 : ny > 0 ? 2 : ny < 0 ? 3 : nz > 0 ? 4 : 5;
    // 면의 최소 꼭짓점 → 그 면이 속한 칸 (+쪽 면은 한 칸 안쪽)
    if (dir === 0) x -= 1; else if (dir === 2) y -= 1; else if (dir === 4) z -= 1;
    const r = col[o], g = col[o + 1], b = col[o + 2];
    let role = -1, s = 1;
    if (defGeo) {
      if (g === 0 && b === 0 && r > 0.5) { role = 0; s = r; }
      else if (r === 0 && b === 0 && g > 0.5) { role = 1; s = g; }
      else if (r === 0 && g === 0 && b > 0.5) { role = 2; s = b; }
      else if (g === 0 && r > 0.5 && b > 0.5) { role = 3; s = r; }
    }
    if (role < 0) {
      faces.push(Math.round(x), Math.round(y), Math.round(z), dir, hexOf(r, g, b));
    } else {
      faces.push(Math.round(x), Math.round(y), Math.round(z), dir, -1 - (role + 4 * Math.round(s * 1000)));
      sum[role][0] += dcol[o] / s; sum[role][1] += dcol[o + 1] / s; sum[role][2] += dcol[o + 2] / s; sum[role][3]++;
    }
  }
  const defaults = sum.map((t) => (t[3] ? hexOf(t[0] / t[3], t[1] / t[3], t[2] / t[3]) : -1));
  return { faces, defaults };
}
const vessels = [];
const vesselIndex = new Map();
function vesselOf(kind, noBand) {
  const key = `${kind}|${noBand ? 1 : 0}`;
  if (vesselIndex.has(key)) return vesselIndex.get(key);
  let out;
  if (kind === 'shaker') {
    // 셰이커는 통 + 거름망 뚜껑 (마개를 빼고 따른다). 원점이 따르는 입구
    const p = buildShakerParts();
    const tin = facesOf(p.tin, null), lid = facesOf(p.lid, null);
    out = { kind, glassy: false, metal: true, spout: false, angle: p.angle, faces: [...tin.faces, ...lid.faces], defaults: [-1, -1, -1, -1], top: 0 };
  } else {
    const v = buildVessel({ v: kind, ...SENT, noBand });
    const d = buildVessel({ v: kind, noBand });
    const f = facesOf(v.geo, d.geo);
    out = { kind: v.kind, glassy: v.glassy, metal: v.metal, spout: v.spout, angle: v.angle, faces: f.faces, defaults: f.defaults, top: 0 };
  }
  let lo = Infinity;
  for (let i = 1; i < out.faces.length; i += 5) lo = Math.min(lo, out.faces[i]);
  out.bottom = lo;
  vesselIndex.set(key, vessels.length);
  vessels.push(out);
  return vessels.length - 1;
}
const opt = (v) => (v === undefined || v === null ? -1 : v);
// 셰이커는 통·거름망 뚜껑·마개를 따로도 내보낸다 (재료를 넣을 땐 통만, 흔들 땐 셋 다, 따를 땐 마개만 뺀다)
function partOf(kind, geo) {
  const f = facesOf(geo, null);
  let lo = Infinity;
  for (let i = 1; i < f.faces.length; i += 5) lo = Math.min(lo, f.faces[i]);
  vessels.push({ kind, glassy: false, metal: true, spout: false, angle: 2.0, faces: f.faces, defaults: [-1, -1, -1, -1], top: 0, bottom: lo });
  return vessels.length - 1;
}
let shaker = null;
function shakerParts() {
  if (shaker) return shaker;
  const p = buildShakerParts();
  shaker = { tin: partOf('shaker_tin', p.tin), lid: partOf('shaker_lid', p.lid), cap: partOf('shaker_cap', p.cap), tinTop: p.tinTop };
  return shaker;
}
// 따르기 전에 그릇마다 하는 일 (원본 glass.js 의 prepKind, 새로 따를 때 기준)
const PREP_BY_VESSEL = { shaker: 'shake', mixglass: 'stir', blender: 'blend', champagne: 'cork', can: 'can', makgeolli: 'flip', soju: 'swirl' };
function prepOf(st) {
  let kind = st.prep !== undefined ? st.prep : PREP_BY_VESSEL[st.v];
  if (st.prep === undefined && st.v === 'soju' && st.color != null) kind = null;
  return !kind || kind === 'none' ? '' : kind;
}
const bottleFields = (st, stream) => ({
  kind: st.v ?? 'beer', vessel: vesselOf(st.v ?? 'beer', !!st.noBand),
  color: opt(st.color), label: opt(st.label), cap: opt(st.cap), liquid: opt(st.liquid ?? stream), stream: opt(stream),
});
// 따르는 순서 (원본 pourSteps): 그릇마다 재료 넣기·섞기·따르기
function pourOf(d) {
  const steps = d.pour ?? [{ v: d.bottle, color: d.bottleColor ?? d.canColor, prep: d.prep }];
  return steps.map((st) => {
    if (st.v === 'shaker') shakerParts();
    return {
      ...bottleFields(st, st.stream ?? d.color),
      prep: prepOf(st), to: opt(st.to), layer: !!st.layer, dur: opt(st.dur), carb: opt(st.carb),
      hot: st.hot === undefined ? -1 : st.hot ? 1 : 0, tint: opt(st.tint), sink: !!st.sink, sinkFrom: opt(st.sinkFrom),
      build: (st.build ?? []).map((ing) => ({ ...bottleFields(ing, ing.stream ?? 0xffffff), dash: !!ing.dash })),
    };
  });
}
function bottleOf(d) {
  const st = (d.pour ?? [{ v: d.bottle, color: d.bottleColor ?? d.canColor }])[0] ?? {};
  const kind = st.v ?? d.bottle ?? 'beer';
  return {
    kind, vessel: vesselOf(kind, !!st.noBand),
    color: opt(st.color ?? d.bottleColor ?? d.canColor), label: opt(st.label), cap: opt(st.cap),
    liquid: opt(st.liquid ?? st.stream ?? d.color), stream: st.stream ?? d.color,
  };
}
function drinkFields(d) {
  return {
    color: d.color, op: d.op ?? 1, fill: d.fill, abv: d.abv ?? 0,
    foam: d.foam ? { h: d.foam.h, color: d.foam.color } : null,
    carb: d.carb ?? 0, ice: d.ice ?? 0, hot: !!d.hot, flame: !!d.flame, layered: !!d.layered,
    gradient: d.gradient ?? null, bottle: bottleOf(d), prep: prepKind(d),
    // 겉모습: 장식, 떠다니는 것, 얼음 모양, 기포 종류, 폭탄주에 빠뜨린 잔
    garnish: d.garnish ?? [], floaters: d.cloudy ? 'cloudy' : d.floaters ?? '', cloudy: !!d.cloudy,
    iceShape: d.iceShape ?? 'cube', bubble: d.bubble ?? '', bubbleColor: opt(d.bubbleColor),
    bomb: d.bomb?.type ?? '', bombShot: opt(d.bomb?.shot),
    pour: pourOf(d), after: d.after ?? '',
  };
}
const drinks = DRINKS.map((d) => ({
  id: d.id, cat: d.cat, name: d.name, desc: d.desc, glass: d.glass, shot: !!d.shot,
  ...drinkFields(d),
  variants: (d.variants ?? []).map((v) => ({ id: v.id, name: v.name, desc: v.desc, ...drinkFields(resolveDrink(d.id, v.id)) })),
}));

// 검증: 명세서 표(기본 브랜드·추천 잔)와 같은 제공량이 나오는지
const capacity = (glassId, fill) => {
  const g = glasses.find((x) => x.id === glassId);
  const top = g.cavityStart + (g.H - g.cavityStart) * fill;
  let n = 0;
  for (let y = g.cavityStart; y < Math.floor(top); y++) n += (g.cavity[y]?.length ?? 0) / 2;
  return n;
};
for (const id of ['lager', 'soju', 'red', 'makgeolli', 'highball', 'cola']) {
  const d = drinks.find((x) => x.id === id);
  console.log(`${id.padEnd(10)} ${d.glass.padEnd(9)} ${capacity(d.glass, d.fill)}ml  ${d.abv}%`);
}

// ── 안주: 접시와 하나씩 집어 먹는 조각. 원본 anju.js 의 상자들을 그대로 뽑는다 ─────────────
const { ANJU, pairAnju } = await import(js('anju.js'));
const { BoxBuilder } = await import(js('voxel.js'));
// BoxBuilder 는 색을 three.js 작업 색공간으로 담는다. 회색 하나를 넣어 보고 sRGB 로 되돌리는 법을 정한다
const probe = new BoxBuilder().box(0, 0, 0, 1, 1, 1, 0x808080, 0).col[0];
const toSrgb = Math.abs(probe - 128 / 255) < 0.01 ? (c) => c : (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
const r3 = (v) => Math.round(v * 1000) / 1000;
function boxesOf(fn) {
  const b = new BoxBuilder();
  fn(b);
  if (b.pos.length % 108) throw new Error('안주 상자에 빠진 면이 있다');
  let list = [];
  for (let i = 0; i < b.pos.length; i += 108) {   // 상자 하나 = 6면 × 꼭짓점 6
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    for (let k = 0; k < 108; k++) {
      lo[k % 3] = Math.min(lo[k % 3], b.pos[i + k]);
      hi[k % 3] = Math.max(hi[k % 3], b.pos[i + k]);
    }
    const ch = [0, 1, 2].map((k) => Math.max(0, Math.min(255, Math.round(toSrgb(b.col[i + k]) * 255))));
    list.push({ lo: lo.map(r3), hi: hi.map(r3), ch });
  }
  // 색이 거의 같은 이웃 상자는 합친다 (접시 원판 같은 1복셀 격자가 수백 개라서). 먼저 x 로, 다음 z 로
  const near = (p, q) => p.ch.every((c, k) => Math.abs(c - q.ch[k]) <= 10);
  for (const ax of [0, 2]) {
    const others = [0, 1, 2].filter((k) => k !== ax);
    const same = (p, q) => others.every((k) => p.lo[k] === q.lo[k] && p.hi[k] === q.hi[k]);
    list.sort((p, q) => others.map((k) => p.lo[k] - q.lo[k] || p.hi[k] - q.hi[k]).find((v) => v) || p.lo[ax] - q.lo[ax]);
    const out = [];
    for (const c of list) {
      const last = out[out.length - 1];
      if (last && same(last, c) && Math.abs(last.hi[ax] - c.lo[ax]) < 1e-6 && near(last, c)) last.hi[ax] = c.hi[ax];
      else out.push({ lo: c.lo.slice(), hi: c.hi.slice(), ch: c.ch });
    }
    list = out;
  }
  return list.flatMap((c) => [...c.lo, ...c.hi, (c.ch[0] << 16) | (c.ch[1] << 8) | c.ch[2]]);
}
const anju = ANJU.map((a) => ({ id: a.id, name: a.name, desc: a.desc, hot: !!a.hot, plate: boxesOf(a.plate), pieces: a.pieces.map((fn) => boxesOf(fn)) }));
const pairing = Object.fromEntries(DRINKS.map((d) => [d.id, ANJU.findIndex((a) => a.id === pairAnju(d))]));
console.log('anju', anju.map((a) => `${a.id}:${a.plate.length / 7}+${a.pieces.reduce((n, p) => n + p.length / 7, 0)}`).join(' '));

const out = { version: 4, categories: CATEGORIES, phrases: PHRASES, glasses, drinks, shaker: shakerParts(), vessels, floaters: FLOATERS, anju, pairing };
const file = resolve(here, '../../src/main/resources/assets/newyearcountdown/jjan/catalog.json');
mkdirSync(dirname(file), { recursive: true });
writeFileSync(file, JSON.stringify(out));
const kinds = {};
for (const d of drinks) for (const l of [d, ...d.variants]) for (const st of l.pour) kinds[st.prep || 'pour'] = (kinds[st.prep || 'pour'] ?? 0) + 1;
console.log('steps', JSON.stringify(kinds));
console.log(`vessels ${vessels.length} (${vessels.map((v) => v.kind).join(', ')})`);
console.log(`drinks ${drinks.length}, categories ${CATEGORIES.length}, glasses ${glasses.filter((g) => !g.hidden).length} (+${glasses.filter((g) => g.hidden).length} hidden) -> ${file}`);
