// Serving Stremio on other devices (TV, PC on the same Wi-Fi) from this phone.
//
// The addon itself is unchanged and listens on 127.0.0.1 only (internal
// port). In front of it, a small gateway listens on 0.0.0.0:7000:
//   http://127.0.0.1:7000/manifest.json         this phone, no key needed
//   http://<phone-ip>:7000/<key>/manifest.json  other devices: the personal
//                                               key is required; any other
//                                               request from the network → 404
// For keyed requests the gateway rewrites the stream URLs in the addon's
// answers to the same keyed address, and serves the manifest under its own
// id ("…lan"), so the account can hold it next to the phone's own
// 127.0.0.1 entry without replacing it.

'use strict';

const crypto = require('crypto');
const dgram = require('dgram');
const http = require('http');
const os = require('os');

const LAN_SUFFIX = '.lan';
const STREMIO_RESOURCE = /^\/(catalog|meta|stream|subtitles)\/[^/]+\/[^/]+\.json/;
const DEDUPE_RESOURCE = /^\/(catalog|stream)\/[^/]+\/[^/]+\.json/;

const newKey = () => crypto.randomBytes(16).toString('base64url');

function sameKey(given, key) {
  const a = Buffer.from(String(given));
  const b = Buffer.from(String(key));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const plainAddress = (a) => String(a || '').replace(/^::ffff:/, '');
const isLoopback = (a) => /^127\./.test(plainAddress(a)) || a === '::1';

// RFC 1918 home/office networks (also a phone's own hotspot).
function isPrivate(ip) {
  return /^10\./.test(ip) || /^192\.168\./.test(ip) || /^172\.(1[6-9]|2\d|3[01])\./.test(ip);
}

// "/<key>/rest" with the right key → "/rest"; anything else → null.
function stripKey(url, key) {
  const m = /^\/([A-Za-z0-9_-]{16,64})(\/.*)?$/.exec(url);
  return m && sameKey(m[1], key) ? m[2] || '/' : null;
}

// The manifest other devices get: own id and name, so it sits next to the
// 127.0.0.1 entry in the Stremio account instead of replacing it.
function lanManifest(manifest) {
  return {
    ...manifest,
    id: String(manifest.id) + LAN_SUFFIX,
    name: `${manifest.name} (phone)`,
    description: `${manifest.description || ''} Served by your phone over Wi-Fi.`.trim(),
  };
}

// An addon answer for another device: stream URLs on the keyed LAN address.
function rewriteBody(path, body, { localBase, keyedBase }) {
  if (path === '/manifest.json') return JSON.stringify(lanManifest(JSON.parse(body)));
  return body.split(localBase + '/').join(keyedBase + '/');
}

// The phone's Wi-Fi (or hotspot) IPv4 address, or null when not on Wi-Fi.
// Interface names first (wlan0, …); some Android versions refuse to list
// interfaces, then use the address the system would send from.
function lanFromInterfaces() {
  let ifs;
  try {
    ifs = os.networkInterfaces();
  } catch {
    return undefined; // not allowed here: try the other way
  }
  const found = [];
  for (const [name, addrs] of Object.entries(ifs || {})) {
    for (const a of addrs || []) {
      if ((a.family === 'IPv4' || a.family === 4) && !a.internal && isPrivate(a.address)) {
        if (/^(rmnet|ccmni|tun|ppp|dummy|p2p|v4-rmnet)/.test(name)) continue; // mobile data, VPN
        found.push({ name, address: a.address, rank: /^(wlan|swlan|ap|eth|en)/.test(name) ? 0 : 1 });
      }
    }
  }
  found.sort((x, y) => x.rank - y.rank);
  return found.length ? found[0].address : null;
}

function lanFromRoute() {
  return new Promise((resolve) => {
    const sock = dgram.createSocket('udp4');
    const done = (ip) => {
      try { sock.close(); } catch { /* closed */ }
      resolve(ip && isPrivate(ip) ? ip : null);
    };
    sock.on('error', () => done(null));
    // UDP connect sends nothing; it only picks the route and local address.
    sock.connect(53, '1.1.1.1', () => {
      try { done(sock.address().address); } catch { done(null); }
    });
  });
}

async function lanAddress() {
  const ip = lanFromInterfaces();
  return ip === undefined ? lanFromRoute() : ip;
}

// The gateway. getKey() → the personal key; isSelf(address) → true for this
// phone's own LAN address; dedupeSelf() → true while this phone's Stremio
// uses the 127.0.0.1 entry (then the keyed entry answers the phone itself with
// nothing, so the phone doesn't list every stream twice).
// onLocalStremio(): Stremio on this phone asked for a resource.
// activeStreams() → video transfers in progress (for wake/Wi-Fi locks).
function createGateway({ port, addonPort, getKey, isSelf, dedupeSelf, onLocalStremio }) {
  const localBase = `http://127.0.0.1:${port}`;
  let streams = 0;

  function proxy(req, res, path, rewrite) {
    const headers = { ...req.headers, host: `127.0.0.1:${addonPort}` };
    if (rewrite) delete headers['accept-encoding']; // need a plain body to rewrite
    const isVideo = /^\/stream\/[^/]+\/\d+/.test(path) && !/\.json$/.test(path.split('?')[0]);
    if (isVideo) streams++;
    let counted = isVideo;
    const release = () => {
      if (counted) {
        counted = false;
        streams--;
      }
    };
    const up = http.request(
      { host: '127.0.0.1', port: addonPort, method: req.method, path, headers },
      (ur) => {
        const type = String(ur.headers['content-type'] || '');
        if (rewrite && ur.statusCode === 200 && /json/.test(type)) {
          const chunks = [];
          ur.on('data', (c) => chunks.push(c));
          ur.on('end', () => {
            let body = Buffer.concat(chunks).toString('utf8');
            try {
              body = rewrite(path.split('?')[0], body);
            } catch (err) {
              console.log('[lan] rewrite failed: ' + err.message);
            }
            const out = { ...ur.headers, 'content-length': Buffer.byteLength(body) };
            delete out['content-encoding'];
            delete out['transfer-encoding'];
            res.writeHead(ur.statusCode, out);
            res.end(body);
          });
          return;
        }
        res.writeHead(ur.statusCode, ur.headers);
        ur.pipe(res);
      },
    );
    up.on('error', () => {
      release();
      if (!res.headersSent) res.writeHead(502);
      res.end();
    });
    res.on('close', () => {
      release();
      up.destroy();
    });
    req.pipe(up);
  }

  const server = http.createServer((req, res) => {
    const url = req.url || '/';
    const from = plainAddress(req.socket.remoteAddress);
    const notFound = () => {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not found');
    };

    const keyed = stripKey(url, getKey());
    if (keyed === null) {
      // No (or a wrong) key: only this phone itself.
      if (!isLoopback(from)) return notFound();
      if (STREMIO_RESOURCE.test(url)) onLocalStremio(url);
      return proxy(req, res, url, null);
    }

    if (DEDUPE_RESOURCE.test(keyed) && isSelf(from) && dedupeSelf()) {
      res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
      return res.end(JSON.stringify(keyed.startsWith('/stream/') ? { streams: [] } : { metas: [] }));
    }
    // The address the other device used to reach us (it changes with the IP).
    const host = /^[A-Za-z0-9.\-:[\]]{1,80}$/.test(req.headers.host || '') ? req.headers.host : null;
    if (!host) return notFound();
    const keyedBase = `http://${host}/${getKey()}`;
    proxy(req, res, keyed, (path, body) => rewriteBody(path, body, { localBase, keyedBase }));
  });
  server.on('error', (err) => console.error('[lan] gateway error: ' + err.message));
  server.listen(port, '0.0.0.0');
  return { server, activeStreams: () => streams };
}

module.exports = {
  newKey,
  stripKey,
  isLoopback,
  isPrivate,
  lanManifest,
  rewriteBody,
  lanAddress,
  createGateway,
  LAN_SUFFIX,
};
