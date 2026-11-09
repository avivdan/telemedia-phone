// Adding the phone's LAN address to the user's Stremio account, so Stremio on
// the TV / PC (same account) gets it by itself. Same mechanism as the TV
// app's stremio-link.js (link.stremio.com login, then read / merge / write /
// verify the account's addon collection, mergeAddonCollection() reused), but
// the session key is kept on the phone: when the phone's Wi-Fi address
// changes, the entry is updated without asking the user again.
//
// The collection API replaces the whole list in one call, so we never write
// unless we read a non-empty list, and we verify the result.

'use strict';

const { mergeAddonCollection } = require('./tv/stremio-link');

const LINK_API = 'https://link.stremio.com/api';
const API = 'https://api.strem.io/api';
const CODE_TTL_MS = 10 * 60 * 1000;
const POLL_MS = 3000;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url, init) {
  const res = await fetch(url, init);
  return res.json();
}

class StremioApiError extends Error {}

async function api(method, body) {
  const json = await getJson(`${API}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (json.error) throw new StremioApiError(`${method}: ${json.error.message || JSON.stringify(json.error)}`);
  return json.result;
}

// A session that no longer works (logged out, expired): ask the user again.
const isAuthError = (err) => err instanceof StremioApiError && /session|auth/i.test(err.message);

// { code, link }: the user opens link and confirms there.
async function createLink() {
  const created = await getJson(`${LINK_API}/create?type=Create`);
  const code = created.result && created.result.code;
  const link = created.result && created.result.link;
  if (!code || !link) throw new Error('Stremio is unreachable right now.');
  return { code, link };
}

// Polls until the user confirms → authKey; null when the code expired or
// stop() says to give up.
async function waitForLink(code, stop = () => false) {
  const deadline = Date.now() + CODE_TTL_MS;
  while (Date.now() < deadline && !stop()) {
    await sleep(POLL_MS);
    try {
      // Until confirmed this answers error 101 "Invalid or expired token".
      const read = await getJson(`${LINK_API}/read?type=Read&code=${encodeURIComponent(code)}`);
      if (read.result && read.result.authKey) return read.result.authKey;
    } catch {
      // network hiccup: keep polling
    }
  }
  return null;
}

// Adds or refreshes addons, one write for all ([{ transportUrl, manifest }];
// same manifest id = replaced in place, e.g. after an IP change).
// → 'installed' | 'already-installed'
async function putAddons(authKey, entries) {
  const before = await api('addonCollectionGet', { type: 'AddonCollectionGet', authKey, update: true });
  const addons = before && before.addons;
  if (!Array.isArray(addons) || addons.length === 0) {
    throw new Error('could not read your Stremio addons, nothing was changed');
  }
  let next = addons;
  for (const { transportUrl, manifest } of entries) next = mergeAddonCollection(next, transportUrl, manifest) || next;
  if (next === addons) return 'already-installed';
  await api('addonCollectionSet', { type: 'AddonCollectionSet', authKey, addons: next });
  const after = await api('addonCollectionGet', { type: 'AddonCollectionGet', authKey, update: true });
  const saved = after && Array.isArray(after.addons) ? after.addons : [];
  for (const { transportUrl } of entries) {
    if (!saved.some((a) => a.transportUrl === transportUrl)) throw new Error('Stremio did not save the addon');
  }
  if (saved.length < addons.length) throw new Error('Stremio addon list shrank unexpectedly');
  return 'installed';
}

// The account's addons: name, id and transport host (no paths, so no keys).
async function listAddons(authKey) {
  const res = await api('addonCollectionGet', { type: 'AddonCollectionGet', authKey, update: true });
  return ((res && res.addons) || []).map((a) => {
    let host = '';
    try { host = new URL(a.transportUrl).host; } catch { /* not a URL */ }
    return { name: (a.manifest && a.manifest.name) || '', id: (a.manifest && a.manifest.id) || '', host };
  });
}

// Ends a session we no longer need.
const logout = (authKey) => api('logout', { type: 'Logout', authKey });

module.exports = { createLink, waitForLink, putAddons, listAddons, logout, isAuthError };
