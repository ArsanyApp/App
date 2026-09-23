#!/usr/bin/env node
// Generates every secret the license server needs, plus the public key for the Android app.
// Run once:  node scripts/generate-keys.mjs  (Node 18+, no dependencies).
// Output goes to your terminal only. Do not commit it.
import { webcrypto as crypto } from "node:crypto";

const b64 = (buf) => Buffer.from(buf).toString("base64");
const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const privateKey = b64(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
const publicKey = b64(await crypto.subtle.exportKey("spki", pair.publicKey));
const codeKey = b64(crypto.getRandomValues(new Uint8Array(32)));
const adminToken = Buffer.from(crypto.getRandomValues(new Uint8Array(24))).toString("base64url");

if (process.argv.includes("--dev-vars")) {
  // Local development / CI end-to-end tests only.
  process.stdout.write(
    `LICENSE_SIGNING_KEY="${privateKey}"\nLICENSE_CODE_KEY="${codeKey}"\nADMIN_TOKEN="${adminToken}"\nALLOW_HTTP="true"\n# PUBLIC_KEY=${publicKey}\n`,
  );
} else {
  console.log(`
=== SERVER SECRETS (Cloudflare Worker secrets / GitHub Actions secrets) — keep private ===
LICENSE_SIGNING_KEY=${privateKey}
LICENSE_CODE_KEY=${codeKey}
ADMIN_TOKEN=${adminToken}

=== ANDROID APP (GitHub Actions variable LICENSE_PUBLIC_KEY) — public, safe to share ===
LICENSE_PUBLIC_KEY=${publicKey}
`);
}
