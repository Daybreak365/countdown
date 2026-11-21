// 바텐더 고르기: 서버와 브라우저가 같이 쓴다 (three.js 없이).
// venue가 없으면 어느 가게에서나 고를 수 있고, 있으면 그 가게에서만.
// look: 얼굴과 머리 (가게 옷차림은 그대로), outfit: 옷까지 통째로 (그 가게 전용 인물)

export const BARKEEPS = [
  {
    id: 'classic', name: '기본 바텐더', desc: '말린 콧수염. 가게마다 옷을 갈아입어요.',
  },
  {
    id: 'bob', name: '단발 바텐더', desc: '단정한 단발머리, 손이 빨라요.', host: '바텐더',
    look: { hairStyle: 'bob', hair: 0x2a1a14, facial: 'none', lips: 0xc0505a, skin: 0xf3cfa8, skinDark: 0xe0b48c },
    hello: ['어서 오세요! 오늘은 뭘로 드릴까요?', '오셨어요? 자리 편한 데 앉으세요.'],
  },
  {
    id: 'bear', name: '곰 사장님', desc: '덥수룩한 수염, 민머리. 말수는 적어요.', host: '사장님',
    look: { hairStyle: 'bald', hair: 0x4a2e1e, facial: 'beard', skin: 0xe2ac80, skinDark: 0xc8936a },
    hello: ['어. 왔어? 앉아.', '오늘도 한잔하러 왔구먼.'],
  },
  {
    id: 'youth', name: '알바생', desc: '모자를 거꾸로 쓴 신입. 의욕은 넘쳐요.', host: '알바생',
    look: { hairStyle: 'short', hair: 0x3a2a20, facial: 'none', cap: 0x2f6fb0, skin: 0xf3d0aa, skinDark: 0xe0b890 },
    hello: ['어서 오세요! 저 오늘 첫 출근이에요!', '안녕하세요~ 주문 도와드릴게요!'],
  },
  // ── 가게 전용 ──
  {
    id: 'gentleman', venue: 'bar', name: '노신사', desc: '하얀 머리에 외알 안경. 이 바의 첫 주인.', host: '노신사',
    look: { hair: 0xe8e4dc, facial: 'mustache', monocle: true, skin: 0xecc09a, skinDark: 0xd4a47e },
    outfit: { vest: 0x3a2a3a, tie: 0x2a1a2a, apron: null },
    hello: ['어서 오시게. 오늘은 무얼 따라 드릴까.', '허허, 반갑네. 천천히 들게.'],
  },
  {
    id: 'imo', venue: 'pocha', name: '이모님', desc: '뽀글머리 이모님. 안주는 늘 넉넉히.', host: '이모님',
    look: { hairStyle: 'perm', hair: 0x2a1e1a, facial: 'none', lips: 0xb8404a, skin: 0xf0c49c },
    outfit: { shirt: 0xe07a96, cuff: 0xe07a96, collar: 0xd06a86, vest: null, tie: null, apron: 0x5a8ab0, floral: true, band: null, pants: 0x3a3a40 },
    hello: ['아이고 왔어? 춥지? 여기 앉아.', '오늘 꼼장어 좋아~ 한 접시 줄까?'],
  },
  {
    id: 'cheer', venue: 'hof', name: '응원단장', desc: '빨간 유니폼에 뿔 머리띠. 골 들어가면 한 잔 서비스!', host: '단장님',
    look: { hairStyle: 'short', hair: 0x2a1e18, facial: 'none', horns: true, facepaint: true },
    outfit: { shirt: 0xc8242a, cuff: 0xc8242a, collar: 0xf4f4f0, vest: null, tie: null, apron: null, pants: 0x2a2a30, number: true },
    hello: ['대~한민국! 어서 오세요!', '오늘 경기 이기면 서비스 나갑니다!'],
  },
  {
    id: 'vigneron', venue: 'wine', name: '포도밭 주인', desc: '베레모에 줄무늬 셔츠. 직접 키운 포도로 담가요.', host: '주인장',
    look: { facial: 'mustache', beret: 0x2a2a3a, hair: 0x6a5a4a },
    outfit: { shirt: 0xf4f4f0, cuff: 0xf4f4f0, collar: 0xf4f4f0, stripes: 0x2a3a6a, vest: null, tie: null, scarf: 0xb3282e, apron: 0x6a2a3a, longApron: false, tastevin: false },
    hello: ['봉수아! 오늘 딱 좋은 병이 있어요.', '어서 와요, 이 해 포도가 참 달았지요.'],
  },
  {
    id: 'santa', venue: 'xmas', name: '산타', desc: '배달 끝나고 한숨 돌리러 온 산타 할아버지.', host: '산타',
    look: { hair: 0xf4f6fa, facial: 'santa', skin: 0xf0c4a8, blush: 0xf08a7a },
    outfit: { shirt: 0xb3282e, cuff: 0xf4f6fa, collar: 0xf4f6fa, vest: null, tie: null, apron: null, pants: 0xb3282e, santa: true, santaSuit: true, shoe: 0x141210, glove: 0xf4f6fa },
    hello: ['호호호! 메리 크리스마스!', '착한 손님에겐 한 잔 더! 호호호.'],
  },
  {
    id: 'gamer', venue: 'attic', name: '게이머 친구', desc: '헤드셋 낀 채로 술 따르는 친구. 한 판만 더…', host: '친구',
    look: { hairStyle: 'messy', hair: 0x2a2a3a, facial: 'none', headset: true, glasses: true },
    outfit: { shirt: 0x1c1c22, cuff: 0x1c1c22, collar: 0x1c1c22, vest: null, tie: null, apron: null, pants: 0x2a2a38, rgb: true, hood: false, beanie: null },
    hello: ['어 왔냐? 잠깐, 이 판만 끝내고.', 'ㅇㅋ 뭐 마실래? 냉장고에 다 있음.'],
  },
  {
    id: 'okami', venue: 'izakaya', name: '여주인', desc: '쪽진 머리에 비녀. 단골 이름은 다 기억해요.', host: '여주인',
    look: { hairStyle: 'bun', hair: 0x1a1414, facial: 'none', lips: 0xb83a4a, skin: 0xf3d4b4, skinDark: 0xe0bc98 },
    outfit: { shirt: 0x6a2a4a, cuff: 0x6a2a4a, collar: 0xf4f0e8, vest: null, tie: null, apron: 0xd9c08a, longApron: false, band: null, cross: null, pants: 0x6a2a4a },
    hello: ['어서 오세요. 오늘도 와 주셨네요.', '따뜻한 사케 한 잔 데워 드릴까요?'],
  },
  {
    id: 'mixologist', venue: 'rooftop', name: '믹솔로지스트', desc: '선글라스에 넘긴 머리. 칵테일엔 진심이에요.', host: '바텐더',
    look: { hairStyle: 'slick', hair: 0x1a1414, facial: 'stubble', shades: true },
    outfit: { shirt: 0xf4f4f0, cuff: 0xf4f4f0, collar: 0xf4f4f0, vest: 0x1c1c22, tie: 0x1c1c22, gold: 0xc9a24a, apron: 0x3a2a20, pants: 0x1c1c22 },
    hello: ['오늘 밤 도시는 우리 거예요. 뭘로 갈까요?', '시그니처 한 잔 말아 드릴게요.'],
  },
  {
    id: 'manager', venue: 'conv', name: '점장님', desc: '돋보기 안경에 카디건. 밤샘 손님 걱정이 많아요.', host: '점장님',
    look: { hairStyle: 'bald', hair: 0x8a8278, facial: 'mustache', glasses: true, skin: 0xecc09a, skinDark: 0xd4a47e },
    outfit: { shirt: 0x8a7a5a, cuff: 0x8a7a5a, collar: 0xf2f2ee, vest: 0x2f8f6a, tie: null, gold: 0xf2f2ee, apron: null, pants: 0x4a4a52, nametag: 0xf4f4f0 },
    hello: ['아이고 늦게까지 고생 많아요. 따뜻한 거 줄까?', '컵라면은 물 받아서 3분! 기다려요.'],
  },
];

export const barkeepById = Object.fromEntries(BARKEEPS.map((b) => [b.id, b]));
export const DEFAULT_BARKEEP = 'classic';

// 그 가게에서 고를 수 있는지
export const barkeepFits = (id, venue) => !!barkeepById[id] && (!barkeepById[id].venue || barkeepById[id].venue === venue);

// 그 가게에서 고를 수 있는 바텐더들: 기본, 어디서나 고르는 셋, 그 가게 전용 하나
export const barkeepsFor = (venue) => BARKEEPS.filter((b) => !b.venue || b.venue === venue);
