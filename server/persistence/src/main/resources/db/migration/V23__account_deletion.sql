-- Penghapusan akun pemain (ADR-0044, T-052 turunan). docs/08_DATA_SCHEMA.md §2.1 & §2.11.
--
-- "Hapus akun" TIDAK bisa berarti DELETE: id `app_user` dirujuk 7 tabel + `audit_event`, dan
-- barisnya menopang milik ORANG LAIN — `winner` menentukan peringkat & cooldown peserta lain
-- (ADR-0021/0027), `run`/`level_score` dasar peringkat periode lampau sekaligus bahan re-simulasi
-- anti-cheat (ADR-0036), `purchase` bahan pembukuan & void (ADR-0025). Yang dibuang adalah
-- KAITANNYA KE MANUSIA, bukan barisnya.
--
-- `deleted_at`, bukan nilai baru di CHECK `status`: `status` sudah bermakna 'active'/'banned' untuk
-- penegakan sanksi, dan menumpuk arti ketiga di sana membuat tiap query sanksi harus ingat
-- kekecualian baru.
ALTER TABLE app_user ADD COLUMN deleted_at timestamptz;

-- PII klaim hadiah dihapus SUNGGUHAN saat akun dihapus. `phone_enc` semula NOT NULL karena klaim
-- tanpa nomor tak bisa diverifikasi (ADR-0021); setelah akun hilang, tak ada lagi yang perlu
-- ditelepon — yang tersisa hanya `prize_value`/`status`/`paid_at` sebagai jejak pembukuan & pajak.
ALTER TABLE prize_claim ALTER COLUMN phone_enc DROP NOT NULL;
