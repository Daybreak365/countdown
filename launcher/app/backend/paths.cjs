'use strict';
const path = require('node:path');

// electron-builder's portable wrapper supplies the original EXE directory.
// process.execPath points at the unpacked Electron binary inside TEMP instead.
function resolveLocations({ executable, portableDir, packaged, appRoot, defaultDataRoot }) {
  const portable = packaged && typeof portableDir === 'string' && path.isAbsolute(portableDir);
  return {
    portable,
    dataRoot: portable ? path.join(portableDir, '2027-Launcher-data') : defaultDataRoot,
    distributionRoot: packaged ? path.dirname(executable) : path.resolve(appRoot, '..', 'distribution')
  };
}
module.exports = { resolveLocations };
