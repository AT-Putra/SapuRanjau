package com.koneksiglobal.sapuranjau.api.tournament

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Endpoint contoh ber-auth: bukti spine T-021 jalan end-to-end (Bearer → filter → resolver →
// prefix /v1 → JSON). Bentuk & enum status = RATIFIED (05 §3, ADR-0021/0025/0026). Logika nyata
// (LOCKED/BANNED/CONSENT_REQUIRED dari periode+ban+consent) menyusul di T-026.
@RestController
class TournamentStatusController {

    // GET /v1/tournament/status (prefix /v1 dari ApiWebConfig). Skeleton: selalu OK utk user terautentikasi.
    @GetMapping("/tournament/status")
    fun status(user: VerifiedUser): StatusResponse = StatusResponse(TournamentStatus.OK)
}

enum class TournamentStatus { LOCKED, BANNED, CONSENT_REQUIRED, OK } // 05 §3

data class StatusResponse(val status: TournamentStatus)
