# Choice Auto Tap license server

A Cloudflare Worker with a D1 database. It issues one-time activation codes, binds each code to one
app installation, and lets you revoke licenses. It runs on Cloudflare's free plan.

```
Android app ──HTTPS──▶ Cloudflare Worker (this folder) ──▶ D1 database
Owner browser ──HTTPS──▶ /admin page on the same Worker
```

The app only talks to the Worker; it never talks to D1 and contains no server credential.

## One-time setup (about 15 minutes)

You need a free Cloudflare account and this GitHub repository. You don't need to edit any code.

### 1. Generate your keys
Open `license-server/tools/keygen.html` in any browser (download the file and double-click it) and
press **Generate keys**. Everything is generated locally in the browser and nothing is sent anywhere.
(Alternative: `node license-server/scripts/generate-keys.mjs`.)

You get four values:

| Value | What it is | Where it goes |
|---|---|---|
| `LICENSE_SIGNING_KEY` | private signing key | GitHub **secret** only |
| `LICENSE_CODE_KEY` | key that hashes/encrypts codes | GitHub **secret** only |
| `ADMIN_TOKEN` | your admin page password | GitHub **secret** + your password manager |
| `LICENSE_PUBLIC_KEY` | public key built into the app | GitHub **variable** (public) |

Keep a private backup of the three secrets. If you lose `LICENSE_SIGNING_KEY` or `LICENSE_CODE_KEY`
and generate new ones, every existing license stops working.

### 2. Create a Cloudflare API token
Go to Cloudflare dashboard → My Profile → API Tokens → **Create Token** → *Create Custom Token*, with these permissions:
- Account → **Workers Scripts** → Edit
- Account → **D1** → Edit

Copy the token. Your **Account ID** is shown on the right side of the Workers & Pages overview page.

### 3. Add everything to GitHub
Go to GitHub → this repository → Settings → Secrets and variables → Actions.

**Secrets** tab → *New repository secret*, once for each:
`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `LICENSE_SIGNING_KEY`, `LICENSE_CODE_KEY`, `ADMIN_TOKEN`

**Variables** tab → *New repository variable*:
`LICENSE_PUBLIC_KEY` = the public key from step 1

### 4. Deploy the server
Go to Actions → **Deploy license server** → *Run workflow*. It will:
1. create the D1 database `choice-auto-tap-licenses` (or reuse it),
2. apply the migrations in `migrations/`,
3. deploy the Worker,
4. set the three Worker secrets,
5. check that `/api/health` works, and that the admin API accepts your token and rejects requests without it.

The run summary shows your Worker URL, e.g. `https://choice-auto-tap-license.<you>.workers.dev`.

### 5. Point the app at your server
Add one more repository **variable**: `LICENSE_API_URL` = the Worker URL from step 4 (no trailing slash).

Then re-run the **Android CI** workflow (Actions → Android CI → Run workflow, or push any commit). Once
every job passes, it publishes a GitHub Release whose APK is built for your server. **Only APKs
built after `LICENSE_API_URL` and `LICENSE_PUBLIC_KEY` are set can be activated.** APKs built
without them show "This copy of the app is not set up for activation".

## Daily use

