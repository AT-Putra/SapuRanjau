package com.koneksiglobal.sapuranjau.tournament

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.lives.LifeService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Siklus hidup periode turnamen (ADR-0021): dibuat admin lewat date-picker, lalu berpindah status
// sendiri. CRUD-nya lewat HTTP admin = T-042; di sini operasi domainnya + mesin pergantiannya.
@Service
class PeriodService(
    private val jdbc: JdbcClient,
    private val lives: LifeService,
    private val winners: WinnerService,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Periode baru selalu lahir UPCOMING; yang mengangkatnya jadi ACTIVE cuma `rollover()`. Satu
    // jalur promosi = tak ada dua tempat yang bisa berbeda pendapat soal "sudah waktunya belum".
    @Transactional
    fun create(name: String?, startsAt: Instant, endsAt: Instant): Long {
        if (!endsAt.isAfter(startsAt)) {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Periode berakhir sebelum mulai.")
        }
        // No-overlap terhadap periode aktif/terjadwal (ADR-0021). Periode yang sudah ENDED tak
        // menghalangi apa pun — sejarah tak bisa bentrok dengan jadwal.
        val bentrok = jdbc.sql(
            "SELECT 1 FROM period WHERE status <> 'ENDED' AND starts_at < ? AND ends_at > ? LIMIT 1",
        ).params(endsAt.utc(), startsAt.utc()).query(Int::class.java).optional().isPresent
        if (bentrok) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Rentang bertumpang-tindih dengan periode lain.")
        }

        return jdbc.sql("INSERT INTO period (name, starts_at, ends_at) VALUES (?, ?, ?) RETURNING id")
            // listOf: `name` boleh null (periode tanpa judul), varargs JdbcClient tidak menerimanya.
            .params(listOf(name, startsAt.utc(), endsAt.utc())).query(Long::class.java).single()
    }

    // Tutup periode lebih awal: cukup majukan jam berakhirnya — sisanya (pemenang, papan yatim,
    // nyawa hangus) urusan `rollover()`, jadi jalur "berakhir normal" dan "ditutup admin" identik.
    @Transactional
    fun closeNow(periodId: Long) {
        jdbc.sql("UPDATE period SET ends_at = now() WHERE id = ? AND status <> 'ENDED'").param(periodId).update()
        rollover()
    }

    // Mesin pergantian periode (ADR-0040). Dipanggil tiap tick; TAK MENGINGAT APA PUN — ia cuma
    // melihat keadaan tabel sekarang lalu membetulkannya. Server mati semalam, deploy baru, atau
    // admin memasang periode yang tanggal mulainya sudah lewat: tick berikutnya tetap membereskan.
    // Tiap langkah idempoten, jadi mengulangnya (atau mati di tengah) tak merusak apa pun.
    @Transactional
    fun rollover() {
        // 1. Periode yang jamnya habis → ENDED.
        val ended = jdbc.sql("UPDATE period SET status = 'ENDED' WHERE status = 'ACTIVE' AND ends_at <= now()").update()

        // 2. Papan yang masih terbuka di periode yang tak lagi berjalan → `failed`. Ini yang mencegah
        //    pemain menuntaskan level milik periode mati (skornya sudah tak bisa masuk — lihat guard
        //    di GameService.finalizeLevel — tapi membiarkannya bermain terus itu bohong).
        jdbc.sql(
            "UPDATE board SET status = 'failed', updated_at = now() WHERE status = 'active' AND run_id IN " +
                "(SELECT r.id FROM run r JOIN period p ON p.id = r.period_id WHERE p.status <> 'ACTIVE')",
        ).update()

        // 3. FreeLife terikat periode (`expiry = period.ends_at`, ADR-0008) yang sudah lewat.
        lives.expireLapsed()

        // 4. Pemenang periode yang baru berakhir. Periode tanpa `prize_config` tak masuk daftar ini
        //    (tak ada hadiah → tak ada pemenang). ponytail: periode ber-hadiah yang NOL pemenang
        //    eligible akan dicoba lagi tiap tick — dua query berindeks, dan justru benar bila
        //    admin kemudian mencabut ban seseorang. Butuh penanda "sudah difinalkan" hanya kalau
        //    ini pernah terasa mahal.
        jdbc.sql(
            "SELECT p.id FROM period p JOIN prize_config c ON c.period_id = p.id " +
                "WHERE p.status = 'ENDED' AND NOT EXISTS (SELECT 1 FROM winner w WHERE w.period_id = p.id)",
        ).query(Long::class.java).list().filterNotNull().forEach { winners.finalizePeriod(it) }

        // 5. Angkat SATU periode terjadwal yang jendelanya sudah masuk. `NOT EXISTS` + index parsial
        //    `one_active_period` (`08` §2.2) menjaga invarian "satu ACTIVE" walau datanya aneh.
        val activated = jdbc.sql(
            "UPDATE period SET status = 'ACTIVE' WHERE id = (SELECT id FROM period WHERE status = 'UPCOMING' " +
                "AND starts_at <= now() AND ends_at > now() ORDER BY starts_at LIMIT 1) " +
                "AND NOT EXISTS (SELECT 1 FROM period WHERE status = 'ACTIVE')",
        ).update()

        // 6. Ban yang tertunda (T-025: void terdeteksi saat belum ada periode sama sekali).
        issueDeferredBans()

        if (ended > 0 || activated > 0) log.info("Rollover periode: $ended berakhir, $activated diaktifkan")
    }

    // ADR-0025 tak mengenal void tanpa sanksi. Kalau saat void terdeteksi belum ada periode mana pun,
    // `billing` menundanya (audit `purchase_voided_ban_deferred`) — di sini ban itu diterbitkan
    // begitu ada periode berjalan. Sumbernya `purchase` sendiri, bukan penguraian string audit.
    //
    // PENTING utk T-042: mengampuni pemain = TANDAI bannya, jangan hapus barisnya — baris yang hilang
    // membuat purchase-nya terlihat "belum tertangani" dan tick berikutnya menerbitkan ban baru.
    private fun issueDeferredBans() {
        val activeId = jdbc.sql("SELECT id FROM period WHERE status = 'ACTIVE'")
            .query(Long::class.java).optional().orElse(null) ?: return

        val tertunda = jdbc.sql(
            "SELECT p.id, p.user_id, p.void_reason FROM purchase p WHERE p.status = 'voided' " +
                "AND NOT EXISTS (SELECT 1 FROM tournament_ban b WHERE b.purchase_id = p.id)",
        ).query { rs, _ -> Triple(rs.getLong("id"), rs.getLong("user_id"), rs.getString("void_reason")) }.list()

        for ((purchaseId, userId, reason) in tertunda) {
            jdbc.sql(
                "INSERT INTO tournament_ban (user_id, reason, purchase_id, period_start_id) VALUES (?, ?, ?, ?)",
            ).params(userId, reason, purchaseId, activeId).update()
            jdbc.sql(
                "INSERT INTO audit_event (actor_type, actor_id, event_type, target, detail) " +
                    "VALUES ('system', ?, 'tournament_ban_issued', ?, ?::jsonb)",
            ).params(
                userId, "purchase:$purchaseId",
                json.writeValueAsString(mapOf("reason" to reason, "periodStartId" to activeId, "deferred" to true)),
            ).update()
            log.warn("Ban tertunda purchase $purchaseId diterbitkan mulai periode $activeId")
        }
    }

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}

