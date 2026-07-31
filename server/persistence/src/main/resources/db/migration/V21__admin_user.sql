-- admin_user — akun panel admin (T-040; ADR-0010 panel terpisah, ADR-0013 auth sesi+2FA).
-- docs/08_DATA_SCHEMA.md §2.16.
--
-- Sengaja TERPISAH dari `app_user`, bukan kolom `is_admin` di sana: pemain diautentikasi Firebase
-- (ADR-0030) dan tak pernah punya password di sistem ini, sedangkan admin punya password + TOTP dan
-- tak pernah ikut turnamen. Satu tabel gabungan berarti satu bug otorisasi cukup untuk menjadikan
-- pemain admin — dan `audit_event.actor_id` kehilangan arti karena `actor_type` tak lagi menentukan
-- ruang id-nya.
--
-- `totp_secret_enc` disimpan TERENKRIPSI (AES-GCM, `PiiCipher`, kunci env ADR-0015) dengan alasan yang
-- sama seperti PII klaim hadiah: yang dilindungi adalah backup offsite. Secret TOTP polos di dump DB
-- membuat 2FA runtuh jadi satu faktor persis di skenario yang jadi alasan 2FA ada. NULL = belum enroll
-- → akun itu belum bisa login (lihat T-040: login pertama memaksa enrolment).
CREATE TABLE admin_user (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username        text NOT NULL UNIQUE,
  password_hash   text NOT NULL,                          -- bcrypt (spring-security-crypto)
  role            text NOT NULL CHECK (role IN ('admin','finance','moderator')),
  totp_secret_enc bytea,                                  -- AES-GCM; NULL = TOTP belum di-enroll
  disabled_at     timestamptz,                            -- non-NULL = akun dinonaktifkan (jangan DELETE: audit merujuk id)
  last_login_at   timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now()
);
