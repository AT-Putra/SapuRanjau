package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.lives.LifeService
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// Inbox pemain + klaim hadiah (T-029, `05` §3, ADR-0021).
//
// Pesan hanya bisa DIKIRIM admin (`message.admin_id NOT NULL`, ADR-0021: admin mengetik sendiri per
// pemenang) → sampai panel admin ada (T-042), inbox ini memang kosong. Dibangun sekarang karena
// murah dan supaya klien (T-034) tak menunggu server lagi.
//
// **Push FCM ditunda** (penyimpangan sadar dari ADR-0021, tercatat di `RELEASE`): tak ada tempat
// menyimpan registration token di seluruh `08`, dan yang memproduksinya adalah klien Android yang
// belum ada. Mengarang bentuk tabelnya sekarang berarti menebak apa yang klien punya. Inbox tetap
// jalan tanpa push; push cuma mempercepat pemberitahuan.
@RestController
class InboxController(
    private val jdbc: JdbcClient,
    private val lives: LifeService, // userIdOf: resolusi Firebase UID → app_user (ADR-0030)
    private val claims: PrizeClaimService,
) {

    @GetMapping("/messages")
    fun messages(
        user: VerifiedUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): MessagesResponse {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "page ≥ 0 dan size 1..$MAX_PAGE_SIZE.")
        }
        val userId = lives.userIdOf(user.uid)
        val rows = jdbc.sql(
            "SELECT id, body, created_at, read_at FROM message WHERE user_id = :uid " +
                "ORDER BY created_at DESC, id DESC LIMIT :lim OFFSET :off",
        ).param("uid", userId).param("lim", size).param("off", page * size)
            .query { rs, _ ->
                MessageDto(
                    id = rs.getLong("id").toString(),
                    body = rs.getString("body"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    readAt = rs.getTimestamp("read_at")?.toInstant(),
                )
            }.list()

        val unread = jdbc.sql("SELECT count(*) FROM message WHERE user_id = ? AND read_at IS NULL")
            .param(userId).query(Int::class.java).single()

        return MessagesResponse(page, size, unread, rows)
    }

    // Idempoten: menandai pesan yang sudah dibaca tak mengubah waktu bacanya. Pesan milik pemain
    // lain dibalas 404 — bukan 403, supaya keberadaannya pun tak bocor.
    @PostMapping("/messages/{id}/read")
    @Transactional
    fun read(user: VerifiedUser, @PathVariable id: String): MessageReadResponse {
        val messageId = id.toLongOrNull()
            ?: throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "id pesan tak valid.")
        val userId = lives.userIdOf(user.uid)
        val updated = jdbc.sql(
            "UPDATE message SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL",
        ).params(messageId, userId).update()

        if (updated == 0) {
            val ada = jdbc.sql("SELECT 1 FROM message WHERE id = ? AND user_id = ?")
                .params(messageId, userId).query(Int::class.java).optional().isPresent
            if (!ada) throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Pesan tak ditemukan.")
        }
        return MessageReadResponse(id, true)
    }

    // POST /v1/prizes/claim — form klaim hadiah (PII terenkripsi, ARCH §14).
    @PostMapping("/prizes/claim")
    fun claim(user: VerifiedUser, @RequestBody req: PrizeClaimRequest): PrizeClaimResponse =
        claims.submit(lives.userIdOf(user.uid), req)

    private companion object {
        const val MAX_PAGE_SIZE = 50
    }
}

data class MessagesResponse(val page: Int, val size: Int, val unread: Int, val messages: List<MessageDto>)

data class MessageDto(val id: String, val body: String, val createdAt: Instant, val readAt: Instant?)

data class MessageReadResponse(val id: String, val read: Boolean)