### Generate licenses
Open `https://<your-worker>/admin`, sign in with `ADMIN_TOKEN`, choose how many codes, add an
optional note (e.g. the customer's name), and press **Generate**. Use **Copy** to copy a code. Codes look like
`CAT-7K4P-X92M-Q3TD`.

Send the customer the APK and **one** code.

### What the customer does
Install the APK, open it, enter the code, and tap **Activate** (internet required). The license becomes
**ACTIVE** and is bound to that installation. The app never asks for the code again.

If they pass the APK and the same code to someone else, that person sees **"Activation code already used."**

### Revoke a license
On the admin page, press **Revoke** next to the license. The app stops at its next successful
check: when it's opened or brought to the foreground, and hourly while the accessibility service is
running, with a real server call at most every 12 hours. A running macro is stopped and the app
shows **"License revoked — Please contact the software owner."**

To give that person access again, generate a new code. They can enter it from the revoked screen
via **Enter a new code**.

### Admin API (optional, for scripts)
All admin calls need `Authorization: Bearer <ADMIN_TOKEN>`.

```
POST /api/admin/licenses   {"count": 5, "note": "shop A"}  → new codes
GET  /api/admin/licenses[?status=UNUSED|ACTIVE|REVOKED]    → list
POST /api/license/revoke   {"id": "..."} or {"activationCode": "CAT-..."}
```

App endpoints: `POST /api/license/activate {activationCode, installationId}` and
`POST /api/license/heartbeat {licenseToken, installationId}`.

## How it works

- **Codes**: `CAT-XXXX-XXXX-XXXX`. They use 12 characters from an unambiguous 32-symbol alphabet
  (no I/L/O/U): 11 are cryptographically random (55 bits, from `crypto.getRandomValues`), and the
  last one is a check character, so the app rejects typos before going online. Input is
  case-insensitive, ignores spaces and dashes, and reads O as 0 and I/L as 1.
- **Storage**: raw codes are never stored in clear. D1 holds an HMAC-SHA256 of the code (used for
  lookup) and an AES-256-GCM ciphertext (so the admin page can show and copy it again). Both keys
  are derived from `LICENSE_CODE_KEY`. Installation hashes are stored as a further HMAC.
- **Device binding**: on first launch the app creates a random 128-bit installation id
  (`SecureRandom`). It is stored encrypted with an Android Keystore key and excluded from backups.
  The server only ever sees `SHA-256("choice-auto-tap/installation/v1:" + id)`. The app reads no
  IMEI, serial number, phone number, advertising ID, contacts, location or files.
- **Tokens**: after activation the Worker returns a token signed with ECDSA P-256 (claims: license
  id, installation hash, issued-at, offline limit). The app verifies it with the public key only;
  the private key exists only as a Cloudflare secret.
- **Offline grace**: every successful check (activation or heartbeat) allows up to **7 days**
  offline, counted on the phone's clock from that check.
  - Up to 24 h after a check the app is `ACTIVE`, and from 24 h to 7 days it is `OFFLINE_GRACE`.
    Both are fully usable.
  - After 7 days without a successful check it shows "Connect to the internet" until one succeeds.
  - Setting the phone clock back by more than 10 minutes also forces an online check.
  - Short outages never interrupt use.
- **Heartbeat**: sent at most every 12 h (on app open/resume and hourly checks from the service).
  - The Worker answers `VALID` with a fresh token, `REVOKED`, or `INVALID`.
  - The server is authoritative, so a phone that was offline longer than 7 days recovers as soon
    as it reconnects, unless the license was revoked.
- **Abuse protection**: all requests require HTTPS.
  - Activation is limited to 10 attempts per 10 minutes per IP and per installation.
  - Heartbeats are limited to 30 per hour per installation, and failed admin logins to 10 per 15
    minutes per IP.
  - Unknown, malformed and revoked codes all return the same `INVALID_CODE`.
  - Logs contain only event names and license ids, never codes, IPs or installation ids.

## Local development and tests

```
cd license-server
npm ci
npm test                       # unit + service tests against a real local D1
scripts/local-server.sh start  # real Worker on :8787 with throwaway keys (.dev.vars, git-ignored)
set -a; . .e2e/env; set +a
npm run test:e2e               # end-to-end A–F over HTTP
scripts/local-server.sh stop
```

## Manual deployment with wrangler (alternative to the workflow)

```
cd license-server && npm ci
npx wrangler login
npx wrangler d1 create choice-auto-tap-licenses       # copy the database_id into wrangler.toml
npx wrangler d1 migrations apply DB --remote
npx wrangler deploy
npx wrangler secret put LICENSE_SIGNING_KEY            # paste value
npx wrangler secret put LICENSE_CODE_KEY
npx wrangler secret put ADMIN_TOKEN
```
