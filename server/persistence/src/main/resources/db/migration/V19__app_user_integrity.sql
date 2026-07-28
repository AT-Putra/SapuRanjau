-- app_user.integrity_ok_until — cache verdict Play Integrity (T-028, ADR-0041). docs/08 §2.1.
--
-- Attestasi dilakukan SEKALI PER SESI lewat POST /v1/integrity, bukan per-request: memverifikasi
-- token berarti memanggil API Google (berkuota + latensi jaringan), sedangkan /tournament/level/action
-- punya anggaran p95 < 200 ms (ARCH §11). Gerbang di titik masuk cukup membaca kolom ini.
--
-- Batas yang disadari: cache ini per PEMAIN, bukan per perangkat — pemain yang lulus di HP bersih
-- lalu pindah ke HP di-root tetap lolos sampai masa berlakunya habis. Yang membatasinya = jendela
-- pendek (properti `sapuranjau.integrity.valid-for`), bukan skema.
ALTER TABLE app_user ADD COLUMN integrity_ok_until timestamptz;
