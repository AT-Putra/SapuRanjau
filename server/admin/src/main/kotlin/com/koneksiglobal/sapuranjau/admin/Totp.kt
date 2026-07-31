package com.koneksiglobal.sapuranjau.admin

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// TOTP RFC 6238 (HMAC-SHA1, 6 digit, langkah 30 detik) — 2FA wajib panel admin (ARCH §10, ADR-0013).
//
// Ditulis tangan, tanpa library TOTP + tanpa library QR: algoritmanya ~40 baris di atas `javax.crypto`
// yang sudah ada di JDK, dan QR bisa dilewati karena Google Authenticator/Authy menerima secret
// base32 yang diketik manual. Operator panel ini 1–3 orang, sekali enrol seumur akun — menambah dua
// dependency (plus permukaan supply-chain-nya) untuk menghemat sekali ketik adalah pertukaran rugi.
//
// SHA1 di sini bukan kelalaian: RFC 6238 memakainya sebagai default dan itu satu-satunya algoritma
// yang dijamin didukung semua aplikasi authenticator. Kekuatannya datang dari secret 160-bit + jendela
// 30 detik, bukan dari hash-nya.
object Totp {

    const val DIGITS = 6
    const val STEP_SECONDS = 30L
    private const val SECRET_BYTES = 20 // 160 bit, sesuai anjuran RFC 4226 §4
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun randomSecret(): String = base32Encode(ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes))

    // Kode untuk satu titik waktu. Dipakai verify() dan test.
    fun code(secretBase32: String, atEpochSecond: Long): String {
        val counter = atEpochSecond / STEP_SECONDS
        val message = ByteArray(8)
        var sisa = counter
        for (i in 7 downTo 0) {
            message[i] = (sisa and 0xff).toByte()
            sisa = sisa ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(base32Decode(secretBase32), "HmacSHA1"))
        }
        val hash = mac.doFinal(message)
        // Dynamic truncation (RFC 4226 §5.3): 4 byte mulai dari offset yang ditunjuk nibble terakhir.
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val bin = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(DIGITS, '0')
    }

    // Toleransi ±1 langkah (±30 dtk): jam HP operator dan jam server tak pernah persis sama, dan
    // menolak karena selisih 2 detik adalah cara tercepat membuat 2FA dimatikan orang.
    //
    // ponytail: kode yang SUDAH DIPAKAI masih diterima sampai jendelanya lewat (RFC 6238 §5.2
    // menyarankan menolaknya). Menutup itu butuh kolom `admin_user.last_totp_step` + satu UPDATE per
    // login; nilainya kecil selama jalurnya HTTPS dan operatornya sedikit. Kalau panel dibuka ke
    // banyak orang, tambahkan kolom itu — bukan cache di memori (dua instance app = dua ingatan).
    fun verify(secretBase32: String, code: String, now: Instant = Instant.now(), skewSteps: Int = 1): Boolean {
        val bersih = code.trim().filter { it.isDigit() }
        if (bersih.length != DIGITS) return false
        val epoch = now.epochSecond
        return (-skewSteps..skewSteps).any { langkah ->
            // Bandingkan constant-time: kebocoran waktu di sini kecil, tapi gratis untuk ditutup.
            MessageDigest.isEqual(
                code(secretBase32, epoch + langkah * STEP_SECONDS).toByteArray(),
                bersih.toByteArray(),
            )
        }
    }

    // URI standar yang dibaca aplikasi authenticator; ditampilkan sebagai teks (operator boleh
    // menempelkannya, atau mengetik secret-nya saja).
    fun otpauthUri(issuer: String, account: String, secretBase32: String): String {
        val i = URLEncoder.encode(issuer, Charsets.UTF_8)
        val a = URLEncoder.encode(account, Charsets.UTF_8)
        return "otpauth://totp/$i:$a?secret=$secretBase32&issuer=$i&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    // ── Base32 RFC 4648 (tanpa padding — semua authenticator menerimanya) ────────────────────────

    fun base32Encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(BASE32[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(BASE32[(buffer shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    fun base32Decode(secret: String): ByteArray {
        val out = ArrayList<Byte>(secret.length * 5 / 8 + 1)
        var buffer = 0
        var bits = 0
        for (c in secret.uppercase()) {
            if (c == '=' || c.isWhitespace() || c == '-') continue
            val v = BASE32.indexOf(c)
            require(v >= 0) { "karakter base32 tak sah: $c" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
