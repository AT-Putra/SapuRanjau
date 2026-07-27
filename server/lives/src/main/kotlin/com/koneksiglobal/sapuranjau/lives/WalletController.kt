package com.koneksiglobal.sapuranjau.lives

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// GET /v1/wallet (docs/05 §3) — saldo FreeLife/PaidLife + expiry. Prefix `/v1` & auth Bearer datang
// dari `server/api` (ADR-0035). Pembelian nyawa = T-025 (`POST /billing/verify`), bukan di sini.
@RestController
@RequestMapping("/wallet")
class WalletController(private val lives: LifeService) {

    @GetMapping
    fun wallet(user: VerifiedUser): Wallet = lives.wallet(lives.userIdOf(user.uid))
}
