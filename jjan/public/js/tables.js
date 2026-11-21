// 같이 짠: 탁자 종류와 자리. 서버(server.js)와 브라우저가 같이 쓰는 숫자만 둔다 (three.js 없이).
// 좌표 단위는 방과 같다(복셀 ≈ 1cm). 자리의 x, z는 그 사람 잔이 놓이는 곳,
// yaw는 앉은 쪽 방향(탁자 가운데에서 그 사람 쪽을 본 각도)이다.
// 잔(반지름 ~8)과 안주 접시(반지름 ~11)가 옆 사람 것과 겹치지 않게 자리 사이를 넉넉히 둔다.

export const TABLE_TYPES = ['bar', 'square', 'round', 'long', 'biground', 'ushape'];

export const TABLES = {
  bar: { name: '바 테이블', short: '바', desc: '바텐더 앞에 둘이 나란히', cap: 2, order: [0, 1] },
  square: { name: '사각 테이블', short: '사각', desc: '마주 보고 넷까지', cap: 4, order: [0, 1, 2, 3] },
  round: { name: '원형 테이블', short: '원형', desc: '빙 둘러 여덟 명', cap: 8, order: [0, 4, 2, 6, 1, 5, 3, 7] },
  long: { name: '긴 테이블', short: '긴 탁자', desc: '양쪽으로 열두 명', cap: 12, order: [2, 8, 3, 9, 1, 7, 4, 10, 0, 6, 5, 11] },
  biground: { name: '큰 원탁', short: '큰 원탁', desc: '빙 둘러 열두 명', cap: 12, order: [0, 6, 3, 9, 1, 7, 4, 10, 2, 8, 5, 11] },
  ushape: { name: 'ㄷ자 단체석', short: 'ㄷ자', desc: '바를 보고 둘러앉는 열여섯 명', cap: 16, order: [7, 8, 6, 9, 5, 10, 4, 11, 3, 12, 2, 13, 1, 14, 0, 15] },
};

export const MAX_PLAYERS = Math.max(...Object.values(TABLES).map((t) => t.cap));

// 자리가 다 차면 한 단계 큰 탁자로 옮긴다
export const UPGRADE = { bar: 'square', square: 'round', round: 'biground', long: 'ushape', biground: 'ushape' };
// RESEAT[지금 탁자][지금 자리] = 옮겨 간 탁자의 자리. 옆 사람, 앞사람이 되도록 그대로 둔다.
const RESEAT = {
  bar: [0, 1],
  square: [0, 4, 2, 6],
  round: [0, 2, 3, 5, 6, 8, 9, 11],
  biground: [8, 10, 11, 12, 14, 15, 0, 2, 3, 4, 6, 7],
  long: [5, 6, 7, 8, 9, 10, 2, 1, 0, 15, 14, 13],
};

export function reseat(from, seat) {
  return RESEAT[from]?.[seat] ?? seat;
}

export const isTable = (t) => TABLE_TYPES.includes(t);

// 탁자 크기
// r: 상판 반지름, home: 잔 자리, chair: 의자, reach: 잔이 갈 수 있는 끝, anju: 안주 반지름과 잔에서 돌아간 각
export const ROUND = { r: 68, home: 48, chair: 96, reach: 58, anjuR: 33, anjuA: 0.33 };
export const BIGROUND = { r: 88, home: 70, chair: 114, reach: 78, anjuR: 52, anjuA: 0.23 };
export const SQUARE = { half: 36, home: 26, reach: 30 };
export const LONG = { halfX: 122, halfZ: 32, home: 22, x0: -90, gap: 36, reachX: 114, reachZ: 26 };
export const BAR_SEATS = { x0: -80, x1: 80, z1: -131 };
// ㄷ자: 앞쪽(바 맞은편) 긴 탁자 하나와 바 쪽으로 뻗은 양 팔. 모두 바깥쪽에 앉아 안쪽을 본다.
// 자리 0~3 왼팔(바 쪽 끝부터), 4~11 앞 탁자(왼쪽부터), 12~15 오른팔(앞쪽부터)
export const USHAPE = {
  base: { x0: -125, x1: 125, z0: 60, z1: 110 },
  armL: { x0: -125, x1: -75, z0: -92, z1: 60 },
  armR: { x0: 75, x1: 125, z0: -92, z1: 60 },
  baseZ: 97, armX: 113, gap: 30, armZ: [-56, -24, 8, 40],
  inset: 23,
};
// 잔이 갈 수 있는 곳 (상판 위, 가장자리에서 조금 안쪽)
export const U_REGION = ['base', 'armL', 'armR'].map((k) => {
  const r = USHAPE[k];
  return { x0: r.x0 + 6, x1: r.x1 - 6, z0: r.z0 + 6, z1: r.z1 - 6 };
});

