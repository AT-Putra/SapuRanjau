-- board: progres level BERJALAN (ADR-0036, T-022). Aditif — kolom lama utuh.
-- DB = sumber kebenaran; cache Board in-memory cuma memo (buang kapan saja → generate + replay).
-- Peta bom & state revealed/flag TETAP tak disimpan: turunan f(config, seed, firstClick) + moves.
ALTER TABLE board
  ADD COLUMN moves          bytea       NOT NULL DEFAULT '\x'::bytea,  -- format identik level_score.moves
  ADD COLUMN moves_count    int         NOT NULL DEFAULT 0,            -- juga token optimistic-lock (ADR-0036)
  ADD COLUMN active_time_ms bigint      NOT NULL DEFAULT 0,            -- waktu aktif server-side (ADR-0028)
  ADD COLUMN updated_at     timestamptz NOT NULL DEFAULT now();
