-- prize_config — hadiah per periode (ADR-0021). docs/08_DATA_SCHEMA.md §2.9.
CREATE TABLE prize_config (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  period_id      bigint NOT NULL REFERENCES period(id) UNIQUE,
  winners_count  int    NOT NULL CHECK (winners_count BETWEEN 3 AND 10),  -- ADR-0021
  prizes         jsonb  NOT NULL,                         -- daftar hadiah per-peringkat (free-text)
  created_at     timestamptz NOT NULL DEFAULT now()
);
