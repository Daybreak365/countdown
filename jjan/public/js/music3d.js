import * as THREE from 'three';
import { BoxBuilder } from './voxel.js';

// 노래를 트는 기물. 가게마다 다르다: 피아니스트, 카세트 라디오, 라이브 밴드, LP 플레이어, 오케스트리온, 컴퓨터.
// 누르면 신청곡 창이 열리고, 노래가 나오는 동안엔 연주하고 돌고 반짝인다.
// 기물의 앞(손님 쪽)은 +z. 원점은 바닥.

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const _box = new THREE.Box3();

function mesh(b, mat, cast = true) {
  const m = new THREE.Mesh(b.geometry(), mat);
  m.castShadow = cast;
  m.receiveShadow = true;
  return m;
}

// 원기둥 비슷한 계단식 원판 (세로로 선 북, 판 등)
function discY(b, cx, y, cz, r, h, color, step = 1, j = 0.03) {
  for (let z = -r; z < r; z += step) for (let x = -r; x < r; x += step) {
    if (Math.hypot(x + step / 2, z + step / 2) < r) b.box(cx + x, y, cz + z, step, h, step, typeof color === 'function' ? color(x, z) : color, j);
  }
}
// 앞을 보는 원판 (z쪽으로 얇은)
function discZ(b, cx, cy, z, r, d, color, step = 1, j = 0.03) {
  for (let y = -r; y < r; y += step) for (let x = -r; x < r; x += step) {
    const dd = Math.hypot(x + step / 2, y + step / 2);
    if (dd < r) b.box(cx + x, cy + y, z, step, step, d, typeof color === 'function' ? color(dd) : color, j);
  }
}

// 사람 하나 (연주자). 몸통·머리·두 팔을 따로 움직인다
function musician(mat, look) {
  const g = new THREE.Group();
  const { shirt, pants, skin = 0xf0c49c, hair = 0x2a1d16, sit = false, shoe = 0x1e1a18 } = look;
  const legs = new BoxBuilder();
  if (sit) {
    // 앉은 다리: 허벅지는 앞으로, 정강이는 아래로
    for (const s of [-1, 1]) {
      legs.box(s * 3.5 - 3, 22, 0, 6.5, 6, 16, pants, 0.03);
      legs.box(s * 3.5 - 3, 2, 12, 6.5, 22, 6, pants, 0.03);
      legs.box(s * 3.5 - 3.2, 0, 11, 7, 3, 10, shoe, 0.02);
    }
  } else {
    for (const s of [-1, 1]) {
      legs.box(s * 3.5 - 3, 3, -3, 6.5, 40, 6.5, pants, 0.03);
      legs.box(s * 3.5 - 3.2, 0, -3, 7, 3, 10, shoe, 0.02);
    }
  }
  g.add(mesh(legs, mat));
  const hipY = sit ? 28 : 43;
  const torso = new THREE.Group();
  torso.position.y = hipY;
  g.add(torso);
  const tb = new BoxBuilder();
  tb.box(-8, 0, -5, 16, 26, 10, shirt, 0.03);
  if (look.collar) tb.box(-3, 22, 4.6, 6, 4, 1, look.collar, 0.02);
  if (look.tie) tb.box(-1, 12, 5.1, 2, 12, 0.8, look.tie, 0.02);
  tb.box(-2.5, 26, -2.5, 5, 3, 5, skin, 0.02);
  torso.add(mesh(tb, mat));
  const head = new THREE.Group();
  head.position.y = 29;
  torso.add(head);
  const hb = new BoxBuilder();
  hb.box(-6, 0, -5.5, 12, 12, 11, skin, 0.02);
  hb.box(-6.4, 8, -6, 12.8, 5, 12, hair, 0.05);
  hb.box(-6.4, 2, -6, 12.8, 7, 3, hair, 0.05);
  if (look.hat) { hb.box(-6.8, 11, -6.4, 13.6, 4, 12.8, look.hat, 0.04); hb.box(-6.8, 11, 5, 13.6, 1.4, 4, look.hat, 0.03); }
  hb.box(-3.6, 6, 5.5, 2, 2, 0.6, 0x2a1f1a, 0);
  hb.box(1.6, 6, 5.5, 2, 2, 0.6, 0x2a1f1a, 0);
  head.add(mesh(hb, mat));
  const arm = (side) => {
    const a = new THREE.Group();
    a.position.set(side * 9.5, 23, 0);
    torso.add(a);
    const ab = new BoxBuilder();
    ab.box(-2.4, -12, -2.4, 4.8, 13, 4.8, shirt, 0.03);
    ab.box(-2.2, -22, -2.2, 4.4, 11, 4.4, look.sleeve ?? skin, 0.03);
    ab.box(-2.4, -25, -2.4, 4.8, 4, 4.8, skin, 0.02);
    a.add(mesh(ab, mat));
    return a;
  };
  return { g, torso, head, armL: arm(1), armR: arm(-1) };
}

class Device {
  constructor(kind, mat) {
    this.kind = kind;
    this.mat = mat;
    this.root = new THREE.Group();
    this.act = 0; // 0 쉼 → 1 연주
    this.mats = [];
  }
  own(m) { this.mats.push(m); return m; }
  // 월드 좌표 상자 (누르기 판정, 카메라 맞추기)
  bounds(out = _box) {
    this.root.updateWorldMatrix(true, true);
    return out.setFromObject(this.root, false);
  }
  update(dt, t, playing) {
    this.act += ((playing ? 1 : 0) - this.act) * Math.min(1, dt * 4);
    this.animate(dt, t, this.act);
  }
  animate() {}
  dispose() {
    this.root.traverse((o) => { if (o.geometry) o.geometry.dispose(); });
    for (const m of this.mats) { m.map?.dispose(); m.dispose(); }
    this.root.removeFromParent();
  }
}

