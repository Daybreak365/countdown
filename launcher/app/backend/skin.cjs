'use strict';
// Looks up the signed-in player's skin on Mojang's official servers and cuts out the 8x8 face
// and its hat overlay. Any failure rejects, and the launcher keeps its default account icon.
const PROFILE = 'https://sessionserver.mojang.com/session/minecraft/profile/';
const SKIN_HOST = 'textures.minecraft.net';
const MAX_BYTES = 256 * 1024;

function skinUrl(profile) {
  const property = Array.isArray(profile?.properties) ? profile.properties.find(p => p?.name === 'textures') : null;
  if (typeof property?.value !== 'string' || property.value.length > 8192) throw new Error('스킨 정보가 없습니다.');
  const url = new URL(JSON.parse(Buffer.from(property.value, 'base64').toString('utf8'))?.textures?.SKIN?.url);
  if (url.hostname !== SKIN_HOST || !/^\/texture\/[a-f0-9]{1,128}$/i.test(url.pathname)) throw new Error('허용되지 않은 스킨 주소입니다.');
  return `https://${SKIN_HOST}${url.pathname}`;
}

async function body(response, limit) {
  if (!response.ok) throw new Error(`스킨 서버 응답 ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length === 0 || bytes.length > limit) throw new Error('스킨 응답 크기가 올바르지 않습니다.');
  return bytes;
}

// `decode` turns PNG bytes into an Electron NativeImage (injected so this stays testable).
async function fetchFace(id, {fetch = globalThis.fetch, decode, timeoutMs = 8000} = {}) {
  if (!/^[a-f0-9]{32}$/i.test(id || '')) throw new Error('계정 ID가 올바르지 않습니다.');
  const options = () => ({signal: AbortSignal.timeout(timeoutMs), redirect: 'error', credentials: 'omit'});
  const profile = JSON.parse((await body(await fetch(PROFILE + id, options()), 16384)).toString('utf8'));
  const image = decode(await body(await fetch(skinUrl(profile), options()), MAX_BYTES));
  const {width, height} = image.getSize();
  if (width !== 64 || (height !== 64 && height !== 32)) throw new Error('스킨 크기가 올바르지 않습니다.');
  const part = x => image.crop({x, y: 8, width: 8, height: 8});
  // Old skins fill the unused hat area with solid colour; like the game, ignore a hat with no transparency.
  const hat = part(40), pixels = hat.toBitmap();
  let opaque = true;
  for (let i = 3; i < pixels.length; i += 4) if (pixels[i] < 255) { opaque = false; break; }
  return {face: part(8).toDataURL(), hat: opaque ? null : hat.toDataURL()};
}

module.exports = {fetchFace, skinUrl};
