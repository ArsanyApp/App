/** WebCrypto helpers. Works in Workers and in Node >= 20 (globalThis.crypto). */

const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64urlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64urlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function b64Decode(s: string): Uint8Array {
  const bin = atob(s.replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomHex(nBytes: number): string {
  return hex(crypto.getRandomValues(new Uint8Array(nBytes)));
}

export async function sha256Hex(data: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(data)));
}

/** Constant-time comparison of two strings (compares SHA-256 digests). */
export async function timingSafeEqual(a: string, b: string): Promise<boolean> {
  const [da, db] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const x = new Uint8Array(da);
  const y = new Uint8Array(db);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0 && a.length === b.length;
}

/** Keys derived from LICENSE_CODE_KEY: an HMAC key for lookups and an AES-GCM key for code_enc. */
export class CodeKeys {
  private constructor(private readonly hmacKey: CryptoKey, private readonly aesKey: CryptoKey) {}

  static async fromSecret(secretB64: string): Promise<CodeKeys> {
    const raw = b64Decode(secretB64);
    if (raw.length < 32) throw new Error("LICENSE_CODE_KEY must be at least 32 bytes");
    const master = await crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const derive = async (label: string) => new Uint8Array(await crypto.subtle.sign("HMAC", master, enc.encode(label)));
    const hmacKey = await crypto.subtle.importKey(
      "raw", await derive("choice-auto-tap/hmac/v1"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
    );
    const aesKey = await crypto.subtle.importKey("raw", await derive("choice-auto-tap/aes/v1"), "AES-GCM", false, [
      "encrypt",
      "decrypt",
    ]);
    return new CodeKeys(hmacKey, aesKey);
  }

  async hmac(value: string): Promise<string> {
    return hex(await crypto.subtle.sign("HMAC", this.hmacKey, enc.encode(value)));
  }

  async encrypt(plain: string): Promise<string> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, this.aesKey, enc.encode(plain)));
    return `${b64urlEncode(iv)}.${b64urlEncode(ct)}`;
  }

  async decrypt(value: string): Promise<string> {
    const [iv, ct] = value.split(".");
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: b64urlDecode(iv) }, this.aesKey, b64urlDecode(ct));
    return dec.decode(pt);
  }
}

/** License token claims. Timestamps are epoch milliseconds. */
export interface TokenClaims {
  v: 1;
  /** License id. */
  lid: string;
  /** Installation hash (SHA-256 hex) this license is bound to. */
  ih: string;
  /** Issued at. */
  iat: number;
  /** Offline validity limit: the app refuses to run offline after this time. */
  exp: number;
}

/**
 * ECDSA P-256 / SHA-256 signer. Token format: base64url(JSON claims) "." base64url(64-byte r||s signature).
 * Only the public key ships in the app.
 */
export class TokenSigner {
  private constructor(private readonly privateKey: CryptoKey, readonly publicKey: CryptoKey) {}

  static async fromPkcs8(pkcs8B64: string): Promise<TokenSigner> {
    const alg = { name: "ECDSA", namedCurve: "P-256" };
    const privateKey = await crypto.subtle.importKey("pkcs8", b64Decode(pkcs8B64), alg, true, ["sign"]);
    const jwk = (await crypto.subtle.exportKey("jwk", privateKey)) as JsonWebKey;
    const publicKey = await crypto.subtle.importKey(
      "jwk", { kty: "EC", crv: "P-256", x: jwk.x, y: jwk.y, ext: true }, alg, true, ["verify"],
    );
    return new TokenSigner(privateKey, publicKey);
  }

  async publicKeySpkiB64(): Promise<string> {
    const spki = new Uint8Array((await crypto.subtle.exportKey("spki", this.publicKey)) as ArrayBuffer);
    return btoa(String.fromCharCode(...spki));
  }

  async sign(claims: TokenClaims): Promise<string> {
    const payload = b64urlEncode(enc.encode(JSON.stringify(claims)));
    const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, this.privateKey, enc.encode(payload));
    return `${payload}.${b64urlEncode(new Uint8Array(sig))}`;
  }

  /** Returns the claims if the signature is authentic and the structure valid, else null. Does not check exp. */
  async verify(token: unknown): Promise<TokenClaims | null> {
    if (typeof token !== "string" || token.length > 4096) return null;
    const parts = token.split(".");
    if (parts.length !== 2) return null;
    try {
      const ok = await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" }, this.publicKey, b64urlDecode(parts[1]), enc.encode(parts[0]),
      );
      if (!ok) return null;
      const c = JSON.parse(dec.decode(b64urlDecode(parts[0])));
      if (c?.v !== 1 || typeof c.lid !== "string" || typeof c.ih !== "string" ||
        typeof c.iat !== "number" || typeof c.exp !== "number") return null;
      return c as TokenClaims;
    } catch {
      return null;
    }
  }
}