// ── 피아니스트와 업라이트 피아노 ─────────────────────────────
class Piano extends Device {
  constructor(mat) {
    super('piano', mat);
    const b = new BoxBuilder();
    const wood = 0x2a1712, wood2 = 0x3a2219;
    b.box(-30, 0, -14, 60, 6, 22, 0x1e110d, 0.02);
    b.box(-30, 6, -14, 60, 48, 18, wood, 0.02);
    b.box(-31, 54, -15, 62, 3, 20, wood2, 0.02);
    // 건반 선반, 흰건반, 검은건반
    b.box(-28, 30, 4, 56, 3, 10, wood2, 0.02);
    for (let i = 0; i < 26; i++) b.box(-27 + i * 2.08, 33, 5, 1.8, 1.2, 8.5, 0xf3eee2, 0.01);
    for (let i = 0; i < 25; i++) if (![2, 6, 9, 13, 16, 20, 23].includes(i % 28)) b.box(-25.6 + i * 2.08, 34.2, 5, 1.1, 1.2, 4.8, 0x141010, 0);
    b.box(-28, 34, 3, 56, 6, 1.5, wood, 0.02);
    // 보면대와 악보, 다리, 페달
    b.box(-14, 40, 2.5, 28, 12, 1.2, wood2, 0.02);
    b.box(-10, 41, 3.6, 9, 11, 0.4, 0xf2ead6, 0.02);
    b.box(1, 41, 3.6, 9, 11, 0.4, 0xefe6d0, 0.02);
    for (const x of [-27, 23]) b.box(x, 6, 4, 4, 24, 4, wood2, 0.02);
    for (const x of [-4, 1]) b.box(x, 2, 6, 3, 1.2, 4, 0xc9a24a, 0);
    // 의자
    b.box(-12, 18, 26, 24, 4, 12, wood2, 0.02);
    b.box(-11, 22, 27, 22, 2, 10, 0x6b1f22, 0.03);
    for (const [x, z] of [[-11, 27], [8, 27], [-11, 35], [8, 35]]) b.box(x, 0, z, 3, 18, 3, wood, 0.02);
    // 위에 촛대
    b.box(16, 57, -8, 6, 1.2, 6, 0xc9a24a, 0.02);
    b.box(18, 58, -6, 2, 7, 2, 0xf3e6c8, 0.02);
    // 비스듬히 돌려 놓아 연주하는 옆모습이 보이게
    const inner = new THREE.Group();
    inner.rotation.y = 0.65;
    this.root.add(inner);
    inner.add(mesh(b, mat));
    const p = musician(mat, { shirt: 0x1d1b22, pants: 0x1d1b22, collar: 0xf3eee4, tie: 0x7a1c2b, hair: 0x5a4030, sit: true, sleeve: 0x1d1b22 });
    p.g.position.set(0, 0, 30);
    p.g.rotation.y = Math.PI;
    inner.add(p.g);
    this.p = p;
    this.size = 60;
  }
  animate(dt, t, k) {
    const { torso, head, armL, armR } = this.p;
    const beat = t * 7.2;
    // 건반 쪽(사람 기준 앞, -z 쪽으로 돌려 앉아 있으니 로컬 +z)으로 팔을 뻗고, 연주하면 손이 오르내리며 좌우로 간다
    for (const [a, s, ph] of [[armL, 1, 0], [armR, -1, 1.7]]) {
      a.rotation.x = -1.15 + k * Math.sin(beat + ph) * 0.12;
      a.rotation.z = s * (0.18 + k * Math.sin(t * 1.3 + ph) * 0.14);
    }
    torso.rotation.z = k * Math.sin(t * 1.6) * 0.06;
    torso.rotation.x = 0.12 + k * Math.sin(t * 2.4) * 0.03;
    head.rotation.z = k * Math.sin(t * 1.6 + 0.6) * 0.12;
    head.rotation.x = -0.05 + k * Math.sin(beat * 0.5) * 0.05;
  }
}

