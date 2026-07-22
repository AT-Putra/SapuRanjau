-- life_ledger — dompet nyawa, satu baris per token (ADR-0008). docs/08_DATA_SCHEMA.md §2.8.
CREATE TABLE life_ledger (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id        bigint NOT NULL REFERENCES app_user(id),
  type           text   NOT NULL CHECK (type IN ('free','paid')),
  source         text   NOT NULL,                        -- 'grant_period' | 'earn_casual' | 'purchase'
  purchase_id    bigint REFERENCES purchase(id),         -- utk paid; clawback saat void (ADR-0025)
  period_id      bigint REFERENCES period(id),           -- utk free (terikat periode)
  expiry         timestamptz,                            -- NULL utk paid (carry-over, ADR-0008)
  status         text   NOT NULL DEFAULT 'available' CHECK (status IN ('available','used','clawed_back','expired')),
  used_at        timestamptz,
  used_in_run_id bigint REFERENCES run(id),
  created_at     timestamptz NOT NULL DEFAULT now()
);
-- Wallet + konsumsi FIFO-expiry (ADR-0008): index HANYA nyawa aktif (partial)
CREATE INDEX life_available ON life_ledger (user_id, expiry NULLS LAST, id) WHERE status = 'available';
