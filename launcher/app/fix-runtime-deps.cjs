'use strict';
// @xmcl/installer 6.3.4 imports a utils subpath omitted from @xmcl/core 2.16.2's npm archive.
// The only imported symbol is isNotNull. Keep this compatibility entry reproducible on npm ci.
const fs=require('node:fs'),path=require('node:path');
const dir=path.dirname(require.resolve('@xmcl/core/package.json'));
const version=JSON.parse(fs.readFileSync(path.join(dir,'package.json'))).version;
if(version!=='2.16.2')throw new Error('Recheck the XMCL utils compatibility entry after changing the core version.');
fs.writeFileSync(path.join(dir,'utils.js'),"'use strict';\nexports.isNotNull = value => value !== null && value !== undefined;\n");
