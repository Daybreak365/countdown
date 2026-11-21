// 잔과 술 정의. 단위는 복셀(1칸 ≈ 1cm).
//
// 잔은 아래에서 위로 쌓인 층(layer)의 목록이다. 각 층은 고리(ring) 여러 개와
// 액체가 들어갈 수 있는 안쪽 반지름(cavity)을 가진다. 고리 [바깥, 안쪽]에서
// 안쪽이 0이면 꽉 찬 원판이다.

const L = (rings, cavity = 0) => ({ rings, cavity });
const solid = (n, r) => Array.from({ length: n }, () => L([[r, 0]]));
const walls = (radii, t = 1) => radii.map((r) => L([[r, r - t]], r - t));
const taper = (n, a, b) => Array.from({ length: n }, (_, i) => a + ((b - a) * i) / Math.max(1, n - 1));
const same = (n, r) => new Array(n).fill(r);

// ── 잔 무늬 ───────────────────────────────────────────────
const facets = (light, dark) => ({ angle, edge }) => (edge && Math.floor(((angle + Math.PI) / (Math.PI * 2)) * 16) % 2 ? dark : light);

const cutCrystal = ({ angle, y, edge }) => {
  if (!edge || y < 3) return null;
  const a = Math.floor(((angle + Math.PI) / (Math.PI * 2)) * 18);
  return (a + y) % 3 === 0 ? 0xffffff : null;
};

export const GLASSES = [
  {
    id: 'pint', name: '파인트', material: 'glass',
    layers: [...solid(2, 3.4), ...walls(taper(13, 3.5, 4.4))],
  },
  {
    id: 'jug', name: '생맥주 조끼', material: 'thick',
    layers: [...solid(2, 4.4), ...walls(same(12, 4.6), 1.4)],
    handle: { from: 3, to: 11, out: 3 },
    paint: facets(null, 0xcfe6e4),
  },
  {
    id: 'pilsner', name: '필스너', material: 'glass',
    layers: [...solid(1, 2.9), ...solid(1, 2.3), ...walls(taper(17, 2.5, 3.9))],
  },
  {
    id: 'weizen', name: '바이젠', material: 'glass',
    layers: [...solid(1, 2.9), ...solid(1, 2.3),
      ...walls([2.4, 2.4, 2.5, 2.6, 2.7, 2.8, 3.0, 3.2, 3.4, 3.6, 3.7, 3.8, 3.8, 3.7, 3.6, 3.5, 3.4, 3.4])],
  },
  {
    id: 'somaek', name: '소맥잔', material: 'glass',
    layers: [...solid(1, 2.8), ...walls(taper(10, 2.9, 3.4))],
    // 소주 황금비율 눈금
    paint: ({ y, edge }) => (edge && y === 4 ? 0xffffff : null),
  },
  {
    id: 'wine', name: '와인잔', material: 'crystal',
    layers: [...solid(1, 3.6), ...solid(1, 1.6), ...solid(5, 0.8), ...solid(1, 1.8), ...solid(1, 2.6),
      ...walls([3.3, 3.8, 4.1, 4.2, 4.2, 4.1, 3.9, 3.7, 3.5])],
  },
  {
    id: 'flute', name: '샴페인 플루트', material: 'crystal',
    layers: [...solid(1, 3.0), ...solid(1, 1.2), ...solid(6, 0.8), ...solid(1, 1.8),
      ...walls([2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.9, 2.9, 2.9, 2.8, 2.8, 2.7])],
  },
  {
    id: 'martini', name: '마티니 글라스', material: 'crystal',
    layers: [...solid(1, 3.4), ...solid(1, 1.2), ...solid(6, 0.8), ...solid(1, 1.4),
      ...walls([2.2, 3.0, 3.8, 4.6, 5.4, 6.2], 1.1)],
  },
  {
    id: 'coupe', name: '쿠프 글라스', material: 'crystal',
    layers: [...solid(1, 3.4), ...solid(1, 1.2), ...solid(5, 0.8), ...solid(1, 2.0), ...solid(1, 3.2),
      ...walls([4.2, 4.8, 5.1, 5.2])],
  },
  {
    id: 'rocks', name: '락 글라스', material: 'thick',
    layers: [...solid(3, 4.2), ...walls(same(7, 4.3), 1.2)],
    paint: cutCrystal,
  },
  {
    id: 'glencairn', name: '글렌캐런 잔', material: 'crystal',
    // 아래가 불룩하고 위로 좁아지는 위스키 시음잔
    layers: [...solid(2, 3.0), ...solid(2, 1.6), ...walls([2.6, 3.2, 3.4, 3.4, 3.3, 3.1, 2.9, 2.7, 2.6, 2.5, 2.6])],
  },
  {
    id: 'highball', name: '하이볼 글라스', material: 'glass',
    layers: [...solid(2, 3.2), ...walls(same(16, 3.3))],
  },
  {
    id: 'soju', name: '소주잔', material: 'glass',
    layers: [...solid(1, 2.2), ...walls(taper(6, 2.5, 3.0))],
  },
  {
    id: 'shot', name: '샷 글라스', material: 'thick',
    layers: [...solid(3, 2.5), ...walls(taper(5, 2.6, 2.9))],
  },
  {
    id: 'bowl', name: '양은 사발', material: 'metal', color: 0xd8ac4c,
    layers: [L([[3.4, 2.4]]), ...solid(1, 3.6), ...walls([4.4, 5.1, 5.6, 5.9, 6.1])],
    // 두드려 편 자국
    paint: ({ h }) => (h < 0.18 ? 0xc2943a : h > 0.9 ? 0xe9c56a : null),
  },
  {
    id: 'teacup', name: '찻잔', material: 'ceramic', color: 0xf4efe4,
    layers: [...solid(1, 4.6), L([[2.2, 0], [5.6, 4.6]]), ...solid(1, 2.9),
      ...walls([3.2, 3.6, 3.9, 4.1, 4.2, 4.3])],
    handle: { from: 4, to: 7, out: 2 },
    paint: ({ y, d, edge }) => {
      if (y === 1 && d >= 4.6) return 0x5a7fb0;
      if (edge && (y === 7 || y === 5) && d > 3.5) return 0x5a7fb0;
      return null;
    },
  },
  {
    id: 'mug', name: '머그컵', material: 'ceramic', color: 0xe8dbc1,
    layers: [...solid(1, 3.6), ...walls(same(10, 3.9), 1.1)],
    handle: { from: 2, to: 8, out: 3 },
    // 아래는 유약 안 바른 흙, 위는 귀리색 유약에 점박이
    paint: ({ y, h, angle }) => {
      const drip = Math.sin(angle * 7) > 0.6 ? 1 : 0;
      if (y < 2 + drip) return 0xa9744f;
      return h < 0.07 ? 0x8a6a4c : null;
    },
  },
  {
    id: 'ochoko', name: '사케 잔', material: 'ceramic', color: 0xf6f3ec,
    layers: [L([[2.0, 1.0]]), ...solid(1, 2.2), ...walls([2.8, 3.2, 3.4, 3.5])],
    // 바닥의 뱀눈(蛇の目) 무늬
    paint: ({ y, d }) => (y === 1 && (d < 0.8 || (d > 1.3 && d < 1.9)) ? 0x2f5d9c : null),
  },
  {
    id: 'tankard', name: '나무 잔', material: 'wood', color: 0x8a5a33,
    layers: [...solid(2, 4.2), ...walls(same(11, 4.4), 1.2)],
    handle: { from: 3, to: 10, out: 3 },
    paint: ({ y, angle, edge, ring }) => {
      if (ring === 'handle') return 0x6e4426;
      if (edge && (y === 3 || y === 10)) return 0x4d4a48;
      return Math.floor(((angle + Math.PI) / (Math.PI * 2)) * 14) % 2 ? 0x7b4d2b : null;
    },
  },
  {
    id: 'copper', name: '구리 머그', material: 'metal', color: 0xc8733a,
    layers: [...solid(2, 3.9), ...walls(taper(10, 4.0, 4.3), 1.0)],
    handle: { from: 3, to: 9, out: 3 },
    // 망치로 두드린 자국
    paint: ({ ring, edge, y }) => (ring === 'handle' ? 0xb0622f : edge && y === 11 ? 0xe2a066 : y < 2 ? 0xa85d2c : null),
  },
  {
    id: 'irish', name: '아이리시 커피 잔', material: 'crystal',
    layers: [...solid(1, 3.2), ...solid(3, 0.9), ...solid(1, 2.0),
      ...walls([2.8, 3.3, 3.6, 3.8, 3.9, 3.9, 3.9, 3.9, 3.9, 3.9])],
    handle: { from: 7, to: 12, out: 2 },
  },
  {
    id: 'celadon', name: '청자 잔', material: 'ceramic', color: 0x9dbaa2,
    layers: [L([[2.8, 1.8]]), ...solid(1, 3.0), ...walls(taper(8, 3.2, 3.6), 1)],
    // 굽은 흙빛, 몸통엔 빙렬처럼 옅은 무늬
    paint: ({ y, h, edge }) => {
      if (y < 1) return 0xb08a62;
      if (edge && y === 9) return 0xb6cdb8;
      return h < 0.08 ? 0x86a68c : null;
    },
  },
  // 폭탄주 안에 떨어뜨리는 작은 잔 (목록에 노출하지 않음)
  {
    id: 'dropshot', name: '샷', material: 'thick', hidden: true,
    layers: [...solid(2, 1.9), ...walls(taper(4, 2.05, 2.2))],
  },
];

