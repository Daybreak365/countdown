import * as THREE from 'three';
import { BoxBuilder, hash3 } from './voxel.js';

// 안주. 접시(plate)와 하나씩 집어 먹는 조각(pieces)으로 나뉜다. 단위는 복셀(≈1cm), 접시 가운데가 원점.

const rnd = (i, k) => hash3(i, k, 77);

function disc(b, y, r, h, colorAt, step = 1) {
  for (let z = -r; z < r; z += step) {
    for (let x = -r; x < r; x += step) {
      const d = Math.hypot(x + step / 2, z + step / 2);
      if (d >= r) continue;
      const c = colorAt(x, z, d);
      if (c != null) b.box(x, y, z, step, h, step, c, 0.03);
    }
  }
}

// 흰 접시: 가장자리가 살짝 올라온다
function plate(b, r = 9, color = 0xf2ece0) {
  disc(b, 0, r, 1, () => color);
  for (let a = 0; a < Math.PI * 2; a += 0.28) b.cbox(Math.cos(a) * (r - 0.5), 1, Math.sin(a) * (r - 0.5), 1, 0.8, 1, 0xe7e0d2, 0);
}

// 소스 종지: 접시 위 (x, z)에 놓는다
function sauceDish(b, x, z, color, r = 2.6) {
  const g = new BoxBuilder();
  disc(g, 1, r, 1.6, (px, pz, d) => (d > r - 1 ? 0xe9e2d4 : null));
  disc(g, 1, r - 0.8, 1, () => 0xe9e2d4);
  disc(g, 1.8, r - 0.8, 0.6, () => color);
  merge(b, g, x, 0, z);
}

// 다른 빌더의 상자들을 옮겨 붙인다
function merge(b, g, dx, dy, dz) {
  for (let i = 0; i < g.pos.length; i += 3) b.pos.push(g.pos[i] + dx, g.pos[i + 1] + dy, g.pos[i + 2] + dz);
  b.nor.push(...g.nor);
  b.col.push(...g.col);
}

