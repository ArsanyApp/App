import { readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { getPlatformProxy } from "wrangler";
import { CodeKeys, TokenSigner, b64urlDecode, b64urlEncode } from "../src/crypto";
import { type Env, LicenseService, OFFLINE_GRACE_MS } from "../src/service";
import worker from "../src/index";

// Runs the real service logic against a real local D1 (SQLite in workerd via wrangler).
let proxy: Awaited<ReturnType<typeof getPlatformProxy<Env>>>;
let service: LicenseService;
let signer: TokenSigner;
let now = 1_800_000_000_000;
let env: Env;

const INSTALL_A = "a".repeat(64);
const INSTALL_B = "b".repeat(64);
const b64 = (buf: ArrayBuffer) => Buffer.from(buf).toString("base64");

async function expectError(p: Promise<unknown>, code: string) {
  await expect(p).rejects.toMatchObject({ code });
}

beforeAll(async () => {
  proxy = await getPlatformProxy<Env>({ persist: false });
  const sql = readFileSync(new URL("../migrations/0001_licenses.sql", import.meta.url), "utf8")
    .split(";").map((s) => s.replace(/--.*$/gm, "").trim()).filter(Boolean);
  for (const stmt of sql) await proxy.env.DB.prepare(stmt).run();

  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pkcs8 = b64((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer);
  const codeKey = b64(crypto.getRandomValues(new Uint8Array(32)).buffer);
  env = { DB: proxy.env.DB, LICENSE_SIGNING_KEY: pkcs8, LICENSE_CODE_KEY: codeKey, ADMIN_TOKEN: "test-admin-token-123", ALLOW_HTTP: "false" };
  signer = await TokenSigner.fromPkcs8(pkcs8);
  service = new LicenseService(proxy.env.DB, await CodeKeys.fromSecret(codeKey), signer, () => now);
});

afterAll(async () => {
  await proxy?.dispose();
});

beforeEach(async () => {
  await proxy.env.DB.prepare("DELETE FROM licenses").run();
  await proxy.env.DB.prepare("DELETE FROM rate_limits").run();
  now += 24 * 60 * 60 * 1000;
});

async function newCode(): Promise<{ id: string; code: string }> {
  const { licenses } = await service.createLicenses({ count: 1 });
  return { id: licenses[0].id, code: licenses[0].activationCode };
}

describe("license service", () => {
  it("1. UNUSED code activates and returns a signed token bound to the installation", async () => {
    const { id, code } = await newCode();
    const res = await service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1");
    expect(res.status).toBe("ACTIVE");
    const claims = await signer.verify(res.licenseToken);
    expect(claims).toMatchObject({ v: 1, lid: id, ih: INSTALL_A, iat: now, exp: now + OFFLINE_GRACE_MS });
    const { licenses } = await service.listLicenses("ACTIVE");
    expect(licenses).toHaveLength(1);
    expect(licenses[0]).toMatchObject({ id, status: "ACTIVE", deviceBound: true, activatedAt: now });
  });

  it("2. an activated code cannot activate another installation", async () => {
    const { code } = await newCode();
    await service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1");
    await expectError(service.activate({ activationCode: code, installationId: INSTALL_B }, "2.2.2.2"), "ALREADY_USED");
  });

  it("3. the same installation can activate again (reinstall-safe re-validation)", async () => {
    const { code } = await newCode();
    await service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1");
    const again = await service.activate({ activationCode: code.toLowerCase(), installationId: INSTALL_A }, "1.1.1.1");
    expect(again.status).toBe("ACTIVE");
  });

  it("4. a REVOKED code is rejected with the generic error", async () => {
    const { id, code } = await newCode();
    await service.revoke({ id });
    await expectError(service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1"), "INVALID_CODE");
  });

  it("5/6. unknown and malformed codes are rejected identically", async () => {
    await expectError(service.activate({ activationCode: "CAT-0000-0000-0000", installationId: INSTALL_A }, "1.1.1.1"), "INVALID_CODE");
    await expectError(service.activate({ activationCode: "hello", installationId: INSTALL_A }, "1.1.1.1"), "INVALID_CODE");
    await expectError(service.activate({ activationCode: "CAT-1234", installationId: "not-a-hash" }, "1.1.1.1"), "BAD_REQUEST");
  });

  it("7. activation attempts are rate-limited per IP and per installation", async () => {
    for (let i = 0; i < 10; i++) {
      await expectError(service.activate({ activationCode: "CAT-0000-0000-0000", installationId: INSTALL_A }, "9.9.9.9"), "INVALID_CODE");
    }
    await expectError(service.activate({ activationCode: "CAT-0000-0000-0000", installationId: INSTALL_A }, "9.9.9.9"), "RATE_LIMITED");
    // Same installation from another IP is also limited (per-installation bucket).
    await expectError(service.activate({ activationCode: "CAT-0000-0000-0000", installationId: INSTALL_A }, "8.8.8.8"), "RATE_LIMITED");
    // The window resets.
    now += 11 * 60 * 1000;
    const { code } = await newCode();
    expect((await service.activate({ activationCode: code, installationId: INSTALL_A }, "9.9.9.9")).status).toBe("ACTIVE");
  });

  it("8. forged, tampered, foreign-key and wrong-installation tokens are rejected", async () => {
    const { code } = await newCode();
    const { licenseToken } = await service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1");
    const [payload, sig] = licenseToken.split(".");
    const claims = JSON.parse(new TextDecoder().decode(b64urlDecode(payload)));
    const tampered = b64urlEncode(new TextEncoder().encode(JSON.stringify({ ...claims, exp: claims.exp + 1e12 }))) + "." + sig;
    expect((await service.heartbeat({ licenseToken: tampered, installationId: INSTALL_A })).status).toBe("INVALID");
    expect((await service.heartbeat({ licenseToken: "garbage", installationId: INSTALL_A })).status).toBe("INVALID");
    expect((await service.heartbeat({ licenseToken, installationId: INSTALL_B })).status).toBe("INVALID");
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
    const other = await TokenSigner.fromPkcs8(b64((await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer));
    const foreign = await other.sign({ ...claims });
    expect((await service.heartbeat({ licenseToken: foreign, installationId: INSTALL_A })).status).toBe("INVALID");
  });

  it("10. heartbeat returns VALID with a fresh token, then REVOKED after revocation", async () => {
    const { id, code } = await newCode();
    const { licenseToken } = await service.activate({ activationCode: code, installationId: INSTALL_A }, "1.1.1.1");
    now += 3 * 24 * 60 * 60 * 1000;
    const hb = await service.heartbeat({ licenseToken, installationId: INSTALL_A });
    expect(hb.status).toBe("VALID");
    expect((await signer.verify((hb as { licenseToken: string }).licenseToken))?.exp).toBe(now + OFFLINE_GRACE_MS);
    // A token past its offline exp can still renew online (server is the authority).
    now += 30 * 24 * 60 * 60 * 1000;
    expect((await service.heartbeat({ licenseToken, installationId: INSTALL_A })).status).toBe("VALID");
    await service.revoke({ id });
    expect(await service.heartbeat({ licenseToken, installationId: INSTALL_A })).toEqual({ status: "REVOKED" });
  });

  it("admin: generates many unique codes, lists and filters them, revokes by code", async () => {
    const { licenses } = await service.createLicenses({ count: 25, note: "batch" });
    expect(new Set(licenses.map((l) => l.activationCode)).size).toBe(25);
    await service.revoke({ activationCode: licenses[3].activationCode });
    const all = await service.listLicenses(null);
    expect(all.licenses).toHaveLength(25);
    expect(all.licenses.every((l) => l.activationCode && l.note === "batch")).toBe(true);
    expect((await service.listLicenses("REVOKED")).licenses).toHaveLength(1);
    await expectError(service.createLicenses({ count: 101 }), "BAD_REQUEST");
    // Raw codes are never stored: only an HMAC and an AES-GCM ciphertext.
    const row = await proxy.env.DB.prepare("SELECT * FROM licenses LIMIT 1").first<Record<string, string>>();
    expect(JSON.stringify(row)).not.toContain(licenses[0].activationCode.replace(/-/g, "").slice(3));
  });
});

describe("HTTP layer", () => {
  const call = (path: string, init: RequestInit = {}, e: Env = env) =>
    worker.fetch(new Request(`https://license.example${path}`, init), e);

  it("admin endpoints require the admin token", async () => {
    expect((await call("/api/admin/licenses")).status).toBe(401);
    expect((await call("/api/admin/licenses", { headers: { authorization: "Bearer wrong" } })).status).toBe(401);
    const ok = await call("/api/admin/licenses", { headers: { authorization: `Bearer ${env.ADMIN_TOKEN}` } });
    expect(ok.status).toBe(200);
    const revoke = await call("/api/license/revoke", {
      method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: "x" }),
    });
    expect(revoke.status).toBe(401);
  });

  it("brute-forcing the admin token is rate-limited", async () => {
    for (let i = 0; i < 10; i++) {
      await call("/api/admin/licenses", { headers: { authorization: "Bearer nope", "cf-connecting-ip": "7.7.7.7" } });
    }
    const res = await call("/api/admin/licenses", { headers: { authorization: "Bearer nope", "cf-connecting-ip": "7.7.7.7" } });
    expect(res.status).toBe(429);
  });

  it("plain HTTP is refused unless explicitly allowed for local development", async () => {
    const res = await worker.fetch(new Request("http://license.example/api/health"), env);
    expect(res.status).toBe(403);
  });

  it("returns JSON errors without internals, and serves the admin page with a strict CSP", async () => {
    const res = await call("/api/license/activate", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ activationCode: "CAT-0000-0000-0000", installationId: INSTALL_A }),
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: "INVALID_CODE" });
    const page = await call("/admin");
    expect(page.status).toBe(200);
    expect(page.headers.get("content-security-policy")).toContain("script-src 'self'");
    expect(await page.text()).not.toContain(env.ADMIN_TOKEN);
  });

  it("reports SERVER_NOT_CONFIGURED when secrets are missing", async () => {
    const res = await call("/api/license/activate", { method: "POST" }, { ...env, LICENSE_SIGNING_KEY: "" });
    expect(res.status).toBe(503);
  });
});
