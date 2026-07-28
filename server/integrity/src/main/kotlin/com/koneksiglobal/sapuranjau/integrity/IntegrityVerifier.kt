package com.koneksiglobal.sapuranjau.integrity

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.FileInputStream
import java.time.Duration
import java.time.Instant

// Gerbang device Play Integrity (T-028, ADR-0041) — pluggable dengan pola yang sama seperti
// `TokenVerifier` (T-021) dan `PlayPurchases` (T-025): implementasi produksi di-guard properti,
// stub jadi default supaya dev & test jalan tanpa kredensial Google.
interface IntegrityVerifier {
    fun verdict(token: String): IntegrityVerdict
}

// Tiga hasil, dan pembedaan `Fail` vs `Unavailable` itulah inti ADR-0041: yang pertama adalah BUKTI
// tentang perangkat pemain, yang kedua adalah kegagalan infrastruktur KITA dan bukan bukti apa pun.
sealed interface IntegrityVerdict {
    data object Pass : IntegrityVerdict

    /** Perangkat/APK tak lulus, atau tokennya tak sah → tolak (403 `INTEGRITY_FAILED`). */
    data class Fail(val reason: String) : IntegrityVerdict

    /** Kita tak bisa memeriksa (Google tak terjangkau, kuota habis, konfigurasi salah) → izinkan + audit. */
    data class Unavailable(val reason: String) : IntegrityVerdict
}

// Stub DEV — HANYA local/test (pola DevTokenVerifier/StubPlayPurchases). Token apa pun yang tak
// kosong dianggap lulus. WAJIB mati di prod (`sapuranjau.integrity.enabled=true`): tanpa itu
// emulator dan APK modifikasi lolos begitu saja.
@Component
@ConditionalOnProperty(name = ["sapuranjau.integrity.enabled"], havingValue = "false", matchIfMissing = true)
class StubIntegrityVerifier : IntegrityVerifier {
    init {
        LoggerFactory.getLogger(javaClass)
            .warn("StubIntegrityVerifier AKTIF — gerbang device PALSU utk dev/test. Set sapuranjau.integrity.enabled=true di prod.")
    }

    override fun verdict(token: String): IntegrityVerdict =
        if (token.isBlank()) IntegrityVerdict.Fail("token kosong") else IntegrityVerdict.Pass
}

// Implementasi PRODUKSI. Play Integrity API dipanggil sebagai REST biasa (`decodeIntegrityToken`),
// sama seperti `GooglePlayPurchases`: yang tak kita punya cuma penukaran service-account JSON →
// access token, itu tugas google-auth-library.
//
// BELUM PERNAH DIJALANKAN terhadap Google sungguhan (kredensial tak ada di repo, ADR-0015) → gate
// verifikasi manual pra-rilis di `RELEASE` §3, persis seperti FirebaseTokenVerifier & PlayPurchases.
@Component
@ConditionalOnProperty(name = ["sapuranjau.integrity.enabled"], havingValue = "true")
class PlayIntegrityVerifier(
    @Value("\${sapuranjau.integrity.package-name}") private val packageName: String,
    @Value("\${sapuranjau.integrity.credentials-path}") private val credentialsPath: String,
    // Umur maksimum token: verdict lama tak boleh dipakai ulang (replay). Token Play Integrity
    // memang berumur pendek; nilai ini pagarnya di sisi kita.
    @Value("\${sapuranjau.integrity.max-token-age:PT5M}") private val maxTokenAge: Duration,
) : IntegrityVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    private val credentials: GoogleCredentials by lazy {
        FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }.createScoped(SCOPE)
    }

    private val http = RestClient.create(BASE)

    override fun verdict(token: String): IntegrityVerdict {
        if (token.isBlank()) return IntegrityVerdict.Fail("token kosong")

        val payload = try {
            credentials.refreshIfExpired()
            http.post()
                .uri("/{pkg}:decodeIntegrityToken", packageName)
                .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
                .body(mapOf("integrity_token" to token))
                .retrieve().body(DecodeResponse::class.java)?.tokenPayloadExternal
        } catch (e: Exception) {
            // Google tak terjangkau / kuota habis / kredensial salah → BUKAN bukti tentang pemain.
            log.error("Decode token Play Integrity gagal", e)
            return IntegrityVerdict.Unavailable(e.javaClass.simpleName)
        } ?: return IntegrityVerdict.Unavailable("respons kosong")

        return evaluate(payload, packageName, maxTokenAge, Instant.now())
    }

    private data class DecodeResponse(val tokenPayloadExternal: TokenPayload? = null)

    private companion object {
        const val BASE = "https://playintegrity.googleapis.com/v1"
        const val SCOPE = "https://www.googleapis.com/auth/playintegrity"
    }
}

// Bentuk payload Play Integrity yang kita pakai (bagian lain diabaikan).
data class TokenPayload(
    val requestDetails: RequestDetails? = null,
    val appIntegrity: AppIntegrity? = null,
    val deviceIntegrity: DeviceIntegrity? = null,
)

data class RequestDetails(val requestPackageName: String? = null, val timestampMillis: String? = null)

data class AppIntegrity(val appRecognitionVerdict: String? = null)

data class DeviceIntegrity(val deviceRecognitionVerdict: List<String>? = null)

// Kebijakan lulus/tolak (ADR-0041) sebagai FUNGSI MURNI: bisa diuji tanpa Google, tanpa Spring,
// tanpa database — dan itu satu-satunya cara menguji aturan ini sebelum klien ada.
//
// Lulus = `MEETS_DEVICE_INTEGRITY` + `PLAY_RECOGNIZED`. `MEETS_VIRTUAL_INTEGRITY` (emulator ber-Play
// services) sengaja TIDAK dihitung lulus: ladang bot paling murah justru emulator.
// `appLicensingVerdict` sengaja tak dituntut — aplikasi ini gratis, jadi ancamannya bot, bukan
// pembajakan, sementara menuntutnya menambah kelas pemain jujur yang tertolak tanpa paham sebabnya.
fun evaluate(payload: TokenPayload, packageName: String, maxTokenAge: Duration, now: Instant): IntegrityVerdict {
    val pkg = payload.requestDetails?.requestPackageName
    if (pkg != packageName) return IntegrityVerdict.Fail("paket tak cocok: $pkg")

    val issued = payload.requestDetails.timestampMillis?.toLongOrNull()
        ?: return IntegrityVerdict.Fail("timestamp token tak terbaca")
    if (Duration.between(Instant.ofEpochMilli(issued), now) > maxTokenAge) {
        return IntegrityVerdict.Fail("token kedaluwarsa")
    }

    val device = payload.deviceIntegrity?.deviceRecognitionVerdict.orEmpty()
    if (DEVICE_OK !in device) return IntegrityVerdict.Fail("deviceRecognitionVerdict=$device")

    val app = payload.appIntegrity?.appRecognitionVerdict
    if (app != APP_OK) return IntegrityVerdict.Fail("appRecognitionVerdict=$app")

    return IntegrityVerdict.Pass
}

private const val DEVICE_OK = "MEETS_DEVICE_INTEGRITY"
private const val APP_OK = "PLAY_RECOGNIZED"
