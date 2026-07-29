-- Jumlah auto-pause per papan (ADR-0028, T-036). Disimpan di DB, bukan cuma di memo sesi:
-- pola penyalahgunaan yang ingin dilihat justru pemain yang berulang kali membanting app ke
-- background — dan itu persis yang membuang memo sesi. docs/08_DATA_SCHEMA.md §2.5.
--
-- Yang TIDAK disimpan: `paused_at`. Jam skor hanya berjalan di antara aksi dalam satu sesi hidup
-- (ADR-0036), jadi sesi yang hilang otomatis tak menghitung jeda apa pun — tak ada yang perlu
-- dipulihkan setelah server restart.
ALTER TABLE board ADD COLUMN pause_count int NOT NULL DEFAULT 0;
