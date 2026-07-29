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