// ── 술 ────────────────────────────────────────────────────
export const CATEGORIES = [
  { id: 'beer', name: '맥주' },
  { id: 'wine', name: '와인' },
  { id: 'whisky', name: '위스키' },
  { id: 'cocktail', name: '칵테일' },
  { id: 'korean', name: '소주 · 전통주' },
  { id: 'bomb', name: '폭탄주' },
  { id: 'soft', name: '차 · 음료' },
];

const BEER_FOAM = { h: 2.4, color: 0xfff6e2 };

// 따르는 그릇과 그 안의 술. stream: 따를 때 보이는 색, carb: 탄산
const V = {
  soju: { v: 'soju', stream: 0xeaf3f6 },
  beer: { v: 'beer', stream: 0xe5a227, carb: 0.7 },
  whisky: { v: 'whisky', stream: 0xc06a1e },
  makgeolli: { v: 'makgeolli', stream: 0xece4d2 },
  soda: { v: 'can', color: 0xe6edf0, label: 0x3f7fc0, stream: 0xeef7f6, carb: 1 },
  tonic: { v: 'can', color: 0xf2efe2, label: 0xd9a13a, stream: 0xeef5f2, carb: 1 },
  gingerbeer: { v: 'can', color: 0xd9a441, label: 0x2e5a3a, stream: 0xe9dfb6, carb: 0.8 },
  cider: { v: 'can', color: 0x2f8f4e, stream: 0xeef7ee, carb: 1 },
  gin: { v: 'spirit', color: 0xcfe6e0, label: 0x2f5d8a, stream: 0xf2f6f4 },
  vodka: { v: 'spirit', color: 0xe6eef2, label: 0xc8202f, stream: 0xf4f6f6 },
  rum: { v: 'spirit', color: 0xe8efe8, label: 0x3f6f9a, stream: 0xf4f4ee },
  tequila: { v: 'spirit', color: 0xd9e6dc, label: 0x3f7a3a, stream: 0xf1e7c4 },
  juice: { v: 'carton', stream: 0xf5a623 },
  grenadine: { v: 'liqueur', color: 0x8a0f22, label: 0xf2e3c0, stream: 0xb5122b },
  kahlua: { v: 'liqueur', color: 0x2a160b, label: 0xf2d27a, stream: 0x3a1a0a },
  baileys: { v: 'liqueur', color: 0x2b2b30, label: 0xc9a24e, stream: 0xe6d3ac },
  grandmarnier: { v: 'liqueur', color: 0x9a4a12, label: 0xb3261e, stream: 0xd9862a },
  baekseju: { v: 'liqueur', color: 0x6b4a1e, label: 0xe8d6a0, stream: 0xd8b04a },
  coffee: { v: 'carafe', stream: 0x2a140a },
  cream: { v: 'creamer', stream: 0xf4ecd8 },
  hotwater: { v: 'teapot', stream: 0xf2ead2 },
  // 칵테일 재료
  cola: { v: 'can', color: 0xc4161c, label: 0xf3efe6, stream: 0x3a1a0b, carb: 0.8 },
  prosecco: { v: 'champagne', color: 0x3f5a34, label: 0xf3e7c9, stream: 0xf3e2a0, carb: 1 },
  bourbon: { v: 'whisky', color: 0x8a3e12, label: 0xefe2c4, stream: 0xb0601c },
  limejuice: { v: 'liqueur', color: 0x7fae3a, label: 0xf3efe6, stream: 0xe3eca8 },
  lemonjuice: { v: 'liqueur', color: 0xe8cf3a, label: 0xf3efe6, stream: 0xf6eeb0 },
  syrup: { v: 'liqueur', color: 0xece4d0, label: 0x8a5a2a, stream: 0xf6efd8 },
  triplesec: { v: 'liqueur', color: 0xe8781e, label: 0xf3efe6, stream: 0xf6eedc },
  curacao: { v: 'liqueur', color: 0x1f6fb8, label: 0xf3efe6, stream: 0x2aa4d6 },
  dryvermouth: { v: 'wine', color: 0xc9d6b8, label: 0x2f5d3a, stream: 0xe9e6c8 },
  sweetvermouth: { v: 'wine', color: 0x4a1410, label: 0xe8d6a0, stream: 0x7a2412 },
  campari: { v: 'liqueur', color: 0xb5121c, label: 0xf3efe6, stream: 0xc8202f },
  aperol: { v: 'liqueur', color: 0xe8581e, label: 0xf3efe6, stream: 0xf0702a },
  bitters: { v: 'liqueur', color: 0x5a2a12, label: 0xf2e3c0, stream: 0x6a2a12, dash: true },
  espresso: { v: 'creamer', color: 0x2b2420, stream: 0x24110a },
  eggwhite: { v: 'creamer', color: 0xf4efe4, stream: 0xf6f0de },
  coconut: { v: 'carton', color: 0xf8f4ea, label: 0x6b4a2a, stream: 0xf4ecd8 },
  pineapple: { v: 'carton', color: 0xf8f4ea, label: 0xf3c42a, stream: 0xf4d65a },
  cranberry: { v: 'carton', color: 0xf8f4ea, label: 0xc8202f, stream: 0xc8203a },
  orangejuice: { v: 'carton', stream: 0xf5a623 },
};
// 셰이커·믹싱 글라스·블렌더에 재료를 차례로 넣고 만든다
const shake = (stream, ...build) => ({ v: 'shaker', stream, build });
const stir = (stream, ...build) => ({ v: 'mixglass', stream, build });
const blend = (stream, ...build) => ({ v: 'blender', stream, build });
const to = (step, f, extra) => ({ ...step, to: f, ...extra });

