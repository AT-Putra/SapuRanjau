package com.koneksiglobal.sapuranjau.billing

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.FileInputStream
import java.time.Instant

// Jendela ke Google Play, dibuat pluggable dengan pola yang sama seperti `TokenVerifier` (T-021):
// implementasi produksi di-guard properti, stub jadi default supaya dev & test jalan tanpa
// kredensial. Semua keputusan uang ada di sisi kita (BillingService) — ini murni transport.
interface PlayPurchases {
    /** null = token tak dikenal/tak sah utk SKU itu → klaim ditolak. */
    fun verify(productId: String, purchaseToken: String): PlayPurchase?

    /** Consumable wajib di-consume supaya SKU-nya bisa dibeli lagi (ADR-0011, ARCH §8). */
    fun consume(productId: String, purchaseToken: String)

    /** Purchase yang di-void (refund/chargeback) sejak `since` — sumber deteksi ADR-0025. */
    fun listVoided(since: Instant): List<VoidedPurchase>
}

data class PlayPurchase(val orderId: String?, val purchased: Boolean)

data class VoidedPurchase(val purchaseToken: String, val reason: VoidReason)

// `08` §2.7 `void_reason` hanya mengenal dua nilai; ragam kode Google dipetakan ke dua ini.
enum class VoidReason { REFUND, CHARGEBACK }

// Stub DEV — HANYA local/test (pola sama dengan DevTokenVerifier, T-021). Menerima token apa pun
// sebagai sah dan tak pernah melaporkan void. Aktif saat `sapuranjau.billing.play.enabled`
// false/absen. WAJIB mati di prod (set true → GooglePlayPurchases): tanpa itu siapa pun bisa
// mengarang purchaseToken dan mencetak nyawa.
@Component
@ConditionalOnProperty(name = ["sapuranjau.billing.play.enabled"], havingValue = "false", matchIfMissing = true)
class StubPlayPurchases : PlayPurchases {
    init {
        LoggerFactory.getLogger(javaClass)
            .warn("StubPlayPurchases AKTIF — verifikasi pembelian PALSU utk dev/test. Set sapuranjau.billing.play.enabled=true di prod.")
    }

    override fun verify(productId: String, purchaseToken: String) = PlayPurchase(orderId = "dev-$purchaseToken", purchased = true)
    override fun consume(productId: String, purchaseToken: String) = Unit
    override fun listVoided(since: Instant): List<VoidedPurchase> = emptyList()
}

// Implementasi PRODUKSI (ADR-0011/0025). Play Developer API dipanggil sebagai REST biasa: yang
// tak kita punya cuma penukaran service-account JSON → access token, dan itu tugas
// google-auth-library. Klien androidpublisher generated tak ditarik — 3 endpoint tak sebanding
// dengan berat dependensinya.
//
// BELUM PERNAH DIJALANKAN terhadap Google sungguhan: kredensial tak ada di repo (ADR-0015, secret =
// env-file 0600) → wajib diverifikasi manual saat gate rilis (RELEASE §3), persis seperti
// FirebaseTokenVerifier.
@Component
@ConditionalOnProperty(name = ["sapuranjau.billing.play.enabled"], havingValue = "true")
class GooglePlayPurchases(
    @Value("\${sapuranjau.billing.play.package-name}") private val packageName: String,
    @Value("\${sapuranjau.billing.play.credentials-path}") private val credentialsPath: String,
) : PlayPurchases {

    private val credentials: GoogleCredentials by lazy {
        FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }.createScoped(SCOPE)
    }

    private val http = RestClient.create(BASE)

    private fun token(): String {
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    override fun verify(productId: String, purchaseToken: String): PlayPurchase? =
        http.get()
            .uri("/applications/{pkg}/purchases/products/{sku}/tokens/{token}", packageName, productId, purchaseToken)
            .header("Authorization", "Bearer ${token()}")
            .exchange { _, res ->
                // 404 = pasangan (SKU, token) tak dikenal. Inilah yang mengikat productId: klien tak
                // bisa menukar token `life_s` jadi klaim `life_l` — Google yang menolaknya.
                if (!res.statusCode.is2xxSuccessful) return@exchange null
                val body = res.bodyTo(ProductPurchaseDto::class.java)
                PlayPurchase(orderId = body?.orderId, purchased = body?.purchaseState == PURCHASED)
            }

    override fun consume(productId: String, purchaseToken: String) {
        http.post()
            .uri("/applications/{pkg}/purchases/products/{sku}/tokens/{token}:consume", packageName, productId, purchaseToken)
            .header("Authorization", "Bearer ${token()}")
            .retrieve().toBodilessEntity()
    }

    override fun listVoided(since: Instant): List<VoidedPurchase> =
        http.get()
            .uri("/applications/{pkg}/purchases/voidedpurchases?startTime={start}", packageName, since.toEpochMilli())
            .header("Authorization", "Bearer ${token()}")
            .retrieve().body(VoidedListDto::class.java)
            ?.voidedPurchases.orEmpty()
            .map { VoidedPurchase(it.purchaseToken, reasonOf(it.voidedReason)) }

    // voidedReason 7 = chargeback; sisanya (remorse/not-received/defective/accidental/fraud/other)
    // adalah refund. Dua-duanya disanksi sama beratnya (ADR-0025 zero-tolerance) — pemisahan ini
    // hanya untuk jejak audit & laporan.
    private fun reasonOf(code: Int?): VoidReason = if (code == CHARGEBACK_CODE) VoidReason.CHARGEBACK else VoidReason.REFUND

    private data class ProductPurchaseDto(val orderId: String? = null, val purchaseState: Int? = null)
    private data class VoidedEntryDto(val purchaseToken: String, val voidedReason: Int? = null)
    private data class VoidedListDto(val voidedPurchases: List<VoidedEntryDto>? = null)

    private companion object {
        const val BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        const val PURCHASED = 0
        const val CHARGEBACK_CODE = 7
    }
}
