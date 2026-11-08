// Telegram login on the phone itself, for one api key (the starter key during
// first-run setup, or the user's own key later). The phone can't scan a QR
// code on its own screen, so the user picks one of:
//   - link:  auth.exportLoginToken → "tg://login?token=…", opened in the
//            Telegram app on this phone, which asks to confirm the login.
//   - phone: auth.sendCode → Telegram sends a code to the Telegram app (or by
//            SMS) → the user types it here → auth.signIn.
// A two-step verification password is asked through askPassword(hint, wrong).
//
// createLogin(...).done resolves with the logged-in TelegramClient.

'use strict';

function errorText(err) {
  return String(err && (err.errorMessage || err.message) ? err.errorMessage || err.message : err);
}

// Telegram error codes → what the user can do about them.
function friendlyError(err) {
  const text = errorText(err);
  const seconds = Number(err && err.seconds) || Number((text.match(/FLOOD_WAIT_(\d+)/) || [])[1]) || 0;
  if (seconds || /^FLOOD/.test(text)) {
    const minutes = Math.max(1, Math.ceil(seconds / 60));
    return `Telegram says: too many tries. Wait ${seconds ? `${minutes} minute${minutes > 1 ? 's' : ''}` : 'a while'} and try again.`;
  }
  if (/PHONE_NUMBER_INVALID/.test(text)) return 'That number is not valid. Use the full number with the country code, e.g. +972 50 123 4567.';
  if (/PHONE_NUMBER_BANNED/.test(text)) return 'Telegram has banned this phone number.';
  if (/PHONE_CODE_INVALID|PHONE_CODE_EMPTY/.test(text)) return 'Wrong code. Check the code in the Telegram app and type it again.';
  if (/PHONE_CODE_EXPIRED/.test(text)) return 'The code expired. Tap "Send code" to get a new one.';
  if (/API_ID_INVALID/.test(text)) return 'Telegram rejected this app\'s API key.';
  return text;
}

const toLink = (token) => 'tg://login?token=' + Buffer.from(token).toString('base64url');

// onStatus(view): view = { method: null|'link'|'phone', step?: 'code', codeVia?, error? }
// askPassword(hint, wrong) → Promise<string>
function createLogin({ apiId, apiHash, onStatus, askPassword }) {
  const { TelegramClient, Api } = require('telegram');
  const { StringSession } = require('telegram/sessions');
  apiId = Number(apiId);
  const creds = { apiId, apiHash };

  let attempt = null; // { kind, client, ... } — at most one at a time
  let finished = false;
  let resolveDone;
  const done = new Promise((r) => { resolveDone = r; });
  let view = { method: null };
  const show = (patch) => {
    view = { ...view, ...patch };
    onStatus(view);
  };

  async function connect() {
    const client = new TelegramClient(new StringSession(''), apiId, apiHash, { connectionRetries: 5 });
    await client.connect();
    return client;
  }

  async function drop(a) {
    if (!a) return;
    a.cancelled = true;
    if (attempt === a) attempt = null;
    if (a.client) await a.client.destroy().catch(() => {});
  }

  async function begin(kind) {
    if (finished) throw new Error('Already logged in.');
    await drop(attempt);
    const a = { kind, cancelled: false };
    attempt = a;
    a.client = await connect();
    if (a.cancelled) {
      await a.client.destroy().catch(() => {});
      throw new Error('cancelled');
    }
    return a;
  }

  function succeed(a) {
    if (a.cancelled || finished) return;
    finished = true;
    attempt = null;
    resolveDone(a.client);
  }

  function fail(a, err) {
    if (a.cancelled || finished) return;
    console.log(`[login] ${a.kind} login failed: ${errorText(err)}`);
    show({ error: friendlyError(err) });
  }

  // Two-step verification. A wrong password asks again; anything else ends
  // this attempt (the user can start over).
  async function withPassword(a) {
    let wrong = false;
    await a.client.signInWithPassword(creds, {
      password: (hint) => askPassword(hint, wrong),
      onError: async (err) => {
        if (/PASSWORD_HASH_INVALID/.test(errorText(err))) {
          wrong = true;
          return a.cancelled;
        }
        return true;
      },
    });
  }

  // After Telegram confirms (updateLoginToken), exportLoginToken again
  // returns the authorization, possibly on another data center.
  async function finishLink(a) {
    try {
      let r = await a.client.invoke(new Api.auth.ExportLoginToken({ apiId, apiHash, exceptIds: [] }));
      if (r instanceof Api.auth.LoginTokenMigrateTo) {
        await a.client._switchDC(r.dcId);
        r = await a.client.invoke(new Api.auth.ImportLoginToken({ token: r.token }));
      }
      if (r instanceof Api.auth.LoginTokenSuccess) return succeed(a);
      // Still a plain token: not confirmed yet. Keep waiting.
    } catch (err) {
      if (errorText(err) === 'SESSION_PASSWORD_NEEDED') {
        try {
          await withPassword(a);
          return succeed(a);
        } catch (e) {
          return fail(a, e);
        }
      }
      fail(a, err);
    }
  }

  // A fresh tg://login link, valid ~30 s: the UI opens it right away.
  async function requestLink() {
    let a = attempt && attempt.kind === 'link' && !attempt.cancelled ? attempt : null;
    if (!a) {
      a = await begin('link');
      a.client.addEventHandler((update) => {
        if (update instanceof Api.UpdateLoginToken && !a.cancelled) finishLink(a);
      });
    }
    try {
      const r = await a.client.invoke(new Api.auth.ExportLoginToken({ apiId, apiHash, exceptIds: [] }));
      if (r instanceof Api.auth.LoginTokenSuccess) {
        succeed(a);
        return null;
      }
      if (!(r instanceof Api.auth.LoginToken)) throw new Error('Unexpected answer from Telegram: ' + r.className);
      show({ method: 'link', error: null });
      return toLink(r.token);
    } catch (err) {
      throw new Error(friendlyError(err));
    }
  }

  async function submitPhone(phone) {
    const a = await begin('phone');
    try {
      const sent = await a.client.sendCode(creds, phone);
      a.phone = phone;
      a.codeHash = sent.phoneCodeHash;
      show({ method: 'phone', step: 'code', codeVia: sent.isCodeViaApp ? 'app' : 'sms', error: null });
    } catch (err) {
      await drop(a);
      throw new Error(friendlyError(err));
    }
  }

  // Resolves once the code is accepted. If a two-step password is needed,
  // that continues in the background (askPassword → state need_password).
  async function submitCode(code) {
    const a = attempt;
    if (!a || a.kind !== 'phone' || !a.codeHash) throw new Error('Send the phone number first.');
    const phoneCode = String(code || '').replace(/\D/g, '');
    if (!phoneCode) throw new Error('Type the code from the Telegram app.');
    try {
      const r = await a.client.invoke(
        new Api.auth.SignIn({ phoneNumber: a.phone, phoneCodeHash: a.codeHash, phoneCode }),
      );
      if (r instanceof Api.auth.AuthorizationSignUpRequired) {
        throw new Error('No Telegram account uses this number. Sign up in the Telegram app first.');
      }
      succeed(a);
    } catch (err) {
      if (errorText(err) !== 'SESSION_PASSWORD_NEEDED') throw new Error(friendlyError(err));
      withPassword(a).then(() => succeed(a), (e) => fail(a, e));
    }
  }

  async function reset() {
    await drop(attempt);
    view = { method: null };
    onStatus(view);
  }

  onStatus(view);
  return { done, requestLink, submitPhone, submitCode, reset };
}

module.exports = { createLogin, friendlyError, toLink };
