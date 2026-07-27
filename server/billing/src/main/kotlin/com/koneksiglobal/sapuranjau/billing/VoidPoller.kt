package com.koneksiglobal.sapuranjau.billing

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

// Deteksi void (ADR-0025) lewat **Voided Purchases API saja**, di-poll berkala.
//
// ADR-0025 menyebut "Voided Purchases API + Real-time Developer Notifications". RTDN sengaja
// DITUNDA: ia menuntut topik Pub/Sub + endpoint publik yang harus diamankan sendiri, sementara
// keuntungannya cuma latensi — padahal sanksinya berumur 3 periode (mingguan/bulanan), jadi
// terlambat sejam tak mengubah apa pun. Polling menutup kasus yang sama dengan nol permukaan
// serang baru. Naikkan ke RTDN kalau latensi void pernah jadi masalah nyata.
@Component
@ConditionalOnProperty(name = ["sapuranjau.billing.play.enabled"], havingValue = "true")
class VoidPoller(
    private val play: PlayPurchases,
    private val billing: BillingService,
    // Voided Purchases API sendiri hanya menyimpan ~30 hari terakhir. Selalu meminta jendela penuh
    // itu membuat poller **stateless** — tak ada kolom cursor yang bisa melenceng, dan `applyVoid`
    // sudah idempoten jadi mengulang data lama tak berbiaya apa pun.
    @Value("\${sapuranjau.billing.void-lookback-days:30}") private val lookbackDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${sapuranjau.billing.void-poll-ms:3600000}", initialDelayString = "\${sapuranjau.billing.void-poll-initial-ms:60000}")
    fun poll() {
        val since = Instant.now().minus(Duration.ofDays(lookbackDays))
        val voided = runCatching { play.listVoided(since) }
            .onFailure { log.error("Poll voided purchases gagal — dicoba lagi siklus berikutnya", it) }
            .getOrElse { return }

        // Per-purchase, bukan satu transaksi besar: satu baris bermasalah tak boleh membatalkan
        // penegakan atas yang lain.
        var applied = 0
        for (v in voided) {
            runCatching { if (billing.applyVoid(v.purchaseToken, v.reason)) applied++ }
                .onFailure { log.error("Gagal menegakkan void utk token ${v.purchaseToken}", it) }
        }
        if (applied > 0) log.info("Void ditegakkan atas $applied purchase (dari ${voided.size} dilaporkan Play)")
    }
}

// Scheduler hanya hidup bersama Play sungguhan — dev/test tak perlu thread yang mem-poll stub.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["sapuranjau.billing.play.enabled"], havingValue = "true")
class BillingSchedulingConfig
