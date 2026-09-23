import { generateCode, normalizeCode } from "./code";
import { CodeKeys, TokenSigner, randomHex } from "./crypto";

export interface Env {
  DB: D1Database;
  LICENSE_SIGNING_KEY: string;
  LICENSE_CODE_KEY: string;
  ADMIN_TOKEN: string;
  /** "true" only for local development (wrangler dev), never in production. */
  ALLOW_HTTP?: string;
}

/** How long the app may run without reaching the server (token exp = now + this). */
export const OFFLINE_GRACE_MS = 7 * 24 * 60 * 60 * 1000;

const LIMITS = {
  activateIp: { limit: 10, windowMs: 10 * 60 * 1000 },
  activateDevice: { limit: 10, windowMs: 10 * 60 * 1000 },
  heartbeat: { limit: 30, windowMs: 60 * 60 * 1000 },
  adminFail: { limit: 10, windowMs: 15 * 60 * 1000 },
};

const INSTALLATION_RE = /^[0-9a-f]{64}$/;

type Status = "UNUSED" | "ACTIVE" | "REVOKED";

interface LicenseRow {
  id: string;
  code_hash: string;
  code_enc: string;
  code_hint: string;
  status: Status;
  note: string | null;
  created_at: number;
  activated_at: number | null;
  device_id_hash: string | null;
  revoked_at: number | null;
  last_seen_at: number | null;
}

export class HttpError extends Error {
  constructor(readonly status: number, readonly code: string, readonly extra: Record<string, unknown> = {}) {
    super(code);
  }
}

export function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...extraHeaders,
    },
  });
}

export async function readJson(request: Request): Promise<Record<string, unknown>> {
  const type = request.headers.get("content-type") ?? "";
  if (!type.includes("application/json")) throw new HttpError(415, "BAD_REQUEST");
  const text = await request.text();
  if (text.length > 8192) throw new HttpError(413, "BAD_REQUEST");
  try {
    const v = JSON.parse(text);
    if (v === null || typeof v !== "object" || Array.isArray(v)) throw new Error();
    return v as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "BAD_REQUEST");
  }
}

export function clientIp(request: Request): string {
  // CF-Connecting-IP is set by Cloudflare's edge and cannot be spoofed in production.
  return request.headers.get("cf-connecting-ip") ?? "local";
}

export class LicenseService {
  constructor(
    private readonly db: D1Database,
    private readonly keys: CodeKeys,
    private readonly signer: TokenSigner,
    private readonly now: () => number = Date.now,
  ) {}

  static async create(env: Env): Promise<LicenseService> {
    if (!env.LICENSE_SIGNING_KEY || !env.LICENSE_CODE_KEY || !env.ADMIN_TOKEN) {
      throw new HttpError(503, "SERVER_NOT_CONFIGURED");
    }
    const [keys, signer] = await Promise.all([
      CodeKeys.fromSecret(env.LICENSE_CODE_KEY),
      TokenSigner.fromPkcs8(env.LICENSE_SIGNING_KEY),
    ]);
    return new LicenseService(env.DB, keys, signer);
  }

  /** Fixed-window counter. Throws 429 when the bucket is over its limit. */
  async rateLimit(kind: keyof typeof LIMITS, subject: string): Promise<void> {
    const { limit, windowMs } = LIMITS[kind];
    const now = this.now();
    const bucket = `${kind}:${(await this.keys.hmac(`rl:${subject}`)).slice(0, 32)}`;
    const row = await this.db
      .prepare(
        `INSERT INTO rate_limits (bucket, count, reset_at) VALUES (?1, 1, ?2)
         ON CONFLICT(bucket) DO UPDATE SET
           count = CASE WHEN reset_at <= ?3 THEN 1 ELSE count + 1 END,
           reset_at = CASE WHEN reset_at <= ?3 THEN ?2 ELSE reset_at END
         RETURNING count, reset_at`,
      )
      .bind(bucket, now + windowMs, now)
      .first<{ count: number; reset_at: number }>();
    if (Math.random() < 0.02) {
      await this.db.prepare("DELETE FROM rate_limits WHERE reset_at <= ?1").bind(now).run();
    }
    if (row && row.count > limit) {
      throw new HttpError(429, "RATE_LIMITED", { retryAfterSeconds: Math.ceil((row.reset_at - now) / 1000) });
    }
  }

