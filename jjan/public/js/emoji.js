// 이모지 반응에 쓰는 글자 다듬기 (서버와 같이 씀)
// 이모지 하나(국기, 키캡, 피부색, ZWJ로 이은 가족·직업 이모지까지)만 통과시킨다.

const PIC = String.raw`(?:\p{Extended_Pictographic}|\p{Emoji_Presentation})`;
const ONE = String.raw`${PIC}(?:\uFE0F|\p{Emoji_Modifier})?`;
const EMOJI_RE = new RegExp(
  String.raw`^(?:\p{Regional_Indicator}{2}|[#*0-9]\uFE0F?\u20E3|${ONE}(?:[\u{E0020}-\u{E007E}]+\u{E007F})?(?:\u200D${ONE})*)$`,
  'u',
);

// 처음 고른 8개 (쟁반의 기본 자리)
export const QUICK_EMOJI = ['👍', '😂', '🥴', '😍', '👏', '🔥', '😭', '🍻'];

const seg = typeof Intl !== 'undefined' && Intl.Segmenter ? new Intl.Segmenter('ko', { granularity: 'grapheme' }) : null;

// 받은 글자에서 맨 앞 이모지 하나. 이모지가 아니면 ''
export function cleanEmoji(s) {
  if (typeof s !== 'string' || !s) return '';
  s = s.trim().slice(0, 40);
  const first = seg ? (seg.segment(s)[Symbol.iterator]().next().value?.segment ?? '') : s;
  return first.length <= 32 && EMOJI_RE.test(first) ? first : '';
}

// 글 속에 든 이모지를 앞에서부터 (직접 입력 칸에 붙여 넣은 걸 고를 때)
export function findEmojis(s, max = 8) {
  const out = [];
  if (!seg || typeof s !== 'string') return out;
  for (const { segment } of seg.segment(s.slice(0, 200))) {
    if (EMOJI_RE.test(segment) && !/^[#*0-9]$/.test(segment) && !out.includes(segment)) out.push(segment);
    if (out.length >= max) break;
  }
  return out;
}

// 피부색: 0 기본(노랑), 1~5 밝은 → 어두운
export const TONES = ['', '\u{1F3FB}', '\u{1F3FC}', '\u{1F3FD}', '\u{1F3FE}', '\u{1F3FF}'];
// 두 사람 이상이 함께 있는 이모지는 사람마다 색을 따로 넣어야 해서 기본 색으로 둔다
const PERSON = /[\u{1F466}-\u{1F469}\u{1F9D1}\u{1F468}\u{1FAF1}\u{1FAF2}]/u;
export function withTone(e, tone) {
  const m = TONES[tone];
  if (!m) return e;
  const parts = e.split('\u200D');
  if (parts.filter((p) => PERSON.test(p)).length > 1) return e;
  const [cp, ...rest] = [...parts[0]];
  parts[0] = cp + m + rest.filter((c) => c !== '\uFE0F').join('');
  return parts.join('\u200D');
}
