-- purchase — pembelian Play Billing (ADR-0011/0022/0025). docs/08_DATA_SCHEMA.md §2.7.
CREATE TABLE purchase (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         bigint NOT NULL REFERENCES app_user(id),
  purchase_token  text   NOT NULL UNIQUE,               -- idempotensi + lookup void
  product_id      text   NOT NULL CHECK (product_id IN ('life_s','life_m','life_l')),  -- ADR-0022
  lives_granted   int    NOT NULL,
  amount          numeric(19,2),                         -- nilai tercatat (laporan)
  status          text   NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','verified','granted','voided')),
  verified_at     timestamptz,
  voided_at       timestamptz,
  void_reason     text CHECK (void_reason IN ('refund','chargeback')),  -- ADR-0025
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX purchase_user ON purchase (user_id);
