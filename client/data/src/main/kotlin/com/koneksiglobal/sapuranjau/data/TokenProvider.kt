package com.koneksiglobal.sapuranjau.data

// Sumber Firebase ID token yang dipasang ke tiap request /v1 (ADR-0030). Pluggable dengan pola yang
// sama seperti `TokenVerifier` di server (T-021): transport tak pernah tahu dari mana token datang.
//
// `null` = pemain belum masuk. Casual murni tak pernah memanggil ini — ia offline (ADR-0030).
interface TokenProvider {
    suspend fun idToken(): String?
}

// Stub DEV — pasangan persis `DevTokenVerifier` di server (token berbentuk "dev:<uid>"), sehingga
// seluruh jalur online bisa dibangun & diuji SEBELUM proyek Firebase ada.
//
// WAJIB diganti implementasi Firebase sebelum rilis; server produksi menolaknya begitu
// `sapuranjau.auth.firebase.enabled=true` (RELEASE §3). Implementasi Firebase-nya belum ditulis
// karena `google-services.json` belum ada — plugin google-services menggagalkan build tanpa file itu.
class DevTokenProvider(private val uid: String) : TokenProvider {
    override suspend fun idToken(): String = "dev:$uid"
}

// Token Play Integrity (ADR-0041, kewajiban klien T-032/T-033). Dipisah dari [TokenProvider]: yang
// satu identitas pemain, yang satu bukti perangkat — server pun memisahkannya (auth vs `/v1/integrity`).
interface IntegrityTokenProvider {
    /** `requestHash` mengikat token ke aksi tertentu; server belum memeriksanya (utang ADR-0041). */
    suspend fun token(requestHash: String): String?
}

// Stub DEV — pasangan `StubIntegrityVerifier` di server (token apa pun yang tak kosong lulus).
// Play Integrity yang sungguhan menuntut Google Play Services + APK terdaftar, jadi ia TAK BISA
// dijalankan di emulator: ADR-0041 justru menolak `MEETS_VIRTUAL_INTEGRITY`. Implementasi nyata
// dipasang bersama pengujian di HP fisik (RELEASE §3).
class DevIntegrityTokenProvider : IntegrityTokenProvider {
    override suspend fun token(requestHash: String): String = "dev-integrity:$requestHash"
}
