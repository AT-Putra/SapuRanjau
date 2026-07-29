package com.koneksiglobal.sapuranjau.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koneksiglobal.sapuranjau.data.ApiException
import com.koneksiglobal.sapuranjau.data.DevPurchases
import com.koneksiglobal.sapuranjau.data.LifePackage
import com.koneksiglobal.sapuranjau.data.PurchaseStatus
import com.koneksiglobal.sapuranjau.data.Purchases
import com.koneksiglobal.sapuranjau.data.SapuRanjauApi
import com.koneksiglobal.sapuranjau.data.devApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalletUi(
    val memuat: Boolean = true,
    val gratis: Int = 0,
    val berbayar: Int = 0,
    /** ISO-8601; null = tak ada nyawa yang kedaluwarsa (semuanya carry-over). */
    val kedaluwarsaTerdekat: String? = null,
    val sedangMembeli: LifePackage? = null,
    val pesan: String? = null,
) {
    val total: Int get() = gratis + berbayar
}

// Pembelian = tiga langkah yang TAK BOLEH ditukar urutannya: Play menerbitkan token → server
// memverifikasinya ke Google → server yang menerbitkan nyawa. Klien tak pernah menambah nyawa
// sendiri (ADR-0011/0022), jadi saldo di layar ini selalu datang dari `GET /v1/wallet`.
class WalletViewModel(
    private val api: SapuRanjauApi = devApi(),
    private val purchases: Purchases = DevPurchases(),
    /** Firebase UID; jadi `obfuscatedAccountId` yang mengikat purchase ke akun (menutup lubang T-025). */
    private val accountId: String = "dev-player",
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUi())
    val state: StateFlow<WalletUi> = _state.asStateFlow()

    init {
        muat()
    }

    // Membaca dompet juga yang MEMICU grant 2 FreeLife periode di server (GDD §7.2) — jadi layar ini
    // sengaja dibaca ulang tiap masuk, bukan di-cache.
    fun muat() = jalankan {
        _state.update { it.copy(memuat = true, pesan = null) }
        val w = api.wallet()
        _state.value = WalletUi(memuat = false, gratis = w.free, berbayar = w.paid, kedaluwarsaTerdekat = w.nextExpiry)
    }

    fun beli(paket: LifePackage) = jalankan {
        _state.update { it.copy(sedangMembeli = paket, pesan = null) }
        val token = purchases.beli(paket.productId, accountId)
        if (token == null) { // pemain membatalkan di dialog Play — bukan error
            _state.update { it.copy(sedangMembeli = null) }
            return@jalankan
        }
        val hasil = api.verifikasiPembelian(paket.productId, token)
        _state.update {
            it.copy(
                memuat = false,
                sedangMembeli = null,
                gratis = hasil.free,
                berbayar = hasil.paid,
                pesan = when (hasil.status) {
                    PurchaseStatus.GRANTED -> "${hasil.livesGranted} nyawa masuk ke dompet."
                    PurchaseStatus.PENDING -> "Pembayaran masih diproses Google. Nyawa masuk begitu lunas."
                    PurchaseStatus.VOIDED -> "Pembelian ini dibatalkan/di-refund."
                    else -> "Pembelian tercatat."
                },
            )
        }
    }

    fun tutupPesan() = _state.update { it.copy(pesan = null) }

    private fun jalankan(blok: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                blok()
            } catch (e: ApiException) {
                // Token yang gagal diverifikasi TIDAK hangus: server me-rollback penuh sehingga token
                // yang sama boleh dicoba lagi (T-025). Karena itu pesannya menyuruh coba lagi.
                _state.update { it.copy(memuat = false, sedangMembeli = null, pesan = e.detail) }
            } catch (e: Exception) {
                _state.update { it.copy(memuat = false, sedangMembeli = null, pesan = "Tak bisa menghubungi server. Coba lagi.") }
            }
        }
    }
}