  private deviceHash(installationId: string): Promise<string> {
    return this.keys.hmac(`dev:${installationId}`);
  }

  private async issueToken(lid: string, installationId: string) {
    const now = this.now();
    const exp = now + OFFLINE_GRACE_MS;
    const licenseToken = await this.signer.sign({ v: 1, lid, ih: installationId, iat: now, exp });
    return { status: "ACTIVE", licenseToken, serverTime: now, offlineValidUntil: exp };
  }

  async activate(body: Record<string, unknown>, ip: string) {
    const installationId = body.installationId;
    if (typeof installationId !== "string" || !INSTALLATION_RE.test(installationId)) {
      throw new HttpError(400, "BAD_REQUEST");
    }
    await this.rateLimit("activateIp", ip);
    await this.rateLimit("activateDevice", installationId);

    const code = normalizeCode(body.activationCode);
    // Malformed, unknown and revoked codes all get the same answer.
    if (!code) throw new HttpError(400, "INVALID_CODE");
    const codeHash = await this.keys.hmac(`code:${code}`);
    const license = await this.db.prepare("SELECT * FROM licenses WHERE code_hash = ?1").bind(codeHash).first<LicenseRow>();
    if (!license || license.status === "REVOKED") throw new HttpError(400, "INVALID_CODE");

    const device = await this.deviceHash(installationId);
    if (license.status === "ACTIVE") {
      if (license.device_id_hash !== device) throw new HttpError(409, "ALREADY_USED");
      await this.touch(license.id);
      console.log(JSON.stringify({ event: "reactivate", lid: license.id }));
      return this.issueToken(license.id, installationId);
    }

    // UNUSED: bind atomically so two simultaneous activations cannot both win.
    const now = this.now();
    const result = await this.db
      .prepare(
        `UPDATE licenses SET status = 'ACTIVE', device_id_hash = ?1, activated_at = ?2, last_seen_at = ?2
         WHERE id = ?3 AND status = 'UNUSED'`,
      )
      .bind(device, now, license.id)
      .run();
    if (result.meta.changes !== 1) {
      const current = await this.db.prepare("SELECT * FROM licenses WHERE id = ?1").bind(license.id).first<LicenseRow>();
      if (current?.status !== "ACTIVE" || current.device_id_hash !== device) throw new HttpError(409, "ALREADY_USED");
    }
    console.log(JSON.stringify({ event: "activate", lid: license.id }));
    return this.issueToken(license.id, installationId);
  }

  async heartbeat(body: Record<string, unknown>) {
    const installationId = body.installationId;
    if (typeof installationId !== "string" || !INSTALLATION_RE.test(installationId)) {
      throw new HttpError(400, "BAD_REQUEST");
    }
    await this.rateLimit("heartbeat", installationId);
    // Authentic tokens are accepted even after `exp`: the server's database is the authority,
    // so a phone that was offline longer than the grace period can recover by reconnecting.
    const claims = await this.signer.verify(body.licenseToken);
    if (!claims || claims.ih !== installationId) return { status: "INVALID" };
    const license = await this.db.prepare("SELECT * FROM licenses WHERE id = ?1").bind(claims.lid).first<LicenseRow>();
    if (!license) return { status: "INVALID" };
    if (license.status === "REVOKED") return { status: "REVOKED" };
    if (license.status !== "ACTIVE" || license.device_id_hash !== (await this.deviceHash(installationId))) {
      return { status: "INVALID" };
    }
    await this.touch(license.id);
    const fresh = await this.issueToken(license.id, installationId);
    return { ...fresh, status: "VALID" };
  }