export const ANJU = [
  {
    id: 'chicken', name: '후라이드 치킨', desc: '치맥은 못 참지.',
    plate(b) {
      // 종이 깔린 바구니
      b.cbox(0, 0, 0, 20, 3, 16, 0x9a6a3a, 0.04);
      b.cbox(0, 3, 0, 18.4, 0.4, 14.4, 0xf3ead8, 0.02);
      for (let i = -8; i < 8; i += 4) b.cbox(i + 1, 3.05, 0, 2, 0.4, 14.4, 0xd84a3a, 0);
      // 치킨무
      for (let i = 0; i < 4; i++) b.cbox(-7 + (i % 2) * 1.6, 3.4, -5 + Math.floor(i / 2) * 1.6, 1.4, 1.4, 1.4, 0xf6f1d9, 0.03);
    },
    pieces: Array.from({ length: 7 }, (_, i) => (b) => {
      const a = rnd(i, 1) * 6.28, r = 2 + rnd(i, 2) * 4.5;
      const x = Math.cos(a) * r + 1.5, z = Math.sin(a) * r * 0.7, y = 3.4 + (i % 3) * 1.2;
      const c = i % 2 ? 0xb5651d : 0xc9792a;
      b.cbox(x, y, z, 4, 3, 3.4, c, 0.12);
      b.cbox(x + 0.6, y + 2.4, z - 0.4, 2.6, 1.2, 2.2, i % 2 ? 0xc9792a : 0xd88a36, 0.12);
      if (i === 3) {
        // 닭다리 뼈
        b.cbox(x + 3, y + 0.6, z, 2.6, 1, 1, 0xf3e6c8, 0);
        b.cbox(x + 4.2, y + 0.4, z, 1, 1.5, 1.5, 0xf3e6c8, 0);
      }
    }),
  },
  {
    id: 'eggroll', name: '계란말이', desc: '케첩 콕, 폭신하게.',
    plate: (b) => plate(b),
    pieces: Array.from({ length: 5 }, (_, i) => (b) => {
      const x = -5.6 + i * 2.8;
      b.cbox(x, 1, 0, 2.6, 4, 5.4, 0xf2c94c, 0.05);
      b.cbox(x, 2, 0, 2.7, 2, 3.2, 0xf7e08a, 0.03);
      b.cbox(x, 2.6, 0.8, 2.7, 0.6, 0.6, 0x5f9445, 0);
      b.cbox(x, 1.8, -1, 2.7, 0.6, 0.6, 0xd8443a, 0);
      if (i === 2) b.cbox(x, 5, 0, 1.4, 0.6, 1.4, 0xc4161c, 0);
    }),
  },
  {
    id: 'peanuts', name: '땅콩', desc: '오도독 짭짤하게.',
    plate(b) {
      disc(b, 0, 7, 1, () => 0xf2ece0);
      for (let a = 0; a < 6.28; a += 0.4) b.cbox(Math.cos(a) * 6.5, 1, Math.sin(a) * 6.5, 1, 1.2, 1, 0xe5ddcd, 0);
    },
    pieces: Array.from({ length: 5 }, (_, g) => (b) => {
      for (let k = 0; k < 4; k++) {
        const i = g * 4 + k;
        const a = rnd(i, 1) * 6.28, r = rnd(i, 2) * 4.6;
        b.cbox(Math.cos(a) * r, 1 + (i % 3) * 0.6, Math.sin(a) * r, 1.6, 1.1, 1.1, i % 2 ? 0xc49a63 : 0xb0844f, 0.08);
      }
    }),
  },
  {
    id: 'fries', name: '감자튀김', desc: '케첩 듬뿍, 바삭하게.',
    plate(b) {
      plate(b, 9);
      // 종이 컵
      b.cbox(-1, 1, 0, 9, 7, 7, 0xd84a3a, 0.03);
      b.cbox(-1, 4, 3.6, 9, 2, 0.4, 0xf3ead8, 0);
      sauceDish(b, 5.5, 4, 0xc4161c, 2.4);
    },
    pieces: Array.from({ length: 8 }, (_, i) => (b) => {
      const x = -4.5 + (i % 4) * 2.3, z = -1.6 + Math.floor(i / 4) * 2.6;
      const h = 9 + rnd(i, 3) * 3;
      b.cbox(x, 4, z, 1, h, 1, i % 3 ? 0xf2c14e : 0xe8b040, 0.08);
    }),
  },
  {
    id: 'nogari', name: '노가리', desc: '호프집 단골, 마요네즈에 고추 쏙.',
    plate(b) {
      plate(b, 9);
      sauceDish(b, 4.5, 3.8, 0xf2b23a, 2.8);
      // 청양고추 송송
      for (let i = 0; i < 3; i++) b.cbox(4 + i * 0.9, 1.9, 3.4 + (i % 2) * 0.8, 0.7, 0.4, 0.7, 0x3f8f3a, 0);
    },
    pieces: Array.from({ length: 6 }, (_, i) => (b) => {
      const z = -5.5 + i * 1.9, x = -3 + rnd(i, 4) * 1.5;
      b.cbox(x, 1 + (i % 2) * 0.5, z, 9, 0.9, 1.5, 0xd9b98a, 0.08);
      b.cbox(x, 1.6 + (i % 2) * 0.5, z, 8, 0.4, 0.5, 0x9a7a52, 0.05);
      b.cbox(x - 4.8, 1 + (i % 2) * 0.5, z, 1.4, 1.2, 2, 0xb8956a, 0.05);
    }),
  },
  {
    id: 'pajeon', name: '파전', desc: '막걸리엔 역시, 비 오는 날.',
    hot: true,
    plate(b) {
      plate(b, 10);
      sauceDish(b, 7, -6, 0x3a2414, 2.4);
    },
    // 여섯 조각으로 자른 둥근 전
    pieces: Array.from({ length: 6 }, (_, i) => (b) => {
      const a0 = (i / 6) * Math.PI * 2, a1 = ((i + 1) / 6) * Math.PI * 2;
      for (let z = -7; z < 7; z++) for (let x = -7; x < 7; x++) {
        const d = Math.hypot(x + 0.5, z + 0.5);
        if (d >= 7) continue;
        let a = Math.atan2(z + 0.5, x + 0.5);
        if (a < 0) a += Math.PI * 2;
        if (a < a0 + 0.05 || a >= a1 - 0.05) continue;
        const h = hash3(x, z, 3);
        const c = h < 0.18 ? 0x4f8f3a : h < 0.24 ? 0xd8443a : d > 6 ? 0xb5772e : h < 0.6 ? 0xd9a248 : 0xe6b860;
        b.box(x, 1, z, 1, 1.4 + (h < 0.18 ? 0.4 : 0), 1, c, 0.05);
      }
    }),
  },
  {
    id: 'tteokbokki', name: '떡볶이', desc: '매콤달콤, 어묵이랑 계란도.',
    hot: true,
    plate(b) {
      plate(b, 10);
      disc(b, 1, 8, 0.6, () => 0xc4321c);
      // 삶은 달걀 반쪽, 어묵
      b.cbox(5, 1.4, -4, 3, 2.4, 3.4, 0xf8f4ea, 0.02);
      b.cbox(5, 3.4, -4, 1.8, 0.6, 2, 0xf2c94c, 0);
      b.cbox(-5, 1.5, -4.5, 4, 0.7, 3, 0xe0a060, 0.05);
      b.cbox(-4, 1.6, 5, 4, 0.7, 2.6, 0xe0a060, 0.05);
    },
    pieces: Array.from({ length: 8 }, (_, i) => (b) => {
      const a = rnd(i, 5) * 6.28, r = 1 + rnd(i, 6) * 4.5;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      const along = i % 2;
      b.cbox(x, 1.4 + (i % 3) * 0.5, z, along ? 4.5 : 1.5, 1.5, along ? 1.5 : 4.5, i % 3 ? 0xd9462e : 0xe8604a, 0.06);
      if (i % 3 === 0) b.cbox(x, 3, z, 1.6, 0.4, 0.4, 0x4f8f3a, 0);
    }),
  },
  {
    id: 'cheese', name: '치즈 플래터', desc: '와인 한 잔엔 치즈, 크래커, 포도.',
    plate(b) {
      // 나무 도마
      b.cbox(0, 0, 0, 22, 1.6, 13, 0x9a6a43, 0.04);
      b.cbox(12, 0.4, 0, 4, 0.8, 2.6, 0x7a4f30, 0.03);
      // 포도송이
      for (let i = 0; i < 9; i++) b.cbox(6 + (i % 3) * 1.4, 1.6 + Math.floor(i / 6) * 1.2, -3.5 + Math.floor(i / 3) * 1.3, 1.3, 1.3, 1.3, i % 2 ? 0x5a2a5e : 0x6e3a72, 0.05);
      b.cbox(7.4, 3.4, -3, 0.4, 1.4, 0.4, 0x5b3a1e, 0);
    },
    pieces: [
      (b) => { b.cbox(-7, 1.6, -2.5, 4, 3, 3.4, 0xf2c94c, 0.04); b.cbox(-7.6, 2.4, -0.8, 0.8, 0.8, 0.2, 0xd9a83a, 0); },
      (b) => { b.cbox(-2.5, 1.6, -3, 3, 3, 3, 0xf6e7b8, 0.04); },
      (b) => { b.cbox(-7, 1.6, 3, 3.2, 2.4, 3.2, 0xe8a83a, 0.04); },
      (b) => { b.cbox(-2.5, 1.6, 2.8, 4, 0.8, 4, 0xd9b26a, 0.05); b.cbox(-2.5, 2.4, 2.8, 4, 0.8, 4, 0xe2bc76, 0.05); },
      (b) => { b.cbox(2, 1.6, 3, 4, 0.8, 4, 0xd9b26a, 0.05); },
      (b) => { b.cbox(2, 1.6, -3, 3, 2.6, 3, 0xf0d9a0, 0.04); b.cbox(2, 4.2, -3, 1, 0.4, 1, 0x5f9445, 0); },
    ],
  },
  {
    id: 'fruit', name: '과일 한 접시', desc: '수박, 오렌지, 딸기. 입가심으로.',
    plate: (b) => plate(b, 10),
    pieces: [
      ...[0, 1, 2].map((i) => (b) => {
        const x = -6 + i * 3.2, z = -3;
        b.cbox(x, 1, z, 2.6, 1.2, 6, 0x3f7a3a, 0.04);
        b.cbox(x, 2.2, z, 2.6, 0.6, 6, 0xe8f2c8, 0.02);
        b.cbox(x, 2.8, z, 2.6, 3, 6, 0xe5404a, 0.05);
        b.cbox(x + 0.4, 4, z + 1, 0.5, 0.5, 0.5, 0x2a1a14, 0);
      }),
      ...[0, 1].map((i) => (b) => {
        const x = 3 + i * 3.4, z = -3;
        b.cbox(x, 1, z, 3, 3, 1.2, 0xf08a24, 0.04);
        b.cbox(x, 1.4, z, 2.2, 2.2, 1.3, 0xf6b04a, 0.04);
      }),
      ...[0, 1, 2].map((i) => (b) => {
        const x = -4 + i * 3.6, z = 4;
        b.cbox(x, 1, z, 2.2, 2.4, 2.2, 0xd81e3a, 0.06);
        b.cbox(x, 3.4, z, 1.6, 0.6, 1.6, 0x4f8f3a, 0);
      }),
    ],
  },
  {
    id: 'odeng', name: '어묵탕', desc: '뜨끈한 국물, 꼬치째 하나씩.',
    hot: true,
    plate(b) {
      // 깊은 그릇과 국물
      disc(b, 0, 8, 1, () => 0xe8e0d0);
      for (let y = 1; y < 6; y++) disc(b, y, 8.4, 1, (x, z, d) => (d > 7.4 ? (y === 5 ? 0xf2ece0 : 0x9a3a2a) : null));
      disc(b, 1, 7.4, 3.6, () => 0xd9a85a);
      b.cbox(-3, 4.6, 3, 2, 0.4, 0.8, 0x4f8f3a, 0);
      b.cbox(2, 4.6, -4, 1.4, 0.4, 0.8, 0x4f8f3a, 0);
      b.cbox(4, 4.4, 3, 1.4, 0.6, 1.4, 0xd84a3a, 0);
    },
    pieces: Array.from({ length: 4 }, (_, i) => (b) => {
      const a = (i / 4) * 6.28 + 0.4, r = 3.2;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      // 꼬치에 접어 꽂은 어묵
      for (let k = 0; k < 3; k++) b.cbox(x + (k % 2 ? 0.8 : -0.8), 3 + k * 2.2, z, 2.6, 2, 1.2, k % 2 ? 0xd9a060 : 0xe8b878, 0.06);
      b.cbox(x, 3, z, 0.4, 14, 0.4, 0xc9a26b, 0.02);
    }),
  },
  {
    id: 'yakitori', name: '꼬치 모둠', desc: '숯불에 구운 닭꼬치, 염통, 대파꼬치.',
    hot: true,
    plate(b) {
      // 길쭉한 나무 접시와 양념 종지
      b.cbox(0, 0, 0, 22, 1.2, 12, 0xc8a070, 0.04);
      b.cbox(0, 1.2, 0, 21, 0.4, 11, 0xd8b88a, 0.03);
      sauceDish(b, 8.4, -3.6, 0x5a2a12, 2.2);
      b.cbox(8.4, 1.4, 3.4, 3, 0.8, 2.4, 0xf0e070, 0.03);
    },
    pieces: Array.from({ length: 5 }, (_, i) => (b) => {
      const z = -4 + i * 2.1, x0 = -9;
      b.cbox(x0 + 7, 1.8, z, 15, 0.5, 0.5, 0xd8c08a, 0);
      const kinds = [[0xa8642e, 0xc07a3a], [0x7a2a1a, 0x8a3a22], [0xa8642e, 0x6b8a3a], [0xc8783a, 0xb06a32], [0x8a4a22, 0xa8642e]][i];
      for (let k = 0; k < 4; k++) b.cbox(x0 + 2 + k * 3.1, 1.9, z, 2.6, 2, 2, kinds[k % 2], 0.08);
    }),
  },
  {
    id: 'cupramen', name: '컵라면', desc: '물 붓고 3분, 편의점 앞 국룰.',
    hot: true,
    plate(b) {
      // 컵 (위가 넓다), 벗겨 접은 뚜껑, 나무젓가락
      for (let y = 0; y < 11; y++) {
        const r = 4.6 + y * 0.18;
        disc(b, y, r, 1, (x, z, d) => (d > r - 0.9 ? (y > 3 && y < 7 ? 0xd8302a : 0xf2f2ee) : y === 0 ? 0xf2f2ee : null));
      }
      disc(b, 6.4, 5.4, 0.6, () => 0xc0602a);
      b.cbox(7.4, 0, -4, 6, 0.4, 6, 0xf2f2ee, 0.02);
      b.cbox(7.4, 0.4, -4, 5, 0.2, 5, 0xe05a2a, 0);
      b.cbox(-7.4, 0, 2, 1.2, 0.8, 14, 0xd8c08a, 0.02);
    },
    pieces: Array.from({ length: 5 }, (_, i) => (b) => {
      // 면 한 젓가락씩 (위에서부터 줄어든다), 마지막엔 건더기
      const y = 9.6 - i * 1.3;
      disc(b, y, 5.2, 1.2, (x, z, d) => (hash3(Math.round(x), Math.round(z), i) < 0.5 ? 0xf0d890 : 0xe6c878));
      if (i === 0) {
        b.cbox(-1.6, y + 1.2, 1, 2, 0.6, 1.4, 0x4f8f3a, 0);
        b.cbox(1.8, y + 1.2, -1, 1.6, 0.6, 1.6, 0xd8a050, 0);
        b.cbox(0.4, y + 1.2, 2.2, 1.2, 0.6, 1.2, 0xe0503a, 0);
      }
    }),
  },
  {
    id: 'dubu', name: '두부김치', desc: '소주엔 두부김치, 국룰.',
    plate(b) {
      plate(b, 10);
      // 가운데 볶은 김치와 돼지고기
      for (let i = 0; i < 22; i++) {
        const a = rnd(i, 7) * 6.28, r = rnd(i, 8) * 3.4;
        b.cbox(Math.cos(a) * r, 1 + (i % 3) * 0.5, Math.sin(a) * r, 1.6, 0.8, 1.2, i % 4 === 0 ? 0xe8c8a8 : i % 3 ? 0xc8402a : 0xe0602e, 0.06);
      }
      b.cbox(0, 2.6, 0, 1.6, 0.4, 0.4, 0x4f8f3a, 0);
      b.cbox(0.6, 2.8, 0.6, 0.6, 0.3, 0.6, 0xf2ead2, 0);
    },
    pieces: Array.from({ length: 6 }, (_, i) => (b) => {
      const a = (i / 6) * 6.28, r = 6.6;
      b.cbox(Math.cos(a) * r, 1, Math.sin(a) * r, 3, 2, 3, 0xfbf7ec, 0.03);
      b.cbox(Math.cos(a) * r, 3, Math.sin(a) * r, 3, 0.2, 3, 0xe9e2cf, 0);
    }),
  },
  {
    id: 'nachos', name: '나초', desc: '살사랑 치즈 소스에 푹.',
    plate(b) {
      plate(b, 10);
      sauceDish(b, -2.8, 0, 0xc8402a, 2.6);
      sauceDish(b, 2.8, 0, 0xf2c14e, 2.6);
    },
    pieces: Array.from({ length: 8 }, (_, i) => (b) => {
      const a = (i / 8) * 6.28, r = 6.8;
      const x = Math.cos(a) * r, z = Math.sin(a) * r;
      // 세모 칩
      b.cbox(x, 1 + (i % 2) * 0.4, z, 3.4, 0.5, 1.2, 0xe8a83a, 0.08);
      b.cbox(x, 1 + (i % 2) * 0.4, z, 2.2, 0.5, 2.4, 0xf2b84a, 0.08);
      b.cbox(x, 1 + (i % 2) * 0.4, z, 1, 0.5, 3.4, 0xe8a83a, 0.08);
    }),
  },
];

