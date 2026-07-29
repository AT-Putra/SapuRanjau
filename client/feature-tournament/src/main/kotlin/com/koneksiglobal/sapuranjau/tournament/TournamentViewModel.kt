package com.koneksiglobal.sapuranjau.tournament

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koneksiglobal.sapuranjau.data.ActionResult
import com.koneksiglobal.sapuranjau.data.ApiErrorCode
import com.koneksiglobal.sapuranjau.data.ApiException
import com.koneksiglobal.sapuranjau.data.Cell
import com.koneksiglobal.sapuranjau.data.DevIntegrityTokenProvider
import com.koneksiglobal.sapuranjau.data.IntegrityTokenProvider
import com.koneksiglobal.sapuranjau.data.LevelStart
import com.koneksiglobal.sapuranjau.data.LevelStatus
import com.koneksiglobal.sapuranjau.data.LifeUsed
import com.koneksiglobal.sapuranjau.data.SapuRanjauApi
import com.koneksiglobal.sapuranjau.data.TournamentAction
import com.koneksiglobal.sapuranjau.data.TournamentStatusCode
import com.koneksiglobal.sapuranjau.data.devApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Gerbang turnamen = LAYAR, bukan pesan error (ADR-0021/0025/0026): kode dari server memilih salah
// satu keadaan di bawah. `Gagal` disediakan terpisah supaya masalah jaringan tak menyamar jadi ban.
sealed interface TournamentUi {
    data object Memuat : TournamentUi
    data object Terkunci : TournamentUi
    data class Dilarang(val sisaPeriode: Int?) : TournamentUi
    data class ButuhPersetujuan(val tncVersion: String) : TournamentUi
    data class Bermain(val level: LevelUi) : TournamentUi
    data class Gagal(val pesan: String) : TournamentUi
}

data class LevelUi(
    val runId: String,
    val levelIndex: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
    val revealed: Map<Cell, Int> = emptyMap(),
    val flags: Set<Cell> = emptySet(),
    val movesCount: Int = 0,
    val status: LevelStatus = LevelStatus.CONTINUE,
    val score: Int? = null,
    val awaitingLife: Boolean = false,
    /** Auto-pause ADR-0028: papan ditutup & jam skor beku di server. */
    val dijeda: Boolean = false,
    val hitungMundurMs: Long = 0,
    /** Hasil pemakaian nyawa TERAKHIR di level ini — sumber peringatan "skor sudah 0" (ADR-0037). */
    val nyawa: LifeUsed? = null,
    val dompetKosong: Boolean = false,
) {
    val sisaBom: Int get() = mineCount - flags.size
    val bolehDisentuh: Boolean get() = status == LevelStatus.CONTINUE && !dijeda
    /** Nyawa berikutnya tak akan menaikkan skor level ini lagi — syarat isi dialog ADR-0037. */
    val skorSudahNol: Boolean get() = nyawa != null && nyawa.livesUsed >= nyawa.lifeCap
}