// ── 카세트 라디오 (포장마차) ─────────────────────────────────
class Radio extends Device {
  constructor(mat) {
    super('radio', mat);
    const b = new BoxBuilder();
    // 맥주 상자 두 개를 쌓은 받침
    const crate = (y, c, c2) => {
      b.box(-24, y, -16, 48, 24, 32, c, 0.03);
      for (let x = -20; x < 20; x += 8) b.box(x, y + 6, 15.7, 5, 12, 0.6, c2, 0.02);
      b.box(-24, y + 21, -16, 48, 3, 32, c2, 0.02);
    };
    crate(0, 0x2f8f4e, 0x24703c);
    crate(24, 0xe0b030, 0xb88c22);
    // 라디오 몸통
    const y0 = 48;
    b.box(-26, y0, -7, 52, 28, 14, 0xb7babd, 0.02);
    b.box(-25, y0 + 1, 6.6, 50, 26, 0.8, 0x2b2b2e, 0.02);
    // 카세트 칸, 버튼
    b.box(-8, y0 + 4, 7, 16, 11, 0.6, 0x1b1b1d, 0.01);
    b.box(-6, y0 + 7, 7.3, 12, 5, 0.4, 0x6f5a3a, 0.02);
    for (let i = 0; i < 5; i++) b.box(-7.5 + i * 3.2, y0 + 28, -3, 2.4, 1.6, 4, i === 1 ? 0xc0392b : 0x3a3a3d, 0.02);
    // 손잡이, 안테나
    b.box(-18, y0 + 28, -1.5, 3, 7, 3, 0x2b2b2e, 0.02);
    b.box(15, y0 + 28, -1.5, 3, 7, 3, 0x2b2b2e, 0.02);
    b.box(-18, y0 + 34, -1.5, 36, 3, 3, 0x2b2b2e, 0.02);
    for (let i = 0; i < 9; i++) b.box(20 + i * 1.6, y0 + 30 + i * 3.2, -5, 1, 3.4, 1, 0xd0d4d8, 0);
    this.root.add(mesh(b, mat));
    // 스피커 둘: 소리에 맞춰 들썩인다
    this.cones = [-17, 17].map((x) => {
      const cb = new BoxBuilder();
      discZ(cb, 0, 0, 0, 7.5, 0.8, (d) => (d > 6.5 ? 0x9a9da0 : d > 2.2 ? 0x1c1c1e : 0x5a5a5e), 1, 0.02);
      const m = mesh(cb, mat, false);
      m.position.set(x, y0 + 12, 7.2);
      this.root.add(m);
      return m;
    });
    // 주파수 눈금 (켜지면 주황빛)
    this.dialMat = this.own(new THREE.MeshBasicMaterial({ color: 0x3a2a1a, toneMapped: false }));
    const dial = new THREE.Mesh(new THREE.BoxGeometry(14, 3.2, 0.5), this.dialMat);
    dial.position.set(0, y0 + 20.5, 7.3);
    this.root.add(dial);
    this.needle = new THREE.Mesh(new THREE.BoxGeometry(0.6, 3.6, 0.6), this.own(new THREE.MeshBasicMaterial({ color: 0xc0392b })));
    this.needle.position.set(-3, y0 + 20.5, 7.7);
    this.root.add(this.needle);
    this.size = 84;
  }
  animate(dt, t, k) {
    const thump = Math.max(0, Math.sin(t * 7.4)) ** 3;
    this.cones.forEach((c, i) => { c.scale.z = 1 + k * (thump * 2.6 + Math.sin(t * 23 + i) * 0.4); c.position.z = 7.2 + k * thump * 0.5; });
    this.dialMat.color.setHex(k > 0.1 ? 0xffa64a : 0x3a2a1a).multiplyScalar(0.25 + k * 0.75);
    this.needle.position.x = -3 + k * 2 + Math.sin(t * 0.3) * 0.2 * k;
  }
}

// ── 라이브 밴드 (호프집) ────────────────────────────────────
class Band extends Device {
  constructor(mat) {
    super('band', mat);
    const b = new BoxBuilder();
    // 무대
    b.box(-42, 0, -34, 84, 12, 68, 0x2e211a, 0.03);
    b.box(-42, 12, -34, 84, 1, 68, 0x3b2b22, 0.04);
    b.box(-42, 2, 33, 84, 9, 1.4, 0x1c1410, 0.02);
    // 앰프 둘
    for (const [x, c] of [[-34, 0x1d1d20], [22, 0x1d1d20]]) {
      b.box(x, 13, -32, 14, 20, 11, c, 0.02);
      b.box(x + 1, 15, -21.4, 12, 13, 0.6, 0x3a3530, 0.05);
      b.box(x + 1, 29, -21.4, 12, 2.6, 0.6, 0xc9a24a, 0.02);
    }
    // 드럼: 큰북, 작은북, 탐, 심벌 받침
    discZ(b, 0, 24, -10, 10, 9, (d) => (d > 9 ? 0xb03a2e : 0xeee6d6), 1, 0.02);
    for (const [x, z, r] of [[-12, -14, 5], [11, -14, 4.5]]) {
      discY(b, x, 36, z, r, 5, (xx, zz) => (Math.hypot(xx, zz) > r - 1 ? 0xb03a2e : 0xeee6d6), 1, 0.02);
      b.box(x - 0.6, 13, z - 0.6, 1.2, 23, 1.2, 0x8a8d90, 0);
    }
    for (const x of [-20, 19]) b.box(x - 0.5, 13, -18.5, 1, 32, 1, 0x8a8d90, 0);
    // 드럼 의자
    b.box(-4, 13, -34, 8, 22, 7, 0x2a2a2d, 0.02);
    // 마이크 스탠드
    b.box(-0.6, 13, 18, 1.2, 36, 1.2, 0x2a2a2d, 0);
    b.box(-1.4, 49, 17, 2.8, 4, 2.8, 0x3a3a3d, 0.02);
    b.box(-4, 13, 15, 8, 1.2, 8, 0x2a2a2d, 0);
    this.root.add(mesh(b, mat));
    // 심벌 (흔들린다)
    this.cymbals = [[-20, -18, 45], [19, -18, 43]].map(([x, z, y]) => {
      const cb = new BoxBuilder();
      discY(cb, 0, 0, 0, 6, 0.6, 0xd9b44a, 1, 0.03);
      const m = mesh(cb, mat, false);
      m.position.set(x, y + 1, z);
      this.root.add(m);
      return m;
    });
    // 드러머 (앉아서), 기타, 베이스
    const drummer = musician(mat, { shirt: 0x2a2a2d, pants: 0x3a3e4a, hair: 0x1c1410, sit: true, sleeve: 0xf0c49c });
    // 의자 위(높이 35)에 허벅지가 오게
    drummer.g.position.set(0, 13, -30);
    this.root.add(drummer.g);
    const gtr = musician(mat, { shirt: 0x8a2a24, pants: 0x2a2f3a, hair: 0x3a2618 });
    gtr.g.position.set(-24, 13, 6);
    gtr.g.rotation.y = 0.35;
    this.root.add(gtr.g);
    const bass = musician(mat, { shirt: 0x2b4a6e, pants: 0x22252c, hair: 0x6b4a2a, hat: 0x1d1d20 });
    bass.g.position.set(24, 13, 4);
    bass.g.rotation.y = -0.35;
    this.root.add(bass.g);
    // 기타 몸통 (몸통에 붙인다)
    const guitar = (p, body, neck) => {
      const gb = new BoxBuilder();
      gb.box(-6, 4, 5.4, 11, 9, 3, body, 0.03);
      gb.box(-4, 13, 5.4, 7, 4, 3, body, 0.03);
      gb.box(4, 10, 6.2, 18, 2, 1.6, neck, 0.02);
      gb.box(21, 9, 6.2, 4, 4, 1.6, 0x1c1410, 0.02);
      gb.box(-2, 7, 8.5, 4, 3, 0.4, 0x1c1410, 0);
      const m = mesh(gb, mat);
      m.rotation.z = 0.35;
      p.torso.add(m);
    };
    guitar(gtr, 0xc0392b, 0x6b4a2a);
    guitar(bass, 0x1d2a44, 0x4a3420);
    this.players = { drummer, gtr, bass };
    this.size = 100;
  }
  animate(dt, t, k) {
    const { drummer, gtr, bass } = this.players;
    const beat = t * Math.PI * 2 * 2.1; // 126bpm쯤
    drummer.armL.rotation.x = -0.9 - k * Math.max(0, Math.sin(beat)) * 0.7;
    drummer.armR.rotation.x = -0.9 - k * Math.max(0, Math.sin(beat + Math.PI)) * 0.7;
    drummer.armL.rotation.z = 0.35;
    drummer.armR.rotation.z = -0.35;
    drummer.head.rotation.x = k * Math.sin(beat) * 0.12;
    // 기타: 오른팔로 줄을 긁고, 왼팔은 넥을 잡는다
    for (const [p, ph] of [[gtr, 0], [bass, 1.1]]) {
      // 오른팔은 몸통 앞 기타 줄로, 왼팔은 넥 끝으로
      p.armR.rotation.x = -0.42 + k * Math.sin(beat * (p === gtr ? 2 : 1) + ph) * 0.22;
      p.armR.rotation.z = 0.48;
      p.armL.rotation.x = -0.92;
      p.armL.rotation.z = 0.71;
      p.head.rotation.x = k * Math.max(0, Math.sin(beat + ph)) * 0.22;
      p.torso.rotation.z = k * Math.sin(beat * 0.5 + ph) * 0.05;
      p.g.position.y = 13 + k * Math.max(0, Math.sin(beat + ph)) * 0.8;
    }
    this.cymbals.forEach((c, i) => { c.rotation.x = k * Math.sin(t * 9 + i * 2) * 0.12; c.rotation.z = k * Math.cos(t * 7 + i) * 0.1; });
  }
}