  private async touch(id: string) {
    await this.db.prepare("UPDATE licenses SET last_seen_at = ?1 WHERE id = ?2").bind(this.now(), id).run();
  }

  // ---- Admin -----------------------------------------------------------------------------------

  async createLicenses(body: Record<string, unknown>) {
    const count = body.count === undefined ? 1 : Number(body.count);
    if (!Number.isInteger(count) || count < 1 || count > 100) throw new HttpError(400, "BAD_REQUEST");
    const note = typeof body.note === "string" ? body.note.slice(0, 200) : null;
    const created = [];
    for (let i = 0; i < count; i++) {
      const activationCode = generateCode();
      const body12 = normalizeCode(activationCode)!;
      const row = {
        id: randomHex(16),
        code_hash: await this.keys.hmac(`code:${body12}`),
        code_enc: await this.keys.encrypt(activationCode),
        code_hint: body12.slice(-4),
        created_at: this.now(),
      };
      await this.db
        .prepare(
          `INSERT INTO licenses (id, code_hash, code_enc, code_hint, status, note, created_at)
           VALUES (?1, ?2, ?3, ?4, 'UNUSED', ?5, ?6)`,
        )
        .bind(row.id, row.code_hash, row.code_enc, row.code_hint, note, row.created_at)
        .run();
      created.push({ id: row.id, activationCode, status: "UNUSED", note, createdAt: row.created_at });
    }
    console.log(JSON.stringify({ event: "create", count }));
    return { licenses: created };
  }

  async listLicenses(status: string | null) {
    const valid = status === "UNUSED" || status === "ACTIVE" || status === "REVOKED";
    const stmt = valid
      ? this.db.prepare("SELECT * FROM licenses WHERE status = ?1 ORDER BY created_at DESC LIMIT 1000").bind(status)
      : this.db.prepare("SELECT * FROM licenses ORDER BY created_at DESC LIMIT 1000");
    const { results } = await stmt.all<LicenseRow>();
    const licenses = [];
    for (const r of results) {
      let activationCode: string | null = null;
      try {
        activationCode = await this.keys.decrypt(r.code_enc);
      } catch {
        activationCode = null;
      }
      licenses.push({
        id: r.id,
        activationCode,
        codeHint: r.code_hint,
        status: r.status,
        note: r.note,
        createdAt: r.created_at,
        activatedAt: r.activated_at,
        revokedAt: r.revoked_at,
        lastSeenAt: r.last_seen_at,
        deviceBound: r.device_id_hash !== null,
      });
    }
    return { licenses };
  }

  async revoke(body: Record<string, unknown>) {
    let id: string | null = typeof body.id === "string" ? body.id : null;
    if (!id && body.activationCode !== undefined) {
      const code = normalizeCode(body.activationCode);
      if (!code) throw new HttpError(400, "INVALID_CODE");
      const row = await this.db
        .prepare("SELECT id FROM licenses WHERE code_hash = ?1")
        .bind(await this.keys.hmac(`code:${code}`))
        .first<{ id: string }>();
      id = row?.id ?? null;
    }
    if (!id) throw new HttpError(404, "NOT_FOUND");
    const result = await this.db
      .prepare("UPDATE licenses SET status = 'REVOKED', revoked_at = ?1 WHERE id = ?2 AND status != 'REVOKED'")
      .bind(this.now(), id)
      .run();
    if (result.meta.changes !== 1) {
      const exists = await this.db.prepare("SELECT status FROM licenses WHERE id = ?1").bind(id).first();
      if (!exists) throw new HttpError(404, "NOT_FOUND");
    }
    console.log(JSON.stringify({ event: "revoke", lid: id }));
    return { id, status: "REVOKED" };
  }
}

