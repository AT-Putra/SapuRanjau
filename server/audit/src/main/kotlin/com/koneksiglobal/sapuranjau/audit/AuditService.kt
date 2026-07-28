package com.koneksiglobal.sapuranjau.audit

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4

// Penulis `audit_event` (T-027, `08` §2.15) — SATU tempat, menggantikan lima salinan helper `audit()`
// yang sempat tersebar di `billing`/`lives`/`tournament`.
//
// Tanpa `@Transactional`: penulisan audit selalu IKUT transaksi pemanggilnya. Void yang di-rollback
// tak boleh meninggalkan jejak audit yang mengatakan ia terjadi.
//
// Tabelnya append-only (trigger V15) — tak ada `update`/`delete` di sini, dan memang tak boleh ada.
// Retensi yang dijanjikan `08` §4 saat ini TERHALANG trigger itu; membukanya = ADR sendiri.
@Service
class AuditService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

    fun record(actor: Actor, actorId: Long?, event: String, target: String? = null, detail: Map<String, Any?> = emptyMap()) {
        jdbc.sql(
            "INSERT INTO audit_event (actor_type, actor_id, event_type, target, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
        ).params(listOf(actor.dbValue, actorId, event, target, json.writeValueAsString(detail))).update()
    }
}

// Nilai `actor_type` dijaga CHECK constraint (`08` §2.15) — enum ini yang membuat typo jadi error
// kompilasi, bukan error runtime dari Postgres.
enum class Actor(val dbValue: String) {
    PLAYER("player"), // pemain yang melakukan sendiri (mis. menyetujui S&K)
    ADMIN("admin"), // aksi panel admin (T-040/T-042)
    SYSTEM("system"), // server atas inisiatif sendiri (poller void, rollover periode, flag anomali)
}
