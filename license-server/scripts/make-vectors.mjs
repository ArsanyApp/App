// Builds test/vectors.json from fixed byte sequences, so Kotlin and TypeScript can be checked
// against the exact same expected codes.
import { writeFileSync } from "node:fs";
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const check = (s) => { let sum = 0; for (let i = 0; i < s.length; i++) sum += (2 * i + 1) * ALPHABET.indexOf(s[i]); return ALPHABET[sum % 32]; };
const fmt = (b) => `CAT-${b.slice(0, 4)}-${b.slice(4, 8)}-${b.slice(8, 12)}`;
const generated = [];
for (const bytes of [[0,0,0,0,0,0,0,0,0,0,0],[255,254,253,252,251,250,249,248,247,246,245],[7,20,4,21,29,9,2,19,26,3,13],[31,63,95,127,159,191,223,255,1,33,65]]) {
  let body = ""; for (const b of bytes) body += ALPHABET[b & 31];
  generated.push({ bytes, code: fmt(body + check(body)) });
}
const v = generated[2].code; const body = v.replace(/^CAT-/, "").replace(/-/g, "");
const valid = [
  { input: v, body },
  { input: v.toLowerCase(), body },
  { input: body, body },
  { input: `  ${v.replace(/-/g, " ")}  `, body },
  { input: `cat${body}`, body },
];
// O -> 0 and I/L -> 1 normalization (built from a code containing 0 and 1 when possible)
const withZero = generated[0].code; const zb = withZero.replace(/^CAT-/, "").replace(/-/g, "");
valid.push({ input: withZero.replace(/0/g, "O"), body: zb });
const invalid = [
  "", "CAT", "CAT-1234-5678", `${v}X`, v.replace(/.$/, (c) => ALPHABET[(ALPHABET.indexOf(c) + 1) % 32]),
  v.slice(0, 5) + "U" + v.slice(6), "CAT-!!!!-????-****", "X".repeat(80), "DOG-" + v.slice(4),
];
writeFileSync(new URL("../test/vectors.json", import.meta.url), JSON.stringify({ alphabet: ALPHABET, generated, valid, invalid }, null, 2) + "\n");
console.log("wrote test/vectors.json");
