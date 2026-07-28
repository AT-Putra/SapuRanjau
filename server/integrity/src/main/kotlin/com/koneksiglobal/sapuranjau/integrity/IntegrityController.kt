package com.koneksiglobal.sapuranjau.integrity

import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// POST /v1/integrity (ADR-0041) — klien menyerahkan token Play Integrity, server memutuskan dan
// meng-cache verdictnya. Dipanggil saat masuk mode turnamen atau sebelum klaim nyawa casual.
@RestController
class IntegrityController(private val gate: IntegrityGate, private val jdbc: JdbcClient) {

    @PostMapping("/integrity")
    fun attest(user: VerifiedUser, @RequestBody req: IntegrityRequest): IntegrityResponse =
        IntegrityResponse(gate.attest(userIdOf(user.uid), req.token).toString())

    // ponytail: 4 baris kembar dengan `lives`/`game` — modul `user` bersama baru berbayar kalau ada
    // pemanggil keempat; endpoint ini memang bisa jadi sentuhan PERTAMA pemain, jadi ia harus bisa
    // membuat barisnya sendiri (ADR-0030: akun lahir saat pemain menyentuh fitur online).
    @Transactional
    fun userIdOf(firebaseUid: String): Long {
        jdbc.sql("INSERT INTO app_user (firebase_uid) VALUES (?) ON CONFLICT (firebase_uid) DO NOTHING")
            .param(firebaseUid).update()
        return jdbc.sql("SELECT id FROM app_user WHERE firebase_uid = ?")
            .param(firebaseUid).query(Long::class.java).single()
    }
}

data class IntegrityRequest(val token: String)

// `validUntil` = ISO-8601; klien attest ulang sebelum waktu itu supaya tak kena 403 di tengah sesi.
data class IntegrityResponse(val validUntil: String)