export const DRINKS = [
  // 맥주
  { id: 'lager', cat: 'beer', name: '라거', desc: '황금빛으로 차갑게, 첫 잔은 역시.', color: 0xe5a227, op: 0.78, fill: 0.8, carb: 0.7, foam: BEER_FOAM, glass: 'pint', bottle: 'beer' },
  { id: 'draft', cat: 'beer', name: '생맥주', desc: '퇴근길 호프집, 피처에서 500cc 한 잔.', color: 0xeaa92b, op: 0.78, fill: 0.78, carb: 0.75, foam: { h: 3, color: 0xfffaf0 }, glass: 'jug', bottle: 'pitcher' },
  { id: 'stout', cat: 'beer', name: '흑맥주', desc: '거품이 아래로 가라앉는 걸 구경해 보세요.', color: 0x2b170d, op: 0.93, fill: 0.8, carb: 0.5, bubble: 'cascade', bubbleColor: 0xc9a77c, foam: { h: 2.2, color: 0xe6cfa6 }, glass: 'pint', bottle: 'beer' },
  { id: 'wheat', cat: 'beer', name: '밀맥주', desc: '뿌옇고 부드러운, 바나나 향.', color: 0xeec157, op: 0.9, fill: 0.78, carb: 0.6, foam: { h: 3.4, color: 0xfffbf2 }, garnish: ['lemon'], glass: 'weizen', bottle: 'beer' },
  { id: 'ipa', cat: 'beer', name: 'IPA', desc: '쌉쌀한 홉 향, 진한 호박색.', color: 0xd4842a, op: 0.86, fill: 0.8, carb: 0.6, foam: { h: 2.2, color: 0xfff0d6 }, glass: 'pint', bottle: 'beer', bottleColor: 0x4a2a10 },
  { id: 'kriek', cat: 'beer', name: '체리 맥주', desc: '새콤한 체리, 거품까지 분홍.', color: 0x9e1f30, op: 0.86, fill: 0.78, carb: 0.6, foam: { h: 2.6, color: 0xf6d2d2 }, glass: 'pilsner', bottle: 'beer', bottleColor: 0x2e1a14 },

  // 와인
  { id: 'red', cat: 'wine', name: '레드 와인', desc: '천천히 돌려 가며 한 모금.', color: 0x5e0b1a, op: 0.9, fill: 0.45, glass: 'wine', bottle: 'wine', after: 'swirl' },
  { id: 'white', cat: 'wine', name: '화이트 와인', desc: '차갑게, 가볍게.', color: 0xead98a, op: 0.5, fill: 0.48, glass: 'wine', bottle: 'wine', bottleColor: 0x8fae6b, after: 'swirl' },
  { id: 'rose', cat: 'wine', name: '로제 와인', desc: '분홍빛 저녁.', color: 0xf09a9c, op: 0.65, fill: 0.48, glass: 'wine', bottle: 'wine', bottleColor: 0xe9c9c3, after: 'swirl' },
  { id: 'champagne', cat: 'wine', name: '샴페인', desc: '기포가 줄지어 올라와요.', color: 0xf3dc8c, op: 0.52, fill: 0.82, carb: 1, bubble: 'stream', foam: { h: 0.8, color: 0xfff8e3 }, glass: 'flute', bottle: 'champagne' },
  { id: 'vinchaud', cat: 'wine', name: '뱅쇼', desc: '계피 향 나는 따뜻한 와인.', color: 0x6d1222, op: 0.95, fill: 0.8, hot: true, garnish: ['orange', 'cinnamon'], glass: 'mug', bottle: 'teapot' },
  { id: 'sangria', cat: 'wine', name: '상그리아', desc: '과일 조각이 둥둥, 얼음 가득.', color: 0x7a1426, op: 0.86, fill: 0.86, ice: 2, floaters: 'fruit', garnish: ['orange'], glass: 'highball', pour: [{ v: 'pitcher', noBand: true, stream: 0x7a1426 }] },

  // 위스키
  { id: 'neat', cat: 'whisky', name: '스트레이트', desc: '아무것도 섞지 않고, 향부터 맡고 한 모금.', color: 0xc06a1e, op: 0.75, fill: 0.42, glass: 'glencairn', bottle: 'whisky' },
  { id: 'ontherocks', cat: 'whisky', name: '온더락', desc: '동그란 얼음 하나, 천천히 녹여 가며.', color: 0xb9661c, op: 0.7, fill: 0.62, ice: 1, iceShape: 'sphere', glass: 'rocks', bottle: 'whisky' },
  { id: 'highball', cat: 'whisky', name: '하이볼', desc: '얼음 위에 위스키, 탄산수는 잔 벽을 타고 살살.', color: 0xe3b866, op: 0.5, fill: 0.88, carb: 0.8, ice: 3, garnish: ['lemon'], glass: 'highball', pour: [to(V.whisky, 0.3), V.soda] },
  { id: 'hottoddy', cat: 'whisky', name: '핫 토디', desc: '위스키에 꿀과 레몬, 뜨거운 물. 감기 기운 있을 때.', color: 0xd59a3c, op: 0.75, fill: 0.82, hot: true, garnish: ['lemon', 'cinnamon'], glass: 'mug', pour: [to(V.whisky, 0.3), V.hotwater] },
  { id: 'oldfashioned', cat: 'whisky', name: '올드 패션드', desc: '각설탕 하나, 오렌지 껍질 한 줄.', color: 0xa8521a, op: 0.8, fill: 0.6, ice: 1, iceShape: 'sphere', garnish: ['orange', 'cherry'], glass: 'rocks', bottle: 'whisky', after: 'stir' },
  { id: 'irish', cat: 'whisky', name: '아이리시 커피', desc: '위스키 넣은 커피, 위엔 크림 한 겹.', color: 0x2e1609, op: 1, fill: 0.86, hot: true, gradient: [[0, 0x24110a], [0.72, 0x3a1c0b], [0.76, 0xeee2c8], [1, 0xf7efdc]], layered: true, glass: 'irish', pour: [to(V.coffee, 0.62), to(V.whisky, 0.74), { ...V.cream, layer: true }] },

  // 칵테일
  { id: 'martini', cat: 'cocktail', name: '마티니', desc: '믹싱 글라스에 저어서, 올리브 하나 콕.', color: 0xe7ead2, op: 0.42, fill: 0.85, garnish: ['olive'], glass: 'martini', pour: [stir(0xe7ead2, V.gin, V.dryvermouth)] },
  { id: 'mojito', cat: 'cocktail', name: '모히토', desc: '민트, 라임, 그리고 얼음.', color: 0xdcefc4, op: 0.42, fill: 0.88, carb: 0.5, ice: 3, floaters: 'mint', garnish: ['lime', 'straw'], glass: 'highball', pour: [to(V.rum, 0.35), V.soda], after: 'stir' },
  { id: 'margarita', cat: 'cocktail', name: '마가리타', desc: '잔 테두리엔 소금.', color: 0xe4ec9a, op: 0.6, fill: 0.82, garnish: ['salt', 'lime'], glass: 'coupe', pour: [shake(0xe4ec9a, V.tequila, V.triplesec, V.limejuice)] },
  { id: 'bluehawaii', cat: 'cocktail', name: '블루 하와이', desc: '작은 우산이 꽂혀 있어요.', color: 0x2aa4d6, op: 0.68, fill: 0.88, ice: 3, garnish: ['umbrella', 'cherry'], glass: 'highball', pour: [shake(0x2aa4d6, V.rum, V.curacao, V.pineapple)] },
  { id: 'sunrise', cat: 'cocktail', name: '데킬라 선라이즈', desc: '아래부터 노을빛.', color: 0xf39a1e, op: 0.85, fill: 0.88, ice: 2, gradient: [[0, 0xb5122b], [0.3, 0xe8481f], [0.6, 0xf39a1e], [1, 0xf7c044]], garnish: ['orange', 'cherry'], glass: 'highball', pour: [to(V.tequila, 0.25), to(V.juice, 0.92, { tint: 0xf39a1e }), { ...V.grenadine, tint: 0xf39a1e, sink: true, sinkFrom: 0xf39a1e }] },
  { id: 'gintonic', cat: 'cocktail', name: '진토닉', desc: '얼음 가득 진, 토닉워터로 채우기.', color: 0xeef5f2, op: 0.3, fill: 0.88, carb: 0.8, ice: 3, garnish: ['lime'], glass: 'highball', pour: [to(V.gin, 0.3), V.tonic] },
  { id: 'negroni', cat: 'cocktail', name: '네그로니', desc: '쌉싸름하고 달큰한 붉은 한 잔.', color: 0x9c1a16, op: 0.9, fill: 0.6, ice: 1, iceShape: 'sphere', garnish: ['orange'], glass: 'rocks', pour: [stir(0x9c1a16, V.gin, V.campari, V.sweetvermouth)] },
  { id: 'cosmo', cat: 'cocktail', name: '코스모폴리탄', desc: '크랜베리 빛, 라임 한 조각.', color: 0xe0436a, op: 0.65, fill: 0.82, garnish: ['lime'], glass: 'martini', pour: [shake(0xe0436a, V.vodka, V.triplesec, V.cranberry, V.limejuice)] },
  { id: 'pinacolada', cat: 'cocktail', name: '피냐 콜라다', desc: '코코넛, 파인애플, 얼음을 블렌더로 갈아서.', color: 0xf4ecd4, op: 1, fill: 0.88, garnish: ['umbrella', 'cherry'], glass: 'highball', pour: [blend(0xf4ecd4, V.rum, V.coconut, V.pineapple)] },
  { id: 'mule', cat: 'cocktail', name: '모스코 뮬', desc: '구리 잔에 진저비어, 라임 한 조각.', color: 0xe9dfb6, op: 0.5, fill: 0.86, carb: 0.7, ice: 3, garnish: ['lime'], glass: 'copper', pour: [to(V.vodka, 0.25), V.gingerbeer] },
  { id: 'b52', cat: 'cocktail', name: 'B-52', desc: '바 스푼 위로 세 겹을 살살 띄우고 불을 붙여요. 빨대로 단숨에.', color: 0x8a4a1c, op: 1, fill: 0.86, flame: true, layered: true, gradient: [[0, 0x3a1a0a], [0.32, 0x3a1a0a], [0.36, 0xe6d3ac], [0.64, 0xe6d3ac], [0.68, 0xd9862a], [1, 0xd9862a]], glass: 'shot', pour: [{ ...V.kahlua, to: 0.33, layer: true }, { ...V.baileys, to: 0.66, layer: true }, { ...V.grandmarnier, layer: true }], shot: true },
  { id: 'daiquiri', cat: 'cocktail', name: '다이키리', desc: '럼, 라임, 설탕 시럽. 셰이커로 차갑게.', color: 0xeef0c8, op: 0.5, fill: 0.82, garnish: ['lime'], glass: 'coupe', pour: [shake(0xeef0c8, V.rum, V.limejuice, V.syrup)] },
  { id: 'gimlet', cat: 'cocktail', name: '김렛', desc: '진과 라임, 새콤하고 깔끔하게.', color: 0xe2edb2, op: 0.5, fill: 0.82, garnish: ['lime'], glass: 'coupe', pour: [shake(0xe2edb2, V.gin, V.limejuice, V.syrup)] },
  { id: 'whiskysour', cat: 'cocktail', name: '위스키 사워', desc: '흰자를 넣고 흔들어 위에 폭신한 거품.', color: 0xe8c062, op: 0.85, fill: 0.62, ice: 2, foam: { h: 1.6, color: 0xfbf3dc }, garnish: ['cherry'], glass: 'rocks', pour: [shake(0xe8c062, V.bourbon, V.lemonjuice, V.syrup, V.eggwhite)] },
  { id: 'manhattan', cat: 'cocktail', name: '맨해튼', desc: '버번, 스위트 베르무트, 비터스 몇 방울. 저어서.', color: 0x8a2a12, op: 0.86, fill: 0.82, garnish: ['cherry'], glass: 'coupe', pour: [stir(0x8a2a12, V.bourbon, V.sweetvermouth, V.bitters)] },
  { id: 'espressomartini', cat: 'cocktail', name: '에스프레소 마티니', desc: '세게 흔들어 크레마 같은 거품, 커피콩 세 알.', color: 0x2a140a, op: 1, fill: 0.8, foam: { h: 1.3, color: 0xc9a172 }, garnish: ['beans'], glass: 'martini', pour: [shake(0x2a140a, V.vodka, V.kahlua, V.espresso)] },
  { id: 'longisland', cat: 'cocktail', name: '롱아일랜드 아이스티', desc: '홍차는 한 방울도 없는데 홍차 색. 네 가지 술에 콜라.', color: 0xa8622a, op: 0.82, fill: 0.88, carb: 0.5, ice: 3, garnish: ['lemon', 'straw'], glass: 'highball', pour: [{ ...shake(0xd8b070, V.vodka, V.gin, V.rum, V.tequila, V.triplesec), to: 0.7 }, { ...V.cola, tint: 0xa8622a }] },
  { id: 'cubalibre', cat: 'cocktail', name: '쿠바 리브레', desc: '얼음에 럼, 콜라, 라임 한 조각.', color: 0x4a2412, op: 0.9, fill: 0.86, carb: 0.8, ice: 3, garnish: ['lime'], glass: 'highball', pour: [to(V.rum, 0.3), V.cola] },
  { id: 'whiterussian', cat: 'cocktail', name: '화이트 러시안', desc: '보드카와 커피 리큐어 위에 크림을 살살.', color: 0x6a3a1c, op: 1, fill: 0.7, ice: 2, layered: true, gradient: [[0, 0x3a1c0c], [0.62, 0x4a2410], [0.68, 0xe9dcc2], [1, 0xf4ecd8]], glass: 'rocks', pour: [to(V.vodka, 0.3), to(V.kahlua, 0.64), { ...V.cream, layer: true }] },
  { id: 'aperolspritz', cat: 'cocktail', name: '아페롤 스프리츠', desc: '와인 잔에 얼음, 프로세코 펑, 아페롤, 탄산수 조금.', color: 0xf0702a, op: 0.62, fill: 0.86, carb: 0.8, ice: 3, garnish: ['orange'], glass: 'wine', pour: [to(V.prosecco, 0.48), to(V.aperol, 0.84, { tint: 0xf0702a }), { ...V.soda, tint: 0xf0702a }] },
  { id: 'tequila', cat: 'cocktail', name: '데킬라 샷', desc: '소금 한 번 핥고, 라임 한 입.', color: 0xf1e7c4, op: 0.42, fill: 0.85, garnish: ['salt', 'lime'], glass: 'shot', pour: [V.tequila], shot: true },

  // 소주 · 전통주
  { id: 'soju', cat: 'korean', name: '소주', desc: '역시 이거지.', color: 0xeaf3f6, op: 0.3, fill: 0.85, glass: 'soju', bottle: 'soju', shot: true },
  { id: 'makgeolli', cat: 'korean', name: '막걸리', desc: '양은 사발에 콸콸.', color: 0xece4d2, op: 1, fill: 0.82, cloudy: true, glass: 'bowl', bottle: 'makgeolli' },
  { id: 'dongdongju', cat: 'korean', name: '동동주', desc: '밥알이 동동.', color: 0xefe7cf, op: 0.95, fill: 0.82, floaters: 'rice', glass: 'bowl', bottle: 'kettle' },
  { id: 'bokbunja', cat: 'korean', name: '복분자주', desc: '진한 산딸기 빛.', color: 0x4b0a22, op: 0.88, fill: 0.85, glass: 'soju', pour: [{ v: 'wine', color: 0x3a1420, label: 0xe8d6a0, stream: 0x4b0a22 }], shot: true },
  { id: 'maesil', cat: 'korean', name: '매실주', desc: '새콤달콤 매실 향.', color: 0xe6d27a, op: 0.55, fill: 0.85, glass: 'soju', pour: [{ v: 'spirit', color: 0x7fa04a, label: 0xf2ead2, stream: 0xe6d27a }], shot: true },
  { id: 'sake', cat: 'korean', name: '사케', desc: '작은 잔에 조금씩.', color: 0xf2f0de, op: 0.3, fill: 0.8, glass: 'ochoko', bottle: 'tokkuri', shot: true },
  { id: 'atsukan', cat: 'korean', name: '따뜻한 사케', desc: '데워 마시는 사케, 아츠칸.', color: 0xf3eed6, op: 0.34, fill: 0.8, hot: true, glass: 'ochoko', bottle: 'tokkuri', shot: true },
  { id: 'cheongju', cat: 'korean', name: '청주', desc: '맑게 걸러 낸 술, 은은한 쌀 향.', color: 0xf0e2a8, op: 0.42, fill: 0.8, glass: 'celadon', bottle: 'tokkuri', bottleColor: 0xdfe8de },
  { id: 'gaoliang', cat: 'korean', name: '고량주', desc: '작은 잔에 독하게. 짜장면 생각나요.', color: 0xf4f6f0, op: 0.24, fill: 0.82, glass: 'shot', pour: [{ v: 'liqueur', color: 0xe6dcc6, label: 0xc8202f, stream: 0xf4f6f0 }], shot: true },

  // 폭탄주
  { id: 'somaek', cat: 'bomb', name: '소맥', desc: '소주 하나에 맥주 셋, 회오리는 덤.', color: 0xf0c460, op: 0.72, fill: 0.85, carb: 1, foam: { h: 2.6, color: 0xfff7e6 }, bomb: { type: 'tornado' }, glass: 'somaek', pour: [to(V.soju, 0.3), V.beer] },
  { id: 'yangpok', cat: 'bomb', name: '양폭', desc: '맥주에 위스키 잔이 퐁당.', color: 0xe3a02a, op: 0.78, fill: 0.8, carb: 0.8, foam: BEER_FOAM, bomb: { type: 'drop', shot: 0xb0601c }, glass: 'jug', bottle: 'beer' },
  { id: 'jagerbomb', cat: 'bomb', name: '예거밤', desc: '잔째로 떨어뜨려요.', color: 0xf1d23a, op: 0.72, fill: 0.78, carb: 0.9, bomb: { type: 'drop', shot: 0x3a1c0e }, glass: 'pint', bottle: 'can', canColor: 0x2b4fa8 },
  { id: 'gojingamrae', cat: 'bomb', name: '고진감래', desc: '쓴맛 뒤에 콜라의 단맛.', color: 0xe8a830, op: 0.78, fill: 0.8, carb: 0.7, foam: BEER_FOAM, bomb: { type: 'drop', shot: 0x2e140a }, glass: 'pint', bottle: 'beer' },
  { id: 'maksa', cat: 'bomb', name: '막사', desc: '막걸리에 사이다, 톡 쏘게.', color: 0xefe9da, op: 0.92, fill: 0.82, carb: 0.8, cloudy: true, glass: 'bowl', pour: [to(V.makgeolli, 0.6), V.cider] },
  { id: 'sopok', cat: 'bomb', name: '소폭', desc: '맥주잔 속으로 소주잔이 풍덩.', color: 0xeab040, op: 0.76, fill: 0.8, carb: 0.9, foam: BEER_FOAM, bomb: { type: 'drop', shot: 0xe8f1f3 }, glass: 'pint', bottle: 'beer' },
  { id: 'osipseju', cat: 'bomb', name: '오십세주', desc: '소주 반, 약주 반. 살살 저어서.', color: 0xdcbc5a, op: 0.62, fill: 0.85, bomb: { type: 'stir' }, glass: 'soju', pour: [to(V.soju, 0.5), V.baekseju], shot: true },

  // 차 · 음료
  { id: 'tea', cat: 'soft', name: '따뜻한 차', desc: '김이 모락모락.', color: 0xc4b158, op: 0.65, fill: 0.82, hot: true, garnish: ['teabag'], glass: 'teacup', bottle: 'teapot' },
  { id: 'yuja', cat: 'soft', name: '유자차', desc: '건더기까지 달달하게.', color: 0xf0b43a, op: 0.8, fill: 0.82, hot: true, floaters: 'peel', glass: 'mug', bottle: 'teapot' },
  { id: 'cocoa', cat: 'soft', name: '핫초코', desc: '마시멜로가 동동.', color: 0x5e3520, op: 1, fill: 0.82, hot: true, floaters: 'marshmallow', glass: 'mug', bottle: 'teapot' },
  { id: 'cola', cat: 'soft', name: '콜라', desc: '얼음 가득, 톡 쏘게.', color: 0x3a1a0b, op: 0.92, fill: 0.86, carb: 0.8, ice: 3, foam: { h: 0.7, color: 0xb98d62 }, glass: 'highball', bottle: 'can', canColor: 0xc4161c },
  { id: 'cider', cat: 'soft', name: '사이다', desc: '투명하게 톡톡.', color: 0xeef7ee, op: 0.26, fill: 0.86, carb: 1, ice: 2, glass: 'highball', bottle: 'can', canColor: 0x2f8f4e },
  { id: 'sikhye', cat: 'soft', name: '식혜', desc: '밥알 동동, 살얼음 살짝.', color: 0xefe0bb, op: 0.72, fill: 0.84, floaters: 'rice', glass: 'rocks', bottle: 'can', canColor: 0xd2a94e },
  { id: 'greentea', cat: 'soft', name: '녹차', desc: '연둣빛, 따뜻하게 한 모금.', color: 0xa6b54c, op: 0.6, fill: 0.8, hot: true, glass: 'celadon', bottle: 'teapot' },
  { id: 'coffee', cat: 'soft', name: '따뜻한 커피', desc: '김 나는 핸드드립 한 잔.', color: 0x2a140a, op: 0.97, fill: 0.82, hot: true, glass: 'mug', bottle: 'carafe' },
  { id: 'iceame', cat: 'soft', name: '아이스 아메리카노', desc: '한겨울에도 얼음 가득.', color: 0x2a1408, op: 0.95, fill: 0.86, ice: 4, garnish: ['straw'], glass: 'highball', bottle: 'carafe' },
];

