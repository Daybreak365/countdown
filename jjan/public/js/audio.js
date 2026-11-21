// 모든 소리는 Web Audio로 그때그때 합성한다 (녹음 파일 없음)

// 재질별 진동 모드: [주파수 배율, 세기, 감쇠 시간(초)]
const MATERIALS = {
  glass: { f: 2350, modes: [[1, 1, 0.9], [2.32, 0.45, 0.45], [4.25, 0.22, 0.22], [6.63, 0.1, 0.12]], noise: 0.22, nf: 6500 },
  thick: { f: 1550, modes: [[1, 1, 0.5], [2.41, 0.5, 0.26], [4.6, 0.25, 0.1]], noise: 0.35, nf: 4000 },
  crystal: { f: 2750, modes: [[1, 1, 2.3], [1.0025, 0.6, 2.3], [2.76, 0.4, 1.1], [5.4, 0.2, 0.5], [8.93, 0.08, 0.25]], noise: 0.12, nf: 9000 },
  ceramic: { f: 1750, modes: [[1, 1, 0.16], [2.13, 0.5, 0.08], [3.9, 0.25, 0.05]], noise: 0.75, nf: 2600 },
  metal: { f: 760, modes: [[1, 1, 2.6], [1.006, 0.8, 2.6], [2.71, 0.55, 1.4], [2.735, 0.4, 1.4], [5.08, 0.25, 0.6]], noise: 0.18, nf: 4200 },
  wood: { f: 470, modes: [[1, 1, 0.08], [2.62, 0.4, 0.04]], noise: 0.9, nf: 900 },
};

export class AudioEngine {
  constructor() {
    this.ctx = null;
    this.muted = false;
    this.ambienceOn = true;
    // 0 → 1
    this.volume = 1;
    this.ambVolume = 1;
  }

  masterLevel() {
    return this.muted ? 0 : 0.9 * this.volume;
  }

  ambLevel() {
    return this.ambienceOn ? this.ambVolume : 0;
  }

  setVolume(v) {
    this.volume = Math.max(0, Math.min(1, v));
    if (this.master) this.master.gain.setTargetAtTime(this.masterLevel(), this.ctx.currentTime, 0.05);
  }

  setAmbienceVolume(v) {
    this.ambVolume = Math.max(0, Math.min(1, v));
    if (this.ambBus) this.ambBus.gain.setTargetAtTime(this.ambLevel(), this.ctx.currentTime, 0.2);
  }

