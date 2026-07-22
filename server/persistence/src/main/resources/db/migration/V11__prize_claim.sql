-- prize_claim — klaim hadiah, PII terenkripsi (ADR-0021/0030). docs/08_DATA_SCHEMA.md §2.11.
CREATE TABLE prize_claim (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  winner_id    bigint NOT NULL REFERENCES winner(id) UNIQUE,
  phone_enc    bytea NOT NULL,                            -- PII AES-GCM (verifikasi manual, ADR-0030)
  ewallet_enc  bytea,                                     -- PII AES-GCM
  address_enc  bytea,                                     -- PII AES-GCM
  prize_value  numeric(19,2),                             -- utk PPh
  status       text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','paid')),
  verified_by  bigint,                                    -- admin id (skema admin terpisah, ADR-0010)
  paid_by      bigint,
  paid_at      timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);
