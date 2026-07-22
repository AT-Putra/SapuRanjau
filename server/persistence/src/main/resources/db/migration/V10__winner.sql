-- winner — pemenang final; sumber cooldown (ADR-0021/0027). docs/08_DATA_SCHEMA.md §2.10.
CREATE TABLE winner (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  period_id         bigint NOT NULL REFERENCES period(id),
  user_id           bigint NOT NULL REFERENCES app_user(id),
  rank              int    NOT NULL,
  status            text   NOT NULL DEFAULT 'active' CHECK (status IN ('active','disqualified')),
  disqualify_reason text,                                 -- WAJIB saat disqualified (ADR-0021) — enforce app-level
  created_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (period_id, rank),
  UNIQUE (period_id, user_id)
);
CREATE INDEX winner_user ON winner (user_id, period_id);  -- hitung cooldown 3 periode (ADR-0027)
