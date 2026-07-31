package com.koneksiglobal.sapuranjau.admin

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// TOTP ditulis tangan (lihat Totp.kt) → ia wajib diuji terhadap vektor resmi RFC 6238, bukan terhadap
// dirinya sendiri. Secret vektor itu = ASCII "12345678901234567890".
class TotpTest {

    private val secret = Totp.base32Encode("12345678901234567890".toByteArray())

    @Test
    fun `base32 sesuai RFC 4648`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", secret)
        assertEquals("12345678901234567890", String(Totp.base32Decode(secret)))
    }

    @Test
    fun `kode cocok dengan vektor uji RFC 6238`() {
        // Vektor SHA-1 resmi memakai 8 digit; implementasi ini 6 digit = enam digit terakhirnya.
        assertEquals("287082", Totp.code(secret, 59))
        assertEquals("081804", Totp.code(secret, 1_111_111_109))
        assertEquals("005924", Totp.code(secret, 1_234_567_890))
        assertEquals("279037", Totp.code(secret, 2_000_000_000))
    }

    @Test
    fun `verify menerima jendela sekarang dan tetangganya, menolak yang jauh`() {
        val now = Instant.ofEpochSecond(1_234_567_890)
        assertTrue(Totp.verify(secret, Totp.code(secret, now.epochSecond), now))
        assertTrue(Totp.verify(secret, Totp.code(secret, now.epochSecond - 30), now), "jam klien lambat 30 dtk")
        assertTrue(Totp.verify(secret, Totp.code(secret, now.epochSecond + 30), now), "jam klien cepat 30 dtk")
        assertFalse(Totp.verify(secret, Totp.code(secret, now.epochSecond + 120), now), "4 langkah = ditolak")
    }

    @Test
    fun `kode cacat ditolak tanpa melempar`() {
        val now = Instant.ofEpochSecond(1_234_567_890)
        assertFalse(Totp.verify(secret, "", now))
        assertFalse(Totp.verify(secret, "12345", now))
        assertFalse(Totp.verify(secret, "abcdef", now))
    }

    @Test
    fun `secret acak panjang 160 bit dan berbeda tiap panggilan`() {
        val a = Totp.randomSecret()
        assertEquals(20, Totp.base32Decode(a).size)
        assertFalse(a == Totp.randomSecret())
    }
}