// ── LP 플레이어 (와인바) ────────────────────────────────────
class Turntable extends Device {
  constructor(mat) {
    super('lp', mat);
    const b = new BoxBuilder();
    const walnut = 0x4a2c1c, walnut2 = 0x5b3622;
    // 장식장: 칸마다 LP 판
    b.box(-34, 0, -16, 68, 40, 30, walnut, 0.02);
    b.box(-34, 40, -16, 68, 2.5, 31, walnut2, 0.02);
    const sleeves = [0xc0392b, 0x2f4a6e, 0xe0b030, 0x2f6b4a, 0xe9e2d3, 0x6b2a5a, 0x1c1c1e, 0xd9773a];
    for (let i = 0; i < 22; i++) b.box(-31 + i * 2.9, 4, 12, 2.4, 30 - (i % 3) * 2, 2, sleeves[i % sleeves.length], 0.03);
    b.box(-34, 0, 13.5, 68, 4, 1.2, walnut2, 0.02);
    // 턴테이블 받침
    b.box(-17, 42.5, -13, 34, 6, 26, 0x6b4229, 0.02);
    b.box(9, 48.5, -10, 5, 2, 5, 0x9a9da0, 0.02);
    b.box(-15, 48.5, 9, 4, 1.2, 2, 0xc9a24a, 0);
    // 기대 세운 판 두 장
    b.box(-30, 42.5, -14, 12, 12, 1.4, 0x2f4a6e, 0.03);
    b.box(-28, 42.5, -12, 12, 12, 1.4, 0xe0b030, 0.03);
    // 스피커 둘
    for (const x of [-56, 38]) {
      b.box(x, 0, -14, 18, 66, 18, walnut, 0.02);
      discZ(b, x + 9, 22, 4, 7, 1, (d) => (d > 6 ? 0x3a2418 : d > 2 ? 0x1c1712 : 0x5a5048), 1, 0.02);
      discZ(b, x + 9, 46, 4, 3.5, 1, (d) => (d > 2.8 ? 0x3a2418 : 0xc9a24a), 1, 0.02);
    }
    this.root.add(mesh(b, mat));
    // 돌아가는 판
    const pb = new BoxBuilder();
    discY(pb, 0, 0, 0, 11, 1.2, (x, z) => {
      const d = Math.hypot(x, z);
      if (d < 3.4) return x > 0 && z > 0 ? 0xf3e6c8 : 0xc0392b;
      return Math.floor(d) % 2 ? 0x141214 : 0x1f1c1f;
    }, 1, 0);
    this.platter = mesh(pb, mat, false);
    this.platter.position.set(-3, 48.5, 0);
    this.root.add(this.platter);
    // 바늘 팔
    const ab = new BoxBuilder();
    ab.box(-0.5, 0, -0.5, 1, 1, 15, 0xd0d4d8, 0);
    ab.box(-1, -0.6, 14, 2, 1.6, 3, 0x2a2a2d, 0);
    this.arm = mesh(ab, mat, false);
    this.arm.position.set(11.5, 51, -8);
    this.root.add(this.arm);
    this.cones = [-47, 47].map((x) => {
      const cb = new BoxBuilder();
      discZ(cb, 0, 0, 0, 5.6, 0.8, (d) => (d > 2 ? 0x24201c : 0x6a6058), 1, 0);
      const m = mesh(cb, mat, false);
      m.position.set(x, 22, 4.9);
      this.root.add(m);
      return m;
    });
    this.spin = 0;
    this.size = 66;
    this.width = 116;
  }
  animate(dt, t, k) {
    this.spin += dt * k * 3.5;
    this.platter.rotation.y = -this.spin;
    // 재생하면 바늘이 판 위로
    this.arm.rotation.y = -0.15 - k * 0.42;
    const thump = Math.max(0, Math.sin(t * 6.2)) ** 3;
    this.cones.forEach((c) => { c.scale.z = 1 + k * thump * 2.2; });
  }
}

