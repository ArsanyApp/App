import { ADMIN_HTML, ADMIN_JS } from "./admin-page";
import { timingSafeEqual } from "./crypto";
import { type Env, HttpError, LicenseService, clientIp, json, readJson } from "./service";

async function requireAdmin(request: Request, env: Env, service: LicenseService): Promise<void> {
  const auth = request.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  const ok = token.length > 0 && (await timingSafeEqual(token, env.ADMIN_TOKEN));
  if (!ok) {
    await service.rateLimit("adminFail", clientIp(request));
    throw new HttpError(401, "UNAUTHORIZED");
  }
}

const SECURITY_HEADERS = {
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
  "referrer-policy": "no-referrer",
  "cache-control": "no-store",
};

export async function handle(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  if (url.protocol !== "https:" && env.ALLOW_HTTP !== "true") {
    return json({ error: "HTTPS_REQUIRED" }, 403);
  }
  const route = `${request.method} ${url.pathname}`;

  if (route === "GET /admin") {
    return new Response(ADMIN_HTML, {
      headers: {
        ...SECURITY_HEADERS,
        "content-type": "text/html; charset=utf-8",
        "content-security-policy":
          "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
      },
    });
  }
  if (route === "GET /admin.js") {
    return new Response(ADMIN_JS, { headers: { ...SECURITY_HEADERS, "content-type": "text/javascript; charset=utf-8" } });
  }
  if (route === "GET /api/health") return json({ ok: true });

  const service = await LicenseService.create(env);
  switch (route) {
    case "POST /api/license/activate":
      return json(await service.activate(await readJson(request), clientIp(request)));
    case "POST /api/license/heartbeat":
      return json(await service.heartbeat(await readJson(request)));
    case "POST /api/license/revoke":
      await requireAdmin(request, env, service);
      return json(await service.revoke(await readJson(request)));
    case "POST /api/admin/licenses":
      await requireAdmin(request, env, service);
      return json(await service.createLicenses(await readJson(request)));
    case "GET /api/admin/licenses":
      await requireAdmin(request, env, service);
      return json(await service.listLicenses(url.searchParams.get("status")));
    default:
      throw new HttpError(404, "NOT_FOUND");
  }
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      return await handle(request, env);
    } catch (e) {
      if (e instanceof HttpError) {
        const headers: Record<string, string> = {};
        if (e.status === 429) headers["retry-after"] = String(e.extra.retryAfterSeconds ?? 60);
        return json({ error: e.code, ...e.extra }, e.status, headers);
      }
      console.error(JSON.stringify({ event: "error", message: e instanceof Error ? e.message : "unknown" }));
      return json({ error: "SERVER_ERROR" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
