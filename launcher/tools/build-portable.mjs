import { build, Platform, Arch } from 'electron-builder';
import path from 'node:path';
import fs from 'node:fs';

const version = JSON.parse(fs.readFileSync('app/package.json','utf8')).version;

const files = await build({
  projectDir: path.resolve('.'),
  prepackaged: path.resolve('build/2027-Launcher-win32-x64'),
  targets: Platform.WINDOWS.createTarget('portable', Arch.x64),
  config: {
    appId: 'dev.countdown.launcher2027',
    productName: '2027-Launcher',
    electronVersion: '44.5.1',
    directories: { app:'app', output:'portable-build', buildResources:'app/assets' },
    win: { target:['portable'], icon:'app/assets/launcher-icon.ico', signAndEditExecutable:false },
    portable: { artifactName:'2027-Launcher-Portable.exe', requestExecutionLevel:'user',
      unpackDirName:`countdown-2027-${version}` },
    compression:'maximum',
    npmRebuild:false
  }
});
console.log('Single-file portable:', files);
