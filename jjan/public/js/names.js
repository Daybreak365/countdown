// 이름 다듬기: 서버와 브라우저가 같이 쓴다.
// 글자 수(코드 단위)로 자르면 한글·이모지 이름이 화면에서 훨씬 길어지고, 보이지 않는 글자로 빈 이름을 만들 수도 있다.
// 그래서 화면에 보이는 폭으로 자른다: 한글·한자·이모지 같은 넓은 글자는 2칸, 나머지는 1칸, 모두 NAME_WIDTH칸까지.

export const NAME_WIDTH = 14;

// 보이지 않거나 글줄을 어지럽히는 글자: 제어 문자, 폭 없는 공백, 방향 바꾸기, 한글 채움 문자 …
const INVISIBLE = /[\u0000-\u001f\u007f-\u009f\u00ad\u034f\u061c\u115f\u1160\u17b4\u17b5\u180b-\u180e\u200b\u200c\u200e\u200f\u202a-\u202e\u2060-\u206f\u3164\ufe00-\ufe0e\ufeff\uffa0\ufff0-\ufffb<>]/g;
// 한 글자에 덧붙는 표시(악센트 등)는 둘까지만 (겹겹이 쌓으면 위아래로 길게 번진다)
const MARKS = /(\p{M}{2})\p{M}+/gu;
// 유난히 넓게 그려지는 글자
const HUGE = /[\ufdfd\u{12000}-\u{1254f}\u{1d200}-\u{1d24f}]/gu;

const segmenter = typeof Intl !== 'undefined' && Intl.Segmenter ? new Intl.Segmenter('ko', { granularity: 'grapheme' }) : null;
const graphemes = (s) => (segmenter ? [...segmenter.segment(s)].map((x) => x.segment) : Array.from(s));

// 넓은 글자인지 (한글, 한중일 글자, 전각, 이모지)
function wide(g) {
  const c = g.codePointAt(0);
  return (
    (c >= 0x1100 && c <= 0x115f) || (c >= 0x2e80 && c <= 0xa4cf) || (c >= 0xac00 && c <= 0xd7a3) ||
    (c >= 0xf900 && c <= 0xfaff) || (c >= 0xfe30 && c <= 0xfe4f) || (c >= 0xff00 && c <= 0xff60) || (c >= 0xffe0 && c <= 0xffe6) ||
    (c >= 0x1f000 && c <= 0x1faff) || (c >= 0x20000 && c <= 0x3fffd) || /\p{Extended_Pictographic}/u.test(g)
  );
}

export function nameWidth(s) {
  let w = 0;
  for (const g of graphemes(String(s ?? ''))) w += wide(g) ? 2 : 1;
  return w;
}

export function cleanName(s, fallback = '손님') {
  let t = String(s ?? '').normalize('NFC').replace(INVISIBLE, '').replace(MARKS, '$1').replace(HUGE, '');
  t = t.replace(/\s+/g, ' ').trim();
  let out = '', w = 0;
  for (const g of graphemes(t)) {
    const gw = wide(g) ? 2 : 1;
    if (w + gw > NAME_WIDTH) break;
    out += g;
    w += gw;
  }
  out = out.trim();
  // 남은 게 글자 없이 기호·공백뿐이면 손님
  return /[\p{L}\p{N}\p{Extended_Pictographic}]/u.test(out) ? out : fallback;
}