// Papan hidup di SERVER (ADR-0002): tak ada engine di sini, tak ada peta bom, dan tiap aksi adalah
// satu panggilan. Yang dirender = persis yang dijawab server.
class TournamentViewModel(
    private val api: SapuRanjauApi = devApi(),
    private val integrity: IntegrityTokenProvider = DevIntegrityTokenProvider(),
) : ViewModel() {

    private val _state = MutableStateFlow<TournamentUi>(TournamentUi.Memuat)
    val state: StateFlow<TournamentUi> = _state.asStateFlow()

    init {
        muat()
    }

    fun muat() = jalankan {
        _state.value = TournamentUi.Memuat
        val status = api.tournamentStatus()
        _state.value = when (status.status) {
            TournamentStatusCode.OK -> TournamentUi.Bermain(mulaiLevel().toUi())
            TournamentStatusCode.LOCKED -> TournamentUi.Terkunci
            TournamentStatusCode.BANNED -> TournamentUi.Dilarang(status.banPeriodsLeft)
            TournamentStatusCode.CONSENT_REQUIRED -> TournamentUi.ButuhPersetujuan(status.tncVersion)
            TournamentStatusCode.UNKNOWN -> TournamentUi.Gagal("Versi aplikasi ini belum mengenal keadaan turnamen tersebut. Perbarui aplikasi.")
        }
    }

    fun setujuiSnK(tncVersion: String) = jalankan {
        api.consent(tncVersion)
        muat()
    }

    fun tap(x: Int, y: Int) {
        val level = bermain() ?: return
        if (!level.bolehDisentuh) return
        val at = Cell(x, y)
        if (at in level.flags) return // bendera dulu dilepas — cegah reveal tak sengaja (03 §3)
        // Satu handler kontekstual (ADR-0019): angka terbuka = chord, sel tertutup = reveal.
        kirim(level, if (at in level.revealed) TournamentAction.CHORD else TournamentAction.REVEAL, at)
    }

    fun tahan(x: Int, y: Int) {
        val level = bermain() ?: return
        if (!level.bolehDisentuh) return
        val at = Cell(x, y)
        if (at in level.revealed) return
        // Render optimistis HANYA untuk bendera: hasilnya sudah pasti (tak ada informasi baru dari
        // server) sementara reveal/chord bergantung isi papan yang cuma server tahu. Jawaban server
        // tetap merekonsiliasi — kalau ia menolak, `FLAGGED`/`UNFLAGGED` yang menang.
        perbarui { it.copy(flags = if (at in it.flags) it.flags - at else it.flags + at) }
        kirim(level, TournamentAction.FLAG, at)
    }

    fun pakaiNyawa() = jalankan {
        val level = bermain() ?: return@jalankan
        val hasil = try {
            api.useLife(level.runId, level.levelIndex)
        } catch (e: ApiException) {
            // Dompet kosong (409) bukan kegagalan: itu titik masuk jalur casual gratis / pembelian.
            if (e.code == ApiErrorCode.CONFLICT) return@jalankan perbarui { it.copy(dompetKosong = true) }
            throw e
        }
        // Level lanjut DI TEMPAT dan bom yang meledak di-auto-flag (ADR-0037) — keadaan papan yang
        // baru cuma server yang tahu, jadi ia dibaca ulang, bukan ditebak.
        _state.value = TournamentUi.Bermain(mulaiLevel().toUi().copy(nyawa = hasil))
    }

    fun tutupDialogDompet() = perbarui { it.copy(dompetKosong = false) }

    // ── Auto-pause (ADR-0028) — dipicu daur hidup, TAK ADA tombol pause manual ───────────────────
    // Papan ditutup lebih dulu, baru server dikabari: kalau jaringan lambat, yang tak boleh terjadi
    // adalah papan tetap terlihat sementara pemain sudah keluar (eksploit "pause untuk berpikir").
    fun keBackground() {
        val level = bermain() ?: return
        perbarui { it.copy(dijeda = true, hitungMundurMs = 0) }
        jalankan { api.pauseLevel(level.runId, level.levelIndex) }
    }

    fun keDepan() {
        val level = bermain() ?: return
        if (!level.dijeda) return
        jalankan {
            val lanjut = api.resumeLevel(level.runId, level.levelIndex)
            // Hitung-mundur milik server: klien tak boleh memutuskan sendiri kapan jam jalan lagi.
            var sisa = lanjut.countdownMs
            while (sisa > 0) {
                perbarui { it.copy(hitungMundurMs = sisa) }
                delay(minOf(sisa, 1000L))
                sisa -= 1000L
            }
            perbarui { it.copy(dijeda = false, hitungMundurMs = 0) }
        }
    }

    /** Level berikutnya setelah level bersih (one-shot: tak ada ulang, ADR-0024). */
    fun lanjutLevel() = jalankan { _state.value = TournamentUi.Bermain(mulaiLevel().toUi()) }

    private fun kirim(level: LevelUi, aksi: TournamentAction, at: Cell) = jalankan {
        val hasil = api.action(level.runId, level.levelIndex, aksi, at.x, at.y)
        perbarui { s ->
            s.copy(
                revealed = s.revealed + hasil.cells.associate { Cell(it.x, it.y) to it.adjacentMines },
                flags = when (hasil.result) {
                    ActionResult.FLAGGED -> s.flags + at
                    ActionResult.UNFLAGGED -> s.flags - at
                    else -> s.flags
                },
                movesCount = hasil.movesCount,
                status = hasil.status,
                score = hasil.score ?: s.score,
                awaitingLife = hasil.status == LevelStatus.HIT_MINE,
            )
        }
    }

    // Integritas perangkat diperiksa saat DITAGIH, bukan di muka: `INTEGRITY_REQUIRED` artinya
    // "attest lalu ulangi" (ADR-0041) — dengan begitu attestasi hanya terjadi sekali per sesi.
    private suspend fun mulaiLevel(): LevelStart =
        try {
            api.startLevel()
        } catch (e: ApiException) {
            if (e.code != ApiErrorCode.INTEGRITY_REQUIRED) throw e
            api.attestIntegrity(integrity.token("tournament-start") ?: throw e)
            api.startLevel()
        }

    private fun LevelStart.toUi() = LevelUi(
        runId = runId,
        levelIndex = levelIndex,
        gridWidth = gridWidth,
        gridHeight = gridHeight,
        mineCount = mineCount,
        revealed = revealed.associate { Cell(it.x, it.y) to it.adjacentMines },
        flags = flags.toSet(),
        movesCount = movesCount,
        awaitingLife = awaitingLife,
        status = if (awaitingLife) LevelStatus.HIT_MINE else LevelStatus.CONTINUE,
    )

    private fun bermain(): LevelUi? = (_state.value as? TournamentUi.Bermain)?.level

    private fun perbarui(blok: (LevelUi) -> LevelUi) =
        _state.update { if (it is TournamentUi.Bermain) TournamentUi.Bermain(blok(it.level)) else it }

    // Satu tempat penerjemah error → layar. Gerbang (ADR-0021/0025/0026) punya kode sendiri supaya
    // tak tercampur dengan gangguan jaringan; sisanya tampil apa adanya dari `detail` server.
    private fun jalankan(blok: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                blok()
            } catch (e: ApiException) {
                when (e.code) {
                    ApiErrorCode.LOCKED -> _state.value = TournamentUi.Terkunci
                    ApiErrorCode.BANNED -> _state.value = TournamentUi.Dilarang(null)
                    // Versi S&K-nya tak ada di error, dan menyetujui versi tebakan = 409 → tanya status.
                    ApiErrorCode.CONSENT_REQUIRED -> muat()
                    else -> _state.value = TournamentUi.Gagal(e.detail)
                }
            } catch (e: Exception) {
                _state.value = TournamentUi.Gagal("Tak bisa menghubungi server. Periksa koneksi lalu coba lagi.")
            }
        }
    }
}
