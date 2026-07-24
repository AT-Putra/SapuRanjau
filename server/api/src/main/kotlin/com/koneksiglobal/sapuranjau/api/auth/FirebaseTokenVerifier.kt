package com.koneksiglobal.sapuranjau.api.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.io.FileInputStream

// Verifier PRODUKSI (ADR-0030): verifikasi kripto ID token via Firebase Admin SDK → UID. Aktif hanya
// saat `sapuranjau.auth.firebase.enabled=true`. Injeksi FirebaseApp memaksa urutan init benar.
@Component
@ConditionalOnProperty(name = ["sapuranjau.auth.firebase.enabled"], havingValue = "true")
class FirebaseTokenVerifier(private val app: FirebaseApp) : TokenVerifier {
    override fun verify(idToken: String): VerifiedUser? =
        try {
            VerifiedUser(FirebaseAuth.getInstance(app).verifyIdToken(idToken).uid)
        } catch (e: Exception) {
            null // token invalid/kadaluarsa/revoked → 401
        }
}

// Inisialisasi FirebaseApp sekali dari service-account JSON. Path via env/properti (ADR-0015:
// secret = env-file, TAK di-commit). Idempoten bila app sudah ada.
@Configuration
@ConditionalOnProperty(name = ["sapuranjau.auth.firebase.enabled"], havingValue = "true")
class FirebaseConfig {
    @Bean
    fun firebaseApp(@Value("\${sapuranjau.auth.firebase.credentials-path}") credentialsPath: String): FirebaseApp {
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val creds = FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) }
        return FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(creds).build())
    }
}
