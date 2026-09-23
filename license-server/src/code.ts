/**
 * Activation code format: CAT-XXXX-XXXX-XXXX
 *
 * - 12 characters from the Crockford base-32 alphabet (no I, L, O, U -> nothing ambiguous).
 * - 11 characters are cryptographically random (55 bits of entropy).
 * - The 12th is a check character: sum((2i+1) * value[i]) mod 32 over the first 11. Every odd
 *   weight is invertible mod 32, so any single mistyped character is detected before any network call.
 * - Input is normalized: case-insensitive, spaces/dashes ignored, O->0, I/L->1, optional "CAT" prefix.
 *
 * The Android app implements the exact same algorithm (license-core/ActivationCode.kt);
 * test/vectors.json is shared by both test suites.
 */
export const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
export const PREFIX = "CAT";
const RANDOM_LEN = 11;
const BODY_LEN = RANDOM_LEN + 1;

export function checkChar(randomPart: string): string {
  let sum = 0;
  for (let i = 0; i < randomPart.length; i++) {
    sum += (2 * i + 1) * ALPHABET.indexOf(randomPart[i]);
  }
  return ALPHABET[sum % 32];
}

/** Returns the canonical 12-character body, or null if the input is not a well-formed code. */
export function normalizeCode(input: unknown): string | null {
  if (typeof input !== "string" || input.length > 64) return null;
  let s = input.toUpperCase().replace(/[\s\-_]/g, "");
  if (s.length === PREFIX.length + BODY_LEN && s.startsWith(PREFIX)) s = s.slice(PREFIX.length);
  if (s.length !== BODY_LEN) return null;
  s = s.replace(/O/g, "0").replace(/[IL]/g, "1");
  for (const c of s) if (!ALPHABET.includes(c)) return null;
  if (checkChar(s.slice(0, RANDOM_LEN)) !== s[RANDOM_LEN]) return null;
  return s;
}

export function formatCode(body: string): string {
  return `${PREFIX}-${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}`;
}

/** New random code, e.g. "CAT-7K4P-X92M-Q3TD". 256 % 32 == 0, so `byte & 31` is unbiased. */
export function generateCode(random: (n: number) => Uint8Array = defaultRandom): string {
  const bytes = random(RANDOM_LEN);
  let body = "";
  for (let i = 0; i < RANDOM_LEN; i++) body += ALPHABET[bytes[i] & 31];
  return formatCode(body + checkChar(body));
}

function defaultRandom(n: number): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(n));
}
