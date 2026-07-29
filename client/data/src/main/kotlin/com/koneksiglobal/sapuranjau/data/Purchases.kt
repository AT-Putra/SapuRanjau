package com.koneksiglobal.sapuranjau.data

import java.util.UUID

// Paket nyawa (ADR-0022). Isi paket ditulis di sini HANYA untuk ditampilkan; yang menentukan berapa
// nyawa terbit tetap tabel SKU milik server — klien tak pernah boleh jadi sumber kebenaran uang.
//
// `hargaTampilan` = angka ADR-0022 sebagai penahan sementara. Harga SESUNGGUHNYA datang dari Play
// (`ProductDetails.formattedPrice`, sudah dalam mata uang & pajak pemain) begitu Play Billing nyata
// terpasang — jangan pernah mengirim harga dari sini ke server.
enum class LifePackage(val productId: String, val nyawa: Int, val hargaTampilan: String) {
    KECIL("life_s", 1, "Rp 5.000"),
    SEDANG("life_m", 5, "Rp 22.500"),
    BESAR("life_l", 10, "Rp 37.500"),
}

// Jembatan ke Google Play Billing. Implementasi nyata butuh Play Services + aplikasi terdaftar di
// Play Console, jadi ia menyusul bersama pengujian di HP fisik (RELEASE §3) — pola pluggable yang
// sama dengan `TokenProvider` dan `IntegrityTokenProvider`.
interface Purchases {
    /**
     * Buka alur beli Play, kembalikan `purchaseToken` (null = pemain membatalkan).
     *
     * [obfuscatedAccountId] **WAJIB** dipasang ke `BillingFlowParams`: itulah yang mengikat purchase
     * ke akun pemain di sisi Google, dan tanpanya token curian bisa diklaim akun lain (sisa lubang
     * T-025). Nilainya = Firebase UID — stabil, opaque, bukan PII, dan Play membatasi 64 karakter.
     */
    suspend fun beli(productId: String, obfuscatedAccountId: String): String?
}

// Stub DEV — pasangan `StubPlayPurchases` di server, yang menerima token apa pun sebagai "purchased".
// Token diberi awalan `dev-` supaya baris `purchase` hasil dev gampang dikenali di database.
class DevPurchases : Purchases {
    override suspend fun beli(productId: String, obfuscatedAccountId: String): String =
        "dev-$productId-${UUID.randomUUID()}"
}
