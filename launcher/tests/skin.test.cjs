'use strict';
const test = require('node:test'), assert = require('node:assert/strict');
const {fetchFace, skinUrl} = require('../app/backend/skin.cjs');

const ID = '0123456789abcdef0123456789abcdef';
const profile = url => ({id: ID, name: 'Player', properties: [{name: 'textures', value: Buffer.from(JSON.stringify({textures: {SKIN: {url}}})).toString('base64')}]});
const response = (value, ok = true) => ({ok, status: ok ? 200 : 404, arrayBuffer: async () => Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value))});
const image = (width = 64, height = 64, hatAlpha = 0) => ({getSize: () => ({width, height}), crop: box => ({toBitmap: () => Buffer.from([0, 0, 0, 255, 0, 0, 0, hatAlpha]), toDataURL: () => `data:image/png;base64,${box.x}-${box.y}-${box.width}-${box.height}`})});

test('the face and hat are cut from the official skin texture', async () => {
  const requested = [];
  const fetch = async url => { requested.push(url); return response(requested.length === 1 ? profile('http://textures.minecraft.net/texture/abc123') : Buffer.from('png')); };
  const result = await fetchFace(ID, {fetch, decode: () => image()});
  assert.deepEqual(requested, [`https://sessionserver.mojang.com/session/minecraft/profile/${ID}`, 'https://textures.minecraft.net/texture/abc123']);
  assert.deepEqual(result, {face: 'data:image/png;base64,8-8-8-8', hat: 'data:image/png;base64,40-8-8-8'});
});

test('an old skin with a solid hat area shows the face alone', async () => {
  const fetch = async url => response(url.includes('sessionserver') ? profile('http://textures.minecraft.net/texture/abc123') : Buffer.from('png'));
  assert.deepEqual(await fetchFace(ID, {fetch, decode: () => image(64, 32, 255)}), {face: 'data:image/png;base64,8-8-8-8', hat: null});
});

test('unexpected skin hosts, sizes and lookups are rejected', async () => {
  assert.throws(() => skinUrl(profile('https://attacker.example/texture/abc123')));
  assert.throws(() => skinUrl(profile('https://textures.minecraft.net/other/abc123')));
  assert.throws(() => skinUrl({properties: []}));
  const fetch = async url => response(url.includes('sessionserver') ? profile('https://textures.minecraft.net/texture/abc123') : Buffer.from('png'));
  await assert.rejects(fetchFace('not-an-id', {fetch, decode: () => image()}));
  await assert.rejects(fetchFace(ID, {fetch, decode: () => image(128, 128)}));
  await assert.rejects(fetchFace(ID, {fetch: async () => response({}, false), decode: () => image()}));
});
