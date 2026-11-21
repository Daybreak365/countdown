import { newMusic, musicApply, musicPos, youtubeId, isPlaylistOnly } from './musicq.js';

// 같이 듣는 노래. 목록은 서버(같이 짠) 또는 이 브라우저(혼자 짠)가 들고 있고,
// 여기서는 유튜브 플레이어를 그 목록과 시각에 맞춰 튼다. 몇 초 넘게 어긋나면 맞춘다.

const YT_STATE = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 };
let apiPromise = null;

// 유튜브 IFrame API는 처음 노래를 틀 때 불러온다
function loadApi() {
  if (window.YT?.Player) return Promise.resolve(window.YT);
  if (apiPromise) return apiPromise;
  apiPromise = new Promise((resolve, reject) => {
    const prev = window.onYouTubeIframeAPIReady;
    window.onYouTubeIframeAPIReady = () => { prev?.(); resolve(window.YT); };
    const s = document.createElement('script');
    s.src = 'https://www.youtube.com/iframe_api';
    s.async = true;
    s.onerror = () => { apiPromise = null; reject(new Error('yt')); };
    document.head.appendChild(s);
  });
  return apiPromise;
}

const rid = () => Math.random().toString(36).slice(2, 10);

export class MusicBox {
  constructor({ mount, onChange, onError }) {
    this.mount = mount; // 플레이어를 넣을 자리 (div id)
    this.onChange = onChange;
    this.onError = onError;
    this.m = newMusic();
    this.net = null;
    this.offset = 0;
    this.player = null;
    this.ready = false;
    this.loadedKey = null;
    this.volume = 0.7;
    this.state = YT_STATE.UNSTARTED;
    this.blocked = false;
    this.wantSince = 0;
    this.timer = setInterval(() => this.tick(), 1000);
  }

  // ── 목록 ───────────────────────────────────────────
  // 혼자 짠: 이 브라우저가 목록을 들고 있다 (가게를 나가도 이어서 듣는다)
  useLocal() {
    if (!this.net) return;
    this.net = null;
    this.offset = 0;
    this.m = newMusic();
    this.changed();
  }

  // 같이 짠: 서버가 보낸 목록을 따른다
  useNet(net, m, now) {
    this.net = net;
    this.setState(m, now);
  }

  setState(m, now) {
    if (now) this.offset = now - Date.now();
    this.m = m || newMusic();
    this.changed();
  }

  now() {
    return Date.now() + this.offset;
  }

  get current() {
    return this.m.list[0] ?? null;
  }

  get queue() {
    return this.m.list;
  }

  get paused() {
    return this.m.paused;
  }

  // 지금 실제로 소리가 나는지 (기물이 연주하는 동안)
  get playing() {
    return !!this.current && !this.m.paused && this.state === YT_STATE.PLAYING;
  }

  get position() {
    if (this.ready && this.loadedKey === this.current?.key) {
      try { return this.player.getCurrentTime() || 0; } catch { /* 아직 */ }
    }
    return musicPos(this.m, this.now());
  }

  get duration() {
    if (!this.ready || this.loadedKey !== this.current?.key) return 0;
    try { return this.player.getDuration() || 0; } catch { return 0; }
  }

  act(a, extra = {}) {
    if (this.net) {
      this.net.send({ t: 'music', a, ...extra });
      return;
    }
    if (musicApply(this.m, { a, ...extra }, Date.now())) this.changed();
  }

  // 링크 넣기. 문제가 있으면 이유를 돌려준다
  add(text, by) {
    const id = youtubeId(text);
    if (!id) return isPlaylistOnly(text) ? 'playlist' : 'bad-url';
    if (this.m.list.length >= 40) return 'full';
    if (this.net) {
      this.net.send({ t: 'music', a: 'add', url: id });
      return null;
    }
    const key = rid();
    if (musicApply(this.m, { a: 'add', item: { key, id, title: '', author: '', by } }, Date.now())) this.changed();
    // 제목은 서버에 물어본다
    fetch(`/api/yt-title?id=${id}`).then((r) => (r.ok ? r.json() : null)).then((j) => {
      if (j?.title && !this.net && musicApply(this.m, { a: 'title', key, title: j.title, author: j.author }, Date.now())) this.onChange?.();
    }).catch(() => {});
    return null;
  }

  togglePause() {
    const cur = this.current;
    if (!cur) return;
    // 버튼을 누른 이 사람의 실제 재생 위치로 멈춘다
    if (this.m.paused) this.act('play');
    else this.act('pause');
    this.unblock();
  }

  next() {
    const cur = this.current;
    if (cur) this.act('next', { key: cur.key });
  }

  remove(key) {
    this.act('remove', { key });
  }

  changed() {
    this.sync();
    this.onChange?.();
  }