// ── 같은 술, 다른 상품 ────────────────────────────────────
// 실제로 파는 상품 중에서 고른다. step: 따르는 그릇(병·캔) 모양 바꾸기 (at번째 따르기, 기본 0),
// drink: 잔에 담긴 술 색 같은 것 바꾸기. 로고는 그리지 않고 병·캔 색과 띠 색으로만 구분한다.
const SOJU_PREP = { v: 'soju', prep: 'swirl' };
const VARIANTS = {
  lager: [
    { id: 'cass', name: '카스', desc: '시원하게 톡 쏘는 맛.', step: { color: 0x5a3410, label: 0x2f5d9c, cap: 0x2f5d9c } },
    { id: 'terra', name: '테라', desc: '초록 병, 청량한 탄산.', step: { color: 0x2f6e3a, label: 0xd8c47a, cap: 0xc9a23a } },
    { id: 'kelly', name: '켈리', desc: '부드럽고 진한 라거.', step: { color: 0x7a4a16, label: 0xd9b24a, cap: 0xc9a23a } },
    { id: 'heineken', name: '하이네켄', desc: '초록 병의 네덜란드 라거.', step: { color: 0x2f7a3a, label: 0xf3efe6, cap: 0x2f6e3a } },
    { id: 'asahi', name: '아사히 슈퍼 드라이', desc: '깔끔하게 떨어지는 드라이.', step: { color: 0x4a2a10, label: 0xc9cdd0, cap: 0xc9cdd0 }, drink: { color: 0xecb63a } },
    { id: 'budweiser', name: '버드와이저', desc: '가볍고 부드러운 미국 라거.', step: { color: 0x5a3410, label: 0xc8202f, cap: 0xc8202f } },
  ],
  wheat: [
    { id: 'hoegaarden', name: '호가든', desc: '오렌지 껍질과 고수 향.', step: { color: 0xdfe6ea, label: 0x6fa8d8, cap: 0x6fa8d8 }, drink: { color: 0xf3d77a } },
    { id: 'bluemoon', name: '블루문', desc: '오렌지 한 조각 꽂아서.', step: { color: 0x5a3410, label: 0x2f4f8a, cap: 0x2f4f8a } },
    { id: 'paulaner', name: '파울라너 헤페바이스', desc: '뮌헨 밀맥주, 바나나 향.', step: { color: 0x5a3410, label: 0xf3efe6, cap: 0x2f5d9c }, drink: { color: 0xe6a83a } },
  ],
  stout: [
    { id: 'guinness', name: '기네스 드래프트', desc: '크림 같은 거품이 가라앉아요.', step: { v: 'can', color: 0x1c1a1a, label: 0xe8d6a0 } },
  ],
  red: [
    { id: 'montes', name: '몬테스 알파 카베르네 소비뇽', desc: '칠레, 진하고 묵직하게.', step: { color: 0x1f2a1c, label: 0xf2ead8, cap: 0x6e1424 } },
    { id: '1865', name: '1865 카베르네 소비뇽', desc: '칠레, 부드러운 탄닌.', step: { color: 0x1c2420, label: 0x1a1a1a, cap: 0xc9a23a } },
    { id: 'yellowtail', name: '옐로우테일 쉬라즈', desc: '호주, 과일 향 가득.', step: { color: 0x233d26, label: 0xf2d27a, cap: 0x1a1a1a }, drink: { color: 0x6a0f22 } },
  ],
  champagne: [
    { id: 'moet', name: '모엣 샹동 임페리얼', desc: '가장 흔히 터뜨리는 샴페인.', step: { color: 0x2f4a2a, label: 0xf3efe6, cap: 0xd9b452 } },
    { id: 'veuve', name: '뵈브 클리코', desc: '노란 라벨, 묵직한 기포.', step: { color: 0x2a3a24, label: 0xf0a020, cap: 0xf0a020 } },
    { id: 'dom', name: '돔 페리뇽', desc: '특별한 날에.', step: { color: 0x1c2a1c, label: 0xe8d6a0, cap: 0x1a1a1a } },
  ],
  neat: [
    { id: 'jwblack', name: '조니워커 블랙 라벨', desc: '스모키한 블렌디드 스카치.', step: { color: 0x3a2410, label: 0x1a1a1a, cap: 0x1a1a1a } },
    { id: 'ballantines17', name: '발렌타인 17년', desc: '부드럽고 달큰한 스카치.', step: { color: 0x6b3a12, label: 0xe8d6a0, cap: 0xc9a23a } },
    { id: 'macallan12', name: '맥캘란 12년', desc: '셰리 오크 싱글 몰트.', step: { color: 0xa8621c, label: 0xf2ead8, cap: 0x1a1a1a }, drink: { color: 0xb8561a } },
    { id: 'glenfiddich12', name: '글렌피딕 12년', desc: '서양배 향 싱글 몰트.', step: { color: 0x2f5a3a, label: 0xe9e2d3, cap: 0x1a1a1a }, drink: { color: 0xd08a2a } },
  ],
  ontherocks: [
    { id: 'jwblack', name: '조니워커 블랙 라벨', desc: '스모키한 블렌디드 스카치.', step: { color: 0x3a2410, label: 0x1a1a1a, cap: 0x1a1a1a } },
    { id: 'jackdaniels', name: '잭 다니엘', desc: '테네시 위스키, 바닐라 향.', step: { color: 0x2a1a10, label: 0x1a1a1a, cap: 0x1a1a1a } },
    { id: 'macallan12', name: '맥캘란 12년', desc: '셰리 오크 싱글 몰트.', step: { color: 0xa8621c, label: 0xf2ead8, cap: 0x1a1a1a } },
  ],
  highball: [
    { id: 'kakubin', name: '산토리 가쿠빈', desc: '일본 이자카야의 그 하이볼.', step: { color: 0xc8902a, label: 0xf3efe6, cap: 0xf3efe6 }, drink: { color: 0xead08a } },
    { id: 'jimbeam', name: '짐빔', desc: '버번 하이볼, 달큰하게.', step: { color: 0x8a4a12, label: 0xf2ead8, cap: 0x1a1a1a } },
    { id: 'jackdaniels', name: '잭 다니엘', desc: '잭콕 말고 잭 하이볼.', step: { color: 0x2a1a10, label: 0x1a1a1a, cap: 0x1a1a1a } },
  ],
  gintonic: [
    { id: 'bombay', name: '봄베이 사파이어', desc: '파란 병, 향긋한 진.', step: { color: 0x3a6fc8, label: 0xe8eef6, cap: 0x2a3a6a } },
    { id: 'tanqueray', name: '탱커레이', desc: '초록 병, 쨍한 주니퍼.', step: { color: 0x2f6e3a, label: 0xc8202f, cap: 0x1a1a1a } },
    { id: 'hendricks', name: '헨드릭스', desc: '오이 한 조각과 함께.', step: { color: 0x2a2420, label: 0xe9e2d3, cap: 0x1a1a1a } },
    { id: 'beefeater', name: '비피터', desc: '런던 드라이 진의 기본.', step: { color: 0xdfeae6, label: 0xc8202f, cap: 0xc8202f } },
  ],
  soju: [
    { id: 'chamisul', name: '참이슬 후레쉬', desc: '가장 많이 마시는 그 초록 병.', step: { ...SOJU_PREP, color: 0x3f8f4f, label: 0xe8f2ec, cap: 0x2f6e3c } },
    { id: 'cheoeum', name: '처음처럼', desc: '부드럽게 넘어가요.', step: { ...SOJU_PREP, color: 0x4a9a52, label: 0xd9eef2, cap: 0x2f6e3c } },
    { id: 'jinro', name: '진로', desc: '하늘색 병, 두꺼비.', step: { ...SOJU_PREP, color: 0x7fc4d8, label: 0xeaf6f8, cap: 0x3f8fb0 } },
    { id: 'saero', name: '새로', desc: '투명한 병, 제로 슈거.', step: { ...SOJU_PREP, color: 0xdfeaee, label: 0x9a9a9a, cap: 0x8a8a8a } },
    { id: 'goodday', name: '좋은데이', desc: '부산 소주, 순하게.', step: { ...SOJU_PREP, color: 0x3f8f4f, label: 0xf2c6d0, cap: 0x2f6e3c } },
  ],
  makgeolli: [
    { id: 'jangsu', name: '장수 생막걸리', desc: '서울 막걸리의 기본.', step: { color: 0xf2efe6, label: 0x3c7a46, cap: 0x2f6e3c } },
    { id: 'jipyeong', name: '지평 생막걸리', desc: '달달하고 톡 쏘는.', step: { color: 0xf2efe6, label: 0xd9b24a, cap: 0x2f5d8a } },
    { id: 'neurin', name: '느린마을 막걸리', desc: '단맛 없이 쌀 향만.', step: { color: 0xf2efe6, label: 0x8a5a2a, cap: 0x8a5a2a }, drink: { color: 0xeee6d0 } },
  ],
  cola: [
    { id: 'coke', name: '코카콜라', desc: '빨간 캔, 원래 그 맛.', step: { color: 0xd8141c, label: 0xf3efe6 } },
    { id: 'cokezero', name: '코카콜라 제로', desc: '검은 캔, 설탕 없이.', step: { color: 0x1c1a1a, label: 0xd8141c } },
    { id: 'pepsi', name: '펩시', desc: '파란 캔.', step: { color: 0x1f4fb0, label: 0xf3efe6 } },
    { id: 'pepsizerolime', name: '펩시 제로 라임', desc: '검은 캔에 라임 향.', step: { color: 0x141820, label: 0x9ed43a } },
  ],
  cider: [
    { id: 'chilsung', name: '칠성사이다', desc: '맑고 깨끗하게.', step: { color: 0x2f8f4e, label: 0xf3efe6 } },
    { id: 'sprite', name: '스프라이트', desc: '레몬 라임 향.', step: { color: 0x14905a, label: 0xf2d22a } },
    { id: 'chilsungzero', name: '칠성사이다 제로', desc: '설탕 없이 톡.', step: { color: 0x1c2a22, label: 0x3fbf6a } },
  ],
  jagerbomb: [
    { id: 'redbull', name: '레드불', desc: '은색과 파란색 캔.', step: { color: 0x2b3f9a, label: 0xc9cdd0 } },
    { id: 'monster', name: '몬스터 에너지', desc: '검은 캔, 초록 줄무늬.', step: { color: 0x1a1a1a, label: 0x7ad43a }, drink: { color: 0xd8e04a } },
    { id: 'hot6', name: '핫식스', desc: '주황 캔.', step: { color: 0xe8501e, label: 0x1a1a1a } },
  ],
};
for (const d of DRINKS) if (VARIANTS[d.id]) d.variants = VARIANTS[d.id];

