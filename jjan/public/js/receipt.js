// 계산서: 마신 술과 안주를 적어 두었다가 나갈 때 영수증으로 뽑는다.
// 가격은 원(₩). 가게마다 값이 다르다 (편의점은 싸고, 루프탑은 비싸고, 다락방은 친구 집이라 공짜).

import { drinkById, resolveDrink } from './data.js';
import { anjuById } from './anju.js';
import { remedyById } from './remedy.js';
import { venueById } from './venues.js';

// 술 한 잔 값 (기본 가게 기준)
const DRINK_PRICE = {
  lager: 5000, draft: 5000, stout: 8000, wheat: 8000, ipa: 9000, kriek: 9000,
  red: 12000, white: 12000, rose: 12000, champagne: 18000, vinchaud: 10000, sangria: 11000,
  neat: 15000, ontherocks: 15000, highball: 9000, hottoddy: 11000, oldfashioned: 14000, irish: 12000,
  b52: 12000, longisland: 15000, tequila: 8000,
  soju: 5000, makgeolli: 6000, dongdongju: 8000, bokbunja: 12000, maesil: 10000, sake: 12000, atsukan: 12000, cheongju: 10000, gaoliang: 15000,
  somaek: 9000, yangpok: 14000, jagerbomb: 12000, gojingamrae: 10000, maksa: 9000, sopok: 9000, osipseju: 10000,
  tea: 4000, yuja: 5000, cocoa: 5000, cola: 2500, cider: 2500, sikhye: 3000, greentea: 4000, coffee: 4500, iceame: 4500,
};
const CAT_PRICE = { beer: 6000, wine: 12000, whisky: 14000, cocktail: 13000, korean: 7000, bomb: 10000, soft: 3000 };
const ANJU_PRICE = {
  chicken: 20000, eggroll: 12000, peanuts: 5000, fries: 9000, nogari: 9000, pajeon: 15000, tteokbokki: 13000,
  cheese: 22000, fruit: 18000, odeng: 15000, dubu: 15000, nachos: 12000, yakitori: 16000, cupramen: 3000,
};
const REMEDY_PRICE = 4500;

// 가게마다 값 (편의점은 50원 단위, 나머지는 500원 단위로 끊는다)
const VENUE = {
  bar: { k: 1.1, addr: '서울 단골구 골목길 12, 1층', tel: '02-000-1987', bye: '오늘 하루도 수고했어요.' },
  pocha: { k: 1.0, addr: '서울 포차구 천막로 3 (노상)', tel: '010-0000-0303', bye: '조심히 들어가요~ 또 와!' },
  hof: { k: 0.95, addr: '서울 퇴근구 치맥로 500, 지하 1층', tel: '02-000-0500', bye: '오늘도 퇴근 축하드려요!' },
  wine: { k: 1.3, addr: '서울 포도구 숙성길 7, 2층', tel: '02-000-0707', bye: 'Santé! 좋은 밤 되세요.' },
  xmas: { k: 1.2, addr: '강원 눈내리군 산장길 25', tel: '033-000-1225', bye: 'Merry Christmas! 눈길 조심하세요.' },
  attic: { k: 1, free: true, addr: '우리 집 다락방 (사다리 위)', tel: '단톡방으로 연락', bye: '설거지는 네가 해.' },
  izakaya: { k: 1.15, addr: '서울 숯불구 꼬치길 8', tel: '02-000-0808', bye: '아리가토! 또 오세요.' },
  rooftop: { k: 1.5, addr: '서울 야경구 하늘로 77, 옥상', tel: '02-000-0077', bye: '도시의 밤을 즐기셨나요?' },
  conv: { k: 0.45, step: 50, addr: '서울 밤샘구 편의로 24', tel: '02-000-2424', bye: '봉투 필요하세요? (20원)' },
};

const round = (v, step) => Math.max(0, Math.round(v / step) * step);
export function priceOf(kind, id, venue) {
  const V = VENUE[venue] ?? VENUE.bar;
  let base;
  if (kind === 'drink') base = DRINK_PRICE[id] ?? CAT_PRICE[drinkById[id]?.cat] ?? 8000;
  else if (kind === 'anju') base = ANJU_PRICE[id] ?? 12000;
  else base = REMEDY_PRICE;
  return round(base * V.k, V.step ?? 500);
}

// ── 계산서 ─────────────────────────────────────────────
export class Tab {
  constructor() {
    this.reset({});
  }

