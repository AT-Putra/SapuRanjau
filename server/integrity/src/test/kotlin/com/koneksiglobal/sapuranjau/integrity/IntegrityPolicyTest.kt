package com.koneksiglobal.sapuranjau.integrity







import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Kebijakan lulus/tolak Play Integrity (ADR-0041) = fungsi murni → bisa diuji tanpa Google, tanpa
// Spring, tanpa DB. Ini satu-satunya cara menguji aturannya sebelum klien Android ada.
class IntegrityPolicyTest {

    private val paket = "com.koneksiglobal.sapuranjau"
    private val now = Instant.parse("2026-07-28T10:00:00Z")
    private val maxAge = Duration.ofMinutes(5)

    private fun payload(
        pkg: String? = "com.koneksiglobal.sapuranjau",
        issued: Instant = now,
        device: List<String>? = listOf("MEETS_DEVICE_INTEGRITY"),
        app: String? = "PLAY_RECOGNIZED",
    ) = TokenPayload(
        requestDetails = RequestDetails(pkg, issued.toEpochMilli().toString()),
        appIntegrity = AppIntegrity(app),
        deviceIntegrity = DeviceIntegrity(device),
    )

    private fun nilai(p: TokenPayload) = evaluate(p, paket, maxAge, now)

    @Test
    fun `perangkat Play bersertifikat dengan APK asli = lulus`() {
        assertEquals(IntegrityVerdict.Pass, nilai(payload()))
    }

    @Test
    fun `emulator ditolak walau MEETS_VIRTUAL_INTEGRITY`() {
        // Ladang bot paling murah = emulator ber-Play services. `MEETS_VIRTUAL_INTEGRITY` sengaja
        // BUKAN kelulusan (ADR-0041).
        assertTrue(nilai(payload(device = listOf("MEETS_VIRTUAL_INTEGRITY", "MEETS_BASIC_INTEGRITY"))) is IntegrityVerdict.Fail)
    }

    @Test
    fun `root atau ROM tak dikenal (verdict kosong) ditolak`() {
        assertTrue(nilai(payload(device = emptyList())) is IntegrityVerdict.Fail)
        assertTrue(nilai(payload(device = null)) is IntegrityVerdict.Fail)
    }

    @Test
    fun `MEETS_BASIC_INTEGRITY saja belum cukup`() {
        assertTrue(nilai(payload(device = listOf("MEETS_BASIC_INTEGRITY"))) is IntegrityVerdict.Fail)
    }

    @Test
    fun `APK modifikasi atau bukan dari Play ditolak`() {
        assertTrue(nilai(payload(app = "UNRECOGNIZED_VERSION")) is IntegrityVerdict.Fail)
        assertTrue(nilai(payload(app = null)) is IntegrityVerdict.Fail)
    }

    @Test
    fun `token untuk paket lain ditolak`() {
        assertTrue(nilai(payload(pkg = "com.tetangga.app")) is IntegrityVerdict.Fail)
        assertTrue(nilai(payload(pkg = null)) is IntegrityVerdict.Fail)
    }

    @Test
    fun `token lama ditolak — verdict tak boleh dipakai ulang`() {
        assertTrue(nilai(payload(issued = now.minus(Duration.ofMinutes(6)))) is IntegrityVerdict.Fail)
        assertEquals(IntegrityVerdict.Pass, nilai(payload(issued = now.minus(Duration.ofMinutes(4)))))
    }

    @Test
    fun `MEETS_STRONG_INTEGRITY tetap lulus selama device integrity ikut disebut`() {
        assertEquals(
            IntegrityVerdict.Pass,
            nilai(payload(device = listOf("MEETS_DEVICE_INTEGRITY", "MEETS_STRONG_INTEGRITY"))),
        )
    }
}