// ── 오케스트리온 (크리스마스 산장) ────────────────────────────
class Orchestrion extends Device {
  constructor(mat) {
    super('orchestrion', mat);
    const b = new BoxBuilder();
    const wood = 0x5a2a1c, wood2 = 0x6e3622, gold = 0xc9a24a;
    b.box(-32, 0, -16, 64, 12, 30, wood, 0.02);
    // 뒤판과 앞 틀 (창 두 개는 속이 들여다보이게 비워 둔다)
    b.box(-30, 12, -15, 60, 86, 9, wood2, 0.02);
    b.box(-30, 12, -6, 3, 86, 17, wood2, 0.02);
    b.box(27, 12, -6, 3, 86, 17, wood2, 0.02);
    b.box(-27, 12, -6, 54, 3, 17, wood2, 0.02);
    b.box(-27, 50, -6, 54, 5, 17, wood2, 0.02);
    b.box(-27, 94, -6, 54, 4, 17, wood2, 0.02);
    // 위 장식 (계단식 지붕)
    for (let i = 0; i < 5; i++) b.box(-32 + i * 3, 98 + i * 3, -16, 64 - i * 6, 3, 30, i % 2 ? wood : wood2, 0.02);
    b.box(-4, 113, -6, 8, 7, 8, gold, 0.02);
    // 금테
    for (const y of [12, 52, 96]) b.box(-31, y, 11, 62, 1.6, 1.2, gold, 0);
    for (const x of [-31, 29.4]) b.box(x, 12, 11, 1.6, 86, 1.2, gold, 0);
    // 아래 창: 북과 트라이앵글, 위 창: 파이프 (안쪽 벽은 짙게)
    b.box(-27, 15, -6.4, 54, 35, 0.6, 0x1f120d, 0);
    b.box(-27, 55, -6.4, 54, 39, 0.6, 0x2a1810, 0);
    b.box(-27, 15, -6, 54, 1, 16, 0x3a2016, 0);
    b.box(-27, 55, -6, 54, 1, 16, 0x3a2016, 0);
    discZ(b, -9, 32, -2, 11, 6, (d) => (d > 10 ? 0xb03a2e : 0xeee6d6), 1, 0.02);
    for (let i = 0; i < 8; i++) b.box(10 + i * 0.9, 22 + i * 1.8, 2, 1, 1, 1, gold, 0);
    for (let i = 0; i < 8; i++) b.box(17 - i * 0.9, 22 + i * 1.8, 2, 1, 1, 1, gold, 0);
    b.box(10, 22, 2, 8, 1, 1, gold, 0);
    // 앞 다리와 바퀴 장식
    for (const x of [-30, 26]) b.box(x, 0, 10, 4, 3, 4, gold, 0);
    this.root.add(mesh(b, mat));
    // 파이프: 연주하면 차례로 반짝인다
    this.pipes = [];
    const n = 11;
    for (let i = 0; i < n; i++) {
      const h = 14 + Math.round(Math.abs(i - (n - 1) / 2) * -2.4 + 18);
      const m = new THREE.MeshStandardMaterial({ color: gold, metalness: 0.7, roughness: 0.3, emissive: 0xffc860, emissiveIntensity: 0 });
      this.own(m);
      const p = new THREE.Mesh(new THREE.BoxGeometry(3, h, 3), m);
      p.position.set(-22.5 + i * 4.5, 58 + h / 2, -2);
      this.root.add(p);
      this.pipes.push(p);
    }
    // 북채
    const sb = new BoxBuilder();
    sb.box(-0.6, -12, -0.6, 1.2, 12, 1.2, 0x8a6a40, 0);
    sb.box(-1.6, -14, -1.6, 3.2, 3.2, 3.2, 0xeee6d6, 0);
    this.beater = mesh(sb, mat, false);
    this.beater.position.set(4, 46, 4);
    this.root.add(this.beater);
    // 돌아가는 종이 롤
    const rb = new BoxBuilder();
    for (let a = 0; a < 12; a++) {
      const ang = (a / 12) * Math.PI * 2;
      rb.box(-20, Math.sin(ang) * 3.4 - 1, Math.cos(ang) * 3.4 - 1, 40, 2, 2, a % 3 ? 0xe9dcc0 : 0x6b5a40, 0);
    }
    this.roll = mesh(rb, mat, false);
    this.roll.position.set(0, 6, 15);
    this.root.add(this.roll);
    this.size = 120;
  }
  animate(dt, t, k) {
    const beat = t * 2 * Math.PI * 1.8;
    this.pipes.forEach((p, i) => {
      const on = Math.max(0, Math.sin(beat * 0.5 - i * 0.7));
      p.material.emissiveIntensity = k * on * on * 0.9;
      p.scale.y = 1 + k * on * 0.03;
    });
    this.beater.rotation.z = -0.4 - k * Math.max(0, Math.sin(beat)) * 0.5;
    this.roll.rotation.x -= dt * k * 1.4;
  }
}

