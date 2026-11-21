import * as THREE from 'three';
import { buildVessel } from './props.js';
import { BoxBuilder } from './voxel.js';

// 숙취해소제. 잔 옆에 놓였다가 들어 올려 쭉 마시고(알약은 톡 털어 넣고) 사라진다.
// 병 모양은 그릇 목록(props.js)을 그대로 쓰고, 색과 띠로만 구분한다.
export const REMEDIES = [
  { id: 'condition', name: '컨디션', desc: '갈색 작은 병, 한 번에 쭉.', v: 'liqueur', color: 0x6b3a12, label: 0xf2c63a, cap: 0xc9a23a },
  { id: 'yeomyeong', name: '여명808', desc: '짙은 병, 마시기 전에 미리.', v: 'liqueur', color: 0x2a1e18, label: 0xd8202f, cap: 0xc9a23a },
  { id: 'hutgae', name: '헛개수', desc: '시원한 헛개차 한 병.', v: 'spirit', color: 0xd9b46a, label: 0xefe2c4, cap: 0x8a5a2a },
  { id: 'sangkwae', name: '상쾌환', desc: '작은 알약 한 봉지 털어 넣기.', pouch: true, color: 0x2f8f4e, label: 0xf3efe6 },
];
export const remedyById = Object.fromEntries(REMEDIES.map((r) => [r.id, r]));

// 이만큼 취기가 내려간다, 그리고 이만큼 기다려야 또 먹을 수 있다
export const REMEDY_RELIEF = 0.45;
export const REMEDY_COOLDOWN = 20;

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const smooth = (t) => t * t * (3 - 2 * t);

let pouchGeo = null;
function buildPouch(color, label) {
  const b = new BoxBuilder();
  b.box(-2.4, 0, -0.5, 4.8, 6, 1, color, 0.03);
  b.box(-2.4, 2, -0.55, 4.8, 1.6, 1.1, label, 0.02);
  // 위쪽 뜯는 곳
  for (let i = 0; i < 5; i++) b.box(-2.4 + i * 1.0, 6, -0.4, 0.6, 0.5, 0.8, color, 0);
  return b.geometry();
}

export class RemedyFx {
  // at: 잔이 놓인 곳(월드), yaw: 잔이 놓인 방향(마시는 사람이 잔 기준 +z), scale: 잔과 같은 배율
  constructor(scene, env, id, at, yaw, scale = 1.25) {
    this.def = remedyById[id] || REMEDIES[0];
    this.env = env;
    this.t = 0;
    this.root = new THREE.Group();
    this.root.position.copy(at);
    this.root.rotation.y = yaw;
    this.holder = new THREE.Group();
    this.root.add(this.holder);
    const d = this.def;
    let geo, angle = 2.0;
    this.mat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.35, metalness: 0.05, envMap: env.envMap, envMapIntensity: 0.8 });
    if (d.pouch) {
      geo = pouchGeo ||= buildPouch(d.color, d.label);
      this.box = new THREE.Box3(new THREE.Vector3(-2.4, 0, -0.5), new THREE.Vector3(2.4, 6.5, 0.5));
    } else {
      const v = buildVessel({ v: d.v, color: d.color, label: d.label, cap: d.cap });
      geo = v.geo;
      angle = v.angle;
      this.box = v.box;
    }
    this.angle = angle;
    const mesh = new THREE.Mesh(geo, this.mat);
    mesh.castShadow = true;
    // 병은 입구가 원점이라 바닥이 탁자에 닿게 올린다
    if (!d.pouch) mesh.position.y = -this.box.min.y;
    this.holder.add(mesh);
    this.holder.scale.setScalar(0.001);
    this.base = new THREE.Vector3(-11, 0, 3);
    this.holder.position.copy(this.base);
    this.root.scale.setScalar(scale);
    scene.add(this.root);
    this.done = false;
    this.effected = false;
    this.gulps = 0;
  }

  // true를 돌려주면 끝
  update(dt) {
    this.t += dt;
    const t = this.t, h = this.holder;
    const h0 = this.def.pouch ? 6.5 : this.box.max.y - this.box.min.y;
    const pop = smooth(clamp(t / 0.3, 0, 1)) * (1 - smooth(clamp((t - 2.15) / 0.3, 0, 1)));
    h.scale.setScalar(Math.max(0.001, pop));
    // 들어 올려 마시는 사람 쪽으로
    const lift = smooth(clamp((t - 0.35) / 0.5, 0, 1)) * (1 - smooth(clamp((t - 1.85) / 0.35, 0, 1)));
    const tip = smooth(clamp((t - 0.75) / 0.35, 0, 1)) * (1 - smooth(clamp((t - 1.7) / 0.3, 0, 1)));
    h.position.set(this.base.x + 8 * lift, this.base.y + (14 + h0 * 0.2) * lift, this.base.z + 12 * lift);
    // 병은 입구가 마시는 사람 쪽으로 기울고, 봉지는 털어 넣듯 뒤집힌다
    h.rotation.set(tip * (this.def.pouch ? 2.6 : this.angle * 0.85), 0, 0);
    const audio = this.env.audio;
    if (!this.def.pouch && this.gulps < 3 && t > 1.0 + this.gulps * 0.28) { this.gulps++; audio?.gulp(); }
    if (this.def.pouch && this.gulps < 1 && t > 1.05) { this.gulps++; audio?.tap(); audio?.gulp(); }
    if (!this.effected && t > 1.2) { this.effected = true; this.onEffect?.(); }
    if (t > 2.5) { this.dispose(); return true; }
    return false;
  }

  dispose() {
    this.root.removeFromParent();
    this.mat.dispose();
    this.done = true;
  }
}