  // ── 플레이어 ─────────────────────────────────────────
  ensurePlayer() {
    if (this.player || this.creating) return;
    this.creating = true;
    loadApi().then((YT) => {
      this.player = new YT.Player(this.mount, {
        width: '100%',
        height: '100%',
        playerVars: { autoplay: 0, controls: 0, disablekb: 1, playsinline: 1, rel: 0, modestbranding: 1, iv_load_policy: 3, fs: 0 },
        events: {
          onReady: () => {
            this.ready = true;
            this.applyVolume();
            this.sync();
          },
          onStateChange: (e) => this.onState(e.data),
          onError: (e) => this.onPlayerError(e.data),
        },
      });
    }, () => {
      this.creating = false;
      this.onError?.('api');
    });
  }

  onState(s) {
    this.state = s;
    const cur = this.current;
    if (s === YT_STATE.PLAYING) {
      this.blocked = false;
      // 제목을 아직 모르면 플레이어가 아는 걸로 채운다
      if (cur && !cur.title) {
        try {
          const d = this.player.getVideoData();
          if (d?.title) this.act('title', { key: cur.key, title: d.title, author: d.author || '' });
        } catch { /* 괜찮다 */ }
      }
    }
    if (s === YT_STATE.ENDED && cur && this.loadedKey === cur.key) this.act('ended', { key: cur.key });
    this.onChange?.();
  }

  onPlayerError(code) {
    const cur = this.current;
    if (!cur || this.loadedKey !== cur.key) return;
    // 101/150: 다른 사이트에서 틀 수 없는 영상, 100: 없는 영상
    this.onError?.(code === 101 || code === 150 ? 'embed' : 'missing', cur);
    this.act('error', { key: cur.key });
  }

  sync() {
    const cur = this.current;
    if (!cur) {
      if (this.ready && this.loadedKey) {
        try { this.player.stopVideo(); } catch { /* 괜찮다 */ }
      }
      this.loadedKey = null;
      this.blocked = false;
      return;
    }
    if (!this.ready) { this.ensurePlayer(); return; }
    const want = musicPos(this.m, this.now());
    const p = this.player;
    try {
      if (this.loadedKey !== cur.key) {
        this.loadedKey = cur.key;
        this.wantSince = performance.now();
        if (this.m.paused) p.cueVideoById({ videoId: cur.id, startSeconds: want });
        else p.loadVideoById({ videoId: cur.id, startSeconds: want });
        return;
      }
      const st = p.getPlayerState();
      if (this.m.paused) {
        if (st === YT_STATE.PLAYING || st === YT_STATE.BUFFERING) p.pauseVideo();
        if (Math.abs((p.getCurrentTime() || 0) - want) > 1.5) p.seekTo(want, true);
        return;
      }
      if (st !== YT_STATE.PLAYING && st !== YT_STATE.BUFFERING && st !== YT_STATE.ENDED) {
        if (!this.wantSince) this.wantSince = performance.now();
        p.playVideo();
      }
      if (st === YT_STATE.PLAYING) {
        this.wantSince = 0;
        const drift = (p.getCurrentTime() || 0) - want;
        // 다른 사람과 3초 넘게 어긋나면 맞춘다 (방금 맞췄으면 잠깐 기다린다)
        const dur = p.getDuration() || 0;
        if (Math.abs(drift) > 3 && (!dur || want < dur - 1) && performance.now() - (this.seekAt || 0) > 4000) {
          this.seekAt = performance.now();
          p.seekTo(want, true);
        }
      }
    } catch { /* 플레이어가 아직 준비 중 */ }
  }

  tick() {
    if (!this.current) return;
    this.sync();
    // 재생하려는데 몇 초째 시작을 안 하면 브라우저가 소리를 막은 것: 눌러 달라고 한다
    const stuck = !this.m.paused && this.ready && this.wantSince && performance.now() - this.wantSince > 2500 && this.state !== YT_STATE.PLAYING && this.state !== YT_STATE.BUFFERING;
    if (stuck !== this.blocked) {
      this.blocked = stuck;
      this.onChange?.();
    }
    this.onTick?.();
  }

  // 사용자가 누른 순간에 다시 틀어 본다 (자동 재생이 막혔을 때)
  unblock() {
    if (!this.ready) { this.ensurePlayer(); return; }
    try {
      if (!this.m.paused) {
        this.player.seekTo(musicPos(this.m, this.now()), true);
        this.player.playVideo();
      }
    } catch { /* 괜찮다 */ }
  }

  setVolume(v, muted) {
    this.volume = v;
    this.muted = muted;
    this.applyVolume();
  }

  applyVolume() {
    if (!this.ready) return;
    try {
      this.player.setVolume(Math.round(this.volume * 100));
      if (this.muted || this.volume <= 0) this.player.mute();
      else this.player.unMute();
    } catch { /* 괜찮다 */ }
  }
}