// ── 컴퓨터 (다락방) ─────────────────────────────────────────
class Computer extends Device {
  constructor(mat) {
    super('pc', mat);
    const b = new BoxBuilder();
    const desk = 0x2a2a2e;
    b.box(-36, 68, -18, 72, 4, 34, 0x8a6440, 0.02);
    for (const x of [-34, 30]) b.box(x, 0, -16, 4, 68, 30, desk, 0.02);
    // 모니터 받침, 키보드, 마우스, 스피커
    b.box(-3, 72, -12, 6, 10, 4, 0x1c1c1e, 0.02);
    b.box(-8, 72, -14, 16, 1.4, 10, 0x1c1c1e, 0.02);
    b.box(-14, 72, 2, 28, 1.6, 9, 0x1c1c1e, 0.02);
    for (let i = 0; i < 12; i++) for (let j = 0; j < 3; j++) b.box(-13 + i * 2.2, 73.6, 3 + j * 2.6, 1.8, 0.8, 2, 0x3a3a3e, 0);
    b.box(17, 72, 4, 4, 1.6, 6, 0x1c1c1e, 0.02);
    b.box(19.5, 72, -14, 2, 1, 14, 0x2a2a2e, 0);
    for (const x of [-32, 24]) {
      b.box(x, 72, -12, 8, 14, 8, 0x1c1c1e, 0.02);
      discZ(b, x + 4, 78, -3.9, 3, 0.6, (d) => (d > 2.4 ? 0x2a2a2e : 0x55555a), 1, 0);
    }
    // 본체
    b.box(38, 0, -14, 16, 40, 30, 0x1c1c1e, 0.02);
    b.box(39, 2, 16, 14, 36, 0.6, 0x2a2a30, 0.02);
    this.root.add(mesh(b, mat));
    // 의자: 책상에서 빼서 옆으로 돌려 둔다 (앞에서 화면이 보이게)
    const cb = new BoxBuilder();
    cb.box(-12, 26, -11, 24, 5, 22, 0x1c1c1e, 0.02);
    cb.box(-12, 31, 7, 24, 34, 5, 0x1c1c1e, 0.02);
    cb.box(-8, 33, 11.5, 16, 26, 1, 0xb02a30, 0.02);
    cb.box(-2, 6, -2, 4, 20, 4, 0x3a3a3e, 0);
    cb.box(-12, 4, -12, 24, 3, 24, 0x3a3a3e, 0);
    const chair = mesh(cb, mat);
    chair.position.set(-46, 0, 24);
    chair.rotation.y = -1.2;
    this.root.add(chair);
    // 모니터: 작은 캔버스에 그린다 (노래 나오면 막대가 춤춘다)
    const c = document.createElement('canvas');
    c.width = 64; c.height = 36;
    this.cv = c;
    this.ctx = c.getContext('2d');
    this.tex = new THREE.CanvasTexture(c);
    this.tex.magFilter = THREE.NearestFilter;
    this.tex.minFilter = THREE.NearestFilter;
    this.tex.colorSpace = THREE.SRGBColorSpace;
    const screen = new THREE.Mesh(new THREE.PlaneGeometry(44, 24.75), this.own(new THREE.MeshBasicMaterial({ map: this.tex, toneMapped: false })));
    screen.position.set(0, 96, -10.4);
    this.root.add(screen);
    const fb = new BoxBuilder();
    fb.box(-24, 82, -12, 48, 28, 1.4, 0x111113, 0.01);
    this.root.add(mesh(fb, mat));
    // RGB 띠
    this.rgb = this.own(new THREE.MeshBasicMaterial({ color: 0xff3a6a, toneMapped: false }));
    for (const [x, y, z, w, h, d] of [[38.5, 38.5, 16.5, 15, 1, 0.5], [-14, 73.7, 10.6, 28, 0.5, 0.5], [-36, 67.5, 15.6, 72, 0.6, 0.6]]) {
      const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), this.rgb);
      m.position.set(x + w / 2, y, z);
      this.root.add(m);
    }
    this.drawT = 0;
    this.size = 112;
    this.draw(0, 0);
  }
  draw(t, k) {
    const g = this.ctx, W = 64, H = 36;
    g.fillStyle = '#141a2e';
    g.fillRect(0, 0, W, H);
    if (k < 0.05) {
      // 바탕화면: 아이콘 몇 개와 아래 작업 표시줄
      g.fillStyle = '#23305a';
      g.fillRect(0, 0, W, H);
      for (let i = 0; i < 4; i++) { g.fillStyle = ['#e0b030', '#4aa3df', '#c0392b', '#7ac74f'][i]; g.fillRect(3, 3 + i * 7, 4, 4); }
      g.fillStyle = '#0d1226';
      g.fillRect(0, 32, W, 4);
      return;
    }
    // 재생기 화면: 곡 막대, 진행 표시, 막대 그래프
    g.fillStyle = '#e9e2d3';
    g.fillRect(4, 3, 30, 2);
    g.fillStyle = '#7a8bb8';
    g.fillRect(4, 7, 18, 1);
    const n = 14;
    for (let i = 0; i < n; i++) {
      const v = Math.abs(Math.sin(t * (2.2 + i * 0.37) + i * 1.3)) * 0.6 + Math.abs(Math.sin(t * 5.1 + i)) * 0.4;
      const h = Math.max(1, Math.round(v * 18 * k));
      const hue = (i / n) * 300 + t * 40;
      g.fillStyle = `hsl(${hue % 360} 80% 60%)`;
      g.fillRect(4 + i * 4, 30 - h, 3, h);
    }
    g.fillStyle = '#3a4570';
    g.fillRect(4, 32, 56, 1);
    g.fillStyle = '#e0b030';
    g.fillRect(4, 32, Math.round(((t * 0.02) % 1) * 56), 1);
  }
  animate(dt, t, k) {
    this.drawT += dt;
    if (this.drawT > 1 / 15) {
      this.drawT = 0;
      this.draw(t, k);
      this.tex.needsUpdate = true;
    }
    this.rgb.color.setHSL((t * 0.08) % 1, 0.85, 0.35 + 0.25 * k);
  }
}