// 도수(%). 잔에 든 양과 함께 취기를 정한다 (게임 속 숫자)
const ABV = {
  lager: 5, draft: 4.5, stout: 4.2, wheat: 5, ipa: 6.5, kriek: 4.5,
  red: 13.5, white: 12, rose: 12, champagne: 12, vinchaud: 9, sangria: 9,
  neat: 40, ontherocks: 40, highball: 9, hottoddy: 12, oldfashioned: 32, irish: 10,
  martini: 30, mojito: 13, margarita: 20, bluehawaii: 12, sunrise: 12, gintonic: 10, negroni: 24, cosmo: 20,
  pinacolada: 13, mule: 10, b52: 26, daiquiri: 22, gimlet: 24, whiskysour: 16, manhattan: 28, espressomartini: 18,
  longisland: 22, cubalibre: 11, whiterussian: 20, aperolspritz: 9, tequila: 40,
  soju: 16.5, makgeolli: 6, dongdongju: 6, bokbunja: 15, maesil: 13, sake: 15, atsukan: 15, cheongju: 13, gaoliang: 42,
  somaek: 9, yangpok: 10, jagerbomb: 8, gojingamrae: 8, maksa: 4, sopok: 9, osipseju: 15,
};
for (const d of DRINKS) d.abv = ABV[d.id] ?? 0;

