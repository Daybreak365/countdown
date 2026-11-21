import * as THREE from 'three';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { Room, LAYOUT, TABLE_Y } from './room.js';
import { WorldFX } from './effects.js';
import { GlassEntity, SCALE } from './glass.js';
import { AudioEngine } from './audio.js';
import { Bartender } from './bartender.js';
import { AnjuPlate } from './anju.js';
import { TABLES, seatPose, anjuPose, chairPose, SQUARE, LONG, BAR_SEATS, BIGROUND, U_REGION } from './tables.js';
import { cleanEmoji, QUICK_EMOJI } from './emoji.js';
import { RemedyFx, REMEDY_RELIEF, REMEDY_COOLDOWN } from './remedy.js';
import { venueById, DEFAULT_VENUE } from './venues.js';
import { barkeepById, DEFAULT_BARKEEP } from './barkeeps.js';
import { Tab } from './receipt.js';

// 취기: 순수 알코올 이만큼(ml) 마시면 1(만취)
const ALC_PER_LEVEL = 60;
// 가만히 있으면 초당 이만큼 깬다
const SOBER_RATE = 0.0035;
const DRUNK_KEY = 'jjan:drunk';

// 잔을 들면 잔의 가운데가 이 높이(탁자 위)에 온다. 모두 같은 높이로 맞춰야 잔끼리 닿는다.
const LIFT = 30;
const CLINK_MIN_SPEED = 10;
// 잔에서 멀리 눌렀을 때는 이만큼만 떨어진 채로 따라온다
const GRAB_REACH = 48;
// 한 번 짠 하면 이만큼 떨어졌다가 다시 와야 또 짠이 된다(밀고 있는 동안 계속 울리지 않게)
const REARM = 2;
// 짠 횟수는 이만큼 떨어졌다 다시 부딪혀야 하나 더 센다 (밀고 있는 동안 튕기는 건 소리만)
const COUNT_REARM = 6;

const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
// 혼자 짠: 바텐더 몫 안주 (바 오른쪽, 바텐더가 술 만드는 자리와 떨어진 곳). 손님이 몰래 집어 먹을 수 있다
const BAR_SNACK = { x: 58, z: -137, yaw: -0.35 };
const BAR_SNACK_BY_VENUE = { bar: 'peanuts', pocha: 'tteokbokki', hof: 'nogari', wine: 'cheese', xmas: 'fruit', attic: 'nachos', izakaya: 'yakitori', rooftop: 'fries', conv: 'cupramen' };
// 손님 안주와 같으면 헷갈리니 다른 걸로
const snackFor = (venue, mine) => {
  const a = BAR_SNACK_BY_VENUE[venue] ?? 'peanuts';
  return a !== mine ? a : a === 'peanuts' ? 'nogari' : 'peanuts';
};
// 바텐더가 안주를 뺏겼을 때
// 혼자 짠: 내 반응에 바텐더가 이모지로 받아 준다 (없는 건 따라 하거나 웃어 준다)
const BARKEEP_EMOJI = {
  '👍': '😊', '😂': '🤣', '🤣': '😂', '🥴': '💧', '😍': '☺️', '🥰': '☺️', '👏': '🙇', '🔥': '🔥', '😭': '🤗', '😢': '🤗', '🍻': '🍻', '🥂': '🥂',
  '❤️': '☺️', '😡': '😅', '🤬': '😅', '😴': '☕', '🤮': '💧', '🤢': '💧', '🙏': '🙇', '💸': '😅', '🍺': '🍻', '🍶': '🍶', '🎉': '🥳', '🥳': '🎉',
};
const EMO_LIFE = 1.9;
const pickOne = (list) => list[Math.floor(Math.random() * list.length)];
const STOLEN_LINES = ['어? 그거 제 건데요!', '하나만 드세요~', '제 안주가… 맛있죠?', '그럼 저도 하나만!', '몰래 드셔도 다 보여요.', '허허, 손이 빠르시네요.'];
const smooth = (t) => t * t * (3 - 2 * t);
const _v = new THREE.Vector3();
const _v2 = new THREE.Vector3();
const _ndc = new THREE.Vector2();


