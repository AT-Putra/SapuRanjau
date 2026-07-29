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

    /** Peringkat periode (tanpa `period` = periode berjalan). `size` dibatasi server ≤ 50. */
    suspend fun leaderboard(page: Int = 0, size: Int = 20): Leaderboard =
        client.get("/v1/leaderboard?page=$page&size=$size", Leaderboard.serializer())

    /** Nama yang tampil di leaderboard & daftar pemenang (ADR-0039). 400 bila di luar 2–20 karakter. */
    suspend fun ubahNamaTampilan(displayName: String): DisplayName =
        client.put("/v1/profile/display-name", DisplayName(displayName), DisplayName.serializer(), DisplayName.serializer())

    /** Inbox admin→pemain (ADR-0021). Satu-satunya kanal pemberitahuan selama push FCM belum ada. */
    suspend fun pesan(page: Int = 0, size: Int = 20): Inbox =
        client.get("/v1/messages?page=$page&size=$size", Inbox.serializer())

    /** Idempoten; pesan milik pemain lain dibalas 404 — keberadaannya pun tak bocor. */
    suspend fun tandaiDibaca(id: String): MessageRead =
        client.post("/v1/messages/$id/read", MessageRead.serializer())

    /**
     * Form klaim hadiah pemenang — **PII** (ADR-0021/0030): no. HP wajib, plus minimal satu dari
     * e-wallet/alamat. Boleh dikirim ulang untuk memperbaiki selama status `pending`.
     * 409 = tak ada kemenangan yang bisa diklaim (atau sudah diproses admin).
     */
    suspend fun klaimHadiah(phone: String, ewallet: String?, address: String?): PrizeClaim =
        client.post("/v1/prizes/claim", PrizeClaimRequest(phone, ewallet, address), PrizeClaimRequest.serializer(), PrizeClaim.serializer())

    /** Setujui S&K periode berjalan (ADR-0026). Versi ≠ versi server → 409, muat naskah baru dulu. */
    suspend fun consent(tncVersion: String): TournamentStatus =
        client.post("/v1/tournament/consent", ConsentRequest(tncVersion), ConsentRequest.serializer(), TournamentStatus.serializer())

    /**
     * Mulai/lanjut level. Tanpa body — level yang dimulai ditentukan server (one-shot, ADR-0024).
     * `revealed`/`flags` terisi saat resume; papan baru mengembalikannya kosong (ARCH §12).
     */
    suspend fun startLevel(): LevelStart = client.post("/v1/tournament/level/start", LevelStart.serializer())

    /** Reveal/flag/chord. Aksi PERTAMA tiap level wajib `REVEAL` (papan terwujud saat itu, ADR-0031). */
    suspend fun action(runId: String, levelIndex: Int, action: TournamentAction, x: Int, y: Int): ActionOutcome =
        client.post(
            "/v1/tournament/level/action",
            ActionRequest(runId, levelIndex, action, Cell(x, y)),
            ActionRequest.serializer(),
            ActionOutcome.serializer(),
        )

    /**
     * Setor `purchaseToken` dari Play → server verifikasi ke Google lalu menerbitkan nyawa.
     * Klien TAK PERNAH menentukan jumlah nyawa: itu dibaca server dari tabel SKU (ADR-0022).
     * Idempoten — token yang sama dikirim ulang membalas keadaan sekarang, bukan grant kedua.
     */
    suspend fun verifikasiPembelian(productId: String, purchaseToken: String): BillingResult =
        client.post(
            "/v1/billing/verify",
            VerifyRequest(productId, purchaseToken),
            VerifyRequest.serializer(),
            BillingResult.serializer(),
        )

    /** Pakai nyawa setelah kena bom — level lanjut DI TEMPAT (ADR-0037). Dompet kosong → 409. */
    suspend fun useLife(runId: String, levelIndex: Int): LifeUsed =
        client.post("/v1/tournament/life/use", UseLifeRequest(runId, levelIndex), UseLifeRequest.serializer(), LifeUsed.serializer())
}

