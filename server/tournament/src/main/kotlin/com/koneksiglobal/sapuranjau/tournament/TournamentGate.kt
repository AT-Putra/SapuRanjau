package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// Gerbang turnamen (T-026): satu tempat yang menjawab "boleh main tidak?" — periode terkunci
// (ADR-0021), ban refund (ADR-0025), persetujuan S&K (ADR-0026).
//
// `GET /v1/tournament/status` cuma MENAMPILKAN hasil gerbang; PENEGAKANNYA ada di `require()` yang
// dipanggil `server/game` sebelum papan disentuh. Server tak pernah percaya klien sudah bertanya.
@Service
class TournamentGate(
    private val jdbc: JdbcClient,
    private val audit: AuditService, // T-027
    // Versi S&K berjalan. Naskahnya hidup di legal pack + klien; server cuma menyimpan versi yang
    // disetujui (ADR-0026/0040). Ganti versi = ganti properti ini bersamaan dengan deploy naskahnya.
    @Value("\${sapuranjau.tournament.tnc-version:2026-07-01}") private val tncVersion: String,
) {

    // Keadaan gerbang untuk seorang pemain. `periodId` null hanya saat LOCKED.
    fun check(userId: Long): GateStatus {
        val periodId = activePeriodId() ?: return GateStatus(TournamentStatus.LOCKED, null, tncVersion, null)

        val banDistance = jdbc.sql("SELECT ${banDistanceSql(":uid", ":pid")}")
            .param("uid", userId).param("pid", periodId)
            .query(Int::class.javaObjectType).optional().orElse(null)
        if (banDistance != null && banDistance < BAN_PERIODS) {
            return GateStatus(TournamentStatus.BANNED, periodId, tncVersion, BAN_PERIODS - banDistance)
        }

        val agreed = jdbc.sql("SELECT tnc_version FROM tournament_consent WHERE user_id = ? AND period_id = ?")
            .params(userId, periodId).query(String::class.java).optional().orElse(null)
        // Versi berubah di tengah periode = harus setuju ulang (ADR-0026), bukan cuma periode baru.
        if (agreed != tncVersion) return GateStatus(TournamentStatus.CONSENT_REQUIRED, periodId, tncVersion, null)

        return GateStatus(TournamentStatus.OK, periodId, tncVersion, null)
    }

    // Penegakan: balas id periode aktif, atau lempar. Kode error dibedakan (LOCKED/BANNED/
    // CONSENT_REQUIRED) karena klien memilih LAYAR dari kode itu — popup S&K vs pesan ban.
    fun require(userId: Long): Long {
        val g = check(userId)
        return when (g.status) {
            TournamentStatus.OK -> g.periodId!!

            TournamentStatus.LOCKED -> throw ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.LOCKED,
                "Tak ada periode turnamen aktif.",
            )

            TournamentStatus.BANNED -> throw ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.BANNED,
                "Akun ini tak bisa ikut turnamen (${g.banPeriodsLeft} periode lagi).",
            )

            TournamentStatus.CONSENT_REQUIRED -> throw ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.CONSENT_REQUIRED,
                "Setujui Syarat & Ketentuan turnamen dulu (versi ${g.tncVersion}).",
            )
        }
    }

    // Persetujuan S&K periode berjalan (ADR-0026). Barisnya = KEADAAN SEKARANG (UNIQUE user+periode,
    // `08` §2.14) → di-upsert; RIWAYATNYA disimpan di `audit_event` yang memang append-only, jadi
    // jejak legal "dia menyetujui versi X jam sekian" tak pernah tertimpa (ADR-0040).
    @Transactional
    fun agree(userId: Long, version: String): GateStatus {
        if (version != tncVersion) {
            throw ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.CONFLICT,
                "Versi S&K sudah berubah jadi $tncVersion — muat ulang naskahnya.",
            )
        }
        val periodId = activePeriodId()
            ?: throw ApiException(HttpStatus.CONFLICT, ErrorCode.LOCKED, "Tak ada periode turnamen aktif.")

        jdbc.sql(
            "INSERT INTO tournament_consent (user_id, period_id, tnc_version) VALUES (?, ?, ?) " +
                "ON CONFLICT (user_id, period_id) DO UPDATE SET tnc_version = EXCLUDED.tnc_version, agreed_at = now()",
        ).params(userId, periodId, version).update()

        // Actor PLAYER: ini benar-benar tindakan pemain (beda dari flag anomali yang diamati server).
        audit.record(Actor.PLAYER, userId, "tournament_consent", "period:$periodId", mapOf("tncVersion" to version))

        return check(userId)
    }

    // Satu periode ACTIVE dijamin index parsial `one_active_period` (`08` §2.2) — bukan cuma kode.
    fun activePeriodId(): Long? =
        jdbc.sql("SELECT id FROM period WHERE status = 'ACTIVE'").query(Long::class.java).optional().orElse(null)
}

// 05 §3 — enum RATIFIED. Sebelumnya tinggal di `server/api` sebagai stub yang selalu OK (T-021).
enum class TournamentStatus { LOCKED, BANNED, CONSENT_REQUIRED, OK }

data class GateStatus(
    val status: TournamentStatus,
    val periodId: Long?,
    val tncVersion: String,
    val banPeriodsLeft: Int?, // hanya saat BANNED — dipakai klien utk "sisa N periode" (ADR-0025)
)