export const anjuById = Object.fromEntries(ANJU.map((a) => [a.id, a]));

// 술에 어울리는 기본 안주
const PAIRING_BY_DRINK = {
  makgeolli: 'pajeon', dongdongju: 'pajeon', maksa: 'pajeon',
  soju: 'dubu', bokbunja: 'fruit', maesil: 'fruit', gaoliang: 'tteokbokki',
  sake: 'odeng', atsukan: 'odeng', cheongju: 'odeng',
  somaek: 'chicken', sopok: 'nogari', yangpok: 'nogari', gojingamrae: 'nogari',
  champagne: 'fruit', sangria: 'cheese', vinchaud: 'cheese',
  mojito: 'nachos', margarita: 'nachos', tequila: 'nachos',
};
const PAIRING_BY_CAT = { beer: 'chicken', wine: 'cheese', whisky: 'peanuts', cocktail: 'nachos', korean: 'dubu', bomb: 'nogari', soft: 'fruit' };

export function pairAnju(drink) {
  return PAIRING_BY_DRINK[drink.id] ?? PAIRING_BY_CAT[drink.cat] ?? 'peanuts';
}

const geoCache = new Map();
function anjuGeometry(def) {
  if (geoCache.has(def.id)) return geoCache.get(def.id);
  const pb = new BoxBuilder();
  def.plate(pb);
  const pieces = def.pieces.map((fn) => {
    const b = new BoxBuilder();
    fn(b);
    const g = b.geometry();
    g.computeBoundingBox();
    return g;
  });
  const out = { plate: pb.geometry(), pieces };
  geoCache.set(def.id, out);
  return out;
}