// Perakitan DEV: server lokal + token `dev:<uid>` (ADR-0043). Satu-satunya tempat yang tahu cara
// merakit klien selama Firebase belum ada — diganti saat `FirebaseTokenProvider` masuk.
fun devApi(uid: String = "dev-player"): SapuRanjauApi = SapuRanjauApi(ApiClient(DEV_BASE_URL, DevTokenProvider(uid)))

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

@Serializable
private data class ConsentRequest(val tncVersion: String)

@Serializable
private data class VerifyRequest(val productId: String, val purchaseToken: String)

// Tanpa id pemain: peringkat + nama sudah cukup dirender, dan id internal tak perlu bocor.
// `me` (bukan `isMe`) menandai baris pemilik token — awalan `is` membuat bentuk wire tak simetris.
@Serializable
data class LeaderboardEntry(
    val rank: Int = 0,
    val name: String = "",
    val totalScore: Long = 0,
    val livesUsed: Int = 0,
    val totalTimeMs: Long = 0,
    val totalMoves: Int = 0,
    val me: Boolean = false,
)

@Serializable
data class Leaderboard(
    val periodId: String = "",
    val page: Int = 0,
    val size: Int = 0,
    val entries: List<LeaderboardEntry> = emptyList(),
)

@Serializable
data class DisplayName(val displayName: String)

@Serializable
data class MessageItem(
    val id: String = "",
    val body: String = "",
    val createdAt: String = "",
    val readAt: String? = null,
)

@Serializable
data class Inbox(
    val page: Int = 0,
    val size: Int = 0,
    val unread: Int = 0,
    val messages: List<MessageItem> = emptyList(),
)

@Serializable
data class MessageRead(val id: String = "", val read: Boolean = false)

@Serializable
private data class PrizeClaimRequest(val phone: String, val ewallet: String? = null, val address: String? = null)

// Balasan sengaja TAK memantulkan PII kembali: pemain melihat status, bukan salinan datanya.
@Serializable
data class PrizeClaim(val winnerId: String = "", val status: String = "")

enum class PurchaseStatus { PENDING, VERIFIED, GRANTED, VOIDED, UNKNOWN }

@Serializable
data class BillingResult(
    val status: PurchaseStatus = PurchaseStatus.UNKNOWN,
    val livesGranted: Int = 0,
    val free: Int = 0,
    val paid: Int = 0,
)

@Serializable
data class Cell(val x: Int, val y: Int)

@Serializable
data class RevealedCell(val x: Int, val y: Int, val adjacentMines: Int)

@Serializable
data class LevelStart(
    val runId: String,
    val boardId: String,
    val levelIndex: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val commitHash: String,
    val revealed: List<RevealedCell> = emptyList(),
    val flags: List<Cell> = emptyList(),
    val movesCount: Int = 0,
    val awaitingLife: Boolean = false,
)

// Alfabet aksi KLIEN. `USE_LIFE` sengaja tak ada: server menolaknya dengan 400 — pemakaian nyawa
// punya endpoint sendiri dan cuma hidup di log langkah (ADR-0037).
enum class TournamentAction { REVEAL, FLAG, CHORD }

@Serializable
private data class ActionRequest(val runId: String, val levelIndex: Int, val action: TournamentAction, val cell: Cell)

enum class ActionResult { REVEALED, FLAGGED, UNFLAGGED, HIT_MINE, LEVEL_CLEARED, NO_OP, UNKNOWN }

enum class LevelStatus { CONTINUE, HIT_MINE, LEVEL_CLEARED, UNKNOWN }

@Serializable
data class ActionOutcome(
    val result: ActionResult = ActionResult.UNKNOWN,
    val cells: List<RevealedCell> = emptyList(),
    val status: LevelStatus = LevelStatus.UNKNOWN,
    val movesCount: Int = 0,
    val score: Int? = null, // hanya saat LEVEL_CLEARED
)

@Serializable
private data class UseLifeRequest(val runId: String, val levelIndex: Int)

// `lifeCap` ikut dikirim supaya klien WAJIB memperingatkan "level ini sudah 0 skor" saat
// `livesUsed >= lifeCap` — nyawa tetap boleh dipakai (ADR-0037).
@Serializable
data class LifeUsed(val livesUsed: Int, val lifeCap: Int, val freeLives: Int, val paidLives: Int)
