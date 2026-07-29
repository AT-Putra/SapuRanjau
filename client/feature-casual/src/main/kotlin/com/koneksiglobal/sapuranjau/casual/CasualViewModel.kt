package com.koneksiglobal.sapuranjau.casual

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.koneksiglobal.sapuranjau.engine.Board
import com.koneksiglobal.sapuranjau.engine.CellIndex
import com.koneksiglobal.sapuranjau.engine.LevelConfig
import com.koneksiglobal.sapuranjau.engine.MinesweeperEngine
import com.koneksiglobal.sapuranjau.engine.RevealResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

// Preset klasik. "Sedang" sengaja = 16×16/40, ambang yang sama dengan syarat earn nyawa di server
// (ADR-0023/T-024) — pemain yang berlatih di sini berlatih di papan yang nanti berbuah nyawa.
enum class Difficulty(val label: String, val config: LevelConfig) {
    MUDAH("Mudah", LevelConfig(9, 9, 10)),
    SEDANG("Sedang", LevelConfig(16, 16, 40)),
    SULIT("Sulit", LevelConfig(16, 30, 99)),
}

enum class GameStatus { SIAP, MENYIAPKAN, MAIN, KALAH, MENANG }

data class CasualUiState(
    val difficulty: Difficulty = Difficulty.MUDAH,
    val revealed: Map<CellIndex, Int> = emptyMap(),
    val flags: Set<CellIndex> = emptySet(),
    val status: GameStatus = GameStatus.SIAP,
    val explodedAt: CellIndex? = null,
) {
    val config: LevelConfig get() = difficulty.config
    val sisaBom: Int get() = config.mineCount - flags.size
    val bolehDisentuh: Boolean get() = status == GameStatus.SIAP || status == GameStatus.MAIN
}

