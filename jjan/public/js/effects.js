import * as THREE from 'three';

const _m = new THREE.Matrix4();
const _c = new THREE.Color();
const _q = new THREE.Quaternion();
const _p = new THREE.Vector3();
const _s = new THREE.Vector3();
const _e = new THREE.Euler();

// 크기 곡선: 0 → 1 사이의 수명 비율 p를 받아 크기 배율을 돌려준다
const ENVELOPES = {
  steam: (p) => (0.35 + p * 1.25) * (p > 0.7 ? (1 - p) / 0.3 : 1) * Math.min(1, p * 8),
  shrink: (p) => 1 - p,
  hold: (p) => (p > 0.8 ? (1 - p) / 0.2 : 1),
  pop: (p) => Math.min(1, p * 10) * (p > 0.85 ? (1 - p) / 0.15 : 1),
};

// 인스턴스 큐브 파티클
export class Particles {
  constructor(max, material, { spin = false } = {}) {
    this.max = max;
    this.mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), material, max);
    this.mesh.count = 0;
    this.mesh.frustumCulled = false;
    this.mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
    this.mesh.setColorAt(0, _c.set(0xffffff));
    this.spin = spin;
    this.n = 0;
    this.p = new Float32Array(max * 3);
    this.v = new Float32Array(max * 3);
    this.life = new Float32Array(max);
    this.age = new Float32Array(max);
    this.size = new Float32Array(max);
    this.grav = new Float32Array(max);
    this.drag = new Float32Array(max);
    this.floor = new Float32Array(max);
    this.wob = new Float32Array(max);
    this.rot = new Float32Array(max);
    this.env = new Array(max);
    this.colors = new Float32Array(max * 3);
  }

  spawn(o) {
    if (this.n >= this.max) return;
    const i = this.n++;
    this.p[i * 3] = o.x; this.p[i * 3 + 1] = o.y; this.p[i * 3 + 2] = o.z;
    this.v[i * 3] = o.vx || 0; this.v[i * 3 + 1] = o.vy || 0; this.v[i * 3 + 2] = o.vz || 0;
    this.life[i] = o.life;
    this.age[i] = 0;
    this.size[i] = o.size;
    this.grav[i] = o.gravity || 0;
    this.drag[i] = o.drag || 0;
    this.floor[i] = o.floor ?? -Infinity;
    this.wob[i] = o.wobble || 0;
    this.rot[i] = Math.random() * 6.28;
    this.env[i] = ENVELOPES[o.env || 'shrink'];
    _c.set(o.color);
    this.colors[i * 3] = _c.r; this.colors[i * 3 + 1] = _c.g; this.colors[i * 3 + 2] = _c.b;
  }

  kill(i) {
    const j = --this.n;
    if (i === j) return;
    for (let a = 0; a < 3; a++) {
      this.p[i * 3 + a] = this.p[j * 3 + a];
      this.v[i * 3 + a] = this.v[j * 3 + a];
      this.colors[i * 3 + a] = this.colors[j * 3 + a];
    }
    this.life[i] = this.life[j]; this.age[i] = this.age[j]; this.size[i] = this.size[j];
    this.grav[i] = this.grav[j]; this.drag[i] = this.drag[j]; this.floor[i] = this.floor[j];
    this.wob[i] = this.wob[j]; this.rot[i] = this.rot[j]; this.env[i] = this.env[j];
  }

  update(dt, t) {
    for (let i = 0; i < this.n; i++) {
      this.age[i] += dt;
      if (this.age[i] >= this.life[i]) { this.kill(i); i--; continue; }
      const k = i * 3;
      this.v[k + 1] -= this.grav[i] * dt;
      const dr = Math.exp(-this.drag[i] * dt);
      this.v[k] *= dr; this.v[k + 1] *= dr; this.v[k + 2] *= dr;
      this.p[k] += this.v[k] * dt + Math.sin(t * 2.3 + this.rot[i]) * this.wob[i] * dt;
      this.p[k + 1] += this.v[k + 1] * dt;
      this.p[k + 2] += this.v[k + 2] * dt + Math.cos(t * 1.9 + this.rot[i]) * this.wob[i] * dt;
      if (this.p[k + 1] < this.floor[i]) {
        // 탁자에 떨어진 방울은 납작하게 잠깐 남는다
        this.p[k + 1] = this.floor[i];
        this.v[k] = this.v[k + 1] = this.v[k + 2] = 0;
        this.grav[i] = 0;
        this.age[i] = Math.max(this.age[i], this.life[i] * 0.8);
      }
    }
    for (let i = 0; i < this.n; i++) {
      const k = i * 3;
      const s = this.size[i] * this.env[i](this.age[i] / this.life[i]);
      _p.set(this.p[k], this.p[k + 1], this.p[k + 2]);
      if (this.spin) _q.setFromEuler(_e.set(this.rot[i] + this.age[i] * 3, this.rot[i] * 2, 0));
      else _q.identity();
      _s.set(s, s, s);
      _m.compose(_p, _q, _s);
      this.mesh.setMatrixAt(i, _m);
      _c.setRGB(this.colors[k], this.colors[k + 1], this.colors[k + 2]);
      this.mesh.setColorAt(i, _c);
    }
    // 살아 있는 입자만큼만 GPU로 올린다. 하나도 없으면 그리기 자체를 건너뛴다
    this.mesh.count = this.n;
    this.mesh.visible = this.n > 0;
    if (!this.n) return;
    const im = this.mesh.instanceMatrix, ic = this.mesh.instanceColor;
    im.addUpdateRange(0, this.n * 16);
    im.needsUpdate = true;
    if (ic) { ic.addUpdateRange(0, this.n * 3); ic.needsUpdate = true; }
  }
}

// 방 전체에서 쓰는 파티클 묶음
export class WorldFX {
  constructor(scene) {
    this.steam = new Particles(500, new THREE.MeshBasicMaterial({
      color: 0xffffff, transparent: true, opacity: 0.26, depthWrite: false,
    }));
    this.steam.mesh.renderOrder = 50;
    this.drops = new Particles(400, new THREE.MeshStandardMaterial({ roughness: 0.2 }));
    this.sparks = new Particles(200, new THREE.MeshBasicMaterial({ toneMapped: false }), { spin: true });
    this.flames = new Particles(420, new THREE.MeshBasicMaterial({ toneMapped: false }));
    for (const p of [this.steam, this.drops, this.sparks, this.flames]) scene.add(p.mesh);
  }

  update(dt, t) {
    this.steam.update(dt, t);
    this.drops.update(dt, t);
    this.sparks.update(dt, t);
    this.flames.update(dt, t);
  }

  // 잔이 부딪힌 자리에서 반짝
  clinkSparks(x, y, z, strength) {
    const n = Math.round(4 + strength * 10);
    for (let i = 0; i < n; i++) {
      const a = Math.random() * Math.PI * 2, e = (Math.random() - 0.3) * 1.4;
      const sp = 25 + Math.random() * 45 * (0.4 + strength);
      this.sparks.spawn({
        x, y, z,
        vx: Math.cos(a) * Math.cos(e) * sp, vy: Math.sin(e) * sp + 10, vz: Math.sin(a) * Math.cos(e) * sp,
        life: 0.25 + Math.random() * 0.3, size: 0.7 + Math.random() * 0.8, drag: 5,
        color: Math.random() < 0.5 ? 0xfff4d6 : 0xffd27a,
      });
    }
  }
}
