-- Local identity: the default the Authenticator port resolves to until an
-- external provider is wired in. Deliberately small, and deliberately real —
-- the sign-in screens the design specifies ask for a second factor, an attempt
-- count and a one-time invitation link, and drawing those over nothing would be
-- security theatre.

CREATE TABLE account (
    account_id    bigserial PRIMARY KEY,
    identifier    text        NOT NULL UNIQUE,
    -- Argon2id. The parameters live inside the encoded hash, so raising them
    -- later does not invalidate existing accounts.
    password_hash text,
    totp_secret   text,
    is_owner      boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    disabled_at   timestamptz
);

-- Single-use recovery codes, stored hashed. A code that can be read out of the
-- database is a second factor the database administrator also holds.
CREATE TABLE recovery_code (
    account_id bigint      NOT NULL REFERENCES account (account_id) ON DELETE CASCADE,
    code_hash  text        NOT NULL,
    used_at    timestamptz,
    PRIMARY KEY (account_id, code_hash)
);

-- Sessions are server side. The browser only ever holds an opaque identifier in
-- an httpOnly cookie, so no script on the page can read it and revoking a
-- session is a row, not a hope that a token expires.
CREATE TABLE session (
    session_id   text        PRIMARY KEY,
    account_id   bigint      NOT NULL REFERENCES account (account_id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    ip           inet,
    user_agent   text
);

CREATE INDEX session_account ON session (account_id, expires_at DESC);

-- Enrolment is by invitation, never open registration. A single-node panel has
-- an owner; anyone else is there because that owner said so.
CREATE TABLE invitation (
    token_hash  text        PRIMARY KEY,
    identifier  text        NOT NULL,
    issued_by   bigint      REFERENCES account (account_id),
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    opened_at   timestamptz,
    consumed_at timestamptz
);

-- Every attempt, successful or not, with the context the second-factor screen
-- shows: where it came from and whether that is usual for this account.
CREATE TABLE login_attempt (
    id          bigserial PRIMARY KEY,
    identifier  text        NOT NULL,
    at          timestamptz NOT NULL DEFAULT now(),
    ip          inet,
    user_agent  text,
    stage       text        NOT NULL,   -- password | totp | recovery
    outcome     text        NOT NULL    -- ok | bad_credential | locked | unknown_account
);

CREATE INDEX login_attempt_recent ON login_attempt (identifier, at DESC);