  unlock() {
    if (!this.ctx) {
      const Ctx = window.AudioContext || window.webkitAudioContext;
      if (!Ctx) return;
      const ctx = (this.ctx = new Ctx());
      this.comp = ctx.createDynamicsCompressor();
      this.comp.threshold.value = -14;
      this.comp.ratio.value = 4;
      this.master = ctx.createGain();
      this.master.gain.value = this.masterLevel();
      this.master.connect(this.comp).connect(ctx.destination);
      this.ambBus = ctx.createGain();
      this.ambBus.gain.value = 0;
      this.ambBus.connect(this.master);
      const len = ctx.sampleRate * 2;
      this.noise = ctx.createBuffer(1, len, ctx.sampleRate);
      const d = this.noise.getChannelData(0);
      for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
      this.startAmbience();
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
  }

  get ready() {
    return !!this.ctx && this.ctx.state === 'running';
  }

  setMuted(m) {
    this.muted = m;
    if (this.master) this.master.gain.setTargetAtTime(this.masterLevel(), this.ctx.currentTime, 0.05);
  }

  setAmbience(on) {
    this.ambienceOn = on;
    if (this.ambBus) this.ambBus.gain.setTargetAtTime(this.ambLevel(), this.ctx.currentTime, 0.4);
  }

  out(pan = 0) {
    if (!this.ctx.createStereoPanner) return this.master;
    const p = this.ctx.createStereoPanner();
    p.pan.value = Math.max(-1, Math.min(1, pan || 0));
    p.connect(this.master);
    return p;
  }

  noiseSource(t, dur) {
    const src = this.ctx.createBufferSource();
    src.buffer = this.noise;
    src.loop = true;
    src.start(t, Math.random() * 1.5);
    src.stop(t + dur + 0.05);
    return src;
  }

  // 한 쪽 물체의 울림
  voice(material, strength, size, t, dest) {
    const ctx = this.ctx;
    const m = MATERIALS[material] || MATERIALS.glass;
    const f0 = m.f * Math.pow(4 / Math.max(1.5, size), 0.7) * (1 + (Math.random() - 0.5) * 0.04);
    const amp = 0.05 + 0.5 * Math.pow(strength, 1.25);
    m.modes.forEach(([ratio, g, decay], i) => {
      const f = f0 * ratio;
      if (f > 18000) return;
      const osc = ctx.createOscillator();
      osc.frequency.value = f;
      const gain = ctx.createGain();
      // 세게 칠수록 높은 배음이 더 산다
      const level = amp * g * (i === 0 ? 1 : 0.6 + strength * 0.6) * 0.5;
      const dur = decay * (0.6 + strength * 0.5);
      gain.gain.setValueAtTime(0, t);
      gain.gain.linearRampToValueAtTime(level, t + 0.002);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      osc.connect(gain).connect(dest);
      osc.start(t);
      osc.stop(t + dur + 0.05);
    });
    // 맞닿는 순간의 짧은 '틱'
    const src = this.noiseSource(t, 0.05);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = m.nf;
    bp.Q.value = 1.2;
    const ng = ctx.createGain();
    const nl = amp * m.noise * 0.9;
    ng.gain.setValueAtTime(nl, t);
    ng.gain.exponentialRampToValueAtTime(0.0001, t + 0.025 + m.noise * 0.02);
    src.connect(bp).connect(ng).connect(dest);
  }

  // 잔과 잔 (또는 잔과 벽)
  clink(a, b, strength, pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime + 0.005;
    const dest = this.out(pan);
    this.voice(a.material, strength, a.size, t, dest);
    if (b) this.voice(b.material, strength * 0.85, b.size, t + 0.0015, dest);
    if (a.ice || b?.ice) this.iceTinkle(strength, t, dest);
  }

  wall(glass, strength, pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime + 0.005;
    const dest = this.out(pan);
    this.voice(glass.material, strength * 0.9, glass.size, t, dest);
    if (glass.ice) this.iceTinkle(strength, t, dest);
    // 벽이 받아 주는 둔탁한 소리
    const src = this.noiseSource(t, 0.2);
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 320;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.5 * strength, t);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
    src.connect(lp).connect(g).connect(dest);
    const osc = ctx.createOscillator();
    osc.frequency.setValueAtTime(130, t);
    osc.frequency.exponentialRampToValueAtTime(70, t + 0.12);
    const og = ctx.createGain();
    og.gain.setValueAtTime(0.35 * strength, t);
    og.gain.exponentialRampToValueAtTime(0.0001, t + 0.15);
    osc.connect(og).connect(dest);
    osc.start(t);
    osc.stop(t + 0.2);
  }

  iceTinkle(strength, t, dest) {
    const n = 2 + Math.floor(Math.random() * 3);
    for (let i = 0; i < n; i++) {
      const at = t + 0.02 + Math.random() * 0.12;
      const osc = this.ctx.createOscillator();
      osc.frequency.value = 3200 + Math.random() * 2600;
      const g = this.ctx.createGain();
      const lv = 0.06 * strength * (0.5 + Math.random());
      g.gain.setValueAtTime(0, at);
      g.gain.linearRampToValueAtTime(lv, at + 0.002);
      g.gain.exponentialRampToValueAtTime(0.0001, at + 0.09);
      osc.connect(g).connect(dest);
      osc.start(at);
      osc.stop(at + 0.12);
    }
  }