// Satu tick = sekali bangun. Tak ada timer per-periode yang bisa hilang saat restart: tiap bangun
// membaca keadaan tabel apa adanya (ADR-0040).
//
// ponytail: benar untuk SATU instance app (ADR-0015 = satu app-VM). Dua instance → dua tick
// bersamaan; idempotensi + `UNIQUE (period_id, rank)` menahan kerusakannya, obat sebenarnya
// `pg_try_advisory_lock` — pasang kalau deployment pernah punya instance kedua.
@Component
@ConditionalOnProperty(name = ["sapuranjau.tournament.tick.enabled"], havingValue = "true", matchIfMissing = true)
class PeriodTick(private val periods: PeriodService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${sapuranjau.tournament.tick-ms:60000}",
        initialDelayString = "\${sapuranjau.tournament.tick-initial-ms:10000}",
    )
    fun tick() {
        // Exception tak boleh keluar dari sini: satu tick gagal (mis. DB sedang lepas) tak boleh
        // mendiamkan pergantian periode selamanya.
        runCatching { periods.rollover() }.onFailure { log.error("Rollover periode gagal — dicoba lagi tick berikutnya", it) }
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["sapuranjau.tournament.tick.enabled"], havingValue = "true", matchIfMissing = true)
class TournamentSchedulingConfig
