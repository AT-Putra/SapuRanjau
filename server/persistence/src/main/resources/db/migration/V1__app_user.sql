-- app_user — identitas pemain (ADR-0030). docs/08_DATA_SCHEMA.md §2.1.
CREATE TABLE app_user (
  id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  firebase_uid  text NOT NULL UNIQUE,                  -- key auth (ADR-0030)
  email         text,                                  -- Google Sign-In
  phone_enc     bytea,                                 -- PII AES-GCM (opsional di level akun)
  status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active','banned')),
  created_at    timestamptz NOT NULL DEFAULT now()
);