// 상품을 고른 술: 따르는 그릇과 술 색만 바꾼 사본 (id는 그대로)
const resolved = new Map();
export function resolveDrink(drinkId, variantId) {
  const base = drinkById[drinkId] || DRINKS[0];
  const v = base.variants?.find((x) => x.id === variantId) ?? null;
  if (!v) return base;
  const key = `${base.id}|${v.id}`;
  if (resolved.has(key)) return resolved.get(key);
  const steps = (base.pour ?? [{ v: base.bottle, color: base.bottleColor ?? base.canColor, prep: base.prep }]).map((st) => ({ ...st }));
  const at = v.at ?? 0;
  steps[at] = { ...steps[at], ...v.step };
  const d = { ...base, ...v.drink, pour: steps, variant: v.id, brand: v.name };
  resolved.set(key, d);
  return d;
}

export const variantOf = (drinkId, variantId) => drinkById[drinkId]?.variants?.find((x) => x.id === variantId) ?? null;

// 버튼에 없는 말 (서버에는 번호로 보낸다)
export const SAY_EXTRA = { 19: '원샷!', 20: '딸꾹!' };

export const PHRASES = ['짠!', '건배!', '위하여!', '원샷!', '오늘 수고했어', '한 잔 더?', '천천히 마셔'];

export const glassById = Object.fromEntries(GLASSES.map((g) => [g.id, g]));
export const drinkById = Object.fromEntries(DRINKS.map((d) => [d.id, d]));
