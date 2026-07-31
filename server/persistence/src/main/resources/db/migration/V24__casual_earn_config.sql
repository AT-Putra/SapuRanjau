-- Parameter earn nyawa casual jadi admin-config (ADR-0023 memang sudah memutuskannya "admin-config";
-- implementasinya tertunda sejak T-024 karena panelnya belum ada). docs/08_DATA_SCHEMA.md §2.17.
--
-- SATU BARIS, dipaksa basis data (`CHECK (id = 1)`) — bukan tabel key-value generik: nilainya
-- bertipe (int/desimal) dan punya invarian antar-kolom yang bisa ditegakkan DB. Tabel KV akan
-- mengubah tiap pembacaan jadi parsing teks dan memindahkan invariannya ke kode.
CREATE TABLE casual_earn_config (
  id           int  PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  reward_lives int  NOT NULL DEFAULT 1 CHECK (reward_lives BETWEEN 1 AND 3),
  cap_daily    int  NOT NULL CHECK (cap_daily  >= 1),
  cap_weekly   int  NOT NULL CHECK (cap_weekly >= 1),
  cap_monthly  int  NOT NULL CHECK (cap_monthly >= 1),
  min_mines    int  NOT NULL CHECK (min_mines >= 1),
  -- Batas atas 0,30 sama dengan pagar kelayakan no-guess (ADR-0031): ambang di atas itu menuntut
  -- papan yang generator memang tak dijamin bisa membuatnya.
  min_density  numeric(4,3) NOT NULL CHECK (min_density > 0 AND min_density <= 0.300),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  updated_by   bigint,                                  -- admin_user.id, tanpa FK (skema admin terpisah, ADR-0010)
  -- Cap mingguan di bawah harian (atau bulanan di bawah mingguan) bukan kebijakan ketat, melainkan
  -- salah ketik: yang lebih kecil akan selalu menang dan yang lain jadi hiasan.
  CHECK (cap_weekly >= cap_daily AND cap_monthly >= cap_weekly)
);

-- Nilai awal = persis default properti yang selama ini dipakai (ADR-0023: 1/5/10; ambang "≥ medium"
-- = intermediate klasik 16×16/40). Setelah baris ini ada, properti `sapuranjau.lives.casual.*`
-- DICABUT — dua sumber kebenaran untuk angka yang sama adalah cara termurah membuat panel berbohong.
--
-- CATATAN LEGAL: menurunkan cap menyentuh lantai `01_GDD.md` §9.5 ("jalur nyawa gratis memadai
-- agar bayar = kenyamanan, bukan keharusan"). 1/5/10 sudah dekat lantai itu; menurunkannya adalah
-- keputusan hukum, bukan penyetelan ekonomi.
INSERT INTO casual_earn_config (id, reward_lives, cap_daily, cap_weekly, cap_monthly, min_mines, min_density)
VALUES (1, 1, 1, 5, 10, 40, 0.150);
