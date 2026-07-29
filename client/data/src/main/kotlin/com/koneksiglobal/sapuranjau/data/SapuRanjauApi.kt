package com.koneksiglobal.sapuranjau.data

import kotlinx.serialization.Serializable

// Endpoint yang sudah punya pemanggil di depan mata (T-032/T-033). Sisanya (`level/start`,
// `/action`, `/leaderboard`, `/messages`, `/billing/verify`, …) ditambahkan bersama layarnya —
// DTO tanpa layar cuma tebakan yang harus dirawat. Bentuk field mengikuti 05 §3 (RATIFIED).
class SapuRanjauApi(private val client: ApiClient) {

    /** Gerbang turnamen (ADR-0021/0025/0026): klien memilih layar dari `status`. */
    suspend fun tournamentStatus(): TournamentStatus =
        client.get("/v1/tournament/status", TournamentStatus.serializer())

    /** Dompet nyawa. Membacanya juga yang memicu grant 2 FreeLife periode di server (GDD §7.2). */
    suspend fun wallet(): Wallet = client.get("/v1/wallet", Wallet.serializer())

    /** Attestasi Play Integrity — sekali per sesi, diulang sebelum `validUntil` (ADR-0041). */
    suspend fun attestIntegrity(token: String): Integrity =
        client.post("/v1/integrity", IntegrityRequest(token), IntegrityRequest.serializer(), Integrity.serializer())

    /** Klaim 1 FreeLife dari kemenangan casual — server yang me-replay `(seed, moves)` (ADR-0023). */
    suspend fun claimCasual(claim: CasualClaim): CasualClaimResult =
        client.post("/v1/casual/claim", claim, CasualClaim.serializer(), CasualClaimResult.serializer())
}

// Semua enum wire punya anggota UNKNOWN + dipakai sebagai nilai default: server yang menambah nilai
// baru tak boleh membunuh APK lama (lihat `coerceInputValues` di ApiClient).
enum class TournamentStatusCode { OK, LOCKED, BANNED, CONSENT_REQUIRED, UNKNOWN }

@Serializable
data class TournamentStatus(
    val status: TournamentStatusCode = TournamentStatusCode.UNKNOWN,
    val periodId: String? = null,
    val tncVersion: String = "",
    val banPeriodsLeft: Int? = null, // hanya terisi saat BANNED
)

/** `nextExpiry` ISO-8601; null = semua nyawa carry-over tanpa kedaluwarsa. */
@Serializable
data class Wallet(val free: Int = 0, val paid: Int = 0, val nextExpiry: String? = null)

@Serializable
private data class IntegrityRequest(val token: String)

@Serializable
data class Integrity(val validUntil: String)

enum class CasualAction { REVEAL, FLAG, CHORD }

@Serializable
data class CasualMove(val action: CasualAction, val x: Int, val y: Int)

// `elapsedMs` diperlakukan server sebagai sinyal anomali saja, bukan kebenaran — casual boleh offline.
@Serializable
data class CasualClaim(
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val seed: Long,
    val moves: List<CasualMove>,
    val elapsedMs: Long,
)

// Cap tercapai & papan terlalu mudah = jawaban NORMAL (200), bukan error (T-024).
enum class ClaimResult { GRANTED, CAP_DAILY, CAP_WEEKLY, CAP_MONTHLY, BELOW_THRESHOLD, NO_ACTIVE_PERIOD, UNKNOWN }

@Serializable
data class CasualClaimResult(
    val result: ClaimResult = ClaimResult.UNKNOWN,
    val free: Int = 0,
    val paid: Int = 0,
)
