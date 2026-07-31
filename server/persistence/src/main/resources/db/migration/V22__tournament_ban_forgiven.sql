-- Pengampunan ban turnamen (T-042, ADR-0025). docs/08_DATA_SCHEMA.md §2.13.
--
-- Mengampuni pemain = MENANDAI barisnya, BUKAN menghapusnya. `PeriodService.issueDeferredBans()`
-- menerbitkan ban untuk setiap `purchase` ber-status 'voided' yang BELUM punya baris
-- `tournament_ban`; baris yang dihapus akan lahir kembali di tick berikutnya, dan pemain yang sudah
-- diberi tahu "ban Anda dicabut" kena lagi tanpa ada yang menekan tombol apa pun.
--
-- Penegakan (PeriodWindows.banDistanceSql) melewati baris ber-`forgiven_at`. Barisnya tetap ada
-- sebagai sejarah: banding pemain ditangani dari jejak ini (ADR-0025).
--
-- `forgiven_by` = `admin_user.id`, tanpa FK — skema admin memang dipisah (ADR-0010), pola yang sama
-- dengan `prize_claim.verified_by`/`paid_by` (§2.11).
ALTER TABLE tournament_ban
  ADD COLUMN forgiven_at    timestamptz,
  ADD COLUMN forgiven_by    bigint,
  ADD COLUMN forgive_reason text,
  -- Ampunan tanpa alasan tak boleh ada: ia sanksi uang yang dicabut manusia, dan alasannya yang
  -- dibaca saat seseorang bertanya "kenapa orang ini lolos?" (pola ADR-0021 gugur-pemenang).
  ADD CONSTRAINT ban_forgive_reason CHECK (forgiven_at IS NULL OR forgive_reason IS NOT NULL);
