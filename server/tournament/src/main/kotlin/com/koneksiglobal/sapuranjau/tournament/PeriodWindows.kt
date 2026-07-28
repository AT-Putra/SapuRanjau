package com.koneksiglobal.sapuranjau.tournament

// Jendela sanksi & cooldown dihitung ORDINAL — jarak dalam URUTAN periode (`starts_at`), bukan dari
// tanggal maupun dari kolom `tournament_ban.period_end_id` (ADR-0038): ban terbit seketika saat void
// terdeteksi, sedangkan periode dibuat admin ad-hoc tanpa cadence (ADR-0021), jadi P+1/P+2 lazimnya
// BELUM ADA saat ban lahir. Membaca `period_end_id` akan salah-melepas ban.
//
// File ini satu-satunya tempat aturan itu diterjemahkan ke SQL. Gerbang (TournamentGate) dan
// pemilihan pemenang (WinnerService) memakai ekspresi yang sama supaya tak bisa melenceng.

// Jarak ordinal ban TERKECIL milik seorang pemain terhadap sebuah periode; NULL = tak pernah kena
// ban (atau bannya mulai di periode yang belum sampai). ADR-0025: P, P+1, P+2 = jarak 0..2 →
// eligible lagi di P+3.
internal fun banDistanceSql(userExpr: String, periodIdExpr: String) = """
    (SELECT min((SELECT count(*) FROM period x
                  WHERE x.starts_at > ps.starts_at AND x.starts_at <= tp.starts_at))
       FROM tournament_ban b
       JOIN period ps ON ps.id = b.period_start_id
       JOIN period tp ON tp.id = $periodIdExpr
      WHERE b.user_id = $userExpr AND ps.starts_at <= tp.starts_at)
"""

// Cooldown pemenang (ADR-0027): menang di P → tak eligible HADIAH di P+1..P+3 (jarak 1..3), eligible
// lagi P+4. Pemenang yang DIGUGURKAN tidak kena cooldown — ia tak pernah menerima hadiah
// (`status = 'active'`).
internal fun onWinnerCooldownSql(userExpr: String, periodIdExpr: String) = """
    EXISTS (SELECT 1 FROM winner w
              JOIN period wp ON wp.id = w.period_id
              JOIN period tp ON tp.id = $periodIdExpr
             WHERE w.user_id = $userExpr AND w.status = 'active'
               AND (SELECT count(*) FROM period x
                     WHERE x.starts_at > wp.starts_at AND x.starts_at <= tp.starts_at) BETWEEN 1 AND $COOLDOWN_PERIODS)
"""

// Lama sanksi/cooldown dalam satuan periode (ADR-0025 / ADR-0027). Konstanta, bukan admin-config:
// keduanya kebijakan yang tertulis di S&K yang disetujui pemain (ADR-0026) — mengubahnya diam-diam
// lewat panel akan membuat naskah yang mereka setujui berbohong.
internal const val BAN_PERIODS = 3
internal const val COOLDOWN_PERIODS = 3