// ── 다다미 단 위 샤미센 연주자 (이자카야) ─────────────────────────
class Shamisen extends Device {
  constructor(mat) {
    super('shamisen', mat);
    const b = new BoxBuilder();
    // 다다미 단과 방석, 옆에 작은 초롱
    b.box(-34, 0, -26, 68, 12, 52, 0x6a4a2a, 0.02);
    for (let x = -32, i = 0; x < 32; x += 16, i++) b.box(x, 12, -24, 15.4, 1.4, 48, i % 2 ? 0xb8b070 : 0xc0b878, 0.03);
    b.box(-34, 12, -26, 68, 1.6, 2, 0x2a2a30, 0.02);
    b.box(-10, 13.4, -4, 20, 3, 18, 0x7a2a3a, 0.04);
    b.box(22, 13.4, -18, 8, 1, 8, 0x2a1a10, 0.02);
    b.box(24, 14.4, -16, 4, 14, 4, 0xd8302a, 0.03);
    b.box(23.4, 28.4, -16.6, 5.2, 1.4, 5.2, 0x2a1a10, 0.02);
    this.root.add(mesh(b, mat));
    // 꿇어앉은 연주자: 기모노 색 웃옷
    const p = musician(mat, { shirt: 0x2a3550, pants: 0x2a3550, collar: 0xf4f0e8, hair: 0x1a1414, sit: true, sleeve: 0x2a3550 });
    p.g.position.set(0, 13.4, 2);
    this.root.add(p.g);
    // 샤미센: 네모난 몸통과 긴 목 (몸통 앞에 비스듬히)
    const sb = new BoxBuilder();
    sb.box(-5, -5, -1.6, 10, 10, 3.2, 0xe8dcc0, 0.02);
    sb.box(-5.4, -5.4, -1.8, 10.8, 1, 3.6, 0x3a2414, 0.02);
    sb.box(-5.4, 4.4, -1.8, 10.8, 1, 3.6, 0x3a2414, 0.02);
    sb.box(-0.8, 5, -0.8, 1.6, 30, 1.6, 0x2a1a10, 0.02);
    sb.box(-1.4, 34, -1.2, 2.8, 4, 2.4, 0x2a1a10, 0.02);
    for (const x of [-0.5, 0, 0.5]) sb.box(x - 0.1, -3, 1.7, 0.2, 38, 0.2, 0xf4f0e0, 0);
    this.inst = mesh(sb, mat);
    this.inst.position.set(-2, 18, 10);
    this.inst.rotation.z = 0.9;
    p.torso.add(this.inst);
    this.inst.position.set(-1, 8, 7);
    this.p = p;
    this.size = 70;
  }
  animate(dt, t, k) {
    const { torso, head, armL, armR } = this.p;
    // 왼손은 목을 잡고, 오른손 채(바치)로 줄을 튕긴다
    armL.rotation.x = -1.0;
    armL.rotation.z = -0.55;
    const pluck = Math.max(0, Math.sin(t * 6.2)) ** 4;
    armR.rotation.x = -0.75 - k * pluck * 0.35;
    armR.rotation.z = 0.35;
    torso.rotation.x = 0.06 + k * Math.sin(t * 1.4) * 0.03;
    head.rotation.z = k * Math.sin(t * 1.1) * 0.08;
    head.rotation.x = 0.1 + k * Math.sin(t * 3.1) * 0.04;
  }
}

