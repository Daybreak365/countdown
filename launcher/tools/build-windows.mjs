import { packager } from '@electron/packager';
import * as ResEdit from 'resedit';
import fs from 'node:fs';
import path from 'node:path';
import './fetch-portablemc.mjs';

// Reuse the release ZIP already in this workspace, including caches copied from
// another machine whose download-URL cache key differs from the current one.
const electronVersion='44.5.1';
const cacheRoot=path.resolve('tools/electron-cache');
const zipName=`electron-v${electronVersion}-win32-x64.zip`;
const cacheDirs=fs.existsSync(cacheRoot)
  ? [cacheRoot,...fs.readdirSync(cacheRoot,{withFileTypes:true}).filter(e=>e.isDirectory()).map(e=>path.join(cacheRoot,e.name))]
  : [];
const electronZipDir=cacheDirs.find(dir=>fs.existsSync(path.join(dir,zipName)));

const [output] = await packager({ dir:'app', out:'build', name:'2027-Launcher', platform:'win32', arch:'x64',
  electronVersion, electronZipDir, overwrite:true, asar:{unpackDir:'vendor/portablemc/windows-x64'}, prune:true,
  download:{ cacheRoot }
});
const file=path.join(output,'2027-Launcher.exe');
const exe=ResEdit.NtExecutable.from(fs.readFileSync(file),{ignoreCert:true});
const resources=ResEdit.NtExecutableResource.from(exe);
const icon=ResEdit.Data.IconFile.from(fs.readFileSync('app/assets/launcher-icon.ico'));
for (const lang of [1033,1042]) ResEdit.Resource.IconGroupEntry.replaceIconsForResource(resources.entries,1,lang,icon.icons.map(i=>i.data));
const infos=ResEdit.Resource.VersionInfo.fromEntries(resources.entries);
const version=JSON.parse(fs.readFileSync('app/package.json','utf8')).version+'.0';
for(const info of infos){
 info.setFileVersion(version);info.setProductVersion(version);
 for(const lang of info.getAvailableLanguages()) info.setStringValues(lang,{CompanyName:'Countdown Project',FileDescription:'2027 Countdown Launcher',ProductName:'2027 Countdown',OriginalFilename:'2027-Launcher.exe',InternalName:'2027-Launcher',LegalCopyright:'Custom launcher © Countdown Project 2026. Electron © OpenJS Foundation.'});
 info.outputToResourceEntries(resources.entries);
}
resources.outputResource(exe);fs.writeFileSync(file,Buffer.from(exe.generate()));
for(const name of ['사용방법.txt','README.md']) if(fs.existsSync(name))fs.copyFileSync(name,path.join(output,name));
if(fs.existsSync('LICENSE'))fs.copyFileSync('LICENSE',path.join(output,'LICENSE-launcher.txt'));
console.log('Windows package:',output);
