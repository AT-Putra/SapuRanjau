package com.koneksiglobal.sapuranjau.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Self-check T-010: reveal/flood-fill deterministik. Board dirakit langsung (generate = T-011).
class RevealTest {

    private val engine = MinesweeperEngine()

    // 3x3, satu bom di (0,0). Semua sel selain bom & tetangganya = 0-cell.
    private fun board() = Board(LevelConfig(3, 3, 1), seed = 42L, mines = setOf(CellIndex(0, 0)))

    @Test fun hitMine() {
        assertEquals(RevealResult.HitMine(CellIndex(0, 0)), engine.reveal(board(), CellIndex(0, 0)))
    }

    @Test fun revealSingleNumberedCell() {
        // (1,0) bersebelahan dengan bom → 1 tetangga, tanpa flood-fill.
        val r = assertIs<RevealResult.Revealed>(engine.reveal(board(), CellIndex(1, 0)))
        assertEquals(listOf(RevealedCell(CellIndex(1, 0), 1)), r.cells)
    }

    @Test fun floodFillClearsBoard() {
        // Klik sel-0 terjauh (2,2) → cascade membuka semua 8 sel aman → menang.
        val r = assertIs<RevealResult.LevelCleared>(engine.reveal(board(), CellIndex(2, 2)))
        assertEquals(8, r.cells.size)
        assertTrue(r.cells.none { it.index == CellIndex(0, 0) }) // bom tak pernah terbuka
    }

    @Test fun partialFloodDoesNotClear() {
        // 3x3, bom di dua sudut → klik (2,0) hanya membuka kantong lokal, belum menang.
        val b = Board(LevelConfig(3, 3, 2), 7L, setOf(CellIndex(0, 0), CellIndex(2, 2)))
        val r = assertIs<RevealResult.Revealed>(engine.reveal(b, CellIndex(2, 0)))
        assertTrue(r.cells.size in 1 until b.safeCellCount)
    }

    @Test fun deterministicOrder() {
        // Dua board identik → hasil list identik (urutan stabil (y,x)).
        assertEquals(engine.reveal(board(), CellIndex(2, 2)), engine.reveal(board(), CellIndex(2, 2)))
    }

    @Test fun idempotentReveal() {
        val b = board()
        engine.reveal(b, CellIndex(2, 2)) // buka semua
        val again = assertIs<RevealResult.LevelCleared>(engine.reveal(b, CellIndex(2, 2)))
        assertTrue(again.cells.isEmpty()) // tak ada yang baru
    }
}