  reset({ venue = 'bar', mode = 'single', table = null, seat = null }) {
    this.venue = venue;
    this.mode = mode;
    this.table = table;
    this.seat = seat;
    this.start = Date.now();
    this.no = 1 + Math.floor(Math.random() * 9000);
    this.lines = new Map();
    this.clinks = 0;
    this.stole = 0;
    this.lost = 0;
    this.drunkPeak = 0;
    this.friends = new Set();
  }

  add(kind, id, label, venue = this.venue) {
    const price = priceOf(kind, id, venue);
    const key = `${kind}|${id}|${label}|${price}`;
    const line = this.lines.get(key) ?? { kind, id, label, price, qty: 0 };
    line.qty++;
    this.lines.set(key, line);
  }

  drink(drinkId, variant, venue) {
    const d = resolveDrink(drinkId, variant);
    if (!d) return;
    this.add('drink', d.id, d.brand ? `${d.name} (${d.brand})` : d.name, venue);
  }

  anju(id, venue) {
    const a = anjuById[id];
    if (a) this.add('anju', id, a.name, venue);
  }

  remedy(id, venue) {
    const r = remedyById[id];
    if (r) this.add('remedy', id, r.name, venue);
  }

  get items() {
    // 술 먼저, 그다음 안주, 해장
    const order = { drink: 0, anju: 1, remedy: 2 };
    return [...this.lines.values()].sort((a, b) => order[a.kind] - order[b.kind]);
  }

  get total() {
    let t = 0;
    for (const l of this.lines.values()) t += l.price * l.qty;
    return t;
  }
}

// ── 영수증 그리기 (감열지) ───────────────────────────────
const won = (n) => n.toLocaleString('ko-KR');
const pad = (n) => String(n).padStart(2, '0');

