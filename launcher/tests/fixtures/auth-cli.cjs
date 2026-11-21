'use strict';
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const at = flag => args[args.indexOf(flag) + 1];
const databaseFile = at('--msa-db-file');
const scenario = at('--scenario');
const uuid = '11111111-2222-3333-4444-555555555555';
const account = {uuid, username: 'TestPlayer', app_id: '00000000-1111-2222-3333-444444444444', refresh_token: 'TEST_REFRESH_SECRET', access_token: 'TEST_MINECRAFT_SECRET', xuid: '12345'};
fs.writeFileSync(path.join(path.dirname(databaseFile), 'observed.json'), JSON.stringify({args, injectedOverride: process.env.PMC_MSA_AZURE_APP_ID || process.env.pmc_output || null}));
if (scenario === 'refresh-expired' && args.includes('refresh')) {
  process.stdout.write('error_auth_unknown\tinvalid_grant TEST_SECRET should never reach the UI\n');
  process.exitCode = 1;
} else if (scenario === 'network' && args.includes('refresh')) {
  process.stdout.write('error_http_request\tTEST_SECRET user@example.invalid\n');
  process.exitCode = 1;
} else if (scenario === 'unowned') {
  process.stdout.write('error_auth_does_not_own_game\n');
  process.exitCode = 1;
} else if (scenario === 'bad-url') {
  process.stdout.write('auth_device_code\thttps://login.live.com.attacker.example\tABCD12345\n');
  setInterval(() => {}, 1000);
} else if (args.includes('refresh')) {
  fs.writeFileSync(databaseFile, JSON.stringify({accounts: [account]}));
  process.stdout.write('auth_account_refreshed\t' + uuid + '\tTestPlayer\n');
} else {
  // Deliberately split a protocol line across writes to exercise pipe buffering.
  process.stdout.write('auth_request_device_code\nauth_device_');
  setTimeout(() => {
    process.stdout.write('code\thttps://www.microsoft.com/link\tABCD12345\n');
    if (scenario === 'cancel') {
      fs.writeFileSync(databaseFile, JSON.stringify({accounts: [account]}));
      setInterval(() => {}, 1000);
    } else {
      setTimeout(() => {
        fs.writeFileSync(databaseFile, scenario === 'corrupt' ? 'TEST_SECRET invalid JSON' : JSON.stringify({accounts: [account]}));
        process.stdout.write('auth_account_authenticated\t' + uuid + '\tTestPlayer\n');
      }, 40);
    }
  }, 10);
}
