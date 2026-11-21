// 같이 듣는 노래 목록. 서버(server.js)와 혼자 짠(브라우저)이 같은 규칙으로 바꾼다.
// list[0]이 지금 곡. at: 지금 곡을 0초부터 틀었다고 치는 시각(ms), paused면 pos(초)에서 멈춰 있다.

export const MUSIC_MAX = 40;
const YT_ID = /^[A-Za-z0-9_-]{11}$/;

// 유튜브 · 유튜브 뮤직 링크에서 영상 ID 꺼내기 (곡 하나만. 재생목록만 있는 링크는 안 된다)
export function youtubeId(text) {
  const s = String(text ?? '').trim();
  if (YT_ID.test(s)) return s;
  let u;
  try { u = new URL(/^[a-z]+:\/\//i.test(s) ? s : `https://${s}`); } catch { return null; }
  const host = u.hostname.toLowerCase().replace(/^(www|m|music)\./, '');
  let id = null;
  if (host === 'youtu.be') id = u.pathname.split('/')[1];
  else if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
    if (u.pathname === '/watch') id = u.searchParams.get('v');
    else id = u.pathname.match(/^\/(?:shorts|embed|live|v)\/([^/?#]+)/)?.[1] ?? null;
  }
  return id && YT_ID.test(id) ? id : null;
}

export const isPlaylistOnly = (text) => !youtubeId(text) && /[?&]list=/.test(String(text ?? ''));

export const newMusic = () => ({ list: [], at: 0, paused: false, pos: 0 });

// 지금 곡을 몇 초째 듣고 있나
export function musicPos(m, now) {
  if (!m.list.length) return 0;
  return m.paused ? m.pos : Math.max(0, (now - m.at) / 1000);
}

const clean = (s, n) => String(s ?? '').replace(/[\u0000-\u001f<>]/g, '').trim().slice(0, n);

// act: { a, key?, item?, pos?, title?, author? }. 바뀌었으면 true
export function musicApply(m, act, now) {
  const cur = m.list[0];
  const start = () => { m.at = now; m.pos = 0; m.paused = false; };
  const dropFirst = () => {
    m.list.shift();
    if (m.list.length) start();
    else { m.paused = false; m.pos = 0; }
  };
  switch (act.a) {
    case 'add': {
      if (m.list.length >= MUSIC_MAX || !act.item) return false;
      const it = act.item;
      m.list.push({ key: clean(it.key, 16), id: it.id, title: clean(it.title, 120), author: clean(it.author, 60), by: clean(it.by, 12) });
      if (m.list.length === 1) start();
      return true;
    }
    case 'pause':
      if (!cur || m.paused) return false;
      m.pos = musicPos(m, now);
      m.paused = true;
      return true;
    case 'play':
      if (!cur || !m.paused) return false;
      m.at = now - m.pos * 1000;
      m.paused = false;
      return true;
    // 다음 곡 / 다 들었음 / 틀 수 없는 영상: 지금 곡이 그 곡일 때만 (여럿이 동시에 눌러도 한 번만 넘어간다)
    case 'next':
    case 'ended':
    case 'error':
      if (!cur || act.key !== cur.key) return false;
      dropFirst();
      return true;
    case 'remove': {
      const i = m.list.findIndex((x) => x.key === act.key);
      if (i < 0) return false;
      if (i === 0) dropFirst();
      else m.list.splice(i, 1);
      return true;
    }
    case 'seek': {
      if (!cur || act.key !== cur.key) return false;
      const p = Math.max(0, Number(act.pos) || 0);
      if (m.paused) m.pos = p;
      else m.at = now - p * 1000;
      return true;
    }
    // 제목은 비어 있을 때만 채운다
    case 'title': {
      const it = m.list.find((x) => x.key === act.key);
      if (!it || it.title) return false;
      it.title = clean(act.title, 120);
      it.author = clean(act.author, 60);
      return !!it.title;
    }
  }
  return false;
}
