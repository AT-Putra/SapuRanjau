package com.koneksiglobal.sapuranjau.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koneksiglobal.sapuranjau.data.ApiErrorCode
import com.koneksiglobal.sapuranjau.data.ApiException
import com.koneksiglobal.sapuranjau.data.LeaderboardEntry
import com.koneksiglobal.sapuranjau.data.MessageItem
import com.koneksiglobal.sapuranjau.data.SapuRanjauApi
import com.koneksiglobal.sapuranjau.data.devApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PeringkatUi(
    val memuat: Boolean = true,
    /** Tak ada periode berjalan (ADR-0021) — leaderboard memang tak punya isi, bukan gagal. */
    val terkunci: Boolean = false,
    val entries: List<LeaderboardEntry> = emptyList(),
    val halaman: Int = 0,
    val adaHalamanLagi: Boolean = false,
    val pesanMasuk: List<MessageItem> = emptyList(),
    val belumDibaca: Int = 0,
    val namaTampilan: String? = null,
    val statusKlaim: String? = null,
    val catatan: String? = null,
)

// Satu ViewModel untuk peringkat + inbox: keduanya isi tab yang sama dan selalu dimuat bersamaan,
// jadi memecahnya cuma menggandakan penanganan error yang identik.
class LeaderboardViewModel(private val api: SapuRanjauApi = devApi()) : ViewModel() {

    private val _state = MutableStateFlow(PeringkatUi())
    val state: StateFlow<PeringkatUi> = _state.asStateFlow()

    init {
        muat()
    }

    fun muat(halaman: Int = 0) = jalankan {
        _state.update { it.copy(memuat = true, catatan = null) }
        val papan = api.leaderboard(page = halaman)
        val inbox = api.pesan()
        _state.update {
            it.copy(
                memuat = false,
                terkunci = false,
                entries = papan.entries,
                halaman = papan.page,
                // Server tak mengirim total baris; halaman penuh = pertanda masih ada lanjutannya.
                adaHalamanLagi = papan.entries.size >= papan.size && papan.size > 0,
                pesanMasuk = inbox.messages,
                belumDibaca = inbox.unread,
                namaTampilan = papan.entries.firstOrNull { e -> e.me }?.name ?: it.namaTampilan,
            )
        }
    }

    fun halamanBerikut() = muat(_state.value.halaman + 1)

    fun halamanSebelum() = muat((_state.value.halaman - 1).coerceAtLeast(0))

    fun tandaiDibaca(id: String) = jalankan {
        api.tandaiDibaca(id)
        muat(_state.value.halaman)
    }

    fun ubahNama(nama: String) = jalankan {
        val hasil = api.ubahNamaTampilan(nama.trim())
        _state.update { it.copy(namaTampilan = hasil.displayName, catatan = "Nama tampilan diperbarui.") }
        muat(_state.value.halaman)
    }

    fun klaimHadiah(phone: String, ewallet: String, alamat: String) = jalankan {
        val hasil = api.klaimHadiah(phone.trim(), ewallet.trim().ifBlank { null }, alamat.trim().ifBlank { null })
        _state.update { it.copy(statusKlaim = hasil.status, catatan = "Klaim terkirim. Admin akan menghubungi lewat telepon.") }
    }

    fun tutupCatatan() = _state.update { it.copy(catatan = null) }

    private fun jalankan(blok: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                blok()
            } catch (e: ApiException) {
                // LOCKED bukan error yang perlu tombol "coba lagi": tak ada periode berjalan, titik.
                if (e.code == ApiErrorCode.LOCKED) {
                    _state.update { it.copy(memuat = false, terkunci = true, entries = emptyList()) }
                } else {
                    _state.update { it.copy(memuat = false, catatan = e.detail) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(memuat = false, catatan = "Tak bisa menghubungi server. Coba lagi.") }
            }
        }
    }
}
