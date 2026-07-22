-- board — papan aktif per level (ARCH §6, provably-fair). docs/08_DATA_SCHEMA.md §2.5.
CREATE TABLE board (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id          bigint NOT NULL REFERENCES run(id),
  level_config_id bigint NOT NULL REFERENCES level_config(id),
  seed            bigint NOT NULL,                      -- board = f(config, seed); PETA BOM TAK DISIMPAN (ADR-0003)
  commit_hash     text   NOT NULL,                      -- provably-fair (ARCH §6.1/§6.5)
  status          text   NOT NULL DEFAULT 'active' CHECK (status IN ('active','cleared','failed')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (run_id, level_config_id)
);
