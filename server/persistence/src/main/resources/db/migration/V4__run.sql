-- run — agregat pemain per periode = sumber leaderboard (§8, ADR-0024). docs/08_DATA_SCHEMA.md §2.4.
CREATE TABLE run (
  id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           bigint NOT NULL REFERENCES app_user(id),
  period_id         bigint NOT NULL REFERENCES period(id),
  current_level     int    NOT NULL DEFAULT 0,
  total_score       bigint NOT NULL DEFAULT 0,
  lives_used        int    NOT NULL DEFAULT 0,
  total_time_ms     bigint NOT NULL DEFAULT 0,
  total_moves       int    NOT NULL DEFAULT 0,
  completed_all_at  timestamptz,                        -- NULL sampai semua level tuntas (tie-breaker §8.2 #4)
  score_locked      boolean NOT NULL DEFAULT false,     -- refund → skor 0 + kunci (ADR-0025)
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  UNIQUE (period_id, user_id)
);
-- Leaderboard = index scan murni (tanpa node sort), tie-breaker §8.2, covering:
CREATE INDEX run_leaderboard ON run
  (period_id, total_score DESC, lives_used ASC, total_time_ms ASC, total_moves ASC, completed_all_at ASC)
  INCLUDE (user_id);
