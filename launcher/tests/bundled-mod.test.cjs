'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const AdmZip=require('../app/node_modules/adm-zip');
const {Engine}=require('../app/backend/engine.cjs');
const {inspectJar}=require('../app/backend/jar.cjs');
const appRoot=path.join(__dirname,'../app');
const pack=JSON.parse(fs.readFileSync(path.join(appRoot,'server-config.json')));
const submitted=JSON.parse(fs.readFileSync(path.join(appRoot,'assets/submitted-file.json')));
const bundled=fs.readdirSync(path.join(appRoot,'server-mods')).filter(n=>n.endsWith('.jar'))
  .map(n=>({file:path.join(appRoot,'server-mods',n),info:inspectJar(path.join(appRoot,'server-mods',n))}))
  .filter(m=>m.info.id==='newyearcountdown');
assert.equal(bundled.length,1,'bundle exactly one countdown mod');
const modPath=bundled[0].file;

function classStrings(b){
  assert.equal(b.readUInt32BE(0),0xcafebabe);
  assert.equal(b.readUInt16BE(6),69,'the actual mod requires Java 25');
  const strings=[],count=b.readUInt16BE(8);let pos=10;
  for(let i=1;i<count;i++){
    const tag=b[pos++];
    if(tag===1){const n=b.readUInt16BE(pos);pos+=2;strings.push(b.subarray(pos,pos+n).toString('utf8'));pos+=n;}
    else if([3,4,9,10,11,12,17,18].includes(tag))pos+=4;
    else if([5,6].includes(tag)){pos+=8;i++;}
    else if([7,8,16,19,20].includes(tag))pos+=2;
    else if(tag===15)pos+=3;
    else assert.fail('invalid class constant pool tag '+tag);
  }
  return strings;
}

test('the real bundled countdown and Fabric API match the fixed game configuration',()=>{
  const d=fs.mkdtempSync(path.join(os.tmpdir(),'countdown-pack-'));
  try{
    assert.equal(pack.minecraft,'26.3');assert.equal(pack.loader,'fabric');
    const e=new Engine({dataRoot:d,distributionRoot:d,appRoot,platform:'win32',
      config:{...pack,serverAddress:'pack.test.example',auth:{clientId:'00000000-1111-2222-3333-444444444444'}},
      auth:{account:()=>null}});
    const mods=e.checkPack();assert.deepEqual(new Set(mods.map(m=>m.id)),new Set(['newyearcountdown','fabric-api']));
    assert(e.configured());
    assert.equal(path.basename(modPath),submitted.mod.name);
    assert.equal(inspectJar(modPath).sha256,submitted.mod.sha256);
    assert.equal(fs.statSync(modPath).size,submitted.mod.size);
    assert.equal(inspectJar(modPath).version,submitted.mod.version);
    e.config.serverAddress='';assert.equal(e.checkPack().length,2);assert(e.configured());
  }finally{fs.rmSync(d,{recursive:true,force:true});}
});

// Minecraft 26.x ships unobfuscated, so the mod refers to real class names rather than intermediary ones.
test('the bundled mod is Java 25 bytecode with unobfuscated references, valid entrypoints and Mixin configs',()=>{
  const jar=new AdmZip(modPath),meta=JSON.parse(jar.readAsText('fabric.mod.json'));
  const entries=jar.getEntries().filter(e=>e.entryName.endsWith('.class'));
  assert(entries.length>0);
  assert.equal(entries.length,submitted.mod.classCount);
  for(const entry of entries)for(const s of classStrings(entry.getData()))assert(!/net\/minecraft\/class_\d+/.test(s),entry.entryName+': '+s);
  assert.equal(meta.depends.minecraft,'~26.3');assert.equal(meta.depends.java,'>=25');
  for(const values of Object.values(meta.entrypoints))for(const value of values)assert(jar.getEntry(value.replaceAll('.','/')+'.class'),value);
  for(const mixin of meta.mixins)assert(jar.getEntry(typeof mixin==='string'?mixin:mixin.config));
});
