'use strict';
// The caller (A): plain http.get. The http instrumentation creates the CLIENT span and injects
// the headers; the pp sampler adds the entry.
//   OTEL_SERVICE_NAME=order-api node -r ./otel-setup.js caller.js http://127.0.0.1:18099/order 10
const http = require('node:http');
const { sdk } = require('./otel-setup');

const [target, countArg] = process.argv.slice(2);
const count = Number(countArg);

function get(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve({ status: res.statusCode, body }));
    }).on('error', reject);
  });
}

(async () => {
  let ok = 0;
  for (let i = 0; i < count; i++) {
    const { status, body } = await get(target);
    if (i === 0) process.stdout.write('CALLER-GOT ' + body);
    if (status === 200) ok++;
    await new Promise((r) => setTimeout(r, 300));
  }
  console.log(`CALLER-DONE ok=${ok}/${count}`);
  await sdk.shutdown();
})();
