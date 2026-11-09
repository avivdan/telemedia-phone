// Telegram login on the phone itself, for one api key (the starter key during
// first-run setup, or the user's own key later), with the phone number:
// auth.sendCode → Telegram sends a code to the Telegram app (or by SMS) →
// the user types it here → auth.signIn. A two-step verification password is
// asked through askPassword(hint, wrong).
//
// Why not the TV's QR login: a phone can't scan its own screen, and Telegram
// refuses tg://login?token=… links opened on the phone ("To confirm Telegram
// login, please go to Settings > Devices > Scan QR"; tested on Telegram for
// Android, Nov 2026).
//
// createLogin(...).done resolves with the logged-in TelegramClient, or
// rejects with a KeyRejectedError when Telegram refuses the api key itself
// (nothing the user can type fixes that: the caller falls back to another key).

'use strict';

class KeyRejectedError extends Error {}

const KEY_REJECTED = /API_ID_INVALID|API_ID_PUBLISHED_FLOOD/;

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
  if (KEY_REJECTED.test(text)) return 'Telegram rejected this app\'s API key.';
  if (/NO_CONNECTION/.test(text)) return 'No connection to Telegram. Check the internet and try again.';
  return text;
}

// gramjs's connect() resolves false (it doesn't throw) when Telegram can't be
// reached; everything after it would then hang.
async function connectOrThrow(client) {
  if ((await client.connect()) === false) throw new Error('NO_CONNECTION');
}

// onStatus(view): view = { step: 'phone'|'code', codeVia?: 'app'|'sms', error? }
// askPassword(hint, wrong) → Promise<string>
function createLogin({ apiId, apiHash, onStatus, askPassword }) {
  const { TelegramClient, Api } = require('telegram');
  const { StringSession } = require('telegram/sessions');
  apiId = Number(apiId);
  const creds = { apiId, apiHash };

  let attempt = null; // { client, phone, codeHash, cancelled }: the latest working "Send code"
  let finished = false;
  let resolveDone;
  let rejectDone;
  const done = new Promise((resolve, reject) => {
    resolveDone = resolve;
    rejectDone = reject;
  });
  done.catch(() => {}); // handled by the caller; no unhandled-rejection noise
  let view = { step: 'phone' };
  const show = (patch) => {
    view = { ...view, ...patch };
    onStatus(view);
  };

  async function drop(a) {
    if (!a) return;
    a.cancelled = true;
    if (attempt === a) attempt = null;
    if (a.client) await a.client.destroy().catch(() => {});
  }

  function succeed(a) {
    if (a.cancelled || finished) return;
    finished = true;
    attempt = null;
    resolveDone(a.client);
  }

  // Telegram refuses the api key: this login can't work, whatever is typed.
  function keyRejected(err) {
    if (finished) return;
    finished = true;
    const a = attempt;
    attempt = null;
    if (a) drop(a);
    rejectDone(new KeyRejectedError(errorText(err)));
  }

  function fail(a, err) {
    if (a.cancelled || finished) return;
    console.log(`[login] login failed: ${errorText(err)}`);
    if (KEY_REJECTED.test(errorText(err))) return keyRejected(err);
    show({ error: friendlyError(err) });
  }

  // Two-step verification. A wrong password asks again; anything else ends
  // this attempt (the user can send a new code) with the real reason shown.
  async function withPassword(a) {
    let wrong = false;
    a.lastError = null;
    await a.client.signInWithPassword(creds, {
      password: (hint) => askPassword(hint, wrong),
      onError: async (err) => {
        if (/PASSWORD_HASH_INVALID/.test(errorText(err))) {
          wrong = true;
          return a.cancelled;
        }
        a.lastError = err;
        console.log('[login] password step: ' + errorText(err));
        return true;
      },
    });
  }

  // A new attempt each time: "Send code" again also fixes a mistyped number.
  // The previous attempt stays usable until the new code has really been sent.
  async function submitPhone(phone) {
    if (finished) throw new Error('Already logged in.');
    const prev = attempt;
    const a = { cancelled: false };
    attempt = a;
    try {
      a.client = new TelegramClient(new StringSession(''), apiId, apiHash, { connectionRetries: 5 });
      await connectOrThrow(a.client);
      const sent = await a.client.sendCode(creds, phone);
      if (a.cancelled) return;
      a.phone = phone;
      a.codeHash = sent.phoneCodeHash;
      if (prev) await drop(prev);
      show({ step: 'code', codeVia: sent.isCodeViaApp ? 'app' : 'sms', error: null });
    } catch (err) {
      await drop(a);
      if (prev && !prev.cancelled) attempt = prev;
      if (KEY_REJECTED.test(errorText(err))) {
        keyRejected(err);
        throw new Error(friendlyError(err));
      }
      throw new Error(friendlyError(err));
    }
  }

  // Resolves once the code is accepted. If a two-step password is needed,
  // that continues in the background (askPassword → state need_password).
  async function submitCode(code) {
    const a = attempt;
    if (!a || !a.codeHash) throw new Error('Send the phone number first.');
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
      withPassword(a).then(() => succeed(a), (e) => fail(a, a.lastError || e));
    }
  }

  async function reset() {
    await drop(attempt);
    view = { step: 'phone' };
    onStatus(view);
  }

  onStatus(view);
  return { done, submitPhone, submitCode, reset };
}

module.exports = { createLogin, friendlyError, connectOrThrow, KeyRejectedError, KEY_REJECTED };
