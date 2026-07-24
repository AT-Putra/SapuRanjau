package com.koneksiglobal.sapuranjau.api.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

// Verifikasi Firebase ID token (Bearer) → identitas terverifikasi. Stateless (ADR-0030): server tak
// simpan sesi, tiap request bawa token. Impl bisa ditukar (Firebase prod vs dev) tanpa menyentuh
// filter/controller — [[FirebaseTokenVerifier]] aktif saat `sapuranjau.auth.firebase.enabled=true`.
interface TokenVerifier {
    fun verify(idToken: String): VerifiedUser? // null = token invalid/kadaluarsa → 401
}

// uid = Firebase UID (ADR-0030). Resolve/registrasi ke entitas app_user by uid = tanggung jawab
// modul yang butuh (T-022 dst), bukan lapisan auth.
data class VerifiedUser(val uid: String)

// Verifier DEV — HANYA local/test. Token bentuk "dev:<uid>" → VerifiedUser(uid); lainnya → null.
// Aktif saat `sapuranjau.auth.firebase.enabled` = false/absen. WAJIB mati di prod (set true →
// FirebaseTokenVerifier). Bukan verifikasi kripto — jangan sekali-kali dipakai produksi.
@Component
@ConditionalOnProperty(name = ["sapuranjau.auth.firebase.enabled"], havingValue = "false", matchIfMissing = true)
class DevTokenVerifier : TokenVerifier {
    init {
        LoggerFactory.getLogger(javaClass)
            .warn("DevTokenVerifier AKTIF — auth PALSU utk dev/test. Set sapuranjau.auth.firebase.enabled=true di prod.")
    }

    override fun verify(idToken: String): VerifiedUser? =
        idToken.removePrefix("dev:").takeIf { it != idToken && it.isNotBlank() }?.let { VerifiedUser(it) }
}