// ── DJ 부스 (루프탑) ───────────────────────────────────────
class DJBooth extends Device {
  constructor(mat) {
    super('dj', mat);
    const b = new BoxBuilder();
    b.box(-40, 0, -14, 80, 56, 28, 0x1c1c22, 0.02);
    b.box(-41, 56, -15, 82, 3, 30, 0x2a2a32, 0.02);
    // 턴테이블 둘과 믹서
    for (const x of [-26, 14]) {
      b.box(x, 59, -11, 18, 2, 18, 0x3a3a44, 0.02);
      b.box(x + 12, 61, -9, 2, 3, 10, 0x9a9da0, 0);
    }
    b.box(-6, 59, -10, 12, 3, 16, 0x2a2a32, 0.02);
    for (let i = 0; i < 4; i++) b.box(-4.6 + i * 3, 62, -6, 1.4, 1.4, 1.4, 0xd8d8d2, 0);
    this.root.add(mesh(b, mat));
    // 앞판 불빛 (박자에 맞춰 흐른다)
    this.ledMat = this.own(new THREE.MeshBasicMaterial({ color: 0xff5ad0, toneMapped: false }));
    this.leds = [];
    for (let i = 0; i < 12; i++) {
      const m = new THREE.Mesh(new THREE.BoxGeometry(5, 4, 0.6), this.ledMat.clone());
      this.mats.push(m.material);
      m.position.set(-33 + i * 6, 30, 14.4);
      this.root.add(m);
      this.leds.push(m);
    }
    this.plates = [-17, 23].map((x) => {
      const pb = new BoxBuilder();
      discY(pb, 0, 0, 0, 7.5, 0.8, (dx, dz) => (Math.hypot(dx, dz) < 2 ? 0xc0392b : 0x141418), 1, 0.02);
      const m = mesh(pb, mat, false);
      m.position.set(x, 61, -2);
      this.root.add(m);
      return m;
    });
    // DJ: 헤드폰 낀 사람
    const p = musician(mat, { shirt: 0x2a2a3a, pants: 0x1c1c22, hair: 0xd8c060, hat: 0x1c1c22 });
    p.g.position.set(0, 0, -30);
    this.root.add(p.g);
    const hp = new BoxBuilder();
    hp.box(-7.4, 4, -2, 2, 6, 5, 0x1c1c22, 0.02);
    hp.box(5.4, 4, -2, 2, 6, 5, 0x1c1c22, 0.02);
    hp.box(-7, 13, -1, 14, 1.6, 2, 0x1c1c22, 0.02);
    p.head.add(mesh(hp, mat));
    this.p = p;
    this.size = 80;
  }
  animate(dt, t, k) {
    const beat = t * 8.4;
    this.plates.forEach((m) => { m.rotation.y -= dt * (0.4 + k * 5); });
    this.leds.forEach((m, i) => {
      const on = k * (0.5 + 0.5 * Math.sin(beat * 0.5 - i * 0.7));
      m.material.color.setHSL(((t * 0.08 + i / 12) % 1), 0.9, 0.12 + on * 0.5);
    });
    const { torso, head, armL, armR } = this.p;
    const bob = Math.max(0, Math.sin(beat)) * k;
    torso.position.y = 43 - bob * 1.2;
    head.rotation.x = 0.25 + bob * 0.25;
    armR.rotation.x = -1.2 + k * Math.sin(beat * 0.5) * 0.2;
    armR.rotation.z = 0.3;
    // 왼손은 가끔 헤드폰을 귀에 댄다
    const cue = k * (Math.sin(t * 0.4) > 0.3 ? 1 : 0);
    armL.rotation.x = -1.1 - cue * 1.4;
    armL.rotation.z = -0.25 - cue * 0.5;
  }
}

// ── 블루투스 스피커 (편의점 앞) ─────────────────────────────
class Speaker extends Device {
  constructor(mat) {
    super('speaker', mat);
    const b = new BoxBuilder();
    // 생수 묶음 위에 둔 원통형 스피커
    for (const [x, z] of [[-14, -10], [0, -10], [14, -10], [-14, 4], [0, 4], [14, 4]]) {
      b.box(x - 6.5, 0, z - 6.5, 13, 30, 13, 0xcfe6f0, 0.03);
      b.box(x - 6.6, 10, z - 6.6, 13.2, 6, 13.2, 0x3a8ae0, 0.02);
    }
    b.box(-21, 30, -17, 42, 1, 28, 0xdfeef4, 0.02);
    discY(b, 0, 31, -3, 8, 1, 0x2a2a30, 1, 0.02);
    this.root.add(mesh(b, mat));
    this.body = new THREE.Group();
    this.body.position.set(0, 32, -3);
    this.root.add(this.body);
    const sb = new BoxBuilder();
    discY(sb, 0, 0, 0, 7, 22, (dx, dz) => (Math.hypot(dx, dz) > 6 ? 0x2a2a30 : 0x3a3a44), 1, 0.02);
    sb.box(-2, 22, -2, 4, 2, 4, 0x1c1c22, 0);
    this.body.add(mesh(sb, mat));
    this.ringMat = this.own(new THREE.MeshBasicMaterial({ color: 0x2a3a4a, toneMapped: false }));
    const ring = new BoxBuilder();
    for (let a = 0; a < 20; a++) {
      const ang = (a / 20) * Math.PI * 2;
      ring.box(Math.cos(ang) * 6.8 - 0.8, 2, Math.sin(ang) * 6.8 - 0.8, 1.6, 1.4, 1.6, 0xffffff, 0);
    }
    this.ring = new THREE.Mesh(ring.geometry(), this.ringMat);
    this.body.add(this.ring);
    // 옆에 다 마신 캔 몇 개
    const cb = new BoxBuilder();
    for (const [x, z, c] of [[26, 8, 0xc0392b], [32, 2, 0x2f6fb0], [-28, 10, 0xe0b030]]) cb.box(x - 2.4, 0, z - 2.4, 4.8, 9, 4.8, c, 0.03);
    this.root.add(mesh(cb, mat));
    this.size = 60;
  }
  animate(dt, t, k) {
    const thump = Math.max(0, Math.sin(t * 7.4)) ** 3;
    this.body.scale.set(1 + k * thump * 0.06, 1 + k * thump * 0.03, 1 + k * thump * 0.06);
    this.ringMat.color.setHSL((t * 0.1) % 1, 0.8, 0.15 + k * (0.35 + thump * 0.2));
  }
}

const KINDS = { piano: Piano, radio: Radio, band: Band, lp: Turntable, orchestrion: Orchestrion, pc: Computer, shamisen: Shamisen, dj: DJBooth, speaker: Speaker };

export function makeDevice(kind, mat) {
  const C = KINDS[kind] || Piano;
  return new C(mat);
}
