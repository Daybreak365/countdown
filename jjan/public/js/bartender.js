import * as THREE from 'three';
import { BoxBuilder } from './voxel.js';
import { GlassEntity, SCALE } from './glass.js';
import { TABLE_Y, LAYOUT, BAR } from './room.js';
import { venueById } from './venues.js';
import { barkeepById, barkeepFits, DEFAULT_BARKEEP } from './barkeeps.js';

// 혼자 짠: 바 건너편 바텐더. 손님 잔부터 자기 잔까지 손으로 직접 만들어 따르고,
// 손님이 잔을 들면 자기 잔도 들어 마주 대 준다.

const C = {
  skin: 0xf0c49c,
  skinDark: 0xd9a77e,
  hair: 0x3a2618,
  shirt: 0xf3eee4,
  vest: 0x2f2a33,
  tie: 0xa8242f,
  pants: 0x2a2428,
  apron: 0x4a3426,
  shoe: 0x1f1a17,
  gold: 0xc9a24a,
  blush: 0xe89a8a,
};

// 가게마다 옷차림: 포차 사장님은 수건 머리띠, 소믈리에는 긴 앞치마, 산장지기는 산타 모자, 다락방 친구는 후드티와 비니
const STYLES = {
  bar: {},
  pocha: { shirt: 0xe9e4d8, cuff: 0xe9e4d8, vest: null, tie: null, apron: 0xa8322b, pants: 0x3a3a40, band: 0xf4f4f0 },
  hof: { shirt: 0x2b4a6e, cuff: 0x2b4a6e, collar: 0x23405f, vest: null, tie: null, apron: 0x24242a, pants: 0x2a2a30 },
  wine: { vest: 0x1a181d, tie: 0x1a181d, apron: 0x17161a, longApron: true, tastevin: true },
  xmas: { shirt: 0xa8242f, cuff: 0xf4f6fa, collar: 0xf4f6fa, vest: null, tie: null, apron: 0x2f5d3a, santa: true },
  attic: { shirt: 0x5b6170, cuff: 0x5b6170, vest: null, tie: null, apron: null, pants: 0x34465e, beanie: 0xd29b2e, hood: true },
  // 이자카야: 남색 작업복에 흰 머리띠, 긴 앞치마
  izakaya: { shirt: 0x2a3550, cuff: 0x2a3550, collar: 0xf4f0e8, vest: null, tie: null, apron: 0x1c2438, longApron: true, pants: 0x2a2a30, band: 0xf4f4f0, cross: 0xf4f0e8 },
  // 루프탑: 검은 셔츠, 금색 가는 넥타이
  rooftop: { shirt: 0x1c1c22, cuff: 0x1c1c22, collar: 0x1c1c22, vest: 0x2a2a32, tie: 0xc9a24a, gold: 0xc9a24a, apron: null, pants: 0x1c1c22 },
  // 편의점: 흰 셔츠에 초록 조끼, 이름표
  conv: { shirt: 0xf2f2ee, cuff: 0xf2f2ee, collar: 0xf2f2ee, vest: 0x2f8f6a, tie: null, gold: 0xf2f2ee, apron: null, pants: 0x3a3a44, nametag: 0xf4f4f0 },
};

const UP = new THREE.Vector3(0, 1, 0);
const _axX = new THREE.Vector3();
const _axY = new THREE.Vector3();
const _basis = new THREE.Matrix4();
const _a = new THREE.Vector3();
const _b = new THREE.Vector3();
const _p = new THREE.Vector3();
const _pole = new THREE.Vector3();
const _cuff = new THREE.Vector3();
const _hand = new THREE.Vector3();
const _seg = new THREE.Vector3();
const UNIT = new THREE.BoxGeometry(1, 1, 1);

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smooth = (t) => t * t * (3 - 2 * t);
const pick = (list) => list[Math.floor(Math.random() * list.length)];

const UPPER = 22, FORE = 21;
// 허리 높이: 윗몸(몸통, 머리, 팔)은 여기를 축으로 숙인다
const WAIST = 90;
// 팔이 뚫고 지나가면 안 되는 곳 (윗몸 기준 좌표). 팔 두께만큼 넓혀서 잰다
const ARM_R = 3.8;
const BLOCKERS = [
  { x0: -15.8, x1: 15.8, y0: 0, y1: 44, z0: -8.8, z1: 9.0 }, // 몸통 + 조끼
  { x0: -12, x1: 12, y0: 44, y1: 68, z0: -11, z1: 11.5 }, // 머리
  { x0: -15.6, x1: 15.6, y0: -46, y1: 0, z0: -8.8, z1: 9.4 }, // 허리 아래, 앞치마
];
const _u = new THREE.Vector3();
const _w = new THREE.Vector3();
const _e = new THREE.Vector3();
const _q2 = new THREE.Vector3();
const _bestPole = new THREE.Vector3();
const _pref = new THREE.Vector3();

// 선분이 몸 안으로 얼마나 들어가는지 (깊이의 합)
function penetration(a, b, r = ARM_R) {
  let sum = 0;
  for (let i = 1; i <= 6; i++) {
    const t = i / 6;
    const x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t, z = a.z + (b.z - a.z) * t;
    for (const k of BLOCKERS) {
      const dx = Math.min(x - (k.x0 - r), k.x1 + r - x);
      const dy = Math.min(y - (k.y0 - r), k.y1 + r - y);
      const dz = Math.min(z - (k.z0 - r), k.z1 + r - z);
      if (dx > 0 && dy > 0 && dz > 0) sum += Math.min(dx, dy, dz);
    }
  }
  return sum;
}

// 손이 몸 속으로 들어가면 몸 앞으로 꺼낸다
function pushOut(p, r = 3.4) {
  for (const k of BLOCKERS) {
    if (p.x > k.x0 - r && p.x < k.x1 + r && p.y > k.y0 - r && p.y < k.y1 + r && p.z > k.z0 - r && p.z < k.z1 + r) p.z = k.z1 + r;
  }
  return p;
}

export const BARTENDER_LINES = {
  hello: ['어서 오세요. 오늘 하루 어땠어요?', '어서 오세요! 한 잔 하고 가요.', '오셨어요? 자리 비워 뒀어요.'],
  newDrink: ['좋은 선택이에요.', '저도 같은 걸로 할게요.', '그거 제가 좋아하는 거예요.', '잘 고르셨네요.'],
  clink: ['짠!', '건배!', '캬, 좋다.', '오늘도 고생했어요.', '짠, 짠!'],
  reply: ['짠!', '저도 짠!', '오늘도 고생했어요.', '천천히 드세요.', '한 잔 더 드릴까요?', '안주도 좀 드세요.', '저는 늘 여기 있어요.', '좋은 밤이네요.'],
  refill: ['한 잔 더 따를게요.', '제 것도 채울게요.'],
};

