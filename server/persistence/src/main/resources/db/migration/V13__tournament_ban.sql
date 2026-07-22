-- tournament_ban — sanksi refund/chargeback (ADR-0025). docs/08_DATA_SCHEMA.md §2.13.
CREATE TABLE tournament_ban (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         bigint NOT NULL REFERENCES app_user(id),
  reason          text   NOT NULL CHECK (reason IN ('refund','chargeback')),
  purchase_id     bigint REFERENCES purchase(id),
  period_start_id bigint NOT NULL REFERENCES period(id),  -- P (ke-1)
  period_end_id   bigint NOT NULL REFERENCES period(id),  -- P+2 (eligible lagi P+3)
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ban_user ON tournament_ban (user_id);        -- cek eligibility tiap aksi turnamen
