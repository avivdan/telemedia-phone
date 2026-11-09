# Telemedia for Android phones

Watch movies and series from your Telegram channels and groups in
[Stremio](https://www.stremio.com), on your Android phone. Everything runs on
the phone: no computer, no TV, no second device.

## What you need

- An Android phone (Android 7 or newer)
- [Telegram](https://play.google.com/store/apps/details?id=org.telegram.messenger) on the same phone, logged in
- [Stremio](https://play.google.com/store/apps/details?id=com.stremio.one) on the same phone, and a Stremio account

## Setup (about 2 minutes, once)

1. **Install the app.** The APK (`Telemedia-Phone-vX.Y.Z.apk`) is shared
   privately: ask whoever gave you Telemedia. Open it on the phone; Android
   asks once to allow installing apps from that app (Files, Chrome, …): allow,
   then **Install**.
2. **Open Telemedia** and allow notifications (it shows that it is running).
3. **Log in to Telegram:** tap **Use my phone number**, type your number, tap
   **Send code**. Telegram sends a code to your Telegram app (chat
   *Telegram*): type it in Telemedia and tap **Log in**. If your account has a
   two-step verification password, type it too.
   Telemedia then gets your own Telegram API key by itself (about a minute).
   You'll see a "login code" message in the *Telegram* chat: nothing to do,
   Telemedia reads it.
4. **Suggested groups:** tick the groups and channels you want to join, tap
   **Join selected** (or **Skip**). You can leave them anytime in Telegram.
5. **Add to Stremio:** tap **Add to Stremio**. A Stremio page opens in the
   browser: log in with your Stremio account and confirm, then come back to
   Telemedia.
6. **Allow running in the background** when Android asks, so videos don't stop
   while you watch.

Done. Open Stremio, pick any movie or episode, choose a **Telemedia** stream.

If Telemedia doesn't show up in Stremio yet, close Stremio and open it again.

## Watch on your TV or computer too

The phone can also serve Stremio on other devices on the same Wi-Fi (a TV, a
PC), like a small home server.

1. In Telemedia, under **Use from another device**, tap **Add to my Stremio
   account** and confirm on the Stremio page. Stremio on every device logged
   in to that account gets **Telemedia (phone)**.
   Or, in Stremio on the other device, paste the address shown under the QR
   code into the Add-ons search box and install it.
2. Keep the phone on the same Wi-Fi, ideally charging. It keeps serving with
   the screen off.

The address has a personal key in it (`http://<phone-ip>:7000/<key>/manifest.json`):
without the key, the phone answers nothing to the network. Don't share it.

**Give the phone a fixed address.** The address contains the phone's Wi-Fi IP.
If it changes, Telemedia updates your Stremio account by itself, but it's
simplest to set a *DHCP reservation* for the phone in your router (often
under LAN / DHCP settings: pick the phone, "reserve IP" or "static lease").
On Android also set *Wi-Fi → your network → Privacy → Use device MAC*, or the
phone may show up with a new MAC (and IP) after a while.

On a device that runs its own Telemedia (the TV app), you see both: its own
**Telemedia** and **Telemedia (phone)**. Remove one in Stremio's Add-ons if you
don't need it. On the PC, use the Stremio desktop app (the web version at
web.stremio.com can't reach `http://` addresses).

**Outside home?** Not built in. One option: install
[Tailscale](https://tailscale.com) on the phone and the other device, and use
the phone's Tailscale IP in the address instead.

## Everyday use

- Nothing to do: Telemedia runs in the background (see its notification) and
  starts by itself when the phone turns on.
- **Restart** in the app fixes most hiccups.
- **Log out of Telegram** signs the phone out. You can also sign it out from
  Telegram: *Settings → Devices*.
- Updates: install the new APK over the old one. It keeps your login.

## Good to know

- Your Telegram login stays on the phone. It's only sent to Telegram.
- Other devices on your Wi-Fi can only use Telemedia with the address that
  has your personal key in it. It travels unencrypted on your Wi-Fi, so use it
  at home, not on public Wi-Fi.
- If Telegram says "too many tries", wait a few hours before trying again.
- Telemedia and Telemedia TV can share one Stremio account: each device plays
  from its own Telemedia.

---

## For developers

The app embeds Node.js ([nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile),
Node 18) and runs the Telemedia addon on the phone, like the TV app does. The
addon is not copied here: the build compiles it from the TV project, so there
is one addon codebase.

```
proj/
  telemedia-stremio-claude/   addon/ (the Stremio addon) and tv-app/ (TV app)
  telemedia-phone/            this repo
```

```
MainActivity (UI process)                NodeService (":node" process, foreground service)
  one card per setup step       ──▶       node-bootstrap/main.js
  polls 127.0.0.1:7001                      ├─ control server 127.0.0.1:7001 (status + actions)
                                            ├─ login.js   Telegram login on the phone
                                            ├─ setup.js   own API key (starter key → own key)
                                            ├─ groups.js  head-start groups (join on request)
                                            ├─ lan.js     gateway 0.0.0.0:7000 → addon
                                            ├─ stremio-account.js  LAN entry in the Stremio account
                                            ├─ tv/stremio-link.js, tv/my-telegram.js (from the TV app)
                                            └─ require(addon dist) → addon on 127.0.0.1:7010
BootReceiver → starts NodeService on boot, once set up
```

**Serving other devices** (`lan.js`). The addon is unchanged and listens on
127.0.0.1:7010 only. A gateway on 0.0.0.0:7000 forwards to it:
- from the phone itself (127.0.0.1), no key: `http://127.0.0.1:7000/manifest.json`;
- from the network, only with the personal key (random, made once, in the
  app's `config.json`): `http://<phone-ip>:7000/<key>/manifest.json`. Anything
  else from the network gets 404. For keyed requests the gateway rewrites the
  stream URLs to the keyed address (from the request's `Host`) and serves the
  manifest as `community.telemedia.lan` / "Telemedia (phone)", so the account
  holds it next to the 127.0.0.1 entry instead of replacing it. When the
  phone's own Stremio uses the 127.0.0.1 entry, keyed catalog/stream requests
  from the phone itself get empty answers (no duplicates).
- "Add to my Stremio account" (`stremio-account.js`) is the link login again,
  but the Stremio session key is kept (app-private) so that when the Wi-Fi IP
  changes the entry is replaced in place. If that session stops working, the
  app asks to add it again.
- While a video streams, `NodeService` holds a partial wake lock and a Wi-Fi
  lock (it polls `activeStreams` in `/status`).

**Build and install** (Android SDK with NDK and CMake 3.22.1, JDK 17; both
found under `%LOCALAPPDATA%\Android` if `JAVA_HOME`/`ANDROID_HOME` aren't set):

```bash
npm run libnode                          # once: Node runtime → app/libnode/
npm test                                 # unit tests (no network)
npm run deploy -- 192.168.0.50:41234     # bundle, build, adb install, start
npm run release -- 0.1.0                 # signed APK in release/
```

Wireless debugging: on the phone, *Developer options → Wireless debugging →
Pair device with pairing code*, then `adb pair <ip>:<pair-port>` and
`adb connect <ip>:<port>` (the port shown on the Wireless debugging screen).
If the phone shows "offline": `adb kill-server`, then `adb connect` again.

**Telegram login on one phone.** The TV app shows a QR code that another
phone scans. A phone can't scan its own screen, and Telegram refuses
`tg://login?token=…` links opened on the phone itself (it answers "go to
Settings > Devices > Scan QR"; tested on a Galaxy S24 FE). So the phone app
logs in with the phone number: `auth.sendCode` sends a code to the Telegram
app, the user types it (`login.js`). The first-run setup is the TV app's automatic one
(`setup.js`): the bundled starter key logs in, the my.telegram.org login code
is read from the *Telegram* chat (777000), the user's own api_id/api_hash is
read or created (`my-telegram.js`), then the own key logs in by approving its
login token from the starter session (`auth.acceptLoginToken`) and the
starter session logs out. If that fails, the app shows the same
my.telegram.org steps by hand, or takes a pasted api_id/api_hash.

**Adding the addon to Stremio** (`stremio-link.js`, shared with the TV app):
"Add to Stremio" opens Stremio's link login (link.stremio.com) in the browser.
Once the user confirms, the app reads the account's addon collection, adds or
refreshes Telemedia, writes it back, verifies it and logs that session out.
The Stremio app syncs the account's addons.

**Build-time settings, outside the repo** (in `~/Documents/telemedia-signing`,
or `TELEMEDIA_SIGNING_DIR`), all optional except for releases:

| File | What |
|---|---|
| `signing.properties` + keystore | Release signing (same key as the TV app). `deploy` uses it too, so deploys and releases can replace each other. |
| `starter.properties` | `apiId=…`, `apiHash=…`: the starter key, stored scrambled in `defaults.json`. Share an APK with it privately. |
| `groups.txt` | Head-start groups: one `t.me/name`, `@name` or invite link (`t.me/+…`) per line, optional ` \| note`. Or `TELEMEDIA_GROUPS`. No groups are built in. |

Addon settings (`CATALOG_COUNTRY`, `TG_HEBREW_GROUP`, …) come from the
environment or the addon's user config file, as for the TV app.

Logs: `adb logcat -s TelemediaNode NodeService`

Notes:
- ABIs: `arm64-v8a`, `armeabi-v7a`.
- nodejs-mobile has no full ICU, so addon code must not use regex `\p{…}` or
  `String.prototype.normalize`. `lru-cache` is pinned to v10 (v11 needs Node 20+).
- The TV helpers (`my-telegram.js`, `stremio-link.js`, `starter-login.js`)
  are copied from `../telemedia-stremio-claude/tv-app/node-bootstrap` at
  build time; `bundle-node.mjs` checks they still export what the phone uses.