export class Bartender {
  constructor(scene, env) {
    this.scene = scene;
    this.env = env;
    this.root = new THREE.Group();
    this.root.position.copy(LAYOUT.single.bartender);
    scene.add(this.root);
    this.mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.8 });
    this.flat = new Map();
    this.C = { ...C };
    this.style = 'bar';
    this.person = DEFAULT_BARKEEP;
    this.lines = { ...BARTENDER_LINES };
    this.buildBody();
    this.buildHead();
    this.arms = { right: this.buildArm(-1), left: this.buildArm(1) };
    this.buildProps();

    this.glass = null;
    this.gp = new THREE.Vector3(BAR_REST.x, TABLE_Y, BAR_REST.z);
    this.gv = new THREE.Vector3();
    this.happy = 0;
    this.blinkT = 2;
    this.sipT = -1;
    this.refillT = -1;
    this.lookX = 0;
    // 따라 줄 잔 차례
    this.queue = [];
    this.serving = null;
    this.handoff = undefined;
    // 술 꺼내 오는 곳 (바 상판 안쪽 아래)
    this.wellZ = BAR.z0 - 3;
    this.anchor = { body: this.head, topLocal: (out) => out.set(0, 27, 0) };
  }

  colorMat(hex) {
    if (!this.flat.has(hex)) this.flat.set(hex, new THREE.MeshStandardMaterial({ color: hex, roughness: 0.75 }));
    return this.flat.get(hex);
  }

  // 다리·앞치마는 root에, 몸통·머리·팔은 허리를 축으로 숙이는 upper에
  buildBody() {
    this.legs = new THREE.Mesh(this.legsGeo(), this.mat);
    this.legs.castShadow = true;
    this.root.add(this.legs);
    this.upper = new THREE.Group();
    this.upper.position.set(0, WAIST, 0);
    this.root.add(this.upper);
    this.body = new THREE.Mesh(this.bodyGeo(), this.mat);
    this.body.castShadow = true;
    this.upper.add(this.body);
  }

  legsGeo() {
    const C = this.C;
    const b = new BoxBuilder();
    // 다리, 구두
    b.cbox(-6, 4, 0, 9, 88, 11, C.pants, 0.03);
    b.cbox(6, 4, 0, 9, 88, 11, C.pants, 0.03);
    b.cbox(-6, 0, 2, 10, 4, 15, C.shoe, 0.02);
    b.cbox(6, 0, 2, 10, 4, 15, C.shoe, 0.02);
    // 산타는 털 달린 장화
    if (C.santaSuit) {
      for (const x of [-6, 6]) {
        b.cbox(x, 0, 1, 11, 20, 14, C.shoe, 0.03);
        b.cbox(x, 18, 0, 11.4, 4, 12.4, 0xf4f6fa, 0.05);
      }
    }
    // 앞치마 (소믈리에는 발목까지)
    if (C.apron) {
      if (C.longApron) b.box(-15, 14, 7.4, 30, 80, 1.2, C.apron, 0.04);
      else b.box(-15, 46, 7.4, 30, 48, 1.2, C.apron, 0.04);
      b.box(-15.5, 89, -8.5, 31, 4, 17, C.apron, 0.02);
      if (C.floral) {
        // 꽃무늬 앞치마
        for (let i = 0; i < 14; i++) {
          const x = -12 + (i % 4) * 7 + (Math.floor(i / 4) % 2) * 3.5, y = 50 + Math.floor(i / 4) * 11;
          b.box(x, y, 8.6, 2.6, 2.6, 0.4, i % 3 ? 0xf4f4f0 : 0xf0c040, 0);
          b.box(x + 0.8, y + 0.8, 8.9, 1, 1, 0.3, 0xe07a96, 0);
        }
      }
    } else {
      // 청바지 허리띠
      b.box(-15.5, 89, -8.5, 31, 4, 17, 0x3a2a1e, 0.02);
      b.box(-15.6, 46, -8.6, 31.2, 44, 17.2, C.pants, 0.03);
    }
    return b.geometry();
  }

  bodyGeo() {
    const C = this.C;
    const u = new BoxBuilder();
    const W = -WAIST;
    // 셔츠 몸통
    u.cbox(0, 92 + W, 0, 30, 40, 16, C.shirt, 0.02);
    // 조끼: 앞판 둘, 옆, 뒤
    if (C.vest) {
      u.box(-15.4, 94 + W, 8.05, 11, 33, 0.8, C.vest, 0.03);
      u.box(4.4, 94 + W, 8.05, 11, 33, 0.8, C.vest, 0.03);
      u.box(-15.6, 94 + W, -8.6, 31.2, 33, 1, C.vest, 0.03);
      u.box(-15.6, 94 + W, -8.6, 1, 33, 17, C.vest, 0.03);
      u.box(14.6, 94 + W, -8.6, 1, 33, 17, C.vest, 0.03);
      for (let i = 0; i < 3; i++) u.cbox(-3.3, 100 + i * 7 + W, 8.6, 1.4, 1.4, 0.6, C.gold, 0);
    } else if (C.apron && !C.santa) {
      // 앞치마 끈
      for (const x of [-8, 8]) u.cbox(x, 92 + W, 8.3, 2, 40, 0.6, C.apron, 0.02);
    }
    if (C.santaSuit) {
      // 산타 옷: 흰 털 단, 가운데 털 줄, 검은 허리띠와 금 버클
      u.box(-15.4, 92 + W, -8.4, 30.8, 4, 16.8, 0xf4f6fa, 0.05);
      u.box(-1.6, 96 + W, 8.05, 3.2, 34, 0.8, 0xf4f6fa, 0.05);
      u.box(-15.3, 99 + W, -8.3, 30.6, 4, 16.6, 0x141210, 0.02);
      u.cbox(0, 98.6 + W, 8.6, 6, 4.8, 0.8, 0xd9b04a, 0);
      u.cbox(0, 99.8 + W, 8.9, 3, 2.4, 0.4, 0x141210, 0);
    } else if (C.santa) {
      // 스웨터 무늬: 흰 눈송이 띠
      for (let x = -14; x < 14; x += 4) u.cbox(x + 2, 112 + W, 8.2, 2, 2, 0.4, 0xf4f6fa, 0);
      u.cbox(0, 92 + W, 8.15, 30, 3, 0.4, 0xf4f6fa, 0);
    }
    if (C.stripes) {
      // 줄무늬 셔츠 (앞뒤옆 둘레)
      for (let y = 95; y < 128; y += 5) u.box(-15.15, y + W, -8.15, 30.3, 1.8, 16.3, C.stripes, 0.02);
    }
    if (C.scarf) {
      u.cbox(0, 124 + W, 8.4, 10, 4, 1, C.scarf, 0.03);
      u.cbox(0, 119 + W, 8.6, 5, 5, 1, C.scarf, 0.03);
      u.cbox(0, 116 + W, 8.7, 2, 3, 1, C.scarf, 0.03);
    }
    if (C.number) {
      // 등번호 12 (앞에도)
      const px = (x, y, w, hh) => u.box(x, y + W, 8.05, w, hh, 0.7, 0xf4f4f0, 0);
      px(-7, 104, 2, 14);
      px(-2, 116, 7, 2); px(3, 110, 2, 8); px(-2, 110, 7, 2); px(-2, 104, 2, 8); px(-2, 104, 7, 2);
    }
    if (C.rgb) {
      // 게임 티셔츠: 빛나는 줄
      for (const [x, c] of [[-14.6, 0xff5aa8], [13.6, 0x5af0ff]]) u.box(x, 94 + W, 8.05, 1, 34, 0.7, c, 0);
      u.cbox(0, 112 + W, 8.4, 10, 6, 0.6, 0x5af0ff, 0);
      u.cbox(0, 113 + W, 8.7, 6, 4, 0.4, 0x1c1c22, 0);
    }
    if (C.hood) {
      // 후드 끈과 뒤로 접힌 후드
      for (const x of [-3, 3]) u.cbox(x, 116 + W, 8.2, 1, 10, 0.5, 0xe9e2d3, 0);
      u.cbox(0, 102 + W, 8.2, 16, 9, 0.5, 0x4f5462, 0.02);
      u.box(-12, 118 + W, -12.5, 24, 12, 5, 0x4f5462, 0.04);
    }
    if (C.nametag) {
      // 왼쪽 가슴 이름표
      u.box(5, 116 + W, 8.7, 8, 3.4, 0.5, C.nametag, 0);
      u.box(5.6, 117 + W, 9.1, 5, 0.8, 0.3, 0x2a2a30, 0);
    }
    if (C.cross) {
      // 소매를 묶은 끈 (어깨에서 엇갈려)
      for (let i = 0; i < 9; i++) {
        u.cbox(-12 + i * 3, 127 - i * 3.6 + W, 8.4, 3, 2, 0.5, C.cross, 0);
        u.cbox(12 - i * 3, 127 - i * 3.6 + W, 8.5, 3, 2, 0.5, C.cross, 0);
      }
    }
    // 깃, 나비넥타이, 목
    u.cbox(0, 129 + W, 0, 14, 3, 12, C.collar ?? C.shirt, 0.02);
    if (C.tie) {
      u.cbox(-3.2, 126.5 + W, 8.2, 5, 4, 1.4, C.tie, 0.02);
      u.cbox(3.2, 126.5 + W, 8.2, 5, 4, 1.4, C.tie, 0.02);
      u.cbox(0, 127 + W, 8.7, 2, 3, 1.4, C.tie === C.vest ? 0x111013 : 0x7d1a22, 0);
    }
    if (C.tastevin) {
      // 소믈리에 시음잔: 목걸이에 매단 은잔
      for (let i = 0; i < 7; i++) u.cbox(-4.5 + i * 1.5, 124 - Math.abs(i - 3) * 2 + W, 8.6, 0.6, 0.6, 0.4, 0xc9ccce, 0);
      u.cbox(0, 115 + W, 8.8, 4, 3, 1, 0xd2d6da, 0);
    }
    u.cbox(0, 131 + W, 0, 9, 4, 9, C.skin, 0.02);
    // 어깨: 둥글게 (위가 좁게)
    for (const side of [-1, 1]) {
      u.cbox(side * 17.4, 112 + W, 0, 5.4, 15, 13, C.shirt, 0.02);
      u.cbox(side * 16.6, 127 + W, 0, 5, 3, 11, C.shirt, 0.02);
    }
    return u.geometry();
  }

  // 가게를 옮기면 옷과 인사말이 바뀐다
  // person: 누가 서 있나 (barkeeps.js). 그 가게에서 못 고르는 사람이면 기본 바텐더
  setStyle(venue, person = this.person) {
    if (!barkeepFits(person, venue)) person = DEFAULT_BARKEEP;
    if (this.style === venue && this.person === person) return;
    this.style = venue;
    this.person = person;
    const P = barkeepById[person] ?? {};
    const look = P.look ?? {};
    const C2 = { ...C, ...(STYLES[venue] || {}), ...look, ...(P.outfit || {}) };
    // 제 모자가 있는 사람은 가게 모자(산타 모자, 비니, 수건)를 쓰지 않는다
    if (look.cap || look.beret || look.headset || look.horns) { C2.santaHat = false; C2.beanie = null; C2.band = null; }
    else C2.santaHat = !!C2.santa;
    this.C = C2;
    const v = venueById[venue];
    this.lines = { ...BARTENDER_LINES, hello: P.hello ?? v?.hello ?? BARTENDER_LINES.hello };
    // 수염이 있으면 입이 수염 앞으로 나온다 (마실 때 잔을 대는 자리도)
    this.mouth.position.z = C2.facial === 'santa' ? 14.4 : C2.facial === 'beard' ? 12.4 : 10.2;
    this.mouth.material = this.colorMat(C2.lips ?? 0x7a3a2a);
    for (const [m, geo] of [[this.legs, this.legsGeo()], [this.body, this.bodyGeo()], [this.headMesh, this.headGeo()]]) {
      m.geometry.dispose();
      m.geometry = geo;
    }
    for (const arm of [this.arms.left, this.arms.right]) {
      arm.upper.material = this.colorMat(this.C.shirt);
      arm.cuff.material = this.colorMat(this.C.cuff ?? 0xe6e0d4);
      arm.fore.material = this.colorMat(this.C.skin);
      arm.hand.material = this.colorMat(this.C.glove ?? this.C.skin);
    }
  }

  buildHead() {
    this.head = new THREE.Group();
    this.head.position.set(0, 134 - WAIST, 0);
    const headMesh = new THREE.Mesh(this.headGeo(), this.mat);
    headMesh.castShadow = true;
    this.headMesh = headMesh;
    this.head.add(headMesh);
    this.eyes = [-4.6, 4.6].map((x) => {
      const m = new THREE.Mesh(UNIT, this.colorMat(0x2a1f1a));
      m.scale.set(2.4, 3, 0.6);
      m.position.set(x, 11.5, 10.2);
      this.head.add(m);
      return m;
    });
    // 취하면 진해지는 볼
    const blushMat = new THREE.MeshBasicMaterial({ color: 0xe0503e, transparent: true, opacity: 0, depthWrite: false });
    this.blushes = [-7.7, 7.7].map((x) => {
      const m = new THREE.Mesh(UNIT, blushMat);
      m.position.set(x, 7.5, 10.35);
      m.visible = false;
      this.head.add(m);
      return m;
    });
    this.mouth = new THREE.Mesh(UNIT, this.colorMat(0x7a3a2a));
    this.mouth.position.set(0, 2.6, 10.2);
    this.head.add(this.mouth);
    this.upper.add(this.head);
  }

  headGeo() {
    const C = this.C;
    const h = new BoxBuilder();
    h.cbox(0, 0, 0, 22, 22, 20, C.skin, 0.02);
    this.hairGeo(h, C);
    // 귀, 코
    h.cbox(-11.7, 8, 0, 1.6, 5, 3.6, C.skinDark, 0.02);
    h.cbox(11.7, 8, 0, 1.6, 5, 3.6, C.skinDark, 0.02);
    h.cbox(0, 7.5, 10.7, 3, 4, 2, C.skinDark, 0.02);
    this.faceGeo(h, C);
    // 볼
    h.box(-9.2, 6.5, 10.05, 3, 2, 0.4, C.blush, 0);
    h.box(6.2, 6.5, 10.05, 3, 2, 0.4, C.blush, 0);
    if (C.band) {
      // 수건 머리띠, 뒤에 묶은 매듭
      h.box(-11.9, 15, -10.9, 23.8, 4.4, 21.8, C.band, 0.03);
      h.box(-3, 12, -13, 6, 6, 3, C.band, 0.04);
    }
    if (C.santaHat) {
      h.cbox(0, 19, -1, 24.4, 4, 22.4, 0xf4f6fa, 0.05);
      h.cbox(0, 23, -2, 21, 4, 19, 0xb3282e, 0.03);
      h.cbox(1.5, 27, -3.5, 16, 4, 15, 0xb3282e, 0.03);
      h.cbox(3.5, 31, -5.5, 11, 4, 10, 0xb3282e, 0.03);
      h.cbox(6, 34, -8, 6, 4, 6, 0xb3282e, 0.03);
      h.cbox(8.5, 33, -11, 6, 6, 6, 0xf4f6fa, 0.06);
    }
    if (C.beanie) {
      h.cbox(0, 15, -1, 23.8, 4, 22, 0xb98328, 0.03);
      h.cbox(0, 19, -1, 23.4, 6, 21.6, C.beanie, 0.04);
      h.cbox(0, 25, -1, 18, 2, 17, C.beanie, 0.04);
    }
    this.hatGeo(h, C);
    return h.geometry();
  }

  // 머리 모양
  hairGeo(h, C) {
    const H = C.hair, st = C.hairStyle ?? 'side';
    if (st === 'bald') {
      // 민머리: 옆과 뒤에만 짧게, 정수리엔 살짝 빛
      h.box(-11.6, 5, -10.8, 23.2, 9, 3, H, 0.05);
      h.box(-11.7, 6, -8, 2, 8, 9, H, 0.05);
      h.box(9.7, 6, -8, 2, 8, 9, H, 0.05);
      h.cbox(-3, 22, 1, 6, 0.4, 5, 0xffe2c4, 0);
    } else if (st === 'bob') {
      // 단발: 턱선까지 내려오는 옆머리와 일자 앞머리
      h.cbox(0, 17, -1, 23.4, 8, 21.8, H, 0.05);
      h.box(-11.8, 1, -11.2, 23.6, 18, 3.4, H, 0.05);
      h.box(-12.4, 1, -9, 2.6, 18, 15.6, H, 0.05);
      h.box(9.8, 1, -9, 2.6, 18, 15.6, H, 0.05);
      h.box(-11.8, 15.2, 9.4, 23.6, 4.6, 1.8, H, 0.05);
      h.box(-12.4, 1, 6, 3, 15, 4.2, H, 0.05);
      h.box(9.4, 1, 6, 3, 15, 4.2, H, 0.05);
    } else if (st === 'short') {
      h.cbox(0, 18, -1, 22.8, 5, 21, H, 0.05);
      h.box(-11.5, 8, -10.7, 23, 13, 2.6, H, 0.05);
      h.box(-11.6, 11, -8, 1.8, 9, 9, H, 0.05);
      h.box(9.8, 11, -8, 1.8, 9, 9, H, 0.05);
      h.box(-11.4, 18, 9.2, 22.8, 2.4, 1.4, H, 0.05);
    } else if (st === 'perm') {
      // 뽀글머리: 작은 덩어리를 겹겹이
      h.cbox(0, 16, -1, 24, 9, 23, H, 0.06);
      for (let i = 0; i < 26; i++) {
        const a = (i / 26) * Math.PI * 2, r = 11.5 + (i % 3);
        h.cbox(Math.cos(a) * r, 14 + (i % 4) * 2.4, -1 + Math.sin(a) * r * 0.95, 4, 4, 4, i % 2 ? H : 0x3a2a24, 0.06);
      }
      for (let i = 0; i < 9; i++) h.cbox(-8 + (i % 3) * 8, 24 + Math.floor(i / 3) * 0.6, -8 + Math.floor(i / 3) * 7, 5, 3, 5, H, 0.06);
      h.box(-11.8, 4, -11.6, 23.6, 13, 3.4, H, 0.06);
    } else if (st === 'bun') {
      // 쪽진 머리: 단정히 넘기고 뒤에 틀어 올린 쪽, 비녀
      h.cbox(0, 17, -1, 23, 7, 21.4, H, 0.04);
      h.box(-11.6, 6, -10.8, 23.2, 13, 3, H, 0.04);
      h.box(-11.7, 9, -8, 2, 10, 9, H, 0.04);
      h.box(9.7, 9, -8, 2, 10, 9, H, 0.04);
      // 이마 위를 덮어 넘긴 앞머리와 가운데 가르마
      h.box(-11.6, 17.5, 9.4, 23.2, 6.5, 1.6, H, 0.04);
      h.box(-0.6, 19.5, 10.95, 1.2, 4.5, 0.2, C.skin ?? 0xf3d4b4, 0);
      // 정수리 뒤쪽에 높이 틀어 올린 쪽 (앞에서도 머리 위로 보이게), 옆으로 꽂은 비녀
      h.cbox(0, 22, -5, 13, 6, 12, H, 0.05);
      h.cbox(0, 28, -5, 9, 2.4, 8, H, 0.05);
      h.cbox(0, 21, -5, 15, 1.2, 13, 0x6a2a4a, 0);
      h.cbox(1, 25.4, -5, 25, 1.6, 1.6, 0xd9b04a, 0);
      h.cbox(13.6, 24.8, -5, 2.8, 2.8, 2.8, 0x3aa07a, 0);
      h.cbox(-11.6, 25.2, -5, 1.8, 2, 1.8, 0xd9b04a, 0);
    } else if (st === 'slick') {
      // 뒤로 넘긴 머리
      h.cbox(0, 17, -1.5, 23, 8, 20.4, H, 0.03);
      h.box(-11.6, 6, -10.8, 23.2, 14, 3, H, 0.03);
      h.box(-11.7, 10, -8, 2, 9, 10, H, 0.03);
      h.box(9.7, 10, -8, 2, 9, 10, H, 0.03);
      h.box(-10, 21.5, 0, 20, 2, 9, H, 0.03);
    } else if (st === 'messy') {
      h.cbox(0, 17, -1, 23, 7, 21.4, H, 0.05);
      h.box(-11.6, 6, -10.8, 23.2, 13, 3, H, 0.05);
      for (const [x, z, y] of [[-8, 6, 24], [-2, 8, 25], [5, 6, 24.5], [9, -2, 23.5], [-6, -6, 24], [2, -8, 24]]) h.cbox(x, y, z, 4, 3, 4, H, 0.06);
      h.box(-11.6, 15.5, 9.4, 9, 4.5, 1.6, H, 0.05);
      h.box(2, 16.5, 9.4, 9, 3.5, 1.6, H, 0.05);
    } else {
      // 옆가르마, 뒤통수, 구레나룻
      h.cbox(0, 17, -1, 23, 7, 21.4, H, 0.05);
      h.box(-11.6, 4, -10.8, 23.2, 15, 3, H, 0.05);
      h.box(-11.7, 8, -8, 2, 11, 11, H, 0.05);
      h.box(9.7, 8, -8, 2, 11, 11, H, 0.05);
      h.box(-11.6, 16.5, 9.4, 15, 3.5, 1.6, H, 0.05);
      h.box(-11.6, 13, 9.4, 3, 4, 1.6, H, 0.05);
    }
  }

  // 수염, 눈썹, 입술, 안경 …
  faceGeo(h, C) {
    const H = C.hair, f = C.facial ?? 'mustache';
    const brow = f === 'santa' ? 0xf4f6fa : H;
    const bw = f === 'santa' || C.hairStyle === 'bald' ? 2 : 1.3;
    h.box(-7.2, 14.6, 10.1, 4.6, bw, 0.6, brow, 0.02);
    h.box(2.6, 14.6, 10.1, 4.6, bw, 0.6, brow, 0.02);
    if (f === 'mustache') {
      // 말린 콧수염
      h.box(-6.2, 5.2, 10.2, 5.4, 2, 1.4, H, 0.03);
      h.box(0.8, 5.2, 10.2, 5.4, 2, 1.4, H, 0.03);
      h.box(-7.8, 6.2, 10.2, 1.8, 1.8, 1.4, H, 0.03);
      h.box(6, 6.2, 10.2, 1.8, 1.8, 1.4, H, 0.03);
    } else if (f === 'stubble') {
      // 짧게 다듬은 턱수염 (얇아서 마실 때 걸리지 않는다)
      h.box(-9, -1.4, 10, 18, 2.4, 0.5, H, 0.08);
      h.box(-2.4, -1, 10.1, 4.8, 1.4, 0.5, H, 0.08);
      h.box(-5, 4.8, 10, 10, 1.2, 0.5, H, 0.06);
    } else if (f === 'beard') {
      // 덥수룩한 수염: 턱과 볼을 덮고 입만 열어 둔다
      h.box(-11.4, -4, 7.6, 22.8, 9, 4.6, H, 0.06);
      h.box(-11.8, -2, -2, 2.4, 12, 11, H, 0.06);
      h.box(9.4, -2, -2, 2.4, 12, 11, H, 0.06);
      h.box(-6.6, 4.6, 10.2, 13.2, 2.4, 2, H, 0.05);
      h.box(-11.4, 5, 9.6, 3.4, 5, 2.4, H, 0.06);
      h.box(8, 5, 9.6, 3.4, 5, 2.4, H, 0.06);
    } else if (f === 'santa') {
      // 산타 수염: 가슴까지 풍성하게
      const W2 = 0xf4f6fa, W3 = 0xe4e8ee;
      h.box(-12, -16, 7, 24, 22, 6, W2, 0.05);
      h.box(-9, -22, 7.6, 18, 6, 5, W3, 0.05);
      h.box(-5, -26, 8, 10, 4, 4, W2, 0.05);
      h.box(-12.4, -6, -3, 2.6, 16, 11, W2, 0.05);
      h.box(9.8, -6, -3, 2.6, 16, 11, W2, 0.05);
      h.box(-8.5, 3.6, 12.4, 7.6, 3.4, 2.4, W2, 0.04);
      h.box(0.9, 3.6, 12.4, 7.6, 3.4, 2.4, W2, 0.04);
      h.box(-10, 5, 11.6, 2.4, 2.4, 2, W3, 0.04);
      h.box(7.6, 5, 11.6, 2.4, 2.4, 2, W3, 0.04);
    }
    if (C.lips) h.box(-2.6, 1.8, 10.08, 5.2, 1.6, 0.4, C.lips, 0);
    if (C.shades) {
      // 선글라스
      for (const x of [-4.6, 4.6]) h.box(x - 3.4, 9.2, 10.5, 6.8, 4, 0.8, 0x141418, 0);
      h.box(-1.2, 12, 10.5, 2.4, 0.8, 0.8, 0x141418, 0);
      h.box(-11.6, 12, 4, 0.8, 0.8, 7, 0x141418, 0);
      h.box(10.8, 12, 4, 0.8, 0.8, 7, 0x141418, 0);
    }
    if (C.glasses) {
      for (const x of [-4.6, 4.6]) {
        h.box(x - 3.4, 9.2, 10.5, 6.8, 0.9, 0.8, 0x1c1c22, 0);
        h.box(x - 3.4, 14.2, 10.5, 6.8, 0.9, 0.8, 0x1c1c22, 0);
        h.box(x - 3.4, 9.2, 10.5, 0.9, 5.9, 0.8, 0x1c1c22, 0);
        h.box(x + 2.5, 9.2, 10.5, 0.9, 5.9, 0.8, 0x1c1c22, 0);
      }
      h.box(-1.2, 12.6, 10.5, 2.4, 0.8, 0.8, 0x1c1c22, 0);
    }
    if (C.monocle) {
      // 오른눈 외알 안경과 늘어진 금줄
      const G = 0xd9b04a;
      h.box(1.2, 8.6, 10.6, 7, 0.8, 0.6, G, 0);
      h.box(1.2, 14.6, 10.6, 7, 0.8, 0.6, G, 0);
      h.box(1.2, 8.6, 10.6, 0.8, 6.8, 0.6, G, 0);
      h.box(7.4, 8.6, 10.6, 0.8, 6.8, 0.6, G, 0);
      for (let i = 0; i < 6; i++) h.box(7.6 + i * 0.5, 8 - i * 1.6, 10.4 - i * 0.6, 0.6, 1.4, 0.6, G, 0);
    }
    if (C.facepaint) {
      // 볼에 태극 줄무늬
      for (const x of [-9.6, 6.6]) {
        h.box(x, 7.4, 10.08, 3, 1, 0.4, 0xc8242a, 0);
        h.box(x, 5.8, 10.08, 3, 1, 0.4, 0x2f5fb0, 0);
      }
    }
  }

  // 사람마다 쓰는 것: 거꾸로 쓴 모자, 베레모, 헤드셋, 뿔 머리띠
  hatGeo(h, C) {
    if (C.cap) {
      h.cbox(0, 18, -1, 24, 6, 22.6, C.cap, 0.04);
      h.cbox(0, 24, -1, 20, 2, 18.6, C.cap, 0.04);
      h.box(-7, 18, -19, 14, 1.6, 8, C.cap, 0.03);
      h.cbox(0, 26, -1, 2.4, 1, 2.4, 0xf4f4f0, 0);
      h.box(-3, 19, 10.5, 6, 4, 0.6, 0xf4f4f0, 0);
    }
    if (C.beret) {
      h.cbox(-1, 20, -1, 25, 3, 23, C.beret, 0.04);
      h.cbox(-2, 23, -1, 22, 3, 21, C.beret, 0.04);
      h.cbox(1, 26, -1, 2, 2, 2, C.beret, 0);
    }
    if (C.headset) {
      const K = 0x1c1c22;
      for (let i = 0; i < 11; i++) {
        const a = (i / 10) * Math.PI;
        h.cbox(-Math.cos(a) * 13.6, 11 + Math.sin(a) * 16.4, 0, 3, 3, 4.4, K, 0);
      }
      for (const s of [-1, 1]) {
        h.cbox(s * 13.6, 4, 0, 4, 12, 11, K, 0.02);
        h.cbox(s * 15.8, 6, 0, 0.8, 8, 7, 0x5af0ff, 0);
      }
      // 마이크는 입 옆 볼에 (입 앞에 두면 마실 때 잔에 닿는다)
      for (let i = 0; i < 6; i++) h.cbox(-12.4 + i * 0.7, 4 - i * 0.2, 2 + i * 1.4, 1, 1, 1.6, K, 0);
      h.cbox(-8, 2.8, 10.4, 2, 2, 2, 0xc0392b, 0);
    }
    if (C.horns) {
      // 붉은 악마 뿔 머리띠
      h.cbox(0, 19, -1, 24, 2, 22.4, 0x1c1c1e, 0);
      for (const s of [-1, 1]) {
        for (let k = 0; k < 5; k++) h.cbox(s * (6 + k * 0.8), 21 + k * 2, -1, 4 - k * 0.6, 2.2, 4 - k * 0.6, 0xe02020, 0);
      }
    }
  }

  buildArm(side) {
    const upper = new THREE.Mesh(UNIT, this.colorMat(this.C.shirt));
    const cuff = new THREE.Mesh(UNIT, this.colorMat(this.C.cuff ?? 0xe6e0d4));
    const fore = new THREE.Mesh(UNIT, this.colorMat(C.skin));
    const hand = new THREE.Mesh(UNIT, this.colorMat(C.skin));
    hand.scale.set(6.5, 7, 6.5);
    for (const m of [upper, cuff, fore, hand]) { m.castShadow = true; this.upper.add(m); }
    // 팔꿈치가 어느 쪽으로 굽었는지 기억해서 프레임마다 튀지 않게
    return { side, upper, cuff, fore, hand, shoulder: new THREE.Vector3(side * 19.4, 122.5 - WAIST, 0.6), elbow: new THREE.Vector3(), pole: new THREE.Vector3(side * 0.6, -0.8, -0.1).normalize() };
  }

  // 닦는 잔과 행주 (혼자 짠이 아닐 때)
  buildProps() {
    const g = new BoxBuilder();
    g.cbox(0, 0, 0, 7, 1.5, 7, 0xdcefee, 0);
    g.cbox(0, 1.5, 0, 7.4, 8, 7.4, 0xdcefee, 0);
    this.wipeGlass = new THREE.Mesh(g.geometry(), new THREE.MeshStandardMaterial({ vertexColors: true, transparent: true, opacity: 0.45, roughness: 0.05, envMap: this.env.envMap, depthWrite: false }));
    this.towel = new THREE.Mesh(UNIT, this.colorMat(0xf2ead8));
    this.towel.scale.set(6, 9, 4);
    this.upper.add(this.wipeGlass, this.towel);
  }

  // 어깨 → 손 두 마디 팔. 팔꿈치는 바깥 아래로 굽히되, 팔이 몸통·머리를 뚫지 않는 방향을 고른다
  setArm(arm, target) {
    const s = arm.shoulder;
    const hand = _hand.copy(target);
    // 오른손은 몸 가운데를 넘어 왼쪽으로, 왼손은 오른쪽으로 가지 않는다
    hand.x = arm.side < 0 ? Math.min(hand.x, 3) : Math.max(hand.x, -3);
    pushOut(hand);
    const dir = _seg.subVectors(hand, s);
    let dist = dir.length();
    const max = UPPER + FORE - 0.5;
    if (dist > max) { dir.setLength(max); hand.copy(s).add(dir); dist = max; }
    dist = Math.max(dist, Math.abs(UPPER - FORE) + 0.5);
    dir.normalize();
    const a = (UPPER * UPPER - FORE * FORE + dist * dist) / (2 * dist);
    const h = Math.sqrt(Math.max(0, UPPER * UPPER - a * a));
    // 어깨-손 축에 수직인 두 방향으로 팔꿈치 후보를 빙 돌려 본다
    _u.crossVectors(Math.abs(dir.y) < 0.9 ? UP : _w.set(1, 0, 0), dir).normalize();
    _w.crossVectors(dir, _u).normalize();
    _pref.set(arm.side * 0.75, -1, -0.2).normalize();
    let best = Infinity;
    for (let i = 0; i < 16; i++) {
      const ang = (i / 16) * Math.PI * 2;
      _pole.copy(_u).multiplyScalar(Math.cos(ang)).addScaledVector(_w, Math.sin(ang));
      _e.copy(s).addScaledVector(dir, a).addScaledVector(_pole, h);
      let score = penetration(s, _e) * 2.4 + penetration(_e, hand) * 2;
      score += 0.5 * (1 - _pole.dot(_pref)) + 0.35 * (1 - _pole.dot(arm.pole));
      if (score < best) { best = score; _bestPole.copy(_pole); }
    }
    // 부드럽게 따라간다
    arm.pole.lerp(_bestPole, 0.35);
    arm.pole.addScaledVector(dir, -arm.pole.dot(dir));
    if (arm.pole.lengthSq() < 1e-6) arm.pole.copy(_bestPole);
    arm.pole.normalize();
    arm.elbow.copy(s).addScaledVector(dir, a).addScaledVector(arm.pole, h);
    this.segment(arm.upper, s, arm.elbow, 7.5);
    _cuff.subVectors(hand, arm.elbow).setLength(3).add(arm.elbow);
    this.segment(arm.cuff, arm.elbow, _cuff, 7.8);
    this.segment(arm.fore, _cuff, hand, 6);
    arm.hand.position.copy(hand);
    arm.hand.quaternion.copy(arm.fore.quaternion);
  }

  // 팔 마디: 상자의 옆면이 늘 수평이 되게 돌린다 (비스듬히 돌면 마름모처럼 뾰족해 보인다)
  segment(mesh, from, to, w) {
    const d = _a.subVectors(to, from);
    const len = Math.max(0.1, d.length());
    mesh.position.addVectors(from, to).multiplyScalar(0.5);
    d.normalize();
    const x = _axX.crossVectors(UP, d);
    if (x.lengthSq() < 1e-4) x.set(1, 0, 0);
    x.normalize();
    const y = _axY.crossVectors(d, x);
    _basis.makeBasis(x, y, d);
    mesh.quaternion.setFromRotationMatrix(_basis);
    mesh.scale.set(w, w, len);
  }

  // 월드 → 바텐더 윗몸 기준 (허리를 숙이면 같이 돈다)
  toLocal(world, out) {
    this.root.updateMatrixWorld();
    return this.upper.worldToLocal(out.copy(world));
  }

  // ── 바텐더 잔 ─────────────────────────────────────────
  // 손님 잔(guest)을 먼저 만들어 따르고, 그다음 자기 잔. 병 하나로 끝나는 술은 같은 병(셰이커)으로 이어서 따른다.
  setDrink(drinkId, glassId, { greet = false, guest = null, variant = null } = {}) {
    this.cancelService();
    if (!this.glass) {
      this.glass = new GlassEntity(this.env, { drinkId, glassId, variant, yaw: 0 });
      this.glass.peerId = 'bartender';
      this.scene.add(this.glass.root);
      this.gp.set(BAR_REST.x, TABLE_Y, BAR_REST.z);
      this.gv.set(0, 0, 0);
      this.glass.root.position.copy(this.gp);
    } else {
      this.glass.set(drinkId, glassId, variant);
    }
    this.sipT = -1;
    this.refillT = -1;
    const one = this.glass.pourSteps().length === 1;
    if (guest) this.enqueue(guest, { fromEmpty: true, keepLast: one });
    this.enqueue(this.glass, { fromEmpty: true, skipPrep: !!guest && one, handoff: !!guest && one });
    const brand = this.glass.drink.brand;
    const lines = greet ? this.lines.hello : brand ? [`${brand}, 좋죠.`, `${brand}로 드릴게요.`, `${brand} 나왔습니다.`] : BARTENDER_LINES.newDrink;
    this.say(pick(lines), greet ? 400 : 900);
  }

  // 손님이 한 잔 더 달라고 할 때
  serveGuest(entity, { fromEmpty = false } = {}) {
    if (this.queue.some((j) => j.e === entity) || this.serving === entity) return;
    this.enqueue(entity, { fromEmpty });
  }

  enqueue(e, opts) {
    e.waitingServe = true;
    // 손님 잔은 바텐더 앞으로 가져와서 따른다 (다 따르면 손님 앞으로 밀어 준다)
    if (e !== this.glass) e.serveSpot = SERVE_SPOT;
    if (opts.fromEmpty) {
      // 차례를 기다리는 빈 잔: 술도 장식도 아직
      e.fill = 0;
      e.hideGarnish();
    }
    this.queue.push({ e, opts });
    if (!this.serving && this.handoff === undefined) this.handoff = null;
  }

  // 다음 잔 (잔의 연출 갱신 중에 끝나므로 바텐더 갱신 때 넘어간다)
  nextService(handIn) {
    const job = this.queue.shift();
    this.serving = job ? job.e : null;
    if (!job) return;
    const { opts } = job;
    job.e.serve({
      fromEmpty: opts.fromEmpty, srv: this, skipPrep: opts.skipPrep, keepLast: opts.keepLast && this.queue.length > 0,
      handIn: opts.handoff ? handIn : null,
      onServed: (out) => {
        job.e.serveSpot = null;
        if (this.serving === job.e) this.handoff = out;
      },
    });
  }

  cancelService() {
    for (const j of this.queue) { j.e.waitingServe = false; j.e.serveSpot = null; }
    if (this.serving) this.serving.serveSpot = null;
    this.queue = [];
    if (this.serving) this.serving.cancelSequences();
    this.serving = null;
    this.handoff = undefined;
  }

  // 따르는 동안 병·셰이커가 스치지 말아야 할 바 위의 다른 잔들
  obstacles(e) {
    const list = new Set([this.glass, this.guestRef, this.serving, ...this.queue.map((j) => j.e)]);
    return [...list].filter((o) => o && o !== e && o.shape && o.root.parent);
  }

  // 두 잔 다 바텐더 앞에 있으니 옆(바텐더 오른손 쪽)에서 따른다
  yawFor() {
    return 0;
  }

  removeGlass() {
    this.cancelService();
    this.glass?.dispose();
    this.glass = null;
    this.sipT = -1;
    this.refillT = -1;
  }

  // 말풍선은 game이 띄운다
  say(text, delay = 0) {
    clearTimeout(this.sayTimer);
    this.sayTimer = setTimeout(() => this.onSay?.(text), delay);
  }

  onClink(strength) {
    this.happy = 1.8;
    this.gv.z -= 45 * strength;
    this.gv.x += (Math.random() - 0.5) * 20 * strength;
    if (Math.random() < 0.4) this.say(pick(BARTENDER_LINES.clink), 350);
  }

  // 손님이 마시면 따라 마신다 (원샷엔 같이 원샷)
  followSip({ shot = false } = {}) {
    const e = this.glass;
    if (shot) this.say(pick(['오, 원샷!', '멋있다!', '저도 원샷!']), 200);
    if (!e || e.busy || e.fill < 0.05 || this.sipT >= 0) return;
    setTimeout(() => {
      if (this.glass !== e || e.busy || this.sipT >= 0) return;
      if (e.flameOn) { e.strawSip(); return; }
      this.sipT = 0;
      this.sipShot = shot;
      this.sipFrom = e.fill;
      this.sipTo = shot || e.drink.shot || ['soju', 'shot', 'ochoko'].includes(e.glassDef.id) ? 0 : Math.max(0, e.fill - 0.34);
      this.gulped = 0;
      // 바텐더도 마신 만큼 취한다
      this.drunk = Math.min(1.4, (this.drunk || 0) + e.alcohol(this.sipFrom - this.sipTo) / 90);
    }, shot ? 350 : 500 + Math.random() * 500);
  }

  // 손님 취기에 맞춰 한마디
  noticeGuest(stage) {
    const lines = {
      2: ['얼굴 빨개지셨어요.', '기분 좋아 보이세요.'],
      3: ['천천히 드세요. 물도 좀 드시고요.', '어질어질하시죠? 안주도 좀 드세요.'],
      4: ['오늘은 여기까지 할까요? 숙취해소제 하나 드릴까요?', '많이 드셨어요. 해장 버튼 눌러 보세요.'],
    };
    if (lines[stage]) this.say(pick(lines[stage]), 600);
  }

  // ── 매 프레임 ─────────────────────────────────────────
  update(dt, t, ctx) {
    this.guestRef = ctx.me;
    // 숨쉬기, 깜빡임, 웃음
    const breath = Math.sin(t * 1.7) * 0.5;
    this.upper.position.y = WAIST + breath * 0.6;
    this.blinkT -= dt;
    if (this.blinkT < 0) this.blinkT = 2.5 + Math.random() * 3;
    const blink = this.blinkT < 0.12 ? 0.15 : 1;
    this.eyes.forEach((m) => (m.scale.y = 3 * blink));
    this.happy = Math.max(0, this.happy - dt);
    const smile = smooth(clamp(this.happy, 0, 1));
    this.mouth.scale.set(5 + smile * 2.5, 1 + smile * 2, 0.6);

    const e = this.glass;
    const userGlass = ctx.me && ctx.mode === 'single' ? ctx.me.root.position : null;
    // 따르던 잔이 끝났으면 다음 잔으로
    // 따르던 연출이 어떤 이유로든 끊겼으면 다음 잔으로 넘어간다 (차례가 멈춰 버리지 않게)
    if (this.serving && this.handoff === undefined && !this.serving.seq.some((s) => s.pour)) this.handoff = null;
    if (this.handoff !== undefined) {
      const h = this.handoff;
      this.handoff = undefined;
      this.nextService(h);
    }
    const hands = this.serving?.hands;
    // 술 만들 땐 바 앞으로 다가서서 손이 그릇에 닿게, 아니면 제자리
    const work = hands?.right;
    // 두 손을 다 쓰면 두 손 사이에, 한 손이면 오른손(화면 왼쪽)이 닿게
    const rx = work ? clamp(hands.left ? (work.x + hands.left.x) / 2 : work.x + 19, -56, 56) : 0;
    // 앞치마가 바 안쪽 끝에 닿을 만큼만 다가선다 (바를 뚫지 않게)
    const rz = work ? BAR.z0 - 9.6 : LAYOUT.single.bartender.z;
    // 일하는 중엔 손을 따라 빨리 옆걸음 (팔이 몸 앞을 가로지르지 않게)
    const k = Math.min(1, dt * (work ? 12 : 8));
    this.root.position.x += (rx - this.root.position.x) * k;
    this.root.position.z += (rz - this.root.position.z) * k;
    // 손이 닿지 않으면 허리를 굽혀 바 위로 숙인다
    let need = 0;
    if (hands) {
      for (const [h, side] of [[hands.right, -1], [hands.left, 1]]) {
        if (!h) continue;
        const d = Math.hypot(h.x - (this.root.position.x + side * 19.4), h.y - (WAIST + 32.5), h.z - this.root.position.z);
        // 팔을 다 펴야 닿을 거리면 미리 숙인다 (쭉 편 팔은 몸통 모서리를 가로지른다)
        need = Math.max(need, d - (UPPER + FORE) * 0.74);
      }
    }
    const lean = clamp(need / 26, 0, 0.42);
    this.upper.rotation.x += (lean - this.upper.rotation.x) * Math.min(1, dt * 6);
    // 손님 잔 쪽을 바라본다 (술 만들 땐 손끝을)
    const lookTo = work ? clamp((work.x - this.root.position.x) / 60, -0.35, 0.35) : userGlass ? clamp((userGlass.x - this.root.position.x) / 60, -0.5, 0.5) : Math.sin(t * 0.3) * 0.15;
    this.lookX += (lookTo - this.lookX) * Math.min(1, dt * 4);
    this.drunk = Math.max(0, (this.drunk || 0) - dt * 0.0035);
    const dr = Math.min(1, this.drunk);
    // 마실 때는 고개를 뒤로 젖힌다 (원샷은 더 많이)
    if (this.sipT < 0) this.sipBack = (this.sipBack || 0) * 0.9;
    this.head.rotation.set(0.12 - smile * 0.08 + Math.sin(t * 1.7) * 0.01 - (this.sipBack || 0), this.lookX, Math.sin(t * 0.9) * (0.02 + dr * 0.07) + smile * 0.05 * Math.sin(t * 9));
    this.upper.rotation.z = Math.sin(t * 0.7) * 0.035 * dr;
    if (this.blushes) {
      const k = 0.05 + dr;
      this.blushes.forEach((b) => { b.visible = dr > 0.05; b.scale.set(3 + dr * 2, 2 + dr, 0.4); b.material.opacity = Math.min(0.85, k); });
    }
    this.head.position.y = 134 - WAIST;

    if (!e) {
      this.updateWiping(t);
      return;
    }
    this.wipeGlass.visible = this.towel.visible = false;

    // 잔이 갈 곳
    const T = _p;
    const restX = BAR_REST.x, restZ = BAR_REST.z;
    let tilt = 0;
    if (this.sipT >= 0) {
      this.sipT += dt;
      const s = this.sipT;
      const long = this.sipShot ? 0.6 : 0;
      const up = smooth(clamp(s / 0.45, 0, 1)) * (1 - smooth(clamp((s - 1.2 - long) / 0.45, 0, 1)));
      // 잔을 기울였을 때 아랫입술 쪽 잔 테두리가 입 바로 앞에 오도록 잔 바닥 자리를 거꾸로 셈한다
      // (바닥을 입에 맞추고 기울이면 테두리가 얼굴 쪽으로 넘어가 머리 속에 박힌다)
      tilt = -(1.0 + (this.sipShot ? 0.45 * up : 0)) * up;
      const full = -(1.0 + (this.sipShot ? 0.45 : 0));
      const mouth = this.mouth.getWorldPosition(_q2);
      const H = e.height, R = (e.shape.rimR || e.shape.R || 4) * SCALE;
      const sn = Math.sin(full), cs = Math.cos(full);
      // 테두리 가운데 = 입 앞 1.6 + 테두리 반지름만큼 (위·앞으로 비켜서), 바닥 = 거기서 잔 높이만큼 뒤로
      // 원샷은 잔을 거의 눕히니 테두리 윗부분이 코에 닿지 않게 조금 더 앞으로
      // 긴 수염은 고개를 젖히면 앞으로 쏠려 잔에 닿는다: 수염이 길수록 덜 젖히고 잔을 조금 더 앞에 댄다
      const beard = this.C.facial === 'santa' ? 1 : this.C.facial === 'beard' ? 0.5 : 0;
      const cy = mouth.y - 0.6 + R * -sn, cz = mouth.z + 1.6 + R * cs + (this.sipShot ? 1.6 + R * 0.45 : 0) + beard * (this.sipShot ? 4 : 1.5);
      this.sipBack = up * (this.sipShot ? 0.42 - beard * 0.22 : 0.14 - beard * 0.06);
      const bx = mouth.x, by = cy - H * cs, bz = cz - H * sn;
      T.set(restX + (bx - restX) * up, TABLE_Y + (Math.max(TABLE_Y, by) - TABLE_Y) * up, restZ + (bz - restZ) * up);
      if (s > 0.45 && s < 1.2 + long) e.fill = this.sipFrom + (this.sipTo - this.sipFrom) * smooth((s - 0.45) / (0.75 + long));
      if (s > 0.55 && this.gulped === 0) { this.gulped = 1; this.env.audio?.gulp(); }
      if (this.sipShot && s > 0.95 && this.gulped === 1) { this.gulped = 2; this.env.audio?.gulp(); }
      if (s >= 1.7 + long) {
        this.sipT = -1;
        e.fill = this.sipTo;
        // 마지막 모금이면 장식도 집어 먹는다 (바텐더는 잔 -z 쪽에 있다)
        if (e.fill < 0.02) e.eatGarnish(-1);
        if (e.fill < 0.05) this.refillT = 1.2;
      }
    } else if (!e.busy && !e.waitingServe && !this.serving && userGlass && ctx.held) {
      // 손님이 잔을 들면 마주 들어 준다
      const dz = userGlass.z - restZ;
      const near = clamp(1 - (Math.abs(dz) - 12) / 25, 0, 1);
      // 손님 잔 바로 뒤에 숨지 않게 살짝 옆(바텐더 오른손 쪽)으로 비켜 든다
      const side = Math.min(e.radius + ctx.me.radius, 12) * 0.75;
      T.set(clamp(userGlass.x - side, -48, 40), ctx.liftY - e.contactMid, BAR.z0 + 5 + near * 3);
    } else {
      T.set(restX, TABLE_Y, restZ);
    }
    if ((e.busy || e.waitingServe) && this.sipT < 0) T.set(restX, TABLE_Y, restZ);
    e.extraTilt.x += (tilt - e.extraTilt.x) * Math.min(1, dt * 12);

    // 빈 잔은 다시 채운다 (빨대로 다 마셨을 때도)
    if (this.refillT < 0 && this.sipT < 0 && !e.busy && !e.waitingServe && e.fill < 0.03) this.refillT = 1.4;
    if (this.refillT >= 0) {
      this.refillT -= dt;
      if (this.refillT < 0 && !e.busy) {
        this.serveGuest(e, { fromEmpty: false });
        if (Math.random() < 0.5) this.say(pick(BARTENDER_LINES.refill));
      }
    }

    // 용수철처럼 따라간다 (부딪히면 살짝 밀려난다)
    const K = 150, D = 21;
    this.gv.x += ((T.x - this.gp.x) * K - this.gv.x * D) * dt;
    this.gv.y += ((T.y - this.gp.y) * K - this.gv.y * D) * dt;
    this.gv.z += ((T.z - this.gp.z) * K - this.gv.z * D) * dt;
    this.gp.addScaledVector(this.gv, dt);
    if (this.gp.y < TABLE_Y) { this.gp.y = TABLE_Y; this.gv.y = Math.max(0, this.gv.y); }
    e.root.position.copy(this.gp);

    this.armCtx = { held: ctx.held, breath };
  }

  // 팔은 잔들이 다 움직인 뒤에 맞춘다 (따르는 병을 손이 정확히 쥐도록)
  lateUpdate() {
    const e = this.glass;
    if (!e || !this.armCtx) return;
    const { held, breath } = this.armCtx;
    const hands = this.serving?.hands;
    // 오른손(화면 왼쪽)은 잔을 쥐고, 왼손은 바에 짚는다. 술 만들 땐 두 손 다 그릇으로
    const rh = this.rightHand || (this.rightHand = new THREE.Vector3());
    const lean = held ? 1 : 0;
    const bar = (side, out) => this.toLocal(_a.set(this.root.position.x + side * 16, TABLE_Y + 3 + breath * 0.3, BAR.z0 + 6 - lean * 2), out);
    if (hands?.right) {
      this.toLocal(hands.right, rh);
      this.setArm(this.arms.right, rh);
      // 왼손이 닿지 않을 만큼 멀면 그냥 바를 짚는다
      const lt = hands.left ? this.toLocal(hands.left, _b) : null;
      if (lt && lt.distanceTo(this.arms.left.shoulder) < UPPER + FORE + 4) this.setArm(this.arms.left, lt);
      else this.setArm(this.arms.left, bar(1, _b));
      return;
    }
    // 자기 잔은 가까운 쪽 손으로 쥐고, 다른 손은 바를 짚는다
    const local = this.toLocal(this.gp, _b);
    if (local.x < -4) {
      this.toLocal(_a.set(this.gp.x - e.radius - 3, this.gp.y + Math.min(e.contactMid, 12), this.gp.z), rh);
      this.setArm(this.arms.right, rh);
      this.setArm(this.arms.left, bar(1, _b));
    } else {
      this.toLocal(_a.set(this.gp.x + e.radius + 3, this.gp.y + Math.min(e.contactMid, 12), this.gp.z), _b);
      this.setArm(this.arms.left, _b);
      this.setArm(this.arms.right, bar(-1, rh));
    }
  }

  updateWiping(t) {
    this.wipeGlass.visible = this.towel.visible = true;
    const a = t * 3.2;
    const gx = -2 + Math.sin(t * 0.7) * 1.5, gy = 100 - WAIST + Math.sin(t * 1.1), gz = 15;
    this.wipeGlass.position.set(gx, gy, gz);
    this.wipeGlass.rotation.set(0.15, t * 0.6, 0);
    this.setArm(this.arms.right, _b.set(gx - 5, gy + 4, gz));
    const tx = gx + 3 + Math.cos(a) * 1.5, ty = gy + 6 + Math.sin(a) * 1.2;
    this.towel.position.set(tx, ty, gz + 1);
    this.towel.rotation.set(0, 0, Math.sin(a) * 0.3);
    this.setArm(this.arms.left, _b.set(tx + 2, ty + 1, gz + 1));
  }
}

// 바텐더 잔이 쉬는 자리
// 바텐더 잔 자리: 손님 잔을 앞으로 가져와 따를 때 옆에 둔 피처·주전자가 닿지 않게 넉넉히 떨어뜨린다
const BAR_REST = { x: -28, z: BAR.z0 + 12 };
// 손님 잔을 받아 와서 따르는 자리 (바텐더 바로 앞)
export const SERVE_SPOT = new THREE.Vector3(10, TABLE_Y, BAR.z0 + 16);
