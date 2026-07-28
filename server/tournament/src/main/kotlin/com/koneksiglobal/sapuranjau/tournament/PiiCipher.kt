package com.koneksiglobal.sapuranjau.tournament

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Enkripsi PII field-level AES-256-GCM (ADR-0020, ARCH §14) untuk kolom `*_enc` di `prize_claim`
// (`08` §2.11). Ini BUKAN sekadar disk-at-rest: yang dilindungi terutama backup offsite (ADR-0015),
// yang keluar dari mesin dan disimpan di tempat lain.
//
// **Server menolak boot tanpa kunci yang sah.** Sengaja tak ada default dev seperti stub-stub lain
// (`DevTokenVerifier`, `StubPlayPurchases`): stub yang lolos ke prod bisa dimatikan hari itu juga,
// sedangkan PII yang terlanjur tertulis memakai kunci yang diketahui publik **tetap tersimpan** —
// kerusakannya senyap dan hanya bisa diperbaiki dengan re-enkripsi seluruh data.
//
// ponytail: satu pemakai hari ini (`prize_claim`). Kalau modul `admin` (T-040) butuh mendekripsi
// untuk peran `finance`, NAIKKAN file ini ke modul bersama — jangan menyalinnya.
@Component
class PiiCipher(@Value("\${sapuranjau.pii.key:}") keyBase64: String) {

    private val key: SecretKeySpec = run {
        require(keyBase64.isNotBlank()) {
            "sapuranjau.pii.key kosong — server tak boleh jalan tanpa kunci PII (ARCH §14, ADR-0015). " +
                "Isi env PII_KEY dengan 32 byte acak ber-base64."
        }
        val raw = try {
            Base64.getDecoder().decode(keyBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("sapuranjau.pii.key bukan base64 yang sah", e)
        }
        require(raw.size == KEY_BYTES) { "sapuranjau.pii.key harus $KEY_BYTES byte (AES-256), bukan ${raw.size}" }
        SecretKeySpec(raw, "AES")
    }

    private val random = SecureRandom()

    // Format: [versi 1 byte][IV 12 byte][ciphertext+tag]. Byte versi ada supaya rotasi kunci kelak
    // tak ambigu — data lama bisa dikenali tanpa menebak. IV diundi acak tiap enkripsi (syarat GCM:
    // IV tak boleh berulang untuk kunci yang sama) → kolom PII **tak bisa dicari/di-query**, dan itu
    // memang tak dibutuhkan: admin membacanya per-pemenang, bukan mencarinya.
    fun encrypt(plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plain.toByteArray())
        return ByteArray(1 + IV_BYTES + ct.size).also {
            it[0] = VERSION
            iv.copyInto(it, 1)
            ct.copyInto(it, 1 + IV_BYTES)
        }
    }

    fun decrypt(blob: ByteArray): String {
        require(blob.size > 1 + IV_BYTES) { "blob PII terlalu pendek" }
        require(blob[0] == VERSION) { "versi enkripsi PII tak dikenal: ${blob[0]}" }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        }
        return String(cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES))
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32 // AES-256
        const val IV_BYTES = 12 // panjang IV yang direkomendasikan untuk GCM
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