function wrapName(g, text, max) {
  if (g.measureText(text).width <= max) return [text];
  const out = [];
  let cur = '';
  for (const ch of text) {
    if (g.measureText(cur + ch).width > max) { out.push(cur); cur = ch; } else cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}

const TIPSY = ['말짱', '기분 좋음', '알딸딸', '어질어질', '만취'];
const stageOf = (d) => (d < 0.1 ? 0 : d < 0.3 ? 1 : d < 0.55 ? 2 : d < 0.8 ? 3 : 4);

// info: { tab, host, tableName, now, drunkPeak, minutes, me }
export function drawReceipt(canvas, info) {
  const { tab } = info;
  const v = venueById[tab.venue] ?? venueById.bar;
  const V = VENUE[tab.venue] ?? VENUE.bar;
  const W = 300, M = 18, S = 2;
  const g0 = canvas.getContext('2d');
  // 높이를 먼저 셈한다 (같은 걸 두 번 그린다: 한 번은 재기만)
  const draw = (g, measure) => {
    let y = 18;
    const font = (px, bold = false) => { g.font = `${bold ? '700 ' : ''}${px}px Galmuri11, 'Apple SD Gothic Neo', monospace`; };
    const text = (s, x, align = 'left', color = '#2a2622') => { if (!measure) { g.textAlign = align; g.fillStyle = color; g.fillText(s, x, y); } };
    const line = (dash = true) => {
      if (!measure) {
        g.fillStyle = '#4a443e';
        if (dash) for (let x = M; x < W - M; x += 6) g.fillRect(x, y - 4, 3, 1);
        else { g.fillRect(M, y - 5, W - M * 2, 1); g.fillRect(M, y - 3, W - M * 2, 1); }
      }
      y += 10;
    };
    const row = (l, r, opt = {}) => {
      font(opt.size ?? 11, opt.bold);
      text(l, opt.indent ? M + 10 : M);
      if (r != null) text(r, W - M, 'right');
      y += opt.gap ?? 16;
    };
    g.textBaseline = 'alphabetic';
    font(10); text('[영 수 증]', W / 2, 'center', '#6a625a'); y += 22;
    font(19, true); text(v.name, W / 2, 'center', '#1a1612'); y += 20;
    font(10);
    text(`대표 ${info.host}   사업자 000-00-00000`, W / 2, 'center', '#4a443e'); y += 14;
    text(V.addr, W / 2, 'center', '#4a443e'); y += 14;
    text(`TEL ${V.tel}`, W / 2, 'center', '#4a443e'); y += 16;
    line();
    const d = info.now;
    row(`${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`, info.tableName, { size: 10, gap: 14 });
    row(`영수 No. ${String(tab.no).padStart(4, '0')}`, `담당 ${info.host}`, { size: 10, gap: 12 });
    line(false);
    font(10); text('메뉴', M); text('단가', W - M - 96, 'right'); text('수량', W - M - 62, 'right'); text('금액', W - M, 'right'); y += 8;
    line();
    font(11);
    const items = tab.items;
    if (!items.length) row('주문 없음', '0', { size: 11 });
    for (const it of items) {
      const names = wrapName(g, it.label, W - M * 2 - 120);
      names.forEach((n, i) => {
        text(n, M);
        if (i === 0) {
          text(won(it.price), W - M - 96, 'right');
          text(String(it.qty), W - M - 62, 'right');
          text(won(it.price * it.qty), W - M, 'right');
        }
        y += 15;
      });
    }
    if (tab.stole) row(`  남의 안주 ${tab.stole}조각 (서비스)`, '0', { size: 10, gap: 14 });
    line();
    const total = tab.total;
    const supply = Math.round(total / 1.1);
    row('합계', won(total), { size: 12, bold: true, gap: 16 });
    row('공급가액', won(supply), { size: 10, indent: true, gap: 13 });
    row('부가세', won(total - supply), { size: 10, indent: true, gap: 12 });
    const due = V.free ? 0 : total;
    if (V.free && total) row('우정 할인 (100%)', `-${won(total)}`, { size: 10, bold: true, gap: 13 });
    line(false);
    row('받을 금액', `${won(due)}원`, { size: 14, bold: true, gap: 18 });
    row(due ? '카드 결제 (일시불)' : V.free ? '결제 없음 (친구 집)' : '결제 없음', won(due), { size: 10, gap: 12 });
    if (due) row('승인번호', String(10000000 + ((tab.no * 7919) % 89999999)).slice(0, 8), { size: 10, gap: 12 });
    line();
    font(11, true); text('오늘의 기록', M); y += 16;
    row('짠!', `${tab.clinks}회`, { size: 11, indent: true });
    row('머문 시간', info.minutes >= 60 ? `${Math.floor(info.minutes / 60)}시간 ${info.minutes % 60}분` : `${Math.max(1, info.minutes)}분`, { size: 11, indent: true });
    row('최고 취기', TIPSY[stageOf(tab.drunkPeak)], { size: 11, indent: true });
    if (tab.friends.size) {
      font(11);
      const names = [...tab.friends].join(', ');
      const ls = wrapName(g, names, W - M * 2 - 110);
      text('같이 마신 사람', M + 10);
      ls.forEach((n, i) => { text(n, W - M, 'right'); y += 15; });
      y += 1;
    }
    if (tab.lost) row('뺏긴 안주', `${tab.lost}조각`, { size: 11, indent: true });
    line();
    // 바코드
    if (!measure) {
      const code = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}${pad(d.getHours())}${pad(d.getMinutes())}${String(tab.no).padStart(4, '0')}`;
      let x = M + 20;
      g.fillStyle = '#1a1612';
      for (let i = 0; i < 46 && x < W - M - 20; i++) {
        const c = code.charCodeAt(i % code.length) + i * 7;
        const bw = 1 + (c % 3);
        g.fillRect(x, y - 6, bw, 34);
        x += bw + 1 + ((c >> 2) % 3);
      }
      y += 40;
      font(9); text(code.replace(/(\d{4})(?=\d)/g, '$1 '), W / 2, 'center', '#4a443e');
      y += 14;
    } else y += 54;
    y += 2;
    font(12, true); text(V.bye, W / 2, 'center', '#1a1612'); y += 18;
    font(9); text('* 짠 게임 속 가상의 영수증이에요', W / 2, 'center', '#8a827a'); y += 14;
    return y + 12;
  };
  // 재기
  g0.font = '11px Galmuri11';
  const H = draw(g0, true);
  canvas.width = W * S;
  canvas.height = H * S;
  const g = canvas.getContext('2d');
  g.scale(S, S);
  // 감열지: 살짝 누런 흰 바탕, 위아래 톱니
  g.fillStyle = '#fbf8f0';
  g.beginPath();
  g.moveTo(0, 6);
  for (let x = 0; x <= W; x += 8) g.lineTo(x, x % 16 ? 0 : 6);
  g.lineTo(W, H - 6);
  for (let x = W; x >= 0; x -= 8) g.lineTo(x, x % 16 ? H : H - 6);
  g.closePath();
  g.fill();
  // 종이 결
  g.fillStyle = 'rgba(120,100,80,0.035)';
  for (let y = 10; y < H - 10; y += 3) g.fillRect(0, y, W, 1);
  draw(g, false);
  return { width: W, height: H };
}
