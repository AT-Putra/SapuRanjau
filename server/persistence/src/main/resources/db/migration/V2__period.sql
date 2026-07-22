-- period — periode turnamen (ADR-0021). docs/08_DATA_SCHEMA.md §2.2.
CREATE TABLE period (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        text,
  starts_at   timestamptz NOT NULL,
  ends_at     timestamptz NOT NULL,
  status      text NOT NULL DEFAULT 'UPCOMING' CHECK (status IN ('UPCOMING','ACTIVE','ENDED')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at)
);
-- Paksa "satu periode ACTIVE" di level DB (ADR-0021), bukan cuma kode:
CREATE UNIQUE INDEX one_active_period ON period ((true)) WHERE status = 'ACTIVE';
