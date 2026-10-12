'use strict';
// The callee (B): a plain http server. The http instrumentation creates the SERVER span; the
// application has nothing to do for Pinpoint and only echoes the tracestate it received.
//   OTEL_SERVICE_NAME=inventory-api node -r ./otel-setup.js callee.js 18099
const http = require('node:http');

const port = Number(process.argv[2]);
http.createServer((req, res) => {
  const ts = req.headers['tracestate'];
  console.log('CALLEE-SAW tracestate: ' + ts);
  res.writeHead(200, { 'content-type': 'text/plain' });
  res.end(`pong tracestate=${ts}\n`);
}).listen(port, '127.0.0.1', () => console.log('CALLEE-UP ' + port));
