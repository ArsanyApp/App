// End-to-end tests A–F against a running Worker (scripts/local-server.sh start, or a deployed URL).
// Env: LICENSE_URL, ADMIN_TOKEN. Uses only HTTP, exactly like the Android app and the admin page.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";

const URL_ = process.env.LICENSE_URL;
const ADMIN = process.env.ADMIN_TOKEN;
if (!URL_ || !ADMIN) throw new Error("LICENSE_URL and ADMIN_TOKEN are required");

// Same derivation as the app: SHA-256("choice-auto-tap/installation/v1:" + random installation id).
const installation = () => createHash("sha256").update("choice-auto-tap/installation/v1:" + randomBytes(16).toString("hex")).digest("hex");
let ipCounter = 0;
const freshIp = () => `203.0.113.${++ipCounter}`;

async function call(method, path, body, headers = {}) {
  const res = await fetch(URL_ + path, {
    method,
    headers: { ...(body ? { "content-type": "application/json" } : {}), "cf-connecting-ip": freshIp(), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
}
const admin = (method, path, body) => call(method, path, body, { authorization: `Bearer ${ADMIN}` });
const activate = (activationCode, installationId) => call("POST", "/api/license/activate", { activationCode, installationId });
const heartbeat = (licenseToken, installationId) => call("POST", "/api/license/heartbeat", { licenseToken, installationId });

const INSTALL_A = installation();
const INSTALL_B = installation();
const state = {};

test("A: generate license A, activate installation A -> PASS", async () => {
  const gen = await admin("POST", "/api/admin/licenses", { count: 1, note: "e2e A" });
  assert.equal(gen.status, 200);
  state.a = gen.body.licenses[0];
  assert.match(state.a.activationCode, /^CAT-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/);
  const res = await activate(state.a.activationCode, INSTALL_A);
  assert.equal(res.status, 200);
  assert.equal(res.body.status, "ACTIVE");
  assert.ok(res.body.licenseToken);
  state.tokenA = res.body.licenseToken;
});

test("B: license A on installation B -> MUST FAIL (already used)", async () => {
  const res = await activate(state.a.activationCode, INSTALL_B);
  assert.equal(res.status, 409);
  assert.deepEqual(res.body, { error: "ALREADY_USED" });
});

test("C: revalidate installation A -> MUST PASS", async () => {
  const hb = await heartbeat(state.tokenA, INSTALL_A);
  assert.equal(hb.status, 200);
  assert.equal(hb.body.status, "VALID");
  const again = await activate(state.a.activationCode.toLowerCase(), INSTALL_A);
  assert.equal(again.body.status, "ACTIVE");
});

test("D: revoke license A -> heartbeat MUST become REVOKED", async () => {
  const rev = await admin("POST", "/api/license/revoke", { id: state.a.id });
  assert.equal(rev.status, 200);
  const hb = await heartbeat(state.tokenA, INSTALL_A);
  assert.deepEqual(hb.body, { status: "REVOKED" });
  const list = await admin("GET", "/api/admin/licenses?status=REVOKED");
  assert.ok(list.body.licenses.some((l) => l.id === state.a.id));
});

test("E: generate license B, activate installation B -> PASS", async () => {
  const gen = await admin("POST", "/api/admin/licenses", { count: 1, note: "e2e B" });
  const res = await activate(gen.body.licenses[0].activationCode, INSTALL_B);
  assert.equal(res.status, 200);
  assert.equal(res.body.status, "ACTIVE");
});

test("F: the signed token carries a 7-day offline validity window", async () => {
  const gen = await admin("POST", "/api/admin/licenses", { count: 1 });
  const res = await activate(gen.body.licenses[0].activationCode, installation());
  const claims = JSON.parse(Buffer.from(res.body.licenseToken.split(".")[0], "base64url").toString());
  assert.equal(claims.exp - claims.iat, 7 * 24 * 60 * 60 * 1000);
  assert.equal(res.body.offlineValidUntil, claims.exp);
});

test("security: bad codes, no admin token, brute force", async () => {
  assert.equal((await activate("CAT-0000-0000-0000", installation())).body.error, "INVALID_CODE");
  assert.equal((await activate("not a code", installation())).body.error, "INVALID_CODE");
  assert.equal((await call("GET", "/api/admin/licenses")).status, 401);
  const ip = "198.51.100.77";
  const inst = installation();
  let last;
  for (let i = 0; i < 11; i++) {
    last = await call("POST", "/api/license/activate", { activationCode: "CAT-0000-0000-0000", installationId: inst }, { "cf-connecting-ip": ip });
  }
  assert.equal(last.status, 429);
});
