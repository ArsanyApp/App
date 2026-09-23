-- Licenses. Raw activation codes are never stored in clear:
--   code_hash = HMAC-SHA256(LICENSE_CODE_KEY, normalized code)   -> lookup
--   code_enc  = AES-256-GCM(key derived from LICENSE_CODE_KEY)    -> owner can copy it again later
--   device_id_hash = HMAC-SHA256(LICENSE_CODE_KEY, "dev:" + installation hash sent by the app)
CREATE TABLE licenses (
    id              TEXT    PRIMARY KEY,           -- 128-bit random hex, not sequential
    code_hash       TEXT    NOT NULL UNIQUE,
    code_enc        TEXT    NOT NULL,
    code_hint       TEXT    NOT NULL,              -- last 4 characters, for the admin list
    status          TEXT    NOT NULL CHECK (status IN ('UNUSED', 'ACTIVE', 'REVOKED')),
    note            TEXT,
    created_at      INTEGER NOT NULL,              -- epoch milliseconds
    activated_at    INTEGER,
    device_id_hash  TEXT,
    revoked_at      INTEGER,
    last_seen_at    INTEGER
);

CREATE INDEX idx_licenses_status_created ON licenses (status, created_at);
CREATE INDEX idx_licenses_created ON licenses (created_at);

-- Fixed-window rate limiting. bucket keys contain only HMACs, never raw IPs or codes.
CREATE TABLE rate_limits (
    bucket    TEXT    PRIMARY KEY,
    count     INTEGER NOT NULL,
    reset_at  INTEGER NOT NULL
);

CREATE INDEX idx_rate_limits_reset ON rate_limits (reset_at);
