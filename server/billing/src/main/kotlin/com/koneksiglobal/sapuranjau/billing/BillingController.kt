package com.koneksiglobal.sapuranjau.billing

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// POST /v1/billing/verify (docs/05 §3, ARCH §8) — klien menyetor `purchaseToken` dari Play,
// server memverifikasinya ke Google lalu menerbitkan PaidLife. Klien tak pernah grant sendiri.
@RestController
@RequestMapping("/billing")
class BillingController(private val billing: BillingService) {

    @PostMapping("/verify")
    fun verify(user: VerifiedUser, @RequestBody req: VerifyRequest): VerifyResponse =
        billing.verifyAndGrant(user.uid, req)
}

// `productId` ikut dikirim karena Play Developer API mengalamatkan purchase lewat (SKU, token) —
// dan justru itu yang MENGIKATNYA: token `life_s` yang diklaim sebagai `life_l` dibalas 404 oleh
// Google, jadi klien tak bisa menaikkan isi paketnya sendiri. Jumlah nyawa tetap dibaca dari tabel
// SKU milik server (ADR-0022), bukan dari body ini.
data class VerifyRequest(val productId: String, val purchaseToken: String)

enum class PurchaseStatus {
    PENDING, VERIFIED, GRANTED, VOIDED;

    companion object {
        fun of(dbValue: String): PurchaseStatus = valueOf(dbValue.uppercase())
    }
}

data class VerifyResponse(
    val status: PurchaseStatus,
    val livesGranted: Int,
    val free: Int,
    val paid: Int,
)
