import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';

const require = createRequire(path.resolve('app/package.json'));
const AdmZip = require('adm-zip');
const version = '5.0.5';
const source = `https://github.com/theorzr/portablemc/releases/download/v${version}/portablemc-${version}-windows-x86_64-msvc.zip`;
const archiveSha256 = 'c1ad577e9441040e65a55b521c6ab0fc4c32ec0c6f114d169ae6eee7d3d9fda6';
const binarySha256 = '1af06cedeaa17432047e76c04b73b9af4e3161c90a8b91324aeb3ad288604f41';
const runtime = {name: 'vcruntime140.dll', sha256: 'd1f4225df2cd877dbf130d5668a021dce3f94118455ff5ec952061c30afc9ce7',
  source: 'https://aka.ms/vc14/vc_redist.x64.exe', packageSha256: '843068991daaa1f73ad9f6239bce4d0f6a07a51f18c37ea2a867e9beca71295c', packageVersion: '14.51.36247.0'};
const destination = path.resolve('app/vendor/portablemc/windows-x64');
const binary = path.join(destination, 'portablemc.exe');
const sha = b => crypto.createHash('sha256').update(b).digest('hex');

if (!fs.existsSync(binary) || sha(fs.readFileSync(binary)) !== binarySha256) {
  const response = await fetch(source, {signal: AbortSignal.timeout(90000)});
  if (!response.ok) throw new Error(`PortableMC download failed: ${response.status}`);
  const archive = Buffer.from(await response.arrayBuffer());
  if (sha(archive) !== archiveSha256) throw new Error('PortableMC archive checksum mismatch');
  const entries = new AdmZip(archive).getEntries().filter(e => !e.isDirectory && path.posix.basename(e.entryName) === 'portablemc.exe');
  if (entries.length !== 1) throw new Error('Unexpected PortableMC archive contents');
  const contents = entries[0].getData();
  if (sha(contents) !== binarySha256) throw new Error('PortableMC executable checksum mismatch');
  fs.mkdirSync(destination, {recursive: true});
  fs.writeFileSync(binary, contents);
}
const runtimeFile = path.join(destination, runtime.name);
if (!fs.existsSync(runtimeFile) || sha(fs.readFileSync(runtimeFile)) !== runtime.sha256) throw new Error('The source package must contain the verified Microsoft runtime DLL');
fs.writeFileSync(path.join(destination, 'metadata.json'), JSON.stringify({version, source, archiveSha256, binarySha256, runtime}, null, 2) + '\n');
console.log(`PortableMC ${version}: official Windows executable verified`);