  // 따르는 소리: 잔이 찰수록 울림이 높아진다
  startPour(dur, drink) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const src = this.noiseSource(t, dur + 0.2);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.Q.value = 6;
    bp.frequency.setValueAtTime(380, t);
    bp.frequency.exponentialRampToValueAtTime(1500, t + dur);
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = drink?.carb ? 6000 : 3000;
    const g = ctx.createGain();
    // 꿀렁꿀렁
    const curve = new Float32Array(64);
    for (let i = 0; i < 64; i++) {
      const env = Math.min(1, i / 4) * Math.min(1, (63 - i) / 6);
      curve[i] = env * (0.16 + Math.random() * 0.12);
    }
    g.gain.setValueCurveAtTime(curve, t, dur);
    src.connect(bp).connect(lp).connect(g).connect(this.master);
    this.pourNodes = { src, g };
    if (drink?.carb) this.fizz(dur + 1.2, 0.5 * drink.carb);
  }

  stopPour() {
    if (!this.pourNodes || !this.ctx) return;
    try { this.pourNodes.src.stop(this.ctx.currentTime + 0.05); } catch { /* 이미 멈춤 */ }
    this.pourNodes = null;
  }

  fizz(dur, intensity = 0.6, pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const src = this.noiseSource(t, dur);
    const hp = ctx.createBiquadFilter();
    hp.type = 'highpass';
    hp.frequency.value = 5200;
    const g = ctx.createGain();
    const curve = new Float32Array(96);
    for (let i = 0; i < 96; i++) {
      const p = i / 95;
      curve[i] = intensity * 0.09 * Math.pow(1 - p, 1.6) * (Math.random() < 0.3 ? 1.8 : 0.5);
    }
    g.gain.setValueCurveAtTime(curve, t, dur);
    src.connect(hp).connect(g).connect(this.out(pan));
  }

  plop(pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const dest = this.out(pan);
    const osc = ctx.createOscillator();
    osc.frequency.setValueAtTime(700, t);
    osc.frequency.exponentialRampToValueAtTime(140, t + 0.13);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.4, t);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.18);
    osc.connect(g).connect(dest);
    osc.start(t);
    osc.stop(t + 0.2);
    const src = this.noiseSource(t, 0.3);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = 1800;
    const ng = ctx.createGain();
    ng.gain.setValueAtTime(0.25, t);
    ng.gain.exponentialRampToValueAtTime(0.0001, t + 0.25);
    src.connect(bp).connect(ng).connect(dest);
  }

  // 짧은 잡음 한 덩이: 필터 종류, 중심 주파수, 세기, 길이
  burst(type, freq, level, dur, t = this.ctx.currentTime, dest = this.master, q = 1) {
    const src = this.noiseSource(t, dur + 0.02);
    const f = this.ctx.createBiquadFilter();
    f.type = type;
    f.frequency.value = freq;
    f.Q.value = q;
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(level, t);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    src.connect(f).connect(g).connect(dest);
    return f;
  }

  tone(freq, to, level, dur, t = this.ctx.currentTime, dest = this.master, type = 'sine') {
    const osc = this.ctx.createOscillator();
    osc.type = type;
    osc.frequency.setValueAtTime(freq, t);
    if (to !== freq) osc.frequency.exponentialRampToValueAtTime(to, t + dur);
    const g = this.ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(level, t + 0.004);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    osc.connect(g).connect(dest);
    osc.start(t);
    osc.stop(t + dur + 0.05);
  }

  // 샴페인 코르크: 펑 + 쉬익
  pop(pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    this.tone(420, 90, 0.55, 0.09, t, dest);
    this.burst('bandpass', 1300, 0.7, 0.06, t, dest, 0.8);
    this.burst('highpass', 4200, 0.12, 0.9, t + 0.03, dest);
  }

  // 캔 따는 소리: 딸깍 + 치익
  canOpen(pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    this.burst('bandpass', 2600, 0.35, 0.02, t, dest, 3);
    this.tone(1900, 1400, 0.12, 0.03, t, dest, 'triangle');
    this.burst('highpass', 3800, 0.2, 0.45, t + 0.04, dest);
  }

  // 셰이커 속 얼음: 금속 통에 부딪히는 짤랑짤랑
  rattle(strength = 1, pan = 0, metal = true) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    const n = 2 + Math.floor(Math.random() * 3);
    for (let i = 0; i < n; i++) {
      const at = t + Math.random() * 0.05;
      const f = metal ? 2100 + Math.random() * 1500 : 3200 + Math.random() * 2400;
      this.tone(f, f, 0.15 * strength * (0.6 + Math.random()), 0.07, at, dest, 'triangle');
    }
    this.burst('bandpass', metal ? 1500 : 2600, 0.35 * strength, 0.04, t, dest, 1.5);
  }

  // 잔 속으로 얼음이 떨어지는 소리
  iceDrop(strength = 1, pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    const f = 2600 + Math.random() * 1400;
    this.tone(f, f, 0.2 * strength, 0.16, t, dest);
    this.tone(f * 2.4, f * 2.4, 0.08 * strength, 0.08, t, dest);
    this.burst('bandpass', 1800, 0.28 * strength, 0.05, t, dest, 1.2);
  }

  // 막걸리 병을 뒤집을 때 출렁
  slosh(pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const src = this.noiseSource(t, 0.6);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.Q.value = 3;
    bp.frequency.setValueAtTime(260, t);
    bp.frequency.linearRampToValueAtTime(700, t + 0.25);
    bp.frequency.linearRampToValueAtTime(320, t + 0.55);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(0.9, t + 0.15);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.58);
    src.connect(bp).connect(g).connect(this.out(pan));
  }

  // 병 바닥 툭
  tap(pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    this.tone(880, 620, 0.22, 0.06, t, dest);
    this.burst('bandpass', 1200, 0.25, 0.03, t, dest, 1.5);
  }

  // 라이터: 칙 + 화르륵
  lighter(pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const dest = this.out(pan);
    this.burst('highpass', 5000, 0.25, 0.03, t, dest);
    const src = this.noiseSource(t + 0.05, 0.5);
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.setValueAtTime(300, t + 0.05);
    lp.frequency.exponentialRampToValueAtTime(1400, t + 0.35);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t + 0.05);
    g.gain.linearRampToValueAtTime(0.3, t + 0.15);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.55);
    src.connect(lp).connect(g).connect(dest);
  }

  // 빨대로 쭈욱 — 마지막엔 바닥 긁는 소리
  slurp(dur = 1, pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const dest = this.out(pan);
    const src = this.noiseSource(t, dur + 0.3);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.Q.value = 4;
    bp.frequency.setValueAtTime(700, t);
    bp.frequency.linearRampToValueAtTime(1100, t + dur);
    bp.frequency.linearRampToValueAtTime(2400, t + dur + 0.25);
    const g = ctx.createGain();
    const curve = new Float32Array(48);
    for (let i = 0; i < 48; i++) {
      const p = i / 47;
      const env = Math.min(1, p * 10) * (p < 0.78 ? 1 : 1 - (p - 0.78) / 0.22);
      // 꿀렁꿀렁 끊기며 빨려 올라온다
      curve[i] = env * (0.18 + (Math.sin(i * 1.9) > 0.2 ? 0.16 : 0.02)) * (p > 0.78 ? 1.6 : 1);
    }
    g.gain.setValueCurveAtTime(curve, t, dur + 0.25);
    src.connect(bp).connect(g).connect(dest);
  }

  // 블렌더 위이잉
  blend(dur = 1, pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const dest = this.out(pan);
    const osc = ctx.createOscillator();
    osc.type = 'sawtooth';
    osc.frequency.setValueAtTime(60, t);
    osc.frequency.exponentialRampToValueAtTime(190, t + 0.25);
    osc.frequency.setValueAtTime(190, t + dur - 0.2);
    osc.frequency.exponentialRampToValueAtTime(70, t + dur);
    const lp = ctx.createBiquadFilter();
    lp.type = 'lowpass';
    lp.frequency.value = 1400;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(0.12, t + 0.2);
    g.gain.setValueAtTime(0.12, t + dur - 0.2);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    osc.connect(lp).connect(g).connect(dest);
    osc.start(t);
    osc.stop(t + dur + 0.05);
    // 얼음 갈리는 사각사각
    const src = this.noiseSource(t, dur);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = 2600;
    bp.Q.value = 0.8;
    const ng = ctx.createGain();
    const curve = new Float32Array(40);
    for (let i = 0; i < 40; i++) curve[i] = (i < 3 || i > 36 ? 0.02 : 0.06) * (0.5 + Math.random());
    ng.gain.setValueCurveAtTime(curve, t, dur);
    src.connect(bp).connect(ng).connect(dest);
  }

  // 안주 한 입: 와삭 (부드러운 건 덜 바삭하게)
  crunch(crisp = 1, pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    const dest = this.out(pan);
    for (let i = 0; i < 3; i++) {
      const at = t + 0.05 + i * (0.09 + Math.random() * 0.05);
      this.burst('bandpass', 1400 + crisp * 1800 + Math.random() * 800, 0.22 * (0.4 + crisp * 0.6), 0.04 + (1 - crisp) * 0.05, at, dest, 1.4);
    }
  }

  // 후 — 불 끄는 소리
  puff(pan = 0) {
    if (!this.ready) return;
    const t = this.ctx.currentTime;
    this.burst('lowpass', 700, 0.5, 0.28, t, this.out(pan));
  }

  gulp() {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const osc = ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(260, t);
    osc.frequency.exponentialRampToValueAtTime(90, t + 0.12);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t);
    g.gain.linearRampToValueAtTime(0.28, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
    osc.connect(g).connect(this.master);
    osc.start(t);
    osc.stop(t + 0.2);
  }

  // 딸꾹: 짧게 숨 막히는 소리 + 위로 튀는 목소리
  hic(pan = 0) {
    if (!this.ready) return;
    const ctx = this.ctx;
    const t = ctx.currentTime;
    const dest = this.out(pan);
    this.burst('bandpass', 1800, 0.18, 0.05, t, dest, 2);
    const osc = ctx.createOscillator();
    osc.type = 'triangle';
    osc.frequency.setValueAtTime(330, t + 0.03);
    osc.frequency.exponentialRampToValueAtTime(720, t + 0.11);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0, t + 0.03);
    g.gain.linearRampToValueAtTime(0.16, t + 0.05);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
    osc.connect(g).connect(dest);
    osc.start(t + 0.03);
    osc.stop(t + 0.2);
  }

  // 가게 소리: 늘 깔리는 두 겹의 잡음(필터만 가게마다 다르게) + 가끔 나는 소리
  // fire 장작 · street 천막에 듣는 빗소리와 지글지글 · crowd 웅성웅성 · quiet 조용한 와인바 · rain 지붕 빗소리
  startAmbience() {
    const ctx = this.ctx;
    const layer = () => {
      const src = ctx.createBufferSource();
      src.buffer = this.noise;
      src.loop = true;
      const f = ctx.createBiquadFilter();
      const g = ctx.createGain();
      g.gain.value = 0;
      src.connect(f).connect(g).connect(this.ambBus);
      src.start(ctx.currentTime + Math.random());
      return { f, g };
    };
    this.ambA = layer();
    this.ambB = layer();
    this.applyScene();
    this.ambBus.gain.setTargetAtTime(this.ambLevel(), ctx.currentTime, 1.5);
    const next = () => {
      if (!this.ctx) return;
      setTimeout(next, this.ambEvent());
    };
    next();
  }

  setScene(kind) {
    this.scene = kind;
    if (this.ctx && this.ambA) this.applyScene();
  }

  applyScene() {
    const P = {
      fire: [['lowpass', 280, 0.7, 0.05], ['bandpass', 3000, 1, 0]],
      street: [['lowpass', 140, 0.7, 0.06], ['highpass', 4200, 0.7, 0.012]],
      crowd: [['lowpass', 220, 0.7, 0.03], ['bandpass', 520, 0.9, 0.05]],
      quiet: [['lowpass', 180, 0.7, 0.022], ['bandpass', 650, 0.9, 0.01]],
      rain: [['lowpass', 200, 0.7, 0.03], ['bandpass', 4200, 0.5, 0.028]],
      wind: [['lowpass', 160, 0.7, 0.05], ['bandpass', 700, 0.6, 0.02]],
      city: [['lowpass', 120, 0.7, 0.06], ['bandpass', 1200, 0.5, 0.01]],
    }[this.scene] ?? [['lowpass', 280, 0.7, 0.05], ['bandpass', 3000, 1, 0]];
    const t = this.ctx.currentTime;
    [this.ambA, this.ambB].forEach((L, i) => {
      const [type, freq, q, gain] = P[i];
      L.f.type = type;
      L.f.frequency.setTargetAtTime(freq, t, 0.3);
      L.f.Q.setTargetAtTime(q, t, 0.3);
      L.g.gain.setTargetAtTime(gain, t, 0.8);
    });
  }

  // 짧게 툭: 걸러 낸 잡음 한 조각
  tick(freq, q, level, dur) {
    const ctx = this.ctx;
    const t = ctx.currentTime + 0.05;
    const s = this.noiseSource(t, dur + 0.02);
    const bp = ctx.createBiquadFilter();
    bp.type = 'bandpass';
    bp.frequency.value = freq;
    bp.Q.value = q;
    const g = ctx.createGain();
    g.gain.setValueAtTime(level, t);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    s.connect(bp).connect(g).connect(this.ambBus);
  }

  // 멀리서 잔 부딪히는 소리
  farClink(level) {
    const ctx = this.ctx;
    const t = ctx.currentTime + 0.05;
    const f = 2300 + Math.random() * 1200;
    for (const [m, a] of [[1, 1], [2.32, 0.4]]) {
      const o = ctx.createOscillator();
      o.frequency.value = f * m;
      const g = ctx.createGain();
      g.gain.setValueAtTime(level * a, t);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 0.5);
      o.connect(g).connect(this.ambBus);
      o.start(t);
      o.stop(t + 0.55);
    }
  }

  // 다음 소리까지 몇 ms
  ambEvent() {
    const r = Math.random;
    switch (this.scene) {
      case 'street':
        // 천막 위 빗방울, 가끔 큰 물방울
        if (r() < 0.04) this.tick(900 + r() * 400, 2, 0.06, 0.04);
        else this.tick(1500 + r() * 1200, 1.5, 0.008 + r() * 0.02, 0.02);
        return 25 + r() * 50;
      case 'crowd': {
        // 웅성거림이 커졌다 작아졌다, 가끔 저쪽 테이블 짠
        this.ambB.g.gain.setTargetAtTime(0.03 + r() * 0.045, this.ctx.currentTime, 0.35);
        if (r() < 0.08) this.farClink(0.012 + r() * 0.012);
        return 220 + r() * 260;
      }
      case 'wind': {
        // 바람이 불었다 잦아들었다
        const t = this.ctx.currentTime;
        this.ambA.g.gain.setTargetAtTime(0.03 + Math.random() * 0.06, t, 1.2);
        this.ambB.f.frequency.setTargetAtTime(500 + Math.random() * 600, t, 1);
        this.ambB.g.gain.setTargetAtTime(0.01 + Math.random() * 0.03, t, 1.2);
        if (r() < 0.05) this.farClink(0.008);
        return 900 + r() * 900;
      }
      case 'city': {
        // 멀리 차 지나가는 소리, 가끔 오토바이
        const t = this.ctx.currentTime;
        if (r() < 0.35) {
          const swell = 0.04 + r() * 0.05, dur = 2 + r() * 2.5;
          this.ambB.f.frequency.setTargetAtTime(400 + r() * 500, t, dur / 3);
          this.ambB.g.gain.setTargetAtTime(swell, t, dur / 3);
          this.ambB.g.gain.setTargetAtTime(0.008, t + dur * 0.6, dur / 3);
        }
        if (r() < 0.06) this.tick(2200 + r() * 800, 3, 0.012, 0.06);
        return 1200 + r() * 1800;
      }
      case 'quiet':
        if (r() < 0.025) this.farClink(0.006);
        return 400;
      case 'rain':
        if (r() < 0.03) this.tick(700 + r() * 300, 2, 0.03, 0.05);
        else this.tick(3000 + r() * 3000, 1.2, 0.005 + r() * 0.012, 0.012);
        return 14 + r() * 32;
      default: {
        const big = r() < 0.12;
        this.tick(big ? 900 + r() * 600 : 1800 + r() * 3000, 2, big ? 0.12 : 0.015 + r() * 0.04, big ? 0.05 : 0.015 + r() * 0.02);
        return 40 + r() * (r() < 0.2 ? 900 : 260);
      }
    }
  }
}
