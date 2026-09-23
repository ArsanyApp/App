import { describe, expect, it } from "vitest";
import vectors from "./vectors.json";
import { ALPHABET, checkChar, formatCode, generateCode, normalizeCode } from "../src/code";

describe("activation code format", () => {
  it("matches the shared vectors (same algorithm as the Android app)", () => {
    for (const g of vectors.generated) {
      expect(generateCode(() => new Uint8Array(g.bytes))).toBe(g.code);
    }
    for (const v of vectors.valid) expect(normalizeCode(v.input)).toBe(v.body);
    for (const bad of vectors.invalid) expect(normalizeCode(bad)).toBeNull();
  });

  it("generates well-formed, unique, human-friendly codes", () => {
    const seen = new Set<string>();
    for (let i = 0; i < 5000; i++) {
      const code = generateCode();
      expect(code).toMatch(/^CAT-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
      expect(normalizeCode(code)).not.toBeNull();
      seen.add(code);
    }
    expect(seen.size).toBe(5000);
  });

  it("uses every alphabet symbol roughly uniformly (no bias)", () => {
    const counts = new Map<string, number>();
    for (let i = 0; i < 4000; i++) {
      for (const c of normalizeCode(generateCode())!.slice(0, 11)) counts.set(c, (counts.get(c) ?? 0) + 1);
    }
    expect(counts.size).toBe(32);
    const expected = (4000 * 11) / 32;
    for (const n of counts.values()) expect(Math.abs(n - expected) / expected).toBeLessThan(0.2);
  });

  it("detects every single-character typo", () => {
    const body = normalizeCode(generateCode())!;
    for (let pos = 0; pos < 12; pos++) {
      for (const c of ALPHABET) {
        if (c === body[pos]) continue;
        const typo = body.slice(0, pos) + c + body.slice(pos + 1);
        expect(normalizeCode(typo)).toBeNull();
      }
    }
  });

  it("formats and normalizes case / separators / look-alikes", () => {
    const body = "0123456789A";
    const code = formatCode(body + checkChar(body));
    expect(normalizeCode(code.toLowerCase())).toBe(body + checkChar(body));
    expect(normalizeCode(code.replace("0", "o").replace("1", "l"))).toBe(body + checkChar(body));
    expect(normalizeCode(123 as unknown)).toBeNull();
  });
});
