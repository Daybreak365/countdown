'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const AdmZip = require('adm-zip');

function versionParts(v) { return String(v).replace(/^[v~^= ]+/, '').split(/[.+-]/).slice(0, 3).map(x => Number(x) || 0); }
function compareVersion(a, b) {
  const aa = versionParts(a), bb = versionParts(b);
  for (let i = 0; i < 3; i++) { const d = (aa[i] || 0) - (bb[i] || 0); if (d) return Math.sign(d); }
  return 0;
}
function satisfies(v, spec) {
  if (Array.isArray(spec)) return spec.some(s => satisfies(v, s));
  if (!spec || spec === '*') return true;
  const s = String(spec).trim();
  if (s.includes('||')) return s.split('||').some(x => satisfies(v, x));
  const range = s.match(/^([[(])\s*([^,]*)\s*,\s*([^\])]*)\s*([\])])$/);
  if (range) {
    const lo = range[2].trim(), hi = range[3].trim();
    return (!lo || compareVersion(v, lo) >= (range[1] === '[' ? 0 : 1)) &&
      (!hi || compareVersion(v, hi) <= (range[4] === ']' ? 0 : -1));
  }
  if (/^\[[\d.]+\]$/.test(s)) return compareVersion(v, s.slice(1, -1)) === 0;
  const tests = s.match(/(?:>=|<=|>|<|~|\^|=)?\s*\d+(?:\.\d+)*(?:\.[x*])?/g);
  if (!tests) return false;
  return tests.every(t => {
    const m = t.trim().match(/^(>=|<=|>|<|~|\^|=)?\s*(.+)$/), op = m[1] || '=', x = m[2];
    const cmp = compareVersion(v, x);
    if (/[x*]/.test(x)) return String(v).startsWith(x.replace(/\.?[x*].*$/, '') + '.');
    if (op === '>=') return cmp >= 0;
    if (op === '<=') return cmp <= 0;
    if (op === '>') return cmp > 0;
    if (op === '<') return cmp < 0;
    if (op === '~') { const a = versionParts(v), b = versionParts(x); return cmp >= 0 && a[0] === b[0] && a[1] === b[1]; }
    if (op === '^') return cmp >= 0 && versionParts(v)[0] === versionParts(x)[0];
    return cmp === 0;
  });
}
function inferMinecraft(spec) {
  if (Array.isArray(spec)) return inferMinecraft(spec[0]);
  if (!spec) return null;
  const s = String(spec).trim();
  // Select a declared lower bound, never the upper bound of a range.
  if (/^<|^\(\s*,|^\[\s*,/.test(s)) return null;
  const m = s.match(/\d+\.\d+(?:\.\d+)?/);
  return m ? m[0] : null;
}
function tomlValue(block, key) {
  const m = block.match(new RegExp('^\\s*' + key + '\\s*=\\s*["\x27]([^"\x27]*)["\x27]', 'm'));
  return m ? m[1] : null;
}
function inspectJar(file) {
  const size = fs.statSync(file).size;
  if (size > 160 * 1024 * 1024) throw new Error('모드 한 파일의 크기는 160MB 이하여야 해요.');
  if (!/\.jar$/i.test(file)) throw new Error('빌드된 .jar 모드 파일을 선택해 주세요.');
  let zip;
  try { zip = new AdmZip(file); } catch { throw new Error('JAR 압축 구조가 손상되어 있어요.'); }
  const entries = zip.getEntries();
  if (entries.length > 50000) throw new Error('모드 압축 항목이 너무 많아요.');
  const read = name => {
    const e = zip.getEntry(name);
    if (!e) return null;
    if (e.header.size > 1024 * 1024) throw new Error('모드 정보 파일이 비정상적으로 커요.');
    return zip.readAsText(e);
  };
  const fabric = read('fabric.mod.json');
  const neo = read('META-INF/neoforge.mods.toml');
  const forge = read('META-INF/mods.toml');
  if (!fabric && !neo && !forge) {
    const classes = entries.filter(e => e.entryName.endsWith('.class')).length;
    throw new Error(classes === 0 ? `이 JAR는 ${size.toLocaleString()}바이트이며 모드 코드와 정보가 없는 빈 파일이에요. build/libs 안의 정상 JAR를 선택해 주세요.` : '지원되는 Fabric · NeoForge · Forge 모드 정보가 없어요.');
  }
  let data;
  if (fabric) {
    let f;
    try { f = JSON.parse(fabric); } catch { throw new Error('fabric.mod.json을 읽을 수 없어요.'); }
    const deps = f.depends || {};
    data = { id: f.id, name: f.name || f.id, version: f.version || '', loader: 'fabric', mcRange: deps.minecraft || null,
      loaderRange: deps.fabricloader || '*', needsFabricApi: Boolean(deps['fabric-api'] || deps.fabric),
      dependencies: Object.keys(deps).filter(k => !['minecraft','java','fabricloader','fabric-api','fabric'].includes(k)),
      environment: f.environment || '*' };
  } else {
    const t = neo || forge;
    const loader = neo || /modId\s*=\s*["']neoforge["']/.test(t) ? 'neoforge' : 'forge';
    const mods = t.split(/\[\[mods\]\]/)[1]?.split(/\n\[/)[0] || t;
    const dependencies = [];
    let mcRange = null, loaderRange = '*';
    for (const b of t.split(/\[\[dependencies\.[^\]]+\]\]/).slice(1)) {
      const id = tomlValue(b, 'modId'), range = tomlValue(b, 'versionRange');
      if (id === 'minecraft') mcRange = range;
      else if (id === loader || id === 'forge') loaderRange = range || '*';
      else if (id && !/mandatory\s*=\s*false|type\s*=\s*["']optional["']/.test(b)) dependencies.push(id);
    }
    data = { id: tomlValue(mods, 'modId'), name: tomlValue(mods, 'displayName') || tomlValue(mods, 'modId'),
      version: tomlValue(mods, 'version') || '', loader, mcRange, loaderRange, dependencies, needsFabricApi: false, environment: '*' };
  }
  if (!/^[a-z0-9_.-]+$/i.test(data.id || '')) throw new Error('모드 식별자가 올바르지 않아요.');
  const manifest = read('META-INF/MANIFEST.MF') || '';
  if (data.version.includes('${')) data.version = manifest.match(/^Implementation-Version:\s*(.+)$/m)?.[1].trim() || '버전 미기재';
  return { ...data, minecraft: inferMinecraft(data.mcRange), fileName: path.basename(file), size,
    sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex') };
}
module.exports = { inspectJar, inferMinecraft, satisfies, compareVersion };