// State permainan dipegang di sini, BUKAN di composable (03 §7). Papan sendiri hidup di `engine-core`
// yang deterministik; UI cuma menyimpan apa yang sudah terlihat pemain.
//
// Catatan jujur: `Board.revealed`/`flags` bersifat `internal` di engine (milik modul itu), jadi klien
// memang harus melacak state tampilannya sendiri — persis yang dilakukan `LevelSession` di server.
class CasualViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = MinesweeperEngine()
    private var board: Board? = null

    // Papan casual deterministik dari `(config, seed, klik pertama)` (ADR-0031) → yang perlu disimpan
    // cuma ketiganya + log langkah, bukan papan hasilnya. Simpanan jadi ratusan byte, dan tak ada
    // sumber kebenaran kedua yang bisa melenceng dari engine.
    private var seed: Long = 0
    private var klikPertama: CellIndex? = null
    private val langkah = ArrayList<Langkah>()

    private val _state = MutableStateFlow(CasualUiState())
    val state: StateFlow<CasualUiState> = _state.asStateFlow()

    private val simpanan = app.getSharedPreferences("casual", Context.MODE_PRIVATE)

    init {
        pulihkan()
    }

    fun pilihKesulitan(d: Difficulty) {
        board = null
        klikPertama = null
        langkah.clear()
        simpanan.edit().remove(KUNCI).apply()
        _state.value = CasualUiState(difficulty = d)
    }

    fun ulang() = pilihKesulitan(_state.value.difficulty)

    fun tap(at: CellIndex) {
        val s = _state.value
        if (!s.bolehDisentuh || at in s.flags) return

        val b = board
        if (b == null) {
            mulaiPapan(at) // papan terwujud saat klik pertama (ADR-0031) — first-click-safe
            return
        }
        // Satu handler kontekstual (ADR-0019): angka terbuka = chord, sel tertutup = reveal.
        val chord = at in s.revealed
        langkah += Langkah(if (chord) "CHORD" else "REVEAL", at.x, at.y)
        terapkan(if (chord) engine.chord(b, at) else engine.reveal(b, at))
    }

    fun tahan(at: CellIndex) {
        val s = _state.value
        if (!s.bolehDisentuh || at in s.revealed) return
        val b = board ?: return // sebelum papan ada, bendera tak bermakna

        engine.toggleFlag(b, at) // engine harus ikut tahu: bendera memengaruhi cascade & chord
        langkah += Langkah("FLAG", at.x, at.y)
        _state.update {
            it.copy(flags = if (at in it.flags) it.flags - at else it.flags + at)
        }
    }

    // Generate no-guess memutar solver berkali-kali (ADR-0031) — ratusan milidetik di papan besar.
    // Wajib di luar main thread, kalau tidak klik pertama membekukan layar (NFR 60 fps, ARCH §11).
    private fun mulaiPapan(firstClick: CellIndex) {
        val cfg = _state.value.config
        val undian = Random.nextLong()
        _state.update { it.copy(status = GameStatus.MENYIAPKAN) }
        viewModelScope.launch {
            val b = withContext(Dispatchers.Default) { engine.generate(cfg, undian, firstClick) }
            board = b
            seed = undian
            klikPertama = firstClick
            langkah.clear()
            langkah += Langkah("REVEAL", firstClick.x, firstClick.y)
            _state.update { it.copy(status = GameStatus.MAIN) }
            terapkan(engine.reveal(b, firstClick))
        }
    }

    // ── Simpan & lanjut (T-036, ADR-0028) ────────────────────────────────────────────────────────
    // Casual TIDAK di-blur dan tak punya jam skor: "auto-pause"-nya memang cuma menyimpan keadaan.
    // Dipanggil daur hidup layar (ON_STOP), bukan tombol.
    fun simpan() {
        val awal = klikPertama
        if (awal == null || _state.value.status != GameStatus.MAIN) {
            simpanan.edit().remove(KUNCI).apply() // papan selesai/belum dimulai tak perlu dibawa pulang
            return
        }
        val isi = Simpanan(_state.value.difficulty.name, seed, awal.x, awal.y, langkah.toList())
        simpanan.edit().putString(KUNCI, json.encodeToString(Simpanan.serializer(), isi)).apply()
    }

    // Pemulihan = regenerate papan dari `(config, seed, klik pertama)` lalu MEMUTAR ULANG log langkah
    // lewat engine yang sama — bukan memuat "hasil" yang tersimpan. Jadi tak ada bentuk kedua yang
    // bisa keliru, dan simpanan rusak/format lama cukup dibuang.
    private fun pulihkan() {
        val teks = simpanan.getString(KUNCI, null) ?: return
        val isi = runCatching { json.decodeFromString(Simpanan.serializer(), teks) }.getOrNull() ?: return
        val d = Difficulty.entries.firstOrNull { it.name == isi.difficulty } ?: return

        viewModelScope.launch {
            _state.value = CasualUiState(difficulty = d, status = GameStatus.MENYIAPKAN)
            val awal = CellIndex(isi.firstX, isi.firstY)
            val b = withContext(Dispatchers.Default) { engine.generate(d.config, isi.seed, awal) }
            board = b
            seed = isi.seed
            klikPertama = awal
            langkah.clear()
            _state.update { it.copy(status = GameStatus.MAIN) }
            isi.moves.forEach { m ->
                val at = CellIndex(m.x, m.y)
                langkah += m
                when (m.action) {
                    "FLAG" -> {
                        engine.toggleFlag(b, at)
                        _state.update { it.copy(flags = if (at in it.flags) it.flags - at else it.flags + at) }
                    }
                    "CHORD" -> terapkan(engine.chord(b, at))
                    else -> terapkan(engine.reveal(b, at))
                }
            }
        }
    }

    private fun terapkan(hasil: RevealResult) {
        _state.update { s ->
            when (hasil) {
                is RevealResult.Revealed -> s.copy(revealed = s.revealed + hasil.cells.associate { it.index to it.adjacentMines })
                is RevealResult.LevelCleared -> s.copy(
                    revealed = s.revealed + hasil.cells.associate { it.index to it.adjacentMines },
                    status = GameStatus.MENANG,
                )
                is RevealResult.HitMine -> s.copy(status = GameStatus.KALAH, explodedAt = hasil.at)
            }
        }
    }
}

// Bentuk simpanan casual. `moves` memakai nama aksi sebagai teks, bukan ordinal enum: file yang
// sudah telanjur ditulis di HP pemain tak boleh berubah arti hanya karena urutan enum digeser.
@Serializable
internal data class Langkah(val action: String, val x: Int, val y: Int)

@Serializable
internal data class Simpanan(
    val difficulty: String,
    val seed: Long,
    val firstX: Int,
    val firstY: Int,
    val moves: List<Langkah>,
)

private val json = Json { ignoreUnknownKeys = true }
private const val KUNCI = "papan-berjalan"
