-- tournament_consent — gate S&K per periode (ADR-0026). docs/08_DATA_SCHEMA.md §2.14.
CREATE TABLE tournament_consent (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id      bigint NOT NULL REFERENCES app_user(id),
  period_id    bigint NOT NULL REFERENCES period(id),
  tnc_version  text   NOT NULL,
  agreed_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, period_id)                              -- gate (ADR-0026)
);