export class Game {
  constructor(canvas, hooks = {}) {
    this.canvas = canvas;
    this.hooks = hooks;
    const renderer = (this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: 'high-performance' }));
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFShadowMap;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.0;

    const scene = (this.scene = new THREE.Scene());
    scene.background = new THREE.Color(0x1a120d);
    scene.fog = new THREE.Fog(0x1a120d, 420, 820);
    this.camera = new THREE.PerspectiveCamera(45, 1, 1, 1600);

    const pmrem = new THREE.PMREMGenerator(renderer);
    this.envMap = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
    pmrem.dispose();

    this.fx = new WorldFX(scene);
    // 불붙은 잔 하나를 비추는 작은 불빛 (켜고 끄면 셰이더가 다시 컴파일되므로 늘 두고 세기만 바꾼다)
    this.flameLight = new THREE.PointLight(0xff9a48, 0, 70, 0);
    scene.add(this.flameLight);
    this.room = new Room(scene, this.fx);
    this.audio = new AudioEngine();
    this.env = { envMap: this.envMap, fx: this.fx, audio: this.audio, tableY: TABLE_Y };
    this.bartender = new Bartender(scene, this.env);
    this.bartender.onSay = (text) => this.bartenderSay(text);

    this.mode = 'title';
    // 같이 짠 탁자 종류 (tables.js)
    this.table = 'round';
    this.me = null;
    this.myId = 'me';
    this.phys = { pos: new THREE.Vector3(), vel: new THREE.Vector3(), target: new THREE.Vector3(), home: new THREE.Vector3(), held: false };
    this.remotes = new Map();
    this.decor = [];
    this.decorAnju = [];
    this.myAnju = null;
    this.clinkMemo = new Map();
    // 한 번 센 짝은 잔이 충분히 떨어졌다 다시 와야 또 센다
    this.countSpent = new Set();
    this.countAt = new Map();
    this.touching = new Set();
    this.net = null;
    this.poseTimer = 0;
    this.time = 0;
    this.camPos = new THREE.Vector3(0, 170, 160);
    this.camLook = new THREE.Vector3(0, 90, 0);
    this.raycaster = new THREE.Raycaster();
    this.plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), -(TABLE_Y + LIFT));
    this.inputEnabled = false;
    this.labelLayer = hooks.labelLayer;
    // 바텐더 말풍선
    this.bartenderHolder = {};
    this.emos = [];
    this.emoSent = [];
    // 바텐더 얼굴 옆 (이모지 답장이 뜨는 자리)
    const bt = this.bartender;
    this.bkFace = { get body() { return bt.head; }, topLocal: (out) => out.set(0, 8, 0) };
    // 취기: drunkTarget은 마신 만큼 바로 오르고, drunk(보이는 취기)는 천천히 따라 오른다
    this.drunk = 0;
    this.drunkTarget = 0;
    this.loadDrunk();
    addEventListener('pagehide', () => this.saveDrunk());
    this.hicT = 20;
    this.remedies = [];
    this.remedyReadyAt = 0;
    // 계산서 (나갈 때 영수증)
    this.tab = new Tab();
    // 둘러보기: 자리에 앉은 채 고개를 돌린다 (yaw 좌우, pitch 위아래, 둘 다 원래 보던 방향에서 얼마나)
    this.lookMode = false;
    this.lookYaw = this.lookPitch = 0;
    this.lookYawGoal = this.lookPitchGoal = 0;
    this.lookZoom = 1;
    // 둘러볼 때는 화면을 잡던 자리에서 내가 앉은 의자의 눈높이로 옮겨 간다 (0 → 1)
    this.lookBlend = 0;

    this.bindInput();
    this.resize();
    window.addEventListener('resize', () => this.resize());
    this.setTitle();
    this.clock = new THREE.Timer();
    renderer.setAnimationLoop(() => this.frame());
  }

  // ── 모드 ───────────────────────────────────────────────
  clearAll() {
    this.me?.dispose();
    this.me = null;
    this.serveFrom = null;
    for (const r of this.remotes.values()) this.removeRemote(r.id);
    for (const d of this.decor) d.dispose();
    this.decor = [];
    for (const a of this.decorAnju || []) a.dispose();
    this.decorAnju = [];
    this.myAnju?.dispose();
    this.myAnju = null;
    this.barAnju?.dispose();
    this.barAnju = null;
    clearTimeout(this.payback);
    this.myLabel?.remove();
    this.myLabel = null;
    for (const holder of [this, this.bartenderHolder]) {
      holder.bubbleEl?.remove();
      holder.bubbleEl = null;
      clearTimeout(holder.bubbleTimer);
    }
    for (const e of this.emos) e.el.remove();
    this.emos.length = 0;
    this.emoSent.length = 0;
    this.phys.held = false;
    this.touching.clear();
    this.countSpent.clear();
    this.bartender.removeGlass();
    for (const fx of this.remedies) fx.dispose();
    this.remedies = [];
  }

  // 가게 옮기기: 방, 바텐더 옷차림, 가게 소리가 같이 바뀐다
  setVenue(id) {
    if (!venueById[id]) id = DEFAULT_VENUE;
    this.venue = id;
    this.tab.venue = id;
    if (!this.room.setVenue(id)) return false;
    this.bartender.setStyle(id, this.barkeepFor?.(id) ?? DEFAULT_BARKEEP);
    if (this.barAnju) {
      const want = snackFor(id, this.myAnju?.def.id);
      if (this.barAnju.def.id !== want) this.barAnju.set(want);
    }
    this.audio.setScene?.(this.room.look.ambience);
    // 가게마다 불빛이 달라서 그림자 지도를 다시 그린다
    this.renderer.shadowMap.needsUpdate = true;
    return true;
  }

  get venueInfo() {
    return venueById[this.venue ?? DEFAULT_VENUE];
  }

  // 바텐더 바꾸기 (그 가게에서 못 고르는 사람이면 기본 바텐더)
  setBarkeep(person, greet = true) {
    const before = this.bartender.person;
    this.bartender.setStyle(this.venue ?? DEFAULT_VENUE, person);
    if (greet && before !== this.bartender.person && this.mode === 'single') this.bartenderSay(this.bartender.lines.hello[0]);
    return this.bartender.person;
  }

  // 바텐더를 부르는 말 (힌트, 안주 뺏기)
  get hostName() {
    return barkeepById[this.bartender.person]?.host ?? this.venueInfo.host;
  }

  // 둘러보기 켜고 끄기. 끄면 고개가 천천히 제자리로 돌아온다
  setLook(on) {
    on = !!on && this.mode !== 'title';
    this.lookMode = on;
    if (on) {
      this.phys.held = false;
      this.pointerId = null;
    } else {
      // 돌아올 때는 가까운 쪽으로 돌아온다
      const TAU = Math.PI * 2;
      this.lookYaw = ((this.lookYaw % TAU) + TAU + Math.PI) % TAU - Math.PI;
      this.lookYawGoal = this.lookPitchGoal = 0;
      this.lookZoom = 1;
    }
    this.hooks.onLook?.(on);
    return on;
  }

  // 눌린 자리에 무엇이 있나: 노래 기물, 내 안주, 남의 안주
  tapAt(e) {
    if (this.hitAnju(e)) { this.eatAnju(); return true; }
    const other = this.hitOtherAnju(e);
    if (other) { this.stealAnju(other); return true; }
    if (this.hitDevice(e)) { this.hooks.onDevice?.(); return true; }
    return false;
  }

  // 노래 기물을 눌렀나
  hitDevice(e) {
    const d = this.room.device;
    if (!d || this.mode === 'title') return false;
    const rect = this.canvas.getBoundingClientRect();
    _ndc.set(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.camera.updateMatrixWorld();
    this.raycaster.setFromCamera(_ndc, this.camera);
    return !!this.raycaster.ray.intersectBox(d.bounds(), _v);
  }

  setTitle(table = 'round') {
    this.clearAll();
    this.mode = 'title';
    this.useTable(table);
    this.inputEnabled = false;
    const picks = [['lager', null, 0, 'chicken'], ['red', null, 3, 'cheese'], ['tea', null, 5, null], ['somaek', null, 6, 'nogari']];
    // 원형 탁자가 아니면 그 탁자의 앞자리부터 잔을 놓아 둔다
    if (table !== 'round') picks.forEach((p, i) => { p[2] = TABLES[table].order[i]; });
    for (const [drink, glass, seat, anju] of picks) {
      if (seat == null) continue;
      const e = new GlassEntity(this.env, { drinkId: drink, glassId: glass, yaw: this.seatYaw(seat) });
      this.seatHome(seat, e.root.position);
      this.scene.add(e.root);
      this.decor.push(e);
      if (anju) this.decorAnju.push(this.placeAnju(anju, this.seatAnjuPos(seat), this.seatYaw(seat)));
    }
  }

  // ── 탁자와 자리 ────────────────────────────────────────
  // 방 만들 때 고르는 탁자를 타이틀 뒤에 미리 보여 준다
  previewTable(table) {
    if (this.mode === 'title' && this.table !== table) this.setTitle(table);
  }

  useTable(table) {
    this.table = table;
    this.room.setTable(table);
  }

  seatYaw(seat) {
    return seatPose(this.table, seat).yaw;
  }

  seatHome(seat, out = new THREE.Vector3()) {
    const p = seatPose(this.table, seat);
    return out.set(p.x, TABLE_Y, p.z);
  }

  seatAnjuPos(seat) {
    // 내 잔 오른쪽, 탁자 가운데 쪽으로 조금 들어간 자리
    const p = anjuPose(this.table, seat);
    return new THREE.Vector3(p.x, TABLE_Y, p.z);
  }

  // 사람이 늘어 큰 탁자로 옮겨 앉는다. seats: { id: 새 자리 }
  moveTable(table, seats) {
    this.useTable(table);
    const mine = seats[this.myId];
    if (mine != null && this.me) {
      this.mySeat = mine;
      const P = this.phys, yaw = this.seatYaw(mine);
      this.seatHome(mine, P.home);
      P.pos.copy(P.home);
      P.target.copy(P.home);
      P.vel.set(0, 0, 0);
      P.held = false;
      this.me.yaw = yaw;
      this.me.root.rotation.y = yaw;
      this.me.root.position.copy(P.home);
      this.moveAnju(this.myAnju, mine);
    }
    for (const r of this.remotes.values()) {
      const s = seats[r.id];
      if (s == null) continue;
      r.seat = s;
      const yaw = this.seatYaw(s);
      r.entity.yaw = yaw;
      r.entity.root.rotation.y = yaw;
      // 새 자리 포즈가 오기 전까지는 새 자리에 둔다
      r.snaps.length = 0;
      this.seatHome(s, r.entity.root.position);
      this.moveAnju(r.anju, s);
    }
    this.touching.clear();
    this.countSpent.clear();
  }

  moveAnju(plate, seat) {
    if (!plate) return;
    plate.root.position.copy(this.seatAnjuPos(seat));
    plate.root.rotation.y = this.seatYaw(seat);
  }

  startSingle(drinkId, glassId, anjuId, variant = null) {
    this.clearAll();
    this.mode = 'single';
    this.useTable('round');
    this.myId = 'me';
    this.spawnMe(drinkId, glassId, 0, LAYOUT.single.home, false, variant);
    this.myAnju = this.placeAnju(anjuId, LAYOUT.single.anju, 0);
    this.openTab({ mode: 'single' }, drinkId, variant, this.myAnju.def.id);
    this.barAnju = this.placeAnju(snackFor(this.venue, anjuId), new THREE.Vector3(BAR_SNACK.x, TABLE_Y, BAR_SNACK.z), BAR_SNACK.yaw);
    this.barStolen = 0;
    // 바텐더가 내 잔부터 만들어 따르고, 자기 것도 같은 걸로 한 잔. 처음엔 빈 잔이 바텐더 앞에 있다
    this.bartender.setDrink(drinkId, glassId, { greet: true, guest: this.me, variant });
    if (this.me.serveSpot) {
      this.phys.pos.copy(this.me.serveSpot);
      this.me.root.position.copy(this.me.serveSpot);
    }
  }

  startMulti(you, peers, net, table = 'round') {
    this.clearAll();
    this.mode = 'multi';
    this.useTable(table);
    this.net = net;
    this.myId = you.id;
    this.mySeat = you.seat;
    this.spawnMe(you.drink, you.glass, this.seatYaw(you.seat), this.seatHome(you.seat), true, you.variant);
    this.myAnju = this.placeAnju(you.anju, this.seatAnjuPos(you.seat), this.seatYaw(you.seat));
    this.openTab({ mode: 'multi', table, seat: you.seat }, you.drink, you.variant, this.myAnju.def.id);
    this.myLabel = this.makeLabel(you.name, true);
    for (const p of peers) this.addRemote(p, false);
  }

  // 계산서 새로 펴기: 첫 잔과 첫 안주부터 적는다
  openTab(meta, drinkId, variant, anjuId) {
    this.tab.reset({ venue: this.venue, ...meta });
    this.tab.drink(drinkId, variant, this.venue);
    if (anjuId) this.tab.anju(anjuId, this.venue);
    this.myAnju.onRefill = () => this.tab.anju(this.myAnju.def.id, this.venue);
  }

  leave() {
    this.net = null;
    this.setTitle();
  }

  spawnMe(drinkId, glassId, yaw, home, serve = true, variant = null) {
    const e = (this.me = new GlassEntity(this.env, { drinkId, glassId, variant, yaw, local: true }));
    e.onDrink = (ml) => this.addAlcohol(ml);
    this.phys.home.copy(home);
    this.phys.pos.copy(home);
    this.phys.vel.set(0, 0, 0);
    this.phys.target.copy(home);
    e.root.position.copy(home);
    this.scene.add(e.root);
    if (serve) e.serve();
    this.inputEnabled = true;
  }

  setMyDrink(drinkId, glassId, variant = null) {
    if (!this.me) return;
    this.me.set(drinkId, glassId, variant);
    this.tab.drink(drinkId, variant, this.venue);
    if (this.mode === 'single') this.bartender.setDrink(drinkId, glassId, { guest: this.me, variant });
    else this.me.serve();
    this.sendSet();
  }

  setMyAnju(anjuId) {
    if (!this.myAnju || this.myAnju.def.id === anjuId) return;
    this.myAnju.set(anjuId);
    this.tab.anju(anjuId, this.venue);
    if (this.barAnju && this.barAnju.def.id === anjuId) this.barAnju.set(snackFor(this.venue, anjuId));
    if (this.mode === 'single') this.bartender.say('안주 나왔습니다!', 300);
    this.sendSet();
  }

  sendSet() {
    if (!this.me) return;
    this.net?.send({ t: 'set', drink: this.me.drink.id, variant: this.me.drink.variant ?? '', glass: this.me.glassDef.id, anju: this.myAnju?.def.id });
  }

  // ── 안주 ───────────────────────────────────────────────
  placeAnju(id, pos, yaw) {
    const plate = new AnjuPlate(this.env, id, { yaw });
    plate.root.position.copy(pos);
    this.scene.add(plate.root);
    return plate;
  }

  eatAnju() {
    if (!this.myAnju?.eat()) return false;
    this.net?.send({ t: 'act', a: 'eat' });
    if (this.mode === 'single' && Math.random() < 0.25) this.bartender.say(Math.random() < 0.5 ? '맛있죠?' : '더 드릴까요?', 500);
    return true;
  }

  hitAnju(e) {
    if (!this.myAnju) return false;
    const rect = this.canvas.getBoundingClientRect();
    _ndc.set(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.camera.updateMatrixWorld();
    this.myAnju.root.updateMatrixWorld(true);
    this.raycaster.setFromCamera(_ndc, this.camera);
    return this.raycaster.intersectObjects(this.myAnju.meshes, false).length > 0;
  }

  // 남의 안주 접시를 눌렀나: { plate, remote } 또는 바텐더 몫이면 { plate, bartender: true }
  hitOtherAnju(e) {
    const list = [];
    for (const r of this.remotes.values()) if (r.anju) list.push({ plate: r.anju, remote: r });
    if (this.barAnju) list.push({ plate: this.barAnju, bartender: true });
    if (!list.length) return null;
    const rect = this.canvas.getBoundingClientRect();
    _ndc.set(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
    this.camera.updateMatrixWorld();
    this.raycaster.setFromCamera(_ndc, this.camera);
    let best = null, bd = Infinity;
    for (const o of list) {
      o.plate.root.updateMatrixWorld(true);
      const hit = this.raycaster.intersectObjects(o.plate.meshes, false)[0];
      if (hit && hit.distance < bd) { bd = hit.distance; best = o; }
    }
    return best;
  }

  // 안주 조각이 날아갈 곳: 먹는 사람 입 쪽
  mouthOf(who) {
    if (who === 'bartender') return this.bartender.head.localToWorld(new THREE.Vector3(0, 8, 8));
    if (who === this.myId || who === 'me') {
      // 나는 화면 앞이 입이다: 내 잔 위에서 카메라 쪽으로
      const p = this.me ? this.me.root.position.clone() : new THREE.Vector3(0, TABLE_Y, 0);
      p.y += 30;
      return p.lerp(this.camera.position, 0.55);
    }
    const r = this.remotes.get(who);
    if (!r) return null;
    const yaw = this.seatYaw(r.seat), p = r.entity.root.position.clone();
    return p.set(p.x + Math.sin(yaw) * 26, TABLE_Y + 44, p.z + Math.cos(yaw) * 26);
  }

  // 남의 안주 한 입 뺏어 먹기
  stealAnju(target) {
    const { plate, remote, bartender } = target;
    if (plate.left <= 0) {
      this.hooks.onStealEmpty?.(remote ? remote.name : this.hostName);
      return false;
    }
    plate.snatch(this.mouthOf(this.myId));
    this.tab.stole++;
    this.showBubble(this.me, '냠!', true);
    this.net?.send({ t: 'act', a: 'steal', v: remote?.id ?? '' });
    if (bartender && this.mode === 'single') {
      const n = ++this.barStolen;
      this.bartender.say(STOLEN_LINES[(n - 1) % STOLEN_LINES.length], 350);
      // 몇 번 뺏기면 바텐더도 손님 안주를 하나 집어 간다
      if (n % 3 === 0 || Math.random() < 0.2) {
        clearTimeout(this.payback);
        this.payback = setTimeout(() => {
          if (this.mode !== 'single' || !this.myAnju?.left) return;
          this.myAnju.snatch(this.mouthOf('bartender'));
          this.bartender.say('저도 하나만 먹을게요. 히히', 0);
        }, 2600 + Math.random() * 2400);
      }
    }
    this.hooks.onSteal?.(remote ? remote.name : null);
    return true;
  }

  sip() {
    if (!this.me?.sip()) return;
    this.net?.send({ t: 'act', a: 'sip' });
    if (this.mode === 'single') this.bartender.followSip();
  }

  refill() {
    if (!this.me || this.me.busy) return;
    this.tab.drink(this.me.drink.id, this.me.drink.variant, this.venue);
    // 혼자 짠: 바텐더가 채워 준다
    if (this.mode === 'single') this.bartender.serveGuest(this.me, { fromEmpty: false });
    else this.me.serve({ fromEmpty: false });
    this.net?.send({ t: 'act', a: 'refill' });
  }

  say(i, text) {
    this.showBubble(this.me, text, true);
    this.net?.send({ t: 'say', i });
  }

  // ── 이모지 반응 ─────────────────────────────────────────
  // 너무 빨리 누르면 (3초에 8개 넘게) 잠깐 쉰다
  react(emoji) {
    const ch = cleanEmoji(emoji);
    if (!ch || !this.me || this.mode === 'title') return false;
    const now = this.time;
    while (this.emoSent.length && now - this.emoSent[0] > 3) this.emoSent.shift();
    if (this.emoSent.length >= 8) return false;
    this.emoSent.push(now);
    this.spawnEmoji(this.me, ch, this.mode === 'multi' ? 16 : 8);
    this.net?.send({ t: 'emo', e: ch });
    if (this.mode === 'single' && Math.random() < 0.75) {
      const back = BARKEEP_EMOJI[ch] ?? (Math.random() < 0.65 ? ch : pickOne(['😊', '😆', '👍', '😮']));
      clearTimeout(this.emoReply);
      this.emoReply = setTimeout(() => {
        if (this.mode !== 'single') return;
        // 바텐더는 머리가 화면 위쪽이라 얼굴 옆에서 조금만 떠오른다
        this.spawnEmoji(this.bkFace, back, 0, { dx: (this.width < 640 ? 96 : 66) + Math.random() * 12, riseK: 0.55 });
        if (ch === '😭' && Math.random() < 0.5) this.bartenderSay('무슨 일 있었어요? 한 잔 하면서 얘기해요.');
        if (ch === '🥴' && Math.random() < 0.5) this.bartenderSay('물 한 잔 드릴까요?');
      }, 600 + Math.random() * 500);
    }
    return true;
  }

  // 머리(잔) 위에서 톡 튀어나와 둥실 올라가다 사라진다
  spawnEmoji(entity, ch, lift, { dx = (Math.random() - 0.5) * 34, drop = 0, riseK = 1 } = {}) {
    if (!this.labelLayer || !entity) return;
    if (this.emos.length > 40) this.emos.shift().el.remove();
    const el = document.createElement('div');
    el.className = 'emo';
    el.textContent = ch;
    el.style.opacity = '0';
    this.labelLayer.appendChild(el);
    this.emos.push({ el, entity, lift, t0: this.time, dx, drop, riseK, ph: Math.random() * 6 });
  }

  updateEmojis() {
    if (!this.emos.length) return;
    const now = this.time;
    this.emos = this.emos.filter((e) => {
      const a = now - e.t0;
      if (a > EMO_LIFE) { e.el.remove(); return false; }
      const sp = this.labelSpot(e.el, e.entity, e.lift);
      if (!sp) return true;
      const pop = a < 0.12 ? 0.4 + (a / 0.12) * 0.8 : a < 0.26 ? 1.2 - ((a - 0.12) / 0.14) * 0.2 : 1;
      const rise = 18 + a * 46 + (1 - Math.exp(-a * 6)) * 14;
      const x = sp.x + e.dx + Math.sin(a * 4 + e.ph) * 5;
      // 위쪽 버튼 줄에 가리지 않게
      const y = Math.max(sp.y + e.drop - rise * e.riseK, (this.width < 640 ? 96 : 44) + 30);
      e.el.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px) translate(-50%, -100%) scale(${pop.toFixed(3)})`;
      e.el.style.opacity = String(Math.min(1, a / 0.08, (EMO_LIFE - a) / 0.5).toFixed(2));
      return true;
    });
  }

  // ── 취기 ───────────────────────────────────────────────
  loadDrunk() {
    try {
      const v = JSON.parse(localStorage.getItem(DRUNK_KEY) || 'null');
      if (v && Number.isFinite(v.v)) {
        // 꺼 둔 동안에도 깼다
        const left = Math.max(0, v.v - ((Date.now() - v.t) / 1000) * SOBER_RATE);
        this.drunk = this.drunkTarget = Math.min(1.6, left);
      }
    } catch { /* 저장 못 해도 괜찮다 */ }
  }

  saveDrunk() {
    try { localStorage.setItem(DRUNK_KEY, JSON.stringify({ v: +this.drunkTarget.toFixed(3), t: Date.now() })); } catch { /* 괜찮다 */ }
  }

  addAlcohol(ml) {
    if (!(ml > 0)) return;
    this.drunkTarget = Math.min(1.6, this.drunkTarget + ml / ALC_PER_LEVEL);
    this.saveDrunk();
  }

  updateDrunk(dt) {
    this.drunkTarget = Math.max(0, this.drunkTarget - SOBER_RATE * dt);
    // 술기운은 몇 초에 걸쳐 오르고, 깰 때(해장)도 몇 초에 걸쳐
    if (this.drunk < this.drunkTarget) this.drunk = Math.min(this.drunkTarget, this.drunk + dt * 0.06);
    else this.drunk = Math.max(this.drunkTarget, this.drunk - dt * 0.12);
    if (this.mode !== 'title') this.tab.drunkPeak = Math.max(this.tab.drunkPeak, this.drunk);
    this.saveT = (this.saveT || 0) + dt;
    if (this.saveT > 5) { this.saveT = 0; this.saveDrunk(); }
    // 많이 취하면 가끔 딸꾹
    if (this.me && this.mode !== 'title' && this.drunk > 0.5) {
      this.hicT -= dt;
      if (this.hicT <= 0) {
        this.hicT = (10 + Math.random() * 18) / Math.min(1.5, this.drunk);
        this.hiccup();
      }
    }
  }

  hiccup() {
    this.audio.hic(0);
    this.showBubble(this.me, '딸꾹!', true);
    this.me.hit(0.25, 0, 1);
    this.net?.send({ t: 'say', i: 20 });
  }

  // 숙취해소제
  get remedyWait() {
    return Math.max(0, this.remedyReadyAt - this.time);
  }

  drinkRemedy(id) {
    if (!this.me || this.remedyWait > 0) return false;
    this.remedyReadyAt = this.time + REMEDY_COOLDOWN;
    this.tab.remedy(id, this.venue);
    const fx = this.spawnRemedy(id, this.me);
    fx.onEffect = () => {
      this.drunkTarget = Math.max(0, this.drunkTarget - REMEDY_RELIEF);
      this.hicT = 30;
      this.saveDrunk();
    };
    this.net?.send({ t: 'act', a: 'remedy', v: id });
    if (this.mode === 'single') this.bartender.say(this.drunk > 0.6 ? '이거 드시고 물도 한 잔 드세요.' : '내일 아침이 편할 거예요.', 400);
    return true;
  }

  spawnRemedy(id, entity) {
    const fx = new RemedyFx(this.scene, this.env, id, entity.root.position.clone().setY(TABLE_Y), entity.yaw, SCALE);
    this.remedies.push(fx);
    return fx;
  }

  // 원샷
  oneShot() {
    if (!this.me?.oneShot()) return false;
    this.showBubble(this.me, '원샷!', true);
    this.net?.send({ t: 'act', a: 'shot' });
    if (this.mode === 'single') this.bartender.followSip({ shot: true });
    return true;
  }

  // 취해서 흔들리는 화면 (카메라만 흔든다: 그리는 비용은 그대로)
  drunkCamera(t) {
    const w = this.mode === 'title' ? 0 : Math.pow(Math.min(1.4, this.drunk), 1.3);
    const cam = this.camera;
    if (w < 0.01) {
      if (cam.up.x !== 0) { cam.up.set(0, 1, 0); cam.lookAt(this.camLook); }
      const f = this.fovBase ?? this.baseFov;
      if (cam.fov !== f) { cam.fov = f; cam.updateProjectionMatrix(); }
      return;
    }
    cam.position.x += Math.sin(t * 0.43) * 4 * w;
    cam.position.y += Math.sin(t * 0.9 + 1) * 1.6 * w;
    _v.copy(this.camLook);
    _v.x += Math.sin(t * 0.71) * 6 * w;
    _v.y += Math.sin(t * 0.53 + 2) * 2.5 * w;
    _v.z += Math.cos(t * 0.61) * 3 * w;
    cam.up.set(Math.sin(t * 0.37) * 0.08 * w, 1, 0).normalize();
    cam.lookAt(_v);
    cam.fov = (this.fovBase ?? this.baseFov) + Math.sin(t * 0.8) * 2.2 * w;
    cam.updateProjectionMatrix();
  }

  // ── 다른 사람 ──────────────────────────────────────────
  addRemote(p, announce = true) {
    if (this.remotes.has(p.id)) return;
    const e = new GlassEntity(this.env, { drinkId: p.drink, glassId: p.glass, variant: p.variant, yaw: this.seatYaw(p.seat) });
    this.seatHome(p.seat, e.root.position);
    e.netFill = p.fill ?? 1;
    e.fill = p.fill ?? 1;
    e.peerId = p.id;
    this.scene.add(e.root);
    const r = { id: p.id, name: p.name, seat: p.seat, entity: e, snaps: [], label: this.makeLabel(p.name, false) };
    if (this.mode === 'multi') this.tab.friends.add(p.name);
    r.anju = this.placeAnju(p.anju, this.seatAnjuPos(p.seat), this.seatYaw(p.seat));
    this.remotes.set(p.id, r);
    if (announce) e.serve();
    // 먼저 와 있던 사람의 B-52는 이미 불이 붙어 있다
    else if (e.drink.flame && e.fill > 0.5) e.ignite(true);
    return r;
  }

  setRemoteDrunk(r, d) {
    r.drunk = d;
    const stage = d > 0.8 ? 'drunk' : d > 0.45 ? 'tipsy' : '';
    if (r.label && r.labelStage !== stage) {
      r.labelStage = stage;
      r.label.classList.toggle('tipsy', stage === 'tipsy');
      r.label.classList.toggle('drunk', stage === 'drunk');
    }
  }

  removeRemote(id) {
    const r = this.remotes.get(id);
    if (!r) return;
    r.entity.dispose();
    r.anju?.dispose();
    r.label?.remove();
    r.bubbleEl?.remove();
    clearTimeout(r.bubbleTimer);
    this.emos = this.emos.filter((e) => (e.entity === r.entity ? (e.el.remove(), false) : true));
    this.remotes.delete(id);
  }

  onNet(msg) {
    const r = msg.id ? this.remotes.get(msg.id) : null;
    switch (msg.t) {
      case 'peer-join':
        this.addRemote(msg.player);
        break;
      case 'peer-leave':
        this.removeRemote(msg.id);
        break;
      case 'pose':
        if (!r) break;
        r.snaps.push({ t: performance.now(), p: msg.p, r: msg.r, h: msg.h });
        if (r.snaps.length > 30) r.snaps.shift();
        r.entity.netFill = msg.f;
        if (msg.d != null) this.setRemoteDrunk(r, msg.d);
        break;
      case 'set':
        if (!r) break;
        if (msg.drink !== r.entity.drink.id || msg.glass !== r.entity.glassDef.id || (msg.variant || null) !== (r.entity.drink.variant ?? null)) {
          r.entity.set(msg.drink, msg.glass, msg.variant);
          r.entity.serve();
        }
        if (msg.anju && r.anju && msg.anju !== r.anju.def.id) r.anju.set(msg.anju);
        break;
      case 'act':
        if (!r) break;
        if (msg.a === 'refill') r.entity.serve({ fromEmpty: false });
        // 불붙은 술은 친구 화면에서도 빨대로 마신다
        else if (msg.a === 'sip' && r.entity.flameOn) r.entity.strawSip();
        else if (msg.a === 'eat') r.anju?.eat();
        else if (msg.a === 'shot') { if (r.entity.flameOn) r.entity.strawSip(); this.showBubble(r.entity, '원샷!', false, r); }
        else if (msg.a === 'remedy') this.spawnRemedy(msg.v, r.entity);
        else if (msg.a === 'steal') {
          // 누가 누구 안주를 뺏어 먹었다: 그 접시에서 한 조각이 그 사람 입으로
          const mine = msg.v === this.myId;
          const plate = mine ? this.myAnju : this.remotes.get(msg.v)?.anju;
          if (plate?.snatch(this.mouthOf(r.id) ?? r.entity.root.position)) {
            this.showBubble(r.entity, '냠!', false, r);
            if (mine) { this.tab.lost++; this.hooks.onStolen?.(r.name); }
          }
        }
        break;
      case 'say':
        if (r) this.showBubble(r.entity, this.hooks.phrase?.(msg.i) ?? '짠!', false, r);
        break;
      case 'emo':
        if (r) {
          const ch = msg.e !== undefined ? cleanEmoji(msg.e) : QUICK_EMOJI[msg.i];
          if (ch) this.spawnEmoji(r.entity, ch, 16);
        }
        break;
      case 'clink':
        this.onRemoteClink(msg);
        break;
      case 'table':
        this.moveTable(msg.table, msg.seats);
        break;
    }
  }

  entityById(id) {
    if (id === this.myId) return this.me;
    return this.remotes.get(id)?.entity ?? null;
  }

  onRemoteClink({ from, with: other, s }) {
    const a = this.entityById(from), b = this.entityById(other);
    if (!a || !b) return;
    const key = [from, other].sort().join(':');
    const now = performance.now();
    const fresh = now - (this.clinkMemo.get(key) ?? -1e9) > 350;
    this.clinkMemo.set(key, now);
    const n = _v.subVectors(b.root.position, a.root.position).setY(0);
    const d = n.length() || 1;
    n.divideScalar(d);
    if (fresh) {
      const mid = _v2.addVectors(a.root.position, b.root.position).multiplyScalar(0.5);
      mid.y += (a.contactMid + b.contactMid) / 2;
      this.audio.clink(this.soundInfo(a), this.soundInfo(b), s, this.panAt(mid));
      this.fx.clinkSparks(mid.x, mid.y, mid.z, s);
      a.hit(s * 0.8, -n.x, -n.z);
      b.hit(s * 0.8, n.x, n.z);
      if (b === this.me || a === this.me) this.countClink(key, s);
    }
  }

  // 부딪힌 뒤 튕겨서 한 번 더 닿는 건 소리만 내고 세지 않는다
  countClink(key, s) {
    const now = performance.now();
    if (this.countSpent.has(key) || now - (this.countAt.get(key) ?? -1e9) < 650) return false;
    this.countSpent.add(key);
    this.countAt.set(key, now);
    if (this.mode !== 'title') this.tab.clinks++;
    this.hooks.onClink?.(s);
    return true;
  }

  // ── 소리 ───────────────────────────────────────────────
  soundInfo(e) {
    return { material: e.glassDef.material, size: e.shape.contactR, ice: !!e.drink.ice };
  }

  panAt(p) {
    _v2.copy(p).project(this.camera);
    return clamp(_v2.x * 0.7, -0.8, 0.8);
  }

  // ── 입력 ───────────────────────────────────────────────
  bindInput() {
    const c = this.canvas;
    const P = this.phys;
    const grabOff = new THREE.Vector3();
    const hit = (e) => {
      const rect = c.getBoundingClientRect();
      _ndc.set(((e.clientX - rect.left) / rect.width) * 2 - 1, -((e.clientY - rect.top) / rect.height) * 2 + 1);
      this.raycaster.setFromCamera(_ndc, this.camera);
      return this.raycaster.ray.intersectPlane(this.plane, _v);
    };
    const update = (e) => {
      if (hit(e)) P.target.set(_v.x + grabOff.x, 0, _v.z + grabOff.z);
    };
    c.addEventListener('pointerdown', (e) => {
      if (!this.inputEnabled || !this.me) return;
      this.audio.unlock();
      // 둘러보기: 끌면 고개를 돌리고, 끌지 않고 떼면 그 자리를 누른 것
      if (this.lookMode) {
        if (this.lookDrag) return;
        c.setPointerCapture(e.pointerId);
        this.lookDrag = { id: e.pointerId, x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, moved: false };
        return;
      }
      // 안주 접시를 누르면 한 입 (남의 접시면 한 입 뺏어 먹기), 노래 기물을 누르면 신청곡 창
      if (this.tapAt(e)) return;
      c.setPointerCapture(e.pointerId);
      P.held = true;
      this.pointerId = e.pointerId;
      // 누른 점은 들어 올릴 높이보다 낮아서 그대로 쓰면 잔이 앞으로 튄다. 잡은 자리를 기억해 둔다.
      grabOff.set(0, 0, 0);
      if (hit(e)) {
        grabOff.set(P.pos.x - _v.x, 0, P.pos.z - _v.z);
        if (grabOff.length() > GRAB_REACH) grabOff.setLength(GRAB_REACH);
      }
      update(e);
      this.hooks.onGrab?.();
    });
    c.addEventListener('pointermove', (e) => {
      const L = this.lookDrag;
      if (L && e.pointerId === L.id) {
        const dx = e.clientX - L.x, dy = e.clientY - L.y;
        L.x = e.clientX; L.y = e.clientY;
        if (Math.hypot(e.clientX - L.sx, e.clientY - L.sy) > 6) L.moved = true;
        // 화면을 잡아 끄는 느낌: 오른쪽으로 끌면 왼쪽을 본다. 넓게 보고 있을수록 조금 더 돈다
        const k = (this.camera.fov / 45) * (e.pointerType === 'touch' ? 0.0065 : 0.0048);
        this.lookYawGoal += dx * k;
        this.lookPitchGoal = clamp(this.lookPitchGoal + dy * k, -1.0, 0.85);
        return;
      }
      if (this.phys.held && e.pointerId === this.pointerId) { update(e); return; }
      // 마우스를 기물에 올리면 누를 수 있다는 표시
      if (e.pointerType === 'mouse' && this.inputEnabled) {
        const on = this.hitDevice(e);
        if (on !== this.hoverDevice) { this.hoverDevice = on; c.style.cursor = on ? 'pointer' : ''; }
      }
    });
    const release = (e) => {
      const L = this.lookDrag;
      if (L && e.pointerId === L.id) {
        this.lookDrag = null;
        if (!L.moved && e.type === 'pointerup') this.tapAt(e);
        return;
      }
      if (e.pointerId !== this.pointerId) return;
      this.phys.held = false;
      this.pointerId = null;
    };
    c.addEventListener('pointerup', release);
    c.addEventListener('pointercancel', release);
    // 둘러볼 때는 휠로 당겨 보기
    c.addEventListener('wheel', (e) => {
      if (!this.lookMode) return;
      e.preventDefault();
      this.lookZoom = clamp(this.lookZoom * Math.exp(e.deltaY * 0.0012), 0.45, 1.25);
    }, { passive: false });
    // 화살표 키로도 돌아본다
    addEventListener('keydown', (e) => {
      if (!this.lookMode || e.target?.closest?.('input, textarea')) return;
      const step = 0.12;
      if (e.key === 'ArrowLeft') this.lookYawGoal += step;
      else if (e.key === 'ArrowRight') this.lookYawGoal -= step;
      else if (e.key === 'ArrowUp') this.lookPitchGoal = clamp(this.lookPitchGoal + step, -1.0, 0.85);
      else if (e.key === 'ArrowDown') this.lookPitchGoal = clamp(this.lookPitchGoal - step, -1.0, 0.85);
      else if (e.key === 'Escape') this.setLook(false);
      else return;
      e.preventDefault();
    });
    // 다른 탭으로 가면 프레임이 멈춘다. 들고 있던 잔이 친구 화면에서 공중에 멈춰 있지 않게 내려놓고 알린다.
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden || !this.me) return;
      P.held = false;
      this.pointerId = null;
      P.pos.copy(P.home);
      P.vel.set(0, 0, 0);
      this.me.root.position.copy(P.home);
      this.net?.send({ t: 'pose', p: [P.home.x, P.home.y, P.home.z], r: [0, 0], f: Math.round(this.me.fill * 100) / 100, h: false });
    });
  }

  // ── 물리 ───────────────────────────────────────────────
  others() {
    const list = [];
    for (const r of this.remotes.values()) list.push(r.entity);
    if (this.mode === 'single' && this.bartender.glass) list.push(this.bartender.glass);
    return list;
  }

  stepMe(dt) {
    const P = this.phys, e = this.me;
    if (!e) return;
    const locked = e.waitingServe || e.seq.some((s) => s.blocking);
    const held = P.held && !locked;
    // 혼자 짠: 바텐더가 따르는 동안 잔은 바텐더 앞(serveSpot)에 가 있다가 다 따르면 내 앞으로 온다
    const rest = e.serveSpot ?? P.home;
    // 다 따른 잔은 먼저 앞으로 빼고 나서 옆으로 온다 (옆에 둔 병을 스치지 않게)
    if (e.serveSpot) this.serveFrom = e.serveSpot;
    let restX = rest.x;
    if (!e.serveSpot && this.serveFrom) {
      const f = this.serveFrom;
      const pz = Math.abs(P.home.z - f.z) > 1 ? clamp((P.pos.z - f.z) / (P.home.z - f.z), 0, 1) : 1;
      restX = f.x + (P.home.x - f.x) * smooth(clamp((pz - 0.3) / 0.6, 0, 1));
      if (pz > 0.97 || held) this.serveFrom = null;
    }
    // 취하면 잡은 잔이 손을 따라 비틀비틀
    const w = held ? Math.min(1.4, this.drunk) : 0;
    const tx = held ? P.target.x + Math.sin(this.time * 2.3) * 7 * w + Math.sin(this.time * 5.1) * 2 * w : restX;
    const tz = held ? P.target.z + Math.cos(this.time * 1.7) * 5 * w : rest.z;
    const ty = held ? TABLE_Y + LIFT - e.contactMid : TABLE_Y;
    const others = this.others();
    const n = 4, h = dt / n;
    for (let i = 0; i < n; i++) {
      const K = 240, D = 29, KY = 160, DY = 24;
      let ax = (tx - P.pos.x) * K - P.vel.x * D;
      let az = (tz - P.pos.z) * K - P.vel.z * D;
      const ay = (ty - P.pos.y) * KY - P.vel.y * DY;
      const am = Math.hypot(ax, az);
      if (am > 9000) { ax *= 9000 / am; az *= 9000 / am; }
      P.vel.x += ax * h; P.vel.z += az * h; P.vel.y += ay * h;
      const sp = Math.hypot(P.vel.x, P.vel.z);
      if (sp > 420) { P.vel.x *= 420 / sp; P.vel.z *= 420 / sp; }
      P.pos.addScaledVector(P.vel, h);
      if (P.pos.y < TABLE_Y) { P.pos.y = TABLE_Y; P.vel.y = Math.max(0, P.vel.y); }
      this.collide(e, others);
    }
    e.root.position.copy(P.pos);
    // 마시기 동작은 잔 기준 오프셋으로 얹는다
    if (e.offset.lengthSq() > 0) {
      const c = Math.cos(e.yaw), s = Math.sin(e.yaw);
      e.root.position.x += e.offset.x * c + e.offset.z * s;
      e.root.position.z += -e.offset.x * s + e.offset.z * c;
      e.root.position.y += e.offset.y;
    }
  }

  collide(e, others) {
    const P = this.phys;
    const r = e.radius;
    const now = performance.now();
    const box = (x0, x1, z0, z1) => {
      if (P.pos.x < x0) { P.pos.x = x0; P.vel.x = Math.max(0, P.vel.x); }
      if (P.pos.x > x1) { P.pos.x = x1; P.vel.x = Math.min(0, P.vel.x); }
      if (P.pos.z < z0) { P.pos.z = z0; P.vel.z = Math.max(0, P.vel.z); }
      if (P.pos.z > z1) { P.pos.z = z1; P.vel.z = Math.min(0, P.vel.z); }
    };
    if (this.mode === 'single' || this.table === 'bar') {
      const L = LAYOUT.single, B = this.mode === 'single' ? L.bounds : BAR_SEATS;
      // 바 상판 안쪽 끝을 넘어가지 않게
      box(B.x0, B.x1, L.backZ + r, B.z1);
    } else if (this.table === 'square') {
      box(-SQUARE.reach, SQUARE.reach, -SQUARE.reach, SQUARE.reach);
    } else if (this.table === 'long') {
      box(-LONG.reachX, LONG.reachX, -LONG.reachZ, LONG.reachZ);
    } else if (this.table === 'ushape') {
      // ㄷ자: 세 상판 중 하나 위에 있으면 그대로, 아니면 가장 가까운 상판 끝으로
      let best = null, bd = Infinity;
      for (const R of U_REGION) {
        const cx = clamp(P.pos.x, R.x0, R.x1), cz = clamp(P.pos.z, R.z0, R.z1);
        const dd = (cx - P.pos.x) ** 2 + (cz - P.pos.z) ** 2;
        if (dd < bd) { bd = dd; best = R; }
      }
      if (bd > 0) box(best.x0, best.x1, best.z0, best.z1);
    } else {
      const d = Math.hypot(P.pos.x, P.pos.z), max = this.table === 'biground' ? BIGROUND.reach : LAYOUT.multi.reach;
      if (d > max) {
        const nx = P.pos.x / d, nz = P.pos.z / d;
        P.pos.x = nx * max; P.pos.z = nz * max;
        const vn = P.vel.x * nx + P.vel.z * nz;
        if (vn > 0) { P.vel.x -= vn * nx; P.vel.z -= vn * nz; }
      }
    }

    // 탁자 가운데 놓인 것 (맥주 타워, 어묵탕 냄비 …): 들어서 그보다 높이 지나가지 않으면 비켜 간다
    if (this.mode === 'multi' && this.table !== 'bar') {
      for (const ob of this.room.obstacles ?? []) {
        if (P.pos.y + e.contactLow > TABLE_Y + ob.h) continue;
        const dx = P.pos.x - ob.x, dz = P.pos.z - ob.z, d = Math.hypot(dx, dz), min = ob.r + r * 0.8;
        if (d >= min) continue;
        const nx = d > 1e-4 ? dx / d : 1, nz = d > 1e-4 ? dz / d : 0;
        P.pos.x = ob.x + nx * min; P.pos.z = ob.z + nz * min;
        const vn = P.vel.x * nx + P.vel.z * nz;
        if (vn < 0) { P.vel.x -= vn * nx; P.vel.z -= vn * nz; }
      }
    }

    const myLow = P.pos.y + e.contactLow, myTop = P.pos.y + e.height;
    for (const o of others) {
      const op = o.root.position;
      const key = [this.myId, o.peerId].sort().join(':');
      const oLow = op.y + o.contactLow, oTop = op.y + o.height;
      let dx = P.pos.x - op.x, dz = P.pos.z - op.z;
      let dist = Math.hypot(dx, dz);
      const minD = r + o.radius;
      const apart = myTop < oLow || oTop < myLow;
      if (apart || dist > minD + COUNT_REARM) this.countSpent.delete(key);
      if (apart || dist >= minD) {
        if (apart || dist > minD + REARM) this.touching.delete(key);
        continue;
      }
      if (dist < 1e-3) { dx = P.home.x - op.x; dz = P.home.z - op.z; dist = Math.hypot(dx, dz) || 1; }
      const nx = dx / dist, nz = dz / dist;
      P.pos.x += nx * (minD - dist);
      P.pos.z += nz * (minD - dist);
      const rel = (P.vel.x - o.vel.x) * nx + (P.vel.z - o.vel.z) * nz;
      if (rel < 0) {
        P.vel.x -= 1.2 * rel * nx;
        P.vel.z -= 1.2 * rel * nz;
        if (-rel > CLINK_MIN_SPEED && !this.touching.has(key) && now - (this.clinkMemo.get(key) ?? -1e9) > 140) {
          this.touching.add(key);
          this.clinkMemo.set(key, now);
          this.onLocalClink(o, -rel, nx, nz, Math.max(myLow, oLow), Math.min(myTop, oTop));
        }
      }
    }
  }

  strengthOf(speed) {
    return clamp((speed - CLINK_MIN_SPEED * 0.6) / 170, 0.05, 1);
  }

  onLocalClink(o, speed, nx, nz, y0, y1) {
    const s = this.strengthOf(speed);
    const P = this.phys;
    const at = _v.set(P.pos.x - nx * this.me.radius, (y0 + y1) / 2, P.pos.z - nz * this.me.radius);
    this.audio.clink(this.soundInfo(this.me), this.soundInfo(o), s, this.panAt(at));
    this.fx.clinkSparks(at.x, at.y, at.z, s);
    this.me.hit(s, nx, nz);
    o.hit(s * 0.8, -nx, -nz);
    this.net?.send({ t: 'clink', with: o.peerId, s });
    const counted = this.countClink([this.myId, o.peerId].sort().join(':'), s);
    if (o === this.bartender.glass) {
      this.bartender.onClink(s);
      if (counted) this.room.addTally();
    }
  }

  // ── 남의 잔 위치 보간 ──────────────────────────────────
  interpolate(r) {
    const snaps = r.snaps;
    if (!snaps.length) return;
    const rt = performance.now() - 110;
    let a = snaps[0], b = snaps[snaps.length - 1];
    for (let i = snaps.length - 1; i > 0; i--) {
      if (snaps[i - 1].t <= rt) { a = snaps[i - 1]; b = snaps[i]; break; }
    }
    const u = b.t === a.t ? 1 : clamp((rt - a.t) / (b.t - a.t), 0, 1);
    const e = r.entity;
    e.root.position.set(
      a.p[0] + (b.p[0] - a.p[0]) * u,
      a.p[1] + (b.p[1] - a.p[1]) * u,
      a.p[2] + (b.p[2] - a.p[2]) * u,
    );
    e.netLean.x = a.r[0] + (b.r[0] - a.r[0]) * u;
    e.netLean.z = a.r[1] + (b.r[1] - a.r[1]) * u;
    while (snaps.length > 2 && snaps[1].t < rt) snaps.shift();
  }

  // ── 이름표와 말풍선 ────────────────────────────────────
  makeLabel(name, mine) {
    if (!this.labelLayer) return null;
    const el = document.createElement('div');
    el.className = 'tag' + (mine ? ' tag-me' : '');
    // 내 이름표는 색으로 구분한다 ("(나)"를 붙이면 길어진다)
    el.textContent = name;
    el.title = name;
    this.labelLayer.appendChild(el);
    return el;
  }

  // 혼자 짠: 바텐더가 말한다
  bartenderSay(text) {
    if (this.mode !== 'single') return;
    this.showBubble(this.bartender.anchor, text, false, this.bartenderHolder, 'barkeep');
  }

  showBubble(entity, text, mine, remote, cls = '') {
    if (!this.labelLayer || !entity) return;
    const holder = mine ? this : remote;
    holder.bubbleEl?.remove();
    const el = document.createElement('div');
    el.className = `bubble ${cls}`;
    el.textContent = text;
    this.labelLayer.appendChild(el);
    holder.bubbleEl = el;
    holder.bubbleEntity = entity;
    clearTimeout(holder.bubbleTimer);
    holder.bubbleTimer = setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 2200);
  }

  // 화면 위 자리 (아직 옮기지 않는다). 카메라 뒤면 null
  labelSpot(el, entity, lift) {
    if (!el || !entity) return null;
    entity.topLocal(_v);
    entity.body.localToWorld(_v);
    _v.y += lift;
    _v.project(this.camera);
    const hide = _v.z > 1;
    if (el._hide !== hide) { el._hide = hide; el.style.display = hide ? 'none' : ''; }
    if (hide) return null;
    // 글이 바뀔 때만 폭을 잰다
    if (el._txt !== el.textContent || !el._w) { el._txt = el.textContent; el._w = el.offsetWidth; el._h = el.offsetHeight; }
    return { el, x: (_v.x * 0.5 + 0.5) * this.width, y: (-_v.y * 0.5 + 0.5) * this.height, w: el._w, h: el._h, depth: _v.z };
  }

  placeAt(sp) {
    if (!sp) return;
    const { el } = sp;
    let { x, y } = sp;
    // 화면 밖으로 잘리지 않게: 좌우 끝 안쪽, 위쪽 버튼 줄 아래
    const half = sp.w / 2 + 6;
    if (half * 2 < this.width) x = Math.min(Math.max(x, half), this.width - half);
    y = Math.max(y, sp.h + (this.width < 640 ? 56 : 10));
    // 위치가 그대로면 스타일을 건드리지 않아 레이아웃 계산을 아낀다
    const tf = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px) translate(-50%, -100%)`;
    if (el._tf !== tf) { el._tf = tf; el.style.transform = tf; }
  }

  placeAbove(el, entity, lift) {
    this.placeAt(this.labelSpot(el, entity, lift));
  }

  updateLabels() {
    // 이름표끼리 겹치면 (사람이 많을 때 옆 사람 이름과 붙어서 한 줄처럼 보인다) 먼 쪽을 위로 비켜 놓는다
    const tags = [];
    const own = new Map();
    for (const r of this.remotes.values()) {
      const sp = this.labelSpot(r.label, r.entity, 4);
      if (sp) { tags.push(sp); own.set(r, sp); }
    }
    const mine = this.labelSpot(this.myLabel, this.me, 4);
    if (mine) tags.push(mine);
    tags.sort((a, b) => a.depth - b.depth);
    for (let i = 1; i < tags.length; i++) {
      const a = tags[i];
      for (let pass = 0; pass < 4; pass++) {
        let hit = false;
        for (let j = 0; j < i; j++) {
          const b = tags[j];
          if (Math.abs(a.x - b.x) < (a.w + b.w) / 2 + 3 && a.y > b.y - b.h - 1 && a.y - a.h < b.y + 1) {
            a.y = b.y - b.h - 2;
            hit = true;
          }
        }
        if (!hit) break;
      }
    }
    for (const sp of tags) this.placeAt(sp);
    // 말풍선은 제 이름표 위에
    const bubble = (el, entity, lift, tag) => {
      const sp = this.labelSpot(el, entity, lift);
      if (sp && tag) sp.y = Math.min(sp.y, tag.y - tag.h - 3);
      this.placeAt(sp);
    };
    for (const r of this.remotes.values()) bubble(r.bubbleEl, r.entity, 16, own.get(r));
    bubble(this.bubbleEl, this.me, this.mode === 'multi' ? 16 : 8, mine);
    this.placeAbove(this.bartenderHolder.bubbleEl, this.bartender.anchor, 2);
  }

  // ── 카메라 ─────────────────────────────────────────────
  resize() {
    const w = (this.width = window.innerWidth), h = (this.height = window.innerHeight);
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.fov = this.baseFov = w / h < 0.9 ? 58 : 45;
    this.camera.updateProjectionMatrix();
  }

  // 카메라가 벽 밖이나 바 뒤로 나가지 않게 (멀어진 만큼은 위로 올린다)
  keepInRoom(pos, look) {
    const x = clamp(pos.x, -226, 226), z = clamp(pos.z, -150, 206);
    const lost = Math.hypot(pos.x - x, pos.z - z);
    pos.set(x, Math.min(236, pos.y + lost * 0.5), z);
    // 천장(다락방은 기운 지붕) 밑으로: 보는 곳 쪽으로 다가가며 내려온다
    for (let i = 0; i < 24 && look && pos.y > this.room.ceilingAt(pos.x); i++) {
      pos.lerp(look, 0.08);
      pos.y = Math.min(pos.y, this.room.ceilingAt(pos.x) + (24 - i));
    }
    pos.y = Math.min(pos.y, this.room.ceilingAt(pos.x));
    return pos;
  }

  // 화면 폭에 맞춰 물러선다
  fitCamera(pos, look, halfWidth) {
    const dir = _v.subVectors(pos, look);
    const base = dir.length();
    dir.normalize();
    const hf = Math.atan(Math.tan(THREE.MathUtils.degToRad(this.baseFov / 2)) * this.camera.aspect);
    const dist = Math.max(base, halfWidth / Math.tan(hf));
    return pos.copy(look).addScaledVector(dir, dist);
  }

  cameraGoal(t, pos, look) {
    this.baseCameraGoal(t, pos, look);
    this.applyLook(pos, look);
  }

  // 내가 앉은 의자의 눈높이
  eyeAt(out) {
    if (this.mode === 'single') return out.set(0, 126, -96);
    const c = chairPose(this.table, this.mySeat ?? 0);
    if (this.table === 'bar') return out.set(c.x, 126, -96);
    const yaw = this.seatYaw(this.mySeat ?? 0);
    return out.set(c.x - Math.sin(yaw) * 6, 122, c.z - Math.cos(yaw) * 6);
  }

  // 둘러보기: 의자에 앉은 눈높이에서 보는 방향만 돌린다
  applyLook(pos, look) {
    if (this.mode === 'title' || this.musicView || this.picking) return;
    const w = smooth(this.lookBlend);
    if (!this.lookMode && w < 1e-3 && Math.abs(this.lookYaw) < 1e-3 && Math.abs(this.lookPitch) < 1e-3) return;
    const dx = look.x - pos.x, dy = look.y - pos.y, dz = look.z - pos.z;
    const flat = Math.hypot(dx, dz), dist = Math.hypot(flat, dy) || 1;
    const yaw = Math.atan2(dx, dz) + this.lookYaw;
    // 의자로 내려앉으면 탁자를 덜 내려다본다
    const pitch = clamp(Math.atan2(dy, flat) * (1 - w * 0.55) + this.lookPitch, -1.3, 1.0);
    if (w > 0) pos.lerp(this.eyeAt(_v), w);
    look.set(pos.x + Math.sin(yaw) * Math.cos(pitch) * dist, pos.y + Math.sin(pitch) * dist, pos.z + Math.cos(yaw) * Math.cos(pitch) * dist);
    this.looking = true;
  }

  baseCameraGoal(t, pos, look) {
    const narrow = this.camera.aspect < 0.9;
    // 신청곡 창을 열면 노래 기물을 비춘다
    if (this.musicView && this.mode !== 'title' && this.room.deviceView(pos, look, narrow, !narrow)) return;
    // 안주 고를 땐 내 접시를 들여다본다
    if (this.picking === 'anju' && this.myAnju && this.mode !== 'title') {
      const p = this.myAnju.root.position;
      const a = this.mode === 'multi' ? this.seatYaw(this.mySeat) : 0;
      look.set(p.x, TABLE_Y + 2, p.z);
      pos.set(p.x + Math.sin(a) * 48, TABLE_Y + 42, p.z + Math.cos(a) * 48);
      return;
    }
    if (this.mode === 'single') {
      // 바 의자에 앉은 눈높이: 내 잔, 바텐더 얼굴, 뒤 선반이 한 화면에
      look.set(0, 108, -178);
      pos.set(0, 136, -12);
      this.fitCamera(pos, look, narrow ? 44 : 78);
      if (this.picking) { look.set(0, 90, -146); pos.set(0, 116, -92); }
    } else if (this.mode === 'multi') {
      const sp = seatPose(this.table, this.mySeat), a = sp.yaw;
      if (this.table === 'bar') {
        // 바에 나란히: 혼자 짠과 같은 눈높이, 내 쪽으로 조금
        look.set(sp.x * 0.4, 108, -178);
        pos.set(sp.x * 0.5, 136, -12);
        this.fitCamera(pos, look, narrow ? 50 : 78);
        if (this.picking) { look.set(sp.x, 90, -146); pos.set(sp.x, 116, -92); }
        return;
      }
      if (this.table === 'long') {
        // 긴 탁자: 내 앞을 보되 가운데 쪽으로 당겨서 양옆이 다 들어오게
        const cx = sp.x * 0.55, side = Math.sign(sp.z);
        look.set(cx, 88, -side * 4);
        pos.set(cx, 146, side * 112);
        this.fitCamera(pos, look, narrow ? 52 : 84);
      } else if (this.table === 'ushape') {
        // ㄷ자: 내 뒤에서 ㄷ자 안쪽을 내려다본다 (맞은편 팔과 앞 탁자가 다 보이게)
        const bx = Math.sin(a), bz = Math.cos(a);
        look.set((sp.x - bx * 70) * 0.6, 84, (sp.z - bz * 70) * 0.6 + 8);
        pos.set(sp.x + bx * 86, 176, sp.z + bz * 86);
        this.fitCamera(pos, look, narrow ? 60 : 96);
        this.keepInRoom(pos, look);
      } else {
        const big = this.table === 'biground';
        const R = this.table === 'square' ? 98 : big ? 152 : 124;
        look.set(0, 88, 0);
        pos.set(Math.sin(a) * R, big ? 166 : 152, Math.cos(a) * R);
        this.fitCamera(pos, look, narrow ? (this.table === 'square' ? 42 : big ? 70 : 54) : big ? 92 : 72);
        this.keepInRoom(pos, look);
      }
      if (this.picking) {
        const h = this.seatHome(this.mySeat, _v2), bx = Math.sin(a), bz = Math.cos(a);
        look.set(h.x - bx * 6.8, 92, h.z - bz * 6.8);
        pos.set(h.x + bx * 50, 120, h.z + bz * 50);
      }
    } else {
      const a = t * 0.05 + 0.4;
      look.set(0, 84, -10);
      pos.set(Math.sin(a) * 150, 150, Math.cos(a) * 130 - 10);
    }
  }

  updateFlameLight(all, t) {
    const L = this.flameLight;
    const burning = this.me?.flameOn ? this.me : all.find((e) => e.flameOn);
    if (!burning) {
      L.intensity *= 0.85;
      return;
    }
    const p = burning.root.position;
    L.position.set(p.x, p.y + burning.height + 4, p.z);
    const flicker = 1 + Math.sin(t * 31) * 0.12 + Math.sin(t * 17.3) * 0.1;
    L.intensity += (1.6 * flicker - L.intensity) * 0.3;
  }

  // ── 매 프레임 ──────────────────────────────────────────
  frame() {
    this.clock.update();
    const dt = Math.min(0.05, this.clock.getDelta());
    const t = (this.time += dt);

    this.bartender.update(dt, t, { mode: this.mode, me: this.me, held: this.phys.held && !this.me?.busy, liftY: TABLE_Y + LIFT });
    this.stepMe(dt);
    for (const r of this.remotes.values()) this.interpolate(r);

    const all = [...this.decor, ...this.others()];
    if (this.me) all.push(this.me);
    for (const e of all) {
      e.panHint = this.panAt(e.root.position);
      e.update(dt, t);
    }

    // 먼 잔부터 그리기
    const cp = this.camera.position;
    all.sort((a, b) => b.root.position.distanceToSquared(cp) - a.root.position.distanceToSquared(cp));
    all.forEach((e, i) => { if (e.renderBase !== i * 10) e.setRenderBase(i * 10); });

    this.updateFlameLight(all, t);
    for (const a of [this.myAnju, this.barAnju, ...this.decorAnju, ...[...this.remotes.values()].map((r) => r.anju)]) {
      if (!a) continue;
      a.panHint = this.panAt(a.root.position);
      a.update(dt, t);
    }
    this.bartender.lateUpdate();
    this.updateDrunk(dt);
    for (let i = 0; i < this.remedies.length; i++) {
      if (this.remedies[i].update(dt)) { this.remedies.splice(i, 1); i--; }
    }
    this.room.update(dt, t, !!this.music?.playing);
    this.fx.update(dt, t);

    // 둘러보는 각도는 손을 바로 따라가되 살짝 부드럽게
    const kl = 1 - Math.exp(-dt * (this.lookMode ? 14 : 4));
    this.lookYaw += (this.lookYawGoal - this.lookYaw) * kl;
    this.lookPitch += (this.lookPitchGoal - this.lookPitch) * kl;
    this.lookBlend = clamp(this.lookBlend + (this.lookMode ? dt : -dt) * 1.6, 0, 1);
    const goalPos = _v2.set(0, 0, 0), goalLook = new THREE.Vector3();
    this.looking = false;
    this.cameraGoal(t, goalPos, goalLook);
    const k = 1 - Math.exp(-dt * (this.mode === 'title' ? 1.5 : 3));
    this.camPos.lerp(goalPos, k);
    // 고개를 돌리는 동안엔 보는 점을 바로 옮긴다 (점을 천천히 옮기면 크게 돌 때 코앞을 지나간다)
    if (this.looking) this.camLook.copy(goalLook);
    else this.camLook.lerp(goalLook, k);
    // 둘러보며 당겨 보기 (화각). 취했을 때 흔들림은 이 화각을 기준으로 얹는다
    const fovGoal = this.baseFov * (this.lookMode ? this.lookZoom : 1);
    this.fovBase = this.fovBase == null ? fovGoal : this.fovBase + (fovGoal - this.fovBase) * (1 - Math.exp(-dt * 8));
    if (Math.abs(this.fovBase - fovGoal) < 0.02) this.fovBase = fovGoal;
    this.camera.position.copy(this.camPos);
    this.camera.lookAt(this.camLook);
    this.drunkCamera(t);

    // 20Hz로 내 잔 위치 보내기
    if (this.net && this.me) {
      this.poseTimer -= dt;
      if (this.poseTimer <= 0) {
        this.poseTimer = 0.05;
        const p = this.me.root.position;
        this.net.send({ t: 'pose', p: [p.x, p.y, p.z], r: [this.me.pivot.rotation.x, this.me.pivot.rotation.z], f: Math.round(this.me.fill * 100) / 100, h: this.phys.held, d: Math.round(this.drunk * 100) / 100 });
      }
    }

    this.renderer.render(this.scene, this.camera);
    this.updateLabels();
    this.updateEmojis();
  }
}

// ── 고르기 화면용 미리보기 ─────────────────────────────────
export class ThumbRenderer {
  constructor(size = 112) {
    this.size = size;
    const r = (this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true }));
    r.setPixelRatio(1);
    r.setSize(size, size, false);
    r.toneMapping = THREE.ACESFilmicToneMapping;
    r.toneMappingExposure = 1.15;
    const pmrem = new THREE.PMREMGenerator(r);
    this.env = { envMap: pmrem.fromScene(new RoomEnvironment(), 0.04).texture, fx: null, audio: null };
    pmrem.dispose();
    this.scene = new THREE.Scene();
    this.scene.add(new THREE.HemisphereLight(0xfff0dc, 0x6b4a33, 1.6));
    const key = new THREE.DirectionalLight(0xffe2b8, 2.2);
    key.position.set(-30, 60, 50);
    this.scene.add(key);
    this.camera = new THREE.PerspectiveCamera(30, 1, 1, 500);
  }

  draw(canvas, drinkId, glassId) {
    const e = new GlassEntity(this.env, { drinkId, glassId, yaw: -0.35 });
    e.fill = 1;
    e.rebuildLiquid(0, true);
    e.updateIce(0, 0);
    e.updateFloaters(0, 0);
    this.scene.add(e.root);
    const h = e.height + 4 * SCALE, w = e.shape.R * 2 * SCALE;
    const span = Math.max(h, w * 1.1);
    const dist = span / 2 / Math.tan(THREE.MathUtils.degToRad(15)) * 1.08;
    // 불투명한 잔(머그, 사발)은 위에서 내려다봐야 안에 든 술이 보인다
    const up = e.shape.transparent ? 0.32 : 0.78;
    this.camera.position.set(0, h * 0.5 + dist * up, dist * (1 - (up - 0.32) * 0.35));
    this.camera.lookAt(0, h * 0.46, 0);
    this.renderer.render(this.scene, this.camera);
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(this.renderer.domElement, 0, 0, canvas.width, canvas.height);
    e.dispose();
  }

  drawAnju(canvas, id) {
    const a = new AnjuPlate(this.env, id);
    this.scene.add(a.root);
    this.camera.position.set(0, 30, 27);
    this.camera.lookAt(0, 1.5, 0);
    this.renderer.render(this.scene, this.camera);
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(this.renderer.domElement, 0, 0, canvas.width, canvas.height);
    a.dispose();
  }
}
