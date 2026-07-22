-- level_config — konfigurasi level per periode (§5, ADR-0017). docs/08_DATA_SCHEMA.md §2.3.
CREATE TABLE level_config (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  period_id    bigint NOT NULL REFERENCES period(id),
  level_index  int    NOT NULL,                        -- urutan dalam periode
  grid_width   int    NOT NULL,
  grid_height  int    NOT NULL,
  mine_count   int    NOT NULL,
  base_score   int    NOT NULL,                        -- Base(L), ADR-0017 (tunable)
  life_cap     int    NOT NULL,                        -- capNyawa per-level, ADR-0017 (tunable)
  created_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE (period_id, level_index)
);
