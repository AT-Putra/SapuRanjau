package com.koneksiglobal.sapuranjau.casual

import androidx.lifecycle.ViewModel
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
class CasualViewModel : ViewModel() {

    private val engine = MinesweeperEngine()
    private var board: Board? = null

    private val _state = MutableStateFlow(CasualUiState())
    val state: StateFlow<CasualUiState> = _state.asStateFlow()

    fun pilihKesulitan(d: Difficulty) {
        board = null
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
        val hasil = if (at in s.revealed) engine.chord(b, at) else engine.reveal(b, at)
        terapkan(hasil)
    }

    fun tahan(at: CellIndex) {
        val s = _state.value
        if (!s.bolehDisentuh || at in s.revealed) return
        val b = board ?: return // sebelum papan ada, bendera tak bermakna

        engine.toggleFlag(b, at) // engine harus ikut tahu: bendera memengaruhi cascade & chord
        _state.update {
            it.copy(flags = if (at in it.flags) it.flags - at else it.flags + at)
        }
    }

    // Generate no-guess memutar solver berkali-kali (ADR-0031) — ratusan milidetik di papan besar.
    // Wajib di luar main thread, kalau tidak klik pertama membekukan layar (NFR 60 fps, ARCH §11).
    private fun mulaiPapan(firstClick: CellIndex) {
        val cfg = _state.value.config
        _state.update { it.copy(status = GameStatus.MENYIAPKAN) }
        viewModelScope.launch {
            val b = withContext(Dispatchers.Default) {
                engine.generate(cfg, Random.nextLong(), firstClick)
            }
            board = b
            _state.update { it.copy(status = GameStatus.MAIN) }
            terapkan(engine.reveal(b, firstClick))
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
