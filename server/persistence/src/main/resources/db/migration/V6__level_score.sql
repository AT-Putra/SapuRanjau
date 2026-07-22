-- level_score — hasil level final + langkah untuk re-sim (ADR-0017/0023/0024). docs/08_DATA_SCHEMA.md §2.6.
CREATE TABLE level_score (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id          bigint NOT NULL REFERENCES run(id),
  level_config_id bigint NOT NULL REFERENCES level_config(id),
  moves           bytea  NOT NULL,                      -- COMPACT (bukan 1 baris/reveal); anti-cheat re-sim (seed di board)
  moves_count     int    NOT NULL,
  par_moves       int    NOT NULL,                      -- solver, ADR-0017
  active_time_ms  bigint NOT NULL,
  lives_used      int    NOT NULL,
  score           int    NOT NULL CHECK (score >= 0),   -- invariant ADR-0017/§6.2
  completed_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (run_id, level_config_id)                       -- one-shot (ADR-0024)
);