// 탁자 위 안주 한 접시
export class AnjuPlate {
  constructor(env, id, { yaw = 0 } = {}) {
    this.env = env;
    this.root = new THREE.Group();
    this.root.rotation.y = yaw;
    this.mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.7 });
    // 뺏겨서 날아가는 조각들
    this.flying = [];
    this.set(id);
  }

  set(id) {
    this.clearMeshes();
    const def = (this.def = anjuById[id] || ANJU[0]);
    const geo = anjuGeometry(def);
    this.plateMesh = new THREE.Mesh(geo.plate, this.mat);
    this.plateMesh.castShadow = this.plateMesh.receiveShadow = true;
    this.root.add(this.plateMesh);
    this.pieces = geo.pieces.map((g) => {
      const m = new THREE.Mesh(g, this.mat);
      m.castShadow = true;
      m.userData.pop = 0;
      this.root.add(m);
      return m;
    });
    this.left = this.pieces.length;
    this.refillT = -1;
    this.steamAcc = 0;
    // 새로 나온 접시는 톡 하고 놓인다
    this.appear = 0;
  }

  clearMeshes() {
    if (!this.plateMesh) return;
    this.root.remove(this.plateMesh);
    for (const p of this.pieces) this.root.remove(p);
    this.plateMesh = null;
    this.pieces = [];
  }

  get meshes() {
    return [this.plateMesh, ...this.pieces.filter((p) => p.visible)];
  }

  // 남이 한 입 뺏어 간다: 조각 하나가 접시에서 to(월드 좌표)까지 포물선을 그리며 날아가 사라진다
  snatch(to, { silent = false } = {}) {
    if (this.left <= 0 || !this.root.parent) return false;
    const i = this.pieces.length - this.left;
    const piece = this.pieces[i];
    piece.visible = false;
    this.left--;
    const bb = piece.geometry.boundingBox;
    const c = bb.getCenter(new THREE.Vector3());
    const m = new THREE.Mesh(piece.geometry, this.mat);
    m.position.copy(c).negate();
    m.castShadow = true;
    const g = new THREE.Group();
    g.add(m);
    const from = c.clone();
    this.root.localToWorld(from);
    g.position.copy(from);
    g.quaternion.copy(this.root.quaternion);
    g.scale.copy(this.root.scale);
    this.root.parent.add(g);
    this.flying.push({ g, from, to: to.clone(), t: 0, silent, spin: (Math.random() - 0.5) * 9 });
    if (this.left === 0) this.refillT = 4;
    return true;
  }

  updateFlying(dt) {
    for (let k = 0; k < this.flying.length; k++) {
      const f = this.flying[k];
      f.t += dt / 0.6;
      const u = Math.min(1, f.t);
      const e = u * u * (3 - 2 * u);
      f.g.position.lerpVectors(f.from, f.to, e);
      f.g.position.y += Math.sin(u * Math.PI) * 22;
      f.g.rotation.y += f.spin * dt;
      f.g.rotation.x += f.spin * 0.6 * dt;
      // 다 와 가면 입에 쏙
      f.g.scale.setScalar(Math.max(0.001, this.root.scale.x * (u < 0.75 ? 1 : 1 - (u - 0.75) / 0.25)));
      if (u >= 1) {
        if (!f.silent) this.env.audio?.crunch(this.def.hot ? 0.5 : 1, this.panHint);
        f.g.removeFromParent();
        this.flying.splice(k, 1);
        k--;
      }
    }
  }

  // 한 입: 남은 조각 하나를 집어 먹는다
  eat({ silent = false } = {}) {
    if (this.left <= 0) return false;
    const i = this.pieces.length - this.left;
    const piece = this.pieces[i];
    piece.visible = false;
    this.left--;
    if (!silent) this.env.audio?.crunch(this.def.hot ? 0.5 : 1, this.panHint);
    const fx = this.env.fx;
    if (fx) {
      const bb = piece.geometry.boundingBox;
      const c = bb.getCenter(new THREE.Vector3());
      this.root.localToWorld(c);
      for (let k = 0; k < 5; k++) {
        fx.drops.spawn({ x: c.x, y: c.y, z: c.z, vx: (Math.random() - 0.5) * 14, vy: 6 + Math.random() * 10, vz: (Math.random() - 0.5) * 14, gravity: 200, life: 1, size: 0.5 + Math.random() * 0.4, color: 0xd9b26a, floor: this.root.position.y + 1, env: 'hold' });
      }
    }
    if (this.left === 0) this.refillT = 4;
    return true;
  }

  update(dt, t) {
    if (this.flying.length) this.updateFlying(dt);
    this.appear = Math.min(1, this.appear + dt * 4);
    const k = this.appear;
    const pop = k < 1 ? 1 + Math.sin(k * Math.PI) * 0.12 : 1;
    this.root.scale.setScalar(Math.max(0.001, k * pop));
    // 다 먹으면 잠시 뒤 다시 채워진다
    if (this.refillT >= 0) {
      this.refillT -= dt;
      if (this.refillT < 0) {
        for (const p of this.pieces) { p.visible = true; p.userData.pop = 0; }
        this.left = this.pieces.length;
        // 다 먹고 다시 채워지면 한 접시 더 시킨 셈 (계산서)
        this.onRefill?.();
      }
    }
    for (const p of this.pieces) {
      if (!p.visible || p.userData.pop >= 1) continue;
      p.userData.pop = Math.min(1, p.userData.pop + dt * 3);
      p.scale.setScalar(Math.max(0.001, p.userData.pop));
    }
    // 뜨거운 안주는 김이 난다
    const fx = this.env.fx;
    if (this.def.hot && fx && this.left > 0) {
      this.steamAcc += dt * 3;
      while (this.steamAcc >= 1) {
        this.steamAcc -= 1;
        const v = new THREE.Vector3((Math.random() - 0.5) * 8, 5, (Math.random() - 0.5) * 8);
        this.root.localToWorld(v);
        fx.steam.spawn({ x: v.x, y: v.y, z: v.z, vx: (Math.random() - 0.5) * 2, vy: 6 + Math.random() * 4, vz: (Math.random() - 0.5) * 2, wobble: 3, drag: 0.4, life: 2 + Math.random(), size: 1.4 + Math.random(), env: 'steam', color: 0xf7f2ea });
      }
    }
  }

  dispose() {
    for (const f of this.flying) f.g.removeFromParent();
    this.flying = [];
    this.clearMeshes();
    this.mat.dispose();
    this.root.removeFromParent();
  }
}