function circle(T, n, seat) {
  const yaw = (seat / n) * Math.PI * 2;
  return { x: Math.sin(yaw) * T.home, z: Math.cos(yaw) * T.home, yaw };
}

// 잔이 놓이는 자리와 앉은 방향
export function seatPose(table, seat) {
  switch (table) {
    case 'bar':
      return { x: seat ? 26 : -26, z: -140, yaw: 0 };
    case 'square': {
      const yaw = [0, Math.PI, Math.PI / 2, -Math.PI / 2][seat] ?? 0;
      return { x: Math.sin(yaw) * SQUARE.home, z: Math.cos(yaw) * SQUARE.home, yaw };
    }
    case 'long': {
      const side = seat < 6 ? 1 : -1;
      return { x: LONG.x0 + LONG.gap * (seat % 6), z: side * LONG.home, yaw: side > 0 ? 0 : Math.PI };
    }
    case 'biground':
      return circle(BIGROUND, 12, seat);
    case 'ushape': {
      const U = USHAPE;
      if (seat < 4) return { x: -U.armX, z: U.armZ[seat], yaw: -Math.PI / 2 };
      if (seat < 12) return { x: -105 + (seat - 4) * U.gap, z: U.baseZ, yaw: 0 };
      return { x: U.armX, z: U.armZ[15 - seat], yaw: Math.PI / 2 };
    }
    default:
      return circle(ROUND, 8, seat);
  }
}

// 의자(바에선 스툴) 자리
export function chairPose(table, seat) {
  const p = seatPose(table, seat);
  if (table === 'bar') return { x: p.x, z: -104 };
  if (table === 'long') return { x: p.x, z: Math.sign(p.z) * (LONG.halfZ + 28) };
  if (table === 'ushape') {
    const bx = Math.sin(p.yaw), bz = Math.cos(p.yaw);
    return { x: p.x + bx * 37, z: p.z + bz * 37 };
  }
  const r = table === 'square' ? SQUARE.half + 28 : table === 'biground' ? BIGROUND.chair : ROUND.chair;
  return { x: Math.sin(p.yaw) * r, z: Math.cos(p.yaw) * r };
}

// 안주 접시 자리: 잔 옆, 탁자 안쪽으로 조금
export function anjuPose(table, seat) {
  const p = seatPose(table, seat);
  if (table === 'round' || table === 'biground') {
    const T = table === 'round' ? ROUND : BIGROUND;
    const a = p.yaw + T.anjuA;
    return { x: Math.sin(a) * T.anjuR, z: Math.cos(a) * T.anjuR };
  }
  if (table === 'bar') return { x: p.x + (seat ? 24 : -24), z: -134 };
  if (table === 'ushape') {
    // 잔 바로 앞, 탁자 안쪽
    const bx = Math.sin(p.yaw), bz = Math.cos(p.yaw);
    return { x: p.x - bx * USHAPE.inset, z: p.z - bz * USHAPE.inset };
  }
  const right = table === 'long' ? 18 : 17, fwd = 9;
  const rx = Math.cos(p.yaw), rz = -Math.sin(p.yaw);
  const fx = -Math.sin(p.yaw), fz = -Math.cos(p.yaw);
  return { x: p.x + rx * right + fx * fwd, z: p.z + rz * right + fz * fwd };
}

// 두 자리 사이: 'next'(옆자리), 'across'(맞은편), 아니면 null
export function relation(table, a, b) {
  if (a === b || a == null || b == null) return null;
  const ring = (n) => {
    const d = Math.abs(a - b) % n, dd = Math.min(d, n - d);
    return dd === n / 2 ? 'across' : dd === 1 ? 'next' : null;
  };
  switch (table) {
    case 'bar':
      return 'next';
    case 'square':
      return (a ^ 1) === b ? 'across' : 'next';
    case 'long': {
      if (a < 6 === b < 6) return Math.abs(a - b) === 1 ? 'next' : null;
      return a % 6 === b % 6 ? 'across' : null;
    }
    case 'biground':
      return ring(12);
    case 'ushape':
      // 양 팔은 서로 마주 본다 (0↔15, 1↔14 …)
      if (Math.abs(a - b) === 1) return 'next';
      if (Math.min(a, b) < 4 && a + b === 15) return 'across';
      return null;
    default:
      return ring(8);
  }
}

// 사람 수에 맞는 가장 작은 탁자 (만들 때 고른 것보다 작아지지는 않는다)
export function tableFor(count, from = 'bar') {
  let t = from;
  while (TABLES[t].cap < count && UPGRADE[t]) t = UPGRADE[t];
  return t;
}
