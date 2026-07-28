package com.koneksiglobal.sapuranjau.tournament

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Enkripsi PII (ARCH §14) = fungsi murni atas kunci → diuji tanpa Spring & tanpa database.
class PiiCipherTest {

    private val kunci = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" // 32 byte, HANYA untuk test
    private val cipher = PiiCipher(kunci)

    @Test
    fun `bolak-balik utuh, termasuk karakter non-ASCII`() {
        listOf("081234567890", "Jl. Melati No. 7, RT 03/RW 05, Yogyakarta", "Ünïcødé 東京").forEach {
            assertEquals(it, cipher.decrypt(cipher.encrypt(it)))
        }
    }

    @Test
    fun `ciphertext tak memuat teks aslinya`() {
        val blob = cipher.encrypt("081234567890")
        assertTrue(!String(blob, Charsets.ISO_8859_1).contains("081234567890"))
    }

    @Test
    fun `dua enkripsi nilai yang sama menghasilkan ciphertext berbeda (IV acak)`() {
        // Syarat GCM: IV tak boleh berulang untuk kunci yang sama. Konsekuensinya kolom PII tak
        // bisa dicari — memang tak dibutuhkan (admin membaca per-pemenang).
        assertTrue(!cipher.encrypt("sama").contentEquals(cipher.encrypt("sama")))
    }

    @Test
    fun `byte pertama = versi format, supaya rotasi kunci kelak tak ambigu`() {
        assertEquals(1.toByte(), cipher.encrypt("x")[0])
    }

    @Test
    fun `ciphertext yang diutak-atik ditolak, bukan dibalas data ngawur`() {
        val blob = cipher.encrypt("081234567890")
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte() // rusak tag GCM
        assertFailsWith<Exception> { cipher.decrypt(blob) }
    }

    @Test
    fun `kunci yang tak sah membuat komponen gagal dibuat — server tak boleh boot tanpanya`() {
        assertFailsWith<IllegalArgumentException> { PiiCipher("") }
        assertFailsWith<IllegalArgumentException> { PiiCipher("bukan-base64!!") }
        assertFailsWith<IllegalArgumentException> { PiiCipher("YWFhYWFh") } // base64 sah tapi < 32 byte
    }
}
