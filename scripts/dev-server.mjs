import http from 'node:http';
import { createReadStream, statSync } from 'node:fs';
import { extname, resolve, sep } from 'node:path';

const root = resolve(import.meta.dirname, '..', 'frontend');
const port = Number(process.env.FRONTEND_PORT || 8090);
const backend = new URL(process.env.BACKEND_URL || 'http://localhost:8080');

const types = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json; charset=utf-8'
};

function proxy(request, response) {
  const target = new URL(request.url, backend);
  // Browser and API intentionally share this origin: forward cookies and CSRF
  // headers unchanged, replacing only Host for the upstream connection.
  const upstream = http.request(target, {
    method: request.method,
    headers: { ...request.headers, host: backend.host }
  }, upstreamResponse => {
    response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
    upstreamResponse.pipe(response);
  });
  upstream.on('error', error => {
    if (!response.headersSent) {
      response.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    }
    response.end(JSON.stringify({ message: `Backend недоступен: ${error.message}` }));
  });
  request.pipe(upstream);
}

function staticFile(request, response) {
  const pathname = decodeURIComponent(new URL(request.url, `http://${request.headers.host}`).pathname);
  const requested = resolve(root, `.${pathname}`);
  let file = requested.startsWith(`${root}${sep}`) || requested === root ? requested : resolve(root, 'index.html');

  try {
    if (statSync(file).isDirectory()) file = resolve(file, 'index.html');
    statSync(file);
  } catch {
    file = resolve(root, 'index.html');
  }

  response.writeHead(200, {
    'Content-Type': types[extname(file)] || 'application/octet-stream',
    'Cache-Control': 'no-store'
  });
  createReadStream(file).pipe(response);
}

const server = http.createServer((request, response) => {
  if (request.url === '/api' || request.url.startsWith('/api/')) {
    proxy(request, response);
  } else {
    staticFile(request, response);
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Frontend: http://localhost:${port}`);
  console.log(`API proxy: ${backend.href}`);
});
